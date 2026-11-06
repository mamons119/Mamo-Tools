package it.mamotools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/** Scheda "Esporta": nome del brano, formato (WAV, FLAC, M4A), parte da esportare. */
final class ProdExportTab implements ProdSession.Tab {

    private static final String[] FMT = {"WAV", "FLAC", "M4A (AAC)"};
    private static final String[] EXT = {".wav", ".flac", ".m4a"};
    private static final String[] MIME = {"audio/x-wav", "audio/flac", "audio/mp4"};
    private static final int[] KBPS = {128, 192, 256};
    private static final int[] REPS = {1, 2, 4, 8};

    private final ProdSession s;
    private final Context c;
    private final android.app.Activity act;
    private final ScrollView root;
    private final EditText title;
    private final TextView[] fmtChips = new TextView[3], qChips = new TextView[3], scopeChips = new TextView[2],
            repChips = new TextView[4];
    private final LinearLayout qBox, repBox;
    private final TextView info, fmtInfo;
    private final android.widget.Button exportBtn, shareBtn;
    private final ResultCard result;
    private int fmt = 2, q = 1, scope = 0, reps = 1;
    private boolean busy = false;
    private Uri lastUri;
    private String lastMime;

    ProdExportTab(ProdSession session, android.app.Activity activity) {
        s = session;
        c = s.ctx;
        act = activity;
        root = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 24));
        root.addView(col, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout nameCard = Ui.card(c);
        nameCard.addView(Ui.section(c, "Nome del brano"));
        title = new EditText(c);
        title.setText(s.song.title);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setTextColor(Ui.TEXT);
        title.setHintTextColor(Ui.DIM);
        title.setHint("Nome della canzone");
        title.setBackground(Ui.round(c, Ui.CARD2, 12));
        title.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        title.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String t = e.toString().trim();
                s.song.title = t.isEmpty() ? "Senza titolo" : t;
                s.changed();
                updateInfo();
            }
        });
        nameCard.addView(title, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        col.addView(nameCard);

        LinearLayout fc = Ui.card(c);
        fc.addView(Ui.section(c, "Formato"));
        LinearLayout row = new LinearLayout(c);
        for (int i = 0; i < 3; i++) {
            final int f = i;
            fmtChips[i] = ProdUi.chip(c, FMT[i], false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fmt = f;
                    refresh();
                }
            });
            row.addView(fmtChips[i], Ui.lp(c, 0, Ui.dp(c, 42), i == 0 ? 0 : 8, 0, 1f));
        }
        fc.addView(row);
        fmtInfo = Ui.text(c, "", 12, Ui.DIM, false);
        fc.addView(fmtInfo, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8));
        qBox = new LinearLayout(c);
        qBox.setOrientation(LinearLayout.VERTICAL);
        qBox.addView(Ui.text(c, "Qualità", 13, Ui.DIM, false), Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 10));
        LinearLayout qr = new LinearLayout(c);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            qChips[i] = ProdUi.chip(c, KBPS[i] + " kbps", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    q = k;
                    refresh();
                }
            });
            qr.addView(qChips[i], Ui.lp(c, 0, Ui.dp(c, 38), i == 0 ? 0 : 8, 0, 1f));
        }
        qBox.addView(qr, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 6));
        fc.addView(qBox);
        col.addView(fc, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout sc = Ui.card(c);
        sc.addView(Ui.section(c, "Cosa esportare"));
        LinearLayout sr = new LinearLayout(c);
        String[] sn = {"Brano intero", "Solo un pattern"};
        for (int i = 0; i < 2; i++) {
            final int k = i;
            scopeChips[i] = ProdUi.chip(c, sn[i], false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    scope = k;
                    refresh();
                }
            });
            sr.addView(scopeChips[i], Ui.lp(c, 0, Ui.dp(c, 42), i == 0 ? 0 : 8, 0, 1f));
        }
        sc.addView(sr);
        repBox = new LinearLayout(c);
        repBox.setOrientation(LinearLayout.VERTICAL);
        repBox.addView(Ui.text(c, "Ripetizioni del pattern", 13, Ui.DIM, false), Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 10));
        LinearLayout rr = new LinearLayout(c);
        for (int i = 0; i < 4; i++) {
            final int k = i;
            repChips[i] = ProdUi.chip(c, REPS[i] + "×", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    reps = REPS[k];
                    refresh();
                }
            });
            rr.addView(repChips[i], Ui.lp(c, 0, Ui.dp(c, 38), i == 0 ? 0 : 8, 0, 1f));
        }
        repBox.addView(rr, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 6));
        sc.addView(repBox);
        info = Ui.text(c, "", 13, Ui.DIM, false);
        sc.addView(info, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 10));
        col.addView(sc, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));

        exportBtn = Ui.btn(c, "Esporta", Ui.ACCENT, 0xFF0F1115, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export();
            }
        });
        exportBtn.setTextSize(16);
        col.addView(exportBtn, Ui.lp(c, Ui.MATCH, Ui.dp(c, 52), 0, 16));
        result = new ResultCard(act);
        col.addView(result.view, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 12));
        shareBtn = Ui.btn(c, "Condividi il file", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        shareBtn.setVisibility(View.GONE);
        col.addView(shareBtn, Ui.lp(c, Ui.MATCH, Ui.dp(c, 46), 0, 8));
        refresh();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        if (!title.getText().toString().equals(s.song.title)) {
            title.setText(s.song.title);
        }
        refresh();
    }

    @Override
    public void tick(int[] pos) {
    }

    private void refresh() {
        for (int i = 0; i < 3; i++) {
            Ui.styleChip(c, fmtChips[i], i == fmt);
            Ui.styleChip(c, qChips[i], i == q);
        }
        for (int i = 0; i < 2; i++) {
            Ui.styleChip(c, scopeChips[i], i == scope);
        }
        for (int i = 0; i < 4; i++) {
            Ui.styleChip(c, repChips[i], REPS[i] == reps);
        }
        qBox.setVisibility(fmt == 2 ? View.VISIBLE : View.GONE);
        repBox.setVisibility(scope == 1 ? View.VISIBLE : View.GONE);
        fmtInfo.setText(fmt == 0 ? "Senza compressione: qualità massima, file grande."
                : fmt == 1 ? "Compresso senza perdita di qualità: circa metà di un WAV."
                : "Compresso, leggero e leggibile da tutti i telefoni e programmi. "
                + "L'MP3 non è disponibile perché Android non include un codificatore MP3.");
        updateInfo();
    }

    private void updateInfo() {
        double sec;
        String what;
        if (scope == 0) {
            long steps = s.song.songSteps();
            sec = ProdEngine.framesFor(s.song, steps) / (double) ProdEngine.RATE;
            what = steps == 0 ? "Il brano è vuoto: aggiungi dei blocchi nella scheda Brano." : "Brano intero";
        } else {
            long steps = (long) s.song.patterns[s.curPattern].steps * reps;
            sec = ProdEngine.framesFor(s.song, steps) / (double) ProdEngine.RATE;
            what = "Pattern " + ProdUi.letter(s.curPattern) + " × " + reps;
        }
        info.setText(String.format(Locale.ITALY, "%s · circa %s (compresa la coda di eco e riverbero) · file: %s%s",
                what, ProdUi.time(sec), s.song.title, EXT[fmt]));
    }

    private String fileName() {
        String n = s.song.title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return (n.isEmpty() ? "Brano" : n) + EXT[fmt];
    }

    private void export() {
        if (busy) {
            return;
        }
        if (scope == 0 && s.song.songSteps() == 0) {
            Toast.makeText(c, "Il brano è vuoto: aggiungi dei blocchi nella scheda Brano.", Toast.LENGTH_LONG).show();
            return;
        }
        if (scope == 1 && s.song.patterns[s.curPattern].isEmpty()) {
            Toast.makeText(c, "Il pattern " + ProdUi.letter(s.curPattern) + " è vuoto.", Toast.LENGTH_LONG).show();
            return;
        }
        s.player.stop();
        s.saveNow();
        busy = true;
        exportBtn.setEnabled(false);
        shareBtn.setVisibility(View.GONE);
        final ProdModel.Song copy = s.song.copy();
        final int f = fmt, kbps = KBPS[q], sc = scope, rp = reps, pat = s.curPattern;
        final String name = fileName();
        result.start("Esportazione in corso...");
        result.progress("Preparazione...", 0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ProdFiles.Progress pr = new ProdFiles.Progress() {
                    @Override
                    public void onProgress(int pct) {
                        result.progress("Esportazione " + pct + "%", pct);
                    }
                };
                try {
                    final ProdEngine e = new ProdEngine(copy, s.store);
                    if (sc == 0) {
                        e.startExport(ProdEngine.MODE_SONG, 0, 1);
                    } else {
                        e.startExport(ProdEngine.MODE_PATTERN, pat, rp);
                    }
                    final long frames = e.exportFrames(e.exportSteps());
                    Uri u;
                    if (f == 2) {
                        u = FileUtil.saveFd(c, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/MamoTools", name,
                                MIME[f], new FileUtil.FdWriter() {
                                    @Override
                                    public void write(FileDescriptor fd) throws IOException {
                                        ProdM4a.write(e, frames, fd, kbps, pr);
                                    }
                                });
                    } else {
                        final boolean flac = f == 1;
                        u = FileUtil.save(c, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/MamoTools", name,
                                MIME[f], new FileUtil.Writer() {
                                    @Override
                                    public void write(OutputStream os) throws IOException {
                                        if (flac) {
                                            ProdFiles.writeFlac(e, frames, os, pr);
                                        } else {
                                            ProdFiles.writeWav(e, frames, os, pr);
                                        }
                                    }
                                });
                    }
                    lastUri = u;
                    lastMime = MIME[f];
                    result.ok("Fatto! Salvato in Musica/MamoTools/" + name, u, MIME[f], "Ascolta il file");
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            shareBtn.setVisibility(View.VISIBLE);
                        }
                    });
                } catch (Throwable t) {
                    String m = t.getMessage();
                    result.error("Esportazione non riuscita" + (m != null && !m.isEmpty() ? ": " + m : "."));
                } finally {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            exportBtn.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private void share() {
        if (lastUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(lastMime);
        i.putExtra(Intent.EXTRA_STREAM, lastUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(i, "Condividi il brano"));
    }
}
