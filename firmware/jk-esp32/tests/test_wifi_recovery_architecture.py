"""Regression guards for JK Wi-Fi STA recovery after a temporary outage.

Tests source policy without RF hardware. A connected reader must never be
stranded for two minutes in provisioning AP by a transient Wi-Fi loss.
"""
from pathlib import Path

source = (Path(__file__).resolve().parents[1] / "src" / "main.cpp").read_text(encoding="utf-8")

assert "bool hasJoinedHomeWifi = false;" in source
assert "hasJoinedHomeWifi = true;" in source
assert 'doc["wifi_rssi_dbm"]' in source
assert 'doc["wifi_joined_this_boot"]' in source
assert "if (hasJoinedHomeWifi)" in source
assert "WiFi.reconnect();" in source
assert 'wifiIssue = "home_wifi_retrying";' in source

# The former 120-second AP fallback remains for initial provisioning only.
initial_only = source.split('if (hasJoinedHomeWifi)', 1)[1].split('startSetup(true);', 1)[0]
assert 'wifiIssue = "join_timeout";' in initial_only
assert 'startSetup(true);' in source
assert 'WiFi.softAP(' in source
assert 'heldFor>=SETUP_HOLD_MS' in source

print("JK Wi-Fi recovery regression guards passed")
