package it.mamotools;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pannello "Immagini → PDF": elenco ordinabile, formato pagina, margini. Usato da Image Converter. */
final class ImagesToPdfPanel {
    static final int REQ_PICK = 51;
    private static final int MAX_SIDE_PDF = 1600;

    private static final class Img {
        Uri uri;
        String name;
        long size;
        Bitmap thumb;
    }

    private final Activity act;
    final LinearLayout view;
    private volatile boolean busy = false;
    private final List<Img> imgs = new ArrayList<>();
    private LinearLayout listBox;
    private Button run;
    private ResultCard result;
    private boolean pageA4 = true;
    private int margin = 1;
    private TextView chA4, chFit, chM0, chM1, chM2;

    ImagesToPdfPanel(Activity a) {
        act = a;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.VERTICAL);
        build(view);
        result = new ResultCard(a);
        view.addView(result.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 14));
        refresh();
    }

    private Img primary;
    private Uri primaryUri;

    /** L'immagine scelta in alto nell'Image Converter diventa la prima pagina del PDF. */
    void setPrimary(final Uri u) {
        if (u == null || u.equals(primaryUri) || busy) {
            return;
        }
        busy = true;
        result.start("Lettura dell'immagine...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Img im = new Img();
                im.uri = u;
                im.name = FileUtil.queryName(act, u);
                im.size = FileUtil.querySize(act, u);
                im.thumb = decode(u, 160, false);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (im.thumb == null) {
                            result.error("Questo file non e' un'immagine leggibile.");
                            refresh();
                            return;
                        }
                        if (primary != null) {
                            imgs.remove(primary);
                        }
                        primary = im;
                        primaryUri = u;
                        imgs.add(0, im);
                        refresh();
                        result.hide();
                    }
                });
            }
        }).start();
    }

    private void pickFiles() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            act.startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(act, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    /** Da chiamare da onActivityResult dell'Activity; true se la richiesta era di questo pannello. */
    boolean handleResult(int req, int res, Intent data) {
        if (req != REQ_PICK) {
            return false;
        }
        if (res != Activity.RESULT_OK || data == null) {
            return true;
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
            return true;
        }
        busy = true;
        result.start("Lettura dei file...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                loadImages(uris);
            }
        }).start();
        return true;
    }

    private void refresh() {
        listBox.removeAllViews();
        for (int i = 0; i < imgs.size(); i++) {
            listBox.addView(row(i));
        }
        if (imgs.isEmpty()) {
            TextView e = Ui.text(act, "Nessuna immagine aggiunta", 14, Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(act, 22), 0, Ui.dp(act, 14));
            listBox.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        run.setEnabled(!imgs.isEmpty() && !busy);
    }

        private void build(LinearLayout root) {
        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);
        root.addView(Ui.btn(act, "＋  Aggiungi altre immagini", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFiles();
            }
        }), Ui.lp(act, Ui.MATCH, Ui.dp(act, 50), 0, 8));

        LinearLayout opts = Ui.card(act);
        root.addView(opts, Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 14));
        opts.addView(Ui.section(act, "Dimensione pagina"));
        LinearLayout r1 = new LinearLayout(act);
        chA4 = Ui.chip(act, "A4");
        chFit = Ui.chip(act, "Come l'immagine");
        r1.addView(chA4, Ui.lp(act, 0, Ui.dp(act, 44), 0, 0, 1f));
        r1.addView(chFit, Ui.lp(act, 0, Ui.dp(act, 44), 8, 0, 1f));
        opts.addView(r1);
        opts.addView(Ui.text(act, "Margine (solo A4)", 14, Ui.DIM, false), Ui.lp(act, Ui.WRAP, Ui.WRAP, 0, 14));
        LinearLayout r2 = new LinearLayout(act);
        chM0 = Ui.chip(act, "Nessuno");
        chM1 = Ui.chip(act, "Piccolo");
        chM2 = Ui.chip(act, "Medio");
        r2.addView(chM0, Ui.lp(act, 0, Ui.dp(act, 44), 0, 0, 1f));
        r2.addView(chM1, Ui.lp(act, 0, Ui.dp(act, 44), 8, 0, 1f));
        r2.addView(chM2, Ui.lp(act, 0, Ui.dp(act, 44), 8, 0, 1f));
        opts.addView(r2, Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 8));
        chA4.setOnClickListener(setPage(true));
        chFit.setOnClickListener(setPage(false));
        chM0.setOnClickListener(setMargin(0));
        chM1.setOnClickListener(setMargin(1));
        chM2.setOnClickListener(setMargin(2));
        styleToPdf();

        run = Ui.btn(act, "Crea PDF", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startToPdf();
            }
        });
        run.setTextSize(16);
        root.addView(run, Ui.lp(act, Ui.MATCH, Ui.dp(act, 54), 0, 16));
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
        Ui.styleChip(act, chA4, pageA4);
        Ui.styleChip(act, chFit, !pageA4);
        Ui.styleChip(act, chM0, margin == 0);
        Ui.styleChip(act, chM1, margin == 1);
        Ui.styleChip(act, chM2, margin == 2);
        float a = pageA4 ? 1f : 0.35f;
        chM0.setAlpha(a);
        chM1.setAlpha(a);
        chM2.setAlpha(a);
    }

    private void loadImages(List<Uri> uris) {
        final List<Img> added = new ArrayList<>();
        int bad = 0;
        for (Uri u : uris) {
            Img im = new Img();
            im.uri = u;
            im.name = FileUtil.queryName(act, u);
            im.size = FileUtil.querySize(act, u);
            im.thumb = decode(u, 160, false);
            if (im.thumb == null) {
                bad++;
                continue;
            }
            added.add(im);
        }
        final int fBad = bad;
        act.runOnUiThread(new Runnable() {
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

    private Bitmap decode(Uri u, int maxSide, boolean white) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = act.getContentResolver().openInputStream(u)) {
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
            try (InputStream in = act.getContentResolver().openInputStream(u)) {
                bm = BitmapFactory.decodeStream(in, null, o2);
            }
            if (bm == null) {
                return null;
            }
            int orient = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = act.getContentResolver().openInputStream(u)) {
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

    private View row(final int idx) {
        Img im = imgs.get(idx);
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 6), Ui.dp(act, 8));
        r.setBackground(Ui.round(act, Ui.CARD, 14));
        ImageView iv = new ImageView(act);
        iv.setImageBitmap(im.thumb);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setBackground(Ui.round(act, Ui.BG, 8));
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(act, 8));
            }
        });
        r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(act, 52), Ui.dp(act, 52)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 4), 0);
        TextView n = Ui.text(act, (idx + 1) + ".  " + im.name, 14, Ui.TEXT, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(n);
        col.addView(Ui.text(act, FileUtil.humanSize(im.size), 12, Ui.DIM, false));
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
        lp.bottomMargin = Ui.dp(act, 8);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView small(String s, boolean enabled, View.OnClickListener l) {
        TextView t = Ui.text(act, s, 16, enabled ? Ui.TEXT : Ui.CARD2, false);
        t.setGravity(Gravity.CENTER);
        if (enabled) {
            t.setOnClickListener(l);
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)));
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
                    act.runOnUiThread(new Runnable() {
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
            Uri u = FileUtil.save(act, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/MamoTools",
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
}
