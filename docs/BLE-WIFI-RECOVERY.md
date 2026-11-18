# Smart Energy — récupération du Wi-Fi hors ligne par Bluetooth (version de test)

## Pourquoi

Après changement de box Internet, l'ESP32 VPN n'est plus joignable dans Tailscale si son ancien SSID ou mot de passe ne fonctionne plus. Chaque module dispose donc d'une récupération BLE **locale** et **physiquement déclenchée**, qui ne dépend pas d'Internet.

## Périmètre

- ESP32 JK classique : utiliser un appui BOOT de **6 à 9 secondes** sur le lecteur allumé. L'ESP32 redémarre en mode BLE **sans démarrer les connexions Bluetooth aux BMS**. Les appuis BOOT existants (association 4–6 s, Wi-Fi AP >=10 s) sont conservés.
- ESP32 VPN classique et S3 : appui BOOT **3,5 à 5,9 secondes** = maintenance USB historique ; **6 secondes ou davantage** = récupération BLE après relâchement et redémarrage. Une fenêtre de 9 secondes après démarrage accepte aussi les modes.
- ESP32-S3 Voltronic et Deye : après un redémarrage, appuyer sur BOOT pendant **3–5,9 secondes** pour l'USB ou **6 secondes et plus** pour le BLE, en relâchant pendant la fenêtre initiale de 9 secondes. Ces versions n'ont pas encore de déclencheur BLE depuis le bouton en régime normal : il faut les redémarrer.

**Attention** : GPIO0/BOOT ne doit pas être maintenu pendant l'application de l'alimentation, sinon le microcontrôleur peut entrer dans son chargeur ROM. Appuyer APRÈS le démarrage.

## Protocole BLE

- Service GATT : `b5e70001-3a62-4e58-9d12-4e4f44450001`.
- Défi (lecture, 16 octets aléatoires) : `b5e70002-3a62-4e58-9d12-4e4f44450001`.
- Écriture chiffrée : `b5e70003-3a62-4e58-9d12-4e4f44450001`.
- Vérification en lecture : `b5e70004-3a62-4e58-9d12-4e4f44450001`. `OK` = candidat enregistré en NVS ; `WAIT` sinon.
- Clé AES-256 : `HMAC-SHA256(secret_ESP, "smart-energy-wifi-ble-v1" || défi)`. ESP32 VPN : utiliser **le token de passerelle**, pas la clé de rattachement `tskey-auth-`.
- Paquet : `IV aléatoire 12 octets || tag GCM 16 octets || ciphertext(JSON {ssid,password})`. AAD GCM = défi.
- Le code privé n'est jamais transmis dans le paquet ; le SSID/mot de passe sont chiffrés. Un voisin ne peut pas reconfigurer un module sans le secret ET l'appui BOOT.
- Une seule configuration possible par session, expiration automatique après deux minutes, aucune commande batterie/onduleur ni modification du token Tailscale.

## Bascule et rollback

1. L'ESP32 enregistre la nouvelle configuration Wi-Fi dans une **clé NVS séparée** ; l'ancien Wi-Fi n'est pas écrasé.
2. L'ESP32 redémarre, tente de rejoindre le nouveau réseau Wi-Fi 2,4 GHz.
3. En cas d'association réussie avec attribution IP, les identifiants sont promus et la configuration provisoire est supprimée.
4. Sans connexion après ~45 s sur JK ou ~60 s sur IDF, les données provisoires sont effacées et le module revient à son ancien Wi-Fi.
5. Les BMS, noms d'équipements, codes privés, clés VPN, associations Tailscale et paramètres onduleurs restent intacts.

## Firmware et application

- Le flasher Windows tout-en-un intègre les firmwares quand GitHub Actions termine **tous** les builds ESP32, S3 et JK avec succès. Ne pas présenter un ancien EXE comme étant BLE-compatible.
- L'APK `0.3.62-dev` fournit `Configuration et associations → Récupération Wi-Fi Bluetooth` et conserve les dashboards JK/Voltronic/Sunsynk.
- Tests automatiques compilateur + tests de politiques en CI. **Aucun essai matériel Wi-Fi/BLE, mémoire du VPN classique ou retour en connexion Tailscale n'est encore validé.** Tester sur des ESP de réserve avant mise à jour de modules en production.
