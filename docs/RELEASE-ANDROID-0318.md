# Smart Energy Android 0.3.18-dev — 4+ BMS, interface compacte et notifications (10 novembre 2026)

**Branche :** `dev`. **Android :** `0.3.18-dev` / code 32 / application expérimentale `fr.smartenergy.android.vpntrial318`.

## Changements demandés et inclus

1. **Onglets Batterie communs aux trois lecteurs JK** : `Accueil` + tous les BMS configurés sur ESP 1, 2 et 3. Les libellés sont uniquement les noms personnalisés (ou `Batt-1`, etc.), sans afficher ESP1/ESP2 dans la barre. La sélection d'une batterie d'un autre lecteur bascule vers son vrai profil, son IP/son code et ses pages individuelles.
2. **Tri par numéro** : le premier numéro trouvé dans le nom est utilisé pour ordonner les onglets : Batt-1, Batt-2, Batt-3, Batt-4, indépendamment de l'ordre d'ajout et de l'ESP32. Les noms sans numéro viennent ensuite, sans permutation arbitraire.
3. **Anneaux SOC** (global + individuel) : 0–29 % rouge, 30–59 % orange, 60–100 % turquoise. Valeur non disponible : gris, jamais un faux SOC.
4. **Accueil plus compact** : tension centrée verticalement dans une carte à hauteur adaptée, valeurs plus grandes, rangées plus courtes pour puissance, courant, alarmes et température. Les mesures continuent d'être issues de BMS réellement disponibles.
5. **Configuration repliable** : un panneau ouvert à la fois dans la configuration ; les onglets de sélection ESP restent accessibles et les contrôles d'association ne sont pas supprimés. Les sections Wi-Fi et code gardent leurs validations.
6. **Onglet Réglages** indépendant de l'onglet de paramètres JK : activation volontaire des notifications d'alarme et bouton **Envoyer une notification de test**, sans provoquer de défaut réel du BMS.
7. **Aucun historique** : ni graphiques ni persistance des mesures dans le téléphone ou l'ESP32. La surveillance ne sauvegarde pas les données des capteurs.

## Notifications lorsque l'application n'est pas à l'écran

- Android utilise un **service au premier plan** de type `connectedDevice` uniquement après activation explicite. Il s'accompagne d'une notification permanente « Surveillance active ». L'APK demande la permission Android `POST_NOTIFICATIONS` lorsque nécessaire.
- Toutes les ~30 secondes, il interroge les **API de télémétrie cachées** des ESP32 JK configurés, sans nouveau scan ni nouvelle connexion BLE et sans modifier les BMS.
- Une lecture valide, fraîche et alarmée produit une **notification ponctuelle portant le nom enregistré du BMS**. Une alarme maintenue active n'est pas répétée indéfiniment ; une nouvelle alarme après retour normal peut en déclencher une nouvelle.
- Aucune notification n'est générée en cas de donnée indisponible ou de BMS temporairement hors ligne. Ce premier pilote couvre **l'alarme BMS**, pas encore les alertes hors ligne ou seuils personnalisés.
- En local, seule une IP privée du lecteur avec le code du profil Android est interrogée. Sur un VPN Tailscale reconnu, les routes GET de la passerelle VPN sont utilisées uniquement si le mode VPN est configuré/activé dans l'application. **L'ancienne passerelle en service peut ne prendre en charge que le lecteur JK1** ; il faudra tester une passerelle multi-lecteurs séparée pour la surveillance distante complète.
- Android peut limiter/arrêter l'exécution malgré le service (économiseur batterie, restrictions constructeur, arrêt forcé, redémarrage), ou bloquer le réseau. **Les notifications ne sont pas garanties 24 h/24**. Cette fonctionnalité n'est pas un système de sécurité certifié.
- Le bouton de test vérifie les notifications sans écrire dans les registres JK. Aucune surveillance ne démarre automatiquement après installation ; elle est désactivée par défaut.
- Aucun permissionnement ou route HTTP supplémentaire n'est ajouté côté ESP32, et aucun token n'est enregistré en clair.

## Installation sans perte

- `0.3.18-dev` est distribuée avec le suffixe de paquet `.vpntrial318`. Elle s'installe **à côté** des anciennes variantes `.vpntrial317`/etc. Les préférences et les secrets Keystore **ne sont pas migrés automatiquement entre APK distinctes** : réassocier les lecteurs dans cette variante sans supprimer l'application qui fonctionne.
- **Ne reflasher ni ESP32 JK ni ESP32 VPN** pour ces changements Android ; aucun firmware n'a été modifié.
- Ne jamais utiliser Erase Flash/FACTORY sur un lecteur déjà configuré ni reconfigurer la clé Tailscale pour tester une fonction d'interface.
- Compilation et tests en CI : GitHub Actions > `Build development prototypes`. Vérifier le **dernier build du commit concerné**, pas un ancien artifact.

## Validation matérielle à réaliser

1. Contrôler `Accueil / Batt-1 / Batt-2 / Batt-3 / Batt-4` même avec deux ESP JK et le tri par numéro. Cliquer Batt-3 et Batt-4 : voir les **vraies** pages Batterie/Détails/Paramètres du deuxième lecteur.
2. Vérifier le SOC rouge/orange/turquoise sur des données réelles ; ne jamais modifier le SOC BMS pour produire artificiellement un test visuel.
3. Vérifier les cadres compacts et chaque panneau repliable, notamment association BOOT ESP2.
4. Autoriser les notifications Android puis activer le monitoring dans Réglages. Vérifier la notification permanente, utiliser la **notification de test** sans générer d'alarme réelle.
5. Vérifier ultérieurement les alertes lors d'une véritable alarme, sans toucher aux protections du BMS. Tester réseau local, écran éteint, puis VPN si disponible ; noter que les restrictions Android peuvent interrompre le service.
6. Comparer l'affichage des valeurs live : lecteur sélectionné interrogé environ **2,5 s**, autres lecteurs à tour de rôle, mise à jour seulement avec les cache BLE effectivement disponibles. Aucun historique.
