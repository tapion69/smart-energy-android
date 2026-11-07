#pragma once
// Smart JK ESP32: standalone, read-only JK02 frame assembler and *provisional*
// telemetry decoder. Derived from the author's Smart JK-BMS Bluetooth decoder.
// No Arduino dependencies: testable with a desktop C++17 compiler.
// JK02_24S and JK02_32S are FRAME LAYOUTS, not the number of real cells.
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>

namespace smartjk {
static constexpr size_t FRAME_LEN = 300;
struct Reading {
  bool valid = false;
  uint8_t layout = 0;  // 24 or 32, frame profile, not pack cell count
  uint8_t sequence = 0;
  uint8_t cellCount = 0;
  bool cellActive[32] = {};
  float cellVoltage[32] = {};
  float voltageV = 0;
  float currentA = 0;
  float powerW = 0;
  uint8_t soc = 0;
  float cellMinV = 0;
  float cellMaxV = 0;
  int cellDeltaMv = 0;
  float tempC[3] = {};
  bool tempAvailable[3] = {};
  bool tempSafetyVerified = false; // NEVER set true in experimental parser
  float nominalCapacityAh = 0;
  uint32_t cycles = 0;
  bool chargingMos = false;
  bool dischargingMos = false;
  uint32_t errorMask = 0;
};

inline uint16_t u16(const uint8_t *b, size_t o) {
  return (uint16_t)b[o] | (uint16_t)((uint16_t)b[o + 1] << 8);
}
inline uint32_t u32(const uint8_t *b, size_t o) {
  return (uint32_t)b[o] | ((uint32_t)b[o + 1] << 8) |
         ((uint32_t)b[o + 2] << 16) | ((uint32_t)b[o + 3] << 24);
}
inline int16_t i16(const uint8_t *b, size_t o) { return (int16_t)u16(b, o); }
inline int32_t i32(const uint8_t *b, size_t o) { return (int32_t)u32(b, o); }

inline bool frameValid(const uint8_t *b) {
  if (b[0] != 0x55 || b[1] != 0xAA || b[2] != 0xEB || b[3] != 0x90) return false;
  if (b[4] != 1 && b[4] != 2 && b[4] != 3) return false;
  unsigned sum = 0;
  for (size_t i = 0; i < FRAME_LEN - 1; ++i) sum = (sum + b[i]) & 0xffu;
  return sum == b[FRAME_LEN - 1];
}

struct Profile {
  uint8_t name, cellSlots;
  uint16_t mask, voltage, current, soc, t1, t2, mos, capacity, cycles;
  uint16_t charge, discharge, errors;
  uint8_t errorBytes;
};
static constexpr Profile PROFILES[] = {
  {24, 24, 54, 118, 126, 141, 130, 132, 134, 146, 150, 166, 167, 136, 2},
  {32, 32, 70, 150, 158, 173, 162, 164, 144, 178, 182, 198, 199, 166, 4}
};

inline bool decodeProfile(const uint8_t* frame, const Profile& p, Reading& out) {
  Reading r{};
  r.layout = p.name;
  r.sequence = frame[5];
  const uint32_t mask = u32(frame, p.mask);
  float sum = 0.0f;
  for (uint8_t i = 0; i < p.cellSlots; ++i) {
    if ((mask & (uint32_t(1) << i)) == 0) continue;
    const float v = u16(frame, 6 + 2 * i) / 1000.0f;
    if (v < 1.5f || v > 4.5f) return false;
    r.cellActive[i] = true;
    r.cellVoltage[i] = v;
    if (r.cellCount == 0 || v < r.cellMinV) r.cellMinV = v;
    if (r.cellCount == 0 || v > r.cellMaxV) r.cellMaxV = v;
    r.cellCount++;
    sum += v;
  }
  if (r.cellCount < 4) return false;
  r.voltageV = u32(frame, p.voltage) / 1000.0f;
  r.currentA = i32(frame, p.current) / 1000.0f;
  r.powerW = r.voltageV * r.currentA;
  r.soc = frame[p.soc];
  if (r.voltageV < 5.0f || r.voltageV > 170.0f || r.soc > 100 ||
      fabsf(r.currentA) > 3000.0f ||
      fabsf(sum - r.voltageV) > fmaxf(1.5f, r.voltageV * 0.04f)) return false;
  const size_t offset[3] = {p.t1, p.t2, p.mos};
  int invalidTemps = 0;
  bool allMissingSentinel = true;
  for (size_t i = 0; i < 3; ++i) {
    const int16_t raw = i16(frame, offset[i]);
    if (raw != -2000) allMissingSentinel = false;
    const float value = raw / 10.0f;
    if (value >= -80.0f && value <= 160.0f) {
      r.tempAvailable[i] = true;
      r.tempC[i] = value;
    } else {
      invalidTemps++;
    }
  }
  // Some observed 32S frames return -2000 (unavailable) on all three channels.
  if (invalidTemps > 1 && !allMissingSentinel) return false;
  r.cellDeltaMv = (int)lroundf((r.cellMaxV - r.cellMinV) * 1000.0f);
  r.nominalCapacityAh = u32(frame, p.capacity) / 1000.0f;
  r.cycles = u32(frame, p.cycles);
  r.chargingMos = frame[p.charge] == 1;
  r.dischargingMos = frame[p.discharge] == 1;
  r.errorMask = p.errorBytes == 4 ? u32(frame, p.errors) : u16(frame, p.errors);
  r.valid = true;
  out = r;
  return true;
}
inline bool decodeFrame(const uint8_t *frame, Reading &out) {
  if (!frameValid(frame) || frame[4] != 2) return false;
  Reading candidate{};
  size_t found = 0;
  for (const Profile &p : PROFILES) {
    Reading current{};
    if (decodeProfile(frame, p, current)) {
      ++found;
      candidate = current;
    }
  }
  if (found != 1) return false; // no matching layout OR ambiguous layout
  out = candidate;
  return true;
}

class Assembler {
 public:
  void reset() { used_ = 0; badCrc = validFrames = rejectedMeasurements = 0; }
  uint32_t badCrc = 0;
  uint32_t validFrames = 0;
  uint32_t rejectedMeasurements = 0;
  // Returns true only when one or more full, validated telemetry frames decoded.
  bool append(const uint8_t *data, size_t size, Reading &latest) {
    if (!data || !size) return false;
    bool decoded = false;
    for (size_t k = 0; k < size; ++k) {
      if (used_ == sizeof buffer_) {
        memmove(buffer_, buffer_ + 1, --used_);
      }
      buffer_[used_++] = data[k];
      while (used_ >= 4) {
        size_t pos = 0;
        while (pos + 4 <= used_ &&
               !(buffer_[pos] == 0x55 && buffer_[pos + 1] == 0xAA &&
                 buffer_[pos + 2] == 0xEB && buffer_[pos + 3] == 0x90)) ++pos;
        if (pos + 4 > used_) {
          const size_t keep = used_ < 3 ? used_ : 3;
          memmove(buffer_, buffer_ + used_ - keep, keep);
          used_ = keep;
          break;
        }
        if (pos > 0) {
          used_ -= pos;
          memmove(buffer_, buffer_ + pos, used_);
        }
        if (used_ < FRAME_LEN) break;
        if (!frameValid(buffer_)) {
          ++badCrc;
          memmove(buffer_, buffer_ + 1, --used_);
          continue;
        }
        ++validFrames;
        Reading reading{};
        if (buffer_[4] == 2) {
          if (decodeFrame(buffer_, reading)) {
            latest = reading;
            decoded = true;
          } else {
            ++rejectedMeasurements;
          }
        }
        used_ -= FRAME_LEN;
        if (used_) memmove(buffer_, buffer_ + FRAME_LEN, used_);
      }
    }
    return decoded;
  }
 private:
  uint8_t buffer_[FRAME_LEN + 64] = {};
  size_t used_ = 0;
};
}  // namespace smartjk
