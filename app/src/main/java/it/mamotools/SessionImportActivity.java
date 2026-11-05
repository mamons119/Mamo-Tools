package it.mamotools;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

/** Riceve un file .mamotel (da "Apri con" o condivisione), lo importa tra le sessioni e lo apre. */
public class SessionImportActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        Uri u = in == null ? null : in.getData();
        if (u == null && in != null) {
            u = in.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (u == null) {
            finish();
            return;
        }
        final Uri src = u;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                File dest = null;
                try {
                    dest = importSession(src);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final File d = dest;
                final String e = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (d != null) {
                            startActivity(new Intent(SessionImportActivity.this, SessionViewActivity.class)
                                    .putExtra("path", d.getAbsolutePath()));
                        } else {
                            Toast.makeText(SessionImportActivity.this, "Impossibile aprire la sessione: " + e,
                                    Toast.LENGTH_LONG).show();
                        }
                        finish();
                    }
                });
            }
        }).start();
    }

    private File importSession(Uri src) throws Exception {
        File dir = new File(getFilesDir(), "sessions");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, "import.tmp");
        FileUtil.copyToFile(this, src, tmp);
        long epoch;
        try (InputStream is = new java.io.FileInputStream(tmp);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String first = br.readLine();
            if (first == null) {
                throw new Exception("file vuoto");
            }
            if (first.startsWith("﻿")) {
                first = first.substring(1);
            }
            if (!first.startsWith("#MAMOTEL1,")) {
                throw new Exception("non è una sessione di Mamo Tools");
            }
            String[] p = first.split(",", 3);
            try {
                epoch = Long.parseLong(p[1].trim());
            } catch (Exception e) {
                epoch = System.currentTimeMillis();
            }
        }
        File dest = new File(dir, "s_" + epoch + ".csv");
        if (dest.exists()) {
            // stessa sessione gia' presente: la sostituisce solo se il file ricevuto e' diverso come dimensione
            if (dest.length() == tmp.length()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return dest;
            }
            dest = new File(dir, "s_" + epoch + "_" + System.currentTimeMillis() % 100000 + ".csv");
        }
        if (!tmp.renameTo(dest)) {
            FileUtil.copyFile(tmp, dest);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        SessionData.load(dest); // verifica che sia leggibile
        return dest;
    }
}
