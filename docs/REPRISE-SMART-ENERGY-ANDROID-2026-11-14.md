# Reprise — Smart Energy Android, 14 novembre 2026

## Sources techniques

- Dépôt de travail : `tapion69/smart-energy-android`, branche de référence `dev` avant correctifs au commit `f45921130530e497549b578840fbdc90f57cfb14`.
- Dernière APK réellement publiée à cette reprise : `0.3.44-dev`, `versionCode=58`, avec signature permanente. Le correctif de cette branche n'est **pas** une APK publiée, et aucune mise à jour automatique n'est activée.
- Flow de référence : `tapion69/smart_voltronic`, branche `develop`, fichier `smart-voltronic/flows.json`, nœud `normalize state`.
- Captures du 14/11/2026 : les deux présentations affichent à tort les mesures supplémentaires dans des cartes **sous** le diagramme.

## Architecture à préserver

- APK native Android autonome : pas de dépendance à Home Assistant / Node-RED pour le fonctionnement normal.
- ESP32-S3 Voltronic relié en RS232 par MAX3232 ; protocole `QPIGS`, `QPIGS2`, QPIRI/QDOP, API locale et accès privé VPN.
- ESP32 JK BMS (plusieurs BMS par lecteur), passerelle VPN distincte. **Ne pas modifier** l'écran JK existant, ses associations BLE, ses codes, son firmware ou le firmware VPN au titre d'un ajustement graphique.
- Jusqu'à trois onduleurs Voltronic configurables ; afficher seulement les MPPT réellement détectés et frais.
- Le passage d'une APK signée à la suivante doit préserver configuration, enregistrements et certificat de signature.

## État à la fin de la conversation « Résumé mise à jour OTA »

Les demandes de la révision 0.3.44 étaient :

1. Afficher les cartes Batt-1, Batt-2, etc. dans l'ordre numérique, sans changer le dashboard JK dédié.
2. Enlever les panneaux jaunes non essentiels.
3. Rendre prioritaires les réglages réels `POP` (priorité sortie/secteur) et `PCP` (priorité charge), et masquer les champs QPIRI/QDOP bruts non modifiables de l'éditeur.
4. Faire apparaître les mesures réellement disponibles : température onduleur, courant AC de sortie (indiqué calculé), puissance/courant batterie, tension moyenne JK, température batterie et consommation du jour UTC issue du compteur natif.
5. Flux animés uniquement lorsqu'une puissance positive est réellement disponible ; flux batterie réversible charge/décharge ; jamais d'animation réseau à 0 W, ni de sens réseau non validé.
6. Dashboard moderne : échanger **exactement** les positions Maison et Onduleurs, en conservant les ancres de la scène de référence.
7. Réparer les blocages de défilement dans l'onglet Onduleurs.
8. MPPT : si un champ de puissance native Voltronic existe, l'utiliser avant toute estimation V × A.

Les captures du 14/11 montrent que la version 0.3.44 a déplacé les chiffres en bas des deux schémas, au lieu de les intégrer **dans les cadres illustrés**, et que l'étiquette MPPT1 continue à indiquer « Calcul V × A ».

## Ce que corrige la présente branche

### Vue Moderne

- Maison à l'ancien emplacement des Onduleurs (gauche), avec puissance actuelle + consommation du jour UTC intégrées **dans le même cadre**.
- Onduleurs à l'ancienne position exacte de Maison (droite), avec nombre d'unités, température et courant AC intégrés dans ce cadre.
- Batteries : SOC, puissance, courant, tension moyenne et température / sens de charge dans le cadre Batteries, au-dessus de l'image.
- Énergie PV journalière dans la carte Solaire. Plus de cartes d'appoint sous la scène.
- Tracé des chemins d'énergie réaligné sur les nouvelles positions : solaire → onduleurs → maison ; onduleurs ↔ batterie ; réseau ↔ onduleurs.

### Vue Sunsynk

