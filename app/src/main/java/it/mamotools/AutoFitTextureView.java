package it.mamotools;

import android.content.Context;
import android.view.TextureView;

/** TextureView che mantiene le proporzioni dell'anteprima (senza deformarla). */
public class AutoFitTextureView extends TextureView {

    private int ratioW = 0;
    private int ratioH = 0;

    public AutoFitTextureView(Context context) {
        super(context);
    }

    public void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width == ratioW && height == ratioH) {
            return;
        }
        ratioW = width;
        ratioH = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h);
        } else if (w < h * ratioW / ratioH) {
            setMeasuredDimension(w, w * ratioH / ratioW);
        } else {
            setMeasuredDimension(h * ratioW / ratioH, h);
        }
    }
}
