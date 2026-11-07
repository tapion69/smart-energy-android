#include "../src/jk_extended.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static void w16(uint8_t *b,int n,uint16_t v) {
  b[n] = (uint8_t)(v&255); b[n+1]=(uint8_t)(v>>8);
}
static void w32(uint8_t *b,int n,uint32_t v) {
  b[n]=(uint8_t)v; b[n+1]=(uint8_t)(v>>8);
  b[n+2]=(uint8_t)(v>>16); b[n+3]=(uint8_t)(v>>24);
}
static void sign(uint8_t *b) {
  int sum=0;for(int n=0;n<299;++n)sum=(sum+b[n])&255;b[299]=sum;
}
int main() {
  uint8_t frame[300]={0x55,0xAA,0xEB,0x90,0x01};
  w32(frame,10,2650); w32(frame,14,3000); w32(frame,18,3650);
  w32(frame,22,3450); w32(frame,38,3450); w32(frame,42,3375);
  w32(frame,50,150000);w32(frame,62,200000);w32(frame,114,16);
  frame[118]=1;frame[122]=1;frame[126]=0;
  w32(frame,130,280000);
  w32(frame,98,(uint32_t)(int32_t)-20);
  w16(frame,282,0x0201);
  frame[284]=(uint8_t)-4;
  frame[285]=8;
  sign(frame);
  jkextra::Settings s{};
  assert(jkextra::decodeSettings(frame,s));
  assert(s.confirmed);
  assert(jkextra::PARAM_COUNT>=40);
  bool foundUvp=false, foundFloat=false,foundCurrent=false,foundNeg=false,foundSwitch=false;
  for(size_t i=0;i<jkextra::PARAM_COUNT;++i) {
    const char *k=jkextra::PARAMS[i].key;
    if(!strcmp(k,"cell_uvp_v")) {
      assert(s.available[i]&&fabs(s.value[i]-2.65f)<0.001);foundUvp=true;
    }
    if(!strcmp(k,"request_float_v")) {
      assert(s.available[i]&&fabs(s.value[i]-3.375f)<0.001);foundFloat=true;
    }
    if(!strcmp(k,"max_charge_current_a")) {
      assert(s.available[i]&&fabs(s.value[i]-150.f)<0.001);foundCurrent=true;
    }
    if(!strcmp(k,"charge_utp_c")) {
      assert(s.available[i]&&fabs(s.value[i]+2.f)<0.001);foundNeg=true;
    }
    if(!strcmp(k,"charging_float_mode")) {
      assert(s.available[i]&&s.value[i]==1.f);foundSwitch=true;
    }
  }
  assert(foundUvp&&foundFloat&&foundCurrent&&foundNeg&&foundSwitch);
  frame[299]^=0xFF;
  assert(!jkextra::decodeSettings(frame,s));
  frame[299]^=0xFF;
  frame[4]=2;sign(frame);
  assert(!jkextra::decodeSettings(frame,s));

  uint8_t info[300]={0x55,0xAA,0xEB,0x90,0x03};
  memcpy(info+6,"JK-BMS",6);
  memcpy(info+22,"V19",3);
  memcpy(info+30,"11.23",5);
  memcpy(info+46,"JK pack",7);
  memcpy(info+62,"ACCESS-PASSWORD",15);
  memcpy(info+118,"SECRET-SYSTEM",13);
  w32(info,42,137);w32(info,38,3600);
  sign(info);
  jkextra::Info i{};
  assert(jkextra::decodeInfo(info,i));
  assert(!strcmp(i.hardware,"V19"));
  assert(!strcmp(i.software,"11.23"));
  assert(!strcmp(i.deviceName,"JK pack"));
  assert(!strstr(i.vendor,"ACCESS") && !strstr(i.deviceName,"SECRET"));
  assert(i.powerOnCount==137 && i.uptimeS==3600);

  uint8_t d[300]={0x55,0xAA,0xEB,0x90,0x02};
  for(int j=0;j<16;j++){ w16(d,6+j*2,3280);w16(d,80+j*2,40+j); }
  w32(d,70,0xFFFF);
  w32(d,150,52480);w32(d,158,1200);d[173]=69;
  w16(d,144,180);w16(d,162,150);w16(d,164,160);
  w16(d,170,(uint16_t)(int16_t)-500);
  w32(d,174,180000);w32(d,178,280000);w32(d,182,137);
  w32(d,186,13500000);d[190]=95;
  w32(d,194,86400);d[198]=1;d[199]=1;
  d[214]=0x3F;
  w16(d,258,198);w16(d,256,201);w16(d,254,203);
  d[215]=1;d[280]=2;
  sign(d);
  smartjk::Reading basic{};
  assert(smartjk::decodeFrame(d,basic));
  assert(basic.layout==32);
  jkextra::Reading extra{};
  assert(jkextra::decode(d,basic,extra));
  assert(extra.resistanceAvailable[0] && fabs(extra.resistanceOhm[0]-0.04f)<0.0001);
  assert(extra.temperatureAvailable[0] && fabs(extra.temperatureC[0]-19.8f)<0.1f);
  assert(extra.temperatureAvailable[1] && extra.temperatureAvailable[2]);
  assert(extra.balanceCurrentAvailable && fabs(extra.balanceCurrentA + 0.5f)<0.01f);
  assert(extra.capacityRemainingAvailable && fabs(extra.capacityRemainingAh-180.f)<0.01f);
  assert(extra.sohAvailable && extra.soh==95);
  assert(extra.chargeStatusAvailable && extra.chargeStatus==2);

  // Frame-assembler dispatch: 0x01 and 0x03 can be recovered in fragments.
  struct TestState {int info=0, settings=0, telemetry=0; } state;
  auto visitor=[](const uint8_t *raw, const smartjk::Reading *reading,void *ctx){
    auto *s=(TestState*)ctx;
    if(raw[4]==1){assert(!reading);s->settings++;}
    if(raw[4]==3){assert(!reading);s->info++;}
    if(raw[4]==2){assert(reading && reading->valid);s->telemetry++;}
  };
  // Rebuild type 1 after adversarial invalid-type test.
  frame[4]=1;sign(frame);
  smartjk::Assembler a{};
  smartjk::Reading latest{};
  assert(!a.append(frame,100,latest,visitor,&state));
  assert(!a.append(frame+100,200,latest,visitor,&state));
  assert(!a.append(info,300,latest,visitor,&state));
  assert(a.append(d,300,latest,visitor,&state));
  assert(state.info==1 && state.settings==1 && state.telemetry==1);
  puts("JK02 extended settings/info/temps/resistances parser tests passed");
}
