package it.mamotools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

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

/**
 * Registra la sessione (GPS + sensori, 10 righe al secondo) in un servizio in primo piano: continua con lo schermo
 * bloccato o l'app chiusa, mostrando una notifica con tempo, distanza e pulsante Stop.
 * La schermata legge lo stato dai campi statici (stesso processo).
 */
public class RecorderService extends Service {

    static final String ACTION_START = "it.mamotools.REC_START";
    static final String ACTION_STOP = "it.mamotools.REC_STOP";
    static final String ACTION_GATE = "it.mamotools.REC_GATE";
    private static final String CH_REC = "recorder", CH_DONE = "recorder_done";
    private static final int ID_REC = 20, ID_DONE = 21;

    // ---- stato condiviso con la schermata
    static volatile boolean running = false;
    static volatile long startElapsedMs;
    static volatile double dist, vMax;
    static volatile float maxLeanL, maxLeanR, maxG, speed;
    static volatile boolean hasFix = false, gateSet = false;
    static volatile String vehicle = "", lapsText = "";
    /** Ultima sessione terminata e non ancora "chiusa" dalla schermata (percorso e nome predefinito). */
    static volatile String lastPath = null, lastName = "", lastVehicle = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private GpsSource gps;
    private ImuSource imu;
    private PowerManager.WakeLock wl;
    private File file;
    private BufferedWriter out;
    private ExecutorService io;
    private GpsSource.Fix fix, prevFix;
    private long lastRowMs, lastLapMs = -100000, lastFlushMs, lastNotifMs, startEpoch;
    private double[] gate;
    private int lapCount;
    private final List<Double> lapTimes = new ArrayList<>();
    private double lapStartS = -1;
    private String name = "";

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            if (running) {
                stopRecording();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_GATE.equals(a)) {
            setGate();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(a) || running) {
            if (!running) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        startRecording(intent.getStringExtra("vehicle"), intent.getStringExtra("name"),
                intent.getStringExtra("desc"));
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ avvio / arresto

    private void startRecording(String veh, String customName, String customDesc) {
        makeChannels();
        Notification n = buildNotif(0, 0);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_REC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(ID_REC, n);
        }
        File dir = new File(getFilesDir(), "sessions");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        startEpoch = System.currentTimeMillis();
        file = new File(dir, "s_" + startEpoch + ".csv");
        name = customName != null && !customName.trim().isEmpty() ? customName.trim()
                : "Sessione " + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(new Date(startEpoch));
        try {
            out = new BufferedWriter(new FileWriter(file), 32 * 1024);
            out.write(SessionData.header(startEpoch, name) + "\n");
            out.write("#VEH," + (veh == null ? "" : veh) + "\n");
            if (customDesc != null && !customDesc.trim().isEmpty()) {
                out.write("#DESC," + SessionData.esc1(customDesc.trim()) + "\n");
            }
        } catch (IOException e) {
            stopForeground(true);
            stopSelf();
            return;
        }
        io = Executors.newSingleThreadExecutor();
        SharedPreferences p = getSharedPreferences("telem", MODE_PRIVATE);
        gps = new GpsSource(this, new GpsSource.Cb() {
            @Override
            public void onFix(GpsSource.Fix f) {
                if (imu != null) {
                    imu.setSpeed(f.speed);
                }
                handleFix(f);
            }
        });
        imu = new ImuSource(this);
        imu.mount = p.getInt("mount", 0);
        imu.invert = p.getBoolean("invert", false);
        imu.start();
        gps.start();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mamotools:recorder");
        wl.acquire(12L * 60 * 60 * 1000);

        startElapsedMs = SystemClock.elapsedRealtime();
        lastRowMs = 0;
        lastFlushMs = lastNotifMs = startElapsedMs;
        dist = 0;
        vMax = 0;
        maxLeanL = maxLeanR = maxG = speed = 0;
        gate = null;
        gateSet = false;
        lapCount = 0;
        lapTimes.clear();
        lapStartS = -1;
        lastLapMs = -100000;
        prevFix = fix = null;
        hasFix = false;
        vehicle = veh == null ? "" : veh;
        lapsText = "Premi il pulsante mentre passi sul traguardo, nel verso di marcia.";
        lastPath = null;
        running = true;
        h.post(tick);
    }

