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
#include <time.h>
#include "esp_sntp.h"
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

#define VT_VERSION "0.2.1-dev-httpdiag-read-only"
#define VT_UART UART_NUM_1
#define VT_TX GPIO_NUM_17
#define VT_RX GPIO_NUM_16
#define VT_BAUD 2400
#define VT_HTTP_PORT 18081
#define VT_NVS_NS "se_volt"
#define VT_NVS_KEY "usb_config"
static const char *TAG="SE-VOLTRONIC";

typedef struct {
    char wifi_ssid[33];
    char wifi_password[65];
    char read_code[33];
} vol_cfg_t;
typedef struct {
    vt_qpigs_t qpigs;
    vt_qpigs2_t qpigs2;
    bool have_qpigs, have_qpigs2, have_qpiri, have_qdop, have_qpiws;
    bool have_pv_energy_total, have_load_energy_total;
    double pv_energy_total_raw_wh, load_energy_total_raw_wh;
    double energy_raw_wh[2][4]; /* solar/load x total/day/month/year */
    bool energy_valid[2][4];
    double qpiri_fields[48], qdop_fields[48];
    uint8_t qpiri_count, qdop_count;
    char qmod, warning_bits[128];
    uint32_t last_qpigs_ms,last_qpigs2_ms,last_qmod_ms,last_qpiri_ms;
    uint32_t last_qdop_ms,last_qpiws_ms,last_valid_ms;
    uint32_t q2_disabled_until_ms, polls_ok, crc_rejects, timeouts, naks;
    uint32_t rx_bytes, rx_frames, no_rx_timeouts, partial_frames;
    uint32_t malformed_frames, overflow_frames, parse_rejects;
    uint32_t tx_commands, last_request_rx_bytes;
    char last_command[VT_MAX_COMMAND];
} vol_state_t;
typedef struct {
    uint8_t frame[VT_MAX_FRAME];
    size_t len;
    uint32_t rx_bytes, bad_crc, malformed, overflow, partial;
    bool timeout, nak;
} vt_exchange_t;
static vol_cfg_t cfg={0};
static vol_state_t state={0};
static SemaphoreHandle_t state_lock;
static EventGroupHandle_t wifi_events;
#define WIFI_OK BIT0
/* Aggregated, anonymous counters; no SSID, MAC, IP, password or access code. */
typedef struct {
    uint32_t wifi_disconnects, wifi_got_ip, wifi_connect_attempts;
    uint32_t http_requests, http_telemetry_requests, http_send_failures;
    int32_t wifi_last_disconnect_reason;
} vt_net_diag_t;
static vt_net_diag_t net_diag={0};
static portMUX_TYPE net_diag_lock=portMUX_INITIALIZER_UNLOCKED;

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
    (void)arg;
    if(base==WIFI_EVENT && id==WIFI_EVENT_STA_START) {
        portENTER_CRITICAL(&net_diag_lock);
        net_diag.wifi_connect_attempts++;
        portEXIT_CRITICAL(&net_diag_lock);
        esp_wifi_connect();
    }
    if(base==WIFI_EVENT && id==WIFI_EVENT_STA_DISCONNECTED){
        wifi_event_sta_disconnected_t *evt=(wifi_event_sta_disconnected_t *)data;
        portENTER_CRITICAL(&net_diag_lock);
        net_diag.wifi_disconnects++;
        net_diag.wifi_connect_attempts++;
        if(evt)net_diag.wifi_last_disconnect_reason=evt->reason;
        portEXIT_CRITICAL(&net_diag_lock);
        xEventGroupClearBits(wifi_events,WIFI_OK);
        esp_wifi_connect();
    }
    if(base==IP_EVENT && id==IP_EVENT_STA_GOT_IP) {
        portENTER_CRITICAL(&net_diag_lock);
        net_diag.wifi_got_ip++;
        portEXIT_CRITICAL(&net_diag_lock);
        xEventGroupSetBits(wifi_events,WIFI_OK);
    }
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
    portENTER_CRITICAL(&net_diag_lock);
    net_diag.http_requests++;
    portEXIT_CRITICAL(&net_diag_lock);
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
    esp_err_t out=httpd_resp_sendstr(r,json);
    if(out!=ESP_OK){
        portENTER_CRITICAL(&net_diag_lock);
        net_diag.http_send_failures++;
        portEXIT_CRITICAL(&net_diag_lock);
    }
    free(json);return out;
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
/* Safe snapshot, cheap enough to add to an existing telemetry GET.
 * Firmware returns counters since boot; Android compares them over time. */
