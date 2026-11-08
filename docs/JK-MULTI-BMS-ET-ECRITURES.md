# Smart Energy Android — lecture simultanée multi-JK et réglages locaux

Statut : **feuille de route**, aucune écriture BMS ni connexion multi-client activée à ce stade.
Branche de développement : `experiment/esp32-vpn-gateway`.
Ce document est générique : aucune IP, MAC, clé, donnée de batterie ou identifiant utilisateur n'est conservé ici.

## Objectif

- Un même ESP32 classique (ESP32 DevKit, Wi-Fi + BLE) lit en continu **2, puis 3, puis 4 BMS JK**.
- Les données de tous les JK restent visibles dans l'APK simultanément, avec fraîcheur indépendante.
- Chaque BMS a une session BLE persistante; aucune rotation toutes les 90 secondes.
- Plus tard, l'utilisateur peut modifier *manuellement*, sur son LAN privé, uniquement des paramètres dont le registre et la plage sont officiellement compatibles et vérifiés.
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
7. Pas de modification de groupe, pas de presets distribués à plusieurs BMS, pas d'API WAN d'écriture tant que transport authentifié et chiffré, permission et audit ne sont pas implémentés.

## Non-régression et séparation des produits

- L'APK Android actuelle ne doit pas perdre ses données, le firmware ESP32 JK connu ne doit pas perdre le Wi-Fi ni les adresses enregistrées.
- Le deuxième ESP32 VPN reste inchangé et en lecture seule.
- Les autres modules du dépôt (Voltronic/Deye) suivent leurs protocoles et permissions propres; jamais de mélange de commandes.
- Aucun test automatique n'envoie de commandes d'écriture à une batterie réelle.
