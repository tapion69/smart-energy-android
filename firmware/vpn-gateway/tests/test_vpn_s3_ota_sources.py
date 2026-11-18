"""Static safety invariants for VPN OTA (ESP32-S3 only).
The compiler and real USB/Wi-Fi/Tailscale/OTA hardware tests remain mandatory.
"""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/"main"/"main.c").read_text()
ota=(root/"main"/"vpn_ota.c").read_text()
sdk=(root/"sdkconfig.defaults.esp32s3").read_text()
classic=(root/"sdkconfig.defaults").read_text()
parts=(root/"partitions_s3_ota.csv").read_text()
assert "CONFIG_PARTITION_TABLE_CUSTOM=y" in sdk
assert 'CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="partitions_s3_ota.csv"' in sdk
assert "CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y" in sdk
assert "CONFIG_PARTITION_TABLE_SINGLE_APP_LARGE=y" in classic, "Classic VPN must remain safe USB-only"
assert "CONFIG_PARTITION_TABLE_CUSTOM=y" not in classic
for item in (
    "nvs,data,nvs,0x9000,0x6000,",
    "factory,app,factory,0x10000,0x200000,",
    "ota_0,app,ota_0,0x210000,0x200000,",
    "ota_1,app,ota_1,0x410000,0x200000,",
    "otadata,data,ota,0x610000,0x2000,",
):assert item in parts,item
assert "#if CONFIG_IDF_TARGET_ESP32S3" in ota
assert "esp_ota_get_next_update_partition(NULL)" in ota
assert "esp_ota_begin" in ota and "esp_ota_write" in ota
assert "esp_ota_end" in ota and "esp_ota_set_boot_partition" in ota
assert "esp_ota_mark_app_valid_cancel_rollback" in ota
assert "esp_ota_mark_app_invalid_rollback_and_reboot" in ota
assert "mbedtls_sha256_update" in ota and "checksum_ok" in ota
assert "esp_crt_bundle_attach" in ota
assert "RELEASE_PREFIX" in ota and "approved_release(" in ota
assert '"/SmartEnergy-VPN-S3-OTA.bin"' in ota
assert "se_vpn_ota_start(" in source
assert "vpn_ota_request_handler" in source
assert "X-OTA-Confirmed" in source and "X-Gateway-Token" in source
assert source.index("request_is_private_vpn(req)",source.index("static esp_err_t vpn_ota_request_handler"))<source.index("se_vpn_ota_start(GATEWAY_VERSION)")
assert source.index("authorized(req)",source.index("static esp_err_t vpn_ota_request_handler"))<source.index("se_vpn_ota_start(GATEWAY_VERSION)")
assert "se_vpn_ota_confirm_after_tailnet_ready();" in source
assert "se_vpn_ota_failed_start_rollback();" in source
assert "URL(" not in ota, "No unrestricted user-supplied firmware URL"
print("PASS VPN S3 OTA: auth, strict manifest, partition slots, SHA256, rollback and classic-only USB")
