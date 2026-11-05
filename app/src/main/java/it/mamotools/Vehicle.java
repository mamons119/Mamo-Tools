package it.mamotools;

/** Mezzi selezionabili nel registratore. */
final class Vehicle {
    static final String[] IDS = {"car", "moto", "bike", "boat", "canoe"};
    static final String[] LABELS = {"Macchina", "Moto", "Bicicletta", "Barca", "Canoa"};

    private Vehicle() {
    }

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    static String label(String id) {
        int i = index(id);
        return i < 0 ? "Non indicato" : LABELS[i];
    }
}
