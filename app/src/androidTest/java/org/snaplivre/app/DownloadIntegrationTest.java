package org.snaplivre.app;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises bundled Python, yt-dlp, FFmpeg, foreground queue and public export together. */
@RunWith(AndroidJUnit4.class)
public final class DownloadIntegrationTest {
  private Context context;
  private MainActivity activity;
  private LocalMediaServer server;
  private byte[] media;
  private SharedPreferences queue;
  private SharedPreferences settings;
  private String originalQueue;
  private boolean hadWifiSetting;
  private boolean originalWifiOnly;
  private boolean prepared;
  private final List<String> created = new ArrayList<>();
  private final String titlePrefix = "SnapLivre-test-" + UUID.randomUUID();

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue(
        "These tests verify the scoped MediaStore export on Android 10+.",
        Build.VERSION.SDK_INT >= 29);
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertFalse(
        "Run on an idle test installation; an existing download service is active.",
        serviceRunning());
    queue = context.getSharedPreferences("download_queue", Context.MODE_PRIVATE);
    settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    originalQueue = queue.getString("jobs", null);
    hadWifiSetting = settings.contains("wifi_only");
    originalWifiOnly = settings.getBoolean("wifi_only", false);
    assertTrue(queue.edit().putString("jobs", "[]").commit());
    prepared = true;
    assertTrue(settings.edit().putBoolean("wifi_only", false).commit());
    Intent launch =
        new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    activity =
        (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(launch);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    media =
        LocalMediaServer.fixture(
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("fixture-av.mp4"));
    server = new LocalMediaServer();
  }

