# Mise à jour OTA du firmware VPN dans Smart Energy Android

## Comportement visé

APK Smart Energy Android **0.3.63-dev** → onglet **MÀJ** → **Firmware ESP32 VPN / Tailscale · OTA**.

L'application utilise le réseau VPN Tailscale du téléphone et le **token privé de passerelle déjà sauvegardé**. Une fois la version/partition vérifiée, elle lit `updates/vpn-gateway.json` (HTTPS GitHub), refuse les versions identiques, images non officielles et cibles incorrectes, puis demande une confirmation utilisateur.

La commande `POST /api/v1/vpn/ota/request` ne contient qu'une instruction `{"confirmed":true,"action":"check_and_install"}`, sans firmware ni URL arbitraire. Les contrôles côté ESP imposent :
- connexion réellement entrante depuis le tailnet vers l'IP Tailscale du VPN ;
- token `X-Gateway-Token` et `X-OTA-Confirmed: yes` ;
- ESP32-S3 N16R8 avec deux partitions OTA ;
- SHA-256 de l'image et identité ESP-IDF validées ;
- URL officielle exacte `vpn-gateway-vX.Y.Z/SmartEnergy-VPN-S3-OTA.bin` ;
- un seul OTA à la fois, aucune tentative automatique en boucle.

**L'ESP32 VPN télécharge lui-même l'image HTTPS via son Wi-Fi domestique**, évitant d'envoyer ~1,4 Mo dans le tunnel TailTiny. Il redémarre ensuite sur la partition mise à jour ; le nouveau firmware ne valide le démarrage qu'après démarrage réussi du Wi-Fi, de Tailscale et du serveur privé. Une défaillance Wi-Fi/Tailscale au démarrage demande un rollback vers l'application précédente.

## Compatibilité

| Modèle | OTA | Première installation |
|---|---|---|
| VPN ESP32 classique 4 Mo | Non : application unique actuellement, pas deux slots suffisants garantis | USB via flasher |
| VPN ESP32-S3 N16R8 neuf (16 Mo) | Oui, **après installation de la nouvelle image Factory OTA et validation matérielle** | Flasher Windows Factory S3 |
| VPN ESP32-S3 déjà déployé avec ancienne table unique | Non tant que `ota_supported=false` | Migration USB de partitionnement à prévoir et à valider avec sauvegarde NVS |

**Ne jamais utiliser le merged Factory à 0x0 sur un appareil déjà configuré** : il peut écraser les informations NVS, notamment l'identité Tailscale, Wi-Fi et les codes. L'APP-ONLY conserve la NVS mais ne migre **pas** le partitionnement.

La table S3 nouvelle : NVS `0x9000..0xEFFF`, factory `0x10000/2 Mo`, OTA0 `0x210000/2 Mo`, OTA1 `0x410000/2 Mo`, otadata `0x610000`.

## Publication des nouvelles versions

L'image `SmartEnergy-VPN-S3-OTA.bin` générée par GitHub Actions est un **candidat de test** ; l'existence d'un artifact ne rend pas une OTA disponible sur l'APK. Le manifeste de `main` reste `available=false` jusqu'à la validation électrique et réseau, puis à une publication GitHub Release `vpn-gateway-vX.Y.Z` dont le workflow vérifiera l'en-tête ESP32-S3 et calculera le SHA-256.

## Essais matériels indispensables avant diffusion

1. Sur un S3 de réserve, sauvegarder NVS ; installer le firmware OTA sur le partitionnement attendu sans effacement des identifiants de production.
2. Vérifier le retour du même identifiant et de l'IP Tailscale, les lecteurs JK/Voltronic/Deye, et le bouton de récupération Wi-Fi Bluetooth.
3. Publier une **version ultérieure** signée/contrôlée uniquement après validation ; déclencher l'OTA en mode local et distant et comparer /health et /api/v1/vpn/ota/status.
4. Vérifier les erreurs : manifeste absent/désactivé, image tronquée, mauvais SHA-256, firmware d'une autre puce, coupure d'Internet ou de Wi-Fi, rollback au prochain démarrage.
5. Une erreur ou un redémarrage sans reconnexion exige un diagnostic et éventuellement une récupération USB physique. Ne jamais relancer automatiquement un flash.

**Les compilations GitHub ne remplacent pas ces essais matériels.** La passerelle classique en service et les clients existants ne doivent pas être modifiés automatiquement.

**Tailscale reste nécessaire sur le téléphone pour les opérations distantes.** « VPN dans l'APK » désigne ici la mise à jour du firmware de la passerelle ; cela n'intègre pas un moteur VPN Tailscale autonome dans Smart Energy.
