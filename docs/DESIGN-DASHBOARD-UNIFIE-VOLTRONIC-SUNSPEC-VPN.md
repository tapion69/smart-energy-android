# Smart Energy Android — proposition de tableau de bord universel et accès VPN unique

État : **proposition de conception, non encore implémentée comme tableau de bord complet**.
Objectif : Voltronic et SunSpec présentés avec les mêmes composants visuels sans inventer des mesures qui ne sont pas fournies. Compatible multi-onduleurs, multi-JK, téléphone avec ou sans Home Assistant.

## 1. Ce qui fonctionne actuellement (branche dev, 11 novembre 2026)

- Flasheur Windows : onglet « Voltronic S3 » avec **code de lecture librement choisi, 8 à 32 caractères**, Wi-Fi et flash FACTORY/APP-ONLY ; il transmet le code par USB physique puis le S3 le conserve dans la NVS. Aucune clé client codée en dur.
- Firmware Voltronic : ESP32-S3 + MAX3232, 2400 bauds, QPIGS/QPIGS2/QMOD/QPIRI, API GET locale 18081 (en cours de validation sur matériel réel).
- APK : sonde de présence /api/v1/info Voltronic avec code de lecture **non encore enregistré de façon chiffrée dans le formulaire onduleur**.
- APK : SunSpec, découverte de la signature SunS et des ID modèles via Modbus/TCP FC03. **Pas encore de décodage de mesures**.
- S3 VPN : accès distant JK1/JK2/JK3 par routes HTTP en lecture seule ; il ne relaie **pas encore** Voltronic (route prévue mais désactivée) ou SunSpec. Ne pas annoncer ces accès comme disponibles.

## 2. Installation « ajouter un équipement » en une seule fois

1. Un seul menu « Équipements » avec bouton « Ajouter » : JK / Voltronic via ESP32-S3 / SunSpec local ; recherche locale et proposition de correspondance (ne pas sélectionner ou associer silencieusement un appareil inconnu).
2. Voltronic : l'utilisateur sélectionne le code de lecture dans le flasheur au premier flash ; l'APK ne redemande **qu'une seule fois** ce code au moment de la première connexion vérifiée. Après validation /api/v1/info, sauvegarde via Android Keystore ; aucune conservation en clair dans les préférences et aucun code dans les logs.
3. SunSpec : IP/nom d'hôte privé, port 502 par défaut, Unit ID 1 modifiable (paramètres experts masqués), identification SunS puis vérification des modèles réellement présents. Pas de « code ESP32 » pour ce protocole.
4. Les utilisateurs sans équipement correspondant ne voient pas les champs qui lui sont propres. Rester en lecture seule pendant la validation des protocoles.

## 3. Une section VPN pour toute l'application

« Réglages → Réseau et accès → Accès à distance (Tailscale) » avec une seule adresse IP Tailnet du S3, **un seul jeton de la passerelle VPN** et mode « Automatique ». Ne jamais demander à l'utilisateur de réenregistrer Tailscale pour chaque ESP, BMS ou onduleur.

- Tailscale activé + passerelle joignable et identité vérifiée : APK -> adresse 100.64.0.0/10 du S3 VPN -> route sûre de l'équipement choisi.
- Tailscale coupé : retour aux adresses Wi-Fi locales enregistrées ; pas besoin d'appuyer sur « Se connecter ».
- Tailscale déclaré actif mais passerelle non joignable : erreur explicite / réessai borné, **aucun rabattement LAN non contrôlé ou émission de secret hors du canal prévu**.
- Identifiants de **chaque lecteur** distincts et stockés lors de leur association unique ; **jeton de la passerelle** commun à toute l'APK, indépendant des codes des lecteurs.
- Le S3 VPN ne doit **jamais** devenir un proxy arbitraire. Ses cibles (JK, Voltronic, SunSpec) doivent être préenregistrées par association physique/autorisée, puis les routes strictement autorisées ; seules lectures GET (Voltronic) et FC03 avec restrictions d'adresse/registres (SunSpec).
- Aucune ouverture de port Internet, aucun serveur payant. Écritures JK et modifications des protections onduleur restent interdites via VPN.
- **Travail encore nécessaire** : extension firmware VPN allowlist pour Voltronic et SunSpec, approvisionnement sécurisé des cibles, télémetries API, tests de non-régression JK.

## 4. Tableau de bord unique : proposition de navigation

Accueil général :
- Équipements/Batteries (JK) existants conservés ;
- Carte « Énergie » (PV, consommation AC, batterie, réseau) : sources prouvées seulement ;
- Carte « Onduleurs » : nombre d'appareils, état de communication, dernier échantillon, défauts avérés.

