package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Righello a grandezza reale: centimetri su un bordo, pollici sull'altro, riga di misura mobile. */
final class RulerView extends View {

    float scale = 1f;
    boolean card = false;
    private float marker = -1;
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dm1;

    RulerView(Context c) {
        super(c);
        dm1 = c.getResources().getDisplayMetrics().density;
        tick.setColor(0xFFECEFF4);
        tick.setStrokeWidth(1.5f * dm1);
        txt.setColor(0xFFECEFF4);
        txt.setTextSize(13 * dm1);
        line.setColor(0xFFFFB74D);
        line.setStrokeWidth(2 * dm1);
        fill.setColor(0xFF1A1D24);
    }

    private float pxPerMm(boolean yAxis) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return (yAxis ? m.ydpi : m.xdpi) / 25.4f * scale;
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawColor(0xFF0F1115);
        boolean vert = h >= w;
        float pm = pxPerMm(vert);
        float len = vert ? h : w;

        // centimetri / millimetri sul primo bordo
        int maxMm = (int) (len / pm) + 1;
        for (int mm = 0; mm <= maxMm; mm++) {
            float p = mm * pm;
            float size = (mm % 10 == 0) ? 30 : (mm % 5 == 0) ? 20 : 12;
            size *= dm1;
            if (vert) {
                cv.drawLine(0, p, size, p, tick);
            } else {
                cv.drawLine(p, 0, p, size, tick);
            }
            if (mm % 10 == 0 && mm > 0) {
                String s = String.valueOf(mm / 10);
                if (vert) {
                    cv.drawText(s, size + 6 * dm1, p + txt.getTextSize() / 3, txt);
                } else {
                    cv.drawText(s, p - txt.measureText(s) / 2, size + txt.getTextSize() + 2 * dm1, txt);
                }
            }
        }
        // pollici (1/16) sul bordo opposto
        float pi = pm * 25.4f / 16f;
        int maxK = (int) (len / pi) + 1;
        for (int k = 0; k <= maxK; k++) {
            float p = k * pi;
            float size = (k % 16 == 0) ? 30 : (k % 8 == 0) ? 24 : (k % 4 == 0) ? 18 : (k % 2 == 0) ? 13 : 8;
            size *= dm1;
            if (vert) {
                cv.drawLine(w - size, p, w, p, tick);
            } else {
                cv.drawLine(p, h - size, p, h, tick);
            }
            if (k % 16 == 0 && k > 0) {
                String s = String.valueOf(k / 16);
                if (vert) {
                    cv.drawText(s, w - size - 6 * dm1 - txt.measureText(s), p + txt.getTextSize() / 3, txt);
                } else {
                    cv.drawText(s, p - txt.measureText(s) / 2, h - size - 6 * dm1, txt);
                }
            }
        }
        // etichette unita'
        Paint u = new Paint(txt);
        u.setColor(0xFF4DD0E1);
        if (vert) {
            cv.drawText("cm", 40 * dm1, 18 * dm1, u);
            cv.drawText("in", w - 40 * dm1 - u.measureText("in"), 18 * dm1, u);
        } else {
            cv.drawText("cm", 8 * dm1, 60 * dm1, u);
            cv.drawText("in", 8 * dm1, h - 40 * dm1, u);
        }

        // scheda di riferimento 85,6 x 53,98 mm
        if (card) {
            float lMm = 85.6f, sMm = 53.98f;
            float pa = pxPerMm(vert), pc = pxPerMm(!vert);
            float cw = vert ? sMm * pc : lMm * pa;
            float ch = vert ? lMm * pa : sMm * pc;
            rect.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f);
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
            st.setStyle(Paint.Style.STROKE);
            st.setColor(0xFF4DD0E1);
            st.setStrokeWidth(2 * dm1);
            st.setPathEffect(new DashPathEffect(new float[]{12 * dm1, 8 * dm1}, 0));
            cv.drawRoundRect(rect, 12 * dm1, 12 * dm1, st);
            Paint t2 = new Paint(txt);
            t2.setColor(0xFF4DD0E1);
            t2.setTextSize(12 * dm1);
            String s = "Sagoma di una carta di credito";
            cv.drawText(s, (w - t2.measureText(s)) / 2f, rect.centerY(), t2);
        }

        // riga di misura
        if (marker >= 0) {
            float mm = marker / pm;
            if (vert) {
                cv.drawLine(0, marker, w, marker, line);
            } else {
                cv.drawLine(marker, 0, marker, h, line);
            }
            String s = String.format(Locale.ITALY, "%.1f mm  ·  %.2f cm  ·  %.3f in", mm, mm / 10f, mm / 25.4f);
            Paint t3 = new Paint(Paint.ANTI_ALIAS_FLAG);
            t3.setTextSize(15 * dm1);
            t3.setFakeBoldText(true);
            t3.setColor(0xFFFFB74D);
            float tw = t3.measureText(s) + 20 * dm1, th = 32 * dm1;
            float cx = vert ? w / 2f : Math.max(tw / 2f, Math.min(w - tw / 2f, marker));
            float cy = vert ? Math.max(th, marker - 22 * dm1) : h / 2f;
            rect.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);
            cv.drawRoundRect(rect, 10 * dm1, 10 * dm1, fill);
            cv.drawText(s, rect.left + 10 * dm1, rect.centerY() + t3.getTextSize() / 3, t3);
        } else {
            Paint t4 = new Paint(Paint.ANTI_ALIAS_FLAG);
            t4.setTextSize(13 * dm1);
            t4.setColor(0xFF9AA3B2);
            String s = "Tocca per misurare";
            cv.drawText(s, (w - t4.measureText(s)) / 2f, h / 2f + 60 * dm1, t4);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            marker = getHeight() >= getWidth() ? e.getY() : e.getX();
            invalidate();
        }
        return true;
    }
}
