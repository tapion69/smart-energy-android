# Journal des modifications

## 0.1.8-dev — récupération physique sans ancien code (2026-11-07)

- ESP32 : confirmation par BOOT 4 secondes et fenêtre d'association LAN de 45 secondes ; aucun code ancien révélé par le réseau.
- API de réassociation limitée dans le temps, un seul changement de code par confirmation, sans effacer Wi-Fi ni JK.
- APK : bouton « Code perdu ? Réassocier avec BOOT », générateur sécurisé de nouveau code, sauvegarde Android Keystore après vérification.
- BOOT maintenu 10 secondes ouvre désormais le mode point d'accès ; l'usage du bouton change à partir de ce firmware.
- Requiert un premier flash pour les anciennes versions : **pas d'OTA**. Fonction test local seulement, **HTTP non chiffré**.
- Les APK debug ne sont pas encore signées avec la clé permanente de production.

## 0.1.6-dev — première télémétrie JK Bluetooth autonome (2026-11-07)

- Acquisition Bluetooth GATT `FFE0/FFE1` en tâche indépendante ESP32, requêtes de lecture `0x97/0x96` uniquement.
- Assembleur JK02 (trames 300 octets, CRC) + décodeur provisoire des profils 24S/32S avec validations strictes.
- API authentifiée `/api/v1/telemetry`, vérification de fraîcheur, états d'erreur et lecture des BMS sauvegardés.
- APK : tableau de bord des tensions/courant/SOC/températures/cellules, sans valeurs fictives, rafraîchissement automatique.
- Tests unitaires synthétiques intégrés à GitHub Actions ; **validation BLE sur matériel réel encore nécessaire**.
- Aucun réglage batterie modifié, aucune OTA ou publication grand public.

## 0.1.3-dev — correction enregistrement Wi-Fi (2026-11-07)

- Corrige le bouton Wi-Fi qui restait désactivé après la connexion au LAN.
- Affiche les erreurs et confirmations directement sous le formulaire Wi-Fi.
- Autorise la reconfiguration Wi-Fi sur le réseau local de confiance avec authentification par code déjà associé (firmware 0.1.3-dev).
- Vérifie l'identité/version de l'ESP32 avant l'envoi d'une modification Wi-Fi.
- Aucune nouvelle fonction de télémétrie JK ni d'OTA dans cette version.
- Builds APK et firmware validés par GitHub Actions ; validation matérielle à effectuer.

## Non publié — initialisation du dépôt (2026-11-07)

- Structure séparée Android / firmware JK / firmware Voltronic / mises à jour.
- Architecture et critères de validation du premier prototype.
- Manifestes de mise à jour désactivés jusqu'à une véritable publication.
- Aucun firmware, APK ou accès distant annoncé comme disponible.

## 0.1.0-dev — code source initial (2026-11-07)

- Assistant Android Studio : connexion au point d'accès ESP32, découverte BLE, sélection et enregistrement de BMS, Wi-Fi maison.
- Firmware ESP32 Arduino/PlatformIO : point d'accès unique protégé, stockage persistant, scan BLE et API de configuration.
- Code source uniquement ; **ni compilation ni tests matériels validés**, aucune valeur JK affichée et aucune OTA active.
