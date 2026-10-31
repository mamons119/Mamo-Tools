package it.mamotools;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Disegna un DocModel in un PDF usando solo le API di Android (PdfDocument + StaticLayout).
 * Rende testo formattato, elenchi, tabelle semplici e immagini; non rende intestazioni,
 * pie' di pagina, caselle di testo e forme.
 */
final class DocxToPdf {

    private static final float CELL_PAD = 4f;
    private static final int SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    private final DocModel doc;
    private final PdfDocument pdf = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNo = 0;
    private float y;
    private final float contentW;
    private final float bottom;
    private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private DocxToPdf(DocModel doc) {
        this.doc = doc;
        this.contentW = doc.pageW - doc.marginL - doc.marginR;
        this.bottom = doc.pageH - doc.marginB;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(0.6f);
        linePaint.setColor(Color.BLACK);
    }

    static void render(DocModel doc, OutputStream out) throws IOException {
        DocxToPdf r = new DocxToPdf(doc);
        try {
            r.run();
            r.pdf.writeTo(out);
        } finally {
            r.pdf.close();
        }
    }

    private void run() {
        newPage();
        for (DocModel.Block b : doc.blocks) {
            if (b instanceof DocModel.Para) {
                drawPara((DocModel.Para) b);
            } else if (b instanceof DocModel.Table) {
                drawTable((DocModel.Table) b);
            }
        }
        pdf.finishPage(page);
        page = null;
    }