Onglet « Onduleurs » (identique visuellement pour Voltronic et SunSpec) :
- Bandeau : installation / onduleur sélectionné, local ou VPN, fraîcheur, mode lecture seule, nombre d'onduleurs.
- Bloc flux énergétique central : Solaire W -> consommation/charges, Batterie +/- W et SOC %, Réseau import/export W ; valeurs absentes = « — », pas « 0 ».
- Cartes : Production solaire instantanée W, Charge AC W, Batterie V/A/W/SOC lorsque disponibles, Réseau V/Hz et puissance si native, Mode onduleur.
- Section détails dépliable : PV1/PV2, AC entrée/sortie, températures, état, trames/alertes, modèle, disponibilité des compteurs, données sources brutes validées.
- Onglets « Global », « Onduleur 1 », « Onduleur 2 », etc. Supports sources hétérogènes.
- Historique et énergie jour/mois/année uniquement si **compteurs effectivement lus** ; pas de kWh inventés à partir d'un point instantané. Si intégration temporelle ajoutée un jour, mentionner explicitement la nature « estimation » et ses lacunes.

## 5. Contrat de données commun (modèle interne proposé)

Chaque mesure porte : `value: number?`, `unit`, `source`, `observed_at`, `age_ms`, `quality` (native / derived / unavailable), éventuellement `device_id`.
Clés communes **optionnelles**, avec conventions de signe documentées :
- `pv_power_w`, `load_power_w`, `grid_import_w`, `grid_export_w`
- `battery_voltage_v`, `battery_current_a`, `battery_power_w`, `battery_soc_pct`
- `ac_input_voltage_v`, `ac_output_voltage_v`, `ac_output_frequency_hz`, `operation_mode`
- `energy_pv_today_kwh`, `energy_load_today_kwh`, `energy_grid_import_today_kwh` **seulement si compteurs fiables**
- `faults`, `temperatures`, `source_specific` (détails Voltronic QPIGS/QPIGS2/QMOD/QPIRI ou SunSpec modèles).

Mapping Voltronic aujourd'hui : `output_w` vers charge AC si sémantique validée ; `battery_voltage_v` et `battery_soc_pct` valides ; `pv1_voltage_v` + `pv1_current_a` peuvent donner une **estimation** PV1, clairement étiquetée dérivée. `grid_power_native_w` seulement si fourni, pas à déduire des volts. `pv2_power_w` seulement si QPIGS2 valide.

Mapping SunSpec : découverte existante des IDs de modèles **ne vaut pas** mesures disponibles. Décodeurs à réaliser selon les modèles et facteurs d'échelle réellement exposés. Les modèles standards ne garantissent pas la présence d'une batterie, de PV DC, de puissance réseau ou de compteurs énergétiques sur chaque marque.

## 6. Multi-onduleurs sans double comptage

- La production et la charge peuvent être agrégées seulement si les appareils mesurent des flux distincts, synchrones, comparables ; éviter somme incohérente de valeurs stale et live.
- La batterie physique partagée par deux onduleurs n'a qu'un seul SOC global ; utiliser de préférence le BMS autoritatif et ne pas additionner/faire la moyenne aveugle de SOC dupliqués.
- L'énergie import réseau ne doit pas être doublée si plusieurs onduleurs décrivent le même point réseau.
- Unités, signes, qualité et instant d'acquisition sont normalisés ; pas de fusion « PV volts » avec « puissance PV W ».
- Réseau hors ligne, solaire de nuit et champs indisponibles = états normaux, pas des erreurs.

## 7. Étapes de développement recommandées

1. **Valider sur matériel** le firmware Voltronic S3 avec le câblage MAX3232, son /api/v1/info et ses trames QPIGS/QPIGS2/QMOD ; sans écriture.
2. Contrat de données commun et décodeur Voltronic -> cartes affichant seulement les valeurs réellement validées.
3. SunSpec : décodage modèles et facteurs d'échelle avec fixtures documentées, puis même tableau de bord.
4. Mise en mémoire chiffrée du code Voltronic et simplification de l'assistant « Ajouter équipement ».
5. Déplacer la configuration VPN JK existante vers **une section unique** pour toute l'APK, sans changer l'authentification en place ; ajouter les routes Voltronic/SunSpec au S3 VPN **en lecture seule** et tester les changements local ↔ VPN.
6. Valider plusieurs onduleurs, stockage des associations, actualisation fluide, gestion des absences/erreurs et tests sécurité puis livrer une APK avec **signature permanente** pour mises à jour sans perte des réglages.

**Ne jamais afficher comme « terminé » une fonction simplement compilée ou non vérifiée matériellement.**
