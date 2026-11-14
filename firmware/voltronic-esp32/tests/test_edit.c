#include <assert.h>
#include <string.h>
#include "../main/voltronic_edit.h"

int main(void) {
    const vt_edit_spec_t *v=vt_edit_find("battery_float_voltage_v");
    assert(v && v->index==11 && strcmp(v->query,"QPIRI")==0);
    assert(vt_edit_allowed(v,55.2,54.8,48));
    assert(!vt_edit_allowed(v,55.2,68.0,48));
    assert(!vt_edit_allowed(v,55.2,54.85,48));
    assert(!vt_edit_allowed(v,55.2,54.8,0));
    char cmd[48]={0};
    assert(vt_edit_command(v,54.8,cmd,sizeof(cmd)));
    assert(strcmp(cmd,"PBFT54.8")==0);
    const vt_edit_spec_t *m=vt_edit_find("max_discharging_current_a");
    assert(m && vt_edit_allowed(m,100,120,48));
    assert(!vt_edit_allowed(m,100,125,48));
    assert(vt_edit_command(m,120,cmd,sizeof(cmd)));
    assert(strcmp(cmd,"PBATMAXDISC120")==0);
    const vt_edit_spec_t *p=vt_edit_find("output_source_priority_code");
    assert(p && vt_edit_allowed(p,0,2,48));
    assert(vt_edit_command(p,2,cmd,sizeof(cmd)));
    assert(strcmp(cmd,"POP02")==0);
    const vt_edit_spec_t *b=vt_edit_find("battery_type_code");
    assert(b && !vt_edit_allowed(b,2,4,48));
    assert(!vt_edit_find("raw_command"));
    assert(!vt_edit_find("erase_flash"));
    const vt_edit_spec_t *q=vt_edit_find("battery_under_capacity_pct");
    assert(q && strcmp(q->query,"QDOP")==0 && q->index==10);
    return 0;
}
