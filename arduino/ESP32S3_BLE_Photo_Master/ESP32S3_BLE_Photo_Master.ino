#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEClient.h>
#include "photo_data.h"

// Same service and characteristic as your successful Espx test.
#define SERVICE_UUID "4fa2c730-13a1-11eb-adc1-0242ac120002"
#define CHAR_UUID "4fa2c974-13a1-11eb-adc1-0242ac120002"

BLEScan* scanner;
BLEClient* client = nullptr;
BLERemoteCharacteristic* receiver = nullptr;
BLEAdvertisedDevice* found = nullptr;

class ScanCallback : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice device) override {
    if (device.haveServiceUUID() &&
        device.getServiceUUID().equals(BLEUUID(SERVICE_UUID))) {
      if (!found) found = new BLEAdvertisedDevice(device);
      BLEDevice::getScan()->stop();
    }
  }
};

bool connectPhone() {
  if (found) { delete found; found = nullptr; }
  Serial.println("Searching for BLE Photo Receiver...");
  scanner->clearResults();
  scanner->start(5, false);
  if (!found) return false;
  if (!client) client = BLEDevice::createClient();
  Serial.println("Connecting...");
  if (!client->connect(found)) return false;
  BLERemoteService* svc = client->getService(BLEUUID(SERVICE_UUID));
  if (!svc) { client->disconnect(); return false; }
  receiver = svc->getCharacteristic(BLEUUID(CHAR_UUID));
  if (!receiver || !receiver->canWrite()) {
    client->disconnect(); receiver = nullptr; return false;
  }
  Serial.println("Connected! Type 1 in Serial Monitor to send a JPEG.");
  return true;
}

// Protocol: exactly 4 bytes unsigned little-endian image size,
// then raw JPG split into chunks; Write With Response for every chunk.
void sendPhoto() {
  if (!client || !client->isConnected() || !receiver) return;
  uint32_t size = (uint32_t)photoSize;
  uint8_t header[4] = {
    (uint8_t)size, (uint8_t)(size >> 8),
    (uint8_t)(size >> 16), (uint8_t)(size >> 24)
  };
  receiver->writeValue(header, 4, true);
  Serial.printf("Sending JPG: %lu bytes\n", (unsigned long)size);
  for (size_t offset = 0; offset < photoSize; offset += 20) {
    if (!client->isConnected()) { Serial.println("Disconnected during transfer"); return; }
    size_t len = min((size_t)20, photoSize - offset);
    // ESP32-S3 flash arrays are memory mapped; direct pointer is readable.
    receiver->writeValue((uint8_t*)(photoBuffer + offset), len, true);
    delay(12);
  }
  Serial.println("All writes requested; check picture on phone.");
}

void setup() {
  Serial.begin(115200);
  delay(1200);
  BLEDevice::init("ESP32-S3-Master");
  scanner = BLEDevice::getScan();
  scanner->setAdvertisedDeviceCallbacks(new ScanCallback());
  scanner->setActiveScan(true);
  scanner->setInterval(100);
  scanner->setWindow(80);
  Serial.println("ESP32-S3 BLE Photo Master ready");
}

void loop() {
  if (!client || !client->isConnected()) {
    receiver = nullptr;
    if (!connectPhone()) delay(1500);
    return;
  }
  if (Serial.available()) {
    char c = Serial.read();
    if (c == '1') sendPhoto();
  }
  delay(20);
}
