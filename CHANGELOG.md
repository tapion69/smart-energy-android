# Journal des modifications

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