  @After
  public void tearDown() throws Exception {
    if (!prepared) return;
    try {
      for (String id : created) {
        DownloadStore.Job job = DownloadStore.get(context, id);
        if (job != null && !("complete".equals(job.status) || "cancelled".equals(job.status))) {
          DownloadService.cancel(context, id);
        }
      }
      // Closing HTTP sockets also releases a downloader if a failed assertion interrupted a test.
      if (server != null) server.close();
      context.stopService(new Intent(context, DownloadService.class));
      await("test download service to stop", 20000, () -> !serviceRunning());
      for (String id : created) {
        DownloadStore.Job job = DownloadStore.get(context, id);
        if (job != null) {
          deleteExport(job.uri);
          deleteExport(job.pendingUri);
        }
        deleteTree(workingDirectory(id));
      }
      // Also catch an export inserted immediately before an assertion failed.
      context
          .getContentResolver()
          .delete(
              MediaStore.Downloads.EXTERNAL_CONTENT_URI,
              MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
              new String[] {titlePrefix + "%"});
    } finally {
      if (activity != null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
      SharedPreferences.Editor restoreQueue = queue.edit();
      if (originalQueue == null) restoreQueue.remove("jobs");
      else restoreQueue.putString("jobs", originalQueue);
      assertTrue(restoreQueue.commit());
      SharedPreferences.Editor restoreSettings = settings.edit();
      if (hadWifiSetting) restoreSettings.putBoolean("wifi_only", originalWifiOnly);
      else restoreSettings.remove("wifi_only");
      assertTrue(restoreSettings.commit());
    }
  }

  @Test
  public void mp4InspectionAndExportContainActualVideoAndAudio() throws Exception {
    String url = server.register("/export-video.mp4", media, false);
    JSONObject inspected = MediaEngine.inspect(context, url);
    assertEquals(url, inspected.getString("url"));
    assertEquals("mp4", inspected.getString("ext"));
    DownloadStore.Job completed = awaitComplete(enqueue(url, "MP4"));
    assertExport(completed, "video/mp4", ".mp4");
    assertTracks(completed, "video/avc", "audio/mp4a-latm");
    try (InputStream input =
        context.getContentResolver().openInputStream(Uri.parse(completed.uri))) {
      assertNotNull(input);
      byte[] header = new byte[12];
      assertEquals(header.length, input.read(header));
      assertArrayEquals(
          new byte[] {'f', 't', 'y', 'p'}, new byte[] {header[4], header[5], header[6], header[7]});
    }
    assertFalse(
        "Completed work files must be removed after the durable public export.",
        workingDirectory(completed.id).exists());
  }

  @Test
  public void mp3ExportUsesFfmpegToExtractRealAudio() throws Exception {
    String url = server.register("/extract-audio.mp4", media, false);
    DownloadStore.Job completed = awaitComplete(enqueue(url, "MP3"));
    assertExport(completed, "audio/mpeg", ".mp3");
    assertTracks(completed, "audio/mpeg");
    assertFalse(workingDirectory(completed.id).exists());
  }

  @Test
  public void pausedDownloadResumesWithRangeAndStoppedQueuedJobsRemainStopped() throws Exception {
    String slowUrl = server.register("/resume.mp4", LocalMediaServer.paddedMp4(media), true);
    DownloadStore.Job active = enqueue(slowUrl, "MP4");
    awaitPartial(active.id);

    DownloadStore.Job pausedQueued =
        enqueue(server.register("/queued-paused.mp4", media, false), "MP4");
    DownloadStore.Job cancelledQueued =
        enqueue(server.register("/queued-cancelled.mp4", media, false), "MP4");
    DownloadService.pause(context, pausedQueued.id);
    DownloadService.cancel(context, cancelledQueued.id);
    awaitStatus(pausedQueued.id, "paused", 15000);
    awaitStatus(cancelledQueued.id, "cancelled", 15000);

    DownloadService.pause(context, active.id);
    DownloadStore.Job paused = awaitStatus(active.id, "paused", 15000);
    assertTrue("A resumable part must remain on disk.", partialBytes(active.id) > 0);
    SystemClock.sleep(1500); // Allow process termination and stale progress callbacks to arrive.
    DownloadStore.Job stable = DownloadStore.get(context, active.id);
    assertEquals("paused", stable.status);
    assertEquals(paused.progress, stable.progress, 0f);
    assertEquals("", stable.uri);

    DownloadService.resume(context, active.id);
    DownloadStore.Job completed = awaitComplete(active);
    assertTracks(completed, "video/avc", "audio/mp4a-latm");
    boolean resumedFromOffset = false;
    for (LocalMediaServer.Request request : server.requests("/resume.mp4")) {
      if ("GET".equals(request.method) && request.rangeStart > 0) resumedFromOffset = true;
    }
    assertTrue(
        "yt-dlp must use a nonzero HTTP Range rather than restarting the entire file.",
        resumedFromOffset);
    SystemClock.sleep(750);
    assertEquals("paused", DownloadStore.get(context, pausedQueued.id).status);
    assertEquals("cancelled", DownloadStore.get(context, cancelledQueued.id).status);
    assertTrue(
        "A paused queued item must never reach the extractor.",
        server.requests("/queued-paused.mp4").isEmpty());
    assertTrue(
        "A cancelled queued item must never reach the extractor.",
        server.requests("/queued-cancelled.mp4").isEmpty());
  }

  @Test
  public void cancelledActiveDownloadDoesNotExportAndNextJobCompletes() throws Exception {
    DownloadStore.Job cancelled =
        enqueue(
            server.register("/cancel-active.mp4", LocalMediaServer.paddedMp4(media), true), "MP4");
    awaitPartial(cancelled.id);
    DownloadService.cancel(context, cancelled.id);
    awaitStatus(cancelled.id, "cancelled", 15000);

    DownloadStore.Job next = enqueue(server.register("/after-cancel.mp4", media, false), "MP4");
    assertTracks(awaitComplete(next), "video/avc", "audio/mp4a-latm");
    SystemClock.sleep(1000);
    DownloadStore.Job stable = DownloadStore.get(context, cancelled.id);
    assertEquals("cancelled", stable.status);
    assertEquals("", stable.uri);
    assertEquals("", stable.pendingUri);
    try (Cursor exports =
        context
            .getContentResolver()
            .query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + " = ?",
                new String[] {stable.title + " - " + stable.id.substring(0, 8) + ".mp4"},
                null)) {
      assertNotNull(exports);
      assertEquals("Cancellation must not publish partial media.", 0, exports.getCount());
    }
  }

  private DownloadStore.Job enqueue(String url, String format) {
    DownloadStore.Job job =
        DownloadStore.create(context, url, titlePrefix + "-" + created.size(), format, 0);
    created.add(job.id);
    DownloadService.enqueue(context, job);
    return job;
  }

