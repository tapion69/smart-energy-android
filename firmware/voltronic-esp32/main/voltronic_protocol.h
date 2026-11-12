#pragma once
/*
 * Smart Energy Voltronic S3 – pure C read-only protocol.
 * Ported against tapion69/smart_voltronic/develop/smart-voltronic/flows.json:
 * "helpers", "construire commande", "parser" and "normalize state".
 *
 * Device-specific serial/HTTP work remains in main.c. This header is also
 * compiled by strict native host tests (no ESP-IDF dependency).
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <ctype.h>

#define VT_MAX_FRAME 512
#define VT_MAX_COMMAND 24

typedef struct {
    double grid_voltage_v, grid_hz, output_voltage_v, output_hz;
    double apparent_va, output_w, load_percent, bus_voltage_v;
    double battery_v, battery_charge_a, battery_discharge_a;
    double battery_soc_percent, pv1_current_a, pv1_voltage_v;
    double output_current_a, battery_net_current_a;
    double battery_voltage_from_scc_v;
    int heat_sink_c;
    bool grid_power_native_available;
    double grid_power_native_w;
    bool pv1_power_native_available;
    double pv1_power_native_w;
} vt_qpigs_t;

typedef struct { double current_a, voltage_v, power_w; } vt_qpigs2_t;

static inline uint16_t vt_crc16_xmodem(const uint8_t *bytes,size_t n) {
    uint16_t crc=0;
    for(size_t i=0;i<n;i++) {
        crc^=(uint16_t)bytes[i]<<8;
        for(int b=0;b<8;b++)
            crc=(crc&0x8000)?(uint16_t)((crc<<1)^0x1021):(uint16_t)(crc<<1);
    }
    return crc;
}
static inline uint8_t vt_crc_escape(uint8_t b) {
    return (b==0x28||b==0x0D||b==0x0A)?(uint8_t)(b+1):b;
}

/* Exact command allowlist: never permit user-supplied P... inverter SETs. */
static inline bool vt_read_command(const char *cmd) {
    if(!cmd)return false;
    const char *fixed[]={"QPIGS","QPIGS2","QMOD","QPIRI","QDOP","QPIWS",
        "QET","QLT","QMN","QID","QVFW","QVFW3"};
    for(size_t i=0;i<sizeof(fixed)/sizeof(fixed[0]);i++)
        if(strcmp(cmd,fixed[i])==0)return true;
    struct {const char *prefix;size_t date_len;} dated[]={
        {"QED",8},{"QLD",8},{"QEM",6},{"QLM",6},{"QEY",4},{"QLY",4}
    };
    for(size_t i=0;i<sizeof(dated)/sizeof(dated[0]);i++) {
        size_t k=strlen(dated[i].prefix);
        if(strncmp(cmd,dated[i].prefix,k)==0 &&
           strlen(cmd)==k+dated[i].date_len) {
            for(size_t j=k;j<k+dated[i].date_len;j++)
                if(cmd[j]<'0'||cmd[j]>'9')return false;
            return true;
        }
    }
    return false;
}

/* The proven Node-RED helper sends CRC high,low as RAW bytes + CR.
 * Prior S3 prototype escaped CRC bytes unconditionally, unlike that flow.
 * Only query bytes from the strict allowlist can reach the serial port.
 */
static inline size_t vt_make_command(const char *cmd,uint8_t out[VT_MAX_COMMAND]) {
    if(!vt_read_command(cmd))return 0;
    size_t n=strlen(cmd);
    if(n+3>VT_MAX_COMMAND)return 0;
    uint16_t crc=vt_crc16_xmodem((const uint8_t*)cmd,n);
    memcpy(out,cmd,n);
    out[n]=(uint8_t)(crc>>8);
    out[n+1]=(uint8_t)crc;
    out[n+2]=0x0D;
    return n+3;
}
typedef enum {
    VT_REPLY_MORE=0,
    VT_REPLY_VALID=1,
    VT_REPLY_NAK=2,
    VT_REPLY_BAD_CRC=-1,
    VT_REPLY_MALFORMED=-2,
    VT_REPLY_OVERFLOW=-3
} vt_reply_t;
typedef struct {
    uint8_t bytes[VT_MAX_FRAME];
    size_t len;
    uint32_t rx_bytes;
    uint32_t incomplete_frames;
    uint32_t overflow_frames;
} vt_rx_stream_t;

