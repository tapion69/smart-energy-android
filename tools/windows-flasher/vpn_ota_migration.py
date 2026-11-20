"""One-time Smart Energy VPN ESP32-S3 dual-OTA bootstrap over physical USB.

This is NOT an app-only flash and is NOT a remotely callable operation.
The utility validates the actual installed layout, backs up the original
bootloader, partition table, app and complete private NVS before any write,
and refuses any unknown or already-OTA layout. It never erases flash and
never exports or prints user secrets. Only 0x0, 0x8000 and 0x10000 are written.
"""
from __future__ import annotations
from pathlib import Path
from typing import Callable
import hashlib
import re

from voltronic_ota_migration import parse_partition_table, EXPECTED

BOOT = "vpn-s3-ota-bootloader.bin"
TABLE = "vpn-s3-ota-partition-table.bin"
APP = "SmartEnergy-VPN-S3-OTA.bin"
OTA_DATA_ADDRESS = 0x610000
OTA_DATA_LENGTH = 0x2000
NVS_OFFSET = 0x9000
NVS_LENGTH = 0x6000
APP_OFFSET = 0x10000
EXPECTED_LEGACY = {
    "nvs": (0x01,0x02,0x9000,0x6000),
    "phy_init": (0x01,0x01,0xF000,0x1000),
    "factory": (0x00,0x00,0x10000,0x300000),
}

def _image_is_s3(raw: bytes) -> bool:
    return len(raw)>=14 and raw[0]==0xE9 and int.from_bytes(raw[12:14],"little")==9

def _read_exact(esptool:Callable, args:list[str], where:int,
                length:int, destination:Path) -> bytes:
    esptool(args+["read_flash",hex(where),hex(length),str(destination)])
    if not destination.is_file() or destination.stat().st_size!=length:
        raise ValueError("Lecture physique USB incomplète : aucune écriture autorisée")
    return destination.read_bytes()

def validate_installed_vpn_table(raw:bytes) -> None:
    parts=parse_partition_table(raw)
    if parts.get("ota_0") or parts.get("ota_1") or parts.get("otadata"):
        raise ValueError(
            "Ce VPN possède déjà une table OTA. Le service OTA 404 provient "
            "du firmware installé : ne pas écraser les partitions en USB. "
            "Une mise à jour spécifique au slot actif est nécessaire.")
    if parts!=EXPECTED_LEGACY:
        raise ValueError(
            "Table VPN non reconnue : migration refusée sans diagnostic. "
            "La configuration Tailscale/NVS ne sera pas touchée.")
    for key,(_ty,_sub,start,length) in parts.items():
        if key!="nvs" and start<NVS_OFFSET+NVS_LENGTH and start+length>NVS_OFFSET:
            raise ValueError("Une partition recouvre la NVS : migration refusée")

def validate_images(directory:Path)->tuple[Path,Path,Path]:
    boot,table,app=(directory/file for file in (BOOT,TABLE,APP))
    for p in (boot,table,app):
        if not p.is_file(): raise ValueError("Pack migration VPN 0.3.14 incomplet")
    if not (16384<=boot.stat().st_size<=0x8000):
        raise ValueError("Bootloader VPN S3 : taille invalide")
    if not (1024<=table.stat().st_size<=0x1000):
        raise ValueError("Table VPN : taille invalide")
    if not (16384<=app.stat().st_size<=0x200000):
        raise ValueError("Firmware VPN : taille incompatible avec les deux slots OTA")
    if not _image_is_s3(boot.read_bytes()[:14]) or not _image_is_s3(app.read_bytes()[:14]):
        raise ValueError("Images non compatibles ESP32-S3 : aucun flash")
    if b"0.3.14-dev-vpn-wifi-recovery" not in app.read_bytes():
        raise ValueError("Ce n'est pas la version VPN 0.3.14 validée")
    if parse_partition_table(table.read_bytes())!=EXPECTED:
        raise ValueError("Partitions OTA VPN différentes du plan de migration validé")
    # Bootloader writes [0,0x8000), table writes [0x8000,0x9000),
    # application writes [0x10000,0x210000), never [0x9000,0xF000).
    return boot,table,app

