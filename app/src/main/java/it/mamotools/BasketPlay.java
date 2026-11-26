package it.mamotools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema di basket: modello dei dati e simulazione dell'animazione (nessuna dipendenza dall'interfaccia).
 *
 * Le coordinate sono in metri sul campo FIBA (28 x 15 m): x e' la lunghezza (il canestro d'attacco e' vicino a x = 0),
 * y e' la larghezza. Lo schema e' diviso in fasi: in ogni fase ogni giocatore (e la palla) puo' avere al piu' un
 * movimento; tutti i movimenti della fase partono insieme e la fase successiva comincia quando sono finiti tutti.
 */
final class BasketPlay {

    static final int NP = 5;          // giocatori
    static final int BALL = 5;        // indice della palla tra le "entita'"
    static final int MOVE = 0, SCREEN = 1, DRIBBLE = 2, PASS = 3, SHOT = 4;

    static final double COURT_L = 28, COURT_W = 15;
    static final double HOOP_X = 1.575, HOOP_Y = 7.5;
    static final double PAUSE = 0.45;     // pausa tra una fase e la successiva (secondi)

    static final class Pt {
        double x, y;

        Pt(double x, double y) {
            this.x = x;
            this.y = y;
        }

        Pt copy() {
            return new Pt(x, y);
        }
    }

    static final class Seg {
        int type;
        int target = -1;                  // passaggio: giocatore che riceve (-1 nessuno)
        ArrayList<Pt> pts = new ArrayList<>();

        Seg copy() {
            Seg s = new Seg();
            s.type = type;
            s.target = target;
            for (Pt p : pts) {
                s.pts.add(p.copy());
            }
            return s;
        }
    }

    static final class Step {
        Seg[] seg = new Seg[NP + 1];

        Step copy() {
            Step s = new Step();
            for (int i = 0; i < seg.length; i++) {
                s.seg[i] = seg[i] == null ? null : seg[i].copy();
            }
            return s;
        }

        boolean isEmpty() {
            for (Seg s : seg) {
                if (s != null) {
                    return false;
                }
            }
            return true;
        }
    }

    String name = "Nuovo schema";
    boolean full = false;                 // campo intero o meta' campo
    Pt[] start = new Pt[NP];
    Pt ball = new Pt(0, 0);
    int holder = -1;                      // giocatore che ha la palla all'inizio (-1 palla libera)
    final ArrayList<Step> steps = new ArrayList<>();

    BasketPlay() {
        formation(0);
        steps.add(new Step());
    }

    /** Formazioni di partenza (meta' campo). */
    static final String[] FORMATIONS = {"5 fuori", "4 fuori 1 dentro", "Box (2-2 sul post)", "1-4 alto"};

    void formation(int k) {
        double[][] f;
        switch (k) {
            case 1:
                f = new double[][]{{9.6, 7.5}, {7.4, 1.9}, {7.4, 13.1}, {4.6, 3.4}, {2.9, 7.5}};
                break;
            case 2:
                f = new double[][]{{9.2, 7.5}, {7.0, 12.2}, {3.6, 5.05}, {3.6, 9.95}, {7.0, 2.8}};
                break;
            case 3:
                f = new double[][]{{10.0, 7.5}, {5.8, 2.0}, {5.8, 13.0}, {5.8, 5.05}, {5.8, 9.95}};
                break;
            default:
                f = new double[][]{{9.8, 7.5}, {6.4, 1.6}, {6.4, 13.4}, {3.6, 3.4}, {3.6, 11.6}};
        }
        for (int i = 0; i < NP; i++) {
            start[i] = new Pt(f[i][0], f[i][1]);
        }
        holder = 0;
        ball = start[0].copy();
        // gli spostamenti gia' disegnati non hanno piu' senso con posizioni diverse
        for (Step s : steps) {
            for (int i = 0; i < s.seg.length; i++) {
                s.seg[i] = null;
            }
        }
    }

