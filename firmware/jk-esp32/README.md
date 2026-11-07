# Firmware JK ESP32 — spécification v0.1

**État : non implémenté.** Ce dossier accueillera un firmware autonome, distinct du Bluetooth Proxy ESPHome utilisé avec Home Assistant.

### Fonctions obligatoires

- Appairage local protégé et configuration Wi-Fi depuis l'APK.
- Scan BLE JK, signal RSSI et sélection par l'utilisateur dans l'APK.
- Configuration des BMS sauvegardée en flash ; ajout, suppression et renommage sans reflash.
- Compatibilité JK V19 à confirmer sur les révisions réellement testées.
- Lecture seule des valeurs initialement, reconnexion après perte BLE et reprise après panne électrique.
- Interface locale authentifiée destinée à l'APK.
- OTA avec vérifications et conservation des paramètres.

Un JK puis plusieurs JK seront testés pour mesurer la charge radio, la mémoire et la stabilité. Le nombre maximum ne sera pas inventé.

**Ne jamais flasher une passerelle JK Home Assistant en service pour ce premier prototype.**
