package it.mamotools;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Righello a schermo intero 1:1 con calibrazione (sagoma di carta di credito). */
public class RulerActivity extends Activity {

    private RulerView ruler;
    private LinearLayout panel;
    private View panelHost;
    private Button shapeBtn;
    private TextView scaleText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        prefs = getSharedPreferences("ruler", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        ruler = new RulerView(this);
        ruler.scale = prefs.getFloat("scale", 1f);
        root.addView(ruler, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        Button gear = Ui.btn(this, "⚙  Calibra", Ui.CARD, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panelHost.setVisibility(panelHost.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 44),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        gp.bottomMargin = Ui.dp(this, 90);
        root.addView(gear, gp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, 0xF51A1D24, 20));
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 18));
        panel.setClickable(true);

        // titolo + chiusura
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Calibrazione", 18, Ui.ACCENT, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        head.addView(Ui.btn(this, "✕", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        panel.addView(head, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        TextView how = Ui.text(this, "Appoggia una carta di credito allo schermo e regola finche' la "
                + "sagoma tratteggiata coincide con la carta.", 12, Ui.DIM, false);
        how.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        panel.addView(how);

        // regolazione: [−]  scala  [＋]
        LinearLayout adj = new LinearLayout(this);
        adj.setGravity(Gravity.CENTER_VERTICAL);
        adj.addView(step("−", -0.005f), new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 50)));
        scaleText = Ui.text(this, "", 20, Ui.TEXT, true);
        scaleText.setGravity(Gravity.CENTER);
        adj.addView(scaleText, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        adj.addView(step("＋", 0.005f), new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 50)));
        panel.addView(adj, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // azioni
        LinearLayout acts = new LinearLayout(this);
        shapeBtn = Ui.btn(this, "", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ruler.card = !ruler.card;
                ruler.invalidate();
                styleShape();
            }
        });
        acts.addView(shapeBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        acts.addView(Ui.btn(this, "Ripristina", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setScale(1f);
            }
        }), Ui.lp(this, 0, Ui.dp(this, 48), 10, 0, 1f));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        ap.topMargin = Ui.dp(this, 18);
        panel.addView(acts, ap);
        styleShape();

        android.widget.ScrollView ps = new android.widget.ScrollView(this);
        ps.addView(panel, new ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP));
        panelHost = ps;
        panelHost.setVisibility(View.GONE);
        int w = Math.min(Ui.dp(this, 320), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(w, Ui.WRAP, Gravity.CENTER);
        root.addView(ps, pp);
        setContentView(root);
        updateText();
    }

    private Button step(String label, final float d) {
        return Ui.btn(this, label, Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ruler.card = true;
                styleShape();
                setScale(ruler.scale + d);
            }
        });
    }

    private void closePanel() {
        panelHost.setVisibility(View.GONE);
        ruler.card = false;
        ruler.invalidate();
        styleShape();
    }

    private void styleShape() {
        shapeBtn.setText(ruler.card ? "Sagoma: attiva" : "Sagoma: spenta");
        shapeBtn.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44FFFFFF),
                Ui.round(this, ruler.card ? Ui.ACCENT : Ui.CARD2, 12), null));
        shapeBtn.setTextColor(ruler.card ? 0xFF000000 : Ui.TEXT);
    }

    private void setScale(float s) {
        ruler.scale = Math.max(0.7f, Math.min(1.4f, s));
        prefs.edit().putFloat("scale", ruler.scale).apply();
        ruler.invalidate();
        updateText();
    }

    private void updateText() {
        scaleText.setText(String.format(Locale.ITALY, "Scala %.1f%%", ruler.scale * 100f));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
