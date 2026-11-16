"""Physical-USB-only factory onboarding for Smart Energy JK ESP32.
No Wi-Fi credentials, codes or names are compiled into firmware or uploaded.
Never automatically reconfigure a previously provisioned JK board.
"""
import json
import time
from typing import Callable
import serial


def jk_config_line(values:dict[str,str])->bytes:
    keys=("wifi_ssid","wifi_password","read_code","display_name")
    if not all(k in values for k in keys):
        raise ValueError("Configuration initiale JK incomplète.")
    ssid=values["wifi_ssid"]
    password=values["wifi_password"]
    code=values["read_code"]
    label=values["display_name"].strip()
    if not 1<=len(ssid.encode("utf-8"))<=32:
        raise ValueError("SSID JK invalide (1–32 octets).")
    if not 8<=len(password)<=63:
        raise ValueError("Mot de passe Wi-Fi JK invalide.")
    if not 8<=len(code)<=32 or any(not 33<=ord(c)<=126 for c in code):
        raise ValueError("Code de lecture JK invalide (8–32 caractères ASCII).")
    if not 1<=len(label.encode("utf-8"))<=32 or any(ord(c)<32 or c=='|' for c in label):
        raise ValueError("Nom JK invalide (1–32 octets).")
    payload=json.dumps({**{key:values[key] for key in keys},"display_name":label},
                       ensure_ascii=True,separators=(",",":"))
    message=("SEJK_CONFIG "+payload+"\n").encode("ascii")
    if len(message)>=349 or b"\x00" in message:
        raise ValueError("Configuration JK USB trop longue.")
    return message


def provision_jk(port:str,values:dict[str,str],log:Callable[[str],None],
                 timeout:float=50)->None:
    payload=jk_config_line(values)
    deadline=time.monotonic()+timeout
    ser=serial.Serial()
    ser.port=port
    ser.baudrate=115200
    ser.timeout=0.4
    ser.write_timeout=1.2
    ser.dtr=False
    ser.rts=False
    try:
        while time.monotonic()<deadline:
            try:
                ser.open()
                ser.dtr=False
                ser.rts=False
                break
            except (serial.SerialException,OSError):
                time.sleep(0.4)
        else:
            raise RuntimeError("Lecteur JK USB introuvable après flash.")
        last_ping=0.0
        while time.monotonic()<deadline:
            if time.monotonic()-last_ping>1.5:
                ser.write(b"SEJK_PING\n")
                last_ping=time.monotonic()
            response=ser.readline().strip()
            if response==b"SEJK_READY_V1":
                break
        else:
            raise RuntimeError("Firmware JK non prêt pour la configuration USB.")
        log("ESP JK reconnu : transfert des paramètres privés sur le câble USB.")
        ser.write(payload) # ONCE. Never retry an uncertain write.
        ser.flush()
        while time.monotonic()<deadline:
            response=ser.readline().strip()
            if response==b"SEJK_OK_REBOOT":
                log("Configuration initiale JK confirmée ; redémarrage.")
                return
            if response==b"SEJK_ERR_INVALID":
                raise RuntimeError("Le firmware JK a refusé la configuration.")
        raise RuntimeError("Configuration JK non confirmée : ne relance pas automatiquement.")
    finally:
        if ser.is_open:
            ser.close()
