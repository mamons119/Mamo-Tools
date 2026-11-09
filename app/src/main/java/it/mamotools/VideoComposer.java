package it.mamotools;

import android.content.Context;
import android.graphics.SurfaceTexture;
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
import java.util.List;

/**
 * Monta piu' tratti di video (anche di file diversi) in un unico file MP4 con transizioni.
 * Ogni tratto e' adattato al formato di uscita (bande nere se le proporzioni sono diverse).
 * Le transizioni usano il primo/ultimo fotogramma "congelati" e aggiungono la loro durata al video.
 */
final class VideoComposer {

    static final class Seg {
        Uri uri;
        long startUs, endUs;
        int trans;       // transizione che precede questo tratto (0 = nessuna)
        long transUs;    // durata della transizione
        float[] tp = new float[4];   // parametri della transizione (vedi params())

        Seg(Uri uri, long startUs, long endUs) {
            this.uri = uri;
            this.startUs = startUs;
            this.endUs = endUs;
        }

        Seg copy() {
            Seg s = new Seg(uri, startUs, endUs);
            s.trans = trans;
            s.transUs = transUs;
            s.tp = tp.clone();
            return s;
        }
    }

    static final String[] TRANS_NAMES = {"Nessuna", "Dissolvenza", "Dissolvenza al nero",
            "Scorrimento", "Tendina", "Zoom", "Pixel", "VHS noise glitch"};

    /** Un parametro regolabile di una transizione. */
    static final class Param {
        static final int PERCENT = 0, COUNT = 1, CHOICE = 2, TIME = 3;
        final String label, hint;
        final int kind;
        final float def, min, max;
        final String[] choices;

        Param(String label, String hint, int kind, float def, float min, float max, String[] choices) {
            this.label = label;
            this.hint = hint;
            this.kind = kind;
            this.def = def;
            this.min = min;
            this.max = max;
            this.choices = choices;
        }
    }

    private static Param pc(String l, String h, float d) {
        return new Param(l, h, Param.PERCENT, d, 0, 1, null);
    }

    private static Param tm(String l, String h, float d) {
        return new Param(l, h, Param.TIME, d, 0, 1, null);
    }

    private static Param ch(String l, float d, String... c) {
        return new Param(l, null, Param.CHOICE, d, 0, c.length - 1, c);
    }

    /** Parametri personalizzabili di ogni transizione (al massimo 4). */
    static Param[] params(int type) {
        switch (type) {
            case 1:
                return new Param[]{pc("Morbidezza", "0% = passaggio lineare, 100% = parte e finisce piano", 0.7f)};
            case 2:
                return new Param[]{tm("Momento del nero totale", "quando lo schermo e' tutto nero", 0.5f),
                        pc("Quanto resta nero", "pausa al buio tra le due clip", 0.1f)};
            case 3:
                return new Param[]{ch("Da dove entra la nuova clip", 0, "Da destra", "Da sinistra", "Dall'alto",
                        "Dal basso"), pc("Morbidezza del movimento", "parte e finisce piano", 0.5f)};
            case 4:
                return new Param[]{ch("Da dove parte la tendina", 0, "Da sinistra", "Da destra", "Dall'alto",
                        "Dal basso"), pc("Bordo sfumato", "quanto e' morbido il bordo", 0.1f)};
            case 5:
                return new Param[]{pc("Intensita' dello zoom", null, 0.5f),
                        pc("Dissolvenza", "0% = cambio netto a meta', 100% = dissolvenza", 1f)};
            case 6:
                return new Param[]{pc("Grandezza dei pixel", "quanto diventano grossi al culmine", 0.8f),
                        tm("Momento del cambio clip", "dove la clip precedente lascia posto alla successiva", 0.5f)};
            case 7:
                return new Param[]{new Param("Barre verticali", "in quante colonne si divide lo schermo",
                        Param.COUNT, 12, 2, 40, null),
                        pc("Livello di interferenza", "disturbi, rumore, colori sfalsati", 0.7f),
                        tm("Inizio scomparsa clip precedente", "da qui la clip precedente comincia a sparire", 0.25f),
                        tm("Inizio comparsa clip successiva", "da qui la clip successiva comincia ad apparire", 0.5f)};
            default:
                return new Param[0];
        }
    }

