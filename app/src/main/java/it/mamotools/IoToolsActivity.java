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

/** Sezione "Input/Output": strumenti per audio in uscita e periferiche di ingresso. */
public class IoToolsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "Input/Output",
                "Audio in uscita e periferiche collegate al telefono.");
        root.addView(tile("🔊", "Volume Boost",
                "Amplifica il volume oltre il massimo, equalizzatore e bilanciamento sinistra/destra per le cuffie.",
                VolumeBoostActivity.class));
        root.addView(tile("⌨", "Keyboard bluetooth",
                "Assegna funzioni e simboli ai tasti di una tastiera Bluetooth, validi in tutto il telefono.",
                KeyboardMapActivity.class));
    }

    private View tile(String icon, String title, String desc, final Class<? extends Activity> target) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334DD0E1), Ui.round(this, Ui.CARD, 16), null));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(IoToolsActivity.this, target));
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
