# Feuille de route et critères de validation

| Étape | Livrable | Critère d'acceptation | Statut |
| --- | --- | --- | --- |
| 0 | Dépôt structuré et contrat produit | README, architecture, sécurité, canaux de mises à jour | En cours |
| 1 | Firmware JK autonome | ESP32 se configure sur Wi-Fi, scanne le JK, sauvegarde une MAC sans recompiler | **Code initial disponible, compilation et tests matériels non réalisés** |
| 2 | APK Android Studio | Détection/appairage ESP32, Wi-Fi, recherche BLE par l'ESP32 et sélection JK dans l'APK | Non commencé |
| 3 | Télémétrie réelle | SOC, V, A, cellules, alarmes, valeurs datées sur un JK V19 et reconnexion après coupure | Non commencé |
| 4 | Plusieurs JK | Deux puis trois JK et, si nécessaire, plusieurs ESP32 ; stabilité et consommation mesurées | Non commencé |
| 5 | OTA ESP32 | Mise à jour demandée dans l'APK, vérifiée, configuration conservée et reprise contrôlée | Non commencé |
| 6 | Mise à jour APK | Détection de release signée, affichage nouveautés, téléchargement/installation sur consentement | Non commencé |
| 7 | Voltronic | Firmware RS232 autonome, un puis plusieurs onduleurs, sélection dans l'APK | Non commencé |
| 8 | Accès extérieur | Authentification, tunnel sortant chiffré, révocation, aucun port box ouvert | Non commencé |

## Conditions pour qualifier la « v0.1 testable »

- APK **compilée et installée**, firmware **compilé et flashé**, compatibilité ESP32 vérifiée.
- Configuration initiale du Wi-Fi **dans l'application**.
- Scan des JK effectué sur **l'ESP32**, avec ajout/suppression sans recompilation.
- Données JK **effectivement reçues**, et aucune simulation masquée.
- Perte Bluetooth, coupure Wi-Fi, redémarrage ESP32 : états cohérents, reconnexion.
- Aucune écriture de paramètres JK.
- **Infrastructure OTA préparée** mais publication activée uniquement après test d'intégrité et de récupération.

Le prototype statique YAML + lecteur HTTP précédent est conservé hors de la version cible : il ne répondait pas au besoin de configuration depuis l'APK.

## Matériel de test

- Un ESP32 **supplémentaire**, pas le proxy Home Assistant en fonctionnement.
- Un JK V19 à portée BLE (puis jusqu'à trois).
- Un téléphone Android et Android Studio.
- Ne pas supposer le modèle exact de carte avant de vérifier sa référence.

## État de développement au 7 novembre 2026

- Première application Android Kotlin avec connexion Wi-Fi Android au point d’accès de l’ESP32, scan BLE, sélection d’adresses, configuration Wi-Fi maison.
- Firmware ESP32 PlatformIO dédié, mode setup protégé par un code unique fourni via le moniteur série USB pour les premiers tests.
- **Aucun firmware ou APK compilé, flashé ni validé en conditions réelles à ce stade**.
- Scan BLE = tous les appareils détectés, pas encore reconnaissance certifiée JK. Les mesures ne sont pas décodées.
- La première version grand public exigera un provisionnement sans PC après flash d’usine ou fourniture d’un code par QR.
