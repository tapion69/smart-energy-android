# Smart Energy Flasher — préparation par nom (prototype)

Cette version regroupe **3 onglets uniquement** : **ESP32 JK**, **ESP32 VPN** et **Onduleurs**.
Tous les firmwares disponibles sont précompilés et inclus dans `SmartEnergyFlasher.exe`.
**Aucune IP locale à prévoir** et aucun fichier `.bin` externe à choisir.

## Réparer les onduleurs via le VPN sans reflasher (erreur 503)

Un ESP32 Voltronic peut répondre sur le Wi-Fi local et dans Home Assistant
tout en étant absent du relais VPN : l'association nom/code n'a pas abouti,
ou l'IP du lecteur a changé. Le VPN renvoie alors `source_not_configured`
(503), **sans signifier que la NVS a été effacée**.

Pour contourner la découverte par nom, utiliser
`SmartEnergy-VPN-ReparerOnduleurs.exe` (GitHub Actions
`build-vpn-target-repair.yml`) ou la section de réparation USB de l'onglet
ESP32 VPN du flasher récent. **Aucun firmware n'est écrit.**

1. Relever l'IP locale **actuelle** de chaque ESP32 Voltronic (celle qui
   fonctionne dans Home Assistant), puis son code de lecture déjà configuré.
   Une réservation DHCP dans la box évite que cette IP change.
2. Brancher **la passerelle VPN** au PC Windows avec un câble USB de données,
   pas les ESP32 Voltronic. Sélectionner le port COM réel.
3. Choisir INV1, saisir l'IP privée et le code de l'ESP32 Voltronic 1.
   Cliquer **Enregistrer cet INV dans le VPN — AUCUN FLASH**.
4. Sur la passerelle VPN, maintenir BOOT environ 4 secondes puis relâcher,
   sans appuyer sur RESET. L'outil vérifie le canal USB par `SEVPN_PING` avec
   nonce avant tout envoi de configuration.
5. Attendre la confirmation `SEVPN_OK_REBOOT`. Elle signifie que le firmware
   a écrit **uniquement** `volt1_ip` et `volt1_code` puis relu la NVS.
   La passerelle redémarre et rejoint son réseau.
6. Refaire pour INV2 avec son IP et son code, une fois le VPN revenu.
   Vérifier les remontées en 4G/5G avec Tailscale.

L'outil refuse une IP publique ou Tailscale, un code mal formé et toute absence
de réponse bidirectionnelle USB. Si la confirmation NVS n'arrive pas, l'état
est **non confirmé** et aucun réessai d'écriture n'est effectué
automatiquement. Sur un ancien firmware sans cette commande, il est
impossible de l'utiliser : ne pas reflasher la passerelle de production par
défaut. Les JK, Wi-Fi, clé Tailscale, token et autres INV ne sont pas modifiés.

## Préparer un nouveau module

1. Brancher **un seul ESP32** au PC, le sélectionner dans la liste de ports COM.
2. Sélectionner le bon onglet et le matériel. Pour un appareil vierge uniquement, choisir **FACTORY** ; pour mettre à jour un ESP déjà configuré, **APP-ONLY**.
3. **JK** (ESP32 classique) : saisir un **nom personnalisé**, le Wi-Fi 2,4 GHz du site et un code privé de 8 à 32 caractères. Les BMS pourront être associés ensuite dans l'APK.
4. **Onduleurs** (ESP32-S3 N16R8) : sélectionner **Voltronic/Axpert**, saisir le nom, le Wi-Fi et le code privé, puis flasher. **Deye** apparaît en préparation mais le flash est bloqué tant que son vrai firmware n'existe pas.
5. **VPN** (ESP32 classique ou S3) : saisir son Wi-Fi, sa clé d'association Tailscale et son jeton privé. Pour chaque JK et onduleur préparé, renseigner **le même nom et le même code**. L'adresse IP locale reste vide et sera retrouvée après installation par la passerelle.
6. Contrôler la confirmation USB et les informations de progression. Lors d'une mise à jour APP-ONLY, la NVS, le Wi-Fi et les codes doivent être conservés. **Une mise à jour ne renomme pas un appareil déjà enregistré.**

## Sur place

Le destinataire connecte ses ESP à l'alimentation et au Wi-Fi connu. Dans l'application **Smart Energy** : **Configuration → Ajouter un équipement → Rechercher mes équipements**. Il choisit le nom du lecteur et saisit son code une fois. La découverte JK et Voltronic se fait sur le **réseau local**. Pour le VPN, utiliser l'adresse Tailscale déjà connue et le jeton de passerelle.

**Limites importantes :** les annonces LAN nécessitent un réseau qui autorise les broadcasts/mDNS ; les lecteurs doivent pouvoir atteindre le VPN sur le LAN. Les noms sont des étiquettes, **pas des secrets** : utiliser des codes longs et aléatoires (16–32 caractères recommandé). Le VPN vérifie une signature cryptographique avant d'accepter une annonce et d'interroger l'appareil.

## Sécurité du flash

- **FACTORY efface/remplace potentiellement le partitionnement : seulement pour un module neuf.**
- **APP-ONLY** ne doit pas toucher à la NVS ; les mises à jour JK imposent une sauvegarde NVS locale et une vérification des partitions avant écriture.
- Les mots de passe Wi-Fi, codes et clés Tailscale sont transférés **uniquement via USB physique** pour cette première préparation ; ils ne sont pas intégrés aux firmwares publiés.
- Le S3 Voltronic nécessite une interface **MAX3232 RS232 3,3 V** : ne jamais brancher la liaison RS232 directement sur des broches GPIO.
- Aucune écriture automatique de paramètres, aucune commande brute, aucune activation distante des écritures VPN n'est introduite par la découverte.
- Les nouveaux firmwares et le fonctionnement distant nécessitent encore **une vérification sur ESP physiques de réserve**. Ne pas écraser un VPN fonctionnel avant ces essais.
- Deye et la mise à jour OTA du lecteur à travers le tunnel VPN ne sont pas livrés dans ce pack.

La compilation Windows tout-en-un est générée par [GitHub Actions](https://github.com/tapion69/smart-energy-android/actions/workflows/build-vpn-gateway.yml).
La conception technique de la découverte figure dans [UNIVERSAL-READER-DISCOVERY-DEV.md](../../docs/UNIVERSAL-READER-DISCOVERY-DEV.md).

## Récupération Bluetooth lors d'un changement de box (branche BLE de test)

Le flasher de cette branche doit être **recompilé avec les nouveaux binaires** avant que cette fonction soit disponible. Le firmware JK utilise son BLE exclusivement pour la maintenance pendant cette opération ; on peut éloigner le lecteur des batteries. Les firmwares VPN, Voltronic et Deye ouvrent un service BLE chiffré uniquement après un appui BOOT physique. Dans Smart Energy Android 0.3.62-dev, ouvrir « Configuration et associations → Récupération Wi-Fi Bluetooth ». Enregistrer le Wi-Fi avec le code de l'appareil (token pour VPN) ; les autres paramètres NVS sont conservés. Voir [documentation BLE](../../docs/BLE-WIFI-RECOVERY.md). Ne pas reflasher un ESP client fonctionnel avant validation physique sur un module de réserve.