/* No assumptions about serial driver packet boundaries: one or many bytes
 * per call, replies split over arbitrary UART reads, terminate at CR. */
static inline vt_reply_t vt_rx_feed(vt_rx_stream_t *stream,uint8_t b) {
    stream->rx_bytes++;
    if(stream->len>=VT_MAX_FRAME) {
        stream->len=0;
        stream->overflow_frames++;
        return VT_REPLY_OVERFLOW;
    }
    stream->bytes[stream->len++]=b;
    if(b!=0x0D)return VT_REPLY_MORE;
    return VT_REPLY_VALID; /* Full frame; validate separately below. */
}
static inline void vt_rx_reset(vt_rx_stream_t *stream) { stream->len=0; }
static inline bool vt_is_nak(const uint8_t *frame,size_t n) {
    if(n==1&&frame[0]==0x15)return true;
    if(n>=4 && frame[0]=='(' && memcmp(frame,"(NAK",4)==0)return true;
    return false;
}
static inline vt_reply_t vt_classify_response(const uint8_t *frame,size_t n) {
    if(!frame||n==0||n>VT_MAX_FRAME)return VT_REPLY_MALFORMED;
    if(n==1&&frame[0]==0x15)return VT_REPLY_NAK;
    if(n<5||frame[0]!='('||frame[n-1]!=0x0D)return VT_REPLY_MALFORMED;
    uint16_t crc=vt_crc16_xmodem(frame,n-3);
    uint8_t hi=(uint8_t)(crc>>8),lo=(uint8_t)crc;
    /* Prefer unescaped CRC (Node-RED parser); accept escaped variants for
       compatible Axpert firmware revisions, but never skip CRC validation. */
    bool raw=(frame[n-3]==hi&&frame[n-2]==lo);
    bool escaped=(frame[n-3]==vt_crc_escape(hi) &&
                  frame[n-2]==vt_crc_escape(lo));
    if(!raw&&!escaped)return VT_REPLY_BAD_CRC;
    return vt_is_nak(frame,n)?VT_REPLY_NAK:VT_REPLY_VALID;
}
static inline bool vt_valid_response(const uint8_t *frame,size_t n) {
    return vt_classify_response(frame,n)==VT_REPLY_VALID ||
           vt_classify_response(frame,n)==VT_REPLY_NAK;
}
static inline bool vt_nak(const uint8_t *frame,size_t n) {
    return vt_classify_response(frame,n)==VT_REPLY_NAK;
}

/* Payload copy for alphanumeric QMOD/QPIWS/ID and numeric replies.
   Omits binary CRC and CR. Never return a raw serial frame to the client. */
