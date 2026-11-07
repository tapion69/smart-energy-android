# Smart JK ESP32 — firmware de test (0.1.6-dev)

**Fonctions actuellement codées** : diagnostic Wi-Fi et retour automatique au mode configuration après 30 secondes d'échec, point d'accès Wi-Fi de configuration, secret unique par appareil, lecture de l'identité, scan BLE, sélection et enregistrement persistant des MAC JK, configuration du Wi-Fi, retour au réseau local, et **première acquisition BLE expérimentale en lecture seule**.

**Pas encore validés sur matériel réel** : acquisition BLE et décodage des données JK V19 sur ESP32 ; **non développés** : appairage définitif de production, OTA, accès Internet. Ne pas considérer un appareil scanné comme un JK sans vérification.

## Premier flash

1. Utiliser **un ESP32 DevKit classique de test** (`esp32dev`), pas le Bluetooth Proxy Home Assistant en service.
2. Installer l'extension **PlatformIO** dans VS Code ; ouvrir **ce dossier** (celui contenant `platformio.ini`).
3. Brancher le nouvel ESP32 en USB puis **Build** et **Upload**.
4. Ouvrir le moniteur série à 115200 bauds. L'appareil imprime son SSID `SmartJK-Setup-XXXXXX` et un **code de 12 caractères unique à cette carte**.
5. Ouvrir l'APK Smart Energy : saisir ce SSID et son code, puis appuyer sur « Connecter l'ESP32 ».
6. Utiliser « Rechercher les batteries » et sélectionner la MAC d'un JK identifié.
7. Utiliser « Sauvegarder la sélection », puis saisir SSID + mot de passe de la box et « Configurer le Wi-Fi ».
8. Après redémarrage de l'ESP32, reconnecter le téléphone à son Wi-Fi habituel. L'ESP32 répond à `http://smartjk-XXXXXX.local/api/v1/info` (ou à l'IP attribuée par le routeur).

## Mode configuration après un premier appairage

Sur une carte équipée du bouton BOOT (GPIO0), après le démarrage, le maintenir **4 secondes** pour réactiver le point d'accès. Ne pas maintenir BOOT pendant la mise sous tension : GPIO0 est une broche de démarrage.

## Sécurité / limites du prototype

- Mot de passe propre à chaque ESP32, **pas de mot de passe universel**. Il ne doit jamais être publié.
- Configuration initiale limitée au mode setup ; gestion depuis le LAN autorisée avec le code unique transmis dans l'en-tête `X-Setup-Code`.
- Le code est affiché sur le port série **uniquement sur cette version de développement** ; une solution de provisionnement par QR/code livré avec la carte sera nécessaire pour le grand public.
- La configuration Wi-Fi est stockée dans les préférences de l'ESP32. **Pas de chiffrement de flash NVS activé à ce stade** : prototype de test local seulement.
- Accès Internet et modification des paramètres JK **absents**. Télémétrie JK02 **expérimentale**, à valider sur matériel.
- Aucune limite de performance prouvée pour 8 BMS : interrogation **séquentielle** des BMS mémorisés, pas encore validée en situation réelle.

## API v0.1.0-dev

| Route | Méthode | Mode | Fonction |
| --- | --- | --- | --- |
| `/api/v1/info` | GET | LAN / Setup | Identité et version |
| `/api/v1/bms/scan` | GET | Setup authentifié | Scan BLE 4,5 s |
| `/api/v1/bms` | GET / PUT | Setup authentifié | Lister/enregistrer MAC |
| `/api/v1/wifi` | POST | Setup authentifié | Enregistrer Wi-Fi et redémarrer |
| `/api/v1/telemetry` | GET | LAN / Setup | `available:false` jusqu'au décodeur JK |

Le scan BLE s'appuie sur l'API NimBLE-Arduino 2.x ; il peut trouver d'autres périphériques à proximité.

## Dépannage Wi-Fi avec la version 0.1.1-dev

La configuration Wi-Fi maison est enregistrée en NVS. Après le redémarrage :

- La série (115200) affiche `[WiFi] Trying saved Wi-Fi (2.4 GHz required)...`.
- Si connexion réussie : `[WiFi] Connected: ...` puis `Smart JK local: ...`.
- Si impossible après 30 secondes : `[WiFi] Failed ... status=... reason=...` et **le réseau SmartJK-Setup-XXXXXX réapparaît automatiquement**.
- Pour corriger immédiatement les paramètres sur l'ancienne version 0.1.0, maintenir **BOOT 4 s après démarrage** (pas pendant l'allumage), puis reconnecter l'APK.
- Les paramètres sauvegardés n'ont pas de SSID/mot de passe imprimés dans les logs ; ne pas poster le code de 12 caractères.

