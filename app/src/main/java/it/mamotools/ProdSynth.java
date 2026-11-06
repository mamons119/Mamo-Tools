package it.mamotools;

/** Sintesi dei suoni di "Produzioni": ogni nota diventa un breve buffer audio (mono o stereo). Java puro. */
final class ProdSynth {

    static final int RATE = 44100;

    /** Campione importato dall'utente (16 bit, 44100 Hz). r e' null se mono. */
    static final class Sample {
        final short[] l, r;

        Sample(short[] l, short[] r) {
            this.l = l;
            this.r = r;
        }

        int frames() {
            return l.length;
        }
    }

    interface SampleProvider {
        Sample get(String id);
    }

    static final class Voice {
        float[] l, r; // r null => mono
    }

    /** guadagno finale (picco) di ogni suono: bilancia i volumi di partenza */
    private static final float[] PEAK = {0.95f, 0.8f, 0.5f, 0.5f, 0.75f, 0.85f, 0.6f,
            0.85f, 0.6f, 0.45f, 0.5f, 0.7f, 0.5f, 0.45f, 0.9f};

    private ProdSynth() {
    }

    private static final class Rnd {
        int s;

        Rnd(int seed) {
            s = seed * 2654435761L == 0 ? 1 : (int) (seed * 2654435761L) | 1;
        }

        float next() { // -1..1
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            return (s & 0xFFFFFF) / (float) 0x7FFFFF - 1f;
        }
    }

    static double freq(int midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    /** Genera la nota. note = numero MIDI (per le percussioni viene ignorato). Null se il sample manca. */
    static Voice render(ProdModel.Track t, int note, SampleProvider sp) {
        int si = ProdModel.soundIndex(t.sound);
        float m = (float) Math.pow(2.0, (t.decay - 50) / 25.0); // 0.25x .. 4x
        double pitch = Math.pow(2.0, t.tone / 12.0);
        Rnd rnd = new Rnd(si * 131 + note * 7 + 5);
        Voice v = new Voice();
        switch (t.sound) {
            case "kick":
                v.l = kick(m, pitch, rnd);
                break;
            case "snare":
                v.l = snare(m, pitch, rnd);
                break;
            case "hatc":
                v.l = hat(0.022f * m, 0.14f * m, pitch, rnd);
                break;
            case "hato":
                v.l = hat(0.12f * m, 0.7f * m, pitch, rnd);
                break;
            case "clap":
                v.l = clap(m, rnd);
                break;
            case "tom":
                v.l = tom(m, pitch);
                break;
            case "rim":
                v.l = rim(m, pitch, rnd);
                break;
            case "bass":
                v.l = bass(freq(note + t.tone), m);
                break;
            case "keys":
                v.l = keys(freq(note + t.tone), m);
                break;
            case "pad":
                v.l = pad(freq(note + t.tone), m);
                break;
            case "lead":
                v.l = lead(freq(note + t.tone), m);
                break;
            case "pluck":
                v.l = pluck(freq(note + t.tone), m, rnd);
                break;
            case "bell":
                v.l = bell(freq(note + t.tone), m);
                break;
            case "fx":
                v.l = swell(m, Math.pow(2.0, (note + t.tone - 60) / 12.0), rnd);
                break;
            case "sample": {
                ProdSynth.Sample s = sp == null ? null : sp.get(t.sample);
                if (s == null || s.frames() < 2) {
                    return null;
                }
                sampleVoice(v, s, t, note, m);
                break;
            }
            default:
                v.l = new float[1];
        }
        // filtro passa-basso della traccia
        if (t.cutoff < 99) {
            double fc = 150.0 * Math.pow(120.0, t.cutoff / 100.0);
            lowpass(v.l, fc);
            if (v.r != null) {
                lowpass(v.r, fc);
            }
        }
        // normalizza al picco del suono e smorza la coda
        float peak = 1e-6f;
        for (float x : v.l) {
            peak = Math.max(peak, Math.abs(x));
        }
        if (v.r != null) {
            for (float x : v.r) {
                peak = Math.max(peak, Math.abs(x));
            }
        }
        float g = PEAK[si] / peak;
        fadeEnd(v.l, g);
        if (v.r != null) {
            fadeEnd(v.r, g);
        }
        return v;
    }

    private static void fadeEnd(float[] b, float g) {
        int f = Math.min(b.length / 2, 330);
        for (int i = 0; i < b.length; i++) {
            float x = b[i] * g;
            if (i >= b.length - f) {
                x *= (b.length - i) / (float) f;
            }
            b[i] = x;
        }
    }

    private static void lowpass(float[] b, double fc) {
        float a = (float) (1.0 - Math.exp(-2.0 * Math.PI * Math.min(fc, 20000) / RATE));
        float y1 = 0, y2 = 0;
        for (int i = 0; i < b.length; i++) {
            y1 += a * (b[i] - y1);
            y2 += a * (y1 - y2);
            b[i] = y2;
        }
    }

    private static void highpass(float[] b, double fc) {
        float a = (float) (1.0 - Math.exp(-2.0 * Math.PI * fc / RATE));
        float lp = 0;
        for (int i = 0; i < b.length; i++) {
            lp += a * (b[i] - lp);
            b[i] -= lp;
        }
    }

    private static int len(double sec) {
        return Math.max(64, (int) (Math.min(sec, 6.0) * RATE));
    }

    private static float[] kick(float m, double pitch, Rnd rnd) {
        float[] b = new float[len(0.5 * m)];
        double ph = 0, f0 = 52 * pitch, f1 = 190 * pitch;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            double f = f0 + (f1 - f0) * Math.exp(-t / 0.028);
            ph += 2 * Math.PI * f / RATE;
            float x = (float) (Math.sin(ph) * Math.exp(-t / (0.17 * m)));
            if (t < 0.003) {
                x += rnd.next() * 0.25f * (float) (1 - t / 0.003);
            }
            b[i] = (float) Math.tanh(x * 1.6);
        }
        return b;
    }

