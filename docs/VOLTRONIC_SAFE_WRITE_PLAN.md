# Smart Energy Android — transactions Voltronic et profils (étape 1)

Source de référence : `tapion69/smart_voltronic`, branche `develop`,
`smart-voltronic/flows.json` : fonctions `requetes`,
`HA SET → Voltronic CMD`, `SET VERIFY + LEARN`, `Queue Enqueue`,
`Queue Pump` et `normalize state`.

## Ce qui est intégré en 0.3.33-dev

- Lecture native Voltronic conservée, aucune modification de l'ESP32-S3.
- Sauvegarde locale par INV1, INV2 et INV3 des capacités et valeurs QPIRI/QDOP
  réellement observées, sans utiliser les compteurs calculés Node-RED.
- Export/import JSON manuel, versionné, sans IP, MAC, Wi-Fi, code d'accès,
  numéro de série, informations de connexion ou commandes à envoyer.
- Import de profil = référence locale seulement ; **jamais une écriture**.
- Base de planification testable pour SET prioritaire, ACK et relecture
  QPIRI/QDOP prioritaires, état vérifié/non confirmé.
- Aucune action série SET ou HTTP POST n'est encore accessible à l'utilisateur.

## Invariants obligatoires de la phase d'écriture

1. Préparer un changement pour un seul INV configuré et identifié.
2. Lire à nouveau QPIRI ou QDOP et vérifier que la valeur actuelle
   correspond à la valeur qui a été montrée à l'utilisateur.
3. Demander confirmation explicite pour le paramètre et la nouvelle valeur.
4. Vérifier type, plage, pas autorisé, firmware/modèle et capacité.
   **Rejeter** toute valeur non supportée : ne pas la tronquer ni l'arrondir.
5. Une seule transaction à la fois par ESP, sous ordonnanceur série unique.
   Si une requête est en cours, attendre son achèvement. Le SET
   prend priorité sur les prochaines lectures ordinaires, sans
   interruption ni chevauchement d'une trame.
6. Dès la réponse au SET, lire QPIRI ou QDOP **en priorité**, avant
   le polling normal. Un ACK n'est pas une confirmation.
7. Confirmer uniquement sur relecture **postérieure** à l'écriture et
   correspondance à tolérance définie par paramètre.
8. En cas de NAK, perte de liaison, CRC, timeout ou valeur différente :
   indiquer « non confirmé », reprendre le polling normal et
   ne jamais envoyer une autre commande de réglage automatiquement.
9. Mémoriser la commande candidate réussie **uniquement** si la relecture
   confirme la nouvelle valeur. Pinner la connaissance au modèle/firmware.
10. Permettre une vérification manuelle et un nouveau choix explicite
    de candidate : les variantes ne sont pas forcément équivalentes.
    Le flow de référence propose notamment `PBCV` comme variante de
    bulk alors que `PBCV` est utilisé pour la recharge ; **ne pas faire
    ce fallback à l'aveugle**.
11. Ne jamais rejouer un SET après redémarrage de l'ESP ou restauration
    de profil. Les intentions non terminées restent « non confirmées ».
12. Ne pas enregistrer les codes d'accès ou données sensibles dans les
    journaux, JSON de profils, traces d'erreur ou rapports.

## Phase 2 — firmware expérimental, à valider avec matériel

- Ajouter un point d'entrée local authentifié **avec validation explicite**
  pour les transactions, jamais un pont de commandes série arbitraires.
- Partager le moteur RS232 existant avec le travailleur de transactions.
- Priorité : SET → accusé de réception → QPIRI/QDOP → comparaison →
  résultat ; pas de FIFO de lectures qui s'intercale.
- Exposer un identifiant de transaction et un état fini :
  préparé, envoyé, attente relecture, vérifié, refusé, non confirmé.
- Vérifier les règles propres à 24/48 V, à la famille et au firmware
  avant d'autoriser les paramètres à risque.
- Commencer par un test de modification non critique sur un appareil de
  test, puis élargir aux tensions/courants avec vérification spécifique.
- Protéger les anciens profils/associations JK et le VPN.

## Phase 3 — apprentissage confirmé

- N'apprendre une candidate de SET qu'après comparaison d'un QPIRI/QDOP
  frais et cohérent.
- Sauvegarder la candidate validée par couple (modèle, firmware, paramètre)
  et conserver un historique d'état sans secrets.
- Restaurer les profils comme références, sans mise en œuvre automatique ;
  forcer une nouvelle lecture du modèle avant toute modification.

## Remarque sur les sauvegardes

Le code Android de test utilise des identifiants d'application distincts.
Pour les tests, exporter explicitement un profil depuis l'ancienne
installation, puis l'importer dans la nouvelle. Il ne sera jamais
fusionné automatiquement à partir d'une autre application Android.
