#include "../src/jk_identity_policy.h"
#include <cassert>
#include <cstdio>
int main() {
    // Same factory OUI, two different device-specific 24-bit tails.
    const uint64_t board1 = (uint64_t(0xA12345) << 24) | 0x5C3314;
    const uint64_t board2 = (uint64_t(0xB67890) << 24) | 0x5C3314;
    assert(jkidentity::suffix(board1, 1) == 0x5C3314);
    assert(jkidentity::suffix(board2, 1) == 0x5C3314);
    assert(jkidentity::suffix(board1, 2) == 0xA12345);
    assert(jkidentity::suffix(board2, 2) == 0xB67890);
    assert(jkidentity::suffix(board1, 2) != jkidentity::suffix(board2, 2));
    // Updating an already configured reader must NOT change its identity.
    assert(jkidentity::chooseScheme(0, true) == 1);
    assert(jkidentity::chooseScheme(1, true) == 1);
    // A new reader preserves new identity after Wi-Fi is configured.
    assert(jkidentity::chooseScheme(0, false) == 2);
    assert(jkidentity::chooseScheme(2, true) == 2);
    puts("SmartJK per-device eFuse identity and legacy migration OK");
}
