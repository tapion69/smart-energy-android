"""Precompiled Smart Energy VPN factory/app-only flash and physical USB setup.

No git, ESP-IDF, sdkconfig, Internet or user secrets in firmware / exe.
The generic firmware must implement the SEVPN_READY_V1 USB handshake.
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Callable

import serial


def format_usb_configuration(values: dict[str, str]) -> bytes:
    # Strictly reject accidental log/line injection and oversized requests.
    expected = {
        "wifi_ssid", "wifi_password", "tailscale_key", "gateway_token",
        "jk1_ip", "jk1_code", "jk2_ip", "jk2_code", "jk3_ip", "jk3_code",
    }
    if not expected.issubset(values):
        raise ValueError("Champs VPN manquants.")
    payload = json.dumps(
        {key: values[key] for key in sorted(expected)},
        ensure_ascii=True, separators=(",", ":")
    )
    data = ("SEVPN_CONFIG " + payload + "\n").encode("ascii")
    if len(data) > 1599 or b"\x00" in data:
        raise ValueError("La configuration VPN dépasse la taille USB autorisée.")
    return data


def check_precompiled_image(image: Path, mode: str, chip: str = "esp32") -> int:
    """Validate both flash OFFSET and chip identity before any USB operation.

    ESP32 classic bootloader at 0x1000; ESP32-S3 bootloader at 0x0.
    APP-ONLY at 0x10000 on both. Never call erase_flash.
    """
    if chip not in ("esp32", "esp32s3"):
        raise ValueError("Choisis ESP32 classique ou ESP32-S3 N16R8.")
    if not image.is_file() or image.suffix.lower() != ".bin":
        raise ValueError("Choisis un firmware VPN précompilé .bin valide.")
    size = image.stat().st_size
    if size < 16384 or size > (16 if chip == "esp32s3" else 4) * 1024 * 1024:
        raise ValueError("Taille du firmware VPN inattendue pour ce modèle.")
    # IDF image header: magic 0xE9, target chip ID at bytes 12 and 13 (LE).
    # ESP32=0x0000, ESP32-S3=0x0009. Require boot+app match for factory.
    expected_id = 9 if chip == "esp32s3" else 0
    boot_offset = 0 if chip == "esp32s3" else 0x1000
    offset = 0 if mode == "factory" else 0x10000
    if mode not in ("factory", "update"):
        raise ValueError("Mode VPN inconnu.")
    with image.open("rb") as stream:
        def read_header(where: int) -> None:
            stream.seek(where)
            data = stream.read(14)
            if len(data) != 14 or data[0] != 0xE9:
                raise ValueError("En-tête de firmware VPN absent.")
            found_id = int.from_bytes(data[12:14], "little")
            if found_id != expected_id:
                raise ValueError(
                    f"Ce firmware cible une autre puce (image id {found_id}, "
                    f"ESP attendu {chip}). Aucun flash effectué.")
        if mode == "factory":
            read_header(boot_offset)
            read_header(0x10000)
        else:
            read_header(0)
    return offset


def flash_precompiled(port: str, image: Path, mode: str,
                      log: Callable[[str], None], chip: str = "esp32") -> None:
    offset = check_precompiled_image(image, mode, chip=chip)
    size = "16MB" if chip == "esp32s3" else "4MB"
    log(f"Flash ESP32 VPN {chip} sur {port}, offset 0x{offset:X}, "
        f"mode {mode}. Aucune compilation locale.")
    from esptool import main as esptool_main
    esptool_main([
        "--chip", chip, "--port", port, "--baud", "460800",
        "write_flash", "--flash_mode", "dio", "--flash_freq", "40m",
        "--flash_size", size, hex(offset), str(image)
    ])
    log("Firmware précompilé transféré. Configuration physique USB en cours.")


def provision_over_serial(port: str, values: dict[str, str],
                          log: Callable[[str], None],
                          timeout_seconds: int = 50,
                          on_ready: Callable[[], None] | None = None) -> None:
    """Never echoes passwords, tokens or the JSON payload into logs."""
    payload = format_usb_configuration(values)
    deadline = time.monotonic() + timeout_seconds
    ser = serial.Serial()
    ser.port = port
    ser.baudrate = 115200
    ser.timeout = 0.5
    ser.write_timeout = 3
    ser.dtr = False
    ser.rts = False
    while time.monotonic() < deadline:
        try:
            ser.open()
            ser.dtr = False
            ser.rts = False
            break
        except (serial.SerialException, OSError):
            time.sleep(0.7)
    else:
        raise RuntimeError("Port USB indisponible après flash. "
                           "Reconnecte le câble et utilise Configurer sans flasher.")

    try:
        log("Attente du signal USB SEVPN_READY_V1. Aucun secret transmis avant la réponse.")
        last_ping = 0.0
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now - last_ping > 1.5:
                ser.write(b"SEVPN_PING\n")
                last_ping = now
            line = ser.readline().strip()
            if b"SEVPN_READY_V1" in line:
                log("Firmware VPN prêt : transmission locale de la configuration.")
                break
        else:
            raise RuntimeError(
                "Le firmware ne répond pas au protocole USB Smart Energy. "
                "Sur une passerelle déjà associée, redémarre puis maintiens "
                "BOOT pendant 3 secondes au démarrage pour autoriser la modification."
            )
        if on_ready is not None:
            on_ready()
        ser.write(payload)
        ser.flush()
        confirmation_deadline = time.monotonic() + 10
        while time.monotonic() < confirmation_deadline:
            line = ser.readline().strip()
            if b"SEVPN_OK_REBOOT" in line:
                log("Configuration NVS confirmée par le firmware. Redémarrage automatique.")
                return
            if b"SEVPN_ERR_INVALID" in line:
                raise RuntimeError("Le firmware a refusé les champs : "
                                   "aucune configuration n'a été enregistrée.")
        raise RuntimeError("Aucune confirmation NVS reçue. "
                           "Vérifie l'appareil avant de retenter le flash.")
    finally:
        ser.close()
