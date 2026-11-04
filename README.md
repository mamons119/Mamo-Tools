# Mamo Tools

Raccolta di strumenti per Android (minSdk 29, pensata per Android 11).

## Strumenti

- **Volume Boost**: amplificazione fino a +100 dB, equalizzatore con preset, bilanciamento
  sinistra/destra (effetto audio, funziona anche con le cuffie Bluetooth).
- **Stable Cam**: registra video con la stabilizzazione del telefono (elettronica + ottica)
  alla qualita' massima supportata dalla camera. Il video finisce in Galleria
  (cartella Movies/MamoTools) con nome `MamoRecord_AAAAMMGG_OOMMSS.mp4`.
  - Comandi in basso (in orizzontale: colonna a destra): cambia camera, registra con timer
    sotto/sopra il pulsante, impostazioni. In alto: qualita', stabilizzazione, torcia.
  - Gesti: pizzica per lo zoom (cambia da sola la lente giusta quando non stai registrando), tocca per mettere a fuoco ed esporre.
  - Lenti: sopra i comandi compaiono i pulsanti x0.5, x1, x3... (uno per lente rilevata, con
    il fattore rispetto alla camera principale). Si usano insieme allo zoom digitale. Il tasto
    di rotazione passa tra posteriore e frontale.
  - Impostazioni: qualita'/fps, stabilizzazione, audio on/off, zoom, esposizione (EV),
    bilanciamento del bianco, griglia dei terzi, timer di avvio (3/5/10 s).

- **PDF Tools**: menu con tutte le operazioni sui PDF; i file nuovi vanno in Download/MamoTools.
  - Unisci (ordine a scelta con frecce), Dividi (una pagina per file o a gruppi di N), Estrai
    pagine, Elimina pagine, Ruota pagine (90/180/270). Le pagine si indicano come `1-3, 5, 8-`.
    Usa PdfBox-Android; i PDF protetti da password non sono supportati.
  - Immagini -> PDF: foto in ordine, pagina A4 (con margini) o grande come l'immagine
    (lato massimo 1600 px per pagina). PDF -> Immagini: JPG o PNG, 72/150/300 dpi, in
    Immagini/MamoTools.
  - PDF <-> Word: sceglie un file con il selettore di sistema e lo converte nell'altro formato.
  - PDF -> Word (.docx): estrae il testo (libreria PdfBox-Android) e lo rimette in paragrafi
    con titoli e grassetto. Non mantiene immagini, tabelle e impaginazione; i PDF
    scansionati (solo immagini) non funzionano perche' serve l'OCR.
  - Word (.docx) -> PDF: lettore e rendering scritti a mano (nessuna libreria): testo
    formattato, elenchi, tabelle semplici, immagini, dimensioni pagina. Intestazioni, pie'
    di pagina, caselle di testo e forme non vengono disegnati. Il formato .doc vecchio non
    e' supportato.

- **Image Converter**: sceglie un'immagine (JPG, PNG, WEBP, GIF, BMP, ICO, HEIC/HEIF, DNG...)
  e la salva come JPG, PNG, WEBP, BMP o ICO in Immagini/MamoTools, con qualita' regolabile per
  JPG e WEBP. Supporta anche ICO in uscita, con dimensione in pixel a scelta (1-256). Non e' possibile scrivere GIF, TIFF o HEIC: Android non li sa codificare.
