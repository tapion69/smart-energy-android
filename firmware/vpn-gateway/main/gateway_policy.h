#pragma once
// Pure C policy for Smart Energy VPN Gateway. Tested on Linux/Windows host.
// The gateway is NOT an IP router or an arbitrary URL/TCP forwarder.
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <ctype.h>
#include <stdio.h>
#include <string.h>

typedef enum {
    SE_ROUTE_DENIED = 0,
    SE_ROUTE_HEALTH,
    SE_ROUTE_JK_TELEMETRY,
    SE_ROUTE_JK_DETAILS,
    SE_ROUTE_VOLT_STATUS
} se_route_t;

static inline se_route_t se_route_get(const char *path, bool is_get) {
    if (!is_get || !path) return SE_ROUTE_DENIED;
    if (!strcmp(path, "/api/v1/health")) return SE_ROUTE_HEALTH;
    if (!strcmp(path, "/api/v1/jk/telemetry")) return SE_ROUTE_JK_TELEMETRY;
    if (!strcmp(path, "/api/v1/jk/details")) return SE_ROUTE_JK_DETAILS;
    if (!strcmp(path, "/api/v1/voltronic/status")) return SE_ROUTE_VOLT_STATUS;
    return SE_ROUTE_DENIED;
}

static inline bool se_is_private_ipv4(const char *ip) {
    if (!ip || strlen(ip) > 15 || strlen(ip) < 7) return false;
    unsigned a=256, b=256, c=256, d=256;
    char trailing = 0;
    if (sscanf(ip, "%u.%u.%u.%u%c", &a, &b, &c, &d, &trailing) != 4 ||
        a > 255 || b > 255 || c > 255 || d > 255) return false;
    return a == 10 ||
          (a == 172 && b >= 16 && b <= 31) ||
          (a == 192 && b == 168);
}

// Paths are configured by the local administrator, never supplied by VPN clients.
static inline bool se_is_fixed_read_path(const char *path) {
    if (!path || path[0] != '/' || strlen(path) > 80 ||
        strstr(path, "..") != NULL) return false;
    for (const unsigned char *p = (const unsigned char *)path; *p; ++p) {
        if (!(isalnum(*p) || *p == '/' || *p == '_' || *p == '-' || *p == '.'))
            return false;
    }
    return true;
}

static inline bool se_is_tailnet_ipv4(uint32_t ip_host_order) {
    // 100.64.0.0/10 Tailscale CGNAT addresses
    return (ip_host_order & 0xffc00000UL) == 0x64400000UL;
}

// lwIP/ESP-IDF may return IPv4 peers as AF_INET6 IPv4-mapped sockets
// (::ffff:a.b.c.d), even when the actual WireGuard tailnet is IPv4.
// Strictly reject native IPv6: the gateway currently has no VPN IPv6 identity.
static inline bool se_ipv4_from_mapped_v6_bytes(const uint8_t addr[16],
                                                 uint32_t *host_ip) {
    if (!addr || !host_ip) return false;
    for (size_t i = 0; i < 10; ++i) {
        if (addr[i] != 0) return false;
    }
    if (addr[10] != 0xff || addr[11] != 0xff) return false;
    *host_ip = ((uint32_t)addr[12] << 24) |
               ((uint32_t)addr[13] << 16) |
               ((uint32_t)addr[14] << 8) |
               (uint32_t)addr[15];
    return true;
}

static inline bool se_secure_equals(const char *left, const char *right) {
    if (!left || !right) return false;
    // Fixed upper bound avoids unbounded work on a malicious HTTP header.
    const size_t a = strnlen(left, 129);
    const size_t b = strnlen(right, 129);
    if (a > 128 || b > 128 || a != b || a < 20) return false;
    unsigned char difference = 0;
    for (size_t i = 0; i < a; ++i)
        difference |= (unsigned char)left[i] ^ (unsigned char)right[i];
    return difference == 0;
}
