# Smart Energy 0.3.2-dev — diagnostic BLE multi-BMS (EXPÉRIMENTAL)

## Symptôme utilisateur reproductible
- Un JK V19 déjà testé se connecte lorsqu'il est le seul BMS sélectionné.
- Un autre JK V19 se connecte aussi seul.
- Lorsque les deux adresses MAC sont sélectionnées, seul un BMS reste en ligne malgré l'attente.
- Ceci ne démontre pas encore la cause exacte : initiation du lien BLE, scan pendant les notifications, ressources clients NimBLE, ou échec GATT.

## Changements *uniquement* sur la branche 0.3.2
- **ESP32 JK / ESP1** : scans passifs avec fenêtre radio réduite (120 ms / 48 ms), durée de découverte portée à 5 s, initialisation du flux et premier handshake protégés par le verrou radio. Les sessions restent **persistantes** : aucune alternance volontaire entre BMS et aucun abandon d'une liaison saine.
- Diagnostics indépendants par MAC : état, essais de scan/connexion, absence aux scans, échecs de connexion, de création de client, de service et de souscription, dernier code de retour NimBLE. Les identifiants et les codes ne sont jamais inclus dans les diagnostics.
- **Android** : page d'état de test affichant les diagnostics des BMS hors ligne. APK autonome `.vpntrial32`, pour ne pas écraser la 0.3.1.
- **ESP32 VPN / ESP2 : totalement inchangé.** Aucune modification des routes VPN, du token, du code VPN, des paramètres BMS, des contrôles BOOT ou du Wi-Fi.

## Validation
1. Sur **ESP1 uniquement**, conserver les paramètres Wi-Fi, le code et la liste des BMS. Installer le firmware **APP-ONLY** à `0x10000`, **sans effacer la flash ni la NVS**. Ne pas utiliser l'image `FULL_ERASE_FIRST` pour une mise à jour !
2. Installer l'APK Android 0.3.2 de diagnostic séparément et l'associer au même ESP1 en Wi-Fi local, avec son code actuel. Ne pas changer le code.
3. Sélectionner les deux JK. Attendre ~90 s, puis regarder les états « BMS 1 » et « BMS 2 ». Les compteurs permettent de distinguer :
   - `ble_scan_attempts` augmente mais `ble_connect_attempts` reste à 0 : cible non reconnue au scan.
   - `ble_connect_attempts` et `ble_connect_failures` augmentent : échec d'établissement BLE ; noter `ble_last_error`.
   - `ble_client_failures` : allocation/limite de clients.
   - `ble_service_failures` ou `ble_subscribe_failures` : connexion établie, échec des services ou notifications.
   - `waiting_frames` : lien connecté mais les données JK n'arrivent pas/décodent mal.
4. Vérifier les deux BMS en ligne **ensemble** pendant au moins cinq minutes ; surveiller les compteurs de déconnexion et l'âge des lectures. Tester avec leur ordre inversé seulement si le premier essai échoue.
5. Tester séparément le VPN **après** validation BLE. Le jeton Android est `X-Gateway-Token`, le code *entre les deux ESP32* doit correspondre à `CONFIG_SE_JK_READ_CODE` de l'ESP2. Ne pas communiquer de codes personnels.

## Limites et sécurité
- Une CI verte signifie que les projets compilent et les tests logiciels réussissent, **pas** que plusieurs liens BLE sont validés sur le matériel JK réel.
- La télémétrie et les écritures de paramètres restent expérimentales ; ne pas baser une protection de batterie sur le prototype.
- Les écritures distantes restent interdites ; ne jamais rediriger le port HTTP du JK sur Internet.
- Ne pas publier de jetons VPN, clés Tailscale, identifiants Wi-Fi ou codes BMS dans un ticket GitHub.
