#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace jkreader {
  void begin();
  void loadConfigured(const String &saved);
  void setConfigured(JsonArrayConst macs);
  bool acquireRadio(uint32_t waitMs);
  void releaseRadio();
  void fillTelemetry(JsonDocument &out);
  void fillDetails(JsonDocument &out);
  // Only one explicit command may be pending per BMS. Never auto-retry.
  bool requestWrite(const char *mac, const char *key, float value,
                    bool boolType, float expectedOld, String &error);
  bool isConfigured(const char *mac);
  bool requestSettingsRefresh(const char *mac);
  void fillWriteStatus(JsonDocument &out, const char *mac);
}
