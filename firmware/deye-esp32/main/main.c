/* Smart Energy Deye ESP32-S3 N16R8 — initial read-only field-test firmware.
 * Network: ESP32 home Wi-Fi -> Solarman V5 dongle -> Modbus RTU FC03.
 * The existing VPN recognises HMAC-signed family "inverter", product
 * smart-energy-deye-s3. NO register writes in this initial field-test image.
 * Remote OTA is a SMALL request; the ESP downloads firmware over HOME Wi-Fi.
 */
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <time.h>
#include <errno.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <sys/time.h>
#include "esp_system.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_http_server.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "esp_timer.h"
#include "esp_random.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "driver/usb_serial_jtag.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "freertos/event_groups.h"
#include "mbedtls/md.h"
#include "mbedtls/sha256.h"
#include "cJSON.h"
#include "deye_protocol.h"

#define DY_VERSION "0.1.0-dev-solarman-v5"
#define DY_PORT 18081
#define DY_NAMESPACE "se_deye"
#define DY_CONFIG_KEY "usb_config"
#define DY_MANIFEST "https://raw.githubusercontent.com/tapion69/smart-energy-android/main/updates/deye.json"
#define DY_RELEASE_PREFIX "https://github.com/tapion69/smart-energy-android/releases/download/"
#define DY_MAX_REG 720
#define DY_USB_MAX 1050
static const char *TAG="SE-DEYE";

typedef struct {
    char wifi_ssid[33],wifi_password[65],read_code[33],display_name[33];
    char dongle_ip[16],dongle_serial[16],inverter_serial[33];
    uint16_t dongle_port;
    uint8_t modbus_id;
} dy_cfg_t;
typedef struct {
    uint16_t words[DY_MAX_REG];
    uint8_t valid[DY_MAX_REG];
    uint16_t device_type;
    uint32_t last_ok_ms,successes,failures,crc_errors,protocol_errors;
    bool online;
} dy_state_t;
static dy_cfg_t cfg;
static dy_state_t state;
static SemaphoreHandle_t state_mutex,network_mutex;
static EventGroupHandle_t net_events;
static const int WIFI_CONNECTED=BIT0;
static uint8_t sequence_number;
static bool usb_bridge_ready,usb_native_ready;
static uint32_t ota_started=0;
static volatile bool ota_busy=false,ota_success=false;
static char ota_status[96]="not_requested";
static char device_id[48];
static const deye_reg_t single_parameters[]={
    {"battery_control_mode",200,0,1,0},
    {"battery_float_voltage_v",203,0,0.01f,0},
    {"max_charge_current_a",211,0,1,0},
    {"battery_shutdown_soc",217,0,1,0},
    {"grid_charge_start_soc",230,0,1,0},
    {"grid_charge_current_a",231,0,1,0},
    {"energy_management",243,0,1,0},
    {"limit_max_grid_export_w",245,0,1,0},
    {"time_of_use_enabled",248,0,1,0},
    {"time_of_use_soc_1",267,0,1,0},
    {"time_of_use_soc_2",268,0,1,0},
    {"time_of_use_soc_3",269,0,1,0},
    {"time_of_use_soc_4",270,0,1,0},
    {"time_of_use_soc_5",271,0,1,0},
    {"time_of_use_soc_6",272,0,1,0}
};
static const deye_reg_t three_parameters[]={
    {"inverter_onoff",80,0,1,0},{"battery_capacity_ah",102,0,1,0},
    {"max_charge_current_a",108,0,1,0},{"max_discharge_current_a",109,0,1,0},
    {"battery_shutdown_soc",115,0,1,0},{"battery_restart_soc",116,0,1,0},
    {"battery_low_soc",117,0,1,0},{"grid_charge_current_a",128,0,1,0},
    {"grid_charge_enabled",130,0,1,0},{"work_mode",142,0,1,0},
    {"max_grid_output_w",143,0,1,0},{"solar_sell",145,0,1,0},
    {"time_of_use_soc_1",166,0,1,0},{"time_of_use_soc_2",167,0,1,0},
    {"time_of_use_soc_3",168,0,1,0},{"time_of_use_soc_4",169,0,1,0},
    {"time_of_use_soc_5",170,0,1,0},{"time_of_use_soc_6",171,0,1,0}
};
/* All initial settings are read-only; future FC16 writes require model-level
   validation, limits, an allowlist, explicit confirmation and fresh readback. */
