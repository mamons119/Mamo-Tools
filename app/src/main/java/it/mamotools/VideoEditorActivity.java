package it.mamotools;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.RippleDrawable;
import android.view.ViewOutlineProvider;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Editor video semplice: anteprima, timeline, tagli, audio on/off e colonna sonora.
 * L'elaborazione vera e propria e' in {@link VideoProcessor}.
 */
public class VideoEditorActivity extends Activity {

    private static final int REQ_VIDEO = 51;
    private static final int REQ_SOUND = 52;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // stato
    private Uri videoUri, soundUri, resultUri;
    private String videoName;
    private long durMs = 0;
    private final List<long[]> cuts = new ArrayList<>();   // ms
    private long markA = -1, markB = -1;
    private long soundDurMs = 0;
    private long soundStartMs = 0;
    private boolean keepAudio = true;
    private boolean mix = false;
    private int dbDown = 12;
    private volatile boolean busy = false;
    private boolean stabOn = false, stabRoll = true, stabAccurate = false;
    private int stabPreset = 1;      // 0 leggera, 1 normale, 2 forte, 3 treppiede
    private int stabZoom = 8;        // % massimo
    private LinearLayout stabBox, qualBox;
    private EditText nameEdit;
    private boolean nameEdited = false;
    private String autoName = "";
    private LinearLayout infoBox;
    private TextView[][] infoCells;
    private TextView[] qualChips;
    private TextView qualDesc;
    private int quality = 0;   // 0 massima (predefinita), 1 alta, 2 media, 3 bassa
    private final java.util.List<VideoFx.Item> fxItems = new java.util.ArrayList<>();
    private LinearLayout fxList;
    private TextOverlayView overlay;
    private int dispVw = 0, dispVh = 0;
    private TextView[] stabChips;
    private TextView stabZoomText;

