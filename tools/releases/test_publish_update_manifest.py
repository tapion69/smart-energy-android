import importlib.util
import pathlib
import tempfile
import unittest
import os

path=pathlib.Path(__file__).with_name("publish_update_manifest.py")
spec=importlib.util.spec_from_file_location("publish_update_manifest",path)
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class TestReleaseManifest(unittest.TestCase):
    def test_android_official_url_and_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=pathlib.Path(tmp)/mod.AND_NAME
            target.write_bytes(b"a"*21000)
            out,payload=mod.manifest("android-v0.3.35",target,100000,"0.3.35",50)
            self.assertEqual(out,"updates/android.json")
            self.assertTrue(payload["downloadUrl"].endswith("SmartEnergy-Android.apk"))
            self.assertEqual(len(payload["sha256"]),64)
    def test_reject_tag_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=pathlib.Path(tmp)/mod.AND_NAME
            target.write_bytes(b"a"*21000)
            with self.assertRaises(ValueError):
                mod.manifest("android-v0.3.35",target,100000,"0.3.36",50)
    def test_require_esp32s3_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=pathlib.Path(tmp)/mod.FW_NAME
            data=bytearray(25000);data[0]=0xe9;data[12:14]=(9).to_bytes(2,"little")
            target.write_bytes(data)
            out,payload=mod.manifest("voltronic-v0.3.3",target,200000,"0.3.3")
            self.assertEqual(out,"updates/voltronic-esp32.json")
            self.assertEqual(payload["hardwareTargets"],["esp32s3-n16r8"])
            data[12:14]=(0).to_bytes(2,"little");target.write_bytes(data)
            with self.assertRaises(ValueError):
                mod.manifest("voltronic-v0.3.3",target,200000,"0.3.3")
    def test_vpn_s3_ota_release_target_and_exact_image_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            asset=pathlib.Path(tmp)/mod.VPN_FW_NAME
            image=bytearray(26000)
            image[0]=0xe9
            image[12:14]=(9).to_bytes(2,"little")
            asset.write_bytes(image)
            target,manifest=mod.manifest("vpn-gateway-v0.3.14",
                asset,2*1024*1024,"0.3.14")
            self.assertEqual(target,"updates/vpn-gateway.json")
            self.assertEqual(manifest["target"],"esp32s3-n16r8")
            self.assertEqual(manifest["product"],"smart-energy-vpn-gateway")
            self.assertTrue(manifest["downloadUrl"].endswith(mod.VPN_FW_NAME))
            with self.assertRaises(ValueError):
                mod.manifest("vpn-gateway-v0.3.14",
                    asset,2*1024*1024,"0.3.15")
            image[12:14]=(0).to_bytes(2,"little")
            asset.write_bytes(image)
            with self.assertRaises(ValueError):
                mod.manifest("vpn-gateway-v0.3.14",
                    asset,2*1024*1024,"0.3.14")

    def test_no_asset_replacement(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=pathlib.Path(tmp)/"unknown.apk";target.write_bytes(b"x"*21000)
            with self.assertRaises(ValueError):
                mod.manifest("android-v0.3.35",target,100000,"0.3.35",50)

if __name__=="__main__":unittest.main()
