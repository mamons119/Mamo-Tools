package it.mamotools;

import android.content.Context;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Scheda "Suoni": scelta dello strumento di ogni traccia, regolazioni e sample importati. */
final class ProdSoundsTab implements ProdSession.Tab {

    interface Host {
        void pickSample(int track);
    }

    private final ProdSession s;
    private final Context c;
    private final Host host;
    private final ScrollView root;
    private final TextView[] trackChips = new TextView[ProdModel.TRACKS];
    private final TextView soundName, soundKind, sampleInfo;
    private final ProdUi.Slider tone, decay, cutoff, vol, pan, smpStart, smpEnd;
    private final LinearLayout sampleBox;
    private final ProdWaveView wave;

    ProdSoundsTab(ProdSession session, Host host) {
        s = session;
        c = s.ctx;
        this.host = host;
        root = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 24));
        root.addView(col, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        for (int i = 0; i < ProdModel.TRACKS; i++) {
            final int t = i;
            TextView tv = ProdUi.chip(c, "", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.curTrack = t;
                    refresh();
                }
            });
            trackChips[i] = tv;
            row.addView(tv, Ui.lp(c, Ui.WRAP, Ui.dp(c, 38), i == 0 ? 0 : 6, 0));
        }
        hs.addView(row);
        col.addView(hs);

        LinearLayout card = Ui.card(c);
        soundKind = Ui.text(c, "Suono della traccia", 12, Ui.DIM, false);
        card.addView(soundKind);
        soundName = Ui.text(c, "", 22, Ui.TEXT, true);
        card.addView(soundName, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 2));
        LinearLayout btns = new LinearLayout(c);
        btns.addView(Ui.btn(c, "Cambia suono", Ui.ACCENT, 0xFF0F1115, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSound();
            }
        }), Ui.lp(c, 0, Ui.dp(c, 44), 0, 0, 1f));
        btns.addView(Ui.btn(c, "▶  Ascolta", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listen();
            }
        }), Ui.lp(c, 0, Ui.dp(c, 44), 8, 0, 1f));
        card.addView(btns, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));
        col.addView(card, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout ctl = Ui.card(c);
        ctl.addView(Ui.section(c, "Regolazioni"));
        tone = new ProdUi.Slider(c, "Tono", -12, 12, 0, new ProdUi.Fmt() {
            @Override
            public String f(int v) {
                return String.format(Locale.US, "%+d semitoni", v);
            }
        }, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                tr().tone = v;
                s.changed();
            }
        });
        tone.release = new Runnable() {
            @Override
            public void run() {
                listen();
            }
        };
        decay = new ProdUi.Slider(c, "Durata", 0, 100, 50, new ProdUi.Fmt() {
            @Override
            public String f(int v) {
                return String.format(Locale.US, "×%.1f", Math.pow(2.0, (v - 50) / 25.0));
            }
        }, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                tr().decay = v;
                s.changed();
            }
        });
        decay.release = tone.release;
        cutoff = new ProdUi.Slider(c, "Filtro (suono più scuro verso sinistra)", 0, 100, 100, new ProdUi.Fmt() {
            @Override
            public String f(int v) {
                if (v >= 99) {
                    return "aperto";
                }
                double hz = 150.0 * Math.pow(120.0, v / 100.0);
                return hz < 1000 ? Math.round(hz) + " Hz" : String.format(Locale.US, "%.1f kHz", hz / 1000);
            }
        }, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                tr().cutoff = v;
                s.changed();
            }
        });
        cutoff.release = tone.release;
        vol = new ProdUi.Slider(c, "Volume", 0, 100, 80, ProdUi.PCT, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                tr().vol = v;
                s.changed();
            }
        });
        vol.release = tone.release;
        pan = new ProdUi.Slider(c, "Posizione stereo", -100, 100, 0, new ProdUi.Fmt() {
            @Override
            public String f(int v) {
                return v == 0 ? "centro" : (v < 0 ? "sinistra " + (-v) : "destra " + v);
            }
        }, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                tr().pan = v;
                s.changed();
            }
        });
        pan.release = tone.release;
        ProdUi.Slider[] all = {tone, decay, cutoff, vol, pan};
        for (ProdUi.Slider sl : all) {
            ctl.addView(sl.view, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));
        }
        col.addView(ctl, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));

        // sample
        sampleBox = Ui.card(c);
        sampleBox.addView(Ui.section(c, "Il tuo sample"));
        sampleInfo = Ui.text(c, "", 13, Ui.DIM, false);
        sampleBox.addView(sampleInfo);
        wave = new ProdWaveView(c);
        sampleBox.addView(wave, Ui.lp(c, Ui.MATCH, Ui.dp(c, 90), 0, 10));
        smpStart = new ProdUi.Slider(c, "Inizio", 0, 990, 0, new ProdUi.Fmt() {
            @Override
            public String f(int v) {
                return String.format(Locale.US, "%.0f%%", v / 10.0);
            }
        }, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                ProdModel.Track t = tr();
                t.smpStart = Math.min(v, t.smpEnd - 5);
                wave.setRange(t.smpStart, t.smpEnd);
                s.changed();
            }
        });
        smpStart.release = tone.release;
        smpEnd = new ProdUi.Slider(c, "Fine", 10, 1000, 1000, new ProdUi.Fmt() {
            @Override
            public String f(int v) {
                return String.format(Locale.US, "%.0f%%", v / 10.0);
            }
        }, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                ProdModel.Track t = tr();
                t.smpEnd = Math.max(v, t.smpStart + 5);
                wave.setRange(t.smpStart, t.smpEnd);
                s.changed();
            }
        });
        smpEnd.release = tone.release;
        sampleBox.addView(smpStart.view, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 10));
        sampleBox.addView(smpEnd.view, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));
        sampleBox.addView(Ui.btn(c, "Scegli un altro sample dal telefono", Ui.CARD2, Ui.ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Host h = ProdSoundsTab.this.host;
                        h.pickSample(s.curTrack);
                    }
                }), Ui.lp(c, Ui.MATCH, Ui.dp(c, 44), 0, 12));
        sampleBox.addView(Ui.text(c, "Per i sample le note dei pad cambiano l'intonazione. Durata massima: 30 secondi.",
                12, Ui.DIM, false), Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 8));
        col.addView(sampleBox, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));
        refresh();
    }

    private ProdModel.Track tr() {
        return s.song.tracks[s.curTrack];
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void tick(int[] pos) {
    }

    void listen() {
        ProdModel.Track t = tr();
        s.player.preview(s.curTrack, t.melodic() ? s.curNote(t) : 60);
    }

    private void chooseSound() {
        final String[] items = new String[ProdModel.SOUNDS.length];
        for (int i = 0; i < items.length; i++) {
            String grp = i < 7 ? "Batteria" : (i < 13 ? "Strumento" : "Altro");
            items[i] = ProdModel.LABELS[i] + (i == items.length - 1 ? " (dal telefono…)" : "") + "   · " + grp;
        }
        ProdUi.pickList(c, "Suono della traccia " + (s.curTrack + 1), items, new ProdUi.IntCb() {
            @Override
            public void on(int which) {
                ProdModel.Track t = tr();
                String id = ProdModel.SOUNDS[which];
                boolean toSample = id.equals("sample");
                if (!id.equals(t.sound)) {
                    t.sound = id;
                    t.tone = 0;
                    t.decay = 50;
                    t.cutoff = id.equals("pad") ? 62 : 100;
                    t.lastNote = -1;
                    s.changed();
                }
                refresh();
                if (toSample && t.sample.isEmpty()) {
                    host.pickSample(s.curTrack);
                } else {
                    listen();
                }
            }
        });
    }

    void refresh() {
        for (int i = 0; i < ProdModel.TRACKS; i++) {
            ProdModel.Track t = s.song.tracks[i];
            String name = (i + 1) + " " + t.label();
            if (name.length() > 16) {
                name = name.substring(0, 15) + "…";
            }
            trackChips[i].setText(name);
            ProdUi.colorChip(c, trackChips[i], i == s.curTrack, ProdModel.TRACK_COLORS[i]);
            if (i != s.curTrack) {
                trackChips[i].setTextColor(ProdModel.TRACK_COLORS[i]);
            }
        }
        ProdModel.Track t = tr();
        soundName.setText(t.label());
        soundName.setTextColor(ProdModel.TRACK_COLORS[s.curTrack]);
        soundKind.setText("Traccia " + (s.curTrack + 1) + " · " + (t.melodic() ? "strumento a note" : "percussione"));
        tone.set(t.tone);
        decay.set(t.decay);
        cutoff.set(t.cutoff);
        vol.set(t.vol);
        pan.set(t.pan);
        boolean smp = t.sound.equals("sample");
        sampleBox.setVisibility(smp ? View.VISIBLE : View.GONE);
        if (smp) {
            ProdSynth.Sample sm = t.sample.isEmpty() ? null : s.store.get(t.sample);
            if (sm == null) {
                sampleInfo.setText("Nessun sample caricato. Tocca il pulsante qui sotto per sceglierne uno.");
                wave.set(null, 0, 1000, ProdModel.TRACK_COLORS[s.curTrack]);
            } else {
                sampleInfo.setText(t.sampleName + " · " + String.format(Locale.US, "%.1f s",
                        sm.frames() / (double) ProdSynth.RATE) + (sm.r == null ? " · mono" : " · stereo"));
                wave.set(sm.l, t.smpStart, t.smpEnd, ProdModel.TRACK_COLORS[s.curTrack]);
            }
            smpStart.set(t.smpStart);
            smpEnd.set(t.smpEnd);
        }
    }
}
