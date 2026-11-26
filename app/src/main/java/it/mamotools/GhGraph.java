package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Disposizione ad albero dei commit (come i grafici di git gui) e vista che disegna una riga del grafico. */
final class GhGraph {

    static final int[] COLORS = {0xFF4DD0E1, 0xFFFFB74D, 0xFF81C784, 0xFFBA68C8, 0xFFFF8A80, 0xFF64B5F6,
            0xFFFFF176, 0xFFA1887F};
    static final int MAX_LANES = 8;

    static final class Row {
        GhApi.Cm c;
        int lane;
        String[] before, after;
        List<Integer> extra = new ArrayList<>();   // corsie dei genitori gia' presenti
    }

    /** Ordina i commit dal piu' recente (i figli sempre prima dei genitori) e assegna le corsie. */
    static List<Row> layout(Map<String, GhApi.Cm> all) {
        // ordine topologico: un commit esce solo dopo tutti i suoi figli
        Map<String, Integer> kids = new HashMap<>();
        for (GhApi.Cm c : all.values()) {
            for (String p : c.parents) {
                if (all.containsKey(p)) {
                    Integer n = kids.get(p);
                    kids.put(p, n == null ? 1 : n + 1);
                }
            }
        }
        PriorityQueue<GhApi.Cm> q = new PriorityQueue<>((a, b) -> Long.compare(b.time, a.time));
        for (GhApi.Cm c : all.values()) {
            if (!kids.containsKey(c.sha)) {
                q.add(c);
            }
        }
        List<GhApi.Cm> order = new ArrayList<>();
        while (!q.isEmpty()) {
            GhApi.Cm c = q.poll();
            order.add(c);
            for (String p : c.parents) {
                GhApi.Cm pc = all.get(p);
                if (pc == null) {
                    continue;
                }
                int n = kids.get(p) - 1;
                kids.put(p, n);
                if (n == 0) {
                    q.add(pc);
                }
            }
        }
        // corsie
        List<Row> rows = new ArrayList<>();
        List<String> lanes = new ArrayList<>();
        for (GhApi.Cm c : order) {
            Row r = new Row();
            r.c = c;
            r.before = lanes.toArray(new String[0]);
            int lane = lanes.indexOf(c.sha);
            if (lane < 0) {
                lane = firstFree(lanes);
                if (lane == lanes.size()) {
                    lanes.add(null);
                }
            }
            r.lane = lane;
            // le altre corsie che aspettavano questo commit si chiudono qui
            for (int i = 0; i < lanes.size(); i++) {
                if (i != lane && c.sha.equals(lanes.get(i))) {
                    lanes.set(i, null);
                }
            }
            lanes.set(lane, null);
            boolean first = true;
            for (String p : c.parents) {
                int ex = lanes.indexOf(p);
                if (first) {
                    if (ex >= 0 && ex != lane) {
                        r.extra.add(ex);   // il primo genitore e' gia' seguito da un'altra corsia
                    } else {
                        lanes.set(lane, p);
                    }
                    first = false;
                } else if (ex >= 0) {
                    r.extra.add(ex);
                } else {
                    int f = firstFree(lanes);
                    if (f == lanes.size()) {
                        lanes.add(p);
                    } else {
                        lanes.set(f, p);
                    }
                }
            }
            while (!lanes.isEmpty() && lanes.get(lanes.size() - 1) == null) {
                lanes.remove(lanes.size() - 1);
            }
            r.after = lanes.toArray(new String[0]);
            rows.add(r);
        }
        return rows;
    }

    private static int firstFree(List<String> l) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) == null) {
                return i;
            }
        }
        return l.size();
    }

    static int maxLanes(List<Row> rows) {
        int m = 1;
        for (Row r : rows) {
            m = Math.max(m, Math.max(r.lane + 1, Math.max(r.before.length, r.after.length)));
        }
        return Math.min(m, MAX_LANES);
    }

    /** Una riga del grafico: linee delle corsie e pallino del commit. */
    static final class RowView extends View {
        private final Row r;
        private final int lanes;
        private final float step;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        RowView(Context c, Row r, int lanes) {
            super(c);
            this.r = r;
            this.lanes = lanes;
            step = Ui.dp(c, 16);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(c, 2));
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        static int widthFor(Context c, int lanes) {
            return Math.round(Ui.dp(c, 16) * lanes + Ui.dp(c, 8));
        }

        private float x(int lane) {
            return step * (Math.min(lane, MAX_LANES - 1) + 0.5f) + Ui.dp(getContext(), 2);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(widthFor(getContext(), lanes), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            float H = getHeight(), mid = H / 2f, nx = x(r.lane);
            int n = Math.max(r.before.length, r.after.length);
            for (int i = 0; i < n; i++) {
                String b = i < r.before.length ? r.before[i] : null;
                String a = i < r.after.length ? r.after[i] : null;
                p.setColor(COLORS[i % COLORS.length]);
                if (b != null && b.equals(r.c.sha)) {
                    c.drawLine(x(i), 0, nx, mid, p);               // arriva al commit
                } else if (b != null && b.equals(a)) {
                    c.drawLine(x(i), 0, x(i), H, p);               // passa oltre
                }
                if (a != null && (b == null || !b.equals(a)) && i < r.after.length) {
                    c.drawLine(nx, mid, x(i), H, p);               // parte dal commit verso il genitore
                }
            }
            for (int ex : r.extra) {
                p.setColor(COLORS[ex % COLORS.length]);
                c.drawLine(nx, mid, x(ex), H, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLORS[r.lane % COLORS.length]);
            c.drawCircle(nx, mid, Ui.dp(getContext(), 5), p);
            p.setColor(0xFF0F1115);
            c.drawCircle(nx, mid, Ui.dp(getContext(), 2), p);
            p.setStyle(Paint.Style.STROKE);
        }
    }
}
