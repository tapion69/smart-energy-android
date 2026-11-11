/*
 * Smart Energy VPN Gateway — first hardware proof of concept.
 * Dedicated classic ESP32; no Bluetooth, no commands to battery/inverter.
 *
 * Transport: Android Tailscale -> TailTiny/WireGuard -> this HTTP server
 * -> fixed RFC1918 LAN IP -> JK or (later) Voltronic ESP.
 *
 * Do NOT expose TCP port 18767 on the internet. No subnet routing/NAT.
 * Universal firmware: personal secrets are provisioned by physical USB into NVS.
 */
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include "sdkconfig.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_mac.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_http_server.h"
#include "esp_http_client.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "tailtiny.h"
#include "gateway_policy.h"
#include "vpn_provision.h"

#define GATEWAY_VERSION "0.3.0-dev-usb-provision"
#define GATEWAY_PORT 18767
#define MAX_JSON_BYTES 32768

static const char *TAG = "SE-VPN";
static tailtiny_t *vpn;
static const se_vpn_config_t *personal = NULL;
static httpd_handle_t http_server = NULL;
static EventGroupHandle_t wifi_events;
static const int WIFI_READY = BIT0;
static volatile bool wifi_ready = false;
static volatile uint16_t wifi_last_disconnect_reason = 0;
static volatile unsigned wifi_retry_count = 0;

/* Reasons are intentionally generic: log no passwords, SSIDs, tokens, MACs,
 * or captured 802.11 frames. Only standard ESP-IDF Wi-Fi reason codes. */
static const char *wifi_failure_hint(uint16_t reason) {
    switch(reason) {
        case WIFI_REASON_NO_AP_FOUND: return "AP_NOT_FOUND";
        case WIFI_REASON_AUTH_FAIL: return "AUTH_REJECTED";
        case WIFI_REASON_ASSOC_FAIL: return "ASSOCIATION_REJECTED";
        case WIFI_REASON_HANDSHAKE_TIMEOUT: return "WPA_HANDSHAKE_TIMEOUT";
        case WIFI_REASON_4WAY_HANDSHAKE_TIMEOUT: return "WPA_4WAY_TIMEOUT";
        default: return "OTHER_WIFI_DISCONNECT";
    }
}

static void on_wifi_event(void *arg, esp_event_base_t base,
                          int32_t id, void *data) {
    (void)arg;
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        const wifi_event_sta_disconnected_t *event =
            (const wifi_event_sta_disconnected_t *)data;
        const uint16_t reason = event ? event->reason : 0;
        wifi_last_disconnect_reason = reason;
        ++wifi_retry_count;
        wifi_ready = false;
        xEventGroupClearBits(wifi_events, WIFI_READY);
        // Throttle the human-readable report, not the reconnect attempt.
        // Diagnostic codes never contain credentials, unlike full WiFi logs.
        if(wifi_retry_count <= 6 || wifi_retry_count % 10 == 0)
            ESP_LOGW(TAG, "WiFi retry %u: reason=%u (%s). No credentials logged.",
                     wifi_retry_count, (unsigned)reason, wifi_failure_hint(reason));
        esp_err_t retry=esp_wifi_connect();
        if(retry!=ESP_OK)
            ESP_LOGW(TAG,"WiFi retry request error=%s",esp_err_to_name(retry));
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        wifi_ready = true;
        wifi_last_disconnect_reason = 0;
        wifi_retry_count = 0;
        xEventGroupSetBits(wifi_events, WIFI_READY);
        ESP_LOGI(TAG, "Home WiFi connected");
    }
}

static void start_wifi(void) {
    wifi_events = xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                &on_wifi_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                                &on_wifi_event, NULL));
    wifi_config_t wifi = {
        .sta = {
            .threshold.authmode = WIFI_AUTH_WPA2_PSK,
        },
    };
    strncpy((char *)wifi.sta.ssid, personal->wifi_ssid,
            sizeof(wifi.sta.ssid) - 1);
    strncpy((char *)wifi.sta.password, personal->wifi_password,
            sizeof(wifi.sta.password) - 1);
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi));
    ESP_ERROR_CHECK(esp_wifi_start());
    // Important for tailnet keepalives and fast response.
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
}

