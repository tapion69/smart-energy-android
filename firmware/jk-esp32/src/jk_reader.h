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
}
