package it.mamotools;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Motore del Video Editor, solo API Android (MediaExtractor / MediaCodec / MediaMuxer).
 *
 * Video: i tratti tenuti vengono copiati senza ricodifica (nessuna perdita di qualita',
 * velocissimo). Il taglio e' quindi preciso al fotogramma chiave precedente (di solito
 * entro ~1 secondo). Audio: se non c'e' colonna sonora e' copiato com'e'; con colonna
 * sonora viene decodificato, mixato e ricodificato in AAC.
 */
final class VideoProcessor {

    interface Progress {
        void onProgress(String stage, int percent);
    }

    static final class Options {
        Uri video;
        /** Tratti da tenere, in microsecondi: {inizio, fine}. */
        long[][] keep;
        boolean keepAudio = true;
        Uri sound;               // null = nessuna colonna sonora
        long soundStartUs = 0;
        boolean mix = false;     // true = aggiunge all'originale, false = sostituisce
        float originalDb = -12f; // abbassamento dell'originale quando mix (negativo)
        VideoStabilizer.Params stab; // null = nessuna stabilizzazione
        int extraRot = 0; // rotazione aggiuntiva scelta dall'utente (0/90/180/270, in senso orario)
        int quality = 0; // 0 massima, 1 alta, 2 media, 3 bassa (ricodifica)
        java.util.List<VideoFx.Item> fx; // effetti e testi (tempi in microsecondi)
        long soundFadeInUs = 0, soundFadeOutUs = 0; // dissolvenze della colonna sonora (0 = nessuna)
        int soundFadeCurve = 1; // 0 lineare, 1 morbida
    }

    static final int RATE = 44100;
    private static final long TIMEOUT = 10000;

    private VideoProcessor() {
    }

    // =========================================================================== export

