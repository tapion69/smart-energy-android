#pragma once
#include <stdint.h>

namespace jkidentity {
  // Arduino ESP.getEfuseMac() packs factory MAC bytes in little-endian order.
  // Low 24 bits are the vendor/OUI prefix and are NOT device-unique.
  static inline uint32_t suffix(uint64_t efuseMac, uint8_t scheme) {
    if (scheme == 1) return static_cast<uint32_t>(efuseMac & 0xFFFFFFULL);
    // Last 3 physical MAC bytes, which distinguish boards from the same vendor.
    return static_cast<uint32_t>((efuseMac >> 24) & 0xFFFFFFULL);
  }
  // Keep the previous hostname for already installed Wi-Fi readers, otherwise
  // generate a proper per-board identity and persist it before provisioning.
  static inline uint8_t chooseScheme(uint8_t stored, bool hasSavedWifi) {
    if (stored == 1 || stored == 2) return stored;
    return hasSavedWifi ? 1 : 2;
  }
}
