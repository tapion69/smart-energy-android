# Smart Energy Android — lecture simultanée multi-JK et réglages locaux / Tailscale

Statut : **feuille de route**, aucune écriture BMS ni connexion multi-client activée à ce stade.
Branche de développement : `experiment/esp32-vpn-gateway`.
Ce document est générique : aucune IP, MAC, clé, donnée de batterie ou identifiant utilisateur n'est conservé ici.

## Objectif

- Un même ESP32 classique (ESP32 DevKit, Wi-Fi + BLE) lit en continu **2, puis 3, puis 4 BMS JK**.
- Les données de tous les JK restent visibles dans l'APK simultanément, avec fraîcheur indépendante.
- Chaque BMS a une session BLE persistante; aucune rotation toutes les 90 secondes.
- Plus tard, l'utilisateur peut modifier *manuellement*, sur son LAN privé **et via Tailscale depuis l'extérieur**, uniquement des paramètres dont le registre et la plage sont officiellement compatibles et vérifiés.
- Une déconnexion BLE ou un échec d'écriture n'altère pas les autres batteries, la configuration Wi-Fi ni les autres passerelles.

## Capacité réelle, sans promesse prématurée

La pile ESP-NimBLE de l'ESP32 classique permet de **configurer** plusieurs connexions centrales (limite déclarée jusqu'à 9, défaut souvent 3). Ce n'est pas une garantie applicative: ressources RAM, contrôleur, Wi-Fi 2,4 GHz, fréquence des notifications GATT et comportement de chaque BMS limitent la stabilité.

Cible **de test** = 4 connexions simultanées; cible **initiale de production** = 2 ou 3 validées. Si 4 ne tiennent pas en continu, proposer plusieurs ESP32 JK par installation, au lieu de détériorer la fréquence et la fiabilité des mesures.

Ne pas confondre `MAX_BMS=8` actuel (adresses sauvegardées, lues *en rotation*) avec une capacité de 8 connexions simultanées.

## Refactorisation nécessaire

Le prototype actuel a `activeIndex`, `activeMac`, `assembler` et `radioMutex` globaux : il ne peut pas faire recevoir correctement plusieurs clients en parallèle.

1. Chaque JK a sa session dédiée : `NimBLEClient`, notifications et `smartjk::Assembler` indépendants, compteurs et timestamps isolés.
2. Associer chaque notification au bon JK par le contexte de sa session; ne jamais router par une variable `activeIndex` globale.
3. Gérer les clients sans blocage d'une session par une autre; scans et reconnexions coordonnés sans interrompre systématiquement les liens existants.
4. Sérialiser les commandes **par BMS** et limiter la découverte active, pas les mesures régulières.
5. Lire les trames 0x01/0x02/0x03 avec les décodeurs et contrôles CRC déjà validés; ne pas retoucher les offsets pour ajouter le multi-BMS.
6. Mesures `available` par BMS, état indépendant, `age_seconds`, `connections`, `disconnections`, `last_error`, `free_heap` global; jamais de valeur ancienne présentée comme actuelle.
7. Exposer la liste des BMS dans l'API et permettre leur choix individuel dans l'APK; empêcher toute confusion entre une MAC et les commandes d'un autre BMS.
8. Conserver un chemin retour vers le firmware JK stable mono-client et la conservation de NVS lors de la mise à jour.

## Plan de validation

- Tests unitaires : trames fragmentées et entrelacées de plusieurs BMS, identification correcte, reconnexions isolées, aucune fuite entre décodeurs.
- 2 BMS : tests au moins 24 h, vérifier fraîcheur, charge Wi-Fi, mémoire libre, bips, reconnexions et absence d'altération des réglages.
- 3 BMS puis 4 BMS : observation étendue (idéalement 48 à 72 h), y compris sur signal BLE faible, redémarrage réseau, perte d'un BMS, maintien des autres.
- Seuil de sortie : données disponibles pour tous sur la durée, absence de crash, absence de saut de batterie dans l'APK, reconnexions isolées et consommation RAM acceptable. Ne pas promettre de cadence exacte avant mesure.
- Compter les bips mais distinguer les bips de reconnexion de ceux des requêtes 0x96/0x97 ou des événements internes du JK.

## Réglages JK : implémentation par étapes

Source de référence : `tapion69/smart-jkbms-bluetooth/smart-jkbms-bluetooth/ble/parameters.py` (catalogue de registres et `build_write`), `ble/control.py` et `ble/mqtt_ha.py` (autorisation locale, débit limité, confirmation par relecture).

