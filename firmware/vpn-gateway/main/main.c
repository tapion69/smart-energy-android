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
#include "esp_timer.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "tailtiny.h"
#include "gateway_policy.h"
#include "gateway_write_policy.h"
#include "vpn_provision.h"
#include "cJSON.h"
#include <math.h>

#define GATEWAY_VERSION "0.3.4-dev-explicit-remote-parameter-writes"
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
    // Compatibility: read_only still describes the default JK telemetry
    // interface. Writable routes are separate and individually gated by USB.
    char body[320]={0};
    snprintf(body,sizeof body,
        "{\"product\":\"smart-energy-vpn-gateway\","
        "\"version\":\"%s\",\"vpn_connected\":true,"
        "\"vpn_only\":true,\"read_only\":true,"
        "\"jk_reader_slots\":3,\"voltronic_reader_slots\":3,"
        "\"remote_parameter_writes_opt_in\":%s}",
        GATEWAY_VERSION,
        personal && personal->remote_writes_enabled?"true":"false");
    httpd_resp_set_type(req,"application/json");
    httpd_resp_set_hdr(req,"Cache-Control","no-store");
    return httpd_resp_sendstr(req,body);
}

typedef struct {
    const char *host;
    const char *path;
    const char *access_code;
    int port;
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
        out->port=80;
        // No dynamic host, URL or credentials ever accepted from VPN callers.
        return se_is_private_ipv4(out->host) &&
               strlen(out->access_code) >= 8;
    }
    if(route == SE_ROUTE_VOLT_STATUS || route == SE_ROUTE_VOLT1_DETAILS ||
       route == SE_ROUTE_VOLT2_TELEMETRY || route == SE_ROUTE_VOLT2_DETAILS ||
       route == SE_ROUTE_VOLT3_TELEMETRY || route == SE_ROUTE_VOLT3_DETAILS) {
        const int slot=(route==SE_ROUTE_VOLT3_TELEMETRY ||
            route==SE_ROUTE_VOLT3_DETAILS)?2:
            (route==SE_ROUTE_VOLT2_TELEMETRY ||
             route==SE_ROUTE_VOLT2_DETAILS)?1:0;
        const bool details=(route==SE_ROUTE_VOLT1_DETAILS ||
            route==SE_ROUTE_VOLT2_DETAILS || route==SE_ROUTE_VOLT3_DETAILS);
        out->host=personal ? personal->voltronic_ip[slot]:"";
        out->access_code=personal ? personal->voltronic_code[slot]:"";
        out->path=details?"/api/v1/details":"/api/v1/telemetry";
        out->port=18081;
        return se_is_private_ipv4(out->host) &&
            strlen(out->access_code)>=8 && strlen(out->access_code)<=32;
    }
    return false;
}

static esp_err_t relay_read(httpd_req_t *req) {
    if (!request_is_private_vpn(req))
        return deny(req, "403 Forbidden", "{\"error\":\"vpn_only\"}");
    if (!authorized(req))
        return deny(req, "401 Unauthorized", "{\"error\":\"invalid_gateway_token\"}");
    se_route_t route = se_route_get(req->uri, req->method == HTTP_GET);
    const int64_t relay_started_us = esp_timer_get_time();
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
        .port = target.port,
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
        ESP_LOGW(TAG, "JK relay upstream connect error: route=%d, reason=%s, elapsed_ms=%lld",
                 (int)route, esp_err_to_name(err),
                 (long long)((esp_timer_get_time() - relay_started_us) / 1000));
        esp_http_client_cleanup(client);
        return deny(req, "502 Bad Gateway", "{\"error\":\"upstream_unreachable\"}");
    }
    const int64_t len = esp_http_client_fetch_headers(client);
    const int code = esp_http_client_get_status_code(client);
    if (len > MAX_JSON_BYTES || code != 200) {
        ESP_LOGW(TAG, "JK relay upstream HTTP error: route=%d, status=%d, len=%lld, elapsed_ms=%lld",
                 (int)route, code, (long long)len,
                 (long long)((esp_timer_get_time() - relay_started_us) / 1000));
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
    const long long elapsed_ms = (long long)((esp_timer_get_time() - relay_started_us) / 1000);
    if (!complete || !transfer_ok)
        ESP_LOGW(TAG, "JK relay incomplete: route=%d bytes=%d complete=%d transport_ok=%d elapsed_ms=%lld",
                 (int)route, total, (int)complete, (int)transfer_ok, elapsed_ms);
    else if (elapsed_ms > 2000)
        ESP_LOGW(TAG, "JK relay slow: route=%d bytes=%d elapsed_ms=%lld",
                 (int)route, total, elapsed_ms);
    return ESP_OK;
}


