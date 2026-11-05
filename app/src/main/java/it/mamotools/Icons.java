package it.mamotools;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.ImageView;

/** Icone vettoriali disegnate a mano per i veicoli e il casco. */
final class Icons {

    private static final int[] VEH = {R.drawable.ic_veh_car, R.drawable.ic_veh_moto, R.drawable.ic_veh_bike,
            R.drawable.ic_veh_boat, R.drawable.ic_veh_canoe};

    private Icons() {
    }

    /** Icona di un veicolo: 0 macchina, 1 moto, 2 bicicletta, 3 barca, 4 canoa; il colore si cambia con setColor. */
    static final class VehicleIcon extends ImageView {
        VehicleIcon(Context c, int kind) {
            super(c);
            setImageResource(VEH[kind]);
            setScaleType(ScaleType.FIT_CENTER);
            setColor(Ui.TEXT);
        }

        void setColor(int color) {
            setImageTintList(ColorStateList.valueOf(color));
        }
    }

    /** Casco rosso, per il registratore "Onboard". */
    static final class Helmet extends ImageView {
        Helmet(Context c) {
            super(c);
            setImageResource(R.drawable.ic_helmet);
            setScaleType(ScaleType.FIT_CENTER);
        }
    }

    static ImageView edit(Context c, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(R.drawable.ic_edit);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }
}
