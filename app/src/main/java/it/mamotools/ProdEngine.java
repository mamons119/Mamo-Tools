package it.mamotools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sequencer + mixer + effetti di "Produzioni". Non conosce Android: produce blocchi di audio float
 * stereo, usati sia dalla riproduzione in tempo reale sia dall'esportazione.
 */
final class ProdEngine {

    static final int RATE = ProdSynth.RATE;
    static final int MODE_PATTERN = 0, MODE_SONG = 1;

    final ProdModel.Song song;
    private final ProdSynth.SampleProvider samples;

    // ---- trasporto (modificabile dall'interfaccia) ----
    volatile boolean playing;
    volatile int curPattern = 0;
    private int mode = MODE_PATTERN;
    private boolean loop = true;
    private long endStep = Long.MAX_VALUE;
    private volatile boolean finished;

    private long frame;               // fotogrammi prodotti dall'inizio
    private double nextStep;          // fotogramma in cui parte il prossimo passo
    private long step;                // passo globale corrente
    private boolean ended;
    private long tailLeft;
    private int[] seqPat = new int[0], seqStep = new int[0];

    // ---- voci ----
    private static final class V {
        float[] l, r;
        int pos, track, group, fade = -1;
        float gl, gr;
    }

    private final List<V> voices = new ArrayList<>();
    private final Map<String, ProdSynth.Voice> cache = new HashMap<>();
    private final ConcurrentLinkedQueue<int[]> previews = new ConcurrentLinkedQueue<>();

    // ---- posizione per l'interfaccia ----
    private final long[] ringFrame = new long[128];
    private final int[] ringPat = new int[128], ringSip = new int[128];
    private final long[] ringStep = new long[128];
    private volatile int ringHead = 0;

    private long quiet = 0;

    ProdEngine(ProdModel.Song song, ProdSynth.SampleProvider samples) {
        this.song = song;
        this.samples = samples;
        initFx();
    }

    // ------------------------------------------------------------------ trasporto

    /** Avvia dal principio. loop=false nel brano: si ferma alla fine e lascia sfumare le code. */
    synchronized void start(int mode, boolean loop) {
        this.mode = mode;
        this.loop = loop;
        this.endStep = Long.MAX_VALUE;
        reset();
        if (mode == MODE_SONG) {
            buildSequence();
            if (seqPat.length == 0) {
                playing = false;
                finished = true;
                return;
            }
            if (!loop) {
                endStep = seqPat.length;
            }
        }
        playing = true;
    }

    /** Per l'esportazione: un numero finito di passi, poi la coda. */
    synchronized void startExport(int mode, int pattern, int repeats) {
        this.mode = mode;
        this.loop = false;
        reset();
        if (mode == MODE_SONG) {
            buildSequence();
            endStep = seqPat.length;
        } else {
            curPattern = pattern;
            endStep = (long) song.patterns[pattern].steps * Math.max(1, repeats);
        }
        playing = endStep > 0;
    }

    synchronized void stop() {
        playing = false;
        for (V v : voices) {
            if (v.fade < 0) {
                v.fade = 2000;
            }
        }
    }

    private void reset() {
        step = 0;
        nextStep = frame;
        ended = false;
        finished = false;
        tailLeft = 0;
        ringHead = 0;
        for (int i = 0; i < 128; i++) {
            ringFrame[i] = Long.MAX_VALUE;
        }
    }

    private void buildSequence() {
        int n = song.songSteps();
        seqPat = new int[n];
        seqStep = new int[n];
        int k = 0;
        for (int[] a : song.arr) {
            ProdModel.Pattern p = song.patterns[a[0]];
            for (int r = 0; r < a[1]; r++) {
                for (int s = 0; s < p.steps; s++) {
                    seqPat[k] = a[0];
                    seqStep[k] = s;
                    k++;
                }
            }
        }
    }

    boolean isFinished() {
        return finished;
    }

    /** Vero se c'e' ancora qualcosa da suonare (trasporto, voci o code di eco/riverbero). */
    boolean active() {
        return playing || !voices.isEmpty() || !previews.isEmpty() || quiet < RATE * 3L;
    }

    int mode() {
        return mode;
    }

    double stepFrames() {
        return RATE * 60.0 / (Math.max(40, song.bpm) * 4.0);
    }

    private double swingShift() {
        return song.swing / 100.0 * 0.33;
    }

    /** Durata in fotogrammi di n passi (con swing) + coda per eco/riverbero. */
    long exportFrames(long steps) {
        return framesFor(song, steps);
    }