/* Remote writes are OFF by default. Enabling requires physical USB BOOT
 * provisioning: SEVPN_REMOTE_WRITES_ON. No network setting can enable them.
 * The public GitHub firmware does not contain installation credentials.
 *
 * Fixed endpoint and per-device secret on both hops:
 * Android Tailscale -> token + VPN dst/source gate -> this relay ->
 * fixed NVS LAN host+per-device code -> device's semantic allowlist and
 * explicit SET + fresh readback. No raw serial, BLE opcode, arbitrary IP,
 * arbitrary HTTP path, pairing, NVS changes or OTA can be relayed.
 */
static int64_t last_remote_commit_us[6]={0};

static bool safe_write_payload(se_write_kind_t kind,const char *body) {
    cJSON *json=cJSON_Parse(body);
    if(!cJSON_IsObject(json)) {cJSON_Delete(json);return false;}
    bool allowed=false;
    const cJSON *confirmation=cJSON_GetObjectItemCaseSensitive(json,"confirmation");
    const cJSON *mac=cJSON_GetObjectItemCaseSensitive(json,"mac");
    const bool valid_mac=cJSON_IsString(mac) && se_write_mac(mac->valuestring);
    if(kind==SE_WRITE_JK_REFRESH) {
        allowed=valid_mac;
    }else if(kind==SE_WRITE_JK_ARM) {
        allowed=valid_mac && cJSON_IsString(confirmation) &&
            strcmp(confirmation->valuestring,"I_CONFIRM_ONE_JK_WRITE")==0;
    }else if(kind==SE_WRITE_JK_COMMIT) {
        const cJSON *key=cJSON_GetObjectItemCaseSensitive(json,"key");
        const cJSON *value=cJSON_GetObjectItemCaseSensitive(json,"value");
        const cJSON *old=cJSON_GetObjectItemCaseSensitive(json,"expected_old");
        const cJSON *risk=cJSON_GetObjectItemCaseSensitive(json,"risk_confirmation");
        const bool types=(cJSON_IsBool(value)||cJSON_IsNumber(value)) &&
                         (cJSON_IsBool(old)||cJSON_IsNumber(old));
        bool key_safe=cJSON_IsString(key) && key->valuestring &&
                      strlen(key->valuestring)>0 && strlen(key->valuestring)<64;
        if(key_safe) for(const unsigned char *p=(const unsigned char*)key->valuestring;*p;p++){
            if(!isalnum(*p) && *p!='_'){key_safe=false;break;}
        }
        allowed=valid_mac && types && key_safe &&
            (!cJSON_IsNumber(value)||isfinite(value->valuedouble)) &&
            (!cJSON_IsNumber(old)||isfinite(old->valuedouble)) &&
            cJSON_IsString(confirmation) &&
            strcmp(confirmation->valuestring,"APPLY_TO_SELECTED_BMS")==0 &&
            (!risk || (cJSON_IsString(risk) &&
                strcmp(risk->valuestring,"I_ACCEPT_PROTECTION_RISK")==0));
    }else if(kind==SE_WRITE_VOLTRONIC_PARAM) {
        const cJSON *key=cJSON_GetObjectItemCaseSensitive(json,"parameter");
        const cJSON *old=cJSON_GetObjectItemCaseSensitive(json,"old");
        const cJSON *value=cJSON_GetObjectItemCaseSensitive(json,"value");
        const cJSON *confirmed=cJSON_GetObjectItemCaseSensitive(json,"confirmed");
        allowed=cJSON_IsString(key) && se_write_voltronic_key(key->valuestring) &&
            cJSON_IsNumber(old) && cJSON_IsNumber(value) &&
            isfinite(old->valuedouble) && isfinite(value->valuedouble) &&
            cJSON_IsTrue(confirmed);
    }
    cJSON_Delete(json);
    return allowed;
}

static bool parse_jk_status_mac(httpd_req_t *req,char result[18]) {
    char query[96]={0},mac[64]={0};
    if(httpd_req_get_url_query_str(req,query,sizeof query)!=ESP_OK ||
       httpd_query_key_value(query,"mac",mac,sizeof mac)!=ESP_OK)return false;
    // Android URLEncoder encodes MAC colon as %3A. Decode ONLY that syntax;
    // reject all other encodings, extra query delimiters and path injection.
    size_t output=0;
    for(size_t i=0;mac[i] && output<17;i++) {
        if(mac[i]=='%') {
            if(mac[i+1]!='3' || (mac[i+2]!='A' && mac[i+2]!='a'))
                return false;
            result[output++]=':';i+=2;
        }else result[output++]=mac[i];
    }
    result[output]=0;
    return se_write_mac(result);
}

