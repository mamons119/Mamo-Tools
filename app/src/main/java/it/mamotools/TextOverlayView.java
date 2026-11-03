package it.mamotools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Disegna sopra il VideoView i testi attivi alla posizione corrente (solo anteprima). */
final class TextOverlayView extends View {
    private List<VideoFx.Item> items;
    private long posMs;
    private int vw, vh;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<VideoFx.Item, Object[]> cache = new HashMap<>();

    TextOverlayView(Context c) {
        super(c);
        setClickable(false);
    }

    void setItems(List<VideoFx.Item> l) {
        items = l;
        cache.clear();
        invalidate();
    }

    void setVideoSize(int displayW, int displayH) {
        vw = displayW;
        vh = displayH;
        cache.clear();
        invalidate();
    }

    void setPos(long ms) {
        if (ms != posMs) {
            posMs = ms;
            invalidate();
        }
    }

    /** Da chiamare quando un elemento e' stato modificato. */
    void refresh() {
        cache.clear();
        invalidate();
    }

    static String sig(VideoFx.Item o, int h) {
        return o.text + "|" + o.font + o.bold + o.italic + o.sizeFrac + o.color + o.outline + o.shadow + o.box + "|" + h;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (items == null || items.isEmpty() || vw <= 0 || vh <= 0) {
            return;
        }
        float s = Math.min(getWidth() / (float) vw, getHeight() / (float) vh);
        float rw = vw * s, rh = vh * s;
        float l = (getWidth() - rw) / 2f, t = (getHeight() - rh) / 2f;
        long us = posMs * 1000;
        for (VideoFx.Item it : items) {
            if (!it.isText || !it.activeAt(us) || it.text == null || it.text.trim().isEmpty()) {
                continue;
            }
            int hh = Math.round(rh);
            String sg = sig(it, hh);
            Object[] e = cache.get(it);
            if (e == null || !sg.equals(e[0])) {
                Bitmap b = VideoFx.render(it, Math.round(rw), hh, 0);
                e = new Object[]{sg, b};
                cache.put(it, e);
            }
            Bitmap b = (Bitmap) e[1];
            if (b == null) {
                continue;
            }
            c.drawBitmap(b, l + it.x * rw - b.getWidth() / 2f, t + it.y * rh - b.getHeight() / 2f, paint);
        }
    }
}
