# Smart JK ESP32 — firmware de test (0.1.3-dev)

**Fonctions actuellement codées** : diagnostic Wi-Fi et retour automatique au mode configuration après 30 secondes d'échec, point d'accès Wi-Fi de configuration, secret unique par appareil, lecture de l'identité, scan BLE, sélection et enregistrement persistant des MAC JK, configuration du Wi-Fi, retour au réseau local et état « pas encore de télémétrie ».

**Pas encore codés** : connexion GATT aux JK, décodage des données V19, appairage définitif de production, OTA, accès Internet. Ne pas considérer un appareil scanné comme un JK sans vérification.

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
- Configuration accessible seulement quand le mode setup est actif, et avec l'en-tête `X-Setup-Code`.
- Le code est affiché sur le port série **uniquement sur cette version de développement** ; une solution de provisionnement par QR/code livré avec la carte sera nécessaire pour le grand public.
- La configuration Wi-Fi est stockée dans les préférences de l'ESP32. **Pas de chiffrement de flash NVS activé à ce stade** : prototype de test local seulement.
- Accès Internet, modification des paramètres JK et télémétrie réelle **absents**.
- Aucune limite de performance prouvée pour 8 BMS : cette version ne sauvegarde que la liste, elle ne les interroge pas.

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
