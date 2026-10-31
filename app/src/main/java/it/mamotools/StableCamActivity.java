package it.mamotools;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Stable Cam: registra video con la stabilizzazione del telefono (elettronica + ottica)
 * alla qualita' massima disponibile, e salva il file in Galleria come MamoRecord_*.mp4.
 * Usa direttamente Camera2 + MediaRecorder, senza librerie esterne.
 */
public class StableCamActivity extends Activity {

    private static final int REQ_PERMS = 21;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ------------------------------------------------------------------ modelli dati

    private static final class Quality {
        final CamcorderProfile profile;
        final Size size;
        final int fps;
        final int bitrate;
        final String label;

        Quality(CamcorderProfile profile, Size size, int fps, int bitrate, String label) {
            this.profile = profile;
            this.size = size;
            this.fps = fps;
            this.bitrate = bitrate;
            this.label = label;
        }
    }

    private static final class CamInfo {
        String id;
        String label;
        boolean front;
        int sensorOrientation;
        boolean eis;        // stabilizzazione elettronica disponibile
        boolean ois;        // stabilizzazione ottica disponibile
        boolean afVideo;    // messa a fuoco continua per video
        CameraCharacteristics ch;
        StreamConfigurationMap map;
        final List<Quality> qualities = new ArrayList<>();
    }

    // ------------------------------------------------------------------ stato

    private SharedPreferences prefs;
    private CameraManager manager;
    private final List<CamInfo> cams = new ArrayList<>();
    private int camIndex = 0;
    private int qualityIndex = 0;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private final Semaphore camLock = new Semaphore(1);
    private volatile boolean opening = false;
    private volatile boolean stabOn = true;

    private HandlerThread bgThread;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Size previewSize;
    private MediaRecorder recorder;
    private Uri outUri;
    private ParcelFileDescriptor outPfd;
    private String outName;
    private volatile boolean recording = false;
    private boolean starting = false;
    private int recordAttempts = 0;
    private long recStartMs;

    private boolean permAsked = false;

    // ------------------------------------------------------------------ interfaccia

