// JK02 V19 read-only persistent BLE telemetry, standalone ESP32.
// Uses the same documented FFE0/FFE1 handshake and 300-byte frame profiles as
// the author's Smart JK-BMS Bluetooth Home Assistant project.
// This worker never sends BMS parameter writes, MOS commands, or SOC changes.
// Runtime measurements are provisional and MUST NOT control safety logic.
#include "jk_reader.h"
#include "jk_protocol.h"
#include "jk_extended.h"
#include "jk_write.h"
#include "ble_manager_policy.h"
#include "ble_link_policy.h"
#include <NimBLEDevice.h>
#include <WiFi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#include <string.h>
#include <new>

extern bool setupMode; // main.cpp manages Wi-Fi provisioning state.
extern volatile uint32_t wifiDropCount; // diagnostic since boot only
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
  uint32_t gapBusyCount = 0;
  uint32_t gapCancelCount = 0;
  uint32_t gapStillBusyCount = 0;
  uint32_t lastConnectDurationMs = 0;
  bool gapBeforeConnect = false;
  bool gapAfterConnect = false;
  bool scanBeforeConnect = false;
  uint32_t connections = 0;
  uint32_t disconnections = 0;
  uint32_t radioDisconnections = 0;
  uint32_t intentionalDisconnections = 0;
  int lastDisconnectReason = -1; // NimBLE GAP reason, captured by callback.
  uint32_t lastDisconnectAt = 0;
  uint32_t connectedAt = 0;
  int lastRssiDbm = 0;
  uint32_t lastRssiAt = 0;
  uint16_t connectionIntervalUnits = 0; // 1.25 ms
  uint16_t supervisionTimeoutUnits = 0; // 10 ms
  // One-shot, non-optimistic parameter write. 0=idle, 1=queued, 2=sent,
  // 3=confirmed, 4=mismatch, 5=send_failed, 6=timeout, 7=link_lost.
  uint8_t writePhase = 0;
  int8_t writeIndex = -1;
  float writeTarget = 0;
  uint32_t writeAt = 0;
  uint32_t writeSentAt = 0;
  // A parameter write must only be confirmed from a fresh settings response
  // explicitly queried AFTER the command was sent.
  uint32_t writeVerifyRequestedAt = 0;
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
  uint32_t nextRetryAt = 0;
  uint32_t lastRefreshAt = 0;
  uint32_t lastSettingsRequestAt = 0;
  uint32_t lastInfoRequestAt = 0;
  uint8_t failures = 0;
  uint32_t lastSignalPollAt = 0;
};
static Session sessions[MAX_BMS];
static volatile bool maintenanceRequested = false;

// The HA add-on uses ONE scan for all missing BMSs and independent persistent
// GATT sessions. Mirror that structure on NimBLE: copy each discovered address
// (including its random/public address type) BEFORE freeing scan results.
// This cache is used only with radioMutex held by a worker.
struct SharedDiscoveryEntry {
  char mac[18] = {};
  NimBLEAddress address{};
  bool seen = false;
};
static SharedDiscoveryEntry discovery[MAX_BMS];
static uint8_t discoveryCount = 0;
static uint32_t discoveryAt = 0;
static constexpr uint32_t DISCOVERY_TTL_MS = 15000;
static constexpr uint32_t DISCOVERY_SCAN_MS = 6500;


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
      case BleEvent::GapBusy: ++item.gapBusyCount; break;
      case BleEvent::GapCancelled: ++item.gapCancelCount; break;
      case BleEvent::GapStillBusy: ++item.gapStillBusyCount; break;
    }
  }
  portEXIT_CRITICAL(&stateMux);
}

// Receive the real GAP disconnection reason from NimBLE's host, without
// performing any BLE/HTTP work or reconnecting inside the callback.
class BmsClientCallbacks : public NimBLEClientCallbacks {
 public:
  void onDisconnect(NimBLEClient *client, int reason) override {
    if(!client) return;
    portENTER_CRITICAL(&stateMux);
    for(Session &session : sessions) {
      if(session.client!=client || session.index>=configured) continue;
      Device &d=devices[session.index];
      if(strcmp(d.mac,session.mac)==0) {
        d.lastDisconnectReason=reason;
        d.lastDisconnectAt=millis();
      }
      break;
    }
    portEXIT_CRITICAL(&stateMux);
  }
};
static BmsClientCallbacks bmsClientCallbacks;

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
          item.writeVerifyRequestedAt &&
          (int32_t)(now - item.writeVerifyRequestedAt) >= 0 &&
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

