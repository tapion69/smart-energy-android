# Manifestes de mises à jour

Les fichiers JSON de ce dossier sont des **emplacements réservés**. Ils portent `available:false` et ne désignent **aucun** firmware ou APK téléchargeable.

- `android.json` : nouvelle APK Android signée, diffusée via GitHub Releases.
- `jk-esp32.json` : firmware JK signé et compatible avec une cible matérielle explicite.
- `voltronic-esp32.json` : firmware Voltronic distinct.

Avant d'activer une release : déposer l'artefact, son hash SHA-256, la signature, le numéro de version, les cibles compatibles et le changelog. Tester l'installation réelle. Les URL de dépôt ne sont pas des URL de firmware.

Le firmware ne doit **jamais** interpréter `available:false` comme une mise à jour. Ne jamais déclencher une OTA à partir d'un manifeste non signé/non vérifié.
