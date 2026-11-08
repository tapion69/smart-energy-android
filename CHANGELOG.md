# Journal des modifications

## 0.2.3-dev ESP32 JK — identité vérifiée et diagnostic de trames (2026-11-08)

- Identité BMS: validation supplémentaire du cache avant réponse API, pas de modèle absent avec `available=true`.
- Alignement sur le module JK Bluetooth HA : suppression du padding ASCII des champs reçus et conversion dynamique de date YYMMDD en YYYY-MM-DD (sans valeurs de BMS codées en dur).
- Tests multi-modèles synthétiques pour vérifier la récupération dynamique de modèles, versions, noms et dates différents.
- Diagnostics strictement non sensibles `identity_decoder_revision`, `decoder_revision`, `identity_integrity_ok`, `type3_frames_seen`, `last_rejection` et `unrecognized_frames`. Aucun contenu brut de trame 0x03, mot de passe ou numéro de série dans ces diagnostics.
- Contrôle borné des chaînes pour éviter toute lecture mémoire hors limites si un enregistrement en cache est altéré.
- Tests de régression pour identités JSON incohérentes, versions incorrectes, cache sans modèle et chaînes non terminées.
- Modifie uniquement le lecteur ESP32 JK et ses tests/docs ; l'application Android 0.2.4, les données 0x01/0x02 et le deuxième ESP32 Tailscale ne changent pas.
- Correctif conservateur : **ne reconstitue pas encore** les véritables champs d'une variante de trame 0x03 inconnue. Ne flasher l'APP-ONLY qu'après validation CI et vérification de sa configuration.

## 0.2.4-dev Android + 0.2.2-dev JK ESP32 — correctifs cellules/alarmes et identité (2026-11-08)

- **APK Android** : corrige les couleurs des cellules : une seule cellule rouge (minimum) et une seule bleue (maximum), déterminées par leurs **numéros de cellule**, même si plusieurs tensions sont égales au millivolt. Si toutes les tensions sont égales, pas de faux min/max. Transition animée conservée.
- L'APK privilégie `cell_min_number` et `cell_max_number` fournis par le firmware JK 0.2.2 ; elle utilise un calcul déterministe par première cellule à l'extrême pour les anciennes versions JK 0.2.0.
- **Alarmes BMS** : « Aucune alarme active » s'affiche dès le premier rapport avec masque d'erreur à zéro ; correction de l'état « En attente » bloqué.
- **Informations du BMS** : explique clairement pourquoi une identité JK 0x03 est masquée, sans énormes lignes illisibles, et affiche à défaut le profil de mesures et les cellules actives réellement confirmés. Ne présente pas le profil JK02 comme le modèle matériel exact.
- **Firmware JK 0.2.2-dev** : publie les numéros physiques min/max depuis les octets JK02 native max/min (24S : 62/63, 32S : 78/79) après vérification qu'ils correspondent réellement à la trame ; utilise un calcul de secours en cas d'index invalide. Intègre aussi les contrôles de trame d'identité introduits dans JK 0.2.1.
- Le décodeur d'identité 0x03 demeure expérimental : un modèle/numéro de série exact ne peut pas être affiché tant que la trame réelle du BMS n'est pas reconnue. Aucune trame brute ni mot de passe du BMS n'est exposé.
- APK de test installée séparément sous `.vpntrial24` pour préserver les autres installations et leurs clés Android. Réassociation locale de ce paquet de test nécessaire une fois.
- Le firmware JK 0.2.2 **APP-ONLY** s'écrit à l'offset `0x10000` **sans effacement NVS** sur l'ESP32 JK classique. Il n'est pas nécessaire pour corriger l'accueil Android ; ne pas flasher le deuxième ESP32 VPN.
- Tests JK synthétiques 24S/32S, indices natifs/incohérents, tests JVM de sélection des cellules et compilation Android/ESP32 réussis en CI.


## 0.2.3-dev — accueil JK enrichi, cellules dynamiques et icône Smart Energy (2026-11-08)