    static float[] defaults(int type) {
        float[] d = new float[4];
        Param[] ps = params(type);
        for (int i = 0; i < ps.length; i++) {
            d[i] = ps[i].def;
        }
        return d;
    }

    private static final long TIMEOUT = 10000;
    private static final long FRAME_US = 33333;

    private VideoComposer() {
    }

    /** Durata (in us) che un tratto occupa nel video montato, transizione compresa. */
    static long lengthUs(Seg s, boolean first) {
        return (s.endUs - s.startUs) + (!first && s.trans > 0 ? s.transUs : 0);
    }

    static int[] fitSize(int dw, int dh, int maxSide) {
        float sc = Math.min(1f, maxSide / (float) Math.max(dw, dh));
        int w = Math.max(16, Math.round(dw * sc / 2f) * 2);
        int h = Math.max(16, Math.round(dh * sc / 2f) * 2);
        return new int[]{w, h};
    }

    // ------------------------------------------------------------------ shader

    private static final String VS_V = "attribute vec2 aPos;\n"
            + "attribute vec2 aD;\n"
            + "uniform mat4 uST;\n"
            + "uniform vec4 uA;\n"
            + "uniform vec2 uB;\n"
            + "varying vec2 vTc;\n"
            + "void main(){\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "  vec2 s = vec2(uA.x * aD.x + uA.y * aD.y, uA.z * aD.x + uA.w * aD.y) + uB;\n"
            + "  vTc = (uST * vec4(s.x, 1.0 - s.y, 0.0, 1.0)).xy;\n"
            + "}\n";

    private static final String FS_V = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTc;\n"
            + "uniform samplerExternalOES sTex;\n"
            + "void main(){ gl_FragColor = texture2D(sTex, vTc); }\n";

    private static final String VS_X = "attribute vec2 aPos;\n"
            + "attribute vec2 aTex;\n"
            + "varying vec2 vUv;\n"
            + "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUv = aTex; }\n";

