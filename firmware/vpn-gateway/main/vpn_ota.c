/* Smart Energy VPN S3 OTA: fetch GitHub manifest and binary over HOUSE Wi-Fi.
 * The Android app sends ONLY a tiny authenticated+confirmed tailnet command.
 * No firmware bytes, URL, Wi-Fi password, Tailscale key, or NVS secret pass
 * through the APK or VPN API. OTA requires the two OTA app partitions.
 *
 * Never automatically publish an unverified firmware. Existing classic ESP32
 * and legacy S3 single-app deployments remain OTA-ineligible.
 */
#include "vpn_ota.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <ctype.h>
#include "sdkconfig.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "cJSON.h"
#include "mbedtls/sha256.h"

#define MANIFEST_URL "https://raw.githubusercontent.com/tapion69/smart-energy-android/main/updates/vpn-gateway.json"
#define RELEASE_PREFIX "https://github.com/tapion69/smart-energy-android/releases/download/vpn-gateway-v"
#define BIN_FILENAME "/SmartEnergy-VPN-S3-OTA.bin"
#define MANIFEST_LIMIT 1500

static const char *TAG="SE-VPN-OTA";
static volatile bool busy=false;
static char progress_state[48]="idle";

static bool dual_ota_ready(size_t *max_bytes,bool *rollback_pending) {
    if(max_bytes)*max_bytes=0;
    if(rollback_pending)*rollback_pending=false;
#if CONFIG_IDF_TARGET_ESP32S3
    const esp_partition_t *a=esp_partition_find_first(
        ESP_PARTITION_TYPE_APP,ESP_PARTITION_SUBTYPE_APP_OTA_0,NULL);
    const esp_partition_t *b=esp_partition_find_first(
        ESP_PARTITION_TYPE_APP,ESP_PARTITION_SUBTYPE_APP_OTA_1,NULL);
    const esp_partition_t *next=esp_ota_get_next_update_partition(NULL);
    const esp_partition_t *running=esp_ota_get_running_partition();
    esp_ota_img_states_t state=ESP_OTA_IMG_UNDEFINED;
    bool pending=running && esp_ota_get_state_partition(running,&state)==ESP_OK &&
        state==ESP_OTA_IMG_PENDING_VERIFY;
    if(rollback_pending)*rollback_pending=pending;
    if(a&&b&&next&&!pending && next->size>=262144 &&
       next->size<=8388608) {
        if(max_bytes)*max_bytes=next->size;
        return true;
    }
#endif
    return false;
}
void se_vpn_ota_status_json(char *out,size_t capacity,const char *version) {
    if(!out||capacity<256)return;
    size_t max=0;
    bool pending=false;
    bool ready=dual_ota_ready(&max,&pending);
    snprintf(out,capacity,
        "{\"product\":\"smart-energy-vpn-gateway\","
        "\"source\":\"smart-energy-vpn-ota-status\","
        "\"version\":\"%s\","
#if CONFIG_IDF_TARGET_ESP32S3
        "\"target\":\"esp32s3-n16r8\","
#else
        "\"target\":\"esp32-classic\","
#endif
        "\"ota_supported\":%s,\"flash_ready\":%s,"
        "\"partition_scheme\":\"%s\",\"rollback_pending\":%s,"
        "\"max_image_bytes\":%u,\"in_progress\":%s,\"state\":\"%s\"}",
        version?version:"",
        ready?"true":"false",(ready&&!busy)?"true":"false",
        ready?"dual_ota":"usb_migration_required",
        pending?"true":"false",
        (unsigned)max,busy?"true":"false",progress_state);
}
#if CONFIG_IDF_TARGET_ESP32S3
typedef struct {
    char *buffer;
    size_t used,capacity;
    bool failed;
} manifest_ctx_t;
static esp_err_t manifest_event(esp_http_client_event_t *event) {
    if(event->event_id!=HTTP_EVENT_ON_DATA || !event->user_data)return ESP_OK;
    manifest_ctx_t *ctx=(manifest_ctx_t*)event->user_data;
    if(esp_http_client_get_status_code(event->client)!=200)return ESP_OK;
    if(event->data_len<0 || ctx->used+(size_t)event->data_len>=ctx->capacity){
        ctx->failed=true;
        return ESP_FAIL;
    }
    memcpy(ctx->buffer+ctx->used,event->data,event->data_len);
    ctx->used+=(size_t)event->data_len;
    ctx->buffer[ctx->used]='\0';
    return ESP_OK;
}
static bool newer_than(const char *candidate,const char *installed) {
    int a=0,b=0,c=0,x=0,y=0,z=0;
    if(!candidate||!installed||
       sscanf(candidate,"%d.%d.%d",&a,&b,&c)!=3 ||
       sscanf(installed,"%d.%d.%d",&x,&y,&z)!=3)return false;
    if(a<0||b<0||c<0||a>999||b>999||c>999)return false;
    return a>x || (a==x && (b>y || (b==y && c>z)));
}
static bool approved_release(const char *url,const char *version) {
    if(!url||!version || strlen(url)>=320 ||
       strlen(version)>=32 || strpbrk(version,"/?#& \t\r\n"))return false;
    char expected[350];
    int n=snprintf(expected,sizeof expected,"%s%s%s",
        RELEASE_PREFIX,version,BIN_FILENAME);
    return n>0 && n<(int)sizeof(expected) && !strcmp(url,expected);
}
typedef struct {
    esp_ota_handle_t handle;
    const esp_partition_t *target;
    mbedtls_sha256_context sha;
    size_t received;
    bool failed;
} firmware_ctx_t;
static esp_err_t firmware_event(esp_http_client_event_t *event) {
    if(event->event_id!=HTTP_EVENT_ON_DATA || !event->user_data)return ESP_OK;
    firmware_ctx_t *ctx=(firmware_ctx_t*)event->user_data;
    if(esp_http_client_get_status_code(event->client)!=200)return ESP_OK;
    if(event->data_len<=0)return ESP_OK;
    if(ctx->failed || !ctx->target ||
       ctx->received+(size_t)event->data_len>ctx->target->size ||
       esp_ota_write(ctx->handle,event->data,event->data_len)!=ESP_OK) {
        ctx->failed=true;
        return ESP_FAIL;
    }
    ctx->received+=(size_t)event->data_len;
    mbedtls_sha256_update(&ctx->sha,event->data,event->data_len);
    return ESP_OK;
}
static bool checksum_ok(const unsigned char digest[32],const char expected[65]){
    static const char *hex="0123456789abcdef";
    unsigned mismatch=0;
    for(size_t i=0;i<32;i++) {
        const uint8_t hi=digest[i]>>4,lo=digest[i]&15;
        mismatch|=(unsigned)(uint8_t)(
            (uint8_t)tolower((unsigned char)expected[i*2])^(uint8_t)hex[hi]);
        mismatch|=(unsigned)(uint8_t)(
            (uint8_t)tolower((unsigned char)expected[i*2+1])^(uint8_t)hex[lo]);
    }
    return mismatch==0;
}
static void worker(void *arg) {
    char installed[48]={0};
    const char *version=(const char*)arg;
    if(version)snprintf(installed,sizeof installed,"%s",version);
    free(arg);
    snprintf(progress_state,sizeof progress_state,"fetch_manifest");
    char raw[MANIFEST_LIMIT]={0};
    manifest_ctx_t manifest={.buffer=raw,.used=0,.capacity=sizeof(raw),.failed=false};
    esp_http_client_config_t mc={.url=MANIFEST_URL,
        .crt_bundle_attach=esp_crt_bundle_attach,.timeout_ms=12000,
        .disable_auto_redirect=true,.event_handler=manifest_event,
        .user_data=&manifest};
    esp_http_client_handle_t client=esp_http_client_init(&mc);
    esp_err_t rc=client?esp_http_client_perform(client):ESP_FAIL;
    int status=client?esp_http_client_get_status_code(client):0;
    if(client)esp_http_client_cleanup(client);
    bool valid=rc==ESP_OK && status==200 && !manifest.failed &&
        manifest.used>0 && manifest.used<sizeof(raw)-1;
    cJSON *doc=valid?cJSON_Parse(raw):NULL;
    const cJSON *available=doc?cJSON_GetObjectItemCaseSensitive(doc,"available"):NULL;
    const cJSON *product=doc?cJSON_GetObjectItemCaseSensitive(doc,"product"):NULL;
    const cJSON *target=doc?cJSON_GetObjectItemCaseSensitive(doc,"target"):NULL;
    const cJSON *schema=doc?cJSON_GetObjectItemCaseSensitive(doc,"schemaVersion"):NULL;
    const cJSON *build=doc?cJSON_GetObjectItemCaseSensitive(doc,"version"):NULL;
    const cJSON *url=doc?cJSON_GetObjectItemCaseSensitive(doc,"downloadUrl"):NULL;
    const cJSON *hash=doc?cJSON_GetObjectItemCaseSensitive(doc,"sha256"):NULL;
    if(valid && !cJSON_IsTrue(available)){
        snprintf(progress_state,sizeof progress_state,"no_published_update");
        goto finish;
    }
    const char *remote_version=cJSON_IsString(build)?build->valuestring:NULL;
    const char *download=cJSON_IsString(url)?url->valuestring:NULL;
    const char *sha=cJSON_IsString(hash)?hash->valuestring:NULL;
    valid=valid && cJSON_IsNumber(schema) && schema->valueint==1 &&
        cJSON_IsString(product) && !strcmp(product->valuestring,"smart-energy-vpn-gateway") &&
        cJSON_IsString(target) && !strcmp(target->valuestring,"esp32s3-n16r8") &&
        newer_than(remote_version,installed) &&
        approved_release(download,remote_version) &&
        sha && strlen(sha)==64;
    if(valid)for(int i=0;i<64;i++){
        if(!isxdigit((unsigned char)sha[i])){valid=false;break;}
    }
    if(!valid){
        snprintf(progress_state,sizeof progress_state,"invalid_manifest_or_version");
        goto finish;
    }
    char digest_expected[65]={0},approved_url[350]={0};
    snprintf(digest_expected,sizeof digest_expected,"%s",sha);
    snprintf(approved_url,sizeof approved_url,"%s",download);
    cJSON_Delete(doc);doc=NULL;
    size_t limit=0;bool rollback=false;
    if(!dual_ota_ready(&limit,&rollback)){
        snprintf(progress_state,sizeof progress_state,"ota_partitions_unavailable");
        goto finish;
    }
    const esp_partition_t *partition=esp_ota_get_next_update_partition(NULL);
    if(!partition || partition->size!=limit){
        snprintf(progress_state,sizeof progress_state,"partition_changed");
        goto finish;
    }
    firmware_ctx_t image={.target=partition,.received=0,.failed=false};
    mbedtls_sha256_init(&image.sha);
    bool started=esp_ota_begin(partition,OTA_SIZE_UNKNOWN,&image.handle)==ESP_OK;
    if(!started){
        snprintf(progress_state,sizeof progress_state,"ota_begin_failed");
        mbedtls_sha256_free(&image.sha);
        goto finish;
    }
    mbedtls_sha256_starts(&image.sha,0);
    snprintf(progress_state,sizeof progress_state,"download_over_home_wifi");
    esp_http_client_config_t fc={.url=approved_url,
        .crt_bundle_attach=esp_crt_bundle_attach,
        .timeout_ms=15000,.max_redirection_count=4,
        .event_handler=firmware_event,.user_data=&image};
    esp_http_client_handle_t fw=esp_http_client_init(&fc);
    rc=fw?esp_http_client_perform(fw):ESP_FAIL;
    status=fw?esp_http_client_get_status_code(fw):0;
    if(fw)esp_http_client_cleanup(fw);
    unsigned char digest[32]={0};
    mbedtls_sha256_finish(&image.sha,digest);
    mbedtls_sha256_free(&image.sha);
    if(rc!=ESP_OK || status!=200 || image.failed ||
       image.received<16384 || !checksum_ok(digest,digest_expected)){
        snprintf(progress_state,sizeof progress_state,"download_or_sha256_failed");
        esp_ota_abort(image.handle);
        goto finish;
    }
    if(esp_ota_end(image.handle)!=ESP_OK ||
       esp_ota_set_boot_partition(partition)!=ESP_OK){
        snprintf(progress_state,sizeof progress_state,"image_validation_failed");
        goto finish;
    }
    snprintf(progress_state,sizeof progress_state,"verified_restarting");
    ESP_LOGW(TAG,"Verified firmware downloaded; rebooting to OTA slot");
    busy=false;
    vTaskDelay(pdMS_TO_TICKS(1300));
    esp_restart();

finish:
    cJSON_Delete(doc);
    busy=false;
    vTaskDelete(NULL);
}
#endif /* ESP32-S3 */
bool se_vpn_ota_start(const char *version) {
#if CONFIG_IDF_TARGET_ESP32S3
    if(busy || !version || strlen(version)>47)return false;
    size_t max=0; bool pending=false;
    if(!dual_ota_ready(&max,&pending))return false;
    char *copy=strdup(version);
    if(!copy)return false;
    busy=true;
    snprintf(progress_state,sizeof progress_state,"queued");
    if(xTaskCreate(worker,"se-vpn-ota",12288,copy,4,NULL)!=pdPASS){
        busy=false;
        free(copy);
        return false;
    }
    return true;
#else
    (void)version;
    return false;
#endif
}
void se_vpn_ota_confirm_after_tailnet_ready(void) {
#if CONFIG_IDF_TARGET_ESP32S3
    const esp_partition_t *running=esp_ota_get_running_partition();
    esp_ota_img_states_t state=ESP_OTA_IMG_UNDEFINED;
    if(running &&
       esp_ota_get_state_partition(running,&state)==ESP_OK &&
       state==ESP_OTA_IMG_PENDING_VERIFY) {
        if(esp_ota_mark_app_valid_cancel_rollback()==ESP_OK)
            ESP_LOGI(TAG,"Boot confirmed after Wi-Fi + Tailnet + HTTP startup");
        else ESP_LOGE(TAG,"OTA boot confirmation failed");
    }
#endif
}
void se_vpn_ota_failed_start_rollback(void) {
#if CONFIG_IDF_TARGET_ESP32S3
    const esp_partition_t *running=esp_ota_get_running_partition();
    esp_ota_img_states_t state=ESP_OTA_IMG_UNDEFINED;
    if(running &&
       esp_ota_get_state_partition(running,&state)==ESP_OK &&
       state==ESP_OTA_IMG_PENDING_VERIFY) {
        ESP_LOGE(TAG,"VPN boot health failed, rolling back OTA image");
        if(esp_ota_mark_app_invalid_rollback_and_reboot()!=ESP_OK)
            ESP_LOGE(TAG,"Automatic rollback failed; physical USB required");
    }
#endif
}
