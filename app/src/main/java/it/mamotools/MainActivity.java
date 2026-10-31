package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
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
            new Tool("📄", "PDF ↔ Word",
                    "Converte un PDF in Word (.docx) o un Word in PDF e salva il risultato "
                            + "in Download.",
                    ConverterActivity.class),
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

        TextView sub = new TextView(this);
        sub.setText("I tuoi strumenti");
        sub.setTextSize(15);
        sub.setTextColor(getColor(R.color.text_dim));
        sub.setPadding(0, dp(4), 0, dp(24));
        root.addView(sub);

        for (final Tool t : TOOLS) {
            root.addView(toolCard(t.icon, t.name, t.description, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, t.target));
                }
            }));
        }

        // segnaposto per i prossimi strumenti
        View soon = toolCard("➕", "Altri strumenti in arrivo",
                "Qui compariranno i prossimi tool.", null);
        soon.setAlpha(0.45f);
        root.addView(soon);

        setContentView(scroll);
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
