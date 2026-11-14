# Smart Energy Android 0.3.43-dev — paramètres VPN, catalogue complet et secours USB JK

Date du travail : 14 novembre 2026. Branche `dev`.

## APK Android
- `versionCode 57`, `versionName 0.3.43-dev`.
- Conserve le paquet `fr.smartenergy.android` et le certificat permanent.
- Côté Voltronic, l'onglet **Onduleurs → INVx → Paramètres** présente les
  paramètres interprétés et les autres positions numériques natives fraîches
  de QPIRI/QDOP. **Les positions sans commande validée restent non modifiables**.
- En VPN, les paramètres Voltronic peuvent être consultés. Les écritures ne
  deviennent sélectionnables que si l'onduleur annonce `verified-write-v1`
  et si l'ESP VPN annonce l'autorisation physique.
- Côté JK, écran Paramètres : affichage du nombre de champs validés sur les
  **47 champs connus**. Le firmware peut annoncer `unavailable_items` pour les
  champs de la trame type 1 non reconnus ; aucun zéro fictif n'est affiché.
- L'interface indique si le mode modification JK est local ou VPN.
- Le tableau de bord JK et les associations BLE sont inchangés.

## VPN / modification de paramètres
- La passerelle ESP VPN est **en lecture seule par défaut**.
- Elle doit recevoir le nouveau firmware **APP-ONLY via USB** en conservant
  son NVS (identité Tailscale, Wi-Fi, token et configuration JK).
- Pour ajouter un Voltronic, utiliser `Ajouter INV sans effacer le reste`,
  jamais une reconfiguration complète.
- Après redémarrage du VPN, BOOT 3 s dans les neuf premières secondes puis
  dans le flasheur `Autoriser écritures VPN (USB)`. Un autre bouton peut
  les rebloquer à tout moment.
- Les routes distantes acceptent seulement les opérations JK
  `settings-refresh`, `write-arm`, `write`, `write-status` et
  Voltronic `parameters/change`. Les IP et codes du LAN restent provisionnés
  localement sur le VPN, jamais fournis par les requêtes Tailscale.
- L'ESP cible effectue toujours la validation de valeurs, l'authentification
  locale et la relecture de confirmation ; pas de deuxième envoi automatique.
- **Non validé en conditions matérielles réelles** : tester un paramètre
  non critique et confirmer la relecture avant toute utilisation courante.
  Les protections batteries ne doivent pas être modifiées à distance
  avant validation complète.

## Secours JK via USB quand l'OTA est indisponible
- Nouveau flasheur Windows tout-en-un compilé en CI, inclut les firmwares
  JK, VPN et Voltronic.
- Choisir `ESP32 JK → APP-ONLY`; le programme lit d'abord la table à
  `0x8000`, vérifie un partitionnement connu et que l'image tient dans APP
  à `0x10000`.
- **Une sauvegarde locale et privée de la vraie partition NVS est obligatoire
  avant le flash**. Si une lecture, une vérification ou une écriture de
  sauvegarde échoue, aucun flash n'est lancé.
- Le flash APP-ONLY n'écrit ni la partition NVS ni la table de partitions.
  Il ne crée donc pas magiquement de partitions OTA sur un ancien plan simple.
- Le fichier NVS contient possiblement des secrets, ne jamais le partager.
- Ne jamais choisir FACTORY sur un appareil déjà associé.
- Après l'USB, contrôler la version, les BMS enregistrés, le Wi-Fi,
  le fonctionnement BLE et `/api/v1/ota/status` avant toute OTA ultérieure.

## Validation
- Les tests Windows du flasheur couvrent des tables ESP32 simulées, les
  sauvegardes NVS et les refus de flash pour layouts non compatibles.
- La compilation CI et les tests de sources ne prouvent pas encore un essai
  de modification distante sur une batterie ou un onduleur réel.
