# Smart Energy Android 0.3.9-dev — correction du basculement VPN → LAN

## Problème utilisateur

Sur la 0.3.8, avec le badge VPN Android/Tailscale actif, le tableau de bord affiche :
`Erreur : Connexion locale bloquée par le VPN`, et tente l'ancienne adresse locale `192.168.1.53`.
Le firmware JK et ses trois connexions BLE ne sont PAS la cause de ce message Android : il est produit par la fonction `networkError` de l'application quand le transport courant est LAN.

## Deux défauts identifiés

1. `vpnConnected()` observait seulement `ConnectivityManager.activeNetwork` : sous certains environnements Android, le réseau actif renvoyé est Wi-Fi ou mobile alors qu'un réseau VPN Tailscale distinct existe.
2. Le démarrage et la boucle périodique vérifiaient **toutes** les préconditions VPN (case automatique cochée, IP Tailscale, token enregistré et VPN détecté). Si une seule échouait, ils relançaient aveuglément la connexion Wi-Fi locale au lieu de laisser l'utilisateur finir la configuration VPN.

## Correctif Android 0.3.9

- Détection VPN par les réseaux `ConnectivityManager.allNetworks` et non uniquement `activeNetwork`, avec repli de détection si nécessaire.
- Sélection explicite `connection_preference`: `gateway` lorsque l'utilisateur presse « Enregistrer et tester mon ESP32 VPN » ou le bouton rapide, `local` seulement lorsqu'il choisit délibérément la connexion Wi-Fi locale.
- Si Tailscale est détecté ou la passerelle VPN a été explicitement choisie, **aucun essai automatique sur l'IP locale** lorsque la configuration VPN manque, le tunnel est absent ou le token est invalide. Le message oriente vers « Accès extérieur — ESP32 VPN ».
- Si l'option « Connexion VPN automatique » est activée ET un token valide est déjà mémorisé, VPN prioritaire sur tout ancien IP LAN lorsque Tailscale apparaît, y compris si le LAN était encore affiché.
- Ajout d'un bouton direct **« Se connecter via ESP32 VPN / Tailscale »** sur l'accueil. Il sélectionne le mode VPN ; les anciennes routes HTTP de la passerelle en lecture seule et le header de token `X-Gateway-Token` sont conservés.
- Tests unitaires Android `JkTransportPolicyTest` : pas de repli local sous VPN, sélection VPN manuelle, priorité VPN automatique, choix local explicite, fonctionnement normal à la maison sans VPN.

## Limites et données à conserver

- **AUCUN changement des firmwares ESP32 JK (0.3.8) ni ESP32 VPN/Tailscale**. La CI 0.3.9 construit l'APK uniquement.
- L'APK de test `.vpntrial39` s'installe à côté de `.vpntrial38` et possède son propre stockage sécurisé : elle ne récupère PAS automatiquement l'association JK, l'IP de l'ESP32 VPN ou le token mémorisés dans l'APK 0.3.8. Pour l'usage VPN, une première association LAN avec le code JK connu peut rester nécessaire **si cette nouvelle APK n'a encore jamais été associée**. Ne pas réinitialiser BOOT ni modifier le code du JK pour cela.
- Utiliser l'IP Tailscale du **deuxième ESP32 VPN** (plage 100.64/10) et le `CONFIG_SE_GATEWAY_TOKEN` (**pas** le code personnel JK ni `tskey-auth`). La passerelle VPN doit toujours avoir le code `CONFIG_SE_JK_READ_CODE` identique au code JK actuel.
- Si le VPN ne fonctionne toujours pas, tester `http://IP_TAILSCALE_ESP2:18767/api/v1/health` dans Chrome avec Tailscale actif. Un échec indique un problème réseau/port indépendant de la priorité de l'APK. Une réponse `/health` ne valide pas encore les routes de télémétrie ni le code JK.
- Ne jamais envoyer de jeton, code JK, clé Tailscale ou mot de passe Wi-Fi dans une capture publique.
