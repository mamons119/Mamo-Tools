package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Produzioni: elenco dei brani e creazione di un nuovo brano. */
public class ProducerActivity extends Activity {

    private LinearLayout root;
    private ProdStore store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ProdStore(this);
        root = Ui.screen(this, "Produzioni", "Crea la tua musica da zero: ritmi, melodie e i tuoi sample.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        Ui.PickCard nw = new Ui.PickCard(this, "🎛", "Nuovo brano", "Parti da una pagina bianca con un kit di base",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String id = ProdStore.newId();
                        try {
                            store.save(id, ProdModel.Song.fresh());
                        } catch (Exception e) {
                            android.widget.Toast.makeText(ProducerActivity.this, "Impossibile creare il brano.",
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        startActivity(new Intent(ProducerActivity.this, ProdEditorActivity.class).putExtra("id", id));
                    }
                });
        root.addView(nw.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));

        List<ProdStore.Entry> list = store.list();
        if (list.isEmpty()) {
            TextView e = Ui.text(this, "Nessun brano ancora.\nTocca \"Nuovo brano\" per iniziare.", 14, Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 30), 0, 0);
            root.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            return;
        }
        root.addView(Ui.text(this, "I tuoi brani", 14, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 18));
        for (final ProdStore.Entry e : list) {
            LinearLayout card = Ui.card(this);
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(ProducerActivity.this, ProdEditorActivity.class).putExtra("id", e.id));
                }
            });
            card.addView(Ui.text(this, e.title, 17, Ui.TEXT, true));
            String when = DateFormat.format("d MMM yyyy, HH:mm", new Date(e.modified)).toString();
            card.addView(Ui.text(this, String.format(Locale.ITALY, "%d BPM · %s · modificato %s", e.bpm,
                    e.seconds > 0 ? ProdUi.time(e.seconds) : "nessun blocco nel brano", when), 12, Ui.DIM, false));
            card.addView(Ui.btn(this, "Elimina", Ui.CARD2, Ui.DANGER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(e);
                }
            }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 12));
            root.addView(card, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
    }

    private void confirmDelete(final ProdStore.Entry e) {
        new AlertDialog.Builder(this).setTitle("Eliminare il brano?")
                .setMessage("\"" + e.title + "\" e i suoi sample verranno eliminati definitivamente. "
                        + "I file già esportati non vengono toccati.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Elimina", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        store.delete(e.id);
                        onResume();
                    }
                }).show();
    }
}
