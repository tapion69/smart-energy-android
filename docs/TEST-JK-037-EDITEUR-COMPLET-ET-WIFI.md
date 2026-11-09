# Smart Energy Android / JK 0.3.7-dev — éditeur de tous les paramètres JK02 reconnus

## État de départ

- Les trois JK BMS ont été sélectionnés et ont tous remonté leurs mesures dans la 0.3.6, selon le retour utilisateur.
- Plusieurs modifications pilotes de paramètres ont fonctionné lors de premiers essais.
- Le calcul de synthèse SOC/tension/W/A et la reconnexion BLE persistante restent inchangés.
- **ESP32 VPN (passerelle Tailscale distincte) inchangé.**

## Développements de cette branche

### Tous les paramètres JK02 répertoriés

Les **47 registres** présents dans le catalogue `jkextra::PARAMS` peuvent désormais être préparés dans l'éditeur. Chaque modification est adressée par la MAC du BMS et suit son propre processus de confirmation. Elle n'est PAS automatiquement copiée sur les autres BMS.

Les protections ne sont **pas** considérées validées sur le matériel simplement parce que l'application compile. Le comportement exact des écritures dépend de la variante de firmware JK et doit être testé progressivement.

Classement et protection :
- Niveau 0 : préférences courantes (écran, veille, journalisation, flottement, délai de veille). Confirmation simple.
- Niveau 1 : paramètres opérationnels (équilibrage, précharge, etc.). Confirmation et avertissement.
- Niveau 2 : tensions/courants/limites de protection, capteurs de température, charge/décharge, capacité et nombre de cellules. Double confirmation : une revue avant changement, puis saisie exacte de `PROTECTION`. **Le serveur ESP32 exige aussi** `risk_confirmation=I_ACCEPT_PROTECTION_RISK` : une vieille APK ne peut pas déclencher ces écritures.

Règles générales conservées :
1. Accès local authentifié uniquement, jamais via ESP32 VPN.
2. Une seule commande par confirmation et BMS ; aucune répétition automatique.
3. BMS connecté avec télémetrie récente, sans alarme.
4. Paramètres CRC vérifiés et relus depuis moins de 60 secondes.
5. Identité JK reconnue à partir de la trame 0x03. **Si la trame 0x03 est absente ou non reconnue, l'écriture reste bloquée** (ne pas contourner).
6. Ancienne valeur identique à la lecture en cache, nouvelle valeur representable exactement selon le registre.
7. Vérification de cohérence des seuils dépendants (UVP/retour, OVP/retour, flottement/charge demandée, certaines températures).
8. Écriture de la commande, nouvelle demande de trame 0x96 puis confirmation **uniquement** de la valeur relue.

**Important :** une écriture à risque extrême (par exemple désactiver la surveillance thermique, changer les limites de courant ou le nombre de cellules) peut rendre un pack dangereux. N'utiliser les valeurs que si elles sont validées par la documentation du fabricant et les caractéristiques de la batterie et du câblage. Les tests logiciels ne constituent pas cette validation. Ne pas chercher à contourner une écriture refusée.

### Wi-Fi : section 4 cachée mais conservée

La configuration Wi-Fi principale se fait lors du premier appairage physique BOOT. La section détaillée **4** a disparu de l'affichage normal : l'utilisateur peut ouvrir **« Changer le Wi-Fi de l'ESP32 (dépannage) »** pour gérer une nouvelle box/SSID/mot de passe. Le mécanisme d'appairage initial, la reprise BOOT et le code personnel de 8 à 32 caractères sont inchangés.

### Autres invariants

- Somme des watts et des ampères pour le tableau de bord multi-BMS, tension moyenne et SOC pondéré si possible.
- Sessions BLE simultanées : aucune alternance voulue, pas de reset général NimBLE.
- Accès distant via ESP32 VPN : **lecture seule** et aucun changement de firmware ni des tokens.

## Test recommandé

1. Ne pas toucher à la 0.3.6 tant que les trois BMS restent en cours d'observation.
2. Installer l'APK 0.3.7 **séparément** (`.vpntrial37`) pour constater que la section Wi-Fi détaillée est masquée, mais accessible via le bouton de dépannage.
3. Pour tester les écritures de tous les paramètres, reflasher uniquement **ESP32 JK**, **APP-ONLY à l'adresse 0x10000**, **sans effacer NVS/Flash**, et reconnecter l'APK avec le code JK actuel. NE PAS reflasher l'ESP32 VPN.
4. Vérifier **firmware 0.3.7-dev**, trois BMS en ligne en continu, puis démarrer par un **paramètre non critique** connu.
5. Pour les paramètres de protection : ne pas tester au hasard. Vérifier les valeurs d'origine et leurs dépendances avant toute modification, et les conserver si le comportement n'est pas validé par une lecture officielle constructeur.
6. Si une modification échoue, relever la raison fournie par l'APK et vérifier dans l'application JK d'origine avant de retenter. Ne jamais considérer `sent_unconfirmed` comme réussite.

Cette version reste une **version expérimentale**. Le catalogue est complet pour les registres JK02 connus, mais ni la compatibilité de chaque écriture avec chaque firmware JK ni sa sécurité à l'usage n'ont encore été validées en laboratoire.
