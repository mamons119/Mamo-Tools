package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;

/** Tachimetro ad arco 0-300 km/h con lancetta (la lancetta si ferma a fondoscala). */
public class SpeedGaugeView extends View {

    static final float MAX = 300f, START = 135f, SWEEP = 270f;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path needle = new Path();
    private float target, shown, maxMark;

    public SpeedGaugeView(Context c) {
        super(c);
    }

    void setSpeed(float kmh) {
        target = Math.max(0, kmh);
        if (target > maxMark) {
            maxMark = target;
        }
        invalidate();
    }

    void resetMax() {
        maxMark = target;
        invalidate();
    }

    private static float angle(float v) {
        return START + SWEEP * Math.min(MAX, Math.max(0, v)) / MAX;
    }

    private static int zone(float v) {
        if (v < 120) {
            return 0xFF4DD0E1;
        }
        if (v < 200) {
            return 0xFFFFC857;
        }
        return 0xFFFF6B6B;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (width * 0.82f));
    }

    @Override
    protected void onDraw(Canvas c) {
        // animazione morbida della lancetta
        float diff = target - shown;
        if (Math.abs(diff) > 0.2f) {
            shown += diff * 0.18f;
            postInvalidateOnAnimation();
        } else {
            shown = target;
        }
        float w = getWidth(), cx = w / 2f, cy = getHeight() * 0.54f;
        float stroke = w * 0.05f;
        float r = Math.min(w / 2f, getHeight() * 0.54f) - stroke * 1.6f;
        rect.set(cx - r, cy - r, cx + r, cy + r);

        // sfondo e traccia
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xFF12151B);
        c.drawCircle(cx, cy, r + stroke * 0.9f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF262B35);
        c.drawArc(rect, START, SWEEP, false, p);

        // arco colorato fino alla velocita' attuale, a tratti per zona
        float cur = Math.min(shown, MAX);
        float[] zs = {0, 120, 200, 300};
        for (int i = 0; i < 3; i++) {
            float a = zs[i], b = Math.min(zs[i + 1], cur);
            if (b > a) {
                p.setColor(zone(a));
                p.setStrokeCap(i == 0 ? Paint.Cap.ROUND : Paint.Cap.BUTT);
                c.drawArc(rect, angle(a), angle(b) - angle(a), false, p);
            }
        }

        // tacche
        p.setStrokeCap(Paint.Cap.BUTT);
        float ro = r - stroke * 0.9f;
        for (int v = 0; v <= 300; v += 10) {
            boolean major = v % 50 == 0;
            double a = Math.toRadians(angle(v));
            float len = major ? stroke * 0.9f : stroke * 0.45f;
            p.setStrokeWidth(major ? Math.max(2f, w * 0.006f) : Math.max(1f, w * 0.003f));
            p.setColor(major ? 0xFFECEFF4 : 0xFF6B7384);
            c.drawLine(cx + (float) Math.cos(a) * ro, cy + (float) Math.sin(a) * ro,
                    cx + (float) Math.cos(a) * (ro - len), cy + (float) Math.sin(a) * (ro - len), p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(w * 0.04f);
        p.setColor(0xFF9AA3B2);
        float rl = ro - stroke * 1.9f;
        for (int v = 0; v <= 300; v += 50) {
            double a = Math.toRadians(angle(v));
            c.drawText(String.valueOf(v), cx + (float) Math.cos(a) * rl,
                    cy + (float) Math.sin(a) * rl + p.getTextSize() * 0.35f, p);
        }

        // marcatore massimo
        if (maxMark > 1) {
            double a = Math.toRadians(angle(maxMark));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(3f, w * 0.008f));
            p.setColor(0xFFFF6B6B);
            c.drawLine(cx + (float) Math.cos(a) * (r + stroke * 0.55f), cy + (float) Math.sin(a) * (r + stroke * 0.55f),
                    cx + (float) Math.cos(a) * (r + stroke * 1.15f), cy + (float) Math.sin(a) * (r + stroke * 1.15f), p);
        }

        // lancetta (si ferma a 300 km/h)
        float na = angle(shown);
        c.save();
        c.rotate(na, cx, cy);
        needle.reset();
        float nl = ro - stroke * 0.2f, nw = w * 0.018f;
        needle.moveTo(nl, 0);
        needle.lineTo(-nl * 0.14f, -nw);
        needle.lineTo(-nl * 0.14f, nw);
        needle.close();
        needle.offset(cx, cy);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFF5252);
        c.drawPath(needle, p);
        c.restore();
        p.setColor(0xFF262B35);
        c.drawCircle(cx, cy, w * 0.045f, p);
        p.setColor(0xFFECEFF4);
        c.drawCircle(cx, cy, w * 0.02f, p);

        // etichetta unita'
        p.setTextSize(w * 0.04f);
        p.setColor(0xFF9AA3B2);
        c.drawText("km/h", cx, cy + r * 0.55f, p);
    }
}
