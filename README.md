# Smart Energy Android

**Projet en développement — un premier code source de test est présent, mais aucune APK de release ni firmware validé n'est publié.**

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

Ce dépôt contient maintenant **un premier prototype de configuration JK** : application Android Kotlin (`android/`) et firmware ESP32 (`firmware/jk-esp32/`). La configuration Wi-Fi et la liste de MAC sont codées, mais **les deux projets compilent via GitHub Actions, mais les tests matériels restent à réaliser**. Le protocole de lecture des valeurs JK, l'OTA, l'appairage grand public et l'accès distant restent à implémenter.

**Essai développeur :** voir [guide Android Studio](android/README.md) et [guide de flash ESP32](firmware/jk-esp32/README.md).

[Compilation automatique de développement (GitHub Actions)](https://github.com/tapion69/smart-energy-android/actions/workflows/build-dev.yml) : lorsqu'une compilation réussit, les fichiers de test apparaissent dans les *Artifacts* du workflow ; **ce ne sont pas des Releases**.

➡️ Voir [feuille de route](docs/ROADMAP.md), [architecture](docs/ARCHITECTURE.md) et [sécurité](docs/SECURITY.md).

Les projets Home Assistant existants ainsi que WaterVan ne sont pas modifiés par ce dépôt.


## Développement actif — Smart Energy Android

**Branches GitHub :** `main` (référence stable conservée) et `dev` (travail courant). L'historique de chaque branche expérimentale supprimée a été sauvegardé sous un tag `archive/2026-11-09/*`, sans perte de commits.

- Application Android expérimentale **0.3.12-dev** : onglet configuration distinct, trois lecteurs JK sélectionnables (deux BMS maximum par lecteur), renommage de batteries et activation encadrée des modifications de paramètres.
- Firmware JK **0.3.11-dev** : récupération automatique des sessions BLE figées, test prolongé avec deux BMS encore nécessaire.
- Firmware VPN expérimental **0.2.0-dev-multi3** : jusqu'à trois lecteurs JK sur le LAN, GET uniquement, configuration personnelle locale nécessaire ; ne remplace pas le VPN actuellement installé sans test.
- **Flasheur Windows `.exe`** : source dans `tools/windows-flasher`, construit via GitHub Actions. JK APP-ONLY `0x10000` et construction/flash VPN avec paramètres propres à l'utilisateur. Usage expérimental ; voir les prérequis dans le README du flasheur.

[Plan de reprise, limites et étapes](docs/ARCHITECTURE-DEV-3-ESP32.md) · [Flasheur Windows](tools/windows-flasher/README.md)


### État de la branche dev — 0.3.13

La vue globale Android regroupe maintenant les batteries des trois lecteurs JK ; Debug est désactivé par défaut. Le firmware JK 0.3.13 interroge ses paramètres toutes les 30 min, à la demande avant édition et immédiatement après écriture pour confirmer. Le flasheur Windows ne nécessite plus Git pour récupérer TailTiny ; **ESP-IDF reste exigé pour compiler le VPN avec les identifiants personnels**. S3 N16R8 : compatibilité matérielle plausible pour de nouveaux développements, aucune image S3 validée à ce stade.
