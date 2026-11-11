package it.mamotools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Disegna sopra il VideoView i testi attivi alla posizione corrente (solo anteprima). */
final class TextOverlayView extends View {
    private List<VideoFx.Item> items;
    private List<VideoFx.Item> texts;
    private final float[] cpos = new float[2];
    private long posMs;
    private int vw, vh;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<VideoFx.Item, Object[]> cache = new HashMap<>();

    TextOverlayView(Context c) {
        super(c);
        setClickable(false);
    }

    private PreviewVideo video;
    private final float[] u = new float[VideoFx.U_COUNT];
    private final Paint fxPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float topFade = 1f;

    void setVideo(PreviewVideo v) {
        video = v;
    }

    /** Calcola gli effetti attivi alla posizione corrente e li applica al player. */
    private void applyFx() {
        VideoFx.uniforms(items, posMs * 1000, u);
        // il nero di "Appare/Sparisce al nero" viene disegnato per ultimo, sopra a tutto
        topFade = u[VideoFx.U_FADE];
        u[VideoFx.U_FADE] = 1f;
        if (video != null) {
            if (VideoFx.isNeutral(u)) {
                video.setFx(null, 1f, 0.5f, 0.5f);
            } else {
                video.setFx(VideoFx.colorMatrix(u), u[VideoFx.U_ZOOM], u[VideoFx.U_ZX], u[VideoFx.U_ZY]);
            }
        }
    }

    void setItems(List<VideoFx.Item> l) {
        items = l;
        texts = VideoFx.expandVhs(l);
        cache.clear();
        applyFx();
        invalidate();
    }

    void setVideoSize(int displayW, int displayH) {
        vw = displayW;
        vh = displayH;
        cache.clear();
        invalidate();
    }

    void setPos(long ms) {
        if (ms != posMs) {
            posMs = ms;
            applyFx();
            invalidate();
        }
    }

    /** Da chiamare quando un elemento e' stato modificato. */
    void refresh() {
        texts = VideoFx.expandVhs(items);
        cache.clear();
        applyFx();
        invalidate();
    }

    static String sig(VideoFx.Item o, int h) {
        return o.text + "|" + o.font + o.bold + o.italic + o.sizeFrac + o.color + o.outline + o.shadow + o.box + "|" + h
                + (o.vhs ? "v" + o.vhsJit + o.align + o.salt : "");
    }

    @Override
    protected void onDraw(Canvas c) {
        if (items == null || items.isEmpty() || vw <= 0 || vh <= 0) {
            return;
        }
        float s = Math.min(getWidth() / (float) vw, getHeight() / (float) vh);
        float rw = vw * s, rh = vh * s;
        float l = (getWidth() - rw) / 2f, t = (getHeight() - rh) / 2f;
        textsDone = false;
        drawFx(c, l, t, rw, rh, s);
        if (!textsDone) {
            drawTexts(c, l, t, rw, rh);
        }
        if (topFade < 0.999f) {
            Paint kp = new Paint();
            kp.setColor(0xFF000000);
            kp.setAlpha((int) (255 * (1f - Math.max(0f, topFade))));
            c.drawRect(l, t, l + rw, t + rh, kp);
        }
    }

    private boolean textsDone = false;

    /** Disegna i testi (anche VHS) del momento corrente nel rettangolo del video (l,t,rw,rh). */
    private void drawTexts(Canvas c, float l, float t, float rw, float rh) {
        long us = posMs * 1000;
        for (VideoFx.Item it : texts) {
            if (!it.isText || !it.activeAt(us) || it.text == null || it.text.trim().isEmpty()) {
                continue;
            }
            int hh = Math.round(rh);
            String sg = sig(it, hh);
            Object[] e = cache.get(it);
            if (e == null || !sg.equals(e[0])) {
                Bitmap b = VideoFx.render(it, Math.round(rw), hh, 0);
                e = new Object[]{sg, b};
                cache.put(it, e);
            }
            Bitmap b = (Bitmap) e[1];
            if (b == null) {
                continue;
            }
            VideoFx.center(it, b.getWidth(), b.getHeight(), rw, rh, cpos);
            float jx = it.vhsJit ? VideoFx.vhsJitter(us, it.salt) * rw : 0f;
            c.drawBitmap(b, l + cpos[0] * rw - b.getWidth() / 2f + jx, t + cpos[1] * rh - b.getHeight() / 2f, paint);
        }
    }

