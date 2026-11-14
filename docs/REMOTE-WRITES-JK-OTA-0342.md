# Smart Energy 0.3.42 — relecture Voltronic, OTA JK et écritures VPN

Développement expérimental du 14 novembre 2026, branche `dev`.

## Correctif de l'APK Voltronic

Le bouton `Onduleurs → INV1/2/3 → Tous les paramètres → Lire / modifier`
affichait une boîte « Chargement des réglages INV1 » bloquante qui
restait au premier plan après le retour QPIRI/QDOP. Remplacée par un
Toast non bloquant. La boîte des valeurs n'apparaît qu'après la réponse.

Ce changement d'interface ne modifie aucun paramètre électrique.

## OTA JK : première migration USB obligatoire sur les anciens firmwares

- Prototype firmware JK `0.3.15-dev-ota-preview`.
- `GET /api/v1/ota/status` vérifie le vrai partitionnement et le
  démarrage courant. `ota_supported` est faux si aucune partition
  de secours n'existe. Aucun faux statut OTA.
- `POST /api/v1/ota/image` : authentification individuelle JK,
  `X-OTA-Confirmed: yes`, SHA-256 strict 64 hex, écriture progressive
  sur l'autre partition uniquement, comparaison de hash avant
  `Update.end`, redémarrage volontaire. Aucun effacement NVS.
- L'APK lit chaque ESP1/ESP2/ESP3 séparément, vérifie le catalogue
  GitHub public, version supérieure, adresse LAN, partition et SHA256
  du binaire avant proposition avec confirmation. Aucune commande OTA
  via le VPN.
- Le catalogue `updates/jk-esp32.json` reste `available:false`
  pour interdire toute distribution automatique avant les tests
  matériels de rollback et de préservation des BMS.
- Sur les anciens firmwares JK annoncés `ota:false`, l'OTA
  ne peut pas s'installer rétroactivement par l'APK. Il faut
  d'abord une migration USB `app-only` à 0x10000 **UNIQUEMENT
  si la partition existante est compatible**, sans `erase_flash`
  ni image factory. Ne pas effectuer cette migration sans vérifier
  les partitions réelles de l'ESP et sa configuration.

## Écritures de paramètres JK et Voltronic via VPN : opt-in physique

Les firmwares VPN préexistants ne possèdent pas les nouvelles routes.
Une simple mise à jour de l'APK 0.3.42 ne suffit donc pas.

- Prototype VPN `0.3.4-dev-explicit-remote-parameter-writes`, pour
  ESP32 classique ou ESP32-S3 selon le matériel réellement installé.
- Mise à jour USB **app-only**, jamais `erase_flash`, pour conserver
  l'identité Tailscale, Wi-Fi, IP/codes JK, et IP/codes Voltronic.
- Le relais VPN possède par défaut
  `remote_parameter_writes_opt_in:false`. Même si le token VPN est
  correct, toutes les routes d'écriture restent verrouillées.
- Pour autoriser : brancher l'ESP VPN en USB, redémarrer et maintenir
  BOOT 3 secondes durant la fenêtre de démarrage de 9 secondes, puis
  dans le flasheur Windows utiliser
  « Autoriser écritures VPN (USB) ». Pour revenir au mode lecture
  seule, « Bloquer écritures VPN (USB) ».
- La commande USB modifie **uniquement** `remote_writes_enabled`
  en NVS et compare les autres clés avant de sauvegarder. Ne jamais
  exporter les secrets ni les enregistrer sur GitHub.
- Les écritures VPN restent limitées aux routes sémantiques :
  `POST /api/v1/jk/{slot}/settings-refresh`,
  `/write-arm`, `/write`, et
  `POST /api/v1/voltronic/{slot}/parameters/change`.
  `GET /api/v1/jk/{slot}/write-status` retourne l'état.
  Aucune écriture arbitraire, modification Wi-Fi, association BLE,
  récupération de code, commande série brute ou OTA par VPN.
- Conditions cumulatives : tunnel Tailscale, IP VPN restreinte
  100.64.0.0/10, jeton ESP VPN, IP LAN et code JK/Voltronic
  configurés **dans l'ESP VPN**, activation physique,
  appareil identifié, valeur native relue avant modification,
  autorisation individuelle et vérification après modification.
- Le VPN relaie seulement les données de la requête approuvée vers
  un ESP32 local fixe. La destination/clé de l'équipement n'est
  **jamais fournie par le téléphone**.
- En cas de réponse perdue ou de valeur non confirmée, **aucun
  nouvel essai automatique** ne doit être déclenché. Lire la valeur
  réelle sur l'équipement avant tout autre essai.

Les modifications de protections de batterie (JK) et les seuils
électriques de l'onduleur sont des opérations à risque.
Avant un essai distant, valider les lectures de télémétrie et
les écritures non critiques sur l'installation avec supervision
locale, et s'assurer de pouvoir rétablir l'accès physique.

## Limites des tests actuels

- Compilation CI et tests de politique VPN réalisés.
- Les changements réels JK et Voltronic via la nouvelle passerelle
  VPN n'ont pas encore été confirmés sur le matériel de l'utilisateur.
- Le nouveau récepteur OTA JK n'a pas encore été validé sur ESP32
  réel après un flash USB; le rollback lors d'une perte d'alimentation
  ou d'un firmware non démarrable n'est pas encore prouvé.
- Les images factory ne doivent pas être utilisées pour mettre
  à jour les dispositifs déjà configurés.
