# Smart Energy Android — prototype Android v0.2.0-dev

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

- La lecture JK V19 vient d'être ajoutée **à titre expérimental** dans le firmware 0.1.6-dev. Les mesures ne sont affichées que si leur trame a été validée ; le matériel réel n'a pas encore été testé.
- Le secret de l'ESP32 neuf est fourni par le moniteur série USB pendant la phase de test. Le provisionnement QR/étiquette sera conçu avant publication publique.
- Transport HTTP local provisoire sur Wi-Fi domestique ou réseau temporaire, **non chiffré** : pas d'accès distant, pas d'OTA ni de mise à jour APK active.
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

## 0.1.5-dev — Correctif Android EPERM (sans changement ESP32)

- Sur le **Wi-Fi maison**, l'APK utilise désormais le routage réseau Android normal via `URL.openConnection()` au lieu de sélectionner puis forcer arbitrairement un objet `Network`. Sous certains VPN et certaines politiques Android, `Network.openConnection()` échouait avec `Binding socket to network ... EPERM`.
- Le **réseau de configuration ESP32** (`192.168.4.1`) continue de nécessiter un `Network.openConnection()` dédié car il provient de `WifiNetworkSpecifier`. Si un VPN ou un mode « bloquer les connexions sans VPN » refuse le réseau local, l'APK affiche un diagnostic explicite ; elle ne contourne pas la politique Android du téléphone.
- Aucun firmware à reflasher : l'ESP32 0.1.4-dev ou 0.1.3-dev reste compatible pour la gestion LAN correspondante. Scan des réseaux à distance : nécessite firmware 0.1.4-dev.
- Test recommandé : connecter le téléphone au Wi-Fi habituel, **désactiver temporairement le VPN** et son option de blocage si nécessaire, puis ouvrir l'APK. Le mode « VPN actif » ne doit pas faire l'objet d'une tentative de contournement silencieuse.

## 0.1.6-dev — Lecture des mesures JK en temps réel (test)

- Le tableau de bord demande `GET /api/v1/telemetry` toutes les **8 secondes** quand la connexion LAN ou setup est active.
- Si une trame JK02 valide est reçue : affichage SOC, tension batterie, courant signé, puissance, températures disponibles, min./max. cellule, écart en mV, cycles et **tensions individuelles**.
- Si l'acquisition BLE échoue : affichage de l'état (`JK hors de portée`, `connexion BLE échouée`, `trames non reconnues`, `mesures trop anciennes`), **jamais de valeurs inventées**.
- `JK02_24S` et `JK02_32S` sont des profils de structure de trame, pas le nombre de cellules. Le décodage est provisoire et doit être comparé à l'outil JK constructeur.
- Les résistances par cellule **ne sont pas encore transmises**.
- Le firmware ne modifie aucun paramètre de batterie : lecture seule.
- Le moteur BLE **n'a pas encore été testé sur la batterie physique**. Les tests automatiques portent uniquement sur des trames synthétiques, leur checksum et les fragments.

**Installation :** firmware ESP32 0.1.6-dev et APK Android 0.1.6-dev nécessaires pour obtenir les premières mesures. L'ESP32 relit la liste de MAC mémorisées ; il n'est normalement pas nécessaire de sélectionner à nouveau le JK **si la mise à jour du firmware préserve NVS**. Lors des premiers tests, éviter une seconde connexion BLE simultanée au même JK.

## 0.1.8-dev — Code ESP32 perdu : bouton BOOT, sans reconfigurer le Wi-Fi

Dans la section 1, saisir l'adresse locale de l'ESP32 et appuyer sur **« Code perdu ? Réassocier avec BOOT »**. Sur l'ESP32 équipé de la v0.1.8, maintenir BOOT **4 secondes, puis relâcher**. Valider dans l'APK dans les **45 secondes**. L'APK crée un nouveau code de 20 caractères aléatoires, le fait enregistrer par l'ESP32, vérifie l'accès à sa liste de BMS puis conserve le code avec Android Keystore. Le Wi-Fi, les batteries JK et leurs adresses MAC **restent enregistrés**.

Si l'ESP32 utilise encore la v0.1.6, il n'existe pas de récupération distante sans authentification. Rechercher d'abord le code dans les anciens journaux USB ou flasher une fois la v0.1.8. **Ne jamais ajouter de route HTTP exposant l'ancien code.** La fonction expérimentale s'utilise sur un réseau local privé de confiance, pas à distance via un port ouvert.

La signature Android permanente est traitée séparément dans `docs/APK-SIGNATURE-PERMANENTE.md` et nécessite les secrets GitHub avant de produire un artifact signé. Les APK debug peuvent toujours nécessiter une réinstallation.

## v0.1.9-dev — tableau de bord Android finalisé pour le test réel

- L'écran principal affiche désormais les mesures, **avant les formulaires de configuration**.
- Cartes Android natives : jauge SOC dessinée, tension/courant/puissance, 2 colonnes de cellules avec barres, températures T1/T2/MOS et cycles.
- **Le format JK02 24S/32S n'est plus affiché** ; il reste une donnée technique interne.
- Actualisation automatique **toutes les 2,5 secondes**, à partir d'un cache de télémétrie ESP32 (aucune nouvelle connexion BLE déclenchée par l'APK).
- Les mêmes vues sont mises à jour sans être recréées à chaque appel, pour limiter scintillement et coût CPU.
- Si les valeurs ne sont plus fraîches, les cartes sont masquées et un état de diagnostic explicite apparaît, sans valeurs fictives.
- « Configuration et association » ouvre/referme les sections Wi-Fi, JK, code et récupération BOOT.
- La connexion BLE persistante et les barres de cellules sont **à valider sur batterie physique**. Les temps de mise à jour de 2,5 s concernent **le rafraîchissement de l'APK** ; la fréquence des trames dépend du JK.
- Les APK debug GitHub Actions ont toujours une signature éphémère : une réinstallation peut perdre l'association Android. La signature permanente est préparée, mais les secrets GitHub doivent encore être configurés pour publier des APK signées.

## 0.2.0-dev — Paramètres JK en lecture seule

- Trois onglets Android : **Batterie** (jauge et cellules), **Détails** (mesures étendues, jusqu'à 6 températures si disponibles, résistances par cellule, alarmes, MOS, équilibrage, capacités, SOH, diagnostic BLE), **Paramètres** (47 seuils/commutateurs JK et informations d'identité).
- L'ESP32 décode les trames 0x02 de mesures, 0x01 de réglages et 0x03 d'information (checksum confirmé) ; aucun champ de mots de passe BMS n'est exposé.
- Les valeurs absentes/invalides sont affichées indisponibles, pas à 0 ; les paramètres non encore reçus affichent leur état d'attente.
- L'APK interroge la télémétrie toutes les 2,5 s et le cache des paramètres seulement à la consultation, puis toutes les 30 s au maximum. Cela ne déclenche pas de reconnexion BLE.
- Lecture strictement seule : **aucun paramètre ne peut être modifié depuis cette version**. L'affichage et le décodeur restent expérimentaux, à comparer à l'application JK constructeur avant toute utilisation plus poussée.
