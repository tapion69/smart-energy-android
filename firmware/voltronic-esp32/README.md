# Firmware Voltronic ESP32

**État : non implémenté.** Module indépendant du firmware JK, utilisant la liaison RS232 avec convertisseur de niveau approprié.

Objectifs : configuration depuis l'APK, lecture du protocole Voltronic, un ou plusieurs onduleurs suivant les interfaces série réellement disponibles, télémétrie datée, erreurs, OTA indépendante.

Le câblage RJ45/RS232 du port onduleur devra être confirmé sur le modèle testé avant tout branchement ; la prise RJ45 n'est pas un port Ethernet.

Ce firmware n'exige pas Home Assistant, MQTT ou Node-RED.
