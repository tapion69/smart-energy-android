# Smart Energy Android 0.3.17-dev — association ESP32 JK simplifiée

La page **Configuration et association** propose désormais un parcours **par lecteur actif** directement sous ESP 1 / ESP 2 / ESP 3 :

1. **Choisir ESP 2**. Son profil Wi-Fi, code et BMS est chargé indépendamment. Si une connexion de l'ancien lecteur est encore en cours, le changement demandé est mis en attente et appliqué automatiquement après sa fin ; un changement pendant la fenêtre BOOT est bloqué pour la sécurité.
2. **Rechercher mon ESP32**. Les scans Wi-Fi frais (moins de 30 secondes) sont utilisés, les résultats du cache Android sont signalés. Un seul lecteur frais non encore associé est automatiquement sélectionné ; aucune confusion avec un lecteur déjà rattaché à ESP 1. Si plusieurs appareils sont nouveaux, l'utilisateur choisit explicitement.
3. **Associer ESP 2 avec BOOT**. Un guide déroulant s'ouvre toujours, même si aucun SSID n'a encore été sélectionné ; dans ce dernier cas, il propose de relancer la recherche. Les validations du code (8–32 caractères), du Wi-Fi et des permissions restent dans le formulaire au lieu de le fermer.
4. **Premier BOOT** sur l'ESP32 allumé : tenir 4 secondes et relâcher. La fenêtre d'association temporaire est ouverte au plus 120 secondes.
5. Après l'envoi du code et la réponse **Code prêt**, **deuxième BOOT** sur le même lecteur : tenir 2 secondes et relâcher. Appuyer sur **Après le 2e BOOT : confirmer l'association**. Le résultat s'affiche immédiatement dans le panneau d'association.

Le flux conserve la protection d'identité avant l'enregistrement des codes. Les codes d'un ancien lecteur et les données BMS ne sont pas effacés. Une recherche d'ESP2 ne redéfinit pas ESP1.

**Firmware :** le lecteur JK corrigé **0.3.14-dev** reste recommandé pour produire un SSID matériel unique. Il n'est **pas nécessaire de reflasher l'ESP32** pour cette correction de l'interface Android. Le firmware VPN ne change pas.

**APK de test :** version `0.3.17-dev`, suffixe `.vpntrial317`, distinct des APK expérimentales précédentes (les associations ne sont pas migrées entre identifiants de paquet séparés). La validation matérielle doit être effectuée avant de supprimer l'application qui fonctionne.

Pour le test : brancher uniquement ESP2 si les réseaux restent ambigus, rechercher son SSID `SmartJK-Setup-XXXXXX` corrigé, puis suivre les trois boutons numérotés. Vérifier que les instructions 4 s / 2 s sont visibles et que l'état de la confirmation apparaît sur la même page.
