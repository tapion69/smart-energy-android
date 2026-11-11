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
