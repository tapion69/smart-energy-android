#include "../src/jk_write.h"
#include <cassert>
#include <cstring>
int main() {
  uint8_t frame[20] = {};
  int idx=jkwrite::find("display_always_on");
  assert(idx>=0);
  assert(jkwrite::build((size_t)idx,1.f,true,frame));
  assert(frame[4]==0x2B && frame[5]==4 && frame[6]==1);
  unsigned sum=0;for(int i=0;i<19;++i)sum+=frame[i];assert(frame[19]==(sum&255));
  assert(!jkwrite::build((size_t)idx,9.f,true,frame));
  assert(!jkwrite::build((size_t)idx,1.f,false,frame));
  int time=jkwrite::find("smart_sleep_delay_h");
  assert(time>=0 && jkwrite::build((size_t)time,12.f,false,frame));
  assert(frame[4]==0x39 && frame[5]==1 && frame[6]==12);
  int protect=jkwrite::find("cell_uvp_v");
  assert(protect>=0 && jkwrite::riskLevel(size_t(protect))==2);
  assert(jkwrite::build((size_t)protect,2.65f,false,frame));
  assert(frame[4]==0x02 && frame[5]==4 && frame[6]==0x5A && frame[7]==0x0A);
  int disabled=jkwrite::find("disable_temperature_sensors");
  assert(disabled>=0 && jkwrite::riskLevel(size_t(disabled))==2);
  assert(jkwrite::build((size_t)disabled,1.f,true,frame));
  assert(frame[4]==0x28 && frame[5]==4 && frame[6]==1);
  assert(jkwrite::riskLevel(size_t(idx))==0);
  assert(jkwrite::riskLevel(size_t(time))==0);
  for(size_t i=0;i<jkextra::PARAM_COUNT;++i) {
    uint8_t packet[20]={};
    assert(jkwrite::editable(i));
    const jkextra::Param &p=jkextra::PARAMS[i];
    float x=p.minimum;
    const double scaled=double(x)*p.scale;
    if(fabs(scaled-round(scaled))>0.0005) x=p.maximum;
    assert(jkwrite::build(i,x,jkwrite::isBoolean(i),packet));
    assert(packet[4]==jkwrite::REGISTERS[i]);
    unsigned c=0;for(int b=0;b<19;++b)c+=packet[b];
    assert(packet[19]==(c&255));
  }
  jkextra::Settings settings{};
  settings.confirmed=true;
  for(size_t i=0;i<jkextra::PARAM_COUNT;++i) {
    settings.available[i]=true;
    settings.value[i]=jkextra::PARAMS[i].minimum;
  }
  settings.value[size_t(jkwrite::find("cell_uvp_v"))]=2.65f;
  settings.value[size_t(jkwrite::find("cell_uvpr_v"))]=2.75f;
  settings.value[size_t(jkwrite::find("cell_ovpr_v"))]=3.52f;
  settings.value[size_t(jkwrite::find("cell_ovp_v"))]=3.60f;
  assert(jkwrite::consistentCandidate(size_t(protect),2.68f,settings));
  assert(!jkwrite::consistentCandidate(size_t(protect),2.79f,settings));
}
