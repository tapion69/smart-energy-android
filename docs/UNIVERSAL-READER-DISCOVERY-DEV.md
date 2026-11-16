# Smart Energy — Découverte universelle JK / Voltronic (branche de test)

État : **prototype, validation physique en attente**. Branches de développement uniquement. Aucun flash du VPN, JK ou Voltronic de production avant essai sur des modules de réserve.

## Installation des nouveaux lecteurs

1. Flasheur Windows → **ESP32 JK** pour un JK classique ou **Onduleurs → Voltronic/Axpert** pour le S3 N16R8.
2. Mode **FACTORY** réservé à une carte vierge ; indiquer le Wi-Fi de destination 2,4 GHz, un **nom personnalisé distinct** (max. 32 octets UTF-8), un **code de lecture** personnel (8–32 caractères).
3. Le programme envoie les informations **physiquement en USB**, jamais via GitHub ni dans l'image précompilée. Pour JK, le nouveau mécanisme exige un firmware prenant en charge `SEJK_CONFIG` et un premier démarrage sans Wi-Fi sauvegardé. Pour Voltronic, `SEVOLT_CONFIG`.
4. Flasheur → **ESP32 VPN**, renseigner Wi-Fi/Tailscale, jeton de passerelle et **noms + codes** identiques aux lecteurs. L'adresse LAN des lecteurs peut rester vide. Le flasheur accepte aussi les anciennes IP fixes dans **Expert**, pour ne pas modifier les installations existantes.
5. Une fois les lecteurs branchés chez le destinataire, l'APK → **Ajouter un équipement — recherche automatique → Rechercher mes équipements → sélectionner → saisir le code de lecture**. Aucune IP saisie dans le parcours normal. La passerelle VPN conserve son IP Tailscale connue de l'APK : elle n'a pas besoin d'être découverte.

## Protocole local et sécurité

- JK classique : mDNS `smartjk-xxxxxx` sur `_http._tcp` 80, plus annonce UDP 18769.
- Voltronic S3 : mDNS `sevolt-xxxxxx` sur `_http._tcp` 18081, plus annonce UDP 18769.
- Annonce UDP sans secret : `{"proto":"smart-energy-discovery-v1","family":"jk|voltronic","name":"...","id":"...","port":80|18081}`.
- Chaque annonce contient l'IP locale de l'émetteur et une signature HMAC-SHA256 calculée avec le code de lecture sur les champs `protocole|famille|nom|id|port|ip`. La passerelle vérifie d'abord la signature **sans transmettre le code** à l'IP découverte. Elle exige que le datagramme provienne du même sous-réseau et que l'IP signée corresponde à la source. Ensuite seulement elle confirme le produit et l'identité de l'ESP par une requête authentifiée. Une identité différente sous le même nom provoque un blocage conservateur. La sécurité de ce protocole suppose un code de lecture suffisamment long et aléatoire (16–32 caractères recommandés) ; le transport HTTP local reste non chiffré.
- L'APK ne doit pas enregistrer le code avant une réponse authentifiée. Les codes Android existants restent chiffrés sous Android Keystore.
- Les routes de commande JK/Voltronic existantes et leurs conditions d'écriture restent inchangées.
- Un **nom est seulement une étiquette**, et non une preuve d'identité ni une clé d'authentification.
- Réseau invité, isolation Wi-Fi, pare-feu UDP ou sous-réseaux différents peuvent empêcher la découverte automatique. Les IP fixes sont conservées comme secours expert.
- Le VPN fonctionne avec une adresse Tailscale déjà configurée. L'OTA **distante via VPN** n'est pas encore activée par cette modification.

## Ce qui ne change pas

- Lecteurs déjà associés, codes NVS, BMS et journal/flux historique ; mode APP-ONLY sans reconfiguration.
- Possibilité de saisir une IP manuelle en configuration experte Android ou flasheur.
- Voltronic nécessite toujours le MAX3232 RS232 ; JK reste classique et ne devient pas S3.
- Deye est affiché comme **futur type d'onduleur**, mais son firmware est **absent** : le flasheur bloque volontairement son flash.

## À vérifier matériellement

- Première configuration USB après factory (JK, Voltronic, VPN S3) ; USB natif et CH343.
- Découverte automatique après DHCP, redémarrage box et changement IP, 2 lecteurs de même type et même famille.
- Refus code invalide ou nom doublon, blocage d'identité usurpée, aucune divulgation Wi-Fi ou jetons.
- Tailscale maintient ses routes sur l'ESP VPN, lecture JK/Voltronic sans casser les fonctions existantes, puis test OTA uniquement sur matériel de réserve.
