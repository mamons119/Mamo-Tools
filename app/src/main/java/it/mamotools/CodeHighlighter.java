package it.mamotools;

import java.util.Locale;

/** Colora il codice producendo HTML con span (classi c,s,n,k,t,f,a,v,p,g). Java puro. */
final class CodeHighlighter {

    private final StringBuilder o;
    private final String s;
    private final CodeLang l;
    private final int n;

    private CodeHighlighter(String s, CodeLang l, StringBuilder o) {
        this.s = s;
        this.l = l;
        this.n = s.length();
        this.o = o;
    }

    static String highlight(String text, CodeLang l) {
        StringBuilder sb = new StringBuilder(text.length() * 2);
        run(text, l, sb);
        return sb.toString();
    }

    private static void run(String text, CodeLang l, StringBuilder sb) {
        CodeHighlighter h = new CodeHighlighter(text, l, sb);
        switch (l.kind) {
            case CodeLang.XML:
            case CodeLang.HTML:
                h.markup();
                break;
            case CodeLang.JSON:
                h.json();
                break;
            case CodeLang.MARKDOWN:
                h.markdown();
                break;
            case CodeLang.YAML:
            case CodeLang.INI:
                h.lines();
                break;
            default:
                h.code();
        }
    }

    // ---------------------------------------------------------------- utilita'

    private void esc(CharSequence t, int a, int b) {
        for (int i = a; i < b; i++) {
            char c = t.charAt(i);
            switch (c) {
                case '<':
                    o.append("&lt;");
                    break;
                case '>':
                    o.append("&gt;");
                    break;
                case '&':
                    o.append("&amp;");
                    break;
                default:
                    o.append(c);
            }
        }
    }

    private void span(String cls, int a, int b) {
        if (b <= a) {
            return;
        }
        if (cls == null) {
            esc(s, a, b);
            return;
        }
        o.append("<span class=\"").append(cls).append("\">");
        esc(s, a, b);
        o.append("</span>");
    }

    private static boolean idStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean idPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private int lineEnd(int i) {
        int e = s.indexOf('\n', i);
        return e < 0 ? n : e;
    }

    // ---------------------------------------------------------------- linguaggi generici

