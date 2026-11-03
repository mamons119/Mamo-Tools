package it.mamotools;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Stabilizzazione video in due passaggi, solo API Android:
 * 1) analisi: si decodifica il video e per ogni coppia di fotogrammi si stima il movimento
 *    della camera (traslazione + rotazione) con block matching su immagini ridotte;
 * 2) si leviga il percorso della camera (media gaussiana) e si ricodifica il video
 *    compensando la differenza, con zoom per nascondere i bordi (OpenGL -> MediaCodec).
 * L'audio viene copiato senza modifiche.
 */
final class VideoStabilizer {

    static final class Params {
        float sigmaSec = 0.6f;   // fluidita': piu' alto = piu' stabile (e piu' ritaglio)
        boolean fixRoll = true;  // corregge anche l'inclinazione
        float maxZoom = 0.08f;   // zoom massimo per nascondere i bordi (0.02..0.25)
        boolean accurate = false; // analisi a risoluzione maggiore
    }

    private static final long TIMEOUT = 10000;

    private VideoStabilizer() {
    }

    // =========================================================================== entrata

    /** prm null = nessuna stabilizzazione (solo effetti e testi). */
    static void run(Context ctx, Uri src, File out, Params prm, java.util.List<VideoFx.Item> items,
                    int quality, VideoProcessor.Progress p) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, src, null);
        int vt = -1, at = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m == null) {
                continue;
            }
            if (m.startsWith("video/") && vt < 0) {
                vt = i;
            } else if (m.startsWith("audio/") && at < 0) {
                at = i;
            }
        }
        if (vt < 0) {
            ex.release();
            throw new IOException("il file non contiene video");
        }
        MediaFormat vf = ex.getTrackFormat(vt);
        ex.release();

        int rotation = 0;
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(ctx, src);
            String r = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (r != null) {
                rotation = Integer.parseInt(r);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }

        int w = vf.getInteger(MediaFormat.KEY_WIDTH) & ~1;
        int h = vf.getInteger(MediaFormat.KEY_HEIGHT) & ~1;
        long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
        int fps = vf.containsKey(MediaFormat.KEY_FRAME_RATE) ? vf.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
        if (fps <= 0 || fps > 120) {
            fps = 30;
        }

        Est est = null;
        float[][] corr = null;
        int base = 1;
        if (prm != null) {
            // ---- 1) analisi
            p.onProgress("Stabilizzazione: analisi del movimento...", 1);
            est = new Est(prm.accurate ? 320 : 192, w, h);
            float[][] motion = analyze(ctx, src, vf, est, durUs, p);
            if (motion.length < 3) {
                throw new IOException("video troppo corto per essere stabilizzato");
            }
            // ---- 2) percorso e correzioni
            p.onProgress("Stabilizzazione: calcolo del percorso...", 40);
            corr = corrections(motion, est, prm, fps, w, h);
            base = 41;
        }

        // ---- 3) rendering
        long srcRate = vf.containsKey(MediaFormat.KEY_BIT_RATE) ? vf.getInteger(MediaFormat.KEY_BIT_RATE)
                : (long) w * h * 5;
        long baseRate = Math.max(srcRate, (long) (0.15 * w * h * fps));
        double[] qf = {2.0, 1.2, 0.7, 0.4};
        int bitrate = (int) Math.max(1_000_000L, Math.min(400_000_000L,
                (long) (baseRate * qf[Math.max(0, Math.min(3, quality))])));
        render(ctx, src, vf, at, out, rotation, w, h, fps, bitrate, corr, est, items, base, durUs, p);
    }

    // =========================================================================== analisi

    /** Stima del movimento fra fotogrammi consecutivi su immagini in scala di grigi. */
    private static final class Est {
        final int w0, h0, w1, h1, r1;
        final int gx = 6, gy = 5;
        final int[] x0s, y0s;
        final int bw, bh;
        final int tw, th; // dimensioni video originale

        Est(int aw, int vw, int vh) {
            tw = vw;
            th = vh;
            w0 = aw & ~1;
            h0 = ((int) Math.round(aw * (double) vh / vw)) & ~1;
            w1 = w0 / 2;
            h1 = h0 / 2;
            r1 = Math.max(4, w0 / 48);
            bw = (w1 - 2 * r1) / gx;
            bh = (h1 - 2 * r1) / gy;
            x0s = new int[gx];
            y0s = new int[gy];
            for (int i = 0; i < gx; i++) {
                x0s[i] = r1 + i * bw;
            }
            for (int i = 0; i < gy; i++) {
                y0s[i] = r1 + i * bh;
            }
        }

        /** Restituisce {tx, ty, rot} in pixel del livello 0 (movimento del contenuto prev->cur). */
        float[] estimate(int[] p0, int[] c0, int[] p1, int[] c1) {
            int maxB = gx * gy;
            float[] px = new float[maxB], py = new float[maxB], vx = new float[maxB], vy = new float[maxB];
            int cnt = 0;
            for (int by = 0; by < gy; by++) {
                for (int bx = 0; bx < gx; bx++) {
                    int x0 = x0s[bx], y0 = y0s[by];
                    // texture sufficiente?
                    long grad = 0;
                    for (int y = y0; y < y0 + bh; y += 2) {
                        int row = y * w1;
                        for (int x = x0; x < x0 + bw - 1; x += 2) {
                            grad += Math.abs(c1[row + x + 1] - c1[row + x]);
                        }
                    }
                    if (grad < (long) (bw / 2) * (bh / 2) * 2) {
                        continue;
                    }
                    // livello 1: ricerca completa
                    long best = Long.MAX_VALUE, sum = 0;
                    int bu = 0, bv = 0, num = 0;
                    for (int v = -r1; v <= r1; v++) {
                        for (int u = -r1; u <= r1; u++) {
                            long s = 0;
                            for (int y = 0; y < bh; y++) {
                                int rc = (y0 + y) * w1 + x0;
                                int rp = (y0 + y + v) * w1 + x0 + u;
                                for (int x = 0; x < bw; x++) {
                                    s += Math.abs(c1[rc + x] - p1[rp + x]);
                                }
                            }
                            sum += s;
                            num++;
                            if (s < best) {
                                best = s;
                                bu = u;
                                bv = v;
                            }
                        }
                    }
                    double mean = sum / (double) num;
                    if (best > mean * 0.9) {
                        continue; // corrispondenza ambigua
                    }
                    // livello 0: raffinamento +-2 attorno a 2*(bu,bv)
                    int X0 = x0 * 2, Y0 = y0 * 2, BW = bw * 2, BH = bh * 2;
                    long b0 = Long.MAX_VALUE;
                    int fu = 2 * bu, fv = 2 * bv;
                    for (int v = 2 * bv - 2; v <= 2 * bv + 2; v++) {
                        for (int u = 2 * bu - 2; u <= 2 * bu + 2; u++) {
                            if (X0 + u < 0 || X0 + u + BW > w0 || Y0 + v < 0 || Y0 + v + BH > h0) {
                                continue;
                            }
                            long s = 0;
                            for (int y = 0; y < BH; y += 2) {
                                int rc = (Y0 + y) * w0 + X0;
                                int rp = (Y0 + y + v) * w0 + X0 + u;
                                for (int x = 0; x < BW; x += 2) {
                                    s += Math.abs(c0[rc + x] - p0[rp + x]);
                                }
                            }
                            if (s < b0) {
                                b0 = s;
                                fu = u;
                                fv = v;
                            }
                        }
                    }
                    px[cnt] = X0 + BW / 2f - w0 / 2f;
                    py[cnt] = Y0 + BH / 2f - h0 / 2f;
                    vx[cnt] = -fu;
                    vy[cnt] = -fv;
                    cnt++;
                }
            }
            if (cnt < 4) {
                return new float[]{0, 0, 0};
            }
            boolean[] use = new boolean[cnt];
            Arrays.fill(use, true);
            float[] sol = {0, 0, 0};
            for (int it = 0; it < 4; it++) {
                sol = fit(px, py, vx, vy, use, cnt);
                float[] res = new float[cnt];
                for (int i = 0; i < cnt; i++) {
                    float ex = vx[i] - (sol[0] - sol[2] * py[i]);
                    float ey = vy[i] - (sol[1] + sol[2] * px[i]);
                    res[i] = (float) Math.sqrt(ex * ex + ey * ey);
                }
                float[] sorted = res.clone();
                Arrays.sort(sorted);
                float med = sorted[cnt / 2];
                float thr = Math.max(1.0f, 1.8f * med);
                int kept = 0;
                for (int i = 0; i < cnt; i++) {
                    use[i] = res[i] <= thr;
                    if (use[i]) {
                        kept++;
                    }
                }
                if (kept < 4) {
                    break;
                }
            }
            return sol;
        }

        /** Minimi quadrati su v = t + th * (-y, x). */
        private static float[] fit(float[] px, float[] py, float[] vx, float[] vy, boolean[] use, int n) {
            double n1 = 0, sx = 0, sy = 0, sxx = 0, syy = 0, bx = 0, by = 0, bt = 0;
            for (int i = 0; i < n; i++) {
                if (!use[i]) {
                    continue;
                }
                n1++;
                sx += px[i];
                sy += py[i];
                sxx += px[i] * px[i];
                syy += py[i] * py[i];
                bx += vx[i];
                by += vy[i];
                bt += -py[i] * vx[i] + px[i] * vy[i];
            }
            double[][] m = {{n1, 0, -sy, bx}, {0, n1, sx, by}, {-sy, sx, sxx + syy, bt}};
            if (n1 < 1) {
                return new float[]{0, 0, 0};
            }
            return solve3(m);
        }

        private static float[] solve3(double[][] m) {
            for (int c = 0; c < 3; c++) {
                int piv = c;
                for (int r = c + 1; r < 3; r++) {
                    if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) {
                        piv = r;
                    }
                }
                double[] t = m[c];
                m[c] = m[piv];
                m[piv] = t;
                if (Math.abs(m[c][c]) < 1e-12) {
                    return new float[]{0, 0, 0};
                }
                for (int r = 0; r < 3; r++) {
                    if (r != c) {
                        double f = m[r][c] / m[c][c];
                        for (int k = c; k < 4; k++) {
                            m[r][k] -= f * m[c][k];
                        }
                    }
                }
            }
            return new float[]{(float) (m[0][3] / m[0][0]), (float) (m[1][3] / m[1][1]),
                    (float) (m[2][3] / m[2][2])};
        }
    }

    private static float[][] analyze(Context ctx, Uri src, MediaFormat vf, Est est, long durUs,
                                     VideoProcessor.Progress p) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        java.util.ArrayList<float[]> list = new java.util.ArrayList<>();
        try {
            ex.setDataSource(ctx, src, null);
            int vt = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    vt = i;
                    break;
                }
            }
            ex.selectTrack(vt);
            dec = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME));
            dec.configure(vf, null, null, 0);
            dec.start();

            int[] prev0 = null, prev1 = null;
            int[] cur0 = new int[est.w0 * est.h0];
            int[] cur1 = new int[est.w1 * est.h1];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int lastPct = -1;
            while (!outDone) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(TIMEOUT);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, TIMEOUT);
                if (oi >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        Image img = dec.getOutputImage(oi);
                        if (img == null) {
                            throw new IOException("formato video non supportato per l'analisi");
                        }
                        try {
                            gray(img, est, cur0);
                        } finally {
                            img.close();
                        }
                        half(cur0, est, cur1);
                        if (prev0 == null) {
                            list.add(new float[]{0, 0, 0});
                            prev0 = new int[cur0.length];
                            prev1 = new int[cur1.length];
                        } else {
                            list.add(est.estimate(prev0, cur0, prev1, cur1));
                        }
                        System.arraycopy(cur0, 0, prev0, 0, cur0.length);
                        System.arraycopy(cur1, 0, prev1, 0, cur1.length);
                        if (durUs > 0) {
                            int pct = (int) (1 + 38L * info.presentationTimeUs / durUs);
                            pct = Math.max(1, Math.min(39, pct));
                            if (pct != lastPct) {
                                lastPct = pct;
                                p.onProgress("Stabilizzazione: analisi del movimento...", pct);
                            }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outDone = true;
                    }
                }
            }
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                dec.release();
            }
            ex.release();
        }
        return list.toArray(new float[0][]);
    }

    private static void gray(Image img, Est e, int[] out) {
        Image.Plane pl = img.getPlanes()[0];
        ByteBuffer b = pl.getBuffer();
        int rs = pl.getRowStride(), ps = pl.getPixelStride();
        android.graphics.Rect cr = img.getCropRect();
        int cw = cr.width() > 0 ? cr.width() : img.getWidth();
        int ch = cr.height() > 0 ? cr.height() : img.getHeight();
        int ox = cr.left, oy = cr.top;
        for (int y = 0; y < e.h0; y++) {
            int sy0 = oy + (int) ((y + 0.25f) * ch / e.h0);
            int sy1 = oy + (int) ((y + 0.75f) * ch / e.h0);
            int r0 = sy0 * rs, r1 = sy1 * rs;
            for (int x = 0; x < e.w0; x++) {
                int sx0 = (ox + (int) ((x + 0.25f) * cw / e.w0)) * ps;
                int sx1 = (ox + (int) ((x + 0.75f) * cw / e.w0)) * ps;
                int s = (b.get(r0 + sx0) & 0xFF) + (b.get(r0 + sx1) & 0xFF)
                        + (b.get(r1 + sx0) & 0xFF) + (b.get(r1 + sx1) & 0xFF);
                out[y * e.w0 + x] = s >> 2;
            }
        }
    }

    private static void half(int[] a, Est e, int[] out) {
        for (int y = 0; y < e.h1; y++) {
            for (int x = 0; x < e.w1; x++) {
                int i = 2 * y * e.w0 + 2 * x;
                out[y * e.w1 + x] = (a[i] + a[i + 1] + a[i + e.w0] + a[i + e.w0 + 1]) >> 2;
            }
        }
    }

    // =========================================================================== percorso

    /**
     * Restituisce per ogni fotogramma {dx, dy, rot, zoom}: spostamento del contenuto in pixel
     * del livello 0, rotazione in radianti e zoom (uguale per tutti).
     */
    private static float[][] corrections(float[][] mot, Est e, Params prm, int fps, int w, int h) {
        int n = mot.length;
        double[] px = new double[n], py = new double[n], pa = new double[n];
        for (int i = 1; i < n; i++) {
            px[i] = px[i - 1] + mot[i][0];
            py[i] = py[i - 1] + mot[i][1];
            pa[i] = pa[i - 1] + (prm.fixRoll ? mot[i][2] : 0);
        }
        double sigma = Math.max(1.0, prm.sigmaSec * fps);
        int rad = (int) Math.ceil(3 * sigma);
        double[] kern = new double[2 * rad + 1];
        double ks = 0;
        for (int k = -rad; k <= rad; k++) {
            kern[k + rad] = Math.exp(-0.5 * k * k / (sigma * sigma));
            ks += kern[k + rad];
        }
        float[][] c = new float[n][4];
        for (int i = 0; i < n; i++) {
            double sx = 0, sy = 0, sa = 0;
            for (int k = -rad; k <= rad; k++) {
                int j = Math.max(0, Math.min(n - 1, i + k));
                double wgt = kern[k + rad];
                sx += wgt * px[j];
                sy += wgt * py[j];
                sa += wgt * pa[j];
            }
            c[i][0] = (float) (sx / ks - px[i]);
            c[i][1] = (float) (sy / ks - py[i]);
            c[i][2] = (float) (sa / ks - pa[i]);
        }
        // zoom necessario (fisso per tutto il video)
        double ratio = Math.max(w / (double) h, h / (double) w);
        double zMax = 1.0 + prm.maxZoom;
        double need = 1.0;
        for (int i = 0; i < n; i++) {
            need = Math.max(need, zoomFor(c[i][0] / e.w0, c[i][1] / e.h0, c[i][2], ratio));
        }
        double z = Math.min(zMax, need);
        // riduce le correzioni dei fotogrammi che non ci stanno
        for (int i = 0; i < n; i++) {
            double lo = 0, hi = 1;
            if (zoomFor(c[i][0] / e.w0, c[i][1] / e.h0, c[i][2], ratio) > z) {
                for (int it = 0; it < 14; it++) {
                    double mid = (lo + hi) / 2;
                    if (zoomFor(mid * c[i][0] / e.w0, mid * c[i][1] / e.h0, mid * c[i][2], ratio) <= z) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                c[i][0] *= lo;
                c[i][1] *= lo;
                c[i][2] *= lo;
            }
            c[i][3] = (float) z;
        }
        return c;
    }

    /** Zoom minimo per non mostrare bordi con spostamento (fx, fy in frazione) e rotazione a. */
    private static double zoomFor(double fx, double fy, double a, double ratio) {
        double m = 2 * Math.max(Math.abs(fx), Math.abs(fy));
        if (m >= 0.95) {
            return 100;
        }
        double aa = Math.abs(a);
        return (Math.cos(aa) + ratio * Math.sin(aa)) / (1 - m);
    }

    // =========================================================================== rendering

    private static final String VS = "attribute vec2 aPos;\n"
            + "attribute vec2 aTex;\n"
            + "uniform float asp;\n"
            + "uniform float zoom;\n"
            + "uniform vec2 d;\n"
            + "uniform vec2 rot;\n"
            + "uniform mat4 uST;\n"
            + "varying vec2 vTc;\n"
            + "varying vec2 vPos;\n"
            + "void main(){\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "  vPos = aTex;\n"
            + "  vec2 p = aTex - 0.5; p.x *= asp;\n"
            + "  vec2 u = p / zoom - d;\n"
            + "  vec2 q = vec2(rot.x*u.x + rot.y*u.y, -rot.y*u.x + rot.x*u.y);\n"
            + "  q.x /= asp; q += 0.5;\n"
            + "  vTc = (uST * vec4(q.x, 1.0 - q.y, 0.0, 1.0)).xy;\n"
            + "}\n";

    private static final String FS = "#extension GL_OES_EGL_image_external : require\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "varying vec2 vTc;\n"
            + "varying vec2 vPos;\n"
            + "uniform samplerExternalOES sTex;\n"
            + "uniform vec2 texel;\n"
            + "uniform float uGray, uSepia, uInv, uVig, uBlur, uPix, uSat, uCon, uFade, uSplit;\n"
            + "vec4 s(vec2 uv){ return texture2D(sTex, uv); }\n"
            + "void main(){\n"
            + "  vec2 uv = vTc;\n"
            + "  if (uPix > 0.0) { vec2 b = texel * (4.0 + uPix * 40.0); uv = (floor(uv / b) + 0.5) * b; }\n"
            + "  vec4 c;\n"
            + "  if (uBlur > 0.0) {\n"
            + "    float r = uBlur * 14.0;\n"
            + "    c = s(uv) * 0.12;\n"
            + "    for (int i = 0; i < 8; i++) {\n"
            + "      float a = float(i) * 0.785398;\n"
            + "      vec2 dir = vec2(cos(a), sin(a)) * texel;\n"
            + "      c += s(uv + dir * r) * 0.07 + s(uv + dir * r * 0.5) * 0.05;\n"
            + "    }\n"
            + "  } else if (uSplit > 0.0) {\n"
            + "    vec2 o = vec2(texel.x * uSplit * 14.0, 0.0);\n"
            + "    c = vec4(s(uv + o).r, s(uv).g, s(uv - o).b, 1.0);\n"
            + "  } else { c = s(uv); }\n"
            + "  vec3 col = c.rgb;\n"
            + "  float l = dot(col, vec3(0.299, 0.587, 0.114));\n"
            + "  col = mix(col, vec3(l), uGray);\n"
            + "  vec3 sep = vec3(dot(col, vec3(0.393, 0.769, 0.189)), dot(col, vec3(0.349, 0.686, 0.168)),"
            + " dot(col, vec3(0.272, 0.534, 0.131)));\n"
            + "  col = mix(col, sep, uSepia);\n"
            + "  col = mix(col, 1.0 - col, uInv);\n"
            + "  float l2 = dot(col, vec3(0.299, 0.587, 0.114));\n"
            + "  col = mix(vec3(l2), col, uSat);\n"
            + "  col = (col - 0.5) * uCon + 0.5;\n"
            + "  float dd = distance(vPos, vec2(0.5));\n"
            + "  col *= 1.0 - uVig * smoothstep(0.3, 0.85, dd) * 1.1;\n"
            + "  col *= uFade;\n"
            + "  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private static final String VS_T = "attribute vec2 aPos;\n"
            + "attribute vec2 aTex;\n"
            + "uniform vec2 uC;\n"
            + "uniform vec2 uH;\n"
            + "varying vec2 vTex;\n"
            + "void main(){ gl_Position = vec4(uC + aPos * uH, 0.0, 1.0); vTex = aTex; }\n";

    private static final String FS_T = "precision mediump float;\n"
            + "varying vec2 vTex;\n"
            + "uniform sampler2D sT;\n"
            + "uniform float uA;\n"
            + "void main(){ gl_FragColor = texture2D(sT, vTex) * uA; }\n";

    private static int shader(int type, String src) throws IOException {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IOException("shader non compilato: " + log);
        }
        return s;
    }

    private static void render(Context ctx, Uri src, MediaFormat vf, int audioTrackIdx, File out,
                               int rotation, int w, int h, int fps, int bitrate, float[][] corr,
                               Est est, java.util.List<VideoFx.Item> items, int base, long durUs,
                               VideoProcessor.Progress p) throws IOException {
        MediaCodec enc = null, dec = null;
        MediaMuxer mx = null;
        MediaExtractor ex = new MediaExtractor();
        MediaExtractor ae = null;
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ectx = EGL14.EGL_NO_CONTEXT;
        EGLSurface esurf = EGL14.EGL_NO_SURFACE;
        SurfaceTexture st = null;
        Surface decSurface = null, encSurface = null;
        try {
            // encoder
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", w, h);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            enc = MediaCodec.createEncoderByType("video/avc");
            try {
                android.util.Range<Integer> br = enc.getCodecInfo().getCapabilitiesForType("video/avc")
                        .getVideoCapabilities().getBitrateRange();
                ef.setInteger(MediaFormat.KEY_BIT_RATE, br.clamp(bitrate));
            } catch (Exception ignored) {
            }
            try {
                ef.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            } catch (Exception ignored) {
            }
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encSurface = enc.createInputSurface();
            enc.start();

            // EGL
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
                throw new IOException("EGL non disponibile");
            }
            int[] cfgAttr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142, 1, EGL14.EGL_NONE};
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] nc = new int[1];
            EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, nc, 0);
            if (nc[0] <= 0) {
                throw new IOException("configurazione grafica non disponibile");
            }
            ectx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            esurf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], encSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(dpy, esurf, esurf, ectx)) {
                throw new IOException("contesto grafico non attivabile");
            }
            GLES20.glViewport(0, 0, w, h);

            int vs = shader(GLES20.GL_VERTEX_SHADER, VS), fs = shader(GLES20.GL_FRAGMENT_SHADER, FS);
            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            int[] lk = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, lk, 0);
            if (lk[0] == 0) {
                throw new IOException("programma grafico non valido");
            }
            GLES20.glUseProgram(prog);
            int aPos = GLES20.glGetAttribLocation(prog, "aPos"), aTex = GLES20.glGetAttribLocation(prog, "aTex");
            int uAsp = GLES20.glGetUniformLocation(prog, "asp"), uZoom = GLES20.glGetUniformLocation(prog, "zoom");
            int uD = GLES20.glGetUniformLocation(prog, "d"), uRot = GLES20.glGetUniformLocation(prog, "rot");
            int uST = GLES20.glGetUniformLocation(prog, "uST");
            int uTexel = GLES20.glGetUniformLocation(prog, "texel");
            String[] un = {"uGray", "uSepia", "uInv", "uVig", "uBlur", "uPix", "uSat", "uCon", "uFade", "uSplit"};
            int[] uFx = new int[un.length];
            for (int i = 0; i < un.length; i++) {
                uFx[i] = GLES20.glGetUniformLocation(prog, un[i]);
            }
            float[] fxu = new float[VideoFx.U_COUNT];

            float[] quad = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};
            FloatBuffer fb = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(quad);
            fb.position(0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aTex);
            fb.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, fb);
            fb.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, fb);

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // ---- programma per i testi
            int prog2 = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog2, shader(GLES20.GL_VERTEX_SHADER, VS_T));
            GLES20.glAttachShader(prog2, shader(GLES20.GL_FRAGMENT_SHADER, FS_T));
            GLES20.glLinkProgram(prog2);
            int[] lk2 = new int[1];
            GLES20.glGetProgramiv(prog2, GLES20.GL_LINK_STATUS, lk2, 0);
            if (lk2[0] == 0) {
                throw new IOException("programma grafico dei testi non valido");
            }
            int tPos = GLES20.glGetAttribLocation(prog2, "aPos"), tTex = GLES20.glGetAttribLocation(prog2, "aTex");
            int tC = GLES20.glGetUniformLocation(prog2, "uC"), tH = GLES20.glGetUniformLocation(prog2, "uH");
            int tA = GLES20.glGetUniformLocation(prog2, "uA"), tS = GLES20.glGetUniformLocation(prog2, "sT");
            boolean swap = rotation == 90 || rotation == 270;
            int dispW = swap ? h : w, dispH = swap ? w : h;
            java.util.List<VideoFx.Item> texts = new java.util.ArrayList<>();
            java.util.List<int[]> textTex = new java.util.ArrayList<>();
            java.util.List<int[]> textSz = new java.util.ArrayList<>();
            if (items != null) {
                for (VideoFx.Item it : items) {
                    if (!it.isText || it.text == null || it.text.trim().isEmpty()) {
                        continue;
                    }
                    android.graphics.Bitmap bm = VideoFx.render(it, dispW, dispH, rotation);
                    if (bm == null) {
                        continue;
                    }
                    int[] tt = new int[1];
                    GLES20.glGenTextures(1, tt, 0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tt[0]);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                    texts.add(it);
                    textTex.add(tt);
                    textSz.add(new int[]{bm.getWidth(), bm.getHeight()});
                    bm.recycle();
                }
            }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);

            final Object lock = new Object();
            final boolean[] avail = {false};
            st = new SurfaceTexture(tex[0]);
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture s) {
                    synchronized (lock) {
                        avail[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            decSurface = new Surface(st);

            // sorgente
            ex.setDataSource(ctx, src, null);
            int vt = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    vt = i;
                    break;
                }
            }
            ex.selectTrack(vt);
            // la rotazione e' gia' gestita dall'indicazione di orientamento del file di uscita: se il
            // decoder la applicasse anche alla texture il video risulterebbe ruotato due volte
            vf.setInteger("rotation-degrees", 0);
            dec = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME));
            dec.configure(vf, decSurface, null, 0);
            dec.start();

            // audio
            MediaFormat af = null;
            if (audioTrackIdx >= 0) {
                ae = new MediaExtractor();
                ae.setDataSource(ctx, src, null);
                for (int i = 0; i < ae.getTrackCount(); i++) {
                    String m = ae.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) {
                        ae.selectTrack(i);
                        af = ae.getTrackFormat(i);
                        break;
                    }
                }
            }

            mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) {
                mx.setOrientationHint(rotation);
            }

            float[] stm = new float[16];
            float asp = w / (float) h;
            int idx = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo einfo = new MediaCodec.BufferInfo();
            boolean inDone = false, decDone = false;
            int[] muxTracks = {-1, -1};
            boolean[] started = {false};
            int lastPct = -1;
            // dimensioni del livello 0 usate nell'analisi: servono per convertire in unita' shader
            int ah = est != null ? est.h0 : 1;

            while (!decDone) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(TIMEOUT);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, TIMEOUT);
                if (oi >= 0) {
                    boolean draw = info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    dec.releaseOutputBuffer(oi, draw);
                    if (draw) {
                        synchronized (lock) {
                            long t0 = System.currentTimeMillis();
                            while (!avail[0]) {
                                try {
                                    lock.wait(500);
                                } catch (InterruptedException ignored) {
                                }
                                if (!avail[0] && System.currentTimeMillis() - t0 > 5000) {
                                    throw new IOException("il decoder non fornisce fotogrammi");
                                }
                            }
                            avail[0] = false;
                        }
                        st.updateTexImage();
                        st.getTransformMatrix(stm);
                        long ptsUs = info.presentationTimeUs;
                        VideoFx.uniforms(items, ptsUs, fxu);
                        GLES20.glUseProgram(prog);
                        GLES20.glDisable(GLES20.GL_BLEND);
                        GLES20.glEnableVertexAttribArray(aPos);
                        GLES20.glEnableVertexAttribArray(aTex);
                        fb.position(0);
                        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, fb);
                        fb.position(2);
                        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, fb);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
                        float cz = 1f, cdx = 0f, cdy = 0f, cr = 0f;
                        if (corr != null) {
                            float[] c = corr[Math.min(idx, corr.length - 1)];
                            cz = c[3];
                            cdx = c[0] / ah;
                            cdy = c[1] / ah;
                            cr = c[2];
                        }
                        GLES20.glUniform1f(uAsp, asp);
                        GLES20.glUniform1f(uZoom, cz * fxu[VideoFx.U_ZOOM]);
                        GLES20.glUniform2f(uD, cdx, cdy);
                        GLES20.glUniform2f(uRot, (float) Math.cos(cr), (float) Math.sin(cr));
                        GLES20.glUniform2f(uTexel, 1f / w, 1f / h);
                        for (int k = 0; k < uFx.length; k++) {
                            GLES20.glUniform1f(uFx[k], fxu[k]);
                        }
                        GLES20.glUniformMatrix4fv(uST, 1, false, stm, 0);
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                        // testi
                        if (!texts.isEmpty()) {
                            GLES20.glUseProgram(prog2);
                            GLES20.glEnable(GLES20.GL_BLEND);
                            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                            GLES20.glEnableVertexAttribArray(tPos);
                            GLES20.glEnableVertexAttribArray(tTex);
                            fb.position(0);
                            GLES20.glVertexAttribPointer(tPos, 2, GLES20.GL_FLOAT, false, 16, fb);
                            fb.position(2);
                            GLES20.glVertexAttribPointer(tTex, 2, GLES20.GL_FLOAT, false, 16, fb);
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                            GLES20.glUniform1i(tS, 0);
                            for (int k = 0; k < texts.size(); k++) {
                                VideoFx.Item it = texts.get(k);
                                if (!it.activeAt(ptsUs)) {
                                    continue;
                                }
                                float al = 1f;
                                float fade = 250000f;
                                al = Math.min(al, (ptsUs - it.startUs) / fade);
                                al = Math.min(al, (it.endUs - ptsUs) / fade);
                                al = Math.max(0f, Math.min(1f, al));
                                float fu = it.x, fv = it.y, fxc, fyc;
                                if (rotation == 90) {
                                    fxc = fv;
                                    fyc = 1 - fu;
                                } else if (rotation == 270) {
                                    fxc = 1 - fv;
                                    fyc = fu;
                                } else if (rotation == 180) {
                                    fxc = 1 - fu;
                                    fyc = 1 - fv;
                                } else {
                                    fxc = fu;
                                    fyc = fv;
                                }
                                int[] sz = textSz.get(k);
                                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex.get(k)[0]);
                                GLES20.glUniform2f(tC, 2 * fxc - 1, 1 - 2 * fyc);
                                GLES20.glUniform2f(tH, sz[0] / (float) w, sz[1] / (float) h);
                                GLES20.glUniform1f(tA, al);
                                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                            }
                            GLES20.glDisable(GLES20.GL_BLEND);
                        }
                        EGLExt.eglPresentationTimeANDROID(dpy, esurf, info.presentationTimeUs * 1000L);
                        EGL14.eglSwapBuffers(dpy, esurf);
                        idx++;
                        if (durUs > 0) {
                            int pct = (int) (base + (95 - base) * info.presentationTimeUs / durUs);
                            pct = Math.max(base, Math.min(95, pct));
                            if (pct != lastPct) {
                                lastPct = pct;
                                p.onProgress("Applicazione di stabilizzazione/effetti...", pct);
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decDone = true;
                    }
                }
                drain(enc, mx, einfo, af, muxTracks, started, false);
            }
            enc.signalEndOfInputStream();
            drain(enc, mx, einfo, af, muxTracks, started, true);

            // audio copiato com'e'
            if (af != null && muxTracks[1] >= 0 && started[0]) {
                ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024);
                MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
                while (true) {
                    buf.clear();
                    int size = ae.readSampleData(buf, 0);
                    if (size < 0) {
                        break;
                    }
                    ai.set(0, size, ae.getSampleTime(), MediaCodec.BUFFER_FLAG_KEY_FRAME);
                    mx.writeSampleData(muxTracks[1], buf, ai);
                    ae.advance();
                }
            }
            if (started[0]) {
                mx.stop();
            } else {
                throw new IOException("nessun fotogramma prodotto");
            }
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                dec.release();
            }
            if (enc != null) {
                try {
                    enc.stop();
                } catch (Exception ignored) {
                }
                enc.release();
            }
            if (mx != null) {
                try {
                    mx.release();
                } catch (Exception ignored) {
                }
            }
            if (st != null) {
                st.release();
            }
            if (decSurface != null) {
                decSurface.release();
            }
            if (encSurface != null) {
                encSurface.release();
            }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (esurf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(dpy, esurf);
                }
                if (ectx != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(dpy, ectx);
                }
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(dpy);
            }
            ex.release();
            if (ae != null) {
                ae.release();
            }
        }
    }

    private static void drain(MediaCodec enc, MediaMuxer mx, MediaCodec.BufferInfo info, MediaFormat af,
                              int[] tracks, boolean[] started, boolean end) throws IOException {
        while (true) {
            int oi = enc.dequeueOutputBuffer(info, end ? TIMEOUT : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) {
                    return;
                }
                continue;
            }
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                tracks[0] = mx.addTrack(enc.getOutputFormat());
                if (af != null) {
                    tracks[1] = mx.addTrack(af);
                }
                mx.start();
                started[0] = true;
            } else if (oi >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && started[0]) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    mx.writeSampleData(tracks[0], ob, info);
                }
                enc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            }
        }
    }
}