static void on_vpn_state(tailtiny_t *ml, tailtiny_state_t state, void *arg) {
    (void)arg;
    if (state == ML_STATE_CONNECTED) {
        char ip[16];
        tailtiny_ip_to_str(tailtiny_get_vpn_ip(ml), ip);
        ESP_LOGI(TAG, "VPN CONNECTED. Tailnet address %s", ip);
        ESP_LOGI(TAG, "From a tailnet client: http://%s:%u/api/v1/health",
                 ip, GATEWAY_PORT);
    } else if (state == ML_STATE_ERROR) {
        ESP_LOGW(TAG, "VPN encountered an error; no LAN service exposed");
    }
}

// Fail closed if the HTTP socket is not actually addressed to this node's
// TailTiny VPN IP. TailTiny's default HTTP listener otherwise binds INADDR_ANY.
// ESP-IDF's lwIP httpd can return IPv4 connections as AF_INET6 sockets
// with ::ffff:IPv4 addresses. Preserve both original VPN address checks.
static bool socket_ipv4_host(const struct sockaddr_storage *addr,
                             uint32_t *ip) {
    if (!addr || !ip) return false;
    if (addr->ss_family == AF_INET) {
        const struct sockaddr_in *a = (const struct sockaddr_in *)addr;
        *ip = ntohl(a->sin_addr.s_addr);
        return true;
    }
    if (addr->ss_family == AF_INET6) {
        const struct sockaddr_in6 *a = (const struct sockaddr_in6 *)addr;
        return se_ipv4_from_mapped_v6_bytes(a->sin6_addr.s6_addr, ip);
    }
    return false;
}

static bool request_is_private_vpn(httpd_req_t *req) {
    const bool vpn_ready = vpn && tailtiny_is_connected(vpn);
    if (!vpn_ready || !wifi_ready) {
        ESP_LOGW(TAG, "VPN gate: refused state (vpn_ready=%d, wifi_ready=%d)",
                 (int)vpn_ready, (int)wifi_ready);
        return false;
    }
    const int fd = httpd_req_to_sockfd(req);
    // sockaddr_in (16 bytes) is too small for the AF_INET6 socket metadata
    // returned by ESP-IDF's dual-stack HTTP server.
    struct sockaddr_storage local = {0}, peer = {0};
    socklen_t local_len = sizeof(local), peer_len = sizeof(peer);
    if (fd < 0 ||
        getsockname(fd, (struct sockaddr *)&local, &local_len) != 0 ||
        getpeername(fd, (struct sockaddr *)&peer, &peer_len) != 0) {
        ESP_LOGW(TAG, "VPN gate: refused socket metadata unavailable");
        return false;
    }
    uint32_t own = 0, src = 0;
    const bool local_ipv4 = socket_ipv4_host(&local, &own);
    const bool peer_ipv4 = socket_ipv4_host(&peer, &src);
    if (!local_ipv4 || !peer_ipv4) {
        ESP_LOGW(TAG, "VPN gate: refused non-IPv4 and non-mapped socket (local_family=%d, peer_family=%d, dest_mapped=%d, peer_mapped=%d)",
                 (int)local.ss_family, (int)peer.ss_family,
                 (int)local_ipv4, (int)peer_ipv4);
        return false;
    }
    const uint32_t vpn_ip = tailtiny_get_vpn_ip(vpn);
    const bool target_matches = vpn_ip != 0 && own == vpn_ip;
    const bool peer_is_tailnet = se_is_tailnet_ipv4(src);
    if (!target_matches || !peer_is_tailnet) {
        ESP_LOGW(TAG, "VPN gate: refused address checks (dest_is_vpn=%d, src_is_tailnet=%d, dest_is_any=%d, vpn_ip_ready=%d)",
                 (int)target_matches, (int)peer_is_tailnet,
                 (int)(own == 0), (int)(vpn_ip != 0));
        return false;
    }
    return true;
}

