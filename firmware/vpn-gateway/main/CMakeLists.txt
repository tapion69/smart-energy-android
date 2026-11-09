idf_component_register(SRCS "main.c" "vpn_provision.c" INCLUDE_DIRS "." REQUIRES
  tailtiny esp_wifi esp_netif esp_event nvs_flash esp_http_server esp_http_client
  freertos lwip driver esp_driver_uart esp_driver_gpio json)
