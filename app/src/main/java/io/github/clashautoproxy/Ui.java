package io.github.clashautoproxy;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;

/** All visible controls use the Material 3 theme and semantic color/typography roles. */
final class Ui {
    private Ui() {}
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static void pad(View v, int l, int t, int r, int b) { v.setPadding(dp(v.getContext(), l), dp(v.getContext(), t), dp(v.getContext(), r), dp(v.getContext(), b)); }
    static int color(Context c, int attr) { return MaterialColors.getColor(c, attr, "Material 3 theme required"); }
    static MaterialTextView text(Context c, String label, int appearance, boolean secondary) {
        MaterialTextView v = new MaterialTextView(c); v.setText(label); v.setTextAppearance(appearance);
        v.setTextColor(color(c, secondary ? com.google.android.material.R.attr.colorOnSurfaceVariant : com.google.android.material.R.attr.colorOnSurface));
        return v;
    }
    static MaterialTextView body(Context c, String label) { return text(c, label, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, true); }
    static void heading(LinearLayout p, String label) {
        MaterialTextView v = text(p.getContext(), label, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, false);
        pad(v, 0, 0, 0, 12); p.addView(v);
    }
    static LinearLayout card(AppCompatActivity a, LinearLayout p, String title) {
        MaterialCardView card = (MaterialCardView) a.getLayoutInflater().inflate(R.layout.ui_card, p, false);
        LinearLayout box = column(a); pad(box, 20, 20, 20, 20); card.addView(box); p.addView(card);
        if (title != null) heading(box, title); return box;
    }
    static MaterialButton button(AppCompatActivity a, LinearLayout p, String title, Runnable action) {
        MaterialButton b = (MaterialButton) a.getLayoutInflater().inflate(R.layout.ui_button, p, false);
        b.setText(title); b.setOnClickListener(v -> action.run()); p.addView(b); return b;
    }
    static MaterialSwitch toggle(LinearLayout p, String title, String detail, boolean checked) {
        Context c = p.getContext(); MaterialSwitch s = new MaterialSwitch(c); s.setText(title); s.setChecked(checked);
        s.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        s.setMinHeight(dp(c, 48)); s.setSwitchPadding(dp(c, 16)); p.addView(s, new LinearLayout.LayoutParams(-1, -2));
        MaterialTextView note = body(c, detail); pad(note, 0, 2, 0, 14); p.addView(note); return s;
    }
    static MaterialToolbar toolbar(Context c, LinearLayout root, String title) {
        MaterialToolbar bar = new MaterialToolbar(c); bar.setTitle(title); bar.setMinimumHeight(dp(c, 64));
        bar.setBackgroundColor(color(c, com.google.android.material.R.attr.colorSurface)); root.addView(bar, new LinearLayout.LayoutParams(-1, -2)); return bar;
    }
    static void install(AppCompatActivity a, LinearLayout root) {
        root.setBackgroundColor(color(a, com.google.android.material.R.attr.colorSurface));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        a.setContentView(root); root.requestApplyInsets();
    }
}
