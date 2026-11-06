package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Editor di un brano di "Produzioni": trasporto in alto, cinque schede in basso. */
public class ProdEditorActivity extends Activity implements ProdSoundsTab.Host {

    private static final int REQ_SAMPLE = 71;
    private static final String[] TAB_ICON = {"🥁", "🎹", "🧱", "🎚", "💾"};
    private static final String[] TAB_NAME = {"Pattern", "Suoni", "Brano", "Mix", "Esporta"};

    private ProdSession s;
    private ProdSession.Tab[] tabs;
    private ProdSoundsTab soundsTab;
    private FrameLayout content;
    private TextView[] tabIcons = new TextView[5], tabLabels = new TextView[5];
    private LinearLayout[] tabBtns = new LinearLayout[5];
    private TextView playBtn, bpmTv, posTv, titleTv, modeP, modeS, loopChip;
    private int tab = 0, pendingTrack = 0;
    private boolean lastPlaying = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            h.postDelayed(this, 40);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        String id = getIntent().getStringExtra("id");
        ProdStore store = new ProdStore(this);
        ProdModel.Song song;
        try {
            song = id == null ? null : store.load(id);
        } catch (Exception e) {
            song = null;
        }
        if (song == null) {
            Toast.makeText(this, "Impossibile aprire il brano.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        s = new ProdSession(this, id, song, store);
        s.player.open();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.addView(buildTransport(), new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        root.addView(buildTabBar(), new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        setContentView(root);

        soundsTab = new ProdSoundsTab(s, this);
        tabs = new ProdSession.Tab[]{new ProdPatternTab(s), soundsTab, new ProdSongTab(s), new ProdMixTab(s),
                new ProdExportTab(s, this)};
        showTab(0);
        updateTransport();
    }

    // ------------------------------------------------------------------ trasporto

    private View buildTransport() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.CARD);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));

