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
// JK02 profile registers are all represented by the same verified type-01
// parameter definitions. "Editable" means command construction is supported,
// NOT that a given BMS firmware has yet been hardware-validated for writing.
inline bool editable(size_t i) {
  return i < jkextra::PARAM_COUNT;
}
// 0 = everyday display/logging, 1 = operational, 2 = protection-critical.
// These levels enforce confirmations both in the APK and on the ESP32 API.
inline uint8_t riskLevel(size_t i) {
  if(!editable(i)) return 2;
  const uint8_t reg=REGISTERS[i];
  switch(reg) {
    case 0x2B: case 0x2D: case 0x2F: case 0x30: case 0x39:
      return 0;
    case 0x01: case 0x06: case 0x12: case 0x13: case 0x1F:
    case 0x21: case 0x22: case 0x25: case 0x27: case 0x2E:
    case 0x37: case 0x38:
      return 1;
    default:
      return 2;
  }
}
// Reject inconsistent settings before any BLE command is queued. Compare a
// proposed value with other fresh, CRC-checked settings from the same BMS.
// Fail closed if a required dependent setting is not readable.
inline bool consistentCandidate(size_t changed, float candidate,
                                const jkextra::Settings &saved) {
  if(!editable(changed) || !saved.confirmed) return false;
  const uint8_t changedReg=REGISTERS[changed];
  const struct Pair { uint8_t lower, upper; } rules[]={
    {0x02,0x03}, // cell UVP < UVP recovery
    {0x03,0x05}, // UVP recovery < OVP recovery
    {0x05,0x04}, // cell OVP recovery < OVP
    {0x0A,0x09}, // requested float voltage <= charge request (strict below)
    {0x15,0x14}, // charge overtemperature recovery < trip
    {0x17,0x16}, // discharge overtemperature recovery < trip
    {0x18,0x19}, // charge undertemperature trip < recovery
    {0x38,0x37}  // heating stop/start may vary by hardware; see below
  };
  for(size_t r=0;r<sizeof(rules)/sizeof(rules[0]);++r) {
    const Pair &p=rules[r];
    // Heating start/stop direction varies by firmware and project wiring.
    if(p.lower==0x38) continue;
    if(changedReg!=p.lower && changedReg!=p.upper) continue;
    int lowIdx=-1, highIdx=-1;
    for(size_t i=0;i<jkextra::PARAM_COUNT;++i) {
      if(REGISTERS[i]==p.lower)lowIdx=int(i);
      if(REGISTERS[i]==p.upper)highIdx=int(i);
    }
    if(lowIdx<0 || highIdx<0 ||
       !saved.available[size_t(lowIdx)] || !saved.available[size_t(highIdx)])
      return false;
    const float lo=changedReg==p.lower?candidate:saved.value[size_t(lowIdx)];
    const float hi=changedReg==p.upper?candidate:saved.value[size_t(highIdx)];
    // Float/charge requests can legitimately be equal on some models.
    if(p.lower==0x0A) { if(lo>hi) return false; }
    else if(lo>=hi) return false;
  }
  return true;
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
