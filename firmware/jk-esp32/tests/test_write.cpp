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
  assert(protect>=0 && !jkwrite::build((size_t)protect,2.65f,false,frame));
  int disabled=jkwrite::find("disable_temperature_sensors");
  assert(disabled>=0 && !jkwrite::build((size_t)disabled,1.f,true,frame));
}
