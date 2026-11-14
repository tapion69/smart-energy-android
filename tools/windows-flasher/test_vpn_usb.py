import tempfile
import unittest
from pathlib import Path

from vpn_usb import format_usb_configuration,format_voltronic_patch,check_precompiled_image


def values():
    return {
        "wifi_ssid": "Maison",
        "wifi_password": "EXAMPLE_WIFI_SECRET",
        "tailscale_key": "tskey-auth-EXAMPLEONLY",
        "gateway_token": "ABCDEF0123456789ABCDEFGH",
        "jk1_ip": "192.168.1.10", "jk1_code": "EXAMPLE123",
        "jk2_ip": "", "jk2_code": "", "jk3_ip": "", "jk3_code": "",
        "volt1_ip":"","volt1_code":"","volt2_ip":"","volt2_code":"",
        "volt3_ip":"","volt3_code":"",
    }


class UsbProtocolTests(unittest.TestCase):
    def test_ascii_one_line_no_secrets_in_error(self):
        v = values()
        packet = format_usb_configuration(v)
        self.assertTrue(packet.startswith(b"SEVPN_CONFIG {"))
        self.assertTrue(packet.endswith(b"}\n"))
        self.assertEqual(packet.count(b"\n"), 1)
        self.assertLess(len(packet), 1600)

    def test_voltronic_add_never_sends_existing_secrets(self):
        packet=format_voltronic_patch(1,"192.168.1.63","TEST_ACCESS_123")
        self.assertTrue(packet.startswith(b"SEVPN_ADD_VOLTRONIC "))
        self.assertEqual(packet.count(b"\n"),1)
        self.assertNotIn(b"tailscale",packet)
        self.assertNotIn(b"wifi_",packet)
        self.assertNotIn(b"jk1",packet)
        self.assertNotIn(b"gateway_token",packet)

    def test_voltronic_add_validates_address_and_slot(self):
        for ip in ("8.8.8.8","100.64.0.4","169.254.3.4","bad-ip"):
            with self.assertRaises(ValueError):
                format_voltronic_patch(1,ip,"TEST_ACCESS_123")
        with self.assertRaises(ValueError):
            format_voltronic_patch(4,"192.168.1.63","TEST_ACCESS_123")
        with self.assertRaises(ValueError):
            format_voltronic_patch(1,"192.168.1.63","short")

    def test_missing_field_fails(self):
        v = values()
        v.pop("wifi_password")
        with self.assertRaises(ValueError):
            format_usb_configuration(v)

    def test_large_payload_fails(self):
        v = values()
        v["wifi_password"] = "a" * 2000
        with self.assertRaises(ValueError):
            format_usb_configuration(v)

    def test_factory_and_app_images_have_distinct_offsets(self):
        with tempfile.TemporaryDirectory() as dirname:
            app = Path(dirname) / "app.bin"
            app.write_bytes(b"\xe9" + b"\0" * (17000 - 1))
            self.assertEqual(check_precompiled_image(app, "update"), 0x10000)
            with self.assertRaises(ValueError):
                check_precompiled_image(app, "factory")
            factory = Path(dirname) / "factory.bin"
            content = bytearray(b"\xff" * (0x10000 + 17000))
            content[0x1000] = 0xe9
            content[0x1000 + 12:0x1000 + 14] = b"\x00\x00"
            content[0x10000] = 0xe9
            content[0x10000 + 12:0x10000 + 14] = b"\x00\x00"
            factory.write_bytes(content)
            self.assertEqual(check_precompiled_image(factory, "factory"), 0)
            with self.assertRaises(ValueError):
                check_precompiled_image(factory, "update")


    def test_s3_n16r8_factory_at_zero_and_app_at_0x10000(self):
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / "esp32-s3.bin"
            body = bytearray(b"\xff" * (0x10000 + 17000))
            for where in (0, 0x10000):
                body[where] = 0xE9
                body[where+12:where+14] = (9).to_bytes(2,"little")
            destination.write_bytes(body)
            self.assertEqual(check_precompiled_image(destination,"factory","esp32s3"),0)
            with self.assertRaises(ValueError):
                check_precompiled_image(destination,"factory","esp32")
            app=Path(folder)/"s3-app.bin"
            app.write_bytes(body[0x10000:])
            self.assertEqual(check_precompiled_image(app,"update","esp32s3"),0x10000)
            with self.assertRaises(ValueError):
                check_precompiled_image(app,"update","esp32")

    def test_factory_bootloader_requires_target_header(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"bad-factory.bin"
            body=bytearray(b"\xff" * (0x10000+17000))
            body[0] = 0xE9
            body[12:14] = (9).to_bytes(2,"little")
            body[0x10000] = 0xE9
            body[0x10000+12:0x10000+14] = (0).to_bytes(2,"little")
            path.write_bytes(body)
            with self.assertRaises(ValueError):
                check_precompiled_image(path,"factory","esp32s3")


if __name__ == "__main__":
    unittest.main()
