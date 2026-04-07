#include <Wire.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

const int MPU = 0x68;

int16_t AcX, AcY, AcZ;
float Ax, Ay, Az;
float totalAcceleration;

float threshold = 3.0;

BLECharacteristic *pCharacteristic;
bool deviceConnected = false;

class MyServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("Device Connected");
  };

  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("Device Disconnected");
    pServer->startAdvertising();
    Serial.println("Restart Advertising");
  }
};

void setup() {

  Serial.begin(115200);

  Wire.begin(5,6);

  Wire.beginTransmission(MPU);
  Wire.write(0x6B);
  Wire.write(0);
  Wire.endTransmission(true);

  BLEDevice::init("HelmetDetector");

  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService("1234");

  pCharacteristic = pService->createCharacteristic(
                     "5678",
                     BLECharacteristic::PROPERTY_NOTIFY
                   );

  pCharacteristic->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->start();

  Serial.println("BLE Ready");
}

void loop() {

  Wire.beginTransmission(MPU);
  Wire.write(0x3B);
  Wire.endTransmission(false);
  Wire.requestFrom(MPU,6,true);

  AcX = Wire.read()<<8 | Wire.read();
  AcY = Wire.read()<<8 | Wire.read();
  AcZ = Wire.read()<<8 | Wire.read();

  Ax = AcX / 16384.0;
  Ay = AcY / 16384.0;
  Az = AcZ / 16384.0;

  totalAcceleration = sqrt(Ax*Ax + Ay*Ay + Az*Az);

  Serial.println(totalAcceleration);
  if(deviceConnected){
  delay(500);
  pCharacteristic->setValue("TEST");
  pCharacteristic->notify();
  Serial.println("Sending TEST");
  delay(2000);
}

 if(totalAcceleration > threshold){

  Serial.println("ACCIDENT DETECTED");

  if(deviceConnected){
    for(int i = 0; i < 5; i++){   
      pCharacteristic->setValue("ACCIDENT");
      pCharacteristic->notify();
      delay(500);
    }
  }

  delay(5000);
}

  delay(200);
}