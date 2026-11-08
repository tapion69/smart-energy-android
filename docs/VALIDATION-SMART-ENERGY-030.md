# Smart Energy Android / JK ESP32 0.3.0-dev — plan de validation terrain

**Date : 2026-11-08** · **Branche : `experiment/smart-energy-v0.3.0-multibms`**.
Version expérimentale **non stable**. Ne pas recommander à tous les utilisateurs tant que les tests ne sont pas satisfaisants. L'ESP32 VPN existant reste inchangé et en lecture seule.

## Préalable : récupérer le lecteur JK actuellement bloqué

Le remplacement du code 8–11 caractères au redémarrage dans le firmware précédent est un bug confirmé par inspection des sources ; le firmware 0.3.0 ne peut pas restaurer un secret NVS déjà remplacé.

1. Ne pas faire d'Erase Flash, ne pas flasher un fichier factory, ne pas effacer NVS.
2. Retrouver **localement** le code WPA2/API actif du lecteur JK à l'aide d'un terminal série USB 115200 baud (sans divulguer le code dans GitHub/chat) ou par une procédure de récupération physique alternative sécurisée si disponible.
3. Depuis Smart Energy, rétablir le SSID **2,4 GHz** de la box, le bon mot de passe Wi-Fi et l'adresse IP du lecteur JK. Confirmer la réception de données.
4. Seulement ensuite sauvegarder la configuration et envisager le flash APP-ONLY 0.3.0, à **0x10000 sans Erase Flash**, sur le lecteur JK uniquement.

## Installation APK d'essai

- Les ZIP d'artefacts sont dans GitHub Actions (workflow `build-android-vpn.yml`, branche 0.3.0). Vérifier que les jobs APK et JK ESP32 sont **verts sur le même commit**.
- L'APK expérimentale `vpntrial30` s'installe **à côté** de `vpntrial24`. L'ancienne app reste intacte. Le nouvel essai demande une association locale une fois récupéré le code du lecteur JK.
- Ne pas installer le firmware VPN actuel à partir du workflow : ce projet d'essai ne l'a pas modifié.

## Étape 1 : un seul BMS

- Relever modèle, hardware, software, SOC, tension, A, W, cellules min/max, températures et état alarmes ; comparer aux valeurs du module HA.
- Vérifier la conservation du Wi-Fi, de l'association, des valeurs BMS et du code après arrêt/redémarrage normal.
- Avec **aucune modification** de réglage, observer les reconnexions et bips.

## Étape 2 : deux BMS simultanés

- Enregistrer deux JK. Dans la même minute, les deux doivent être `ble_connected` et `available` ; aucune rotation 90 s.
- Vérifier que les valeurs, cellules et erreurs ne sont pas échangées entre appareils.
- Tester une perte BLE du premier BMS : le second reste suivi. Reconnecter ensuite.
- Observer pendant une journée si possible.

## Étape 3 : trois, puis quatre BMS

- Ajouter un JK à la fois. Vérifier la mémoire disponible, le Wi-Fi, les temps de rafraîchissement, les bips, l'absence de blocage et la persistance de chaque connexion.
- Vérifier les onglets Accueil et JK 1/2/3/4. Le SOC global n'est affiché que si toutes les valeurs sont fraîches ; il est pondéré par les capacités si elles sont disponibles, ou calculé en moyenne simple explicitement identifiée.
- Les autres valeurs globales sont des **moyennes** des appareils en ligne, pas des totaux électriques.
- Simuler/observer une vraie alarme uniquement si les conditions de test sont sûres ; vérifier le BMS concerné. Ne pas provoquer d'alarme de sécurité pour tester l'interface.
- Observer 48–72 h si possible avant de qualifier 4 connexions de stables.

## Étape 4 : paramètre pilote local (facultatif et seulement après stabilité)

- Les modifications sont **désactivées par défaut** : seuls 5 réglages à faible criticité sont disponibles : `display_always_on`, `smart_sleep_enabled`, `timed_stored_data`, `charging_float_mode`, `smart_sleep_delay_h`.
- Le firmware 0.3.1 accepte un code personnel ASCII **de 8 à 32 caractères** pour armer les écritures locales pilotes. Privilégier un code difficile à deviner. Les protections restent verrouillées.
- Une seule batterie, une seule commande ; confirmer ancienne/nouvelle valeur dans l'APK. Aucun ordre automatique, pas de modification groupée.
- Observer `queued` puis `confirmed` **uniquement après nouvelle trame de paramètres vérifiée**. En cas de `mismatch`, `send_failed`, `timeout_unverified`, `link_lost_unverified`, s'arrêter et lire le BMS manuellement, sans répéter la commande à l'aveugle.
- Les protections cellule, courant, température, MOS, calibration SOC ne sont pas modifiables dans cette version de test.

## Tailscale et évolution nécessaire

Le second ESP32 VPN **n'a pas changé** : il relaie la lecture (GET) mais **aucun POST**. La lecture peut être désynchronisée si le code JK a été renouvelé depuis son ancien flash (le code du lecteur JK est actuellement compilé dans le firmware VPN).

Pour modifier les paramètres depuis l'extérieur, il faudra adapter ce relais sur une branche distincte (routes POST strictement listées, identifiant JK sécurisé, code renouvelable, autorisation limitée, protection anti-rejeu, audit) et valider le nouvel ESP32 VPN sur matériel. Ne pas tenter d'envoyer des écritures par des routes GET ou par un serveur exposé à Internet.

**Critère de sortie stable :** jobs GitHub verts, tests 1→4 BMS réussis, aucune perte de BMS, pas de faux succès sur les écritures et récupération Wi-Fi/code validée.

## Étape 0 — première association ou code oublié sans USB série (0.3.1)

- Avec firmware antérieur déjà installé, flasher `firmware.bin` de la 0.3.1 sur le lecteur JK uniquement, APP-ONLY à `0x10000`, **sans effacement NVS**. Cela exige un moyen de flash, mais pas la récupération du code initial.
- BOOT 4 s sur un ESP32 déjà allumé ; l'APK rejoint le Wi-Fi temporaire ouvert, un seul client, au plus 120 s.
- Saisir le nouveau code (8–32 caractères) et, seulement pour une installation vierge ou changement de réseau, les identifiants du Wi-Fi 2,4 GHz.
- L'APK prépare l'opération ; BOOT 2 s **après la demande**, relâcher ; confirmer dans l'APK. Vérifier code enregistré et retour Wi-Fi, sans perte des JK.
- Tester code 8, 11, 12, 20 caractères et redémarrage ; vérification de l'état des paramètres BMS et du VPN.
- Sécurité radio : mécanisme **expérimental**, AP brièvement ouvert, pas de chiffrement applicatif sur les secrets. Validation de résistance aux attaques MITM nécessaire avant stable.
