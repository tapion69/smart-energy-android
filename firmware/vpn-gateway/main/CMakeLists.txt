# Native USB Serial/JTAG provision on ESP32-S3, bridge UART0 on classic ESP32.
set(SE_VPN_USB_EXTRA "")
if(IDF_TARGET STREQUAL "esp32s3")
  set(SE_VPN_USB_EXTRA esp_driver_usb_serial_jtag)
endif()
idf_component_register(SRCS "main.c" "vpn_provision.c" INCLUDE_DIRS "." REQUIRES
  tailtiny esp_wifi esp_netif esp_event nvs_flash esp_http_server esp_http_client
  freertos lwip driver esp_driver_uart esp_driver_gpio json ${SE_VPN_USB_EXTRA})
