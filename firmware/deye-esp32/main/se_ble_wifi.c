/* Smart Energy BLE Wi-Fi recovery v1 — physically initiated ONLY.
 * Zero normal-runtime Bluetooth overhead; Tailscale/BMS/RS232 tasks are stopped.
 * Existing device code/token is a PSK; Wi-Fi credentials are AES-256-GCM
 * encrypted and authenticated. Only a pending Wi-Fi candidate is ever written.
 */
#include "se_ble_wifi.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include "esp_system.h"
#include "esp_random.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "nvs.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "cJSON.h"
#include "mbedtls/md.h"
#include "mbedtls/gcm.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG="SE-WIFI-BLE";
static const char *NAMESPACE="se_blewifi";
static const char *KEY="candidate";
static const char *DOMAIN="smart-energy-wifi-ble-v1";
static const ble_uuid128_t SERVICE_UUID=BLE_UUID128_INIT(
    0x01,0x00,0x45,0x44,0x4f,0x4e,0x12,0x9d,
    0x58,0x4e,0x62,0x3a,0x01,0x00,0xe7,0xb5);
static const ble_uuid128_t CHALLENGE_UUID=BLE_UUID128_INIT(
    0x01,0x00,0x45,0x44,0x4f,0x4e,0x12,0x9d,
    0x58,0x4e,0x62,0x3a,0x02,0x00,0xe7,0xb5);
static const ble_uuid128_t CONFIG_UUID=BLE_UUID128_INIT(
    0x01,0x00,0x45,0x44,0x4f,0x4e,0x12,0x9d,
    0x58,0x4e,0x62,0x3a,0x03,0x00,0xe7,0xb5);
static const ble_uuid128_t STATUS_UUID=BLE_UUID128_INIT(
    0x01,0x00,0x45,0x44,0x4f,0x4e,0x12,0x9d,
    0x58,0x4e,0x62,0x3a,0x04,0x00,0xe7,0xb5);
static uint8_t challenge[16];
static char secret[129];
static volatile bool accepted=false;
static uint8_t address_type=BLE_OWN_ADDR_PUBLIC;
static char advertising_name[25]="SE-WIFI-RECOVERY";

