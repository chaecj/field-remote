/*
 * HW 리모컨 동글 펌웨어 v1.1 (M5Stack AtomS3 Lite)
 * ------------------------------------------------------------
 * 휴대폰(블루투스) → 동글 → TV·노트북·전자칠판
 *   - USB       : 꽂은 기기에 키보드 + 마우스 + 미디어키로 인식
 *   - 블루투스  : 전자칠판·태블릿 등의 블루투스 설정에서 키보드로 연결 (선 없이)
 *   - IR        : 내장 적외선 LED로 TV 리모컨 신호 송신
 *
 * Arduino IDE 설정
 *   ESP32 보드 패키지 : 3.3 이상
 *   보드              : M5AtomS3
 *   USB Mode          : USB-OTG (TinyUSB)      ← 반드시
 *   USB CDC On Boot   : Disabled
 *
 * 다시 업로드할 때: 리셋 버튼을 2초간 눌러 초록 LED가 켜지면(다운로드 모드) 업로드
 * 초기화: 윗면 버튼을 누른 채 USB를 꽂으면 이름과 블루투스 키보드 연결 기록이 지워짐
 */

#include <Arduino.h>
#include <Preferences.h>
#include <Update.h>

#if ARDUINO_USB_MODE
#error "도구(Tools) > USB Mode 를 'USB-OTG (TinyUSB)' 로 바꾼 뒤 다시 컴파일하세요."
#endif

#include "USB.h"
#include "USBHIDKeyboard.h"
#include "USBHIDMouse.h"
#include "USBHIDConsumerControl.h"
#include "USBHIDSystemControl.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLEHIDDevice.h>
#include <BLESecurity.h>

#if !defined(CONFIG_NIMBLE_ENABLED)
#error "ESP32 보드 패키지를 3.3 이상으로 업데이트하세요 (NimBLE 블루투스 스택 필요)."
#endif

extern "C" bool tud_mounted(void);
extern "C" bool tud_suspended(void);
extern "C" bool tud_remote_wakeup(void);
extern "C" int ble_store_clear(void);
#include "esp_system.h"

// ---------------- 진단: 어디서 멈췄는지 기록 (재시작해도 남는 메모리) ----------------
RTC_NOINIT_ATTR uint32_t rtcMagic, rtcStage, rtcCrashes;
static const uint32_t RTC_MAGIC = 0x48575243;
static inline void stage(uint8_t s) { rtcStage = s; }
String diag;        // 휴대폰 앱에 보여 줄 진단 문구
// 무선 업데이트 상태
bool otaOk = false;
uint32_t otaGot = 0, otaSize = 0;
volatile bool otaDropped = false;   // 대기열이 꽉 차 펌웨어 조각을 못 받은 적이 있음
String otaMsg = "";
bool noHid = false; // 안전 모드: 블루투스 키보드 기능 끔

// ---------------- 하드웨어 ----------------
static const int PIN_IR  = 4;   // 내장 IR LED
static const int PIN_LED = 35;  // RGB LED (WS2812)
static const int PIN_BTN = 41;  // 윗면 버튼

static const char *FW_VERSION = "1.6.0";

// ---------------- BLE UUID ----------------
#define SVC_UUID  "7e1a0001-5c2b-4f3a-9e7d-3c1b8a9f0a11"
#define CMD_UUID  "7e1a0002-5c2b-4f3a-9e7d-3c1b8a9f0a11"
#define INFO_UUID "7e1a0003-5c2b-4f3a-9e7d-3c1b8a9f0a11"