static inline bool vt_payload_copy(const uint8_t *frame,size_t n,
                                   char *out,size_t capacity) {
    if(!out||capacity==0||vt_classify_response(frame,n)!=VT_REPLY_VALID)
        return false;
    size_t length=n-4;
    if(length>=capacity)return false;
    for(size_t i=0;i<length;i++) {
        uint8_t ch=frame[1+i];
        if(ch<32||ch>126)return false;
        out[i]=(char)ch;
    }
    out[length]=0;
    return true;
}
static inline size_t vt_fields(const uint8_t *frame,size_t n,
                               double *out,size_t cap) {
    if(!out||cap==0)return 0;
    char text[VT_MAX_FRAME];
    if(!vt_payload_copy(frame,n,text,sizeof(text)))return 0;
    size_t count=0;
    char *p=text;
    while(*p&&count<cap) {
        while(*p==' '||*p=='\t')p++;
        if(!*p)break;
        char *end=NULL;
        double v=strtod(p,&end);
        if(end==p||!isfinite(v)||(*end!=' '&&*end!='\t'&&*end!='\0'))
            return 0;
        out[count++]=v;
        p=end;
    }
    return count;
}
static inline bool vt_parse_energy(const uint8_t *frame,size_t n,double *wh) {
    double numbers[2];
    if(vt_fields(frame,n,numbers,2)!=1||numbers[0]<0||numbers[0]>1e12)
        return false;
    *wh=numbers[0];return true;
}
static inline bool vt_parse_qpigs(const uint8_t *frame,size_t n,vt_qpigs_t *out) {
    if(!out)return false;
    double f[40]={0};size_t count=vt_fields(frame,n,f,40);
    if(count<16||f[0]<0||f[0]>300||f[2]<0||f[2]>300||
       (f[1]!=0&&(f[1]<40||f[1]>65))||
       (f[3]!=0&&(f[3]<40||f[3]>65))||
       f[4]<0||f[4]>30000||f[5]<0||f[5]>30000||
       f[6]<0||f[6]>100||f[8]<0||f[8]>100||
       f[9]<0||f[9]>350||f[10]<0||f[10]>100||
       f[12]<0||f[12]>100||f[13]<0||f[13]>600||
       f[15]<0||f[15]>350)return false;
    vt_qpigs_t m={0};
    m.grid_voltage_v=f[0];m.grid_hz=f[1];
    m.output_voltage_v=f[2];m.output_hz=f[3];
    m.apparent_va=f[4];m.output_w=f[5];m.load_percent=f[6];
    m.bus_voltage_v=f[7];m.battery_v=f[8];m.battery_charge_a=f[9];
    m.battery_soc_percent=f[10];m.heat_sink_c=(int)f[11];
    m.pv1_current_a=f[12];m.pv1_voltage_v=f[13];
    m.battery_voltage_from_scc_v=f[14];
    m.battery_discharge_a=f[15];
    m.output_current_a=(f[2]>1)?f[5]/f[2]:0;
    m.battery_net_current_a=f[9]-f[15];
    if(count>24&&f[24]>=-30000&&f[24]<=30000) {
        m.grid_power_native_available=true;m.grid_power_native_w=f[24];
    }
    /* Matching native PV1 field, if present: Node-RED learns from candidates
       [19,20,18,17,16]. Without an approximately consistent P=V*I use only
       voltage/current values, never pretend PV1 power was measured. */
    const int candidates[]={19,20,18,17,16};
    double estimate=m.pv1_voltage_v*m.pv1_current_a;
    if(estimate>10)for(size_t j=0;j<sizeof(candidates)/sizeof(candidates[0]);j++) {
        int idx=candidates[j];
        if(count>(size_t)idx && f[idx]>=0 && f[idx]<=30000) {
            double ratio=f[idx]/estimate;
            if(ratio>0.8&&ratio<1.2) {
                m.pv1_power_native_available=true;
                m.pv1_power_native_w=f[idx];break;
            }
        }
    }
    *out=m;return true;
}
static inline bool vt_parse_qpigs2(const uint8_t *frame,size_t n,
                                    vt_qpigs2_t *out) {
    if(!out)return false;
    double f[8]={0};size_t count=vt_fields(frame,n,f,8);
    if(count<3||f[0]<0||f[0]>100||f[1]<0||f[1]>600||
       f[2]<0||f[2]>30000)return false;
    *out=(vt_qpigs2_t){.current_a=f[0],.voltage_v=f[1],.power_w=f[2]};
    return true;
}
static inline char vt_parse_qmod(const uint8_t *frame,size_t n) {
    char text[8];
    if(!vt_payload_copy(frame,n,text,sizeof(text))||strlen(text)!=1)
        return 0;
    char mode=text[0];
    return (mode=='P'||mode=='S'||mode=='L'||mode=='B'||mode=='F')?mode:0;
}
