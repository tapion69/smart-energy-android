# Smart Energy Android — accès depuis l'extérieur (VPN privé)

## État de la v0.2.1-dev

La v0.2.1-dev permet à l'APK de **communiquer via un tunnel VPN privé** avec l'ESP32 resté à la maison. L'ESP32 conserve son adresse IP locale et sa configuration. La connexion n'est possible que si un autre appareil connecté au LAN domestique **sert de routeur VPN pour le sous-réseau** et que le téléphone rejoint ce VPN. Cette fonctionnalité ne crée pas de cloud, ne configure pas automatiquement un VPN et ne rend pas l'ESP32 directement accessible sur Internet.

- Un appareil fonctionnant en permanence au domicile (routeur Linux, Raspberry Pi, ordinateur, mini-PC ou routeur compatible) assure le routage du sous-réseau.
- Le téléphone Android doit utiliser le même réseau VPN et disposer d'une route vers le LAN de la maison.
- L'APK détecte la connexion VPN Android et utilise la même **IP privée** de l'ESP32 qu'à domicile.
- À distance, seules les routes **GET** sont autorisées : \`/api/v1/info\`, \`/api/v1/bms\`, \`/api/v1/telemetry\` et \`/api/v1/details\`.
- L'APK refuse toute adresse IPv4 publique. Elle n'envoie jamais le code de la passerelle à \`/api/v1/info\`.
- Le transport entre le téléphone et le routeur VPN est chiffré par le VPN. Le dernier segment routeur↔ESP32 reste HTTP sur le LAN local **de confiance**.
- Le symbole VPN Android peut correspondre à un VPN commercial qui **ne donne pas accès à la maison**. La détection d'un VPN ne prouve pas l'accessibilité : tester effectivement l'IP de l'ESP32.

## Option 1 : Tailscale, avec routeur de sous-réseau à domicile

Documentation officielle : https://tailscale.com/docs/features/subnet-routers/how-to/setup

### Sur un appareil Linux allumé en permanence à la maison

Installer Tailscale depuis https://tailscale.com/download/linux et se connecter au compte Tailscale choisi.

Activer la transmission IPv4 vers les machines du LAN et annoncer le réseau **réel** de la maison. Dans l'exemple ci-dessous, l'ESP32 a une adresse de la forme \`192.168.1.x\`, donc le réseau complet est \`192.168.1.0/24\` :

\`\`\`sh
sudo sysctl -w net.ipv4.ip_forward=1
sudo tailscale set --advertise-routes=192.168.1.0/24
\`\`\`

Pour une configuration pérenne, suivre le guide Tailscale pour activer IP forwarding au démarrage. Les règles de filtrage réseau de la machine doivent autoriser l'accès du routeur VPN au LAN local. Il peut être nécessaire d'activer le SNAT selon la configuration Tailscale ; ne pas ouvrir le port 80 de l'ESP32 sur le routeur Internet.

Dans la console d'administration Tailscale, aller dans **Machines**, trouver le routeur de sous-réseau, puis **approuver la route annoncée** \`192.168.1.0/24\`. Cette approbation est indispensable.

### Sur le téléphone Android

1. Installer Tailscale depuis https://tailscale.com/download/android et rejoindre le même réseau Tailscale.
2. Activer le VPN Tailscale Android. **Attention :** un seul VPN Android peut généralement être actif à la fois ; un VPN d'une autre application peut bloquer ce tunnel.
3. Désactiver le Wi-Fi du téléphone pour tester réellement depuis la **4G/5G**.
4. Essayer d'ouvrir \`http://<IP-LOCALE-ESP32>/api/v1/info\` depuis le navigateur. L'adresse reste privée. La page renvoie un JSON lorsque la route fonctionne.
5. Dans l'APK v0.2.1, ouvrir **Configuration et association → Accès extérieur — VPN privé**, cocher « Reconnexion automatique quand mon VPN est actif », puis appuyer sur **Tester ma connexion distante via VPN**.
6. L'APK utilise le code chiffré qui était déjà enregistré sur le téléphone. Si l'application a été réinstallée, refaire l'association locale une fois avec BOOT selon le guide existant. Elle ne demande pas de nouveau mot de passe dans le mode VPN.
7. Avec le VPN actif, les données de Batterie / Détails / Paramètres remontent depuis l'ESP32, sans Home Assistant.

### Dépannage

- « VPN non connecté » : activer le VPN sur Android. L'APK n'installe ni ne lance Tailscale à ta place.
- « VPN actif mais passerelle inaccessible » : route de sous-réseau non approuvée, IP changée, firewall local, machine routeur hors ligne, ou VPN qui n'est pas relié à la maison.
- Si l'ESP32 ne répond plus en local, vérifier d'abord son alimentation et le Wi-Fi domestique. Le VPN n'améliore pas la connectivité BLE du JK.
- Sur un réseau extérieur qui utilise par hasard le même sous-réseau \`192.168.1.0/24\`, il peut exister un conflit de routes. Tester d'abord en 4G/5G.
- Le routeur VPN n'a pas besoin d'être Home Assistant. Cela fonctionne sur un LAN classique avec n'importe quel appareil routable.
- Le mode extérieur est **strictement en lecture seule**. La configuration Wi-Fi/BLE ou la réassociation BOOT doivent rester sur place.

## Ce que cette version ne fait pas encore

- Pas d'accès direct via Internet à partir du seul ESP32.
- Pas de relais cloud avec authentification et chiffrement gérés par Smart Energy.
- Pas de gestion automatique de comptes Tailscale ou WireGuard dans l'APK.
- Pas de route publique HTTP, DNS dynamique ou ouverture de ports.
- Pas d'OTA firmware ESP32 pour le moment ; mise à jour APP-ONLY à \`0x10000\` sans effacement de NVS.

**Objectif futur :** pour distribuer une solution à tous les utilisateurs sans routeur VPN ni ordinateur, développer un relais sécurisé avec authentification indépendante et connexion ESP32 **sortante** (TLS), ainsi qu'un processus de révocation et d'appairage. Ce service exige une infrastructure serveur et un hébergement ; il n'existe pas dans la v0.2.1.
