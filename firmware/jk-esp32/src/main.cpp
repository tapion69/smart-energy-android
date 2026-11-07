// Smart Energy JK - standalone telemetry and physical recovery 0.1.8-dev
// JK02 telemetry is experimental. LAN recovery requires physical BOOT confirmation.
// Target: classic ESP32 DevKit (esp32dev), flash a spare unit, not the HA proxy.
#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <ESPmDNS.h>
#include <NimBLEDevice.h>
#include "jk_reader.h"
#include <ArduinoJson.h>
#include <ctype.h>

static const char *VERSION = "0.1.9-dev";
static const uint32_t WIFI_JOIN_TIMEOUT_MS = 30000; // Return to setup after 30s
static const int SETUP_BUTTON = 0; // BOOT is physical proof for pairing recovery.
static const uint32_t RECOVERY_HOLD_MS = 4000; // open short LAN recovery window
static const uint32_t SETUP_HOLD_MS = 10000;  // long hold enters setup AP
static const uint32_t RECOVERY_WINDOW_MS = 45000;

Preferences prefs;
WebServer http(80);
String hostName;
String setupSsid;
String setupCode;
bool setupMode = false;
bool mdnsRunning = false;
uint32_t nextMdnsAttemptAt = 0;
uint32_t restartAt = 0;
uint32_t buttonPressedAt = 0;
uint32_t recoveryWindowAt = 0; // nonzero only after BOOT pressed 4s
uint8_t recoveryAttempts = 0;
bool recoveryHoldTriggered = false;
bool setupHoldTriggered = false;
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
bool requireControl() {
  // Development-only LAN access: a unique per-device code must be supplied.
  // TODO production: replace plaintext HTTP with authenticated encrypted channel.
  if (http.header("X-Setup-Code") != setupCode) {
    error(401, "invalid_setup_code");
    return false;
  }
  return true;
}
bool requireSetup() {
  if (!setupMode) {
    error(403, "setup_mode_required");
    return false;
  }
  return requireControl();
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
  recoveryWindowAt = 0;
  wifiAttemptAt = 0;
  nextMdnsAttemptAt = 0;
  if (mdnsRunning) {
    MDNS.end();
    mdnsRunning = false;
  }
  WiFi.disconnect(true);
  // AP + STA is needed to scan nearby 2.4 GHz routers while pairing.
  WiFi.mode(WIFI_AP_STA);
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
  recoveryWindowAt = 0;
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
  doc["lan_management"] = true;
  doc["wifi_scan"] = true;
  doc["change_code"] = true;
  doc["telemetry"] = true;
  doc["telemetry_experimental"] = true;
  doc["ota"] = false;
  doc["physical_recovery"] = true; // does NOT reveal the old password.
  respond(200, doc);
}
void onConfiguredBms() {
  if (!requireControl()) return;
  JsonDocument doc;
  String saved = prefs.getString("bmsList", "[]");
  deserializeJson(doc, saved);
  JsonDocument out;
  out["ok"] = true;
  out["bms"] = doc.as<JsonArray>();
  respond(200, out);
}
void onScan() {
  if (!requireControl()) return;
  if (!jkreader::acquireRadio(1800)) { error(503, "ble_radio_busy_retry"); return; }
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
  jkreader::releaseRadio();
  respond(200, doc);
}
void onSaveBms() {
  if (!requireControl()) return;
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
  jkreader::setConfigured(target);
  JsonDocument resp;
  resp["ok"] = true;
  resp["saved"] = target.size();
  respond(200, resp);
}

// Scan router networks on the ESP32 itself so Android only asks for Wi-Fi password.
// Only 2.4 GHz networks can be seen by classic ESP32.
void onWifiScan() {
  if (!requireControl()) return;
  if (!jkreader::acquireRadio(2000)) { error(503, "radio_busy_retry"); return; }
  int count = WiFi.scanNetworks(false, true);
  if (count < 0) {
    jkreader::releaseRadio();
    error(503, "wifi_scan_failed");
    return;
  }
  JsonDocument doc;
  doc["ok"] = true;
  doc["supports_5ghz"] = false;
  JsonArray networks = doc["networks"].to<JsonArray>();
  String seen[40];
  size_t seenCount = 0;
  // scanNetworks sorts strongest to weakest, keep strongest instance per SSID.
  for (int i = 0; i < count && i < 40; ++i) {
    String ssid = WiFi.SSID(i);
    if (ssid.isEmpty()) continue;
    bool duplicate = false;
    for (size_t j = 0; j < seenCount; ++j) {
      if (seen[j] == ssid) { duplicate = true; break; }
    }
    if (duplicate) continue;
    seen[seenCount++] = ssid;
    JsonObject network = networks.add<JsonObject>();
    network["ssid"] = ssid;
    network["rssi"] = WiFi.RSSI(i);
    network["channel"] = WiFi.channel(i);
    network["secured"] = WiFi.encryptionType(i) != WIFI_AUTH_OPEN;
  }
  WiFi.scanDelete();
  jkreader::releaseRadio();
  doc["count"] = networks.size();
  respond(200, doc);
}

