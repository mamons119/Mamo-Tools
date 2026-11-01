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
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Editor audio: taglia, copia, incolla, filtri passa-alto/basso/banda, volume ed esportazione. */
public class AudioEditorActivity extends Activity {

    private static final int REQ_OPEN = 61, REQ_ADD = 62;

    private final AudioEngine eng = new AudioEngine();
    private final Handler h = new Handler(Looper.getMainLooper());
    private AudioWaveView wave;
    private AudioEngine.Player player;
    private volatile AudioEngine.Fx fx = new AudioEngine.Fx(AudioEngine.Fx.NONE, 200, 4000, 1, 0);
    private final AudioEngine.FxSource fxSource = new AudioEngine.FxSource() {
        @Override
        public AudioEngine.Fx get() {
            return fx;
        }
    };
    private volatile boolean busy = false;
    private String projectName = "audio";

    // interfaccia
    private Ui.PickCard pick;
    private TextView tvTime, tvSel, tvLo, tvHi, tvGain;
    private SeekBar scroll, seekLo, seekHi, seekGain;
    private Button btnPlay, btnSelPlay;
    private final List<View> needsAudio = new ArrayList<>();
    private Button bCut, bCopy, bPaste, bDel, bKeep, bUndo;
    private TextView[] modeChips, slopeChips, fmtChips;
    private LinearLayout rowLo, rowHi, rowSlope;
    private int mode = AudioEngine.Fx.NONE, order = 1;
    private boolean wavOut = false;
    private ResultCard result;

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
        LinearLayout root = Ui.screen(this, "Audio Editor",
                "Taglia, incolla, filtra e regola il volume di una traccia audio.");

