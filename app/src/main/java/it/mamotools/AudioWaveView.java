package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Forma d'onda con cursore, selezione e zoom a pizzico; un dito sposta il cursore. */
final class AudioWaveView extends View {

    interface Listener {
        void onCursor(long frame, boolean fromUser);

        void onViewport();
    }

    private final AudioEngine eng;
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveSel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path tri = new Path();
    private final ScaleGestureDetector scale;
    private Listener listener;

    long viewStart = 0, viewLen = AudioEngine.RATE * 10L;
    long cursor = 0;
    long selA = -1, selB = -1;
    private boolean scaling = false;

    AudioWaveView(Context c, AudioEngine e) {
        super(c);
        eng = e;
        wave.setColor(Ui.ACCENT);
        wave.setStrokeWidth(Ui.dp(c, 1));
        waveSel.setColor(0xFFFFFFFF);
        waveSel.setStrokeWidth(Ui.dp(c, 1));
        line.setColor(0xFFFFB74D);
        line.setStrokeWidth(Ui.dp(c, 2));
        txt.setColor(Ui.DIM);
        txt.setTextSize(Ui.dp(c, 10));
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                long total = eng.total();
                if (total <= 0 || getWidth() == 0) {
                    return true;
                }
                float fx = d.getFocusX() / getWidth();
                long anchor = viewStart + (long) (fx * viewLen);
                long nl = (long) (viewLen / d.getScaleFactor());
                setViewLen(nl, anchor, fx);
                return true;
            }

            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                scaling = true;
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
            }
        });
    }

    private View twin;

    /** Vista che mostra lo stesso tratto (il grafico dei filtri): si ridisegna insieme a questa. */
    void setTwin(View v) {
        twin = v;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (twin != null) {
            twin.invalidate();
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    long minLen() {
        return AudioEngine.RATE; // 1 secondo
    }

    void setViewLen(long len, long anchor, float anchorFrac) {
        long total = Math.max(1, eng.total());
        len = Math.max(minLen(), Math.min(len, total));
        viewLen = len;
        viewStart = anchor - (long) (anchorFrac * len);
        clampView();
        invalidate();
        if (listener != null) {
            listener.onViewport();
        }
    }

    void zoom(float factor) {
        long anchor = cursor;
        if (anchor < viewStart || anchor > viewStart + viewLen) {
            anchor = viewStart + viewLen / 2;
        }
        float frac = (anchor - viewStart) / (float) Math.max(1, viewLen);
        setViewLen((long) (viewLen / factor), anchor, frac);
    }

    void zoomAll() {
        viewStart = 0;
        viewLen = Math.max(minLen(), eng.total());
        invalidate();
        if (listener != null) {
            listener.onViewport();
        }
    }

    void setViewStart(long s) {
        viewStart = s;
        clampView();
        invalidate();
    }

    private void clampView() {
        long total = eng.total();
        if (viewLen > total) {
            viewLen = Math.max(minLen(), total);
        }
        if (viewStart > total - viewLen) {
            viewStart = total - viewLen;
        }
        if (viewStart < 0) {
            viewStart = 0;
        }
    }

    void setCursor(long f) {
        cursor = Math.max(0, Math.min(f, eng.total()));
        invalidate();
    }

    void ensureVisible(long f) {
        if (f < viewStart || f > viewStart + viewLen) {
            viewStart = f - viewLen / 10;
            clampView();
            if (listener != null) {
                listener.onViewport();
            }
        } else if (f > viewStart + viewLen * 0.92) {
            viewStart = f - viewLen / 10;
            clampView();
            if (listener != null) {
                listener.onViewport();
            }
        }
        invalidate();
    }

    void setSelection(long a, long b) {
        selA = a;
        selB = b;
        invalidate();
    }

    /** Da richiamare dopo un'operazione che cambia la durata. */
    void refreshModel() {
        clampView();
        if (cursor > eng.total()) {
            cursor = eng.total();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        fill.setColor(Ui.BG);
        rect.set(0, 0, w, h);
        float rad = Ui.dp(getContext(), 12);
        cv.drawRoundRect(rect, rad, rad, fill);
        long total = eng.total();
        float mid = h / 2f;
        if (total <= 0) {
            txt.setTextSize(Ui.dp(getContext(), 13));
            String m = "Apri un audio per vedere la forma d'onda";
            cv.drawText(m, (w - txt.measureText(m)) / 2f, mid, txt);
            txt.setTextSize(Ui.dp(getContext(), 10));
            return;
        }
        // selezione (sfondo)
        if (selA >= 0 && selB > selA) {
            float x1 = xOf(selA), x2 = xOf(selB);
            fill.setColor(0x334DD0E1);
            cv.drawRect(Math.max(0, x1), 0, Math.min(w, x2), h, fill);
        }
        // onda
        double perPx = viewLen / (double) w;
        for (int x = 0; x < w; x++) {
            long t0 = viewStart + (long) (x * perPx);
            long t1 = viewStart + (long) ((x + 1) * perPx);
            if (t0 >= total) {
                break;
            }
            int pk = eng.peak(t0, Math.max(t1, t0 + 1));
            float amp = pk / 127f * (mid - Ui.dp(getContext(), 14));
            if (pk > 0 && amp < 1f) {
                amp = 1f;
            }
            boolean inSel = selA >= 0 && selB > selA && t0 >= selA && t0 < selB;
            cv.drawLine(x, mid - amp, x, mid + amp, inSel ? waveSel : wave);
        }
        fill.setColor(0x33FFFFFF);
        cv.drawRect(0, mid - 0.5f, w, mid + 0.5f, fill);
        // marcatori di selezione
        if (selA >= 0) {
            marker(cv, xOf(selA), true);
        }
        if (selB > selA && selB >= 0) {
            marker(cv, xOf(selB), false);
        }
        // tempi agli estremi
        cv.drawText(fmt(viewStart), Ui.dp(getContext(), 6), Ui.dp(getContext(), 12), txt);
        String e = fmt(Math.min(total, viewStart + viewLen));
        cv.drawText(e, w - txt.measureText(e) - Ui.dp(getContext(), 6), Ui.dp(getContext(), 12), txt);
        // cursore
        if (cursor >= viewStart && cursor <= viewStart + viewLen) {
            float x = xOf(cursor);
            cv.drawLine(x, 0, x, h, line);
            tri.reset();
            float s = Ui.dp(getContext(), 6);
            tri.moveTo(x - s, 0);
            tri.lineTo(x + s, 0);
            tri.lineTo(x, s * 1.3f);
            tri.close();
            fill.setColor(0xFFFFB74D);
            cv.drawPath(tri, fill);
        }
    }

    private void marker(Canvas cv, float x, boolean start) {
        if (x < -2 || x > getWidth() + 2) {
            return;
        }
        fill.setColor(Ui.ACCENT);
        float d = Ui.dp(getContext(), 7);
        cv.drawRect(x - 1, 0, x + 1, getHeight(), fill);
        float y = getHeight();
        tri.reset();
        tri.moveTo(x, y);
        tri.lineTo(x + (start ? d : -d), y);
        tri.lineTo(x, y - d * 1.4f);
        tri.close();
        cv.drawPath(tri, fill);
    }

    private float xOf(long f) {
        return (f - viewStart) / (float) viewLen * getWidth();
    }

    static String fmt(long frames) {
        double s = frames / (double) AudioEngine.RATE;
        int m = (int) (s / 60);
        double r = s - m * 60;
        return String.format(java.util.Locale.US, "%d:%04.1f", m, r);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scale.onTouchEvent(ev);
        if (eng.total() <= 0) {
            return true;
        }
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (scaling || ev.getPointerCount() > 1) {
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            long f = viewStart + (long) (ev.getX() / Math.max(1, getWidth()) * viewLen);
            f = Math.max(0, Math.min(f, eng.total()));
            cursor = f;
            invalidate();
            if (listener != null) {
                listener.onCursor(f, true);
            }
        }
        return true;
    }
}
