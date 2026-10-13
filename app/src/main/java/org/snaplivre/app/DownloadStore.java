package org.snaplivre.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small durable queue. Every caller receives a detached job snapshot. */
public final class DownloadStore {
  private static final String PREFERENCES = "download_queue";
  private static final String KEY = "jobs";
  private static boolean restoredThisProcess;

  private DownloadStore() {}

  public static final class Job {
    public String id = "";
    public String url = "";
    public String title = "";
    public String format = "MP4";
    public String status = "queued";
    public String error = "";
    public String uri = "";
    public String pendingUri = "";
    public int quality;
    public float progress;
    public long created;

    public JSONObject toJson() {
      JSONObject object = new JSONObject();
      try {
        object.put("id", id);
        object.put("url", url);
        object.put("title", title);
        object.put("format", format);
        object.put("status", status);
        object.put("error", error);
        object.put("uri", uri);
        object.put("pendingUri", pendingUri);
        object.put("quality", quality);
        object.put("progress", Float.isFinite(progress) ? progress : 0);
        object.put("created", created);
      } catch (JSONException exception) {
        throw new IllegalStateException("Não foi possível registrar o download.", exception);
      }
      return object;
    }

    static Job fromJson(JSONObject data) {
      Job job = new Job();
      job.id = data.optString("id", "");
      job.url = data.optString("url", "");
      job.title = data.optString("title", "Sem título");
      job.format = data.optString("format", "MP4");
      job.status = data.optString("status", "paused");
      job.error = data.optString("error", "");
      job.uri = data.optString("uri", "");
      job.pendingUri = data.optString("pendingUri", "");
      job.quality = Math.max(0, data.optInt("quality"));
      double progress = data.optDouble("progress", 0);
      job.progress = Double.isFinite(progress) ? (float) Math.max(0, Math.min(100, progress)) : 0;
      job.created = data.optLong("created", System.currentTimeMillis());
      return job;
    }
  }

  public static synchronized List<Job> list(Context context) {
    List<Job> jobs = read(context);
    if (!restoredThisProcess) {
      boolean changed = false;
      for (Job job : jobs) {
        if ("running".equals(job.status)) {
          job.status = "paused";
          job.error = "Download interrompido. Toque em retomar.";
          changed = true;
        }
      }
      if (changed) write(context, jobs);
      restoredThisProcess = true;
    }
    jobs.sort(Comparator.comparingLong((Job job) -> job.created).reversed());
    return jobs;
  }

  public static synchronized Job get(Context context, String id) {
    for (Job job : list(context)) if (job.id.equals(id)) return job;
    return null;
  }

  public static synchronized Job create(
      Context context, String url, String title, String format, int quality) {
    Job job = new Job();
    job.id = UUID.randomUUID().toString();
    job.url = MediaEngine.validatedUrl(url);
    job.title = title == null || title.trim().isEmpty() ? "Sem título" : title.trim();
    job.format = format == null ? "MP4" : format.toUpperCase(java.util.Locale.ROOT);
    job.quality = Math.max(0, quality);
    job.created = System.currentTimeMillis();
    save(context, job);
    return job;
  }

  public static synchronized void save(Context context, Job job) {
    List<Job> jobs = list(context);
    int position = -1;
    for (int i = 0; i < jobs.size(); i++)
      if (jobs.get(i).id.equals(job.id)) {
        position = i;
        break;
      }
    if (position < 0) jobs.add(job);
    else jobs.set(position, job);
    write(context, jobs);
  }

  private static List<Job> read(Context context) {
    SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    List<Job> jobs = new ArrayList<>();
    String serialized = preferences.getString(KEY, "[]");
    try {
      JSONArray data = new JSONArray(serialized == null ? "[]" : serialized);
      for (int i = 0; i < data.length(); i++) {
        JSONObject object = data.optJSONObject(i);
        if (object == null) continue;
        Job job = Job.fromJson(object);
        if (job.id.matches("[A-Za-z0-9_-]{1,100}") && !job.url.isEmpty()) jobs.add(job);
      }
    } catch (JSONException exception) {
      // Preserve unreadable data for recovery instead of silently overwriting it.
      throw new IllegalStateException("O histórico de downloads está corrompido.", exception);
    }
    return jobs;
  }

  private static void write(Context context, List<Job> jobs) {
    JSONArray data = new JSONArray();
    for (Job job : jobs) data.put(job.toJson());
    if (!context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, data.toString())
        .commit()) {
      throw new IllegalStateException("Não foi possível salvar o histórico de downloads.");
    }
  }
}
