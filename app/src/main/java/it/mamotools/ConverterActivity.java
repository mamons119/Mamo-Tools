package it.mamotools;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Converte PDF in Word (.docx) e Word (.docx) in PDF.
 * Il file si sceglie con il selettore di sistema e il risultato va nella cartella Download.
 */
public class ConverterActivity extends Activity {

    private static final int REQ_PICK = 31;
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private interface Writer {
        void write(OutputStream os) throws IOException;
    }

    private Uri pickedUri;
    private String pickedName;
    private boolean pickedIsPdf;
    private long pickedSize = -1;
    private Uri resultUri;
    private String resultMime;
    private volatile boolean busy = false;

    private TextView fileName, fileInfo, status;
    private Button pickBtn, convertBtn, openBtn;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(40), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        root.addView(text("PDF ↔ Word", 28, R.color.text, true));
        TextView sub = text("Scegli un file PDF o Word (.docx): lo converto nell'altro formato "
                + "e lo salvo in Download.", 14, R.color.text_dim, false);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        pickBtn = new Button(this);
        pickBtn.setText("Scegli un file");
        pickBtn.setAllCaps(false);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });
        root.addView(pickBtn, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        fileName = text("Nessun file scelto", 16, R.color.text, true);
        fileInfo = text("", 13, R.color.text_dim, false);
        fileInfo.setPadding(0, dp(4), 0, 0);
        card.addView(fileName);
        card.addView(fileInfo);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.topMargin = dp(16);
        root.addView(card, clp);

        convertBtn = new Button(this);
        convertBtn.setAllCaps(false);
        convertBtn.setText("Converti");
        convertBtn.setEnabled(false);
        convertBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                convert();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH, WRAP);
        blp.topMargin = dp(16);
        root.addView(convertBtn, blp);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(WRAP, WRAP);
        plp.topMargin = dp(16);
        plp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        root.addView(progress, plp);

        status = text("", 14, R.color.text, false);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        openBtn = new Button(this);
        openBtn.setAllCaps(false);
        openBtn.setText("Apri il file convertito");
        openBtn.setVisibility(View.GONE);
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openResult();
            }
        });
        root.addView(openBtn, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView note = text("PDF → Word: estrae il testo e lo rimette in paragrafi "
                + "(titoli, grassetto e dimensioni). Immagini, tabelle e impaginazione "
                + "non vengono mantenute, e i PDF scansionati (solo immagini) non funzionano.\n\n"
                + "Word → PDF: mantiene testo formattato, elenchi, tabelle semplici e "
                + "immagini. Intestazioni, pie' di pagina e caselle di testo non vengono "
                + "disegnati, e i font possono differire dall'originale. "
                + "Il formato .doc vecchio non e' supportato: salvalo prima come .docx.",
                12, R.color.text_dim, false);
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    // ------------------------------------------------------------------ scelta del file

    private void pick() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{MIME_PDF, MIME_DOCX});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = queryName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = getContentResolver().getType(uri);

        resultUri = null;
        openBtn.setVisibility(View.GONE);

        if (lower.endsWith(".pdf") || MIME_PDF.equals(mime)) {
            pickedIsPdf = true;
        } else if (lower.endsWith(".docx") || MIME_DOCX.equals(mime)) {
            pickedIsPdf = false;
        } else {
            pickedUri = null;
            convertBtn.setEnabled(false);
            fileName.setText(name);
            fileInfo.setText("");
            status.setText(lower.endsWith(".doc")
                    ? "Il formato .doc (Word vecchio) non e' supportato: aprilo in Word e "
                    + "salvalo come .docx."
                    : "Formato non supportato: scegli un file PDF o .docx.");
            return;
        }
        pickedUri = uri;
        pickedName = name;
        pickedSize = querySize(uri);
        fileName.setText(name);
        fileInfo.setText((pickedIsPdf ? "PDF" : "Word (.docx)")
                + (pickedSize >= 0 ? " · " + humanSize(pickedSize) : ""));
        convertBtn.setText(pickedIsPdf ? "Converti in Word (.docx)" : "Converti in PDF");
        convertBtn.setEnabled(true);
        status.setText("");
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
        return last == null ? "documento" : last;
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
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.0f KB", b / 1024f);
        }
        return String.format(Locale.getDefault(), "%.1f MB", b / 1048576f);
    }

    // ------------------------------------------------------------------ conversione

    private void convert() {
        if (busy || pickedUri == null) {
            return;
        }
        busy = true;
        resultUri = null;
        openBtn.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        pickBtn.setEnabled(false);
        convertBtn.setEnabled(false);
        setStatus("Conversione in corso...");

        final Uri src = pickedUri;
        final boolean pdf = pickedIsPdf;
        final String base = baseName(pickedName);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(getCacheDir(), pdf ? "input.pdf" : "input.docx");
                try {
                    copyToFile(src, tmp);
                    Uri saved;
                    String mime;
                    if (pdf) {
                        final PdfTextExtractor.Result r = PdfTextExtractor.extract(
                                ConverterActivity.this, tmp, new PdfTextExtractor.Progress() {
                                    @Override
                                    public void onPage(int page, int total) {
                                        setStatus("Lettura pagina " + page + " di " + total + "...");
                                    }
                                });
                        setStatus("Creazione del file Word...");
                        mime = MIME_DOCX;
                        saved = saveToDownloads(base + ".docx", mime, new Writer() {
                            @Override
                            public void write(OutputStream os) throws IOException {
                                DocxWriter.write(r.paras, r.pageW, r.pageH, os);
                            }
                        });
                    } else {
                        setStatus("Lettura del documento Word...");
                        final DocModel model = DocxParser.parse(tmp);
                        setStatus("Creazione del PDF...");
                        mime = MIME_PDF;
                        saved = saveToDownloads(base + ".pdf", mime, new Writer() {
                            @Override
                            public void write(OutputStream os) throws IOException {
                                DocxToPdf.render(model, os);
                            }
                        });
                    }
                    done(saved, mime);
                } catch (OutOfMemoryError oom) {
                    fail("Memoria insufficiente: il documento e' troppo grande per essere "
                            + "convertito su questo telefono.");
                } catch (Throwable t) {
                    String m = t.getMessage();
                    fail("Conversione non riuscita"
                            + (m != null && !m.isEmpty() ? ": " + m : " (" + t.getClass().getSimpleName() + ")"));
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }).start();
    }

    private void done(final Uri uri, final String mime) {
        final String name = displayNameOf(uri);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                resultUri = uri;
                resultMime = mime;
                progress.setVisibility(View.GONE);
                pickBtn.setEnabled(true);
                convertBtn.setEnabled(true);
                openBtn.setVisibility(View.VISIBLE);
                status.setText("Fatto! Salvato in Download: " + name);
            }
        });
    }

    private void fail(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                progress.setVisibility(View.GONE);
                pickBtn.setEnabled(true);
                convertBtn.setEnabled(pickedUri != null);
                status.setText(msg);
            }
        });
    }

    private void setStatus(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(msg);
            }
        });
    }

    private void copyToFile(Uri src, File dst) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IOException("impossibile leggere il file scelto");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    /** Salva nella cartella Download tramite MediaStore (non servono permessi). */
    private Uri saveToDownloads(String name, String mime, Writer w) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("impossibile creare il file in Download");
        }
        boolean ok = false;
        try {
            try (OutputStream raw = cr.openOutputStream(uri)) {
                if (raw == null) {
                    throw new IOException("impossibile scrivere il file in Download");
                }
                BufferedOutputStream os = new BufferedOutputStream(raw, 64 * 1024);
                w.write(os);
                os.flush();
            }
            ok = true;
        } finally {
            if (ok) {
                ContentValues u = new ContentValues();
                u.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, u, null, null);
            } else {
                cr.delete(uri, null, null);
            }
        }
        return uri;
    }

    private String displayNameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) {
                    return n;
                }
            }
        } catch (Exception ignored) {
        }
        return "file convertito";
    }

    private static String baseName(String name) {
        String n = name == null ? "documento" : name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "documento" : n;
    }

    private void openResult() {
        if (resultUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(resultUri, resultMime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Nessuna app per aprire questo file. Lo trovi in Download.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ utilita'

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(colorRes));
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
