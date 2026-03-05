#include <TinyGPS++.h>

#define SIM_RX 8
#define SIM_TX 9

HardwareSerial simSerial(1);
TinyGPSPlus gps;

// ── ThingSpeak config ─────────────────────────────────────────────────────
const char* APN             = "portalnmms";           // Vodafone Idea
const char* THINGSPEAK_HOST = "api.thingspeak.com";
const char* WRITE_API_KEY   = "75FK06PDBUXOD190";
const char* DEVICE_ID       = "HELMET_001";
// ─────────────────────────────────────────────────────────────────────────

extern volatile bool alarmActive;

// ════════════════════════════════════════════════════════════════════════
//  Helper: send AT command, wait, return response
// ════════════════════════════════════════════════════════════════════════

String sendAT(String cmd, int waitMs = 1000) {
  while (simSerial.available()) simSerial.read();
  Serial.println("SEND: " + cmd);
  simSerial.println(cmd);
  vTaskDelay(pdMS_TO_TICKS(waitMs));
  String resp = "";
  while (simSerial.available()) resp += (char)simSerial.read();
  resp.trim();
  Serial.println("RECV: " + resp);
  return resp;
}

// ════════════════════════════════════════════════════════════════════════
//  Setup GPRS bearer
// ════════════════════════════════════════════════════════════════════════

bool setupGPRS() {
  sendAT("AT+SAPBR=3,1,\"Contype\",\"GPRS\"");
  sendAT("AT+SAPBR=3,1,\"APN\",\"" + String(APN) + "\"");
  sendAT("AT+SAPBR=1,1", 3000);
  String ip = sendAT("AT+SAPBR=2,1");
  if (ip.indexOf("0.0.0.0") != -1) {
    Serial.println(">>> ❌ No GPRS IP — check SIM/APN");
    return false;
  }
  Serial.println(">>> ✅ GPRS connected: " + ip);
  return true;
}

// ════════════════════════════════════════════════════════════════════════
//  Send location to ThingSpeak
//
//  ThingSpeak plain HTTP GET format:
//  GET /update?api_key=KEY&field1=deviceId&field2=lat&field3=lng&field4=speed
//
//  No SSL, no JSON, no custom headers — works perfectly on SIM808
// ════════════════════════════════════════════════════════════════════════

void sendLocationToThingSpeak() {
  Serial.println("\n════ THINGSPEAK UPLOAD ════");

  String lat = String(gps.location.isValid() ? gps.location.lat() : 0.0, 6);
  String lng = String(gps.location.isValid() ? gps.location.lng() : 0.0, 6);
  String spd = String(gps.speed.isValid()    ? gps.speed.kmph()   : 0.0, 2);

  Serial.println("Lat: " + lat + " Lng: " + lng + " Speed: " + spd);

  // ── STEP 1: GPRS ─────────────────────────────────────────────────────
  if (!setupGPRS()) return;

  // ── STEP 2: Close old HTTP session ───────────────────────────────────
  sendAT("AT+HTTPTERM", 500);

  // ── STEP 3: Init HTTP ────────────────────────────────────────────────
  String initResp = sendAT("AT+HTTPINIT");
  if (initResp.indexOf("ERROR") != -1) {
    Serial.println(">>> ❌ HTTPINIT failed");
    sendAT("AT+SAPBR=0,1");
    return;
  }

  // ── STEP 4: Bearer profile ───────────────────────────────────────────
  sendAT("AT+HTTPPARA=\"CID\",1");

  // ── STEP 5: No SSL — plain HTTP port 80 ──────────────────────────────
  sendAT("AT+HTTPSSL=0");

  // ── STEP 6: Build GET URL ─────────────────────────────────────────────
  // field1 = deviceId
  // field2 = latitude
  // field3 = longitude
  // field4 = speed
  String url = "http://" + String(THINGSPEAK_HOST)
             + "/update?api_key=" + String(WRITE_API_KEY)
             + "&field1="  + String(DEVICE_ID)
             + "&field2="  + lat
             + "&field3="  + lng
             + "&field4="  + spd;

  Serial.println("URL: " + url);
  sendAT("AT+HTTPPARA=\"URL\",\"" + url + "\"");

  // ── STEP 7: Execute GET request ───────────────────────────────────────
  String getResp = sendAT("AT+HTTPACTION=0", 10000); // 0 = GET

  // ThingSpeak returns 200 + entry number on success
  // e.g. +HTTPACTION: 0,200,1  (entry ID = 1)
  if (getResp.indexOf(",200,") != -1) {
    Serial.println(">>> ✅ Saved to ThingSpeak!");
  } else if (getResp.indexOf(",400,") != -1) {
    Serial.println(">>> ❌ 400 — check Write API Key");
  } else if (getResp.indexOf(",404,") != -1) {
    Serial.println(">>> ❌ 404 — check channel/URL");
  } else {
    Serial.println(">>> ⚠️ Unexpected: " + getResp);
  }

  // ── STEP 8: Read response (ThingSpeak returns entry number) ──────────
  vTaskDelay(pdMS_TO_TICKS(500));
  sendAT("AT+HTTPREAD", 2000);

  // ── STEP 9: Cleanup ───────────────────────────────────────────────────
  sendAT("AT+HTTPTERM");
  sendAT("AT+SAPBR=0,1");

  Serial.println("════ DONE ════\n");
}

// ════════════════════════════════════════════════════════════════════════
//  GPS Task
// ════════════════════════════════════════════════════════════════════════

void gpsTask(void* parameter) {
  Serial.println("[GPS] Task started");
  simSerial.begin(9600, SERIAL_8N1, SIM_RX, SIM_TX);
  vTaskDelay(pdMS_TO_TICKS(2000));

  // Power on GPS
  sendAT("AT+CGNSPWR=1");
  delay(50);
  sendAT("AT+CGNSSEQ=RMC"); //configure GPS settings
  delay(150);
  sendAT("AT+CGPSSTATUS?"); //check if GPS fix is already available (should be either 2d or 3d fixed location)

  unsigned long lastUploadTime = 0;

  for (;;) {
    // Feed GPS data
    while (simSerial.available() > 0) gps.encode(simSerial.read());

    // Log GPS status every 10s
    static unsigned long lastGpsLog = 0;
    if (millis() - lastGpsLog > 10000) {
      if (gps.location.isValid()) {
        Serial.printf("[GPS] ✅ lat=%.6f lng=%.6f speed=%.1f\n",
          gps.location.lat(), gps.location.lng(), gps.speed.kmph());
      } else {
        Serial.println("[GPS] ⏳ No fix yet");
      }
      lastGpsLog = millis();
    }

    // Upload when alarm is active — every 30s
    if (alarmActive) {
      if (millis() - lastUploadTime > 30000) {
        sendLocationToThingSpeak();
        lastUploadTime = millis();
      }
    }

    vTaskDelay(pdMS_TO_TICKS(500));
  }
}
