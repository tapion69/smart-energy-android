idf_component_register(SRCS "main.c" "se_ble_wifi.c" INCLUDE_DIRS "." REQUIRES
  esp_wifi esp_netif esp_event esp_http_server esp_http_client
  nvs_flash json freertos esp_timer driver esp_driver_uart esp_driver_gpio
  esp_driver_usb_serial_jtag app_update mbedtls esp-tls bt)
