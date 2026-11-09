# Smart Energy JK 0.3.8-dev — stabilité des trois liaisons Bluetooth

## Constat rapporté

L'utilisateur a confirmé que les **3 BMS** remontent leurs données en même temps sur un seul ESP32 JK dans les versions précédentes, mais que certains compteurs affichent **plus de 10 déconnexions** pendant leur fonctionnement. Le nombre seul ne permet pas de distinguer une vraie rupture radio, une fermeture volontaire par le firmware ou une coupure temporaire du Wi-Fi.

## Erreur logicielle confirmée

Le gestionnaire `jk_reader.cpp` de 0.3.7 calcule `working = !setupMode && WiFi.status() == WL_CONNECTED`. Pour **chacun des trois BMS**, si `working` est faux, il appelait immédiatement `closeSession()`. Un bref trou de réception Wi-Fi de la box pouvait donc provoquer **3 fermetures BLE volontaires**, déclencher 3 nouvelles connexions et faire monter artificiellement les compteurs.

La 0.3.8 sépare :
- **Session BLE déjà connectée :** reste ouverte tant que l'utilisateur n'a pas explicitement ouvert le mode configuration de l'ESP32, même si le Wi-Fi coupe brièvement.
- **Nouveau scan / nouvelle connexion :** ne démarre que lorsque l'ESP32 a retrouvé son Wi-Fi normal (évite d'aggraver la concurrence radio pendant la reconnexion).
- **Mode configuration BOOT :** ferme les sessions selon le mécanisme existant. Un long échec Wi-Fi avec passage en AP de secours peut encore interrompre les sessions et sera comptabilisé.

## Coexistence ESP32 Wi-Fi + trois BLE

La puce ESP32 classique ne possède qu'une radio 2,4 GHz partagée entre Wi-Fi et BLE. Espressif confirme que 3 clients BLE sont possibles mais que le débit / intervalle / délai de supervision doivent être réglés pour ne pas dépasser la fenêtre radio.

- Intervalle demandé : **60 à 80 ms** (anciennement 40–60 ms), pour réduire la densité d'événements BLE sur les trois liens.
- Supervision demandée : **12 s** (anciennement 6 s), pour tolérer davantage les retards radio sans déclarer tout de suite une liaison perdue.
- L'ESP32 affiche aussi **les valeurs réellement négociées par le BMS** dans l'API. Le BMS peut décider d'autres paramètres, aucune garantie sans mesure réelle.
- Scan partagé, gestionnaire unique et sessions permanentes de 0.3.5 préservés.
- Pas de remise à zéro globale NimBLE, pas de rotation entre batteries, pas de commande de modification BMS automatique.

Ces réglages sont **expérimentaux** et doivent être comparés en réel aux sessions précédentes. Ne pas prolonger artificiellement tous les délais sans diagnostic si les pertes sont dues à une mémoire insuffisante ou à une perturbation radio très forte.

## Nouveaux diagnostics

Dans `/api/v1/telemetry`, par BMS :
- `disconnections`, `ble_radio_disconnections`, `ble_intentional_disconnections` ;
- `ble_last_disconnect_reason` — raison GAP/NimBLE `onDisconnect` (0x08 supervision expirée, 0x13 rupture initiée par le BMS, etc.) ;
- `ble_rssi_dbm` — puissance du signal, relue environ une fois par minute, sans requête GATT additionnelle ;
- `ble_connected_seconds`, `ble_interval_ms`, `ble_timeout_ms` — durée de connexion et paramètres réellement obtenus ;
- `wifi_connected`, `wifi_disconnect_count`, `wifi_rssi_dbm`, `free_heap_bytes`, `min_free_heap_bytes`, `uptime_seconds` — conditions Wi-Fi et mémoire.

L'APK **0.3.8** affiche les compteurs et les codes de chaque BMS même lorsqu'il est de nouveau « en ligne ». Les comptes sont **cumulés depuis le dernier démarrage de l'ESP32**, pas depuis la date d'installation de l'application.

## Protocole de validation (n'inclut aucun changement VPN)

1. Comparer l'ancien comportement : noter la durée depuis la mise sous tension, les déconnexions sur chaque BMS, la distance physique jusqu'à l'ESP32 et le signal Wi-Fi avant de flasher.
2. Brancher uniquement l'**ESP32 JK (ESP 1)** en USB. Installer le fichier **APP-ONLY** à l'offset `0x10000` **sans Erase Flash** et **sans modifier le code JK, les MAC ou le Wi-Fi**. Ne pas utiliser `COMPLETE-ERASE-FIRST` pour une mise à jour.
3. Installer l'APK 0.3.8 test (application séparée) et reconnecter au Wi-Fi local avec le code personnel JK déjà configuré. **ESP32 VPN/Tailscale (ESP2) inchangé.**
4. Vérifier la version firmware affichée **0.3.8-dev** et **3/3 JK en ligne**.
5. Laisser tourner 1 à 2 heures dans la même position (ou, idéalement, 24 h). Comparer le nombre de **nouvelles** déconnexions sur la même durée.
6. En cas de rupture, relever le code raison GAP, le RSSI, le compteur de pertes Wi-Fi et la mémoire minimale. Observer si les trois BMS tombent exactement au même moment (Wi-Fi/ESP) ou si un seul tombe (RSSI/BMS/connexion).
7. Ne pas faire de modification de protections BMS pendant cette mesure de stabilité ; sinon les commandes de paramètres ajoutent un trafic supplémentaire.

## Interprétation rapide

- `ble_radio_disconnections` augmente et GAP **8** : délai de supervision dépassé, suspecter concurrence radio Wi-Fi/BLE, signal, distance, paramètres de liaison.
- `ble_intentional_disconnections` augmente : fermeture par le gestionnaire, généralement mode configuration BOOT ou reconfiguration de MAC.
- `wifi_disconnect_count` augmente juste avant : piste du Wi-Fi de la box.
- RSSI très faible (typiquement inférieur à environ -80 dBm) : rapprochement ESP/BMS ou orientation de l'antenne à examiner, sans affirmer de seuil universel.
- Mémoire minimale qui chute très bas : investigation de pression mémoire / fragmentation.

Sources :
- Guide officiel Espressif, cohabitation Wi-Fi et BLE : https://docs.espressif.com/projects/esp-idf/en/v5.4/esp32/api-guides/coexist.html
- NimBLE-Arduino 2.5.2 client callbacks : https://github.com/h2zero/NimBLE-Arduino/blob/2.5.2/examples/NimBLE_Client/NimBLE_Client.ino

**Limite :** compilation et tests unitaires ne prouvent pas que trois BMS restent connectés 24h sur le matériel réel. Cette version est un test de stabilité, pas une déclaration de fonctionnement définitif. 
