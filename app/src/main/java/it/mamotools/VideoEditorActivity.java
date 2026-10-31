package it.mamotools;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
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

    // viste
    private VideoView videoView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(40), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        root.addView(text("Video Editor", 28, R.color.text, true));
        TextView sub = text("Scegli un video dalla Galleria, taglia le parti che non vuoi, "
                + "gestisci l'audio e salva.", 14, R.color.text_dim, false);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        Button pick = button("Scegli un video", v -> pickVideo());
        root.addView(pick, new LinearLayout.LayoutParams(MATCH, WRAP));

        editorBox = new LinearLayout(this);
        editorBox.setOrientation(LinearLayout.VERTICAL);
        editorBox.setVisibility(View.GONE);
        root.addView(editorBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- anteprima
        FrameLayout vf = new FrameLayout(this);
        vf.setBackgroundColor(Color.BLACK);
        videoView = new VideoView(this);
        vf.addView(videoView, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(MATCH, dp(240));
        vlp.topMargin = dp(16);
        editorBox.addView(vf, vlp);
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

        // ---- controlli di riproduzione
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = button("▶", v -> togglePlay());
        ctl.addView(playBtn, new LinearLayout.LayoutParams(dp(64), WRAP));
        ctl.addView(button("-1s", v -> seekRel(-1000)), new LinearLayout.LayoutParams(dp(60), WRAP));
        ctl.addView(button("+1s", v -> seekRel(1000)), new LinearLayout.LayoutParams(dp(60), WRAP));
        timeText = text("0:00 / 0:00", 14, R.color.text, false);
        timeText.setGravity(Gravity.END);
        ctl.addView(timeText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        editorBox.addView(ctl, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- timeline
        timeline = new Timeline(this);
        timeline.setOnSeek(ms -> {
            videoView.seekTo((int) ms);
            updateTexts();
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, dp(64));
        tlp.topMargin = dp(8);
        editorBox.addView(timeline, tlp);

        markText = text("", 13, R.color.text_dim, false);
        markText.setPadding(0, dp(8), 0, 0);
        editorBox.addView(markText);

        LinearLayout cutRow1 = new LinearLayout(this);
        cutRow1.addView(button("[ Inizio", v -> {
            markA = timeline.pos;
            if (markB >= 0 && markB <= markA) {
                markB = -1;
            }
            refreshMarks();
        }), new LinearLayout.LayoutParams(0, WRAP, 1f));
        cutRow1.addView(button("Fine ]", v -> {
            markB = timeline.pos;
            if (markA >= 0 && markA >= markB) {
                markA = -1;
            }
            refreshMarks();
        }), new LinearLayout.LayoutParams(0, WRAP, 1f));
        editorBox.addView(cutRow1, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout cutRow2 = new LinearLayout(this);
        cutRow2.addView(button("✂ Taglia via la selezione", v -> addCut()),
                new LinearLayout.LayoutParams(0, WRAP, 1.4f));
        cutRow2.addView(button("Annulla taglio", v -> {
            if (!cuts.isEmpty()) {
                cuts.remove(cuts.size() - 1);
                refreshMarks();
            }
        }), new LinearLayout.LayoutParams(0, WRAP, 1f));
        editorBox.addView(cutRow2, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- audio
        editorBox.addView(sectionTitle("Audio"));
        Switch sw = new Switch(this);
        sw.setText("Mantieni l'audio originale");
        sw.setTextColor(getColor(R.color.text));
        sw.setChecked(true);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            keepAudio = on;
            if (videoPlayer != null) {
                videoPlayer.setVolume(on ? 1f : 0f, on ? 1f : 0f);
            }
            updateModeAvailability();
        });
        editorBox.addView(sw, new LinearLayout.LayoutParams(MATCH, WRAP));

        editorBox.addView(sectionTitle("Colonna sonora"));
        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setGravity(Gravity.CENTER_VERTICAL);
        soundRow.addView(button("Scegli audio", v -> pickSound()),
                new LinearLayout.LayoutParams(WRAP, WRAP));
        Button clear = button("Rimuovi", v -> clearSound());
        soundRow.addView(clear, new LinearLayout.LayoutParams(WRAP, WRAP));
        soundName = text("Nessuna", 13, R.color.text_dim, false);
        soundName.setPadding(dp(8), 0, 0, 0);
        soundRow.addView(soundName, new LinearLayout.LayoutParams(0, WRAP, 1f));
        editorBox.addView(soundRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        soundBox = new LinearLayout(this);
        soundBox.setOrientation(LinearLayout.VERTICAL);
        soundBox.setVisibility(View.GONE);
        editorBox.addView(soundBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        soundStartText = text("", 14, R.color.text, false);
        soundStartText.setPadding(0, dp(8), 0, 0);
        soundBox.addView(soundStartText);
        soundStartBar = new SeekBar(this);
        soundStartBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                soundStartMs = p * 100L;
                updateTexts();
            }
        });
        soundBox.addView(soundStartBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        soundTestBtn = button("▶ Ascolta da questo punto", v -> toggleSoundTest());
        soundBox.addView(soundTestBtn, new LinearLayout.LayoutParams(MATCH, WRAP));

        modeGroup = new RadioGroup(this);
        RadioButton replace = new RadioButton(this);
        replace.setId(2001);
        replace.setText("Sostituisci l'audio originale");
        replace.setTextColor(getColor(R.color.text));
        modeMix = new RadioButton(this);
        modeMix.setId(2002);
        modeMix.setText("Aggiungi all'audio originale (abbassato)");
        modeMix.setTextColor(getColor(R.color.text));
        modeGroup.addView(replace);
        modeGroup.addView(modeMix);
        modeGroup.check(2001);
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            mix = id == 2002;
            updateModeAvailability();
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        mlp.topMargin = dp(8);
        soundBox.addView(modeGroup, mlp);

        dbText = text("", 14, R.color.text, false);
        soundBox.addView(dbText);
        dbBar = new SeekBar(this);
        dbBar.setMax(27);          // 3..30 dB
        dbBar.setProgress(dbDown - 3);
        dbBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                dbDown = p + 3;
                updateTexts();
            }
        });
        soundBox.addView(dbBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- salvataggio
        exportBtn = button("Salva il video", v -> export());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(MATCH, WRAP);
        elp.topMargin = dp(20);
        editorBox.addView(exportBtn, elp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        editorBox.addView(progress, new LinearLayout.LayoutParams(MATCH, WRAP));

        status = text("", 14, R.color.text, false);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        openBtn = button("Apri il video salvato", v -> openResult());
        openBtn.setVisibility(View.GONE);
        root.addView(openBtn, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView note = text("I tagli non ricodificano il video, quindi la qualita' resta identica "
                + "e il salvataggio e' veloce; per questo il punto di taglio puo' slittare "
                + "all'indietro fino al fotogramma chiave precedente (di solito meno di 1 secondo). "
                + "La colonna sonora piu' corta del video lascia il resto in silenzio (o solo "
                + "l'audio originale se lo mantieni). Le parti tagliate in anteprima vengono "
                + "saltate.", 12, R.color.text_dim, false);
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);

        setContentView(scroll);
        updateTexts();
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

    private void loadVideo(Uri uri) {
        videoUri = uri;
        videoName = queryName(uri);
        resultUri = null;
        openBtn.setVisibility(View.GONE);
        status.setText("");
        cuts.clear();
        markA = markB = -1;
        durMs = 0;
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(this, uri);
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
            status.setText("Questo video non sembra leggibile dal telefono.");
            editorBox.setVisibility(View.GONE);
            return;
        }
        editorBox.setVisibility(View.VISIBLE);
        timeline.setDuration(durMs);
        timeline.setPos(0);
        videoView.setVideoURI(uri);
        videoView.seekTo(1);
        playBtn.setText("▶");
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        timeline.loadThumbs(this, uri);
        refreshMarks();
        status.setText(videoName);
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
                videoView.seekTo(0);
            }
            videoView.start();
            playBtn.setText("❚❚");
        }
    }

    private void seekRel(long delta) {
        long t = Math.max(0, Math.min(durMs, timeline.pos + delta));
        videoView.seekTo((int) t);
        timeline.setPos(t);
        updateTexts();
    }

    /** Aggiorna il cursore e salta le parti tagliate durante la riproduzione. */
    private volatile boolean skipping = false;

    private void tick() {
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
                    videoView.seekTo((int) p);
                } else {
                    p = c[1];
                    seekExact(p);
                }
                break;
            }
        }
        timeline.setPos(p);
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
            soundTestBtn.setText("■ Ferma");
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
            soundTestBtn.setText("▶ Ascolta da questo punto");
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
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);

        final VideoProcessor.Options o = new VideoProcessor.Options();
        o.video = videoUri;
        o.keep = keptSegmentsUs();
        o.keepAudio = keepAudio;
        o.sound = soundUri;
        o.soundStartUs = soundStartMs * 1000;
        o.mix = mix && keepAudio;
        o.originalDb = -dbDown;

        new Thread(new Runnable() {
            @Override
            public void run() {
                ContentResolver cr = getContentResolver();
                Uri uri = null;
                ParcelFileDescriptor pfd = null;
                boolean ok = false;
                try {
                    String name = "MamoEdit_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                            Locale.US).format(new Date()) + ".mp4";
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
                    status.setText("Fatto! Salvato in Movies/MamoTools (lo trovi in Galleria).");
                } else {
                    status.setText(error);
                }
            }
        });
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
        TextView t = text(s, 16, R.color.accent, true);
        t.setPadding(0, dp(20), 0, dp(4));
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

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            int n = 10;
            synchronized (thumbs) {
                for (int i = 0; i < thumbs.size() && i < n; i++) {
                    Bitmap b = thumbs.get(i);
                    if (b == null) {
                        continue;
                    }
                    int x0 = w * i / n;
                    int x1 = w * (i + 1) / n;
                    // ritaglio centrale per riempire la cella
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
                    c.drawBitmap(b, src, dst, null);
                }
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
