package it.mamotools;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Archivio dei brani e dei sample di Produzioni (cartella privata dell'app). */
final class ProdStore implements ProdSynth.SampleProvider {

    static final long MAX_SAMPLE_US = 30_000_000L;

    static final class Entry {
        String id, title;
        int bpm;
        long modified;
        double seconds;
    }

    private final File dir, sdir;
    private final Map<String, ProdSynth.Sample> cache = new HashMap<>();

    ProdStore(Context c) {
        dir = new File(c.getFilesDir(), "produzioni");
        sdir = new File(dir, "samples");
        //noinspection ResultOfMethodCallIgnored
        sdir.mkdirs();
    }

    File file(String id) {
        return new File(dir, id + ".mamoprod");
    }

    static String newId() {
        return "b" + System.currentTimeMillis();
    }

    List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(".mamoprod")) {
                    continue;
                }
                try {
                    ProdModel.Song s = ProdModel.Song.parse(read(f));
                    Entry e = new Entry();
                    e.id = f.getName().substring(0, f.getName().length() - 9);
                    e.title = s.title;
                    e.bpm = s.bpm;
                    e.modified = f.lastModified();
                    e.seconds = s.songSteps() * 60.0 / (s.bpm * 4.0);
                    out.add(e);
                } catch (Exception ignored) {
                }
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.modified, a.modified);
            }
        });
        return out;
    }

    private static String read(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) {
                bo.write(b, 0, n);
            }
            return bo.toString("UTF-8");
        }
    }

    ProdModel.Song load(String id) throws IOException {
        return ProdModel.Song.parse(read(file(id)));
    }

    synchronized void save(String id, ProdModel.Song s) throws IOException {
        File tmp = new File(dir, id + ".tmp");
        try (java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
            os.write(s.serialize().getBytes("UTF-8"));
        }
        if (!tmp.renameTo(file(id))) {
            //noinspection ResultOfMethodCallIgnored
            file(id).delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(id));
        }
    }

    void delete(String id) {
        //noinspection ResultOfMethodCallIgnored
        file(id).delete();
        File[] ss = sdir.listFiles();
        if (ss != null) {
            for (File f : ss) {
                if (f.getName().startsWith(id + "_")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    // ------------------------------------------------------------------ sample

    /** Decodifica un audio del telefono (max 30 s) e lo salva per il brano. Ritorna l'id del sample. */
    String importSample(Context c, String songId, Uri uri) throws IOException {
        String id = songId + "_" + System.currentTimeMillis();
        File f = new File(sdir, id + ".pcm");
        VideoProcessor.PcmWriter w = new VideoProcessor.PcmWriter(f);
        try {
            VideoProcessor.decodeRange(c, uri, 0, MAX_SAMPLE_US, w);
        } finally {
            w.close();
        }
        if (w.frames < 64) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IOException("il file non contiene audio leggibile");
        }
        synchronized (this) {
            cache.remove(id);
        }
        return id;
    }

    @Override
    public synchronized ProdSynth.Sample get(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ProdSynth.Sample s = cache.get(id);
        if (s != null) {
            return s;
        }
        File f = new File(sdir, id + ".pcm");
        if (!f.exists()) {
            return null;
        }
        try {
            int n = (int) (f.length() / 4);
            short[] l = new short[n], r = new short[n];
            try (InputStream is = new java.io.BufferedInputStream(new FileInputStream(f), 64 * 1024)) {
                byte[] b = new byte[4];
                boolean same = true;
                for (int i = 0; i < n; i++) {
                    if (is.read(b) < 4) {
                        break;
                    }
                    l[i] = (short) ((b[0] & 0xFF) | (b[1] << 8));
                    r[i] = (short) ((b[2] & 0xFF) | (b[3] << 8));
                    if (l[i] != r[i]) {
                        same = false;
                    }
                }
                s = new ProdSynth.Sample(l, same ? null : r);
            }
            cache.put(id, s);
            return s;
        } catch (IOException e) {
            return null;
        }
    }
}
