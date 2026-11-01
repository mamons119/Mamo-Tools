package it.mamotools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Motore dell'Audio Editor: ogni audio caricato diventa un file PCM 16 bit stereo a 44100 Hz
 * nella cache; la traccia e' una lista di "clip" (pezzi di quei file), cosi' taglia/incolla/
 * elimina non copiano dati. Filtri e volume sono applicati in ascolto ed esportazione.
 */
final class AudioEngine {

    static final int RATE = 44100;
    static final int BLOCK = 147; // fotogrammi per campione della forma d'onda

    static final class Src {
        File pcm;
        long frames;
        byte[] peaks;
    }

    static final class Clip {
        final int src;
        final long a, b; // fotogrammi [a, b) nel file sorgente

        Clip(int src, long a, long b) {
            this.src = src;
            this.a = a;
            this.b = b;
        }

        long len() {
            return b - a;
        }
    }

    /** Filtro + volume, immutabile: per cambiare un parametro se ne crea uno nuovo. */
    static final class Fx {
        static final int NONE = 0, HP = 1, LP = 2, BP = 3;
        final int mode;
        final float lo, hi; // Hz: taglio del passa-alto / del passa-basso
        final int order;    // 1 = 12 dB/ott, 2 = 24 dB/ott
        final float gainDb;

        Fx(int mode, float lo, float hi, int order, float gainDb) {
            this.mode = mode;
            this.lo = lo;
            this.hi = hi;
            this.order = order;
            this.gainDb = gainDb;
        }

        boolean isNeutral() {
            return mode == NONE && Math.abs(gainDb) < 0.01f;
        }
    }

    interface FxSource {
        Fx get();
    }

    interface Progress {
        void onProgress(int pct);
    }

    final List<Src> srcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    List<Clip> tl = new ArrayList<>();
    List<Clip> clipboard = new ArrayList<>();
    private final ArrayDeque<List<Clip>> undo = new ArrayDeque<>();

    // ------------------------------------------------------------------ timeline

    static long total(List<Clip> l) {
        long t = 0;
        for (Clip c : l) {
            t += c.len();
        }
        return t;
    }

    long total() {
        return total(tl);
    }

