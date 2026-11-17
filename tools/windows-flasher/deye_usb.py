"""First-time Deye ESP32-S3 provision over USB only. Never prints secrets.
Images are compiled generically; personal IP/serial/Wi-Fi remain solely in NVS.
"""
from __future__ import annotations
import ipaddress
import json
import time
from typing import Callable
import serial

def _private_ipv4(addr: str) -> bool:
    try:
        ip=ipaddress.IPv4Address(addr)
        return ip in ipaddress.IPv4Network("10.0.0.0/8") or \
               ip in ipaddress.IPv4Network("172.16.0.0/12") or \
               ip in ipaddress.IPv4Network("192.168.0.0/16")
    except (ValueError,TypeError):
        return False

def deye_config_line(values: dict[str,str]) -> bytes:
    required={"wifi_ssid","wifi_password","read_code","display_name",
              "dongle_ip","dongle_port","modbus_id","dongle_serial","inverter_serial"}
    if not required.issubset(values):
        raise ValueError("Configuration Deye incomplète.")
    ssid=values["wifi_ssid"]
    password=values["wifi_password"]
    code=values["read_code"]
    name=values["display_name"]
    ip=values["dongle_ip"]
    sn=values["dongle_serial"]
    serial=values["inverter_serial"]
    if not 1<=len(ssid.encode("utf-8"))<=32 or not 8<=len(password)<=64:
        raise ValueError("Wi-Fi invalide (SSID 1–32 octets, code 8–64).")
    if not 8<=len(code)<=32 or any(ord(c)<33 or ord(c)>126 for c in code):
        raise ValueError("Code ESP32 : 8–32 caractères ASCII sans espace.")
    if not 1<=len(name.encode("utf-8"))<=32 or any(ord(c)<32 or c=="|" for c in name):
        raise ValueError("Nom de l'ESP32 invalide.")
    if not _private_ipv4(ip):
        raise ValueError("Adresse IP de clé Deye invalide (réseau privé seulement).")
    if not sn.isascii() or not sn.isdigit() or not 0<int(sn)<=0xffffffff:
        raise ValueError("Numéro de série du dongle invalide (1 à 4294967295).")
    if not serial.isascii() or not serial.isdigit() or not 1<=len(serial)<=32:
        raise ValueError("Numéro de série de l'onduleur invalide.")
    try:
        port=int(values["dongle_port"])
        slave=int(values["modbus_id"])
    except (ValueError,TypeError) as err:
        raise ValueError("Port et ID Modbus : valeurs entières requises.") from err
    if not 1<=port<=65535 or not 1<=slave<=247:
        raise ValueError("Port 1–65535 ; esclave 1–247.")
    payload={k:values[k] for k in sorted(required) if k not in {"dongle_port","modbus_id"}}
    payload.update(dongle_port=port,modbus_id=slave)
    body=json.dumps(payload,ensure_ascii=True,separators=(",",":"))
    message=("SEDEYE_CONFIG "+body+"\n").encode("ascii")
    if len(message)>=1050 or b"\x00" in message:
        raise ValueError("Configuration USB trop longue.")
    return message

def provision_deye(port: str,values: dict[str,str],
                   log: Callable[[str],None],timeout: int=55) -> None:
    payload=deye_config_line(values)
    deadline=time.monotonic()+timeout
    conn=serial.Serial()
    conn.port=port;conn.baudrate=115200
    conn.timeout=.5;conn.write_timeout=3
    conn.dtr=False;conn.rts=False
    while time.monotonic()<deadline:
        try:
            conn.open();conn.dtr=False;conn.rts=False
            break
        except (serial.SerialException,OSError):
            time.sleep(.7)
    else:
        raise RuntimeError("Port USB ESP32-S3 Deye introuvable.")
    try:
        log("Attente de l'ESP32 Deye en USB (aucun secret envoyé avant READY).")
        next_ping=0.0
        while time.monotonic()<deadline:
            now=time.monotonic()
            if now>=next_ping:
                conn.write(b"SEDEYE_PING\n")
                next_ping=now+1.5
            if conn.readline().strip()==b"SEDEYE_READY_V1":
                break
        else:
            raise RuntimeError("ESP32 Deye non prêt. Carte neuve uniquement pour FACTORY.")
        conn.write(payload);conn.flush()
        while time.monotonic()<deadline:
            msg=conn.readline().strip()
            if msg==b"SEDEYE_OK_REBOOT":
                log("Deye enregistré dans NVS ; redémarrage ESP32.")
                return
            if msg==b"SEDEYE_ERR_INVALID":
                raise RuntimeError("L'ESP32 a refusé les paramètres Deye.")
        raise RuntimeError("Pas de confirmation USB après envoi Deye.")
    finally:
        conn.close()
