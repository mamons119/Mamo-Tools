package it.mamotools;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "Calcolo miscela",
                "Olio da aggiungere alla benzina per i motori a 2 tempi.");

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
        root.addView(c1);

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

        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.section(this, "Olio da aggiungere"));
        big = Ui.text(this, "—", 40, Ui.ACCENT, true);
        big.setGravity(Gravity.CENTER);
        c3.addView(big, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        bigSub = Ui.text(this, "", 16, Ui.TEXT, false);
        bigSub.setGravity(Gravity.CENTER);
        c3.addView(bigSub, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        details = Ui.text(this, "Inserisci i litri di benzina e la percentuale.", 13, Ui.DIM, false);
        details.setGravity(Gravity.CENTER);
        details.setPadding(0, Ui.dp(this, 12), 0, 0);
        c3.addView(details, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(c3, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

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
            return;
        }
        if (pct > 25) {
            big.setText("—");
            bigSub.setText("");
            details.setText("La percentuale sembra troppo alta: controlla il valore.");
            return;
        }
        double ml = fuel * 1000.0 * pct / 100.0;
        big.setText(fmt(ml, ml < 10 ? 1 : 0) + " ml");
        bigSub.setText(ml >= 1000 ? fmt(ml / 1000.0, 2) + " litri di olio"
                : fmt(ml / 1000.0, 3) + " litri di olio");
        details.setText("Rapporto 1:" + fmt(100.0 / pct, 1) + "  ·  " + fmt(10.0 * pct, 1)
                + " ml di olio per ogni litro\nMiscela totale: " + fmt(fuel + ml / 1000.0, 2) + " litri");
    }
}