Causes possibles : réseau limité au 5 GHz, mot de passe mal saisi, SSID erroné, WPA3-only sur routeur, filtrage MAC ou signal trop faible. Vérifier la connexion sur 2,4 GHz avec WPA2/WPA2+WPA3 et le mot de passe exact avant de conclure à un bug.

### Attention en reflashant

Le binaire `*-factory.bin` est une image **complète** destinée à une installation USB depuis l'adresse `0x0000` : elle peut **effacer les données NVS**, notamment le Wi-Fi et le code d'appairage. Le fichier `firmware.bin` (app-only) se flashe à l'adresse `0x10000`, avec un outil supportant explicitement cette adresse, et n'est **pas** une image factory. Ce prototype ne dispose pas encore d'OTA.

## Changement Wi-Fi depuis le LAN (0.1.3-dev)

La route `POST /api/v1/wifi` fonctionne désormais depuis le mode setup **ou** le réseau domestique avec l'en-tête d'authentification individuel. Un succès HTTP confirme l'enregistrement dans les préférences, suivi d'un redémarrage ; la connexion à la box **reste à confirmer dans les logs**. L'ESP32 reviendra automatiquement en mode setup en cas d'échec après 30 secondes.

⚠️ Ce transport en clair est réservé au **réseau domestique de confiance pendant les essais**. Il devra être remplacé par un transport chiffré avant toute distribution.

## 0.1.6-dev — Lecture Bluetooth réelle, expérimentale

La liste de MAC JK est relue depuis les préférences de l'ESP32 au démarrage. Un processus autonome tente de retrouver chaque JK en BLE, se connecte au service `FFE0` / caractéristique `FFE1`, s'abonne aux notifications, puis envoie seulement les **requêtes de lecture** `0x97` et `0x96` (20 octets, CRC somme 8 bits). Il n'envoie **aucune commande de modification** des réglages du BMS.

Les fragments BLE sont reconstitués en trames de 300 octets, contrôlées par en-tête `55 AA EB 90` et checksum. Un décodeur expérimental `JK02_24S` / `JK02_32S` vérifie les cellules, leur somme, la tension pack, le SOC, le courant et les températures avant publication. Les profils sont des **layouts de trames** et **non** des nombres de cellules. Les températures invalides ne sont pas présentées comme des mesures ; **elles ne doivent jamais servir aux protections de batterie**.

Le point API `GET /api/v1/telemetry` exige `X-Setup-Code` et renvoie `devices` avec `available=true` uniquement pour les mesures validées de moins de 90 secondes. Sinon, un état de diagnostic (`ble_not_seen`, `no_valid_jk_frame`, `unknown_jk_layout`, etc.) est fourni. Les mesures sont publiées sur le Wi-Fi local, **sans Home Assistant**.

L'intégration Bluetooth et le décodeur sont **en cours de validation sur les vrais JK**. Les tests automatisés utilisent des trames synthétiques, non des captures de ton matériel. Les valeurs retournées ne sont pas adaptées aux commandes de charge/décharge, alarmes critiques ou protections. Les multiples JK sont parcourus l'un après l'autre ; la stabilité et la disponibilité restent à mesurer.

Si le JK est simultanément connecté en Bluetooth à un téléphone, Home Assistant ou un autre proxy, le nouvel ESP32 peut ne pas arriver à le joindre. Pendant les premiers essais, utiliser un JK de test ou interrompre temporairement **la connexion BLE concurrente de ce JK**, sans reflasher ni changer l'ESP32 Home Assistant existant.

**Attention flash :** une image `factory.bin` complète est destinée à l'adresse `0x0000` et peut réinitialiser NVS. Un fichier `app-only` peut mettre à jour le programme à l'adresse `0x10000` seulement avec un outil de flash explicitement compatible avec cette adresse ; ce n'est **pas** une OTA. Si le code de première association ou la liste de JK est effacée, refaire l'association une fois dans l'APK.
