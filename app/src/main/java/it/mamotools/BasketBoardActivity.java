package it.mamotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

/** Editor degli schemi di basket: disegno dei movimenti, fasi, animazione e salvataggio automatico. */
public class BasketBoardActivity extends Activity implements BasketBoardView.Listener {

    private ProjStore store;
    private String id;
    private BasketBoardView board;
    private TextView titleTv, subTv, playBtn, timeTv, speedBtn, undoBtn, redoBtn;
    private SeekBar seek;
    private LinearLayout stepsRow;
    private HorizontalScrollView stepsScroll;
    private PopupWindow menu;
    private boolean updatingSeek = false, tracking = false;
    private int shownStep = -1, shownCount = -1;
    private double[] speeds = {0.5, 1, 1.5, 2};
    private int speedIx = 1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<String> undo = new ArrayList<>(), redo = new ArrayList<>();
    private String lastSnap = "";
    private boolean dirty = false;
    private final Runnable saveRun = this::save;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ProjStore(this, "basket");
        id = getIntent().getStringExtra("project");
        BasketPlay play;
        try {
            play = BasketPlay.fromJson(store.read(id));
        } catch (Exception e) {
            Toast.makeText(this, "Schema non leggibile.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(0, dp(6), 0, 0);

        // barra in alto
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(2), dp(10), dp(8));
        top.addView(roundBtn("‹", 26, v -> onBackPressed()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(12), 0, dp(6), 0);
        titleTv = Ui.text(this, play.name, 17, Ui.TEXT, true);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTv.setOnClickListener(v -> rename());
        tc.addView(titleTv);
        subTv = Ui.text(this, "", 11, Ui.DIM, false);
        subTv.setSingleLine(true);
        tc.addView(subTv);
        top.addView(tc, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        undoBtn = roundBtn("↶", 18, v -> doUndo());
        redoBtn = roundBtn("↷", 18, v -> doRedo());
        top.addView(undoBtn, Ui.lp(this, dp(40), dp(40), 6, 0));
        top.addView(redoBtn, Ui.lp(this, dp(40), dp(40), 6, 0));
        top.addView(roundBtn("⋮", 20, v -> showMainMenu(v)), Ui.lp(this, dp(40), dp(40), 6, 0));
        root.addView(top, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // lavagna
        board = new BasketBoardView(this);
        board.setPlay(play);
        root.addView(board, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        // pannello in basso: fasi e riproduzione
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xFF14171D);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(8));
        stepsScroll = new HorizontalScrollView(this);
        stepsScroll.setHorizontalScrollBarEnabled(false);
        stepsRow = new LinearLayout(this);
        stepsRow.setGravity(Gravity.CENTER_VERTICAL);
        stepsScroll.addView(stepsRow);
        bottom.addView(stepsScroll, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        TextView rew = roundBtn("⏮", 16, v -> board.stopLive());
        tr.addView(rew, new LinearLayout.LayoutParams(dp(42), dp(42)));
        playBtn = Ui.text(this, "▶", 20, 0xFF000000, true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), Ui.round(this, Ui.ACCENT, 100), null));
        playBtn.setClickable(true);
        playBtn.setOnClickListener(v -> {
            if (board.isPlaying()) {
                board.pause();
            } else {
                board.playAnim();
            }
        });
        tr.addView(playBtn, Ui.lp(this, dp(52), dp(52), 8, 0));
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    board.seekTo(board.total() * p / 1000.0);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                tracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                tracking = false;
            }
        });
        tr.addView(seek, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        timeTv = Ui.text(this, "0:00", 12, Ui.DIM, true);
        timeTv.setMinWidth(dp(66));
        timeTv.setGravity(Gravity.CENTER);
        tr.addView(timeTv);
        speedBtn = Ui.text(this, "1×", 13, Ui.ACCENT, true);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), Ui.round(this, Ui.CARD2, 100), null));
        speedBtn.setClickable(true);
        speedBtn.setOnClickListener(v -> {
            speedIx = (speedIx + 1) % speeds.length;
            board.setSpeed(speeds[speedIx]);
            speedBtn.setText(fmtSpeed(speeds[speedIx]));
        });
        tr.addView(speedBtn, new LinearLayout.LayoutParams(dp(44), dp(36)));
        bottom.addView(tr, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 8));
        root.addView(bottom, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        setContentView(root);

        try {
            lastSnap = play.toJson().toString();
        } catch (Exception ignored) {
        }
        board.setListener(this);
        refreshChrome();
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private TextView roundBtn(String label, int sp, View.OnClickListener l) {
        TextView t = Ui.text(this, label, sp, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), Ui.round(this, Ui.CARD2, 100), null));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private static String fmtSpeed(double s) {
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.format(Locale.ITALY, "%.1f", s)) + "×";
    }

    // ------------------------------------------------------------------------------ barra delle fasi

    private void refreshChrome() {
        BasketPlay p = board.model();
        titleTv.setText(p.name);
        int n = p.steps.size();
        int cur = board.isLive() ? shownStep : board.step();
        subTv.setText((p.full ? "Campo intero" : "Metà campo") + " · fase " + (Math.max(0, cur) + 1) + " di " + n);
        undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
        buildSteps(n, board.isLive() ? Math.max(0, shownStep) : board.step());
    }

    private void buildSteps(int n, int cur) {
        stepsRow.removeAllViews();
        for (int i = 0; i < n; i++) {
            final int k = i;
            TextView c = Ui.chip(this, "Fase " + (i + 1));
            c.setTextSize(13);
            c.setPadding(dp(16), 0, dp(16), 0);
            Ui.styleChip(this, c, i == cur);
            c.setOnClickListener(v -> {
                dismissMenu();
                board.stopLive();
                board.setStep(k);
                refreshChrome();
            });
            c.setOnLongClickListener(v -> {
                if (n > 1) {
                    confirmDeleteStep(k);
                }
                return true;
            });
            stepsRow.addView(c, Ui.lp(this, Ui.WRAP, dp(36), i == 0 ? 0 : 6, 0));
        }
        TextView add = Ui.chip(this, "+  Nuova fase");
        add.setTextSize(13);
        add.setTextColor(Ui.ACCENT);
        add.setPadding(dp(16), 0, dp(16), 0);
        add.setOnClickListener(v -> {
            dismissMenu();
            board.stopLive();
            board.model().steps.add(new BasketPlay.Step());
            board.setStep(board.model().steps.size() - 1);
            board.changed();
            refreshChrome();
            stepsScroll.post(() -> stepsScroll.fullScroll(View.FOCUS_RIGHT));
        });
        stepsRow.addView(add, Ui.lp(this, Ui.WRAP, dp(36), 6, 0));
    }

    private void confirmDeleteStep(final int k) {
        new AlertDialog.Builder(this).setTitle("Eliminare la fase " + (k + 1) + "?")
                .setMessage("I movimenti disegnati in questa fase vengono eliminati.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Elimina", (d, w) -> deleteStep(k)).show();
    }

    private void deleteStep(int k) {
        BasketPlay p = board.model();
        if (p.steps.size() <= 1) {
            return;
        }
        board.stopLive();
        p.steps.remove(k);
        board.setStep(Math.min(k, p.steps.size() - 1));
        board.changed();
        refreshChrome();
    }

    // ------------------------------------------------------------------------------ Listener della lavagna

    @Override
    public void onEdited() {
        try {
            String now = board.model().toJson().toString();
            if (!now.equals(lastSnap)) {
                undo.add(lastSnap);
                if (undo.size() > 60) {
                    undo.remove(0);
                }
                redo.clear();
                lastSnap = now;
            }
        } catch (Exception ignored) {
        }
        dirty = true;
        h.removeCallbacks(saveRun);
        h.postDelayed(saveRun, 1500);
        refreshChrome();
    }

    @Override
    public void onToast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDismiss() {
        dismissMenu();
    }

    @Override
    public void onPlayback(double t, double total, boolean playing, int step) {
        playBtn.setText(playing ? "⏸" : "▶");
        if (!tracking) {
            updatingSeek = true;
            seek.setProgress(total <= 0 ? 0 : (int) Math.round(Math.min(1, t / total) * 1000));
            updatingSeek = false;
        }
        timeTv.setText(fmtTime(t) + " / " + fmtTime(total));
        boolean liveNow = board.isLive();
        int st = liveNow ? step : board.step();
        if (st != shownStep || board.model().steps.size() != shownCount) {
            shownStep = st;
            shownCount = board.model().steps.size();
            refreshChrome();
        }
        if (!liveNow) {
            shownStep = -1;
        }
    }

    private static String fmtTime(double t) {
        int ds = (int) Math.round(t * 10);
        return String.format(Locale.ITALY, "%d:%02d", ds / 600, (ds / 10) % 60) ;
    }

    // ------------------------------------------------------------------------------ menu di giocatore / palla

    @Override
    public void onMenu(int ent, float sx, float sy, float radius) {
        dismissMenu();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), dp(6), dp(4), dp(6));
        col.setBackground(Ui.round(this, 0xF2171A21, 16));
        boolean ball = ent == BasketPlay.BALL;
        TextView head = Ui.text(this, ball ? "Palla" : "Giocatore " + (ent + 1), 11, Ui.DIM, true);
        head.setPadding(dp(12), dp(4), dp(12), dp(4));
        col.addView(head);
        if (ball) {
            addItem(col, BasketPlay.PASS, "Passaggio", () -> board.beginDraw(BasketPlay.PASS));
            addItem(col, BasketPlay.SHOT, "Tiro a canestro", () -> board.shoot());
            if (board.hasSeg(ent)) {
                addItem(col, -2, "Elimina il passaggio", () -> board.removeSeg(ent));
            }
        } else {
            addItem(col, BasketPlay.MOVE, "Movimento", () -> board.beginDraw(BasketPlay.MOVE));
            addItem(col, BasketPlay.SCREEN, "Blocco", () -> board.beginDraw(BasketPlay.SCREEN));
            if (board.holdsBall(ent)) {
                addItem(col, BasketPlay.DRIBBLE, "Palleggio", () -> board.beginDraw(BasketPlay.DRIBBLE));
            }
            if (board.step() == 0) {
                final boolean has = board.model().holder == ent;
                addItem(col, -1, has ? "Togli la palla" : "Ha la palla", () -> board.toggleHolder(ent));
            }
            if (board.hasSeg(ent)) {
                addItem(col, -2, "Elimina il percorso", () -> board.removeSeg(ent));
            }
        }
        final PopupWindow pw = new PopupWindow(col, Ui.WRAP, Ui.WRAP);
        pw.setOutsideTouchable(false);
        pw.setFocusable(false);
        pw.setElevation(dp(10));
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        col.measure(View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = Math.max(col.getMeasuredWidth(), dp(170)), hh = col.getMeasuredHeight();
        int[] loc = new int[2];
        board.getLocationOnScreen(loc);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int x = Math.round(loc[0] + sx - w / 2f);
        x = Math.max(dp(8), Math.min(sw - w - dp(8), x));
        int y = Math.round(loc[1] + sy - radius - hh - dp(12));
        if (y < loc[1] + dp(48)) {
            y = Math.round(loc[1] + sy + radius + dp(12));
        }
        pw.setWidth(w);
        menu = pw;
        pw.showAtLocation(board, Gravity.NO_GRAVITY, x, y);
    }

    private void addItem(LinearLayout col, int type, String label, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(2), dp(14), dp(2));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334DD0E1), null, null));
        row.setClickable(true);
        View ic = type >= 0 ? new LineIcon(this, type) : null;
        if (ic != null) {
            row.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(36)));
        } else {
            TextView e = Ui.text(this, type == -2 ? "🗑" : "🏀", 16, Ui.TEXT, false);
            e.setGravity(Gravity.CENTER);
            row.addView(e, new LinearLayout.LayoutParams(dp(44), dp(36)));
        }
        TextView t = Ui.text(this, label, 15, type == -2 ? Ui.DANGER : Ui.TEXT, true);
        t.setPadding(dp(6), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        row.setOnClickListener(v -> {
            dismissMenu();
            r.run();
        });
        col.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, dp(46)));
    }

    private void dismissMenu() {
        if (menu != null) {
            try {
                menu.dismiss();
            } catch (Exception ignored) {
            }
            menu = null;
        }
    }

    /** Miniatura dello stile di linea (movimento, blocco, palleggio, passaggio, tiro). */
    private static final class LineIcon extends View {
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        LineIcon(Context c, int type) {
            super(c);
            this.type = type;
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight(), y = h / 2, x0 = 5 * d, x1 = w - 5 * d;
            int col = type == BasketPlay.PASS ? 0xFFFF8A3D : type == BasketPlay.SHOT ? 0xFFFF5252 : 0xFF6FA8FF;
            p.setColor(col);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.6f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            Path path = new Path();
            if (type == BasketPlay.DRIBBLE) {
                path.moveTo(x0, y);
                int k = 0;
                for (float x = x0 + 3 * d; x < x1 - 8 * d; x += 4 * d) {
                    path.lineTo(x, y + ((k++ % 2 == 0) ? -4 : 4) * d);
                }
                path.lineTo(x1 - 6 * d, y);
            } else {
                path.moveTo(x0, y);
                path.lineTo(x1 - 4 * d, y);
            }
            if (type == BasketPlay.PASS || type == BasketPlay.SHOT) {
                p.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * d, 4 * d}, 0));
            }
            c.drawPath(path, p);
            p.setPathEffect(null);
            if (type == BasketPlay.SCREEN) {
                p.setStrokeWidth(3.2f * d);
                p.setStrokeCap(Paint.Cap.BUTT);
                c.drawLine(x1 - 3 * d, y - 8 * d, x1 - 3 * d, y + 8 * d, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                Path a = new Path();
                a.moveTo(x1 + 2 * d, y);
                a.lineTo(x1 - 7 * d, y - 5 * d);
                a.lineTo(x1 - 7 * d, y + 5 * d);
                a.close();
                c.drawPath(a, p);
            }
        }
    }

    // ------------------------------------------------------------------------------ menu principale

    private void showMainMenu(View anchor) {
        dismissMenu();
        final BasketPlay p = board.model();
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> acts = new ArrayList<>();
        labels.add("Formazione di partenza…");
        acts.add(this::pickFormation);
        labels.add(p.full ? "Mostra metà campo" : "Mostra campo intero");
        acts.add(() -> {
            board.setFull(!p.full);
            board.changed();
        });
        labels.add(board.showAll() ? "✓  Schema completo" : "Schema completo");
        acts.add(() -> {
            board.stopLive();
            board.setShowAll(!board.showAll());
            refreshChrome();
        });
        labels.add("Esporta come immagine");
        acts.add(this::exportImage);
        labels.add("Rinomina lo schema");
        acts.add(this::rename);
        if (p.steps.size() > 1) {
            labels.add("Elimina la fase " + (board.step() + 1));
            acts.add(() -> confirmDeleteStep(board.step()));
        }
        labels.add("Cancella tutti i movimenti");
        acts.add(this::confirmClear);
        labels.add("Come si usa");
        acts.add(this::help);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, dp(6));
        final PopupWindow pw = new PopupWindow(Ui.WRAP, Ui.WRAP);
        for (int i = 0; i < labels.size(); i++) {
            final Runnable r = acts.get(i);
            TextView t = Ui.text(this, labels.get(i), 15, Ui.TEXT, false);
            t.setPadding(dp(18), dp(12), dp(22), dp(12));
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
            t.setClickable(true);
            t.setOnClickListener(v -> {
                pw.dismiss();
                r.run();
            });
            col.addView(t, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        sv.setBackground(Ui.round(this, Ui.CARD2, 12));
        pw.setContentView(sv);
        pw.setWidth(dp(250));
        pw.setHeight(Ui.WRAP);
        pw.setFocusable(true);
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pw.setElevation(dp(12));
        pw.showAsDropDown(anchor, -dp(210), dp(4));
    }

    private void pickFormation() {
        new AlertDialog.Builder(this).setTitle("Formazione di partenza")
                .setMessage("Posiziona i 5 giocatori e cancella i movimenti già disegnati.")
                .setItems(BasketPlay.FORMATIONS, (d, which) -> {
                    board.stopLive();
                    board.model().formation(which);
                    board.changed();
                }).setNegativeButton("Annulla", null).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("Cancellare tutti i movimenti?")
                .setMessage("Restano le posizioni di partenza di giocatori e palla.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Cancella", (d, w) -> {
                    board.stopLive();
                    BasketPlay p = board.model();
                    p.steps.clear();
                    p.steps.add(new BasketPlay.Step());
                    board.setStep(0);
                    board.changed();
                }).show();
    }

    private void rename() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(board.model().name);
        in.setSelectAllOnFocus(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        in.setTextColor(Ui.TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(in, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(this).setTitle("Nome dello schema").setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (!n.isEmpty()) {
                        board.model().name = n;
                        board.changed();
                    }
                }).show();
    }

    private void help() {
        new AlertDialog.Builder(this).setTitle("Come si usa")
                .setMessage("• Fase 1: trascina i giocatori e la palla dove vuoi. Per dare la palla a un giocatore "
                        + "trascinala sopra di lui oppure tocca il giocatore e scegli \"Ha la palla\".\n\n"
                        + "• Tocca un giocatore: scegli Movimento, Blocco o Palleggio (se ha la palla) e disegna "
                        + "il percorso trascinando il dito dal giocatore.\n\n"
                        + "• Tocca la palla: disegna il passaggio fino a un compagno (si aggancia da solo a dove "
                        + "arriverà), oppure scegli Tiro a canestro.\n\n"
                        + "• Ogni fase parte quando finiscono i movimenti della precedente: con \"+ Nuova fase\" "
                        + "aggiungi il passo successivo. Dalla fase 2 basta trascinare un giocatore per disegnarne il movimento. "
                        + "Tieni premuto su \"Fase N\" per eliminarla.\n\n"
                        + "• ▶ avvia l'animazione; la barra scorre il tempo e il pulsante \"1×\" cambia la velocità. "
                        + "\"Schema completo\" mostra tutte le fasi insieme.")
                .setPositiveButton("Ok", null).show();
    }

    private void exportImage() {
        board.stopLive();
        final Bitmap bmp = board.exportBitmap();
        if (bmp == null) {
            return;
        }
        String name = board.model().name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) {
            name = "schema";
        }
        try {
            FileUtil.save(this, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/MamoTools",
                    name + ".png", "image/png", new FileUtil.Writer() {
                        @Override
                        public void write(OutputStream os) throws IOException {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                        }
                    });
            Toast.makeText(this, "Salvato in Immagini/MamoTools", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Esportazione non riuscita: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------------------ cronologia e salvataggio

    private void doUndo() {
        if (undo.isEmpty()) {
            return;
        }
        try {
            redo.add(lastSnap);
            lastSnap = undo.remove(undo.size() - 1);
            restore(lastSnap);
        } catch (Exception ignored) {
        }
    }

    private void doRedo() {
        if (redo.isEmpty()) {
            return;
        }
        try {
            undo.add(lastSnap);
            lastSnap = redo.remove(redo.size() - 1);
            restore(lastSnap);
        } catch (Exception ignored) {
        }
    }

    private void restore(String json) throws Exception {
        BasketPlay p = BasketPlay.fromJson(new JSONObject(json));
        board.setPlay(p);
        dirty = true;
        h.removeCallbacks(saveRun);
        h.postDelayed(saveRun, 1500);
        refreshChrome();
    }

    private void save() {
        h.removeCallbacks(saveRun);
        if (board == null || !dirty) {
            return;
        }
        try {
            store.write(id, board.model().toJson());
            dirty = false;
        } catch (Exception e) {
            Toast.makeText(this, "Salvataggio non riuscito.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (menu != null) {
            dismissMenu();
            return;
        }
        if (board != null && board.isLive()) {
            board.stopLive();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (board != null && board.isPlaying()) {
            board.pause();
        }
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        save();
    }
}
