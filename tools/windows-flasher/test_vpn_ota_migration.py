"""Regression for strictly guarded VPN S3 one-time OTA USB bootstrap.

All tests are local simulations. No physical ESP is touched.
"""
from __future__ import annotations
from pathlib import Path
import struct
import tempfile
import unittest
from vpn_ota_migration import (
    BOOT,TABLE,APP,EXPECTED,EXPECTED_LEGACY,
    validate_installed_vpn_table,validate_images,prepare_usb_migration
)

def table(items):
    body=bytearray()
    for name,(ty,sub,offset,length) in items.items():
        line=bytearray(32)
        struct.pack_into("<HBBII",line,0,0x50aa,ty,sub,offset,length)
        line[12:12+len(name)]=name.encode()
        body.extend(line)
    body.extend(b"\xff"*32)
    return bytes(body).ljust(4096,b"\xff")

def image(size,version=None):
    b=bytearray(size)
    b[0]=0xe9
    b[12:14]=(9).to_bytes(2,"little")
    if version:
        b[100:100+len(version)]=version.encode()
    return bytes(b)

class FakeUsb:
    def __init__(self,initial_nvs=b"se_vpn_cfg----json",initial_table=None):
        self.parts=initial_table or table(EXPECTED_LEGACY)
        self.nvs=initial_nvs.ljust(0x6000,b"\xff")
        self.old_app=image(0x300000,"Smart Energy VPN Gateway")
        self.commands=[]
        self.wrote=False
        self.wrote_parts=None
    def __call__(self,argv):
        if "read_flash" in argv:
            i=argv.index("read_flash")
            offset=int(argv[i+1],0);size=int(argv[i+2],0)
            file=Path(argv[i+3])
            if offset==0x8000:raw=self.wrote_parts if self.wrote else self.parts
            elif offset==0x9000:raw=self.nvs
            elif offset==0x10000:raw=self.old_app
            elif offset==0x0:raw=image(0x8000)
            elif offset==0x610000:raw=b"\xff"*0x2000
            else:raise AssertionError(f"unexpected flash read {offset:x}")
            if len(raw)!=size:raise AssertionError(f"incorrect synthetic fixture {len(raw)} != {size}")
            file.write_bytes(raw)
        elif "write_flash" in argv:
            self.wrote=True
            i=argv.index("write_flash")
            assert argv[i+1:i+7:2]==["0x0","0x8000","0x10000"]
            self.wrote_parts=Path(argv[i+4]).read_bytes().ljust(0x1000,b"\xff")
        elif "verify_flash" in argv:
            assert self.wrote
        else:raise AssertionError(f"invalid esptool command {argv}")
        self.commands.append(argv)

class MigrationSafetyTests(unittest.TestCase):
    def make_images(self,root):
        p=Path(root)
        (p/BOOT).write_bytes(image(16384))
        (p/APP).write_bytes(image(16384,"0.3.14-dev-vpn-wifi-recovery"))
        (p/TABLE).write_bytes(table(EXPECTED)[:3072])
        return p
    def test_exact_images_and_chip(self):
        with tempfile.TemporaryDirectory() as td:
            self.make_images(td)
            validate_images(Path(td))
            (Path(td)/BOOT).write_bytes(image(16384).replace(b"\t\x00",b"\x00\x00",1))
            with self.assertRaises(ValueError):validate_images(Path(td))
    def test_legacy_layout_only(self):
        validate_installed_vpn_table(table(EXPECTED_LEGACY))
        with self.assertRaisesRegex(ValueError,"déjà une table OTA"):
            validate_installed_vpn_table(table(EXPECTED))
        invalid=dict(EXPECTED_LEGACY)
        invalid["nvs"]=(1,2,0xA000,0x5000)
        with self.assertRaisesRegex(ValueError,"Table VPN non reconnue"):
            validate_installed_vpn_table(table(invalid))
    def test_read_and_verify_before_write(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)
            imgs=path/"imgs"
            imgs.mkdir()
            self.make_images(imgs)
            backup=path/"backups"
            usb=FakeUsb()
            prepare_usb_migration("COM5",imgs,backup,lambda s:None,esptool=usb)
            self.assertTrue(usb.wrote)
            self.assertEqual((backup/"nvs-PRIVEE.bin").read_bytes(),usb.nvs)
            self.assertEqual((backup/"nvs-apres-PRIVEE.bin").read_bytes(),usb.nvs)
            self.assertEqual(len((backup/"app-avant.bin").read_bytes()),0x300000)
            operations=[c[[i for i,x in enumerate(c) if x in ("read_flash","write_flash","verify_flash")][0]] for c in usb.commands]
            self.assertEqual(operations.count("write_flash"),1)
            self.assertIn("verify_flash",operations)
            self.assertTrue(all(x=="read_flash" for x in operations[:5]))
            self.assertEqual(operations[-2:],[ "read_flash","read_flash" ])
            self.assertFalse(any("erase_flash" in c for c in usb.commands))
    def test_unknown_or_already_ota_never_written(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td);imgs=p/"imgs";imgs.mkdir();self.make_images(imgs)
            for fixture in (table(EXPECTED),table({"nvs":(1,2,0xA000,0x5000)})):
                usb=FakeUsb(initial_table=fixture)
                with self.assertRaises(ValueError):
                    prepare_usb_migration("COM3",imgs,p/("out"+fixture[:20].hex()),lambda s:None,esptool=usb)
                self.assertFalse(usb.wrote)
    def test_foreign_nvs_is_never_written(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td);imgs=p/"imgs";imgs.mkdir();self.make_images(imgs)
            usb=FakeUsb(initial_nvs=b"not-my-vpn-config")
            with self.assertRaisesRegex(ValueError,"Identité NVS VPN absente"):
                prepare_usb_migration("COM9",imgs,p/"private",lambda s:None,esptool=usb)
            self.assertFalse(usb.wrote)
    def test_missing_images_cannot_flash(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError):
                validate_images(Path(td))

if __name__=="__main__":
    unittest.main()
