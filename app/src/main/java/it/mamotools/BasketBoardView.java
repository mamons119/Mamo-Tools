package it.mamotools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/** Lavagna del basket: campo, giocatori, palla, disegno dei movimenti e animazione. */
final class BasketBoardView extends View {

    interface Listener {
        /** Tocco su un giocatore (0-4) o sulla palla (5): sx, sy sono il suo centro nella vista. */
        void onMenu(int ent, float sx, float sy, float radius);

        void onDismiss();

        /** Lo schema e' cambiato (salvataggio e cronologia). */
        void onEdited();

        void onToast(String s);

        /** Avanzamento dell'animazione. */
        void onPlayback(double t, double total, boolean playing, int step);
    }

    static final int NONE = -1;
    private static final int COL_PLAYER = 0xFF1F6FD1;
    private static final int COL_LINE = 0xFF0D2F6B;
    private static final int COL_PASS = 0xFFD35400;
    private static final int COL_SHOT = 0xFFB71C1C;

    private final float dp;
    private BasketPlay play = new BasketPlay();
    private BasketPlay.Sim sim;
    private Listener lis;
    private int step = 0;

    // trasformazione campo -> schermo
    private final Matrix w2s = new Matrix();
    private boolean portrait = true;
    private float sc = 1, ox, oy;
    private Bitmap court;
    private int courtW, courtH;
    private boolean courtFull, courtPortrait;

    // stato dell'interazione
    private int sel = NONE;
    private int drawType = NONE;
    private int lastType = BasketPlay.MOVE;
    private boolean showAll = false;
    private boolean exporting = false;

    private int hit = NONE;
    private float downX, downY;
    private boolean dragging, stroking;
    private int strokeEnt, strokeType;
    private final ArrayList<BasketPlay.Pt> stroke = new ArrayList<>();
    private int snapCand = NONE;