1. **Lecture seule conservée par défaut.** Modèle et version vérifiés, trame 0x01 CRC contrôlée, paramètres lus récemment et plages validées. Les registres supportés varient selon la famille et le firmware. Jamais de registre inconnu.
2. **Autorisation locale distincte de l'appairage et du VPN.** Confirmation explicite, possibilité d'autorisation temporaire par BMS et blocage immédiat sur perte BLE. HTTP local non chiffré reste un prototype et nécessite une solution sécurisée avant distribution large; accès VPN en lecture seule à ce stade.
3. **Écriture unique et ciblée** sur un seul BMS, avec ancien et nouveau réglage visibles, type/unités/plages/échelle vérifiés avant envoi; un seul ordre en vol par BMS, délai minimal et refus des doublons.
4. Requête de relecture 0x96 après l'écriture, puis contrôle d'une **nouvelle trame 0x01** CRC-validée, pas du cache précédent. `confirmed` uniquement si la valeur relue correspond. Sinon afficher `non_confirmé` avec état d'échec ou inconnu sans retenter automatiquement.
5. Plages de sécurité cohérentes entre paramètres (p. ex. UVP < UVPR < OVP) avant action; tests spécifiques sur un JK dédié, et pas de désactivation automatique des protections.
6. D'abord champs non critiques compatibles avec le modèle réellement identifié; ensuite seuils de protection, MOS, température et calibration SOC séparément, avec permissions renforcées, validation matérielle et avertissements spécifiques. L'expérience HA montre qu'une trame acceptée par le transport **ne prouve pas** que le BMS a appliqué le changement.
7. Pas de modification de groupe ni de presets distribués à plusieurs BMS. **Écriture distante via Tailscale = objectif requis**, à activer uniquement après authentification de commande, autorisation temporaire explicite par BMS et audit des écritures.

## Écriture des paramètres à distance via Tailscale (objectif validé)

La technologie VPN n'est **pas** le point bloquant : les paquets peuvent transporter des POST autant que des GET. **Notre ESP32 VPN actuel est, lui, volontairement limité aux GET** (voir `firmware/vpn-gateway/main/gateway_policy.h` et `main.c`), ce qui rend les écritures impossibles aujourd'hui par conception. De plus, le dernier saut ESP32 VPN → lecteur JK se fait en HTTP non chiffré sur le LAN.

Plan d'implémentation :

1. Le **lecteur JK** expose une route d'écriture dédiée par BMS et **ne modifie plus rien** sans identité du BMS vérifiée, valeur fraîche, validation des unités/plages et permission temporaire distincte de la simple lecture.
2. La **passerelle Tailscale** ajoute une liste stricte de routes POST spécifiquement autorisées (jamais de proxy URL arbitraire). Authentifier le client, séparer la permission de lecture du droit de modifier, rejeter les commandes hors VPN, inclure une protection anti-rejeu et des limites de cadence.
3. Le segment local entre passerelle et lecteur JK doit lui aussi authentifier chaque commande et être suffisamment protégé contre les interceptions/rejeux; la protection du tunnel Tailscale ne couvre pas automatiquement ce segment Wi-Fi. Prévoir chiffrement de bout en bout ou une preuve de commande liée à un nonce à courte durée avec secrets provisionnés de manière sûre; choisir la solution après tests de mémoire/CPU.
4. Dans l'**APK Android**, choix explicite du BMS puis du paramètre, affichage ancienne/nouvelle valeur, avertissement adapté, confirmation avant envoi. Le token VPN existant ne doit jamais suffire seul à autoriser une écriture.
5. Une requête acceptée par le réseau **ne signifie pas** une modification confirmée. Après l'écriture, nouvelle trame `0x01` CRC-vérifiée du même BMS et comparaison de la valeur; en cas de perte BLE ou de réponse ambiguë, afficher `non confirmé` et ne pas rejouer automatiquement.
6. Journal côté client et passerelle **sans secrets ni trames brutes**. Interdire les écritures simultanées vers plusieurs BMS, les commandes critiques non compatibles avec un firmware donné, et toute écriture automatique.
7. Tester d'abord en local, puis via le VPN sur 4G/5G. Ne pas activer les routes distantes sur les installations existantes avant validation d'autorisation, de révocation, de reprise et de non-régression.

## Proposition d'interface multi-BMS

- Accueil : récapitulatif de 4 BMS (SOC, tension, courant, puissance, température, dernière réception et état BLE), capacité et SOC global calculés selon les capacités connues; aucune donnée obsolète présentée comme actuelle.
- Navigation : onglets (ou barre horizontale mobile) par BMS; sous-onglets « Mesures », « Cellules », « Paramètres », « Informations ».
- Paramètres : lecture seule par défaut, changement manuel ciblé et statut de relecture, journal d'audit. Les autorisations local/Tailscale sont visibles et contrôlées séparément.
- Une maquette HTML indépendante, sans accès réseau ni commandes matérielles, a été préparée pour valider la présentation avant développement natif Kotlin.

## Non-régression et séparation des produits

- L'APK Android actuelle ne doit pas perdre ses données, le firmware ESP32 JK connu ne doit pas perdre le Wi-Fi ni les adresses enregistrées.
- Le deuxième ESP32 VPN reste inchangé et en lecture seule.
- Les autres modules du dépôt (Voltronic/Deye) suivent leurs protocoles et permissions propres; jamais de mélange de commandes.
- Aucun test automatique n'envoie de commandes d'écriture à une batterie réelle.
