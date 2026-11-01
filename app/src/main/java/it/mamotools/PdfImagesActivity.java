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

/** Immagini → PDF (mode "img2pdf") e PDF → immagini (mode "pdf2img"). */
public class PdfImagesActivity extends Activity {

    private static final int REQ_PICK = 51;
    private static final int MAX_SIDE_PDF = 1600;
    private static final int MAX_SIDE_IMG = 4500;

    private static final class Img {
        Uri uri;
        String name;
        long size;
        Bitmap thumb;
    }

    private boolean toPdf;
    private volatile boolean busy = false;
    private final List<Img> imgs = new ArrayList<>();
    private LinearLayout listBox;
    private Button run;
    private ResultCard result;

    // img2pdf
    private boolean pageA4 = true;
    private int margin = 1; // 0 nessuno, 1 piccolo, 2 medio
    private TextView chA4, chFit, chM0, chM1, chM2;

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
        toPdf = !"pdf2img".equals(getIntent().getStringExtra("mode"));
        LinearLayout root = Ui.screen(this, toPdf ? "Immagini → PDF" : "PDF → Immagini",
                toPdf ? "Aggiungi le foto e ordinale: ogni immagine diventa una pagina."
                        : "Trasforma le pagine di un PDF in immagini JPG o PNG.");
        if (toPdf) {
            buildToPdf(root);
        } else {
            buildToImg(root);
        }
        result = new ResultCard(this);
        root.addView(result.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
        refresh();
    }

    // ------------------------------------------------------------------ interfaccia