- Température et courant AC dans le bloc central Onduleurs.
- Consommation du jour UTC directement dans le bloc Maison.
- Puissance, courant, tension moyenne et température sous le SOC **dans le bloc Batterie** ; suppression de la grille annexe.
- MPPT distincts et chemins rejoignant une barre commune ; pas d'animation sur les canaux à 0 W.

### MPPT1 : conformité avec Node-RED

Dans le flow `normalize state` : `pv1PowerIdx` est appris parmi les candidats `[19,20,18,17,16]` lorsqu'une production réelle permet de comparer P avec V × A ; cet index est réutilisé les lectures suivantes. En absence de production, le flow lit le champ 19 comme valeur provisoire.

Le firmware S3 précédent réévaluait la correspondance à **chaque lecture** avec condition `V × A > 10 W`. De nuit, la puissance native `pv1_power_native_w` disparaissait donc et l'APK revenait à son estimation `V × A`.

Cette branche conserve l'index validé en mémoire du S3 et publie `pv1_power_native_verified`. Pour un zéro de nuit avant validation, le champ 19 est conservé avec la mention **à confirmer**, pour ne pas présenter une donnée ambiguë comme définitivement validée. PV2 reste basé sur `QPIGS2` lorsqu'il est disponible. La vue premium secondaire privilégie également la puissance native.

**Important** : pour que le nouveau calcul natif fonctionne sur un téléphone réel, la modification du firmware S3 devra être compilée puis livrée via une mise à jour **Voltronic** validée. Mettre à jour seulement l'APK ne peut pas fabriquer un champ absent de l'API. Aucun firmware n'a été déployé sur un appareil par ce changement GitHub.

## Tests et validations

- Contrôles structurels effectués sur le HTML : pas d'ID dupliqué, pas de référence JS vers un ID manquant ; aucun `energy-details` en dehors des illustrations.
- Régression C ajoutée : nuit avant apprentissage (champ provisoire), jour avec champ 19, nuit avec index 19 retenu, autre modèle sur champ 20 et nuit avec index 20 retenu.
- Workflow de validation PR : tests C GCC stricts, contrôle des cadres HTML et tests unitaires Kotlin.
- **À confirmer sur smartphone** : fidélité des emplacements à différentes résolutions, sens et visibilité des animations, défilement Onduleurs, compteur journalier à minuit UTC, lecture native MPPT après firmware.
- **Aucune mise à jour APK signée 0.3.45 publiée** ; `main`, `updates/android.json`, les binaires OTA épinglés et les firmwares JK/VPN sont inchangés.

## Autres points issus de la conversation OTA

- Le réseau VPN JK était utilisable et les quatre BMS remontaient correctement.
- Le code `503` côté Voltronic VPN indiquait une entrée INV non enregistrée dans la passerelle, à traiter par ajout ciblé, **sans effacement FACTORY ni flash JK/VPN général**. Cela ne doit pas être confondu avec un défaut du dashboard.
- Écritures Voltronic via `POP`/`PCP` : relecture/confirmation et contrôles existants à préserver.
- Signature APK permanente : une mise à jour doit se superposer sans désinstaller l'application ni perdre les profils ou secrets de l'installation.

## Fichiers modifiés (sur cette branche de correction)

- `android/app/src/main/assets/energy_dashboard_v3.html`
- `android/app/src/main/java/fr/smartenergy/android/VoltronicFleet.kt`
- `android/app/src/main/java/fr/smartenergy/android/MainActivity.kt`
- `firmware/voltronic-esp32/main/voltronic_protocol.h`
- `firmware/voltronic-esp32/main/main.c`
- `firmware/voltronic-esp32/tests/test_protocol.c`
- `.github/workflows/verify-energy-dashboard-inline.yml`

## Étape suivante

Faire valider la branche et ses tests, inspecter le rendu sur le téléphone, puis décider d'une vraie version d'essai signée Android et d'une image OTA Voltronic distincte, chacune construite et vérifiée. **Ne pas écraser ni redistribuer la version 0.3.44 existante.**
