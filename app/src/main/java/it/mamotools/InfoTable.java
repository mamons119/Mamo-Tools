package it.mamotools;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tabella "Originale / Esportato" usata dai tool di esportazione. */
final class InfoTable extends LinearLayout {
    private final TextView[][] cells;

    InfoTable(Context c, String[] rows) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Ui.round(c, Ui.CARD2, 12));
        setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        cells = new TextView[rows.length + 1][3];
        for (int r = 0; r <= rows.length; r++) {
            LinearLayout row = new LinearLayout(c);
            for (int k = 0; k < 3; k++) {
                String t = k == 0 ? (r == 0 ? "" : rows[r - 1]) : (r == 0 ? (k == 1 ? "Originale" : "Esportato") : "—");
                TextView tv = Ui.text(c, t, 12, r == 0 || k == 0 ? Ui.DIM : Ui.TEXT, r == 0);
                if (k > 0) {
                    tv.setGravity(Gravity.END);
                }
                cells[r][k] = tv;
                row.addView(tv, new LinearLayout.LayoutParams(0, Ui.WRAP, k == 0 ? 1.0f : 1.1f));
            }
            addView(row, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, r == 0 ? 0 : 6));
        }
    }

    /** col 1 = originale, 2 = esportato; null = trattini. */
    void set(int col, String[] v) {
        for (int r = 1; r < cells.length; r++) {
            cells[r][col].setText(v == null || r - 1 >= v.length ? "—" : v[r - 1]);
        }
    }
}