    private static float[] snare(float m, double pitch, Rnd rnd) {
        float[] b = new float[len(0.3 * m)];
        float[] n = new float[b.length];
        double ph = 0;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * 190 * pitch / RATE;
            n[i] = (float) (rnd.next() * Math.exp(-t / (0.09 * m)));
            b[i] = (float) (Math.sin(ph) * Math.exp(-t / (0.06 * m)) * 0.7);
        }
        highpass(n, 1200 * pitch);
        for (int i = 0; i < b.length; i++) {
            b[i] += n[i] * 0.9f;
        }
        return b;
    }

    private static float[] hat(float tau, float length, double pitch, Rnd rnd) {
        float[] b = new float[len(length)];
        for (int i = 0; i < b.length; i++) {
            b[i] = rnd.next() * (float) Math.exp(-(i / (double) RATE) / tau);
        }
        highpass(b, 7000 * pitch);
        return b;
    }

    private static float[] clap(float m, Rnd rnd) {
        float[] b = new float[len(0.35 * m)];
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            double e = 0;
            for (int k = 0; k < 4; k++) {
                double tt = t - k * 0.011;
                if (tt >= 0) {
                    e += Math.exp(-tt / (k < 3 ? 0.004 : 0.09 * m)) * (k < 3 ? 0.8 : 1.0);
                }
            }
            b[i] = (float) (rnd.next() * e);
        }
        highpass(b, 700);
        lowpass(b, 4500);
        return b;
    }

    private static float[] tom(float m, double pitch) {
        float[] b = new float[len(0.45 * m)];
        double ph = 0;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            double f = (105 + 90 * Math.exp(-t / 0.05)) * pitch;
            ph += 2 * Math.PI * f / RATE;
            b[i] = (float) (Math.sin(ph) * Math.exp(-t / (0.14 * m)));
        }
        return b;
    }

    private static float[] rim(float m, double pitch, Rnd rnd) {
        float[] b = new float[len(0.08 * m)];
        double ph = 0;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            ph += 2 * Math.PI * 1750 * pitch / RATE;
            b[i] = (float) ((Math.sin(ph) * 0.7 + rnd.next() * 0.3) * Math.exp(-t / (0.012 * m)));
        }
        return b;
    }

    private static double polyBlep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.0;
        } else if (t > 1.0 - dt) {
            t = (t - 1.0) / dt;
            return t * t + t + t + 1.0;
        }
        return 0.0;
    }

    private static float[] bass(double f, float m) {
        float[] b = new float[len(0.9 * m)];
        double ph = 0, sub = 0, dt = f / RATE;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            ph += dt;
            ph -= Math.floor(ph);
            sub += 2 * Math.PI * f / RATE;
            double saw = 2 * ph - 1 - polyBlep(ph, dt);
            double env = Math.min(1, t / 0.004) * Math.exp(-t / (0.42 * m));
            b[i] = (float) ((saw * 0.45 + Math.sin(sub) * 0.75) * env);
        }
        lowpass(b, 900 + f * 3);
        return b;
    }

    private static float[] keys(double f, float m) {
        float[] b = new float[len(2.0 * m)];
        double pc = 0, pm = 0, pb = 0;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            pm += 2 * Math.PI * f / RATE;
            double idx = 1.9 * Math.exp(-t / 0.22) + 0.25;
            pc += 2 * Math.PI * f / RATE;
            pb += 2 * Math.PI * f * 14 / RATE;
            double car = Math.sin(pc + idx * Math.sin(pm));
            double tine = Math.sin(pb) * Math.exp(-t / 0.02) * 0.25;
            double trem = 1 + 0.06 * Math.sin(2 * Math.PI * 4.7 * t);
            b[i] = (float) ((car + tine) * Math.min(1, t / 0.003) * Math.exp(-t / (0.85 * m)) * trem);
        }
        return b;
    }

    private static float[] pad(double f, float m) {
        float[] b = new float[len(2.4 * m)];
        double[] det = {0.994, 1.0, 1.006};
        double[] ph = {0, 0.33, 0.66};
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            double s = 0;
            for (int k = 0; k < 3; k++) {
                double dt = f * det[k] / RATE;
                ph[k] += dt;
                ph[k] -= Math.floor(ph[k]);
                s += 2 * ph[k] - 1 - polyBlep(ph[k], dt);
            }
            double env = Math.min(1, t / 0.22) * Math.exp(-t / (1.6 * m));
            b[i] = (float) (s * env);
        }
        lowpass(b, 2200 + f);
        return b;
    }

    private static float[] lead(double f, float m) {
        float[] b = new float[len(0.8 * m)];
        double ph = 0;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            double vib = 1 + 0.004 * Math.sin(2 * Math.PI * 5.2 * t) * Math.min(1, t / 0.2);
            double dt = f * vib / RATE;
            ph += dt;
            ph -= Math.floor(ph);
            double w = 0.4;
            double s = ph < w ? 1 : -1;
            s += polyBlep(ph, dt) - polyBlep((ph + 1 - w) % 1.0, dt);
            b[i] = (float) (s * Math.min(1, t / 0.006) * Math.exp(-t / (0.35 * m)));
        }
        lowpass(b, 3500 + f);
        return b;
    }

    private static float[] pluck(double f, float m, Rnd rnd) {
        float[] b = new float[len(1.8 * m)];
        int n = Math.max(2, (int) Math.round(RATE / f));
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            d[i] = rnd.next();
        }
        float damp = (float) (1.0 - 0.0042 / Math.max(0.25, m));
        int p = 0;
        for (int i = 0; i < b.length; i++) {
            int q = (p + 1) % n;
            float y = 0.5f * (d[p] + d[q]) * damp;
            b[i] = d[p];
            d[p] = y;
            p = q;
        }
        return b;
    }

    private static float[] bell(double f, float m) {
        float[] b = new float[len(3.0 * m)];
        double pc = 0, pm = 0;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            pm += 2 * Math.PI * f * 3.5 / RATE;
            pc += 2 * Math.PI * f / RATE;
            double idx = 2.6 * Math.exp(-t / 0.5);
            b[i] = (float) (Math.sin(pc + idx * Math.sin(pm)) * Math.min(1, t / 0.002) * Math.exp(-t / (1.1 * m)));
        }
        return b;
    }

    private static float[] swell(float m, double ratio, Rnd rnd) {
        int n = len(1.6 * m);
        float[] b = new float[n];
        float y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            double fc = (350 + 5500 * p * p) * ratio;
            float a = (float) (1.0 - Math.exp(-2.0 * Math.PI * Math.min(fc, 15000) / RATE));
            y1 += a * (rnd.next() - y1);
            y2 += a * (y1 - y2);
            double env = Math.sin(Math.PI * Math.pow(p, 0.7));
            b[i] = (float) (y2 * env);
        }
        return b;
    }

    private static void sampleVoice(Voice v, Sample s, ProdModel.Track t, int note, float m) {
        int total = s.frames();
        int a = (int) ((long) total * t.smpStart / 1000L);
        int e = (int) ((long) total * Math.max(t.smpEnd, t.smpStart + 5) / 1000L);
        e = Math.min(total, Math.max(a + 2, e));
        double ratio = Math.pow(2.0, (note - 60 + t.tone) / 12.0);
        int outLen = (int) Math.min(6.0 * RATE, ((e - a) / ratio));
        outLen = Math.max(2, outLen);
        v.l = new float[outLen];
        v.r = s.r == null ? null : new float[outLen];
        boolean shaped = t.decay < 50;
        double tau = shaped ? 0.05 + 1.5 * (t.decay / 50.0) : 0;
        for (int i = 0; i < outLen; i++) {
            double pos = a + i * ratio;
            int i0 = (int) pos;
            if (i0 >= e - 1) {
                v.l = java.util.Arrays.copyOf(v.l, i);
                if (v.r != null) {
                    v.r = java.util.Arrays.copyOf(v.r, i);
                }
                break;
            }
            float fr = (float) (pos - i0);
            float env = shaped ? (float) Math.exp(-(i / (double) RATE) / tau) : 1f;
            v.l[i] = (s.l[i0] + (s.l[i0 + 1] - s.l[i0]) * fr) / 32768f * env;
            if (v.r != null) {
                v.r[i] = (s.r[i0] + (s.r[i0 + 1] - s.r[i0]) * fr) / 32768f * env;
            }
        }
        if (v.l.length < 2) {
            v.l = new float[2];
            if (v.r != null) {
                v.r = new float[2];
            }
        }
    }
}
