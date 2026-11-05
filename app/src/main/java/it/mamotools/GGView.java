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
        float peak = Math.max(Math.max(maxLeft, maxRight), Math.max(maxAccel, maxBrake));
        while (peak > scaleG * 0.92f && scaleG < 4f) {
            scaleG += 0.5f;
        }
        invalidate();
    }

    void clearMax() {
        maxLeft = maxRight = maxAccel = maxBrake = 0;
        scaleG = 1.5f;
        hn = 0;
        invalidate();
    }

    void setPoints(double[] aLong, double[] aLat, int step) {
        hn = 0;
        hi = 0;
        int n = aLong.length;
        float peak = 0;
        for (int i = 0; i < n; i++) {
            peak = Math.max(peak, (float) Math.max(Math.abs(aLong[i]), Math.abs(aLat[i])));
        }
        scaleG = 1.5f;
        while (peak > scaleG * 0.92f && scaleG < 4f) {
            scaleG += 0.5f;
        }
        int stride = Math.max(1, n / hx.length);
        for (int i = 0; i < n; i += stride) {
            hx[hi] = (float) aLat[i];
            hy[hi] = (float) aLong[i];
            hi = (hi + 1) % hx.length;
            hn = Math.min(hn + 1, hx.length);
        }
        invalidate();
    }

    private static String g(float v) {
        return String.format(java.util.Locale.ITALY, v == Math.round(v) ? "%.0f" : "%.1f", v);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float mx = w / 2f, my = h / 2f;
        float R = Math.min(w, h) / 2f - 20 * d;
        float step = scaleG <= 2.01f ? 0.5f : 1f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1A1D24);
        c.drawCircle(mx, my, R, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(0xFF3A4150);
        for (float gv = step; gv <= scaleG + 0.01f; gv += step) {
            p.setColor(Math.abs(gv - Math.round(gv)) < 0.01f ? 0xFF4A5366 : 0xFF343B4A);
            c.drawCircle(mx, my, R * gv / scaleG, p);
        }
        p.setColor(0xFF3A4150);
        c.drawLine(mx - R, my, mx + R, my, p);
        c.drawLine(mx, my - R, mx, my + R, p);
        // diagonali sottili
        p.setColor(0xFF2B313D);
        float dg = R * 0.7071f;
        c.drawLine(mx - dg, my - dg, mx + dg, my + dg, p);
        c.drawLine(mx - dg, my + dg, mx + dg, my - dg, p);

        // valori degli anelli lungo gli assi
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(9 * d);
        p.setColor(0xFF9AA3B2);
        for (float gv = step; gv <= scaleG + 0.01f; gv += step) {
            String t = g(gv) + (gv >= scaleG - 0.01f || Math.abs(gv - 1f) < 0.01f ? " G" : "");
            float rr = R * gv / scaleG;
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(t, mx + rr - (gv >= scaleG - 0.01f ? 11 * d : 0), my - 3 * d, p);
            c.drawText(g(gv), mx + 11 * d, my + rr + 3 * d, p);
            c.drawText(g(gv), mx + 11 * d, my - rr + 3 * d, p);
        }
        // nomi delle direzioni
        p.setTextSize(10 * d);
        p.setColor(0xFFECEFF4);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("FRENATA", mx, my - R - 6 * d, p);
        c.drawText("ACCELERAZIONE", mx, my + R + 14 * d, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("DX", mx - R - 4 * d, my + 4 * d, p);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("SX", mx + R + 4 * d, my + 4 * d, p);

        // scia
        for (int i = 0; i < hn; i++) {
            int idx = (hi - 1 - i + hx.length * 2) % hx.length;
            float a = 1f - i / (float) hn;
            p.setColor(((int) (a * 150) << 24) | 0x004DD0E1);
            c.drawCircle(mx + hx[idx] / scaleG * R, my + hy[idx] / scaleG * R, 2.2f * d, p);
        }
        // massimi: rombo tratteggiato e valori sui quattro assi
        if (showMax && (maxLeft > 0 || maxRight > 0 || maxAccel > 0 || maxBrake > 0)) {
            float xl = mx + maxLeft / scaleG * R, xr = mx - maxRight / scaleG * R;
            float yb = my - maxBrake / scaleG * R, ya = my + maxAccel / scaleG * R;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * d);
            p.setColor(0xAAFFB74D);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{5 * d, 4 * d}, 0));
            android.graphics.Path pp = new android.graphics.Path();
            pp.moveTo(xl, my);
            pp.lineTo(mx, ya);
            pp.lineTo(xr, my);
            pp.lineTo(mx, yb);
            pp.close();
            c.drawPath(pp, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFB74D);
            c.drawCircle(xl, my, 3.5f * d, p);
            c.drawCircle(xr, my, 3.5f * d, p);
            c.drawCircle(mx, ya, 3.5f * d, p);
            c.drawCircle(mx, yb, 3.5f * d, p);
            p.setTextSize(10 * d);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(g2(maxLeft), Math.min(mx + R - 10 * d, xl + 14 * d), my + 18 * d, p);
            c.drawText(g2(maxRight), Math.max(mx - R + 10 * d, xr - 14 * d), my + 18 * d, p);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(g2(maxAccel), mx + 7 * d, ya + 14 * d > my + R ? ya - 4 * d : ya + 14 * d, p);
            c.drawText(g2(maxBrake), mx + 7 * d, yb - 6 * d < my - R ? yb + 14 * d : yb - 6 * d, p);
        }
        // punto attuale e valore
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF4DD0E1);
        float px = Math.max(-R, Math.min(R, cx / scaleG * R)), py = Math.max(-R, Math.min(R, cy / scaleG * R));
        if (hn > 0 && showMax) {
            c.drawCircle(mx + px, my + py, 6 * d, p);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            c.drawCircle(mx + px, my + py, 6 * d, p);
        }
    }

    private static String g2(float v) {
        return String.format(java.util.Locale.ITALY, "%.2f", v);
    }
}
