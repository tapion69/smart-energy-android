#pragma once
/* Boot-only BLE recovery for JK. BMS scan/links are deliberately NOT started.
 * ESP32 / NimBLE-Arduino 2.5.x server; same GATT+AES-GCM as IDF readers.
 * Existing JK associations, pairing code and settings remain unchanged.
 */
#include <Arduino.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <NimBLEDevice.h>
#include <mbedtls/md.h>
#include <mbedtls/gcm.h>
#include <esp_system.h>
#include <esp_attr.h>
#include <string>

namespace se_ble_wifi_jk {
static constexpr const char *SERVICE="b5e70001-3a62-4e58-9d12-4e4f44450001";
static constexpr const char *CHALLENGE="b5e70002-3a62-4e58-9d12-4e4f44450001";
static constexpr const char *CONFIG="b5e70003-3a62-4e58-9d12-4e4f44450001";
static constexpr const char *STATUS="b5e70004-3a62-4e58-9d12-4e4f44450001";
static constexpr const char *DOMAIN="smart-energy-wifi-ble-v1";
static constexpr uint32_t NEXT_BOOT=0x5345424aU;
RTC_NOINIT_ATTR static uint32_t nextBootMarker;
static uint8_t challenge[16];
static String code;
static Preferences *prefsStore=nullptr;
static volatile bool accepted=false;
static NimBLECharacteristic *stageStatus=nullptr;

class WriteCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *characteristic, NimBLEConnInfo& info) override {
        (void)info;
        const std::string frame=characteristic->getValue();
        if(accepted || frame.size()<29 || frame.size()>240 || !prefsStore)return;
        const uint8_t *bytes=(const uint8_t*)frame.data();
        const size_t length=frame.size()-28;
        if(length>212 || code.length()<8 || code.length()>32)return;
        uint8_t mac_input[64]={0},key[32]={0};
        const size_t domain_len=strlen(DOMAIN);
        memcpy(mac_input,DOMAIN,domain_len);
        memcpy(mac_input+domain_len,challenge,16);
        const mbedtls_md_info_t *md=mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
        if(!md || mbedtls_md_hmac(md,
           (const uint8_t*)code.c_str(),code.length(),
           mac_input,domain_len+16,key)!=0)return;
        mbedtls_gcm_context gcm;
        mbedtls_gcm_init(&gcm);
        uint8_t plain[213]={0};
        int rc=mbedtls_gcm_setkey(&gcm,MBEDTLS_CIPHER_ID_AES,key,256);
        if(rc==0)rc=mbedtls_gcm_auth_decrypt(&gcm,length,bytes,12,
            challenge,16,bytes+12,16,bytes+28,plain);
        mbedtls_gcm_free(&gcm);
        memset(key,0,sizeof(key));
        if(rc!=0){memset(plain,0,sizeof(plain));return;}
        plain[length]=0;
        JsonDocument doc;
        if(deserializeJson(doc,(const char*)plain)==DeserializationError::Ok) {
            String ssid=doc["ssid"].as<String>();
            String pass=doc["password"].as<String>();
            if(ssid.length()>=1 && ssid.length()<=32 &&
               pass.length()>=8 && pass.length()<=63 &&
               prefsStore->putString("bleSsid",ssid)==ssid.length() &&
               prefsStore->putString("blePass",pass)==pass.length()) {
                accepted=true;
                if(stageStatus)stageStatus->setValue("OK");
            }
        }
        memset(plain,0,sizeof(plain));
    }
};
static WriteCallbacks callbacks;

static void run(Preferences& prefs, const String& secret, const String& identity) {
    prefsStore=&prefs;
    code=secret;
    accepted=false;
    esp_fill_random(challenge,sizeof(challenge));
    // No BMS client is active. Start BLE server only during this maintenance.
    NimBLEDevice::init("SE-WIFI-JK");
    NimBLEServer *server=NimBLEDevice::createServer();
    NimBLEService *service=server->createService(SERVICE);
    NimBLECharacteristic *nonce=service->createCharacteristic(
        CHALLENGE,NIMBLE_PROPERTY::READ,16);
    nonce->setValue(challenge,sizeof(challenge));
    NimBLECharacteristic *change=service->createCharacteristic(
        CONFIG,NIMBLE_PROPERTY::WRITE,240);
    change->setCallbacks(&callbacks);
    stageStatus=service->createCharacteristic(STATUS,NIMBLE_PROPERTY::READ,8);
    stageStatus->setValue("WAIT");
    service->start();
    NimBLEAdvertising *adv=NimBLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE);
    String label="SE-WIFI-"+identity.substring(0,16);
    adv->setName(label.c_str());
    adv->enableScanResponse(true);
    NimBLEDevice::startAdvertising();
    Serial.println("[BLE recovery] Boot-only session active (120s).");
    const uint32_t started=millis();
    while((uint32_t)(millis()-started)<120000) {
        if(accepted) {
            Serial.println("[BLE recovery] Encrypted Wi-Fi candidate saved.");
            delay(1800);
            ESP.restart();
        }
        delay(100);
    }
    Serial.println("[BLE recovery] Expired; original Wi-Fi retained.");
    ESP.restart();
}
} // namespace se_ble_wifi_jk
