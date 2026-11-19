import unittest
import struct
import tempfile
from pathlib import Path
from voltronic_ota_migration import (
    EXPECTED,BOOT,PARTITION,APP,parse_partition_table,
    validate_migration_images,check_installed_voltronic_layout,
    describe_partition_table
)

class MigrationTests(unittest.TestCase):
    def make_table(self):
        raw=bytearray()
        for name,(ty,sub,off,length) in EXPECTED.items():
            row=bytearray(32)
            struct.pack_into("<HBBII",row,0,0x50aa,ty,sub,off,length)
            row[12:12+len(name)]=name.encode("ascii")
            raw.extend(row)
        raw.extend(b"\xff"*32)
        return bytes(raw).ljust(3072,b"\xff")
    def test_preserve_exact_nvs_and_ota(self):
        self.assertEqual(parse_partition_table(self.make_table()),EXPECTED)
        self.assertEqual(EXPECTED["nvs"][2:],(0x9000,0x6000))
        self.assertEqual(EXPECTED["ota_1"][2],0x410000)
    def test_installed_single_app_is_required_for_usb_migration(self):
        def row(name,kind,sub,offset,size):
            entry=bytearray(32)
            struct.pack_into("<HBBII",entry,0,0x50aa,kind,sub,offset,size)
            entry[12:12+len(name)]=name.encode("ascii")
            return bytes(entry)
        original=(row("nvs",1,2,0x9000,0x6000)+
                  row("phy_init",1,1,0xf000,0x1000)+
                  row("factory",0,0,0x10000,0x300000)+
                  b"\xff"*32)
        check_installed_voltronic_layout(original)
        # Stock ESP-IDF single_app_large can be 1.5 MB.
        for old_size in (0x100000,0x180000,0x1e0000,0x200000):
            variant=original.replace(row("factory",0,0,0x10000,0x300000),
                                      row("factory",0,0,0x10000,old_size))
            check_installed_voltronic_layout(variant)
        self.assertIn("taille=0x180000",
            describe_partition_table({"factory":(0,0,0x10000,0x180000)}))
        with self.assertRaises(ValueError):
            check_installed_voltronic_layout(original.replace(
                row("nvs",1,2,0x9000,0x6000),row("nvs",1,2,0xA000,0x5000)))
        with self.assertRaises(ValueError):
            check_installed_voltronic_layout(original.replace(
                b"\xff"*32,row("ota_1",0,0x11,0x410000,0x200000)+b"\xff"*32))
        with self.assertRaises(ValueError):
            check_installed_voltronic_layout(original.replace(
                row("factory",0,0,0x10000,0x300000),
                row("factory",0,0,0x10000,0x80000)))

    def test_reject_unknown_data_partitions_and_non_ota_safe_layout(self):
        def row(name,kind,sub,offset,size):
            entry=bytearray(32)
            struct.pack_into("<HBBII",entry,0,0x50aa,kind,sub,offset,size)
            entry[12:12+len(name)]=name.encode("ascii")
            return bytes(entry)
        base=(row("nvs",1,2,0x9000,0x6000)+
              row("phy_init",1,1,0xf000,0x1000)+
              row("factory",0,0,0x10000,0x180000))
        with self.assertRaisesRegex(ValueError,"supplémentaires"):
            check_installed_voltronic_layout(base+
                row("storage",1,0x82,0x210000,0x10000)+b"\\xff"*32)
        with self.assertRaisesRegex(ValueError,"ancienne configuration OTA"):
            check_installed_voltronic_layout(base+
                row("otadata",1,0,0x610000,0x2000)+b"\\xff"*32)
        with self.assertRaisesRegex(ValueError,"PHY non reconnue"):
            check_installed_voltronic_layout(
                row("nvs",1,2,0x9000,0x6000)+
                row("phy_init",1,1,0xe000,0x1000)+
                row("factory",0,0,0x10000,0x180000)+b"\\xff"*32)
        with self.assertRaisesRegex(ValueError,"Ancien partitionnement"):
            check_installed_voltronic_layout(
                row("nvs",1,2,0x9000,0x6000)+
                row("phy_init",1,1,0xf000,0x1000)+
                row("factory",0,0,0x20000,0x180000)+b"\\xff"*32)

    def test_no_merged_image_or_wrong_chip(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d)
            boot=bytearray(16384);boot[0]=0xe9;boot[12]=9
            app=bytearray(16384);app[0]=0xe9;app[12]=9
            (folder/BOOT).write_bytes(boot)
            (folder/PARTITION).write_bytes(self.make_table())
            (folder/APP).write_bytes(app)
            self.assertEqual(len(validate_migration_images(folder)),3)
            boot[12]=0
            (folder/BOOT).write_bytes(boot)
            with self.assertRaises(ValueError):validate_migration_images(folder)
            (folder/BOOT).write_bytes(b"\0"*65536)
            with self.assertRaises(ValueError):validate_migration_images(folder)

if __name__=="__main__":
    unittest.main()
