package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lettore PDF: scorrimento continuo, zoom, vai a pagina, ricerca, modalita' notte,
 * condivisione, stampa e salvataggio di una copia. Si apre anche da "Apri con".
 */
public class PdfReaderActivity extends Activity {

    private static final int REQ_PICK = 61;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int C_BAR = 0xE60F1115, C_BTN = 0xFF262B35, C_ACCENT = 0xFF4DD0E1,
            C_TEXT = 0xFFECEFF4, C_DIM = 0xFF9AA3B2;

    private FrameLayout root;
    private PdfView pdfView;
    private LinearLayout topBar, bottomBar, searchBar, emptyBox;
    private TextView title, pagePill, badge, searchCount;
    private EditText searchEdit;
    private ProgressBar spinner;
    private TextView spinnerText;

    private Uri docUri;
    private String docName = "documento.pdf";
    private long docBytes = -1;
    private int pages = 0;
    private float firstW, firstH;
    private boolean chrome = true;

    // ricerca
    private String[] pageTexts;
    private File searchFile;
    private final List<float[]> matches = new ArrayList<>();     // {pagina, y in punti}
    private int matchIdx = -1;
    private volatile boolean indexing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0F1115);
        getWindow().setNavigationBarColor(0xFF0F1115);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF202124);

        buildEmpty();
        buildSpinner();
        buildTopBar();
        buildBottomBar();
        buildBadge();
        setContentView(root);

        Intent in = getIntent();
        if (in != null && in.getData() != null) {
            open(in.getData());
        } else {
            pick();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            open(intent.getData());
        }
    }

    // ---------------------------------------------------------------- interfaccia

    private void buildEmpty() {
        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(dp(24), 0, dp(24), 0);
        TextView ic = text("📕", 54, Color.WHITE, false);
        ic.setGravity(Gravity.CENTER);
        emptyBox.addView(ic, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView t = text("PDF Reader", 24, C_TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(4));
        emptyBox.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView st = text("Scegli un PDF dal telefono per leggerlo.", 14, C_DIM, false);
        st.setGravity(Gravity.CENTER);
        emptyBox.addView(st, new LinearLayout.LayoutParams(MATCH, WRAP));
        Button b = button("Apri un PDF", C_ACCENT, Color.BLACK, v -> pick());
        b.setPadding(dp(28), 0, dp(28), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, dp(48));
        lp.topMargin = dp(20);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        emptyBox.addView(b, lp);
        root.addView(emptyBox, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
    }

    private void buildSpinner() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(C_ACCENT));
        box.addView(spinner);
        spinnerText = text("", 13, C_DIM, false);
        spinnerText.setGravity(Gravity.CENTER);
        spinnerText.setPadding(0, dp(8), 0, 0);
        box.addView(spinnerText);
        box.setVisibility(View.GONE);
        box.setTag("spinnerBox");
        root.addView(box, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
    }

    private void showBusy(String msg) {
        View box = root.findViewWithTag("spinnerBox");
        box.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        spinnerText.setText(msg == null ? "" : msg);
    }

    private void buildTopBar() {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(C_BAR);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.addView(iconBtn("‹", v -> finish()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        title = text("PDF Reader", 16, C_TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setPadding(dp(8), 0, dp(8), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(iconBtn("🔍", v -> showSearch(true)), lp(dp(44), dp(44), 4));
        row.addView(iconBtn("⋮", v -> showMenu(v)), lp(dp(44), dp(44), 4));
        topBar.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));

        searchBar = new LinearLayout(this);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(10), dp(6), dp(6), dp(6));
        searchBar.setVisibility(View.GONE);
        searchEdit = new EditText(this);
        searchEdit.setHint("Cerca nel documento");
        searchEdit.setHintTextColor(C_DIM);
        searchEdit.setTextColor(C_TEXT);
        searchEdit.setSingleLine(true);
        searchEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        searchEdit.setBackground(round(C_BTN, 12));
        searchEdit.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchEdit.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                runSearch();
                return true;
            }
            return false;
        });
        searchBar.addView(searchEdit, new LinearLayout.LayoutParams(0, WRAP, 1f));
        searchCount = text("", 12, C_DIM, false);
        searchCount.setPadding(dp(8), 0, dp(4), 0);
        searchBar.addView(searchCount);
        searchBar.addView(iconBtn("▲", v -> stepHit(-1)), lp(dp(40), dp(40), 2));
        searchBar.addView(iconBtn("▼", v -> stepHit(1)), lp(dp(40), dp(40), 2));
        searchBar.addView(iconBtn("✕", v -> showSearch(false)), lp(dp(40), dp(40), 2));
        topBar.addView(searchBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        topBar.setVisibility(View.GONE);
        root.addView(topBar, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));
    }

    private void buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(10));
        bottomBar.setBackgroundColor(C_BAR);
        bottomBar.addView(iconBtn("⏮", v -> pdfView.goToPage(pdfView.currentPage() - 1)),
                new LinearLayout.LayoutParams(dp(46), dp(46)));
        pagePill = button("– / –", C_BTN, C_TEXT, v -> askPage());
        bottomBar.addView(pagePill, lp(dp(110), dp(46), 8));
        bottomBar.addView(iconBtn("⏭", v -> pdfView.goToPage(pdfView.currentPage() + 1)),
                lp(dp(46), dp(46), 8));
        bottomBar.addView(iconBtn("−", v -> pdfView.zoomBy(1 / 1.4f)), lp(dp(46), dp(46), 24));
        bottomBar.addView(iconBtn("+", v -> pdfView.zoomBy(1.4f)), lp(dp(46), dp(46), 8));
        bottomBar.setVisibility(View.GONE);
        root.addView(bottomBar, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
    }

    private void buildBadge() {
        badge = text("", 12, Color.WHITE, true);
        badge.setBackground(round(0xAA000000, 100));
        badge.setPadding(dp(12), dp(5), dp(12), dp(5));
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(16);
        root.addView(badge, lp);
    }

    private void setChrome(boolean on) {
        chrome = on;
        topBar.setVisibility(on && pdfView != null ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(on && pdfView != null ? View.VISIBLE : View.GONE);
        badge.setVisibility(!on && pdfView != null ? View.VISIBLE : View.GONE);
    }

    private void updatePage(int page, int total) {
        String s = (page + 1) + " / " + total;
        pagePill.setText(s);
        badge.setText(s);
    }

    // ---------------------------------------------------------------- apertura

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("Impossibile aprire il selettore di file.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            open(data.getData());
        }
    }

    private void open(final Uri uri) {
        docUri = uri;
        docName = queryName(uri);
        docBytes = querySize(uri);
        pageTexts = null;
        matches.clear();
        matchIdx = -1;
        if (searchFile != null) {
            //noinspection ResultOfMethodCallIgnored
            searchFile.delete();
            searchFile = null;
        }
        emptyBox.setVisibility(View.GONE);
        title.setText(docName);
        showBusy("Apertura...");
        if (pdfView != null) {
            root.removeView(pdfView);
            pdfView.close();
            pdfView = null;
        }
        setChrome(false);
        badge.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                PdfRenderer r = null;
                String err = null;
                try {
                    try {
                        ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
                        if (fd == null) {
                            throw new IOException("file non leggibile");
                        }
                        r = new PdfRenderer(fd);
                    } catch (SecurityException se) {
                        throw se;
                    } catch (Exception first) {
                        // alcuni provider non permettono di spostarsi nel file: lo copio in cache
                        File f = new File(getCacheDir(), "open.pdf");
                        copy(uri, f);
                        r = new PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY));
                    }
                    final int n = r.getPageCount();
                    if (n == 0) {
                        throw new IOException("il PDF non contiene pagine");
                    }
                    final float[] w = new float[n];
                    final float[] h = new float[n];
                    for (int i = 0; i < n; i++) {
                        PdfRenderer.Page p = r.openPage(i);
                        w[i] = p.getWidth();
                        h[i] = p.getHeight();
                        p.close();
                    }
                    final PdfRenderer rr = r;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            attach(rr, w, h);
                        }
                    });
                    return;
                } catch (SecurityException se) {
                    err = "Il PDF e' protetto da password: non posso aprirlo.";
                } catch (Throwable t) {
                    err = "Impossibile aprire il PDF"
                            + (t.getMessage() != null ? ": " + t.getMessage() : ".");
                }
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
                final String e = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showBusy(null);
                        emptyBox.setVisibility(View.VISIBLE);
                        toast(e);
                    }
                });
            }
        }).start();
    }

    private void attach(PdfRenderer r, float[] w, float[] h) {
        showBusy(null);
        pages = w.length;
        firstW = w[0];
        firstH = h[0];
        pdfView = new PdfView(this);
        pdfView.setListener(new PdfView.Listener() {
            @Override
            public void onPageChanged(int page, int total) {
                updatePage(page, total);
            }

            @Override
            public void onTap() {
                setChrome(!chrome);
            }
        });
        root.addView(pdfView, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        pdfView.setDocument(r, w, h);
        updatePage(0, pages);
        setChrome(true);
    }

    // ---------------------------------------------------------------- menu

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Vai a pagina...");
        m.getMenu().add(0, 2, 1, pdfView != null && pdfView.isNight() ? "Modalita' giorno" : "Modalita' notte");
        m.getMenu().add(0, 3, 2, "Condividi");
        m.getMenu().add(0, 4, 3, "Stampa");
        m.getMenu().add(0, 5, 4, "Salva una copia in Download");
        m.getMenu().add(0, 6, 5, "Informazioni");
        m.getMenu().add(0, 7, 6, "Apri un altro PDF");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: askPage(); break;
                case 2:
                    if (pdfView != null) {
                        pdfView.setNight(!pdfView.isNight());
                    }
                    break;
                case 3: share(); break;
                case 4: print(); break;
                case 5: saveCopy(); break;
                case 6: info(); break;
                default: pick(); break;
            }
            return true;
        });
        m.show();
    }

    private void askPage() {
        if (pdfView == null) {
            return;
        }
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1 - " + pages);
        new AlertDialog.Builder(this)
                .setTitle("Vai a pagina")
                .setView(e)
                .setPositiveButton("Vai", (d, w) -> {
                    try {
                        pdfView.goToPage(Integer.parseInt(e.getText().toString().trim()) - 1);
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void info() {
        if (pdfView == null) {
            return;
        }
        float wmm = firstW / 72f * 25.4f;
        float hmm = firstH / 72f * 25.4f;
        String format = (Math.abs(firstW - 595f) < 4 && Math.abs(firstH - 842f) < 4) ? " (A4)"
                : (Math.abs(firstW - 612f) < 4 && Math.abs(firstH - 792f) < 4) ? " (Letter)" : "";
        String msg = "Nome: " + docName
                + "\nDimensione: " + humanSize(docBytes)
                + "\nPagine: " + pages
                + String.format(Locale.US, "\nFormato pagina 1: %.0f x %.0f mm%s", wmm, hmm, format)
                + String.format(Locale.US, "\nZoom: %.0f%%", pdfView.zoom() * 100);
        new AlertDialog.Builder(this).setTitle("Informazioni").setMessage(msg)
                .setPositiveButton("OK", null).show();
    }

    // ---------------------------------------------------------------- condividi / stampa / salva

    private void share() {
        if (docUri == null) {
            return;
        }
        final String name = safeName();
        toast("Preparo il file...");
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "shared");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, name);
                copy(docUri, f);
                final Uri u = Uri.parse("content://" + PdfShareProvider.AUTHORITY + "/" + Uri.encode(name));
                runOnUiThread(() -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("application/pdf");
                    s.putExtra(Intent.EXTRA_STREAM, u);
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Condividi PDF"));
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("Condivisione non riuscita: " + t.getMessage()));
            }
        }).start();
    }

    private void print() {
        if (docUri == null) {
            return;
        }
        final Uri src = docUri;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        pm.print(docName, new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal c,
                                 LayoutResultCallback cb, Bundle extras) {
                if (c.isCanceled()) {
                    cb.onLayoutCancelled();
                    return;
                }
                cb.onLayoutFinished(new PrintDocumentInfo.Builder(docName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(pages).build(), true);
            }

            @Override
            public void onWrite(final PageRange[] pr, final ParcelFileDescriptor dest,
                                CancellationSignal c, final WriteResultCallback cb) {
                new Thread(() -> {
                    try (InputStream in = getContentResolver().openInputStream(src);
                         OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                        byte[] buf = new byte[64 * 1024];
                        int k;
                        while (in != null && (k = in.read(buf)) > 0) {
                            out.write(buf, 0, k);
                        }
                        cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                    } catch (Throwable t) {
                        cb.onWriteFailed(t.getMessage());
                    }
                }).start();
            }
        }, null);
    }

    private void saveCopy() {
        if (docUri == null) {
            return;
        }
        final String name = safeName();
        new Thread(() -> {
            ContentResolver cr = getContentResolver();
            Uri out = null;
            boolean ok = false;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                out = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (out == null) {
                    throw new IOException("impossibile creare il file");
                }
                try (InputStream in = cr.openInputStream(docUri);
                     OutputStream os = cr.openOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int k;
                    while (in != null && os != null && (k = in.read(buf)) > 0) {
                        os.write(buf, 0, k);
                    }
                }
                ContentValues u = new ContentValues();
                u.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(out, u, null, null);
                ok = true;
                runOnUiThread(() -> toast("Copia salvata in Download."));
            } catch (Throwable t) {
                runOnUiThread(() -> toast("Salvataggio non riuscito: " + t.getMessage()));
            } finally {
                if (!ok && out != null) {
                    cr.delete(out, null, null);
                }
            }
        }).start();
    }

    private String safeName() {
        String n = docName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) {
            n = "documento";
        }
        return n.toLowerCase(Locale.ROOT).endsWith(".pdf") ? n : n + ".pdf";
    }

    private void copy(Uri src, File dst) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IOException("impossibile leggere il file");
            }
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) {
                os.write(buf, 0, k);
            }
        }
    }

    // ---------------------------------------------------------------- ricerca

    private void showSearch(boolean on) {
        searchBar.setVisibility(on ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (on) {
            searchEdit.requestFocus();
            imm.showSoftInput(searchEdit, 0);
        } else {
            imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
            matches.clear();
            matchIdx = -1;
            searchCount.setText("");
            if (pdfView != null) {
                pdfView.setHighlights(null);
            }
        }
    }

    private void runSearch() {
        if (docUri == null || pdfView == null) {
            return;
        }
        final String q = searchEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
        if (pageTexts != null && searchFile != null) {
            applySearch(q);
            return;
        }
        if (indexing) {
            return;
        }
        indexing = true;
        showBusy("Leggo il testo del documento...");
        new Thread(() -> {
            try {
                PDFBoxResourceLoader.init(getApplicationContext());
                File f = new File(getCacheDir(), "search.pdf");
                copy(docUri, f);
                PDDocument doc = PDDocument.load(f);
                try {
                    final int n = doc.getNumberOfPages();
                    String[] t = new String[n];
                    PDFTextStripper st = new PDFTextStripper();
                    st.setSortByPosition(true);
                    for (int p = 0; p < n; p++) {
                        final int pp = p;
                        runOnUiThread(() -> spinnerText.setText("Leggo il testo: pagina " + (pp + 1)
                                + " di " + n));
                        st.setStartPage(p + 1);
                        st.setEndPage(p + 1);
                        t[p] = st.getText(doc).toLowerCase(Locale.ROOT);
                    }
                    pageTexts = t;
                    searchFile = f;
                } finally {
                    doc.close();
                }
                runOnUiThread(() -> {
                    showBusy(null);
                    applySearch(q);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    showBusy(null);
                    toast("Ricerca non disponibile: " + (t.getMessage() != null
                            ? t.getMessage() : t.getClass().getSimpleName()));
                });
            } finally {
                indexing = false;
            }
        }).start();
    }

    /** Trova le pagine con il testo e ne calcola i rettangoli da evidenziare in giallo. */
    private void applySearch(final String q) {
        final List<Integer> hitPages = new ArrayList<>();
        boolean anyText = false;
        for (int i = 0; i < pageTexts.length; i++) {
            if (!pageTexts[i].trim().isEmpty()) {
                anyText = true;
            }
            if (pageTexts[i].contains(q)) {
                hitPages.add(i);
            }
        }
        matches.clear();
        matchIdx = -1;
        pdfView.setHighlights(null);
        if (!anyText) {
            searchCount.setText("");
            toast("Questo PDF non ha testo selezionabile (e' una scansione).");
            return;
        }
        if (hitPages.isEmpty()) {
            searchCount.setText("0");
            toast("Nessun risultato.");
            return;
        }
        showBusy("Evidenzio i risultati...");
        final File f = searchFile;
        new Thread(() -> {
            final java.util.Map<Integer, List<float[]>> map = new java.util.HashMap<>();
            final List<float[]> found = new ArrayList<>();
            try {
                PDFBoxResourceLoader.init(getApplicationContext());
                PDDocument doc = PDDocument.load(f);
                try {
                    Finder fin = new Finder(q);
                    fin.setSortByPosition(true);
                    int done = 0;
                    for (int p : hitPages) {
                        if (done++ > 400) {
                            break;
                        }
                        fin.reset();
                        fin.setStartPage(p + 1);
                        fin.setEndPage(p + 1);
                        fin.getText(doc);
                        fin.flushLine();
                        if (!fin.rects.isEmpty()) {
                            map.put(p, new ArrayList<>(fin.rects));
                            for (float[] r : fin.rects) {
                                found.add(new float[]{p, r[1]});
                            }
                        }
                    }
                } finally {
                    doc.close();
                }
            } catch (Throwable ignored) {
            }
            if (found.isEmpty()) {
                // posizioni non disponibili: almeno si salta alla pagina giusta
                for (int p : hitPages) {
                    found.add(new float[]{p, 0f});
                }
            }
            runOnUiThread(() -> {
                showBusy(null);
                pdfView.setHighlights(map);
                matches.addAll(found);
                int cur = pdfView.currentPage();
                matchIdx = 0;
                for (int i = 0; i < matches.size(); i++) {
                    if ((int) matches.get(i)[0] >= cur) {
                        matchIdx = i;
                        break;
                    }
                }
                showMatch();
            });
        }).start();
    }

    private void stepHit(int d) {
        if (matches.isEmpty()) {
            runSearch();
            return;
        }
        matchIdx = (matchIdx + d + matches.size()) % matches.size();
        showMatch();
    }

    private void showMatch() {
        float[] m = matches.get(matchIdx);
        searchCount.setText((matchIdx + 1) + "/" + matches.size());
        pdfView.scrollToPoint((int) m[0], m[1]);
    }

    /** Cerca il testo riga per riga e ne ricava la posizione sulla pagina. */
    private static final class Finder extends PDFTextStripper {
        final String q;
        final List<float[]> rects = new ArrayList<>();
        private final StringBuilder line = new StringBuilder();
        private final List<TextPosition> pos = new ArrayList<>();

        Finder(String q) throws IOException {
            super();
            this.q = q;
        }

        void reset() {
            rects.clear();
            line.setLength(0);
            pos.clear();
        }

        @Override
        protected void writeString(String text, List<TextPosition> tps) throws IOException {
            for (TextPosition tp : tps) {
                String u = tp.getUnicode();
                line.append(u);
                for (int k = 0; k < u.length(); k++) {
                    pos.add(tp);
                }
            }
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            line.append(' ');
            pos.add(null);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
        }

        @Override
        protected void writeParagraphStart() throws IOException {
            flushLine();
        }

        @Override
        protected void writeParagraphEnd() throws IOException {
            flushLine();
        }

        void flushLine() {
            String l = line.toString().toLowerCase(Locale.ROOT);
            if (l.length() == pos.size()) {
                int from = 0;
                while ((from = l.indexOf(q, from)) >= 0) {
                    float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = 0, y1 = 0;
                    boolean any = false;
                    for (int i = from; i < from + q.length(); i++) {
                        TextPosition tp = pos.get(i);
                        if (tp == null) {
                            continue;
                        }
                        float h = tp.getHeightDir();
                        float x = tp.getXDirAdj();
                        float y = tp.getYDirAdj() - h;
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x + tp.getWidthDirAdj());
                        y1 = Math.max(y1, y + h * 1.2f);
                        any = true;
                    }
                    if (any) {
                        rects.add(new float[]{x0, y0, x1 - x0, y1 - y0});
                    }
                    from += Math.max(1, q.length());
                }
            }
            line.setLength(0);
            pos.clear();
        }
    }

    // ---------------------------------------------------------------- utilita'

    @Override
    public void onBackPressed() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            showSearch(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pdfView != null) {
            pdfView.close();
        }
        if (searchFile != null) {
            //noinspection ResultOfMethodCallIgnored
            searchFile.delete();
        }
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "documento.pdf" : last;
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String humanSize(long b) {
        if (b < 0) {
            return "n/d";
        }
        if (b < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", b / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", b / 1048576f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private Button button(String label, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(round(bg, 12));
        b.setOnClickListener(l);
        return b;
    }

    private Button iconBtn(String label, View.OnClickListener l) {
        Button b = button(label, C_BTN, C_TEXT, l);
        b.setTextSize(16);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(leftDp);
        return p;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
