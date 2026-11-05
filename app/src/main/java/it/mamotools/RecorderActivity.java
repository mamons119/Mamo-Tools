package it.mamotools;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * Registra percorsi viaggio: la registrazione vive in RecorderService (continua a schermo spento o app chiusa);
 * qui si sceglie il veicolo, si avvia/ferma e si rivedono i valori in tempo reale.
 */
public class RecorderActivity extends TelemBase {

    private static final int REQ_NOTIF = 78;

    private boolean wasRunning = false;
    private String vehicle = "";

    private Button startBtn, lineBtn, openBtn, saveBtn;
    private TextView tTime, tSpeed, tDist, tVmax, tLean, tG, lapsTv, infoTv;
    private TextView[] vehChips;
    private LinearLayout vehCard, doneCard;
    private EditText nameEdit, descEdit;
    private String donePath = null;
    private boolean metaDirty = false;

    @Override
    protected String title() {
        return "Registra percorsi viaggio";
    }

    @Override
    protected String subtitle() {
        return "Registra percorso, velocita' e forze G di un viaggio, anche a schermo spento, per rivederlo "
                + "dopo su mappa e grafici.";
    }

    @Override
    protected void build(LinearLayout root) {
        // ---- veicolo
        vehCard = Ui.card(this);
        vehCard.addView(Ui.section(this, "Veicolo"));
        LinearLayout vr = new LinearLayout(this);
        vehChips = new TextView[Vehicle.IDS.length];
        for (int i = 0; i < vehChips.length; i++) {
            final int k = i;
            SpannableString ss = new SpannableString(Vehicle.ICONS[i] + "\n" + Vehicle.LABELS[i]);
            ss.setSpan(new RelativeSizeSpan(2.1f), 0, Vehicle.ICONS[i].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView t = Ui.chip(this, "");
            t.setText(ss);
            t.setSingleLine(false);
            t.setMaxLines(4);
            t.setTextSize(10);
            t.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (RecorderService.running) {
                        return;
                    }
                    vehicle = Vehicle.IDS[k];
                    styleVehicles();
                }
            });
            vehChips[i] = t;
            vr.addView(t, Ui.lp(this, 0, Ui.dp(this, 78), i == 0 ? 0 : 6, 0, 1f));
        }
        vehCard.addView(vr);
        root.addView(vehCard);
        styleVehicles();

        // ---- avvio
        LinearLayout c = Ui.card(this);
        startBtn = Ui.btn(this, "●  Avvia registrazione", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (RecorderService.running) {
                    startService(new Intent(RecorderActivity.this, RecorderService.class)
                            .setAction(RecorderService.ACTION_STOP));
                } else {
                    tryStart();
                }
            }
        });
        startBtn.setTextSize(16);
        c.addView(startBtn, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 56)));
        infoTv = Ui.text(this, "La registrazione continua anche con lo schermo bloccato o fuori dall'app: "
                + "in alto compare una notifica con tempo, distanza e pulsante Stop. Per i migliori risultati "
                + "fissa il telefono e tieni attivo il GPS.", 12, Ui.DIM, false);
        infoTv.setPadding(0, Ui.dp(this, 10), 0, 0);
        c.addView(infoTv);
        root.addView(c, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout g1 = new LinearLayout(this);
        tTime = stat(g1, "Tempo", 0);
        tSpeed = stat(g1, "km/h", 8);
        root.addView(g1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        LinearLayout g2 = new LinearLayout(this);
        tDist = stat(g2, "Distanza", 0);
        tVmax = stat(g2, "Max km/h", 8);
        root.addView(g2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        LinearLayout g3 = new LinearLayout(this);
        tLean = stat(g3, "Piega max SX / DX", 0);
        tG = stat(g3, "G max", 8);
        root.addView(g3, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));

        LinearLayout lc = Ui.card(this);
        lc.addView(Ui.section(this, "Giri"));
        lineBtn = Ui.btn(this, "⚑  Imposta il traguardo qui", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(RecorderActivity.this, RecorderService.class)
                        .setAction(RecorderService.ACTION_GATE));
            }
        });
        lc.addView(lineBtn, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 46)));
        lapsTv = Ui.text(this, "Premi il pulsante mentre passi sul traguardo, nel verso di marcia: da "
                + "quel punto i giri vengono contati da soli.", 13, Ui.DIM, false);
        lapsTv.setPadding(0, Ui.dp(this, 10), 0, 0);
        lc.addView(lapsTv);
        root.addView(lc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        // ---- sessione appena registrata: nome, descrizione, apri
        doneCard = Ui.card(this);
        doneCard.addView(Ui.section(this, "Sessione appena registrata"));
        doneCard.addView(Ui.text(this, "Nome", 12, Ui.DIM, false));
        nameEdit = field("Nome della sessione", false);
        doneCard.addView(nameEdit, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        TextView dl = Ui.text(this, "Descrizione (facoltativa)", 12, Ui.DIM, false);
        dl.setPadding(0, Ui.dp(this, 12), 0, 0);
        doneCard.addView(dl);
        descEdit = field("Note sul viaggio, condizioni, meta…", true);
        doneCard.addView(descEdit, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        LinearLayout dr = new LinearLayout(this);
        saveBtn = Ui.btn(this, "Salva", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveMeta()) {
                    Toast.makeText(RecorderActivity.this, "Nome e descrizione salvati", Toast.LENGTH_SHORT).show();
                }
            }
        });
        dr.addView(saveBtn, Ui.lp(this, 0, Ui.dp(this, 48), 0, 0, 1f));
        openBtn = Ui.btn(this, "Apri sessione appena registrata", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (donePath != null) {
                    saveMeta();
                    startActivity(new Intent(RecorderActivity.this, SessionViewActivity.class)
                            .putExtra("path", donePath));
                }
            }
        });
        dr.addView(openBtn, Ui.lp(this, 0, Ui.dp(this, 48), 8, 0, 2f));
        doneCard.addView(dr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
        doneCard.setVisibility(View.GONE);
        root.addView(doneCard, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        root.addView(Ui.btn(this, "Sessioni salvate", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(RecorderActivity.this, SessionsActivity.class));
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 48), 0, 12));
        lineBtn.setEnabled(false);
    }

    private EditText field(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x669AA3B2);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(15);
        e.setBackground(Ui.round(this, Ui.BG, 12));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setSingleLine(true);
        }
        return e;
    }

    private TextView stat(LinearLayout row, String label, int left) {
        LinearLayout c = Ui.card(this);
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 12));
        TextView l = Ui.text(this, label, 11, Ui.DIM, false);
        TextView v = Ui.text(this, "—", 20, Ui.TEXT, true);
        c.addView(l);
        c.addView(v);
        row.addView(c, Ui.lp(this, 0, Ui.WRAP, left, 0, 1f));
        return v;
    }

    private void styleVehicles() {
        for (int i = 0; i < vehChips.length; i++) {
            Ui.styleChip(this, vehChips[i], Vehicle.IDS[i].equals(vehicle));
        }
    }

    // ------------------------------------------------------------------ avvio

    private void tryStart() {
        if (Vehicle.index(vehicle) < 0) {
            Toast.makeText(this, "Prima scegli il veicolo che stai usando.", Toast.LENGTH_LONG).show();
            vehCard.setBackground(Ui.round(this, 0xFF3A2328, 16));
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    vehCard.setBackground(Ui.round(RecorderActivity.this, Ui.CARD, 16));
                }
            }, 900);
            return;
        }
        if (!hasPerm() || !gps.enabled()) {
            Toast.makeText(this, "Prima concedi il permesso e attiva il GPS (vedi Stato dei sensori).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        doStart();
    }

    private void doStart() {
        saveMetaQuiet();
        donePath = null;
        doneCard.setVisibility(View.GONE);
        RecorderService.lastPath = null;
        startForegroundService(new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START).putExtra("vehicle", vehicle));
        wasRunning = true;
        applyState(true);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_NOTIF) {
            // senza notifiche il servizio funziona comunque (si vede nel gestore attivita')
            doStart();
            return;
        }
        super.onRequestPermissionsResult(req, perms, res);
    }

    private void applyState(boolean rec) {
        if (rec) {
            startBtn.setText("■  Ferma e salva");
            startBtn.setBackground(Ui.round(this, Ui.DANGER, 12));
        } else {
            startBtn.setText("●  Avvia registrazione");
            startBtn.setBackground(Ui.round(this, Ui.ACCENT, 12));
            lineBtn.setEnabled(false);
        }
        for (TextView t : vehChips) {
            t.setAlpha(rec ? 0.55f : 1f);
        }
    }

    // ------------------------------------------------------------------ sessione conclusa

    private void showDone(String path, String defName) {
        donePath = path;
        nameEdit.setText(defName);
        descEdit.setText("");
        metaDirty = false;
        doneCard.setVisibility(View.VISIBLE);
    }

    private boolean saveMeta() {
        if (donePath == null) {
            return false;
        }
        try {
            String nm = nameEdit.getText().toString().trim();
            SessionData.updateMeta(new File(donePath), nm.isEmpty() ? RecorderService.lastName : nm,
                    descEdit.getText().toString());
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Non riesco a salvare: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void saveMetaQuiet() {
        if (donePath != null) {
            try {
                String nm = nameEdit.getText().toString().trim();
                SessionData.updateMeta(new File(donePath), nm.isEmpty() ? RecorderService.lastName : nm,
                        descEdit.getText().toString());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveMetaQuiet();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean rec = RecorderService.running;
        if (rec) {
            vehicle = RecorderService.vehicle;
            styleVehicles();
        } else if (RecorderService.lastPath != null && donePath == null) {
            showDone(RecorderService.lastPath, RecorderService.lastName);
        }
        wasRunning = rec;
        applyState(rec);
    }

    @Override
    protected boolean shouldStopOnPause() {
        return true;
    }

    // ------------------------------------------------------------------ aggiornamento

    @Override
    protected void onFix(GpsSource.Fix f) {
        if (!RecorderService.running) {
            tSpeed.setText(String.valueOf(Math.round(f.speed * 3.6)));
        }
    }

    @Override
    protected void onTick() {
        boolean rec = RecorderService.running;
        if (wasRunning && !rec) {
            wasRunning = false;
            applyState(false);
            if (RecorderService.lastPath != null) {
                showDone(RecorderService.lastPath, RecorderService.lastName);
            }
        } else if (!wasRunning && rec) {
            wasRunning = true;
            vehicle = RecorderService.vehicle;
            styleVehicles();
            applyState(true);
        }
        if (!rec) {
            return;
        }
        long el = SystemClock.elapsedRealtime() - RecorderService.startElapsedMs;
        tTime.setText(SessionData.fmtTime(el / 1000.0));
        tSpeed.setText(RecorderService.hasFix ? String.valueOf(Math.round(RecorderService.speed * 3.6)) : "—");
        tDist.setText(RecorderService.fmtDist(RecorderService.dist));
        tVmax.setText(String.valueOf(Math.round(RecorderService.vMax * 3.6)));
        tLean.setText(String.format(Locale.ITALY, "%.0f° / %.0f°", RecorderService.maxLeanL,
                RecorderService.maxLeanR));
        tG.setText(String.format(Locale.ITALY, "%.2f", RecorderService.maxG));
        lineBtn.setEnabled(RecorderService.hasFix);
        lapsTv.setText(RecorderService.lapsText);
    }
}