// The HA add-on serializes scans and maintains all existing GATT connections.
 // On the ESP32 NimBLE controller, only ONE GAP initiation can be in flight.
 // EALREADY=2 means another initiation is pending, not that a BMS is defective.
 // This function is called only while the radio mutex is held. Never reset the
 // controller or terminate an already connected peer to recover a pending init.
bool awaitIdleInitiator(uint32_t timeoutMs) {
  const uint32_t start=millis();
  while (ble_gap_conn_active() &&
         uint32_t(millis()-start)<timeoutMs) {
    vTaskDelay(pdMS_TO_TICKS(50));
  }
  return ble_gap_conn_active()==0;
}
bool recoverGapBeforeConnect(int index,const char* mac) {
  if(!ble_gap_conn_active()) return true;
  markBleEvent(index,mac,BleEvent::GapBusy);
  if(awaitIdleInitiator(1800)) return true;
  // The scanner is not running and all *other initiations* are serialized by
  // radioMutex. ble_gap_conn_cancel() touches only a pending GAP initiation.
  const int rc=ble_gap_conn_cancel();
  if(rc==0 || rc==BLE_HS_EALREADY) markBleEvent(index,mac,BleEvent::GapCancelled);
  const bool ready=awaitIdleInitiator(2200);
  if(!ready) {
    markBleEvent(index,mac,BleEvent::GapStillBusy);
    updateState(index,mac,"ble_gap_busy_retry");
    Serial.printf("[JK] GAP initiation still busy on slot %d, cancel=%d\n",index+1,rc);
  } else vTaskDelay(pdMS_TO_TICKS(180));
  return ready;
}

// Shared discovery is called ONLY while radioMutex is held. Every configured
// MAC is located in the same scan, like the working HA add-on. A connected JK
// is never disconnected just to scan for a different JK.
bool discoverSharedAddress(const char *wanted, NimBLEAddress &target) {
  const uint32_t now = millis();
  bool known = false;
  for (uint8_t i = 0; i < discoveryCount; ++i)
    if (!strcmp(discovery[i].mac, wanted)) known = true;
  if (!discoveryAt || (uint32_t)(now-discoveryAt) >= DISCOVERY_TTL_MS || !known) {
    char macs[MAX_BMS][18] = {};
    uint8_t count = 0;
    portENTER_CRITICAL(&stateMux);
    count = configured;
    for (uint8_t i = 0; i < count; ++i)
      strncpy(macs[i], devices[i].mac, sizeof(macs[i])-1);
    portEXIT_CRITICAL(&stateMux);

    discoveryCount = count;
    for (uint8_t i = 0; i < count; ++i) {
      strncpy(discovery[i].mac, macs[i], sizeof(discovery[i].mac)-1);
      discovery[i].mac[sizeof(discovery[i].mac)-1] = 0;
      discovery[i].address = NimBLEAddress{};
      discovery[i].seen = false;
    }
    NimBLEScan *scanner = NimBLEDevice::getScan();
    scanner->setActiveScan(false);
    scanner->setInterval(120);
    scanner->setWindow(48);
    scanner->setMaxResults(40);
    NimBLEScanResults results = scanner->getResults(DISCOVERY_SCAN_MS, false);
    for (int n = 0; n < results.getCount(); ++n) {
      const NimBLEAdvertisedDevice *advert = results.getDevice(n);
      if (!advert) continue;
      String mac = String(advert->getAddress().toString().c_str());
      mac.toUpperCase();
      for (uint8_t i = 0; i < count; ++i) {
        if (mac == macs[i]) {
          discovery[i].address = advert->getAddress(); // includes address TYPE
          discovery[i].seen = true;
        }
      }
    }
    // No client receives a pointer to a scan-owned object; no dangling pointer
    // when another client scans or the results are cleared.
    scanner->clearResults();
    discoveryAt = millis();
    uint8_t seen = 0;
    for (uint8_t i = 0; i < count; ++i) if (discovery[i].seen) ++seen;
    Serial.printf("[JK] Shared BLE discovery: configured=%u, found=%u\n",
                  unsigned(count), unsigned(seen));
  }
  for (uint8_t i = 0; i < discoveryCount; ++i) {
    if (strcmp(discovery[i].mac, wanted)==0 && discovery[i].seen) {
      target = discovery[i].address;
      return true;
    }
  }
  return false;
}

