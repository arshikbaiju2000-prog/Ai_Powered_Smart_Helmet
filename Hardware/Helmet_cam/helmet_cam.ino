#include <WiFi.h>
#include <esp_camera.h>

#define SERVER_PORT 12345

#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     10
#define SIOD_GPIO_NUM     40
#define SIOC_GPIO_NUM     39

#define Y9_GPIO_NUM       48
#define Y8_GPIO_NUM       11
#define Y7_GPIO_NUM       12
#define Y6_GPIO_NUM       14
#define Y5_GPIO_NUM       16
#define Y4_GPIO_NUM       18
#define Y3_GPIO_NUM       17
#define Y2_GPIO_NUM       15
#define VSYNC_GPIO_NUM    38
#define HREF_GPIO_NUM     47
#define PCLK_GPIO_NUM     13


const char* ssid = "ESP32_CAM";
const char* password = "12345678";

WiFiServer server(SERVER_PORT);
WiFiClient client;

// Camera config
camera_config_t config;

void setupCamera() {
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

  config.frame_size   = FRAMESIZE_QXGA;   // 2048x1536
  config.jpeg_quality = 8;
  config.fb_count     = 1;

  esp_camera_init(&config);
}

void setup() {
  Serial.begin(115200);

  setupCamera();

  WiFi.softAP(ssid, password);
  server.begin();

  Serial.println("ESP32 Camera Server Started");
  Serial.println(WiFi.softAPIP()); // should be 192.168.4.1
}

void loop() {
  if (!client || !client.connected()) {
    client = server.available();
    return;
  }

  // Wait for START_STREAM command
  if (client.available()) {
    String cmd = client.readStringUntil('\n');
    cmd.trim();

    if (cmd == "START_STREAM") {
      Serial.println("Streaming started");

      while (client.connected()) {
        camera_fb_t* fb = esp_camera_fb_get();
        if (!fb) {
          Serial.println("Camera capture failed");
          break;
        }

        uint32_t size = fb->len;

        // Send image size (big-endian)
        client.write((uint8_t*)&size + 3, 1);
        client.write((uint8_t*)&size + 2, 1);
        client.write((uint8_t*)&size + 1, 1);
        client.write((uint8_t*)&size + 0, 1);

        // Send image data
        client.write(fb->buf, fb->len);
        client.flush();

        esp_camera_fb_return(fb);

        delay(500); // 1 image every 5 seconds
      }
    }
  }
}
