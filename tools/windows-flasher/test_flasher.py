import unittest
import io
import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

from smart_energy_flasher import sdkconfig_contents, valid_lan_ip, valid_serial_port, validate_vpn_config, selected_serial_port, serial_port_label, ssid_looks_five_ghz_only, ensure_esptool_console_streams, Flasher, FlashProgressWriter, monitor_esptool_progress


def config():
    return {
        "wifi_ssid": "MaBox24", "wifi_password": "secretfort",
        "tailscale_key": "tskey-auth-example", "gateway_token": "A" * 25,
        "jk1_ip": "192.168.1.53", "jk1_code": "readerSecret1",
        "jk2_ip": "10.0.0.22", "jk2_code": "readerSecret2",
        "jk3_ip": "", "jk3_code": "",
        "volt1_ip": "", "volt1_code": "",
        "volt2_ip": "", "volt2_code": "",
        "volt3_ip": "", "volt3_code": "",
    }


class FlasherTests(unittest.TestCase):
    def test_warn_for_probable_five_ghz_only_ssid_without_rejecting_2_4(self):
        self.assertTrue(ssid_looks_five_ghz_only("Livebox-6B6_5GHz"))
        self.assertTrue(ssid_looks_five_ghz_only("routeur-5ghz"))
        self.assertFalse(ssid_looks_five_ghz_only("Livebox-6B6"))
        self.assertFalse(ssid_looks_five_ghz_only("maison_2.4GHz"))
        self.assertFalse(ssid_looks_five_ghz_only("wifi-5G-mobile"))

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

    def test_voltronic_only_and_reject_unpaired(self):
        c=config()
        for i in range(1,4):
            c[f"jk{i}_ip"]="";c[f"jk{i}_code"]=""
        c["volt1_ip"]="192.168.1.63"
        c["volt1_code"]="voltronicABC"
        validate_vpn_config(c)
        c["volt1_code"]=""
        with self.assertRaises(ValueError):
            validate_vpn_config(c)
    def test_voltronic_no_open_relay_or_public_ip(self):
        c=config()
        c["volt3_ip"]="8.8.8.8";c["volt3_code"]="secretABC"
        with self.assertRaises(ValueError):validate_vpn_config(c)
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
                     "COM5 — device\nrm"):
            with self.subTest(item=item):
                with self.assertRaises(ValueError):
                    selected_serial_port(item)


    def test_windowed_esptool_streams_never_none(self):
        # PyInstaller --windowed sets stdout/stderr to None on Windows.
        with patch.object(sys,"stdout",None), patch.object(sys,"stderr",None):
            ensure_esptool_console_streams()
            try:
                self.assertIsNotNone(sys.stdout)
                self.assertIsNotNone(sys.stderr)
                sys.stdout.write("test")
                sys.stdout.flush()
                sys.stderr.flush()
            finally:
                sys.stdout.close()
                sys.stderr.close()

    def test_esptool_real_percent_carriage_returns_and_chunks(self):
        values=[]
        stream=io.StringIO()
        observer=FlashProgressWriter(stream,values.append)
        observer.write("Compressed 100 to 40 bytes\n")
        observer.write("Writing at 0x00000000... (3")
        observer.write("1 %)\r")
        observer.flush()
        observer.write("Writing at 0x00010000... (100 %)\r")
        observer.write("Hash of data verified.\n")
        self.assertEqual(values,[31,100])
        self.assertIn("Writing at",stream.getvalue())

    def test_progress_observer_ignores_unrelated_percent(self):
        values=[]
        observer=FlashProgressWriter(io.StringIO(),values.append)
        observer.write("Wi-Fi signal 99 %\n")
        observer.write("Writing at 0x00010000... (20 %)\r")
        observer.write("Writing at 0x00010000... (10 %)\r")
        self.assertEqual(values,[20])

    def test_progress_observer_restores_stdout_on_failure(self):
        values=[]
        original=sys.stdout
        with patch.object(sys,"stdout",io.StringIO()):
            local=sys.stdout
            with self.assertRaises(RuntimeError):
                with monitor_esptool_progress(values.append):
                    print("Writing at 0x00010000... (45 %)")
                    raise RuntimeError("synthetic flash failure")
            self.assertIs(sys.stdout,local)
        self.assertIs(sys.stdout,original)
        self.assertEqual(values,[45])

    def test_bundled_jk_factory_and_app_selected_by_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/"embedded_firmware"
            folder.mkdir()
            (folder/"smart-jk-esp32-factory.bin").write_bytes(b"firmware_factory")
            (folder/"smart-jk-esp32-app-only.bin").write_bytes(b"firmware_update")
            with patch.object(sys,"_MEIPASS",directory,create=True):
                self.assertEqual(Flasher._bundled_jk_image("factory").name,
                                 "smart-jk-esp32-factory.bin")
                self.assertEqual(Flasher._bundled_jk_image("update").name,
                                 "smart-jk-esp32-app-only.bin")
                with self.assertRaises(ValueError):
                    Flasher._bundled_jk_image("unknown")
            with patch.object(sys,"_MEIPASS",str(Path(directory)/"missing"),create=True):
                with self.assertRaises(ValueError):
                    Flasher._bundled_jk_image("factory")

    def test_bundled_vpn_factory_and_app_selected_by_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/"embedded_firmware"
            folder.mkdir()
            for filename in ("smart-energy-vpn-USB-FACTORY-ESP32-0x0.bin",
                             "smart-energy-vpn-USB-APP-ESP32-0x10000.bin"):
                (folder/filename).write_bytes(b"test")
            with patch.object(sys,"_MEIPASS",directory,create=True):
                self.assertIn("FACTORY",Flasher._bundled_vpn_image("factory").name)
                self.assertIn("APP",Flasher._bundled_vpn_image("update").name)
                with self.assertRaises(ValueError):
                    Flasher._bundled_vpn_image("unknown")


    def test_bundled_voltronic_s3_requires_correct_image(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/"embedded_firmware"
            folder.mkdir()
            (folder/"smart-energy-voltronic-S3-FACTORY-0x0.bin").write_bytes(b"factory")
            (folder/"smart-energy-voltronic-S3-APP-0x10000.bin").write_bytes(b"application")
            with patch.object(sys,"_MEIPASS",directory,create=True):
                self.assertIn("FACTORY",
                    Flasher._bundled_voltronic_image("factory").name)
                self.assertIn("APP",
                    Flasher._bundled_voltronic_image("update").name)
                with self.assertRaises(ValueError):
                    Flasher._bundled_voltronic_image("unknown")

    def test_voltronic_app_only_preserves_nvs_without_usb_reprovision(self):
        # S3 already has working Wi-Fi and read code: never overwrite either.
        f = Flasher.__new__(Flasher)
        with (
            patch("smart_energy_flasher.ensure_esptool_console_streams"),
            patch("smart_energy_flasher.monitor_esptool_progress"),
            patch("vpn_usb.flash_precompiled") as flash,
            patch("voltronic_usb.provision_voltronic") as provision,
            patch.object(Flasher,"_progress_stage"),
            patch.object(Flasher,"_progress_step") as stage,
            patch.object(Flasher,"_log"),
        ):
            f._do_voltronic("COM11",Path("voltronic.bin"),"update",None)
            flash.assert_called_once()
            provision.assert_not_called()
            self.assertTrue(any("NVS conservée" in str(c)
                                for c in stage.call_args_list))

    def test_voltronic_factory_requires_usb_values(self):
        f=Flasher.__new__(Flasher)
        with (
            patch("smart_energy_flasher.ensure_esptool_console_streams"),
            patch("smart_energy_flasher.monitor_esptool_progress"),
            patch("vpn_usb.flash_precompiled"),
            patch("voltronic_usb.provision_voltronic") as provision,
            patch.object(Flasher,"_progress_stage"),
            patch.object(Flasher,"_progress_step"),
        ):
            with self.assertRaises(ValueError):
                f._do_voltronic("COM11",Path("voltronic.bin"),"factory",None)
            provision.assert_not_called()

    def test_vpn_app_only_preserves_nvs_without_usb_reprovision(self):
        # Updating an already-associated gateway must not require a used
        # one-time Tailscale auth key or overwrite JK/Wi-Fi credentials.
        f = Flasher.__new__(Flasher)
        with (
            patch("smart_energy_flasher.ensure_esptool_console_streams"),
            patch("smart_energy_flasher.monitor_esptool_progress"),
            patch("vpn_usb.flash_precompiled") as flash,
            patch("vpn_usb.provision_over_serial") as provision,
            patch.object(Flasher, "_progress_stage"),
            patch.object(Flasher, "_progress_step") as stage,
            patch.object(Flasher, "_log"),
        ):
            f._do_vpn("COM11", Path("test.bin"), "update", None, "esp32s3")
            flash.assert_called_once()
            provision.assert_not_called()
            self.assertTrue(any("NVS conservée" in str(x)
                                for x in stage.call_args_list))

    def test_flasher_source_vpn_buttons_do_not_overlap(self):
        from inspect import getsource
        source=getsource(Flasher._build_vpn)
        flash=source.index("command=self._flash_vpn).grid(")
        add=source.index("command=self._add_voltronic_only).grid(")
        configure=source.index("command=self._configure_only).grid(")
        self.assertIn("row=8,",source[flash:flash+90])
        self.assertIn("row=9,",source[source.index("append.grid("):source.index("append.grid(")+130])
        self.assertIn("row=1,",source[add:add+90])
        self.assertIn("row=10,",source[configure:configure+100])


if __name__ == "__main__":
    unittest.main()
