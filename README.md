# Smart Energy Android

**Projet en développement — aucune APK ni aucun firmware Smart Energy n'est encore publié.**

Application Android autonome (sans Home Assistant) pour consulter les données des **JK BMS** et des **onduleurs Voltronic/Axpert** par l'intermédiaire de passerelles ESP32 dédiées.

## Objectif utilisateur

Après un premier flash USB du firmware sur un ESP32 vierge (ou avec une passerelle préprogrammée), **tout doit être configurable depuis l'application Android** :

1. Détecter et associer sa passerelle de façon sécurisée.
2. Configurer ou modifier son Wi-Fi sans YAML ni recompilation.
3. Pour JK : rechercher les BMS en Bluetooth, sélectionner un ou plusieurs appareils, les nommer, enregistrer leur configuration dans l'ESP32.
4. Pour Voltronic : régler les liaisons série prises en charge et sélectionner les onduleurs.
5. Consulter les vraies mesures, savoir si les données sont fraîches ou périmées, diagnostiquer les pertes de connexion.
6. Installer **sur demande** les mises à jour de firmware ESP32 (OTA) et être prévenu de la disponibilité d'une nouvelle APK.
7. Plus tard, accéder aux mesures hors du domicile via un **relai Internet sécurisé** (sans ouvrir les ports RS232/TCP de la box).

## Organisation

| Dossier | Rôle |
| --- | --- |
| `android/` | Projet Android Studio natif Kotlin et parcours de configuration |
| `firmware/jk-esp32/` | Passerelle ESP32 BLE autonome pour un ou plusieurs JK |
| `firmware/voltronic-esp32/` | Passerelle ESP32 RS232 autonome, séparée du JK |
| `updates/` | Manifestes de publication et règles de mise à jour |
| `docs/` | Architecture, sécurité et étapes de validation |

## Décisions

- **Un ESP32 par système** : JK BLE et Voltronic RS232 ne partagent pas la même passerelle. Plusieurs ESP32 du même type pourront être associés à une installation.
- **JK d'abord**, puis Voltronic. Une installation sans Voltronic peut utiliser JK seul, et inversement.
- Application **en lecture seule** pour le premier cycle de tests. Aucun changement de paramètres critiques de batterie ou d'onduleur.
- **Aucun état simulé présenté comme une mesure réelle**. Un équipement absent affiche « non connecté », « non disponible » ou des données datées.
- **Aucune dépendance obligatoire** à Home Assistant, Node-RED ou MQTT dans l'installation finale.
- **Aucune donnée secrète** (Wi-Fi, MAC personnelles, tokens, clés OTA) dans les fichiers publiés.

## État actuel

Ce dépôt contient **le socle documentaire et les contrats de développement uniquement**. La configuration dynamique du JK, l'appairage, l'APK compilable, l'OTA et l'accès distant restent à implémenter et à tester sur matériel réel.

➡️ Voir [feuille de route](docs/ROADMAP.md), [architecture](docs/ARCHITECTURE.md) et [sécurité](docs/SECURITY.md).

Les projets Home Assistant existants ainsi que WaterVan ne sont pas modifiés par ce dépôt.
