# Smart Energy JK — récupération Wi-Fi et association stable (expérimental)

## Incident confirmé par audit source (2026-11-08)

Le firmware 0.2.4-dev accepte un code personnel de 8 à 32 caractères via `POST /api/v1/access`, mais **au démarrage** régénère automatiquement tout code de moins de 12 caractères (`if (setupCode.length() < 12)`). Avec un code 8–11 caractères, un redémarrage peut donc écraser le code choisi par un nouveau code aléatoire, qui devient aussi le mot de passe du point d'accès WPA2 `SmartJK-Setup-XXXXXX`. L'APK ignore ce changement. Ne jamais réinjecter ce code dans Git, les logs publics ou une capture.

**Deuxième défaut** : après une perte du Wi-Fi domestique de 30 s, `startSetup()` passe en mode point d'accès. La boucle Bluetooth de `jk_reader.cpp` est suspendue lorsque `setupMode=true`. Le BMS peut ne plus biper et n'émet plus de mesures dans l'application ; cela ne prouve pas une panne matérielle. Le firmware 0.2.4 restait indéfiniment dans ce mode sans retenter le SSID domestique.

## Correction minimale 0.2.5-dev (à valider)

- Une **unique règle** de validation 8–32 caractères ASCII sans espaces pour le code utilisateur, côté API et restauration NVS au démarrage. Ne jamais régénérer un code valable juste parce qu'il a moins de 12 caractères.
- La perte Wi-Fi déclenche toujours un point d'accès **WPA2 privé**, mais uniquement comme solution de secours. Après 120 secondes sans client connecté au point d'accès, le firmware retente le SSID précédemment enregistré, sans effacer le Wi-Fi, les MAC JK ni le code.
- Le mode configuration volontaire par BOOT (10 s, après démarrage) reste actif sans tentative automatique; en configuration automatique, BOOT 10 s peut choisir de rester en mode manuel. BOOT 4 s du mode LAN conserve son fonctionnement existant.
- `GET /api/v1/info` ajoute `wifi_auto_recovery` pour distinguer le secours automatique du mode volontaire.
- Aucun changement du BLE, du décodage des trames JK, des états BMS, de l'APK Android et du deuxième ESP32 Tailscale. Binaire APP-ONLY uniquement à l'adresse 0x10000, sans Erase Flash, **après** compilation et tests satisfaisants.
- Tests C++ du code 8–32 caractères ajoutés aux actions CI. Le test 0.2.5 ne prétend pas démontrer la reprise Wi-Fi en conditions réelles; valider successivement coupure 2,4 GHz et reprise du routeur.

**Important :** la correction ne récupère pas l'ancien code s'il a **déjà** été écrasé par un redémarrage du firmware 0.2.4. Les NVS sont persistantes. Un flash APP-ONLY 0.2.5 ne retrouve pas un secret antérieur qui a disparu.

## Récupérer l'appareil actuellement verrouillé sans effacer NVS

1. Ne pas utiliser Erase Flash ou image factory, ne pas maintenir BOOT à l'allumage (GPIO0 mode flash) et ne pas lancer de récupération de code à répétition.
2. Si le téléphone ne peut pas joindre le point d'accès `SmartJK-Setup-XXXXXX` malgré le dernier code, utiliser une **liaison série USB locale** sur le lecteur JK : moniteur série 115200 baud depuis PC ou téléphone USB-OTG avec terminal série compatible USB-UART. Le firmware actuel affiche lors de l'entrée en mode configuration la ligne `Wi-Fi password AND setup code:` : c'est la valeur **actuellement active**. Cette valeur est privée, ne pas la publier.
3. Depuis le téléphone, rejoindre le point d'accès avec cette valeur, puis associer l'APK et rétablir le SSID **2,4 GHz** de la Livebox avec le vrai mot de passe Wi-Fi. Ne pas choisir un SSID uniquement 5 GHz.
4. Si le terminal série n'affiche rien, vérifier le câble USB data, le port adapté et redémarrer via RESET/EN **sans maintenir BOOT** après avoir commencé la capture. Sinon prévoir un flash conservant NVS et un mécanisme de récupération physique à accès local.
5. Une fois les mesures de nouveau accessibles, sauvegarder la configuration et déployer seulement une version CI testée. Ne pas utiliser BOOT « Réassocier » pour corriger une simple coupure Wi-Fi.
6. La passerelle VPN a dans son firmware un ancien code JK compilé. Tout changement du code JK peut donc invalider ses lectures jusqu'à une reprovision sûre de ce code. Les codes ne doivent pas être synchronisés par diffusion non chiffrée.

## Simplification de l'association à moyen terme (sans affaiblir la sécurité)

Objectif : **une seule association par ESP32**. Le secret généré de manière aléatoire doit être conservé dans NVS et Keystore Android, et **aucun code ne doit être redemandé** pour basculer LAN/Tailscale ou ouvrir l'application.

- Séparer le **mot de passe Wi-Fi du point d'accès de configuration** (stable par appareil) du **jeton d'accès aux API JK** (révocable), et du token du **VPN Tailscale**. Les trois doivent rester indépendants.
- Ajouter un **mode récupération physique temporaire sur BOOT même si l'ESP32 n'est plus connecté à la box**, avec authentification et durée limitée, sans exposer un point d'accès permanent ouvert sans mot de passe. Documenter explicitement sa procédure.
- Utiliser dans l'application un assistant de première association par QR/code généré pour le matériel, puis stockage chiffré; masque avancé « changer mon code » uniquement pour rotation volontaire.
- Remplacer les erreurs techniques par `Wi-Fi perdu`, `code refusé`, `ESP32 en configuration`, `VPN actif mais LAN bloqué`, etc.; aucun bouton de réparation ne change silencieusement le code.
- Prévoir une procédure contrôlée de reprovisionnement de la passerelle VPN lorsqu'un jeton JK change, avant d'autoriser l'écriture de paramètres BMS à distance.

Ceci est un plan : le mode BOOT sans SSID et la séparation des tokens ne sont **pas encore implémentés** dans 0.2.5. La solution actuelle reste provisoire, et aucune API de modification de paramètres BMS n'a été ouverte.

## Avancement 0.3.1-dev — récupération sans lecture du code USB

Le nouveau firmware 0.3.1 **implémente désormais** une fenêtre physique BOOT 4 s avec AP temporaire ouvert 120 s, suivie d'un deuxième BOOT 2 s après demande de l'APK. L'app permet de choisir un code personnel 8–32 caractères, y compris si l'ancien code a été perdu, et conserve la NVS Wi-Fi/BMS. Il n'est donc **plus nécessaire de lire le code par USB série après mise à jour du firmware**. Le flash initial de ce nouveau firmware nécessite toutefois un outil d'installation ; l'OTA n'est pas encore disponible. L'ESP32 VPN reste inchangé.

**Limite de sécurité** : les secrets transmis sur l'AP ouvert ne sont pas encore chiffrés au niveau applicatif. La confirmation BOOT n'est pas une authentification cryptographique ; ne pas présenter ce mécanisme expérimental comme totalement sûr contre écoute ou usurpation radio. Avant diffusion publique, introduire un protocole de bootstrap chiffré/authentifié.
