#include "../main/voltronic_protocol.h"
#include <assert.h>
#include <stdio.h>
static size_t make_response(const char *body,uint8_t *buffer) {
    const size_t n=strlen(body);
    memcpy(buffer,body,n);
    uint16_t crc=vt_crc16_xmodem(buffer,n);
    buffer[n]=vt_crc_byte((uint8_t)(crc>>8));
    buffer[n+1]=vt_crc_byte((uint8_t)crc);
    buffer[n+2]=13;
    return n+3;
}
int main(void) {
    uint8_t frame[512]={0};
    const uint8_t qpigs_expected[]={'Q','P','I','G','S',0xb7,0xa9,13};
    assert(vt_make_command("QPIGS",frame)==sizeof qpigs_expected);
    assert(memcmp(frame,qpigs_expected,sizeof qpigs_expected)==0);
    assert(vt_make_command("PBCV",frame)==0);
    assert(vt_make_command("POP00",frame)==0);
    assert(vt_make_command("QMOD",frame)==7);
    size_t n=make_response("(B",frame);
    assert(vt_valid_response(frame,n));assert(vt_parse_qmod(frame,n)=='B');
    frame[n-2]^=1;assert(!vt_valid_response(frame,n));
    n=make_response("(NAK",frame);assert(vt_nak(frame,n));
    n=make_response("(230.1 50.0 230.0 50.0 0120 0110 009 365 52.1 010 063 032 0.5 357.0 52.2 006",frame);
    vt_qpigs_t p={0};
    assert(vt_parse_qpigs(frame,n,&p));
    assert(p.output_w==110 && p.battery_v==52.1);
    assert(p.battery_net_current_a==4.0);
    assert(p.grid_power_native_available==false);
    n=make_response("(0.7 355.0 245",frame);
    vt_qpigs2_t p2={0};
    assert(vt_parse_qpigs2(frame,n,&p2));
    assert(p2.power_w==245.0);
    n=make_response("(230.1 50.0 230.0 50.0 0120 0110 999 365 52.1 010 063 032 0.5 357.0 52.2 006",frame);
    assert(!vt_parse_qpigs(frame,n,&p));
    puts("Voltronic read-only protocol: PASS");
    return 0;
}
