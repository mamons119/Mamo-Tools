package it.mamotools;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Calcolo della miscela olio-benzina per motori a 2 tempi. */
public class MixCalcActivity extends Activity {

    private static final double[] PCT = {1, 1.5, 2, 2.5, 3, 4, 5, 6};
    private EditText fuelEt, pctEt;
    private TextView big, bigSub, details;
    private TextView[] fuelChips, pctChips;
    private static final double[] FUEL = {1, 2, 5, 10};
    private boolean internal = false;
    private CupView cup;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "Calcolo miscela",
                "Olio da aggiungere alla benzina per i motori a 2 tempi.");

        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.section(this, "Olio da aggiungere"));
        LinearLayout resRow = new LinearLayout(this);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        cup = new CupView(this);
        resRow.addView(cup, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 240)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 14), 0, 0, 0);
        big = Ui.text(this, "—", 34, Ui.ACCENT, true);
        col.addView(big, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        bigSub = Ui.text(this, "", 15, Ui.TEXT, false);
        col.addView(bigSub, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        details = Ui.text(this, "Inserisci i litri di benzina e la percentuale.", 13, Ui.DIM, false);
        details.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(details, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        resRow.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        c3.addView(resRow, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(c3);

        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.section(this, "Benzina"));
        fuelEt = field("Litri di benzina");
        c1.addView(fuelEt);
        LinearLayout r1 = new LinearLayout(this);
        fuelChips = new TextView[FUEL.length];
        for (int i = 0; i < FUEL.length; i++) {
            final double v = FUEL[i];
            fuelChips[i] = Ui.chip(this, fmt(v, 0) + " L");
            fuelChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    internal = true;
                    fuelEt.setText(fmt(v, 0));
                    internal = false;
                    update();
                }
            });
            r1.addView(fuelChips[i], Ui.lp(this, 0, Ui.dp(this, 40), i == 0 ? 0 : 8, 0, 1f));
        }
        c1.addView(r1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        root.addView(c1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.section(this, "Percentuale di miscela"));
        pctEt = field("Percentuale (es. 2)");
        c2.addView(pctEt);
        pctChips = new TextView[PCT.length];
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this);
            for (int k = 0; k < 4; k++) {
                final int i = row * 4 + k;
                pctChips[i] = Ui.chip(this, fmt(PCT[i], 1) + "%");
                pctChips[i].setTextSize(13);
                pctChips[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        internal = true;
                        pctEt.setText(fmt(PCT[i], 1));
                        internal = false;
                        update();
                    }
                });
                r.addView(pctChips[i], Ui.lp(this, 0, Ui.dp(this, 40), k == 0 ? 0 : 8, 0, 1f));
            }
            c2.addView(r, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
        root.addView(c2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        TextView note = Ui.text(this, "La percentuale e' riferita alla benzina: 2% significa 20 ml di olio "
                + "ogni litro (rapporto 1:50). Controlla sempre la percentuale indicata nel libretto del "
                + "motore o sulla confezione dell'olio.", 12, Ui.DIM, false);
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        root.addView(note);

        TextWatcher tw = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!internal) {
                    update();
                }
            }
        };
        fuelEt.addTextChangedListener(tw);
        pctEt.addTextChangedListener(tw);
        fuelEt.setText("5");
        pctEt.setText("2");
        update();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.DIM);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(20);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setBackground(Ui.round(this, Ui.BG, 12));
        e.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        return e;
    }

    private static String fmt(double v, int maxDec) {
        String s = String.format(Locale.ITALY, "%." + maxDec + "f", v);
        if (maxDec > 0 && s.contains(",")) {
            s = s.replaceAll("0+$", "").replaceAll(",$", "");
        }
        return s;
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private void update() {
        double fuel = parse(fuelEt.getText().toString());
        double pct = parse(pctEt.getText().toString());
        for (int i = 0; i < FUEL.length; i++) {
            Ui.styleChip(this, fuelChips[i], fuel == FUEL[i]);
        }
        for (int i = 0; i < PCT.length; i++) {
            Ui.styleChip(this, pctChips[i], pct == PCT[i]);
        }
        if (Double.isNaN(fuel) || Double.isNaN(pct) || fuel <= 0 || pct <= 0) {
            big.setText("—");
            bigSub.setText("");
            details.setText("Inserisci i litri di benzina e la percentuale.");
            cup.setMl(0);
            return;
        }
        if (pct > 25) {
            big.setText("—");
            bigSub.setText("");
            details.setText("La percentuale sembra troppo alta: controlla il valore.");
            cup.setMl(0);
            return;
        }
        double ml = fuel * 1000.0 * pct / 100.0;
        cup.setMl((float) ml);
        big.setText(fmt(ml, ml < 10 ? 1 : 0) + " ml");
        bigSub.setText(ml >= 1000 ? fmt(ml / 1000.0, 2) + " litri di olio"
                : fmt(ml / 1000.0, 3) + " litri di olio");
        details.setText("Rapporto 1:" + fmt(100.0 / pct, 1) + "  ·  " + fmt(10.0 * pct, 1)
                + " ml di olio per ogni litro\nMiscela totale: " + fmt(fuel + ml / 1000.0, 2) + " litri");
    }

    /** Misurino graduato che si riempie d'olio; la scala si adatta alla quantita'. */
    private static final class CupView extends View {
        private static final float[] SCALES = {20, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 20000};
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final float d;
        private float targetMl = 0f, shownMl = 0f, scaleMax = 100f, shownMax = 100f;
        private ValueAnimator anim;

        CupView(android.content.Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        void setMl(float ml) {
            targetMl = ml;
            float need = ml / 0.85f;
            float sm = SCALES[SCALES.length - 1];
            for (float sc : SCALES) {
                if (sc >= need) {
                    sm = sc;
                    break;
                }
            }
            final float fromMl = shownMl, fromMax = shownMax;
            scaleMax = sm;
            if (anim != null) {
                anim.cancel();
            }
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(500);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float f = (float) a.getAnimatedValue();
                    shownMl = fromMl + (targetMl - fromMl) * f;
                    shownMax = fromMax + (scaleMax - fromMax) * f;
                    invalidate();
                }
            });
            anim.start();
        }

        private String lab(float ml) {
            if (scaleMax >= 1000) {
                float l = ml / 1000f;
                return (l == Math.floor(l) ? String.valueOf((int) l) : String.format(Locale.US, "%.1f", l)) + " L";
            }
            return (int) ml + "";
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float left = 34f * d, right = w - 8f * d, top = 14f * d, bottom = h - 8f * d;
            float rad = 10f * d;
            // vetro
            r.set(left, top, right, bottom);
            path.reset();
            path.moveTo(left, top);
            path.lineTo(right, top);
            path.lineTo(right, bottom - rad);
            path.quadTo(right, bottom, right - rad, bottom);
            path.lineTo(left + rad, bottom);
            path.quadTo(left, bottom, left, bottom - rad);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x14FFFFFF);
            c.drawPath(path, p);
            // olio
            float frac = Math.max(0f, Math.min(1f, shownMl / shownMax));
            float usable = (bottom - 4f * d) - (top + 6f * d);
            float yLevel = (bottom - 4f * d) - frac * usable;
            if (shownMl > 0.01f) {
                c.save();
                c.clipPath(path);
                p.setShader(new LinearGradient(0, yLevel, 0, bottom, 0xFFFFD54F, 0xFFFF8F00, Shader.TileMode.CLAMP));
                p.setStyle(Paint.Style.FILL);
                c.drawRect(left, yLevel, right, bottom, p);
                p.setShader(null);
                // superficie
                p.setColor(0x66FFFFFF);
                r.set(left, yLevel - 3f * d, right, yLevel + 3f * d);
                c.drawOval(r, p);
                c.restore();
            }
            // graduazioni
            t.setColor(0xFF9AA3B2);
            t.setTextSize(10f * d);
            t.setTextAlign(Paint.Align.RIGHT);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            float majorStep = shownMax / 5f;
            float minorStep = shownMax / 20f;
            for (int i = 0; i <= 20; i++) {
                float v = i * minorStep;
                float y = (bottom - 4f * d) - v / shownMax * usable;
                boolean major = i % 4 == 0;
                p.setStrokeWidth((major ? 1.6f : 1f) * d);
                p.setColor(major ? 0xFFECEFF4 : 0x99ECEFF4);
                float len = (major ? 14f : 8f) * d;
                c.drawLine(left, y, left + len, y, p);
                if (major && i > 0) {
                    c.drawText(lab(v), left - 4f * d, y + 3.5f * d, t);
                }
            }
            // contorno del bicchiere
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * d);
            p.setColor(0xCCECEFF4);
            c.drawPath(path, p);
            // indicatore del livello
            if (shownMl > 0.01f) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF4DD0E1);
                Path tri = new Path();
                tri.moveTo(right + 6f * d, yLevel);
                tri.lineTo(right - 4f * d, yLevel - 5f * d);
                tri.lineTo(right - 4f * d, yLevel + 5f * d);
                tri.close();
                c.drawPath(tri, p);
            }
        }
    }
}
