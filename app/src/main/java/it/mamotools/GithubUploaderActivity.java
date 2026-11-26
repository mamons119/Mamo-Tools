package it.mamotools;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Github uploader: carica un aggiornamento (zip) su un repository GitHub con un solo commit, da telefono o tablet. */
public class GithubUploaderActivity extends Activity {

    private static final int REQ_ZIP = 71;
    private static final long MAX_TOTAL = 120L * 1024 * 1024;

    private static final class Repo {
        GhStore.Acct acct;
        String full, branch;
        boolean priv, canPush;
        String pushed;
    }

    private GhStore store;
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout root, body;
    private final List<GhStore.Acct> accts = new ArrayList<>();
    private final List<Repo> repos = new ArrayList<>();
    private boolean reposLoading = false;
    private String reposError = "";
    private String filter = "";
    private int probeDone = 0, probeTotal = 0;
    private int loadGen = 0;
    private static final Map<String, Long> NEG = new java.util.concurrent.ConcurrentHashMap<>();

    // schermata del repository
    private Repo cur;
    private String curBranch;
    private GhApi.Remote remote;
    private String remoteErr = "";
    private boolean remoteLoading = false;
    private Map<String, byte[]> zipFiles;       // percorsi originali dello zip
    private String zipName = "";
    private String zipRoot = "";                 // cartella radice comune, se c'e'
    private boolean stripRoot = false;
    private boolean publishing = false;
    private String publishMsg = "";
    private String message = "Aggiornamento da Mamo Tools";
    private String doneSha = "";
    private String runText = "";
    private int polls = 0;
    private boolean inRepo = false;
    private String installMsg = "";
    private boolean installing = false;
    private java.io.File pendingApk;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new GhStore(this);
        root = Ui.screen(this, "Github uploader",
                "Carica un aggiornamento (zip) su GitHub con un solo commit, anche da telefono o tablet.");
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        home();
        loadRepos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (inRepo) {
            h.removeCallbacksAndMessages(null);
            inRepo = false;
            home();
            return;
        }
        super.onBackPressed();
    }

    // =========================================================================== schermata iniziale

    private void home() {
        inRepo = false;
        body.removeAllViews();
        accts.clear();
        accts.addAll(store.list());

        body.addView(Ui.section(this, "Chiavi di accesso"));
        LinearLayout kc = Ui.card(this);
        if (accts.isEmpty()) {
            kc.addView(Ui.text(this, "Nessuna chiave. Crea su GitHub un token (Impostazioni → Developer settings → "
                    + "Personal access tokens → Fine-grained tokens) con permesso Contents: lettura e scrittura "
                    + "sul repository, poi incollalo qui. Puoi aggiungere piu' chiavi, anche di account diversi.",
                    12, Ui.DIM, false));
        }
        for (final GhStore.Acct a : accts) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(this, a.label.isEmpty() ? a.login : a.label, 15, Ui.TEXT, true));
            col.addView(Ui.text(this, a.token == null ? "chiave illeggibile: rimuovila e reinseriscila"
                    : "account " + a.login, 12, a.token == null ? Ui.DANGER : Ui.DIM, false));
            row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            row.addView(Ui.btn(this, "Rimuovi", Ui.CARD2, Ui.DANGER, v -> confirmRemove(a)),
                    Ui.lp(this, Ui.WRAP, Ui.dp(this, 38), 8, 0));
            kc.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        }
        kc.addView(Ui.btn(this, "Aggiungi chiave", Ui.ACCENT, 0xFF000000, v -> addKeyDialog()),
                Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_BTN), 0, 12));
        body.addView(kc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 0));

        TextView rs = Ui.section(this, "Repository");
        rs.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 10));
        body.addView(rs);
        if (accts.isEmpty()) {
            body.addView(Ui.text(this, "Aggiungi una chiave per vedere i repository.", 13, Ui.DIM, false));
            return;
        }
        final EditText fe = field("Cerca un repository", false);
        fe.setText(filter);
        fe.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                filter = e.toString().trim().toLowerCase();
                fillRepoList();
            }
        });
        body.addView(fe, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 0));
        repoList = new LinearLayout(this);
        repoList.setOrientation(LinearLayout.VERTICAL);
        body.addView(repoList, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        fillRepoList();
    }

    private LinearLayout repoList;

    private void fillRepoList() {
        if (repoList == null) {
            return;
        }
        repoList.removeAllViews();
        if (reposLoading) {
            repoList.addView(Ui.text(this, probeTotal > 0
                    ? "Verifica dei permessi di scrittura: " + probeDone + "/" + probeTotal + "..."
                    : "Caricamento dei repository...", 13, Ui.DIM, false));
        }
        if (!reposError.isEmpty()) {
            repoList.addView(Ui.text(this, reposError, 12, Ui.DANGER, false));
        }
        java.util.Set<String> pins = store.pins();
        List<Repo> sorted = new ArrayList<>(repos);
        int shown = 0;
        // prima i preferiti, poi gli altri
        for (int pass = 0; pass < 2; pass++) {
            for (Repo r : sorted) {
                boolean pinned = pins.contains(r.acct.id + "|" + r.full);
                if ((pass == 0) != pinned) {
                    continue;
                }
                if (!filter.isEmpty() && !r.full.toLowerCase().contains(filter)) {
                    continue;
                }
                repoList.addView(repoTile(r, pinned));
                shown++;
            }
        }
        if (shown == 0 && !reposLoading && reposError.isEmpty()) {
            repoList.addView(Ui.text(this, "Nessun repository scrivibile trovato con queste chiavi (quelli in sola lettura "
                    + "non vengono mostrati).", 13, Ui.DIM, false));
        }
    }

    private View repoTile(final Repo r, final boolean pinned) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x334DD0E1), Ui.round(this, Ui.CARD, 14), null));
        t.setClickable(true);
        t.setOnClickListener(v -> openRepo(r));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, r.full, 15, Ui.TEXT, true));
        String sub = (r.priv ? "privato" : "pubblico") + " · ramo " + r.branch
                + (r.canPush ? "" : " · sola lettura") + " · chiave " + (r.acct.label.isEmpty() ? r.acct.login : r.acct.label);
        col.addView(Ui.text(this, sub, 11, Ui.DIM, false));
        t.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView pin = Ui.text(this, "★", 26, pinned ? 0xFFFFC107 : 0xFF5C6370, false);
        pin.setGravity(Gravity.CENTER);
        pin.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        pin.setOnClickListener(v -> {
            store.togglePin(r.acct.id + "|" + r.full);
            fillRepoList();
        });
        t.addView(pin, Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        t.addView(Ui.text(this, "›", 24, Ui.DIM, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 6, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private void loadRepos() {
        final List<GhStore.Acct> as = store.list();
        repos.clear();
        reposError = "";
        if (as.isEmpty()) {
            return;
        }
        reposLoading = true;
        probeDone = probeTotal = 0;
        final int gen = ++loadGen;
        fillRepoList();
        new Thread(() -> {
            final List<Repo> all = new ArrayList<>();
            final StringBuilder err = new StringBuilder();
            for (GhStore.Acct a : as) {
                if (a.token == null) {
                    continue;
                }
                try {
                    for (JSONObject o : GhApi.repos(a.token)) {
                        Repo r = new Repo();
                        r.acct = a;
                        r.full = o.optString("full_name");
                        r.branch = o.optString("default_branch", "main");
                        r.priv = o.optBoolean("private");
                        r.pushed = o.optString("pushed_at");
                        JSONObject pm = o.optJSONObject("permissions");
                        r.canPush = pm == null || pm.optBoolean("push", false);
                        if (r.canPush) {   // i repository in sola lettura non servono: non si possono modificare
                            all.add(r);
                        }
                    }
                } catch (Exception e) {
                    err.append("Chiave ").append(a.label.isEmpty() ? a.login : a.label).append(": ")
                            .append(e.getMessage()).append("\n");
                }
            }
            // prova reale del permesso: GitHub elenca anche i repository pubblici non inclusi nella chiave
            final java.util.Set<String> okKeys = store.okRepos();
            final List<Repo> todo = new ArrayList<>();
            final List<Repo> known = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Repo r : all) {
                String key = r.acct.id + "|" + r.full;
                if (okKeys.contains(key)) {
                    known.add(r);
                } else {
                    Long neg = NEG.get(key);
                    if (neg == null || now - neg > 3600_000L) {
                        todo.add(r);
                    }
                }
            }
            runOnUiThread(() -> {
                if (gen != loadGen) {
                    return;
                }
                repos.clear();
                repos.addAll(known);
                probeTotal = todo.size();
                reposError = err.toString().trim();
                reposLoading = !todo.isEmpty();
                fillRepoList();
            });
            if (todo.isEmpty()) {
                return;
            }
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(6);
            for (final Repo r : todo) {
                ex.submit(() -> {
                    boolean ok = false;
                    boolean sure = true;
                    try {
                        ok = GhApi.canWrite(r.acct.token, r.full);
                    } catch (Exception e) {
                        sure = false;
                    }
                    final boolean fok = ok, fsure = sure;
                    String key = r.acct.id + "|" + r.full;
                    if (fok) {
                        store.addOkRepo(key);
                    } else if (fsure) {
                        NEG.put(key, System.currentTimeMillis());
                    }
                    runOnUiThread(() -> {
                        if (gen != loadGen) {
                            return;
                        }
                        probeDone++;
                        if (fok) {
                            repos.add(r);
                        }
                        if (probeDone >= probeTotal) {
                            reposLoading = false;
                        }
                        fillRepoList();
                    });
                });
            }
            ex.shutdown();
        }).start();
    }

    private void confirmRemove(final GhStore.Acct a) {
        final Dialog dd = new Dialog(this);
        dd.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout b = dialogBox();
        b.addView(Ui.text(this, "Rimuovere la chiave?", 18, Ui.TEXT, true));
        TextView m = Ui.text(this, "La chiave \"" + (a.label.isEmpty() ? a.login : a.label)
                + "\" viene cancellata da questo dispositivo. Su GitHub resta valida finche' non la revochi tu.",
                13, Ui.DIM, false);
        m.setPadding(0, Ui.dp(this, 10), 0, 0);
        b.addView(m);
        LinearLayout br = new LinearLayout(this);
        br.addView(Ui.btn(this, "Annulla", Ui.CARD2, Ui.TEXT, v -> dd.dismiss()),
                Ui.lp(this, 0, Ui.dp(this, Ui.H_BTN), 0, 0, 1f));
        br.addView(Ui.btn(this, "Rimuovi", Ui.DANGER, 0xFF000000, v -> {
            dd.dismiss();
            store.remove(a.id);
            loadRepos();
            home();
        }), Ui.lp(this, 0, Ui.dp(this, Ui.H_BTN), 8, 0, 1f));
        b.addView(br, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 16));
        show(dd, b);
    }

    private LinearLayout dialogBox() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.round(this, Ui.CARD, 20));
        b.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 16));
        return b;
    }

    private void show(Dialog d, View content) {
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.6f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), Ui.WRAP);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setHint(hint);
        e.setTextSize(15);
        e.setBackground(Ui.round(this, Ui.CARD2, 12));
        e.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        return e;
    }

    private void addKeyDialog() {
        final Dialog d = new Dialog(this);
        LinearLayout b = dialogBox();
        b.addView(Ui.text(this, "Aggiungi una chiave", 18, Ui.TEXT, true));
        TextView hint = Ui.text(this, "Nome (per riconoscerla) e token generato su GitHub. Il token viene provato "
                + "subito e poi salvato cifrato su questo dispositivo.", 12, Ui.DIM, false);
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 10));
        b.addView(hint);
        final EditText name = field("Nome (per esempio: Mamo Tools)", false);
        final EditText tok = field("Token (github_pat_...)", true);
        b.addView(name, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 0));
        b.addView(tok, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        final TextView st = Ui.text(this, "", 12, Ui.DANGER, false);
        st.setPadding(0, Ui.dp(this, 8), 0, 0);
        b.addView(st);
        LinearLayout br = new LinearLayout(this);
        br.addView(Ui.btn(this, "Annulla", Ui.CARD2, Ui.TEXT, v -> d.dismiss()),
                Ui.lp(this, 0, Ui.dp(this, Ui.H_BTN), 0, 0, 1f));
        final android.widget.Button ok = Ui.btn(this, "Prova e salva", Ui.ACCENT, 0xFF000000, null);
        ok.setOnClickListener(v -> {
            final String t = tok.getText().toString().trim();
            final String nm = name.getText().toString().trim();
            if (t.isEmpty()) {
                st.setText("Incolla il token.");
                return;
            }
            ok.setEnabled(false);
            st.setTextColor(Ui.DIM);
            st.setText("Verifica in corso...");
            new Thread(() -> {
                try {
                    final String login = GhApi.login(t);
                    store.add(nm.isEmpty() ? login : nm, login, t);
                    runOnUiThread(() -> {
                        d.dismiss();
                        Toast.makeText(this, "Chiave salvata: " + login, Toast.LENGTH_SHORT).show();
                        home();
                        loadRepos();
                    });
                } catch (final Exception e) {
                    runOnUiThread(() -> {
                        ok.setEnabled(true);
                        st.setTextColor(Ui.DANGER);
                        st.setText("Non funziona: " + e.getMessage());
                    });
                }
            }).start();
        });
        br.addView(ok, Ui.lp(this, 0, Ui.dp(this, Ui.H_BTN), 8, 0, 1f));
        b.addView(br, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
        show(d, b);
    }

    // =========================================================================== schermata del repository

    private void openRepo(Repo r) {
        cur = r;
        curBranch = r.branch;
        zipFiles = null;
        zipName = "";
        zipRoot = "";
        stripRoot = false;
        doneSha = "";
        runText = "";
        publishMsg = "";
        remote = null;
        inRepo = true;
        loadRemote();
        repoScreen();
    }

    private void loadRemote() {
        remote = null;
        remoteErr = "";
        remoteLoading = true;
        final Repo r = cur;
        final String br = curBranch;
        new Thread(() -> {
            GhApi.Remote rm = null;
            String err = "";
            try {
                rm = GhApi.remote(r.acct.token, r.full, br);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final GhApi.Remote frm = rm;
            final String ferr = err;
            runOnUiThread(() -> {
                if (r != cur || !br.equals(curBranch)) {
                    return;
                }
                remoteLoading = false;
                remote = frm;
                remoteErr = ferr;
                if (zipFiles != null && frm != null) {
                    stripRoot = !zipRoot.isEmpty() && !frm.roots.contains(zipRoot);
                }
                if (inRepo && doneSha.isEmpty()) {
                    repoScreen();
                }
            });
        }).start();
    }

    /** Percorso nel repository per un percorso dello zip, secondo l'opzione "cartella radice". */
    private String target(String p) {
        if (stripRoot && !zipRoot.isEmpty() && p.startsWith(zipRoot + "/")) {
            return p.substring(zipRoot.length() + 1);
        }
        return p;
    }

    private void repoScreen() {
        body.removeAllViews();
        body.addView(Ui.text(this, cur.full, 18, Ui.TEXT, true));
        body.addView(Ui.text(this, (cur.priv ? "privato" : "pubblico") + " · chiave "
                + (cur.acct.label.isEmpty() ? cur.acct.login : cur.acct.label), 12, Ui.DIM, false));

        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.text(this, "Ramo", 12, Ui.DIM, false));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, curBranch, 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        row.addView(Ui.btn(this, "Cambia ramo", Ui.CARD2, Ui.ACCENT, v -> chooseBranch()),
                Ui.lp(this, Ui.WRAP, Ui.dp(this, 38), 8, 0));
        c1.addView(row);
        if (remoteLoading) {
            c1.addView(Ui.text(this, "Lettura del contenuto del repository...", 12, Ui.DIM, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        } else if (!remoteErr.isEmpty()) {
            c1.addView(Ui.text(this, remoteErr, 12, Ui.DANGER, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        } else if (remote != null && remote.truncated) {
            c1.addView(Ui.text(this, "Repository molto grande: il confronto con i file esistenti e' parziale.",
                    12, Ui.DIM, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        }
        if (!cur.canPush) {
            c1.addView(Ui.text(this, "Questa chiave ha accesso in sola lettura: la pubblicazione verra' rifiutata.",
                    12, Ui.DANGER, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        }
        body.addView(c1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.text(this, "Aggiornamento", 15, Ui.ACCENT, true));
        c2.addView(Ui.btn(this, zipFiles == null ? "Scegli lo zip" : "Scegli un altro zip", Ui.ACCENT, 0xFF000000,
                v -> pickZip()), Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_BTN), 0, 10));
        if (zipFiles != null) {
            c2.addView(Ui.text(this, zipName + " · " + zipFiles.size() + " file", 12, Ui.DIM, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
            if (!zipRoot.isEmpty()) {
                final TextView chip = Ui.chip(this, "");
                chip.setText("Rimuovi la cartella «" + zipRoot + "» dai percorsi: " + (stripRoot ? "si" : "no"));
                Ui.styleChip(this, chip, stripRoot);
                chip.setOnClickListener(v -> {
                    stripRoot = !stripRoot;
                    repoScreen();
                });
                c2.addView(chip, Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_CHIP), 0, 8));
            }
            addPreview(c2);
        }
        body.addView(c2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        if (!doneSha.isEmpty() || publishing || !publishMsg.isEmpty()) {
            LinearLayout c3 = Ui.card(this);
            c3.addView(Ui.text(this, publishMsg, 13, doneSha.isEmpty() && !publishing ? Ui.DANGER : Ui.TEXT, false));
            if (!runText.isEmpty()) {
                c3.addView(Ui.text(this, runText, 13, Ui.TEXT, true), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
            }
            if (!doneSha.isEmpty()) {
                LinearLayout lr = new LinearLayout(this);
                lr.addView(Ui.btn(this, "Apri il commit", Ui.CARD2, Ui.ACCENT,
                        v -> openUrl("https://github.com/" + cur.full + "/commit/" + doneSha)),
                        Ui.lp(this, 0, Ui.dp(this, Ui.H_BTN), 0, 0, 1f));
                lr.addView(Ui.btn(this, "Apri le Release", Ui.CARD2, Ui.ACCENT,
                        v -> openUrl("https://github.com/" + cur.full + "/releases/latest")),
                        Ui.lp(this, 0, Ui.dp(this, Ui.H_BTN), 8, 0, 1f));
                c3.addView(lr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
            }
            body.addView(c3, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }

        LinearLayout c4 = Ui.card(this);
        c4.addView(Ui.text(this, "Installa l'app", 15, Ui.ACCENT, true));
        String cv = "";
        try {
            cv = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        c4.addView(Ui.text(this, "Scarica l'APK dell'ultima Release di questo repository e avvia l'installazione. "
                + "Versione installata ora: " + cv + ".", 12, Ui.DIM, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        android.widget.Button ib = Ui.btn(this, installing ? "Download in corso..." : "Scarica e installa",
                Ui.ACCENT, 0xFF000000, v -> downloadInstall());
        ib.setEnabled(!installing);
        c4.addView(ib, Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_BTN), 0, 10));
        if (!installMsg.isEmpty()) {
            c4.addView(Ui.text(this, installMsg, 12, installMsg.startsWith("Non riuscito") ? Ui.DANGER : Ui.TEXT, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        }
        body.addView(c4, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
    }

    // =========================================================================== download e installazione

    private void downloadInstall() {
        if (installing) {
            return;
        }
        installing = true;
        installMsg = "Ricerca dell'ultima release...";
        repoScreen();
        final Repo r = cur;
        new Thread(() -> {
            try {
                final String[] rel = GhApi.latestApk(r.acct.token, r.full);
                java.io.File dir = new java.io.File(getCacheDir(), "shared");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                final java.io.File apk = new java.io.File(dir, "MamoTools-update.apk");
                final String head = rel[0] + " (" + (Long.parseLong(rel[3]) / 1024 / 1024) + " MB) · ";
                GhApi.download(r.acct.token, rel[1], apk, m -> runOnUiThread(() -> {
                    installMsg = head + m;
                    if (inRepo && cur == r) {
                        repoScreen();
                    }
                }));
                runOnUiThread(() -> {
                    installing = false;
                    installMsg = "Scaricato: " + rel[0] + ". Si apre l'installazione di Android.";
                    if (inRepo && cur == r) {
                        repoScreen();
                    }
                    startInstall(apk);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    installing = false;
                    installMsg = "Non riuscito: " + e.getMessage();
                    if (inRepo && cur == r) {
                        repoScreen();
                    }
                });
            }
        }).start();
    }

    private void startInstall(java.io.File apk) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            Ui.infoDialog(this, "Permesso di installazione",
                    "Android richiede di consentire a Mamo Tools di installare app. Nella schermata che si apre "
                            + "attiva \"Consenti da questa fonte\", poi torna qui: l'installazione parte da sola.",
                    () -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(this, "Apri le impostazioni di Android e consenti l'installazione da Mamo Tools.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("content://" + PdfShareProvider.AUTHORITY + "/" + apk.getName()),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            installMsg = "Non riuscito: impossibile avviare l'installazione (" + e.getMessage() + ").";
            repoScreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingApk != null && getPackageManager().canRequestPackageInstalls()) {
            java.io.File f = pendingApk;
            pendingApk = null;
            startInstall(f);
        }
    }

    private int nNew, nMod, nSame;
    private Map<String, byte[]> toSend = new LinkedHashMap<>();

    private void addPreview(LinearLayout c) {
        toSend = new LinkedHashMap<>();
        nNew = nMod = nSame = 0;
        TreeMap<String, String> lines = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : zipFiles.entrySet()) {
            String p = target(e.getKey());
            if (p.isEmpty()) {
                continue;
            }
            String[] old = remote == null ? null : remote.files.get(p);
            if (old == null) {
                nNew++;
                lines.put(p, "+ nuovo   ");
                toSend.put(p, e.getValue());
            } else if (!old[0].equals(GhApi.blobSha(e.getValue()))) {
                nMod++;
                lines.put(p, "~ modificato   ");
                toSend.put(p, e.getValue());
            } else {
                nSame++;
            }
        }
        c.addView(Ui.text(this, nNew + " nuovi · " + nMod + " modificati · " + nSame + " uguali (non vengono inviati)",
                13, Ui.TEXT, true), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : lines.entrySet()) {
            if (n++ >= 60) {
                sb.append("... e altri ").append(lines.size() - 60).append(" file\n");
                break;
            }
            sb.append(e.getValue()).append(e.getKey()).append('\n');
        }
        if (sb.length() > 0) {
            TextView lt = Ui.text(this, sb.toString().trim(), 11, Ui.DIM, false);
            lt.setTypeface(android.graphics.Typeface.MONOSPACE);
            c.addView(lt, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        }
        if (remote == null) {
            c.addView(Ui.text(this, "Attendi la lettura del repository per poter pubblicare.", 12, Ui.DIM, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
            return;
        }
        final EditText me = field("Messaggio del commit", false);
        me.setText(message);
        me.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int cc) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int cc) {
            }

            @Override
            public void afterTextChanged(Editable ed) {
                message = ed.toString();
            }
        });
        c.addView(me, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        android.widget.Button pub = Ui.btn(this, "Pubblica su " + curBranch, Ui.ACCENT, 0xFF000000, v -> publish());
        pub.setEnabled(!toSend.isEmpty() && !publishing && cur.canPush);
        c.addView(pub, Ui.lp(this, Ui.MATCH, Ui.dp(this, 52), 0, 12));
    }

    private void chooseBranch() {
        final Repo r = cur;
        new Thread(() -> {
            List<String> bl;
            try {
                bl = GhApi.branches(r.acct.token, r.full);
            } catch (final Exception e) {
                runOnUiThread(() -> Ui.infoDialog(this, "Rami non disponibili", e.getMessage()));
                return;
            }
            final List<String> fbl = bl;
            runOnUiThread(() -> {
                final Dialog d = new Dialog(this);
                LinearLayout b = dialogBox();
                b.addView(Ui.text(this, "Scegli il ramo", 18, Ui.TEXT, true));
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                for (final String n : fbl) {
                    TextView t = Ui.text(this, (n.equals(curBranch) ? "✓ " : "") + n, 15, Ui.TEXT, false);
                    t.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
                    t.setBackground(Ui.round(this, Ui.CARD2, 10));
                    t.setOnClickListener(v -> {
                        d.dismiss();
                        curBranch = n;
                        doneSha = "";
                        publishMsg = "";
                        loadRemote();
                        repoScreen();
                    });
                    list.addView(t, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
                }
                ScrollView sv = new ScrollView(this);
                sv.addView(list);
                b.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 320)));
                show(d, b);
            });
        }).start();
    }

    private void openUrl(String u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            Toast.makeText(this, "Nessun browser disponibile.", Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================== zip

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                "application/octet-stream"});
        startActivityForResult(i, REQ_ZIP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ZIP || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri u = data.getData();
        final String nm = FileUtil.queryName(this, u);
        publishMsg = "";
        doneSha = "";
        body.removeAllViews();
        body.addView(Ui.text(this, "Lettura dello zip...", 14, Ui.DIM, false));
        new Thread(() -> {
            final Map<String, byte[]> files = new LinkedHashMap<>();
            String err = "";
            try (InputStream is = getContentResolver().openInputStream(u); ZipInputStream zi = new ZipInputStream(is)) {
                ZipEntry ze;
                long total = 0;
                byte[] buf = new byte[65536];
                while ((ze = zi.getNextEntry()) != null) {
                    String name = ze.getName().replace('\\', '/');
                    while (name.startsWith("/")) {
                        name = name.substring(1);
                    }
                    if (ze.isDirectory() || name.isEmpty() || name.startsWith("__MACOSX/") || name.contains("/.git/")
                            || name.startsWith(".git/") || name.endsWith(".DS_Store") || name.contains("../")) {
                        continue;
                    }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    int n;
                    while ((n = zi.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_TOTAL) {
                            throw new java.io.IOException("lo zip e' troppo grande (oltre 120 MB)");
                        }
                        bo.write(buf, 0, n);
                    }
                    files.put(name, bo.toByteArray());
                }
                if (files.isEmpty()) {
                    err = "Lo zip e' vuoto oppure non e' un file zip.";
                }
            } catch (Exception e) {
                err = "Impossibile leggere lo zip: " + e.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> {
                if (!ferr.isEmpty()) {
                    Ui.infoDialog(this, "Zip non valido", ferr);
                    repoScreen();
                    return;
                }
                zipFiles = files;
                zipName = nm == null ? "aggiornamento.zip" : nm;
                // cartella radice comune a tutti i file?
                String root0 = null;
                boolean common = true;
                for (String p : files.keySet()) {
                    int s = p.indexOf('/');
                    if (s < 0) {
                        common = false;
                        break;
                    }
                    String top = p.substring(0, s);
                    if (root0 == null) {
                        root0 = top;
                    } else if (!root0.equals(top)) {
                        common = false;
                        break;
                    }
                }
                zipRoot = common && root0 != null ? root0 : "";
                stripRoot = !zipRoot.isEmpty() && (remote == null || !remote.roots.contains(zipRoot));
                repoScreen();
            });
        }).start();
    }

    // =========================================================================== pubblicazione

    private void publish() {
        if (publishing || toSend.isEmpty() || remote == null) {
            return;
        }
        publishing = true;
        doneSha = "";
        runText = "";
        publishMsg = "Pubblicazione in corso...";
        repoScreen();
        final Repo r = cur;
        final String br = curBranch;
        final GhApi.Remote rm = remote;
        final Map<String, byte[]> files = new LinkedHashMap<>(toSend);
        final String msg = message.trim().isEmpty() ? "Aggiornamento da Mamo Tools" : message.trim();
        new Thread(() -> {
            try {
                final String sha = GhApi.commit(r.acct.token, r.full, br, rm, files, msg, m -> runOnUiThread(() -> {
                    publishMsg = m;
                    if (inRepo && cur == r) {
                        repoScreen();
                    }
                }));
                runOnUiThread(() -> {
                    publishing = false;
                    doneSha = sha;
                    publishMsg = "Commit creato: " + files.size() + (files.size() == 1 ? " file" : " file")
                            + " su " + br + " (" + sha.substring(0, 7) + ").";
                    runText = "Compilazione: in attesa di GitHub...";
                    polls = 0;
                    zipFiles = null;
                    if (inRepo && cur == r) {
                        loadRemote();
                        repoScreen();
                        h.postDelayed(() -> pollRun(r, sha), 6000);
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    publishing = false;
                    publishMsg = "Pubblicazione non riuscita: " + e.getMessage();
                    if (inRepo && cur == r) {
                        repoScreen();
                    }
                });
            }
        }).start();
    }

    private void pollRun(final Repo r, final String sha) {
        if (!inRepo || cur != r || !sha.equals(doneSha)) {
            return;
        }
        new Thread(() -> {
            String txt;
            boolean again = true;
            try {
                String[] run = GhApi.run(r.acct.token, r.full, sha);
                if (run == null) {
                    txt = "Compilazione: in attesa di GitHub...";
                } else if ("completed".equals(run[0])) {
                    again = false;
                    txt = "success".equals(run[1]) ? "Compilazione riuscita: l'APK e' nelle Release (o negli artifact)."
                            : "Compilazione terminata con esito: " + run[1] + ". Apri il commit per i dettagli.";
                } else {
                    txt = "Compilazione in corso (" + run[0] + ")...";
                }
            } catch (Exception e) {
                again = false;
                txt = "Stato della compilazione non disponibile (serve il permesso Actions in lettura): "
                        + e.getMessage();
            }
            final String ft = txt;
            final boolean fa = again;
            runOnUiThread(() -> {
                if (!inRepo || cur != r || !sha.equals(doneSha)) {
                    return;
                }
                runText = ft;
                repoScreen();
                if (fa && ++polls < 100) {
                    h.postDelayed(() -> pollRun(r, sha), 7000);
                }
            });
        }).start();
    }
}