- **Android APK expérimentale** : intègre le logo Smart Energy approuvé, adapté au petit format d'icône Android (éléments maison/solaire/batterie/VPN) et affiché également dans l'en-tête.
- L'accueil JK présente les six canaux de température disponibles : sondes T1 à T5 et MOS. Une sonde non transmise affiche « — », jamais une valeur fabriquée.
- Affiche les alarmes JK directement sur l'accueil, avec leur libellé, leur masque d'origine et une indication claire lorsqu'aucune alarme n'est reçue.
- Cellules : barres animées lors des nouvelles lectures et changement de couleur progressif lorsque les cellules les plus basse (rouge) et haute (bleue) changent. En cas d'égalité, plusieurs cellules peuvent être mises en évidence ; si toutes sont identiques, il n'y a pas de fausse distinction.
- Sécurise l'affichage « Informations BMS » : filtre fragments JSON et caractères incompatibles ; avec le firmware JK 0.2.0, ne présente pas les compteurs d'identité non validés comme réels. Cette protection **ne répare pas les trames JK 0x03 inconnues** : installer le firmware JK 0.2.1 au besoin pour la validation stricte, qui peut encore masquer des champs si le BMS utilise un format différent.
- Conserve le fonctionnement **VPN TailTiny/Tailscale second ESP32** validé en 4G/5G, les contrôles de token chiffré localement, l'association JK vérifiée et l'interdiction de toute commande distante.
- Nouvelle APK de test **installable à côté** de l'application normale grâce à son identifiant séparé `.vpntrial23`. Le code source et les binaires de développement ne contiennent aucun identifiant Wi-Fi/Tailscale utilisateur.
- Cette version **ne nécessite pas** de reflasher l'ESP32 VPN ni le lecteur JK pour afficher le nouveau tableau de bord.


## 0.2.1-dev — identité JK plus robuste et accès Android via VPN privé (2026-11-07)

- Corrige l'affichage de caractères et fragments JSON parasites dans « Informations du BMS » : un checksum de trame 0x03 ne suffit plus, le modèle et chaque champ doivent être plausibles.
- Ajoute des tests de non-régression incluant des trames 0x03 à pseudo-JSON malgré leur checksum valide.
- Ajoute un mode **VPN privé** dans l'APK (lecture seule, adresse LAN privée, réessais automatiques lorsque le VPN est disponible).
- Accès à distance seulement après configuration d'un routeur VPN vers le réseau domestique ; ne met pas en place de cloud ni d'ouverture de port, ne dépend pas de Home Assistant.
- L'APK n'envoie plus de code d'authentification à la route publique `/api/v1/info`.
- Connexion BLE persistante conservée ; compilation et tests unitaires, à vérifier sur le matériel réel.

## 0.2.0-dev — télémétrie étendue, paramètres JK en lecture seule (2026-11-07)

- Décodeurs JK02 type 0x01 et 0x03 validés CRC, en plus des mesures type 0x02.
- Catalogue read-only de 47 paramètres, informations BMS filtrées, résistance et tension par cellule, sondes supplémentaires, SOH, capacités, équilibrage et alarmes quand disponibles.
- API de paramètres `/api/v1/details` séparée de la télémétrie rapide pour conserver la fluidité.
- Trois écrans natifs Android : Batterie, Détails, Paramètres. Absence de données explicitement signalée.
- Conservations des fonctionnalités Wi-Fi, association BOOT et connexion BLE persistante.
- Tests synthétiques et compilations ; **validation sur le JK V19 physique encore nécessaire**.
- Aucune fonction d'écriture des protections ou paramètres batterie.

## 0.1.9-dev — connexion BLE persistante et tableau de bord JK (2026-11-07)

- ESP32 : connexion JK maintenue tant que le BLE est actif, plus de déconnexion volontaire à chaque échantillon.
- Cache HTTP non bloquant ; reconnexion progressive après perte, lectures `0x96` limitées en cas d'absence de nouvelles trames.
- Télémétrie : statut BLE, compteurs de connexions/déconnexions, refus d'afficher une mesure vieille de plus de 20 s.
- APK : mise à jour des valeurs toutes les 2,5 s, sans recréer la totalité des vues ; interface native moderne avec jauge SOC, cellules en 2 colonnes, températures et cycles.
- Masquage du format JK technique dans l'interface, conservation de l'accès aux réglages derrière « Configuration et association ».
- Maintien de la réassociation par bouton BOOT déjà développée.
- Compilation automatisée et tests synthétiques ; **réduction des bips et rapidité réelles à confirmer sur le JK**.

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
