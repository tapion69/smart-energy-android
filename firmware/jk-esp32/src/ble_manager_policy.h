#pragma once
#include <stdint.h>
// Pure scheduling policy: a missing JK never forces disconnection of a healthy
// JK. Each MAC/slot retries independently, including across millis() overflow.
namespace blemanager {
inline uint32_t retryDelayMs(uint8_t failureStreak) {
  return failureStreak <= 1 ? 4000u :
         failureStreak == 2 ? 9000u :
         failureStreak == 3 ? 16000u : 30000u;
}
inline bool due(uint32_t now,uint32_t nextAttemptAt) {
  return (int32_t)(now-nextAttemptAt) >= 0;
}
inline bool eligible(bool wifiReady,bool maintenance,bool configured,
                     bool alreadyConnected,uint32_t now,uint32_t nextAttemptAt) {
  return wifiReady && !maintenance && configured && !alreadyConnected &&
         due(now,nextAttemptAt);
}
} // namespace blemanager
