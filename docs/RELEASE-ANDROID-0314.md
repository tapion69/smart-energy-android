# Smart Energy Android 0.3.14-dev — corrections accueil, températures et édition JK

**Branche :** `dev` uniquement. **Firmware JK requis :** le `0.3.13-dev` existant suffit, aucune nouvelle image ESP32 à flasher.

## Corrections incluses

- Les messages techniques « Connecté automatiquement », version firmware, mémoire minimale, diagnostics BLE/Wi-Fi, etc., ne s'affichent plus en haut de l'accueil en mode normal. Ils sont disponibles avec **Configuration et association → Debug et diagnostics**. Les retours de configuration restent accessibles sur la page Configuration.
- La barre **Accueil / BMS 1 / BMS 2** est placée immédiatement sous le titre « Tableau de bord JK », devant la vue globale. Les onglets Batterie / Détails / Paramètres viennent juste après lorsqu'une batterie est sélectionnée.
- **Température max** ajoutée à la vue globale : maximum valide parmi `temp1_c`, `temp2_c`, `temp3_c`, `temp4_c`, `temp5_c` et `mos_temp_c` de tous les BMS en ligne sur les trois ESP32. Le nom personnalisé de la batterie concernée est affiché, sinon « ESP X · BMS Y ». Une batterie hors ligne n'entre pas dans le calcul. Les températures manquantes ou incohérentes ne sont pas inventées.
- Sur la page Paramètres, les motifs détaillés de blocage, âge de lecture et fréquence de rafraîchissement sont réservés à Debug. La fréquence affichée, quand elle l'est, est **30 minutes**, plus la lecture initiale et les relectures à la demande.
- **Bug du deuxième paramètre** : le pilote firmware impose toujours une temporisation de sécurité de 15 s entre deux écritures. Après une écriture **confirmée**, l'APK attend 16,5 s avec marge, effectue une relecture ciblée locale, vérifie via `GET /api/v1/details` que `write_pilot.ready` est revenu à vrai, puis remet à disposition les liens Modifier sans exiger de quitter/revenir dans l'application. Les consultations répétées de `/details` lisent le cache HTTP, pas la radio BLE ; une seule requête de relecture BLE est envoyée à ce stade.
- Un lien **Actualiser les réglages** est présent pendant le mode modification pour relancer ponctuellement une relecture si la liaison l'a manquée ; aucune écriture automatique ni contournement des conditions de sécurité du BMS.
- Le service de relecture utilise un worker séparé, afin que les mesures SOC/puissance restent actualisées pendant les quelques secondes nécessaires à la vérification.

## Règles de sécurité préservées

- Écritures uniquement en Wi-Fi local authentifié ; aucune écriture sur le VPN/Tailscale.
- Une seule écriture par confirmation, aucune relance automatique d'une écriture échouée ou non confirmée.
- Double confirmation du risque pour les protections critiques.
- Le firmware continue d'imposer identité vérifiée, état BMS sûr, paramètres de moins de 60 secondes et espacement minimal des écritures.

## Installation et validation

- Version debug Android : **`0.3.14-dev`**, suffixe de test `.vpntrial314`. Elle s'installe à côté de `0.3.13-dev` sans désinstaller ni écraser ses données. **L'association locale n'est pas automatiquement importée entre les deux installations de test**. Une vraie politique de signature stable est nécessaire pour des mises à niveau transparentes à long terme.
- Tests automatiques : maximum température multi-lecteurs / noms personnalisés / lectures corrompues ; temporisation de relecture après écriture ; tests Android existants.
- Tests matériels restant à faire : page Debug activé/désactivé, BMS 1/BMS 2, une écriture bénigne (non critique) puis une deuxième après fin de la sécurité 15s, et surveillance simultanée des mesures. Ne jamais provoquer un changement de protection critique pour simplement tester l'interface.

**État :** code compilable, validation matérielle non encore effectuée. Ne pas désinstaller l'APK fonctionnelle avant confirmation.
