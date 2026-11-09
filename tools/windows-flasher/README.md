# Smart Energy Flasheur Windows — pilote expérimental

**Objectif** : avoir un programme Windows \`SmartEnergyFlasher.exe\` pour les deux types d'ESP32 classiques, sans Home Assistant. Interface graphique, port COM, choix du mode et bouton Flash.

- **ESP32 JK — mise à jour** : sélectionner le binaire APP-ONLY de Smart Energy, flash **uniquement à \`0x10000\`**, sans \`erase_flash\`. Le Wi-Fi, le code JK et la liste BMS restent normalement dans la NVS. Ne pas mélanger les modèles de cartes.
- **ESP32 JK — première installation** : image FULL combinée avec les partitions à \`0x0000\`, uniquement sur une carte neuve. Ce mode demande une confirmation supplémentaire et peut rendre inaccessible une ancienne NVS. Toujours conserver les sauvegardes.
- **ESP32 VPN — construction propre à chaque utilisateur** : renseigner Wi-Fi, clé Tailscale, token du relais, puis jusqu'à **3 lecteurs JK** (IP + code de lecture distinct). Le programme crée le fichier local \`sdkconfig.credentials\` ignoré par Git, récupère TailTiny au commit épinglé, régénère \`sdkconfig\`, compile via ESP-IDF et flashe UNIQUEMENT la carte VPN sur son port COM. Aucun secret ne fait partie de l'EXE.

## Prérequis Windows

- Pour le JK : pilote USB série FTDI/CH340/CP210x adapté et câble de données USB. Le programme comprend esptool (aucun Python requis pour l'utilisateur).
- Pour le VPN : en plus, **Git for Windows et ESP-IDF 5.3.2 déjà installé**, avec le chemin vers \`export.bat\`. La compilation personnalisée s'effectue localement pour ne jamais transmettre les secrets au serveur de compilation. Connexion Internet pour récupérer TailTiny une première fois.
- Répertoire \`firmware/vpn-gateway\` provenant de la branche \`dev\` du dépôt. L'EXE ne télécharge pas ce dépôt automatiquement.

## Sécurité

- L'outil n'appelle jamais \`erase_flash\`.
- Les codes de BMS et jetons sont saisis comme champs masqués. Le journal masque leurs valeurs, mais ne doit pas être partagé aveuglément.
- Le fichier local \`sdkconfig.credentials\` et ses éventuelles sauvegardes **contiennent les secrets** : protéger ce PC et ne jamais les ajouter à GitHub, ni à une capture publique.
- Le prototype VPN compile les secrets dans le firmware. La sécurité physique et la gestion des clés restent expérimentales ; ne pas distribuer ce firmware comme produit commercial.
- Le flasheur ne vérifie pas encore les signatures cryptographiques des binaires choisis : n'utiliser que les artefacts de build provenant du dépôt du projet.
- Le déploiement d'un ESP32 VPN existant n'est **pas** nécessaire pour tester les corrections Android locales. Garder le firmware VPN actuellement opérationnel tant que le multi-lecteur n'a pas été validé.

## Construction du .exe

GitHub Actions sur \`dev\` : [workflow Smart Energy Windows Flasher](https://github.com/tapion69/smart-energy-android/actions/workflows/build-windows-flasher.yml). L'artefact contient un \`.exe\` unique.

Test matériel requis : un ESP32 JK de réserve et un second ESP32 VPN **non critique** avant diffusion à d'autres utilisateurs. La compilation n'est pas une certification du flash matériel.


## Mise à jour — flasheur sans Git

Le flasheur Windows télécharge désormais uniquement le composant TailTiny du commit épinglé par HTTPS. **Il ne demande plus Git for Windows**. Le système VPN actuel exige cependant toujours ESP-IDF 5.3.2 pour une compilation locale incluant les identifiants privés : l'EXE n'est donc **pas encore 100 % autonome pour le VPN**. Il est autonome pour flasher un lecteur JK avec son firmware déjà compilé.

Un kit VPN réellement monobinaire devra utiliser un firmware universel précompilé et une configuration physique par USB conservée en NVS, avec contrôles d'accès et tests matériels, plutôt que de distribuer un SDK de plusieurs gigaoctets.

Le firmware ESP32-S3 nécessite une compilation et une sélection de carte spécifiques : **le flasheur JK actuel est réservé à l'ESP32 classique**.