    // ------------------------------------------------------------------------------ percorsi

    /** Elimina i punti superflui (Ramer-Douglas-Peucker). */
    static ArrayList<Pt> simplify(List<Pt> in, double eps) {
        ArrayList<Pt> out = new ArrayList<>();
        if (in.size() < 3) {
            for (Pt p : in) {
                out.add(p.copy());
            }
            return out;
        }
        boolean[] keep = new boolean[in.size()];
        keep[0] = true;
        keep[in.size() - 1] = true;
        rdp(in, 0, in.size() - 1, eps, keep);
        for (int i = 0; i < in.size(); i++) {
            if (keep[i]) {
                out.add(in.get(i).copy());
            }
        }
        return out;
    }

    private static void rdp(List<Pt> p, int a, int b, double eps, boolean[] keep) {
        double dmax = 0;
        int idx = -1;
        for (int i = a + 1; i < b; i++) {
            double d = distSeg(p.get(i), p.get(a), p.get(b));
            if (d > dmax) {
                dmax = d;
                idx = i;
            }
        }
        if (idx >= 0 && dmax > eps) {
            keep[idx] = true;
            rdp(p, a, idx, eps, keep);
            rdp(p, idx, b, eps, keep);
        }
    }

    private static double distSeg(Pt p, Pt a, Pt b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double l2 = dx * dx + dy * dy;
        if (l2 < 1e-12) {
            return Math.hypot(p.x - a.x, p.y - a.y);
        }
        double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2));
        return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
    }

    /** Curva morbida (Catmull-Rom) che passa per i punti dati, campionata ogni ~10 cm. */
    static ArrayList<Pt> smooth(List<Pt> c) {
        ArrayList<Pt> out = new ArrayList<>();
        int n = c.size();
        if (n == 0) {
            return out;
        }
        if (n == 1) {
            out.add(c.get(0).copy());
            return out;
        }
        for (int i = 0; i < n - 1; i++) {
            Pt p0 = c.get(Math.max(0, i - 1)), p1 = c.get(i), p2 = c.get(i + 1), p3 = c.get(Math.min(n - 1, i + 2));
            double len = Math.hypot(p2.x - p1.x, p2.y - p1.y);
            int k = Math.max(1, (int) Math.ceil(len / 0.1));
            for (int j = 0; j < k; j++) {
                double t = j / (double) k, t2 = t * t, t3 = t2 * t;
                double x = 0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
                        + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
                double y = 0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
                        + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
                out.add(new Pt(x, y));
            }
        }
        out.add(c.get(n - 1).copy());
        return out;
    }

    /** Linea spezzata con lunghezze cumulate, per leggere un punto a una certa distanza. */
    static final class Poly {
        final double[] x, y, cum;
        final double len;

        Poly(List<Pt> p) {
            int n = p.size();
            x = new double[n];
            y = new double[n];
            cum = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = p.get(i).x;
                y[i] = p.get(i).y;
                if (i > 0) {
                    cum[i] = cum[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
                }
            }
            len = n == 0 ? 0 : cum[n - 1];
        }

        Pt at(double d) {
            int n = x.length;
            if (n == 0) {
                return new Pt(0, 0);
            }
            if (d <= 0 || n == 1) {
                return new Pt(x[0], y[0]);
            }
            if (d >= len) {
                return new Pt(x[n - 1], y[n - 1]);
            }
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int m = (lo + hi) >>> 1;
                if (cum[m] <= d) {
                    lo = m;
                } else {
                    hi = m;
                }
            }
            double seg = cum[hi] - cum[lo];
            double t = seg < 1e-9 ? 0 : (d - cum[lo]) / seg;
            return new Pt(x[lo] + (x[hi] - x[lo]) * t, y[lo] + (y[hi] - y[lo]) * t);
        }
    }

    // ------------------------------------------------------------------------------ simulazione

    static final class StepSim {
        Pt[] startPos = new Pt[NP], endPos = new Pt[NP];
        Pt ballStart, ballEnd;
        int holderStart, holderEnd, recv = -1;
        Poly[] poly = new Poly[NP + 1];       // percorso effettivo di ogni entita' (null = ferma)
        ArrayList<Pt>[] dense = newDense();   // stesso percorso come punti (per disegnarlo)
        int[] type = new int[NP + 1];
        double[] dur = new double[NP + 1];
        double D;                             // durata dei movimenti
        double t0;                            // istante di inizio della fase nell'animazione
        boolean empty;

        @SuppressWarnings("unchecked")
        private static ArrayList<Pt>[] newDense() {
            return (ArrayList<Pt>[]) new ArrayList[NP + 1];
        }

        double span() {
            return empty ? 0 : D + PAUSE;
        }
    }

    static final class Sim {
        final ArrayList<StepSim> steps = new ArrayList<>();
        double total;
        BasketPlay play;
    }

    static double speed(int type) {
        switch (type) {
            case DRIBBLE:
                return 3.8;
            case PASS:
                return 13;
            case SHOT:
                return 10;
            default:
                return 4.8;
        }
    }

    Pt hoopFor(Pt from) {
        if (full && from.x > COURT_L / 2) {
            return new Pt(COURT_L - HOOP_X, HOOP_Y);
        }
        return new Pt(HOOP_X, HOOP_Y);
    }

    Sim simulate() {
        Sim sm = new Sim();
        sm.play = this;
        Pt[] pos = new Pt[NP];
        for (int i = 0; i < NP; i++) {
            pos[i] = start[i].copy();
        }
        Pt bpos = ball.copy();
        int hold = holder;
        double t = 0;
        for (Step st : steps) {
            StepSim ss = new StepSim();
            for (int i = 0; i < NP; i++) {
                ss.startPos[i] = pos[i].copy();
            }
            ss.holderStart = hold;
            ss.ballStart = hold >= 0 ? pos[hold].copy() : bpos.copy();
            double dmax = 0;
            for (int i = 0; i < NP; i++) {
                Seg s = st.seg[i];
                ss.endPos[i] = pos[i].copy();
                if (s == null || s.pts.size() < 2) {
                    continue;
                }
                ArrayList<Pt> c = anchored(s.pts, pos[i]);
                ArrayList<Pt> d = smooth(c);
                Poly pl = new Poly(d);
                ss.poly[i] = pl;
                ss.dense[i] = d;
                ss.type[i] = s.type;
                ss.dur[i] = Math.max(0.3, pl.len / speed(s.type));
                ss.endPos[i] = pl.at(pl.len);
                dmax = Math.max(dmax, ss.dur[i]);
            }
            // palla
            Seg bs = st.seg[BALL];
            ss.ballEnd = ss.ballStart.copy();
            ss.holderEnd = hold;
            ss.recv = -1;
            if (bs != null && bs.pts.size() >= 2) {
                Pt a = ss.ballStart;
                ArrayList<Pt> c = new ArrayList<>();
                if (bs.type == SHOT) {
                    Pt h = hoopFor(a);
                    c.add(a.copy());
                    c.add(new Pt((a.x + h.x) / 2, (a.y + h.y) / 2));
                    c.add(h);
                } else {
                    c.addAll(anchored(bs.pts, a));
                    if (bs.target >= 0 && bs.target < NP && bs.target != hold) {
                        c.set(c.size() - 1, ss.endPos[bs.target].copy());
                        ss.recv = bs.target;
                    }
                }
                ArrayList<Pt> d = smooth(c);
                Poly pl = new Poly(d);
                ss.poly[BALL] = pl;
                ss.dense[BALL] = d;
                ss.type[BALL] = bs.type;
                ss.dur[BALL] = Math.max(0.35, pl.len / speed(bs.type));
                dmax = Math.max(dmax, ss.dur[BALL]);
                if (ss.recv >= 0) {
                    ss.holderEnd = ss.recv;
                    ss.ballEnd = ss.endPos[ss.recv].copy();
                } else {
                    ss.holderEnd = -1;
                    ss.ballEnd = pl.at(pl.len);
                }
            } else if (hold >= 0) {
                ss.ballEnd = ss.endPos[hold].copy();
            }
            ss.D = dmax;
            ss.empty = dmax <= 0;
            ss.t0 = t;
            t += ss.span();
            sm.steps.add(ss);
            for (int i = 0; i < NP; i++) {
                pos[i] = ss.endPos[i].copy();
            }
            hold = ss.holderEnd;
            bpos = ss.ballEnd.copy();
        }
        sm.total = t;
        return sm;
    }

    /** Fotogramma dell'animazione. */
    static final class Frame {
        Pt[] p = new Pt[NP];
        Pt ball;
        double carry;       // 0 palla libera, 1 in mano a un giocatore
        int holder = -1;    // giocatore che ha la palla (per evidenziarlo)
        boolean dribble;    // palla che rimbalza
        double phase;       // tempo per il rimbalzo
        int step;           // fase corrente
    }

    private static double ease(double u) {
        u = Math.max(0, Math.min(1, u));
        return 0.45 * u + 0.55 * (u * u * (3 - 2 * u));
    }

    static Frame frame(Sim sm, int stepIdx, double tl) {
        StepSim ss = sm.steps.get(stepIdx);
        Frame f = new Frame();
        f.step = stepIdx;
        f.phase = tl;
        double[] u = new double[NP + 1];
        for (int i = 0; i <= NP; i++) {
            u[i] = ss.poly[i] == null ? 1 : Math.min(1, tl / ss.dur[i]);
        }
        for (int i = 0; i < NP; i++) {
            f.p[i] = ss.poly[i] == null ? ss.startPos[i].copy() : ss.poly[i].at(ease(u[i]) * ss.poly[i].len);
        }
        Poly bp = ss.poly[BALL];
        if (bp != null) {
            double ub = ease(u[BALL]);
            Pt base = bp.at(ub * bp.len);
            Pt e = bp.at(bp.len);
            if (ss.recv >= 0) {
                // la palla "insegue" il ricevitore: la curva disegnata si sposta man mano che lui si muove
                Pt r = f.p[ss.recv];
                base.x += ub * (r.x - e.x);
                base.y += ub * (r.y - e.y);
                f.carry = Math.max(ss.holderStart >= 0 ? 1 - Math.min(1, 4 * ub) : 0, Math.max(0, Math.min(1, (ub - 0.7) / 0.3)));
                f.holder = u[BALL] >= 1 ? ss.recv : -1;
            } else {
                f.carry = ss.holderStart >= 0 ? 1 - Math.min(1, 4 * ub) : 0;
                f.holder = -1;
            }
            f.ball = base;
        } else if (ss.holderStart >= 0) {
            f.ball = f.p[ss.holderStart].copy();
            f.carry = 1;
            f.holder = ss.holderStart;
            f.dribble = ss.poly[ss.holderStart] != null && ss.type[ss.holderStart] == DRIBBLE && u[ss.holderStart] < 1;
        } else {
            f.ball = ss.ballStart.copy();
            f.carry = 0;
        }
        return f;
    }

    /** Fotogramma all'istante t dell'animazione (0..total). */
    static Frame frameAt(Sim sm, double t) {
        int idx = sm.steps.size() - 1;
        for (int i = 0; i < sm.steps.size(); i++) {
            StepSim q = sm.steps.get(i);
            if (t < q.t0 + q.span()) {
                idx = i;
                break;
            }
        }
        StepSim ss = sm.steps.get(idx);
        double tl = Math.min(t - ss.t0, ss.D);
        return frame(sm, idx, Math.max(0, tl));
    }

    /** Stato all'inizio di una fase (per la modifica). */
    static Frame startOf(Sim sm, int stepIdx) {
        return frame(sm, stepIdx, 0);
    }

    // ------------------------------------------------------------------------------ modifiche

    /**
     * I percorsi sono "relativi": la loro forma resta uguale e parte sempre da dove si trova davvero l'entita'
     * all'inizio della fase. Cosi' se si cambia una fase precedente quelle successive la seguono.
     */
    static ArrayList<Pt> anchored(List<Pt> pts, Pt at) {
        ArrayList<Pt> c = new ArrayList<>();
        double dx = at.x - pts.get(0).x, dy = at.y - pts.get(0).y;
        for (Pt p : pts) {
            c.add(new Pt(p.x + dx, p.y + dy));
        }
        return c;
    }

    /** Sposta un giocatore alla posizione di partenza. */
    void moveStart(int i, double nx, double ny) {
        start[i].x = nx;
        start[i].y = ny;
        if (holder == i) {
            ball = start[i].copy();
        }
    }

    // ------------------------------------------------------------------------------ salvataggio

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("full", full);
        JSONArray st = new JSONArray();
        for (Pt p : start) {
            st.put(new JSONArray().put(round(p.x)).put(round(p.y)));
        }
        o.put("start", st);
        o.put("ball", new JSONArray().put(round(ball.x)).put(round(ball.y)));
        o.put("holder", holder);
        JSONArray ss = new JSONArray();
        for (Step s : steps) {
            JSONArray sg = new JSONArray();
            for (Seg g : s.seg) {
                if (g == null) {
                    sg.put(JSONObject.NULL);
                    continue;
                }
                JSONObject go = new JSONObject();
                go.put("t", g.type);
                go.put("tg", g.target);
                JSONArray pa = new JSONArray();
                for (Pt p : g.pts) {
                    pa.put(new JSONArray().put(round(p.x)).put(round(p.y)));
                }
                go.put("p", pa);
                sg.put(go);
            }
            ss.put(sg);
        }
        o.put("steps", ss);
        int n = 0;
        for (Step s : steps) {
            n += s.isEmpty() ? 0 : 1;
        }
        o.put("summary", (full ? "Campo intero" : "Metà campo") + " · " + steps.size()
                + (steps.size() == 1 ? " fase" : " fasi"));
        o.put("title", name);
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    static BasketPlay fromJson(JSONObject o) throws Exception {
        BasketPlay p = new BasketPlay();
        p.steps.clear();
        p.name = o.optString("name", o.optString("title", "Schema"));
        p.full = o.optBoolean("full", false);
        JSONArray st = o.getJSONArray("start");
        for (int i = 0; i < NP; i++) {
            JSONArray a = st.getJSONArray(i);
            p.start[i] = new Pt(a.getDouble(0), a.getDouble(1));
        }
        JSONArray b = o.getJSONArray("ball");
        p.ball = new Pt(b.getDouble(0), b.getDouble(1));
        p.holder = o.optInt("holder", -1);
        JSONArray ss = o.getJSONArray("steps");
        for (int k = 0; k < ss.length(); k++) {
            JSONArray sg = ss.getJSONArray(k);
            Step s = new Step();
            for (int i = 0; i < NP + 1 && i < sg.length(); i++) {
                if (sg.isNull(i)) {
                    continue;
                }
                JSONObject go = sg.getJSONObject(i);
                Seg g = new Seg();
                g.type = go.getInt("t");
                g.target = go.optInt("tg", -1);
                JSONArray pa = go.getJSONArray("p");
                for (int j = 0; j < pa.length(); j++) {
                    JSONArray q = pa.getJSONArray(j);
                    g.pts.add(new Pt(q.getDouble(0), q.getDouble(1)));
                }
                s.seg[i] = g;
            }
            p.steps.add(s);
        }
        if (p.steps.isEmpty()) {
            p.steps.add(new Step());
        }
        return p;
    }

    BasketPlay copyDeep() {
        try {
            return fromJson(toJson());
        } catch (Exception e) {
            return this;
        }
    }
}
