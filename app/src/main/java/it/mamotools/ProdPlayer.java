package it.mamotools;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Riproduzione in tempo reale del motore di Produzioni tramite AudioTrack. */
final class ProdPlayer {

    private static final int BLOCK = 1024;

    final ProdEngine engine;
    private AudioTrack track;
    private Thread thread;
    private volatile boolean run;
    private final Object lock = new Object();

    ProdPlayer(ProdEngine engine) {
        this.engine = engine;
    }

    void open() {
        if (run) {
            return;
        }
        int min = AudioTrack.getMinBufferSize(ProdEngine.RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ProdEngine.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(min, BLOCK * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        track.play();
        run = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "prod-audio");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private void loop() {
        float[] l = new float[BLOCK], r = new float[BLOCK];
        short[] out = new short[BLOCK * 2];
        while (run) {
            if (!engine.active()) {
                synchronized (lock) {
                    try {
                        lock.wait(150);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                continue;
            }
            engine.fill(l, r, BLOCK);
            for (int i = 0; i < BLOCK; i++) {
                out[2 * i] = ProdFiles.toShort(l[i]);
                out[2 * i + 1] = ProdFiles.toShort(r[i]);
            }
            AudioTrack t = track;
            if (t == null) {
                return;
            }
            t.write(out, 0, out.length);
        }
    }

    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    void play(int mode, boolean loop) {
        engine.start(mode, loop);
        wake();
    }

    void stop() {
        engine.stop();
    }

    void preview(int track, int note) {
        engine.preview(track, note);
        wake();
    }

    /** {passo globale, pattern, passo nel pattern} che si sta sentendo ora, oppure null. */
    int[] position() {
        AudioTrack t = track;
        if (t == null || !engine.playing) {
            return null;
        }
        long played = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        return engine.positionAt(played);
    }

    void release() {
        run = false;
        wake();
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Exception ignored) {
            }
            t.release();
        }
    }
}
