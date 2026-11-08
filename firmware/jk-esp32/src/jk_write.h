#pragma once
// JK02 parameter writes, opt-in experimental allowlist.
// Registers and framing derived from the working Smart JK Bluetooth HA addon.
// Frame creation is pure, testable, and NEVER writes to the BMS itself.
#include "jk_extended.h"
#include <math.h>
#include <string.h>
namespace jkwrite {
static constexpr uint8_t REGISTERS[jkextra::PARAM_COUNT] = {
  0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C,
  0x0D,0x0E,0x0F,0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,0x18,
  0x19,0x1A,0x1B,0x1C,0x1D,0x1E,0x1F,0x20,0x21,0x22,0x25,0x27,
  0x28,0x2B,0x2D,0x2E,0x2F,0x30,0x37,0x38,0x39,0x3A,0x3B
};
static_assert(sizeof(REGISTERS)/sizeof(REGISTERS[0]) == jkextra::PARAM_COUNT,
              "Parameter/register mapping must match HA module");
// Only low-risk display/logging/standby/float user options in initial field
// pilot. Protection thresholds, MOS control, alarm disabling, heater and SOC
// calibration are deliberately excluded until confirmed per firmware.
inline bool editable(size_t i) {
  if (i>=jkextra::PARAM_COUNT) return false;
  switch(REGISTERS[i]) {
    case 0x2B: // display always on
    case 0x2D: // smart sleep
    case 0x2F: // periodic data storage
    case 0x30: // floating mode
    case 0x39: // smart sleep delay h
      return true;
    default: return false;
  }
}
inline int find(const char *key) {
  if(!key) return -1;
  for(size_t i=0;i<jkextra::PARAM_COUNT;++i)
    if(!strcmp(key,jkextra::PARAMS[i].key))return int(i);
  return -1;
}
inline bool isBoolean(size_t idx) {
  if(idx>=jkextra::PARAM_COUNT)return false;
  const auto kind=jkextra::PARAMS[idx].kind;
  return kind==jkextra::BOOL1 || kind==jkextra::FLAG16;
}
inline bool build(size_t idx, float value, bool valueIsBool, uint8_t out[20]) {
  if(!out || idx>=jkextra::PARAM_COUNT || !editable(idx) || !isfinite(value))
    return false;
  const jkextra::Param &p=jkextra::PARAMS[idx];
  if(isBoolean(idx)!=valueIsBool || value<p.minimum || value>p.maximum)
    return false;
  const double scaled=double(value)*p.scale;
  const double nearest=round(scaled);
  if(fabs(scaled-nearest)>0.0005)return false;
  if(nearest < -2147483647.0 || nearest > 4294967295.0)return false;
  const int32_t signedRaw=(int32_t)nearest;
  const uint32_t bits= (nearest<0) ? uint32_t(signedRaw) : uint32_t(nearest);
  const uint8_t size=(p.kind==jkextra::UINT1 || p.kind==jkextra::INT1) ? 1 : 4;
  memset(out,0,20);
  out[0]=0xAA;out[1]=0x55;out[2]=0x90;out[3]=0xEB;
  out[4]=REGISTERS[idx];out[5]=size;
  for(uint8_t i=0;i<size;++i)out[6+i]=uint8_t(bits>>(8*i));
  unsigned sum=0;for(int k=0;k<19;++k)sum+=out[k];
  out[19]=uint8_t(sum);
  return true;
}
} // namespace jkwrite
