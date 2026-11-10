#include "vpn_provision.h"
#include "gateway_policy.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "cJSON.h"
#include "driver/uart.h"
#if CONFIG_IDF_TARGET_ESP32S3
#include "driver/usb_serial_jtag.h"
#endif
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_system.h"
#include "nvs.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// Each device has its own NVS record. The firmware contains NO user tokens.
// WARNING: initial prototype does NOT yet enable ESP32 flash encryption.
// Treat lost/stolen physical ESP32 boards as secret exposure.
#define CFG_NAMESPACE "se_vpn_cfg"
#define CFG_KEY "json"
#define LINE_MAX 1600
static const char *TAG="SE-VPN-USB";
static se_vpn_config_t current;
static char line_buffer[LINE_MAX];

/* Classic ESP32 uses bridge UART0; ESP32-S3 uses native USB Serial/JTAG CDC.
 * Never echo JSON, keys or user secret values to either USB console. */
static void usb_ack(const char *message) {
#if CONFIG_IDF_TARGET_ESP32S3
    usb_serial_jtag_write_bytes(message, strlen(message), pdMS_TO_TICKS(1000));
    usb_serial_jtag_write_bytes("\n", 1, pdMS_TO_TICKS(1000));
#else
    puts(message);
    fflush(stdout);
#endif
}
static int usb_read_one(uint8_t *byte) {
#if CONFIG_IDF_TARGET_ESP32S3
    return usb_serial_jtag_read_bytes(byte,1,pdMS_TO_TICKS(200));
#else
    return uart_read_bytes(UART_NUM_0,byte,1,pdMS_TO_TICKS(200));
#endif
}


static bool text_field(const cJSON *obj,const char *name,
                       char *dst,size_t size,bool required,size_t min_len) {
    const cJSON *item=cJSON_GetObjectItemCaseSensitive(obj,name);
    if(!cJSON_IsString(item) || !item->valuestring) return !required;
    size_t len=strnlen(item->valuestring,size);
    if(len>=size || (required && len<min_len))return false;
    memcpy(dst,item->valuestring,len+1);
    return true;
}
static bool parse_config(const char *line,se_vpn_config_t *out) {
    cJSON *doc=cJSON_Parse(line);
    if(!doc || !cJSON_IsObject(doc)) { cJSON_Delete(doc);return false; }
    se_vpn_config_t result={0};
    bool ok=text_field(doc,"wifi_ssid",result.wifi_ssid,sizeof(result.wifi_ssid),true,1) &&
       text_field(doc,"wifi_password",result.wifi_password,sizeof(result.wifi_password),true,8) &&
       text_field(doc,"tailscale_key",result.tailscale_key,sizeof(result.tailscale_key),true,12) &&
       text_field(doc,"gateway_token",result.gateway_token,sizeof(result.gateway_token),true,20);
    if(ok && strncmp(result.tailscale_key,"tskey-auth-",11)!=0)ok=false;
    // Reject optional half-defined readers. JK1 is mandatory for now.
    for(int i=0;i<3 && ok;++i) {
        char ipkey[12],codekey[12];
        snprintf(ipkey,sizeof ipkey,"jk%d_ip",i+1);
        snprintf(codekey,sizeof codekey,"jk%d_code",i+1);
        ok=text_field(doc,ipkey,result.jk_ip[i],sizeof(result.jk_ip[i]),i==0,7) &&
           text_field(doc,codekey,result.jk_code[i],sizeof(result.jk_code[i]),i==0,8);
        if(!ok)break;
        bool has_ip=result.jk_ip[i][0]!=0, has_code=result.jk_code[i][0]!=0;
        if(has_ip!=has_code || (has_ip && (!se_is_private_ipv4(result.jk_ip[i]) ||
            strlen(result.jk_code[i])<8)))ok=false;
    }
    cJSON_Delete(doc);
    if(ok)*out=result;
    return ok;
}
static bool load_config(se_vpn_config_t *out) {
    nvs_handle_t handle;
    if(nvs_open(CFG_NAMESPACE,NVS_READONLY,&handle)!=ESP_OK)return false;
    size_t len=sizeof(line_buffer);
    bool good=nvs_get_str(handle,CFG_KEY,line_buffer,&len)==ESP_OK &&
              len>0 && len<sizeof(line_buffer) &&
              parse_config(line_buffer,out);
    nvs_close(handle);
    memset(line_buffer,0,sizeof line_buffer);
    return good;
}
static bool save_config(const char *raw) {
    se_vpn_config_t candidate={0};
    if(!parse_config(raw,&candidate))return false;
    nvs_handle_t handle;
    if(nvs_open(CFG_NAMESPACE,NVS_READWRITE,&handle)!=ESP_OK)return false;
    esp_err_t err=nvs_set_str(handle,CFG_KEY,raw);
    if(err==ESP_OK)err=nvs_commit(handle);
    nvs_close(handle);
    memset(&candidate,0,sizeof candidate);
    return err==ESP_OK;
}
static void serial_prepare(void) {
    // Classic ESP32: bridge UART0. S3 N16R8: native USB Serial/JTAG CDC.
    // Setup is allowed only over physical cable, never over HTTP/Tailscale.
#if CONFIG_IDF_TARGET_ESP32S3
    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size=2048,
        .tx_buffer_size=2048,
    };
    esp_err_t err=usb_serial_jtag_driver_install(&cfg);
