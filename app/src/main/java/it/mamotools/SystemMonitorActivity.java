package it.mamotools;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** Monitor di sistema: grafici in tempo reale (CPU per core, RAM, rete) e schede informative. */
public class SystemMonitorActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int HIST = 60;

    private static final int[] PALETTE = {
            0xFFE95420, 0xFF4DD0E1, 0xFFFFD60A, 0xFF8BC34A, 0xFFBA68C8, 0xFFFF6B6B,
            0xFF64B5F6, 0xFFFFB74D, 0xFF4DB6AC, 0xFFF06292, 0xFFAED581, 0xFF9575CD,
            0xFFFFF176, 0xFF4FC3F7, 0xFFA1887F, 0xFF90A4AE};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tickCount = 0;
    private boolean running = false;

    // CPU
    private int cores;
    private Graph cpuGraph;
    private TextView[] coreViews;
    private TextView cpuSummary, cpuStatic;
    private long[] prevTotal, prevIdle;
    private boolean statOk = false;
    private String cpuMode = "";

    // RAM
    private Graph ramGraph;
    private TextView ramText;

    // rete
    private Graph netGraph;
    private TextView netText;
    private long lastRx = -1, lastTx = -1, lastT = 0;

    // altre schede
    private TextView storageText, batteryText, deviceText, displayText, sensorsText, thermalText,
            gpuText, systemText;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                tick();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cores = countCores();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(40), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        root.addView(text("System Monitor", 28, R.color.text, true));
        TextView sub = text("Grafici in tempo reale e tutte le informazioni del telefono.",
                14, R.color.text_dim, false);
        sub.setPadding(0, dp(6), 0, dp(8));
        root.addView(sub);

        // ---- CPU
        LinearLayout cpu = card(root, "CPU");
        cpuSummary = text("", 14, R.color.text, true);
        cpu.addView(cpuSummary);
        cpuGraph = new Graph(this, cores, 100f, v -> String.format(Locale.US, "%.0f%%", v));
        cpuGraph.setColors(PALETTE);
        cpu.addView(cpuGraph, new LinearLayout.LayoutParams(MATCH, dp(180)));
        coreViews = new TextView[cores];
        LinearLayout row = null;
        for (int i = 0; i < cores; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                cpu.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            TextView t = text("", 12, R.color.text, false);
            t.setPadding(0, dp(4), 0, 0);
            coreViews[i] = t;
            row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        cpuStatic = text("", 12, R.color.text_dim, false);
        cpuStatic.setPadding(0, dp(12), 0, 0);
        cpu.addView(cpuStatic);

        // ---- RAM
        LinearLayout ram = card(root, "Memoria RAM");
        ramGraph = new Graph(this, 2, 100f, v -> String.format(Locale.US, "%.0f%%", v));
        ramGraph.setColors(new int[]{0xFF4DD0E1, 0xFFFF6B6B});
        ram.addView(ramGraph, new LinearLayout.LayoutParams(MATCH, dp(130)));
        ram.addView(legend("● RAM usata", 0xFF4DD0E1, "● Swap usato", 0xFFFF6B6B));
        ramText = text("", 12, R.color.text, false);
        ramText.setPadding(0, dp(8), 0, 0);
        ram.addView(ramText);

        // ---- rete
        LinearLayout net = card(root, "Rete e traffico internet");
        netGraph = new Graph(this, 2, 0f, v -> rate(v));
        netGraph.setColors(new int[]{0xFF8BC34A, 0xFFFFB74D});
        net.addView(netGraph, new LinearLayout.LayoutParams(MATCH, dp(130)));
        net.addView(legend("● Download", 0xFF8BC34A, "● Upload", 0xFFFFB74D));
        netText = text("", 12, R.color.text, false);
        netText.setPadding(0, dp(8), 0, 0);
        net.addView(netText);

        // ---- altre
        storageText = info(root, "Archiviazione e microSD");
        batteryText = info(root, "Batteria");
        thermalText = info(root, "Temperature");
        gpuText = info(root, "Grafica (GPU)");
        displayText = info(root, "Schermo");
        deviceText = info(root, "Dispositivo");
        systemText = info(root, "Sistema e funzioni");
        sensorsText = info(root, "Sensori");

        setContentView(scroll);
        fillStatic();
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = text(title, 16, R.color.accent, true);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(14);
        root.addView(c, lp);
        return c;
    }

    private TextView info(LinearLayout root, String title) {
        LinearLayout c = card(root, title);
        TextView t = text("", 12, R.color.text, false);
        t.setLineSpacing(dp(2), 1f);
        c.addView(t);
        return t;
    }

    private View legend(String a, int ca, String b, int cb) {
        LinearLayout l = new LinearLayout(this);
        TextView x = text(a, 12, R.color.text_dim, false);
        x.setTextColor(ca);
        TextView y = text(b, 12, R.color.text_dim, false);
        y.setTextColor(cb);
        y.setPadding(dp(16), 0, 0, 0);
        l.addView(x);
        l.addView(y);
        l.setPadding(0, dp(6), 0, 0);
        return l;
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        lastRx = -1;
        handler.post(loop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(loop);
    }

    // ---------------------------------------------------------------- aggiornamento

    private void tick() {
        tickCount++;
        updateCpu();
        updateRam();
        updateNet();
        if (tickCount % 3 == 1) {
            updateBattery();
            updateThermal();
            updateGpu();
        }
        if (tickCount % 5 == 1) {
            updateStorage();
        }
    }

    // ---- CPU

    private int countCores() {
        int n = 0;
        String[] l = new File("/sys/devices/system/cpu").list();
        if (l != null) {
            for (String s : l) {
                if (s.matches("cpu\\d+")) {
                    n++;
                }
            }
        }
        return Math.max(n, Runtime.getRuntime().availableProcessors());
    }

    private static String read(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path), 8192)) {
            StringBuilder sb = new StringBuilder();
            String line;
            int guard = 0;
            while ((line = r.readLine()) != null && guard++ < 2000) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long readLong(String path) {
        String s = read(path);
        if (s == null) {
            return -1;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void updateCpu() {
        float[] usage = new float[cores];
        long[] cur = new long[cores];
        long[] max = new long[cores];
        boolean[] on = new boolean[cores];
        for (int i = 0; i < cores; i++) {
            String b = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            cur[i] = readLong(b + "scaling_cur_freq");
            max[i] = readLong(b + "cpuinfo_max_freq");
            on[i] = cur[i] > 0;
        }

        // carico reale da /proc/stat (spesso bloccato da Android 8 in poi)
        boolean got = false;
        String st = read("/proc/stat");
        if (st != null) {
            long[] tot = new long[cores];
            long[] idl = new long[cores];
            int found = 0;
            for (String line : st.split("\n")) {
                if (line.startsWith("cpu") && line.length() > 3 && Character.isDigit(line.charAt(3))) {
                    String[] f = line.split("\\s+");
                    int id;
                    try {
                        id = Integer.parseInt(f[0].substring(3));
                    } catch (Exception e) {
                        continue;
                    }
                    if (id >= cores || f.length < 8) {
                        continue;
                    }
                    long t = 0;
                    for (int k = 1; k < Math.min(f.length, 9); k++) {
                        t += Long.parseLong(f[k]);
                    }
                    tot[id] = t;
                    idl[id] = Long.parseLong(f[4]) + Long.parseLong(f[5]);
                    found++;
                }
            }
            if (found > 0) {
                if (prevTotal != null && statOk) {
                    for (int i = 0; i < cores; i++) {
                        long dt = tot[i] - prevTotal[i];
                        long di = idl[i] - prevIdle[i];
                        usage[i] = dt > 0 ? Math.max(0f, Math.min(100f, 100f * (dt - di) / dt)) : 0f;
                    }
                }
                prevTotal = tot;
                prevIdle = idl;
                statOk = true;
                got = true;
                cpuMode = "carico reale (/proc/stat)";
            }
        }
        if (!got) {
            statOk = false;
            cpuMode = "stima dalla frequenza (Android blocca la lettura del carico)";
            for (int i = 0; i < cores; i++) {
                usage[i] = (on[i] && max[i] > 0) ? Math.min(100f, 100f * cur[i] / max[i]) : 0f;
            }
        }

        cpuGraph.push(usage);
        float sum = 0;
        int online = 0;
        for (int i = 0; i < cores; i++) {
            sum += usage[i];
            if (on[i] || usage[i] > 0) {
                online++;
            }
            String f = cur[i] > 0 ? String.format(Locale.US, "%.0f MHz", cur[i] / 1000f)
                    : "n/d";
            coreViews[i].setText(String.format(Locale.US, "● CPU%d  %.0f%%  %s", i, usage[i], f));
            coreViews[i].setTextColor(PALETTE[i % PALETTE.length]);
        }
        cpuSummary.setText(String.format(Locale.US, "Totale %.0f%%  ·  %d core  ·  %s",
                sum / Math.max(1, cores), cores, cpuMode));
    }

    // ---- RAM

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long total = mi.totalMem;
        long avail = mi.availMem;
        long swapT = 0, swapF = 0, cached = 0, buffers = 0, active = 0, inactive = 0, shmem = 0,
                slab = 0, dirty = 0, mapped = 0, commit = 0;
        String mem = read("/proc/meminfo");
        if (mem != null) {
            for (String line : mem.split("\n")) {
                String[] f = line.split("\\s+");
                if (f.length < 2) {
                    continue;
                }
                long kb;
                try {
                    kb = Long.parseLong(f[1]) * 1024;
                } catch (Exception e) {
                    continue;
                }
                switch (f[0]) {
                    case "MemTotal:": total = kb; break;
                    case "MemAvailable:": avail = kb; break;
                    case "SwapTotal:": swapT = kb; break;
                    case "SwapFree:": swapF = kb; break;
                    case "Cached:": cached = kb; break;
                    case "Buffers:": buffers = kb; break;
                    case "Active:": active = kb; break;
                    case "Inactive:": inactive = kb; break;
                    case "Shmem:": shmem = kb; break;
                    case "Slab:": slab = kb; break;
                    case "Dirty:": dirty = kb; break;
                    case "Mapped:": mapped = kb; break;
                    case "Committed_AS:": commit = kb; break;
                    default: break;
                }
            }
        }
        long used = total - avail;
        float pct = total > 0 ? 100f * used / total : 0f;
        float spct = swapT > 0 ? 100f * (swapT - swapF) / swapT : 0f;
        ramGraph.push(new float[]{pct, spct});
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "In uso: %s di %s (%.0f%%)\n", size(used), size(total), pct));
        sb.append("Disponibile: ").append(size(avail)).append('\n');
        sb.append("Cache: ").append(size(cached)).append("  ·  Buffer: ").append(size(buffers)).append('\n');
        sb.append("Attiva: ").append(size(active)).append("  ·  Inattiva: ").append(size(inactive)).append('\n');
        sb.append("Condivisa: ").append(size(shmem)).append("  ·  Mappata: ").append(size(mapped)).append('\n');
        sb.append("Slab kernel: ").append(size(slab)).append("  ·  Dirty: ").append(size(dirty)).append('\n');
        sb.append("Committed: ").append(size(commit)).append('\n');
        sb.append(swapT > 0 ? "Swap/zRAM: " + size(swapT - swapF) + " di " + size(swapT)
                : "Swap/zRAM: non presente").append('\n');
        sb.append("Soglia memoria bassa: ").append(size(mi.threshold))
                .append(mi.lowMemory ? "  (MEMORIA BASSA!)" : "").append('\n');
        sb.append("Heap per app: ").append(am.getMemoryClass()).append(" MB (")
                .append(am.getLargeMemoryClass()).append(" MB con largeHeap)\n");
        Runtime rt = Runtime.getRuntime();
        sb.append("Heap di Mamo Tools: ").append(size(rt.totalMemory() - rt.freeMemory()))
                .append(" / ").append(size(rt.maxMemory()));
        ramText.setText(sb.toString());
    }

    // ---- rete

    private void updateNet() {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        float dl = 0, ul = 0;
        if (lastRx >= 0 && rx >= 0 && now > lastT) {
            float dt = (now - lastT) / 1000f;
            dl = Math.max(0, (rx - lastRx) / dt);
            ul = Math.max(0, (tx - lastTx) / dt);
        }
        lastRx = rx;
        lastTx = tx;
        lastT = now;
        netGraph.push(new float[]{dl, ul});

        long mrx = TrafficStats.getMobileRxBytes();
        long mtx = TrafficStats.getMobileTxBytes();
        StringBuilder sb = new StringBuilder();
        sb.append("Download: ").append(rate(dl)).append("   ·   Upload: ").append(rate(ul)).append('\n');
        sb.append("Totale dall'avvio: ↓ ").append(size(rx)).append("  ↑ ").append(size(tx)).append('\n');
        if (mrx >= 0) {
            sb.append("Dati mobili: ↓ ").append(size(mrx)).append("  ↑ ").append(size(mtx)).append('\n');
            sb.append("Wi-Fi/altro: ↓ ").append(size(Math.max(0, rx - mrx)))
                    .append("  ↑ ").append(size(Math.max(0, tx - mtx))).append('\n');
        }
        sb.append("Pacchetti: ↓ ").append(TrafficStats.getTotalRxPackets())
                .append("  ↑ ").append(TrafficStats.getTotalTxPackets()).append('\n');
        sb.append(networkDetails());
        netText.setText(sb.toString().trim());
    }

    private String networkDetails() {
        StringBuilder sb = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) {
                sb.append("Connessione: nessuna\n");
            } else {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null) {
                    List<String> tr = new ArrayList<>();
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) tr.add("Wi-Fi");
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) tr.add("Dati mobili");
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) tr.add("Ethernet");
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) tr.add("Bluetooth");
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) tr.add("VPN");
                    sb.append("Connessione: ").append(tr.isEmpty() ? "altra" : join(tr, " + "));
                    sb.append(nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            ? "  (internet ok)\n" : "  (internet non verificato)\n");
                    sb.append("Banda stimata: ↓ ").append(nc.getLinkDownstreamBandwidthKbps() / 1000)
                            .append(" Mbit/s  ↑ ").append(nc.getLinkUpstreamBandwidthKbps() / 1000)
                            .append(" Mbit/s\n");
                    sb.append(cm.isActiveNetworkMetered() ? "Rete a consumo (metered)\n"
                            : "Rete non a consumo\n");
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        WifiManager wm = (WifiManager) getApplicationContext()
                                .getSystemService(WIFI_SERVICE);
                        WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                        if (wi != null) {
                            sb.append("Wi-Fi: ").append(wi.getLinkSpeed()).append(" Mbit/s, ")
                                    .append(wi.getFrequency()).append(" MHz, segnale ")
                                    .append(wi.getRssi()).append(" dBm\n");
                        }
                    }
                }
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    sb.append("Interfaccia: ").append(lp.getInterfaceName()).append("  MTU ")
                            .append(Build.VERSION.SDK_INT >= 29 ? lp.getMtu() : 0).append('\n');
                    List<String> dns = new ArrayList<>();
                    for (InetAddress a : lp.getDnsServers()) {
                        dns.add(a.getHostAddress());
                    }
                    sb.append("DNS: ").append(join(dns, ", ")).append('\n');
                }
            }
            sb.append("IP: ").append(ipList()).append('\n');
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null && tm.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE) {
                sb.append("Operatore: ").append(tm.getNetworkOperatorName())
                        .append("  ·  SIM: ").append(tm.getSimOperatorName())
                        .append(tm.isNetworkRoaming() ? "  ·  ROAMING" : "").append('\n');
            }
        } catch (Throwable t) {
            sb.append("Dettagli di rete non disponibili\n");
        }
        return sb.toString();
    }

    private String ipList() {
        List<String> l = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> ads = ni.getInetAddresses();
                while (ads.hasMoreElements()) {
                    InetAddress a = ads.nextElement();
                    String h = a.getHostAddress();
                    int pct = h.indexOf('%');
                    if (pct > 0) {
                        h = h.substring(0, pct);
                    }
                    if (!a.isLinkLocalAddress()) {
                        l.add(ni.getName() + " " + h);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return l.isEmpty() ? "nessuno" : join(l, ", ");
    }

    // ---- archiviazione

    private void updateStorage() {
        StringBuilder sb = new StringBuilder();
        sb.append(vol("Memoria interna (dati)", Environment.getDataDirectory()));
        sb.append(vol("Sistema (root)", Environment.getRootDirectory()));
        try {
            StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
            List<StorageVolume> vols = sm.getStorageVolumes();
            for (StorageVolume v : vols) {
                File dir = Build.VERSION.SDK_INT >= 30 ? v.getDirectory() : null;
                if (dir == null && v.isPrimary()) {
                    dir = Environment.getExternalStorageDirectory();
                }
                String name = v.getDescription(this)
                        + (v.isRemovable() ? " (rimovibile / microSD)" : " (interna)")
                        + " [" + v.getState() + "]";
                if (dir != null && "mounted".equals(v.getState())) {
                    sb.append(vol(name, dir));
                } else {
                    sb.append(name).append("\n\n");
                }
            }
        } catch (Throwable t) {
            sb.append("Volumi non leggibili\n");
        }
        File[] ext = getExternalFilesDirs(null);
        int removable = 0;
        for (File f : ext) {
            if (f != null && Environment.isExternalStorageRemovable(f)) {
                removable++;
            }
        }
        sb.append("MicroSD/memorie rimovibili rilevate: ").append(removable);
        storageText.setText(sb.toString());
    }

    private String vol(String name, File dir) {
        try {
            StatFs s = new StatFs(dir.getPath());
            long total = s.getTotalBytes();
            long free = s.getAvailableBytes();
            long used = total - s.getFreeBytes();
            return String.format(Locale.US, "%s\n  %s\n  Usato %s di %s (%.0f%%)  ·  libero %s\n\n",
                    name, dir.getPath(), size(used), size(total),
                    total > 0 ? 100.0 * used / total : 0, size(free));
        } catch (Throwable t) {
            return name + "\n  non leggibile\n\n";
        }
    }

    // ---- batteria

    private void updateBattery() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        StringBuilder sb = new StringBuilder();
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            int plug = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            sb.append(String.format(Locale.US, "Carica: %d%%\n", level * 100 / Math.max(1, scale)));
            sb.append("Stato: ").append(statusName(status)).append('\n');
            sb.append("Alimentazione: ").append(plug == 0 ? "a batteria"
                    : plug == BatteryManager.BATTERY_PLUGGED_AC ? "caricatore"
                    : plug == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                    : plug == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : "altro").append('\n');
            sb.append("Salute: ").append(healthName(health)).append('\n');
            sb.append(String.format(Locale.US, "Tensione: %.2f V\n",
                    b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f));
            sb.append(String.format(Locale.US, "Temperatura: %.1f °C\n",
                    b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f));
            String tech = b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            sb.append("Tecnologia: ").append(tech == null ? "n/d" : tech).append('\n');
        }
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null) {
            long cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            long cnt = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (cur != Long.MIN_VALUE && cur != 0) {
                sb.append("Corrente: ").append(cur / 1000).append(" mA\n");
            }
            if (cnt > 0) {
                sb.append("Carica residua: ").append(cnt / 1000).append(" mAh\n");
            }
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            sb.append("Risparmio energetico: ").append(pm.isPowerSaveMode() ? "attivo" : "no").append('\n');
        }
        batteryText.setText(sb.toString().trim());
    }

    private static String statusName(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "in carica";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "in scarica";
            case BatteryManager.BATTERY_STATUS_FULL: return "carica completa";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "non in carica";
            default: return "sconosciuto";
        }
    }

    private static String healthName(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "buona";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "surriscaldata";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "esaurita";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "sovratensione";
            case BatteryManager.BATTERY_HEALTH_COLD: return "fredda";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "guasto";
            default: return "sconosciuta";
        }
    }

    // ---- temperature / GPU

    private void updateThermal() {
        StringBuilder sb = new StringBuilder();
        File[] zones = new File("/sys/class/thermal").listFiles();
        int shown = 0;
        if (zones != null) {
            List<File> zs = new ArrayList<>();
            for (File z : zones) {
                if (z.getName().startsWith("thermal_zone")) {
                    zs.add(z);
                }
            }
            Collections.sort(zs);
            for (File z : zs) {
                long t = readLong(new File(z, "temp").getPath());
                String type = read(new File(z, "type").getPath());
                if (t == -1 || type == null) {
                    continue;
                }
                float c = Math.abs(t) > 1000 ? t / 1000f : t;
                if (c < -20 || c > 150) {
                    continue;
                }
                sb.append(String.format(Locale.US, "%s: %.1f °C\n", type.trim(), c));
                shown++;
            }
        }
        if (shown == 0) {
            sb.append("Sensori termici del kernel non accessibili (bloccati da Android).\n");
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && Build.VERSION.SDK_INT >= 29) {
            int ts = pm.getCurrentThermalStatus();
            String[] names = {"nessuno", "leggero", "moderato", "severo", "critico",
                    "emergenza", "spegnimento"};
            sb.append("Stato termico del sistema: ")
                    .append(ts >= 0 && ts < names.length ? names[ts] : "?");
        }
        thermalText.setText(sb.toString().trim());
    }

    private void updateGpu() {
        StringBuilder sb = new StringBuilder();
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ConfigurationInfo ci = am.getDeviceConfigurationInfo();
        sb.append("OpenGL ES: ").append(ci.getGlEsVersion()).append('\n');
        String[][] probes = {
                {"/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "Carico GPU (Adreno)", "%"},
                {"/sys/class/kgsl/kgsl-3d0/gpuclk", "Frequenza GPU (Adreno)", "Hz"},
                {"/sys/class/misc/mali0/device/utilisation", "Carico GPU (Mali)", "%"},
                {"/sys/class/misc/mali0/device/utilization", "Carico GPU (Mali)", "%"},
                {"/sys/devices/platform/gpusysfs/gpu_busy", "Carico GPU", "%"}};
        int found = 0;
        for (String[] p : probes) {
            String v = read(p[0]);
            if (v != null && !v.trim().isEmpty()) {
                sb.append(p[1]).append(": ").append(v.trim().split("\\s+")[0]).append(' ')
                        .append(p[2]).append('\n');
                found++;
            }
        }
        if (found == 0) {
            sb.append("Carico e frequenza della GPU non accessibili su questo telefono.\n");
        }
        gpuText.setText(sb.toString().trim());
    }

    // ---------------------------------------------------------------- informazioni statiche

    private void fillStatic() {
        // CPU
        StringBuilder c = new StringBuilder();
        String info = read("/proc/cpuinfo");
        String hw = null, model = null, feat = null, arch = null, part = null;
        if (info != null) {
            for (String line : info.split("\n")) {
                int i = line.indexOf(':');
                if (i < 0) {
                    continue;
                }
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if (k.equals("Hardware")) hw = v;
                else if (k.equals("model name") && model == null) model = v;
                else if (k.equals("Features") && feat == null) feat = v;
                else if (k.equals("CPU architecture") && arch == null) arch = v;
                else if (k.equals("CPU part")) part = (part == null ? "" : part + " ") + v;
            }
        }
        c.append("Piattaforma: ").append(Build.HARDWARE).append(hw != null ? " (" + hw + ")" : "").append('\n');
        if (Build.VERSION.SDK_INT >= 31) {
            c.append("SoC: ").append(socName()).append('\n');
        }
        if (model != null) c.append("Modello: ").append(model).append('\n');
        if (arch != null) c.append("Architettura ARM: ").append(arch).append('\n');
        c.append("ABI supportate: ").append(join(java.util.Arrays.asList(Build.SUPPORTED_ABIS), ", ")).append('\n');
        c.append("Core: ").append(cores).append('\n');
        for (int i = 0; i < cores; i++) {
            String b = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            long mn = readLong(b + "cpuinfo_min_freq");
            long mx = readLong(b + "cpuinfo_max_freq");
            String gov = read(b + "scaling_governor");
            if (mx > 0) {
                c.append(String.format(Locale.US, "  CPU%d: %d–%d MHz  %s\n", i, mn / 1000, mx / 1000,
                        gov == null ? "" : gov.trim()));
            }
        }
        if (feat != null) c.append("Funzioni: ").append(feat).append('\n');
        cpuStatic.setText(c.toString().trim());

        // schermo
        StringBuilder d = new StringBuilder();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Display disp = getWindowManager().getDefaultDisplay();
        DisplayMetrics real = new DisplayMetrics();
        disp.getRealMetrics(real);
        d.append("Risoluzione: ").append(real.widthPixels).append(" x ").append(real.heightPixels).append(" px\n");
        d.append("Densita': ").append(dm.densityDpi).append(" dpi (x").append(dm.density).append(")\n");
        d.append(String.format(Locale.US, "DPI fisici: %.0f x %.0f\n", real.xdpi, real.ydpi));
        d.append(String.format(Locale.US, "Diagonale: %.1f pollici\n",
                Math.hypot(real.widthPixels / real.xdpi, real.heightPixels / real.ydpi)));
        d.append(String.format(Locale.US, "Frequenza di aggiornamento: %.0f Hz\n", disp.getRefreshRate()));
        Display.Mode[] modes = disp.getSupportedModes();
        List<String> ms = new ArrayList<>();
        for (Display.Mode m : modes) {
            ms.add(m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + "@" + Math.round(m.getRefreshRate()));
        }
        d.append("Modalita': ").append(join(ms, ", ")).append('\n');
        d.append("HDR: ").append(disp.isHdr() ? "si" : "no").append("  ·  Colore ampio: ")
                .append(disp.isWideColorGamut() ? "si" : "no");
        displayText.setText(d.toString());

        // dispositivo
        StringBuilder v = new StringBuilder();
        v.append("Produttore: ").append(Build.MANUFACTURER).append('\n');
        v.append("Marca: ").append(Build.BRAND).append('\n');
        v.append("Modello: ").append(Build.MODEL).append('\n');
        v.append("Dispositivo: ").append(Build.DEVICE).append("  ·  Prodotto: ").append(Build.PRODUCT).append('\n');
        v.append("Scheda: ").append(Build.BOARD).append('\n');
        v.append("Bootloader: ").append(Build.BOOTLOADER).append('\n');
        v.append("Radio/modem: ").append(Build.getRadioVersion()).append('\n');
        v.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append(")\n");
        v.append("Patch di sicurezza: ").append(Build.VERSION.SECURITY_PATCH).append('\n');
        v.append("Build: ").append(Build.DISPLAY).append('\n');
        v.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n');
        v.append("Tipo build: ").append(Build.TYPE).append("  ·  Tag: ").append(Build.TAGS).append('\n');
        v.append("Kernel: ").append(System.getProperty("os.version")).append('\n');
        v.append("Architettura: ").append(System.getProperty("os.arch")).append('\n');
        v.append("Lingua: ").append(Locale.getDefault().toString());
        deviceText.setText(v.toString());

        // sensori
        StringBuilder s = new StringBuilder();
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        s.append(all.size()).append(" sensori\n");
        for (Sensor x : all) {
            s.append("• ").append(x.getName()).append(" (").append(x.getVendor()).append(")\n");
        }
        sensorsText.setText(s.toString().trim());

        updateSystemText();
    }

    private void updateSystemText() {
        StringBuilder s = new StringBuilder();
        PackageManager pm = getPackageManager();
        String[][] feats = {
                {PackageManager.FEATURE_BLUETOOTH, "Bluetooth"},
                {PackageManager.FEATURE_BLUETOOTH_LE, "Bluetooth LE"},
                {PackageManager.FEATURE_WIFI, "Wi-Fi"},
                {PackageManager.FEATURE_WIFI_DIRECT, "Wi-Fi Direct"},
                {PackageManager.FEATURE_NFC, "NFC"},
                {PackageManager.FEATURE_TELEPHONY, "Telefonia"},
                {PackageManager.FEATURE_LOCATION_GPS, "GPS"},
                {PackageManager.FEATURE_FINGERPRINT, "Impronta digitale"},
                {PackageManager.FEATURE_CAMERA_FLASH, "Flash"},
                {PackageManager.FEATURE_CAMERA_AUTOFOCUS, "Autofocus"},
                {PackageManager.FEATURE_USB_HOST, "USB host (OTG)"},
                {PackageManager.FEATURE_USB_ACCESSORY, "USB accessory"},
                {PackageManager.FEATURE_CONSUMER_IR, "Infrarossi"},
                {PackageManager.FEATURE_SENSOR_BAROMETER, "Barometro"},
                {PackageManager.FEATURE_SENSOR_GYROSCOPE, "Giroscopio"},
                {PackageManager.FEATURE_SENSOR_COMPASS, "Bussola"},
                {PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, "Vulkan"},
                {PackageManager.FEATURE_AUDIO_LOW_LATENCY, "Audio a bassa latenza"},
                {PackageManager.FEATURE_AUDIO_PRO, "Audio professionale"},
                {PackageManager.FEATURE_MICROPHONE, "Microfono"},
                {PackageManager.FEATURE_TELEPHONY_EUICC, "eSIM"},
                {PackageManager.FEATURE_FACE, "Riconoscimento del volto"}};
        List<String> yes = new ArrayList<>();
        List<String> no = new ArrayList<>();
        for (String[] f : feats) {
            (pm.hasSystemFeature(f[0]) ? yes : no).add(f[1]);
        }
        s.append("Presenti: ").append(join(yes, ", ")).append('\n');
        s.append("Assenti: ").append(join(no, ", ")).append('\n');
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            s.append("Fotocamere: ").append(cm.getCameraIdList().length).append('\n');
        } catch (Throwable ignored) {
        }
        s.append("Impostazioni di sistema > Info per i dettagli IMEI/numeri di serie "
                + "(Android non li fornisce alle app).");
        systemText.setText(s.toString());
    }

    private static String socName() {
        try {
            return Build.class.getField("SOC_MANUFACTURER").get(null) + " "
                    + Build.class.getField("SOC_MODEL").get(null);
        } catch (Throwable t) {
            return "n/d";
        }
    }

    // ---------------------------------------------------------------- utilita'

    private static String size(long b) {
        if (b < 0) {
            return "n/d";
        }
        double v = b;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
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

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(colorRes));
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- grafico

    private interface Fmt {
        String f(float v);
    }

    /** Grafico a linee con storico scorrevole; fixedMax = 0 -> scala automatica. */
    private static final class Graph extends View {
        private final int n;
        private final float fixedMax;
        private final Fmt fmt;
        private final float[][] data;
        private int count = 0;
        private int[] colors = {0xFFFFFFFF};
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint();
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Graph(Context c, int series, float fixedMax, Fmt fmt) {
            super(c);
            this.n = series;
            this.fixedMax = fixedMax;
            this.fmt = fmt;
            data = new float[series][HIST];
            float d = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2f * d);
            line.setStrokeJoin(Paint.Join.ROUND);
            grid.setColor(0x33FFFFFF);
            grid.setStrokeWidth(1f);
            txt.setColor(0xFF9AA3B2);
            txt.setTextSize(10f * d);
            setBackgroundColor(0xFF0F1115);
        }

        void setColors(int[] c) {
            colors = c;
        }

        void push(float[] v) {
            for (int s = 0; s < n; s++) {
                System.arraycopy(data[s], 1, data[s], 0, HIST - 1);
                data[s][HIST - 1] = s < v.length ? v[s] : 0f;
            }
            count = Math.min(HIST, count + 1);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float pad = 6f * getResources().getDisplayMetrics().density;
            float max = fixedMax;
            if (max <= 0f) {
                float m = 0;
                for (float[] s : data) {
                    for (float x : s) {
                        m = Math.max(m, x);
                    }
                }
                max = Math.max(10f * 1024f, m * 1.2f);
            }
            for (int i = 0; i <= 4; i++) {
                float y = pad + (h - 2 * pad) * i / 4f;
                c.drawLine(0, y, w, y, grid);
            }
            c.drawText(fmt.f(max), pad, pad + txt.getTextSize(), txt);
            c.drawText(fmt.f(0), pad, h - pad - 2f, txt);
            float step = (w) / (HIST - 1);
            for (int s = 0; s < n; s++) {
                line.setColor(colors[s % colors.length]);
                path.reset();
                boolean first = true;
                for (int i = HIST - count; i < HIST; i++) {
                    float x = i * step;
                    float y = pad + (h - 2 * pad) * (1f - Math.min(1f, data[s][i] / max));
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                }
                c.drawPath(path, line);
            }
        }
    }
}
