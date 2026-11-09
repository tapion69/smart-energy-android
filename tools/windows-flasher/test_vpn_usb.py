import tempfile
import unittest
from pathlib import Path

from vpn_usb import format_usb_configuration, check_precompiled_image


def values():
    return {
        "wifi_ssid": "Maison",
        "wifi_password": "EXAMPLE_WIFI_SECRET",
        "tailscale_key": "tskey-auth-EXAMPLEONLY",
        "gateway_token": "ABCDEF0123456789ABCDEFGH",
        "jk1_ip": "192.168.1.10", "jk1_code": "EXAMPLE123",
        "jk2_ip": "", "jk2_code": "", "jk3_ip": "", "jk3_code": "",
    }


class UsbProtocolTests(unittest.TestCase):
    def test_ascii_one_line_no_secrets_in_error(self):
        v = values()
        packet = format_usb_configuration(v)
        self.assertTrue(packet.startswith(b"SEVPN_CONFIG {"))
        self.assertTrue(packet.endswith(b"}\n"))
        self.assertEqual(packet.count(b"\n"), 1)
        self.assertLess(len(packet), 1600)

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
            content[0x10000] = 0xe9
            factory.write_bytes(content)
            self.assertEqual(check_precompiled_image(factory, "factory"), 0)
            with self.assertRaises(ValueError):
                check_precompiled_image(factory, "update")


if __name__ == "__main__":
    unittest.main()
