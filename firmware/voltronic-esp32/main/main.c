/* Smart Energy Voltronic/Axpert — ESP32-S3 N16R8, experimental READ-ONLY.
 * Local Wi-Fi + UART1 2400 8N1 to a MAX3232 3.3V RS232 interface.
 * UART1 TX=GPIO17, RX=GPIO16. NEVER connect inverter RS232 directly to GPIO.
 * Commands are compile-time whitelisted queries; HTTP GET has no serial writes.
 * No MQTT, Node-RED, cloud dependency, or on-device measurement history.
 */
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdlib.h>
#include <ctype.h>
#include "esp_system.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_http_server.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "driver/usb_serial_jtag.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "freertos/event_groups.h"
#include "cJSON.h"
#include "voltronic_protocol.h"

#define VT_VERSION "0.1.1-dev-s3-read-only"
#define VT_UART UART_NUM_1
#define VT_TX GPIO_NUM_17
#define VT_RX GPIO_NUM_16
#define VT_BAUD 2400
#define VT_HTTP_PORT 18081
#define VT_NVS_NS "se_volt"
#define VT_NVS_KEY "usb_config"
#define VT_MAX_FRAME 512
static const char *TAG="SE-VOLTRONIC";

typedef struct {
    char wifi_ssid[33];
    char wifi_password[65];
    char read_code[33];
} vol_cfg_t;
typedef struct {
    vt_qpigs_t qpigs;
    vt_qpigs2_t qpigs2;
    bool have_qpigs;
    bool have_qpigs2;
    char qmod;
    bool have_qpiri;
    uint32_t last_qpigs_ms,last_qpigs2_ms,last_qmod_ms,last_qpiri_ms;
    uint32_t q2_disabled_until_ms, polls_ok, crc_rejects, timeouts, naks;
} vol_state_t;
static vol_cfg_t cfg={0};
static vol_state_t state={0};
static SemaphoreHandle_t state_lock;
static EventGroupHandle_t wifi_events;
#define WIFI_OK BIT0

static bool valid_config_json(const char *text,vol_cfg_t *out) {
    cJSON *root=cJSON_Parse(text);
    if(!root)return false;
    cJSON *ssid=cJSON_GetObjectItemCaseSensitive(root,"wifi_ssid");
    cJSON *pass=cJSON_GetObjectItemCaseSensitive(root,"wifi_password");
    cJSON *code=cJSON_GetObjectItemCaseSensitive(root,"read_code");
    bool good=cJSON_IsString(ssid) && cJSON_IsString(pass) && cJSON_IsString(code) &&
        ssid->valuestring && pass->valuestring && code->valuestring;
    if(good) {
        size_t a=strnlen(ssid->valuestring,34);
        size_t b=strnlen(pass->valuestring,66);
        size_t c=strnlen(code->valuestring,34);
        good=(a>=1 && a<=32 && b>=8 && b<=64 && c>=8 && c<=32);
        for(size_t j=0;j<a && good;j++) {
            if((unsigned char)ssid->valuestring[j]<32)good=false;
        }
        for(size_t j=0;j<c && good;j++) {
            if((unsigned char)code->valuestring[j]<33 ||
               (unsigned char)code->valuestring[j]>126)good=false;
        }
    }
    if(good) {
        memset(out,0,sizeof(*out));
        strcpy(out->wifi_ssid,ssid->valuestring);
        strcpy(out->wifi_password,pass->valuestring);
        strcpy(out->read_code,code->valuestring);
    }
    cJSON_Delete(root);
    return good;
}
static bool load_config(void) {
    nvs_handle_t nvs;
    if(nvs_open(VT_NVS_NS,NVS_READONLY,&nvs)!=ESP_OK)return false;
    char raw[300]={0};size_t len=sizeof(raw);
    bool ok=nvs_get_str(nvs,VT_NVS_KEY,raw,&len)==ESP_OK &&
        len>0 && len<sizeof(raw) && valid_config_json(raw,&cfg);
    nvs_close(nvs);memset(raw,0,sizeof(raw));
    return ok;
}
static bool save_config(const char *text) {
    vol_cfg_t candidate={0};
    if(!valid_config_json(text,&candidate))return false;
    nvs_handle_t nvs;
    if(nvs_open(VT_NVS_NS,NVS_READWRITE,&nvs)!=ESP_OK)return false;
    esp_err_t result=nvs_set_str(nvs,VT_NVS_KEY,text);
    if(result==ESP_OK)result=nvs_commit(nvs);
    nvs_close(nvs);
    memset(&candidate,0,sizeof(candidate));
    return result==ESP_OK;
}
/* ESP32-S3 dev boards may expose CH343 USB-UART on GPIO43/44 instead of
 * native USB Serial/JTAG. Accept either physical provisioning interface;
 * never print passwords or codes, and never expose remote configuration. */
