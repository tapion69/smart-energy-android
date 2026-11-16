import json
import unittest

from smart_energy_flasher import validate_vpn_config
from vpn_usb import format_usb_configuration
from voltronic_usb import voltronic_config_line
from jk_usb import jk_config_line


def gateway():
    data={
        "wifi_ssid":"TestNetwork24","wifi_password":"testpassfort",
        "tailscale_key":"tskey-auth-unit-test","gateway_token":"T"*30
    }
    for family in ("jk","volt"):
        for i in (1,2,3):
            data[f"{family}{i}_ip"]=""
            data[f"{family}{i}_name"]=""
            data[f"{family}{i}_code"]=""
    return data


class UniversalDiscoveryTests(unittest.TestCase):
    def test_vpn_accepts_preflashed_name_without_known_lan_ip(self):
        values=gateway()
        values["gateway_name"]="maison-vpn"
        values["jk1_name"]="Batteries maison"
        values["jk1_code"]="readerSecret1"
        values["volt1_name"]="Onduleur principal"
        values["volt1_code"]="readerSecret2"
        validate_vpn_config(values)
        message=format_usb_configuration(values).decode("ascii")
        payload=json.loads(message.removeprefix("SEVPN_CONFIG ").strip())
        self.assertEqual(payload["gateway_name"],"maison-vpn")
        self.assertEqual(payload["jk1_ip"],"")
        self.assertEqual(payload["jk1_name"],"Batteries maison")
        self.assertEqual(payload["volt1_name"],"Onduleur principal")
        self.assertEqual(payload["volt1_ip"],"")

    def test_invalid_vpn_name_rejected(self):
        values=gateway()
        values["jk1_name"]="Batteries"
        values["jk1_code"]="readerSecret1"
        values["gateway_name"]="My VPN With Spaces"
        with self.assertRaises(ValueError):validate_vpn_config(values)

    def test_unconfigured_reader_without_name_or_ip_is_rejected(self):
        values=gateway()
        values["jk1_code"]="readerSecret1"
        with self.assertRaises(ValueError):validate_vpn_config(values)

    def test_duplicate_same_family_names_are_rejected(self):
        values=gateway()
        values["jk1_name"]=values["jk2_name"]="Batteries maison"
        values["jk1_code"]="readerSecret1"
        values["jk2_code"]="readerSecret2"
        with self.assertRaises(ValueError):validate_vpn_config(values)

    def test_existing_fixed_ip_configuration_still_valid(self):
        values=gateway()
        values["jk1_ip"]="192.168.1.20"
        values["jk1_code"]="readerSecret1"
        validate_vpn_config(values)

    def test_factory_jk_usb_name_and_code(self):
        data=jk_config_line({"wifi_ssid":"Maison24","wifi_password":"testpassfort",
            "read_code":"jkPersonalABC","display_name":"Batteries maison"})
        p=json.loads(data.decode("ascii").removeprefix("SEJK_CONFIG ").strip())
        self.assertEqual(p["display_name"],"Batteries maison")
        self.assertEqual(p["read_code"],"jkPersonalABC")

    def test_voltronic_factory_usb_name_and_code(self):
        data=voltronic_config_line({"wifi_ssid":"Maison24","wifi_password":"testpassfort",
            "read_code":"voltPersonalABC","display_name":"Onduleur principal"})
        p=json.loads(data.decode("ascii").removeprefix("SEVOLT_CONFIG ").strip())
        self.assertEqual(p["display_name"],"Onduleur principal")

    def test_reject_oversized_or_control_character_reader_name(self):
        for invalid in ("Y"*33,"Oui\nNon","Batteries|maison",""):
            with self.subTest(invalid=repr(invalid)):
                with self.assertRaises(ValueError):
                    jk_config_line({"wifi_ssid":"Maison24","wifi_password":"testpassfort",
                        "read_code":"jkPersonalABC","display_name":invalid})


if __name__=="__main__":
    unittest.main()
