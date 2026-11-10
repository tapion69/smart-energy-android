# Smart Energy Voltronic — ESP32-S3 N16R8 (prototype lecture seule)

**État : code et compilation expérimentaux, aucune validation sur un vrai onduleur pour l'instant.**
Ce module indépendant **n'embarque ni Node-RED, ni MQTT, ni le dashboard HA**.
Le dépôt source JK BMS reste intact. Aucune commande d'écriture Voltronic n'est générée.

## Matériel de départ

- ESP32-S3 N16R8 (16 Mo flash, 8 Mo PSRAM), alimenté par USB 5 V indépendant ;
- transceiver **MAX3232 compatible 3,3 V** entre UART TTL 3,3 V et RS232 ;
- ESP **UART1 TX GPIO17**, **UART1 RX GPIO16**, baud **2400**, **8N1**, flow-control désactivé ;
- côté onduleur, câble DB9/RJ45 selon le **brochage du port COM exact**. L'éventuelle prise RJ45 n'est pas de l'Ethernet et ne doit pas être branchée sur un routeur ;
- prévoir isolation galvanique avant une installation permanente si nécessaire.

ATTENTION : ne jamais relier directement une sortie RS232 (-12/+12 V possibles) à un GPIO 3,3 V. Ne pas utiliser les ports BMS/CAN ou puissance. Vérifier les masses, le sens RX/TX et le brochage dans le manuel matériel.

## Pipeline choisi à partir du flow Smart Voltronic

Le flow Node-RED d'origine `flows(20261002-154611).json` contenait **322 nœuds** ; seuls les invariants éprouvés ont été repris. La référence publique actuelle est `tapion69/smart_voltronic/smart-voltronic/flows.json`, mais elle ne doit **pas** être copiée intégralement dans le S3.

- CRC16/XMODEM sur la commande ASCII, CRC haute puis basse, octets 0x28/0x0D/0x0A incrémentés, suivi du CR ;
- une **seule commande à la fois**, association stricte réponse/requête, validation de CRC sur la réponse, timeout et contrôles de plages ;
- **autorisé en V1** : `QPIGS` (mesures), `QPIGS2` (MPPT2 lorsqu'il existe), `QMOD` (mode), `QPIRI` (paramètres lus) ; `QPIWS` est dans la liste blanche protocole mais son décodage/ordonnancement n'est pas encore inclus ;
- **interdit** : toute commande de changement POP, PCP, PBCV, PBFT, PBDV, charge, MOS, SOC ou protection. Aucun endpoint HTTP n'envoie de commande série ;
- `QPIGS` tenté ~2,5 s ; `QPIGS2` ~4 s avec pause d'1 h sur NAK/incompatibilité ; `QMOD` ~12 s ; `QPIRI` ~5 min ;
- pas de compteurs énergie, apprentissage automatique, MPPT2 Modbus, ratios non validés, valeurs inférées ou historique dans cette première version.

La structure conservatrice `vt_qpigs_t` vient des champs vérifiés du module et expose AC, PV1, batterie, courant global calculé, SOC et charge. Le champ grid_power n'est exposé que si réellement fourni en index 24, **pas estimé comme valeur certaine**. Tous les champs JSON sont accompagnés de disponibilité/fraîcheur.

## Configuration sur USB natif S3

La première mise en route attend une configuration physique sur USB Serial/JTAG ; **aucun accès Wi-Fi ou HTTP n'est ouvert avant cela**. Le protocole de provisionnement (pas un mot de passe partagé) est :

- Signal `SEVOLT_READY_V1`, ou envoyer `SEVOLT_PING\n`.
- Envoyer une ligne JSON précédée de `SEVOLT_CONFIG ` avec les champs `wifi_ssid`, `wifi_password` et `read_code` (code personnel 8–32 caractères).
- Sur réponse `SEVOLT_OK_REBOOT`, la configuration est sauvegardée en NVS puis la carte redémarre.
- Pour modifier une configuration existante, maintenir BOOT (GPIO0) environ 3 secondes **après** redémarrage, dans une fenêtre de 9 secondes. L'outil USB est à ajouter au flasheur Windows une fois le premier firmware validé.

**Ne pas publier les secrets de Wi-Fi ni le code de lecture en GitHub, dans les journaux ou les captures.** La NVS de ce prototype n'est pas chiffrée et peut être extraite par une personne ayant accès physiquement à la carte.

## API LAN (port 18081)

Chaque requête doit inclure `X-Setup-Code: <code personnel>`. Réponses strictement en GET :

- `GET /api/v1/info` — `source=voltronic-rs232-read-only`, version, modèle ESP, `read_only:true`
- `GET /api/v1/telemetry` — `source=voltronic-qpigs-read-only`, `available`, `age_ms`, mesures fraîches si datées de 10 s maximum
- `GET /api/v1/details` — code de mode QMOD, disponibilité QPIGS2, bulk/float lus depuis QPIRI

Aucune IP publique n'est nécessaire ; on utilise le Wi-Fi local. Le relais VPN n'est **pas encore raccordé** à ce nouveau lecteur. L'APK propose en configuration un **test de détection** de l'API ; l'affichage intégré du tableau de bord viendra après test des trames réelles.

## Tests et compilation

- Hôte : `gcc -std=gnu11 -Wall -Wextra -Werror firmware/voltronic-esp32/tests/test_protocol.c -o /tmp/test-voltronic -lm && /tmp/test-voltronic`
- Compilation S3 automatique dans [GitHub Actions](https://github.com/tapion69/smart-energy-android/actions/workflows/build-voltronic-s3.yml), ESP-IDF v5.3.2, cible esp32s3.
- Artefacts précompilés distincts : `smart-energy-voltronic-S3-FACTORY-0x0.bin` (nouvelle carte seulement) et `smart-energy-voltronic-S3-APP-0x10000.bin` (mise à jour après validation des partitions/NVS).

**Aucune mesure réelle ni stabilité matérielle ne sont garanties par une compilation GitHub réussie.**
