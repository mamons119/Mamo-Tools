package it.mamotools;

import java.io.IOException;
import java.io.OutputStream;

/** Scrittura di WAV e FLAC a partire dal motore di Produzioni. Java puro. */
final class ProdFiles {

    interface Progress {
        void onProgress(int pct);
    }

    private ProdFiles() {
    }

    static final int BLOCK = 4096;

    /** Fa generare al motore esattamente frames fotogrammi e li passa a sink come 16 bit stereo interleaved. */
    interface Sink {
        void write(short[] interleaved, int frames) throws IOException;
    }

    static void pump(ProdEngine e, long frames, Progress p, Sink sink) throws IOException {
        float[] l = new float[BLOCK], r = new float[BLOCK];
        short[] out = new short[BLOCK * 2];
        long done = 0;
        int lastPct = -1;
        while (done < frames) {
            int n = (int) Math.min(BLOCK, frames - done);
            e.fill(l, r, n);
            for (int i = 0; i < n; i++) {
                out[2 * i] = toShort(l[i]);
                out[2 * i + 1] = toShort(r[i]);
            }
            sink.write(out, n);
            done += n;
            int pct = (int) (done * 100 / Math.max(1, frames));
            if (p != null && pct != lastPct) {
                lastPct = pct;
                p.onProgress(pct);
            }
        }
    }

    static short toShort(float x) {
        int v = Math.round(x * 32767f);
        return (short) Math.max(-32768, Math.min(32767, v));
    }

    // ------------------------------------------------------------------ WAV

