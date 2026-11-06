package it.mamotools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dati di un brano di "Produzioni": tracce, pattern, arrangiamento. Java puro (testabile senza Android). */
final class ProdModel {

    static final int TRACKS = 8, PATTERNS = 8, MAX_STEPS = 32, OFF = -1000;

    /** id, etichetta, melodico?, nota MIDI di partenza nella tastiera, colore del volume (guadagno) */
    static final String[] SOUNDS = {"kick", "snare", "hatc", "hato", "clap", "tom", "rim",
            "bass", "keys", "pad", "lead", "pluck", "bell", "fx", "sample"};
    static final String[] LABELS = {"Cassa", "Rullante", "Hi-hat chiuso", "Hi-hat aperto", "Battito", "Tom", "Rim",
            "Basso", "Piano elettrico", "Pad", "Lead", "Pizzico", "Campana", "Soffio", "Sample"};
    static final boolean[] MELODIC = {false, false, false, false, false, false, false,
            true, true, true, true, true, true, true, true};
    static final int[] BASE_NOTE = {60, 60, 60, 60, 60, 60, 60, 36, 60, 60, 72, 60, 72, 60, 60};
    static final String[] GROUPS = {"Batteria", "Strumenti", "Altro"};

    static final String[] SCALE_NAMES = {"Maggiore", "Minore", "Pentatonica minore", "Pentatonica maggiore",
            "Dorica", "Cromatica"};
    static final int[][] SCALES = {{0, 2, 4, 5, 7, 9, 11}, {0, 2, 3, 5, 7, 8, 10}, {0, 3, 5, 7, 10},
            {0, 2, 4, 7, 9}, {0, 2, 3, 5, 7, 9, 10}, {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}};
    static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    static final int[] TRACK_COLORS = {0xFFFF6B6B, 0xFFFFA94D, 0xFFFFD43B, 0xFF69DB7C,
            0xFF4DD0E1, 0xFF74A8FF, 0xFFB197FC, 0xFFF783AC};

    static int soundIndex(String id) {
        for (int i = 0; i < SOUNDS.length; i++) {
            if (SOUNDS[i].equals(id)) {
                return i;
            }
        }
        return 0;
    }

    static String noteName(int midi) {
        return NOTE_NAMES[((midi % 12) + 12) % 12] + (midi / 12 - 1);
    }

    static final class Track {
        String sound = "kick";
        int tone = 0;          // semitoni -12..+12
        int decay = 50;        // 0..100 (50 = normale)
        int cutoff = 100;      // 0..100 (100 = filtro aperto)
        int vol = 80;          // 0..100
        int pan = 0;           // -100..100
        boolean mute, solo;
        String sample = "";    // id del file del sample
        String sampleName = "";
        int smpStart = 0, smpEnd = 1000; // 0..1000
        int lastNote = -1;     // ultima nota scelta (solo interfaccia)

        boolean melodic() {
            return MELODIC[soundIndex(sound)];
        }

        String label() {
            if (sound.equals("sample") && !sampleName.isEmpty()) {
                return sampleName;
            }
            return LABELS[soundIndex(sound)];
        }

        Track copy() {
            Track t = new Track();
            t.sound = sound;
            t.tone = tone;
            t.decay = decay;
            t.cutoff = cutoff;
            t.vol = vol;
            t.pan = pan;
            t.mute = mute;
            t.solo = solo;
            t.sample = sample;
            t.sampleName = sampleName;
            t.smpStart = smpStart;
            t.smpEnd = smpEnd;
            t.lastNote = lastNote;
            return t;
        }
    }

    static final class Pattern {
        int steps = 16;
        final int[][] note = new int[TRACKS][MAX_STEPS];

        Pattern() {
            for (int[] r : note) {
                Arrays.fill(r, OFF);
            }
        }

        boolean isEmpty() {
            for (int t = 0; t < TRACKS; t++) {
                for (int s = 0; s < MAX_STEPS; s++) {
                    if (note[t][s] != OFF) {
                        return false;
                    }
                }
            }
            return true;
        }

        void clear() {
            for (int[] r : note) {
                Arrays.fill(r, OFF);
            }
        }

        void copyFrom(Pattern o) {
            steps = o.steps;
            for (int t = 0; t < TRACKS; t++) {
                System.arraycopy(o.note[t], 0, note[t], 0, MAX_STEPS);
            }
        }
    }

    static final class Song {
        String title = "Nuovo brano";
        int bpm = 90, swing = 0, master = 80, echo = 0, reverb = 0, lofi = 0;
        int root = 0, scale = 0;
        final Track[] tracks = new Track[TRACKS];
        final Pattern[] patterns = new Pattern[PATTERNS];
        /** blocchi del brano: {pattern, ripetizioni} */
        final List<int[]> arr = new ArrayList<>();

        Song() {
            for (int i = 0; i < PATTERNS; i++) {
                patterns[i] = new Pattern();
            }
            for (int i = 0; i < TRACKS; i++) {
                tracks[i] = new Track();
            }
        }

