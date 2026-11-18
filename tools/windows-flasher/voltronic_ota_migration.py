"""One-time ESP32-S3 Voltronic dual-OTA migration, preserving NVS bytes.

Only separate flashes at 0x0, 0x8000 and 0x10000. Never merge 0x0-0x10000
as a single image, never erase_flash. All images must come from the same
validated GitHub CI build and match the expected OTA partition layout.
"""
from pathlib import Path
from typing import Callable
import struct
import tempfile
import hashlib

EXPECTED = {
    "nvs": (0x01,0x02,0x9000,0x6000),
    "phy_init": (0x01,0x01,0xf000,0x1000),
    "factory": (0x00,0x00,0x10000,0x200000),
    "ota_0": (0x00,0x10,0x210000,0x200000),
    "ota_1": (0x00,0x11,0x410000,0x200000),
    "otadata": (0x01,0x00,0x610000,0x2000),
}
BOOT="voltronic-s3-ota-bootloader.bin"
PARTITION="voltronic-s3-ota-partition-table.bin"
APP="voltronic-s3-ota-app.bin"

def parse_partition_table(raw:bytes) -> dict[str,tuple[int,int,int,int]]:
    result={}
    for at in range(0,min(len(raw),0xc00),32):
        row=raw[at:at+32]
        if len(row)!=32:break
        magic=int.from_bytes(row[:2],"little")
        if magic in (0xffff,0xebeb):break  # ESP-IDF EOF / optional MD5 marker
        if magic!=0x50aa:
            raise ValueError("Table OTA ESP-IDF invalide")
        ty,sub,offset,size=struct.unpack_from("<BBII",row,2)
        name=row[12:28].split(b"\0",1)[0].decode("ascii")
        if name in result:raise ValueError("Partition dupliquée")
        result[name]=(ty,sub,offset,size)
    return result

def validate_migration_images(directory:Path) -> tuple[Path,Path,Path]:
    boot,table,app=(directory/name for name in (BOOT,PARTITION,APP))
    for path in (boot,table,app):
        if not path.is_file():raise ValueError("Images OTA incomplètes dans ce flasheur")
    if not (16384<=boot.stat().st_size<=0x8000):
        raise ValueError("Bootloader S3 : taille inattendue")
    if not (1024<=table.stat().st_size<=4096):
        raise ValueError("Table de partitions : taille inattendue")
    if not (16384<=app.stat().st_size<=0x200000):
        raise ValueError("Application OTA : taille inattendue")
    for image in (boot,app):
        header=image.read_bytes()[:14]
        if len(header)!=14 or header[0]!=0xe9 or int.from_bytes(header[12:14],"little")!=9:
            raise ValueError("Image non compatible avec ESP32-S3")
    partitions=parse_partition_table(table.read_bytes())
    if partitions!=EXPECTED:
        raise ValueError("Table OTA non autorisée : emplacement NVS non garanti")
    # Explicit invariant: writes do not overlap protected NVS [0x9000..0xF000).
    if boot.stat().st_size>0x8000 or table.stat().st_size>0x1000:
        raise ValueError("Les images ne doivent pas écraser NVS")
    return boot,table,app

def check_installed_voltronic_layout(raw:bytes) -> None:
    """Require installed classic SINGLE_APP_LARGE, exact NVS and Voltronic NVS.
    Unknown, already-OTA or incompatible partition layouts must not be altered.
    """
    parts=parse_partition_table(raw)
    nvs=parts.get("nvs")
    factory=parts.get("factory")
    if nvs!=(0x01,0x02,0x9000,0x6000):
        raise ValueError("NVS non reconnue à 0x9000/0x6000 : migration USB interdite")
    if not factory or factory[0:3]!=(0x00,0x00,0x10000) or factory[3]<0x200000:
        raise ValueError("Ancien firmware non compatible avec notre migration Voltronic")
    if "ota_0" in parts or "ota_1" in parts:
        raise ValueError("Une table OTA est déjà présente : ne pas réécrire les partitions")
    for name,(_ty,_sub,start,size) in parts.items():
        if name!="nvs" and start<0xf000 and start+size>0x9000:
            raise ValueError("Une autre partition recouvre la NVS : migration interdite")


