package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Grafico della risposta del filtro (frequenza in scala logaritmica, livello in dB), calcolato dai biquad reali. */
final class FilterGraphView extends View {

    private static final double F0 = 20, F1 = 20000, DB_TOP = 6, DB_BOT = -48;
    private AudioEngine.Fx fx = new AudioEngine.Fx(AudioEngine.Fx.NONE, 200, 4000, 1, 0);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), lbl = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), area = new Path();

    FilterGraphView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        grid.setColor(0x33FFFFFF);
        grid.setStrokeWidth(d);
        grid.setStyle(Paint.Style.STROKE);
        lbl.setColor(Ui.DIM);
        lbl.setTextSize(10 * d);
        line.setColor(Ui.ACCENT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(0x334DD0E1);
        fill.setStyle(Paint.Style.FILL);
        mark.setColor(0xAAFFFFFF);
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(d);
        mark.setPathEffect(new DashPathEffect(new float[]{6 * d, 5 * d}, 0));
        setBackground(Ui.round(c, Ui.BG, 12));
    }

    void setFx(AudioEngine.Fx f) {
        fx = f;
        invalidate();
    }

    private float xOf(double f, float l, float w) {
        return l + (float) (Math.log(f / F0) / Math.log(F1 / F0)) * w;
    }

    private float yOf(double db, float t, float h) {
        double c = Math.max(DB_BOT, Math.min(DB_TOP, db));
        return t + (float) ((DB_TOP - c) / (DB_TOP - DB_BOT)) * h;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float d = getResources().getDisplayMetrics().density;
        float l = 34 * d, r = 10 * d, t = 10 * d, b = 22 * d;
        float w = getWidth() - l - r, h = getHeight() - t - b;
        if (w <= 0 || h <= 0) {
            return;
        }
        // griglia dB
        for (int db = 0; db >= -48; db -= 12) {
            float y = yOf(db, t, h);
            grid.setAlpha(db == 0 ? 0x77 : 0x33);
            cv.drawLine(l, y, l + w, y, grid);
            lbl.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(db + "", l - 4 * d, y + 3.5f * d, lbl);
        }
        grid.setAlpha(0x33);
        // griglia frequenze
        double[] fs = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
        String[] fn = {"20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k"};
        for (int i = 0; i < fs.length; i++) {
            float x = xOf(fs[i], l, w);
            cv.drawLine(x, t, x, t + h, grid);
            if (i % 2 == 0 || fs[i] == 1000) {
                lbl.setTextAlign(i == 0 ? Paint.Align.LEFT : i == fs.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                cv.drawText(fn[i], x, t + h + 14 * d, lbl);
            }
        }
        // linea a -3 dB
        float y3 = yOf(-3, t, h);
        mark.setAlpha(0x55);
        cv.drawLine(l, y3, l + w, y3, mark);
        // curva
        path.reset();
        area.reset();
        int steps = Math.max(60, (int) (w / (2 * d)));
        for (int i = 0; i <= steps; i++) {
            double f = F0 * Math.pow(F1 / F0, i / (double) steps);
            float x = l + w * i / steps, y = yOf(AudioEngine.responseDb(fx, f), t, h);
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, t + h);
                area.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        area.lineTo(l + w, t + h);
        area.close();
        cv.drawPath(area, fill);
        cv.drawPath(path, line);
        // frequenze di taglio
        mark.setAlpha(0xCC);
        lbl.setColor(Ui.TEXT);
        lbl.setTextAlign(Paint.Align.CENTER);
        if (fx.mode == AudioEngine.Fx.HP || fx.mode == AudioEngine.Fx.BP) {
            drawCut(cv, fx.lo, l, w, t, h, d);
        }
        if (fx.mode == AudioEngine.Fx.LP || fx.mode == AudioEngine.Fx.BP) {
            drawCut(cv, fx.hi, l, w, t, h, d);
        }
        lbl.setColor(Ui.DIM);
        if (fx.mode == AudioEngine.Fx.NONE) {
            lbl.setTextAlign(Paint.Align.CENTER);
            cv.drawText("Nessun filtro: tutte le frequenze passano", l + w / 2, t + h / 2 + 22 * d, lbl);
        }
    }

    private void drawCut(Canvas cv, double f, float l, float w, float t, float h, float d) {
        float x = xOf(f, l, w);
        cv.drawLine(x, t, x, t + h, mark);
        String s = f < 1000 ? Math.round(f) + " Hz" : String.format(java.util.Locale.US, "%.1f kHz", f / 1000);
        float tw = lbl.measureText(s);
        float cx = Math.max(l + tw / 2, Math.min(l + w - tw / 2, x));
        cv.drawText(s, cx, t + 11 * d, lbl);
    }
}
