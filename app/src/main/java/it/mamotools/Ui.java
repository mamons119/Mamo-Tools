package it.mamotools;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Componenti grafici condivisi dai tool piu' recenti (stesso aspetto scuro e arrotondato). */
final class Ui {

    static final int BG = 0xFF0F1115, CARD = 0xFF1A1D24, CARD2 = 0xFF262B35, ACCENT = 0xFF4DD0E1,
            TEXT = 0xFFECEFF4, DIM = 0xFF9AA3B2, DANGER = 0xFFFF6B6B, OK = 0xFF66BB6A;
    /** Altezze standard (dp): pulsanti d'azione, chip/interruttori, piccoli pulsanti a icona. */
    static final int H_BTN = 46, H_CHIP = 40, ICON_W = 38, ICON_H = 36;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Ui() {
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, int leftDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(c, leftDp);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, int leftDp, int topDp, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.leftMargin = dp(c, leftDp);
        p.topMargin = dp(c, topDp);
        return p;
    }

    /** Pagina scorrevole con titolo e sottotitolo; restituisce il contenitore dove aggiungere. */
    static LinearLayout screen(Activity a, String title, String subtitle) {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 16), dp(a, 36), dp(a, 16), dp(a, 28));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(text(a, title, 28, TEXT, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(a, subtitle, 14, DIM, false);
            sub.setPadding(0, dp(a, 4), 0, dp(a, 16));
            root.addView(sub);
        } else {
            root.addView(new View(a), new LinearLayout.LayoutParams(MATCH, dp(a, 16)));
        }
        a.setContentView(scroll);
        return root;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, CARD, 16));
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16));
        return l;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 15, ACCENT, true);
        t.setPadding(0, 0, 0, dp(c, 10));
        return t;
    }

    /** Bottone arrotondato; da disabilitato diventa semitrasparente. */
    static final class Btn extends Button {
        Btn(Context c) {
            super(c);
        }

        @Override
        public void setEnabled(boolean e) {
            super.setEnabled(e);
            setAlpha(e ? 1f : 0.4f);
        }
    }

    static Button btn(Context c, String label, int bg, int fg, View.OnClickListener l) {
        Button b = new Btn(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(c, bg, 12), null));
        b.setOnClickListener(l);
        return b;
    }

    static TextView chip(Context c, String label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setClickable(true);
        styleChip(c, t, false);
        return t;
    }

    static void styleChip(Context c, TextView t, boolean on) {
        if (t == null) {
            return;
        }
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),
                round(c, on ? ACCENT : CARD2, 12), null));
        t.setTextColor(on ? 0xFF000000 : TEXT);
    }

    static SeekBar seek(Context c, SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(c);
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sb.setOnSeekBarChangeListener(l);
        return sb;
    }

    static abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    static ProgressBar bar(Context c) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setProgressTintList(ColorStateList.valueOf(ACCENT));
        p.setVisibility(View.GONE);
        return p;
    }

    /** Indicatore di caricamento unico: rotellina, testo e (se nota) barra di avanzamento sottile. */
    static final class Loading extends LinearLayout {
        private final TextView label;
        private final ProgressBar pbar;

        Loading(Context c, String text) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            ProgressBar spin = new ProgressBar(c);
            spin.setIndeterminate(true);
            spin.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            addView(spin, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
            label = text(c, text, 14, TEXT, true);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), 0);
            addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));
            pbar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            pbar.setMax(100);
            pbar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            pbar.setVisibility(GONE);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(MATCH, dp(c, 6));
            pl.setMargins(dp(c, 24), dp(c, 12), dp(c, 24), 0);
            addView(pbar, pl);
        }

        /** pct < 0: avanzamento sconosciuto (barra nascosta). */
        void set(String text, int pct) {
            if (text != null) {
                label.setText(text);
            }
            if (pct < 0) {
                pbar.setVisibility(GONE);
            } else {
                pbar.setVisibility(VISIBLE);
                pbar.setProgress(Math.min(100, pct));
            }
        }
    }

    /** Scheda "scegli un file": icona, titolo e sottotitolo, cliccabile. */
    static final class PickCard {
        final LinearLayout view;
        final TextView title, sub;

        PickCard(Context c, String emoji, String titleText, String subText, View.OnClickListener l) {
            view = card(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setClickable(true);
            view.setOnClickListener(l);
            TextView ic = text(c, emoji, 26, TEXT, false);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(round(c, BG, 100));
            view.addView(ic, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(c, 14), 0, dp(c, 8), 0);
            title = text(c, titleText, 17, TEXT, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            sub = text(c, subText, 13, DIM, false);
            col.addView(title);
            col.addView(sub);
            view.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
            view.addView(text(c, "›", 28, DIM, false));
        }
    }

    /** Finestra informativa nello stile dell'app (al posto dell'AlertDialog standard). */
    static void infoDialog(Context c, String title, String message) {
        infoDialog(c, title, message, null);
    }

    static void infoDialog(Context c, String title, String message, final Runnable onClose) {
        final android.app.Dialog d = new android.app.Dialog(c);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(c, CARD, 20));
        box.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 16));
        box.addView(text(c, title, 18, TEXT, true));
        ScrollView sv = new ScrollView(c);
        TextView m = text(c, message, 14, DIM, false);
        m.setLineSpacing(0, 1.15f);
        sv.addView(m);
        box.addView(sv, lp(c, MATCH, WRAP, 0, 12));
        Button ok = btn(c, "Ho capito", ACCENT, 0xFF000000, v -> d.dismiss());
        box.addView(ok, lp(c, MATCH, dp(c, 46), 0, 16));
        if (onClose != null) {
            d.setOnDismissListener(x -> onClose.run());
        }
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            w.setLayout((int) (c.getResources().getDisplayMetrics().widthPixels * 0.9f), WRAP);
        }
        d.show();
    }
}
