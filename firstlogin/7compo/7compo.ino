#include <Arduino.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <RTClib.h>
#include <DHT.h>
#include "FS.h"
#include "SD.h"
#include "SPI.h"
#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <time.h>
#include <HardwareSerial.h>

// System overview:
// - Reads aquarium sensors (temperature, humidity, pH, water level, turbidity)
// - Controls light relay + feeder servo (manual button, VC-02 voice, Firebase, schedule)
// - Shows live values on 20x4 LCD
// - Logs hourly data to SD and syncs logs + realtime status to Firebase

// ===== WIFI + FIREBASE =====
#define WIFI_SSID "Sqr1n2.4G"
#define WIFI_PASSWORD "!@#Engnan!@#12345"
#define API_KEY "AIzaSyABOF1n8z5T_NQP1w75cU8FcvqxD7znZ0I"
#define DATABASE_URL "https://aquasave-64c9f-default-rtdb.firebaseio.com"
#define I2C_SDA_PIN 21
#define I2C_SCL_PIN 22
#define DS3231_I2C_ADDRESS 0x68

// ===== RELAY + FEEDER =====
#define RELAY_PIN 2
#define FEEDER_SERVO_PIN 25
// Most ESP32 relay modules are active-low: LOW energizes the relay, HIGH releases it.
#define RELAY_ON_LEVEL LOW
#define RELAY_OFF_LEVEL HIGH
#define FEEDER_SERVO_CHANNEL 2
#define FEEDER_SERVO_FREQ 50
#define FEEDER_SERVO_RESOLUTION 16
#define FEEDER_SERVO_OPEN_ANGLE 75
#define FEEDER_SERVO_CLOSED_ANGLE 0
#define FEEDER_SERVO_OPEN_TIME_MS 300
#define FEEDER_SERVO_SETTLE_TIME_MS 120
#define MANUAL_LIGHT_BUTTON_PIN 27
#define VC02_RX_PIN 16
#define VC02_TX_PIN 17
#define VC02_BAUD_RATE 9600
#define VC02_OPPOSITE_GUARD_MS 15000
#define VC02_CONFIRM_WINDOW_MS 1200

bool lightOn = false;
bool feederBusy = false;
unsigned long lastFeedActionMs = 0;
const unsigned long feederCooldownMs = 3000;
const unsigned long manualLightButtonDebounceMs = 60;
const unsigned long vc02LocalOnlyHoldMs = 1500;
const unsigned long vc02MinAcceptedIntervalMs = 700;
const bool vc02BypassFirebaseLightControl = false;
unsigned long lastManualLightButtonChangeMs = 0;
int lastManualLightButtonReading = HIGH;
int lastManualLightButtonStableState = HIGH;
byte pendingVc02Command = 0;
unsigned long pendingVc02CommandMs = 0;
unsigned long lastVc02AppliedMs = 0;
unsigned long ignoreFirebaseLightUntilMs = 0;
int lastProcessedFeedRequestId = 0;
int lastFeedEpochSeconds = 0;
String lastScheduledFeedKey = "";
unsigned long lastScheduleCheckMs = 0;
const unsigned long scheduleCheckIntervalMs = 30000;
HardwareSerial VC02(1);

// ===== LCD =====
LiquidCrystal_I2C lcd(0x27, 20, 4);

// ===== RTC =====
RTC_DS3231 rtc;
bool rtc_ok = false;
#define SET_RTC_TIME_ONCE true

// ===== DHT11 =====
#define DHTPIN 4
#define DHTTYPE DHT11
DHT dht(DHTPIN, DHTTYPE);

// ===== pH SENSOR =====
#define PH_PIN 34
float phValue = -1;
float calibration_value = 21.34f + 1.5f;
unsigned long int avgval = 0;
int buffer_arr[10];
int buffer_temp = 0;
float ph_act = -1;
float phVoltage = 0;
int phAdcSpan = 0;

// ===== TURBIDITY SENSOR =====
#define TURBIDITY_PIN 35
// Replaced NTU calibration model with simple mapped percentage model
// to match your requested LCD logic (CLEAR/CLOUDY/DIRTY).
const int TURBIDITY_MAP_RAW_MIN = 0;
const int TURBIDITY_MAP_RAW_MAX = 3200;
int turbidityPercent = -1;
int turbidityRaw = 0;
// Optional indicator LEDs (can be left unwired).
#define TURBIDITY_LED_CLEAR_PIN 26
#define TURBIDITY_LED_CLOUDY_PIN 32
#define TURBIDITY_LED_DIRTY_PIN 33
String turbidityStatus = "UNKNOWN";
String turbidityAlertMessage = "Waiting for turbidity reading";
bool turbidityAlertActive = false;

// ===== ULTRASONIC =====
#define TRIG 12
#define ECHO 13
float distance;
const float WATER_HIGH_LEVEL_DISTANCE_CM = 15.0;
const float WATER_LOW_LEVEL_DISTANCE_CM = 35.0;
String waterLevelStatus = "UNKNOWN";
String waterAlertMessage = "Waiting for water level reading";
bool waterAlertActive = false;

// ===== CALIBRATION =====
const float PH_TARGET = 8.0;
// pH formula adapted from your provided ESP32 example.
const float PH_ADC_VREF = 3.3f;
const int PH_ADC_MAX = 4095;
const float PH_GOOD_MIN = 7.8;
const float PH_GOOD_MAX = 8.2;
const float PH_WARNING_MIN = 7.5;
const float PH_WARNING_MAX = 8.5;
String phStatus = "UNKNOWN";
String phAlertMessage = "Waiting for pH reading";
bool phAlertActive = false;

// ===== VALUES =====
float humidity = 0;
float temperature = 0;
const float TEMP_LOW_LIMIT_C = 24.0;
const float TEMP_HIGH_LIMIT_C = 32.0;
String temperatureStatus = "UNKNOWN";
String temperatureAlertMessage = "Waiting for temperature reading";
bool temperatureAlertActive = false;
String humidityStatus = "UNKNOWN";
String humidityAlertMessage = "Waiting for humidity reading";
bool humidityAlertActive = false;

// ===== SD CARD =====
#define SD_CS 5
bool sd_ok = false;
bool allowDataBackup = true;

// ===== FIREBASE OBJECTS =====
FirebaseData fbdo;
FirebaseData lightStream;
FirebaseData feedStream;
FirebaseData feedControlData;
FirebaseAuth auth;
FirebaseConfig config;

