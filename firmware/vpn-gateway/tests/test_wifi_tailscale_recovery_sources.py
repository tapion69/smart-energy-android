"""VPN Wi-Fi drop recovery: regression checks without requiring an ESP32.

The production firmware must not lose a short disconnect which appears and
disappears between two iterations of the main VPN loop. It may only call
TailTiny rebind after the Wi-Fi interface has obtained IP again. Credentials
in NVS, JK/Voltronic relay routes, and the VPN-only guard are not changed.
"""
from pathlib import Path
s=(Path(__file__).resolve().parents[1]/"main/main.c").read_text(encoding="utf-8")
cm=(Path(__file__).resolve().parents[1]/"main/CMakeLists.txt").read_text(encoding="utf-8")
wifi=s[s.index("static void on_wifi_event("):s.index("static void start_wifi(")]
health=s[s.index("static esp_err_t health_handler("):s.index("static esp_err_t vpn_ota_status_handler(")]
main=s[s.index("void app_main(void)"):]
assert "esp_event esp_timer nvs_flash" in cm
assert "static volatile uint32_t wifi_disconnect_total = 0;" in s
assert "static volatile uint32_t wifi_got_ip_total = 0;" in s
assert "++wifi_disconnect_total;" in wifi
assert "++wifi_got_ip_total;" in wifi
assert "wifi_last_disconnect_reason = 0;" not in wifi, "Do not forget failure on reconnect"
assert "schedule_wifi_retry();" in wifi
disconnect_handler=wifi[wifi.index("WIFI_EVENT_STA_DISCONNECTED"):wifi.index("IP_EVENT_STA_GOT_IP")]
assert "esp_wifi_connect();" not in disconnect_handler, "Do not hammer Wi-Fi from disconnect callback"
assert "esp_timer_create(&retry_args,&wifi_retry_timer)" in s
assert "const uint32_t disconnects=wifi_disconnect_total;" in main
assert "const uint32_t got_ip=wifi_got_ip_total;" in main
assert "disconnects!=handled_disconnects || got_ip!=handled_got_ip" in main
assert "if(!wifi_ready)continue;" in main
assert "tailtiny_rebind(vpn)" in main
assert "handled_disconnects=disconnects;" in main
assert "handled_got_ip=got_ip;" in main
assert "if(rebound==ESP_OK)" in main
assert "esp_restart();" not in main[main.index("se_vpn_ota_confirm_after_tailnet_ready()"):]
for required in (
    '"wifi_disconnect_count"', '"wifi_got_ip_count"',
    '"wifi_last_disconnect_reason"', '"wifi_rssi_dbm"',
    '"vpn_rebind_count"', '"vpn_rebind_failures"'
):
    assert required.replace('"','\\"') in health, f"missing diagnostics {required}"
assert "if (!request_is_private_vpn(req))" in health
assert "X-Gateway-Token" in s, "Do not weaken authentication"
assert "se_vpn_provision_or_load();" in main, "Do not reset NVS on reconnect"
assert "ML_STATE_CONNECTED" in s, "Keep callback semantics"
print("PASS: transient Wi-Fi drops trigger a guarded TailTiny rebind; private diagnostics remain read-only")
