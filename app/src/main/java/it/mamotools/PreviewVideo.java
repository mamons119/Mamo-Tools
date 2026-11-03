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
    private MediaPlayer mp;
    private Surface surface;
    private boolean prepared = false;
    private int dispW = 0, dispH = 0;
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
        addView(tv, new LayoutParams(10, 10));
        setClipChildren(true);
    }

    void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
        onPrepared = l;
    }

    void setOnCompletionListener(MediaPlayer.OnCompletionListener l) {
        onCompletion = l;
    }

    /** Dimensioni del video come appare (gia' ruotato), per il rapporto d'aspetto. */
    void setDisplaySize(int w, int h) {
        dispW = w;
        dispH = h;
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
                    dispW = m.getVideoWidth();
                    dispH = m.getVideoHeight();
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
        float[] arr = cm == null ? null : cm.getArray().clone();
        boolean same = (arr == null && lastCm == null) || (arr != null && lastCm != null
                && java.util.Arrays.equals(arr, lastCm));
        if (!same) {
            lastCm = arr;
            layerPaint.setColorFilter(cm == null ? null : new ColorMatrixColorFilter(cm));
            tv.setLayerType(LAYER_TYPE_HARDWARE, layerPaint);
        }
        tv.setScaleX(zoom);
        tv.setScaleY(zoom);
    }

    /** Fotogramma corrente ridotto (senza filtri), oppure null. */
    Bitmap grab(int w, int h) {
        if (!tv.isAvailable() || w < 2 || h < 2) {
            return null;
        }
        try {
            return tv.getBitmap(w, h);
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
        tv.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rw)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rh)), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        tv.layout(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
        setClipBounds(new android.graphics.Rect(Math.round(rect.left), Math.round(rect.top),
                Math.round(rect.right), Math.round(rect.bottom)));
    }
}
