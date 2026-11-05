package it.mamotools;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Utilita' per leggere nomi/dimensioni e salvare risultati in Download, Immagini, Musica. */
final class FileUtil {

    interface Writer {
        void write(OutputStream os) throws IOException;
    }

    interface FdWriter {
        void write(FileDescriptor fd) throws IOException;
    }

    private FileUtil() {
    }

    static String queryName(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (q != null && q.moveToFirst()) {
                String n = q.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    static long querySize(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) {
                return q.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static String humanSize(long b) {
        if (b < 0) {
            return "dimensione n/d";
        }
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", b / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", b / 1048576f);
    }

    static String baseName(String name) {
        String n = name == null ? "file" : name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "file" : n;
    }

    static void copyFile(java.io.File src, java.io.File dst) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] b = new byte[32 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
        }
    }

    static void copyToFile(Context c, Uri src, File dst) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IOException("impossibile leggere il file scelto");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    /** Salva in una raccolta di MediaStore (Downloads, Images, Audio...) senza permessi. */
    static Uri save(Context c, Uri collection, String relPath, String name, String mime, Writer w)
            throws IOException {
        return saveInternal(c, collection, relPath, name, mime, w, null);
    }

    /** Come {@link #save} ma passa il file descriptor (per MediaMuxer). */
    static Uri saveFd(Context c, Uri collection, String relPath, String name, String mime,
                      FdWriter w) throws IOException {
        return saveInternal(c, collection, relPath, name, mime, null, w);
    }

    private static Uri saveInternal(Context c, Uri collection, String relPath, String name,
                                    String mime, Writer w, FdWriter fw) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(collection, v);
        if (uri == null) {
            throw new IOException("impossibile creare il file in " + relPath);
        }
        boolean ok = false;
        try {
            if (fw != null) {
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw")) {
                    if (pfd == null) {
                        throw new IOException("impossibile scrivere il file");
                    }
                    fw.write(pfd.getFileDescriptor());
                }
            } else {
                try (OutputStream raw = cr.openOutputStream(uri)) {
                    if (raw == null) {
                        throw new IOException("impossibile scrivere il file");
                    }
                    BufferedOutputStream os = new BufferedOutputStream(raw, 64 * 1024);
                    w.write(os);
                    os.flush();
                }
            }
            ok = true;
        } finally {
            if (ok) {
                ContentValues u = new ContentValues();
                u.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, u, null, null);
            } else {
                cr.delete(uri, null, null);
            }
        }
        return uri;
    }

    static String displayName(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && q.getString(0) != null) {
                return q.getString(0);
            }
        } catch (Exception ignored) {
        }
        return "file";
    }
}