static bool authorized(httpd_req_t *req) {
    const char *expected = personal ? personal->gateway_token : "";
    if (strlen(expected) < 20 ||
        strstr(expected, "REPLACE") || strstr(expected, "TOKEN")) return false;
    char supplied[129] = {0};
    if (httpd_req_get_hdr_value_str(req, "X-Gateway-Token",
                                    supplied, sizeof(supplied)) != ESP_OK)
        return false;
    return se_secure_equals(expected, supplied);
}

static esp_err_t deny(httpd_req_t *req, const char *status,
                      const char *json) {
    httpd_resp_set_status(req, status);
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    return httpd_resp_sendstr(req, json);
}

static esp_err_t health_handler(httpd_req_t *req) {
    if (!request_is_private_vpn(req))
        return deny(req, "403 Forbidden", "{\"error\":\"vpn_only\"}");
    // Never return an access code, auth key or private LAN host address.
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    return httpd_resp_sendstr(req,
        "{\"product\":\"smart-energy-vpn-gateway\","
        "\"version\":\"" GATEWAY_VERSION "\","
        "\"vpn_connected\":true,"
        "\"vpn_only\":true,"
        "\"read_only\":true,\"jk_reader_slots\":3}");
}

typedef struct {
    const char *host;
    const char *path;
    const char *access_code;
} lan_target_t;

static bool get_target(se_route_t route, lan_target_t *out) {
    memset(out, 0, sizeof(*out));
    if (route == SE_ROUTE_JK_TELEMETRY || route == SE_ROUTE_JK_DETAILS ||
        route == SE_ROUTE_JK2_TELEMETRY || route == SE_ROUTE_JK2_DETAILS ||
        route == SE_ROUTE_JK3_TELEMETRY || route == SE_ROUTE_JK3_DETAILS) {
        const bool second = route == SE_ROUTE_JK2_TELEMETRY ||
                            route == SE_ROUTE_JK2_DETAILS;
        const bool third = route == SE_ROUTE_JK3_TELEMETRY ||
                           route == SE_ROUTE_JK3_DETAILS;
        const int slot = third ? 2 : second ? 1 : 0;
        out->host = personal ? personal->jk_ip[slot] : "";
        out->access_code = personal ? personal->jk_code[slot] : "";
        const bool details = route == SE_ROUTE_JK_DETAILS ||
                             route == SE_ROUTE_JK2_DETAILS ||
                             route == SE_ROUTE_JK3_DETAILS;
        out->path = details ? "/api/v1/details" : "/api/v1/telemetry";
        // No dynamic host, URL or credentials ever accepted from VPN callers.
        return se_is_private_ipv4(out->host) &&
               strlen(out->access_code) >= 8;
    }
    if (route == SE_ROUTE_VOLT_STATUS) {
        // Fail-closed until the Voltronic protocol and target are verified.
        // The generic USB schema must NOT accept a user-supplied arbitrary URL.
        return false;
    }
    return false;
}

