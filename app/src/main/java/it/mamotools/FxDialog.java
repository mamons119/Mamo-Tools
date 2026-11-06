package it.mamotools;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Finestra per creare o modificare un effetto o un testo del Video Editor. */
final class FxDialog {
    interface Callback {
        void onSave(VideoFx.Item item);

        void onDelete();
    }

    private final Context ctx;
    private final VideoFx.Item it;
    private final long durMs;
    private final int vw, vh;
    private final Callback cb;
    private final int extraRot;
    private final Dialog dlg;
    private TextView timeText;
    private SeekBar sbStart, sbEnd;
    private Preview preview;
    private TextView[] fontChips;
    private final Handler h = new Handler(Looper.getMainLooper());

    static void show(Context ctx, VideoFx.Item initial, boolean editing, Uri video, long durMs, long cursorMs,
                     int vw, int vh, int extraRot, Callback cb) {
        new FxDialog(ctx, initial, editing, video, durMs, cursorMs, vw, vh, extraRot, cb);
    }

    private int dp(int v) {
        return Ui.dp(ctx, v);
    }

    private FxDialog(Context ctx, VideoFx.Item initial, boolean editing, Uri video, long durMs, long cursorMs,
                     int vw, int vh, int extraRot, Callback cb) {
        this.ctx = ctx;
        this.extraRot = extraRot;
        this.it = initial.copy();
        this.durMs = durMs;
        this.vw = vw;
        this.vh = vh;
        this.cb = cb;
        dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(root, new ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP));

        root.addView(Ui.text(ctx, it.isText ? "Testo" : "Effetto", 20, Ui.TEXT, true));

        if (it.isText) {
            buildText(root, video);
        } else {
            buildEffect(root);
        }

