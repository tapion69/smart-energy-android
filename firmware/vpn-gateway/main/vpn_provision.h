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
    char gateway_name[33]; // Optional Tailscale DNS-friendly name set through physical USB.
    char jk_ip[3][16];
    char jk_name[3][33]; // Optional name-based discovery for preflashed readers.
    char jk_code[3][33];
    char voltronic_ip[3][16];
    char voltronic_name[3][33];
    char voltronic_code[3][33];
    bool remote_writes_enabled; // opt-in only through physical USB + BOOT, default false
} se_vpn_config_t;

// Loads complete credentials, else remains in local physical USB provisioning.
// An initial 3-second hold on BOOT in the first 9 seconds still works.
// S3 maintenance monitor additionally supports BOOT hold then release while
// the gateway is running. Never erases NVS, never opens a network write toggle.
const se_vpn_config_t *se_vpn_provision_or_load(void);

// S3 only: starts a background, physical GPIO0 hold/release detector.
// A physical 3.5s hold then release requests ONE offline maintenance boot.
void se_vpn_start_physical_maintenance_monitor(void);
