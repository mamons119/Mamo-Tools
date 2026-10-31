package it.mamotools;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

public class VolumeBoostActivity extends Activity {

    /** Guadagno massimo: 100 dB = 10000 millibel. */
    private static final int MAX_GAIN_MB = 10000;
    private static final int GAIN_STEP_MB = 50;      // 0,5 dB
    private static final int WARN_GAIN_MB = 2000;    // da +20 dB in rosso
    private static final int EQ_STEP_MB = 50;

    private static final int[] PRESET_FREQ = {60, 230, 910, 3600, 14000};
    private static final String[] PRESET_NAMES =
            {"Personalizzato", "Piatto", "Bassi", "Voci", "Acuti", "Rock", "Loudness"};
    private static final float[][] PRESET_DB = {
            null,
            {0, 0, 0, 0, 0},
            {6, 4, 1, 0, 0},
            {-3, -1, 3, 4, 1},
            {0, 0, 1, 4, 6},
            {5, 3, -1, 3, 5},
            {6, 3, 0, 2, 5}
    };

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating = false;

    private TextView deviceText, gainText, gainWarn, balText, statusText;
    private Switch boostSwitch, eqSwitch;
    private SeekBar gainSeek, balSeek;
    private Switch balFxSwitch;
    private Button permButton;

    // equalizzatore
    private boolean eqSupported = false;
    private int bandCount = 0, minLvl = -1500, maxLvl = 1500;
    private int[] centerHz = new int[0];
    private SeekBar[] bandSeeks;
    private TextView[] bandLabels;
    private Spinner presetSpinner;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateDevice();
            statusText.setText("Boost: " + BoostService.boostStatus
                    + "\nEqualizzatore: " + BoostService.eqStatus
                    + "\nBilanciamento: " + (prefs.getBoolean("bal_fx", true)
                    ? BoostService.balStatus : "impostazione di sistema"));
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("boost", MODE_PRIVATE);
        queryEq();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(40), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        TextView title = text("Volume Boost", 28, R.color.text, true);
        root.addView(title);
        deviceText = text("", 14, R.color.text_dim, false);
        deviceText.setPadding(0, dp(6), 0, dp(20));
        root.addView(deviceText);

        buildBoostCard(root);
        buildEqCard(root);
        buildBalanceCard(root);

        statusText = text("", 13, R.color.text_dim, false);
        statusText.setPadding(dp(4), 0, 0, dp(12));
        root.addView(statusText);

