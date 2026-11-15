# ESP32 S3 + Smart Voltronic - TEST LIVE isole (15 novembre 2026)

Branches de test uniquement :
- Firmware et APK : `experiment/esp32-s3-full-live-test`
- Add-on HA : `tapion69/smart_voltronic`, branche `dev/esp32-s3-full-live-test`

## Artefacts GitHub Actions

Les workflows `build-voltronic-s3.yml` et
`build-esp32-live-test-apk.yml` publient, apres succes :

- `SmartEnergy-VOLTRONIC-ESP32S3-N16R8-FULL-LIVE-TEST` :
  image FACTORY au decalage flash 0x0 et image APP a 0x10000.
  **UNIQUEMENT pour ESP32-S3 N16R8 16 MB** (pas ESP32 JK classique).
- `SmartEnergy-Android-ESP-HA-TEST` :
  `SmartEnergy-ESP-HA-TEST-signed.apk` si la cle de signature GitHub
  est configuree, plus une APK de debug separee (identifiant suffixe .debug).

L'APK stable de test utilise `fr.smartenergy.android.espbridge`.
Ce n'est pas une mise a jour de `fr.smartenergy.android`. Elle apparait
sous le titre `Smart Energy ESP TEST`, et ses donnees sont isolees.
Le code Android reprend le dashboard 0.3.46-dev.

**Le telechargement automatique des mises a jour officielles Android est
desactive pour cette application TEST** afin de ne pas lancer la mise
a jour de l'application de production. L'ancienne fonction OTA avec
image embarquee 0.3.4 est egalement desactivee pour eviter un retour
vers l'ancien firmware. Utiliser seulement le BIN experimental fourni
par le workflow de cette branche.

## Test sur un seul onduleur utilise pour le MPPT

1. Sauvegarder la configuration actuelle, et utiliser un seul ESP32 S3.
   MAX3232 3.3 V indispensable entre RS232 Voltronic et GPIO 16/17 ;
   port UART1 2400 8N1.
2. Flasher le S3 d'essai, puis verifier les lectures en APK de test,
   sans aucune commande SET. En cas de flash usine, reconfigurer Wi-Fi/code.
3. Dans le module HA de test, un seul onduleur en mode esp32 sur l'IP S3,
   port 18081 ; `inv1_esp_write_enabled=false`.
4. Comparer en meme temps l'APK TEST et les entites HA ; le S3 doit
   executer **un seul polling UART**, et HTTP n'expose que son cache.
5. Observer puissance PV1/PV2, erreurs CRC, QPIGS/QPIGS2, frequence
   des remontees, progression des compteurs et transition jour/nuit.
6. Les commandes sont hors test telemetrie. Une ecriture necessite
   l'option explicite de l'add-on, une valeur ancienne fraiche et le
   controle apres lecture native ; aucune repetition aveugle si
   resultat incertain.

## Attention au MQTT !

L'add-on de test et l'add-on de production conservent **les memes**
identifiants d'entites et topics MQTT (exigence de parite). Installer
deux add-ons separes ne suffit PAS pour isoler leurs publications.
Pour tester sur le meme Home Assistant, arreter la publication MQTT
du producteur de production pendant le test, ou utiliser un broker HA
isole. Ne pas lancer deux sources MQTT sur les memes identifiants.

Les regressions et l'absence de fallback `MODBUS_MPPT2` ou de toutes
les commandes `LOAD2` cote S3 restent a valider ou implementer avant
tout usage en production. Le premier test materiel doit rester en
lecture seule pour observer les mesures reelles.
