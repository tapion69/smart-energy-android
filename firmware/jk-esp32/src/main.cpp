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
#include "jk_write.h"
#include "setup_code_policy.h"
#include <ArduinoJson.h>
#include <ctype.h>

static const char *VERSION = "0.3.1-dev";
static const uint32_t WIFI_JOIN_TIMEOUT_MS = 30000; // Return to temporary setup after 30s
static const uint32_t SETUP_AUTORETRY_MS = 120000; // Retry saved Wi-Fi after brief fallback
static const int SETUP_BUTTON = 0; // BOOT is physical proof for pairing recovery.
static const uint32_t RECOVERY_HOLD_MS = 4000; // open short LAN recovery window
static const uint32_t SETUP_HOLD_MS = 10000;  // long hold enters setup AP
static const uint32_t RECOVERY_WINDOW_MS = 45000;
static const uint32_t PHYSICAL_PAIR_WINDOW_MS = 120000;
static const uint32_t PHYSICAL_CONFIRM_HOLD_MS = 2000;
static const uint32_t PHYSICAL_COMMIT_WINDOW_MS = 45000;

Preferences prefs;
WebServer http(80);
String hostName;
String setupSsid;
String setupCode;
bool setupMode = false;
bool automaticSetupFallback = false;
uint32_t setupFallbackAt = 0;
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
// Parameter writes are disabled by default, per-device, and expire quickly.
uint32_t writeArmUntil = 0;
String writeArmMac;

// A brief, physically requested, one-client onboarding AP. It is open ONLY
// during physical pairing, and no normal JK API is accessible over it.
// Stage a unique ticket and require a SECOND hardware BOOT confirmation before
// committing a new code. The code and Wi-Fi are never returned by an endpoint.
bool physicalPairing = false;
bool physicalStaged = false;
bool physicalConfirmed = false;
uint32_t physicalStartedAt = 0;
uint32_t physicalConfirmedAt = 0;
uint32_t physicalTicket = 0;
uint8_t physicalFailedStages = 0;
String physicalNewCode;
String physicalNewSsid;
String physicalNewWifiPass;


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
  if (physicalPairing) { error(423, "physical_pairing_in_progress"); return false; }
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
void connectHome();
void clearPhysicalPairing() {
  physicalPairing = false;
  physicalStaged = false;
  physicalConfirmed = false;
  physicalStartedAt = 0;
  physicalConfirmedAt = 0;
  physicalTicket = 0;
  physicalFailedStages = 0;
  physicalNewCode = "";
  physicalNewSsid = "";
  physicalNewWifiPass = "";
}

