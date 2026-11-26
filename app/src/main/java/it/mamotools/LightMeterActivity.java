package it.mamotools;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * Intensita' luminosa: la fotocamera funziona da esposimetro a punto. Toccando lo schermo si sceglie il punto
 * da misurare (messa a fuoco ed esposizione si spostano li') e si legge luminanza, illuminamento stimato ed EV.
 * Sono stime ricavate da tempo di posa, ISO e diaframma: non sostituiscono un luxmetro calibrato.
 */
public class LightMeterActivity extends Activity implements SensorEventListener {

    private static final int REQ_CAM = 41;

    private AutoFitTextureView tv;
    private Reticle reticle;
    private TextView bigTv, luxTv, evTv, spotTv, descTv, rangeTv, sensTv, msgTv;
    private View barFill;
    private android.widget.Button holdBtn;

    private CameraManager manager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder builder;
    private HandlerThread thread;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CameraCharacteristics chars;
    private int sensorOrientation = 90;
    private Size previewSize;
    private float fNumber = 1.8f;

    // punto misurato (0..1 nella vista dell'anteprima)
    private volatile float spotU = 0.5f, spotV = 0.5f;
    // ultimi dati della camera
    private volatile double expTimeS = 0, iso = 0, fN = 0;
    private volatile boolean haveExp = false;

    private float smoothL = -1, minL = Float.MAX_VALUE, maxL = 0, smoothY = -1;
    private boolean hold = false;
    private float lightSensor = -1;
    private SensorManager sm;
    private Bitmap probe;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new AutoFitTextureView(this);
        tv.setSurfaceTextureListener(texListener);
        root.addView(tv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        reticle = new Reticle(this);
        root.addView(reticle, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        reticle.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                setSpot(e.getX(), e.getY(), e.getAction() == MotionEvent.ACTION_DOWN);
            }
            return true;
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, 0xE60F1115, 22));
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 14));
        panel.addView(Ui.text(this, "Intensità luminosa", 13, Ui.ACCENT, true));
        bigTv = Ui.text(this, "—", 38, Ui.TEXT, true);
        panel.addView(bigTv);
        descTv = Ui.text(this, "Tocca lo schermo per scegliere il punto da misurare", 12, Ui.DIM, false);
        panel.addView(descTv);
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.round(this, Ui.CARD2, 6));
        barFill = new View(this);
        barFill.setBackground(Ui.round(this, Ui.ACCENT, 6));
        bar.addView(barFill, new LinearLayout.LayoutParams(0, Ui.dp(this, 8)));
        panel.addView(bar, Ui.lp(this, Ui.MATCH, Ui.dp(this, 8), 0, 8));
        LinearLayout r1 = new LinearLayout(this);
        luxTv = cell("Illuminamento", r1);
        evTv = cell("EV (ISO 100)", r1);
        spotTv = cell("Luminosità punto", r1);
        panel.addView(r1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        rangeTv = Ui.text(this, "", 12, Ui.DIM, false);
        panel.addView(rangeTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        sensTv = Ui.text(this, "", 12, Ui.DIM, false);
        panel.addView(sensTv);
        msgTv = Ui.text(this, "", 12, Ui.DANGER, false);
        panel.addView(msgTv);
        LinearLayout br = new LinearLayout(this);
        br.addView(Ui.btn(this, "Centro", Ui.CARD2, Ui.ACCENT, v -> {
            spotU = 0.5f;
            spotV = 0.5f;
            applyRegions(true);
            reticle.invalidate();
        }), Ui.lp(this, 0, Ui.dp(this, Ui.H_CHIP), 0, 0, 1f));
        br.addView(Ui.btn(this, "Azzera min/max", Ui.CARD2, Ui.ACCENT, v -> {
            minL = Float.MAX_VALUE;
            maxL = 0;
        }), Ui.lp(this, 0, Ui.dp(this, Ui.H_CHIP), 8, 0, 1f));
        holdBtn = Ui.btn(this, "Blocca", Ui.CARD2, Ui.ACCENT, v -> {
            hold = !hold;
            holdBtn.setText(hold ? "Sblocca" : "Blocca");
        });
        br.addView(holdBtn, Ui.lp(this, 0, Ui.dp(this, Ui.H_CHIP), 8, 0, 1f));
        panel.addView(br, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM);
        pl.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 10));
        root.addView(panel, pl);
        setContentView(root);
    }

    private TextView cell(String label, LinearLayout row) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(Ui.text(this, label, 11, Ui.DIM, false));
        TextView v = Ui.text(this, "—", 15, Ui.TEXT, true);
        c.addView(v);
        row.addView(c, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        return v;
    }

    // =========================================================================== punto misurato

    private void setSpot(float x, float y, boolean down) {
        int l = tv.getLeft(), t = tv.getTop(), w = tv.getWidth(), h = tv.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float u = (x - l) / w, v = (y - t) / h;
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return;
        }
        spotU = u;
        spotV = v;
        reticle.invalidate();
        applyRegions(down);
    }

    /** Sposta esposizione e messa a fuoco sul punto toccato (coordinate del sensore). */
    private void applyRegions(final boolean triggerAf) {
        final CaptureRequest.Builder bd = builder;
        final CameraCaptureSession ss = session;
        final CameraCharacteristics ch = chars;
        if (bd == null || ss == null || ch == null) {
            return;
        }
        final float u = spotU, v = spotV;
        bg.post(() -> {
            try {
                android.graphics.Rect act = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (act == null) {
                    return;
                }
                int W = act.width(), H = act.height();
                float sx, sy;
                switch (sensorOrientation) {
                    case 90:
                        sx = v * W;
                        sy = (1 - u) * H;
                        break;
                    case 270:
                        sx = (1 - v) * W;
                        sy = u * H;
                        break;
                    case 180:
                        sx = (1 - u) * W;
                        sy = (1 - v) * H;
                        break;
                    default:
                        sx = u * W;
                        sy = v * H;
                }
                int half = Math.max(16, Math.min(W, H) / 14);
                int x0 = Math.max(0, Math.min(W - 2 * half, Math.round(sx) - half)) + act.left;
                int y0 = Math.max(0, Math.min(H - 2 * half, Math.round(sy) - half)) + act.top;
                MeteringRectangle[] reg = {new MeteringRectangle(x0, y0, 2 * half, 2 * half,
                        MeteringRectangle.METERING_WEIGHT_MAX - 1)};
                Integer ae = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
                Integer af = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                if (ae != null && ae > 0) {
                    bd.set(CaptureRequest.CONTROL_AE_REGIONS, reg);
                }
                if (af != null && af > 0) {
                    bd.set(CaptureRequest.CONTROL_AF_REGIONS, reg);
                }
                if (triggerAf && af != null && af > 0) {
                    bd.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    ss.capture(bd.build(), null, bg);
                    bd.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
                ss.setRepeatingRequest(bd.build(), capCb, bg);
            } catch (Exception ignored) {
            }
        });
    }

    // =========================================================================== camera

    private final TextureView.SurfaceTextureListener texListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    };

    private void openCamera() {
        if (camera != null || !tv.isAvailable()) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        try {
            String id = null;
            for (String c : manager.getCameraIdList()) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(c);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) {
                    id = c;
                    chars = ch;
                    break;
                }
            }
            if (id == null) {
                msgTv.setText("Nessuna fotocamera posteriore trovata.");
                return;
            }
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            float[] ap = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (ap != null && ap.length > 0) {
                fNumber = ap[0];
            }
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = pickSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            // schermo in verticale: larghezza e altezza dell'anteprima si scambiano
            if (sensorOrientation == 90 || sensorOrientation == 270) {
                tv.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            } else {
                tv.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            }
            manager.openCamera(id, stateCb, bg);
        } catch (SecurityException | CameraAccessException e) {
            msgTv.setText("Impossibile aprire la fotocamera.");
        }
    }

    private Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) {
                continue;
            }
            boolean wide = Math.abs(s.getWidth() * 9f / s.getHeight() - 16f) < 0.2f;
            if (!wide) {
                continue;
            }
            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        if (best != null) {
            return best;
        }
        Size any = sizes[0];
        for (Size s : sizes) {
            if (s.getWidth() <= 1920 && s.getWidth() * s.getHeight() > any.getWidth() * any.getHeight()) {
                any = s;
            }
        }
        return any;
    }

    private final CameraDevice.StateCallback stateCb = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice c) {
            camera = c;
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice c) {
            c.close();
            camera = null;
        }

        @Override
        public void onError(CameraDevice c, int err) {
            c.close();
            camera = null;
            ui.post(() -> msgTv.setText("Errore della fotocamera (" + err + ")."));
        }
    };

    private void startPreview() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null || camera == null) {
                return;
            }
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface sf = new Surface(st);
            builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(sf);
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Collections.singletonList(sf), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    applyRegions(false);
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    ui.post(() -> msgTv.setText("Impossibile avviare l'anteprima."));
                }
            }, bg);
        } catch (Exception e) {
            ui.post(() -> msgTv.setText("Impossibile avviare l'anteprima."));
        }
    }

    private final CameraCaptureSession.CaptureCallback capCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            Long t = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso0 = res.get(CaptureResult.SENSOR_SENSITIVITY);
            Float ap = res.get(CaptureResult.LENS_APERTURE);
            if (t == null || iso0 == null || t <= 0 || iso0 <= 0) {
                return;
            }
            double boost = 100;
            try {
                Integer bs = res.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
                if (bs != null && bs > 0) {
                    boost = bs;
                }
            } catch (Throwable ignored) {
            }
            expTimeS = t / 1e9;
            iso = iso0 * boost / 100.0;
            fN = ap != null && ap > 0 ? ap : fNumber;
            haveExp = true;
        }
    };

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CAM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                msgTv.setText("Serve il permesso della fotocamera per misurare la luce.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        thread = new HandlerThread("lightmeter");
        thread.start();
        bg = new Handler(thread.getLooper());
        Sensor ls = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (ls != null) {
            sm.registerListener(this, ls, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (tv.isAvailable()) {
            openCamera();
        }
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        sm.unregisterListener(this);
        try {
            if (session != null) {
                session.close();
            }
            if (camera != null) {
                camera.close();
            }
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        builder = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        lightSensor = e.values[0];
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }

    // =========================================================================== calcolo e visualizzazione

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            reticle.invalidate();
            ui.postDelayed(this, 180);
        }
    };

    /** Luminosita' media (0..255) dell'area attorno al punto, letta dall'anteprima. */
    private float spotLuma() {
        if (!tv.isAvailable() || tv.getWidth() <= 0) {
            return -1;
        }
        int bw = 72, bh = Math.max(8, Math.round(72f * tv.getHeight() / tv.getWidth()));
        if (probe == null || probe.getWidth() != bw || probe.getHeight() != bh) {
            probe = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        }
        tv.getBitmap(probe);
        int cx = Math.round(spotU * (bw - 1)), cy = Math.round(spotV * (bh - 1));
        int rad = Math.max(2, bw / 14);
        int x0 = Math.max(0, cx - rad), x1 = Math.min(bw - 1, cx + rad);
        int y0 = Math.max(0, cy - rad), y1 = Math.min(bh - 1, cy + rad);
        double sum = 0;
        int n = 0;
        int[] px = new int[(x1 - x0 + 1) * (y1 - y0 + 1)];
        probe.getPixels(px, 0, x1 - x0 + 1, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        for (int p : px) {
            sum += 0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p);
            n++;
        }
        return n == 0 ? -1 : (float) (sum / n);
    }

    private static double lin(double y255) {
        double c = y255 / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private void update() {
        if (hold) {
            return;
        }
        float y = spotLuma();
        if (y >= 0) {
            smoothY = smoothY < 0 ? y : smoothY * 0.6f + y * 0.4f;
        }
        if (!haveExp) {
            return;
        }
        double t = expTimeS, s = iso, n = fN;
        double ev100 = Math.log(n * n / t) / Math.log(2) - Math.log(s / 100.0) / Math.log(2);
        // l'esposizione automatica porta il punto misurato a un grigio medio (18%): la differenza si corregge con i pixel
        double corr = 1.0;
        boolean sat = false;
        if (smoothY >= 0) {
            double lv = lin(smoothY);
            corr = Math.max(0.05, Math.min(8.0, lv / 0.18));
            sat = smoothY > 245 || smoothY < 4;
        }
        double L = 12.5 * n * n / (t * s) * corr;          // cd/m2
        double lux = 250.0 * n * n / (t * s) * corr;       // illuminamento stimato
        double evAdj = ev100 + Math.log(corr) / Math.log(2);
        smoothL = smoothL < 0 ? (float) L : smoothL * 0.6f + (float) L * 0.4f;
        minL = Math.min(minL, smoothL);
        maxL = Math.max(maxL, smoothL);

        bigTv.setText(fmt(smoothL) + " cd/m²");
        luxTv.setText(fmt(smoothL * 20f) + " lux");
        evTv.setText(String.format(Locale.US, "%.1f", evAdj));
        spotTv.setText(smoothY < 0 ? "—" : Math.round(smoothY / 255f * 100) + "%");
        descTv.setText(describe(smoothL * 20f) + (sat ? " · punto fuori scala, misura incerta" : ""));
        rangeTv.setText("Min " + fmt(minL) + " · Max " + fmt(maxL) + " cd/m²");
        sensTv.setText("Tempo 1/" + Math.round(1.0 / t) + " s · ISO " + Math.round(s) + " · f/" + String.format(Locale.US, "%.1f", n)
                + (lightSensor >= 0 ? " · sensore di luce del telefono: " + fmt(lightSensor) + " lux" : ""));
        // barra su scala logaritmica (0.1 .. 100000 cd/m2)
        double frac = (Math.log10(Math.max(0.1, smoothL)) + 1) / 6.0;
        frac = Math.max(0, Math.min(1, frac));
        View parent = (View) barFill.getParent();
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) barFill.getLayoutParams();
        lp.width = (int) (parent.getWidth() * frac);
        barFill.setLayoutParams(lp);
    }

    private static String fmt(float v) {
        if (v >= 1000) {
            return String.format(Locale.ITALY, "%,.0f", v);
        }
        if (v >= 100) {
            return String.format(Locale.ITALY, "%.0f", v);
        }
        if (v >= 10) {
            return String.format(Locale.ITALY, "%.1f", v);
        }
        return String.format(Locale.ITALY, "%.2f", v);
    }

    private static String describe(float lux) {
        if (lux < 1) {
            return "Buio quasi totale";
        } else if (lux < 10) {
            return "Molto scuro";
        } else if (lux < 50) {
            return "Luce soffusa";
        } else if (lux < 200) {
            return "Interno poco illuminato";
        } else if (lux < 800) {
            return "Interno illuminato (casa, ufficio)";
        } else if (lux < 3000) {
            return "Ambiente molto luminoso";
        } else if (lux < 20000) {
            return "All'aperto, cielo coperto o ombra";
        } else if (lux < 60000) {
            return "All'aperto, giorno luminoso";
        }
        return "Sole diretto";
    }

    // =========================================================================== mirino

    private final class Reticle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Reticle(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(c, 2));
            p.setColor(Ui.ACCENT);
        }

        @Override
        protected void onDraw(Canvas c) {
            int l = tv.getLeft(), t = tv.getTop(), w = tv.getWidth(), h = tv.getHeight();
            if (w <= 0) {
                return;
            }
            float x = l + spotU * w, y = t + spotV * h;
            float r = Ui.dp(getContext(), 26);
            c.drawCircle(x, y, r, p);
            float a = Ui.dp(getContext(), 8);
            c.drawLine(x - r - a, y, x - r + a, y, p);
            c.drawLine(x + r - a, y, x + r + a, y, p);
            c.drawLine(x, y - r - a, x, y - r + a, p);
            c.drawLine(x, y + r - a, x, y + r + a, p);
        }
    }
}
