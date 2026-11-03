package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Diagramma G-G (cerchio di aderenza): ogni punto e' la forza sentita dal pilota. */
final class GGView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    float scaleG = 1.5f;
    private final float[] hx = new float[200], hy = new float[200];
    private int hn = 0, hi = 0;
    private float cx, cy;
    float maxLeft, maxRight, maxAccel, maxBrake;
    boolean showMax = true;

    GGView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    /** aLong: + accelerazione; aLat: + curva a sinistra (in g). */
    void push(float aLong, float aLat) {
        // forza sentita: curva a sinistra spinge a destra, frenata spinge in avanti (alto)
        float x = aLat, y = aLong;
        cx = x;
        cy = y;
        hx[hi] = x;
        hy[hi] = y;
        hi = (hi + 1) % hx.length;
        hn = Math.min(hn + 1, hx.length);
        maxLeft = Math.max(maxLeft, aLat);
        maxRight = Math.max(maxRight, -aLat);
        maxAccel = Math.max(maxAccel, aLong);
        maxBrake = Math.max(maxBrake, -aLong);
        invalidate();
    }

    void clearMax() {
        maxLeft = maxRight = maxAccel = maxBrake = 0;
        hn = 0;
        invalidate();
    }

    void setPoints(double[] aLong, double[] aLat, int step) {
        hn = 0;
        hi = 0;
        int n = aLong.length;
        int stride = Math.max(1, n / hx.length);
        for (int i = 0; i < n; i += stride) {
            hx[hi] = (float) aLat[i];
            hy[hi] = (float) aLong[i];
            hi = (hi + 1) % hx.length;
            hn = Math.min(hn + 1, hx.length);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float mx = w / 2f, my = h / 2f;
        float R = Math.min(w, h) / 2f - 6 * d;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1A1D24);
        c.drawCircle(mx, my, R, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(0xFF3A4150);
        for (float g = 0.5f; g <= scaleG + 0.01f; g += 0.5f) {
            c.drawCircle(mx, my, R * g / scaleG, p);
        }
        c.drawLine(mx - R, my, mx + R, my, p);
        c.drawLine(mx, my - R, mx, my + R, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(10 * d);
        p.setColor(0xFF9AA3B2);
        c.drawText("1 G", mx + R / scaleG + 3 * d, my - 3 * d, p);
        c.drawText("frenata", mx - 22 * d, my - R - 0 * d + 12 * d, p);
        c.drawText("accel.", mx - 16 * d, my + R - 4 * d, p);
        // scia
        for (int i = 0; i < hn; i++) {
            int idx = (hi - 1 - i + hx.length * 2) % hx.length;
            float a = 1f - i / (float) hn;
            p.setColor(((int) (a * 150) << 24) | 0x004DD0E1);
            c.drawCircle(mx + hx[idx] / scaleG * R, my + hy[idx] / scaleG * R, 2.2f * d, p);
        }
        p.setColor(0xFFFFB74D);
        float px = Math.max(-R, Math.min(R, cx / scaleG * R)), py = Math.max(-R, Math.min(R, cy / scaleG * R));
        if (hn > 0 && showMax) {
            c.drawCircle(mx + px, my + py, 6 * d, p);
        }
    }
}