    private void buildToPdf(LinearLayout root) {
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);
        root.addView(Ui.btn(this, "＋  Aggiungi immagini", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFiles();
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 50), 0, 8));

        LinearLayout opts = Ui.card(this);
        root.addView(opts, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
        opts.addView(Ui.section(this, "Dimensione pagina"));
        LinearLayout r1 = new LinearLayout(this);
        chA4 = Ui.chip(this, "A4");
        chFit = Ui.chip(this, "Come l'immagine");
        r1.addView(chA4, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        r1.addView(chFit, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        opts.addView(r1);
        opts.addView(Ui.text(this, "Margine (solo A4)", 14, Ui.DIM, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 14));
        LinearLayout r2 = new LinearLayout(this);
        chM0 = Ui.chip(this, "Nessuno");
        chM1 = Ui.chip(this, "Piccolo");
        chM2 = Ui.chip(this, "Medio");
        r2.addView(chM0, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        r2.addView(chM1, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        r2.addView(chM2, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        opts.addView(r2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        chA4.setOnClickListener(setPage(true));
        chFit.setOnClickListener(setPage(false));
        chM0.setOnClickListener(setMargin(0));
        chM1.setOnClickListener(setMargin(1));
        chM2.setOnClickListener(setMargin(2));
        styleToPdf();

        run = Ui.btn(this, "Crea PDF", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startToPdf();
            }
        });
        run.setTextSize(16);
        root.addView(run, Ui.lp(this, Ui.MATCH, Ui.dp(this, 54), 0, 16));
    }

    private View.OnClickListener setPage(final boolean a4) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pageA4 = a4;
                styleToPdf();
            }
        };
    }

    private View.OnClickListener setMargin(final int m) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                margin = m;
                styleToPdf();
            }
        };
    }

    private void styleToPdf() {
        Ui.styleChip(this, chA4, pageA4);
        Ui.styleChip(this, chFit, !pageA4);
        Ui.styleChip(this, chM0, margin == 0);
        Ui.styleChip(this, chM1, margin == 1);
        Ui.styleChip(this, chM2, margin == 2);
        float a = pageA4 ? 1f : 0.35f;
        chM0.setAlpha(a);
        chM1.setAlpha(a);
        chM2.setAlpha(a);
    }

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
        i.setType(toPdf ? "image/*" : "application/pdf");
        if (toPdf) {
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
                if (toPdf) {
                    loadImages(uris);
                } else {
                    loadPdf(uris.get(0));
                }
            }
        }).start();
    }

    private void loadImages(List<Uri> uris) {
        final List<Img> added = new ArrayList<>();
        int bad = 0;
        for (Uri u : uris) {
            Img im = new Img();
            im.uri = u;
            im.name = FileUtil.queryName(this, u);
            im.size = FileUtil.querySize(this, u);
            im.thumb = decode(u, 160, false);
            if (im.thumb == null) {
                bad++;
                continue;
            }
            added.add(im);
        }
        final int fBad = bad;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                imgs.addAll(added);
                refresh();
                if (fBad > 0) {
                    result.error(fBad + (fBad == 1 ? " file non e' un'immagine leggibile e non e' stato aggiunto."
                            : " file non sono immagini leggibili e non sono stati aggiunti."));
                } else {
                    result.hide();
                }
            }
        });
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
    private Bitmap decode(Uri u, int maxSide, boolean white) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return null;
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) {
                s *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                bm = BitmapFactory.decodeStream(in, null, o2);
            }
            if (bm == null) {
                return null;
            }
            int orient = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                orient = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) {
            }
            Matrix m = new Matrix();
            switch (orient) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    m.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    m.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    m.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    m.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    m.postScale(1, -1);
                    break;
                default:
                    break;
            }
            if (!m.isIdentity()) {
                Bitmap t = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (t != bm) {
                    bm.recycle();
                }
                bm = t;
            }
            return bm;
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ elenco immagini

    private void refresh() {
        if (toPdf) {
            listBox.removeAllViews();
            for (int i = 0; i < imgs.size(); i++) {
                listBox.addView(row(i));
            }
            if (imgs.isEmpty()) {
                TextView e = Ui.text(this, "Nessuna immagine aggiunta", 14, Ui.DIM, false);
                e.setGravity(Gravity.CENTER);
                e.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 14));
                listBox.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            }
            run.setEnabled(!imgs.isEmpty() && !busy);
        } else {
            if (pdfFile == null) {
                pick.title.setText("Scegli un PDF");
                pick.sub.setText("Tocca per selezionare il file");
            } else {
                pick.title.setText(pdfName);
                pick.sub.setText(pdfPages + (pdfPages == 1 ? " pagina" : " pagine"));
            }
            run.setEnabled(pdfFile != null && !busy);
        }
    }

    private View row(final int idx) {
        Img im = imgs.get(idx);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        r.setBackground(Ui.round(this, Ui.CARD, 14));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(im.thumb);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setBackground(Ui.round(this, Ui.BG, 8));
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(PdfImagesActivity.this, 8));
            }
        });
        r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        TextView n = Ui.text(this, (idx + 1) + ".  " + im.name, 14, Ui.TEXT, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(n);
        col.addView(Ui.text(this, FileUtil.humanSize(im.size), 12, Ui.DIM, false));
        r.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        r.addView(small("▲", idx > 0, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swap(idx, idx - 1);
            }
        }));
        r.addView(small("▼", idx < imgs.size() - 1, new View.OnClickListener() {
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
                imgs.remove(idx);
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
        Img t = imgs.get(a);
        imgs.set(a, imgs.get(b));
        imgs.set(b, t);
        refresh();
    }

    // ------------------------------------------------------------------ immagini -> PDF

    private void startToPdf() {
        if (busy || imgs.isEmpty()) {
            return;
        }
        busy = true;
        run.setEnabled(false);
        result.start("Creazione del PDF...");
        final List<Img> work = new ArrayList<>(imgs);
        final boolean a4 = pageA4;
        final int mg = margin;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    buildPdf(work, a4, mg);
                } catch (OutOfMemoryError oom) {
                    result.error("Memoria insufficiente: prova con meno immagini alla volta.");
                } catch (Throwable t) {
                    result.error("Creazione non riuscita: " + t.getMessage());
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

    private void buildPdf(List<Img> work, boolean a4, int mg) throws IOException {
        final PdfDocument doc = new PdfDocument();
        try {
            int margPt = mg == 0 ? 0 : mg == 1 ? 24 : 48;
            int n = 0;
            for (Img im : work) {
                n++;
                result.progress("Pagina " + n + " di " + work.size(), n * 100 / work.size());
                Bitmap bm = decode(im.uri, MAX_SIDE_PDF, true);
                if (bm == null) {
                    throw new IOException("impossibile leggere \"" + im.name + "\"");
                }
                int pw, ph;
                if (a4) {
                    boolean land = bm.getWidth() > bm.getHeight();
                    pw = land ? 842 : 595;
                    ph = land ? 595 : 842;
                } else {
                    float sc = 842f / Math.max(bm.getWidth(), bm.getHeight());
                    pw = Math.max(50, Math.round(bm.getWidth() * sc));
                    ph = Math.max(50, Math.round(bm.getHeight() * sc));
                }
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, n).create());
                Canvas c = page.getCanvas();
                c.drawColor(Color.WHITE);
                int m = a4 ? margPt : 0;
                float bw = pw - 2f * m, bh = ph - 2f * m;
                float s = Math.min(bw / bm.getWidth(), bh / bm.getHeight());
                float dw = bm.getWidth() * s, dh = bm.getHeight() * s;
                float l = (pw - dw) / 2f, t = (ph - dh) / 2f;
                c.drawBitmap(bm, null, new RectF(l, t, l + dw, t + dh), null);
                doc.finishPage(page);
            }
            result.progress("Salvataggio...", -1);
            String name = "Immagini_" + new java.text.SimpleDateFormat("yyMMdd_HHmmss", Locale.US)
                    .format(new java.util.Date()) + ".pdf";
            Uri u = FileUtil.save(this, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/MamoTools",
                    name, "application/pdf", new FileUtil.Writer() {
                        @Override
                        public void write(OutputStream os) throws IOException {
                            doc.writeTo(os);
                        }
                    });
            result.ok("Fatto! PDF di " + work.size() + (work.size() == 1 ? " pagina" : " pagine")
                    + " salvato in Download/MamoTools/" + name, u, "application/pdf", "Apri il PDF");
        } finally {
            doc.close();
        }
    }

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
