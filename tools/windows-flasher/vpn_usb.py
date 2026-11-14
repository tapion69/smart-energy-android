"""Precompiled Smart Energy VPN factory/app-only flash and physical USB setup.

No git, ESP-IDF, sdkconfig, Internet or user secrets in firmware / exe.
The generic firmware must implement the SEVPN_READY_V1 USB handshake.
"""
from __future__ import annotations

import json
import time
import ipaddress
from pathlib import Path
from typing import Callable

import serial


def format_usb_configuration(values: dict[str, str]) -> bytes:
    # Strictly reject accidental log/line injection and oversized requests.
    expected = {
        "wifi_ssid", "wifi_password", "tailscale_key", "gateway_token",
        "jk1_ip", "jk1_code", "jk2_ip", "jk2_code", "jk3_ip", "jk3_code",
        "volt1_ip", "volt1_code", "volt2_ip", "volt2_code", "volt3_ip", "volt3_code",
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


def format_voltronic_patch(slot: int, ip: str, code: str) -> bytes:
    """USB-only change of one target; never sends VPN/JK credentials."""
    if slot not in (1, 2, 3):
        raise ValueError("Choisis INV1, INV2 ou INV3.")
    try:
        address=ipaddress.IPv4Address(ip.strip())
        private=any(address in ipaddress.ip_network(network) for network in
                    ("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16"))
    except ValueError:
        private=False
    if not private:raise ValueError("Adresse IP Voltronic privée invalide.")
    if not 8<=len(code)<=32 or any(ch in code for ch in "\r\n\x00"):
        raise ValueError("Code Voltronic invalide (8–32 caractères).")
    data=json.dumps({"slot":slot,"ip":ip.strip(),"code":code},
                    ensure_ascii=True,separators=(",",":"))
    return ("SEVPN_ADD_VOLTRONIC "+data+"\n").encode("ascii")


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


def provision_over_serial(port: str, values: dict[str, str] | None,
                          log: Callable[[str], None],
                          timeout_seconds: int = 50,
                          on_ready: Callable[[], None] | None = None,
                          payload_override: bytes | None = None) -> None:
    """Reopen the SAME COM port after a reset during SEVPN_READY_V1 wait.

    The only automatic retry is the unprivileged SEVPN_PING. The secret
    configuration payload is sent ONCE, only after READY, and is NEVER retried
    automatically after an ambiguous write/disconnect.
    """
    payload = (payload_override if payload_override is not None
               else format_usb_configuration(values or {}))
    deadline = time.monotonic() + timeout_seconds
    ser = serial.Serial()
    ser.port = port
    ser.baudrate = 115200
    ser.timeout = 0.5
    ser.write_timeout = 1.2
    ser.dtr = False
    ser.rts = False
    log("Attente du signal USB SEVPN_READY_V1. Aucun secret transmis avant la réponse.")
    last_ping = 0.0
    had_usb_error = False
    connected_once = False
    ready = False
    try:
        while time.monotonic() < deadline:
            if not ser.is_open:
                try:
                    ser.open()
                    ser.dtr = False
                    ser.rts = False
                    last_ping = 0.0
                    if had_usb_error:
                        log(f"Port {port} reconnecté : attente du mode BOOT USB.")
                        had_usb_error = False
                    connected_once = True
                except (serial.SerialException, OSError):
                    time.sleep(0.35)
                    continue
            try:
                now = time.monotonic()
                if now - last_ping >= 1.5 or last_ping == 0.0:
                    ser.write(b"SEVPN_PING\n")
                    last_ping = now
                line = ser.readline().strip()
                if b"SEVPN_READY_V1" in line:
                    ready = True
                    log("Firmware VPN prêt : transmission locale de la configuration.")
                    break
            except (serial.SerialException, serial.SerialTimeoutException, OSError):
                # RESET can temporarily remove the USB CDC port on ESP32-S3.
                # An unacknowledged PING has no side effects: safe to retry.
                if not had_usb_error:
                    log(f"Redémarrage USB détecté sur {port} : réouverture automatique. "
                        "Ne lance pas un nouveau flash.")
                had_usb_error = True
                try:
                    ser.close()
                except (serial.SerialException, OSError):
                    pass
                time.sleep(0.35)
        if not ready:
            if connected_once:
                raise RuntimeError(
                    "Aucun SEVPN_READY_V1 reçu : redémarre l'ESP32 avec RESET, "
                    "puis maintiens BOOT 3 secondes pendant les 9 secondes de "
                    "démarrage. Si le port COM a changé, sélectionne le nouveau "
                    "port et recommence l'ajout ciblé (sans flasher)."
                )
            raise RuntimeError(
                f"Le port {port} ne se rouvre pas après RESET. Sélectionne le "
                "nouveau port COM dans le flasher, sans effacer la configuration."
            )
        if on_ready is not None:
            on_ready()
        # This transmission MAY update NVS. Do not retry if it fails:
        # data could already have been received, despite a lost USB ACK.
        try:
            ser.write(payload)
            ser.flush()
        except (serial.SerialException, serial.SerialTimeoutException, OSError):
            raise RuntimeError(
                "Connexion USB interrompue pendant l'envoi de configuration. "
                "Enregistrement NON CONFIRMÉ : ne réessaie pas automatiquement. "
                "Vérifie d'abord si l'ESP32 a redémarré et si l'onduleur est "
                "accessible par le VPN."
            ) from None
        confirmation_deadline = time.monotonic() + 10
        while time.monotonic() < confirmation_deadline:
            try:
                line = ser.readline().strip()
            except (serial.SerialException, serial.SerialTimeoutException, OSError):
                raise RuntimeError(
                    "Port USB interrompu en attente de confirmation. "
                    "Enregistrement NON CONFIRMÉ : vérifie l'ESP32 avant tout "
                    "nouvel essai."
                ) from None
            if b"SEVPN_OK_REBOOT" in line:
                log("Configuration NVS confirmée par le firmware. "
                    "Redémarrage automatique.")
                return
            if b"SEVPN_ERR_INVALID" in line:
                raise RuntimeError(
                    "Le firmware a refusé les champs : "
                    "aucune nouvelle configuration confirmée."
                )
        raise RuntimeError(
            "Aucune confirmation NVS reçue. État NON CONFIRMÉ : "
            "vérifie l'appareil avant de retenter l'opération."
        )
    finally:
        try:
            ser.close()
        except (serial.SerialException, OSError):
            pass


def set_remote_writes_over_serial(port: str, enabled: bool,
                                  log: Callable[[str], None],
                                  on_ready: Callable[[], None] | None = None) -> None:
    """Physical BOOT+USB opt-in, changes only NVS remote_writes_enabled.

    The VPN firmware verifies every other saved credential remains unchanged.
    This command never carries a credential; no network endpoint can toggle it.
    """
    command=b"SEVPN_REMOTE_WRITES_ON\n" if enabled else b"SEVPN_REMOTE_WRITES_OFF\n"
    provision_over_serial(port,None,log,on_ready=on_ready,payload_override=command)


def add_voltronic_over_serial(port: str, slot: int, ip: str, code: str,
                              log: Callable[[str], None],
                              on_ready: Callable[[], None] | None = None) -> None:
    """Preserve NVS keys other than volt{slot}_ip/code, via physical BOOT."""
    payload=format_voltronic_patch(slot,ip,code)
    provision_over_serial(port,None,log,on_ready=on_ready,payload_override=payload)