    private void code() {
        int i = 0;
        boolean lineStart = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                o.append('\n');
                i++;
                lineStart = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                o.append(c);
                i++;
                continue;
            }
            // commento di riga
            boolean lc = false;
            for (String lcm : l.lineC) {
                if (!lcm.isEmpty() && s.regionMatches(l.ci, i, lcm, 0, lcm.length())) {
                    boolean alpha = Character.isLetter(lcm.charAt(0));
                    boolean okPrev = i == 0 || Character.isWhitespace(s.charAt(i - 1));
                    if (alpha && !okPrev) {
                        continue;
                    }
                    if (l.hashCommentWs && lcm.equals("#") && !okPrev) {
                        continue;
                    }
                    if (alpha && lcm.trim().length() > 0 && !lineStartOnly(i)) {
                        continue;
                    }
                    lc = true;
                    break;
                }
            }
            if (lc) {
                int e = lineEnd(i);
                span("c", i, e);
                i = e;
                continue;
            }
            if (l.blockStart != null && s.startsWith(l.blockStart, i)) {
                int e = s.indexOf(l.blockEnd, i + l.blockStart.length());
                e = e < 0 ? n : e + l.blockEnd.length();
                span("c", i, e);
                i = e;
                continue;
            }
            if (l.hashPre && lineStart && c == '#') {
                int e = lineEnd(i);
                // le direttive possono contenere commenti: si colora tutta la riga
                span("p", i, e);
                i = e;
                continue;
            }
            // stringhe
            if ((c == '"' || c == '\'') && l.quotes.indexOf(c) >= 0) {
                i = string(i, c);
                lineStart = false;
                continue;
            }
            if (c == '`' && l.backtick) {
                int e = i + 1;
                while (e < n && s.charAt(e) != '`') {
                    if (s.charAt(e) == '\\') {
                        e++;
                    }
                    e++;
                }
                e = Math.min(e + 1, n);
                span("s", i, e);
                i = e;
                lineStart = false;
                continue;
            }
            lineStart = false;
            if (c == '@' && l.atAnno && i + 1 < n && idStart(s.charAt(i + 1))) {
                int e = i + 1;
                while (e < n && (idPart(s.charAt(e)) || s.charAt(e) == '.')) {
                    e++;
                }
                span("a", i, e);
                i = e;
                continue;
            }
            if (c == '$' && l.dollarVar && i + 1 < n) {
                int e = i + 1;
                char d = s.charAt(e);
                if (d == '{') {
                    int k = s.indexOf('}', e);
                    e = k < 0 ? lineEnd(e) : k + 1;
                } else if (d == '(' ) {
                    e = i + 1;
                    span(null, i, e);
                    i = e;
                    continue;
                } else if (idStart(d)) {
                    while (e < n && idPart(s.charAt(e))) {
                        e++;
                    }
                } else if (Character.isDigit(d) || d == '@' || d == '?' || d == '#' || d == '*' || d == '!' || d == '$') {
                    e++;
                } else {
                    span(null, i, i + 1);
                    i++;
                    continue;
                }
                span("v", i, e);
                i = e;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int e = i + 1;
                while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '.' || s.charAt(e) == '_'
                        || ((s.charAt(e) == '+' || s.charAt(e) == '-') && (s.charAt(e - 1) == 'e' || s.charAt(e - 1) == 'E')
                        && !s.startsWith("0x", i)))) {
                    if (s.charAt(e) == '.' && e + 1 < n && !Character.isDigit(s.charAt(e + 1))) {
                        break;
                    }
                    e++;
                }
                span("n", i, e);
                i = e;
                continue;
            }
            if (idStart(c)) {
                int e = i + 1;
                while (e < n && idPart(s.charAt(e))) {
                    e++;
                }
                // prefissi di stringa (f"..", r'..', b"..")
                if (l.pyPrefix && e < n && (s.charAt(e) == '"' || s.charAt(e) == '\'') && e - i <= 2
                        && s.substring(i, e).toLowerCase(Locale.ROOT).matches("[rbfu]{1,2}")) {
                    i = string(e, s.charAt(e), i);
                    continue;
                }
                String w = s.substring(i, e);
                String key = l.ci ? w.toLowerCase(Locale.ROOT) : w;
                String cls = null;
                if (l.kw.contains(key)) {
                    cls = "k";
                } else if (l.types.contains(key)) {
                    cls = "t";
                } else {
                    int k = e;
                    while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
                        k++;
                    }
                    if (k < n && s.charAt(k) == '(') {
                        cls = "f";
                    } else if (l.capsType && Character.isUpperCase(w.charAt(0)) && w.length() > 1) {
                        cls = "t";
                    }
                }
                span(cls, i, e);
                i = e;
                continue;
            }
            // simbolo qualsiasi
            esc(s, i, i + 1);
            i++;
        }
    }

    private boolean lineStartOnly(int i) {
        int k = i - 1;
        while (k >= 0 && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
            k--;
        }
        return k < 0 || s.charAt(k) == '\n';
    }

    private int string(int i, char q) {
        return string(i, q, i);
    }

    /** Colora una stringa che inizia in i (il prefisso parte da from). Ritorna l'indice dopo la chiusura. */
    private int string(int i, char q, int from) {
        int e;
        boolean tri = l.triple && i + 2 < n && s.charAt(i + 1) == q && s.charAt(i + 2) == q;
        if (tri) {
            int k = s.indexOf(new String(new char[]{q, q, q}), i + 3);
            e = k < 0 ? n : k + 3;
        } else if (q == '\'' && l.charLit && !l.pyPrefix) {
            // letterale char (o apostrofo): solo se si chiude entro pochi caratteri
            e = i + 1;
            int lim = Math.min(n, i + 12);
            while (e < lim && s.charAt(e) != '\'' && s.charAt(e) != '\n') {
                if (s.charAt(e) == '\\') {
                    e++;
                }
                e++;
            }
            if (e < lim && s.charAt(e) == '\'') {
                e++;
            } else {
                span(null, i, i + 1);
                return i + 1;
            }
        } else {
            e = i + 1;
            while (e < n && s.charAt(e) != q && s.charAt(e) != '\n') {
                if (s.charAt(e) == '\\' && e + 1 < n && s.charAt(e + 1) != '\n') {
                    e++;
                }
                e++;
            }
            e = (e < n && s.charAt(e) == q) ? e + 1 : e;
        }
        span("s", from, e);
        return e;
    }

    // ---------------------------------------------------------------- JSON

    private void json() {
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int e = i + 1;
                while (e < n && s.charAt(e) != '"' && s.charAt(e) != '\n') {
                    if (s.charAt(e) == '\\') {
                        e++;
                    }
                    e++;
                }
                e = Math.min(e + 1, n);
                int k = e;
                while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
                    k++;
                }
                span(k < n && s.charAt(k) == ':' ? "a" : "s", i, e);
                i = e;
            } else if (c == '/' && i + 1 < n && (s.charAt(i + 1) == '/' || s.charAt(i + 1) == '*')) {
                int e;
                if (s.charAt(i + 1) == '/') {
                    e = lineEnd(i);
                } else {
                    int k = s.indexOf("*/", i + 2);
                    e = k < 0 ? n : k + 2;
                }
                span("c", i, e);
                i = e;
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int e = i + 1;
                while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '.' || s.charAt(e) == '+'
                        || s.charAt(e) == '-')) {
                    e++;
                }
                span("n", i, e);
                i = e;
            } else if (Character.isLetter(c)) {
                int e = i + 1;
                while (e < n && Character.isLetterOrDigit(s.charAt(e))) {
                    e++;
                }
                String w = s.substring(i, e);
                span(w.equals("true") || w.equals("false") || w.equals("null") ? "k" : null, i, e);
                i = e;
            } else {
                esc(s, i, i + 1);
                i++;
            }
        }
    }

    // ---------------------------------------------------------------- XML / HTML

    private void markup() {
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c != '<') {
                int e = s.indexOf('<', i);
                e = e < 0 ? n : e;
                esc(s, i, e);
                i = e;
                continue;
            }
            if (s.startsWith("<!--", i)) {
                int e = s.indexOf("-->", i + 4);
                e = e < 0 ? n : e + 3;
                span("c", i, e);
                i = e;
            } else if (s.startsWith("<![CDATA[", i)) {
                int e = s.indexOf("]]>", i);
                e = e < 0 ? n : e + 3;
                span("s", i, e);
                i = e;
            } else if (s.startsWith("<?", i) || s.startsWith("<!", i)) {
                int e = s.indexOf('>', i);
                e = e < 0 ? n : e + 1;
                span("p", i, e);
                i = e;
            } else {
                i = tag(i);
            }
        }
    }

    private int tag(int i) {
        int e = i + 1;
        boolean close = e < n && s.charAt(e) == '/';
        if (close) {
            e++;
        }
        int nameStart = e;
        while (e < n && !Character.isWhitespace(s.charAt(e)) && s.charAt(e) != '>' && s.charAt(e) != '/') {
            e++;
        }
        if (e == nameStart) {
            span(null, i, i + 1);
            return i + 1;
        }
        span("g", i, e);
        String name = s.substring(nameStart, e).toLowerCase(Locale.ROOT);
        // attributi
        while (e < n && s.charAt(e) != '>') {
            char c = s.charAt(e);
            if (Character.isWhitespace(c) || c == '/' || c == '=') {
                esc(s, e, e + 1);
                e++;
            } else if (c == '"' || c == '\'') {
                int k = s.indexOf(c, e + 1);
                k = k < 0 ? n : k + 1;
                span("s", e, k);
                e = k;
            } else if (c == '<') {
                return e; // tag non chiuso
            } else {
                int k = e;
                while (k < n && !Character.isWhitespace(s.charAt(k)) && s.charAt(k) != '=' && s.charAt(k) != '>'
                        && s.charAt(k) != '/') {
                    k++;
                }
                if (k == e) {
                    k = e + 1;
                }
                span("a", e, k);
                e = k;
            }
        }
        if (e < n) {
            int gt = e;
            span("g", gt, gt + 1);
            e++;
        }
        // contenuto di script / style in HTML
        if (l.kind == CodeLang.HTML && !close && (name.equals("script") || name.equals("style"))
                && !(e >= 2 && s.charAt(e - 2) == '/')) {
            int k = -1;
            String endTag = "</" + name;
            for (int j = e; j + endTag.length() <= n; j++) {
                if (s.regionMatches(true, j, endTag, 0, endTag.length())) {
                    k = j;
                    break;
                }
            }
            if (k < 0) {
                k = n;
            }
            if (k > e) {
                CodeLang inner = CodeLang.byName(name.equals("style") ? "CSS / SCSS" : "JavaScript");
                run(s.substring(e, k), inner, o);
            }
            e = k;
        }
        return e;
    }

    // ---------------------------------------------------------------- Markdown / YAML / INI

    private void markdown() {
        int i = 0;
        boolean fence = false;
        while (i < n) {
            int e = lineEnd(i);
            String ln = s.substring(i, e);
            String t = ln.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                fence = !fence;
                span("p", i, e);
            } else if (fence) {
                span("s", i, e);
            } else if (t.startsWith("#")) {
                span("k", i, e);
            } else if (t.startsWith(">")) {
                span("c", i, e);
            } else if (t.matches("^([-*+]|\\d+\\.)\\s.*")) {
                int p = ln.indexOf(t.charAt(0));
                span(null, i, i + p);
                int q = ln.indexOf(' ', p);
                span("n", i + p, i + q);
                inlineMd(i + q, e);
            } else if (t.matches("^[-*_=]{3,}$")) {
                span("c", i, e);
            } else {
                inlineMd(i, e);
            }
            if (e < n) {
                o.append('\n');
            }
            i = e + 1;
        }
    }

    private void inlineMd(int a, int b) {
        int i = a;
        while (i < b) {
            char c = s.charAt(i);
            if (c == '`') {
                int k = s.indexOf('`', i + 1);
                if (k > 0 && k < b) {
                    span("s", i, k + 1);
                    i = k + 1;
                    continue;
                }
            } else if (c == '[') {
                int k = s.indexOf("](", i);
                int m = k < 0 ? -1 : s.indexOf(')', k);
                if (k > 0 && k < b && m > 0 && m < b) {
                    span("f", i, k + 1);
                    span("a", k + 1, m + 1);
                    i = m + 1;
                    continue;
                }
            } else if (c == '*' || c == '_') {
                int len = i + 1 < b && s.charAt(i + 1) == c ? 2 : 1;
                int k = s.indexOf(String.valueOf(c).concat(len == 2 ? String.valueOf(c) : ""), i + len);
                if (k > 0 && k < b && k > i + len) {
                    span("t", i, k + len);
                    i = k + len;
                    continue;
                }
            }
            esc(s, i, i + 1);
            i++;
        }
    }

    private void lines() {
        int i = 0;
        boolean yaml = l.kind == CodeLang.YAML;
        while (i < n) {
            int e = lineEnd(i);
            String ln = s.substring(i, e);
            String t = ln.trim();
            if (t.isEmpty()) {
                // niente
            } else if (t.startsWith("#") || (!yaml && t.startsWith(";"))) {
                span("c", i, e);
            } else if (!yaml && t.startsWith("[")) {
                span("k", i, e);
            } else {
                int lead = ln.length() - ln.replaceAll("^\\s+", "").length();
                int p = i + lead;
                if (yaml && t.startsWith("- ")) {
                    span("n", p, p + 1);
                    p++;
                    while (p < e && s.charAt(p) == ' ') {
                        o.append(' ');
                        p++;
                    }
                }
                span(null, i, i + lead);
                int sep = -1;
                for (int k = p; k < e; k++) {
                    char c = s.charAt(k);
                    if (c == '"' || c == '\'') {
                        break;
                    }
                    if ((yaml && c == ':' && (k + 1 == e || s.charAt(k + 1) == ' ')) || (!yaml && (c == '=' || c == ':'))) {
                        sep = k;
                        break;
                    }
                }
                if (sep > p) {
                    span("a", p, sep);
                    span(null, sep, sep + 1);
                    valueRest(sep + 1, e);
                } else {
                    valueRest(p, e);
                }
            }
            if (e < n) {
                o.append('\n');
            }
            i = e + 1;
        }
    }

    private void valueRest(int a, int e) {
        int i = a;
        while (i < e) {
            char c = s.charAt(i);
            if (c == '#' && (i == a || s.charAt(i - 1) == ' ')) {
                span("c", i, e);
                return;
            }
            if (c == '"' || c == '\'') {
                int k = i + 1;
                while (k < e && s.charAt(k) != c) {
                    if (s.charAt(k) == '\\' && c == '"') {
                        k++;
                    }
                    k++;
                }
                k = Math.min(k + 1, e);
                span("s", i, k);
                i = k;
            } else if (Character.isLetterOrDigit(c) || c == '-' || c == '.') {
                int k = i + 1;
                while (k < e && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '.' || s.charAt(k) == '_'
                        || s.charAt(k) == '-')) {
                    k++;
                }
                String w = s.substring(i, k);
                String lw = w.toLowerCase(Locale.ROOT);
                String cls = null;
                if (w.matches("-?\\d+(\\.\\d+)?")) {
                    cls = "n";
                } else if (lw.equals("true") || lw.equals("false") || lw.equals("null") || lw.equals("yes")
                        || lw.equals("no") || lw.equals("~")) {
                    cls = "k";
                }
                span(cls, i, k);
                i = k;
            } else {
                esc(s, i, i + 1);
                i++;
            }
        }
    }
}
