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

- **PDF <-> Word**: sceglie un file con il selettore di sistema e lo converte nell'altro
  formato, salvandolo in Download.
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
- **Video Editor**: sceglie un video dalla Galleria, anteprima con timeline a miniature,
  taglio di parti (segna Inizio/Fine e "Taglia via"), audio originale on/off, colonna sonora
  dal telefono con punto di inizio a scelta, in sostituzione oppure in aggiunta all'originale
  abbassato di 3-30 dB. Salva in Movies/MamoTools (`MamoEdit_*.mp4`). I tagli non ricodificano
  il video: qualita' invariata, ma il punto di taglio puo' slittare al fotogramma chiave
  precedente.

- **System Monitor**: grafici in tempo reale (1 s) di CPU con una linea colorata per core, RAM/swap
  e traffico di rete (download/upload), piu' schede con memoria, archiviazione e microSD,
  batteria (icona grande), termometri CPU/RAM/SoC/batteria, GPU, schermo (con disegno del telefono), dispositivo, funzioni e sensori. Android blocca alcune
  letture alle app: se `/proc/stat` non e' leggibile il carico per core e' stimato dalla frequenza,
  e temperature/GPU compaiono solo se il telefono le espone.

- **PDF Reader**: scorrimento continuo, zoom a pizzico e doppio tap, vai a pagina, ricerca di testo
  (per pagina), modalita' notte, condividi, stampa, salva una copia in Download, informazioni.
  Compare anche in "Apri con" per i file PDF come "Mamo Tools (PDF)". Non modifica i PDF.

## Widget

- **Mamo Tools · System Monitor**: pila di grafici (CPU per core, RAM, rete) da scorrere con
  uno swipe verticale; aggiornato ogni secondo a schermo acceso da un servizio con notifica
  silenziosa. Il tocco apre il System Monitor.
- **Mamo Tools · Volume Boost**: tasto ON/OFF e guadagno a passi di 1 e 5 dB; il tocco sul resto
  del widget apre Volume Boost.

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
