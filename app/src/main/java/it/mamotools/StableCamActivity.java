package it.mamotools;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
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
import android.util.Rational;
import android.util.Size;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
 *
 * Interfaccia: anteprima a tutto schermo, barra indicatori in alto, comandi in basso
 * (in orizzontale: a destra), impostazioni in un pannello che sale dal basso.
 */
public class StableCamActivity extends Activity {

    private static final int REQ_PERMS = 21;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int ACCENT = 0xFF4DD0E1;
    private static final int REC_RED = 0xFFE53935;
    private static final int PILL_BG = 0x99000000;
    private static final int BAR_BG = 0x88000000;
    private static final int SHEET_BG = 0xFF1A1D24;
    private static final int TEXT_DIM = 0xFF9AA3B2;

    private static final int[] WB_MODES = {
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE,
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT};
    private static final String[] WB_NAMES = {
            "Automatico", "Luce diurna", "Nuvoloso", "Ombra", "Incandescenza", "Fluorescente"};
    private static final int[] TIMER_SECS = {0, 3, 5, 10};
    private static final String[] TIMER_NAMES = {"Nessuno", "3 secondi", "5 secondi", "10 secondi"};

    // ------------------------------------------------------------------ modelli dati

    private static final class Quality {
        final CamcorderProfile profile;
        final Size size;
        final int fps;
        final int bitrate;
        final String label;        // completa, con risoluzione in pixel
        final String shortLabel;   // per l'indicatore in alto

        Quality(CamcorderProfile profile, Size size, int fps, int bitrate,
                String label, String shortLabel) {
            this.profile = profile;
            this.size = size;
            this.fps = fps;
            this.bitrate = bitrate;
            this.label = label;
            this.shortLabel = shortLabel;
        }
    }

    private static final class CamInfo {
        String id;
        String openId;      // id da aprire (diverso da id per le lenti fisiche nascoste)
        String physId;      // lente fisica scelta dentro una camera logica
        String label;
        boolean front;
        int sensorOrientation;
        boolean eis;        // stabilizzazione elettronica disponibile
        boolean ois;        // stabilizzazione ottica disponibile
        boolean afVideo;    // messa a fuoco continua per video
        boolean flash;      // torcia disponibile
        float maxZoom = 1f;
        float minZoom = 1f;        // < 1 solo con CONTROL_ZOOM_RATIO (ultra grandangolo)
        boolean zoomRatio;         // zoom tramite CONTROL_ZOOM_RATIO (Android 11+)
        float equiv;               // lunghezza focale equivalente 35 mm (0 = sconosciuta)
        final List<Float> physEquiv = new ArrayList<>();   // lenti fisiche di una camera logica
        Rect active;
        int evMin, evMax;
        float evStep;
        int maxAfRegions, maxAeRegions;
        final List<Integer> wbModes = new ArrayList<>();   // modalita' disponibili (valori Camera2)
        CameraCharacteristics ch;
        StreamConfigurationMap map;
        final List<Quality> qualities = new ArrayList<>();
    }

    private interface IntCb {
        void on(int v);
    }

    /** Una lente selezionabile: camera + zoom (relativo a quella camera) + fattore mostrato. */
    private static final class Lens {
        int camIdx;
        float zoom;
        float factor;
        String label;
        TextView view;
    }

    private final List<Lens> lenses = new ArrayList<>();
    private LinearLayout lensRow;

    // ------------------------------------------------------------------ stato

    private SharedPreferences prefs;
    private CameraManager manager;
    private final List<CamInfo> cams = new ArrayList<>();
    private int camIndex = 0;
    private int qualityIndex = 0;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private CaptureRequest.Builder currentBuilder;
    private boolean currentIsRecord = false;
    private final Semaphore camLock = new Semaphore(1);
    private volatile boolean opening = false;

    // impostazioni
    private volatile boolean stabOn = true;
    private boolean audioOn = true;
    private boolean gridOn = false;
    private int timerSec = 0;
    private volatile boolean torchOn = false;
    private volatile float zoom = 1f;
    private volatile int evValue = 0;
    private volatile int wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    private volatile MeteringRectangle focusRect = null;

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

    private boolean counting = false;
    private int countLeft = 0;

    private boolean permAsked = false;

    // ------------------------------------------------------------------ interfaccia

