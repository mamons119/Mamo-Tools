package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/** Converte PDF in Word (.docx) e Word (.docx) in PDF; il risultato va in Download/MamoTools. */
public class ConverterActivity extends Activity {

    private static final int REQ_PICK = 31;
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String DIR = "Download/MamoTools";

    private Uri pickedUri;
    private String pickedName;
    private boolean pickedIsPdf;
    private volatile boolean busy = false;

    private Ui.PickCard pick;
    private TextView direction;
    private Button convertBtn;
    private ResultCard result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.screen(this, "PDF ↔ Word",
                "Scegli un PDF o un Word (.docx): lo converto nell'altro formato.");

        pick = new Ui.PickCard(this, "📄", "Scegli un file", "PDF oppure Word (.docx)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pick();
                    }
                });
        root.addView(pick.view);

        direction = Ui.text(this, "", 15, Ui.ACCENT, true);
        direction.setGravity(android.view.Gravity.CENTER);
        direction.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(direction);

        convertBtn = Ui.btn(this, "Converti", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                convert();
            }
        });
        convertBtn.setTextSize(16);
        convertBtn.setEnabled(false);
        root.addView(convertBtn, Ui.lp(this, Ui.MATCH, Ui.dp(this, 54), 0, 12));

        result = new ResultCard(this);
        root.addView(result.view, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));

        LinearLayout notes = Ui.card(this);
        notes.addView(Ui.text(this, "Cosa aspettarsi", 14, Ui.ACCENT, true));
        TextView t = Ui.text(this, "PDF → Word: estrae il testo e lo rimette in paragrafi "
                + "(titoli, grassetto e dimensioni). Immagini, tabelle e impaginazione non "
                + "vengono mantenute, e i PDF scansionati (solo immagini) non funzionano.\n\n"
                + "Word → PDF: mantiene testo formattato, elenchi, tabelle semplici e immagini. "
                + "Intestazioni, pie' di pagina e caselle di testo non vengono disegnati e i "
                + "font possono differire. Il vecchio .doc non e' supportato: salvalo prima "
                + "come .docx.", 12, Ui.DIM, false);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        notes.addView(t);
        root.addView(notes, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 18));
    }

    private void pick() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{MIME_PDF, MIME_DOCX});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire il selettore di file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = FileUtil.queryName(this, uri);
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = getContentResolver().getType(uri);
        result.hide();

        if (lower.endsWith(".pdf") || MIME_PDF.equals(mime)) {
            pickedIsPdf = true;
        } else if (lower.endsWith(".docx") || MIME_DOCX.equals(mime)) {
            pickedIsPdf = false;
        } else {
            pickedUri = null;
            convertBtn.setEnabled(false);
            direction.setText("");
            pick.title.setText(name);
            pick.sub.setText("Formato non supportato");
            result.error(lower.endsWith(".doc")
                    ? "Il formato .doc (Word vecchio) non e' supportato: aprilo in Word e "
                    + "salvalo come .docx."
                    : "Formato non supportato: scegli un file PDF o .docx.");
            return;
        }
        pickedUri = uri;
        pickedName = name;
        pick.title.setText(name);
        pick.sub.setText((pickedIsPdf ? "PDF" : "Word (.docx)") + " · "
                + FileUtil.humanSize(FileUtil.querySize(this, uri)));
        direction.setText(pickedIsPdf ? "PDF  →  Word (.docx)" : "Word (.docx)  →  PDF");
        convertBtn.setText(pickedIsPdf ? "Converti in Word" : "Converti in PDF");
        convertBtn.setEnabled(true);
    }

    private void convert() {
        if (busy || pickedUri == null) {
            return;
        }
        busy = true;
        convertBtn.setEnabled(false);
        result.start("Conversione in corso...");

        final Uri src = pickedUri;
        final boolean pdf = pickedIsPdf;
        final String base = FileUtil.baseName(pickedName);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(getCacheDir(), pdf ? "input.pdf" : "input.docx");
                try {
                    FileUtil.copyToFile(ConverterActivity.this, src, tmp);
                    Uri saved;
                    String mime;
                    String name;
                    if (pdf) {
                        final PdfTextExtractor.Result r = PdfTextExtractor.extract(
                                ConverterActivity.this, tmp, new PdfTextExtractor.Progress() {
                                    @Override
                                    public void onPage(int page, int total) {
                                        result.progress("Lettura pagina " + page + " di " + total,
                                                page * 100 / Math.max(1, total));
                                    }
                                });
                        result.progress("Creazione del file Word...", -1);
                        mime = MIME_DOCX;
                        name = base + ".docx";
                        saved = FileUtil.save(ConverterActivity.this,
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, DIR, name, mime,
                                new FileUtil.Writer() {
                                    @Override
                                    public void write(OutputStream os) throws IOException {
                                        DocxWriter.write(r.paras, r.pageW, r.pageH, os);
                                    }
                                });
                    } else {
                        result.progress("Lettura del documento Word...", -1);
                        final DocModel model = DocxParser.parse(tmp);
                        result.progress("Creazione del PDF...", -1);
                        mime = MIME_PDF;
                        name = base + ".pdf";
                        saved = FileUtil.save(ConverterActivity.this,
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, DIR, name, mime,
                                new FileUtil.Writer() {
                                    @Override
                                    public void write(OutputStream os) throws IOException {
                                        DocxToPdf.render(model, os);
                                    }
                                });
                    }
                    result.ok("Fatto! Salvato in Download/MamoTools/" + FileUtil.displayName(
                            ConverterActivity.this, saved), saved, mime,
                            pdf ? "Apri il file Word" : "Apri il PDF");
                } catch (OutOfMemoryError oom) {
                    result.error("Memoria insufficiente: il documento e' troppo grande per essere "
                            + "convertito su questo telefono.");
                } catch (Throwable t) {
                    String m = t.getMessage();
                    result.error("Conversione non riuscita"
                            + (m != null && !m.isEmpty() ? ": " + m : " (" + t.getClass().getSimpleName() + ")"));
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            convertBtn.setEnabled(pickedUri != null);
                        }
                    });
                }
            }
        }).start();
    }
}
