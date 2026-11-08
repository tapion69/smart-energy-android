#pragma once
// Portable, unified code validation for setup AP, HTTP API and NVS restoration.
// A user-selected 8..11 character code must NEVER be replaced at boot just
// because old firmware used a stricter startup threshold than its own UI.
#include <stddef.h>
namespace setupcode {
inline bool valid(const char *s, size_t n) {
  if (!s || n < 8 || n > 32) return false;
  for (size_t i = 0; i < n; ++i) {
    const unsigned char c = static_cast<unsigned char>(s[i]);
    if (c < 33 || c > 126) return false;
  }
  return true;
}
} // namespace setupcode
