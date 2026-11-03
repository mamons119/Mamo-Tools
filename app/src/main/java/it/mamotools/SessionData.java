package it.mamotools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sessione di telemetria registrata. Formato CSV, una riga ogni ~100 ms:
 * t_ms,lat,lon,alt,velocita_ms,direzione,precisione,g_long,g_lat,piega,imbardata
 * piu' righe speciali: #MAMOTEL1,epoch_ms,nome / #LINE,lat,lon,direzione / #LAP,t_ms,n
 */
final class SessionData {

    String name = "";
    long startEpoch;
    int n;
    double[] t, lat, lon, alt, v, brg, acc, aLong, aLat, lean, yaw, dist;
    double[] line;                       // lat, lon, direzione del traguardo (o null)
    final List<double[]> laps = new ArrayList<>(); // {t_s, n}

    // statistiche
    double duration, distM, vMax, vAvgMoving, movingTime, maxAccelG, maxBrakeG, maxLatG;
    double maxLeanL, maxLeanR, altGain, altMin, altMax;
    double latMin, latMax, lonMin, lonMax;

    static String header(long epoch, String name) {
        return "#MAMOTEL1," + epoch + "," + name.replace(',', ' ').replace('\n', ' ');
    }

    static SessionData load(File f) throws IOException {
        SessionData s = new SessionData();
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                if (ln.isEmpty()) {
                    continue;
                }
                if (ln.startsWith("#")) {
                    String[] p = ln.split(",", 3);
                    try {
                        if (ln.startsWith("#MAMOTEL1") && p.length >= 3) {
                            s.startEpoch = Long.parseLong(p[1]);
                            s.name = p[2];
                        } else if (ln.startsWith("#LINE")) {
                            String[] q = ln.split(",");
                            s.line = new double[]{Double.parseDouble(q[1]), Double.parseDouble(q[2]),
                                    Double.parseDouble(q[3])};
                        } else if (ln.startsWith("#LAP")) {
                            String[] q = ln.split(",");
                            s.laps.add(new double[]{Double.parseDouble(q[1]) / 1000.0, Double.parseDouble(q[2])});
                        }
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                String[] p = ln.split(",");
                if (p.length < 11) {
                    continue;
                }
                try {
                    double[] r = new double[11];
                    for (int i = 0; i < 11; i++) {
                        r[i] = Double.parseDouble(p[i]);
                    }
                    rows.add(r);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        s.n = rows.size();
        s.t = new double[s.n];
        s.lat = new double[s.n];
        s.lon = new double[s.n];
        s.alt = new double[s.n];
        s.v = new double[s.n];
        s.brg = new double[s.n];
        s.acc = new double[s.n];
        s.aLong = new double[s.n];
        s.aLat = new double[s.n];
        s.lean = new double[s.n];
        s.yaw = new double[s.n];
        s.dist = new double[s.n];
        for (int i = 0; i < s.n; i++) {
            double[] r = rows.get(i);
            s.t[i] = r[0] / 1000.0;
            s.lat[i] = r[1];
            s.lon[i] = r[2];
            s.alt[i] = r[3];
            s.v[i] = r[4];
            s.brg[i] = r[5];
            s.acc[i] = r[6];
            s.aLong[i] = r[7];
            s.aLat[i] = r[8];
            s.lean[i] = r[9];
            s.yaw[i] = r[10];
        }
        s.stats();
        return s;
    }

    private void stats() {
        if (n == 0) {
            return;
        }
        duration = t[n - 1] - t[0];
        latMin = lonMin = 1e9;
        latMax = lonMax = -1e9;
        altMin = 1e9;
        altMax = -1e9;
        double mv = 0, ref = alt[0];
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                double dt = t[i] - t[i - 1];
                dist[i] = dist[i - 1] + (v[i] > 0.5 ? v[i] * dt : 0);
                if (v[i] > 1.0) {
                    movingTime += dt;
                    mv += v[i] * dt;
                }
            }
            vMax = Math.max(vMax, v[i]);
            maxAccelG = Math.max(maxAccelG, aLong[i]);
            maxBrakeG = Math.max(maxBrakeG, -aLong[i]);
            maxLatG = Math.max(maxLatG, Math.abs(aLat[i]));
            if (lean[i] > 0) {
                maxLeanL = Math.max(maxLeanL, lean[i]);
            } else {
                maxLeanR = Math.max(maxLeanR, -lean[i]);
            }
            latMin = Math.min(latMin, lat[i]);
            latMax = Math.max(latMax, lat[i]);
            lonMin = Math.min(lonMin, lon[i]);
            lonMax = Math.max(lonMax, lon[i]);
            altMin = Math.min(altMin, alt[i]);
            altMax = Math.max(altMax, alt[i]);
            if (alt[i] > ref + 3) {
                altGain += alt[i] - ref;
                ref = alt[i];
            } else if (alt[i] < ref - 3) {
                ref = alt[i];
            }
        }
        distM = dist[n - 1];
        vAvgMoving = movingTime > 0 ? mv / movingTime : 0;
    }

    /** Tempi dei giri (secondi), dal primo passaggio sul traguardo. */
    List<Double> lapTimes() {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < laps.size(); i++) {
            out.add(laps.get(i)[0] - laps.get(i - 1)[0]);
        }
        return out;
    }

