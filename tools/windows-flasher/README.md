# Smart Energy Flasher — installation Windows sans SDK

**Nouvelle version (dev)** : l'utilisateur n'a besoin ni de Git, ni d'ESP-IDF, ni de Python.

Pour la passerelle VPN sur **ESP32 classique**, le workflow [Build VPN + Windows](https://github.com/tapion69/smart-energy-android/actions/workflows/build-vpn-gateway.yml) produit un seul fichier **SmartEnergyVPNFlasher.exe** contenant déjà :
- l'image **FACTORY** (nouvelle carte uniquement, à l'adresse `0x0`) ;
- l'image **APP-ONLY** (mise à jour à `0x10000`, NVS conservée).

### Première installation d'une nouvelle carte

Brancher l'ESP32 avec un câble USB de données, sélectionner son port COM dans l'EXE, ouvrir l'onglet ESP32 VPN, garder « nouvelle passerelle (FACTORY) », remplir les champs Wi-Fi 2,4 GHz, clé d'association Tailscale, token de passerelle, IP/code des ESP32 JK (maximum trois), puis cliquer **Flasher et configurer**. Le programme flashe l'image intégrée, attend `SEVPN_READY_V1`, transmet les données uniquement par USB et vérifie `SEVPN_OK_REBOOT`. Aucun secret n'est inclus dans le firmware générique ni dans l'EXE.

### Reconfigurer sans flasher

Après redémarrage, maintenir BOOT environ 3 secondes au cours des 9 premières secondes d'exécution, **pas** pendant l'alimentation électrique de la carte (sinon entrée en mode bootloader). Puis dans l'EXE, **Configurer via USB sans reflasher** et attendre `SEVPN_OK_REBOOT`. Aucune route Tailscale/HTTP ne permet la reconfiguration.

### Mise à jour

Choisir APP-ONLY pour une passerelle VPN **compatible**, sans `erase_flash`. Si l'ancienne configuration était compilée dans le firmware, renseigner les champs et reconfigurer une fois par USB. **Ne pas expérimenter sur une passerelle qui fonctionne déjà sans carte de réserve.** FACTORY n'est pas une mise à jour et peut rendre les anciennes données NVS inaccessibles.

### ESP32 JK

L'onglet JK sait flasher son image APP-ONLY `0x10000` ou FACTORY `0x0`. Le firmware JK n'est pas inclus dans l'EXE VPN ; il doit être sélectionné depuis un build officiel et compatible.

### Limites et sécurité

- **Prototype : aucune validation physique USB/Tailscale achevée à ce stade** ; compilation et tests logiciels seulement.
- Les firmwares intégrés ciblent **l'ESP32 classique**, jamais l'ESP32-S3 N16R8 ; une variante distincte sera nécessaire.
- L'ESP32 doit disposer d'un pont USB-série compatible (CH340, CP210x, FTDI). Un pilote USB peut être nécessaire suivant Windows.
- L'identité Tailscale et les identifiants sont conservés dans la **NVS non chiffrée** de l'ESP32. Une personne ayant accès au matériel peut potentiellement les extraire. La protection flash, la signature, la rotation/révocation et les tests de récupération sont nécessaires avant diffusion publique.
- Utiliser le firmware du dépôt officiel ; le flasheur ne valide pas encore cryptographiquement la signature d'un fichier .bin sélectionné manuellement.
- L'ESP32 VPN sert seulement de relais GET pour trois lecteurs JK du réseau local. Il ne commande jamais les BMS.
