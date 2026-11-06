package it.mamotools;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Scheda "Pattern": 16 pad per passo, scelta della traccia e delle note. */
final class ProdPatternTab implements ProdSession.Tab {

    private final ProdSession s;
    private final Context c;
    private final ScrollView root;
    private final LinearLayout patRow, trackRow, noteRow, noteBox, pageRow, scaleRow;
    private final TextView lenChip, rootChip, scaleChip, infoTv;
    private final HorizontalScrollView noteScroll;
    private final PadGridView grid;
    private final ProdOverviewView overview;
    private TextView[] patChips = new TextView[ProdModel.PATTERNS];
    private TextView[] trackChips = new TextView[ProdModel.TRACKS];
    private TextView[] pageChips = new TextView[2];
    private int page = 0;

    ProdPatternTab(ProdSession session) {
        s = session;
        c = s.ctx;
        root = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 20));
        root.addView(col, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        // pattern A..H
        patRow = new LinearLayout(c);
        for (int i = 0; i < ProdModel.PATTERNS; i++) {
            final int p = i;
            TextView t = ProdUi.chip(c, ProdUi.letter(i), false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.selectPattern(p);
                    page = 0;
                    refresh();
                }
            });
            t.setPadding(0, 0, 0, 0);
            patChips[i] = t;
            patRow.addView(t, Ui.lp(c, 0, Ui.dp(c, 40), i == 0 ? 0 : 6, 0, 1f));
        }
        col.addView(patRow);

        // strumenti del pattern
        LinearLayout tools = new LinearLayout(c);
        lenChip = ProdUi.chip(c, "16 passi", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProdModel.Pattern p = s.song.patterns[s.curPattern];
                p.steps = p.steps == 16 ? 32 : 16;
                page = 0;
                s.changed();
                refresh();
            }
        });
        tools.addView(lenChip, Ui.lp(c, 0, Ui.dp(c, 36), 0, 0, 1f));
        TextView copy = ProdUi.chip(c, "Copia in…", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTo();
            }
        });
        tools.addView(copy, Ui.lp(c, 0, Ui.dp(c, 36), 6, 0, 1f));
        TextView clear = ProdUi.chip(c, "Svuota", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearPattern();
            }
        });
        tools.addView(clear, Ui.lp(c, 0, Ui.dp(c, 36), 6, 0, 1f));
        col.addView(tools, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));

        // tracce
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        trackRow = new LinearLayout(c);
        for (int i = 0; i < ProdModel.TRACKS; i++) {
            final int t = i;
            TextView tv = ProdUi.chip(c, "", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.curTrack = t;
                    refresh();
                    s.player.preview(t, previewNote(t));
                }
            });
            trackChips[i] = tv;
            trackRow.addView(tv, Ui.lp(c, Ui.WRAP, Ui.dp(c, 38), i == 0 ? 0 : 6, 0));
        }
        hs.addView(trackRow);
        col.addView(hs, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 10));

        // griglia
        grid = new PadGridView(c);
        grid.setListener(new PadGridView.Listener() {
            @Override
            public void onPad(int index) {
                tapPad(index);
            }
        });
        int maxW = (int) Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 24),
                Ui.dp(c, 420));
        col.addView(grid, Ui.lp(c, maxW, Ui.WRAP, 0, 10));
        ((LinearLayout.LayoutParams) grid.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;

        pageRow = new LinearLayout(c);
        pageRow.setGravity(Gravity.CENTER);
        String[] pn = {"Passi 1–16", "Passi 17–32"};
        for (int i = 0; i < 2; i++) {
            final int pg = i;
            pageChips[i] = ProdUi.chip(c, pn[i], false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    page = pg;
                    refresh();
                }
            });
            pageRow.addView(pageChips[i], Ui.lp(c, Ui.WRAP, Ui.dp(c, 36), i == 0 ? 0 : 8, 0));
        }
        col.addView(pageRow, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));

        // note (solo strumenti melodici)
        noteBox = new LinearLayout(c);
        noteBox.setOrientation(LinearLayout.VERTICAL);
        infoTv = Ui.text(c, "", 12, Ui.DIM, false);
        noteBox.addView(infoTv);
        noteScroll = new HorizontalScrollView(c);
        noteScroll.setHorizontalScrollBarEnabled(false);
        noteRow = new LinearLayout(c);
        noteScroll.addView(noteRow);
        noteBox.addView(noteScroll, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 6));
        scaleRow = new LinearLayout(c);
        rootChip = ProdUi.chip(c, "", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProdUi.pickList(c, "Nota di partenza", ProdModel.NOTE_NAMES, new ProdUi.IntCb() {
                    @Override
                    public void on(int v) {
                        s.song.root = v;
                        s.changed();
                        refresh();
                    }
                });
            }
        });
        scaleChip = ProdUi.chip(c, "", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProdUi.pickList(c, "Scala", ProdModel.SCALE_NAMES, new ProdUi.IntCb() {
                    @Override
                    public void on(int v) {
                        s.song.scale = v;
                        s.changed();
                        refresh();
                    }
                });
            }
        });
        scaleRow.addView(rootChip, Ui.lp(c, Ui.WRAP, Ui.dp(c, 36), 0, 0));
        scaleRow.addView(scaleChip, Ui.lp(c, Ui.WRAP, Ui.dp(c, 36), 8, 0));
        noteBox.addView(scaleRow, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));
        col.addView(noteBox, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 4));

        // panoramica
        col.addView(Ui.text(c, "Tutte le tracce", 12, Ui.DIM, false), Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 14));
        overview = new ProdOverviewView(c);
        overview.setListener(new ProdOverviewView.Listener() {
            @Override
            public void onTrack(int track) {
                s.curTrack = track;
                refresh();
            }
        });
        col.addView(overview, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 6));
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

    private int previewNote(int t) {
        ProdModel.Track tr = s.song.tracks[t];
        return tr.melodic() ? s.curNote(tr) : 60;
    }

    private void tapPad(int index) {
        ProdModel.Pattern p = s.song.patterns[s.curPattern];
        int step = page * 16 + index;
        if (step >= p.steps) {
            return;
        }
        ProdModel.Track tr = s.song.tracks[s.curTrack];
        int note = tr.melodic() ? s.curNote(tr) : 60;
        int cur = p.note[s.curTrack][step];
        if (cur == note) {
            p.note[s.curTrack][step] = ProdModel.OFF;
        } else {
            p.note[s.curTrack][step] = note;
            s.player.preview(s.curTrack, note);
        }
        s.changed();
        refresh();
    }

    private void copyTo() {
        String[] names = new String[ProdModel.PATTERNS];
        for (int i = 0; i < names.length; i++) {
            names[i] = "Pattern " + ProdUi.letter(i);
        }
        ProdUi.pickList(c, "Copia il pattern " + ProdUi.letter(s.curPattern) + " in…", names, new ProdUi.IntCb() {
            @Override
            public void on(int v) {
                if (v != s.curPattern) {
                    s.song.patterns[v].copyFrom(s.song.patterns[s.curPattern]);
                    s.changed();
                    refresh();
                }
            }
        });
    }

    private void clearPattern() {
        new AlertDialog.Builder(c).setTitle("Svuotare il pattern " + ProdUi.letter(s.curPattern) + "?")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Svuota", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        s.song.patterns[s.curPattern].clear();
                        s.changed();
                        refresh();
                    }
                }).show();
    }

    private void refresh() {
        ProdModel.Song song = s.song;
        ProdModel.Pattern pat = song.patterns[s.curPattern];
        if (page == 1 && pat.steps < 32) {
            page = 0;
        }
        for (int i = 0; i < ProdModel.PATTERNS; i++) {
            boolean sel = i == s.curPattern;
            Ui.styleChip(c, patChips[i], sel);
            if (!sel) {
                patChips[i].setTextColor(song.patterns[i].isEmpty() ? Ui.DIM : Ui.TEXT);
            }
        }
        lenChip.setText(pat.steps + " passi");
        Ui.styleChip(c, lenChip, false);
        for (int i = 0; i < ProdModel.TRACKS; i++) {
            ProdModel.Track t = song.tracks[i];
            String name = (i + 1) + " " + t.label();
            if (name.length() > 16) {
                name = name.substring(0, 15) + "…";
            }
            trackChips[i].setText(name);
            ProdUi.colorChip(c, trackChips[i], i == s.curTrack, ProdModel.TRACK_COLORS[i]);
            if (i != s.curTrack) {
                trackChips[i].setTextColor(t.mute ? Ui.DIM : ProdModel.TRACK_COLORS[i]);
            }
        }
        ProdModel.Track tr = song.tracks[s.curTrack];
        int[] n = new int[16];
        String[] lab = new String[16];
        for (int i = 0; i < 16; i++) {
            int st = page * 16 + i;
            n[i] = st < pat.steps ? pat.note[s.curTrack][st] : ProdModel.OFF;
            if (n[i] != ProdModel.OFF && tr.melodic()) {
                lab[i] = ProdModel.noteName(n[i]);
            }
        }
        grid.set(n, lab, ProdModel.TRACK_COLORS[s.curTrack], page * 16);
        pageRow.setVisibility(pat.steps > 16 ? View.VISIBLE : View.GONE);
        Ui.styleChip(c, pageChips[0], page == 0);
        Ui.styleChip(c, pageChips[1], page == 1);

        noteBox.setVisibility(tr.melodic() ? View.VISIBLE : View.GONE);
        if (tr.melodic()) {
            buildNotes(tr);
        }
        overview.set(pat, song.tracks, s.curTrack);
    }

    private void buildNotes(final ProdModel.Track tr) {
        int cur = s.curNote(tr);
        infoTv.setText("Nota da inserire: " + ProdModel.noteName(cur) + " — scegli la nota, poi tocca i pad.");
        rootChip.setText("Tonalità " + ProdModel.NOTE_NAMES[s.song.root]);
        scaleChip.setText(ProdModel.SCALE_NAMES[s.song.scale]);
        noteRow.removeAllViews();
        int base = ProdModel.BASE_NOTE[ProdModel.soundIndex(tr.sound)];
        int lo = Math.max(12, base - 12), hi = Math.min(108, base + 24);
        final int color = ProdModel.TRACK_COLORS[s.curTrack];
        TextView selected = null;
        for (int m = lo; m <= hi; m++) {
            if (!s.inScale(m)) {
                continue;
            }
            final int midi = m;
            TextView t = ProdUi.chip(c, ProdModel.noteName(m), false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tr.lastNote = midi;
                    s.changed();
                    s.player.preview(s.curTrack, midi);
                    refresh();
                }
            });
            boolean sel = m == cur;
            ProdUi.colorChip(c, t, sel, color);
            if (!sel && (((m - s.song.root) % 12 + 12) % 12) == 0) {
                t.setTextColor(color);
            }
            noteRow.addView(t, Ui.lp(c, Ui.dp(c, 52), Ui.dp(c, 40), noteRow.getChildCount() == 0 ? 0 : 6, 0));
            if (sel) {
                selected = t;
            }
        }
        final TextView sel = selected;
        if (sel != null) {
            noteScroll.post(new Runnable() {
                @Override
                public void run() {
                    noteScroll.scrollTo(Math.max(0, sel.getLeft() - Ui.dp(c, 100)), 0);
                }
            });
        }
    }

    @Override
    public void tick(int[] pos) {
        if (pos == null) {
            grid.setPlayhead(-1);
            overview.setHead(-1);
            return;
        }
        if (pos[1] != s.curPattern) {
            grid.setPlayhead(-1);
            overview.setHead(-1);
            return;
        }
        int idx = pos[2] - page * 16;
        grid.setPlayhead(idx >= 0 && idx < 16 ? idx : -1);
        overview.setHead(pos[2]);
    }
}
