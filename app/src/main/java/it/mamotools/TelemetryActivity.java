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

/** Menu della telemetria: prestazioni, piega e G-meter, registra percorsi viaggio. */
public class TelemetryActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "Telemetria",
                "Per moto, auto e fuoristrada: GPS, accelerometro e giroscopio del telefono.");
        root.addView(tile("🏁", "Prestazioni", "0–100, 0–200, 100–200, 1/4 di miglio, ripresa e frenata",
                PerfActivity.class));
        root.addView(tile("🏍", "Piega e G-meter", "Angolo di piega, accelerazione, frenata e forza laterale",
                LeanActivity.class));
        root.addView(tile(new Icons.Helmet(this), "Registra Onboard",
                "Registra viaggio/corsa anche a schermo spento: percorso, velocità, G; poi rivedi mappa e grafici",
                RecorderActivity.class));
        TextView n = Ui.text(this, "Suggerimenti: fissa bene il telefono, tieni lo schermo acceso, usa il GPS "
                + "all'aperto.",
                12, Ui.DIM, false);
        n.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(n);
    }

    private View tile(String icon, String title, String desc, final Class<? extends Activity> target) {
        TextView ic = Ui.text(this, icon, 24, Ui.TEXT, false);
        ic.setGravity(Gravity.CENTER);
        return tile(ic, title, desc, target);
    }

    private View tile(View ic, String title, String desc, final Class<? extends Activity> target) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334DD0E1),
                Ui.round(this, Ui.CARD, 16), null));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(TelemetryActivity.this, target));
            }
        });
        ic.setBackground(Ui.round(this, Ui.BG, 100));
        ic.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
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