    static void writeWav(ProdEngine e, long frames, OutputStream os, Progress p) throws IOException {
        long dataLen = frames * 4;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, 36 + dataLen);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le32(h, 16, 16);
        le16(h, 20, 1);
        le16(h, 22, 2);
        le32(h, 24, ProdEngine.RATE);
        le32(h, 28, ProdEngine.RATE * 4L);
        le16(h, 32, 4);
        le16(h, 34, 16);
        put(h, 36, "data");
        le32(h, 40, dataLen);
        os.write(h);
        final byte[] buf = new byte[BLOCK * 4];
        pump(e, frames, p, new Sink() {
            @Override
            public void write(short[] s, int n) throws IOException {
                for (int i = 0; i < n * 2; i++) {
                    buf[2 * i] = (byte) s[i];
                    buf[2 * i + 1] = (byte) (s[i] >> 8);
                }
                os.write(buf, 0, n * 4);
            }
        });
    }

    private static void put(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[o + i] = (byte) s.charAt(i);
        }
    }

    private static void le32(byte[] b, int o, long v) {
        for (int i = 0; i < 4; i++) {
            b[o + i] = (byte) (v >> (8 * i));
        }
    }

    private static void le16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    // ------------------------------------------------------------------ FLAC

    private static final class Bits {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(16384);
        long acc;
        int n;

        void put(long v, int bits) {
            while (bits > 0) {
                int take = Math.min(bits, 32);
                long part = (v >>> (bits - take)) & ((1L << take) - 1);
                acc = (acc << take) | part;
                n += take;
                bits -= take;
                while (n >= 8) {
                    out.write((int) ((acc >>> (n - 8)) & 0xFF));
                    n -= 8;
                }
                acc &= (1L << n) - 1;
            }
        }

        void align() {
            if (n > 0) {
                put(0, 8 - n);
            }
        }
    }

    private static int crc8(byte[] b, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) {
            c ^= b[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
            }
        }
        return c;
    }

    private static int crc16(byte[] b, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) {
            c ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xFFFF : (c << 1) & 0xFFFF;
            }
        }
        return c;
    }

    /** FLAC 16 bit stereo 44100 Hz (predittori fissi, Rice). Il numero di campioni e' noto in anticipo. */
    static void writeFlac(ProdEngine e, long frames, OutputStream os, Progress p) throws IOException {
        byte[] h = new byte[4 + 4 + 34];
        put(h, 0, "fLaC");
        h[4] = (byte) 0x80; // ultimo blocco metadati, tipo STREAMINFO
        h[5] = 0;
        h[6] = 0;
        h[7] = 34;
        int o = 8;
        h[o++] = (byte) (BLOCK >> 8);
        h[o++] = (byte) BLOCK;
        h[o++] = (byte) (BLOCK >> 8);
        h[o++] = (byte) BLOCK;
        o += 6; // dimensioni frame min/max: sconosciute
        // 20 bit frequenza, 3 bit canali-1, 5 bit profondita'-1, 36 bit campioni
        long packed = ((long) ProdEngine.RATE << 44) | (1L << 41) | (15L << 36) | (frames & 0xFFFFFFFFFL);
        for (int i = 0; i < 8; i++) {
            h[o++] = (byte) (packed >>> (56 - 8 * i));
        }
        // MD5 a zero (non calcolato)
        os.write(h);
        final long[] frameNo = {0};
        final int[] l = new int[BLOCK], r = new int[BLOCK];
        pump(e, frames, p, new Sink() {
            @Override
            public void write(short[] s, int n) throws IOException {
                for (int i = 0; i < n; i++) {
                    l[i] = s[2 * i];
                    r[i] = s[2 * i + 1];
                }
                os.write(flacFrame(l, r, n, frameNo[0]++));
            }
        });
    }

    private static byte[] flacFrame(int[] l, int[] r, int n, long frameNo) {
        Bits b = new Bits();
        // intestazione
        b.put(0x3FFE, 14);
        b.put(0, 1);
        b.put(0, 1);
        b.put(n == BLOCK ? 12 : 7, 4); // 4096 oppure "8 bit - 1 a fine intestazione"... usiamo 16 bit sotto
        b.put(9, 4);                   // 44100 Hz
        b.put(1, 4);                   // 2 canali indipendenti
        b.put(4, 3);                   // 16 bit
        b.put(0, 1);
        utf8(b, frameNo);
        if (n != BLOCK) {
            b.put(n - 1, 16);
        }
        b.align();
        byte[] head = b.out.toByteArray();
        b.out.reset();
        int hc = crc8(head, head.length);
        Bits f = new Bits();
        for (byte x : head) {
            f.put(x & 0xFF, 8);
        }
        f.put(hc, 8);
        subframe(f, l, n);
        subframe(f, r, n);
        f.align();
        byte[] body = f.out.toByteArray();
        int c16 = crc16(body, body.length);
        byte[] res = new byte[body.length + 2];
        System.arraycopy(body, 0, res, 0, body.length);
        res[body.length] = (byte) (c16 >> 8);
        res[body.length + 1] = (byte) c16;
        return res;
    }

    private static void utf8(Bits b, long v) {
        if (v < 0x80) {
            b.put(v, 8);
        } else if (v < 0x800) {
            b.put(0xC0 | (v >> 6), 8);
            b.put(0x80 | (v & 0x3F), 8);
        } else if (v < 0x10000) {
            b.put(0xE0 | (v >> 12), 8);
            b.put(0x80 | ((v >> 6) & 0x3F), 8);
            b.put(0x80 | (v & 0x3F), 8);
        } else if (v < 0x200000) {
            b.put(0xF0 | (v >> 18), 8);
            b.put(0x80 | ((v >> 12) & 0x3F), 8);
            b.put(0x80 | ((v >> 6) & 0x3F), 8);
            b.put(0x80 | (v & 0x3F), 8);
        } else if (v < 0x4000000) {
            b.put(0xF8 | (v >> 24), 8);
            b.put(0x80 | ((v >> 18) & 0x3F), 8);
            b.put(0x80 | ((v >> 12) & 0x3F), 8);
            b.put(0x80 | ((v >> 6) & 0x3F), 8);
            b.put(0x80 | (v & 0x3F), 8);
        } else {
            b.put(0xFC | (v >> 30), 8);
            for (int s = 24; s >= 0; s -= 6) {
                b.put(0x80 | ((v >> s) & 0x3F), 8);
            }
        }
    }

    private static void subframe(Bits b, int[] x, int n) {
        // sceglie l'ordine fisso (0..4) con il residuo piu' piccolo
        int bestOrder = 0;
        long bestCost = Long.MAX_VALUE;
        int[] res = new int[n];
        int[] best = new int[n];
        for (int order = 0; order <= 4 && order < n; order++) {
            long cost = 0;
            for (int i = order; i < n; i++) {
                res[i] = residual(x, i, order);
                cost += Math.abs((long) res[i]);
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestOrder = order;
                System.arraycopy(res, 0, best, 0, n);
            }
        }
        // costante?
        boolean constant = true;
        for (int i = 1; i < n; i++) {
            if (x[i] != x[0]) {
                constant = false;
                break;
            }
        }
        b.put(0, 1);
        if (constant) {
            b.put(0, 6);
            b.put(0, 1);
            b.put(x[0] & 0xFFFF, 16);
            return;
        }
        b.put(8 + bestOrder, 6);
        b.put(0, 1);
        for (int i = 0; i < bestOrder; i++) {
            b.put(x[i] & 0xFFFF, 16);
        }
        // Rice, una sola partizione
        int cnt = n - bestOrder;
        long sum = 0;
        for (int i = bestOrder; i < n; i++) {
            sum += zig(best[i]);
        }
        int k = 0;
        long mean = cnt == 0 ? 0 : sum / cnt;
        while (k < 14 && (1L << (k + 1)) <= mean + 1) {
            k++;
        }
        b.put(0, 2);   // metodo Rice a 4 bit
        b.put(0, 4);   // ordine partizione 0
        b.put(k, 4);
        for (int i = bestOrder; i < n; i++) {
            long u = zig(best[i]);
            long q = u >>> k;
            for (long t = 0; t < q; t++) {
                b.put(0, 1);
            }
            b.put(1, 1);
            if (k > 0) {
                b.put(u & ((1L << k) - 1), k);
            }
        }
    }

    private static long zig(int v) {
        return v >= 0 ? ((long) v << 1) : (((long) -v << 1) - 1);
    }

    private static int residual(int[] x, int i, int order) {
        switch (order) {
            case 0:
                return x[i];
            case 1:
                return x[i] - x[i - 1];
            case 2:
                return x[i] - 2 * x[i - 1] + x[i - 2];
            case 3:
                return x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3];
            default:
                return x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4];
        }
    }
}
