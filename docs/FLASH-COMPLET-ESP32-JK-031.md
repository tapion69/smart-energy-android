# Reflashage complet Smart Energy ESP32 JK — 0.3.1-dev
**Pour ESP 1 (lecteur JK Bluetooth) uniquement. NE JAMAIS flasher ESP 2 (VPN/Tailscale).**

## Prérequis

- **ESP 1** seul branché en USB au PC Windows / Linux / macOS (câble de données).
- Navigateur de bureau **Google Chrome ou Microsoft Edge** pour le flash Web Serial ; Android/iOS ne conviennent pas pour ce flasheur.
- Les fichiers d'essai sont dans GitHub Actions, branche `experiment/smart-energy-v0.3.0-multibms`, exécution dont les 2 jobs sont verts.
- Télécharger **l'artefact** `smart-jk-esp32-v0.3.1-COMPLETE-ERASE-FIRST-address-0x0000`, **décompresser** l'archive puis utiliser le fichier `smart_jk_esp32_v0.3.1_FULL_ERASE_FIRST_0x0000.bin`.
- Ne pas confondre avec l'artefact **APP-ONLY offset 0x10000**, réservé aux mises à jour **sans effacement**.

## Procédure graphique (sans PlatformIO)

1. Identifier physiquement l'**ESP32 JK**, débrancher les autres cartes ESP USB du PC et ne jamais sélectionner l'ESP VPN.
2. Ouvrir **https://esptool.spacehuhn.com/** avec Chrome/Edge sur PC.
3. Cliquer sur **Connect**, choisir le port série de l'ESP32 JK (COMx sous Windows).
4. Cliquer sur **Erase** et confirmer l'effacement de l'ESP32 JK choisi. Cette action efface **NVS** : code, SSID/mot de passe Wi-Fi domestique et MAC JK configurées.
5. Cliquer sur **Add File**, sélectionner le fichier fusionné `smart_jk_esp32_v0.3.1_FULL_ERASE_FIRST_0x0000.bin`, indiquer **0x0000** dans Address.
6. Cliquer sur **Program** puis attendre le résultat « success » / « finished ». Ne jamais utiliser `0x10000` avec cette image complète.
7. Débrancher/rebrancher l'alimentation ou appuyer **EN/RESET** *sans* maintenir BOOT.
8. Les BMS eux-mêmes n'ont pas été effacés ; leurs paramètres internes restent intacts. L'ESP VPN non branché au PC n'a pas été modifié.

Si aucun COM n'apparaît, changer de câble USB (câble données), vérifier les pilotes USB UART (CH340, CP210x selon ESP) et les autorisations série. Si la connexion bloque, la procédure du flasheur peut nécessiter BOOT seulement pour **entrer en mode flash** ; ne pas confondre avec les appuis BOOT de l'association, **qui ont lieu plus tard, ESP déjà démarré sans USB**.

## Essai première installation (APK Android 0.3.1-dev)

1. Installer l'APK d'essai téléchargée depuis l'artefact `smart-energy-android-v0.3.1-multibms-debug-test` de la **même exécution GitHub Actions**. Elle s'installe à côté des APK précédentes.
2. Sur le téléphone à domicile, **désactiver temporairement Tailscale**, activer Wi-Fi, localisation et permissions Wi-Fi proches.
3. Alimenter l'**ESP JK**, attendre le démarrage puis maintenir **BOOT 4 secondes** et relâcher. Un point d'accès provisoire `SmartJK-Setup-XXXXXX` s'ouvre pour **2 minutes**.
4. Dans Smart Energy, ouvrir **Configuration et association**, puis **Trouver un ESP32 proche**, sélectionner son réseau. Choisir **« Associer avec BOOT (première fois / code oublié) »**.
5. Choisir un **code personnel de 8 à 32 caractères ASCII sans espaces**. Pour éviter de changer ESP 2, utiliser **exactement le code JK déjà renseigné dans le firmware de la passerelle VPN** (ce n'est pas le jeton `X-Gateway-Token` du VPN). Pour une remise à zéro, saisir aussi le SSID **2,4 GHz** de la box et son mot de passe. Le nom SSID 5 GHz uniquement ne fonctionne pas sur l'ESP32 classique.
6. Accepter la demande Android de connexion au Wi-Fi provisoire. L'application affiche que la demande est préparée et réclame un **deuxième BOOT 2 secondes**, commencé **après** ce message. Relâcher puis toucher **« Après le 2e BOOT : confirmer l'association »**.
7. Le lecteur redémarre, rejoint le Wi-Fi maison et l'application conserve le code chiffré. Vérifier la découverte du JK. Les MAC JK doivent être à nouveau sélectionnées dans **Détecter les JK BMS** puis enregistrées : elles ont été effacées au flash complet.
8. Pour conserver **ESP VPN 2 tel quel**, vérifier impérativement que l'ESP JK reprend son adresse IP de destination configurée dans le VPN (anciennement `192.168.1.53`). Configurer une réservation DHCP sur la Livebox pour l'ESP JK si nécessaire, en vérifiant qu'aucun autre équipement n'utilise cette IP. ESP 2 utilise cette adresse et un code JK compilés auparavant ; il ne les découvre pas automatiquement.
9. Tester le tableau de bord en local, puis activer Tailscale et vérifier les **lectures uniquement** à distance. Pas de modification de paramètres via le VPN actuel.

## Important — sécurité et limites

- **Deux appuis physiques BOOT sont requis**, mais le Wi-Fi provisoire reste **ouvert** durant l'association et les échanges ne sont pas encore chiffrés au niveau applicatif. C'est un mécanisme **expérimental**, ne pas l'utiliser sur un réseau hostile. Protéger le premier appairage d'une personne extérieure au périmètre radio.
- Ne pas divulguer dans des captures ou GitHub le code de l'ESP VPN, les mots de passe Wi-Fi ni les clés Tailscale.
- La 0.3.1 compile, mais son assistant BOOT et ses quatre sessions BLE simultanées nécessitent encore des essais réels avant publication stable.
- Le seul ESP à flasher ou à effacer est **ESP 1**. Le firmware, la carte, les secrets et la configuration d'**ESP 2 restent inchangés**.

## Variante développeur PlatformIO

Depuis la racine du dépôt, avec Python et PlatformIO :

```bash
python -m pip install platformio==6.1.18
pio run -d firmware/jk-esp32 -e esp32dev
# Contrôler le port série de ESP 1 avant d'effacer !
pio run -d firmware/jk-esp32 -e esp32dev -t erase
pio run -d firmware/jk-esp32 -e esp32dev -t upload
```

Le `upload` PlatformIO comprend tous les segments nécessaires au boot, contrairement au seul `firmware.bin` APP-ONLY. Les fichiers du deuxième ESP32 VPN ne sont jamais utilisés.