        Button dev = new Button(this);
        dev.setText("Opzioni sviluppatore (volume assoluto)");
        dev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    statusText.setText("Attiva prima le Opzioni sviluppatore "
                            + "(Info telefono > tocca 7 volte Numero build).");
                }
            }
        });
        root.addView(dev);

        setContentView(scroll);
    }

    // ---------------------------------------------------------------- Amplificazione

    private void buildBoostCard(LinearLayout root) {
        LinearLayout c = card(root, "Amplificazione");

        boostSwitch = new Switch(this);
        boostSwitch.setText("Boost attivo");
        boostSwitch.setTextSize(16);
        boostSwitch.setTextColor(getColor(R.color.text));
        boostSwitch.setPadding(0, dp(12), 0, dp(4));
        c.addView(boostSwitch);

        gainText = text("", 20, R.color.text, true);
        gainText.setGravity(Gravity.CENTER_HORIZONTAL);
        gainText.setPadding(0, dp(16), 0, dp(4));
        c.addView(gainText, new LinearLayout.LayoutParams(MATCH, WRAP));

        gainSeek = new SeekBar(this);
        gainSeek.setMax(MAX_GAIN_MB / GAIN_STEP_MB);
        gainSeek.setProgress(prefs.getInt("gain_mb", 300) / GAIN_STEP_MB);
        c.addView(gainSeek);

        gainWarn = text("", 13, R.color.text_dim, false);
        gainWarn.setPadding(0, dp(8), 0, 0);
        c.addView(gainWarn);
        updateGainLabel();

        gainSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updateGainLabel();
                if (fromUser) {
                    prefs.edit().putInt("gain_mb", progress * GAIN_STEP_MB).apply();
                    if (prefs.getBoolean("boost_on", false)) {
                        syncService();
                    }
                }
            }
        });

        boostSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (updating) return;
                prefs.edit().putBoolean("boost_on", checked).apply();
                syncService();
            }
        });
    }

    private void updateGainLabel() {
        int mb = gainSeek.getProgress() * GAIN_STEP_MB;
        gainText.setText(String.format(Locale.getDefault(), "+%.1f dB", mb / 100f));
        boolean high = mb >= WARN_GAIN_MB;
        gainText.setTextColor(getColor(high ? R.color.warn : R.color.text));
        gainWarn.setTextColor(getColor(high ? R.color.warn : R.color.text_dim));
        gainWarn.setText(high
                ? "Livello estremo: il suono sara' quasi certamente saturo e "
                + "puo' danneggiare l'udito e le cuffie. Abbassa subito se senti "
                + "distorsione o fastidio."
                : "Parti basso e sali piano: con il volume di sistema al massimo "
                + "il suono puo' distorcere e danneggiare l'udito.");
    }

    // ---------------------------------------------------------------- Equalizzatore

    private void queryEq() {
        Equalizer e = null;
        try {
            e = new Equalizer(0, 0);
            bandCount = e.getNumberOfBands();
            short[] r = e.getBandLevelRange();
            minLvl = r[0];
            maxLvl = r[1];
            centerHz = new int[bandCount];
            SharedPreferences.Editor ed = prefs.edit();
            ed.putInt("eqinfo_bands", bandCount)
                    .putInt("eqinfo_min", minLvl)
                    .putInt("eqinfo_max", maxLvl);
            for (short i = 0; i < bandCount; i++) {
                centerHz[i] = e.getCenterFreq(i) / 1000;
                ed.putInt("eqinfo_f" + i, centerHz[i]);
            }
            ed.apply();
            eqSupported = true;
        } catch (Throwable t) {
            // puo' fallire se il servizio tiene gia' l'equalizzatore: uso la copia salvata
            bandCount = prefs.getInt("eqinfo_bands", 0);
            if (bandCount > 0) {
                minLvl = prefs.getInt("eqinfo_min", -1500);
                maxLvl = prefs.getInt("eqinfo_max", 1500);
                centerHz = new int[bandCount];
                for (int i = 0; i < bandCount; i++) {
                    centerHz[i] = prefs.getInt("eqinfo_f" + i, 1000);
                }
                eqSupported = true;
            } else {
                eqSupported = false;
            }
        } finally {
            if (e != null) {
                try {
                    e.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void buildEqCard(LinearLayout root) {
        LinearLayout c = card(root, "Equalizzatore");

        if (!eqSupported) {
            TextView t = text("Equalizzatore non disponibile su questo telefono.",
                    14, R.color.text_dim, false);
            t.setPadding(0, dp(8), 0, 0);
            c.addView(t);
            return;
        }

        eqSwitch = new Switch(this);
        eqSwitch.setText("Equalizzatore attivo");
        eqSwitch.setTextSize(16);
        eqSwitch.setTextColor(getColor(R.color.text));
        eqSwitch.setPadding(0, dp(12), 0, dp(8));
        c.addView(eqSwitch);

        presetSpinner = new Spinner(this);
        presetSpinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, PRESET_NAMES));
        c.addView(presetSpinner);

        bandSeeks = new SeekBar[bandCount];
        bandLabels = new TextView[bandCount];
        for (int i = 0; i < bandCount; i++) {
            final int idx = i;
            TextView l = text("", 14, R.color.text_dim, false);
            l.setPadding(0, dp(14), 0, 0);
            c.addView(l);
            SeekBar s = new SeekBar(this);
            s.setMax((maxLvl - minLvl) / EQ_STEP_MB);
            s.setProgress((prefs.getInt("eq_" + i, 0) - minLvl) / EQ_STEP_MB);
            c.addView(s);
            bandLabels[i] = l;
            bandSeeks[i] = s;
            updateBandLabel(i);

            s.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    updateBandLabel(idx);
                    if (fromUser) {
                        prefs.edit().putInt("eq_" + idx, levelOf(progress)).apply();
                        presetSpinner.setSelection(0);
                        if (prefs.getBoolean("eq_on", false)) {
                            syncService();
                        }
                    }
                }
            });
        }

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == 0) return;
                SharedPreferences.Editor ed = prefs.edit();
                for (int i = 0; i < bandCount; i++) {
                    int lvl = presetLevel(PRESET_DB[pos], centerHz[i]);
                    ed.putInt("eq_" + i, lvl);
                    bandSeeks[i].setProgress((lvl - minLvl) / EQ_STEP_MB);
                    updateBandLabel(i);
                }
                ed.apply();
                if (prefs.getBoolean("eq_on", false)) {
                    syncService();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        eqSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (updating) return;
                prefs.edit().putBoolean("eq_on", checked).apply();
                syncService();
            }
        });
    }

    private int levelOf(int progress) {
        return Math.max(minLvl, Math.min(maxLvl, minLvl + progress * EQ_STEP_MB));
    }

    private void updateBandLabel(int i) {
        int hz = centerHz[i];
        String f = hz >= 1000
                ? String.format(Locale.getDefault(), "%.1f kHz", hz / 1000f)
                : hz + " Hz";
        int lvl = levelOf(bandSeeks[i].getProgress());
        bandLabels[i].setText(String.format(Locale.getDefault(),
                "%s   %+.1f dB", f, lvl / 100f));
    }

    /** Interpola (in scala logaritmica) il preset sulle frequenze reali delle bande. */
    private int presetLevel(float[] db, int hz) {
        int last = PRESET_FREQ.length - 1;
        double v = 0;
        if (hz <= PRESET_FREQ[0]) {
            v = db[0];
        } else if (hz >= PRESET_FREQ[last]) {
            v = db[last];
        } else {
            double x = Math.log10(hz);
            for (int k = 0; k < last; k++) {
                if (hz <= PRESET_FREQ[k + 1]) {
                    double x0 = Math.log10(PRESET_FREQ[k]);
                    double x1 = Math.log10(PRESET_FREQ[k + 1]);
                    double t = (x - x0) / (x1 - x0);
                    v = db[k] + t * (db[k + 1] - db[k]);
                    break;
                }
            }
        }
        int mb = Math.round((float) v * 100);
        return Math.max(minLvl, Math.min(maxLvl, mb));
    }

    // ---------------------------------------------------------------- Bilanciamento

    private void buildBalanceCard(LinearLayout root) {
        LinearLayout c = card(root, "Bilanciamento sinistra / destra");

        balText = text("", 18, R.color.text, true);
        balText.setGravity(Gravity.CENTER_HORIZONTAL);
        balText.setPadding(0, dp(14), 0, dp(4));
        c.addView(balText, new LinearLayout.LayoutParams(MATCH, WRAP));

        balSeek = new SeekBar(this);
        balSeek.setMax(200);
        balSeek.setProgress(Math.round(readBalance() * 100) + 100);
        c.addView(balSeek);
        updateBalLabel();

        balSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updateBalLabel();
                if (fromUser) {
                    applyBalance((progress - 100) / 100f);
                }
            }
        });

        balFxSwitch = new Switch(this);
        balFxSwitch.setText("Metodo effetto audio (consigliato per le cuffie Bluetooth)");
        balFxSwitch.setTextSize(14);
        balFxSwitch.setTextColor(getColor(R.color.text));
        balFxSwitch.setPadding(0, dp(12), 0, dp(8));
        balFxSwitch.setChecked(prefs.getBoolean("bal_fx", true));
        balFxSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (updating) return;
                prefs.edit().putBoolean("bal_fx", checked).apply();
                updatePermVisibility();
                applyBalance((balSeek.getProgress() - 100) / 100f);
            }
        });
        c.addView(balFxSwitch);

        Button center = new Button(this);
        center.setText("Centra");
        center.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                balSeek.setProgress(100);
                applyBalance(0f);
            }
        });
        c.addView(center);

        permButton = new Button(this);
        permButton.setText("Concedi il permesso per il bilanciamento");
        permButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        c.addView(permButton);
    }

    private void updateBalLabel() {
        int p = balSeek.getProgress() - 100;
        if (p == 0) {
            balText.setText("Centro");
        } else {
            balText.setText((p < 0 ? "Sinistra " : "Destra ") + Math.abs(p) + "%");
        }
    }

    private float readSystemBalance() {
        try {
            return Settings.System.getFloat(getContentResolver(), "master_balance", 0f);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private float readBalance() {
        return prefs.getBoolean("bal_fx", true)
                ? prefs.getFloat("bal", 0f)
                : readSystemBalance();
    }

    private void applyBalance(float b) {
        b = Math.max(-1f, Math.min(1f, b));
        boolean fx = prefs.getBoolean("bal_fx", true);
        prefs.edit().putFloat("bal", b).apply();

        if (fx) {
            // metodo effetto: azzero quello di sistema per non applicarlo due volte
            if (Settings.System.canWrite(this) && readSystemBalance() != 0f) {
                try {
                    Settings.System.putFloat(getContentResolver(), "master_balance", 0f);
                } catch (Throwable ignored) {
                }
            }
        } else {
            if (!Settings.System.canWrite(this)) {
                statusText.setText("Per il bilanciamento di sistema serve il permesso: "
                        + "tocca \"Concedi il permesso\".");
            } else {
                try {
                    Settings.System.putFloat(getContentResolver(), "master_balance", b);
                } catch (Throwable t) {
                    statusText.setText("Bilanciamento non riuscito: "
                            + t.getClass().getSimpleName());
                }
            }
        }
        syncService();
    }

    private void updatePermVisibility() {
        boolean need = !prefs.getBoolean("bal_fx", true) && !Settings.System.canWrite(this);
        permButton.setVisibility(need ? View.VISIBLE : View.GONE);
    }

    private boolean anyEffectOn() {
        return prefs.getBoolean("boost_on", false)
                || prefs.getBoolean("eq_on", false)
                || (prefs.getBoolean("bal_fx", true) && prefs.getFloat("bal", 0f) != 0f);
    }

    // ---------------------------------------------------------------- Utilita'

    private void syncService() {
        boolean on = anyEffectOn();
        Intent i = new Intent(this, BoostService.class);
        if (on) {
            i.setAction(BoostService.ACTION_APPLY);
            startForegroundService(i);
        } else {
            stopService(i);
        }
    }

    private void updateDevice() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        String name = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                name = String.valueOf(d.getProductName());
                break;
            }
        }
        deviceText.setText(name != null
                ? "Cuffie Bluetooth: " + name
                : "Nessuna cuffia Bluetooth collegata (gli effetti agiscono sull'uscita attiva)");
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(16);
        parent.addView(c, lp);
        c.addView(text(title, 18, R.color.accent, true));
        return c;
    }

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updating = true;
        boostSwitch.setChecked(prefs.getBoolean("boost_on", false));
        if (eqSwitch != null) {
            eqSwitch.setChecked(prefs.getBoolean("eq_on", false));
        }
        balFxSwitch.setChecked(prefs.getBoolean("bal_fx", true));
        updating = false;
        balSeek.setProgress(Math.round(readBalance() * 100) + 100);
        updatePermVisibility();
        if (anyEffectOn()) {
            syncService();
        }
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    /** SeekBar listener con i due metodi che non ci servono gia' vuoti. */
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }
}