    // animazione
    private boolean playing = false;
    private boolean live = false;           // animazione in corso o ferma a un istante (modifica bloccata)
    private double t = 0;
    private double speed = 1;
    private long lastTick;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    BasketBoardView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        resim();
    }

    // ------------------------------------------------------------------------------ API

    void setListener(Listener l) {
        lis = l;
    }

    BasketPlay model() {
        return play;
    }

    void setPlay(BasketPlay pl) {
        play = pl;
        step = Math.min(step, play.steps.size() - 1);
        sel = NONE;
        drawType = NONE;
        stopLive();
        courtW = 0;
        layoutCourt();
        resim();
        invalidate();
    }

    int step() {
        return step;
    }

    void setStep(int s) {
        step = Math.max(0, Math.min(play.steps.size() - 1, s));
        sel = NONE;
        drawType = NONE;
        if (!playing) {
            stopLive();
        }
        invalidate();
    }

    void resim() {
        sim = play.simulate();
    }

    /** Dopo una modifica. */
    void changed() {
        resim();
        invalidate();
        if (lis != null) {
            lis.onEdited();
        }
    }

    void setFull(boolean full) {
        play.full = full;
        courtW = 0;
        layoutCourt();
        invalidate();
    }

    void setShowAll(boolean on) {
        showAll = on;
        sel = NONE;
        drawType = NONE;
        invalidate();
    }

    boolean showAll() {
        return showAll;
    }

    void clearSelection() {
        sel = NONE;
        drawType = NONE;
        invalidate();
    }

    void beginDraw(int type) {
        drawType = type;
        if (type == BasketPlay.MOVE || type == BasketPlay.DRIBBLE) {
            lastType = type;
        }
        invalidate();
    }

    boolean holdsBall(int i) {
        return BasketPlay.startOf(sim, step).holder == i;
    }

    boolean hasSeg(int ent) {
        return play.steps.get(step).seg[ent] != null;
    }

    void removeSeg(int ent) {
        play.steps.get(step).seg[ent] = null;
        changed();
    }

    /** Tiro: la palla parte da chi la tiene (o da dov'e') e va al canestro. */
    void shoot() {
        BasketPlay.StepSim ss = sim.steps.get(step);
        BasketPlay.Seg s = new BasketPlay.Seg();
        s.type = BasketPlay.SHOT;
        s.pts.add(ss.ballStart.copy());
        s.pts.add(play.hoopFor(ss.ballStart));
        play.steps.get(step).seg[BasketPlay.BALL] = s;
        sel = NONE;
        changed();
    }

    /** Da' (o toglie) la palla a un giocatore, nella posizione di partenza. */
    void toggleHolder(int i) {
        if (step != 0) {
            return;
        }
        if (play.holder == i) {
            play.holder = -1;
            BasketPlay.Pt q = play.start[i];
            play.ball = new BasketPlay.Pt(q.x + 0.9, q.y - 0.9);
        } else {
            play.holder = i;
            play.ball = play.start[i].copy();
        }
        changed();
    }

    // ------------------------------------------------------------------------------ animazione

    double total() {
        return sim.total;
    }

    boolean isPlaying() {
        return playing;
    }

    boolean isLive() {
        return live;
    }

    void setSpeed(double s) {
        speed = s;
    }

    void playAnim() {
        if (sim.total <= 0.01) {
            if (lis != null) {
                lis.onToast("Disegna almeno un movimento da animare.");
            }
            return;
        }
        sel = NONE;
        drawType = NONE;
        if (lis != null) {
            lis.onDismiss();
        }
        if (!live || t >= sim.total - 0.001) {
            t = 0;
        }
        live = true;
        playing = true;
        lastTick = SystemClock.uptimeMillis();
        postOnAnimation(ticker);
        notifyPlayback();
        invalidate();
    }

    void pause() {
        playing = false;
        notifyPlayback();
    }

    /** Torna alla modalita' di modifica. */
    void stopLive() {
        playing = false;
        live = false;
        t = 0;
        notifyPlayback();
        invalidate();
    }

    void seekTo(double time) {
        if (time <= 0.0005) {
            stopLive();
            return;
        }
        playing = false;
        live = true;
        t = Math.min(time, sim.total);
        sel = NONE;
        drawType = NONE;
        if (lis != null) {
            lis.onDismiss();
        }
        notifyPlayback();
        invalidate();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!playing) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            t += (now - lastTick) / 1000.0 * speed;
            lastTick = now;
            if (t >= sim.total) {
                t = sim.total;
                playing = false;
            }
            notifyPlayback();
            invalidate();
            if (playing) {
                postOnAnimation(this);
            }
        }
    };

    private void notifyPlayback() {
        if (lis != null) {
            int st = live ? BasketPlay.frameAt(sim, t).step : step;
            lis.onPlayback(t, sim.total, playing, st);
        }
    }

    // ------------------------------------------------------------------------------ geometria

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutCourt();
    }

    private void layoutCourt() {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) {
            return;
        }
        RectF b = BasketCourt.bounds(play.full);
        float bw = b.width(), bh = b.height();
        float sp = Math.min(W / bh, H / bw);
        float sl = Math.min(W / bw, H / bh);
        portrait = sp >= sl;
        sc = portrait ? sp : sl;
        float[] v;
        if (portrait) {
            ox = (W - bh * sc) / 2 - b.top * sc;
            oy = (H - bw * sc) / 2 - b.left * sc;
            v = new float[]{0, sc, ox, sc, 0, oy, 0, 0, 1};
        } else {
            ox = (W - bw * sc) / 2 - b.left * sc;
            oy = (H - bh * sc) / 2 - b.top * sc;
            v = new float[]{sc, 0, ox, 0, sc, oy, 0, 0, 1};
        }
        w2s.setValues(v);
        if (court == null || courtW != W || courtH != H || courtFull != play.full || courtPortrait != portrait) {
            courtW = W;
            courtH = H;
            courtFull = play.full;
            courtPortrait = portrait;
            court = BasketCourt.render(W, H, play.full, w2s);
        }
    }

    private float sx(double x, double y) {
        return portrait ? (float) (sc * y + ox) : (float) (sc * x + ox);
    }

    private float sy(double x, double y) {
        return portrait ? (float) (sc * x + oy) : (float) (sc * y + oy);
    }

    private BasketPlay.Pt world(float px, float py) {
        if (portrait) {
            return new BasketPlay.Pt((py - oy) / sc, (px - ox) / sc);
        }
        return new BasketPlay.Pt((px - ox) / sc, (py - oy) / sc);
    }

    private float rPlayer() {
        return Math.max(15 * dp, Math.min(24 * dp, 0.62f * sc));
    }

    private float rBall() {
        return Math.max(9 * dp, Math.min(13 * dp, 0.32f * sc));
    }

    /** Spostamento della palla rispetto al giocatore che la tiene (in pixel). */
    private float carryDx() {
        return rPlayer() * 1.1f;
    }

    private float carryDy() {
        return rPlayer() * 0.7f;
    }

    private BasketPlay.Frame editFrame() {
        return BasketPlay.startOf(sim, step);
    }

    private float[] entityXY(int ent, BasketPlay.Frame f) {
        if (ent == BasketPlay.BALL) {
            return new float[]{sx(f.ball.x, f.ball.y) + (float) f.carry * carryDx(),
                    sy(f.ball.x, f.ball.y) + (float) f.carry * carryDy()};
        }
        return new float[]{sx(f.p[ent].x, f.p[ent].y), sy(f.p[ent].x, f.p[ent].y)};
    }

    // ------------------------------------------------------------------------------ disegno

    @Override
    protected void onDraw(Canvas c) {
        if (getWidth() <= 0) {
            return;
        }
        if (court == null) {
            layoutCourt();
        }
        if (court != null) {
            c.drawBitmap(court, 0, 0, null);
        }
        BasketPlay.Frame f;
        if (showAll) {
            f = BasketPlay.startOf(sim, 0);
        } else if (live) {
            f = BasketPlay.frameAt(sim, t);
        } else {
            f = editFrame();
        }
        int curStep = live && !showAll ? f.step : step;

        // percorsi
        for (int k = 0; k < sim.steps.size(); k++) {
            BasketPlay.StepSim ss = sim.steps.get(k);
            int alpha = showAll ? 255 : live ? (k <= curStep ? 150 : 60) : (k == curStep ? 255 : 80);
            for (int e = 0; e <= BasketPlay.NP; e++) {
                if (ss.dense[e] != null) {
                    drawPath(c, ss.dense[e], ss.type[e], alpha);
                }
            }
            if (showAll && sim.steps.size() > 1) {
                for (int e = 0; e <= BasketPlay.NP; e++) {
                    if (ss.dense[e] != null && ss.dense[e].size() > 1) {
                        BasketPlay.Pt a = ss.dense[e].get(ss.dense[e].size() - 1);
                        badge(c, sx(a.x, a.y), sy(a.x, a.y), String.valueOf(k + 1));
                    }
                }
            }
        }

        // posizioni di arrivo (fantasmi) nella fase in modifica
        if (!live && !showAll && !exporting) {
            BasketPlay.StepSim ss = sim.steps.get(step);
            for (int i = 0; i < BasketPlay.NP; i++) {
                if (ss.poly[i] != null) {
                    float gx = sx(ss.endPos[i].x, ss.endPos[i].y), gy = sy(ss.endPos[i].x, ss.endPos[i].y);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x661F6FD1);
                    c.drawCircle(gx, gy, rPlayer(), p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * dp);
                    p.setColor(0xAAFFFFFF);
                    c.drawCircle(gx, gy, rPlayer(), p);
                    label(c, String.valueOf(i + 1), gx, gy, rPlayer(), 0xCCFFFFFF);
                }
            }
        }

        // tratto in corso
        if (stroking && stroke.size() > 1) {
            drawPath(c, stroke, strokeType, 255);
        }

        // giocatori
        for (int i = 0; i < BasketPlay.NP; i++) {
            float[] q = entityXY(i, f);
            float r = rPlayer();
            // ombra
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55000000);
            c.drawCircle(q[0] + 2 * dp, q[1] + 3 * dp, r, p);
            if (!exporting && !live && (sel == i || snapCand == i)) {
                p.setColor(0xFFFFD54F);
                c.drawCircle(q[0], q[1], r + 5 * dp, p);
            }
            if (f.holder == i && !showAll) {
                p.setColor(0xFFFF9800);
                c.drawCircle(q[0], q[1], r + 3 * dp, p);
            }
            p.setColor(COL_PLAYER);
            c.drawCircle(q[0], q[1], r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * dp);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(q[0], q[1], r, p);
            label(c, String.valueOf(i + 1), q[0], q[1], r, 0xFFFFFFFF);
        }

        // palla
        float[] bq = entityXY(BasketPlay.BALL, f);
        float rb = rBall();
        float by = bq[1];
        if (f.dribble && !showAll) {
            double ph = Math.abs(Math.sin(f.phase * Math.PI * 2.4));
            by -= (float) (ph * rPlayer() * 1.1);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        c.drawOval(bq[0] - rb * 0.9f, bq[1] + rb * 0.55f, bq[0] + rb * 0.9f, bq[1] + rb * 1.15f, p);
        if (!exporting && !live && sel == BasketPlay.BALL) {
            p.setColor(0xFFFFD54F);
            c.drawCircle(bq[0], by, rb + 5 * dp, p);
        }
        p.setColor(0xFFE8751A);
        c.drawCircle(bq[0], by, rb, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f * dp);
        p.setColor(0xFF3B1A08);
        c.drawCircle(bq[0], by, rb, p);
        c.drawLine(bq[0] - rb, by, bq[0] + rb, by, p);
        c.drawLine(bq[0], by - rb, bq[0], by + rb, p);
        path.reset();
        path.addArc(bq[0] - rb * 1.7f, by - rb, bq[0] - rb * 0.1f, by + rb, -60, 120);
        c.drawPath(path, p);
        path.reset();
        path.addArc(bq[0] + rb * 0.1f, by - rb, bq[0] + rb * 1.7f, by + rb, 120, 120);
        c.drawPath(path, p);

        if (exporting) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(17 * dp);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(4 * dp, 0, 0, 0xFF000000);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(play.name, 14 * dp, 28 * dp, p);
            p.setShadowLayer(0, 0, 0, 0);
            return;
        }

        // suggerimenti
        String hint = null;
        if (stroking || drawType != NONE) {
            switch (drawType != NONE ? drawType : strokeType) {
                case BasketPlay.MOVE:
                    hint = "Trascina dal giocatore per disegnare il movimento";
                    break;
                case BasketPlay.SCREEN:
                    hint = "Trascina dal giocatore: il blocco finisce con una barra";
                    break;
                case BasketPlay.DRIBBLE:
                    hint = "Trascina dal giocatore per disegnare il palleggio";
                    break;
                default:
                    hint = "Trascina dalla palla fino al compagno (o dove vuoi)";
            }
        } else if (!live && !showAll && step == 0 && sel == NONE && !anySeg()) {
            hint = "Trascina giocatori e palla per posizionarli · tocca un giocatore per disegnare";
        } else if (showAll) {
            hint = "Schema completo · i numeri indicano la fase";
        }
        if (hint != null) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(12.5f * dp);
            float tw = p.measureText(hint);
            float maxW = getWidth() - 24 * dp;
            if (tw > maxW) {
                p.setTextSize(11f * dp);
                tw = p.measureText(hint);
            }
            tw = Math.min(tw, maxW);
            float bx = (getWidth() - tw) / 2 - 12 * dp, byy = 10 * dp;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC0F1115);
            c.drawRoundRect(bx, byy, bx + tw + 24 * dp, byy + 32 * dp, 16 * dp, 16 * dp, p);
            p.setColor(0xFFFFFFFF);
            p.setTextAlign(Paint.Align.LEFT);
            c.save();
            c.clipRect(bx, byy, bx + tw + 24 * dp, byy + 32 * dp);
            c.drawText(hint, bx + 12 * dp, byy + 21 * dp, p);
            c.restore();
        }
    }

    private boolean anySeg() {
        for (BasketPlay.Step s : play.steps) {
            if (!s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void label(Canvas c, String s, float cx, float cy, float r, int col) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(r * 1.05f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, p);
    }

    private void badge(Canvas c, float x, float y, String s) {
        float r = 9 * dp;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0F1115);
        c.drawCircle(x + r, y - r, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(x + r, y - r, r, p);
        label(c, s, x + r, y - r, r * 1.0f, 0xFFFFFFFF);
    }

    /** Linea di un movimento: continua (movimento), a zig-zag (palleggio), con barra (blocco), tratteggiata (passaggio). */
    private void drawPath(Canvas c, java.util.List<BasketPlay.Pt> pts, int type, int alpha) {
        int n = pts.size();
        if (n < 2) {
            return;
        }
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = sx(pts.get(i).x, pts.get(i).y);
            ys[i] = sy(pts.get(i).x, pts.get(i).y);
        }
        float[][] pl = {xs, ys};
        if (type == BasketPlay.DRIBBLE) {
            pl = zigzag(xs, ys);
        }
        xs = pl[0];
        ys = pl[1];
        n = xs.length;
        path.reset();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            path.lineTo(xs[i], ys[i]);
        }
        int col = type == BasketPlay.PASS ? COL_PASS : type == BasketPlay.SHOT ? COL_SHOT : COL_LINE;
        // alone chiaro sotto la linea
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setPathEffect(null);
        p.setStrokeWidth(6.5f * dp);
        p.setColor(((int) (alpha * 0.55f) << 24) | 0xFFFFFF);
        c.drawPath(path, p);
        if (type == BasketPlay.PASS || type == BasketPlay.SHOT) {
            p.setPathEffect(new DashPathEffect(new float[]{10 * dp, 7 * dp}, 0));
        }
        p.setStrokeWidth(3f * dp);
        p.setColor((alpha << 24) | (col & 0xFFFFFF));
        c.drawPath(path, p);
        p.setPathEffect(null);
        // punta o barra finale
        int last = n - 1, k = last;
        float need = 12 * dp;
        float acc = 0;
        while (k > 0 && acc < need) {
            acc += (float) Math.hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1]);
            k--;
        }
        float dx = xs[last] - xs[k], dy = ys[last] - ys[k];
        float d = (float) Math.hypot(dx, dy);
        if (d < 1e-3f) {
            return;
        }
        dx /= d;
        dy /= d;
        float ex = xs[last], ey = ys[last];
        p.setColor((alpha << 24) | (col & 0xFFFFFF));
        if (type == BasketPlay.SCREEN) {
            float w = 13 * dp;
            p.setStrokeWidth(4f * dp);
            p.setStrokeCap(Paint.Cap.BUTT);
            c.drawLine(ex - dy * w, ey + dx * w, ex + dy * w, ey - dx * w, p);
        } else {
            float len = 14 * dp, wd = 7.5f * dp;
            path.reset();
            path.moveTo(ex + dx * 3 * dp, ey + dy * 3 * dp);
            path.lineTo(ex - dx * len - dy * wd, ey - dy * len + dx * wd);
            path.lineTo(ex - dx * len + dy * wd, ey - dy * len - dx * wd);
            path.close();
            p.setStyle(Paint.Style.FILL);
            c.drawPath(path, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private float[][] zigzag(float[] xs, float[] ys) {
        int n = xs.length;
        float[] cum = new float[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        float len = cum[n - 1];
        int m = Math.max(2, (int) (len / (2.5f * dp)));
        float[] ox2 = new float[m + 1], oy2 = new float[m + 1];
        int seg = 1;
        float amp = 5.5f * dp, wave = 22 * dp;
        for (int j = 0; j <= m; j++) {
            float d = len * j / m;
            while (seg < n - 1 && cum[seg] < d) {
                seg++;
            }
            float sl = cum[seg] - cum[seg - 1];
            float u = sl < 1e-6f ? 0 : (d - cum[seg - 1]) / sl;
            float x = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * u;
            float y = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * u;
            float tx = xs[seg] - xs[seg - 1], ty = ys[seg] - ys[seg - 1];
            float tl = (float) Math.hypot(tx, ty);
            if (tl > 1e-6f) {
                tx /= tl;
                ty /= tl;
            }
            float ramp = Math.min(1f, Math.min(d, len - d) / (14 * dp));
            float off = (float) Math.sin(d / wave * 2 * Math.PI) * amp * ramp;
            ox2[j] = x - ty * off;
            oy2[j] = y + tx * off;
        }
        return new float[][]{ox2, oy2};
    }

    // ------------------------------------------------------------------------------ tocco

    private int hitTest(float x, float y) {
        BasketPlay.Frame f = editFrame();
        float[] bq = entityXY(BasketPlay.BALL, f);
        float rb = Math.max(rBall() * 2.1f, 24 * dp);
        if (Math.hypot(x - bq[0], y - bq[1]) <= rb) {
            return BasketPlay.BALL;
        }
        int best = NONE;
        double bd = Math.max(rPlayer() * 1.3f, 26 * dp);
        for (int i = 0; i < BasketPlay.NP; i++) {
            float[] q = entityXY(i, f);
            double d = Math.hypot(x - q[0], y - q[1]);
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (live || showAll) {
            if (e.getAction() == MotionEvent.ACTION_UP && live) {
                // un tocco sulla lavagna ferma o riprende l'animazione
                if (playing) {
                    pause();
                } else if (t < sim.total - 0.001) {
                    playAnim();
                }
            }
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                dragging = false;
                stroking = false;
                snapCand = NONE;
                hit = NONE;
                if (drawType != NONE && sel != NONE) {
                    BasketPlay.Frame f = editFrame();
                    float[] q = entityXY(sel, f);
                    if (Math.hypot(x - q[0], y - q[1]) <= Math.max(rPlayer() * 2.3f, 56 * dp)) {
                        beginStroke(sel, drawType, q);
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    drawType = NONE;
                }
                hit = hitTest(x, y);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (stroking) {
                    BasketPlay.Pt w = world(x, y);
                    BasketPlay.Pt last = stroke.get(stroke.size() - 1);
                    if (Math.hypot((w.x - last.x) * sc, (w.y - last.y) * sc) >= 5 * dp) {
                        stroke.add(w);
                        invalidate();
                    }
                    return true;
                }
                if (hit != NONE) {
                    if (!dragging && Math.hypot(x - downX, y - downY) > 8 * dp) {
                        dragging = true;
                        if (lis != null) {
                            lis.onDismiss();
                        }
                        if (step != 0) {
                            // dalla seconda fase in poi trascinare un'entita' significa disegnarne il movimento
                            int type = hit == BasketPlay.BALL ? BasketPlay.PASS
                                    : (lastType == BasketPlay.DRIBBLE && holdsBall(hit)) ? BasketPlay.DRIBBLE
                                    : BasketPlay.MOVE;
                            sel = hit;
                            beginStroke(hit, type, entityXY(hit, editFrame()));
                            return true;
                        }
                        sel = NONE;
                        if (hit == BasketPlay.BALL) {
                            play.holder = -1;
                        }
                    }
                    if (dragging) {
                        dragEntity(x, y);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (stroking) {
                    finishStroke();
                    return true;
                }
                if (dragging && hit != NONE) {
                    if (hit == BasketPlay.BALL) {
                        int near = nearestPlayer(world(x, y), 1.25, -1);
                        play.holder = near;
                        if (near >= 0) {
                            play.ball = play.start[near].copy();
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        }
                    }
                    snapCand = NONE;
                    dragging = false;
                    changed();
                    return true;
                }
                if (hit != NONE) {
                    sel = hit;
                    drawType = NONE;
                    BasketPlay.Frame f = editFrame();
                    float[] q = entityXY(hit, f);
                    if (lis != null) {
                        lis.onMenu(hit, q[0], q[1], hit == BasketPlay.BALL ? rBall() : rPlayer());
                    }
                } else {
                    sel = NONE;
                    drawType = NONE;
                    if (lis != null) {
                        lis.onDismiss();
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                stroking = false;
                dragging = false;
                snapCand = NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void dragEntity(float x, float y) {
        BasketPlay.Pt w = world(x, y);
        RectF b = BasketCourt.bounds(play.full);
        w.x = Math.max(b.left + 0.3, Math.min(b.right - 0.3, w.x));
        w.y = Math.max(b.top + 0.3, Math.min(b.bottom - 0.3, w.y));
        if (hit == BasketPlay.BALL) {
            play.ball = w;
            play.holder = -1;
            int near = nearestPlayer(w, 1.25, -1);
            if (near != snapCand) {
                snapCand = near;
                if (near >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
        } else {
            play.moveStart(hit, w.x, w.y);
        }
        resim();
        invalidate();
    }

    private int nearestPlayer(BasketPlay.Pt w, double maxD, int except) {
        int best = -1;
        double bd = maxD;
        for (int i = 0; i < BasketPlay.NP; i++) {
            if (i == except) {
                continue;
            }
            double d = Math.hypot(play.start[i].x - w.x, play.start[i].y - w.y);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private void beginStroke(int ent, int type, float[] q) {
        stroking = true;
        strokeEnt = ent;
        strokeType = type;
        stroke.clear();
        stroke.add(world(q[0], q[1]));
        BasketPlay.Frame f = editFrame();
        // il tratto parte dalla posizione vera dell'entita' (senza lo spostamento grafico della palla)
        if (ent == BasketPlay.BALL) {
            stroke.set(0, f.ball.copy());
        } else {
            stroke.set(0, f.p[ent].copy());
        }
        if (lis != null) {
            lis.onDismiss();
        }
        invalidate();
    }

    private void finishStroke() {
        stroking = false;
        drawType = NONE;
        double len = 0;
        for (int i = 1; i < stroke.size(); i++) {
            len += Math.hypot(stroke.get(i).x - stroke.get(i - 1).x, stroke.get(i).y - stroke.get(i - 1).y);
        }
        if (len < 0.9) {
            invalidate();
            if (lis != null) {
                lis.onToast("Tratto troppo corto: trascina piu' lontano.");
            }
            return;
        }
        ArrayList<BasketPlay.Pt> ctrl = BasketPlay.simplify(stroke, 0.22);
        BasketPlay.Seg s = new BasketPlay.Seg();
        s.type = strokeType;
        s.pts.addAll(ctrl);
        if (strokeEnt == BasketPlay.BALL) {
            BasketPlay.StepSim ss = sim.steps.get(step);
            BasketPlay.Pt e = s.pts.get(s.pts.size() - 1);
            int tg = -1;
            double bd = 1.9;
            for (int i = 0; i < BasketPlay.NP; i++) {
                if (i == ss.holderStart) {
                    continue;
                }
                double d = Math.hypot(ss.endPos[i].x - e.x, ss.endPos[i].y - e.y);
                if (d < bd) {
                    bd = d;
                    tg = i;
                }
            }
            s.target = tg;
            if (tg >= 0) {
                s.pts.set(s.pts.size() - 1, ss.endPos[tg].copy());
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            }
        }
        if (strokeType == BasketPlay.MOVE || strokeType == BasketPlay.DRIBBLE) {
            lastType = strokeType;
        }
        play.steps.get(step).seg[strokeEnt] = s;
        changed();
    }

    // ------------------------------------------------------------------------------ esportazione

    Bitmap exportBitmap() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        boolean oa = showAll;
        boolean ol = live;
        showAll = true;
        live = false;
        exporting = true;
        draw(c);
        exporting = false;
        showAll = oa;
        live = ol;
        return bmp;
    }
}
