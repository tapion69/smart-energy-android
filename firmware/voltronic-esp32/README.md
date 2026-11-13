# Smart Energy Voltronic — ESP32-S3 N16R8 (v0.2.1-dev diagnostic HTTP, lecture seule)

**État : moteur série et binaire compilables en CI. Validation sur un véritable onduleur Axpert Ultra encore nécessaire.**

Ce firmware ESP32-S3 N16R8 a été refondu en prenant comme référence le flow **Smart Voltronic Node-RED** actuellement accessible dans `tapion69/smart_voltronic`, branche `develop`, fichier `smart-voltronic/flows.json`. La référence est le moteur de communication déjà éprouvé par l'utilisateur, pas un protocole imaginaire. **Le flow HA complet n'a pas été copié** : MQTT, les automatisations, la configuration Home Assistant et toutes les écritures/SET onduleur sont volontairement exclus de cette passerelle Android **strictement en lecture seule**.

## Câblage physique

- ESP32-S3 N16R8 + transceiver **MAX3232 3,3 V**, alim USB S3 indépendante ;
- UART1 TX **GPIO17** → entrée **RX** du convertisseur TTL ;
- UART1 RX **GPIO16** ← sortie **TX** du convertisseur TTL ;
- GND → GND ; 3V3 → VCC ;
- DB9 RS232 → câble DB9/RJ45 spécifiquement compatible avec le port COM Voltronic de l'onduleur (pas un câble Ethernet).
- Paramètres série : **2400 bauds, 8 bits, sans parité, 1 bit stop, sans contrôle de flux**, comme le flow d'origine.

**Attention** : vérifier le croisement TX/RX et le brochage DB9 avant toute connexion. Un câble RS232 précédemment fonctionnel avec un autre convertisseur ne garantit pas automatiquement le même sens de broches avec le MAX3232. Ne jamais brancher le RS232 directement sur les GPIO 3,3 V.

## Audit / portage depuis Node-RED

| Composant Node-RED | ESP32-S3 0.2.0 | Limite |
|---|---|---|
| `helpers.buildVoltronicCommand` | CRC16/XMODEM octets high/low bruts + CR | commandes SET impossibles, liste blanche |
| `construire commande` | commande associée à sa réponse | aucun payload d'identité publié |
| `parser` | accumulateur UART par octets, trames finissant par CR, contrôle CRC | rejette et compte les trames incorrectes au lieu de les décoder silencieusement |
| `Queue Pump / Queue Enqueue` | un seul échange série à la fois, ordonnancement équitable | pas de commandes manuelles via HTTP |
| `requetes` | QPIGS, QPIGS2, QMOD, QPIRI, QDOP, QPIWS, QET/QLT, QMN, QID, QVFW/QVFW3 et compteurs datés | optionnel/NAK = retry ; date UTC après NTP |
| `normalize state` | valeurs QPIGS, QPIGS2, QMOD, paramètres QPIRI/QDOP, compteurs d'énergie | pas de mapping inventé, pas de pseudo-mesures réseau |
| HA discovery, MQTT, SET/VERIFY | **non portés** | hors périmètre d'une passerelle Android en lecture seule |

- QPIGS 2,5 s, QPIGS2 4 s, QMOD 12 s, QPIRI/QDOP/QPIWS 5 min ; autres informations périodiques.
- QPIGS2 suspendu 1 h après NAK / réponse incompatible, puis retenté.
- Le S3 conserve son fonctionnement et ses requêtes de mesures même sans accès NTP ; seuls QED/QEM/QEY/QLD/QLM/QLY demandent l'horloge UTC synchronisée. Les unités d'énergie sont des valeurs retournées par l'onduleur ; vérifier avec le flow HA avant toute facturation.
- `pv1_power_native_w` n'est renseigné que si un champ parmi les positions candidates du flow est cohérent avec `V × I`. Sinon, ne pas affirmer qu'il est mesuré. `grid_power_native_w` n'est exposé que si le champ 25 est réellement présent et valide.
- Les mesures demeurent conservatrices. Les avertissements `QPIWS` conservent les bits bruts et sont également décodés d'après la table `normalize state` du flow ; `Line fail` reste un **avertissement** (le réseau peut être volontairement coupé), pas automatiquement un défaut critique.

## API Wi-Fi

