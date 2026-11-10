# Smart Energy Android — architecture de lecture SunSpec et Voltronic S3

Date : 10 novembre 2026. Version APK de test **0.3.20-dev** (paquet `fr.smartenergy.android.vpntrial320`), indépendante des APK JK existantes.

## Principes immuables

- La gestion actuelle JK BMS, ses firmwares et sa passerelle VPN Tailscale en service **ne sont pas modifiés**.
- L'APK permet désormais dans **Configuration → Onduleurs Voltronic / SunSpec — lecture seule** de tester une connexion, sans encore afficher les télémétries dans la synthèse globale.
- Ni le module SunSpec ni le module Voltronic n'envoient de commandes de configuration ni de modification de paramètres à un onduleur.
- Pas de serveur payant, pas d'enregistrement d'historique sur l'ESP, pas de MQTT/Node-RED sur le lecteur S3.
- Les secrets d'association sont propres à l'installation ; ne jamais publier de Wi-Fi, clés d'accès ou trames sensibles dans GitHub ou dans les captures.

## SunSpec : Modbus/TCP local (lecture FC03)

`SunSpecReadOnly.kt` réalise :
- validation d'une adresse IPv4 **privée uniquement** et du port TCP (502 par défaut) ;
- transaction Modbus/TCP avec contrôle de l'identifiant, code fonction **03** (lecture), longueur, unité esclave (1 par défaut) ;
- recherche des en-têtes SunSpec `SunS` à plusieurs bases courantes, puis découverte des modèles Common/Inverter/Meter/Storage qui sont réellement déclarés ;
- pas de FC06/FC16 ni de serveur ou passerelle ESP imposée lorsque l'onduleur expose directement SunSpec Modbus/TCP.

**Ce premier prototype découvre uniquement les IDs de modèles.** Il ne décode pas encore les facteurs d'échelle ni les registres des modèles 101, 103, 111, 160 etc. Les lectures réelles dépendront de la table SunSpec et des registres exposés sur chaque onduleur ; ne jamais inventer de mesures. Certains fabricants ne supportent que Modbus RTU/RS485 ou une passerelle Modbus distincte ; un ESP32-S3 isolé RS485 sera envisagé pour ces cas, pas un branchement direct à la borne BMS/CAN ou Meter.

## Voltronic / Axpert Ultra : ESP32-S3 + RS232 physique

Le nouveau code est dans `firmware/voltronic-esp32`, compilé en cible ESP32-S3 N16R8 ESP-IDF 5.3.2. Il utilise un **MAX3232 compatible 3,3 V** et un câble vérifié du port PC/RS232 ; UART1 TX GPIO17 / RX GPIO16, 2400 bauds 8N1 pour le premier test. Ne brancher jamais du RS232 direct sur un GPIO 3,3 V.

Réemploi *minimal* des invariants de l'ancien flow Smart Voltronic : CRC16/XMODEM corrigé pour les octets 0x28/0x0D/0x0A, commandes `QPIGS`, `QPIGS2`, `QMOD`, `QPIRI` strictement en lecture ; réponse CRC vérifiée et timeout, appareil non compatible MPPT2 mis en pause 1 h en cas de NAK.

L'ESP expose les routes GET avec code personnel (port LAN 18081) :
- `/api/v1/info` : version, interface série, lecture seule ;
- `/api/v1/telemetry` : AC entrée/sortie, puissances, SOC batterie, courants batterie, PV1/PV2 **uniquement si mesures reçues et fraîches** ;
- `/api/v1/details` : QMOD et éléments de QPIRI lus, à valider en pratique.

La conversion et l'association des champs restent **expérimentales** tant que les trames du véritable Axpert Ultra ne sont pas vérifiées. Les commandes de modification des seuils, priorités, charge, bulk/float sont absentes. Aucun compteur QED/QLD/QEM/QLM ni historique intégré à cette première version.

## Flasheur Windows tout-en-un

Le workflow `.github/workflows/build-vpn-gateway.yml` peut désormais compiler et inclure dans `SmartEnergyFlasher.exe` :
1. JK ESP32 classique (inchangé) ;
2. VPN ESP32 classique (inchangé) ;
3. VPN ESP32-S3 N16R8, factory 0x0 / app-only 0x10000 ;
4. **Voltronic ESP32-S3 N16R8**, factory 0x0 / app-only 0x10000.

L'onglet Voltronic S3 comporte une configuration physique par USB Serial/JTAG `SEVOLT_CONFIG` pour Wi-Fi/code de lecture. Les images et leur identifiant de puce sont vérifiés avant de flasher, sans `erase_flash` automatique. La nouvelle carte S3 sera testée en parallèle, sans toucher au VPN opérationnel.

## Séquence de validation matérielle après réception du S3

1. Vérifier l'inscription exacte de la carte, la taille flash/PSRAM, Windows COM et quel connecteur USB est natif.
2. Sur une carte S3 **neuve**, choisir VPN S3 + FACTORY ; vérifier handshake USB `SEVPN_READY_V1`, la configuration NVS confirmée et Tailscale.
3. Utiliser le VPN S3 en parallèle de l'ancien et tester les lectures JK1/JK2/JK3 ; ne retirer l'ancien qu'après stabilité prolongée.
4. Sur un S3 **distinct** pour Voltronic, flasher le lecteur read-only ; configurer Wi-Fi/code USB ; vérifier `/api/v1/info` avant de connecter le MAX3232. Vérifier le pinout exact avant le câble onduleur.
5. Contrôler les commandes QPIGS/QPIGS2/QMOD et les valeurs sans faire d'écriture. En cas d'anomalie, corriger le parseur/ordonnancement ; seulement après validation, ajouter les cartes mesures à l'APK.
6. SunSpec : si un onduleur compatible est disponible sur le réseau, tester son port Modbus TCP en lecture et noter uniquement les IDs de modèles exposés.

**Statut :** compilations CI uniquement, aucun matériel testé dans cette livraison. Les protections internes des batteries/onduleurs doivent rester indépendantes de cette application.
