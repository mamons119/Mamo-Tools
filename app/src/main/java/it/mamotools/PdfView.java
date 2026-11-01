package it.mamotools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visualizzatore PDF a scorrimento continuo con zoom a pizzico. Le pagine sono disegnate
 * a riquadri (tile) generati da {@link PdfRenderer}, quindi anche con lo zoom alto la
 * memoria resta contenuta.
 */
final class PdfView extends View {

    interface Listener {
        void onPageChanged(int page, int total);

        void onTap();
    }

    private static final float GAP_PT = 10f;
    private static final int TILE = 384;
    private static final float MAX_ZOOM = 8f;

    private final Object lock = new Object();
    private PdfRenderer renderer;
    private PdfRenderer.Page openPage;
    private int openIndex = -1;

    private int pageCount;
    private float[] pw, ph, top;
    private float maxW = 1f, totalPt = 1f;

    private float zoom = 1f;
    private float baseScale = 1f;
    private float scrollX, scrollY;
    private boolean night = false;
    private boolean scaling = false;
    private int curPage = -1;
    private Listener listener;

    private final OverScroller scroller;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint();
    private final RectF dst = new RectF();

    private final LruCache<Long, Bitmap> cache;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile Set<Long> wanted = new HashSet<>();
    private final Set<Long> pending = new HashSet<>();
    private volatile boolean closed = false;

