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
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
    private static final int REQ_CLIP = 53;
    private static final int PROXY_SIDE = 960;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // stato
    private Uri videoUri, soundUri, resultUri;
    private String videoName;
    private long durMs = 0;
    private final List<long[]> cuts = new ArrayList<>();   // ms
    private long markA = -1, markB = -1;
    private long soundDurMs = 0;
    private long soundStartMs = 0, soundPrevMs = 0;
    private long soundFadeInMs = 0, soundFadeOutMs = 0;
    private int soundFadeCurve = 1;
    private TextView fadeInText, fadeOutText;
    private SeekBar fadeInBar, fadeOutBar;
    private TextView[] curveChips;
    private boolean keepAudio = true;
    private boolean mix = false;
    private int dbDown = 12;
    private volatile boolean busy = false;
    private volatile boolean cancelExport = false;
    private Button cancelBtn;
    private boolean soundBarSync = false, soundTestReady = false;
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
    private int dispVw = 0, dispVh = 0;      // dimensioni mostrate (con rotazione utente)
    private int baseVw = 0, baseVh = 0;      // dimensioni mostrate dal file originale
    private int extraRot = 0;                // rotazione scelta dall'utente (0/90/180/270)
    private TextView rotText;
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
    private MediaPlayer prevSound;          // colonna sonora riprodotta insieme al video nell'anteprima
    private boolean prevSoundReady = false;

    // clip aggiunte al video: sequenza di tratti (il primo e' il video scelto)
    private static final int C_BTN_ON_CARD = 0xFF3A4252;

    private static final class Piece {
        VideoComposer.Seg s;
        String name;

        Piece(VideoComposer.Seg s, String name) {
            this.s = s;
            this.name = name;
        }
    }

    private final List<Piece> pieces = new ArrayList<>();
    private Uri origUri;
    private long origDurMs = 0;
    private int origVw = 0, origVh = 0;
    private LinearLayout clipList;
    private LinearLayout editLoadBox;
    private TextView editLoadText;
    private Switch swKeep;
    private android.widget.Switch stSwitch;
    private SeekBar stabZoomBar;
    private TextView chRollV, chAccV;
    private ProjStore store;
    private String projectId, projectTitle;
    private File compFile;
    private int compSeq = 0;
    private int rebuildGen = 0;

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
        root.addView(new View(this), new LinearLayout.LayoutParams(MATCH, dp(16)));

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
        editLoadBox = new LinearLayout(this);
        editLoadBox.setOrientation(LinearLayout.VERTICAL);
        editLoadBox.setGravity(Gravity.CENTER);
        editLoadBox.setBackgroundColor(0xCC0F1115);
        editLoadBox.setClickable(true);
        editLoadBox.setVisibility(View.GONE);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminate(true);
        spin.setIndeterminateTintList(ColorStateList.valueOf(C_ACCENT));
        editLoadBox.addView(spin, new LinearLayout.LayoutParams(dp(48), dp(48)));
        editLoadText = text("", 13, R.color.text, true);
        editLoadText.setGravity(Gravity.CENTER);
        editLoadText.setPadding(dp(16), dp(10), dp(16), 0);
        editLoadBox.addView(editLoadText, new LinearLayout.LayoutParams(MATCH, WRAP));
        vf.addView(editLoadBox, new FrameLayout.LayoutParams(MATCH, MATCH));
        prev.addView(vf, new LinearLayout.LayoutParams(MATCH, dp(220)));
        videoView.setOnPreparedListener(mp -> {
            videoPlayer = mp;
            applyVolumes();
            if (durMs <= 0) {
                durMs = Math.max(1, videoView.getDuration());
            }
            timeline.setDuration(durMs);
            updateTexts();
        });
        videoView.setOnCompletionListener(mp -> {
            playBtn.setText("▶");
            timeline.setPos(durMs);
            soundPause();
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
        tl.addView(btn("🎞  Aggiungi clip nel punto del cursore", C_CARD2, C_ACCENT, v -> pickClip()),
                lp(MATCH, dp(46), 0, 8));
        editorBox.addView(tl, lp(MATCH, WRAP, 0, 12));

        // ---- clip
        LinearLayout cc = card();
        cc.addView(sectionTitle("Clip"));
        TextView clipNote = text("Qui trovi i tratti che compongono il video. Tra una clip e l'altra puoi "
                + "scegliere una transizione; se elimini una clip il video si ricompatta.", 12, R.color.text_dim, false);
        clipNote.setPadding(0, dp(4), 0, dp(6));
        cc.addView(clipNote);
        clipList = new LinearLayout(this);
        clipList.setOrientation(LinearLayout.VERTICAL);
        cc.addView(clipList, new LinearLayout.LayoutParams(MATCH, WRAP));
        editorBox.addView(cc, lp(MATCH, WRAP, 0, 12));

        // ---- audio
        LinearLayout au = card();
        au.addView(sectionTitle("Audio"));
        store = new ProjStore(this, "video");
        Switch sw = new Switch(this);
        swKeep = sw;
        sw.setText("Mantieni l'audio originale");
        sw.setTextColor(C_TEXT);
        sw.setTextSize(15);
        sw.setChecked(true);
        sw.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        sw.setTrackTintList(ColorStateList.valueOf(0x664DD0E1));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            keepAudio = on;
            updateModeAvailability();
            applyVolumes();
        });
        au.addView(sw, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- colonna sonora (nella stessa scheda dell'audio)
        LinearLayout so = au;
        TextView soTitle = text("Colonna sonora", 13, R.color.text_dim, true);
        soTitle.setPadding(0, dp(18), 0, dp(8));
        so.addView(soTitle);
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
                if (soundBarSync) {
                    return;
                }
                soundPrevMs = p * 100L;
                if (user && soundPlayer != null && soundTestReady) {
                    try {
                        soundPlayer.seekTo((int) soundPrevMs);
                    } catch (Exception ignored) {
                    }
                }
                updateTexts();
            }
        });
        soundBox.addView(soundStartBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout trRow = new LinearLayout(this);
        trRow.setBaselineAligned(false);
        soundTestBtn = btn("▶  Ascolta", C_CARD2, C_ACCENT, v -> toggleSoundTest());
        trRow.addView(soundTestBtn, lp(0, dp(44), 0, 0, 1f));
        trRow.addView(btn("⚑  Parti da qui", C_CARD2, C_TEXT, v -> setSoundStartHere()),
                lp(0, dp(44), 8, 0, 1.3f));
        soundBox.addView(trRow, new LinearLayout.LayoutParams(MATCH, WRAP));

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
            applyVolumes();
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
                applyVolumes();
            }
        });
        dbBar.setMax(27);          // 3..30 dB
        dbBar.setProgress(dbDown - 3);
        soundBox.addView(dbBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        // dissolvenze della colonna sonora
        TextView fl = text("Dissolvenze della colonna sonora", 13, R.color.text_dim, false);
        fl.setPadding(0, dp(18), 0, dp(2));
        soundBox.addView(fl);
        fadeInText = text("", 14, R.color.text, false);
        fadeInText.setPadding(0, dp(6), 0, 0);
        soundBox.addView(fadeInText);
        fadeInBar = seek(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                soundFadeInMs = p * 100L;
                updateTexts();
                applyVolumes();
            }
        });
        fadeInBar.setMax(150);       // 0..15 s
        soundBox.addView(fadeInBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        fadeOutText = text("", 14, R.color.text, false);
        fadeOutText.setPadding(0, dp(6), 0, 0);
        soundBox.addView(fadeOutText);
        fadeOutBar = seek(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                soundFadeOutMs = p * 100L;
                updateTexts();
                applyVolumes();
            }
        });
        fadeOutBar.setMax(150);
        soundBox.addView(fadeOutBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout curveRow = new LinearLayout(this);
        curveChips = new TextView[2];
        String[] cn = {"Lineare", "Morbida"};
        for (int i = 0; i < 2; i++) {
            final int ci = i;
            curveChips[i] = Ui.chip(this, cn[i]);
            curveChips[i].setTextSize(12);
            curveChips[i].setOnClickListener(v -> {
                soundFadeCurve = ci;
                styleCurve();
                applyVolumes();
            });
            curveRow.addView(curveChips[i], Ui.lp(this, 0, dp(40), i == 0 ? 0 : 6, 0, 1f));
        }
        soundBox.addView(curveRow, lp(MATCH, WRAP, 0, 10));
        TextView fnote = text("Lineare: il volume sale e scende in modo costante. Morbida: parte e finisce piano. "
                + "L'uscita finisce alla fine del video; 0 = nessuna dissolvenza.", 12, R.color.text_dim, false);
        fnote.setPadding(0, dp(6), 0, 0);
        soundBox.addView(fnote);
        styleCurve();
        editorBox.addView(au, lp(MATCH, WRAP, 0, 12));

        // ---- rotazione
        LinearLayout rc = card();
        LinearLayout rh = new LinearLayout(this);
        rh.setGravity(Gravity.CENTER_VERTICAL);
        rh.addView(sectionTitle("Rotazione"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        rotText = text("0°", 14, R.color.text_dim, true);
        rh.addView(rotText);
        rc.addView(rh, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout rr = new LinearLayout(this);
        rr.addView(btn("↺  90° a sinistra", C_CARD2, C_ACCENT, v -> rotate(-90)),
                new LinearLayout.LayoutParams(0, dp(46), 1f));
        rr.addView(btn("↻  90° a destra", C_CARD2, C_ACCENT, v -> rotate(90)), lp(0, dp(46), 8, 0, 1f));
        rc.addView(rr, lp(MATCH, WRAP, 0, 10));
        editorBox.addView(rc, lp(MATCH, WRAP, 0, 12));

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
        stSwitch = stSw;
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
        stabZoomBar = zb;
        zb.setMax(23);
        zb.setProgress(stabZoom - 2);
        stabBox.addView(zb, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout opt = new LinearLayout(this);
        final TextView chRoll = Ui.chip(this, "Correggi inclinazione");
        final TextView chAcc = Ui.chip(this, "Analisi accurata");
        chRollV = chRoll;
        chAccV = chAcc;
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
        LinearLayout exHead = new LinearLayout(this);
        exHead.setGravity(Gravity.CENTER_VERTICAL);
        exHead.addView(sectionTitle("Esporta"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        Button infoBtn = btn("i", C_CARD2, C_ACCENT, vv -> Ui.infoDialog(this, "Come funziona l'esportazione",
                "I tagli non ricodificano il video: la qualita' resta identica e il salvataggio e' veloce. "
                + "Con stabilizzazione, effetti o testo invece il video viene ricodificato, ed e' per questo "
                + "che puoi scegliere la qualita'.\n\n"
                + "Senza ricodifica il punto di taglio puo' slittare all'indietro fino al fotogramma chiave "
                + "precedente (di solito meno di 1 secondo).\n\n"
                + "Una colonna sonora piu' corta del video lascia il resto in silenzio (o con il solo audio "
                + "originale)."));
        infoBtn.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        infoBtn.setTextSize(16);
        exHead.addView(infoBtn, new LinearLayout.LayoutParams(dp(38), dp(38)));
        ex.addView(exHead, new LinearLayout.LayoutParams(MATCH, WRAP));
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
        LinearLayout exRow = new LinearLayout(this);
        exRow.setBaselineAligned(false);
        exRow.addView(exportBtn, lp(0, dp(54), 0, 0, 1f));
        Button saveBtn = btn("Salva progetto", C_CARD2, C_ACCENT, v -> saveProject());
        saveBtn.setTextSize(16);
        exRow.addView(saveBtn, lp(0, dp(54), 8, 0, 1f));
        ex.addView(exRow, lp(MATCH, WRAP, 0, 16));
        cancelBtn = btn("Annulla esportazione", C_CARD2, C_DANGER, v -> {
            cancelExport = true;
            cancelBtn.setEnabled(false);
            status.setText("Annullamento in corso...");
        });
        cancelBtn.setVisibility(View.GONE);
        ex.addView(cancelBtn, lp(MATCH, dp(46), 0, 8));

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



        setContentView(scroll);
        updateTexts();

        // aperto da "Apri con" dal telefono
        Intent in = getIntent();
        if (in != null && in.getStringExtra("project") != null) {
            loadProject(in.getStringExtra("project"));
        } else if (in != null && in.getData() != null && Intent.ACTION_VIEW.equals(in.getAction())) {
            Uri d = in.getData();
            String nm = queryName(d);
            String last = d.getLastPathSegment() == null ? "" : d.getLastPathSegment();
            String type = in.getType() != null ? in.getType() : getContentResolver().getType(d);
            boolean isVideoType = type != null && type.startsWith("video/");
            if (nm.toLowerCase(Locale.ROOT).endsWith(".mamovideo")
                    || last.toLowerCase(Locale.ROOT).endsWith(".mamovideo") || !isVideoType) {
                // anche i file ricevuti da WhatsApp & co. (senza estensione nel percorso) arrivano qui
                importProject(d);
            } else {
                loadVideo(d);
            }
        }
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
        ProjStore.keep(this, uri);
        if (req == REQ_VIDEO) {
            projectId = null;
            projectTitle = null;
            loadVideo(uri);
        } else if (req == REQ_SOUND) {
            loadSound(uri);
        } else if (req == REQ_CLIP) {
            addClip(uri);
        }
    }



    // ------------------------------------------------------------------------ progetti

    private void saveProject() {
        if (busy || videoUri == null || origUri == null) {
            return;
        }
        if (projectId != null) {
            doSaveProject(projectTitle);
            return;
        }
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setTextColor(C_TEXT);
        et.setTextSize(15);
        et.setBackground(round(C_CARD2, 12));
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        et.setText(FileUtil.baseName(videoName == null ? "Progetto" : videoName));
        et.selectAll();
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(C_CARD, 20));
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        box.addView(text("Salva il progetto", 18, R.color.text, true));
        TextView hint = text("Nome del progetto", 13, R.color.text_dim, false);
        hint.setPadding(0, dp(10), 0, dp(6));
        box.addView(hint);
        box.addView(et, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout br = new LinearLayout(this);
        br.addView(btn("Annulla", C_CARD2, C_TEXT, v -> d.dismiss()), lp(0, dp(46), 0, 0, 1f));
        br.addView(btn("Salva", C_ACCENT, Color.BLACK, v -> {
            String t = et.getText().toString().trim();
            d.dismiss();
            doSaveProject(t.isEmpty() ? "Progetto" : t);
        }), lp(0, dp(46), 8, 0, 1f));
        box.addView(br, lp(MATCH, WRAP, 0, 16));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), WRAP);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
    }

    private void doSaveProject(String title) {
        try {
            String id = projectId != null ? projectId : ProjStore.newId();
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("title", title);
            o.put("origUri", origUri.toString());
            ProjStore.keep(this, origUri);
            org.json.JSONArray pa = new org.json.JSONArray();
            for (Piece pc : pieces) {
                org.json.JSONObject p = new org.json.JSONObject();
                p.put("uri", pc.s.uri.toString());
                ProjStore.keep(this, pc.s.uri);
                p.put("name", pc.name);
                p.put("startUs", pc.s.startUs);
                p.put("endUs", pc.s.endUs);
                p.put("trans", pc.s.trans);
                org.json.JSONArray tpa = new org.json.JSONArray();
                for (float f : pc.s.tp) {
                    tpa.put((double) f);
                }
                p.put("tp", tpa);
                p.put("transUs", pc.s.transUs);
                p.put("speed", (double) pc.s.speed);
                p.put("reverse", pc.s.reverse);
                p.put("vol", (double) pc.s.vol);
                p.put("mirH", pc.s.mirH);
                p.put("mirV", pc.s.mirV);
                p.put("look", pc.s.look);
                pa.put(p);
            }
            o.put("pieces", pa);
            o.put("videoName", videoName);
            o.put("extraRot", extraRot);
            org.json.JSONArray ca = new org.json.JSONArray();
            for (long[] c : cuts) {
                ca.put(new org.json.JSONArray().put(c[0]).put(c[1]));
            }
            o.put("cuts", ca);
            o.put("markA", markA);
            o.put("markB", markB);
            org.json.JSONArray fa = new org.json.JSONArray();
            for (VideoFx.Item it : fxItems) {
                fa.put(it.toJson());
            }
            o.put("fx", fa);
            o.put("keepAudio", keepAudio);
            o.put("mix", mix);
            o.put("dbDown", dbDown);
            if (soundUri != null) {
                o.put("soundUri", soundUri.toString());
                ProjStore.keep(this, soundUri);
                o.put("soundStartMs", soundStartMs);
                o.put("soundFadeIn", soundFadeInMs);
                o.put("soundFadeOut", soundFadeOutMs);
                o.put("soundFadeCurve", soundFadeCurve);
            }
            o.put("stabOn", stabOn);
            o.put("stabRoll", stabRoll);
            o.put("stabAccurate", stabAccurate);
            o.put("stabPreset", stabPreset);
            o.put("stabZoom", stabZoom);
            o.put("quality", quality);
            o.put("pos", timeline.pos);
            o.put("nameEdited", nameEdited);
            if (nameEdited) {
                o.put("outName", nameEdit.getText().toString());
            }
            o.put("summary", fmt(keptTotal()) + " · " + pieces.size() + (pieces.size() == 1 ? " clip" : " clip")
                    + (fxItems.isEmpty() ? "" : " · " + fxItems.size() + " effetti/testi"));
            store.write(id, o);
            projectId = id;
            projectTitle = title;
            Toast.makeText(this, "Progetto salvato: " + title, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Salvataggio del progetto non riuscito.", Toast.LENGTH_LONG).show();
        }
    }

    /** Apre un file .mamovideo ricevuto da fuori (Apri con): lo copia tra i progetti e lo carica. */
    private void importProject(Uri uri) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    throw new java.io.IOException("file non leggibile");
                }
                byte[] b = new byte[16384];
                int n;
                while ((n = is.read(b)) > 0) {
                    bo.write(b, 0, n);
                }
            }
            org.json.JSONObject o = new org.json.JSONObject(bo.toString("UTF-8"));
            if (!o.has("pieces") || !o.has("origUri")) {
                throw new IllegalArgumentException("non e' un progetto del Video Editor");
            }
            String id = ProjStore.newId();
            store.write(id, o);
            loadProject(id);
        } catch (Exception e) {
            Ui.infoDialog(this, "Progetto non leggibile", "Non riesco ad aprire questo file"
                    + (e.getMessage() != null ? ": " + e.getMessage() : "."));
        }
    }

    private void loadProject(String id) {
        try {
            org.json.JSONObject o = store.read(id);
            Uri ou = Uri.parse(o.getString("origUri"));
            if (durationOf(ou) <= 0) {
                Ui.infoDialog(this, "Video non trovato", "Il video originale di questo progetto non e' piu' "
                        + "disponibile (spostato o eliminato). Il progetto resta nell'elenco.");
                return;
            }
            org.json.JSONArray pa = o.getJSONArray("pieces");
            for (int i = 0; i < pa.length(); i++) {
                Uri u = Uri.parse(pa.getJSONObject(i).getString("uri"));
                if (!u.equals(ou) && durationOf(u) <= 0) {
                    Ui.infoDialog(this, "Clip non trovata", "Una delle clip di questo progetto ("
                            + pa.getJSONObject(i).optString("name", "") + ") non e' piu' disponibile.");
                    return;
                }
            }
            projectId = id;
            projectTitle = o.optString("title", "Progetto");
            loadVideo(ou);
            if (durMs <= 0) {
                return;
            }
            Snap plain = snap();
            extraRot = o.optInt("extraRot", 0);
            applyRotation();
            cuts.clear();
            org.json.JSONArray ca = o.optJSONArray("cuts");
            for (int i = 0; ca != null && i < ca.length(); i++) {
                cuts.add(new long[]{ca.getJSONArray(i).getLong(0), ca.getJSONArray(i).getLong(1)});
            }
            markA = o.optLong("markA", -1);
            markB = o.optLong("markB", -1);
            fxItems.clear();
            org.json.JSONArray fa = o.optJSONArray("fx");
            for (int i = 0; fa != null && i < fa.length(); i++) {
                fxItems.add(VideoFx.Item.fromJson(fa.getJSONObject(i)));
            }
            swKeep.setChecked(o.optBoolean("keepAudio", true));
            keepAudio = swKeep.isChecked();
            dbDown = o.optInt("dbDown", 12);
            dbBar.setProgress(dbDown - 3);
            modeGroup.check(o.optBoolean("mix", false) && keepAudio ? 2002 : 2001);
            String su = o.optString("soundUri", "");
            if (!su.isEmpty()) {
                Uri sUri = Uri.parse(su);
                if (durationOf(sUri) > 0) {
                    loadSound(sUri);
                    long ss = o.optLong("soundStartMs", 0);
                    soundStartMs = ss;
                    soundPrevMs = ss;
                    soundStartBar.setProgress((int) (ss / 100));
                    soundFadeInMs = o.optLong("soundFadeIn", 0);
                    soundFadeOutMs = o.optLong("soundFadeOut", 0);
                    soundFadeCurve = o.optInt("soundFadeCurve", 1);
                    fadeInBar.setProgress((int) (soundFadeInMs / 100));
                    fadeOutBar.setProgress((int) (soundFadeOutMs / 100));
                    styleCurve();
                } else {
                    Toast.makeText(this, "La colonna sonora del progetto non e' piu' disponibile.",
                            Toast.LENGTH_LONG).show();
                }
            }
            stSwitch.setChecked(o.optBoolean("stabOn", false));
            stabRoll = o.optBoolean("stabRoll", true);
            stabAccurate = o.optBoolean("stabAccurate", false);
            Ui.styleChip(this, chRollV, stabRoll);
            Ui.styleChip(this, chAccV, stabAccurate);
            stabPreset = o.optInt("stabPreset", 1);
            stabZoomBar.setProgress(o.optInt("stabZoom", 8) - 2);
            styleStab();
            quality = o.optInt("quality", 0);
            styleQuality();
            if (o.optBoolean("nameEdited", false)) {
                nameEdit.setText(o.optString("outName", ""));
                nameEdited = true;
            }
            projectTitle = o.optString("title", "Progetto");
            pickTitle.setText(projectTitle);
            // clip aggiunte
            pieces.clear();
            for (int i = 0; i < pa.length(); i++) {
                org.json.JSONObject p = pa.getJSONObject(i);
                VideoComposer.Seg sg = new VideoComposer.Seg(Uri.parse(p.getString("uri")),
                        p.getLong("startUs"), p.getLong("endUs"));
                sg.trans = p.optInt("trans", 0);
                sg.tp = VideoComposer.defaults(sg.trans);
                org.json.JSONArray tpa = p.optJSONArray("tp");
                for (int k = 0; tpa != null && k < 4 && k < tpa.length(); k++) {
                    sg.tp[k] = (float) tpa.optDouble(k, sg.tp[k]);
                }
                sg.transUs = p.optLong("transUs", 0);
                sg.speed = (float) p.optDouble("speed", 1.0);
                if (!(sg.speed >= 0.05f && sg.speed <= 20f)) {
                    sg.speed = 1f;
                }
                sg.reverse = p.optBoolean("reverse", false);
                sg.vol = (float) p.optDouble("vol", 1.0);
                sg.mirH = p.optBoolean("mirH", false);
                sg.mirV = p.optBoolean("mirV", false);
                sg.look = p.optInt("look", 0);
                pieces.add(new Piece(sg, p.optString("name", "")));
            }
            long pos = o.optLong("pos", 0);
            boolean isPlain = pieces.size() == 1 && pieces.get(0).s.uri.equals(origUri)
                    && pieces.get(0).s.startUs == 0 && pieces.get(0).s.endUs == origDurMs * 1000 && pieces.get(0).s.isPlain();
            refreshMarks();
            refreshFx();
            refreshClips();
            if (isPlain) {
                timeline.setPos(pos);
                seekExact(pos);
            } else {
                rebuild(pos, plain);
            }
            updateTexts();
        } catch (Exception e) {
            Ui.infoDialog(this, "Progetto non leggibile", "Non riesco ad aprire questo progetto"
                    + (e.getMessage() != null ? ": " + e.getMessage() : "."));
        }
    }

    // ------------------------------------------------------------------------ clip

    private void pickClip() {
        if (busy || videoUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        try {
            startActivityForResult(i, REQ_CLIP);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    private long durationOf(Uri uri) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(this, uri);
            String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Copia dello stato che dipende dalla durata, per tornare indietro se il montaggio fallisce. */
    private static final class Snap {
        List<Piece> pieces = new ArrayList<>();
        List<long[]> cuts = new ArrayList<>();
        List<VideoFx.Item> fx = new ArrayList<>();
        long markA, markB;
    }

    private Snap snap() {
        Snap sn = new Snap();
        for (Piece pc : pieces) {
            sn.pieces.add(new Piece(pc.s.copy(), pc.name));
        }
        for (long[] c : cuts) {
            sn.cuts.add(c.clone());
        }
        for (VideoFx.Item it : fxItems) {
            sn.fx.add(it.copy());
        }
        sn.markA = markA;
        sn.markB = markB;
        return sn;
    }

    private void restore(Snap sn) {
        pieces.clear();
        pieces.addAll(sn.pieces);
        cuts.clear();
        cuts.addAll(sn.cuts);
        fxItems.clear();
        fxItems.addAll(sn.fx);
        markA = sn.markA;
        markB = sn.markB;
    }

    private long totalUs() {
        long t = 0;
        for (int i = 0; i < pieces.size(); i++) {
            t += VideoComposer.lengthUs(pieces.get(i).s, i == 0);
        }
        return t;
    }

    private void addClip(Uri uri) {
        if (busy || videoUri == null) {
            return;
        }
        long dMs = durationOf(uri);
        if (dMs <= 0) {
            Toast.makeText(this, "Questo video non sembra leggibile dal telefono.", Toast.LENGTH_LONG).show();
            return;
        }
        Snap sn = snap();
        long tUs = timeline.pos * 1000;
        long cum = 0;
        int k = pieces.size() - 1;
        long lenK = 0, trK = 0;
        for (int i = 0; i < pieces.size(); i++) {
            VideoComposer.Seg sg = pieces.get(i).s;
            long len = VideoComposer.lengthUs(sg, i == 0);
            if (tUs < cum + len || i == pieces.size() - 1) {
                k = i;
                lenK = len;
                trK = (i > 0 && sg.trans > 0) ? sg.transUs : 0;
                break;
            }
            cum += len;
        }
        Piece pk = pieces.get(k);
        long off = tUs - cum - trK;
        long body = pk.s.bodyUs();
        int at;
        long insAtUs;
        if (!pk.s.isPlain()) {
            // clip modificata (velocita', reverse...): non si divide, si inserisce prima o dopo
            if (off < body / 2) {
                at = k;
                insAtUs = cum;
            } else {
                at = k + 1;
                insAtUs = cum + lenK;
            }
        } else if (off <= 300000) {
            at = k;
            insAtUs = cum;
        } else if (body - off <= 300000) {
            at = k + 1;
            insAtUs = cum + lenK;
        } else {
            VideoComposer.Seg s2 = pk.s.copy();
            s2.startUs = pk.s.startUs + off;
            s2.trans = 0;
            pk.s.endUs = pk.s.startUs + off;
            pieces.add(k + 1, new Piece(s2, pk.name));
            at = k + 1;
            insAtUs = cum + trK + off;
        }
        long lenUs = dMs * 1000;
        pieces.add(at, new Piece(new VideoComposer.Seg(uri, 0, lenUs), queryName(uri)));
        remapInsert(insAtUs / 1000, lenUs / 1000);
        rebuild(insAtUs / 1000, sn);
    }

    private void deleteClip(int idx) {
        if (busy || pieces.size() < 2 || idx < 0 || idx >= pieces.size()) {
            return;
        }
        Snap sn = snap();
        long cum = 0;
        for (int i = 0; i < idx; i++) {
            cum += VideoComposer.lengthUs(pieces.get(i).s, i == 0);
        }
        long len = VideoComposer.lengthUs(pieces.get(idx).s, idx == 0);
        pieces.remove(idx);
        if (idx == 0 && !pieces.isEmpty()) {
            // il nuovo primo tratto non ha niente prima: la sua transizione sparisce
            VideoComposer.Seg f = pieces.get(0).s;
            if (f.trans > 0) {
                len += f.transUs;
                f.trans = 0;
            }
        } else if (idx > 0 && idx < pieces.size()) {
            // tratti contigui dello stesso file senza transizione: si riuniscono
            VideoComposer.Seg a = pieces.get(idx - 1).s, b = pieces.get(idx).s;
            if (b.trans == 0 && a.uri.equals(b.uri) && a.endUs == b.startUs && a.isPlain() && b.isPlain()) {
                a.endUs = b.endUs;
                pieces.remove(idx);
            }
        }
        remapRemove(cum / 1000, (cum + len) / 1000);
        rebuild(cum / 1000, sn);
    }

    private void setTrans(int idx, int type, long durUs, float[] tp) {
        if (busy || idx <= 0 || idx >= pieces.size()) {
            return;
        }
        Snap sn = snap();
        long cum = 0;
        for (int i = 0; i < idx; i++) {
            cum += VideoComposer.lengthUs(pieces.get(i).s, i == 0);
        }
        VideoComposer.Seg sg = pieces.get(idx).s;
        long oldT = sg.trans > 0 ? sg.transUs : 0;
        long newT = type > 0 ? durUs : 0;
        sg.trans = type;
        sg.transUs = newT;
        sg.tp = type > 0 ? tp.clone() : new float[4];
        if (newT > oldT) {
            remapInsert((cum + oldT) / 1000, (newT - oldT) / 1000);
        } else if (newT < oldT) {
            remapRemove((cum + newT) / 1000, (cum + oldT) / 1000);
        }
        VideoComposer.Seg old = sn.pieces.get(idx).s;
        if (newT == oldT && sg.trans == old.trans && java.util.Arrays.equals(sg.tp, old.tp)) {
            return;
        }
        rebuild(cum / 1000, sn);
    }

    /** Inserisce len ms nel punto t: tagli, selezione ed effetti dopo t slittano. */
    private void remapInsert(long t, long len) {
        long oldDur = durMs;
        List<long[]> nc = new ArrayList<>();
        for (long[] c : cuts) {
            if (c[0] >= t) {
                nc.add(new long[]{c[0] + len, c[1] + len});
            } else if (c[1] > t) {
                nc.add(new long[]{c[0], t});
                nc.add(new long[]{t + len, c[1] + len});
            } else {
                nc.add(c);
            }
        }
        cuts.clear();
        cuts.addAll(nc);
        if (markA >= t) {
            markA += len;
        }
        if (markB >= t) {
            markB += len;
        }
        long newDur = totalUs() / 1000;
        for (VideoFx.Item it : fxItems) {
            boolean toEnd = it.endUs >= oldDur * 1000;
            if (it.startUs >= t * 1000) {
                it.startUs += len * 1000;
                it.endUs += len * 1000;
            } else if (it.endUs > t * 1000) {
                it.endUs += len * 1000;
            }
            if (toEnd) {
                it.endUs = newDur * 1000 + 1000;
            }
        }
    }

    /** Toglie l'intervallo [a,b) ms: il video si ricompatta, i tempi successivi tornano indietro. */
    private void remapRemove(long a, long b) {
        long oldDur = durMs;
        long l = b - a;
        List<long[]> nc = new ArrayList<>();
        for (long[] c : cuts) {
            long x = mapRem(c[0], a, b, l), y = mapRem(c[1], a, b, l);
            if (y - x > 0) {
                nc.add(new long[]{x, y});
            }
        }
        cuts.clear();
        cuts.addAll(nc);
        if (markA >= 0) {
            markA = mapRem(markA, a, b, l);
        }
        if (markB >= 0) {
            markB = mapRem(markB, a, b, l);
        }
        if (markA >= 0 && markB >= 0 && markB <= markA) {
            markA = markB = -1;
        }
        long newDur = totalUs() / 1000;
        for (java.util.Iterator<VideoFx.Item> iter = fxItems.iterator(); iter.hasNext(); ) {
            VideoFx.Item it = iter.next();
            boolean toEnd = it.endUs >= oldDur * 1000;
            long x = mapRem(it.startUs / 1000, a, b, l) * 1000 + it.startUs % 1000;
            long y = mapRem(it.endUs / 1000, a, b, l) * 1000 + it.endUs % 1000;
            if (toEnd) {
                y = newDur * 1000 + 1000;
            }
            if (y - x < 1000 && it.endUs > it.startUs) {
                iter.remove();
                continue;
            }
            it.startUs = x;
            it.endUs = y;
        }
    }

    private static long mapRem(long x, long a, long b, long l) {
        return x <= a ? x : x >= b ? x - l : a;
    }

    /** Ricostruisce il video montato (o torna all'originale) e aggiorna anteprima e timeline. */
    private void rebuild(final long seekMs, final Snap undo) {
        final int gen = ++rebuildGen;
        boolean plain = pieces.size() == 1 && pieces.get(0).s.uri.equals(origUri)
                && pieces.get(0).s.startUs == 0 && pieces.get(0).s.endUs == origDurMs * 1000 && pieces.get(0).s.isPlain();
        soundPause();
        videoView.pause();
        playBtn.setText("▶");
        if (plain) {
            applyVideo(origUri, origDurMs, seekMs);
            return;
        }
        final List<VideoComposer.Seg> segs = new ArrayList<>();
        for (Piece pc : pieces) {
            segs.add(pc.s.copy());
        }
        busy = true;
        exportBtn.setEnabled(false);
        status.setText("");
        setEditLoading(true, "Montaggio delle clip...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File out = new File(getCacheDir(), "clips_" + (++compSeq) + ".mp4");
                try {
                    int[] wh = VideoComposer.fitSize(origVw, origVh, PROXY_SIDE);
                    VideoComposer.compose(VideoEditorActivity.this, segs, out, getCacheDir(), wh[0], wh[1],
                            Math.max(1500000, wh[0] * wh[1] * 4), new VideoProcessor.Progress() {
                                @Override
                                public void onProgress(final String stage, final int pct) {
                                    runOnUiThread(() -> {
                                        if (gen == rebuildGen) {
                                            setEditLoading(true, stage + " " + pct + "%");
                                        }
                                    });
                                }
                            });
                    final long d = durationOf(Uri.fromFile(out));
                    runOnUiThread(() -> {
                        busy = false;
                        exportBtn.setEnabled(true);
                        setEditLoading(false, null);
                        if (gen != rebuildGen || d <= 0) {
                            //noinspection ResultOfMethodCallIgnored
                            out.delete();
                            if (gen == rebuildGen) {
                                restore(undo);
                                status.setText("Montaggio non riuscito: il video prodotto non e' leggibile.");
                                Toast.makeText(VideoEditorActivity.this, "Montaggio non riuscito: il video "
                                        + "prodotto non e' leggibile.", Toast.LENGTH_LONG).show();
                                rebuildPlain();
                            }
                            return;
                        }
                        if (compFile != null) {
                            //noinspection ResultOfMethodCallIgnored
                            compFile.delete();
                        }
                        compFile = out;
                        status.setText("");
                        applyVideo(Uri.fromFile(out), d, seekMs);
                    });
                } catch (final Throwable t) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    runOnUiThread(() -> {
                        busy = false;
                        exportBtn.setEnabled(true);
                        setEditLoading(false, null);
                        if (gen == rebuildGen) {
                            restore(undo);
                            String m = t.getMessage();
                            status.setText("Montaggio non riuscito" + (m != null && !m.isEmpty() ? ": " + m : "")
                                    + ". Il video e' rimasto com'era.");
                            Toast.makeText(VideoEditorActivity.this, status.getText(), Toast.LENGTH_LONG).show();
                            rebuildPlain();
                        }
                    });
                }
            }
        }).start();
    }

    /** Dopo un errore: ripristina la vista sul video che c'era prima (gia' presente su disco). */
    /** Caricamento durante la modifica: cerchio sopra l'anteprima e "Caricamento..." nella timeline. */
    private void setEditLoading(boolean on, String msg) {
        if (editLoadBox == null) {
            return;
        }
        editLoadBox.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && msg != null) {
            editLoadText.setText(msg);
        }
        timeline.setLoading(on);
    }

    private void rebuildPlain() {
        refreshMarks();
        refreshFx();
        refreshClips();
    }

    private void applyVideo(Uri uri, long newDurMs, long seekMs) {
        videoUri = uri;
        durMs = Math.max(1, newDurMs);
        baseVw = origVw;
        baseVh = origVh;
        applyRotation();
        timeline.setDuration(durMs);
        long sp = Math.max(0, Math.min(durMs - 1, seekMs));
        timeline.setPos(sp);
        videoView.setDisplaySize(dispVw, dispVh);
        videoView.setVideoURI(uri);
        videoView.seekTo((int) Math.max(1, sp));
        overlay.setPos(sp);
        timeline.loadThumbs(this, uri);
        pickSub.setText(fmt(durMs) + " · tocca per cambiare video");
        refreshMarks();
        refreshFx();
        refreshClips();
        updateTexts();
        final Uri du = uri;
        new Thread(() -> {
            final String[] d = describe(du);
            runOnUiThread(() -> {
                if (du.equals(videoUri)) {
                    showInfo(1, d);
                }
            });
        }).start();
    }

    private void refreshClips() {
        if (clipList == null) {
            return;
        }
        clipList.removeAllViews();
        List<Long> joins = new ArrayList<>();
        long cum = 0;
        for (int i = 0; i < pieces.size(); i++) {
            final int idx = i;
            final Piece pc = pieces.get(i);
            VideoComposer.Seg sg = pc.s;
            long len = VideoComposer.lengthUs(sg, i == 0);
            long tr = (i > 0 && sg.trans > 0) ? sg.transUs : 0;
            if (i > 0) {
                joins.add(cum / 1000);
                LinearLayout jr = new LinearLayout(this);
                jr.setGravity(Gravity.CENTER_VERTICAL);
                jr.setPadding(dp(12), dp(2), dp(4), dp(2));
                String jt = "↔  Transizione: " + VideoComposer.TRANS_NAMES[sg.trans]
                        + (sg.trans > 0 ? String.format(Locale.US, " · %.1f s", sg.transUs / 1e6) : "");
                jr.addView(text(jt, 13, R.color.accent, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
                jr.addView(btn("✎", C_CARD, C_ACCENT, v -> transDialog(idx)),
                        new LinearLayout.LayoutParams(dp(44), dp(36)));
                clipList.addView(jr, lp(MATCH, WRAP, 0, 4));
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(round(C_CARD2, 12));
            row.setPadding(dp(12), dp(8), dp(4), dp(8));
            TextView ic = text("🎞", 18, R.color.accent, true);
            ic.setGravity(Gravity.CENTER);
            row.addView(ic, new LinearLayout.LayoutParams(dp(30), WRAP));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView nm = text((sg.uri.equals(origUri) ? "Video" : "Clip") + " · " + pc.name, 14,
                    R.color.text, true);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tx.addView(nm);
            tx.addView(text(fmt((cum + tr) / 1000) + " → " + fmt((cum + len) / 1000) + "  ("
                    + fmt(sg.bodyUs() / 1000) + ")", 12, R.color.text_dim, false));
            if (!sg.isPlain()) {
                TextView sm = text(clipSummary(sg), 12, R.color.accent, false);
                tx.addView(sm);
            }
            row.addView(tx, lp(0, WRAP, 8, 0, 1f));
            Button infoB = btn("i", C_BTN_ON_CARD, C_ACCENT, v -> showClipInfo(pc));
            infoB.setPadding(0, 0, 0, 0);
            row.addView(infoB, lp(dp(36), dp(34), 0, 0));
            if (pieces.size() > 1) {
                Button delB = btn("✕", C_BTN_ON_CARD, C_DANGER, v -> deleteClip(idx));
                delB.setPadding(0, 0, 0, 0);
                row.addView(delB, lp(dp(36), dp(34), 6, 0));
            }
            final long seekTo = cum / 1000;
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (videoUri == null) {
                    return;
                }
                seekExact(seekTo);
                timeline.setPos(seekTo);
                if (overlay != null) {
                    overlay.setPos(seekTo);
                }
                updateTexts();
            });
            LinearLayout cardc = new LinearLayout(this);
            cardc.setOrientation(LinearLayout.VERTICAL);
            cardc.setBackground(round(C_CARD2, 12));
            row.setBackground(null);
            cardc.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            Button editB = btn("Modifica clip: velocita', reverse, effetti", C_BTN_ON_CARD, C_ACCENT,
                    v -> clipDialog(idx));
            editB.setTextSize(13);
            cardc.addView(editB, lp(MATCH, dp(38), 12, 0));
            ((LinearLayout.LayoutParams) editB.getLayoutParams()).setMargins(dp(12), 0, dp(12), dp(10));
            clipList.addView(cardc, lp(MATCH, WRAP, 0, 4));
            cum += len;
        }
        timeline.setJoins(joins);
    }

    /** Popup con le informazioni del file di una clip, in forma di tabella. */
    private void showClipInfo(Piece pc) {
        Uri u = pc.s.uri;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"#File", null});
        rows.add(new String[]{"Nome", queryName(u)});
        String path = null, rel = null, mime = null;
        long size = -1, mod = 0;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) {
                    size = c.getLong(i);
                }
                i = c.getColumnIndex("_data");
                if (i >= 0 && !c.isNull(i)) {
                    path = c.getString(i);
                }
                i = c.getColumnIndex("relative_path");
                if (i >= 0 && !c.isNull(i)) {
                    rel = c.getString(i);
                }
                i = c.getColumnIndex("mime_type");
                if (i >= 0 && !c.isNull(i)) {
                    mime = c.getString(i);
                }
                i = c.getColumnIndex("date_modified");
                if (i >= 0 && !c.isNull(i)) {
                    mod = c.getLong(i) * 1000;
                }
            }
        } catch (Exception ignored) {
        }
        if (path == null && "file".equals(u.getScheme())) {
            path = u.getPath();
        }
        if (path == null && rel != null) {
            path = rel + queryName(u);
        }
        rows.add(new String[]{"Percorso", path != null ? path : u.toString()});
        if (size < 0) {
            try (android.content.res.AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(u, "r")) {
                if (afd != null) {
                    size = afd.getLength();
                }
            } catch (Exception ignored) {
            }
        }
        if (size >= 0) {
            rows.add(new String[]{"Dimensione", humanSize(size)});
        }
        if (mime == null) {
            mime = getContentResolver().getType(u);
        }
        if (mime != null) {
            rows.add(new String[]{"Tipo", mime});
        }
        if (mod > 0) {
            rows.add(new String[]{"Modificato", new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
                    .format(new java.util.Date(mod))});
        }
        rows.add(new String[]{"#Video", null});
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(this, u);
            String w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            String dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String br = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String date = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (w != null && h != null) {
                int rr = rot == null ? 0 : Integer.parseInt(rot);
                rows.add(new String[]{"Risoluzione", (rr % 180 == 0 ? w + " x " + h : h + " x " + w)
                        + (rr != 0 ? " (ruotato di " + rr + "°)" : "")});
            }
            if (dur != null) {
                rows.add(new String[]{"Durata del file", fmt(Long.parseLong(dur))});
            }
            if (br != null) {
                rows.add(new String[]{"Bitrate totale",
                        String.format(Locale.US, "%.1f Mb/s", Long.parseLong(br) / 1e6)});
            }
            if (date != null) {
                rows.add(new String[]{"Data di ripresa", date});
            }
        } catch (Exception ignored) {
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
        List<String[]> audioRows = new ArrayList<>();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, u, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) {
                    continue;
                }
                if (m.startsWith("video/")) {
                    rows.add(new String[]{"Codec", m.substring(6)});
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        String fr;
                        try {
                            fr = Math.round(f.getFloat(MediaFormat.KEY_FRAME_RATE)) + " fps";
                        } catch (Exception e1) {
                            fr = f.getInteger(MediaFormat.KEY_FRAME_RATE) + " fps";
                        }
                        rows.add(new String[]{"Fotogrammi", fr});
                    }
                } else if (m.startsWith("audio/")) {
                    audioRows.add(new String[]{"Codec", m.substring(6)});
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        audioRows.add(new String[]{"Frequenza", f.getInteger(MediaFormat.KEY_SAMPLE_RATE) + " Hz"});
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        audioRows.add(new String[]{"Canali", ch == 1 ? "mono" : ch == 2 ? "stereo" : ch + " canali"});
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            ex.release();
        }
        rows.add(new String[]{"#Audio", null});
        if (audioRows.isEmpty()) {
            rows.add(new String[]{"Traccia audio", "assente"});
        } else {
            rows.addAll(audioRows);
        }
        rows.add(new String[]{"#Nel montaggio", null});
        rows.add(new String[]{"Parte usata", fmt(pc.s.startUs / 1000) + " → " + fmt(pc.s.endUs / 1000)});
        rows.add(new String[]{"Durata usata", fmt((pc.s.endUs - pc.s.startUs) / 1000)});
        rows.add(new String[]{"Ruolo", pc.s.uri.equals(origUri) ? "video principale" : "clip aggiunta"});

        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(C_CARD, 20));
        box.setPadding(dp(18), dp(18), dp(18), dp(16));
        TextView ttl = text(pc.name, 17, R.color.text, true);
        ttl.setSingleLine(true);
        ttl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(ttl);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout tbl = new LinearLayout(this);
        tbl.setOrientation(LinearLayout.VERTICAL);
        sv.addView(tbl, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout cur = null;
        for (String[] r : rows) {
            if (r[1] == null) {
                TextView hd = text(r[0].substring(1), 12, R.color.accent, true);
                hd.setPadding(dp(4), dp(14), 0, dp(6));
                tbl.addView(hd);
                cur = new LinearLayout(this);
                cur.setOrientation(LinearLayout.VERTICAL);
                cur.setBackground(round(C_CARD2, 12));
                cur.setPadding(dp(14), dp(8), dp(14), dp(8));
                tbl.addView(cur, new LinearLayout.LayoutParams(MATCH, WRAP));
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(5), 0, dp(5));
            TextView k = text(r[0], 13, R.color.text_dim, false);
            TextView v = text(r[1], 13, R.color.text, false);
            v.setGravity(Gravity.END);
            row.addView(k, new LinearLayout.LayoutParams(0, WRAP, 0.8f));
            row.addView(v, new LinearLayout.LayoutParams(0, WRAP, 1.4f));
            cur.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        box.addView(btn("Chiudi", C_ACCENT, 0xFF000000, v -> d.dismiss()), lp(MATCH, dp(46), 0, 14));
        d.setContentView(box);
        android.view.Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            win.setDimAmount(0.6f);
            win.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
        }
        d.show();
    }

    private static String humanSize(long b) {
        if (b < 1024) {
            return b + " B";
        }
        double kb = b / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024;
        return mb < 1024 ? String.format(Locale.US, "%.1f MB", mb) : String.format(Locale.US, "%.2f GB", mb / 1024);
    }

    private static String speedLabel(float v) {
        return "×" + (v == Math.round(v) ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.2f", v)
                .replaceAll("0+$", "").replaceAll("\\.$", ""));
    }

    private static String clipSummary(VideoComposer.Seg sg) {
        StringBuilder sb = new StringBuilder();
        if (sg.speed != 1f) {
            sb.append("Velocita' ").append(speedLabel(sg.speed));
        }
        if (sg.reverse) {
            sb.append(sb.length() > 0 ? " · " : "").append("Reverse");
        }
        if (sg.look != 0) {
            sb.append(sb.length() > 0 ? " · " : "").append(VideoComposer.LOOK_NAMES[sg.look]);
        }
        if (sg.mirH || sg.mirV) {
            sb.append(sb.length() > 0 ? " · " : "").append("Specchio");
        }
        if (sg.vol != 1f) {
            sb.append(sb.length() > 0 ? " · " : "").append(sg.vol == 0f ? "Muto"
                    : "Volume " + Math.round(sg.vol * 100) + "%");
        }
        return sb.toString();
    }

    private static final float[] SPEEDS = {0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f, 10f};

    /** Popup per modificare una clip: velocita', reverse, aspetto, specchio e volume. */
    private void clipDialog(final int idx) {
        if (busy || idx < 0 || idx >= pieces.size()) {
            return;
        }
        final VideoComposer.Seg sg = pieces.get(idx).s;
        final float[] speed = {sg.speed};
        final boolean[] rev = {sg.reverse}, mh = {sg.mirH}, mv = {sg.mirV};
        final int[] look = {sg.look};
        final float[] vol = {sg.vol};
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(C_CARD, 20));
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        TextView ttl = text("Modifica clip", 18, R.color.text, true);
        box.addView(ttl);
        TextView sub = text(pieces.get(idx).name, 12, R.color.text_dim, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(sub);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, new ViewGroup.LayoutParams(MATCH, WRAP));
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        final TextView sumText = text("", 13, R.color.accent, true);

        // ---- velocita'
        TextView t1 = text("Velocita'", 13, R.color.text_dim, false);
        t1.setPadding(0, dp(14), 0, dp(2));
        body.addView(t1);
        final TextView[] sc = new TextView[SPEEDS.length];
        final Runnable[] upd = new Runnable[1];
        LinearLayout line = null;
        for (int i = 0; i < SPEEDS.length; i++) {
            final int si = i;
            if (i % 4 == 0) {
                line = new LinearLayout(this);
                body.addView(line, lp(MATCH, WRAP, 0, 6));
            }
            sc[i] = Ui.chip(this, speedLabel(SPEEDS[i]));
            sc[i].setTextSize(13);
            sc[i].setOnClickListener(v -> {
                speed[0] = SPEEDS[si];
                upd[0].run();
            });
            line.addView(sc[i], lp(0, dp(40), i % 4 == 0 ? 0 : 6, 0, 1f));
        }

        // ---- direzione
        TextView t2 = text("Direzione", 13, R.color.text_dim, false);
        t2.setPadding(0, dp(16), 0, 0);
        body.addView(t2);
        final TextView revChip = Ui.chip(this, "");
        revChip.setTextSize(13);
        revChip.setOnClickListener(v -> {
            rev[0] = !rev[0];
            upd[0].run();
        });
        body.addView(revChip, lp(MATCH, dp(42), 0, 6));

        // ---- aspetto
        TextView t3 = text("Aspetto", 13, R.color.text_dim, false);
        t3.setPadding(0, dp(16), 0, 0);
        body.addView(t3);
        final TextView[] lc = new TextView[VideoComposer.LOOK_NAMES.length];
        LinearLayout ll = null;
        for (int i = 0; i < lc.length; i++) {
            final int li = i;
            if (i % 2 == 0) {
                ll = new LinearLayout(this);
                body.addView(ll, lp(MATCH, WRAP, 0, 6));
            }
            lc[i] = Ui.chip(this, VideoComposer.LOOK_NAMES[i]);
            lc[i].setTextSize(13);
            lc[i].setOnClickListener(v -> {
                look[0] = li;
                upd[0].run();
            });
            ll.addView(lc[i], lp(0, dp(40), i % 2 == 0 ? 0 : 6, 0, 1f));
        }
        final TextView mhChip = Ui.chip(this, ""), mvChip = Ui.chip(this, "");
        mhChip.setTextSize(13);
        mvChip.setTextSize(13);
        mhChip.setOnClickListener(v -> {
            mh[0] = !mh[0];
            upd[0].run();
        });
        mvChip.setOnClickListener(v -> {
            mv[0] = !mv[0];
            upd[0].run();
        });
        LinearLayout mr = new LinearLayout(this);
        mr.addView(mhChip, lp(0, dp(40), 0, 0, 1f));
        mr.addView(mvChip, lp(0, dp(40), 6, 0, 1f));
        body.addView(mr, lp(MATCH, WRAP, 0, 6));

        // ---- audio
        final TextView volText = text("", 14, R.color.text, false);
        volText.setPadding(0, dp(16), 0, 0);
        body.addView(volText);
        final SeekBar volBar = seek(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                vol[0] = p / 10f;
                if (upd[0] != null) {
                    upd[0].run();
                }
            }
        });
        volBar.setMax(20);
        volBar.setProgress(Math.round(sg.vol * 10));
        body.addView(volBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        body.addView(sumText, lp(MATCH, WRAP, 0, 14));
        TextView hint = text("Con velocita' diversa da ×1 anche il tono dell'audio cambia. Il reverse e' piu' lento "
                + "da elaborare perche' legge un fotogramma alla volta. Gli effetti e i testi che hai gia' messo "
                + "restano agli stessi istanti del video.", 11, R.color.text_dim, false);
        body.addView(hint, lp(MATCH, WRAP, 0, 8));

        upd[0] = () -> {
            for (int i = 0; i < SPEEDS.length; i++) {
                Ui.styleChip(this, sc[i], Math.abs(SPEEDS[i] - speed[0]) < 0.001f);
            }
            revChip.setText(rev[0] ? "Reverse: attivo (la clip va dalla fine all'inizio)"
                    : "Reverse: disattivato");
            Ui.styleChip(this, revChip, rev[0]);
            for (int i = 0; i < lc.length; i++) {
                Ui.styleChip(this, lc[i], look[0] == i);
            }
            mhChip.setText(mh[0] ? "Specchio orizzontale: si" : "Specchio orizzontale");
            mvChip.setText(mv[0] ? "Specchio verticale: si" : "Specchio verticale");
            Ui.styleChip(this, mhChip, mh[0]);
            Ui.styleChip(this, mvChip, mv[0]);
            volText.setText(vol[0] == 0f ? "Audio della clip: muto"
                    : "Volume dell'audio della clip: " + Math.round(vol[0] * 100) + "%");
            long oldB = sg.bodyUs();
            long newB = Math.max(1, Math.round((sg.endUs - sg.startUs) / (double) speed[0]));
            sumText.setText("Durata della clip: " + fmt(oldB / 1000) + " → " + fmt(newB / 1000));
        };
        upd[0].run();

        LinearLayout br = new LinearLayout(this);
        br.addView(btn("Ripristina", C_CARD2, C_DANGER, v -> {
            speed[0] = 1f;
            rev[0] = false;
            mh[0] = false;
            mv[0] = false;
            look[0] = 0;
            vol[0] = 1f;
            volBar.setProgress(10);
            upd[0].run();
        }), lp(0, dp(46), 0, 0, 1f));
        br.addView(btn("Annulla", C_CARD2, C_TEXT, v -> d.dismiss()), lp(0, dp(46), 6, 0, 1f));
        br.addView(btn("Applica", C_ACCENT, Color.BLACK, v -> {
            d.dismiss();
            setClipMods(idx, speed[0], rev[0], vol[0], mh[0], mv[0], look[0]);
        }), lp(0, dp(46), 6, 0, 1f));
        box.addView(br, lp(MATCH, WRAP, 0, 14));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.9f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f), maxH);
        }
        d.show();
    }

    private void setClipMods(int idx, float speed, boolean reverse, float vol, boolean mh, boolean mv, int look) {
        if (busy || idx < 0 || idx >= pieces.size()) {
            return;
        }
        VideoComposer.Seg sg = pieces.get(idx).s;
        if (sg.speed == speed && sg.reverse == reverse && sg.vol == vol && sg.mirH == mh && sg.mirV == mv
                && sg.look == look) {
            return;
        }
        Snap sn = snap();
        long cum = 0;
        for (int i = 0; i < idx; i++) {
            cum += VideoComposer.lengthUs(pieces.get(i).s, i == 0);
        }
        long oldLen = VideoComposer.lengthUs(sg, idx == 0);
        sg.speed = speed;
        sg.reverse = reverse;
        sg.vol = vol;
        sg.mirH = mh;
        sg.mirV = mv;
        sg.look = look;
        long newLen = VideoComposer.lengthUs(sg, idx == 0);
        if (newLen > oldLen) {
            remapInsert((cum + oldLen) / 1000, (newLen - oldLen) / 1000);
        } else if (newLen < oldLen) {
            remapRemove((cum + newLen) / 1000, (cum + oldLen) / 1000);
        }
        rebuild(cum / 1000, sn);
    }

    private void transDialog(final int idx) {
        if (busy || idx <= 0 || idx >= pieces.size()) {
            return;
        }
        final VideoComposer.Seg sg = pieces.get(idx).s;
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(C_CARD, 20));
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        box.addView(text("Transizione", 18, R.color.text, true));
        final ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, new ViewGroup.LayoutParams(MATCH, WRAP));
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        final int[] type = {sg.trans};
        final float[] vals = sg.trans > 0 ? sg.tp.clone() : VideoComposer.defaults(1);
        if (sg.trans <= 0) {
            java.util.Arrays.fill(vals, 0f);
        }
        final TextView[] chips = new TextView[VideoComposer.TRANS_NAMES.length];
        final TextView durText = text("", 13, R.color.text, true);
        final SeekBar durBar = new SeekBar(this);
        final LinearLayout paramBox = new LinearLayout(this);
        paramBox.setOrientation(LinearLayout.VERTICAL);

        TextView tl0 = text("Tipo", 13, R.color.text_dim, false);
        tl0.setPadding(0, dp(12), 0, 0);
        body.addView(tl0);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = null;
        for (int i = 0; i < chips.length; i++) {
            final int ti = i;
            if (i % 2 == 0) {
                line = new LinearLayout(this);
                grid.addView(line, lp(MATCH, WRAP, 0, 8));
            }
            chips[i] = Ui.chip(this, VideoComposer.TRANS_NAMES[i]);
            line.addView(chips[i], lp(0, dp(42), i % 2 == 0 ? 0 : 8, 0, 1f));
            chips[i].setOnClickListener(v -> {
                if (type[0] == ti) {
                    return;
                }
                type[0] = ti;
                float[] df = VideoComposer.defaults(ti);
                System.arraycopy(df, 0, vals, 0, 4);
                for (int j = 0; j < chips.length; j++) {
                    Ui.styleChip(this, chips[j], j == ti);
                }
                buildTransParams(paramBox, ti, vals, durBar);
                durText.setVisibility(ti > 0 ? View.VISIBLE : View.GONE);
                durBar.setVisibility(ti > 0 ? View.VISIBLE : View.GONE);
            });
        }
        body.addView(grid, lp(MATCH, WRAP, 0, 2));

        body.addView(durText, lp(MATCH, WRAP, 0, 16));
        durBar.setMax(27);
        durBar.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        durBar.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        int cur = sg.trans > 0 ? (int) Math.max(0, Math.min(27, Math.round(sg.transUs / 100000.0) - 3)) : 3;
        durBar.setProgress(cur);
        durText.setText(String.format(Locale.US, "Durata: %.1f s", (cur + 3) / 10.0));
        durBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                durText.setText(String.format(Locale.US, "Durata: %.1f s", (p + 3) / 10.0));
                // i tempi dei parametri sono in frazioni della durata: aggiorna le scritte
                refreshTransTimes(paramBox, (p + 3) / 10f);
            }
        });
        body.addView(durBar, lp(MATCH, WRAP, 0, 4));
        body.addView(paramBox, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView hint = text("La transizione si aggiunge alla durata del video: il fotogramma finale della clip "
                + "precedente e quello iniziale della successiva restano fermi durante l'effetto.", 12,
                R.color.text_dim, false);
        body.addView(hint, lp(MATCH, WRAP, 0, 14));

        for (int j = 0; j < chips.length; j++) {
            Ui.styleChip(this, chips[j], j == type[0]);
        }
        durText.setVisibility(type[0] > 0 ? View.VISIBLE : View.GONE);
        durBar.setVisibility(type[0] > 0 ? View.VISIBLE : View.GONE);
        buildTransParams(paramBox, type[0], vals, durBar);

        LinearLayout br = new LinearLayout(this);
        br.addView(btn("Annulla", C_CARD2, C_TEXT, v -> d.dismiss()), lp(0, dp(46), 0, 0, 1f));
        br.addView(btn("Applica", C_ACCENT, Color.BLACK, v -> {
            d.dismiss();
            setTrans(idx, type[0], (durBar.getProgress() + 3) * 100000L, vals);
        }), lp(0, dp(46), 8, 0, 1f));
        box.addView(br, lp(MATCH, WRAP, 0, 14));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.88f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f), maxH);
        }
        d.show();
    }

    private final java.util.List<Object[]> timeLabels = new ArrayList<>();

    /** Costruisce i comandi dei parametri della transizione scelta. */
    private void buildTransParams(LinearLayout box, int type, final float[] vals, SeekBar durBar) {
        box.removeAllViews();
        timeLabels.clear();
        VideoComposer.Param[] ps = VideoComposer.params(type);
        if (ps.length == 0) {
            return;
        }
        TextView t = text("Personalizza", 13, R.color.text_dim, false);
        t.setPadding(0, dp(18), 0, 0);
        box.addView(t);
        for (int i = 0; i < ps.length; i++) {
            final int pi = i;
            final VideoComposer.Param pr = ps[i];
            final TextView lab = text("", 14, R.color.text, true);
            lab.setPadding(0, dp(14), 0, 0);
            box.addView(lab);
            if (pr.hint != null) {
                box.addView(text(pr.hint, 11, R.color.text_dim, false));
            }
            if (pr.kind == VideoComposer.Param.CHOICE) {
                lab.setText(pr.label);
                final TextView[] cc = new TextView[pr.choices.length];
                LinearLayout row = null;
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                for (int k = 0; k < cc.length; k++) {
                    final int ki = k;
                    if (k % 2 == 0) {
                        row = new LinearLayout(this);
                        col.addView(row, lp(MATCH, WRAP, 0, 6));
                    }
                    cc[k] = Ui.chip(this, pr.choices[k]);
                    cc[k].setTextSize(12);
                    Ui.styleChip(this, cc[k], Math.round(vals[pi]) == k);
                    cc[k].setOnClickListener(v -> {
                        vals[pi] = ki;
                        for (int m = 0; m < cc.length; m++) {
                            Ui.styleChip(this, cc[m], m == ki);
                        }
                    });
                    row.addView(cc[k], lp(0, dp(38), k % 2 == 0 ? 0 : 6, 0, 1f));
                }
                box.addView(col, new LinearLayout.LayoutParams(MATCH, WRAP));
                continue;
            }
            final SeekBar sb = new SeekBar(this);
            sb.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
            sb.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
            if (pr.kind == VideoComposer.Param.COUNT) {
                sb.setMax((int) (pr.max - pr.min));
                sb.setProgress(Math.round(vals[pi] - pr.min));
            } else {
                sb.setMax(100);
                sb.setProgress(Math.round(vals[pi] * 100));
            }
            final Runnable upd = () -> {
                if (pr.kind == VideoComposer.Param.COUNT) {
                    lab.setText(pr.label + ": " + Math.round(vals[pi]));
                } else if (pr.kind == VideoComposer.Param.TIME) {
                    float dur = (durBar.getProgress() + 3) / 10f;
                    lab.setText(pr.label + ": " + String.format(Locale.US, "%.1f s (%d%%)", vals[pi] * dur,
                            Math.round(vals[pi] * 100)));
                } else {
                    lab.setText(pr.label + ": " + Math.round(vals[pi] * 100) + "%");
                }
            };
            sb.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override
                public void onProgressChanged(SeekBar s2, int p, boolean user) {
                    vals[pi] = pr.kind == VideoComposer.Param.COUNT ? pr.min + p : p / 100f;
                    upd.run();
                }
            });
            upd.run();
            box.addView(sb, lp(MATCH, WRAP, 0, 2));
            if (pr.kind == VideoComposer.Param.TIME) {
                timeLabels.add(new Object[]{upd});
            }
        }
    }

    private void refreshTransTimes(LinearLayout box, float durSec) {
        for (Object[] o : timeLabels) {
            ((Runnable) o[0]).run();
        }
    }


    private void rotate(int delta) {
        if (videoUri == null) {
            return;
        }
        extraRot = ((extraRot + delta) % 360 + 360) % 360;
        applyRotation();
    }

    private void applyRotation() {
        boolean sw = extraRot == 90 || extraRot == 270;
        dispVw = sw ? baseVh : baseVw;
        dispVh = sw ? baseVw : baseVh;
        overlay.setVideoSize(dispVw, dispVh);
        videoView.setDisplaySize(dispVw, dispVh);
        videoView.setExtraRotation(extraRot);
        timeline.setExtraRot(extraRot);
        if (rotText != null) {
            rotText.setText(extraRot + "°");
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
        FxDialog.show(this, item, editing, videoUri, durMs, cur, dispVw, dispVh, extraRot, new FxDialog.Callback() {
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
        soundPause();
        cuts.clear();
        fxItems.clear();
        refreshFx();
        markA = markB = -1;
        durMs = 0;
        dispVw = dispVh = baseVw = baseVh = 0;
        extraRot = 0;
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(this, uri);
            try {
                int vw0 = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int vh0 = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rr = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                int rot = rr == null ? 0 : Integer.parseInt(rr);
                boolean sw = rot == 90 || rot == 270;
                baseVw = sw ? vh0 : vw0;
                baseVh = sw ? vw0 : vh0;
            } catch (Exception ignored) {
            }
            applyRotation();
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
        origUri = uri;
        origDurMs = durMs;
        origVw = baseVw;
        origVh = baseVh;
        rebuildGen++;
        pieces.clear();
        pieces.add(new Piece(new VideoComposer.Seg(uri, 0, durMs * 1000), videoName));
        if (compFile != null) {
            //noinspection ResultOfMethodCallIgnored
            compFile.delete();
            compFile = null;
        }
        refreshClips();
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
        soundPrevMs = 0;
        soundName.setText(queryName(uri) + " (" + fmt(d) + ")");
        soundStartBar.setMax((int) Math.max(1, (d - 1000) / 100));
        soundStartBar.setProgress(0);
        soundBox.setVisibility(View.VISIBLE);
        updateModeAvailability();
        updateTexts();
        preparePrevSound();
        applyVolumes();
    }

    private void clearSound() {
        stopSoundTest();
        soundUri = null;
        soundName.setText("Nessuna");
        soundBox.setVisibility(View.GONE);
        releasePrevSound();
        applyVolumes();
    }

    // ------------------------------------------------------------------------ colonna sonora nell'anteprima

    /** Tempo del video dopo i tagli (ms) corrispondente alla posizione videoMs. */
    private long keptBefore(long videoMs) {
        long t = videoMs;
        for (long[] c : cuts) {
            long b = Math.min(c[1], videoMs);
            if (b > c[0]) {
                t -= b - c[0];
            }
        }
        return Math.max(0, t);
    }

    /** Volumi dell'anteprima: come nel file esportato (originale muto, normale o abbassato; colonna sonora piena). */
    private void applyVolumes() {
        float g = 0f;
        if (keepAudio) {
            if (soundUri == null) {
                g = 1f;
            } else if (mix) {
                g = (float) Math.pow(10.0, -dbDown / 20.0);
            }
        }
        try {
            if (videoPlayer != null) {
                videoPlayer.setVolume(g, g);
            }
            if (prevSound != null && prevSoundReady) {
                float sg = soundGain(videoView != null && videoView.isPlaying()
                        ? videoView.getCurrentPosition() : (timeline == null ? 0 : timeline.pos));
                prevSound.setVolume(sg, sg);
            }
        } catch (Exception ignored) {
        }
    }

    /** Volume della colonna sonora nel punto videoMs, con dissolvenza in entrata e in uscita. */
    private float soundGain(long videoMs) {
        if (soundFadeInMs <= 0 && soundFadeOutMs <= 0) {
            return 1f;
        }
        long t = keptBefore(videoMs), tot = keptTotal();
        float g = 1f;
        if (soundFadeInMs > 0 && t < soundFadeInMs) {
            g *= fadeShape(t / (float) soundFadeInMs);
        }
        if (soundFadeOutMs > 0 && t > tot - soundFadeOutMs) {
            g *= fadeShape((tot - t) / (float) soundFadeOutMs);
        }
        return g;
    }

    private float fadeShape(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return soundFadeCurve == 0 ? x : x * x * (3 - 2 * x);
    }

    private void styleCurve() {
        if (curveChips == null) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            Ui.styleChip(this, curveChips[i], soundFadeCurve == i);
        }
    }

    /** Il punto raggiunto dall'ascolto (o dalla barra) diventa l'inizio della colonna sonora. */
    private void setSoundStartHere() {
        if (soundUri == null) {
            return;
        }
        long pos = soundPrevMs;
        if (soundPlayer != null && soundTestReady) {
            try {
                pos = soundPlayer.getCurrentPosition();
            } catch (Exception ignored) {
            }
        }
        soundStartMs = Math.max(0, Math.min(pos, Math.max(0, soundDurMs - 1000)));
        updateTexts();
        soundSeek(timeline == null ? 0 : timeline.pos);
        Toast.makeText(this, "La colonna sonora parte da " + fmt(soundStartMs), Toast.LENGTH_SHORT).show();
    }

    private void preparePrevSound() {
        releasePrevSound();
        if (soundUri == null) {
            return;
        }
        try {
            final MediaPlayer m = new MediaPlayer();
            m.setDataSource(this, soundUri);
            m.setOnErrorListener((mp, what, extra) -> true);
            m.setOnPreparedListener(mp -> {
                if (prevSound != mp) {
                    return;
                }
                prevSoundReady = true;
                applyVolumes();
                if (videoUri != null && videoView.isPlaying()) {
                    soundPlay(videoView.getCurrentPosition());
                }
            });
            prevSound = m;
            m.prepareAsync();
        } catch (Exception e) {
            releasePrevSound();
        }
    }

    private void releasePrevSound() {
        prevSoundReady = false;
        if (prevSound != null) {
            try {
                prevSound.release();
            } catch (Exception ignored) {
            }
            prevSound = null;
        }
    }

    private void soundPlay(long videoMs) {
        stopSoundTest();
        if (prevSound == null || !prevSoundReady) {
            return;
        }
        long t = soundStartMs + keptBefore(videoMs);
        try {
            if (t >= soundDurMs) {
                if (prevSound.isPlaying()) {
                    prevSound.pause();
                }
                return;
            }
            prevSound.seekTo((int) t, MediaPlayer.SEEK_CLOSEST);
            prevSound.start();
        } catch (Exception ignored) {
        }
    }

    private void soundPause() {
        try {
            if (prevSound != null && prevSoundReady && prevSound.isPlaying()) {
                prevSound.pause();
            }
        } catch (Exception ignored) {
        }
    }

    /** Il video e' stato spostato: porta la colonna sonora nel punto giusto. */
    private void soundSeek(long videoMs) {
        if (prevSound == null || !prevSoundReady) {
            return;
        }
        long t = soundStartMs + keptBefore(videoMs);
        try {
            boolean playing = prevSound.isPlaying();
            if (playing && Math.abs(prevSound.getCurrentPosition() - t) < 200) {
                return;
            }
            if (t >= soundDurMs) {
                if (playing) {
                    prevSound.pause();
                }
                return;
            }
            prevSound.seekTo((int) t, MediaPlayer.SEEK_CLOSEST);
            if (videoView != null && videoView.isPlaying() && !playing) {
                prevSound.start();
            }
        } catch (Exception ignored) {
        }
    }

    /** Durante la riproduzione tiene la colonna sonora allineata al video. */
    private void soundDrift(long videoMs) {
        if (prevSound == null || !prevSoundReady) {
            return;
        }
        long t = soundStartMs + keptBefore(videoMs);
        try {
            if (t >= soundDurMs) {
                if (prevSound.isPlaying()) {
                    prevSound.pause();
                }
            } else if (!prevSound.isPlaying()) {
                prevSound.seekTo((int) t, MediaPlayer.SEEK_CLOSEST);
                prevSound.start();
            } else if (Math.abs(prevSound.getCurrentPosition() - t) > 350) {
                prevSound.seekTo((int) t, MediaPlayer.SEEK_CLOSEST);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------ riproduzione

    private void togglePlay() {
        if (videoUri == null) {
            return;
        }
        if (videoView.isPlaying()) {
            videoView.pause();
            soundPause();
            playBtn.setText("▶");
        } else {
            long startMs = timeline.pos;
            if (timeline.pos >= durMs - 100) {
                seekExact(0);
                startMs = 0;
            } else if (!skipping) {
                seekExact(timeline.pos);
            }
            videoView.start();
            soundPlay(startMs);
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
        followSoundTest();
        if (videoUri != null && overlay != null) {
            overlay.setPos(timeline.pos);
        }
        if ((soundFadeInMs > 0 || soundFadeOutMs > 0) && prevSoundReady && videoUri != null
                && videoView.isPlaying()) {
            applyVolumes();
        }
        if (videoUri == null || !videoView.isPlaying() || skipping) {
            return;
        }
        long p = videoView.getCurrentPosition();
        for (long[] c : cuts) {
            if (p >= c[0] && p < c[1]) {
                if (c[1] >= durMs - 50) {
                    videoView.pause();
                    soundPause();
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
        soundDrift(p);
    }

    /** Salto preciso (non al fotogramma chiave precedente) con blocco anti-ripetizione. */
    private void seekExact(long ms) {
        soundSeek(ms);
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

    /** Durante l'ascolto di prova: cursore e tempo seguono la canzone. */
    private void followSoundTest() {
        if (soundPlayer == null || !soundTestReady) {
            return;
        }
        try {
            int pos = soundPlayer.getCurrentPosition();
            soundPrevMs = pos;
            soundBarSync = true;
            soundStartBar.setProgress(pos / 100);
            soundBarSync = false;
            updateTexts();
        } catch (Exception ignored) {
            soundBarSync = false;
        }
    }

    /** Play / pausa dell'ascolto di prova della colonna sonora. */
    private void toggleSoundTest() {
        if (soundUri == null) {
            return;
        }
        if (soundPlayer != null) {
            if (!soundTestReady) {
                return;
            }
            try {
                if (soundPlayer.isPlaying()) {
                    soundPlayer.pause();
                    soundPrevMs = soundPlayer.getCurrentPosition();
                    soundTestBtn.setText("▶  Ascolta");
                } else {
                    soundPlayer.start();
                    soundTestBtn.setText("❚❚  Pausa");
                }
            } catch (Exception ignored) {
            }
            return;
        }
        if (videoUri != null && videoView.isPlaying()) {
            videoView.pause();
            playBtn.setText("▶");
        }
        soundPause();
        try {
            soundPlayer = new MediaPlayer();
            soundPlayer.setDataSource(this, soundUri);
            soundPlayer.setOnPreparedListener(mp -> {
                mp.seekTo((int) soundPrevMs);
                mp.start();
                soundTestReady = true;
            });
            soundPlayer.setOnCompletionListener(mp -> {
                soundPrevMs = soundStartMs;
                stopSoundTest();
            });
            soundPlayer.prepareAsync();
            soundTestBtn.setText("❚❚  Pausa");
        } catch (Exception e) {
            stopSoundTest();
            Toast.makeText(this, "Impossibile riprodurre l'audio.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSoundTest() {
        soundTestReady = false;
        if (soundPlayer != null) {
            try {
                soundPrevMs = soundPlayer.getCurrentPosition();
            } catch (Exception ignored) {
            }
            try {
                soundPlayer.release();
            } catch (Exception ignored) {
            }
            soundPlayer = null;
        }
        if (soundTestBtn != null) {
            soundTestBtn.setText("▶  Ascolta");
        }
        if (soundStartBar != null) {
            soundBarSync = true;
            soundStartBar.setProgress((int) (soundPrevMs / 100));
            soundBarSync = false;
            updateTexts();
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
            soundStartText.setText("Ascolto: " + fmt(soundPrevMs) + " (su " + fmt(soundDurMs)
                    + ")\nLa colonna sonora parte da: " + fmt(soundStartMs));
        }
        if (fadeInText != null && fadeOutText != null) {
            fadeInText.setText(soundFadeInMs <= 0 ? "Dissolvenza in entrata: nessuna"
                    : "Dissolvenza in entrata: " + String.format(Locale.US, "%.1f", soundFadeInMs / 1000.0) + " s");
            fadeOutText.setText(soundFadeOutMs <= 0 ? "Dissolvenza in uscita: nessuna"
                    : "Dissolvenza in uscita: " + String.format(Locale.US, "%.1f", soundFadeOutMs / 1000.0) + " s");
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
        cancelExport = false;
        cancelBtn.setEnabled(true);
        cancelBtn.setVisibility(View.VISIBLE);
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
        // con piu' clip, per il salvataggio il montaggio viene rifatto alla risoluzione piena
        final boolean multi = !(pieces.size() == 1 && pieces.get(0).s.uri.equals(origUri)
                && pieces.get(0).s.startUs == 0 && pieces.get(0).s.endUs == origDurMs * 1000 && pieces.get(0).s.isPlain());
        final List<VideoComposer.Seg> exSegs = new ArrayList<>();
        for (Piece pc : pieces) {
            exSegs.add(pc.s.copy());
        }
        final int fullSide = Math.min(1920, Math.max(origVw, origVh));
        final boolean needFull = multi && fullSide > PROXY_SIDE;
        o.keep = keptSegmentsUs();
        o.fx = new java.util.ArrayList<>(fxItems);
        o.quality = quality;
        o.extraRot = extraRot;
        o.keepAudio = keepAudio;
        o.sound = soundUri;
        o.soundStartUs = soundStartMs * 1000;
        o.soundFadeInUs = soundFadeInMs * 1000;
        o.soundFadeOutUs = soundFadeOutMs * 1000;
        o.soundFadeCurve = soundFadeCurve;
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
                File fullFile = null;
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
                    final int share = needFull ? 35 : 0;
                    if (needFull) {
                        fullFile = new File(getCacheDir(), "clips_full.mp4");
                        int[] wh = VideoComposer.fitSize(origVw, origVh, fullSide);
                        VideoComposer.compose(VideoEditorActivity.this, exSegs, fullFile, getCacheDir(),
                                wh[0], wh[1], Math.max(4000000, wh[0] * wh[1] * 8),
                                new VideoProcessor.Progress() {
                                    @Override
                                    public void onProgress(final String stage, final int pct) {
                                        if (cancelExport) {
                                            throw new IllegalStateException("annullata");
                                        }
                                        runOnUiThread(() -> {
                                            status.setText(stage);
                                            progress.setProgress(pct * share / 100);
                                        });
                                    }
                                });
                        o.video = Uri.fromFile(fullFile);
                    }
                    VideoProcessor.export(VideoEditorActivity.this, o, pfd.getFileDescriptor(),
                            getCacheDir(), new VideoProcessor.Progress() {
                                @Override
                                public void onProgress(final String stage, final int pct) {
                                    if (cancelExport) {
                                        throw new IllegalStateException("annullata");
                                    }
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            status.setText(stage);
                                            progress.setProgress(share + pct * (100 - share) / 100);
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
                    if (cancelExport) {
                        exportDone(null, "Esportazione annullata.");
                        return;
                    }
                    exportDone(null, "Salvataggio non riuscito"
                            + (m != null && !m.isEmpty() ? ": " + m : " (" + t.getClass().getSimpleName() + ")"));
                } finally {
                    if (fullFile != null) {
                        //noinspection ResultOfMethodCallIgnored
                        fullFile.delete();
                    }
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
                cancelBtn.setVisibility(View.GONE);
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
        soundPause();
        stopSoundTest();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        stopSoundTest();
        releasePrevSound();
        videoView.release();
        if (compFile != null) {
            //noinspection ResultOfMethodCallIgnored
            compFile.delete();
        }
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
        int extraRot = 0;
        final java.util.Map<Bitmap, Bitmap> rotCache = new java.util.IdentityHashMap<>();

        void setExtraRot(int deg) {
            extraRot = deg;
            rotCache.clear();
            invalidate();
        }

        private Bitmap rotated(Bitmap b) {
            if (extraRot == 0) {
                return b;
            }
            Bitmap r = rotCache.get(b);
            if (r == null) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(extraRot);
                r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                rotCache.put(b, r);
            }
            return r;
        }

        void setFx(List<VideoFx.Item> l) {
            fxMarks = new ArrayList<>(l);
            invalidate();
        }

        List<Long> joins = new ArrayList<>();
        boolean loading = false;

        void setLoading(boolean on) {
            loading = on;
            invalidate();
        }

        void setJoins(List<Long> j) {
            joins = new ArrayList<>(j);
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
                    b = rotated(b);
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
                if (VideoFx.needsGrab(u) || u[VideoFx.U_VIG] > 0 || u[VideoFx.U_ZOOM] != 1 || u[VideoFx.U_CRT] > 0
                        || u[VideoFx.U_VHS] > 0) {
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
            paint.setColor(0xFFB388FF);
            for (long jm : joins) {
                float jx = x(jm);
                c.drawRect(jx - 2, 0, jx + 2, h, paint);
            }
            paint.setColor(0xFFFFFFFF);
            float px = x(pos);
            c.drawRect(px - 3, 0, px + 3, h, paint);
            if (loading) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xCC0F1115);
                c.drawRect(0, 0, w, h, paint);
                int n3 = (int) (System.currentTimeMillis() / 400 % 3) + 1;
                String dots = n3 == 1 ? "." : n3 == 2 ? ".." : "...";
                paint.setColor(0xFFECEFF4);
                paint.setTextSize(h * 0.26f);
                paint.setTextAlign(Paint.Align.LEFT);
                paint.setFakeBoldText(true);
                float tw = paint.measureText("Caricamento...");
                c.drawText("Caricamento" + dots, (w - tw) / 2f, h / 2f + h * 0.09f, paint);
                paint.setFakeBoldText(false);
                postInvalidateDelayed(400);
            }
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
