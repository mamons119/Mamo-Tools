package it.mamotools;

/** Pagina HTML del lettore di codice (tema scuro, numeri di riga fissi a sinistra). Java puro. */
final class CodePage {

    private CodePage() {
    }

    static String build(String codeHtml, int lines) {
        StringBuilder g = new StringBuilder(lines * 5);
        for (int i = 1; i <= lines; i++) {
            g.append(i);
            if (i < lines) {
                g.append('\n');
            }
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "html,body{margin:0;background:#0F1115;color:#D7DBE3}"
                + "pre{margin:0;font:13px/1.55 ui-monospace,'Roboto Mono','DejaVu Sans Mono',monospace;tab-size:4;"
                + "-moz-tab-size:4}"
                + ".w{display:flex;min-width:max-content;padding:8px 0 40px}"
                + "pre.gut{position:sticky;left:0;z-index:2;background:#14171D;color:#566074;text-align:right;"
                + "padding:0 10px 0 12px;border-right:1px solid #262B35;user-select:none;-webkit-user-select:none}"
                + "pre.src{padding:0 16px;white-space:pre}"
                + "span.c{color:#6A9955;font-style:italic}span.s{color:#CE9178}span.n{color:#B5CEA8}"
                + "span.k{color:#569CD6}span.t{color:#4EC9B0}span.f{color:#DCDCAA}span.a{color:#9CDCFE}"
                + "span.v{color:#C586C0}span.p{color:#C586C0}span.g{color:#569CD6}"
                + "</style></head><body><div class=\"w\"><pre class=\"gut\">" + g + "</pre><pre class=\"src\">"
                + codeHtml + "</pre></div></body></html>";
    }
}
