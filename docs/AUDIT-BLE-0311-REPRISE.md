# Smart Energy JK ESP32 0.3.11-dev — audit déconnexions et reprise BLE

**Branche expérimentale** : `experiment/smart-energy-v0.3.11-ble-stall-recovery`. Base : code 0.3.10 (JK 0.3.8). **APK et ESP32 VPN/Tailscale inchangés**.

## Symptômes et constats du 9 novembre 2026

- Téléphone : lecteur JK 0.3.8-dev visible, deux JK mémorisés mais 0/2 en ligne.
- BMS 1 : 18 coupures radio, GAP 520, dernier RSSI -48 dBm, délai de supervision réellement lu 6000 ms.
- BMS 2 : 2 coupures radio, GAP 520, dernier RSSI -75 dBm, délai lu 6000 ms, dernier échec de connexion BLE 13.
- La passerelle Tailscale effectue uniquement des lectures HTTP GET du cache du lecteur JK. Elle ne demande ni scans, ni connexions Bluetooth, ni suppression/écriture BMS. L'ESP32 JK partage cependant sa radio 2,4 GHz entre Wi-Fi et BLE.
- GAP 520 = 0x208, *HCI connection supervision timeout*. BLE 13 = `BLE_HS_ETIMEOUT` (opération NimBLE expirée).
- L'intervalle/timeout demandés dans le firmware (`setConnectionParams(48,64,0,1200,...)`) ne sont pas une garantie : l'API rapporte ici 6000 ms réellement obtenus.
- Le firmware 0.3.8 retente effectivement les connexions (ex. 26 tentatives BMS 1) : la panne ne correspond donc pas à une simple absence de boucle de retry.
- **Défaut de code vérifié** : si `client->isConnected()` reste vrai mais que le BMS ne transmet plus de trame type 2 valide, `serviceSession()` ne fait que renvoyer `0x96` toutes les huit secondes et ne reconstruit jamais la session bloquée.

## Correctif expérimental limité, sans modification des protections

1. Un watchdog par BMS attend **60 s sans trame type 2 validée** depuis l'établissement de la session ou depuis le dernier échantillon. Le seuil ne provoque aucun effet tant que la session délivre des mesures récentes.
2. En cas de blocage, il ferme **cette session uniquement**, puis programme une reconnexion retardée de 16 s (avec léger décalage par slot). Aucun reset global NimBLE : les autres sessions saines sont préservées.
3. La télémétrie expose `ble_telemetry_stall_recoveries` et `ble_last_valid_frame_age_seconds` pour distinguer un blocage de notifications d'un échec radio.
4. Tests unitaires : absence de fermeture prématurée, récupération après 60 s, mesures récemment revenues, anciennes trames d'une session précédente, rollover `millis()`.
5. La cause des supervision-timeouts 520 et des erreurs 13 n'est **pas encore prouvée** (coexistence RF, délais négociés, BMS déjà connecté ailleurs, pile NimBLE, mémoire/fragmentation) ; le watchdog corrige une lacune de reprise mais ne garantit pas la résolution de toutes les coupures.

## Validation matérielle à effectuer avant généralisation

- Ne pas flasher ni modifier l'ESP32 VPN/Tailscale ; ne pas désinstaller l'APK, changer le code JK ou effacer les MAC.
- Sauvegarder/identifier la version JK actuelle. **Ne jamais utiliser un fichier factory ou Erase Flash pour une mise à jour ordinaire.** Le binaire APP-ONLY est destiné à l'adresse `0x10000` sur la carte classique testée.
- Comparer 2 BMS en local pendant une durée longue, puis le même essai en VPN/4G avec les mêmes deux BMS. Ne pas lancer de scan BLE manuel pendant l'observation.
- Capturer d'un seul coup : `uptime_seconds`, `wifi_disconnect_count`, `wifi_rssi_dbm`, `free_heap_bytes`, `min_free_heap_bytes`, par JK `ble_connected`, `connections`, `disconnections`, `ble_radio_disconnections`, `ble_intentional_disconnections`, `ble_last_disconnect_reason`, `ble_last_error`, `ble_connect_attempts`, `ble_scan_attempts`, `ble_timeout_ms`, `ble_interval_ms`, `ble_last_valid_frame_age_seconds`, `ble_telemetry_stall_recoveries`.
- Si le BMS n'annonce plus son adresse ou qu'une autre centrale BLE s'y connecte, ce watchdog ne suffira pas : diagnostiquer le BMS et les initiations plutôt que multiplier les resets.
- Ne pas modifier les seuils, MOS, SOC ou protections du BMS pendant ces tests.

## Sources

Code audité :
- `firmware/jk-esp32/src/jk_reader.cpp` : gestionnaire, GAP, reconnexions, watchdog
- `firmware/jk-esp32/src/main.cpp` : Wi-Fi, API GET cache, mode configuration
- `firmware/vpn-gateway/main/main.c` : relais privé GET/Tailscale
- `android/app/src/main/java/fr/smartenergy/android/MainActivity.kt` : bascule VPN/LAN
- `firmware/jk-esp32/src/ble_manager_policy.h` et tests associés

Références techniques :
- https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-guides/coexist.html
- https://apache.googlesource.com/mynewt-site/+/e30806a8a4bee8723e41f6857dbfc39a22d2c88c/docs/network/ble/ble_hs/ble_hs_return_codes.md

## Résultat de compilation

- **GitHub Actions : succès** — tests C++ et contrôle architectural + compilation ESP32, exécution n° `42689925656`.
- L'artefact `smart-jk-esp32-v0.3.11-dev-APP-ONLY-offset-0x10000` a été publié. Son expiration GitHub est prévue au 23 novembre 2026.
- Aucune validation sur BMS réel n'a encore été réalisée. L'APK 0.3.10 reste compatible et inchangée.
- Consulter : https://github.com/tapion69/smart-energy-android/actions/runs/42689925656