static esp_err_t relay_parameter_control(httpd_req_t *req) {
    if(!request_is_private_vpn(req))
        return deny(req,"403 Forbidden","{\"error\":\"vpn_only\"}");
    if(!authorized(req))
        return deny(req,"401 Unauthorized","{\"error\":\"invalid_gateway_token\"}");
    if(!personal || !personal->remote_writes_enabled)
        return deny(req,"403 Forbidden","{\"error\":\"remote_writes_disabled_usb_opt_in_required\"}");
    const bool post=req->method==HTTP_POST;
    const se_write_route_t route=se_write_route(req->uri,post);
    if(route.kind==SE_WRITE_DENIED)
        return deny(req,"404 Not Found","{\"error\":\"write_route_not_allowed\"}");
    char body[769]={0};
    int length=0;
    if(post) {
        char confirmed[8]={0};
        if(httpd_req_get_hdr_value_str(req,"X-Remote-Write-Confirmed",
                confirmed,sizeof confirmed)!=ESP_OK ||
           strcmp(confirmed,"yes")!=0)
            return deny(req,"403 Forbidden","{\"error\":\"remote_write_confirmation_required\"}");
        if(req->content_len<2 || req->content_len>768)
            return deny(req,"400 Bad Request","{\"error\":\"request_size_invalid\"}");
        while(length<req->content_len) {
            int got=httpd_req_recv(req,body+length,req->content_len-length);
            if(got<=0) return deny(req,"400 Bad Request","{\"error\":\"incomplete_json\"}");
            length+=got;
        }
        body[length]=0;
        if(!safe_write_payload(route.kind,body))
            return deny(req,"400 Bad Request","{\"error\":\"not_an_approved_parameter_request\"}");
    }
    const int stamp_idx=(route.kind==SE_WRITE_VOLTRONIC_PARAM?3:0)+route.slot-1;
    const bool committed=route.kind==SE_WRITE_JK_COMMIT ||
                         route.kind==SE_WRITE_VOLTRONIC_PARAM;
    const int64_t now=esp_timer_get_time();
    if(committed && last_remote_commit_us[stamp_idx] &&
       now-last_remote_commit_us[stamp_idx]<15000000LL)
        return deny(req,"429 Too Many Requests","{\"error\":\"parameter_write_cooldown\"}");

    lan_target_t target={0};
    se_route_t selection;
    if(route.kind==SE_WRITE_VOLTRONIC_PARAM)
        selection=route.slot==1?SE_ROUTE_VOLT_STATUS:
                  route.slot==2?SE_ROUTE_VOLT2_TELEMETRY:SE_ROUTE_VOLT3_TELEMETRY;
    else
        selection=route.slot==1?SE_ROUTE_JK_TELEMETRY:
                  route.slot==2?SE_ROUTE_JK2_TELEMETRY:SE_ROUTE_JK3_TELEMETRY;
    if(!get_target(selection,&target))
        return deny(req,"503 Service Unavailable","{\"error\":\"source_not_configured\"}");
    char with_query[96]={0};
    if(route.kind==SE_WRITE_JK_STATUS) {
        char mac[18]={0};
        if(!parse_jk_status_mac(req,mac))
            return deny(req,"400 Bad Request","{\"error\":\"invalid_bms_mac\"}");
        snprintf(with_query,sizeof with_query,"%s?mac=%s",route.upstream_path,mac);
        target.path=with_query;
    }else target.path=route.upstream_path;

    esp_http_client_config_t cfg={
        .host=target.host,.port=target.port,.path=target.path,
        .transport_type=HTTP_TRANSPORT_OVER_TCP,
        .timeout_ms=route.kind==SE_WRITE_VOLTRONIC_PARAM?23000:10000,
        .buffer_size=1024,.buffer_size_tx=1024
    };
    esp_http_client_handle_t client=esp_http_client_init(&cfg);
    if(!client)
        return deny(req,"502 Bad Gateway","{\"error\":\"upstream_init_failed\"}");
    esp_http_client_set_method(client,post?HTTP_METHOD_POST:HTTP_METHOD_GET);
    esp_http_client_set_header(client,"X-Setup-Code",target.access_code);
    if(route.kind==SE_WRITE_VOLTRONIC_PARAM)
        esp_http_client_set_header(client,"X-Write-Confirmed","yes");
    if(post)esp_http_client_set_header(client,"Content-Type","application/json");
    // Count an attempted write BEFORE issuing it: a lost response cannot
    // trigger a second automatic write through a different phone request.
    if(committed)last_remote_commit_us[stamp_idx]=now;
    esp_err_t err=esp_http_client_open(client,post?length:0);
    if(err!=ESP_OK) {
        esp_http_client_cleanup(client);
        return deny(req,"502 Bad Gateway","{\"error\":\"upstream_unreachable\"}");
    }
    if(post && esp_http_client_write(client,body,length)!=length) {
        esp_http_client_close(client);esp_http_client_cleanup(client);
        return deny(req,"502 Bad Gateway","{\"error\":\"upstream_write_uncertain\"}");
    }
    (void)esp_http_client_fetch_headers(client);
    const int code=esp_http_client_get_status_code(client);
    char response[2049]={0};
    const int count=esp_http_client_read_response(client,response,sizeof(response)-1);
    esp_http_client_close(client);
    esp_http_client_cleanup(client);
    if(count<=0 || count>=2048 || code<200 || code>=600)
        return deny(req,"502 Bad Gateway","{\"error\":\"upstream_response_unconfirmed\"}");
    response[count]=0;
    // Return only expected JSON documents. Do not trust HTML, bytes or secrets.
    cJSON *verified=cJSON_Parse(response);
    const bool valid=cJSON_IsObject(verified);
    cJSON_Delete(verified);
    if(!valid)
        return deny(req,"502 Bad Gateway","{\"error\":\"upstream_response_not_json\"}");
    if(code==200)httpd_resp_set_status(req,"200 OK");
    else if(code==202)httpd_resp_set_status(req,"202 Accepted");
    else if(code==400)httpd_resp_set_status(req,"400 Bad Request");
    else if(code==401)httpd_resp_set_status(req,"401 Unauthorized");
    else if(code==403)httpd_resp_set_status(req,"403 Forbidden");
    else if(code==404)httpd_resp_set_status(req,"404 Not Found");
    else if(code==409)httpd_resp_set_status(req,"409 Conflict");
    else if(code==429)httpd_resp_set_status(req,"429 Too Many Requests");
    else httpd_resp_set_status(req,"502 Bad Gateway");
    httpd_resp_set_type(req,"application/json");
    httpd_resp_set_hdr(req,"Cache-Control","no-store");
    httpd_resp_set_hdr(req,"X-Content-Type-Options","nosniff");
    return httpd_resp_send(req,response,count);
}

