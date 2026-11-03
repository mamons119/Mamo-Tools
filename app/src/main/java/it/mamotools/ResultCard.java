package it.mamotools;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** Scheda con avanzamento, messaggio di stato e pulsante "Apri" riusata dai tool. Thread-safe. */
final class ResultCard {
    final LinearLayout view;
    private final Activity act;
    private final TextView status;
    private final ProgressBar bar;
    private final Button open;
    private Uri uri;
    private String mime;

    ResultCard(final Activity a) {
        this(a, false);
    }

    /** flat = senza sfondo e margini propri, per inserirla dentro un'altra scheda. */
    ResultCard(final Activity a, boolean flat) {
        act = a;
        if (flat) {
            view = new LinearLayout(a);
            view.setOrientation(LinearLayout.VERTICAL);
        } else {
            view = Ui.card(a);
        }
        view.setVisibility(View.GONE);
        status = Ui.text(a, "", 14, Ui.TEXT, false);
        bar = Ui.bar(a);
        open = Ui.btn(a, "Apri", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openIt();
            }
        });
        open.setVisibility(View.GONE);
        view.addView(status);
        view.addView(bar, Ui.lp(a, Ui.MATCH, Ui.dp(a, 8), 0, 10));
        view.addView(open, Ui.lp(a, Ui.MATCH, Ui.dp(a, 46), 0, 12));
    }

    void start(final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uri = null;
                view.setVisibility(View.VISIBLE);
                open.setVisibility(View.GONE);
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                status.setTextColor(Ui.TEXT);
                status.setText(msg);
            }
        });
    }

    void progress(final String msg, final int pct) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.setVisibility(View.VISIBLE);
                bar.setVisibility(View.VISIBLE);
                if (pct < 0) {
                    bar.setIndeterminate(true);
                } else {
                    bar.setIndeterminate(false);
                    bar.setProgress(pct);
                }
                status.setText(msg);
            }
        });
    }

    /** u null: nessun pulsante singolo; per piu' file mostra "Apri Download". */
    void ok(final String msg, final Uri u, final String m, final String openLabel) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uri = u;
                mime = m;
                view.setVisibility(View.VISIBLE);
                bar.setVisibility(View.GONE);
                status.setTextColor(Ui.OK);
                status.setText(msg);
                open.setText(openLabel);
                open.setVisibility(View.VISIBLE);
            }
        });
    }

    void error(final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.setVisibility(View.VISIBLE);
                bar.setVisibility(View.GONE);
                open.setVisibility(View.GONE);
                status.setTextColor(Ui.DANGER);
                status.setText(msg);
            }
        });
    }

    void hide() {
        view.setVisibility(View.GONE);
    }

    private void openIt() {
        try {
            Intent i;
            if (uri == null) {
                i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            } else {
                i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "Nessuna app per aprire il file: lo trovi nella cartella MamoTools.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
