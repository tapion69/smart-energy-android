# Smart Energy Android — prototype Android v0.1.3-dev

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

## Correction v0.1.3-dev — Wi-Fi

- Le bouton **Enregistrer / modifier le Wi-Fi** est désormais toujours cliquable. Il affiche une explication sous le bouton lorsque la passerelle n'est pas encore associée ou connectée.
- Si le Wi-Fi actuel fonctionne déjà, **inutile de le réenregistrer** : passer directement au scan BLE.
- Pour **changer** de Wi-Fi depuis le réseau domestique, le firmware JK **0.1.3-dev** et la connexion authentifiée à la passerelle sont nécessaires. L'APK vérifie la version avant d'envoyer des identifiants.
- La configuration initiale depuis le réseau temporaire fonctionne aussi.
- Après confirmation de l'ESP32, l'APK indique clairement le redémarrage. Si l'enregistrement échoue, l'APK affiche le message d'erreur dans la même section.

**Limites :** le lien local est en HTTP non chiffré sur un réseau privé de test. Ne pas publier comme produit commercial. Les APK debug GitHub Actions peuvent être signées avec des clés différentes : une désinstallation peut être nécessaire et fait perdre l'association mémorisée sur le téléphone ; il faut une clé de signature Android stable pour éviter ce problème.

## 0.1.4-dev — Configuration assistée par l'application

- Recherche automatique **des ESP32 en mode appairage** à partir des points d'accès Wi-Fi proches sur le téléphone ; peut nécessiter l'autorisation de localisation et que la localisation Android soit activée. Le mode d'association utilise encore le code unique **une seule fois**.
- Recherche **par l'ESP32** des réseaux Wi-Fi **2,4 GHz uniquement** et sélection dans une liste. L'utilisateur n'entre que le mot de passe Wi-Fi du réseau choisi.
- Le même bouton de découverte mDNS continue à retrouver les passerelles déjà associées sur le LAN.
- Trafic HTTP dirigé explicitement vers le réseau Wi-Fi approprié ; erreurs VPN et réseau temporaire interrompu mieux expliquées.
- Changement du code ESP32 depuis l'APK (8–32 caractères, conservé dans Keystore). Un code universel `1234` n'est **pas** employé : WPA2 requiert au moins huit caractères et un code par défaut public permettrait un accès non autorisé.
- Transport local HTTP non chiffré **réservé aux tests**, pas de sortie WAN, pas d'OTA, pas encore de télémétrie JK.

**Séquence** : installer firmware ESP32 0.1.4-dev et APK 0.1.4-dev → trouver la passerelle sur le LAN ou en mode BOOT → associer une seule fois → « Rechercher les réseaux Wi-Fi disponibles » → sélectionner le SSID 2,4 GHz → saisir son mot de passe → « Enregistrer ». Quand la passerelle est déjà connectée à la box et que l'on ne veut pas changer de réseau, passer directement au scan BLE.
