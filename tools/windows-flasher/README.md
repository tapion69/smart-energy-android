# Smart Energy Flasher Windows — version précompilée (dev)

Le programme \`SmartEnergyFlasher.exe\` contient les outils nécessaires à l'écriture USB d'un ESP32 classique. **Aucun Git, ESP-IDF ou Python à installer sur le PC utilisateur.** Un pilote USB-série (CP210x, CH340 ou FTDI selon la carte) reste éventuellement nécessaire.

## Fichiers nécessaires

- Un \`SmartEnergyFlasher.exe\` compilé par [GitHub Actions](https://github.com/tapion69/smart-energy-android/actions/workflows/build-windows-flasher.yml).
- Le \`.bin\` **précompilé correspondant à la carte** et au mode de flash. Pour le VPN, [voir le workflow firmware VPN](https://github.com/tapion69/smart-energy-android/actions/workflows/build-vpn-gateway.yml). Les artefacts du workflow ne contiennent **aucun mot de passe, clé Tailscale ou code BMS**.
- Un ESP32 **classique** de test et un câble USB transmettant les données. **Ne pas utiliser ces binaires sur ESP32-S3.** Les images S3 doivent être compilées et testées séparément.

## ESP32 JK

- **Mise à jour** d'un appareil existant : sélectionner l'image JK APP-ONLY, flash \`0x10000\`, pas de \`erase_flash\` ni de reconfiguration.
- **Première installation** : sélectionner l'image JK FACTORY à \`0x0000\`, uniquement sur un nouvel ESP32 ; ce mode peut remplacer le partitionnement et rendre la NVS inaccessible sur un ancien ESP32.

## ESP32 VPN — première installation avec firmware universel

1. Télécharger le \`.bin\` \`smart-energy-vpn-USB-FACTORY-ESP32-0x0.bin\` (pour une nouvelle carte classique uniquement).
2. Ouvrir le \`.exe\`, sélectionner l'onglet **ESP32 VPN**, le port COM USB et le firmware précompilé.
3. Renseigner les champs Wi-Fi (2,4 GHz), mot de passe, **clé d'association Tailscale personnelle**, token de passerelle et jusqu'à trois couples IP/code de lecteurs JK (JK1 requis dans ce prototype).
4. Cliquer **Flasher et configurer l'ESP32 VPN**. Le programme écrit l'image, attend la réponse \`SEVPN_READY_V1\`, puis transmet les informations **uniquement par liaison USB physique**. L'ESP32 répond \`SEVPN_OK_REBOOT\` après écriture NVS, et redémarre.
5. Depuis Android sur le même tailnet, vérifier \`/api/v1/health\` puis \`/api/v1/jk/telemetry\`.

## Modification ultérieure sans reflasher

- Brancher le câble USB, redémarrer la passerelle (NE PAS maintenir BOOT pendant la mise sous tension : cela entre dans le bootloader ROM).
- Pendant les 9 premières secondes **après démarrage**, maintenir BOOT environ 3 secondes.
- Dans l'EXE, cliquer **Configurer via USB sans reflasher**. Le programme envoie à nouveau les champs, conserve les autres partitions et les autres appareils JK.
- La commande de reconfiguration n'est disponible qu'après autorisation physique BOOT ; aucune route HTTP ou Tailscale ne permet de modifier les identifiants.

## Mise à jour d'une passerelle VPN existante

Choisir **APP-ONLY à 0x10000**. Le firmware universel conserve la partition NVS et les identités Tailscale si le partitionnement est compatible. Une passerelle utilisant l'ancienne méthode avec des secrets compilés devra être configurée une fois via USB après cette mise à jour, faute de config NVS universelle.

**Important :** ne pas convertir une passerelle actuellement opérationnelle sans carte de réserve et sans validation matérielle du firmware universel. L'image FACTORY \`0x0\` n'est jamais une mise à jour ; elle pourrait écraser la NVS.

## Sécurité et statut

- **Prototype de développement non validé sur carte réelle** : la compilation et les tests ne garantissent ni Tailscale, ni la séquence USB, ni les reprises de courant.
- Aucun secret n'est intégré dans le firmware générique ou l'EXE. La transmission USB doit se faire sur un ordinateur de confiance. Les logs n'impriment pas les champs ni leur contenu.
- La configuration est pour le moment enregistrée dans la **NVS non chiffrée** de la carte. Un attaquant disposant physiquement du module peut potentiellement extraire ces secrets : **flash encryption/secure boot** et stratégie de révocation sont à prévoir avant diffusion grand public.
- La passerelle refuse toutes les commandes d'écriture relayées ; seules des routes GET explicitement autorisées vers trois IP LAN privées sont prévues.
- Le programme ne lance **jamais \`erase_flash\`**. Un flash FACTORY peut néanmoins écraser des partitions puisqu'il réécrit la zone de démarrage : **nouveaux appareils uniquement**.
- Le modèle ESP32-S3 N16R8 doit avoir sa propre image et un mécanisme USB compatible testé ; **ne jamais lui flasher l'ESP32 classique**.

## Code / tests

- \`tools/windows-flasher/smart_energy_flasher.py\` — interface graphique
- \`tools/windows-flasher/vpn_usb.py\` — transport série, vérification des images, protocole USB
- \`firmware/vpn-gateway/main/vpn_provision.c\` — validation et stockage NVS, activation BOOT
- GitHub Actions compile l'EXE et les images génériques dans des workflows séparés. Plus tard, les deux fichiers pourront être distribués ensemble dans un paquet de release signé.
