package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Forma d'onda di un sample con i punti di inizio e fine. */
public class ProdWaveView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private short[] data;
    private int start = 0, end = 1000;
    private int color = 0xFF4DD0E1;
    private float[] mins, maxs;
    private int builtW = -1;

    public ProdWaveView(Context c) {
        super(c);
    }

    void set(short[] d, int start, int end, int color) {
        data = d;
        this.start = start;
        this.end = end;
        this.color = color;
        builtW = -1;
        invalidate();
    }

    void setRange(int start, int end) {
        this.start = start;
        this.end = end;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF14171D);
        c.drawRect(0, 0, w, h, p);
        if (data == null || data.length == 0 || w <= 0) {
            p.setColor(Ui.DIM);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Ui.dp(getContext(), 13));
            c.drawText("Nessun sample", w / 2f, h / 2f, p);
            return;
        }
        if (builtW != w) {
            mins = new float[w];
            maxs = new float[w];
            for (int x = 0; x < w; x++) {
                int a = (int) ((long) data.length * x / w), b = (int) ((long) data.length * (x + 1) / w);
                b = Math.max(b, a + 1);
                short mn = 0, mx = 0;
                int step = Math.max(1, (b - a) / 64);
                for (int i = a; i < b && i < data.length; i += step) {
                    mn = (short) Math.min(mn, data[i]);
                    mx = (short) Math.max(mx, data[i]);
                }
                mins[x] = mn / 32768f;
                maxs[x] = mx / 32768f;
            }
            builtW = w;
        }
        float mid = h / 2f;
        float xs = w * start / 1000f, xe = w * end / 1000f;
        for (int x = 0; x < w; x++) {
            boolean in = x >= xs && x <= xe;
            p.setColor(in ? color : 0xFF3A4150);
            c.drawLine(x, mid - maxs[x] * mid * 0.95f, x, mid - mins[x] * mid * 0.95f - 1, p);
        }
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(Ui.dp(getContext(), 2));
        c.drawLine(xs, 0, xs, h, p);
        c.drawLine(xe, 0, xe, h, p);
    }
}