        titleTv = Ui.text(this, s.song.title, 13, Ui.DIM, true);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(titleTv);

        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = Ui.text(this, "▶", 22, 0xFF0F1115, true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(Ui.round(this, Ui.ACCENT, 28));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        r1.addView(playBtn, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        modeP = ProdUi.chip(this, "Pattern", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(ProdEngine.MODE_PATTERN);
            }
        });
        modeS = ProdUi.chip(this, "Brano", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(ProdEngine.MODE_SONG);
            }
        });
        loopChip = ProdUi.chip(this, "Loop", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (s.mode == ProdEngine.MODE_SONG) {
                    s.loop = !s.loop;
                    if (s.engine.playing) {
                        s.player.stop();
                    }
                    updateTransport();
                } else {
                    Toast.makeText(ProdEditorActivity.this, "Il pattern si ripete sempre. Il loop si sceglie per il brano.",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        r1.addView(modeP, Ui.lp(this, 0, Ui.dp(this, 42), 12, 0, 1f));
        r1.addView(modeS, Ui.lp(this, 0, Ui.dp(this, 42), 6, 0, 1f));
        r1.addView(loopChip, Ui.lp(this, 0, Ui.dp(this, 42), 6, 0, 1f));
        box.addView(r1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));

        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.addView(bpmBtn("−", -1));
        bpmTv = Ui.text(this, "", 16, Ui.TEXT, true);
        bpmTv.setGravity(Gravity.CENTER);
        bpmTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProdUi.askNumber(ProdEditorActivity.this, "Battiti al minuto (BPM)", s.song.bpm, 40, 220,
                        new ProdUi.IntCb() {
                            @Override
                            public void on(int v) {
                                s.song.bpm = v;
                                s.changed();
                                updateTransport();
                            }
                        });
            }
        });
        r2.addView(bpmTv, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.WRAP));
        r2.addView(bpmBtn("+", 1));
        posTv = Ui.text(this, "", 13, Ui.DIM, false);
        posTv.setGravity(Gravity.END);
        r2.addView(posTv, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        box.addView(r2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        return box;
    }

    private View bpmBtn(String t, final int d) {
        android.widget.Button b = Ui.btn(this, t, Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.song.bpm = ProdModel.clamp(s.song.bpm + d, 40, 220);
                s.changed();
                updateTransport();
            }
        });
        b.setTextSize(18);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 38)));
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                s.song.bpm = ProdModel.clamp(s.song.bpm + d * 5, 40, 220);
                s.changed();
                updateTransport();
                return true;
            }
        });
        return b;
    }

    private void setMode(int m) {
        if (s.mode == m) {
            return;
        }
        s.player.stop();
        s.mode = m;
        updateTransport();
    }

    private void togglePlay() {
        if (s.engine.playing) {
            s.player.stop();
            updateTransport();
            return;
        }
        if (s.mode == ProdEngine.MODE_SONG) {
            if (s.song.arr.isEmpty()) {
                Toast.makeText(this, "Il brano è vuoto: aggiungi dei blocchi nella scheda Brano.",
                        Toast.LENGTH_LONG).show();
                showTab(2);
                return;
            }
            s.player.play(ProdEngine.MODE_SONG, s.loop);
        } else {
            s.engine.curPattern = s.curPattern;
            s.player.play(ProdEngine.MODE_PATTERN, true);
        }
        updateTransport();
    }

    private void updateTransport() {
        boolean playing = s.engine.playing;
        playBtn.setText(playing ? "■" : "▶");
        bpmTv.setText(s.song.bpm + " BPM");
        titleTv.setText(s.song.title);
        Ui.styleChip(this, modeP, s.mode == ProdEngine.MODE_PATTERN);
        Ui.styleChip(this, modeS, s.mode == ProdEngine.MODE_SONG);
        Ui.styleChip(this, loopChip, s.mode == ProdEngine.MODE_SONG && s.loop);
        loopChip.setAlpha(s.mode == ProdEngine.MODE_SONG ? 1f : 0.45f);
        if (!playing) {
            posTv.setText("Fermo");
        }
        lastPlaying = playing;
    }

    private void tick() {
        boolean playing = s.engine.playing;
        if (playing != lastPlaying) {
            updateTransport();
        }
        int[] pos = playing ? s.player.position() : null;
        if (pos != null) {
            posTv.setText(String.format(java.util.Locale.ITALY, "Pattern %s · passo %02d",
                    ProdUi.letter(pos[1]), pos[2] + 1));
        }
        tabs[tab].tick(pos);
    }

    // ------------------------------------------------------------------ schede

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.CARD);
        for (int i = 0; i < 5; i++) {
            final int t = i;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(t);
                }
            });
            tabIcons[i] = Ui.text(this, TAB_ICON[i], 19, Ui.TEXT, false);
            tabIcons[i].setGravity(Gravity.CENTER);
            tabLabels[i] = Ui.text(this, TAB_NAME[i], 11, Ui.DIM, true);
            tabLabels[i].setGravity(Gravity.CENTER);
            b.addView(tabIcons[i], new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            b.addView(tabLabels[i], new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            tabBtns[i] = b;
            bar.addView(b, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        }
        return bar;
    }

    private void showTab(int t) {
        tab = t;
        content.removeAllViews();
        content.addView(tabs[t].view(), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        for (int i = 0; i < 5; i++) {
            boolean on = i == t;
            tabLabels[i].setTextColor(on ? Ui.ACCENT : Ui.DIM);
            tabIcons[i].setAlpha(on ? 1f : 0.55f);
            tabBtns[i].setBackground(on ? Ui.round(this, 0x224DD0E1, 12) : null);
        }
        tabs[t].onShow();
        updateTransport();
    }

    // ------------------------------------------------------------------ sample

    @Override
    public void pickSample(int track) {
        pendingTrack = track;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_SAMPLE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAMPLE || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri u = data.getData();
        final int track = pendingTrack;
        Toast.makeText(this, "Importo il sample…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null, sid = null;
                String name = FileUtil.displayName(ProdEditorActivity.this, u);
                try {
                    sid = s.store.importSample(ProdEditorActivity.this, s.id, u);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String fs = sid, fe = err;
                final String fn = name.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fe != null) {
                            Toast.makeText(ProdEditorActivity.this, "Sample non importato: " + fe,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        ProdModel.Track t = s.song.tracks[track];
                        t.sound = "sample";
                        t.sample = fs;
                        t.sampleName = fn;
                        t.smpStart = 0;
                        t.smpEnd = 1000;
                        t.tone = 0;
                        t.decay = 50;
                        t.cutoff = 100;
                        t.lastNote = -1;
                        s.changed();
                        soundsTab.refresh();
                        soundsTab.listen();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onResume() {
        super.onResume();
        if (s != null) {
            h.post(ticker);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(ticker);
        if (s != null) {
            s.player.stop();
            s.saveNow();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (s != null) {
            s.saveNow();
            s.player.release();
        }
    }
}
