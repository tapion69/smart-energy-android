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


def check_precompiled_image(image: Path, mode: str) -> int:
    if not image.is_file() or image.suffix.lower() != ".bin":
        raise ValueError("Choisis un firmware VPN précompilé .bin valide.")
    size = image.stat().st_size
    if size < 16384 or size > 4 * 1024 * 1024:
        raise ValueError("Taille du firmware VPN inattendue.")
    with image.open("rb") as stream:
        if mode == "factory":
            stream.seek(0x1000)
            boot_magic = stream.read(1)
            stream.seek(0x10000)
            app_magic = stream.read(1)
            if boot_magic != b"\xe9" or app_magic != b"\xe9":
                raise ValueError("Image VPN FACTORY attendue (bootloader+app).")
            return 0
        if mode == "update":
            if stream.read(1) != b"\xe9":
                raise ValueError("Image VPN APP-ONLY attendue.")
            return 0x10000
    raise ValueError("Mode VPN inconnu.")


def flash_precompiled(port: str, image: Path, mode: str,
                      log: Callable[[str], None]) -> None:
    offset = check_precompiled_image(image, mode)
    log(f"Flash ESP32 VPN classique sur {port}, offset 0x{offset:X}, "
        f"mode {mode}. Aucune compilation locale.")
    from esptool import main as esptool_main
    esptool_main([
        "--chip", "esp32", "--port", port, "--baud", "460800",
        "write_flash", "--flash_mode", "dio", "--flash_freq", "40m",
        "--flash_size", "4MB", hex(offset), str(image)
    ])
    log("Firmware précompilé transféré. Configuration physique USB en cours.")


def provision_over_serial(port: str, values: dict[str, str],
                          log: Callable[[str], None],
                          timeout_seconds: int = 50) -> None:
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
