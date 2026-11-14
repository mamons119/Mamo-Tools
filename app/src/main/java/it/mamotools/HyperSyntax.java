package it.mamotools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Colori e controlli di sintassi dell'editor Hyper Text. Riusa CodeHighlighter (che produce HTML con span) e ne
 * ricava gli intervalli di testo da colorare. I controlli sono volutamente prudenti: parentesi non chiuse o
 * sbagliate, stringhe non chiuse, virgole di troppo nel JSON ed errori di struttura nell'XML.
 */
final class HyperSyntax {

    private HyperSyntax() {
    }

    /** Intervallo colorato: classe c,s,n,k,t,f,a,v,p,g come in CodePage. */
    static final class Tok {
        final int a, b;
        final char cls;

        Tok(int a, int b, char cls) {
            this.a = a;
            this.b = b;
            this.cls = cls;
        }
    }

    static final class Err {
        final int a, b;
        final String msg;

        Err(int a, int b, String msg) {
            this.a = a;
            this.b = b;
            this.msg = msg;
        }
    }

    static int color(char cls) {
        switch (cls) {
            case 'c':
                return 0xFF6A9955;
            case 's':
                return 0xFFCE9178;
            case 'n':
                return 0xFFB5CEA8;
            case 'k':
            case 'g':
                return 0xFF569CD6;
            case 't':
                return 0xFF4EC9B0;
            case 'f':
                return 0xFFDCDCAA;
            case 'a':
                return 0xFF9CDCFE;
            case 'v':
            case 'p':
                return 0xFFC586C0;
            default:
                return 0xFFD7DBE3;
        }
    }

    /** Intervalli da colorare del testo (indici nel testo originale). */
    static List<Tok> tokens(String text, CodeLang l) {
        List<Tok> out = new ArrayList<>();
        if (l == null || l.name.equals("Testo semplice")) {
            return out;
        }
        String h = CodeHighlighter.highlight(text, l);
        int p = 0, n = h.length();
        int start = -1;
        char cls = 0;
        for (int i = 0; i < n; ) {
            char c = h.charAt(i);
            if (c == '<') {
                if (h.startsWith("<span class=\"", i)) {
                    int q = h.indexOf('"', i + 13);
                    cls = h.charAt(i + 13);
                    start = p;
                    i = h.indexOf('>', q) + 1;
                    continue;
                }
                if (h.startsWith("</span>", i)) {
                    if (start >= 0 && p > start) {
                        out.add(new Tok(start, p, cls));
                    }
                    start = -1;
                    i += 7;
                    continue;
                }
            }
            if (c == '&') {
                if (h.startsWith("&lt;", i) || h.startsWith("&gt;", i)) {
                    i += 4;
                    p++;
                    continue;
                }
                if (h.startsWith("&amp;", i)) {
                    i += 5;
                    p++;
                    continue;
                }
            }
            i++;
            p++;
        }
        return out;
    }

    private static final Set<String> BRACKET_LANGS = new HashSet<>(Arrays.asList("C", "C++", "Java", "Kotlin", "C#",
            "JavaScript", "TypeScript", "Python", "Go", "Rust", "Swift", "PHP", "Dart", "Scala", "Groovy / Gradle",
            "CSS / SCSS", "JSON", "SQL"));

    static boolean checks(CodeLang l) {
        return l != null && (BRACKET_LANGS.contains(l.name) || l.kind == CodeLang.XML);
    }