    static long framesFor(ProdModel.Song song, long steps) {
        double sf = RATE * 60.0 / (Math.max(40, song.bpm) * 4.0), sh = song.swing / 100.0 * 0.33;
        double d = (steps / 2) * 2 * sf + ((steps & 1) == 1 ? sf * (1 + sh) : 0);
        d += tailFor(song);
        return (long) Math.ceil(d);
    }

    static long tailFor(ProdModel.Song song) {
        return (song.echo > 0 || song.reverb > 0) ? RATE * 3L : RATE / 4;
    }

    long tailFrames() {
        return tailFor(song);
    }

    long exportSteps() {
        return endStep;
    }

    /** {passo globale, pattern, passo nel pattern} per il fotogramma riprodotto dato; null se sconosciuto. */
    int[] positionAt(long playedFrames) {
        int h = ringHead;
        long best = -1;
        int bi = -1;
        for (int i = 0; i < 128; i++) {
            long f = ringFrame[i];
            if (f <= playedFrames && f > best) {
                best = f;
                bi = i;
            }
        }
        if (bi < 0) {
            return null;
        }
        return new int[]{(int) ringStep[bi], ringPat[bi], ringSip[bi]};
    }

    long framesProduced() {
        return frame;
    }

    // ------------------------------------------------------------------ anteprima

    void preview(int track, int note) {
        previews.add(new int[]{track, note});
    }

    // ------------------------------------------------------------------ produzione audio

    synchronized void fill(float[] l, float[] r, int n) {
        java.util.Arrays.fill(l, 0, n, 0f);
        java.util.Arrays.fill(r, 0, n, 0f);
        int[] pv;
        while ((pv = previews.poll()) != null) {
            spawn(pv[0], pv[1], true);
        }
        int done = 0;
        while (done < n) {
            int chunk = n - done;
            if (playing && !ended) {
                while (!ended && playing && nextStepFrame() <= frame) {
                    triggerStep();
                }
                if (playing && !ended) {
                    long until = nextStepFrame() - frame;
                    chunk = (int) Math.max(1, Math.min(chunk, until));
                }
            }
            mix(l, r, done, chunk);
            frame += chunk;
            done += chunk;
            if (ended) {
                tailLeft -= chunk;
                if (tailLeft <= 0 && playing) {
                    playing = false;
                    finished = true;
                }
            }
        }
        fx(l, r, n);
        float peak = 0;
        for (int i = 0; i < n; i++) {
            peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        }
        quiet = peak < 0.0002f && voices.isEmpty() ? quiet + n : 0;
    }

    private long nextStepFrame() {
        return (long) Math.ceil(nextStep);
    }

    private void triggerStep() {
        if (step >= endStep) {
            ended = true;
            tailLeft = tailFrames();
            return;
        }
        int pat, sip;
        if (mode == MODE_SONG) {
            int k = (int) (step % seqPat.length);
            pat = seqPat[k];
            sip = seqStep[k];
        } else {
            pat = curPattern;
            sip = (int) (step % song.patterns[pat].steps);
        }
        int h = ringHead;
        ringFrame[h & 127] = nextStepFrame();
        ringStep[h & 127] = step;
        ringPat[h & 127] = pat;
        ringSip[h & 127] = sip;
        ringHead = h + 1;

        ProdModel.Pattern p = song.patterns[pat];
        boolean anySolo = false;
        for (ProdModel.Track t : song.tracks) {
            anySolo |= t.solo;
        }
        for (int t = 0; t < ProdModel.TRACKS; t++) {
            int note = p.note[t][sip];
            if (note == ProdModel.OFF) {
                continue;
            }
            ProdModel.Track tr = song.tracks[t];
            if (tr.mute || (anySolo && !tr.solo)) {
                continue;
            }
            spawn(t, note, false);
        }
        double sf = stepFrames(), sh = swingShift();
        nextStep += (step & 1) == 0 ? sf * (1 + sh) : sf * (1 - sh);
        step++;
    }

    private static int groupOf(String sound) {
        switch (sound) {
            case "hatc":
            case "hato":
                return 1;
            case "bass":
                return 2;
            case "lead":
                return 3;
            default:
                return 0;
        }
    }