Port **18081**, GET uniquement avec en-tête `X-Setup-Code: <code choisi au flash>` :

- `/api/v1/info` → `source=voltronic-rs232-read-only`, version, transport, capacités ;
- `/api/v1/telemetry` → `available`, `age_ms`, `values` si QPIGS récent, et compteurs ;
- `/api/v1/details` → mode QMOD, disponibilité QPIGS2, paramètres QPIRI/QDOP validés, énergie brute, QPIWS (bits + alertes classées) ;
- **`/api/v1/diagnostics`** → commandes envoyées, octets reçus, trames complètes, `crc_rejects`, `partial_frames`, `no_rx_timeouts`, `parse_rejects` et dernier échantillon.
- **v0.2.1-dev :** `/telemetry` et `/diagnostics` exposent en plus les compteurs Wi-Fi (`wifi_disconnect_count`, `wifi_last_disconnect_reason`, `wifi_rssi_dbm`) et serveur HTTP (`http_telemetry_requests`, `http_send_failures`), mémoire et uptime. Il n'y a **aucun SSID, code, MAC ou adresse IP** dans les diagnostics. Le serveur a une pile de 8 Ko, une purge LRU des sessions anciennes et un délai réseau de 4 secondes. Il continue de lire le RS232 exactement comme avant.

**Pour la panne « `available=false`, `polls_ok=0`, `timeouts=111` » :**
1. La bonne réponse `/api/v1/info` (HTTP 200 après code) confirme que Wi-Fi et provisionnement fonctionnent.
2. Relire `/api/v1/diagnostics` après plusieurs secondes.
3. Si `rx_bytes_total=0` avec `no_rx_timeouts` qui augmente : vérifier MAX3232 / RX/TX / câble DB9/RJ45 / port COM.
4. Si `rx_bytes_total>0` avec `partial_frames` ou `crc_rejects` : examiner trames incomplètes, CRC et variante de protocole. **Ne pas publier de dump brut, de code ni d'identifiant d'onduleur.**
5. Les `timeouts` seuls ne prouvent pas qu'aucun octet n'est reçu.

Sous PowerShell, sans afficher ni publier le code :

```powershell
$code = Read-Host "Code de lecture Voltronic"
Invoke-RestMethod -Uri "http://192.168.1.63:18081/api/v1/info" -Headers @{"X-Setup-Code"=$code}
Invoke-RestMethod -Uri "http://192.168.1.63:18081/api/v1/diagnostics" -Headers @{"X-Setup-Code"=$code}
Invoke-RestMethod -Uri "http://192.168.1.63:18081/api/v1/telemetry" -Headers @{"X-Setup-Code"=$code}
Remove-Variable code
```

## Flasher Windows précompilé : préserver la configuration existante

Le workflow `.github/workflows/build-vpn-gateway.yml` inclut un job **Voltronic S3** et génère un unique **SmartEnergyFlasher.exe** avec firmware JK, VPN et Voltronic précompilés.

Dans l'onglet **Voltronic S3** :

- **APP-ONLY (recommandé si S3 Voltronic déjà configuré)** : image `smart-energy-voltronic-S3-APP-0x10000.bin` à l'adresse `0x10000` ; pas de provisionnement USB, **préserve Wi-Fi + code en NVS**. Pour passer de la v0.2.0 à la v0.2.1-dev, flasher uniquement cette image, sans effacement de la flash. Ne jamais utiliser APP-ONLY pour remplacer un firmware d'un autre projet (JK/VPN).
- **FACTORY (S3 neuf uniquement)** : image `smart-energy-voltronic-S3-FACTORY-0x0.bin` à l'adresse `0x0` ; configure ensuite Wi-Fi et code de lecture 8–32 caractères via USB physique. Peut effacer les données présentes ; ne pas choisir pour un S3 déjà associé.
- Ne pas reconfigurer/effacer le S3 VPN Tailscale, ni les lecteurs JK BMS.

Source : `firmware/voltronic-esp32/main/main.c` et `voltronic_protocol.h`. Tests natifs : `tests/test_protocol.c`. CI : `build-voltronic-s3.yml` + `build-vpn-gateway.yml`.

**Une compilation réussie n'est pas une preuve de fonctionnement RS232 sur matériel réel. La validation se fera avec les trames et diagnostics du vrai onduleur, en comparaison avec le flow HA.**
