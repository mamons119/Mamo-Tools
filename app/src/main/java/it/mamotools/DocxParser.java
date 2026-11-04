package it.mamotools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Legge un file .docx (testo, stili, elenchi, tabelle, immagini, dimensioni pagina)
 * e lo trasforma in un DocModel. Solo Java standard: nessuna libreria esterna.
 */
final class DocxParser {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";

    /** Formattazione parziale: i campi null vengono ereditati. */
    private static final class Fmt {
        Boolean b, i, u, strike, caps;
        Float sz;
        Integer color;
        String font;
        Integer jc;
        Float before, after, line, indL, indFirst;
        String numId;
        Integer ilvl;
        Boolean pageBreakBefore;
        String basedOn;

        Fmt copy() {
            Fmt f = new Fmt();
            f.overlay(this);
            f.basedOn = basedOn;
            return f;
        }

        void overlay(Fmt o) {
            if (o.b != null) b = o.b;
            if (o.i != null) i = o.i;
            if (o.u != null) u = o.u;
            if (o.strike != null) strike = o.strike;
            if (o.caps != null) caps = o.caps;
            if (o.sz != null) sz = o.sz;
            if (o.color != null) color = o.color;
            if (o.font != null) font = o.font;
            if (o.jc != null) jc = o.jc;
            if (o.before != null) before = o.before;
            if (o.after != null) after = o.after;
            if (o.line != null) line = o.line;
            if (o.indL != null) indL = o.indL;
            if (o.indFirst != null) indFirst = o.indFirst;
            if (o.numId != null) numId = o.numId;
            if (o.ilvl != null) ilvl = o.ilvl;
            if (o.pageBreakBefore != null) pageBreakBefore = o.pageBreakBefore;
        }
    }

    private static final class Lvl {
        String fmt = "decimal";
        String text;
        int start = 1;
        float left;
        float hanging;
    }

    private final ZipFile zip;
    private final java.util.LinkedHashSet<String> warnSet = new java.util.LinkedHashSet<>();
    private final List<DocModel.Para> boxQueue = new ArrayList<>();
    private final List<String> noteOrder = new ArrayList<>();   // "f:ID" o "e:ID" nell'ordine di comparsa
    private boolean inHF = false;
    private String fieldPending = null;
    private boolean fieldSkip = false;
    private boolean usesTotal = false;
    private final Fmt defaults = new Fmt();
    private final Map<String, Fmt> styleDefs = new HashMap<>();
    private String defaultParaStyle;
    private final Map<String, String> rels = new HashMap<>();
    private final Map<String, String> numToAbs = new HashMap<>();
    private final Map<String, Map<Integer, Lvl>> absLvls = new HashMap<>();
    private final Map<String, int[]> counters = new HashMap<>();

    private DocxParser(ZipFile zip) {
        this.zip = zip;
    }