    static void export(Context ctx, Options o, FileDescriptor out, File cacheDir, Progress p)
            throws IOException {
        if (o.stab != null || VideoFx.hasAny(o.fx)) {
            File tmp = new File(cacheDir, "stab.mp4");
            try {
                final Progress outer = p;
                VideoStabilizer.run(ctx, o.video, tmp, o.stab, o.fx, o.quality, o.extraRot, new Progress() {
                    @Override
                    public void onProgress(String stage, int pct) {
                        outer.onProgress(stage, pct * 70 / 100);
                    }
                });
                Options o2 = new Options();
                o2.video = Uri.fromFile(tmp);
                o2.keep = o.keep;
                o2.keepAudio = o.keepAudio;
                o2.sound = o.sound;
                o2.soundStartUs = o.soundStartUs;
                o2.mix = o.mix;
                o2.originalDb = o.originalDb;
                o2.soundFadeInUs = o.soundFadeInUs;
                o2.soundFadeOutUs = o.soundFadeOutUs;
                o2.soundFadeCurve = o.soundFadeCurve;
                o2.extraRot = 0; // gia' applicata nel file intermedio
                export(ctx, o2, out, cacheDir, new Progress() {
                    @Override
                    public void onProgress(String stage, int pct) {
                        outer.onProgress(stage, 70 + pct * 30 / 100);
                    }
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return;
        }
        // ---- taglio preciso: se un inizio di tratto non cade su un fotogramma chiave, la copia
        // diretta includerebbe anche parte del materiale eliminato. In quel caso i tratti da tenere
        // vengono montati con ricodifica (precisi al fotogramma) e poi si prosegue sul risultato.
        if (needsAccurateCut(ctx, o)) {
            File trimmed = new File(cacheDir, "trimmed.mp4");
            try {
                final Progress outer2 = p;
                java.util.List<VideoComposer.Seg> segsList = new java.util.ArrayList<>();
                long len = 0;
                for (long[] k : o.keep) {
                    if (k[1] - k[0] >= 100000) {
                        segsList.add(new VideoComposer.Seg(o.video, k[0], k[1]));
                        len += k[1] - k[0];
                    }
                }
                int[] dim = displaySize(ctx, o.video);
                int side = Math.min(1920, Math.max(dim[0], dim[1]));
                int[] wh = VideoComposer.fitSize(dim[0], dim[1], side);
                VideoComposer.compose(ctx, segsList, trimmed, cacheDir, wh[0], wh[1],
                        Math.max(4000000, wh[0] * wh[1] * 8), new Progress() {
                            @Override
                            public void onProgress(String stage, int pct) {
                                outer2.onProgress(stage, pct * 60 / 100);
                            }
                        });
                Options o3 = new Options();
                o3.video = Uri.fromFile(trimmed);
                o3.keep = new long[][]{{0, len}};
                o3.keepAudio = o.keepAudio;
                o3.sound = o.sound;
                o3.soundStartUs = o.soundStartUs;
                o3.mix = o.mix;
                o3.originalDb = o.originalDb;
                o3.soundFadeInUs = o.soundFadeInUs;
                o3.soundFadeOutUs = o.soundFadeOutUs;
                o3.soundFadeCurve = o.soundFadeCurve;
                o3.extraRot = o.extraRot;
                export(ctx, o3, out, cacheDir, new Progress() {
                    @Override
                    public void onProgress(String stage, int pct) {
                        outer2.onProgress(stage, 60 + pct * 40 / 100);
                    }
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                trimmed.delete();
            }
            return;
        }
        p.onProgress("Analisi del video...", 1);

        MediaExtractor ve = new MediaExtractor();
        ve.setDataSource(ctx, o.video, null);
        int vTrack = findTrack(ve, "video/");
        if (vTrack < 0) {
            ve.release();
            throw new IOException("il file non contiene video");
        }
        MediaFormat vFormat = ve.getTrackFormat(vTrack);
        long duration = vFormat.containsKey(MediaFormat.KEY_DURATION)
                ? vFormat.getLong(MediaFormat.KEY_DURATION) : Long.MAX_VALUE;

        int rotation = 0;
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(ctx, o.video);
            String rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rot != null) {
                rotation = Integer.parseInt(rot);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
        rotation = (rotation + o.extraRot) % 360;

        // inizio reale dei tratti (fotogramma chiave precedente) e loro durata
        int n = o.keep.length;
        long[] starts = new long[n];
        long[] ends = new long[n];
        long[] offsets = new long[n];
        long total = 0;
        ve.selectTrack(vTrack);
        int used = 0;
        for (int i = 0; i < n; i++) {
            long a = Math.max(0, o.keep[i][0]);
            long b = Math.min(duration, o.keep[i][1]);
            if (b - a < 100000) {
                continue;
            }
            ve.seekTo(a, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long s0 = ve.getSampleTime();
            if (s0 < 0 || s0 >= b) {
                continue;
            }
            starts[used] = s0;
            ends[used] = b;
            offsets[used] = total;
            total += b - s0;
            used++;
        }
        if (used == 0) {
            ve.release();
            throw new IOException("non resta nessun tratto da salvare");
        }
        final int segs = used;

        // audio originale presente?
        boolean origAudio = false;
        {
            MediaExtractor t = new MediaExtractor();
            try {
                t.setDataSource(ctx, o.video, null);
                origAudio = findTrack(t, "audio/") >= 0;
            } finally {
                t.release();
            }
        }

        // ---- fase audio con colonna sonora: crea un m4a temporaneo
        File tmpDir = cacheDir;
        File pcmOrig = new File(tmpDir, "orig.pcm");
        File pcmSound = new File(tmpDir, "sound.pcm");
        File tmpM4a = new File(tmpDir, "mix.m4a");
        boolean useSound = o.sound != null;
        try {
            if (useSound) {
                boolean needOrig = o.keepAudio && origAudio && o.mix;
                if (needOrig) {
                    p.onProgress("Lettura dell'audio originale...", 5);
                    PcmWriter w = new PcmWriter(pcmOrig);
                    try {
                        for (int i = 0; i < segs; i++) {
                            decodeRange(ctx, o.video, starts[i], ends[i], w);
                            p.onProgress("Lettura dell'audio originale...", 5 + 10 * (i + 1) / segs);
                        }
                    } finally {
                        w.close();
                    }
                }
                p.onProgress("Lettura della colonna sonora...", 15);
                PcmWriter w = new PcmWriter(pcmSound);
                try {
                    decodeRange(ctx, o.sound, o.soundStartUs, o.soundStartUs + total, w);
                } finally {
                    w.close();
                }
                p.onProgress("Mixaggio audio...", 30);
                float g = (float) Math.pow(10.0, o.originalDb / 20.0);
                encodeMix(needOrig ? pcmOrig : null, pcmSound, g, tmpM4a, p,
                        o.soundFadeInUs, o.soundFadeOutUs, o.soundFadeCurve, total);
            }

            // ---- muxing finale
            p.onProgress("Scrittura del video...", 60);
            MediaMuxer mx = new MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaExtractor ae = null;
            try {
                if (rotation != 0) {
                    mx.setOrientationHint(rotation);
                }
                int muxV = mx.addTrack(vFormat);

                int muxA = -1;
                int aTrack = -1;
                boolean audioFromTemp = false;
                if (useSound) {
                    ae = new MediaExtractor();
                    ae.setDataSource(tmpM4a.getAbsolutePath());
                    aTrack = findTrack(ae, "audio/");
                    audioFromTemp = true;
                } else if (o.keepAudio && origAudio) {
                    ae = new MediaExtractor();
                    ae.setDataSource(ctx, o.video, null);
                    aTrack = findTrack(ae, "audio/");
                }
                if (ae != null && aTrack >= 0) {
                    muxA = mx.addTrack(ae.getTrackFormat(aTrack));
                    ae.selectTrack(aTrack);
                }
                mx.start();

                ByteBuffer buf = ByteBuffer.allocateDirect(8 * 1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                for (int i = 0; i < segs; i++) {
                    ve.seekTo(starts[i], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    while (true) {
                        long t = ve.getSampleTime();
                        if (t < 0 || t >= ends[i]) {
                            break;
                        }
                        buf.clear();
                        int size = ve.readSampleData(buf, 0);
                        if (size < 0) {
                            break;
                        }
                        info.set(0, size, Math.max(0, t - starts[i]) + offsets[i],
                                (ve.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        mx.writeSampleData(muxV, buf, info);
                        ve.advance();
                    }
                    p.onProgress("Scrittura del video...", 60 + 30 * (i + 1) / segs);
                }

                if (muxA >= 0) {
                    p.onProgress("Scrittura dell'audio...", 92);
                    if (audioFromTemp) {
                        copySamples(ae, mx, muxA, 0, Long.MAX_VALUE, 0, buf, info);
                    } else {
                        for (int i = 0; i < segs; i++) {
                            copySamples(ae, mx, muxA, starts[i], ends[i], offsets[i], buf, info);
                        }
                    }
                }
                p.onProgress("Chiusura del file...", 98);
                mx.stop();
            } finally {
                try {
                    mx.release();
                } catch (Exception ignored) {
                }
                if (ae != null) {
                    ae.release();
                }
            }
        } finally {
            ve.release();
            //noinspection ResultOfMethodCallIgnored
            pcmOrig.delete();
            //noinspection ResultOfMethodCallIgnored
            pcmSound.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpM4a.delete();
        }
        p.onProgress("Fatto", 100);
    }

    /** Vero se almeno un tratto da tenere non inizia su un fotogramma chiave. */
    private static boolean needsAccurateCut(Context ctx, Options o) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, o.video, null);
            int t = findTrack(ex, "video/");
            if (t < 0) {
                return false;
            }
            ex.selectTrack(t);
            for (long[] k : o.keep) {
                if (k[1] - k[0] < 100000 || k[0] <= 0) {
                    continue;
                }
                ex.seekTo(k[0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long s0 = ex.getSampleTime();
                if (s0 >= 0 && k[0] - s0 > 20000) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            ex.release();
        }
    }

    /** Dimensioni del video come lo si vede (rotazione applicata). */
    private static int[] displaySize(Context ctx, Uri uri) {
        int w = 1280, h = 720, rot = 0;
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(ctx, uri);
            w = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            h = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String r = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            rot = r == null ? 0 : Integer.parseInt(r);
        } catch (Exception ignored) {
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
        return rot % 180 == 0 ? new int[]{w, h} : new int[]{h, w};
    }

    /** Copia i campioni audio tra start e end, spostandoli di (offset - start). */
    private static void copySamples(MediaExtractor ae, MediaMuxer mx, int track, long start,
                                    long end, long offset, ByteBuffer buf,
                                    MediaCodec.BufferInfo info) {
        ae.seekTo(start, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            long t = ae.getSampleTime();
            if (t < 0 || t >= end) {
                break;
            }
            if (t < start) {
                ae.advance();
                continue;
            }
            buf.clear();
            int size = ae.readSampleData(buf, 0);
            if (size < 0) {
                break;
            }
            info.set(0, size, t - start + offset, MediaCodec.BUFFER_FLAG_KEY_FRAME);
            mx.writeSampleData(track, buf, info);
            ae.advance();
        }
    }

    static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // =========================================================================== PCM

    /** Scrive PCM 16 bit stereo a 44100 Hz su file, ricampionando in modo lineare. */
    static final class PcmWriter {
        private final OutputStream os;
        private double pos = 0;
        private boolean havePrev = false;
        private float prevL, prevR;
        long frames = 0;

        PcmWriter(File f) throws IOException {
            os = new BufferedOutputStream(new FileOutputStream(f), 128 * 1024);
        }

        void reset() {
            pos = 0;
            havePrev = false;
        }

        /** Aggiunge n fotogrammi di silenzio (alla frequenza di uscita). */
        void silence(long n) throws IOException {
            for (long i = 0; i < n; i++) {
                os.write(0);
                os.write(0);
                os.write(0);
                os.write(0);
            }
            frames += Math.max(0, n);
        }

        /** l, r: campioni -1..1 di n fotogrammi alla frequenza srcRate. */
        void add(float[] l, float[] r, int n, int srcRate) throws IOException {
            if (n <= 0) {
                return;
            }
            if (!havePrev) {
                prevL = l[0];
                prevR = r[0];
                havePrev = true;
                pos = 0;
            }
            double step = srcRate / (double) RATE;
            while (true) {
                int i0 = (int) Math.floor(pos);
                if (i0 > n - 2) {
                    break;
                }
                double fr = pos - i0;
                float l0 = i0 < 0 ? prevL : l[i0];
                float r0 = i0 < 0 ? prevR : r[i0];
                float l1 = l[i0 + 1];
                float r1 = r[i0 + 1];
                put((float) (l0 + (l1 - l0) * fr));
                put((float) (r0 + (r1 - r0) * fr));
                frames++;
                pos += step;
            }
            pos -= n;
            prevL = l[n - 1];
            prevR = r[n - 1];
        }

        private void put(float v) throws IOException {
            int s = Math.round(v * 32767f);
            if (s > 32767) {
                s = 32767;
            } else if (s < -32768) {
                s = -32768;
            }
            os.write(s & 0xFF);
            os.write((s >> 8) & 0xFF);
        }

        void close() throws IOException {
            os.close();
        }
    }

    /** Decodifica l'audio di uri tra startUs e endUs nel writer (che lo converte). */
    static void decodeRange(Context ctx, Uri uri, long startUs, long endUs,
                                    PcmWriter w) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = findTrack(ex, "audio/");
            if (track < 0) {
                throw new IOException("nessuna traccia audio nel file");
            }
            MediaFormat fmt = ex.getTrackFormat(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(track);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            w.reset();

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : RATE;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int enc = 2;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false;
            boolean outDone = false;
            float[] fl = new float[0];
            float[] fr = new float[0];
            while (!outDone) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(TIMEOUT);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int size = ex.readSampleData(ib, 0);
                        long t = ex.getSampleTime();
                        if (size < 0 || t >= endUs) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, t, 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, TIMEOUT);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (of.containsKey("pcm-encoding")) {
                        enc = of.getInteger("pcm-encoding");
                    }
                } else if (oi >= 0) {
                    if (info.size > 0 && ch > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        int bytesPer = enc == 4 ? 4 : 2;
                        int frames = info.size / (bytesPer * ch);
                        long bufStart = info.presentationTimeUs;
                        int skip = 0;
                        if (bufStart < startUs) {
                            skip = (int) Math.min(frames, (startUs - bufStart) * rate / 1000000L);
                        }
                        long lim = (endUs - bufStart) * rate / 1000000L;
                        int stop = (int) Math.max(0, Math.min(frames, lim));
                        int cnt = stop - skip;
                        if (cnt > 0) {
                            if (fl.length < cnt) {
                                fl = new float[cnt];
                                fr = new float[cnt];
                            }
                            if (enc == 4) {
                                FloatBuffer fb = ob.asFloatBuffer();
                                for (int k = 0; k < cnt; k++) {
                                    int base = (skip + k) * ch;
                                    fl[k] = fb.get(base);
                                    fr[k] = ch > 1 ? fb.get(base + 1) : fl[k];
                                }
                            } else {
                                ShortBuffer sb = ob.asShortBuffer();
                                for (int k = 0; k < cnt; k++) {
                                    int base = (skip + k) * ch;
                                    fl[k] = sb.get(base) / 32768f;
                                    fr[k] = ch > 1 ? sb.get(base + 1) / 32768f : fl[k];
                                }
                            }
                            w.add(fl, fr, cnt, rate);
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outDone = true;
                    }
                }
            }
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                dec.release();
            }
            ex.release();
        }
    }

    // =========================================================================== mix + AAC

    /** Legge due file PCM e li somma (orig * gain + sound), restituendo byte s16le stereo. */
    private static final class MixSource {
        private final InputStream sound;
        private final InputStream orig;
        private final float gain;
        private final byte[] bs = new byte[16384];
        private final byte[] bo = new byte[16384];
        long totalFrames = 0;
        long fadeInF = 0, fadeOutF = 0, endF = 0;
        int curve = 1;

        void setFade(long inUs, long outUs, int curve, long totalUs) {
            fadeInF = inUs * RATE / 1000000L;
            fadeOutF = outUs * RATE / 1000000L;
            endF = totalUs * RATE / 1000000L;
            this.curve = curve;
        }

        private float shape(float x) {
            x = Math.max(0f, Math.min(1f, x));
            return curve == 0 ? x : x * x * (3 - 2 * x);
        }

        /** Guadagno della colonna sonora al fotogramma f (dissolvenza in entrata e in uscita). */
        private float fadeGain(long f) {
            float g = 1f;
            if (fadeInF > 0 && f < fadeInF) {
                g *= shape(f / (float) fadeInF);
            }
            if (fadeOutF > 0 && endF > 0 && f > endF - fadeOutF) {
                g *= shape((endF - f) / (float) fadeOutF);
            }
            return g;
        }

        MixSource(File orig, File sound, float gain) throws IOException {
            this.sound = new BufferedInputStream(new FileInputStream(sound), 64 * 1024);
            this.orig = orig == null ? null
                    : new BufferedInputStream(new FileInputStream(orig), 64 * 1024);
            this.gain = gain;
        }

        /** @return byte scritti in dst (multiplo di 4) oppure -1 a fine. */
        int read(byte[] dst, int max) throws IOException {
            int want = Math.min(max, bs.length) & ~3;
            int ns = fill(sound, bs, want);
            int no = orig == null ? 0 : fill(orig, bo, want);
            int n = Math.max(ns, no);
            if (n <= 0) {
                return -1;
            }
            for (int i = 0; i < n; i += 2) {
                int s = i < ns ? (short) ((bs[i] & 0xFF) | (bs[i + 1] << 8)) : 0;
                if (s != 0 && (fadeInF > 0 || fadeOutF > 0)) {
                    s = Math.round(s * fadeGain(totalFrames + (i >> 2)));
                }
                int ov = i < no ? (short) ((bo[i] & 0xFF) | (bo[i + 1] << 8)) : 0;
                int v = orig == null ? s : Math.round(s + ov * gain);
                if (v > 32767) {
                    v = 32767;
                } else if (v < -32768) {
                    v = -32768;
                }
                dst[i] = (byte) v;
                dst[i + 1] = (byte) (v >> 8);
            }
            totalFrames += n / 4;
            return n;
        }

        private static int fill(InputStream in, byte[] b, int want) throws IOException {
            int got = 0;
            while (got < want) {
                int r = in.read(b, got, want - got);
                if (r < 0) {
                    break;
                }
                got += r;
            }
            return got & ~3;
        }

        void close() {
            try {
                sound.close();
                if (orig != null) {
                    orig.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    static void encodeMix(File orig, File sound, float gain, File outM4a, Progress p)
            throws IOException {
        encodeMix(orig, sound, gain, outM4a, p, 0, 0, 1, 0);
    }

    static void encodeMix(File orig, File sound, float gain, File outM4a, Progress p,
                          long fadeInUs, long fadeOutUs, int curve, long totalUs)
            throws IOException {
        MixSource src = new MixSource(orig, sound, gain);
        src.setFade(fadeInUs, fadeOutUs, curve, totalUs);
        long totalBytes = Math.max(orig == null ? 0 : orig.length(), sound.length());
        MediaCodec enc = null;
        MediaMuxer mx = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 160000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mx = new MediaMuxer(outM4a.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] chunk = new byte[16384];
            boolean inDone = false;
            boolean started = false;
            int track = -1;
            long lastPct = -1;
            while (true) {
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(TIMEOUT);
                    if (ii >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(ii);
                        ib.clear();
                        int n = src.read(chunk, Math.min(ib.capacity(), chunk.length));
                        if (n <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, src.totalFrames * 1000000L / RATE,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            long pts = (src.totalFrames - n / 4) * 1000000L / RATE;
                            ib.put(chunk, 0, n);
                            enc.queueInputBuffer(ii, 0, n, pts, 0);
                            long pct = 30 + 30 * (src.totalFrames * 4) / Math.max(1, totalBytes);
                            if (pct != lastPct) {
                                lastPct = pct;
                                p.onProgress("Mixaggio audio...", (int) Math.min(60, pct));
                            }
                        }
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, TIMEOUT);
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
            src.close();
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
