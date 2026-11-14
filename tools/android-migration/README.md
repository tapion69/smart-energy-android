# Smart Energy Android — fin des conflits de signature APK

## Pourquoi Android refuse une mise à jour

Les anciennes APK de test sur GitHub ont été signées avec une clé debug
éphémère de chaque runner GitHub Actions. Android exige **le même package et
le même certificat** pour mettre à jour une installation existante, même
quand le nouveau versionCode est supérieur. Une clé privée perdue ne peut
pas être reconstruite depuis une APK.

**Ne jamais désinstaller l'ancienne APK avant d'avoir vérifié une sauvegarde.**
Ne pas proposer l'option désinstaller comme solution par défaut.

## Étape unique : créer la signature PERMANENTE (sur PC Windows)

Prérequis : GitHub CLI (`gh auth login`, droits d'administration sur
`tapion69/smart-energy-android`) et Java JDK/Android Studio.

Exécuter localement :
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\android-migration\setup-permanent-signing.ps1
```

Le script crée un nouveau certificat de signature RSA 3072 bits, une
sauvegarde de clé et de mot de passe dans
`Documents\SmartEnergy-SIGNING-BACKUP`, et configure cinq secrets GitHub :
`SMART_ENERGY_ANDROID_KEYSTORE_BASE64`,
`SMART_ENERGY_ANDROID_STORE_PASSWORD`,
`SMART_ENERGY_ANDROID_KEY_ALIAS`,
`SMART_ENERGY_ANDROID_KEY_PASSWORD` et
`SMART_ENERGY_ANDROID_CERT_SHA256`.

Conserver la clé et le fichier de récupération ENSEMBLE dans une sauvegarde
privée hors ligne. **Ne jamais les publier sur GitHub ou les envoyer dans un
chat.** GitHub Actions utilise cette même identité pour toutes les
futures versions officielles.

## Récupérer la configuration depuis l'ancienne APK de test

Cette étape est nécessaire une seule fois si l'ancien APK était signé avec
une clé éphémère. Elle n'est pas nécessaire pour les futures mises à jour
officielles avec la même signature.

1. Laisser l'ancienne APK de test installée, active et non effacée.
2. Brancher le téléphone au PC Windows avec débogage USB autorisé.
3. Depuis un dossier contenant `legacy_backup.py`, lancer
   `python legacy_backup.py`. Installer Python 3 au besoin.
4. Le script trouve `adb.exe` dans Android Studio et lit *uniquement*
   les préférences non secrètes de l'APK debug
   `fr.smartenergy.android.vpntrial333` par `adb run-as`.
   Avec une autre version debug, utiliser
   `--package fr.smartenergy.android.vpntrial332`, par exemple.
5. Conserver le fichier
   `SmartEnergy_Ancienne_Configuration.json` sur le PC, puis le transférer
   localement sur le téléphone (USB, pas de publication Internet).
6. Déclencher le workflow **Build development prototypes**, télécharger
   **uniquement** l'artefact `smart-energy-android-signed-upgradeable`.
   Si l'artefact n'apparaît pas, les secrets ne sont pas correctement
   configurés : ne pas installer un nouvel APK debug temporaire.
7. Installer la nouvelle APK officielle à côté de l'ancienne APK de test.
   Dans la nouvelle application, aller à **Réglages → Sauvegarde / migration →
   Importer une configuration depuis l'ancienne APK**.
8. Vérifier l'aperçu puis accepter la restauration. Fermer et rouvrir
   l'application. Re-renseigner une fois les codes des ESP32 JK et Voltronic
   et le token VPN. Les codes sont protégés par Android Keystore, impossible
   de copier les anciens ciphertexts entre deux UID d'application.
9. Vérifier les 4 BMS et les onduleurs. Ne supprimer l'ancienne APK de test
   **qu'après validation** et sauvegarde.

`legacy_backup.py` refuse l'export des champs
`pairing_code_cipher`, `remote_gateway_token_cipher` et autres secrets ;
le fichier ne contient pas de token, de mot de passe ou de codes ESP32.
**Il contient néanmoins des adresses et noms privés : ne pas le partager.**

Si `adb run-as` échoue parce que l'ancien package n'est pas debuggable,
le logiciel ne peut pas extraire ses préférences de façon sécurisée sans
son propre mécanisme d'export. Garder l'application intacte et effectuer une
migration manuelle.

## Futures mises à jour — sans perte de configuration

Le workflow ne publie plus d'APK à clé debug temporaire. Il refuse une
version officielle si la signature ne correspond pas au certificat
permanent configuré. Un téléchargement de release GitHub doit également
conserver le même `applicationId` et avoir un `versionCode` supérieur.
La nouvelle APK s'installe alors *par-dessus* la version officielle, sans
effacer les SharedPreferences ni l'Android Keystore.

L'export JSON non secret est une sauvegarde complémentaire ; il n'est pas
nécessaire à chaque mise à jour correctement signée.
