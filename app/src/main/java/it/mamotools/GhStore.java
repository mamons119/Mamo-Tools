package it.mamotools;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Chiavi (token) di GitHub: piu' account, cifrati con una chiave del Keystore di Android. */
final class GhStore {

    static final class Acct {
        String id, label, login, token;
    }

    private static final String ALIAS = "mamotools_gh_key";
    private final SharedPreferences sp;

    GhStore(Context c) {
        sp = c.getSharedPreferences("gh_store", Context.MODE_PRIVATE);
    }

    List<Acct> list() {
        List<Acct> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("accounts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Acct x = new Acct();
                x.id = o.getString("id");
                x.label = o.optString("label", "");
                x.login = o.optString("login", "");
                try {
                    x.token = dec(o.getString("tok"));
                } catch (Exception e) {
                    x.token = null;   // chiave del Keystore non piu' valida: va reinserita
                }
                out.add(x);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    void add(String label, String login, String token) throws Exception {
        JSONArray a = new JSONArray(sp.getString("accounts", "[]"));
        a.put(new JSONObject().put("id", "k" + System.currentTimeMillis()).put("label", label)
                .put("login", login).put("tok", enc(token)));
        sp.edit().putString("accounts", a.toString()).apply();
    }

    void remove(String id) {
        try {
            JSONArray a = new JSONArray(sp.getString("accounts", "[]"));
            JSONArray n = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (!a.getJSONObject(i).getString("id").equals(id)) {
                    n.put(a.getJSONObject(i));
                }
            }
            sp.edit().putString("accounts", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Repository preferiti ("idChiave|proprietario/nome"). */
    java.util.Set<String> pins() {
        return new java.util.HashSet<>(sp.getStringSet("pins", new java.util.HashSet<String>()));
    }

    boolean togglePin(String key) {
        java.util.Set<String> p = pins();
        boolean on = !p.remove(key);
        if (on) {
            p.add(key);
        }
        sp.edit().putStringSet("pins", p).apply();
        return on;
    }

    /** Repository gia' verificati come scrivibili con una certa chiave (evita di riprovarli a ogni apertura). */
    java.util.Set<String> okRepos() {
        return new java.util.HashSet<>(sp.getStringSet("okrepos", new java.util.HashSet<String>()));
    }

    void addOkRepo(String key) {
        java.util.Set<String> s = okRepos();
        s.add(key);
        sp.edit().putStringSet("okrepos", s).apply();
    }

    String last() {
        return sp.getString("last", "");
    }

    void setLast(String s) {
        sp.edit().putString("last", s).apply();
    }

    // ------------------------------------------------------------------ cifratura

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private static String enc(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(s.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String dec(String s) throws Exception {
        String[] p = s.split(":");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
