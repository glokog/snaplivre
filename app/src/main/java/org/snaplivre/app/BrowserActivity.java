package org.snaplivre.app;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;

public class BrowserActivity extends Activity {
  private WebView web;
  private EditText address;
  private ProgressBar progress;
  private final List<String> media = new CopyOnWriteArrayList<>();
  private static final String[] BLOCKED = {
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "google-analytics.com",
    "googletagmanager.com",
    "app-measurement.com",
    "facebook.net",
    "adsrvr.org",
    "adnxs.com",
    "taboola.com",
    "outbrain.com",
    "criteo.com",
    "criteo.net",
    "pubmatic.com",
    "rubiconproject.com",
    "amazon-adsystem.com",
    "scorecardresearch.com",
    "hotjar.com",
    "unityads.unity3d.com",
    "applovin.com",
    "vungle.com",
    "mopub.com",
    "chartboost.com",
    "ironsrc.com",
    "adcolony.com",
    "ads.tiktok.com",
    "analytics.tiktok.com",
    "ads.youtube.com"
  };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    setContentView(root);
    Ui.edge(this, root);
    LinearLayout toolbar = Ui.row(this);
    Ui.pad(toolbar, 8);
    toolbar.addView(
        Ui.button(
            this,
            "‹",
            false,
            () -> {
              if (web.canGoBack()) web.goBack();
              else finish();
            }));
    address = new EditText(this);
    address.setSingleLine(true);
    address.setTextSize(13);
    address.setImeOptions(EditorInfo.IME_ACTION_GO);
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    toolbar.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
    address.setOnEditorActionListener(
        (v, id, event) -> {
          String input = address.getText().toString().trim();
          String url = MainActivity.findUrl(input.startsWith("http") ? input : "https://" + input);
          if (url != null) web.loadUrl(url);
          return true;
        });
    toolbar.addView(Ui.button(this, "↻", false, () -> web.reload()));
    root.addView(toolbar);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    root.addView(progress, Ui.params(this, -1, 3));
    web = new WebView(this);
    root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setSupportMultipleWindows(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
    web.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onProgressChanged(WebView v, int n) {
            progress.setProgress(n);
            progress.setVisibility(n == 100 ? View.INVISIBLE : View.VISIBLE);
          }
        });
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String scheme = r.getUrl().getScheme();
            return !"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme);
          }

          @Override
          public void onPageStarted(WebView v, String url, Bitmap icon) {
            media.clear();
            address.setText(url);
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            String host = r.getUrl().getHost();
            if (isBlocked(host))
              return new WebResourceResponse(
                  "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            String u = r.getUrl().toString();
            if (u.matches("(?i).*\\.(mp4|webm|m4a|mp3|m3u8)(\\?.*)?$")
                && media.size() < 20
                && !media.contains(u)) media.add(u);
            return null;
          }
        });
    web.setDownloadListener((url, userAgent, disposition, mime, size) -> offerDownload(url));
    LinearLayout bar = Ui.row(this);
    Ui.pad(bar, 12);
    bar.addView(
        Ui.button(this, "↓ Baixar desta página", true, this::detectMedia),
        new LinearLayout.LayoutParams(-1, -2));
    root.addView(bar);
    if (state == null) web.loadUrl(getIntent().getStringExtra("url"));
    else web.restoreState(state);
  }

  static boolean isBlocked(String host) {
    if (host == null) return false;
    for (String blocked : BLOCKED)
      if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
    return false;
  }

  private void detectMedia() {
    web.evaluateJavascript(
        "JSON.stringify(Array.from(document.querySelectorAll('video, audio, video source, audio"
            + " source')).map(e=>e.currentSrc||e.src).filter(u=>u&&/^https?:/.test(u)))",
        value -> {
          List<String> choices = new ArrayList<>();
          String page = web.getUrl();
          if (page != null) choices.add(page);
          try {
            Object raw = new JSONTokener(value).nextValue();
            JSONArray arr = new JSONArray(raw.toString());
            for (int k = 0; k < arr.length(); k++) {
              String u = arr.getString(k);
              if (!choices.contains(u)) choices.add(u);
            }
          } catch (Exception ignored) {
          }
          for (String u : media) if (!choices.contains(u)) choices.add(u);
          if (choices.isEmpty()) return;
          if (choices.size() == 1) {
            offerDownload(choices.get(0));
            return;
          }
          String[] labels = new String[choices.size()];
          for (int k = 0; k < labels.length; k++)
            labels[k] =
                k == 0
                    ? "Analisar o link da página"
                    : "Mídia detectada "
                        + k
                        + " · "
                        + Uri.parse(choices.get(k)).getLastPathSegment();
          new AlertDialog.Builder(this)
              .setTitle("Escolha o link")
              .setItems(labels, (d, n) -> offerDownload(choices.get(n)))
              .setNegativeButton("Voltar", null)
              .show();
        });
  }

  private void offerDownload(String url) {
    startActivity(
        new Intent(this, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    web.saveState(state);
  }

  @Override
  protected void onPause() {
    super.onPause();
    web.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (web != null) web.onResume();
  }

  @Override
  protected void onDestroy() {
    web.destroy();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (web.canGoBack()) web.goBack();
    else super.onBackPressed();
  }
}