    static final String FS_X = ""
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "varying vec2 vUv;\n"
            + "uniform sampler2D sA;\n"
            + "uniform sampler2D sB;\n"
            + "uniform float uType;\n"
            + "uniform float uP;\n"
            + "uniform vec4 uQ;\n"
            + "uniform vec2 uRes;\n"
            + "vec4 A(vec2 u){ return texture2D(sA, clamp(u, 0.0, 1.0)); }\n"
            + "vec4 B(vec2 u){ return texture2D(sB, clamp(u, 0.0, 1.0)); }\n"
            + "float h11(float x){ return fract(sin(x * 127.1) * 43758.5453); }\n"
            + "float h21(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
            + "float ease(float p, float k){ return mix(p, p * p * (3.0 - 2.0 * p), k); }\n"
            + "void main(){\n"
            + "  vec2 u = vUv;\n"
            + "  vec4 c;\n"
            + "  if (uType < 0.5) {\n"
            + "    c = B(u);\n"
            + "  } else if (uType < 1.5) {\n"
            + "    c = mix(A(u), B(u), ease(uP, uQ.x));\n"
            + "  } else if (uType < 2.5) {\n"
            + "    float cc = clamp(uQ.x, 0.05, 0.95);\n"
            + "    float hd = max(0.0, min(uQ.y * 0.5, min(cc - 0.02, 0.98 - cc)));\n"
            + "    float e1 = max(cc - hd, 0.02);\n"
            + "    float e2 = max(1.0 - cc - hd, 0.02);\n"
            + "    float b = uP < cc - hd ? smoothstep(0.0, 1.0, uP / e1)\n"
            + "        : (uP > cc + hd ? 1.0 - smoothstep(0.0, 1.0, (uP - cc - hd) / e2) : 1.0);\n"
            + "    vec4 src = uP < cc ? A(u) : B(u);\n"
            + "    c = src * (1.0 - b);\n"
            + "  } else if (uType < 3.5) {\n"
            + "    float q = ease(uP, uQ.y);\n"
            + "    float d = floor(uQ.x + 0.5);\n"
            + "    vec2 dv = d < 0.5 ? vec2(1.0, 0.0) : (d < 1.5 ? vec2(-1.0, 0.0) : (d < 2.5 ? vec2(0.0, 1.0) : vec2(0.0, -1.0)));\n"
            + "    float m = dot(u - 0.5, dv) + 0.5;\n"
            + "    c = m < 1.0 - q ? A(u + dv * q) : B(u - dv * (1.0 - q));\n"
            + "  } else if (uType < 4.5) {\n"
            + "    float d = floor(uQ.x + 0.5);\n"
            + "    float m = d < 0.5 ? u.x : (d < 1.5 ? 1.0 - u.x : (d < 2.5 ? 1.0 - u.y : u.y));\n"
            + "    float w = uQ.y * 0.3 + 0.002;\n"
            + "    float r = uP * (1.0 + 2.0 * w) - w;\n"
            + "    c = mix(B(u), A(u), smoothstep(r - w, r + w, m));\n"
            + "  } else if (uType < 5.5) {\n"
            + "    float k = uQ.x * 1.2;\n"
            + "    float q = uP;\n"
            + "    vec2 ua = 0.5 + (u - 0.5) / (1.0 + q * k);\n"
            + "    vec2 ub = 0.5 + (u - 0.5) / (1.0 + (1.0 - q) * k);\n"
            + "    float w = mix(step(0.5, q), smoothstep(0.0, 1.0, q), uQ.y);\n"
            + "    c = mix(A(ua), B(ub), w);\n"
            + "  } else if (uType < 6.5) {\n"
            + "    float s = clamp(uQ.y, 0.1, 0.9);\n"
            + "    float env = uP < s ? smoothstep(0.0, 1.0, uP / s) : 1.0 - smoothstep(0.0, 1.0, (uP - s) / (1.0 - s));\n"
            + "    float cells = mix(2000.0, mix(200.0, 6.0, uQ.x), env);\n"
            + "    vec2 g = (floor(u * cells) + 0.5) / cells;\n"
            + "    c = uP > s ? B(g) : A(g);\n"
            + "  } else {\n"
            + "    float p = uP;\n"
            + "    float N = max(2.0, floor(uQ.x + 0.5));\n"
            + "    float I = uQ.y;\n"
            + "    float win = 0.2;\n"
            + "    float tA = min(uQ.z, 1.0);\n"
            + "    float tB = min(uQ.w, 1.0 - win);\n"
            + "    float wa = 1.0 - smoothstep(tA, tA + win, p);\n"
            + "    float wb = smoothstep(tB, tB + win, p);\n"
            + "    float ts = floor(p * 24.0);\n"
            + "    float st = min(tA, tB) - 0.12;\n"
            + "    float en = max(tA, tB) + win + 0.12;\n"
            + "    float env = smoothstep(st, st + 0.12, p) * (1.0 - smoothstep(en - 0.12, en, p));\n"
            + "    env = clamp(env, 0.0, 1.0) * smoothstep(0.0, 0.06, p) * (1.0 - smoothstep(0.94, 1.0, p));\n"
            + "    float e = env * I;\n"
            + "    vec2 uv = u;\n"
            + "    float col = floor(u.x * N);\n"
            + "    float rc = h21(vec2(col, ts));\n"
            + "    uv.y += (rc - 0.5) * 0.6 * e * step(0.35, h21(vec2(col + 17.0, ts)));\n"
            + "    float band = floor(u.y * 48.0);\n"
            + "    float rb = h21(vec2(band, ts + 31.0));\n"
            + "    float tear = step(1.0 - 0.4 * e, rb) * (h21(vec2(band, ts + 7.0)) - 0.5) * 0.35 * e;\n"
            + "    float trackY = fract(0.9 - p * 1.3);\n"
            + "    float tb = smoothstep(0.06, 0.0, abs(u.y - trackY));\n"
            + "    tear += tb * 0.07 * e * sin(u.y * 220.0 + ts);\n"
            + "    uv.x += tear;\n"
            + "    float cs = 0.014 * e * (0.4 + h21(vec2(ts, 3.0)));\n"
            + "    vec3 a = vec3(A(uv + vec2(cs, 0.0)).r, A(uv).g, A(uv - vec2(cs, 0.0)).b);\n"
            + "    vec3 b = vec3(B(uv + vec2(cs, 0.0)).r, B(uv).g, B(uv - vec2(cs, 0.0)).b);\n"
            + "    float g = h21(floor(u * uRes * 0.5) + ts);\n"
            + "    vec3 bg = mix(vec3(g) * 0.8, vec3(0.05, 0.1, 0.35) + g * 0.2, step(0.72, h11(ts * 1.7)));\n"
            + "    float rra = h21(vec2(col, floor(u.y * 12.0) + 3.0));\n"
            + "    float rrb = h21(vec2(col + 5.0, floor(u.y * 12.0) + 9.0));\n"
            + "    float waP = smoothstep(rra * 0.7 - 0.15, rra * 0.7 + 0.05, wa) * smoothstep(0.0, 0.05, wa);\n"
            + "    float wbP = smoothstep(rrb * 0.7 - 0.15, rrb * 0.7 + 0.05, wb) * smoothstep(0.0, 0.05, wb);\n"
            + "    vec3 base = mix(bg, a, waP);\n"
            + "    vec3 col3 = mix(base, b, wbP);\n"
            + "    col3 *= 1.0 - 0.14 * e * (0.5 + 0.5 * sin(u.y * uRes.y * 3.14159));\n"
            + "    float g2 = h21(u * uRes + ts * 3.1);\n"
            + "    col3 += (g2 - 0.5) * 0.4 * e;\n"
            + "    col3 += vec3(0.5) * step(0.985, h21(vec2(band, ts + 55.0))) * e;\n"
            + "    col3 *= 1.0 + 0.3 * e * (h11(ts) - 0.5);\n"
            + "    c = vec4(col3, 1.0);\n"
            + "  }\n"
            + "  gl_FragColor = vec4(c.rgb, 1.0);\n"
            + "}\n";

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

