#!/usr/bin/env python3
"""Create ESP32 classic 4 MB *complete* factory-image at address 0x0000.

This is for a chosen full wipe/first-install ONLY. It includes bootloader,
partition table, Arduino OTA initialization, and the tested application.
No personal Wi-Fi passwords, JK MACs, access codes, or VPN credentials are
embedded or read. Source is PlatformIO's currently compiled esp32dev output.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PIO = ROOT / ".pio/build/esp32dev"
PLATFORMIO = Path.home() / ".platformio/packages"
OTA_BOOT = PLATFORMIO / "framework-arduinoespressif32/tools/partitions/boot_app0.bin"
SECTIONS = [
    (0x1000, PIO / "bootloader.bin"),
    (0x8000, PIO / "partitions.bin"),
    (0xE000, OTA_BOOT),
    (0x10000, PIO / "firmware.bin"),
]
OUTPUT = PIO / "smart_jk_esp32_v0.3.3_FULL_ERASE_FIRST_0x0000.bin"


def assemble():
    for offset, path in SECTIONS:
        if not path.is_file():
            raise FileNotFoundError(f"Missing ESP32 build segment at 0x{offset:04X}: {path}")
    spans = [(offset, offset + path.stat().st_size, path) for offset, path in SECTIONS]
    for i, (start, end, path) in enumerate(spans):
        if i + 1 < len(spans) and end > spans[i+1][0]:
            raise RuntimeError(f"Overlapping factory segments: {path} ends at 0x{end:X}")
    if spans[-1][1] >= 0x400000:
        raise RuntimeError("Image unexpectedly exceeds 4 MB classic ESP32 flash")
    out = bytearray(b"\xFF" * spans[-1][1])
    for start, end, path in spans:
        data = path.read_bytes()
        if len(data) == 0:
            raise RuntimeError(f"Empty flash segment {path}")
        if start in (0x1000, 0x10000) and data[0] != 0xE9:
            raise RuntimeError(f"Invalid Espressif image at 0x{start:X}: {path}")
        out[start:end] = data
        print(f"0x{start:06X}-0x{end:06X} {path.name} ({len(data)} bytes)")
    OUTPUT.write_bytes(out)
    assert OUTPUT.read_bytes()[0x1000:0x1004] == (PIO/"bootloader.bin").read_bytes()[:4]
    assert OUTPUT.read_bytes()[0x10000:0x10004] == (PIO/"firmware.bin").read_bytes()[:4]
    print(f"Full ESP32 image: {OUTPUT.name}, {len(out)} bytes; flash at 0x0000 only")


if __name__ == "__main__":
    try:
        assemble()
    except (OSError, RuntimeError, AssertionError) as exc:
        sys.exit(f"ERROR: {exc}")