#else
    esp_err_t err=uart_driver_install(UART_NUM_0,2048,0,0,NULL,0);
#endif
    if(err!=ESP_OK && err!=ESP_ERR_INVALID_STATE)
        ESP_LOGE(TAG,"USB serial unavailable: %s",esp_err_to_name(err));
}
static void provisioning_loop(void) {
    serial_prepare();
    ESP_LOGW(TAG,"No network service started: USB provisioning only");
    usb_ack("SEVPN_READY_V1");
    size_t used=0;
    uint32_t last_ready=esp_log_timestamp();
    while(true) {
        uint8_t byte=0;
        int n=usb_read_one(&byte);
        if(n<=0) {
            if((uint32_t)(esp_log_timestamp()-last_ready)>2000) {
                usb_ack("SEVPN_READY_V1");
                last_ready=esp_log_timestamp();
            }
            continue;
        }
        if(byte=='\r')continue;
        if(byte=='\n') {
            line_buffer[used]=0;
            if(strcmp(line_buffer,"SEVPN_PING")==0) {
                usb_ack("SEVPN_READY_V1");
                used=0;
                continue;
            }
            const char prefix[]="SEVPN_CONFIG ";
            if(used>sizeof(prefix)-1 &&
               memcmp(line_buffer,prefix,sizeof(prefix)-1)==0 &&
               save_config(line_buffer+sizeof(prefix)-1)) {
                // ACK contains no secrets and is sent before controlled reboot.
                usb_ack("SEVPN_OK_REBOOT");
                memset(line_buffer,0,sizeof line_buffer);
                vTaskDelay(pdMS_TO_TICKS(1200));
                esp_restart();
            }
            // Do not print input lines, validation details, or any credentials.
            usb_ack("SEVPN_ERR_INVALID");
            memset(line_buffer,0,sizeof line_buffer);
            used=0;continue;
        }
        if(byte<32 || byte>126 || used>=LINE_MAX-2) {
            memset(line_buffer,0,sizeof line_buffer);
            used=0;continue;
        }
        line_buffer[used++]=(char)byte;
    }
}
static bool physical_reconfigure_requested(void) {
    // BOOT must be pressed AFTER reset, never while power is applied, or the
    // ESP32 would enter ROM bootloader mode. Allow a short supervised window.
    gpio_config_t cfg={
        .pin_bit_mask=1ULL<<GPIO_NUM_0,
        .mode=GPIO_MODE_INPUT,
        .pull_up_en=GPIO_PULLUP_ENABLE,
        .pull_down_en=GPIO_PULLDOWN_DISABLE,
        .intr_type=GPIO_INTR_DISABLE
    };
    if(gpio_config(&cfg)!=ESP_OK)return false;
    int held=0;
    puts("SEVPN_BOOT_WINDOW_9S");
    for(int step=0;step<90;++step) {
        if(gpio_get_level(GPIO_NUM_0)==0) {
            if(++held>=30)return true;
        } else held=0;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    return false;
}
const se_vpn_config_t *se_vpn_provision_or_load(void) {
    if(!load_config(&current)) {
        provisioning_loop(); // May restart after first successful provisioning
        return NULL;
    }
    if(physical_reconfigure_requested()) {
        ESP_LOGW(TAG,"Physical BOOT authorization: USB reprovision mode");
        provisioning_loop();
        return NULL;
    }
    return &current;
}
