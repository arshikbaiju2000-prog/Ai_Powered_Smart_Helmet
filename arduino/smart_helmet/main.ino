/*
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║           SMART HELMET — Main Entry Point                           ║
 * ║                                                                      ║
 * ║  This folder contains multiple .ino files — Arduino IDE merges      ║
 * ║  them all into ONE single binary automatically.                      ║
 * ║                                                                      ║
 * ║  FILES:                                                              ║
 * ║   smart_helmet_project.ino  ← YOU ARE HERE (setup + loop)          ║
 * ║   camera_wifi.ino           ← WiFi AP + Camera streaming            ║
 * ║   ble_antitheft.ino         ← BLE anti-theft + speaker alarm        ║
 * ║                                                                      ║
 * ║  ARDUINO IDE SETTINGS:                                              ║
 * ║   Board            : ESP32 Dev Module (or ESP32-S3)                 ║
 * ║   PSRAM            : Enabled                                        ║
 * ║   Partition Scheme : Huge APP (3MB No OTA)                         ║
 * ║   CPU Frequency    : 240MHz                                         ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */

#include <WiFi.h>
#include <esp_camera.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── Shared hardware pins (used by both modules) ────────────
#define LED_PIN      2    // Built-in LED
#define SPEAKER_PIN  25   // Passive buzzer / speaker

// ── FreeRTOS task handles (defined here, used across files) ─
TaskHandle_t bleTaskHandle = NULL;
TaskHandle_t camTaskHandle = NULL;

// ── Forward declarations (implemented in their own .ino) ────
void bleTask(void* parameter);    // defined in ble_antitheft.ino
void cameraTask(void* parameter); // defined in camera_wifi.ino

// ════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n╔══════════════════════════════════════╗");
  Serial.println("║     SmartHelmet — Starting Up        ║");
  Serial.println("╚══════════════════════════════════════╝");

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  pinMode(SPEAKER_PIN, OUTPUT);

  // Launch BLE anti-theft on Core 0
  xTaskCreatePinnedToCore(
    bleTask,        // function in ble_antitheft.ino
    "BLE_Task",
    8192,           // stack bytes — BLE needs ≥8KB
    NULL,
    2,              // priority — BLE higher so alarm is never delayed
    &bleTaskHandle,
    0               // Core 0
  );

  // Launch Camera/WiFi streaming on Core 1
  xTaskCreatePinnedToCore(
    cameraTask,     // function in camera_wifi.ino
    "CAM_Task",
    8192,
    NULL,
    1,
    &camTaskHandle,
    1               // Core 1
  );

  Serial.println("[MAIN] BLE task → Core 0");
  Serial.println("[MAIN] Camera task → Core 1");
  Serial.println("[MAIN] Both running. loop() is idle.");
}

// ════════════════════════════════════════════════════════════
void loop() {
  // All logic lives in FreeRTOS tasks.
  // loop() just yields — do not add blocking code here.
  vTaskDelay(pdMS_TO_TICKS(1000));
}
