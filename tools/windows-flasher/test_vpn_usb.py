import tempfile
import unittest
from pathlib import Path

from unittest.mock import patch
import serial

from vpn_usb import (format_usb_configuration, format_voltronic_patch,
                     check_precompiled_image, provision_over_serial)


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


    def test_reset_during_ping_reopens_com_before_secret_payload(self):
        class FakeSerial:
            def __init__(self):
                self.is_open=False
                self.open_count=0
                self.close_count=0
                self.failed_first_ping=False
                self.ready_was_received=False
                self.pending=[]
                self.payloads=[]
            def open(self):
                self.is_open=True
                self.open_count+=1
            def close(self):
                self.is_open=False
                self.close_count+=1
            def write(self,packet):
                if packet==b"SEVPN_PING\n":
                    if not self.failed_first_ping:
                        self.failed_first_ping=True
                        raise serial.SerialTimeoutException("Port reset mid-ping")
                    self.pending.append(b"SEVPN_READY_V1\n")
                else:
                    if not self.ready_was_received:
                        raise AssertionError("Payload sent before READY")
                    self.payloads.append(packet)
                    self.pending.append(b"SEVPN_OK_REBOOT\n")
                return len(packet)
            def readline(self):
                line=self.pending.pop(0) if self.pending else b""
                if b"SEVPN_READY_V1" in line:self.ready_was_received=True
                return line
            def flush(self):
                return None
        device=FakeSerial()
        logs=[]
        with patch("vpn_usb.serial.Serial",return_value=device), \
             patch("vpn_usb.time.sleep",return_value=None):
            provision_over_serial(
                "COM7",None,logs.append,timeout_seconds=3,
                payload_override=format_voltronic_patch(
                    1,"192.168.1.63","EXAMPLE_CODE_123"))
        self.assertGreaterEqual(device.open_count,2)
        self.assertEqual(len(device.payloads),1)
        self.assertTrue(device.ready_was_received)
        self.assertTrue(any("réouverture automatique" in line for line in logs))
        self.assertFalse(any("EXAMPLE_CODE_123" in line for line in logs))

    def test_payload_write_failure_does_not_auto_retry(self):
        class FakeSerial:
            def __init__(self):
                self.is_open=False
                self.pending=[]
                self.payload_count=0
            def open(self):self.is_open=True
            def close(self):self.is_open=False
            def write(self,packet):
                if packet==b"SEVPN_PING\n":
                    self.pending.append(b"SEVPN_READY_V1\n")
                    return len(packet)
                self.payload_count+=1
                raise serial.SerialTimeoutException("Lost connection after READY")
            def readline(self):
                return self.pending.pop(0) if self.pending else b""
            def flush(self):return None
        device=FakeSerial()
        with patch("vpn_usb.serial.Serial",return_value=device):
            with self.assertRaisesRegex(RuntimeError,"NON CONFIRMÉ"):
                provision_over_serial(
                    "COM7",None,lambda message:None,timeout_seconds=3,
                    payload_override=format_voltronic_patch(
                        1,"192.168.1.63","EXAMPLE_CODE_123"))
        self.assertEqual(device.payload_count,1)

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
