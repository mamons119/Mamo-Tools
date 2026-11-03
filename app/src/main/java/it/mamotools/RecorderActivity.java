package it.mamotools;

import android.content.Intent;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Registra una sessione di guida (GPS + sensori, 10 righe al secondo) da rivedere con mappa e grafici. */
public class RecorderActivity extends TelemBase {

    private boolean recording = false;
    private long startElapsedMs, lastRowMs, lastLapMs = -100000;
    private long startEpoch;
    private File file;
    private BufferedWriter out;
    private ExecutorService io;
    private GpsSource.Fix fix, prevFix;
    private double dist, vMax;
    private float maxLeanL, maxLeanR, maxG;
    private double[] gate; // lat, lon, direzione
    private int lapCount = 0;
    private final List<Double> lapTimes = new ArrayList<>();
    private double lapStartS = -1;

    private Button startBtn, lineBtn, openBtn;
    private TextView tTime, tSpeed, tDist, tVmax, tLean, tG, lapsTv, infoTv;

    @Override
    protected String title() {
        return "Registratore";
    }

    @Override
    protected String subtitle() {
        return "Registra percorso, velocita', forze G e piega per rivederli dopo su mappa e grafici.";
    }

    @Override
    protected void build(LinearLayout root) {
        LinearLayout c = Ui.card(this);
        startBtn = Ui.btn(this, "●  Avvia registrazione", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) {
                    stopRecording(true);
                } else {
                    startRecording();
                }
            }
        });
        startBtn.setTextSize(16);
        c.addView(startBtn, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 56)));
        infoTv = Ui.text(this, "Tieni lo schermo acceso e il telefono fissato: il GPS non registra con lo "
                + "schermo spento o l'app in secondo piano.", 12, Ui.DIM, false);
        infoTv.setPadding(0, Ui.dp(this, 10), 0, 0);
        c.addView(infoTv);
        root.addView(c);

        LinearLayout g1 = new LinearLayout(this);
        tTime = stat(g1, "Tempo", 0);
        tSpeed = stat(g1, "km/h", 8);
        root.addView(g1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        LinearLayout g2 = new LinearLayout(this);
        tDist = stat(g2, "Distanza", 0);
        tVmax = stat(g2, "Max km/h", 8);
        root.addView(g2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        LinearLayout g3 = new LinearLayout(this);
        tLean = stat(g3, "Piega max SX / DX", 0);
        tG = stat(g3, "G max", 8);
        root.addView(g3, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));

        LinearLayout lc = Ui.card(this);
        lc.addView(Ui.section(this, "Giri"));
        lineBtn = Ui.btn(this, "⚑  Imposta il traguardo qui", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setGate();
            }
        });
        lc.addView(lineBtn, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 46)));
        lapsTv = Ui.text(this, "Premi il pulsante mentre passi sul traguardo, nel verso di marcia: da "
                + "quel punto i giri vengono contati da soli.", 13, Ui.DIM, false);
        lapsTv.setPadding(0, Ui.dp(this, 10), 0, 0);
        lc.addView(lapsTv);
        root.addView(lc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        openBtn = Ui.btn(this, "Apri la sessione appena registrata", Ui.CARD2, Ui.ACCENT, null);
        openBtn.setVisibility(View.GONE);
        root.addView(openBtn, Ui.lp(this, Ui.MATCH, Ui.dp(this, 48), 0, 12));
        root.addView(Ui.btn(this, "Sessioni salvate", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(RecorderActivity.this, SessionsActivity.class));
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 48), 0, 8));
        lineBtn.setEnabled(false);
    }

    private TextView stat(LinearLayout row, String label, int left) {
        LinearLayout c = Ui.card(this);
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 12));
        TextView l = Ui.text(this, label, 11, Ui.DIM, false);
        TextView v = Ui.text(this, "—", 20, Ui.TEXT, true);
        c.addView(l);
        c.addView(v);
        row.addView(c, Ui.lp(this, 0, Ui.WRAP, left, 0, 1f));
        return v;
    }

    @Override
    protected boolean shouldStopOnPause() {
        return !recording;
    }

    // ------------------------------------------------------------------ registrazione

    private void startRecording() {
        if (!hasPerm() || !gps.enabled()) {
            Toast.makeText(this, "Prima concedi il permesso e attiva il GPS (vedi Stato dei sensori).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File dir = new File(getFilesDir(), "sessions");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        startEpoch = System.currentTimeMillis();
        file = new File(dir, "s_" + startEpoch + ".csv");
        try {
            out = new BufferedWriter(new FileWriter(file), 32 * 1024);
            String name = "Sessione " + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
                    .format(new Date(startEpoch));
            out.write(SessionData.header(startEpoch, name) + "\n");
        } catch (IOException e) {
            Toast.makeText(this, "Impossibile creare il file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        io = Executors.newSingleThreadExecutor();
        startElapsedMs = SystemClock.elapsedRealtime();
        lastRowMs = 0;
        dist = 0;
        vMax = 0;
        maxLeanL = maxLeanR = maxG = 0;
        gate = null;
        lapCount = 0;
        lapTimes.clear();
        lapStartS = -1;
        prevFix = fix = null;
        recording = true;
        startBtn.setText("■  Ferma e salva");
        startBtn.setBackground(Ui.round(this, Ui.DANGER, 12));
        openBtn.setVisibility(View.GONE);
        lineBtn.setEnabled(true);
        lapsTv.setText("Premi il pulsante mentre passi sul traguardo, nel verso di marcia.");
    }

    private void stopRecording(boolean notify) {
        if (!recording) {
            return;
        }
        recording = false;
        final BufferedWriter w = out;
        final File f = file;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    w.flush();
                    w.close();
                } catch (IOException ignored) {
                }
            }
        });
        io.shutdown();
        startBtn.setText("●  Avvia registrazione");
        startBtn.setBackground(Ui.round(this, Ui.ACCENT, 12));
        lineBtn.setEnabled(false);
        if (notify) {
            openBtn.setVisibility(View.VISIBLE);
            openBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(RecorderActivity.this, SessionViewActivity.class)
                            .putExtra("path", f.getAbsolutePath()));
                }
            });
            Toast.makeText(this, "Sessione salvata", Toast.LENGTH_SHORT).show();
        }
        if (!isFinishing()) {
            // rilascia i sensori se non siamo piu' in primo piano
        }
    }

    private void write(final String s) {
        final BufferedWriter w = out;
        if (io == null || w == null) {
            return;
        }
        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        w.write(s);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    private long sessionMs(long tNs) {
        return tNs / 1_000_000L - startElapsedMs;
    }

    private void setGate() {
        if (fix == null) {
            return;
        }
        float brg = fix.speed > 2f ? fix.bearing : 0;
        gate = new double[]{fix.lat, fix.lon, brg};
        write(String.format(Locale.US, "#LINE,%.7f,%.7f,%.1f\n", fix.lat, fix.lon, brg));
        lapCount = 0;
        lapTimes.clear();
        lapStartS = -1;
        lastLapMs = -100000;
        lapsTv.setText(fix.speed > 2f ? "Traguardo impostato: i giri partono dal prossimo passaggio."
                : "Traguardo impostato (muoviti per dare la direzione: meglio impostarlo in marcia).");
    }

    @Override
    protected void onFix(GpsSource.Fix f) {
        prevFix = fix;
        fix = f;
        if (!recording) {
            tSpeed.setText(String.valueOf(Math.round(f.speed * 3.6)));
            return;
        }
        if (prevFix != null) {
            double dt = (f.tNs - prevFix.tNs) / 1e9;
            if (dt > 0 && dt < 5 && f.speed > 0.5) {
                dist += f.speed * dt;
            }
        }
        vMax = Math.max(vMax, f.speed);
        if (gate != null && prevFix != null && f.speed > 2f) {
            double s = SessionData.crossFraction(gate[0], gate[1], gate[2], 35, prevFix.lat, prevFix.lon, f.lat, f.lon);
            if (s >= 0) {
                long tc = sessionMs(prevFix.tNs + (long) (s * (f.tNs - prevFix.tNs)));
                if (tc - lastLapMs > 8000) {
                    lastLapMs = tc;
                    write("#LAP," + tc + "," + lapCount + "\n");
                    double ts = tc / 1000.0;
                    if (lapStartS >= 0) {
                        lapTimes.add(ts - lapStartS);
                    }
                    lapStartS = ts;
                    lapCount++;
                    showLaps();
                }
            }
        }
    }

    private void showLaps() {
        StringBuilder sb = new StringBuilder();
        double best = Double.MAX_VALUE;
        for (double t : lapTimes) {
            best = Math.min(best, t);
        }
        for (int i = 0; i < lapTimes.size(); i++) {
            double t = lapTimes.get(i);
            sb.append("Giro ").append(i + 1).append(":  ").append(SessionData.fmtTime(t))
                    .append(t == best ? "   ★ migliore" : "").append("\n");
        }
        if (lapTimes.isEmpty()) {
            sb.append("Primo passaggio rilevato: il giro 1 e' in corso…");
        }
        lapsTv.setText(sb.toString().trim());
    }

    @Override
    protected void onTick() {
        if (fix != null && !recording) {
            return;
        }
        if (!recording) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long el = now - startElapsedMs;
        tTime.setText(SessionData.fmtTime(el / 1000.0));
        if (fix != null) {
            tSpeed.setText(String.valueOf(Math.round(fix.speed * 3.6)));
        }
        tDist.setText(dist < 1000 ? String.format(Locale.ITALY, "%.0f m", dist)
                : String.format(Locale.ITALY, "%.2f km", dist / 1000));
        tVmax.setText(String.valueOf(Math.round(vMax * 3.6)));
        float lean = imu.leanDeg;
        if (lean > 0) {
            maxLeanL = Math.max(maxLeanL, lean);
        } else {
            maxLeanR = Math.max(maxLeanR, -lean);
        }
        maxG = Math.max(maxG, (float) Math.hypot(imu.aLong, imu.aLat));
        tLean.setText(String.format(Locale.ITALY, "%.0f° / %.0f°", maxLeanL, maxLeanR));
        tG.setText(String.format(Locale.ITALY, "%.2f", maxG));
        lineBtn.setEnabled(fix != null);

        if (fix != null && gps.hasRecentFix() && now - lastRowMs >= 100) {
            lastRowMs = now;
            write(String.format(Locale.US, "%d,%.7f,%.7f,%.1f,%.2f,%.1f,%.1f,%.3f,%.3f,%.1f,%.1f\n",
                    el, fix.lat, fix.lon, fix.alt, fix.speed, fix.bearing, fix.acc,
                    imu.aLong, imu.aLat, imu.leanDeg, imu.yawDegS));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recording) {
            stopRecording(false);
        }
        stopSources();
    }
}
