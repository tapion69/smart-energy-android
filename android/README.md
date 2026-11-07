# Smart Energy Android — première version de développement

Un projet Android Studio **réel**, en Kotlin natif, ciblant Android 10+.

## Fonctionnalités codées (0.1.0-dev)

- Formulaire pour SSID `SmartJK-Setup-XXXXXX` et code unique de l'ESP32.
- Demande de connexion au Wi-Fi de l'ESP32 via la boîte de dialogue système Android (WifiNetworkSpecifier). Le téléphone ne doit pas être configuré manuellement dans les réglages.
- Lecture de l'identité du firmware à `192.168.4.1`.
- Scan BLE effectué **par l'ESP32** et affichage MAC/nom/RSSI.
- Sélection multiple de BMS, stockage dans l'ESP32, sans YAML.
- Saisie et transfert Wi-Fi maison, puis redémarrage de l'ESP32.
- Aucune fausse mesure ou commande critique.

## Limites

- Cette version ne lit **pas encore** les valeurs JK V19. Le firmware renvoie `available:false` pour la télémétrie.
- Le secret de l'ESP32 neuf est fourni par le moniteur série USB pendant la phase de test. Le provisionnement QR/étiquette sera conçu avant publication publique.
- Transport HTTP local provisoire limité au Wi-Fi de configuration : pas d'accès distant, pas d'OTA ni de mise à jour APK active.
- La sélection de BMS s'applique à un ESP32 à la fois ; capacité BLE réelle encore à tester.
- Ni compilation Android, ni flash ESP32, ni test matériel effectués automatiquement lors de ce commit.

## Ouvrir le projet

1. Dans Android Studio : **File > Open** puis choisir le dossier `android` (celui qui contient `settings.gradle.kts`).
2. Attendre **Gradle Sync** (JDK 17, Android SDK Platform 35).
3. **Build > Build APK(s)** puis installer sur le téléphone Android.
4. Installer sur un ESP32 *supplémentaire* le firmware du dossier `firmware/jk-esp32`, depuis VS Code + PlatformIO.
5. Lire SSID / code de la passerelle dans le moniteur série à 115200.
6. Démarrer l'APK, accorder les autorisations demandées et suivre les trois étapes.

**Important :** l'ESP32 actuellement utilisé en Bluetooth Proxy HA n'est pas compatible avec cette APK et ne doit pas être reflashé pour ce premier essai.

## Suite

Après validation de ce parcours sur matériel réel, intégrer le décodeur JK V19, la reconnexion multi-BMS, les mises à jour OTA authentifiées, et la vérification des Releases APK.
