# Smart Energy VPN Gateway — firmware universel USB (dev)

**État : expérimental.** Le firmware est précompilé une seule fois, sans secrets d'installation. Un programme Windows indépendant envoie ensuite le Wi-Fi, la clé Tailscale et les lecteurs JK par **USB physique**, et l'ESP32 garde ces valeurs dans sa partition NVS.

**Pour l'instant : ESP32 classique uniquement. L'ESP32-S3 N16R8 nécessitera sa propre compilation, son paramétrage PSRAM et des essais USB.**

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