enum { VUSB_BRIDGE=0, VUSB_NATIVE=1 };
static bool bridge_ready=false,native_ready=false;
static char usb_lines[2][300];
static size_t usb_lengths[2]={0,0};
static void usb_message(int port,const char *message) {
    if(port==VUSB_NATIVE) {
        if(native_ready) {
            usb_serial_jtag_write_bytes(message,strlen(message),pdMS_TO_TICKS(20));
            usb_serial_jtag_write_bytes("\n",1,pdMS_TO_TICKS(20));
        }
    } else if(bridge_ready) {
        uart_write_bytes(UART_NUM_0,message,strlen(message));
        uart_write_bytes(UART_NUM_0,"\n",1);
    }
}
static int usb_recv(int port,uint8_t *b) {
    if(port==VUSB_NATIVE)
        return native_ready ? usb_serial_jtag_read_bytes(b,1,pdMS_TO_TICKS(15)) : 0;
    return bridge_ready ? uart_read_bytes(UART_NUM_0,b,1,pdMS_TO_TICKS(15)) : 0;
}
static void usb_begin(void) {
    const uart_config_t uart={
        .baud_rate=115200,
        .data_bits=UART_DATA_8_BITS,.parity=UART_PARITY_DISABLE,
        .stop_bits=UART_STOP_BITS_1,.flow_ctrl=UART_HW_FLOWCTRL_DISABLE
    };
    esp_err_t err=uart_param_config(UART_NUM_0,&uart);
    if(err==ESP_OK)err=uart_set_pin(UART_NUM_0,GPIO_NUM_43,GPIO_NUM_44,
                                    UART_PIN_NO_CHANGE,UART_PIN_NO_CHANGE);
    if(err==ESP_OK)err=uart_driver_install(UART_NUM_0,1024,256,0,NULL,0);
    bridge_ready=err==ESP_OK || err==ESP_ERR_INVALID_STATE;
    if(!bridge_ready)
        ESP_LOGE(TAG,"USB-UART bridge init failed %s",esp_err_to_name(err));
    const usb_serial_jtag_driver_config_t native={
        .rx_buffer_size=1024,.tx_buffer_size=1024
    };
    err=usb_serial_jtag_driver_install(&native);
    native_ready=err==ESP_OK || err==ESP_ERR_INVALID_STATE;
    if(!native_ready)
        ESP_LOGE(TAG,"Native USB init failed %s",esp_err_to_name(err));
}
static bool boot_reprovision(void) {
    gpio_config_t b={
        .pin_bit_mask=(1ULL<<GPIO_NUM_0),.mode=GPIO_MODE_INPUT,
        .pull_up_en=GPIO_PULLUP_ENABLE,.intr_type=GPIO_INTR_DISABLE
    };
    if(gpio_config(&b)!=ESP_OK)return false;
    int held=0;
    for(int i=0;i<90;i++){
        if(gpio_get_level(GPIO_NUM_0)==0) {
            if(++held>=30)return true;
        } else held=0;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    return false;
}
static void usb_provision(void) {
    usb_message(VUSB_BRIDGE,"SEVOLT_READY_V1");
    usb_message(VUSB_NATIVE,"SEVOLT_READY_V1");
    uint32_t last=esp_log_timestamp();
    for(;;) {
        for(int port=VUSB_BRIDGE;port<=VUSB_NATIVE;port++) {
            uint8_t b=0;
            if(usb_recv(port,&b)<=0)continue;
            char *line=usb_lines[port];
            size_t *used=&usb_lengths[port];
            if(b=='\r')continue;
            if(b=='\n') {
                line[*used]=0;
                if(strcmp(line,"SEVOLT_PING")==0) {
                    usb_message(port,"SEVOLT_READY_V1");
                    memset(line,0,300);*used=0;continue;
                }
                const char prefix[]="SEVOLT_CONFIG ";
                if(*used>sizeof(prefix)-1 &&
                    memcmp(line,prefix,sizeof(prefix)-1)==0 &&
                    save_config(line+sizeof(prefix)-1)) {
                    usb_message(port,"SEVOLT_OK_REBOOT");
                    memset(line,0,300);
                    vTaskDelay(pdMS_TO_TICKS(1200));
                    esp_restart();
                }
                usb_message(port,"SEVOLT_ERR_INVALID");
                memset(line,0,300);*used=0;continue;
            }
            if(b<32 || b>126 || *used>=298) {
                memset(line,0,300);*used=0;continue;
            }
            line[(*used)++]=(char)b;
        }
        if((uint32_t)(esp_log_timestamp()-last)>2000) {
            usb_message(VUSB_BRIDGE,"SEVOLT_READY_V1");
            usb_message(VUSB_NATIVE,"SEVOLT_READY_V1");
            last=esp_log_timestamp();
        }
    }
}

static void wifi_handler(void *arg,esp_event_base_t base,int32_t id,void *data){
    (void)arg;(void)data;
    if(base==WIFI_EVENT && id==WIFI_EVENT_STA_START)esp_wifi_connect();
    if(base==WIFI_EVENT && id==WIFI_EVENT_STA_DISCONNECTED){
        xEventGroupClearBits(wifi_events,WIFI_OK);esp_wifi_connect();
    }
    if(base==IP_EVENT && id==IP_EVENT_STA_GOT_IP)
        xEventGroupSetBits(wifi_events,WIFI_OK);
}
static void wifi_start(void){
    wifi_events=xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t i=WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&i));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT,ESP_EVENT_ANY_ID,
                                               &wifi_handler,NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT,IP_EVENT_STA_GOT_IP,
                                               &wifi_handler,NULL));
    wifi_config_t w={0};
    strncpy((char*)w.sta.ssid,cfg.wifi_ssid,sizeof(w.sta.ssid)-1);
    strncpy((char*)w.sta.password,cfg.wifi_password,sizeof(w.sta.password)-1);
    w.sta.threshold.authmode=WIFI_AUTH_WPA2_PSK;
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA,&w));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
}
static bool authorized(httpd_req_t *r) {
    char code[48]={0};
    if(httpd_req_get_hdr_value_str(r,"X-Setup-Code",code,sizeof(code))!=ESP_OK)
        return false;
    size_t a=strlen(code), b=strlen(cfg.read_code);
    if(a==0 || a!=b)return false;
    unsigned diff=0;
    for(size_t k=0;k<b;k++)diff |= (unsigned)(uint8_t)(code[k]^cfg.read_code[k]);
    return diff==0;
}
static esp_err_t send_json(httpd_req_t *r,cJSON *doc){
    char *json=cJSON_PrintUnformatted(doc);cJSON_Delete(doc);
    if(!json)return httpd_resp_send_err(r,HTTPD_500_INTERNAL_SERVER_ERROR,"json");
    httpd_resp_set_type(r,"application/json");
    httpd_resp_set_hdr(r,"Cache-Control","no-store");
    esp_err_t out=httpd_resp_sendstr(r,json);free(json);return out;
}
static esp_err_t deny(httpd_req_t *r){
    httpd_resp_set_status(r,"401 Unauthorized");
    return httpd_resp_sendstr(r,"{\"error\":\"invalid_read_code\"}");
}
static cJSON *base_json(const char *source){
    cJSON *root=cJSON_CreateObject();
    cJSON_AddStringToObject(root,"source",source);
    cJSON_AddStringToObject(root,"version",VT_VERSION);
    cJSON_AddBoolToObject(root,"read_only",true);
    return root;
}
static esp_err_t info_handler(httpd_req_t *r){
    if(!authorized(r))return deny(r);
    cJSON *root=base_json("voltronic-rs232-read-only");
    cJSON_AddStringToObject(root,"product","smart-energy-voltronic-s3");
    cJSON_AddStringToObject(root,"chip","esp32s3");
    cJSON_AddNumberToObject(root,"serial_baud",VT_BAUD);
    cJSON_AddStringToObject(root,"transport","uart1-rs232-max3232");
    cJSON_AddStringToObject(root,"capabilities","QPIGS QPIGS2 QMOD QPIRI");
    return send_json(r,root);
}
static esp_err_t telemetry_handler(httpd_req_t *r){
    if(!authorized(r))return deny(r);
    vol_state_t snap;
    xSemaphoreTake(state_lock,portMAX_DELAY);snap=state;xSemaphoreGive(state_lock);
    uint32_t age=(uint32_t)(esp_log_timestamp()-snap.last_qpigs_ms);
    bool live=snap.have_qpigs && age<=10000;
    cJSON *root=base_json("voltronic-qpigs-read-only");
    cJSON_AddBoolToObject(root,"available",live);
    cJSON_AddNumberToObject(root,"age_ms",snap.have_qpigs?age:-1);
    cJSON_AddNumberToObject(root,"polls_ok",snap.polls_ok);
    cJSON_AddNumberToObject(root,"crc_rejects",snap.crc_rejects);
    cJSON_AddNumberToObject(root,"timeouts",snap.timeouts);
    if(live){
        cJSON *v=cJSON_AddObjectToObject(root,"values");
        vt_qpigs_t m=snap.qpigs;
        cJSON_AddNumberToObject(v,"grid_voltage_v",m.grid_voltage_v);
        cJSON_AddNumberToObject(v,"grid_hz",m.grid_hz);
        cJSON_AddNumberToObject(v,"output_voltage_v",m.output_voltage_v);
        cJSON_AddNumberToObject(v,"output_hz",m.output_hz);
        cJSON_AddNumberToObject(v,"output_va",m.apparent_va);
        cJSON_AddNumberToObject(v,"output_w",m.output_w);
        cJSON_AddNumberToObject(v,"load_percent",m.load_percent);
        cJSON_AddNumberToObject(v,"battery_voltage_v",m.battery_v);
        cJSON_AddNumberToObject(v,"battery_soc_pct",m.battery_soc_percent);
        cJSON_AddNumberToObject(v,"battery_charge_a",m.battery_charge_a);
        cJSON_AddNumberToObject(v,"battery_discharge_a",m.battery_discharge_a);
        cJSON_AddNumberToObject(v,"battery_net_current_a",m.battery_net_current_a);
        cJSON_AddNumberToObject(v,"output_current_a",m.output_current_a);
        cJSON_AddNumberToObject(v,"pv1_current_a",m.pv1_current_a);
        cJSON_AddNumberToObject(v,"pv1_voltage_v",m.pv1_voltage_v);
        cJSON_AddNumberToObject(v,"heatsink_temp_c",m.heat_sink_c);
        if(m.grid_power_native_available)
            cJSON_AddNumberToObject(v,"grid_power_native_w",m.grid_power_native_w);
        if(snap.have_qpigs2 && (uint32_t)(esp_log_timestamp()-snap.last_qpigs2_ms)<=15000){
            cJSON_AddNumberToObject(v,"pv2_current_a",snap.qpigs2.current_a);
            cJSON_AddNumberToObject(v,"pv2_voltage_v",snap.qpigs2.voltage_v);
            cJSON_AddNumberToObject(v,"pv2_power_w",snap.qpigs2.power_w);
        }
    }
    return send_json(r,root);
}
static esp_err_t details_handler(httpd_req_t *r){
    if(!authorized(r))return deny(r);
    vol_state_t snap;
    xSemaphoreTake(state_lock,portMAX_DELAY);snap=state;xSemaphoreGive(state_lock);
    cJSON *root=base_json("voltronic-details-read-only");
    cJSON_AddStringToObject(root,"operation_mode",
        snap.qmod ? (char[]){snap.qmod,0} : "");
    cJSON_AddBoolToObject(root,"qpigs2_available",snap.have_qpigs2);
    cJSON_AddBoolToObject(root,"qpiri_available",snap.have_qpiri);
    cJSON_AddNumberToObject(root,"naks",snap.naks);
    // QPIRI frame is detected only; exact positions depend on model/firmware.
    // Never present ambiguous fields as validated bulk/float settings.
    return send_json(r,root);
}
static void start_api(void){
    httpd_config_t conf=HTTPD_DEFAULT_CONFIG();conf.server_port=VT_HTTP_PORT;
    conf.max_uri_handlers=4;
    httpd_handle_t server=NULL;
    ESP_ERROR_CHECK(httpd_start(&server,&conf));
    httpd_uri_t info={.uri="/api/v1/info",.method=HTTP_GET,.handler=info_handler};
    httpd_uri_t telemetry={.uri="/api/v1/telemetry",.method=HTTP_GET,.handler=telemetry_handler};
    httpd_uri_t details={.uri="/api/v1/details",.method=HTTP_GET,.handler=details_handler};
    ESP_ERROR_CHECK(httpd_register_uri_handler(server,&info));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server,&telemetry));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server,&details));
}
static void serial_start(void){
    const uart_config_t u={
        .baud_rate=VT_BAUD,.data_bits=UART_DATA_8_BITS,.parity=UART_PARITY_DISABLE,
        .stop_bits=UART_STOP_BITS_1,.flow_ctrl=UART_HW_FLOWCTRL_DISABLE
    };
    ESP_ERROR_CHECK(uart_param_config(VT_UART,&u));
    ESP_ERROR_CHECK(uart_set_pin(VT_UART,VT_TX,VT_RX,UART_PIN_NO_CHANGE,UART_PIN_NO_CHANGE));
    ESP_ERROR_CHECK(uart_driver_install(VT_UART,2048,0,0,NULL,0));
}
static size_t exchange(const char *command,uint8_t *frame,size_t cap,bool *timeout){
    uint8_t out[16]={0};
    size_t n=vt_make_command(command,out);
    if(n==0)return 0; // No serial writes except the five whitelisted queries.
    uart_flush_input(VT_UART);
    uart_write_bytes(VT_UART,(const char*)out,n);
    uart_wait_tx_done(VT_UART,pdMS_TO_TICKS(500));
    size_t len=0;uint32_t started=esp_log_timestamp();
    while((uint32_t)(esp_log_timestamp()-started)<2500 && len<cap){
        uint8_t b=0;int received=uart_read_bytes(VT_UART,&b,1,pdMS_TO_TICKS(70));
        if(received<=0)continue;
        frame[len++]=b;
        if(b==13){
            if(vt_valid_response(frame,len))return len;
            return 0;
        }
    }
    *timeout=true;
    return 0;
}
static void polling_task(void *unused){
    (void)unused;
    serial_start();
    uint32_t last_qpigs=0,last_qpigs2=0,last_qmod=0,last_qpiri=0;
    while(true){
        uint32_t now=esp_log_timestamp();uint8_t bytes[VT_MAX_FRAME];
        const char *cmd=NULL;
        if(now-last_qpigs>=2500)cmd="QPIGS";
        else if(now-last_qpigs2>=4000 && now>=state.q2_disabled_until_ms)cmd="QPIGS2";
        else if(now-last_qmod>=12000)cmd="QMOD";
        else if(now-last_qpiri>=300000)cmd="QPIRI";
        if(!cmd){vTaskDelay(pdMS_TO_TICKS(100));continue;}
        if(strcmp(cmd,"QPIGS")==0)last_qpigs=now;
        if(strcmp(cmd,"QPIGS2")==0)last_qpigs2=now;
        if(strcmp(cmd,"QMOD")==0)last_qmod=now;
        if(strcmp(cmd,"QPIRI")==0)last_qpiri=now;
        bool timeout=false;
        size_t length=exchange(cmd,bytes,sizeof(bytes),&timeout);
        xSemaphoreTake(state_lock,portMAX_DELAY);
        if(!length){if(timeout)state.timeouts++;else state.crc_rejects++;}
        else if(vt_nak(bytes,length)){
            state.naks++;
            if(strcmp(cmd,"QPIGS2")==0)
                state.q2_disabled_until_ms=(uint32_t)(esp_log_timestamp()+3600000U);
        }else if(strcmp(cmd,"QPIGS")==0){
            vt_qpigs_t measured;
            if(vt_parse_qpigs(bytes,length,&measured)){
                state.qpigs=measured;state.have_qpigs=true;
                state.last_qpigs_ms=esp_log_timestamp();state.polls_ok++;
            }
        }else if(strcmp(cmd,"QPIGS2")==0){
            vt_qpigs2_t measured;
            if(vt_parse_qpigs2(bytes,length,&measured)){
                state.qpigs2=measured;state.have_qpigs2=true;
                state.last_qpigs2_ms=esp_log_timestamp();state.polls_ok++;
            }else state.q2_disabled_until_ms=(uint32_t)(esp_log_timestamp()+3600000U);
        }else if(strcmp(cmd,"QMOD")==0){
            char mode=vt_parse_qmod(bytes,length);
            if(mode){state.qmod=mode;state.last_qmod_ms=esp_log_timestamp();}
        }else if(strcmp(cmd,"QPIRI")==0){
            double fields[40];
            size_t count=vt_fields(bytes,length,fields,40);
            if(count>=20) {
                state.have_qpiri=true; // Read confirmed, no unverified mapping.
                state.last_qpiri_ms=esp_log_timestamp();
            }
        }
        xSemaphoreGive(state_lock);
        vTaskDelay(pdMS_TO_TICKS(60));
    }
}
void app_main(void){
    ESP_ERROR_CHECK(nvs_flash_init());
    state_lock=xSemaphoreCreateMutex();
    usb_begin();
    if(!load_config() || boot_reprovision()){
        ESP_LOGW(TAG,"USB provisioning required — no Wi-Fi or inverter writes");
        usb_provision();return;
    }
    wifi_start();
    xTaskCreate(polling_task,"voltronic_read",7168,NULL,6,NULL);
    start_api();
    ESP_LOGI(TAG,"Read-only Voltronic bridge starting. HTTP port %d",VT_HTTP_PORT);
}