    static String fmtTime(double s) {
        int m = (int) (s / 60);
        return String.format(Locale.ITALY, "%d:%05.2f", m, s - m * 60);
    }

    String gpx() {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder(n * 120 + 400);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"Mamo Tools\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("<metadata><name>").append(esc(name)).append("</name></metadata>\n<trk><name>")
                .append(esc(name)).append("</name><trkseg>\n");
        for (int i = 0; i < n; i++) {
            if (i > 0 && i % 5 != 0 && i != n - 1) {
                continue; // 2 punti al secondo bastano
            }
            sb.append("<trkpt lat=\"").append(String.format(Locale.US, "%.7f", lat[i]))
                    .append("\" lon=\"").append(String.format(Locale.US, "%.7f", lon[i])).append("\"><ele>")
                    .append(String.format(Locale.US, "%.1f", alt[i])).append("</ele><time>")
                    .append(iso.format(new Date(startEpoch + (long) (t[i] * 1000)))).append("</time>")
                    .append("<extensions><speed>").append(String.format(Locale.US, "%.2f", v[i]))
                    .append("</speed></extensions></trkpt>\n");
        }
        sb.append("</trkseg></trk></gpx>\n");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ traguardo

    /**
     * Frazione (0..1) lungo il segmento p1->p2 in cui attraversa il traguardo, oppure -1.
     * Il traguardo e' un segmento di 2*halfW metri centrato in (cLat,cLon), perpendicolare a heading.
     */
    static double crossFraction(double cLat, double cLon, double headingDeg, double halfW,
                                double lat1, double lon1, double lat2, double lon2) {
        double mLat = 111320.0, mLon = 111320.0 * Math.cos(Math.toRadians(cLat));
        double x1 = (lon1 - cLon) * mLon, y1 = (lat1 - cLat) * mLat;
        double x2 = (lon2 - cLon) * mLon, y2 = (lat2 - cLat) * mLat;
        double hx = Math.sin(Math.toRadians(headingDeg)), hy = Math.cos(Math.toRadians(headingDeg));
        // direzione del movimento deve essere concorde con la direzione del traguardo
        if ((x2 - x1) * hx + (y2 - y1) * hy <= 0) {
            return -1;
        }
        // coordinate nel sistema (lungo hx,hy ; trasversale)
        double a1 = x1 * hx + y1 * hy, a2 = x2 * hx + y2 * hy;
        if (a1 > 0 || a2 < 0) {
            return -1;
        }
        double s = (0 - a1) / (a2 - a1);
        double px = x1 + s * (x2 - x1), py = y1 + s * (y2 - y1);
        double along = -px * hy + py * hx; // trasversale
        if (Math.abs(along) > halfW) {
            return -1;
        }
        return s;
    }
}
