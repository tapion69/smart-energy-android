#pragma once
#include <stdbool.h>
#include <stddef.h>

/* VPN S3 OTA: authenticated small tailnet command; device downloads the image
 * over its own Wi-Fi. No arbitrary URLs or large uploads through TailTiny.
 * Classic ESP32 without dual OTA remains read-only and USB-upgrade-only.
 */
void se_vpn_ota_status_json(char *buffer,size_t capacity,const char *version);
bool se_vpn_ota_start(const char *current_version);
void se_vpn_ota_confirm_after_tailnet_ready(void);
void se_vpn_ota_failed_start_rollback(void);
