package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Rivedi una sessione: statistiche, mappa col percorso colorato per velocita', grafici e diagramma G-G. */
public class SessionViewActivity extends Activity {

    private File file;
    private SessionData s;
    private LinearLayout root;
    private TileMapView map;
    private ChartView chart;
    private TextView cursorTv;
    private TextView[] layerChips, chanChips;
    private int chan = 0;

    private static final String[] CHAN = {"Velocità", "G longitudinale", "G laterale", "Piega", "Quota"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Ui.screen(this, "Sessione", "Caricamento…");
        file = new File(getIntent().getStringExtra("path"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SessionData d = SessionData.load(file);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            s = d;
                            if (d.n < 5) {
                                ((TextView) root.getChildAt(1)).setText("Sessione troppo corta o senza dati GPS.");
                                addDelete();
                            } else {
                                build();
                            }
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ((TextView) root.getChildAt(1)).setText("Impossibile leggere la sessione: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void build() {
        ((TextView) root.getChildAt(0)).setText(s.name.isEmpty() ? "Sessione" : s.name);
        ((TextView) root.getChildAt(1)).setText(String.format(Locale.ITALY, "%s · %s", SessionData.fmtTime(s.duration),
                s.distM >= 1000 ? String.format(Locale.ITALY, "%.2f km", s.distM / 1000) : Math.round(s.distM) + " m"));

        // ---- statistiche
        LinearLayout sc = Ui.card(this);
        sc.addView(Ui.section(this, "Riepilogo"));
        TextView st = Ui.text(this, String.format(Locale.ITALY,
                "Distanza: %.2f km\nDurata: %s  (in movimento %s)\nVelocità max: %.0f km/h   media in marcia: %.0f km/h\n"
                        + "Accelerazione max: %.2f G   frenata max: %.2f G\nForza laterale max: %.2f G\n"
                        + "Piega max: SX %.0f°   DX %.0f°\nQuota: %.0f–%.0f m   dislivello positivo: %.0f m",
                s.distM / 1000, SessionData.fmtTime(s.duration), SessionData.fmtTime(s.movingTime), s.vMax * 3.6,
                s.vAvgMoving * 3.6, s.maxAccelG, s.maxBrakeG, s.maxLatG, s.maxLeanL, s.maxLeanR,
                s.altMin, s.altMax, s.altGain), 14, Ui.TEXT, false);
        st.setLineSpacing(0, 1.3f);
        sc.addView(st);
        root.addView(sc);

        // ---- mappa
        LinearLayout mc = Ui.card(this);
        mc.addView(Ui.section(this, "Percorso"));
        map = new TileMapView(this);
        map.setTrack(s.lat, s.lon, s.v, s.n);
        if (s.line != null) {
            map.setGate(s.line[0], s.line[1]);
        }
        mc.addView(map, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 340)));
        LinearLayout lr = new LinearLayout(this);
        layerChips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int l = i;
            layerChips[i] = Ui.chip(this, TileMapView.NAMES[i]);
            layerChips[i].setTextSize(12);
            layerChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    map.setLayer(l);
                    styleLayers();
                }
            });
            lr.addView(layerChips[i], Ui.lp(this, 0, Ui.dp(this, 38), i == 0 ? 0 : 6, 0, 1f));
        }
        mc.addView(lr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        LinearLayout br = new LinearLayout(this);
        br.addView(Ui.btn(this, "Centra", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                map.fit();
            }
        }), Ui.lp(this, 0, Ui.dp(this, 42), 0, 0, 1f));
        br.addView(Ui.btn(this, "Apri in Google Maps", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMaps();
            }
        }), Ui.lp(this, 0, Ui.dp(this, 42), 8, 0, 2f));
        mc.addView(br, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        TextView mn = Ui.text(this, "Colori: dal blu (piano) al rosso (veloce). Tocca il percorso per vedere il punto "
                + "nel grafico. Google Maps mostra solo un percorso approssimato su strada: per i tracciati "
                + "esatti (anche fuoristrada) usa questa mappa o esporta il GPX.", 11, Ui.DIM, false);
        mn.setPadding(0, Ui.dp(this, 8), 0, 0);
        mc.addView(mn);
        root.addView(mc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        styleLayers();

        // ---- grafici
        LinearLayout cc = Ui.card(this);
        cc.addView(Ui.section(this, "Telemetria"));
        LinearLayout cr = new LinearLayout(this);
        chanChips = new TextView[CHAN.length];
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        for (int i = 0; i < CHAN.length; i++) {
            final int ch = i;
            chanChips[i] = Ui.chip(this, CHAN[i]);
            chanChips[i].setTextSize(12);
            chanChips[i].setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            chanChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chan = ch;
                    showChannel();
                }
            });
            cr.addView(chanChips[i], Ui.lp(this, Ui.WRAP, Ui.dp(this, 38), i == 0 ? 0 : 6, 0));
        }
        hs.addView(cr);
        cc.addView(hs);
        chart = new ChartView(this);
        cc.addView(chart, Ui.lp(this, Ui.MATCH, Ui.dp(this, 200), 0, 10));
        cursorTv = Ui.text(this, "Tocca il grafico per scorrere la sessione.", 12, Ui.DIM, false);
        cursorTv.setPadding(0, Ui.dp(this, 8), 0, 0);
        cc.addView(cursorTv);
        root.addView(cc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        chart.listener = new ChartView.Listener() {
            @Override
            public void onCursor(int i) {
                moveCursor(i);
            }
        };
        map.listener = new TileMapView.Listener() {
            @Override
            public void onPick(int i) {
                chart.setCursor(i);
                moveCursor(i);
            }
        };
        showChannel();

        // ---- G-G
        LinearLayout gc = Ui.card(this);
        gc.addView(Ui.section(this, "Diagramma G-G"));
        GGView gg = new GGView(this);
        gg.showMax = false;
        gg.setPoints(s.aLong, s.aLat, 1);
        gc.addView(gg, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 260)));
        TextView gn = Ui.text(this, "Ogni punto e' la forza sentita dal pilota: in alto la frenata, in basso "
                + "l'accelerazione, ai lati le curve. Piu' il cerchio e' pieno, piu' sfrutti l'aderenza.", 11,
                Ui.DIM, false);
        gn.setPadding(0, Ui.dp(this, 8), 0, 0);
        gc.addView(gn);
        root.addView(gc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        // ---- giri
        List<Double> laps = s.lapTimes();
        if (!laps.isEmpty()) {
            LinearLayout lc = Ui.card(this);
            lc.addView(Ui.section(this, "Giri"));
            double best = Double.MAX_VALUE;
            for (double t : laps) {
                best = Math.min(best, t);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < laps.size(); i++) {
                sb.append("Giro ").append(i + 1).append(":  ").append(SessionData.fmtTime(laps.get(i)))
                        .append(laps.get(i) == best ? "   ★ migliore" : "").append("\n");
            }
            lc.addView(Ui.text(this, sb.toString().trim(), 15, Ui.TEXT, false));
            root.addView(lc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }

        // ---- esporta
        LinearLayout ec = Ui.card(this);
        ec.addView(Ui.section(this, "Esporta"));
        LinearLayout er = new LinearLayout(this);
        er.addView(Ui.btn(this, "Salva GPX", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(true, false);
            }
        }), Ui.lp(this, 0, Ui.dp(this, 44), 0, 0, 1f));
        er.addView(Ui.btn(this, "Condividi GPX", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(true, true);
            }
        }), Ui.lp(this, 0, Ui.dp(this, 44), 8, 0, 1f));
        ec.addView(er);
        ec.addView(Ui.btn(this, "Salva i dati grezzi (CSV)", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(false, false);
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 44), 0, 8));
        root.addView(ec, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        addDelete();
    }

    private void addDelete() {
        root.addView(Ui.btn(this, "Elimina questa sessione", Ui.CARD2, Ui.DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                finish();
            }
        }), Ui.lp(this, Ui.MATCH, Ui.dp(this, 46), 0, 12));
    }

    private void styleLayers() {
        for (int i = 0; i < 3; i++) {
            Ui.styleChip(this, layerChips[i], map.layer == i);
        }
    }

    private void showChannel() {
        double[] y;
        String unit;
        int color;
        switch (chan) {
            case 1:
                y = s.aLong;
                unit = "G";
                color = 0xFFFFB74D;
                break;
            case 2:
                y = s.aLat;
                unit = "G";
                color = 0xFFBA68C8;
                break;
            case 3:
                y = s.lean;
                unit = "° (+ sinistra)";
                color = 0xFF66BB6A;
                break;
            case 4:
                y = s.alt;
                unit = "m";
                color = 0xFF90A4AE;
                break;
            default:
                y = new double[s.n];
                for (int i = 0; i < s.n; i++) {
                    y[i] = s.v[i] * 3.6;
                }
                unit = "km/h";
                color = 0xFF4DD0E1;
                break;
        }
        for (int i = 0; i < CHAN.length; i++) {
            Ui.styleChip(this, chanChips[i], chan == i);
        }
        chart.setData(s.t, y, unit, color);
    }

    private void moveCursor(int i) {
        map.setCursor(i, s.lat[i], s.lon[i]);
        cursorTv.setText(String.format(Locale.ITALY, "%s · %.0f km/h · accel. %+.2f G · laterale %+.2f G · piega %+.0f° · "
                        + "quota %.0f m", SessionData.fmtTime(s.t[i] - s.t[0]), s.v[i] * 3.6, s.aLong[i], s.aLat[i],
                s.lean[i], s.alt[i]));
        cursorTv.setTextColor(Ui.TEXT);
    }

    private void openMaps() {
        StringBuilder sb = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving");
        sb.append(String.format(Locale.US, "&origin=%.6f,%.6f&destination=%.6f,%.6f", s.lat[0], s.lon[0],
                s.lat[s.n - 1], s.lon[s.n - 1]));
        int wp = 8;
        StringBuilder w = new StringBuilder();
        for (int k = 1; k <= wp; k++) {
            int i = (int) ((long) s.n * k / (wp + 1));
            if (w.length() > 0) {
                w.append("%7C");
            }
            w.append(String.format(Locale.US, "%.6f,%.6f", s.lat[i], s.lon[i]));
        }
        sb.append("&waypoints=").append(w);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString())));
        } catch (Exception e) {
            Toast.makeText(this, "Nessuna app per aprire Google Maps.", Toast.LENGTH_LONG).show();
        }
    }

    private void export(final boolean gpx, final boolean share) {
        final String base = FileUtil.baseName(s.name.isEmpty() ? "sessione" : s.name).replace(' ', '_');
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String name = base + (gpx ? ".gpx" : ".csv");
                    final String mime = gpx ? "application/gpx+xml" : "text/csv";
                    Uri u = FileUtil.save(SessionViewActivity.this, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            "Download/MamoTools", name, mime, new FileUtil.Writer() {
                                @Override
                                public void write(OutputStream os) throws IOException {
                                    if (gpx) {
                                        os.write(s.gpx().getBytes(StandardCharsets.UTF_8));
                                    } else {
                                        java.io.FileInputStream in = new java.io.FileInputStream(file);
                                        byte[] buf = new byte[32768];
                                        int r;
                                        while ((r = in.read(buf)) > 0) {
                                            os.write(buf, 0, r);
                                        }
                                        in.close();
                                    }
                                }
                            });
                    final Uri fu = u;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(SessionViewActivity.this, "Salvato in Download/MamoTools/" + name,
                                    Toast.LENGTH_LONG).show();
                            if (share) {
                                Intent i = new Intent(Intent.ACTION_SEND);
                                i.setType(mime);
                                i.putExtra(Intent.EXTRA_STREAM, fu);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(i, "Condividi"));
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(SessionViewActivity.this, "Esportazione non riuscita: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (map != null) {
            map.shutdown();
        }
    }
}
