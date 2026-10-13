package org.snaplivre.app;

import android.content.Context;
import android.os.Looper;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent extraction engine: no original APK code or advertising SDKs. */
public final class MediaEngine {
  private static final Object INITIALIZATION_LOCK = new Object();
  private static volatile boolean initialized;

  private MediaEngine() {}

  public static void ensureInitialized(Context context) throws Exception {
    if (initialized) return;
    if (Looper.myLooper() == Looper.getMainLooper()) {
      throw new IllegalStateException("O mecanismo deve ser iniciado fora da interface.");
    }
    synchronized (INITIALIZATION_LOCK) {
      if (initialized) return;
      Context application = context.getApplicationContext();
      YoutubeDL.getInstance().init(application);
      FFmpeg.getInstance().init(application);
      initialized = true;
    }
  }

  public static List<MediaItem> search(Context context, String query, String source)
      throws Exception {
    ensureInitialized(context);
    if (query == null || query.trim().isEmpty()) return Collections.emptyList();
    String prefix =
        source != null && source.toLowerCase(Locale.ROOT).contains("soundcloud")
            ? "scsearch20:"
            : "ytsearch20:";
    YoutubeDLRequest request = baseRequest(prefix + query.trim());
    request.addOption("--flat-playlist");
    request.addOption("--dump-single-json");
    request.addOption("--skip-download");
    JSONObject root = executeJson(request);
    JSONArray entries = root.optJSONArray("entries");
    List<MediaItem> results = new ArrayList<>();
    if (entries == null) {
      MediaItem item = MediaItem.fromJson(root);
      if (!item.url.isEmpty()) results.add(item);
    } else {
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry == null) continue;
        MediaItem item = MediaItem.fromJson(entry);
        if (item.url.startsWith("https://") || item.url.startsWith("http://")) results.add(item);
      }
    }
    return results;
  }

  public static JSONObject inspect(Context context, String url) throws Exception {
    ensureInitialized(context);
    YoutubeDLRequest request = baseRequest(validatedUrl(url));
    request.addOption("--dump-single-json");
    request.addOption("--no-playlist");
    request.addOption("--skip-download");
    return executeJson(request);
  }

  public static void update(Context context) throws Exception {
    ensureInitialized(context);
    YoutubeDL.getInstance()
        .updateYoutubeDL(context.getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
  }

  public static List<String> qualityLabels(JSONObject info) {
    TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
    JSONArray formats = info == null ? null : info.optJSONArray("formats");
    if (formats != null) {
      for (int i = 0; i < formats.length(); i++) {
        JSONObject format = formats.optJSONObject(i);
        if (format == null
            || "none".equals(format.optString("vcodec"))
            || format.optBoolean("has_drm")) continue;
        int height = format.optInt("height", 0);
        if (height > 0) heights.add(height);
      }
    }
    List<String> labels = new ArrayList<>();
    labels.add("Melhor qualidade");
    for (Integer height : heights) labels.add(height + "p");
    return labels;
  }

  public static void cancel(String id) {
    // The wrapper interpolates IDs in its child-process cleanup command.
    if (id != null && id.matches("[A-Za-z0-9_-]{1,100}"))
      YoutubeDL.getInstance().destroyProcessById(id);
  }

  static YoutubeDLRequest baseRequest(String url) {
    YoutubeDLRequest request = new YoutubeDLRequest(url);
    request.addOption("--ignore-config");
    request.addOption("--no-warnings");
    request.addOption("--socket-timeout", 25);
    request.addOption("--retries", 3);
    return request;
  }

  static String validatedUrl(String url) {
    if (url == null) throw new IllegalArgumentException("Informe um link.");
    String trimmed = url.trim();
    URI parsed;
    try {
      parsed = URI.create(trimmed);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Use um link http ou https válido.", exception);
    }
    if (!("https".equalsIgnoreCase(parsed.getScheme())
            || "http".equalsIgnoreCase(parsed.getScheme()))
        || parsed.getHost() == null
        || parsed.getHost().isEmpty()) {
      throw new IllegalArgumentException("Use um link http ou https válido.");
    }
    return trimmed;
  }

  private static JSONObject executeJson(YoutubeDLRequest request) throws Exception {
    String output =
        YoutubeDL.getInstance().execute(request, "info_" + UUID.randomUUID(), null).getOut();
    return new JSONObject(output.trim());
  }
}
