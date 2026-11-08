# Smart Energy Android — architecture multi-onduleurs Voltronic / Deye

État : **étude de compatibilité, non implémentée en production**.
Cette note ne contient aucun détail de l'installation de test, IP, identifiant,
secret ou configuration d'utilisateur.

## But

Une APK Android autonome (sans Home Assistant) accueille au choix :
- un ou plusieurs JK BMS Bluetooth via ESP32 JK ;
- un ou plusieurs Voltronic/Axpert via ESP32 série RS232 ;
- un ou plusieurs Deye hybrides via ESP32 RS485 Modbus RTU ;
- plusieurs équipements de familles différentes dans la même installation.

À domicile : APK -> Wi-Fi local -> ESP32 dédié.
À l'extérieur : APK -> Tailscale Android -> ESP32 VPN -> Wi-Fi local -> ESP32 dédié.
Le VPN fonctionne déjà pour JK ; **les routes Voltronic/Deye restent à coder et tester**.
Pas de cloud du mainteneur, pas de serveur payant et pas de port box à ouvrir.

## Voltronic / Axpert Ultra : banc de test recommandé

- 1 ESP32 DevKit par onduleur (une UART série, Wi-Fi).
- 1 convertisseur **RS232 ↔ UART niveau logique 3,3 V**, p. ex. MAX3232
  avec compatibilité 3,3 V vérifiée ; version isolée recommandée pour montage pérenne.
- 1 câble convenant au **port communication PC** exact du modèle.
- 1 alimentation USB 5 V indépendante, boîtier.
- S'appuyer sur les commandes et garde-fous du projet Voltronic déjà développé
  (QPIGS, QPIGS2 si disponible, QMOD, QPIRI, QDOP, compteurs énergie).
- V1 : lecture seule ; aucune écriture POP/PCP/charges ou seuils autorisée.
- Si le port physique est USB uniquement, un ESP32 classique avec MAX3232
  n'est **pas** un hôte USB ; prévoir ESP32-S3 USB host, pont adapté
  ou un autre connecteur série reconnu.
- Le brochage RJ45 de communication d'un onduleur n'est PAS du réseau Ethernet ;
  vérifier manuel/mesure pour chaque variante et ne rien raccorder au hasard.

## Deye : banc de test recommandé

- 1 ESP32 DevKit par onduleur ; 1 convertisseur RS485 ↔ UART,
  de préférence **isolé** et compatible 3,3 V ; alimentation USB 5 V.
- Raccorder au port Modbus RS485 que **le manuel du modèle précis**
  autorise pour la supervision ; les ports METER et BMS/CAN ont des fonctions
  et brochages différents selon la gamme. Ne pas débrancher les équipements
  de mesure ni ajouter un deuxième maître Modbus au hasard sur le bus.
- Modbus RTU : lecture de registres de maintien par **FC03 uniquement**.
  Ne pas compiler les codes FC06/FC16 (écriture).
- Définir des profils séparés par famille/modele et firmware :
  ex. SUN-SG05LP1 (monophasé) et SUN-SG04LP3 (triphasé).
  Le bon registre, échelle, signe, ordre des mots et adresse esclave
  exigent une validation sur le modèle réel.
- Selon le modèle, un datalogger peut offrir une interface TCP locale ;
  c'est une option à étudier, non une garantie de compatibilité universelle.

## API/UX génériques proposées

API locale read-only sur chaque ESP32 :
- GET /api/v1/info : device_id + marque/profil/firmware/état
- GET /api/v1/telemetry : PV, batterie, réseau, charge, températures,
  compteurs, alarmes, fraîcheur des données, champs optionnels
- GET /api/v1/details : états techniques et paramètres lus (aucune écriture)

Une couche normalise des mesures du fabricant vers des clés communes
mais conserve les champs spécifiques sous un sous-objet namespacé.

Dans l'APK : « Ajouter un équipement » -> JK / Voltronic / Deye,
détection Wi-Fi, association avec identification persistante.
Vue par onduleur et synthèse multi-appareils. Les cartes et alarmes ne
doivent jamais fabriquer une valeur indisponible.

Dans ESP32 VPN : conserver les routes allowlistées et des cibles
configurables **sans reflasher la clé Tailscale ni exposer des écritures**.
Le prototype actuel a un relais JK fonctionnel mais un routage Voltronic
seulement esquissé et aucun décodeur Deye effectif.

## Sécurité et limites de matériel

- La partie communication n'est pas une borne d'alimentation :
  ne jamais alimenter l'ESP32 depuis un port série d'onduleur sans preuve
  d'une sortie protégée appropriée ; préférer un bloc USB 5 V indépendant.
- MAX3232 n'est **pas** RS485, et SP3485 n'est **pas** RS232.
- Pour les ports de puissance et les installations à forte perturbation,
  utiliser des transceivers et des alimentations avec isolation galvanique.
- Ne pas modifier les protections d'onduleur/BMS depuis une première version.
- Éviter l'exposition du port série/IP local à Internet ; tunnel Tailscale
  pour l'accès distant.

## JK V19 identité 0x03 — suivi indépendant

Le lecteur valide la trame JK02 300 octets et cherche identité aux offsets
6:21, matériel 22:29, logiciel 30:37 (conformes à la référence syssi).
Le firmware actuel peut rejeter des trames à CRC valide mais autre disposition.
Il faut observer uniquement les métadonnées **non sensibles** :
info.available, info.reason et info.unrecognized_frames depuis
/api/v1/details avec le code d'accès JK local.
Aucune trame brute : elle peut renfermer des passcodes (62–77, 118–133).
Si l'identité reste rejetée, implémenter une classification locale contrôlée
des champs, puis parser la variante réellement vérifiée sur matériel,
sans envoyer les secrets ni nom/numéro de série en clair.

## Références publiques

- Voltronic Axpert Ultra : https://voltronicpower.com/content/download/Brochure/Axpert_Ultra.pdf
- JK02 BLE : https://github.com/syssi/esphome-jk-bms/blob/main/docs/protocol-design-ble.md
- Deye modèles/manuels : https://deye-inverters.com/fr/downloads/hybrid/
- Exemples de lecture Modbus locale : https://github.com/VMrenato/homeassistant-deye-tcan485-esphome
