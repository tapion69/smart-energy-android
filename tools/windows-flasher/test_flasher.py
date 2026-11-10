import unittest

from smart_energy_flasher import sdkconfig_contents, valid_lan_ip, valid_serial_port, validate_vpn_config, selected_serial_port, serial_port_label


def config():
    return {
        "wifi_ssid": "MaBox24", "wifi_password": "secretfort",
        "tailscale_key": "tskey-auth-example", "gateway_token": "A" * 25,
        "jk1_ip": "192.168.1.53", "jk1_code": "readerSecret1",
        "jk2_ip": "10.0.0.22", "jk2_code": "readerSecret2",
        "jk3_ip": "", "jk3_code": "",
    }


class FlasherTests(unittest.TestCase):
    def test_lan_only(self):
        self.assertTrue(valid_lan_ip("192.168.1.53"))
        self.assertTrue(valid_lan_ip("172.18.1.2"))
        self.assertFalse(valid_lan_ip("100.100.0.1"))
        self.assertFalse(valid_lan_ip("8.8.8.8"))
        self.assertFalse(valid_lan_ip("127.0.0.1"))
        self.assertFalse(valid_lan_ip("example.com"))

    def test_reject_incomplete_slot(self):
        c = config()
        c["jk2_code"] = ""
        with self.assertRaises(ValueError):
            validate_vpn_config(c)

    def test_three_readers_are_distinct(self):
        c = config()
        c["jk3_ip"] = "192.168.1.60"
        c["jk3_code"] = "abc12345678"
        validate_vpn_config(c)
        content = sdkconfig_contents(c)
        self.assertIn('CONFIG_SE_JK3_IP="192.168.1.60"', content)
        self.assertIn('CONFIG_SE_JK2_READ_CODE="readerSecret2"', content)

    def test_escape_dangerous_characters(self):
        c = config()
        c["wifi_password"] = 'go\\"backslash'
        content = sdkconfig_contents(c)
        self.assertIn('CONFIG_ML_WIFI_PASSWORD="go\\\\\\"backslash"', content)

    def test_no_newline_sdkconfig_injection(self):
        c = config()
        c["gateway_token"] = "A" * 20 + "\nCONFIG_ML_WIFI_SSID=evil"
        with self.assertRaises(ValueError):
            validate_vpn_config(c)

    def test_serial_port(self):
        self.assertTrue(valid_serial_port("COM5"))
        self.assertTrue(valid_serial_port("com12"))
        self.assertFalse(valid_serial_port("ttyUSB0"))
        self.assertFalse(valid_serial_port("COM5 && erase_flash"))

    def test_dropdown_labels_and_safe_port_extraction(self):
        label = serial_port_label("COM12", "USB-SERIAL CH340")
        self.assertEqual(label, "COM12 — USB-SERIAL CH340")
        self.assertEqual(selected_serial_port(label), "COM12")
        self.assertEqual(selected_serial_port("com8"), "COM8")
        self.assertEqual(serial_port_label("COM5", "n/a"), "COM5")

    def test_invalid_serial_labels_rejected(self):
        for item in ("", "ttyUSB0", "COM5 && erase_flash", "COM5 — ",
                     "COM5 — device\\nrm"):
            with self.subTest(item=item):
                with self.assertRaises(ValueError):
                    selected_serial_port(item)


if __name__ == "__main__":
    unittest.main()
