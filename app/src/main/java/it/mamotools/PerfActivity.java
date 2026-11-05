package it.mamotools;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Prestazioni con il GPS: da fermo (0-100, 0-200, 100-200, 1/4 di miglio...), ripresa e frenata. */
public class PerfActivity extends TelemBase {

    private static final float[] SP = {50, 100, 150, 200, 96.56f};
    private static final String[] SP_NAME = {"0–50 km/h", "0–100 km/h", "0–150 km/h", "0–200 km/h", "0–60 mph"};
    private static final double[] DS = {201.17, 402.34, 1000};
    private static final String[] DS_NAME = {"1/8 di miglio (201 m)", "1/4 di miglio (402 m)", "1000 m"};

    private int mode = 0;
    private int state = 0; // 0 inattivo, 1 pronto, 2 in corso, 3 finito
    private TextView[] modeChips;
    private LinearLayout paramBox;
    private EditText fromEt, toEt;
    private SpeedGaugeView gauge;
    private TextView toLabel, speedTv, stateTv, resultTv, accTv;
    private SharedPreferences prefs;

    private long t0, candNs, prevT, brakeNs;
    private double prevV, prevD, dist;
    private GpsSource.Fix lastFix;
    private double[] spT = new double[SP.length], dsT = new double[DS.length];
    private double trap = Double.NaN, rollTime, rollDist, stopTime, stopDist, vmax;

    @Override
    protected String title() {
        return "Prestazioni";
    }

    @Override
    protected String subtitle() {
        return "Tempi di accelerazione, ripresa e frenata misurati con il GPS.";
    }

