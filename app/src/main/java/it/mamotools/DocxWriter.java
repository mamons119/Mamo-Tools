package it.mamotools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Scrive un file .docx minimale ma valido (solo Java, nessuna libreria). */
final class DocxWriter {

    static final class Para {
        final String text;
        final float sizePt;
        final boolean bold;
        final boolean pageBreakBefore;

        Para(String text, float sizePt, boolean bold, boolean pageBreakBefore) {
            this.text = text;
            this.sizePt = sizePt;
            this.bold = bold;
            this.pageBreakBefore = pageBreakBefore;
        }
    }

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private static final String CONTENT_TYPES = HEAD
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "</Types>";

    private static final String RELS = HEAD
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";

    private static final String DOC_RELS = HEAD
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = HEAD
            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:docDefaults>"
            + "<w:rPrDefault><w:rPr>"
            + "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>"
            + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"it-IT\"/>"
            + "</w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
            + "</w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
            + "</w:styles>";

    private DocxWriter() {
    }

    static void write(List<Para> paras, float pageWpt, float pageHpt, OutputStream out)
            throws IOException {
        ZipOutputStream z = new ZipOutputStream(out);
        put(z, "[Content_Types].xml", CONTENT_TYPES);
        put(z, "_rels/.rels", RELS);
        put(z, "word/_rels/document.xml.rels", DOC_RELS);
        put(z, "word/styles.xml", STYLES);
        put(z, "word/document.xml", document(paras, pageWpt, pageHpt));
        z.finish();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String document(List<Para> paras, float pageW, float pageH) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append(HEAD);
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        if (paras.isEmpty()) {
            sb.append("<w:p/>");
        }
        for (Para p : paras) {
            sb.append("<w:p><w:pPr>");
            if (p.pageBreakBefore) {
                sb.append("<w:pageBreakBefore/>");
            }
            sb.append("</w:pPr>");
            int half = Math.max(2, Math.round(p.sizePt * 2f));
            appendRuns(sb, p.text, half, p.bold);
            sb.append("</w:p>");
        }
        int w = Math.round(pageW * 20f);
        int h = Math.round(pageH * 20f);
        if (w < 2000 || h < 2000) {
            w = 11906;
            h = 16838;
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"").append(w).append("\" w:h=\"").append(h).append('"');
        if (w > h) {
            sb.append(" w:orient=\"landscape\"");
        }
        sb.append("/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\""
                + " w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>");
        sb.append("</w:body></w:document>");
        return sb.toString();
    }

    /** Scrive il testo in un run, trasformando tabulazioni e a-capo negli elementi Word. */
    private static void appendRuns(StringBuilder sb, String text, int halfPts, boolean bold) {
        sb.append("<w:r><w:rPr>");
        if (bold) {
            sb.append("<w:b/><w:bCs/>");
        }
        sb.append("<w:sz w:val=\"").append(halfPts).append("\"/><w:szCs w:val=\"")
                .append(halfPts).append("\"/></w:rPr>");
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') {
                flushText(sb, chunk);
                sb.append(c == '\n' ? "<w:br/>" : "<w:tab/>");
            } else if (isXmlChar(c)) {
                chunk.append(c);
            }
        }
        flushText(sb, chunk);
        sb.append("</w:r>");
    }

    private static void flushText(StringBuilder sb, StringBuilder chunk) {
        if (chunk.length() == 0) {
            return;
        }
        sb.append("<w:t xml:space=\"preserve\">");
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        sb.append("</w:t>");
        chunk.setLength(0);
    }

    /** Caratteri ammessi da XML 1.0 (i caratteri di controllo farebbero rifiutare il file). */
    private static boolean isXmlChar(char c) {
        if (c == '\n' || c == '\r' || c == '\t') {
            return true;
        }
        if (c < 0x20) {
            return false;
        }
        return c != 0xFFFE && c != 0xFFFF;
    }
}