    private void newPage() {
        if (page != null) {
            pdf.finishPage(page);
        }
        pageNo++;
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                Math.round(doc.pageW), Math.round(doc.pageH), pageNo).create();
        page = pdf.startPage(info);
        canvas = page.getCanvas();
        y = doc.marginT;
    }

    // ------------------------------------------------------------------ paragrafi

    private void drawPara(DocModel.Para p) {
        if (p.pageBreakBefore && y > doc.marginT + 1f) {
            newPage();
        }
        if (y > doc.marginT + 0.5f) {
            y += p.before;
        }

        boolean imageOnly = p.runs.isEmpty() && !p.images.isEmpty()
                && (p.listPrefix == null || p.listPrefix.isEmpty());
        if (!imageOnly) {
            drawText(p);
        }
        for (DocModel.Img img : p.images) {
            drawImageBlock(img, p.align);
        }

        y += p.after;
        if (p.pageBreakAfter) {
            newPage();
        }
    }

    private void drawText(DocModel.Para p) {
        StaticLayout l = buildLayout(p, contentW);
        int lines = l.getLineCount();
        int i = 0;
        while (i < lines) {
            float avail = bottom - y;
            float top = l.getLineTop(i);
            int j = i - 1;
            while (j + 1 < lines && l.getLineBottom(j + 1) - top <= avail) {
                j++;
            }
            if (j < i) {
                if (y > doc.marginT + 0.5f) {
                    newPage();
                    continue;
                }
                j = i;   // una riga piu' alta della pagina: la disegno comunque
            }
            float lineBottom = l.getLineBottom(j);
            canvas.save();
            canvas.translate(doc.marginL, y - top);
            if (!(i == 0 && j == lines - 1)) {
                canvas.clipRect(0f, top, contentW, lineBottom);
            }
            l.draw(canvas);
            canvas.restore();
            y += lineBottom - top;
            i = j + 1;
            if (i < lines) {
                newPage();
            }
        }
    }

    /** Converte il paragrafo in un Layout di testo con spans (font, colore, rientri...). */
    private StaticLayout buildLayout(DocModel.Para p, float width) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        float firstSize = p.runs.isEmpty() ? p.defaultSize : p.runs.get(0).size;

        if (p.listPrefix != null && !p.listPrefix.isEmpty()) {
            int s = sb.length();
            sb.append(p.listPrefix);
            sb.setSpan(new SizeSpan(firstSize), s, sb.length(), SPAN_FLAGS);
        }
        for (DocModel.Run r : p.runs) {
            int s = sb.length();
            sb.append(r.text);
            int e = sb.length();
            if (e == s) {
                continue;
            }
            sb.setSpan(new SizeSpan(r.size), s, e, SPAN_FLAGS);
            sb.setSpan(new TypefaceSpan(familyOf(r.font)), s, e, SPAN_FLAGS);
            if (r.bold && r.italic) {
                sb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), s, e, SPAN_FLAGS);
            } else if (r.bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, e, SPAN_FLAGS);
            } else if (r.italic) {
                sb.setSpan(new StyleSpan(Typeface.ITALIC), s, e, SPAN_FLAGS);
            }
            if (r.color != 0xFF000000) {
                sb.setSpan(new ForegroundColorSpan(r.color), s, e, SPAN_FLAGS);
            }
            if (r.underline) {
                sb.setSpan(new UnderlineSpan(), s, e, SPAN_FLAGS);
            }
            if (r.strike) {
                sb.setSpan(new StrikethroughSpan(), s, e, SPAN_FLAGS);
            }
        }
        if (sb.length() == 0) {
            sb.append(' ');
            sb.setSpan(new SizeSpan(p.defaultSize), 0, 1, SPAN_FLAGS);
        }
        int rest = Math.max(0, Math.round(p.indentLeft));
        int first = Math.max(0, Math.round(p.indentLeft + p.indentFirst));
        if (rest > 0 || first > 0) {
            sb.setSpan(new LeadingMarginSpan.Standard(first, rest), 0, sb.length(), SPAN_FLAGS);
        }

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(p.defaultSize);
        tp.setColor(Color.BLACK);

        Layout.Alignment al = p.align == 1 ? Layout.Alignment.ALIGN_CENTER
                : p.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE
                : Layout.Alignment.ALIGN_NORMAL;
        int w = Math.max(20, Math.round(width));
        StaticLayout.Builder b = StaticLayout.Builder.obtain(sb, 0, sb.length(), tp, w)
                .setAlignment(al)
                .setLineSpacing(0f, Math.max(0.8f, p.lineMult))
                .setIncludePad(false);
        if (p.align == 3 && Build.VERSION.SDK_INT >= 26) {
            b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        }
        return b.build();
    }

    private static String familyOf(String font) {
        String f = font == null ? "" : font.toLowerCase(Locale.ROOT);
        if (f.contains("courier") || f.contains("consolas") || f.contains("mono")) {
            return "monospace";
        }
        if (f.contains("times") || f.contains("georgia") || f.contains("garamond")
                || f.contains("cambria") || f.contains("palatino") || f.contains("book")
                || f.contains("serif") && !f.contains("sans")) {
            return "serif";
        }
        return "sans-serif";
    }

    /** Span che imposta la dimensione del testo in punti (anche frazionaria). */
    private static final class SizeSpan extends MetricAffectingSpan {
        private final float size;

        SizeSpan(float size) {
            this.size = size;
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setTextSize(size);
        }

        @Override
        public void updateMeasureState(TextPaint p) {
            p.setTextSize(size);
        }
    }

    // ------------------------------------------------------------------ immagini

    private float[] fit(DocModel.Img img, float maxW, float maxH) {
        float w = img.w;
        float h = img.h;
        if (w <= 0f || h <= 0f) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(img.data, 0, img.data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return null;
            }
            w = o.outWidth * 0.75f;
            h = o.outHeight * 0.75f;
        }
        float s = Math.min(1f, Math.min(maxW / w, maxH / h));
        return new float[]{w * s, h * s};
    }

    private Bitmap decode(DocModel.Img img, float targetWpt) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(img.data, 0, img.data.length, o);
        if (o.outWidth <= 0) {
            return null;
        }
        int target = Math.max(1, Math.round(targetWpt * 3f));   // circa 216 dpi
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target) {
            sample *= 2;
        }
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(img.data, 0, img.data.length, d);
    }

    private void drawImageBlock(DocModel.Img img, int align) {
        float[] wh = fit(img, contentW, bottom - doc.marginT);
        if (wh == null) {
            return;
        }
        Bitmap b = decode(img, wh[0]);
        if (b == null) {
            return;   // formato non supportato (ad esempio EMF)
        }
        if (y + wh[1] > bottom && y > doc.marginT + 0.5f) {
            newPage();
        }
        float x = doc.marginL + (align == 1 ? (contentW - wh[0]) / 2f
                : align == 2 ? contentW - wh[0] : 0f);
        canvas.drawBitmap(b, null, new RectF(x, y, x + wh[0], y + wh[1]), imgPaint);
        b.recycle();
        y += wh[1] + 4f;
    }

    /** Disegna un'immagine dentro una cella; restituisce l'altezza occupata. */
    private float drawImageIn(DocModel.Img img, float x, float top, float maxW, boolean draw) {
        float[] wh = fit(img, maxW, 600f);
        if (wh == null) {
            return 0f;
        }
        if (draw) {
            Bitmap b = decode(img, wh[0]);
            if (b == null) {
                return 0f;
            }
            canvas.drawBitmap(b, null, new RectF(x, top, x + wh[0], top + wh[1]), imgPaint);
            b.recycle();
        }
        return wh[1] + 2f;
    }

    // ------------------------------------------------------------------ tabelle

    private void drawTable(DocModel.Table t) {
        int ncols = 0;
        for (List<DocModel.Cell> row : t.rows) {
            int c = 0;
            for (DocModel.Cell cell : row) {
                c += Math.max(1, cell.span);
            }
            ncols = Math.max(ncols, c);
        }
        if (ncols == 0) {
            return;
        }
        float[] cw = new float[ncols];
        float sum = 0f;
        if (t.cols != null) {
            for (int k = 0; k < ncols; k++) {
                cw[k] = k < t.cols.length ? t.cols[k] : 0f;
                sum += cw[k];
            }
        }
        boolean allPositive = t.cols != null && sum > 1f;
        if (allPositive) {
            for (int k = 0; k < ncols; k++) {
                if (cw[k] <= 0f) {
                    allPositive = false;
                }
            }
        }
        if (!allPositive) {
            Arrays.fill(cw, contentW / ncols);
            sum = contentW;
        }
        if (sum > contentW) {
            float f = contentW / sum;
            for (int k = 0; k < ncols; k++) {
                cw[k] *= f;
            }
        }

        for (List<DocModel.Cell> row : t.rows) {
            int n = row.size();
            if (n == 0) {
                continue;
            }
            float[] xs = new float[n];
            float[] ws = new float[n];
            List<List<StaticLayout>> layouts = new ArrayList<>();
            float rowH = 0f;
            float x = doc.marginL;
            int col = 0;
            for (int c = 0; c < n; c++) {
                DocModel.Cell cell = row.get(c);
                int span = Math.max(1, cell.span);
                float w = 0f;
                for (int k = col; k < Math.min(ncols, col + span); k++) {
                    w += cw[k];
                }
                xs[c] = x;
                ws[c] = w;
                x += w;
                col += span;

                float inner = Math.max(10f, w - 2f * CELL_PAD);
                float h = 2f * CELL_PAD;
                List<StaticLayout> ll = new ArrayList<>();
                for (DocModel.Para p : cell.paras) {
                    StaticLayout l = buildLayout(p, inner);
                    ll.add(l);
                    h += p.before + l.getHeight() + p.after;
                    for (DocModel.Img img : p.images) {
                        h += drawImageIn(img, 0f, 0f, inner, false);
                    }
                }
                layouts.add(ll);
                rowH = Math.max(rowH, h);
            }

            if (y + rowH > bottom && y > doc.marginT + 0.5f) {
                newPage();
            }
            for (int c = 0; c < n; c++) {
                if (t.borders) {
                    canvas.drawRect(xs[c], y, xs[c] + ws[c], y + rowH, linePaint);
                }
                float inner = Math.max(10f, ws[c] - 2f * CELL_PAD);
                float cy = y + CELL_PAD;
                List<DocModel.Para> paras = row.get(c).paras;
                for (int pi = 0; pi < paras.size(); pi++) {
                    DocModel.Para p = paras.get(pi);
                    StaticLayout l = layouts.get(c).get(pi);
                    cy += p.before;
                    canvas.save();
                    canvas.translate(xs[c] + CELL_PAD, cy);
                    l.draw(canvas);
                    canvas.restore();
                    cy += l.getHeight() + p.after;
                    for (DocModel.Img img : p.images) {
                        cy += drawImageIn(img, xs[c] + CELL_PAD, cy, inner, true);
                    }
                }
            }
            y += rowH;
        }
        y += 6f;
    }
}
