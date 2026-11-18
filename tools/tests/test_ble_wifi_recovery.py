"""Safety/source contracts for Smart Energy's offline BLE Wi-Fi rescue.
Static checks complement ESP-IDF, PlatformIO and Android builds; no hardware claim.
"""
from pathlib import Path

root=Path(__file__).resolve().parents[2]
uuid="b5e70001-3a62-4e58-9d12-4e4f44450001"
for platform in ("vpn-gateway","voltronic-esp32","deye-esp32"):
    folder=root/"firmware"/platform/"main"
    src=(folder/"se_ble_wifi.c").read_text()
    header=(folder/"se_ble_wifi.h").read_text()
    main=(folder/("vpn_provision.c" if platform=="vpn-gateway" else "main.c")).read_text()
    assert "se_ble_wifi_recovery(" in src and "se_ble_wifi_recovery(" in main
    assert "mbedtls_gcm_auth_decrypt" in src
    assert "mbedtls_md_hmac" in src and "smart-energy-wifi-ble-v1" in src
    assert "se_ble_wifi_stage" in src and "se_ble_wifi_override" in src
    assert "se_ble_wifi_promote" in src and "se_ble_wifi_guard_start" in src
    assert "nvs_commit" in src and "se_ble_wifi_discard" in src
    assert "memcmp(" not in src, "Wi-Fi secrets must not be compared in unauthenticated plaintext"
    assert "ble_gatts_add_svcs" in src and "ble_gap_adv_start" in src
    assert "CONFIG_UUID" in src and "STATUS_UUID" in src
    assert "se_ble_wifi_recovery" in header
    cfg=(root/"firmware"/platform/"sdkconfig.defaults").read_text()
    assert "CONFIG_BT_NIMBLE_ENABLED=y" in cfg
    assert platform != "vpn-gateway" or "start_offline_usb_task()" in main
    assert "get_target(route" not in src, "No BLE parameter relay allowed"
jk=(root/"firmware/jk-esp32/src/main.cpp").read_text()
jkble=(root/"firmware/jk-esp32/src/ble_wifi_recovery.h").read_text()
assert uuid in jkble
assert "mbedtls_gcm_auth_decrypt" in jkble and "NimBLEDevice::startAdvertising()" in jkble
assert "heldFor>=6000" in jk
assert "se_ble_wifi_jk::run(prefs,setupCode,hostName)" in jk
assert "bleWiFiPending" in jk and 'prefs.remove("bleSsid")' in jk
assert "jkreader::begin();" in jk, "Do not regress normal BMS BLE"
act=(root/"android/app/src/main/java/fr/smartenergy/android/BleWifiRecoveryActivity.kt").read_text()
assert uuid in act and "HmacSHA256" in act and "AES/GCM/NoPadding" in act
assert "GCMParameterSpec(128,iv)" in act
assert "Manifest.permission.BLUETOOTH_SCAN" in act
assert "Manifest.permission.BLUETOOTH_CONNECT" in act
assert "getCharacteristic(statusUuid)" in act
assert "BleWifiRecoveryActivity::class.java" in (root/"android/app/src/main/java/fr/smartenergy/android/MainActivity.kt").read_text()
manifest=(root/"android/app/src/main/AndroidManifest.xml").read_text()
assert 'android:name=".BleWifiRecoveryActivity" android:exported="false"' in manifest
assert 'android.permission.BLUETOOTH_CONNECT' in manifest
assert 'android.permission.BLUETOOTH_SCAN' in manifest
print("PASS: offline boot-only BLE Wi-Fi recovery contracts across JK/VPN/Voltronic/Deye and Android")