    // viste
    private PreviewVideo videoView;
    private MediaPlayer videoPlayer;
    private Timeline timeline;
    private TextView timeText, markText, soundName, soundStartText, dbText, status;
    private Button playBtn, exportBtn, openBtn, soundTestBtn;
    private SeekBar soundStartBar, dbBar;
    private RadioGroup modeGroup;
    private RadioButton modeMix;
    private LinearLayout editorBox, soundBox;
    private ProgressBar progress;
    private MediaPlayer soundPlayer;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 80);
        }
    };

    // ------------------------------------------------------------------------ UI

    private static final int C_BG = 0xFF0F1115, C_CARD = 0xFF1A1D24, C_CARD2 = 0xFF262B35,
            C_ACCENT = 0xFF4DD0E1, C_DANGER = 0xFFFF6B6B, C_TEXT = 0xFFECEFF4, C_DIM = 0xFF9AA3B2;

    private TextView pickTitle, pickSub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(36), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        root.addView(text("Video Editor", 28, R.color.text, true));
        TextView sub = text("Taglia, gestisci l'audio e aggiungi una colonna sonora.",
                14, R.color.text_dim, false);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // ---- scelta del video
        LinearLayout pickCard = card();
        pickCard.setOrientation(LinearLayout.HORIZONTAL);
        pickCard.setGravity(Gravity.CENTER_VERTICAL);
        pickCard.setClickable(true);
        pickCard.setOnClickListener(v -> pickVideo());
        TextView ic = text("🎬", 26, R.color.text, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(round(C_BG, 100));
        pickCard.addView(ic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout pt = new LinearLayout(this);
        pt.setOrientation(LinearLayout.VERTICAL);
        pt.setPadding(dp(14), 0, dp(8), 0);
        pickTitle = text("Scegli un video", 17, R.color.text, true);
        pickTitle.setSingleLine(true);
        pickTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pickSub = text("Dalla Galleria o dai file", 13, R.color.text_dim, false);
        pt.addView(pickTitle);
        pt.addView(pickSub);
        pickCard.addView(pt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        pickCard.addView(text("›", 28, R.color.text_dim, false));
        root.addView(pickCard, lp(MATCH, WRAP, 0, 0));

        editorBox = new LinearLayout(this);
        editorBox.setOrientation(LinearLayout.VERTICAL);
        editorBox.setVisibility(View.GONE);
        root.addView(editorBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- anteprima
        LinearLayout prev = card();
        prev.setPadding(dp(12), dp(12), dp(12), dp(12));
        FrameLayout vf = new FrameLayout(this);
        vf.setBackgroundColor(Color.BLACK);
        vf.setClipToOutline(true);
        vf.setBackground(round(Color.BLACK, 14));
        videoView = new PreviewVideo(this);
        vf.addView(videoView, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        overlay = new TextOverlayView(this);
        overlay.setVideo(videoView);
        overlay.setItems(fxItems);
        vf.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        prev.addView(vf, new LinearLayout.LayoutParams(MATCH, dp(220)));
        videoView.setOnPreparedListener(mp -> {
            videoPlayer = mp;
            mp.setVolume(keepAudio ? 1f : 0f, keepAudio ? 1f : 0f);
            if (durMs <= 0) {
                durMs = Math.max(1, videoView.getDuration());
            }
            timeline.setDuration(durMs);
            updateTexts();
        });
        videoView.setOnCompletionListener(mp -> {
            playBtn.setText("▶");
            timeline.setPos(durMs);
        });

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        ctl.setPadding(0, dp(10), 0, 0);
        playBtn = btn("▶", C_ACCENT, Color.BLACK, v -> togglePlay());
        playBtn.setTextSize(18);
        ctl.addView(playBtn, new LinearLayout.LayoutParams(dp(52), dp(44)));
        ctl.addView(btn("−1s", C_CARD2, C_TEXT, v -> seekRel(-1000)), lp(dp(54), dp(44), 8, 0));
        ctl.addView(btn("+1s", C_CARD2, C_TEXT, v -> seekRel(1000)), lp(dp(54), dp(44), 6, 0));
        timeText = text("0:00.0 / 0:00.0", 14, R.color.text, true);
        timeText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ctl.addView(timeText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        prev.addView(ctl, new LinearLayout.LayoutParams(MATCH, WRAP));
        editorBox.addView(prev, lp(MATCH, WRAP, 0, 12));

        // ---- timeline e tagli
        LinearLayout tl = card();
        tl.addView(sectionTitle("Timeline"));
        timeline = new Timeline(this);
        timeline.setClipToOutline(true);
        timeline.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        timeline.setOnSeek(ms -> {
            seekExact(ms);
            updateTexts();
        });
        tl.addView(timeline, new LinearLayout.LayoutParams(MATCH, dp(68)));
        markText = text("", 13, R.color.text_dim, false);
        markText.setPadding(0, dp(10), 0, dp(8));
        tl.addView(markText);

        LinearLayout r1 = new LinearLayout(this);
        r1.addView(btn("[  Inizio", C_CARD2, C_TEXT, v -> {
            markA = timeline.pos;
            if (markB >= 0 && markB <= markA) {
                markB = -1;
            }
            refreshMarks();
        }), lp(0, dp(44), 0, 0, 1f));
        r1.addView(btn("Fine  ]", C_CARD2, C_TEXT, v -> {
            markB = timeline.pos;
            if (markA >= 0 && markA >= markB) {
                markA = -1;
            }
            refreshMarks();
        }), lp(0, dp(44), 8, 0, 1f));
        tl.addView(r1, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(btn("✂  Taglia la selezione", C_DANGER, Color.BLACK, v -> addCut()),
                lp(0, dp(44), 0, 8, 1.6f));
        r2.addView(btn("↶  Annulla", C_CARD2, C_TEXT, v -> {
            if (!cuts.isEmpty()) {
                cuts.remove(cuts.size() - 1);
                refreshMarks();
            }
        }), lp(0, dp(44), 8, 8, 1f));
        tl.addView(r2, new LinearLayout.LayoutParams(MATCH, WRAP));
        editorBox.addView(tl, lp(MATCH, WRAP, 0, 12));

        // ---- audio
        LinearLayout au = card();
        au.addView(sectionTitle("Audio"));
        Switch sw = new Switch(this);
        sw.setText("Mantieni l'audio originale");
        sw.setTextColor(C_TEXT);
        sw.setTextSize(15);
        sw.setChecked(true);
        sw.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        sw.setTrackTintList(ColorStateList.valueOf(0x664DD0E1));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            keepAudio = on;
            if (videoPlayer != null) {
                videoPlayer.setVolume(on ? 1f : 0f, on ? 1f : 0f);
            }
            updateModeAvailability();
        });
        au.addView(sw, new LinearLayout.LayoutParams(MATCH, WRAP));
        editorBox.addView(au, lp(MATCH, WRAP, 0, 12));

        // ---- colonna sonora
        LinearLayout so = card();
        so.addView(sectionTitle("Colonna sonora"));
        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setGravity(Gravity.CENTER_VERTICAL);
        soundRow.addView(btn("♪  Scegli audio", C_CARD2, C_TEXT, v -> pickSound()),
                new LinearLayout.LayoutParams(WRAP, dp(44)));
        soundName = text("Nessuna", 13, R.color.text_dim, false);
        soundName.setPadding(dp(12), 0, dp(8), 0);
        soundName.setSingleLine(true);
        soundName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        soundRow.addView(soundName, new LinearLayout.LayoutParams(0, WRAP, 1f));
        soundRow.addView(btn("✕", C_CARD2, C_DANGER, v -> clearSound()),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        so.addView(soundRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        soundBox = new LinearLayout(this);
        soundBox.setOrientation(LinearLayout.VERTICAL);
        soundBox.setVisibility(View.GONE);
        so.addView(soundBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        soundStartText = text("", 14, R.color.text, false);
        soundStartText.setPadding(0, dp(14), 0, 0);
        soundBox.addView(soundStartText);
        soundStartBar = seek(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                soundStartMs = p * 100L;
                updateTexts();
            }
        });
        soundBox.addView(soundStartBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        soundTestBtn = btn("▶  Ascolta da questo punto", C_CARD2, C_ACCENT, v -> toggleSoundTest());
        soundBox.addView(soundTestBtn, new LinearLayout.LayoutParams(MATCH, dp(44)));

        TextView ml = text("Come usarla", 13, R.color.text_dim, false);
        ml.setPadding(0, dp(16), 0, dp(2));
        soundBox.addView(ml);
        modeGroup = new RadioGroup(this);
        RadioButton replace = radio("Sostituisci l'audio originale", 2001);
        modeMix = radio("Aggiungi all'originale (abbassato)", 2002);
        modeGroup.addView(replace);
        modeGroup.addView(modeMix);
        modeGroup.check(2001);
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            mix = id == 2002;
            updateModeAvailability();
        });
        soundBox.addView(modeGroup, new LinearLayout.LayoutParams(MATCH, WRAP));

        dbText = text("", 14, R.color.text, false);
        dbText.setPadding(0, dp(8), 0, 0);
        soundBox.addView(dbText);
        dbBar = seek(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                dbDown = p + 3;
                updateTexts();
            }
        });
        dbBar.setMax(27);          // 3..30 dB
        dbBar.setProgress(dbDown - 3);
        soundBox.addView(dbBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        editorBox.addView(so, lp(MATCH, WRAP, 0, 12));

        // ---- effetti e testo
        LinearLayout fx = card();
        fx.addView(sectionTitle("Effetti e testo"));
        TextView fxNote = text("Aggiungi un effetto o un testo a partire dal punto in cui si trova il cursore.",
                12, R.color.text_dim, false);
        fxNote.setPadding(0, dp(4), 0, dp(10));
        fx.addView(fxNote);
        LinearLayout fxRow = new LinearLayout(this);
        fxRow.addView(btn("✦  Effetto", C_CARD2, C_ACCENT, v -> addFx(false)),
                new LinearLayout.LayoutParams(0, dp(46), 1f));
        fxRow.addView(btn("T  Testo", C_CARD2, C_ACCENT, v -> addFx(true)), lp(0, dp(46), 8, 0, 1f));
        fx.addView(fxRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        fxList = new LinearLayout(this);
        fxList.setOrientation(LinearLayout.VERTICAL);
        fx.addView(fxList, new LinearLayout.LayoutParams(MATCH, WRAP));
        editorBox.addView(fx, lp(MATCH, WRAP, 0, 12));
        refreshFx();

        // ---- stabilizzazione
        LinearLayout st = card();
        LinearLayout stHead = new LinearLayout(this);
        stHead.setGravity(Gravity.CENTER_VERTICAL);
        stHead.addView(sectionTitle("Stabilizzazione"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        android.widget.Switch stSw = new android.widget.Switch(this);
        stSw.setOnCheckedChangeListener((b, on) -> {
            stabOn = on;
            updateQualityVisibility();
            stabBox.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        stHead.addView(stSw);
        st.addView(stHead, new LinearLayout.LayoutParams(MATCH, WRAP));
        stabBox = new LinearLayout(this);
        stabBox.setOrientation(LinearLayout.VERTICAL);
        stabBox.setVisibility(View.GONE);
        st.addView(stabBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView sl = text("Intensita'", 13, R.color.text_dim, false);
        sl.setPadding(0, dp(8), 0, dp(6));
        stabBox.addView(sl);
        LinearLayout chipRow = new LinearLayout(this);
        String[] pn = {"Leggera", "Normale", "Forte", "Treppiede"};
        stabChips = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            stabChips[i] = Ui.chip(this, pn[i]);
            stabChips[i].setTextSize(12);
            stabChips[i].setOnClickListener(v -> {
                stabPreset = idx;
                styleStab();
            });
            chipRow.addView(stabChips[i], Ui.lp(this, 0, dp(40), i == 0 ? 0 : 6, 0, 1f));
        }
        stabBox.addView(chipRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView sh = text("Leggera segue i movimenti volontari; Treppiede cerca di tenere l'inquadratura "
                + "quasi ferma.", 11, R.color.text_dim, false);
        sh.setPadding(0, dp(6), 0, 0);
        stabBox.addView(sh);

        stabZoomText = text("", 14, R.color.text, false);
        stabZoomText.setPadding(0, dp(14), 0, 0);
        stabBox.addView(stabZoomText);
        SeekBar zb = seek(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                stabZoom = p + 2;
                styleStab();
            }
        });
        zb.setMax(23);
        zb.setProgress(stabZoom - 2);
        stabBox.addView(zb, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout opt = new LinearLayout(this);
        final TextView chRoll = Ui.chip(this, "Correggi inclinazione");
        final TextView chAcc = Ui.chip(this, "Analisi accurata");
        chRoll.setTextSize(12);
        chAcc.setTextSize(12);
        chRoll.setOnClickListener(v -> {
            stabRoll = !stabRoll;
            Ui.styleChip(this, chRoll, stabRoll);
        });
        chAcc.setOnClickListener(v -> {
            stabAccurate = !stabAccurate;
            Ui.styleChip(this, chAcc, stabAccurate);
        });
        Ui.styleChip(this, chRoll, stabRoll);
        Ui.styleChip(this, chAcc, stabAccurate);
        opt.addView(chRoll, Ui.lp(this, 0, dp(40), 0, 0, 1f));
        opt.addView(chAcc, Ui.lp(this, 0, dp(40), 8, 0, 1f));
        stabBox.addView(opt, lp(MATCH, WRAP, 0, 12));
        TextView sn = text("Il video viene ricodificato (ci vuole piu' tempo, circa 1-2 volte la durata) e "
                + "ingrandito leggermente per nascondere i bordi. Lo zoom massimo limita quanto "
                + "puo' correggere.", 11, R.color.text_dim, false);
        sn.setPadding(0, dp(8), 0, 0);
        stabBox.addView(sn);
        styleStab();
        editorBox.addView(st, lp(MATCH, WRAP, 0, 12));

        // ---- esportazione
        LinearLayout ex = card();
        ex.addView(sectionTitle("Esporta"));
        TextView nl = text("Nome del file", 13, R.color.text, true);
        nl.setPadding(0, dp(8), 0, dp(6));
        ex.addView(nl);
        nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        nameEdit.setTextColor(C_TEXT);
        nameEdit.setHintTextColor(0xFF8A93A3);
        nameEdit.setTextSize(14);
        nameEdit.setBackground(round(C_CARD2, 12));
        nameEdit.setPadding(dp(14), dp(10), dp(14), dp(10));
        nameEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameEdit.setText(defaultName());
        nameEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a1, int b1, int c1) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a1, int b1, int c1) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                nameEdited = !e.toString().equals(autoName);
            }
        });
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(nameEdit, new LinearLayout.LayoutParams(0, WRAP, 1f));
        nameRow.addView(text(".mp4", 14, R.color.text_dim, false), lp(WRAP, WRAP, 8, 0));
        ex.addView(nameRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        qualBox = new LinearLayout(this);
        qualBox.setOrientation(LinearLayout.VERTICAL);
        TextView ql = text("Qualita' del video", 13, R.color.text, true);
        ql.setPadding(0, dp(14), 0, 0);
        qualBox.addView(ql);
        LinearLayout qRow = new LinearLayout(this);
        qualChips = new TextView[4];
        String[] qn = {"Massima", "Alta", "Media", "Bassa"};
        for (int i = 0; i < 4; i++) {
            final int qi = i;
            qualChips[i] = Ui.chip(this, qn[i]);
            qualChips[i].setTextSize(13);
            qualChips[i].setOnClickListener(v -> {
                quality = qi;
                styleQuality();
            });
            qRow.addView(qualChips[i], Ui.lp(this, 0, dp(40), i == 0 ? 0 : 6, 0, 1f));
        }
        qualBox.addView(qRow, lp(MATCH, WRAP, 0, 6));
        qualDesc = text("", 11, R.color.text_dim, false);
        qualDesc.setPadding(0, dp(6), 0, 0);
        qualBox.addView(qualDesc);
        ex.addView(qualBox, new LinearLayout.LayoutParams(MATCH, WRAP));
        styleQuality();
        updateQualityVisibility();

        exportBtn = btn("Esporta video", C_ACCENT, Color.BLACK, v -> export());
        exportBtn.setTextSize(16);
        ex.addView(exportBtn, lp(MATCH, dp(54), 0, 16));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        progress.setVisibility(View.GONE);
        ex.addView(progress, lp(MATCH, WRAP, 0, 8));

        status = text("", 14, R.color.text, false);
        status.setPadding(0, dp(10), 0, 0);
        ex.addView(status);

        openBtn = btn("Apri il video salvato", C_CARD2, C_ACCENT, v -> openResult());
        openBtn.setVisibility(View.GONE);
        ex.addView(openBtn, lp(MATCH, dp(46), 0, 10));

        // tabella informazioni: originale / esportato
        infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setBackground(round(C_CARD2, 12));
        infoBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        infoBox.setVisibility(View.GONE);
        String[] labels = {"", "Dimensione", "Risoluzione", "Fotogrammi", "Bitrate", "Durata"};
        infoCells = new TextView[labels.length][3];
        for (int r = 0; r < labels.length; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c2 = 0; c2 < 3; c2++) {
                String t = c2 == 0 ? labels[r] : (r == 0 ? (c2 == 1 ? "Originale" : "Esportato") : "—");
                TextView tv = text(t, 12, r == 0 || c2 == 0 ? R.color.text_dim : R.color.text, r == 0);
                if (c2 > 0) {
                    tv.setGravity(Gravity.END);
                }
                infoCells[r][c2] = tv;
                row.addView(tv, new LinearLayout.LayoutParams(0, WRAP, c2 == 0 ? 1.0f : 1.1f));
            }
            infoBox.addView(row, lp(MATCH, WRAP, 0, r == 0 ? 0 : 6));
        }
        ex.addView(infoBox, lp(MATCH, WRAP, 0, 14));
        editorBox.addView(ex, lp(MATCH, WRAP, 0, 12));

        TextView note = text("I tagli non ricodificano il video (qualita' identica, tranne con stabilizzazione, effetti o testo, che lo ricodificano) e salvataggio "
                + "veloce, ma il punto di taglio puo' slittare all'indietro fino al fotogramma "
                + "chiave precedente (di solito meno di 1 secondo). Una colonna sonora piu' corta "
                + "del video lascia il resto in silenzio (o con il solo audio originale).",
                12, R.color.text_dim, false);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);

        setContentView(scroll);
        updateTexts();
    }

    // ---- componenti grafici

    /** Bottone arrotondato con ombra di tocco e aspetto spento quando disabilitato. */
    private final class RoundButton extends Button {
        RoundButton(Context c) {
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
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(bg, 12), null));
        b.setOnClickListener(l);
        return b;
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

    private SeekBar seek(SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(this);
        sb.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        sb.setOnSeekBarChangeListener(l);
        return sb;
    }

    private RadioButton radio(String label, int id) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(label);
        r.setTextColor(C_TEXT);
        r.setTextSize(14);
        r.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        return r;
    }

    // ------------------------------------------------------------------------ scelta file

    private void pickVideo() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        try {
            startActivityForResult(i, REQ_VIDEO);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    private void pickSound() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, REQ_SOUND);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (req == REQ_VIDEO) {
            loadVideo(uri);
        } else if (req == REQ_SOUND) {
            loadSound(uri);
        }
    }

    private void addFx(boolean isText) {
        VideoFx.Item n = new VideoFx.Item();
        n.isText = isText;
        editFx(n, false);
    }

    private void editFx(final VideoFx.Item item, final boolean editing) {
        if (videoUri == null) {
            return;
        }
        long cur = timeline.pos;
        FxDialog.show(this, item, editing, videoUri, durMs, cur, dispVw, dispVh, new FxDialog.Callback() {
            @Override
            public void onSave(VideoFx.Item saved) {
                if (editing) {
                    int i = fxItems.indexOf(item);
                    if (i >= 0) {
                        fxItems.set(i, saved);
                    }
                } else {
                    fxItems.add(saved);
                }
                sortFx();
                refreshFx();
            }

            @Override
            public void onDelete() {
                fxItems.remove(item);
                refreshFx();
            }
        });
    }

    private void styleQuality() {
        for (int i = 0; i < qualChips.length; i++) {
            Ui.styleChip(this, qualChips[i], i == quality);
        }
        String[] d = {"Massima: bitrate molto alto, nessuna perdita visibile. File piu' grande.",
                "Alta: simile all'originale.", "Media: file piu' leggero, lieve perdita.",
                "Bassa: file piccolo, qualita' ridotta."};
        qualDesc.setText(d[quality] + " Vale quando si usano effetti, testo o stabilizzazione.");
    }

    private void updateQualityVisibility() {
        if (qualBox != null) {
            qualBox.setVisibility(stabOn || !fxItems.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void sortFx() {
        java.util.Collections.sort(fxItems, (a, b) -> Long.compare(a.startUs, b.startUs));
    }

    private void refreshFx() {
        if (fxList == null) {
            return;
        }
        overlay.refresh();
        timeline.setFx(fxItems);
        updateQualityVisibility();
        fxList.removeAllViews();
        for (final VideoFx.Item it : fxItems) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(round(C_CARD2, 12));
            row.setPadding(dp(12), dp(8), dp(4), dp(8));
            TextView ic = text(it.isText ? "T" : VideoFx.EFFECT_ICONS[it.effect], 18, R.color.accent, true);
            ic.setGravity(Gravity.CENTER);
            row.addView(ic, new LinearLayout.LayoutParams(dp(30), WRAP));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(text(it.label(), 14, R.color.text, true));
            tx.addView(text(FxDialog.fmt(it.startUs / 1000) + " → " + FxDialog.fmt(it.endUs / 1000), 12,
                    R.color.text_dim, false));
            row.addView(tx, lp(0, WRAP, 8, 0, 1f));
            row.addView(btn("✎", C_CARD2, C_ACCENT, v -> editFx(it, true)), new LinearLayout.LayoutParams(dp(44), dp(40)));
            row.addView(btn("✕", C_CARD2, C_DANGER, v -> {
                fxItems.remove(it);
                refreshFx();
            }), new LinearLayout.LayoutParams(dp(44), dp(40)));
            row.setOnClickListener(v -> {
                seekExact(it.startUs / 1000);
                timeline.setPos(it.startUs / 1000);
                overlay.setPos(it.startUs / 1000);
            });
            fxList.addView(row, lp(MATCH, WRAP, 0, 8));
        }
    }

    private void loadVideo(Uri uri) {
        videoUri = uri;
        videoName = queryName(uri);
        resultUri = null;
        openBtn.setVisibility(View.GONE);
        status.setText("");
        cuts.clear();
        fxItems.clear();
        refreshFx();
        markA = markB = -1;
        durMs = 0;
        dispVw = dispVh = 0;
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(this, uri);
            try {
                int vw0 = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int vh0 = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rr = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                int rot = rr == null ? 0 : Integer.parseInt(rr);
                boolean sw = rot == 90 || rot == 270;
                dispVw = sw ? vh0 : vw0;
                dispVh = sw ? vw0 : vh0;
            } catch (Exception ignored) {
            }
            overlay.setVideoSize(dispVw, dispVh);
            String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durMs = d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) {
            durMs = 0;
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
        if (durMs <= 0) {
            pickSub.setText("Questo video non sembra leggibile dal telefono.");
            editorBox.setVisibility(View.GONE);
            return;
        }
        editorBox.setVisibility(View.VISIBLE);
        timeline.setDuration(durMs);
        timeline.setPos(0);
        videoView.setDisplaySize(dispVw, dispVh);
        videoView.setVideoURI(uri);
        videoView.seekTo(1);
        playBtn.setText("▶");
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        timeline.loadThumbs(this, uri);
        refreshMarks();
        pickTitle.setText(videoName);
        pickSub.setText(fmt(durMs) + " · tocca per cambiare video");
        final Uri du = uri;
        showInfo(1, null);
        showInfo(2, null);
        infoBox.setVisibility(View.VISIBLE);
        resetName();
        new Thread(() -> {
            final String[] d = describe(du);
            runOnUiThread(() -> {
                if (du.equals(videoUri)) {
                    showInfo(1, d);
                }
            });
        }).start();
        status.setText("");
    }

    private void loadSound(Uri uri) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        long d = 0;
        try {
            mr.setDataSource(this, uri);
            String s = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            d = s == null ? 0 : Long.parseLong(s);
        } catch (Exception ignored) {
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
        if (d <= 0) {
            Toast.makeText(this, "File audio non leggibile.", Toast.LENGTH_LONG).show();
            return;
        }
        stopSoundTest();
        soundUri = uri;
        soundDurMs = d;
        soundStartMs = 0;
        soundName.setText(queryName(uri) + " (" + fmt(d) + ")");
        soundStartBar.setMax((int) Math.max(1, (d - 1000) / 100));
        soundStartBar.setProgress(0);
        soundBox.setVisibility(View.VISIBLE);
        updateModeAvailability();
        updateTexts();
    }

    private void clearSound() {
        stopSoundTest();
        soundUri = null;
        soundName.setText("Nessuna");
        soundBox.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------------ riproduzione

    private void togglePlay() {
        if (videoUri == null) {
            return;
        }
        if (videoView.isPlaying()) {
            videoView.pause();
            playBtn.setText("▶");
        } else {
            if (timeline.pos >= durMs - 100) {
                seekExact(0);
            } else if (!skipping) {
                seekExact(timeline.pos);
            }
            videoView.start();
            playBtn.setText("❚❚");
        }
    }

    private void seekRel(long delta) {
        if (videoUri == null) {
            return;
        }
        long base = videoView.isPlaying() && !skipping ? videoView.getCurrentPosition() : timeline.pos;
        long t = Math.max(0, Math.min(durMs, base + delta));
        seekExact(t);
        timeline.setPos(t);
        updateTexts();
    }

    /** Aggiorna il cursore e salta le parti tagliate durante la riproduzione. */
    private volatile boolean skipping = false;

    private void tick() {
        if (videoUri != null && overlay != null) {
            overlay.setPos(timeline.pos);
        }
        if (videoUri == null || !videoView.isPlaying() || skipping) {
            return;
        }
        long p = videoView.getCurrentPosition();
        for (long[] c : cuts) {
            if (p >= c[0] && p < c[1]) {
                if (c[1] >= durMs - 50) {
                    videoView.pause();
                    playBtn.setText("▶");
                    p = c[0];
                    seekExact(p);
                } else {
                    p = c[1];
                    seekExact(p);
                }
                break;
            }
        }
        timeline.setPos(p);
        overlay.setPos(p);
        updateTexts();
    }

    /** Salto preciso (non al fotogramma chiave precedente) con blocco anti-ripetizione. */
    private void seekExact(long ms) {
        if (videoPlayer == null) {
            videoView.seekTo((int) ms);
            return;
        }
        skipping = true;
        handler.removeCallbacks(clearSkip);
        handler.postDelayed(clearSkip, 800);   // rete di sicurezza
        try {
            videoPlayer.setOnSeekCompleteListener(mp -> {
                skipping = false;
                handler.removeCallbacks(clearSkip);
            });
            videoPlayer.seekTo((int) ms, MediaPlayer.SEEK_CLOSEST);
        } catch (Exception e) {
            skipping = false;
            videoView.seekTo((int) ms);
        }
    }

    private final Runnable clearSkip = new Runnable() {
        @Override
        public void run() {
            skipping = false;
        }
    };

    private void toggleSoundTest() {
        if (soundPlayer != null) {
            stopSoundTest();
            return;
        }
        if (soundUri == null) {
            return;
        }
        try {
            soundPlayer = new MediaPlayer();
            soundPlayer.setDataSource(this, soundUri);
            soundPlayer.setOnPreparedListener(mp -> {
                mp.seekTo((int) soundStartMs);
                mp.start();
            });
            soundPlayer.setOnCompletionListener(mp -> stopSoundTest());
            soundPlayer.prepareAsync();
            soundTestBtn.setText("■  Ferma");
        } catch (Exception e) {
            stopSoundTest();
            Toast.makeText(this, "Impossibile riprodurre l'audio.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSoundTest() {
        if (soundPlayer != null) {
            try {
                soundPlayer.release();
            } catch (Exception ignored) {
            }
            soundPlayer = null;
        }
        if (soundTestBtn != null) {
            soundTestBtn.setText("▶  Ascolta da questo punto");
        }
    }

    // ------------------------------------------------------------------------ tagli

    private void addCut() {
        if (markA < 0 || markB < 0 || markB <= markA) {
            Toast.makeText(this, "Segna prima Inizio e Fine della parte da tagliare.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        long[] n = {markA, markB};
        // unisce i tagli sovrapposti
        List<long[]> merged = new ArrayList<>();
        for (long[] c : cuts) {
            if (c[1] < n[0] || c[0] > n[1]) {
                merged.add(c);
            } else {
                n[0] = Math.min(n[0], c[0]);
                n[1] = Math.max(n[1], c[1]);
            }
        }
        merged.add(n);
        cuts.clear();
        cuts.addAll(merged);
        if (keptTotal() < 500) {
            cuts.remove(n);
            Toast.makeText(this, "Non puoi tagliare tutto il video.", Toast.LENGTH_SHORT).show();
            return;
        }
        markA = markB = -1;
        refreshMarks();
    }

    private long keptTotal() {
        long t = durMs;
        for (long[] c : cuts) {
            t -= c[1] - c[0];
        }
        return t;
    }

    private long[][] keptSegmentsUs() {
        List<long[]> sorted = new ArrayList<>(cuts);
        Collections.sort(sorted, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        List<long[]> keep = new ArrayList<>();
        long cur = 0;
        for (long[] c : sorted) {
            if (c[0] > cur) {
                keep.add(new long[]{cur * 1000, c[0] * 1000});
            }
            cur = Math.max(cur, c[1]);
        }
        if (cur < durMs) {
            keep.add(new long[]{cur * 1000, durMs * 1000});
        }
        return keep.toArray(new long[0][]);
    }

    private void refreshMarks() {
        timeline.setCuts(cuts);
        timeline.setSelection(markA, markB);
        StringBuilder sb = new StringBuilder();
        sb.append("Selezione: ").append(markA < 0 ? "--" : fmt(markA)).append(" → ")
                .append(markB < 0 ? "--" : fmt(markB));
        sb.append("   ·   Tagli: ").append(cuts.size());
        sb.append("   ·   Durata finale: ").append(fmt(keptTotal()));
        markText.setText(sb.toString());
    }

    // ------------------------------------------------------------------------ testi / modi

    private void updateModeAvailability() {
        if (modeMix == null) {
            return;
        }
        modeMix.setEnabled(keepAudio);
        if (!keepAudio && mix) {
            modeGroup.check(2001);
            mix = false;
        }
        boolean showDb = mix && keepAudio;
        dbText.setVisibility(showDb ? View.VISIBLE : View.GONE);
        dbBar.setVisibility(showDb ? View.VISIBLE : View.GONE);
    }

    private void updateTexts() {
        if (timeText == null) {
            return;
        }
        timeText.setText(fmt(timeline == null ? 0 : timeline.pos) + " / " + fmt(durMs));
        if (soundStartText != null) {
            soundStartText.setText("La colonna sonora parte da: " + fmt(soundStartMs)
                    + " (su " + fmt(soundDurMs) + ")");
        }
        if (dbText != null) {
            dbText.setText("Audio originale abbassato di " + dbDown + " dB");
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long t = (ms % 1000) / 100;
        return String.format(Locale.US, "%d:%02d.%d", s / 60, s % 60, t);
    }

    // ------------------------------------------------------------------------ salvataggio

    private void styleStab() {
        for (int i = 0; i < 4; i++) {
            Ui.styleChip(this, stabChips[i], stabPreset == i);
        }
        stabZoomText.setText("Zoom massimo per i bordi: " + stabZoom + "%");
    }

    private void export() {
        if (busy || videoUri == null) {
            return;
        }
        busy = true;
        stopSoundTest();
        videoView.pause();
        playBtn.setText("▶");
        exportBtn.setEnabled(false);
        openBtn.setVisibility(View.GONE);
        showInfo(2, null);
        final String fileName = chosenName() + ".mp4";
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);

        final VideoProcessor.Options o = new VideoProcessor.Options();
        o.video = videoUri;
        o.keep = keptSegmentsUs();
        o.fx = new java.util.ArrayList<>(fxItems);
        o.quality = quality;
        o.keepAudio = keepAudio;
        o.sound = soundUri;
        o.soundStartUs = soundStartMs * 1000;
        o.mix = mix && keepAudio;
        o.originalDb = -dbDown;
        if (stabOn) {
            VideoStabilizer.Params sp = new VideoStabilizer.Params();
            sp.sigmaSec = new float[]{0.25f, 0.6f, 1.2f, 4f}[stabPreset];
            sp.fixRoll = stabRoll;
            sp.accurate = stabAccurate;
            sp.maxZoom = stabZoom / 100f;
            o.stab = sp;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                ContentResolver cr = getContentResolver();
                Uri uri = null;
                ParcelFileDescriptor pfd = null;
                boolean ok = false;
                try {
                    String name = fileName;
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    v.put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/MamoTools");
                    v.put(MediaStore.Video.Media.IS_PENDING, 1);
                    uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) {
                        throw new java.io.IOException("impossibile creare il file in Galleria");
                    }
                    pfd = cr.openFileDescriptor(uri, "rw");
                    if (pfd == null) {
                        throw new java.io.IOException("impossibile scrivere il file");
                    }
                    VideoProcessor.export(VideoEditorActivity.this, o, pfd.getFileDescriptor(),
                            getCacheDir(), new VideoProcessor.Progress() {
                                @Override
                                public void onProgress(final String stage, final int pct) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            status.setText(stage);
                                            progress.setProgress(pct);
                                        }
                                    });
                                }
                            });
                    pfd.close();
                    pfd = null;
                    ContentValues u = new ContentValues();
                    u.put(MediaStore.Video.Media.IS_PENDING, 0);
                    cr.update(uri, u, null, null);
                    ok = true;
                    exportDone(uri, null);
                } catch (OutOfMemoryError oom) {
                    exportDone(null, "Memoria insufficiente per elaborare questo video.");
                } catch (Throwable t) {
                    String m = t.getMessage();
                    exportDone(null, "Salvataggio non riuscito"
                            + (m != null && !m.isEmpty() ? ": " + m : " (" + t.getClass().getSimpleName() + ")"));
                } finally {
                    if (pfd != null) {
                        try {
                            pfd.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (!ok && uri != null) {
                        cr.delete(uri, null, null);
                    }
                }
            }
        }).start();
    }

    private void exportDone(final Uri uri, final String error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                exportBtn.setEnabled(true);
                progress.setVisibility(View.GONE);
                if (uri != null) {
                    resultUri = uri;
                    openBtn.setVisibility(View.VISIBLE);
                    status.setText("Fatto! Salvato in Movies/MamoTools come “" + FileUtil.displayName(
                            VideoEditorActivity.this, uri) + "” (lo trovi in Galleria).");
                    new Thread(() -> {
                        final String[] d = describe(uri);
                        runOnUiThread(() -> {
                            if (uri.equals(resultUri)) {
                                showInfo(2, d);
                            }
                        });
                    }).start();
                    resetName();
                } else {
                    status.setText(error);
                }
            }
        });
    }

    private String defaultName() {
        autoName = "MamoEdit_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return autoName;
    }

    private void resetName() {
        nameEdited = false;
        nameEdit.setText(defaultName());
    }

    private String chosenName() {
        String n = nameEdit.getText().toString().trim();
        if (!nameEdited || n.isEmpty()) {
            return "MamoEdit_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (n.toLowerCase(Locale.US).endsWith(".mp4")) {
            n = n.substring(0, n.length() - 4);
        }
        n = n.trim();
        return n.isEmpty() ? "MamoEdit" : n;
    }

    /** Dimensione, risoluzione, fps, bitrate, durata di un video (testi gia' formattati). */
    private String[] describe(Uri uri) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(this, uri);
            int w = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rr = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rot = rr == null ? 0 : Integer.parseInt(rr);
            if (rot == 90 || rot == 270) {
                int t = w;
                w = h;
                h = t;
            }
            long dur = Long.parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            String fc = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
            String fpsS = "?";
            if (fc != null && dur > 0) {
                fpsS = String.format(Locale.US, "%.1f fps", Long.parseLong(fc) * 1000.0 / dur);
            }
            long size = FileUtil.querySize(this, uri);
            String br = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            long bitrate = br != null ? Long.parseLong(br) : (size > 0 && dur > 0 ? size * 8000 / dur : 0);
            return new String[]{size > 0 ? FileUtil.humanSize(size) : "?", w + " × " + h, fpsS,
                    String.format(Locale.US, "%.1f Mb/s", bitrate / 1e6), fmt(dur)};
        } catch (Exception e) {
            return new String[]{"?", "?", "?", "?", "?"};
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void showInfo(int col, String[] d) {
        for (int r = 0; r < 5; r++) {
            infoCells[r + 1][col].setText(d == null ? "—" : d[r]);
        }
    }

    private void openResult() {
        if (resultUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(resultUri, "video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Nessuna app per aprire il video. Lo trovi in Galleria.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoUri != null && videoView.isPlaying()) {
            videoView.pause();
            playBtn.setText("▶");
        }
        stopSoundTest();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        stopSoundTest();
        videoView.release();
    }

    // ------------------------------------------------------------------------ utilita'

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
        return last == null ? "file" : last;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 15, R.color.accent, true);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
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

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    // ------------------------------------------------------------------------ timeline

    /** Striscia di miniature con tagli (rosso), selezione (giallo) e cursore. */
    private static final class Timeline extends View {
        interface SeekListener {
            void onSeek(long ms);
        }

        long dur = 1;
        long pos = 0;
        long selA = -1, selB = -1;
        List<long[]> cuts = new ArrayList<>();
        final List<Bitmap> thumbs = new ArrayList<>();
        SeekListener listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private int token = 0;

        Timeline(Context c) {
            super(c);
            setBackgroundColor(0xFF1A1D24);
        }

        void setOnSeek(SeekListener l) {
            listener = l;
        }

        void setDuration(long d) {
            dur = Math.max(1, d);
            invalidate();
        }

        void setPos(long p) {
            pos = Math.max(0, Math.min(dur, p));
            invalidate();
        }

        List<VideoFx.Item> fxMarks = new ArrayList<>();

        void setFx(List<VideoFx.Item> l) {
            fxMarks = new ArrayList<>(l);
            invalidate();
        }

        void setCuts(List<long[]> c) {
            cuts = new ArrayList<>(c);
            invalidate();
        }

        void setSelection(long a, long b) {
            selA = a;
            selB = b;
            invalidate();
        }

        void loadThumbs(final Context ctx, final Uri uri) {
            final int my = ++token;
            synchronized (thumbs) {
                thumbs.clear();
            }
            invalidate();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    MediaMetadataRetriever mr = new MediaMetadataRetriever();
                    try {
                        mr.setDataSource(ctx, uri);
                        int n = 10;
                        for (int i = 0; i < n && my == token; i++) {
                            long t = dur * 1000L * i / n;
                            Bitmap b = mr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (b != null) {
                                int h = 120;
                                int w = Math.max(1, b.getWidth() * h / Math.max(1, b.getHeight()));
                                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                                if (s != b) {
                                    b.recycle();
                                }
                                synchronized (thumbs) {
                                    if (my == token) {
                                        thumbs.add(s);
                                    }
                                }
                            } else {
                                synchronized (thumbs) {
                                    thumbs.add(null);
                                }
                            }
                            postInvalidate();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            mr.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }).start();
        }

        private float x(long ms) {
            return (float) ms / dur * getWidth();
        }

        private void drawThumbs(Canvas c, int w, int h, int n, Paint p) {
            synchronized (thumbs) {
                for (int i = 0; i < thumbs.size() && i < n; i++) {
                    Bitmap b = thumbs.get(i);
                    if (b == null) {
                        continue;
                    }
                    int x0 = w * i / n;
                    int x1 = w * (i + 1) / n;
                    float cellAspect = (x1 - x0) / (float) h;
                    float bAspect = b.getWidth() / (float) b.getHeight();
                    if (bAspect > cellAspect) {
                        int sw = (int) (b.getHeight() * cellAspect);
                        int sx = (b.getWidth() - sw) / 2;
                        src.set(sx, 0, sx + sw, b.getHeight());
                    } else {
                        int sh = (int) (b.getWidth() / cellAspect);
                        int sy = (b.getHeight() - sh) / 2;
                        src.set(0, sy, b.getWidth(), sy + sh);
                    }
                    dst.set(x0, 0, x1, h);
                    c.drawBitmap(b, src, dst, p);
                }
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            int n = 10;
            drawThumbs(c, w, h, n, null);
            float[] u = new float[VideoFx.U_COUNT];
            for (VideoFx.Item f : fxMarks) {
                if (f.isText) {
                    continue;
                }
                long mid = (f.startUs + f.endUs) / 2;
                List<VideoFx.Item> one = new ArrayList<>();
                one.add(f);
                VideoFx.uniforms(one, mid, u);
                c.save();
                c.clipRect(x(f.startUs / 1000), 0, x(f.endUs / 1000), h);
                Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
                fp.setColorFilter(new android.graphics.ColorMatrixColorFilter(VideoFx.colorMatrix(u)));
                drawThumbs(c, w, h, n, fp);
                if (VideoFx.needsGrab(u) || u[VideoFx.U_VIG] > 0 || u[VideoFx.U_ZOOM] != 1) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0x334DD0E1);
                    c.drawRect(x(f.startUs / 1000), 0, x(f.endUs / 1000), h, paint);
                }
                c.restore();
            }
            // barre degli intervalli: effetti in basso (azzurro), testi sopra (arancio)
            float bh = h * 0.09f;
            paint.setStyle(Paint.Style.FILL);
            for (VideoFx.Item f : fxMarks) {
                paint.setColor(f.isText ? 0xFFFFB74D : 0xFF4DD0E1);
                float y1 = f.isText ? h - bh - 2 : h;
                c.drawRect(x(f.startUs / 1000), y1 - bh, x(f.endUs / 1000), y1, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xAAFF3B30);
            for (long[] cut : cuts) {
                c.drawRect(x(cut[0]), 0, x(cut[1]), h, paint);
            }
            if (selA >= 0 || selB >= 0) {
                long a = selA >= 0 ? selA : selB;
                long b = selB >= 0 ? selB : selA;
                paint.setColor(0x66FFD60A);
                c.drawRect(x(a), 0, x(b), h, paint);
                paint.setColor(0xFFFFD60A);
                c.drawRect(x(a) - 2, 0, x(a) + 2, h, paint);
                c.drawRect(x(b) - 2, 0, x(b) + 2, h, paint);
            }
            paint.setColor(0xFFFFFFFF);
            float px = x(pos);
            c.drawRect(px - 3, 0, px + 3, h, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fallthrough
                case MotionEvent.ACTION_MOVE:
                    long ms = (long) (Math.max(0, Math.min(1, e.getX() / Math.max(1, getWidth())))
                            * dur);
                    setPos(ms);
                    if (listener != null) {
                        listener.onSeek(ms);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }
}
