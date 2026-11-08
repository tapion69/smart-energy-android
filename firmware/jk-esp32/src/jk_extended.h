#pragma once
// JK02 read-only decoded extensions: exact CRC-checked BLE frame offsets.
// Source maps: JK02 (syssi ESPHome) and author's smart-jkbms-bluetooth/ble/parameters.py.
// Does NOT include or expose passcodes or personal user data from type 3.
#include "jk_protocol.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>
#include <stdio.h>

namespace jkextra {
struct Reading {
  bool valid = false;
  bool resistanceAvailable[32] = {};
  float resistanceOhm[32] = {};
  bool temperatureAvailable[3] = {};
  float temperatureC[3] = {}; // T3, T4, T5 (32S only)
  bool balanceCurrentAvailable = false;
  float balanceCurrentA = 0;
  int balanceAction = 0; // 0 = none, 1 = charge, 2 = discharge
  uint32_t wireWarningMask = 0;
  float capacityRemainingAh = 0;
  float cycledCapacityAh = 0;
  uint8_t soh = 0;
  uint32_t runtimeSeconds = 0;
  bool capacityRemainingAvailable = false;
  bool cycledCapacityAvailable = false;
  bool sohAvailable = false;
  bool heating = false;
  bool precharging = false;
  bool dry1 = false;
  bool dry2 = false;
  int chargeStatus = -1;
  bool chargeStatusAvailable = false;
  uint16_t chargeStatusTimeS = 0;
  bool chargeStatusTimeAvailable = false;
  uint16_t emergencyCountdownS = 0;
};

inline bool isTemperature(const uint8_t *f, size_t pos, float &value) {
  const int16_t t = smartjk::i16(f, pos);
  if (t == -2000) return false;
  const float temp = t / 10.0f;
  if (temp < -80 || temp > 160) return false;
  value = temp;
  return true;
}
inline bool decode(const uint8_t *f, const smartjk::Reading &basic, Reading &out) {
  if (!basic.valid || !smartjk::frameValid(f) || f[4] != 2) return false;
  Reading reading{};
  reading.valid = true;
  const size_t baseResistance = basic.layout == 32 ? 80 : 64;
  for (int i = 0; i < 32; ++i) {
    if (!basic.cellActive[i]) continue;
    if (basic.layout == 24 && i >= 24) continue;
    const uint16_t raw = smartjk::u16(f, baseResistance + i * 2);
    // 0 and 0xFFFF are not a measured cell resistance.
    if (raw >= 1 && raw <= 5000) {
      reading.resistanceAvailable[i] = true;
      reading.resistanceOhm[i] = raw / 1000.0f;
    }
  }
  const size_t shift = basic.layout == 32 ? 32 : 0;
  reading.wireWarningMask = smartjk::u32(f, 114 + shift);
  reading.runtimeSeconds = smartjk::u32(f, 162 + shift);
  if (basic.layout == 32) {
    for (int i = 0; i < 3; ++i) {
      const size_t offsets[] = {258, 256, 254};
      reading.temperatureAvailable[i] = isTemperature(f, offsets[i], reading.temperatureC[i]);
      // Ignore improbable disconnected/zero probes when the BMS reports absent.
      // Presence bitmasks vary by firmware; do not use for battery safety.
      if (!(f[214] & (1u << (i+3)))) reading.temperatureAvailable[i] = false;
    }
    const float balanceA = smartjk::i16(f, 170) / 1000.0f;
    if (fabsf(balanceA) <= 25.0f) {
      reading.balanceCurrentAvailable = true;
      reading.balanceCurrentA = balanceA;
    }
    if (f[172] <= 2) reading.balanceAction = f[172];
    reading.capacityRemainingAh = smartjk::u32(f, 174) / 1000.0f;
    reading.capacityRemainingAvailable =
      reading.capacityRemainingAh >= 0 && reading.capacityRemainingAh <= 20000;
    reading.cycledCapacityAh = smartjk::u32(f, 186) / 1000.0f;
    reading.cycledCapacityAvailable = reading.cycledCapacityAh <= 1e8f;
    reading.soh = f[190];
    reading.sohAvailable = reading.soh <= 100;
    reading.precharging = f[191] == 1;
    reading.heating = f[215] == 1;
    reading.dry1 = (f[281] & 0x02) != 0;
    reading.dry2 = (f[281] & 0x04) != 0;
    if (f[280] <= 2) {
      reading.chargeStatus = f[280];
      reading.chargeStatusAvailable = true;
    }
    reading.chargeStatusTimeS = smartjk::u16(f, 278);
    reading.chargeStatusTimeAvailable = true;
    reading.emergencyCountdownS = smartjk::u16(f, 218);
  }
  out = reading;
  return true;
}

// Catalog must be synchronized with read_settings() in original JK add-on.
// Kind: unsigned 4B, signed 4B, unsigned 1B, signed 1B, full-byte switch,
// bit in 16-bit flags field at 282 (no writes of any kind).
enum Kind: uint8_t { UINT4, INT4, UINT1, INT1, BOOL1, FLAG16 };
struct Param {
  const char *key;
  const char *label;
  const char *group;
  const char *unit;
  uint16_t offset;
  Kind kind;
  float scale, minimum, maximum;
  int flagIndex;
  // ESP32 Arduino uses C++11. Default member initializers make an aggregate
  // in C++17 but not in C++11, so use an explicit constexpr constructor.
  constexpr Param(const char *k, const char *l, const char *g, const char *u,
                  uint16_t o, Kind type, float multiplier,
                  float lo, float hi, int flagIndexValue = -1):
    key(k), label(l), group(g), unit(u), offset(o), kind(type),
    scale(multiplier), minimum(lo), maximum(hi), flagIndex(flagIndexValue) {}
};
static constexpr Param PARAMS[] = {
  {"smart_sleep_voltage","Tension veille intelligente","Énergie","V",6,UINT4,1000,0.003f,3.65f},
  {"cell_uvp_v","Protection sous-tension cellule","Tensions","V",10,UINT4,1000,1.2f,4.35f},
  {"cell_uvpr_v","Retour protection sous-tension","Tensions","V",14,UINT4,1000,1.2f,4.35f},
  {"cell_ovp_v","Protection surtension cellule","Tensions","V",18,UINT4,1000,1.2f,4.35f},
  {"cell_ovpr_v","Retour protection surtension","Tensions","V",22,UINT4,1000,1.2f,4.35f},
  {"balance_trigger_v","Déclenchement équilibrage","Équilibrage","V",26,UINT4,1000,0,1},
  {"cell_soc100_v","Tension SOC 100 %","Tensions","V",30,UINT4,1000,1.2f,4.35f},
  {"cell_soc0_v","Tension SOC 0 %","Tensions","V",34,UINT4,1000,1.2f,4.35f},
  {"request_charge_v","Tension demande charge RCV","Tensions","V",38,UINT4,1000,1.2f,4.35f},
  {"request_float_v","Tension demande flottement RFV","Tensions","V",42,UINT4,1000,1.2f,4.35f},
  {"power_off_v","Tension arrêt","Tensions","V",46,UINT4,1000,1.2f,4.35f},
  {"max_charge_current_a","Courant de charge maximal","Courants","A",50,UINT4,1000,0,500},
  {"charge_ocp_delay_s","Délai surcharge courant charge","Courants","s",54,UINT4,1,0,3600},
  {"charge_ocp_recovery_s","Retour surcharge charge","Courants","s",58,UINT4,1,0,3600},
  {"max_discharge_current_a","Courant de décharge maximal","Courants","A",62,UINT4,1000,0,500},
  {"discharge_ocp_delay_s","Délai surcharge décharge","Courants","s",66,UINT4,1,0,3600},
  {"discharge_ocp_recovery_s","Retour surcharge décharge","Courants","s",70,UINT4,1,0,3600},
  {"short_circuit_recovery_s","Retour court-circuit","Courants","s",74,UINT4,1,0,3600},
  {"max_balance_current_a","Courant maximal équilibrage","Équilibrage","A",78,UINT4,1000,0,20},
  {"charge_otp_c","Température haute charge","Températures protection","°C",82,UINT4,10,0,120},
  {"charge_otpr_c","Retour température haute charge","Températures protection","°C",86,UINT4,10,0,120},
  {"discharge_otp_c","Température haute décharge","Températures protection","°C",90,UINT4,10,0,120},
  {"discharge_otpr_c","Retour température haute décharge","Températures protection","°C",94,UINT4,10,0,120},
  {"charge_utp_c","Température basse charge","Températures protection","°C",98,INT4,10,-40,120},
  {"charge_utpr_c","Retour température basse charge","Températures protection","°C",102,INT4,10,-40,120},
  {"mos_otp_c","Température haute MOS","Températures protection","°C",106,INT4,10,0,160},
  {"mos_otpr_c","Retour température MOS","Températures protection","°C",110,INT4,10,0,160},
  {"configured_cell_count","Nombre cellules configuré","Batterie","",114,UINT4,1,4,32},
  {"charging_enabled","Charge autorisée","Interrupteurs","",118,BOOL1,1,0,1},
  {"discharging_enabled","Décharge autorisée","Interrupteurs","",122,BOOL1,1,0,1},
  {"balancer_enabled","Équilibrage autorisé","Interrupteurs","",126,BOOL1,1,0,1},
  {"capacity_config_ah","Capacité configurée","Batterie","Ah",130,UINT4,1000,1,10000},
  {"short_circuit_delay_us","Délai protection court-circuit","Courants","µs",134,UINT4,1,0,500000},
  {"balance_start_v","Tension début équilibrage","Équilibrage","V",138,UINT4,1000,1.2f,4.35f},
  {"precharge_time_s","Durée précharge","Énergie","s",274,UINT1,1,0,255},
  {"heating_enabled","Chauffage autorisé","Interrupteurs","",282,FLAG16,1,0,1,0},
  {"disable_temperature_sensors","Surveillance températures désactivée","Interrupteurs","",282,FLAG16,1,0,1,1},
  {"display_always_on","Écran toujours allumé","Interrupteurs","",282,FLAG16,1,0,1,4},
  {"smart_sleep_enabled","Veille intelligente","Interrupteurs","",282,FLAG16,1,0,1,6},
  {"disable_pcl_module","Module PCL désactivé","Interrupteurs","",282,FLAG16,1,0,1,7},
  {"timed_stored_data","Enregistrement périodique","Interrupteurs","",282,FLAG16,1,0,1,8},
  {"charging_float_mode","Mode flottement","Interrupteurs","",282,FLAG16,1,0,1,9},
  {"heating_start_c","Démarrage chauffage","Températures protection","°C",284,INT1,1,-30,90},
  {"heating_stop_c","Arrêt chauffage","Températures protection","°C",285,INT1,1,-30,90},
  {"smart_sleep_delay_h","Délai veille","Énergie","h",286,UINT1,1,0,24},
  {"discharge_utp_c","Température basse décharge","Températures protection","°C",296,INT1,1,-40,50},
  {"discharge_utpr_c","Retour température basse décharge","Températures protection","°C",297,INT1,1,-40,50}
};
static constexpr size_t PARAM_COUNT = sizeof(PARAMS) / sizeof(PARAMS[0]);
struct Settings {
  bool confirmed = false;
  bool available[PARAM_COUNT] = {};
  float value[PARAM_COUNT] = {};
};
inline bool decodeSettings(const uint8_t *f, Settings &out) {
  if (!smartjk::frameValid(f) || f[4] != 1) return false;
  Settings settings{};
  for (size_t i = 0; i < PARAM_COUNT; ++i) {
    const Param &p = PARAMS[i];
    int64_t raw = 0;
    if (p.kind == UINT4) raw = smartjk::u32(f, p.offset);
    else if (p.kind == INT4) raw = smartjk::i32(f, p.offset);
    else if (p.kind == UINT1 || p.kind == BOOL1) raw = f[p.offset];
    else if (p.kind == INT1) raw = int8_t(f[p.offset]);
    else if (p.kind == FLAG16) raw = ((smartjk::u16(f,p.offset) & (uint16_t(1) << p.flagIndex)) != 0);
    if (p.kind == BOOL1 && raw != 0 && raw != 1) continue;
    float val = float(raw) / p.scale;
    if (!isfinite(val) || val < p.minimum || val > p.maximum) continue;
    settings.available[i] = true;
    settings.value[i] = val;
  }
  const size_t uvp = 1, ovp = 3, cellCount = 27;
  if (!settings.available[uvp] || !settings.available[ovp] ||
      settings.value[uvp] >= settings.value[ovp] ||
      !settings.available[cellCount]) return false;
  settings.confirmed = true;
  out = settings;
  return true;
}

struct Info {
  bool confirmed = false;
  char vendor[17] = {};
  char hardware[9] = {};
  char software[9] = {};
  char deviceName[17] = {};
  char manufactureDate[11] = {}; // Derived YYYY-MM-DD, not a device constant
  char serialNumber[12] = {};
  uint32_t powerOnCount = 0;
  uint32_t uptimeS = 0;
  uint8_t uart1Protocol = 0;
  uint8_t canProtocol = 0;
};
inline void readPrintable(const uint8_t *f, size_t offset, size_t len,
                          char *dest, size_t maxLen) {
  if (!maxLen) return;
  size_t n = 0;
  for (; n < len && n < maxLen - 1; ++n) {
    unsigned char ch = f[offset + n];
    if (ch == 0) break;
    if (ch < 32 || ch > 126) { dest[0] = 0; return; }
    dest[n] = char(ch);
  }
  dest[n] = 0;
  // Home Assistant's JK02 decoder uses ASCII .strip() on each fixed-width
  // field. Some BMS pad versions/model with spaces instead of NUL bytes.
  // Trim these padding bytes before validating; never substitute a value.
  while (n && dest[n - 1] == ' ') dest[--n] = 0;
  size_t first = 0;
  while (first < n && dest[first] == ' ') ++first;
  if (first) memmove(dest, dest + first, n - first + 1);
}
// JK variants may send a CRC-valid type-03 frame with different content
// layouts (including printable JSON fragments). A valid checksum proves the
// frame arrived intact, NOT that these fixed offsets contain identity data.
// Fail CLOSED unless the vendor/model prefix is recognizable, then validate
// each independent field. Never decode/access password regions.
inline bool identifierCharacter(char ch, bool allowSpaces = false) {
  return (ch >= 'A' && ch <= 'Z') ||
         (ch >= 'a' && ch <= 'z') ||
         (ch >= '0' && ch <= '9') ||
         ch == '_' || ch == '-' || ch == '.' ||
         (allowSpaces && ch == ' ');
}
inline bool safeIdentifier(const char *str, size_t minimum,
                           bool allowSpaces = false) {
  const size_t n = strlen(str);
  if (n < minimum) return false;
  for (size_t i=0; i<n; ++i)
    if (!identifierCharacter(str[i], allowSpaces)) return false;
  return true;
}
inline bool likelyJKModel(const char *v) {
  return safeIdentifier(v, 3, true) &&
     ((v[0]=='J' && v[1]=='K') ||
      (v[0]=='P' && v[1]=='B') ||
      (v[0]=='J' && v[1]=='i' && v[2]=='k'));
}
// Check cached identity again at the API boundary. Frame CRC and firmware
// version alone cannot certify a model and its version fields.
// Reject missing terminators BEFORE passing cached buffers to strlen().
inline bool terminatedWithin(const char *field, size_t size) {
  for (size_t i = 0; i < size; ++i) if (field[i] == 0) return true;
  return false;
}
inline bool usableIdentity(const Info &info) {
  if (!terminatedWithin(info.vendor, sizeof(info.vendor)) ||
      !terminatedWithin(info.hardware, sizeof(info.hardware)) ||
      !terminatedWithin(info.software, sizeof(info.software)) ||
      !terminatedWithin(info.deviceName, sizeof(info.deviceName)) ||
      !terminatedWithin(info.serialNumber, sizeof(info.serialNumber)) ||
      !terminatedWithin(info.manufactureDate, sizeof(info.manufactureDate)))
    return false;
  if (!info.confirmed || !likelyJKModel(info.vendor)) return false;
  const bool hw = info.hardware[0] && safeIdentifier(info.hardware, 2);
  const bool sw = info.software[0] && safeIdentifier(info.software, 2);
  if (!hw && !sw) return false;
  if (info.hardware[0] && !hw) return false;
  if (info.software[0] && !sw) return false;
  if (info.deviceName[0] && !safeIdentifier(info.deviceName, 2, true)) return false;
  if (info.serialNumber[0] && !safeIdentifier(info.serialNumber, 5)) return false;
  return true;
}
inline bool decodeInfo(const uint8_t *f, Info &out) {
  if (!smartjk::frameValid(f) || f[4] != 3) return false;
  Info info{};
  readPrintable(f, 6,16,info.vendor,sizeof(info.vendor));
  // A binary payload or JSON fragment must NEVER be displayed as BMS identity.
  if (!likelyJKModel(info.vendor)) return false;
  readPrintable(f,22,8,info.hardware,sizeof(info.hardware));
  readPrintable(f,30,8,info.software,sizeof(info.software));
  readPrintable(f,46,16,info.deviceName,sizeof(info.deviceName));
  char dateRaw[9] = {};
  readPrintable(f,78,8,dateRaw,sizeof(dateRaw));
  readPrintable(f,86,11,info.serialNumber,sizeof(info.serialNumber));

  if (!safeIdentifier(info.hardware, 2)) info.hardware[0] = 0;
  if (!safeIdentifier(info.software, 2)) info.software[0] = 0;
  if (!safeIdentifier(info.deviceName, 2, true)) info.deviceName[0] = 0;
  if (!safeIdentifier(info.serialNumber, 5)) info.serialNumber[0] = 0;
  // Match Home Assistant: derive ISO 8601 from a verified YYMMDD field.
  // This is read from the real type-3 BMS frame, never a hardcoded date.
  if (strlen(dateRaw) == 6) {
    bool digits = true;
    for (size_t n = 0; n < 6; ++n)
      if (dateRaw[n] < '0' || dateRaw[n] > '9') digits = false;
    if (digits) {
      const int month = (dateRaw[2] - '0') * 10 + dateRaw[3] - '0';
      const int day = (dateRaw[4] - '0') * 10 + dateRaw[5] - '0';
      if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
        snprintf(info.manufactureDate, sizeof(info.manufactureDate),
                 "20%c%c-%c%c-%c%c",
                 dateRaw[0],dateRaw[1],dateRaw[2],dateRaw[3],dateRaw[4],dateRaw[5]);
      }
    }
  }
  const uint32_t uptimeS = smartjk::u32(f,38);
  const uint32_t powerOn = smartjk::u32(f,42);
  // A dubious field must not be presented as a confirmed counter.
  info.uptimeS = uptimeS < 315360000u ? uptimeS : 0u;
  info.powerOnCount = powerOn < 1000000u ? powerOn : 0u;
  info.uart1Protocol = f[184];
  info.canProtocol = f[185];
  // Explicitly ignore secret areas [62,78) and [118,134).
  info.confirmed = true;
  if (!usableIdentity(info)) return false; // model + at least one valid version
  out = info;
  return true;
}
} // namespace jkextra
