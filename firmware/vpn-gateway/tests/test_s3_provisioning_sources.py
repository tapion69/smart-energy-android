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
assert 'if(armed)' in source
assert 'usb_maintenance_request=SE_USB_MAINT_MAGIC' in source
assert 'esp_restart()' in source
assert 'esp_reset_reason()==ESP_RST_SW' in source
assert 'usb_maintenance_request=0' in source
assert 'runtime_physical_request || physical_reconfigure_requested()' in source
assert 'se_vpn_start_physical_maintenance_monitor(void)' in source
assert 'usb_maintenance_watch_task' in source
main=(Path(__file__).resolve().parents[1] / "main" / "main.c").read_text()
assert '#define GATEWAY_VERSION "0.3.5-dev-boot-anytime-usb"' in main
assert 'se_vpn_start_physical_maintenance_monitor();' in main
