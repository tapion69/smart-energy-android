#include "../src/boot_pair_policy.h"
#include <cassert>
int main() {
  using namespace bootpair;
  assert(!windowOpen(100,0));
  assert(windowOpen(5000,4100));
  assert(!windowOpen(125000,4100));
  assert(confirmedRecently(10200,9000));
  assert(!confirmedRecently(55000,9000));
  assert(secondPress(9000,4100,6000,6500,2200));
  assert(!secondPress(9000,4100,6000,5900,2200)); // pressed BEFORE stage
  assert(!secondPress(9000,4100,6000,6500,1000)); // not held 2 s
  assert(!secondPress(19000,4100,6000,6500,10000)); // long press
  assert(!secondPress(125000,4100,6000,123000,2000)); // expired
  return 0;
}
