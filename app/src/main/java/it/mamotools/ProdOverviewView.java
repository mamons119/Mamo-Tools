package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Panoramica del pattern: una riga per traccia, una colonna per passo. Toccando una riga la si seleziona. */
public class ProdOverviewView extends View {

    interface Listener {
        void onTrack(int track);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private ProdModel.Pattern pat;
    private ProdModel.Track[] tracks;
    private int sel = 0, head = -1;
    private Listener listener;

    public ProdOverviewView(Context c) {
        super(c);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void set(ProdModel.Pattern pat, ProdModel.Track[] tracks, int sel) {
        this.pat = pat;
        this.tracks = tracks;
        this.sel = sel;
        invalidate();
    }

    void setHead(int h) {
        if (h != head) {
            head = h;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (ProdModel.TRACKS * (width / 40f)) + Ui.dp(getContext(), 4));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pat == null) {
            return;
        }
        int steps = pat.steps;
        float rowH = (getHeight() - Ui.dp(getContext(), 4)) / (float) ProdModel.TRACKS;
        float colW = getWidth() / (float) steps;
        for (int t = 0; t < ProdModel.TRACKS; t++) {
            float y = t * rowH + Ui.dp(getContext(), 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(t == sel ? 0xFF2A303B : 0xFF1F232B);
            r.set(0, y + 1, getWidth(), y + rowH - 1);
            c.drawRoundRect(r, 4, 4, p);
            for (int s = 0; s < steps; s++) {
                if (pat.note[t][s] != ProdModel.OFF) {
                    p.setColor(ProdModel.TRACK_COLORS[t]);
                    r.set(s * colW + 1.5f, y + 2, (s + 1) * colW - 1.5f, y + rowH - 2);
                    c.drawRoundRect(r, 3, 3, p);
                } else if (s % 4 == 0) {
                    p.setColor(0x22FFFFFF);
                    c.drawRect(s * colW + colW * 0.4f, y + rowH * 0.4f, s * colW + colW * 0.6f, y + rowH * 0.6f, p);
                }
            }
        }
        if (head >= 0 && head < steps) {
            p.setColor(0x55FFFFFF);
            c.drawRect(head * colW, 0, (head + 1) * colW, getHeight(), p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
            float rowH = (getHeight() - Ui.dp(getContext(), 4)) / (float) ProdModel.TRACKS;
            int t = (int) ((e.getY() - Ui.dp(getContext(), 2)) / rowH);
            listener.onTrack(Math.max(0, Math.min(ProdModel.TRACKS - 1, t)));
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
