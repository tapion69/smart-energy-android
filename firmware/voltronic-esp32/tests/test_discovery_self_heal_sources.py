"""Static regression guard for the Voltronic name-discovery self-heal.

The firmware's existing authenticated UDP envelope, port and UART loop must
not change. No EEPROM/NVS edits or discovery-related credential disclosures.
"""
from pathlib import Path

main=(Path(__file__).resolve().parents[1]/"main/main.c").read_text(encoding="utf-8")
wifi=main[main.index("static void wifi_handler("):main.index("static void wifi_start(")]
discovery=main[main.index("static bool mdns_registration_done=false;"):main.index("void app_main(void)")]
app=main[main.index("void app_main(void)"):]
assert "setup_mdns_discovery();" in wifi
assert "if(mdns_registration_done)return;" in discovery
assert 'mdns_service_add(instance,"_http","_tcp",VT_HTTP_PORT,NULL,0)' in discovery
assert 'ESP_LOGW(TAG,"mDNS service deferred:' in discovery
assert 'mdns_registration_done=true' in discovery
assert "int fd=-1;" in discovery
assert "if(fd<0){" in discovery
assert "close(fd);fd=-1;" in discovery
assert "dst.sin_addr.s_addr=ip.ip.addr | ~ip.netmask.addr;" in discovery
assert "dst.sin_addr.s_addr=htonl(INADDR_BROADCAST);" in discovery
assert "vTaskDelay(pdMS_TO_TICKS(15000));" in discovery
assert 'cJSON_AddStringToObject(j,"name",cfg.display_name);' in discovery
assert 'cJSON_AddStringToObject(j,"family","voltronic");' in discovery
assert 'cJSON_AddNumberToObject(j,"port",VT_HTTP_PORT);' in discovery
assert "mbedtls_md_hmac(sha,(const unsigned char*)cfg.read_code" in discovery
assert 'setup_mdns_discovery();' not in app
assert 'xTaskCreate(polling_task,"voltronic_read",7168,NULL,6,NULL)' in app
assert "start_api();" in app
assert "VT_FAST_CHANNEL_MS 1000U" in main
print("PASS: mDNS registered after GOT_IP, broadcast restarted on failure, signed name-only discovery unchanged")
