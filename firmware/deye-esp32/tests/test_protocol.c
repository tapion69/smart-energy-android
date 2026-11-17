#include "../main/deye_protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static void make_reply(uint8_t *packet,size_t *len,bool doubled) {
    uint8_t rtu[9]={1,3,4,0,25,0,70,0,0};
    deye_le16(rtu+7,deye_crc16(rtu,7));
    int payload=14+sizeof rtu+(doubled?2:0);
    memset(packet,0,80);
    packet[0]=0xa5;deye_le16(packet+1,payload);
    deye_le16(packet+3,0x1510);
    packet[5]=0xbb;deye_le32(packet+7,0x6a3c6eb2);
    packet[11]=2;packet[12]=1;
    memcpy(packet+25,rtu,sizeof rtu);
    packet[11+payload]=deye_checksum(packet+1,10+payload);
    packet[12+payload]=0x15;
    *len=13+payload;
}
int main(void) {
    uint8_t packet[80]={0};
    uint32_t serial=0;
    assert(deye_serial("3133245188",&serial));
    assert(serial==3133245188UL);
    assert(!deye_serial("123456789012",&serial));
    assert(!deye_serial("12x5",&serial));
    assert(!deye_serial("4294967296",&serial));
    uint8_t tx[36]={0};
    assert(deye_v5_make_fc03(tx,sizeof tx,0x6a3c6eb2,0xbb,1,3,5)==36);
    const uint8_t known[36]={
       0xa5,0x17,0,0x10,0x45,0xbb,0,0xb2,0x6e,0x3c,0x6a,
       0x02,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,0,3,0,5,0x75,0xc9,0x39,0x15
    };
    assert(memcmp(tx,known,sizeof known)==0);
    assert(deye_v5_make_fc03(tx,sizeof tx,0x6a3c6eb2,0xbb,1,3,0)==0);
    assert(deye_v5_make_fc03(tx,sizeof tx,0x6a3c6eb2,0xbb,1,3,125)==0);
    size_t len=0;uint16_t words[2]={0};
    make_reply(packet,&len,false);
    assert(deye_v5_parse_fc03(packet,len,0x6a3c6eb2,0xbb,1,2,words));
    assert(words[0]==25 && words[1]==70);
    assert(!deye_v5_parse_fc03(packet,len,0x6a3c6eb2,0x41,1,2,words));
    assert(!deye_v5_parse_fc03(packet,len,0x6a3c6eb2,0xbb,2,2,words));
    packet[28]^=0x01;
    assert(!deye_v5_parse_fc03(packet,len,0x6a3c6eb2,0xbb,1,2,words));
    make_reply(packet,&len,true);
    assert(deye_v5_parse_fc03(packet,len,0x6a3c6eb2,0xbb,1,2,words));
    assert(deye_type_valid(0x0300));
    assert(deye_is_3phase(0x0500));assert(deye_is_3phase(0x0601));
    assert(!deye_type_valid(0x1234));
    puts("Deye Solarman V5 FC03 parser and framing tests OK");
}
