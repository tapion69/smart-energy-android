"""Refuse unsafe JK APP-ONLY flashes and back up the NVS first.

This module never erases flash or writes partition tables. It reads the *actual*
ESP32 classic partition table, checks app0 and the NVS region, then backs up
only the exact NVS partition to a local private file. The backup can contain
Wi-Fi passwords and device codes: never upload or publish it.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import hashlib
import struct
import tempfile

FLASH_BYTES = 4 * 1024 * 1024
PARTITION_TABLE_OFFSET = 0x8000
PARTITION_TABLE_BYTES = 0x1000
APP0_OFFSET = 0x10000


@dataclass(frozen=True)
class JkPartition:
    kind: int
    subtype: int
    start: int
    length: int
    label: str

    @property
    def end(self) -> int:
        return self.start + self.length


@dataclass(frozen=True)
class JkPreflight:
    nvs: JkPartition
    app0: JkPartition
    has_second_ota_slot: bool


def parse_jk_partitions(data: bytes) -> tuple[JkPartition, ...]:
    if len(data) != PARTITION_TABLE_BYTES:
        raise ValueError("Table de partitions JK incomplète ; aucune écriture.")
    found: list[JkPartition] = []
    for offset in range(0, 0xC00, 32):
        record = data[offset:offset + 32]
        if record == b"\xff" * 32:
            break
        magic, kind, subtype, start, length, label, _flags = struct.unpack(
            "<HBBII16sI", record
        )
        if magic == 0xEBEB:
            # ESP-IDF optional MD5 trailer; entries before it are sufficient.
            break
        if magic != 0x50AA:
            raise ValueError("Table partitions JK non reconnue : flash USB bloqué.")
        if length <= 0 or start < 0x9000 or start + length > FLASH_BYTES:
            raise ValueError("Une partition du JK dépasse la flash 4 Mo.")
        name = label.split(b"\x00", 1)[0].decode("ascii", errors="strict")
        found.append(JkPartition(kind, subtype, start, length, name))
    if not found:
        raise ValueError("Table partitions JK vide : aucun flash possible.")
    for i, entry in enumerate(found):
        if any(entry.start < other.end and other.start < entry.end
               for other in found[:i]):
            raise ValueError("Partitions ESP32 chevauchantes : flash bloqué.")
    return tuple(found)


def check_jk_app_only(data: bytes, image_bytes: int) -> JkPreflight:
    entries = parse_jk_partitions(data)
    nvs_list = [p for p in entries if p.kind == 0x01 and p.subtype == 0x02]
    app_list = [p for p in entries
                if p.kind == 0x00 and p.start == APP0_OFFSET and
                p.subtype in (0x00, 0x10)]
    if len(nvs_list) != 1 or len(app_list) != 1:
        raise ValueError("NVS ou emplacement APP 0x10000 introuvable : migration experte nécessaire.")
    nvs, app = nvs_list[0], app_list[0]
    if not (nvs.start >= 0x9000 and nvs.end <= APP0_OFFSET and
            nvs.length in range(0x1000, 0x8001, 0x1000)):
        raise ValueError("NVS JK hors de sa région protégée : flash bloqué.")
    if not (16384 <= image_bytes <= app.length):
        raise ValueError("Le nouveau firmware dépasse la partition APP JK.")
    second_ota = any(p.kind == 0x00 and p.subtype == 0x11 for p in entries)
    return JkPreflight(nvs, app, second_ota)


def backup_jk_nvs(port: str, destination: Path, image_size: int,
                  esptool_main) -> JkPreflight:
    """Fail closed: both reads, table validation and persisted backup first."""
    if not destination or destination.suffix.lower() != ".bin":
        raise ValueError("Choisis un fichier de sauvegarde NVS .bin local.")
    if destination.exists():
        raise ValueError("La sauvegarde NVS existe déjà : choisis un nouveau nom.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="smartenergy-jk-preflight-") as temp:
        table = Path(temp) / "partition_table.bin"
        args = ["--chip", "esp32", "--port", port, "--baud", "460800"]
        esptool_main(args + ["read_flash", hex(PARTITION_TABLE_OFFSET),
                             hex(PARTITION_TABLE_BYTES), str(table)])
        if not table.is_file():
            raise ValueError("Impossible de relire les partitions du JK.")
        checked = check_jk_app_only(table.read_bytes(), image_size)
        private = Path(temp) / "nvs-private.bin"
        esptool_main(args + ["read_flash", hex(checked.nvs.start),
                             hex(checked.nvs.length), str(private)])
        raw = private.read_bytes()
        if len(raw) != checked.nvs.length:
            raise ValueError("Sauvegarde NVS incomplète : mise à jour refusée.")
        # The backup is local and deliberately not logged, printed or
        # transmitted; it may include private pairing and Wi-Fi credentials.
        with destination.open("xb") as handle:
            handle.write(raw)
        if hashlib.sha256(destination.read_bytes()).digest() != hashlib.sha256(raw).digest():
            destination.unlink(missing_ok=True)
            raise ValueError("Vérification de la sauvegarde NVS échouée.")
        return checked
