#pragma once
// Pure safety rules for physical onboarding. No network, device or secrets.
#include <stdint.h>
namespace bootpair {
static constexpr uint32_t SESSION_MS=120000;
static constexpr uint32_t CONFIRM_MS=45000;
static constexpr uint32_t FIRST_PRESS_MS=4000;
static constexpr uint32_t SECOND_PRESS_MS=2000;
static constexpr uint32_t LONG_PRESS_MS=10000;
inline bool windowOpen(uint32_t now,uint32_t opened) {
  return opened!=0 && uint32_t(now-opened)<SESSION_MS;
}
inline bool confirmedRecently(uint32_t now,uint32_t confirmedAt) {
  return confirmedAt!=0 && uint32_t(now-confirmedAt)<CONFIRM_MS;
}
inline bool secondPress(uint32_t now,uint32_t opened,uint32_t stagedAt,
                        uint32_t pressedAt,uint32_t heldMs) {
  return windowOpen(now,opened) && stagedAt!=0 && pressedAt!=0 &&
         int32_t(pressedAt-stagedAt)>0 &&
         heldMs>=SECOND_PRESS_MS && heldMs<LONG_PRESS_MS;
}
}
