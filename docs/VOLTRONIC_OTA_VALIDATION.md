# Smart Energy Voltronic — essai OTA de validation

## Objectif

Valider la mise à jour sans câble USB du lecteur Voltronic ESP32-S3 N16R8,
**sans toucher aux réglages de l'onduleur, aux JK ni au VPN**.

**Version candidate :** `0.3.3-dev-ota-preflight-read-only`. Cette version
ajoute seulement des champs de diagnostic à `GET /api/v1/ota/status`. Elle
ne contient aucune écriture des paramètres Voltronic ; ces modifications
nécessitent une phase séparée avec confirmations et tests RS232.

## Première condition : partitions OTA

Le firmware installé expose le chemin local authentifié :

`http://<IP locale S3>:18081/api/v1/ota/status`

L'application ou le script `tools/voltronic-ota/check-ota-local.ps1`
vérifie la réponse. Les champs décisifs :

- `ota_supported=true` : une partition OTA de destination existe.
- `ota_supported=false` : une seule partition ; **ne pas tenter l'OTA**.
  Une migration USB sécurisée est nécessaire, une seule fois.
- `rollback_pending=true` : redémarrage en attente de validation ;
  ne pas déclencher une seconde OTA.

N'utiliser **ni** `Factory` **ni** `Erase flash` pour migrer un S3 déjà
configuré. Une opération USB doit préserver la région NVS à partir de
`0x9000` et la table de partitions doit être validée avant écriture.

Sur le PC Windows avec le S3 et le PC sur le même LAN :

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\voltronic-ota\check-ota-local.ps1
```

Le code Voltronic est demandé localement, non affiché ni enregistré. Ne
publier aucun code, adresse personnelle ni capture contenant un secret.

## Deuxième condition : image vérifiée et publiée

La compilation CI dual-OTA fournit
`SmartEnergy-VOLTRONIC-S3-OTA-0.3.3-PREFLIGHT` (artifact) contenant
`smart-energy-voltronic-S3-OTA.bin` ; c'est une image d'application
ESP32-S3, **pas** un exécutable Windows ni une image Factory.

Un **artifact GitHub Actions n'est pas une Release GitHub**.
Pour que le bouton APK « Installer OTA Voltronic depuis GitHub » soit
utilisable, le propriétaire doit publier cette image dans une Release
GitHub contrôlée et le manifeste officiel
`updates/voltronic-esp32.json` doit pointer sur cet asset,
avec l'empreinte SHA-256 correcte et la version supérieure.
Le workflow de publication de manifests ne traite qu'une Release
officiellement publiée : il ne doit jamais annoncer une image inexis­tante.

Tester uniquement en Wi-Fi local, connecté au S3 par son IP LAN.
Le VPN Tailscale est **lecture seule** et n'est pas autorisé pour OTA.

## Essai sur le matériel

1. Vérifier les valeurs QPIGS / QPIGS2 / QPIRI / QDOP avant l'opération.
2. Vérifier `ota_supported=true` via le diagnostic ou le script.
3. Télécharger la Release depuis l'APK, vérifier le SHA-256 et demander
   confirmation explicite avant le transfert HTTP local.
4. Attendre le redémarrage ; ne pas couper l'alimentation du S3.
5. Relire `/api/v1/info`, `/api/v1/ota/status`,
   `/api/v1/telemetry` et `/api/v1/details`.
6. Confirmer la nouvelle version, le retour des MPPT et la conservation
   du Wi-Fi, code du S3 et des paramètres natifs.
7. Si le redémarrage échoue, stopper les essais et diagnostiquer le
   rollback OTA ; ne pas enchaîner automatiquement une seconde image.

## Étape suivante : modification réelle des paramètres

L'éditeur sera indépendant du moteur de polling normal et n'enverra
aucune commande tant que l'utilisateur n'aura pas confirmé la nouvelle
valeur. Il faudra obligatoirement :

- identifier le modèle et récupérer une valeur QPIRI/QDOP fraîche ;
- vérifier la plage, le pas, la variante de commande déjà validée ;
- sérialiser : SET prioritaire → ACK/NAK → relecture prioritaire
  QPIRI/QDOP → comparaison, sans lecture ordinaire intercalée ;
- déclarer « vérifié » uniquement sur relecture correspondante ;
- sur échec, déclarer « non confirmé », sans seconde commande automatique
  ni fallback vers une commande ayant une autre signification ;
- conserver l'identification de profil locale INV1/INV2/INV3.

**La compilation CI n'atteste ni de la prise en charge des commandes SET
par l'onduleur réel, ni du bon fonctionnement du rollback sur le vrai S3.**
