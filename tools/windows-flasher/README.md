# Smart Energy Flasher — un seul EXE Windows, JK + VPN

**Version expérimentale :** `SmartEnergyFlasher.exe`, sans installation de Python, Git ou ESP-IDF sur Windows. Les firmwares **JK et VPN sont tous les deux déjà inclus** dans l'EXE.

Télécharger l'artefact **`SmartEnergyFlasher-Windows-AllInOne-JK-VPN`** du dernier build réussi du [workflow tout-en-un](https://github.com/tapion69/smart-energy-android/actions/workflows/build-vpn-gateway.yml). Ne plus télécharger les anciens `SmartEnergyVPNFlasher-Windows-ESP32-Firmware-Included` ou `SmartEnergyFlasher-Windows-EXE` : ils n'ont pas les correctifs décrits ici.

## Utilisation

1. Brancher **un seul ESP32 classique** en USB avec un câble de données.
2. En haut, sélectionner son port dans le **menu déroulant COM** (l'EXE détecte automatiquement les appareils USB et permet d'actualiser la liste). Un port débranché n'est jamais remplacé silencieusement par un autre.
3. Choisir l'onglet **ESP32 JK** ou **ESP32 VPN** ; les firmwares officiels précompilés sont sélectionnés automatiquement. Aucun fichier `.bin` n'est à choisir.
4. Sélectionner **FACTORY** pour un **nouvel ESP32 vierge** ou **APP-ONLY** pour mettre à jour un appareil existant, sans effacer volontairement la NVS.
5. Sur l'onglet **JK**, cliquer `Flasher le lecteur JK`. Sur l'onglet **VPN**, renseigner les champs **Wi-Fi, clé d'association Tailscale, token de passerelle, IP et codes personnels des ESP JK 1 à 3**, puis `Flasher et configurer`. Le firmware VPN enregistre les paramètres reçus via le port série dans la NVS.
6. Lire la confirmation dans le journal ; contrôler ensuite sur le vrai appareil que les connexions Wi-Fi, BLE et/ou Tailscale sont effectives.

### Deuxième lecteur JK pour les batteries 3 et 4

- Nouveau lecteur ESP32 classique : onglet **JK**, mode **FACTORY 0x0000**, fichier intégré `smart-jk-esp32-factory.bin`.
- Ancien lecteur déjà configuré : mode **APP-ONLY 0x10000** ; **ne jamais** choisir FACTORY.
- Après flash du nouvel ESP : procéder à l'association BOOT et à la configuration des deux BMS dans l'APK. Les mesures des deux lecteurs seront agrégées dans la vue globale.

### Passerelle VPN

- L'onglet VPN montre **de vrais champs de saisie** pour le Wi-Fi/Tailscale et les trois lecteurs, organisés en blocs indépendants pour éviter leur disparition sous les boutons.
- Le firmware précompilé `smart-energy-vpn-USB-FACTORY-ESP32-0x0.bin` ou `smart-energy-vpn-USB-APP-ESP32-0x10000.bin` est intégré à l'EXE.
- Le token de passerelle est distinct du code JK ; la clé Tailscale doit être encore valable pour l'association.
- L'option `Configurer via USB sans reflasher` exige l'autorisation physique BOOT sur un firmware universel déjà installé.
- **Attention à la migration de l'ancien VPN en service :** même si APP-ONLY n'efface pas volontairement la NVS, le partitionnement, les identifiants compilés dans l'ancienne image et l'identité TailTiny peuvent être incompatibles. Faire le test sur un deuxième ESP32 avant de convertir le VPN fonctionnel.

### Options expertes

Chaque onglet dispose d'un bouton `Options avancées` permettant de sélectionner un autre fichier `.bin`, mais ce n'est **pas nécessaire** en usage normal. N'utiliser que les fichiers du projet et vérifier leur provenance.

## Correctifs du flasheur

- L'exécutable `--windowed` créait `sys.stdout=None` et `sys.stderr=None`, ce qui provoquait `'NoneType' object has no attribute 'flush'` dans esptool. Le flasheur initialise désormais correctement ces flux avant le flash.
- Le port COM n'a plus à être saisi manuellement : liste déroulante et bouton **Actualiser**, avec description USB des ports.
- Une **barre de progression toujours visible sous le menu COM** suit le flash JK/VPN. Pendant l'écriture, le pourcentage affiché provient des informations réelles d'esptool ; tant que la connexion et la préparation sont en cours, elle s'anime sans inventer de pourcentage. Pour le VPN, l'installation n'est indiquée comme terminée qu'après la confirmation USB `SEVPN_OK_REBOOT` de l'enregistrement NVS. En cas d'échec, la barre reste incomplète et le journal indique l'erreur.
- La disposition de l'onglet VPN a été corrigée : les boutons radio de sélection ne masquent plus les champs.
- JK et VPN sont désormais distribués **dans le même EXE**, sans second téléchargement.
- Aucune commande `erase_flash` n'est exécutée par le programme.

## Sécurité et limites

Ces versions **ne sont pas encore validées sur les ESP32 physiques**. Les tests et compilations automatisés ne prouvent pas la réussite de l'association Tailscale ou de l'écriture USB ; vérifier sur une carte de réserve. Les configurations VPN restent actuellement stockées dans une **NVS non chiffrée**, donc sensible à l'accès physique. Ne pas publier de clés Tailscale, codes ou mots de passe dans les journaux ou captures.

**ESP32 classique uniquement** : ne jamais flasher ces images sur ESP32-S3 N16R8.
