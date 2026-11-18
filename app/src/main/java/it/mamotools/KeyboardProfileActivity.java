package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Mappature di una tastiera (o di tutte): elenco, nuova mappatura con rilevamento del tasto, prova. */
public class KeyboardProfileActivity extends Activity {

    private LinearLayout root;
    private String pid, pname;
    private Dialog capture;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pid = getIntent().getStringExtra("id");
        pname = getIntent().getStringExtra("name");
        if (pid == null) {
            pid = KbdStore.ALL;
        }
        if (pname == null) {
            pname = "Tutte le tastiere";
        }
        root = Ui.screen(this, pname, pid.equals(KbdStore.ALL)
                ? "Queste mappature valgono per qualsiasi tastiera, a meno che la tastiera abbia una mappatura sua per lo stesso tasto."
                : "Queste mappature valgono solo per questa tastiera e si applicano appena la colleghi.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCapture();
    }

    private void build() {
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        root.addView(Ui.btn(this, "+  Nuova mappatura", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCapture();
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_BTN), 0, 0));

        List<KbdStore.Profile> all = KbdStore.load(this);
        KbdStore.Profile p = KbdStore.find(all, pid);
        if (p == null || p.maps.isEmpty()) {
            TextView e = Ui.text(this, "Nessuna mappatura.\nTocca \"Nuova mappatura\", premi la combinazione sulla "
                    + "tastiera e scegli cosa deve fare.", 14, Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 10));
            root.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        } else {
            root.addView(Ui.text(this, "Mappature", 14, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 18));
            for (final KbdStore.Map m : p.maps) {
                LinearLayout card = Ui.card(this);
                card.setClickable(true);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseAction(m, false);
                    }
                });
                card.addView(Ui.text(this, m.keyName.isEmpty() ? "Tasto" : m.keyName, 18, Ui.TEXT, true));
                card.addView(Ui.text(this, "→  " + m.action(), 14, Ui.ACCENT, false));
                LinearLayout btns = new LinearLayout(this);
                btns.addView(Ui.btn(this, "Cambia azione", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseAction(m, false);
                    }
                }), Ui.lp(this, 0, Ui.dp(this, 42), 0, 0, 1f));
                btns.addView(Ui.btn(this, "Elimina", Ui.CARD2, Ui.DANGER, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        remove(m);
                    }
                }), Ui.lp(this, 0, Ui.dp(this, 42), 8, 0, 1f));
                card.addView(btns, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
                root.addView(card, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            }
        }

        root.addView(Ui.text(this, "Prova", 14, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 22));
        EditText test = new EditText(this);
        test.setHint("Tocca qui e prova i tuoi tasti");
        test.setHintTextColor(Ui.DIM);
        test.setTextColor(Ui.TEXT);
        test.setMinLines(2);
        test.setGravity(Gravity.TOP);
        test.setBackground(Ui.round(this, Ui.CARD, 12));
        test.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        root.addView(test, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        if (!KeyboardMapActivity.serviceEnabled(this)) {
            root.addView(Ui.text(this, "Il servizio non e' attivo: le mappature non funzionano finche' non lo "
                    + "attivi dalla pagina precedente.", 12, Ui.DANGER, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 4, 8));
        }
    }

    private void remove(final KbdStore.Map m) {
        new AlertDialog.Builder(this).setTitle("Eliminare la mappatura?")
                .setMessage(m.keyName + "  →  " + m.action())
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Elimina", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<KbdStore.Profile> all = KbdStore.load(KeyboardProfileActivity.this);
                        KbdStore.Profile p = KbdStore.find(all, pid);
                        if (p != null) {
                            for (int i = p.maps.size() - 1; i >= 0; i--) {
                                KbdStore.Map x = p.maps.get(i);
                                if (x.keyCode == m.keyCode && x.scanCode == m.scanCode && x.mods == m.mods) {
                                    p.maps.remove(i);
                                }
                            }
                            KbdStore.save(KeyboardProfileActivity.this, all);
                        }
                        build();
                    }
                }).show();
    }

    private static boolean isModifier(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_FUNCTION:
                return true;
            default:
                return false;
        }
    }

    /** Aspetta la pressione di una combinazione di tasti (il servizio, intanto, non intercetta nulla). */
    private void startCapture() {
        stopCapture();
        KeyRemapService.learning = true;
        final Activity self = this;
        capture = new Dialog(this) {
            @Override
            public boolean onKeyDown(int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK) {
                    dismiss();
                    return true;
                }
                if (isModifier(code) || e.getRepeatCount() > 0) {
                    return true;
                }
                final KbdStore.Map m = new KbdStore.Map();
                m.keyCode = code;
                m.scanCode = e.getScanCode();
                m.mods = KbdStore.normalize(e.getMetaState());
                if (code == KeyEvent.KEYCODE_UNKNOWN && m.scanCode == 0) {
                    return true;
                }
                m.keyName = KbdStore.keyLabel(code, m.scanCode, m.mods, e.getUnicodeChar(0));
                dismiss();
                self.getWindow().getDecorView().post(new Runnable() {
                    @Override
                    public void run() {
                        chooseAction(m, true);
                    }
                });
                return true;
            }
        };
        capture.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD, 20));
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 22), Ui.dp(this, 24), Ui.dp(this, 20));
        box.addView(Ui.text(this, "Premi la combinazione", 18, Ui.TEXT, true));
        TextView t = Ui.text(this, "Sulla tastiera Bluetooth premi il tasto (o i tasti insieme, per esempio "
                + "Ctrl + \\ oppure Ctrl + Maiusc + \\).", 14, Ui.DIM, false);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        box.addView(t);
        box.addView(Ui.btn(this, "Annulla", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopCapture();
            }
        }), new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 42)));
        capture.setContentView(box);
        capture.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                KeyRemapService.learning = false;
            }
        });
        android.view.Window w = capture.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        capture.show();
    }

    private void stopCapture() {
        KeyRemapService.learning = false;
        if (capture != null) {
            try {
                capture.dismiss();
            } catch (Exception ignored) {
            }
            capture = null;
        }
    }

    private void chooseAction(final KbdStore.Map m, final boolean isNew) {
        final String[] names = {"Scrivere un testo o un simbolo", "Aprire un'app", "Azione di sistema",
                "Musica e volume", "Nessuna azione (blocca il tasto)"};
        new AlertDialog.Builder(this).setTitle(m.keyName + "  →  cosa deve fare?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        switch (w) {
                            case 0:
                                askText(m);
                                break;
                            case 1:
                                pickApp(m);
                                break;
                            case 2:
                                pickFrom(m, KbdStore.T_GLOBAL, KbdStore.GLOBALS, "Azione di sistema");
                                break;
                            case 3:
                                pickFrom(m, KbdStore.T_MEDIA, KbdStore.MEDIA, "Musica e volume");
                                break;
                            default:
                                m.type = KbdStore.T_NONE;
                                m.arg = "";
                                m.label = "";
                                store(m);
                                break;
                        }
                    }
                }).setNegativeButton("Annulla", null).show();
    }

    private void askText(final KbdStore.Map m) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("es. <   oppure   >   oppure   mario@email.it");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setTextColor(Ui.TEXT);
        in.setHintTextColor(Ui.DIM);
        if (KbdStore.T_TEXT.equals(m.type)) {
            in.setText(m.arg.replace("\n", "\\n").replace("\t", "\\t"));
        }
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(in, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(this).setTitle("Testo da scrivere")
                .setMessage("Scrivi il carattere o il testo. Usa \\n per andare a capo e \\t per un tab.")
                .setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String t = in.getText().toString().replace("\\n", "\n").replace("\\t", "\t");
                        if (t.isEmpty()) {
                            return;
                        }
                        m.type = KbdStore.T_TEXT;
                        m.arg = t;
                        m.label = "";
                        store(m);
                    }
                }).show();
    }

    private void pickFrom(final KbdStore.Map m, final String type, final String[][] opts, String title) {
        String[] names = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            names[i] = opts[i][1];
        }
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        m.type = type;
                        m.arg = opts[w][0];
                        m.label = opts[w][1];
                        store(m);
                    }
                }).setNegativeButton("Annulla", null).show();
    }

    private void pickApp(final KbdStore.Map m) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            apps.add(new String[]{String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName});
        }
        Collections.sort(apps, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        if (apps.isEmpty()) {
            Ui.infoDialog(this, "Nessuna app", "Non riesco a leggere l'elenco delle app installate.");
            return;
        }
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = apps.get(i)[0];
        }
        new AlertDialog.Builder(this).setTitle("Quale app aprire?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        m.type = KbdStore.T_APP;
                        m.arg = apps.get(w)[1];
                        m.label = apps.get(w)[0];
                        store(m);
                    }
                }).setNegativeButton("Annulla", null).show();
    }

    /** Salva (o sostituisce) la mappatura per lo stesso tasto e le stesse combinazioni. */
    private void store(KbdStore.Map m) {
        List<KbdStore.Profile> all = KbdStore.load(this);
        KbdStore.Profile p = KbdStore.get(all, pid, pname);
        for (int i = p.maps.size() - 1; i >= 0; i--) {
            KbdStore.Map x = p.maps.get(i);
            if (x.keyCode == m.keyCode && x.scanCode == m.scanCode && x.mods == m.mods) {
                p.maps.remove(i);
            }
        }
        p.maps.add(m);
        KbdStore.save(this, all);
        build();
    }
}
