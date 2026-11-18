package it.mamotools;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;

/** Keyboard bluetooth: stato del servizio, elenco delle tastiere e accesso alle mappature di ognuna. */
public class KeyboardMapActivity extends Activity {

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Ui.screen(this, "Keyboard bluetooth",
                "Assegna funzioni e simboli ai tasti. Valgono in tutto il telefono, anche con Mamo Tools chiuso.");
    }

    static boolean serviceEnabled(android.content.Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) {
            return false;
        }
        ComponentName cn = new ComponentName(c, KeyRemapService.class);
        for (String t : s.split(":")) {
            if (t.equalsIgnoreCase(cn.flattenToString()) || t.equalsIgnoreCase(cn.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        while (root.getChildCount() > 2) {
            root.removeViewAt(2);
        }
        boolean on = serviceEnabled(this);

        LinearLayout st = Ui.card(this);
        st.addView(Ui.text(this, on ? "Servizio attivo ✓" : "Servizio non attivo", 17, on ? Ui.OK : Ui.DANGER, true));
        if (on) {
            st.addView(Ui.text(this, "Le mappature si applicano da sole quando colleghi la tastiera, anche "
                    + "senza aprire Mamo Tools.", 13, Ui.DIM, false));
        } else {
            st.addView(Ui.text(this, "Per far funzionare i tasti in tutto il telefono serve attivare una volta "
                    + "il servizio di accessibilita':\n1. tocca il pulsante qui sotto\n2. apri \"App scaricate\" "
                    + "(o \"Servizi installati\")\n3. scegli \"Mamo Tools - Keyboard bluetooth\" e attivalo.\n\n"
                    + "Android mostrera' un avviso: il servizio legge solo i tasti della tastiera fisica e non "
                    + "invia nulla fuori dal telefono.", 13, Ui.DIM, false));
        }
        st.addView(Ui.btn(this, on ? "Impostazioni di accessibilita'" : "Attiva il servizio", Ui.CARD2, Ui.ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception e) {
                            Toast.makeText(KeyboardMapActivity.this, "Impossibile aprire le impostazioni.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }), Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_BTN), 0, 12));
        if (on) {
            st.addView(Ui.text(this, "Se dopo un po' i tasti smettono di rispondere, escludi Mamo Tools dal "
                    + "risparmio energetico nelle impostazioni del telefono.", 12, Ui.DIM, false),
                    Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
        root.addView(st, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 0));

        final boolean enabled = KbdStore.enabled(this);
        final android.widget.Button sw = Ui.btn(this, enabled ? "Mappature attive: SI'" : "Mappature attive: NO",
                Ui.CARD, enabled ? Ui.OK : Ui.DIM, null);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KbdStore.setEnabled(KeyboardMapActivity.this, !KbdStore.enabled(KeyboardMapActivity.this));
                onResume();
            }
        });
        root.addView(sw, Ui.lp(this, Ui.MATCH, Ui.dp(this, Ui.H_BTN), 0, 10));

        root.addView(Ui.text(this, "Tastiere", 14, Ui.DIM, true), Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 20));

        List<KbdStore.Profile> profiles = KbdStore.load(this);
        // id -> {nome, connessa}
        LinkedHashMap<String, String[]> list = new LinkedHashMap<>();
        list.put(KbdStore.ALL, new String[]{"Tutte le tastiere", "1"});
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual() || (d.getSources() & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD
                    || d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                continue;
            }
            list.put(d.getDescriptor(), new String[]{d.getName(), "1"});
        }
        for (KbdStore.Profile p : profiles) {
            if (!list.containsKey(p.id)) {
                list.put(p.id, new String[]{p.name.isEmpty() ? "Tastiera" : p.name, "0"});
            }
        }
        boolean any = false;
        for (final java.util.Map.Entry<String, String[]> en : list.entrySet()) {
            final String pid = en.getKey();
            final String name = en.getValue()[0];
            boolean connected = en.getValue()[1].equals("1");
            if (!pid.equals(KbdStore.ALL) && connected) {
                any = true;
            }
            KbdStore.Profile p = KbdStore.find(profiles, pid);
            int n = p == null ? 0 : p.maps.size();
            String sub = (n == 0 ? "nessuna mappatura" : n + (n == 1 ? " mappatura" : " mappature"));
            if (pid.equals(KbdStore.ALL)) {
                sub += " · valgono per ogni tastiera";
            } else {
                sub += connected ? " · connessa" : " · non connessa";
            }
            LinearLayout card = Ui.card(this);
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(KeyboardMapActivity.this, KeyboardProfileActivity.class)
                            .putExtra("id", pid).putExtra("name", name));
                }
            });
            TextView t = Ui.text(this, (pid.equals(KbdStore.ALL) ? "🌐  " : "⌨  ") + name, 17, Ui.TEXT, true);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(t);
            card.addView(Ui.text(this, sub, 12, connected && !pid.equals(KbdStore.ALL) ? Ui.OK : Ui.DIM, false));
            root.addView(card, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10));
        }
        if (!any) {
            TextView e = Ui.text(this, "Nessuna tastiera fisica collegata ora.\nCollegala dalle impostazioni Bluetooth: "
                    + "apparira' qui. Intanto puoi usare \"Tutte le tastiere\".", 13, Ui.DIM, false);
            e.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, 0);
            root.addView(e);
        }
    }
}