    private void stopRecording() {
        running = false;
        h.removeCallbacks(tick);
        if (gps != null) {
            gps.stop();
        }
        if (imu != null) {
            imu.stop();
        }
        if (wl != null && wl.isHeld()) {
            wl.release();
        }
        final BufferedWriter w = out;
        final File f = file;
        final String nm = name, veh = vehicle;
        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        w.flush();
                        w.close();
                    } catch (IOException ignored) {
                    }
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            finishSaved(f, nm, veh);
                        }
                    });
                }
            });
        } catch (Exception e) {
            finishSaved(f, nm, veh);
        }
        io.shutdown();
    }

    private void finishSaved(File f, String nm, String veh) {
        lastPath = f.getAbsolutePath();
        lastName = nm;
        lastVehicle = veh;
        stopForeground(true);
        Intent open = new Intent(this, SessionViewActivity.class).putExtra("path", f.getAbsolutePath());
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sessione salvata")
                .setContentText(String.format(Locale.ITALY, "%s · %s — tocca per aprirla", nm, fmtDist(dist)))
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Apri", pi).build())
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_DONE, n);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (running) {
            // il sistema ci ferma: salva quel che c'e'
            running = false;
            h.removeCallbacks(tick);
            if (gps != null) {
                gps.stop();
            }
            if (imu != null) {
                imu.stop();
            }
            try {
                out.flush();
                out.close();
            } catch (Exception ignored) {
            }
            lastPath = file.getAbsolutePath();
            lastName = name;
            lastVehicle = vehicle;
        }
        if (wl != null && wl.isHeld()) {
            wl.release();
        }
    }

    // ------------------------------------------------------------------ dati

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.elapsedRealtime(), el = now - startElapsedMs;
            float lean = imu.leanDeg;
            if (lean > 0) {
                maxLeanL = Math.max(maxLeanL, lean);
            } else {
                maxLeanR = Math.max(maxLeanR, -lean);
            }
            maxG = Math.max(maxG, (float) Math.hypot(imu.aLong, imu.aLat));
            if (fix != null && gps.hasRecentFix() && now - lastRowMs >= 100) {
                lastRowMs = now;
                write(String.format(Locale.US, "%d,%.7f,%.7f,%.1f,%.2f,%.1f,%.1f,%.3f,%.3f,%.1f,%.1f\n",
                        el, fix.lat, fix.lon, fix.alt, fix.speed, fix.bearing, fix.acc,
                        imu.aLong, imu.aLat, imu.leanDeg, imu.yawDegS));
            }
            if (now - lastFlushMs > 5000) {
                lastFlushMs = now;
                flushAsync();
            }
            if (now - lastNotifMs >= 1000) {
                lastNotifMs = now;
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(ID_REC, buildNotif(el, dist));
            }
            h.postDelayed(this, 50);
        }
    };

    private void handleFix(GpsSource.Fix f) {
        if (!running) {
            return;
        }
        prevFix = fix;
        fix = f;
        hasFix = true;
        speed = f.speed;
        if (prevFix != null) {
            double dt = (f.tNs - prevFix.tNs) / 1e9;
            if (dt > 0 && dt < 5 && f.speed > 0.5) {
                dist += f.speed * dt;
            }
        }
        vMax = Math.max(vMax, f.speed);
        if (gate != null && prevFix != null && f.speed > 2f) {
            double s = SessionData.crossFraction(gate[0], gate[1], gate[2], 35, prevFix.lat, prevFix.lon,
                    f.lat, f.lon);
            if (s >= 0) {
                long tc = (prevFix.tNs + (long) (s * (f.tNs - prevFix.tNs))) / 1_000_000L - startElapsedMs;
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

    private void setGate() {
        if (!running || fix == null) {
            return;
        }
        float brg = fix.speed > 2f ? fix.bearing : 0;
        gate = new double[]{fix.lat, fix.lon, brg};
        gateSet = true;
        write(String.format(Locale.US, "#LINE,%.7f,%.7f,%.1f\n", fix.lat, fix.lon, brg));
        lapCount = 0;
        lapTimes.clear();
        lapStartS = -1;
        lastLapMs = -100000;
        lapsText = fix.speed > 2f ? "Traguardo impostato: i giri partono dal prossimo passaggio."
                : "Traguardo impostato (muoviti per dare la direzione: meglio impostarlo in marcia).";
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
        lapsText = sb.toString().trim();
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

    private void flushAsync() {
        final BufferedWriter w = out;
        if (io == null || w == null) {
            return;
        }
        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        w.flush();
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ notifica

    private void makeChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_REC, "Registrazione percorso",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Sessioni registrate",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    static String fmtDist(double m) {
        return m < 1000 ? String.format(Locale.ITALY, "%.0f m", m) : String.format(Locale.ITALY, "%.2f km", m / 1000);
    }

    private static String fmtClock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ITALY, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ITALY, "%d:%02d", s / 60, s % 60);
    }

    private Notification buildNotif(long elapsedMs, double distM) {
        Intent open = new Intent(this, RecorderActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String v = Vehicle.index(vehicle) >= 0 ? " · " + Vehicle.label(vehicle) : "";
        return new Notification.Builder(this, CH_REC)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Mamo Tools sta registrando il percorso")
                .setContentText("Tempo " + fmtClock(elapsedMs) + " · Distanza " + fmtDist(distM) + v)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "■  Stop e salva", stopPi).build())
                .build();
    }
}
