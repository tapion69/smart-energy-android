# Smart Energy 0.3.3-dev — JK multi-BMS et code NimBLE BLE 2

## Observation réelle (2026-11-09)

Deux BMS ont été enregistrés et détectés, mais seul l'un est connecté :
- BMS 1 `C8:47:80:29:84:50` : état `ble_connect_failed`, 3 scans, 3 tentatives, **`ble_last_error=2`** ;
- BMS 2 `C8:47:80:2B:31:F6` : BLE connecté, mesures JK cohérentes et actualisées.

## Cause technique : operation de connexion déjà en cours

Dans NimBLE, `BLE_HS_EALREADY = 2`. Le code `ble_gap_connect` renvoie cette erreur quand un **initiateur GAP** est déjà actif : cela ne signifie PAS que la MAC du second BMS est mauvaise, ni que ses trames ont échoué au décodage.

L'add-on `tapion69/smart-jkbms-bluetooth` utilise Python Bleak/BlueZ sur hôte Home Assistant et verrouille les périodes de scan sans alterner les connexions GATT persistantes. Notre lecteur ESP32 utilise un contrôleur BLE NimBLE embarqué avec un seul initiateur de connexion à la fois ; copier du Python à l'identique n'est ni possible ni souhaitable.

## Correctif expérimental

- Garder chaque session BMS **persistante**, sans rotation ni déconnexion d'une session saine.
- Vérifier que la procédure NimBLE d'initiation précédente a réellement fini avant **chaque scan et chaque nouvelle connexion**.
- Sous le verrou radio, attendre temporairement la fin d'une initiation. Si elle reste bloquée, ne **cancel** que la procédure de *connexion en cours* via `ble_gap_conn_cancel`. Aucune connexion établie n'est terminée par ce contrôle.
- Attendre la fin de la récupération avant de laisser un autre BMS commencer sa recherche.
- Utiliser 15 s pour l'établissement BLE, comme le délai de l'add-on, au lieu des 9 s antérieurs.
- Afficher `ble_gap_busy_count`, `ble_gap_cancel_count`, `ble_gap_still_busy_count` et un texte dédié à `BLE 2` pour comprendre les éventuelles récidives.
- La section VPN et **ESP32 VPN n°2** restent strictement inchangés ; l'APK embarque les précédentes améliorations de diagnostic VPN, sans toucher au firmware ESP2.

## Procédure de test

1. Sur **ESP32 JK (ESP1) uniquement**, flash **APP-ONLY à 0x10000**, sans `Erase Flash`. Ne jamais utiliser l'image de première installation complète pour ce test.
2. Le Wi-Fi, les MAC JK et le code de l'ESP32 JK doivent normalement être conservés en NVS. Vérifier l'identité et l'adresse LAN du lecteur avant toute modification.
3. Installer l'APK indépendante `smart-energy-android-v0.3.3-multibms-diagnostic-debug-test`. Chaque APK de test distincte dispose d'un stockage de secrets propre ; reconnecter ESP1 en local avec le code JK actuel au besoin, sans nécessairement refaire BOOT. Ne pas partager le code dans un journal ou une capture.
4. Connecter le téléphone au Wi-Fi local, Tailscale désactivé, choisir les deux JK et attendre **2 minutes**. Confirmer `2/2 JK en ligne` et que les mesures des deux batteries restent actualisées.
5. Laisser fonctionner **au moins 5 minutes**, vérifier qu'aucune connexion ne retombe, que les compteurs de récupération ne croissent pas sans fin et que les deux trames restent fraîches.
6. Si le BMS 1 reste en échec, fournir une capture de la ligne des compteurs (scans, tentatives, BLE, attente contrôleur, récupérations). Ne pas oublier qu'un autre client Bluetooth (application JK / add-on HA) peut aussi monopoliser une connexion JK ; éviter les connexions tierces *uniquement pendant cet essai*, si possible.
7. Si nécessaire, relancer le test avec le BMS 1 seul puis les deux, mais conserver le VPN ESP2 dans son état initial.

Ce build **compile** avec les tests, mais l'utilisation de deux à quatre JK simultanés sur un ESP32 réel doit encore être confirmée. Les commandes de paramètres JK restent expérimentales ; ne pas les utiliser pour ce diagnostic de lecture.

**Références techniques** :
- Apache NimBLE `nimble/host/include/host/ble_hs.h` : `BLE_HS_EALREADY=2`.
- NimBLE-Arduino 2.4.0 `src/NimBLEClient.cpp` : démarrage de `ble_gap_connect`, erreur `EALREADY` lors d'une autre tentative en cours.
- Add-on `tapion69/smart-jkbms-bluetooth/smart-jkbms-bluetooth/ble/bridge.py` : `BLE_SCAN_LOCK`, sessions Bleak persistantes et commandes de lecture `0x97`/`0x96`.
