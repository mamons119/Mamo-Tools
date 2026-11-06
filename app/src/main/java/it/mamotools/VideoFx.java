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
            "Sfocatura", "Pixel", "Vivido", "Appare dal nero", "Sparisce al nero", "Zoom avanti", "Glitch RGB", "Schermo CRT"};
    static final String[] EFFECT_ICONS = {"◐", "🟤", "◑", "📽", "◉", "🌫", "▦", "🌈", "⬆", "⬇", "🔍", "📺", "🖥"};
    /** Se l'effetto ha un'intensita' regolabile. */
    static final boolean[] EFFECT_HAS_K = {true, true, true, true, true, true, true, true, false, false, true, true, true};

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
            U_FADE = 8, U_SPLIT = 9, U_ZOOM = 10, U_CRT = 11, U_WARP = 12, U_COUNT = 13;

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
                    break;
                default:
                    break;
            }
        }
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
        return u[U_PIX] > 0 || u[U_BLUR] > 0 || u[U_SPLIT] > 0 || (u[U_CRT] > 0 && u[U_WARP] > 0);
    }

    static boolean isNeutral(float[] u) {
        return u[U_GRAY] == 0 && u[U_SEPIA] == 0 && u[U_INV] == 0 && u[U_VIG] == 0 && u[U_BLUR] == 0
                && u[U_PIX] == 0 && u[U_SAT] == 1 && u[U_CON] == 1 && u[U_FADE] == 1 && u[U_SPLIT] == 0
                && u[U_ZOOM] == 1 && u[U_CRT] == 0;
    }
}