void closeSession(Session &session,const char* nextState) {
  NimBLEClient *client=session.client;
  if(!client) return;
  const bool wasConnected=client->isConnected();
  // Stop notifications before freeing the characteristic. A deliberate
  // closing request is distinct from a radio / remote disconnection.
  session.subscribed=false;
  session.notify=nullptr;
  session.client=nullptr;
  if(wasConnected) client->disconnect();
  NimBLEDevice::deleteClient(client);
  const int index=session.index;
  portENTER_CRITICAL(&stateMux);
  if(index<configured && strcmp(devices[index].mac,session.mac)==0) {
    Device &d=devices[index];
    if(d.bleConnected) {
      ++d.disconnections;
      if(wasConnected) ++d.intentionalDisconnections;
      else ++d.radioDisconnections;
      d.connectedAt=0;
      d.lastDisconnectAt=millis();
    }
    d.bleConnected=false;
    if(d.writePhase==1 || d.writePhase==2) d.writePhase=7;
  }
  portEXIT_CRITICAL(&stateMux);
  if(nextState) updateState(index,session.mac,nextState);
}
bool openSession(Session &session, const char *mac) {
  const int index = session.index;
  strncpy(session.mac, mac, sizeof(session.mac) - 1);
  session.mac[sizeof(session.mac)-1] = 0;
  if(!recoverGapBeforeConnect(index,mac)) {
    
    return false;
  }
  updateState(index, mac, "searching_ble");
  markBleEvent(index, mac, BleEvent::Scan);
  NimBLEAddress target;
  if (!discoverSharedAddress(mac, target)) {
    markBleEvent(index, mac, BleEvent::NotSeen);
    updateState(index, mac, "ble_not_seen");
    
    return false;
  }
  // Discover once for every missing JK; before initiating a new connection
  // explicitly wait for (or recover) any prior unfinished GAP initiation.
  if(!recoverGapBeforeConnect(index,mac)) {
    
    return false;
  }
  updateState(index, mac, "connecting_ble");
  NimBLEClient *client = NimBLEDevice::createClient();
  if (!client) {
    markBleEvent(index, mac, BleEvent::NoClient);
    updateState(index, mac, "ble_client_unavailable");
    
    return false;
  }
  // Same deliberate 15-second timeout used by our reliable Bleak/BlueZ add-on.
  client->setConnectTimeout(15000);
  client->setClientCallbacks(&bmsClientCallbacks,false);
  // Single manager owns ALL BLE initiations; leave per-BMS retries to its
  // bounded scheduler. Avoid racing internal retries with new GAP attempts.
  client->setConnectRetries(0);
  // Moderate 60-80 ms event interval and 12 s supervision budget: with
  // three JKs plus Wi-Fi, a 6 s budget can be too tight under RF contention.
  // The JK may negotiate different values, exposed in diagnostics below.
  client->setConnectionParams(48,64,0,1200,48,32);
  markBleEvent(index,mac,BleEvent::Connect);
  const uint32_t t0=millis();
  const bool gapBefore=ble_gap_conn_active()!=0;
  const bool scanned=NimBLEDevice::getScan()->isScanning();
  if(scanned) { NimBLEDevice::getScan()->stop(); vTaskDelay(pdMS_TO_TICKS(120)); }
  const bool connected=client->connect(target);
  const uint32_t elapsed=millis()-t0;
  const bool gapAfter=ble_gap_conn_active()!=0;
  portENTER_CRITICAL(&stateMux);
  if(index<configured && strcmp(devices[index].mac,mac)==0) {
    devices[index].lastConnectDurationMs=elapsed;
    devices[index].gapBeforeConnect=gapBefore;
    devices[index].gapAfterConnect=gapAfter;
    devices[index].scanBeforeConnect=scanned;
  }
  portEXIT_CRITICAL(&stateMux);
  const int bleError=connected?0:client->getLastError();
  if (!connected) {
    markBleEvent(index, mac, BleEvent::ConnectFailed, bleError);
    Serial.printf("[JK] BLE client connect failed, slot=%d, code=%d\n", index + 1, bleError);
    updateState(index, mac, bleError == BLE_HS_EALREADY ? "ble_busy_retry_exhausted" : "ble_connect_failed");
    NimBLEDevice::deleteClient(client);
    // Do not let another JK worker retry while the host still owns a pending
    // initiation from this failed attempt.
    if(bleError==BLE_HS_EALREADY || ble_gap_conn_active())
      recoverGapBeforeConnect(index,mac);
    
    return false;
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
    
    return false;
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
    
    return false;
  }

  const int rssi=client->getRssi();
  const NimBLEConnInfo connInfo=client->getConnInfo();
  const uint32_t connectedAt=millis();
  portENTER_CRITICAL(&stateMux);
  if (index < configured && strcmp(devices[index].mac, mac) == 0) {
    Device &d=devices[index];
    d.bleConnected=true;
    ++d.connections;
    d.connectedAt=connectedAt;
    d.connectionIntervalUnits=connInfo.getConnInterval();
    d.supervisionTimeoutUnits=connInfo.getConnTimeout();
    if(rssi!=0) {d.lastRssiDbm=rssi;d.lastRssiAt=connectedAt;}
  }
  portEXIT_CRITICAL(&stateMux);
  session.lastSignalPollAt=connectedAt;
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
  
  session.lastRefreshAt=millis();
  session.lastSettingsRequestAt=millis();
  session.lastInfoRequestAt=millis();
  return true;


}

