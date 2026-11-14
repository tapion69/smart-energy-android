# Smart Energy Android 0.3.40-dev — expérimentation Voltronic OTA intégrée

Date : 14 novembre 2026. Dépôt `tapion69/smart-energy-android`, branche `dev`.

## Résultat de compilation
- APK officielle `fr.smartenergy.android`, versionName `0.3.40-dev`,
  versionCode `54`, signée avec la **même clé permanente** que 0.3.39-dev.
- Workflow réussi : https://github.com/tapion69/smart-energy-android/actions/runs/43590745781
- Artefact `smart-energy-android-signed-upgradeable`.
- APK SHA256 du build :
  `8c9111309ae94f75946fa18342b7fe89518db08040e8eb1a5db2fe52eb003b8b`.
- Firmware intégré : `assets/voltronic-s3-0.3.4-test.bin` (901664 octets),
  SHA256 `9cfc8c87280c50bf92eb4f6b8029ea67004aa79cfbe7ff0faa0f24f78e3bf289`.
  L'image est récupérée du build CI Voltronic vérifié 43585410799.

## Parcours utilisateur dans l'APK (AUCUN PowerShell)
1. Installer l'APK officielle signée 0.3.40-dev. Si 0.3.39 officielle
   est déjà installée, l'installation conserve ses données Android.
   Une ancienne APK debug avec identifiant `.vpntrial333` ne peut pas
   être remplacée directement : la conserver et migrer sa configuration.
2. Associer INV1 au lecteur Voltronic S3 sur le Wi-Fi LAN avec IP/code privés
   (seulement si cela n'est pas déjà fait dans l'APK officielle).
3. Dans **Mises à jour**, choisir
   **TEST · OTA Voltronic 0.3.4 depuis cette APK**, puis **INV1**.
4. L'APK vérifie **en lecture seule** : identité ESP32-S3, version exacte
   `0.3.2-dev-ota-gated-read-only`, double partition OTA,
   `max_image_bytes`, absence de rollback en attente, checksum de
   l'image intégrée. Toute différence bloque le transfert.
5. **Confirmer « Installer 0.3.4 »**. Transfert direct de l'APK vers
   `/api/v1/ota/image` sur le réseau Wi-Fi physique seulement, avec
   `X-OTA-Confirmed: yes` et SHA256 épinglé. Le S3 calcule aussi le SHA
   et valide l'image via ESP-IDF avant de choisir la partition.
6. L'APK tente ensuite de relire `/api/v1/info` et attend la version
   `0.3.4-dev-ota-parameter-edit-test`. Si elle ne peut la vérifier,
   elle n'affirme pas le succès et **n'envoie aucun second flash**.

## Limites de sécurité
- Test non réalisé sur matériel réel au moment de la compilation.
- Pas de publication automatique du firmware via `main/updates/`.
- Aucune modification de firmware JK ou VPN, ni du dashboard JK.
- L'OTA ne touche pas aux valeurs de l'onduleur ; la nouvelle capacité
  de modification des paramètres de l'onduleur doit être testée ensuite
  prudemment, un paramètre non critique à la fois.
- Ne pas débrancher l'ESP S3 ni interrompre son alimentation pendant l'OTA.
- Si le firmware déjà installé n'est pas exactement le 0.3.2 observé
  lors du pré-vol, le bouton refuse le test.
