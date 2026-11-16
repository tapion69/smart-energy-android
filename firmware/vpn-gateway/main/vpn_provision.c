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
#if CONFIG_IDF_TARGET_ESP32S3
#include "esp_attr.h"
#endif
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
#if CONFIG_IDF_TARGET_ESP32S3
/*
 * A physical hold/release on BOOT while running requests a ONE-TIME,
 * local-only USB maintenance boot. The marker is RTC memory (NOT NVS);
 * a cold boot, brownout or a normal reset cannot accidentally arm it.
 * Only esp_restart() performed after GPIO0 has been released consumes it.
 */
#define SE_USB_MAINT_MAGIC 0x53455635UL
RTC_NOINIT_ATTR static uint32_t usb_maintenance_request;
static void usb_maintenance_watch_task(void *unused) {
    (void)unused;
    int consecutive_low=0;
    bool armed=false;
    for(;;) {
        const bool held=gpio_get_level(GPIO_NUM_0)==0;
        if(held) {
            if(consecutive_low<40)++consecutive_low;
            if(consecutive_low>=35)armed=true; // 3.5s; debounce margin
        }else {
            if(armed) {
                /* RELEASE is mandatory. Never reset with BOOT held,
                 * which could enter the ESP32-S3 ROM download mode. */
                ESP_LOGW(TAG, "Physical BOOT hold/release: entering offline USB maintenance");
                usb_maintenance_request=SE_USB_MAINT_MAGIC;
                esp_restart();
            }
            consecutive_low=0;
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}
#endif

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
        char ipkey[12],codekey[12],namekey[15];
        snprintf(namekey,sizeof namekey,"jk%d_name",i+1);
        snprintf(ipkey,sizeof ipkey,"jk%d_ip",i+1);
        snprintf(codekey,sizeof codekey,"jk%d_code",i+1);
        ok=text_field(doc,ipkey,result.jk_ip[i],sizeof(result.jk_ip[i]),false,0) &&
           text_field(doc,namekey,result.jk_name[i],sizeof(result.jk_name[i]),false,0) &&
           text_field(doc,codekey,result.jk_code[i],sizeof(result.jk_code[i]),false,0);
        if(!ok)break;
        bool has_ip=result.jk_ip[i][0]!=0, has_code=result.jk_code[i][0]!=0;
        bool has_name=result.jk_name[i][0]!=0;
        if((has_ip && !se_is_private_ipv4(result.jk_ip[i])) ||
           (has_code!=(has_ip||has_name)) ||
           (has_code && (strlen(result.jk_code[i])<8 || strlen(result.jk_code[i])>32)))ok=false;
        if(has_code)any_target=true;
    }
    for(int i=0;i<3 && ok;++i) {
        char ipkey[16],codekey[16],namekey[16];
        snprintf(namekey,sizeof namekey,"volt%d_name",i+1);
        snprintf(ipkey,sizeof ipkey,"volt%d_ip",i+1);
        snprintf(codekey,sizeof codekey,"volt%d_code",i+1);
        ok=text_field(doc,ipkey,result.voltronic_ip[i],sizeof(result.voltronic_ip[i]),false,0) &&
           text_field(doc,namekey,result.voltronic_name[i],sizeof(result.voltronic_name[i]),false,0) &&
           text_field(doc,codekey,result.voltronic_code[i],sizeof(result.voltronic_code[i]),false,0);
        if(!ok)break;
        bool has_ip=result.voltronic_ip[i][0]!=0,has_code=result.voltronic_code[i][0]!=0;
        bool has_name=result.voltronic_name[i][0]!=0;
        if((has_ip && !se_is_private_ipv4(result.voltronic_ip[i])) ||
           (has_code!=(has_ip||has_name)) ||
           (has_code && (strlen(result.voltronic_code[i])<8 ||
                         strlen(result.voltronic_code[i])>32)))ok=false;
        if(has_code)any_target=true;
    }
    if(!any_target)ok=false;
    const cJSON *writes=cJSON_GetObjectItemCaseSensitive(doc,"remote_writes_enabled");
    if(writes && !cJSON_IsBool(writes))ok=false;
    result.remote_writes_enabled=cJSON_IsTrue(writes);
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

/* Physical USB BOOT mode only. Merge ONE Voltronic slot into the existing
 * NVS record, preserving Wi-Fi, Tailscale, gateway token and JK 1..3.
 * No network endpoint exists for this operation. */
/* One slot at a time over a physically authorized USB connection.
 * Editing a JK/Voltronic IP never changes Tailscale, Wi-Fi or other slots.
 * The code is optional ONLY for an already configured reader; then the
 * existing stored code is preserved exactly. Neither old nor new code logs.
 * A verified readback after NVS commit is mandatory for the ACK. */
static bool patch_reader_only(const char *raw_patch, bool jk) {
    bool success=false;
    cJSON *request=NULL,*original=NULL;
    char *merged=NULL;
    nvs_handle_t handle;
    char stored[LINE_MAX]={0};
    size_t stored_len=sizeof(stored);
    se_vpn_config_t previous={0},checked={0},persisted={0};
    if(!raw_patch || strlen(raw_patch)>=LINE_MAX)return false;
    request=cJSON_Parse(raw_patch);
    if(!cJSON_IsObject(request))goto finish;
    const int fields=cJSON_GetArraySize(request);
    if(fields!=2 && fields!=3)goto finish;
    const cJSON *slot_item=cJSON_GetObjectItemCaseSensitive(request,"slot");
    const cJSON *ip_item=cJSON_GetObjectItemCaseSensitive(request,"ip");
    const cJSON *code_item=cJSON_GetObjectItemCaseSensitive(request,"code");
    if(!cJSON_IsNumber(slot_item) || !cJSON_IsString(ip_item) ||
       !ip_item->valuestring || (code_item && !cJSON_IsString(code_item)))
        goto finish;
    const int slot=slot_item->valueint;
    if(slot<1 || slot>3 || slot_item->valuedouble!=(double)slot)goto finish;
    const char *ip=ip_item->valuestring;
    if(!se_is_private_ipv4(ip))goto finish;
    if(nvs_open(CFG_NAMESPACE,NVS_READONLY,&handle)!=ESP_OK)goto finish;
    const esp_err_t status=nvs_get_str(handle,CFG_KEY,stored,&stored_len);
    nvs_close(handle);
    if(status!=ESP_OK || !stored_len || stored_len>=LINE_MAX ||
       !parse_config(stored,&previous))goto finish;
    original=cJSON_Parse(stored);
    if(!cJSON_IsObject(original))goto finish;
    char ip_key[16],code_key[16];
    snprintf(ip_key,sizeof(ip_key),jk?"jk%d_ip":"volt%d_ip",slot);
    snprintf(code_key,sizeof(code_key),jk?"jk%d_code":"volt%d_code",slot);
    const char *code=code_item?code_item->valuestring:
        (jk?previous.jk_code[slot-1]:previous.voltronic_code[slot-1]);
    if(!code || strlen(code)<8 || strlen(code)>32)goto finish;
    cJSON *new_ip=cJSON_CreateString(ip),*new_code=cJSON_CreateString(code);
    if(!new_ip || !new_code) {
        cJSON_Delete(new_ip);cJSON_Delete(new_code);goto finish;
    }
    if(cJSON_HasObjectItem(original,ip_key))
        cJSON_ReplaceItemInObjectCaseSensitive(original,ip_key,new_ip);
    else cJSON_AddItemToObject(original,ip_key,new_ip);
    if(cJSON_HasObjectItem(original,code_key))
        cJSON_ReplaceItemInObjectCaseSensitive(original,code_key,new_code);
    else cJSON_AddItemToObject(original,code_key,new_code);
    merged=cJSON_PrintUnformatted(original);
    if(!merged || strlen(merged)>=LINE_MAX ||
       !parse_config(merged,&checked))goto finish;
    if(strcmp(previous.wifi_ssid,checked.wifi_ssid) ||
       strcmp(previous.wifi_password,checked.wifi_password) ||
       strcmp(previous.tailscale_key,checked.tailscale_key) ||
       strcmp(previous.gateway_token,checked.gateway_token) ||
       previous.remote_writes_enabled!=checked.remote_writes_enabled)goto finish;
    for(int i=0;i<3;i++) {
        if((!jk || i!=slot-1) &&
           (strcmp(previous.jk_ip[i],checked.jk_ip[i]) ||
            strcmp(previous.jk_code[i],checked.jk_code[i])))goto finish;
        if((jk || i!=slot-1) &&
           (strcmp(previous.voltronic_ip[i],checked.voltronic_ip[i]) ||
            strcmp(previous.voltronic_code[i],checked.voltronic_code[i])))
            goto finish;
    }
    const char *saved_ip=jk?checked.jk_ip[slot-1]:
                              checked.voltronic_ip[slot-1];
    const char *saved_code=jk?checked.jk_code[slot-1]:
                                checked.voltronic_code[slot-1];
    if(strcmp(saved_ip,ip) || strcmp(saved_code,code))goto finish;
    if(!save_config(merged))goto finish;
    // The only positive ACK comes after a fresh NVS read proves every
    // configuration field equals the proposed validated result.
    success=load_config(&persisted) &&
            memcmp(&persisted,&checked,sizeof(checked))==0;
finish:
    if(merged)free(merged);
    cJSON_Delete(original);cJSON_Delete(request);
    memset(stored,0,sizeof(stored));
    memset(&previous,0,sizeof(previous));
    memset(&checked,0,sizeof(checked));
    memset(&persisted,0,sizeof(persisted));
    return success;
}
static bool patch_voltronic_only(const char *raw_patch) {
    return patch_reader_only(raw_patch,false);
}
static bool patch_jk_only(const char *raw_patch) {
    return patch_reader_only(raw_patch,true);
}

/* USB-only opt-in; preserve every existing JK, Voltronic, Wi-Fi and
 * TailTiny secret. No network endpoint can enable remote control. */
static bool patch_remote_writes(bool enabled) {
    char stored[LINE_MAX]={0};
    nvs_handle_t h;
    size_t size=sizeof stored;
    if(nvs_open(CFG_NAMESPACE,NVS_READONLY,&h)!=ESP_OK)return false;
    const esp_err_t status=nvs_get_str(h,CFG_KEY,stored,&size);
    nvs_close(h);
    if(status!=ESP_OK || size==0 || size>=LINE_MAX)return false;
    se_vpn_config_t before={0},after={0};
    if(!parse_config(stored,&before))return false;
    cJSON *doc=cJSON_Parse(stored);
    if(!cJSON_IsObject(doc)) {cJSON_Delete(doc);return false;}
    cJSON_DeleteItemFromObjectCaseSensitive(doc,"remote_writes_enabled");
    cJSON_AddBoolToObject(doc,"remote_writes_enabled",enabled);
    char *merged=cJSON_PrintUnformatted(doc);
    bool ok=merged && strlen(merged)<LINE_MAX && parse_config(merged,&after);
    if(ok) {
        // Compare all other fields, including unknown optional reader slots.
        before.remote_writes_enabled=enabled;
        ok=memcmp(&before,&after,sizeof(before))==0 && save_config(merged);
    }
    /* An ACK means the NVS value survived a fresh read, not merely
     * that the set/commit API returned ESP_OK. */
    if(ok) {
        se_vpn_config_t persisted={0};
        ok=load_config(&persisted) &&
            persisted.remote_writes_enabled==enabled &&
            memcmp(&persisted,&after,sizeof(persisted))==0;
        memset(&persisted,0,sizeof(persisted));
    }
    if(merged)free(merged);
    cJSON_Delete(doc);
    memset(&before,0,sizeof before);
    memset(&after,0,sizeof after);
    memset(stored,0,sizeof stored);
    return ok;
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
/* The old READY banner is unsolicited; it proves TX only. A random
 * nonce PING requires the S3 to receive bytes AND transmit the matching
 * response on the same physical port, before any NVS-changing command. */
static bool usb_nonce_hex(const char *value) {
    if(!value || strlen(value)!=16)return false;
    for(int i=0;i<16;i++) {
        const char c=value[i];
        if(!((c>='0' && c<='9') || (c>='a' && c<='f')))return false;
    }
    return true;
}
static void usb_ack_reboot(int port) {
    usb_ack(port,"SEVPN_OK_REBOOT");
#if CONFIG_IDF_TARGET_ESP32S3
    if(port==SE_USB_BRIDGE && serial_bridge_ready)
        uart_wait_tx_done(UART_NUM_0,pdMS_TO_TICKS(450));
#endif
    vTaskDelay(pdMS_TO_TICKS(1500));
    esp_restart();
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
                    // Backward-compatible read-only handshake.
                    usb_ack(port,"SEVPN_READY_V1");
                    memset(buffer,0,LINE_MAX);*used=0;
                    continue;
                }
                if(strncmp(buffer,"SEVPN_PING ",11)==0) {
                    const char *nonce=buffer+11;
                    if(usb_nonce_hex(nonce)) {
                        char reply[32];
                        snprintf(reply,sizeof(reply),"SEVPN_PONG %s",nonce);
                        usb_ack(port,reply);
                    } else usb_ack(port,"SEVPN_ERR_PROTOCOL");
                    memset(buffer,0,LINE_MAX);*used=0;
                    continue;
                }
                if(strcmp(buffer,"SEVPN_REMOTE_WRITES_ON")==0 ||
                   strcmp(buffer,"SEVPN_REMOTE_WRITES_OFF")==0) {
                    const bool enable=strcmp(buffer,"SEVPN_REMOTE_WRITES_ON")==0;
                    if(patch_remote_writes(enable)) {
                        usb_ack_reboot(port);
                    }
                    usb_ack(port,"SEVPN_ERR_NVS");
                    memset(buffer,0,LINE_MAX);*used=0;continue;
                }
                const char set_jk[]="SEVPN_SET_JK ";
                if(*used>sizeof(set_jk)-1 &&
                   memcmp(buffer,set_jk,sizeof(set_jk)-1)==0) {
                    if(patch_jk_only(buffer+sizeof(set_jk)-1))
                        usb_ack_reboot(port);
                    usb_ack(port,"SEVPN_ERR_NVS");
                    memset(buffer,0,LINE_MAX);*used=0;continue;
                }
                const char add_volt[]="SEVPN_ADD_VOLTRONIC ";
                if(*used>sizeof(add_volt)-1 &&
                   memcmp(buffer,add_volt,sizeof(add_volt)-1)==0 &&
                   patch_voltronic_only(buffer+sizeof(add_volt)-1)) {
                    usb_ack_reboot(port);
                }
                const char prefix[]="SEVPN_CONFIG ";
                if(*used>sizeof(prefix)-1 &&
                   memcmp(buffer,prefix,sizeof(prefix)-1)==0 &&
                   save_config(buffer+sizeof(prefix)-1)) {
                    usb_ack_reboot(port);
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
/*
 * The physical USB setup must NEVER run on the small ESP-IDF app_main stack.
 * patch_remote_writes() and patch_voltronic_only() need several KB of local
 * scratch buffers for the existing NVS JSON, parsed configurations, and
 * validated merged document. The old inline call can exhaust app_main's
 * default stack right after a successful nonce PONG, before an NVS ACK.
 *
 * A separate task is started only in USB maintenance mode. Normal boot
 * keeps the original app_main memory footprint, Wi-Fi and TailTiny unchanged.
 * No write or network service is available from this offline USB task.
 */
#define SE_USB_MAINT_TASK_STACK 12288
static void usb_maintenance_task(void *unused) {
    (void)unused;
    provisioning_loop();  // Only exits if an unrecoverable firmware bug occurs.
    ESP_LOGE(TAG,"Offline USB task exited unexpectedly");
    vTaskDelete(NULL);
}
static bool start_offline_usb_task(void) {
    if(xTaskCreate(usb_maintenance_task,"se-vpn-usb",SE_USB_MAINT_TASK_STACK,
                   NULL,5,NULL)!=pdPASS) {
        ESP_LOGE(TAG,"Cannot allocate offline USB provisioning stack");
        return false;
    }
    return true;
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
#if CONFIG_IDF_TARGET_ESP32S3
    const bool runtime_physical_request=
        esp_reset_reason()==ESP_RST_SW &&
        usb_maintenance_request==SE_USB_MAINT_MAGIC;
    // Consume before any provisioning work; never turn a later reboot into
    // another maintenance request, and never persist a marker in NVS.
    usb_maintenance_request=0;
#else
    const bool runtime_physical_request=false;
#endif
    if(!load_config(&current)) {
        // No network startup when first provisioning; the dedicated USB task
        // handles validated configuration before rebooting into normal mode.
        (void)start_offline_usb_task();
        return NULL;
    }
    if(runtime_physical_request || physical_reconfigure_requested()) {
        ESP_LOGW(TAG,"Physical BOOT authorization: offline USB provisioning mode");
        (void)start_offline_usb_task();
        return NULL;
    }
    return &current;
}

void se_vpn_start_physical_maintenance_monitor(void) {
#if CONFIG_IDF_TARGET_ESP32S3
    // GPIO0 is configured as a pulled-up input by the initial 9s check.
    // Monitoring is independent of Wi-Fi and VPN connection state.
    if(xTaskCreate(usb_maintenance_watch_task,"se-usb-boot",3072,
                   NULL,5,NULL)!=pdPASS)
        ESP_LOGE(TAG,"Failed to start physical USB BOOT monitor");
#endif
}
