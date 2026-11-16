#pragma once
/* Strict fixed-path policy. Never accept a caller-supplied LAN IP or URL. */
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <ctype.h>

typedef enum {
    SE_WRITE_DENIED=0,
    SE_WRITE_JK_REFRESH,
    SE_WRITE_JK_ARM,
    SE_WRITE_JK_COMMIT,
    SE_WRITE_JK_STATUS,
    SE_WRITE_VOLTRONIC_PARAM,
    SE_WRITE_DEYE_CONFIG,
    SE_WRITE_DEYE_PARAM,
    SE_WRITE_DEYE_OTA_TRIGGER
} se_write_kind_t;

typedef struct {
    int slot; /* 1..3 */
    se_write_kind_t kind;
    const char *upstream_path;
} se_write_route_t;

static inline se_write_route_t se_write_route(const char *uri,bool is_post) {
    se_write_route_t denied={0,SE_WRITE_DENIED,NULL};
    if(!uri)return denied;
    for(int slot=1;slot<=3;slot++) {
        char prefix[40];
        snprintf(prefix,sizeof prefix,"/api/v1/jk/%d/",slot);
        const size_t prefix_len=strlen(prefix);
        if(!strncmp(uri,prefix,prefix_len)) {
            const char *name=uri+prefix_len;
            if(is_post && !strcmp(name,"settings-refresh"))
                return (se_write_route_t){slot,SE_WRITE_JK_REFRESH,
                    "/api/v1/jk/settings-refresh"};
            if(is_post && !strcmp(name,"write-arm"))
                return (se_write_route_t){slot,SE_WRITE_JK_ARM,
                    "/api/v1/jk/write-arm"};
            if(is_post && !strcmp(name,"write"))
                return (se_write_route_t){slot,SE_WRITE_JK_COMMIT,
                    "/api/v1/jk/write"};
            if(!is_post && (!strcmp(name,"write-status") ||
                            !strncmp(name,"write-status?",13)))
                return (se_write_route_t){slot,SE_WRITE_JK_STATUS,
                    "/api/v1/jk/write-status"};
            return denied;
        }
        snprintf(prefix,sizeof prefix,"/api/v1/voltronic/%d/parameters/change",slot);
        if(is_post && !strcmp(uri,prefix))
            return (se_write_route_t){slot,SE_WRITE_VOLTRONIC_PARAM,
                "/api/v1/parameters/change"};
        /* Deye is another ESP32 inverter reader on the SAME provisioned slot.
           Never forward arbitrary Modbus registers, LAN hosts or OTA binaries. */
        snprintf(prefix,sizeof prefix,"/api/v1/deye/%d/",slot);
        const size_t deye_prefix_len=strlen(prefix);
        if(!strncmp(uri,prefix,deye_prefix_len)){
            const char *name=uri+deye_prefix_len;
            if(is_post && !strcmp(name,"device-config"))
                return (se_write_route_t){slot,SE_WRITE_DEYE_CONFIG,
                    "/api/v1/device-config"};
            if(is_post && !strcmp(name,"parameters/change"))
                return (se_write_route_t){slot,SE_WRITE_DEYE_PARAM,
                    "/api/v1/parameters/change"};
            if(is_post && !strcmp(name,"ota/request"))
                return (se_write_route_t){slot,SE_WRITE_DEYE_OTA_TRIGGER,
                    "/api/v1/ota/request"};
            return denied;
        }
    }
    return denied;
}

static inline bool se_write_mac(const char *mac) {
    if(!mac || strlen(mac)!=17)return false;
    for(int i=0;i<17;i++) {
        const unsigned char c=(unsigned char)mac[i];
        if(i==2||i==5||i==8||i==11||i==14) {
            if(c!=':')return false;
        } else if(!isxdigit(c))return false;
    }
    return true;
}

/* Voltronic only accepts a known semantic key, never serial opcodes. */
static inline bool se_write_voltronic_key(const char *key) {
    static const char *keys[]={
        "battery_recharge_voltage_v","battery_under_voltage_v",
        "battery_bulk_voltage_v","battery_float_voltage_v",
        "battery_type_code","max_ac_charging_current_a",
        "max_charging_current_a","output_source_priority_code",
        "charger_source_priority_code","battery_re_discharge_voltage_v",
        "max_discharging_current_a","battery_recharge_capacity_pct",
        "battery_redischarge_capacity_pct","battery_under_capacity_pct"
    };
    if(!key)return false;
    for(size_t i=0;i<sizeof keys/sizeof keys[0];i++)
        if(!strcmp(key,keys[i]))return true;
    return false;
}

/* The ESP32 Deye owns the Modbus register allowlist. The VPN accepts only
   semantic parameter identifiers, never raw register addresses or opcodes. */
static inline bool se_write_deye_key(const char *key){
    if(!key)return false;
    size_t length=strnlen(key,65);
    if(length==0 || length>=65)return false;
    if(!(key[0]>='a' && key[0]<='z'))return false;
    for(size_t i=1;i<length;i++){
        char c=key[i];
        if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='_'))
            return false;
    }
    return true;
}