    private void spawn(int t, int note, boolean isPreview) {
        ProdModel.Track tr = song.tracks[t];
        int nn = tr.melodic() ? note : 60;
        String key = tr.sound + "|" + tr.tone + "|" + tr.decay + "|" + tr.cutoff + "|" + nn + "|" + tr.sample + "|"
                + tr.smpStart + "|" + tr.smpEnd;
        ProdSynth.Voice pv = cache.get(key);
        if (pv == null) {
            pv = ProdSynth.render(tr, nn, samples);
            if (pv == null) {
                return;
            }
            if (cache.size() > 400) {
                cache.clear();
            }
            cache.put(key, pv);
        }
        int g = groupOf(tr.sound);
        for (V o : voices) {
            if (o.fade < 0 && g != 0 && o.group == g && (g == 1 || o.track == t)) {
                o.fade = 330;
            }
        }
        V v = new V();
        v.l = pv.l;
        v.r = pv.r;
        v.track = t;
        v.group = g;
        double vol = tr.vol / 100.0;
        vol = vol * vol * 1.0;
        double pan = tr.pan / 100.0;
        double ang = (pan + 1) * Math.PI / 4;
        v.gl = (float) (vol * Math.cos(ang) * Math.sqrt(2));
        v.gr = (float) (vol * Math.sin(ang) * Math.sqrt(2));
        if (voices.size() >= 40) {
            voices.remove(0);
        }
        voices.add(v);
    }

    private void mix(float[] l, float[] r, int off, int n) {
        for (int k = voices.size() - 1; k >= 0; k--) {
            V v = voices.get(k);
            int len = v.l.length;
            int m = Math.min(n, len - v.pos);
            float[] vr = v.r == null ? v.l : v.r;
            for (int i = 0; i < m; i++) {
                float e = 1f;
                if (v.fade >= 0) {
                    if (v.fade == 0) {
                        v.pos = len;
                        break;
                    }
                    e = Math.min(1f, v.fade / 330f);
                    v.fade--;
                }
                int p = v.pos + i;
                l[off + i] += v.l[p] * v.gl * e;
                r[off + i] += vr[p] * v.gr * e;
            }
            v.pos += m;
            if (v.pos >= len || v.fade == 0) {
                voices.remove(k);
            }
        }
    }

    // ------------------------------------------------------------------ effetti master

    private static final int[] COMB_L = {1116, 1188, 1277, 1356}, COMB_R = {1139, 1211, 1300, 1379};
    private static final int[] AP_L = {556, 441}, AP_R = {579, 464};
    private float[][] combL, combR, apL, apR;
    private int[] combPosL, combPosR, apPosL, apPosR;
    private float[] combLpL, combLpR;
    private float[] dlyL, dlyR;
    private int dlyPos;
    private float dlyLpL, dlyLpR;
    private float[] wowBuf;
    private int wowPos;
    private double wowPh;
    private float lpA1, lpA2, lpB1, lpB2, holdL, holdR;
    private int holdCnt;
    private int crackle;
    private int seed = 12345;

    private void initFx() {
        combL = new float[4][];
        combR = new float[4][];
        for (int i = 0; i < 4; i++) {
            combL[i] = new float[COMB_L[i]];
            combR[i] = new float[COMB_R[i]];
        }
        apL = new float[2][];
        apR = new float[2][];
        for (int i = 0; i < 2; i++) {
            apL[i] = new float[AP_L[i]];
            apR[i] = new float[AP_R[i]];
        }
        combPosL = new int[4];
        combPosR = new int[4];
        apPosL = new int[2];
        apPosR = new int[2];
        combLpL = new float[4];
        combLpR = new float[4];
        dlyL = new float[RATE * 2];
        dlyR = new float[RATE * 2];
        wowBuf = new float[4096];
    }

    private float rnd() {
        seed ^= seed << 13;
        seed ^= seed >>> 17;
        seed ^= seed << 5;
        return (seed & 0xFFFFFF) / (float) 0x7FFFFF - 1f;
    }

