"""Regression checks for the one-slot physical USB VPN inverter recovery.

No firmware flash call must exist in the stand-alone rescue interface.
"""
from pathlib import Path
import json
import unittest
from vpn_usb import format_voltronic_patch

HERE=Path(__file__).resolve().parent
UI=(HERE/"vpn_target_repair.py").read_text(encoding="utf-8")
FLASHER=(HERE/"smart_energy_flasher.py").read_text(encoding="utf-8")
FIRMWARE=(HERE.parent.parent/"firmware/vpn-gateway/main/vpn_provision.c").read_text(
    encoding="utf-8")

class TestVpnInverterRepair(unittest.TestCase):
    def test_fixed_one_slot_payload(self):
        payload=format_voltronic_patch(2,"192.168.1.52","testSecret123").decode()
        prefix="SEVPN_ADD_VOLTRONIC "
        self.assertTrue(payload.startswith(prefix))
        self.assertTrue(payload.endswith("\n"))
        self.assertEqual(json.loads(payload[len(prefix):]),
                         {"slot":2,"ip":"192.168.1.52","code":"testSecret123"})
        self.assertNotIn("tailscale_key",payload)
        self.assertNotIn("jk1",payload)
        self.assertNotIn("gateway_token",payload)

    def test_invalid_target_rejected(self):
        for bad_ip in ("8.8.8.8","100.100.1.2","192.168.1.52\nHELLO",""):
            with self.subTest(ip=bad_ip):
                with self.assertRaises(ValueError):
                    format_voltronic_patch(1,bad_ip,"testSecret123")
        for slot in (-1,0,4):
            with self.assertRaises(ValueError):
                format_voltronic_patch(slot,"192.168.1.52","testSecret123")

    def test_standalone_only_sends_physical_usb_patch(self):
        self.assertIn("provision_over_serial(port, None, safe_log",UI)
        self.assertIn("payload_override=payload",UI)
        self.assertIn("format_voltronic_patch(slot, address, secret)",UI)
        self.assertNotIn("flash_precompiled",UI)
        self.assertNotIn("erase_flash",UI)
        self.assertNotIn("SEVPN_CONFIG ",UI)
        self.assertNotIn("SEVPN_SET_JK ",UI)
        self.assertNotIn("SEVPN_REMOTE_WRITES_",UI)
        self.assertIn('self.code.set("")',UI)

    def test_existing_flasher_exposes_repair(self):
        self.assertIn('self.vpn_patch_slot = tk.StringVar(value="1")',FLASHER)
        self.assertIn("self.vpn_repair_ip = tk.StringVar()",FLASHER)
        self.assertIn("self.vpn_repair_code = tk.StringVar()",FLASHER)
        self.assertIn("command=self._add_voltronic_only",FLASHER)
        self.assertIn("ip=self.vpn_repair_ip.get().strip()",FLASHER)
        self.assertIn("code=self.vpn_repair_code.get()",FLASHER)

    def test_firmware_requires_physical_usb_and_nvs_readback(self):
        self.assertIn('const char add_volt[]="SEVPN_ADD_VOLTRONIC "',FIRMWARE)
        self.assertIn("patch_voltronic_only(buffer+sizeof(add_volt)-1)",FIRMWARE)
        self.assertIn("success=load_config(&persisted)",FIRMWARE)
        self.assertIn("previous.remote_writes_enabled!=checked.remote_writes_enabled",FIRMWARE)
        self.assertIn("memcmp(&persisted,&checked,sizeof(checked))==0",FIRMWARE)


if __name__=="__main__":
    unittest.main()
