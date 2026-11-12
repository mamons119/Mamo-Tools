package it.mamotools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

/**
 * Player per l'anteprima basato su TextureView (al posto di VideoView), cosi' gli effetti di colore
 * e lo zoom si possono applicare in tempo reale. Espone lo stesso sottoinsieme di metodi di VideoView.
 */
final class PreviewVideo extends FrameLayout {
    private final TextureView tv;
    private final FrameLayout inner;   // mostra solo la zona ritagliata; zoom e specchio agiscono qui
    private float[] crop;              // {x, y, larghezza, altezza} del ritaglio (frazioni dello schermo intero)
    private MediaPlayer mp;
    private Surface surface;
    private boolean prepared = false;
    private int dispW = 0, dispH = 0;
    private int extraRot = 0;
    private int pendingSeek = -1;
    private boolean pendingStart = false;
    private MediaPlayer.OnPreparedListener onPrepared;
    private MediaPlayer.OnCompletionListener onCompletion;
    private final Paint layerPaint = new Paint();
    private final RectF rect = new RectF();
    private float[] lastCm;

    PreviewVideo(Context c) {
        super(c);
        tv = new TextureView(c);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                if (mp != null) {
                    try {
                        mp.setSurface(surface);
                        if (prepared && !mp.isPlaying()) {
                            mp.seekTo(mp.getCurrentPosition(), MediaPlayer.SEEK_CLOSEST);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                try {
                    if (mp != null) {
                        mp.setSurface(null);
                    }
                } catch (Exception ignored) {
                }
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        inner = new FrameLayout(c) {
            // misura e posizione del TextureView le decide PreviewVideo
            @Override
            protected void onMeasure(int ws, int hs) {
                setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs));
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
            }
        };
        inner.setClipChildren(true);
        inner.addView(tv, new LayoutParams(10, 10));
        addView(inner, new LayoutParams(10, 10));
        setClipChildren(true);
    }

    void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
        onPrepared = l;
    }

    void setOnCompletionListener(MediaPlayer.OnCompletionListener l) {
        onCompletion = l;
    }

    /** Zona ritagliata mostrata (null = tutto). setDisplaySize deve ricevere le dimensioni gia' ritagliate. */
    void setCrop(float[] c) {
        crop = c == null || c.length != 4 ? null : c.clone();
        requestLayout();
    }

    /** Dimensioni del video come appare (gia' ruotato e ritagliato), per il rapporto d'aspetto. */
    void setDisplaySize(int w, int h) {
        dispW = w;
        dispH = h;
        requestLayout();
    }

    /** Rotazione aggiuntiva (0/90/180/270): dispW/dispH vanno passati gia' ruotati. */
    void setExtraRotation(int deg) {
        extraRot = deg;
        tv.setRotation(deg);
        requestLayout();
    }

