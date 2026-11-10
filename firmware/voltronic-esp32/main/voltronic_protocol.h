#pragma once
/* Minimal Voltronic/Axpert RS232 READ-ONLY serial framing and conservative parser.
 * Adapted from the owner's Smart Voltronic Node-RED helpers/normalizeState,
 * NOT a copy of the large flow. No write/set commands can be generated.
 * Licensed as part of the Smart Energy repository.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

typedef struct {
    double grid_voltage_v, grid_hz, output_voltage_v, output_hz;
    double apparent_va, output_w, load_percent;
    double battery_v, battery_charge_a, battery_discharge_a;
    double battery_soc_percent, pv1_current_a, pv1_voltage_v;
    double output_current_a, battery_net_current_a;
    int heat_sink_c;
    bool grid_power_native_available;
    double grid_power_native_w;
} vt_qpigs_t;

typedef struct {
    double current_a, voltage_v, power_w;
} vt_qpigs2_t;

static inline uint16_t vt_crc16_xmodem(const uint8_t *bytes, size_t length) {
    uint16_t crc=0;
    for(size_t i=0;i<length;i++) {
        crc ^= (uint16_t)bytes[i] << 8;
        for(int bit=0;bit<8;bit++)
            crc = (crc & 0x8000) ? (uint16_t)((crc<<1)^0x1021) : (uint16_t)(crc<<1);
    }
    return crc;
}
static inline uint8_t vt_crc_byte(uint8_t b) {
    return (b==0x28 || b==0x0d || b==0x0a) ? (uint8_t)(b+1) : b;
}
static inline bool vt_read_command(const char *command) {
    return strcmp(command,"QPIGS")==0 || strcmp(command,"QPIGS2")==0 ||
        strcmp(command,"QMOD")==0 || strcmp(command,"QPIRI")==0 ||
        strcmp(command,"QPIWS")==0;
}
static inline size_t vt_make_command(const char *command, uint8_t out[16]) {
    if(!command || !vt_read_command(command))return 0;
    size_t n=strlen(command);
    uint16_t crc=vt_crc16_xmodem((const uint8_t*)command,n);
    memcpy(out,command,n);
    out[n]=vt_crc_byte((uint8_t)(crc>>8));
    out[n+1]=vt_crc_byte((uint8_t)crc);
    out[n+2]=13;
    return n+3;
}
static inline bool vt_valid_response(const uint8_t *frame,size_t n) {
    if(!frame || n<5 || n>512 || frame[0]!='(' || frame[n-1]!=13)return false;
    const uint16_t crc=vt_crc16_xmodem(frame,n-3);
    return frame[n-3]==vt_crc_byte((uint8_t)(crc>>8)) &&
           frame[n-2]==vt_crc_byte((uint8_t)crc);
}
static inline bool vt_nak(const uint8_t *frame,size_t n) {
    return vt_valid_response(frame,n) && n>=7 && memcmp(frame,"(NAK",4)==0;
}
static inline size_t vt_fields(const uint8_t *frame,size_t n,double *out,size_t cap) {
    if(!vt_valid_response(frame,n) || cap==0)return 0;
    // Copy payload excluding '(' CRC-16 and CR. Numeric fields only.
    char text[510];
    size_t payload_len=n-4;
    if(payload_len==0 || payload_len>=sizeof(text))return 0;
    memcpy(text,frame+1,payload_len);text[payload_len]=0;
    size_t count=0;
    char *cursor=text;
    while(*cursor && count<cap){
        while(*cursor==' ' || *cursor=='\t')cursor++;
        if(!*cursor)break;
        char *end=NULL;
        double value=strtod(cursor,&end);
        if(end==cursor || !isfinite(value) ||
           (*end!=' ' && *end!='\t' && *end!='\0'))return 0;
        out[count++]=value;
        cursor=end;
    }
    return count;
}
static inline bool vt_parse_qpigs(const uint8_t *frame,size_t n,vt_qpigs_t *out) {
    if(!out)return false;
    double f[40]={0};
    size_t count=vt_fields(frame,n,f,40);
    if(count<16 || f[0]<0 || f[0]>300 || f[2]<0 || f[2]>300 ||
       (f[1]!=0 && (f[1]<40 || f[1]>65)) ||
       (f[3]!=0 && (f[3]<40 || f[3]>65)) ||
       f[4]<0 || f[4]>30000 || f[5]<0 || f[5]>30000 ||
       f[6]<0 || f[6]>100 || f[8]<0 || f[8]>100 ||
       f[9]<0 || f[9]>350 || f[10]<0 || f[10]>100 ||
       f[12]<0 || f[12]>100 || f[13]<0 || f[13]>600 ||
       f[15]<0 || f[15]>350)return false;
    vt_qpigs_t m={0};
    m.grid_voltage_v=f[0];m.grid_hz=f[1];
    m.output_voltage_v=f[2];m.output_hz=f[3];
    m.apparent_va=f[4];m.output_w=f[5];m.load_percent=f[6];
    m.battery_v=f[8];m.battery_charge_a=f[9];
    m.battery_soc_percent=f[10];m.heat_sink_c=(int)f[11];
    m.pv1_current_a=f[12];m.pv1_voltage_v=f[13];
    m.battery_discharge_a=f[15];
    m.output_current_a=(f[2]>1) ? (f[5]/f[2]) : 0;
    m.battery_net_current_a=f[9]-f[15];
    // Native grid power is optional and model-dependent; don't fake a value.
    if(count>24 && f[24]>=0 && f[24]<=30000) {
        m.grid_power_native_available=true;
        m.grid_power_native_w=f[24];
    }
    *out=m;
    return true;
}
static inline bool vt_parse_qpigs2(const uint8_t *frame,size_t n,vt_qpigs2_t *out) {
    if(!out)return false;
    double f[8]={0};size_t count=vt_fields(frame,n,f,8);
    if(count<3 || f[0]<0 || f[0]>100 || f[1]<0 || f[1]>600 ||
        f[2]<0 || f[2]>30000)return false;
    *out=(vt_qpigs2_t){.current_a=f[0],.voltage_v=f[1],.power_w=f[2]};
    return true;
}
static inline char vt_parse_qmod(const uint8_t *frame,size_t n) {
    if(!vt_valid_response(frame,n)||n!=5)return 0;
    char mode=(char)frame[1];
    return (mode=='P'||mode=='S'||mode=='L'||mode=='B'||mode=='F') ? mode : 0;
}
