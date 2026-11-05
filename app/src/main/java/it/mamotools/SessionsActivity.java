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

    private void confirmDelete(final File f, String name) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Eliminare la sessione?")
                .setMessage("\"" + name + "\" verrà eliminata definitivamente da questo telefono.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Elimina", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        onResume();
                    }
                }).show();
    }

    private void share(final File f, final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String base = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
                    if (base.isEmpty() || base.equals(f.getName())) {
                        base = f.getName().replace(".csv", "");
                    }
                    final String out = base + ".mamotel";
                    File dir = new File(getCacheDir(), "shared");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    FileUtil.copyFile(f, new File(dir, out));
                    final android.net.Uri u = android.net.Uri.parse("content://" + PdfShareProvider.AUTHORITY + "/"
                            + android.net.Uri.encode(out));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("application/octet-stream");
                            s.putExtra(Intent.EXTRA_STREAM, u);
                            s.putExtra(Intent.EXTRA_SUBJECT, name);
                            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(s, "Condividi sessione"));
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(SessionsActivity.this,
                                    "Condivisione non riuscita: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        File[] fs = new File(getFilesDir(), "sessions").listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File d, String n) {
                return n.endsWith(".csv");
            }
        });
        if (fs == null || fs.length == 0) {
            TextView e = Ui.text(this, "Nessuna sessione registrata.\nUsa \"Registra Onboard\" per crearne una.", 14,
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
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.addView(Ui.btn(this, "Condividi", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    share(f, title.getText().toString());
                }
            }), Ui.lp(this, 0, Ui.dp(this, 46), 0, 0, 1f));
            acts.addView(Ui.btn(this, "Elimina", Ui.CARD2, Ui.DANGER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(f, title.getText().toString());
                }
            }), Ui.lp(this, 0, Ui.dp(this, 46), 8, 0, 1f));
            row.addView(acts, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
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
                                sub.setText(String.format(Locale.ITALY, "%s%.2f km · %s · max %.0f km/h",
                                        Vehicle.index(d.vehicle) < 0 ? "" : Vehicle.label(d.vehicle) + " · ", d.distM / 1000,
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
