# Smart Energy Android 0.3.21-dev — ouverture et navigation multi-ESP plus rapides

Date : 11 novembre 2026 · Branche `dev` · APK de test `fr.smartenergy.android.vpntrial321`.

## Problème constaté

Sur une installation à deux ESP32 JK et quatre BMS, les lectures arrivaient tard après l'ouverture de l'APK, et lors d'un clic de `Batt-2` vers `Batt-3` (passage ESP1 → ESP2). Une vérification `/info` puis `/bms` précédait la première requête `/telemetry`. Changer d'ESP supprimait l'écran détaillé, alors que le cache des autres lecteurs existait déjà. Enfin, réafficher une ancienne réponse dans `showTelemetry` actualisait par erreur la date de son cache.

## Correction Android uniquement

1. **Lecture rapide au lancement / retour sur l'application** : jusqu'à 3 requêtes GET en parallèle interrogent uniquement les télémétries HTTP déjà mises en cache par les lecteurs JK, indépendamment des vérifications ordinaires d'identité. Les réponses sont acceptées seulement pour un ESP associé, avec son code/token et au moins une MAC JK enregistrée reconnue. La connexion normale /info et /bms continue.
2. **Affichage immédiat lors d'un passage ESP1 ⇄ ESP2** : les données déjà en mémoire (maximum 15 s) sont présentées instantanément, avec mention « dernière lecture… actualisation ». Une nouvelle lecture HTTP du lecteur choisi part immédiatement, sans attendre les étapes de reconnexion classiques.
3. **Données périmées identifiées** : le cache n'est **jamais antidaté** par un simple changement d'onglet. Après 15 s sans nouvelle mesure, les valeurs et alarmes sont masquées comme indisponibles, sans valeur fictive « en ligne ». Un code protégé `newRead` distingue une réponse réseau fraîche d'une réutilisation visuelle.
4. **Protection anti-race** : les requêtes de télémétrie de l'ancien lecteur ne peuvent plus mettre à jour l'écran une fois un autre ESP sélectionné (vérification du slot et d'un numéro de génération). Les requêtes rapides utilisent une photographie des identifiants et de la route au début de la lecture, sans dépendre des champs d'association en cours de modification.
5. **Respect de la route réseau** : la décision LAN/VPN du lecteur rapide suit `JkTransportPolicy.select`. Aucun retour automatique LAN si Tailscale/VPN est choisi. Un VPN qui ne relaie que JK1 ne permet pas magiquement de lire JK2/Jk3 à distance.
6. **Pas d'historique** : une seule photographie **en mémoire vive** par ESP est réutilisée tant que l'application reste chargée ; rien n'est enregistré durablement sur le téléphone. Les fenêtres d'historique/graphes restent absentes.

La fréquence habituelle reste un tick ~2,5 secondes ; aucune modification des firmwares ESP32 JK, ESP32 VPN, Bluetooth, BMS ou paramètres n'a été faite. Toutes les nouvelles requêtes sont des **GET HTTP de caches ESP32** sans demande de nouvelle trame BLE.

## Tests proposés sur téléphone

- Installer cette APK expérimentale à côté de la version fonctionnelle. **Attention : les identifiants des APK d'essai sont différents**, et leurs secrets Keystore ne sont pas automatiquement partagés. Faire l'association propre à la nouvelle version sans effacer l'ancienne ni reflasher les ESP.
- Sur Wi-Fi local, ouvrir l'application et observer en combien de secondes la première batterie puis les 4/4 apparaissent. Comparer à la 0.3.20 uniquement à conditions réseau/BLE comparables.
- Appuyer sur Batt-1, Batt-3, Batt-2, Batt-4. Le changement d'onglet devrait être immédiat avec mesures en mémoire de moins de 15 s, ou afficher un message d'attente si l'ESP n'a pas encore répondu.
- Passer à une batterie d'un ESP puis couper temporairement son réseau pour contrôler la disparition de l'état « en ligne » après 15 s. **Ne pas provoquer volontairement d'alarme ou modifier les protections des BMS.**
- Activer Tailscale et contrôler que les routes du mode VPN restent respectées ; ne pas modifier le firmware VPN opérationnel.
- Vérifier que les réglages BMS restent en lecture/mode modification explicite uniquement. Pas de réglage automatique.

Les tests JVM vérifient les règles de fraîcheur et l'acceptation des MAC appairées ; la compilation GitHub confirme seulement l'absence d'erreurs logicielles, pas les délais réels en Wi-Fi sur l'installation.