unsigned long lastFirebaseMs = 0;
const unsigned long firebaseIntervalMs = 5000;
unsigned long lastLightCheckMs = 0;
const unsigned long lightCheckIntervalMs = 10000;
unsigned long lastLightReadErrorMs = 0;
const unsigned long lightReadErrorLogIntervalMs = 30000;
unsigned long lastFeedCheckMs = 0;
const unsigned long feedCheckIntervalMs = 100;
unsigned long lastFeedReadErrorMs = 0;
const unsigned long feedReadErrorLogIntervalMs = 30000;
unsigned long lastWiFiReconnectMs = 0;
const unsigned long wifiReconnectIntervalMs = 10000;
unsigned long lastTimeSyncRetryMs = 0;
const unsigned long timeSyncRetryIntervalMs = 30000;
unsigned long lastTimeSyncLogMs = 0;
const unsigned long timeSyncLogIntervalMs = 30000;
unsigned long lastBackupControlCheckMs = 0;
const unsigned long backupControlCheckIntervalMs = 10000;
unsigned long lastBackupControlReadErrorMs = 0;
const unsigned long backupControlReadErrorLogIntervalMs = 30000;
unsigned long lastCloudBootstrapMs = 0;
const unsigned long cloudBootstrapRetryMs = 10000;
bool cloudBootstrapDone = false;

// ===== TIME / SYNC =====
const long gmtOffset_sec = 8 * 3600;  // Philippines UTC+8
const int daylightOffset_sec = 0;

const char* SD_LOG_PATH = "/data.csv";
const char* SD_CURSOR_PATH = "/upload.cursor";
const bool SKIP_BACKLOG_ON_BOOT = true;

unsigned long lastSyncMs = 0;
const unsigned long syncIntervalMs = 60000;  // 1 min
const int maxSyncLinesPerCycle = 1;

String lastLoggedHourKey = "";
String lastTrendKey = "";
bool deprecatedCloudCleanupDone = false;

// One-time cleanup list for old Firebase keys no longer used by app/firmware.
const char* deprecatedCloudPaths[] = {
  "/status/waterLevelStatus",
  "/status/temperatureStatus",
  "/status/humidityStatus",
  "/status/phStatus",
  "/status/turbidityStatus",
  "/alerts/waterLevel/lowThresholdCm",
  "/alerts/waterLevel/highThresholdCm",
  "/alerts/temperature/lowThreshold",
  "/alerts/temperature/highThreshold",
  "/alerts/ph/target",
  "/alerts/ph/goodMinThreshold",
  "/alerts/ph/goodMaxThreshold",
  "/alerts/ph/warningMinThreshold",
  "/alerts/ph/warningMaxThreshold",
  "/alerts/turbidity/impact",
  "/alerts/turbidity/voltage",
  "/alerts/turbidity/adcSpan",
  "/alerts/turbidity/goodMinThreshold",
  "/alerts/turbidity/goodMaxThreshold",
  "/alerts/turbidity/warningMaxThreshold",
  "/alerts/turbidity/criticalMaxThreshold",
  "/alerts/turbidity/recommended/good",
  "/alerts/turbidity/recommended/warning",
  "/alerts/turbidity/recommended/critical",
  "/controls/feedRequestId"
};

// Convert servo pulse width (microseconds) to ESP32 LEDC duty cycle.
uint32_t servoPulseWidthToDuty(int microseconds) {
  return (uint32_t)(((float)microseconds / 20000.0f) * ((1 << FEEDER_SERVO_RESOLUTION) - 1));
}

// Move feeder servo to requested angle using configured PWM channel.
void setFeederServoAngle(int angle) {
  angle = constrain(angle, 0, 180);
  int pulseWidth = map(angle, 0, 180, 500, 2400);
  ledcWrite(FEEDER_SERVO_PIN, servoPulseWidthToDuty(pulseWidth));
}

// Configure feeder PWM and park servo at closed angle on boot.
void setupFeederServo() {
  ledcAttach(FEEDER_SERVO_PIN, FEEDER_SERVO_FREQ, FEEDER_SERVO_RESOLUTION);
  setFeederServoAngle(FEEDER_SERVO_CLOSED_ANGLE);
}

// Remove stale cloud keys once per boot to keep database clean.
void cleanupDeprecatedCloudFields() {
  if (deprecatedCloudCleanupDone || !cloudReady()) return;
  for (size_t i = 0; i < sizeof(deprecatedCloudPaths) / sizeof(deprecatedCloudPaths[0]); i++) {
    Firebase.RTDB.deleteNode(&fbdo, deprecatedCloudPaths[i]);
  }
  deprecatedCloudCleanupDone = true;
  Serial.println("Cloud cleanup: removed deprecated Firebase fields");
}

// Debug helper: list all I2C devices found on SDA/SCL bus.
void scanI2CDevices() {
  Serial.println("I2C scan start...");
  byte foundDevices = 0;

  for (byte address = 1; address < 127; address++) {
    Wire.beginTransmission(address);
    byte error = Wire.endTransmission();

    if (error == 0) {
      Serial.print("I2C device found at 0x");
      if (address < 16) Serial.print("0");
      Serial.println(address, HEX);
      foundDevices++;
    }
  }

  if (foundDevices == 0) {
    Serial.println("No I2C devices found");
  }
}

bool isI2CDevicePresent(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

// Cloud operations are safe only when WiFi is connected and Firebase token is ready.
bool cloudReady() {
  return WiFi.status() == WL_CONNECTED && Firebase.ready();
}

// Retry RTC startup several times to tolerate slow power-up/I2C timing.
bool initRtcWithRetry() {
  const int maxAttempts = 5;
  for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    if (!isI2CDevicePresent(DS3231_I2C_ADDRESS)) {
      Serial.print("RTC probe attempt ");
      Serial.print(attempt);
      Serial.println(": DS3231 not detected at 0x68");
      delay(150);
      continue;
    }

    if (rtc.begin()) {
      return true;
    }

    Serial.print("RTC probe attempt ");
    Serial.print(attempt);
    Serial.println(": rtc.begin() failed, retrying...");
    delay(150);
  }
  return false;
}

// Returns current epoch from RTC/NTP source; 0 means unavailable.
int getCurrentEpochSeconds() {
  tm timeinfo;
  if (!getTimeInfo(timeinfo)) return 0;
  return (int)mktime(&timeinfo);
}

// Publish feeder state + last feed time to Firebase status paths.
void setFeederStatus(const String& state) {
  if (!cloudReady()) return;
  Firebase.RTDB.setString(&fbdo, "/status/feederState", state);
  Firebase.RTDB.setBool(&fbdo, "/status/feederBusy", feederBusy);
  if (lastFeedEpochSeconds > 0) {
    Firebase.RTDB.setInt(&fbdo, "/status/lastFeedAt", lastFeedEpochSeconds);
  }
}

