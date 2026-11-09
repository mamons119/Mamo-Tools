package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;
import java.util.List;

/** Schermata iniziale di Video Editor e Audio Editor: apri un file nuovo o riprendi un progetto salvato. */
public abstract class ProjectsActivity extends Activity {

    private static final int REQ_PICK = 71;
    private LinearLayout root;
    private ProjStore store;

    abstract boolean video();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ProjStore(this, video() ? "video" : "audio");
        root = Ui.screen(this, video() ? "Video Editor" : "Audio Editor",
                video() ? "Taglia, unisci clip, aggiungi effetti, testi e colonna sonora."
                        : "Taglia, copia e incolla parti di un audio, filtri e volume.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        Ui.PickCard pick = new Ui.PickCard(this, video() ? "🎬" : "🎧",
                video() ? "Apri un video" : "Apri un audio",
                video() ? "Dalla Galleria o dai file" : "MP3, M4A, WAV, OGG… oppure l'audio di un video",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        if (video()) {
                            i.setType("video/*");
                        } else {
                            i.setType("*/*");
                            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
                        }
                        try {
                            startActivityForResult(i, REQ_PICK);
                        } catch (Exception e) {
                            Toast.makeText(ProjectsActivity.this, "Impossibile aprire il selettore di file.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
        root.addView(pick.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));

        List<ProjStore.Entry> list = store.list();
        if (list.isEmpty()) {
            TextView e = Ui.text(this, "Nessun progetto ancora.\nApri un file e, quando vuoi, tocca "
                    + "\"Salva progetto\" per ritrovarlo qui.", 14, Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 30), 0, 0);
            root.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            return;
        }
        root.addView(Ui.text(this, "I tuoi progetti", 14, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 18));
        for (final ProjStore.Entry e : list) {
            LinearLayout card = Ui.card(this);
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(ProjectsActivity.this,
                            video() ? VideoEditorActivity.class : AudioEditorActivity.class)
                            .putExtra("project", e.id));
                }
            });
            card.addView(Ui.text(this, e.title, 17, Ui.TEXT, true));
            String when = DateFormat.format("d MMM yyyy, HH:mm", new Date(e.modified)).toString();
            card.addView(Ui.text(this, (e.summary.isEmpty() ? "" : e.summary + " · ") + "modificato " + when,
                    12, Ui.DIM, false));
            card.addView(Ui.btn(this, "Elimina", Ui.CARD2, Ui.DANGER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(e);
                }
            }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 12));
            root.addView(card, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        ProjStore.keep(this, uri);
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.setClass(this, video() ? VideoEditorActivity.class : AudioEditorActivity.class);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private void confirmDelete(final ProjStore.Entry e) {
        new AlertDialog.Builder(this).setTitle("Eliminare il progetto?")
                .setMessage("\"" + e.title + "\" verrà eliminato definitivamente. "
                        + "I file originali e quelli già esportati non vengono toccati.")
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
