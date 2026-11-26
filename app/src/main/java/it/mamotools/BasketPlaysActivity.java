package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Date;
import java.util.List;

/** Elenco degli schemi di basket: nuovo, apri, duplica, elimina. */
public class BasketPlaysActivity extends Activity {

    private LinearLayout root;
    private ProjStore store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ProjStore(this, "basket");
        root = Ui.screen(this, "Schemi Basket",
                "Disegna gli schemi su un campo FIBA, aggiungi le fasi e guardali in animazione.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        Ui.PickCard pick = new Ui.PickCard(this, "🏀", "Nuovo schema", "Parti da una formazione base sul campo",
                v -> askName());
        root.addView(pick.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        List<ProjStore.Entry> list = store.list();
        if (list.isEmpty()) {
            TextView e = Ui.text(this, "Nessuno schema ancora.\nTocca \"Nuovo schema\" per iniziare.", 14, Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 30), 0, 0);
            root.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            return;
        }
        root.addView(Ui.text(this, "I tuoi schemi", 14, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 18));
        for (final ProjStore.Entry e : list) {
            LinearLayout card = Ui.card(this);
            card.setClickable(true);
            card.setOnClickListener(v -> open(e.id));
            card.addView(Ui.text(this, e.title, 17, Ui.TEXT, true));
            String when = DateFormat.format("d MMM yyyy, HH:mm", new Date(e.modified)).toString();
            card.addView(Ui.text(this, (e.summary.isEmpty() ? "" : e.summary + " · ") + "modificato " + when, 12,
                    Ui.DIM, false));
            LinearLayout btns = new LinearLayout(this);
            btns.setBaselineAligned(false);
            btns.addView(Ui.btn(this, "Duplica", Ui.CARD2, Ui.ACCENT, v -> duplicate(e)),
                    Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
            btns.addView(Ui.btn(this, "Elimina", Ui.CARD2, Ui.DANGER, v -> confirmDelete(e)),
                    Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
            card.addView(btns, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
            root.addView(card, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
    }

    private void open(String id) {
        startActivity(new Intent(this, BasketBoardActivity.class).putExtra("project", id));
    }

    private void askName() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("es. Pick and roll, Uscita dai blocchi");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        in.setTextColor(Ui.TEXT);
        in.setHintTextColor(Ui.DIM);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(in, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(this).setTitle("Nome dello schema").setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Crea", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty()) {
                        n = "Schema " + (store.list().size() + 1);
                    }
                    try {
                        BasketPlay p = new BasketPlay();
                        p.name = n;
                        String id = ProjStore.newId();
                        store.write(id, p.toJson());
                        open(id);
                    } catch (Exception ex) {
                        Toast.makeText(this, "Impossibile creare lo schema.", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void duplicate(ProjStore.Entry e) {
        try {
            JSONObject o = store.read(e.id);
            String n = o.optString("name", e.title) + " (copia)";
            o.put("name", n);
            o.put("title", n);
            store.write(ProjStore.newId(), o);
            onResume();
        } catch (Exception ex) {
            Toast.makeText(this, "Duplicazione non riuscita.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(final ProjStore.Entry e) {
        new AlertDialog.Builder(this).setTitle("Eliminare lo schema?")
                .setMessage("\"" + e.title + "\" verrà eliminato dall'app.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Elimina", (d, w) -> {
                    store.delete(e.id);
                    onResume();
                }).show();
    }
}