static bool valid_wifi(const char *ssid,const char *pass) {
    if(!ssid || !pass)return false;
    const size_t a=strnlen(ssid,33),b=strnlen(pass,65);
    if(a<1||a>32||b<8||b>63)return false;
    for(size_t i=0;i<a;i++)if((unsigned char)ssid[i]<32)return false;
    for(size_t i=0;i<b;i++)if((unsigned char)pass[i]<32)return false;
    return true;
}
bool se_ble_wifi_stage(const char *ssid,const char *pass) {
    if(!valid_wifi(ssid,pass))return false;
    cJSON *root=cJSON_CreateObject();
    if(!root)return false;
    cJSON_AddStringToObject(root,"ssid",ssid);
    cJSON_AddStringToObject(root,"password",pass);
    char *raw=cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if(!raw)return false;
    nvs_handle_t h;
    bool ok=nvs_open(NAMESPACE,NVS_READWRITE,&h)==ESP_OK;
    if(ok) {
        ok=nvs_set_str(h,KEY,raw)==ESP_OK && nvs_commit(h)==ESP_OK;
        nvs_close(h);
    }
    memset(raw,0,strlen(raw));free(raw);
    return ok;
}
static cJSON *pending_json(void) {
    nvs_handle_t h;
    if(nvs_open(NAMESPACE,NVS_READONLY,&h)!=ESP_OK)return NULL;
    size_t len=0;
    bool ok=nvs_get_str(h,KEY,NULL,&len)==ESP_OK && len>=2 && len<=1024;
    char *data=ok?calloc(len,1):NULL;
    if(!data)ok=false;
    if(ok)ok=nvs_get_str(h,KEY,data,&len)==ESP_OK;
    nvs_close(h);
    cJSON *result=ok?cJSON_Parse(data):NULL;
    if(data){memset(data,0,len);free(data);}
    return result;
}
bool se_ble_wifi_override(char *ssid,size_t ssid_len,char *pass,size_t pass_len) {
    cJSON *pending=pending_json();
    if(!pending)return false;
    const cJSON *a=cJSON_GetObjectItemCaseSensitive(pending,"ssid");
    const cJSON *b=cJSON_GetObjectItemCaseSensitive(pending,"password");
    bool ok=cJSON_IsString(a)&&cJSON_IsString(b) &&
        valid_wifi(a->valuestring,b->valuestring) &&
        strlen(a->valuestring)<ssid_len &&
        strlen(b->valuestring)<pass_len;
    if(ok) {
        strcpy(ssid,a->valuestring);
        strcpy(pass,b->valuestring);
    }
    cJSON_Delete(pending);return ok;
}
void se_ble_wifi_discard(void) {
    nvs_handle_t h;
    if(nvs_open(NAMESPACE,NVS_READWRITE,&h)==ESP_OK){
        nvs_erase_key(h,KEY);nvs_commit(h);nvs_close(h);
    }
}
bool se_ble_wifi_promote(const char *ns,const char *key) {
    cJSON *candidate=pending_json();
    if(!candidate)return false;
    const cJSON *a=cJSON_GetObjectItemCaseSensitive(candidate,"ssid");
    const cJSON *b=cJSON_GetObjectItemCaseSensitive(candidate,"password");
    if(!cJSON_IsString(a)||!cJSON_IsString(b)||
       !valid_wifi(a->valuestring,b->valuestring)) {
        cJSON_Delete(candidate);return false;
    }
    nvs_handle_t h;
    if(nvs_open(ns,NVS_READWRITE,&h)!=ESP_OK){
        cJSON_Delete(candidate);return false;
    }
    size_t size=0;
    bool ok=nvs_get_str(h,key,NULL,&size)==ESP_OK &&
        size>=2 && size<=8192;
    char *raw=ok?calloc(size,1):NULL;
    if(!raw)ok=false;
    if(ok)ok=nvs_get_str(h,key,raw,&size)==ESP_OK;
    cJSON *config=ok?cJSON_Parse(raw):NULL;
    if(!cJSON_IsObject(config))ok=false;
    if(ok){
        cJSON *s=cJSON_CreateString(a->valuestring);
        cJSON *p=cJSON_CreateString(b->valuestring);
        if(!s||!p) {
            cJSON_Delete(s);cJSON_Delete(p);ok=false;
        }else{
            cJSON_ReplaceItemInObjectCaseSensitive(config,"wifi_ssid",s);
            cJSON_ReplaceItemInObjectCaseSensitive(config,"wifi_password",p);
            char *out=cJSON_PrintUnformatted(config);
            ok=out && nvs_set_str(h,key,out)==ESP_OK &&
                nvs_commit(h)==ESP_OK;
            if(out){memset(out,0,strlen(out));free(out);}
        }
    }
    cJSON_Delete(config);cJSON_Delete(candidate);
    if(raw){memset(raw,0,size);free(raw);}
    nvs_close(h);
    if(ok)se_ble_wifi_discard();
    return ok;
}
static void timeout_guard(void *unused) {
    (void)unused;
    // 60 seconds to associate to the new access point. A reboot restores the
    // previous config if Wi-Fi was unavailable: staged candidate is removed.
    vTaskDelay(pdMS_TO_TICKS(60000));
    cJSON *candidate=pending_json();
    if(candidate) {
        cJSON_Delete(candidate);
        se_ble_wifi_discard();
        ESP_LOGW(TAG,"New Wi-Fi did not connect; reverting original configuration");
        esp_restart();
    }
    vTaskDelete(NULL);
}
void se_ble_wifi_guard_start(void) {
    cJSON *candidate=pending_json();
    if(candidate) {
        cJSON_Delete(candidate);
        xTaskCreate(timeout_guard,"se_blewifi_guard",3072,NULL,3,NULL);
    }
}
static int gatt_access(uint16_t conn_handle,uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt,void *arg) {
    (void)conn_handle;(void)attr_handle;(void)arg;
    if(ctxt->op==BLE_GATT_ACCESS_OP_READ_CHR &&
       ble_uuid_cmp(ctxt->chr->uuid,&CHALLENGE_UUID.u)==0){
        return os_mbuf_append(ctxt->om,challenge,sizeof(challenge))==0?
            0:BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if(ctxt->op==BLE_GATT_ACCESS_OP_READ_CHR &&
       ble_uuid_cmp(ctxt->chr->uuid,&STATUS_UUID.u)==0){
        const char *status=accepted?"OK":"WAIT";
        return os_mbuf_append(ctxt->om,status,strlen(status))==0?
            0:BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if(ctxt->op!=BLE_GATT_ACCESS_OP_WRITE_CHR ||
       ble_uuid_cmp(ctxt->chr->uuid,&CONFIG_UUID.u)!=0 ||
       accepted)return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    uint8_t wire[240];
    uint16_t length=0;
    if(ble_hs_mbuf_to_flat(ctxt->om,wire,sizeof(wire),&length)!=0||
       length<29 || length>sizeof(wire))return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    // Packet: 12 random-IV bytes | 16 GCM-tag bytes | encrypted JSON.
    const size_t ct_len=length-28;
    uint8_t plain[213]={0}, key[32]={0}, info[64]={0};
    size_t domain_len=strlen(DOMAIN);
    if(domain_len+sizeof(challenge)>sizeof(info) || ct_len>=sizeof(plain))
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    memcpy(info,DOMAIN,domain_len);
    memcpy(info+domain_len,challenge,sizeof(challenge));
    const mbedtls_md_info_t *sha=mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if(!sha || mbedtls_md_hmac(sha,(const uint8_t*)secret,strlen(secret),
          info,domain_len+sizeof(challenge),key)!=0)
        return BLE_ATT_ERR_UNLIKELY;
    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    int rc=mbedtls_gcm_setkey(&gcm,MBEDTLS_CIPHER_ID_AES,key,256);
    if(rc==0)rc=mbedtls_gcm_auth_decrypt(&gcm,ct_len,wire,12,
        challenge,sizeof(challenge),wire+12,16,wire+28,plain);
    mbedtls_gcm_free(&gcm);
    memset(key,0,sizeof(key));
    if(rc!=0){memset(plain,0,sizeof(plain));return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;}
    plain[ct_len]=0;
    cJSON *root=cJSON_Parse((const char*)plain);
    const cJSON *ssid=root?cJSON_GetObjectItemCaseSensitive(root,"ssid"):NULL;
    const cJSON *password=root?cJSON_GetObjectItemCaseSensitive(root,"password"):NULL;
    bool ok=cJSON_IsString(ssid)&&cJSON_IsString(password)&&
        se_ble_wifi_stage(ssid->valuestring,password->valuestring);
    cJSON_Delete(root);memset(plain,0,sizeof(plain));
    if(!ok)return BLE_ATT_ERR_UNLIKELY;
    accepted=true;
    return 0;
}
static const struct ble_gatt_chr_def characteristics[]={
    {.uuid=&CHALLENGE_UUID.u,.access_cb=gatt_access,.flags=BLE_GATT_CHR_F_READ},
    {.uuid=&CONFIG_UUID.u,.access_cb=gatt_access,.flags=BLE_GATT_CHR_F_WRITE},
    {.uuid=&STATUS_UUID.u,.access_cb=gatt_access,.flags=BLE_GATT_CHR_F_READ},
    {0}
};
static const struct ble_gatt_svc_def services[]={
    {.type=BLE_GATT_SVC_TYPE_PRIMARY,.uuid=&SERVICE_UUID.u,
     .characteristics=characteristics},
    {0}
};
static void advertise(void);
static int gap_event(struct ble_gap_event *event,void *arg) {
    (void)arg;
    if(event->type==BLE_GAP_EVENT_DISCONNECT)advertise();
    return 0;
}
static void advertise(void) {
    struct ble_hs_adv_fields fields={0};
    fields.flags=BLE_HS_ADV_F_DISC_GEN|BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128=(ble_uuid128_t*)&SERVICE_UUID;
    fields.num_uuids128=1;fields.uuids128_is_complete=1;
    int rc=ble_gap_adv_set_fields(&fields);
    if(rc!=0){ESP_LOGW(TAG,"Cannot advertise BLE service (%d)",rc);return;}
    struct ble_hs_adv_fields scan={0};
    scan.name=(uint8_t*)advertising_name;
    scan.name_len=strlen(advertising_name);
    scan.name_is_complete=1;
    ble_gap_adv_rsp_set_fields(&scan);
    struct ble_gap_adv_params params={0};
    params.conn_mode=BLE_GAP_CONN_MODE_UND;
    params.disc_mode=BLE_GAP_DISC_MODE_GEN;
    rc=ble_gap_adv_start(address_type,NULL,BLE_HS_FOREVER,&params,gap_event,NULL);
    if(rc!=0)ESP_LOGW(TAG,"Cannot start BLE advertising (%d)",rc);
}
static void sync_callback(void) {
    if(ble_hs_id_infer_auto(0,&address_type)==0)advertise();
}
static void host_task(void *unused) {
    (void)unused;
    nimble_port_run();
    nimble_port_freertos_deinit();
}
void se_ble_wifi_recovery(const char *device_name,const char *device_secret) {
    if(!device_secret || strlen(device_secret)<8 || strlen(device_secret)>128)
        return;
    memset(secret,0,sizeof(secret));
    strcpy(secret,device_secret);
    esp_fill_random(challenge,sizeof(challenge));
    if(device_name && device_name[0]) {
        snprintf(advertising_name,sizeof(advertising_name),"SE-WIFI-%.16s",device_name);
    }
    accepted=false;
    // This is only called BEFORE Wi-Fi, VPN and serial/BMS tasks are started.
    // A failed BLE init never alters the existing NVS configuration.
    if(nimble_port_init()!=ESP_OK) {
        ESP_LOGE(TAG,"NimBLE recovery unavailable");
        return;
    }
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set(advertising_name);
    ble_hs_cfg.sync_cb=sync_callback;
    if(ble_gatts_count_cfg(services)!=0 || ble_gatts_add_svcs(services)!=0) {
        ESP_LOGE(TAG,"BLE recovery GATT registration failed");
        esp_restart();
    }
    nimble_port_freertos_init(host_task);
    ESP_LOGI(TAG,"Bluetooth Wi-Fi recovery active for 120 seconds");
    for(int i=0;i<600;i++) {
        if(accepted) {
            ESP_LOGI(TAG,"Encrypted Wi-Fi candidate stored; restarting for test");
            vTaskDelay(pdMS_TO_TICKS(1600));
            esp_restart();
        }
        vTaskDelay(pdMS_TO_TICKS(200));
    }
    ESP_LOGI(TAG,"Recovery expired; old Wi-Fi remains unchanged");
    esp_restart();
}