- **Video Editor** (con effetti e testi inseribili in qualsiasi intervallo della timeline, font/dimensione/colore a scelta, e stabilizzazione opzionale: analisi del movimento, 4 intensita', zoom massimo,
  correzione dell'inclinazione; ricodifica il video): sceglie un video dalla Galleria, anteprima con timeline a miniature,
  taglio di parti (segna Inizio/Fine e "Taglia via"), audio originale on/off, colonna sonora
  dal telefono con punto di inizio a scelta, in sostituzione oppure in aggiunta all'originale
  abbassato di 3-30 dB. Salva in Movies/MamoTools (`MamoEdit_*.mp4`). I tagli non ricodificano
  il video: qualita' invariata, ma il punto di taglio puo' slittare al fotogramma chiave
  precedente.

- **Audio Editor**: apre un audio (o l'audio di un video), forma d'onda con zoom a pizzico,
  selezione con Inizio/Fine, taglia/copia/incolla/elimina/mantieni, annulla (30 passi), inserisci
  un altro audio al cursore. Filtri passa-alto, passa-basso e passa-banda (12 o 24 dB/ottava,
  taglio 20 Hz-20 kHz) e volume da -20 a +20 dB, udibili in ascolto e applicati all'esportazione
  (M4A AAC o WAV) in Musica/MamoTools. Internamente 44,1 kHz stereo.

- **Strumenti di Misura**: menu di piccoli strumenti.
  - Calcolo miscela: litri di benzina + percentuale (anche 1,5%, 2,5%...) = ml di olio, rapporto 1:N e
    totale della miscela.
  - Righello: schermo intero a grandezza reale (cm/mm su un bordo, pollici 1/16 sull'altro), riga di
    misura mobile; calibrazione con la sagoma di una carta di credito (le dimensioni dello schermo
    dichiarate dal telefono non sono sempre esatte).
  - Livello: bolla circolare con il telefono appoggiato, a tubo con il telefono in piedi; usa il
    sensore di gravita'/accelerometro (il giroscopio non da' l'inclinazione assoluta); "Azzera qui".
- **Telemetria e prestazioni** (dentro Strumenti di Misura; usa GPS, accelerometro e giroscopio, con
  scheda di stato dei sensori e pulsanti per permesso/GPS):
  - Prestazioni: da fermo (0-50/100/150/200, 100-200, 0-60 mph, 1/8, 1/4 di miglio con velocita' di
    uscita, 1000 m), ripresa da-a, frenata da-0 con spazio; migliori salvati. Tempi interpolati tra i fix GPS.
  - Piega e G-meter: piega = atan(v * imbardata / g) con GPS + giroscopio (indipendente dal montaggio),
    senza GPS inclinazione apparente; diagramma G-G, massimi.
  - Registratore: sessioni a 10 righe/s (CSV in memoria dell'app), traguardo con conteggio giri e tempi.
  - Sessioni: mappa a tessere OpenStreetMap / OpenTopoMap (fuoristrada) / Esri satellite con percorso
    colorato per velocita' (serve internet per le tessere, poi restano in cache), grafici con cursore
    collegato alla mappa, G-G, statistiche, GPX e CSV, link Google Maps approssimato.
- **System Monitor**: grafici in tempo reale (1 s) di CPU con una linea colorata per core, RAM/swap
  e traffico di rete (download/upload), piu' schede con memoria, archiviazione e microSD,
  batteria (icona grande), termometri CPU/RAM/SoC/batteria, GPU, schermo (con disegno del telefono), dispositivo, funzioni e sensori. Android blocca alcune
  letture alle app: se `/proc/stat` non e' leggibile il carico per core e' stimato dalla frequenza,
  e temperature/GPU compaiono solo se il telefono le espone.

- **PDF Reader** (dentro PDF Tools, sezione "Reader"; resta selezionabile come "Mamo Tools (PDF)" per aprire i PDF): scorrimento continuo, zoom a pizzico e doppio tap, vai a pagina, ricerca di testo
  con evidenziazione in giallo, modalita' notte, condividi, stampa, salva una copia in Download, informazioni.
  Compare anche in "Apri con" per i file PDF come "Mamo Tools (PDF)". Non modifica i PDF.

## Aggiungere un nuovo strumento

1. Crea una nuova Activity in `app/src/main/java/it/mamotools/`.
2. Registrala in `AndroidManifest.xml`.
3. Aggiungi una riga `new Tool(...)` nell'elenco `TOOLS` di `MainActivity.java`.

## Compilare

GitHub Actions: carica la cartella su un repository, poi Actions > Build APK > scarica
l'artefatto `MamoTools-apk`. In alternativa Android Studio (Build > Build APK) oppure
`gradle assembleRelease` con JDK 17 e Android SDK.

## Note su Stable Cam

- Per la qualita' massima l'app elenca i profili video che la camera supporta (8K, 4K, 2K,
  1080p...) e parte dal piu' alto a 30 fps; dove disponibile compare anche la voce a 60 fps.
- Se il telefono non riesce a registrare con la combinazione scelta, l'app scende da sola
  al profilo successivo e lo segnala.
- La stabilizzazione usa gli algoritmi del telefono: puo' ritagliare leggermente
  l'inquadratura. Alcune camere (ad esempio ultra grandangolo o frontale) non la offrono:
  lo stato in alto lo indica.
- I video registrati da piu' di un secondo si salvano automaticamente; quelli troppo brevi
  vengono scartati.
