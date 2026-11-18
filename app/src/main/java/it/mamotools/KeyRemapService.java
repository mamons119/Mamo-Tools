package it.mamotools;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Servizio di accessibilita': legge i tasti della tastiera fisica in tutto il telefono ed esegue le mappature
 * salvate per quella tastiera. Resta attivo anche con l'app chiusa, quindi le impostazioni valgono appena la
 * tastiera si collega.
 */
public class KeyRemapService extends AccessibilityService {

    /** Vero mentre l'app sta registrando un tasto: in quel caso nulla viene intercettato. */
    static volatile boolean learning = false;
    static volatile boolean running = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile List<KbdStore.Profile> profiles;
    private volatile boolean on = true;
    private final Set<Long> consumed = new HashSet<>();
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        reload();
        listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                reload();
            }
        };
        KbdStore.prefs(this).registerOnSharedPreferenceChangeListener(listener);
    }

    private void reload() {
        profiles = KbdStore.load(this);
        on = KbdStore.enabled(this);
    }

    @Override
    public boolean onUnbind(Intent i) {
        running = false;
        if (listener != null) {
            KbdStore.prefs(this).unregisterOnSharedPreferenceChangeListener(listener);
            listener = null;
        }
        return super.onUnbind(i);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override
    public void onInterrupt() {
    }

    private static long id(KeyEvent e) {
        return ((long) e.getDeviceId() << 32) | (e.getKeyCode() & 0xFFFFFFFFL);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (learning || !on) {
            return false;
        }
        long id = id(e);
        if (e.getAction() == KeyEvent.ACTION_UP) {
            return consumed.remove(id);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        InputDevice dev = e.getDevice();
        if (dev != null && dev.isVirtual()) {
            return false;
        }
        List<KbdStore.Profile> ps = profiles;
        if (ps == null || ps.isEmpty()) {
            return false;
        }
        String desc = dev == null ? "" : dev.getDescriptor();
        KbdStore.Map hit = null;
        for (int pass = 0; pass < 2 && hit == null; pass++) {
            for (KbdStore.Profile p : ps) {
                boolean wanted = pass == 0 ? p.id.equals(desc) : p.id.equals(KbdStore.ALL);
                if (!wanted) {
                    continue;
                }
                for (KbdStore.Map m : p.maps) {
                    if (m.matches(e.getKeyCode(), e.getScanCode(), e.getMetaState())) {
                        hit = m;
                        break;
                    }
                }
                if (hit != null) {
                    break;
                }
            }
        }
        if (hit == null) {
            return false;
        }
        consumed.add(id);
        boolean repeat = e.getRepeatCount() > 0;
        if (!repeat || KbdStore.T_TEXT.equals(hit.type)) {
            run(hit);
        }
        return true;
    }

    private void run(KbdStore.Map m) {
        try {
            switch (m.type) {
                case KbdStore.T_TEXT:
                    insertText(m.arg);
                    break;
                case KbdStore.T_APP:
                    Intent i = getPackageManager().getLaunchIntentForPackage(m.arg);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                    break;
                case KbdStore.T_GLOBAL:
                    global(m.arg);
                    break;
                case KbdStore.T_MEDIA:
                    media(m.arg);
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private void global(String a) {
        int act;
        switch (a) {
            case "back":
                act = GLOBAL_ACTION_BACK;
                break;
            case "home":
                act = GLOBAL_ACTION_HOME;
                break;
            case "recents":
                act = GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                act = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quick":
                act = GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "screenshot":
                act = GLOBAL_ACTION_TAKE_SCREENSHOT;
                break;
            case "lock":
                act = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "power":
                act = GLOBAL_ACTION_POWER_DIALOG;
                break;
            default:
                return;
        }
        performGlobalAction(act);
    }

    private void media(String a) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        int key = 0;
        switch (a) {
            case "playpause":
                key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                break;
            case "next":
                key = KeyEvent.KEYCODE_MEDIA_NEXT;
                break;
            case "prev":
                key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                break;
            case "stop":
                key = KeyEvent.KEYCODE_MEDIA_STOP;
                break;
            case "volup":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return;
            case "voldown":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return;
            case "mute":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE,
                        AudioManager.FLAG_SHOW_UI);
                return;
            default:
                return;
        }
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    /** Scrive il testo nel campo che ha il cursore: prima modificando il testo, poi (se serve) incollando. */
    private void insertText(String s) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null || !n.isEditable()) {
            return;
        }
        if (!n.isPassword()) {
            CharSequence cur = n.isShowingHintText() || n.getText() == null ? "" : n.getText();
            int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
            if (a < 0 || b < 0 || a > cur.length() || b > cur.length()) {
                a = b = cur.length();
            }
            int lo = Math.min(a, b), hi = Math.max(a, b);
            String nt = cur.subSequence(0, lo).toString() + s + cur.subSequence(hi, cur.length());
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nt);
            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                final int caret = lo + s.length();
                final AccessibilityNodeInfo node = n;
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            node.refresh();
                            Bundle sel = new Bundle();
                            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
                            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                        } catch (Exception ignored) {
                        }
                    }
                }, 30);
                return;
            }
        }
        // alternativa: incolla (usa gli appunti, poi prova a rimettere quelli di prima)
        final ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        ClipData old = null;
        try {
            old = cm.getPrimaryClip();
        } catch (Exception ignored) {
        }
        cm.setPrimaryClip(ClipData.newPlainText("", s));
        n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        final ClipData restore = old;
        if (restore != null) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        cm.setPrimaryClip(restore);
                    } catch (Exception ignored) {
                    }
                }
            }, 400);
        }
    }
}
