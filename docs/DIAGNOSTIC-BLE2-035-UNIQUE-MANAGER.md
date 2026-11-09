# Smart Energy 0.3.5-dev — analyse profonde BLE 2 et gestionnaire unique

## Problème reproduit sur l'installation

Deux JK BMS V19 détectés, adresses enregistrées durablement, mais un seul connecté simultanément. Les captures ont montré plusieurs fois **BLE 2** (`BLE_HS_EALREADY`), avec changement du BMS qui réussit selon les essais. Dans la variante à découverte partagée, la seconde liaison atteignait `ble_busy_retry_exhausted` (12 tentatives).

Il ne s'agit pas d'un échec de décodage 0x02/0x03. Le code 2 est retourné pendant la **tentative de connexion centrale**. Selon la documentation NimBLE, `BLE_HS_EALREADY` signifie qu'une autre initiation est active, et `BLE_HS_EBUSY` que la recherche est encore active.

## Recherche sur les sources amont

- Apache NimBLE (API `ble_gap_connect()`) : [opération de connexion déjà en cours = 2](https://apache.googlesource.com/mynewt-site/+/dc10b57973810ead72da606e6390bbed126b202e/versions/v1_3_0/docs/network/ble/ble_hs/ble_gap/functions/ble_gap_connect.md).
- NimBLE-Arduino 2.4.0 : méthode `NimBLEClient::connect`, gestion des `EBUSY` / `EALREADY`, et états de connexion.
- NimBLE-Arduino **2.5.0** corrige l'état des connexions clientes ; **2.5.2** corrige en plus des problèmes mémoire et de reconnexion d'identité ; intégrée maintenant dans notre prototype.
- Add-on HA [Smart JK-BMS Bluetooth](https://github.com/tapion69/smart-jkbms-bluetooth) : sessions GATT persistantes distinctes, partage des scans, abonnement FFE1 et requêtes lecture uniquement 0x97 / 0x96.
- [Guide officiel Espressif multi-connexions](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/ble/ble-multiconnection-guide.html) : les capacités de l'hôte **et** du contrôleur doivent correspondre ; la mémoire et les paramètres de connexion influencent la stabilité.

## Changement architectural 0.3.5

**Avant :** quatre tâches FreeRTOS responsables chacune d'essayer de scanner, initier, s'abonner et maintenir sa session. Un mutex protégeait partiellement les étapes de connexion, mais chaque tâche pouvait recommencer séparément. Plusieurs tentatives consécutives étaient parfois renvoyées avec code 2.

**Maintenant :** un **seul gestionnaire FreeRTOS** possède toutes les opérations de scan et de connexion. Il lance **au maximum une initiation à la fois**, attend son résultat, laisse la session ouverte, puis passe au prochain BMS. Les notifications de chaque BMS sont reçues indépendamment et en continu par la tâche NimBLE du système. Une liaison saine n'est jamais volontairement alternée pour permettre la connexion d'une autre.

- Bibliothèque NimBLE-Arduino bloquée sur **2.5.2**.
- Reconnexions **indépendantes par BMS** avec temporisation progressive (4, 9, 16, 30 s) : un JK absent n'interrompt pas les autres.
- Désactivation des tentatives internes implicites (`setConnectRetries(0)`) ; une seule source possède la planification des tentatives.
- Délai d'établissement 15 s, paramètres de connexion adaptés à la coexistence Wi-Fi/BLE 2,4 GHz.
- Conservation du scan BLE partagé ; mémorisation du type d'adresse BLE avant destruction des résultats.
- Vérification/annulation uniquement de l'initiation GAP bloquée, **aucune réinitialisation générale de NimBLE** tant qu'un autre BMS est connecté.
- Diagnostics : durée de la dernière connexion, GAP actif avant/après, scan encore actif, compteurs de scans, échecs, récupération.
- Paramètres BMS : aucun changement des seuils ni écriture automatique. Les rares modifications pilotes restent locales avec confirmation par relecture.
- Version réellement présente sur ESP32 ajoutée à l'écran de connexion.
- **ESP32 VPN/Tailscale (ESP 2) strictement inchangé** : aucune modification de ses secrets, du jeton, de l'adresse Tailscale ni de son firmware.

## Limites à ne pas masquer

Cette refonte est **expérimentale** tant qu'on n'a pas mesuré simultanément les deux JK en fonctionnement réel. Une compilation et des tests unitaires ne reproduisent pas les contraintes radio exactes.

Le paquet Arduino-ESP32 précompilé utilisé dans le build courant contient `CONFIG_BTDM_CTRL_BLE_MAX_CONN=3`. La déclaration `-D ...=4` dans PlatformIO ne reconstruit pas le contrôleur précompilé : **quatre connexions simultanées ne sont pas encore validées ni garanties**. Pour quatre, il faudra revoir le SDK/précompilation du contrôleur, avec des tests mémoire et radio séparés.

## Procédure de validation utilisateur

1. **ESP32 JK (ESP 1) uniquement** : télécharger l'artefact `smart-jk-esp32-v0.3.5-dev-APP-ONLY-offset-0x10000` dans GitHub Actions, décompresser, écrire le .bin à **0x10000, sans Erase Flash**. Ne pas installer l'image complète `COMPLETE-ERASE-FIRST` ici. L'ESP32 VPN (ESP 2) ne doit pas être connecté au flasheur.
2. Installer l'APK `smart-energy-android-v0.3.5-single-ble-manager-debug-test`. Il s'installe séparément des anciennes versions et peut nécessiter de ressaisir le code JK **actuel** dans l'association locale. Aucun BOOT de récupération si le code est connu.
3. Sur le Wi-Fi domestique, Tailscale coupé pendant cet essai local, vérifier que l'écran affiche **firmware 0.3.5-dev**. Dans le cas contraire, ne pas interpréter les compteurs comme une validation de cette refonte.
4. Sélectionner les deux JK, attendre deux minutes et observer `2/2 JK en ligne`. Vérifier que les courants, tensions et SOC des **deux** sont frais.
5. Laisser fonctionner 10 minutes sans déconnexion. Prendre capture de **Accueil** et des deux onglets BMS.
6. En cas d'échec, capturer uniquement le texte des diagnostics : scans, connexions, code BLE, délai de connexion, GAP 0/1, scan actif, attentes/libérations/blocages. **Ne jamais partager un mot de passe**.
7. Ne tester le VPN séparément qu'après ce test BLE ; le correctif VPN de l'APK est déjà présent, sans reflasher ESP 2.

## Validation CI

- Compilation du firmware ESP32 et de l'APK.
- Tests synthétiques des décodeurs JK, de la politique BOOT et de la logique d'écriture.
- Tests unitaires du délai de retry et du débordement `millis()`.
- Test statique garantissant **une seule tâche de gestion Bluetooth**, sans reconnecter volontairement un JK sain ni réinitialiser tout le contrôleur.
