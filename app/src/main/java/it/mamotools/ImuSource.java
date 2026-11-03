package it.mamotools;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Sensori di movimento e grandezze derivate nel riferimento del veicolo.
 * Piega: atan(v * velocita' di imbardata / g), con v dal GPS e imbardata dal giroscopio proiettato
 * sulla verticale (indipendente da come e' montato il telefono). Senza GPS: angolo apparente
 * dalla gravita'. Segni: positivo = sinistra.
 */
final class ImuSource implements SensorEventListener {

    static final float G = 9.80665f;

    private final SensorManager sm;
    final boolean hasGrav, hasGyro, hasLin, hasAcc;
    private final Sensor sGrav, sGyro, sLin, sAcc;
    private boolean running = false;

    private final float[] g = {0, 0, G}, w = new float[3], lin = new float[3], acc = new float[3];
    private float speed = 0;
    private long speedAtMs = 0;

    int mount = 0;          // 0 = telefono in piedi, schermo verso di me; 1 = disteso, schermo in alto
    boolean invert = false; // inverte il senso di marcia

    volatile float leanDeg, leanApparentDeg, aLong, aLat, yawDegS;
    volatile boolean leanFromGps = false;
    long lastEventMs = 0;

    ImuSource(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        sGrav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sLin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sAcc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        hasGrav = sGrav != null;
        hasGyro = sGyro != null;
        hasLin = sLin != null;
        hasAcc = sAcc != null;
    }

    boolean usable() {
        return (hasGrav || hasAcc) && (hasLin || hasAcc);
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        int d = SensorManager.SENSOR_DELAY_GAME;
        if (sGrav != null) {
            sm.registerListener(this, sGrav, d);
        }
        if (sGyro != null) {
            sm.registerListener(this, sGyro, d);
        }
        if (sLin != null) {
            sm.registerListener(this, sLin, d);
        }
        if (sAcc != null && (sGrav == null || sLin == null)) {
            sm.registerListener(this, sAcc, d);
        }
    }

    void stop() {
        running = false;
        sm.unregisterListener(this);
    }

    /** Velocita' GPS (m/s); se non arriva da piu' di 3 s vale 0. */
    void setSpeed(float v) {
        speed = v;
        speedAtMs = SystemClock.elapsedRealtime();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        lastEventMs = SystemClock.elapsedRealtime();
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                System.arraycopy(e.values, 0, g, 0, 3);
                break;
            case Sensor.TYPE_GYROSCOPE:
                System.arraycopy(e.values, 0, w, 0, 3);
                update();
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                System.arraycopy(e.values, 0, lin, 0, 3);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                for (int i = 0; i < 3; i++) {
                    acc[i] = e.values[i];
                }
                if (sGrav == null) {
                    for (int i = 0; i < 3; i++) {
                        g[i] += 0.08f * (acc[i] - g[i]);
                    }
                }
                if (sLin == null) {
                    for (int i = 0; i < 3; i++) {
                        lin[i] = acc[i] - g[i];
                    }
                }
                if (!hasGyro) {
                    update();
                }
                break;
            default:
                break;
        }
    }

    private float yawF = 0, aLongF = 0, aLatF = 0;

    private void update() {
        float gn = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (gn < 1f) {
            return;
        }
        float[] u = {g[0] / gn, g[1] / gn, g[2] / gn};
        float[] f0 = mount == 0 ? new float[]{0, 0, -1} : new float[]{0, 1, 0};
        float[] n0 = mount == 0 ? new float[]{0, 1, 0} : new float[]{0, 0, 1};
        if (invert) {
            f0[0] = -f0[0];
            f0[1] = -f0[1];
            f0[2] = -f0[2];
        }
        // avanti reale: f0 privato della componente lungo la verticale
        float d = dot(f0, u);
        float[] f = {f0[0] - d * u[0], f0[1] - d * u[1], f0[2] - d * u[2]};
        float fn = (float) Math.sqrt(dot(f, f));
        if (fn < 0.2f) {
            return;
        }
        f[0] /= fn;
        f[1] /= fn;
        f[2] /= fn;
        float[] r = cross(f, u); // destra

        float yaw = dot(w, u);   // rad/s, positivo = sinistra
        yawF += 0.3f * (yaw - yawF);
        boolean gps = SystemClock.elapsedRealtime() - speedAtMs < 3000;
        float v = gps ? speed : 0;

        float[] r0 = cross(f0, n0);
        leanApparentDeg = (float) Math.toDegrees(Math.atan2(dot(u, r0), dot(u, n0)));
        float leanGps = (float) Math.toDegrees(Math.atan(v * yawF / G));
        leanFromGps = hasGyro && gps && v > 2.5f;
        leanDeg = leanFromGps ? leanGps : leanApparentDeg;
        yawDegS = (float) Math.toDegrees(yawF);

        float al = dot(lin, f) / G;
        float alSens = -dot(lin, r) / G;
        float latNow = (hasGyro && gps && v > 3f) ? v * yawF / G : alSens;
        aLongF += 0.35f * (al - aLongF);
        aLatF += 0.35f * (latNow - aLatF);
        aLong = aLongF;
        aLat = aLatF;
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }
}
