// JK02 V19 read-only persistent BLE telemetry, standalone ESP32.
// Uses the same documented FFE0/FFE1 handshake and 300-byte frame profiles as
// the author's Smart JK-BMS Bluetooth Home Assistant project.
// This worker never sends BMS parameter writes, MOS commands, or SOC changes.
// Runtime measurements are provisional and MUST NOT control safety logic.
#include "jk_reader.h"
#include "jk_protocol.h"
#include "jk_extended.h"
#include "jk_write.h"
#include <NimBLEDevice.h>
// Inspect only the *initiating* GAP procedure. This does not interrupt any
// established JK-BMS connection (NimBLE error 2 = BLE_HS_EALREADY).
extern "C" {
#include "host/ble_gap.h"
#include "host/ble_hs.h"
}
#include <WiFi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#include <string.h>
#include <new>

extern bool setupMode; // main.cpp manages Wi-Fi provisioning state.
namespace jkreader {
namespace {
static constexpr uint8_t MAX_BMS = 4;
static constexpr uint32_t FRESH_MS = 20000; // never present 30s+ readings as live
struct Device {
  char mac[18] = {};
  char state[30] = {};
  smartjk::Reading reading{};
  jkextra::Reading extra{};
  jkextra::Settings settings{};
  jkextra::Info info{};
  uint32_t lastSettingsMs = 0;
  uint32_t lastInfoMs = 0;
  uint32_t lastReadingMs = 0;
  uint32_t lastAttemptMs = 0;
  uint32_t badFrames = 0;
  uint32_t rejectedMeasurements = 0;
  uint32_t rejectedIdentityFrames = 0;
  uint32_t seenType3Frames = 0;
  char lastIdentityFailure[36] = {};
  bool bleConnected = false;
  // Per-BMS diagnostics; no keys, payloads, or secrets are ever retained.
  uint32_t scanAttempts = 0;
  uint32_t scanMisses = 0;
  uint32_t connectAttempts = 0;
  uint32_t connectFailures = 0;
  uint32_t clientFailures = 0;
  uint32_t serviceFailures = 0;
  uint32_t subscribeFailures = 0;
  int lastBleError = 0;
  uint32_t gapInitiatorBusy = 0;
  uint32_t gapInitiatorCancelled = 0;
  uint32_t gapInitiatorStillBusy = 0;
  uint32_t connections = 0;
  uint32_t disconnections = 0;
  // One-shot, non-optimistic parameter write. 0=idle, 1=queued, 2=sent,
  // 3=confirmed, 4=mismatch, 5=send_failed, 6=timeout, 7=link_lost.
  uint8_t writePhase = 0;
  int8_t writeIndex = -1;
  float writeTarget = 0;
  uint32_t writeAt = 0;
  uint32_t writeSentAt = 0;
  bool writeMismatchSeen = false;
};
static Device devices[MAX_BMS];
static uint8_t configured = 0;
static portMUX_TYPE stateMux = portMUX_INITIALIZER_UNLOCKED;
static SemaphoreHandle_t radioMutex = nullptr;
struct Session {
  uint8_t index = 0;
  char mac[18] = {};
  smartjk::Assembler assembler;
  NimBLEClient *client = nullptr;
  NimBLERemoteCharacteristic *notify = nullptr;
  bool subscribed = false;
};
static Session sessions[MAX_BMS];
static volatile bool maintenanceRequested = false;

void updateState(int index, const char *mac, const char *message) {
  portENTER_CRITICAL(&stateMux);
  if (index >= 0 && index < configured &&
      strcmp(devices[index].mac, mac) == 0) {
    strncpy(devices[index].state, message, sizeof(devices[index].state) - 1);
    devices[index].state[sizeof(devices[index].state) - 1] = '\0';
    devices[index].lastAttemptMs = millis();
  }
  portEXIT_CRITICAL(&stateMux);
}

enum class BleEvent : uint8_t {
  Scan, NotSeen, Connect, NoClient, ConnectFailed, ServiceFailed,
  SubscribeFailed, Connected, GapBusy, GapCancelled, GapStillBusy
};
void markBleEvent(int index, const char *mac, BleEvent event, int error = 0) {
  portENTER_CRITICAL(&stateMux);
  if (index >= 0 && index < configured &&
      strcmp(devices[index].mac, mac) == 0) {
    Device &item = devices[index];
    switch (event) {
      case BleEvent::Scan: ++item.scanAttempts; break;
      case BleEvent::NotSeen: ++item.scanMisses; break;
      case BleEvent::Connect: ++item.connectAttempts; break;
      case BleEvent::NoClient: ++item.clientFailures; break;
      case BleEvent::ConnectFailed: ++item.connectFailures; item.lastBleError = error; break;
      case BleEvent::ServiceFailed: ++item.serviceFailures; item.lastBleError = error; break;
      case BleEvent::SubscribeFailed: ++item.subscribeFailures; item.lastBleError = error; break;
      case BleEvent::Connected: item.lastBleError = 0; break;
      case BleEvent::GapBusy: ++item.gapInitiatorBusy; break;
      case BleEvent::GapCancelled: ++item.gapInitiatorCancelled; break;
      case BleEvent::GapStillBusy: ++item.gapInitiatorStillBusy; break;
    }
  }
  portEXIT_CRITICAL(&stateMux);
}

// Callback gets ONLY a frame with a verified 300-byte header and checksum.
// Never preserve raw frames, as type 3 includes JK device passcodes.
void onValidatedFrame(const uint8_t *frame, const smartjk::Reading *basic, void *context) {
  Session *session = static_cast<Session *>(context);
  if (!session) return;
  const int index = session->index;
  const uint8_t type = frame[4];
  jkextra::Reading extra{};
  jkextra::Settings settings{};
  jkextra::Info info{};
  if (type == 2 && basic) {
    if (!jkextra::decode(frame, *basic, extra)) return;
  } else if (type == 1) {
    if (!jkextra::decodeSettings(frame, settings)) return;
  } else if (type == 3) {
    // Keep only safe classification counters, never raw type-3 frame bytes:
    // those frames may include BMS passcodes and personal fields.
    char prefix[17] = {};
    jkextra::readPrintable(frame, 6, 16, prefix, sizeof(prefix));
    const char *rejectHint = jkextra::likelyJKModel(prefix) ?
      "jk_prefix_versions_unusable" :
      ((frame[6] == '{' || frame[6] == '"') ?
       "json_like_or_shifted_layout" : "unknown_model_prefix");
    const bool recognized = jkextra::decodeInfo(frame, info);
    portENTER_CRITICAL(&stateMux);
    if (index < configured && strcmp(devices[index].mac, session->mac) == 0) {
      Device &item = devices[index];
      item.seenType3Frames++;
      if (!recognized) {
        item.rejectedIdentityFrames++;
        strncpy(item.lastIdentityFailure, rejectHint, sizeof(item.lastIdentityFailure)-1);
        item.lastIdentityFailure[sizeof(item.lastIdentityFailure)-1] = 0;
      }
    }
    portEXIT_CRITICAL(&stateMux);
    if (!recognized) return;
  } else {
    return;
  }
  const uint32_t now = millis();
  portENTER_CRITICAL(&stateMux);
  if (index < configured &&
      strcmp(devices[index].mac, session->mac) == 0) {
    Device &item = devices[index];
    if (type == 2 && basic) {
      item.reading = *basic;
      item.extra = extra;
      item.lastReadingMs = now;
      strncpy(item.state, "valid_sample", sizeof(item.state) - 1);
    } else if (type == 1) {
      item.settings = settings;
      item.lastSettingsMs = now;
      if (item.writePhase == 2 && item.writeSentAt &&
          (uint32_t)(now - item.writeSentAt) < 16000 &&
          item.writeIndex >= 0 && item.writeIndex < int(jkextra::PARAM_COUNT)) {
        const size_t k = size_t(item.writeIndex);
        const bool matches = settings.available[k] &&
          fabsf(settings.value[k] - item.writeTarget) < 0.0011f;
        if(matches) item.writePhase = 3;
        else item.writeMismatchSeen = true; // Later fresh frame may still confirm.
      }
    } else if (type == 3) {
      item.info = info;
      item.lastInfoMs = now;
    }
  }
  portEXIT_CRITICAL(&stateMux);
}

void onNotify(NimBLERemoteCharacteristic *chr, uint8_t *data, size_t len, bool) {
  if (!chr || !data || !len) return;
  for (Session &session : sessions) {
    if (session.subscribed && session.notify == chr) {
      smartjk::Reading ignored{};
      session.assembler.append(data, len, ignored, onValidatedFrame, &session);
      return;
    }
  }
}

void sendReadCommand(NimBLERemoteCharacteristic *characteristic, uint8_t command,
                     bool withResponse) {
  uint8_t bytes[20] = {0xAA, 0x55, 0x90, 0xEB};
  bytes[4] = command;
  unsigned sum = 0;
  for (int i = 0; i < 19; ++i) sum += bytes[i];
  bytes[19] = sum & 0xff;
  // JK02 0x97 (device info) and 0x96 (settings) are READ-ONLY queries.
  // A validated type-2 telemetry frame normally follows automatically.
  if (!characteristic->writeValue(bytes, sizeof(bytes), withResponse)) {
    Serial.printf("[JK] Read-only request 0x%02X not acknowledged\n", command);
  }
}

// The BlueZ add-on serializes *discovery*, not persistent GATT sessions.
// NimBLE's ESP32 controller additionally allows only one pending GAP
// initiation at a time. A cancelled/timed-out attempt can remain in progress
// briefly after connect() returns. Under radioMutex it is safe to let that
// stale procedure finish, or cancel the *pending initiation only* when stuck.
// Never reset NimBLE, disconnect another client, or rotate BMS connections.
bool waitForGapIdle(uint32_t waitMs) {
  const uint32_t start = millis();
  while (ble_gap_conn_active() && (uint32_t)(millis()-start)<waitMs)
    vTaskDelay(pdMS_TO_TICKS(50));
  return ble_gap_conn_active()==0;
}
bool ensureGapIdle(int index, const char *mac) {
  if (!ble_gap_conn_active()) return true;
  markBleEvent(index,mac,BleEvent::GapBusy);
  if (waitForGapIdle(1800)) return true;
  // At this point no worker is starting a new connection (radioMutex held).
  // This API cancels an in-flight central *initiation* only, not a link.
  const int rc=ble_gap_conn_cancel();
  if (rc==0 || rc==BLE_HS_EALREADY)
    markBleEvent(index,mac,BleEvent::GapCancelled);
  const bool idle=waitForGapIdle(2000);
  if(!idle){
    markBleEvent(index,mac,BleEvent::GapStillBusy);
    updateState(index,mac,"ble_gap_busy_retry");
    Serial.printf("[JK] GAP initiator still busy; slot %d, cancel rc=%d\\n",index+1,rc);
  }
  // Allow NimBLE host's connect-failed callback to release the old client.
  if(idle) vTaskDelay(pdMS_TO_TICKS(180));
  return idle;
}

void pollOne(Session &session, const char *mac) {
  const int index = session.index;
  strncpy(session.mac, mac, sizeof(session.mac) - 1);
  session.mac[sizeof(session.mac)-1] = 0;
  if (!ensureGapIdle(index,mac)) { xSemaphoreGive(radioMutex); return; }
  updateState(index, mac, "searching_ble");
  markBleEvent(index, mac, BleEvent::Scan);
  NimBLEScan *scanner = NimBLEDevice::getScan();
  // Passive, duty-limited scans are less disruptive to the already established
  // JK notification stream; discovery still uses each device's MAC.
  scanner->setActiveScan(false);
  scanner->setInterval(120);
  scanner->setWindow(48);
  scanner->setMaxResults(40);
  NimBLEScanResults results = scanner->getResults(5000, false);
  const NimBLEAdvertisedDevice *match = nullptr;
  for (int i = 0; i < results.getCount(); ++i) {
    const NimBLEAdvertisedDevice *item = results.getDevice(i);
    if (!item) continue;
    String observed = String(item->getAddress().toString().c_str());
    observed.toUpperCase();
    if (observed == mac) { match = item; break; }
  }
  if (!match) {
    scanner->clearResults();
    markBleEvent(index, mac, BleEvent::NotSeen);
    updateState(index, mac, "ble_not_seen");
    xSemaphoreGive(radioMutex);
    return;
  }
  // A previous connect may still be completing after scan(); do not race it.
  if(!ensureGapIdle(index,mac)) {
    scanner->clearResults();
    xSemaphoreGive(radioMutex);
    return;
  }
  updateState(index, mac, "connecting_ble");
  markBleEvent(index, mac, BleEvent::Connect);
  NimBLEClient *client = NimBLEDevice::createClient();
  if (!client) {
    scanner->clearResults();
    markBleEvent(index, mac, BleEvent::NoClient);
    updateState(index, mac, "ble_client_unavailable");
    xSemaphoreGive(radioMutex);
    return;
  }
  client->setConnectTimeout(9000);
  const bool connected = client->connect(match);
  scanner->clearResults();
  if (!connected) {
    const int bleError = client->getLastError();
    markBleEvent(index, mac, BleEvent::ConnectFailed, bleError);
    Serial.printf("[JK] BLE client connect failed, slot=%d, code=%d\n", index + 1, bleError);
    updateState(index, mac, "ble_connect_failed");
    NimBLEDevice::deleteClient(client);
    // Retry only after the previous initiating procedure is idle. In
    // particular BLE_HS_EALREADY (2) is NOT a BMS fault.
    if (bleError == BLE_HS_EALREADY || ble_gap_conn_active()) {
      ensureGapIdle(index,mac);
    }
    xSemaphoreGive(radioMutex);
    return;
  }
  NimBLERemoteService *service =
      client->getService(NimBLEUUID("0000ffe0-0000-1000-8000-00805f9b34fb"));
  NimBLERemoteCharacteristic *characteristic =
      service ? service->getCharacteristic(NimBLEUUID("0000ffe1-0000-1000-8000-00805f9b34fb")) : nullptr;
  if (!characteristic || !characteristic->canNotify() ||
      !(characteristic->canWriteNoResponse() || characteristic->canWrite())) {
    markBleEvent(index, mac, BleEvent::ServiceFailed, client->getLastError());
    updateState(index, mac, "ffe1_unavailable");
    client->disconnect();
    NimBLEDevice::deleteClient(client);
    xSemaphoreGive(radioMutex);
    return;
  }
  session.assembler.reset();
  session.client = client;
  session.notify = characteristic;
  session.subscribed = true;
  updateState(index, mac, "waiting_frames");
  if (!characteristic->subscribe(true, onNotify)) {
    session.subscribed = false;
    session.notify = nullptr;
    session.client = nullptr;
    markBleEvent(index, mac, BleEvent::SubscribeFailed, client->getLastError());
    updateState(index, mac, "ble_subscribe_failed");
    client->disconnect();
    NimBLEDevice::deleteClient(client);
    xSemaphoreGive(radioMutex);
    return;
  }

  portENTER_CRITICAL(&stateMux);
  if (index < configured && strcmp(devices[index].mac, mac) == 0) {
    devices[index].bleConnected = true;
    devices[index].connections++;
  }
  portEXIT_CRITICAL(&stateMux);
  // Keep the radio handshake serialized until the initial subscription and
  // requests are complete; another worker must not start a scan here.
  markBleEvent(index, mac, BleEvent::Connected);
  Serial.printf("[JK] BLE connected to slot %d (persistent session)\n", index + 1);

  // Query metadata/settings ONCE to start unsolicited JK02 telemetry stream.
  // All commands are READ-ONLY; never send SOC / MOS / setting writes.
  const bool withResponse = !characteristic->canWriteNoResponse();
  sendReadCommand(characteristic, 0x97, withResponse);
  vTaskDelay(pdMS_TO_TICKS(650));
  if (client->isConnected()) sendReadCommand(characteristic, 0x96, withResponse);
  xSemaphoreGive(radioMutex);
  uint32_t lastRefreshAt = millis();
  uint32_t lastSettingsRequestAt = millis();
  uint32_t lastInfoRequestAt = millis();


  while (client->isConnected() && !setupMode && WiFi.status() == WL_CONNECTED) {
    // Maintenance blocks new scans, not existing active streams.
    bool configuredStill = false;
    uint32_t lastReadingAt = 0;
    portENTER_CRITICAL(&stateMux);
    if (index < configured && strcmp(devices[index].mac, mac) == 0) {
      configuredStill = true;
      lastReadingAt = devices[index].lastReadingMs;
    }
    portEXIT_CRITICAL(&stateMux);
    if (!configuredStill) break;


    const uint32_t now = millis();
    int pendingIndex = -1;
    float pendingTarget = 0;
    portENTER_CRITICAL(&stateMux);
    if (index < configured && strcmp(devices[index].mac, mac) == 0) {
      Device &item=devices[index];
      if (item.writePhase == 1) {
        pendingIndex = item.writeIndex;
        pendingTarget = item.writeTarget;
        item.writePhase = 2;
        item.writeSentAt = now;
      } else if (item.writePhase == 2 && item.writeSentAt &&
                 (uint32_t)(now - item.writeSentAt) > 15000) {
        item.writePhase = item.writeMismatchSeen ? 4 : 6;
        // Never automatically repeat a timed-out or mismatched write.
      }
    }
    portEXIT_CRITICAL(&stateMux);
    if (pendingIndex >= 0) {
      uint8_t packet[20] = {};
      const bool valid = jkwrite::build(size_t(pendingIndex),pendingTarget,
                                       jkwrite::isBoolean(size_t(pendingIndex)),packet);
      const bool sent = valid && characteristic->writeValue(packet,sizeof(packet),withResponse);
      if (!sent) {
        portENTER_CRITICAL(&stateMux);
        if (index < configured && strcmp(devices[index].mac,mac)==0 &&
            devices[index].writePhase == 2) devices[index].writePhase = 5;
        portEXIT_CRITICAL(&stateMux);
      } else {
        vTaskDelay(pdMS_TO_TICKS(350));
        if (client->isConnected()) sendReadCommand(characteristic,0x96,withResponse);
      }
    }
    // Keep settings and info reasonably fresh for the read-only UI even if
    // somebody else changed the BMS configuration via the official app.
    if ((uint32_t)(now-lastSettingsRequestAt) >= 300000) {
      sendReadCommand(characteristic, 0x96, withResponse);
      lastSettingsRequestAt = now;
    }
    if ((uint32_t)(now-lastInfoRequestAt) >= 900000) {
      sendReadCommand(characteristic, 0x97, withResponse);
      lastInfoRequestAt = now;
    }
    // JK02 usually pushes type-2 updates automatically. Only ask for 0x96 if
    // no validated sample arrived for >= 8 s, throttled to one request / 8 s.
    // Never disconnect a healthy BLE link just to refresh values.
    if ((lastReadingAt == 0 || (uint32_t)(now - lastReadingAt) >= 8000) &&
        (uint32_t)(now - lastRefreshAt) >= 8000) {
      sendReadCommand(characteristic, 0x96, withResponse);
      lastRefreshAt = now;
      if (!lastReadingAt || (uint32_t)(now - lastReadingAt) > 30000) {
        updateState(index, mac, "waiting_frames");
      }
    }
    vTaskDelay(pdMS_TO_TICKS(250));
  }

  session.subscribed = false;
  if (client->isConnected()) {
    characteristic->unsubscribe();
    client->disconnect();
  }
  portENTER_CRITICAL(&stateMux);
  if (index < configured && strcmp(devices[index].mac, mac) == 0) {
    devices[index].bleConnected = false;
    devices[index].disconnections++;
    if (devices[index].writePhase==1 || devices[index].writePhase==2)
      devices[index].writePhase = 7; // Lost; never auto-retry.
    devices[index].badFrames += session.assembler.badCrc;
    devices[index].rejectedMeasurements += session.assembler.rejectedMeasurements;
  }
  portEXIT_CRITICAL(&stateMux);
  session.notify = nullptr;
  session.client = nullptr;
  NimBLEDevice::deleteClient(client);
  Serial.printf("[JK] BLE session ended: %s, reason=link_or_config_lost\n", mac);
  updateState(index, mac, "ble_disconnected");
}

void worker(void *arg) {
  Session *session = static_cast<Session *>(arg);
  if (!session) { vTaskDelete(nullptr); return; }
  const uint8_t index = session->index;
  uint8_t failures = 0;
  while (true) {
    if (setupMode || WiFi.status() != WL_CONNECTED || maintenanceRequested) {
      vTaskDelay(pdMS_TO_TICKS(500)); continue;
    }
    char mac[18] = {};
    portENTER_CRITICAL(&stateMux);
    if (index < configured) strncpy(mac, devices[index].mac, sizeof(mac)-1);
    portEXIT_CRITICAL(&stateMux);
    if (!mac[0]) { vTaskDelay(pdMS_TO_TICKS(1200)); continue; }
    if (radioMutex && xSemaphoreTake(radioMutex, pdMS_TO_TICKS(6500)) == pdTRUE) {
      if (!maintenanceRequested && !setupMode) {
        uint32_t before = 0;
        portENTER_CRITICAL(&stateMux);
        if (index < configured && strcmp(devices[index].mac, mac) == 0)
          before = devices[index].lastReadingMs;
        portEXIT_CRITICAL(&stateMux);
        pollOne(*session, mac); // releases radioMutex after connecting.
        bool sample = false;
        portENTER_CRITICAL(&stateMux);
        if (index < configured && strcmp(devices[index].mac, mac) == 0)
          sample = before != devices[index].lastReadingMs;
        portEXIT_CRITICAL(&stateMux);
        if (sample) failures=0; else if (failures<4) ++failures;
      } else xSemaphoreGive(radioMutex);
    }
    const uint32_t backoff = failures==0 ? 4500 :
       (failures==1 ? 10000 : (failures==2 ? 20000 : 40000));
    vTaskDelay(pdMS_TO_TICKS(backoff+index*300));
  }
}

} // anonymous namespace

bool acquireRadio(uint32_t waitMs) {
  // Called only by authenticated, user-initiated scans, never by BLE worker.
  // Asks a persistent session to release the radio before taking its mutex.
  if (!radioMutex) return false;
  maintenanceRequested = true;
  if (xSemaphoreTake(radioMutex, pdMS_TO_TICKS(waitMs)) == pdTRUE) return true;
  maintenanceRequested = false;
  return false;
}
void releaseRadio() {
  if (radioMutex) xSemaphoreGive(radioMutex);
  maintenanceRequested = false;
}

void setConfigured(JsonArrayConst macs) {
  Device *updated = new (std::nothrow) Device[MAX_BMS]();
  if (!updated) {
    Serial.println("[JK] Memory allocation failed while reconfiguring BMS");
    return;
  }
  uint8_t count = 0;
  for (JsonVariantConst value : macs) {
    if (count >= MAX_BMS) break;
    String mac = value.as<String>();
    if (mac.length() != 17) continue;
    mac.toUpperCase();
    strncpy(updated[count].mac, mac.c_str(), sizeof(updated[count].mac) - 1);
    strncpy(updated[count].state, "waiting_first_poll",
            sizeof(updated[count].state) - 1);
    count++;
  }
  portENTER_CRITICAL(&stateMux);
  // Retain a valid reading for unchanged MACs, but never for a different BMS.
  for (uint8_t i = 0; i < count; ++i) {
    for (uint8_t j = 0; j < configured; ++j) {
      if (strcmp(updated[i].mac, devices[j].mac) == 0) {
        updated[i] = devices[j];
        break;
      }
    }
  }
  memcpy(devices, updated, sizeof devices);
  configured = count;
  portEXIT_CRITICAL(&stateMux);
  delete[] updated;
}
void loadConfigured(const String &saved) {
  JsonDocument config;
  if (deserializeJson(config, saved)) return;
  JsonArrayConst addresses = config.as<JsonArrayConst>();
  if (!addresses.isNull()) setConfigured(addresses);
}

void fillTelemetry(JsonDocument &out) {
  uint8_t count = 0;
  portENTER_CRITICAL(&stateMux);
  count = configured;
  portEXIT_CRITICAL(&stateMux);
  out["available"] = false;
  out["source"] = "jk02-ffe1-read-only";
  out["decoder"] = "experimental_validate_with_manufacturer";
  out["temperature_safety_verified"] = false;
  out["configured_count"] = count;
  out["cache_only"] = true;
  out["recommended_poll_ms"] = 2500;
  const uint32_t now = millis();
  JsonArray list = out["devices"].to<JsonArray>();
  for (uint8_t i = 0; i < count; ++i) {
    Device item{};
    portENTER_CRITICAL(&stateMux);
    item = devices[i];
    portEXIT_CRITICAL(&stateMux);
    JsonObject device = list.add<JsonObject>();
    // item is a local copy. Force JsonDocument-owned strings before return.
    // ArduinoJson 7.4.2 may otherwise retain a pointer to a const char array.
    device["mac"] = String(item.mac);
    device["state"] = String(item.state);
    device["ble_connected"] = item.bleConnected;
    device["connections"] = item.connections;
    device["disconnections"] = item.disconnections;
    device["ble_scan_attempts"] = item.scanAttempts;
    device["ble_scan_misses"] = item.scanMisses;
    device["ble_connect_attempts"] = item.connectAttempts;
    device["ble_connect_failures"] = item.connectFailures;
    device["ble_client_failures"] = item.clientFailures;
    device["ble_service_failures"] = item.serviceFailures;
    device["ble_subscribe_failures"] = item.subscribeFailures;
    device["ble_last_error"] = item.lastBleError;
    device["ble_gap_busy_count"] = item.gapInitiatorBusy;
    device["ble_gap_cancel_count"] = item.gapInitiatorCancelled;
    device["ble_gap_still_busy_count"] = item.gapInitiatorStillBusy;
    device["bad_crc_count"] = item.badFrames;
    device["rejected_measurements"] = item.rejectedMeasurements;
    const bool fresh = item.reading.valid && item.lastReadingMs &&
        (uint32_t)(now - item.lastReadingMs) < FRESH_MS;
    device["available"] = fresh;
    if (!fresh) {
      if (item.reading.valid) device["state"] = "stale";
      continue;
    }
    out["available"] = true;
    device["age_seconds"] = (now - item.lastReadingMs) / 1000;
    const smartjk::Reading &m = item.reading;
    JsonObject values = device["values"].to<JsonObject>();
    values["frame_layout"] = m.layout == 24 ? "JK02_24S" : "JK02_32S";
    values["soc_pct"] = m.soc;
    values["voltage_v"] = m.voltageV;
    values["current_a"] = m.currentA;
    values["power_w"] = m.powerW;
    values["cell_count"] = m.cellCount;
    values["cell_min_v"] = m.cellMinV;
    values["cell_max_v"] = m.cellMaxV;
    if (m.cellMinNumber) values["cell_min_number"] = m.cellMinNumber;
    if (m.cellMaxNumber) values["cell_max_number"] = m.cellMaxNumber;
    values["cell_delta_mv"] = m.cellDeltaMv;
    values["nominal_capacity_ah"] = m.nominalCapacityAh;
    values["cycles"] = m.cycles;
    values["charging_mos"] = m.chargingMos;
    values["discharging_mos"] = m.dischargingMos;
    values["error_mask"] = m.errorMask;
    values["alarm_active"] = (m.errorMask & ~(uint32_t(1) << 4)) != 0;
    values["cell_avg_v"] = m.cellCount ?
        (m.voltageV / m.cellCount) : 0;
    values["charge_power_w"] = m.powerW > 0 ? m.powerW : 0;
    values["discharge_power_w"] = m.powerW < 0 ? -m.powerW : 0;
    values["settings_received"] = item.settings.confirmed;
    values["info_received"] = item.info.confirmed;
    const jkextra::Reading &e = item.extra;
    if (e.valid) {
      if (e.balanceCurrentAvailable) values["balance_current_a"] = e.balanceCurrentA;
      values["balance_action"] = e.balanceAction;
      values["wire_warning_mask"] = e.wireWarningMask;
      values["runtime_s"] = e.runtimeSeconds;
      if (e.capacityRemainingAvailable) values["capacity_remaining_ah"] = e.capacityRemainingAh;
      if (e.cycledCapacityAvailable) values["total_cycle_capacity_ah"] = e.cycledCapacityAh;
      if (e.sohAvailable) values["soh_pct"] = e.soh;
      values["precharging"] = e.precharging;
      values["heating"] = e.heating;
      if (m.layout == 32) {
        values["dry1_contact"] = e.dry1;
        values["dry2_contact"] = e.dry2;
        if (e.chargeStatusAvailable) values["charge_status"] = e.chargeStatus;
        if (e.chargeStatusTimeAvailable)
          values["charge_status_time_s"] = e.chargeStatusTimeS;
        values["emergency_countdown_s"] = e.emergencyCountdownS;
        for (int t = 0; t < 3; ++t) {
          if (!e.temperatureAvailable[t]) continue;
          if (t == 0) values["temp3_c"] = e.temperatureC[t];
          if (t == 1) values["temp4_c"] = e.temperatureC[t];
          if (t == 2) values["temp5_c"] = e.temperatureC[t];
        }
      }
    }
    if (m.tempAvailable[0]) values["temp1_c"] = m.tempC[0];
    if (m.tempAvailable[1]) values["temp2_c"] = m.tempC[1];
    if (m.tempAvailable[2]) values["mos_temp_c"] = m.tempC[2];
    JsonArray cells = values["cells"].to<JsonArray>();
    for (uint8_t cell = 0; cell < 32; ++cell) {
      if (!m.cellActive[cell]) continue;
      JsonObject v = cells.add<JsonObject>();
      v["number"] = cell + 1;
      v["voltage_v"] = m.cellVoltage[cell];
      if (e.valid && e.resistanceAvailable[cell])
        v["resistance_ohm"] = e.resistanceOhm[cell];
      if (e.valid && (e.wireWarningMask & (uint32_t(1) << cell)))
        v["wire_warning"] = true;
    }
  }
}
void fillDetails(JsonDocument &out) {
  uint8_t count = 0;
  portENTER_CRITICAL(&stateMux);
  count = configured;
  portEXIT_CRITICAL(&stateMux);
  out["read_only"] = true;
  JsonArray list = out["devices"].to<JsonArray>();
  const uint32_t now = millis();
  for (uint8_t i = 0; i < count; ++i) {
    Device item{};
    portENTER_CRITICAL(&stateMux);
    item = devices[i];
    portEXIT_CRITICAL(&stateMux);
    JsonObject d = list.add<JsonObject>();
    d["mac"] = String(item.mac); // Owned by the JSON document
    JsonObject settings = d["settings"].to<JsonObject>();
    const bool available = item.settings.confirmed && item.lastSettingsMs &&
      (uint32_t)(now - item.lastSettingsMs) < 1800000;
    settings["available"] = available;
    if (available) {
      settings["age_seconds"] = (now - item.lastSettingsMs) / 1000;
      JsonArray items = settings["items"].to<JsonArray>();
      for (size_t p = 0; p < jkextra::PARAM_COUNT; ++p) {
        if (!item.settings.available[p]) continue;
        const jkextra::Param &def = jkextra::PARAMS[p];
        JsonObject value = items.add<JsonObject>();
        value["key"] = def.key;
        value["editable"] = jkwrite::editable(p);
        value["label"] = def.label;
        value["group"] = def.group;
        if (def.unit[0]) value["unit"] = def.unit;
        if (def.kind == jkextra::BOOL1 || def.kind == jkextra::FLAG16) {
          value["type"] = "bool";
          value["value"] = item.settings.value[p] > 0.5f;
        } else {
          value["type"] = "number";
          value["value"] = item.settings.value[p];
        }
      }
    } else {
      settings["reason"] = "no_fresh_crc_checked_settings_frame";
    }
    JsonObject info = d["info"].to<JsonObject>();
    const bool cachedIdentityPresent = item.info.confirmed && item.lastInfoMs;
    const bool identityIntegrityOk = jkextra::usableIdentity(item.info);
    const bool infoAvailable = cachedIdentityPresent && identityIntegrityOk &&
      (uint32_t)(now - item.lastInfoMs) < 3600000;
    info["available"] = infoAvailable;
    info["decoder_revision"] = "jk02-info-json-owned-4";
    info["identity_integrity_ok"] = identityIntegrityOk;
    info["type3_frames_seen"] = item.seenType3Frames;
    info["unrecognized_frames"] = item.rejectedIdentityFrames;
    if (item.lastIdentityFailure[0])
      info["last_rejection"] = String(item.lastIdentityFailure);
    if (infoAvailable) {
      info["age_seconds"] = (now - item.lastInfoMs) / 1000;
      // All fields below originate from type-3 BLE data. Copy each value
      // into the JSON allocator: item is destroyed before respond() serializes.
      const jkextra::Info &v = item.info;
      if (v.vendor[0]) info["model"] = String(v.vendor);
      if (v.deviceName[0]) info["name"] = String(v.deviceName);
      if (v.hardware[0]) info["hardware_version"] = String(v.hardware);
      if (v.software[0]) info["software_version"] = String(v.software);
      if (v.manufactureDate[0]) info["manufacturing_date"] = String(v.manufactureDate);
      // Never include passcodes or user data; serial is local read-only.
      if (v.serialNumber[0]) info["serial_number"] = String(v.serialNumber);
      info["power_on_count"] = v.powerOnCount;
      info["uptime_hours"] = v.uptimeS / 3600.0f;
    } else {
      info["reason"] = cachedIdentityPresent && !identityIntegrityOk ?
          "identity_record_integrity_failed" :
          (item.rejectedIdentityFrames > 0 ?
           "unrecognized_type3_identity_layout" :
           "no_fresh_crc_checked_device_info_frame");
    }
  }
}

bool isConfigured(const char *mac) {
  if (!mac || !mac[0]) return false;
  bool found=false;
  portENTER_CRITICAL(&stateMux);
  for(uint8_t i=0;i<configured;++i)
    if(!strcmp(devices[i].mac,mac)){found=true;break;}
  portEXIT_CRITICAL(&stateMux);
  return found;
}

bool requestWrite(const char *mac, const char *key, float value,
                  bool boolType, float expectedOld, String &error) {
  if(!mac || !key){error="missing_target";return false;}
  const int idx=jkwrite::find(key);
  uint8_t packet[20]={};
  if(idx<0 || !jkwrite::build(size_t(idx),value,boolType,packet)) {
    error="unsupported_or_invalid_parameter";return false;
  }
  const uint32_t now=millis();
  bool accepted=false;
  const char *reason="bms_not_configured";
  portENTER_CRITICAL(&stateMux);
  for(uint8_t i=0;i<configured;++i){
    Device &d=devices[i];
    if(strcmp(d.mac,mac))continue;
    reason="write_refused";
    if(!d.bleConnected || !d.reading.valid || !d.lastReadingMs ||
       (uint32_t)(now-d.lastReadingMs)>=20000){
      reason="bms_not_live";break;
    }
    if(d.reading.errorMask!=0){reason="bms_alarm_active";break;}
    if(!d.info.confirmed || !jkextra::usableIdentity(d.info)){
      reason="unverified_bms_identity";break;
    }
    if(!d.settings.confirmed || !d.lastSettingsMs ||
       (uint32_t)(now-d.lastSettingsMs)>=60000 ||
       !d.settings.available[size_t(idx)]){
      reason="stale_or_missing_settings";break;
    }
    if(fabsf(d.settings.value[size_t(idx)]-expectedOld)>0.0011f){
      reason="stale_expected_value";break;
    }
    if(d.writePhase==1||d.writePhase==2){
      reason="write_already_pending";break;
    }
    if(d.writeAt && (uint32_t)(now-d.writeAt)<15000){
      reason="write_rate_limit_15s";break;
    }
    d.writeIndex=int8_t(idx);
    d.writeTarget=value;
    d.writeAt=now;
    d.writeSentAt=0;
    d.writeMismatchSeen=false;
    d.writePhase=1;
    accepted=true;reason="";
    break;
  }
  portEXIT_CRITICAL(&stateMux);
  error=reason; // String operations must happen outside FreeRTOS critical.
  return accepted;
}

void fillWriteStatus(JsonDocument &out, const char *mac) {
  out["mac"] = mac ? String(mac) : String("");
  out["verified_by_bms"] = false;
  uint8_t phase=0;
  int idx=-1;
  bool found=false;
  portENTER_CRITICAL(&stateMux);
  for(uint8_t i=0;i<configured;++i){
    Device &d=devices[i];
    if(!mac || strcmp(d.mac,mac))continue;
    phase=d.writePhase;
    idx=d.writeIndex;
    found=true;
    break;
  }
  portEXIT_CRITICAL(&stateMux);
  if(!found){out["state"]="bms_not_configured";return;}
  const char *name = phase==0?"none":phase==1?"queued":
     phase==2?"sent_unconfirmed":phase==3?"confirmed":
     phase==4?"mismatch":phase==5?"send_failed":
     phase==6?"timeout_unverified":"link_lost_unverified";
  out["state"]=name;
  out["verified_by_bms"]=phase==3;
  if(idx>=0 && idx<int(jkextra::PARAM_COUNT))
    out["key"]=jkextra::PARAMS[size_t(idx)].key;
}

void begin() {
  radioMutex = xSemaphoreCreateMutex();
  if (!radioMutex) {
    Serial.println("[JK] Failed to allocate BLE radio mutex");
    return;
  }
  for (uint8_t i=0;i<MAX_BMS;++i) {
    sessions[i].index = i;
    char name[20] = {};
    snprintf(name,sizeof(name),"jk-ble-%u",unsigned(i+1));
    if (xTaskCreate(worker,name,9216,&sessions[i],1,nullptr)!=pdPASS)
      Serial.printf("[JK] BLE worker start failed: %u\n",unsigned(i+1));
  }
  Serial.println("[JK] Multi-BMS persistent BLE reader started");
}
} // namespace jkreader
