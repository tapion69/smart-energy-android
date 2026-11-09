# ESP32-S3 N16R8 — évaluation pour Smart Energy

**Matériel envisagé :** carte ESP32-S3-DevKitC-1 / WROOM-1 N16R8 annoncée avec 16 Mo flash et 8 Mo PSRAM, deux USB-C, BLE 5 et Wi-Fi 2,4 GHz (lot Binghe). La présence réelle de la PSRAM, le type mémoire et la carte exacte doivent être vérifiés sur l'exemplaire reçu.

## Comparaison avec ESP32 classique

| | ESP32 classique | ESP32-S3 |
| --- | --- | --- |
| CPU | dual LX6 jusqu'à 240 MHz | dual LX7 jusqu'à 240 MHz |
| SRAM interne | 520 Ko | 512 Ko |
| Mémoire externe | suivant module ; souvent sans PSRAM | 8 Mo PSRAM **annoncés** sur le N16R8 |
| Wi-Fi | 2,4 GHz | 2,4 GHz |
| BLE | 4.2 | 5 (LE) |
| Bluetooth Classic | Oui | Non |
| USB natif | Non | Oui (USB OTG / USB Serial-JTAG) |
| UART matériels | 3 | 3 |

Les deux cœurs tournent à des fréquences proches. Les bénéfices réels sont surtout **la PSRAM, les périphériques et une architecture CPU plus récente**, pas une multiplication automatique des performances BLE. Sur l'ESP32-S3, la PSRAM externe n'est pas équivalente à la RAM interne en latence et n'est pas utilisable pour toutes les allocations temps réel. Les radios Wi-Fi et BLE restent en coexistence sur 2,4 GHz.

## Pour Smart Voltronic

Le S3 est un bon **nouveau choix de développement** si l'on veut gérer plusieurs onduleurs, les buffers/télémétries JSON, l'OTA sécurisé et éventuellement les adaptateurs Deye. Les données RS232 de l'onduleur ont une vitesse faible ; **un ESP32 classique suffirait pour une simple passerelle série**. Le S3 n'est pas obligatoire, mais donne de la marge.

Câblage : ne connecter **jamais directement** les fils RS232 de l'onduleur sur les GPIO 3,3 V de l'ESP32. Il faut un transceiver de niveaux MAX3232 (3,3 V) ou un adaptateur UART/RS232 isolé adapté. Vérifier la tension, le brochage (RJ45/DB9 selon modèle), les masses et la séparation galvanique avant connexion. Les deux ports USB-C ne remplacent pas cet adaptateur.

## Pour Smart JK BMS

Le JK02 V19 utilise le service BLE FFE0/FFE1 ; **le S3 est théoriquement compatible**, malgré l'absence de Bluetooth Classic. Il faudra recompiler le code NimBLE-Arduino pour une carte `esp32-s3-devkitc-1` avec flash 16 Mo, PSRAM et USB correctement configurés. Ne jamais utiliser le binaire `esp32dev` sur le S3.

**Aucune garantie d'amélioration des déconnexions** : fréquence radio 2,4 GHz partagée Wi-Fi/BLE, antenne et paramètres de supervision restent déterminants. Le problème antérieur avec trois BMS pourrait revenir sur S3. Garder la cible prudente de **2 BMS par ESP**, puis comparer 24–72 h de stabilité et les compteurs radio avec un S3 de test.

## Plan d'expérimentation

1. Acheter/tester **un seul S3 N16R8** avant d'équiper trois lecteurs ; vérifier la référence du module et la PSRAM détectée.
2. Sur `dev` uniquement, créer une cible de compilation distincte `esp32s3` avec version matériel identifiable et binaire séparé.
3. Commencer sans batterie, vérifier flash, redémarrage, Wi-Fi, stockage NVS et USB. Puis connecter **un** JK et enfin **deux**.
4. Comparer logs (BLE 520, BLE 13), qualité RSSI, reconnexions sur 24–72 h à environnement équivalent.
5. Tester Voltronic avec transceiver approprié sur banc, puis avec un onduleur ; pas de commandes d'écriture au premier test.

**Sources :** Espressif https://www.espressif.com/en/products/socs/esp32-s3 et https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-guides/ble/overview.html.

**État :** document de décision seulement ; pas de firmware ESP32-S3 livré ni validé.