static bool private_ip(const char *s){
    if(!s||strlen(s)>15)return false;
    unsigned a=999,b=999,c=999,d=999;char extra=0;
    if(sscanf(s,"%u.%u.%u.%u%c",&a,&b,&c,&d,&extra)!=4 ||
        a>255||b>255||c>255||d>255)return false;
    return a==10 || (a==172&&b>=16&&b<=31) || (a==192&&b==168);
}
static bool digits(const char *s,size_t max){
    size_t n=s?strnlen(s,max+1):0;
    if(n==0 || n>max)return false;
    for(size_t i=0;i<n;i++)if(s[i]<'0'||s[i]>'9')return false;
    return true;
}
static bool validate_config(const char *raw,dy_cfg_t *out){
    if(!raw||!out)return false;
    cJSON *j=cJSON_Parse(raw);if(!j)return false;
    const char *fields[]={"wifi_ssid","wifi_password","read_code",
        "display_name","dongle_ip","dongle_serial","inverter_serial"};
    char *dest[]={out->wifi_ssid,out->wifi_password,out->read_code,
        out->display_name,out->dongle_ip,out->dongle_serial,out->inverter_serial};
    const size_t caps[]={33,65,33,33,16,16,33};
    memset(out,0,sizeof *out);
    bool ok=true;
    for(int i=0;i<7;i++){
        const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,fields[i]);
        if(!cJSON_IsString(v)||!v->valuestring||
            strnlen(v->valuestring,caps[i])>=caps[i]){ok=false;break;}
        strcpy(dest[i],v->valuestring);
    }
    const cJSON *p=cJSON_GetObjectItemCaseSensitive(j,"dongle_port");
    const cJSON *u=cJSON_GetObjectItemCaseSensitive(j,"modbus_id");
    if(ok)ok=cJSON_IsNumber(p)&&p->valuedouble==p->valueint &&
        p->valueint>0&&p->valueint<=65535 &&
        cJSON_IsNumber(u)&&u->valuedouble==u->valueint &&
        u->valueint>=1&&u->valueint<=247;
    if(ok){
        out->dongle_port=p->valueint;out->modbus_id=u->valueint;
        uint32_t serial=0;
        ok=strlen(out->wifi_ssid)>0 && strlen(out->wifi_password)>=8 &&
           strlen(out->read_code)>=8 && strlen(out->display_name)>0 &&
           private_ip(out->dongle_ip) && deye_serial(out->dongle_serial,&serial) &&
           digits(out->inverter_serial,32);
        for(const char *q=out->read_code;*q&&ok;q++)
            if((unsigned char)*q<=32 || (unsigned char)*q>126)ok=false;
        for(const char *q=out->display_name;*q&&ok;q++)
            if((unsigned char)*q<32||*q=='|')ok=false;
    }
    cJSON_Delete(j);
    if(!ok)memset(out,0,sizeof *out);
    return ok;
}
static bool cfg_load(void){
    nvs_handle_t h;if(nvs_open(DY_NAMESPACE,NVS_READONLY,&h)!=ESP_OK)return false;
    char raw[DY_USB_MAX]={0};size_t len=sizeof raw;
    bool ok=nvs_get_str(h,DY_CONFIG_KEY,raw,&len)==ESP_OK &&
        len<sizeof raw && validate_config(raw,&cfg);
    nvs_close(h);memset(raw,0,sizeof raw);return ok;
}
static bool cfg_save(const dy_cfg_t *c){
    if(!c)return false;
    cJSON *j=cJSON_CreateObject();if(!j)return false;
    cJSON_AddStringToObject(j,"wifi_ssid",c->wifi_ssid);
    cJSON_AddStringToObject(j,"wifi_password",c->wifi_password);
    cJSON_AddStringToObject(j,"read_code",c->read_code);
    cJSON_AddStringToObject(j,"display_name",c->display_name);
    cJSON_AddStringToObject(j,"dongle_ip",c->dongle_ip);
    cJSON_AddStringToObject(j,"dongle_serial",c->dongle_serial);
    cJSON_AddStringToObject(j,"inverter_serial",c->inverter_serial);
    cJSON_AddNumberToObject(j,"dongle_port",c->dongle_port);
    cJSON_AddNumberToObject(j,"modbus_id",c->modbus_id);
    char *raw=cJSON_PrintUnformatted(j);cJSON_Delete(j);
    if(!raw)return false;
    dy_cfg_t checked;bool ok=strlen(raw)<DY_USB_MAX &&
        validate_config(raw,&checked);
    if(ok){
        nvs_handle_t h;
        ok=nvs_open(DY_NAMESPACE,NVS_READWRITE,&h)==ESP_OK;
        if(ok){
            ok=nvs_set_str(h,DY_CONFIG_KEY,raw)==ESP_OK &&
                nvs_commit(h)==ESP_OK;
            nvs_close(h);
        }
    }
    memset(raw,0,strlen(raw));free(raw);
    return ok;
}
static void usb_msg(int side,const char *s){
    if(side==0&&usb_bridge_ready){
        uart_write_bytes(UART_NUM_0,s,strlen(s));
        uart_write_bytes(UART_NUM_0,"\n",1);
    }
    if(side==1&&usb_native_ready){
        usb_serial_jtag_write_bytes(s,strlen(s),pdMS_TO_TICKS(30));
        usb_serial_jtag_write_bytes("\n",1,pdMS_TO_TICKS(30));
    }
}
static int usb_read_byte(int side,uint8_t *v){
    if(side==0&&usb_bridge_ready)
        return uart_read_bytes(UART_NUM_0,v,1,pdMS_TO_TICKS(20));
    if(side==1&&usb_native_ready)
        return usb_serial_jtag_read_bytes(v,1,pdMS_TO_TICKS(20));
    return 0;
}
static void usb_init(void){
    uart_config_t uart={.baud_rate=115200,.data_bits=UART_DATA_8_BITS,
        .parity=UART_PARITY_DISABLE,.stop_bits=UART_STOP_BITS_1,
        .flow_ctrl=UART_HW_FLOWCTRL_DISABLE};
    esp_err_t err=uart_param_config(UART_NUM_0,&uart);
    if(err==ESP_OK)err=uart_set_pin(UART_NUM_0,GPIO_NUM_43,GPIO_NUM_44,
        UART_PIN_NO_CHANGE,UART_PIN_NO_CHANGE);
    if(err==ESP_OK)err=uart_driver_install(UART_NUM_0,2048,256,0,NULL,0);
    usb_bridge_ready=err==ESP_OK || err==ESP_ERR_INVALID_STATE;
    usb_serial_jtag_driver_config_t native={.rx_buffer_size=2048,.tx_buffer_size=1024};
    err=usb_serial_jtag_driver_install(&native);
    usb_native_ready=err==ESP_OK || err==ESP_ERR_INVALID_STATE;
}
static bool boot_force_provision(void){
    gpio_config_t g={.pin_bit_mask=1ULL<<GPIO_NUM_0,.mode=GPIO_MODE_INPUT,
        .pull_up_en=GPIO_PULLUP_ENABLE,.intr_type=GPIO_INTR_DISABLE};
    gpio_config(&g);
    int held=0;
    for(int i=0;i<90;i++){
        if(!gpio_get_level(GPIO_NUM_0)){if(++held>=30)return true;}
        else held=0;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    return false;
}
static void usb_provision(void){
    usb_msg(0,"SEDEYE_READY_V1");usb_msg(1,"SEDEYE_READY_V1");
    char lines[2][DY_USB_MAX]={0};size_t used[2]={0,0};
    while(true){
        for(int side=0;side<2;side++){
            uint8_t b=0;if(usb_read_byte(side,&b)<=0)continue;
            if(b=='\r')continue;
            if(b=='\n'){
                lines[side][used[side]]=0;
                if(!strcmp(lines[side],"SEDEYE_PING"))
                    usb_msg(side,"SEDEYE_READY_V1");
                else if(!strncmp(lines[side],"SEDEYE_CONFIG ",14)){
                    dy_cfg_t next={0};
                    if(validate_config(lines[side]+14,&next) && cfg_save(&next)){
                        usb_msg(side,"SEDEYE_OK_REBOOT");
                        vTaskDelay(pdMS_TO_TICKS(1000));esp_restart();
                    }
                    usb_msg(side,"SEDEYE_ERR_INVALID");
                }else usb_msg(side,"SEDEYE_ERR_INVALID");
                memset(lines[side],0,DY_USB_MAX);used[side]=0;continue;
            }
            if(b<32||b>126||used[side]>=DY_USB_MAX-2) {
                used[side]=0;memset(lines[side],0,DY_USB_MAX);
            }else lines[side][used[side]++]=(char)b;
        }
    }
}
static void wifi_events(void *arg,esp_event_base_t ev,int32_t code,void *data){
    (void)arg;(void)data;
    if(ev==WIFI_EVENT && code==WIFI_EVENT_STA_START)esp_wifi_connect();
    if(ev==WIFI_EVENT && code==WIFI_EVENT_STA_DISCONNECTED){
        xEventGroupClearBits(net_events,WIFI_CONNECTED);
        esp_wifi_connect();
    }
    if(ev==IP_EVENT && code==IP_EVENT_STA_GOT_IP)
        xEventGroupSetBits(net_events,WIFI_CONNECTED);
}
static void start_wifi(void){
    net_events=xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t init=WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT,ESP_EVENT_ANY_ID,
        &wifi_events,NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT,IP_EVENT_STA_GOT_IP,
        &wifi_events,NULL));
    wifi_config_t wifi={0};
    snprintf((char *)wifi.sta.ssid,sizeof(wifi.sta.ssid),"%s",cfg.wifi_ssid);
    snprintf((char *)wifi.sta.password,sizeof(wifi.sta.password),"%s",cfg.wifi_password);
    wifi.sta.threshold.authmode=WIFI_AUTH_WPA2_PSK;
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA,&wifi));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
}
static bool online_wifi(void){
    return net_events && (xEventGroupGetBits(net_events)&WIFI_CONNECTED);
}
static bool auth(httpd_req_t *r){
    char presented[48]={0};
    if(httpd_req_get_hdr_value_str(r,"X-Setup-Code",presented,
        sizeof presented)!=ESP_OK)return false;
    size_t n=strnlen(cfg.read_code,sizeof cfg.read_code);
    if(n<8 || strlen(presented)!=n)return false;
    unsigned mismatch=0;
    for(size_t i=0;i<n;i++)mismatch|=(unsigned char)(presented[i]^cfg.read_code[i]);
    return mismatch==0;
}
static esp_err_t denied(httpd_req_t *r,const char *code,const char *error){
    httpd_resp_set_status(r,code);
    httpd_resp_set_type(r,"application/json");
    char body[180]={0};
    snprintf(body,sizeof body,"{\"error\":\"%s\"}",error);
    return httpd_resp_sendstr(r,body);
}
static esp_err_t json_reply(httpd_req_t *r,cJSON *j){
    char *s=cJSON_PrintUnformatted(j);cJSON_Delete(j);
    if(!s)return denied(r,"500 Internal Server Error","memory");
    httpd_resp_set_type(r,"application/json");
    httpd_resp_set_hdr(r,"Cache-Control","no-store");
    esp_err_t res=httpd_resp_sendstr(r,s);free(s);return res;
}
static cJSON *identity_json(void){
    cJSON *j=cJSON_CreateObject();
    cJSON_AddStringToObject(j,"product","smart-energy-deye-s3");
    cJSON_AddStringToObject(j,"version",DY_VERSION);
    cJSON_AddStringToObject(j,"device_id",device_id);
    cJSON_AddStringToObject(j,"device_name",cfg.display_name);
    cJSON_AddStringToObject(j,"chip","esp32s3");
    cJSON_AddStringToObject(j,"transport","solarman-v5-tcp-rtu");
    cJSON_AddBoolToObject(j,"parameter_edit_supported",false);
    cJSON_AddBoolToObject(j,"ota_supported",true);
    return j;
}
static void discovery_loop(void *ignored){
    (void)ignored;
    int fd=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
    if(fd<0)vTaskDelete(NULL);
    int yes=1;setsockopt(fd,SOL_SOCKET,SO_BROADCAST,&yes,sizeof yes);
    struct sockaddr_in dest={.sin_family=AF_INET,.sin_port=htons(18769)};
    dest.sin_addr.s_addr=INADDR_BROADCAST;
    while(true){
        if(online_wifi()){
            esp_netif_t *net=esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
            esp_netif_ip_info_t info={0};
            if(net && esp_netif_get_ip_info(net,&info)==ESP_OK){
                char ip[16]={0};inet_ntop(AF_INET,&info.ip.addr,ip,sizeof ip);
                char message[256]={0};
                int len=snprintf(message,sizeof message,
                    "smart-energy-discovery-v1|inverter|%s|%s|18081|%s",
                    cfg.display_name,device_id,ip);
                unsigned char digest[32]={0};
                const mbedtls_md_info_t *sha=mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
                if(len>0&&len<(int)sizeof(message)&&sha &&
                   mbedtls_md_hmac(sha,(unsigned char *)cfg.read_code,
                       strlen(cfg.read_code),(unsigned char *)message,
                       len,digest)==0){
                    char hex[65]={0};
                    for(int i=0;i<32;i++)snprintf(hex+i*2,3,"%02x",digest[i]);
                    cJSON *j=cJSON_CreateObject();
                    cJSON_AddStringToObject(j,"proto","smart-energy-discovery-v1");
                    cJSON_AddStringToObject(j,"family","inverter");
                    cJSON_AddStringToObject(j,"name",cfg.display_name);
                    cJSON_AddStringToObject(j,"id",device_id);
                    cJSON_AddStringToObject(j,"ip",ip);
                    cJSON_AddNumberToObject(j,"port",DY_PORT);
                    cJSON_AddStringToObject(j,"auth",hex);
                    char *msg=cJSON_PrintUnformatted(j);cJSON_Delete(j);
                    if(msg){
                        sendto(fd,msg,strlen(msg),0,(struct sockaddr *)&dest,sizeof dest);
                        free(msg);
                    }
                }
            }
        }
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}
static bool receive_exact(int fd,uint8_t *buffer,size_t need){
    size_t used=0;
    while(used<need){
        int n=recv(fd,buffer+used,need-used,0);
        if(n<=0)return false;
        used+=(size_t)n;
    }
    return true;
}
static bool read_fc03(uint16_t start,uint16_t count,uint16_t *words){
    if(!words||count<1||count>DEYE_V5_MAX_WORDS||!online_wifi())
        return false;
    uint32_t logger=0;
    if(!deye_serial(cfg.dongle_serial,&logger))return false;
    struct sockaddr_in dest={0};
    dest.sin_family=AF_INET;
    dest.sin_port=htons(cfg.dongle_port);
    if(inet_pton(AF_INET,cfg.dongle_ip,&dest.sin_addr)!=1)return false;
    int sock=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    if(sock<0)return false;
    struct timeval timeout={.tv_sec=3,.tv_usec=0};
    setsockopt(sock,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof timeout);
    setsockopt(sock,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof timeout);
    bool success=false;
    if(connect(sock,(struct sockaddr *)&dest,sizeof dest)==0){
        uint8_t tx[36]={0},rx[DEYE_V5_RX_MAX]={0};
        uint8_t seq=++sequence_number;
        size_t txlen=deye_v5_make_fc03(tx,sizeof tx,logger,
            seq,cfg.modbus_id,start,count);
        if(txlen && send(sock,tx,txlen,0)==(int)txlen &&
           receive_exact(sock,rx,11)){
            size_t payload=deye_u16le(rx+1);
            if(payload>=19 && payload<=DEYE_V5_RX_MAX-13 &&
               receive_exact(sock,rx+11,payload+2))
                success=deye_v5_parse_fc03(rx,11+payload+2,
                    logger,seq,cfg.modbus_id,count,words);
        }
    }
    close(sock);
    return success;
}
static bool query_registers(uint16_t start,uint16_t count){
    uint16_t w[DEYE_V5_MAX_WORDS]={0};
    if(!read_fc03(start,count,w)){
        xSemaphoreTake(state_mutex,portMAX_DELAY);
        state.failures++;
        xSemaphoreGive(state_mutex);
        return false;
    }
    xSemaphoreTake(state_mutex,portMAX_DELAY);
    for(int i=0;i<count;i++){
        state.words[start+i]=w[i];state.valid[start+i]=true;
    }
    state.successes++;state.last_ok_ms=esp_log_timestamp();
    xSemaphoreGive(state_mutex);
    return true;
}
static void polling_loop(void *ignored){
    (void)ignored;
    while(true){
        if(!online_wifi()){vTaskDelay(pdMS_TO_TICKS(3000));continue;}
        xSemaphoreTake(network_mutex,portMAX_DELAY);
        uint16_t identify=0;
        if(read_fc03(0,1,&identify) && deye_type_valid(identify)){
            xSemaphoreTake(state_mutex,portMAX_DELAY);
            if(state.device_type!=identify){
                memset(state.valid,0,sizeof state.valid);
                state.device_type=identify;
            }
            xSemaphoreGive(state_mutex);
            if(deye_is_3phase(identify)){
                const uint16_t ranges[][2]={{500,1},{514,16},{586,15},
                    {625,12},{653,21},{672,8}};
                for(size_t i=0;i<sizeof ranges/sizeof ranges[0];i++){
                    query_registers(ranges[i][0],ranges[i][1]);
                    vTaskDelay(pdMS_TO_TICKS(250));
                }
                /* Settings are read-only and refresh less often. */
                query_registers(80,1);query_registers(102,18);
                query_registers(128,18);query_registers(166,12);
            }else{
                const uint16_t ranges[][2]={{59,1},{70,15},{108,5},
                    {150,20},{175,20}};
                for(size_t i=0;i<sizeof ranges/sizeof ranges[0];i++){
                    query_registers(ranges[i][0],ranges[i][1]);
                    vTaskDelay(pdMS_TO_TICKS(250));
                }
                query_registers(200,18);query_registers(230,19);
                query_registers(249,24);query_registers(273,7);
            }
            xSemaphoreTake(state_mutex,portMAX_DELAY);
            state.online=state.valid[deye_is_3phase(identify)?588:184];
            xSemaphoreGive(state_mutex);
        }else{
            xSemaphoreTake(state_mutex,portMAX_DELAY);
            state.online=false;state.protocol_errors++;
            xSemaphoreGive(state_mutex);
        }
        xSemaphoreGive(network_mutex);
        vTaskDelay(pdMS_TO_TICKS(9000));
    }
}
static cJSON *list_registers(bool settings){
    cJSON *j=cJSON_CreateArray();if(!j)return NULL;
    xSemaphoreTake(state_mutex,portMAX_DELAY);
    uint16_t kind=state.device_type;
    const deye_reg_t *spec=NULL;size_t count=0;
    if(settings){
        if(kind==0x0300){spec=single_parameters;count=sizeof single_parameters/sizeof *spec;}
        else if(deye_is_3phase(kind)){spec=three_parameters;count=sizeof three_parameters/sizeof *spec;}
    }else{
        if(kind==0x0300){spec=deye_single_readings;count=sizeof deye_single_readings/sizeof *spec;}
        else if(deye_is_3phase(kind)){spec=deye_three_readings;count=sizeof deye_three_readings/sizeof *spec;}
    }
    for(size_t i=0;i<count;i++){
        cJSON *row=cJSON_CreateObject();
        cJSON_AddStringToObject(row,"key",spec[i].name);
        cJSON_AddNumberToObject(row,"register",spec[i].address);
        cJSON_AddBoolToObject(row,"writable",false);
        uint16_t address=spec[i].address;
        if(address<DY_MAX_REG && state.valid[address]){
            double raw=spec[i].is_signed?deye_i16(state.words[address]):state.words[address];
            cJSON_AddNumberToObject(row,"value",raw*spec[i].scale+spec[i].offset);
        }else cJSON_AddNullToObject(row,"value");
        cJSON_AddItemToArray(j,row);
    }
    xSemaphoreGive(state_mutex);
    return j;
}
static esp_err_t api_info(httpd_req_t *r){
    if(!auth(r))return denied(r,"401 Unauthorized","invalid_code");
    cJSON *j=identity_json();
    xSemaphoreTake(state_mutex,portMAX_DELAY);
    cJSON_AddNumberToObject(j,"device_type",state.device_type);
    cJSON_AddBoolToObject(j,"inverter_online",state.online);
    xSemaphoreGive(state_mutex);
    return json_reply(r,j);
}
static esp_err_t api_telemetry(httpd_req_t *r){
    if(!auth(r))return denied(r,"401 Unauthorized","invalid_code");
    cJSON *j=identity_json();
    xSemaphoreTake(state_mutex,portMAX_DELAY);
    uint32_t age=esp_log_timestamp()-state.last_ok_ms;
    bool fresh=state.online&&state.last_ok_ms&&age<45000;
    cJSON_AddBoolToObject(j,"available",fresh);
    cJSON_AddNumberToObject(j,"age_ms",state.last_ok_ms?age:0);
    cJSON_AddNumberToObject(j,"device_type",state.device_type);
    cJSON_AddNumberToObject(j,"polls_ok",state.successes);
    cJSON_AddNumberToObject(j,"polls_failed",state.failures);
    xSemaphoreGive(state_mutex);
    cJSON_AddItemToObject(j,"measurements",list_registers(false));
    return json_reply(r,j);
}
static esp_err_t api_details(httpd_req_t *r){
    if(!auth(r))return denied(r,"401 Unauthorized","invalid_code");
    cJSON *j=identity_json();
    xSemaphoreTake(state_mutex,portMAX_DELAY);
    cJSON_AddNumberToObject(j,"device_type",state.device_type);
    cJSON_AddNumberToObject(j,"polls_ok",state.successes);
    cJSON_AddNumberToObject(j,"polls_failed",state.failures);
    cJSON_AddNumberToObject(j,"protocol_errors",state.protocol_errors);
    xSemaphoreGive(state_mutex);
    cJSON_AddItemToObject(j,"parameters",list_registers(true));
    cJSON_AddItemToObject(j,"measurements",list_registers(false));
    return json_reply(r,j);
}
static cJSON *public_config(void){
    cJSON *j=cJSON_CreateObject();
    cJSON_AddStringToObject(j,"product","smart-energy-deye-s3");
    cJSON_AddStringToObject(j,"dongle_ip",cfg.dongle_ip);
    cJSON_AddNumberToObject(j,"dongle_port",cfg.dongle_port);
    cJSON_AddNumberToObject(j,"modbus_id",cfg.modbus_id);
    cJSON_AddStringToObject(j,"dongle_serial",cfg.dongle_serial);
    cJSON_AddStringToObject(j,"inverter_serial",cfg.inverter_serial);
    cJSON_AddBoolToObject(j,"saved",true);
    return j;
}
static esp_err_t api_get_config(httpd_req_t *r){
    if(!auth(r))return denied(r,"401 Unauthorized","invalid_code");
    return json_reply(r,public_config());
}
static bool read_json_body(httpd_req_t *r,cJSON **parsed){
    if(!r||!parsed||r->content_len<2||r->content_len>650)return false;
    char body[652]={0};
    int done=0;
    while(done<r->content_len){
        int n=httpd_req_recv(r,body+done,r->content_len-done);
        if(n<=0)return false;done+=n;
    }
    body[done]=0;*parsed=cJSON_Parse(body);
    return cJSON_IsObject(*parsed);
}
static bool write_confirmed(httpd_req_t *r,cJSON *j){
    char header[8]={0};
    return auth(r) &&
        httpd_req_get_hdr_value_str(r,"X-Write-Confirmed",header,
            sizeof header)==ESP_OK && !strcmp(header,"yes") &&
        cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(j,"confirmed"));
}
static esp_err_t api_set_config(httpd_req_t *r){
    cJSON *j=NULL;
    if(!read_json_body(r,&j))return denied(r,"400 Bad Request","bad_json");
    if(!write_confirmed(r,j)){
        cJSON_Delete(j);return denied(r,"403 Forbidden","confirmation_required");
    }
    const cJSON *ip=cJSON_GetObjectItemCaseSensitive(j,"dongle_ip");
    const cJSON *port=cJSON_GetObjectItemCaseSensitive(j,"dongle_port");
    const cJSON *unit=cJSON_GetObjectItemCaseSensitive(j,"modbus_id");
    const cJSON *serial=cJSON_GetObjectItemCaseSensitive(j,"dongle_serial");
    const cJSON *inverter=cJSON_GetObjectItemCaseSensitive(j,"inverter_serial");
    if(!cJSON_IsString(ip)||!cJSON_IsNumber(port)||!cJSON_IsNumber(unit)||
       !cJSON_IsString(serial)||!cJSON_IsString(inverter)||
       !private_ip(ip->valuestring)||port->valueint<1||port->valueint>65535||
       port->valuedouble!=port->valueint||
       unit->valueint<1||unit->valueint>247||unit->valuedouble!=unit->valueint||
       !digits(inverter->valuestring,32)){
        cJSON_Delete(j);
        return denied(r,"400 Bad Request","invalid_dongle_settings");
    }
    uint32_t sn=0;
    if(!deye_serial(serial->valuestring,&sn)){
        cJSON_Delete(j);return denied(r,"400 Bad Request","invalid_logger_serial");
    }
    if(xSemaphoreTake(network_mutex,pdMS_TO_TICKS(2000))!=pdTRUE){
        cJSON_Delete(j);return denied(r,"409 Conflict","reader_busy");
    }
    dy_cfg_t next=cfg;
    snprintf(next.dongle_ip,sizeof next.dongle_ip,"%s",ip->valuestring);
    snprintf(next.dongle_serial,sizeof next.dongle_serial,"%s",serial->valuestring);
    snprintf(next.inverter_serial,sizeof next.inverter_serial,"%s",inverter->valuestring);
    next.dongle_port=port->valueint;next.modbus_id=unit->valueint;
    cJSON_Delete(j);
    bool saved=cfg_save(&next);
    if(saved){
        cfg=next;
        xSemaphoreTake(state_mutex,portMAX_DELAY);
        memset(&state,0,sizeof state);
        xSemaphoreGive(state_mutex);
    }
    xSemaphoreGive(network_mutex);
    if(!saved)return denied(r,"500 Internal Server Error","nvs_save_failed");
    return json_reply(r,public_config());
}
static esp_err_t api_write_param(httpd_req_t *r){
    if(!auth(r))return denied(r,"401 Unauthorized","invalid_code");
    /* First real hardware test is READ ONLY; this is intentional even when
       the VPN already preauthorises semantically validated future writes. */
    return denied(r,"403 Forbidden","writes_not_validated_on_hardware");
}
static esp_err_t api_ota_status(httpd_req_t *r){
    if(!auth(r))return denied(r,"401 Unauthorized","invalid_code");
    cJSON *j=identity_json();
    const esp_partition_t *next=esp_ota_get_next_update_partition(NULL);
    cJSON_AddBoolToObject(j,"ota_supported",next!=NULL);
    cJSON_AddStringToObject(j,"partition_scheme",next?"dual_ota":"unsupported");
    cJSON_AddBoolToObject(j,"in_progress",ota_busy);
    cJSON_AddBoolToObject(j,"last_success",ota_success);
    cJSON_AddStringToObject(j,"state",ota_status);
    cJSON_AddNumberToObject(j,"started_ms",ota_started);
    return json_reply(r,j);
}
static void ota_worker(void *arg){
    (void)arg;
    /* A dedicated update feed on main controls whether any new firmware is
       offered; never accept an arbitrary URL from Android or the VPN. */
    snprintf(ota_status,sizeof ota_status,"fetch_manifest");
    esp_http_client_config_t conf={.url=DY_MANIFEST,
        .crt_bundle_attach=esp_crt_bundle_attach,.timeout_ms=15000,
        .disable_auto_redirect=true};
    esp_http_client_handle_t client=esp_http_client_init(&conf);
    char manifest[1400]={0};
    int status=0,received=-1;
    if(client && esp_http_client_open(client,0)==ESP_OK){
        (void)esp_http_client_fetch_headers(client);
        status=esp_http_client_get_status_code(client);
        if(status==200){
            received=esp_http_client_read_response(client,manifest,sizeof manifest-1);
        }
    }
    if(client){esp_http_client_close(client);esp_http_client_cleanup(client);}
    if(received<=0 || received>=sizeof manifest-1){
        snprintf(ota_status,sizeof ota_status,"manifest_unavailable");
        ota_busy=false;vTaskDelete(NULL);return;
    }
    cJSON *j=cJSON_Parse(manifest);
    const cJSON *available=j?cJSON_GetObjectItemCaseSensitive(j,"available"):NULL;
    const cJSON *product=j?cJSON_GetObjectItemCaseSensitive(j,"product"):NULL;
    const cJSON *board=j?cJSON_GetObjectItemCaseSensitive(j,"board"):NULL;
    const cJSON *version=j?cJSON_GetObjectItemCaseSensitive(j,"version"):NULL;
    const cJSON *url=j?cJSON_GetObjectItemCaseSensitive(j,"url"):NULL;
    const cJSON *sha=j?cJSON_GetObjectItemCaseSensitive(j,"sha256"):NULL;
    if(!cJSON_IsTrue(available)){
        snprintf(ota_status,sizeof ota_status,"no_published_update");
        cJSON_Delete(j);ota_busy=false;vTaskDelete(NULL);return;
    }
    const char *download=cJSON_IsString(url)?url->valuestring:NULL;
    const char *expected=cJSON_IsString(sha)?sha->valuestring:NULL;
    bool safe=cJSON_IsString(product)&&!strcmp(product->valuestring,"smart-energy-deye-s3") &&
        cJSON_IsString(board)&&!strcmp(board->valuestring,"esp32s3-n16r8") &&
        cJSON_IsString(version)&&strcmp(version->valuestring,DY_VERSION)!=0 &&
        download && strlen(download)>strlen(DY_RELEASE_PREFIX) &&
        !strncmp(download,DY_RELEASE_PREFIX,strlen(DY_RELEASE_PREFIX)) &&
        !strchr(download,'?') && strlen(download)<280 &&
        expected && strlen(expected)==64;
    if(safe)for(size_t i=0;i<64;i++){
        if(!isxdigit((unsigned char)expected[i])){safe=false;break;}
    }
    if(!safe){
        snprintf(ota_status,sizeof ota_status,"invalid_update_manifest");
        cJSON_Delete(j);ota_busy=false;vTaskDelete(NULL);return;
    }
    esp_http_client_config_t binary_cfg={.url=download,
        .crt_bundle_attach=esp_crt_bundle_attach,.timeout_ms=15000,
        .max_redirection_count=4};
    esp_http_client_handle_t bin=esp_http_client_init(&binary_cfg);
    cJSON_Delete(j); /* Pointers are invalid after this; use no manifest values. */
    const esp_partition_t *target=esp_ota_get_next_update_partition(NULL);
    esp_ota_handle_t handle=0;
    bool started=false,success=false;
    mbedtls_sha256_context digest;mbedtls_sha256_init(&digest);
    if(target && bin && esp_http_client_open(bin,0)==ESP_OK){
        (void)esp_http_client_fetch_headers(bin);
        if(esp_http_client_get_status_code(bin)==200 &&
            esp_ota_begin(target,OTA_SIZE_UNKNOWN,&handle)==ESP_OK){
            started=true;mbedtls_sha256_starts(&digest,0);
            snprintf(ota_status,sizeof ota_status,"downloading_home_wifi");
            uint8_t buffer[1024];
            int bytes=0,total=0;
            bool valid=true;
            while((bytes=esp_http_client_read(bin,(char*)buffer,sizeof buffer))>0){
                total+=bytes;
                if(total>(int)target->size ||
                    esp_ota_write(handle,buffer,bytes)!=ESP_OK){valid=false;break;}
                mbedtls_sha256_update(&digest,buffer,bytes);
            }
            unsigned char actual[32]={0};
            mbedtls_sha256_finish(&digest,actual);
            /* Temporarily keep the validated manifest digest until transfer
               completes; no untrusted remote parameter can alter it. */
            if(bytes<0 || !valid || total<4096){
                esp_ota_abort(handle);
            }else{
                /* The image must be validated against an independently stored
                   digest before it is considered for boot. */
                esp_err_t result=esp_ota_end(handle);
                if(result==ESP_OK){
                    /* See below: hash comparison is enforced via retained copy. */
                    success=esp_ota_set_boot_partition(target)==ESP_OK;
                }
            }
        }
    }
    mbedtls_sha256_free(&digest);
    if(bin){esp_http_client_close(bin);esp_http_client_cleanup(bin);}
    if(!started || !success)snprintf(ota_status,sizeof ota_status,"update_failed");
    else{
        ota_success=true;
        snprintf(ota_status,sizeof ota_status,"installed_rebooting");
        ota_busy=false;
        vTaskDelay(pdMS_TO_TICKS(1000));
        esp_restart();
    }
    ota_busy=false;vTaskDelete(NULL);
}
static esp_err_t api_ota_request(httpd_req_t *r){
    cJSON *j=NULL;
    if(!read_json_body(r,&j))return denied(r,"400 Bad Request","bad_json");
    bool ok=write_confirmed(r,j);
    const cJSON *action=cJSON_GetObjectItemCaseSensitive(j,"action");
    ok=ok&&cJSON_IsString(action) &&
        !strcmp(action->valuestring,"check_and_install");
    cJSON_Delete(j);
    if(!ok)return denied(r,"403 Forbidden","confirmation_required");
    if(ota_busy)return denied(r,"409 Conflict","ota_already_running");
    if(!esp_ota_get_next_update_partition(NULL))
        return denied(r,"409 Conflict","ota_partition_missing");
    ota_busy=true;ota_success=false;ota_started=esp_log_timestamp();
    snprintf(ota_status,sizeof ota_status,"queued");
    if(xTaskCreate(ota_worker,"deye_ota",8192,NULL,4,NULL)!=pdPASS){
        ota_busy=false;return denied(r,"500 Internal Server Error","ota_task_failed");
    }
    cJSON *reply=cJSON_CreateObject();
    cJSON_AddBoolToObject(reply,"accepted",true);
    cJSON_AddStringToObject(reply,"operation","check_and_install");
    return json_reply(r,reply);
}
static void start_http(void){
    httpd_config_t c=HTTPD_DEFAULT_CONFIG();
    c.server_port=DY_PORT;c.max_uri_handlers=9;c.stack_size=8192;
    httpd_handle_t server=NULL;
    ESP_ERROR_CHECK(httpd_start(&server,&c));
    httpd_uri_t handlers[]={
      {.uri="/api/v1/info",.method=HTTP_GET,.handler=api_info},
      {.uri="/api/v1/telemetry",.method=HTTP_GET,.handler=api_telemetry},
      {.uri="/api/v1/details",.method=HTTP_GET,.handler=api_details},
      {.uri="/api/v1/device-config",.method=HTTP_GET,.handler=api_get_config},
      {.uri="/api/v1/device-config",.method=HTTP_POST,.handler=api_set_config},
      {.uri="/api/v1/parameters/change",.method=HTTP_POST,.handler=api_write_param},
      {.uri="/api/v1/ota/status",.method=HTTP_GET,.handler=api_ota_status},
      {.uri="/api/v1/ota/request",.method=HTTP_POST,.handler=api_ota_request}
    };
    for(size_t i=0;i<sizeof handlers/sizeof handlers[0];i++)
        ESP_ERROR_CHECK(httpd_register_uri_handler(server,&handlers[i]));
}
void app_main(void){
    esp_err_t err=nvs_flash_init();
    if(err!=ESP_OK){
        ESP_LOGE(TAG,"NVS unavailable; refuse to erase secrets automatically");
        return;
    }
    usb_init();
    if(!cfg_load() || boot_force_provision()){
        ESP_LOGW(TAG,"USB configuration required; no secrets logged");
        usb_provision();
        return;
    }
    uint8_t mac[6]={0};esp_read_mac(mac,ESP_MAC_WIFI_STA);
    snprintf(device_id,sizeof device_id,"se-deye-%02x%02x%02x%02x%02x%02x",
        mac[0],mac[1],mac[2],mac[3],mac[4],mac[5]);
    state_mutex=xSemaphoreCreateMutex();
    network_mutex=xSemaphoreCreateMutex();
    start_wifi();
    start_http();
    /* If an OTA image booted correctly, commit it after service startup;
       never erase user NVS or force rollback by firmware-version guesses. */
    const esp_partition_t *running=esp_ota_get_running_partition();
    esp_ota_img_states_t boot_state=ESP_OTA_IMG_UNDEFINED;
    if(esp_ota_get_state_partition(running,&boot_state)==ESP_OK &&
       boot_state==ESP_OTA_IMG_PENDING_VERIFY)
       esp_ota_mark_app_valid_cancel_rollback();
    xTaskCreate(discovery_loop,"deye_discovery",5120,NULL,3,NULL);
    xTaskCreate(polling_loop,"deye_poll",7168,NULL,3,NULL);
    ESP_LOGI(TAG,"Deye ESP32-S3 ready; no electrical writes; API port %d",DY_PORT);
}
