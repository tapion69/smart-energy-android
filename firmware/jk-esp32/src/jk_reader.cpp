// Read-only JK02 V19 BLE telemetry poller, standalone ESP32.
// Uses the same documented FFE0/FFE1 handshake and 300-byte frame profiles as
// the author's Smart JK-BMS Bluetooth Home Assistant project.
// This worker never sends BMS parameter writes, MOS commands, or SOC changes.
// Runtime measurements are provisional and MUST NOT control safety logic.
#include "jk_reader.h"
#include "jk_protocol.h"
#include <NimBLEDevice.h>
#include <WiFi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#include <string.h>

extern bool setupMode; // main.cpp manages Wi-Fi provisioning state.
namespace jkreader {
namespace {
static constexpr uint8_t MAX_BMS = 8;
static constexpr uint32_t FRESH_MS = 90000;
struct Device {
  char mac[18] = {};
  char state[30] = {};
  smartjk::Reading reading{};
  uint32_t lastReadingMs = 0;
  uint32_t lastAttemptMs = 0;
  uint32_t badFrames = 0;
  uint32_t rejectedMeasurements = 0;
};
static Device devices[MAX_BMS];
static uint8_t configured = 0;
static portMUX_TYPE stateMux = portMUX_INITIALIZER_UNLOCKED;
static SemaphoreHandle_t radioMutex = nullptr;
static smartjk::Assembler assembler;
static int activeIndex = -1;
static char activeMac[18] = {};

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

void onNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t len, bool) {
  if (activeIndex < 0 || !data || !len) return;
  smartjk::Reading decoded{};
  if (!assembler.append(data, len, decoded)) return;
  const uint32_t now = millis();
  portENTER_CRITICAL(&stateMux);
  if (activeIndex < configured &&
      strcmp(devices[activeIndex].mac, activeMac) == 0) {
    Device &item = devices[activeIndex];
    item.reading = decoded;
    item.lastReadingMs = now;
    strncpy(item.state, "valid_sample", sizeof(item.state) - 1);
  }
  portEXIT_CRITICAL(&stateMux);
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
  client->setConnectTimeout(7000);
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
  activeMac[sizeof(activeMac) - 1] = '\0';
  assembler.reset();
  updateState(index, mac, "waiting_frames");
  if (!characteristic->subscribe(true, onNotify)) {
    activeIndex = -1;
    updateState(index, mac, "ble_subscribe_failed");
    client->disconnect();
    NimBLEDevice::deleteClient(client);
    return;
  }

  const uint32_t initialReading = millis();
  const bool withResponse = !characteristic->canWriteNoResponse();
  sendReadCommand(characteristic, 0x97, withResponse);
  vTaskDelay(pdMS_TO_TICKS(650));
  if (client->isConnected()) sendReadCommand(characteristic, 0x96, withResponse);
  for (unsigned attempt = 0; attempt < 45 && client->isConnected(); ++attempt) {
    vTaskDelay(pdMS_TO_TICKS(200));
    bool fresh = false;
    portENTER_CRITICAL(&stateMux);
    if (index < configured && strcmp(devices[index].mac, mac) == 0) {
      fresh = devices[index].lastReadingMs >= initialReading &&
              devices[index].reading.valid;
    }
    portEXIT_CRITICAL(&stateMux);
    if (fresh) break;
  }
  portENTER_CRITICAL(&stateMux);
  if (index < configured && strcmp(devices[index].mac, mac) == 0) {
    devices[index].badFrames += assembler.badCrc;
    devices[index].rejectedMeasurements += assembler.rejectedMeasurements;
  }
  portEXIT_CRITICAL(&stateMux);
  activeIndex = -1;
  if (client->isConnected()) {
    characteristic->unsubscribe();
    client->disconnect();
  }
  NimBLEDevice::deleteClient(client);
  bool fresh = false;
  portENTER_CRITICAL(&stateMux);
  if (index < configured && strcmp(devices[index].mac, mac) == 0) {
    fresh = devices[index].lastReadingMs >= initialReading &&
            devices[index].reading.valid;
  }
  portEXIT_CRITICAL(&stateMux);
  if (!fresh) {
    updateState(index, mac, assembler.validFrames > 0 ?
                "unknown_jk_layout" : "no_valid_jk_frame");
  } else {
    updateState(index, mac, "valid_sample");
  }
}

void worker(void *) {
  uint8_t next = 0;
  while (true) {
    // Do not use the radio while device pairing is active.
    if (setupMode || WiFi.status() != WL_CONNECTED) {
      vTaskDelay(pdMS_TO_TICKS(2200));
      continue;
    }
    char mac[18] = {};
    int index = -1;
    portENTER_CRITICAL(&stateMux);
    if (configured > 0) {
      next %= configured;
      index = next;
      strncpy(mac, devices[index].mac, sizeof(mac) - 1);
      next = (next + 1) % configured;
    }
    portEXIT_CRITICAL(&stateMux);
    if (index < 0 || !mac[0]) {
      vTaskDelay(pdMS_TO_TICKS(2200));
      continue;
    }
    if (acquireRadio(1000)) {
      pollOne(index, mac);
      releaseRadio();
    }
    // Serial radio access; no concurrent sessions yet. Avoid rapid reconnects.
    vTaskDelay(pdMS_TO_TICKS(4500));
  }
}
} // anonymous namespace

bool acquireRadio(uint32_t waitMs) {
  return radioMutex &&
         xSemaphoreTake(radioMutex, pdMS_TO_TICKS(waitMs)) == pdTRUE;
}
void releaseRadio() {
  if (radioMutex) xSemaphoreGive(radioMutex);
}

void setConfigured(JsonArrayConst macs) {
  Device updated[MAX_BMS] = {};
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
}
void loadConfigured(const String &saved) {
  JsonDocument config;
  if (deserializeJson(config, saved)) return;
  JsonArrayConst addresses = config.as<JsonArrayConst>();
  if (!addresses.isNull()) setConfigured(addresses);
}

void fillTelemetry(JsonDocument &out) {
  Device snapshot[MAX_BMS];
  uint8_t count = 0;
  portENTER_CRITICAL(&stateMux);
  count = configured;
  memcpy(snapshot, devices, sizeof devices);
  portEXIT_CRITICAL(&stateMux);
  out["available"] = false;
  out["source"] = "jk02-ffe1-read-only";
  out["decoder"] = "experimental_validate_with_manufacturer";
  out["temperature_safety_verified"] = false;
  out["configured_count"] = count;
  const uint32_t now = millis();
  JsonArray list = out["devices"].to<JsonArray>();
  for (uint8_t i = 0; i < count; ++i) {
    const Device &item = snapshot[i];
    JsonObject device = list.add<JsonObject>();
    device["mac"] = item.mac;
    device["state"] = item.state;
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
    if (m.tempAvailable[0]) values["temp1_c"] = m.tempC[0];
    if (m.tempAvailable[1]) values["temp2_c"] = m.tempC[1];
    if (m.tempAvailable[2]) values["mos_temp_c"] = m.tempC[2];
    JsonArray cells = values["cells"].to<JsonArray>();
    for (uint8_t cell = 0; cell < 32; ++cell) {
      if (!m.cellActive[cell]) continue;
      JsonObject v = cells.add<JsonObject>();
      v["number"] = cell + 1;
      v["voltage_v"] = m.cellVoltage[cell];
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
