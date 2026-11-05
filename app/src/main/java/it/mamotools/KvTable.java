package it.mamotools;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tabella a due colonne (voce / valore); una riga con un solo elemento e' il titolo di un gruppo. */
final class KvTable extends LinearLayout {

    KvTable(Context c) {
        super(c);
        setOrientation(VERTICAL);
    }

    void set(String[][] rows) {
        removeAllViews();
        Context c = getContext();
        for (int i = 0; i < rows.length; i++) {
            LinearLayout r = new LinearLayout(c);
            if (rows[i].length < 2) {
                TextView h = Ui.text(c, rows[i][0], 11, Ui.ACCENT, true);
                h.setAllCaps(true);
                h.setLetterSpacing(0.06f);
                r.setPadding(0, i == 0 ? 0 : Ui.dp(c, 14), 0, Ui.dp(c, 4));
                r.addView(h, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            } else {
                r.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
                TextView k = Ui.text(c, rows[i][0], 13, Ui.DIM, false);
                TextView v = Ui.text(c, rows[i][1], 13, Ui.TEXT, true);
                v.setGravity(Gravity.END);
                r.addView(k, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1.3f);
                vp.leftMargin = Ui.dp(c, 10);
                r.addView(v, vp);
            }
            addView(r, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
    }
}
