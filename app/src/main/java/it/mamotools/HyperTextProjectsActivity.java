package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Elenco dei file di Hyper Text: crea un file nuovo, riprendi, scarica o elimina. */
public class HyperTextProjectsActivity extends Activity {

    private static final int REQ_IMPORT = 91;
    private static final int MAX_IMPORT = 5 * 1024 * 1024;
    private LinearLayout root;
    private ProjStore store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ProjStore(this, "hyper");
        root = Ui.screen(this, "Hyper Text", "Editor di testo e di codice con colori del linguaggio, "
                + "controllo degli errori e supporto alla tastiera Bluetooth.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        Ui.PickCard pick = new Ui.PickCard(this, "📝", "Nuovo file", "Testo o codice in qualsiasi linguaggio",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askName();
                    }
                });
        root.addView(pick.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        Ui.PickCard imp = new Ui.PickCard(this, "📂", "Importa un file", "Apri un file di testo o di codice dal telefono",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        try {
                            startActivityForResult(i, REQ_IMPORT);
                        } catch (Exception e) {
                            Toast.makeText(HyperTextProjectsActivity.this, "Impossibile aprire il selettore di file.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
        root.addView(imp.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));

        List<ProjStore.Entry> list = store.list();
        if (list.isEmpty()) {
            TextView e = Ui.text(this, "Nessun file ancora.\nTocca \"Nuovo file\" per iniziare a scrivere.", 14,
                    Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 30), 0, 0);
            root.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            return;
        }
        root.addView(Ui.text(this, "I tuoi file", 14, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 18));
        for (final ProjStore.Entry e : list) {
            LinearLayout card = Ui.card(this);
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(e.id);
                }
            });
            card.addView(Ui.text(this, e.title, 17, Ui.TEXT, true));
            String when = DateFormat.format("d MMM yyyy, HH:mm", new Date(e.modified)).toString();
            card.addView(Ui.text(this, (e.summary.isEmpty() ? "" : e.summary + " · ") + "modificato " + when, 12,
                    Ui.DIM, false));
            LinearLayout btns = new LinearLayout(this);
            btns.setBaselineAligned(false);
            btns.addView(Ui.btn(this, "Scarica", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    download(e);
                }
            }), Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
            btns.addView(Ui.btn(this, "Elimina", Ui.CARD2, Ui.DANGER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(e);
                }
            }), Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
            card.addView(btns, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
            root.addView(card, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        android.net.Uri uri = data.getData();
        String name = "importato.txt";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && c.getString(ix) != null) {
                    name = c.getString(ix);
                }
            }
        } catch (Exception ignored) {
        }
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("file non leggibile");
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_IMPORT) {
                    Toast.makeText(this, "File troppo grande (massimo 5 MB).", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            byte[] b = bo.toByteArray();
            for (int i = 0; i < Math.min(b.length, 8000); i++) {
                if (b[i] == 0) {
                    Toast.makeText(this, "Questo non sembra un file di testo.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            String text = new String(b, "UTF-8");
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (name.isEmpty()) {
                name = "importato.txt";
            }
            CodeLang l = CodeLang.detect(name, text);
            int lines = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            JSONObject o = new JSONObject();
            o.put("title", name);
            o.put("text", text);
            o.put("lang", l.name);
            o.put("summary", l.name + " · " + lines + (lines == 1 ? " riga" : " righe"));
            String id = ProjStore.newId();
            store.write(id, o);
            open(id);
        } catch (Exception e) {
            Toast.makeText(this, "Importazione non riuscita: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void open(String id) {
        startActivity(new Intent(this, HyperTextActivity.class).putExtra("project", id));
    }

    private void askName() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("es. note.txt, script.py, index.html");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setTextColor(Ui.TEXT);
        in.setHintTextColor(Ui.DIM);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(in, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(this).setTitle("Nome del nuovo file")
                .setMessage("L'estensione sceglie il linguaggio (.py, .java, .html, .json, .txt…).")
                .setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Crea", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = in.getText().toString().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                        if (n.isEmpty()) {
                            n = "nuovo.txt";
                        } else if (!n.contains(".")) {
                            n += ".txt";
                        }
                        String id = ProjStore.newId();
                        try {
                            JSONObject o = new JSONObject();
                            o.put("title", n);
                            o.put("text", "");
                            o.put("lang", CodeLang.detect(n, "").name);
                            o.put("summary", CodeLang.detect(n, "").name);
                            store.write(id, o);
                        } catch (Exception ex) {
                            Toast.makeText(HyperTextProjectsActivity.this, "Impossibile creare il file.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        open(id);
                    }
                }).show();
    }

    private void download(ProjStore.Entry e) {
        try {
            JSONObject o = store.read(e.id);
            final byte[] data = o.optString("text", "").getBytes("UTF-8");
            String name = e.title;
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) {
                mime = "application/octet-stream";
            }
            FileUtil.save(this, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/MamoTools", name, mime,
                    new FileUtil.Writer() {
                        @Override
                        public void write(OutputStream os) throws IOException {
                            os.write(data);
                        }
                    });
            Toast.makeText(this, "Salvato in Download/MamoTools", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, "Download non riuscito: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(final ProjStore.Entry e) {
        new AlertDialog.Builder(this).setTitle("Eliminare il file?")
                .setMessage("\"" + e.title + "\" verra' eliminato dall'app (le copie scaricate restano).")
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
