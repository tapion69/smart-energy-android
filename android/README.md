# Android Studio — application Smart Energy

**État : conception. Aucun projet Android exécutable n'est encore présent dans ce dépôt.**

Cible : application Android native en Kotlin, avec un assistant d'appairage et de provisionnement, connexion locale, gestion de plusieurs passerelles et écrans JK / Voltronic indépendants.

### Première livraison de code exigée

- Parcours « Ajouter une passerelle JK » qui fonctionne avec un vrai ESP32 de test.
- Provisionnement Wi-Fi depuis le téléphone **sans YAML**.
- Scan des BMS depuis l'ESP32 ; sélection et sauvegarde des adresses dans la passerelle.
- Mesures et état de fraîcheur vrais ; aucun chiffre de démonstration sur l'écran opérationnel.
- Enregistrement des passerelles, y compris identification matérielle stable.
- Écran des mises à jour (état « non publié » tant qu'aucune release n'existe).

La première version **ne doit pas** demander à l'utilisateur d'éditer une MAC ou un mot de passe dans du code.