  private DownloadStore.Job awaitComplete(DownloadStore.Job initial) {
    DownloadStore.Job completed = awaitStatus(initial.id, "complete", 180000);
    assertEquals(100f, completed.progress, 0f);
    assertEquals("", completed.error);
    assertEquals("", completed.pendingUri);
    assertTrue("Public media URI missing.", completed.uri.startsWith("content://"));
    return completed;
  }

  private DownloadStore.Job awaitStatus(String id, String expected, long timeout) {
    await(
        "job " + id + " to become " + expected,
        timeout,
        () -> {
          DownloadStore.Job current = DownloadStore.get(context, id);
          assertNotNull(current);
          if ("failed".equals(current.status) && !"failed".equals(expected))
            fail("Download failed: " + current.error);
          return expected.equals(current.status);
        });
    return DownloadStore.get(context, id);
  }

  private void awaitPartial(String id) {
    await(
        "a running download with a persisted .part file",
        120000,
        () -> {
          DownloadStore.Job current = DownloadStore.get(context, id);
          if ("failed".equals(current.status)) fail("Download failed: " + current.error);
          return "running".equals(current.status) && partialBytes(id) >= 65536;
        });
  }

  private long partialBytes(String id) {
    File[] files = workingDirectory(id).listFiles();
    if (files != null)
      for (File file : files) if (file.getName().endsWith(".part")) return file.length();
    return 0;
  }

  private File workingDirectory(String id) {
    File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    return new File(external == null ? context.getFilesDir() : external, "jobs/" + id);
  }

  private void assertExport(DownloadStore.Job job, String mime, String extension) {
    Uri uri = Uri.parse(job.uri);
    assertEquals(mime, context.getContentResolver().getType(uri));
    try (Cursor row =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {
                  MediaStore.Downloads.DISPLAY_NAME,
                  MediaStore.Downloads.RELATIVE_PATH,
                  MediaStore.Downloads.IS_PENDING,
                  MediaStore.Downloads.SIZE
                },
                null,
                null,
                null)) {
      assertNotNull(row);
      assertTrue(row.moveToFirst());
      assertTrue(row.getString(0).endsWith(extension));
      assertEquals("Download/SnapLivre/", row.getString(1));
      assertEquals("Public export must not remain pending.", 0, row.getInt(2));
      assertTrue(row.getLong(3) > 1000);
    }
  }

  private void assertTracks(DownloadStore.Job job, String... expectedMimes) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(context, Uri.parse(job.uri), null);
      assertEquals(expectedMimes.length, extractor.getTrackCount());
      Set<String> mimes = new HashSet<>();
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        mimes.add(format.getString(MediaFormat.KEY_MIME));
        long duration = format.getLong(MediaFormat.KEY_DURATION);
        assertTrue(
            "Fixture duration must be close to two seconds: " + duration,
            duration >= 1500000 && duration <= 2500000);
        extractor.selectTrack(i);
        assertTrue(
            "A codec track must contain actual readable samples.",
            extractor.readSampleData(ByteBuffer.allocate(256 * 1024), 0) > 0);
        extractor.unselectTrack(i);
      }
      for (String expected : expectedMimes)
        assertTrue("Missing codec " + expected + " in " + mimes, mimes.contains(expected));
    } finally {
      extractor.release();
    }
  }

  @SuppressWarnings("deprecation")
  private boolean serviceRunning() {
    ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    for (ActivityManager.RunningServiceInfo service :
        manager.getRunningServices(Integer.MAX_VALUE)) {
      if (DownloadService.class.getName().equals(service.service.getClassName())) return true;
    }
    return false;
  }

  private void deleteExport(String value) {
    if (value != null && !value.isEmpty())
      context.getContentResolver().delete(Uri.parse(value), null, null);
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    if (file.exists()) assertTrue("Unable to remove test temporary file " + file, file.delete());
  }

  private static void await(String description, long timeout, BooleanSupplier condition) {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    do {
      if (condition.getAsBoolean()) return;
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < deadline);
    fail("Timed out waiting for " + description);
  }
}
