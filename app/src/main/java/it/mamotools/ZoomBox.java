package it.mamotools;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Controlli dello zoom dinamico, usati per le clip e per l'effetto generale.
 * I valori stanno in z = {zoomIniziale, zoomFinale, puntoX, puntoY, morbido(0/1)}.
 */
final class ZoomBox extends LinearLayout {
    static final float MIN = 1f, MAX = 4f;
    private final float[] z;
    private final Runnable onChange;
    private final int off;
    private final TextView value;
    private final TextView[] cells = new TextView[9];
    private final TextView lin, soft;
    private final SeekBar sa, sb;

    ZoomBox(Context c, float[] zz, int offColor, Runnable change) {
        super(c);
        z = zz;
        off = offColor;
        onChange = change;
        setOrientation(VERTICAL);
        value = Ui.text(c, "", 20, Ui.ACCENT, true);
        value.setGravity(Gravity.CENTER);
        addView(value, new LayoutParams(Ui.MATCH, Ui.WRAP));
        sa = bar(c, "Zoom iniziale", 0);
        sb = bar(c, "Zoom finale", 1);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout pad = new LinearLayout(c);
        pad.setOrientation(VERTICAL);
        for (int r = 0; r < 3; r++) {
            LinearLayout pr = new LinearLayout(c);
            for (int q = 0; q < 3; q++) {
                final int idx = r * 3 + q;
                TextView t = new TextView(c);
                t.setGravity(Gravity.CENTER);
                t.setClickable(true);
                t.setOnClickListener(v -> {
                    z[2] = (idx % 3) * 0.5f;
                    z[3] = (idx / 3) * 0.5f;
                    refresh();
                    onChange.run();
                });
                cells[idx] = t;
                pr.addView(t, Ui.lp(c, Ui.dp(c, 44), Ui.dp(c, 44), q == 0 ? 0 : 6, 0));
            }
            pad.addView(pr, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, r == 0 ? 0 : 6));
        }
        row.addView(pad);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        lin = Ui.chip(c, "Lineare");
        soft = Ui.chip(c, "Morbido");
        lin.setTextSize(13);
        soft.setTextSize(13);
        lin.setOnClickListener(v -> {
            z[4] = 0;
            refresh();
            onChange.run();
        });
        soft.setOnClickListener(v -> {
            z[4] = 1;
            refresh();
            onChange.run();
        });
        col.addView(lin, new LayoutParams(Ui.MATCH, Ui.dp(c, Ui.H_CHIP)));
        col.addView(soft, Ui.lp(c, Ui.MATCH, Ui.dp(c, Ui.H_CHIP), 0, 8));
        row.addView(col, Ui.lp(c, 0, Ui.WRAP, 16, 0, 1f));
        addView(row, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 14));
        refresh();
    }

    private SeekBar bar(Context c, String label, final int which) {
        TextView t = Ui.text(c, label, 12, Ui.DIM, false);
        addView(t, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));
        SeekBar s = Ui.seek(c, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sk, int p, boolean user) {
                if (!user) {
                    return;
                }
                z[which] = MIN + p / 10f;
                refresh();
                onChange.run();
            }
        });
        s.setMax(Math.round((MAX - MIN) * 10));
        s.setProgress(Math.round((z[which] - MIN) * 10));
        addView(s, new LayoutParams(Ui.MATCH, Ui.WRAP));
        return s;
    }

    /** Riallinea l'aspetto ai valori (dopo un reset da fuori, per esempio). */
    void refresh() {
        value.setText(String.format(java.util.Locale.US, "%.1f× → %.1f×", z[0], z[1]));
        if (sa != null && sa.getProgress() != Math.round((z[0] - MIN) * 10)) {
            sa.setProgress(Math.round((z[0] - MIN) * 10));
        }
        if (sb != null && sb.getProgress() != Math.round((z[1] - MIN) * 10)) {
            sb.setProgress(Math.round((z[1] - MIN) * 10));
        }
        int sel = Math.round(z[3] * 2) * 3 + Math.round(z[2] * 2);
        for (int i = 0; i < 9; i++) {
            boolean on = i == sel;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(getContext(), 10));
            g.setColor(on ? Ui.ACCENT : off);
            cells[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), g, null));
            cells[i].setText(on ? "◎" : "·");
            cells[i].setTextSize(on ? 18 : 22);
            cells[i].setTypeface(Typeface.DEFAULT_BOLD);
            cells[i].setTextColor(on ? Color.BLACK : Ui.TEXT);
        }
        style(lin, z[4] < 0.5f);
        style(soft, z[4] >= 0.5f);
    }

    private void style(TextView t, boolean on) {
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), Ui.round(getContext(),
                on ? Ui.ACCENT : off, 12), null));
        t.setTextColor(on ? Color.BLACK : Ui.TEXT);
    }

    /** Zoom al progresso p (0..1) con la curva scelta. */
    static float at(float a, float b, float x, boolean soft, double p) {
        return VideoFx.zoomAt(a, b, soft, p);
    }
}
