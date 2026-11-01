package it.mamotools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;

import java.util.Locale;

/**
 * Tiene attivi sul mix audio globale (sessione 0) un LoudnessEnhancer (guadagno)
 * e un Equalizer. Lo stato desiderato sta nelle SharedPreferences "boost",
 * scritte dalla schermata; il servizio le rilegge a ogni ACTION_APPLY.
 */
public class BoostService extends Service {

    public static final String ACTION_APPLY = "it.mamotools.APPLY";
    public static final String ACTION_STOP = "it.mamotools.STOP";

    private static final String CHANNEL_ID = "boost";
    private static final int NOTIF_ID = 1;

    /** Ultimo stato, letto dalla schermata (stesso processo). */
    public static volatile String boostStatus = "Spento";
    public static volatile String eqStatus = "Spento";
    public static volatile String balStatus = "Spento";

    private LoudnessEnhancer enhancer;
    private Equalizer equalizer;
    private DynamicsProcessing dynamics;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences("boost", MODE_PRIVATE);

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            p.edit().putBoolean("boost_on", false).putBoolean("eq_on", false)
                    .putFloat("bal", 0f).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification());
        applyBoost(p.getBoolean("boost_on", false), p.getInt("gain_mb", 0));
        applyEq(p.getBoolean("eq_on", false), p);
        applyBalance(p.getBoolean("bal_fx", true), p.getFloat("bal", 0f));
        BoostWidgetProvider.refresh(this);
        return START_STICKY;
    }

    /**
     * Bilanciamento via DynamicsProcessing: abbassa il guadagno in ingresso
     * del canale opposto alla direzione scelta (-1 = tutto a sinistra, +1 = tutto a destra).
     */
    private void applyBalance(boolean fx, float bal) {
        if (!fx || Math.abs(bal) < 0.005f) {
            releaseDynamics();
            balStatus = "Spento";
            return;
        }
        if (Build.VERSION.SDK_INT < 28) {
            balStatus = "non supportato (serve Android 9+)";
            return;
        }
        try {
            if (dynamics == null) {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2, false, 0, false, 0, false, 0, false).build();
                dynamics = new DynamicsProcessing(0, 0, cfg);
            }
            float att = Math.min(1f, Math.abs(bal));
            float db = (float) (20.0 * Math.log10(Math.max(1f - att, 0.001f)));
            float left = bal > 0 ? db : 0f;
            float right = bal < 0 ? db : 0f;
            dynamics.setInputGainbyChannel(0, left);
            dynamics.setInputGainbyChannel(1, right);
            dynamics.setEnabled(true);
            balStatus = String.format(Locale.getDefault(),
                    "attivo (S %.0f dB, D %.0f dB)", left, right);
        } catch (Throwable t) {
            balStatus = "errore: " + describe(t);
            releaseDynamics();
        }
    }

    private void applyBoost(boolean on, int gainMb) {
        if (!on) {
            releaseEnhancer();
            boostStatus = "Spento";
            return;
        }
        try {
            if (enhancer == null) {
                enhancer = new LoudnessEnhancer(0);
            }
            enhancer.setTargetGain(gainMb);
            enhancer.setEnabled(gainMb > 0);
            boostStatus = String.format(Locale.getDefault(),
                    "attivo, +%.1f dB", gainMb / 100f);
        } catch (Throwable t) {
            boostStatus = "errore: " + describe(t);
            releaseEnhancer();
        }
    }

    private void applyEq(boolean on, SharedPreferences p) {
        if (!on) {
            releaseEq();
            eqStatus = "Spento";
            return;
        }
        try {
            if (equalizer == null) {
                equalizer = new Equalizer(0, 0);
            }
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            for (short i = 0; i < bands; i++) {
                int lvl = p.getInt("eq_" + i, 0);
                lvl = Math.max(range[0], Math.min(range[1], lvl));
                equalizer.setBandLevel(i, (short) lvl);
            }
            equalizer.setEnabled(true);
            eqStatus = "attivo";
        } catch (Throwable t) {
            eqStatus = "errore: " + describe(t);
            releaseEq();
        }
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName()
                + (t.getMessage() != null ? " - " + t.getMessage() : "");
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Volume Boost", NotificationManager.IMPORTANCE_LOW));
        }

        Intent open = new Intent(this, VolumeBoostActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, BoostService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Volume Boost attivo")
                .setContentText("Tocca per regolare audio ed equalizzatore")
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        null, "Spegni", stopPi).build())
                .setOngoing(true)
                .build();
    }

    private void releaseEnhancer() {
        if (enhancer != null) {
            try {
                enhancer.setEnabled(false);
                enhancer.release();
            } catch (Throwable ignored) {
            }
            enhancer = null;
        }
    }

    private void releaseEq() {
        if (equalizer != null) {
            try {
                equalizer.setEnabled(false);
                equalizer.release();
            } catch (Throwable ignored) {
            }
            equalizer = null;
        }
    }

    private void releaseDynamics() {
        if (dynamics != null) {
            try {
                dynamics.setEnabled(false);
                dynamics.release();
            } catch (Throwable ignored) {
            }
            dynamics = null;
        }
    }

    @Override
    public void onDestroy() {
        releaseEnhancer();
        releaseEq();
        releaseDynamics();
        boostStatus = "Spento";
        eqStatus = "Spento";
        balStatus = "Spento";
        BoostWidgetProvider.refresh(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
