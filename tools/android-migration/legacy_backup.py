#!/usr/bin/env python3
"""One-time, consented export of non-secret settings from an installed DEBUG APK.

The former GitHub runner signing key cannot be recovered. Do NOT uninstall the
old app. Android Keystore ciphertext cannot be migrated to another app UID.
This utility extracts ONLY known harmless configuration fields (e.g. BMS names,
IP addresses, slot assignments) and never exports access codes or tokens.
Runs on the user's PC and talks only to local Android Debug Bridge.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

FORMAT="smart-energy-settings-migration"
SCHEMA=1
PACKAGE_RE=re.compile(r"^fr\.smartenergy\.android(?:\.[a-z0-9_]+)?$")
READER_RE=re.compile(r"^reader_[1-3]_(device_id|setup_ssid|local_ip|paired_bms_json|known_jk_macs)$")
NAME_RE=re.compile(r"^bms_name_[A-Fa-f0-9]{12}$")
STRINGS={
    "device_id","setup_ssid","local_ip","paired_bms_json","known_jk_macs",
    "remote_gateway_ip","connection_preference","energy_dashboard_style",
    "inverter_probe_ip","inverter_probe_type","inverter_2_ip","inverter_3_ip"
}
BOOLEANS={
    "remote_gateway_enabled","remote_voltronic_enabled","bms_alarm_notifications",
    "debug_enabled","alert_bms_alarm_enabled","alert_global_soc_enabled",
    "alert_bms_soc_enabled","alert_cell_high_enabled","alert_temp_high_enabled"
}
INTS={"active_reader_slot","inverter_probe_port","inverter_2_port",
      "inverter_3_port","alert_global_soc_below","alert_bms_soc_below"}
FLOATS={"alert_cell_high_above_v","alert_temp_high_above_c"}
SUPPORTED={"string":STRINGS,"boolean":BOOLEANS,"int":INTS,"float":FLOATS}

def allowed(key,kind):
    if kind=="string" and (READER_RE.fullmatch(key) or NAME_RE.fullmatch(key)):
        return True
    return key in SUPPORTED.get(kind,set())

def parse_preferences(raw:bytes):
    if len(raw)>1024*1024:
        raise ValueError("Android preference XML too large")
    root=ET.fromstring(raw)
    if root.tag!="map":raise ValueError("Unexpected Android preferences format")
    out={}
    for element in root:
        key=element.attrib.get("name","")
        kind=element.tag
        if not allowed(key,kind):continue
        if kind=="string":
            value=element.text or ""
            if len(value)>8192:continue
        elif kind=="boolean":
            if element.attrib.get("value") not in ("true","false"):continue
            value=element.attrib["value"]=="true"
        elif kind=="int":
            try:value=int(element.attrib["value"])
            except (ValueError,KeyError):continue
        elif kind=="float":
            try:value=float(element.attrib["value"])
            except (ValueError,KeyError):continue
            if not (value==value and abs(value)<1e6):continue
        else:continue
        out[key]=value
    if not out:raise ValueError("No eligible non-secret settings in this APK")
    return {"format":FORMAT,"schema":SCHEMA,"settings":out}

def find_adb():
    found=shutil.which("adb")
    local=os.getenv("LOCALAPPDATA","")
    choices=[found]
    if local:choices.append(os.path.join(local,"Android","Sdk","platform-tools","adb.exe"))
    for name in ("ANDROID_HOME","ANDROID_SDK_ROOT"):
        base=os.getenv(name)
        if base:choices.append(str(Path(base)/"platform-tools"/("adb.exe" if os.name=="nt" else "adb")))
    for path in choices:
        if path and Path(path).is_file():return path
    raise RuntimeError("adb.exe introuvable : installe Android SDK Platform-Tools (Android Studio)")

def run_adb(adb,args):
    result=subprocess.run([adb,*args],capture_output=True,timeout=20)
    if result.returncode:
        # Do not print device stdout/stderr: it might contain private data.
        raise RuntimeError("ADB refused the command; check USB debugging and authorization")
    return result.stdout

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--package",default="fr.smartenergy.android.vpntrial333",
                        help="Exact installed debug package id")
    parser.add_argument("--output",default="SmartEnergy_Ancienne_Configuration.json")
    args=parser.parse_args()
    if not PACKAGE_RE.fullmatch(args.package):raise SystemExit("Invalid package id")
    adb=find_adb()
    devices=run_adb(adb,["devices"]).decode("utf-8",errors="replace")
    active=[x for x in devices.splitlines()[1:] if x.strip().endswith("\tdevice")]
    if len(active)!=1:
        raise RuntimeError("Connect exactly one authorized Android phone over USB")
    # Android's 'run-as' works on debuggable packages; no root required.
    xml=run_adb(adb,["exec-out","run-as",args.package,"cat",
                     "shared_prefs/smart_energy_v012.xml"])
    result=parse_preferences(xml)
    dest=Path(args.output).resolve()
    if dest.exists():raise FileExistsError("Backup file exists; choose --output")
    dest.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Backup created: {dest.name}")
    print(f"Restorable fields: {len(result['settings'])}")
    print("Sensitive fields (access codes, keys, VPN token) deliberately excluded.")
    print("Keep the existing Android application installed until migration is verified.")
    print("Do not upload this backup into chats or public repositories.")

if __name__=="__main__":
    try:main()
    except Exception as exc:
        print("Migration backup unavailable:",str(exc),file=sys.stderr)
        sys.exit(1)
