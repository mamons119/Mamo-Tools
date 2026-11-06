package it.mamotools;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Esporta il motore di Produzioni in AAC dentro un file .m4a. */
final class ProdM4a {

    private ProdM4a() {
    }

    static void write(ProdEngine e, long frames, FileDescriptor fd, int kbps, final ProdFiles.Progress p)
            throws IOException {
        final MediaCodec enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaMuxer mx = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", ProdEngine.RATE, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mx = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final MediaMuxer fmx = mx;
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final int[] track = {-1};
            final long[] fed = {0};

            ProdFiles.pump(e, frames, p, new ProdFiles.Sink() {
                @Override
                public void write(short[] s, int n) throws IOException {
                    byte[] bytes = new byte[n * 4];
                    for (int i = 0; i < n * 2; i++) {
                        bytes[2 * i] = (byte) s[i];
                        bytes[2 * i + 1] = (byte) (s[i] >> 8);
                    }
                    int off = 0;
                    while (off < bytes.length) {
                        int ii = enc.dequeueInputBuffer(10000);
                        if (ii >= 0) {
                            ByteBuffer ib = enc.getInputBuffer(ii);
                            ib.clear();
                            int len = Math.min(ib.capacity(), bytes.length - off);
                            len -= len % 4;
                            ib.put(bytes, off, len);
                            enc.queueInputBuffer(ii, 0, len, fed[0] * 1000000L / ProdEngine.RATE, 0);
                            fed[0] += len / 4;
                            off += len;
                        }
                        drain(enc, fmx, info, track, false);
                    }
                }
            });
            // fine del flusso
            while (true) {
                int ii = enc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    enc.queueInputBuffer(ii, 0, 0, fed[0] * 1000000L / ProdEngine.RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                drain(enc, fmx, info, track, false);
            }
            while (!drain(enc, fmx, info, track, true)) {
                // attende l'ultimo pacchetto
            }
            if (track[0] >= 0) {
                mx.stop();
            }
        } finally {
            try {
                enc.stop();
            } catch (Exception ignored) {
            }
            enc.release();
            if (mx != null) {
                try {
                    mx.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Svuota l'encoder; ritorna true quando arriva la fine del flusso. */
    private static boolean drain(MediaCodec enc, MediaMuxer mx, MediaCodec.BufferInfo info, int[] track,
                                 boolean wait) {
        while (true) {
            int oi = enc.dequeueOutputBuffer(info, wait ? 10000 : 0);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = mx.addTrack(enc.getOutputFormat());
                mx.start();
            } else if (oi >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && track[0] >= 0) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    mx.writeSampleData(track[0], ob, info);
                }
                enc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return true;
                }
            } else {
                return false;
            }
        }
    }
}