def prepare_usb_migration(port:str,images_dir:Path,backup_dir:Path,
                          log:Callable[[str],None],
                          esptool:Callable|None=None)->None:
    """Read, back up, and migrate an EXACT legacy S3 single-app gateway.

    A refusal is intentional and never attempts a fallback factory flash.
    All original private bytes remain in the caller-selected backup folder.
    """
    if not isinstance(port,str) or re.fullmatch(r"COM[1-9][0-9]*",port,re.I) is None:
        raise ValueError("Port Windows COM invalide")
    boot,part,app=validate_images(images_dir)
    if backup_dir.exists():
        raise ValueError("Choisir un dossier de sauvegarde NOUVEAU : aucun écrasement")
    if esptool is None:
        from esptool import main as esptool_main
        do=esptool_main
    else:
        do=esptool
    base=["--chip","esp32s3","--port",port,"--baud","115200",
          "--before","no_reset","--after","no_reset"]
    # First query uses esptool's normal auto-BOOT sequence. Subsequent
    # transactions hold the ROM in download mode without resetting.
    first=["--chip","esp32s3","--port",port,"--baud","115200",
           "--after","no_reset"]
    # Keep the ESP in ROM download mode until writes and verification complete.
    backup_dir.mkdir(parents=True,exist_ok=False)
    log("VPN : lecture USB des partitions, sans modifier l'ESP")
    original_parts=_read_exact(do,first,0x8000,0x1000,backup_dir/"partitions-avant.bin")
    validate_installed_vpn_table(original_parts)
    log("Table SINGLE_APP_LARGE VPN reconnue. Sauvegarde des données privées.")
    nvs=_read_exact(do,base,NVS_OFFSET,NVS_LENGTH,backup_dir/"nvs-PRIVEE.bin")
    # Namespace & key identify OUR gateway without exposing their values.
    if b"se_vpn_cfg" not in nvs or b"json" not in nvs:
        raise ValueError(
            "Identité NVS VPN absente : aucune écriture. Ne partage pas la sauvegarde.")
    old_app=_read_exact(do,base,APP_OFFSET,0x300000,backup_dir/"app-avant.bin")
    if not _image_is_s3(old_app[:14]) or (
        b"Smart Energy VPN Gateway" not in old_app and
        b"smart-energy-vpn-gateway" not in old_app):
        raise ValueError("Application installée non reconnue comme VPN Smart Energy")
    _read_exact(do,base,0x0,0x8000,backup_dir/"bootloader-avant.bin")
    old_ota=_read_exact(do,base,OTA_DATA_ADDRESS,OTA_DATA_LENGTH,
                        backup_dir/"otadata-avant.bin")
    # An unknown previously-used OTA metadata region requires manual handling.
    if any(b!=0xFF for b in old_ota):
        raise ValueError(
            "Zone OTA déjà utilisée : migration automatique refusée pour éviter "
            "un redémarrage ambigu. Sauvegardes conservées.")
    for name,raw in (("partitions-avant.bin",original_parts),
                     ("nvs-PRIVEE.bin",nvs),("app-avant.bin",old_app)):
        if hashlib.sha256((backup_dir/name).read_bytes()).digest()!=hashlib.sha256(raw).digest():
            raise ValueError("Sauvegarde privée illisible : aucune écriture autorisée")
    log("Sauvegarde intégrale validée. Écriture USB séparée, aucun erase_flash.")
    do(["--chip","esp32s3","--port",port,"--baud","460800",
        "--before","no_reset","--after","no_reset",
        "write_flash","--flash_mode","dio","--flash_freq","40m","--flash_size","16MB",
        "0x0",str(boot),"0x8000",str(part),"0x10000",str(app)])
    # esptool verification reads back the specified offsets. This is not
    # an erase nor a write and must complete before declaring success.
    do(["--chip","esp32s3","--port",port,"--baud","115200",
        "--before","no_reset","--after","no_reset",
        "verify_flash","0x0",str(boot),"0x8000",str(part),"0x10000",str(app)])
    after_part=_read_exact(do,base,0x8000,0x1000,backup_dir/"partitions-apres.bin")
    if parse_partition_table(after_part)!=EXPECTED:
        raise ValueError("Partitions OTA non confirmées. Ne pas redémarrer avant diagnostic.")
    after_nvs=_read_exact(do,base,NVS_OFFSET,NVS_LENGTH,backup_dir/"nvs-apres-PRIVEE.bin")
    if after_nvs!=nvs:
        raise ValueError(
            "NVS différente après migration. Ne pas supprimer les sauvegardes privées.")
    log("VPN S3 : partitionnement double OTA et NVS vérifiés. "
        "Redémarre l'ESP, contrôle Tailscale et relis la version dans l'APK.")
