package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/**
 * Lettore di codice: apre file di testo/sorgenti, li colora in base al linguaggio e, se richiesto,
 * corregge l'indentazione. Si apre anche da "Apri con".
 */
public class CodeReaderActivity extends Activity {

    private static final int REQ_OPEN = 91, REQ_SAVE = 92, MAX_BYTES = 3 * 1024 * 1024;

    private SharedPreferences prefs;
    private WebView web;
    private TextView nameTv, langChip, fixChip, hintTv;
    private LinearLayout bar;
    private String fileName = "", original = "", shown = "";
    private CodeLang lang;
    private boolean fix = true, truncated = false;
    private int textPct = 100, token = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("code", MODE_PRIVATE);
        fix = prefs.getBoolean("fix", true);
        textPct = prefs.getInt("text", 100);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 8));
        nameTv = Ui.text(this, "Leggi Codice", 17, Ui.TEXT, true);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(nameTv);
        hintTv = Ui.text(this, "Apri un file di codice per vederlo colorato e indentato.", 12, Ui.DIM, false);
        top.addView(hintTv);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.btn(this, "Apri", Ui.ACCENT, 0xFF0F1115, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        }), Ui.lp(this, Ui.WRAP, Ui.dp(this, 38), 0, 0));
        langChip = Ui.chip(this, "Linguaggio");
        langChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseLang();
            }
        });
        bar.addView(langChip, Ui.lp(this, Ui.WRAP, Ui.dp(this, 38), 8, 0));
        fixChip = Ui.chip(this, "Correggi indentazione");
        fixChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fix = !fix;
                prefs.edit().putBoolean("fix", fix).apply();
                render();
            }
        });
        bar.addView(fixChip, Ui.lp(this, Ui.WRAP, Ui.dp(this, 38), 8, 0));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(bar);
        top.addView(hs, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));

        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.addView(Ui.btn(this, "A−", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoom(-10);
            }
        }), Ui.lp(this, Ui.dp(this, 52), Ui.dp(this, 36), 0, 0));
        bar2.addView(Ui.btn(this, "A+", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoom(10);
            }
        }), Ui.lp(this, Ui.dp(this, 52), Ui.dp(this, 36), 8, 0));
        bar2.addView(Ui.btn(this, "Salva copia", Ui.CARD2, Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCopy();
            }
        }), Ui.lp(this, Ui.WRAP, Ui.dp(this, 36), 8, 0));
        top.addView(bar2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        root.addView(top, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        web = new WebView(this);
        web.setBackgroundColor(Ui.BG);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(false);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(textPct);
        root.addView(web, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);
        refreshChips();

        Intent in = getIntent();
        Uri u = null;
        if (in != null) {
            u = in.getData();
            if (u == null) {
                u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        }
        if (u != null) {
            load(u);
        }
    }

    private void refreshChips() {
        Ui.styleChip(this, fixChip, fix);
        Ui.styleChip(this, langChip, false);
        langChip.setText(lang == null ? "Linguaggio" : lang.name);
    }

    private void zoom(int d) {
        textPct = Math.max(60, Math.min(220, textPct + d));
        prefs.edit().putInt("text", textPct).apply();
        web.getSettings().setTextZoom(textPct);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN);
    }

    private void chooseLang() {
        final String[] names = new String[CodeLang.ALL.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = CodeLang.ALL.get(i).name;
        }
        new AlertDialog.Builder(this).setTitle("Linguaggio").setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                lang = CodeLang.ALL.get(which);
                render();
            }
        }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (req == REQ_OPEN) {
            load(data.getData());
        } else if (req == REQ_SAVE) {
            writeCopy(data.getData());
        }
    }

    private void load(final Uri u) {
        hintTv.setText("Apertura…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String nm = null, txt = null, err = null;
                boolean cut = false;
                try {
                    try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int k = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (k >= 0) {
                                nm = c.getString(k);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    if (nm == null) {
                        nm = u.getLastPathSegment() == null ? "file" : u.getLastPathSegment();
                    }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (InputStream is = getContentResolver().openInputStream(u)) {
                        byte[] buf = new byte[32 * 1024];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            if (bo.size() + n > MAX_BYTES) {
                                bo.write(buf, 0, MAX_BYTES - bo.size());
                                cut = true;
                                break;
                            }
                            bo.write(buf, 0, n);
                        }
                    }
                    byte[] bytes = bo.toByteArray();
                    for (int i = 0; i < Math.min(bytes.length, 4096); i++) {
                        if (bytes[i] == 0) {
                            throw new Exception("sembra un file binario, non di testo");
                        }
                    }
                    txt = decode(bytes);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String fn = nm, tx = txt, er = err;
                final boolean ct = cut;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (er != null) {
                            hintTv.setText("Impossibile aprire il file.");
                            Toast.makeText(CodeReaderActivity.this, "Errore: " + er, Toast.LENGTH_LONG).show();
                            return;
                        }
                        fileName = fn;
                        original = tx.replace("\r\n", "\n").replace('\r', '\n');
                        if (original.startsWith("﻿")) {
                            original = original.substring(1);
                        }
                        truncated = ct;
                        lang = CodeLang.detect(fn, original);
                        nameTv.setText(fn);
                        render();
                    }
                });
            }
        }).start();
    }

    private static String decode(byte[] b) {
        try {
            return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("ISO-8859-1"));
        }
    }

    private void render() {
        if (lang == null) {
            return;
        }
        refreshChips();
        final int my = ++token;
        final CodeLang l = lang;
        final boolean doFix = fix;
        final String src = original;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String t = doFix && CodeFormatter.supported(l) ? CodeFormatter.format(src, l) : src;
                String body = t.endsWith("\n") ? t.substring(0, t.length() - 1) : t;
                int lines = 1;
                for (int i = 0; i < body.length(); i++) {
                    if (body.charAt(i) == '\n') {
                        lines++;
                    }
                }
                final String page = CodePage.build(CodeHighlighter.highlight(body, l), lines);
                final String shownText = t;
                final int ln = lines;
                final boolean changed = !t.equals(src);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (my != token) {
                            return;
                        }
                        shown = shownText;
                        web.loadDataWithBaseURL(null, page, "text/html", "utf-8", null);
                        String info = ln + " righe";
                        if (!CodeFormatter.supported(l)) {
                            info += " · indentazione automatica non disponibile per " + l.name;
                        } else if (doFix) {
                            info += changed ? " · indentazione corretta" : " · indentazione già corretta";
                        } else {
                            info += " · testo originale";
                        }
                        if (truncated) {
                            info += " · file troncato a 3 MB";
                        }
                        hintTv.setText(info);
                    }
                });
            }
        }).start();
    }

    private void saveCopy() {
        if (shown.isEmpty()) {
            Toast.makeText(this, "Apri prima un file.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, fileName.isEmpty() ? "codice.txt" : fileName);
        startActivityForResult(i, REQ_SAVE);
    }

    private void writeCopy(Uri u) {
        try (OutputStream os = getContentResolver().openOutputStream(u)) {
            os.write(shown.getBytes("UTF-8"));
            Toast.makeText(this, "Copia salvata.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Salvataggio non riuscito: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
