package it.mamotools;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Converte un'immagine in un altro formato. Legge tutto cio' che Android sa decodificare
 * (JPG, PNG, WEBP, GIF, BMP, ICO, HEIC/HEIF, DNG...) e scrive JPG, PNG, WEBP o BMP.
 */
public class ImageConverterActivity extends Activity {

    private static final int REQ_PICK = 41;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String[] FORMATS = {"JPG", "PNG", "WEBP", "BMP", "ICO"};

    private Uri pickedUri;
    private String pickedName;
    private Uri resultUri;
    private volatile boolean busy = false;
    private int format = 0;
    private int quality = 92;
    private int icoSize = 64;
    private EditText icoEdit;
    private LinearLayout icoBox;

    private TextView fileName, fileInfo, status, qualityLabel;
    private ImageView preview;
    private Button pickBtn, convertBtn, openBtn;
    private SeekBar qualityBar;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(40), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        root.addView(text("Image Converter", 28, R.color.text, true));
        TextView sub = text("Scegli un'immagine, il formato di uscita, e la salvo in "
                + "Immagini/MamoTools.", 14, R.color.text_dim, false);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        pickBtn = new Button(this);
        pickBtn.setText("Scegli un'immagine");
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
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(220));
        preview.setVisibility(View.GONE);
        card.addView(preview, new LinearLayout.LayoutParams(MATCH, WRAP));
        fileName = text("Nessuna immagine scelta", 16, R.color.text, true);
        fileInfo = text("", 13, R.color.text_dim, false);
        fileInfo.setPadding(0, dp(4), 0, 0);
        card.addView(fileName);
        card.addView(fileInfo);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.topMargin = dp(16);
        root.addView(card, clp);

        TextView fl = text("Formato di uscita", 14, R.color.text_dim, false);
        fl.setPadding(0, dp(20), 0, dp(4));
        root.addView(fl);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < FORMATS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(FORMATS[i]);
            rb.setTextColor(getColor(R.color.text));
            group.addView(rb, new RadioGroup.LayoutParams(0, WRAP, 1f));
        }
        group.check(1000);
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                format = id - 1000;
                updateQualityVisibility();
            }
        });
        root.addView(group, new LinearLayout.LayoutParams(MATCH, WRAP));

        qualityLabel = text("", 14, R.color.text, false);
        qualityLabel.setPadding(0, dp(16), 0, 0);
        root.addView(qualityLabel);
        qualityBar = new SeekBar(this);
        qualityBar.setMax(99);
        qualityBar.setProgress(quality - 1);
        qualityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                quality = p + 1;
                updateQualityVisibility();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        root.addView(qualityBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        updateQualityVisibility();

        icoBox = new LinearLayout(this);
        icoBox.setOrientation(LinearLayout.VERTICAL);
        icoBox.setVisibility(View.GONE);
        TextView il = text("Dimensione dell'icona (pixel, da 1 a 256)", 14, R.color.text, false);
        il.setPadding(0, dp(16), 0, dp(4));
        icoBox.addView(il);
        LinearLayout presets = new LinearLayout(this);
        for (final int sz : new int[]{16, 32, 48, 64, 128, 256}) {
            Button pb = new Button(this);
            pb.setText(String.valueOf(sz));
            pb.setAllCaps(false);
            pb.setMinWidth(0);
            pb.setMinimumWidth(0);
            pb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    icoEdit.setText(String.valueOf(sz));
                }
            });
            presets.addView(pb, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        icoBox.addView(presets, new LinearLayout.LayoutParams(MATCH, WRAP));
        icoEdit = new EditText(this);
        icoEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        icoEdit.setText(String.valueOf(icoSize));
        icoEdit.setTextColor(getColor(R.color.text));
        icoEdit.setHint("pixel");
        icoBox.addView(icoEdit, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView in = text("L'icona e' quadrata: l'immagine viene rimpicciolita per stare "
                + "dentro il quadrato, con sfondo trasparente nei lati liberi.",
                12, R.color.text_dim, false);
        icoBox.addView(in);
        root.addView(icoBox, new LinearLayout.LayoutParams(MATCH, WRAP));

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
        openBtn.setText("Apri l'immagine convertita");
        openBtn.setVisibility(View.GONE);
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openResult();
            }
        });
        root.addView(openBtn, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView note = text("In ingresso: tutto cio' che il telefono sa aprire (JPG, PNG, WEBP, "
                + "GIF, BMP, ICO, HEIC/HEIF, RAW DNG...). Le GIF animate usano il primo "
                + "fotogramma. In uscita: JPG, PNG, WEBP (con qualita' regolabile), BMP e ICO (dimensione a scelta, fino a 256 px). "
                + "Gli sfondi trasparenti diventano bianchi in JPG e BMP.",
                12, R.color.text_dim, false);
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void updateQualityVisibility() {
        boolean lossy = format == 0 || format == 2;
        qualityLabel.setVisibility(lossy ? View.VISIBLE : View.GONE);
        qualityBar.setVisibility(lossy ? View.VISIBLE : View.GONE);
        qualityLabel.setText("Qualita': " + quality + "%");
        if (icoBox != null) {
            icoBox.setVisibility(format == 4 ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ scelta

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
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        pickedUri = data.getData();
        pickedName = queryName(pickedUri);
        resultUri = null;
        openBtn.setVisibility(View.GONE);
        status.setText("");
        fileName.setText(pickedName);
        String mime = getContentResolver().getType(pickedUri);
        fileInfo.setText((mime == null ? "immagine" : mime));
        convertBtn.setEnabled(true);
        try {
            Bitmap bmp = decode(pickedUri, 800);
            preview.setImageBitmap(bmp);
            preview.setVisibility(View.VISIBLE);
            fileInfo.setText(fileInfo.getText() + " · " + originalSize + " px");
        } catch (Throwable t) {
            preview.setVisibility(View.GONE);
            status.setText("Questa immagine non sembra leggibile dal telefono.");
            convertBtn.setEnabled(false);
        }
    }

    private String originalSize = "";

    /** Decodifica l'immagine; maxSide > 0 la riduce (anteprima), 0 = dimensione originale. */
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
        status.setText("Conversione in corso...");

        final Uri src = pickedUri;
        final int fmt = format;
        final int q = quality;
        int sz = 64;
        try {
            sz = Integer.parseInt(icoEdit.getText().toString().trim());
        } catch (Exception ignored) {
        }
        final int icoPx = Math.max(1, Math.min(256, sz));
        if (fmt == 4 && sz != icoPx) {
            icoEdit.setText(String.valueOf(icoPx));
        }
        final String base = baseName(pickedName);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = decode(src, 0);
                    final String ext;
                    final String mime;
                    if (fmt == 0) {
                        ext = "jpg";
                        mime = "image/jpeg";
                    } else if (fmt == 1) {
                        ext = "png";
                        mime = "image/png";
                    } else if (fmt == 2) {
                        ext = "webp";
                        mime = "image/webp";
                    } else if (fmt == 3) {
                        ext = "bmp";
                        mime = "image/bmp";
                    } else {
                        ext = "ico";
                        mime = "image/x-icon";
                    }
                    if (fmt == 4) {
                        bmp = squareFit(bmp, icoPx);
                    }
                    if (fmt == 0 || fmt == 3) {
                        bmp = flatten(bmp);
                    }
                    final Bitmap out = bmp;
                    Uri saved = saveImage(base + "." + ext, mime, new Writer() {
                        @Override
                        public void write(OutputStream os) throws IOException {
                            boolean ok;
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
                                ok = true;
                            } else {
                                writeIco(out, os);
                                ok = true;
                            }
                            if (!ok) {
                                throw new IOException("codifica non riuscita");
                            }
                        }
                    });
                    done(saved);
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
        // riduzione a gradini per non perdere qualita'
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
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, p);
        return out;
    }

    /** ICO con una sola immagine, memorizzata come PNG (valido da Windows Vista in poi). */
    private static void writeIco(Bitmap b, OutputStream os) throws IOException {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
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

    private void done(final Uri uri) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                resultUri = uri;
                progress.setVisibility(View.GONE);
                pickBtn.setEnabled(true);
                convertBtn.setEnabled(true);
                openBtn.setVisibility(View.VISIBLE);
                status.setText("Fatto! Salvata in Immagini/MamoTools (la trovi in Galleria).");
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