    /** Errori trovati (al massimo 50), in ordine di posizione. */
    static List<Err> check(String text, CodeLang l, List<Tok> toks) {
        List<Err> errs = new ArrayList<>();
        if (!checks(l) || text.length() > 400000) {
            return errs;
        }
        if (l.kind == CodeLang.XML) {
            xml(text, errs);
            return errs;
        }
        // zone da ignorare: commenti e stringhe
        int n = text.length();
        boolean[] skip = new boolean[n + 1];
        boolean json = l.kind == CodeLang.JSON;
        for (Tok t : toks) {
            boolean jkey = json && t.cls == 'a' && t.b > t.a && text.charAt(t.a) == '"';
            if (t.cls == 'c' || t.cls == 's' || jkey) {
                for (int i = t.a; i < t.b && i < n; i++) {
                    skip[i] = true;
                }
                if (t.cls == 's' && !json && t.b - t.a >= 1 && text.charAt(t.a) == '"'
                        && !text.startsWith("\"\"\"", t.a) && !l.triple) {
                    boolean closed = t.b - t.a >= 2 && text.charAt(t.b - 1) == '"' && !escapedAt(text, t.b - 1);
                    boolean nl = text.indexOf('\n', t.a) >= 0 && text.indexOf('\n', t.a) < t.b;
                    if (!closed && !nl && t.b < n + 1) {
                        errs.add(new Err(t.a, Math.max(t.a + 1, t.b), "Stringa non chiusa"));
                    }
                }
                if ((t.cls == 's' || jkey) && json && t.b - t.a >= 1 && text.charAt(t.a) == '"') {
                    boolean closed = t.b - t.a >= 2 && text.charAt(t.b - 1) == '"' && !escapedAt(text, t.b - 1);
                    if (!closed) {
                        errs.add(new Err(t.a, Math.max(t.a + 1, t.b), "Stringa non chiusa"));
                    }
                }
            }
        }
        int[] st = new int[256];
        int sp = 0;
        List<int[]> stack = new ArrayList<>();
        for (int i = 0; i < n && errs.size() < 50; i++) {
            if (skip[i]) {
                continue;
            }
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                stack.add(new int[]{c, i});
            } else if (c == ')' || c == ']' || c == '}') {
                char open = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (stack.isEmpty()) {
                    errs.add(new Err(i, i + 1, "'" + c + "' senza la sua apertura '" + open + "'"));
                } else {
                    int[] top = stack.get(stack.size() - 1);
                    if (top[0] == open) {
                        stack.remove(stack.size() - 1);
                    } else {
                        char want = top[0] == '(' ? ')' : top[0] == '[' ? ']' : '}';
                        errs.add(new Err(i, i + 1, "Trovato '" + c + "' ma serviva '" + want + "' per chiudere '"
                                + (char) top[0] + "' (riga " + line(text, top[1]) + ")"));
                        // riallinea: chiude l'apertura se la troviamo piu' in basso
                        for (int k = stack.size() - 1; k >= 0; k--) {
                            if (stack.get(k)[0] == open) {
                                while (stack.size() > k) {
                                    stack.remove(stack.size() - 1);
                                }
                                break;
                            }
                        }
                    }
                }
            } else if (json && c == ',') {
                int j = i + 1;
                while (j < n && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j < n && (text.charAt(j) == '}' || text.charAt(j) == ']')) {
                    errs.add(new Err(i, i + 1, "Virgola di troppo prima di '" + text.charAt(j) + "'"));
                }
            }
        }
        for (int[] o : stack) {
            if (errs.size() >= 50) {
                break;
            }
            char want = o[0] == '(' ? ')' : o[0] == '[' ? ']' : '}';
            errs.add(new Err(o[1], o[1] + 1, "'" + (char) o[0] + "' non chiusa: manca '" + want + "'"));
        }
        java.util.Collections.sort(errs, (x, y) -> Integer.compare(x.a, y.a));
        return errs;
    }

    private static boolean escapedAt(String s, int i) {
        int k = 0;
        for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) {
            k++;
        }
        return k % 2 == 1;
    }

    static int line(String s, int pos) {
        int ln = 1;
        for (int i = 0; i < pos && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                ln++;
            }
        }
        return ln;
    }

    private static void xml(String text, List<Err> errs) {
        try {
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(new java.io.StringReader(text));
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                // basta scorrere: gli errori arrivano come eccezione
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            int ln = Math.max(1, e.getLineNumber()), col = Math.max(1, e.getColumnNumber());
            int pos = 0, cur = 1;
            while (cur < ln && pos < text.length()) {
                int nl = text.indexOf('\n', pos);
                if (nl < 0) {
                    break;
                }
                pos = nl + 1;
                cur++;
            }
            pos = Math.min(text.length() - (text.isEmpty() ? 0 : 1), pos + col - 1);
            pos = Math.max(0, pos);
            String m = e.getMessage();
            if (m != null) {
                int k = m.indexOf(" (position");
                if (k > 0) {
                    m = m.substring(0, k);
                }
            }
            errs.add(new Err(pos, Math.min(text.length(), pos + 1), "XML non valido: " + (m == null ? "" : m)));
        } catch (Throwable ignored) {
        }
    }
}
