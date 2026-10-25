package com.deepseekharness.app.remote.ui;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;

final class WorkspacePage {
  static int dp(Activity activity, int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  static LinearLayout card(Activity activity, LinearLayout parent) {
    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackgroundResource(R.drawable.bg_card);
    int p = dp(activity, 16);
    card.setPadding(p, p, p, p);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(activity, 12);
    parent.addView(card, lp);
    return card;
  }

  static TextView text(Activity activity, LinearLayout parent, String value, boolean heading) {
    TextView text = new TextView(activity);
    text.setText(value);
    text.setTextColor(activity.getColor(heading ? R.color.text : R.color.text_secondary));
    text.setTextSize(heading ? 18 : 14);
    if (heading) text.setTypeface(null, android.graphics.Typeface.BOLD);
    text.setPadding(0, dp(activity, 5), 0, dp(activity, 7));
    parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
    return text;
  }

  static Button button(
      Activity activity,
      LinearLayout parent,
      String label,
      boolean primary,
      View.OnClickListener listener) {
    Button button = new androidx.appcompat.widget.AppCompatButton(activity);
    button.setText(label);
    button.setAllCaps(false);
    button.setMinHeight(dp(activity, 48));
    button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn);
    button.setTextColor(
        activity.getColorStateList(primary ? R.color.button_primary_text : R.color.button_text));
    button.setOnClickListener(listener);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(activity, 8);
    parent.addView(button, lp);
    return button;
  }

  static void insets(View root) {
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          var bars =
              insets.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    androidx.core.view.ViewCompat.requestApplyInsets(root);
  }
}
