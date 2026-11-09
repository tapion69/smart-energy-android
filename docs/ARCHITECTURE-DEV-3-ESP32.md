# Smart Energy Android — architecture en développement (9 novembre 2026)

## Dépôt GitHub à deux branches

- \`main\` : référence stable conservée, non modifiée avant validation sur matériel.
- \`dev\` : **seule** branche où créer les correctifs, nouvelles fonctionnalités et builds expérimentaux.
- Les anciennes branches \`experiment/*\` sont sauvegardées sous des tags Git \`archive/2026-11-09/*\` avant suppression. Les tags conservent leurs commits et fichiers mais ne figurent pas dans la liste des branches.
- Pas de branche par version : les prochaines évolutions sont des commits sur \`dev\`.

## Architecture retenue

- 1 ESP32 JK = **2 BMS maximum configurables depuis l'APK**, afin de privilégier la stabilité. La limite du firmware n'est pas pour l'instant une preuve de stabilité au-delà de 2.
- Jusqu'à **3 ESP32 JK**, chacun avec sa configuration locale indépendante, soit **6 JK BMS au maximum** pour l'installation cible.
- Les noms personnalisés sont conservés par MAC dans l'application Android.
- L'APK \`0.3.12-dev\` propose 3 profils de lecteurs sélectionnables dans **Configuration et association**, avec des IP, codes, identifiants et BMS mémorisés séparément. À ce stade, les tableaux détaillés suivent **le lecteur sélectionné** : la synthèse simultanée des trois lecteurs et les 6 cartes sur un seul écran restent à développer après validation.
- L'ESP32 VPN **reste un appareil distinct** des trois lecteurs JK et ne se connecte pas en Bluetooth. Le firmware VPN \`0.2.0-dev-multi3\` relaie en lecture seule les lectures des trois lecteurs.
- Routes pour JK1 : anciennes \`GET /api/v1/jk/telemetry\`, \`GET /api/v1/jk/details\` et alias \`/api/v1/jk/1/*\`. JK2 : \`/api/v1/jk/2/telemetry\`, \`/api/v1/jk/2/details\`. JK3 : équivalent \`/3/\`. Tous les POST et chemins arbitraires restent rejetés.
- Le firmware VPN doit être compilé **localement avec les IP et codes propres à l'installation**. Un binaire générique non configuré ne peut pas remplacer la passerelle personnelle actuelle.
- Le firmware JK 0.3.11 reste inchangé sur \`dev\`, avec récupération automatique de session BLE figée après 60 secondes. La validation de deux JK sur longue durée est en cours.

## Paramètres JK : nouvelles règles d'interface

- Un bouton unique **Activer les modifications** apparaît dans l'onglet Paramètres du BMS sélectionné.
- Avertissement clair et confirmation avant d'activer le mode. Le mode est désactivé lors du changement de BMS, de la navigation entre onglets ou de la sélection d'un autre lecteur.
- En mode normal, seul le nom du paramètre et sa valeur apparaissent. En mode modification, les boutons **Modifier** apparaissent.
- **Chaque valeur** modifiée requiert la confirmation de l'ancienne et de la nouvelle valeur. Les paramètres critiques exigent également la saisie explicite \`PROTECTION\`.
- Aucune écriture via la passerelle VPN ; ne pas modifier les protections sans connaître les spécifications de la batterie.

## Utilitaire de flash

Le projet \`tools/windows-flasher\` contient un utilitaire \`SmartEnergyFlasher.exe\` créé par GitHub Actions :
- JK : flash APP-ONLY \`0x10000\` sans effacement des données NVS, ou image complète pour une carte neuve avec confirmation.
- VPN : formulaire de configuration de 1 à 3 lecteurs JK, Wi-Fi/Tailscale/token ; génère \`sdkconfig.credentials\` **en local** puis compile/flashe via ESP-IDF 5.3.2 et Git. Prérequis Windows ; pas de clé intégrée à l'EXE.
- Les deux modes sont expérimentaux ; aucune mise à jour distante silencieuse.

## Vérifications restantes avant publication

1. Installer l'APK de test côte à côte (identifiant \`.vpntrial312\`) sans désinstaller celle qui fonctionne ; **les identifiants mémorisés ne sont pas importés automatiquement** entre ces deux applications séparées. Refaire l'association locale une fois par lecteur.
2. Confirmer les trois profils : chacun conserve son IP, son code Android Keystore et ses BMS après navigation/redémarrage.
3. Confirmer le renommage des batteries et le blocage d'édition par défaut, puis tester seulement un réglage non critique sur matériel dédié.
4. Vérifier les trois routes du nouveau VPN en 4G/5G avec un ESP VPN de test, après construction avec secrets locaux. Garder le firmware VPN actuellement stable tant que le nouveau n'a pas été validé.
5. Tester le flasheur Windows sur une carte ESP32 JK de réserve et une carte VPN distincte. Les builds réussis ne sont pas une preuve du fonctionnement USB sur PC utilisateur.
6. Après validation de deux JK stables, construire une vue globale fraîche pour les six JK sur trois lecteurs, sans bloquer l'interface ni faire de scans supplémentaires.
7. Avant usage public : stockage/provisionnement sécurisé des secrets, signature de firmware et release APK, OTA avec rollback, et instrumentation de déconnexions/erreurs.

**Sources** : \`android/app/src/main/java/fr/smartenergy/android/MainActivity.kt\`, \`BatteryAdvancedCard.kt\`, \`JkGlobalOverview.kt\`, \`firmware/vpn-gateway/main/main.c\`, \`gateway_policy.h\`, \`tools/windows-flasher/\`.
