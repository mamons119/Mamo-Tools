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

    private Button startBtn, lineBtn, openBtn;
    private VehCell[] vehCells;
    private String autoName = "";
    private TextView tTime, tSpeed, tDist, tVmax, tLean, tG, lapsTv, infoTv;
    private LinearLayout vehCard;
    private EditText nameEdit, descEdit;
    private String donePath = null;

    @Override
    protected String title() {
        return "Registra Onboard";
    }

    @Override
    protected String subtitle() {
        return "Registra viaggio/corsa anche a schermo spento: percorso, velocita' e forze G, "
                + "da rivedere dopo su mappa e grafici.";
    }

    @Override
    protected void build(LinearLayout root) {
        // ---- dettagli: veicolo, nome, descrizione
        vehCard = Ui.card(this);
        vehCard.addView(Ui.section(this, "Dettagli"));
        vehCard.addView(sub("Veicolo"));
        vehCells = new VehCell[Vehicle.IDS.length];
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        for (int i = 0; i < vehCells.length; i++) {
            final int k = i;
            VehCell vc = new VehCell(i);
            vc.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (RecorderService.running) {
                        return;
                    }
                    vehicle = Vehicle.IDS[k];
                    styleVehicles();
                }
            });
            vehCells[i] = vc;
            if (i < 3) {
                r1.addView(vc, Ui.lp(this, 0, Ui.dp(this, 74), i == 0 ? 0 : 8, 0, 1f));
            } else {
                if (i == 3) {
                    r2.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 0.5f));
                }
                r2.addView(vc, Ui.lp(this, 0, Ui.dp(this, 74), i == 3 ? 0 : 8, 0, 1f));
                if (i == 4) {
                    r2.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 0.5f));
                }
            }
        }
        vehCard.addView(r1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        vehCard.addView(r2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        TextView nl = sub("Nome sessione");
        nl.setPadding(0, Ui.dp(this, 16), 0, 0);
        vehCard.addView(nl);
        nameEdit = field("Nome della sessione", false);
        autoName = defaultName();
        nameEdit.setText(autoName);
        vehCard.addView(nameEdit, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        TextView dl = sub("Descrizione (facoltativa)");
        dl.setPadding(0, Ui.dp(this, 16), 0, 0);
        vehCard.addView(dl);
        descEdit = field("Note sul viaggio, condizioni, meta…", true);
        vehCard.addView(descEdit, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
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

        openBtn = Ui.btn(this, "Apri sessione registrata", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (donePath != null) {
                    startActivity(new Intent(RecorderActivity.this, SessionViewActivity.class)
                            .putExtra("path", donePath));
                }
            }
        });
        openBtn.setVisibility(View.GONE);
        root.addView(openBtn, Ui.lp(this, Ui.MATCH, Ui.dp(this, 52), 0, 12));

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
        for (int i = 0; i < vehCells.length; i++) {
            vehCells[i].setOn(Vehicle.IDS[i].equals(vehicle));
        }
    }

    private TextView sub(String t) {
        return Ui.text(this, t, 12, Ui.DIM, true);
    }

    private static String defaultName() {
        return "Sessione " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
                .format(new java.util.Date());
    }

    /** Pulsante veicolo: icona disegnata sopra, nome sotto; diventa blu se selezionato. */
    private final class VehCell extends LinearLayout {
        private final Icons.VehicleIcon icon;
        private final TextView label;

        VehCell(int i) {
            super(RecorderActivity.this);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            icon = new Icons.VehicleIcon(RecorderActivity.this, i);
            addView(icon, new LinearLayout.LayoutParams(Ui.dp(RecorderActivity.this, 32),
                    Ui.dp(RecorderActivity.this, 32)));
            label = Ui.text(RecorderActivity.this, Vehicle.LABELS[i], 12, Ui.TEXT, true);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            addView(label, Ui.lp(RecorderActivity.this, Ui.WRAP, Ui.WRAP, 0, 4));
            setOn(false);
        }

        void setOn(boolean on) {
            setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x44FFFFFF),
                    Ui.round(RecorderActivity.this, on ? Ui.ACCENT : Ui.CARD2, 12), null));
            icon.setColor(on ? 0xFF000000 : Ui.TEXT);
            label.setTextColor(on ? 0xFF000000 : Ui.TEXT);
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
        String nm = nameEdit.getText().toString().trim();
        donePath = null;
        openBtn.setVisibility(View.GONE);
        RecorderService.lastPath = null;
        startForegroundService(new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START).putExtra("vehicle", vehicle)
                .putExtra("name", nm.equals(autoName) ? "" : nm)
                .putExtra("desc", descEdit.getText().toString()));
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
        for (VehCell t : vehCells) {
            t.setAlpha(rec ? 0.55f : 1f);
        }
        nameEdit.setEnabled(!rec);
        descEdit.setEnabled(!rec);
    }

    /** Sessione appena salvata: mostra "Apri" e prepara nome e descrizione per la prossima. */
    private void showDone(String path) {
        donePath = path;
        openBtn.setVisibility(View.VISIBLE);
        resetDetails();
    }

    private void resetDetails() {
        autoName = defaultName();
        nameEdit.setText(autoName);
        descEdit.setText("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean rec = RecorderService.running;
        if (rec) {
            vehicle = RecorderService.vehicle;
            styleVehicles();
        } else if (RecorderService.lastPath != null && donePath == null) {
            showDone(RecorderService.lastPath);
        } else if (nameEdit.getText().toString().equals(autoName)) {
            autoName = defaultName();
            nameEdit.setText(autoName);
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
                showDone(RecorderService.lastPath);
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