// Change the per-device Wi-Fi AP/API password after authenticating.
// WPA2 requires >=8 characters; never use a universal 4-digit default as a key.
void onChangeCode() {
  if (!requireControl()) return;
  JsonDocument request;
  if (deserializeJson(request, http.arg("plain"))) {
    error(400, "invalid_json");
    return;
  }
  String newCode = request["new_code"].as<String>();
  if (newCode.length() < 8 || newCode.length() > 32) {
    error(400, "code_length_8_to_32_required");
    return;
  }
  for (size_t i = 0; i < newCode.length(); ++i) {
    unsigned char ch = (unsigned char)newCode[i];
    if (ch < 33 || ch > 126) {
      error(400, "code_ascii_printable_no_spaces");
      return;
    }
  }
  if (prefs.putString("setupCode", newCode) != newCode.length()) {
    error(500, "storage_failed");
    return;
  }
  // Accepted under old code; new requests must use the new code.
  setupCode = newCode;
  JsonDocument result;
  result["ok"] = true;
  result["updated"] = true;
  respond(200, result);
  // If the AP is in use, recreate it with the new code after response.
  if (setupMode) restartAt = millis() + 2200;
}

// Developer prototype: remote LAN requests alone can NEVER reveal or rotate
// the per-device secret. BOOT held physically for 4s creates a 45s window.
// New code is user-device-specific random, not the old secret. Not production
// transport: HTTP on LAN remains unencrypted; do NOT port-forward this API.
bool physicalWindowOpen() {
  return !setupMode && WiFi.status() == WL_CONNECTED &&
         recoveryWindowAt != 0 &&
         (uint32_t)(millis() - recoveryWindowAt) < RECOVERY_WINDOW_MS;
}

void onPhysicalRecovery() {
  if (!physicalWindowOpen()) {
    error(403, "press_boot_for_four_seconds_then_retry_within_45s");
    return;
  }
  if (recoveryAttempts >= 3) {
    recoveryWindowAt = 0;
    error(429, "recovery_attempt_limit");
    return;
  }
  recoveryAttempts++;
  JsonDocument incoming;
  if (deserializeJson(incoming, http.arg("plain"))) {
    error(400, "invalid_json");
    return;
  }
  String requestedDevice = incoming["device_id"].as<String>();
  String newSecret = incoming["new_code"].as<String>();
  if (requestedDevice != hostName || newSecret.length() < 16 || newSecret.length() > 32) {
    error(400, "invalid_recovery_request");
    return;
  }
  for (size_t i = 0; i < newSecret.length(); ++i) {
    const unsigned char ch = (unsigned char)newSecret[i];
    if (ch < 33 || ch > 126) {
      error(400, "invalid_recovery_code");
      return;
    }
  }
  if (prefs.putString("setupCode", newSecret) != newSecret.length()) {
    error(500, "recovery_storage_failed");
    return;
  }
  setupCode = newSecret;
  // One physical confirmation authorizes exactly one successful recovery.
  recoveryWindowAt = 0;
  JsonDocument result;
  result["ok"] = true;
  result["paired"] = true;
  result["device_id"] = hostName;
  result["restart_required"] = false;
  respond(200, result);
  Serial.println("[Pairing] Physical recovery approved; access code rotated. Wi-Fi and JK saved.");
}

