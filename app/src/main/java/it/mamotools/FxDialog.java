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
            it.endUs = endFor((cursorMs + 3000) * 1000);
        }
        if (!it.isText) {
            loadFrame(video);
        }
        root.addView(Ui.text(ctx, "Inizio", 12, Ui.DIM, false));
        sbStart = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    it.startUs = p * 100000L;
                    if (it.endUs < it.startUs + 100000) {
                        it.endUs = endFor(it.startUs + 100000);
                        sbEnd.setProgress(endProg());
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
                    long e = p >= sbEnd.getMax() ? durMs * 1000 : p * 100000L;
                    it.endUs = endFor(Math.max(e, it.startUs + 100000));
                    timesChanged();
                }
            }
        });
        sbEnd.setMax(max);
        sbEnd.setProgress(endProg());
        root.addView(sbEnd);
        LinearLayout cur = new LinearLayout(ctx);
        final long cm = cursorMs;
        cur.addView(Ui.btn(ctx, "Inizio = cursore", Ui.CARD2, Ui.TEXT, v -> {
            it.startUs = cm * 1000;
            if (it.endUs <= it.startUs) {
                it.endUs = endFor((cm + 3000) * 1000);
            }
            sbStart.setProgress((int) (it.startUs / 100000));
            sbEnd.setProgress(endProg());
            timesChanged();
        }), new LinearLayout.LayoutParams(0, dp(Ui.H_BTN), 1f));
        cur.addView(Ui.btn(ctx, "Fine = cursore", Ui.CARD2, Ui.TEXT, v -> {
            if (cm * 1000 > it.startUs) {
                it.endUs = endFor(cm * 1000);
                sbEnd.setProgress(endProg());
                timesChanged();
            }
        }), Ui.lp(ctx, 0, dp(Ui.H_BTN), 8, 0, 1f));
        root.addView(cur, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        timesChanged();

        // ---- bottoni
        LinearLayout bt = new LinearLayout(ctx);
        bt.addView(Ui.btn(ctx, "Annulla", Ui.CARD2, Ui.TEXT, v -> dlg.dismiss()),
                new LinearLayout.LayoutParams(0, dp(Ui.H_BTN), 1f));
        if (editing) {
            bt.addView(Ui.btn(ctx, "Elimina", Ui.CARD2, Ui.DANGER, v -> {
                dlg.dismiss();
                cb.onDelete();
            }), Ui.lp(ctx, 0, dp(Ui.H_BTN), 8, 0, 1f));
        }
        bt.addView(Ui.btn(ctx, "Salva", Ui.ACCENT, Color.BLACK, v -> {
            if (it.isText && (it.text == null || it.text.trim().isEmpty())) {
                it.text = "Testo";
            }
            dlg.dismiss();
            cb.onSave(it);
        }), Ui.lp(ctx, 0, dp(Ui.H_BTN), 8, 0, 1.3f));
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
    private TextView crtChip, barsText, barSpeedText, vhsDistChip, vhsTextChip, vhsSizeText;
    private LinearLayout kBox, crtBox, vhsBox;
    private LinearLayout[] vhsSw;
    private TextView[] vhsModeChips;
    private LinearLayout vhsRwBox, vhsCorners;
    private TextView vhsBlinkChip, vhsPlayChip, vhsPlayText, vhsBlueChip;
    private SeekBar vhsPlayBar;
    private TextView kText;
    private ZoomBox zoomBox;

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
            row.addView(fxChips[i], Ui.lp(ctx, 0, dp(Ui.H_CHIP), i % 2 == 0 ? 0 : 6, 0, 1f));
        }
        kBox = new LinearLayout(ctx);
        kBox.setOrientation(LinearLayout.VERTICAL);
        kText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        kBox.addView(kText);
        SeekBar k = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                it.intensity = p / 100f;
                kText.setText((it.effect == VideoFx.EFFECT_VHS ? "Intensita' distorsione: " : "Intensita': ") + p + "%");
            }
        });
        k.setMax(100);
        k.setProgress(Math.round(it.intensity * 100));
        settle(k, Math.round(it.intensity * 100));
        kBox.addView(k);
        root.addView(kBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 14));
        zoomBox = new ZoomBox(ctx, it.zoom, Ui.CARD2, () -> { });
        root.addView(zoomBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 14));
        crtBox = new LinearLayout(ctx);
        crtBox.setOrientation(LinearLayout.VERTICAL);
        crtChip = Ui.chip(ctx, "");
        crtChip.setTextSize(13);
        crtChip.setOnClickListener(v -> {
            it.crtWarp = !it.crtWarp;
            styleFx();
        });
        crtBox.addView(crtChip, new LinearLayout.LayoutParams(Ui.MATCH, dp(Ui.H_CHIP)));
        crtBox.addView(Ui.text(ctx, "Le linee orizzontali e i puntini RGB verticali dello schermo ci sono sempre. "
                + "Le distorsioni aggiungono: schermo bombato con angoli scuri, colori leggermente sfasati, "
                + "tremolio delle righe, sfarfallio e disturbo. L'anteprima mostra linee, puntini, curvatura "
                + "e fasce; tremolio e disturbo si vedono nel video esportato.", 12, Ui.DIM,
                false), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        barsText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        crtBox.addView(barsText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12));
        SeekBar bars = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                it.crtBars = p;
                barsText.setText(p == 0 ? "Fasce scure che scorrono: nessuna"
                        : "Fasce scure che scorrono: " + p);
            }
        });
        bars.setMax(5);
        bars.setProgress(it.crtBars);
        settle(bars, it.crtBars);
        crtBox.addView(bars);
        barSpeedText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        crtBox.addView(barSpeedText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        SeekBar bsp = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                it.crtBarSpeed = (p + 2) / 100f;
                barSpeedText.setText("Velocità delle fasce: " + (p + 2) + "%");
            }
        });
        bsp.setMax(98);
        bsp.setProgress(Math.max(0, Math.min(98, Math.round(it.crtBarSpeed * 100) - 2)));
        settle(bsp, Math.max(0, Math.min(98, Math.round(it.crtBarSpeed * 100) - 2)));
        crtBox.addView(bsp);
        root.addView(crtBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4));
        buildVhs(root);
        styleFx();
    }

    /** Fine dell'intervallo: se arriva (o supera) la fine del video, la porta 1 ms oltre, cosi' l'ultimo fotogramma e' incluso. */
    private long endFor(long us) {
        return us >= durMs * 1000 ? durMs * 1000 + 1000 : us;
    }

    private int endProg() {
        return (int) Math.min(sbEnd.getMax(), (it.endUs + 99999) / 100000);
    }

    /** Rimette il cursore sul valore salvato dopo il primo disegno della finestra. */
    private static void settle(final SeekBar sb, final int value) {
        sb.post(() -> sb.setProgress(value));
    }

    private void buildVhs(LinearLayout root) {
        vhsBox = new LinearLayout(ctx);
        vhsBox.setOrientation(LinearLayout.VERTICAL);
        vhsBox.addView(Ui.text(ctx, "Tipo di effetto VHS", 13, Ui.DIM, false));
        vhsModeChips = new TextView[3];
        String[] mn = {"Normale", "Rewind ◀◀", "Fast forward ▶▶"};
        LinearLayout mrow = new LinearLayout(ctx);
        for (int i = 0; i < 3; i++) {
            final int mi = i;
            vhsModeChips[i] = Ui.chip(ctx, mn[i]);
            vhsModeChips[i].setTextSize(12);
            vhsModeChips[i].setOnClickListener(v -> {
                it.vhsMode = mi;
                styleFx();
            });
            mrow.addView(vhsModeChips[i], Ui.lp(ctx, 0, dp(Ui.H_CHIP), i == 0 ? 0 : 6, 0, 1f));
        }
        vhsBox.addView(mrow, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 6));
        vhsRwBox = new LinearLayout(ctx);
        vhsRwBox.setOrientation(LinearLayout.VERTICAL);
        vhsBlinkChip = Ui.chip(ctx, "");
        vhsBlinkChip.setTextSize(13);
        vhsBlinkChip.setOnClickListener(v -> {
            it.vhsBlink = !it.vhsBlink;
            styleFx();
        });
        vhsRwBox.addView(vhsBlinkChip, Ui.lp(ctx, Ui.MATCH, dp(Ui.H_CHIP), 0, 8));
        vhsBlueChip = Ui.chip(ctx, "");
        vhsBlueChip.setTextSize(13);
        vhsBlueChip.setOnClickListener(v -> {
            it.vhsBlue = !it.vhsBlue;
            styleFx();
        });
        vhsRwBox.addView(vhsBlueChip, Ui.lp(ctx, Ui.MATCH, dp(Ui.H_CHIP), 0, 8));
        vhsPlayChip = Ui.chip(ctx, "");
        vhsPlayChip.setTextSize(13);
        vhsPlayChip.setOnClickListener(v -> {
            it.vhsPlay = !it.vhsPlay;
            styleFx();
        });
        vhsRwBox.addView(vhsPlayChip, Ui.lp(ctx, Ui.MATCH, dp(Ui.H_CHIP), 0, 8));
        vhsPlayText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        vhsRwBox.addView(vhsPlayText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12));
        vhsPlayBar = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                it.vhsPlayUs = (p + 1) * 500000L;
                styleFx();
            }
        });
        vhsPlayBar.setMax(19);   // 0,5 .. 10 s
        vhsRwBox.addView(vhsPlayBar);
        vhsRwBox.addView(Ui.text(ctx, "Fasce di tracking con righe spostate e strisce di disturbo che scendono "
                + "sul video, come in un videoregistratore che riavvolge o manda avanti veloce. La scritta "
                + "compare in alto a sinistra; alla fine dell'effetto puo' comparire PLAY. "
                + "L'intensita' regola numero e forza delle fasce.",
                12, Ui.DIM, false), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        vhsBox.addView(vhsRwBox, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        vhsDistChip = Ui.chip(ctx, "");
        vhsDistChip.setTextSize(13);
        vhsDistChip.setOnClickListener(v -> {
            it.vhsDist = !it.vhsDist;
            styleFx();
        });
        vhsBox.addView(vhsDistChip, Ui.lp(ctx, Ui.MATCH, dp(Ui.H_CHIP), 0, 12));
        vhsTextChip = Ui.chip(ctx, "");
        vhsTextChip.setTextSize(13);
        vhsTextChip.setOnClickListener(v -> {
            it.vhsText = !it.vhsText;
            styleFx();
        });
        vhsBox.addView(vhsTextChip, Ui.lp(ctx, Ui.MATCH, dp(Ui.H_CHIP), 0, 8));
        vhsBox.addView(Ui.text(ctx, "Distorsione: righe sfasate, fasce di disturbo, neve, colori sbiaditi e "
                + "bordi sfocati; l'intensita' regola quanto e' forte. Le scritte hanno pixel grossi e, con la "
                + "distorsione attiva, tremano. Puoi tenere solo le scritte o solo la distorsione. "
                + "Lascia vuota una scritta per non mostrarla.", 12, Ui.DIM, false),
                Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        String[] labels = {"In alto a sinistra", "In alto a destra", "In basso a sinistra", "In basso a destra"};
        vhsCorners = new LinearLayout(ctx);
        vhsCorners.setOrientation(LinearLayout.VERTICAL);
        vhsBox.addView(vhsCorners, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            vhsCorners.addView(Ui.text(ctx, labels[i], 12, Ui.DIM, false), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10));
            EditText e = new EditText(ctx);
            e.setText(it.vhsTxt[i]);
            e.setTextColor(Ui.TEXT);
            e.setHintTextColor(Ui.DIM);
            e.setHint("(vuota)");
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            e.setBackground(Ui.round(ctx, Ui.CARD2, 12));
            e.setPadding(dp(14), dp(8), dp(14), dp(8));
            e.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable ed) {
                    it.vhsTxt[idx] = ed.toString();
                }
            });
            vhsCorners.addView(e, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4));
        }
        vhsSizeText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        vhsBox.addView(vhsSizeText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 14));
        SeekBar sz = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                it.vhsSize = (3 + p) / 100f;
                vhsSizeText.setText("Dimensione scritte: " + (3 + p) + "% dell'altezza del video");
            }
        });
        sz.setMax(12);
        sz.setProgress(Math.max(0, Math.min(12, Math.round(it.vhsSize * 100) - 3)));
        settle(sz, Math.max(0, Math.min(12, Math.round(it.vhsSize * 100) - 3)));
        vhsBox.addView(sz);
        vhsBox.addView(Ui.text(ctx, "Colore delle scritte", 14, Ui.TEXT, false), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12));
        LinearLayout sw = new LinearLayout(ctx);
        int ns = VideoFx.SWATCHES.length;
        vhsSw = new LinearLayout[ns];
        for (int i = 0; i < ns; i++) {
            final int col = VideoFx.SWATCHES[i];
            LinearLayout d = new LinearLayout(ctx);
            d.setOnClickListener(v -> {
                it.vhsColor = col;
                styleFx();
            });
            vhsSw[i] = d;
            sw.addView(d, Ui.lp(ctx, 0, dp(32), i == 0 ? 0 : 5, 0, 1f));
        }
        vhsBox.addView(sw, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));
        root.addView(vhsBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4));
    }

    private void styleFx() {
        for (int i = 0; i < fxChips.length; i++) {
            Ui.styleChip(ctx, fxChips[i], i == it.effect);
        }
        kBox.setVisibility(VideoFx.EFFECT_HAS_K[it.effect] ? View.VISIBLE : View.GONE);
        zoomBox.setVisibility(it.effect == VideoFx.EFFECT_ZOOM ? View.VISIBLE : View.GONE);
        kText.setText("Intensita': " + Math.round(it.intensity * 100) + "%");
        crtBox.setVisibility(it.effect == VideoFx.EFFECT_CRT ? View.VISIBLE : View.GONE);
        crtChip.setText(it.crtWarp ? "Distorsioni CRT: attive" : "Distorsioni CRT: disattivate");
        Ui.styleChip(ctx, crtChip, it.crtWarp);
        barsText.setText(it.crtBars == 0 ? "Fasce scure che scorrono: nessuna"
                : "Fasce scure che scorrono: " + it.crtBars);
        barSpeedText.setText("Velocità delle fasce: " + Math.round(it.crtBarSpeed * 100) + "%");
        vhsBox.setVisibility(it.effect == VideoFx.EFFECT_VHS ? View.VISIBLE : View.GONE);
        vhsDistChip.setText(it.vhsDist ? "Distorsione immagine: attiva" : "Distorsione immagine: disattivata");
        Ui.styleChip(ctx, vhsDistChip, it.vhsDist);
        for (int i = 0; i < 3; i++) {
            Ui.styleChip(ctx, vhsModeChips[i], it.vhsMode == i);
        }
        vhsRwBox.setVisibility(it.vhsMode != 0 ? View.VISIBLE : View.GONE);
        vhsCorners.setVisibility(it.vhsMode == 0 ? View.VISIBLE : View.GONE);
        vhsBlueChip.setText(it.vhsBlue ? "Dominante blu: attiva" : "Dominante blu: disattivata (colori naturali)");
        Ui.styleChip(ctx, vhsBlueChip, it.vhsBlue);
        vhsBlinkChip.setText(it.vhsBlink ? "Scritta lampeggiante: si" : "Scritta lampeggiante: no (fissa)");
        Ui.styleChip(ctx, vhsBlinkChip, it.vhsBlink);
        vhsPlayChip.setText(it.vhsPlay ? "Mostra PLAY alla fine: si" : "Mostra PLAY alla fine: no");
        Ui.styleChip(ctx, vhsPlayChip, it.vhsPlay);
        vhsPlayText.setText("PLAY visibile per " + String.format(java.util.Locale.US, "%.1f", it.vhsPlayUs / 1e6)
                + " s");
        vhsPlayText.setVisibility(it.vhsPlay ? View.VISIBLE : View.GONE);
        vhsPlayBar.setVisibility(it.vhsPlay ? View.VISIBLE : View.GONE);
        if (vhsPlayBar.getProgress() != (int) Math.max(0, Math.min(19, it.vhsPlayUs / 500000L - 1))) {
            vhsPlayBar.setProgress((int) Math.max(0, Math.min(19, it.vhsPlayUs / 500000L - 1)));
        }
        vhsTextChip.setText(it.vhsMode != 0 ? (it.vhsText ? "Scritta REWIND/FAST FORWARD: attiva"
                : "Scritta REWIND/FAST FORWARD: disattivata")
                : (it.vhsText ? "Scritte VHS: attive" : "Scritte VHS: disattivate"));
        Ui.styleChip(ctx, vhsTextChip, it.vhsText);
        vhsSizeText.setText("Dimensione scritte: " + Math.round(it.vhsSize * 100) + "% dell'altezza del video");
        for (int i = 0; i < vhsSw.length; i++) {
            int col = VideoFx.SWATCHES[i];
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(col);
            g.setCornerRadius(dp(8));
            g.setStroke(dp(it.vhsColor == col ? 3 : 1), it.vhsColor == col ? Ui.ACCENT : 0x55FFFFFF);
            vhsSw[i].setBackground(g);
        }
        kText.setText((it.effect == VideoFx.EFFECT_VHS ? "Intensita' distorsione: " : "Intensita': ")
                + Math.round(it.intensity * 100) + "%");
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
            fr.addView(fontChips[i], Ui.lp(ctx, Ui.WRAP, dp(Ui.H_CHIP), i == 0 ? 0 : 6, 0));
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
        st.addView(chBold, new LinearLayout.LayoutParams(dp(44), dp(Ui.H_CHIP)));
        st.addView(chItalic, Ui.lp(ctx, dp(44), dp(Ui.H_CHIP), 6, 0));
        st.addView(chOutline, Ui.lp(ctx, 0, dp(Ui.H_CHIP), 6, 0, 1f));
        st.addView(chShadow, Ui.lp(ctx, 0, dp(Ui.H_CHIP), 6, 0, 1f));
        st.addView(chBox, Ui.lp(ctx, 0, dp(Ui.H_CHIP), 6, 0, 1f));
        root.addView(st, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8));

        sizeText = Ui.text(ctx, "", 14, Ui.TEXT, false);
        root.addView(sizeText, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 14));
        SeekBar sz = Ui.seek(ctx, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                it.sizeFrac = (3 + p) / 100f;
                sizeText.setText("Dimensione: " + (3 + p) + "% dell'altezza del video");
                if (preview != null) {
                    preview.update();
                }
            }
        });
        sz.setMax(27);
        sz.setProgress(Math.max(0, Math.min(27, Math.round(it.sizeFrac * 100) - 3)));
        settle(sz, Math.max(0, Math.min(27, Math.round(it.sizeFrac * 100) - 3)));
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
                if (zoomBox != null) {
                    zoomBox.setFrame(fb);
                }
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