    PdfView(Context c) {
        super(c);
        long mem = Runtime.getRuntime().maxMemory() / 5;
        cache = new LruCache<Long, Bitmap>((int) Math.min(mem, 140L * 1024 * 1024)) {
            @Override
            protected int sizeOf(Long k, Bitmap b) {
                return b.getByteCount();
            }
        };
        scroller = new OverScroller(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                scaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAt(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
                invalidate();
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress()) {
                    return false;
                }
                scrollX += dx;
                scrollY += dy;
                clamp();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaleDetector.isInProgress()) {
                    return false;
                }
                scroller.fling((int) scrollX, (int) scrollY, (int) -vx, (int) -vy,
                        0, (int) maxScrollX(), 0, (int) maxScrollY());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAt(zoom > 1.4f ? 1f : 2.5f, e.getX(), e.getY());
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) {
                    listener.onTap();
                }
                return true;
            }
        });
        setBackgroundColor(0xFF202124);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ documento

    /** Imposta il documento; sizes in punti (1/72"). */
    void setDocument(PdfRenderer r, float[] w, float[] h) {
        synchronized (lock) {
            renderer = r;
        }
        pageCount = w.length;
        pw = w;
        ph = h;
        top = new float[pageCount];
        float y = 0;
        maxW = 1f;
        for (int i = 0; i < pageCount; i++) {
            top[i] = y;
            y += h[i] + GAP_PT;
            maxW = Math.max(maxW, w[i]);
        }
        totalPt = Math.max(1f, y - GAP_PT);
        zoom = 1f;
        scrollX = scrollY = 0;
        curPage = -1;
        cache.evictAll();
        baseScale = getWidth() > 0 ? getWidth() / maxW : 1f;
        invalidate();
    }

    void close() {
        closed = true;
        pool.shutdownNow();
        synchronized (lock) {
            try {
                if (openPage != null) {
                    openPage.close();
                }
            } catch (Throwable ignored) {
            }
            openPage = null;
            try {
                if (renderer != null) {
                    renderer.close();
                }
            } catch (Throwable ignored) {
            }
            renderer = null;
        }
        cache.evictAll();
    }

    int pageCount() {
        return pageCount;
    }

    int currentPage() {
        return Math.max(0, curPage);
    }

    float zoom() {
        return zoom;
    }

    void setNight(boolean n) {
        night = n;
        invalidate();
    }

    boolean isNight() {
        return night;
    }

    // ------------------------------------------------------------------ navigazione

    void goToPage(int i) {
        if (pageCount == 0) {
            return;
        }
        i = Math.max(0, Math.min(pageCount - 1, i));
        scroller.forceFinished(true);
        scrollY = top[i] * scale();
        clamp();
        invalidate();
    }

    void zoomBy(float factor) {
        zoomAt(zoom * factor, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    void resetZoom() {
        zoomAt(1f, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    private float scale() {
        return baseScale * zoom;
    }

    private float offX() {
        return Math.max(0f, (getWidth() - maxW * scale()) / 2f);
    }

    private float maxScrollX() {
        return Math.max(0f, maxW * scale() - getWidth());
    }

    private float maxScrollY() {
        return Math.max(0f, totalPt * scale() - getHeight());
    }

    private void clamp() {
        scrollX = Math.max(0f, Math.min(scrollX, maxScrollX()));
        scrollY = Math.max(0f, Math.min(scrollY, maxScrollY()));
    }

    private void zoomAt(float nz, float fx, float fy) {
        if (pageCount == 0) {
            return;
        }
        nz = Math.max(1f, Math.min(MAX_ZOOM, nz));
        float s = scale();
        float docX = (scrollX + fx - offX()) / s;
        float docY = (scrollY + fy) / s;
        zoom = nz;
        float ns = scale();
        scrollX = docX * ns + offX() - fx;
        scrollY = docY * ns - fy;
        clamp();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (pageCount == 0) {
            return;
        }
        float docY = (scrollY + oh / 2f) / scale();
        baseScale = w / maxW;
        scrollY = docY * scale() - h / 2f;
        clamp();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            scrollY = scroller.getCurrY();
            clamp();
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    // ------------------------------------------------------------------ disegno

    private static long key(int page, int bucket, int tx, int ty) {
        return ((long) page << 40) | ((long) (bucket + 4) << 36) | ((long) (tx + 1) << 18) | (ty + 1);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pageCount == 0 || pw == null) {
            return;
        }
        if (baseScale <= 0f || getWidth() / maxW != baseScale && zoom == 1f && scrollX == 0f) {
            baseScale = getWidth() / maxW;
        }
        if (night) {
            ColorMatrix cm = new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            fill.setColor(0xFF000000);
        } else {
            paint.setColorFilter(null);
            fill.setColor(0xFFFFFFFF);
        }

        float s = scale();
        float ox = offX();
        int vw = getWidth();
        int vh = getHeight();
        int bucket = Math.round((float) (Math.log(zoom) / Math.log(2.0)));
        float rs = baseScale * (float) Math.pow(2.0, bucket);
        float ratio = s / rs;

        Set<Long> want = new HashSet<>();
        int first = -1;
        int centerPage = 0;
        float cy = scrollY + vh / 2f;
        for (int i = 0; i < pageCount; i++) {
            float pt = top[i] * s;
            float pb = pt + ph[i] * s;
            if (cy >= pt && cy < pb + GAP_PT * s) {
                centerPage = i;
            }
            if (pb < scrollY - 1 || pt > scrollY + vh + 1) {
                continue;
            }
            if (first < 0) {
                first = i;
            }
            float pl = ox + (maxW - pw[i]) * s / 2f - scrollX;
            float sy = pt - scrollY;
            dst.set(pl, sy, pl + pw[i] * s, sy + ph[i] * s);
            c.drawRect(dst, fill);

            // miniatura a bassa risoluzione (sempre disponibile in fretta)
            long tk = key(i, 3, -1, -1);
            want.add(tk);
            Bitmap thumb = cache.get(tk);
            if (thumb != null) {
                c.drawBitmap(thumb, null, dst, paint);
            } else {
                request(i, 3, -1, -1, rs);
            }

            // riquadri nitidi
            float pagePxW = pw[i] * rs;
            float pagePxH = ph[i] * rs;
            float visL = Math.max(0f, -pl) / ratio;
            float visT = Math.max(0f, -sy) / ratio;
            float visR = Math.min(pw[i] * s, vw - pl) / ratio;
            float visB = Math.min(ph[i] * s, vh - sy) / ratio;
            int tx0 = (int) Math.floor(visL / TILE);
            int ty0 = (int) Math.floor(visT / TILE);
            int tx1 = (int) Math.floor((visR - 1) / TILE);
            int ty1 = (int) Math.floor((visB - 1) / TILE);
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    if (tx * TILE >= pagePxW || ty * TILE >= pagePxH) {
                        continue;
                    }
                    long k = key(i, bucket, tx, ty);
                    want.add(k);
                    Bitmap b = cache.get(k);
                    if (b == null) {
                        if (!scaling) {
                            request(i, bucket, tx, ty, rs);
                        }
                        continue;
                    }
                    float l = pl + tx * TILE * ratio;
                    float t = sy + ty * TILE * ratio;
                    float w = Math.min(TILE, pagePxW - tx * TILE) * ratio;
                    float h = Math.min(TILE, pagePxH - ty * TILE) * ratio;
                    dst.set(l, t, l + w, t + h);
                    c.drawBitmap(b, new android.graphics.Rect(0, 0,
                            Math.round(w / ratio), Math.round(h / ratio)), dst, paint);
                }
            }
        }
        wanted = want;
        if (centerPage != curPage) {
            curPage = centerPage;
            if (listener != null) {
                final int p = centerPage;
                post(() -> listener.onPageChanged(p, pageCount));
            }
        }
    }

    private void request(final int page, final int bucket, final int tx, final int ty,
                         final float rs) {
        final long k = key(page, bucket, tx, ty);
        synchronized (pending) {
            if (!pending.add(k)) {
                return;
            }
        }
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (closed || !wanted.contains(k)) {
                            return;
                        }
                        Bitmap b = renderTile(page, bucket, tx, ty, rs);
                        if (b != null) {
                            cache.put(k, b);
                            postInvalidate();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        synchronized (pending) {
                            pending.remove(k);
                        }
                    }
                }
            });
        } catch (Exception e) {
            synchronized (pending) {
                pending.remove(k);
            }
        }
    }

    private Bitmap renderTile(int page, int bucket, int tx, int ty, float rs) {
        synchronized (lock) {
            if (renderer == null) {
                return null;
            }
            try {
                if (openIndex != page) {
                    if (openPage != null) {
                        openPage.close();
                        openPage = null;
                    }
                    openPage = renderer.openPage(page);
                    openIndex = page;
                }
                Bitmap b;
                Matrix m = new Matrix();
                if (bucket == 3 && tx == -1) {
                    // miniatura: tutta la pagina con larghezza ~ 0,7 della vista
                    int wpx = Math.max(120, Math.min(720, (int) (getWidth() * 0.7f)));
                    float sc = wpx / pw[page];
                    b = Bitmap.createBitmap(wpx, Math.max(1, Math.round(ph[page] * sc)),
                            Bitmap.Config.ARGB_8888);
                    b.eraseColor(0xFFFFFFFF);
                    m.setScale(sc, sc);
                } else {
                    b = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
                    b.eraseColor(0xFFFFFFFF);
                    m.setScale(rs, rs);
                    m.postTranslate(-tx * TILE, -ty * TILE);
                }
                openPage.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            } catch (Throwable t) {
                try {
                    if (openPage != null) {
                        openPage.close();
                    }
                } catch (Throwable ignored) {
                }
                openPage = null;
                openIndex = -1;
                return null;
            }
        }
    }
}
