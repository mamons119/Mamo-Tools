package it.mamotools;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Mappature della tastiera Bluetooth: salvate in locale, lette anche dal servizio di accessibilita'. */
final class KbdStore {

    static final String PREFS = "kbdmap";
    static final String KEY = "profiles";
    static final String KEY_ON = "enabled";
    /** Profilo valido per qualsiasi tastiera. */
    static final String ALL = "*";

    static final int MODS = KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON
            | KeyEvent.META_META_ON;

    static final String T_TEXT = "text", T_APP = "app", T_GLOBAL = "global", T_MEDIA = "media", T_NONE = "none";

    /** Azioni di sistema (nome interno, testo). */
    static final String[][] GLOBALS = {
            {"back", "Indietro"}, {"home", "Home"}, {"recents", "App recenti"},
            {"notifications", "Apri le notifiche"}, {"quick", "Impostazioni rapide"},
            {"screenshot", "Screenshot"}, {"lock", "Blocca lo schermo"}, {"power", "Menu di spegnimento"}};
    static final String[][] MEDIA = {
            {"playpause", "Play / Pausa"}, {"next", "Brano successivo"}, {"prev", "Brano precedente"},
            {"stop", "Stop"}, {"volup", "Volume +"}, {"voldown", "Volume −"}, {"mute", "Muto"}};

    static final class Map {
        int keyCode, scanCode, mods;
        String type = T_TEXT, arg = "", label = "", keyName = "";

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("k", keyCode);
            o.put("s", scanCode);
            o.put("m", mods);
            o.put("t", type);
            o.put("a", arg);
            o.put("l", label);
            o.put("n", keyName);
            return o;
        }

        static Map of(JSONObject o) {
            Map m = new Map();
            m.keyCode = o.optInt("k");
            m.scanCode = o.optInt("s");
            m.mods = o.optInt("m");
            m.type = o.optString("t", T_TEXT);
            m.arg = o.optString("a", "");
            m.label = o.optString("l", "");
            m.keyName = o.optString("n", "");
            return m;
        }

        boolean matches(int kc, int sc, int meta) {
            if (normalize(meta) != mods) {
                return false;
            }
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                return kc == keyCode;
            }
            return sc != 0 && sc == scanCode;
        }

        String action() {
            switch (type) {
                case T_TEXT:
                    return "Scrive  " + visible(arg);
                case T_APP:
                    return "Apre  " + (label.isEmpty() ? arg : label);
                case T_GLOBAL:
                case T_MEDIA:
                    return label;
                default:
                    return "Nessuna azione (blocca il tasto)";
            }
        }
    }

    static String visible(String s) {
        return s.replace("\n", "↵").replace("\t", "⇥");
    }

    /** Riduce lo stato dei modificatori a Ctrl/Maiusc/Alt/Meta. */
    static int normalize(int meta) {
        int r = 0;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) {
            r |= KeyEvent.META_CTRL_ON;
        }
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) {
            r |= KeyEvent.META_SHIFT_ON;
        }
        if ((meta & KeyEvent.META_ALT_ON) != 0) {
            r |= KeyEvent.META_ALT_ON;
        }
        if ((meta & KeyEvent.META_META_ON) != 0) {
            r |= KeyEvent.META_META_ON;
        }
        return r;
    }

    static final class Profile {
        String id, name = "";
        final List<Map> maps = new ArrayList<>();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_ON, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
    }

    static List<Profile> load(Context c) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Profile p = new Profile();
                p.id = o.getString("id");
                p.name = o.optString("name", "");
                JSONArray ms = o.optJSONArray("maps");
                if (ms != null) {
                    for (int j = 0; j < ms.length(); j++) {
                        p.maps.add(Map.of(ms.getJSONObject(j)));
                    }
                }
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(Context c, List<Profile> list) {
        try {
            JSONArray a = new JSONArray();
            for (Profile p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                JSONArray ms = new JSONArray();
                for (Map m : p.maps) {
                    ms.put(m.json());
                }
                o.put("maps", ms);
                a.put(o);
            }
            prefs(c).edit().putString(KEY, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static Profile find(List<Profile> list, String id) {
        for (Profile p : list) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** Il profilo per questa tastiera; se manca viene creato. */
    static Profile get(List<Profile> list, String id, String name) {
        Profile p = find(list, id);
        if (p == null) {
            p = new Profile();
            p.id = id;
            p.name = name;
            list.add(p);
        }
        return p;
    }

    /** Nome leggibile della combinazione di tasti. */
    static String keyLabel(int keyCode, int scanCode, int mods, int baseChar) {
        StringBuilder sb = new StringBuilder();
        if ((mods & KeyEvent.META_CTRL_ON) != 0) {
            sb.append("Ctrl + ");
        }
        if ((mods & KeyEvent.META_ALT_ON) != 0) {
            sb.append("Alt + ");
        }
        if ((mods & KeyEvent.META_SHIFT_ON) != 0) {
            sb.append("Maiusc + ");
        }
        if ((mods & KeyEvent.META_META_ON) != 0) {
            sb.append("Meta + ");
        }
        if (baseChar > 32 && baseChar < 0xFFFF) {
            sb.append(Character.toUpperCase((char) baseChar));
        } else if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            String n = KeyEvent.keyCodeToString(keyCode);
            sb.append(n.startsWith("KEYCODE_") ? n.substring(8) : n);
        } else {
            sb.append("tasto ").append(scanCode);
        }
        return sb.toString();
    }
}
