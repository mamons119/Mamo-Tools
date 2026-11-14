package it.mamotools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convertitore Markdown -> HTML minimale (titoli, elenchi, codice, citazioni, tabelle, link, immagini, enfasi). */
final class MdRender {

    private MdRender() {
    }

    static final String CSS = "body{background:#0F1115;color:#ECEFF4;font-family:sans-serif;line-height:1.6;"
            + "padding:16px;margin:0;word-wrap:break-word}h1,h2{border-bottom:1px solid #262B35;padding-bottom:.25em}"
            + "a{color:#4DD0E1}code{background:#1A1D24;color:#CE9178;padding:1px 5px;border-radius:4px;"
            + "font-family:monospace;font-size:.92em}pre{background:#1A1D24;padding:12px;border-radius:8px;"
            + "overflow-x:auto}pre code{background:none;color:#D7DBE3;padding:0}blockquote{border-left:4px solid "
            + "#4DD0E1;margin:8px 0;padding:2px 14px;color:#9AA3B2}img{max-width:100%}hr{border:0;"
            + "border-top:1px solid #262B35}table{border-collapse:collapse}th,td{border:1px solid #262B35;"
            + "padding:6px 10px}th{background:#1A1D24}";

    static String page(String md) {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>" + CSS
                + "</style></head><body>" + render(md) + "</body></html>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern IMG = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern ITAL = Pattern.compile("(?<![\\w*])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\w*])");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");

    static String inline(String s) {
        s = esc(s);
        List<String> codes = new ArrayList<>();
        Matcher m = CODE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            codes.add("<code>" + m.group(1) + "</code>");
            m.appendReplacement(sb, "\u0001" + (codes.size() - 1) + "\u0002");
        }
        m.appendTail(sb);
        s = sb.toString();
        s = IMG.matcher(s).replaceAll("<img alt=\"$1\" src=\"$2\">");
        s = LINK.matcher(s).replaceAll("<a href=\"$2\">$1</a>");
        s = BOLD.matcher(s).replaceAll("<b>$2</b>");
        s = ITAL.matcher(s).replaceAll("<i>$2</i>");
        s = STRIKE.matcher(s).replaceAll("<s>$1</s>");
        for (int i = 0; i < codes.size(); i++) {
            s = s.replace("\u0001" + i + "\u0002", codes.get(i));
        }
        return s;
    }

    static String render(String md) {
        String[] ls = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder o = new StringBuilder();
        List<String> para = new ArrayList<>();
        String list = null; // "ul" o "ol"
        int i = 0;
        while (i < ls.length) {
            String l = ls[i];
            String t = l.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                para = flush(o, para);
                list = closeList(o, list);
                String fence = t.substring(0, 3);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < ls.length && !ls[i].trim().startsWith(fence)) {
                    code.append(esc(ls[i])).append('\n');
                    i++;
                }
                o.append("<pre><code>").append(code).append("</code></pre>");
                i++;
                continue;
            }
            if (t.isEmpty()) {
                para = flush(o, para);
                list = closeList(o, list);
                i++;
                continue;
            }
            if (t.matches("^#{1,6}\\s+.*")) {
                para = flush(o, para);
                list = closeList(o, list);
                int n = 0;
                while (n < t.length() && t.charAt(n) == '#') {
                    n++;
                }
                o.append("<h").append(n).append('>').append(inline(t.substring(n).trim().replaceAll("\\s#+$", "")))
                        .append("</h").append(n).append('>');
                i++;
                continue;
            }
            if (t.matches("^([-*_])(\\s*\\1){2,}$")) {
                para = flush(o, para);
                list = closeList(o, list);
                o.append("<hr>");
                i++;
                continue;
            }
            if (t.startsWith(">")) {
                para = flush(o, para);
                list = closeList(o, list);
                StringBuilder q = new StringBuilder();
                while (i < ls.length && ls[i].trim().startsWith(">")) {
                    q.append(ls[i].trim().replaceFirst("^>\\s?", "")).append('\n');
                    i++;
                }
                o.append("<blockquote>").append(render(q.toString())).append("</blockquote>");
                continue;
            }
            if (t.contains("|") && i + 1 < ls.length && ls[i + 1].trim().matches("^\\|?\\s*:?-{2,}.*\\|.*$")) {
                para = flush(o, para);
                list = closeList(o, list);
                o.append("<table><tr>");
                for (String c : cells(t)) {
                    o.append("<th>").append(inline(c)).append("</th>");
                }
                o.append("</tr>");
                i += 2;
                while (i < ls.length && ls[i].contains("|") && !ls[i].trim().isEmpty()) {
                    o.append("<tr>");
                    for (String c : cells(ls[i].trim())) {
                        o.append("<td>").append(inline(c)).append("</td>");
                    }
                    o.append("</tr>");
                    i++;
                }
                o.append("</table>");
                continue;
            }
            Matcher ul = Pattern.compile("^\\s*[-*+]\\s+(.*)$").matcher(l);
            Matcher ol = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$").matcher(l);
            boolean isUl = ul.matches(), isOl = !isUl && ol.matches();
            if (isUl || isOl) {
                para = flush(o, para);
                String kind = isUl ? "ul" : "ol";
                if (list != null && !list.equals(kind)) {
                    list = closeList(o, list);
                }
                if (list == null) {
                    o.append('<').append(kind).append('>');
                    list = kind;
                }
                String item = isUl ? ul.group(1) : ol.group(1);
                if (item.startsWith("[ ] ")) {
                    item = "☐ " + item.substring(4);
                } else if (item.startsWith("[x] ") || item.startsWith("[X] ")) {
                    item = "☑ " + item.substring(4);
                }
                o.append("<li>").append(inline(item)).append("</li>");
                i++;
                continue;
            }
            list = closeList(o, list);
            para.add(t);
            i++;
        }
        flush(o, para);
        closeList(o, list);
        return o.toString();
    }

    private static List<String> cells(String row) {
        if (row.startsWith("|")) {
            row = row.substring(1);
        }
        if (row.endsWith("|")) {
            row = row.substring(0, row.length() - 1);
        }
        List<String> c = new ArrayList<>();
        for (String s : row.split("\\|", -1)) {
            c.add(s.trim());
        }
        return c;
    }

    private static List<String> flush(StringBuilder o, List<String> para) {
        if (!para.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < para.size(); k++) {
                if (k > 0) {
                    sb.append(para.get(k - 1).endsWith("  ") ? "<br>" : " ");
                }
                sb.append(inline(para.get(k)));
            }
            o.append("<p>").append(sb).append("</p>");
        }
        return new ArrayList<>();
    }

    private static String closeList(StringBuilder o, String list) {
        if (list != null) {
            o.append("</").append(list).append('>');
        }
        return null;
    }
}
