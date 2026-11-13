import unittest
import struct
import tempfile
from pathlib import Path
from voltronic_ota_migration import (
    EXPECTED,BOOT,PARTITION,APP,parse_partition_table,
    validate_migration_images
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
