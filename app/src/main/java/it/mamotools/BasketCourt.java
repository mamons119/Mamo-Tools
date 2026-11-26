package it.mamotools;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/** Disegna il campo da basket FIBA (parquet, aree colorate, linee bianche) in coordinate reali in metri. */
final class BasketCourt {

    static final float MARGIN = 1.3f;

    private BasketCourt() {
    }

    /** Parte di campo visibile, in metri. */
    static RectF bounds(boolean full) {
        return new RectF(-MARGIN, -MARGIN, full ? 28 + MARGIN : 15.0f, 15 + MARGIN);
    }

    /** Campo completo gia' disegnato su una bitmap. */
    static Bitmap render(int w, int h, boolean full, Matrix w2s) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF0D0F14);
        RectF b = bounds(full);
        c.save();
        c.concat(w2s);
        c.clipRect(b);
        wood(c, b);
        c.restore();
        c.save();
        c.concat(w2s);
        c.clipRect(b);
        markings(c, b, full);
        c.restore();
        // luce: riflesso al centro, bordi piu' scuri, striscia lucida diagonale
        RectF sb = new RectF(b);
        w2s.mapRect(sb);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = sb.centerX(), cy = sb.centerY();
        float rad = Math.max(sb.width(), sb.height()) * 0.75f;
        p.setShader(new RadialGradient(cx, cy - sb.height() * 0.05f, rad,
                new int[]{0x29FFF5DC, 0x00FFF5DC, 0x61000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.clipRect(sb);
        c.drawRect(sb, p);
        p.setShader(new LinearGradient(sb.left, sb.top, sb.right, sb.bottom,
                new int[]{0x00FFFFFF, 0x00FFFFFF, 0x1AFFFAEB, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.42f, 0.5f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(sb, p);
        c.restore();
        return bmp;
    }

    private static void wood(Canvas c, RectF b) {
        Random r = new Random(7);
        int[][] base = {{222, 176, 108}, {214, 166, 98}, {228, 184, 118}, {208, 158, 92}, {219, 172, 104}};
        float pw = 0.115f;
        Paint fill = new Paint();
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(0.006f);
        Path path = new Path();
        for (float y = b.top; y < b.bottom; y += pw) {
            float x = b.left - r.nextFloat() * 2;
            while (x < b.right) {
                float len = 1.2f + r.nextFloat() * 2.4f;
                int[] bc = base[r.nextInt(base.length)];
                float k = 0.94f + r.nextFloat() * 0.12f;
                fill.setColor(Color.rgb(Math.min(255, (int) (bc[0] * k)), Math.min(255, (int) (bc[1] * k)),
                        Math.min(255, (int) (bc[2] * k))));
                c.drawRect(x, y, x + len + 0.01f, y + pw + 0.004f, fill);
                for (int i = 0; i < 3; i++) {
                    float yy = y + pw * (0.2f + 0.6f * r.nextFloat());
                    boolean dark = r.nextFloat() < 0.5f;
                    int a = (int) ((0.07f + r.nextFloat() * 0.10f) * 255);
                    line.setColor(dark ? Color.argb(a, 110, 70, 30) : Color.argb(a, 255, 235, 200));
                    path.reset();
                    path.moveTo(x, yy);
                    path.cubicTo(x + len * 0.3f, yy + (r.nextFloat() - 0.5f) * 0.03f,
                            x + len * 0.6f, yy + (r.nextFloat() - 0.5f) * 0.03f,
                            x + len, yy + (r.nextFloat() - 0.5f) * 0.02f);
                    c.drawPath(path, line);
                }
                fill.setColor(0x59 << 24 | 0x3C230F);
                c.drawRect(x, y, x + 0.012f, y + pw, fill);
                x += len;
            }
            fill.setColor(0x4D << 24 | 0x3C230F);
            c.drawRect(b.left, y, b.right, y + 0.010f, fill);
        }
        // fuori campo piu' scuro
        Path out = new Path();
        out.setFillType(Path.FillType.EVEN_ODD);
        out.addRect(b, Path.Direction.CW);
        out.addRect(0, 0, b.right, 15, Path.Direction.CW);
        fill.setColor(0x4D << 24 | 0x1E1006);
        c.drawPath(out, fill);
    }

    private static void markings(Canvas c, RectF b, boolean full) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // aree colorate (zona dei 3 secondi e semicerchio dei tiri liberi)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x9E << 24 | 0xA83426);
        for (int side = 0; side < (full ? 2 : 1); side++) {
            c.save();
            if (side == 1) {
                c.translate(28, 0);
                c.scale(-1, 1);
            }
            c.drawRect(0, 5.05f, 5.8f, 9.95f, paint);
            c.drawArc(new RectF(5.8f - 1.8f, 7.5f - 1.8f, 5.8f + 1.8f, 7.5f + 1.8f), -90, 180, true, paint);
            c.restore();
        }
        // linee bianche
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xF5FFFFFF);
        paint.setStrokeWidth(0.075f);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        for (int side = 0; side < (full ? 2 : 1); side++) {
            c.save();
            if (side == 1) {
                c.translate(28, 0);
                c.scale(-1, 1);
            }
            Path p = new Path();
            p.moveTo(0, 5.05f);
            p.lineTo(5.8f, 5.05f);
            p.lineTo(5.8f, 9.95f);
            p.lineTo(0, 9.95f);
            c.drawPath(p, paint);
            RectF ft = new RectF(5.8f - 1.8f, 7.5f - 1.8f, 5.8f + 1.8f, 7.5f + 1.8f);
            p = new Path();
            p.addArc(ft, -90, 180);
            c.drawPath(p, paint);
            paint.setPathEffect(new DashPathEffect(new float[]{0.35f, 0.3f}, 0));
            p = new Path();
            p.addArc(ft, 90, 180);
            c.drawPath(p, paint);
            paint.setPathEffect(null);
            // semicerchio "no sfondamento"
            p = new Path();
            p.moveTo(0, 7.5f - 1.25f);
            p.lineTo(1.575f, 7.5f - 1.25f);
            p.arcTo(new RectF(1.575f - 1.25f, 7.5f - 1.25f, 1.575f + 1.25f, 7.5f + 1.25f), -90, 180);
            p.lineTo(0, 7.5f + 1.25f);
            c.drawPath(p, paint);
            // linea da tre punti
            double dx = Math.sqrt(6.75 * 6.75 - 6.6 * 6.6);
            double ang = Math.toDegrees(Math.asin(6.6 / 6.75));
            p = new Path();
            p.moveTo(0, 0.9f);
            p.lineTo((float) (1.575 + dx), 0.9f);
            p.arcTo(new RectF(1.575f - 6.75f, 7.5f - 6.75f, 1.575f + 6.75f, 7.5f + 6.75f), (float) -ang,
                    (float) (2 * ang));
            p.lineTo(0, 14.1f);
            c.drawPath(p, paint);
            // segni sulla zona
            for (float px : new float[]{1.75f, 2.9f, 4.05f}) {
                c.drawLine(px, 5.05f, px, 4.83f, paint);
                c.drawLine(px, 9.95f, px, 10.17f, paint);
            }
            // tabellone, sostegno e ferro
            paint.setStrokeWidth(0.06f);
            paint.setColor(0xE6FFFFFF);
            c.drawLine(1.2f, 6.6f, 1.2f, 8.4f, paint);
            paint.setColor(0xE63C3C3C);
            paint.setStrokeWidth(0.05f);
            c.drawLine(1.2f, 7.5f, 1.575f - 0.2285f, 7.5f, paint);
            paint.setColor(0xFFE8591A);
            c.drawCircle(1.575f, 7.5f, 0.2285f, paint);
            paint.setColor(0xF5FFFFFF);
            paint.setStrokeWidth(0.075f);
            c.restore();
        }
        // bordo, linea di meta' campo e cerchio centrale
        c.drawRect(0, 0, full ? 28 : b.right, 15, paint);
        c.drawLine(14, 0, 14, 15, paint);
        c.drawCircle(14, 7.5f, 1.8f, paint);
    }
}