        // ---- intervallo
        root.addView(Ui.section(ctx, "Quando"), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 16));
        timeText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        root.addView(timeText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 6));
        int max = (int) Math.max(1, durMs / 100);
        if (it.endUs <= it.startUs) {
            it.startUs = Math.min(cursorMs, Math.max(0, durMs - 500)) * 1000;
            it.endUs = Math.min(durMs, cursorMs + 3000) * 1000;
        }
        root.addView(Ui.text(ctx, "Inizio", 12, Ui.DIM, false));
        sbStart = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    it.startUs = p * 100000L;
                    if (it.endUs < it.startUs + 100000) {
                        it.endUs = Math.min(durMs * 1000, it.startUs + 100000);
                        sbEnd.setProgress((int) (it.endUs / 100000));
                    }
                    timesChanged();
                }
            }
        });
        sbStart.setMax(max);
        sbStart.setProgress((int) (it.startUs / 100000));
        root.addView(sbStart);
        root.addView(Ui.text(ctx, "Fine", 12, Ui.DIM, false));
        sbEnd = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    it.endUs = Math.max(p * 100000L, it.startUs + 100000);
                    timesChanged();
                }
            }
        });
        sbEnd.setMax(max);
        sbEnd.setProgress((int) (it.endUs / 100000));
        root.addView(sbEnd);
        LinearLayout cur = new LinearLayout(ctx);
        final long cm = cursorMs;
        cur.addView(Ui.btn(ctx, "Inizio = cursore", Ui.CARD2, Ui.TEXT, v -> {
            it.startUs = cm * 1000;
            if (it.endUs <= it.startUs) {
                it.endUs = Math.min(durMs, cm + 3000) * 1000;
            }
            sbStart.setProgress((int) (it.startUs / 100000));
            sbEnd.setProgress((int) (it.endUs / 100000));
            timesChanged();
        }), new LinearLayout.LayoutParams(0, dp(42), 1f));
        cur.addView(Ui.btn(ctx, "Fine = cursore", Ui.CARD2, Ui.TEXT, v -> {
            if (cm * 1000 > it.startUs) {
                it.endUs = cm * 1000;
                sbEnd.setProgress((int) (it.endUs / 100000));
                timesChanged();
            }
        }), Ui.lp(ctx, 0, dp(42), 8, 0, 1f));
        root.addView(cur, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        timesChanged();

        // ---- bottoni
        LinearLayout bt = new LinearLayout(ctx);
        bt.addView(Ui.btn(ctx, "Annulla", Ui.CARD2, Ui.TEXT, v -> dlg.dismiss()),
                new LinearLayout.LayoutParams(0, dp(48), 1f));
        if (editing) {
            bt.addView(Ui.btn(ctx, "Elimina", Ui.CARD2, Ui.DANGER, v -> {
                dlg.dismiss();
                cb.onDelete();
            }), Ui.lp(ctx, 0, dp(48), 8, 0, 1f));
        }
        bt.addView(Ui.btn(ctx, "Salva", Ui.ACCENT, Color.BLACK, v -> {
            if (it.isText && (it.text == null || it.text.trim().isEmpty())) {
                it.text = "Testo";
            }
            dlg.dismiss();
            cb.onSave(it);
        }), Ui.lp(ctx, 0, dp(48), 8, 0, 1.3f));
        root.addView(bt, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 20));

        dlg.setContentView(sv);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dlg.show();
    }

    private void timesChanged() {
        timeText.setText(fmt(it.startUs / 1000) + "  →  " + fmt(it.endUs / 1000)
                + "   (" + String.format(java.util.Locale.US, "%.1f", (it.endUs - it.startUs) / 1e6) + " s)");
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    // ------------------------------------------------------------------ effetto

    private TextView[] fxChips;
    private TextView crtChip;
    private LinearLayout kBox, crtBox;
    private TextView kText;

    private void buildEffect(LinearLayout root) {
        root.addView(Ui.section(ctx, "Tipo di effetto"), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12));
        int n = VideoFx.EFFECT_NAMES.length;
        fxChips = new TextView[n];
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(ctx);
                root.addView(row, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 6));
            }
            final int idx = i;
            fxChips[i] = Ui.chip(ctx, VideoFx.EFFECT_ICONS[i] + "  " + VideoFx.EFFECT_NAMES[i]);
            fxChips[i].setTextSize(13);
            fxChips[i].setOnClickListener(v -> {
                it.effect = idx;
                styleFx();
            });
            row.addView(fxChips[i], Ui.lp(ctx, 0, dp(44), i % 2 == 0 ? 0 : 6, 0, 1f));
        }
        kBox = new LinearLayout(ctx);
        kBox.setOrientation(LinearLayout.VERTICAL);
        kText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        kBox.addView(kText);
        SeekBar k = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                it.intensity = p / 100f;
                kText.setText("Intensita': " + p + "%");
            }
        });
        k.setMax(100);
        k.setProgress(Math.round(it.intensity * 100));
        kBox.addView(k);
        root.addView(kBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 14));
        crtBox = new LinearLayout(ctx);
        crtBox.setOrientation(LinearLayout.VERTICAL);
        crtChip = Ui.chip(ctx, "");
        crtChip.setTextSize(13);
        crtChip.setOnClickListener(v -> {
            it.crtWarp = !it.crtWarp;
            styleFx();
        });
        crtBox.addView(crtChip, new LinearLayout.LayoutParams(Ui.MATCH, dp(44)));
        crtBox.addView(Ui.text(ctx, "Le linee orizzontali e i puntini RGB verticali dello schermo ci sono sempre. "
                + "Le distorsioni aggiungono: schermo bombato con angoli scuri, colori leggermente sfasati, "
                + "tremolio delle righe, fascia scura che scorre, sfarfallio e disturbo. L'anteprima mostra "
                + "linee, puntini e curvatura; tremolio e disturbo si vedono nel video esportato.", 12, Ui.DIM,
                false), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        root.addView(crtBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4));
        styleFx();
    }

    private void styleFx() {
        for (int i = 0; i < fxChips.length; i++) {
            Ui.styleChip(ctx, fxChips[i], i == it.effect);
        }
        kBox.setVisibility(VideoFx.EFFECT_HAS_K[it.effect] ? View.VISIBLE : View.GONE);
        kText.setText("Intensita': " + Math.round(it.intensity * 100) + "%");
        crtBox.setVisibility(it.effect == 12 ? View.VISIBLE : View.GONE);
        crtChip.setText(it.crtWarp ? "Distorsioni CRT: attive" : "Distorsioni CRT: disattivate");
        Ui.styleChip(ctx, crtChip, it.crtWarp);
        if (it.effect == 8 || it.effect == 9) {
            // dissolvenze: durata = intervallo scelto
        }
    }

    // ------------------------------------------------------------------ testo

    private TextView chBold, chItalic, chOutline, chShadow, chBox, sizeText;
    private LinearLayout[] swatchViews;

    private void buildText(LinearLayout root, Uri video) {
        preview = new Preview(ctx);
        root.addView(preview, Ui.lp(ctx, Ui.MATCH, dp(200), 0, 10));
        root.addView(Ui.text(ctx, "Trascina il testo nell'anteprima per posizionarlo", 11, Ui.DIM, false));
        loadFrame(video);

        EditText et = new EditText(ctx);
        et.setText(it.text);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.DIM);
        et.setHint("Scrivi il testo");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setBackground(Ui.round(ctx, Ui.CARD2, 12));
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                it.text = e.toString();
                preview.update();
            }
        });
        root.addView(et, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12));

        root.addView(Ui.section(ctx, "Carattere"), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 16));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout fr = new LinearLayout(ctx);
        hs.addView(fr);
        int nf = VideoFx.FONT_NAMES.length;
        fontChips = new TextView[nf];
        for (int i = 0; i < nf; i++) {
            final int idx = i;
            fontChips[i] = Ui.chip(ctx, VideoFx.FONT_NAMES[i]);
            fontChips[i].setPadding(dp(14), 0, dp(14), 0);
            fontChips[i].setTypeface(Typeface.create(VideoFx.FONT_FAMILY[i], Typeface.BOLD));
            fontChips[i].setOnClickListener(v -> {
                it.font = idx;
                styleText();
                preview.update();
            });
            fr.addView(fontChips[i], Ui.lp(ctx, Ui.WRAP, dp(42), i == 0 ? 0 : 6, 0));
        }
        root.addView(hs, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));

        LinearLayout st = new LinearLayout(ctx);
        chBold = Ui.chip(ctx, "G");
        chItalic = Ui.chip(ctx, "C");
        chOutline = Ui.chip(ctx, "Contorno");
        chShadow = Ui.chip(ctx, "Ombra");
        chBox = Ui.chip(ctx, "Sfondo");
        chBold.setTypeface(Typeface.DEFAULT_BOLD);
        chItalic.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        chBold.setOnClickListener(v -> {
            it.bold = !it.bold;
            styleText();
            preview.update();
        });
        chItalic.setOnClickListener(v -> {
            it.italic = !it.italic;
            styleText();
            preview.update();
        });
        chOutline.setOnClickListener(v -> {
            it.outline = !it.outline;
            styleText();
            preview.update();
        });
        chShadow.setOnClickListener(v -> {
            it.shadow = !it.shadow;
            styleText();
            preview.update();
        });
        chBox.setOnClickListener(v -> {
            it.box = !it.box;
            styleText();
            preview.update();
        });
        st.addView(chBold, new LinearLayout.LayoutParams(dp(44), dp(42)));
        st.addView(chItalic, Ui.lp(ctx, dp(44), dp(42), 6, 0));
        st.addView(chOutline, Ui.lp(ctx, 0, dp(42), 6, 0, 1f));
        st.addView(chShadow, Ui.lp(ctx, 0, dp(42), 6, 0, 1f));
        st.addView(chBox, Ui.lp(ctx, 0, dp(42), 6, 0, 1f));
        root.addView(st, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));

        sizeText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        root.addView(sizeText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 14));
        SeekBar sz = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                it.sizeFrac = (3 + p) / 100f;
                sizeText.setText("Dimensione: " + (3 + p) + "% dell'altezza del video");
                if (preview != null) {
                    preview.update();
                }
            }
        });
        sz.setMax(27);
        sz.setProgress(Math.max(0, Math.min(27, Math.round(it.sizeFrac * 100) - 3)));
        sizeText.setText("Dimensione: " + Math.round(it.sizeFrac * 100) + "% dell'altezza del video");
        root.addView(sz);

        root.addView(Ui.text(ctx, "Colore", 14, Ui.TEXT, false), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12));
        LinearLayout sw = new LinearLayout(ctx);
        int ns = VideoFx.SWATCHES.length;
        swatchViews = new LinearLayout[ns];
        for (int i = 0; i < ns; i++) {
            final int col = VideoFx.SWATCHES[i];
            LinearLayout d = new LinearLayout(ctx);
            d.setOnClickListener(v -> {
                it.color = col;
                styleText();
                preview.update();
            });
            swatchViews[i] = d;
            sw.addView(d, Ui.lp(ctx, 0, dp(32), i == 0 ? 0 : 5, 0, 1f));
        }
        root.addView(sw, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        root.addView(Ui.text(ctx, "Tinta personalizzata", 12, Ui.DIM, false), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        SeekBar hue = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    it.color = Color.HSVToColor(new float[]{p, 1f, 1f});
                    styleText();
                    preview.update();
                }
            }
        });
        hue.setMax(360);
        float[] hsv = new float[3];
        Color.colorToHSV(it.color, hsv);
        hue.setProgress((int) hsv[0]);
        root.addView(hue);
        styleText();
    }

    private void styleText() {
        for (int i = 0; i < fontChips.length; i++) {
            Ui.styleChip(ctx, fontChips[i], i == it.font);
        }
        Ui.styleChip(ctx, chBold, it.bold);
        Ui.styleChip(ctx, chItalic, it.italic);
        Ui.styleChip(ctx, chOutline, it.outline);
        Ui.styleChip(ctx, chShadow, it.shadow);
        Ui.styleChip(ctx, chBox, it.box);
        for (int i = 0; i < swatchViews.length; i++) {
            int col = VideoFx.SWATCHES[i];
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(col);
            g.setCornerRadius(dp(8));
            g.setStroke(dp(it.color == col ? 3 : 1), it.color == col ? Ui.ACCENT : 0x55FFFFFF);
            swatchViews[i].setBackground(g);
        }
    }

    private void loadFrame(Uri video) {
        final long at = it.startUs;
        new Thread(() -> {
            Bitmap b = null;
            MediaMetadataRetriever mr = new MediaMetadataRetriever();
            try {
                mr.setDataSource(ctx, video);
                b = mr.getScaledFrameAtTime(Math.max(0, at), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720);
            } catch (Exception ignored) {
            } finally {
                try {
                    mr.release();
                } catch (Exception ignored) {
                }
            }
            if (b != null && extraRot != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(extraRot);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            final Bitmap fb = b;
            h.post(() -> {
                if (preview != null) {
                    preview.frame = fb;
                    preview.invalidate();
                }
            });
        }).start();
    }

    /** Anteprima WYSIWYG con trascinamento del testo. */
    private final class Preview extends View {
        Bitmap frame, textBmp;
        String sig = "";
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        RectF rect = new RectF();

        Preview(Context c) {
            super(c);
        }

        void update() {
            sig = "";
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            if (vw <= 0 || vh <= 0) {
                return;
            }
            float s = Math.min(getWidth() / (float) vw, getHeight() / (float) vh);
            float rw = vw * s, rh = vh * s;
            float l = (getWidth() - rw) / 2f, t = (getHeight() - rh) / 2f;
            rect.set(l, t, l + rw, t + rh);
            Paint bg = new Paint();
            bg.setColor(0xFF222222);
            c.drawRect(rect, bg);
            if (frame != null) {
                c.drawBitmap(frame, null, rect, p);
            }
            String sg = TextOverlayView.sig(it, Math.round(rh));
            if (textBmp == null || !sg.equals(sig)) {
                textBmp = VideoFx.render(it, Math.round(rw), Math.round(rh), 0);
                sig = sg;
            }
            if (textBmp != null) {
                c.drawBitmap(textBmp, l + it.x * rw - textBmp.getWidth() / 2f,
                        t + it.y * rh - textBmp.getHeight() / 2f, p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (rect.width() <= 0) {
                return false;
            }
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                it.x = Math.max(0.02f, Math.min(0.98f, (e.getX() - rect.left) / rect.width()));
                it.y = Math.max(0.02f, Math.min(0.98f, (e.getY() - rect.top) / rect.height()));
                invalidate();
            }
            return true;
        }
    }
}