static void add_runtime_net_diag(cJSON *root){
    vt_net_diag_t nd;
    portENTER_CRITICAL(&net_diag_lock);
    nd=net_diag;
    portEXIT_CRITICAL(&net_diag_lock);
    bool wifi_connected=(xEventGroupGetBits(wifi_events)&WIFI_OK)!=0;
    cJSON_AddBoolToObject(root,"wifi_connected",wifi_connected);
    cJSON_AddNumberToObject(root,"wifi_disconnect_count",nd.wifi_disconnects);
    cJSON_AddNumberToObject(root,"wifi_got_ip_count",nd.wifi_got_ip);
    cJSON_AddNumberToObject(root,"wifi_connect_attempts",nd.wifi_connect_attempts);
    cJSON_AddNumberToObject(root,"wifi_last_disconnect_reason",nd.wifi_last_disconnect_reason);
    cJSON_AddNumberToObject(root,"http_requests",nd.http_requests);
    cJSON_AddNumberToObject(root,"http_telemetry_requests",nd.http_telemetry_requests);
    cJSON_AddNumberToObject(root,"http_send_failures",nd.http_send_failures);
    cJSON_AddNumberToObject(root,"uptime_ms",esp_log_timestamp());
    cJSON_AddNumberToObject(root,"free_heap_bytes",esp_get_free_heap_size());
    cJSON_AddNumberToObject(root,"minimum_free_heap_bytes",esp_get_minimum_free_heap_size());
    wifi_ap_record_t ap={0};
    if(wifi_connected && esp_wifi_sta_get_ap_info(&ap)==ESP_OK)
        cJSON_AddNumberToObject(root,"wifi_rssi_dbm",ap.rssi);
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
    portENTER_CRITICAL(&net_diag_lock);
    net_diag.http_telemetry_requests++;
    portEXIT_CRITICAL(&net_diag_lock);
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
    cJSON_AddNumberToObject(root,"rx_bytes_total",snap.rx_bytes);
    cJSON_AddNumberToObject(root,"no_rx_timeouts",snap.no_rx_timeouts);
    cJSON_AddNumberToObject(root,"partial_frames",snap.partial_frames);
    add_runtime_net_diag(root);
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
        cJSON_AddNumberToObject(v,"bus_voltage_v",m.bus_voltage_v);
        cJSON_AddNumberToObject(v,"battery_voltage_from_scc_v",
                                m.battery_voltage_from_scc_v);
        if(m.pv1_power_native_available)
            cJSON_AddNumberToObject(v,"pv1_power_native_w",m.pv1_power_native_w);
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
/* Warning/fault bit mapping follows Smart Voltronic normalize state QPIWS.
 * "Line fail" is a warning, not automatically an inverter fault: off-grid
 * installations may deliberately disconnect utility input. */
static void add_qpiws_alerts(cJSON *root,const char *bits) {
    cJSON *faults=cJSON_AddArrayToObject(root,"faults");
    cJSON *warnings=cJSON_AddArrayToObject(root,"warnings");
    bool critical=bits && strlen(bits)>1 && bits[1]=='1';
    struct {uint8_t bit;const char *name;uint8_t severity;} labels[]={
        {0,"PV loss",0},{1,"Inverter fault",1},
        {2,"Bus over",1},{3,"Bus under",1},{4,"Bus soft fail",1},
        {5,"Line fail",0},{6,"OPV short",1},
        {7,"Inverter voltage low",1},{8,"Inverter voltage high",1},
        {9,"Over temperature",2},{10,"Fan locked",2},
        {11,"Battery voltage high",2},{12,"Battery low alarm",0},
        {14,"Battery shutdown",0},{15,"Battery derating",0},
        {16,"Over load",2},{17,"EEPROM fault",0},
        {18,"Inverter over current",1},{19,"Inverter soft fail",1},
        {20,"Self test fail",1},{21,"OP DC voltage over",1},
        {23,"Current sensor fail",1},{33,"Low PV energy",0},
        {34,"High AC input",0},{35,"Battery equalization",0}
    };
    size_t len=bits?strlen(bits):0;
    for(size_t i=0;i<sizeof(labels)/sizeof(labels[0]);i++) {
        size_t idx=labels[i].bit;
        if(idx>=len||bits[idx]!='1')continue;
        bool fault=(labels[i].severity==1 ||
                    (labels[i].severity==2 && critical));
        cJSON_AddItemToArray(fault?faults:warnings,
                             cJSON_CreateString(labels[i].name));
    }
    cJSON_AddBoolToObject(root,"has_fault",cJSON_GetArraySize(faults)>0);
    cJSON_AddBoolToObject(root,"has_warning",cJSON_GetArraySize(warnings)>0);
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
    cJSON_AddBoolToObject(root,"qdop_available",snap.have_qdop);
    cJSON_AddBoolToObject(root,"qpiws_available",snap.have_qpiws);
    if(snap.have_qpiws) {
        cJSON_AddStringToObject(root,"warning_bits",snap.warning_bits);
        add_qpiws_alerts(root,snap.warning_bits);
    }
    cJSON *params=cJSON_AddObjectToObject(root,"parameters");
    if(snap.have_qpiri) {
        cJSON *q=cJSON_AddObjectToObject(params,"qpiri");
        struct {const char *name;int index;} mapping[]={
            {"battery_recharge_voltage_v",8},{"battery_under_voltage_v",9},
            {"battery_bulk_voltage_v",10},{"battery_float_voltage_v",11},
            {"battery_type_code",12},{"max_ac_charging_current_a",13},
            {"max_charging_current_a",14},{"output_source_priority_code",16},
            {"charger_source_priority_code",17},{"battery_re_discharge_voltage_v",22},
            {"max_discharging_current_a",27}
        };
        for(size_t j=0;j<sizeof(mapping)/sizeof(mapping[0]);j++)
            if(snap.qpiri_count>(uint8_t)mapping[j].index)
                cJSON_AddNumberToObject(q,mapping[j].name,
                    snap.qpiri_fields[mapping[j].index]);
    }
    if(snap.have_qdop) {
        cJSON *q=cJSON_AddObjectToObject(params,"qdop");
        const char *keys[]={
            "battery_recharge_capacity_pct",
            "battery_redischarge_capacity_pct",
            "battery_under_capacity_pct"
        };
        for(size_t j=0;j<3;j++)
            if(snap.qdop_count>(uint8_t)(j+8))
                cJSON_AddNumberToObject(q,keys[j],snap.qdop_fields[j+8]);
    }
    cJSON *energy=cJSON_AddObjectToObject(root,"energy_wh");
    const char *srcs[]={"pv","load"},*periods[]={"total","day_utc","month_utc","year_utc"};
    for(int src=0;src<2;src++) {
        cJSON *values=cJSON_AddObjectToObject(energy,srcs[src]);
        for(int period=0;period<4;period++)
            if(snap.energy_valid[src][period])
                cJSON_AddNumberToObject(values,periods[period],
                                        snap.energy_raw_wh[src][period]);
    }
    cJSON_AddBoolToObject(root,"pv_energy_total_available",snap.have_pv_energy_total);
    if(snap.have_pv_energy_total)
        cJSON_AddNumberToObject(root,"pv_energy_total_raw_wh",snap.pv_energy_total_raw_wh);
    cJSON_AddBoolToObject(root,"load_energy_total_available",snap.have_load_energy_total);
    if(snap.have_load_energy_total)
        cJSON_AddNumberToObject(root,"load_energy_total_raw_wh",snap.load_energy_total_raw_wh);
    cJSON_AddStringToObject(root,"dated_energy_basis","UTC; only when SNTP is synchronized");
    cJSON_AddNumberToObject(root,"naks",snap.naks);
    /* Never guess QPIRI/QDOP field positions, or publish serial QID. */
    return send_json(r,root);
}
static esp_err_t diagnostics_handler(httpd_req_t *r) {
    if(!authorized(r))return deny(r);
    vol_state_t snap;
    xSemaphoreTake(state_lock,portMAX_DELAY);snap=state;xSemaphoreGive(state_lock);
    cJSON *root=base_json("voltronic-diagnostics-read-only");
    cJSON_AddStringToObject(root,"last_command",snap.last_command);
    cJSON_AddNumberToObject(root,"tx_commands",snap.tx_commands);
    cJSON_AddNumberToObject(root,"rx_bytes_total",snap.rx_bytes);
    cJSON_AddNumberToObject(root,"rx_frames",snap.rx_frames);
    cJSON_AddNumberToObject(root,"last_request_rx_bytes",snap.last_request_rx_bytes);
    cJSON_AddNumberToObject(root,"polls_ok",snap.polls_ok);
    cJSON_AddNumberToObject(root,"timeouts",snap.timeouts);
    cJSON_AddNumberToObject(root,"no_rx_timeouts",snap.no_rx_timeouts);
    cJSON_AddNumberToObject(root,"partial_frames",snap.partial_frames);
    cJSON_AddNumberToObject(root,"crc_rejects",snap.crc_rejects);
    cJSON_AddNumberToObject(root,"malformed_frames",snap.malformed_frames);
    cJSON_AddNumberToObject(root,"overflow_frames",snap.overflow_frames);
    cJSON_AddNumberToObject(root,"parse_rejects",snap.parse_rejects);
    cJSON_AddNumberToObject(root,"naks",snap.naks);
    cJSON_AddBoolToObject(root,"qpigs_available",snap.have_qpigs);
    cJSON_AddNumberToObject(root,"last_good_age_ms",
        snap.last_valid_ms ?
        (uint32_t)(esp_log_timestamp()-snap.last_valid_ms):-1);
    cJSON_AddBoolToObject(root,"read_only",true);
    add_runtime_net_diag(root);
    return send_json(r,root);
}
static void start_api(void){
    httpd_config_t conf=HTTPD_DEFAULT_CONFIG();
    conf.server_port=VT_HTTP_PORT;
    conf.max_uri_handlers=5;
    /* Default 4 KB HTTP stack and LRU disabled: protect frequent polling
       from held sessions and provide more room for cJSON + stack locals. */
    conf.stack_size=8192;
    conf.lru_purge_enable=true;
    conf.recv_wait_timeout=4;
    conf.send_wait_timeout=4;
    conf.backlog_conn=8;
    httpd_handle_t server=NULL;
    ESP_ERROR_CHECK(httpd_start(&server,&conf));
    httpd_uri_t info={.uri="/api/v1/info",.method=HTTP_GET,.handler=info_handler};
    httpd_uri_t telemetry={.uri="/api/v1/telemetry",.method=HTTP_GET,.handler=telemetry_handler};
    httpd_uri_t details={.uri="/api/v1/details",.method=HTTP_GET,.handler=details_handler};
    httpd_uri_t diag={.uri="/api/v1/diagnostics",.method=HTTP_GET,
                       .handler=diagnostics_handler};
    ESP_ERROR_CHECK(httpd_register_uri_handler(server,&info));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server,&telemetry));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server,&details));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server,&diag));
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
/* The known-good Node-RED flow: one request in flight, accumulator until CR.
 * Transport instrumentation distinguishes no signal, partial bytes and bad
 * CRC. No raw serial payload, SSID, code or device ID enters HTTP/logs.
 */
