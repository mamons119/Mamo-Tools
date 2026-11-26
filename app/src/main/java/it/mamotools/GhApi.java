package it.mamotools;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Piccolo client dell'API REST di GitHub (solo HttpURLConnection). Tutte le chiamate sono bloccanti. */
final class GhApi {

    static final class ApiError extends IOException {
        final int code;

        ApiError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    interface Prog {
        void on(String msg);
    }

    static final class Remote {
        String commitSha, treeSha;
        boolean truncated;
        /** percorso -> {sha, modo} */
        final Map<String, String[]> files = new HashMap<>();
        final Set<String> roots = new HashSet<>();
    }

    private static final String BASE = "https://api.github.com";

    private GhApi() {
    }

    static String call(String token, String method, String path, String body) throws IOException {
        String url = path.startsWith("http") ? path : BASE + path;
        boolean override = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(20000);
                c.setReadTimeout(90000);
                try {
                    c.setRequestMethod(override ? "POST" : method);
                } catch (ProtocolException e) {
                    override = true;
                    continue;
                }
                if (override) {
                    c.setRequestProperty("X-HTTP-Method-Override", method);
                }
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                c.setRequestProperty("User-Agent", "MamoTools");
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(b.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(b);
                    }
                }
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String txt = is == null ? "" : read(is);
                if (code >= 400) {
                    String m = txt;
                    try {
                        m = new JSONObject(txt).optString("message", txt);
                    } catch (Exception ignored) {
                    }
                    throw new ApiError(code, explain(code, m));
                }
                return txt;
            } catch (ProtocolException e) {
                if (override) {
                    throw e;
                }
                override = true;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("richiesta non riuscita");
    }

    private static String explain(int code, String m) {
        switch (code) {
            case 401:
                return "chiave non valida o scaduta (" + m + ")";
            case 403:
                return "permesso negato: la chiave non ha il permesso necessario (" + m + ")";
            case 404:
                return "non trovato, oppure la chiave non ha accesso a questo repository (" + m + ")";
            case 409:
                return "conflitto: il repository e' vuoto o e' cambiato nel frattempo (" + m + ")";
            case 422:
                return "GitHub ha rifiutato la richiesta (" + m + ")";
            default:
                return "errore " + code + ": " + m;
        }
    }

