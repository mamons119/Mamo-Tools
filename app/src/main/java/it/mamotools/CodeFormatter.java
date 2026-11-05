package it.mamotools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Corregge l'indentazione del codice in base al linguaggio. Java puro, nessuna dipendenza Android. */
final class CodeFormatter {

    static final int MAX_CHARS = 2_000_000;

    private CodeFormatter() {
    }

    static String format(String text, CodeLang l) {
        if (text.length() > MAX_CHARS) {
            return text;
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try {
            switch (l.fmt) {
                case CodeLang.FMT_BRACE:
                    if (l.kind == CodeLang.CSS) {
                        text = splitCss(text);
                    }
                    return braces(text, l, l.kind == CodeLang.CSS || l.name.startsWith("Java") || l.name.equals("TypeScript")
                            ? 2 : 4);
                case CodeLang.FMT_JSON:
                    try {
                        return json(text);
                    } catch (RuntimeException e) {
                        return braces(text, l, 2);
                    }
                case CodeLang.FMT_XML:
                    return xml(text, false);
                case CodeLang.FMT_HTML:
                    return xml(text, true);
                case CodeLang.FMT_SHELL:
                    return shell(text);
                case CodeLang.FMT_INDENT:
                    return normalizeIndent(text, l.kind == CodeLang.YAML ? 2 : 4, l.kind != CodeLang.YAML);
                default:
                    return text;
            }
        } catch (RuntimeException e) {
            lastError = e;
            return text;
        }
    }

    static RuntimeException lastError;

    static boolean supported(CodeLang l) {
        return l.fmt != CodeLang.FMT_NONE;
    }

    private static String ind(int n, int unit) {
        char[] c = new char[n * unit];
        Arrays.fill(c, ' ');
        return new String(c);
    }

    // ---------------------------------------------------------------- linguaggi a graffe

    private static final Pattern ACCESS = Pattern.compile("^(public|private|protected)\\s*:(?!:).*");

    private static String braces(String text, CodeLang l, int unit) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 256);
        boolean inBlock = false, inRaw = false;
        String rawEnd = null;
        String blockBase = "";
        int depth = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String t = line.trim();
            String res;
            if (inRaw) {
                res = rtrim(line);
                int k = line.indexOf(rawEnd);
                if (k >= 0) {
                    inRaw = false;
                    // il resto della riga dopo la chiusura va comunque contato
                    int[] r = scan(line.substring(k + rawEnd.length()), l, new boolean[]{false, false}, null);
                    depth = Math.max(0, depth + r[0]);
                }
            } else if (inBlock) {
                int e = l.blockEnd == null ? -1 : line.indexOf(l.blockEnd);
                if (t.startsWith("*")) {
                    res = blockBase + " " + t;
                } else {
                    res = rtrim(line);
                }
                if (e >= 0) {
                    inBlock = false;
                    int[] r = scan(line.substring(e + l.blockEnd.length()), l, new boolean[]{false, false}, null);
                    depth = Math.max(0, depth + r[0]);
                }
            } else if (t.isEmpty()) {
                res = "";
            } else if (l.hashPre && t.startsWith("#")) {
                res = t;
            } else {
                boolean[] st = {false, false};
                String[] raw = {null};
                int[] r = scan(t, l, st, raw);
                int lead = Math.min(depth, r[1]);
                int d = Math.max(0, depth - lead);
                if (l.name.equals("C++") && ACCESS.matcher(t).matches()) {
                    d = Math.max(0, depth - 1);
                }
                String pad = ind(d, unit);
                res = pad + t;
                depth = Math.max(0, depth + r[0]);
                if (st[0]) {
                    inBlock = true;
                    blockBase = pad;
                }
                if (st[1]) {
                    inRaw = true;
                    rawEnd = raw[0];
                }
            }
            out.append(res);
            if (li < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Analizza una riga: ritorna {saldo parentesi, chiusure iniziali}. st[0] = resta aperto un commento a blocco,
     * st[1] = resta aperta una stringa multilinea (delimitatore in raw[0]).
     */
    private static int[] scan(String s, CodeLang l, boolean[] st, String[] raw) {
        int net = 0, lead = 0;
        boolean seenCode = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            // commenti di riga
            boolean lc = false;
            for (String c : l.lineC) {
                if (!c.isEmpty() && s.startsWith(c, i) && (!Character.isLetter(c.charAt(0)) || i == 0
                        || Character.isWhitespace(s.charAt(i - 1)))) {
                    lc = true;
                    break;
                }
            }
            if (lc) {
                break;
            }
            if (l.blockStart != null && s.startsWith(l.blockStart, i)) {
                int e = s.indexOf(l.blockEnd, i + l.blockStart.length());
                if (e < 0) {
                    st[0] = true;
                    break;
                }
                i = e + l.blockEnd.length() - 1;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                if (l.quotes.indexOf(ch) < 0 && !(ch == '`' && l.backtick)) {
                    seenCode = true;
                    continue;
                }
                if (l.triple && s.startsWith(rep(ch), i)) {
                    String d = rep(ch);
                    int e = s.indexOf(d, i + 3);
                    if (e < 0) {
                        st[1] = true;
                        if (raw != null) {
                            raw[0] = d;
                        }
                        seenCode = true;
                        break;
                    }
                    i = e + 2;
                    seenCode = true;
                    continue;
                }
                if (ch == '`' && l.backtick) {
                    int e = i + 1;
                    while (e < n && s.charAt(e) != '`') {
                        if (s.charAt(e) == '\\') {
                            e++;
                        }
                        e++;
                    }
                    if (e >= n) {
                        st[1] = true;
                        if (raw != null) {
                            raw[0] = "`";
                        }
                        seenCode = true;
                        break;
                    }
                    i = e;
                    seenCode = true;
                    continue;
                }
                int e = i + 1;
                while (e < n && s.charAt(e) != ch) {
                    if (s.charAt(e) == '\\') {
                        e++;
                    }
                    e++;
                }
                i = Math.min(e, n - 1);
                seenCode = true;
                continue;
            }
            if (ch == '{' || ch == '(' || ch == '[') {
                net++;
                seenCode = true;
            } else if (ch == '}' || ch == ')' || ch == ']') {
                net--;
                if (!seenCode) {
                    lead++;
                }
            } else {
                seenCode = true;
            }
        }
        return new int[]{net, lead};
    }