    private AutoFitTextureView textureView;
    private TextView infoText, timerText;
    private Spinner qualitySpinner;
    private ArrayAdapter<String> qualityAdapter;
    private Button camButton, permButton;
    private Switch stabSwitch;
    private View recButton;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long s = (SystemClock.elapsedRealtime() - recStartMs) / 1000;
            timerText.setText(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60));
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("stablecam", MODE_PRIVATE);
        stabOn = prefs.getBoolean("stab", true);
        manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        buildUi();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new AutoFitTextureView(this);
        textureView.setSurfaceTextureListener(surfaceListener);
        root.addView(textureView, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        // barra in alto: informazioni
        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(12);
        infoText.setPadding(dp(12), dp(8), dp(12), dp(8));
        infoText.setBackgroundColor(0x88000000);
        root.addView(infoText, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));

        // pannello in basso: controlli
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0x99000000);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        qualitySpinner = new Spinner(this);
        qualityAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        qualitySpinner.setAdapter(qualityAdapter);
        qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == qualityIndex) {
                    return;
                }
                qualityIndex = pos;
                if (!recording && cameraDevice != null) {
                    startPreview();
                }
                updateInfo();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row1.addView(qualitySpinner, new LinearLayout.LayoutParams(0, WRAP, 1f));

        camButton = new Button(this);
        camButton.setText("Cambia camera");
        camButton.setAllCaps(false);
        camButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchCamera();
            }
        });
        row1.addView(camButton, new LinearLayout.LayoutParams(WRAP, WRAP));
        panel.addView(row1);

        stabSwitch = new Switch(this);
        stabSwitch.setText("Stabilizzazione");
        stabSwitch.setTextColor(Color.WHITE);
        stabSwitch.setTextSize(16);
        stabSwitch.setChecked(stabOn);
        stabSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                stabOn = checked;
                prefs.edit().putBoolean("stab", checked).apply();
                if (!recording && cameraDevice != null) {
                    startPreview();
                }
                updateInfo();
            }
        });
        stabSwitch.setPadding(0, dp(4), 0, dp(8));
        panel.addView(stabSwitch);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER_VERTICAL);

        timerText = new TextView(this);
        timerText.setTextColor(Color.WHITE);
        timerText.setTextSize(18);
        timerText.setTypeface(Typeface.DEFAULT_BOLD);
        timerText.setGravity(Gravity.CENTER);
        row3.addView(timerText, new LinearLayout.LayoutParams(0, WRAP, 1f));

        recButton = new View(this);
        recButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) {
                    stopRecording(true);
                } else {
                    recordAttempts = 0;
                    startRecording();
                }
            }
        });
        row3.addView(recButton, new LinearLayout.LayoutParams(dp(72), dp(72)));

        View spacer = new View(this);
        row3.addView(spacer, new LinearLayout.LayoutParams(0, WRAP, 1f));
        panel.addView(row3);

        root.addView(panel, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        permButton = new Button(this);
        permButton.setText("Concedi i permessi di fotocamera e microfono");
        permButton.setAllCaps(false);
        permButton.setVisibility(View.GONE);
        permButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        root.addView(permButton, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        setContentView(root);
        updateRecButton();
        timerText.setText("");
    }

    private void updateRecButton() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFE53935);
        d.setStroke(dp(4), Color.WHITE);
        if (recording) {
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(dp(14));
            recButton.setPadding(dp(18), dp(18), dp(18), dp(18));
        } else {
            d.setShape(GradientDrawable.OVAL);
        }
        // quadrato piccolo durante la registrazione: si ottiene con un inset
        if (recording) {
            android.graphics.drawable.InsetDrawable inset =
                    new android.graphics.drawable.InsetDrawable(d, dp(14));
            recButton.setBackground(inset);
        } else {
            recButton.setBackground(d);
        }
        boolean idle = !recording;
        qualitySpinner.setEnabled(idle);
        stabSwitch.setEnabled(idle);
        camButton.setEnabled(idle && cams.size() > 1);
    }

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onResume() {
        super.onResume();
        startBg();
        if (!hasPerms()) {
            infoText.setText("Servono i permessi di fotocamera e microfono.");
            if (!permAsked) {
                permAsked = true;
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
            } else {
                permButton.setVisibility(View.VISIBLE);
            }
        } else {
            permButton.setVisibility(View.GONE);
            if (textureView.isAvailable()) {
                openCamera();
            }
        }
    }

    @Override
    protected void onPause() {
        if (recording) {
            stopRecording(false);
        }
        closeCamera();
        stopBg();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // onResume viene richiamato subito dopo e fa il resto
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyAspect();
        configureTransform(textureView.getWidth(), textureView.getHeight());
    }

    private boolean hasPerms() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startBg() {
        if (bgThread != null) {
            return;
        }
        bgThread = new HandlerThread("StableCamBg");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
    }

    private void stopBg() {
        if (bgThread == null) {
            return;
        }
        bgThread.quitSafely();
        try {
            bgThread.join();
        } catch (InterruptedException ignored) {
        }
        bgThread = null;
        bg = null;
    }

    // ------------------------------------------------------------------ elenco camere e qualita'

    private static boolean contains(int[] arr, int v) {
        if (arr == null) {
            return false;
        }
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private void loadCameras() {
        cams.clear();
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (caps != null && !contains(caps,
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    continue;
                }
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    continue;
                }
                StreamConfigurationMap map =
                        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                CamInfo ci = new CamInfo();
                ci.id = id;
                ci.ch = ch;
                ci.map = map;
                ci.front = facing == CameraCharacteristics.LENS_FACING_FRONT;
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                ci.sensorOrientation = so == null ? 90 : so;
                ci.eis = contains(ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                ci.ois = contains(ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON);
                ci.afVideo = contains(ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                buildQualities(ci);
                if (ci.qualities.isEmpty()) {
                    continue;
                }
                ci.label = (ci.front ? "Frontale" : "Posteriore") + " · id " + id;
                cams.add(ci);
            }
        } catch (CameraAccessException e) {
            toast("Impossibile leggere le fotocamere: " + e.getMessage());
        }
        // prima le posteriori, poi le frontali
        Collections.sort(cams, (a, b) -> Boolean.compare(a.front, b.front));
        camIndex = 0;
        qualityIndex = 0;
        fillQualitySpinner();
    }

    private void buildQualities(CamInfo ci) {
        final int[] order = {
                13 /* QUALITY_8KUHD, introdotto con Android 12 */, CamcorderProfile.QUALITY_2160P,
                CamcorderProfile.QUALITY_2K, CamcorderProfile.QUALITY_QHD,
                CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
                CamcorderProfile.QUALITY_480P};
        final String[] names = {"8K", "4K", "2K", "QHD", "1080p", "720p", "480p"};

        int camNum;
        try {
            camNum = Integer.parseInt(ci.id);
        } catch (NumberFormatException e) {
            return;
        }
        Size[] supported = ci.map.getOutputSizes(MediaRecorder.class);
        if (supported == null) {
            return;
        }
        List<Size> supportedList = Arrays.asList(supported);

        for (int i = 0; i < order.length; i++) {
            CamcorderProfile p;
            try {
                if (!CamcorderProfile.hasProfile(camNum, order[i])) {
                    continue;
                }
                p = CamcorderProfile.get(camNum, order[i]);
            } catch (RuntimeException e) {
                continue;
            }
            if (p == null) {
                continue;
            }
            Size size = new Size(p.videoFrameWidth, p.videoFrameHeight);
            if (!supportedList.contains(size)) {
                continue;
            }
            int baseFps = p.videoFrameRate;
            ci.qualities.add(new Quality(p, size, baseFps, p.videoBitRate,
                    describe(names[i], baseFps, size)));
            if (baseFps < 60 && supports60(ci, size)) {
                int br = (int) Math.min(150_000_000L, (long) p.videoBitRate * 3 / 2);
                ci.qualities.add(new Quality(p, size, 60, br, describe(names[i], 60, size)));
            }
        }
    }

    private static String describe(String name, int fps, Size s) {
        return String.format(Locale.US, "%s · %d fps · %dx%d",
                name, fps, s.getWidth(), s.getHeight());
    }

    private boolean supports60(CamInfo ci, Size size) {
        Range<Integer>[] ranges =
                ci.ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        boolean ae60 = false;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (r.getUpper() >= 60) {
                    ae60 = true;
                    break;
                }
            }
        }
        if (!ae60) {
            return false;
        }
        try {
            long dur = ci.map.getOutputMinFrameDuration(MediaRecorder.class, size);
            return dur > 0 && dur <= 16_800_000L;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Range<Integer> pickFpsRange(CamInfo ci, int fps) {
        Range<Integer>[] rs =
                ci.ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> best = null;
        if (rs != null) {
            for (Range<Integer> r : rs) {
                if (r.getUpper() != fps) {
                    continue;
                }
                if (best == null || r.getLower() > best.getLower()) {
                    best = r;
                }
            }
        }
        return best;
    }

    private void fillQualitySpinner() {
        qualityAdapter.clear();
        if (cams.isEmpty()) {
            return;
        }
        List<Quality> qs = cams.get(camIndex).qualities;
        for (int i = 0; i < qs.size(); i++) {
            qualityAdapter.add(qs.get(i).label + (i == 0 ? "  (massima)" : ""));
        }
        qualityAdapter.notifyDataSetChanged();
        qualityIndex = 0;
        qualitySpinner.setSelection(0);
    }

    private CamInfo curCam() {
        return cams.get(camIndex);
    }

    private Quality curQuality() {
        List<Quality> qs = curCam().qualities;
        return qs.get(Math.max(0, Math.min(qualityIndex, qs.size() - 1)));
    }

    private void switchCamera() {
        if (cams.size() < 2 || recording) {
            return;
        }
        camIndex = (camIndex + 1) % cams.size();
        fillQualitySpinner();
        closeCamera();
        openCamera();
    }

    private void updateInfo() {
        if (cams.isEmpty()) {
            return;
        }
        CamInfo ci = curCam();
        Quality q = curQuality();
        String stab;
        if (!stabOn) {
            stab = "spenta";
        } else if (!ci.eis && !ci.ois) {
            stab = "non offerta da questa camera";
        } else {
            stab = (ci.eis ? "elettronica ✓" : "elettronica ✗") + " · "
                    + (ci.ois ? "ottica ✓" : "ottica ✗");
        }
        infoText.setText(ci.label + "\n" + q.label + "\nStabilizzazione: " + stab);
    }

    // ------------------------------------------------------------------ apertura camera

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    if (hasPerms()) {
                        openCamera();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    configureTransform(w, h);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cd) {
            cameraDevice = cd;
            opening = false;
            camLock.release();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    startPreview();
                }
            });
        }

        @Override
        public void onDisconnected(CameraDevice cd) {
            camLock.release();
            cd.close();
            cameraDevice = null;
            opening = false;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (recording) {
                        stopRecording(false);
                    }
                }
            });
        }

        @Override
        public void onError(CameraDevice cd, final int error) {
            camLock.release();
            cd.close();
            cameraDevice = null;
            opening = false;
            toast("Errore fotocamera (codice " + error + ")");
        }
    };

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (!hasPerms() || !textureView.isAvailable() || cameraDevice != null || opening) {
            return;
        }
        if (cams.isEmpty()) {
            loadCameras();
        }
        if (cams.isEmpty()) {
            infoText.setText("Nessuna fotocamera utilizzabile per il video.");
            return;
        }
        startBg();
        updateInfo();
        updateRecButton();
        boolean acquired = false;
        try {
            acquired = camLock.tryAcquire(2500, TimeUnit.MILLISECONDS);
            if (!acquired) {
                toast("Fotocamera occupata, riprova.");
                return;
            }
            opening = true;
            manager.openCamera(curCam().id, stateCallback, bg);
        } catch (Exception e) {
            opening = false;
            if (acquired) {
                camLock.release();
            }
            toast("Impossibile aprire la fotocamera: " + e.getMessage());
        }
    }

    private void closeCamera() {
        boolean acquired = false;
        try {
            acquired = camLock.tryAcquire(3000, TimeUnit.MILLISECONDS);
            closeSession();
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            releaseRecorder();
        } catch (InterruptedException ignored) {
        } finally {
            if (acquired) {
                camLock.release();
            }
        }
    }

    private void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
    }

    // ------------------------------------------------------------------ anteprima

    private void applyAspect() {
        if (previewSize == null) {
            return;
        }
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (land) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float cx = viewRect.centerX();
        float cy = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        }
        textureView.setTransform(matrix);
    }

    /** Anteprima: stesse proporzioni del video, al massimo 1080p (leggera e fluida). */
    private Size choosePreview(CamInfo ci, Size video) {
        Size[] sizes = ci.map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return video;
        }
        double target = (double) video.getWidth() / video.getHeight();
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) {
                continue;
            }
            if (Math.abs((double) s.getWidth() / s.getHeight() - target) > 0.02) {
                continue;
            }
            if (best == null || (long) s.getWidth() * s.getHeight()
                    > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        if (best != null) {
            return best;
        }
        // nessuna con le stesse proporzioni: la piu' vicina
        double bestDiff = Double.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) {
                continue;
            }
            double diff = Math.abs((double) s.getWidth() / s.getHeight() - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    private void applySettings(CaptureRequest.Builder b, CamInfo ci, Quality q, boolean record) {
        if (ci.afVideo) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        if (ci.eis) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabOn
                    ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
        if (ci.ois) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, stabOn
                    ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if (record) {
            Range<Integer> r = pickFpsRange(ci, q.fps);
            if (r != null) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r);
            }
        }
    }

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable() || recording || starting
                || cams.isEmpty()) {
            return;
        }
        try {
            closeSession();
            CamInfo ci = curCam();
            Quality q = curQuality();
            previewSize = choosePreview(ci, q.size);
            applyAspect();
            configureTransform(textureView.getWidth(), textureView.getHeight());

            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface ps = new Surface(st);

            final CaptureRequest.Builder b =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(ps);
            applySettings(b, ci, q, false);

            cameraDevice.createCaptureSession(Collections.singletonList(ps),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cs) {
                            session = cs;
                            try {
                                cs.setRepeatingRequest(b.build(), null, bg);
                            } catch (Exception e) {
                                toast("Anteprima non riuscita: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cs) {
                            toast("Configurazione anteprima non riuscita.");
                        }
                    }, bg);
        } catch (Exception e) {
            toast("Anteprima non riuscita: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ registrazione

    private int orientationHint(CamInfo ci) {
        int deg = getWindowManager().getDefaultDisplay().getRotation() * 90;
        return ci.front
                ? (ci.sensorOrientation + deg) % 360
                : (ci.sensorOrientation - deg + 360) % 360;
    }

    private void createOutput() throws IOException {
        String name = "MamoRecord_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MamoTools");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (outUri == null) {
            throw new IOException("impossibile creare il file in Galleria");
        }
        outPfd = getContentResolver().openFileDescriptor(outUri, "rw");
        if (outPfd == null) {
            throw new IOException("impossibile aprire il file in Galleria");
        }
        outName = name + ".mp4";
    }

    /** ok = true: il file diventa visibile in Galleria; false: viene cancellato. */
    private void closeOutput(boolean ok) {
        if (outPfd != null) {
            try {
                outPfd.close();
            } catch (IOException ignored) {
            }
            outPfd = null;
        }
        if (outUri != null) {
            try {
                if (ok) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, v, null, null);
                } else {
                    getContentResolver().delete(outUri, null, null);
                }
            } catch (Exception ignored) {
            }
            outUri = null;
        }
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    private void setupRecorder(CamInfo ci, Quality q) throws IOException {
        createOutput();
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setProfile(q.profile);   // formato, codec, risoluzione, bitrate, audio
        recorder.setOutputFile(outPfd.getFileDescriptor());
        if (q.fps != q.profile.videoFrameRate) {
            recorder.setVideoFrameRate(q.fps);
            recorder.setVideoEncodingBitRate(q.bitrate);
        }
        recorder.setOrientationHint(orientationHint(ci));
        recorder.prepare();
    }

    private void startRecording() {
        if (cameraDevice == null || recording || starting || cams.isEmpty()) {
            return;
        }
        starting = true;
        try {
            final CamInfo ci = curCam();
            final Quality q = curQuality();
            closeSession();
            setupRecorder(ci, q);

            previewSize = choosePreview(ci, q.size);
            applyAspect();
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface ps = new Surface(st);
            Surface rs = recorder.getSurface();

            final CaptureRequest.Builder b =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(ps);
            b.addTarget(rs);
            applySettings(b, ci, q, true);

            cameraDevice.createCaptureSession(Arrays.asList(ps, rs),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cs) {
                            session = cs;
                            try {
                                cs.setRepeatingRequest(b.build(), null, bg);
                            } catch (final Exception e) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        recordFailed(e.getMessage());
                                    }
                                });
                                return;
                            }
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        recorder.start();
                                    } catch (RuntimeException e) {
                                        recordFailed(e.getMessage());
                                        return;
                                    }
                                    recording = true;
                                    starting = false;
                                    recStartMs = SystemClock.elapsedRealtime();
                                    ui.post(tick);
                                    updateRecButton();
                                }
                            });
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cs) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    recordFailed("la camera non supporta questa combinazione");
                                }
                            });
                        }
                    }, bg);
        } catch (Exception e) {
            recordFailed(e.getMessage());
        }
    }

    /** Indice di un profilo piu' leggero da provare quando quello scelto non parte. */
    private int fallbackIndex(int idx) {
        List<Quality> qs = curCam().qualities;
        Quality cur = qs.get(idx);
        if (cur.fps > 30 && idx > 0 && qs.get(idx - 1).size.equals(cur.size)) {
            return idx - 1;   // stessa risoluzione a 30 fps
        }
        for (int j = idx + 1; j < qs.size(); j++) {
            if (qs.get(j).fps <= cur.fps) {
                return j;
            }
        }
        return -1;
    }

    private void recordFailed(String why) {
        recording = false;
        starting = false;
        ui.removeCallbacks(tick);
        closeSession();
        releaseRecorder();
        closeOutput(false);
        recordAttempts++;

        int next = fallbackIndex(qualityIndex);
        if (next >= 0 && recordAttempts < 6 && cameraDevice != null) {
            Quality old = curQuality();
            qualityIndex = next;
            qualitySpinner.setSelection(next);
            toast("Non riesco a registrare a " + old.label + ". Provo " + curQuality().label);
            updateInfo();
            startRecording();
        } else {
            toast("Registrazione non riuscita" + (why != null ? ": " + why : ""));
            timerText.setText("");
            updateRecButton();
            startPreview();
        }
    }

    private void stopRecording(boolean restartPreview) {
        if (!recording) {
            return;
        }
        recording = false;
        ui.removeCallbacks(tick);
        timerText.setText("");
        if (session != null) {
            try {
                session.stopRepeating();
                session.abortCaptures();
            } catch (Exception ignored) {
            }
        }
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false;   // registrazione troppo breve o interrotta
        }
        releaseRecorder();
        String name = outName;
        closeOutput(ok);
        if (ok) {
            toast("Salvato in Galleria (Movies/MamoTools): " + name);
        } else {
            toast("Registrazione troppo breve: non salvata.");
        }
        updateRecButton();
        if (restartPreview) {
            startPreview();
        }
    }

    // ------------------------------------------------------------------ utilita'

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(StableCamActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
