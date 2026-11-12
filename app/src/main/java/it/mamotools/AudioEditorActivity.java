package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Editor audio: taglia, copia, incolla, filtri passa-alto/basso/banda, volume ed esportazione. */
public class AudioEditorActivity extends Activity {

    private static final int REQ_OPEN = 61, REQ_ADD = 62;
    private EditText nameEdit;
    private TextView extText;
    private boolean nameEdited = false;
    private String autoName = "";
    private Uri origUri;
    private InfoTable infoTable;

    private final AudioEngine eng = new AudioEngine();
    private final Handler h = new Handler(Looper.getMainLooper());
    private AudioWaveView wave;
    private AudioEngine.Player player;
    private volatile AudioEngine.FxSet fx = new AudioEngine.FxSet(new ArrayList<AudioEngine.Filt>(), 0);
    private final AudioEngine.FxSource fxSource = new AudioEngine.FxSource() {
        @Override
        public AudioEngine.FxSet get() {
            return fx;
        }
    };
    private final List<AudioEngine.Filt> filters = new ArrayList<>();
    private final java.util.ArrayDeque<List<AudioEngine.Filt>> fUndo = new java.util.ArrayDeque<>();
    private FilterTrackView ftrack;
    private LinearLayout filterList;
    private TextView timeCur, timeTot, playHint, selBig, selSub;
    private Button bMarkS, bMarkE, bAll, bNone, bInsert, bAddFilter;
    private long playTo = 0;
    private volatile boolean busy = false;
    private String projectName = "audio";