void startPhysicalPairing() {
  clearPhysicalPairing();
  automaticSetupFallback = false;
  setupFallbackAt = 0;
  recoveryWindowAt = 0;
  wifiAttemptAt = 0;
  if (mdnsRunning) { MDNS.end(); mdnsRunning = false; }
  WiFi.disconnect(true);
  WiFi.mode(WIFI_AP_STA);
  setupMode = true;
  physicalPairing = true;
  physicalStartedAt = millis();
  // Open AP cannot be avoided on a display-less board with no USB, sticker
  // secret or BLE enrollment. Physical proof is therefore required TWICE.
  const bool ok = WiFi.softAP(setupSsid.c_str(), nullptr, 1, false, 1);
  if (!ok) {
    clearPhysicalPairing();
    if (prefs.getString("ssid", "").isEmpty()) {
      // An unknown old WPA2 code is still safer than a permanent open AP.
      WiFi.softAP(setupSsid.c_str(), setupCode.c_str(), 1, false, 1);
    } else {
      connectHome();
    }
    return;
  }
  Serial.println("[Pairing] Temporary one-client AP open for 120 s; second BOOT required.");
}
void startSetup(bool automaticFallback = false) {
  clearPhysicalPairing();
  automaticSetupFallback = automaticFallback;
  setupFallbackAt = millis();
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
  Serial.println("Secret hidden; use physical BOOT to pair without USB.");
  Serial.println("Connect from APK only while pairing is active.");
  Serial.printf("Setup API: http://%s\n", WiFi.softAPIP().toString().c_str());
  Serial.println("==================================");
}
void connectHome() {
  clearPhysicalPairing();
  automaticSetupFallback = false;
  setupFallbackAt = 0;
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
  doc["identity_decoder_revision"] = "jk02-info-json-owned-4";
  doc["device_id"] = hostName;
  doc["setup_mode"] = setupMode;
  doc["wifi_auto_recovery"] = setupMode && automaticSetupFallback;
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
  doc["ble_simultaneous_target"] = 4;
  doc["ota"] = false;
  doc["parameter_writes"] = "experimental_low_risk_local_only";
  doc["physical_recovery"] = true; // does NOT reveal the old password.
  doc["boot_pairing"] = true;
  doc["physical_pairing_mode"] = physicalPairing &&
    (uint32_t)(millis()-physicalStartedAt)<PHYSICAL_PAIR_WINDOW_MS;
  doc["physical_pairing_requires_second_boot"] = true;
  doc["physical_pairing_seconds_left"] = physicalPairing ?
    (uint32_t)(millis()-physicalStartedAt)<PHYSICAL_PAIR_WINDOW_MS ?
      (PHYSICAL_PAIR_WINDOW_MS-(uint32_t)(millis()-physicalStartedAt))/1000 : 0
    : 0;
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
  if (items.isNull() || items.size() > 4) {
    error(400, "expected_0_to_4_bms");
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
  if (!setupcode::valid(newCode.c_str(), newCode.length())) {
    error(400, "code_ascii_printable_no_spaces");
    return;
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
  if (requestedDevice != hostName ||
      !setupcode::valid(newSecret.c_str(), newSecret.length())) {
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

bool physicalPairWindow() {
  return setupMode && physicalPairing && physicalStartedAt &&
         (uint32_t)(millis()-physicalStartedAt) < PHYSICAL_PAIR_WINDOW_MS;
}
bool physicalApClient() {
  if (!physicalPairWindow()) return false;
  const IPAddress peer = http.client().remoteIP();
  // Reject requests arriving on a different interface or from WAN/Tailnet.
  return peer[0] == 192 && peer[1] == 168 && peer[2] == 4 &&
         peer[3] >= 2 && peer[3] <= 254 &&
         WiFi.softAPgetStationNum() == 1;
}
void onPhysicalStage() {
  if (!physicalApClient()) {error(403,"physical_boot_setup_required");return;}
  if (physicalFailedStages >= 3) {error(429,"stage_attempts_exceeded");return;}
  ++physicalFailedStages;
  JsonDocument req;
  if (deserializeJson(req,http.arg("plain"))) {error(400,"invalid_json");return;}
  const String device = req["device_id"].as<String>();
  const String code = req["new_code"].as<String>();
  const String ssid = req["ssid"].as<String>();
  const String pass = req["wifi_password"].as<String>();
  if (device != hostName ||
      !setupcode::valid(code.c_str(), code.length())) {
    error(400,"invalid_device_or_code_8_to_32");return;
  }
  if ((!ssid.isEmpty() && (ssid.length()>32 || pass.length()<8 || pass.length()>63)) ||
      (ssid.isEmpty() && (!pass.isEmpty() || prefs.getString("ssid","").isEmpty()))) {
    error(400,"wifi_2_4ghz_credentials_required");return;
  }
  // New pairing attempt voids any previous staged/confirmed request.
  physicalNewCode=code;
  physicalNewSsid=ssid;
  physicalNewWifiPass=pass;
  physicalTicket=esp_random();
  if(!physicalTicket)physicalTicket=1;
  physicalStaged=true;
  physicalConfirmed=false;
  physicalConfirmedAt=0;
  JsonDocument reply;
  reply["staged"]=true;reply["requires_second_boot"]=true;
  reply["ticket"]=physicalTicket;
  reply["seconds_left"]=(PHYSICAL_PAIR_WINDOW_MS-(uint32_t)(millis()-physicalStartedAt))/1000;
  respond(202,reply);
}
void onPhysicalStatus() {
  if (!physicalApClient()) {error(403,"physical_boot_setup_required");return;}
  const uint32_t ticket=uint32_t(strtoul(http.arg("ticket").c_str(),nullptr,10));
  if(!physicalStaged || !ticket || ticket!=physicalTicket) {
    error(403,"unknown_pairing_ticket");return;
  }
  JsonDocument reply;
  reply["staged"]=true;
  reply["confirmed_by_boot"]=physicalConfirmed &&
     (uint32_t)(millis()-physicalConfirmedAt)<PHYSICAL_COMMIT_WINDOW_MS;
  reply["seconds_left"]=(PHYSICAL_PAIR_WINDOW_MS-(uint32_t)(millis()-physicalStartedAt))/1000;
  respond(200,reply);
}
void onPhysicalCommit() {
  if (!physicalApClient()) {error(403,"physical_boot_setup_required");return;}
  JsonDocument req;
  if(deserializeJson(req,http.arg("plain"))){error(400,"invalid_json");return;}
  const uint32_t ticket=req["ticket"].as<uint32_t>();
  if(!physicalStaged || !physicalConfirmed || !physicalTicket ||
     ticket!=physicalTicket ||
     (uint32_t)(millis()-physicalConfirmedAt)>=PHYSICAL_COMMIT_WINDOW_MS) {
    error(403,"second_boot_confirmation_required");return;
  }
  // Consume physical permission BEFORE altering any persistent state.
  physicalStaged=false;
  physicalConfirmed=false;
  physicalTicket=0;
  const bool codeOk=prefs.putString("setupCode",physicalNewCode)==physicalNewCode.length();
  if(!codeOk){error(500,"code_storage_failed");return;}
  setupCode=physicalNewCode;
  bool wifiOk=true;
  if(!physicalNewSsid.isEmpty()){
    wifiOk=prefs.putString("ssid",physicalNewSsid)==physicalNewSsid.length() &&
           prefs.putString("wifiPass",physicalNewWifiPass)==physicalNewWifiPass.length();
  }
  if(!wifiOk){error(500,"wifi_storage_failed_check_via_usb");return;}
  JsonDocument reply;
  reply["paired"]=true;
  reply["device_id"]=hostName;
  reply["wifi_saved"]=true;
  reply["restart_required"]=true;
  respond(200,reply);
  // Never leak chosen code via HTTP or serial; reboot to closed WPA2 or STA.
  physicalNewCode="";physicalNewSsid="";physicalNewWifiPass="";
  restartAt=millis()+2200;
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
// Only user-triggered, time-boxed commands. No recurring/automatic writes.
// This experimental API is not exposed through the existing VPN gateway.
bool allowedWriteConnection() {
  return !setupMode && WiFi.status()==WL_CONNECTED;
}
void onArmParameterWrite() {
  if(!requireControl())return;
  if(!allowedWriteConnection()){error(403,"local_wifi_required");return;}
  JsonDocument req;
  if(deserializeJson(req,http.arg("plain"))){error(400,"invalid_json");return;}
  String mac=req["mac"].as<String>();
  if(!jkreader::isConfigured(mac.c_str())){error(404,"bms_not_configured");return;}
  if(req["confirmation"].as<String>()!="I_CONFIRM_ONE_JK_WRITE"){
    error(403,"explicit_confirmation_required");return;
  }
  writeArmMac=mac;
  writeArmUntil=millis()+120000;
  JsonDocument out;
  out["ok"]=true;out["armed"]=true;
  out["expires_seconds"]=120;
  out["mac"]=mac;
  respond(200,out);
}
void onWriteParameter() {
  if(!requireControl())return;
  if(!allowedWriteConnection()){error(403,"local_wifi_required");return;}
  JsonDocument req;
  if(deserializeJson(req,http.arg("plain"))){error(400,"invalid_json");return;}
  String mac=req["mac"].as<String>();
  // One successful arm can authorize at most one command. Clear before
  // inspecting target/value to prevent accidental or replayed double writes.
  const bool armed = writeArmUntil && (int32_t)(writeArmUntil-millis())>0 &&
                     writeArmMac==mac;
  writeArmUntil=0;writeArmMac="";
  if(!armed){error(403,"write_permission_expired_or_not_armed");return;}
  if(req["confirmation"].as<String>()!="APPLY_TO_SELECTED_BMS"){
    error(403,"explicit_write_confirmation_required");return;
  }
  String key=req["key"].as<String>();
  const int idx=jkwrite::find(key.c_str());
  if(idx<0||!jkwrite::editable(size_t(idx))){
    error(403,"parameter_not_allowlisted");return;
  }
  JsonVariant value=req["value"];
  JsonVariant prior=req["expected_old"];
  const bool boolType=value.is<bool>();
  const bool numericType=value.is<int>()||value.is<float>()||value.is<double>();
  if(!(boolType||numericType) || prior.isNull() ||
       !(prior.is<bool>()||prior.is<int>()||prior.is<float>()||prior.is<double>())){
    error(400,"typed_value_and_expected_old_required");return;
  }
  if(jkwrite::isBoolean(size_t(idx))!=boolType){
    error(400,"parameter_type_mismatch");return;
  }
  const float desired=boolType?(value.as<bool>()?1.0f:0.0f):value.as<float>();
  const float expected=prior.is<bool>()?(prior.as<bool>()?1.0f:0.0f):prior.as<float>();
  String reason;
  if(!jkreader::requestWrite(mac.c_str(),key.c_str(),desired,boolType,expected,reason)){
    JsonDocument denied;
    denied["error"]=reason;
    respond(409,denied);return;
  }
  JsonDocument out;
  out["accepted"]=true;out["verified_by_bms"]=false;
  out["state"]="queued";
  out["mac"]=mac;out["key"]=key;
  respond(202,out);
}
void onParameterWriteStatus() {
  if(!requireControl())return;
  JsonDocument out;
  String mac=http.arg("mac");
  jkreader::fillWriteStatus(out,mac.c_str());
  respond(200,out);
}
void onTelemetry() {
  if (!requireControl()) return;
  JsonDocument doc;
  jkreader::fillTelemetry(doc);
  respond(200, doc);
}
void onDetails() {
  if (!requireControl()) return;
  JsonDocument doc;
  jkreader::fillDetails(doc);
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
  if (!setupcode::valid(setupCode.c_str(), setupCode.length())) {
    // Only absent/invalid credentials are regenerated; valid 8-11 byte user
    // codes must persist just like 12-32 byte codes after every reboot.
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
  http.on("/api/v1/pair/boot/stage", HTTP_POST, onPhysicalStage);
  http.on("/api/v1/pair/boot/status", HTTP_GET, onPhysicalStatus);
  http.on("/api/v1/pair/boot/commit", HTTP_POST, onPhysicalCommit);
  http.on("/api/v1/telemetry", HTTP_GET, onTelemetry);
  http.on("/api/v1/details", HTTP_GET, onDetails);
  http.on("/api/v1/jk/write-arm", HTTP_POST, onArmParameterWrite);
  http.on("/api/v1/jk/write", HTTP_POST, onWriteParameter);
  http.on("/api/v1/jk/write-status", HTTP_GET, onParameterWriteStatus);
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
      startSetup(true); // Retry saved Wi-Fi later; leave NVS and BLE selection intact.
    }
  }
  // When the router was temporarily unavailable, do NOT strand the reader
  // forever in setup AP. Retry automatically only if setup was entered by
  // a Wi-Fi failure and nobody is using the temporary setup connection.
  // Manual BOOT setup stays active until user configuration is complete.
  if (setupMode && automaticSetupFallback && setupFallbackAt &&
      (uint32_t)(millis() - setupFallbackAt) >= SETUP_AUTORETRY_MS &&
      WiFi.softAPgetStationNum() == 0 &&
      prefs.getString("ssid", "").length() > 0) {
    Serial.println("[WiFi] Retrying saved Wi-Fi after setup fallback (no NVS change).");
    connectHome();
  }
  if (restartAt && (int32_t)(millis() - restartAt) >= 0) {
    delay(150);
    ESP.restart();
  }
  // GPIO0 is a strapping pin; BOOT MUST be pressed only after power-up.
  // First 4-second press opens a 120-second one-client physical pairing AP.
  // After the Android app stages a new code, a SECOND 2-second press confirms
  // that a human is still physically present. No old code/USB is needed.
  if (digitalRead(SETUP_BUTTON) == LOW) {
    if (!buttonPressedAt) buttonPressedAt = millis();
  } else if (buttonPressedAt) {
    const uint32_t heldFor=(uint32_t)(millis()-buttonPressedAt);
    buttonPressedAt=0;
    if (physicalPairWindow() && physicalStaged && !physicalConfirmed &&
        heldFor>=PHYSICAL_CONFIRM_HOLD_MS && heldFor<SETUP_HOLD_MS) {
      physicalConfirmed=true;
      physicalConfirmedAt=millis();
      Serial.println("[Pairing] Second BOOT confirmation received for pending device.");
    } else if (!physicalPairing && heldFor>=RECOVERY_HOLD_MS &&
               heldFor<SETUP_HOLD_MS) {
      wifiIssue="physical_pairing";
      startPhysicalPairing();
    } else if (!physicalPairing && heldFor>=SETUP_HOLD_MS) {
      wifiIssue="manual_setup";
      startSetup(false);
    }
  }
  // The pairing AP must NEVER remain open indefinitely.
  if (physicalPairing && (uint32_t)(millis()-physicalStartedAt)>=PHYSICAL_PAIR_WINDOW_MS) {
    Serial.println("[Pairing] Physical window expired; shutting down open AP.");
    if(prefs.getString("ssid","").isEmpty())startSetup(false);
    else connectHome();
  }
  if (recoveryWindowAt && !physicalWindowOpen()) {
    recoveryWindowAt = 0;
  }
  delay(2);
}
