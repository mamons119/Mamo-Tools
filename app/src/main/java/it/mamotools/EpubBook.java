package it.mamotools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Libro EPUB (2 e 3): metadati, ordine di lettura, indice, copertina, risorse e caratteri offuscati. Java puro. */
final class EpubBook {

    static final class Item {
        String id, path, type, props = "";
        long size;
    }

    static final class TocEntry {
        String title, path, frag = "";
        int depth;
    }

    String title = "", author = "", language = "", publisher = "", date = "", description = "", identifier = "";
    final List<Item> spine = new ArrayList<>();
    final List<TocEntry> toc = new ArrayList<>();
    final Map<String, Item> manifest = new HashMap<>(); // per percorso
    String coverPath = null;
    boolean fixedLayout = false, drm = false, rtl = false;
    long totalSize = 0;
    long[] cum;

    private ZipFile zip;
    private final Map<String, String> fontAlgo = new HashMap<>();
    private final Map<String, String> entryNames = new HashMap<>(); // percorso normalizzato -> nome vero nello zip
    private String opfDir = "";
    private byte[] idpfKey, adobeKey;

    // ------------------------------------------------------------------ apertura

    static EpubBook open(File f) throws IOException {
        EpubBook b = new EpubBook();
        b.zip = new ZipFile(f);
        try {
            b.parse();
        } catch (IOException | RuntimeException e) {
            b.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage() == null
                    ? "File EPUB non valido" : e.getMessage(), e);
        }
        return b;
    }

    void close() {
        try {
            if (zip != null) {
                zip.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void parse() throws IOException {
        java.util.Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            entryNames.put(norm(e.getName()), e.getName());
        }
        byte[] cont = readRaw("META-INF/container.xml");
        if (cont == null) {
            throw new IOException("Non e' un EPUB valido (manca container.xml)");
        }
        Document c = dom(cont);
        String opf = null;
        NodeList rf = c.getElementsByTagNameNS("*", "rootfile");
        for (int i = 0; i < rf.getLength() && opf == null; i++) {
            String fp = ((Element) rf.item(i)).getAttribute("full-path");
            if (!fp.isEmpty()) {
                opf = fp;
            }
        }
        if (opf == null) {
            throw new IOException("Indice del libro non trovato");
        }
        opf = norm(opf);
        int sl = opf.lastIndexOf('/');
        opfDir = sl < 0 ? "" : opf.substring(0, sl + 1);
        byte[] ob = readRaw(opf);
        if (ob == null) {
            throw new IOException("File del libro (OPF) mancante");
        }
        Document d = dom(ob);

        // metadati
        title = first(d, "title");
        author = join(d, "creator");
        language = first(d, "language");
        publisher = first(d, "publisher");
        date = first(d, "date");
        description = stripTags(first(d, "description"));
        String uid = "";
        Element pkg = d.getDocumentElement();
        String uidRef = pkg == null ? "" : pkg.getAttribute("unique-identifier");
        NodeList ids = d.getElementsByTagNameNS("*", "identifier");
        for (int i = 0; i < ids.getLength(); i++) {
            Element e = (Element) ids.item(i);
            String v = e.getTextContent() == null ? "" : e.getTextContent().trim();
            if (uid.isEmpty() || (!uidRef.isEmpty() && uidRef.equals(e.getAttribute("id")))) {
                uid = v;
            }
        }
        identifier = uid;

        // manifest
        String coverMetaId = null;
        NodeList metas = d.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element m = (Element) metas.item(i);
            if ("cover".equals(m.getAttribute("name"))) {
                coverMetaId = m.getAttribute("content");
            }
            String prop = m.getAttribute("property");
            if ("rendition:layout".equals(prop) && m.getTextContent() != null
                    && m.getTextContent().trim().equals("pre-paginated")) {
                fixedLayout = true;
            }
        }
        Map<String, Item> byId = new HashMap<>();
        NodeList mi = d.getElementsByTagNameNS("*", "item");
        String navPath = null;
        for (int i = 0; i < mi.getLength(); i++) {
            Element e = (Element) mi.item(i);
            Item it = new Item();
            it.id = e.getAttribute("id");
            it.path = resolve(opfDir, e.getAttribute("href"));
            it.type = e.getAttribute("media-type");
            it.props = e.getAttribute("properties");
            ZipEntry ze = entry(it.path);
            it.size = ze == null ? 0 : Math.max(1, ze.getSize());
            byId.put(it.id, it);
            manifest.put(it.path, it);
            if (it.props.contains("nav")) {
                navPath = it.path;
            }
            if (it.props.contains("cover-image")) {
                coverPath = it.path;
            }
        }
        if (coverPath == null && coverMetaId != null && byId.containsKey(coverMetaId)) {
            coverPath = byId.get(coverMetaId).path;
        }
        if (coverPath == null) {
            for (Item it : byId.values()) {
                if (it.type.startsWith("image/") && it.id.toLowerCase(Locale.ROOT).contains("cover")) {
                    coverPath = it.path;
                    break;
                }
            }
        }
        // spine
        NodeList sp = d.getElementsByTagNameNS("*", "spine");
        String ncxId = null;
        if (sp.getLength() > 0) {
            Element se = (Element) sp.item(0);
            ncxId = se.getAttribute("toc");
            if ("rtl".equals(se.getAttribute("page-progression-direction"))) {
                rtl = true;
            }
        }
        NodeList refs = d.getElementsByTagNameNS("*", "itemref");
        for (int i = 0; i < refs.getLength(); i++) {
            Element e = (Element) refs.item(i);
            Item it = byId.get(e.getAttribute("idref"));
            if (it != null && entry(it.path) != null && !"no".equals(e.getAttribute("linear"))) {
                spine.add(it);
            } else if (it != null && entry(it.path) != null) {
                spine.add(it); // elementi non lineari: li teniamo in coda alla lettura
            }
        }
        if (spine.isEmpty()) {
            throw new IOException("Il libro non contiene capitoli leggibili");
        }
        cum = new long[spine.size() + 1];
        for (int i = 0; i < spine.size(); i++) {
            cum[i + 1] = cum[i] + Math.max(1, spine.get(i).size);
        }
        totalSize = cum[spine.size()];

        // indice
        try {
            if (navPath != null) {
                parseNav(navPath);
            }
            if (toc.isEmpty() && ncxId != null && byId.containsKey(ncxId)) {
                parseNcx(byId.get(ncxId).path);
            }
            if (toc.isEmpty()) {
                for (Item it : manifest.values()) {
                    if ("application/x-dtbncx+xml".equals(it.type)) {
                        parseNcx(it.path);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (toc.isEmpty()) {
            for (int i = 0; i < spine.size(); i++) {
                TocEntry t = new TocEntry();
                t.title = "Capitolo " + (i + 1);
                t.path = spine.get(i).path;
                toc.add(t);
            }
        }
        if (title.isEmpty()) {
            title = "Libro senza titolo";
        }
        // cifratura: caratteri offuscati ok, altro = DRM
        byte[] enc = readRaw("META-INF/encryption.xml");
        if (enc != null) {
            try {
                Document ed = dom(enc);
                NodeList ec = ed.getElementsByTagNameNS("*", "EncryptedData");
                for (int i = 0; i < ec.getLength(); i++) {
                    Element e = (Element) ec.item(i);
                    NodeList em = e.getElementsByTagNameNS("*", "EncryptionMethod");
                    NodeList cr = e.getElementsByTagNameNS("*", "CipherReference");
                    String algo = em.getLength() > 0 ? ((Element) em.item(0)).getAttribute("Algorithm") : "";
                    String uri = cr.getLength() > 0 ? ((Element) cr.item(0)).getAttribute("URI") : "";
                    if (algo.equals("http://www.idpf.org/2008/embedding")
                            || algo.equals("http://ns.adobe.com/pdf/enc#RC")) {
                        fontAlgo.put(norm(decode(uri)), algo);
                    } else {
                        drm = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!fontAlgo.isEmpty()) {
            try {
                String u = identifier.replaceAll("\\s", "");
                idpfKey = MessageDigest.getInstance("SHA-1").digest(u.getBytes("UTF-8"));
                String hex = u.replace("urn:uuid:", "").replace("-", "");
                if (hex.length() >= 32 && hex.substring(0, 32).matches("[0-9a-fA-F]{32}")) {
                    adobeKey = new byte[16];
                    for (int i = 0; i < 16; i++) {
                        adobeKey[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ indice

    private void parseNav(String navPath) throws IOException {
        byte[] b = readRaw(navPath);
        if (b == null) {
            return;
        }
        String s = decodeText(b);
        Matcher nm = Pattern.compile("(?is)<nav\\b([^>]*)>(.*?)</nav>").matcher(s);
        String body = null;
        while (nm.find()) {
            String attrs = nm.group(1).toLowerCase(Locale.ROOT);
            if (attrs.contains("toc")) {
                body = nm.group(2);
                break;
            }
            if (body == null) {
                body = nm.group(2);
            }
        }
        if (body == null) {
            return;
        }
        int slash = navPath.lastIndexOf('/');
        String dir = slash < 0 ? "" : navPath.substring(0, slash + 1);
        Matcher m = Pattern.compile("(?is)<(/?)(ol|ul)\\b[^>]*>|<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>")
                .matcher(body);
        int depth = 0;
        while (m.find()) {
            if (m.group(2) != null) {
                depth += m.group(1).isEmpty() ? 1 : -1;
            } else {
                String text = unescape(stripTags(m.group(4))).trim();
                if (text.isEmpty()) {
                    continue;
                }
                addToc(text, dir, m.group(3), Math.max(0, depth - 1));
            }
        }
    }

    private void parseNcx(String ncxPath) throws IOException {
        byte[] b = readRaw(ncxPath);
        if (b == null) {
            return;
        }
        int slash = ncxPath.lastIndexOf('/');
        String dir = slash < 0 ? "" : ncxPath.substring(0, slash + 1);
        Document d = dom(b);
        NodeList nm = d.getElementsByTagNameNS("*", "navMap");
        if (nm.getLength() > 0) {
            ncxWalk(nm.item(0), dir, 0);
        }
    }

    private void ncxWalk(Node parent, String dir, int depth) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE || !"navPoint".equals(local(n))) {
                continue;
            }
            String text = "", src = "";
            for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                if ("navLabel".equals(local(c))) {
                    text = c.getTextContent() == null ? "" : c.getTextContent().trim();
                } else if ("content".equals(local(c))) {
                    src = ((Element) c).getAttribute("src");
                }
            }
            if (!text.isEmpty() && !src.isEmpty()) {
                addToc(text, dir, src, depth);
            }
            ncxWalk(n, dir, depth + 1);
        }
    }

    private void addToc(String text, String dir, String href, int depth) {
        String h = unescape(href);
        String frag = "";
        int hash = h.indexOf('#');
        if (hash >= 0) {
            frag = h.substring(hash + 1);
            h = h.substring(0, hash);
        }
        TocEntry t = new TocEntry();
        t.title = text.replaceAll("\\s+", " ");
        t.path = h.isEmpty() ? "" : resolve(dir, h);
        t.frag = frag;
        t.depth = depth;
        toc.add(t);
    }

    private static String local(Node n) {
        String s = n.getLocalName();
        return s != null ? s : n.getNodeName();
    }

    // ------------------------------------------------------------------ accesso alle risorse

    int spineIndex(String path) {
        for (int i = 0; i < spine.size(); i++) {
            if (spine.get(i).path.equals(path)) {
                return i;
            }
        }
        return -1;
    }

    String mime(String path) {
        Item it = manifest.get(path);
        if (it != null && it.type != null && !it.type.isEmpty()) {
            return it.type;
        }
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".xhtml") || p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    /** Contenuto di un file del libro (con caratteri disoffuscati), oppure null. */
    byte[] read(String path) {
        try {
            byte[] b = readRaw(path);
            if (b == null) {
                return null;
            }
            String algo = fontAlgo.get(norm(path));
            if (algo != null) {
                byte[] key = algo.startsWith("http://www.idpf") ? idpfKey : adobeKey;
                int n = algo.startsWith("http://www.idpf") ? 1040 : 1024;
                if (key != null) {
                    for (int i = 0; i < Math.min(n, b.length); i++) {
                        b[i] ^= key[i % key.length];
                    }
                }
            }
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private ZipEntry entry(String path) {
        String real = entryNames.get(norm(path));
        return real == null ? null : zip.getEntry(real);
    }

    private byte[] readRaw(String path) throws IOException {
        ZipEntry e = entry(path);
        if (e == null) {
            String dec = decode(path);
            e = entry(dec);
        }
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, Math.min(e.getSize(), 1 << 26)));
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > 200_000_000) {
                    throw new IOException("File troppo grande");
                }
            }
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ utilita'

    /** Percorso normalizzato: separatori, niente "./" e "../". */
    static String norm(String p) {
        if (p == null) {
            return "";
        }
        p = p.replace('\\', '/');
        List<String> out = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
            } else {
                out.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            sb.append(i > 0 ? "/" : "").append(out.get(i));
        }
        return sb.toString();
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String resolve(String dir, String href) {
        String h = decode(href);
        int q = h.indexOf('?');
        if (q >= 0) {
            h = h.substring(0, q);
        }
        if (h.startsWith("/")) {
            return norm(h);
        }
        return norm(dir + h);
    }

    static String resolveAgainst(String basePath, String href) {
        int sl = basePath.lastIndexOf('/');
        return resolve(sl < 0 ? "" : basePath.substring(0, sl + 1), href);
    }

    private static Document dom(byte[] b) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {
            }
            DocumentBuilder db = f.newDocumentBuilder();
            db.setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
            db.setErrorHandler(null);
            return db.parse(new InputSource(new StringReader(decodeText(b))));
        } catch (Exception e) {
            throw new IOException("XML non valido: " + e.getMessage(), e);
        }
    }

    private static String first(Document d, String tag) {
        NodeList l = d.getElementsByTagNameNS("*", tag);
        return l.getLength() == 0 || l.item(0).getTextContent() == null ? "" : l.item(0).getTextContent().trim();
    }

    private static String join(Document d, String tag) {
        NodeList l = d.getElementsByTagNameNS("*", tag);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.getLength(); i++) {
            String t = l.item(i).getTextContent();
            if (t != null && !t.trim().isEmpty()) {
                sb.append(sb.length() > 0 ? ", " : "").append(t.trim());
            }
        }
        return sb.toString();
    }

    /** Testo con la codifica dichiarata (BOM, intestazione XML o meta charset); UTF-8 se non indicata. */
    static String decodeText(byte[] b) {
        Charset cs = Charset.forName("UTF-8");
        int off = 0;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            off = 3;
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16LE"));
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        } else {
            String head = new String(b, 0, Math.min(b.length, 600), Charset.forName("ISO-8859-1"));
            Matcher m = Pattern.compile("(?i)(?:encoding\\s*=\\s*|charset\\s*=\\s*)[\"']?([A-Za-z0-9_\\-]+)").matcher(head);
            if (m.find()) {
                try {
                    cs = Charset.forName(m.group(1));
                } catch (Exception ignored) {
                }
            }
        }
        return new String(b, off, b.length - off, cs);
    }

    static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                if (e.startsWith("#x")) {
                    r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                } else if (e.startsWith("#")) {
                    r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                } else {
                    switch (e) {
                        case "amp": r = "&"; break;
                        case "lt": r = "<"; break;
                        case "gt": r = ">"; break;
                        case "quot": r = "\""; break;
                        case "apos": r = "'"; break;
                        case "nbsp": r = " "; break;
                        case "rsquo": r = "\u2019"; break;
                        case "lsquo": r = "\u2018"; break;
                        case "rdquo": r = "\u201D"; break;
                        case "ldquo": r = "\u201C"; break;
                        case "hellip": r = "\u2026"; break;
                        case "ndash": r = "\u2013"; break;
                        case "mdash": r = "\u2014"; break;
                        default: r = m.group(0);
                    }
                }
            } catch (Exception ex) {
                r = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
