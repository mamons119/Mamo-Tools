package it.mamotools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mappa a tessere (OpenStreetMap, OpenTopoMap, Esri satellite) con il percorso colorato per
 * velocita'. Le tessere scaricate restano in cache sul telefono; senza rete si vede solo il percorso.
 */
final class TileMapView extends View {

    static final String[] NAMES = {"Strade", "Topografica", "Satellite"};
    private static final String[] URLS = {
            "https://tile.openstreetmap.org/%d/%d/%d.png",
            "https://tile.opentopomap.org/%d/%d/%d.png",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%1$d/%3$d/%2$d"};
    private static final int[] MAXZ = {19, 17, 19};
    private static final String[] ATTR = {"© OpenStreetMap", "© OpenTopoMap · © OpenStreetMap", "Immagini © Esri"};

    interface Listener {
        void onPick(int index);
    }

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final LruCache<String, Bitmap> cache = new LruCache<>(90);
    private final Set<String> pending = new HashSet<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final File dir;
    private int fails = 0, loaded = 0;

    int layer = 0;
    private double[] lat = new double[0], lon = new double[0], spd = new double[0];
    private float[] wx = new float[0], wy = new float[0];
    private int[] idx = new int[0]; // indici originali dei punti campionati
    private int cursor = -1;
    private double vMin, vMax;
    private double[] gate; // lat, lon
    private double cwx = 0.5, cwy = 0.5, zoom = 3;
    private final ScaleGestureDetector scaler;
    private final GestureDetector detector;
    private boolean scaling = false;
    private float lx, ly;
    Listener listener;

    TileMapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        dir = new File(c.getCacheDir(), "tiles");
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector det) {
                scaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector det) {
                zoomAt(Math.log(det.getScaleFactor()) / Math.log(2), det.getFocusX(), det.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector det) {
                scaling = false;
            }
        });
        detector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAt(1, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                pick(e.getX(), e.getY());
                return true;
            }
        });
    }

    void setLayer(int l) {
        layer = l;
        zoom = Math.min(zoom, MAXZ[l] + 1.5);
        invalidate();
    }

    void setTrack(double[] la, double[] lo, double[] v, int n) {
        int stride = Math.max(1, n / 2500);
        int m = (n + stride - 1) / stride;
        lat = new double[m];
        lon = new double[m];
        spd = new double[m];
        wx = new float[m];
        wy = new float[m];
        idx = new int[m];
        vMin = 1e9;
        vMax = -1e9;
        for (int i = 0, k = 0; i < n; i += stride, k++) {
            lat[k] = la[i];
            lon[k] = lo[i];
            spd[k] = v[i];
            idx[k] = i;
            vMin = Math.min(vMin, v[i]);
            vMax = Math.max(vMax, v[i]);
        }
        if (vMax - vMin < 1) {
            vMax = vMin + 1;
        }
        fit();
    }

    void setGate(double la, double lo) {
        gate = new double[]{la, lo};
    }

    void setCursor(int i, double la, double lo) {
        cursor = i;
        curLat = la;
        curLon = lo;
        invalidate();
    }

    private double curLat, curLon;

    void fit() {
        if (lat.length == 0 || getWidth() == 0) {
            invalidate();
            return;
        }
        double minX = 2, maxX = -1, minY = 2, maxY = -1;
        for (int i = 0; i < lat.length; i++) {
            double x = wxOf(lon[i]), y = wyOf(lat[i]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        cwx = (minX + maxX) / 2;
        cwy = (minY + maxY) / 2;
        double spanX = Math.max(maxX - minX, 1e-7), spanY = Math.max(maxY - minY, 1e-7);
        double zx = Math.log((getWidth() * 0.85) / (spanX * 256)) / Math.log(2);
        double zy = Math.log((getHeight() * 0.85) / (spanY * 256)) / Math.log(2);
        zoom = Math.max(3, Math.min(Math.min(zx, zy), 18));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (ow == 0 && lat.length > 0) {
            fit();
        }
    }

    private static double wxOf(double lon) {
        return (lon + 180.0) / 360.0;
    }

    private static double wyOf(double lat) {
        double s = Math.sin(Math.toRadians(Math.max(-85, Math.min(85, lat))));
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    }

    private double scalePx() {
        return 256.0 * Math.pow(2, zoom);
    }

    private void zoomAt(double dz, float fx, float fy) {
        double before = scalePx();
        double wxf = cwx + (fx - getWidth() / 2.0) / before, wyf = cwy + (fy - getHeight() / 2.0) / before;
        zoom = Math.max(2, Math.min(MAXZ[layer] + 1.5, zoom + dz));
        double after = scalePx();
        cwx = wxf - (fx - getWidth() / 2.0) / after;
        cwy = wyf - (fy - getHeight() / 2.0) / after;
        invalidate();
    }

    private void pick(float x, float y) {
        if (listener == null || lat.length == 0) {
            return;
        }
        double s = scalePx();
        int best = -1;
        double bd = 40 * d;
        for (int i = 0; i < lat.length; i++) {
            double sx = getWidth() / 2.0 + (wxOf(lon[i]) - cwx) * s, sy = getHeight() / 2.0 + (wyOf(lat[i]) - cwy) * s;
            double dd = Math.hypot(sx - x, sy - y);
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        if (best >= 0) {
            listener.onPick(idx[best]);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF1A1D24);
        double s = scalePx();
        int tz = (int) Math.max(0, Math.min(MAXZ[layer], Math.floor(zoom)));
        double tilePx = 256.0 * Math.pow(2, zoom - tz);
        int n = 1 << tz;
        double left = cwx - w / 2.0 / s, right = cwx + w / 2.0 / s, top = cwy - h / 2.0 / s, bottom = cwy + h / 2.0 / s;
        int x0 = (int) Math.floor(left * n), x1 = (int) Math.floor(right * n);
        int y0 = Math.max(0, (int) Math.floor(top * n)), y1 = Math.min(n - 1, (int) Math.floor(bottom * n));
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int wrapX = ((tx % n) + n) % n;
                Bitmap b = tile(layer, tz, wrapX, ty);
                float sx = (float) (w / 2.0 + (tx / (double) n - cwx) * s);
                float sy = (float) (h / 2.0 + (ty / (double) n - cwy) * s);
                dst.set(sx, sy, (float) (sx + tilePx + 0.5), (float) (sy + tilePx + 0.5));
                if (b != null) {
                    src.set(0, 0, b.getWidth(), b.getHeight());
                    c.drawBitmap(b, src, dst, tp);
                }
            }
        }
        drawTrack(c, w, h, s);
        // legenda velocita'
        if (lat.length > 0) {
            float lw = 120 * d, lh = 8 * d, lxp = 10 * d, lyp = h - 22 * d;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB30F1115);
            c.drawRoundRect(lxp - 6 * d, lyp - 16 * d, lxp + lw + 6 * d, lyp + lh + 6 * d, 8 * d, 8 * d, p);
            p.setShader(new LinearGradient(lxp, 0, lxp + lw, 0, new int[]{color(0), color(0.33), color(0.66), color(1)},
                    null, Shader.TileMode.CLAMP));
            c.drawRoundRect(lxp, lyp, lxp + lw, lyp + lh, 4 * d, 4 * d, p);
            p.setShader(null);
            p.setColor(0xFFECEFF4);
            p.setTextSize(10 * d);
            c.drawText(String.format(Locale.ITALY, "%.0f", vMin * 3.6), lxp, lyp - 4 * d, p);
            String mx = String.format(Locale.ITALY, "%.0f km/h", vMax * 3.6);
            c.drawText(mx, lxp + lw - p.measureText(mx), lyp - 4 * d, p);
        }
        // attribuzione
        p.setTextSize(10 * d);
        String a = ATTR[layer];
        float tw = p.measureText(a) + 10 * d;
        p.setColor(0xB3FFFFFF);
        c.drawRect(w - tw, h - 18 * d, w, h, p);
        p.setColor(0xFF222222);
        c.drawText(a, w - tw + 5 * d, h - 6 * d, p);
        if (fails > 8 && loaded == 0) {
            p.setColor(0xFF9AA3B2);
            p.setTextSize(12 * d);
            String m = "Mappa non disponibile senza internet: si vede solo il percorso";
            c.drawText(m, (w - p.measureText(m)) / 2f, 24 * d, p);
        }
    }

    private int color(double t) {
        return Color.HSVToColor(new float[]{(float) (240 * (1 - Math.max(0, Math.min(1, t)))), 0.9f, 1f});
    }

    private void drawTrack(Canvas c, int w, int h, double s) {
        int m = lat.length;
        if (m == 0) {
            return;
        }
        for (int i = 0; i < m; i++) {
            wx[i] = (float) (w / 2.0 + (wxOf(lon[i]) - cwx) * s);
            wy[i] = (float) (h / 2.0 + (wyOf(lat[i]) - cwy) * s);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(8 * d);
        p.setColor(0xCCFFFFFF);
        for (int i = 1; i < m; i++) {
            c.drawLine(wx[i - 1], wy[i - 1], wx[i], wy[i], p);
        }
        p.setStrokeWidth(5 * d);
        for (int i = 1; i < m; i++) {
            p.setColor(color(((spd[i] + spd[i - 1]) / 2 - vMin) / (vMax - vMin)));
            c.drawLine(wx[i - 1], wy[i - 1], wx[i], wy[i], p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF66BB6A);
        c.drawCircle(wx[0], wy[0], 7 * d, p);
        p.setColor(0xFFFF6B6B);
        c.drawCircle(wx[m - 1], wy[m - 1], 7 * d, p);
        if (gate != null) {
            float gx = (float) (w / 2.0 + (wxOf(gate[1]) - cwx) * s), gy = (float) (h / 2.0 + (wyOf(gate[0]) - cwy) * s);
            p.setColor(0xFFFFB74D);
            c.drawRect(gx - 6 * d, gy - 6 * d, gx + 6 * d, gy + 6 * d, p);
        }
        if (cursor >= 0) {
            float cx = (float) (w / 2.0 + (wxOf(curLon) - cwx) * s), cy = (float) (h / 2.0 + (wyOf(curLat) - cwy) * s);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, 10 * d, p);
            p.setColor(0xFF4DD0E1);
            c.drawCircle(cx, cy, 6 * d, p);
        }
    }

    // ------------------------------------------------------------------ tessere

    private Bitmap tile(final int lay, final int z, final int x, final int y) {
        final String key = lay + "/" + z + "/" + x + "/" + y;
        Bitmap b = cache.get(key);
        if (b != null) {
            return b;
        }
        synchronized (pending) {
            if (pending.contains(key)) {
                return null;
            }
            pending.add(key);
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                try {
                    File f = new File(dir, lay + "/" + z + "/" + x + "/" + y + ".png");
                    if (f.exists() && f.length() > 100) {
                        bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                    }
                    if (bm == null) {
                        HttpURLConnection con = (HttpURLConnection) new URL(String.format(Locale.US, URLS[lay], z, x, y))
                                .openConnection();
                        con.setConnectTimeout(6000);
                        con.setReadTimeout(8000);
                        con.setRequestProperty("User-Agent", "MamoTools/2.1 (Android personal app)");
                        if (con.getResponseCode() == 200) {
                            //noinspection ResultOfMethodCallIgnored
                            f.getParentFile().mkdirs();
                            try (InputStream in = con.getInputStream(); FileOutputStream os = new FileOutputStream(f)) {
                                byte[] buf = new byte[16384];
                                int r;
                                while ((r = in.read(buf)) > 0) {
                                    os.write(buf, 0, r);
                                }
                            }
                            bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                        }
                        con.disconnect();
                    }
                } catch (Exception ignored) {
                }
                if (bm != null) {
                    cache.put(key, bm);
                    loaded++;
                } else {
                    fails++;
                }
                synchronized (pending) {
                    pending.remove(key);
                }
                postInvalidate();
            }
        });
        return null;
    }

    void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            lx = e.getX();
            ly = e.getY();
        }
        scaler.onTouchEvent(e);
        detector.onTouchEvent(e);
        if (!scaling && e.getPointerCount() == 1 && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            double s = scalePx();
            cwx -= (e.getX() - lx) / s;
            cwy -= (e.getY() - ly) / s;
            lx = e.getX();
            ly = e.getY();
            invalidate();
        } else if (e.getPointerCount() > 1 || e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            lx = e.getX();
            ly = e.getY();
        }
        return true;
    }
}