// Apply light state to relay and optionally mirror it to Firebase controls.
void setLight(bool on, bool syncFirebase) {
  lightOn = on;
  digitalWrite(RELAY_PIN, lightOn ? RELAY_ON_LEVEL : RELAY_OFF_LEVEL);
  Serial.print("Light: ");
  Serial.println(lightOn ? "ON" : "OFF");

  if (syncFirebase && cloudReady()) {
    Firebase.RTDB.setBool(&fbdo, "/controls/light", lightOn);
  }
}

// Classify water level from ultrasonic distance into NORMAL/LOW/HIGH/error.
void updateWaterLevelStatus() {
  if (distance < 0) {
    waterLevelStatus = "SENSOR_ERROR";
    waterAlertActive = true;
    waterAlertMessage = "Water sensor has no echo reading";
  } else if (distance >= WATER_LOW_LEVEL_DISTANCE_CM) {
    waterLevelStatus = "LOW";
    waterAlertActive = true;
    waterAlertMessage = "Low water level detected";
  } else if (distance <= WATER_HIGH_LEVEL_DISTANCE_CM) {
    waterLevelStatus = "HIGH";
    waterAlertActive = true;
    waterAlertMessage = "High water level detected";
  } else {
    waterLevelStatus = "NORMAL";
    waterAlertActive = false;
    waterAlertMessage = "Water level is normal";
  }
}

// Classify ambient temperature against configured safe limits.
void updateTemperatureStatus() {
  if (isnan(temperature) || temperature < 0 || temperature > 80) {
    temperatureStatus = "SENSOR_ERROR";
    temperatureAlertActive = true;
    temperatureAlertMessage = "Temperature sensor reading is invalid or disconnected";
  } else if (temperature <= TEMP_LOW_LIMIT_C) {
    temperatureStatus = "LOW";
    temperatureAlertActive = true;
    temperatureAlertMessage = "Aquarium temperature is too low";
  } else if (temperature >= TEMP_HIGH_LIMIT_C) {
    temperatureStatus = "HIGH";
    temperatureAlertActive = true;
    temperatureAlertMessage = "Aquarium temperature is too high";
  } else {
    temperatureStatus = "NORMAL";
    temperatureAlertActive = false;
    temperatureAlertMessage = "Temperature is normal";
  }
}

// Classify humidity validity (DHT often returns invalid on read errors).
void updateHumidityStatus() {
  if (isnan(humidity) || humidity < 0 || humidity > 100) {
    humidityStatus = "SENSOR_ERROR";
    humidityAlertActive = true;
    humidityAlertMessage = "Humidity sensor reading is invalid or disconnected";
  } else {
    humidityStatus = "NORMAL";
    humidityAlertActive = false;
    humidityAlertMessage = "Humidity is normal";
  }
}

// Read pH using sorted samples and center-average to reduce noise.
float readPhValue() {
  for (int i = 0; i < 10; i++) {
    buffer_arr[i] = analogRead(PH_PIN);
    delay(30);
  }

  for (int i = 0; i < 9; i++) {
    for (int j = i + 1; j < 10; j++) {
      if (buffer_arr[i] > buffer_arr[j]) {
        buffer_temp = buffer_arr[i];
        buffer_arr[i] = buffer_arr[j];
        buffer_arr[j] = buffer_temp;
      }
    }
  }

  avgval = 0;
  for (int i = 2; i < 8; i++) {
    avgval += buffer_arr[i];
  }

  float volt = (float)avgval * PH_ADC_VREF / PH_ADC_MAX / 6.0f;
  phVoltage = volt;
  phAdcSpan = buffer_arr[9] - buffer_arr[0];
  ph_act = (-5.70f * volt) + calibration_value;
  ph_act = constrain(ph_act, 0.0f, 14.0f);
  return ph_act;
}

void updatePhStatus() {
  if (phValue < 0 || phValue > 14) {
    phStatus = "SENSOR_ERROR";
    phAlertActive = true;
    phAlertMessage = "Invalid pH reading (check probe/module wiring)";
  } else if (phValue >= PH_GOOD_MIN && phValue <= PH_GOOD_MAX) {
    phStatus = "GOOD";
    phAlertActive = false;
    phAlertMessage = "pH is around target 8.0";
  } else if (phValue >= PH_WARNING_MIN && phValue <= PH_WARNING_MAX) {
    phStatus = "WARNING";
    phAlertActive = true;
    phAlertMessage = "pH is near target but outside ideal band";
  } else {
    phStatus = "CRITICAL";
    phAlertActive = true;
    phAlertMessage = "pH is far from target 8.0";
  }
}

// Convert raw turbidity ADC samples to percentage scale (0-100).
int readTurbidityPercent() {
  long total = 0;
  const int samples = 20;

  for (int i = 0; i < samples; i++) {
    int raw = analogRead(TURBIDITY_PIN);
    total += raw;
    delay(5);
  }

  turbidityRaw = (int)(total / samples);
  int mapped = map(turbidityRaw, TURBIDITY_MAP_RAW_MIN, TURBIDITY_MAP_RAW_MAX, 100, 0);
  return constrain(mapped, 0, 100);
}

// Convert turbidity percentage to CLEAN/CLOUDY/DIRTY and drive indicator LEDs.
void updateTurbidityStatus() {
  if (turbidityPercent < 0) {
    turbidityStatus = "SENSOR_ERROR";
    turbidityAlertActive = true;
    turbidityAlertMessage = "Invalid turbidity reading";
    digitalWrite(TURBIDITY_LED_CLEAR_PIN, LOW);
    digitalWrite(TURBIDITY_LED_CLOUDY_PIN, LOW);
    digitalWrite(TURBIDITY_LED_DIRTY_PIN, LOW);
  } else if (turbidityPercent < 20) {
    turbidityStatus = "CLEAR";
    turbidityAlertActive = false;
    turbidityAlertMessage = "Water is clear";
    digitalWrite(TURBIDITY_LED_CLEAR_PIN, HIGH);
    digitalWrite(TURBIDITY_LED_CLOUDY_PIN, LOW);
    digitalWrite(TURBIDITY_LED_DIRTY_PIN, LOW);
  } else if (turbidityPercent < 50) {
    turbidityStatus = "CLOUDY";
    turbidityAlertActive = true;
    turbidityAlertMessage = "Water is cloudy";
    digitalWrite(TURBIDITY_LED_CLEAR_PIN, LOW);
    digitalWrite(TURBIDITY_LED_CLOUDY_PIN, HIGH);
    digitalWrite(TURBIDITY_LED_DIRTY_PIN, LOW);
  } else {
    turbidityStatus = "DIRTY";
    turbidityAlertActive = true;
    turbidityAlertMessage = "Water is dirty";
    digitalWrite(TURBIDITY_LED_CLEAR_PIN, LOW);
    digitalWrite(TURBIDITY_LED_CLOUDY_PIN, LOW);
    digitalWrite(TURBIDITY_LED_DIRTY_PIN, HIGH);
  }
}