        /** Un kit di partenza: ritmica, basso e un po' di armonia. Tutti i pattern sono vuoti. */
        static Song fresh() {
            Song s = new Song();
            String[] kit = {"kick", "snare", "hatc", "hato", "bass", "keys", "pad", "pluck"};
            int[] vol = {90, 80, 55, 55, 80, 70, 55, 65};
            for (int i = 0; i < TRACKS; i++) {
                s.tracks[i].sound = kit[i];
                s.tracks[i].vol = vol[i];
                if (kit[i].equals("pad")) {
                    s.tracks[i].cutoff = 62;
                }
            }
            return s;
        }

        int songSteps() {
            int n = 0;
            for (int[] a : arr) {
                n += patterns[a[0]].steps * a[1];
            }
            return n;
        }

        Song copy() {
            return parse(serialize());
        }

        // ---- salvataggio come testo semplice ----

        private static String clean(String x) {
            return x.replace('|', ' ').replace('\n', ' ').replace('\r', ' ');
        }

        String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append("MAMOPROD1\n");
            sb.append("title|").append(clean(title)).append('\n');
            sb.append("mix|").append(bpm).append('|').append(swing).append('|').append(master).append('|')
                    .append(echo).append('|').append(reverb).append('|').append(lofi).append('|').append(root)
                    .append('|').append(scale).append('\n');
            for (int i = 0; i < TRACKS; i++) {
                Track t = tracks[i];
                sb.append("track|").append(i).append('|').append(t.sound).append('|').append(t.tone).append('|')
                        .append(t.decay).append('|').append(t.cutoff).append('|').append(t.vol).append('|')
                        .append(t.pan).append('|').append(t.mute ? 1 : 0).append('|').append(t.solo ? 1 : 0)
                        .append('|').append(clean(t.sample)).append('|').append(clean(t.sampleName)).append('|')
                        .append(t.smpStart).append('|').append(t.smpEnd).append('|').append(t.lastNote)
                        .append('\n');
            }
            for (int p = 0; p < PATTERNS; p++) {
                Pattern pt = patterns[p];
                sb.append("pat|").append(p).append('|').append(pt.steps).append('\n');
                for (int t = 0; t < TRACKS; t++) {
                    for (int s = 0; s < MAX_STEPS; s++) {
                        if (pt.note[t][s] != OFF) {
                            sb.append("step|").append(p).append('|').append(t).append('|').append(s).append('|')
                                    .append(pt.note[t][s]).append('\n');
                        }
                    }
                }
            }
            for (int[] a : arr) {
                sb.append("arr|").append(a[0]).append('|').append(a[1]).append('\n');
            }
            return sb.toString();
        }

        static Song parse(String text) {
            Song s = new Song();
            String[] lines = text.split("\n");
            if (lines.length == 0 || !lines[0].trim().equals("MAMOPROD1")) {
                throw new IllegalArgumentException("non è un brano di Produzioni");
            }
            for (String ln : lines) {
                String[] f = ln.split("\\|", -1);
                try {
                    switch (f[0]) {
                        case "title":
                            s.title = f.length > 1 ? f[1] : s.title;
                            break;
                        case "mix":
                            s.bpm = clamp(num(f, 1), 40, 220);
                            s.swing = clamp(num(f, 2), 0, 100);
                            s.master = clamp(num(f, 3), 0, 100);
                            s.echo = clamp(num(f, 4), 0, 100);
                            s.reverb = clamp(num(f, 5), 0, 100);
                            s.lofi = clamp(num(f, 6), 0, 100);
                            s.root = clamp(num(f, 7), 0, 11);
                            s.scale = clamp(num(f, 8), 0, SCALES.length - 1);
                            break;
                        case "track": {
                            int i = clamp(num(f, 1), 0, TRACKS - 1);
                            Track t = s.tracks[i];
                            t.sound = SOUNDS[soundIndex(f[2])];
                            t.tone = clamp(num(f, 3), -12, 12);
                            t.decay = clamp(num(f, 4), 0, 100);
                            t.cutoff = clamp(num(f, 5), 0, 100);
                            t.vol = clamp(num(f, 6), 0, 100);
                            t.pan = clamp(num(f, 7), -100, 100);
                            t.mute = num(f, 8) == 1;
                            t.solo = num(f, 9) == 1;
                            t.sample = f[10];
                            t.sampleName = f[11];
                            t.smpStart = clamp(num(f, 12), 0, 999);
                            t.smpEnd = clamp(num(f, 13), 1, 1000);
                            t.lastNote = num(f, 14);
                            break;
                        }
                        case "pat": {
                            int p = clamp(num(f, 1), 0, PATTERNS - 1);
                            s.patterns[p].steps = num(f, 2) >= 32 ? 32 : 16;
                            break;
                        }
                        case "step": {
                            int p = clamp(num(f, 1), 0, PATTERNS - 1);
                            s.patterns[p].note[clamp(num(f, 2), 0, TRACKS - 1)][clamp(num(f, 3), 0, MAX_STEPS - 1)]
                                    = num(f, 4);
                            break;
                        }
                        case "arr":
                            s.arr.add(new int[]{clamp(num(f, 1), 0, PATTERNS - 1), clamp(num(f, 2), 1, 64)});
                            break;
                        default:
                            break;
                    }
                } catch (RuntimeException e) {
                    // riga non valida: ignorata
                }
            }
            return s;
        }

        private static int num(String[] f, int i) {
            return Integer.parseInt(f[i].trim());
        }
    }

    static int clamp(int v, int a, int b) {
        return Math.max(a, Math.min(b, v));
    }
}
