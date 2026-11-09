package it.mamotools;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.List;

/** Effetti e testi applicabili a un tratto del video (usati in anteprima e nell'esportazione). */
final class VideoFx {

    private VideoFx() {
    }

    static final String[] EFFECT_NAMES = {"Bianco e nero", "Seppia", "Negativo", "Vintage", "Vignettatura",
            "Sfocatura", "Pixel", "Vivido", "Appare dal nero", "Sparisce al nero", "Zoom avanti", "Glitch RGB", "Schermo CRT", "VHS"};
    static final String[] EFFECT_ICONS = {"◐", "🟤", "◑", "📽", "◉", "🌫", "▦", "🌈", "⬆", "⬇", "🔍", "📺", "🖥", "📼"};
    /** Se l'effetto ha un'intensita' regolabile. */
    static final boolean[] EFFECT_HAS_K = {true, true, true, true, true, true, true, true, false, false, true, true, true, true};

    static final int EFFECT_CRT = 12, EFFECT_VHS = 13;
    static final String[] VHS_DEFAULT = {"PLAY ▶", "VHS", "01:35\nSEP. 03 1985", ""};

    static final String[] FONT_NAMES = {"Normale", "Condensato", "Sottile", "Nero", "Serif", "Macchina",
            "Corsivo", "Informale"};
    static final String[] FONT_FAMILY = {"sans-serif", "sans-serif-condensed", "sans-serif-light",
            "sans-serif-black", "serif", "monospace", "cursive", "casual"};

    static final int[] SWATCHES = {0xFFFFFFFF, 0xFF000000, 0xFFFFD54F, 0xFFFF6B6B, 0xFFFF8A65, 0xFF66BB6A,
            0xFF4DD0E1, 0xFF42A5F5, 0xFFBA68C8, 0xFFF48FB1};

    static final class Item {
        boolean isText;
        long startUs, endUs;
        // effetto
        int effect = 0;
        float intensity = 0.8f;
        // testo
        String text = "Testo";
        int font = 0;
        boolean bold = true, italic = false;
        float sizeFrac = 0.09f;   // altezza del carattere rispetto all'altezza del video
        int color = 0xFFFFFFFF;
        boolean outline = true, shadow = false, box = false;
        float x = 0.5f, y = 0.8f; // centro, in frazione del fotogramma visualizzato
        boolean crtWarp = false;  // effetto CRT: distorsioni (curvatura, tremolio, disturbo)
        int crtBars = 0;          // effetto CRT: quante fasce scure scorrono (0 = nessuna)
        float crtBarSpeed = 0.35f; // effetto CRT: velocita' delle fasce (giri dello schermo al secondo)
        // effetto VHS
        boolean vhsDist = true;   // distorsione dell'immagine
        boolean vhsText = true;   // scritte negli angoli
        String[] vhsTxt = VHS_DEFAULT.clone(); // alto-sinistra, alto-destra, basso-sinistra, basso-destra
        float vhsSize = 0.07f;
        int vhsColor = 0xFFFFFFFF;
        // testo generato da un effetto VHS (non salvato dall'utente)
        boolean vhs = false, vhsJit = false;
        int align = -1;           // -1 = a sinistra, 1 = a destra
        float ay = 0f;            // 0 = in alto, 1 = in basso
        int salt = 0;

        Item copy() {
            Item i = new Item();
            i.isText = isText;
            i.startUs = startUs;
            i.endUs = endUs;
            i.effect = effect;
            i.intensity = intensity;
            i.text = text;
            i.font = font;
            i.bold = bold;
            i.italic = italic;
            i.sizeFrac = sizeFrac;
            i.color = color;
            i.outline = outline;
            i.shadow = shadow;
            i.box = box;
            i.x = x;
            i.y = y;
            i.crtWarp = crtWarp;
            i.crtBars = crtBars;
            i.crtBarSpeed = crtBarSpeed;
            i.vhsDist = vhsDist;
            i.vhsText = vhsText;
            i.vhsTxt = vhsTxt.clone();
            i.vhsSize = vhsSize;
            i.vhsColor = vhsColor;
            i.vhs = vhs;
            i.vhsJit = vhsJit;
            i.align = align;
            i.ay = ay;
            i.salt = salt;
            return i;
        }

