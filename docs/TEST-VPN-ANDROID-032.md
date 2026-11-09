# Smart Energy Android 0.3.2-dev — vérification du VPN (ESP2 inchangé)

Correctif **Android seulement** sur la branche expérimentale 0.3.2 ; le firmware ESP32 VPN, son IP Tailscale et sa configuration sont inchangés.

## Les trois secrets à ne pas confondre

- `CONFIG_ML_TAILSCALE_AUTH_KEY` : la clé d'inscription **ESP2 → compte Tailscale** (`tskey-auth-...`). **Ne pas** la saisir dans l'APK.
- `CONFIG_SE_GATEWAY_TOKEN` : **le token que l'utilisateur doit saisir dans « Accès extérieur — ESP32 VPN »** de l'APK. Il est distinct du code JK et doit contenir au moins 20 caractères.
- `CONFIG_SE_JK_READ_CODE` : le **code JK compilé dans ESP2**, qu'ESP2 utilise sur le réseau local pour lire ESP1. Il doit être **identique au code personnel courant d'ESP1**. Changer le code JK depuis l'APK sur ESP1 ne met PAS à jour automatiquement ESP2.

Ne jamais publier ni transmettre ces valeurs ou les mots de passe Wi-Fi. Les exemples et captures doivent masquer les secrets.

## Test depuis Android

1. Garder la version existante installée ; l'APK d'essai `.vpntrial32` est **séparée** et n'a pas automatiquement le code/les MAC de l'APK précédente. Associer d'abord ESP1 en Wi-Fi local et y lire au moins un JK.
2. Activer Tailscale sur Android, vérifier que le téléphone appartient au même tailnet et désactiver le Wi-Fi pour effectuer le test en 4G/5G.
3. Dans Chrome, ouvrir `http://IP_TAILSCALE_ESP2:18767/api/v1/health` (remplacer `IP_TAILSCALE_ESP2` par l'adresse Tailscale réelle, sans crochets). Attendre un JSON `product=smart-energy-vpn-gateway`, `vpn_connected=true`, `read_only=true`. Cette route ne demande **aucun token** : elle teste le transport uniquement.
4. Dans l'APK d'essai, saisir l'IP Tailscale de **ESP2**, et uniquement `CONFIG_SE_GATEWAY_TOKEN` dans le champ token VPN. Cliquer « Enregistrer et tester mon ESP32 VPN ».
5. La 0.3.2 donne la priorité au VPN activé automatiquement avant de tenter une ancienne IP locale. Les erreurs sont désormais différenciées :
   - `invalid_gateway_token` / 401 : **token VPN** faux.
   - `vpn_only` / 403 : requête non reconnue comme provenant du tunnel VPN.
   - `source_not_configured` : relais JK non configuré sur ESP2.
   - `upstream_unreachable` : **ESP2 rejoint Tailscale mais ne trouve pas ESP1** à l'adresse LAN compilée `CONFIG_SE_JK_IP`.
   - `upstream_http_failure` : **token VPN validé**, mais ESP1 refuse ou échoue sur une lecture ; cause possible : `CONFIG_SE_JK_READ_CODE` ne correspond plus au code JK courant, notamment après une réassociation BOOT ou un changement de code.
   - erreur réseau / délai expiré / absence de réponse `/health` : Tailscale est peut-être « en ligne » sur la console mais le **service sur port 18767 n'est pas joignable** depuis le téléphone.
6. Ne pas modifier ESP2 ou les secrets au hasard. Si `CONFIG_SE_JK_READ_CODE` est obsolète, deux solutions à examiner **après diagnostic** : remettre sur ESP1 le code JK déjà compilé sur ESP2 si l'utilisateur le connaît, ou reconfigurer/reflasher ESP2 de manière contrôlée (pas inclus dans cette version). Aucune synchronisation distante des secrets n'a été ajoutée au prototype.

Le VPN demeure **lecture seule** : aucune modification de paramètres JK, Wi-Fi, BOOT ou MAC n'est autorisée via ESP2. Ne jamais ouvrir le port 18767 sur la box Internet.
