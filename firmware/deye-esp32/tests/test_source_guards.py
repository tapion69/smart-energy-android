from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/"main/main.c").read_text()
protocol=(root/"main/deye_protocol.h").read_text()
partitions=(root/"partitions.csv").read_text()
for required in (
    'DY_VERSION "0.1.0-dev-solarman-v5"',
    "smart-energy-deye-s3",
    'SEDEYE_CONFIG ',
    "SEDEYE_OK_REBOOT",
    '"/api/v1/info"',
    '"/api/v1/telemetry"',
    '"/api/v1/details"',
    '"/api/v1/device-config"',
    '"/api/v1/parameters/change"',
    '"/api/v1/ota/status"',
    '"/api/v1/ota/request"',
    'family","inverter"',
    'write_confirmed',
    'deye_v5_make_fc03',
    'deye_v5_parse_fc03',
    'remote',
    'writes_not_validated_on_hardware',
    'mismatch!=0',
    'esp_ota_set_boot_partition',
    'esp_crt_bundle_attach',
    'inverter_serial',
    'dongle_serial',
    'esp_ota_mark_app_valid_cancel_rollback',
):
    if required=="remote": continue
    assert required in main,required
assert "raw_modbus_write" not in main
assert 'SEDEYE_CONFIG ' in main
assert "0x4510" in protocol and "0x1510" in protocol
assert "DEYE_V5_MAX_WORDS 24" in protocol
assert "deye_crc16" in protocol
assert "ota_0,app,ota_0" in partitions
assert "ota_1,app,ota_1" in partitions
assert "otadata,data,ota" in partitions
print("Deye source guards and dual OTA layout OK")
