package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Andamento dei filtri lungo la traccia, sotto la forma d'onda. Condivide con la forma d'onda zoom, scorrimento,
 * cursore e selezione (legge i valori da lei e le inoltra i gesti), quindi i due grafici si muovono insieme.
 * L'altezza della linea e' la quota di spettro (20 Hz - 20 kHz, scala logaritmica) tagliata di almeno 3 dB
 * dall'insieme dei filtri attivi in quel punto.
 */
final class FilterTrackView extends View {

    private static final int NF = 48;
    private static final double F0 = 20, F1 = 20000;

    private final AudioWaveView wave;
    private final AudioEngine eng;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            cur = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
            base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path(), area = new Path();

    private long[] fa = new long[0], fb = new long[0];
    private double[][] resp = new double[0][];

    FilterTrackView(Context c, AudioWaveView w, AudioEngine e) {
        super(c);
        wave = w;
        eng = e;
        float d = c.getResources().getDisplayMetrics().density;
        line.setColor(0xFFFF9100);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(0x33FF9100);
        fill.setStyle(Paint.Style.FILL);
        cur.setColor(0xFFFFB74D);
        cur.setStrokeWidth(2 * d);
        base.setColor(0x33FFFFFF);
        txt.setColor(Ui.DIM);
        txt.setTextSize(10 * d);
        w.setTwin(this);
    }

    void setFilters(List<AudioEngine.Filt> list) {
        int n = list.size();
        fa = new long[n];
        fb = new long[n];
        resp = new double[n][NF];
        for (int k = 0; k < n; k++) {
            AudioEngine.Filt f = list.get(k);
            fa[k] = f.a;
            fb[k] = f.b;
            AudioEngine.Fx fx = f.fx();
            for (int j = 0; j < NF; j++) {
                resp[k][j] = AudioEngine.responseDb(fx, F0 * Math.pow(F1 / F0, j / (double) (NF - 1)));
            }
        }
        invalidate();
    }

    private float cutAt(long t) {
        int cnt = 0;
        boolean any = false;
        for (int j = 0; j < NF; j++) {
            double db = 0;
            for (int k = 0; k < fa.length; k++) {
                if (t >= fa[k] && t < fb[k]) {
                    db += resp[k][j];
                    any = true;
                }
            }
            if (db <= -3) {
                cnt++;
            }
        }
        return any ? cnt / (float) NF : 0f;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float d = getResources().getDisplayMetrics().density;
        int w = getWidth(), h = getHeight();
        fill.setColor(Ui.BG);
        rect.set(0, 0, w, h);
        cv.drawRoundRect(rect, 12 * d, 12 * d, fill);
        fill.setColor(0x33FF9100);
        long total = eng.total();
        if (total <= 0 || w <= 0) {
            return;
        }
        long vs = wave.viewStart, vl = Math.max(1, wave.viewLen);
        float top = 16 * d, bot = h - 8 * d;
        // selezione
        if (wave.selA >= 0 && wave.selB > wave.selA) {
            float x1 = (wave.selA - vs) / (float) vl * w, x2 = (wave.selB - vs) / (float) vl * w;
            fill.setColor(0x334DD0E1);
            cv.drawRect(Math.max(0, x1), 0, Math.min(w, x2), h, fill);
            fill.setColor(0x33FF9100);
        }
        cv.drawRect(0, bot - 0.5f, w, bot + 0.5f, base);
        // andamento
        path.reset();
        area.reset();
        boolean any = fa.length > 0;
        if (any) {
            double perPx = vl / (double) w;
            for (int x = 0; x < w; x++) {
                long t = vs + (long) ((x + 0.5) * perPx);
                float y = bot - cutAt(t) * (bot - top);
                if (x == 0) {
                    path.moveTo(x, y);
                    area.moveTo(x, bot);
                    area.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    area.lineTo(x, y);
                }
            }
            area.lineTo(w - 1, bot);
            area.close();
            cv.drawPath(area, fill);
            cv.drawPath(path, line);
        }
        txt.setTextAlign(Paint.Align.LEFT);
        cv.drawText(any ? "Filtri" : "Filtri: nessuno", 8 * d, 12 * d, txt);
        // cursore
        long c = wave.cursor;
        if (c >= vs && c <= vs + vl) {
            float x = (c - vs) / (float) vl * w;
            cv.drawLine(x, 0, x, h, cur);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return wave.onTouchEvent(ev);
    }
}