// ---------------- 명령 코드 ----------------
enum : uint8_t {
  C_KEY_TAP = 0x01,  // [key, mods]
  C_KEY_DOWN = 0x02, // [key]
  C_KEY_UP = 0x03,   // [key]
  C_KEY_ALLUP = 0x04,
  C_TEXT = 0x05,     // [ascii...]
  C_MOUSE_MOVE = 0x06, // [dx, dy, wheel, pan] (int8)
  C_MOUSE_CLICK = 0x07, // [buttons]
  C_MOUSE_DOWN = 0x08,
  C_MOUSE_UP = 0x09,
  C_CC_TAP = 0x0A,   // [usage lo, hi]
  C_CC_DOWN = 0x0B,
  C_CC_UP = 0x0C,
  C_SYSTEM = 0x0D,   // [1=off 2=standby 3=wake]
  C_ABS = 0x0E,      // [x u16 LE, y u16 LE, buttons] 화면 위치로 바로 (0~32767, 1.6.0부터)
  C_ABS_CLICK = 0x0F, // [x u16 LE, y u16 LE, (button)] 그 위치로 가서 클릭
  C_IR = 0x20,       // [khz, repeat, gapLo, gapHi, durations(u16 LE)...]
  C_IR_BEGIN = 0x30, // [khz, repeat, gapLo, gapHi]
  C_IR_DATA = 0x31,  // [durations...]
  C_IR_END = 0x32,
  C_IDENTIFY = 0x40,
  C_SET_NAME = 0x41, // [utf8...]
  C_BT_FORGET = 0x42, // 블루투스 키보드 연결 기록 지우기
  C_USB_COMPAT = 0x43, // [0=표준, 1=호환] 저장 후 다시 시작
  // 무선 펌웨어 업데이트 (휴대폰 앱 → 블루투스)
  C_OTA_BEGIN = 0x50,  // [크기 u32 LE]
  C_OTA_DATA = 0x51,   // [바이트...]
  C_OTA_END = 0x52,
  C_OTA_ABORT = 0x53,
  C_OTA_DATA2 = 0x54,  // [시작 위치 u32 LE][바이트...] 위치를 붙여 빠진 조각·중복 조각을 알아챔 (1.5.0부터)
};

// ---------------- 전역 ----------------
// USB 장치는 setup에서 만듦: 먼저 만든 쪽이 USB 인터페이스 종류를 정함
//  - 기본(표준 모드): 일반 HID → TV가 키보드·마우스·미디어키를 모두 인식 (TV 마우스 커서 필요)
//  - 호환 모드: 부트 키보드 → 오래된 TV·BIOS도 키보드로 인식하지만 마우스·미디어키는 안 될 수 있음
USBHIDKeyboard *kbd;
USBHIDMouse *mouse;
USBHIDConsumerControl *consumer;
USBHIDSystemControl *sysctl;
USBHID *hidBase;

// 화면 위치 마우스 (절대 좌표). 일반 마우스와 따로 쓰도록 보고서 번호 7
// 카메라로 TV를 보며 누른 곳을 바로 클릭할 때 씀. 윈도우·맥·안드로이드·구글 TV는 받음
static const uint8_t ABS_ID = 7;
static const uint8_t ABS_DESC[] = {
  0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ABS_ID,
  0x09, 0x01, 0xA1, 0x00,
  0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
  0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
  0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x00, 0x00, 0x26, 0xFF, 0x7F,
  0x36, 0x00, 0x00, 0x46, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
  0xC0, 0xC0,
};
class AbsPointer : public USBHIDDevice {
  USBHID hid;
public:
  AbsPointer() { static bool added = false; if (!added) { added = true; hid.addDevice(this, sizeof(ABS_DESC)); } }
  void begin() { hid.begin(); }
  uint16_t _onGetDescriptor(uint8_t *dst) override { memcpy(dst, ABS_DESC, sizeof(ABS_DESC)); return sizeof(ABS_DESC); }
  bool send(uint8_t buttons, uint16_t x, uint16_t y) {
    x = x > 32767 ? 32767 : x; y = y > 32767 ? 32767 : y;
    uint8_t r[5] = {buttons, (uint8_t)(x & 0xFF), (uint8_t)(x >> 8), (uint8_t)(y & 0xFF), (uint8_t)(y >> 8)};
    return hid.SendReport(ABS_ID, r, sizeof(r));
  }
};
AbsPointer *absPtr;
bool usbCompat = false;
Preferences prefs;

BLEServer *server = nullptr;
BLECharacteristic *infoChar = nullptr;
BLEHIDDevice *bleHid = nullptr;
BLECharacteristic *bleInKb = nullptr, *bleInCc = nullptr, *bleInMouse = nullptr;

static const uint8_t MAX_CONN = 3;   // 휴대폰 1 + 블루투스 키보드 호스트 최대 2
volatile int connCount = 0;
volatile int phoneHandle = -1;       // 휴대폰 앱 연결 핸들 (-1 = 없음)
volatile uint16_t mtuOf[16];
volatile uint16_t peerMtu = 23;
volatile bool infoDirty = false;
volatile bool needAdvertise = false;
String devName;
uint32_t btnCount = 0;

static const size_t MAX_MSG = 520;
struct Msg { uint16_t len; uint8_t d[MAX_MSG]; };
QueueHandle_t cmdQueue;

