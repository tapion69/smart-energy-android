#pragma once
#include <stdbool.h>
#include <stddef.h>

/* Physically opened BLE recovery, never exposed through HTTP/Tailscale.
 * UUIDs: b5e70001/2/3-3a62-4e58-9d12-4e4f44450001.
 * APK authenticates encrypted Wi-Fi payload with each device's existing secret.
 */
void se_ble_wifi_recovery(const char *device_name, const char *device_secret);
bool se_ble_wifi_stage(const char *ssid, const char *password);
bool se_ble_wifi_override(char *ssid, size_t ssid_len, char *password, size_t password_len);
bool se_ble_wifi_promote(const char *config_namespace, const char *config_key);
void se_ble_wifi_discard(void);
void se_ble_wifi_guard_start(void);
