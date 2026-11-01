package it.mamotools;

import android.app.ActivityManager;
import android.content.Context;
import android.net.TrafficStats;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;

/** Campiona CPU per core, RAM e rete per i widget e ne conserva lo storico (1 campione/s). */
final class MonitorSampler {

    static final int HIST = 90;
    private static final MonitorSampler INSTANCE = new MonitorSampler();

    static MonitorSampler get() {
        return INSTANCE;
    }

    final int cores;
    final float[][] cpu;
    final float[] ram = new float[HIST];
    final float[] swap = new float[HIST];
    final float[] dl = new float[HIST];
    final float[] ul = new float[HIST];
    long ramUsed, ramTotal;
    int count = 0;

    private long[] prevTotal, prevIdle;
    private boolean statOk = false;
    private long lastRx = -1, lastTx = -1, lastT = 0;
    private final long[] maxFreq;

    private MonitorSampler() {
        int n = 0;
        String[] l = new java.io.File("/sys/devices/system/cpu").list();
        if (l != null) {
            for (String s : l) {
                if (s.matches("cpu\\d+")) {
                    n++;
                }
            }
        }
        cores = Math.max(1, Math.max(n, Runtime.getRuntime().availableProcessors()));
        cpu = new float[cores][HIST];
        maxFreq = new long[cores];
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
        try {
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void shift(float[] a, float v) {
        System.arraycopy(a, 1, a, 0, a.length - 1);
        a[a.length - 1] = v;
    }

    /** Un campione. Da chiamare ~ogni secondo. */
    synchronized void sample(Context ctx) {
        float[] usage = new float[cores];
        boolean got = false;
        String st = read("/proc/stat");
        if (st != null) {
            long[] tot = new long[cores];
            long[] idl = new long[cores];
            int found = 0;
            for (String line : st.split("\n")) {
                if (line.startsWith("cpu") && line.length() > 3 && Character.isDigit(line.charAt(3))) {
                    try {
                        String[] f = line.split("\\s+");
                        int id = Integer.parseInt(f[0].substring(3));
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
                    } catch (Exception ignored) {
                    }
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
            }
        }
        if (!got) {
            statOk = false;
            for (int i = 0; i < cores; i++) {
                String b = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
                long cur = readLong(b + "scaling_cur_freq");
                if (maxFreq[i] <= 0) {
                    maxFreq[i] = readLong(b + "cpuinfo_max_freq");
                }
                usage[i] = (cur > 0 && maxFreq[i] > 0) ? Math.min(100f, 100f * cur / maxFreq[i]) : 0f;
            }
        }
        for (int i = 0; i < cores; i++) {
            shift(cpu[i], usage[i]);
        }

        long total = 0, avail = 0, swapT = 0, swapF = 0;
        String mem = read("/proc/meminfo");
        if (mem != null) {
            for (String line : mem.split("\n")) {
                String[] f = line.split("\\s+");
                if (f.length < 2) {
                    continue;
                }
                try {
                    long kb = Long.parseLong(f[1]) * 1024;
                    switch (f[0]) {
                        case "MemTotal:": total = kb; break;
                        case "MemAvailable:": avail = kb; break;
                        case "SwapTotal:": swapT = kb; break;
                        case "SwapFree:": swapF = kb; break;
                        default: break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (total <= 0) {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            total = mi.totalMem;
            avail = mi.availMem;
        }
        ramTotal = total;
        ramUsed = total - avail;
        shift(ram, total > 0 ? 100f * ramUsed / total : 0f);
        shift(swap, swapT > 0 ? 100f * (swapT - swapF) / swapT : 0f);

        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        float d = 0, u = 0;
        if (lastRx >= 0 && rx >= 0 && now > lastT) {
            float dt = (now - lastT) / 1000f;
            d = Math.max(0, (rx - lastRx) / dt);
            u = Math.max(0, (tx - lastTx) / dt);
        }
        lastRx = rx;
        lastTx = tx;
        lastT = now;
        shift(dl, d);
        shift(ul, u);
        count = Math.min(HIST, count + 1);
    }

    synchronized void resetNet() {
        lastRx = -1;
        prevTotal = null;
        statOk = false;
    }
}