    void setVideoURI(Uri uri) {
        release();
        prepared = false;
        pendingSeek = -1;
        pendingStart = false;
        try {
            mp = new MediaPlayer();
            mp.setOnPreparedListener(m -> {
                prepared = true;
                if (dispW <= 0 || dispH <= 0) {
                    boolean sw = extraRot == 90 || extraRot == 270;
                    dispW = sw ? m.getVideoHeight() : m.getVideoWidth();
                    dispH = sw ? m.getVideoWidth() : m.getVideoHeight();
                }
                requestLayout();
                if (onPrepared != null) {
                    onPrepared.onPrepared(m);
                }
                if (pendingSeek >= 0) {
                    try {
                        m.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST);
                    } catch (Exception ignored) {
                    }
                    pendingSeek = -1;
                }
                if (pendingStart) {
                    pendingStart = false;
                    m.start();
                }
            });
            mp.setOnCompletionListener(m -> {
                if (onCompletion != null) {
                    onCompletion.onCompletion(m);
                }
            });
            mp.setOnErrorListener((m, what, extra) -> true);
            mp.setDataSource(getContext(), uri);
            if (surface != null) {
                mp.setSurface(surface);
            }
            mp.prepareAsync();
        } catch (Exception e) {
            release();
        }
    }

    void release() {
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        prepared = false;
    }

    boolean isPlaying() {
        try {
            return prepared && mp != null && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    void start() {
        if (prepared && mp != null) {
            mp.start();
        } else {
            pendingStart = true;
        }
    }

    void pause() {
        pendingStart = false;
        if (prepared && mp != null) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
        }
    }

    int getCurrentPosition() {
        try {
            return prepared && mp != null ? mp.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    int getDuration() {
        try {
            return prepared && mp != null ? mp.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    void seekTo(int ms) {
        if (prepared && mp != null) {
            try {
                mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            } catch (Exception ignored) {
            }
        } else {
            pendingSeek = ms;
        }
    }

    /** Rettangolo (nelle coordinate di questa vista) occupato dal video. */
    RectF videoRect() {
        return rect;
    }

    /** Applica filtro di colore e zoom (null/1 = nessuno). */
    void setFx(ColorMatrix cm, float zoom) {
        setFx(cm, zoom, 0.5f, 0.5f, false, false);
    }

    private boolean mirH = false, mirV = false;

    /**
     * Filtro di colore, zoom attorno a un punto (frazioni della larghezza e dell'altezza del riquadro mostrato)
     * e specchio orizzontale/verticale.
     */
    void setFx(ColorMatrix cm, float zoom, float px, float py, boolean mh, boolean mv) {
        float[] arr = cm == null ? null : cm.getArray().clone();
        boolean same = (arr == null && lastCm == null) || (arr != null && lastCm != null
                && java.util.Arrays.equals(arr, lastCm));
        if (!same) {
            lastCm = arr;
            layerPaint.setColorFilter(cm == null ? null : new ColorMatrixColorFilter(cm));
            tv.setLayerType(LAYER_TYPE_HARDWARE, layerPaint);
        }
        if (Float.isNaN(zoom) || zoom <= 0f) {
            zoom = 1f;
        }
        mirH = mh;
        mirV = mv;
        // scala dal centro del riquadro (zoom e specchio) e sposta in modo che il punto scelto resti fermo;
        // lo specchio viene dopo lo zoom, come nell'esportazione
        float sx = mh ? -zoom : zoom, sy = mv ? -zoom : zoom;
        inner.setScaleX(sx);
        inner.setScaleY(sy);
        inner.setTranslationX((mh ? -1f : 1f) * (1f - zoom) * (px - 0.5f) * rect.width());
        inner.setTranslationY((mv ? -1f : 1f) * (1f - zoom) * (py - 0.5f) * rect.height());
    }

    /** Fotogramma corrente come appare nel riquadro (ritagliato e specchiato, senza altri filtri), oppure null. */
    Bitmap grab(int w, int h) {
        return grab(w, h, true);
    }

    /** Fotogramma intero, senza ritaglio e senza specchio (per la finestra "Ruota e ritaglia"). */
    Bitmap grabFull(int w, int h) {
        return grab(w, h, false);
    }

    private Bitmap grab(int w, int h, boolean cropped) {
        if (!tv.isAvailable() || w < 2 || h < 2) {
            return null;
        }
        try {
            float[] c = cropped ? crop : null;
            float cw = c == null ? 1f : c[2], ch = c == null ? 1f : c[3];
            // il fotogramma intero ha dimensioni 1/cw e 1/ch volte piu' grandi del ritaglio richiesto
            int fw = Math.min(2400, Math.max(2, Math.round(w / cw))), fh = Math.min(2400, Math.max(2, Math.round(h / ch)));
            boolean sw = extraRot == 90 || extraRot == 270;
            Bitmap b = sw ? tv.getBitmap(fh, fw) : tv.getBitmap(fw, fh);
            if (b == null) {
                return null;
            }
            if (extraRot != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(extraRot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                b.recycle();
                b = r;
            }
            if (c != null) {
                int x0 = Math.max(0, Math.round(c[0] * b.getWidth())), y0 = Math.max(0, Math.round(c[1] * b.getHeight()));
                int cwp = Math.max(1, Math.min(b.getWidth() - x0, Math.round(c[2] * b.getWidth())));
                int chp = Math.max(1, Math.min(b.getHeight() - y0, Math.round(c[3] * b.getHeight())));
                Bitmap r = Bitmap.createBitmap(b, x0, y0, cwp, chp);
                if (r != b) {
                    b.recycle();
                }
                b = r;
            }
            if (cropped && (mirH || mirV)) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postScale(mirH ? -1 : 1, mirV ? -1 : 1);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                b.recycle();
                b = r;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        float l = 0, t = 0, rw = w, rh = h;
        if (dispW > 0 && dispH > 0) {
            float s = Math.min(w / (float) dispW, h / (float) dispH);
            rw = dispW * s;
            rh = dispH * s;
            l = (w - rw) / 2f;
            t = (h - rh) / 2f;
        }
        rect.set(l, t, l + rw, t + rh);
        inner.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rw)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rh)), MeasureSpec.EXACTLY));
        // dimensioni dello schermo intero nel riquadro (il riquadro mostra solo la parte ritagliata)
        float cw = crop == null ? 1f : crop[2], ch = crop == null ? 1f : crop[3];
        float fw = rw / cw, fh = rh / ch;
        boolean sw = extraRot == 90 || extraRot == 270;
        float tw = sw ? fh : fw, th = sw ? fw : fh;
        tv.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(tw)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(th)), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        inner.layout(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
        boolean sw = extraRot == 90 || extraRot == 270;
        float rw = rect.width(), rh = rect.height();
        float cw = crop == null ? 1f : crop[2], ch = crop == null ? 1f : crop[3];
        float fw = rw / cw, fh = rh / ch;
        // il centro della zona ritagliata va al centro del riquadro
        float cx = rw / 2f + (crop == null ? 0f : (0.5f - (crop[0] + cw / 2f)) * fw);
        float cy = rh / 2f + (crop == null ? 0f : (0.5f - (crop[1] + ch / 2f)) * fh);
        float tw = sw ? fh : fw, th = sw ? fw : fh;
        tv.layout(Math.round(cx - tw / 2), Math.round(cy - th / 2), Math.round(cx + tw / 2), Math.round(cy + th / 2));
        inner.setPivotX(rw / 2f);
        inner.setPivotY(rh / 2f);
        setClipBounds(new android.graphics.Rect(Math.round(rect.left), Math.round(rect.top),
                Math.round(rect.right), Math.round(rect.bottom)));
    }
}
