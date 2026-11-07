# APK Android : signature permanente et mise à jour sans perte des réglages

## Pourquoi l'APK peut « oublier » le code ESP32

Les APK **debug** construites sur GitHub Actions utilisent la clé debug du runner. Les runners sont éphémères : d'une exécution à l'autre, la signature peut changer. Android refuse alors l'installation par-dessus l'ancienne APK, obligeant à la désinstaller. Cette désinstallation supprime les préférences et les clés Android Keystore de l'application. L'ESP32, son Wi-Fi et sa liste de MAC JK ne sont **pas** effacés.

**Ne jamais proposer de changer ou désactiver les règles de sécurité Android pour contourner les signatures.**

## Générer la clé privée une seule fois sur le PC du propriétaire

Sur un PC disposant du JDK (Android Studio installe un JDK) :

```powershell
keytool -genkeypair -v -keystore smart-energy-android-release.jks -alias smartenergy -keyalg RSA -keysize 3072 -validity 10000
```

Conserver **le fichier .jks et les mots de passe hors du dépôt**, dans une sauvegarde privée et durable. Si la clé est perdue, on ne peut normalement plus mettre à jour les installations existantes avec la même identité Android.

Sur Windows PowerShell, depuis le dossier où se trouve le keystore :

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path ".\smart-energy-android-release.jks"))) | Set-Clipboard
```

Ouvrir **le dépôt privé → Settings → Secrets and variables → Actions → New repository secret**, puis créer les quatre secrets :

| Secret GitHub Actions | Valeur |
| --- | --- |
| `SMART_ENERGY_ANDROID_KEYSTORE_BASE64` | Base64 du fichier JKS (depuis le presse-papiers) |
| `SMART_ENERGY_ANDROID_STORE_PASSWORD` | Mot de passe du keystore |
| `SMART_ENERGY_ANDROID_KEY_ALIAS` | `smartenergy` si alias choisi dans la commande |
| `SMART_ENERGY_ANDROID_KEY_PASSWORD` | Mot de passe de la clé dans le keystore |

**Ne jamais coller la clé privée, les mots de passe ou la Base64 dans un chat, une issue GitHub, des captures ou un commit.** Le fichier .jks doit rester une sauvegarde privée ; les secrets GitHub ne sont pas des sauvegardes.

Lorsque les quatre secrets sont présents, GitHub Actions publie l'artifact **`smart-energy-android-signed`** contenant `app-release.apk`, contrôlée par `apksigner verify`. Le job produit toujours un artifact **`smart-energy-android-debug`** pour les tests internes, mais **ne plus installer celui-ci** une fois passé à la version signée stable.

## Première transition depuis l'ancienne APK de développement

La clé stable ne correspond pas à la signature debug existante : **la toute première transition peut nécessiter de désinstaller l'ancienne APK**, donc de refaire l'association une fois. C'est normal et ce sera la dernière fois si l'on conserve la clé stable et le même `applicationId`.

Pour récupérer le code sans effacer l'ESP32 :

1. Brancher l'ESP32 à l'ordinateur et ouvrir le moniteur série à **115200 bauds**.
2. Laisser l'ESP32 démarrer. Puis tenir **BOOT pendant 4 secondes** pour activer le réseau temporaire SmartJK (ne pas presser pendant le démarrage).
3. Relever **privément** le code unique affiché sur le moniteur série, puis redémarrer simplement l'ESP32 pour qu'il retrouve le Wi-Fi de la box. **Ne pas effacer sa mémoire.**
4. Dans l'APK **v0.1.7 ou ultérieure**, saisir l'adresse locale de l'ESP32 dans la section 1 et le code original dans la section 2, puis appuyer sur « Se connecter à ma passerelle » dans la section 1. L'APK valide le code auprès de l'ESP32, puis le mémorise de nouveau dans Android Keystore : pas besoin de rejoindre le point d'accès temporaire.
5. **Ne pas reconfigurer le Wi-Fi ni la MAC du JK** : elles sont toujours enregistrées dans la NVS de l'ESP32.

Les mises à jour suivantes : augmenter `versionCode`, compiler avec les quatre mêmes secrets, installer l'artifact signé par-dessus l'APK signée précédente. Android conservera normalement les données de l'application, dont le code chiffré dans Android Keystore.

## Limites

- Cette procédure **ne récupère pas** un code déjà supprimé du stockage Android. Le code est encore présent sur l'ESP32.
- Une réinitialisation de l'application par l'utilisateur ou le système peut également effacer les données.
- La première version Bluetooth JK est expérimentale, en lecture seule.
