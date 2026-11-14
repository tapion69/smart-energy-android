#pragma once
/* Smart Energy Voltronic: explicit semantic SET allowlist.
 * Pure C so firmware/host tests can verify each parameter without hardware.
 * Never accept an arbitrary command string over HTTP. */
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <math.h>

typedef struct {
    const char *key;
    const char *query;
    const char *opcode;
    unsigned char index;
    double min_value, max_value, step, tolerance;
    bool voltage;
} vt_edit_spec_t;

/* Exact native indices match /details, not translated telemetry.
 * Candidate opcodes match the original Smart Voltronic flow, with no
 * ambiguous fallback and no auto-learning before verified readback. */
static const vt_edit_spec_t VT_EDIT_SPECS[] = {
    {"battery_recharge_voltage_v","QPIRI","PBCV",8,12,65,0.1,0.11,true},
    {"battery_under_voltage_v","QPIRI","PSDV",9,12,65,0.1,0.11,true},
    {"battery_bulk_voltage_v","QPIRI","PCVV",10,12,65,0.1,0.11,true},
    {"battery_float_voltage_v","QPIRI","PBFT",11,12,65,0.1,0.11,true},
    {"battery_type_code","QPIRI","PBT",12,0,9,1,0.01,false},
    {"max_ac_charging_current_a","QPIRI","MUCHGC",13,0,200,10,1,false},
    {"max_charging_current_a","QPIRI","MNCHGC",14,0,200,10,1,false},
    {"output_source_priority_code","QPIRI","POP",16,0,2,1,0.01,false},
    {"charger_source_priority_code","QPIRI","PCP",17,1,3,1,0.01,false},
    {"battery_re_discharge_voltage_v","QPIRI","PBDV",22,12,65,0.1,0.11,true},
    {"max_discharging_current_a","QPIRI","PBATMAXDISC",27,10,200,10,1,false},
    {"battery_recharge_capacity_pct","QDOP","PBCC",8,5,95,1,0.01,false},
    {"battery_redischarge_capacity_pct","QDOP","PBDC",9,10,100,1,0.01,false},
    {"battery_under_capacity_pct","QDOP","PSDC",10,0,90,1,0.01,false}
};
static inline const vt_edit_spec_t *vt_edit_find(const char *key){
    if(!key)return NULL;
    for(size_t i=0;i<sizeof(VT_EDIT_SPECS)/sizeof(VT_EDIT_SPECS[0]);i++)
        if(strcmp(VT_EDIT_SPECS[i].key,key)==0)return &VT_EDIT_SPECS[i];
    return NULL;
}
static inline bool vt_edit_allowed(const vt_edit_spec_t *s,
                                   double current,double target,int nominal_v){
    if(!s||!isfinite(current)||!isfinite(target)||
       current<s->min_value||current>s->max_value||
       target<s->min_value||target>s->max_value||
       fabs(target-current)<=s->tolerance)return false;
    double steps=target/s->step;
    if(fabs(steps-round(steps))>0.00001)return false;
    if(s->voltage) {
        if(nominal_v==48) {
            if(target<40.0||target>60.0)return false;
        }else if(nominal_v==24) {
            if(target<20.0||target>30.0)return false;
        }else return false;
    }
    if(strcmp(s->key,"battery_type_code")==0) {
        int code=(int)target;
        if(!(code==0||code==1||code==2||code==3||
             code==5||code==6||code==8||code==9))return false;
    }
    return true;
}
static inline bool vt_edit_command(const vt_edit_spec_t *s,double target,
                                   char *out,size_t capacity) {
    if(!s||!out||capacity<12||!isfinite(target))return false;
    int written=0;
    if(s->voltage)
        written=snprintf(out,capacity,"%s%.1f",s->opcode,target);
    else if(strcmp(s->opcode,"POP")==0||
            strcmp(s->opcode,"PCP")==0||
            strcmp(s->opcode,"PBT")==0)
        written=snprintf(out,capacity,"%s%02d",s->opcode,(int)target);
    else if(strcmp(s->opcode,"MUCHGC")==0||
            strcmp(s->opcode,"MNCHGC")==0)
        written=snprintf(out,capacity,"%s0%03d",s->opcode,(int)target);
    else
        written=snprintf(out,capacity,"%s%03d",s->opcode,(int)target);
    return written>0 && (size_t)written<capacity;
}