void printLcdField(uint8_t col, uint8_t row, const String& text, uint8_t width) {
  lcd.setCursor(col, row);
  String out = text;
  if (out.length() > width) out = out.substring(0, width);
  lcd.print(out);
  for (uint8_t i = out.length(); i < width; i++) lcd.print(" ");
}

// Main LCD renderer for 20x4 layout (labels + compact live values).
void updateMainLcd() {
  tm timeinfo;
  String timeValue = "--:--";
  if (getTimeInfo(timeinfo)) {
    char hhmm[6];
    strftime(hhmm, sizeof(hhmm), "%H:%M", &timeinfo);
    timeValue = String(hhmm);
  }

  String tempValue = (temperature < 0 || temperature > 80) ? "ERR" : String(temperature, 1) + "C";
  String humidityValue = (humidity < 0 || humidity > 100) ? "ERR" : String(humidity, 0) + "%";
  String phValueText = (phValue < 0 || phValue > 14) ? "ERR" : String(phValue, 2);
  String waterValue = (distance < 0) ? "ERR" : String(distance, 0) + "cm";
  String turbidityValue = (turbidityPercent < 0) ? "ERR" : String(turbidityPercent) + "%";

  // 20x4 layout:
  // Row0: Temp <value>          <time>
  // Row1: Humidity <value>      pH <value>
  // Row2: Water lvl <value>
  // Row3: Turbidity <value>
  printLcdField(0, 0, "Temp", 4);
  printLcdField(5, 0, tempValue, 5);
  printLcdField(14, 0, timeValue, 5);

  printLcdField(0, 1, "Hum", 8);
  printLcdField(9, 1, humidityValue, 5);
  printLcdField(14, 1, "pH", 2);
  printLcdField(16, 1, phValueText, 4);

  printLcdField(0, 2, "Water lvl", 9);
  printLcdField(10, 2, waterValue, 10);

  printLcdField(0, 3, "Turb", 9);
  printLcdField(10, 3, turbidityValue, 10);
}

void printSensorErrorAlerts() {
  if (temperatureStatus == "SENSOR_ERROR") {
    Serial.print("ARDUINO ALERT: ");
    Serial.println(temperatureAlertMessage);
  }
  if (humidityStatus == "SENSOR_ERROR") {
    Serial.print("ARDUINO ALERT: ");
    Serial.println(humidityAlertMessage);
  }
  if (phStatus == "SENSOR_ERROR") {
    Serial.print("ARDUINO ALERT: ");
    Serial.println(phAlertMessage);
  }
  if (waterLevelStatus == "SENSOR_ERROR") {
    Serial.print("ARDUINO ALERT: ");
    Serial.println(waterAlertMessage);
  }
  if (turbidityStatus == "SENSOR_ERROR") {
    Serial.print("ARDUINO ALERT: ");
    Serial.println(turbidityAlertMessage);
  }
}


// Trigger feeder once, with cooldown/busy protection and cloud status updates.
bool runFeederCycle(const char* source) {
  unsigned long now = millis();
  if (feederBusy) {
    Serial.println("Feeder request ignored: feeder already busy");
    return false;
  }

  if (now - lastFeedActionMs < feederCooldownMs) {
    Serial.println("Feeder request ignored: cooldown active");
    return false;
  }

  feederBusy = true;
  lastFeedActionMs = now;
  Serial.print("Feeder request from ");
  Serial.println(source);
  setFeederStatus("Feeding");

  Serial.print("Feeder servo: closed ");
  Serial.print(FEEDER_SERVO_CLOSED_ANGLE);
  Serial.print(" -> dispense ");
  Serial.print(FEEDER_SERVO_OPEN_ANGLE);
  Serial.print(" degrees for ");
  Serial.print(FEEDER_SERVO_OPEN_TIME_MS);
  Serial.println(" ms");
  setFeederServoAngle(FEEDER_SERVO_OPEN_ANGLE);
  delay(FEEDER_SERVO_OPEN_TIME_MS);
  Serial.print("Feeder servo: return to ");
  Serial.print(FEEDER_SERVO_CLOSED_ANGLE);
  Serial.println(" degrees");
  setFeederServoAngle(FEEDER_SERVO_CLOSED_ANGLE);
  delay(FEEDER_SERVO_SETTLE_TIME_MS);

  feederBusy = false;
  lastFeedEpochSeconds = getCurrentEpochSeconds();
  Serial.print("Feeder cycle complete | Last feed epoch: ");
  Serial.println(lastFeedEpochSeconds);
  Firebase.RTDB.setInt(&fbdo, "/controls/FeedRequestId", 0);
  setFeederStatus("Ready");
  return true;
}

// Local physical button toggles light with debounce and cloud sync.
void checkManualLightButton() {
  int reading = digitalRead(MANUAL_LIGHT_BUTTON_PIN);

  if (reading != lastManualLightButtonReading) {
    lastManualLightButtonReading = reading;
    lastManualLightButtonChangeMs = millis();
  }

  if (millis() - lastManualLightButtonChangeMs < manualLightButtonDebounceMs) return;
  if (reading == lastManualLightButtonStableState) return;

  lastManualLightButtonStableState = reading;
  if (reading == LOW) {
    bool requestedState = !lightOn;
    Serial.print("Manual light button: ");
    Serial.println(requestedState ? "ON" : "OFF");
    setLight(requestedState, true);
  }
}

// VC-02 guard: block accidental rapid/opposite commands unless confirmed.
bool shouldApplyVc02Command(byte command) {
  if (command != 0xA0 && command != 0xA1) return false;
  if (lastVc02AppliedMs > 0 && (millis() - lastVc02AppliedMs) < vc02MinAcceptedIntervalMs) {
    Serial.println("VC-02 command ignored: too soon after previous command");
    return false;
  }

  bool requestedOn = command == 0xA0;
  if (requestedOn == lightOn) {
    Serial.println("VC-02 command ignored: state already matched");
    return false;
  }

  bool hasRecentVc02Command = lastVc02AppliedMs > 0 && (millis() - lastVc02AppliedMs) < VC02_OPPOSITE_GUARD_MS;
  if (!hasRecentVc02Command) {
    pendingVc02Command = 0;
    pendingVc02CommandMs = 0;
    return true;
  }

  if (pendingVc02Command == command && (millis() - pendingVc02CommandMs) <= VC02_CONFIRM_WINDOW_MS) {
    pendingVc02Command = 0;
    pendingVc02CommandMs = 0;
    return true;
  }

  pendingVc02Command = command;
  pendingVc02CommandMs = millis();
  Serial.println("VC-02 opposite command guarded: repeat quickly to confirm");
  return false;
}

