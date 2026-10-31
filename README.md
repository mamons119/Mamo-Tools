# Mamo Tools

Raccolta di strumenti per Android (minSdk 29, pensata per Android 11).

## Strumenti

- **Volume Boost**: amplificazione fino a +100 dB, equalizzatore con preset, bilanciamento
  sinistra/destra (effetto audio, funziona anche con le cuffie Bluetooth).
- **Stable Cam**: registra video con la stabilizzazione del telefono (elettronica + ottica)
  alla qualita' massima supportata dalla camera. Il video finisce in Galleria
  (cartella Movies/MamoTools) con nome `MamoRecord_AAAAMMGG_OOMMSS.mp4`.

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
