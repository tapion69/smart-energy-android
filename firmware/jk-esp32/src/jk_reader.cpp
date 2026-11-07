// JK02 V19 read-only persistent BLE telemetry, standalone ESP32.
// Uses the same documented FFE0/FFE1 handshake and 300-byte frame profiles as
// the author's Smart JK-BMS Bluetooth Home Assistant project.
// This worker never sends BMS parameter writes, MOS commands, or SOC changes.
// Runtime measurements are provisional and MUST NOT control safety logic.
#include "jk_reader.h"
#include "jk_protocol.h"
#include "jk_extended.h"
#include <NimBLEDevice.h>
#include <WiFi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#include <string.h>
#include <new>

extern bool setupMode; // main.cpp manages Wi-Fi provisioning state.
namespace jkreader {
namespace {
static constexpr uint8_t MAX_BMS = 8;
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
  bool bleConnected = false;
  uint32_t connections = 0;
  uint32_t disconnections = 0;
};
static Device devices[MAX_BMS];
static uint8_t configured = 0;
static portMUX_TYPE stateMux = portMUX_INITIALIZER_UNLOCKED;
static SemaphoreHandle_t radioMutex = nullptr;
static smartjk::Assembler assembler;
static int activeIndex = -1;
static char activeMac[18] = {};
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

// Callback gets ONLY a frame with a verified 300-byte header and checksum.
// Never preserve raw frames, as type 3 includes JK device passcodes.
void onValidatedFrame(const uint8_t *frame, const smartjk::Reading *basic, void *) {
  const int index = activeIndex;
  if (index < 0) return;
  const uint8_t type = frame[4];
  jkextra::Reading extra{};
  jkextra::Settings settings{};
  jkextra::Info info{};
  if (type == 2 && basic) {
    if (!jkextra::decode(frame, *basic, extra)) return;
  } else if (type == 1) {
    if (!jkextra::decodeSettings(frame, settings)) return;
  } else if (type == 3) {
    if (!jkextra::decodeInfo(frame, info)) return;
  } else {
    return;
  }
  const uint32_t now = millis();
  portENTER_CRITICAL(&stateMux);
  if (index < configured &&
      strcmp(devices[index].mac, activeMac) == 0) {
    Device &item = devices[index];
    if (type == 2 && basic) {
      item.reading = *basic;
      item.extra = extra;
      item.lastReadingMs = now;
      strncpy(item.state, "valid_sample", sizeof(item.state) - 1);
    } else if (type == 1) {
      item.settings = settings;
      item.lastSettingsMs = now;
    } else if (type == 3) {
      item.info = info;
      item.lastInfoMs = now;
    }
  }
  portEXIT_CRITICAL(&stateMux);
}

void onNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t len, bool) {
  if (activeIndex < 0 || !data || !len) return;
  smartjk::Reading ignored{};
  assembler.append(data, len, ignored, onValidatedFrame);
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

void pollOne(int index, const char *mac) {
  updateState(index, mac, "searching_ble");
  NimBLEScan *scanner = NimBLEDevice::getScan();
  scanner->setActiveScan(true);
  scanner->setMaxResults(40);
  NimBLEScanResults results = scanner->getResults(4000, false);
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
    updateState(index, mac, "ble_not_seen");
    return;
  }
  updateState(index, mac, "connecting_ble");
  NimBLEClient *client = NimBLEDevice::createClient();
  if (!client) {
    scanner->clearResults();
    updateState(index, mac, "ble_client_unavailable");
    return;
  }
  client->setConnectTimeout(9000);
  const bool connected = client->connect(match);
  scanner->clearResults();
  if (!connected) {
    updateState(index, mac, "ble_connect_failed");
    NimBLEDevice::deleteClient(client);
    return;
  }
  NimBLERemoteService *service =
      client->getService(NimBLEUUID("0000ffe0-0000-1000-8000-00805f9b34fb"));
  NimBLERemoteCharacteristic *characteristic =
      service ? service->getCharacteristic(NimBLEUUID("0000ffe1-0000-1000-8000-00805f9b34fb")) : nullptr;
  if (!characteristic || !characteristic->canNotify() ||
      !(characteristic->canWriteNoResponse() || characteristic->canWrite())) {
    updateState(index, mac, "ffe1_unavailable");
    client->disconnect();
    NimBLEDevice::deleteClient(client);
    return;
  }
  activeIndex = index;
  strncpy(activeMac, mac, sizeof activeMac - 1);
  activeMac[sizeof activeMac - 1] = '\0';
  assembler.reset();
  updateState(index, mac, "waiting_frames");
  if (!characteristic->subscribe(true, onNotify)) {
    activeIndex = -1;
    updateState(index, mac, "ble_subscribe_failed");
    client->disconnect();
    NimBLEDevice::deleteClient(client);
    return;
  }

  portENTER_CRITICAL(&stateMux);
  if (index < configured && strcmp(devices[index].mac, mac) == 0) {
    devices[index].bleConnected = true;
    devices[index].connections++;
  }
  portEXIT_CRITICAL(&stateMux);
  Serial.printf("[JK] BLE connected to %s (persistent session)\n", mac);

  // Query metadata/settings ONCE to start unsolicited JK02 telemetry stream.
  // All commands are READ-ONLY; never send SOC / MOS / setting writes.
  const bool withResponse = !characteristic->canWriteNoResponse();
  sendReadCommand(characteristic, 0x97, withResponse);
  vTaskDelay(pdMS_TO_TICKS(650));
  if (client->isConnected()) sendReadCommand(characteristic, 0x96, withResponse);
  uint32_t lastRefreshAt = millis();
  uint32_t lastSettingsRequestAt = millis();
  uint32_t lastInfoRequestAt = millis();
  const uint32_t sessionStartedAt = millis();
  bool interruptedForMaintenance = false;
  bool rotation = false;

  while (client->isConnected() && !setupMode && WiFi.status() == WL_CONNECTED) {
    if (maintenanceRequested) {
      interruptedForMaintenance = true;
      break; // explicit user scan; resumption afterwards
    }
    bool configuredStill = false;
    uint32_t lastReadingAt = 0;
    uint8_t deviceCount = 0;
    portENTER_CRITICAL(&stateMux);
    deviceCount = configured;
    if (index < configured && strcmp(devices[index].mac, mac) == 0) {
      configuredStill = true;
      lastReadingAt = devices[index].lastReadingMs;
    }
    portEXIT_CRITICAL(&stateMux);
    if (!configuredStill) break;

    // ESP32 single-client prototype: with 2+ JK devices, rotate slowly rather
    // than continuously disconnecting, to limit audible BLE beeps.
    if (deviceCount > 1 && (uint32_t)(millis() - sessionStartedAt) >= 90000) {
      rotation = true;
      break;
    }
    const uint32_t now = millis();
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

  activeIndex = -1;
  if (client->isConnected()) {
    characteristic->unsubscribe();
    client->disconnect();
  }
  portENTER_CRITICAL(&stateMux);
  if (index < configured && strcmp(devices[index].mac, mac) == 0) {
    devices[index].bleConnected = false;
    devices[index].disconnections++;
    devices[index].badFrames += assembler.badCrc;
    devices[index].rejectedMeasurements += assembler.rejectedMeasurements;
  }
  portEXIT_CRITICAL(&stateMux);
  NimBLEDevice::deleteClient(client);
  Serial.printf("[JK] BLE session ended: %s, reason=%s\n", mac,
                interruptedForMaintenance ? "manual_scan" :
                rotation ? "multi_bms_rotation" : "link_or_config_lost");
  updateState(index, mac, interruptedForMaintenance ? "scan_requested" :
              rotation ? "rotating_multi_bms" : "ble_disconnected");
}

void worker(void *) {
  uint8_t next = 0;
  uint8_t consecutiveFailures = 0;
  while (true) {
    if (setupMode || WiFi.status() != WL_CONNECTED || maintenanceRequested) {
      vTaskDelay(pdMS_TO_TICKS(500));
      continue;
    }
    char mac[18] = {};
    int index = -1;
    uint8_t deviceCount = 0;
    portENTER_CRITICAL(&stateMux);
    deviceCount = configured;
    if (configured > 0) {
      next %= configured;
      index = next;
      strncpy(mac, devices[index].mac, sizeof(mac) - 1);
      next = (next + 1) % configured;
    }
    portEXIT_CRITICAL(&stateMux);
    if (index < 0 || !mac[0]) {
      vTaskDelay(pdMS_TO_TICKS(1500));
      continue;
    }
    // Maintenance requests own the radio exclusively; do not start a session.
    if (radioMutex && !maintenanceRequested &&
        xSemaphoreTake(radioMutex, pdMS_TO_TICKS(1500)) == pdTRUE) {
      if (!maintenanceRequested && !setupMode) {
        uint32_t successfulAtStart = 0;
        portENTER_CRITICAL(&stateMux);
        if (index < configured) successfulAtStart = devices[index].lastReadingMs;
        portEXIT_CRITICAL(&stateMux);
        pollOne(index, mac); // stays connected indefinitely for ONE JK
        bool producedSample = false;
        portENTER_CRITICAL(&stateMux);
        if (index < configured && strcmp(devices[index].mac, mac) == 0) {
          producedSample = devices[index].lastReadingMs != successfulAtStart;
        }
        portEXIT_CRITICAL(&stateMux);
        if (producedSample) consecutiveFailures = 0;
        else if (consecutiveFailures < 4) ++consecutiveFailures;
      }
      xSemaphoreGive(radioMutex);
    }
    // Reconnect only after an actual BLE loss. Slow down on repeated failures.
    const uint32_t backoff = deviceCount > 1 ? 3500 :
      (consecutiveFailures == 0 ? 4500 :
       (consecutiveFailures == 1 ? 10000 :
        (consecutiveFailures == 2 ? 20000 : 40000)));
    uint32_t slept = 0;
    while (slept < backoff && !maintenanceRequested) {
      vTaskDelay(pdMS_TO_TICKS(500));
      slept += 500;
    }
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
    device["mac"] = item.mac;
    device["state"] = item.state;
    device["ble_connected"] = item.bleConnected;
    device["connections"] = item.connections;
    device["disconnections"] = item.disconnections;
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
    d["mac"] = item.mac;
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
    const bool infoAvailable = item.info.confirmed && item.lastInfoMs &&
      (uint32_t)(now - item.lastInfoMs) < 3600000;
    info["available"] = infoAvailable;
    if (infoAvailable) {
      info["age_seconds"] = (now - item.lastInfoMs) / 1000;
      const jkextra::Info &v = item.info;
      if (v.vendor[0]) info["model"] = v.vendor;
      if (v.deviceName[0]) info["name"] = v.deviceName;
      if (v.hardware[0]) info["hardware_version"] = v.hardware;
      if (v.software[0]) info["software_version"] = v.software;
      if (v.manufactureDate[0]) info["manufacturing_date"] = v.manufactureDate;
      // Never include passcodes or user data; serial is local read-only.
      if (v.serialNumber[0]) info["serial_number"] = v.serialNumber;
      info["power_on_count"] = v.powerOnCount;
      info["uptime_hours"] = v.uptimeS / 3600.0f;
    } else {
      info["reason"] = "no_fresh_crc_checked_device_info_frame";
    }
  }
}

void begin() {
  radioMutex = xSemaphoreCreateMutex();
  if (!radioMutex) {
    Serial.println("[JK] Failed to allocate BLE radio mutex");
    return;
  }
  xTaskCreate(worker, "jk-ble-reader", 10240, nullptr, 1, nullptr);
  Serial.println("[JK] Read-only telemetry task started (experimental)");
}
} // namespace jkreader
