"""Safety regression for the S3's dual physical USB provisioning ports.

This source-architecture check complements real ESP-IDF compilation;
it does not prove USB electrical connectivity without a physical S3.
"""
from pathlib import Path

source = (Path(__file__).resolve().parents[1] / "main" / "vpn_provision.c").read_text()
s3 = source.split("#if CONFIG_IDF_TARGET_ESP32S3")
assert len(s3) >= 3, "ESP32-S3 dual-port guards missing"
for name in (
    "GPIO_NUM_43", "GPIO_NUM_44", "uart_param_config",
    "uart_set_pin", "uart_driver_install",
    "usb_serial_jtag_driver_install", "usb_serial_jtag_read_bytes",
    "SE_USB_BRIDGE", "SE_USB_NATIVE", "usb_read_one(port,&byte)",
    'usb_ack(port,"SEVPN_READY_V1")',
    'usb_ack(port,"SEVPN_OK_REBOOT")'
):
    assert name in source, f"Missing USB bridge or native port support: {name}"
assert 'ESP_LOGI(TAG,"USB credentials' not in source
assert 'puts(line_buffer)' not in source
assert 'printf("%s",line_buffer)' not in source
assert 'uart_write_bytes(UART_NUM_0,message,strlen(message))' in source
print("USB provisioning architecture: ESP32 classic + S3 native and CH343 UART0 supported")

# Runtime BOOT maintenance: no fragile RESET/BOOT timing, no write over LAN/VPN.
assert 'RTC_NOINIT_ATTR static uint32_t usb_maintenance_request' in source
assert 'SE_USB_MAINT_MAGIC' in source
assert 'consecutive_low>=35' in source
assert 'consecutive_low>=60' in source # long hold is BLE, short hold remains USB
assert 'usb_maintenance_request=SE_USB_MAINT_MAGIC' in source
assert 'esp_restart()' in source
assert 'esp_reset_reason()==ESP_RST_SW' in source
assert 'usb_maintenance_request=0' in source
assert 'runtime_physical_request?1:physical_reconfigure_requested()' in source
assert 'runtime_ble_request?2:' in source
assert 'se_ble_wifi_recovery(current.gateway_name' in source
assert 'se_ble_wifi_override(current.wifi_ssid' in source
assert 'se_ble_wifi_guard_start()' in source
assert 'se_vpn_start_physical_maintenance_monitor(void)' in source
assert 'usb_maintenance_watch_task' in source
main=(Path(__file__).resolve().parents[1] / "main" / "main.c").read_text()
assert '#define GATEWAY_VERSION "0.3.12-dev-confirmed-writes"' in main
assert '"/api/v1/discovery"' in main
assert 'discovery_inventory(httpd_req_t *req)' in main
assert 'if(!request_is_private_vpn(req))' in main
assert 'if(!authorized(req))' in main
assert 'cJSON_AddBoolToObject(entry,"verified",true)' in main
assert 'se_vpn_start_physical_maintenance_monitor();' in main

# Never trust unsolicited READY as proof of a bidirectional USB link.
assert 'if(strncmp(buffer,"SEVPN_PING ",11)==0)' in source
assert 'usb_nonce_hex(nonce)' in source
assert 'snprintf(reply,sizeof(reply),"SEVPN_PONG %s",nonce)' in source
assert 'SEVPN_ERR_NVS' in source
assert 'persisted.remote_writes_enabled==enabled' in source
assert 'usb_ack_reboot(port)' in source

# USB provisioning must run with enough stack for JSON + 3 validated config
# snapshots, never on ESP-IDF's small default app_main stack.
assert '#define SE_USB_MAINT_TASK_STACK 12288' in source
assert 'xTaskCreate(usb_maintenance_task,"se-vpn-usb",SE_USB_MAINT_TASK_STACK' in source
assert 'start_offline_usb_task()' in source
provision_boot=source.split('const se_vpn_config_t *se_vpn_provision_or_load(void)',1)[1].split('void se_vpn_start_physical_maintenance_monitor(void)',1)[0]
assert 'provisioning_loop();' not in provision_boot
assert provision_boot.count('start_offline_usb_task()') == 2