// Parse VC-02 UART packets and apply local light changes (no Firebase write).
void readVc02LightCommands() {
  if (VC02.available() < 2) return;

  byte prefix = VC02.read();
  byte command = VC02.read();

  Serial.print("Prefix: 0x");
  Serial.println(prefix, HEX);
  Serial.print("Command: 0x");
  Serial.println(command, HEX);

  if (prefix != 0x20) return;

  switch (command) {
    case 0xA0:
      if (!shouldApplyVc02Command(command)) break;
      Serial.println("Light ON");
      setLight(true, false);
      ignoreFirebaseLightUntilMs = millis() + vc02LocalOnlyHoldMs;
      lastVc02AppliedMs = millis();
      break;
    case 0xA1:
      if (!shouldApplyVc02Command(command)) break;
      Serial.println("Light OFF");
      setLight(false, false);
      ignoreFirebaseLightUntilMs = millis() + vc02LocalOnlyHoldMs;
      lastVc02AppliedMs = millis();
      break;
    default:
      Serial.println("Unknown Light Command");
      break;
  }
}

// Auto-feeding schedule: run at 07:00 and 17:00 once per hour-key.
void checkScheduledFeeding() {
  if (millis() - lastScheduleCheckMs < scheduleCheckIntervalMs) return;
  lastScheduleCheckMs = millis();

  tm timeinfo;
  if (!getTimeInfo(timeinfo)) return;
  if (timeinfo.tm_min != 0) return;

  int hour = timeinfo.tm_hour;
  if (hour != 7 && hour != 17) return;

  char dateStr[11];
  strftime(dateStr, sizeof(dateStr), "%Y-%m-%d", &timeinfo);
  String feedKey = String(dateStr) + "-" + String(hour);
  if (feedKey == lastScheduledFeedKey) return;

  Serial.print("Scheduled feeding time reached: ");
  Serial.print(hour == 7 ? "7:00 AM" : "5:00 PM");
  Serial.println(" Philippines time");

  if (runFeederCycle(hour == 7 ? "Schedule 7AM" : "Schedule 5PM")) {
    lastScheduledFeedKey = feedKey;
  }
}

// Firebase stream callback for /controls/light realtime updates.
void lightStreamCallback(FirebaseStream data) {
  if (vc02BypassFirebaseLightControl) return;
  if (data.dataType() != "boolean") return;
  if (millis() < ignoreFirebaseLightUntilMs) return;

  bool requestedLightState = data.boolData();
  if (requestedLightState != lightOn) {
    Serial.print("Firebase light stream: ");
    Serial.println(requestedLightState ? "ON" : "OFF");
    setLight(requestedLightState, false);
  }
}

void lightStreamTimeoutCallback(bool timeout) {
  if (timeout) {
    Serial.println("Firebase light stream timed out, resuming...");
  }
}

// Handle a feed request id from cloud, dedupe it, and run the feeder.
void processFeedRequestId(int requestId, const char* source) {
  if (requestId <= 0) return;
  if (requestId == lastProcessedFeedRequestId) return;

  lastProcessedFeedRequestId = requestId;
  Serial.print("Firebase feed request received (");
  Serial.print(source);
  Serial.print("): ");
  Serial.println(requestId);
  runFeederCycle("Firebase");
}

// Firebase stream callback for /controls/FeedRequestId realtime updates.
void feedStreamCallback(FirebaseStream data) {
  int requestId = 0;
  String type = data.dataType();
  if (type == "int") {
    requestId = data.intData();
  } else if (type == "double") {
    requestId = (int)data.doubleData();
  } else if (type == "string") {
    requestId = data.stringData().toInt();
  } else {
    return;
  }
  processFeedRequestId(requestId, "stream");
}

void feedStreamTimeoutCallback(bool timeout) {
  if (timeout) {
    Serial.println("Firebase feed stream timed out, resuming...");
  }
}

// Poll fallback for light control if stream misses updates.
void checkFirebaseLightControl() {
  if (vc02BypassFirebaseLightControl) return;
  if (!cloudReady()) return;
  if (millis() < ignoreFirebaseLightUntilMs) return;
  if (millis() - lastLightCheckMs < lightCheckIntervalMs) return;

  lastLightCheckMs = millis();

  if (Firebase.RTDB.getBool(&fbdo, "/controls/light")) {
    bool requestedLightState = fbdo.boolData();
    if (requestedLightState != lightOn) {
      Serial.print("Firebase light request: ");
      Serial.println(requestedLightState ? "ON" : "OFF");
      setLight(requestedLightState, false);
    }
  } else {
    if (millis() - lastLightReadErrorMs >= lightReadErrorLogIntervalMs) {
      lastLightReadErrorMs = millis();
      Serial.print("Firebase light read error: ");
      Serial.println(fbdo.errorReason());
    }
  }
}

// Poll FeedRequestId and execute feeder cycle on new request id.
void checkFirebaseFeedControl() {
  if (!cloudReady()) return;
  if (millis() - lastFeedCheckMs < feedCheckIntervalMs) return;

  lastFeedCheckMs = millis();

  if (Firebase.RTDB.getInt(&feedControlData, "/controls/FeedRequestId")) {
    processFeedRequestId(feedControlData.intData(), "poll");
  } else if (millis() - lastFeedReadErrorMs >= feedReadErrorLogIntervalMs) {
    lastFeedReadErrorMs = millis();
    Serial.print("Firebase feed read error: ");
    Serial.println(feedControlData.errorReason());
  }
}

// Read backup toggle from cloud: true = SD logging enabled, false = disabled.
void checkFirebaseBackupControl() {
  if (!cloudReady()) return;
  if (millis() - lastBackupControlCheckMs < backupControlCheckIntervalMs) return;

  lastBackupControlCheckMs = millis();

  if (Firebase.RTDB.getBool(&fbdo, "/controls/allowDataBackup")) {
    bool requested = fbdo.boolData();
    if (requested != allowDataBackup) {
      allowDataBackup = requested;
      Serial.print("Backup control updated: SD logging ");
      Serial.println(allowDataBackup ? "ENABLED" : "DISABLED");
    }
  } else if (millis() - lastBackupControlReadErrorMs >= backupControlReadErrorLogIntervalMs) {
    lastBackupControlReadErrorMs = millis();
    Serial.print("Firebase backup control read error: ");
    Serial.println(fbdo.errorReason());
  }
}

