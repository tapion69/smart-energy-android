"""Guard against dangling ArduinoJson references to stack-local JK buffers.

In fillDetails()/fillTelemetry(), Device item is a local snapshot, destroyed
before main.cpp's respond() serializes JsonDocument. ArduinoJson 7.4.2 may
store const char array references by address instead of copying them.
Forcing String(...) transfers ownership of these dynamic values into JSON.
These assertions cover every affected field; they do not assert or embed
any actual BMS identity, serial, password, or device MAC.
"""
from pathlib import Path

reader = (Path(__file__).resolve().parents[1] / "src" / "jk_reader.cpp").read_text()
required = {
    'telemetry BLE state': 'device["state"] = String(item.state);',
    'telemetry MAC': 'device["mac"] = String(item.mac);',
    'details MAC': 'd["mac"] = String(item.mac);',
    'identity model': 'info["model"] = String(v.vendor);',
    'identity name': 'info["name"] = String(v.deviceName);',
    'identity hardware': 'info["hardware_version"] = String(v.hardware);',
    'identity software': 'info["software_version"] = String(v.software);',
    'identity manufacturing date': 'info["manufacturing_date"] = String(v.manufactureDate);',
    'identity serial': 'info["serial_number"] = String(v.serialNumber);',
    'identity rejection reason': 'info["last_rejection"] = String(item.lastIdentityFailure);',
}
for label, pattern in required.items():
    assert pattern in reader, f"JSON owned-string regression: {label}"

assert 'info["decoder_revision"] = "jk02-info-json-owned-4";' in reader
print(f"JK JSON owned-buffer source guards passed: {len(required)} paths")
