package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Locale;

/** Livella a bolla: circolare con il telefono disteso, a tubo con il telefono in piedi. */
final class LevelView extends View {

    // valori in g (nelle coordinate dello schermo): x a destra, y in alto, z fuori dallo schermo
    float ax, ay, az;
    float zeroX, zeroY;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    LevelView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    boolean flat() {
        return Math.abs(az) > 0.8f;
    }

    /** Inclinazione in gradi lungo x e y (segno: verso il lato alto). */
    float tiltX() {
        return (float) Math.toDegrees(Math.asin(clamp(ax - zeroX)));
    }

    float tiltY() {
        return (float) Math.toDegrees(Math.asin(clamp(ay - zeroY)));
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawColor(0xFF0F1115);
        boolean flat = flat();
        float tx = tiltX(), ty = tiltY();
        float total = flat ? (float) Math.hypot(tx, ty) : Math.abs(tx);
        boolean ok = total < 0.5f;
        int col = ok ? 0xFF66BB6A : 0xFF4DD0E1;

        // lettura grande
        t.setColor(ok ? 0xFF66BB6A : 0xFFECEFF4);
        t.setTextSize(56 * d);
        t.setFakeBoldText(true);
        String big = String.format(Locale.ITALY, "%.1f°", total);
        cv.drawText(big, (w - t.measureText(big)) / 2f, 90 * d, t);
        t.setFakeBoldText(false);
        t.setTextSize(14 * d);
        t.setColor(0xFF9AA3B2);
        String sub = flat ? String.format(Locale.ITALY, "X %+.1f°   Y %+.1f°", tx, ty)
                : "Inclinazione laterale";
        cv.drawText(sub, (w - t.measureText(sub)) / 2f, 118 * d, t);
        String mode = flat ? "Telefono appoggiato" : "Telefono in piedi";
        cv.drawText(mode, (w - t.measureText(mode)) / 2f, 140 * d, t);

        float cx = w / 2f, cy = h / 2f + 40 * d;
        if (flat) {
            float R = Math.min(w, h) * 0.36f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1A1D24);
            cv.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xFF3A4150);
            cv.drawCircle(cx, cy, R, p);
            cv.drawCircle(cx, cy, R * 0.5f, p);
            cv.drawLine(cx - R, cy, cx + R, cy, p);
            cv.drawLine(cx, cy - R, cx, cy + R, p);
            float br = R * 0.14f;
            p.setColor(col);
            p.setStrokeWidth(3 * d);
            cv.drawCircle(cx, cy, br, p);
            // bolla: fondo scala +-10 gradi
            float maxOff = R - br - 4 * d;
            float ox = tx / 10f * maxOff, oy = -ty / 10f * maxOff;
            float len = (float) Math.hypot(ox, oy);
            if (len > maxOff) {
                ox *= maxOff / len;
                oy *= maxOff / len;
            }
            bubble(cv, cx + ox, cy + oy, br, col);
        } else {
            float tw = w * 0.88f, th = 96 * d;
            r.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1A1D24);
            cv.drawRoundRect(r, th / 2f, th / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xFF3A4150);
            cv.drawRoundRect(r, th / 2f, th / 2f, p);
            float br = th * 0.34f;
            float zone = br + 2 * d;
            p.setColor(col);
            p.setStrokeWidth(3 * d);
            cv.drawLine(cx - zone, r.top + 8 * d, cx - zone, r.bottom - 8 * d, p);
            cv.drawLine(cx + zone, r.top + 8 * d, cx + zone, r.bottom - 8 * d, p);
            float maxOff = tw / 2f - br - th * 0.3f;
            float ox = Math.max(-maxOff, Math.min(maxOff, tx / 10f * maxOff));
            bubble(cv, cx + ox, cy, br, col);
        }
    }

    private void bubble(Canvas cv, float x, float y, float rad, int col) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f,
                new int[]{0xFFFFFFFF, col, (col & 0x00FFFFFF) | 0xAA000000}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, rad, p);
        p.setShader(null);
    }
}
