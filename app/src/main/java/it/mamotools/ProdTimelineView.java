package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.List;

/** Linea del tempo del brano: blocchi colorati (pattern x ripetizioni), forma d'onda e cursore. */
public class ProdTimelineView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private ProdModel.Song song;
    private float[] peaks;
    private double head = -1; // 0..1
    private String msg = "";

    public ProdTimelineView(Context c) {
        super(c);
    }

    void set(ProdModel.Song s) {
        song = s;
        invalidate();
    }

    void setPeaks(float[] pk, String message) {
        peaks = pk;
        msg = message == null ? "" : message;
        invalidate();
    }

    void setHead(double h) {
        if (Math.abs(h - head) > 0.0005) {
            head = h;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF14171D);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, 12, 12, p);
        if (song == null) {
            return;
        }
        int total = song.songSteps();
        float blocksH = h * 0.42f;
        if (total == 0) {
            p.setColor(Ui.DIM);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Ui.dp(getContext(), 13));
            c.drawText("Aggiungi dei blocchi per comporre il brano", w / 2f, h / 2f, p);
            return;
        }
        float x = 0;
        List<int[]> arr = song.arr;
        for (int[] a : arr) {
            float bw = w * (song.patterns[a[0]].steps * a[1]) / (float) total;
            int col = ProdModel.TRACK_COLORS[a[0] % ProdModel.TRACK_COLORS.length];
            p.setColor(col);
            r.set(x + 1.5f, 4, x + bw - 1.5f, blocksH);
            c.drawRoundRect(r, 8, 8, p);
            p.setColor(0xFF0F1115);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Ui.dp(getContext(), 14));
            p.setFakeBoldText(true);
            String lab = ProdUi.letter(a[0]) + (a[1] > 1 ? " ×" + a[1] : "");
            if (bw > p.measureText(lab) + 8) {
                c.drawText(lab, x + bw / 2, blocksH / 2 + Ui.dp(getContext(), 5), p);
            }
            p.setFakeBoldText(false);
            x += bw;
        }
        // forma d'onda
        float top = blocksH + 8, mid = (top + h - 4) / 2f, amp = (h - 4 - top) / 2f;
        if (peaks != null && peaks.length > 0) {
            p.setColor(0xFF4DD0E1);
            p.setStrokeWidth(1.5f);
            for (int px = 0; px < w; px++) {
                int i = Math.min(peaks.length - 1, (int) ((long) px * peaks.length / w));
                float v = Math.min(1f, peaks[i]) * amp;
                c.drawLine(px, mid - v, px, mid + v + 1, p);
            }
        } else if (!msg.isEmpty()) {
            p.setColor(Ui.DIM);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Ui.dp(getContext(), 12));
            c.drawText(msg, w / 2f, mid + 4, p);
        }
        if (head >= 0) {
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(Ui.dp(getContext(), 2));
            c.drawLine((float) (head * w), 0, (float) (head * w), h, p);
        }
    }
}
