import unittest
from deye_usb import deye_config_line, _private_ipv4

BASE = {
    "wifi_ssid":"Maison", "wifi_password":"motdepasse2026",
    "read_code":"DeyeCode123!", "display_name":"Onduleur Deye",
    "dongle_ip":"192.168.1.215","dongle_port":"8899",
    "modbus_id":"1", "dongle_serial":"3133245188",
    "inverter_serial":"2506270609"
}

class DeyeUsbTests(unittest.TestCase):
    def test_valid_solarman_config(self):
        packet=deye_config_line(BASE)
        self.assertTrue(packet.startswith(b"SEDEYE_CONFIG {"))
        self.assertTrue(packet.endswith(b"\n"))
        self.assertLess(len(packet),1050)
        self.assertIn(b'"dongle_port":8899',packet)
        self.assertIn(b'"modbus_id":1',packet)
        self.assertIn(b'"dongle_serial":"3133245188"',packet)
        self.assertNotIn(b"tcp://",packet)

    def test_private_ipv4_only(self):
        self.assertTrue(_private_ipv4("172.31.1.2"))
        self.assertTrue(_private_ipv4("192.168.1.215"))
        for ip in ("8.8.8.8","100.91.3.2","169.254.2.3","127.0.0.1",
                   "192.168.1.215.evil","10.0.0.1:8899",""):
            with self.subTest(ip=ip):
                self.assertFalse(_private_ipv4(ip))
                with self.assertRaises(ValueError):
                    deye_config_line({**BASE,"dongle_ip":ip})

    def test_reject_invalid_ports_and_serials(self):
        for value in ("0","65536","8899.5","-1"):
            with self.assertRaises(ValueError):
                deye_config_line({**BASE,"dongle_port":value})
        for value in ("0","248","two"):
            with self.assertRaises(ValueError):
                deye_config_line({**BASE,"modbus_id":value})
        for value in ("", "000", "4294967296","notserial","3133245188/"):
            with self.assertRaises(ValueError):
                deye_config_line({**BASE,"dongle_serial":value})

    def test_no_missing_fields(self):
        for field in BASE:
            with self.assertRaises(ValueError):
                deye_config_line({k:v for k,v in BASE.items() if k!=field})

if __name__=="__main__":
    unittest.main()
