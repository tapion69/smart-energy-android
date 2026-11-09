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
// NimBLE can report a connected GATT client after the last usable JK telemetry.
// A fresh 0x02 frame MUST arrive after this connection and keep arriving.
// Only recover after a full stall window; never reset healthy sibling links.
// The signed difference safely handles millis() wraparound for recent timestamps.
inline bool hasStalledTelemetry(uint32_t now,uint32_t connectedAt,
                               uint32_t lastReadingAt,
                               uint32_t stallMs=60000u) {
  if (!connectedAt || uint32_t(now-connectedAt)<stallMs) return false;
  const bool readAfterConnect=lastReadingAt &&
      int32_t(lastReadingAt-connectedAt)>=0;
  return !readAfterConnect || uint32_t(now-lastReadingAt)>=stallMs;
}
} // namespace blemanager