// Time provider abstraction: prefer RTC, fallback to NTP system time.
bool getTimeInfo(tm& timeinfo) {
  if (rtc_ok) {
    DateTime now = rtc.now();
    timeinfo.tm_year = now.year() - 1900;
    timeinfo.tm_mon = now.month() - 1;
    timeinfo.tm_mday = now.day();
    timeinfo.tm_hour = now.hour();
    timeinfo.tm_min = now.minute();
    timeinfo.tm_sec = now.second();
    timeinfo.tm_isdst = 0;
    return true;
  }
  return getLocalTime(&timeinfo, 2000);
}

bool hasValidSystemTime() {
  time_t now = time(nullptr);
  return now > 1700000000;  // roughly after Nov 2023
}

bool waitForNtpSync(unsigned long timeoutMs) {
  unsigned long start = millis();
  tm timeinfo;
  while (millis() - start < timeoutMs) {
    if (getLocalTime(&timeinfo, 1000)) return true;
    delay(200);
  }
  return false;
}

// Non-blocking WiFi reconnect loop with retry interval guard.
void ensureWiFiConnected() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (millis() - lastWiFiReconnectMs < wifiReconnectIntervalMs) return;

  lastWiFiReconnectMs = millis();
  Serial.println("WiFi disconnected, attempting reconnect...");
  WiFi.disconnect();
  WiFi.reconnect();
}

// Keep system time valid for TLS/Firebase; retries NTP until synced.
void ensureTimeSynced() {
  if (hasValidSystemTime()) return;
  if (WiFi.status() != WL_CONNECTED) return;

  if (millis() - lastTimeSyncRetryMs >= timeSyncRetryIntervalMs) {
    lastTimeSyncRetryMs = millis();
    configTime(gmtOffset_sec, daylightOffset_sec, "pool.ntp.org", "time.nist.gov");
  }

  if (millis() - lastTimeSyncLogMs >= timeSyncLogIntervalMs) {
    lastTimeSyncLogMs = millis();
    Serial.println("System time not synced yet; TLS/Firebase may fail until NTP sync completes.");
  }
}

long loadCursor() {
  if (!SD.exists(SD_CURSOR_PATH)) return 0;
  File f = SD.open(SD_CURSOR_PATH, FILE_READ);
  if (!f) return 0;
  String s = f.readStringUntil('\n');
  f.close();
  return s.toInt();
}

void saveCursor(long pos) {
  File f = SD.open(SD_CURSOR_PATH, FILE_WRITE);
  if (!f) return;
  f.print(pos);
  f.close();
}

// Upload one CSV line from SD logs into Firebase /logs/{date}/{time} JSON.
bool uploadLogLine(const String& line) {
  int c1 = line.indexOf(',');
  int c2 = line.indexOf(',', c1 + 1);
  int c3 = line.indexOf(',', c2 + 1);
  int c4 = line.indexOf(',', c3 + 1);
  int c5 = line.indexOf(',', c4 + 1);
  if (c1 < 0 || c2 < 0 || c3 < 0 || c4 < 0) return true;

  String stamp = line.substring(0, c1);
  if (stamp.length() < 19) return true;

  String datePart = stamp.substring(0, 10);
  String timePart = stamp.substring(11, 19);
  timePart.replace(":", "-");

  float t = line.substring(c1 + 1, c2).toFloat();
  float h = line.substring(c2 + 1, c3).toFloat();
  float p = line.substring(c3 + 1, c4).toFloat();
  float w = 0;
  float tu = -1;
  if (c5 >= 0) {
    w = line.substring(c4 + 1, c5).toFloat();
    tu = line.substring(c5 + 1).toFloat();
  } else {
    w = line.substring(c4 + 1).toFloat();
  }

  String path = String("/logs/") + datePart + "/" + timePart;
  FirebaseJson json;
  json.set("temperature", t);
  json.set("humidity", h);
  json.set("ph", p);
  json.set("waterLevel", w);
  if (tu >= 0) {
    json.set("turbidity", tu);
  }

  return Firebase.RTDB.setJSON(&fbdo, path.c_str(), &json);
}

// Incremental SD->Firebase sync with cursor so uploads resume after reboot.
void syncSdToFirebase() {
  if (!allowDataBackup) return;
  if (!sd_ok || !cloudReady()) return;
  if (millis() - lastSyncMs < syncIntervalMs) return;

  lastSyncMs = millis();

  File file = SD.open(SD_LOG_PATH, FILE_READ);
  if (!file) return;

  long cursor = loadCursor();
  if (cursor < 0 || cursor > file.size()) cursor = 0;
  file.seek(cursor);

  int lines = 0;
  while (file.available() && lines < maxSyncLinesPerCycle) {
    String line = file.readStringUntil('\n');
    long afterPos = file.position();
    line.trim();
    if (line.length() == 0) {
      saveCursor(afterPos);
      continue;
    }

    if (uploadLogLine(line)) {
      saveCursor(afterPos);
      lines++;
    } else {
      break;
    }
  }
  file.close();
}

// Bring up Firebase only when network + valid time are available.
void ensureCloudBootstrap() {
  if (cloudBootstrapDone) return;
  if (WiFi.status() != WL_CONNECTED) return;
  if (!hasValidSystemTime()) return;
  if (millis() - lastCloudBootstrapMs < cloudBootstrapRetryMs) return;
  lastCloudBootstrapMs = millis();

  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;

  if (!Firebase.signUp(&config, &auth, "", "")) {
    Serial.print("Firebase signUp failed: ");
    Serial.println(config.signer.signupError.message.c_str());
    return;
  }

  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);
  cloudBootstrapDone = true;
  feederBusy = false;

  Firebase.RTDB.setBool(&fbdo, "/status/online", true);
  Firebase.RTDB.setBool(&fbdo, "/controls/light", lightOn);
  if (Firebase.RTDB.getBool(&fbdo, "/controls/allowDataBackup")) {
    allowDataBackup = fbdo.boolData();
  } else {
    Firebase.RTDB.setBool(&fbdo, "/controls/allowDataBackup", true);
    allowDataBackup = true;
  }
  Serial.print("Backup control on boot: SD logging ");
  Serial.println(allowDataBackup ? "ENABLED" : "DISABLED");

  if (Firebase.RTDB.getInt(&feedControlData, "/controls/FeedRequestId")) {
    lastProcessedFeedRequestId = feedControlData.intData();
  }
  setFeederStatus("Ready");

  if (!vc02BypassFirebaseLightControl) {
    if (!Firebase.RTDB.beginStream(&lightStream, "/controls/light")) {
      Serial.print("Firebase light stream error: ");
      Serial.println(lightStream.errorReason());
    }
    Firebase.RTDB.setStreamCallback(&lightStream, lightStreamCallback, lightStreamTimeoutCallback);
  } else {
    Serial.println("Light cloud sync bypassed: VC-02 local-only control is active");
  }

  if (!Firebase.RTDB.beginStream(&feedStream, "/controls/FeedRequestId")) {
    Serial.print("Firebase feed stream error: ");
    Serial.println(feedStream.errorReason());
  }
  Firebase.RTDB.setStreamCallback(&feedStream, feedStreamCallback, feedStreamTimeoutCallback);
  cleanupDeprecatedCloudFields();
}

