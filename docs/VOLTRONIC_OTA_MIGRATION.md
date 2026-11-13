# Voltronic ESP32-S3 — migration USB sécurisée vers OTA (prototype)

Le firmware actuel 0.3.1 et l'image APP-ONLY standard disposent de
`CONFIG_PARTITION_TABLE_SINGLE_APP_LARGE` : **aucune OTA sûre possible**.
L'endpoint `/api/v1/ota/status` renvoie donc `ota_supported=false`,
et un POST de mise à jour répond par un refus sans écrire la flash.

## Partitionnement nouveau pour S3 N16R8 16MB

Table `partitions_ota.csv` :
- NVS 0x9000 / 0x6000 (**ancienne position et taille conservées**)
- phy_init 0xF000 / 0x1000 (**ancien emplacement**)
- factory 0x10000 / 2MB
- ota_0 0x210000 / 2MB
- ota_1 0x410000 / 2MB
- otadata 0x610000 / 8KB

L'OTA n'est activable qu'après migration initiale du bootloader et de
la table de partitions. La future installation USB doit écrire uniquement :
`0x0 bootloader.bin`, `0x8000 partition-table.bin`,
`0x10000 smart_energy_voltronic_s3.bin`.

**NE JAMAIS** utiliser une image MERGED `FACTORY-0x0.bin` pour une
migration : les octets 0xFF entre la table et l'application pourraient
effacer les données NVS. Le flasher ne doit jamais appeler `erase_flash`.

Le code OTA vérifie présence de `ota_0/ota_1`, taille, CRC de l'image
via ESP-IDF et SHA-256 fournie par le manifeste officiel GitHub. En cas
de SHA invalide, le nouveau partitionnement n'est pas sélectionné.
L'APK utilise seulement le Wi-Fi local et une action explicite utilisateur.

## Publication

Pour distribuer une OTA :
1. Compiler le profil **OTA**, jamais l'image legacy.
2. Vérifier sur matériel S3 qu'une migration est stable et NVS conservée.
3. Publier l'image applicative `.bin` dans les Releases GitHub.
4. Reporter URL, SHA256, version, cible `esp32s3-n16r8` dans
   `updates/voltronic-esp32.json`, puis activer `available=true`.
5. L'APK informe l'utilisateur ; il peut choisir le lecteur à mettre à jour.
   Aucun transfert ne démarre sans confirmation.
6. Le VPN passe uniquement les routes GET de télémétrie, **jamais l'OTA**.

**Limite** : migration USB/rollback à valider sur carte réelle avant de
la recommander en production. Le firmware JK et les associations JK ne
sont pas concernés. Les données NVS peuvent rester présentes si
les nouveaux offsets sont corrects ; prévoir une configuration USB de
secours en cas d'incompatibilité du contenu.
