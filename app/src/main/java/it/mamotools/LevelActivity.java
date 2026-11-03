package it.mamotools;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Livella a bolla con l'accelerometro/gravita' (il giroscopio non da' l'inclinazione assoluta). */
public class LevelActivity extends Activity implements SensorEventListener {

    private SensorManager sm;
    private Sensor sensor;
    private LevelView view;
    private float fx, fy, fz;
    private boolean have = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (sensor == null) {
            sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        view = new LevelView(this);
        root.addView(view, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        TextView title = Ui.text(this, "Livello", 20, Ui.TEXT, true);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        tp.leftMargin = Ui.dp(this, 20);
        tp.topMargin = Ui.dp(this, 28);
        root.addView(title, tp);

        LinearLayout bar = new LinearLayout(this);
        bar.addView(Ui.btn(this, "Azzera qui", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view.zeroX = view.ax;
                view.zeroY = view.ay;
                view.invalidate();
            }
        }), Ui.lp(this, 0, Ui.dp(this, 50), 0, 0, 1f));
        bar.addView(Ui.btn(this, "Ripristina", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view.zeroX = 0;
                view.zeroY = 0;
                view.invalidate();
            }
        }), Ui.lp(this, 0, Ui.dp(this, 50), 10, 0, 1f));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP,
                android.view.Gravity.BOTTOM);
        bp.leftMargin = Ui.dp(this, 16);
        bp.rightMargin = Ui.dp(this, 16);
        bp.bottomMargin = Ui.dp(this, 28);
        root.addView(bar, bp);

        if (sensor == null) {
            TextView t = Ui.text(this, "Questo telefono non ha un sensore di inclinazione.", 16,
                    Ui.DANGER, false);
            t.setGravity(android.view.Gravity.CENTER);
            root.addView(t, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensor != null) {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float g = SensorManager.GRAVITY_EARTH;
        float x = e.values[0] / g, y = e.values[1] / g, z = e.values[2] / g;
        float sx, sy;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                sx = -y;
                sy = x;
                break;
            case Surface.ROTATION_180:
                sx = -x;
                sy = -y;
                break;
            case Surface.ROTATION_270:
                sx = y;
                sy = -x;
                break;
            default:
                sx = x;
                sy = y;
                break;
        }
        float a = have ? 0.2f : 1f;
        fx += a * (sx - fx);
        fy += a * (sy - fy);
        fz += a * (z - fz);
        have = true;
        view.ax = fx;
        view.ay = fy;
        view.az = fz;
        view.invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }
}