void setup() {
  // Hardware + bus initialization.
  Serial.begin(115200);
  delay(1000);

  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  Wire.setClock(100000);
  scanI2CDevices();

  pinMode(RELAY_PIN, OUTPUT);
  pinMode(MANUAL_LIGHT_BUTTON_PIN, INPUT_PULLUP);
  setupFeederServo();
  setLight(false, false);
  VC02.begin(VC02_BAUD_RATE, SERIAL_8N1, VC02_RX_PIN, VC02_TX_PIN);
  Serial.println("VC-02 Ready (Light Relay on GPIO2)");

  lcd.init();
  lcd.backlight();
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("pH Sensor Ready");
  delay(2000);
  lcd.clear();

  if (initRtcWithRetry()) {
    rtc_ok = true;
    if (rtc.lostPower()) {
      Serial.println("RTC lost power, time needs to be set");
    }
    if (SET_RTC_TIME_ONCE) {
      rtc.adjust(DateTime(F(__DATE__), F(__TIME__)));
      Serial.println("RTC set from compile time");
    }
    Serial.println("✅ RTC OK");
  } else {
    Serial.println("❌ RTC FAIL");
    Serial.println("Expected DS3231 I2C address: 0x68");
    Serial.println("Check wiring: VCC, GND, SDA->GPIO21, SCL->GPIO22");
    lcd.setCursor(0, 1);
    lcd.print("RTC FAIL");
  }

  dht.begin();

  pinMode(TRIG, OUTPUT);
  pinMode(ECHO, INPUT);
  pinMode(TURBIDITY_PIN, INPUT);
  pinMode(TURBIDITY_LED_CLEAR_PIN, OUTPUT);
  pinMode(TURBIDITY_LED_CLOUDY_PIN, OUTPUT);
  pinMode(TURBIDITY_LED_DIRTY_PIN, OUTPUT);
  digitalWrite(TURBIDITY_LED_CLEAR_PIN, LOW);
  digitalWrite(TURBIDITY_LED_CLOUDY_PIN, LOW);
  digitalWrite(TURBIDITY_LED_DIRTY_PIN, LOW);
  analogSetPinAttenuation(PH_PIN, ADC_11db);
  analogSetPinAttenuation(TURBIDITY_PIN, ADC_11db);

  if (SD.begin(SD_CS)) {
    sd_ok = true;
    if (SKIP_BACKLOG_ON_BOOT && SD.exists(SD_LOG_PATH)) {
      File f = SD.open(SD_LOG_PATH, FILE_READ);
      if (f) {
        saveCursor(f.size());
        f.close();
      }
    }
  } else {
    lcd.setCursor(0, 2);
    lcd.print("SD FAIL");
  }

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long wifiWaitStart = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - wifiWaitStart < 8000) {
    delay(250);
  }

  if (WiFi.status() == WL_CONNECTED) {
    configTime(gmtOffset_sec, daylightOffset_sec, "pool.ntp.org", "time.nist.gov");
    if (waitForNtpSync(4000)) {
      Serial.println("NTP time sync OK");
    } else {
      Serial.println("NTP sync timeout: continuing in offline-safe mode");
    }
  } else {
    Serial.println("WiFi not connected at boot: continuing in offline-safe mode");
  }
  ensureCloudBootstrap();

  lcd.setCursor(0, 0);
  lcd.print("Temp");
  lcd.setCursor(0, 1);
  lcd.print("Hum");
  lcd.setCursor(0, 2);
  lcd.print("Water lvl");
  lcd.setCursor(0, 3);
  lcd.print("Turb");
}

