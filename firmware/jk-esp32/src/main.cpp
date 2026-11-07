// Smart Energy JK - provisioning test firmware 0.1.1-dev
// IMPORTANT: Wi-Fi setup and BLE discovery only, JK data decoding NOT included yet.
// Target: classic ESP32 DevKit (esp32dev), flash a spare unit, not the HA proxy.
#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <ESPmDNS.h>
#include <NimBLEDevice.h>
#include <ArduinoJson.h>
#include <ctype.h>

static const char *VERSION = "0.1.1-dev";
static const uint32_t WIFI_JOIN_TIMEOUT_MS = 30000; // Return to setup after 30s
static const int SETUP_BUTTON = 0; // Hold BOOT for 4 s AFTER startup to reopen setup
static const uint32_t HOLD_MS = 4000;

Preferences prefs;
WebServer http(80);
String hostName;
String setupSsid;
String setupCode;
bool setupMode = false;
bool mdnsRunning = false;
uint32_t restartAt = 0;
uint32_t buttonPressedAt = 0;
uint32_t wifiAttemptAt = 0;
bool everConnected = false;
String wifiIssue = "";
volatile uint8_t disconnectReason = 0;

void respond(int status, JsonDocument &doc) {
  String json;
  serializeJson(doc, json);
  http.send(status, "application/json; charset=utf-8", json);
}
void error(int code, const char *message) {
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = message;
  respond(code, doc);
}
bool requireSetup() {
  if (!setupMode) {
    error(403, "setup_mode_required");
    return false;
  }
  if (http.header("X-Setup-Code") != setupCode) {
    error(401, "invalid_setup_code");
    return false;
  }
  return true;
}
String normalizedMac(const String &input) {
  if (input.length() != 17) return "";
  String value = input;
  value.toUpperCase();
  for (int i = 0; i < 17; ++i) {
    char c = value[i];
    if (i == 2 || i == 5 || i == 8 || i == 11 || i == 14) {
      if (c != ':') return "";
    } else if (!isxdigit((unsigned char)c)) {
      return "";
    }
  }
  return value;
}
void onWifiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
  if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    disconnectReason = info.wifi_sta_disconnected.reason;
    Serial.printf("[WiFi] STA disconnected; reason=%u\\n", (unsigned)disconnectReason);
  }
}
void startSetup() {
  wifiAttemptAt = 0;
  if (mdnsRunning) {
    MDNS.end();
    mdnsRunning = false;
  }
  WiFi.disconnect(true);
  WiFi.mode(WIFI_AP);
  setupMode = true;
  const bool started = WiFi.softAP(setupSsid.c_str(), setupCode.c_str(), 1, false, 1);
  Serial.printf("[WiFi] Setup AP %s, diagnostic=%s, last_reason=%u\\n",
                started ? "started" : "FAILED", wifiIssue.c_str(), (unsigned)disconnectReason);
  Serial.println();
  Serial.println("========= SMART JK SETUP =========");
  Serial.printf("SSID: %s\n", setupSsid.c_str());
  Serial.printf("Wi-Fi password AND setup code: %s\n", setupCode.c_str());
  Serial.println("Connect from APK only while pairing is active.");
  Serial.printf("Setup API: http://%s\n", WiFi.softAPIP().toString().c_str());
  Serial.println("==================================");
}
void connectHome() {
  setupMode = false;
  everConnected = false;
  disconnectReason = 0;
  wifiIssue = "";
  String ssid = prefs.getString("ssid", "");
  String password = prefs.getString("wifiPass", "");
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(hostName.c_str());
  WiFi.setAutoReconnect(true);
  WiFi.begin(ssid.c_str(), password.c_str());
  wifiAttemptAt = millis();
  Serial.println("[WiFi] Trying saved Wi-Fi (2.4 GHz required)...");
  // Never log SSIDs or passwords in diagnostic outputs.
}
void onInfo() {
  JsonDocument doc;
  doc["product"] = "smart-jk-esp32";
  doc["version"] = VERSION;
  doc["device_id"] = hostName;
  doc["setup_mode"] = setupMode;
  doc["wifi_connected"] = WiFi.status() == WL_CONNECTED;
  doc["wifi_status_code"] = (int)WiFi.status();
  doc["wifi_saved"] = prefs.getString("ssid", "").length() > 0;
  doc["wifi_issue"] = wifiIssue;
  doc["last_disconnect_reason"] = disconnectReason;
  doc["ip"] = WiFi.localIP().toString();
  doc["ble_scan"] = true;
  doc["telemetry"] = false;
  doc["ota"] = false;
  respond(200, doc);
}
void onConfiguredBms() {
  if (!requireSetup()) return;
  JsonDocument doc;
  String saved = prefs.getString("bmsList", "[]");
  deserializeJson(doc, saved);
  JsonDocument out;
  out["ok"] = true;
  out["bms"] = doc.as<JsonArray>();
  respond(200, out);
}
void onScan() {
  if (!requireSetup()) return;
  NimBLEScan *scan = NimBLEDevice::getScan();
  scan->setActiveScan(true);
  scan->setMaxResults(40);
  NimBLEScanResults results = scan->getResults(4500, false);
  JsonDocument doc;
  doc["ok"] = true;
  JsonArray devices = doc["devices"].to<JsonArray>();
  for (int i = 0; i < results.getCount(); ++i) {
    const NimBLEAdvertisedDevice *item = results.getDevice(i);
    if (!item) continue;
    JsonObject obj = devices.add<JsonObject>();
    String mac = String(item->getAddress().toString().c_str());
    mac.toUpperCase();
    obj["mac"] = mac;
    obj["name"] = String(item->getName().c_str());
    obj["rssi"] = item->getRSSI();
    // Scan results are nearby BLE devices, not guaranteed JK BMS.
  }
  scan->clearResults();
  respond(200, doc);
}
void onSaveBms() {
  if (!requireSetup()) return;
  JsonDocument received;
  if (deserializeJson(received, http.arg("plain"))) {
    error(400, "invalid_json");
    return;
  }
  JsonArray items = received["bms"].as<JsonArray>();
  if (items.isNull() || items.size() > 8) {
    error(400, "expected_0_to_8_bms");
    return;
  }
  JsonDocument cleaned;
  JsonArray target = cleaned.to<JsonArray>();
  for (JsonVariant value : items) {
    String mac = normalizedMac(value.as<String>());
    if (mac.isEmpty()) {
      error(400, "invalid_mac");
      return;
    }
    for (JsonVariant previous : target) {
      if (previous.as<String>() == mac) {
        error(400, "duplicate_mac");
        return;
      }
    }
    target.add(mac);
  }
  String output;
  serializeJson(cleaned, output);
  if (prefs.putString("bmsList", output) != output.length()) {
    error(500, "storage_failed");
    return;
  }
  JsonDocument resp;
  resp["ok"] = true;
  resp["saved"] = target.size();
  respond(200, resp);
}
void onSetWifi() {
  if (!requireSetup()) return;
  JsonDocument request;
  if (deserializeJson(request, http.arg("plain"))) {
    error(400, "invalid_json");
    return;
  }
  String ssid = request["ssid"].as<String>();
  String password = request["password"].as<String>();
  if (ssid.isEmpty() || ssid.length() > 32 || password.length() < 8 || password.length() > 63) {
    error(400, "invalid_wifi_credentials");
    return;
  }
  bool ok1 = prefs.putString("ssid", ssid) == ssid.length();
  bool ok2 = prefs.putString("wifiPass", password) == password.length();
  if (!ok1 || !ok2) {
    error(500, "storage_failed");
    return;
  }
  JsonDocument resp;
  resp["ok"] = true;
  resp["restart_required"] = true;
  resp["note"] = "ESP32 will switch to home Wi-Fi. Reconnect phone to home Wi-Fi.";
  respond(200, resp);
  restartAt = millis() + 2200;
}
void onTelemetry() {
  JsonDocument doc;
  doc["available"] = false;
  doc["reason"] = "jk_decoder_not_implemented";
  doc["samples"] = 0;
  respond(200, doc);
}
void setup() {
  Serial.begin(115200);
  pinMode(SETUP_BUTTON, INPUT_PULLUP);
  delay(250);
  uint32_t id = (uint32_t)(ESP.getEfuseMac() & 0xFFFFFF);
  char hexId[7];
  snprintf(hexId, sizeof(hexId), "%06X", id);
  hostName = String("smartjk-") + hexId;
  setupSsid = String("SmartJK-Setup-") + hexId;

  prefs.begin("smart-jk", false);
  setupCode = prefs.getString("setupCode", "");
  if (setupCode.length() < 12) {
    // Unique per-board secret, shown ONLY via local USB serial for this dev build.
    const char charset[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    setupCode = "";
    for (int i = 0; i < 12; ++i) setupCode += charset[esp_random() % (sizeof(charset) - 1)];
    prefs.putString("setupCode", setupCode);
  }
  NimBLEDevice::init("");
  WiFi.onEvent(onWifiEvent);

  const char *headerKeys[] = {"X-Setup-Code"};
  http.collectHeaders(headerKeys, 1);
  http.on("/api/v1/info", HTTP_GET, onInfo);
  http.on("/api/v1/bms", HTTP_GET, onConfiguredBms);
  http.on("/api/v1/bms/scan", HTTP_GET, onScan);
  http.on("/api/v1/bms", HTTP_PUT, onSaveBms);
  http.on("/api/v1/wifi", HTTP_POST, onSetWifi);
  http.on("/api/v1/telemetry", HTTP_GET, onTelemetry);
  http.onNotFound([] { error(404, "unknown_route"); });
  if (prefs.getString("ssid", "").isEmpty()) startSetup();
  else connectHome();
  http.begin();
}
void loop() {
  http.handleClient();
  if (!setupMode && WiFi.status() == WL_CONNECTED && !mdnsRunning) {
    mdnsRunning = MDNS.begin(hostName.c_str());
    if (mdnsRunning) MDNS.addService("http", "tcp", 80);
    Serial.printf("Smart JK local: http://%s.local or %s\n", hostName.c_str(), WiFi.localIP().toString().c_str());
  }
  if (restartAt && (int32_t)(millis() - restartAt) >= 0) {
    delay(150);
    ESP.restart();
  }
  // GPIO0 is a strapping pin; press BOOT AFTER startup for 4 seconds.
  if (digitalRead(SETUP_BUTTON) == LOW) {
    if (!buttonPressedAt) buttonPressedAt = millis();
    if (!setupMode && millis() - buttonPressedAt >= HOLD_MS) {
      buttonPressedAt = 0;
      startSetup();
    }
  } else {
    buttonPressedAt = 0;
  }
  delay(2);
}
