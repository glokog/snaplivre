package org.snaplivre.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

public class MainActivity extends Activity {
  private final ExecutorService worker = Executors.newFixedThreadPool(2);
  private final ExecutorService imageWorker = Executors.newFixedThreadPool(2);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final android.util.LruCache<String, Bitmap> images = new android.util.LruCache<>(24);
  private LinearLayout shell, content, bottom;
  private EditText search;
  private Spinner source;
  private int tab = 0, generation = 0;
  private String lastQuery = "", lastSource = "YouTube";
  private List<MediaItem> results = new ArrayList<>();
  private boolean searching = false, receiverRegistered = false;
  private String searchError = "";
  private final BroadcastReceiver changed =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (tab == 2) renderDownloads();
        }
      };
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (tab == 2) renderDownloads();
          main.postDelayed(this, 2000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    shell = Ui.column(this);
    shell.setBackgroundColor(Ui.BG);
    setContentView(shell);
    Ui.edge(this, shell);
    content = Ui.column(this);
    shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    bottom = Ui.row(this);
    shell.addView(bottom, Ui.params(this, -1, 66));
    if (state != null) {
      lastQuery = state.getString("query", "");
      lastSource = state.getString("source", "YouTube");
    }
    selectTab(0);
    handleIntent(getIntent());
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("query", lastQuery);
    state.putString("source", lastSource);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
  }

  @Override
  protected void onStart() {
    super.onStart();
    IntentFilter f = new IntentFilter(DownloadService.ACTION_CHANGED);
    androidx.core.content.ContextCompat.registerReceiver(
        this, changed, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    receiverRegistered = true;
    main.post(refresh);
  }

  @Override
  protected void onStop() {
    super.onStop();
    main.removeCallbacks(refresh);
    if (receiverRegistered) {
      unregisterReceiver(changed);
      receiverRegistered = false;
    }
  }

  @Override
  protected void onDestroy() {
    generation++;
    worker.shutdownNow();
    imageWorker.shutdownNow();
    super.onDestroy();
  }

  private void handleIntent(Intent i) {
    String v =
        Intent.ACTION_SEND.equals(i.getAction())
            ? i.getStringExtra(Intent.EXTRA_TEXT)
            : i.getDataString();
    if (v != null) {
      String u = findUrl(v);
      if (u != null) {
        selectTab(0);
        search.setText(u);
        inspect(u);
      } else toast("Compartilhe um link de vídeo ou áudio.");
    }
  }

  private void selectTab(int t) {
    tab = t;
    bottom.removeAllViews();
    String[] names = {"⌂\nInício", "◎\nSites", "↓\nDownloads", "♡\nBiblioteca", "☰\nAjustes"};
    for (int k = 0; k < names.length; k++) {
      final int n = k;
      TextView b = Ui.text(this, names[k], 12, k == t ? Ui.INK : Ui.MUTED, k == t);
      b.setGravity(Gravity.CENTER);
      b.setBackground(Ui.shape(k == t ? 0xFFFFF0BC : Ui.BG, 12, this));
      b.setOnClickListener(v -> selectTab(n));
      bottom.addView(b, new LinearLayout.LayoutParams(0, -1, 1));
    }
    if (t == 0) renderHome();
    else if (t == 1) renderSites();
    else if (t == 2) renderDownloads();
    else if (t == 3) renderLibrary();
    else renderSettings();
  }

  private LinearLayout page(String title, String subtitle) {
    content.removeAllViews();
    LinearLayout header = Ui.column(this);
    header.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 14));
    header.addView(Ui.text(this, title, 26, Ui.INK, true));
    if (subtitle != null) {
      Ui.space(header, 5);
      header.addView(Ui.text(this, subtitle, 13, Ui.MUTED, false));
    }
    content.addView(header);
    ScrollView sc = new ScrollView(this);
    sc.setFillViewport(false);
    LinearLayout body = Ui.column(this);
    body.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 20));
    sc.addView(body);
    content.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
    return body;
  }

  private void renderHome() {
    LinearLayout body = page("SnapLivre", "Seus vídeos e músicas, sem publicidade.");
    LinearLayout searchBox = Ui.column(this);
    searchBox.setBackground(Ui.shape(0xFFF4F1E8, 18, this));
    Ui.pad(searchBox, 12);
    search = new EditText(this);
    search.setSingleLine(true);
    search.setTextSize(15);
    search.setHint("Pesquisar ou colar um link");
    search.setText(lastQuery);
    search.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    search.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    searchBox.addView(search, Ui.params(this, -1, 50));
    LinearLayout actions = Ui.row(this);
    source = new Spinner(this);
    source.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"YouTube", "SoundCloud"}));
    source.setSelection("SoundCloud".equals(lastSource) ? 1 : 0);
    actions.addView(source, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
    actions.addView(
        Ui.button(
            this,
            "Colar",
            false,
            () -> {
              ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
              if (clip.hasPrimaryClip()) {
                CharSequence v = clip.getPrimaryClip().getItemAt(0).coerceToText(this);
                search.setText(v);
                runSearch();
              } else toast("A área de transferência está vazia.");
            }));
    View gap = new View(this);
    actions.addView(gap, Ui.params(this, 8, 1));
    actions.addView(Ui.button(this, "Buscar", true, this::runSearch));
    search.setOnEditorActionListener(
        (v, id, event) -> {
          runSearch();
          return true;
        });
    searchBox.addView(actions);
    body.addView(searchBox);
    Ui.space(body, 20);
    if (searching) {
      loading(body, "Buscando…", "Na primeira busca, o motor de mídia é preparado no aparelho.");
      return;
    }
    if (!searchError.isEmpty()) {
      empty(body, "Não foi possível buscar", searchError);
      body.addView(Ui.button(this, "Tentar novamente", true, this::runSearch));
      return;
    }
    if (!results.isEmpty()) {
      body.addView(Ui.text(this, "Resultados", 19, Ui.INK, true));
      Ui.space(body, 12);
      for (MediaItem item : results) mediaCard(body, item);
      return;
    }
    LinearLayout hero = Ui.column(this);
    hero.setBackground(Ui.shape(0xFFFFF0BC, 20, this));
    Ui.pad(hero, 20);
    hero.addView(Ui.text(this, "Um link. Muitas possibilidades.", 23, Ui.INK, true));
    Ui.space(hero, 8);
    hero.addView(
        Ui.text(
            this,
            "Escolha a qualidade do vídeo ou salve apenas o áudio em MP3 e M4A.",
            15,
            Ui.INK,
            false));
    Ui.space(hero, 16);
    hero.addView(Ui.button(this, "Explorar sites", false, () -> selectTab(1)));
    body.addView(hero);
    Ui.space(body, 24);
    body.addView(Ui.text(this, "Como começar", 19, Ui.INK, true));
    Ui.space(body, 10);
    body.addView(
        Ui.text(
            this,
            "1  Pesquise ou compartilhe um link com o SnapLivre.\n\n"
                + "2  Escolha vídeo ou áudio e a qualidade.\n\n"
                + "3  Acompanhe o download e abra o arquivo salvo.",
            15,
            Ui.MUTED,
            false));
    Ui.space(body, 24);
    List<MediaItem> recent = readItems("history");
    if (!recent.isEmpty()) {
      body.addView(Ui.text(this, "Vistos recentemente", 19, Ui.INK, true));
      Ui.space(body, 12);
      for (int k = 0; k < Math.min(4, recent.size()); k++) mediaCard(body, recent.get(k));
    }
  }

  private void runSearch() {
    String q = search.getText().toString().trim();
    if (q.isEmpty()) {
      toast("Digite uma busca ou cole um link.");
      return;
    }
    lastQuery = q;
    lastSource = source.getSelectedItem().toString();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(search.getWindowToken(), 0);
    String url = findUrl(q);
    if (url != null) {
      inspect(url);
      return;
    }
    int token = ++generation;
    searching = true;
    searchError = "";
    results.clear();
    renderHome();
    worker.execute(
        () -> {
          try {
            List<MediaItem> found = MediaEngine.search(this, q, lastSource);
            main.post(
                () -> {
                  if (token != generation || isFinishing()) return;
                  searching = false;
                  results = found;
                  if (found.isEmpty()) searchError = "Nenhum resultado. Tente outra busca.";
                  if (tab == 0) renderHome();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (token != generation || isFinishing()) return;
                  searching = false;
                  searchError = friendly(e);
                  if (tab == 0) renderHome();
                });
          }
        });
  }

  private void mediaCard(LinearLayout body, MediaItem item) {
    LinearLayout card = Ui.column(this);
    card.setBackground(Ui.shape(android.graphics.Color.WHITE, 16, this));
    Ui.pad(card, 12);
    LinearLayout row = Ui.row(this);
    ImageView cover = new ImageView(this);
    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
    cover.setBackground(Ui.shape(Ui.LINE, 10, this));
    row.addView(cover, Ui.params(this, 108, 70));
    loadImage(cover, item.thumbnail);
    LinearLayout desc = Ui.column(this);
    desc.setPadding(Ui.dp(this, 12), 0, 0, 0);
    TextView title = Ui.text(this, item.title, 15, Ui.INK, true);
    title.setMaxLines(3);
    desc.addView(title);
    Ui.space(desc, 5);
    desc.addView(
        Ui.text(
            this,
            (item.uploader == null ? "" : item.uploader)
                + (item.duration > 0 ? "  ·  " + Ui.duration(item.duration) : ""),
            12,
            Ui.MUTED,
            false));
    row.addView(desc, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(row);
    row.setOnClickListener(v -> inspect(item.url));
    Ui.space(card, 10);
    LinearLayout actions = Ui.row(this);
    actions.addView(
        Ui.button(this, "↓ Baixar", true, () -> inspect(item.url)),
        new LinearLayout.LayoutParams(0, -2, 1));
    TextView favorite =
        Ui.button(
            this,
            isFavorite(item.url) ? "♥" : "♡",
            false,
            () -> {
              toggleFavorite(item);
              if (tab == 3) renderLibrary();
              else if (tab == 0) renderHome();
            });
    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
    fp.leftMargin = Ui.dp(this, 8);
    actions.addView(favorite, fp);
    card.addView(actions);
    body.addView(card);
    Ui.space(body, 12);
  }

  private void inspect(String raw) {
    String url = findUrl(raw);
    if (url == null) {
      toast("Use um link HTTP ou HTTPS válido.");
      return;
    }
    LinearLayout box = Ui.column(this);
    Ui.pad(box, 20);
    ProgressBar p = new ProgressBar(this);
    box.addView(p, Ui.params(this, -1, 48));
    Ui.space(box, 12);
    box.addView(Ui.text(this, "Analisando o link…", 16, Ui.INK, true));
    box.addView(Ui.text(this, "Buscando título e formatos disponíveis.", 13, Ui.MUTED, false));
    AlertDialog loading =
        new AlertDialog.Builder(this).setView(box).setNegativeButton("Fechar", null).create();
    loading.show();
    worker.execute(
        () -> {
          try {
            JSONObject info = MediaEngine.inspect(this, url);
            main.post(
                () -> {
                  if (isFinishing() || !loading.isShowing()) return;
                  loading.dismiss();
                  MediaItem item = MediaItem.fromJson(info);
                  if (item.url == null || item.url.trim().isEmpty()) item.url = url;
                  if (item.title == null || item.title.trim().isEmpty()) item.title = "Mídia";
                  item.info = info;
                  saveItem("history", item);
                  showFormats(item, info);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (isFinishing() || !loading.isShowing()) return;
                  loading.dismiss();
                  new AlertDialog.Builder(this)
                      .setTitle("Não foi possível analisar")
                      .setMessage(friendly(e))
                      .setPositiveButton("OK", null)
                      .setNeutralButton("Abrir no navegador", (d, w) -> browse(url))
                      .show();
                });
          }
        });
  }

  private void showFormats(MediaItem item, JSONObject info) {
    LinearLayout box = Ui.column(this);
    Ui.pad(box, 20);
    box.addView(Ui.text(this, item.title, 20, Ui.INK, true));
    Ui.space(box, 8);
    box.addView(Ui.text(this, item.uploader == null ? "" : item.uploader, 13, Ui.MUTED, false));
    Ui.space(box, 18);
    box.addView(Ui.text(this, "Formato", 14, Ui.INK, true));
    Spinner format = new Spinner(this);
    format.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"Vídeo MP4", "Áudio MP3", "Áudio M4A"}));
    box.addView(format, Ui.params(this, -1, 48));
    Ui.space(box, 12);
    TextView qualityTitle = Ui.text(this, "Qualidade do vídeo", 14, Ui.INK, true);
    box.addView(qualityTitle);
    Spinner quality = new Spinner(this);
    List<String> labels = MediaEngine.qualityLabels(info);
    if (labels.isEmpty()) labels = Collections.singletonList("Melhor qualidade");
    quality.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    box.addView(quality, Ui.params(this, -1, 48));
    format.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> p, View v, int n, long id) {
            quality.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
            qualityTitle.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
          }

          public void onNothingSelected(AdapterView<?> p) {}
        });
    Ui.space(box, 12);
    box.addView(
        Ui.text(this, "Os arquivos serão salvos em Downloads/SnapLivre.", 13, Ui.MUTED, false));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Baixar", null)
            .setNegativeButton("Fechar", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      if (Build.VERSION.SDK_INT < 29
                          && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                              != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(
                            new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 12);
                        toast("Permita salvar arquivos e toque em Baixar novamente.");
                        return;
                      }
                      if (Build.VERSION.SDK_INT >= 33
                          && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                              != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(
                            new String[] {Manifest.permission.POST_NOTIFICATIONS}, 13);
                      String f =
                          format.getSelectedItemPosition() == 1
                              ? "MP3"
                              : format.getSelectedItemPosition() == 2 ? "M4A" : "MP4";
                      String label = quality.getSelectedItem().toString();
                      Matcher m = Pattern.compile("(\\d+)p").matcher(label);
                      int height = m.find() ? Integer.parseInt(m.group(1)) : 0;
                      DownloadStore.Job job =
                          DownloadStore.create(this, item.url, item.title, f, height);
                      DownloadService.enqueue(this, job);
                      dialog.dismiss();
                      selectTab(2);
                      toast("Download adicionado à fila.");
                    }));
    dialog.show();
  }

  private void renderSites() {
    LinearLayout body = page("Sites", "Abra um site ou cole o link da mídia na busca.");
    String[][] sites = {
      {"YouTube", "youtube.com", "https://m.youtube.com", "#FF4A42"},
      {"Instagram", "instagram.com", "https://www.instagram.com", "#C23E9D"},
      {"Facebook", "facebook.com", "https://m.facebook.com", "#1877F2"},
      {"TikTok", "tiktok.com", "https://www.tiktok.com", "#25231D"},
      {"SoundCloud", "soundcloud.com", "https://soundcloud.com", "#FF7700"},
      {"Vimeo", "vimeo.com", "https://vimeo.com", "#16B1D6"},
      {"Dailymotion", "dailymotion.com", "https://www.dailymotion.com", "#166ADB"},
      {"Bandcamp", "bandcamp.com", "https://bandcamp.com", "#40899A"}
    };
    for (String[] site : sites) {
      LinearLayout r = Ui.row(this);
      Ui.pad(r, 14);
      r.setBackground(Ui.shape(android.graphics.Color.WHITE, 16, this));
      TextView icon =
          Ui.text(this, site[0].substring(0, 1), 23, android.graphics.Color.WHITE, true);
      icon.setGravity(Gravity.CENTER);
      icon.setBackground(Ui.shape(android.graphics.Color.parseColor(site[3]), 12, this));
      r.addView(icon, Ui.params(this, 46, 46));
      LinearLayout labels = Ui.column(this);
      labels.setPadding(Ui.dp(this, 14), 0, 0, 0);
      labels.addView(Ui.text(this, site[0], 16, Ui.INK, true));
      labels.addView(Ui.text(this, site[1], 12, Ui.MUTED, false));
      r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      r.addView(Ui.text(this, "›", 26, Ui.MUTED, false));
      r.setOnClickListener(v -> browse(site[2]));
      body.addView(r);
      Ui.space(body, 10);
    }
    Ui.space(body, 12);
    body.addView(
        Ui.text(
            this,
            "A disponibilidade de formatos depende do site e do acesso público ao conteúdo.",
            13,
            Ui.MUTED,
            false));
  }

  private void renderDownloads() {
    LinearLayout body = page("Downloads", "Sua fila e os arquivos salvos no aparelho.");
    List<DownloadStore.Job> jobs = DownloadStore.list(this);
    if (jobs.isEmpty()) {
      empty(body, "Sua fila está vazia", "Pesquise uma mídia ou cole um link para começar.");
      body.addView(Ui.button(this, "Ir para a busca", true, () -> selectTab(0)));
      return;
    }
    for (DownloadStore.Job j : jobs) {
      LinearLayout card = Ui.column(this);
      card.setBackground(Ui.shape(android.graphics.Color.WHITE, 16, this));
      Ui.pad(card, 14);
      card.addView(Ui.text(this, j.title, 16, Ui.INK, true));
      Ui.space(card, 6);
      String status =
          switch (j.status) {
            case "queued" -> "Na fila";
            case "running" -> "Baixando";
            case "paused" -> "Pausado";
            case "complete" -> "Concluído";
            case "failed" -> "Falhou";
            case "cancelled" -> "Cancelado";
            default -> j.status;
          };
      card.addView(
          Ui.text(
              this,
              j.format
                  + (j.quality > 0 ? " · " + j.quality + "p" : "")
                  + "  ·  "
                  + status
                  + ("running".equals(j.status) ? " " + Math.round(j.progress) + "%" : ""),
              13,
              Ui.MUTED,
              false));
      if ("running".equals(j.status) || "queued".equals(j.status)) {
        Ui.space(card, 10);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.round(j.progress));
        bar.setIndeterminate("queued".equals(j.status));
        card.addView(bar, Ui.params(this, -1, 8));
      }
      if (j.error != null && !j.error.isEmpty()) {
        Ui.space(card, 8);
        card.addView(Ui.text(this, j.error, 12, 0xFFAE462C, false));
      }
      Ui.space(card, 12);
      LinearLayout actions = Ui.row(this);
      if ("complete".equals(j.status)) {
        actions.addView(Ui.button(this, "Abrir arquivo", true, () -> openFile(j)));
        View gap = new View(this);
        actions.addView(gap, Ui.params(this, 8, 1));
        actions.addView(Ui.button(this, "Compartilhar", false, () -> shareFile(j)));
      } else if ("running".equals(j.status) || "queued".equals(j.status)) {
        actions.addView(
            Ui.button(
                this,
                "Pausar",
                false,
                () -> {
                  DownloadService.pause(this, j.id);
                  renderDownloads();
                }));
        View gap = new View(this);
        actions.addView(gap, Ui.params(this, 8, 1));
        actions.addView(
            Ui.button(
                this,
                "Cancelar",
                false,
                () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Cancelar download?")
                        .setMessage(j.title)
                        .setPositiveButton(
                            "Cancelar download",
                            (d, w) -> {
                              DownloadService.cancel(this, j.id);
                              renderDownloads();
                            })
                        .setNegativeButton("Continuar", null)
                        .show()));
      } else if ("paused".equals(j.status) || "failed".equals(j.status)) {
        actions.addView(
            Ui.button(
                this,
                "Retomar",
                true,
                () -> {
                  DownloadService.resume(this, j.id);
                  renderDownloads();
                }));
        View gap = new View(this);
        actions.addView(gap, Ui.params(this, 8, 1));
        actions.addView(
            Ui.button(
                this,
                "Cancelar",
                false,
                () -> {
                  DownloadService.cancel(this, j.id);
                  renderDownloads();
                }));
      }
      card.addView(actions);
      body.addView(card);
      Ui.space(body, 12);
    }
  }

  private void renderLibrary() {
    LinearLayout body = page("Biblioteca", "Favoritos e histórico ficam apenas neste aparelho.");
    List<MediaItem> fav = readItems("favorites"), history = readItems("history");
    body.addView(Ui.text(this, "Favoritos", 19, Ui.INK, true));
    Ui.space(body, 12);
    if (fav.isEmpty())
      body.addView(
          Ui.text(this, "Toque no coração de uma mídia para guardá-la aqui.", 14, Ui.MUTED, false));
    else for (MediaItem i : fav) mediaCard(body, i);
    Ui.space(body, 24);
    LinearLayout row = Ui.row(this);
    row.addView(
        Ui.text(this, "Histórico", 19, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(
        Ui.button(
            this,
            "Limpar",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Limpar histórico?")
                    .setPositiveButton(
                        "Limpar",
                        (d, w) -> {
                          getSharedPreferences("library", 0).edit().remove("history").apply();
                          renderLibrary();
                        })
                    .setNegativeButton("Voltar", null)
                    .show()));
    body.addView(row);
    Ui.space(body, 12);
    if (history.isEmpty())
      body.addView(
          Ui.text(this, "Os links que você analisar aparecerão aqui.", 14, Ui.MUTED, false));
    else for (MediaItem i : history) mediaCard(body, i);
  }

  private void renderSettings() {
    LinearLayout body = page("Ajustes", "SnapLivre 1.0.1");
    LinearLayout card = Ui.column(this);
    Ui.pad(card, 16);
    card.setBackground(Ui.shape(android.graphics.Color.WHITE, 16, this));
    card.addView(Ui.text(this, "Downloads", 19, Ui.INK, true));
    Ui.space(card, 12);
    Switch wifi = new Switch(this);
    wifi.setText("Baixar apenas no Wi-Fi");
    wifi.setTextColor(Ui.INK);
    wifi.setChecked(getSharedPreferences("settings", 0).getBoolean("wifi_only", false));
    wifi.setOnCheckedChangeListener(
        (b, on) -> getSharedPreferences("settings", 0).edit().putBoolean("wifi_only", on).apply());
    card.addView(wifi, Ui.params(this, -1, 52));
    Ui.space(card, 8);
    card.addView(
        Ui.text(
            this,
            "Pasta: Downloads/SnapLivre\nVídeo: MP4 · Áudio: MP3 ou M4A",
            14,
            Ui.MUTED,
            false));
    body.addView(card);
    Ui.space(body, 18);
    body.addView(Ui.text(this, "Motor de mídia", 19, Ui.INK, true));
    Ui.space(body, 8);
    body.addView(
        Ui.text(
            this,
            "Atualize os extratores quando um site mudar a forma de disponibilizar seus vídeos.",
            14,
            Ui.MUTED,
            false));
    Ui.space(body, 12);
    body.addView(Ui.button(this, "Atualizar extratores", true, this::updateEngine));
    Ui.space(body, 24);
    body.addView(Ui.text(this, "Privacidade", 19, Ui.INK, true));
    Ui.space(body, 8);
    body.addView(
        Ui.text(
            this,
            "Sem anúncios, cadastro ou telemetria. Buscas e downloads se conectam aos sites"
                + " escolhidos. O navegador bloqueia uma lista de domínios de publicidade e"
                + " rastreamento.",
            14,
            Ui.MUTED,
            false));
    Ui.space(body, 24);
    body.addView(
        Ui.button(
            this,
            "Sobre e licenças",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("SnapLivre")
                    .setMessage(
                        "Aplicativo independente inspirado nos fluxos observados do APK"
                            + " fornecido.\n\n"
                            + "Código próprio: GPL-3.0-or-later.\n"
                            + "youtubedl-android / FFmpeg: GPL-3.0 e licenças dos componentes.\n"
                            + "yt-dlp: Unlicense e licenças dos componentes.\n"
                            + "AndroidX: Apache-2.0.\n\n"
                            + "Código-fonte e atribuições acompanham o projeto.")
                    .setPositiveButton("Fechar", null)
                    .show()));
  }

  private void updateEngine() {
    ProgressDialog p =
        ProgressDialog.show(
            this, "Atualizando", "Consultando a versão estável do yt-dlp…", true, false);
    worker.execute(
        () -> {
          try {
            MediaEngine.update(this);
            main.post(
                () -> {
                  if (isFinishing()) return;
                  p.dismiss();
                  toast("Extratores atualizados.");
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (isFinishing()) return;
                  p.dismiss();
                  new AlertDialog.Builder(this)
                      .setTitle("Falha na atualização")
                      .setMessage(friendly(e))
                      .setPositiveButton("OK", null)
                      .show();
                });
          }
        });
  }

  private void browse(String url) {
    startActivity(new Intent(this, BrowserActivity.class).putExtra("url", url));
  }

  private void openFile(DownloadStore.Job j) {
    if (j.uri == null || j.uri.isEmpty()) {
      toast("O arquivo não está disponível.");
      return;
    }
    try {
      Uri uri = Uri.parse(j.uri);
      String mime = getContentResolver().getType(uri);
      if (mime == null) {
        mime =
            "MP3".equalsIgnoreCase(j.format)
                ? "audio/mpeg"
                : "M4A".equalsIgnoreCase(j.format) ? "audio/mp4" : "video/mp4";
      }
      Intent intent =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, mime)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri(j.title, uri));
      startActivity(intent);
    } catch (ActivityNotFoundException e) {
      toast("Nenhum aplicativo disponível para abrir o arquivo.");
    } catch (SecurityException | IllegalArgumentException e) {
      toast("O arquivo não está disponível.");
    }
  }

  private void shareFile(DownloadStore.Job j) {
    try {
      Uri u = Uri.parse(j.uri);
      Intent i =
          new Intent(Intent.ACTION_SEND)
              .setType("MP4".equalsIgnoreCase(j.format) ? "video/mp4" : "audio/*")
              .putExtra(Intent.EXTRA_STREAM, u)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      i.setClipData(ClipData.newRawUri(j.title, u));
      startActivity(Intent.createChooser(i, "Compartilhar arquivo"));
    } catch (Exception e) {
      toast("O arquivo não está disponível.");
    }
  }

  private List<MediaItem> readItems(String key) {
    List<MediaItem> list = new ArrayList<>();
    try {
      JSONArray a = new JSONArray(getSharedPreferences("library", 0).getString(key, "[]"));
      for (int k = 0; k < a.length(); k++) list.add(MediaItem.fromJson(a.getJSONObject(k)));
    } catch (JSONException ignored) {
    }
    return list;
  }

  private void saveItem(String key, MediaItem item) {
    List<MediaItem> list = readItems(key);
    list.removeIf(v -> Objects.equals(v.url, item.url));
    list.add(0, item);
    writeItems(key, list);
  }

  private void writeItems(String key, List<MediaItem> list) {
    JSONArray a = new JSONArray();
    for (int k = 0; k < Math.min(list.size(), 80); k++) {
      MediaItem i = list.get(k);
      JSONObject j = new JSONObject();
      try {
        j.put("webpage_url", i.url);
        j.put("title", i.title);
        j.put("thumbnail", i.thumbnail);
        j.put("uploader", i.uploader);
        j.put("duration", i.duration);
        a.put(j);
      } catch (JSONException ignored) {
      }
    }
    getSharedPreferences("library", 0).edit().putString(key, a.toString()).apply();
  }

  private boolean isFavorite(String url) {
    for (MediaItem i : readItems("favorites")) if (Objects.equals(i.url, url)) return true;
    return false;
  }

  private void toggleFavorite(MediaItem item) {
    List<MediaItem> list = readItems("favorites");
    boolean removed = list.removeIf(i -> Objects.equals(i.url, item.url));
    if (!removed) list.add(0, item);
    writeItems("favorites", list);
    toast(removed ? "Removido dos favoritos." : "Salvo nos favoritos.");
  }

  private void loadImage(ImageView v, String url) {
    if (url == null || !url.startsWith("https://")) return;
    v.setTag(url);
    Bitmap cached = images.get(url);
    if (cached != null) {
      v.setImageBitmap(cached);
      return;
    }
    imageWorker.execute(
        () -> {
          HttpURLConnection conn = null;
          try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap image = BitmapFactory.decodeStream(conn.getInputStream(), null, options);
            if (image != null) {
              images.put(url, image);
              main.post(
                  () -> {
                    if (url.equals(v.getTag())) v.setImageBitmap(image);
                  });
            }
          } catch (Exception ignored) {
          } finally {
            if (conn != null) conn.disconnect();
          }
        });
  }

  private void loading(LinearLayout body, String title, String detail) {
    ProgressBar p = new ProgressBar(this);
    body.addView(p, Ui.params(this, -1, 48));
    Ui.space(body, 12);
    empty(body, title, detail);
  }

  private void empty(LinearLayout body, String title, String detail) {
    LinearLayout card = Ui.column(this);
    Ui.pad(card, 22);
    card.setBackground(Ui.shape(0xFFF5F2E9, 18, this));
    card.addView(Ui.text(this, title, 20, Ui.INK, true));
    Ui.space(card, 8);
    card.addView(Ui.text(this, detail, 14, Ui.MUTED, false));
    body.addView(card);
    Ui.space(body, 16);
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  static String findUrl(String text) {
    if (text == null) return null;
    Matcher m =
        Pattern.compile("https?://[^\\s<>\"\\[\\]]+", Pattern.CASE_INSENSITIVE).matcher(text);
    if (!m.find()) return null;
    String u = m.group().replaceAll("[),.;]+$", "");
    try {
      URI uri = new URI(u);
      if (uri.getHost() == null || uri.getUserInfo() != null) return null;
      return uri.toASCIIString();
    } catch (Exception e) {
      return null;
    }
  }

  static String friendly(Exception e) {
    String s = e.getMessage();
    if (s == null) s = e.getClass().getSimpleName();
    if (s.contains("Sign in") || s.contains("login") || s.contains("cookies"))
      return "Este conteúdo exige acesso pelo site. Tente um link público ou abra no navegador.";
    if (s.contains("Unsupported URL"))
      return "Este link não é reconhecido pelo extrator. Abra a página e copie o link da mídia.";
    if (s.contains("Unable to download") || s.contains("timed out") || s.contains("resolve"))
      return "Não foi possível conectar ao site. Verifique a conexão e tente novamente.";
    if (s.contains("Requested format"))
      return "Este formato está indisponível. Tente Melhor qualidade ou outro formato.";
    return s.length() > 500 ? s.substring(0, 500) : s;
  }
}
