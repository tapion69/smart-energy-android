#include "../src/ble_manager_policy.h"
#include <cassert>
#include <stdint.h>
int main() {
  using namespace blemanager;
  assert(retryDelayMs(1)==4000);
  assert(retryDelayMs(2)==9000);
  assert(retryDelayMs(3)==16000);
  assert(retryDelayMs(4)==30000);
  assert(retryDelayMs(255)==30000);
  assert(due(100,100));
  assert(!due(99,100));
  assert(due(0x00000010u,0xFFFFFFF0u)); // millis rollover
  assert(!due(0xFFFFFFF0u,0x00000010u));
  assert(eligible(true,false,true,false,100,100));
  assert(!eligible(false,false,true,false,100,100));
  assert(!eligible(true,true,true,false,100,100));
  assert(!eligible(true,false,false,false,100,100));
  assert(!eligible(true,false,true,true,100,100)); // don't rotate live BMS
  assert(!eligible(true,false,true,false,99,100));
  assert(!hasStalledTelemetry(59000u,1000u,0u));
  assert(hasStalledTelemetry(61000u,1000u,0u));
  assert(!hasStalledTelemetry(62000u,1000u,61000u));
  assert(hasStalledTelemetry(122000u,1000u,61000u));
  assert(hasStalledTelemetry(61000u,1000u,999u)); // sample before current link
  assert(!hasStalledTelemetry(0x20u,0xFFFFFFF0u,0u));
  assert(hasStalledTelemetry(0x00010010u,0xFFFFFFF0u,0u));
  assert(!hasStalledTelemetry(0x00010010u,0xFFFFFFF0u,0x0000FF00u));
  return 0;
}