        pick = new Ui.PickCard(this, "🎧", "Apri un audio", "MP3, M4A, WAV, OGG… oppure l'audio di un video",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickAudio(REQ_OPEN);
                    }
                });
        root.addView(pick.view);

        // ---- forma d'onda
        LinearLayout wc = Ui.card(this);
        root.addView(wc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        wave = new AudioWaveView(this, eng);
        wc.addView(wave, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 150)));
        tvTime = Ui.text(this, "0:00.0 / 0:00.0", 16, Ui.TEXT, true);
        tvTime.setPadding(0, Ui.dp(this, 10), 0, 0);
        wc.addView(tvTime);
        tvSel = Ui.text(this, "Nessuna selezione", 12, Ui.DIM, false);
        wc.addView(tvSel);
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

        LinearLayout zr = new LinearLayout(this);
        zr.addView(small("−  Zoom", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wave.zoom(0.5f);
            }
        }, true), Ui.lp(this, 0, Ui.dp(this, 40), 0, 0, 1f));
        zr.addView(small("＋  Zoom", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wave.zoom(2f);
            }
        }, true), Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 1f));
        zr.addView(small("Tutto", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wave.zoomAll();
            }
        }, true), Ui.lp(this, 0, Ui.dp(this, 40), 8, 0, 1f));
        wc.addView(zr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        TextView tip = Ui.text(this, "Tocca la forma d'onda per spostare il cursore; pizzica per ingrandire.",
                11, Ui.DIM, false);
        tip.setPadding(0, Ui.dp(this, 6), 0, 0);
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

        // ---- trasporto
        LinearLayout tr = new LinearLayout(this);
        root.addView(tr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        Button toStart = small("⏮", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlay();
                wave.setCursor(0);
                wave.ensureVisible(0);
                updateTexts();
            }
        }, false);
        tr.addView(toStart, Ui.lp(this, Ui.dp(this, 56), Ui.dp(this, 52), 0, 0));
        btnPlay = Ui.btn(this, "▶  Ascolta", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        tr.addView(btnPlay, Ui.lp(this, 0, Ui.dp(this, 52), 8, 0, 1f));
        btnSelPlay = Ui.btn(this, "▶  Selezione", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playSelection();
            }
        });
        tr.addView(btnSelPlay, Ui.lp(this, 0, Ui.dp(this, 52), 8, 0, 1f));
        needsAudio.add(toStart);
        needsAudio.add(btnPlay);

        // ---- modifica
        LinearLayout ec = Ui.card(this);
        root.addView(ec, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        ec.addView(Ui.section(this, "Modifica"));
        LinearLayout m1 = new LinearLayout(this);
        m1.addView(small("[  Inizio", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                markStart();
            }
        }, false), Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        m1.addView(small("Fine  ]", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                markEnd();
            }
        }, false), Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        m1.addView(small("Deseleziona", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wave.setSelection(-1, -1);
                updateAll();
            }
        }, false), Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        ec.addView(m1);
        LinearLayout m2 = new LinearLayout(this);
        bCut = small("✂  Taglia", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                opCut();
            }
        }, false);
        bCopy = small("Copia", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                opCopy();
            }
        }, false);
        bPaste = small("Incolla", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                opPaste();
            }
        }, false);
        m2.addView(bCut, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        m2.addView(bCopy, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        m2.addView(bPaste, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        ec.addView(m2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        LinearLayout m3 = new LinearLayout(this);
        bDel = small("🗑  Elimina", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                opDelete();
            }
        }, false);
        bKeep = small("Mantieni", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                opKeep();
            }
        }, false);
        bUndo = small("↶  Annulla", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                opUndo();
            }
        }, false);
        m3.addView(bDel, Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        m3.addView(bKeep, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        m3.addView(bUndo, Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        ec.addView(m3, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        ec.addView(Ui.btn(this, "＋  Inserisci un altro audio al cursore", Ui.CARD2, Ui.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickAudio(REQ_ADD);
                    }
                }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 46), 0, 8));
        TextView etip = Ui.text(this, "Metti Inizio e Fine sul cursore per selezionare un tratto; "
                + "poi taglialo, copialo o eliminalo. \"Mantieni\" scarta tutto il resto.", 11, Ui.DIM, false);
        etip.setPadding(0, Ui.dp(this, 8), 0, 0);
        ec.addView(etip);

        // ---- filtri
        LinearLayout fc = Ui.card(this);
        root.addView(fc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        fc.addView(Ui.section(this, "Filtro"));
        LinearLayout f1 = new LinearLayout(this);
        String[] names = {"Nessuno", "Passa-alto", "Passa-basso", "Passa-banda"};
        modeChips = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int m = i;
            modeChips[i] = Ui.chip(this, names[i]);
            modeChips[i].setTextSize(12);
            modeChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mode = m;
                    rebuildFx();
                    updateFilterUi();
                }
            });
            f1.addView(modeChips[i], Ui.lp(this, 0, Ui.dp(this, 42), i == 0 ? 0 : 6, 0, 1f));
        }
        fc.addView(f1);

        rowLo = new LinearLayout(this);
        rowLo.setOrientation(LinearLayout.VERTICAL);
        tvLo = Ui.text(this, "", 13, Ui.TEXT, false);
        rowLo.addView(tvLo);
        seekLo = Ui.seek(this, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                updateFilterTexts();
                rebuildFx();
            }
        });
        seekLo.setMax(1000);
        seekLo.setProgress(333);
        rowLo.addView(seekLo);
        fc.addView(rowLo, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        rowHi = new LinearLayout(this);
        rowHi.setOrientation(LinearLayout.VERTICAL);
        tvHi = Ui.text(this, "", 13, Ui.TEXT, false);
        rowHi.addView(tvHi);
        seekHi = Ui.seek(this, new Ui.SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                updateFilterTexts();
                rebuildFx();
            }
        });
        seekHi.setMax(1000);
        seekHi.setProgress(767);
        rowHi.addView(seekHi);
        fc.addView(rowHi, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));

        rowSlope = new LinearLayout(this);
        slopeChips = new TextView[2];
        String[] sl = {"Morbido (12 dB/ott.)", "Ripido (24 dB/ott.)"};
        for (int i = 0; i < 2; i++) {
            final int o = i + 1;
            slopeChips[i] = Ui.chip(this, sl[i]);
            slopeChips[i].setTextSize(12);
            slopeChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    order = o;
                    rebuildFx();
                    updateFilterUi();
                }
            });
            rowSlope.addView(slopeChips[i], Ui.lp(this, 0, Ui.dp(this, 40), i == 0 ? 0 : 8, 0, 1f));
        }
        fc.addView(rowSlope, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        TextView ftip = Ui.text(this, "Il filtro vale per tutta la traccia: lo senti subito in ascolto "
                + "e viene applicato all'esportazione.", 11, Ui.DIM, false);
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
        xc.addView(Ui.section(this, "Esporta"));
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
                    updateFilterUi();
                }
            });
            x1.addView(fmtChips[i], Ui.lp(this, 0, Ui.dp(this, 42), i == 0 ? 0 : 8, 0, 1f));
        }
        xc.addView(x1);
        Button export = Ui.btn(this, "Esporta in Musica/MamoTools", Ui.ACCENT, 0xFF000000,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        export();
                    }
                });
        export.setTextSize(16);
        xc.addView(export, Ui.lp(this, Ui.MATCH, Ui.dp(this, 52), 0, 12));
        needsAudio.add(export);

        result = new ResultCard(this);
        root.addView(result.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        updateFilterUi();
        updateFilterTexts();
        updateAll();
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

    private static float freqOf(int p) {
        return (float) (20.0 * Math.pow(1000.0, p / 1000.0));
    }

    private static String freqText(float f) {
        return f < 1000 ? String.format(Locale.US, "%d Hz", Math.round(f))
                : String.format(Locale.US, "%.1f kHz", f / 1000f);
    }

    private float gainDb() {
        return (seekGain.getProgress() - 200) / 10f;
    }

    private void rebuildFx() {
        if (seekLo == null || seekHi == null || seekGain == null) {
            return;
        }
        float lo = freqOf(seekLo.getProgress()), hi = freqOf(seekHi.getProgress());
        fx = new AudioEngine.Fx(mode, lo, hi, order, gainDb());
    }

    private void updateFilterTexts() {
        if (seekLo == null || seekHi == null || seekGain == null || tvLo == null || tvHi == null
                || tvGain == null) {
            return;
        }
        float g = gainDb();
        tvGain.setText(String.format(Locale.US, "%+.1f dB  (×%.2f)", g, Math.pow(10, g / 20.0)));
        float lo = freqOf(seekLo.getProgress()), hi = freqOf(seekHi.getProgress());
        if (mode == AudioEngine.Fx.BP) {
            tvLo.setText("Taglia sotto i  " + freqText(lo));
            tvHi.setText("Taglia sopra i  " + freqText(hi));
        } else if (mode == AudioEngine.Fx.HP) {
            tvLo.setText("Frequenza di taglio:  " + freqText(lo) + "  (passano le frequenze piu' alte)");
        } else if (mode == AudioEngine.Fx.LP) {
            tvHi.setText("Frequenza di taglio:  " + freqText(hi) + "  (passano le frequenze piu' basse)");
        }
    }

    private void updateFilterUi() {
        for (int i = 0; i < 4; i++) {
            Ui.styleChip(this, modeChips[i], mode == i);
        }
        for (int i = 0; i < 2; i++) {
            Ui.styleChip(this, slopeChips[i], order == i + 1);
            Ui.styleChip(this, fmtChips[i], wavOut == (i == 1));
        }
        rowLo.setVisibility(mode == AudioEngine.Fx.HP || mode == AudioEngine.Fx.BP ? View.VISIBLE : View.GONE);
        rowHi.setVisibility(mode == AudioEngine.Fx.LP || mode == AudioEngine.Fx.BP ? View.VISIBLE : View.GONE);
        rowSlope.setVisibility(mode == AudioEngine.Fx.NONE ? View.GONE : View.VISIBLE);
        updateFilterTexts();
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
        final Uri uri = data.getData();
        final boolean add = req == REQ_ADD && eng.total() > 0;
        final String name = FileUtil.queryName(this, uri);
        if (!add) {
            eng.clearAll();
            wave.setSelection(-1, -1);
            wave.setCursor(0);
            wave.refreshModel();
        }
        busy = true;
        updateAll();
        result.start("Lettura e decodifica di \"" + name + "\"...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final AudioEngine.Src s = eng.load(AudioEditorActivity.this, uri);
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
        result.hide();
        int idx = eng.srcs.indexOf(s);
        AudioEngine.Clip c = new AudioEngine.Clip(idx, 0, s.frames);
        if (add) {
            eng.pushUndo();
            long at = wave.cursor;
            List<AudioEngine.Clip> ins = new ArrayList<>();
            ins.add(c);
            eng.insert(at, ins);
            wave.setSelection(at, at + s.frames);
            wave.setCursor(at + s.frames);
            wave.refreshModel();
        } else {
            projectName = FileUtil.baseName(name);
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

    private void togglePlay() {
        if (player != null) {
            stopPlay();
            return;
        }
        long total = eng.total();
        if (total <= 0) {
            return;
        }
        long from = wave.cursor >= total ? 0 : wave.cursor;
        play(from, total);
    }

    private void playSelection() {
        if (!selValid()) {
            Toast.makeText(this, "Prima seleziona un tratto con Inizio e Fine.", Toast.LENGTH_SHORT).show();
            return;
        }
        play(wave.selA, wave.selB);
    }

    private void play(long from, long to) {
        stopPlay();
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
                            btnPlay.setText("▶  Ascolta");
                            updateTexts();
                        }
                    }
                });
            }
        });
        player = ref[0];
        player.start();
        btnPlay.setText("⏸  Pausa");
        h.post(tick);
    }

    private void stopPlay() {
        AudioEngine.Player p = player;
        if (p != null) {
            long pos = p.position();
            player = null;
            p.stop();
            wave.setCursor(pos);
            btnPlay.setText("▶  Ascolta");
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
        eng.pushUndo();
        eng.remove(a, b);
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
        eng.pushUndo();
        eng.insert(at, eng.clipboard);
        wave.setSelection(at, at + len);
        wave.setCursor(at + len);
        afterEdit();
    }

    private void opDelete() {
        if (!canEdit() || !selValid()) {
            return;
        }
        long a = wave.selA;
        eng.pushUndo();
        eng.remove(a, wave.selB);
        wave.setSelection(-1, -1);
        wave.setCursor(a);
        afterEdit();
    }

    private void opKeep() {
        if (!canEdit() || !selValid()) {
            return;
        }
        eng.pushUndo();
        eng.keep(wave.selA, wave.selB);
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
        tvTime.setText(AudioWaveView.fmt(wave.cursor) + " / " + AudioWaveView.fmt(eng.total()));
        if (selValid()) {
            tvSel.setText("Selezione: " + AudioWaveView.fmt(wave.selA) + " → "
                    + AudioWaveView.fmt(wave.selB) + "  ("
                    + String.format(Locale.US, "%.1f s", (wave.selB - wave.selA) / (double) AudioEngine.RATE)
                    + ")");
            tvSel.setTextColor(Ui.ACCENT);
        } else if (wave.selA >= 0) {
            tvSel.setText("Inizio impostato a " + AudioWaveView.fmt(wave.selA) + ": sposta il cursore e premi Fine");
            tvSel.setTextColor(Ui.ACCENT);
        } else {
            tvSel.setText("Nessuna selezione");
            tvSel.setTextColor(Ui.DIM);
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
        btnSelPlay.setEnabled(has && sel && !busy);
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
        final AudioEngine.Fx f = fx;
        final String name = "MamoAudio_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new java.util.Date()) + (wav ? ".wav" : ".m4a");
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
