#pragma once
#include <stdbool.h>
#include <stdint.h>

// Local USB provisioning only. Never expose these to a network endpoint.
// Persisted in the ESP32 NVS partition, NOT compiled into the firmware.
typedef struct {
    char wifi_ssid[33];
    char wifi_password[65];
    char tailscale_key[256];
    char gateway_token[129];
    char jk_ip[3][16];
    char jk_code[3][33];
} se_vpn_config_t;

// Loads complete credentials, else remains in local physical USB provisioning.
// When already configured, GPIO0 (BOOT) held for 3 seconds DURING the initial
// post-boot 9-second window can reopen USB provisioning without an erase.
const se_vpn_config_t *se_vpn_provision_or_load(void);
