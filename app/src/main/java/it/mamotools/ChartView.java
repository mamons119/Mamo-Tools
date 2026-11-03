package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Grafico di un canale della sessione nel tempo, con cursore trascinabile. */
final class ChartView extends View {

    interface Listener {
        void onCursor(int index);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;
    private double[] t = new double[0], y = new double[0];
    private int cursor = -1;
    private String unit = "";
    private int color = 0xFF4DD0E1;
    Listener listener;

    ChartView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setData(double[] t, double[] y, String unit, int color) {
        this.t = t;
        this.y = y;
        this.unit = unit;
        this.color = color;
        invalidate();
    }

    void setCursor(int i) {
        cursor = i;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int n = t.length;
        float padL = 44 * d, padR = 8 * d, padT = 10 * d, padB = 22 * d;
        float pw = w - padL - padR, ph = h - padT - padB;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0F1115);
        c.drawRoundRect(0, 0, w, h, 12 * d, 12 * d, p);
        if (n < 2) {
            return;
        }
        double min = 1e18, max = -1e18;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, y[i]);
            max = Math.max(max, y[i]);
        }
        if (max - min < 1e-6) {
            max = min + 1;
        }
        double pad = (max - min) * 0.08;
        min -= pad;
        max += pad;
        double t0 = t[0], t1 = t[n - 1];
        // griglia
        p.setTextSize(10 * d);
        p.setStrokeWidth(1 * d);
        for (int g = 0; g <= 4; g++) {
            float yy = padT + ph * g / 4f;
            p.setColor(0xFF262B35);
            c.drawLine(padL, yy, w - padR, yy, p);
            p.setColor(0xFF9AA3B2);
            double val = max - (max - min) * g / 4.0;
            c.drawText(String.format(Locale.ITALY, Math.abs(max - min) < 5 ? "%.2f" : "%.0f", val), 4 * d, yy + 4 * d, p);
        }
        if (min < 0 && max > 0) {
            float zy = (float) (padT + ph * (max / (max - min)));
            p.setColor(0xFF4A5160);
            p.setStrokeWidth(1.5f * d);
            c.drawLine(padL, zy, w - padR, zy, p);
        }
        for (int g = 0; g <= 4; g++) {
            double ts = t0 + (t1 - t0) * g / 4.0;
            String s = SessionData.fmtTime(ts).replaceAll("\\.\\d+$", "");
            p.setColor(0xFF9AA3B2);
            float xx = padL + pw * g / 4f;
            c.drawText(s, Math.max(0, Math.min(w - p.measureText(s), xx - p.measureText(s) / 2f)), h - 6 * d, p);
        }
        // curva (una colonna per pixel)
        int cols = (int) pw;
        path.reset();
        boolean first = true;
        int pos = 0;
        for (int x = 0; x < cols; x++) {
            double ta = t0 + (t1 - t0) * x / cols, tb = t0 + (t1 - t0) * (x + 1) / cols;
            double sum = 0;
            int cnt = 0;
            while (pos < n && t[pos] < tb) {
                if (t[pos] >= ta) {
                    sum += y[pos];
                    cnt++;
                }
                pos++;
            }
            if (cnt == 0) {
                continue;
            }
            float xx = padL + x, yy = (float) (padT + ph * (max - sum / cnt) / (max - min));
            if (first) {
                path.moveTo(xx, yy);
                first = false;
            } else {
                path.lineTo(xx, yy);
            }
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor(color);
        c.drawPath(path, p);
        // cursore
        if (cursor >= 0 && cursor < n) {
            float xx = (float) (padL + pw * (t[cursor] - t0) / (t1 - t0));
            p.setColor(0xFFFFB74D);
            p.setStrokeWidth(2 * d);
            c.drawLine(xx, padT, xx, padT + ph, p);
            p.setStyle(Paint.Style.FILL);
            float yy = (float) (padT + ph * (max - y[cursor]) / (max - min));
            c.drawCircle(xx, yy, 5 * d, p);
            String s = String.format(Locale.ITALY, "%.2f %s", y[cursor], unit);
            p.setColor(0xFFFFB74D);
            p.setTextSize(12 * d);
            float tx = xx + 8 * d;
            if (tx + p.measureText(s) > w) {
                tx = xx - 8 * d - p.measureText(s);
            }
            c.drawText(s, tx, padT + 14 * d, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int n = t.length;
        if (n < 2) {
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        float padL = 44 * d, pw = getWidth() - padL - 8 * d;
        double f = Math.max(0, Math.min(1, (e.getX() - padL) / pw));
        double tt = t[0] + (t[n - 1] - t[0]) * f;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (t[mid] < tt) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        cursor = lo;
        invalidate();
        if (listener != null) {
            listener.onCursor(lo);
        }
        return true;
    }
}
