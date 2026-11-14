import struct
import tempfile
import unittest
from pathlib import Path

from jk_usb_guard import (
    parse_jk_partitions, check_jk_app_only, backup_jk_nvs
)


def table(*rows):
    contents=bytearray(b"\xff"*0x1000)
    for i,(kind,subtype,offset,size,label) in enumerate(rows):
        struct.pack_into("<HBBII16sI",contents,32*i,0x50aa,
                         kind,subtype,offset,size,label.encode("ascii"),0)
    return bytes(contents)


VALID=table(
    (1,2,0x9000,0x5000,"nvs"),
    (1,0,0xe000,0x2000,"otadata"),
    (0,0x10,0x10000,0x140000,"ota_0"),
    (0,0x11,0x150000,0x140000,"ota_1"),
)


class JkUsbGuardTests(unittest.TestCase):
    def test_realistic_ota_and_private_nvs(self):
        p=check_jk_app_only(VALID,750000)
        self.assertEqual((p.nvs.start,p.nvs.length),(0x9000,0x5000))
        self.assertTrue(p.has_second_ota_slot)
        self.assertEqual(p.app0.start,0x10000)

    def test_single_factory_app_is_acceptable_but_does_not_create_ota(self):
        raw=table((1,2,0x9000,0x6000,"nvs"),
                  (0,0,0x10000,0x130000,"factory"))
        p=check_jk_app_only(raw,750000)
        self.assertFalse(p.has_second_ota_slot)

    def test_refuse_unknown_table_or_oversize_firmware(self):
        with self.assertRaises(ValueError):
            check_jk_app_only(b"\xff"*0x1000,600000)
        with self.assertRaises(ValueError):
            check_jk_app_only(VALID,0x140001)

    def test_refuse_table_with_nvs_overlapping_app(self):
        bad=table((1,2,0x9000,0x8000,"nvs"),
                  (0,0,0x10000,0x130000,"factory"))
        with self.assertRaises(ValueError):
            check_jk_app_only(bad,500000)

    def test_backup_is_required_before_any_flash_and_never_overwrites(self):
        nvs=b"private Wi-Fi and BMS code" + b"\xaa"*(0x5000-25)
        nvs=nvs[:0x5000].ljust(0x5000,b"\xaa")
        calls=[]
        def fake_esptool(argv):
            calls.append(list(argv))
            if argv[-3:-1] == ["0x8000","0x1000"]:
                Path(argv[-1]).write_bytes(VALID)
            elif argv[-3:-1] == ["0x10000","0x140000"]:
                product=b"smart-jk-esp32"
                Path(argv[-1]).write_bytes(product+b"\xff"*(0x140000-len(product)))
            elif argv[-3:-1] == ["0x9000","0x5000"]:
                Path(argv[-1]).write_bytes(nvs)
            else:
                raise AssertionError("Unexpected read operation")
        with tempfile.TemporaryDirectory() as temp:
            dst=Path(temp)/"private-nvs.bin"
            ready=backup_jk_nvs("COM11",dst,750000,fake_esptool)
            self.assertTrue(dst.exists())
            self.assertEqual(dst.read_bytes(),nvs)
            self.assertEqual(ready.app0.start,0x10000)
            self.assertEqual(len(calls),3)
            self.assertTrue(all("read_flash" in x for x in calls))
            with self.assertRaises(ValueError):
                backup_jk_nvs("COM11",dst,750000,fake_esptool)

    def test_vpn_board_with_identical_partition_table_is_refused(self):
        calls=[]
        def fake_esptool(argv):
            calls.append(list(argv))
            if argv[-3:-1]==["0x8000","0x1000"]:
                Path(argv[-1]).write_bytes(VALID)
            elif argv[-3:-1]==["0x10000","0x140000"]:
                product=b"smart-energy-vpn-gateway"
                Path(argv[-1]).write_bytes(product+b"\xff"*(0x140000-len(product)))
            else:
                raise AssertionError("The NVS must not be backed up on a wrong board")
        with tempfile.TemporaryDirectory() as temp:
            dst=Path(temp)/"private.bin"
            with self.assertRaisesRegex(ValueError,"Smart JK"):
                backup_jk_nvs("COM11",dst,750000,fake_esptool)
            self.assertFalse(dst.exists())
            self.assertEqual(len(calls),2)

    def test_unsafe_partition_refuses_backup_and_never_reads_nvs(self):
        calls=[]
        def fake_esptool(argv):
            calls.append(list(argv))
            Path(argv[-1]).write_bytes(b"\xff"*0x1000)
        with tempfile.TemporaryDirectory() as temp:
            dst=Path(temp)/"private.bin"
            with self.assertRaises(ValueError):
                backup_jk_nvs("COM11",dst,600000,fake_esptool)
            self.assertFalse(dst.exists())
            self.assertEqual(len(calls),1)


if __name__=="__main__":
    unittest.main()
