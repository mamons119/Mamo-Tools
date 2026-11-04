package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Lettore di documenti Word (.docx) senza conversioni: il documento viene letto e mostrato
 * direttamente, con testo selezionabile, zoom (pizzico o pulsanti) e modalita' notte.
 * Risponde anche a "Apri con" per i file .docx.
 */
public class WordReaderActivity extends Activity {
    private static final int REQ_PICK = 71;
    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final Handler h = new Handler(Looper.getMainLooper());
    private DocModel model;
    private String docName = "";
    private float zoom = 1f;
    private boolean night = false;
    private volatile boolean busy = false;
    private int buildToken = 0;

    private TextView title, empty;
    private LinearLayout page;
    private PinchScroll scroll;
    private Button warnBtn;
    private TextView busyText;

    /** ScrollView che riconosce il pizzico per lo zoom. */
    private final class PinchScroll extends ScrollView {
        private final ScaleGestureDetector det;
        private float factor = 1f;

        PinchScroll(Activity a) {
            super(a);
            det = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    factor *= d.getScaleFactor();
                    page.setScaleX(Math.max(0.5f, Math.min(3f, factor)));
                    page.setScaleY(Math.max(0.5f, Math.min(3f, factor)));
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector d) {
                    page.setScaleX(1f);
                    page.setScaleY(1f);
                    float f = factor;
                    factor = 1f;
                    setZoom(zoom * f);
                }
            });
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            det.onTouchEvent(e);
            if (det.isInProgress()) {
                MotionEvent c = MotionEvent.obtain(e);
                c.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c);
                c.recycle();
                return true;
            }
            return super.dispatchTouchEvent(e);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(0, Ui.dp(this, 30), 0, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 8));
        title = Ui.text(this, "Leggi Word", 17, Ui.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        bar.addView(barBtn("A−", v -> setZoom(zoom / 1.15f)));
        bar.addView(barBtn("A+", v -> setZoom(zoom * 1.15f)));
        bar.addView(barBtn("☾", v -> {
            night = !night;
            render();
        }));
        bar.addView(barBtn("📂", v -> pickFile()));
        root.addView(bar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        FrameLayout body = new FrameLayout(this);
        scroll = new PinchScroll(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 24));
        warnBtn = Ui.btn(this, "", Ui.CARD2, Ui.ACCENT, v -> showWarnings());
        warnBtn.setTextSize(12);
        warnBtn.setVisibility(View.GONE);
        col.addView(warnBtn, Ui.lp(this, Ui.MATCH, Ui.dp(this, 40), 0, 0));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPivotX(0f);
        col.addView(page, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        scroll.addView(col, new ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP));
        body.addView(scroll, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        empty = Ui.text(this, "Scegli un documento Word (.docx)\nTocca per aprire", 16, Ui.DIM, false);
        empty.setGravity(Gravity.CENTER);
        empty.setOnClickListener(v -> pickFile());
        body.addView(empty, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        busyText = Ui.text(this, "", 14, Ui.TEXT, true);
        busyText.setGravity(Gravity.CENTER);
        busyText.setBackground(Ui.round(this, Ui.CARD, 16));
        busyText.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 16));
        busyText.setVisibility(View.GONE);
        body.addView(busyText, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));

        root.addView(body, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);

        Intent in = getIntent();
        if (in != null && in.getData() != null) {
            load(in.getData());
        } else if (b == null) {
            pickFile();
        }
    }

    private Button barBtn(String t, View.OnClickListener l) {
        Button x = Ui.btn(this, t, Ui.CARD2, Ui.TEXT, l);
        x.setTextSize(15);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40));
        p.leftMargin = Ui.dp(this, 6);
        x.setLayoutParams(p);
        return x;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            load(intent.getData());
        }
    }

    private void pickFile() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{MIME_DOCX});
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
            load(data.getData());
        }
    }

    // ------------------------------------------------------------------ caricamento

    private void load(final Uri uri) {
        if (busy) {
            return;
        }
        busy = true;
        docName = FileUtil.queryName(this, uri);
        title.setText(docName);
        busyText.setText("Apertura del documento...");
        busyText.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(getCacheDir(), "word");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File in = new File(dir, "input.docx");
                    FileUtil.copyToFile(WordReaderActivity.this, uri, in);
                    final DocModel m = DocxParser.parse(in);
                    //noinspection ResultOfMethodCallIgnored
                    in.delete();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            model = m;
                            empty.setVisibility(View.GONE);
                            warnBtn.setVisibility(m.warnings.isEmpty() ? View.GONE : View.VISIBLE);
                            warnBtn.setText("⚠  " + m.warnings.size()
                                    + (m.warnings.size() == 1 ? " elemento non visualizzabile" : " elementi non visualizzabili")
                                    + "  ›");
                            scroll.scrollTo(0, 0);
                            render();
                        }
                    });
                } catch (Throwable t) {
                    final String msg = t instanceof OutOfMemoryError ? "memoria insufficiente" : t.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            busyText.setVisibility(View.GONE);
                            Ui.infoDialog(WordReaderActivity.this, "Documento non aperto",
                                    "Non riesco ad aprire questo documento"
                                            + (msg != null && !msg.isEmpty() ? ": " + msg : "."));
                        }
                    });
                }
            }
        }).start();
    }

    private void showWarnings() {
        if (model == null || model.warnings.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("Questo documento contiene elementi che l'app non riesce a disegnare:\n");
        for (String w : model.warnings) {
            sb.append("\n• ").append(w);
        }
        sb.append("\n\nIl resto (testo, tabelle, immagini, intestazioni, note) e' mostrato per intero.");
        Ui.infoDialog(this, "Elementi non visualizzabili", sb.toString());
    }

    private void setZoom(float z) {
        float nz = Math.max(0.6f, Math.min(3.5f, z));
        if (Math.abs(nz - zoom) < 0.01f) {
            return;
        }
        zoom = nz;
        render();
    }

    // ------------------------------------------------------------------ disegno del documento

    private void render() {
        if (model == null) {
            return;
        }
        final int token = ++buildToken;
        final int scrollY = scroll.getScrollY();
        final int oldH = Math.max(1, page.getHeight());
        page.removeAllViews();
        page.setBackground(Ui.round(this, night ? 0xFF1B1E24 : 0xFFFFFFFF, 12));
        int pad = Ui.dp(this, 16);
        page.setPadding(pad, pad, pad, pad);
        busyText.setText("Impaginazione...");
        busyText.setVisibility(View.VISIBLE);

        final float ppp = getResources().getDisplayMetrics().density * 1.25f * zoom;   // pixel per punto
        final int avail = getResources().getDisplayMetrics().widthPixels - 2 * Ui.dp(this, 10) - 2 * pad;
        final java.util.ArrayList<DocModel.Block> all = new java.util.ArrayList<>();
        if (!model.header.isEmpty()) {
            all.addAll(model.header);
        }
        all.addAll(model.blocks);
        final int firstBody = model.header.size();
        if (!model.footer.isEmpty()) {
            all.addAll(model.footer);
        }
        final int footStart = firstBody + model.blocks.size();
        final int[] idx = {0};
        h.post(new Runnable() {
            @Override
            public void run() {
                if (token != buildToken) {
                    return;
                }
                int end = Math.min(all.size(), idx[0] + 40);
                for (; idx[0] < end; idx[0]++) {
                    int i = idx[0];
                    boolean dim = i < firstBody || i >= footStart;
                    if (i == footStart && footStart < all.size()) {
                        page.addView(divider(0x33888888), Ui.lp(WordReaderActivity.this, Ui.MATCH, 2, 0, 14));
                    }
                    View v = blockView(all.get(i), ppp, avail, dim);
                    if (v != null) {
                        page.addView(v);
                    }
                    if (i == firstBody - 1 && firstBody > 0) {
                        page.addView(divider(0x33888888), Ui.lp(WordReaderActivity.this, Ui.MATCH, 2, 0, 8));
                    }
                }
                if (idx[0] < all.size()) {
                    h.post(this);
                } else {
                    busyText.setVisibility(View.GONE);
                    page.post(new Runnable() {
                        @Override
                        public void run() {
                            // mantiene (circa) il punto di lettura dopo un cambio di zoom
                            scroll.scrollTo(0, (int) ((long) scrollY * Math.max(1, page.getHeight()) / oldH));
                        }
                    });
                }
            }
        });
    }

    private View divider(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        return v;
    }

    private View blockView(DocModel.Block b, float ppp, int availPx, boolean dim) {
        View v;
        if (b instanceof DocModel.Para) {
            v = paraView((DocModel.Para) b, ppp, availPx);
        } else if (b instanceof DocModel.Table) {
            v = tableView((DocModel.Table) b, ppp, availPx);
        } else {
            return null;
        }
        if (dim) {
            v.setAlpha(0.6f);
        }
        return v;
    }

    private int textColor(int c) {
        if (night && (c == 0xFF000000 || Color.alpha(c) == 0)) {
            return 0xFFE6E8EC;
        }
        if (!night && c == 0xFFFFFFFF) {
            return 0xFF000000;
        }
        return c;
    }

    private static String family(String font) {
        String f = font == null ? "" : font.toLowerCase(Locale.ROOT);
        if (f.contains("courier") || f.contains("consolas") || f.contains("mono")) {
            return "monospace";
        }
        if (f.contains("times") || f.contains("georgia") || f.contains("garamond")
                || f.contains("cambria") || f.contains("palatino") || f.contains("book")
                || f.contains("serif") && !f.contains("sans")) {
            return "serif";
        }
        return "sans-serif";
    }

    private View paraView(DocModel.Para p, float ppp, int availPx) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (p.pageBreakBefore) {
            box.addView(divider(0x44888888), Ui.lp(this, Ui.MATCH, 2, 0, 10));
        }
        boolean imageOnly = p.runs.isEmpty() && !p.images.isEmpty()
                && (p.listPrefix == null || p.listPrefix.isEmpty());
        if (!imageOnly) {
            box.addView(textView(p, ppp, availPx));
        }
        for (DocModel.Img img : p.images) {
            View iv = imageView(img, ppp, availPx, p.align);
            if (iv != null) {
                box.addView(iv);
            }
        }
        if (p.pageBreakAfter) {
            box.addView(divider(0x44888888), Ui.lp(this, Ui.MATCH, 2, 0, 10));
        }
        return box;
    }

    private TextView textView(DocModel.Para p, float ppp, int availPx) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        float firstSize = p.runs.isEmpty() ? p.defaultSize : p.runs.get(0).size;
        if (p.listPrefix != null && !p.listPrefix.isEmpty()) {
            sb.append(p.listPrefix);
            sb.setSpan(new AbsoluteSizeSpan(Math.round(firstSize * ppp), false), 0, sb.length(), flags);
            sb.setSpan(new ForegroundColorSpan(textColor(0xFF000000)), 0, sb.length(), flags);
        }
        for (DocModel.Run r : p.runs) {
            String t = r.text.replace("\u0001", "").replace("\u0002", "");
            int s = sb.length();
            sb.append(t);
            int e = sb.length();
            if (e == s) {
                continue;
            }
            sb.setSpan(new AbsoluteSizeSpan(Math.max(4, Math.round(r.size * ppp)), false), s, e, flags);
            sb.setSpan(new TypefaceSpan(family(r.font)), s, e, flags);
            if (r.bold && r.italic) {
                sb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), s, e, flags);
            } else if (r.bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, e, flags);
            } else if (r.italic) {
                sb.setSpan(new StyleSpan(Typeface.ITALIC), s, e, flags);
            }
            sb.setSpan(new ForegroundColorSpan(textColor(r.color)), s, e, flags);
            if (r.underline) {
                sb.setSpan(new UnderlineSpan(), s, e, flags);
            }
            if (r.strike) {
                sb.setSpan(new StrikethroughSpan(), s, e, flags);
            }
        }
        if (sb.length() == 0) {
            sb.append(' ');
        }
        int maxIndent = availPx / 2;
        int rest = Math.min(maxIndent, Math.max(0, Math.round(p.indentLeft * ppp)));
        int first = Math.min(maxIndent, Math.max(0, Math.round((p.indentLeft + p.indentFirst) * ppp)));
        if (rest > 0 || first > 0) {
            sb.setSpan(new LeadingMarginSpan.Standard(first, rest), 0, sb.length(), flags);
        }
        TextView tv = new TextView(this);
        tv.setText(sb);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(4f, p.defaultSize * ppp));
        tv.setTextColor(textColor(0xFF000000));
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0, Math.max(1f, p.lineMult));
        tv.setIncludeFontPadding(false);
        switch (p.align) {
            case 1:
                tv.setGravity(Gravity.CENTER_HORIZONTAL);
                break;
            case 2:
                tv.setGravity(Gravity.END);
                break;
            case 3:
                tv.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
                break;
            default:
                break;
        }
        int top = Math.min(Ui.dp(this, 40), Math.round(p.before * ppp));
        int bottom = Math.min(Ui.dp(this, 40), Math.round(p.after * ppp));
        tv.setPadding(0, top, 0, bottom + Ui.dp(this, 1));
        return tv;
    }

    private View imageView(DocModel.Img img, float ppp, int availPx, int align) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(img.data, 0, img.data.length, o);
            if (o.outWidth <= 0) {
                return null;
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= 1600) {
                s *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeByteArray(img.data, 0, img.data.length, o2);
            if (bm == null) {
                return null;
            }
            float wPx = img.w > 0 ? img.w * ppp : bm.getWidth();
            float hPx = img.h > 0 ? img.h * ppp : wPx * bm.getHeight() / bm.getWidth();
            if (wPx > availPx) {
                hPx = hPx * availPx / wPx;
                wPx = availPx;
            }
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(wPx), Math.round(hPx));
            lp.gravity = align == 1 ? Gravity.CENTER_HORIZONTAL : align == 2 ? Gravity.END : Gravity.START;
            lp.topMargin = Ui.dp(this, 4);
            lp.bottomMargin = Ui.dp(this, 4);
            iv.setLayoutParams(lp);
            return iv;
        } catch (Throwable t) {
            return null;
        }
    }

    private View tableView(DocModel.Table t, float ppp, int availPx) {
        LinearLayout tbl = new LinearLayout(this);
        tbl.setOrientation(LinearLayout.VERTICAL);
        int line = night ? 0x66FFFFFF : 0x66000000;
        for (List<DocModel.Cell> row : t.rows) {
            LinearLayout r = new LinearLayout(this);
            int ci = 0;
            for (DocModel.Cell c : row) {
                float wgt = 0f;
                for (int k = 0; k < c.span; k++) {
                    wgt += t.cols != null && ci + k < t.cols.length && t.cols[ci + k] > 0 ? t.cols[ci + k] : 1f;
                }
                ci += c.span;
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                int cp = Ui.dp(this, 5);
                cell.setPadding(cp, cp, cp, cp);
                if (t.borders) {
                    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                    g.setStroke(Math.max(1, Ui.dp(this, 1) / 2), line);
                    cell.setBackground(g);
                }
                for (DocModel.Para p : c.paras) {
                    cell.addView(textView(p, ppp, Math.max(availPx / 3, Math.round(availPx * wgt / 600f))));
                }
                r.addView(cell, new LinearLayout.LayoutParams(0, Ui.MATCH, wgt));
            }
            tbl.addView(r, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = Ui.dp(this, 6);
        lp.bottomMargin = Ui.dp(this, 6);
        tbl.setLayoutParams(lp);
        return tbl;
    }
}