    // interfaccia
    private Ui.PickCard pick;
    private TextView tvGain;
    private SeekBar scroll, seekGain;
    private Button btnPlay;
    private TextView loopChip;
    private boolean loop = false;
    private final List<View> needsAudio = new ArrayList<>();
    private Button bCut, bCopy, bPaste, bDel, bKeep, bUndo;
    private TextView[] fmtChips;
    private boolean wavOut = false;
    private ResultCard result;
    private LinearLayout loadBox;
    private TextView loadText, loadPct;
    private android.widget.ProgressBar loadBar;
    private ProjStore store;
    private String projectId, projectTitle;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                long p = player.position();
                wave.setCursor(p);
                wave.ensureVisible(p);
                updateTexts();
                h.postDelayed(this, 40);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "Audio Editor", "");
        store = new ProjStore(this, "audio");

        pick = new Ui.PickCard(this, "🎧", "Apri un audio", "MP3, M4A, WAV, OGG… oppure l'audio di un video",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickAudio(REQ_OPEN);
                    }
                });
        root.addView(pick.view);

        // ---- forma d'onda + andamento dei filtri
        LinearLayout wc = Ui.card(this);
        root.addView(wc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        wave = new AudioWaveView(this, eng);
        android.widget.FrameLayout waveBox = new android.widget.FrameLayout(this);
        waveBox.addView(wave, new android.widget.FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        loadBox = new LinearLayout(this);
        loadBox.setOrientation(LinearLayout.VERTICAL);
        loadBox.setGravity(android.view.Gravity.CENTER);
        loadBox.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        loadBox.setBackground(Ui.round(this, 0xF20F1115, 12));
        loadBox.setClickable(true);
        loadBox.setVisibility(View.GONE);
        loadText = Ui.text(this, "", 14, Ui.TEXT, true);
        loadText.setGravity(android.view.Gravity.CENTER);
        loadText.setSingleLine(true);
        loadText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        loadBox.addView(loadText, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        loadBar = Ui.bar(this);
        loadBar.setVisibility(View.VISIBLE);
        loadBox.addView(loadBar, Ui.lp(this, Ui.MATCH, Ui.dp(this, 8), 0, 14));
        loadPct = Ui.text(this, "", 12, Ui.DIM, false);
        loadPct.setGravity(android.view.Gravity.CENTER);
        loadBox.addView(loadPct, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        waveBox.addView(loadBox, new android.widget.FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        wc.addView(waveBox, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 150)));
        ftrack = new FilterTrackView(this, wave, eng);
        wc.addView(ftrack, Ui.lp(this, Ui.MATCH, Ui.dp(this, 64), 0, 6));
        scroll = Ui.seek(this, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    long span = eng.total() - wave.viewLen;
                    wave.setViewStart((long) (span * (p / 1000.0)));
                }
            }
        });
        scroll.setMax(1000);
        scroll.setVisibility(View.GONE);
        wc.addView(scroll, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));

        // tempo che scorre a sinistra, durata totale a destra
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), 0);
        timeCur = Ui.text(this, "0:00.0", 14, Ui.TEXT, true);
        timeTot = Ui.text(this, "0:00.0", 14, Ui.DIM, true);
        timeTot.setGravity(android.view.Gravity.END);
        timeRow.addView(timeCur, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        timeRow.addView(timeTot, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        wc.addView(timeRow, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // trasporto: inizio, -1s, play/pausa, +1s, fine
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(android.view.Gravity.CENTER);
        ctl.setPadding(0, Ui.dp(this, 12), 0, 0);
        Button toStart = roundBtn("⏮", BTN_ON_CARD, Ui.TEXT, 40, 14, v -> {
            stopPlay();
            wave.setCursor(0);
            wave.ensureVisible(0);
            updateAll();
        });
        ctl.addView(toStart);
        Button back1 = roundBtn("−1s", BTN_ON_CARD, Ui.TEXT, 48, 13, v -> seekRel(-1000));
        ctl.addView(back1, Ui.lp(this, Ui.dp(this, 48), Ui.dp(this, 48), 12, 0));
        btnPlay = roundBtn("▶", Ui.ACCENT, 0xFF000000, 64, 22, v -> togglePlay());
        ctl.addView(btnPlay, Ui.lp(this, Ui.dp(this, 64), Ui.dp(this, 64), 12, 0));
        Button fwd1 = roundBtn("+1s", BTN_ON_CARD, Ui.TEXT, 48, 13, v -> seekRel(1000));
        ctl.addView(fwd1, Ui.lp(this, Ui.dp(this, 48), Ui.dp(this, 48), 12, 0));
        Button toEnd = roundBtn("⏭", BTN_ON_CARD, Ui.TEXT, 40, 14, v -> {
            stopPlay();
            wave.setCursor(eng.total());
            wave.ensureVisible(eng.total());
            updateAll();
        });
        ctl.addView(toEnd, Ui.lp(this, Ui.dp(this, 40), Ui.dp(this, 40), 12, 0));
        wc.addView(ctl, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        needsAudio.add(toStart);
        needsAudio.add(back1);
        needsAudio.add(btnPlay);
        needsAudio.add(fwd1);
        needsAudio.add(toEnd);
        playHint = Ui.text(this, "", 11, Ui.DIM, false);
        playHint.setGravity(android.view.Gravity.CENTER);
        playHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        wc.addView(playHint, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // loop e zoom
        LinearLayout zr = new LinearLayout(this);
        loopChip = Ui.chip(this, "🔁  Loop");
        loopChip.setTextSize(13);
        Ui.styleChip(this, loopChip, false);
        loopChip.setOnClickListener(v -> {
            loop = !loop;
            Ui.styleChip(AudioEditorActivity.this, loopChip, loop);
            if (player != null) {
                player.setLoop(loop);
            }
            updateTexts();
        });
        zr.addView(loopChip, Ui.lp(this, 0, Ui.dp(this, 40), 0, 0, 1.5f));
        zr.addView(small("−", v -> wave.zoom(0.5f), true), Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 0.8f));
        zr.addView(small("＋", v -> wave.zoom(2f), true), Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 0.8f));
        zr.addView(small("Tutto", v -> wave.zoomAll(), true), Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 1.1f));
        wc.addView(zr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        TextView tip = Ui.text(this, "Tocca la forma d'onda (o il grafico dei filtri) per spostare il cursore; "
                + "pizzica o usa − ＋ per lo zoom: i due grafici si muovono insieme. Il tasto play riproduce la "
                + "selezione se il cursore e' al suo interno, altrimenti dal cursore alla fine. "
                + "Il grafico arancione mostra quanta parte dello spettro e' tagliata dai filtri in ogni punto.",
                11, Ui.DIM, false);
        tip.setPadding(0, Ui.dp(this, 8), 0, 0);
        wc.addView(tip);

        wave.setListener(new AudioWaveView.Listener() {
            @Override
            public void onCursor(long frame, boolean fromUser) {
                if (fromUser && player != null) {
                    stopPlay();
                }
                updateTexts();
            }

            @Override
            public void onViewport() {
                updateScroll();
            }
        });

        // ---- modifica
        LinearLayout ec = Ui.card(this);
        root.addView(ec, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        ec.addView(Ui.section(this, "Modifica"));

        LinearLayout selBox = innerBox(ec, "1 · Scegli un tratto");
        selBig = Ui.text(this, "", 20, Ui.ACCENT, true);
        selBig.setGravity(android.view.Gravity.CENTER);
        selBox.addView(selBig, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        selSub = Ui.text(this, "", 12, Ui.DIM, false);
        selSub.setGravity(android.view.Gravity.CENTER);
        selSub.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        selBox.addView(selSub, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        LinearLayout m1 = new LinearLayout(this);
        bMarkS = tool("[  Inizio qui", v -> markStart());
        bMarkE = tool("Fine qui  ]", v -> markEnd());
        m1.addView(bMarkS, Ui.lp(this, 0, Ui.dp(this, 46), 0, 0, 1f));
        m1.addView(bMarkE, Ui.lp(this, 0, Ui.dp(this, 46), 8, 0, 1f));
        selBox.addView(m1);
        LinearLayout m1b = new LinearLayout(this);
        bAll = tool("Tutto l'audio", v -> {
            if (canEdit()) {
                wave.setSelection(0, eng.total());
                updateAll();
            }
        });
        bNone = tool("Deseleziona", v -> {
            wave.setSelection(-1, -1);
            updateAll();
        });
        m1b.addView(bAll, Ui.lp(this, 0, Ui.dp(this, 40), 0, 0, 1f));
        m1b.addView(bNone, Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 1f));
        selBox.addView(m1b, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));

        LinearLayout actBox = innerBox(ec, "2 · Cosa farne");
        LinearLayout m2 = new LinearLayout(this);
        bCut = tile("✂", "Taglia", v -> opCut());
        bCopy = tile("📄", "Copia", v -> opCopy());
        bDel = tile("🗑", "Elimina", v -> opDelete());
        bKeep = tile("✔", "Mantieni", v -> opKeep());
        m2.addView(bCut, Ui.lp(this, 0, Ui.dp(this, 62), 0, 0, 1f));
        m2.addView(bCopy, Ui.lp(this, 0, Ui.dp(this, 62), 6, 0, 1f));
        m2.addView(bDel, Ui.lp(this, 0, Ui.dp(this, 62), 6, 0, 1f));
        m2.addView(bKeep, Ui.lp(this, 0, Ui.dp(this, 62), 6, 0, 1f));
        actBox.addView(m2);
        TextView aNote = Ui.text(this, "Taglia e Copia mettono il tratto negli appunti. Mantieni scarta tutto "
                + "il resto.", 11, Ui.DIM, false);
        aNote.setPadding(0, Ui.dp(this, 8), 0, 0);
        actBox.addView(aNote);

        LinearLayout insBox = innerBox(ec, "3 · Aggiungi al cursore");
        LinearLayout m3 = new LinearLayout(this);
        bPaste = tool("📋  Incolla", v -> opPaste());
        bInsert = tool("＋  Altro audio", v -> pickAudio(REQ_ADD));
        m3.addView(bPaste, Ui.lp(this, 0, Ui.dp(this, 46), 0, 0, 1f));
        m3.addView(bInsert, Ui.lp(this, 0, Ui.dp(this, 46), 8, 0, 1f));
        insBox.addView(m3);

        bUndo = Ui.btn(this, "↶  Annulla l'ultima modifica", Ui.CARD2, Ui.TEXT, v -> opUndo());
        bUndo.setTextSize(13);
        ec.addView(bUndo, Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 4));

        // ---- filtri
        LinearLayout fc = Ui.card(this);
        root.addView(fc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        fc.addView(Ui.section(this, "Filtri"));
        bAddFilter = Ui.btn(this, "＋  Aggiungi un filtro", Ui.CARD2, Ui.ACCENT, v -> filterDialog(-1));
        fc.addView(bAddFilter, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, Ui.H_BTN)));
        needsAudio.add(bAddFilter);
        filterList = new LinearLayout(this);
        filterList.setOrientation(LinearLayout.VERTICAL);
        fc.addView(filterList, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        TextView ftip = Ui.text(this, "Ogni filtro agisce solo tra il suo inizio e la sua fine. Se due filtri "
                + "si sovrappongono, nel tratto in comune agiscono entrambi, uno dopo l'altro. Li senti subito "
                + "in ascolto e vengono applicati all'esportazione. I tempi seguono i tagli e gli inserimenti "
                + "che fai sulla traccia.", 11, Ui.DIM, false);
        ftip.setPadding(0, Ui.dp(this, 10), 0, 0);
        fc.addView(ftip);

        // ---- volume
        LinearLayout vc = Ui.card(this);
        root.addView(vc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        vc.addView(Ui.section(this, "Volume"));
        tvGain = Ui.text(this, "", 14, Ui.TEXT, true);
        vc.addView(tvGain);
        seekGain = Ui.seek(this, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                updateFilterTexts();
                rebuildFx();
            }
        });
        seekGain.setMax(400);
        seekGain.setProgress(200);
        vc.addView(seekGain, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        vc.addView(small("Riporta a 0 dB", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekGain.setProgress(200);
            }
        }, false), Ui.lp(this, Ui.MATCH, Ui.dp(this, 40), 0, 8));

        // ---- esporta
        LinearLayout xc = Ui.card(this);
        root.addView(xc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        LinearLayout xh = new LinearLayout(this);
        xh.setGravity(android.view.Gravity.CENTER_VERTICAL);
        xh.addView(Ui.section(this, "Esporta"), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        Button infoBtn = Ui.btn(this, "i", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.infoDialog(AudioEditorActivity.this, "Come funziona l'esportazione",
                        "M4A (AAC) e' leggero e adatto a tutti i lettori; WAV e' senza perdita ma i file sono "
                        + "molto piu' grandi.\n\nL'audio viene esportato in stereo a 44,1 kHz con tagli, "
                        + "incolla, filtri e volume gia' applicati.\n\nIl file va in Musica/MamoTools. "
                        + "Se non scrivi un nome, viene usato MamoAudio con data e ora.");
            }
        });
        infoBtn.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF,
                android.graphics.Typeface.BOLD_ITALIC));
        infoBtn.setTextSize(16);
        xh.addView(infoBtn, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        xc.addView(xh);
        LinearLayout x1 = new LinearLayout(this);
        fmtChips = new TextView[2];
        String[] fn = {"M4A (AAC, leggero)", "WAV (senza perdita)"};
        for (int i = 0; i < 2; i++) {
            final boolean w = i == 1;
            fmtChips[i] = Ui.chip(this, fn[i]);
            fmtChips[i].setTextSize(12);
            fmtChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    wavOut = w;
                    if (extText != null) {
                        extText.setText(w ? ".wav" : ".m4a");
                    }
                    updateFilterUi();
                }
            });
            x1.addView(fmtChips[i], Ui.lp(this, 0, Ui.dp(this, 42), i == 0 ? 0 : 8, 0, 1f));
        }
        xc.addView(x1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        TextView nl = Ui.text(this, "Nome del file", 13, Ui.TEXT, true);
        nl.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        xc.addView(nl);
        nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        nameEdit.setTextColor(Ui.TEXT);
        nameEdit.setTextSize(14);
        nameEdit.setBackground(Ui.round(this, Ui.CARD2, 12));
        nameEdit.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        nameEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameEdit.setText(defaultName());
        nameEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int s1, int s2, int s3) {
            }

            @Override
            public void onTextChanged(CharSequence c, int s1, int s2, int s3) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                nameEdited = !e.toString().equals(autoName);
            }
        });
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        nameRow.addView(nameEdit, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        extText = Ui.text(this, ".m4a", 14, Ui.DIM, false);
        nameRow.addView(extText, Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        xc.addView(nameRow);
        Button export = Ui.btn(this, "Esporta audio", Ui.ACCENT, 0xFF000000,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        export();
                    }
                });
        export.setTextSize(16);
        LinearLayout exRow = new LinearLayout(this);
        exRow.setBaselineAligned(false);
        exRow.addView(export, Ui.lp(this, 0, Ui.dp(this, 52), 0, 0, 1f));
        Button saveBtn = Ui.btn(this, "Salva progetto", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveProject();
            }
        });
        saveBtn.setTextSize(16);
        exRow.addView(saveBtn, Ui.lp(this, 0, Ui.dp(this, 52), 8, 0, 1f));
        xc.addView(exRow, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 16));
        needsAudio.add(export);
        needsAudio.add(saveBtn);

        result = new ResultCard(this, true);
        xc.addView(result.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));

        infoTable = new InfoTable(this, AudioInfo.ROWS);
        infoTable.setVisibility(View.GONE);
        xc.addView(infoTable, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));

        updateFilterUi();
        refreshFilters();
        updateAll();

        // aperto da "Apri con" dal telefono
        Intent in = getIntent();
        if (in != null && in.getStringExtra("project") != null) {
            loadProject(in.getStringExtra("project"));
        } else if (in != null && in.getData() != null && Intent.ACTION_VIEW.equals(in.getAction())) {
            startLoad(in.getData(), false);
        }
    }

    private String defaultName() {
        autoName = "MamoAudio_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new java.util.Date());
        return autoName;
    }

    private void resetName() {
        nameEdited = false;
        nameEdit.setText(defaultName());
    }

    private String chosenName() {
        String n = nameEdit.getText().toString().trim();
        if (!nameEdited || n.isEmpty()) {
            return "MamoAudio_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new java.util.Date());
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        String l = n.toLowerCase(Locale.US);
        if (l.endsWith(".m4a") || l.endsWith(".wav")) {
            n = n.substring(0, n.length() - 4);
        }
        n = n.trim();
        return n.isEmpty() ? "MamoAudio" : n;
    }

    private Button small(String label, View.OnClickListener l, boolean always) {
        Button b = Ui.btn(this, label, Ui.CARD2, Ui.TEXT, l);
        b.setTextSize(13);
        if (!always) {
            needsAudio.add(b);
        }
        return b;
    }

    // ------------------------------------------------------------------ filtri / volume

    private static final int BTN_ON_CARD = 0xFF3A4252;
    private static final long UNIT = AudioEngine.RATE / 10; // 0,1 s

    private static float freqOf(int p) {
        return (float) (20.0 * Math.pow(1000.0, p / 1000.0));
    }

    private static int progOf(float f) {
        return (int) Math.round(1000.0 * Math.log(f / 20.0) / Math.log(1000.0));
    }

    private static String freqText(float f) {
        return f < 1000 ? String.format(Locale.US, "%d Hz", Math.round(f))
                : String.format(Locale.US, "%.1f kHz", f / 1000f);
    }

    private float gainDb() {
        return (seekGain.getProgress() - 200) / 10f;
    }

    private static String filtName(AudioEngine.Filt f) {
        if (f.mode == AudioEngine.Fx.BP) {
            return "Passa-banda " + freqText(f.lo) + " – " + freqText(f.hi);
        }
        return (f.mode == AudioEngine.Fx.HP ? "Passa-alto " + freqText(f.lo) : "Passa-basso " + freqText(f.hi));
    }

    private List<AudioEngine.Filt> copyFilters() {
        List<AudioEngine.Filt> l = new ArrayList<>();
        for (AudioEngine.Filt f : filters) {
            l.add(f.copy());
        }
        return l;
    }

    /** Come eng.pushUndo(), ma ricorda anche i filtri. */
    private void pushU() {
        eng.pushUndo();
        fUndo.push(copyFilters());
        while (fUndo.size() > 30) {
            fUndo.removeLast();
        }
    }

    private void rebuildFx() {
        if (seekGain == null) {
            return;
        }
        fx = new AudioEngine.FxSet(filters, gainDb());
        if (ftrack != null) {
            ftrack.setFilters(filters);
        }
    }

    private void updateFilterTexts() {
        if (seekGain == null || tvGain == null) {
            return;
        }
        float g = gainDb();
        tvGain.setText(String.format(Locale.US, "%+.1f dB  (×%.2f)", g, Math.pow(10, g / 20.0)));
    }

    private void updateFilterUi() {
        for (int i = 0; i < 2; i++) {
            Ui.styleChip(this, fmtChips[i], wavOut == (i == 1));
        }
        updateFilterTexts();
    }

    private void sortFilters() {
        java.util.Collections.sort(filters, (x, y) -> x.a != y.a ? Long.compare(x.a, y.a) : Long.compare(x.b, y.b));
    }

    /** Aggiorna l'elenco dei filtri, il grafico e il suono. */
    private void refreshFilters() {
        sortFilters();
        filterList.removeAllViews();
        if (filters.isEmpty()) {
            TextView t = Ui.text(this, "Nessun filtro. Aggiungine uno e scegli da dove a dove agisce.", 13,
                    Ui.DIM, false);
            filterList.addView(t, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        for (int i = 0; i < filters.size(); i++) {
            final int idx = i;
            final AudioEngine.Filt f = filters.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 12));
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(Ui.text(this, (i + 1) + " · " + filtName(f), 14, Ui.TEXT, true));
            tx.addView(Ui.text(this, AudioWaveView.fmt(f.a) + " → " + AudioWaveView.fmt(f.b) + "  ("
                    + String.format(Locale.US, "%.1f s", (f.b - f.a) / (double) AudioEngine.RATE) + ")"
                    + (f.mode == AudioEngine.Fx.NONE ? "" : f.order >= 2 ? " · ripido" : " · morbido"),
                    12, Ui.ACCENT, false));
            row.addView(tx, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            Button del = Ui.btn(this, "✕", BTN_ON_CARD, Ui.DANGER, v -> {
                if (busy) {
                    return;
                }
                pushU();
                filters.remove(f);
                refreshFilters();
                updateAll();
            });
            del.setPadding(0, 0, 0, 0);
            row.addView(del, Ui.lp(this, Ui.dp(this, Ui.ICON_W), Ui.dp(this, Ui.ICON_H), 6, 0));
            row.setOnClickListener(v -> filterDialog(idx));
            filterList.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, i == 0 ? 0 : 8));
        }
        rebuildFx();
    }

    private static long mapCut(long p, long a, long b) {
        return p <= a ? p : p >= b ? p - (b - a) : a;
    }

    private void dropEmptyFilters() {
        for (int i = filters.size() - 1; i >= 0; i--) {
            if (filters.get(i).b - filters.get(i).a < AudioEngine.RATE / 20) {
                filters.remove(i);
            }
        }
    }

    /** I tempi dei filtri seguono la traccia quando si toglie il tratto [a, b). */
    private void filtersCut(long a, long b) {
        for (AudioEngine.Filt f : filters) {
            f.a = mapCut(f.a, a, b);
            f.b = mapCut(f.b, a, b);
        }
        dropEmptyFilters();
        refreshFilters();
    }

    /** ... quando si inseriscono len fotogrammi in at. */
    private void filtersInsert(long at, long len) {
        for (AudioEngine.Filt f : filters) {
            if (f.a >= at) {
                f.a += len;
            }
            if (f.b > at) {
                f.b += len;
            }
        }
        refreshFilters();
    }

    /** ... quando si tiene solo [a, b). */
    private void filtersKeep(long a, long b) {
        for (AudioEngine.Filt f : filters) {
            f.a = Math.max(a, Math.min(b, f.a)) - a;
            f.b = Math.max(a, Math.min(b, f.b)) - a;
        }
        dropEmptyFilters();
        refreshFilters();
    }

    private LinearLayout innerBox(LinearLayout parent, String title) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.round(this, Ui.CARD2, 14));
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 14));
        TextView h = Ui.text(this, title, 13, Ui.DIM, true);
        h.setPadding(0, 0, 0, Ui.dp(this, 8));
        b.addView(h);
        parent.addView(b, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        return b;
    }

    /** Tasto dentro una scheda scura: piu' chiaro dello sfondo, cosi' si riconosce come tasto. */
    private Button tool(String label, View.OnClickListener l) {
        Button b = Ui.btn(this, label, BTN_ON_CARD, Ui.TEXT, l);
        b.setTextSize(13);
        needsAudio.add(b);
        return b;
    }

    /** Tasto quadrato con icona sopra e nome sotto. */
    private Button tile(String icon, String label, View.OnClickListener l) {
        Button b = Ui.btn(this, icon + "\n" + label, BTN_ON_CARD, Ui.TEXT, l);
        b.setTextSize(12);
        b.setPadding(0, 0, 0, 0);
        b.setLineSpacing(0, 0.95f);
        needsAudio.add(b);
        return b;
    }

    private Button roundBtn(String label, int bg, int fg, int size, int sp, View.OnClickListener l) {
        Button b = new Ui.Btn(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(sp);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(bg);
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44FFFFFF), g, mask));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, size), Ui.dp(this, size)));
        return b;
    }

    // ------------------------------------------------------------------ finestra del filtro

    private void filterDialog(final int idx) {
        if (busy || eng.total() <= 0) {
            return;
        }
        stopPlay();
        final long total = eng.total();
        final AudioEngine.Filt f;
        if (idx >= 0) {
            f = filters.get(idx).copy();
        } else {
            f = new AudioEngine.Filt();
            if (selValid()) {
                f.a = wave.selA;
                f.b = wave.selB;
            } else {
                f.a = Math.max(0, Math.min(wave.cursor, total - UNIT));
                f.b = total;
            }
        }
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD, 20));
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 16));
        box.addView(Ui.text(this, idx >= 0 ? "Modifica filtro" : "Nuovo filtro", 18, Ui.TEXT, true));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, new android.view.ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP));
        box.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        final Runnable[] upd = new Runnable[1];
        // tipo
        TextView tl = Ui.text(this, "Tipo di filtro", 13, Ui.DIM, false);
        tl.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        body.addView(tl);
        LinearLayout tr = new LinearLayout(this);
        final TextView[] mc = new TextView[3];
        String[] names = {"Passa-alto", "Passa-basso", "Passa-banda"};
        for (int i = 0; i < 3; i++) {
            final int m = i + 1;
            mc[i] = Ui.chip(this, names[i]);
            mc[i].setTextSize(12);
            mc[i].setOnClickListener(v -> {
                f.mode = m;
                upd[0].run();
            });
            tr.addView(mc[i], Ui.lp(this, 0, Ui.dp(this, 42), i == 0 ? 0 : 6, 0, 1f));
        }
        body.addView(tr);
        final FilterGraphView g = new FilterGraphView(this);
        body.addView(g, Ui.lp(this, Ui.MATCH, Ui.dp(this, 150), 0, 12));
        TextView logNote = Ui.text(this, "Scala logaritmica", 10, Ui.ACCENT, false);
        logNote.setGravity(android.view.Gravity.CENTER);
        logNote.setPadding(0, Ui.dp(this, 4), 0, 0);
        body.addView(logNote, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        // frequenze
        final LinearLayout rowLo = new LinearLayout(this), rowHi = new LinearLayout(this);
        rowLo.setOrientation(LinearLayout.VERTICAL);
        rowHi.setOrientation(LinearLayout.VERTICAL);
        final TextView tvLo = Ui.text(this, "", 13, Ui.TEXT, false), tvHi = Ui.text(this, "", 13, Ui.TEXT, false);
        rowLo.addView(tvLo);
        rowHi.addView(tvHi);
        final SeekBar sLo = Ui.seek(this, null), sHi = Ui.seek(this, null);
        sLo.setMax(1000);
        sHi.setMax(1000);
        sLo.setProgress(progOf(f.lo));
        sHi.setProgress(progOf(f.hi));
        sLo.setOnSeekBarChangeListener(new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    f.lo = freqOf(p);
                    upd[0].run();
                }
            }
        });
        sHi.setOnSeekBarChangeListener(new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    f.hi = freqOf(p);
                    upd[0].run();
                }
            }
        });
        rowLo.addView(sLo);
        rowHi.addView(sHi);
        body.addView(rowLo, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        body.addView(rowHi, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        // pendenza
        LinearLayout slr = new LinearLayout(this);
        final TextView[] sc = new TextView[2];
        String[] sl = {"Morbido (12 dB/ott.)", "Ripido (24 dB/ott.)"};
        for (int i = 0; i < 2; i++) {
            final int o = i + 1;
            sc[i] = Ui.chip(this, sl[i]);
            sc[i].setTextSize(12);
            sc[i].setOnClickListener(v -> {
                f.order = o;
                upd[0].run();
            });
            slr.addView(sc[i], Ui.lp(this, 0, Ui.dp(this, 40), i == 0 ? 0 : 8, 0, 1f));
        }
        body.addView(slr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        // tempi
        LinearLayout when = innerBox(body, "Dove agisce");
        final TextView tvA = Ui.text(this, "", 14, Ui.TEXT, true), tvB = Ui.text(this, "", 14, Ui.TEXT, true);
        final int maxP = (int) Math.max(1, (total + UNIT - 1) / UNIT);
        final SeekBar sA = Ui.seek(this, null), sB = Ui.seek(this, null);
        sA.setMax(maxP);
        sB.setMax(maxP);
        sA.setProgress((int) (f.a / UNIT));
        sB.setProgress((int) Math.min(maxP, (f.b + UNIT - 1) / UNIT));
        sA.setOnSeekBarChangeListener(new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                f.a = Math.max(0, Math.min(p * UNIT, f.b - UNIT));
                if (f.a != p * UNIT) {
                    s.setProgress((int) (f.a / UNIT));
                }
                upd[0].run();
            }
        });
        sB.setOnSeekBarChangeListener(new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                f.b = p >= maxP ? total : Math.min(total, Math.max(p * UNIT, f.a + UNIT));
                s.setProgress((int) Math.min(maxP, (f.b + UNIT - 1) / UNIT));
                upd[0].run();
            }
        });
        when.addView(tvA);
        when.addView(sA);
        LinearLayout ab = new LinearLayout(this);
        ab.addView(Ui.btn(this, "Inizio = cursore", BTN_ON_CARD, Ui.TEXT, v -> {
            f.a = Math.max(0, Math.min(wave.cursor, f.b - UNIT));
            sA.setProgress((int) (f.a / UNIT));
            upd[0].run();
        }), Ui.lp(this, 0, Ui.dp(this, 40), 0, 0, 1f));
        ab.addView(Ui.btn(this, "Fine = cursore", BTN_ON_CARD, Ui.TEXT, v -> {
            f.b = Math.min(total, Math.max(wave.cursor, f.a + UNIT));
            sB.setProgress((int) Math.min(maxP, (f.b + UNIT - 1) / UNIT));
            upd[0].run();
        }), Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 1f));
        for (int i = 0; i < ab.getChildCount(); i++) {
            ((TextView) ab.getChildAt(i)).setTextSize(12);
        }
        tvB.setPadding(0, Ui.dp(this, 10), 0, 0);
        when.addView(tvB);
        when.addView(sB);
        when.addView(ab, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        LinearLayout ab2 = new LinearLayout(this);
        Button bWhole = Ui.btn(this, "Tutta la traccia", BTN_ON_CARD, Ui.TEXT, v -> {
            f.a = 0;
            f.b = total;
            sA.setProgress(0);
            sB.setProgress(maxP);
            upd[0].run();
        });
        bWhole.setTextSize(12);
        Button bSel = Ui.btn(this, "Come la selezione", BTN_ON_CARD, Ui.TEXT, v -> {
            if (!selValid()) {
                Toast.makeText(this, "Nessuna selezione.", Toast.LENGTH_SHORT).show();
                return;
            }
            f.a = wave.selA;
            f.b = Math.min(total, wave.selB);
            sA.setProgress((int) (f.a / UNIT));
            sB.setProgress((int) Math.min(maxP, (f.b + UNIT - 1) / UNIT));
            upd[0].run();
        });
        bSel.setTextSize(12);
        ab2.addView(bWhole, Ui.lp(this, 0, Ui.dp(this, 40), 0, 0, 1f));
        ab2.addView(bSel, Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 1f));
        when.addView(ab2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));

        upd[0] = () -> {
            for (int i = 0; i < 3; i++) {
                Ui.styleChip(this, mc[i], f.mode == i + 1);
            }
            for (int i = 0; i < 2; i++) {
                Ui.styleChip(this, sc[i], f.order == i + 1);
            }
            rowLo.setVisibility(f.mode == AudioEngine.Fx.HP || f.mode == AudioEngine.Fx.BP ? View.VISIBLE : View.GONE);
            rowHi.setVisibility(f.mode == AudioEngine.Fx.LP || f.mode == AudioEngine.Fx.BP ? View.VISIBLE : View.GONE);
            if (f.mode == AudioEngine.Fx.BP) {
                tvLo.setText("Taglia sotto i  " + freqText(f.lo));
                tvHi.setText("Taglia sopra i  " + freqText(f.hi));
            } else if (f.mode == AudioEngine.Fx.HP) {
                tvLo.setText("Frequenza di taglio:  " + freqText(f.lo) + "  (passano le frequenze piu' alte)");
            } else {
                tvHi.setText("Frequenza di taglio:  " + freqText(f.hi) + "  (passano le frequenze piu' basse)");
            }
            tvA.setText("Inizia a: " + AudioWaveView.fmt(f.a));
            tvB.setText("Finisce a: " + AudioWaveView.fmt(f.b) + "   (dura "
                    + String.format(Locale.US, "%.1f s", (f.b - f.a) / (double) AudioEngine.RATE) + ")");
            g.setFx(f.fx());
        };
        upd[0].run();

        LinearLayout br = new LinearLayout(this);
        if (idx >= 0) {
            br.addView(Ui.btn(this, "Elimina", Ui.CARD2, Ui.DANGER, v -> {
                d.dismiss();
                pushU();
                filters.remove(idx);
                refreshFilters();
                updateAll();
            }), Ui.lp(this, 0, Ui.dp(this, 46), 0, 0, 1f));
        }
        br.addView(Ui.btn(this, "Annulla", Ui.CARD2, Ui.TEXT, v -> d.dismiss()),
                Ui.lp(this, 0, Ui.dp(this, 46), idx >= 0 ? 6 : 0, 0, 1f));
        br.addView(Ui.btn(this, "Applica", Ui.ACCENT, 0xFF000000, v -> {
            d.dismiss();
            pushU();
            if (idx >= 0) {
                filters.set(idx, f);
            } else {
                filters.add(f);
            }
            refreshFilters();
            updateAll();
        }), Ui.lp(this, 0, Ui.dp(this, 46), 6, 0, 1f));
        box.addView(br, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
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

    // ------------------------------------------------------------------ progetti

    private void saveProject() {
        if (busy || eng.total() <= 0) {
            return;
        }
        if (projectId != null) {
            doSaveProject(projectTitle);
            return;
        }
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setTextColor(Ui.TEXT);
        et.setTextSize(15);
        et.setBackground(Ui.round(this, Ui.CARD2, 12));
        et.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        et.setText(projectName == null || projectName.isEmpty() ? "Progetto audio" : projectName);
        et.selectAll();
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD, 20));
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 16));
        box.addView(Ui.text(this, "Salva il progetto", 18, Ui.TEXT, true));
        TextView hint = Ui.text(this, "Nome del progetto", 13, Ui.DIM, false);
        hint.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        box.addView(hint);
        box.addView(et, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        LinearLayout br = new LinearLayout(this);
        br.addView(Ui.btn(this, "Annulla", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        }), Ui.lp(this, 0, Ui.dp(this, 46), 0, 0, 1f));
        br.addView(Ui.btn(this, "Salva", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = et.getText().toString().trim();
                d.dismiss();
                doSaveProject(t.isEmpty() ? "Progetto audio" : t);
            }
        }), Ui.lp(this, 0, Ui.dp(this, 46), 8, 0, 1f));
        box.addView(br, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 16));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), Ui.WRAP);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
    }

    private void doSaveProject(final String title) {
        stopPlay();
        final List<AudioEngine.Filt> fcopy = copyFilters();
        busy = true;
        updateAll();
        result.start("Salvataggio del progetto...");
        final String id = projectId != null ? projectId : ProjStore.newId();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("title", title);
                    org.json.JSONArray sa = new org.json.JSONArray();
                    for (int i = 0; i < eng.srcs.size(); i++) {
                        AudioEngine.Src sr = eng.srcs.get(i);
                        String fn = "s" + i + ".pcm";
                        if (!fn.equals(sr.projFile) || !store.asset(id, fn).exists()) {
                            ProjStore.copy(sr.pcm, store.asset(id, fn));
                            sr.projFile = fn;
                        }
                        sa.put(new org.json.JSONObject().put("file", fn).put("frames", sr.frames));
                    }
                    o.put("srcs", sa);
                    org.json.JSONArray ta = new org.json.JSONArray();
                    for (AudioEngine.Clip c : eng.tl) {
                        ta.put(new org.json.JSONArray().put(c.src).put(c.a).put(c.b));
                    }
                    o.put("tl", ta);
                    org.json.JSONArray fja = new org.json.JSONArray();
                    for (AudioEngine.Filt ft : fcopy) {
                        fja.put(new org.json.JSONObject().put("mode", ft.mode).put("lo", (double) ft.lo)
                                .put("hi", (double) ft.hi).put("order", ft.order).put("a", ft.a).put("b", ft.b));
                    }
                    o.put("filters", fja);
                    o.put("gain", seekGain.getProgress());
                    o.put("wavOut", wavOut);
                    o.put("loop", loop);
                    o.put("cursor", wave.cursor);
                    o.put("projectName", projectName);
                    if (origUri != null) {
                        o.put("origUri", origUri.toString());
                    }
                    o.put("nameEdited", nameEdited);
                    if (nameEdited) {
                        o.put("outName", nameEdit.getText().toString());
                    }
                    o.put("summary", fmtTime(eng.total() * 1000L / AudioEngine.RATE) + " · "
                            + eng.tl.size() + (eng.tl.size() == 1 ? " tratto" : " tratti")
                            + (fcopy.isEmpty() ? "" : " · " + fcopy.size() + (fcopy.size() == 1 ? " filtro" : " filtri")));
                    store.write(id, o);
                    projectId = id;
                    projectTitle = title;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            result.hide();
                            Toast.makeText(AudioEditorActivity.this, "Progetto salvato: " + title,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Throwable t) {
                    result.error("Salvataggio del progetto non riuscito"
                            + (t.getMessage() != null ? ": " + t.getMessage() : "."));
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            updateAll();
                        }
                    });
                }
            }
        }).start();
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void loadProject(final String id) {
        final org.json.JSONObject o;
        try {
            o = store.read(id);
        } catch (Exception e) {
            Ui.infoDialog(this, "Progetto non leggibile", "Non riesco ad aprire questo progetto.");
            return;
        }
        projectId = id;
        projectTitle = o.optString("title", "Progetto audio");
        eng.clearAll();
        filters.clear();
        fUndo.clear();
        wave.setSelection(-1, -1);
        wave.setCursor(0);
        wave.refreshModel();
        busy = true;
        updateAll();
        result.hide();
        loadText.setText("Apertura di " + projectTitle);
        loadPct.setText("Caricamento in corso...");
        loadBar.setIndeterminate(true);
        loadBox.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONArray sa = o.getJSONArray("srcs");
                    for (int i = 0; i < sa.length(); i++) {
                        org.json.JSONObject so = sa.getJSONObject(i);
                        String fn = so.getString("file");
                        File from = store.asset(id, fn);
                        if (!from.exists()) {
                            throw new IOException("mancano i dati audio del progetto");
                        }
                        File to = new File(getCacheDir(), "aud_" + i + "_" + System.nanoTime() + ".pcm");
                        ProjStore.copy(from, to);
                        AudioEngine.Src sr = eng.adopt(to, so.getLong("frames"));
                        sr.projFile = fn;
                    }
                    final List<AudioEngine.Clip> tl = new ArrayList<>();
                    org.json.JSONArray ta = o.getJSONArray("tl");
                    for (int i = 0; i < ta.length(); i++) {
                        org.json.JSONArray c = ta.getJSONArray(i);
                        tl.add(new AudioEngine.Clip(c.getInt(0), c.getLong(1), c.getLong(2)));
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            applyProject(o, tl);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            loadBox.setVisibility(View.GONE);
                            result.error("Non riesco ad aprire il progetto"
                                    + (t.getMessage() != null ? ": " + t.getMessage() : "."));
                            updateAll();
                        }
                    });
                }
            }
        }).start();
    }

    private void applyProject(org.json.JSONObject o, List<AudioEngine.Clip> tl) {
        busy = false;
        loadBox.setVisibility(View.GONE);
        eng.tl = tl;
        filters.clear();
        fUndo.clear();
        org.json.JSONArray fja = o.optJSONArray("filters");
        if (fja != null) {
            for (int i = 0; i < fja.length(); i++) {
                org.json.JSONObject fo = fja.optJSONObject(i);
                if (fo == null) {
                    continue;
                }
                AudioEngine.Filt ft = new AudioEngine.Filt();
                ft.mode = Math.max(1, Math.min(3, fo.optInt("mode", 1)));
                ft.lo = (float) fo.optDouble("lo", 200);
                ft.hi = (float) fo.optDouble("hi", 4000);
                ft.order = fo.optInt("order", 1) >= 2 ? 2 : 1;
                ft.a = Math.max(0, fo.optLong("a", 0));
                ft.b = Math.min(eng.total(), fo.optLong("b", eng.total()));
                if (ft.b - ft.a >= AudioEngine.RATE / 20) {
                    filters.add(ft);
                }
            }
        } else if (o.optInt("mode", 0) != AudioEngine.Fx.NONE) {
            // progetto salvato con il vecchio filtro unico: vale per tutta la traccia
            AudioEngine.Filt ft = new AudioEngine.Filt();
            ft.mode = o.optInt("mode", 1);
            ft.order = o.optInt("order", 1);
            ft.lo = freqOf(o.optInt("lo", 333));
            ft.hi = freqOf(o.optInt("hi", 767));
            ft.a = 0;
            ft.b = eng.total();
            filters.add(ft);
        }
        seekGain.setProgress(o.optInt("gain", 200));
        wavOut = o.optBoolean("wavOut", false);
        if (extText != null) {
            extText.setText(wavOut ? ".wav" : ".m4a");
        }
        loop = o.optBoolean("loop", false);
        Ui.styleChip(this, loopChip, loop);
        projectName = o.optString("projectName", projectTitle);
        refreshFilters();
        updateFilterUi();
        pick.title.setText(projectTitle);
        if (o.optBoolean("nameEdited", false)) {
            nameEdit.setText(o.optString("outName", ""));
            nameEdited = true;
        }
        infoTable.set(1, null);
        infoTable.set(2, null);
        infoTable.setVisibility(View.VISIBLE);
        String ou = o.optString("origUri", "");
        if (!ou.isEmpty()) {
            origUri = Uri.parse(ou);
            final Uri fou = origUri;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String[] d = AudioInfo.describe(AudioEditorActivity.this, fou);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (fou == origUri) {
                                    infoTable.set(1, d);
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
        }
        wave.setSelection(-1, -1);
        wave.refreshModel();
        wave.zoomAll();
        wave.setCursor(Math.min(o.optLong("cursor", 0), eng.total()));
        updateAll();
        wave.ensureVisible(wave.cursor);
    }

    // ------------------------------------------------------------------ apertura

    private void pickAudio(int req) {
        if (busy) {
            return;
        }
        stopPlay();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_OPEN && req != REQ_ADD) || res != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        ProjStore.keep(this, data.getData());
        startLoad(data.getData(), req == REQ_ADD && eng.total() > 0);
    }

    private void startLoad(final Uri uri, final boolean add) {
        final String name = FileUtil.queryName(this, uri);
        if (!add) {
            origUri = uri;
            projectId = null;
            projectTitle = null;
            eng.clearAll();
            filters.clear();
            fUndo.clear();
            refreshFilters();
            wave.setSelection(-1, -1);
            wave.setCursor(0);
            wave.refreshModel();
        }
        busy = true;
        updateAll();
        result.hide();
        loadText.setText("Caricamento di " + name);
        loadPct.setText("Decodifica in corso...");
        loadBar.setIndeterminate(true);
        loadBox.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final AudioEngine.Src s = eng.load(AudioEditorActivity.this, uri,
                            new AudioEngine.Progress() {
                                @Override
                                public void onProgress(final int pct) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (pct < 0) {
                                                loadBar.setIndeterminate(true);
                                            } else {
                                                loadBar.setIndeterminate(false);
                                                loadBar.setProgress(pct);
                                                loadPct.setText("Decodifica " + pct + "%");
                                            }
                                        }
                                    });
                                }
                            });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onLoaded(s, add, name);
                        }
                    });
                } catch (Throwable t) {
                    final String m = t instanceof OutOfMemoryError ? "memoria insufficiente"
                            : t.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            loadBox.setVisibility(View.GONE);
                            result.error("Non riesco a leggere questo file" + (m != null ? ": " + m : "."));
                            updateAll();
                        }
                    });
                }
            }
        }).start();
    }

    private void onLoaded(AudioEngine.Src s, boolean add, String name) {
        busy = false;
        loadBox.setVisibility(View.GONE);
        result.hide();
        int idx = eng.srcs.indexOf(s);
        AudioEngine.Clip c = new AudioEngine.Clip(idx, 0, s.frames);
        if (add) {
            pushU();
            long at = wave.cursor;
            List<AudioEngine.Clip> ins = new ArrayList<>();
            ins.add(c);
            eng.insert(at, ins);
            filtersInsert(at, s.frames);
            wave.setSelection(at, at + s.frames);
            wave.setCursor(at + s.frames);
            wave.refreshModel();
        } else {
            projectName = FileUtil.baseName(name);
            infoTable.set(1, null);
            infoTable.set(2, null);
            infoTable.setVisibility(View.VISIBLE);
            final Uri ou = origUri;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String[] d = AudioInfo.describe(AudioEditorActivity.this, ou);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (ou == origUri) {
                                infoTable.set(1, d);
                            }
                        }
                    });
                }
            }).start();
            List<AudioEngine.Clip> l = new ArrayList<>();
            l.add(c);
            eng.tl = l;
            wave.setSelection(-1, -1);
            wave.setCursor(0);
            wave.zoomAll();
            pick.title.setText(name);
        }
        updateAll();
        wave.ensureVisible(wave.cursor);
    }

    // ------------------------------------------------------------------ riproduzione

    /** Cosa riprodurre: la selezione se il cursore e' al suo interno, altrimenti dal cursore alla fine. */
    private long[] playRange() {
        long total = eng.total(), c = wave.cursor;
        if (selValid() && c >= wave.selA && c <= wave.selB) {
            return new long[]{c >= wave.selB ? wave.selA : c, wave.selB};
        }
        return new long[]{c >= total ? 0 : c, total};
    }

    private void togglePlay() {
        if (player != null) {
            stopPlay();
            return;
        }
        if (eng.total() <= 0) {
            return;
        }
        long[] r = playRange();
        play(r[0], r[1]);
    }

    /** Sposta di delta millisecondi, anche durante l'ascolto. */
    private void seekRel(long deltaMs) {
        long total = eng.total();
        if (total <= 0 || busy) {
            return;
        }
        long base = player != null ? player.position() : wave.cursor;
        long np = Math.max(0, Math.min(total, base + deltaMs * AudioEngine.RATE / 1000));
        if (player != null) {
            long to = playTo;
            stopPlay();
            wave.setCursor(np);
            wave.ensureVisible(np);
            if (np < to) {
                play(np, to);
            }
        } else {
            wave.setCursor(np);
            wave.ensureVisible(np);
        }
        updateAll();
    }

    private void play(long from, long to) {
        stopPlay();
        playTo = to;
        final AudioEngine.Player[] ref = new AudioEngine.Player[1];
        ref[0] = new AudioEngine.Player(eng, from, to, fxSource, new AudioEngine.Player.Callback() {
            @Override
            public void onEnd() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (player == ref[0]) {
                            wave.setCursor(ref[0].position());
                            player = null;
                            btnPlay.setText("▶");
                            updateTexts();
                        }
                    }
                });
            }
        });
        player = ref[0];
        player.setLoop(loop);
        player.start();
        btnPlay.setText("❚❚");
        h.post(tick);
    }

    private void stopPlay() {
        AudioEngine.Player p = player;
        if (p != null) {
            long pos = p.position();
            player = null;
            p.stop();
            wave.setCursor(pos);
            btnPlay.setText("▶");
            updateTexts();
        }
    }

    // ------------------------------------------------------------------ modifica

    private boolean selValid() {
        return wave.selA >= 0 && wave.selB > wave.selA;
    }

    private boolean canEdit() {
        if (busy) {
            return false;
        }
        stopPlay();
        return eng.total() > 0;
    }

    private void markStart() {
        if (!canEdit()) {
            return;
        }
        long a = wave.cursor, b = wave.selB;
        if (b <= a) {
            b = -1;
        }
        wave.setSelection(a, b);
        updateAll();
    }

    private void markEnd() {
        if (!canEdit()) {
            return;
        }
        long b = wave.cursor, a = wave.selA;
        if (a < 0 || a >= b) {
            a = 0;
        }
        if (b <= a) {
            return;
        }
        wave.setSelection(a, b);
        updateAll();
    }

    private void opCut() {
        if (!canEdit() || !selValid()) {
            return;
        }
        long a = wave.selA, b = wave.selB;
        eng.clipboard = AudioEngine.slice(eng.tl, a, b);
        pushU();
        eng.remove(a, b);
        filtersCut(a, b);
        wave.setSelection(-1, -1);
        wave.setCursor(a);
        afterEdit();
    }

    private void opCopy() {
        if (!canEdit() || !selValid()) {
            return;
        }
        eng.clipboard = AudioEngine.slice(eng.tl, wave.selA, wave.selB);
        Toast.makeText(this, "Copiato: " + AudioWaveView.fmt(AudioEngine.total(eng.clipboard)),
                Toast.LENGTH_SHORT).show();
        updateAll();
    }

    private void opPaste() {
        if (!canEdit()) {
            return;
        }
        if (eng.clipboard.isEmpty()) {
            Toast.makeText(this, "Niente da incollare: prima copia o taglia un tratto.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        long at = wave.cursor, len = AudioEngine.total(eng.clipboard);
        pushU();
        eng.insert(at, eng.clipboard);
        filtersInsert(at, len);
        wave.setSelection(at, at + len);
        wave.setCursor(at + len);
        afterEdit();
    }

    private void opDelete() {
        if (!canEdit() || !selValid()) {
            return;
        }
        long a = wave.selA, bb = wave.selB;
        pushU();
        eng.remove(a, bb);
        filtersCut(a, bb);
        wave.setSelection(-1, -1);
        wave.setCursor(a);
        afterEdit();
    }

    private void opKeep() {
        if (!canEdit() || !selValid()) {
            return;
        }
        long ka = wave.selA, kb = wave.selB;
        pushU();
        eng.keep(ka, kb);
        filtersKeep(ka, kb);
        wave.setSelection(-1, -1);
        wave.setCursor(0);
        wave.zoomAll();
        afterEdit();
    }

    private void opUndo() {
        if (!canEdit() || !eng.canUndo()) {
            return;
        }
        eng.undo();
        if (!fUndo.isEmpty()) {
            filters.clear();
            filters.addAll(fUndo.pop());
        }
        refreshFilters();
        wave.setSelection(-1, -1);
        afterEdit();
    }

    private void afterEdit() {
        wave.refreshModel();
        wave.ensureVisible(wave.cursor);
        updateAll();
    }

    // ------------------------------------------------------------------ stato interfaccia

    private void updateTexts() {
        timeCur.setText(AudioWaveView.fmt(wave.cursor));
        timeTot.setText(AudioWaveView.fmt(eng.total()));
        if (selValid()) {
            selBig.setText(AudioWaveView.fmt(wave.selA) + " → " + AudioWaveView.fmt(wave.selB));
            selBig.setTextColor(Ui.ACCENT);
            selSub.setText("Durata " + String.format(Locale.US, "%.1f s",
                    (wave.selB - wave.selA) / (double) AudioEngine.RATE));
        } else if (wave.selA >= 0) {
            selBig.setText("Inizio a " + AudioWaveView.fmt(wave.selA));
            selBig.setTextColor(Ui.ACCENT);
            selSub.setText("Sposta il cursore dove vuoi che finisca e premi \"Fine qui\"");
        } else {
            selBig.setText("Nessuna selezione");
            selBig.setTextColor(Ui.DIM);
            selSub.setText("Sposta il cursore e premi \"Inizio qui\" e \"Fine qui\"");
        }
        if (eng.total() > 0) {
            long[] r = playRange();
            boolean whole = !(selValid() && wave.cursor >= wave.selA && wave.cursor <= wave.selB);
            playHint.setText((whole ? "Riproduce dal cursore alla fine" : "Riproduce la selezione "
                    + AudioWaveView.fmt(wave.selA) + " → " + AudioWaveView.fmt(wave.selB))
                    + (loop ? " · in loop" : ""));
        } else {
            playHint.setText("");
        }
    }

    private void updateScroll() {
        long total = eng.total();
        boolean zoomed = total > 0 && wave.viewLen < total - 1;
        scroll.setVisibility(zoomed ? View.VISIBLE : View.GONE);
        if (zoomed) {
            scroll.setProgress((int) (1000.0 * wave.viewStart / Math.max(1, total - wave.viewLen)));
        }
    }

    private void updateAll() {
        boolean has = eng.total() > 0;
        boolean sel = selValid();
        for (View v : needsAudio) {
            v.setEnabled(has && !busy);
        }
        bCut.setEnabled(has && sel && !busy);
        bCopy.setEnabled(has && sel && !busy);
        bDel.setEnabled(has && sel && !busy);
        bKeep.setEnabled(has && sel && !busy);
        bPaste.setEnabled(has && !eng.clipboard.isEmpty() && !busy);
        bUndo.setEnabled(has && eng.canUndo() && !busy);
        bMarkS.setEnabled(has && !busy);
        bMarkE.setEnabled(has && !busy);
        bAll.setEnabled(has && !busy);
        bNone.setEnabled(has && (wave.selA >= 0) && !busy);
        updateTexts();
        updateScroll();
    }

    // ------------------------------------------------------------------ esportazione

    private void export() {
        if (busy || eng.total() <= 0) {
            return;
        }
        stopPlay();
        busy = true;
        updateAll();
        final boolean wav = wavOut;
        final AudioEngine.FxSet f = fx;
        final String name = chosenName() + (wav ? ".wav" : ".m4a");
        infoTable.set(2, null);
        result.start("Esportazione in corso...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AudioEngine.Progress pr = new AudioEngine.Progress() {
                    @Override
                    public void onProgress(int pct) {
                        result.progress("Esportazione " + pct + "%", pct);
                    }
                };
                try {
                    Uri u;
                    String mime = wav ? "audio/x-wav" : "audio/mp4";
                    if (wav) {
                        u = FileUtil.save(AudioEditorActivity.this,
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/MamoTools", name,
                                mime, new FileUtil.Writer() {
                                    @Override
                                    public void write(OutputStream os) throws IOException {
                                        eng.exportWav(f, os, pr);
                                    }
                                });
                    } else {
                        u = FileUtil.saveFd(AudioEditorActivity.this,
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/MamoTools", name,
                                mime, new FileUtil.FdWriter() {
                                    @Override
                                    public void write(FileDescriptor fd) throws IOException {
                                        eng.exportM4a(f, fd, pr);
                                    }
                                });
                    }
                    result.ok("Fatto! Salvato in Musica/MamoTools/" + name, u, mime, "Ascolta il file");
                    final String[] info = AudioInfo.describe(AudioEditorActivity.this, u);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            infoTable.set(2, info);
                            resetName();
                        }
                    });
                } catch (Throwable t) {
                    String m = t.getMessage();
                    result.error("Esportazione non riuscita" + (m != null && !m.isEmpty() ? ": " + m : "."));
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            updateAll();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlay();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlay();
        h.removeCallbacksAndMessages(null);
        if (!busy) {
            eng.clearAll();
        }
    }
}
