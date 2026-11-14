import pathlib
import sys
import unittest
sys.path.insert(0,str(pathlib.Path(__file__).parent))
from legacy_backup import parse_preferences

class LegacyBackupTests(unittest.TestCase):
    def test_preserves_fleet_and_never_exports_encrypted_codes(self):
        xml=b'''<map>
          <string name="device_id">smartjk-000001</string>
          <string name="paired_bms_json">["AA:BB:CC:DD:EE:FF"]</string>
          <string name="reader_2_local_ip">192.168.1.31</string>
          <string name="bms_name_AABBCCDDEEFF">Battery 1</string>
          <string name="premium_voltronic_code_cipher">PRIVATE_CIPHER</string>
          <string name="reader_2_pairing_code_cipher">PRIVATE_CIPHER</string>
          <string name="remote_gateway_token_cipher">PRIVATE_CIPHER</string>
          <string name="wifi_password">DONTLEAK</string>
          <boolean name="remote_voltronic_enabled" value="true"/>
          <int name="active_reader_slot" value="2"/>
        </map>'''
        data=parse_preferences(xml)
        self.assertEqual(data["schema"],1)
        values=data["settings"]
        self.assertEqual(values["reader_2_local_ip"],"192.168.1.31")
        self.assertTrue(values["remote_voltronic_enabled"])
        self.assertEqual(values["active_reader_slot"],2)
        self.assertEqual(len(values),6)
        self.assertNotIn("PRIVATE_CIPHER",str(data))
        self.assertNotIn("DONTLEAK",str(data))
    def test_empty_data_denied(self):
        with self.assertRaises(ValueError):
            parse_preferences(b'<map><string name="pairing_code_cipher">x</string></map>')

if __name__=="__main__":unittest.main()
