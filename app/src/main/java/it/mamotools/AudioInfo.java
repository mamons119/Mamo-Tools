package it.mamotools;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.util.Locale;

/** Informazioni tecniche di un file audio. */
final class AudioInfo {
    static final String[] ROWS = {"Dimensione", "Formato", "Campionamento", "Canali", "Bitrate", "Durata"};

    private AudioInfo() {
    }

    static String[] describe(Context c, Uri uri) {
        long size = FileUtil.querySize(c, uri);
        String mime = null;
        int rate = 0, ch = 0;
        long br = 0, durMs = 0;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    mime = m;
                    rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                    ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        br = f.getInteger(MediaFormat.KEY_BIT_RATE);
                    }
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        durMs = f.getLong(MediaFormat.KEY_DURATION) / 1000;
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            ex.release();
        }
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(c, uri);
            String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                durMs = Long.parseLong(d);
            }
            String b = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (b != null) {
                br = Long.parseLong(b);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                mr.release();
            } catch (Exception ignored) {
            }
        }
        if (br <= 0 && size > 0 && durMs > 0) {
            br = size * 8000 / durMs;
        }
        return new String[]{
                size > 0 ? FileUtil.humanSize(size) : "?",
                name(mime),
                rate > 0 ? String.format(Locale.US, "%.1f kHz", rate / 1000.0) : "?",
                ch == 1 ? "Mono" : ch == 2 ? "Stereo" : ch > 0 ? ch + " canali" : "?",
                br > 0 ? (br >= 1_000_000 ? String.format(Locale.US, "%.2f Mb/s", br / 1e6)
                        : Math.round(br / 1000.0) + " kb/s") : "?",
                durMs > 0 ? fmt(durMs) : "?"};
    }

    private static String name(String m) {
        if (m == null) {
            return "?";
        }
        switch (m) {
            case "audio/mpeg":
                return "MP3";
            case "audio/mp4a-latm":
                return "AAC (M4A)";
            case "audio/raw":
                return "WAV (PCM)";
            case "audio/vorbis":
                return "OGG Vorbis";
            case "audio/opus":
                return "Opus";
            case "audio/flac":
                return "FLAC";
            case "audio/3gpp":
            case "audio/amr-wb":
                return "AMR";
            default:
                return m.substring(m.indexOf('/') + 1);
        }
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }
}