void serviceSession(Session &session) {
  if(!session.client || !session.client->isConnected() || !session.notify)return;
  const int index=session.index;
  const uint32_t now=millis();
  NimBLERemoteCharacteristic *characteristic=session.notify;
  const bool withResponse=!characteristic->canWriteNoResponse();
  // Local GAP RSSI is fast and does not request a GATT read from the BMS.
  // Sample sparsely to avoid adding traffic to 3 always-on sessions.
  if(uint32_t(now-session.lastSignalPollAt)>=60000) {
    const int rssi=session.client->getRssi();
    if(rssi!=0) {
      portENTER_CRITICAL(&stateMux);
      if(index<configured && strcmp(devices[index].mac,session.mac)==0) {
        devices[index].lastRssiDbm=rssi;
        devices[index].lastRssiAt=millis();
      }
      portEXIT_CRITICAL(&stateMux);
    }
    session.lastSignalPollAt=now;
  }
  uint32_t lastReading=0;
  int pending=-1;
  float target=0;
  portENTER_CRITICAL(&stateMux);
  if(index<configured && strcmp(devices[index].mac,session.mac)==0) {
    Device &d=devices[index];
    lastReading=d.lastReadingMs;
    if(d.writePhase==1) {
      pending=d.writeIndex;target=d.writeTarget;
    } else if(d.writePhase==2 && d.writeSentAt &&
              (uint32_t)(now-d.writeSentAt)>15000) {
      d.writePhase=d.writeMismatchSeen?4:6;
    }
  }
  portEXIT_CRITICAL(&stateMux);
  if(pending>=0) {
    uint8_t packet[20]={};
    const bool valid=jkwrite::build(size_t(pending),target,
                     jkwrite::isBoolean(size_t(pending)),packet);
    const bool sent=valid && characteristic->writeValue(packet,sizeof(packet),withResponse);
    portENTER_CRITICAL(&stateMux);
    if(index<configured && strcmp(devices[index].mac,session.mac)==0 &&
       devices[index].writePhase==1) {
      devices[index].writePhase=sent?2:5;
      devices[index].writeSentAt=sent?millis():0;
    }
    portEXIT_CRITICAL(&stateMux);
    if(sent) {
      // Do not treat an old/spontaneous settings frame as write confirmation.
      // Allow the BMS time to commit, then deliberately read register 0x96.
      vTaskDelay(pdMS_TO_TICKS(850));
      if(session.client && session.client->isConnected()) {
        portENTER_CRITICAL(&stateMux);
        if(index<configured && strcmp(devices[index].mac,session.mac)==0 &&
           devices[index].writePhase==2)
          devices[index].writeVerifyRequestedAt=millis();
        portEXIT_CRITICAL(&stateMux);
        sendReadCommand(characteristic,0x96,withResponse);
      }
    }
  }
  // Write guards require settings younger than 60 s. The old 5-minute
  // polling interval could show "editable" options that were always rejected.
  if(uint32_t(now-session.lastSettingsRequestAt)>=45000) {
    sendReadCommand(characteristic,0x96,withResponse);
    session.lastSettingsRequestAt=now;
  }
  if(uint32_t(now-session.lastInfoRequestAt)>=900000) {
    sendReadCommand(characteristic,0x97,withResponse);
    session.lastInfoRequestAt=now;
  }
  if((!lastReading || uint32_t(now-lastReading)>=8000) &&
     uint32_t(now-session.lastRefreshAt)>=8000) {
    sendReadCommand(characteristic,0x96,withResponse);
    session.lastRefreshAt=now;
    if(!lastReading || uint32_t(now-lastReading)>30000)
      updateState(index,session.mac,"waiting_frames");
  }
}