    private void drawFx(Canvas c, float l, float t, float rw, float rh, float s) {
        if (video == null || VideoFx.isNeutral(u)) {
            return;
        }
        float z = u[VideoFx.U_ZOOM];
        if (VideoFx.needsGrab(u)) {
            int gw;
            boolean smooth = true;
            if (u[VideoFx.U_PIX] > 0) {
                float block = (4 + u[VideoFx.U_PIX] * 40) * s;
                gw = Math.max(2, Math.round(rw / block));
                smooth = false;
            } else if (u[VideoFx.U_BLUR] > 0) {
                gw = Math.max(6, Math.round(rw / (1 + u[VideoFx.U_BLUR] * 14 * 0.9f * s)));
            } else {
                gw = Math.round(rw / 2);
            }
            int gh = Math.max(2, Math.round(gw * rh / rw));
            Bitmap g = video.grab(gw, gh);
            if (g != null) {
                c.save();
                c.clipRect(l - 2, t - 2, l + rw + 2, t + rh + 2);
                float cx = l + rw / 2, cy = t + rh / 2;
                float pvx = l + rw * u[VideoFx.U_ZX], pvy = t + rh * u[VideoFx.U_ZY];
                RectF dst = new RectF(pvx + (l - pvx) * z, pvy + (t - pvy) * z,
                        pvx + (l + rw - pvx) * z, pvy + (t + rh - pvy) * z);
                boolean frameDone = false;
                if (u[VideoFx.U_SPLIT] > 0) {
                    Paint bk = new Paint();
                    bk.setColor(0xFF000000);
                    c.drawRect(l, t, l + rw, t + rh, bk);
                    float o = u[VideoFx.U_SPLIT] * 14 * s;
                    float[][] masks = {
                            {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0},
                            {0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0},
                            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0}};
                    float[] dx = {o, 0, -o};
                    for (int i = 0; i < 3; i++) {
                        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                        p.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(masks[i])));
                        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
                        RectF d2 = new RectF(dst);
                        d2.offset(dx[i], 0);
                        c.saveLayer(l, t, l + rw, t + rh, null);
                        c.drawBitmap(g, null, d2, p);
                        c.restore();
                    }
                } else if (u[VideoFx.U_CRT] > 0 && u[VideoFx.U_WARP] > 0 && u[VideoFx.U_PIX] == 0
                        && u[VideoFx.U_BLUR] == 0) {
                    if (!g.isMutable()) {
                        Bitmap mg = g.copy(Bitmap.Config.ARGB_8888, true);
                        g.recycle();
                        g = mg;
                    }
                    Canvas gc = new Canvas(g);
                    gc.scale(g.getWidth() / rw, g.getHeight() / rh);
                    drawTexts(gc, 0, 0, rw, rh);
                    textsDone = true;
                    drawCrtMesh(c, g, dst, l, t, rw, rh);
                    frameDone = true;
                } else if (u[VideoFx.U_FISH] > 0 && u[VideoFx.U_PIX] == 0 && u[VideoFx.U_BLUR] == 0) {
                    drawFishMesh(c, g, l, t, rw, rh);
                    frameDone = true;
                } else if (u[VideoFx.U_RW] > 0 && u[VideoFx.U_PIX] == 0 && u[VideoFx.U_BLUR] == 0) {
                    drawRewindMesh(c, g, dst, l, t, rw, rh);
                } else if (u[VideoFx.U_VHS] > 0 && u[VideoFx.U_PIX] == 0 && u[VideoFx.U_BLUR] == 0) {
                    drawVhsMesh(c, g, dst, l, t, rw, rh);
                } else {
                    fxPaint.setFilterBitmap(smooth);
                    fxPaint.setColorFilter(new ColorMatrixColorFilter(VideoFx.colorMatrix(u)));
                    c.drawBitmap(g, null, dst, fxPaint);
                    fxPaint.setColorFilter(null);
                }
                if (u[VideoFx.U_CRT] > 0 && u[VideoFx.U_WARP] > 0 && !frameDone) {
                    drawCrtFrame(c, l, t, rw, rh);
                }
                c.restore();
                g.recycle();
            }
        }
        if (u[VideoFx.U_CRT] > 0) {
            if (!textsDone) {
                // i testi stanno sotto lo schermo CRT: ne subiscono scanline e maschera
                drawTexts(c, l, t, rw, rh);
                textsDone = true;
            }
            drawCrtOverlay(c, l, t, rw, rh);
            if (u[VideoFx.U_BARS] > 0) {
                drawCrtBars(c, l, t, rw, rh);
            }
            if (u[VideoFx.U_WARP] > 0) {
                drawCrtFrame(c, l, t, rw, rh);
            }
        }
        if (u[VideoFx.U_VHS] > 0) {
            drawVhsOverlay(c, l, t, rw, rh);
        }
        if (u[VideoFx.U_RW] > 0) {
            drawRewindOverlay(c, l, t, rw, rh);
        }
        if (u[VideoFx.U_VIG] > 0) {
            c.save();
            c.clipRect(l, t, l + rw, t + rh);
            c.translate(l + rw / 2, t + rh / 2);
            c.scale(rw, rh);
            Paint vp = new Paint();
            int a = (int) (255 * Math.min(1f, 1.1f * u[VideoFx.U_VIG]));
            vp.setShader(new RadialGradient(0, 0, 0.85f, new int[]{0, 0, a << 24},
                    new float[]{0f, 0.353f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(-0.5f, -0.5f, 0.5f, 0.5f, vp);
            c.restore();
        }
    }

    // ------------------------------------------------------------------ schermo CRT (anteprima)

    private Bitmap crtTile;
    private String crtTileKey = "";

    /** Curvatura dello schermo: il fotogramma viene deformato a "pancia" su fondo nero. */
    private void drawCrtMesh(Canvas c, Bitmap g, RectF dst, float l, float t, float rw, float rh) {
        Paint bk = new Paint();
        bk.setColor(0xFF000000);
        c.drawRect(l - 2, t - 2, l + rw + 2, t + rh + 2, bk);
        final int n = 16;
        float[] v = new float[(n + 1) * (n + 1) * 2];
        int k = 0;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                float sx = i / (float) n - 0.5f, sy = j / (float) n - 0.5f;
                float f = 1f / (1f + 0.2f * (sx * sx + sy * sy));
                v[k++] = dst.centerX() + sx * f * dst.width();
                v[k++] = dst.centerY() + sy * f * dst.height();
            }
        }
        fxPaint.setFilterBitmap(true);
        fxPaint.setColorFilter(new ColorMatrixColorFilter(VideoFx.colorMatrix(u)));
        c.drawBitmapMesh(g, n, n, v, 0, null, 0, fxPaint);
        fxPaint.setColorFilter(null);
    }

    /** Linee orizzontali (scanline), maschera RGB verticale e, con le distorsioni, angoli scuri. */
    private void drawCrtOverlay(Canvas c, float l, float t, float rw, float rh) {
        float k = u[VideoFx.U_CRT];
        int tw = Math.max(3, Math.round(rw / 320f / 3f) * 3);
        int th = Math.max(3, Math.round(rh / 240f));
        String key = tw + "x" + th + "k" + Math.round(k * 100);
        if (crtTile == null || !key.equals(crtTileKey)) {
            int[] px = new int[tw * th];
            for (int y = 0; y < th; y++) {
                float sl = 0.5f + 0.5f * (float) Math.cos(2 * Math.PI * y / th);
                float ad = 0.5f * k * (1 - sl);
                for (int x = 0; x < tw; x++) {
                    int band = x * 3 / tw;
                    float ta = 0.16f * k;
                    float[] tint = {band == 0 ? 255 : 0, band == 1 ? 255 : 0, band == 2 ? 255 : 0};
                    float a = ta + ad * (1 - ta);
                    int r = Math.round(a > 0 ? tint[0] * ta / a : 0), gg = Math.round(a > 0 ? tint[1] * ta / a : 0),
                            b = Math.round(a > 0 ? tint[2] * ta / a : 0);
                    px[y * tw + x] = android.graphics.Color.argb(Math.round(a * 255), r, gg, b);
                }
            }
            if (crtTile != null) {
                crtTile.recycle();
            }
            crtTile = Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888);
            crtTileKey = key;
        }
        c.save();
        c.clipRect(l, t, l + rw, t + rh);
        Paint p = new Paint();
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(crtTile, Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(l, t);
        sh.setLocalMatrix(m);
        p.setShader(sh);
        c.drawRect(l, t, l + rw, t + rh, p);
        if (u[VideoFx.U_WARP] > 0) {
            Paint vp = new Paint();
            float rad = (float) Math.hypot(rw, rh) / 2f;
            vp.setShader(new RadialGradient(l + rw / 2, t + rh / 2, rad, new int[]{0, 0, 0x99000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(l, t, l + rw, t + rh, vp);
        }
        c.restore();
    }

    /** Cornice nera con il bordo curvo dello schermo CRT (copre tutto il fuori-schermo, fino ai lati). */
    private void drawCrtFrame(Canvas c, float l, float t, float rw, float rh) {
        android.graphics.Path path = new android.graphics.Path();
        path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        path.addRect(l - 2, t - 2, l + rw + 2, t + rh + 2, android.graphics.Path.Direction.CW);
        final int n = 24;
        float cx = l + rw / 2, cy = t + rh / 2;
        boolean first = true;
        for (int q = 0; q < 4 * n; q++) {
            float sx, sy;
            int side = q / n, i = q % n;
            float a = i / (float) n - 0.5f;
            if (side == 0) {
                sx = a;
                sy = -0.5f;
            } else if (side == 1) {
                sx = 0.5f;
                sy = a;
            } else if (side == 2) {
                sx = -a;
                sy = 0.5f;
            } else {
                sx = -0.5f;
                sy = -a;
            }
            float f = 1f / (1f + 0.2f * (sx * sx + sy * sy));
            float px = cx + sx * f * rw, py = cy + sy * f * rh;
            if (first) {
                path.moveTo(px, py);
                first = false;
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        c.drawPath(path, p);
    }

    /** Fasce scure che scorrono dal basso verso l'alto (stessa formula dello shader dell'esportazione). */
    private void drawCrtBars(Canvas c, float l, float t, float rw, float rh) {
        float tm = posMs / 1000f;
        int n = Math.min(5, Math.round(u[VideoFx.U_BARS]));
        c.save();
        c.clipRect(l, t, l + rw, t + rh);
        Paint p = new Paint();
        for (int i = 0; i < n; i++) {
            float ph = tm * u[VideoFx.U_BSPD] * (1f + 0.37f * i) + 0.381966f * i;
            float y0 = (float) (-ph - Math.floor(-ph));
            for (int w = -1; w <= 0; w++) {
                float top = t + (y0 + w) * rh;
                p.setShader(new android.graphics.LinearGradient(0, top, 0, top + 0.16f * rh, 0x38000000, 0,
                        Shader.TileMode.CLAMP));
                c.drawRect(l, top, l + rw, top + 0.16f * rh, p);
            }
        }
        c.restore();
    }

    private static float sstep(float a, float b, float x) {
        float q = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return q * q * (3 - 2 * q);
    }

    private static float bandY(int i, float tm) {
        float v = tm * (0.04f + 0.025f * i) + 0.43f * i + 0.1f;
        return (float) (v - Math.floor(v));
    }

    /** Fotogramma con righe sfasate: tremolio, fasce di disturbo e rumore in basso. */
    private void drawVhsMesh(Canvas c, Bitmap g, RectF dst, float l, float t, float rw, float rh) {
        float k = u[VideoFx.U_VHS];
        float tm = posMs / 1000f;
        long step = posMs / 40;
        final int n = 60;
        float[] v = new float[2 * (n + 1) * 2];
        int q = 0;
        for (int j = 0; j <= n; j++) {
            float fy = j / (float) n;
            float off = (VideoFx.hash1(j * 131L + step * 7) - 0.5f) * 0.004f * k;
            for (int i = 0; i < 2; i++) {
                float m = 1f - sstep(0f, 0.03f + 0.012f * i, Math.abs(fy - bandY(i, tm)));
                off += m * (VideoFx.hash1(j * 71L + step * 11 + i * 5) - 0.5f) * 0.1f * k;
            }
            off += sstep(0.94f, 1f, fy) * (0.01f + 0.03f * VideoFx.hash1(j * 17L + step)) * k;
            float y = dst.top + dst.height() * fy;
            v[q++] = dst.left + off * rw;
            v[q++] = y;
            v[q++] = dst.right + off * rw;
            v[q++] = y;
        }
        fxPaint.setFilterBitmap(true);
        fxPaint.setColorFilter(new ColorMatrixColorFilter(VideoFx.colorMatrix(u)));
        c.drawBitmapMesh(g, 1, n, v, 0, null, 0, fxPaint);
        fxPaint.setColorFilter(null);
    }

    /** Obiettivo fisheye: il fotogramma si gonfia al centro, si comprime ai bordi, fuori dal cerchio e' nero. */
    private void drawFishMesh(Canvas c, Bitmap g, float l, float t, float rw, float rh) {
        float th = 0.12f + 0.93f * Math.max(0f, Math.min(1f, u[VideoFx.U_FISH]));
        float tanT = (float) Math.tan(th * 1.02f);
        float asp = rw / rh;
        float cx = l + rw / 2, cy = t + rh / 2, half = rh / 2;
        Paint bk = new Paint();
        bk.setColor(0xFF000000);
        c.drawRect(l - 2, t - 2, l + rw + 2, t + rh + 2, bk);
        final int nx = 48, ny = 28;
        float[] v = new float[(nx + 1) * (ny + 1) * 2];
        int k = 0;
        for (int j = 0; j <= ny; j++) {
            for (int i = 0; i <= nx; i++) {
                float px = (i / (float) nx - 0.5f) * 2f * asp, py = (j / (float) ny - 0.5f) * 2f;
                float rs = (float) Math.sqrt(px * px + py * py);
                float f = 1f;
                if (rs > 1e-5f) {
                    f = (float) (Math.atan(rs * tanT) / th) / rs;
                }
                v[k++] = cx + px * f * half;
                v[k++] = cy + py * f * half;
            }
        }
        fxPaint.setFilterBitmap(true);
        fxPaint.setColorFilter(new ColorMatrixColorFilter(VideoFx.colorMatrix(u)));
        c.drawBitmapMesh(g, nx, ny, v, 0, null, 0, fxPaint);
        fxPaint.setColorFilter(null);
        // vignettatura della lente e bordo morbido, senza stacco netto
        Paint vp = new Paint();
        vp.setShader(new RadialGradient(cx, cy, half, new int[]{0, 0, 0xA6000000, 0xFF000000},
                new float[]{0f, 0.45f, 0.93f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(l - 2, t - 2, l + rw + 2, t + rh + 2, vp);
    }

    private static float rwBandY(int i, float tm, float spd) {
        return frac(tm * spd * (0.8f + 0.25f * i) + 0.27f * i + 0.1f);
    }

    private static float rwBand(float y, int i, float tm, float spd) {
        float hw = 0.07f + 0.03f * (i % 2);
        return 1f - sstep(hw * 0.55f, hw, Math.abs(y - rwBandY(i, tm, spd)));
    }

    private static int rwBands(float k) {
        return Math.min(4, 2 + Math.round(2 * k));
    }

    /** Fotogramma con le righe spostate nelle fasce di tracking che scendono, come in un rewind VHS. */
    private void drawRewindMesh(Canvas c, Bitmap g, RectF dst, float l, float t, float rw, float rh) {
        float k = Math.max(0f, Math.min(1f, u[VideoFx.U_RW]));
        float spd = u[VideoFx.U_RWDIR] > 0 ? 0.55f : 0.38f;
        float tm = posMs / 1000f;
        long step = posMs / 40;
        int nb = rwBands(k);
        final int n = 120;
        float[] v = new float[2 * (n + 1) * 2];
        int q = 0;
        for (int j = 0; j <= n; j++) {
            float fy = j / (float) n;
            float off = (VideoFx.hash1(j * 131L + step * 7) - 0.5f) * 0.006f * k
                    + (float) Math.sin(fy * 7f + tm * 2.5f) * 0.003f * k;
            for (int i = 0; i < nb; i++) {
                off += rwBand(fy, i, tm, spd) * (VideoFx.hash1(j * 71L + step * 11 + i * 5) - 0.5f) * 0.30f * k;
            }
            off += sstep(0.93f, 1f, fy) * (0.01f + 0.05f * VideoFx.hash1(j * 17L + step)) * k;
            float y = dst.top + dst.height() * fy;
            v[q++] = dst.left + off * rw;
            v[q++] = y;
            v[q++] = dst.right + off * rw;
            v[q++] = y;
        }
        ColorMatrix cm = new ColorMatrix(VideoFx.colorMatrix(u));
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1f - 0.35f * k);
        cm.postConcat(sat);
        fxPaint.setFilterBitmap(true);
        fxPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmapMesh(g, 1, n, v, 0, null, 0, fxPaint);
        fxPaint.setColorFilter(null);
    }

    /** Righe di disturbo nelle fasce di tracking, rumore leggero e dominante blu opzionale. */
    private void drawRewindOverlay(Canvas c, float l, float t, float rw, float rh) {
        float k = Math.max(0f, Math.min(1f, u[VideoFx.U_RW]));
        boolean blue = u[VideoFx.U_RWBLUE] > 0.5f;
        float spd = u[VideoFx.U_RWDIR] > 0 ? 0.55f : 0.38f;
        float tm = posMs / 1000f;
        long step = posMs / 40;
        java.util.Random r = new java.util.Random(step * 104729L + 7);
        float cell = Math.max(1f, rh / 240f);
        int nb = rwBands(k);
        c.save();
        c.clipRect(l, t, l + rw, t + rh);
        Paint p = new Paint();
        if (blue) {
            p.setColor(android.graphics.Color.argb(Math.round(255 * 0.30f * k), 16, 34, 170));
            c.drawRect(l, t, l + rw, t + rh, p);
        }
        // rumore leggero su tutto il fotogramma
        int ns = Math.round(260 * k);
        float[] pts = new float[ns * 2];
        for (int i = 0; i < ns; i++) {
            pts[2 * i] = l + r.nextFloat() * rw;
            pts[2 * i + 1] = t + r.nextFloat() * rh;
        }
        p.setColor(0x66FFFFFF);
        p.setStrokeWidth(cell);
        p.setStrokeCap(Paint.Cap.SQUARE);
        c.drawPoints(pts, p);
        int rows = 240;
        float rowH = rh / rows;
        int[] pal = {0xFF37E1E8, 0xFFE83CD2, 0xFF6BE04A, 0xFFF2E24A, 0xFFEFEFEF, 0xFF3A55E8, 0xFF2A2A2A};
        for (int j = 0; j < rows; j++) {
            float fy = (j + 0.5f) / rows;
            float m = sstep(0.93f, 1f, fy);
            for (int i = 0; i < nb; i++) {
                m = Math.max(m, rwBand(fy, i, tm, spd));
            }
            if (m < 0.15f) {
                continue;
            }
            float y = t + j * rowH;
            // fondo piu' scuro e sporco nella fascia
            p.setColor(android.graphics.Color.argb(Math.round(110 * m * k), 0, 0, 10));
            c.drawRect(l, y, l + rw, y + rowH + 0.5f, p);
            if (r.nextFloat() < 0.08f * m) {
                int g2 = 150 + r.nextInt(105);
                p.setColor(android.graphics.Color.argb(Math.round(180 * m * k), g2, g2, blue ? 255 : g2));
                c.drawRect(l, y, l + rw, y + rowH + 0.5f, p);
            }
            int nd = Math.round((6 + r.nextInt(10)) * m * k);
            for (int d = 0; d < nd; d++) {
                float x = l + r.nextFloat() * rw;
                float w = cell * (4 + r.nextInt(26));
                int col;
                if (blue) {
                    col = r.nextFloat() < 0.18f ? 0xFFDCE6FF : android.graphics.Color.rgb(12, 24 + r.nextInt(30), 120 + r.nextInt(120));
                } else {
                    col = pal[r.nextInt(pal.length)];
                }
                p.setColor((col & 0x00FFFFFF) | (Math.round(255 * Math.min(1f, 0.95f * m)) << 24));
                c.drawRect(x, y, x + w, y + rowH + 0.5f, p);
            }
        }
        c.restore();
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    /** Tinta violacea, puntini di neve, strisce bianche sulle fasce di disturbo e rumore in basso. */
    private void drawVhsOverlay(Canvas c, float l, float t, float rw, float rh) {
        float k = u[VideoFx.U_VHS];
        float tm = posMs / 1000f;
        java.util.Random r = new java.util.Random((posMs / 40) * 7919L + 13);
        float cell = Math.max(1f, rh / 240f);
        c.save();
        c.clipRect(l, t, l + rw, t + rh);
        Paint p = new Paint();
        p.setColor(android.graphics.Color.argb(Math.round(255 * 0.14f * k), 48, 16, 72));
        c.drawRect(l, t, l + rw, t + rh, p);
        int cnt = Math.min(1500, Math.round(rw * rh / (cell * cell) * 0.01f * k));
        float[] pts = new float[cnt * 2];
        for (int i = 0; i < cnt; i++) {
            pts[2 * i] = l + r.nextFloat() * rw;
            pts[2 * i + 1] = t + r.nextFloat() * rh;
        }
        Paint sp = new Paint();
        sp.setColor(0xCCDDDDDD);
        sp.setStrokeWidth(cell);
        sp.setStrokeCap(Paint.Cap.SQUARE);
        c.drawPoints(pts, sp);
        Paint dp = new Paint();
        dp.setColor(0xDDE0E0E0);
        for (int i = 0; i < 2; i++) {
            float y0 = t + bandY(i, tm) * rh;
            p.setColor(android.graphics.Color.argb(Math.round(255 * 0.12f * k), 255, 255, 255));
            c.drawRect(l, y0 - 0.012f * rh, l + rw, y0 + 0.012f * rh, p);
            for (int d = 0; d < 40; d++) {
                float yy = y0 + (r.nextFloat() - 0.5f) * 0.05f * rh;
                float x = l + r.nextFloat() * rw;
                float len = (0.01f + r.nextFloat() * 0.08f) * rw;
                c.drawRect(x, yy, x + len, yy + cell, dp);
            }
        }
        for (int d = 0; d < 40; d++) {
            float yy = t + (0.95f + r.nextFloat() * 0.05f) * rh;
            float x = l + r.nextFloat() * rw;
            c.drawRect(x, yy, x + (0.02f + r.nextFloat() * 0.1f) * rw, yy + cell, dp);
        }
        c.restore();
    }
}
