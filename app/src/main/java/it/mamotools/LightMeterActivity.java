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
    private TextView bigTv, luxTv, evTv, spotTv, descTv, minTv, maxTv, phoneTv, expTv, msgTv;
    private ScaleBar scaleBar;
    private LinearLayout details;
    private TextView holdBtn, detailsBtn;
    // quanta parte dell'immagine della camera e' visibile nella vista (l'anteprima riempie lo schermo ritagliando i bordi)
    private volatile float imgSx = 1f, imgSy = 1f;
    private int bufW = 1080, bufH = 1920;

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
        tv.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fitPreview());
        root.addView(tv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        reticle = new Reticle(this);
        root.addView(reticle, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        reticle.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                setSpot(e.getX(), e.getY(), e.getAction() == MotionEvent.ACTION_DOWN);
            }
            return true;
        });

        // barra in alto: indietro + titolo
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹", 26, Ui.TEXT, true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.round(this, 0xAA0F1115, 100));
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        TextView ttl = Ui.text(this, "Intensità luminosa", 14, Ui.TEXT, true);
        ttl.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        ttl.setGravity(Gravity.CENTER);
        ttl.setBackground(Ui.round(this, 0xAA0F1115, 100));
        top.addView(ttl, Ui.lp(this, Ui.WRAP, Ui.dp(this, 40), 8, 0));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.START);
        tl.setMargins(Ui.dp(this, 12), Ui.dp(this, 12), 0, 0);
        root.addView(top, tl);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, 0xEB0F1115, 24));
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        // valore principale
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        bigTv = Ui.text(this, "—", 44, Ui.TEXT, true);
        head.addView(bigTv);
        TextView unit = Ui.text(this, "cd/m²", 16, Ui.DIM, true);
        unit.setPadding(Ui.dp(this, 8), 0, 0, Ui.dp(this, 9));
        head.addView(unit);
        panel.addView(head);
        descTv = Ui.text(this, "Tocca lo schermo per scegliere il punto da misurare", 13, Ui.ACCENT, true);
        panel.addView(descTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 2));
        scaleBar = new ScaleBar(this);
        panel.addView(scaleBar, Ui.lp(this, Ui.MATCH, Ui.dp(this, 38), 0, 10));

        // misure principali
        LinearLayout r1 = new LinearLayout(this);
        luxTv = tile("ILLUMINAMENTO", r1, 0);
        evTv = tile("EV (ISO 100)", r1, 8);
        spotTv = tile("LUMINOSITÀ PUNTO", r1, 8);
        panel.addView(r1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        // dettagli (comprimibili)
        details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r2 = new LinearLayout(this);
        minTv = tile("MINIMO", r2, 0);
        maxTv = tile("MASSIMO", r2, 8);
        phoneTv = tile("SENSORE TELEFONO", r2, 8);
        details.addView(r2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        expTv = Ui.text(this, "", 12, Ui.DIM, false);
        expTv.setGravity(Gravity.CENTER);
        details.addView(expTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        panel.addView(details);
        msgTv = Ui.text(this, "", 12, Ui.DANGER, false);
        panel.addView(msgTv);

        // pulsanti
        LinearLayout br = new LinearLayout(this);
        br.addView(pill("⌖  Centro", v -> {
            spotU = 0.5f;
            spotV = 0.5f;
            applyRegions(true);
            reticle.invalidate();
        }), Ui.lp(this, 0, Ui.dp(this, 46), 0, 0, 1f));
        br.addView(pill("↺  Azzera", v -> {
            minL = Float.MAX_VALUE;
            maxL = 0;
        }), Ui.lp(this, 0, Ui.dp(this, 46), 8, 0, 1f));
        holdBtn = pill("⏸  Blocca", v -> {
            hold = !hold;
            holdBtn.setText(hold ? "▶  Sblocca" : "⏸  Blocca");
            holdBtn.setBackground(Ui.round(this, hold ? Ui.ACCENT : Ui.CARD2, 14));
            holdBtn.setTextColor(hold ? 0xFF000000 : Ui.ACCENT);
        });
        br.addView(holdBtn, Ui.lp(this, 0, Ui.dp(this, 46), 8, 0, 1f));
        detailsBtn = pill("▾", v -> {
            boolean open = details.getVisibility() != View.VISIBLE;
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            detailsBtn.setText(open ? "▾" : "▴");
        });
        br.addView(detailsBtn, Ui.lp(this, Ui.dp(this, 46), Ui.dp(this, 46), 8, 0));
        panel.addView(br, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM);
        pl.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 10));
        root.addView(panel, pl);
        setContentView(root);
    }

    private TextView pill(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 14, Ui.ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44FFFFFF), Ui.round(this, Ui.CARD2, 14), null));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** Riquadro con etichetta piccola e valore grande. */
    private TextView tile(String label, LinearLayout row, int leftMargin) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, Ui.CARD2, 12));
        c.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 9));
        TextView l = Ui.text(this, label, 10, Ui.DIM, true);
        l.setSingleLine(true);
        l.setLetterSpacing(0.04f);
        c.addView(l);
        TextView v = Ui.text(this, "—", 18, Ui.TEXT, true);
        v.setSingleLine(true);
        v.setPadding(0, Ui.dp(this, 2), 0, 0);
        c.addView(v);
        row.addView(c, Ui.lp(this, 0, Ui.WRAP, leftMargin, 0, 1f));
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
        final float u = 0.5f + (spotU - 0.5f) / imgSx, v = 0.5f + (spotV - 0.5f) / imgSy;
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
            fitPreview();
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
                bufW = previewSize.getHeight();
                bufH = previewSize.getWidth();
            } else {
                bufW = previewSize.getWidth();
                bufH = previewSize.getHeight();
            }
            ui.post(this::fitPreview);
            manager.openCamera(id, stateCb, bg);
        } catch (SecurityException | CameraAccessException e) {
            msgTv.setText("Impossibile aprire la fotocamera.");
        }
    }

    /**
     * L'anteprima riempie tutto lo schermo senza deformarsi: la TextureView stira il frame sulla propria area, quindi
     * si applica una scala uniforme (la stessa in larghezza e altezza) e si ritagliano i bordi in eccesso.
     */
    private void fitPreview() {
        int vw = tv.getWidth(), vh = tv.getHeight();
        if (vw <= 0 || vh <= 0 || bufW <= 0 || bufH <= 0) {
            return;
        }
        float s = Math.max(vw / (float) bufW, vh / (float) bufH);
        float sx = bufW * s / vw, sy = bufH * s / vh;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(sx, sy, vw / 2f, vh / 2f);
        tv.setTransform(m);
        imgSx = sx;
        imgSy = sy;
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

        float luxV = smoothL * 20f;
        bigTv.setText(fmt(smoothL));
        luxTv.setText(fmt(luxV) + " lx");
        evTv.setText(String.format(Locale.ITALY, "%.1f", evAdj));
        spotTv.setText(smoothY < 0 ? "—" : Math.round(smoothY / 255f * 100) + "%");
        descTv.setText(icon(luxV) + "  " + describe(luxV) + (sat ? "  ·  punto fuori scala" : ""));
        descTv.setTextColor(sat ? 0xFFFFB74D : Ui.ACCENT);
        minTv.setText(fmt(minL));
        maxTv.setText(fmt(maxL));
        phoneTv.setText(lightSensor >= 0 ? fmt(lightSensor) + " lx" : "n.d.");
        expTv.setText("Tempo 1/" + Math.round(1.0 / t) + " s   ·   ISO " + Math.round(s) + "   ·   f/"
                + String.format(Locale.ITALY, "%.1f", n) + "     (min e max in cd/m²)");
        scaleBar.set(frac(smoothL), minL == Float.MAX_VALUE ? -1 : frac(minL), maxL <= 0 ? -1 : frac(maxL));
    }

    /** Posizione sulla barra (scala logaritmica da 0,1 a 100.000 cd/m²). */
    private static float frac(float l) {
        double f = (Math.log10(Math.max(0.1, l)) + 1) / 6.0;
        return (float) Math.max(0, Math.min(1, f));
    }

    private static String icon(float lux) {
        if (lux < 1) {
            return "🌑";
        } else if (lux < 10) {
            return "🌘";
        } else if (lux < 50) {
            return "🌙";
        } else if (lux < 200) {
            return "💡";
        } else if (lux < 800) {
            return "🏠";
        } else if (lux < 3000) {
            return "🔆";
        } else if (lux < 20000) {
            return "⛅";
        } else if (lux < 60000) {
            return "🌤";
        }
        return "☀️";
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

    /** Barra logaritmica con gradiente, segni di minimo/massimo e tacche con i valori. */
    private final class ScaleBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float v = 0, mn = -1, mx = -1;
        private final String[] lab = {"0,1", "1", "10", "100", "1k", "10k", "100k"};

        ScaleBar(Context c) {
            super(c);
        }

        void set(float v, float mn, float mx) {
            this.v = v;
            this.mn = mn;
            this.mx = mx;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), d = Ui.dp(getContext(), 1), bh = 10 * d, r = bh / 2;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.CARD2);
            c.drawRoundRect(0, 0, w, bh, r, r, p);
            if (v > 0) {
                p.setShader(new android.graphics.LinearGradient(0, 0, w, 0, new int[]{0xFF1E88E5, Ui.ACCENT, 0xFFFFE082},
                        null, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(0, 0, Math.max(bh, w * v), bh, r, r, p);
                p.setShader(null);
            }
            p.setColor(0xFFFFFFFF);
            if (mn >= 0) {
                c.drawRect(w * mn - d, -2 * d, w * mn + d, bh + 2 * d, p);
            }
            if (mx >= 0) {
                c.drawRect(w * mx - d, -2 * d, w * mx + d, bh + 2 * d, p);
            }
            p.setTextSize(Ui.dp(getContext(), 10));
            p.setColor(Ui.DIM);
            for (int i = 0; i < lab.length; i++) {
                float x = w * i / 6f;
                p.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 6 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                c.drawText(lab[i], x, bh + 15 * d, p);
            }
        }

    }

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