    private static String rep(char c) {
        return new String(new char[]{c, c, c});
    }

    private static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(0, e);
    }

    static String splitCss(String t) {
        t = t.trim();
        int lines = 1;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                lines++;
            }
        }
        if (lines > 1 && t.length() / lines < 120) {
            return t;
        }
        StringBuilder sb = new StringBuilder(t.length() + t.length() / 8);
        int paren = 0;
        char q = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            sb.append(c);
            if (q != 0) {
                if (c == '\\' && i + 1 < t.length()) {
                    sb.append(t.charAt(++i));
                } else if (c == q) {
                    q = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '*') {
                int e = t.indexOf("*/", i + 2);
                e = e < 0 ? t.length() : e + 2;
                sb.append(t, i + 1, e);
                i = e - 1;
                sb.append('\n');
            } else if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (c == '}') {
                sb.setLength(sb.length() - 1);
                while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.setLength(sb.length() - 1);
                }
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
                sb.append("}\n");
                int j = i + 1;
                while (j < t.length() && t.charAt(j) == ' ') {
                    j++;
                }
                i = j - 1;
            } else if ((c == '{' || (c == ';' && paren == 0))) {
                int j = i + 1;
                while (j < t.length() && t.charAt(j) == ' ') {
                    j++;
                }
                sb.append('\n');
                i = j - 1;
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- JSON

    private static String json(String s) {
        int n = s.length();
        int[] match = new int[n];
        Arrays.fill(match, -1);
        int[] stack = new int[n + 1];
        int sp = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i = endString(s, i);
            } else if (c == '/' ) {
                throw new IllegalStateException("commento");
            } else if (c == '{' || c == '[') {
                stack[sp++] = i;
            } else if (c == '}' || c == ']') {
                if (sp == 0) {
                    throw new IllegalStateException("bilanciamento");
                }
                int o = stack[--sp];
                if ((s.charAt(o) == '{') != (c == '}')) {
                    throw new IllegalStateException("tipo");
                }
                match[o] = i;
            }
        }
        if (sp != 0) {
            throw new IllegalStateException("bilanciamento");
        }
        StringBuilder out = new StringBuilder(n + n / 4);
        int depth = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '"') {
                int e = endString(s, i);
                out.append(s, i, e + 1);
                i = e;
            } else if (c == '{' || c == '[') {
                int m = match[i];
                String inner = s.substring(i + 1, m).trim();
                if (inner.isEmpty()) {
                    out.append(c).append(s.charAt(m));
                    i = m;
                } else if (c == '[' && inner.indexOf('{') < 0 && inner.indexOf('[') < 0 && inner.length() <= 80) {
                    out.append('[').append(compactList(inner)).append(']');
                    i = m;
                } else {
                    depth++;
                    out.append(c).append('\n').append(ind(depth, 2));
                }
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
                out.append('\n').append(ind(depth, 2)).append(c);
            } else if (c == ',') {
                out.append(",\n").append(ind(depth, 2));
            } else if (c == ':') {
                out.append(": ");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String compactList(String inner) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"') {
                int e = endString(inner, i);
                sb.append(inner, i, e + 1);
                i = e;
            } else if (Character.isWhitespace(c)) {
                continue;
            } else if (c == ',') {
                sb.append(", ");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int endString(String s, int i) {
        int n = s.length(), e = i + 1;
        while (e < n && s.charAt(e) != '"') {
            if (s.charAt(e) == '\\') {
                e++;
            }
            e++;
        }
        if (e >= n) {
            throw new IllegalStateException("stringa");
        }
        return e;
    }

    // ---------------------------------------------------------------- XML / HTML

    private static final Set<String> VOID = new HashSet<>(Arrays.asList("area", "base", "br", "col", "embed", "hr", "img",
            "input", "link", "meta", "param", "source", "track", "wbr", "!doctype"));
    private static final Set<String> INLINE = new HashSet<>(Arrays.asList("a", "b", "i", "u", "s", "em", "strong", "span",
            "code", "small", "sup", "sub", "label", "abbr", "font", "mark", "br", "kbd", "q", "cite", "big", "tt"));
    private static final Set<String> RAW = new HashSet<>(Arrays.asList("pre", "textarea", "script", "style"));

    private static final int T_TEXT = 0, T_OPEN = 1, T_CLOSE = 2, T_SELF = 3, T_OTHER = 4;

    private static final class Tok {
        int type;
        String s, name = "";
    }

    private static List<Tok> tokenize(String x, boolean html) {
        List<Tok> out = new ArrayList<>();
        int n = x.length(), i = 0;
        String rawName = null;
        while (i < n) {
            Tok t = new Tok();
            if (rawName != null) {
                // contenuto di script/style/pre: fino alla chiusura
                int e = indexOfIgnoreCase(x, "</" + rawName, i);
                if (e < 0) {
                    e = n;
                }
                if (e > i) {
                    t.type = T_TEXT;
                    t.s = x.substring(i, e);
                    t.name = "#raw";
                    out.add(t);
                }
                i = e;
                rawName = null;
                continue;
            }
            char c = x.charAt(i);
            if (c != '<') {
                int e = x.indexOf('<', i);
                if (e < 0) {
                    e = n;
                }
                t.type = T_TEXT;
                t.s = x.substring(i, e);
                i = e;
            } else if (x.startsWith("<!--", i)) {
                int e = x.indexOf("-->", i + 4);
                e = e < 0 ? n : e + 3;
                t.type = T_OTHER;
                t.s = x.substring(i, e);
                i = e;
            } else if (x.startsWith("<![CDATA[", i)) {
                int e = x.indexOf("]]>", i + 9);
                e = e < 0 ? n : e + 3;
                t.type = T_OTHER;
                t.s = x.substring(i, e);
                i = e;
            } else if (x.startsWith("<?", i)) {
                int e = x.indexOf("?>", i + 2);
                e = e < 0 ? n : e + 2;
                t.type = T_OTHER;
                t.s = x.substring(i, e);
                i = e;
            } else {
                int e = i + 1;
                char q = 0;
                while (e < n) {
                    char d = x.charAt(e);
                    if (q != 0) {
                        if (d == q) {
                            q = 0;
                        }
                    } else if (d == '"' || d == '\'') {
                        q = d;
                    } else if (d == '>') {
                        break;
                    } else if (d == '<') {
                        e--;
                        break;
                    }
                    e++;
                }
                e = Math.min(e + 1, n);
                t.s = x.substring(i, e);
                i = e;
                if (t.s.startsWith("<!")) {
                    t.type = T_OTHER;
                } else {
                    boolean close = t.s.startsWith("</");
                    int a = close ? 2 : 1, b = a;
                    while (b < t.s.length() && !Character.isWhitespace(t.s.charAt(b)) && t.s.charAt(b) != '>'
                            && t.s.charAt(b) != '/') {
                        b++;
                    }
                    t.name = html ? t.s.substring(a, b).toLowerCase(Locale.ROOT) : t.s.substring(a, b);
                    if (close) {
                        t.type = T_CLOSE;
                    } else if (t.s.endsWith("/>") || (html && VOID.contains(t.name))) {
                        t.type = T_SELF;
                    } else {
                        t.type = T_OPEN;
                        if (html && RAW.contains(t.name)) {
                            rawName = t.name;
                        }
                    }
                }
            }
            out.add(t);
        }
        return out;
    }

    private static int indexOfIgnoreCase(String s, String what, int from) {
        int n = s.length() - what.length();
        for (int i = from; i <= n; i++) {
            if (s.regionMatches(true, i, what, 0, what.length())) {
                return i;
            }
        }
        return -1;
    }

    private static void emitTag(StringBuilder out, String tag, int depth) {
        String[] ls = tag.split("\n");
        for (int i = 0; i < ls.length; i++) {
            String ln = ls[i].trim();
            if (ln.isEmpty()) {
                continue;
            }
            out.append(ind(depth + (i == 0 ? 0 : 1), 2)).append(ln).append('\n');
        }
    }

    private static String xml(String x, boolean html) {
        List<Tok> toks = tokenize(x, html);
        StringBuilder out = new StringBuilder(x.length() + x.length() / 4);
        List<String> stack = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            int depth = stack.size();
            switch (t.type) {
                case T_TEXT: {
                    if (t.name.equals("#raw")) {
                        break; // gestito dall'apertura
                    }
                    String[] ls = t.s.split("\n");
                    for (String ln : ls) {
                        String tt = ln.trim();
                        if (!tt.isEmpty()) {
                            out.append(ind(depth, 2)).append(tt).append('\n');
                        }
                    }
                    break;
                }
                case T_OPEN: {
                    String one = tryInline(toks, i, html);
                    if (one != null && one.indexOf('\n') < 0 && depth * 2 + one.length() <= 110) {
                        out.append(ind(depth, 2)).append(one).append('\n');
                        i = inlineEnd;
                        break;
                    }
                    emitTag(out, t.s, depth);
                    if (html && RAW.contains(t.name)) {
                        Tok next = i + 1 < toks.size() ? toks.get(i + 1) : null;
                        if (next != null && next.type == T_TEXT && next.name.equals("#raw")) {
                            String body = next.s;
                            if (t.name.equals("pre") || t.name.equals("textarea")) {
                                out.append(body);
                            } else {
                                String blk = body.trim().isEmpty() ? "" : dedentBlock(body);
                                if (t.name.equals("style")) {
                                    blk = splitCss(blk);
                                }
                                String f = braces(blk,
                                        CodeLang.byName(t.name.equals("style") ? "CSS / SCSS" : "JavaScript"), 2);
                                for (String ln : f.split("\n")) {
                                    if (!ln.trim().isEmpty()) {
                                        out.append(ind(depth + 1, 2)).append(ln).append('\n');
                                    }
                                }
                            }
                            i++;
                        }
                        if (t.name.equals("pre") || t.name.equals("textarea")) {
                            // chiusura sulla stessa riga del contenuto
                            Tok cl = i + 1 < toks.size() ? toks.get(i + 1) : null;
                            if (cl != null && cl.type == T_CLOSE) {
                                if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                                    out.setLength(out.length() - 1);
                                }
                                out.append(cl.s.trim()).append('\n');
                                i++;
                            }
                        } else {
                            Tok cl = i + 1 < toks.size() ? toks.get(i + 1) : null;
                            if (cl != null && cl.type == T_CLOSE) {
                                out.append(ind(depth, 2)).append(cl.s.trim()).append('\n');
                                i++;
                            }
                        }
                        break;
                    }
                    stack.add(t.name);
                    break;
                }
                case T_CLOSE: {
                    int k = stack.lastIndexOf(t.name);
                    if (k >= 0) {
                        while (stack.size() > k) {
                            stack.remove(stack.size() - 1);
                        }
                    }
                    emitTag(out, t.s, stack.size());
                    break;
                }
                default:
                    emitTag(out, t.s, depth);
            }
        }
        return out.toString().replaceAll("\n+$", "\n");
    }

    private static int inlineEnd;

    /** Se un elemento contiene solo testo/elementi inline lo rende su una riga sola. */
    private static String tryInline(List<Tok> toks, int i, boolean html) {
        Tok o = toks.get(i);
        if (o.s.indexOf('\n') >= 0 || (html && RAW.contains(o.name))) {
            return null;
        }
        StringBuilder sb = new StringBuilder(o.s);
        int open = 1;
        for (int j = i + 1; j < toks.size() && j < i + 40; j++) {
            Tok t = toks.get(j);
            switch (t.type) {
                case T_TEXT:
                    if (t.name.equals("#raw")) {
                        return null;
                    }
                    sb.append(t.s.replaceAll("\\s+", " "));
                    break;
                case T_SELF:
                    if (!html || !INLINE.contains(t.name) || t.s.indexOf('\n') >= 0) {
                        return null;
                    }
                    sb.append(t.s);
                    break;
                case T_OPEN:
                    if (!html || !INLINE.contains(t.name) || t.s.indexOf('\n') >= 0) {
                        return null;
                    }
                    open++;
                    sb.append(t.s);
                    break;
                case T_CLOSE:
                    sb.append(t.s.trim());
                    open--;
                    if (open == 0) {
                        if (!t.name.equals(o.name)) {
                            return null;
                        }
                        inlineEnd = j;
                        String r = sb.toString().replaceAll(">\\s+", ">").replaceAll("\\s+<", "<");
                        // niente spazi persi tra testo e tag inline: si conserva l'originale per l'html
                        return html ? sb.toString().trim() : r;
                    }
                    break;
                default:
                    return null;
            }
        }
        return null;
    }

    private static String dedentBlock(String b) {
        String[] ls = b.replace("\t", "    ").split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (String l : ls) {
            if (!l.trim().isEmpty()) {
                int k = 0;
                while (k < l.length() && l.charAt(k) == ' ') {
                    k++;
                }
                min = Math.min(min, k);
            }
        }
        if (min == Integer.MAX_VALUE) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : ls) {
            sb.append(l.length() >= min ? l.substring(min) : l.trim()).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Shell

    private static final Pattern CASE_LABEL = Pattern.compile("^\\(?[^\\s()]+(\\|[^\\s()]+)*\\)(\\s.*)?$");
    private static final Pattern HEREDOC = Pattern.compile("<<-?\\s*['\"]?([A-Za-z_][A-Za-z0-9_]*)['\"]?");

    private static String shell(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 128);
        int depth = 0;
        boolean cont = false;
        String heredoc = null;
        List<Integer> caseLevels = new ArrayList<>();
        boolean inCaseBody = false;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String t = line.trim();
            String res;
            if (heredoc != null) {
                res = rtrim(line);
                if (t.equals(heredoc)) {
                    heredoc = null;
                }
            } else if (t.isEmpty()) {
                res = "";
                cont = false;
            } else if (t.startsWith("#")) {
                res = ind(depth + (cont ? 1 : 0), 4) + t;
            } else {
                String code = stripShell(t);
                String[] fw = code.split("[\\s;]+");
                String first = code.startsWith(";;") ? ";;" : (fw.length == 0 ? "" : fw[0]);
                int d = depth;
                int net = 0;
                boolean label = false;
                if (!caseLevels.isEmpty() && CASE_LABEL.matcher(code).matches() && !first.equals("case")
                        && !code.startsWith("esac")) {
                    label = true;
                }
                if (first.equals("fi") || first.equals("done") || first.equals("esac")) {
                    if (first.equals("esac") && !caseLevels.isEmpty()) {
                        depth = caseLevels.remove(caseLevels.size() - 1);
                        d = depth;
                        inCaseBody = false;
                    } else {
                        depth = Math.max(0, depth - 1);
                        d = depth;
                    }
                } else if (first.equals("else") || first.equals("elif")) {
                    d = Math.max(0, depth - 1);
                } else if (first.equals("then") || first.equals("do")) {
                    d = Math.max(0, depth - 1);
                } else if (label) {
                    if (inCaseBody) {
                        depth = Math.max(0, depth - 1);
                    }
                    d = depth;
                    net = 1;
                    inCaseBody = true;
                } else if (first.startsWith("}")) {
                    depth = Math.max(0, depth - 1);
                    d = depth;
                }
                res = ind(d + (cont ? 1 : 0), 4) + t;
                if (first.equals("if") || first.equals("for") || first.equals("while") || first.equals("until")
                        || first.equals("select")) {
                    net += 1;
                } else if (first.equals("case")) {
                    caseLevels.add(depth);
                    net += 1;
                    inCaseBody = false;
                } else if (!label && !first.equals("fi") && !first.equals("done") && !first.equals("esac")) {
                    net += braceNet(code, first);
                }
                if (label && code.contains(";;")) {
                    net = 0;
                    inCaseBody = false;
                } else if (!label && first.equals(";;") ) {
                    depth = Math.max(0, depth - 1);
                    res = ind(depth + 1, 4) + t;
                    inCaseBody = false;
                    net = 0;
                } else if (!label && code.endsWith(";;") && inCaseBody) {
                    depth = Math.max(0, depth - 1);
                    inCaseBody = false;
                }
                depth = Math.max(0, depth + net);
                cont = code.endsWith("\\");
                Matcher m = HEREDOC.matcher(code);
                if (m.find()) {
                    heredoc = m.group(1);
                }
            }
            out.append(res);
            if (li < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static int braceNet(String code, String first) {
        String c = code.replaceAll("\\$\\{[^}]*\\}", "");
        int o = 0, k = 0;
        for (int i = 0; i < c.length(); i++) {
            if (c.charAt(i) == '{') {
                o++;
            } else if (c.charAt(i) == '}') {
                k++;
            }
        }
        // la chiusura iniziale e' gia' stata applicata
        if (first.startsWith("}")) {
            k--;
        }
        return o - k;
    }

    /** Toglie stringhe e commenti da una riga di shell per riconoscere le parole chiave. */
    private static String stripShell(String t) {
        StringBuilder sb = new StringBuilder();
        char q = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (q != 0) {
                if (c == '\\' && q == '"') {
                    i++;
                } else if (c == q) {
                    q = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                q = c;
                sb.append("''");
            } else if (c == '#' && (i == 0 || Character.isWhitespace(t.charAt(i - 1)))) {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- Python / YAML

    private static String normalizeIndent(String text, int unit, boolean tripleStrings) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        List<Integer> stack = new ArrayList<>();
        stack.add(0);
        int brackets = 0, delta = 0;
        String tripleD = null;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li].replace("\t", "    ");
            String t = line.trim();
            String res;
            if (tripleD != null) {
                res = rtrim(line);
                if (line.contains(tripleD)) {
                    tripleD = null;
                }
            } else if (t.isEmpty()) {
                res = "";
            } else if (brackets > 0) {
                int w0 = 0;
                while (w0 < line.length() && line.charAt(w0) == ' ') {
                    w0++;
                }
                res = ind(1, Math.max(0, w0 + delta)).substring(0, Math.max(0, w0 + delta)) + t;
            } else {
                int w = 0;
                while (w < line.length() && line.charAt(w) == ' ') {
                    w++;
                }
                if (t.startsWith("#") && !tripleStrings) {
                    // commenti YAML: seguono il livello corrente
                    int lvl = levelFor(stack, w);
                    res = ind(lvl, unit) + t;
                } else {
                    int lvl = levelFor(stack, w);
                    res = ind(lvl, unit) + t;
                    delta = lvl * unit - w;
                    // aggiorna lo stack con la riga reale
                    while (stack.size() > 1 && stack.get(stack.size() - 1) > w) {
                        stack.remove(stack.size() - 1);
                    }
                    if (stack.get(stack.size() - 1) < w) {
                        stack.add(w);
                    }
                }
            }
            if (tripleD == null && !t.startsWith("#")) {
                int[] b = bracketScan(lines[li], tripleStrings);
                brackets = Math.max(0, brackets + b[0]);
                if (b[1] == 1) {
                    tripleD = "\"\"\"";
                } else if (b[1] == 2) {
                    tripleD = "'''";
                }
            }
            out.append(res);
            if (li < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static int levelFor(List<Integer> stack, int w) {
        int lvl = 0;
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i) <= w) {
                lvl = i;
            }
        }
        if (stack.get(stack.size() - 1) < w) {
            lvl = stack.size();
        }
        return lvl;
    }

    private static int[] bracketScan(String s, boolean py) {
        int net = 0, tri = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '#' && py) {
                break;
            }
            if (c == '"' || c == '\'') {
                if (py && s.startsWith(rep(c), i)) {
                    int e = s.indexOf(rep(c), i + 3);
                    if (e < 0) {
                        tri = c == '"' ? 1 : 2;
                        break;
                    }
                    i = e + 2;
                    continue;
                }
                int e = i + 1;
                while (e < s.length() && s.charAt(e) != c) {
                    if (s.charAt(e) == '\\') {
                        e++;
                    }
                    e++;
                }
                i = e;
            } else if (py && (c == '(' || c == '[' || c == '{')) {
                net++;
            } else if (py && (c == ')' || c == ']' || c == '}')) {
                net--;
            }
        }
        return new int[]{net, tri};
    }
}
