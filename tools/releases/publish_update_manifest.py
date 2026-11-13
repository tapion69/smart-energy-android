#!/usr/bin/env python3
"""Build strict WaterVan-style GitHub release update manifests.

Only executed by our own GitHub release workflow after asset validation.
No APK installation, no firmware flash, no implicit configuration changes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

REPO="tapion69/smart-energy-android"
AND_NAME="SmartEnergy-Android.apk"
FW_NAME="smart-energy-voltronic-S3-OTA.bin"
VERSION_RE=re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?$")
TAG_RE=re.compile(r"^(android|voltronic)-v(.+)$")

def digest(path):
    h=hashlib.sha256()
    with open(path,"rb") as f:
        for chunk in iter(lambda:f.read(65536),b""):h.update(chunk)
    return h.hexdigest()

def manifest(tag,filename,size,expected_version="",version_code=0):
    match=TAG_RE.fullmatch(tag)
    if not match:raise ValueError("Release tag must be android-vX or voltronic-vX")
    product,version=match.groups()
    if not VERSION_RE.fullmatch(version) or version!=expected_version:
        raise ValueError("Release tag must match the actual binary version")
    path=Path(filename)
    if not path.is_file() or not 20000<=path.stat().st_size<=size:
        raise ValueError("Binary size invalid")
    if path.name!=(AND_NAME if product=="android" else FW_NAME):
        raise ValueError("Unexpected asset name")
    url=f"https://github.com/{REPO}/releases/download/{tag}/{path.name}"
    sha=digest(path)
    if product=="android":
        if not 1<=version_code<=100000000:
            raise ValueError("Invalid Android versionCode")
        result={
            "schemaVersion":1,"product":"smart-energy-android","available":True,
            "versionName":version,"versionCode":version_code,
            "downloadUrl":url,"sha256":sha,
            "releaseNotesUrl":f"https://github.com/{REPO}/releases/tag/{tag}",
            "minimumSupportedVersionCode":1
        }
        target="updates/android.json"
    else:
        # ESP32-S3 image header magic must match the target architecture.
        with path.open("rb") as f:header=f.read(16)
        if len(header)<14 or header[0]!=0xe9 or int.from_bytes(
                header[12:14],"little")!=9:
            raise ValueError("Firmware is not an ESP32-S3 image")
        result={
            "schemaVersion":1,"product":"smart-voltronic-esp32","available":True,
            "version":version,"hardwareTargets":["esp32s3-n16r8"],
            "downloadUrl":url,"sha256":sha,
            "releaseNotesUrl":f"https://github.com/{REPO}/releases/tag/{tag}"
        }
        target="updates/voltronic-esp32.json"
    return target,result

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--tag",required=True)
    p.add_argument("--asset",required=True)
    p.add_argument("--version",required=True)
    p.add_argument("--version-code",type=int,default=0)
    args=p.parse_args()
    target,doc=manifest(args.tag,args.asset,
                        128*1024*1024 if args.tag.startswith("android-")
                        else 2*1024*1024,
                        args.version,args.version_code)
    Path(target).parent.mkdir(parents=True,exist_ok=True)
    Path(target).write_text(json.dumps(doc,indent=2,ensure_ascii=False)+"\n",
                            encoding="utf-8")
    print("Safe GitHub update manifest:",target,"version:",args.version)

if __name__=="__main__":
    main()
