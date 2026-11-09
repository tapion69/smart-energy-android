# Smart Energy Android 0.3.10-dev — retirer et réassocier un JK déjà connecté

## Symptôme utilisateur et cause

Après connexion des deux ou trois JK BMS, l'application **ne les montre plus lors du scan Bluetooth** ; l'écran de sélection n'affiche alors aucune case cochée. L'ancienne version créait la liste des cases **uniquement depuis `/api/v1/bms/scan`** : un JK connecté peut ne plus émettre d'annonces ou ne pas être retourné par un scan. La MAC reste pourtant enregistrée en NVS sur l'ESP32 JK.

## Correction application Android uniquement

- Bouton **« Afficher mes BMS enregistrés »**, indépendant d'un scan, utilisant `GET /api/v1/bms`.
- Cases à cocher toujours visibles pour **tous les JK enregistrés dans l'ESP32**, même si aucun n'apparaît dans les annonces BLE.
- Scan de découverte ajouté **en complément** : fusion par MAC entre **BMS sauvegardés**, **BMS anciennement connus du téléphone**, et **appareils actuellement détectés**. Aucune duplication si la même MAC apparaît plusieurs fois.
- **Sauvegarder** envoie la sélection complète par `PUT /api/v1/bms`, accepte une liste vide, puis vérifie sa persistance par `GET /api/v1/bms`. Les BMS décochés sont déconnectés par le gestionnaire ESP32 existant et disparaissent du tableau de bord après mise à jour des mesures.
- Les adresses retirées restent dans l'historique local de **cette installation de l'application**. Elles apparaissent non cochées sous **« Ancien BMS connu · réassociation possible »**. Pour les réactiver, recocher l'adresse et sauvegarder, sans nouveau scan ni changement de paramètres du BMS.
- Confirmation supplémentaire lors de la suppression d'un ou plusieurs JK, pour éviter un clic accidentel.
- Toutes ces opérations sont **interdites via la passerelle ESP32 VPN** (lecture seule) ; utiliser le Wi-Fi local relié directement au JK.
- Le firmware JK 0.3.8 / 0.3.7 / 0.3.6 et l'ESP32 VPN sont **inchangés**. L'API existante accepte déjà la nouvelle liste.
- Tests unitaires Kotlin : déjà connecté mais non découvert ; retrait et réactivation sans scan ; déduplication ; refus de MAC invalide ; liste vide.

## Test 3 → 2 → 3

1. Ne pas reflasher l'ESP32. Installer seulement l'APK `smart-energy-android-v0.3.10-bms-selection-debug-test` à côté de 0.3.9/0.3.8.
2. Cette APK de test possède son **propre stockage Android** et peut demander une première connexion en Wi-Fi local avec le code JK déjà choisi. Ne pas changer le code JK ni refaire l'association BOOT si le code est connu.
3. Ouvrir « Configuration et association », section **3. Gérer les JK BMS enregistrés**, puis **« Afficher mes BMS enregistrés »**. Les trois BMS doivent être visibles **sans scanner**.
4. Décocher le troisième BMS ; cliquer **« Sauvegarder les batteries choisies »** et confirmer sa suppression. Les paramètres du BMS physique restent inchangés.
5. Vérifier le tableau de bord : **2/2 JK en ligne** ; le troisième doit rester visible dans la liste de gestion comme **« Ancien BMS connu · réassociation possible »**, non coché.
6. Recocher le troisième et enregistrer. Attendre la reconnexion du JK et vérifier **3/3 JK en ligne**. Aucun nouveau scan nécessaire.
7. Vérifier que le choix est persistant après redémarrage de l'application et de l'ESP32, sans toucher aux seuils de protection.

**Limite :** les anciennes MACs sont conservées dans le stockage local de cette installation Android, non dans une base de données distante. Si l'application est désinstallée ou une nouvelle APK de test avec un *applicationId* distinct est installée, cet historique local n'est pas automatiquement transféré. Les MAC toujours sauvegardées dans l'ESP32 seront relues normalement. Les MAC retirées et perdues du téléphone pourront être retrouvées par un scan ultérieur lorsqu'elles annoncent leur présence BLE.
