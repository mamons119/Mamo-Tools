package it.mamotools;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Unisci, dividi, estrai, elimina e ruota pagine di PDF (modo scelto con l'extra "mode"). */
public class PdfOrganizeActivity extends Activity {

    private static final int REQ_PICK = 41;
    private static final String MIME = "application/pdf";
    private static final String DIR = "Download/MamoTools";

    private static final class Item {
        File file;
        String name;
        long size;
        int pages;
    }

    private String mode;
    private final List<Item> items = new ArrayList<>();
    private int counter = 0;
    private volatile boolean busy = false;

    private LinearLayout listBox;
    private Ui.PickCard pick;
    private EditText rangeEt, nEt;
    private TextView hint;
    private boolean splitEach = true;
    private int rotateDeg = 90;
    private TextView chipEach, chipN, chipR90, chipR180, chipR270;
    private LinearLayout nRow;
    private Button run;
    private ResultCard result;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String m = getIntent().getStringExtra("mode");
        mode = m == null ? "merge" : m;

        String title, sub;
        switch (mode) {
            case "split":
                title = "Dividi PDF";
                sub = "Spezza un PDF in piu' file.";
                break;
            case "extract":
                title = "Estrai pagine";
                sub = "Crea un nuovo PDF con le sole pagine che scegli.";
                break;
            case "delete":
                title = "Elimina pagine";
                sub = "Salva una copia del PDF senza le pagine indicate.";
                break;
            case "rotate":
                title = "Ruota pagine";
                sub = "Salva una copia con le pagine ruotate.";
                break;
            default:
                title = "Unisci PDF";
                sub = "Aggiungi due o piu' PDF e ordinali: il risultato segue l'ordine della lista.";
                break;
        }
        LinearLayout root = Ui.screen(this, title, sub);

        if (mode.equals("merge")) {
            listBox = new LinearLayout(this);
            listBox.setOrientation(LinearLayout.VERTICAL);
            root.addView(listBox);
            root.addView(Ui.btn(this, "＋  Aggiungi PDF", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickFiles();
                }
            }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 50), 0, 8));
        } else {
            pick = new Ui.PickCard(this, "📕", "Scegli un PDF", "Tocca per selezionare il file",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pickFiles();
                        }
                    });
            root.addView(pick.view);
        }

        LinearLayout opts = null;
        if (!mode.equals("merge")) {
            opts = Ui.card(this);
            root.addView(opts, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }

        if (mode.equals("split")) {
            opts.addView(Ui.section(this, "Come dividere"));
            LinearLayout row = new LinearLayout(this);
            chipEach = Ui.chip(this, "Una pagina per file");
            chipN = Ui.chip(this, "A gruppi di N pagine");
            row.addView(chipEach, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
            row.addView(chipN, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
            opts.addView(row);
            nRow = new LinearLayout(this);
            nRow.setGravity(Gravity.CENTER_VERTICAL);
            nRow.addView(Ui.text(this, "Pagine per file:", 14, Ui.DIM, false));
            nEt = field("5", "5");
            nEt.setInputType(InputType.TYPE_CLASS_NUMBER);
            nRow.addView(nEt, Ui.lp(this, Ui.dp(this, 80), Ui.WRAP, 12, 0));
            nRow.setVisibility(View.GONE);
            opts.addView(nRow, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            chipEach.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    splitEach = true;
                    styleSplit();
                }
            });
            chipN.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    splitEach = false;
                    styleSplit();
                }
            });
            styleSplit();
        } else if (!mode.equals("merge")) {
            opts.addView(Ui.section(this, mode.equals("extract") ? "Pagine da estrarre"
                    : mode.equals("delete") ? "Pagine da eliminare" : "Pagine da ruotare"));
            rangeEt = field(mode.equals("rotate") ? "vuoto = tutte" : "es. 1-3, 5, 8-", "");
            opts.addView(rangeEt);
            hint = Ui.text(this, "Usa numeri e intervalli separati da virgole. \"8-\" significa "
                    + "dalla pagina 8 alla fine.", 12, Ui.DIM, false);
            hint.setPadding(0, Ui.dp(this, 6), 0, 0);
            opts.addView(hint);
            if (mode.equals("rotate")) {
                opts.addView(Ui.text(this, "Rotazione (in senso orario)", 14, Ui.DIM, false),
                        Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
                LinearLayout row = new LinearLayout(this);
                chipR90 = Ui.chip(this, "90°");
                chipR180 = Ui.chip(this, "180°");
                chipR270 = Ui.chip(this, "270°");
                row.addView(chipR90, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
                row.addView(chipR180, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
                row.addView(chipR270, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
                opts.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
                chipR90.setOnClickListener(rot(90));
                chipR180.setOnClickListener(rot(180));
                chipR270.setOnClickListener(rot(270));
                styleRot();
            }
        }

        run = Ui.btn(this, actionLabel(), Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        });
        run.setTextSize(16);
        root.addView(run, Ui.lp(this, Ui.MATCH, Ui.dp(this, 54), 0, 16));
        result = new ResultCard(this);
        root.addView(result.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
        refresh();
    }

    private String actionLabel() {
        switch (mode) {
            case "split":
                return "Dividi";
            case "extract":
                return "Estrai pagine";
            case "delete":
                return "Elimina pagine";
            case "rotate":
                return "Ruota";
            default:
                return "Unisci";
        }
    }

    private View.OnClickListener rot(final int d) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateDeg = d;
                styleRot();
            }
        };
    }

    private void styleRot() {
        Ui.styleChip(this, chipR90, rotateDeg == 90);
        Ui.styleChip(this, chipR180, rotateDeg == 180);
        Ui.styleChip(this, chipR270, rotateDeg == 270);
    }

    private void styleSplit() {
        Ui.styleChip(this, chipEach, splitEach);
        Ui.styleChip(this, chipN, !splitEach);
        nRow.setVisibility(splitEach ? View.GONE : View.VISIBLE);
    }

    private EditText field(String hintText, String value) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setHintTextColor(Ui.DIM);
        e.setTextColor(Ui.TEXT);
        e.setText(value);
        e.setSingleLine(true);
        e.setBackground(Ui.round(this, Ui.BG, 10));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        return e;
    }

    // ------------------------------------------------------------------ scelta file

    private void pickFiles() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(MIME);
        if (mode.equals("merge")) {
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) {
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                uris.add(cd.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        busy = true;
        result.start("Lettura dei file...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> added = new ArrayList<>();
                String err = null;
                for (Uri u : uris) {
                    String name = FileUtil.queryName(PdfOrganizeActivity.this, u);
                    try {
                        File f = new File(getCacheDir(), "sel_" + (counter++) + ".pdf");
                        FileUtil.copyToFile(PdfOrganizeActivity.this, u, f);
                        Item it = new Item();
                        it.file = f;
                        it.name = name;
                        it.size = f.length();
                        it.pages = countPages(f);
                        added.add(it);
                    } catch (SecurityException se) {
                        err = "\"" + name + "\" e' protetto da password: non posso usarlo.";
                    } catch (Exception e) {
                        err = "\"" + name + "\" non sembra un PDF valido.";
                    }
                }
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (mode.equals("merge")) {
                            items.addAll(added);
                        } else if (!added.isEmpty()) {
                            clearItems();
                            items.add(added.get(0));
                        }
                        refresh();
                        if (fErr != null) {
                            result.error(fErr);
                        } else {
                            result.hide();
                        }
                    }
                });
            }
        }).start();
    }

    private static int countPages(File f) throws IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer r = new PdfRenderer(pfd);
            int n = r.getPageCount();
            r.close();
            return n;
        } finally {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void clearItems() {
        for (Item it : items) {
            //noinspection ResultOfMethodCallIgnored
            it.file.delete();
        }
        items.clear();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!busy) {
            clearItems();
        }
    }

    // ------------------------------------------------------------------ elenco

    private void refresh() {
        if (mode.equals("merge")) {
            listBox.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                listBox.addView(row(i));
            }
            if (items.isEmpty()) {
                TextView e = Ui.text(this, "Nessun file aggiunto", 14, Ui.DIM, false);
                e.setGravity(Gravity.CENTER);
                e.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 14));
                listBox.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            }
            run.setEnabled(items.size() >= 2);
        } else {
            if (items.isEmpty()) {
                pick.title.setText("Scegli un PDF");
                pick.sub.setText("Tocca per selezionare il file");
                run.setEnabled(false);
            } else {
                Item it = items.get(0);
                pick.title.setText(it.name);
                pick.sub.setText(it.pages + (it.pages == 1 ? " pagina · " : " pagine · ")
                        + FileUtil.humanSize(it.size));
                run.setEnabled(true);
            }
        }
    }

    private View row(final int idx) {
        Item it = items.get(idx);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        r.setBackground(Ui.round(this, Ui.CARD, 14));
        TextView num = Ui.text(this, String.valueOf(idx + 1), 14, 0xFF000000, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.round(this, Ui.ACCENT, 100));
        r.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        TextView n = Ui.text(this, it.name, 15, Ui.TEXT, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(n);
        col.addView(Ui.text(this, it.pages + " pag. · " + FileUtil.humanSize(it.size), 12, Ui.DIM, false));
        r.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        r.addView(small("▲", idx > 0, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swap(idx, idx - 1);
            }
        }));
        r.addView(small("▼", idx < items.size() - 1, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swap(idx, idx + 1);
            }
        }));
        r.addView(small("✕", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                Item gone = items.remove(idx);
                //noinspection ResultOfMethodCallIgnored
                gone.file.delete();
                refresh();
            }
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 8);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView small(String s, boolean enabled, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 16, enabled ? Ui.TEXT : Ui.CARD2, false);
        t.setGravity(Gravity.CENTER);
        if (enabled) {
            t.setOnClickListener(l);
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        return t;
    }

    private void swap(int a, int b) {
        if (busy) {
            return;
        }
        Item t = items.get(a);
        items.set(a, items.get(b));
        items.set(b, t);
        refresh();
    }

    // ------------------------------------------------------------------ esecuzione

    /** "1-3,5,8-" -> indici 0-based ordinati; stringa vuota -> null (= tutte). */
    static int[] parseRanges(String s, int total) {
        String t = s.replace(" ", "").replace(";", ",");
        if (t.isEmpty()) {
            return null;
        }
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : t.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                int a, b;
                int dash = part.indexOf('-');
                if (dash < 0) {
                    a = b = Integer.parseInt(part);
                } else {
                    String l = part.substring(0, dash), r = part.substring(dash + 1);
                    a = l.isEmpty() ? 1 : Integer.parseInt(l);
                    b = r.isEmpty() ? total : Integer.parseInt(r);
                }
                if (a < 1 || b < 1 || a > total || b > total) {
                    throw new IllegalArgumentException("Le pagine vanno da 1 a " + total + ".");
                }
                if (a > b) {
                    int x = a;
                    a = b;
                    b = x;
                }
                for (int p = a; p <= b; p++) {
                    set.add(p - 1);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Non capisco \"" + part
                        + "\". Scrivi ad esempio 1-3, 5, 8-");
            }
        }
        if (set.isEmpty()) {
            return null;
        }
        int[] out = new int[set.size()];
        int k = 0;
        for (int v : set) {
            out[k++] = v;
        }
        return out;
    }

    private void start() {
        if (busy || items.isEmpty()) {
            return;
        }
        final Item first = items.get(0);
        int[] sel = null;
        int n = 0;
        try {
            if (rangeEt != null) {
                sel = parseRanges(rangeEt.getText().toString(), first.pages);
                if (sel == null && !mode.equals("rotate")) {
                    throw new IllegalArgumentException("Indica quali pagine usare (es. 1-3, 5).");
                }
                if (mode.equals("delete") && sel.length >= first.pages) {
                    throw new IllegalArgumentException("Cosi' eliminerei tutte le pagine: ne deve restare almeno una.");
                }
            }
            if (mode.equals("split") && !splitEach) {
                try {
                    n = Integer.parseInt(nEt.getText().toString().trim());
                } catch (NumberFormatException e) {
                    n = 0;
                }
                if (n < 1) {
                    throw new IllegalArgumentException("Indica quante pagine per file (almeno 1).");
                }
            }
        } catch (IllegalArgumentException e) {
            result.error(e.getMessage());
            return;
        }
        final int[] fsel = sel;
        final int fn = n;
        final List<Item> work = new ArrayList<>(items);
        busy = true;
        run.setEnabled(false);
        result.start("Elaborazione in corso...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PDFBoxResourceLoader.init(getApplicationContext());
                    execute(work, fsel, fn);
                } catch (OutOfMemoryError oom) {
                    result.error("Memoria insufficiente: il PDF e' troppo grande per questo telefono.");
                } catch (Throwable t) {
                    String m = t.getMessage();
                    String low = m == null ? "" : m.toLowerCase();
                    if (low.contains("password") || low.contains("encrypt") || low.contains("decrypt")) {
                        result.error("Il PDF e' protetto da password: non posso modificarlo.");
                    } else {
                        result.error("Operazione non riuscita"
                                + (m != null && !m.isEmpty() ? ": " + m : " (" + t.getClass().getSimpleName() + ")"));
                    }
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            run.setEnabled(mode.equals("merge") ? items.size() >= 2 : !items.isEmpty());
                        }
                    });
                }
            }
        }).start();
    }

    private PDDocument load(File f) throws IOException {
        PDDocument d = PDDocument.load(f);
        d.setAllSecurityToBeRemoved(true);
        return d;
    }

    private Uri savePdf(final PDDocument d, String name) throws IOException {
        return FileUtil.save(this, MediaStore.Downloads.EXTERNAL_CONTENT_URI, DIR, name, MIME,
                new FileUtil.Writer() {
                    @Override
                    public void write(OutputStream os) throws IOException {
                        d.save(os);
                    }
                });
    }

    private String stamp() {
        return new java.text.SimpleDateFormat("yyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private void execute(List<Item> work, int[] sel, int n) throws IOException {
        Item first = work.get(0);
        String base = FileUtil.baseName(first.name);
        if (mode.equals("merge")) {
            List<PDDocument> open = new ArrayList<>();
            PDDocument out = new PDDocument();
            try {
                int total = 0;
                for (Item it : work) {
                    total += it.pages;
                }
                int done = 0;
                for (Item it : work) {
                    PDDocument d = load(it.file);
                    open.add(d);
                    for (PDPage p : d.getPages()) {
                        out.importPage(p);
                        done++;
                        if (done % 5 == 0) {
                            result.progress("Unione pagina " + done + " di " + total, done * 100 / total);
                        }
                    }
                }
                result.progress("Salvataggio...", -1);
                Uri u = savePdf(out, "Unito_" + stamp() + ".pdf");
                result.ok("Fatto! " + total + " pagine unite in Download/MamoTools/"
                        + FileUtil.displayName(this, u), u, MIME, "Apri il PDF unito");
            } finally {
                closeQuiet(out);
                for (PDDocument d : open) {
                    closeQuiet(d);
                }
            }
            return;
        }

        PDDocument src = load(first.file);
        try {
            int total = src.getNumberOfPages();
            if (mode.equals("split")) {
                int per = splitEach ? 1 : n;
                int parts = (total + per - 1) / per;
                Uri lastUri = null;
                for (int pi = 0; pi < parts; pi++) {
                    PDDocument part = new PDDocument();
                    try {
                        for (int p = pi * per; p < Math.min(total, (pi + 1) * per); p++) {
                            part.importPage(src.getPage(p));
                        }
                        String nm = base + (per == 1 ? "_p" + String.format("%03d", pi + 1)
                                : "_parte" + String.format("%02d", pi + 1)) + ".pdf";
                        lastUri = savePdf(part, nm);
                    } finally {
                        closeQuiet(part);
                    }
                    result.progress("Creato file " + (pi + 1) + " di " + parts, (pi + 1) * 100 / parts);
                }
                if (parts == 1) {
                    result.ok("Il PDF ha meno pagine del gruppo scelto: creato un solo file.",
                            lastUri, MIME, "Apri il PDF");
                } else {
                    result.ok("Fatto! " + parts + " file salvati in Download/MamoTools",
                            null, null, "Apri Download");
                }
            } else if (mode.equals("extract")) {
                PDDocument out = new PDDocument();
                try {
                    for (int p : sel) {
                        out.importPage(src.getPage(p));
                    }
                    Uri u = savePdf(out, base + "_estratto_" + stamp() + ".pdf");
                    result.ok("Fatto! " + sel.length + (sel.length == 1 ? " pagina" : " pagine")
                            + " in Download/MamoTools/" + FileUtil.displayName(this, u),
                            u, MIME, "Apri il PDF");
                } finally {
                    closeQuiet(out);
                }
            } else if (mode.equals("delete")) {
                for (int k = sel.length - 1; k >= 0; k--) {
                    src.removePage(sel[k]);
                }
                Uri u = savePdf(src, base + "_ridotto_" + stamp() + ".pdf");
                result.ok("Fatto! Eliminate " + sel.length + (sel.length == 1 ? " pagina" : " pagine")
                        + ". Restano " + src.getNumberOfPages() + ". Salvato in Download/MamoTools/"
                        + FileUtil.displayName(this, u), u, MIME, "Apri il PDF");
            } else { // rotate
                int count = 0;
                if (sel == null) {
                    for (int p = 0; p < total; p++) {
                        rotate(src.getPage(p));
                        count++;
                    }
                } else {
                    for (int p : sel) {
                        rotate(src.getPage(p));
                        count++;
                    }
                }
                Uri u = savePdf(src, base + "_ruotato_" + stamp() + ".pdf");
                result.ok("Fatto! Ruotate " + count + (count == 1 ? " pagina" : " pagine") + " di "
                        + rotateDeg + "°. Salvato in Download/MamoTools/" + FileUtil.displayName(this, u),
                        u, MIME, "Apri il PDF");
            }
        } finally {
            closeQuiet(src);
        }
    }

    private void rotate(PDPage p) {
        p.setRotation(((p.getRotation() + rotateDeg) % 360 + 360) % 360);
    }

    private static void closeQuiet(PDDocument d) {
        try {
            d.close();
        } catch (Exception ignored) {
        }
    }
}