    @Override
    protected void build(LinearLayout root) {
        prefs = getSharedPreferences("perf", MODE_PRIVATE);
        LinearLayout c = Ui.card(this);
        c.addView(Ui.section(this, "Prova"));
        LinearLayout r = new LinearLayout(this);
        String[] n = {"Da fermo", "Ripresa", "Frenata"};
        modeChips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int m = i;
            modeChips[i] = Ui.chip(this, n[i]);
            modeChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mode = m;
                    resetRun();
                    styleModes();
                }
            });
            r.addView(modeChips[i], Ui.lp(this, 0, Ui.dp(this, 42), i == 0 ? 0 : 8, 0, 1f));
        }
        c.addView(r);
        paramBox = new LinearLayout(this);
        paramBox.setGravity(Gravity.CENTER_VERTICAL);
        paramBox.addView(Ui.text(this, "Da", 14, Ui.DIM, false));
        fromEt = num("60");
        paramBox.addView(fromEt, Ui.lp(this, Ui.dp(this, 76), Ui.WRAP, 8, 0));
        toLabel = Ui.text(this, "a", 14, Ui.DIM, false);
        toLabel.setPadding(Ui.dp(this, 14), 0, 0, 0);
        paramBox.addView(toLabel);
        toEt = num("100");
        paramBox.addView(toEt, Ui.lp(this, Ui.dp(this, 76), Ui.WRAP, 8, 0));
        paramBox.addView(Ui.text(this, "km/h", 14, Ui.DIM, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        c.addView(paramBox, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        root.addView(c);

        LinearLayout live = Ui.card(this);
        gauge = new SpeedGaugeView(this);
        live.addView(gauge, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        speedTv = Ui.text(this, "0", 56, Ui.TEXT, true);
        speedTv.setGravity(Gravity.CENTER);
        live.addView(speedTv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        TextView unit = Ui.text(this, "km/h", 14, Ui.DIM, false);
        unit.setGravity(Gravity.CENTER);
        live.addView(unit, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        speedTv.setTextColor(Ui.ACCENT);
        stateTv = Ui.text(this, "", 16, Ui.ACCENT, true);
        stateTv.setGravity(Gravity.CENTER);
        stateTv.setPadding(0, Ui.dp(this, 12), 0, 0);
        live.addView(stateTv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        accTv = Ui.text(this, "", 12, Ui.DIM, false);
        accTv.setGravity(Gravity.CENTER);
        live.addView(accTv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        live.addView(Ui.btn(this, "Azzera / nuova prova", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetRun();
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 12));
        root.addView(live, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout res = Ui.card(this);
        res.addView(Ui.section(this, "Risultati"));
        resultTv = Ui.text(this, "", 15, Ui.TEXT, false);
        resultTv.setLineSpacing(0, 1.3f);
        res.addView(resultTv);
        TextView note = Ui.text(this, "I tempi sono interpolati tra i punti GPS (di solito uno al secondo): "
                + "errore tipico 0,1–0,3 s. Partenza rilevata dall'accelerometro. Prova su strada chiusa.",
                11, Ui.DIM, false);
        note.setPadding(0, Ui.dp(this, 10), 0, 0);
        res.addView(note);
        root.addView(res, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        styleModes();
        resetRun();
    }

    private EditText num(String v) {
        EditText e = new EditText(this);
        e.setText(v);
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.round(this, Ui.BG, 10));
        e.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        return e;
    }

    private void styleModes() {
        for (int i = 0; i < 3; i++) {
            Ui.styleChip(this, modeChips[i], mode == i);
        }
        paramBox.setVisibility(mode == 0 ? View.GONE : View.VISIBLE);
        toLabel.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        toEt.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        if (mode == 2 && fromEt.getText().toString().equals("60")) {
            fromEt.setText("100");
        }
    }

    private void resetRun() {
        state = 0;
        candNs = 0;
        brakeNs = 0;
        trap = Double.NaN;
        rollTime = rollDist = stopTime = stopDist = Double.NaN;
        vmax = 0;
        if (gauge != null) {
            gauge.resetMax();
        }
        java.util.Arrays.fill(spT, Double.NaN);
        java.util.Arrays.fill(dsT, Double.NaN);
        updateState();
        updateResults();
    }

    private double kmh(double ms) {
        return ms * 3.6;
    }

    @Override
    protected void onFix(GpsSource.Fix f) {
        double v = f.speed;
        speedTv.setText(String.valueOf(Math.round(kmh(v))));
        gauge.setSpeed((float) kmh(v));
        accTv.setText(String.format(Locale.ITALY, "precisione GPS ±%.0f m", f.acc));
        vmax = Math.max(vmax, v);
        if (mode == 0) {
            fromStandstill(f);
        } else if (mode == 1) {
            roll(f);
        } else {
            brake(f);
        }
        lastFix = f;
        updateState();
        updateResults();
    }

    @Override
    protected void onTick() {
        // rilevazione partenza e frenata dall'accelerometro
        long now = SystemClock.elapsedRealtimeNanos();
        if (mode == 0 && state == 1) {
            if (imu.aLong > 0.18f) {
                if (candNs == 0) {
                    candNs = now;
                }
            } else if (candNs != 0 && now - candNs > 4_000_000_000L) {
                candNs = 0;
            }
        }
        if (imu.aLong < -0.22f) {
            brakeNs = now;
        }
    }

    // ------------------------------------------------------------------ da fermo

    private void fromStandstill(GpsSource.Fix f) {
        double v = f.speed;
        if (state == 0 || state == 3) {
            if (state == 0 && v < 0.6) {
                state = 1;
                candNs = 0;
            }
            if (state != 1) {
                lastFix = f;
                return;
            }
        }
        if (state == 1) {
            if (v < 0.6) {
                if (candNs != 0 && f.tNs - candNs > 4_000_000_000L) {
                    candNs = 0;
                }
                return;
            }
            long start;
            if (candNs != 0 && f.tNs - candNs < 4_000_000_000L) {
                start = candNs;
            } else {
                double a = 3.0;
                if (lastFix != null && lastFix.speed < 0.6 && f.tNs > lastFix.tNs) {
                    a = Math.max(0.8, (v - lastFix.speed) / ((f.tNs - lastFix.tNs) / 1e9));
                }
                start = f.tNs - (long) (v / a * 1e9);
                if (lastFix != null) {
                    start = Math.max(start, lastFix.tNs);
                }
            }
            t0 = start;
            prevT = t0;
            prevV = 0;
            prevD = 0;
            state = 2;
        }
        if (state == 2) {
            double dt = (f.tNs - prevT) / 1e9;
            if (dt <= 0) {
                return;
            }
            double d1 = prevD + 0.5 * (prevV + v) * dt;
            for (int i = 0; i < SP.length; i++) {
                double th = SP[i] / 3.6;
                if (Double.isNaN(spT[i]) && prevV < th && v >= th) {
                    double tc = prevT + (th - prevV) / (v - prevV) * dt * 1e9;
                    spT[i] = (tc - t0) / 1e9;
                }
            }
            for (int j = 0; j < DS.length; j++) {
                if (Double.isNaN(dsT[j]) && prevD < DS[j] && d1 >= DS[j]) {
                    double fr = (DS[j] - prevD) / (d1 - prevD);
                    double tc = prevT + fr * dt * 1e9;
                    dsT[j] = (tc - t0) / 1e9;
                    if (j == 1) {
                        trap = kmh(prevV + fr * (v - prevV));
                    }
                }
            }
            prevT = f.tNs;
            prevV = v;
            prevD = d1;
            double el = (f.tNs - t0) / 1e9;
            if (!Double.isNaN(dsT[2]) || (v < 0.5 && el > 3) || el > 150) {
                state = 3;
                saveBests();
            }
        }
    }

    // ------------------------------------------------------------------ ripresa

    private double param(EditText e, double def) {
        try {
            return Double.parseDouble(e.getText().toString().trim());
        } catch (Exception x) {
            return def;
        }
    }

    private void roll(GpsSource.Fix f) {
        double a = param(fromEt, 60) / 3.6, b = param(toEt, 100) / 3.6;
        if (b <= a) {
            return;
        }
        double v = f.speed;
        if (state == 0 || state == 3) {
            if (state == 3) {
                lastFix = f;
                return;
            }
            if (v < a - 0.3) {
                state = 1; // pronto: sotto la velocita' di partenza
            }
            if (state == 0) {
                lastFix = f;
                return;
            }
        }
        if (state == 1 && lastFix != null && lastFix.speed < a && v >= a) {
            double dt = (f.tNs - lastFix.tNs) / 1e9;
            double fr = (a - lastFix.speed) / (v - lastFix.speed);
            t0 = lastFix.tNs + (long) (fr * dt * 1e9);
            prevT = f.tNs;
            prevV = v;
            prevD = 0.5 * (a + v) * (f.tNs - t0) / 1e9;
            dist = prevD;
            state = 2;
            if (v >= b) {
                finishRoll(f, b);
            }
            return;
        }
        if (state == 2) {
            double dt = (f.tNs - prevT) / 1e9;
            double d1 = prevD + 0.5 * (prevV + v) * dt;
            if (v >= b && prevV < b) {
                double fr = (b - prevV) / (v - prevV);
                double tc = prevT + fr * dt * 1e9;
                rollTime = (tc - t0) / 1e9;
                rollDist = prevD + fr * (d1 - prevD);
                state = 3;
                return;
            }
            if (v < a - 1.0) {
                state = 1; // e' rallentato: si riparte da capo
                return;
            }
            prevT = f.tNs;
            prevV = v;
            prevD = d1;
        }
    }

    private void finishRoll(GpsSource.Fix f, double b) {
        rollTime = (f.tNs - t0) / 1e9;
        rollDist = prevD;
        state = 3;
    }

    // ------------------------------------------------------------------ frenata

    private void brake(GpsSource.Fix f) {
        double from = param(fromEt, 100) / 3.6;
        double v = f.speed;
        if (state == 0) {
            if (v > from + 1.0) {
                state = 1; // sopra la velocita': pronto a frenare
            }
            lastFix = f;
            return;
        }
        if (state == 1 && lastFix != null && lastFix.speed > from && v <= from) {
            boolean braking = SystemClock.elapsedRealtimeNanos() - brakeNs < 2_000_000_000L || !imu.usable();
            if (braking) {
                double dt = (f.tNs - lastFix.tNs) / 1e9;
                double fr = (lastFix.speed - from) / (lastFix.speed - v);
                t0 = lastFix.tNs + (long) (fr * dt * 1e9);
                prevT = f.tNs;
                prevV = v;
                prevD = 0.5 * (from + v) * (f.tNs - t0) / 1e9;
                state = 2;
            } else {
                state = 0;
            }
            return;
        }
        if (state == 2) {
            double dt = (f.tNs - prevT) / 1e9;
            double d1 = prevD + 0.5 * (prevV + v) * dt;
            if (v < 0.4) {
                double fr = prevV > 0 ? 1 : 0;
                stopTime = (prevT - t0) / 1e9 + (prevV / Math.max(0.5, (prevV - v) / Math.max(dt, 0.01))) * fr;
                stopDist = d1;
                state = 3;
                return;
            }
            if (v > prevV + 1.5) {
                state = 1; // ha ripreso a correre: annulla
                return;
            }
            prevT = f.tNs;
            prevV = v;
            prevD = d1;
        }
    }

    // ------------------------------------------------------------------ interfaccia

    private void updateState() {
        String s;
        if (!gps.hasRecentFix()) {
            s = "In attesa del segnale GPS…";
        } else if (mode == 0) {
            s = state == 1 ? "Pronto: parti quando vuoi" : state == 2 ? "In corsa…"
                    : state == 3 ? "Prova conclusa" : "Fermati per armare la prova";
        } else if (mode == 1) {
            s = state == 1 ? "Pronto: accelera oltre " + (int) param(fromEt, 60) + " km/h"
                    : state == 2 ? "Misuro…" : state == 3 ? "Prova conclusa" : "Scendi sotto "
                    + (int) param(fromEt, 60) + " km/h per armare";
        } else {
            s = state == 1 ? "Pronto: frena da " + (int) param(fromEt, 100) + " km/h"
                    : state == 2 ? "Frenata in corso…" : state == 3 ? "Prova conclusa"
                    : "Superare " + (int) param(fromEt, 100) + " km/h per armare";
        }
        stateTv.setText(s);
    }

    private void updateResults() {
        StringBuilder sb = new StringBuilder();
        if (mode == 0) {
            for (int i = 0; i < SP.length; i++) {
                if (!Double.isNaN(spT[i])) {
                    sb.append(line(SP_NAME[i], "s" + i, spT[i], null));
                }
            }
            if (!Double.isNaN(spT[1]) && !Double.isNaN(spT[3])) {
                sb.append(line("100–200 km/h", "s100200", spT[3] - spT[1], null));
            }
            for (int j = 0; j < DS.length; j++) {
                if (!Double.isNaN(dsT[j])) {
                    sb.append(line(DS_NAME[j], "d" + j, dsT[j],
                            j == 1 && !Double.isNaN(trap) ? String.format(Locale.ITALY, "  @ %.0f km/h", trap) : null));
                }
            }
        } else if (mode == 1 && !Double.isNaN(rollTime)) {
            sb.append(String.format(Locale.ITALY, "%.0f–%.0f km/h: %.2f s\nSpazio percorso: %.0f m\n",
                    param(fromEt, 60), param(toEt, 100), rollTime, rollDist));
        } else if (mode == 2 && !Double.isNaN(stopTime)) {
            sb.append(String.format(Locale.ITALY, "%.0f–0 km/h: %.2f s\nSpazio di frenata: %.1f m\n",
                    param(fromEt, 100), stopTime, stopDist));
        }
        if (sb.length() == 0) {
            sb.append("Nessun risultato ancora.");
            if (vmax > 0) {
                sb.append(String.format(Locale.ITALY, "\nVelocita' massima: %.0f km/h", kmh(vmax)));
            }
        }
        resultTv.setText(sb.toString().trim());
    }

    private String line(String name, String key, double t, String extra) {
        double best = prefs.getFloat("best_" + key, 0f);
        String star = (best > 0 && t < best - 0.004 && state == 3) ? "  ★ record" : "";
        String b = best > 0 ? String.format(Locale.ITALY, "   (migliore %.2f)", best) : "";
        return String.format(Locale.ITALY, "%s: %.2f s%s%s%s\n", name, t, extra == null ? "" : extra, star, b);
    }

    private void saveBests() {
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < SP.length; i++) {
            put(ed, "s" + i, spT[i]);
        }
        if (!Double.isNaN(spT[1]) && !Double.isNaN(spT[3])) {
            put(ed, "s100200", spT[3] - spT[1]);
        }
        for (int j = 0; j < DS.length; j++) {
            put(ed, "d" + j, dsT[j]);
        }
        ed.apply();
    }

    private void put(SharedPreferences.Editor ed, String key, double t) {
        if (Double.isNaN(t)) {
            return;
        }
        float best = prefs.getFloat("best_" + key, 0f);
        if (best <= 0 || t < best) {
            ed.putFloat("best_" + key, (float) t);
        }
    }
}
