"""USB Serial/JTAG setup for a new Smart Energy Voltronic ESP32-S3.

NEVER log passwords or access codes. No network provisioning.
Works exclusively via physical serial cable and BOOT confirmation.
"""
from __future__ import annotations
import json
import time
from typing import Callable
import serial


def voltronic_config_line(values: dict[str, str]) -> bytes:
    expected = {"wifi_ssid", "wifi_password", "read_code"}
    if not expected.issubset(values):
        raise ValueError("Les paramètres Wi-Fi / code Voltronic sont incomplets.")
    ssid = values["wifi_ssid"]
    password = values["wifi_password"]
    code = values["read_code"]
    if not (1 <= len(ssid.encode("utf-8")) <= 32 and
            8 <= len(password.encode("utf-8")) <= 64 and
            8 <= len(code) <= 32):
        raise ValueError("SSID 1–32 octets, Wi-Fi 8–64 octets, code 8–32 caractères.")
    if any(ord(x)<33 or ord(x)>126 for x in code):
        raise ValueError("Utilise un code ASCII imprimable sans espace.")
    payload = json.dumps({key: values[key] for key in sorted(expected)},
                         ensure_ascii=True, separators=(",",":"))
    message = ("SEVOLT_CONFIG " + payload + "\n").encode("ascii")
    if len(message) >= 299 or b"\x00" in message:
        raise ValueError("Configuration USB trop longue.")
    return message


def provision_voltronic(port: str, values: dict[str, str],
                        log: Callable[[str], None], timeout: int = 50) -> None:
    data = voltronic_config_line(values)
    deadline = time.monotonic() + timeout
    connection = serial.Serial()
    connection.port = port
    connection.baudrate = 115200
    connection.timeout = .5
    connection.write_timeout = 3
    connection.dtr = False
    connection.rts = False
    while time.monotonic() < deadline:
        try:
            connection.open()
            connection.dtr = False
            connection.rts = False
            break
        except (serial.SerialException,OSError):
            time.sleep(.7)
    else:
        raise RuntimeError("Port USB S3 introuvable. Actualise la liste COM après flash.")
    try:
        log("Attente du lecteur S3 SEVOLT_READY_V1 (sans envoyer de secret).")
        last_ping=0.0
        while time.monotonic() < deadline:
            now=time.monotonic()
            if now-last_ping>=1.5:
                connection.write(b"SEVOLT_PING\n")
                last_ping=now
            line=connection.readline().strip()
            if line == b"SEVOLT_READY_V1":
                break
        else:
            raise RuntimeError("Aucune réponse du lecteur Voltronic S3. "
                               "Pour reconfigurer : redémarre puis maintiens BOOT "
                               "3 secondes dans les 9 premières secondes.")
        log("Lecteur prêt : transfert du Wi-Fi et du code uniquement par USB.")
        connection.write(data)
        connection.flush()
        while time.monotonic() < deadline:
            line=connection.readline().strip()
            if line == b"SEVOLT_OK_REBOOT":
                log("Configuration enregistrée dans NVS ; redémarrage du S3.")
                return
            if line == b"SEVOLT_ERR_INVALID":
                raise RuntimeError("Le firmware a refusé les champs.")
        raise RuntimeError("Pas de confirmation du S3 après configuration USB.")
    finally:
        connection.close()
