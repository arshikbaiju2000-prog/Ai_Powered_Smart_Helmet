/*
 * ════════════════════════════════════════════════════════════
 *  ble_antitheft.ino — BLE Anti-Theft Module
 *  Runs on Core 0 via FreeRTOS task launched from main .ino
 *
 *  BEHAVIOR:
 *   • Advertises as "SmartHelmet_Security" over BLE
 *   • Phone connects → LED solid ON
 *   • BLE disconnects → speaker alarm + LED blinks
 *   • Phone sends "DISARM" → alarm silenced
 *   • Phone sends "ARM"    → alarm re-armed
 *   • Auto re-advertises so phone can always reconnect
 * ════════════════════════════════════════════════════════════
 */

// ── BLE UUIDs — must match your Android app exactly ────────
#define BLE_DEVICE_NAME  "SmartHelmet_Security"
#define SERVICE_UUID     "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
#define CHAR_ALERT_UUID  "a1b2c3d4-e5f6-7890-abcd-ef1234567891"  // Notify → phone
#define CHAR_CMD_UUID    "a1b2c3d4-e5f6-7890-abcd-ef1234567892"  // Write  ← phone

// ── Alarm tuning ───────────────────────────────────────────
#define ALARM_FREQ_HZ   1000   // Hz tone for passive buzzer
#define BEEP_ON_MS       400   // beep duration
#define BEEP_OFF_MS      200   // gap between beeps
#define ALARM_REPEAT_MS 3000   // repeat alarm every 3s while disconnected

// ── Shared state (volatile = visible across both cores) ────
volatile bool bleConnected     = false;
volatile bool prevBleConnected = false;
volatile bool alarmArmed       = true;
volatile bool alarmActive      = false;

// ── BLE object handles ─────────────────────────────────────
static BLEServer*         pServer    = nullptr;
static BLECharacteristic* pCharAlert = nullptr;
static BLECharacteristic* pCharCmd   = nullptr;

// ══════════════════════════════════════════════════════════
//  SPEAKER HELPERS — ESP32 Core v3.x API
//  (ledcAttach replaces old ledcSetup + ledcAttachPin)
// ══════════════════════════════════════════════════════════
static void tone_start(int freq) {
  ledcAttach(SPEAKER_PIN, freq, 8); // pin, Hz, 8-bit resolution
  ledcWrite(SPEAKER_PIN, 128);      // 50% duty = audible tone
}

static void tone_stop() {
  ledcWrite(SPEAKER_PIN, 0);
  ledcDetach(SPEAKER_PIN);
}

static void playAlarm() {
  for (int i = 0; i < 3; i++) {
    tone_start(ALARM_FREQ_HZ);
    vTaskDelay(pdMS_TO_TICKS(BEEP_ON_MS));
    tone_stop();
    vTaskDelay(pdMS_TO_TICKS(BEEP_OFF_MS));
  }
}

static void silenceAlarm() {
  tone_stop();
  alarmActive = false;
  Serial.println("[BLE] Alarm silenced");
}

// ══════════════════════════════════════════════════════════
//  BLE CALLBACKS
// ══════════════════════════════════════════════════════════

// Receives ARM / DISARM / STATUS commands written from phone
class CmdCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) {
    String cmd = pChar->getValue(); // Core v3.x returns String directly
    cmd.trim();
    Serial.print("[BLE CMD] "); Serial.println(cmd);

    if (cmd == "DISARM") {
      alarmArmed  = false;
      alarmActive = false;
      silenceAlarm();
    }
    else if (cmd == "ARM") {
      alarmArmed = true;
      Serial.println("[BLE] Alarm re-armed");
    }
    else if (cmd == "STATUS") {
      String s = alarmArmed ? "ARMED" : "DISARMED";
      pCharAlert->setValue(s.c_str());
      pCharAlert->notify();
    }
  }
};

// Connection / disconnection events
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) {
    bleConnected = true;
    Serial.println("[BLE] ✅ Phone connected");
  }
  void onDisconnect(BLEServer* s) {
    bleConnected = false;
    Serial.println("[BLE] ❌ Phone disconnected — anti-theft triggered!");
  }
};

// ══════════════════════════════════════════════════════════
//  FREERTOS TASK — called from smart_helmet_project.ino
// ══════════════════════════════════════════════════════════
void bleTask(void* parameter) {
  Serial.println("[BLE] Task started on Core 0");

  // Initialise BLE stack
  BLEDevice::init(BLE_DEVICE_NAME);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  // Alert characteristic — notifies phone of events
  pCharAlert = pService->createCharacteristic(
    CHAR_ALERT_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharAlert->addDescriptor(new BLE2902());
  pCharAlert->setValue("HELMET_READY");

  // Command characteristic — receives commands from phone
  pCharCmd = pService->createCharacteristic(
    CHAR_CMD_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  pCharCmd->setCallbacks(new CmdCallback());

  pService->start();

  BLEAdvertising* pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  pAdv->setMinPreferred(0x06);
  BLEDevice::startAdvertising();

  Serial.print("[BLE] Advertising as: ");
  Serial.println(BLE_DEVICE_NAME);

  // Startup double-beep — confirms speaker is alive
  tone_start(800);  vTaskDelay(pdMS_TO_TICKS(180));
  tone_stop();      vTaskDelay(pdMS_TO_TICKS(80));
  tone_start(1200); vTaskDelay(pdMS_TO_TICKS(180));
  tone_stop();

  // ── Main BLE loop ───────────────────────────────────────
  unsigned long lastAlarmTime = 0;
  unsigned long lastBlink     = 0;
  bool ledState = false;

  for (;;) {

    // Just connected
    if (bleConnected && !prevBleConnected) {
      prevBleConnected = true;
      alarmActive      = false;
      silenceAlarm();
      digitalWrite(LED_PIN, HIGH);        // Solid ON = connected

      pCharAlert->setValue("HELMET_CONNECTED");
      pCharAlert->notify();
      Serial.println("[BLE] Notified phone: HELMET_CONNECTED");
    }

    // Just disconnected
    if (!bleConnected && prevBleConnected) {
      prevBleConnected = false;
      digitalWrite(LED_PIN, LOW);

      if (alarmArmed) {
        alarmActive   = true;
        lastAlarmTime = millis();
        Serial.println("[BLE] ANTI-THEFT ACTIVE");
        playAlarm();                      // First burst immediately
      }

      // Re-advertise so phone can reconnect
      vTaskDelay(pdMS_TO_TICKS(500));
      BLEDevice::startAdvertising();
      Serial.println("[BLE] Re-advertising...");
    }

    // Alarm repeating while disconnected
    if (alarmActive && !bleConnected) {
      unsigned long now = millis();

      // Blink LED every 200ms
      if (now - lastBlink >= 200) {
        lastBlink = now;
        ledState  = !ledState;
        digitalWrite(LED_PIN, ledState);
      }

      // Repeat alarm every ALARM_REPEAT_MS
      if (now - lastAlarmTime >= ALARM_REPEAT_MS) {
        lastAlarmTime = now;
        playAlarm();
      }
    }

    vTaskDelay(pdMS_TO_TICKS(10));
  }
}