        org.json.JSONObject toJson() throws org.json.JSONException {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("isText", isText);
            o.put("startUs", startUs);
            o.put("endUs", endUs);
            o.put("effect", effect);
            o.put("intensity", intensity);
            o.put("text", text);
            o.put("font", font);
            o.put("bold", bold);
            o.put("italic", italic);
            o.put("sizeFrac", sizeFrac);
            o.put("color", color);
            o.put("outline", outline);
            o.put("shadow", shadow);
            o.put("box", box);
            o.put("x", x);
            o.put("y", y);
            o.put("crtWarp", crtWarp);
            o.put("crtBars", crtBars);
            o.put("crtBarSpeed", crtBarSpeed);
            o.put("vhsDist", vhsDist);
            o.put("vhsText", vhsText);
            org.json.JSONArray a = new org.json.JSONArray();
            for (String t : vhsTxt) {
                a.put(t);
            }
            o.put("vhsTxt", a);
            o.put("vhsSize", vhsSize);
            o.put("vhsColor", vhsColor);
            return o;
        }

        static Item fromJson(org.json.JSONObject o) {
            Item i = new Item();
            i.isText = o.optBoolean("isText", i.isText);
            i.startUs = o.optLong("startUs", 0);
            i.endUs = o.optLong("endUs", 0);
            i.effect = o.optInt("effect", i.effect);
            i.intensity = (float) o.optDouble("intensity", i.intensity);
            i.text = o.optString("text", i.text);
            i.font = o.optInt("font", i.font);
            i.bold = o.optBoolean("bold", i.bold);
            i.italic = o.optBoolean("italic", i.italic);
            i.sizeFrac = (float) o.optDouble("sizeFrac", i.sizeFrac);
            i.color = o.optInt("color", i.color);
            i.outline = o.optBoolean("outline", i.outline);
            i.shadow = o.optBoolean("shadow", i.shadow);
            i.box = o.optBoolean("box", i.box);
            i.x = (float) o.optDouble("x", i.x);
            i.y = (float) o.optDouble("y", i.y);
            i.crtWarp = o.optBoolean("crtWarp", i.crtWarp);
            i.crtBars = o.optInt("crtBars", i.crtBars);
            i.crtBarSpeed = (float) o.optDouble("crtBarSpeed", i.crtBarSpeed);
            i.vhsDist = o.optBoolean("vhsDist", i.vhsDist);
            i.vhsText = o.optBoolean("vhsText", i.vhsText);
            org.json.JSONArray a = o.optJSONArray("vhsTxt");
            if (a != null) {
                for (int k = 0; k < 4 && k < a.length(); k++) {
                    i.vhsTxt[k] = a.optString(k, "");
                }
            }
            i.vhsSize = (float) o.optDouble("vhsSize", i.vhsSize);
            i.vhsColor = o.optInt("vhsColor", i.vhsColor);
            return i;
        }

        boolean activeAt(long us) {
            return us >= startUs && us < endUs;
        }

        String label() {
            String t = isText ? "Testo “" + (text.length() > 18 ? text.substring(0, 18) + "…" : text.replace('\n', ' ')) + "”"
                    : EFFECT_NAMES[effect];
            return t;
        }
    }

    static boolean hasAny(List<Item> l) {
        return l != null && !l.isEmpty();
    }

    // ------------------------------------------------------------------ parametri degli effetti

    static final int U_GRAY = 0, U_SEPIA = 1, U_INV = 2, U_VIG = 3, U_BLUR = 4, U_PIX = 5, U_SAT = 6, U_CON = 7,
            U_FADE = 8, U_SPLIT = 9, U_ZOOM = 10, U_CRT = 11, U_WARP = 12, U_BARS = 13, U_BSPD = 14, U_VHS = 15, U_COUNT = 16;

