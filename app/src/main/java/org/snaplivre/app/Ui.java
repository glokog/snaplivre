package org.snaplivre.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;

final class Ui {
  static final int BG = 0xFFFFFDF7,
      INK = 0xFF25231D,
      MUTED = 0xFF77746C,
      YELLOW = 0xFFFFCF32,
      LINE = 0xFFECE8DC;

  static int dp(Context c, float v) {
    return Math.round(v * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable shape(int color, float radius, Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static TextView text(Context c, String s, int size, int color, boolean bold) {
    TextView t = new TextView(c);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return t;
  }

  static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(android.view.Gravity.CENTER_VERTICAL);
    return l;
  }

  static void pad(View v, int p) {
    int d = dp(v.getContext(), p);
    v.setPadding(d, d, d, d);
  }

  static TextView button(Context c, String label, boolean primary, Runnable action) {
    TextView b = text(c, label, 14, INK, true);
    b.setGravity(android.view.Gravity.CENTER);
    b.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
    b.setBackground(shape(primary ? YELLOW : 0xFFF3F0E8, 14, c));
    b.setOnClickListener(v -> action.run());
    b.setContentDescription(label);
    return b;
  }

  static LinearLayout.LayoutParams params(Context c, int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : dp(c, w), h < 0 ? h : dp(c, h));
  }

  static void space(LinearLayout l, int h) {
    View v = new View(l.getContext());
    l.addView(v, params(l.getContext(), 1, h));
  }

  static void edge(Activity a, View root) {
    if (Build.VERSION.SDK_INT >= 30) {
      a.getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return in;
          });
    } else a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
  }

  static String duration(long n) {
    if (n <= 0) return "";
    return n >= 3600
        ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", n / 3600, n / 60 % 60, n % 60)
        : String.format(java.util.Locale.ROOT, "%d:%02d", n / 60, n % 60);
  }
}
