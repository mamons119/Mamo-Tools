package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor di testo e di codice: colori del linguaggio, errori di sintassi, scorciatoie da tastiera. */
public class HyperTextActivity extends Activity {

    private static final class SynSpan extends ForegroundColorSpan {
        SynSpan(int c) {
            super(c);
        }
    }

    private static final class ErrSpan extends BackgroundColorSpan {
        ErrSpan() {
            super(0x66FF6B6B);
        }
    }

    private static final class Snap {
        final String t;
        final int sel;

        Snap(String t, int sel) {
            this.t = t;
            this.sel = sel;
        }
    }

    private static final int MAX_HILITE = 400000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private ProjStore store;
    private String id, title = "", langName = "Testo semplice";
    private CodeLang lang;
    private CodeEditText ed;
    private TextView titleView, langChip, status;
    private LinearLayout findBar;
    private EditText findIn, replIn;
    private TextView findCount;
    private SharedPreferences prefs;
    private float fontSp = 14f;
    private boolean wrap = false, lineNums = true;

    private final ArrayList<Snap> undo = new ArrayList<>(), redo = new ArrayList<>();
    private String prevText = "";
    private long lastChange = 0;
    private boolean restoring = false, dirty = false, indenting = false;
    private int chStart, chCount, chBefore;
    private volatile int version = 0;
    private List<HyperSyntax.Err> errs = new ArrayList<>();

