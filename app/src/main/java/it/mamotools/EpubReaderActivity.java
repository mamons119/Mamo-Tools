package it.mamotools;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lettore EPUB: apre i libri .epub direttamente (anche da "Apri con"), con indice, ricerca, segnalibri,
 * temi, carattere e interlinea regolabili, lettura a scorrimento o a pagine, posizione ricordata e libri recenti.
 */
public class EpubReaderActivity extends Activity {

    private static final int REQ_PICK = 81;
    private static final String HOST = "mamo.epub";
    private static final String[][] THEMES = {
            // sfondo, testo, collegamenti
            {"#FFFFFF", "#1B1B1B", "#1565C0"}, {"#F4ECD8", "#4B3A26", "#8D5A1B"},
            {"#1A1D24", "#D7DBE3", "#4DD0E1"}, {"#000000", "#BDC2CB", "#4DD0E1"}};
    private static final String[] THEME_NAMES = {"Chiaro", "Seppia", "Notte", "Nero"};
    private static final String[] FONT_NAMES = {"Originale", "Serif", "Sans-serif"};
    private static final String[] FONT_CSS = {"", "Georgia,'Noto Serif',serif", "'Roboto',sans-serif"};
    private static final String[] MARGIN_NAMES = {"Stretti", "Medi", "Larghi"};
    private static final int[] MARGIN_PX = {10, 22, 40};
    private static final String[] ALIGN_NAMES = {"Originale", "Sinistra", "Giustificato"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // impostazioni
    private int theme = 0, textPct = 100, fontFam = 0, lh = 15, margin = 1, align = 0, mode = 0, bright = 0;
    private boolean keepOn = false;

    // libro
    private EpubBook book;
    private String bookId = "";
    private String[] chTitle = new String[0];
    private int chapter = 0;
    private float frac = 0;
    private boolean atEnd = false, loading = false, seeking = false;
    private int loadToken = 0, initToken = -1, reloadCount = 0;
    private float pendFrac = 0;
    private String pendAnchor = null;
    private boolean pendEnd = false;
    private String pendSearch = null;
    private int pendSearchIdx = 0;
    private final List<float[]> history = new ArrayList<>();
    private boolean fromIntent = false, inReader = false, barsOn = true;
    private volatile String css = "";
    private String jsSource = null;

    // viste del lettore
    private FrameLayout readerRoot;
    private WebView web;
    private LinearLayout topBar, bottomBar;
    private TextView titleTv, progTv, footTv;
    private SeekBar seek;

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            savePos();
        }
    };

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("epub", MODE_PRIVATE);
        theme = prefs.getInt("theme", 0);
        textPct = prefs.getInt("text", 100);
        fontFam = prefs.getInt("font", 0);
        lh = prefs.getInt("lh", 15);
        margin = prefs.getInt("margin", 1);
        align = prefs.getInt("align", 0);
        mode = prefs.getInt("mode", 0);
        bright = prefs.getInt("bright", 0);
        keepOn = prefs.getBoolean("keepon", false);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            fromIntent = true;
            showLibrary();
            openUri(in.getData());
        } else {
            showLibrary();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        savePos();
        if (web != null) {
            web.destroy();
        }
        if (book != null) {
            book.close();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (inReader && mode == 1 && book != null) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reload();
                }
            }, 350);
        }
    }

    @Override
    public void onBackPressed() {
        if (inReader) {
            if (!history.isEmpty()) {
                float[] p = history.remove(history.size() - 1);
                loadChapter((int) p[0], p[1], null, false);
                return;
            }
            if (fromIntent) {
                finish();
                return;
            }
            closeBook();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ libreria

    private void showLibrary() {
        inReader = false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = Ui.screen(this, "Leggi EPUB", "Libri in formato .epub, con indice, ricerca e segnalibri.");
        root.addView(new Ui.PickCard(this, "📖", "Apri un libro EPUB", "Scegli un file .epub dal telefono",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pick();
                    }
                }).view);
        List<JSONObject> rec = recents();
        root.addView(Ui.section(this, "Libri recenti"), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 18));
        if (rec.isEmpty()) {
            TextView e = Ui.text(this, "Qui compariranno i libri che apri: li ritrovi con la pagina a cui eri arrivato.",
                    13, Ui.DIM, false);
            root.addView(e);
            return;
        }
        for (final JSONObject r : rec) {
            final String id = r.optString("id");
            LinearLayout row = Ui.card(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSaved(id);
                }
            });
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(Ui.round(this, Ui.BG, 8));
            File cf = new File(epubDir(), id + ".jpg");
            if (cf.exists()) {
                cover.setImageBitmap(BitmapFactory.decodeFile(cf.getAbsolutePath()));
            } else {
                cover.setImageResource(R.drawable.ic_notification);
                cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
            cover.setClipToOutline(true);
            cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(EpubReaderActivity.this, 8));
                }
            });
            row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 76)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
            TextView t = Ui.text(this, r.optString("title"), 15, Ui.TEXT, true);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
            String au = r.optString("author");
            if (!au.isEmpty()) {
                TextView a = Ui.text(this, au, 12, Ui.DIM, false);
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(a);
            }
            int pct = (int) Math.round(r.optDouble("pct", 0) * 100);
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(pct);
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            col.addView(pb, Ui.lp(this, Ui.MATCH, Ui.dp(this, 6), 0, 8));
            col.addView(Ui.text(this, pct + "% letto", 11, Ui.DIM, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 4));
            row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            row.addView(Ui.btn(this, "✕", Ui.CARD2, Ui.DIM, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeRecent(id);
                    showLibrary();
                }
            }), new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
            root.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "application/octet-stream",
                "application/zip"});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            openUri(data.getData());
        }
    }

    private File epubDir() {
        File d = new File(getFilesDir(), "epub");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private List<JSONObject> recents() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (new File(epubDir(), o.optString("id") + ".epub").exists()) {
                    out.add(o);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void putRecent(String id, String title, String author, float pct) {
        try {
            JSONArray old = new JSONArray(prefs.getString("recent", "[]"));
            JSONArray n = new JSONArray();
            JSONObject me = new JSONObject();
            me.put("id", id);
            me.put("title", title);
            me.put("author", author);
            me.put("pct", pct);
            n.put(me);
            for (int i = 0; i < old.length() && n.length() < 12; i++) {
                if (!old.getJSONObject(i).optString("id").equals(id)) {
                    n.put(old.getJSONObject(i));
                }
            }
            for (int i = 0; i < old.length(); i++) {
                String oid = old.getJSONObject(i).optString("id");
                boolean kept = false;
                for (int j = 0; j < n.length(); j++) {
                    kept |= n.getJSONObject(j).optString("id").equals(oid);
                }
                if (!kept) {
                    deleteBookFiles(oid);
                }
            }
            prefs.edit().putString("recent", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void removeRecent(String id) {
        try {
            JSONArray old = new JSONArray(prefs.getString("recent", "[]"));
            JSONArray n = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                if (!old.getJSONObject(i).optString("id").equals(id)) {
                    n.put(old.getJSONObject(i));
                }
            }
            prefs.edit().putString("recent", n.toString()).remove("pos_" + id).remove("bm_" + id).apply();
        } catch (Exception ignored) {
        }
        deleteBookFiles(id);
    }

    private void deleteBookFiles(String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(epubDir(), id + ".epub").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(epubDir(), id + ".jpg").delete();
    }

    // ------------------------------------------------------------------ apertura del libro

    private void openSaved(String id) {
        openFile(new File(epubDir(), id + ".epub"), id);
    }

    private void openUri(final Uri uri) {
        Toast.makeText(this, "Apertura del libro…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File tmp = new File(epubDir(), "incoming.tmp");
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(tmp)) {
                        if (in == null) {
                            throw new java.io.IOException("file non leggibile");
                        }
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            md.update(buf, 0, r);
                            out.write(buf, 0, r);
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    byte[] dg = md.digest();
                    for (int i = 0; i < 8; i++) {
                        sb.append(String.format(Locale.US, "%02x", dg[i]));
                    }
                    final String id = sb.toString();
                    File dest = new File(epubDir(), id + ".epub");
                    if (dest.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    } else if (!tmp.renameTo(dest)) {
                        throw new java.io.IOException("copia non riuscita");
                    }
                    final File f = dest;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            openFile(f, id);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.infoDialog(EpubReaderActivity.this, "Non riesco ad aprire il libro",
                                    "Il file non si puo' leggere: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void openFile(final File f, final String id) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final EpubBook b = EpubBook.open(f);
                    // copertina in miniatura
                    if (b.coverPath != null && !new File(epubDir(), id + ".jpg").exists()) {
                        byte[] cb = b.read(b.coverPath);
                        if (cb != null) {
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(cb, 0, cb.length, o);
                            int ss = 1;
                            while (o.outHeight / (ss * 2) >= 320) {
                                ss *= 2;
                            }
                            o = new BitmapFactory.Options();
                            o.inSampleSize = ss;
                            Bitmap bm = BitmapFactory.decodeByteArray(cb, 0, cb.length, o);
                            if (bm != null) {
                                try (FileOutputStream fo = new FileOutputStream(new File(epubDir(), id + ".jpg"))) {
                                    bm.compress(Bitmap.CompressFormat.JPEG, 80, fo);
                                }
                            }
                        }
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (b.drm) {
                                b.close();
                                Ui.infoDialog(EpubReaderActivity.this, "Libro protetto",
                                        "Questo EPUB e' protetto da DRM (blocco di copia) e non si puo' leggere "
                                                + "qui. I libri senza protezione si aprono normalmente.");
                                return;
                            }
                            startBook(b, id);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.infoDialog(EpubReaderActivity.this, "Non riesco ad aprire il libro",
                                    "Il file non sembra un EPUB valido: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startBook(EpubBook b, String id) {
        if (book != null) {
            book.close();
        }
        book = b;
        bookId = id;
        history.clear();
        chTitle = new String[b.spine.size()];
        for (EpubBook.TocEntry t : b.toc) {
            int i = b.spineIndex(t.path);
            if (i >= 0 && chTitle[i] == null) {
                chTitle[i] = t.title;
            }
        }
        for (int i = 0; i < chTitle.length; i++) {
            if (chTitle[i] == null) {
                chTitle[i] = "Capitolo " + (i + 1);
            }
        }
        buildReader();
        int ch = 0;
        float f = 0;
        String p = prefs.getString("pos_" + id, "");
        if (p.contains(":")) {
            try {
                ch = Math.max(0, Math.min(b.spine.size() - 1, Integer.parseInt(p.substring(0, p.indexOf(':')))));
                f = Float.parseFloat(p.substring(p.indexOf(':') + 1));
            } catch (Exception ignored) {
            }
        }
        putRecent(id, b.title, b.author, overall());
        inReader = true;
        setContentView(readerRoot);
        applyWindow();
        showBars(true);
        titleTv.setText(b.title);
        loadChapter(ch, f, null, false);
    }

    private void closeBook() {
        savePos();
        web.loadUrl("about:blank");
        showLibrary();
    }

    // ------------------------------------------------------------------ vista del lettore

    private void buildReader() {
        if (readerRoot != null) {
            return;
        }
        readerRoot = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serve(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return link(r.getUrl());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady(url);
            }
        });
        readerRoot.addView(web, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        footTv = Ui.text(this, "", 10, Ui.DIM, false);
        footTv.setGravity(Gravity.CENTER);
        footTv.setPadding(0, 0, 0, Ui.dp(this, 4));
        readerRoot.addView(footTv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        // barra in alto
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(0xF2151820);
        topBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 28), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.addView(Ui.btn(this, "‹", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (fromIntent) {
                    savePos();
                    finish();
                } else {
                    closeBook();
                }
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        titleTv = Ui.text(this, "", 15, Ui.TEXT, true);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setPadding(Ui.dp(this, 12), 0, 0, 0);
        r1.addView(titleTv, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        topBar.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        String[] names = {"Indice", "Cerca", "Segnalibri", "Aspetto", "Info"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            Button bt = Ui.btn(this, names[i], Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (k == 0) {
                        showToc();
                    } else if (k == 1) {
                        showSearch();
                    } else if (k == 2) {
                        showBookmarks();
                    } else if (k == 3) {
                        showSettings();
                    } else {
                        showInfo();
                    }
                }
            });
            bt.setTextSize(12);
            bt.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            r2.addView(bt, Ui.lp(this, 0, Ui.dp(this, 38), i == 0 ? 0 : 6, 0, 1f));
        }
        topBar.addView(r2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        readerRoot.addView(topBar, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        // barra in basso
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(0xF2151820);
        bottomBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 14));
        progTv = Ui.text(this, "", 12, Ui.DIM, false);
        progTv.setGravity(Gravity.CENTER);
        progTv.setSingleLine(true);
        progTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bottomBar.addView(progTv);
        LinearLayout r3 = new LinearLayout(this);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        r3.addView(Ui.btn(this, "‹ Cap.", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prevChapter(false);
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 38)));
        seek = Ui.seek(this, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (user) {
                    progTv.setText(String.format(Locale.ITALY, "%d%%", Math.round(p / 10f)));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                seekTo(sb.getProgress() / 1000f);
            }
        });
        seek.setMax(1000);
        r3.addView(seek, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        r3.addView(Ui.btn(this, "Cap. ›", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextChapter();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 38)));
        bottomBar.addView(r3, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        readerRoot.addView(bottomBar, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));
    }

    private void applyWindow() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = bright <= 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : bright / 100f;
        getWindow().setAttributes(lp);
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        int bg = android.graphics.Color.parseColor(THEMES[theme][0]);
        readerRoot.setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        footTv.setTextColor(android.graphics.Color.parseColor(THEMES[theme][1]) & 0x99FFFFFF);
    }

    private void showBars(boolean on) {
        barsOn = on;
        topBar.setVisibility(on ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(on ? View.VISIBLE : View.GONE);
        footTv.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ caricamento dei capitoli

    private void loadChapter(int idx, float f, String anchor, boolean end) {
        if (book == null || idx < 0 || idx >= book.spine.size()) {
            return;
        }
        chapter = idx;
        frac = f < 0 ? 0 : f;
        atEnd = false;
        pendFrac = f;
        pendAnchor = anchor;
        pendEnd = end;
        loading = true;
        loadToken++;
        css = buildCss();
        WebSettings s = web.getSettings();
        boolean fixed = book.fixedLayout;
        s.setTextZoom(fixed ? 100 : textPct);
        s.setUseWideViewPort(fixed);
        s.setLoadWithOverviewMode(fixed);
        s.setSupportZoom(fixed);
        s.setBuiltInZoomControls(fixed);
        s.setDisplayZoomControls(false);
        applyWindow();
        web.setAlpha(0f);
        final int tk = loadToken;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loadToken == tk) {
                    web.setAlpha(1f);
                }
            }
        }, 5000);
        web.loadUrl("https://" + HOST + "/" + Uri.encode(book.spine.get(idx).path, "/") + "?v=" + loadToken);
        updateProgressUi();
    }

    private void reload() {
        if (book != null && inReader) {
            loadChapter(chapter, frac, null, false);
        }
    }

    private String js() {
        if (jsSource == null) {
            try (InputStream in = getAssets().open("epub_reader.js")) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int r;
                while ((r = in.read(b)) > 0) {
                    o.write(b, 0, r);
                }
                jsSource = o.toString("UTF-8");
            } catch (Exception e) {
                jsSource = "";
            }
        }
        return jsSource;
    }

    private void pageReady(String url) {
        if (book == null || url == null || !url.startsWith("https://" + HOST) || initToken == loadToken) {
            return;
        }
        initToken = loadToken;
        web.evaluateJavascript(js(), null);
        web.evaluateJavascript("mt.init('" + (mode == 1 && !book.fixedLayout ? "page" : "scroll") + "',"
                + (chapter > 0) + "," + (chapter < book.spine.size() - 1) + ");", null);
        String a = pendAnchor == null ? "null" : JSONObject.quote(pendAnchor);
        web.evaluateJavascript("mt.restore(" + pendFrac + "," + a + "," + pendEnd + ");", null);
        loading = false;
        web.animate().alpha(1f).setDuration(120).start();
        if (pendSearch != null) {
            final String q = pendSearch;
            final int k = pendSearchIdx;
            pendSearch = null;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    web.findAllAsync(q);
                    for (int i = 0; i < k; i++) {
                        web.findNext(true);
                    }
                }
            }, 400);
        } else {
            web.clearMatches();
        }
    }

    private String buildCss() {
        if (book.fixedLayout) {
            return "html,body{background:" + THEMES[theme][0] + ";}";
        }
        String bg = THEMES[theme][0], fg = THEMES[theme][1], lk = THEMES[theme][2];
        int px = MARGIN_PX[margin];
        boolean page = mode == 1;
        StringBuilder c = new StringBuilder();
        c.append("html{background:").append(bg).append(" !important;-webkit-text-size-adjust:100%;}");
        c.append("body{background:").append(bg).append(" !important;color:").append(fg)
                .append(" !important;margin:0 !important;padding:20px ").append(px).append("px 26px ").append(px)
                .append("px !important;box-sizing:border-box !important;max-width:none !important;")
                .append("overflow-wrap:break-word;");
        if (!FONT_CSS[fontFam].isEmpty()) {
            c.append("font-family:").append(FONT_CSS[fontFam]).append(" !important;");
        }
        c.append("}");
        c.append("body *{color:").append(fg).append(" !important;background-color:transparent !important;}");
        c.append("a,a *{color:").append(lk).append(" !important;}");
        if (!FONT_CSS[fontFam].isEmpty()) {
            c.append("body *{font-family:inherit !important;}pre,code,kbd,samp,tt{font-family:monospace !important;}");
        }
        c.append("p,li,blockquote,dd,div{line-height:").append(lh / 10f).append(" !important;}");
        if (align == 1) {
            c.append("p,li,blockquote,dd,div{text-align:left !important;}");
        } else if (align == 2) {
            c.append("p,li,blockquote,dd,div{text-align:justify !important;hyphens:auto;-webkit-hyphens:auto;}");
        }
        c.append("img{max-width:100% !important;height:auto !important;}svg{max-width:100%;}");
        c.append("table{max-width:100%;}pre{white-space:pre-wrap;}");
        if (page) {
            c.append("html{overflow:hidden !important;height:100% !important;}");
            c.append("body{height:100vh !important;width:100vw !important;column-width:calc(100vw - ")
                    .append(px * 2).append("px) !important;column-gap:").append(px * 2)
                    .append("px !important;column-fill:auto !important;}");
            c.append("img,svg,figure,table{break-inside:avoid;}img{max-height:calc(100vh - 60px) !important;")
                    .append("object-fit:contain;}h1,h2,h3{break-after:avoid;}");
        }
        return c.toString();
    }

    // ------------------------------------------------------------------ risorse e collegamenti

    private WebResourceResponse serve(Uri u) {
        EpubBook b = book;
        if (b == null || !HOST.equals(u.getHost())) {
            return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", new HashMap<String, String>(),
                    new ByteArrayInputStream(new byte[0]));
        }
        String path = EpubBook.norm(u.getPath() == null ? "" : u.getPath());
        byte[] data = b.read(path);
        if (data == null) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", new HashMap<String, String>(),
                    new ByteArrayInputStream(new byte[0]));
        }
        String mime = b.mime(path);
        Map<String, String> hd = new HashMap<>();
        hd.put("Access-Control-Allow-Origin", "*");
        boolean html = mime.contains("html") || b.spineIndex(path) >= 0 && mime.contains("xml");
        if (html) {
            String t = html(EpubBook.decodeText(data));
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", hd,
                    new ByteArrayInputStream(t.getBytes(java.nio.charset.Charset.forName("UTF-8"))));
        }
        String enc = mime.startsWith("text/") || mime.contains("xml") || mime.contains("svg") ? "UTF-8" : null;
        return new WebResourceResponse(mime, enc, 200, "OK", hd, new ByteArrayInputStream(data));
    }

    private static final Pattern VOID_FIX = Pattern.compile(
            "<(a|div|span|p|script|style|title|h[1-6]|section|article|aside|nav|header|footer|ul|ol|li|table|tr|td|th|"
                    + "tbody|thead|b|i|em|strong|u|sup|sub|blockquote|pre|code|label|button|textarea|iframe|figure|"
                    + "figcaption|small|font|center|dl|dt|dd|cite|abbr|mark)(\\s[^<>]*?)?\\s*/>",
            Pattern.CASE_INSENSITIVE);

    /** Prepara una pagina XHTML: tag auto-chiusi corretti e stile del lettore. */
    private String html(String t) {
        Matcher m = VOID_FIX.matcher(t);
        t = m.replaceAll("<$1$2></$1>");
        t = t.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "");
        if (indexOfIgnoreCase(t, "<!doctype") < 0) {
            t = "<!DOCTYPE html>" + t;
        }
        String inject = (book.fixedLayout ? "" : "<meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\">") + "<style id=\"mt-style\">" + css + "</style>";
        int i = indexOfIgnoreCase(t, "</head>");
        if (i >= 0) {
            return t.substring(0, i) + inject + t.substring(i);
        }
        int b = indexOfIgnoreCase(t, "<body");
        if (b >= 0) {
            return t.substring(0, b) + "<head>" + inject + "</head>" + t.substring(b);
        }
        return "<head>" + inject + "</head>" + t;
    }

    private static int indexOfIgnoreCase(String s, String what) {
        return s.toLowerCase(Locale.ROOT).indexOf(what);
    }

    private boolean link(Uri u) {
        if (HOST.equals(u.getHost())) {
            String path = EpubBook.norm(u.getPath() == null ? "" : u.getPath());
            int idx = book == null ? -1 : book.spineIndex(path);
            String frag = u.getFragment();
            if (idx >= 0) {
                if (idx == chapter && frag != null && !loading) {
                    web.evaluateJavascript("mt.restore(-1," + JSONObject.quote(frag) + ",false);", null);
                } else {
                    history.add(new float[]{chapter, frac});
                    loadChapter(idx, frag == null ? 0 : -1, frag, false);
                }
            }
            return true;
        }
        final String s = u.getScheme();
        if ("http".equals(s) || "https".equals(s) || "mailto".equals(s)) {
            confirmExternal(u);
        }
        return true;
    }

    private void confirmExternal(final Uri u) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD, 20));
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 16));
        box.addView(Ui.text(this, "Collegamento esterno", 18, Ui.TEXT, true));
        TextView m = Ui.text(this, "Aprire " + u + " fuori dal lettore?", 14, Ui.DIM, false);
        box.addView(m, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        LinearLayout r = new LinearLayout(this);
        r.addView(Ui.btn(this, "Annulla", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        }), Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        r.addView(Ui.btn(this, "Apri", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    Toast.makeText(EpubReaderActivity.this, "Nessuna app per aprirlo", Toast.LENGTH_SHORT).show();
                }
            }
        }), Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        box.addView(r, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 16));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    // ------------------------------------------------------------------ ponte con la pagina

    private final class Bridge {
        @JavascriptInterface
        public void tap(final int zone) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    onTap(zone);
                }
            });
        }

        @JavascriptInterface
        public void pos(final float f, final boolean end) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (loading) {
                        return;
                    }
                    frac = f;
                    atEnd = end;
                    updateProgressUi();
                    h.removeCallbacks(saver);
                    h.postDelayed(saver, 1500);
                }
            });
        }

        @JavascriptInterface
        public void next() {
            h.post(new Runnable() {
                @Override
                public void run() {
                    nextChapter();
                }
            });
        }

        @JavascriptInterface
        public void prev() {
            h.post(new Runnable() {
                @Override
                public void run() {
                    prevChapter(false);
                }
            });
        }
    }

    private void onTap(int zone) {
        web.clearMatches();
        if (zone == 1) {
            showBars(!barsOn);
            return;
        }
        if (barsOn) {
            showBars(false);
            return;
        }
        final int dir = zone == 2 ? 1 : -1;
        web.evaluateJavascript("mt.go(" + dir + ");", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if ("false".equals(v)) {
                    if (dir > 0) {
                        nextChapter();
                    } else {
                        prevChapter(true);
                    }
                }
            }
        });
    }

    private void nextChapter() {
        if (book == null) {
            return;
        }
        if (chapter + 1 < book.spine.size()) {
            loadChapter(chapter + 1, 0, null, false);
        } else {
            Toast.makeText(this, "Fine del libro", Toast.LENGTH_SHORT).show();
        }
    }

    private void prevChapter(boolean end) {
        if (book == null) {
            return;
        }
        if (chapter > 0) {
            loadChapter(chapter - 1, 0, null, end);
        } else {
            Toast.makeText(this, "Sei all'inizio del libro", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ avanzamento e posizione

    private float overall() {
        if (book == null || book.totalSize <= 0) {
            return 0;
        }
        float f = atEnd ? 1f : Math.max(0, frac);
        long size = book.cum[chapter + 1] - book.cum[chapter];
        float v = (book.cum[chapter] + f * size) / (float) book.totalSize;
        return Math.max(0, Math.min(1, v));
    }

    private void updateProgressUi() {
        if (book == null || progTv == null || seeking) {
            return;
        }
        float o = overall();
        String t = chTitle.length > chapter ? chTitle[chapter] : "";
        String line = String.format(Locale.ITALY, "%s  ·  %d/%d  ·  %d%%", t, chapter + 1, book.spine.size(),
                Math.round(o * 100));
        progTv.setText(line);
        footTv.setText(String.format(Locale.ITALY, "%d/%d  ·  %d%%", chapter + 1, book.spine.size(),
                Math.round(o * 100)));
        seek.setProgress(Math.round(o * 1000));
    }

    private void seekTo(float o) {
        if (book == null) {
            return;
        }
        float target = o * book.totalSize;
        int ch = book.spine.size() - 1;
        for (int i = 0; i < book.spine.size(); i++) {
            if (target < book.cum[i + 1]) {
                ch = i;
                break;
            }
        }
        long size = Math.max(1, book.cum[ch + 1] - book.cum[ch]);
        float f = Math.max(0, Math.min(0.999f, (target - book.cum[ch]) / size));
        history.add(new float[]{chapter, frac});
        loadChapter(ch, f, null, false);
    }

    private void savePos() {
        if (book == null || bookId.isEmpty()) {
            return;
        }
        prefs.edit().putString("pos_" + bookId, chapter + ":" + frac).apply();
        putRecent(bookId, book.title, book.author, overall());
    }

    // ------------------------------------------------------------------ finestre

    private Dialog sheet(String title, View content, boolean tall) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD, 20));
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 14));
        box.addView(Ui.text(this, title, 18, Ui.TEXT, true));
        ScrollView sv = new ScrollView(this);
        sv.addView(content, new ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP));
        if (tall) {
            box.addView(sv, Ui.lp(this, Ui.MATCH, 0, 0, 12, 1f));
        } else {
            box.addView(sv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }
        box.addView(Ui.btn(this, "Chiudi", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 12));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
            w.setLayout((int) (sw * 0.92f), tall ? (int) (sh * 0.82f) : ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        return d;
    }

    private TextView row(String text, boolean on, int indentDp) {
        TextView t = Ui.text(this, text, 14, on ? Ui.ACCENT : Ui.TEXT, on);
        t.setPadding(Ui.dp(this, indentDp), Ui.dp(this, 11), Ui.dp(this, 4), Ui.dp(this, 11));
        t.setClickable(true);
        return t;
    }

    private void showToc() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dref = new Dialog[1];
        for (final EpubBook.TocEntry e : book.toc) {
            int idx = book.spineIndex(e.path);
            boolean cur = idx == chapter && e.frag.isEmpty();
            TextView t = row(e.title, cur, Math.min(e.depth, 4) * 18);
            t.setEnabled(idx >= 0);
            t.setAlpha(idx >= 0 ? 1f : 0.4f);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dref[0].dismiss();
                    int i = book.spineIndex(e.path);
                    if (i < 0) {
                        return;
                    }
                    history.add(new float[]{chapter, frac});
                    if (i == chapter && !e.frag.isEmpty() && !loading) {
                        web.evaluateJavascript("mt.restore(-1," + JSONObject.quote(e.frag) + ",false);", null);
                    } else {
                        loadChapter(i, e.frag.isEmpty() ? 0 : -1, e.frag.isEmpty() ? null : e.frag, false);
                    }
                    showBars(false);
                }
            });
            col.addView(t);
            View sep = new View(this);
            sep.setBackgroundColor(0x22FFFFFF);
            col.addView(sep, new LinearLayout.LayoutParams(Ui.MATCH, 1));
        }
        dref[0] = sheet("Indice", col, true);
    }

    // ---- ricerca

    private void showSearch() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final EditText q = new EditText(this);
        q.setHint("Cerca nel libro");
        q.setHintTextColor(0x669AA3B2);
        q.setTextColor(Ui.TEXT);
        q.setTextSize(15);
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setBackground(Ui.round(this, Ui.BG, 12));
        q.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        col.addView(q, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        final TextView status = Ui.text(this, "Scrivi una parola e premi invio.", 12, Ui.DIM, false);
        status.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        col.addView(status);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results);
        final Dialog[] dref = new Dialog[1];
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                String s = q.getText().toString().trim();
                if (s.length() < 2) {
                    status.setText("Scrivi almeno 2 lettere.");
                    return true;
                }
                runSearch(s, status, results, dref);
                return true;
            }
        });
        dref[0] = sheet("Cerca nel libro", col, true);
        q.requestFocus();
    }

    private void runSearch(final String query, final TextView status, final LinearLayout results, final Dialog[] dref) {
        status.setText("Ricerca in corso…");
        results.removeAllViews();
        final EpubBook b = book;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Object[]> hits = new ArrayList<>(); // {capitolo, occorrenza, anteprima}
                String ql = query.toLowerCase(Locale.ROOT);
                outer:
                for (int i = 0; i < b.spine.size(); i++) {
                    byte[] d = b.read(b.spine.get(i).path);
                    if (d == null) {
                        continue;
                    }
                    String body = EpubBook.decodeText(d);
                    int bi = indexOfIgnoreCase(body, "<body");
                    if (bi > 0) {
                        body = body.substring(bi);
                    }
                    String text = EpubBook.unescape(EpubBook.stripTags(body));
                    String low = text.toLowerCase(Locale.ROOT);
                    int from = 0, k = 0;
                    while (true) {
                        int p = low.indexOf(ql, from);
                        if (p < 0) {
                            break;
                        }
                        int a = Math.max(0, p - 40), e = Math.min(text.length(), p + query.length() + 60);
                        hits.add(new Object[]{i, k, (a > 0 ? "…" : "") + text.substring(a, e) + (e < text.length() ? "…" : "")});
                        k++;
                        from = p + ql.length();
                        if (hits.size() >= 300) {
                            break outer;
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(hits.isEmpty() ? "Nessun risultato." : (hits.size() >= 300 ? "Oltre 300" : "" + hits.size())
                                + " risultati");
                        for (final Object[] ht : hits) {
                            LinearLayout r = new LinearLayout(EpubReaderActivity.this);
                            r.setOrientation(LinearLayout.VERTICAL);
                            r.setPadding(0, Ui.dp(EpubReaderActivity.this, 9), 0, Ui.dp(EpubReaderActivity.this, 9));
                            r.setClickable(true);
                            r.addView(Ui.text(EpubReaderActivity.this, chTitle[(Integer) ht[0]], 12, Ui.ACCENT, true));
                            TextView sn = Ui.text(EpubReaderActivity.this, (String) ht[2], 13, Ui.TEXT, false);
                            r.addView(sn);
                            r.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    dref[0].dismiss();
                                    history.add(new float[]{chapter, frac});
                                    pendSearch = query;
                                    pendSearchIdx = (Integer) ht[1];
                                    int ch = (Integer) ht[0];
                                    if (ch == chapter && !loading) {
                                        web.findAllAsync(query);
                                        for (int i = 0; i < pendSearchIdx; i++) {
                                            web.findNext(true);
                                        }
                                        pendSearch = null;
                                    } else {
                                        loadChapter(ch, 0, null, false);
                                    }
                                    showBars(false);
                                }
                            });
                            results.addView(r);
                            View sep = new View(EpubReaderActivity.this);
                            sep.setBackgroundColor(0x22FFFFFF);
                            results.addView(sep, new LinearLayout.LayoutParams(Ui.MATCH, 1));
                        }
                    }
                });
            }
        }).start();
    }

    // ---- segnalibri

    private JSONArray bookmarks() {
        try {
            return new JSONArray(prefs.getString("bm_" + bookId, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void showBookmarks() {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dref = new Dialog[1];
        col.addView(Ui.btn(this, "＋  Aggiungi segnalibro qui", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    JSONArray a = bookmarks();
                    JSONObject o = new JSONObject();
                    o.put("ch", chapter);
                    o.put("f", frac);
                    o.put("pct", Math.round(overall() * 100));
                    o.put("t", chTitle[chapter]);
                    a.put(o);
                    prefs.edit().putString("bm_" + bookId, a.toString()).apply();
                } catch (Exception ignored) {
                }
                dref[0].dismiss();
                Toast.makeText(EpubReaderActivity.this, "Segnalibro aggiunto", Toast.LENGTH_SHORT).show();
            }
        }), new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 46)));
        JSONArray a = bookmarks();
        if (a.length() == 0) {
            TextView e = Ui.text(this, "Nessun segnalibro: aggiungine uno per ritrovare un punto del libro.", 13,
                    Ui.DIM, false);
            e.setPadding(0, Ui.dp(this, 14), 0, 0);
            col.addView(e);
        }
        for (int i = 0; i < a.length(); i++) {
            final int idx = i;
            final JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setClickable(true);
            t.addView(Ui.text(this, o.optString("t"), 14, Ui.TEXT, true));
            t.addView(Ui.text(this, "Capitolo " + (o.optInt("ch") + 1) + "  ·  " + o.optInt("pct") + "%", 12,
                    Ui.DIM, false));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dref[0].dismiss();
                    history.add(new float[]{chapter, frac});
                    loadChapter(o.optInt("ch"), (float) o.optDouble("f"), null, false);
                    showBars(false);
                }
            });
            r.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            r.addView(Ui.btn(this, "✕", Ui.CARD2, Ui.DIM, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        JSONArray cur = bookmarks();
                        JSONArray n = new JSONArray();
                        for (int j = 0; j < cur.length(); j++) {
                            if (j != idx) {
                                n.put(cur.get(j));
                            }
                        }
                        prefs.edit().putString("bm_" + bookId, n.toString()).apply();
                    } catch (Exception ignored) {
                    }
                    dref[0].dismiss();
                    showBookmarks();
                }
            }), new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
            col.addView(r);
            View sep = new View(this);
            sep.setBackgroundColor(0x22FFFFFF);
            col.addView(sep, new LinearLayout.LayoutParams(Ui.MATCH, 1));
        }
        dref[0] = sheet("Segnalibri", col, true);
    }

    // ---- aspetto

    private TextView[] chips(LinearLayout parent, String label, String[] names, int sel, final IntSetter set) {
        parent.addView(Ui.text(this, label, 12, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
        LinearLayout r = new LinearLayout(this);
        final TextView[] cs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            cs[i] = Ui.chip(this, names[i]);
            cs[i].setTextSize(12);
            Ui.styleChip(this, cs[i], i == sel);
            cs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < cs.length; j++) {
                        Ui.styleChip(EpubReaderActivity.this, cs[j], j == k);
                    }
                    set.set(k);
                }
            });
            r.addView(cs[i], Ui.lp(this, 0, Ui.dp(this, 40), i == 0 ? 0 : 6, 0, 1f));
        }
        parent.addView(r, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        return cs;
    }

    private interface IntSetter {
        void set(int v);
    }

    private void showSettings() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                prefs.edit().putInt("theme", theme).putInt("text", textPct).putInt("font", fontFam).putInt("lh", lh)
                        .putInt("margin", margin).putInt("align", align).putInt("mode", mode).putInt("bright", bright)
                        .putBoolean("keepon", keepOn).apply();
                h.removeCallbacks(reloader);
                h.postDelayed(reloader, 250);
            }
        };
        chips(col, "Tema", THEME_NAMES, theme, new IntSetter() {
            @Override
            public void set(int v) {
                theme = v;
                apply.run();
            }
        });
        // dimensione del testo
        col.addView(Ui.text(this, "Dimensione del testo", 12, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
        LinearLayout sz = new LinearLayout(this);
        sz.setGravity(Gravity.CENTER_VERTICAL);
        final TextView szv = Ui.text(this, textPct + "%", 16, Ui.TEXT, true);
        szv.setGravity(Gravity.CENTER);
        sz.addView(Ui.btn(this, "A−", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                textPct = Math.max(60, textPct - 10);
                szv.setText(textPct + "%");
                apply.run();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 42)));
        sz.addView(szv, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        sz.addView(Ui.btn(this, "A＋", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                textPct = Math.min(260, textPct + 10);
                szv.setText(textPct + "%");
                apply.run();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 42)));
        col.addView(sz, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        chips(col, "Carattere", FONT_NAMES, fontFam, new IntSetter() {
            @Override
            public void set(int v) {
                fontFam = v;
                apply.run();
            }
        });
        // interlinea
        col.addView(Ui.text(this, "Interlinea", 12, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
        LinearLayout ll = new LinearLayout(this);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        final TextView llv = Ui.text(this, String.format(Locale.ITALY, "%.1f", lh / 10f), 16, Ui.TEXT, true);
        llv.setGravity(Gravity.CENTER);
        ll.addView(Ui.btn(this, "−", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lh = Math.max(11, lh - 1);
                llv.setText(String.format(Locale.ITALY, "%.1f", lh / 10f));
                apply.run();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 42)));
        ll.addView(llv, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        ll.addView(Ui.btn(this, "＋", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lh = Math.min(26, lh + 1);
                llv.setText(String.format(Locale.ITALY, "%.1f", lh / 10f));
                apply.run();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 42)));
        col.addView(ll, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        chips(col, "Margini", MARGIN_NAMES, margin, new IntSetter() {
            @Override
            public void set(int v) {
                margin = v;
                apply.run();
            }
        });
        chips(col, "Allineamento", ALIGN_NAMES, align, new IntSetter() {
            @Override
            public void set(int v) {
                align = v;
                apply.run();
            }
        });
        chips(col, "Lettura", new String[]{"Scorrimento", "Pagine"}, mode, new IntSetter() {
            @Override
            public void set(int v) {
                mode = v;
                apply.run();
            }
        });
        // luminosita'
        col.addView(Ui.text(this, "Luminosità (0 = automatica)", 12, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
        SeekBar bs = Ui.seek(this, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    bright = p;
                    prefs.edit().putInt("bright", bright).apply();
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = bright <= 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            : Math.max(0.02f, bright / 100f);
                    getWindow().setAttributes(lp);
                }
            }
        });
        bs.setMax(100);
        bs.setProgress(bright);
        col.addView(bs, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        final TextView keep = Ui.chip(this, "Schermo sempre acceso");
        keep.setTextSize(12);
        Ui.styleChip(this, keep, keepOn);
        keep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keepOn = !keepOn;
                Ui.styleChip(EpubReaderActivity.this, keep, keepOn);
                prefs.edit().putBoolean("keepon", keepOn).apply();
                applyWindow();
            }
        });
        col.addView(keep, Ui.lp(this, Ui.MATCH, Ui.dp(this, 42), 0, 14));
        sheet("Aspetto", col, true);
    }

    private final Runnable reloader = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    // ---- info

    private void showInfo() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (book.coverPath != null) {
            byte[] cb = book.read(book.coverPath);
            if (cb != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap bm = BitmapFactory.decodeByteArray(cb, 0, cb.length, o);
                if (bm != null) {
                    ImageView iv = new ImageView(this);
                    iv.setAdjustViewBounds(true);
                    iv.setImageBitmap(bm);
                    col.addView(iv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 220)));
                }
            }
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Titolo", book.title});
        if (!book.author.isEmpty()) {
            rows.add(new String[]{"Autore", book.author});
        }
        if (!book.publisher.isEmpty()) {
            rows.add(new String[]{"Editore", book.publisher});
        }
        if (!book.language.isEmpty()) {
            rows.add(new String[]{"Lingua", book.language});
        }
        if (!book.date.isEmpty()) {
            rows.add(new String[]{"Data", book.date.length() > 10 ? book.date.substring(0, 10) : book.date});
        }
        rows.add(new String[]{"Capitoli", String.valueOf(book.spine.size())});
        rows.add(new String[]{"Impaginazione", book.fixedLayout ? "Fissa" : "Adattabile"});
        rows.add(new String[]{"Letto", Math.round(overall() * 100) + "%"});
        KvTable kv = new KvTable(this);
        kv.set(rows.toArray(new String[0][]));
        col.addView(kv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        if (!book.description.isEmpty()) {
            TextView d = Ui.text(this, book.description, 13, Ui.DIM, false);
            d.setLineSpacing(0, 1.15f);
            d.setPadding(0, Ui.dp(this, 12), 0, 0);
            col.addView(d);
        }
        sheet("Info sul libro", col, true);
    }
}
