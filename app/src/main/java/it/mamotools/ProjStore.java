package it.mamotools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Archivio dei progetti del Video Editor e dell'Audio Editor (cartella privata dell'app). */
final class ProjStore {

    static final class Entry {
        String id, title, summary;
        long modified;
    }

    private final File dir;
    private final String ext;

    ProjStore(Context c, String kind) {
        dir = new File(c.getFilesDir(), "progetti_" + kind);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        ext = "video".equals(kind) ? ".mamovideo" : ".mamoaudio";
    }

    static String newId() {
        return "p" + System.currentTimeMillis();
    }

    File file(String id) {
        return new File(dir, id + ext);
    }

    /** File accessorio del progetto (per esempio l'audio decodificato). */
    File asset(String id, String name) {
        return new File(dir, id + "_" + name);
    }

    List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(ext)) {
                    continue;
                }
                try {
                    JSONObject o = new JSONObject(readText(f));
                    Entry e = new Entry();
                    e.id = f.getName().substring(0, f.getName().length() - ext.length());
                    e.title = o.optString("title", e.id);
                    e.summary = o.optString("summary", "");
                    e.modified = f.lastModified();
                    out.add(e);
                } catch (Exception ignored) {
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    private static String readText(File f) throws IOException {
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

    JSONObject read(String id) throws Exception {
        return new JSONObject(readText(file(id)));
    }

    synchronized void write(String id, JSONObject o) throws IOException {
        File tmp = new File(dir, id + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(o.toString().getBytes("UTF-8"));
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
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.getName().startsWith(id + "_")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] b = new byte[256 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                os.write(b, 0, n);
            }
        }
    }

    /** Prepara in cache/shared il file da condividere. Audio: archivio con progetto e dati audio. */
    File shareFile(Context c, String id, String title, boolean audio) throws IOException {
        File sd = new File(c.getCacheDir(), "shared");
        //noinspection ResultOfMethodCallIgnored
        sd.mkdirs();
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) {
            safe = "progetto";
        }
        File out = new File(sd, safe + ext);
        if (!audio) {
            copy(file(id), out);
            return out;
        }
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new FileOutputStream(out))) {
            zo.setLevel(1);
            zo.putNextEntry(new java.util.zip.ZipEntry("project.json"));
            try (InputStream in = new FileInputStream(file(id))) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    zo.write(b, 0, n);
                }
            }
            zo.closeEntry();
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.getName().startsWith(id + "_")) {
                        zo.putNextEntry(new java.util.zip.ZipEntry(f.getName().substring(id.length() + 1)));
                        try (InputStream in = new FileInputStream(f)) {
                            byte[] b = new byte[262144];
                            int n;
                            while ((n = in.read(b)) > 0) {
                                zo.write(b, 0, n);
                            }
                        }
                        zo.closeEntry();
                    }
                }
            }
        }
        return out;
    }

    /** Chiede ad Android di ricordare il permesso di leggere il file scelto, cosi' il progetto lo ritrova. */
    static void keep(Context c, Uri u) {
        if (u == null || !"content".equals(u.getScheme())) {
            return;
        }
        try {
            c.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }
}
