# Smart Energy Android 0.3.19-dev — alertes personnalisées multi-BMS

**Date :** 10 novembre 2026 · **branche :** `dev` · **APK :** `0.3.19-dev`, versionCode 33, paquet de test `fr.smartenergy.android.vpntrial319`.

## Nouveautés

- **Vue globale — Alarmes** : `Aucune` en vert uniquement si tous les BMS configurés transmettent un état d'alarme fiable et récent ; présence d'une ou plusieurs alarmes en rouge ; `À vérifier` en gris si données ou état manquants.
- **Réglages → Notifications et seuils JK** : bouton général pour activer/arrêter un service Android de premier plan (notification permanente), et quatre catégories de notifications indépendamment activables :
  - alarmes natives émises par chaque JK BMS ;
  - SOC **global** inférieur au seuil en pourcentage ;
  - SOC de **chaque BMS** inférieur au seuil en pourcentage ;
  - tension d'une **cellule quelconque** au-dessus du seuil en volts, avec numéro et tension de la cellule maximale ;
  - température d'une **sonde quelconque ou MOS** au-dessus du seuil en °C, avec nom de la sonde et température maximale.
- Seuils contrôlés et mémorisés sur le téléphone : SOC 1–100 %, cellule 2,000–4,500 V, température 0–120 °C. Virgule décimale française acceptée. Règles strictes : SOC **strictement inférieur**, cellule et température **strictement supérieures**.
- Le service lit via les **API GET existantes** les mesures fraîches des lecteurs JK 1, 2, 3 ; jusqu'à 2 JK par ESP32. Aucun ajout de mesure, aucune commande d'écriture, aucun historique.
- Notifications par **événement et par BMS** ; pas de répétition toutes les 30 secondes tant que le dépassement persiste. Une nouvelle notification est possible après retour à une valeur normale et nouveau franchissement. Chaque message affiche le nom personnalisé de la batterie, la mesure et le seuil. Pour plusieurs cellules d'un BMS, une seule notification reprend la plus haute.
- La règle SOC global exige **tous les BMS configurés en ligne et frais** : pondération par capacités nominales si disponibles pour tous, sinon moyenne arithmétique, comme le tableau de bord.
- Une mesure hors ligne, périmée, absente ou incohérente n'active **jamais** une nouvelle alerte et ne provoque **pas** de fausse fin d'alerte. Les pertes de connexion ne sont pas encore une catégorie distincte.

## Surveillance quand l'écran de l'application est fermé

- Dans Réglages, cocher `Activer la surveillance en arrière-plan`. Android demande l'autorisation des notifications. L'utilisateur doit ensuite voir la notification permanente `Smart Energy : surveillance active`.
- Le service effectue une vérification environ toutes les 30 secondes. Ce délai n'est **pas une garantie** : restrictions Android, mode économie d'énergie, force-stop, Wi-Fi/VPN/Tailscale indisponible ou démarrage du téléphone peuvent interrompre la surveillance.
- Une passerelle VPN Tailscale qui ne relaie que le premier JK ne permet pas d'alerter à distance sur ESP2/ESP3. Ne pas changer le firmware VPN fonctionnel pour tester l'APK. Les alertes de SOC global sont désactivées temporairement lorsque les BMS configurés ne peuvent pas tous être lus.
- Les notifications ne remplacent **jamais** les protections physiques du JK BMS, et le service n'est pas certifié pour la surveillance de sécurité.
- Les valeurs courantes ne sont pas stockées dans l'ESP32, le téléphone ou un serveur. Seuls le réglage du seuil et la gestion des conditions d'alerte en mémoire volatile du service sont conservés.

## Protocole de test sans provoquer d'alarme physique

1. Installer l'APK `0.3.19-dev` côte à côte avec `0.3.18-dev`. Son **paquet distinct** possède des associations Android Keystore distinctes : associer les ESP32 dans la version de test sans désinstaller l'ancienne APK et sans effacer la NVS.
2. Sur le Wi-Fi local, valider `4/4 BMS en ligne` en utilisant 2 ESP32 JK. Vérifier le tri des onglets et la couleur verte de `Aucune`.
3. Ouvrir **Réglages → Notifications et seuils JK**. Activer `Tension d'une cellule supérieure` et saisir un seuil sûr qui sera **déjà dépassé par les valeurs actuelles** (par exemple 3,200 V si les cellules sont au-dessus de 3,200 V ; ne jamais modifier les BMS pour le test). Enregistrer.
4. Activer la surveillance Android, autoriser les notifications. Fermer **l'écran de l'APK sans forcer son arrêt**. Vérifier qu'une notification comportant le nom de chaque batterie concernée arrive, avec la cellule la plus haute, sa tension et le seuil. Puis restaurer la valeur choisie, par exemple `3,500 V` et enregistrer.
5. Tester séparément SOC global, SOC de chaque BMS, température, et les alarmes réelles uniquement si elles surviennent spontanément. La fonction `Envoyer une notification de test` confirme les permissions Android sans modifier de mesure.
6. Après un dépassement, s'assurer qu'il n'arrive pas de notifications répétées ; après un retour sous le seuil puis nouveau dépassement, une nouvelle alerte doit arriver.
7. Désactiver le bouton général : la notification permanente de surveillance doit disparaître. Aucun changement de firmware ESP32 JK ou VPN n'est nécessaire.

**Qualité :** tests JVM sur lecture complète/incomplète, calcul SOC pondéré, limites des seuils, quatre BMS et capteurs indisponibles ; compilation et APK GitHub Actions à vérifier avant installation matérielle.