// IR 조립 버퍼
static const size_t MAX_IR = 600;
uint16_t irBuf[MAX_IR];
size_t irLen = 0;
uint8_t irKhz = 38, irRepeat = 0;
uint16_t irGap = 40;
rmt_data_t rmtBuf[MAX_IR / 2 + 2];

bool phoneConnected() { return phoneHandle >= 0; }
int btHostCount() { int n = connCount - (phoneConnected() ? 1 : 0); return n < 0 ? 0 : n; }

// ---------------- LED ----------------
uint8_t baseR = 0, baseG = 0, baseB = 18;
void led(uint8_t r, uint8_t g, uint8_t b) { rgbLedWrite(PIN_LED, r, g, b); }
void ledBase() { led(baseR, baseG, baseB); }
void ledFlash() { led(30, 30, 30); delay(25); ledBase(); }

// ---------------- 정보 ----------------
// USB 상태. TV가 쉬는 동안 USB를 절전시켜도 '연결됨'으로 봄 (누르면 깨움)
// 잠깐 끊겼다 붙는 건 3초 동안 기다렸다가 알림 (표시가 깜빡이지 않게)
bool usbOn = false, usbSleep = false;
void usbPoll() {
  static uint32_t offSince = 0;
  if (tud_mounted()) { usbOn = true; offSince = 0; }
  else if (usbOn) {
    if (!offSince) offSince = millis() | 1;
    else if (millis() - offSince > 3000) usbOn = false;
  }
  usbSleep = tud_mounted() && tud_suspended();
}
// 절전 중이면 TV를 먼저 깨운 뒤 보냄 (안 깨우면 누른 키가 버려짐)
void usbWake() {
  if (!tud_mounted() || !tud_suspended()) return;
  if (!tud_remote_wakeup()) return;          // TV가 깨우기를 허락하지 않은 경우
  uint32_t t0 = millis();
  while (tud_suspended() && millis() - t0 < 300) delay(5);
  delay(20);
}

void publishInfo() {
  if (!infoChar) return;
  String j = "{\"n\":\"" + devName + "\",\"v\":\"" + FW_VERSION + "\",\"usb\":" +
             (usbOn ? "true" : "false") + ",\"us\":" + String(usbSleep ? 1 : 0) + ",\"bt\":" + String(btHostCount()) +
             ",\"mtu\":" + String(peerMtu) + ",\"btn\":" + String(btnCount) +
             ",\"uc\":" + String(usbCompat ? 1 : 0) + ",\"o\":\"" + otaMsg + "\",\"d\":\"" + diag + "\"}";
  infoChar->setValue(j.c_str());
  if (phoneConnected()) infoChar->notify();
}

void markPhone(uint16_t handle) {
  if (phoneHandle != (int)handle) {
    phoneHandle = handle;
    peerMtu = mtuOf[handle & 15] ? mtuOf[handle & 15] : 23;
    infoDirty = true;
  }
}

// ---------------- BLE 콜백 ----------------
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer *s, ble_gap_conn_desc *desc) override {
    connCount++;
    mtuOf[desc->conn_handle & 15] = 23;
    s->requestConnParams(desc->conn_handle, 6, 12, 0, 400);  // 7.5~15ms 간격
    if (connCount < MAX_CONN) needAdvertise = true;          // 다른 기기도 연결할 수 있게
    infoDirty = true;
  }
  void onDisconnect(BLEServer *s, ble_gap_conn_desc *desc) override {
    if (connCount > 0) connCount--;
    if ((int)desc->conn_handle == phoneHandle) { phoneHandle = -1; peerMtu = 23; }
    needAdvertise = true;
    infoDirty = true;
  }
  void onMtuChanged(BLEServer *s, ble_gap_conn_desc *desc, uint16_t mtu) override {
    mtuOf[desc->conn_handle & 15] = mtu;
    if ((int)desc->conn_handle == phoneHandle) { peerMtu = mtu; infoDirty = true; }
  }
};

class CmdCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c, ble_gap_conn_desc *desc) override {
    markPhone(desc->conn_handle);
    static Msg m;
    String v = c->getValue();
    size_t n = v.length();
    if (n == 0 || n > MAX_MSG) return;
    m.len = n;
    memcpy(m.d, v.c_str(), n);
    // 펌웨어 조각은 버리면 안 되므로 자리가 날 때까지 기다림 (휴대폰 쪽 전송 속도도 자연히 맞춰짐)
    bool ota = m.d[0] == 0x51 || m.d[0] == 0x54;
    if (xQueueSend(cmdQueue, &m, ota ? pdMS_TO_TICKS(5000) : pdMS_TO_TICKS(20)) != pdTRUE && ota) otaDropped = true;   // 조용히 버리지 않고 표시
  }
};

