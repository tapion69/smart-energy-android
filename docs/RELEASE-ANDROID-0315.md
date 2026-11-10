# Smart Energy Android 0.3.15-dev — vue Accueil compacte et édition JK plus fluide

**Branche :** `dev` uniquement. **ESP32 JK :** firmware `0.3.13-dev` inchangé ; aucun flash nécessaire. **VPN :** inchangé.

## Accueil uniquement

La carte « Vue globale » et les résumés de tous les BMS apparaissent **exclusivement** sur l'onglet `Accueil`. L'ouverture d'un BMS affiche ses seuls sous-onglets `Batterie / Détails / Paramètres` ; la synthèse globale n'est plus dessinée en permanence. Le bouton `Accueil` est disponible même avec un seul BMS associé.

## Présentation sur téléphone

La vue globale abandonne le long empilement vertical au profit d'une **grille à deux colonnes** sur trois rangées :

| Colonne gauche | Colonne droite |
| --- | --- |
| Anneau SOC global | Tension moyenne |
| Puissance totale | Courant total |
| Alarmes | Température max et nom du BMS concerné |

Les cartes récapitulatives des batteries sous la synthèse sont également disposées deux par ligne. Les mesures demeurent issues uniquement de données BMS récentes, et la vue n'additionne pas les BMS hors ligne.

## Édition des paramètres : suppression de l'attente superflue

- La règle **15 secondes minimum entre deux écritures**, imposée par le firmware JK, **est conservée**. Aucun raccourci d'une protection du BMS.
- Les marges d'attente côté APK passent de 2,1 secondes à 300 ms. Les vérifications d'état après écriture utilisent des requêtes courtes sur le **cache HTTP local** de l'ESP32.
- **Si la précédente écriture est confirmée et relue**, aucun nouvel aller-retour BLE n'est déclenché uniquement pour permettre le second réglage : le même jeu de réglages (récent) reste valide pour le firmware.
- Une seule relecture BLE ciblée peut encore être demandée si les paramètres sont devenus trop anciens. Une absence de confirmation ne provoque **jamais** de seconde écriture automatique.
- Les confirmations de l'utilisateur, le mode modification volontaire, le verrouillage VPN en lecture seule, l'identité BMS et les relations de sécurité restent inchangés.

## Tests et installation

Tests JVM : `JkDashboardVisibilityTest`, `JkEditRecoveryPolicyTest`, précédents tests de température et d'agrégation. Compilation Android via workflow GitHub Actions `Build development prototypes`.

Version APK : **0.3.15-dev**, paquet debug `.vpntrial315` installé séparément de `.vpntrial314`. Les profils précédemment enregistrés **ne sont pas transférés automatiquement** entre installations de test à identifiants distincts. Ne désinstallez pas l'APK fonctionnelle.

La disponibilité exacte du second réglage dépend toujours de la confirmation reçue par le BMS, de son état et de l'actualisation effective des données ; l'intégration n'a pas encore été validée sur le matériel réel.
