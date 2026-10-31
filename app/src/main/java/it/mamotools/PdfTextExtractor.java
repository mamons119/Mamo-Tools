package it.mamotools;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Estrae il testo da un PDF (libreria PdfBox-Android) e lo organizza in paragrafi con
 * dimensione e grassetto, pronti per essere scritti in un file Word.
 * Non estrae immagini, tabelle e impaginazione.
 */
final class PdfTextExtractor {

    interface Progress {
        void onPage(int page, int total);
    }

    static final class Result {
        final List<DocxWriter.Para> paras = new ArrayList<>();
        float pageW = 595f;
        float pageH = 842f;
        int pages;
        int chars;
    }

    private PdfTextExtractor() {
    }

    static Result extract(Context ctx, File file, Progress progress) throws IOException {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());

        PDDocument doc;
        try {
            doc = PDDocument.load(file);
        } catch (IOException e) {
            if (e.getClass().getSimpleName().contains("InvalidPassword")) {
                throw new IOException("Il PDF e' protetto da password: rimuovila e riprova.");
            }
            throw e;
        }
        try {
            doc.setAllSecurityToBeRemoved(true);
            Result res = new Result();
            res.pages = doc.getNumberOfPages();
            if (res.pages == 0) {
                throw new IOException("Il PDF non contiene pagine.");
            }

            PDPage first = doc.getPage(0);
            float w = first.getMediaBox().getWidth();
            float h = first.getMediaBox().getHeight();
            int rot = first.getRotation();
            if (rot == 90 || rot == 270) {
                float tmp = w;
                w = h;
                h = tmp;
            }
            if (w > 100f && h > 100f) {
                res.pageW = w;
                res.pageH = h;
            }

            Collector st = new Collector(res);
            st.setSortByPosition(true);
            for (int p = 1; p <= res.pages; p++) {
                if (progress != null) {
                    progress.onPage(p, res.pages);
                }
                st.setStartPage(p);
                st.setEndPage(p);
                st.beginPage();
                st.getText(doc);
                st.endPage();
            }

            if (res.chars < 5 * res.pages) {
                throw new IOException("Il PDF non contiene testo selezionabile (probabilmente e' "
                        + "una scansione): serve il riconoscimento del testo (OCR), "
                        + "che questo strumento non fa.");
            }
            return res;
        } finally {
            doc.close();
        }
    }

    /** Riceve il testo parola per parola e ricostruisce i paragrafi. */
    private static final class Collector extends PDFTextStripper {
        /** Primo "token" di una riga che indica un punto elenco: "-", "•", "1.", "a)". */
        private static final Pattern LIST_MARKER = Pattern.compile(
                "[•●▪◦‣⁃\\-–—*]|\\d{1,3}[.)]|[a-zA-Z][.)]");

        private final Result res;
        private final StringBuilder cur = new StringBuilder();
        private float curSize = 11f;
        private boolean curBold;
        private boolean curBreak;

        private boolean lineStart = true;
        private boolean pageBreakPending;
        private int pagesSeen = 0;
        private boolean haveLast;
        private float lastY;
        private float lastSize;
        private boolean lastBold;
        private float typicalGap;

        Collector(Result res) throws IOException {
            super();
            this.res = res;
        }

        void beginPage() {
            flush();
            pageBreakPending = pagesSeen > 0;
            pagesSeen++;
            haveLast = false;
            lineStart = true;
        }

        void endPage() {
            flush();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (lineStart) {
                lineStart = false;
                TextPosition tp = positions == null || positions.isEmpty() ? null : positions.get(0);
                float size = tp == null ? 11f : tp.getFontSizeInPt();
                if (!(size > 0f)) {
                    size = 11f;
                }
                float y = tp == null ? 0f : tp.getYDirAdj();
                boolean bold = tp != null && isBold(tp);

                boolean newPara = pageBreakPending || !haveLast || cur.length() == 0;
                if (!newPara) {
                    float gap = y - lastY;
                    if (gap < 0f) {
                        newPara = true;                       // salto in alto: nuova colonna
                    } else {
                        if (gap > 0.4f * size && (typicalGap == 0f || gap < typicalGap)) {
                            typicalGap = gap;
                        }
                        float ref = typicalGap > 0f ? typicalGap : 1.25f * size;
                        if (gap > ref * 1.35f) {
                            newPara = true;                   // spazio verticale piu' ampio
                        }
                    }
                    if (Math.abs(size - lastSize) > 0.9f || bold != lastBold) {
                        newPara = true;                       // titolo o cambio di stile
                    }
                    if (LIST_MARKER.matcher(text).matches()) {
                        newPara = true;                       // punto elenco
                    }
                }

                if (newPara) {
                    boolean breakFlag = pageBreakPending;
                    flush();
                    curSize = size;
                    curBold = bold;
                    curBreak = breakFlag;
                    pageBreakPending = false;
                } else {
                    joinLine(text);
                }
                lastY = y;
                lastSize = size;
                lastBold = bold;
                haveLast = true;
            }
            cur.append(text);
        }

        private void joinLine(String nextWord) {
            int n = cur.length();
            if (n > 1 && cur.charAt(n - 1) == '-' && Character.isLetter(cur.charAt(n - 2))
                    && Character.isLowerCase(nextWord.charAt(0))) {
                cur.setLength(n - 1);          // parola spezzata col trattino a fine riga
            } else if (n > 0 && cur.charAt(n - 1) != ' ') {
                cur.append(' ');
            }
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            if (cur.length() > 0 && cur.charAt(cur.length() - 1) != ' ') {
                cur.append(' ');
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            lineStart = true;
        }

        @Override
        protected void writeParagraphSeparator() throws IOException {
            lineStart = true;
        }

        @Override
        protected void writeParagraphStart() throws IOException {
            lineStart = true;
        }

        @Override
        protected void writeParagraphEnd() throws IOException {
            lineStart = true;
        }

        private static boolean isBold(TextPosition tp) {
            String n = null;
            try {
                n = tp.getFont().getName();
            } catch (Throwable ignored) {
            }
            if (n == null) {
                return false;
            }
            n = n.toLowerCase(Locale.ROOT);
            return n.contains("bold") || n.contains("black") || n.contains("heavy");
        }

        private void flush() {
            String t = cur.toString().replaceAll("\\s+", " ").trim();
            cur.setLength(0);
            if (t.isEmpty()) {
                if (curBreak) {
                    pageBreakPending = true;    // il salto pagina passa al prossimo paragrafo
                }
                curBreak = false;
                return;
            }
            res.chars += t.length();
            res.paras.add(new DocxWriter.Para(t, curSize, curBold, curBreak));
            curBreak = false;
        }
    }
}
