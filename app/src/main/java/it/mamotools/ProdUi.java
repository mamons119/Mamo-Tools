package it.mamotools;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Piccoli componenti grafici condivisi dalle schede di Produzioni. */
final class ProdUi {

    interface Fmt {
        String f(int v);
    }

    interface IntCb {
        void on(int v);
    }

    private ProdUi() {
    }

    /** Riga con etichetta, valore e barra di scorrimento. */
    static final class Slider {
        final LinearLayout view;
        final SeekBar sb;
        final TextView val;
        private final int min;
        private final Fmt fmt;
        private boolean quiet;
        Runnable release;

        Slider(Context c, String label, int min, int max, int value, Fmt fmt, final IntCb cb) {
            this.min = min;
            this.fmt = fmt;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(c);
            TextView t = Ui.text(c, label, 13, Ui.DIM, false);
            top.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            val = Ui.text(c, fmt.f(value), 13, Ui.TEXT, true);
            top.addView(val);
            view.addView(top);
            sb = Ui.seek(c, new Ui.SimpleSeek() {
                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    if (release != null) {
                        release.run();
                    }
                }

                @Override
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    val.setText(Slider.this.fmt.f(p + Slider.this.min));
                    if (user && !quiet) {
                        cb.on(p + Slider.this.min);
                    }
                }
            });
            sb.setMax(max - min);
            sb.setProgress(value - min);
            view.addView(sb, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(c, 30)));
        }

        void set(int v) {
            quiet = true;
            sb.setProgress(v - min);
            val.setText(fmt.f(v));
            quiet = false;
        }
    }

    static final Fmt PCT = new Fmt() {
        @Override
        public String f(int v) {
            return v + "%";
        }
    };

    /** Un'etichetta cliccabile a forma di pulsantino (stile chip dell'app). */
    static TextView chip(Context c, String label, boolean on, View.OnClickListener l) {
        TextView t = Ui.chip(c, label);
        t.setTextSize(13);
        t.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        Ui.styleChip(c, t, on);
        t.setOnClickListener(l);
        return t;
    }

    static void colorChip(Context c, TextView t, boolean on, int color) {
        t.setBackground(Ui.round(c, on ? color : Ui.CARD2, 12));
        t.setTextColor(on ? 0xFF0F1115 : Ui.TEXT);
    }

    static void pickList(Context c, String title, String[] items, final IntCb cb) {
        new AlertDialog.Builder(c).setTitle(title).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                cb.on(which);
            }
        }).show();
    }

    static void askNumber(Context c, String title, int cur, final int min, final int max, final IntCb cb) {
        final EditText et = new EditText(c);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(cur));
        et.setSelectAllOnFocus(true);
        et.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(c).setTitle(title).setView(et)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            int v = Integer.parseInt(et.getText().toString().trim());
                            cb.on(Math.max(min, Math.min(max, v)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }).show();
    }

    static String letter(int pattern) {
        return String.valueOf((char) ('A' + pattern));
    }

    static String time(double sec) {
        int s = (int) Math.round(sec);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