static esp_err_t relay_read(httpd_req_t *req) {
    if (!request_is_private_vpn(req))
        return deny(req, "403 Forbidden", "{\"error\":\"vpn_only\"}");
    if (!authorized(req))
        return deny(req, "401 Unauthorized", "{\"error\":\"invalid_gateway_token\"}");
    se_route_t route = se_route_get(req->uri, req->method == HTTP_GET);
    lan_target_t target;
    if (route == SE_ROUTE_DENIED || route == SE_ROUTE_HEALTH)
        return deny(req, "404 Not Found", "{\"error\":\"not_allowed\"}");
    if (!get_target(route, &target))
        return deny(req, "503 Service Unavailable",
                    "{\"error\":\"source_not_configured\"}");

    // Clients cannot submit URLs, headers, IPs or commands for forwarding.
    // The LAN target and GET path are fixed in this build's local settings.
    esp_http_client_config_t client_cfg = {
        .host = target.host,
        .port = 80,
        .path = target.path,
        .transport_type = HTTP_TRANSPORT_OVER_TCP,
        .timeout_ms = 4500,
        .buffer_size = 1024,
        .buffer_size_tx = 256,
    };
    esp_http_client_handle_t client = esp_http_client_init(&client_cfg);
    if (!client)
        return deny(req, "502 Bad Gateway", "{\"error\":\"upstream_init_failed\"}");
    esp_http_client_set_method(client, HTTP_METHOD_GET);
    if (target.access_code && target.access_code[0])
        esp_http_client_set_header(client, "X-Setup-Code", target.access_code);

    esp_err_t err = esp_http_client_open(client, 0);
    if (err != ESP_OK) {
        esp_http_client_cleanup(client);
        return deny(req, "502 Bad Gateway", "{\"error\":\"upstream_unreachable\"}");
    }
    const int64_t len = esp_http_client_fetch_headers(client);
    const int code = esp_http_client_get_status_code(client);
    if (len > MAX_JSON_BYTES || code != 200) {
        esp_http_client_close(client);
        esp_http_client_cleanup(client);
        return deny(req, "502 Bad Gateway", "{\"error\":\"upstream_http_failure\"}");
    }
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    httpd_resp_set_hdr(req, "X-Content-Type-Options", "nosniff");

    // Stream in 1KB chunks: never load the JK details JSON into ESP32 RAM.
    // This is important because TailTiny needs significant free SRAM on classic
    // ESP32 during tunnel reconnects.
    char buf[1024];
    int total = 0, empty_reads = 0;
    bool complete = false, transfer_ok = true;
    while (total < MAX_JSON_BYTES) {
        int n = esp_http_client_read(client, buf, sizeof(buf));
        if (n > 0) {
            total += n;
            if (total > MAX_JSON_BYTES ||
                httpd_resp_send_chunk(req, buf, n) != ESP_OK) {
                transfer_ok = false;
                break;
            }
            empty_reads = 0;
        } else if (n == 0) {
            if (esp_http_client_is_complete_data_received(client)) {
                complete = true;
                break;
            }
            if (++empty_reads > 4) { transfer_ok = false; break; }
            vTaskDelay(pdMS_TO_TICKS(20));
        } else {
            transfer_ok = false;
            break;
        }
    }
    if (total == MAX_JSON_BYTES &&
        esp_http_client_is_complete_data_received(client)) complete = true;
    esp_http_client_close(client);
    esp_http_client_cleanup(client);
    // Close the chunked response even if the device went offline midway.
    // The client rejects truncated JSON. Never report a partial result as OK.
    httpd_resp_send_chunk(req, NULL, 0);
    if (!complete || !transfer_ok)
        ESP_LOGW(TAG, "Partial upstream response, route=%d, bytes=%d",
                 route, total);
    return ESP_OK;
}

