# Architecture cible

## Systèmes indépendants

```text
JK BMS 1..N  <-- BLE -->  ESP32 JK #1 ... #N  -- Wi-Fi -->
                                                             \
                                                              Smart Energy Android
                                                             /
Voltronic 1..N <-- RS232 --> ESP32 Voltronic #1 ... #N -- Wi-Fi -->
```

L'application permet d'utiliser JK seul, Voltronic seul ou les deux, sans Home Assistant.

## Premier parcours utilisateur

1. L'ESP32 vierge reçoit une fois le firmware compatible via USB (ou arrive préflashé).
2. Il expose **un mode d'appairage temporaire et protégé** (point d'accès Wi-Fi de configuration ou BLE provisioning, à départager par test réel).
3. L'APK identifie l'ESP32 ; l'utilisateur prouve la possession physique de la passerelle (code éphémère/QR associé au matériel ou action physique).
4. L'APK fournit le SSID et le secret Wi-Fi ; l'ESP32 les sauvegarde en stockage non volatil et rejoint le LAN.
5. L'APK détecte la passerelle sur le réseau local (découverte et saisie manuelle d'IP comme repli).
6. Pour JK, l'utilisateur demande un scan BLE **réalisé par l'ESP32**, voit MAC, RSSI et identité disponible, sélectionne ses batteries.
7. L'ESP32 sauvegarde la liste sélectionnée et relance ses sessions BLE sans nouveau flash.
8. L'APK affiche uniquement les valeurs confirmées avec horodatage, état de connexion et détection de données périmées.

**Points à implémenter :** la configuration dynamique des MAC et le pilotage du scan ne sont pas fournis tels quels par le prototype ESPHome statique. Le firmware autonome aura besoin d'un gestionnaire BLE et d'une interface de configuration persistante.

## Contrat fonctionnel minimum (à formaliser avant implémentation)

- `GET /api/v1/device` : identité, type (jk/voltronic), version, statut, capacités.
- `GET /api/v1/health` : disponibilité et diagnostique, sans secret.
- `GET /api/v1/bms` : liste des JK configurés, état et dernière lecture.
- `POST /api/v1/bms/scan` : scan sur demande, limité dans le temps, session authentifiée.
- `PUT /api/v1/bms` : remplacement validé et atomique de la sélection de BMS.
- `GET /api/v1/telemetry` : mesures, unités, identifiants stables, timestamp et niveau de fraîcheur.
- `POST /api/v1/ota` : lancement *explicite* et authentifié d'une mise à jour vérifiée.

Les chemins sont un **contrat de conception**, pas des endpoints déjà présents sur le matériel. Les appels de configuration ne doivent jamais être anonymes après l'appairage.

## Mesures JK première étape

SOC, tension batterie, courant, puissance, cellules 1..16, résistances de cellules si fournies, températures disponibles, équilibre, alarmes et informations d'identification. Les valeurs non supportées par le modèle restent indisponibles ; pas de zéros inventés.

Une liaison avec un BMS réel, puis plusieurs BMS, doit être éprouvée (reconnexions, RSSI, charge CPU/RAM, interruptions réseau). Ne pas fixer de limite universelle de BMS par ESP32 sans tests.

## Accès local et distant

- **Local** : communication avec la passerelle sur le LAN, après appairage et contrôle d'accès.
- **Distant, ultérieur** : connexion sortante chiffrée vers un relais sécurisé, authentification par compte + association de passerelle, révocation possible. Aucun accès public direct aux ports série ou au réseau local.
- Un proxy ESPHome actuellement employé par Home Assistant **n'est pas** le firmware autonome prévu ici. Ne pas écraser un ESP32 de production pour tester.

## Séparation de responsabilités

- ESP32 : radio/liaison, lecture et décodage, état, sauvegarde de configuration, OTA sûre.
- APK : assistant de configuration, gestion de passerelles, tableaux de bord, recherche des versions, consentement de téléchargement/installation.
- Distribution : GitHub Releases et manifestes de publication ; une vraie Release doit exister avant d'activer un manifeste.