def flash_voltronic_ota_migration(
    port:str,directory:Path,backup:Path,log:Callable[[str],None]
)->None:
    """One controlled USB migration; never write without full private NVS backup.

    A false negative is safer than silently erasing Wi-Fi, Tailscale identity,
    inverter setup code or another device's flash.
    """
    boot,table,app=validate_migration_images(directory)
    if not port.startswith("COM") or not port[3:].isdigit():
        raise ValueError("Port COM invalide")
    if not isinstance(backup,Path) or backup.suffix.lower()!=".bin":
        raise ValueError("Choisir un fichier de sauvegarde NVS local .bin")
    if backup.exists():
        raise ValueError("Ce fichier de sauvegarde existe déjà : nouveau nom requis")
    backup.parent.mkdir(parents=True,exist_ok=True)
    from esptool import main as esptool_main
    base=["--chip","esp32s3","--port",port,"--baud","115200"]
    with tempfile.TemporaryDirectory(prefix="se-voltronic-ota-") as temp:
        tempdir=Path(temp)
        original_table=tempdir/"original-partitions.bin"
        original_nvs=tempdir/"original-nvs.bin"
        check_nvs=tempdir/"nvs-after.bin"
        final_table=tempdir/"partitions-after.bin"
        log("Lecture USB des partitions Voltronic avant toute écriture…")
        esptool_main(base+["--after","no_reset","read_flash",
                     "0x8000","0x1000",str(original_table)])
        if not original_table.exists() or original_table.stat().st_size!=0x1000:
            raise ValueError("Lecture de la table de partitions incomplète")
        check_installed_voltronic_layout(original_table.read_bytes())
        log("Partitionnement single-app confirmé. Sauvegarde NVS privée…")
        esptool_main(base+["--before","no_reset","--after","no_reset",
                     "read_flash","0x9000","0x6000",str(original_nvs)])
        if not original_nvs.is_file() or original_nvs.stat().st_size!=0x6000:
            raise ValueError("Lecture NVS incomplète : aucune écriture autorisée")
        protected=original_nvs.read_bytes()
        # Verify identity from actual NVS keys: no accidental Deye or VPN flash.
        if b"se_volt" not in protected or b"usb_config" not in protected:
            raise ValueError("Identité NVS Voltronic non reconnue : aucun flash")
        with backup.open("xb") as output:
            output.write(protected)
        if hashlib.sha256(backup.read_bytes()).digest()!=hashlib.sha256(protected).digest():
            backup.unlink(missing_ok=True)
            raise ValueError("La sauvegarde NVS n'a pas pu être vérifiée")
        log("Sauvegarde privée NVS vérifiée sur le PC (ne jamais la partager).")
        # Read-based guard is finished; esptool writes boot+table+APP separately.
        # ESP-IDF's App-only USB mode can never migrate the partition table.
        esptool_main(["--chip","esp32s3","--port",port,"--baud","460800",
            "write_flash","--flash_mode","dio","--flash_freq","40m",
            "--flash_size","16MB",
            "0x0",str(boot),"0x8000",str(table),"0x10000",str(app)])
        log("Images écrites. Relecture des partitions et de la NVS…")
        esptool_main(base+["--after","no_reset","read_flash",
                     "0x8000","0x1000",str(final_table)])
        if not final_table.is_file() or parse_partition_table(final_table.read_bytes())!=EXPECTED:
            raise ValueError("Table OTA non confirmée : conserver la sauvegarde NVS")
        esptool_main(base+["--before","no_reset","read_flash",
                     "0x9000","0x6000",str(check_nvs)])
        if not check_nvs.is_file() or check_nvs.read_bytes()!=protected:
            raise ValueError("NVS modifiée lors du flash : restauration physique requise")
    log("Migration OTA Voltronic terminée et vérifiée. Redémarrer le S3 et relire /api/v1/ota/status.")
