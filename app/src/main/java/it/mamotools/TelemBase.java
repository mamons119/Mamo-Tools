package it.mamotools;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Base degli strumenti di telemetria: permessi, GPS, sensori e scheda di stato. */
abstract class TelemBase extends Activity {

    static final int REQ_LOC = 77;

    protected GpsSource gps;
    protected ImuSource imu;
    protected LinearLayout root;
    protected final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout statusBox;
    private TextView statusText;
    private LinearLayout statusButtons;
    private SharedPreferences prefs;

    protected abstract String title();

    protected abstract String subtitle();

    protected abstract void build(LinearLayout root);

    protected void onFix(GpsSource.Fix f) {
    }

    /** Chiamato circa 20 volte al secondo mentre l'attivita' e' in primo piano. */
    protected void onTick() {
    }

    protected boolean needsGps() {
        return true;
    }

    protected boolean needsImu() {
        return true;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            onTick();
            ui.postDelayed(this, 50);
        }
    };

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("telem", MODE_PRIVATE);
        root = Ui.screen(this, title(), subtitle());
        gps = new GpsSource(this, new GpsSource.Cb() {
            @Override
            public void onFix(GpsSource.Fix f) {
                if (imu != null) {
                    imu.setSpeed(f.speed);
                }
                TelemBase.this.onFix(f);
            }
        });
        imu = new ImuSource(this);
        imu.mount = prefs.getInt("mount", 0);
        imu.invert = prefs.getBoolean("invert", false);

        statusBox = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.addView(Ui.section(this, "Stato dei sensori"));
        statusBox.addView(head);
        statusText = Ui.text(this, "", 13, Ui.TEXT, false);
        statusText.setLineSpacing(0, 1.25f);
        statusBox.addView(statusText);
        statusButtons = new LinearLayout(this);
        statusBox.addView(statusButtons, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        root.addView(statusBox);
        View sp = new View(this);
        root.addView(sp, new LinearLayout.LayoutParams(1, Ui.dp(this, 12)));
        build(root);
    }

    protected void saveMount() {
        prefs.edit().putInt("mount", imu.mount).putBoolean("invert", imu.invert).apply();
    }

    boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSources();
        ui.post(ticker);
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(statusTick);
        if (shouldStopOnPause()) {
            stopSources();
        }
    }

    protected boolean shouldStopOnPause() {
        return true;
    }

    protected void startSources() {
        if (needsImu()) {
            imu.start();
        }
        if (needsGps() && hasPerm()) {
            gps.start();
        }
        refreshStatus();
    }

    protected void stopSources() {
        gps.stop();
        imu.stop();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_LOC) {
            startSources();
        }
    }

    /** Aggiorna la scheda: ogni sensore con un pallino verde/rosso e i pulsanti per risolvere. */
    protected void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        boolean perm = hasPerm(), on = gps.enabled();
        if (needsGps()) {
            if (!perm) {
                sb.append("✖ GPS: permesso di posizione non concesso\n");
            } else if (!on) {
                sb.append("✖ GPS: spento (attiva la posizione del telefono)\n");
            } else if (gps.hasRecentFix()) {
                sb.append(String.format(Locale.ITALY, "✔ GPS: %d satelliti, %.1f aggiornamenti/s\n",
                        gps.satsUsed, gps.rateHz));
            } else {
                sb.append(String.format(Locale.ITALY, "… GPS: in cerca del segnale (%d/%d satelliti). "
                        + "Mettiti all'aperto.\n", gps.satsUsed, gps.satsTotal));
            }
        }
        if (needsImu()) {
            sb.append(imu.hasGrav || imu.hasAcc ? "✔" : "✖").append(" Accelerometro/gravita'\n");
            sb.append(imu.hasLin || imu.hasAcc ? "✔" : "✖").append(" Accelerazione lineare\n");
            sb.append(imu.hasGyro ? "✔" : "✖").append(" Giroscopio")
                    .append(imu.hasGyro ? "" : " (assente: la piega sara' solo apparente)");
        }
        statusText.setText(sb.toString().trim());
        statusButtons.removeAllViews();
        if (needsGps() && !perm) {
            statusButtons.addView(Ui.btn(this, "Concedi il permesso", Ui.ACCENT, 0xFF000000,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                        }
                    }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 42), 0, 0));
        } else if (needsGps() && !on) {
            statusButtons.addView(Ui.btn(this, "Attiva il GPS", Ui.ACCENT, 0xFF000000,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        }
                    }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 42), 0, 0));
        }
        statusButtons.setVisibility(statusButtons.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (needsGps() && perm && on && !gps.hasRecentFix()) {
            gps.start();
        }
    }
}
