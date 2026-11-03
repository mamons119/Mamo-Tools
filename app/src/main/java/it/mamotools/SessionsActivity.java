package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Elenco delle sessioni di telemetria registrate. */
public class SessionsActivity extends Activity {

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Ui.screen(this, "Sessioni salvate", "Tocca una sessione per rivederla.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        File[] fs = new File(getFilesDir(), "sessions").listFiles();
        if (fs == null || fs.length == 0) {
            TextView e = Ui.text(this, "Nessuna sessione registrata.\nUsa il Registratore per crearne una.", 14,
                    Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 30), 0, 0);
            root.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            return;
        }
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File c) {
                return Long.compare(c.lastModified(), a.lastModified());
            }
        });
        for (final File f : fs) {
            LinearLayout row = Ui.card(this);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(SessionsActivity.this, SessionViewActivity.class)
                            .putExtra("path", f.getAbsolutePath()));
                }
            });
            final TextView title = Ui.text(this, f.getName(), 16, Ui.TEXT, true);
            final TextView sub = Ui.text(this, FileUtil.humanSize(f.length()), 12, Ui.DIM, false);
            row.addView(title);
            row.addView(sub);
            root.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final SessionData d = SessionData.load(f);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                title.setText(d.name.isEmpty() ? f.getName() : d.name);
                                sub.setText(String.format(Locale.ITALY, "%.2f km · %s · max %.0f km/h", d.distM / 1000,
                                        SessionData.fmtTime(d.duration), d.vMax * 3.6));
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
    }
}
