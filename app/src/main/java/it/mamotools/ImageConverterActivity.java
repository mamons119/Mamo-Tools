package it.mamotools;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Converte un'immagine in un altro formato. Legge tutto cio' che Android sa decodificare
 * (JPG, PNG, WEBP, GIF, BMP, ICO, HEIC/HEIF, DNG...) e scrive JPG, PNG, WEBP, BMP o ICO.
 */
public class ImageConverterActivity extends Activity {

    private static final int REQ_PICK = 41;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int C_CARD = 0xFF1A1D24, C_CARD2 = 0xFF262B35, C_ACCENT = 0xFF4DD0E1,
            C_TEXT = 0xFFECEFF4, C_DIM = 0xFF9AA3B2, C_BG = 0xFF0F1115;

    private static final String[] FORMATS = {"JPG", "PNG", "WEBP", "BMP", "ICO", "PDF"};
    private static final int[] ICO_SIZES = {16, 32, 48, 64, 128, 256};

    private Uri pickedUri;
    private String pickedName;
    private long pickedBytes = -1;
    private Uri resultUri;
    private volatile boolean busy = false;
    private int format = 0;
    private int quality = 92;
    private int icoSize = 64;
    private String originalSize = "";

    private TextView pickTitle, pickSub, inInfo, outInfo, qualityLabel, status;
    private ImageView inPreview, outPreview;
    private LinearLayout inCard, outCard, qualityBox, icoBox;
    private TextView[] fmtChips = new TextView[FORMATS.length];
    private TextView[] icoChips = new TextView[ICO_SIZES.length];
    private EditText icoEdit;
    private boolean icoEditing = false;
    private Button convertBtn, openBtn;
    private ProgressBar progress;
    private LinearLayout pickCard;
    private ImagesToPdfPanel pdfPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(36), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("Image Converter", 28, C_TEXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        Button infoBtn = btn("i", C_CARD2, C_ACCENT, v -> Ui.infoDialog(this, "Formati e trasparenza",
                "In ingresso: tutto cio' che il telefono sa aprire (JPG, PNG, WEBP, GIF, BMP, ICO, "
                + "HEIC/HEIF, RAW DNG...).\n\nLe GIF animate usano il primo fotogramma.\n\n"
                + "Gli sfondi trasparenti diventano bianchi in JPG e BMP."));
        infoBtn.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        infoBtn.setTextSize(16);
        titleRow.addView(infoBtn, new LinearLayout.LayoutParams(dp(38), dp(38)));
        root.addView(titleRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView sub = text("Converti un'immagine in JPG, PNG, WEBP, BMP o ICO.", 14, C_DIM, false);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // ---- scelta
        pickCard = card();
        pickCard.setOrientation(LinearLayout.HORIZONTAL);
        pickCard.setGravity(Gravity.CENTER_VERTICAL);
        pickCard.setClickable(true);
        pickCard.setOnClickListener(v -> pick());
        TextView ic = text("🖼", 26, C_TEXT, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(round(C_BG, 100));
        pickCard.addView(ic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout pt = new LinearLayout(this);
        pt.setOrientation(LinearLayout.VERTICAL);
        pt.setPadding(dp(14), 0, dp(8), 0);
        pickTitle = text("Scegli un'immagine", 17, C_TEXT, true);
        pickTitle.setSingleLine(true);
        pickTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pickSub = text("Dalla Galleria o dai file", 13, C_DIM, false);
        pt.addView(pickTitle);
        pt.addView(pickSub);
        pickCard.addView(pt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        pickCard.addView(text("›", 28, C_DIM, false));
        root.addView(pickCard, lp(MATCH, WRAP, 0, 0));

        // ---- anteprima ingresso
        inCard = card();
        inCard.setVisibility(View.GONE);
        inCard.addView(sectionTitle("Originale"));
        inPreview = preview();
        inCard.addView(inPreview, new LinearLayout.LayoutParams(MATCH, dp(190)));
        inInfo = text("", 13, C_TEXT, false);
        inInfo.setPadding(0, dp(10), 0, 0);
        inCard.addView(inInfo);
        root.addView(inCard, lp(MATCH, WRAP, 0, 12));

        // ---- formato
        LinearLayout fcard = card();
        fcard.addView(sectionTitle("Formato di uscita"));
        LinearLayout r1 = new LinearLayout(this);
        LinearLayout r2 = new LinearLayout(this);
        for (int i = 0; i < FORMATS.length; i++) {
            final int idx = i;
            TextView chip = chip(FORMATS[i]);
            chip.setOnClickListener(v -> selectFormat(idx));
            fmtChips[i] = chip;
            (i < 3 ? r1 : r2).addView(chip, lp(0, dp(44), i % 3 == 0 ? 0 : 8, 0, 1f));
        }
        fcard.addView(r1, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(MATCH, WRAP);
        r2p.topMargin = dp(8);
        fcard.addView(r2, r2p);

        // qualita'
        qualityBox = new LinearLayout(this);
        qualityBox.setOrientation(LinearLayout.VERTICAL);
        qualityLabel = text("", 14, C_TEXT, false);
        qualityLabel.setPadding(0, dp(18), 0, 0);
        qualityBox.addView(qualityLabel);
        SeekBar qb = new SeekBar(this);
        qb.setMax(99);
        qb.setProgress(quality - 1);
        qb.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        qb.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        qb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                quality = p + 1;
                refreshQualityLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        qualityBox.addView(qb, new LinearLayout.LayoutParams(MATCH, WRAP));
        fcard.addView(qualityBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        // dimensione ICO
        icoBox = new LinearLayout(this);
        icoBox.setOrientation(LinearLayout.VERTICAL);
        icoBox.setVisibility(View.GONE);
        TextView il = text("Dimensione dell'icona (pixel, da 1 a 256)", 14, C_TEXT, false);
        il.setPadding(0, dp(18), 0, dp(8));
        icoBox.addView(il);
        LinearLayout presets = new LinearLayout(this);
        for (int i = 0; i < ICO_SIZES.length; i++) {
            final int sz = ICO_SIZES[i];
            TextView chip = chip(String.valueOf(sz));
            chip.setTextSize(13);
            chip.setPadding(0, 0, 0, 0);
            chip.setOnClickListener(v -> setIcoSize(sz, true));
            icoChips[i] = chip;
            presets.addView(chip, lp(0, dp(40), i == 0 ? 0 : 6, 0, 1f));
        }
        icoBox.addView(presets, new LinearLayout.LayoutParams(MATCH, WRAP));
        icoEdit = new EditText(this);
        icoEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        icoEdit.setTextColor(C_TEXT);
        icoEdit.setHintTextColor(C_DIM);
        icoEdit.setHint("oppure scrivi i pixel");
        icoEdit.setBackground(round(C_CARD2, 12));
        icoEdit.setPadding(dp(14), dp(10), dp(14), dp(10));
        icoEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (icoEditing) {
                    return;
                }
                try {
                    int v = Integer.parseInt(e.toString().trim());
                    icoSize = Math.max(1, Math.min(256, v));
                } catch (Exception ex) {
                    icoSize = 0;
                }
                refreshChips();
            }
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(MATCH, WRAP);
        ep.topMargin = dp(10);
        icoBox.addView(icoEdit, ep);
        TextView in = text("L'icona e' quadrata: l'immagine viene rimpicciolita per stare "
                + "dentro il quadrato, con i lati liberi trasparenti.", 12, C_DIM, false);
        in.setPadding(0, dp(8), 0, 0);
        icoBox.addView(in);
        fcard.addView(icoBox, new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(fcard, lp(MATCH, WRAP, 0, 12));

        // ---- opzioni "Immagini → PDF" (visibili solo con il formato PDF)
        pdfPanel = new ImagesToPdfPanel(this);
        pdfPanel.view.setVisibility(View.GONE);
        root.addView(pdfPanel.view, lp(MATCH, WRAP, 0, 12));

        // ---- converti
        convertBtn = btn("Converti", C_ACCENT, Color.BLACK, v -> convert());
        convertBtn.setTextSize(16);
        convertBtn.setEnabled(false);
        root.addView(convertBtn, lp(MATCH, dp(54), 0, 16));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(C_ACCENT));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(WRAP, WRAP);
        plp.topMargin = dp(12);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, plp);

        status = text("", 14, C_TEXT, false);
        status.setPadding(0, dp(10), 0, 0);
        root.addView(status);

        // ---- risultato
        outCard = card();
        outCard.setVisibility(View.GONE);
        outCard.addView(sectionTitle("Risultato"));
        outPreview = preview();
        outCard.addView(outPreview, new LinearLayout.LayoutParams(MATCH, dp(190)));
        outInfo = text("", 13, C_TEXT, false);
        outInfo.setPadding(0, dp(10), 0, dp(12));
        outCard.addView(outInfo);
        openBtn = btn("Visualizza in Galleria", C_CARD2, C_ACCENT, v -> openResult());
        outCard.addView(openBtn, new LinearLayout.LayoutParams(MATCH, dp(48)));
        root.addView(outCard, lp(MATCH, WRAP, 0, 14));



        setContentView(scroll);
        setIcoSize(icoSize, true);
        selectFormat(0);
    }

    // ---------------------------------------------------------------- selezioni

    private void selectFormat(int idx) {
        format = idx;
        boolean pdf = idx == 5;
        pickCard.setVisibility(pdf ? View.GONE : View.VISIBLE);
        inCard.setVisibility(!pdf && pickedUri != null ? View.VISIBLE : View.GONE);
        convertBtn.setVisibility(pdf ? View.GONE : View.VISIBLE);
        outCard.setVisibility(!pdf && resultUri != null ? View.VISIBLE : View.GONE);
        status.setVisibility(pdf ? View.GONE : View.VISIBLE);
        pdfPanel.view.setVisibility(pdf ? View.VISIBLE : View.GONE);
        qualityBox.setVisibility(idx == 0 || idx == 2 ? View.VISIBLE : View.GONE);
        icoBox.setVisibility(idx == 4 ? View.VISIBLE : View.GONE);
        refreshQualityLabel();
        refreshChips();
    }

    private void setIcoSize(int px, boolean updateEdit) {
        icoSize = px;
        if (updateEdit && icoEdit != null) {
            icoEditing = true;
            icoEdit.setText(String.valueOf(px));
            icoEditing = false;
        }
        refreshChips();
    }

    private void refreshQualityLabel() {
        qualityLabel.setText("Qualita': " + quality + "%");
    }

    private void refreshChips() {
        for (int i = 0; i < fmtChips.length; i++) {
            style(fmtChips[i], i == format);
        }
        for (int i = 0; i < icoChips.length; i++) {
            style(icoChips[i], icoSize == ICO_SIZES[i]);
        }
    }

    private void style(TextView t, boolean on) {
        if (t == null) {
            return;
        }
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),
                round(on ? C_ACCENT : C_CARD2, 12), null));
        t.setTextColor(on ? Color.BLACK : C_TEXT);
    }

    // ---------------------------------------------------------------- scelta file

    private void pick() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (pdfPanel != null && pdfPanel.handleResult(requestCode, resultCode, data)) {
            return;
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        pickedUri = data.getData();
        pickedName = queryName(pickedUri);
        pickedBytes = querySize(pickedUri);
        resultUri = null;
        outCard.setVisibility(View.GONE);
        status.setText("");
        pickTitle.setText(pickedName);
        String mime = getContentResolver().getType(pickedUri);
        try {
            Bitmap bmp = decode(pickedUri, 900);
            inPreview.setImageBitmap(bmp);
            inCard.setVisibility(View.VISIBLE);
            inInfo.setText(describe(mime, originalSize, pickedBytes));
            pickSub.setText(humanSize(pickedBytes) + " · tocca per cambiare immagine");
            convertBtn.setEnabled(true);
        } catch (Throwable t) {
            inCard.setVisibility(View.GONE);
            pickSub.setText("Immagine non leggibile");
            status.setText("Questa immagine non sembra leggibile dal telefono.");
            convertBtn.setEnabled(false);
        }
    }

    private static String describe(String mime, String dims, long bytes) {
        String f = mime == null ? "immagine" : mime.replace("image/", "").toUpperCase(Locale.ROOT);
        return f + "  ·  " + dims + " px  ·  " + humanSize(bytes);
    }

    /** Decodifica; maxSide > 0 riduce (anteprima), 0 = dimensione originale. */
    private Bitmap decode(Uri uri, final int maxSide) throws IOException {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info,
                                        ImageDecoder.Source s) {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                if (maxSide > 0) {
                    originalSize = w + "x" + h;
                    int big = Math.max(w, h);
                    if (big > maxSide) {
                        d.setTargetSize(Math.max(1, w * maxSide / big),
                                Math.max(1, h * maxSide / big));
                    }
                }
            }
        });
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
        return last == null ? "immagine" : last;
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
            return "dimensione n/d";
        }
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", b / 1024f);
        }
        return String.format(Locale.US, "%.2f MB", b / 1048576f);
    }

    // ---------------------------------------------------------------- conversione

    private void convert() {
        if (busy || pickedUri == null) {
            return;
        }
        final int fmt = format;
        final int q = quality;
        if (fmt == 4 && icoSize < 1) {
            Toast.makeText(this, "Scrivi una dimensione valida per l'icona.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int icoPx = Math.max(1, Math.min(256, icoSize));
        busy = true;
        resultUri = null;
        outCard.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        convertBtn.setEnabled(false);
        status.setText("Conversione in corso...");

        final Uri src = pickedUri;
        final String base = baseName(pickedName);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = decode(src, 0);
                    final String ext;
                    final String mime;
                    switch (fmt) {
                        case 0: ext = "jpg"; mime = "image/jpeg"; break;
                        case 1: ext = "png"; mime = "image/png"; break;
                        case 2: ext = "webp"; mime = "image/webp"; break;
                        case 3: ext = "bmp"; mime = "image/bmp"; break;
                        default: ext = "ico"; mime = "image/x-icon"; break;
                    }
                    if (fmt == 0 || fmt == 3) {
                        bmp = flatten(bmp);
                    }
                    if (fmt == 4) {
                        bmp = squareFit(bmp, icoPx);
                    }
                    final Bitmap out = bmp;
                    Uri saved = saveImage(base + "." + ext, mime, new Writer() {
                        @Override
                        public void write(OutputStream os) throws IOException {
                            boolean ok = true;
                            if (fmt == 0) {
                                ok = out.compress(Bitmap.CompressFormat.JPEG, q, os);
                            } else if (fmt == 1) {
                                ok = out.compress(Bitmap.CompressFormat.PNG, 100, os);
                            } else if (fmt == 2) {
                                ok = out.compress(q >= 100
                                        ? Bitmap.CompressFormat.WEBP_LOSSLESS
                                        : Bitmap.CompressFormat.WEBP_LOSSY, q, os);
                            } else if (fmt == 3) {
                                writeBmp(out, os);
                            } else {
                                writeIco(out, os);
                            }
                            if (!ok) {
                                throw new IOException("codifica non riuscita");
                            }
                        }
                    });
                    done(saved, mime, out.getWidth() + "x" + out.getHeight());
                } catch (OutOfMemoryError oom) {
                    fail("Memoria insufficiente: l'immagine e' troppo grande per questo telefono.");
                } catch (Throwable t) {
                    String m = t.getMessage();
                    fail("Conversione non riuscita"
                            + (m != null && !m.isEmpty() ? ": " + m : " (" + t.getClass().getSimpleName() + ")"));
                }
            }
        }).start();
    }

    private void done(final Uri uri, final String mime, final String dims) {
        final long bytes = querySize(uri);
        Bitmap pv = null;
        try {
            pv = decode(uri, 900);
        } catch (Throwable ignored) {
        }
        final Bitmap preview = pv;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                resultUri = uri;
                progress.setVisibility(View.GONE);
                convertBtn.setEnabled(true);
                status.setText("Fatto! Salvata in Immagini/MamoTools.");
                if (preview != null) {
                    outPreview.setImageBitmap(preview);
                }
                StringBuilder sb = new StringBuilder(describe(mime, dims, bytes));
                if (pickedBytes > 0 && bytes > 0) {
                    double ch = 100.0 * (bytes - pickedBytes) / pickedBytes;
                    sb.append(String.format(Locale.US, "\n%s del file originale (%+.0f%%)",
                            ch <= 0 ? "Piu' leggero" : "Piu' pesante", ch));
                }
                outInfo.setText(sb.toString());
                outCard.setVisibility(View.VISIBLE);
            }
        });
    }

    private void fail(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                progress.setVisibility(View.GONE);
                convertBtn.setEnabled(pickedUri != null);
                status.setText(msg);
            }
        });
    }

    /** Appiattisce la trasparenza su sfondo bianco. */
    private static Bitmap flatten(Bitmap src) {
        if (!src.hasAlpha()) {
            return src;
        }
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, 0, 0, null);
        return out;
    }

    /** Ridimensiona per stare in un quadrato size x size, centrato su fondo trasparente. */
    private static Bitmap squareFit(Bitmap src, int size) {
        float scale = Math.min((float) size / src.getWidth(), (float) size / src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * scale));
        int h = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            Bitmap n = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) {
                cur.recycle();
            }
            cur = n;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(cur, w, h, true);
        if (cur != src && cur != scaled) {
            cur.recycle();
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** ICO con una sola immagine, memorizzata come PNG (valido da Windows Vista in poi). */
    private static void writeIco(Bitmap b, OutputStream os) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!b.compress(Bitmap.CompressFormat.PNG, 100, png)) {
            throw new IOException("codifica PNG non riuscita");
        }
        byte[] data = png.toByteArray();
        int w = b.getWidth();
        int h = b.getHeight();
        byte[] head = new byte[22];
        head[2] = 1;                 // tipo: icona
        head[4] = 1;                 // numero di immagini
        head[6] = (byte) (w >= 256 ? 0 : w);
        head[7] = (byte) (h >= 256 ? 0 : h);
        head[10] = 1;                // piani
        head[12] = 32;               // bit per pixel
        put32(head, 14, data.length);
        put32(head, 18, 22);
        os.write(head);
        os.write(data);
    }

    /** BMP a 24 bit non compresso. */
    private static void writeBmp(Bitmap b, OutputStream os) throws IOException {
        int w = b.getWidth();
        int h = b.getHeight();
        int rowSize = (w * 3 + 3) & ~3;
        int dataSize = rowSize * h;
        byte[] head = new byte[54];
        head[0] = 'B';
        head[1] = 'M';
        put32(head, 2, 54 + dataSize);
        put32(head, 10, 54);
        put32(head, 14, 40);
        put32(head, 18, w);
        put32(head, 22, h);
        head[26] = 1;
        head[28] = 24;
        put32(head, 34, dataSize);
        put32(head, 38, 2835);
        put32(head, 42, 2835);
        os.write(head);
        int[] px = new int[w];
        byte[] row = new byte[rowSize];
        for (int y = h - 1; y >= 0; y--) {
            b.getPixels(px, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int p = px[x];
                row[x * 3] = (byte) (p & 0xFF);
                row[x * 3 + 1] = (byte) ((p >> 8) & 0xFF);
                row[x * 3 + 2] = (byte) ((p >> 16) & 0xFF);
            }
            os.write(row);
        }
    }

    private static void put32(byte[] a, int off, int v) {
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >> 8);
        a[off + 2] = (byte) (v >> 16);
        a[off + 3] = (byte) (v >> 24);
    }

    private interface Writer {
        void write(OutputStream os) throws IOException;
    }

    private Uri saveImage(String name, String mime, Writer w) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MamoTools");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("impossibile creare il file in Immagini");
        }
        boolean ok = false;
        try {
            try (OutputStream raw = cr.openOutputStream(uri)) {
                if (raw == null) {
                    throw new IOException("impossibile scrivere il file");
                }
                BufferedOutputStream os = new BufferedOutputStream(raw, 64 * 1024);
                w.write(os);
                os.flush();
            }
            ok = true;
        } finally {
            if (ok) {
                ContentValues u = new ContentValues();
                u.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, u, null, null);
            } else {
                cr.delete(uri, null, null);
            }
        }
        return uri;
    }

    private void openResult() {
        if (resultUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(resultUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Nessuna app per aprire l'immagine. La trovi in Galleria.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String baseName(String name) {
        String n = name == null ? "immagine" : name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return (n.isEmpty() ? "immagine" : n) + "_conv";
    }

    // ---------------------------------------------------------------- componenti grafici

    private final class RoundButton extends Button {
        RoundButton(android.content.Context c) {
            super(c);
        }

        @Override
        public void setEnabled(boolean e) {
            super.setEnabled(e);
            setAlpha(e ? 1f : 0.4f);
        }
    }

    private Button btn(String label, int bg, int fg, View.OnClickListener l) {
        Button b = new RoundButton(this);
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
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(bg, 12), null));
        b.setOnClickListener(l);
        return b;
    }

    private TextView chip(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setClickable(true);
        style(t, false);
        return t;
    }

    private ImageView preview() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackground(round(C_BG, 14));
        v.setPadding(dp(6), dp(6), dp(6), dp(6));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(C_CARD, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        return c;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int leftDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(leftDp);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int leftDp, int topDp, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.leftMargin = dp(leftDp);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 15, C_ACCENT, true);
        t.setPadding(0, 0, 0, dp(10));
        return t;
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
