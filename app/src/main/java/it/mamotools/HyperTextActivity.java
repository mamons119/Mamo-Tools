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
    private boolean wrap = false, lineNums = true, pairs = true;
    private HorizontalScrollView suggestBar;
    private LinearLayout suggestRow;
    private final List<String> sugg = new ArrayList<>();
    private volatile java.util.Set<String> docWords = new java.util.HashSet<>();

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
        pairs = prefs.getBoolean("pairs", true);
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

        // Barra dei menu (stile programmi per PC)
        LinearLayout menuBar = new LinearLayout(this);
        menuBar.setGravity(Gravity.CENTER_VERTICAL);
        menuBar.setBackgroundColor(0xFF14171D);
        menuBar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        menuBtn(menuBar, "File", 0);
        menuBtn(menuBar, "Modifica", 1);
        menuBtn(menuBar, "Cerca", 2);
        menuBtn(menuBar, "Visualizza", 3);
        menuBtn(menuBar, "Strumenti", 4);
        menuBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        menuBar.addView(iconBtn("↶", new Runnable() {
            public void run() {
                doUndo();
            }
        }));
        menuBar.addView(iconBtn("↷", new Runnable() {
            public void run() {
                doRedo();
            }
        }));
        root.addView(menuBar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 42)));

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
                updateSuggest();
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

        // Suggerimenti di completamento
        suggestBar = new HorizontalScrollView(this);
        suggestBar.setHorizontalScrollBarEnabled(false);
        suggestBar.setBackgroundColor(0xFF1F2430);
        suggestBar.setVisibility(View.GONE);
        suggestRow = new LinearLayout(this);
        suggestRow.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        suggestBar.addView(suggestRow);
        root.addView(suggestBar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

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

    // ---------- menu ----------

    private static final class Item {
        String label, key;
        Runnable run;
        int check = -1; // -1 nessuna spunta, 0 spenta, 1 accesa
        boolean sep;
    }

    private static Item mi(String label, String key, Runnable r) {
        Item i = new Item();
        i.label = label;
        i.key = key;
        i.run = r;
        return i;
    }

    private static Item mc(String label, String key, boolean on, Runnable r) {
        Item i = mi(label, key, r);
        i.check = on ? 1 : 0;
        return i;
    }

    private static Item sep() {
        Item i = new Item();
        i.sep = true;
        return i;
    }

    private void menuBtn(LinearLayout bar, String label, final int which) {
        final TextView t = Ui.text(this, label, 14, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(t, menuItems(which));
            }
        });
        bar.addView(t, new LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH));
    }

    private View iconBtn(String label, final Runnable r) {
        TextView t = Ui.text(this, label, 20, Ui.ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 42));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH));
        return t;
    }

    private void edit(int id) {
        ed.requestFocus();
        ed.onTextContextMenuItem(id);
    }

    private List<Item> menuItems(int which) {
        List<Item> m = new ArrayList<>();
        switch (which) {
            case 0:
                m.add(mi("Salva", "Ctrl+S", new Runnable() {
                    public void run() {
                        save();
                        Toast.makeText(HyperTextActivity.this, "Salvato", Toast.LENGTH_SHORT).show();
                    }
                }));
                m.add(mi("Rinomina…", "", new Runnable() {
                    public void run() {
                        rename();
                    }
                }));
                m.add(mi("Scarica in Download", "", new Runnable() {
                    public void run() {
                        download();
                    }
                }));
                m.add(sep());
                m.add(mi("Chiudi file", "", new Runnable() {
                    public void run() {
                        finish();
                    }
                }));
                break;
            case 1:
                m.add(mi("Annulla", "Ctrl+Z", new Runnable() {
                    public void run() {
                        doUndo();
                    }
                }));
                m.add(mi("Ripeti", "Ctrl+Y", new Runnable() {
                    public void run() {
                        doRedo();
                    }
                }));
                m.add(sep());
                m.add(mi("Taglia", "Ctrl+X", new Runnable() {
                    public void run() {
                        edit(android.R.id.cut);
                    }
                }));
                m.add(mi("Copia", "Ctrl+C", new Runnable() {
                    public void run() {
                        edit(android.R.id.copy);
                    }
                }));
                m.add(mi("Incolla", "Ctrl+V", new Runnable() {
                    public void run() {
                        edit(android.R.id.paste);
                    }
                }));
                m.add(mi("Seleziona tutto", "Ctrl+A", new Runnable() {
                    public void run() {
                        edit(android.R.id.selectAll);
                    }
                }));
                m.add(sep());
                m.add(mi("Duplica riga", "Ctrl+D", new Runnable() {
                    public void run() {
                        duplicateLine();
                    }
                }));
                m.add(mi("Elimina riga", "Ctrl+Maiusc+K", new Runnable() {
                    public void run() {
                        deleteLine();
                    }
                }));
                m.add(mi("Sposta riga su", "Alt+↑", new Runnable() {
                    public void run() {
                        moveLine(-1);
                    }
                }));
                m.add(mi("Sposta riga giu'", "Alt+↓", new Runnable() {
                    public void run() {
                        moveLine(1);
                    }
                }));
                m.add(sep());
                m.add(mi("Commenta / decommenta", "Ctrl+/", new Runnable() {
                    public void run() {
                        toggleComment();
                    }
                }));
                m.add(mi("Aumenta rientro", "Tab", new Runnable() {
                    public void run() {
                        tab(false);
                    }
                }));
                m.add(mi("Riduci rientro", "Maiusc+Tab", new Runnable() {
                    public void run() {
                        tab(true);
                    }
                }));
                m.add(mi("Formatta codice", "", new Runnable() {
                    public void run() {
                        format();
                    }
                }));
                break;
            case 2:
                m.add(mi("Trova e sostituisci…", "Ctrl+F", new Runnable() {
                    public void run() {
                        showFind();
                    }
                }));
                m.add(mi("Trova successivo", "F3", new Runnable() {
                    public void run() {
                        findNext(true);
                    }
                }));
                m.add(mi("Trova precedente", "Maiusc+F3", new Runnable() {
                    public void run() {
                        findNext(false);
                    }
                }));
                m.add(sep());
                m.add(mi("Vai a riga…", "Ctrl+G", new Runnable() {
                    public void run() {
                        gotoLine();
                    }
                }));
                m.add(mi("Vai al prossimo errore", "", new Runnable() {
                    public void run() {
                        jumpToError();
                    }
                }));
                break;
            case 3:
                m.add(mi("Anteprima (HTML, Markdown, SVG)", "F5", new Runnable() {
                    public void run() {
                        preview();
                    }
                }));
                m.add(sep());
                m.add(mc("Numeri di riga", "", lineNums, new Runnable() {
                    public void run() {
                        lineNums = !lineNums;
                        prefs.edit().putBoolean("nums", lineNums).apply();
                        ed.setNumbers(lineNums);
                    }
                }));
                m.add(mc("A capo automatico", "", wrap, new Runnable() {
                    public void run() {
                        wrap = !wrap;
                        prefs.edit().putBoolean("wrap", wrap).apply();
                        ed.setHorizontallyScrolling(!wrap);
                        ed.requestLayout();
                    }
                }));
                m.add(sep());
                m.add(mi("Ingrandisci testo", "Ctrl++", new Runnable() {
                    public void run() {
                        size(1);
                    }
                }));
                m.add(mi("Riduci testo", "Ctrl+-", new Runnable() {
                    public void run() {
                        size(-1);
                    }
                }));
                break;
            default:
                m.add(mi("Linguaggio: " + lang.name + "…", "", new Runnable() {
                    public void run() {
                        pickLang();
                    }
                }));
                m.add(mc("Chiusura automatica ( ) \" \"", "", pairs, new Runnable() {
                    public void run() {
                        pairs = !pairs;
                        prefs.edit().putBoolean("pairs", pairs).apply();
                    }
                }));
                m.add(mi("Accetta suggerimento", "Ctrl+Spazio", new Runnable() {
                    public void run() {
                        if (!sugg.isEmpty()) {
                            acceptSuggestion(sugg.get(0));
                        }
                    }
                }));
                break;
        }
        return m;
    }

    private void showMenu(View anchor, List<Item> items) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setMinimumWidth(Ui.dp(this, 250));
        col.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        final android.widget.PopupWindow pw = new android.widget.PopupWindow(Ui.WRAP, Ui.WRAP);
        for (final Item it : items) {
            if (it.sep) {
                View v = new View(this);
                v.setBackgroundColor(0xFF3A404C);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, 1);
                lp.topMargin = Ui.dp(this, 5);
                lp.bottomMargin = Ui.dp(this, 5);
                col.addView(v, lp);
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 10), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
            TextView ck = Ui.text(this, it.check == 1 ? "✓" : "", 14, Ui.ACCENT, true);
            ck.setGravity(Gravity.CENTER);
            row.addView(ck, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.WRAP));
            row.addView(Ui.text(this, it.label, 15, Ui.TEXT, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            if (it.key != null && !it.key.isEmpty()) {
                TextView k = Ui.text(this, it.key, 12, Ui.DIM, false);
                k.setPadding(Ui.dp(this, 24), 0, 0, 0);
                row.addView(k);
            }
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pw.dismiss();
                    it.run.run();
                }
            });
            col.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        sv.setBackground(Ui.round(this, Ui.CARD2, 10));
        pw.setContentView(sv);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
        col.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels,
                View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
        pw.setHeight(Math.min(maxH, col.getMeasuredHeight()));
        pw.setWidth(Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 16),
                Math.max(Ui.dp(this, 250), col.getMeasuredWidth())));
        pw.setFocusable(true);
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pw.setElevation(Ui.dp(this, 12));
        pw.showAsDropDown(anchor, 0, 0);
    }

    private void download() {
        save();
        try {
            final byte[] data = ed.getText().toString().getBytes("UTF-8");
            String ext = title.contains(".") ? title.substring(title.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                    : "";
            String mime = ext.isEmpty() ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) {
                mime = "application/octet-stream";
            }
            FileUtil.save(this, android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/MamoTools",
                    title, mime, new FileUtil.Writer() {
                        @Override
                        public void write(java.io.OutputStream os) throws java.io.IOException {
                            os.write(data);
                        }
                    });
            Toast.makeText(this, "Salvato in Download/MamoTools", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download non riuscito: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
        String old = prevText;
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
        if (!restoring && !indenting) {
            if (chCount == 1 && chBefore == 0 && chStart < prevText.length()) {
                char c = prevText.charAt(chStart);
                if (c == '\n') {
                    autoIndent(s, chStart);
                } else {
                    autoPair(s, chStart, c);
                }
            } else if (chCount == 0 && chBefore == 1 && chStart < old.length()) {
                autoUnpair(s, old.charAt(chStart), chStart);
            }
        }
        h.removeCallbacks(doColor);
        h.postDelayed(doColor, 350);
        h.removeCallbacks(doSave);
        h.postDelayed(doSave, 2500);
        updateStatus();
        updateSuggest();
    }

    private boolean pairsOn() {
        return pairs && lang.kind != CodeLang.MARKDOWN && !lang.name.equals("Testo semplice");
    }

    private static char closerOf(char c) {
        switch (c) {
            case '(':
                return ')';
            case '[':
                return ']';
            case '{':
                return '}';
            default:
                return 0;
        }
    }

    private void autoPair(Editable s, int pos, char c) {
        if (!pairsOn()) {
            return;
        }
        int len = s.length();
        char next = pos + 1 < len ? s.charAt(pos + 1) : 0;
        char prev = pos > 0 ? s.charAt(pos - 1) : 0;
        boolean quote = c == '"' || c == '\'' || c == '`';
        if (quote && c == '\'' && lang.name.equals("Rust")) {
            return;
        }
        if (quote && c != '`' && lang.quotes.indexOf(c) < 0) {
            return;
        }
        if (quote && c == '`' && !lang.backtick) {
            return;
        }
        indenting = true;
        try {
            if ((c == ')' || c == ']' || c == '}' || quote) && next == c) {
                boolean skip = true;
                if (quote) {
                    int ls = lineStart(s, pos), le = lineEnd(s, pos), n = 0;
                    for (int i = ls; i < le; i++) {
                        if (s.charAt(i) == c) {
                            n++;
                        }
                    }
                    skip = n % 2 == 1;
                }
                if (skip) {
                    s.delete(pos, pos + 1);
                    ed.setSelection(pos + 1);
                    return;
                }
            }
            boolean nextOk = next == 0 || Character.isWhitespace(next) || next == ')' || next == ']' || next == '}'
                    || next == ',' || next == ';';
            if (closerOf(c) != 0 && nextOk) {
                s.insert(pos + 1, String.valueOf(closerOf(c)));
                ed.setSelection(pos + 1);
            } else if (quote && nextOk && !Character.isLetterOrDigit(prev) && prev != '\\' && prev != c) {
                s.insert(pos + 1, String.valueOf(c));
                ed.setSelection(pos + 1);
            }
        } finally {
            indenting = false;
        }
    }

    private void autoUnpair(Editable s, char deleted, int pos) {
        if (!pairsOn() || pos >= s.length()) {
            return;
        }
        char want = closerOf(deleted);
        if (want == 0 && (deleted == '"' || deleted == '\'' || deleted == '`')) {
            want = deleted;
        }
        if (want != 0 && s.charAt(pos) == want) {
            indenting = true;
            try {
                s.delete(pos, pos + 1);
            } finally {
                indenting = false;
            }
        }
    }

    // ---------- suggerimenti ----------

    private static boolean wordCh(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private int prefixLen() {
        Editable t = ed.getText();
        int p = ed.getSelectionStart();
        if (p < 0 || p != ed.getSelectionEnd() || p > t.length()) {
            return 0;
        }
        if (p < t.length() && wordCh(t.charAt(p))) {
            return 0;
        }
        int a = p;
        while (a > 0 && wordCh(t.charAt(a - 1))) {
            a--;
        }
        return p - a;
    }

    private void updateSuggest() {
        if (suggestBar == null || ed == null) {
            return;
        }
        sugg.clear();
        int n = restoring ? 0 : prefixLen();
        if (n >= 2 && n <= 40 && !lang.name.equals("Testo semplice") || n >= 3 && n <= 40) {
            int p = ed.getSelectionStart();
            String pre = ed.getText().subSequence(p - n, p).toString();
            boolean ci = lang.ci;
            String key = ci ? pre.toLowerCase(Locale.ROOT) : pre;
            java.util.TreeSet<String> kws = new java.util.TreeSet<>(), docs = new java.util.TreeSet<>();
            for (String w : lang.kw) {
                if (w.startsWith(key) && !w.equals(key)) {
                    kws.add(w);
                }
            }
            for (String w : lang.types) {
                if (w.startsWith(key) && !w.equals(key)) {
                    kws.add(w);
                }
            }
            for (String w : docWords) {
                String c = ci ? w.toLowerCase(Locale.ROOT) : w;
                if (c.startsWith(key) && !c.equals(key) && !kws.contains(w)) {
                    docs.add(w);
                }
            }
            for (String w : kws) {
                if (sugg.size() < 12) {
                    sugg.add(w);
                }
            }
            for (String w : docs) {
                if (sugg.size() < 12) {
                    sugg.add(w);
                }
            }
        }
        suggestRow.removeAllViews();
        for (final String w : sugg) {
            TextView t = Ui.text(this, w, 14, Ui.ACCENT, true);
            t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            t.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    acceptSuggestion(w);
                }
            });
            suggestRow.addView(t);
        }
        suggestBar.setVisibility(sugg.isEmpty() ? View.GONE : View.VISIBLE);
        suggestBar.scrollTo(0, 0);
    }

    private void acceptSuggestion(String w) {
        int n = prefixLen();
        int p = ed.getSelectionStart();
        if (n > 0 && p >= n) {
            ed.getText().replace(p - n, p, w);
        }
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
        if (k == KeyEvent.KEYCODE_F5) {
            preview();
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
            case KeyEvent.KEYCODE_SPACE:
                if (!sugg.isEmpty()) {
                    acceptSuggestion(sugg.get(0));
                }
                return true;
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
        final int caretPos = Math.max(0, ed.getSelectionStart());
        if (text.length() > MAX_HILITE) {
            clearSpans();
            updateStatus();
            return;
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                java.util.Set<String> words = new java.util.HashSet<>();
                java.util.regex.Matcher wm = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}")
                        .matcher(text);
                while (wm.find() && words.size() < 20000) {
                    if (caretPos >= wm.start() && caretPos <= wm.end()) {
                        continue;
                    }
                    words.add(wm.group());
                }
                docWords = words;
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

    private void preview() {
        String t = ed.getText().toString();
        String low = title.toLowerCase(Locale.ROOT);
        String html;
        if (lang.kind == CodeLang.MARKDOWN) {
            html = MdRender.page(t);
        } else if (lang.kind == CodeLang.HTML) {
            html = t;
        } else if (low.endsWith(".svg")) {
            html = "<html><body style='margin:0;background:#fff'>" + t + "</body></html>";
        } else {
            Toast.makeText(this, "L'anteprima e' disponibile per HTML, Markdown e SVG.", Toast.LENGTH_LONG).show();
            return;
        }
        save();
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        bar.addView(Ui.text(this, "Anteprima · " + title, 16, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        bar.addView(Ui.btn(this, "Chiudi", Ui.CARD2, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        }), new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        box.addView(bar);
        final android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.setBackgroundColor(lang.kind == CodeLang.MARKDOWN ? Ui.BG : 0xFFFFFFFF);
        android.webkit.WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        wv.setWebViewClient(new android.webkit.WebViewClient());
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        box.addView(wv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        d.setContentView(box);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                wv.destroy();
            }
        });
        d.show();
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
