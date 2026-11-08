# Smart Energy — Passerelle VPN ESP32 n°2 (prototype)

**Expérimental. Aucun changement sur le lecteur JK existant.**
Ne flasher cette version QUE sur le second ESP32 dédié au VPN.

## Principe

Téléphone Android avec Tailscale en 4G/5G → tunnel WireGuard/TailTiny → ESP32 VPN → Wi-Fi maison → ESP32 JK ou, dans une étape ultérieure, ESP32 Voltronic.

Les deux ESP32 peuvent être **dans des pièces différentes**. Seule l'accessibilité du réseau local importe. Pas de mini-PC, pas de Home Assistant, pas de redirection de port sur la box, pas de serveur Smart Energy à financer. Chaque utilisateur apporte son propre compte Tailscale.

**Ce n'est PAS un routeur de sous-réseau Tailscale.** La passerelle ne transmet que les demandes de lecture explicitement autorisées à ses IP locales préconfigurées.

## Routes de test

Depuis un client Tailscale personnel, à l'adresse VPN du second ESP32, port TCP 18767 :

| Route | Contenu | Contrôle |
| --- | --- | --- |
| /api/v1/health | État de la passerelle, sans secret | VPN obligatoire |
| /api/v1/jk/telemetry | Lecture GET /api/v1/telemetry de l'ESP32 JK | VPN + X-Gateway-Token |
| /api/v1/jk/details | Lecture GET /api/v1/details de l'ESP32 JK | VPN + X-Gateway-Token |
| /api/v1/voltronic/status | Route HTTP fixe du futur ESP Voltronic | Désactivé sans IP et route Voltronic connues |

Les appels de modification et les URLs arbitraires sont exclus. Le token indépendant de la passerelle n'est pas le code JK. Le code JK circule uniquement sur le segment local entre les deux ESP32 et ne sort pas par l'API de la passerelle.

La route Voltronic est un emplacement prévu dans l'architecture, **pas une intégration déjà fonctionnelle**. Nous devons connaître le protocole réel de l'ESP Voltronic (HTTP, MQTT ou TCP série) avant de l'utiliser.

## Première préparation (compilation avec paramètres locaux)

Installer ESP-IDF 5.3.2 (ESP-IDF 5.3 CMD sous Windows), prévoir un second ESP32 classique et un câble USB.

Sur https://login.tailscale.com/admin/settings/keys, créer une clé d'association Tailscale propre au réseau personnel de l'utilisateur. Choisir un appareil NON éphémère. Ne jamais envoyer la clé dans un chat ou un dépôt Git.

Dans un terminal ESP-IDF Windows (adapte le port COM au tien) :

~~~powershell
git clone https://github.com/tapion69/smart-energy-android.git
cd smart-energy-android
git checkout experiment/esp32-vpn-gateway
git clone https://github.com/Asimmimx/tailtiny.git firmware/vpn-gateway/upstream-tailtiny
cd firmware/vpn-gateway/upstream-tailtiny
git checkout d1ec2ae45e252e8dc737452be0f6afe1ac2e3edb
cd ..
mkdir components
xcopy /E /I upstream-tailtiny\components\tailtiny components\tailtiny
copy sdkconfig.credentials.example sdkconfig.credentials
~~~

Éditer uniquement le fichier local sdkconfig.credentials (ignoré par Git). Renseigner :

- CONFIG_ML_WIFI_SSID : Wi-Fi 2,4 GHz de la maison
- CONFIG_ML_WIFI_PASSWORD : mot de passe Wi-Fi
- CONFIG_ML_TAILSCALE_AUTH_KEY : clé du compte Tailscale de CET utilisateur
- CONFIG_SE_JK_IP : IP privée de son ESP32 JK
- CONFIG_SE_JK_READ_CODE : code personnel de l'ESP32 JK
- CONFIG_SE_GATEWAY_TOKEN : nouveau code aléatoire de **20 caractères ou plus** propre à la passerelle
- Laisser les trois champs CONFIG_SE_VOLT_* vides, en attendant l'API Voltronic réelle.

**Aucune clé d'utilisateur ne doit être envoyée à ChatGPT, stockée dans le dépôt ou publiée dans les journaux.** Le prototype utilise encore des secrets compilés : acceptable pour un essai privé contrôlé, **pas pour une distribution publique**.

Compiler et flasher UNIQUEMENT l'ESP32 VPN :

~~~powershell
idf.py set-target esp32
idf.py -p COM5 flash monitor
~~~

Si un fichier sdkconfig a déjà été généré avant la saisie des secrets, supprimer sdkconfig puis recompiler : ESP-IDF conserve les valeurs précédentes lors de la régénération.

## Test depuis l'extérieur

1. Faire rejoindre l'ESP32 VPN au compte Tailscale. Son adresse personnelle apparaît dans la console sous la forme 100.x.x.x. Ne pas publier cette adresse avec les clés d'accès.
2. Installer Tailscale sur Android et connecter le téléphone au **même tailnet**.
3. Désactiver le Wi-Fi du téléphone (4G/5G seule).
4. Ouvrir dans le navigateur : http://100.x.x.x:18767/api/v1/health
5. La réponse doit indiquer product=smart-energy-vpn-gateway, vpn_connected=true et read_only=true.
6. Pour la télémétrie JK, depuis un ordinateur membre du tailnet, faire :

~~~sh
curl -H 'X-Gateway-Token: TON_SECRET_GATEWAY' http://100.x.x.x:18767/api/v1/jk/telemetry
~~~

La réponse doit être celle du JK BMS. Le navigateur ne permet pas d'ajouter cet en-tête de test facilement ; une intégration dédiée dans l'APK sera développée **après validation de cette étape**.

## Sécurité et limites connues

- Les réponses sont exclusivement JSON lu avec GET. Aucun POST de configuration n'est relayé.
- Les clients doivent atteindre la **destination VPN de l'ESP32** avec une adresse source VPN ; une requête sur son IP Wi-Fi est rejetée.
- Le VPN chiffre le trajet depuis Android, mais le segment ESP32 VPN → ESP32 JK reste en HTTP sur le Wi-Fi domestique de confiance.
- Le constructeur du client VPN n'est pas responsable du code tiers TailTiny ; nous devons tester le firmware sur du matériel réel et maintenir les correctifs.
- Plusieurs utilisateurs Tailscale dans le même tailnet ne doivent pas voir toutes les installations : utiliser un tailnet propre à chaque maison et des règles d'accès.
- Ce firmware est un POC, sans OTA, configuration APK, révocation locale ou mémoire sécurisée.
- Le test de cohabitation avec le JK doit couvrir la stabilité BLE, les bips, les redémarrages, les chemins direct/DERP et les coupures Wi-Fi.
- Le test du futur ESP Voltronic ne pourra être fait qu'une fois son interface réseau définie et accessible.

## Suite prévue

Valider le second ESP32 en 4G/5G, puis ajouter le mode de connexion VPN dans l'APK avec un token sécurisé. Ensuite, ajouter l'adaptateur de lecture pour l'ESP Voltronic sur le même réseau VPN, sans modifier les règles de protection du JK ou de l'onduleur.
