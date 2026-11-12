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

    // ------------------------------------------------------------------ condivisione con i media

    /** Avanzamento: restituisce false se l'utente ha annullato. */
    interface Prog {
        boolean step(String text, int pct);
    }

    /** Formato dell'archivio "con media" (campo "mamoEmbedded" del project.json). */
    static final int EMBED_VERSION = 1;

    private static final String[] URI_KEYS = {"origUri", "soundUri"};

    /** Nome leggibile di un file indicato da un Uri. */
    static String nameOf(Context c, Uri u) {
        String n = null;
        try (android.database.Cursor cu = c.getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cu != null && cu.moveToFirst()) {
                n = cu.getString(0);
            }
        } catch (Exception ignored) {
        }
        if (n == null || n.isEmpty()) {
            n = u.getLastPathSegment();
        }
        if (n == null || n.isEmpty()) {
            n = "media";
        }
        return n.replaceFirst("^p\\d+_media_m\\d+_", "").replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static long sizeOf(Context c, Uri u) {
        try (android.content.res.AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(u, "r")) {
            return fd == null ? -1 : fd.getLength();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Insieme degli Uri dei media usati dal progetto (nell'ordine di prima comparsa). */
    private static java.util.LinkedHashSet<String> mediaUris(JSONObject o) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String k : URI_KEYS) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) {
                set.add(v);
            }
        }
        org.json.JSONArray pa = o.optJSONArray("pieces");
        for (int i = 0; pa != null && i < pa.length(); i++) {
            String v = pa.optJSONObject(i) == null ? "" : pa.optJSONObject(i).optString("uri", "");
            if (!v.isEmpty()) {
                set.add(v);
            }
            String sv = pa.optJSONObject(i) == null ? "" : pa.optJSONObject(i).optString("snd", "");
            if (!sv.isEmpty()) {
                set.add(sv);
            }
        }
        return set;
    }

    private static void replaceUris(JSONObject o, java.util.Map<String, String> map) throws org.json.JSONException {
        for (String k : URI_KEYS) {
            String v = o.optString(k, "");
            if (map.containsKey(v)) {
                o.put(k, map.get(v));
            }
        }
        org.json.JSONArray pa = o.optJSONArray("pieces");
        for (int i = 0; pa != null && i < pa.length(); i++) {
            JSONObject p = pa.optJSONObject(i);
            if (p != null && map.containsKey(p.optString("uri", ""))) {
                p.put("uri", map.get(p.getString("uri")));
            }
            if (p != null && map.containsKey(p.optString("snd", ""))) {
                p.put("snd", map.get(p.getString("snd")));
            }
        }
    }

    /** Dimensione totale dei media del progetto, in byte (-1 se qualcuno non e' leggibile). */
    long mediaBytes(Context c, String id) {
        try {
            long tot = 0;
            for (String u : mediaUris(read(id))) {
                long n = sizeOf(c, Uri.parse(u));
                if (n < 0) {
                    return -1;
                }
                tot += n;
            }
            return tot;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Archivio unico con project.json e tutti i media (clip e colonna sonora) del progetto. */
    File shareWithMedia(Context c, String id, String title, Prog prog) throws Exception {
        JSONObject o = read(id);
        java.util.LinkedHashSet<String> uris = mediaUris(o);
        long total = 0;
        for (String u : uris) {
            long n = sizeOf(c, Uri.parse(u));
            if (n < 0) {
                throw new IOException("il file \"" + nameOf(c, Uri.parse(u)) + "\" non e' piu' disponibile "
                        + "(spostato o eliminato)");
            }
            total += n;
        }
        File sd = new File(c.getCacheDir(), "shared");
        //noinspection ResultOfMethodCallIgnored
        sd.mkdirs();
        File[] old = sd.listFiles();
        if (old != null) {
            for (File of : old) {
                if (of.getName().endsWith(" (con media)" + ext)) {
                    //noinspection ResultOfMethodCallIgnored
                    of.delete(); // le condivisioni precedenti occupano molto spazio
                }
            }
        }
        if (sd.getUsableSpace() < total + 20L * 1024 * 1024) {
            throw new IOException("spazio libero insufficiente: servono circa " + (total / 1048576 + 20)
                    + " MB temporanei");
        }
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) {
            safe = "progetto";
        }
        File out = new File(sd, safe + " (con media)" + ext);
        java.util.Map<String, String> map = new java.util.HashMap<>();
        java.util.List<String> names = new ArrayList<>();
        int k = 0;
        for (String u : uris) {
            String nm = "m" + k + "_" + nameOf(c, Uri.parse(u));
            names.add(nm);
            map.put(u, "mamomedia:" + nm);
            k++;
        }
        replaceUris(o, map);
        o.put("mamoEmbedded", EMBED_VERSION);
        boolean ok = false;
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new FileOutputStream(out))) {
            zo.setLevel(0); // i video sono gia' compressi
            zo.putNextEntry(new java.util.zip.ZipEntry("project.json"));
            zo.write(o.toString().getBytes("UTF-8"));
            zo.closeEntry();
            long done = 0;
            int i = 0;
            byte[] b = new byte[256 * 1024];
            for (String u : uris) {
                zo.putNextEntry(new java.util.zip.ZipEntry("media/" + names.get(i)));
                try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(u))) {
                    if (in == null) {
                        throw new IOException("impossibile leggere \"" + names.get(i) + "\"");
                    }
                    int n;
                    while ((n = in.read(b)) > 0) {
                        zo.write(b, 0, n);
                        done += n;
                        if (prog != null && !prog.step("Copio i media (" + (i + 1) + "/" + uris.size() + ")",
                                total > 0 ? (int) (done * 100 / total) : 0)) {
                            throw new IOException("annullata");
                        }
                    }
                }
                zo.closeEntry();
                i++;
            }
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
        return out;
    }

    /** True se il file e' un archivio zip (condivisione con media), false se e' un progetto semplice. */
    static boolean isEmbedded(Context c, Uri u) throws IOException {
        try (InputStream is = c.getContentResolver().openInputStream(u)) {
            if (is == null) {
                throw new IOException("file non leggibile");
            }
            byte[] h = new byte[4];
            int n = is.read(h);
            return n == 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
        }
    }

    /**
     * Apre un archivio "con media": copia i media tra i file del progetto e lo salva con un id nuovo.
     * Restituisce l'id. In caso di errore ripulisce quanto copiato.
     */
    String importEmbedded(Context c, Uri u, Prog prog) throws Exception {
        String id = newId();
        boolean ok = false;
        try {
            long total = 0;
            JSONObject o = null;
            try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(
                    c.getContentResolver().openInputStream(u))) {
                java.util.zip.ZipEntry e;
                while ((e = zi.getNextEntry()) != null) {
                    if ("project.json".equals(e.getName())) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] b = new byte[16384];
                        int n;
                        while ((n = zi.read(b)) > 0) {
                            bo.write(b, 0, n);
                        }
                        o = new JSONObject(bo.toString("UTF-8"));
                        break;
                    }
                }
            }
            if (o == null) {
                throw new IOException("file danneggiato: manca il progetto all'interno");
            }
            int ver = o.optInt("mamoEmbedded", 0);
            if (ver <= 0) {
                throw new IOException("archivio non riconosciuto come progetto con media");
            }
            if (ver > EMBED_VERSION) {
                throw new IOException("questo file e' stato creato da una versione piu' recente di Mamo Tools: "
                        + "aggiorna l'app per aprirlo");
            }
            if (!o.has("pieces") || !o.has("origUri")) {
                throw new IOException("non e' un progetto del Video Editor");
            }
            long len = 0;
            try {
                try (android.content.res.AssetFileDescriptor fd = c.getContentResolver()
                        .openAssetFileDescriptor(u, "r")) {
                    len = fd == null ? 0 : fd.getLength();
                }
            } catch (Exception ignored) {
            }
            if (len > 0 && dir.getUsableSpace() < len + 20L * 1024 * 1024) {
                throw new IOException("spazio libero insufficiente: servono circa " + (len / 1048576 + 20) + " MB");
            }
            total = len;
            // estrazione dei media
            java.util.Map<String, String> map = new java.util.HashMap<>();
            java.util.Set<String> needed = new java.util.HashSet<>();
            for (String mu : mediaUris(o)) {
                if (mu.startsWith("mamomedia:")) {
                    needed.add(mu.substring("mamomedia:".length()));
                }
            }
            long done = 0;
            try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(
                    c.getContentResolver().openInputStream(u))) {
                java.util.zip.ZipEntry e;
                byte[] b = new byte[256 * 1024];
                while ((e = zi.getNextEntry()) != null) {
                    String en = e.getName();
                    if (!en.startsWith("media/") || en.contains("..")) {
                        continue;
                    }
                    String nm = en.substring("media/".length());
                    if (!needed.contains(nm)) {
                        continue;
                    }
                    File dst = asset(id, "media_" + nm);
                    try (OutputStream os = new FileOutputStream(dst)) {
                        int n;
                        while ((n = zi.read(b)) > 0) {
                            os.write(b, 0, n);
                            done += n;
                            if (prog != null && !prog.step("Estraggo i media", total > 0
                                    ? (int) Math.min(99, done * 100 / total) : 0)) {
                                throw new IOException("annullata");
                            }
                        }
                    }
                    map.put("mamomedia:" + nm, Uri.fromFile(dst).toString());
                }
            }
            for (String nm : needed) {
                if (!map.containsKey("mamomedia:" + nm)) {
                    throw new IOException("file danneggiato o incompleto: manca \"" + nm.replaceFirst("^m\\d+_", "")
                            + "\"");
                }
            }
            replaceUris(o, map);
            o.remove("mamoEmbedded");
            write(id, o);
            ok = true;
            return id;
        } finally {
            if (!ok) {
                delete(id);
            }
        }
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