class InfoCB : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic *c, ble_gap_conn_desc *desc) override { markPhone(desc->conn_handle); }
  void onSubscribe(BLECharacteristic *c, ble_gap_conn_desc *desc, uint16_t sub) override {
    if (sub) markPhone(desc->conn_handle);
  }
};

// ---------------- 블루투스 키보드(HID) ----------------
static const uint8_t HID_REPORT_MAP[] = {
  // 키보드 (ID 1)
  0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
  0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
  0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
  0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
  0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
  0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0xA4, 0x05, 0x07, 0x19, 0x00, 0x29, 0xA4, 0x81, 0x00,
  0xC0,
  // 미디어키 (ID 2)
  0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
  0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
  0xC0,
  // 마우스 (ID 3)
  0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x03, 0x09, 0x01, 0xA1, 0x00,
  0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
  0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
  0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
  0xC0, 0xC0,
};

uint8_t btMods = 0, btKeys[6] = {0};
uint8_t btMouseBtn = 0;

void btKbSend() {
  if (!bleInKb || btHostCount() == 0) return;
  uint8_t r[8] = {btMods, 0, btKeys[0], btKeys[1], btKeys[2], btKeys[3], btKeys[4], btKeys[5]};
  bleInKb->setValue(r, 8);
  bleInKb->notify();
}
void btCcSend(uint16_t usage) {
  if (!bleInCc || btHostCount() == 0) return;
  uint8_t r[2] = {(uint8_t)(usage & 0xFF), (uint8_t)(usage >> 8)};
  bleInCc->setValue(r, 2);
  bleInCc->notify();
}
void btMouseSend(int8_t x, int8_t y, int8_t w) {
  if (!bleInMouse || btHostCount() == 0) return;
  uint8_t r[4] = {btMouseBtn, (uint8_t)x, (uint8_t)y, (uint8_t)w};
  bleInMouse->setValue(r, 4);
  bleInMouse->notify();
}

// ---------------- 키 입력 (USB + 블루투스 동시) ----------------
void kPress(uint8_t k) {
  kbd->pressRaw(k);
  if (k >= 0xE0 && k < 0xE8) btMods |= 1 << (k - 0xE0);
  else {
    for (int i = 0; i < 6; i++) if (btKeys[i] == k) { btKbSend(); return; }
    for (int i = 0; i < 6; i++) if (btKeys[i] == 0) { btKeys[i] = k; break; }
  }
  btKbSend();
}
void kRelease(uint8_t k) {
  kbd->releaseRaw(k);
  if (k >= 0xE0 && k < 0xE8) btMods &= ~(1 << (k - 0xE0));
  else for (int i = 0; i < 6; i++) if (btKeys[i] == k) btKeys[i] = 0;
  btKbSend();
}
void kReleaseAll() {
  kbd->releaseAll();
  btMods = 0; memset(btKeys, 0, 6);
  btKbSend();
}
void keyTap(uint8_t key, uint8_t mods) {
  for (int i = 0; i < 8; i++) if (mods & (1 << i)) kPress(0xE0 + i);
  kPress(key);
  delay(30);
  kReleaseAll();
}

