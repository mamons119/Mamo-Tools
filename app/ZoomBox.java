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
    private final PadView padView;
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

        TextView pl = Ui.text(c, "Punto dello zoom (fotogramma corrente)", 12, Ui.DIM, false);
        addView(pl, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 14));
        padView = new PadView(c);
        addView(padView, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 6));
        LinearLayout col = new LinearLayout(c);
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
        col.addView(lin, Ui.lp(c, 0, Ui.dp(c, Ui.H_CHIP), 0, 0, 1f));
        col.addView(soft, Ui.lp(c, 0, Ui.dp(c, Ui.H_CHIP), 8, 0, 1f));
        addView(col, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));
        refresh();
    }

    private android.graphics.Bitmap frameBase;
    private int fRot = 0;
    private boolean fMh = false, fMv = false;

    /** Fotogramma mostrato nella griglia (come appare di base, senza rotazioni/specchi della clip). */
    void setFrame(android.graphics.Bitmap b) {
        frameBase = b;
        padView.rebuild();
    }

    /** Rotazione (quarti di giro orari) e specchi della clip, per mostrare il fotogramma come nel risultato. */
    void setFrameOrient(int rotQ, boolean mh, boolean mv) {
        if (fRot != rotQ || fMh != mh || fMv != mv) {
            fRot = rotQ;
            fMh = mh;
            fMv = mv;
            padView.rebuild();
        }
    }

    /** Fotogramma con la griglia 3x3: un tocco sceglie la zona (il punto e' il centro della casella). */
    private final class PadView extends android.view.View {
        private android.graphics.Bitmap shown;
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF img = new android.graphics.RectF();

        PadView(Context c) {
            super(c);
        }

        void rebuild() {
            shown = null;
            if (frameBase != null) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                if (fRot != 0) {
                    m.postRotate(fRot * 90);
                }
                if (fMh) {
                    m.postScale(-1, 1);
                }
                if (fMv) {
                    m.postScale(1, -1);
                }
                try {
                    shown = android.graphics.Bitmap.createBitmap(frameBase, 0, 0, frameBase.getWidth(),
                            frameBase.getHeight(), m, true);
                } catch (Exception e) {
                    shown = frameBase;
                }
            }
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            float a = shown != null && shown.getHeight() > 0 ? shown.getWidth() / (float) shown.getHeight() : 16f / 9f;
            int h = Math.round(w / a);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.33f);
            setMeasuredDimension(w, Math.max(10, Math.min(h, maxH)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float vw = getWidth(), vh = getHeight();
            float a = shown != null && shown.getHeight() > 0 ? shown.getWidth() / (float) shown.getHeight() : 16f / 9f;
            float s = Math.min(vw / a, vh);
            float iw = s * a, ih = s, l = (vw - iw) / 2f, t = (vh - ih) / 2f;
            img.set(l, t, l + iw, t + ih);
            float d = getResources().getDisplayMetrics().density;
            if (shown != null) {
                c.drawBitmap(shown, null, img, p);
            } else {
                p.setColor(0xFF2A2F3A);
                c.drawRect(img, p);
            }
            int col = Math.max(0, Math.min(2, (int) (z[2] * 3)));
            int row = Math.max(0, Math.min(2, (int) (z[3] * 3)));
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(0x4D4DD0E1);
            c.drawRect(l + iw * col / 3f, t + ih * row / 3f, l + iw * (col + 1) / 3f, t + ih * (row + 1) / 3f, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0xCCFFFFFF);
            for (int i = 0; i <= 3; i++) {
                c.drawLine(l + iw * i / 3f, t, l + iw * i / 3f, t + ih, p);
                c.drawLine(l, t + ih * i / 3f, l + iw, t + ih * i / 3f, p);
            }
            float cx = l + iw * (col * 2 + 1) / 6f, cy = t + ih * (row * 2 + 1) / 6f;
            p.setColor(Ui.ACCENT);
            p.setStrokeWidth(2.5f * d);
            c.drawCircle(cx, cy, 9f * d, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            c.drawCircle(cx, cy, 2.5f * d, p);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN || act == android.view.MotionEvent.ACTION_MOVE) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                float fx = (e.getX() - img.left) / Math.max(1f, img.width());
                float fy = (e.getY() - img.top) / Math.max(1f, img.height());
                int col = Math.max(0, Math.min(2, (int) (fx * 3)));
                int row = Math.max(0, Math.min(2, (int) (fy * 3)));
                float nx = (col * 2 + 1) / 6f, ny = (row * 2 + 1) / 6f;
                if (Math.abs(nx - z[2]) > 1e-4f || Math.abs(ny - z[3]) > 1e-4f) {
                    z[2] = nx;
                    z[3] = ny;
                    refresh();
                    onChange.run();
                }
                return true;
            }
            return true;
        }
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
        padView.invalidate();
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
