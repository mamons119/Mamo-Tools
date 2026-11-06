package it.mamotools;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Scheda "Brano": i blocchi (pattern ripetuti) in ordine, con linea del tempo e forma d'onda. */
final class ProdSongTab implements ProdSession.Tab {

    private final ProdSession s;
    private final Context c;
    private final ScrollView root;
    private final ProdTimelineView timeline;
    private final TextView info;
    private final LinearLayout list;
    private int token = 0;

    ProdSongTab(ProdSession session) {
        s = session;
        c = s.ctx;
        root = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 24));
        root.addView(col, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        timeline = new ProdTimelineView(c);
        col.addView(timeline, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(c, 120)));
        info = Ui.text(c, "", 13, Ui.DIM, false);
        col.addView(info, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 8));

        TextView help = Ui.text(c, "Il brano è una fila di blocchi: ogni blocco suona un pattern (A–H) una o più "
                + "volte. Componi i pattern dalla scheda Pattern, poi mettili in ordine qui.", 12, Ui.DIM, false);
        col.addView(help, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 6));

        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));

        LinearLayout btns = new LinearLayout(c);
        btns.addView(Ui.btn(c, "+  Aggiungi blocco", Ui.ACCENT, 0xFF0F1115, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.song.arr.add(new int[]{s.curPattern, 1});
                s.changed();
                refresh();
            }
        }), Ui.lp(c, 0, Ui.dp(c, 46), 0, 0, 1.6f));
        btns.addView(Ui.btn(c, "Svuota", Ui.CARD2, Ui.DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.song.arr.clear();
                s.changed();
                refresh();
            }
        }), Ui.lp(c, 0, Ui.dp(c, 46), 8, 0, 1f));
        col.addView(btns, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));
        rebuild();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        rebuild();
        if (s.songDirty) {
            s.songDirty = false;
            computeWave();
        }
    }

    @Override
    public void tick(int[] pos) {
        int total = s.song.songSteps();
        if (pos == null || s.mode != ProdEngine.MODE_SONG || total == 0) {
            timeline.setHead(-1);
        } else {
            timeline.setHead((pos[0] % total) / (double) total);
        }
    }

    private void refresh() {
        rebuild();
        computeWave();
    }

    private void rebuild() {
        ProdModel.Song song = s.song;
        timeline.set(song);
        double sec = song.songSteps() * 60.0 / (song.bpm * 4.0);
        info.setText(song.arr.isEmpty() ? "Nessun blocco: il brano è vuoto."
                : String.format(Locale.ITALY, "%d blocchi · durata %s · %d BPM", song.arr.size(),
                ProdUi.time(sec), song.bpm));
        list.removeAllViews();
        for (int i = 0; i < song.arr.size(); i++) {
            list.addView(row(i), Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, i == 0 ? 0 : 8));
        }
    }

    private View row(final int i) {
        final int[] a = s.song.arr.get(i);
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round(c, Ui.CARD, 14));
        r.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));

        TextView badge = Ui.text(c, ProdUi.letter(a[0]), 20, 0xFF0F1115, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(c, ProdModel.TRACK_COLORS[a[0] % ProdModel.TRACK_COLORS.length], 12));
        badge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] n = new String[ProdModel.PATTERNS];
                for (int k = 0; k < n.length; k++) {
                    n[k] = "Pattern " + ProdUi.letter(k) + (s.song.patterns[k].isEmpty() ? "  (vuoto)" : "");
                }
                ProdUi.pickList(c, "Pattern del blocco " + (i + 1), n, new ProdUi.IntCb() {
                    @Override
                    public void on(int v) {
                        a[0] = v;
                        s.changed();
                        refresh();
                    }
                });
            }
        });
        r.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)));

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(Ui.dp(c, 10), 0, 0, 0);
        mid.addView(Ui.text(c, "Blocco " + (i + 1), 12, Ui.DIM, false));
        LinearLayout rep = new LinearLayout(c);
        rep.setGravity(Gravity.CENTER_VERTICAL);
        rep.addView(small("−", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (a[1] > 1) {
                    a[1]--;
                    s.changed();
                    refresh();
                }
            }
        }));
        TextView n = Ui.text(c, a[1] + (a[1] == 1 ? " volta" : " volte"), 14, Ui.TEXT, true);
        n.setGravity(Gravity.CENTER);
        rep.addView(n, new LinearLayout.LayoutParams(Ui.dp(c, 70), Ui.WRAP));
        rep.addView(small("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (a[1] < 64) {
                    a[1]++;
                    s.changed();
                    refresh();
                }
            }
        }));
        mid.addView(rep);
        r.addView(mid, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));

        r.addView(small("↑", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (i > 0) {
                    java.util.Collections.swap(s.song.arr, i, i - 1);
                    s.changed();
                    refresh();
                }
            }
        }));
        r.addView(small("↓", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (i < s.song.arr.size() - 1) {
                    java.util.Collections.swap(s.song.arr, i, i + 1);
                    s.changed();
                    refresh();
                }
            }
        }));
        r.addView(small("✕", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.song.arr.remove(i);
                s.changed();
                refresh();
            }
        }));
        return r;
    }

    private View small(String t, View.OnClickListener l) {
        android.widget.Button b = Ui.btn(c, t, Ui.CARD2, Ui.TEXT, l);
        b.setTextSize(16);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
        p.leftMargin = Ui.dp(c, 6);
        b.setLayoutParams(p);
        return b;
    }

    /** calcola la forma d'onda del brano intero in un thread */
    private void computeWave() {
        final int my = ++token;
        if (s.song.arr.isEmpty()) {
            timeline.setPeaks(null, "");
            return;
        }
        timeline.setPeaks(null, "calcolo della forma d'onda…");
        final ProdModel.Song copy = s.song.copy();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ProdEngine e = new ProdEngine(copy, s.store);
                    e.startExport(ProdEngine.MODE_SONG, 0, 1);
                    final long frames = e.exportFrames(e.exportSteps());
                    final float[] pk = new float[600];
                    final long per = Math.max(1, frames / pk.length);
                    final long[] done = {0};
                    ProdFiles.pump(e, frames, null, new ProdFiles.Sink() {
                        @Override
                        public void write(short[] x, int n) throws java.io.IOException {
                            if (my != token) {
                                throw new java.io.IOException("annullato");
                            }
                            for (int i = 0; i < n; i++) {
                                int b = (int) Math.min(pk.length - 1, (done[0] + i) / per);
                                float v = Math.max(Math.abs(x[2 * i]), Math.abs(x[2 * i + 1])) / 32768f;
                                if (v > pk[b]) {
                                    pk[b] = v;
                                }
                            }
                            done[0] += n;
                        }
                    });
                    root.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token) {
                                timeline.setPeaks(pk, "");
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** utile all'esterno: ripete il calcolo quando cambiano i suoni o il mix */
    void invalidateWave() {
        computeWave();
    }
}