static void start_private_http(void) {
    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.server_port = GATEWAY_PORT;
    cfg.max_uri_handlers = 40;
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
        {.uri="/api/v1/voltronic/1/telemetry",.method=HTTP_GET,.handler=relay_read},
        {.uri="/api/v1/voltronic/1/details",.method=HTTP_GET,.handler=relay_read},
        {.uri="/api/v1/voltronic/2/telemetry",.method=HTTP_GET,.handler=relay_read},
        {.uri="/api/v1/voltronic/2/details",.method=HTTP_GET,.handler=relay_read},
        {.uri="/api/v1/voltronic/3/telemetry",.method=HTTP_GET,.handler=relay_read},
        {.uri="/api/v1/voltronic/3/details",.method=HTTP_GET,.handler=relay_read},
        {.uri="/api/v1/jk/1/settings-refresh",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/1/write-arm",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/1/write",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/1/write-status",.method=HTTP_GET,.handler=relay_parameter_control},
        {.uri="/api/v1/voltronic/1/parameters/change",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/2/settings-refresh",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/2/write-arm",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/2/write",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/2/write-status",.method=HTTP_GET,.handler=relay_parameter_control},
        {.uri="/api/v1/voltronic/2/parameters/change",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/3/settings-refresh",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/3/write-arm",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/3/write",.method=HTTP_POST,.handler=relay_parameter_control},
        {.uri="/api/v1/jk/3/write-status",.method=HTTP_GET,.handler=relay_parameter_control},
        {.uri="/api/v1/voltronic/3/parameters/change",.method=HTTP_POST,.handler=relay_parameter_control},
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
        .max_peers =
#if CONFIG_IDF_TARGET_ESP32S3
            32,
#else
            4,
#endif
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
