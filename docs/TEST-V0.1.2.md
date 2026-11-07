# Test v0.1.2-dev — Une association, puis connexion quotidienne

**Cette version est un prototype de développement, pas une application distribuable aux clients.**

## Pourquoi une nouvelle version ?

Jusqu'à v0.1.1, il fallait entrer à chaque essai le SSID temporaire et le code, puis maintenir BOOT pour lancer un scan BLE. Cette expérience est inutilement répétitive.

En v0.1.2-dev :
- L'application mémorise le nom de la passerelle, son identifiant et son dernier IP local.
- Le code d'association est stocké **chiffré par Android Keystore**, pas en clair dans les préférences.
- La connexion au réseau domestique est automatique au lancement lorsque l'adresse a été retenue.
- Un bouton de découverte mDNS recherche une passerelle `smartjk-XXXXXX`; saisie manuelle de l'adresse IP locale possible une fois si mDNS échoue.
- Les scans et modifications de la **liste de MAC JK** peuvent se faire par Wi-Fi local avec le **firmware 0.1.2-dev** et la clé de configuration déjà associée.
- Le mode `SmartJK-Setup-XXXXXX` ne sert qu'à l'association initiale et au dépannage / changement de Wi-Fi.

## Installation de la version de test

1. Conserver une passerelle ESP32 **de test** séparée de la production Home Assistant.
2. Flasher le firmware 0.1.2-dev sur une carte `esp32dev` compatible. **Le flash complet factory à 0x0000 peut effacer NVS : Wi-Fi et code de configuration seront alors à refaire UNE fois.** Un binaire app-only distinct existe pour un outil de flash capable d'écrire précisément à 0x10000, mais ne doit pas être envoyé tel quel via un outil factory.
3. Installer l'APK `0.1.2-dev`. Les anciennes APK debug de GitHub Actions peuvent avoir une **signature différente** ; Android peut alors imposer la désinstallation de l'ancienne APK de test. Ce problème doit être corrigé avant distribution avec une clé de signature stable, protégée hors du dépôt.
4. Dans « Première association », entrer **une fois** le SSID temporaire et le code du firmware (ne pas les publier).
5. Configurer le Wi-Fi de la maison si nécessaire ; dans « Connexion quotidienne », cliquer sur **Rechercher automatiquement** ou saisir une fois l'adresse IP privée de l'ESP32. L'appli vérifie l'identifiant et les droits, puis enregistre cette IP.
6. Fermer/réouvrir l'application : elle doit se connecter sur l'IP mémorisée **sans redemander** le SSID ni le code.
7. Lancer le scan et enregistrer les JK depuis le réseau local, sans BOOT.

## Sécurité du prototype

Le code est chiffré **au repos sur le téléphone**, mais **le transport HTTP sur le LAN n'est pas chiffré**. Ce protocole est donc **provisoire** et limité aux essais sur un réseau local de confiance. Ne pas rediriger de port sur Internet, ne pas utiliser comme produit public. Le scan retourne les périphériques BLE proches, sans identification JK garantie.

L'APK 0.1.2-dev ne lit toujours **aucune mesure réelle JK** : le firmware répond `telemetry:false`. L'accès extérieur et les mises à jour OTA ne sont pas implémentés.

## Critères du test

- Le firmware ESP32 et l'APK doivent compiler correctement sur GitHub Actions.
- L'ESP32 rejoint la box et répond à `/api/v1/info`.
- Le premier association reconnaît la passerelle et mémorise la clé.
- Après fermeture puis redémarrage de l'APK, le même ESP32 est retrouvé sur Wi-Fi local sans saisie répétée.
- La recherche BLE et l'enregistrement d'une sélection JK fonctionnent sur le LAN.
