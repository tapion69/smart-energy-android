#include "../src/setup_code_policy.h"
#include <cassert>
#include <cstring>
int main() {
  for (size_t n=8; n<=32; ++n) {
    char code[40] = {};
    memset(code,'A',n);
    assert(setupcode::valid(code,n));  // Matches APK 8-32 character policy.
  }
  assert(!setupcode::valid(nullptr, 8));
  assert(!setupcode::valid("AAAAAAA",7));
  char tooLong[34] = {};
  memset(tooLong,'B',33);
  assert(!setupcode::valid(tooLong,33));
  assert(!setupcode::valid("AAAAAAAA AA",11)); // No spaces.
  assert(!setupcode::valid("AAAA\tAAAA",9));   // No controls.
  assert(setupcode::valid("AbCdEf12",8));
}
