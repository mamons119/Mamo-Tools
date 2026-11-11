package it.mamotools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Anteprima del fotogramma con la cornice del ritaglio: la cornice ha il rapporto scelto e si
 * trascina dentro al video; fuori dalla cornice l'immagine e' oscurata.
 */
final class CropView extends View {
    private Bitmap bmp;
    private float aspect = 16f / 9f;     // larghezza / altezza del video mostrato
    private float ratio = 0f;            // rapporto della cornice (0 = nessun ritaglio)
    private float frac = 1f;             // grandezza della cornice rispetto al massimo possibile
    private float cx = 0.5f, cy = 0.5f;  // centro della cornice (frazioni)
    private Runnable onChange;
    private float lastX, lastY;
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF img = new RectF();

    CropView(Context c) {
        super(c);
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    void setAspect(float a) {
        if (a > 0.05f) {
            aspect = a;
        }
        requestLayout();
        invalidate();
    }

    void setBitmap(Bitmap b) {
        bmp = b;
        if (b != null && b.getHeight() > 0) {
            aspect = b.getWidth() / (float) b.getHeight();
        }
        requestLayout();
        invalidate();
    }

    void setRatio(float r) {
        ratio = r;
        clampCenter();
        invalidate();
        fire();
    }

    void setFrac(float f) {
        frac = Math.max(0.1f, Math.min(1f, f));
        clampCenter();
        invalidate();
        fire();
    }

    void recenter() {
        cx = cy = 0.5f;
        invalidate();
        fire();
    }

    float getRatio() {
        return ratio;
    }

    /** Imposta la cornice da un rettangolo esistente (frazioni x, y, larghezza, altezza). */
    void setRect(float[] r) {
        if (r == null || r.length != 4) {
            return;
        }
        cx = r[0] + r[2] / 2f;
        cy = r[1] + r[3] / 2f;
        float[] mx = maxSize();
        frac = mx[0] > 0 ? Math.max(0.1f, Math.min(1f, r[2] / mx[0])) : 1f;
        clampCenter();
        invalidate();
    }

    float getFrac() {
        return frac;
    }

    /** Dimensioni massime (frazioni) di una cornice con il rapporto scelto. */
    private float[] maxSize() {
        if (ratio <= 0) {
            return new float[]{1f, 1f};
        }
        float fw, fh;
        if (ratio >= aspect) {
            fw = 1f;
            fh = aspect / ratio;
        } else {
            fh = 1f;
            fw = ratio / aspect;
        }
        return new float[]{fw, fh};
    }

    /** Rettangolo del ritaglio (frazioni x, y, larghezza, altezza), oppure null se non c'e' ritaglio. */
    float[] rect() {
        if (ratio <= 0) {
            return null;
        }
        float[] mx = maxSize();
        float fw = mx[0] * frac, fh = mx[1] * frac;
        float x = Math.max(0f, Math.min(1f - fw, cx - fw / 2f));
        float y = Math.max(0f, Math.min(1f - fh, cy - fh / 2f));
        return new float[]{x, y, fw, fh};
    }

    private void clampCenter() {
        float[] mx = maxSize();
        float fw = mx[0] * frac, fh = mx[1] * frac;
        cx = Math.max(fw / 2f, Math.min(1f - fw / 2f, cx));
        cy = Math.max(fh / 2f, Math.min(1f - fh / 2f, cy));
    }

    private void fire() {
        if (onChange != null) {
            onChange.run();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.4f);
        int h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
        }
        setMeasuredDimension(w, Math.max(h, 10));
    }

    @Override
    protected void onDraw(Canvas c) {
        float vw = getWidth(), vh = getHeight();
        float s = Math.min(vw / aspect, vh);
        float iw = s * aspect, ih = s;
        float l = (vw - iw) / 2f, t = (vh - ih) / 2f;
        img.set(l, t, l + iw, t + ih);
        if (bmp != null) {
            c.drawBitmap(bmp, null, img, p);
        } else {
            p.setColor(0xFF2A2F3A);
            c.drawRect(img, p);
            p.setColor(0xFF9AA3B2);
            p.setTextSize(13f * getResources().getDisplayMetrics().density);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("Carico il fotogramma...", img.centerX(), img.centerY(), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
        float[] r = rect();
        if (r == null) {
            return;
        }
        float x0 = l + r[0] * iw, y0 = t + r[1] * ih, x1 = x0 + r[2] * iw, y1 = y0 + r[3] * ih;
        float d = getResources().getDisplayMetrics().density;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xB3000000);
        c.drawRect(l, t, l + iw, y0, p);
        c.drawRect(l, y1, l + iw, t + ih, p);
        c.drawRect(l, y0, x0, y1, p);
        c.drawRect(x1, y0, l + iw, y1, p);
        // griglia dei terzi
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f * d);
        p.setColor(0x55FFFFFF);
        for (int i = 1; i < 3; i++) {
            c.drawLine(x0 + (x1 - x0) * i / 3f, y0, x0 + (x1 - x0) * i / 3f, y1, p);
            c.drawLine(x0, y0 + (y1 - y0) * i / 3f, x1, y0 + (y1 - y0) * i / 3f, p);
        }
        p.setStrokeWidth(2.5f * d);
        p.setColor(Ui.ACCENT);
        c.drawRect(x0, y0, x1, y1, p);
        // angoli
        float k = Math.min(18f * d, Math.min(x1 - x0, y1 - y0) / 3f);
        p.setStrokeWidth(5f * d);
        c.drawLine(x0, y0, x0 + k, y0, p);
        c.drawLine(x0, y0, x0, y0 + k, p);
        c.drawLine(x1, y0, x1 - k, y0, p);
        c.drawLine(x1, y0, x1, y0 + k, p);
        c.drawLine(x0, y1, x0 + k, y1, p);
        c.drawLine(x0, y1, x0, y1 - k, p);
        c.drawLine(x1, y1, x1 - k, y1, p);
        c.drawLine(x1, y1, x1, y1 - k, p);
        p.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (ratio <= 0 || img.width() <= 1) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastX = e.getX();
                lastY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                cx += (e.getX() - lastX) / img.width();
                cy += (e.getY() - lastY) / img.height();
                lastX = e.getX();
                lastY = e.getY();
                clampCenter();
                invalidate();
                fire();
                return true;
            default:
                return true;
        }
    }
}
