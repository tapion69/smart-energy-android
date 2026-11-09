#!/usr/bin/env python3
"""Static contract for the single-owner multi-BMS BLE architecture.
No claim of real hardware validation: compile + static only.
"""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
source = (root / "src/jk_reader.cpp").read_text()
board = (root / "platformio.ini").read_text()
assert source.count("xTaskCreate(") == 1, "There must be ONE BLE manager task"
assert 'xTaskCreate(manager,' in source
assert "xTaskCreate(worker" not in source
assert source.count("client->connect(target)") == 1, "No nested concurrent connects"
assert "setConnectRetries(0)" in source, "Manager must own retries"
assert "serviceSession(session)" in source, "Existing streams must be serviced"
assert "session.subscribed=false" in source
assert "NimBLEDevice::deinit(" not in source, "Do not reset active clients"
assert "h2zero/NimBLE-Arduino @ 2.5.2" in board
print("BLE single-owner manager architecture assertions passed")
