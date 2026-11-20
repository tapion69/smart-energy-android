"""No-device-needed tests of the read-only Voltronic discovery capture."""
from __future__ import annotations

import json
import pathlib
import unittest

from voltronic_discovery_diagnostic import parse_beacon, parse_mdns_name


def beacon(**changes):
    source = {
        "proto": "smart-energy-discovery-v1",
        "family": "voltronic",
        "name": "Onduleur 1",
        "id": "se-voltronic-123456abcdef",
        "ip": "192.168.1.42",
        "port": 18081,
        "auth": "a" * 64,
    }
    source.update(changes)
    return json.dumps(source).encode("utf-8")


class DiscoveryDiagnosticsTests(unittest.TestCase):
    def test_expected_beacon(self):
        self.assertEqual(
            parse_beacon(beacon(), "192.168.1.42"),
            ("voltronic", "Onduleur 1", "se-voltronic-123456abcdef"),
        )

    def test_bad_sender_or_source_or_auth(self):
        for packet, sender in (
            (beacon(), "192.168.1.43"),
            (beacon(), "8.8.8.8"),
            (beacon(auth="0" * 62), "192.168.1.42"),
            (beacon(port=8080), "192.168.1.42"),
            (beacon(name="Bogus|Name"), "192.168.1.42"),
            (beacon(proto="bad"), "192.168.1.42"),
            (b"not json", "192.168.1.42"),
        ):
            with self.subTest(sender=sender, packet=packet[:50]):
                self.assertIsNone(parse_beacon(packet, sender))

    def test_mdns_labels(self):
        self.assertEqual(
            parse_mdns_name("5.6 (sevolt-123abc)._http._tcp.local.", {}),
            ("voltronic", "5.6"),
        )
        self.assertEqual(
            parse_mdns_name("ESP1 (sevolt-123abc)._http._tcp.local.", {}),
            ("voltronic", "ESP1"),
        )
        self.assertIsNone(parse_mdns_name("Other._http._tcp.local.", {}))

    def test_listen_only_no_flash_or_network_writes(self):
        src = (pathlib.Path(__file__).parent /
               "voltronic_discovery_diagnostic.py").read_text(encoding="utf-8")
        assert "socket.SOCK_DGRAM" in src
        assert "sock.recvfrom(1024)" in src
        assert "ServiceBrowser(" in src
        for forbidden in ("SEVPN_CONFIG", "sendto(", "requests.post(",
                          "flash_precompiled", "X-Setup-Code", "gateway_token"):
            self.assertNotIn(forbidden, src)


if __name__ == "__main__":
    unittest.main()