    private void fx(float[] l, float[] r, int n) {
        float lofi = song.lofi / 100f, echo = song.echo / 100f, rev = song.reverb / 100f;
        float master = (float) Math.pow(song.master / 100.0, 1.5);

        // 1) Lo-Fi
        if (lofi > 0.001f) {
            float fc = (float) (12000 * Math.pow(2500.0 / 12000.0, lofi));
            float a = (float) (1 - Math.exp(-2 * Math.PI * fc / RATE));
            int hold = 1 + Math.round(lofi * 2f);
            float levels = (float) Math.pow(2.0, 15 - 7 * lofi);
            float drive = 1f + lofi * 1.5f;
            int wl = wowBuf.length;
            for (int i = 0; i < n; i++) {
                wowBuf[wowPos] = (l[i] + r[i]) * 0.5f;
                wowPh += 1.0 / RATE;
                double lfo = Math.sin(2 * Math.PI * 0.55 * wowPh) + 0.4 * Math.sin(2 * Math.PI * 1.9 * wowPh);
                double d = 40 + lfo * lofi * 22;
                double rp = wowPos - d;
                while (rp < 0) {
                    rp += wl;
                }
                int i0 = (int) rp;
                float fr = (float) (rp - i0);
                float mono = wowBuf[i0 % wl] * (1 - fr) + wowBuf[(i0 + 1) % wl] * fr;
                wowPos = (wowPos + 1) % wl;
                float side = (l[i] - r[i]) * 0.5f;
                float xl = mono + side, xr = mono - side;
                lpA1 += a * (xl - lpA1);
                lpA2 += a * (lpA1 - lpA2);
                lpB1 += a * (xr - lpB1);
                lpB2 += a * (lpB1 - lpB2);
                if (holdCnt <= 0) {
                    holdL = Math.round(lpA2 * levels) / levels;
                    holdR = Math.round(lpB2 * levels) / levels;
                    holdCnt = hold;
                }
                holdCnt--;
                float yl = (float) Math.tanh(holdL * drive), yr = (float) Math.tanh(holdR * drive);
                // fruscio e scricchiolii del vinile
                float noise = rnd() * 0.004f * lofi;
                if (rnd() > 1f - 0.00025f * lofi * 2f) {
                    crackle = 90;
                }
                if (crackle > 0) {
                    float c = rnd() * 0.12f * lofi * (crackle / 90f);
                    yl += c;
                    yr += c * 0.8f;
                    crackle--;
                }
                l[i] = yl + noise;
                r[i] = yr + noise;
            }
        }
        // 2) Eco (ping-pong sincronizzato al tempo, croma puntata)
        if (echo > 0.001f) {
            int d = (int) Math.min(dlyL.length - 2, Math.round(stepFrames() * 3));
            float fb = 0.45f, mix = echo * 0.65f;
            float lp = 0.35f;
            for (int i = 0; i < n; i++) {
                int rp = (dlyPos - d + dlyL.length) % dlyL.length;
                float wl = dlyL[rp], wr = dlyR[rp];
                dlyLpL += lp * (wl - dlyLpL);
                dlyLpR += lp * (wr - dlyLpR);
                float in = (l[i] + r[i]) * 0.5f;
                dlyL[dlyPos] = in * 0.8f + dlyLpR * fb;
                dlyR[dlyPos] = dlyLpL * fb;
                dlyPos = (dlyPos + 1) % dlyL.length;
                l[i] += wl * mix;
                r[i] += wr * mix;
            }
        } else {
            for (int i = 0; i < n; i++) {
                dlyL[dlyPos] = 0;
                dlyR[dlyPos] = 0;
                dlyPos = (dlyPos + 1) % dlyL.length;
            }
        }
        // 3) Riverbero (stile Freeverb)
        if (rev > 0.001f) {
            float fb = 0.76f + rev * 0.14f, damp = 0.35f, mix = rev * 0.45f;
            for (int i = 0; i < n; i++) {
                float in = (l[i] + r[i]) * 0.5f * 0.2f;
                float ol = 0, or = 0;
                for (int k = 0; k < 4; k++) {
                    float[] b = combL[k];
                    float y = b[combPosL[k]];
                    combLpL[k] += damp * (y - combLpL[k]);
                    b[combPosL[k]] = in + combLpL[k] * fb;
                    combPosL[k] = (combPosL[k] + 1) % b.length;
                    ol += y;
                    float[] c = combR[k];
                    float z = c[combPosR[k]];
                    combLpR[k] += damp * (z - combLpR[k]);
                    c[combPosR[k]] = in + combLpR[k] * fb;
                    combPosR[k] = (combPosR[k] + 1) % c.length;
                    or += z;
                }
                for (int k = 0; k < 2; k++) {
                    float[] b = apL[k];
                    float bo = b[apPosL[k]];
                    b[apPosL[k]] = ol + bo * 0.5f;
                    ol = bo - ol;
                    apPosL[k] = (apPosL[k] + 1) % b.length;
                    float[] c = apR[k];
                    float co = c[apPosR[k]];
                    c[apPosR[k]] = or + co * 0.5f;
                    or = co - or;
                    apPosR[k] = (apPosR[k] + 1) % c.length;
                }
                l[i] += ol * mix * 1.5f;
                r[i] += or * mix * 1.5f;
            }
        }
        // 4) volume generale e limitatore morbido
        for (int i = 0; i < n; i++) {
            l[i] = soft(l[i] * master);
            r[i] = soft(r[i] * master);
        }
    }

    private static float soft(float x) {
        float a = Math.abs(x);
        if (a <= 0.85f) {
            return x;
        }
        float y = 0.85f + 0.15f * (float) Math.tanh((a - 0.85f) / 0.15f);
        return x < 0 ? -y : y;
    }
}