    private static String read(InputStream is) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = is.read(b)) > 0) {
            bo.write(b, 0, n);
        }
        is.close();
        return bo.toString("UTF-8");
    }

    // ------------------------------------------------------------------ elenchi

    /** Nome utente a cui appartiene la chiave (serve anche a verificarla). */
    static String login(String token) throws IOException {
        try {
            return new JSONObject(call(token, "GET", "/user", null)).getString("login");
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
    }

    /** Repository raggiungibili con la chiave. */
    static List<JSONObject> repos(String token) throws IOException {
        List<JSONObject> out = new ArrayList<>();
        try {
            for (int page = 1; page <= 10; page++) {
                JSONArray a = new JSONArray(call(token, "GET",
                        "/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member&page=" + page,
                        null));
                for (int i = 0; i < a.length(); i++) {
                    out.add(a.getJSONObject(i));
                }
                if (a.length() < 100) {
                    break;
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
        return out;
    }

    /**
     * Prova reale del permesso di scrittura: crea il file vuoto (identico in ogni repository, quindi senza effetti
     * visibili). Una chiave in sola lettura, o che non include il repository, viene rifiutata da GitHub.
     */
    static boolean canWrite(String token, String full) throws IOException {
        try {
            call(token, "POST", "/repos/" + full + "/git/blobs", "{\"content\":\"\",\"encoding\":\"utf-8\"}");
            return true;
        } catch (ApiError e) {
            return false;
        }
    }

    static List<String> branches(String token, String full) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(call(token, "GET", "/repos/" + full + "/branches?per_page=100", null));
            for (int i = 0; i < a.length(); i++) {
                out.add(a.getJSONObject(i).getString("name"));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
        return out;
    }

    // ------------------------------------------------------------------ contenuto remoto

    static Remote remote(String token, String full, String branch) throws IOException {
        Remote r = new Remote();
        try {
            JSONObject ref = new JSONObject(call(token, "GET", "/repos/" + full + "/git/ref/heads/" + branch, null));
            r.commitSha = ref.getJSONObject("object").getString("sha");
            JSONObject cm = new JSONObject(call(token, "GET", "/repos/" + full + "/git/commits/" + r.commitSha, null));
            r.treeSha = cm.getJSONObject("tree").getString("sha");
            JSONObject tr = new JSONObject(call(token, "GET", "/repos/" + full + "/git/trees/" + r.treeSha + "?recursive=1", null));
            r.truncated = tr.optBoolean("truncated", false);
            JSONArray ta = tr.getJSONArray("tree");
            for (int i = 0; i < ta.length(); i++) {
                JSONObject e = ta.getJSONObject(i);
                String p = e.getString("path");
                int s = p.indexOf('/');
                r.roots.add(s < 0 ? p : p.substring(0, s));
                if ("blob".equals(e.optString("type"))) {
                    r.files.put(p, new String[]{e.getString("sha"), e.optString("mode", "100644")});
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
        return r;
    }

    /** Identificatore che git assegna a un file (SHA-1 di "blob <n>\0" + contenuto). */
    static String blobSha(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + data.length + "\0").getBytes(StandardCharsets.UTF_8));
            md.update(data);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ commit

    /** Crea un solo commit con tutti i file indicati (percorso -> contenuto). Restituisce lo SHA del commit. */
    static String commit(String token, String full, String branch, Remote base, Map<String, byte[]> files,
                         String message, Prog p) throws IOException {
        try {
            JSONArray entries = new JSONArray();
            int n = 0;
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                n++;
                p.on("Invio dei file " + n + "/" + files.size() + "...");
                JSONObject bl = new JSONObject();
                bl.put("content", Base64.encodeToString(e.getValue(), Base64.NO_WRAP));
                bl.put("encoding", "base64");
                String sha = new JSONObject(call(token, "POST", "/repos/" + full + "/git/blobs", bl.toString()))
                        .getString("sha");
                String[] old = base.files.get(e.getKey());
                entries.put(new JSONObject().put("path", e.getKey()).put("mode", old != null ? old[1] : "100644")
                        .put("type", "blob").put("sha", sha));
            }
            p.on("Creazione del commit...");
            String tree = new JSONObject(call(token, "POST", "/repos/" + full + "/git/trees",
                    new JSONObject().put("base_tree", base.treeSha).put("tree", entries).toString())).getString("sha");
            String commit = new JSONObject(call(token, "POST", "/repos/" + full + "/git/commits",
                    new JSONObject().put("message", message).put("tree", tree)
                            .put("parents", new JSONArray().put(base.commitSha)).toString())).getString("sha");
            p.on("Aggiornamento del ramo...");
            call(token, "PATCH", "/repos/" + full + "/git/refs/heads/" + branch,
                    new JSONObject().put("sha", commit).toString());
            return commit;
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
    }

    // ------------------------------------------------------------------ storico

    static final class Cm {
        String sha, msg, author;
        long time;
        final List<String> parents = new ArrayList<>();
        final List<String> refs = new ArrayList<>();   // rami che puntano a questo commit
    }

    private static Cm parseCm(JSONObject o) throws org.json.JSONException {
        Cm c = new Cm();
        c.sha = o.getString("sha");
        JSONObject cm = o.getJSONObject("commit");
        c.msg = cm.optString("message", "");
        JSONObject au = cm.optJSONObject("author");
        c.author = au == null ? "" : au.optString("name", "");
        String d = au == null ? "" : au.optString("date", "");
        JSONObject cu = cm.optJSONObject("committer");
        if (cu != null && !cu.optString("date", "").isEmpty()) {
            d = cu.optString("date");
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            c.time = f.parse(d).getTime();
        } catch (Exception e) {
            c.time = 0;
        }
        JSONArray ps = o.optJSONArray("parents");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            c.parents.add(ps.getJSONObject(i).getString("sha"));
        }
        return c;
    }

    /** Nome e commit di punta di ogni ramo. */
    static List<String[]> branchHeads(String token, String full) throws IOException {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(call(token, "GET", "/repos/" + full + "/branches?per_page=100", null));
            for (int i = 0; i < a.length(); i++) {
                JSONObject b = a.getJSONObject(i);
                out.add(new String[]{b.getString("name"), b.getJSONObject("commit").getString("sha")});
            }
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
        return out;
    }

    /** Ultimi commit raggiungibili da un ramo (o da uno SHA). */
    static List<Cm> commits(String token, String full, String ref, int perPage) throws IOException {
        List<Cm> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(call(token, "GET", "/repos/" + full + "/commits?sha="
                    + java.net.URLEncoder.encode(ref, "UTF-8") + "&per_page=" + perPage, null));
            for (int i = 0; i < a.length(); i++) {
                out.add(parseCm(a.getJSONObject(i)));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
        return out;
    }

    /** Dettaglio di un commit: nomi dei file modificati come {stato, nome}. */
    static Cm commitFiles(String token, String full, String sha, List<String[]> files) throws IOException {
        try {
            JSONObject o = new JSONObject(call(token, "GET", "/repos/" + full + "/commits/" + sha, null));
            JSONArray fs = o.optJSONArray("files");
            for (int i = 0; fs != null && i < fs.length(); i++) {
                JSONObject f = fs.getJSONObject(i);
                files.add(new String[]{f.optString("status", "modified"), f.optString("filename")});
            }
            return parseCm(o);
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
    }

    /** Ultima release con un APK: {etichetta, url API dell'APK, nome file, dimensione}. */
    static String[] latestApk(String token, String full) throws IOException {
        try {
            JSONObject o = new JSONObject(call(token, "GET", "/repos/" + full + "/releases/latest", null));
            JSONArray as = o.optJSONArray("assets");
            for (int i = 0; as != null && i < as.length(); i++) {
                JSONObject a = as.getJSONObject(i);
                if (a.optString("name").toLowerCase().endsWith(".apk")) {
                    return new String[]{o.optString("name", o.optString("tag_name")), a.getString("url"),
                            a.getString("name"), String.valueOf(a.optLong("size"))};
                }
            }
            throw new IOException("l'ultima release non contiene un file APK");
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        } catch (ApiError e) {
            if (e.code == 404) {
                throw new IOException("nessuna release trovata: serve il workflow aggiornato che pubblica l'APK nelle Release");
            }
            throw e;
        }
    }

    /** Scarica un file di una release (con un solo reindirizzamento, senza mandare la chiave all'altro sito). */
    static void download(String token, String assetUrl, java.io.File out, Prog p) throws IOException {
        String url = assetUrl;
        boolean auth = true;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Accept", "application/octet-stream");
                c.setRequestProperty("User-Agent", "MamoTools");
                if (auth) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) {
                        throw new IOException("reindirizzamento non valido");
                    }
                    url = loc;
                    auth = false;
                    continue;
                }
                if (code >= 400) {
                    throw new ApiError(code, explain(code, "download non riuscito"));
                }
                long total = c.getContentLengthLong();
                try (InputStream is = c.getInputStream(); java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] b = new byte[65536];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = is.read(b)) > 0) {
                        os.write(b, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            p.on(pct >= 0 ? "Download: " + pct + "%" : "Download: " + (done / 1024 / 1024) + " MB");
                        }
                    }
                }
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("troppi reindirizzamenti");
    }

    /** Ultima esecuzione del workflow per un commit: {status, conclusion} oppure null se non ancora partita. */
    static String[] run(String token, String full, String sha) throws IOException {
        try {
            JSONObject o = new JSONObject(call(token, "GET", "/repos/" + full + "/actions/runs?head_sha=" + sha
                    + "&per_page=5", null));
            JSONArray a = o.optJSONArray("workflow_runs");
            if (a == null || a.length() == 0) {
                return null;
            }
            JSONObject r = a.getJSONObject(0);
            return new String[]{r.optString("status"), r.optString("conclusion", ""), r.optString("html_url")};
        } catch (org.json.JSONException e) {
            throw new IOException("risposta inattesa da GitHub");
        }
    }
}
