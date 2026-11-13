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

/* S3 boards may have TWO USB physical connectors:
 * - CH343/CP210x USB-UART bridge -> UART0 on GPIO43/44;
 * - native USB Serial/JTAG -> GPIO19/20.
 * Listen on BOTH channels during physical USB provisioning and reply ONLY to
 * the channel that sent the command. Classic ESP32 retains its prior UART0.
 * NEVER echo credentials to the serial console or to any diagnostic log. */
enum { SE_USB_BRIDGE=0, SE_USB_NATIVE=1 };
static bool serial_bridge_ready=false;
#if CONFIG_IDF_TARGET_ESP32S3
static bool serial_native_ready=false;
#endif
static char rx_lines[2][LINE_MAX];
static size_t rx_used[2]={0,0};
static int serial_port_count(void) {
#if CONFIG_IDF_TARGET_ESP32S3
    return 2;
#else
    return 1;
#endif
}
static void usb_ack(int port,const char *message) {
#if CONFIG_IDF_TARGET_ESP32S3
    if(port==SE_USB_NATIVE) {
        if(serial_native_ready) {
            // Do not stall provisioning if the board's native USB connector
            // has no host; the CH343 connector may be the only cable attached.
            usb_serial_jtag_write_bytes(message,strlen(message),pdMS_TO_TICKS(20));
            usb_serial_jtag_write_bytes("\n",1,pdMS_TO_TICKS(20));
        }
        return;
    }
    if(serial_bridge_ready) {
        uart_write_bytes(UART_NUM_0,message,strlen(message));
        uart_write_bytes(UART_NUM_0,"\n",1);
    }
#else
    (void)port;
    puts(message);
    fflush(stdout);
#endif
}
static int usb_read_one(int port,uint8_t *byte) {
#if CONFIG_IDF_TARGET_ESP32S3
    if(port==SE_USB_NATIVE)
        return serial_native_ready ?
            usb_serial_jtag_read_bytes(byte,1,pdMS_TO_TICKS(15)) : 0;
    return serial_bridge_ready ?
        uart_read_bytes(UART_NUM_0,byte,1,pdMS_TO_TICKS(15)) : 0;
#else
    (void)port;
    return uart_read_bytes(UART_NUM_0,byte,1,pdMS_TO_TICKS(100));
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
    // Individual targets optional; require at least one JK or Voltronic target.
    bool any_target=false;
    for(int i=0;i<3 && ok;++i) {
        char ipkey[12],codekey[12];
        snprintf(ipkey,sizeof ipkey,"jk%d_ip",i+1);
        snprintf(codekey,sizeof codekey,"jk%d_code",i+1);
        ok=text_field(doc,ipkey,result.jk_ip[i],sizeof(result.jk_ip[i]),false,0) &&
           text_field(doc,codekey,result.jk_code[i],sizeof(result.jk_code[i]),false,0);
        if(!ok)break;
        bool has_ip=result.jk_ip[i][0]!=0, has_code=result.jk_code[i][0]!=0;
        if(has_ip!=has_code || (has_ip && (!se_is_private_ipv4(result.jk_ip[i]) ||
            strlen(result.jk_code[i])<8)))ok=false;
        if(has_ip)any_target=true;
    }
    for(int i=0;i<3 && ok;++i) {
        char ipkey[16],codekey[16];
        snprintf(ipkey,sizeof ipkey,"volt%d_ip",i+1);
        snprintf(codekey,sizeof codekey,"volt%d_code",i+1);
        ok=text_field(doc,ipkey,result.voltronic_ip[i],sizeof(result.voltronic_ip[i]),false,0) &&
           text_field(doc,codekey,result.voltronic_code[i],sizeof(result.voltronic_code[i]),false,0);
        if(!ok)break;
        bool has_ip=result.voltronic_ip[i][0]!=0,has_code=result.voltronic_code[i][0]!=0;
        if(has_ip!=has_code || (has_ip &&
           (!se_is_private_ipv4(result.voltronic_ip[i]) ||
            strlen(result.voltronic_code[i])<8 ||
            strlen(result.voltronic_code[i])>32)))ok=false;
        if(has_ip)any_target=true;
    }
    if(!any_target)ok=false;
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
    // Do not use either USB transport for OTA, LAN or user access. Physical
    // cable only. Separate sources prevent interleaving partial JSON lines.
#if CONFIG_IDF_TARGET_ESP32S3
    const uart_config_t cfg={
        .baud_rate=115200,
        .data_bits=UART_DATA_8_BITS,
        .parity=UART_PARITY_DISABLE,
        .stop_bits=UART_STOP_BITS_1,
        .flow_ctrl=UART_HW_FLOWCTRL_DISABLE
    };
    esp_err_t err=uart_param_config(UART_NUM_0,&cfg);
    if(err==ESP_OK)
        err=uart_set_pin(UART_NUM_0,GPIO_NUM_43,GPIO_NUM_44,
                         UART_PIN_NO_CHANGE,UART_PIN_NO_CHANGE);
    if(err==ESP_OK)
        err=uart_driver_install(UART_NUM_0,2048,256,0,NULL,0);
    serial_bridge_ready=err==ESP_OK || err==ESP_ERR_INVALID_STATE;
    if(!serial_bridge_ready)
        ESP_LOGE(TAG,"CH343 UART0 unavailable: %s",esp_err_to_name(err));
    const usb_serial_jtag_driver_config_t usb_cfg={
        .rx_buffer_size=2048,
        .tx_buffer_size=2048,
    };
    err=usb_serial_jtag_driver_install(&usb_cfg);
    serial_native_ready=err==ESP_OK || err==ESP_ERR_INVALID_STATE;
    if(!serial_native_ready)
        ESP_LOGE(TAG,"Native USB unavailable: %s",esp_err_to_name(err));
#else
    esp_err_t err=uart_driver_install(UART_NUM_0,2048,0,0,NULL,0);
    serial_bridge_ready=err==ESP_OK || err==ESP_ERR_INVALID_STATE;
    if(!serial_bridge_ready)
        ESP_LOGE(TAG,"USB UART unavailable: %s",esp_err_to_name(err));
#endif
}
static void provisioning_loop(void) {
    serial_prepare();
    ESP_LOGW(TAG,"No network service started: USB provisioning only");
    for(int port=0;port<serial_port_count();port++)
        usb_ack(port,"SEVPN_READY_V1");
    uint32_t last_ready=esp_log_timestamp();
    while(true) {
        for(int port=0;port<serial_port_count();port++) {
            uint8_t byte=0;
            if(usb_read_one(port,&byte)<=0)continue;
            char *buffer=rx_lines[port];
            size_t *used=&rx_used[port];
            if(byte=='\r')continue;
            if(byte=='\n') {
                buffer[*used]=0;
                if(strcmp(buffer,"SEVPN_PING")==0) {
                    usb_ack(port,"SEVPN_READY_V1");
                    memset(buffer,0,LINE_MAX);*used=0;
                    continue;
                }
                const char prefix[]="SEVPN_CONFIG ";
                if(*used>sizeof(prefix)-1 &&
                   memcmp(buffer,prefix,sizeof(prefix)-1)==0 &&
                   save_config(buffer+sizeof(prefix)-1)) {
                    usb_ack(port,"SEVPN_OK_REBOOT");
                    memset(buffer,0,LINE_MAX);
                    vTaskDelay(pdMS_TO_TICKS(1200));
                    esp_restart();
                }
                usb_ack(port,"SEVPN_ERR_INVALID");
                memset(buffer,0,LINE_MAX);*used=0;continue;
            }
            if(byte<32 || byte>126 || *used>=LINE_MAX-2) {
                memset(buffer,0,LINE_MAX);*used=0;continue;
            }
            buffer[(*used)++]=(char)byte;
        }
        if((uint32_t)(esp_log_timestamp()-last_ready)>2000) {
            for(int port=0;port<serial_port_count();port++)
                usb_ack(port,"SEVPN_READY_V1");
            last_ready=esp_log_timestamp();
        }
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
