package it.mamotools;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** PDF → immagini (JPG o PNG). */
public class PdfImagesActivity extends Activity {

    private static final int REQ_PICK = 51;
    private static final int MAX_SIDE_PDF = 1600;
    private static final int MAX_SIDE_IMG = 4500;

    private volatile boolean busy = false;
    private Button run;
    private ResultCard result;

    // pdf2img
    private File pdfFile;
    private String pdfName = "";
    private int pdfPages;
    private Ui.PickCard pick;
    private EditText rangeEt;
    private boolean png = false;
    private int dpi = 150;
    private TextView chJpg, chPng, chD72, chD150, chD300;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "PDF → Immagini",
                "Trasforma le pagine di un PDF in immagini JPG o PNG.");
        buildToImg(root);
        result = new ResultCard(this);
        root.addView(result.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
        refresh();
    }

    // ------------------------------------------------------------------ interfaccia





    private void buildToImg(LinearLayout root) {
        pick = new Ui.PickCard(this, "📕", "Scegli un PDF", "Tocca per selezionare il file",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickFiles();
                    }
                });
        root.addView(pick.view);

        LinearLayout opts = Ui.card(this);
        root.addView(opts, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        opts.addView(Ui.section(this, "Pagine"));
        rangeEt = new EditText(this);
        rangeEt.setHint("vuoto = tutte, oppure es. 1-3, 5");
        rangeEt.setHintTextColor(Ui.DIM);
        rangeEt.setTextColor(Ui.TEXT);
        rangeEt.setSingleLine(true);
        rangeEt.setBackground(Ui.round(this, Ui.BG, 10));
        rangeEt.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        opts.addView(rangeEt);

        opts.addView(Ui.text(this, "Formato", 14, Ui.DIM, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
        LinearLayout r1 = new LinearLayout(this);
        chJpg = Ui.chip(this, "JPG (leggero)");
        chPng = Ui.chip(this, "PNG (nitido)");
        r1.addView(chJpg, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        r1.addView(chPng, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        opts.addView(r1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));

        opts.addView(Ui.text(this, "Risoluzione", 14, Ui.DIM, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
        LinearLayout r2 = new LinearLayout(this);
        chD72 = Ui.chip(this, "72 dpi");
        chD150 = Ui.chip(this, "150 dpi");
        chD300 = Ui.chip(this, "300 dpi");
        r2.addView(chD72, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        r2.addView(chD150, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        r2.addView(chD300, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        opts.addView(r2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        chJpg.setOnClickListener(setFmt(false));
        chPng.setOnClickListener(setFmt(true));
        chD72.setOnClickListener(setDpi(72));
        chD150.setOnClickListener(setDpi(150));
        chD300.setOnClickListener(setDpi(300));
        styleToImg();

        run = Ui.btn(this, "Converti in immagini", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startToImg();
            }
        });
        run.setTextSize(16);
        root.addView(run, Ui.lp(this, Ui.MATCH, Ui.dp(this, 54), 0, 16));
    }

    private View.OnClickListener setFmt(final boolean p) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                png = p;
                styleToImg();
            }
        };
    }

    private View.OnClickListener setDpi(final int d) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dpi = d;
                styleToImg();
            }
        };
    }

    private void styleToImg() {
        Ui.styleChip(this, chJpg, !png);
        Ui.styleChip(this, chPng, png);
        Ui.styleChip(this, chD72, dpi == 72);
        Ui.styleChip(this, chD150, dpi == 150);
        Ui.styleChip(this, chD300, dpi == 300);
    }

    // ------------------------------------------------------------------ scelta file

    private void pickFiles() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
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
        if (data.getData() != null) {
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
                loadPdf(uris.get(0));
            }
        }).start();
    }


    private void loadPdf(Uri u) {
        String err = null;
        String name = FileUtil.queryName(this, u);
        int pages = 0;
        File f = new File(getCacheDir(), "img_src.pdf");
        try {
            FileUtil.copyToFile(this, u, f);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                PdfRenderer r = new PdfRenderer(pfd);
                pages = r.getPageCount();
                r.close();
            } finally {
                pfd.close();
            }
        } catch (SecurityException se) {
            err = "Il PDF e' protetto da password: non posso aprirlo.";
        } catch (Exception e) {
            err = "Questo file non sembra un PDF valido.";
        }
        final String fErr = err;
        final String fName = name;
        final int fPages = pages;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                if (fErr == null) {
                    pdfFile = new File(getCacheDir(), "img_src.pdf");
                    pdfName = fName;
                    pdfPages = fPages;
                    result.hide();
                } else {
                    result.error(fErr);
                }
                refresh();
            }
        });
    }

    /** Decodifica con lato massimo maxSide e orientamento EXIF; null se non e' un'immagine. */

    // ------------------------------------------------------------------ elenco immagini

    private void refresh() {
            if (pdfFile == null) {
                pick.title.setText("Scegli un PDF");
                pick.sub.setText("Tocca per selezionare il file");
            } else {
                pick.title.setText(pdfName);
                pick.sub.setText(pdfPages + (pdfPages == 1 ? " pagina" : " pagine"));
            }
            run.setEnabled(pdfFile != null && !busy);
    }




    // ------------------------------------------------------------------ immagini -> PDF



    // ------------------------------------------------------------------ PDF -> immagini

    private void startToImg() {
        if (busy || pdfFile == null) {
            return;
        }
        final int[] sel;
        try {
            sel = PdfOrganizeActivity.parseRanges(rangeEt.getText().toString(), pdfPages);
        } catch (IllegalArgumentException e) {
            result.error(e.getMessage());
            return;
        }
        busy = true;
        run.setEnabled(false);
        result.start("Preparazione...");
        final boolean fPng = png;
        final int fDpi = dpi;
        final File f = pdfFile;
        final String base = FileUtil.baseName(pdfName);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    renderPages(f, base, sel, fPng, fDpi);
                } catch (OutOfMemoryError oom) {
                    result.error("Memoria insufficiente: scegli una risoluzione piu' bassa.");
                } catch (Throwable t) {
                    result.error("Conversione non riuscita: " + t.getMessage());
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            refresh();
                        }
                    });
                }
            }
        }).start();
    }

    private void renderPages(File f, String base, int[] sel, boolean asPng, int dpiV) throws IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = new PdfRenderer(pfd);
        try {
            int total = r.getPageCount();
            int count = sel == null ? total : sel.length;
            Uri first = null;
            for (int k = 0; k < count; k++) {
                int p = sel == null ? k : sel[k];
                result.progress("Pagina " + (p + 1) + " (" + (k + 1) + " di " + count + ")",
                        k * 100 / count);
                PdfRenderer.Page page = r.openPage(p);
                Bitmap bm;
                try {
                    float sc = dpiV / 72f;
                    int w = Math.max(1, Math.round(page.getWidth() * sc));
                    int h = Math.max(1, Math.round(page.getHeight() * sc));
                    int mx = Math.max(w, h);
                    if (mx > MAX_SIDE_IMG) {
                        float d = MAX_SIDE_IMG / (float) mx;
                        w = Math.max(1, Math.round(w * d));
                        h = Math.max(1, Math.round(h * d));
                    }
                    bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bm.eraseColor(Color.WHITE);
                    page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } finally {
                    page.close();
                }
                final Bitmap out = bm;
                final Bitmap.CompressFormat cf = asPng ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
                String name = base + "_p" + String.format(Locale.US, "%03d", p + 1) + (asPng ? ".png" : ".jpg");
                Uri u = FileUtil.save(this, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "Pictures/MamoTools", name, asPng ? "image/png" : "image/jpeg",
                        new FileUtil.Writer() {
                            @Override
                            public void write(OutputStream os) throws IOException {
                                if (!out.compress(cf, 92, os)) {
                                    throw new IOException("compressione non riuscita");
                                }
                            }
                        });
                out.recycle();
                if (first == null) {
                    first = u;
                }
            }
            result.ok("Fatto! " + count + (count == 1 ? " immagine salvata" : " immagini salvate")
                    + " in Immagini/MamoTools", first, asPng ? "image/png" : "image/jpeg",
                    "Apri in Galleria");
        } finally {
            r.close();
            pfd.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pdfFile != null && !busy) {
            //noinspection ResultOfMethodCallIgnored
            pdfFile.delete();
        }
    }
}
