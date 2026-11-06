package it.mamotools;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Scheda "Mix": volumi, muto/solo di ogni traccia ed effetti generali. */
final class ProdMixTab implements ProdSession.Tab {

    private final ProdSession s;
    private final Context c;
    private final ScrollView root;
    private final ProdUi.Slider[] vols = new ProdUi.Slider[ProdModel.TRACKS];
    private final TextView[] mute = new TextView[ProdModel.TRACKS], solo = new TextView[ProdModel.TRACKS];
    private final TextView[] names = new TextView[ProdModel.TRACKS];
    private final ProdUi.Slider master, swing, echo, reverb, lofi;

    ProdMixTab(ProdSession session) {
        s = session;
        c = s.ctx;
        root = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 24));
        root.addView(col, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout tracks = Ui.card(c);
        tracks.addView(Ui.section(c, "Tracce"));
        for (int i = 0; i < ProdModel.TRACKS; i++) {
            final int t = i;
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(LinearLayout.VERTICAL);
            names[i] = Ui.text(c, "", 13, ProdModel.TRACK_COLORS[i], true);
            names[i].setSingleLine(true);
            left.addView(names[i]);
            vols[i] = new ProdUi.Slider(c, "", 0, 100, s.song.tracks[i].vol, ProdUi.PCT, new ProdUi.IntCb() {
                @Override
                public void on(int v) {
                    s.song.tracks[t].vol = v;
                    s.changed();
                }
            });
            left.addView(vols[i].view, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            r.addView(left, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            mute[i] = ProdUi.chip(c, "M", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.song.tracks[t].mute = !s.song.tracks[t].mute;
                    s.changed();
                    refresh();
                }
            });
            solo[i] = ProdUi.chip(c, "S", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.song.tracks[t].solo = !s.song.tracks[t].solo;
                    s.changed();
                    refresh();
                }
            });
            mute[i].setPadding(0, 0, 0, 0);
            solo[i].setPadding(0, 0, 0, 0);
            r.addView(mute[i], Ui.lp(c, Ui.dp(c, 40), Ui.dp(c, 38), 8, 0));
            r.addView(solo[i], Ui.lp(c, Ui.dp(c, 40), Ui.dp(c, 38), 6, 0));
            tracks.addView(r, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 6));
        }
        tracks.addView(Ui.text(c, "M = muto · S = solo (ascolti solo quella traccia)", 12, Ui.DIM, false),
                Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 8));
        col.addView(tracks, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 0));

        LinearLayout fx = Ui.card(c);
        fx.addView(Ui.section(c, "Generale ed effetti"));
        master = new ProdUi.Slider(c, "Volume generale", 0, 100, s.song.master, ProdUi.PCT, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                s.song.master = v;
                s.changed();
            }
        });
        swing = new ProdUi.Slider(c, "Swing (ritmo più “dondolante”)", 0, 100, s.song.swing, ProdUi.PCT,
                new ProdUi.IntCb() {
                    @Override
                    public void on(int v) {
                        s.song.swing = v;
                        s.changed();
                    }
                });
        echo = new ProdUi.Slider(c, "Eco", 0, 100, s.song.echo, ProdUi.PCT, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                s.song.echo = v;
                s.changed();
            }
        });
        reverb = new ProdUi.Slider(c, "Riverbero", 0, 100, s.song.reverb, ProdUi.PCT, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                s.song.reverb = v;
                s.changed();
            }
        });
        lofi = new ProdUi.Slider(c, "Lo-Fi (suono vintage: fruscio, nastro, bit ridotti)", 0, 100, s.song.lofi,
                ProdUi.PCT, new ProdUi.IntCb() {
                    @Override
                    public void on(int v) {
                        s.song.lofi = v;
                        s.changed();
                    }
                });
        ProdUi.Slider[] all = {master, swing, echo, reverb, lofi};
        for (ProdUi.Slider x : all) {
            fx.addView(x.view, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));
        }
        col.addView(fx, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));
        refresh();
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

    private void refresh() {
        for (int i = 0; i < ProdModel.TRACKS; i++) {
            ProdModel.Track t = s.song.tracks[i];
            names[i].setText((i + 1) + "  " + t.label());
            vols[i].set(t.vol);
            Ui.styleChip(c, mute[i], t.mute);
            ProdUi.colorChip(c, solo[i], t.solo, 0xFFFFD43B);
            if (t.mute) {
                mute[i].setBackground(Ui.round(c, Ui.DANGER, 12));
                mute[i].setTextColor(0xFF0F1115);
            }
        }
        master.set(s.song.master);
        swing.set(s.song.swing);
        echo.set(s.song.echo);
        reverb.set(s.song.reverb);
        lofi.set(s.song.lofi);
    }
}
