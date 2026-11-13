"""One-time ESP32-S3 Voltronic dual-OTA migration, preserving NVS bytes.

Only separate flashes at 0x0, 0x8000 and 0x10000. Never merge 0x0-0x10000
as a single image, never erase_flash. All images must come from the same
validated GitHub CI build and match the expected OTA partition layout.
"""
from pathlib import Path
from typing import Callable
import struct

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

def flash_voltronic_ota_migration(port:str,directory:Path,
                                   log:Callable[[str],None])->None:
    boot,table,app=validate_migration_images(directory)
    if not port.startswith("COM") or not port[3:].isdigit():
        raise ValueError("Port COM invalide")
    log("Migration OTA : flash séparé aux offsets 0x0, 0x8000 et 0x10000 ; NVS ignorée.")
    from esptool import main as esptool_main
    esptool_main([
        "--chip","esp32s3","--port",port,"--baud","460800",
        "write_flash","--flash_mode","dio","--flash_freq","40m",
        "--flash_size","16MB",
        "0x0",str(boot),
        "0x8000",str(table),
        "0x10000",str(app)
    ])
    log("Bootloader, table dual-OTA et application écrits sans effacement de NVS.")
