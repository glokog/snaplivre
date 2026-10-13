package org.snaplivre.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kotlin.Unit;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Sequential foreground queue, resumable temporary files and public-media export. */
public final class DownloadService extends Service {
  public static final String ACTION_CHANGED = "org.snaplivre.app.DOWNLOAD_CHANGED";
  private static final String ACTION_ENQUEUE = "org.snaplivre.app.ENQUEUE";
  private static final String ACTION_PAUSE = "org.snaplivre.app.PAUSE";
  private static final String ACTION_RESUME = "org.snaplivre.app.RESUME";
  private static final String ACTION_CANCEL = "org.snaplivre.app.CANCEL";
  private static final String CHANNEL = "downloads";
  private static final int NOTIFICATION_ID = 1;
  private static final String OUTPUT_MARKER = "SNAPLIVRE_OUTPUT=";

  private final Object lock = new Object();
  private final Map<String, Integer> generations = new HashMap<>();
  private final String processSession = UUID.randomUUID().toString().substring(0, 8);
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService cancellationWorker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private boolean draining;
  private boolean destroyed;
  private boolean foreground;
  private String activeId;
  private String activeProcessId;
  private Thread activeThread;
  private long lastProgressUpdate;
  private ConnectivityManager connectivity;
  private ConnectivityManager.NetworkCallback networkCallback;
  private SharedPreferences settings;
  private SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
  private PowerManager.WakeLock wakeLock;

  public static void enqueue(Context context, DownloadStore.Job job) {
    if (job == null) throw new IllegalArgumentException("Download inválido.");
    command(context, ACTION_ENQUEUE, job.id);
  }

  public static void pause(Context context, String id) {
    command(context, ACTION_PAUSE, id);
  }

  public static void resume(Context context, String id) {
    command(context, ACTION_RESUME, id);
  }

  public static void cancel(Context context, String id) {
    command(context, ACTION_CANCEL, id);
  }