    static DocModel parse(File file) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(file);
        } catch (IOException e) {
            throw new IOException("Il file non e' un documento .docx valido.", e);
        }
        try {
            if (zip.getEntry("word/document.xml") == null) {
                throw new IOException("Il file non e' un documento .docx valido.");
            }
            return new DocxParser(zip).run();
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ avvio

    private DocModel run() throws IOException {
        defaults.sz = 11f;
        loadRels();
        loadStyles();
        loadNumbering();

        Document d = dom("word/document.xml");
        Element body = kid(d.getDocumentElement(), "body");
        DocModel model = new DocModel();
        if (body == null) {
            return model;
        }
        for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                processBlock((Element) n, model.blocks);
            }
        }
        readSection(kid(body, "sectPr"), model);
        appendNotes(model);
        model.warnings.addAll(warnSet);
        model.usesTotalPages = usesTotal;
        return model;
    }

    private void readSection(Element sect, DocModel m) throws IOException {
        if (sect == null) {
            return;
        }
        loadHF(sect, "headerReference", m.header);
        loadHF(sect, "footerReference", m.footer);
        Element sz = kid(sect, "pgSz");
        if (sz != null) {
            Float w = twips(wattr(sz, "w"));
            Float h = twips(wattr(sz, "h"));
            if (w != null && h != null && w > 100f && h > 100f) {
                m.pageW = w;
                m.pageH = h;
            }
        }
        Element mar = kid(sect, "pgMar");
        if (mar != null) {
            Float t = twips(wattr(mar, "top"));
            Float b = twips(wattr(mar, "bottom"));
            Float l = twips(wattr(mar, "left"));
            Float r = twips(wattr(mar, "right"));
            if (t != null) m.marginT = Math.max(0f, t);
            if (b != null) m.marginB = Math.max(0f, b);
            if (l != null) m.marginL = Math.max(0f, l);
            if (r != null) m.marginR = Math.max(0f, r);
        }
        if (m.pageW - m.marginL - m.marginR < 100f) {
            m.marginL = 72f;
            m.marginR = 72f;
        }
        if (m.pageH - m.marginT - m.marginB < 100f) {
            m.marginT = 72f;
            m.marginB = 72f;
        }
    }

    private void loadHF(Element sect, String refName, List<DocModel.Block> out) throws IOException {
        String rid = null;
        for (Element r : kids(sect, refName)) {
            String type = wattr(r, "type");
            String id = r.getAttributeNS(R, "id");
            if (id == null || id.isEmpty()) {
                id = r.getAttribute("r:id");
            }
            if (type == null || "default".equals(type)) {
                rid = id;
                break;
            }
        }
        String target = rid == null ? null : rels.get(rid);
        if (target == null) {
            return;
        }
        Document d = domOrNull(target.startsWith("/") ? target.substring(1) : normalize("word/" + target));
        if (d == null) {
            return;
        }
        inHF = true;
        try {
            for (Node n = d.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element) {
                    processBlock((Element) n, out);
                }
            }
        } finally {
            inHF = false;
        }
    }

    /** Note a pie' di pagina e di chiusura: mostrate in fondo al documento, col loro numero. */
    private void appendNotes(DocModel m) throws IOException {
        if (noteOrder.isEmpty()) {
            return;
        }
        Document fd = domOrNull("word/footnotes.xml");
        Document ed = domOrNull("word/endnotes.xml");
        DocModel.Para head = new DocModel.Para();
        DocModel.Run hr = new DocModel.Run();
        hr.text = "Note";
        hr.bold = true;
        hr.size = 12f;
        head.runs.add(hr);
        head.before = 18f;
        head.after = 6f;
        m.blocks.add(head);
        int num = 0;
        for (String key : noteOrder) {
            num++;
            boolean foot = key.startsWith("f:");
            String id = key.substring(2);
            Document d = foot ? fd : ed;
            Element note = null;
            if (d != null) {
                for (Element e : kids(d.getDocumentElement(), foot ? "footnote" : "endnote")) {
                    if (id.equals(wattr(e, "id"))) {
                        note = e;
                        break;
                    }
                }
            }
            if (note == null) {
                continue;
            }
            boolean first = true;
            for (Element p : kids(note, "p")) {
                DocModel.Para para = parsePara(p);
                if (first) {
                    DocModel.Run nr = new DocModel.Run();
                    nr.text = "[" + num + "] ";
                    nr.bold = true;
                    nr.size = 9f;
                    para.runs.add(0, nr);
                    first = false;
                }
                for (DocModel.Run r : para.runs) {
                    r.size = Math.min(r.size, 10f);
                }
                m.blocks.add(para);
            }
        }
        m.blocks.addAll(boxQueueDrain());
    }

    private List<DocModel.Block> boxQueueDrain() {
        List<DocModel.Block> l = new ArrayList<>(boxQueue);
        boxQueue.clear();
        return l;
    }

    // ------------------------------------------------------------------ stili, numerazione, relazioni

    private void loadRels() throws IOException {
        Document d = domOrNull("word/_rels/document.xml.rels");
        if (d == null) {
            return;
        }
        NodeList nl = d.getElementsByTagName("Relationship");
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            rels.put(e.getAttribute("Id"), e.getAttribute("Target"));
        }
    }

    private void loadStyles() throws IOException {
        Document d = domOrNull("word/styles.xml");
        if (d == null) {
            return;
        }
        Element root = d.getDocumentElement();
        Element dd = kid(root, "docDefaults");
        if (dd != null) {
            Element rpd = kid(dd, "rPrDefault");
            if (rpd != null) {
                parseRPr(kid(rpd, "rPr"), defaults);
            }
            Element ppd = kid(dd, "pPrDefault");
            if (ppd != null) {
                parsePPr(kid(ppd, "pPr"), defaults);
            }
        }
        for (Element st : kids(root, "style")) {
            String id = wattr(st, "styleId");
            if (id == null) {
                continue;
            }
            Fmt f = new Fmt();
            f.basedOn = wattr(kid(st, "basedOn"), "val");
            parsePPr(kid(st, "pPr"), f);
            parseRPr(kid(st, "rPr"), f);
            styleDefs.put(id, f);
            String type = wattr(st, "type");
            String def = wattr(st, "default");
            if ("paragraph".equals(type) && ("1".equals(def) || "true".equals(def))) {
                defaultParaStyle = id;
            }
        }
    }

    private Fmt resolveStyle(String id, int depth) {
        Fmt raw = id == null || depth > 12 ? null : styleDefs.get(id);
        if (raw == null) {
            return new Fmt();
        }
        Fmt base = resolveStyle(raw.basedOn, depth + 1);
        base.overlay(raw);
        return base;
    }

    private void loadNumbering() throws IOException {
        Document d = domOrNull("word/numbering.xml");
        if (d == null) {
            return;
        }
        Element root = d.getDocumentElement();
        for (Element an : kids(root, "abstractNum")) {
            String id = wattr(an, "abstractNumId");
            if (id == null) {
                continue;
            }
            Map<Integer, Lvl> levels = new HashMap<>();
            for (Element lvl : kids(an, "lvl")) {
                Lvl l = new Lvl();
                int il = intOr(wattr(lvl, "ilvl"), 0);
                String fmt = wattr(kid(lvl, "numFmt"), "val");
                if (fmt != null) {
                    l.fmt = fmt;
                }
                l.text = wattr(kid(lvl, "lvlText"), "val");
                l.start = intOr(wattr(kid(lvl, "start"), "val"), 1);
                Element ind = kid(kid(lvl, "pPr"), "ind");
                if (ind != null) {
                    Float left = twips(firstNonNull(wattr(ind, "left"), wattr(ind, "start")));
                    Float hang = twips(wattr(ind, "hanging"));
                    l.left = left == null ? 0f : left;
                    l.hanging = hang == null ? 0f : hang;
                }
                levels.put(il, l);
            }
            absLvls.put(id, levels);
        }
        for (Element num : kids(root, "num")) {
            String id = wattr(num, "numId");
            String abs = wattr(kid(num, "abstractNumId"), "val");
            if (id != null && abs != null) {
                numToAbs.put(id, abs);
            }
        }
    }

    private String listPrefix(String numId, int ilvl) {
        String abs = numToAbs.get(numId);
        Map<Integer, Lvl> lv = abs == null ? null : absLvls.get(abs);
        Lvl l = lv == null ? null : lv.get(ilvl);
        if (l == null) {
            return "• ";
        }
        if ("none".equals(l.fmt)) {
            return "";
        }
        if ("bullet".equals(l.fmt)) {
            return bulletOf(l.text) + " ";
        }
        int[] c = counters.get(numId);
        if (c == null) {
            c = new int[10];
            counters.put(numId, c);
        }
        int idx = Math.min(ilvl, 8);
        c[idx] = c[idx] == 0 ? l.start : c[idx] + 1;
        for (int k = idx + 1; k < c.length; k++) {
            c[k] = 0;
        }
        String t = l.text != null ? l.text : "%" + (idx + 1) + ".";
        for (int k = 0; k <= idx; k++) {
            Lvl lk = lv.get(k);
            String f = lk == null ? "decimal" : lk.fmt;
            int val = c[k] == 0 ? (lk == null ? 1 : lk.start) : c[k];
            t = t.replace("%" + (k + 1), formatNumber(f, val));
        }
        return t + " ";
    }

    private static String bulletOf(String text) {
        if (text == null || text.isEmpty()) {
            return "•";
        }
        char c = text.charAt(0);
        if (c >= 0xF000) {
            return "•";           // simbolo del font Symbol/Wingdings
        }
        if (text.equals("o")) {
            return "◦";
        }
        if (text.equals("§")) {
            return "▪";
        }
        return text.length() <= 2 ? text : "•";
    }

    private static String formatNumber(String fmt, int n) {
        switch (fmt) {
            case "lowerLetter":
                return letters(n, false);
            case "upperLetter":
                return letters(n, true);
            case "lowerRoman":
                return roman(n).toLowerCase(Locale.ROOT);
            case "upperRoman":
                return roman(n);
            case "decimalZero":
                return n < 10 ? "0" + n : String.valueOf(n);
            default:
                return String.valueOf(n);
        }
    }

    private static String letters(int n, boolean upper) {
        StringBuilder sb = new StringBuilder();
        int v = Math.max(1, n);
        while (v > 0) {
            v--;
            sb.insert(0, (char) ((upper ? 'A' : 'a') + (v % 26)));
            v /= 26;
        }
        return sb.toString();
    }

    private static String roman(int n) {
        int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] syms = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int v = Math.max(1, Math.min(n, 3999));
        for (int i = 0; i < vals.length; i++) {
            while (v >= vals[i]) {
                sb.append(syms[i]);
                v -= vals[i];
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ blocchi

    private void processBlock(Element e, List<DocModel.Block> out) throws IOException {
        switch (ln(e)) {
            case "p":
                out.add(parsePara(e));
                out.addAll(boxQueueDrain());
                break;
            case "tbl":
                out.add(parseTable(e));
                break;
            case "sdt": {
                Element content = kid(e, "sdtContent");
                if (content != null) {
                    for (Node n = content.getFirstChild(); n != null; n = n.getNextSibling()) {
                        if (n instanceof Element) {
                            processBlock((Element) n, out);
                        }
                    }
                }
                break;
            }
            case "ins":
            case "moveTo":
            case "customXml":
            case "smartTag":
                for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element) {
                        processBlock((Element) n, out);
                    }
                }
                break;
            default:
                break;
        }
    }

    private DocModel.Para parsePara(Element p) throws IOException {
        DocModel.Para para = new DocModel.Para();
        Element pPr = kid(p, "pPr");
        String styleId = wattr(kid(pPr, "pStyle"), "val");

        Fmt pf = defaults.copy();
        pf.overlay(resolveStyle(styleId != null ? styleId : defaultParaStyle, 0));
        Fmt direct = new Fmt();
        parsePPr(pPr, direct);
        pf.overlay(direct);

        para.align = pf.jc == null ? 0 : pf.jc;
        para.before = pf.before == null ? 0f : pf.before;
        para.after = pf.after == null ? 0f : pf.after;
        para.lineMult = pf.line == null ? 1f : Math.max(0.8f, Math.min(pf.line, 3f));
        para.indentLeft = pf.indL == null ? 0f : pf.indL;
        para.indentFirst = pf.indFirst == null ? 0f : pf.indFirst;
        para.defaultSize = pf.sz == null ? 11f : pf.sz;
        para.pageBreakBefore = Boolean.TRUE.equals(pf.pageBreakBefore);

        if (pf.numId != null && !"0".equals(pf.numId)) {
            int il = pf.ilvl == null ? 0 : pf.ilvl;
            para.listPrefix = listPrefix(pf.numId, il);
            String abs = numToAbs.get(pf.numId);
            Map<Integer, Lvl> lv = abs == null ? null : absLvls.get(abs);
            Lvl l = lv == null ? null : lv.get(il);
            if (l != null) {
                if (pf.indL == null) {
                    para.indentLeft = l.left;
                }
                if (pf.indFirst == null) {
                    para.indentFirst = -l.hanging;
                }
            } else if (pf.indL == null) {
                para.indentLeft = 18f * (il + 1);
                para.indentFirst = -18f;
            }
        }

        walkRuns(p, pf, para);
        return para;
    }

    /** Scorre i figli di un paragrafo (o di un collegamento ipertestuale ecc.) cercando i run. */
    private void walkRuns(Element parent, Fmt pf, DocModel.Para para) throws IOException {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) {
                continue;
            }
            Element c = (Element) n;
            switch (ln(c)) {
                case "r":
                    parseRun(c, pf, para);
                    break;
                case "fldSimple": {
                    String instr = wattr(c, "instr");
                    String t = instr == null ? "" : instr.trim().toUpperCase(Locale.ROOT);
                    if (t.equals("PAGE") || t.startsWith("PAGE ") || t.startsWith("PAGE\\")) {
                        addMarker(para, pf, "\u0001");
                    } else if (t.startsWith("NUMPAGES")) {
                        usesTotal = true;
                        addMarker(para, pf, "\u0002");
                    } else {
                        walkRuns(c, pf, para);
                    }
                    break;
                }
                case "oMathPara":
                case "oMath": {
                    NodeList ts = c.getElementsByTagNameNS(M, "t");
                    StringBuilder mt = new StringBuilder();
                    for (int i = 0; i < ts.getLength(); i++) {
                        mt.append(ts.item(i).getTextContent());
                    }
                    if (mt.length() > 0) {
                        DocModel.Run mr = new DocModel.Run();
                        mr.text = " " + mt + " ";
                        mr.italic = true;
                        mr.size = pf.sz == null ? 11f : pf.sz;
                        para.runs.add(mr);
                        warnSet.add("Formule matematiche: mostrate come testo semplice, senza l'impaginazione "
                                + "originale (frazioni, radici, apici...)");
                    }
                    break;
                }
                case "hyperlink":
                case "ins":
                case "moveTo":
                case "smartTag":
                case "customXml":
                    walkRuns(c, pf, para);
                    break;
                case "sdt": {
                    Element content = kid(c, "sdtContent");
                    if (content != null) {
                        walkRuns(content, pf, para);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void parseRun(Element r, Fmt pf, DocModel.Para para) throws IOException {
        Fmt rf = pf.copy();
        Element rPr = kid(r, "rPr");
        if (rPr != null) {
            String rs = wattr(kid(rPr, "rStyle"), "val");
            if (rs != null) {
                rf.overlay(resolveStyle(rs, 0));
            }
            Fmt direct = new Fmt();
            parseRPr(rPr, direct);
            rf.overlay(direct);
        }

        StringBuilder sb = new StringBuilder();
        for (Node n = r.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) {
                continue;
            }
            Element c = (Element) n;
            switch (ln(c)) {
                case "t":
                    if (!fieldSkip) {
                        sb.append(c.getTextContent());
                    }
                    break;
                case "instrText": {
                    String t = c.getTextContent().trim().toUpperCase(Locale.ROOT);
                    if (t.startsWith("NUMPAGES")) {
                        fieldPending = "\u0002";
                        usesTotal = true;
                    } else if (t.equals("PAGE") || t.startsWith("PAGE ") || t.startsWith("PAGE\\")) {
                        fieldPending = "\u0001";
                    }
                    break;
                }
                case "fldChar": {
                    String type = wattr(c, "fldCharType");
                    if ("begin".equals(type)) {
                        fieldPending = null;
                    } else if ("separate".equals(type)) {
                        if (fieldPending != null) {
                            sb.append(fieldPending);
                            fieldPending = null;
                            fieldSkip = true;
                        }
                    } else if ("end".equals(type)) {
                        fieldSkip = false;
                        fieldPending = null;
                    }
                    break;
                }
                case "footnoteReference":
                case "endnoteReference": {
                    String id = wattr(c, "id");
                    if (id != null) {
                        noteOrder.add((ln(c).startsWith("foot") ? "f:" : "e:") + id);
                        sb.append('[').append(noteOrder.size()).append(']');
                    }
                    break;
                }
                case "pict":
                    scanShape(c, para, false);
                    break;
                case "tab":
                    sb.append('\t');
                    break;
                case "br": {
                    String type = wattr(c, "type");
                    if ("page".equals(type)) {
                        if (!para.runs.isEmpty() || sb.length() > 0) {
                            para.pageBreakAfter = true;
                        } else {
                            para.pageBreakBefore = true;
                        }
                    } else {
                        sb.append('\n');
                    }
                    break;
                }
                case "cr":
                    sb.append('\n');
                    break;
                case "noBreakHyphen":
                    sb.append('-');
                    break;
                case "drawing":
                    addImage(c, para);
                    scanShape(c, para, true);
                    break;
                case "AlternateContent": {
                    Element choice = kid(c, "Choice");
                    Element scope = choice != null ? choice : c;
                    NodeList nl = scope.getElementsByTagNameNS(W, "drawing");
                    if (nl.getLength() > 0) {
                        addImage((Element) nl.item(0), para);
                    }
                    scanShape(scope, para, nl.getLength() > 0);
                    break;
                }
                default:
                    break;
            }
        }
        if (sb.length() == 0) {
            return;
        }
        DocModel.Run run = new DocModel.Run();
        run.text = Boolean.TRUE.equals(rf.caps) ? sb.toString().toUpperCase(Locale.ROOT) : sb.toString();
        run.bold = Boolean.TRUE.equals(rf.b);
        run.italic = Boolean.TRUE.equals(rf.i);
        run.underline = Boolean.TRUE.equals(rf.u);
        run.strike = Boolean.TRUE.equals(rf.strike);
        run.size = rf.sz == null ? 11f : Math.max(1f, rf.sz);
        run.color = rf.color == null ? 0xFF000000 : rf.color;
        run.font = rf.font == null ? "" : rf.font;
        para.runs.add(run);
    }

    /** Caselle di testo (le metto in coda come paragrafi) e segnalazione di grafici, SmartArt, forme. */
    private void scanShape(Element scope, DocModel.Para para, boolean modern) throws IOException {
        NodeList tb = scope.getElementsByTagNameNS(W, "txbxContent");
        int boxes = 0;
        for (int i = 0; i < tb.getLength(); i++) {
            Element box = (Element) tb.item(i);
            for (Element p : kids(box, "p")) {
                if (!inHF) {
                    boxQueue.add(parsePara(p));
                }
                boxes++;
            }
        }
        boolean blip = scope.getElementsByTagNameNS(A, "blip").getLength() > 0;
        boolean special = false;
        NodeList gd = scope.getElementsByTagNameNS(A, "graphicData");
        for (int i = 0; i < gd.getLength(); i++) {
            String uri = ((Element) gd.item(i)).getAttribute("uri").toLowerCase(Locale.ROOT);
            if (uri.contains("chart")) {
                warnSet.add("Grafici: non vengono disegnati");
                special = true;
            } else if (uri.contains("diagram")) {
                warnSet.add("Diagrammi SmartArt: non vengono disegnati");
                special = true;
            }
        }
        if (!special && !blip && boxes == 0) {
            warnSet.add("Forme e disegni: non vengono disegnati");
        }
        if (inHF && (blip || boxes > 0)) {
            warnSet.add("Immagini e caselle di testo nelle intestazioni e nei pie' di pagina: non vengono mostrate");
        }
    }

    private void addMarker(DocModel.Para para, Fmt pf, String marker) {
        DocModel.Run run = new DocModel.Run();
        run.text = marker;
        run.bold = Boolean.TRUE.equals(pf.b);
        run.italic = Boolean.TRUE.equals(pf.i);
        run.size = pf.sz == null ? 11f : Math.max(1f, pf.sz);
        run.color = pf.color == null ? 0xFF000000 : pf.color;
        run.font = pf.font == null ? "" : pf.font;
        para.runs.add(run);
    }

    private void addImage(Element drawing, DocModel.Para para) throws IOException {
        if (inHF) {
            return;   // gli id delle immagini di intestazione/pie' di pagina sono in un altro file di relazioni
        }
        NodeList blips = drawing.getElementsByTagNameNS(A, "blip");
        if (blips.getLength() == 0) {
            return;
        }
        Element blip = (Element) blips.item(0);
        String rid = blip.getAttributeNS(R, "embed");
        if (rid == null || rid.isEmpty()) {
            rid = blip.getAttribute("r:embed");
        }
        String target = rels.get(rid);
        if (target == null) {
            return;
        }
        String path = target.startsWith("/") ? target.substring(1) : normalize("word/" + target);
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.getSize() > 40L * 1024 * 1024) {
            return;
        }
        DocModel.Img img = new DocModel.Img();
        img.data = readAll(entry);
        NodeList ext = drawing.getElementsByTagNameNS(WP, "extent");
        if (ext.getLength() > 0) {
            Element e = (Element) ext.item(0);
            try {
                img.w = Long.parseLong(e.getAttribute("cx")) / 12700f;
                img.h = Long.parseLong(e.getAttribute("cy")) / 12700f;
            } catch (NumberFormatException ignored) {
                img.w = 0f;
                img.h = 0f;
            }
        }
        para.images.add(img);
    }

    private DocModel.Table parseTable(Element tbl) throws IOException {
        DocModel.Table t = new DocModel.Table();
        Element tblPr = kid(tbl, "tblPr");
        Element borders = kid(tblPr, "tblBorders");
        if (borders != null) {
            for (Element side : kidsAll(borders)) {
                String v = wattr(side, "val");
                if (v != null && !v.equals("nil") && !v.equals("none")) {
                    t.borders = true;
                }
            }
        }
        String style = wattr(kid(tblPr, "tblStyle"), "val");
        if (style != null && style.toLowerCase(Locale.ROOT).contains("grid")) {
            t.borders = true;
        }
        Element grid = kid(tbl, "tblGrid");
        if (grid != null) {
            List<Element> cols = kids(grid, "gridCol");
            t.cols = new float[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                Float w = twips(wattr(cols.get(i), "w"));
                t.cols[i] = w == null ? 0f : w;
            }
        }
        for (Element tr : kids(tbl, "tr")) {
            List<DocModel.Cell> row = new ArrayList<>();
            for (Element tc : kids(tr, "tc")) {
                DocModel.Cell cell = new DocModel.Cell();
                cell.span = Math.max(1, intOr(wattr(kid(kid(tc, "tcPr"), "gridSpan"), "val"), 1));
                for (Node n = tc.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element)) {
                        continue;
                    }
                    Element c = (Element) n;
                    String name = ln(c);
                    if (name.equals("p")) {
                        cell.paras.add(parsePara(c));
                    } else if (name.equals("tbl")) {
                        DocModel.Table nested = parseTable(c);   // tabelle annidate: appiattite
                        for (List<DocModel.Cell> nr : nested.rows) {
                            for (DocModel.Cell nc : nr) {
                                cell.paras.addAll(nc.paras);
                            }
                        }
                    }
                }
                row.add(cell);
            }
            t.rows.add(row);
        }
        return t;
    }

    // ------------------------------------------------------------------ proprieta'

    private static void parseRPr(Element rPr, Fmt f) {
        if (rPr == null) {
            return;
        }
        Element e;
        if ((e = kid(rPr, "b")) != null) f.b = toggle(e);
        if ((e = kid(rPr, "i")) != null) f.i = toggle(e);
        if ((e = kid(rPr, "strike")) != null) f.strike = toggle(e);
        if ((e = kid(rPr, "caps")) != null) f.caps = toggle(e);
        if ((e = kid(rPr, "u")) != null) {
            String v = wattr(e, "val");
            f.u = v == null || !v.equals("none");
        }
        if ((e = kid(rPr, "sz")) != null) {
            String v = wattr(e, "val");
            if (v != null) {
                try {
                    f.sz = Float.parseFloat(v) / 2f;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if ((e = kid(rPr, "color")) != null) {
            String v = wattr(e, "val");
            if (v != null && v.length() == 6) {
                try {
                    f.color = 0xFF000000 | Integer.parseInt(v, 16);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if ((e = kid(rPr, "rFonts")) != null) {
            String v = firstNonNull(wattr(e, "ascii"), wattr(e, "hAnsi"));
            if (v != null) {
                f.font = v;
            }
        }
    }

    private static void parsePPr(Element pPr, Fmt f) {
        if (pPr == null) {
            return;
        }
        Element e;
        if ((e = kid(pPr, "jc")) != null) {
            String v = wattr(e, "val");
            if (v != null) {
                f.jc = alignOf(v);
            }
        }
        if ((e = kid(pPr, "spacing")) != null) {
            Float before = twips(wattr(e, "before"));
            Float after = twips(wattr(e, "after"));
            if (before != null) f.before = before;
            if (after != null) f.after = after;
            String line = wattr(e, "line");
            String rule = wattr(e, "lineRule");
            if (line != null && (rule == null || rule.equals("auto"))) {
                try {
                    f.line = Float.parseFloat(line) / 240f;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if ((e = kid(pPr, "ind")) != null) {
            Float left = twips(firstNonNull(wattr(e, "left"), wattr(e, "start")));
            Float hang = twips(wattr(e, "hanging"));
            Float first = twips(wattr(e, "firstLine"));
            if (left != null) f.indL = left;
            if (hang != null) {
                f.indFirst = -hang;
            } else if (first != null) {
                f.indFirst = first;
            }
        }
        Element np = kid(pPr, "numPr");
        if (np != null) {
            String id = wattr(kid(np, "numId"), "val");
            if (id != null) f.numId = id;
            String il = wattr(kid(np, "ilvl"), "val");
            if (il != null) f.ilvl = intOr(il, 0);
        }
        if ((e = kid(pPr, "pageBreakBefore")) != null) {
            f.pageBreakBefore = toggle(e);
        }
    }

    private static int alignOf(String v) {
        switch (v) {
            case "center":
                return 1;
            case "right":
            case "end":
                return 2;
            case "both":
            case "distribute":
            case "justify":
                return 3;
            default:
                return 0;
        }
    }

    private static Boolean toggle(Element e) {
        String v = wattr(e, "val");
        if (v == null) {
            return Boolean.TRUE;
        }
        return !(v.equals("0") || v.equals("false") || v.equals("off"));
    }

    // ------------------------------------------------------------------ utilita' DOM

    private Document dom(String name) throws IOException {
        Document d = domOrNull(name);
        if (d == null) {
            throw new IOException("Parte mancante nel file .docx: " + name);
        }
        return d;
    }

    private Document domOrNull(String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // non tutti i parser supportano questa opzione
            }
            return f.newDocumentBuilder().parse(in);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("XML non valido in " + name, ex);
        }
    }

    private byte[] readAll(ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    (int) Math.max(1024, Math.min(e.getSize(), 8L * 1024 * 1024)));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String ln(Node n) {
        String s = n.getLocalName();
        if (s == null) {
            s = n.getNodeName();
            int i = s.indexOf(':');
            if (i >= 0) {
                s = s.substring(i + 1);
            }
        }
        return s;
    }

    private static List<Element> kids(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ln(n).equals(name)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static List<Element> kidsAll(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element kid(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ln(n).equals(name)) {
                return (Element) n;
            }
        }
        return null;
    }

    /** Valore dell'attributo w:name, oppure null se assente o vuoto. */
    private static String wattr(Element e, String name) {
        if (e == null) {
            return null;
        }
        String v = e.getAttributeNS(W, name);
        if (v == null || v.isEmpty()) {
            v = e.getAttribute("w:" + name);
        }
        return v == null || v.isEmpty() ? null : v;
    }

    private static Float twips(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Float.parseFloat(v) / 20f;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
