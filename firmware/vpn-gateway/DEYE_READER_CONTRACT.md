# Contrat ESP32 Onduleur Deye ↔ passerelle VPN (0.3.12-dev)

Objectif : préparer **avant intervention à distance** toutes les routes de communication Deye, notamment les écritures futures, sans casser JK/Voltronic. Ce document est un **contrat d'API** ; il ne signifie pas que le firmware ESP32 Deye est déjà développé.

## Découverte sécurisée

L'ESP32 Onduleur Deye utilise les mêmes emplacements 1, 2 et 3 que les ESP32 Onduleur Voltronic, avec les **nom et code ESP32 déjà configurés dans le flasher VPN**, sans IP fixe de la clé Deye dans le VPN.

- Annonce UDP 18769 : `proto: smart-energy-discovery-v1`, `family: inverter`, `name`, `id`, `ip` (IP locale **de l'ESP32**), `port: 18081`, `auth` (HMAC-SHA256 avec le code ESP32 du message `smart-energy-discovery-v1|inverter|name|id|18081|ip`).
- Preuve authentifiée : `GET http://<ESP32-IP>:18081/api/v1/info` avec `X-Setup-Code`; `product: smart-energy-deye-s3` et `device_id` identique à l'annonce, stable.
- Inventaire VPN : `GET /api/v1/discovery`, ancien `family: voltronic` **conservé pour compatibilité** des 3 emplacements Onduleur, nouveau `device_type: deye` pour différencier l'ESP32 Deye.

Le VPN ne découvre, n'authentifie et ne contacte **jamais directement** l'IP de la clé Wi-Fi Deye.

## Routes VPN déjà préparées

Pour chaque numéro `N=1,2,3` (numéro d'emplacement ESP32 Onduleur) :

| APK → VPN (Tailscale) | VPN → ESP32 lecteur (LAN) | Protection |
| --- | --- | --- |
| GET `/api/v1/deye/N/info` | GET `/api/v1/info` | token VPN + session tailnet |
| GET `/api/v1/deye/N/telemetry` | GET `/api/v1/telemetry` | idem |
| GET `/api/v1/deye/N/details` | GET `/api/v1/details` | idem |
| GET `/api/v1/deye/N/device-config` | GET `/api/v1/device-config` | idem |
| GET `/api/v1/deye/N/ota/status` | GET `/api/v1/ota/status` | idem |
| POST `/api/v1/deye/N/device-config` | POST `/api/v1/device-config` | autorisation écriture distante existante + confirmation |
| POST `/api/v1/deye/N/parameters/change` | POST `/api/v1/parameters/change` | idem + 15 s anti-duplication |
| POST `/api/v1/deye/N/ota/request` | POST `/api/v1/ota/request` | idem : **commande seulement**, pas de binaire |

L'APK envoie `X-Gateway-Token` sur le VPN, `X-Remote-Write-Confirmed: yes` sur les POST. Le VPN relaie vers le lecteur avec le **code de l'ESP32 déjà enregistré** et l'en-tête `X-Write-Confirmed: yes`. Aucun changement d'IP cible VPN depuis l'APK.

### Corps des POST admis

- Configuration de clé Deye :
  `{"dongle_ip":"192.168.1.215","dongle_port":8899,"modbus_id":1,"dongle_serial":"3133245188","inverter_serial":"2506270609","confirmed":true}`.
  L'ESP32 Deye valide et stocke ces valeurs en NVS. Les numéros de série sont des chaînes de chiffres ; IP privée uniquement. Ces valeurs sont *un exemple*, non des valeurs provisionnées.
- Écriture d'un **paramètre sémantique** :
  `{"parameter":"grid_charge_start_soc","old":20,"value":25,"confirmed":true,"readback_required":true}`.
  Le VPN ne transporte ni adresse de registre brute ni commande Modbus arbitraire. **Le firmware ESP32 Deye doit refuser chaque paramètre inconnu** via sa propre liste de registres officiellement validés, puis vérifier le retour en lecture et empêcher les écritures dangereuses/hors plage. Aucune table de registres n'a encore été validée.
- Déclenchement OTA :
  `{"action":"check_and_install","confirmed":true}`.
  L'ESP32 doit vérifier une version **autorisée et compatible**, télécharger le firmware sur son Wi-Fi domestique, en vérifier origine/intégrité, l'installer sur partition OTA inactive et rendre compte de l'état. Pas de binaire ni d'URL arbitraire via VPN.

**Important (VPN 0.3.12+) :** aucune activation physique USB supplémentaire n'est requise pour une écriture distante. Le VPN exige toujours une connexion Tailnet autorisée, le token passerelle, `X-Remote-Write-Confirmed: yes`, un JSON `confirmed: true` et une route sémantique autorisée. Le lecteur cible vérifie lui-même la valeur et sa relecture. Le champ NVS historique `remote_writes_enabled` est conservé pour les mises à jour APP-ONLY, mais ne bloque plus ces commandes. **Les écritures électriques Deye restent désactivées côté lecteur tant que les registres ne sont pas validés.**

## Déploiement / limitation

Le nouveau relais VPN doit être installé **avant** de perdre l'accès local au VPN. Le firmware VPN **actuel ne possède pas d'OTA permettant de se mettre lui-même à jour à distance** : la mise à jour de la passerelle VPN peut encore nécessiter un flash USB APP-ONLY. C'est différent de la **future OTA du lecteur ESP32 Deye**, déclenchée à distance, téléchargée directement par le lecteur.

Les endpoints de ce contrat ne fourniront pas de données Deye réelles tant qu'un firmware ESP32 Deye compatible et l'APK correspondante ne seront pas développés et validés sur du matériel.
