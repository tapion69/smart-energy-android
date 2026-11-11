# Smart Energy VPN Gateway — firmware universel USB (dev)

**État : expérimental.** Le firmware est précompilé une seule fois, sans secrets d'installation. Un programme Windows indépendant envoie ensuite le Wi-Fi, la clé Tailscale et les lecteurs JK par **USB physique**, et l'ESP32 garde ces valeurs dans sa partition NVS.

**Deux variantes sont désormais précompilées : ESP32 classique (4 Mo) et ESP32-S3 N16R8 (16 Mo flash / 8 Mo PSRAM).** Le firmware S3 est désormais compatible avec **les deux types de connexion USB** : l'USB Serial/JTAG natif et les ports USB-UART CH343/CP210x reliés à UART0 (GPIO43/44). C'est le même protocole `SEVPN_READY_V1`/`SEVPN_CONFIG` sur les deux ports, jamais via Internet. **Ces images S3 n'ont pas encore été validées sur carte réelle** ; l'ancien ESP32 VPN en service doit rester intact.

## Architecture

\`Téléphone Android + Tailscale → tunnel WireGuard/TailTiny → ESP32 VPN → Wi-Fi privé → jusqu'à 3 ESP32 JK (2 BMS par lecteur)\`

Le firmware utilise TailTiny épinglé au commit \`d1ec2ae45e252e8dc737452be0f6afe1ac2e3edb\`. Pas de Home Assistant, de routeur de sous-réseau, de redirection de port internet ou de Bluetooth sur la passerelle VPN.

### Routes GET autorisées

- \`/api/v1/health\` — état du VPN sans secret
- \`/api/v1/jk/telemetry\`, \`/api/v1/jk/details\` — lecteur 1 (compatibilité)
- \`/api/v1/jk/{1,2,3}/telemetry\`, \`/api/v1/jk/{1,2,3}/details\` — lecteur visé
- \`/api/v1/voltronic/status\` est **désactivée** tant qu'un endpoint de lecture fiable n'est pas défini

Les requêtes doivent venir du tailnet vers l'IP VPN de la passerelle et utiliser le header \`X-Gateway-Token\`. Le relais n'accepte que des chemins fixes vers des IP LAN privées configurées par USB. Aucune écriture BMS, configuration réseau ou URL arbitraire n'est relayée.

## Installation USB sans SDK

- **Nouveau module classique :** binaire \`smart-energy-vpn-USB-FACTORY-ESP32-0x0.bin\` flash à \`0x0\`.
- **Ancien module compatible :** binaire \`smart-energy-vpn-USB-APP-ESP32-0x10000.bin\` flash à \`0x10000\`, sans effacement NVS.
- **Configuration :** \`SmartEnergyFlasher.exe\` envoie \`SEVPN_CONFIG <JSON>\` par UART0 115200 bauds ; les clés reconnues sont \`wifi_ssid\`, \`wifi_password\`, \`tailscale_key\`, \`gateway_token\`, \`jk1_ip\`, \`jk1_code\`, jusqu'à \`jk3_ip\`/\`jk3_code\` (optionnels). Une validation stricte précède \`nvs_commit\`.
- En sortie, l'ESP32 annonce \`SEVPN_READY_V1\`, puis après sauvegarde \`SEVPN_OK_REBOOT\`. Il redémarre automatiquement, rejoint le Wi-Fi et Tailscale.
- **Reconfiguration :** connexion USB + maintien du bouton BOOT pendant 3 secondes au cours des 9 secondes après démarrage ; aucun HTTP distant ne peut ouvrir ce mode.

Voir [guide Windows](../../tools/windows-flasher/README.md).

## Développement

L'outil de compilation est **ESP-IDF 5.3.2 dans GitHub Actions**, pas chez les utilisateurs. Le build archive une image FACTORY et une image APP-ONLY, génériques et sans secrets. La compilation ESP32-S3 sera un travail séparé.

Les anciens scripts \`prepare-windows.cmd\` et \`sdkconfig.credentials.example\` sont hérités de la méthode de compilation personnelle et **ne doivent plus être utilisés avec la version USB**. Le CMake principal n'inclut plus \`sdkconfig.credentials\`.

## Sécurité et limites

- **NVS non chiffrée** : l'extraction physique du flash peut révéler des clés. Pour une diffusion publique : chiffrement flash/NVS, boot sécurisé, politique de renouvellement Tailscale, signature et vérification des images.
- Le provisionnement se fait exclusivement sur le port série physique UART0 d'un ESP32 classique ; protéger l'accès physique au boîtier. Ne jamais envoyer les secrets à GitHub ou dans les journaux de diagnostic.
- Le composant TailTiny est tiers. Sa stabilité et sa résilience après coupures Wi-Fi/DERP doivent être testées sur les cartes réelles.
- L'image générique n'est pas encore validée matériellement. **Ne pas reflasher la passerelle VPN de production** avant d'avoir testé un autre ESP32 en parallèle.

## ESP32-S3 N16R8 (prototype)

Le job `esp32s3` du workflow `.github/workflows/build-vpn-gateway.yml` génère deux images ciblant **esp32s3** avec IDF 5.3.2 et PSRAM octale :

- `smart-energy-vpn-USB-FACTORY-ESP32S3-N16R8-0x0.bin` — seulement pour un S3 vierge, bootloader S3 à **0x0**, table à 0x8000 et application à 0x10000 ;
- `smart-energy-vpn-USB-APP-ESP32S3-N16R8-0x10000.bin` — application seule, **0x10000**, NVS conservée *si le partitionnement existant correspond*.

Dans le nouveau `SmartEnergyFlasher.exe`, choisir explicitement **ESP32-S3 N16R8** dans l'onglet VPN, puis `Factory` pour un S3 neuf ; le programme vérifie les en-têtes d'image et l'identifiant de puce avant tout flash. Ne sélectionner **jamais** une image ESP32 classique sur un S3, ni l'inverse. Pas d'effacement de la flash par défaut.

**Première validation sur matériel :** port USB de données reconnu Windows, envoi `SEVPN_READY_V1`, configuration physique avec confirmation `SEVPN_OK_REBOOT`, Wi-Fi connecté, identité Tailscale créée et GET `/api/v1/health` depuis le tailnet. Tester ensuite la lecture JK1/JK2/JK3. Aucune validation réelle ne peut être déclarée avant ces tests.

Ce firmware reste expérimental : NVS non chiffrée, sécurité physique nécessaire, pas de serveur public ni de redirection de ports Internet.

## Correctif USB ESP32-S3 du 11 novembre 2026

Le flasheur d'origine pouvait écrire le firmware par une interface **CH343 USB-to-UART** (COM Windows), puis échouer à la configuration : l'application S3 répondait uniquement sur son USB Serial/JTAG natif. Le firmware corrigé ouvre **UART0 GPIO43/44 à 115200 bauds et USB Serial/JTAG natif**, et répond sur le canal physique qui a envoyé `SEVPN_PING` / `SEVPN_CONFIG`. Pas de Wi-Fi, VPN ni serveur exposé pendant le provisionnement ; aucun secret renvoyé dans les journaux.

- Si le S3 a déjà reçu **notre image Factory VPN** et le flasheur est resté à 80 % en erreur USB, conserver les partitions et **flasher la nouvelle APP-ONLY S3 à 0x10000** (pas Factory), puis compléter la configuration. Si le S3 possède une prise USB native séparée, essayer également `Configurer via USB sans reflasher` sur cette prise avec l'ancien firmware.
- Remplacer tout SSID `*_5GHz` par celui du **réseau 2,4 GHz**. Le S3 n'a pas de radio Wi-Fi 5 GHz ; un libellé de réseau ne prouve pas toujours sa bande réelle, d'où un avertissement plutôt qu'un blocage dans le programme Windows.
- Aucun `erase_flash` automatique ni changement de configuration JK/BMS. Ne pas reflasher l'ESP VPN classique qui fonctionne.
- La validation réussie exige le retour USB `SEVPN_OK_REBOOT` et ensuite la connexion Wi-Fi/Tailscale : une compilation seule n'en est pas la preuve.
