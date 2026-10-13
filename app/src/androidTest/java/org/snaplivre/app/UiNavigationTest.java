package org.snaplivre.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UiNavigationTest {
  private MainActivity main;

  @Before
  public void launch() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    main =
        (MainActivity)
            InstrumentationRegistry.getInstrumentation()
                .startActivitySync(
                    new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  @After
  public void finish() {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              if (main != null) main.finish();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  @Test
  public void mainNavigationOpensEveryScreenWithoutNetwork() {
    assertVisibleText(main, "SnapLivre");
    clickText("◎\nSites");
    assertVisibleText(main, "Sites");
    assertVisibleText(main, "SoundCloud");
    clickText("↓\nDownloads");
    assertVisibleText(main, "Downloads");
    clickText("♡\nBiblioteca");
    assertVisibleText(main, "Favoritos");
    assertVisibleText(main, "Histórico");
    clickText("☰\nAjustes");
    assertVisibleText(main, "Baixar apenas no Wi-Fi");
    assertVisibleText(main, "Privacidade");
    clickText("⌂\nInício");
    assertVisibleText(main, "Seus vídeos e músicas, sem publicidade.");
  }

  @Test
  public void sharedLinksStripProseAndPunctuationAndRejectCredentials() {
    assertEquals(
        "https://example.test/video?id=7",
        MainActivity.findUrl("Veja aqui: (https://example.test/video?id=7)."));
    assertEquals(
        "http://127.0.0.1:8123/audio.mp4",
        MainActivity.findUrl("http://127.0.0.1:8123/audio.mp4\nOutro texto"));
    assertNull(MainActivity.findUrl("https://login:secret@example.test/private"));
    assertNull(MainActivity.findUrl("file:///sdcard/movie.mp4"));
    assertNull(MainActivity.findUrl("https:///missing-host"));
    assertNull(MainActivity.findUrl(null));
  }

  private void clickText(String text) {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              TextView button = findText(main.getWindow().getDecorView(), text);
              assertNotNull("Missing navigation control " + text, button);
              assertTrue(button.performClick());
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private static void assertVisibleText(android.app.Activity activity, String text) {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              TextView view = findText(activity.getWindow().getDecorView(), text);
              assertNotNull("Missing screen text " + text, view);
              assertEquals(View.VISIBLE, view.getVisibility());
            });
  }

  private static TextView findText(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText()))
      return (TextView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView match = findText(group.getChildAt(i), text);
        if (match != null) return match;
      }
    }
    return null;
  }
}