void loop() {
  // Connectivity/controls tasks first so commands feel responsive.
  ensureWiFiConnected();
  ensureTimeSynced();
  ensureCloudBootstrap();
  cleanupDeprecatedCloudFields();

  checkManualLightButton();
  readVc02LightCommands();
  checkFirebaseLightControl();
  checkFirebaseFeedControl();
  checkFirebaseBackupControl();
  checkScheduledFeeding();

  // Sensor acquisition + status classification.
  humidity = dht.readHumidity();
  temperature = dht.readTemperature();
  if (isnan(humidity)) humidity = -1;
  if (isnan(temperature)) temperature = -1;

  phValue = readPhValue();
  updateTemperatureStatus();
  updateHumidityStatus();
  updatePhStatus();
  turbidityPercent = readTurbidityPercent();
  updateTurbidityStatus();

  digitalWrite(TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG, LOW);

  long duration = pulseIn(ECHO, HIGH, 30000);
  if (duration == 0) {
    distance = -1;
  } else {
    distance = duration * 0.034 / 2;
    distance = constrain(distance, 0.0f, 300.0f);
  }
  updateWaterLevelStatus();
  updateMainLcd();

  // ===== SD LOG (hourly) =====
  if (sd_ok && allowDataBackup) {
    tm timeinfo;
    if (getTimeInfo(timeinfo) && timeinfo.tm_min == 0) {
      char dateStr[11];
      char hourStr[3];
      strftime(dateStr, sizeof(dateStr), "%Y-%m-%d", &timeinfo);
      strftime(hourStr, sizeof(hourStr), "%H", &timeinfo);
      String hourKey = String(dateStr) + " " + String(hourStr);

      if (hourKey != lastLoggedHourKey) {
        lastLoggedHourKey = hourKey;
        File file = SD.open(SD_LOG_PATH, FILE_APPEND);
        if (file) {
          char stamp[20];
          strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &timeinfo);
          file.print(stamp);
          file.print(",");
          file.print(temperature);
          file.print(",");
          file.print(humidity);
          file.print(",");
          file.print(phValue);
          file.print(",");
          file.print(distance);
          file.print(",");
          file.println(turbidityPercent);
          file.close();
        }
      }
    }
  }

  // ===== FIREBASE REALTIME =====
  if (WiFi.status() == WL_CONNECTED && hasValidSystemTime() && Firebase.ready() && (millis() - lastFirebaseMs >= firebaseIntervalMs)) {
    lastFirebaseMs = millis();
    Firebase.RTDB.setFloat(&fbdo, "/sensors/temperature", temperature);
    Firebase.RTDB.setFloat(&fbdo, "/sensors/humidity", humidity);
    Firebase.RTDB.setFloat(&fbdo, "/sensors/ph", phValue);
    Firebase.RTDB.setFloat(&fbdo, "/sensors/waterLevel", distance);
    Firebase.RTDB.setInt(&fbdo, "/sensors/turbidity", turbidityPercent);
    Firebase.RTDB.setBool(&fbdo, "/status/online", true);

    tm timeinfo;
    int nowEpoch = 0;
    if (getTimeInfo(timeinfo)) {
      nowEpoch = (int)mktime(&timeinfo);
      Firebase.RTDB.setInt(&fbdo, "/status/lastSeen", nowEpoch);
    }
    Firebase.RTDB.setBool(&fbdo, "/status/feederBusy", feederBusy);
    Firebase.RTDB.setString(&fbdo, "/status/feederState", feederBusy ? "Feeding" : "Ready");
    if (lastFeedEpochSeconds > 0) {
      Firebase.RTDB.setInt(&fbdo, "/status/lastFeedAt", lastFeedEpochSeconds);
    }
    Firebase.RTDB.setBool(&fbdo, "/alerts/waterLevel/active", waterAlertActive);
    Firebase.RTDB.setString(&fbdo, "/alerts/waterLevel/status", waterLevelStatus);
    Firebase.RTDB.setString(&fbdo, "/alerts/waterLevel/message", waterAlertMessage);
    Firebase.RTDB.setFloat(&fbdo, "/alerts/waterLevel/distanceCm", distance);
    Firebase.RTDB.setBool(&fbdo, "/alerts/temperature/active", temperatureAlertActive);
    Firebase.RTDB.setString(&fbdo, "/alerts/temperature/status", temperatureStatus);
    Firebase.RTDB.setString(&fbdo, "/alerts/temperature/message", temperatureAlertMessage);
    Firebase.RTDB.setFloat(&fbdo, "/alerts/temperature/value", temperature);
    Firebase.RTDB.setString(&fbdo, "/alerts/temperature/unit", "C");
    Firebase.RTDB.setBool(&fbdo, "/alerts/humidity/active", humidityAlertActive);
    Firebase.RTDB.setString(&fbdo, "/alerts/humidity/status", humidityStatus);
    Firebase.RTDB.setString(&fbdo, "/alerts/humidity/message", humidityAlertMessage);
    Firebase.RTDB.setFloat(&fbdo, "/alerts/humidity/value", humidity);
    Firebase.RTDB.setString(&fbdo, "/alerts/humidity/unit", "%");
    Firebase.RTDB.setBool(&fbdo, "/alerts/ph/active", phAlertActive);
    Firebase.RTDB.setString(&fbdo, "/alerts/ph/status", phStatus);
    Firebase.RTDB.setString(&fbdo, "/alerts/ph/message", phAlertMessage);
    Firebase.RTDB.setFloat(&fbdo, "/alerts/ph/value", phValue);
    Firebase.RTDB.setString(&fbdo, "/alerts/ph/unit", "pH");
    Firebase.RTDB.setBool(&fbdo, "/alerts/turbidity/active", turbidityAlertActive);
    Firebase.RTDB.setString(&fbdo, "/alerts/turbidity/status", turbidityStatus);
    Firebase.RTDB.setString(&fbdo, "/alerts/turbidity/message", turbidityAlertMessage);
    Firebase.RTDB.setInt(&fbdo, "/alerts/turbidity/value", turbidityPercent);
    Firebase.RTDB.setString(&fbdo, "/alerts/turbidity/unit", "%");
    if (nowEpoch > 0) {
      Firebase.RTDB.setInt(&fbdo, "/alerts/waterLevel/updatedAt", nowEpoch);
      Firebase.RTDB.setInt(&fbdo, "/alerts/temperature/updatedAt", nowEpoch);
      Firebase.RTDB.setInt(&fbdo, "/alerts/humidity/updatedAt", nowEpoch);
      Firebase.RTDB.setInt(&fbdo, "/alerts/ph/updatedAt", nowEpoch);
      Firebase.RTDB.setInt(&fbdo, "/alerts/turbidity/updatedAt", nowEpoch);
    }
  }

  // ===== TREND DATA (every 5 minutes) =====
  if (cloudReady()) {
    tm timeinfo;
    if (getTimeInfo(timeinfo) && (timeinfo.tm_min % 5 == 0)) {
      char dateStr[11];
      char hourStr[3];
      char minStr[3];
      strftime(dateStr, sizeof(dateStr), "%Y-%m-%d", &timeinfo);
      strftime(hourStr, sizeof(hourStr), "%H", &timeinfo);
      strftime(minStr, sizeof(minStr), "%M", &timeinfo);
      String trendKey = String(dateStr) + "/" + String(hourStr) + "-" + String(minStr);

      if (trendKey != lastTrendKey) {
        lastTrendKey = trendKey;
        FirebaseJson json;
        json.set("temperature", temperature);
        json.set("humidity", humidity);
        json.set("ph", phValue);
        json.set("waterLevel", distance);
        json.set("turbidity", turbidityPercent);
        String trendPath = String("/trend/") + trendKey;
        Firebase.RTDB.setJSON(&fbdo, trendPath.c_str(), &json);
      }
    }
  }

  // ===== SERIAL MONITOR =====
  Serial.print("Temp: ");
  Serial.print(temperature);
  Serial.print(" (");
  Serial.print(temperatureStatus);
  Serial.print(")");
  Serial.print(" | Hum: ");
  Serial.print(humidity);
  Serial.print(" (");
  Serial.print(humidityStatus);
  Serial.print(")");
  Serial.print(" | pH: ");
  Serial.print(phValue);
  Serial.print(" (");
  Serial.print(phStatus);
  Serial.print(")");
  Serial.print(" V=");
  Serial.print(phVoltage, 2);
  Serial.print(" span=");
  Serial.print(phAdcSpan);
  Serial.print(" | Water: ");
  Serial.print(distance);
  Serial.print(" (");
  Serial.print(waterLevelStatus);
  Serial.print(")");
  Serial.print(" | Turbidity: ");
  Serial.print(turbidityPercent);
  Serial.print("% (");
  Serial.print(turbidityStatus);
  Serial.print(")");
  Serial.print(" raw=");
  Serial.print(turbidityRaw);
  Serial.print(" | Light: ");
  Serial.print(lightOn ? "ON" : "OFF");
  Serial.print(" | Feeder: ");
  Serial.print(feederBusy ? "BUSY" : "READY");
  if (rtc_ok) {
    tm timeinfo;
    if (getTimeInfo(timeinfo)) {
      char stamp[20];
      strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &timeinfo);
      Serial.print(" | RTC: ");
      Serial.print(stamp);
    } else {
      Serial.print(" | RTC: TIME ERR");
    }
  } else {
    Serial.print(" | RTC FAIL");
  }
  Serial.println();
  printSensorErrorAlerts();

  // Background backlog upload and loop pacing.
  syncSdToFirebase();
  delay(100);
}