// 영문 키보드 기준 ASCII → HID 키코드
bool asciiToHid(char c, uint8_t &key, bool &shift) {
  shift = false;
  if (c >= 'a' && c <= 'z') { key = 0x04 + (c - 'a'); return true; }
  if (c >= 'A' && c <= 'Z') { key = 0x04 + (c - 'A'); shift = true; return true; }
  if (c >= '1' && c <= '9') { key = 0x1E + (c - '1'); return true; }
  if (c == '0') { key = 0x27; return true; }
  if (c == ' ') { key = 0x2C; return true; }
  if (c == '\n') { key = 0x28; return true; }
  static const char *plain = "-=[]\\;'`,./";
  static const uint8_t plainK[] = {0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
  static const char *shifted = "_+{}|:\"~<>?";
  static const char *numShift = "!@#$%^&*()";
  for (int i = 0; plain[i]; i++) if (c == plain[i]) { key = plainK[i]; return true; }
  for (int i = 0; shifted[i]; i++) if (c == shifted[i]) { key = plainK[i]; shift = true; return true; }
  for (int i = 0; numShift[i]; i++) if (c == numShift[i]) { key = (i == 9) ? 0x27 : 0x1E + i; shift = true; return true; }
  return false;
}
void typeChar(char c) {
  uint8_t key; bool shift;
  if (!asciiToHid(c, key, shift)) return;
  keyTap(key, shift ? 0x02 : 0);
}

void ccTap(uint16_t u) {
  consumer->press(u); btCcSend(u);
  delay(30);
  consumer->release(); btCcSend(0);
}
void mouseMove(int8_t x, int8_t y, int8_t w, int8_t pan) {
  mouse->move(x, y, w, pan);
  btMouseSend(x, y, w);
}
void mousePress(uint8_t b) { mouse->press(b); btMouseBtn |= b; btMouseSend(0, 0, 0); }
void mouseRelease(uint8_t b) { mouse->release(b); btMouseBtn &= ~b; btMouseSend(0, 0, 0); }
void mouseClick(uint8_t b) { mousePress(b); delay(20); mouseRelease(b); }

// ---------------- IR 송신 ----------------
void irInit() {
  rmtInit(PIN_IR, RMT_TX_MODE, RMT_MEM_NUM_BLOCKS_2, 1000000);  // 1µs 해상도
}

void irSend() {
  if (irLen == 0) return;
  rmtSetCarrier(PIN_IR, true, false, (uint32_t)irKhz * 1000, 0.33);
  size_t n = 0;
  for (size_t i = 0; i < irLen; i += 2) {
    uint16_t mark = irBuf[i];
    uint16_t space = (i + 1 < irLen) ? irBuf[i + 1] : 100;
    if (mark > 32767) mark = 32767;
    if (space > 32767) space = 32767;
    if (space == 0) space = 100;
    rmtBuf[n].level0 = 1; rmtBuf[n].duration0 = mark;
    rmtBuf[n].level1 = 0; rmtBuf[n].duration1 = space;
    n++;
  }
  for (int r = 0; r <= irRepeat; r++) {
    rmtWrite(PIN_IR, rmtBuf, n, 500);
    if (r < irRepeat) delay(irGap);
  }
}

void irAppend(const uint8_t *p, size_t bytes) {
  for (size_t i = 0; i + 1 < bytes && irLen < MAX_IR; i += 2) {
    irBuf[irLen++] = (uint16_t)p[i] | ((uint16_t)p[i + 1] << 8);
  }
}

void irHeader(const uint8_t *p) {
  irKhz = p[0] ? p[0] : 38;
  irRepeat = p[1] > 5 ? 5 : p[1];
  irGap = (uint16_t)p[2] | ((uint16_t)p[3] << 8);
  if (irGap > 200) irGap = 200;
  irLen = 0;
}

// ---------------- 이름 ----------------
String defaultName() {
  uint64_t mac = ESP.getEfuseMac();
  char buf[12];
  snprintf(buf, sizeof(buf), "HW-%02X%02X", (uint8_t)(mac >> 32), (uint8_t)(mac >> 40));
  return String(buf);
}

// ---------------- 명령 처리 ----------------
// 무선 업데이트 실패: 받던 것을 버리고 원래 펌웨어 그대로 둠
void otaFail(const String &msg) {
  otaOk = false; otaMsg = msg; infoDirty = true;
  if (Update.isRunning()) Update.abort();
  ledBase();
}

void handle(const Msg &m) {
  const uint8_t *d = m.d;
  size_t n = m.len;
  uint8_t c = d[0];
  const uint8_t *p = d + 1;
  size_t pn = n - 1;

  switch (c) {
    case C_KEY_TAP: case C_KEY_DOWN: case C_TEXT: case C_MOUSE_MOVE: case C_MOUSE_CLICK:
    case C_MOUSE_DOWN: case C_CC_TAP: case C_CC_DOWN: case C_SYSTEM: case C_ABS: case C_ABS_CLICK:
      usbWake(); break;
  }

  switch (c) {
    case C_KEY_TAP: if (pn >= 1) keyTap(p[0], pn >= 2 ? p[1] : 0); break;
    case C_KEY_DOWN: if (pn >= 1) kPress(p[0]); break;
    case C_KEY_UP: if (pn >= 1) kRelease(p[0]); break;
    case C_KEY_ALLUP:
      kReleaseAll(); consumer->release(); btCcSend(0); mouseRelease(0x1F);
      break;
    case C_TEXT:
      for (size_t i = 0; i < pn; i++) { typeChar((char)p[i]); delay(5); }
      break;
    case C_MOUSE_MOVE:
      if (pn >= 2) mouseMove((int8_t)p[0], (int8_t)p[1], pn >= 3 ? (int8_t)p[2] : 0, pn >= 4 ? (int8_t)p[3] : 0);
      break;
    case C_MOUSE_CLICK: mouseClick(pn ? p[0] : MOUSE_LEFT); break;
    case C_MOUSE_DOWN: mousePress(pn ? p[0] : MOUSE_LEFT); break;
    case C_MOUSE_UP: mouseRelease(pn ? p[0] : MOUSE_LEFT); break;
    case C_CC_TAP: if (pn >= 2) ccTap((uint16_t)p[0] | ((uint16_t)p[1] << 8)); break;
    case C_CC_DOWN:
      if (pn >= 2) { uint16_t u = (uint16_t)p[0] | ((uint16_t)p[1] << 8); consumer->press(u); btCcSend(u); }
      break;
    case C_CC_UP: consumer->release(); btCcSend(0); break;
    case C_ABS:
      if (pn >= 4) absPtr->send(pn >= 5 ? p[4] : 0, (uint16_t)p[0] | ((uint16_t)p[1] << 8), (uint16_t)p[2] | ((uint16_t)p[3] << 8));
      break;
    case C_ABS_CLICK:
      if (pn >= 4) {
        uint16_t x = (uint16_t)p[0] | ((uint16_t)p[1] << 8), y = (uint16_t)p[2] | ((uint16_t)p[3] << 8);
        uint8_t b = pn >= 5 && p[4] ? p[4] : 1;
        absPtr->send(0, x, y); delay(25);      // 먼저 그 위치로
        absPtr->send(b, x, y); delay(40);      // 누르고
        absPtr->send(0, x, y);                 // 떼기
      }
      break;
    case C_SYSTEM:
      if (pn >= 1) { sysctl->press(p[0]); delay(30); sysctl->release(); }
      break;
    case C_IR:
      if (pn >= 4) { irHeader(p); irAppend(p + 4, pn - 4); irSend(); }
      break;
    case C_IR_BEGIN: if (pn >= 4) irHeader(p); break;
    case C_IR_DATA: irAppend(p, pn); break;
    case C_IR_END: irSend(); break;
    case C_IDENTIFY:
      for (int i = 0; i < 6; i++) { led(40, 18, 0); delay(150); led(0, 0, 0); delay(150); }
      break;
    case C_SET_NAME: {
      String nm;
      for (size_t i = 0; i < pn && i < 20; i++) nm += (char)p[i];
      nm.trim();
      prefs.putString("name", nm.length() ? nm : defaultName());
      delay(100);
      ESP.restart();
      break;
    }
    case C_OTA_BEGIN: {
      if (pn < 4) break;
      uint32_t sz = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
      if (Update.isRunning()) Update.abort();
      otaOk = Update.begin(sz);
      otaGot = 0; otaSize = sz; otaDropped = false;
      otaMsg = otaOk ? "receiving" : String("err:begin ") + Update.errorString();
      led(30, 0, 30);
      infoDirty = true;
      return;
    }
    case C_OTA_DATA:
    case C_OTA_DATA2: {
      if (!otaOk || !Update.isRunning()) return;
      if (otaDropped) { otaFail("err:busy 조각을 놓쳤어요"); return; }
      const uint8_t *data = p; size_t dn = pn;
      if (c == C_OTA_DATA2) {
        if (pn < 4) return;
        uint32_t off = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        data = p + 4; dn = pn - 4;
        if (off + dn <= otaGot) return;                       // 이미 받은 조각을 다시 보낸 것 → 무시
        if (off != otaGot) { otaFail(String("err:gap ") + off + "/" + otaGot); return; }   // 중간이 빠짐
      }
      if (Update.write((uint8_t *)data, dn) != dn) { otaFail(String("err:write ") + Update.errorString()); return; }
      otaGot += dn;
      if ((otaGot / 1024) % 16 == 0) led((otaGot / 1024) % 32 ? 30 : 10, 0, 30);
      return;
    }
    case C_OTA_END:
      if (otaOk && (otaDropped || otaGot != otaSize)) {        // 덜 받았으면 설치하지 않음
        otaFail(otaDropped ? String("err:busy 조각을 놓쳤어요") : String("err:size ") + otaGot + "/" + otaSize);
        return;
      }
      if (otaOk && Update.end()) {
        otaMsg = "ok";
        publishInfo();
        for (int i = 0; i < 3; i++) { led(0, 30, 0); delay(150); led(0, 0, 0); delay(150); }
        delay(300);
        ESP.restart();
      } else {
        otaMsg = String("err:end ") + Update.errorString();
        if (Update.isRunning()) Update.abort();
        otaOk = false; infoDirty = true; ledBase();
      }
      return;
    case C_OTA_ABORT:
      if (Update.isRunning()) Update.abort();
      otaOk = false; otaMsg = ""; infoDirty = true; ledBase();
      return;
    case C_USB_COMPAT:
      prefs.putBool("usbcompat", pn >= 1 && p[0] == 1);
      delay(100);
      ESP.restart();
      break;
    case C_BT_FORGET:
      ble_store_clear();
      delay(100);
      ESP.restart();
      break;
    default: return;
  }
  if (c != C_MOUSE_MOVE && c != C_IDENTIFY) ledFlash();
}

// ---------------- setup / loop ----------------
// 재시작 원인을 LED로 알림: 색 = 원인, 깜빡임 횟수 = 멈춘 단계
void blinkCode(uint8_t r, uint8_t g, uint8_t b, int count) {
  led(0, 0, 0); delay(600);
  for (int i = 0; i < count; i++) { led(r, g, b); delay(350); led(0, 0, 0); delay(300); }
  delay(600);
}

void setup() {
  pinMode(PIN_BTN, INPUT_PULLUP);
  for (int i = 0; i < 16; i++) mtuOf[i] = 23;

  // ---- 지난번에 비정상 종료됐는지 확인 ----
  esp_reset_reason_t rr = esp_reset_reason();
  if (rtcMagic != RTC_MAGIC) { rtcMagic = RTC_MAGIC; rtcStage = 0; rtcCrashes = 0; }
  bool panic = rr == ESP_RST_PANIC;
  bool wdt = rr == ESP_RST_INT_WDT || rr == ESP_RST_TASK_WDT || rr == ESP_RST_WDT;
  bool brown = rr == ESP_RST_BROWNOUT;
  bool crashed = panic || wdt || brown;
  uint8_t lastStage = rtcStage;
  rtcCrashes = crashed ? rtcCrashes + 1 : 0;
  stage(1);

  prefs.begin("tvr", false);
  devName = prefs.getString("name", "");
  if (devName.length() == 0) devName = defaultName();

  // 버튼을 누른 채 전원을 넣으면 초기화 (이름 + 블루투스 키보드 연결 기록 + 안전 모드)
  bool resetAll = digitalRead(PIN_BTN) == LOW;
  if (resetAll) {
    prefs.remove("name");
    prefs.remove("nohid");
    prefs.remove("diag");
    devName = defaultName();
  }

  if (crashed) {
    const char *why = panic ? "오류" : wdt ? "응답 없음" : "전원 부족";
    diag = String("재시작 원인 ") + why + ", 멈춘 단계 " + lastStage + ", 연속 " + rtcCrashes + "회";
    // 블루투스 키보드 단계(5) 이후에서 두 번 이상 멈추면 그 기능을 끄고 시작
    if (rtcCrashes >= 2 && lastStage >= 5) prefs.putBool("nohid", true);
    prefs.putString("diag", diag);
    if (panic) blinkCode(40, 0, 0, lastStage);
    else if (wdt) blinkCode(40, 30, 0, lastStage);
    else blinkCode(30, 30, 30, lastStage);
  } else {
    diag = prefs.getString("diag", "");
  }
  noHid = prefs.getBool("nohid", false);
  if (noHid) diag += (diag.length() ? " · " : "") + String("안전 모드(블루투스 키보드 꺼짐)");

  led(0, 0, 18);
  cmdQueue = xQueueCreate(24, sizeof(Msg));
  irInit();

  // USB: 키보드 + 마우스 + 미디어키 + 시스템키
  stage(2);
  usbCompat = prefs.getBool("usbcompat", false);
  if (!usbCompat) hidBase = new USBHID(HID_ITF_PROTOCOL_NONE);   // 인터페이스를 일반 HID로 고정
  kbd = new USBHIDKeyboard();
  mouse = new USBHIDMouse();
  consumer = new USBHIDConsumerControl();
  sysctl = new USBHIDSystemControl();
  absPtr = new AbsPointer();   // 모든 HID 장치는 begin 전에 등록해야 설명서(descriptor)에 들어감
  USB.productName("HW Remote");
  USB.manufacturerName("CHAEUM");
  USB.usbAttributes(TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP);   // 버스 전원 + 절전 중 깨우기 지원 (기본값은 '자체 전원'이라 틀림)
  USB.usbPower(100);                                         // 일반 키보드와 같은 100mA 요청 (500mA 요청은 일부 TV가 거부)
  kbd->begin();
  mouse->begin();
  consumer->begin();
  sysctl->begin();
  absPtr->begin();
  USB.begin();

  // BLE
  stage(3);
  BLEDevice::init(devName.c_str());
  BLEDevice::setMTU(517);
  if (resetAll) {
    ble_store_clear();
    for (int i = 0; i < 3; i++) { led(40, 0, 0); delay(200); led(0, 0, 0); delay(200); }
    led(0, 0, 18);
  }

  if (!noHid) {
    // 블루투스 키보드는 'Just Works' 페어링 (비밀번호 없이)
    BLESecurity *sec = new BLESecurity();
    sec->setAuthenticationMode(true, false, true);   // 본딩 O, MITM X, 보안 연결 O
    sec->setCapability(ESP_IO_CAP_NONE);
  }

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCB());

  // 1) 휴대폰 앱 전용 서비스
  stage(4);
  BLEService *svc = server->createService(SVC_UUID);
  BLECharacteristic *cmd = svc->createCharacteristic(
      CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CmdCB());
  infoChar = svc->createCharacteristic(
      INFO_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  infoChar->setCallbacks(new InfoCB());
  svc->start();

  // 2) 블루투스 키보드·마우스 (HID)
  stage(5);
  if (!noHid) {
    bleHid = new BLEHIDDevice(server);
    bleInKb = bleHid->inputReport(1);
    bleHid->outputReport(1);  // 키보드 LED (호스트 호환용)
    bleInCc = bleHid->inputReport(2);
    bleInMouse = bleHid->inputReport(3);
    bleHid->manufacturer("CHAEUM");
    bleHid->pnp(0x02, 0x303A, 0x4001, 0x0110);
    bleHid->hidInfo(0x00, 0x02);
    bleHid->reportMap((uint8_t *)HID_REPORT_MAP, sizeof(HID_REPORT_MAP));
    bleHid->startServices();
    bleHid->setBatteryLevel(100);   // 광고 시작 전이라 값만 저장됨
  }

  publishInfo();

  stage(6);
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  if (!noHid) adv->setAppearance(0x03C1);  // 키보드
  adv->addServiceUUID(SVC_UUID);
  if (!noHid) adv->addServiceUUID(bleHid->hidService()->getUUID());
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  stage(7);
}

void loop() {
  static bool lastUsb = false;
  static int lastHosts = -1;
  static uint32_t lastBtn = 0, lastPoll = 0;
  static bool btnPrev = HIGH;

  Msg m;
  while (xQueueReceive(cmdQueue, &m, 0) == pdTRUE) handle(m);

  uint32_t now = millis();

  // 버튼: 휴대폰에 '이 동글' 표시
  bool b = digitalRead(PIN_BTN);
  if (btnPrev == HIGH && b == LOW && now - lastBtn > 300) {
    lastBtn = now;
    btnCount++;
    infoDirty = true;
    led(40, 18, 0); delay(120); ledBase();
  }
  btnPrev = b;

  if (needAdvertise) {
    needAdvertise = false;
    delay(50);
    if (connCount < MAX_CONN) BLEDevice::startAdvertising();
  }

  if (now - lastPoll > 250) {
    lastPoll = now;
    stage(phoneConnected() ? 8 : 7);
    static bool diagCleared = false;   // 2분 동안 문제없이 돌면 저장된 진단 기록은 지움 (이번 실행 동안은 계속 보여 줌)
    if (!diagCleared && now > 120000) { diagCleared = true; prefs.remove("diag"); }
    usbPoll();
    bool usb = usbOn;
    static bool lastSleep = false;
    if (usbSleep != lastSleep) { lastSleep = usbSleep; infoDirty = true; }
    int hosts = btHostCount();
    if (usb != lastUsb) { lastUsb = usb; infoDirty = true; }
    if (hosts != lastHosts) { lastHosts = hosts; infoDirty = true; }
    // LED: 파랑=대기, 초록=휴대폰 연결, 청록=휴대폰 + 기기 인식(USB 또는 블루투스 키보드)
    bool linked = usb || hosts > 0;
    if (phoneConnected() && linked) { baseR = 0; baseG = 18; baseB = 10; }
    else if (phoneConnected()) { baseR = 0; baseG = 20; baseB = 0; }
    else { baseR = 0; baseG = 0; baseB = 18; }
    ledBase();
  }

  if (infoDirty) { infoDirty = false; publishInfo(); }

  delay(2);
}
