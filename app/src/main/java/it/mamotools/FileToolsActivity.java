package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Menu del tool "File Tools": un riquadro per ogni operazione. */
public class FileToolsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.screen(this, "File Tools",
                "Tutto quello che serve per i tuoi file. I file nuovi vanno in Download/MamoTools.");

        root.addView(Ui.section(this, "Reader"));
        root.addView(tile("📕", "Leggi PDF", "Zoom, ricerca, vai a pagina, notte, condividi e stampa",
                new Intent(this, PdfReaderActivity.class)));
        root.addView(tile("📘", "Leggi Word", "Apri i documenti .docx direttamente, senza convertirli",
                new Intent(this, WordReaderActivity.class)));
        root.addView(tile("📖", "Leggi EPUB", "Libri .epub con indice, ricerca, segnalibri, temi e carattere regolabile",
                new Intent(this, EpubReaderActivity.class)));

        root.addView(space());
        root.addView(Ui.section(this, "Converti"));
        root.addView(tile("📄", "PDF ↔ Word", "Da PDF a .docx e da .docx a PDF",
                new Intent(this, ConverterActivity.class)));
        root.addView(tile("📷", "PDF → Immagini", "Salva le pagine come JPG o PNG",
                images("pdf2img")));

        root.addView(space());
        root.addView(Ui.section(this, "Organizza"));
        root.addView(tile("🧩", "Unisci PDF", "Combina piu' PDF in uno solo, nell'ordine che vuoi",
                organize("merge")));
        root.addView(tile("✂", "Dividi PDF", "Una pagina per file oppure a gruppi",
                organize("split")));
        root.addView(tile("📑", "Estrai pagine", "Crea un nuovo PDF con solo le pagine scelte",
                organize("extract")));
        root.addView(tile("🗑", "Elimina pagine", "Togli le pagine che non servono",
                organize("delete")));
        root.addView(tile("🔄", "Ruota pagine", "Ruota di 90°, 180° o 270° tutte o alcune pagine",
                organize("rotate")));

        root.addView(space());
        root.addView(Ui.section(this, "Immagini"));
        root.addView(tile("🖼", "Image Converter", "Converti in JPG, PNG, WEBP, BMP, ICO oppure crea un PDF dalle foto",
                new Intent(this, ImageConverterActivity.class)));
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, 10)));
        return v;
    }

    private Intent organize(String mode) {
        return new Intent(this, PdfOrganizeActivity.class).putExtra("mode", mode);
    }

    private Intent images(String mode) {
        return new Intent(this, PdfImagesActivity.class).putExtra("mode", mode);
    }

    private View tile(String icon, String title, String desc, final Intent target) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334DD0E1),
                Ui.round(this, Ui.CARD, 16), null));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(target);
            }
        });
        TextView ic = Ui.text(this, icon, 22, Ui.TEXT, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(this, Ui.BG, 100));
        t.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 6), 0);
        col.addView(Ui.text(this, title, 16, Ui.TEXT, true));
        col.addView(Ui.text(this, desc, 12, Ui.DIM, false));
        t.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        t.addView(Ui.text(this, "›", 24, Ui.DIM, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }
}