    /** Calcola i parametri dello shader per l'istante t combinando gli effetti attivi. */
    static void uniforms(List<Item> items, long tUs, float[] u) {
        u[U_GRAY] = 0;
        u[U_SEPIA] = 0;
        u[U_INV] = 0;
        u[U_VIG] = 0;
        u[U_BLUR] = 0;
        u[U_PIX] = 0;
        u[U_SAT] = 1;
        u[U_CON] = 1;
        u[U_FADE] = 1;
        u[U_SPLIT] = 0;
        u[U_ZOOM] = 1;
        u[U_CRT] = 0;
        u[U_WARP] = 0;
        u[U_BARS] = 0;
        u[U_BSPD] = 0;
        u[U_VHS] = 0;
        if (items == null) {
            return;
        }
        for (Item it : items) {
            if (it.isText || !it.activeAt(tUs)) {
                continue;
            }
            float k = it.intensity;
            float p = (tUs - it.startUs) / (float) Math.max(1, it.endUs - it.startUs);
            switch (it.effect) {
                case 0:
                    u[U_GRAY] = Math.max(u[U_GRAY], k);
                    break;
                case 1:
                    u[U_SEPIA] = Math.max(u[U_SEPIA], k);
                    break;
                case 2:
                    u[U_INV] = Math.max(u[U_INV], k);
                    break;
                case 3:
                    u[U_SEPIA] = Math.max(u[U_SEPIA], 0.55f * k);
                    u[U_VIG] = Math.max(u[U_VIG], 0.75f * k);
                    u[U_CON] *= 1 + 0.12f * k;
                    break;
                case 4:
                    u[U_VIG] = Math.max(u[U_VIG], k);
                    break;
                case 5:
                    u[U_BLUR] = Math.max(u[U_BLUR], k);
                    break;
                case 6:
                    u[U_PIX] = Math.max(u[U_PIX], k);
                    break;
                case 7:
                    u[U_SAT] *= 1 + k;
                    u[U_CON] *= 1 + 0.2f * k;
                    break;
                case 8:
                    u[U_FADE] *= p * p * (3 - 2 * p);
                    break;
                case 9:
                    u[U_FADE] *= 1 - p * p * (3 - 2 * p);
                    break;
                case 10:
                    u[U_ZOOM] *= 1 + 0.5f * k * p;
                    break;
                case 11:
                    u[U_SPLIT] = Math.max(u[U_SPLIT], k);
                    break;
                case 12:
                    u[U_CRT] = Math.max(u[U_CRT], k);
                    if (it.crtWarp) {
                        u[U_WARP] = 1;
                    }
                    u[U_BARS] = Math.max(u[U_BARS], it.crtBars);
                    u[U_BSPD] = Math.max(u[U_BSPD], it.crtBarSpeed);
                    break;
                case 13:
                    if (it.vhsDist) {
                        u[U_VHS] = Math.max(u[U_VHS], k);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ scritte VHS

    /** Aggiunge alla lista i testi generati dagli effetti VHS (uno per ogni scritta non vuota). */
    static List<Item> expandVhs(List<Item> in) {
        if (in == null) {
            return null;
        }
        boolean any = false;
        for (Item i : in) {
            if (!i.isText && i.effect == EFFECT_VHS && i.vhsText) {
                any = true;
            }
        }
        if (!any) {
            return in;
        }
        List<Item> out = new java.util.ArrayList<>(in);
        for (Item i : in) {
            if (i.isText || i.effect != EFFECT_VHS || !i.vhsText) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                String s = i.vhsTxt[k];
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                Item t = new Item();
                t.isText = true;
                t.vhs = true;
                t.vhsJit = i.vhsDist;
                t.startUs = i.startUs;
                t.endUs = i.endUs;
                t.text = s;
                t.sizeFrac = i.vhsSize;
                t.color = i.vhsColor;
                t.align = k % 2 == 0 ? -1 : 1;
                t.ay = k < 2 ? 0f : 1f;
                t.salt = k + 1;
                out.add(t);
            }
        }
        return out;
    }

    /** Centro del testo (frazione del fotogramma visualizzato); bw/bh = dimensioni della bitmap in pixel. */
    static void center(Item o, float bw, float bh, float dispW, float dispH, float[] out) {
        if (!o.vhs) {
            out[0] = o.x;
            out[1] = o.y;
            return;
        }
        float mx = 0.06f, my = 0.07f;
        float hw = bw / dispW / 2f, hh = bh / dispH / 2f;
        out[0] = o.align < 0 ? mx + hw : 1 - mx - hw;
        out[1] = o.ay < 0.5f ? my + hh : 1 - my - hh;
    }

    static float hash1(long n) {
        long x = n * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 32;
        return (x & 0xFFFFFF) / 16777216f;
    }

    /** Spostamento orizzontale (frazione della larghezza) delle scritte VHS: tremolio e ogni tanto uno strappo. */
    static float vhsJitter(long us, int salt) {
        long step = us / 40000L;
        float a = (hash1(step * 31 + salt) - 0.5f) * 0.004f;
        float b = 0f;
        if (us % 1300000L < 120000L && hash1(us / 1300000L * 17 + salt) > 0.45f) {
            b = (hash1(step * 13 + salt * 5) - 0.5f) * 0.05f;
        }
        return a + b;
    }

    /** Scritta a "pixel grossi" come i vecchi videoregistratori: carattere a bassa risoluzione ingrandito. */
    private static Bitmap renderVhs(Item o, int dispW, int dispH, int rotationDeg) {
        int cell = Math.max(1, Math.round(dispH / 240f));
        float fs = Math.max(6f, o.sizeFrac * dispH / cell);
        Paint p = new Paint();
        p.setAntiAlias(false);
        p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        p.setTextSize(fs);
        String[] lines = (o.text == null ? " " : o.text).split("\n", -1);
        Paint.FontMetrics fm = p.getFontMetrics();
        float lh = fm.descent - fm.ascent;
        float wmax = 1;
        float[] lw = new float[lines.length];
        for (int i = 0; i < lines.length; i++) {
            lw[i] = p.measureText(lines[i]);
            wmax = Math.max(wmax, lw[i]);
        }
        int pad = 3;
        int sw = (int) Math.ceil(wmax) + 2 * pad, sh = (int) Math.ceil(lh * lines.length) + 2 * pad;
        Bitmap base = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(base);
        for (int pass = 0; pass < 3; pass++) {
            if (pass == 1 && !o.vhsJit) {
                continue;
            }
            p.setColor(pass == 0 ? 0xB0000000 : pass == 1 ? 0x80FF2060 : o.color);
            float dx = pass == 0 ? 1 : pass == 1 ? 1 : 0, dy = pass == 0 ? 1 : 0;
            for (int i = 0; i < lines.length; i++) {
                float x = o.align < 0 ? pad : sw - pad - lw[i];
                c.drawText(lines[i], x + dx, pad - fm.ascent + i * lh + dy, p);
            }
        }
        Bitmap src = base;
        if (o.vhsJit) {
            Bitmap w = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas wc = new Canvas(w);
            int seed = (o.text == null ? 0 : o.text.hashCode()) + o.salt * 101;
            for (int y = 0; y < sh; y++) {
                int d = 0;
                if (hash1(y * 91L + seed) > 0.88f) {
                    d = hash1(y * 53L + seed) > 0.5f ? 1 : -1;
                }
                wc.drawBitmap(base, new android.graphics.Rect(0, y, sw, y + 1),
                        new android.graphics.Rect(d, y, sw + d, y + 1), null);
            }
            base.recycle();
            src = w;
        }
        Bitmap bm = Bitmap.createScaledBitmap(src, sw * cell, sh * cell, false);
        if (bm != src) {
            src.recycle();
        }
        int rot = ((360 - rotationDeg) % 360 + 360) % 360;
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            bm.recycle();
            return r;
        }
        return bm;
    }

    // ------------------------------------------------------------------ testo

    static Typeface typeface(Item o) {
        int style = (o.bold ? Typeface.BOLD : 0) | (o.italic ? Typeface.ITALIC : 0);
        try {
            return Typeface.create(Typeface.create(FONT_FAMILY[o.font], Typeface.NORMAL), style);
        } catch (Exception e) {
            return Typeface.defaultFromStyle(style);
        }
    }

    /**
     * Disegna il testo in una bitmap trasparente. dispW/dispH = dimensioni del video visualizzato;
     * rotationDeg = rotazione memorizzata nel file: la bitmap viene ruotata al contrario perche' il
     * lettore la ripristini dritta.
     */
    static Bitmap render(Item o, int dispW, int dispH, int rotationDeg) {
        if (o.vhs) {
            return renderVhs(o, dispW, dispH, rotationDeg);
        }
        float px = Math.max(8f, o.sizeFrac * dispH);
        TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        fill.setTypeface(typeface(o));
        fill.setTextSize(px);
        fill.setColor(o.color);
        if (o.shadow) {
            fill.setShadowLayer(px * 0.09f, px * 0.06f, px * 0.06f, 0xCC000000);
        }
        TextPaint stroke = new TextPaint(fill);
        stroke.clearShadowLayer();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(px * 0.14f);
        stroke.setColor(luma(o.color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF);

        String txt = o.text == null || o.text.isEmpty() ? " " : o.text;
        int maxW = Math.max(40, (int) (dispW * 0.92f));
        StaticLayout probe = StaticLayout.Builder.obtain(txt, 0, txt.length(), fill, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        float widest = 1;
        for (int i = 0; i < probe.getLineCount(); i++) {
            widest = Math.max(widest, probe.getLineWidth(i));
        }
        int lw = (int) Math.ceil(widest) + 2;
        StaticLayout fl = StaticLayout.Builder.obtain(txt, 0, txt.length(), fill, lw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        StaticLayout sl = StaticLayout.Builder.obtain(txt, 0, txt.length(), stroke, lw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        int pad = (int) Math.ceil(o.box ? px * 0.4f : px * 0.2f);
        int bw = lw + 2 * pad, bh = fl.getHeight() + 2 * pad;
        Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        if (o.box) {
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(luma(o.color) > 0.5f ? 0xB3000000 : 0xB3FFFFFF);
            c.drawRoundRect(new RectF(0, 0, bw, bh), px * 0.3f, px * 0.3f, bp);
        }
        c.translate(pad, pad);
        if (o.outline && !o.box) {
            sl.draw(c);
        }
        fl.draw(c);
        int rot = ((360 - rotationDeg) % 360 + 360) % 360;
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bw, bh, m, true);
            bm.recycle();
            return r;
        }
        return bm;
    }

    static float luma(int c) {
        return (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
    }

    // ------------------------------------------------------------------ anteprima (ColorMatrix)

    private static float[] lerpM(float[] m, float k) {
        float[] id = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0};
        float[] r = new float[20];
        for (int i = 0; i < 20; i++) {
            r[i] = id[i] * (1 - k) + m[i] * k;
        }
        return r;
    }

    /** Stessa catena di colore dello shader dell'esportazione, come ColorMatrix per l'anteprima. */
    static android.graphics.ColorMatrix colorMatrix(float[] u) {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        if (u[U_GRAY] > 0) {
            cm.postConcat(new android.graphics.ColorMatrix(lerpM(new float[]{
                    .299f, .587f, .114f, 0, 0, .299f, .587f, .114f, 0, 0, .299f, .587f, .114f, 0, 0, 0, 0, 0, 1, 0},
                    u[U_GRAY])));
        }
        if (u[U_SEPIA] > 0) {
            cm.postConcat(new android.graphics.ColorMatrix(lerpM(new float[]{
                    .393f, .769f, .189f, 0, 0, .349f, .686f, .168f, 0, 0, .272f, .534f, .131f, 0, 0, 0, 0, 0, 1, 0},
                    u[U_SEPIA])));
        }
        if (u[U_INV] > 0) {
            cm.postConcat(new android.graphics.ColorMatrix(lerpM(new float[]{
                    -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0}, u[U_INV])));
        }
        if (u[U_SAT] != 1) {
            android.graphics.ColorMatrix sm = new android.graphics.ColorMatrix();
            sm.setSaturation(u[U_SAT]);
            cm.postConcat(sm);
        }
        float c = u[U_CON];
        float f = u[U_FADE];
        float off = 127.5f * (1 - c);
        cm.postConcat(new android.graphics.ColorMatrix(new float[]{
                c * f, 0, 0, 0, off * f, 0, c * f, 0, 0, off * f, 0, 0, c * f, 0, off * f, 0, 0, 0, 1, 0}));
        return cm;
    }

    static boolean needsGrab(float[] u) {
        return u[U_PIX] > 0 || u[U_BLUR] > 0 || u[U_SPLIT] > 0 || (u[U_CRT] > 0 && u[U_WARP] > 0) || u[U_VHS] > 0;
    }

    static boolean isNeutral(float[] u) {
        return u[U_GRAY] == 0 && u[U_SEPIA] == 0 && u[U_INV] == 0 && u[U_VIG] == 0 && u[U_BLUR] == 0
                && u[U_PIX] == 0 && u[U_SAT] == 1 && u[U_CON] == 1 && u[U_FADE] == 1 && u[U_SPLIT] == 0
                && u[U_ZOOM] == 1 && u[U_CRT] == 0 && u[U_VHS] == 0;
    }
}