static vt_exchange_t exchange(const char *command) {
    vt_exchange_t out={0};
    uint8_t tx[VT_MAX_COMMAND]={0};
    size_t tx_len=vt_make_command(command,tx);
    if(!tx_len)return out;
    uart_flush_input(VT_UART);
    uart_write_bytes(VT_UART,(const char*)tx,tx_len);
    uart_wait_tx_done(VT_UART,pdMS_TO_TICKS(600));
    vt_rx_stream_t stream={0};
    uint32_t started=esp_log_timestamp();
    while((uint32_t)(esp_log_timestamp()-started)<3000) {
        uint8_t bytes[64];
        int received=uart_read_bytes(VT_UART,bytes,sizeof(bytes),pdMS_TO_TICKS(65));
        if(received<=0)continue;
        for(int i=0;i<received;i++) {
            if(bytes[i]==0x15 && stream.len==0) {
                out.nak=true;out.rx_bytes++;
                return out;
            }
            vt_reply_t status=vt_rx_feed(&stream,bytes[i]);
            if(status==VT_REPLY_OVERFLOW) {
                out.overflow++;
                continue;
            }
            if(status!=VT_REPLY_VALID)continue;
            out.rx_bytes=stream.rx_bytes;
            vt_reply_t classified=vt_classify_response(stream.bytes,stream.len);
            if(classified==VT_REPLY_VALID || classified==VT_REPLY_NAK) {
                out.len=stream.len;
                memcpy(out.frame,stream.bytes,out.len);
                out.nak=(classified==VT_REPLY_NAK);
                return out;
            }
            if(classified==VT_REPLY_BAD_CRC)out.bad_crc++;
            else out.malformed++;
            vt_rx_reset(&stream);
        }
    }
    out.rx_bytes=stream.rx_bytes;
    if(stream.len>0)out.partial++;
    out.timeout=true;
    return out;
}
typedef struct {
    const char *command;
    uint32_t interval_ms;
    uint32_t next_ms;
    uint32_t deferred_until_ms;
    bool dated;
} vt_job_t;
#define VT_JOBS 18
static vt_job_t jobs[VT_JOBS]={
    {"QMOD",12000,0,0,false},
    {"QPIRI",300000,0,0,false},
    {"QDOP",300000,0,0,false},
    {"QPIWS",300000,0,0,false},
    {"QET",3600000,0,0,false},
    {"QLT",3600000,0,0,false},
    {"QMN",3600000,0,0,false},
    {"QVFW",3600000,0,0,false},
    {"QVFW3",3600000,0,0,false},
    {"QID",3600000,0,0,false},
    {"QED",600000,0,0,true},
    {"QLD",600000,0,0,true},
    {"QEM",21600000,0,0,true},
    {"QLM",21600000,0,0,true},
    {"QEY",86400000,0,0,true},
    {"QLY",86400000,0,0,true},
    {"QPIGS",2500,0,0,false},
    {"QPIGS2",4000,0,0,false}
};
static bool job_command(const vt_job_t *job,char *dst,size_t dst_len) {
    if(!job->dated) {
        snprintf(dst,dst_len,"%s",job->command);
        return true;
    }
    time_t now=time(NULL);
    if(now < (time_t)1700000000)return false; /* Wait for SNTP. */
    struct tm utc={0};
    if(!gmtime_r(&now,&utc))return false;
    char date[12]={0};
    const char *fmt=NULL;
    size_t len=strlen(job->command);
    if(len<3)return false;
    char scope=job->command[2];
    if(scope=='D')fmt="%Y%m%d";
    else if(scope=='M')fmt="%Y%m";
    else if(scope=='Y')fmt="%Y";
    else return false;
    if(strftime(date,sizeof(date),fmt,&utc)==0)return false;
    snprintf(dst,dst_len,"%s%s",job->command,date);
    return true;
}
static vt_job_t *next_due_job(uint32_t now,bool secondary) {
    /* Avoid starvation: alternate QPIGS / QPIGS2 with one queued background
       query. Intervals are independent; never send concurrent commands. */
    static size_t round=0;
    size_t low=secondary?0:16;
    size_t high=secondary?16:VT_JOBS;
    for(size_t k=0;k<high-low;k++) {
        size_t idx=low+(round+k)%(high-low);
        vt_job_t *j=&jobs[idx];
        if((int32_t)(now-j->next_ms)>=0 &&
           (int32_t)(now-j->deferred_until_ms)>=0) {
            round=(idx-low+1)%(high-low);
            return j;
        }
    }
    return NULL;
}
static void interpret_reply(const char *cmd,const vt_exchange_t *rx,uint32_t now) {
    bool known=rx->len>0 && !rx->nak;
    if(rx->nak) {
        state.naks++;
        if(strcmp(cmd,"QPIGS2")==0)
            state.q2_disabled_until_ms=now+3600000U;
        return;
    }
    if(!known)return;
    if(strcmp(cmd,"QPIGS")==0) {
        vt_qpigs_t m;
        if(vt_parse_qpigs(rx->frame,rx->len,&m)) {
            state.qpigs=m;state.have_qpigs=true;state.last_qpigs_ms=now;
            state.polls_ok++;
        } else state.parse_rejects++;
    } else if(strcmp(cmd,"QPIGS2")==0) {
        vt_qpigs2_t m;
        if(vt_parse_qpigs2(rx->frame,rx->len,&m)) {
            state.qpigs2=m;state.have_qpigs2=true;
            state.last_qpigs2_ms=now;state.polls_ok++;
        } else {
            state.parse_rejects++;
            state.q2_disabled_until_ms=now+3600000U;
        }
    } else if(strcmp(cmd,"QMOD")==0) {
        char c=vt_parse_qmod(rx->frame,rx->len);
        if(c) {
            state.qmod=c;state.last_qmod_ms=now;state.polls_ok++;
        } else state.parse_rejects++;
    } else if(strcmp(cmd,"QPIRI")==0 || strcmp(cmd,"QDOP")==0) {
        double numbers[48]={0};
        size_t count=vt_fields(rx->frame,rx->len,numbers,48);
        if(count>=11) {
            if(strcmp(cmd,"QPIRI")==0) {
                memcpy(state.qpiri_fields,numbers,count*sizeof(double));
                state.qpiri_count=(uint8_t)count;
                state.have_qpiri=true;state.last_qpiri_ms=now;
            } else {
                memcpy(state.qdop_fields,numbers,count*sizeof(double));
                state.qdop_count=(uint8_t)count;
                state.have_qdop=true;state.last_qdop_ms=now;
            }
            state.polls_ok++;
        } else state.parse_rejects++;
    } else if(strcmp(cmd,"QPIWS")==0) {
        char bits[128]={0};
        if(vt_payload_copy(rx->frame,rx->len,bits,sizeof(bits))) {
            strncpy(state.warning_bits,bits,sizeof(state.warning_bits)-1);
            state.have_qpiws=true;state.last_qpiws_ms=now;
            state.polls_ok++;
        } else state.parse_rejects++;
    } else if((cmd[0]=='Q') && (cmd[1]=='E'||cmd[1]=='L') &&
              (cmd[2]=='T'||cmd[2]=='D'||cmd[2]=='M'||cmd[2]=='Y')) {
        double value=0;
        if(vt_parse_energy(rx->frame,rx->len,&value)) {
            int source=cmd[1]=='E'?0:1;
            int period=cmd[2]=='T'?0:cmd[2]=='D'?1:cmd[2]=='M'?2:3;
            state.energy_raw_wh[source][period]=value;
            state.energy_valid[source][period]=true;
            if(cmd[1]=='E' && cmd[2]=='T') {
                state.pv_energy_total_raw_wh=value;
                state.have_pv_energy_total=true;
            }
            if(cmd[1]=='L' && cmd[2]=='T') {
                state.load_energy_total_raw_wh=value;
                state.have_load_energy_total=true;
            }
            state.polls_ok++;
        } else state.parse_rejects++;
    } else {
        /* QMN / QID / VFW and dated energy frames: CRC-verified ACK only,
           never expose identifiers or unvalidated units. */
        state.polls_ok++;
    }
}
static void polling_task(void *unused) {
    (void)unused;
    serial_start();
    uint32_t start=esp_log_timestamp();
    for(size_t i=0;i<VT_JOBS;i++) {
        jobs[i].next_ms=start+(uint32_t)(i%16)*4200U;
    }
    jobs[16].next_ms=start;       /* QPIGS first. */
    jobs[17].next_ms=start+1300;  /* MPPT2 next. */
    uint32_t iterations=0;
    while(true) {
        uint32_t now=esp_log_timestamp();
        bool secondary=(iterations%3==2);
        vt_job_t *job=next_due_job(now,secondary);
        if(!job)job=next_due_job(now,!secondary);
        if(!job) {vTaskDelay(pdMS_TO_TICKS(120));continue;}
        if(strcmp(job->command,"QPIGS2")==0 &&
           (int32_t)(now-state.q2_disabled_until_ms)<0) {
            job->next_ms=state.q2_disabled_until_ms;
            continue;
        }
        char cmd[VT_MAX_COMMAND]={0};
        if(!job_command(job,cmd,sizeof(cmd))) {
            job->next_ms=now+60000U;
            continue;
        }
        job->next_ms=now+job->interval_ms;
        vt_exchange_t rx=exchange(cmd);
        uint32_t end=esp_log_timestamp();
        xSemaphoreTake(state_lock,portMAX_DELAY);
        state.tx_commands++;
        /* Publish only whitelisted command name. No raw serial data or ID. */
        snprintf(state.last_command,sizeof(state.last_command),"%s",cmd);
        state.rx_bytes+=rx.rx_bytes;
        state.rx_frames+=(rx.len>0?1U:0U);
        state.last_request_rx_bytes=rx.rx_bytes;
        state.crc_rejects+=rx.bad_crc;
        state.malformed_frames+=rx.malformed;
        state.partial_frames+=rx.partial;
        state.overflow_frames+=rx.overflow;
        if(rx.timeout) {
            state.timeouts++;
            if(rx.rx_bytes==0)state.no_rx_timeouts++;
        }
        if(rx.len>0)state.last_valid_ms=end;
        interpret_reply(cmd,&rx,end);
        xSemaphoreGive(state_lock);
        iterations++;
        vTaskDelay(pdMS_TO_TICKS(120));
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
    /* Network time only for UTC-dated QED/QEM/QEY/QLD/QLM/QLY.
       The serial reader does not depend on Internet or an NTP response. */
    esp_sntp_setoperatingmode(SNTP_OPMODE_POLL);
    esp_sntp_setservername(0,"pool.ntp.org");
    esp_sntp_init();
    xTaskCreate(polling_task,"voltronic_read",7168,NULL,6,NULL);
    start_api();
    ESP_LOGI(TAG,"Read-only Voltronic bridge starting. HTTP port %d",VT_HTTP_PORT);
}
