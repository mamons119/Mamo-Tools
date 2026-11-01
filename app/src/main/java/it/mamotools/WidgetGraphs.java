package it.mamotools;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import java.util.Locale;

/** Disegna le tre pagine del widget (CPU, RAM, rete) come immagini. */
final class WidgetGraphs {

    private static final int[] PALETTE = {
            0xFFE95420, 0xFF4DD0E1, 0xFFFFD60A, 0xFF8BC34A, 0xFFBA68C8, 0xFFFF6B6B,
            0xFF64B5F6, 0xFFFFB74D, 0xFF4DB6AC, 0xFFF06292, 0xFFAED581, 0xFF9575CD,
            0xFFFFF176, 0xFF4FC3F7, 0xFFA1887F, 0xFF90A4AE};

    private WidgetGraphs() {
    }

    static Bitmap render(int page, int w, int h) {
        MonitorSampler s = MonitorSampler.get();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float u = Math.min(w / 560f, h / 250f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xFF1A1D24);

        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.DEFAULT_BOLD);

        float gl = 6 * u, gr = w - 6 * u, gt = 44 * u, gb = h - 24 * u;
        String title;
        String right;
        float max;
        float[][] data;
        int[] colors;
        synchronized (s) {
            int n = MonitorSampler.HIST;
            if (page == 0) {
                title = "CPU";
                float sum = 0;
                for (int i = 0; i < s.cores; i++) {
                    sum += s.cpu[i][n - 1];
                }
                right = String.format(Locale.US, "%.0f%%  ·  %d core", sum / s.cores, s.cores);
                max = 100f;
                data = new float[s.cores][];
                colors = new int[s.cores];
                for (int i = 0; i < s.cores; i++) {
                    data[i] = s.cpu[i].clone();
                    colors[i] = PALETTE[i % PALETTE.length];
                }
            } else if (page == 1) {
                title = "RAM";
                right = String.format(Locale.US, "%s / %s  (%.0f%%)", size(s.ramUsed), size(s.ramTotal),
                        s.ram[n - 1]);
                max = 100f;
                data = new float[][]{s.ram.clone(), s.swap.clone()};
                colors = new int[]{0xFF4DD0E1, 0xFFFF6B6B};
            } else {
                title = "Rete";
                right = "↓ " + rate(s.dl[n - 1]) + "   ↑ " + rate(s.ul[n - 1]);
                float m = 0;
                for (int i = 0; i < n; i++) {
                    m = Math.max(m, Math.max(s.dl[i], s.ul[i]));
                }
                max = Math.max(10f * 1024f, m * 1.2f);
                data = new float[][]{s.dl.clone(), s.ul.clone()};
                colors = new int[]{0xFF8BC34A, 0xFFFFB74D};
            }
        }

        t.setColor(0xFF4DD0E1);
        t.setTextSize(22 * u);
        t.setTextAlign(Paint.Align.LEFT);
        c.drawText(title, gl, 30 * u, t);
        t.setColor(0xFFECEFF4);
        t.setTextSize(17 * u);
        t.setTextAlign(Paint.Align.RIGHT);
        c.drawText(right, gr, 30 * u, t);

        // griglia
        p.setColor(0x33FFFFFF);
        p.setStrokeWidth(1f);
        for (int i = 0; i <= 4; i++) {
            float y = gt + (gb - gt) * i / 4f;
            c.drawLine(gl, y, gr, y, p);
        }
        t.setColor(0xFF9AA3B2);
        t.setTextSize(13 * u);
        t.setTextAlign(Paint.Align.LEFT);
        t.setTypeface(Typeface.DEFAULT);
        c.drawText(page == 2 ? rate(max) : "100%", gl + 3 * u, gt + 14 * u, t);

        // linee
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1.5f, 1.8f * u));
        line.setStrokeJoin(Paint.Join.ROUND);
        int n = MonitorSampler.HIST;
        float step = (gr - gl) / (n - 1);
        Path path = new Path();
        for (int sIdx = 0; sIdx < data.length; sIdx++) {
            line.setColor(colors[sIdx]);
            path.reset();
            for (int i = 0; i < n; i++) {
                float x = gl + i * step;
                float y = gb - (gb - gt) * Math.min(1f, data[sIdx][i] / max);
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            c.drawPath(path, line);
        }

        // indicatore di pagina (3 punti)
        for (int i = 0; i < 3; i++) {
            p.setColor(i == page ? 0xFF4DD0E1 : 0x55FFFFFF);
            c.drawCircle(w / 2f + (i - 1) * 18 * u, h - 11 * u, 4 * u, p);
        }
        return bmp;
    }

    private static String size(long b) {
        double v = b;
        String[] un = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < un.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, i < 3 ? "%.0f %s" : "%.1f %s", v, un[i]);
    }

    private static String rate(float bps) {
        if (bps < 1024) {
            return String.format(Locale.US, "%.0f B/s", bps);
        }
        if (bps < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB/s", bps / 1024f);
        }
        return String.format(Locale.US, "%.2f MB/s", bps / 1048576f);
    }
}
