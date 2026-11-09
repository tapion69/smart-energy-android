#include "../main/gateway_policy.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    assert(se_route_get("/api/v1/health", true) == SE_ROUTE_HEALTH);
    assert(se_route_get("/api/v1/jk/telemetry", true) == SE_ROUTE_JK_TELEMETRY);
    assert(se_route_get("/api/v1/jk/details", true) == SE_ROUTE_JK_DETAILS);
    assert(se_route_get("/api/v1/jk/1/telemetry", true) == SE_ROUTE_JK_TELEMETRY);
    assert(se_route_get("/api/v1/jk/1/details", true) == SE_ROUTE_JK_DETAILS);
    assert(se_route_get("/api/v1/jk/2/telemetry", true) == SE_ROUTE_JK2_TELEMETRY);
    assert(se_route_get("/api/v1/jk/2/details", true) == SE_ROUTE_JK2_DETAILS);
    assert(se_route_get("/api/v1/jk/3/telemetry", true) == SE_ROUTE_JK3_TELEMETRY);
    assert(se_route_get("/api/v1/jk/3/details", true) == SE_ROUTE_JK3_DETAILS);
    assert(se_route_get("/api/v1/jk/2/telemetry", false) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/jk/4/telemetry", true) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/jk/2/bms", true) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/voltronic/status", true) == SE_ROUTE_VOLT_STATUS);
    assert(se_route_get("/api/v1/jk/telemetry", false) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/wifi", true) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/bms", true) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/access", true) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/pair/recover", true) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/voltronic/status?host=evil.com", true) == SE_ROUTE_DENIED);
    assert(se_route_get("/api/v1/jk/../../wifi", true) == SE_ROUTE_DENIED);

    assert(se_is_private_ipv4("192.168.1.100"));
    assert(se_is_private_ipv4("10.10.1.4"));
    assert(se_is_private_ipv4("172.16.0.2"));
    assert(se_is_private_ipv4("172.31.255.254"));
    assert(!se_is_private_ipv4("172.32.0.1"));
    assert(!se_is_private_ipv4("100.64.0.1"));
    assert(!se_is_private_ipv4("8.8.8.8"));
    assert(!se_is_private_ipv4("127.0.0.1"));
    assert(!se_is_private_ipv4("169.254.1.1"));
    assert(!se_is_private_ipv4("192.168.1.100:80"));
    assert(!se_is_private_ipv4("192.168.1.100.bad.test"));
    assert(!se_is_private_ipv4(""));

    assert(se_is_tailnet_ipv4(0x64400001UL));
    assert(se_is_tailnet_ipv4(0x647fffffUL));
    assert(!se_is_tailnet_ipv4(0x64800001UL));
    assert(!se_is_tailnet_ipv4(0x0a000001UL));
    assert(!se_is_tailnet_ipv4(0xc0a80135UL));

    // Regression: ESP-IDF dual-stack listener returns ::ffff:IPv4 AF_INET6.
    uint32_t extracted = 0;
    const uint8_t mapped_vpn[16] = {0,0,0,0,0,0,0,0,0,0,255,255,100,112,119,17};
    const uint8_t mapped_peer[16] = {0,0,0,0,0,0,0,0,0,0,255,255,100,64,118,109};
    const uint8_t mapped_lan[16] = {0,0,0,0,0,0,0,0,0,0,255,255,192,168,1,61};
    const uint8_t native_v6[16] = {0x20,0x01,0x0d,0xb8,0,0,0,0,0,0,0,0,0,0,0,1};
    const uint8_t truncated_prefix[16] = {0,0,0,0,0,0,0,0,0,0,255,0,100,112,119,17};
    assert(se_ipv4_from_mapped_v6_bytes(mapped_vpn, &extracted));
    assert(extracted == 0x64707711UL);
    assert(se_ipv4_from_mapped_v6_bytes(mapped_peer, &extracted));
    assert(se_is_tailnet_ipv4(extracted));
    assert(se_ipv4_from_mapped_v6_bytes(mapped_lan, &extracted));
    assert(!se_is_tailnet_ipv4(extracted));
    assert(!se_ipv4_from_mapped_v6_bytes(native_v6, &extracted));
    assert(!se_ipv4_from_mapped_v6_bytes(truncated_prefix, &extracted));
    assert(!se_ipv4_from_mapped_v6_bytes(NULL, &extracted));
    assert(!se_ipv4_from_mapped_v6_bytes(mapped_vpn, NULL));

    assert(se_is_fixed_read_path("/api/v1/telemetry"));
    assert(se_is_fixed_read_path("/status"));
    assert(!se_is_fixed_read_path("http://evil.com"));
    assert(!se_is_fixed_read_path("/api/v1/../access"));
    assert(!se_is_fixed_read_path("/status?url=evil.com"));
    assert(!se_is_fixed_read_path("/status\r\nX-Admin: true"));

    assert(se_secure_equals("0123456789012345678901234567", "0123456789012345678901234567"));
    assert(!se_secure_equals("0123456789012345678901234567", "0123456789012345678901234568"));
    assert(!se_secure_equals("0123456789012345678901234567", ""));
    assert(!se_secure_equals("short", "short"));
    puts("Smart Energy VPN gateway policy tests OK");
    return 0;
}
