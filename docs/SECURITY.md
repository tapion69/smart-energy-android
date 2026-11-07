# Sécurité et règles de publication

Ce projet surveille des installations électriques et des batteries : en v0.1 **lecture seule des paramètres critiques**.

## Configuration initiale

- Le provisionnement Wi-Fi et l'ajout d'un BMS sont autorisés seulement durant un appairage explicite avec preuve de possession locale.
- Pas de mot de passe universel publié ni de point d'accès de configuration permanent non protégé.
- Secrets Wi-Fi, tokens et identifiants de passerelle ne doivent pas remonter sur GitHub, dans les logs diagnostics ni dans les statistiques.
- Des MAC peuvent être nécessaires au routage BLE mais ne sont pas à publier dans les exemples.

## Application et télémétrie

- En cas de liaison absente ou de mesure ancienne : afficher la date de dernière réception et « hors ligne », jamais conserver silencieusement une valeur comme actuelle.
- Les commandes d'écriture dangereuses ne font pas partie de la v0.1.
- Ne pas exposer l'API de la passerelle sur Internet par redirection de port.
- Prévoir authentification locale liée à la passerelle et stockage protégé des secrets côté Android.

## OTA firmware

- Manifestes de publication distincts JK / Voltronic.
- Vérifier modèle de carte, version, taille, hash SHA-256 et **signature de publication** avant installation.
- Lorsque le partitionnement le permet, prévoir deux partitions OTA et un rollback après échec de validation du nouveau firmware.
- Ne pas effacer la configuration NVS lors d'une mise à jour normale.
- Aucune OTA silencieuse ni installation automatique ; l'utilisateur décide.

## APK

- Utiliser un identifiant Android stable, un code de version monotone et la même clé de signature d'une release à l'autre.
- Annoncer la disponibilité d'une nouvelle version, permettre « télécharger », « plus tard » et ignorer cette version.
- Le téléchargement et la proposition d'installation nécessitent une action explicite.
- Conserver la clé de signature en dehors du dépôt et prévoir une stratégie de sauvegarde.

## Accès extérieur

Nécessite une architecture relais spécifique, non disponible à ce stade. Chiffrement, authentification, appairage d'appareils, journalisation raisonnable et révocation de l'accès seront obligatoires.
