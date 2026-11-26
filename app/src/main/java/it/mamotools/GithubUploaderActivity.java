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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
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
    private int sub = 0;                 // 0 schermata del repository, 1 storico, 2 dettaglio commit
    private int histLimit = 40;
    private List<GhGraph.Row> histRows = new ArrayList<>();
    private String histErr = "";
    private boolean histLoading = false;
    private int histGen = 0;
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
        if (inRepo && sub == 2) {
            sub = 1;
            renderHistory();
            return;
        }
        if (inRepo && sub == 1) {
            sub = 0;
            histGen++;
            repoScreen();
            return;
        }
        if (inRepo) {
            h.removeCallbacksAndMessages(null);
            inRepo = false;
            home();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ elementi grafici comuni

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private void showHeader(boolean on) {
        for (int i = 0; i < 2 && i < root.getChildCount(); i++) {
            root.getChildAt(i).setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    /** Etichetta piccola e colorata (stato, ramo, chiave...). */
    private TextView pill(String t, int fg) {
        TextView p = Ui.text(this, t, 11, fg, true);
        p.setSingleLine(true);
        p.setPadding(dp(8), dp(3), dp(8), dp(3));
        p.setBackground(Ui.round(this, (fg & 0x00FFFFFF) | 0x2A000000, 8));
        return p;
    }

    private LinearLayout pillRow(TextView... ps) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < ps.length; i++) {
            r.addView(ps[i], Ui.lp(this, Ui.WRAP, Ui.WRAP, i == 0 ? 0 : 6, 0));
        }
        return r;
    }

    private static final int[] AV = {0xFF4DD0E1, 0xFFFFB74D, 0xFF81C784, 0xFFBA68C8, 0xFFFF8A80, 0xFF64B5F6};

    /** Cerchio con l'iniziale. */
    private TextView avatar(String name, int sizeDp) {
        int col = AV[Math.abs(name.hashCode()) % AV.length];
        TextView a = Ui.text(this, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ITALY),
                sizeDp / 2 + 1, col, true);
        a.setGravity(Gravity.CENTER);
        a.setBackground(Ui.round(this, (col & 0x00FFFFFF) | 0x26000000, 100));
        a.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return a;
    }

    private TextView roundBtn(String label, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 20, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44FFFFFF), Ui.round(this, Ui.CARD2, 100), null));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** Barra in alto con il tasto indietro, il titolo e un sottotitolo. */
    private LinearLayout topBar(String title, String subtitle) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = roundBtn("‹", v -> onBackPressed());
        back.setTextSize(26);
        r.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = Ui.text(this, title, 20, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = Ui.text(this, subtitle, 12, Ui.DIM, false);
            st.setSingleLine(true);
            st.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(st);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        r.setPadding(0, 0, 0, dp(14));
        return r;
    }

    /** Scheda con intestazione (icona + titolo + azione facoltativa a destra). */
    private LinearLayout card(String icon, String title, String action, View.OnClickListener onAction) {
        LinearLayout c = Ui.card(this);
        LinearLayout h2 = new LinearLayout(this);
        h2.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = Ui.text(this, icon, 16, Ui.TEXT, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(this, Ui.BG, 100));
        h2.addView(ic, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView t = Ui.text(this, title, 15, Ui.TEXT, true);
        t.setPadding(dp(10), 0, 0, 0);
        h2.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        if (action != null) {
            TextView a = Ui.text(this, action, 13, Ui.ACCENT, true);
            a.setPadding(dp(12), dp(6), dp(12), dp(6));
            a.setBackground(Ui.round(this, Ui.CARD2, 10));
            a.setClickable(true);
            a.setOnClickListener(onAction);
            h2.addView(a);
        }
        c.addView(h2);
        return c;
    }

    private android.widget.Button primary(String label, View.OnClickListener l) {
        return Ui.btn(this, label, Ui.ACCENT, 0xFF000000, l);
    }

    private ProgressBar spinner(int sizeDp) {
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        p.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return p;
    }

    private static String fmtSize(long b) {
        if (b < 1024) {
            return b + " B";
        } else if (b < 1024 * 1024) {
            return (b / 1024) + " KB";
        }
        return String.format(Locale.ITALY, "%.1f MB", b / 1048576.0);
    }

    // =========================================================================== schermata iniziale

    private void home() {
        inRepo = false;
        showHeader(true);
        body.removeAllViews();
        accts.clear();
        accts.addAll(store.list());

        LinearLayout kc = card("🔑", "Chiavi di accesso", "+ Aggiungi", v -> addKeyDialog());
        if (accts.isEmpty()) {
            TextView hint = Ui.text(this, "Per cominciare serve un token di GitHub:\n"
                    + "1. github.com → Settings → Developer settings\n"
                    + "2. Personal access tokens → Fine-grained tokens\n"
                    + "3. Scegli i repository e il permesso Contents: lettura e scrittura\n"
                    + "4. Copia il token e premi + Aggiungi.\n"
                    + "Puoi usare più chiavi, anche di account diversi.", 12, Ui.DIM, false);
            hint.setLineSpacing(0, 1.2f);
            kc.addView(hint, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }
        for (final GhStore.Acct a : accts) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String nm = a.label.isEmpty() ? a.login : a.label;
            row.addView(avatar(nm, 36));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);
            col.addView(Ui.text(this, nm, 14, Ui.TEXT, true));
            col.addView(Ui.text(this, a.token == null ? "chiave illeggibile: rimuovila e reinseriscila"
                    : "@" + a.login, 12, a.token == null ? Ui.DANGER : Ui.DIM, false));
            row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            TextView rm = roundBtn("✕", v -> confirmRemove(a));
            rm.setTextSize(14);
            rm.setTextColor(Ui.DANGER);
            row.addView(rm, new LinearLayout.LayoutParams(dp(36), dp(36)));
            kc.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }
        body.addView(kc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 0));
        if (accts.isEmpty()) {
            return;
        }

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        final EditText fe = field("🔍  Cerca un repository", false);
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
        sr.addView(fe, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView rf = roundBtn("↻", v -> {
            NEG.clear();
            loadRepos();
        });
        sr.addView(rf, Ui.lp(this, dp(44), dp(44), 8, 0));
        body.addView(sr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 16));
        repoList = new LinearLayout(this);
        repoList.setOrientation(LinearLayout.VERTICAL);
        body.addView(repoList, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        fillRepoList();
    }

    private LinearLayout repoList;

    private TextView listHeader(String s) {
        TextView t = Ui.text(this, s, 12, Ui.DIM, true);
        t.setPadding(dp(4), dp(10), 0, dp(8));
        return t;
    }

    private void fillRepoList() {
        if (repoList == null) {
            return;
        }
        repoList.removeAllViews();
        if (reposLoading) {
            LinearLayout ld = new LinearLayout(this);
            ld.setGravity(Gravity.CENTER_VERTICAL);
            ld.addView(spinner(18));
            ld.addView(Ui.text(this, probeTotal > 0
                    ? "Verifica dei permessi di scrittura: " + probeDone + "/" + probeTotal
                    : "Caricamento dei repository...", 12, Ui.DIM, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 10, 0));
            ld.setPadding(dp(4), dp(4), 0, dp(8));
            repoList.addView(ld);
        }
        if (!reposError.isEmpty()) {
            TextView er = Ui.text(this, reposError, 12, Ui.DANGER, false);
            er.setPadding(dp(4), 0, 0, dp(8));
            repoList.addView(er);
        }
        java.util.Set<String> pins = store.pins();
        int nPin = 0, nOther = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean headerDone = false;
            for (Repo r : repos) {
                boolean pinned = pins.contains(r.acct.id + "|" + r.full);
                if ((pass == 0) != pinned) {
                    continue;
                }
                if (!filter.isEmpty() && !r.full.toLowerCase().contains(filter)) {
                    continue;
                }
                if (!headerDone) {
                    headerDone = true;
                    if (pass == 0) {
                        repoList.addView(listHeader("★  PREFERITI"));
                    } else if (nPin > 0) {
                        repoList.addView(listHeader("TUTTI I REPOSITORY"));
                    }
                }
                repoList.addView(repoTile(r, pinned));
                if (pass == 0) {
                    nPin++;
                } else {
                    nOther++;
                }
            }
        }
        if (nPin + nOther == 0 && !reposLoading && reposError.isEmpty()) {
            TextView em = Ui.text(this, filter.isEmpty()
                    ? "Nessun repository scrivibile trovato con queste chiavi (quelli in sola lettura non vengono mostrati)."
                    : "Nessun repository corrisponde alla ricerca.", 13, Ui.DIM, false);
            em.setPadding(dp(4), dp(8), 0, 0);
            repoList.addView(em);
        }
    }

    private View repoTile(final Repo r, final boolean pinned) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), dp(12), dp(8), dp(12));
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x334DD0E1), Ui.round(this, Ui.CARD, 16), null));
        t.setClickable(true);
        t.setOnClickListener(v -> openRepo(r));
        String owner = r.full.contains("/") ? r.full.substring(0, r.full.indexOf('/')) : "";
        String name = r.full.contains("/") ? r.full.substring(r.full.indexOf('/') + 1) : r.full;
        t.addView(avatar(name, 42));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(4), 0);
        col.addView(Ui.text(this, owner, 11, Ui.DIM, false));
        TextView nm = Ui.text(this, name, 16, Ui.TEXT, true);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(nm);
        LinearLayout pr = pillRow(pill(r.priv ? "🔒 privato" : "pubblico", r.priv ? 0xFFFFB74D : 0xFF81C784),
                pill("⎇ " + r.branch, Ui.ACCENT));
        if (accts.size() > 1) {
            pr.addView(pill(r.acct.label.isEmpty() ? r.acct.login : r.acct.label, Ui.DIM), Ui.lp(this, Ui.WRAP, Ui.WRAP, 6, 0));
        }
        col.addView(pr, Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 6));
        t.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView pin = Ui.text(this, "★", 26, pinned ? 0xFFFFC107 : 0xFF5C6370, false);
        pin.setGravity(Gravity.CENTER);
        pin.setPadding(dp(10), dp(6), dp(10), dp(6));
        pin.setOnClickListener(v -> {
            store.togglePin(r.acct.id + "|" + r.full);
            fillRepoList();
        });
        t.addView(pin);
        t.addView(Ui.text(this, "›", 24, Ui.DIM, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = dp(8);
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
        sub = 0;
        histRows = new ArrayList<>();
        histLimit = 40;
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

    private TextView quick(String icon, String label, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        TextView t = Ui.text(this, icon + "\n" + label, 12, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(4), dp(12), dp(4), dp(10));
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x334DD0E1), Ui.round(this, Ui.CARD, 14), null));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private void repoScreen() {
        if (sub != 0) {
            return;   // si sta guardando lo storico: la schermata del repository si ridisegna al ritorno
        }
        showHeader(false);
        body.removeAllViews();
        String owner = cur.full.contains("/") ? cur.full.substring(0, cur.full.indexOf('/')) : "";
        String name = cur.full.contains("/") ? cur.full.substring(cur.full.indexOf('/') + 1) : cur.full;
        body.addView(topBar(name, owner + (accts.size() > 1 ? " · chiave "
                + (cur.acct.label.isEmpty() ? cur.acct.login : cur.acct.label) : "")));

        // azioni rapide
        LinearLayout qa = new LinearLayout(this);
        TextView[] qs = {
                quick("🕘", "Storico", v -> openHistory()),
                quick("🚀", "Release", v -> openUrl("https://github.com/" + cur.full + "/releases/latest")),
                quick("↗", "GitHub", v -> openUrl("https://github.com/" + cur.full)),
                quick("📲", installing ? "Scarico..." : "Installa", v -> downloadInstall())};
        for (int i = 0; i < qs.length; i++) {
            qa.addView(qs[i], Ui.lp(this, 0, Ui.WRAP, i == 0 ? 0 : 8, 0, 1f));
        }
        body.addView(qa);

        // ramo
        LinearLayout c1 = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView bi = Ui.text(this, "⎇", 18, Ui.ACCENT, true);
        bi.setGravity(Gravity.CENTER);
        bi.setBackground(Ui.round(this, Ui.BG, 100));
        row.addView(bi, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout bc = new LinearLayout(this);
        bc.setOrientation(LinearLayout.VERTICAL);
        bc.setPadding(dp(12), 0, 0, 0);
        bc.addView(Ui.text(this, "Ramo di destinazione", 11, Ui.DIM, false));
        bc.addView(Ui.text(this, curBranch, 16, Ui.TEXT, true));
        row.addView(bc, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView chg = Ui.text(this, "Cambia", 13, Ui.ACCENT, true);
        chg.setPadding(dp(14), dp(8), dp(14), dp(8));
        chg.setBackground(Ui.round(this, Ui.CARD2, 10));
        chg.setClickable(true);
        chg.setOnClickListener(v -> chooseBranch());
        row.addView(chg);
        c1.addView(row);
        if (remoteLoading) {
            LinearLayout ld = new LinearLayout(this);
            ld.setGravity(Gravity.CENTER_VERTICAL);
            ld.addView(spinner(16));
            ld.addView(Ui.text(this, "Lettura del contenuto del repository...", 12, Ui.DIM, false),
                    Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
            c1.addView(ld, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        } else if (!remoteErr.isEmpty()) {
            c1.addView(Ui.text(this, remoteErr, 12, Ui.DANGER, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        } else if (remote != null && remote.truncated) {
            c1.addView(Ui.text(this, "Repository molto grande: il confronto con i file esistenti è parziale.",
                    12, Ui.DIM, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
        body.addView(c1, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        // 1. scelta dello zip
        LinearLayout c2 = card("📦", "Aggiornamento", zipFiles == null ? null : "Cambia", v -> pickZip());
        if (zipFiles == null) {
            TextView drop = Ui.text(this, "Tocca per scegliere lo zip\nviene scompattato e confrontato col repository",
                    13, Ui.ACCENT, true);
            drop.setGravity(Gravity.CENTER);
            drop.setLineSpacing(0, 1.2f);
            drop.setPadding(dp(12), dp(28), dp(12), dp(28));
            drop.setBackground(Ui.round(this, Ui.CARD2, 14));
            drop.setClickable(true);
            drop.setOnClickListener(v -> pickZip());
            c2.addView(drop, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        } else {
            long tot = 0;
            for (byte[] bb : zipFiles.values()) {
                tot += bb.length;
            }
            TextView zn = Ui.text(this, zipName, 14, Ui.TEXT, true);
            zn.setSingleLine(true);
            zn.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            c2.addView(zn, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            c2.addView(Ui.text(this, zipFiles.size() + " file · " + fmtSize(tot), 12, Ui.DIM, false));
            addPreview(c2);
        }
        body.addView(c2, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        // esito della pubblicazione e della compilazione
        if (!doneSha.isEmpty() || publishing || !publishMsg.isEmpty()) {
            boolean failed = doneSha.isEmpty() && !publishing;
            LinearLayout c3 = card(failed ? "⚠" : publishing ? "⏳" : "✅",
                    failed ? "Pubblicazione non riuscita" : publishing ? "Pubblicazione" : "Commit creato", null, null);
            LinearLayout st = new LinearLayout(this);
            st.setGravity(Gravity.CENTER_VERTICAL);
            if (publishing) {
                st.addView(spinner(18));
            }
            TextView pm = Ui.text(this, publishMsg, 13, failed ? Ui.DANGER : Ui.TEXT, false);
            st.addView(pm, Ui.lp(this, Ui.MATCH, Ui.WRAP, publishing ? 10 : 0, 0));
            c3.addView(st, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            if (!runText.isEmpty()) {
                int col = runText.startsWith("Compilazione riuscita") ? Ui.OK
                        : runText.contains("esito") || runText.startsWith("Stato") ? Ui.DANGER : 0xFFFFB74D;
                LinearLayout rl = new LinearLayout(this);
                rl.setGravity(Gravity.CENTER_VERTICAL);
                rl.setBackground(Ui.round(this, (col & 0x00FFFFFF) | 0x1F000000, 12));
                rl.setPadding(dp(12), dp(10), dp(12), dp(10));
                if (col == 0xFFFFB74D) {
                    rl.addView(spinner(16));
                } else {
                    rl.addView(Ui.text(this, col == Ui.OK ? "●" : "●", 14, col, true));
                }
                rl.addView(Ui.text(this, runText, 13, Ui.TEXT, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 10, 0));
                c3.addView(rl, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            }
            if (!doneSha.isEmpty()) {
                LinearLayout lr = new LinearLayout(this);
                lr.addView(Ui.btn(this, "Apri il commit", Ui.CARD2, Ui.ACCENT,
                        v -> openUrl("https://github.com/" + cur.full + "/commit/" + doneSha)),
                        Ui.lp(this, 0, dp(Ui.H_BTN), 0, 0, 1f));
                lr.addView(Ui.btn(this, "Scarica e installa", Ui.ACCENT, 0xFF000000, v -> downloadInstall()),
                        Ui.lp(this, 0, dp(Ui.H_BTN), 8, 0, 1f));
                c3.addView(lr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
            }
            body.addView(c3, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }

        // installazione dell'app
        if (installing || !installMsg.isEmpty()) {
            boolean bad = installMsg.startsWith("Non riuscito");
            LinearLayout c4 = card(bad ? "⚠" : "📲", "Installazione dell'app", null, null);
            String cv = "";
            try {
                cv = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            LinearLayout il = new LinearLayout(this);
            il.setGravity(Gravity.CENTER_VERTICAL);
            if (installing) {
                il.addView(spinner(18));
            }
            il.addView(Ui.text(this, installMsg, 13, bad ? Ui.DANGER : Ui.TEXT, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, installing ? 10 : 0, 0));
            c4.addView(il, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            c4.addView(Ui.text(this, "Versione installata ora: " + cv, 11, Ui.DIM, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
            body.addView(c4, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }
    }

    // =========================================================================== storico dei commit

    private void openHistory() {
        sub = 1;
        loadHistory();
    }

    private void loadHistory() {
        histLoading = true;
        histErr = "";
        final int gen = ++histGen;
        final Repo r = cur;
        final String br = curBranch;
        final int limit = histLimit;
        renderHistory();
        new Thread(() -> {
            String err = "";
            List<GhGraph.Row> rows = new ArrayList<>();
            try {
                List<String[]> heads = GhApi.branchHeads(r.acct.token, r.full);
                // il ramo aperto per primo, poi gli altri (al massimo 20 in tutto)
                final List<String[]> pick = new ArrayList<>();
                for (String[] hd : heads) {
                    if (hd[0].equals(br)) {
                        pick.add(hd);
                    }
                }
                for (String[] hd : heads) {
                    if (!hd[0].equals(br) && pick.size() < 20) {
                        pick.add(hd);
                    }
                }
                final Map<String, GhApi.Cm> all = new java.util.concurrent.ConcurrentHashMap<>();
                final List<String> errs = Collections.synchronizedList(new ArrayList<String>());
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(4);
                for (final String[] hd : pick) {
                    ex.submit(() -> {
                        try {
                            for (GhApi.Cm c : GhApi.commits(r.acct.token, r.full, hd[0], Math.min(100, limit))) {
                                all.putIfAbsent(c.sha, c);
                            }
                        } catch (Exception e) {
                            errs.add(hd[0] + ": " + e.getMessage());
                        }
                    });
                }
                ex.shutdown();
                ex.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS);
                if (all.isEmpty()) {
                    throw new java.io.IOException(errs.isEmpty() ? "nessun commit trovato" : errs.get(0));
                }
                for (String[] hd : heads) {
                    GhApi.Cm c = all.get(hd[1]);
                    if (c != null) {
                        c.refs.add(hd[0]);
                    }
                }
                rows = GhGraph.layout(new java.util.HashMap<>(all));
            } catch (Exception e) {
                err = e.getMessage() == null ? "errore sconosciuto" : e.getMessage();
            }
            final String ferr = err;
            final List<GhGraph.Row> frows = rows;
            runOnUiThread(() -> {
                if (gen != histGen) {
                    return;
                }
                histLoading = false;
                histErr = ferr;
                histRows = frows;
                if (sub == 1) {
                    renderHistory();
                }
            });
        }).start();
    }

    private static String when(long t) {
        return new java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale.ITALY).format(new java.util.Date(t));
    }

    private static String firstLine(String m) {
        int i = m.indexOf('\n');
        return (i < 0 ? m : m.substring(0, i)).trim();
    }

    private void renderHistory() {
        body.removeAllViews();
        showHeader(false);
        body.addView(topBar("Storico dei commit", cur.full + " · tutti i rami"));
        if (histLoading) {
            body.addView(Ui.text(this, "Caricamento dei commit...", 13, Ui.DIM, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
            return;
        }
        if (!histErr.isEmpty()) {
            body.addView(Ui.text(this, "Non riuscito: " + histErr, 13, Ui.DANGER, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
            return;
        }
        final int lanes = GhGraph.maxLanes(histRows);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.round(this, Ui.CARD, 16));
        list.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        for (final GhGraph.Row r : histRows) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x334DD0E1), null, null));
            row.setOnClickListener(v -> openCommit(r.c));
            row.addView(new GhGraph.RowView(this, r, lanes),
                    new LinearLayout.LayoutParams(GhGraph.RowView.widthFor(this, lanes), Ui.dp(this, 62)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(this, 6), 0, 0, 0);
            StringBuilder refs = new StringBuilder();
            for (String rf : r.c.refs) {
                refs.append("[").append(rf).append("] ");
            }
            TextView t1 = Ui.text(this, refs + firstLine(r.c.msg), 14, Ui.TEXT, true);
            t1.setMaxLines(2);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t1);
            TextView t2 = Ui.text(this, r.c.sha.substring(0, 7) + " · " + r.c.author + " · " + when(r.c.time),
                    11, Ui.DIM, false);
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t2);
            row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            list.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        body.addView(list, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        if (histLimit < 100) {
            body.addView(Ui.btn(this, "Carica altri commit", Ui.CARD2, Ui.ACCENT, v -> {
                histLimit = Math.min(100, histLimit + 40);
                loadHistory();
            }), Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_BTN), 0, 12));
        }
    }

    private void openCommit(final GhApi.Cm c) {
        sub = 2;
        body.removeAllViews();
        showHeader(false);
        body.addView(topBar("Commit " + c.sha.substring(0, 7), c.author + " · " + when(c.time)));
        LinearLayout mc = Ui.card(this);
        mc.addView(Ui.text(this, c.msg.trim(), 14, Ui.TEXT, false));
        body.addView(mc, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 0));
        final TextView st = Ui.text(this, "Caricamento dei file...", 13, Ui.DIM, false);
        body.addView(st, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        final Repo r = cur;
        new Thread(() -> {
            final List<String[]> files = new ArrayList<>();
            String err = "";
            try {
                GhApi.commitFiles(r.acct.token, r.full, c.sha, files);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> {
                if (sub != 2) {
                    return;
                }
                if (!ferr.isEmpty()) {
                    st.setText("Non riuscito: " + ferr);
                    st.setTextColor(Ui.DANGER);
                    return;
                }
                st.setText(files.size() + (files.size() == 1 ? " file modificato" : " file modificati"));
                LinearLayout card = Ui.card(this);
                for (String[] f : files) {
                    String s = f[0];
                    String sym = "added".equals(s) ? "+" : "removed".equals(s) ? "−" : "renamed".equals(s) ? "→" : "~";
                    int col = "added".equals(s) ? Ui.OK : "removed".equals(s) ? Ui.DANGER : Ui.ACCENT;
                    LinearLayout line = new LinearLayout(this);
                    line.addView(Ui.text(this, sym, 14, col, true), new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.WRAP));
                    TextView nm = Ui.text(this, f[1], 12, Ui.TEXT, false);
                    nm.setTypeface(android.graphics.Typeface.MONOSPACE);
                    line.addView(nm, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                    card.addView(line, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
                }
                body.addView(card, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
            });
        }).start();
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
    private boolean showAllFiles = false;
    private Map<String, byte[]> toSend = new LinkedHashMap<>();

    private LinearLayout stat(String num, String label, int col) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(10), dp(4), dp(10));
        b.setBackground(Ui.round(this, (col & 0x00FFFFFF) | 0x22000000, 12));
        TextView n = Ui.text(this, num, 22, col, true);
        n.setGravity(Gravity.CENTER);
        b.addView(n);
        TextView l = Ui.text(this, label, 11, Ui.DIM, false);
        l.setGravity(Gravity.CENTER);
        b.addView(l);
        return b;
    }

    private void addPreview(LinearLayout c) {
        toSend = new LinkedHashMap<>();
        nNew = nMod = nSame = 0;
        TreeMap<String, Integer> lines = new TreeMap<>();   // percorso -> 0 nuovo, 1 modificato
        for (Map.Entry<String, byte[]> e : zipFiles.entrySet()) {
            String p = target(e.getKey());
            if (p.isEmpty()) {
                continue;
            }
            String[] old = remote == null ? null : remote.files.get(p);
            if (old == null) {
                nNew++;
                lines.put(p, 0);
                toSend.put(p, e.getValue());
            } else if (!old[0].equals(GhApi.blobSha(e.getValue()))) {
                nMod++;
                lines.put(p, 1);
                toSend.put(p, e.getValue());
            } else {
                nSame++;
            }
        }
        if (!zipRoot.isEmpty()) {
            LinearLayout sw = new LinearLayout(this);
            sw.setGravity(Gravity.CENTER_VERTICAL);
            TextView sl = Ui.text(this, "Togli la cartella «" + zipRoot + "» dai percorsi", 13, Ui.TEXT, false);
            sw.addView(sl, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            android.widget.Switch sc = new android.widget.Switch(this);
            sc.setChecked(stripRoot);
            sc.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            sc.setTrackTintList(android.content.res.ColorStateList.valueOf(0x664DD0E1));
            sc.setOnCheckedChangeListener((bv, on) -> {
                stripRoot = on;
                repoScreen();
            });
            sw.addView(sc);
            c.addView(sw, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        }
        LinearLayout st = new LinearLayout(this);
        st.addView(stat("+" + nNew, "nuovi", Ui.OK), Ui.lp(this, 0, Ui.WRAP, 0, 0, 1f));
        st.addView(stat("~" + nMod, "modificati", Ui.ACCENT), Ui.lp(this, 0, Ui.WRAP, 8, 0, 1f));
        st.addView(stat("=" + nSame, "invariati", Ui.DIM), Ui.lp(this, 0, Ui.WRAP, 8, 0, 1f));
        c.addView(st, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 14));
        if (!lines.isEmpty()) {
            int limit = showAllFiles ? Integer.MAX_VALUE : 8;
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackground(Ui.round(this, Ui.BG, 12));
            list.setPadding(dp(12), dp(8), dp(12), dp(8));
            int n = 0;
            for (Map.Entry<String, Integer> e : lines.entrySet()) {
                if (n++ >= limit) {
                    break;
                }
                LinearLayout line = new LinearLayout(this);
                line.addView(Ui.text(this, e.getValue() == 0 ? "+" : "~", 13, e.getValue() == 0 ? Ui.OK : Ui.ACCENT, true),
                        new LinearLayout.LayoutParams(dp(18), Ui.WRAP));
                TextView pn = Ui.text(this, e.getKey(), 11, Ui.TEXT, false);
                pn.setTypeface(android.graphics.Typeface.MONOSPACE);
                line.addView(pn, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                list.addView(line, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
            }
            if (lines.size() > 8) {
                TextView more = Ui.text(this, showAllFiles ? "Mostra meno" : "Mostra tutti i " + lines.size() + " file",
                        12, Ui.ACCENT, true);
                more.setPadding(0, dp(8), 0, dp(4));
                more.setClickable(true);
                more.setOnClickListener(v -> {
                    showAllFiles = !showAllFiles;
                    repoScreen();
                });
                list.addView(more);
            }
            c.addView(list, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        } else {
            c.addView(Ui.text(this, "Nessuna differenza: i file dello zip sono già nel repository.", 12, Ui.DIM, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
        if (remote == null) {
            c.addView(Ui.text(this, "Attendi la lettura del repository per poter pubblicare.", 12, Ui.DIM, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
            return;
        }
        c.addView(Ui.text(this, "Messaggio del commit", 11, Ui.DIM, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 16));
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
        c.addView(me, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        android.widget.Button pub = primary(toSend.isEmpty() ? "Niente da pubblicare"
                : "Pubblica " + toSend.size() + (toSend.size() == 1 ? " file" : " file") + " su " + curBranch, v -> publish());
        pub.setTextSize(15);
        pub.setEnabled(!toSend.isEmpty() && !publishing && cur.canPush);
        c.addView(pub, Ui.lp(this, Ui.MATCH, dp(52), 0, 12));
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
