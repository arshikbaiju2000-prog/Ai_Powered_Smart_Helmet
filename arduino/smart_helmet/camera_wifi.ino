/*
 * ════════════════════════════════════════════════════════════
 *  camera_wifi.ino — WiFi AP + Camera Streaming Module
 *  Runs on Core 1 via FreeRTOS task launched from main .ino
 *
 *  Your original camera streaming logic, untouched.
 *  Converted to a FreeRTOS task so it runs alongside BLE.
 * ════════════════════════════════════════════════════════════
 */

// ── Camera Pin Config (your original pinout) ───────────────
#define PWDN_GPIO_NUM    -1
#define RESET_GPIO_NUM   -1
#define XCLK_GPIO_NUM    10
#define SIOD_GPIO_NUM    40
#define SIOC_GPIO_NUM    39
#define Y9_GPIO_NUM      48
#define Y8_GPIO_NUM      11
#define Y7_GPIO_NUM      12
#define Y6_GPIO_NUM      14
#define Y5_GPIO_NUM      16
#define Y4_GPIO_NUM      18
#define Y3_GPIO_NUM      17
#define Y2_GPIO_NUM      15
#define VSYNC_GPIO_NUM   38
#define HREF_GPIO_NUM    47
#define PCLK_GPIO_NUM    13



// ── WiFi Config (your original credentials) ────────────────
#define CAM_SERVER_PORT  12345
const char* cam_ssid     = "ESP32_CAM";
const char* cam_password = "12345678";

WiFiServer camServer(CAM_SERVER_PORT);
WiFiClient camClient;

// ── Camera initialisation (your original code) ─────────────
static void setupCamera() {
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0       = Y2_GPIO_NUM;
  config.pin_d1       = Y3_GPIO_NUM;
  config.pin_d2       = Y4_GPIO_NUM;
  config.pin_d3       = Y5_GPIO_NUM;
  config.pin_d4       = Y6_GPIO_NUM;
  config.pin_d5       = Y7_GPIO_NUM;
  config.pin_d6       = Y8_GPIO_NUM;
  config.pin_d7       = Y9_GPIO_NUM;
  config.pin_xclk     = XCLK_GPIO_NUM;
  config.pin_pclk     = PCLK_GPIO_NUM;
  config.pin_vsync    = VSYNC_GPIO_NUM;
  config.pin_href     = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn     = PWDN_GPIO_NUM;
  config.pin_reset    = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size   = FRAMESIZE_QXGA;  // 2048×1536
  // ⚠️ If heap errors occur, reduce to FRAMESIZE_VGA
  config.jpeg_quality = 8;
  config.fb_count     = 1;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[CAM] Init FAILED: 0x%x\n", err);
  } else {
    Serial.println("[CAM] Camera OK");
  }
}

// ── FreeRTOS Task — called from smart_helmet_project.ino ───
void cameraTask(void* parameter) {
  Serial.println("[CAM] Task started on Core 1");

  setupCamera();

  WiFi.softAP(cam_ssid, cam_password);
  camServer.begin();

  Serial.print("[CAM] WiFi AP: "); Serial.println(cam_ssid);
  Serial.print("[CAM] IP: ");      Serial.println(WiFi.softAPIP());

  for (;;) {

    // Accept new client when none connected
    if (!camClient || !camClient.connected()) {
      camClient = camServer.available();
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }

    // Wait for START_STREAM command from phone
    if (camClient.available()) {
      String cmd = camClient.readStringUntil('\n');
      cmd.trim();

      if (cmd == "START_STREAM") {
        Serial.println("[CAM] Streaming started");

        while (camClient.connected()) {
          camera_fb_t* fb = esp_camera_fb_get();
          if (!fb) {
            Serial.println("[CAM] Frame capture failed");
            break;
          }

          // Send 4-byte big-endian image size (your original protocol)
          uint32_t size = fb->len;
          camClient.write((uint8_t*)&size + 3, 1);
          camClient.write((uint8_t*)&size + 2, 1);
          camClient.write((uint8_t*)&size + 1, 1);
          camClient.write((uint8_t*)&size + 0, 1);

          // Send image data
          camClient.write(fb->buf, fb->len);
          camClient.flush();
          esp_camera_fb_return(fb);

          vTaskDelay(pdMS_TO_TICKS(500)); // 1 frame per 500ms
        }

        Serial.println("[CAM] Client disconnected");
      }
    }

    vTaskDelay(pdMS_TO_TICKS(10));
  }
}
