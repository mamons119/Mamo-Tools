package it.mamotools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Stato condiviso dell'editor di Produzioni: brano, motore audio, player e salvataggio automatico. */
final class ProdSession {

    interface Tab {
        android.view.View view();

        /** la scheda e' stata aperta o i dati sono cambiati altrove */
        void onShow();

        /** posizione di riproduzione {passo, pattern, passo nel pattern} o null se fermo */
        void tick(int[] pos);
    }

    final Context ctx;
    final String id;
    final ProdModel.Song song;
    final ProdStore store;
    final ProdEngine engine;
    final ProdPlayer player;

    int curTrack = 0, curPattern = 0, mode = ProdEngine.MODE_PATTERN;
    boolean loop = true;
    boolean songDirty = true;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            saveNow();
        }
    };

    ProdSession(Context ctx, String id, ProdModel.Song song, ProdStore store) {
        this.ctx = ctx;
        this.id = id;
        this.song = song;
        this.store = store;
        this.engine = new ProdEngine(song, store);
        this.player = new ProdPlayer(engine);
    }

    /** da chiamare a ogni modifica: salva dopo un attimo */
    void changed() {
        songDirty = true;
        h.removeCallbacks(saver);
        h.postDelayed(saver, 900);
    }

    void saveNow() {
        h.removeCallbacks(saver);
        try {
            store.save(id, song);
        } catch (Exception ignored) {
        }
    }

    void selectPattern(int p) {
        curPattern = p;
        engine.curPattern = p;
    }

    /** prima nota nella scala a partire dalla nota di base dello strumento */
    int defaultNote(ProdModel.Track t) {
        int base = ProdModel.BASE_NOTE[ProdModel.soundIndex(t.sound)];
        for (int m = base; m < base + 12; m++) {
            if (inScale(m)) {
                return m;
            }
        }
        return base;
    }

    boolean inScale(int midi) {
        int d = ((midi - song.root) % 12 + 12) % 12;
        for (int x : ProdModel.SCALES[song.scale]) {
            if (x == d) {
                return true;
            }
        }
        return false;
    }

    int curNote(ProdModel.Track t) {
        return t.lastNote >= 0 ? t.lastNote : defaultNote(t);
    }

    boolean playing() {
        return engine.playing;
    }
}
