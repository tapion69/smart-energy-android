#pragma once
// BLE is an independent telemetry link. A transient Wi-Fi outage must never
// deliberately terminate the three established BMS GATT sessions.
namespace blelink {
inline bool keepExistingLink(bool physicalOrNormalSetupMode) {
  return !physicalOrNormalSetupMode;
}
inline bool mayStartNewConnection(bool setupMode,bool wifiConnected) {
  return !setupMode && wifiConnected;
}
} // namespace blelink
