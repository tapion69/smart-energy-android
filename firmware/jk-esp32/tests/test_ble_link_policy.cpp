#include "../src/ble_link_policy.h"
#include <cassert>
int main() {
  assert(blelink::keepExistingLink(false));  // Wi-Fi may go down briefly.
  assert(!blelink::keepExistingLink(true));   // Explicit physical setup.
  assert(blelink::mayStartNewConnection(false,true));
  assert(!blelink::mayStartNewConnection(false,false)); // No new scan during reconnecting Wi-Fi.
  assert(!blelink::mayStartNewConnection(true,true));
  return 0;
}
