package local.jdcapture;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Shared native template: warm canvas, ink status card, teal actions. */
final class CaptureUi {
    static final int CANVAS = Color.rgb(242,245,243), INK = Color.rgb(24,44,43);
    static final int TEAL = Color.rgb(0,105,92), MUTED = Color.rgb(93,112,107);
    static final int LINE = Color.rgb(217,226,221), SOFT = Color.rgb(231,240,235);
    private final Context context;
    CaptureUi(Context context) { this.context = context; }
    int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    GradientDrawable surface(int color, int radius, boolean border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (border) d.setStroke(dp(1), LINE); return d;
    }
    LinearLayout stack() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    TextView text(String value, int size, int color) {
        TextView v = new TextView(context); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setLineSpacing(dp(3), 1); return v;
    }
    TextView eyebrow(String value, int color) {
        TextView v = text(value, 11, color); v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); v.setLetterSpacing(.12f); return v;
    }
    LinearLayout card(LinearLayout parent, boolean dark) {
        LinearLayout card = stack(); card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(surface(dark ? INK : Color.WHITE, 20, !dark));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(14);
        parent.addView(card, p); return card;
    }
    void space(LinearLayout parent, int height) { parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height))); }
    void title(LinearLayout parent, String title, String subtitle) {
        TextView t = text(title, 18, INK); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); parent.addView(t);
        if (!subtitle.isEmpty()) { space(parent, 5); parent.addView(text(subtitle, 13, MUTED)); }
        space(parent, 12);
    }
    Button button(String title, boolean primary, boolean destructive) {
        Button b = new Button(context); b.setText(title); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setMinHeight(dp(50)); b.setMinimumHeight(dp(50));
        b.setGravity(Gravity.CENTER); b.setPadding(dp(12), dp(10), dp(12), dp(10));
        int color = primary ? TEAL : destructive ? Color.rgb(250,239,233) : SOFT;
        b.setTextColor(primary ? Color.WHITE : destructive ? Color.rgb(144,67,40) : TEAL);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1800695c), surface(color, 12, false), null));
        b.setStateListAnimator(null); return b;
    }
    EditText input(String hint, String value) {
        EditText v = new EditText(context); v.setHint(hint); v.setText(value); v.setInputType(2); v.setSingleLine(true);
        v.setTextSize(18); v.setTextColor(INK); v.setHintTextColor(MUTED); v.setPadding(dp(14),dp(12),dp(14),dp(12));
        v.setBackground(surface(CANVAS,12,true)); v.setMinHeight(dp(52)); return v;
    }
}
