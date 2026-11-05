package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Icone disegnate in linea (griglia 24x24) per i veicoli e il casco. */
final class Icons {

    private Icons() {
    }

    /** Icona di un veicolo: 0 macchina, 1 moto, 2 bicicletta, 3 barca, 4 canoa. */
    static final class VehicleIcon extends View {
        private final int kind;
        private int color = Ui.TEXT;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        VehicleIcon(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        private void line(Canvas cv, float... xy) {
            path.reset();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) {
                path.lineTo(xy[i], xy[i + 1]);
            }
            cv.drawPath(path, p);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float sc = Math.min(getWidth(), getHeight()) / 24f;
            cv.save();
            cv.translate((getWidth() - 24 * sc) / 2f, (getHeight() - 24 * sc) / 2f);
            cv.scale(sc, sc);
            p.setColor(color);
            p.setStrokeWidth(1.8f);
            switch (kind) {
                case 0: // macchina
                    line(cv, 4.6f, 16.5f, 2.5f, 16.5f, 2.5f, 12.8f, 5.2f, 11.6f, 8f, 7.5f, 16f, 7.5f, 18.8f, 11.6f,
                            21.5f, 12.8f, 21.5f, 16.5f, 19.4f, 16.5f);
                    line(cv, 9.4f, 16.5f, 14.6f, 16.5f);
                    line(cv, 5.2f, 11.6f, 18.8f, 11.6f);
                    line(cv, 12f, 7.5f, 12f, 11.6f);
                    cv.drawCircle(7f, 16.7f, 2.4f, p);
                    cv.drawCircle(17f, 16.7f, 2.4f, p);
                    break;
                case 1: // moto
                    cv.drawCircle(5.5f, 16f, 3.3f, p);
                    cv.drawCircle(18.5f, 16f, 3.3f, p);
                    line(cv, 5.5f, 16f, 9.5f, 10.5f, 15f, 10.5f, 18.5f, 16f);
                    line(cv, 9.5f, 10.5f, 12f, 16f, 15.5f, 14f);
                    line(cv, 15f, 10.5f, 14f, 7f, 17.2f, 6.6f);
                    line(cv, 6.5f, 9.6f, 11f, 9.6f);
                    break;
                case 2: // bicicletta
                    cv.drawCircle(5.8f, 16.5f, 3.8f, p);
                    cv.drawCircle(18.2f, 16.5f, 3.8f, p);
                    line(cv, 5.8f, 16.5f, 10.8f, 16.5f, 9.3f, 9.8f, 5.8f, 16.5f);
                    line(cv, 9.3f, 9.8f, 15.8f, 9.8f, 10.8f, 16.5f);
                    line(cv, 15.8f, 9.8f, 18.2f, 16.5f);
                    line(cv, 7.8f, 8.3f, 10.8f, 8.3f);
                    line(cv, 15.8f, 9.8f, 14.8f, 6.8f, 17.4f, 6.8f);
                    break;
                case 3: // barca
                    line(cv, 2.5f, 13.5f, 21.5f, 13.5f, 18.6f, 18.5f, 5.4f, 18.5f, 2.5f, 13.5f);
                    line(cv, 6.5f, 13.5f, 9f, 8.8f, 14.2f, 8.8f, 16.2f, 13.5f);
                    line(cv, 11.4f, 8.8f, 11.4f, 13.5f);
                    line(cv, 3.5f, 21.3f, 6f, 20.4f, 8.5f, 21.3f, 11f, 20.4f, 13.5f, 21.3f, 16f, 20.4f, 18.5f,
                            21.3f, 20.5f, 20.6f);
                    break;
                default: // canoa
                    path.reset();
                    path.moveTo(2f, 13f);
                    path.quadTo(12f, 22.5f, 22f, 13f);
                    path.lineTo(2f, 13f);
                    cv.drawPath(path, p);
                    line(cv, 5.5f, 6f, 18.5f, 11f);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(5.2f, 5.8f, 1.7f, p);
                    cv.drawCircle(18.8f, 11.1f, 1.7f, p);
                    p.setStyle(Paint.Style.STROKE);
                    break;
            }
            cv.restore();
        }
    }

    /** Casco rosso, per il registratore "Onboard". */
    static final class Helmet extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Helmet(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float sc = Math.min(getWidth(), getHeight()) / 24f;
            cv.save();
            cv.translate((getWidth() - 24 * sc) / 2f, (getHeight() - 24 * sc) / 2f);
            cv.scale(sc, sc);
            // calotta
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFE53935);
            path.reset();
            path.moveTo(2.8f, 15f);
            path.cubicTo(2.8f, 8.2f, 7f, 4.2f, 12.4f, 4.2f);
            path.cubicTo(17.6f, 4.2f, 21.2f, 8f, 21.2f, 12.8f);
            path.lineTo(21.2f, 17f);
            path.cubicTo(21.2f, 17.9f, 20.5f, 18.6f, 19.6f, 18.6f);
            path.lineTo(11.5f, 18.6f);
            path.cubicTo(10f, 18.6f, 9.4f, 17.3f, 8.4f, 16.4f);
            path.cubicTo(7.5f, 15.6f, 6.5f, 15.6f, 5.5f, 15.6f);
            path.cubicTo(3.9f, 15.6f, 2.8f, 15.6f, 2.8f, 15f);
            path.close();
            cv.drawPath(path, p);
            // visiera
            p.setColor(0xFF1A1D24);
            path.reset();
            path.moveTo(10.2f, 9.4f);
            path.lineTo(21.2f, 9.4f);
            path.lineTo(21.2f, 13.6f);
            path.lineTo(11.6f, 13.6f);
            path.cubicTo(10.4f, 13.6f, 9.6f, 12.6f, 9.6f, 11.6f);
            path.cubicTo(9.6f, 10.6f, 9.8f, 9.8f, 10.2f, 9.4f);
            path.close();
            cv.drawPath(path, p);
            // riflesso sulla visiera e striscia
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(1.1f);
            p.setColor(0x66FFFFFF);
            cv.drawLine(12.5f, 10.8f, 17f, 10.8f, p);
            p.setStrokeWidth(1.4f);
            p.setColor(0xFFFFFFFF);
            path.reset();
            path.moveTo(6.2f, 8.4f);
            path.quadTo(9f, 5.6f, 13f, 5.8f);
            cv.drawPath(path, p);
            cv.restore();
        }
    }
}
