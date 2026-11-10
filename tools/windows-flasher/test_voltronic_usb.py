import unittest
from voltronic_usb import voltronic_config_line

class VoltronicUsbTests(unittest.TestCase):
    def test_usb_line_contains_only_allowed_keys_and_framing(self):
        data=voltronic_config_line({
            "wifi_ssid":"maison","wifi_password":"EXAMPLEpass",
            "read_code":"Private123"
        })
        self.assertTrue(data.startswith(b"SEVOLT_CONFIG {"))
        self.assertTrue(data.endswith(b"}\n"))
        self.assertEqual(data.count(b"\n"),1)
        self.assertLess(len(data),299)

    def test_invalid_or_missing_secrets_rejected(self):
        for cfg in (
            {"wifi_ssid":"test","wifi_password":"12345678"},
            {"wifi_ssid":"test","wifi_password":"12345678","read_code":"abc"},
            {"wifi_ssid":"test","wifi_password":"12345678","read_code":"injected\nbad"},
        ):
            with self.assertRaises(ValueError):
                voltronic_config_line(cfg)
if __name__=="__main__":
    unittest.main()