    private static int program(String vs, String fs) throws IOException {
        int pr = GLES20.glCreateProgram();
        GLES20.glAttachShader(pr, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(pr, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(pr);
        int[] lk = new int[1];
        GLES20.glGetProgramiv(pr, GLES20.GL_LINK_STATUS, lk, 0);
        if (lk[0] == 0) {
            throw new IOException("programma grafico non valido");
        }
        return pr;
    }

    private static int tex2d(int w, int h) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static int rotationOf(Context ctx, Uri uri) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(ctx, uri);
            String r = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return r == null ? 0 : ((Integer.parseInt(r) % 360) + 360) % 360;
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean hasAudio(Context ctx, Uri uri) {
        MediaExtractor t = new MediaExtractor();
        try {
            t.setDataSource(ctx, uri, null);
            return VideoProcessor.findTrack(t, "audio/") >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            t.release();
        }
    }

    // ------------------------------------------------------------------ montaggio

    /**
     * @param w,h  dimensioni di uscita (pari), video dritto (nessuna rotazione nei metadati)
     * @param bitrate bit al secondo del video montato
     */
    static void compose(Context ctx, List<Seg> segs, File out, File cacheDir, int w, int h,
                        int bitrate, VideoProcessor.Progress p) throws IOException {
        if (segs.isEmpty()) {
            throw new IOException("nessun tratto da montare");
        }
        long totalUs = 0;
        for (int i = 0; i < segs.size(); i++) {
            totalUs += lengthUs(segs.get(i), i == 0);
        }

        // ---------------- audio (prima, cosi' si conosce il formato della traccia)
        boolean anyAudio = false;
        boolean[] segAudio = new boolean[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            segAudio[i] = hasAudio(ctx, segs.get(i).uri);
            anyAudio |= segAudio[i];
        }
        File pcm = new File(cacheDir, "compose.pcm");
        File m4a = new File(cacheDir, "compose.m4a");
        MediaExtractor ae = null;
        MediaFormat af = null;
        MediaCodec enc = null, dec = null;
        MediaMuxer mx = null;
        MediaExtractor ex = null;
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ectx = EGL14.EGL_NO_CONTEXT;
        EGLSurface esurf = EGL14.EGL_NO_SURFACE;
        SurfaceTexture st = null;
        Surface decSurface = null, encSurface = null;
        try {
            if (anyAudio) {
                p.onProgress("Preparazione dell'audio...", 1);
                VideoProcessor.PcmWriter pw = new VideoProcessor.PcmWriter(pcm);
                try {
                    long target = 0;
                    for (int i = 0; i < segs.size(); i++) {
                        Seg s = segs.get(i);
                        if (i > 0 && s.trans > 0) {
                            target += s.transUs * VideoProcessor.RATE / 1000000L;
                            pw.silence(target - pw.frames);
                        }
                        target += (s.endUs - s.startUs) * VideoProcessor.RATE / 1000000L;
                        if (segAudio[i]) {
                            try {
                                VideoProcessor.decodeRange(ctx, s.uri, s.startUs, s.endUs, pw);
                            } catch (IOException ignored) {
                            }
                        }
                        pw.silence(target - pw.frames);
                        p.onProgress("Preparazione dell'audio...", 1 + 9 * (i + 1) / segs.size());
                    }
                } finally {
                    pw.close();
                }
                VideoProcessor.encodeMix(null, pcm, 1f, m4a, new VideoProcessor.Progress() {
                    @Override
                    public void onProgress(String stage, int pct) {
                    }
                });
                ae = new MediaExtractor();
                ae.setDataSource(m4a.getAbsolutePath());
                int at = VideoProcessor.findTrack(ae, "audio/");
                if (at >= 0) {
                    ae.selectTrack(at);
                    af = ae.getTrackFormat(at);
                }
            }

            // ---------------- codificatore
            long[] tries = {bitrate, Math.min(bitrate, 20_000_000L), Math.min(bitrate, 10_000_000L), 6_000_000L};
            Exception lastErr = null;
            for (int t = 0; t < tries.length && enc == null; t++) {
                MediaCodec e2 = null;
                try {
                    MediaFormat ef = MediaFormat.createVideoFormat("video/avc", w, h);
                    ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    e2 = MediaCodec.createEncoderByType("video/avc");
                    int br = (int) tries[t];
                    try {
                        br = e2.getCodecInfo().getCapabilitiesForType("video/avc").getVideoCapabilities()
                                .getBitrateRange().clamp(br);
                    } catch (Exception ignored) {
                    }
                    ef.setInteger(MediaFormat.KEY_BIT_RATE, br);
                    ef.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                    ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                    e2.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    Surface s2 = e2.createInputSurface();
                    e2.start();
                    enc = e2;
                    encSurface = s2;
                } catch (Exception ex2) {
                    lastErr = ex2;
                    if (e2 != null) {
                        try {
                            e2.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (enc == null) {
                throw new IOException("codificatore video non avviabile: "
                        + (lastErr == null ? "" : lastErr.getMessage()));
            }

            // ---------------- EGL
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

            int progV = program(VS_V, FS_V);
            int vPos = GLES20.glGetAttribLocation(progV, "aPos"), vD = GLES20.glGetAttribLocation(progV, "aD");
            int vST = GLES20.glGetUniformLocation(progV, "uST"), vA = GLES20.glGetUniformLocation(progV, "uA"),
                    vB = GLES20.glGetUniformLocation(progV, "uB");
            int progX = program(VS_X, FS_X);
            int xPos = GLES20.glGetAttribLocation(progX, "aPos"), xTex = GLES20.glGetAttribLocation(progX, "aTex");
            int xA = GLES20.glGetUniformLocation(progX, "sA"), xB = GLES20.glGetUniformLocation(progX, "sB"),
                    xType = GLES20.glGetUniformLocation(progX, "uType"), xP = GLES20.glGetUniformLocation(progX, "uP");
            int[] xU = {GLES20.glGetUniformLocation(progX, "uQ"), GLES20.glGetUniformLocation(progX, "uRes")};

            int[] oes = new int[1];
            GLES20.glGenTextures(1, oes, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            int texCur = tex2d(w, h), texPrev = tex2d(w, h);
            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, texCur, 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IOException("buffer grafico intermedio non disponibile");
            }
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texPrev);
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            FloatBuffer fbSeg = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] full = {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1};
            FloatBuffer fbFull = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fbFull.put(full);
            fbFull.position(0);

            final Object lock = new Object();
            final boolean[] avail = {false};
            st = new SurfaceTexture(oes[0]);
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

            mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo einfo = new MediaCodec.BufferInfo();
            int[] muxTracks = {-1, -1};
            boolean[] started = {false};
            float[] stm = new float[16];
            long outOffset = 0, lastOut = -1;
            int lastPct = -1;

            for (int i = 0; i < segs.size(); i++) {
                Seg sg = segs.get(i);
                int rot = rotationOf(ctx, sg.uri);
                ex = new MediaExtractor();
                ex.setDataSource(ctx, sg.uri, null);
                int vt = VideoProcessor.findTrack(ex, "video/");
                if (vt < 0) {
                    ex.release();
                    ex = null;
                    throw new IOException("uno dei file non contiene video");
                }
                ex.selectTrack(vt);
                MediaFormat vf = ex.getTrackFormat(vt);
                vf.setInteger("rotation-degrees", 0);
                int sw = vf.getInteger(MediaFormat.KEY_WIDTH), sh = vf.getInteger(MediaFormat.KEY_HEIGHT);
                boolean swap = rot == 90 || rot == 270;
                float dw = swap ? sh : sw, dh = swap ? sw : sh;
                float sc = Math.min(w / dw, h / dh);
                float fx = dw * sc / w, fy = dh * sc / h;
                float[] vq = {-fx, -fy, 0, 1, fx, -fy, 1, 1, -fx, fy, 0, 0, fx, fy, 1, 0};
                fbSeg.position(0);
                fbSeg.put(vq);
                fbSeg.position(0);
                float[] ua;
                float[] ub;
                if (rot == 90) {
                    ua = new float[]{0, 1, -1, 0};
                    ub = new float[]{0, 1};
                } else if (rot == 180) {
                    ua = new float[]{-1, 0, 0, -1};
                    ub = new float[]{1, 1};
                } else if (rot == 270) {
                    ua = new float[]{0, -1, 1, 0};
                    ub = new float[]{1, 0};
                } else {
                    ua = new float[]{1, 0, 0, 1};
                    ub = new float[]{0, 0};
                }
                ex.seekTo(sg.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                dec = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME));
                dec.configure(vf, decSurface, null, 0);
                dec.start();
                synchronized (lock) {
                    avail[0] = false;
                }

                boolean doTrans = i > 0 && sg.trans > 0 && sg.transUs > 0;
                boolean first = true;
                boolean inDone = false, done = false;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (!done) {
                    if (!inDone) {
                        int ii = dec.dequeueInputBuffer(TIMEOUT);
                        if (ii >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(ii);
                            int size = ex.readSampleData(ib, 0);
                            long stt = ex.getSampleTime();
                            if (size < 0 || stt >= sg.endUs + 500000) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inDone = true;
                            } else {
                                dec.queueInputBuffer(ii, 0, size, stt, 0);
                                ex.advance();
                            }
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(info, TIMEOUT);
                    if (oi < 0) {
                        continue;
                    }
                    long pts = info.presentationTimeUs;
                    boolean real = info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    boolean use = real && pts >= sg.startUs && pts < sg.endUs;
                    if (real && pts >= sg.endUs) {
                        done = true;
                    }
                    dec.releaseOutputBuffer(oi, use);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true;
                    }
                    if (!use) {
                        continue;
                    }
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

                    // fotogramma -> immagine intermedia (adattata, con bande nere)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
                    GLES20.glViewport(0, 0, w, h);
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glDisable(GLES20.GL_BLEND);
                    GLES20.glUseProgram(progV);
                    GLES20.glEnableVertexAttribArray(vPos);
                    GLES20.glEnableVertexAttribArray(vD);
                    fbSeg.position(0);
                    GLES20.glVertexAttribPointer(vPos, 2, GLES20.GL_FLOAT, false, 16, fbSeg);
                    fbSeg.position(2);
                    GLES20.glVertexAttribPointer(vD, 2, GLES20.GL_FLOAT, false, 16, fbSeg);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes[0]);
                    GLES20.glUniformMatrix4fv(vST, 1, false, stm, 0);
                    GLES20.glUniform4f(vA, ua[0], ua[1], ua[2], ua[3]);
                    GLES20.glUniform2f(vB, ub[0], ub[1]);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

                    // transizione (una sola volta, sul primo fotogramma del tratto)
                    if (first && doTrans) {
                        int n = (int) Math.max(1, sg.transUs / FRAME_US);
                        for (int k = 0; k < n; k++) {
                            float prog = (k + 1f) / (n + 1f);
                            long tp = outOffset + (long) k * sg.transUs / n;
                            if (tp <= lastOut) {
                                tp = lastOut + 1000;
                            }
                            drawX(progX, xPos, xTex, xA, xB, xType, xP, xU, sg.tp, fbFull, texPrev, texCur,
                                    sg.trans, prog, w, h);
                            EGLExt.eglPresentationTimeANDROID(dpy, esurf, tp * 1000L);
                            EGL14.eglSwapBuffers(dpy, esurf);
                            lastOut = tp;
                            VideoStabilizer.drain(enc, mx, einfo, af, muxTracks, started, false);
                        }
                        outOffset += sg.transUs;
                    }
                    first = false;

                    long tp = outOffset + Math.max(0, pts - sg.startUs);
                    if (tp <= lastOut) {
                        tp = lastOut + 1000;
                    }
                    drawX(progX, xPos, xTex, xA, xB, xType, xP, xU, sg.tp, fbFull, texPrev, texCur, 0, 0f, w, h);
                    EGLExt.eglPresentationTimeANDROID(dpy, esurf, tp * 1000L);
                    EGL14.eglSwapBuffers(dpy, esurf);
                    lastOut = tp;
                    VideoStabilizer.drain(enc, mx, einfo, af, muxTracks, started, false);
                    int pct = (int) (12 + 83 * Math.min(1.0, (double) tp / Math.max(1, totalUs)));
                    if (pct != lastPct) {
                        lastPct = pct;
                        p.onProgress("Montaggio delle clip...", pct);
                    }
                }
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                dec.release();
                dec = null;
                ex.release();
                ex = null;
                if (first && doTrans) {
                    // nessun fotogramma decodificato: la transizione non e' stata disegnata
                    outOffset += sg.transUs;
                }
                outOffset += sg.endUs - sg.startUs;
                // ultimo fotogramma di questo tratto -> servira' alla transizione successiva
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texPrev);
                GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }
            enc.signalEndOfInputStream();
            VideoStabilizer.drain(enc, mx, einfo, af, muxTracks, started, true);

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
            if (!started[0]) {
                throw new IOException("nessun fotogramma prodotto");
            }
            mx.stop();
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
            if (ex != null) {
                ex.release();
            }
            if (ae != null) {
                ae.release();
            }
            //noinspection ResultOfMethodCallIgnored
            pcm.delete();
            //noinspection ResultOfMethodCallIgnored
            m4a.delete();
        }
    }

    private static void drawX(int prog, int aPos, int aTex, int uA, int uB, int uType, int uP, int[] uExtra,
                              float[] tp, FloatBuffer fb, int texA, int texB, int type, float prog01, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(prog);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aTex);
        fb.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, fb);
        fb.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, fb);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
        GLES20.glUniform1i(uA, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
        GLES20.glUniform1i(uB, 1);
        GLES20.glUniform1f(uType, type);
        GLES20.glUniform1f(uP, prog01);
        GLES20.glUniform4f(uExtra[0], tp[0], tp[1], tp[2], tp[3]);
        GLES20.glUniform2f(uExtra[1], w, h);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }
}
