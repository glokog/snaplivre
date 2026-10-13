package org.snaplivre.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Public metadata used by both the search screen and the download screen. */
public final class MediaItem {
  public String url;
  public String title;
  public String thumbnail;
  public String uploader;
  public long duration;
  public JSONObject info;

  public MediaItem(String url, String title, String thumbnail, String uploader, long duration) {
    this.url = url == null ? "" : url;
    this.title = title == null || title.isEmpty() ? "Sem título" : title;
    this.thumbnail = thumbnail == null ? "" : thumbnail;
    this.uploader = uploader == null ? "" : uploader;
    this.duration = Math.max(0, duration);
  }

  public static MediaItem fromJson(JSONObject data) {
    String url = string(data, "webpage_url", string(data, "original_url", string(data, "url", "")));
    String extractor =
        string(data, "ie_key", string(data, "extractor_key", string(data, "extractor", "")));
    String id = string(data, "id", "");
    if (!url.startsWith("http://")
        && !url.startsWith("https://")
        && extractor.toLowerCase(java.util.Locale.ROOT).contains("youtube")
        && !id.isEmpty()) {
      url = "https://www.youtube.com/watch?v=" + id;
    }
    String thumbnail = string(data, "thumbnail", "");
    JSONArray thumbnails = data.optJSONArray("thumbnails");
    if (thumbnail.isEmpty() && thumbnails != null) {
      for (int i = thumbnails.length() - 1; i >= 0; i--) {
        JSONObject entry = thumbnails.optJSONObject(i);
        if (entry != null && !string(entry, "url", "").isEmpty()) {
          thumbnail = string(entry, "url", "");
          break;
        }
      }
    }
    MediaItem result =
        new MediaItem(
            url,
            string(data, "title", "Sem título"),
            thumbnail,
            string(data, "uploader", string(data, "channel", "")),
            (long) data.optDouble("duration", 0));
    result.info = data;
    return result;
  }

  private static String string(JSONObject data, String key, String fallback) {
    return data.isNull(key) ? fallback : data.optString(key, fallback);
  }
}
