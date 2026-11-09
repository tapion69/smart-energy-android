#!/usr/bin/env python3
"""Regression contracts: never poll protection settings every few seconds."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
reader=(root/"src"/"jk_reader.cpp").read_text(encoding="utf-8")
board=(root/"src"/"main.cpp").read_text(encoding="utf-8")
assert "1800000u" in reader, "periodic settings must be 30 minutes"
assert "settingsRefreshPending" in reader, "manual edit refresh must be queued"
assert "requestSettingsRefresh(const char *mac)" in reader
assert 'sendReadCommand(characteristic,0x96,withResponse);' in reader, "verification read must remain"
assert 'if(refreshSettings || uint32_t(now-session.lastSettingsRequestAt)>=1800000u)' in reader
assert 'uint32_t(now-session.lastSettingsRequestAt)>=45000' not in reader
assert 'sendReadCommand(characteristic,0x96,withResponse);\n    session.lastRefreshAt' not in reader, "silent BLE polling must not spam settings"
assert '/api/v1/jk/settings-refresh' in board
assert "onSettingsRefresh" in board
assert "if(!allowedWriteConnection())" in board
assert 'sendReadCommand(characteristic,0x96,withResponse);' in reader
print("JK settings 30min/on-demand/post-write checks OK")
