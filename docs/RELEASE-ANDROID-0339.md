# Smart Energy Android 0.3.39-dev / Voltronic S3 0.3.4 — reprise du 14 novembre 2026

## Source de vérité
- Dépôt : tapion69/smart-energy-android, branche **dev**.
- Android : **0.3.39-dev**, versionCode **53**, identifiant de livraison officiel
  `fr.smartenergy.android`. Les builds de test utilisent toujours le suffixe
  `.vpntrial333` ; ne jamais les proposer pour remplacer une application
  portant un autre certificat.
- Firmware Voltronic : **0.3.4-dev-ota-parameter-edit-test**.
- Aucun changement des firmwares JK, VPN, du dashboard JK, des identifiants
  BMS, des réglages BLE, ou du dashboard Moderne/Sunsynk validé.

## Android : mise à jour depuis l'application
Dans « Mises à jour » :
1. vérifier les manifestes officiels GitHub `main/updates/*.json` ;
2. proposer une APK seulement si versionCode strictement supérieur ;
3. télécharger l'APK explicitement demandée, vérifier son SHA-256 ;
4. vérifier l'identifiant de paquet et la signature déjà installée ;
5. ouvrir l'installateur système Android pour une installation par-dessus
   l'application existante. Ne pas demander de désinstaller.
6. l'autorisation Android « Installer des applis inconnues » est demandée
   uniquement lors d'une action volontaire. Après autorisation, l'onglet
   permet de finaliser l'installation de l'APK déjà vérifiée.

**Les préférences et les secrets Android Keystore ne sont conservés que si
le certificat ET l'identifiant de paquet restent les mêmes.**

### Précondition indispensable pour une APK installable
Exécuter localement le script :
`tools/android-migration/setup-permanent-signing.ps1`.
Il configure cinq secrets GitHub Actions, dont l'empreinte
`SMART_ENERGY_ANDROID_CERT_SHA256`. Ne jamais publier les mots de passe ni le
fichier JKS. Le workflow refuse d'émettre une APK distribuable si ces secrets
sont absents, et ne publie pas d'APK debug à clé éphémère.

Avec une ancienne APK de test, commencer par
`tools/android-migration/legacy_backup.py`, puis importer la configuration
non secrète via Réglages → Sauvegarde/migration. Les codes ESP32 et le token
VPN doivent être ressaisis une fois après une migration entre paquets Android.

### Publication (à faire une fois les tests et signature validés)
Publier `app-release.apk` via une Release GitHub en réutilisant la clé
permanente, avec SHA-256 et versionCode 53 (ou supérieur). Le manifeste
`updates/android.json` ne doit devenir `available=true` que lorsque la
Release signée correspondante existe et a été vérifiée.

## Voltronic : paramètres modifiables (expérimental)
La version 0.3.4 S3 ajoute une route locale authentifiée
`POST /api/v1/parameters/change`. Elle n'accepte **pas** de commande brute ;
uniquement un nom de paramètre parmi une liste de 14 clés natives connues,
une ancienne valeur et la nouvelle valeur explicitement confirmée.

Les champs QPIRI/QDOP disponibles sont affichés par onduleur INV1/INV2/INV3.
Le S3 exige un modèle et firmware identifiés et exécute sur son unique
tâche série :
- lecture QPIRI/QDOP fraîche ;
- vérification de l'ancienne valeur ;
- validation des limites et du pas (décharge par pas de 10 A) ;
- une seule commande SET de la liste blanche ;
- lecture QPIRI/QDOP **immédiatement** après ACK ;
- succès déclaré uniquement si la nouvelle valeur est effectivement relue.

En cas de NAK, CRC incorrect, délai dépassé ou différence de valeurs :
résultat **non confirmé** et **aucun réessai automatique**.
Pas de commandes de modification via le VPN. Le firmware JK et le VPN
restent séparés.

### Validation avant toute installation réelle
- Tests natifs et compilation ESP-IDF CI obligatoires ; une compilation
  réussie n'est pas une validation des variantes Voltronic sur matériel.
- Vérifier la liaison RS232 sans coupure, et commencer par une modification
  non critique sur un appareil d'essai, en contrôlant les données QPIRI/QDOP.
- Ne jamais publier ce firmware en OTA pour les utilisateurs tant que
  l'essai réel et les paramètres/modèles associés ne sont pas confirmés.
- Les S3 actuellement en partition unique exigent d'abord une migration
  USB conservant NVS ; aucun OTA sûr n'est possible tant que
  `ota_supported=false`.
- Ne jamais utiliser `erase_flash` ni une image FACTORY fusionnée pour cette
  migration. Voir `docs/VOLTRONIC_OTA_MIGRATION.md`.

**État après signature (14 novembre 2026) :** Android `0.3.39-dev` compilé
et vérifié avec le certificat permanent, GitHub Actions
[run 43587552565](https://github.com/tapion69/smart-energy-android/actions/runs/43587552565),
artefact `smart-energy-android-signed-upgradeable`. Pas encore de GitHub Release
ni de mise à jour `updates/android.json` ; flux d'actualisation intégré à
activer après validation de l'installation initiale. Firmware Voltronic non
flashé, aucun test sur matériel réel attesté. L'ancien APK debug est conservé
à côté jusqu'à migration complète des paramètres.
