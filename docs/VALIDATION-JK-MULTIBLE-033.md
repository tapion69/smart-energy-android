# Smart Energy 0.3.3-dev — JK multi-BLE, correctif candidat et validation

**Objectif :** tenir deux à quatre sessions JK simultanées et persistantes sur un ESP32 classique, sans alternance ni déconnexion des BMS sains.

## Problème observé sur matériel utilisateur

- Deux JK V19 enregistrés dans Smart Energy 0.3.2.
- JK 2 connecté : tension / courant / SOC / cellules reçus.
- JK 1 : `ble_connect_attempts=3`, `ble_scan_attempts=3`, `ble_last_error=2`, `ble_connected=false`.
- Le code 2 de NimBLE-Arduino 2.4.0 est **BLE_HS_EALREADY** : « operation already in progress or complete ». Ce n'est **pas** un code d'erreur du BMS ni une preuve de défaut matériel.
- Seuls, les deux BMS ont déjà réussi à se connecter.

## Comparaison avec l'add-on Home Assistant de référence

Référence réelle : `tapion69/smart-jkbms-bluetooth`, `smart-jkbms-bluetooth/ble/bridge.py`, gestion `persistent_sessions` :
- Un **scan partagé** de toutes les MAC manquantes, puis connexions indépendantes.
- Une session persistante par MAC avec notifications FFE1 et requêtes JK 0x97/0x96.
- Une reconnexion spécifique au seul BMS manquant, sans perturber les connexions actives.
- L'add-on utilise **BlueZ/Bleak sous Linux**, alors que l'ESP32 utilise **NimBLE-Arduino** ; il faut transposer le principe, pas recopier les appels BlueZ.

## Correction candidate ESP32 0.3.3 (APP-ONLY)

- Ajout d'une **découverte NimBLE partagée** pour toutes les MAC configurées. Le premier travailleur manquant exécute un scan passif limité ; les suivants peuvent réutiliser les adresses récemment découvertes (fenêtre de 15 s).
- Les **adresses Bluetooth sont copiées AVEC leur type public/aléatoire** avant la libération des résultats : plus aucun pointeur vers un élément de scan utilisé après son effacement.
- Chaque connexion GATT reste indépendante et persistante, protégée par un mutex pendant l'établissement et les commandes initiales.
- En cas de `BLE_HS_EALREADY` (code 2), effectuer jusqu'à **deux tentatives supplémentaires** après de courts délais, sans reset Bluetooth global ni déconnexion d'un BMS déjà actif.
- Aucun changement aux commandes de protection/écriture JK, au Wi-Fi, à BOOT, au token du VPN, à Tailscale ou au firmware ESP32 VPN.

## Test utilisateur à réaliser

1. **Seulement ESP1 (lecteur JK) :** reflasher le fichier `smart-jk-esp32-v0.3.3-dev-APP-ONLY-offset-0x10000` à l'adresse `0x10000`, **sans Erase Flash**.
2. Garder l'APK Android 0.3.2 diagnostic déjà associée : la télémétrie 0.3.3 reste compatible. Aucun changement du code personnel ni des adresses BMS.
3. Sélectionner les deux JK. Attendre 2 min, puis confirmer `2/2 JK en ligne`, `BLE connecté` sur les deux, mesures qui se mettent à jour et `0 s` ou faibles secondes de retard.
4. Laisser tourner au moins 10 min : vérifier que les deux restent connectés et les valeurs actualisées. Tester ensuite avec trois puis quatre BMS si tout est stable.
5. En cas d'échec, transmettre la capture des compteurs d'essais, du `BLE N` et l'état de chaque BMS (sans tokens ni codes).

**Limites :** compilation et tests logiciels ne valident pas encore les connexions radio simultanées sur ces BMS. Le firmware est expérimental ; ne pas l'utiliser comme source de protection de batterie. La lecture par ESP2 VPN reste inchangée et lecture seule.