void manager(void*) {
  for(uint8_t i=0;i<MAX_BMS;++i)sessions[i].index=i;
  Serial.println("[JK] Single-owner BLE manager active, independent links");
  while(true) {
    // A brief Wi-Fi dropout must not tear down three healthy BLE links.
    // Pause only NEW BLE connection attempts until the Wi-Fi STA recovers.
    const bool keepLinks=blelink::keepExistingLink(setupMode);
    const bool mayConnect=blelink::mayStartNewConnection(
        setupMode,WiFi.status()==WL_CONNECTED);
    for(uint8_t i=0;i<MAX_BMS;++i) {
      Session &session=sessions[i];
      char selected[18]={};
      portENTER_CRITICAL(&stateMux);
      if(i<configured)strncpy(selected,devices[i].mac,sizeof(selected)-1);
      portEXIT_CRITICAL(&stateMux);
      if(strcmp(session.mac,selected)!=0) {
        closeSession(session,nullptr);
        strncpy(session.mac,selected,sizeof(session.mac)-1);
        session.mac[sizeof(session.mac)-1]=0;
        session.failures=0;
        session.nextRetryAt=millis()+uint32_t(i)*300;
      }
      if(!keepLinks) closeSession(session,"ble_waiting_setup");
      else if(session.client && !session.client->isConnected()) {
        closeSession(session,"ble_disconnected");
        if(session.failures<5)++session.failures;
        session.nextRetryAt=millis()+4000;
      } else if(session.client) serviceSession(session);
    }
    if(mayConnect && !maintenanceRequested && radioMutex &&
       xSemaphoreTake(radioMutex,pdMS_TO_TICKS(100))==pdTRUE) {
      if(!setupMode && !maintenanceRequested) {
        for(uint8_t i=0;i<MAX_BMS;++i) {
          Session &session=sessions[i];
          if(!blemanager::eligible(true,maintenanceRequested,
              session.mac[0]!=0,session.client!=nullptr,millis(),
              session.nextRetryAt))continue;
          // One new link at a time. Other subscribed JKs keep streaming.
          const bool ok=openSession(session,session.mac);
          if(ok) {
            session.failures=0;session.nextRetryAt=0;
          } else {
            if(session.failures<5)++session.failures;
            session.nextRetryAt=millis()+
              blemanager::retryDelayMs(session.failures)+uint32_t(i)*300;
          }
          break;
        }
      }
      xSemaphoreGive(radioMutex);
    }
    vTaskDelay(pdMS_TO_TICKS(250));
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
  out["wifi_connected"] = WiFi.status()==WL_CONNECTED;
  out["wifi_disconnect_count"] = wifiDropCount;
  out["wifi_rssi_dbm"] = WiFi.status()==WL_CONNECTED ? WiFi.RSSI() : 0;
  out["free_heap_bytes"] = ESP.getFreeHeap();
  out["min_free_heap_bytes"] = ESP.getMinFreeHeap();
  out["uptime_seconds"] = millis()/1000;
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
    device["ble_radio_disconnections"] = item.radioDisconnections;
    device["ble_intentional_disconnections"] = item.intentionalDisconnections;
    device["ble_last_disconnect_reason"] = item.lastDisconnectReason;
    if(item.lastDisconnectAt)
      device["ble_last_disconnect_age_seconds"] = (now-item.lastDisconnectAt)/1000;
    if(item.connectedAt)
      device["ble_connected_seconds"] = (now-item.connectedAt)/1000;
    if(item.lastRssiAt)
      device["ble_rssi_dbm"] = item.lastRssiDbm;
    if(item.connectionIntervalUnits)
      device["ble_interval_ms"] = item.connectionIntervalUnits*1.25f;
    if(item.supervisionTimeoutUnits)
      device["ble_timeout_ms"] = item.supervisionTimeoutUnits*10u;
    device["ble_scan_attempts"] = item.scanAttempts;
    device["ble_scan_misses"] = item.scanMisses;
    device["ble_connect_attempts"] = item.connectAttempts;
    device["ble_connect_failures"] = item.connectFailures;
    device["ble_client_failures"] = item.clientFailures;
    device["ble_service_failures"] = item.serviceFailures;
    device["ble_subscribe_failures"] = item.subscribeFailures;
    device["ble_last_error"] = item.lastBleError;
    device["ble_gap_busy_count"] = item.gapBusyCount;
    device["ble_gap_cancel_count"] = item.gapCancelCount;
    device["ble_gap_still_busy_count"] = item.gapStillBusyCount;
    device["ble_connect_duration_ms"] = item.lastConnectDurationMs;
    device["ble_gap_active_before"] = item.gapBeforeConnect;
    device["ble_gap_active_after"] = item.gapAfterConnect;
    device["ble_scan_active_before"] = item.scanBeforeConnect;
    device["bad_crc_count"] = item.badFrames;
    device["rejected_measurements"] = item.rejectedMeasurements;
    const bool fresh = item.bleConnected && item.reading.valid && item.lastReadingMs &&
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
// Pure fail-closed readiness evaluation, identical for the UI and request.
// Never relax BMS identity/protection checks to make an edit appear to work.
static const char *writeBlockReason(const Device &d, uint32_t now) {
  if(!d.bleConnected || !d.reading.valid || !d.lastReadingMs ||
     uint32_t(now-d.lastReadingMs)>=20000) return "bms_not_live";
  if(d.reading.errorMask!=0) return "bms_alarm_active";
  if(!d.info.confirmed || !jkextra::usableIdentity(d.info))
    return "unverified_bms_identity";
  if(!d.settings.confirmed || !d.lastSettingsMs ||
     uint32_t(now-d.lastSettingsMs)>=60000)
    return "stale_or_missing_settings";
  if(d.writePhase==1 || d.writePhase==2)
    return "write_already_pending";
  if(d.writeAt && uint32_t(now-d.writeAt)<15000)
    return "write_rate_limit_15s";
  return "";
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
    JsonObject capability=d["write_pilot"].to<JsonObject>();
    const char *blocked=writeBlockReason(item,now);
    capability["ready"]=blocked[0]==0;
    capability["reason"]=String(blocked);
    capability["settings_age_seconds"]=item.lastSettingsMs ?
      uint32_t(now-item.lastSettingsMs)/1000 : -1;
    capability["identity_verified"]=item.info.confirmed &&
       jkextra::usableIdentity(item.info);
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
        value["risk_level"] = jkwrite::riskLevel(p);
        value["risk_label"] = jkwrite::riskLevel(p)>=2 ? "protection_critique" :
          jkwrite::riskLevel(p)==1 ? "operationnel" : "courant";
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
    reason=writeBlockReason(d,now);
    if(reason[0]) break;
    if(!d.settings.available[size_t(idx)]){
      reason="parameter_not_readable";break;
    }
    if(fabsf(d.settings.value[size_t(idx)]-expectedOld)>0.0011f){
      reason="stale_expected_value";break;
    }
    if(fabsf(d.settings.value[size_t(idx)]-value)<0.0011f){
      reason="already_at_target";break;
    }
    if(!jkwrite::consistentCandidate(size_t(idx),value,d.settings)){
      reason="safety_relationship_failed";break;
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
    d.writeVerifyRequestedAt=0;
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
  radioMutex=xSemaphoreCreateMutex();
  if(!radioMutex) { Serial.println("[JK] BLE mutex allocation failed");return; }
  if(xTaskCreate(manager,"jk-ble-manager",16384,nullptr,1,nullptr)!=pdPASS)
    Serial.println("[JK] BLE manager task allocation failed");
  else Serial.println("[JK] Multi-BMS reader: one GAP owner, persistent sessions");
}

} // namespace jkreader