static void start_private_http(void) {
    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.server_port = GATEWAY_PORT;
    cfg.max_uri_handlers = 10;
    cfg.stack_size = 8192;
    cfg.max_open_sockets = 4;
    cfg.lru_purge_enable = true;
    cfg.recv_wait_timeout = 6;
    cfg.send_wait_timeout = 6;
    // Underlying httpd listens on all interfaces; each handler MUST check
    // both the local destination VPN IP and remote tailnet source IP.
    ESP_ERROR_CHECK(httpd_start(&http_server, &cfg));
    const httpd_uri_t uris[] = {
        {.uri="/api/v1/health", .method=HTTP_GET, .handler=health_handler},
        {.uri="/api/v1/jk/telemetry", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/jk/details", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/jk/1/telemetry", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/jk/1/details", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/jk/2/telemetry", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/jk/2/details", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/jk/3/telemetry", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/jk/3/details", .method=HTTP_GET, .handler=relay_read},
        {.uri="/api/v1/voltronic/status", .method=HTTP_GET, .handler=relay_read},
    };
    for (size_t i=0; i<sizeof(uris)/sizeof(uris[0]); ++i)
        ESP_ERROR_CHECK(httpd_register_uri_handler(http_server, &uris[i]));
    ESP_LOGI(TAG, "Private application relay ready on tailnet port %d", GATEWAY_PORT);
}

void app_main(void) {
    esp_err_t err = nvs_flash_init();
    // Never silently erase NVS: it holds the Tailscale identity and
    // user's USB credentials. Abort and require deliberate recovery.
    if(err!=ESP_OK) {
        ESP_LOGE(TAG,"NVS error %s: refusing startup without erase",
                 esp_err_to_name(err));
        return;
    }

    // First boot enters physical UART0 setup (no Wi-Fi / HTTP / Tailscale).
    // Existing devices can re-enter it by holding BOOT for 3 s just after
    // reset. There are no per-installation secrets inside this binary.
    personal=se_vpn_provision_or_load();
    if(!personal) {
        ESP_LOGE(TAG,"USB provisioning incomplete; no network service started");
        return;
    }
    ESP_LOGI(TAG, "Smart Energy VPN Gateway %s (experimental)", GATEWAY_VERSION);
    ESP_LOGI(TAG, "No Bluetooth: separate JK and future Voltronic equipment over WiFi.");
    start_wifi();
    const EventBits_t result = xEventGroupWaitBits(wifi_events, WIFI_READY,
                pdFALSE, pdTRUE, pdMS_TO_TICKS(60000));
    if (!(result & WIFI_READY)) {
        ESP_LOGE(TAG, "WiFi unavailable after 60 s: last_reason=%u (%s), retries=%u. Verify AP security, 2.4 GHz association and saved credentials.",
                 (unsigned)wifi_last_disconnect_reason,
                 wifi_failure_hint(wifi_last_disconnect_reason), wifi_retry_count);
        return;
    }
    uint8_t mac[6] = {0};
    ESP_ERROR_CHECK(esp_read_mac(mac, ESP_MAC_WIFI_STA));
    char gateway_name[40] = {0};
    snprintf(gateway_name, sizeof(gateway_name), "smartenergy-vpn-%02x%02x%02x",
             mac[3], mac[4], mac[5]);

    tailtiny_config_t cfg = {
        .auth_key = personal->tailscale_key,
        .device_name = gateway_name,
        .enable_derp = true,
        .enable_stun = true,
        .enable_disco = true,
        .max_peers = 4,
    };
    vpn = tailtiny_init(&cfg);
    if (!vpn) {
        ESP_LOGE(TAG, "TailTiny initialization failed, no data exposed.");
        return;
    }
    tailtiny_set_state_callback(vpn, on_vpn_state, NULL);
    ESP_ERROR_CHECK(tailtiny_start(vpn));
    for (int seconds=0; seconds<120; ++seconds) {
        if (tailtiny_is_connected(vpn)) break;
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    if (!tailtiny_is_connected(vpn)) {
        ESP_LOGE(TAG, "Tailnet connection timed out. Check auth key and Internet.");
        return;
    }
    start_private_http();
    while (true) {
        if (!wifi_ready) {
            ESP_LOGW(TAG, "WiFi disconnected; waiting for local WiFi recovery");
            xEventGroupWaitBits(wifi_events, WIFI_READY, pdFALSE,
                                pdTRUE, portMAX_DELAY);
            if (vpn) tailtiny_rebind(vpn);
        }
        vTaskDelay(pdMS_TO_TICKS(3000));
    }
}