    private final Runnable doColor = new Runnable() {
        @Override
        public void run() {
            recolor();
        }
    };
    private final Runnable doSave = new Runnable() {
        @Override
        public void run() {
            save();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new ProjStore(this, "hyper");
        prefs = getSharedPreferences("hypertext", Context.MODE_PRIVATE);
        fontSp = prefs.getFloat("size", 14f);
        wrap = prefs.getBoolean("wrap", false);
        lineNums = prefs.getBoolean("nums", true);
        id = getIntent().getStringExtra("project");
        String text = "";
        int caret = 0;
        try {
            JSONObject o = store.read(id);
            title = o.optString("title", "senza nome.txt");
            text = o.optString("text", "");
            langName = o.optString("lang", "");
            caret = o.optInt("caret", 0);
        } catch (Exception e) {
            Toast.makeText(this, "File non leggibile.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        lang = langName.isEmpty() ? CodeLang.detect(title, text) : CodeLang.byName(langName);
        langName = lang.name;
        build(text, caret);
    }

    private void build(String text, int caret) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(0, Ui.dp(this, 6), 0, 0);

        // Intestazione: indietro, nome, linguaggio
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 10), Ui.dp(this, 4));
        TextView back = Ui.text(this, "‹", 30, Ui.ACCENT, true);
        back.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 14), Ui.dp(this, 4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        titleView = Ui.text(this, title, 17, Ui.TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rename();
            }
        });
        head.addView(titleView, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        langChip = Ui.chip(this, lang.name);
        langChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLang();
            }
        });
        head.addView(langChip, Ui.lp(this, Ui.WRAP, Ui.dp(this, 34), 8, 0));
        root.addView(head, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // Barra degli strumenti
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 6));
        hs.addView(tools);
        tool(tools, "↶", new Runnable() {
            public void run() {
                doUndo();
            }
        });
        tool(tools, "↷", new Runnable() {
            public void run() {
                doRedo();
            }
        });
        tool(tools, "Cerca", new Runnable() {
            public void run() {
                showFind();
            }
        });
        tool(tools, "Vai a riga", new Runnable() {
            public void run() {
                gotoLine();
            }
        });
        tool(tools, "Commenta", new Runnable() {
            public void run() {
                toggleComment();
            }
        });
        tool(tools, "Formatta", new Runnable() {
            public void run() {
                format();
            }
        });
        tool(tools, "A−", new Runnable() {
            public void run() {
                size(-1);
            }
        });
        tool(tools, "A+", new Runnable() {
            public void run() {
                size(1);
            }
        });
        tool(tools, "Numeri", new Runnable() {
            public void run() {
                lineNums = !lineNums;
                prefs.edit().putBoolean("nums", lineNums).apply();
                ed.setNumbers(lineNums);
            }
        });
        tool(tools, "A capo", new Runnable() {
            public void run() {
                wrap = !wrap;
                prefs.edit().putBoolean("wrap", wrap).apply();
                ed.setHorizontallyScrolling(!wrap);
                ed.requestLayout();
            }
        });
        tool(tools, "Salva", new Runnable() {
            public void run() {
                save();
                Toast.makeText(HyperTextActivity.this, "Salvato", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(hs, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // Barra di ricerca
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.VERTICAL);
        findBar.setBackgroundColor(Ui.CARD);
        findBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        findBar.setVisibility(View.GONE);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        findIn = field("Cerca");
        findIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        findIn.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                findNext(true);
                return true;
            }
        });
        r1.addView(findIn, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        findCount = Ui.text(this, "", 12, Ui.DIM, false);
        findCount.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        r1.addView(findCount);
        r1.addView(small("▲", new Runnable() {
            public void run() {
                findNext(false);
            }
        }));
        r1.addView(small("▼", new Runnable() {
            public void run() {
                findNext(true);
            }
        }));
        r1.addView(small("✕", new Runnable() {
            public void run() {
                findBar.setVisibility(View.GONE);
                ed.requestFocus();
            }
        }));
        findBar.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        replIn = field("Sostituisci con");
        r2.addView(replIn, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        r2.addView(small("Sostituisci", new Runnable() {
            public void run() {
                replaceOne();
            }
        }));
        r2.addView(small("Tutto", new Runnable() {
            public void run() {
                replaceAll();
            }
        }));
        findBar.addView(r2);
        root.addView(findBar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // Editor
        ed = new CodeEditText(this);
        ed.setTextSize(fontSp);
        ed.setNumbers(lineNums);
        ed.setHorizontallyScrolling(!wrap);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        ed.setVerticalScrollBarEnabled(true);
        ed.setText(text);
        prevText = text;
        ed.setSelection(Math.min(Math.max(0, caret), text.length()));
        ed.updateLines();
        ed.keys = new CodeEditText.Keys() {
            @Override
            public boolean onKey(int k, KeyEvent e) {
                return handleKey(k, e);
            }
        };
        ed.selListener = new CodeEditText.SelListener() {
            @Override
            public void onSelection() {
                updateStatus();
            }
        };
        ed.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                chStart = st;
                chBefore = before;
                chCount = count;
            }

            @Override
            public void afterTextChanged(Editable s) {
                changed(s);
            }
        });
        root.addView(ed, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        // Simboli
        HorizontalScrollView sy = new HorizontalScrollView(this);
        sy.setHorizontalScrollBarEnabled(false);
        sy.setBackgroundColor(Ui.CARD);
        LinearLayout sym = new LinearLayout(this);
        sym.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        sy.addView(sym);
        sym.addView(symBtn("⇥", "    "));
        String[] chars = {"{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "\"", "'", "=", "/", "\\", "#", "$", "&",
                "|", "!", "?", "*", "+", "-", "_", "~", "`", "%", "@", "^", ","};
        for (String c : chars) {
            sym.addView(symBtn(c, c));
        }
        root.addView(sy, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        status = Ui.text(this, "", 12, Ui.DIM, false);
        status.setBackgroundColor(0xFF14171D);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpToError();
            }
        });
        root.addView(status, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        setContentView(root);
        updateStatus();
        recolor();
    }

    // ---------- piccoli componenti ----------

    private void tool(LinearLayout row, String label, final Runnable r) {
        TextView t = Ui.chip(this, label);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        row.addView(t, Ui.lp(this, Ui.WRAP, Ui.dp(this, 34), 4, 0));
    }

    private TextView small(String label, final Runnable r) {
        TextView t = Ui.text(this, label, 13, Ui.ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private View symBtn(String label, final String ins) {
        TextView t = Ui.text(this, label, 16, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setMinWidth(Ui.dp(this, 36));
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insert(ins);
            }
        });
        return t;
    }

    private void insert(String s) {
        Editable e = ed.getText();
        int a = Math.min(ed.getSelectionStart(), ed.getSelectionEnd());
        int b = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        e.replace(Math.max(a, 0), Math.max(b, 0), s);
    }

    // ---------- modifica, annulla/ripeti ----------

    private void changed(Editable s) {
        if (!restoring) {
            long now = System.currentTimeMillis();
            if (undo.isEmpty() || now - lastChange > 700) {
                undo.add(new Snap(prevText, Math.max(0, ed.getSelectionStart() - chCount + chBefore)));
                int cap = prevText.length() > 200000 ? 8 : 80;
                while (undo.size() > cap) {
                    undo.remove(0);
                }
            }
            redo.clear();
            lastChange = now;
        }
        prevText = s.toString();
        version++;
        dirty = true;
        ed.updateLines();
        if (!restoring && !indenting && chCount == 1 && chBefore == 0 && chStart < prevText.length()
                && prevText.charAt(chStart) == '\n') {
            autoIndent(s, chStart);
        }
        h.removeCallbacks(doColor);
        h.postDelayed(doColor, 350);
        h.removeCallbacks(doSave);
        h.postDelayed(doSave, 2500);
        updateStatus();
    }

    private void autoIndent(Editable s, int nl) {
        int ls = nl;
        while (ls > 0 && s.charAt(ls - 1) != '\n') {
            ls--;
        }
        StringBuilder ind = new StringBuilder();
        for (int i = ls; i < nl && (s.charAt(i) == ' ' || s.charAt(i) == '\t'); i++) {
            ind.append(s.charAt(i));
        }
        String before = s.subSequence(ls, nl).toString().trim();
        boolean open = before.endsWith("{") || before.endsWith("(") || before.endsWith("[")
                || (before.endsWith(":") && lang.name.equals("Python"));
        String extra = open ? "    " : "";
        boolean close = open && nl + 1 < s.length() && (s.charAt(nl + 1) == '}' || s.charAt(nl + 1) == ')'
                || s.charAt(nl + 1) == ']');
        if (ind.length() == 0 && extra.isEmpty()) {
            return;
        }
        indenting = true;
        try {
            String add = ind + extra;
            s.insert(nl + 1, add);
            int caret = nl + 1 + add.length();
            if (close) {
                s.insert(caret, "\n" + ind);
            }
            ed.setSelection(caret);
        } finally {
            indenting = false;
        }
    }

    private void breakUndo() {
        lastChange = 0;
    }

    private void restore(Snap sn) {
        restoring = true;
        try {
            ed.setText(sn.t);
            ed.setSelection(Math.min(sn.sel, sn.t.length()));
        } finally {
            restoring = false;
        }
        lastChange = 0;
    }

    private void doUndo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.add(new Snap(ed.getText().toString(), ed.getSelectionStart()));
        Snap s = undo.remove(undo.size() - 1);
        restore(s);
    }

    private void doRedo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.add(new Snap(ed.getText().toString(), ed.getSelectionStart()));
        Snap s = redo.remove(redo.size() - 1);
        restore(s);
    }

    // ---------- tastiera hardware ----------

    private boolean handleKey(int k, KeyEvent e) {
        boolean ctrl = e.isCtrlPressed(), shift = e.isShiftPressed(), alt = e.isAltPressed();
        if (k == KeyEvent.KEYCODE_TAB && !ctrl && !alt) {
            tab(shift);
            return true;
        }
        if (k == KeyEvent.KEYCODE_ESCAPE && findBar.getVisibility() == View.VISIBLE) {
            findBar.setVisibility(View.GONE);
            return true;
        }
        if (k == KeyEvent.KEYCODE_F3) {
            findNext(!shift);
            return true;
        }
        if (alt && !ctrl && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            moveLine(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            return true;
        }
        if (!ctrl) {
            return false;
        }
        switch (k) {
            case KeyEvent.KEYCODE_S:
                save();
                Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show();
                return true;
            case KeyEvent.KEYCODE_Z:
                if (shift) {
                    doRedo();
                } else {
                    doUndo();
                }
                return true;
            case KeyEvent.KEYCODE_Y:
                doRedo();
                return true;
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_H:
                showFind();
                return true;
            case KeyEvent.KEYCODE_G:
                gotoLine();
                return true;
            case KeyEvent.KEYCODE_D:
                duplicateLine();
                return true;
            case KeyEvent.KEYCODE_K:
                if (shift) {
                    deleteLine();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_SLASH:
                toggleComment();
                return true;
            case KeyEvent.KEYCODE_7:
                if (shift) {
                    toggleComment();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                tab(true);
                return true;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                tab(false);
                return true;
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                size(1);
                return true;
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                size(-1);
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                ed.setSelection(0);
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                ed.setSelection(ed.getText().length());
                return true;
            default:
                return false;
        }
    }

    private int lineStart(CharSequence t, int pos) {
        while (pos > 0 && t.charAt(pos - 1) != '\n') {
            pos--;
        }
        return pos;
    }

    private int lineEnd(CharSequence t, int pos) {
        while (pos < t.length() && t.charAt(pos) != '\n') {
            pos++;
        }
        return pos;
    }

    private void tab(boolean back) {
        Editable t = ed.getText();
        int a = Math.min(ed.getSelectionStart(), ed.getSelectionEnd());
        int b = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        boolean multi = a != b && t.subSequence(a, b).toString().contains("\n");
        if (!multi && !back) {
            insert("    ");
            return;
        }
        int s = lineStart(t, a), e = lineEnd(t, b > a && t.charAt(b - 1) == '\n' ? b - 1 : b);
        String[] lines = t.subSequence(s, e).toString().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (back) {
                int n = 0;
                while (n < 4 && n < l.length() && l.charAt(n) == ' ') {
                    n++;
                }
                if (n == 0 && l.startsWith("\t")) {
                    n = 1;
                }
                l = l.substring(n);
            } else {
                l = "    " + l;
            }
            sb.append(l);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        breakUndo();
        t.replace(s, e, sb.toString());
        ed.setSelection(s, s + sb.length());
    }

    private void duplicateLine() {
        Editable t = ed.getText();
        int a = ed.getSelectionStart(), b = ed.getSelectionEnd();
        int s = lineStart(t, Math.min(a, b)), e = lineEnd(t, Math.max(a, b));
        String l = t.subSequence(s, e).toString();
        breakUndo();
        t.insert(e, "\n" + l);
        ed.setSelection(a + l.length() + 1, b + l.length() + 1);
    }

    private void deleteLine() {
        Editable t = ed.getText();
        int a = ed.getSelectionStart(), b = ed.getSelectionEnd();
        int s = lineStart(t, Math.min(a, b)), e = lineEnd(t, Math.max(a, b));
        if (e < t.length()) {
            e++;
        } else if (s > 0) {
            s--;
        }
        breakUndo();
        t.delete(s, e);
    }

    private void moveLine(int dir) {
        Editable t = ed.getText();
        int a = Math.min(ed.getSelectionStart(), ed.getSelectionEnd());
        int b = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        int s = lineStart(t, a), e = lineEnd(t, b);
        if (dir < 0 && s == 0 || dir > 0 && e >= t.length()) {
            return;
        }
        String block = t.subSequence(s, e).toString();
        breakUndo();
        if (dir < 0) {
            int ps = lineStart(t, s - 1);
            String prev = t.subSequence(ps, s - 1).toString();
            t.replace(ps, e, block + "\n" + prev);
            ed.setSelection(a - (prev.length() + 1), b - (prev.length() + 1));
        } else {
            int ne = lineEnd(t, e + 1);
            String next = t.subSequence(e + 1, ne).toString();
            t.replace(s, ne, next + "\n" + block);
            ed.setSelection(a + next.length() + 1, b + next.length() + 1);
        }
    }

    private void toggleComment() {
        Editable t = ed.getText();
        int a = Math.min(ed.getSelectionStart(), ed.getSelectionEnd());
        int b = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        int s = lineStart(t, a), e = lineEnd(t, b > a && t.charAt(b - 1) == '\n' ? b - 1 : b);
        String block = t.subSequence(s, e).toString();
        String out;
        if (lang.lineC != null && lang.lineC.length > 0) {
            String c = lang.lineC[0];
            String[] ls = block.split("\n", -1);
            boolean all = true;
            for (String l : ls) {
                if (!l.trim().isEmpty() && !l.trim().startsWith(c)) {
                    all = false;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ls.length; i++) {
                String l = ls[i];
                if (l.trim().isEmpty()) {
                    sb.append(l);
                } else if (all) {
                    int p = l.indexOf(c);
                    int q = p + c.length();
                    if (q < l.length() && l.charAt(q) == ' ') {
                        q++;
                    }
                    sb.append(l, 0, p).append(l.substring(q));
                } else {
                    int p = 0;
                    while (p < l.length() && (l.charAt(p) == ' ' || l.charAt(p) == '\t')) {
                        p++;
                    }
                    sb.append(l, 0, p).append(c).append(' ').append(l.substring(p));
                }
                if (i < ls.length - 1) {
                    sb.append('\n');
                }
            }
            out = sb.toString();
        } else if (lang.blockStart != null && lang.blockEnd != null) {
            String tr = block.trim();
            if (tr.startsWith(lang.blockStart) && tr.endsWith(lang.blockEnd)
                    && tr.length() >= lang.blockStart.length() + lang.blockEnd.length()) {
                String in = tr.substring(lang.blockStart.length(), tr.length() - lang.blockEnd.length());
                if (in.startsWith(" ")) {
                    in = in.substring(1);
                }
                if (in.endsWith(" ")) {
                    in = in.substring(0, in.length() - 1);
                }
                out = in;
            } else {
                out = lang.blockStart + " " + block + " " + lang.blockEnd;
            }
        } else {
            Toast.makeText(this, "Questo linguaggio non ha commenti.", Toast.LENGTH_SHORT).show();
            return;
        }
        breakUndo();
        t.replace(s, e, out);
        ed.setSelection(s, s + out.length());
    }

    // ---------- cerca e sostituisci ----------

    private void showFind() {
        findBar.setVisibility(View.VISIBLE);
        int a = ed.getSelectionStart(), b = ed.getSelectionEnd();
        if (a >= 0 && b > a && b - a < 100) {
            String sel = ed.getText().subSequence(a, b).toString();
            if (!sel.contains("\n")) {
                findIn.setText(sel);
            }
        }
        findIn.requestFocus();
        findIn.selectAll();
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) {
            im.showSoftInput(findIn, 0);
        }
    }

    private List<Integer> matches(String q) {
        List<Integer> m = new ArrayList<>();
        if (q.isEmpty()) {
            return m;
        }
        String t = ed.getText().toString().toLowerCase(Locale.ROOT);
        String ql = q.toLowerCase(Locale.ROOT);
        int i = 0;
        while (m.size() < 20000 && (i = t.indexOf(ql, i)) >= 0) {
            m.add(i);
            i += Math.max(1, ql.length());
        }
        return m;
    }

    private void findNext(boolean fwd) {
        String q = findIn.getText().toString();
        List<Integer> m = matches(q);
        if (m.isEmpty()) {
            findCount.setText(q.isEmpty() ? "" : "0 risultati");
            return;
        }
        int cur = fwd ? ed.getSelectionEnd() : ed.getSelectionStart();
        int pick = -1;
        if (fwd) {
            for (int i = 0; i < m.size(); i++) {
                if (m.get(i) >= cur) {
                    pick = i;
                    break;
                }
            }
            if (pick < 0) {
                pick = 0;
            }
        } else {
            for (int i = m.size() - 1; i >= 0; i--) {
                if (m.get(i) + q.length() <= cur) {
                    pick = i;
                    break;
                }
            }
            if (pick < 0) {
                pick = m.size() - 1;
            }
        }
        int p = m.get(pick);
        ed.requestFocus();
        ed.setSelection(p, p + q.length());
        findCount.setText((pick + 1) + "/" + m.size());
    }

    private void replaceOne() {
        String q = findIn.getText().toString();
        if (q.isEmpty()) {
            return;
        }
        int a = ed.getSelectionStart(), b = ed.getSelectionEnd();
        if (b - a == q.length() && ed.getText().subSequence(a, b).toString().equalsIgnoreCase(q)) {
            breakUndo();
            ed.getText().replace(a, b, replIn.getText().toString());
        }
        findNext(true);
    }

    private void replaceAll() {
        String q = findIn.getText().toString();
        List<Integer> m = matches(q);
        if (m.isEmpty()) {
            return;
        }
        String r = replIn.getText().toString();
        StringBuilder sb = new StringBuilder();
        String t = ed.getText().toString();
        int last = 0;
        for (int p : m) {
            sb.append(t, last, p).append(r);
            last = p + q.length();
        }
        sb.append(t.substring(last));
        breakUndo();
        ed.getText().replace(0, t.length(), sb.toString());
        findCount.setText(m.size() + " sostituiti");
    }

    private void gotoLine() {
        final EditText in = field("Numero di riga");
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(in, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(this).setTitle("Vai a riga").setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Vai", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            goLine(Integer.parseInt(in.getText().toString().trim()));
                        } catch (Exception ignored) {
                        }
                    }
                }).show();
    }

    private void goLine(int n) {
        CharSequence t = ed.getText();
        int pos = 0, ln = 1;
        while (ln < n && pos < t.length()) {
            if (t.charAt(pos) == '\n') {
                ln++;
            }
            pos++;
        }
        ed.requestFocus();
        ed.setSelection(pos);
    }

    // ---------- colori ed errori ----------

    private void recolor() {
        final String text = ed.getText().toString();
        final int v = version;
        final CodeLang l = lang;
        if (text.length() > MAX_HILITE) {
            clearSpans();
            updateStatus();
            return;
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final List<HyperSyntax.Tok> toks = HyperSyntax.tokens(text, l);
                final List<HyperSyntax.Err> er = HyperSyntax.checks(l) ? HyperSyntax.check(text, l, toks)
                        : new ArrayList<HyperSyntax.Err>();
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (v != version || isFinishing()) {
                            return;
                        }
                        apply(toks, er, text);
                    }
                });
            }
        });
    }

    private void clearSpans() {
        Editable e = ed.getText();
        for (SynSpan s : e.getSpans(0, e.length(), SynSpan.class)) {
            e.removeSpan(s);
        }
        for (ErrSpan s : e.getSpans(0, e.length(), ErrSpan.class)) {
            e.removeSpan(s);
        }
        errs = new ArrayList<>();
        ed.errLines.clear();
    }

    private void apply(List<HyperSyntax.Tok> toks, List<HyperSyntax.Err> er, String text) {
        clearSpans();
        Editable e = ed.getText();
        int n = e.length();
        for (HyperSyntax.Tok t : toks) {
            if (t.a >= 0 && t.b <= n && t.a < t.b) {
                e.setSpan(new SynSpan(HyperSyntax.color(t.cls)), t.a, t.b, Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        errs = er;
        for (HyperSyntax.Err r : er) {
            int a = Math.min(Math.max(0, r.a), Math.max(0, n - 1));
            int b = Math.min(Math.max(a + 1, r.b), n);
            if (a < b) {
                e.setSpan(new ErrSpan(), a, b, Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            ed.errLines.add(HyperSyntax.line(text, r.a));
        }
        ed.invalidate();
        updateStatus();
    }

    private void jumpToError() {
        if (errs.isEmpty()) {
            return;
        }
        int cur = ed.getSelectionStart();
        HyperSyntax.Err pick = errs.get(0);
        for (HyperSyntax.Err r : errs) {
            if (r.a > cur) {
                pick = r;
                break;
            }
        }
        ed.requestFocus();
        ed.setSelection(Math.min(pick.a, ed.getText().length()));
        Toast.makeText(this, pick.msg, Toast.LENGTH_LONG).show();
    }

    private void updateStatus() {
        if (status == null || ed == null) {
            return;
        }
        Editable t = ed.getText();
        int sel = Math.max(0, ed.getSelectionStart());
        int ln = 1, col = 1;
        for (int i = 0; i < sel && i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                ln++;
                col = 1;
            } else {
                col++;
            }
        }
        int lines = 1;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                lines++;
            }
        }
        String s = "Riga " + ln + ", col " + col + "  ·  " + lines + " righe  ·  " + t.length() + " car.";
        if (t.length() > MAX_HILITE) {
            s += "  ·  file grande: colori disattivati";
            status.setTextColor(Ui.DIM);
        } else if (!errs.isEmpty()) {
            HyperSyntax.Err first = errs.get(0);
            s = errs.size() + (errs.size() == 1 ? " errore: " : " errori: ") + first.msg + "  ·  " + s;
            status.setTextColor(Ui.DANGER);
        } else {
            status.setTextColor(Ui.DIM);
        }
        status.setText(s);
    }

    // ---------- varie ----------

    private void size(int d) {
        fontSp = Math.max(9f, Math.min(32f, fontSp + d));
        prefs.edit().putFloat("size", fontSp).apply();
        ed.setTextSize(fontSp);
    }

    private void format() {
        if (!CodeFormatter.supported(lang)) {
            Toast.makeText(this, "Formattazione non disponibile per " + lang.name + ".", Toast.LENGTH_SHORT).show();
            return;
        }
        String t = ed.getText().toString();
        String f = CodeFormatter.format(t, lang);
        if (f.equals(t)) {
            Toast.makeText(this, "Gia' formattato.", Toast.LENGTH_SHORT).show();
            return;
        }
        breakUndo();
        int c = Math.min(ed.getSelectionStart(), f.length());
        ed.getText().replace(0, t.length(), f);
        ed.setSelection(Math.max(0, Math.min(c, f.length())));
    }

    private void pickLang() {
        final String[] names = new String[CodeLang.ALL.size()];
        int cur = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = CodeLang.ALL.get(i).name;
            if (names[i].equals(lang.name)) {
                cur = i;
            }
        }
        new AlertDialog.Builder(this).setTitle("Linguaggio")
                .setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        lang = CodeLang.ALL.get(w);
                        langName = lang.name;
                        langChip.setText(lang.name);
                        dirty = true;
                        version++;
                        recolor();
                        save();
                        d.dismiss();
                    }
                }).setNegativeButton("Annulla", null).show();
    }

    private void rename() {
        final EditText in = field("Nome del file");
        in.setText(title);
        in.selectAll();
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(in, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(this).setTitle("Rinomina").setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = in.getText().toString().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                        if (n.isEmpty()) {
                            return;
                        }
                        title = n;
                        titleView.setText(n);
                        lang = CodeLang.detect(n, ed.getText().toString());
                        langName = lang.name;
                        langChip.setText(lang.name);
                        version++;
                        recolor();
                        dirty = true;
                        save();
                    }
                }).show();
    }

    private void save() {
        h.removeCallbacks(doSave);
        if (ed == null || id == null) {
            return;
        }
        try {
            String t = ed.getText().toString();
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("text", t);
            o.put("lang", langName);
            o.put("caret", Math.max(0, ed.getSelectionStart()));
            int lines = 1;
            for (int i = 0; i < t.length(); i++) {
                if (t.charAt(i) == '\n') {
                    lines++;
                }
            }
            o.put("summary", langName + " · " + lines + (lines == 1 ? " riga" : " righe"));
            store.write(id, o);
            dirty = false;
        } catch (Exception e) {
            Toast.makeText(this, "Salvataggio non riuscito.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dirty) {
            save();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        exec.shutdownNow();
    }
}
