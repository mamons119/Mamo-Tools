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
    private LinearLayout[] mountOpts;
    private PhoneIcon[] mountIcons;
    private TextView[] mountTitles, mountSubs;
    private android.widget.Switch invSwitch;
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
        mc.addView(Ui.section(this, "Montaggio del telefono"));
        mc.addView(Ui.text(this, "Scegli come hai fissato il telefono alla moto.", 12, Ui.DIM, false));
        LinearLayout r = new LinearLayout(this);
        String[] n = {"In piedi", "Disteso"};
        String[] n2 = {"schermo verso di me", "schermo in alto"};
        mountOpts = new LinearLayout[2];
        mountIcons = new PhoneIcon[2];
        mountTitles = new TextView[2];
        mountSubs = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final int m = i;
            LinearLayout o = new LinearLayout(this);
            o.setOrientation(LinearLayout.VERTICAL);
            o.setGravity(Gravity.CENTER_HORIZONTAL);
            o.setClickable(true);
            o.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
            mountIcons[i] = new PhoneIcon(this, i == 0);
            o.addView(mountIcons[i], new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
            mountTitles[i] = Ui.text(this, n[i], 14, Ui.TEXT, true);
            mountTitles[i].setGravity(Gravity.CENTER);
            o.addView(mountTitles[i], Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
            mountSubs[i] = Ui.text(this, n2[i], 11, Ui.DIM, false);
            mountSubs[i].setGravity(Gravity.CENTER);
            o.addView(mountSubs[i], new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            o.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    imu.mount = m;
                    saveMount();
                    styleMount();
                }
            });
            mountOpts[i] = o;
            r.addView(o, Ui.lp(this, 0, Ui.WRAP, i == 0 ? 0 : 10, 0, 1f));
        }
        mc.addView(r, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        View sep = new View(this);
        sep.setBackgroundColor(0x22FFFFFF);
        mc.addView(sep, Ui.lp(this, Ui.MATCH, 1, 0, 14));
        LinearLayout ir = new LinearLayout(this);
        ir.setGravity(Gravity.CENTER_VERTICAL);
        ir.setPadding(0, Ui.dp(this, 12), 0, 0);
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.addView(Ui.text(this, "Inverti il senso di marcia", 14, Ui.TEXT, true));
        it.addView(Ui.text(this, "Attivalo se accelerazione e frenata risultano scambiate.", 11, Ui.DIM, false));
        ir.addView(it, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        invSwitch = new android.widget.Switch(this);
        invSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (imu.invert != on) {
                    imu.invert = on;
                    saveMount();
                }
                styleMount();
            }
        });
        ir.addView(invSwitch, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        mc.addView(ir);
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
        lc.addView(Ui.btn(this, "Azzera i massimi", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                maxL = maxR = 0;
                gauge.maxL = gauge.maxR = 0;
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 12));
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
                gg.clearMax();
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
            boolean on = imu.mount == i;
            mountOpts[i].setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x44FFFFFF),
                    Ui.round(this, on ? Ui.ACCENT : Ui.CARD2, 14), null));
            mountIcons[i].setColor(on ? 0xFF000000 : Ui.TEXT);
            mountTitles[i].setTextColor(on ? 0xFF000000 : Ui.TEXT);
            mountSubs[i].setTextColor(on ? 0xCC000000 : Ui.DIM);
        }
        if (invSwitch.isChecked() != imu.invert) {
            invSwitch.setChecked(imu.invert);
        }
        invSwitch.setThumbTintList(android.content.res.ColorStateList.valueOf(imu.invert ? Ui.ACCENT : 0xFFB0B7C3));
        invSwitch.setTrackTintList(android.content.res.ColorStateList.valueOf(imu.invert ? 0x884DD0E1 : 0x66FFFFFF));
    }

    /** Icona del telefono: in piedi (verticale) oppure disteso (visto dall'alto in prospettiva). */
    static final class PhoneIcon extends View {
        private final boolean upright;
        private int color = Ui.TEXT;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        PhoneIcon(Context c, boolean upright) {
            super(c);
            this.upright = upright;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            c.scale(s, s);
            p.setColor(color);
            p.setStrokeWidth(1.8f);
            path.reset();
            if (upright) {
                // telefono in piedi: corpo, altoparlante e pulsante
                c.drawRoundRect(6.5f, 2.5f, 17.5f, 21.5f, 2.6f, 2.6f, p);
                c.drawLine(10.5f, 5f, 13.5f, 5f, p);
                c.drawLine(10.5f, 19f, 13.5f, 19f, p);
            } else {
                // telefono disteso: parallelogramma con schermo
                path.moveTo(7.5f, 7f);
                path.lineTo(21.5f, 7f);
                path.lineTo(16.5f, 17f);
                path.lineTo(2.5f, 17f);
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(8.8f, 9.6f);
                path.lineTo(18.4f, 9.6f);
                path.lineTo(15.4f, 14.4f);
                path.lineTo(5.8f, 14.4f);
                path.close();
                p.setStrokeWidth(1.2f);
                p.setAlpha(150);
                c.drawPath(path, p);
                p.setAlpha(255);
            }
            c.restore();
        }
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
