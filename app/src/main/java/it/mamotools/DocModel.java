package it.mamotools;

import java.util.ArrayList;
import java.util.List;

/** Modello semplificato di un documento Word, letto da DocxParser e disegnato da DocxToPdf. */
final class DocModel {

    float pageW = 595.3f;   // punti (1/72 di pollice): A4
    float pageH = 841.9f;
    float marginL = 72f;
    float marginR = 72f;
    float marginT = 72f;
    float marginB = 72f;

    final List<Block> blocks = new ArrayList<>();
    final List<Block> header = new ArrayList<>();   // intestazione (uguale su ogni pagina)
    final List<Block> footer = new ArrayList<>();   // pie' di pagina
    final List<String> warnings = new ArrayList<>(); // elementi non disegnabili trovati
    boolean usesTotalPages;                          // il documento usa il campo "numero di pagine"


    abstract static class Block {
    }

    static final class Run {
        String text = "";
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strike;
        float size = 11f;
        int color = 0xFF000000;
        String font = "";
    }

    static final class Img {
        byte[] data;
        float w;   // punti; 0 = sconosciuta
        float h;
    }

    static final class Para extends Block {
        final List<Run> runs = new ArrayList<>();
        final List<Img> images = new ArrayList<>();
        int align;                 // 0 sinistra, 1 centro, 2 destra, 3 giustificato
        float before;
        float after;
        float lineMult = 1f;
        float indentLeft;
        float indentFirst;         // rispetto a indentLeft (negativo = sporgente)
        String listPrefix = "";
        boolean pageBreakBefore;
        boolean pageBreakAfter;
        float defaultSize = 11f;
    }

    static final class Cell {
        final List<Para> paras = new ArrayList<>();
        int span = 1;
    }

    static final class Table extends Block {
        final List<List<Cell>> rows = new ArrayList<>();
        float[] cols;
        boolean borders;
    }
}