    private FrameLayout root;
    private AutoFitTextureView textureView;
    private GridView gridView;
    private View touchLayer;
    private View focusRing;
    private TextView centerLabel, countdownText;
    private LinearLayout topBar, controlBar, controlRow;
    private FrameLayout cellFlip, cellRec, cellSettings;
    private TextView qualityPill, stabPill, timerText;
    private ImageButton torchBtn, flipBtn, settingsBtn;
    private RecordButton recButton;
    private Button permButton;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector tapDetector;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long s = (SystemClock.elapsedRealtime() - recStartMs) / 1000;
            timerText.setText(String.format(Locale.US, "●  %02d:%02d", s / 60, s % 60));
            ui.postDelayed(this, 500);
        }
    };

    private final Runnable countTick = new Runnable() {
        @Override
        public void run() {
            if (!counting) {
                return;
            }
            if (countLeft <= 0) {
                counting = false;
                countdownText.setVisibility(View.GONE);
                recordAttempts = 0;
                startRecording();
                updateUi();
                return;
            }
            countdownText.setText(String.valueOf(countLeft));
            countdownText.setVisibility(View.VISIBLE);
            countLeft--;
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable clearFocus = new Runnable() {
        @Override
        public void run() {
            focusRect = null;
            refreshRequest();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("stablecam", MODE_PRIVATE);
        stabOn = prefs.getBoolean("stab", true);
        audioOn = prefs.getBoolean("audio", true);
        gridOn = prefs.getBoolean("grid", false);
        timerSec = prefs.getInt("timer", 0);
        manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        buildUi();
    }

    // ------------------------------------------------------------------ costruzione UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new AutoFitTextureView(this);
        textureView.setSurfaceTextureListener(surfaceListener);
        root.addView(textureView, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        gridView = new GridView(this, textureView);
        gridView.setVisibility(gridOn ? View.VISIBLE : View.GONE);
        root.addView(gridView, new FrameLayout.LayoutParams(MATCH, MATCH));
        textureView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                gridView.invalidate();
            }
        });

        // strato per i gesti: pizzica per lo zoom, tocca per mettere a fuoco
        touchLayer = new View(this);
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        setFactor(globalFactor() * d.getScaleFactor());
                        return true;
                    }
                });
        tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                focusAt(e.getX(), e.getY());
                return true;
            }
        });
        touchLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                scaleDetector.onTouchEvent(ev);
                tapDetector.onTouchEvent(ev);
                return true;
            }
        });
        root.addView(touchLayer, new FrameLayout.LayoutParams(MATCH, MATCH));

        // cerchio di messa a fuoco
        focusRing = new View(this);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(Color.TRANSPARENT);
        ringBg.setStroke(dp(2), Color.WHITE);
        focusRing.setBackground(ringBg);
        focusRing.setAlpha(0f);
        root.addView(focusRing, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP | Gravity.START));

        // etichetta temporanea al centro (zoom)
        centerLabel = new TextView(this);
        centerLabel.setTextColor(Color.WHITE);
        centerLabel.setTextSize(22);
        centerLabel.setTypeface(Typeface.DEFAULT_BOLD);
        centerLabel.setPadding(dp(16), dp(8), dp(16), dp(8));
        centerLabel.setBackground(rounded(PILL_BG, 100));
        centerLabel.setAlpha(0f);
        root.addView(centerLabel, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        // conto alla rovescia
        countdownText = new TextView(this);
        countdownText.setTextColor(Color.WHITE);
        countdownText.setTextSize(96);
        countdownText.setTypeface(Typeface.DEFAULT_BOLD);
        countdownText.setShadowLayer(12f, 0f, 0f, Color.BLACK);
        countdownText.setVisibility(View.GONE);
        root.addView(countdownText, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        buildTopBar();
        buildControlBar();

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
        applyOrientationLayout();
        updateUi();
    }

    private void buildTopBar() {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        topBar.setBackgroundColor(BAR_BG);
        topBar.setClickable(true);   // i tocchi sulla barra non devono arrivare all'anteprima

        qualityPill = pill("—");
        qualityPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        topBar.addView(qualityPill, pillParams());

        stabPill = pill("STAB");
        stabPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cams.isEmpty() || recording) {
                    return;
                }
                CamInfo ci = curCam();
                if (!ci.eis && !ci.ois) {
                    toast("Questa fotocamera non offre la stabilizzazione.");
                    return;
                }
                setStab(!stabOn);
            }
        });
        topBar.addView(stabPill, pillParams());

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        torchBtn = iconButton(R.drawable.ic_flash_off, "Torcia");
        torchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                torchOn = !torchOn;
                refreshRequest();
                updateUi();
            }
        });
        topBar.addView(torchBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        root.addView(topBar, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));
    }

    private void buildControlBar() {
        controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.VERTICAL);
        controlBar.setGravity(Gravity.CENTER_HORIZONTAL);
        controlBar.setClickable(true);   // i tocchi sulla barra non devono arrivare all'anteprima

        timerText = new TextView(this);
        timerText.setTextColor(Color.WHITE);
        timerText.setTextSize(18);
        timerText.setTypeface(Typeface.DEFAULT_BOLD);
        timerText.setGravity(Gravity.CENTER);
        timerText.setPadding(0, dp(10), 0, 0);
        timerText.setVisibility(View.INVISIBLE);
        timerText.setText("●  00:00");
        controlBar.addView(timerText, new LinearLayout.LayoutParams(MATCH, WRAP));

        buildLensRow();
        controlBar.addView(lensRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        controlRow = new LinearLayout(this);
        controlRow.setGravity(Gravity.CENTER);

        flipBtn = iconButton(R.drawable.ic_flip, "Cambia fotocamera");
        flipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchCamera();
            }
        });
        cellFlip = cell(flipBtn, dp(52));

        recButton = new RecordButton(this);
        recButton.setContentDescription("Registra");
        recButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRecordClick();
            }
        });
        cellRec = cell(recButton, dp(76));

        settingsBtn = iconButton(R.drawable.ic_settings, "Impostazioni");
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        cellSettings = cell(settingsBtn, dp(52));

        controlRow.addView(cellFlip);
        controlRow.addView(cellRec);
        controlRow.addView(cellSettings);
        controlBar.addView(controlRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        root.addView(controlBar, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
    }

    /** In verticale i comandi stanno in basso; in orizzontale in una colonna a destra. */
    private void applyOrientationLayout() {
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        float r = dp(20);

        FrameLayout.LayoutParams clp;
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(BAR_BG);
        if (land) {
            clp = new FrameLayout.LayoutParams(dp(112), MATCH, Gravity.END);
            bar.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            controlRow.setOrientation(LinearLayout.VERTICAL);
            lensRow.setOrientation(LinearLayout.VERTICAL);
            for (View c : new View[]{cellFlip, cellRec, cellSettings}) {
                c.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 0, 1f));
            }
            controlRow.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 0, 1f));
            timerText.setPadding(0, dp(12), 0, dp(4));
        } else {
            clp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
            bar.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            controlRow.setOrientation(LinearLayout.HORIZONTAL);
            lensRow.setOrientation(LinearLayout.HORIZONTAL);
            for (View c : new View[]{cellFlip, cellRec, cellSettings}) {
                c.setLayoutParams(new LinearLayout.LayoutParams(0, dp(96), 1f));
            }
            controlRow.setLayoutParams(new LinearLayout.LayoutParams(MATCH, WRAP));
            timerText.setPadding(0, dp(10), 0, 0);
        }
        controlBar.setLayoutParams(clp);
        controlBar.setBackground(bar);

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP);
        if (land) {
            tlp.rightMargin = dp(112);   // non finire sotto la colonna dei comandi
        }
        topBar.setLayoutParams(tlp);
    }

    private FrameLayout cell(View child, int size) {
        FrameLayout f = new FrameLayout(this);
        f.addView(child, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return f;
    }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(rounded(PILL_BG, 100));
        return t;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = dp(8);
        return lp;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(Color.WHITE);
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(PILL_BG);
        b.setBackground(bg);
        b.setContentDescription(description);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Aggiorna tutti gli indicatori e lo stato dei comandi. */
    private void updateUi() {
        boolean idle = !recording && !counting;
        recButton.setRecording(recording || counting);
        timerText.setVisibility(recording ? View.VISIBLE : View.INVISIBLE);

        boolean multi = cams.size() > 1;
        updateLensHighlight();
        flipBtn.setEnabled(idle && multi);
        flipBtn.setAlpha(idle && multi ? 1f : 0.4f);

        if (cams.isEmpty()) {
            qualityPill.setText("—");
            stabPill.setText("STAB");
            torchBtn.setVisibility(View.INVISIBLE);
            return;
        }
        CamInfo ci = curCam();
        qualityPill.setText(curQuality().shortLabel);

        boolean supported = ci.eis || ci.ois;
        if (!supported) {
            stabPill.setText("STAB N/D");
            stabPill.setTextColor(TEXT_DIM);
            stabPill.setBackground(rounded(PILL_BG, 100));
        } else if (stabOn) {
            stabPill.setText("STAB ON");
            stabPill.setTextColor(Color.BLACK);
            stabPill.setBackground(rounded(ACCENT, 100));
        } else {
            stabPill.setText("STAB OFF");
            stabPill.setTextColor(Color.WHITE);
            stabPill.setBackground(rounded(PILL_BG, 100));
        }

        torchBtn.setVisibility(ci.flash ? View.VISIBLE : View.INVISIBLE);
        torchBtn.setImageResource(torchOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        torchBtn.setColorFilter(torchOn ? ACCENT : Color.WHITE);
    }

    // ------------------------------------------------------------------ azioni dell'utente

    private void onRecordClick() {
        if (recording) {
            recButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            stopRecording(true);
            return;
        }
        if (starting) {
            return;
        }
        if (counting) {
            cancelCountdown();
            updateUi();
            return;
        }
        if (cameraDevice == null) {
            return;
        }
        recButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (timerSec > 0) {
            counting = true;
            countLeft = timerSec;
            ui.post(countTick);
            updateUi();
        } else {
            recordAttempts = 0;
            startRecording();
        }
    }

    private void cancelCountdown() {
        counting = false;
        ui.removeCallbacks(countTick);
        countdownText.setVisibility(View.GONE);
    }

    private void setStab(boolean on) {
        stabOn = on;
        prefs.edit().putBoolean("stab", on).apply();
        refreshRequest();
        updateUi();
    }

    private void setQuality(int idx) {
        if (idx == qualityIndex || cams.isEmpty()) {
            return;
        }
        qualityIndex = idx;
        if (!recording && cameraDevice != null) {
            startPreview();
        }
        updateUi();
    }

    private void setZoom(float z) {
        if (cams.isEmpty()) {
            return;
        }
        zoom = Math.max(curCam().minZoom, Math.min(z, curCam().maxZoom));
        flashLabel(String.format(Locale.US, "%.1f×", globalFactor()));
        refreshRequest();
        updateLensHighlight();
    }

    private void flashLabel(String s) {
        centerLabel.setText(s);
        centerLabel.animate().cancel();
        centerLabel.setAlpha(1f);
        centerLabel.animate().alpha(0f).setStartDelay(800).setDuration(300).start();
    }

    /** Tocco sull'anteprima: mette a fuoco ed espone per quel punto. */
    private void focusAt(float x, float y) {
        if (cams.isEmpty() || cameraDevice == null || textureView.getWidth() == 0) {
            return;
        }
        CamInfo ci = curCam();
        if (ci.active == null || (ci.maxAfRegions == 0 && ci.maxAeRegions == 0)) {
            return;
        }
        float tx = x - textureView.getLeft();
        float ty = y - textureView.getTop();
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (tx < 0 || ty < 0 || tx > vw || ty > vh) {
            return;
        }
        float[] s = viewToSensor(tx / vw, ty / vh, ci);
        Rect crop = zoom > 1.001f ? cropFor(ci, zoom) : ci.active;
        int half = Math.max(16, ci.active.width() / 12);
        int size = half * 2;
        int cx = crop.left + Math.round(s[0] * crop.width());
        int cy = crop.top + Math.round(s[1] * crop.height());
        int left = Math.max(ci.active.left, Math.min(cx - half, ci.active.right - size));
        int top = Math.max(ci.active.top, Math.min(cy - half, ci.active.bottom - size));
        focusRect = new MeteringRectangle(left, top, size, size,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);
        refreshRequest();
        showFocusRing(x, y);
        ui.removeCallbacks(clearFocus);
        ui.postDelayed(clearFocus, 5000);
    }

    /** Da coordinate normalizzate della vista a coordinate normalizzate del sensore. */
    private float[] viewToSensor(float u, float v, CamInfo ci) {
        int deg = getWindowManager().getDefaultDisplay().getRotation() * 90;
        int rel = (ci.sensorOrientation - deg + 360) % 360;
        switch (rel) {
            case 90:
                return new float[]{v, 1f - u};
            case 180:
                return new float[]{1f - u, 1f - v};
            case 270:
                return new float[]{1f - v, u};
            default:
                return new float[]{u, v};
        }
    }

    private void showFocusRing(float x, float y) {
        float s = dp(64);
        focusRing.animate().cancel();
        focusRing.setX(x - s / 2f);
        focusRing.setY(y - s / 2f);
        focusRing.setAlpha(1f);
        focusRing.setScaleX(1.5f);
        focusRing.setScaleY(1.5f);
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).start();
            }
        }).start();
    }

    // ------------------------------------------------------------------ pannello impostazioni

    private void showSettings() {
        if (cams.isEmpty()) {
            return;
        }
        final CamInfo ci = curCam();
        final boolean idle = !recording && !counting;

        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(SHEET_BG);
        float r = dp(24);
        sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setPadding(dp(20), dp(16), dp(20), dp(12));

        TextView title = sheetText("Impostazioni", 20, Color.WHITE, true);
        sheet.addView(title);

        MaxHeightScrollView sv = new MaxHeightScrollView(this,
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.68f));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, new ViewGroup.LayoutParams(MATCH, WRAP));
        sheet.addView(sv, new LinearLayout.LayoutParams(MATCH, WRAP));

        // --- video
        body.addView(sectionTitle("Video"));
        String[] qn = new String[ci.qualities.size()];
        for (int i = 0; i < qn.length; i++) {
            qn[i] = ci.qualities.get(i).label + (i == 0 ? "  (massima)" : "");
        }
        body.addView(sheetSpinner("Qualita'", qn, qualityIndex, idle, new IntCb() {
            @Override
            public void on(int v) {
                setQuality(v);
            }
        }));

        boolean stabSupported = ci.eis || ci.ois;
        body.addView(sheetSwitch("Stabilizzazione", stabOn && stabSupported,
                idle && stabSupported, new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        setStab(checked);
                    }
                }));
        body.addView(caption(stabSupported
                ? "Elettronica " + (ci.eis ? "✓" : "✗") + "   Ottica "
                + (ci.ois ? "✓" : "✗")
                + "   · puo' ritagliare un po' l'inquadratura"
                : "Questa fotocamera non offre la stabilizzazione."));

        body.addView(sheetSwitch("Registra audio (microfono)", audioOn, idle,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        audioOn = checked;
                        prefs.edit().putBoolean("audio", checked).apply();
                    }
                }));

        // --- immagine
        body.addView(sectionTitle("Immagine"));
        if (ci.maxZoom > 1.05f) {
            final float zmin = ci.minZoom;
            final int zmax = Math.round((ci.maxZoom - zmin) * 10f);
            body.addView(sheetSeek("Zoom", zmax, Math.round((zoom - zmin) * 10f),
                    new SeekFmt() {
                        @Override
                        public String format(int p) {
                            return String.format(Locale.US, "%.1f×", zmin + p / 10f);
                        }
                    }, new IntCb() {
                        @Override
                        public void on(int v) {
                            setZoom(zmin + v / 10f);
                        }
                    }));
            body.addView(caption("Puoi anche pizzicare lo schermo."));
        }
        if (ci.evMax > ci.evMin && ci.evStep > 0f) {
            final float step = ci.evStep;
            final int min = ci.evMin;
            body.addView(sheetSeek("Esposizione", ci.evMax - ci.evMin, evValue - min,
                    new SeekFmt() {
                        @Override
                        public String format(int p) {
                            return String.format(Locale.US, "%+.1f EV", (p + min) * step);
                        }
                    }, new IntCb() {
                        @Override
                        public void on(int v) {
                            evValue = v + min;
                            refreshRequest();
                        }
                    }));
        }
        if (ci.wbModes.size() > 1) {
            final String[] wn = new String[ci.wbModes.size()];
            int sel = 0;
            for (int i = 0; i < wn.length; i++) {
                int mode = ci.wbModes.get(i);
                wn[i] = nameOfWb(mode);
                if (mode == wbMode) {
                    sel = i;
                }
            }
            body.addView(sheetSpinner("Bilanciamento del bianco", wn, sel, true, new IntCb() {
                @Override
                public void on(int v) {
                    wbMode = ci.wbModes.get(v);
                    refreshRequest();
                }
            }));
        }
        body.addView(sheetSwitch("Griglia (regola dei terzi)", gridOn, true,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        gridOn = checked;
                        prefs.edit().putBoolean("grid", checked).apply();
                        gridView.setVisibility(checked ? View.VISIBLE : View.GONE);
                    }
                }));

        // --- registrazione
        body.addView(sectionTitle("Registrazione"));
        int tsel = 0;
        for (int i = 0; i < TIMER_SECS.length; i++) {
            if (TIMER_SECS[i] == timerSec) {
                tsel = i;
            }
        }
        body.addView(sheetSpinner("Timer di avvio", TIMER_NAMES, tsel, idle, new IntCb() {
            @Override
            public void on(int v) {
                timerSec = TIMER_SECS[v];
                prefs.edit().putInt("timer", timerSec).apply();
            }
        }));

        body.addView(sectionTitle("Informazioni"));
        body.addView(caption("Fotocamera: " + ci.label
                + "\nSalvataggio: Galleria › Movies/MamoTools (MamoRecord_data_ora.mp4)"
                + "\nTocca l'anteprima per mettere a fuoco."));

        Button close = new Button(this);
        close.setText("Chiudi");
        close.setAllCaps(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH, WRAP);
        blp.topMargin = dp(8);
        sheet.addView(close, blp);

        dlg.setContentView(sheet);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)), WRAP);
            w.setDimAmount(0.5f);
        }
        dlg.show();
    }

    private static String nameOfWb(int mode) {
        for (int i = 0; i < WB_MODES.length; i++) {
            if (WB_MODES[i] == mode) {
                return WB_NAMES[i];
            }
        }
        return "Modo " + mode;
    }

    private interface SeekFmt {
        String format(int progress);
    }

    private TextView sheetText(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = sheetText(s.toUpperCase(Locale.ROOT), 12, ACCENT, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private TextView caption(String s) {
        TextView t = sheetText(s, 12, TEXT_DIM, false);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    private View sheetSwitch(String label, boolean checked, boolean enabled,
                             CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(15);
        s.setTextColor(Color.WHITE);
        s.setChecked(checked);
        s.setEnabled(enabled);
        s.setPadding(0, dp(10), 0, dp(6));
        s.setOnCheckedChangeListener(l);
        return s;
    }

    private View sheetSpinner(String label, String[] items, int selected, boolean enabled,
                              final IntCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(4));
        box.addView(sheetText(label, 15, Color.WHITE, false));
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, items));
        sp.setSelection(selected);
        sp.setEnabled(enabled);
        final int[] last = {selected};
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == last[0]) {
                    return;
                }
                last[0] = pos;
                cb.on(pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        box.addView(sp, new LinearLayout.LayoutParams(MATCH, WRAP));
        return box;
    }

    private View sheetSeek(String label, int max, int progress, final SeekFmt fmt,
                           final IntCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(4));
        final TextView t = sheetText(label + "   " + fmt.format(progress), 15, Color.WHITE, false);
        box.addView(t);
        final String base = label;
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(progress, max)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                t.setText(base + "   " + fmt.format(p));
                if (fromUser) {
                    cb.on(p);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb, new LinearLayout.LayoutParams(MATCH, WRAP));
        return box;
    }

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onResume() {
        super.onResume();
        startBg();
        if (!hasPerms()) {
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
        cancelCountdown();
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
        applyOrientationLayout();
        applyAspect();
        configureTransform(textureView.getWidth(), textureView.getHeight());
        gridView.invalidate();
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
                ci.openId = id;
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

                Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                ci.flash = fl != null && fl;
                Float mz = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                ci.maxZoom = mz == null ? 1f : Math.max(1f, Math.min(mz, 10f));
                if (Build.VERSION.SDK_INT >= 30) {
                    Range<Float> zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    if (zr != null && zr.getUpper() > 1.05f) {
                        ci.zoomRatio = true;
                        ci.minZoom = Math.min(1f, zr.getLower());
                        ci.maxZoom = Math.max(1f, Math.min(zr.getUpper(), 10f));
                    }
                }
                ci.equiv = equivFocal(ch);
                if (Build.VERSION.SDK_INT >= 28 && ci.zoomRatio) {
                    try {
                        for (String pid : ch.getPhysicalCameraIds()) {
                            float pe = equivFocal(manager.getCameraCharacteristics(pid));
                            if (pe > 0) {
                                ci.physEquiv.add(pe);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                ci.active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Range<Integer> evr = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (evr != null) {
                    ci.evMin = evr.getLower();
                    ci.evMax = evr.getUpper();
                }
                Rational step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                ci.evStep = step == null ? 0f : step.floatValue();
                Integer afr = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                ci.maxAfRegions = afr == null ? 0 : afr;
                Integer aer = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
                ci.maxAeRegions = aer == null ? 0 : aer;
                int[] awb = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
                for (int mode : WB_MODES) {
                    if (contains(awb, mode)) {
                        ci.wbModes.add(mode);
                    }
                }

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
        addHiddenPhysical();
        Collections.sort(cams, (a, b) -> Boolean.compare(a.front, b.front));
        camIndex = 0;
        qualityIndex = 0;
        resetCameraState();
        buildLenses();
        buildLensRow();
        updateLensHighlight();
    }

    /** Aggiunge le lenti fisiche che il telefono non elenca da sole (nascoste dentro la logica). */
    private void addHiddenPhysical() {
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        List<CamInfo> extra = new ArrayList<>();
        for (CamInfo c : cams) {
            if (c.front || c.physId != null || c.equiv <= 0) {
                continue;
            }
            try {
                for (String pid : c.ch.getPhysicalCameraIds()) {
                    boolean known = false;
                    for (CamInfo o : cams) {
                        if (o.id.equals(pid)) {
                            known = true;
                        }
                    }
                    for (CamInfo o : extra) {
                        if (o.id.equals(pid)) {
                            known = true;
                        }
                    }
                    if (known) {
                        continue;
                    }
                    CameraCharacteristics pc = manager.getCameraCharacteristics(pid);
                    float pe = equivFocal(pc);
                    if (pe <= 0 || Math.abs(pe / c.equiv - 1f) < 0.1f) {
                        continue;
                    }
                    CamInfo p = new CamInfo();
                    p.id = pid;
                    p.openId = c.id;
                    p.physId = pid;
                    p.ch = c.ch;
                    p.map = c.map;
                    p.front = false;
                    p.sensorOrientation = c.sensorOrientation;
                    p.eis = c.eis;
                    p.ois = c.ois;
                    p.afVideo = c.afVideo;
                    p.flash = c.flash;
                    Float mz = pc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                    p.maxZoom = mz == null ? 4f : Math.max(1f, Math.min(mz, 6f));
                    p.active = c.active;
                    p.evMin = c.evMin;
                    p.evMax = c.evMax;
                    p.evStep = c.evStep;
                    p.maxAfRegions = 0;     // le zone AF/AE della logica non valgono per la fisica
                    p.maxAeRegions = 0;
                    p.wbModes.addAll(c.wbModes);
                    p.qualities.addAll(c.qualities);
                    p.equiv = pe;
                    p.label = "Posteriore · lente " + pid;
                    extra.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        cams.addAll(extra);
    }

    /** Lunghezza focale equivalente 35 mm, o 0 se il telefono non fornisce i dati. */
    private static float equivFocal(CameraCharacteristics ch) {
        float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        android.util.SizeF ps = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (fl == null || fl.length == 0 || ps == null || ps.getWidth() <= 0 || ps.getHeight() <= 0) {
            return 0f;
        }
        double diag = Math.hypot(ps.getWidth(), ps.getHeight());
        return (float) (fl[0] * 43.27 / diag);
    }

    /** Costruisce l'elenco delle lenti posteriori con il loro fattore (x0.5, x1, x3...). */
    private void buildLenses() {
        lenses.clear();
        CamInfo ref = null;
        for (CamInfo c : cams) {
            if (!c.front && c.equiv > 0
                    && (ref == null || Math.abs(c.equiv - 26f) < Math.abs(ref.equiv - 26f))) {
                ref = c;
            }
        }
        if (ref == null) {
            return;
        }
        // la camera di riferimento viene prima, cosi' vince nei duplicati
        List<Integer> order = new ArrayList<>();
        order.add(cams.indexOf(ref));
        for (int i = 0; i < cams.size(); i++) {
            if (!cams.get(i).front && i != order.get(0)) {
                order.add(i);
            }
        }
        for (int i : order) {
            CamInfo c = cams.get(i);
            if (c.equiv <= 0) {
                continue;
            }
            List<Float> rel = new ArrayList<>();   // zoom relativo a questa camera
            rel.add(1f);
            for (float z : rel) {
                if (z < c.minZoom - 0.02f || z > c.maxZoom + 0.02f) {
                    continue;
                }
                float factor = z * c.equiv / ref.equiv;
                boolean dup = false;
                for (Lens l : lenses) {
                    if (Math.abs(l.factor / factor - 1f) < 0.1f) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    continue;
                }
                Lens l = new Lens();
                l.camIdx = i;
                l.zoom = Math.max(c.minZoom, Math.min(z, c.maxZoom));
                l.factor = factor;
                l.label = lensLabel(factor);
                lenses.add(l);
            }
        }
        // seconda passata: lenti raggiungibili solo con lo zoom della camera logica
        for (int i : order) {
            CamInfo c = cams.get(i);
            if (c.equiv <= 0 || !c.zoomRatio) {
                continue;
            }
            for (float pe : c.physEquiv) {
                float z = pe / c.equiv;
                if (z < c.minZoom - 0.02f || z > c.maxZoom + 0.02f) {
                    continue;
                }
                float factor = z * c.equiv / ref.equiv;
                boolean dup = false;
                for (Lens l : lenses) {
                    if (Math.abs(l.factor / factor - 1f) < 0.1f) {
                        dup = true;
                    }
                }
                if (dup) {
                    continue;
                }
                Lens l = new Lens();
                l.camIdx = i;
                l.zoom = Math.max(c.minZoom, Math.min(z, c.maxZoom));
                l.factor = factor;
                l.label = lensLabel(factor);
                lenses.add(l);
            }
        }
        refEquiv = ref.equiv;
        Collections.sort(lenses, (a, b) -> Float.compare(a.factor, b.factor));
    }

    private float refEquiv = 0f;

    /** Zoom mostrato all'utente, rispetto alla lente principale (x1). */
    private float globalFactor() {
        CamInfo c = curCam();
        return (refEquiv > 0 && c.equiv > 0 && !c.front) ? zoom * c.equiv / refEquiv : zoom;
    }

    /**
     * Zoom a pizzico: sceglie da solo la lente giusta per il fattore richiesto e, se serve,
     * cambia camera (non durante la registrazione).
     */
    private void setFactor(float f) {
        if (cams.isEmpty() || opening || cameraDevice == null) {
            return;
        }
        CamInfo cur = curCam();
        if (cur.front || lenses.size() < 2 || refEquiv <= 0 || cur.equiv <= 0) {
            setZoom(f);
            return;
        }
        float lo = lenses.get(0).factor;
        f = Math.max(lo, Math.min(f, lenses.get(lenses.size() - 1).factor * curMaxOn(lenses.size() - 1)));
        Lens best = lenses.get(0);
        for (Lens l : lenses) {
            if (l.factor <= f * 1.03f) {
                best = l;
            }
        }
        if (best.camIdx != camIndex && !recording && !counting && !starting) {
            CamInfo t = cams.get(best.camIdx);
            float zt = Math.max(t.minZoom, Math.min(f * refEquiv / t.equiv, t.maxZoom));
            goToCamera(best.camIdx, zt);
            flashLabel(String.format(Locale.US, "%.1f×", f));
            return;
        }
        setZoom(f * refEquiv / cur.equiv);
    }

    private float curMaxOn(int lensIdx) {
        CamInfo c = cams.get(lenses.get(lensIdx).camIdx);
        return Math.max(1f, c.maxZoom / Math.max(0.1f, lenses.get(lensIdx).zoom));
    }

    private static String lensLabel(float f) {
        float r = Math.round(f);
        if (f >= 0.95f && Math.abs(f - r) < 0.2f) {
            return "x" + (int) r;
        }
        return String.format(Locale.US, "x%.1f", f);
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
            camNum = ci.front ? 1 : 0;
        }
        // le camere ausiliarie (ultra grandangolo, tele) spesso non hanno profili propri:
        // si usano quelli della camera principale, filtrati sulle risoluzioni supportate
        final int fallbackNum = ci.front ? 1 : 0;
        Size[] supported = ci.map.getOutputSizes(MediaRecorder.class);
        if (supported == null) {
            return;
        }
        List<Size> supportedList = Arrays.asList(supported);

        for (int i = 0; i < order.length; i++) {
            CamcorderProfile p = null;
            for (int num : new int[]{camNum, fallbackNum}) {
                try {
                    if (CamcorderProfile.hasProfile(num, order[i])) {
                        p = CamcorderProfile.get(num, order[i]);
                        if (p != null) {
                            break;
                        }
                    }
                } catch (RuntimeException ignored) {
                }
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
                    describe(names[i], baseFps, size), names[i] + " · " + baseFps + " fps"));
            if (baseFps < 60 && supports60(ci, size)) {
                int br = (int) Math.min(150_000_000L, (long) p.videoBitRate * 3 / 2);
                ci.qualities.add(new Quality(p, size, 60, br, describe(names[i], 60, size),
                        names[i] + " · 60 fps"));
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

    private CamInfo curCam() {
        return cams.get(camIndex);
    }

    private Quality curQuality() {
        List<Quality> qs = curCam().qualities;
        return qs.get(Math.max(0, Math.min(qualityIndex, qs.size() - 1)));
    }

    /** Valori per-camera da azzerare quando si cambia fotocamera o si riapre. */
    private void resetCameraState() {
        torchOn = false;
        zoom = 1f;
        evValue = 0;
        wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        focusRect = null;
    }

    private void switchCamera() {
        if (cams.size() < 2 || recording || counting) {
            return;
        }
        // alterna posteriore <-> frontale; la posteriore riparte dalla lente x1
        boolean toFront = !curCam().front;
        int target = -1;
        float zoomTo = 1f;
        if (toFront) {
            for (int i = 0; i < cams.size(); i++) {
                if (cams.get(i).front) {
                    target = i;
                    break;
                }
            }
        } else {
            for (Lens l : lenses) {
                if (Math.abs(l.factor - 1f) < 0.1f) {
                    target = l.camIdx;
                    zoomTo = l.zoom;
                    break;
                }
            }
            for (int i = 0; target < 0 && i < cams.size(); i++) {
                if (!cams.get(i).front) {
                    target = i;
                }
            }
        }
        if (target < 0) {
            return;
        }
        goToCamera(target, zoomTo);
    }

    /** Passa a un'altra camera mantenendo, se possibile, la stessa qualita'. */
    private void goToCamera(int target, float zoomTo) {
        String keep = curQuality().shortLabel;
        camIndex = target;
        qualityIndex = 0;
        List<Quality> qs = curCam().qualities;
        for (int i = 0; i < qs.size(); i++) {
            if (qs.get(i).shortLabel.equals(keep)) {
                qualityIndex = i;
                break;
            }
        }
        resetCameraState();
        zoom = Math.max(curCam().minZoom, Math.min(zoomTo, curCam().maxZoom));
        closeCamera();
        openCamera();
        updateUi();
    }

    private void selectLens(Lens l) {
        if (cams.isEmpty()) {
            return;
        }
        if (l.camIdx == camIndex) {
            setZoom(l.zoom);
            return;
        }
        if (recording || counting) {
            toast("Questa lente e' una camera separata: ferma la registrazione per cambiarla.");
            return;
        }
        goToCamera(l.camIdx, l.zoom);
    }

    /** Evidenzia il pulsante della lente in uso; nasconde la riga con la camera frontale. */
    private void updateLensHighlight() {
        if (lensRow == null) {
            return;
        }
        boolean show = !cams.isEmpty() && !curCam().front && lenses.size() > 1;
        lensRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        Lens best = null;
        for (Lens l : lenses) {
            if (l.camIdx == camIndex && l.zoom <= zoom + 0.05f
                    && (best == null || l.zoom > best.zoom)) {
                best = l;
            }
        }
        if (best == null) {
            for (Lens l : lenses) {
                if (l.camIdx == camIndex) {
                    best = l;
                    break;
                }
            }
        }
        for (Lens l : lenses) {
            boolean on = l == best;
            l.view.setTextColor(on ? Color.BLACK : Color.WHITE);
            l.view.setBackground(rounded(on ? ACCENT : PILL_BG, 100));
        }
    }

    private void buildLensRow() {
        if (lensRow == null) {
            lensRow = new LinearLayout(this);
            lensRow.setGravity(Gravity.CENTER);
        }
        lensRow.removeAllViews();
        for (final Lens l : lenses) {
            TextView t = pill(l.label);
            t.setGravity(Gravity.CENTER);
            t.setMinWidth(dp(48));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectLens(l);
                }
            });
            l.view = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            lensRow.addView(t, lp);
        }
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
            toast("Nessuna fotocamera utilizzabile per il video.");
            updateUi();
            return;
        }
        startBg();
        updateUi();
        boolean acquired = false;
        try {
            acquired = camLock.tryAcquire(2500, TimeUnit.MILLISECONDS);
            if (!acquired) {
                toast("Fotocamera occupata, riprova.");
                return;
            }
            opening = true;
            manager.openCamera(curCam().openId, stateCallback, bg);
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
        torchOn = false;   // chiudendo la camera la torcia si spegne
        currentBuilder = null;
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

    private Rect cropFor(CamInfo ci, float z) {
        Rect a = ci.active;
        int w = Math.round(a.width() / z);
        int h = Math.round(a.height() / z);
        int l = a.left + (a.width() - w) / 2;
        int t = a.top + (a.height() - h) / 2;
        return new Rect(l, t, l + w, t + h);
    }

    /** Applica a una richiesta tutte le impostazioni correnti. */
    private void applySettings(CaptureRequest.Builder b, CamInfo ci, Quality q, boolean record) {
        if (ci.afVideo) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        boolean stab = stabOn;
        if (ci.eis) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stab
                    ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
        if (ci.ois) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, stab
                    ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if (record) {
            Range<Integer> r = pickFpsRange(ci, q.fps);
            if (r != null) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r);
            }
        }
        // zoom digitale
        if (ci.zoomRatio && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(ci.minZoom, Math.min(zoom, ci.maxZoom)));
        } else if (ci.active != null) {
            float z = zoom;
            b.set(CaptureRequest.SCALER_CROP_REGION, z > 1.001f ? cropFor(ci, z) : ci.active);
        }
        // esposizione
        if (ci.evMax > ci.evMin) {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    Math.max(ci.evMin, Math.min(ci.evMax, evValue)));
        }
        // bilanciamento del bianco
        if (ci.wbModes.contains(wbMode)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, wbMode);
        }
        // torcia
        if (ci.flash) {
            b.set(CaptureRequest.FLASH_MODE, torchOn
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }
        // zona di messa a fuoco scelta con un tocco
        MeteringRectangle fr = focusRect;
        if (fr != null) {
            if (ci.maxAfRegions > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{fr});
            }
            if (ci.maxAeRegions > 0) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{fr});
            }
        } else if (ci.active != null) {
            // nessuna zona scelta: se prima ce n'era una, la sostituisco con "tutto il campo"
            // a peso zero, che per Camera2 equivale a non averne (senza usare valori nulli)
            MeteringRectangle[] neutral = {new MeteringRectangle(ci.active.left, ci.active.top,
                    ci.active.width(), ci.active.height(), 0)};
            if (ci.maxAfRegions > 0 && b.get(CaptureRequest.CONTROL_AF_REGIONS) != null) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, neutral);
            }
            if (ci.maxAeRegions > 0 && b.get(CaptureRequest.CONTROL_AE_REGIONS) != null) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, neutral);
            }
        }
    }

    /** Riapplica le impostazioni alla richiesta in corso, senza riaprire la sessione. */
    private void refreshRequest() {
        final Handler h = bg;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                CameraCaptureSession s = session;
                CaptureRequest.Builder b = currentBuilder;
                if (s == null || b == null || cameraDevice == null || cams.isEmpty()) {
                    return;
                }
                try {
                    applySettings(b, curCam(), curQuality(), currentIsRecord);
                    s.setRepeatingRequest(b.build(), null, bg);
                } catch (Exception ignored) {
                    // sessione gia' chiusa: la prossima apertura usera' i valori aggiornati
                }
            }
        });
    }

    /** Crea la sessione; per le lenti fisiche nascoste indica la lente di ogni uscita. */
    private void createSession(CamInfo ci, List<Surface> surfaces,
                               CameraCaptureSession.StateCallback cb, Handler h)
            throws CameraAccessException {
        if (ci.physId != null && Build.VERSION.SDK_INT >= 28) {
            List<OutputConfiguration> l = new ArrayList<>();
            for (Surface sf : surfaces) {
                OutputConfiguration oc = new OutputConfiguration(sf);
                oc.setPhysicalCameraId(ci.physId);
                l.add(oc);
            }
            cameraDevice.createCaptureSessionByOutputConfigurations(l, cb, h);
        } else {
            cameraDevice.createCaptureSession(surfaces, cb, h);
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
            currentBuilder = b;
            currentIsRecord = false;

            createSession(ci, Collections.singletonList(ps),
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
        if (audioOn) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        if (audioOn) {
            recorder.setProfile(q.profile);   // formato, codec, risoluzione, bitrate, audio
        } else {
            // senza audio il profilo non si puo' usare intero: imposto i valori video a mano
            recorder.setOutputFormat(q.profile.fileFormat);
            recorder.setVideoEncoder(q.profile.videoCodec);
            recorder.setVideoSize(q.size.getWidth(), q.size.getHeight());
            recorder.setVideoFrameRate(q.fps);
            recorder.setVideoEncodingBitRate(q.bitrate);
        }
        recorder.setOutputFile(outPfd.getFileDescriptor());
        if (audioOn && q.fps != q.profile.videoFrameRate) {
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
            currentBuilder = b;
            currentIsRecord = true;

            createSession(ci, Arrays.asList(ps, rs),
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
                                    timerText.setText("●  00:00");
                                    ui.post(tick);
                                    updateUi();
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
            toast("Non riesco a registrare a " + old.label + ". Provo " + curQuality().label);
            updateUi();
            startRecording();
        } else {
            toast("Registrazione non riuscita" + (why != null ? ": " + why : ""));
            updateUi();
            startPreview();
        }
    }

    private void stopRecording(boolean restartPreview) {
        if (!recording) {
            return;
        }
        recording = false;
        ui.removeCallbacks(tick);
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
        updateUi();
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

    // ------------------------------------------------------------------ viste personalizzate

    /** Pulsante di registrazione: anello bianco con cerchio rosso (quadrato mentre registra). */
    private static final class RecordButton extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;

        RecordButton(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(REC_RED);
        }

        void setRecording(boolean r) {
            if (r != on) {
                on = r;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float stroke = w * 0.06f;
            ring.setStrokeWidth(stroke);
            c.drawCircle(cx, cy, w / 2f - stroke / 2f, ring);
            if (on) {
                float s = w * 0.27f;
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, w * 0.07f, w * 0.07f, fill);
            } else {
                c.drawCircle(cx, cy, w / 2f - stroke * 2.2f, fill);
            }
        }
    }

    /** Griglia dei terzi, disegnata solo sopra l'area dell'anteprima. */
    private static final class GridView extends View {
        private final View target;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridView(Context c, View target) {
            super(c);
            this.target = target;
            p.setColor(0x88FFFFFF);
            p.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (target.getWidth() == 0) {
                return;
            }
            float l = target.getLeft();
            float t = target.getTop();
            float r = target.getRight();
            float b = target.getBottom();
            for (int i = 1; i <= 2; i++) {
                float x = l + (r - l) * i / 3f;
                float y = t + (b - t) * i / 3f;
                c.drawLine(x, t, x, b, p);
                c.drawLine(l, y, r, y, p);
            }
        }
    }

    /** ScrollView con altezza massima (per il pannello delle impostazioni). */
    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context c, int maxHeight) {
            super(c);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int h = MeasureSpec.getSize(heightSpec);
            int cap = h > 0 ? Math.min(h, maxHeight) : maxHeight;
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
}
