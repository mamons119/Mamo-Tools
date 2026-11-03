package it.mamotools;

import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Fix GPS alla massima frequenza possibile, con tempo in "elapsed realtime" (stesso orologio dei sensori). */
final class GpsSource implements LocationListener {

    static final class Fix {
        long tNs;
        double lat, lon, alt;
        float speed, bearing, acc;
    }

    interface Cb {
        void onFix(Fix f);
    }

    private final LocationManager lm;
    private final Cb cb;
    private GnssStatus.Callback gcb;
    private Fix last;
    private boolean running = false;
    float rateHz = 0;
    int satsUsed = 0, satsTotal = 0;
    long lastFixMs = 0;

    GpsSource(Context c, Cb cb) {
        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        this.cb = cb;
    }

    boolean enabled() {
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    boolean start() {
        if (running) {
            return true;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this, Looper.getMainLooper());
            gcb = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus s) {
                    int used = 0;
                    for (int i = 0; i < s.getSatelliteCount(); i++) {
                        if (s.usedInFix(i)) {
                            used++;
                        }
                    }
                    satsUsed = used;
                    satsTotal = s.getSatelliteCount();
                }
            };
            lm.registerGnssStatusCallback(gcb, new Handler(Looper.getMainLooper()));
            running = true;
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            lm.removeUpdates(this);
            if (gcb != null) {
                lm.unregisterGnssStatusCallback(gcb);
            }
        } catch (Exception ignored) {
        }
        last = null;
    }

    boolean hasRecentFix() {
        return lastFixMs > 0 && System.currentTimeMillis() - lastFixMs < 4000;
    }

    @Override
    public void onLocationChanged(Location l) {
        Fix f = new Fix();
        f.tNs = l.getElapsedRealtimeNanos();
        f.lat = l.getLatitude();
        f.lon = l.getLongitude();
        f.alt = l.hasAltitude() ? l.getAltitude() : 0;
        f.bearing = l.hasBearing() ? l.getBearing() : 0;
        f.acc = l.hasAccuracy() ? l.getAccuracy() : 99;
        if (l.hasSpeed()) {
            f.speed = l.getSpeed();
        } else if (last != null) {
            float[] r = new float[1];
            Location.distanceBetween(last.lat, last.lon, f.lat, f.lon, r);
            double dt = (f.tNs - last.tNs) / 1e9;
            f.speed = dt > 0 ? (float) (r[0] / dt) : 0;
        }
        if (last != null) {
            double dt = (f.tNs - last.tNs) / 1e9;
            if (dt > 0.01 && dt < 5) {
                float inst = (float) (1.0 / dt);
                rateHz = rateHz == 0 ? inst : rateHz * 0.8f + inst * 0.2f;
            }
        }
        last = f;
        lastFixMs = System.currentTimeMillis();
        cb.onFix(f);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