void onSetWifi() {
  // The same paired device can update Wi-Fi from the trusted home LAN.
  // Prototype uses plaintext HTTP and MUST NOT be exposed to the Internet.
  if (!requireControl()) return;
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
  resp["note"] = "Wi-Fi accepted; restarting ESP32. Configuration stays in NVS.";
  respond(200, resp);
  restartAt = millis() + 2200;
}
void onTelemetry() {
  if (!requireControl()) return;
  JsonDocument doc;
  jkreader::fillTelemetry(doc);
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
  jkreader::loadConfigured(prefs.getString("bmsList", "[]"));
  WiFi.onEvent(onWifiEvent);

  const char *headerKeys[] = {"X-Setup-Code"};
  http.collectHeaders(headerKeys, 1);
  http.on("/api/v1/info", HTTP_GET, onInfo);
  http.on("/api/v1/bms", HTTP_GET, onConfiguredBms);
  http.on("/api/v1/bms/scan", HTTP_GET, onScan);
  http.on("/api/v1/bms", HTTP_PUT, onSaveBms);
  http.on("/api/v1/wifi", HTTP_POST, onSetWifi);
  http.on("/api/v1/wifi/scan", HTTP_GET, onWifiScan);
  http.on("/api/v1/access", HTTP_POST, onChangeCode);
  http.on("/api/v1/pair/recover", HTTP_POST, onPhysicalRecovery);
  http.on("/api/v1/telemetry", HTTP_GET, onTelemetry);
  http.onNotFound([] { error(404, "unknown_route"); });
  if (prefs.getString("ssid", "").isEmpty()) startSetup();
  else connectHome();
  http.begin();
  jkreader::begin();
}
void loop() {
  http.handleClient();
  if (!setupMode && WiFi.status() == WL_CONNECTED) {
    if (!everConnected) {
      everConnected = true;
      wifiIssue = "";
      Serial.printf("[WiFi] Connected: %s\n", WiFi.localIP().toString().c_str());
    }
    wifiAttemptAt = 0;
    if (!mdnsRunning) {
      mdnsRunning = MDNS.begin(hostName.c_str());
      if (mdnsRunning) MDNS.addService("http", "tcp", 80);
      Serial.printf("Smart JK local: http://%s.local or %s\n",
                    hostName.c_str(), WiFi.localIP().toString().c_str());
    }
  }
  if (!setupMode && WiFi.status() != WL_CONNECTED) {
    if (!wifiAttemptAt) {
      wifiAttemptAt = millis();
      if (mdnsRunning) { MDNS.end(); mdnsRunning = false; }
      Serial.println("[WiFi] Link lost; retrying before returning to setup.");
    }
    if ((uint32_t)(millis() - wifiAttemptAt) >= WIFI_JOIN_TIMEOUT_MS) {
      wifiIssue = everConnected ? "link_lost_timeout" : "join_timeout";
      Serial.printf("[WiFi] Failed after %lu seconds: status=%d reason=%u. Reopening setup AP.\n",
                    (unsigned long)(WIFI_JOIN_TIMEOUT_MS / 1000), (int)WiFi.status(),
                    (unsigned)disconnectReason);
      startSetup(); // Credentials + selected JK remain in Preferences.
    }
  }
  if (restartAt && (int32_t)(millis() - restartAt) >= 0) {
    delay(150);
    ESP.restart();
  }
  // GPIO0 is a strapping pin: press only AFTER startup.
  // 4s opens recovery without disrupting Wi-Fi, 10s opens the setup AP.
  // Neither action reveals setupCode over HTTP.
  if (digitalRead(SETUP_BUTTON) == LOW) {
    if (!buttonPressedAt) buttonPressedAt = millis();
    const uint32_t heldFor = (uint32_t)(millis() - buttonPressedAt);
    if (!recoveryHoldTriggered && !setupMode && WiFi.status() == WL_CONNECTED &&
        heldFor >= RECOVERY_HOLD_MS) {
      recoveryHoldTriggered = true;
      recoveryWindowAt = millis();
      recoveryAttempts = 0;
      Serial.println("[Pairing] Physical confirmation: LAN recovery open for 45 seconds.");
    }
    if (!setupHoldTriggered && !setupMode && heldFor >= SETUP_HOLD_MS) {
      setupHoldTriggered = true;
      recoveryWindowAt = 0;
      wifiIssue = "manual_setup";
      startSetup();
    }
  } else {
    buttonPressedAt = 0;
    recoveryHoldTriggered = false;
    setupHoldTriggered = false;
  }
  if (recoveryWindowAt && !physicalWindowOpen()) {
    recoveryWindowAt = 0;
  }
  delay(2);
}
