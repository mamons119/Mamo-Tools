package it.mamotools;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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
                panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 44),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        gp.bottomMargin = Ui.dp(this, 90);
        root.addView(gear, gp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, 0xF01A1D24, 16));
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));
        panel.setVisibility(View.GONE);
        panel.addView(Ui.text(this, "Calibrazione", 16, Ui.ACCENT, true));
        TextView how = Ui.text(this, "Appoggia una carta di credito allo schermo e regola finche' la "
                + "sagoma tratteggiata coincide con la carta.", 12, Ui.DIM, false);
        how.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        panel.addView(how);
        scaleText = Ui.text(this, "", 20, Ui.TEXT, true);
        scaleText.setGravity(Gravity.CENTER);
        panel.addView(scaleText);
        LinearLayout r = new LinearLayout(this);
        r.addView(step("−", -0.005f), Ui.lp(this, 0, Ui.dp(this, 46), 0, 0, 1f));
        r.addView(step("＋", 0.005f), Ui.lp(this, 0, Ui.dp(this, 46), 8, 0, 1f));
        panel.addView(r, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(Ui.btn(this, "Sagoma on/off", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ruler.card = !ruler.card;
                ruler.invalidate();
            }
        }), Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        r2.addView(Ui.btn(this, "Ripristina", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setScale(1f);
            }
        }), Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        panel.addView(r2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        panel.addView(Ui.btn(this, "Chiudi", Ui.ACCENT, 0xFF000000, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panel.setVisibility(View.GONE);
                ruler.card = false;
                ruler.invalidate();
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 8));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Ui.dp(this, 270), Ui.WRAP, Gravity.CENTER);
        root.addView(panel, pp);
        setContentView(root);
        updateText();
    }

    private Button step(String label, final float d) {
        return Ui.btn(this, label, Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ruler.card = true;
                setScale(ruler.scale + d);
            }
        });
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