    void pushUndo() {
        undo.push(new ArrayList<>(tl));
        while (undo.size() > 30) {
            undo.removeLast();
        }
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    void undo() {
        if (!undo.isEmpty()) {
            tl = undo.pop();
        }
    }

    void clearAll() {
        tl = new ArrayList<>();
        undo.clear();
        for (Src s : srcs) {
            //noinspection ResultOfMethodCallIgnored
            s.pcm.delete();
        }
        srcs.clear();
    }

    static List<Clip> slice(List<Clip> list, long a, long b) {
        List<Clip> out = new ArrayList<>();
        long o = 0;
        for (Clip c : list) {
            long s = Math.max(a, o), e = Math.min(b, o + c.len());
            if (s < e) {
                out.add(new Clip(c.src, c.a + (s - o), c.a + (e - o)));
            }
            o += c.len();
        }
        return out;
    }

    static List<Clip> merge(List<Clip> list) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : list) {
            if (c.len() <= 0) {
                continue;
            }
            if (!out.isEmpty()) {
                Clip p = out.get(out.size() - 1);
                if (p.src == c.src && p.b == c.a) {
                    out.set(out.size() - 1, new Clip(p.src, p.a, c.b));
                    continue;
                }
            }
            out.add(c);
        }
        return out;
    }

    void remove(long a, long b) {
        List<Clip> n = slice(tl, 0, a);
        n.addAll(slice(tl, b, Long.MAX_VALUE / 2));
        tl = merge(n);
    }

    void keep(long a, long b) {
        tl = merge(slice(tl, a, b));
    }

    void insert(long pos, List<Clip> ins) {
        List<Clip> n = slice(tl, 0, pos);
        n.addAll(ins);
        n.addAll(slice(tl, pos, Long.MAX_VALUE / 2));
        tl = merge(n);
    }

    // ------------------------------------------------------------------ caricamento

    /** Decodifica un file audio (o l'audio di un video) e lo aggiunge alle sorgenti. */
    Src load(Context ctx, Uri uri) throws IOException {
        File f = new File(ctx.getCacheDir(), "aud_" + srcs.size() + "_" + System.nanoTime() + ".pcm");
        VideoProcessor.PcmWriter w = new VideoProcessor.PcmWriter(f);
        try {
            VideoProcessor.decodeRange(ctx, uri, 0, 1L << 40, w);
        } finally {
            w.close();
        }
        if (w.frames <= 0) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IOException("il file non contiene audio leggibile");
        }
        Src s = new Src();
        s.pcm = f;
        s.frames = w.frames;
        s.peaks = computePeaks(f, w.frames);
        srcs.add(s);
        return s;
    }

    private static byte[] computePeaks(File f, long frames) throws IOException {
        int nb = (int) ((frames + BLOCK - 1) / BLOCK);
        byte[] p = new byte[nb];
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 256 * 1024)) {
            byte[] buf = new byte[BLOCK * 4];
            for (int i = 0; i < nb; i++) {
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
                int max = 0;
                for (int k = 0; k + 1 < n; k += 2) {
                    int v = Math.abs((short) ((buf[k] & 0xFF) | (buf[k + 1] << 8)));
                    if (v > max) {
                        max = v;
                    }
                }
                p[i] = (byte) Math.min(127, max >> 8);
            }
        }
        return p;
    }

    /** Picco (0..127) della traccia tra i fotogrammi [t0, t1). */
    int peak(long t0, long t1) {
        int best = 0;
        long o = 0;
        for (Clip c : tl) {
            long l = c.len();
            if (o + l > t0 && o < t1) {
                long s = Math.max(t0, o), e = Math.min(t1, o + l);
                long fa = c.a + (s - o), fb = c.a + (e - o);
                byte[] pk = srcs.get(c.src).peaks;
                int ba = (int) (fa / BLOCK), bb = (int) ((fb - 1) / BLOCK);
                for (int i = ba; i <= bb && i < pk.length; i++) {
                    if (pk[i] > best) {
                        best = pk[i];
                    }
                }
            }
            o += l;
            if (o >= t1) {
                break;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ lettura

    static final class Reader {
        private final List<Clip> clips;
        private final List<Src> srcs;
        private final long[] starts;
        private final RandomAccessFile[] files;
        long pos;
        final long end;

        Reader(List<Clip> clips, List<Src> srcs, long from, long to) {
            this.clips = new ArrayList<>(clips);
            this.srcs = new ArrayList<>(srcs);
            starts = new long[this.clips.size()];
            long o = 0;
            for (int i = 0; i < starts.length; i++) {
                starts[i] = o;
                o += this.clips.get(i).len();
            }
            files = new RandomAccessFile[this.srcs.size()];
            pos = from;
            end = Math.min(to, o);
        }

        /** Riempie buf con al massimo frames fotogrammi; restituisce quanti ne ha letti. */
        int read(byte[] buf, int frames) throws IOException {
            int done = 0;
            while (done < frames && pos < end) {
                int idx = -1;
                for (int i = 0; i < starts.length; i++) {
                    if (pos >= starts[i] && pos < starts[i] + clips.get(i).len()) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    break;
                }
                Clip c = clips.get(idx);
                long inClip = pos - starts[idx];
                long n = Math.min(Math.min(frames - done, c.len() - inClip), end - pos);
                RandomAccessFile raf = files[c.src];
                if (raf == null) {
                    raf = new RandomAccessFile(srcs.get(c.src).pcm, "r");
                    files[c.src] = raf;
                }
                raf.seek((c.a + inClip) * 4);
                raf.readFully(buf, done * 4, (int) n * 4);
                done += n;
                pos += n;
            }
            return done;
        }

        void close() {
            for (RandomAccessFile f : files) {
                if (f != null) {
                    try {
                        f.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ filtri

    /** Catena di biquad (formule RBJ, Butterworth) piu' guadagno. */
    static final class Chain {
        private final double[][] co;
        private final double[][] sl, sr;
        private final double gain;

        Chain(Fx fx) {
            List<double[]> list = new ArrayList<>();
            double[] qs = fx.order >= 2 ? new double[]{0.5412, 1.3066} : new double[]{0.7071};
            if (fx.mode == Fx.HP || fx.mode == Fx.BP) {
                for (double q : qs) {
                    list.add(coeffs(true, fx.lo, q));
                }
            }
            if (fx.mode == Fx.LP || fx.mode == Fx.BP) {
                for (double q : qs) {
                    list.add(coeffs(false, fx.hi, q));
                }
            }
            co = list.toArray(new double[0][]);
            sl = new double[co.length][4];
            sr = new double[co.length][4];
            gain = Math.pow(10.0, fx.gainDb / 20.0);
        }

        private static double[] coeffs(boolean highpass, double f, double q) {
            f = Math.max(10, Math.min(f, RATE * 0.45));
            double w0 = 2 * Math.PI * f / RATE;
            double cs = Math.cos(w0), alpha = Math.sin(w0) / (2 * q);
            double b0, b1, b2;
            if (highpass) {
                b0 = (1 + cs) / 2;
                b1 = -(1 + cs);
                b2 = b0;
            } else {
                b0 = (1 - cs) / 2;
                b1 = 1 - cs;
                b2 = b0;
            }
            double a0 = 1 + alpha, a1 = -2 * cs, a2 = 1 - alpha;
            return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
        }

        private static double step(double[] c, double[] s, double x) {
            double y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3];
            s[1] = s[0];
            s[0] = x;
            s[3] = s[2];
            s[2] = y;
            return y;
        }

        void process(byte[] b, int frames) {
            for (int i = 0; i < frames; i++) {
                int o = i * 4;
                double l = ((short) ((b[o] & 0xFF) | (b[o + 1] << 8))) / 32768.0;
                double r = ((short) ((b[o + 2] & 0xFF) | (b[o + 3] << 8))) / 32768.0;
                for (int s = 0; s < co.length; s++) {
                    l = step(co[s], sl[s], l);
                    r = step(co[s], sr[s], r);
                }
                put(b, o, l * gain);
                put(b, o + 2, r * gain);
            }
        }

        private static void put(byte[] b, int o, double v) {
            int s = (int) Math.round(v * 32767.0);
            if (s > 32767) {
                s = 32767;
            } else if (s < -32768) {
                s = -32768;
            }
            b[o] = (byte) s;
            b[o + 1] = (byte) (s >> 8);
        }
    }

    // ------------------------------------------------------------------ ascolto

    static final class Player {
        interface Callback {
            void onEnd();
        }

        private final List<Clip> clips;
        private final List<Src> srcs;
        private final long from, to;
        private final FxSource fxs;
        private final Callback cb;
        private volatile boolean stop = false;
        private volatile AudioTrack track;
        private volatile long played = 0;
        private Thread th;

        Player(AudioEngine e, long from, long to, FxSource fxs, Callback cb) {
            this.clips = new ArrayList<>(e.tl);
            this.srcs = new ArrayList<>(e.srcs);
            this.from = from;
            this.to = to;
            this.fxs = fxs;
            this.cb = cb;
        }

        long position() {
            AudioTrack t = track;
            if (t == null) {
                return from;
            }
            try {
                long p = from + (t.getPlaybackHeadPosition() & 0xFFFFFFFFL);
                return Math.min(p, to);
            } catch (Exception e) {
                return from;
            }
        }

        void start() {
            th = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoop();
                }
            }, "audio-play");
            th.start();
        }

        void stop() {
            stop = true;
            AudioTrack t = track;
            if (t != null) {
                try {
                    t.pause();
                    t.flush();
                } catch (Exception ignored) {
                }
            }
        }

        void join() {
            if (th != null) {
                try {
                    th.join(1500);
                } catch (InterruptedException ignored) {
                }
            }
        }

        private void runLoop() {
            Reader rd = new Reader(clips, srcs, from, to);
            AudioTrack t = null;
            boolean natural = false;
            try {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(Math.max(min, RATE / 5 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                track = t;
                t.play();
                byte[] buf = new byte[2048 * 4];
                Fx cur = null;
                Chain chain = null;
                long written = 0;
                while (!stop) {
                    int n = rd.read(buf, 2048);
                    if (n <= 0) {
                        natural = true;
                        break;
                    }
                    Fx f = fxs.get();
                    if (f != cur) {
                        cur = f;
                        chain = f.isNeutral() ? null : new Chain(f);
                    }
                    if (chain != null) {
                        chain.process(buf, n);
                    }
                    int off = 0;
                    while (off < n * 4 && !stop) {
                        int w = t.write(buf, off, n * 4 - off);
                        if (w <= 0) {
                            stop = true;
                            break;
                        }
                        off += w;
                    }
                    written += n;
                }
                if (natural && !stop) {
                    long wait = System.currentTimeMillis();
                    while (!stop && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < written
                            && System.currentTimeMillis() - wait < 3000) {
                        Thread.sleep(20);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                rd.close();
                if (t != null) {
                    try {
                        t.stop();
                    } catch (Exception ignored) {
                    }
                    t.release();
                }
                track = null;
            }
            if (cb != null) {
                cb.onEnd();
            }
        }
    }

    // ------------------------------------------------------------------ esportazione

    void exportWav(Fx fx, OutputStream os, Progress p) throws IOException {
        long total = total();
        long dataLen = total * 4;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, 36 + dataLen);
        put(h, 8, "WAVEfmt ");
        le32(h, 16, 16);
        le16(h, 20, 1);
        le16(h, 22, 2);
        le32(h, 24, RATE);
        le32(h, 28, RATE * 4);
        le16(h, 32, 4);
        le16(h, 34, 16);
        put(h, 36, "data");
        le32(h, 40, dataLen);
        os.write(h);
        Reader rd = new Reader(tl, srcs, 0, total);
        try {
            Chain chain = fx.isNeutral() ? null : new Chain(fx);
            byte[] buf = new byte[8192 * 4];
            int n;
            long done = 0;
            while ((n = rd.read(buf, 8192)) > 0) {
                if (chain != null) {
                    chain.process(buf, n);
                }
                os.write(buf, 0, n * 4);
                done += n;
                p.onProgress((int) (done * 100 / Math.max(1, total)));
            }
        } finally {
            rd.close();
        }
    }

    private static void put(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[o + i] = (byte) s.charAt(i);
        }
    }

    private static void le32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static void le16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    void exportM4a(Fx fx, FileDescriptor fd, Progress p) throws IOException {
        long total = total();
        Reader rd = new Reader(tl, srcs, 0, total);
        MediaCodec enc = null;
        MediaMuxer mx = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 160000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mx = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            Chain chain = fx.isNeutral() ? null : new Chain(fx);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] chunk = new byte[16384];
            boolean inDone = false, started = false;
            int track = -1;
            long fed = 0;
            while (true) {
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(ii);
                        ib.clear();
                        int frames = Math.min(ib.capacity(), chunk.length) / 4;
                        int n = rd.read(chunk, frames);
                        if (n <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, fed * 1000000L / RATE,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            if (chain != null) {
                                chain.process(chunk, n);
                            }
                            ib.put(chunk, 0, n * 4);
                            enc.queueInputBuffer(ii, 0, n * 4, fed * 1000000L / RATE, 0);
                            fed += n;
                            p.onProgress((int) (fed * 100 / Math.max(1, total)));
                        }
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mx.addTrack(enc.getOutputFormat());
                    mx.start();
                    started = true;
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && started) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mx.writeSampleData(track, ob, info);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            if (started) {
                mx.stop();
            }
        } finally {
            rd.close();
            if (enc != null) {
                try {
                    enc.stop();
                } catch (Exception ignored) {
                }
                enc.release();
            }
            if (mx != null) {
                try {
                    mx.release();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
