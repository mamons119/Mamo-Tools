package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Menu degli "Strumenti di Misura": per aggiungerne uno basta un'altra riga tile(). */
public class MeasureToolsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "Strumenti di Misura",
                "Piccoli strumenti pratici sempre a portata di mano.");
        root.addView(tile("⛽", "Calcolo miscela",
                "Quanto olio mettere nella benzina per i motori a 2 tempi", MixCalcActivity.class));
        root.addView(tile("📏", "Righello",
                "Righello a grandezza reale in centimetri e pollici", RulerActivity.class));
        root.addView(tile("🏁", "Telemetria e prestazioni",
                "GPS e sensori: 0–100, piega, G-meter, registratore con mappa", TelemetryActivity.class));
        root.addView(tile("🎯", "Livello",
                "Livella a bolla con il sensore di inclinazione del telefono", LevelActivity.class));
    }

    private View tile(String icon, String title, String desc, final Class<? extends Activity> target) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334DD0E1),
                Ui.round(this, Ui.CARD, 16), null));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MeasureToolsActivity.this, target));
            }
        });
        TextView ic = Ui.text(this, icon, 24, Ui.TEXT, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(this, Ui.BG, 100));
        t.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 6), 0);
        col.addView(Ui.text(this, title, 17, Ui.TEXT, true));
        col.addView(Ui.text(this, desc, 12, Ui.DIM, false));
        t.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        t.addView(Ui.text(this, "›", 26, Ui.DIM, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 10);
        t.setLayoutParams(lp);
        return t;
    }
}
