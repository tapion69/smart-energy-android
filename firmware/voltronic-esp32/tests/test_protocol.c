#include "../main/voltronic_protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

/* Native-host regression suite mirrored from the working Node-RED flow.
 * Test-only synthetic replies are never presented as real inverter readings. */
static size_t response(const char *body,uint8_t *out) {
    size_t n=strlen(body);
    memcpy(out,body,n);
    uint16_t crc=vt_crc16_xmodem(out,n);
    out[n]=(uint8_t)(crc>>8);out[n+1]=(uint8_t)crc;out[n+2]=13;
    return n+3;
}
static void test_command_crc_and_whitelist(void) {
    uint8_t out[VT_MAX_COMMAND]={0};
    const uint8_t expected[]={'Q','P','I','G','S',0xb7,0xa9,13};
    assert(vt_make_command("QPIGS",out)==sizeof(expected));
    /* Host regression on command legality: polling must never introduce SET. */
    assert(memcmp(out,expected,sizeof(expected))==0);
    assert(vt_make_command("QMOD",out)==7);
    assert(vt_make_command("QPIWS",out)>0);
    assert(vt_make_command("QDOP",out)>0);
    assert(vt_make_command("QMN",out)>0);
    assert(vt_make_command("QET",out)>0);
    assert(vt_make_command("QLT",out)>0);
    assert(vt_make_command("QED20261112",out)>0);
    assert(vt_make_command("QEM202611",out)>0);
    assert(vt_make_command("QEY2026",out)>0);
    assert(vt_make_command("QLD20261112",out)>0);
    assert(vt_make_command("QPIWSX",out)==0);
    assert(vt_make_command("QED202611",out)==0);
    assert(vt_make_command("QPIGS\001",out)==0);
    assert(vt_make_command("POP00",out)==0);
    assert(vt_make_command("PBCV55.2",out)==0);
    assert(vt_make_command("PBATMAXDISC150",out)==0);
    assert(vt_make_command("PCP01",out)==0);
}
static void test_streaming_frames(void) {
    uint8_t f[512]={0};size_t n=response("(B",f);
    vt_rx_stream_t rx={0};vt_reply_t result=VT_REPLY_MORE;
    for(size_t i=0;i<n;i++) {
        result=vt_rx_feed(&rx,f[i]);
        assert(result==(i==n-1?VT_REPLY_VALID:VT_REPLY_MORE));
    }
    assert(rx.len==n&&rx.rx_bytes==n);
    assert(vt_classify_response(rx.bytes,rx.len)==VT_REPLY_VALID);
    assert(vt_parse_qmod(rx.bytes,rx.len)=='B');
    vt_rx_reset(&rx);assert(rx.len==0);
    assert(vt_classify_response(f,n)==VT_REPLY_VALID);
    f[n-2]^=0x41;assert(vt_classify_response(f,n)==VT_REPLY_BAD_CRC);
    f[0]='Q';assert(vt_classify_response(f,n)==VT_REPLY_MALFORMED);
    n=response("(NAK",f);
    assert(vt_classify_response(f,n)==VT_REPLY_NAK);
    assert(vt_nak(f,n));
    f[0]=0x15;assert(vt_classify_response(f,1)==VT_REPLY_NAK);
}
static void test_qpigs_qpigs2(void) {
    uint8_t f[512]={0};
    size_t n=response("(230.1 50.0 230.0 50.0 0120 0110 009 365 52.1 010 063 032 0.5 357.0 52.2 006",f);
    vt_qpigs_t m={0};
    assert(vt_parse_qpigs(f,n,&m));
    assert(m.grid_voltage_v==230.1 && m.output_w==110);
    assert(m.battery_v==52.1 && m.battery_net_current_a==4.0);
    assert(m.bus_voltage_v==365.0);
    assert(!m.grid_power_native_available && !m.pv1_power_native_available);
    n=response("(0.7 355.0 245",f);
    vt_qpigs2_t m2={0};
    assert(vt_parse_qpigs2(f,n,&m2));assert(m2.power_w==245);
    n=response("(230.1 50.0 230.0 50.0 0120 0110 999 365 52.1 010 063 032 0.5 357.0 52.2 006",f);
    assert(!vt_parse_qpigs(f,n,&m));
}
static void test_identification_read_only(void) {
    uint8_t frame[512]={0};
    char data[96]={0};
    size_t n=response("(TEST-AXPERT-S3",frame);
    assert(vt_payload_copy(frame,n,data,sizeof(data)));
    assert(strcmp(data,"TEST-AXPERT-S3")==0);
    frame[n-2]^=0x01;
    assert(!vt_payload_copy(frame,n,data,sizeof(data)));
    uint8_t command[VT_MAX_COMMAND];
    assert(vt_make_command("QMN",command)>0);
    assert(vt_make_command("QID",command)>0);
    assert(vt_make_command("QVFW",command)>0);
    assert(vt_make_command("QVFW3",command)>0);
    assert(vt_make_command("PBCV55.2",command)==0);
}
static void test_numeric_energy_and_warning(void) {
    uint8_t f[512]={0};double v=0;
    size_t n=response("(10411700",f);
    assert(vt_parse_energy(f,n,&v)&&v==10411700);
    n=response("(000001000 100",f);
    assert(!vt_parse_energy(f,n,&v));
    n=response("(00000000000000000000000000000000",f);
    char flags[64]={0};
    assert(vt_payload_copy(f,n,flags,sizeof(flags)));
    assert(strlen(flags)==32);
    n=response("(B",f);
    assert(vt_parse_qmod(f,n)=='B');
    n=response("(S",f);
    assert(vt_parse_qmod(f,n)=='S');
}
int main(void) {
    test_command_crc_and_whitelist();
    test_streaming_frames();
    test_qpigs_qpigs2();
    test_numeric_energy_and_warning();
    test_identification_read_only();
    puts("Voltronic complete native read-only serial protocol: PASS");
    return 0;
}