  private static void command(Context context, String action, String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("Download inválido.");
    Intent intent = new Intent(context, DownloadService.class).setAction(action).putExtra("id", id);
    context.startForegroundService(intent);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager notifications = getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(
        new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW));
    DownloadStore.list(this); // Restore interrupted jobs to paused once per process.
    PowerManager power = getSystemService(PowerManager.class);
    if (power != null) {
      wakeLock =
          power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":download_queue");
      wakeLock.setReferenceCounted(false);
    }
    settings = getSharedPreferences("settings", MODE_PRIVATE);
    settingsListener =
        (preferences, key) -> {
          if ("wifi_only".equals(key)) main.post(this::checkActiveNetwork);
        };
    settings.registerOnSharedPreferenceChangeListener(settingsListener);
    connectivity = getSystemService(ConnectivityManager.class);
    if (connectivity != null) {
      networkCallback =
          new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
              main.post(DownloadService.this::checkActiveNetwork);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
              main.post(DownloadService.this::checkActiveNetwork);
            }
          };
      try {
        connectivity.registerDefaultNetworkCallback(networkCallback);
      } catch (SecurityException exception) {
        networkCallback = null;
      }
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (!foreground) {
      Notification notification = notification(null);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      } else startForeground(NOTIFICATION_ID, notification);
      foreground = true;
    }
    String action = intent == null ? null : intent.getAction();
    String id = intent == null ? null : intent.getStringExtra("id");
    synchronized (lock) {
      DownloadStore.Job job = id == null ? null : DownloadStore.get(this, id);
      if (job != null && !terminal(job)) {
        if (ACTION_PAUSE.equals(action)) changeToStopped(job, "paused", "");
        else if (ACTION_CANCEL.equals(action)) changeToStopped(job, "cancelled", "");
        else if (ACTION_RESUME.equals(action)
            && ("paused".equals(job.status) || "failed".equals(job.status))) {
          bump(job.id);
          job.status = "queued";
          job.error = "";
          DownloadStore.save(this, job);
          changed(job.id);
        }
        // ENQUEUE refers to an already persisted queued job; duplicate intents are harmless.
      }
      if (!draining && !destroyed) {
        draining = true;
        worker.execute(this::drain);
      }
    }
    return START_STICKY;
  }

  private void drain() {
    try {
      if (wakeLock != null) wakeLock.acquire(6 * 60 * 60 * 1000L);
      while (true) {
        DownloadStore.Job job;
        int generation;
        synchronized (lock) {
          if (destroyed) return;
          cleanupAbandonedExports();
          job = nextQueued();
          if (job == null) return;
          String networkProblem = networkProblem();
          if (networkProblem != null) {
            job.status = "paused";
            job.error = networkProblem;
            DownloadStore.save(this, job);
            changed(job.id);
            continue;
          }
          generation = generations.getOrDefault(job.id, 0);
          activeId = job.id;
          activeProcessId = processId(job.id, generation);
          activeThread = Thread.currentThread();
          lastProgressUpdate = 0;
          job.status = "running";
          job.error = "";
          DownloadStore.save(this, job);
          changed(job.id);
          notifyProgress(job);
        }
        try {
          download(job, generation);
        } catch (Exception exception) {
          synchronized (lock) {
            DownloadStore.Job current = DownloadStore.get(this, job.id);
            if (isCurrent(job.id, generation)
                && current != null
                && "running".equals(current.status)) {
              current.status = "failed";
              current.error = readableError(exception);
              DownloadStore.save(this, current);
              changed(current.id);
            }
          }
        } finally {
          boolean cancelled;
          synchronized (lock) {
            activeId = null;
            activeProcessId = null;
            activeThread = null;
            DownloadStore.Job stopped = DownloadStore.get(this, job.id);
            cancelled = stopped != null && "cancelled".equals(stopped.status);
          }
          // execute has returned; no new generation can resume a cancelled job.
          if (cancelled) deleteTree(workingDirectory(job.id));
          Thread.interrupted(); // A paused task must not interrupt the next queued task.
        }
      }
    } finally {
      if (wakeLock != null && wakeLock.isHeld()) {
        try {
          wakeLock.release();
        } catch (RuntimeException ignored) {
          /* Its timeout may have just elapsed. */
        }
      }
      synchronized (lock) {
        draining = false;
      }
      main.post(this::stopWhenIdle);
    }
  }

  private DownloadStore.Job nextQueued() {
    return DownloadStore.list(this).stream()
        .filter(job -> "queued".equals(job.status))
        .min(Comparator.comparingLong(job -> job.created))
        .orElse(null);
  }

  private void download(DownloadStore.Job job, int generation) throws Exception {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
      throw new IOException("Autorize o armazenamento para salvar na pasta Downloads.");
    }
    MediaEngine.ensureInitialized(this);
    requireCurrent(job.id, generation);
    File directory = workingDirectory(job.id);
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Não foi possível criar a pasta temporária.");
    YoutubeDLRequest request = buildRequest(job, directory);
    String output =
        YoutubeDL.getInstance()
            .execute(
                request,
                processId(job.id, generation),
                (progress, eta, line) -> {
                  updateProgress(job.id, generation, progress, line);
                  return Unit.INSTANCE;
                })
            .getOut();
    requireCurrent(job.id, generation);
    File result = completedOutput(directory, output, job.format);
    if (result == null)
      throw new IOException("O download terminou sem produzir um arquivo de mídia completo.");
    export(job, generation, result);
    // Only remove working files after the public copy and durable completion succeed.
    deleteTree(directory);
  }

  static YoutubeDLRequest buildRequest(DownloadStore.Job job, File directory) {
    YoutubeDLRequest request = MediaEngine.baseRequest(MediaEngine.validatedUrl(job.url));
    request.addOption("--no-playlist");
    request.addOption("--no-simulate");
    request.addOption("--continue");
    request.addOption("--no-mtime");
    request.addOption("--newline");
    request.addOption("--progress");
    request.addOption("--fragment-retries", 5);
    request.addOption("--concurrent-fragments", 1);
    request.addOption("--write-info-json");
    request.addOption("--print", "after_move:" + OUTPUT_MARKER + "%(filepath)j");
    request.addOption("-o", new File(directory, "media.%(ext)s").getAbsolutePath());
    String format = job.format.toLowerCase(Locale.ROOT);
    if ("mp3".equals(format) || "m4a".equals(format)) {
      request.addOption(
          "-f", "m4a".equals(format) ? "bestaudio[ext=m4a]/bestaudio/best" : "bestaudio/best");
      request.addOption("--extract-audio");
      request.addOption("--audio-format", format);
      request.addOption("--audio-quality", "0");
    } else {
      String height = job.quality > 0 ? "[height<=" + job.quality + "]" : "";
      request.addOption(
          "-f",
          "bestvideo[ext=mp4]"
              + height
              + "+bestaudio[ext=m4a]/best[ext=mp4]"
              + height
              + "/bestvideo"
              + height
              + "+bestaudio/best"
              + height);
      request.addOption("--merge-output-format", "mp4");
      request.addOption("--remux-video", "mp4");
    }
    return request;
  }

  private void updateProgress(String id, int generation, float progress, String line) {
    synchronized (lock) {
      if (!isCurrent(id, generation)) return;
      long now = android.os.SystemClock.elapsedRealtime();
      if (now - lastProgressUpdate < 750) return;
      DownloadStore.Job current = DownloadStore.get(this, id);
      if (current == null || !"running".equals(current.status)) return;
      lastProgressUpdate = now;
      if (Float.isFinite(progress) && progress >= 0)
        current.progress = Math.min(99, Math.max(0, progress));
      if (line != null
          && (line.contains("[Merger]")
              || line.contains("[ExtractAudio]")
              || line.contains("[VideoRemuxer]"))) {
        current.progress = 99;
      }
      DownloadStore.save(this, current);
      changed(id);
      notifyProgress(current);
    }
  }

  private void export(DownloadStore.Job job, int generation, File file) throws Exception {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportModern(job, generation, file);
    else exportLegacy(job, generation, file);
  }

  @androidx.annotation.RequiresApi(29)
  private void exportModern(DownloadStore.Job job, int generation, File file) throws Exception {
    ContentResolver resolver = getContentResolver();
    Uri inserted = null;
    boolean completed = false;
    try {
      synchronized (lock) {
        requireCurrent(job.id, generation);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, exportName(job, file));
        values.put(MediaStore.Downloads.MIME_TYPE, mime(file));
        values.put(
            MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SnapLivre");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        inserted = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (inserted == null)
          throw new IOException("Não foi possível criar o arquivo em Downloads.");
        DownloadStore.Job current = DownloadStore.get(this, job.id);
        current.pendingUri = inserted.toString();
        DownloadStore.save(this, current);
      }
      try (InputStream input = new FileInputStream(file);
          OutputStream output = resolver.openOutputStream(inserted, "w")) {
        if (output == null) throw new IOException("Não foi possível abrir o arquivo em Downloads.");
        copy(input, output, job.id, generation);
      }
      synchronized (lock) {
        requireCurrent(job.id, generation);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        if (resolver.update(inserted, values, null, null) != 1)
          throw new IOException("Não foi possível concluir o arquivo em Downloads.");
        markComplete(job.id, inserted);
        completed = true;
      }
    } finally {
      if (!completed && inserted != null) {
        try {
          resolver.delete(inserted, null, null);
        } catch (RuntimeException ignored) {
        }
        synchronized (lock) {
          DownloadStore.Job current = DownloadStore.get(this, job.id);
          if (current != null && inserted.toString().equals(current.pendingUri)) {
            current.pendingUri = "";
            DownloadStore.save(this, current);
          }
        }
      }
    }
  }

  @SuppressWarnings("deprecation")
  private void exportLegacy(DownloadStore.Job job, int generation, File file) throws Exception {
    File folder =
        new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SnapLivre");
    if (!folder.isDirectory() && !folder.mkdirs())
      throw new IOException("Não foi possível criar Downloads/SnapLivre.");
    File target = new File(folder, exportName(job, file));
    File pending = new File(folder, target.getName() + ".pending");
    boolean completed = false;
    try {
      try (InputStream input = new FileInputStream(file);
          OutputStream output = new FileOutputStream(pending)) {
        copy(input, output, job.id, generation);
      }
      synchronized (lock) {
        requireCurrent(job.id, generation);
        if (!pending.renameTo(target))
          throw new IOException("Não foi possível concluir o arquivo em Downloads.");
        markComplete(job.id, FileProvider.getUriForFile(this, getPackageName() + ".files", target));
        completed = true;
      }
      MediaScannerConnection.scanFile(
          this,
          new String[] {target.getAbsolutePath()},
          new String[] {mime(file)},
          (path, uri) -> {
            if (uri == null) return;
            synchronized (lock) {
              DownloadStore.Job current = DownloadStore.get(this, job.id);
              if (current != null && "complete".equals(current.status)) {
                current.uri = uri.toString();
                DownloadStore.save(this, current);
                changed(current.id);
              }
            }
          });
    } finally {
      if (!completed) {
        pending.delete();
        target.delete();
      }
    }
  }

  private void markComplete(String id, Uri uri) {
    DownloadStore.Job current = DownloadStore.get(this, id);
    current.status = "complete";
    current.uri = uri.toString();
    current.pendingUri = "";
    current.error = "";
    current.progress = 100;
    DownloadStore.save(this, current);
    changed(id);
    notifyProgress(current);
  }

  private void copy(InputStream input, OutputStream output, String id, int generation)
      throws Exception {
    byte[] buffer = new byte[128 * 1024];
    int size;
    while ((size = input.read(buffer)) != -1) {
      requireCurrent(id, generation);
      output.write(buffer, 0, size);
    }
    output.flush();
    requireCurrent(id, generation);
  }

  static File completedOutput(File directory, String output, String requestedFormat)
      throws Exception {
    for (String line : output.split("\\r?\\n")) {
      if (!line.startsWith(OUTPUT_MARKER)) continue;
      Object value = new JSONTokener(line.substring(OUTPUT_MARKER.length())).nextValue();
      if (value instanceof String) {
        File file = allowedOutput(directory, new File((String) value), requestedFormat);
        if (file != null) return file;
      }
    }
    File infoFile = new File(directory, "media.info.json");
    if (infoFile.isFile()) {
      JSONObject info =
          new JSONObject(new String(Files.readAllBytes(infoFile.toPath()), StandardCharsets.UTF_8));
      for (String key : new String[] {"filepath", "_filename"}) {
        String path = info.optString(key, "");
        if (path.isEmpty()) continue;
        File file = allowedOutput(directory, new File(path), requestedFormat);
        if (file != null) return file;
      }
      // Postprocessing changes the suffix; the output template fixes the base name.
      String desired = requestedFormat.toLowerCase(Locale.ROOT);
      if (!"mp3".equals(desired) && !"m4a".equals(desired)) desired = "mp4";
      File file =
          allowedOutput(directory, new File(directory, "media." + desired), requestedFormat);
      if (file != null) return file;
    }
    return null;
  }

  private static File allowedOutput(File directory, File candidate, String requestedFormat)
      throws IOException {
    File canonical = candidate.getCanonicalFile();
    if (!directory.getCanonicalFile().equals(canonical.getParentFile())
        || !canonical.isFile()
        || canonical.length() == 0) return null;
    String name = canonical.getName().toLowerCase(Locale.ROOT);
    String ext = extension(canonical);
    if (!name.equals("media." + ext))
      return null; // Excludes fragments, .part, .ytdl and intermediate files.
    String desired = requestedFormat.toLowerCase(Locale.ROOT);
    if ("mp3".equals(desired) || "m4a".equals(desired))
      return desired.equals(ext) ? canonical : null;
    return "mp4".equals(ext) ? canonical : null;
  }

  private void cleanupAbandonedExports() {
    synchronized (lock) {
      for (DownloadStore.Job job : DownloadStore.list(this)) {
        if (!job.pendingUri.isEmpty()) {
          try {
            getContentResolver().delete(Uri.parse(job.pendingUri), null, null);
          } catch (RuntimeException ignored) {
          }
          job.pendingUri = "";
          DownloadStore.save(this, job);
        }
        if ("cancelled".equals(job.status) && !job.id.equals(activeId))
          deleteTree(workingDirectory(job.id));
      }
    }
  }

  private File workingDirectory(String id) {
    File external = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    return new File(external == null ? getFilesDir() : external, "jobs/" + id);
  }

  private void changeToStopped(DownloadStore.Job job, String status, String error) {
    bump(job.id);
    job.status = status;
    job.error = error;
    DownloadStore.save(this, job);
    changed(job.id);
    if (job.id.equals(activeId)) {
      if (activeThread != null) activeThread.interrupt();
      String cancelledProcess = activeProcessId;
      cancellationWorker.execute(() -> MediaEngine.cancel(cancelledProcess));
    }
  }

  private void checkActiveNetwork() {
    synchronized (lock) {
      if (destroyed || activeId == null) return;
      String problem = networkProblem();
      DownloadStore.Job current = DownloadStore.get(this, activeId);
      if (problem != null && current != null && "running".equals(current.status))
        changeToStopped(current, "paused", problem);
    }
  }

  private String networkProblem() {
    if (connectivity == null) return null;
    try {
      Network network = connectivity.getActiveNetwork();
      NetworkCapabilities capabilities =
          network == null ? null : connectivity.getNetworkCapabilities(network);
      if (capabilities == null
          || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return "Sem conexão. Toque em retomar quando a internet voltar.";
      }
      if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("wifi_only", false)
          && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return "Aguardando Wi-Fi. Toque em retomar ao conectar.";
      }
    } catch (SecurityException ignored) {
      /* The extractor will report the connection error. */
    }
    return null;
  }

  private int bump(String id) {
    int next = generations.getOrDefault(id, 0) + 1;
    generations.put(id, next);
    return next;
  }

  private String processId(String id, int generation) {
    return id + "_" + processSession + "_g" + generation;
  }

  private boolean isCurrent(String id, int generation) {
    return !destroyed && generation == generations.getOrDefault(id, 0) && id.equals(activeId);
  }

  private void requireCurrent(String id, int generation) throws InterruptedException {
    synchronized (lock) {
      if (!isCurrent(id, generation) || Thread.currentThread().isInterrupted())
        throw new InterruptedException("Download interrompido.");
    }
  }

  private static boolean terminal(DownloadStore.Job job) {
    return "complete".equals(job.status) || "cancelled".equals(job.status);
  }

  private void changed(String id) {
    sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()).putExtra("id", id));
  }

  private void notifyProgress(DownloadStore.Job job) {
    main.post(
        () -> {
          synchronized (lock) {
            if (!foreground || destroyed) return;
            DownloadStore.Job current = DownloadStore.get(this, job.id);
            if (current != null)
              getSystemService(NotificationManager.class)
                  .notify(NOTIFICATION_ID, notification(current));
          }
        });
  }

  private Notification notification(DownloadStore.Job job) {
    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job == null ? "SnapLivre" : job.title)
            .setContentText(
                job == null
                    ? "Preparando downloads…"
                    : "complete".equals(job.status)
                        ? "Salvo em Downloads/SnapLivre"
                        : job.progress >= 99 ? "Finalizando arquivo…" : "Baixando mídia…")
            .setOnlyAlertOnce(true)
            .setOngoing(job == null || "running".equals(job.status))
            .setCategory(Notification.CATEGORY_PROGRESS);
    Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (launch != null)
      builder.setContentIntent(
          PendingIntent.getActivity(
              this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    if (job != null && "running".equals(job.status)) {
      builder.setProgress(100, Math.round(job.progress), job.progress <= 0);
      builder.addAction(
          new Notification.Action.Builder(
                  android.R.drawable.ic_media_pause,
                  "Pausar",
                  serviceIntent(ACTION_PAUSE, job.id, 1))
              .build());
      builder.addAction(
          new Notification.Action.Builder(
                  android.R.drawable.ic_menu_close_clear_cancel,
                  "Cancelar",
                  serviceIntent(ACTION_CANCEL, job.id, 2))
              .build());
    }
    return builder.build();
  }

  private PendingIntent serviceIntent(String action, String id, int code) {
    Intent intent = new Intent(this, DownloadService.class).setAction(action).putExtra("id", id);
    return PendingIntent.getForegroundService(
        this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private void stopWhenIdle() {
    synchronized (lock) {
      if (destroyed || draining || activeId != null) return;
      if (nextQueued() != null) {
        draining = true;
        worker.execute(this::drain);
        return;
      }
      stopForeground(STOP_FOREGROUND_REMOVE);
      foreground = false;
      stopSelf();
    }
  }

  @Override
  public void onTimeout(int startId, int foregroundServiceType) {
    synchronized (lock) {
      if (activeId != null) {
        DownloadStore.Job job = DownloadStore.get(this, activeId);
        if (job != null && !terminal(job))
          changeToStopped(job, "paused", "Limite do serviço atingido. Toque em retomar.");
      }
      for (DownloadStore.Job job : DownloadStore.list(this)) {
        if ("queued".equals(job.status)) {
          job.status = "paused";
          job.error = "Limite do serviço atingido. Toque em retomar.";
          DownloadStore.save(this, job);
          changed(job.id);
        }
      }
      destroyed = true;
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    foreground = false;
    stopSelf();
  }

  @Override
  public void onDestroy() {
    synchronized (lock) {
      if (activeId != null) {
        DownloadStore.Job job = DownloadStore.get(this, activeId);
        if (job != null && "running".equals(job.status))
          changeToStopped(job, "paused", "Download interrompido. Toque em retomar.");
      }
      destroyed = true;
    }
    if (connectivity != null && networkCallback != null) {
      try {
        connectivity.unregisterNetworkCallback(networkCallback);
      } catch (RuntimeException ignored) {
      }
    }
    if (settings != null && settingsListener != null)
      settings.unregisterOnSharedPreferenceChangeListener(settingsListener);
    worker.shutdownNow();
    cancellationWorker.shutdown();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private static String exportName(DownloadStore.Job job, File file) {
    String title = job.title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    if (title.isEmpty()) title = "Download";
    if (title.length() > 100) title = title.substring(0, 100);
    return title
        + " - "
        + job.id.substring(0, Math.min(8, job.id.length()))
        + "."
        + extension(file);
  }

  private static String extension(File file) {
    String name = file.getName();
    int position = name.lastIndexOf('.');
    return position < 0 ? "" : name.substring(position + 1).toLowerCase(Locale.ROOT);
  }

  private static String mime(File file) {
    switch (extension(file)) {
      case "mp3":
        return "audio/mpeg";
      case "m4a":
        return "audio/mp4";
      default:
        return "video/mp4";
    }
  }

  private static String readableError(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.trim().isEmpty())
      message =
          exception instanceof InterruptedException
              ? "Download interrompido. Toque em retomar."
              : "Não foi possível baixar este conteúdo.";
    message = message.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
    return message.length() > 700 ? message.substring(message.length() - 700) : message;
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }
}
