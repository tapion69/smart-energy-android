# Smart Energy 0.3.6-dev — sommes multi-BMS et diagnostic d'écriture

## Constats sur les deux JK BMS

- L'utilisateur a validé sur ESP32 JK **0.3.5-dev** : **2/2 BMS BLE connectés simultanément**, mesures des deux fraîches.
- La synthèse affichait le SOC 59 %, les valeurs par BMS -250 W et -238 W, mais « watts moyens -244 W ». Ceci est une moyenne qui ne représente pas la puissance totale.
- Une tentative de modification d'un réglage pilote a échoué, sans motif exploitable sur la capture.

## Dashboard 0.3.6

- **SOC** : le calcul existant est conservé (pondéré par la capacité quand les capacités sont connues pour tous ; sinon moyenne).
- **Tension** : reste moyenne indicative, puisque les batteries sont séparément mesurées.
- **Puissance (W)** : somme algébrique des puissances individuelles, ex. -250 + -238 = **-488 W**.
- **Courant (A)** : somme algébrique des intensités individuelles.
- **Mesure manquante / BMS hors ligne** : affichage « — » plutôt que d'affirmer une puissance totale à partir d'une valeur partielle.
- Tests Android dédiés : deux BMS, trois BMS, signe positif/négatif, données manquantes.

## Modifications des paramètres : anomalie identifiée

Le firmware affichait une trame paramètres JK valide jusqu'à **30 min** après la dernière lecture, alors que `requestWrite()` refusait tout changement lorsque cette trame datait de plus de **60 s**. Son rafraîchissement automatique intervenait seulement toutes les **300 s** : l'écran pouvait montrer un paramètre « modifiable » que le BMS refusait ensuite.

Corrections :
- Nouvelle lecture 0x96 à intervalle maximum de **45 s** si la session est saine, avant l'expiration du garde-fou de 60 s.
- Statut `write_pilot` par BMS dans `/api/v1/details` : prêt / refus et raison ; inclut « identité JK non vérifiée », « trame paramètres périmée », « alarme », « liaison manquante », « écriture déjà en cours » et limitation de fréquence.
- Affichage explicite de la raison dans la carte Paramètres ; le bouton « Modifier » n'est proposé que si toutes les préconditions sont remplies.
- Message détaillé à proximité des boutons Paramètres après chaque tentative : commande transmise, refus, confirmation par BMS, échec/expiration.
- Une confirmation de changement n'est plus tirée d'une trame 0x01 reçue avant la demande explicite de relecture post-commande : le firmware attend après la commande, demande une nouvelle lecture 0x96 et vérifie la valeur reçue.

**Les protections restent inchangées** : aucune écriture à distance via Tailscale, liste blanche limitée, sélection explicite d'un BMS, comparaison de l'ancienne valeur, interdiction si alarme, lecture de trame CRC valide et identité JK vérifiée, confirmation d'une nouvelle valeur. Pas d'auto-répétition des commandes refusées. Code personnel **8 à 32 caractères** déjà pris en charge.

## Limite : comportement matériel réel encore à valider

L'échec initial n'avait pas de motif précis. L'incohérence 300 s / 60 s est un **bug confirmé dans le code**, mais rien ne prouve encore qu'elle était l'unique cause du refus sur la batterie. Une trame identité 0x03 non reconnue bloque également les écritures volontairement : cette condition de sécurité n'est pas désactivée.

Si une tentative échoue après 0.3.6, relever :
- Quel **réglage pilote** a été sélectionné, sans secrets.
- Quelle valeur était affichée et laquelle était demandée.
- Le texte affiché en haut de la carte **Paramètres** ou dans la zone de confirmation.

Ne pas envoyer de mots de passe JK, secrets du VPN ni détails confidentiels. Ne pas répéter des modifications tant que le statut est « non confirmé ».

## Installation sans perdre la configuration

1. Pour corriger **uniquement les moyennes**, l'APK 0.3.6 suffit ; les sommes sont calculées côté Android.
2. Pour tester également les **paramètres**, flasher **ESP32 JK (ESP 1) uniquement** avec l'artefact APP-ONLY à l'adresse **0x10000, sans Erase Flash**. Les deux adresses MAC, le Wi-Fi et le code restent normalement en NVS. Ne pas utiliser l'artefact COMPLET-ERASE-FIRST.
3. Installer l'APK 0.3.6 séparément des APK précédentes. Réassocier localement le code JK courant si nécessaire, sans lancer de récupération BOOT.
4. Vérifier « firmware 0.3.6-dev », « 2/2 JK en ligne », puis la **puissance totale** -488 W pour les valeurs de la capture précédente (les valeurs réelles changent dans le temps).
5. Tester une fois un réglage pilote non critique avec une confirmation manuelle puis vérifier son nouvel état. Si refus, photographier la raison.
6. **ESP32 VPN/Tailscale (ESP 2) : aucun changement, aucun reflash.**

Pour trois BMS, on attend la validation durable 2/2 et la correction des erreurs d'affichage et de sécurité avant élargissement.
