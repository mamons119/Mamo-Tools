package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Griglia 4x4 di pad (un pad = un passo), stile campionatore tascabile. */
public class PadGridView extends View {

    interface Listener {
        void onPad(int index);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int[] notes = new int[16];
    private String[] labels = new String[16];
    private int color = 0xFF4DD0E1, playhead = -1, base = 0;
    private Listener listener;
    private int downIdx = -1;

    public PadGridView(Context c) {
        super(c);
        java.util.Arrays.fill(notes, ProdModel.OFF);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** notes: 16 valori (OFF = spento); base = numero del primo passo mostrato (0 o 16). */
    void set(int[] notes, String[] labels, int color, int base) {
        this.notes = notes;
        this.labels = labels;
        this.color = color;
        this.base = base;
        invalidate();
    }

    void setPlayhead(int idx) {
        if (idx != playhead) {
            playhead = idx;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, width);
    }

    private float cell() {
        return getWidth() / 4f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cs = cell(), gap = cs * 0.07f;
        float rad = cs * 0.16f;
        for (int i = 0; i < 16; i++) {
            int row = i / 4, col = i % 4;
            r.set(col * cs + gap, row * cs + gap, (col + 1) * cs - gap, (row + 1) * cs - gap);
            boolean on = notes[i] != ProdModel.OFF;
            boolean head = i == playhead;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? color : (i % 4 == 0 ? 0xFF2E3441 : 0xFF262B35));
            c.drawRoundRect(r, rad, rad, p);
            if (on) {
                p.setColor(0x33FFFFFF);
                c.drawRoundRect(r.left, r.top, r.right, r.top + r.height() * 0.45f, rad, rad, p);
            }
            if (head) {
                p.setColor(on ? 0xAAFFFFFF : 0x40FFFFFF);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cs * 0.04f);
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, rad, rad, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(cs * 0.17f);
            p.setColor(on ? 0x99000000 : 0x66FFFFFF);
            c.drawText(String.valueOf(base + i + 1), r.left + cs * 0.1f, r.top + cs * 0.24f, p);
            if (on && labels[i] != null) {
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(cs * 0.26f);
                p.setFakeBoldText(true);
                p.setColor(0xFF0F1115);
                c.drawText(labels[i], r.centerX(), r.centerY() + cs * 0.12f, p);
                p.setFakeBoldText(false);
            }
        }
    }

    private int hit(MotionEvent e) {
        float cs = cell();
        int col = (int) (e.getX() / cs), row = (int) (e.getY() / cs);
        if (col < 0 || col > 3 || row < 0 || row > 3) {
            return -1;
        }
        return row * 4 + col;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downIdx = hit(e);
                getParent().requestDisallowInterceptTouchEvent(true);
                if (downIdx >= 0 && listener != null) {
                    listener.onPad(downIdx);
                }
                return true;
            default:
                return true;
        }
    }
}
