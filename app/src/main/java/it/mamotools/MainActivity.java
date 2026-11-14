package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Schermata iniziale: elenco degli strumenti. Per aggiungerne uno basta una riga in TOOLS. */
public class MainActivity extends Activity {

    private static final class Tool {
        final String icon;
        final String name;
        final String description;
        final Class<? extends Activity> target;

        Tool(String icon, String name, String description, Class<? extends Activity> target) {
            this.icon = icon;
            this.name = name;
            this.description = description;
            this.target = target;
        }
    }

    private static final Tool[] TOOLS = {
            new Tool("🔊", "Volume Boost",
                    "Amplifica il volume oltre il massimo, equalizzatore e bilanciamento "
                            + "sinistra/destra per le cuffie.",
                    VolumeBoostActivity.class),
            new Tool("🎥", "Stable Cam",
                    "Registra video stabilizzati alla massima qualita' del telefono "
                            + "e li salva in Galleria.",
                    StableCamActivity.class),
            new Tool("📄", "File Tools",
                    "Leggi PDF e Word, converti PDF ↔ Word, immagini e PDF, "
                            + "unisci, dividi, estrai, elimina e ruota pagine.",
                    FileToolsActivity.class),
            new Tool("🎬", "Video Editor",
                    "Taglia parti del video, togli l'audio o aggiungi una colonna sonora.",
                    VideoProjectsActivity.class),
            new Tool("🎧", "Audio Editor",
                    "Taglia, copia e incolla parti di un audio, filtri passa-alto/basso/banda "
                            + "e volume.",
                    AudioProjectsActivity.class),
            new Tool("📝", "Hyper Text",
                    "Editor di testo e codice con colori del linguaggio, controllo degli errori "
                            + "e scorciatoie per tastiera Bluetooth.",
                    HyperTextProjectsActivity.class),
            new Tool("🎛", "Produzioni",
                    "Componi musica da zero: pad a 16 passi, suoni sintetizzati, i tuoi sample, "
                            + "loop ed esportazione in WAV, FLAC e M4A.",
                    ProducerActivity.class),
            new Tool("📊", "System Monitor",
                    "CPU per core, RAM e traffico di rete in tempo reale, piu' tutte le "
                            + "informazioni del telefono.",
                    SystemMonitorActivity.class),
            new Tool("📐", "Strumenti di Misura",
                    "Calcolo miscela 2 tempi, righello a grandezza reale e livello a bolla.",
                    MeasureToolsActivity.class),
    };

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(40), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        TextView title = new TextView(this);
        title.setText("Mamo Tools");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text));
        root.addView(title);

        // riga "I tuoi strumenti" + lente per cercare
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(20));
        final TextView sub = new TextView(this);
        sub.setText("I tuoi strumenti");
        sub.setTextSize(15);
        sub.setTextColor(getColor(R.color.text_dim));
        row.addView(sub, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final TextView lens = new TextView(this);
        lens.setText("🔍");
        lens.setTextSize(18);
        lens.setGravity(Gravity.CENTER);
        GradientDrawable lensBg = new GradientDrawable();
        lensBg.setShape(GradientDrawable.OVAL);
        lensBg.setColor(getColor(R.color.card));
        lens.setBackground(lensBg);
        lens.setContentDescription("Cerca uno strumento");
        row.addView(lens, new LinearLayout.LayoutParams(dp(44), dp(44)));

        final EditText search = new EditText(this);
        search.setHint("Cerca uno strumento");
        search.setHintTextColor(getColor(R.color.text_dim));
        search.setTextColor(getColor(R.color.text));
        search.setSingleLine(true);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(getColor(R.color.card));
        sbg.setCornerRadius(dp(22));
        search.setBackground(sbg);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setVisibility(View.GONE);
        row.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1f));

        final TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(16);
        close.setTextColor(getColor(R.color.text_dim));
        close.setGravity(Gravity.CENTER);
        close.setVisibility(View.GONE);
        row.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(row);

        final List<View> cards = new ArrayList<>();
        for (final Tool t : TOOLS) {
            View card = toolCard(t.icon, t.name, t.description, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, t.target));
                }
            });
            cards.add(card);
            root.addView(card);
        }

        final TextView none = new TextView(this);
        none.setText("Nessuno strumento trovato.");
        none.setTextColor(getColor(R.color.text_dim));
        none.setTextSize(15);
        none.setGravity(Gravity.CENTER);
        none.setPadding(0, dp(24), 0, 0);
        none.setVisibility(View.GONE);
        root.addView(none);
        root.addView(buildFooter());

        final InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        lens.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sub.setVisibility(View.GONE);
                lens.setVisibility(View.GONE);
                search.setVisibility(View.VISIBLE);
                close.setVisibility(View.VISIBLE);
                search.requestFocus();
                imm.showSoftInput(search, 0);
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
                search.setVisibility(View.GONE);
                close.setVisibility(View.GONE);
                sub.setVisibility(View.VISIBLE);
                lens.setVisibility(View.VISIBLE);
                imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                int shown = 0;
                for (int i = 0; i < TOOLS.length; i++) {
                    Tool t = TOOLS[i];
                    boolean ok = q.isEmpty()
                            || t.name.toLowerCase(Locale.ROOT).contains(q)
                            || t.description.toLowerCase(Locale.ROOT).contains(q);
                    cards.get(i).setVisibility(ok ? View.VISIBLE : View.GONE);
                    if (ok) {
                        shown++;
                    }
                }
                none.setVisibility(!q.isEmpty() && shown == 0 ? View.VISIBLE : View.GONE);
            }
        });

        setContentView(scroll);
    }

    /** Versione dell'app (letta dal pacchetto, quindi sempre uguale a quella installata), autore e link a GitHub. */
    private View buildFooter() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(36), 0, dp(8));

        View line = new View(this);
        line.setBackgroundColor(0x449AA3B2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(2));
        lp.bottomMargin = dp(16);
        box.addView(line, lp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        String vs = "v" + ver;
        android.text.SpannableString sp = new android.text.SpannableString(
                vs + "   ·   Mamons (ft. Claude)   ·");
        sp.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.accent)), 0, vs.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = new TextView(this);
        t.setText(sp);
        t.setTextSize(14);
        t.setLetterSpacing(0.03f);
        t.setTextColor(getColor(R.color.text_dim));
        row.addView(t);

        android.widget.ImageView gh = new android.widget.ImageView(this);
        gh.setImageResource(R.drawable.ic_github);
        gh.setPadding(dp(8), dp(8), dp(8), dp(8));
        gh.setContentDescription("GitHub");
        gh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/mamons119/Mamo-Tools")));
                } catch (Exception ignored) {
                }
            }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(40), dp(40));
        gp.leftMargin = dp(4);
        row.addView(gh, gp);
        box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return box;
    }

    private View toolCard(String icon, String name, String desc, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setCornerRadius(dp(16));
        if (click != null) {
            card.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x334DD0E1), bg, null));
            card.setClickable(true);
            card.setOnClickListener(click);
        } else {
            card.setBackground(bg);
        }

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(26);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(getColor(R.color.bg));
        ic.setBackground(circle);
        card.addView(ic, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, 0, 0);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(18);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(getColor(R.color.text));
        texts.addView(n);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(13);
        d.setTextColor(getColor(R.color.text_dim));
        d.setPadding(0, dp(4), 0, 0);
        texts.addView(d);
        card.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
