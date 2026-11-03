package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Angolo di piega e G-meter (accelerazione, frenata, forza laterale) in tempo reale. */
public class LeanActivity extends TelemBase {

    private GGView gg;
    private LeanGauge gauge;
    private TextView leanTv, srcTv, gTv, maxTv;
    private TextView[] mountChips;
    private TextView invChip;
    private float maxL, maxR;

    @Override
    protected String title() {
        return "Piega e G-meter";
    }

    @Override
    protected String subtitle() {
        return "Angolo di piega della moto e forze G in curva, frenata e accelerazione.";
    }

    @Override
    protected void build(LinearLayout root) {
        LinearLayout mc = Ui.card(this);
        mc.addView(Ui.section(this, "Come hai montato il telefono"));
        LinearLayout r = new LinearLayout(this);
        String[] n = {"In piedi (schermo verso di me)", "Disteso (schermo in alto)"};
        mountChips = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final int m = i;
            mountChips[i] = Ui.chip(this, n[i]);
            mountChips[i].setTextSize(12);
            mountChips[i].setSingleLine(false);
            mountChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    imu.mount = m;
                    saveMount();
                    styleMount();
                }
            });
            r.addView(mountChips[i], Ui.lp(this, 0, Ui.dp(this, 52), i == 0 ? 0 : 8, 0, 1f));
        }
        mc.addView(r);
        invChip = Ui.chip(this, "Inverti il senso di marcia (se accelerazione e frenata sono scambiate)");
        invChip.setTextSize(12);
        invChip.setSingleLine(false);
        invChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                imu.invert = !imu.invert;
                saveMount();
                styleMount();
            }
        });
        mc.addView(invChip, Ui.lp(this, Ui.MATCH, Ui.dp(this, 48), 0, 8));
        root.addView(mc);

        LinearLayout lc = Ui.card(this);
        lc.addView(Ui.section(this, "Piega"));
        gauge = new LeanGauge(this);
        lc.addView(gauge, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 170)));
        leanTv = Ui.text(this, "0°", 40, Ui.TEXT, true);
        leanTv.setGravity(Gravity.CENTER);
        lc.addView(leanTv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        maxTv = Ui.text(this, "", 14, Ui.ACCENT, true);
        maxTv.setGravity(Gravity.CENTER);
        lc.addView(maxTv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        srcTv = Ui.text(this, "", 11, Ui.DIM, false);
        srcTv.setGravity(Gravity.CENTER);
        srcTv.setPadding(0, Ui.dp(this, 6), 0, 0);
        lc.addView(srcTv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(lc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout gc = Ui.card(this);
        gc.addView(Ui.section(this, "Forze G"));
        gg = new GGView(this);
        gc.addView(gg, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 260)));
        gTv = Ui.text(this, "", 14, Ui.TEXT, false);
        gTv.setGravity(Gravity.CENTER);
        gTv.setLineSpacing(0, 1.25f);
        gTv.setPadding(0, Ui.dp(this, 8), 0, 0);
        gc.addView(gTv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        gc.addView(Ui.btn(this, "Azzera i massimi", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                maxL = maxR = 0;
                gg.clearMax();
                gauge.maxL = gauge.maxR = 0;
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 12));
        root.addView(gc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        TextView note = Ui.text(this, "Con GPS e giroscopio la piega e' calcolata dalla velocita' e dalla "
                + "rotazione in curva (indipendente da come e' montato il telefono, precisa nelle curve a "
                + "regime). Senza GPS e' l'inclinazione apparente. Fissa bene il telefono alla moto.",
                11, Ui.DIM, false);
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(note);
        styleMount();
    }

    private void styleMount() {
        for (int i = 0; i < 2; i++) {
            Ui.styleChip(this, mountChips[i], imu.mount == i);
        }
        Ui.styleChip(this, invChip, imu.invert);
    }

    @Override
    protected void onTick() {
        float lean = imu.leanDeg, al = imu.aLong, at = imu.aLat;
        if (lean > 0) {
            maxL = Math.max(maxL, lean);
        } else {
            maxR = Math.max(maxR, -lean);
        }
        gauge.lean = lean;
        gauge.maxL = maxL;
        gauge.maxR = maxR;
        gauge.invalidate();
        gg.push(al, at);
        leanTv.setText(String.format(Locale.ITALY, "%.0f° %s", Math.abs(lean),
                Math.abs(lean) < 1 ? "" : lean > 0 ? "SX" : "DX"));
        maxTv.setText(String.format(Locale.ITALY, "max SX %.0f°   ·   max DX %.0f°", maxL, maxR));
        srcTv.setText(imu.leanFromGps ? "calcolata con GPS + giroscopio"
                : imu.hasGyro ? "stima apparente (servono GPS e movimento sopra 9 km/h)" : "stima apparente (nessun giroscopio)");
        gTv.setText(String.format(Locale.ITALY, "Ora: %s %.2f G   ·   curva %s %.2f G\n"
                        + "Massimi: accel. %.2f G   frenata %.2f G   curva %.2f G",
                al >= 0 ? "accel." : "frenata", Math.abs(al), at >= 0 ? "SX" : "DX", Math.abs(at),
                gg.maxAccel, gg.maxBrake, Math.max(gg.maxLeft, gg.maxRight)));
    }

    /** Quadrante a semicerchio da -60 a +60 gradi (sinistra a sinistra). */
    static final class LeanGauge extends View {
        float lean, maxL, maxR;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float d;

        LeanGauge(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h - 10 * d;
            float R = Math.min(w / 2f - 12 * d, h - 20 * d);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10 * d);
            p.setColor(0xFF262B35);
            rect.set(cx - R, cy - R, cx + R, cy + R);
            c.drawArc(rect, 210, 120, false, p);
            // zone di piega
            p.setStrokeWidth(10 * d);
            p.setColor(0xFF4DD0E1);
            float a = Math.max(-60, Math.min(60, lean));
            if (a >= 0) {
                c.drawArc(rect, 270 - a, a, false, p);
            } else {
                c.drawArc(rect, 270, -a, false, p);
            }
            // massimi
            p.setStrokeWidth(3 * d);
            p.setColor(0xFFFFB74D);
            for (float m : new float[]{Math.min(60, maxL), -Math.min(60, maxR)}) {
                if (m == 0) {
                    continue;
                }
                double ang = Math.toRadians(-90 - m);
                c.drawLine((float) (cx + Math.cos(ang) * (R - 16 * d)), (float) (cy + Math.sin(ang) * (R - 16 * d)),
                        (float) (cx + Math.cos(ang) * (R + 8 * d)), (float) (cy + Math.sin(ang) * (R + 8 * d)), p);
            }
            // tacche
            p.setStrokeWidth(2 * d);
            p.setColor(0xFF9AA3B2);
            p.setTextSize(10 * d);
            for (int deg = -60; deg <= 60; deg += 10) {
                double ang = Math.toRadians(-90 - deg);
                float r1 = R - (deg % 30 == 0 ? 26 : 20) * d, r2 = R - 14 * d;
                c.drawLine((float) (cx + Math.cos(ang) * r1), (float) (cy + Math.sin(ang) * r1),
                        (float) (cx + Math.cos(ang) * r2), (float) (cy + Math.sin(ang) * r2), p);
                if (deg % 30 == 0) {
                    String s = String.valueOf(Math.abs(deg));
                    p.setStyle(Paint.Style.FILL);
                    c.drawText(s, (float) (cx + Math.cos(ang) * (R - 40 * d)) - p.measureText(s) / 2f,
                            (float) (cy + Math.sin(ang) * (R - 40 * d)) + 4 * d, p);
                    p.setStyle(Paint.Style.STROKE);
                }
            }
            // lancetta
            double ang = Math.toRadians(-90 - a);
            p.setStrokeWidth(4 * d);
            p.setColor(0xFFECEFF4);
            c.drawLine(cx, cy, (float) (cx + Math.cos(ang) * (R - 18 * d)), (float) (cy + Math.sin(ang) * (R - 18 * d)), p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 8 * d, p);
        }
    }
}
