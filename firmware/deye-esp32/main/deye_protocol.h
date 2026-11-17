#pragma once
/* Independently implemented from the documented Solarman V5 wire format.
 * FC03 read holding registers is the only electrical Modbus operation in
 * initial firmware v0.1. No raw write/register endpoint exists.
 */
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>

#define DEYE_V5_MAX_WORDS 24
#define DEYE_V5_RX_MAX 256
static inline uint16_t deye_crc16(const uint8_t *buf,size_t len){
    uint16_t crc=0xffff;
    for(size_t i=0;i<len;i++){
        crc^=buf[i];
        for(int j=0;j<8;j++)crc=(crc&1)?(crc>>1)^0xA001:(crc>>1);
    }
    return crc;
}
static inline uint8_t deye_checksum(const uint8_t *buf,size_t len){
    uint8_t sum=0;
    for(size_t i=0;i<len;i++)sum=(uint8_t)(sum+buf[i]);
    return sum;
}
static inline void deye_le16(uint8_t *p,uint16_t n){
    p[0]=(uint8_t)n;p[1]=(uint8_t)(n>>8);
}
static inline void deye_le32(uint8_t *p,uint32_t n){
    for(int i=0;i<4;i++)p[i]=(uint8_t)(n>>(8*i));
}
static inline uint16_t deye_u16le(const uint8_t *p){
    return (uint16_t)p[0]|((uint16_t)p[1]<<8);
}
static inline uint32_t deye_u32le(const uint8_t *p){
    return (uint32_t)p[0]|((uint32_t)p[1]<<8)|
           ((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);
}
static inline uint16_t deye_u16be(const uint8_t *p){
    return ((uint16_t)p[0]<<8)|p[1];
}
static inline int16_t deye_i16(uint16_t n){return (int16_t)n;}
static inline bool deye_serial(const char *src,uint32_t *out){
    if(!src||!out)return false;
    size_t n=strnlen(src,12);
    if(n==0||n>10)return false;
    uint64_t v=0;
    for(size_t i=0;i<n;i++){
        if(src[i]<'0'||src[i]>'9')return false;
        v=v*10+(unsigned)(src[i]-'0');
        if(v>UINT32_MAX)return false;
    }
    if(!v)return false;
    *out=(uint32_t)v;return true;
}
static inline size_t deye_v5_make_fc03(uint8_t *out,size_t cap,
        uint32_t serial,uint8_t seq,uint8_t slave,uint16_t address,uint16_t count){
    if(!out||cap<36||!serial||!slave||slave>247||!count||
       count>DEYE_V5_MAX_WORDS || address>65535-count)return 0;
    uint8_t rtu[8]={
        slave,3,(uint8_t)(address>>8),(uint8_t)address,
        (uint8_t)(count>>8),(uint8_t)count,0,0
    };
    uint16_t crc=deye_crc16(rtu,6);
    deye_le16(rtu+6,crc);
    memset(out,0,36);
    out[0]=0xa5;deye_le16(out+1,15+sizeof rtu);
    deye_le16(out+3,0x4510);
    out[5]=seq;out[6]=0;deye_le32(out+7,serial);
    out[11]=2; /* frame type inverter; remaining 14 request bytes = 0 */
    memcpy(out+26,rtu,sizeof rtu);
    out[34]=deye_checksum(out+1,33);
    out[35]=0x15;
    return 36;
}
/* The caller already read 11+payload_len+2 exactly from TCP.
 * Reject any wrong serial, sequence, control code, checksum, CRC, or count.
 * Some Deye loggers append exactly two erroneous zero bytes after valid CRC.
 */
static inline bool deye_v5_parse_fc03(const uint8_t *packet,size_t len,
        uint32_t serial,uint8_t seq,uint8_t slave,uint16_t count,
        uint16_t *words){
    if(!packet||!words||len<11+14+5+2 || packet[0]!=0xa5 ||
       packet[len-1]!=0x15 || deye_u16le(packet+1)!=(len-13) ||
       deye_u16le(packet+3)!=0x1510 || packet[5]!=seq ||
       deye_u32le(packet+7)!=serial ||
       packet[len-2]!=deye_checksum(packet+1,len-3))return false;
    size_t payload=deye_u16le(packet+1);
    if(payload<14+5 || packet[11]!=2)return false;
    const uint8_t *rtu=packet+11+14;
    size_t n=payload-14;
    if(n<5 || rtu[0]!=slave || rtu[1]!=3 ||
       rtu[2]!=(uint8_t)(count*2))return false;
    size_t expected=3+count*2+2;
    if(n!=expected && !(n==expected+2 &&
        rtu[expected]==0 && rtu[expected+1]==0))return false;
    if(deye_crc16(rtu,expected-2)!=deye_u16le(rtu+expected-2))return false;
    for(int i=0;i<count;i++)words[i]=deye_u16be(rtu+3+2*i);
    return true;
}
typedef struct {
    const char *name; uint16_t address;
    uint8_t is_signed; float scale; float offset;
} deye_reg_t;
/* Documented DEYE hybrid maps, addresses ZERO-BASED.
 * Single phase register 0=0x0300; three phase 0x0500/0x0600/0x0601.
 * Never assume that the other family's addresses have the same meaning.
 * Values are observational only pending field confirmation per model.
 */
static const deye_reg_t deye_single_readings[]={
 {"run_state",59,0,1,0},{"pv_today_kwh",108,0,0.1f,0},
 {"pv1_voltage_v",109,0,0.1f,0},{"pv1_current_a",110,0,0.1f,0},
 {"pv2_voltage_v",111,0,0.1f,0},{"pv2_current_a",112,0,0.1f,0},
 {"pv1_power_w",186,0,1,0},{"pv2_power_w",187,0,1,0},
 {"battery_soc_pct",184,0,1,0},{"battery_voltage_v",183,0,0.01f,0},
 {"battery_power_w",190,1,1,0},{"battery_current_a",191,1,0.01f,0},
 {"battery_temperature_c",182,0,0.1f,-100},
 {"grid_power_w",169,1,1,0},{"grid_voltage_v",150,0,0.1f,0},
 {"grid_frequency_hz",79,0,0.01f,0},{"grid_relay",194,0,1,0},
 {"load_power_w",178,1,1,0},{"inverter_power_w",175,1,1,0},
 {"inverter_frequency_hz",193,0,0.01f,0},
 {"battery_charge_today_kwh",70,0,0.1f,0},
 {"battery_discharge_today_kwh",71,0,0.1f,0},
 {"grid_buy_today_kwh",76,0,0.1f,0},{"grid_sell_today_kwh",77,0,0.1f,0},
 {"load_today_kwh",84,0,0.1f,0}
};
static const deye_reg_t deye_three_readings[]={
 {"run_state",500,0,1,0},{"pv_today_kwh",529,0,0.1f,0},
 {"pv1_power_w",672,0,1,0},{"pv2_power_w",673,0,1,0},
 {"pv1_voltage_v",676,0,0.1f,0},{"pv1_current_a",677,0,0.1f,0},
 {"pv2_voltage_v",678,0,0.1f,0},{"pv2_current_a",679,0,0.1f,0},
 {"battery_soc_pct",588,0,1,0},{"battery_voltage_v",587,0,0.01f,0},
 {"battery_power_w",590,1,1,0},
 {"battery_temperature_c",586,0,0.1f,-100},
 {"grid_voltage_a_v",598,0,0.1f,0},
 {"grid_voltage_b_v",599,0,0.1f,0},
 {"grid_voltage_c_v",600,0,0.1f,0},
 {"battery_charge_today_kwh",514,0,0.1f,0},
 {"battery_discharge_today_kwh",515,0,0.1f,0},
 {"grid_buy_today_kwh",520,0,0.1f,0},
 {"grid_sell_today_kwh",521,0,0.1f,0},
 {"load_today_kwh",526,0,0.1f,0}
};
/* Phase-specific 3P high-word power registers not emitted until the
 * inverter model and word order are checked in hardware.
 */
static inline bool deye_type_valid(uint16_t code){
    return code==0x0300||code==0x0500||code==0x0600||code==0x0601;
}
/* The map at 500..679 is documented for 3P LOW-VOLTAGE 0x0500.
 * HV 0x0600/0x0601 must not be decoded using these same addresses. */
static inline bool deye_is_3phase(uint16_t code){return code==0x0500;}
static inline bool deye_is_unmapped_hv(uint16_t code){
    return code==0x0600||code==0x0601;
}
