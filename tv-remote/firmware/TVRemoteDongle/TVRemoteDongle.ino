/*
 * 현장 리모컨 동글 펌웨어 v1.1 (M5Stack AtomS3 Lite)
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
extern "C" int ble_store_clear(void);

// ---------------- 하드웨어 ----------------
static const int PIN_IR  = 4;   // 내장 IR LED
static const int PIN_LED = 35;  // RGB LED (WS2812)
static const int PIN_BTN = 41;  // 윗면 버튼

static const char *FW_VERSION = "1.1.0";

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
  C_IR = 0x20,       // [khz, repeat, gapLo, gapHi, durations(u16 LE)...]
  C_IR_BEGIN = 0x30, // [khz, repeat, gapLo, gapHi]
  C_IR_DATA = 0x31,  // [durations...]
  C_IR_END = 0x32,
  C_IDENTIFY = 0x40,
  C_SET_NAME = 0x41, // [utf8...]
  C_BT_FORGET = 0x42, // 블루투스 키보드 연결 기록 지우기
};

// ---------------- 전역 ----------------
USBHIDKeyboard Keyboard;
USBHIDMouse Mouse;
USBHIDConsumerControl Consumer;
USBHIDSystemControl SystemCtl;
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
bool usbReady() { return tud_mounted() && !tud_suspended(); }

void publishInfo() {
  if (!infoChar) return;
  String j = "{\"n\":\"" + devName + "\",\"v\":\"" + FW_VERSION + "\",\"usb\":" +
             (usbReady() ? "true" : "false") + ",\"bt\":" + String(btHostCount()) +
             ",\"mtu\":" + String(peerMtu) + ",\"btn\":" + String(btnCount) + "}";
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
    xQueueSend(cmdQueue, &m, pdMS_TO_TICKS(20));
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
  Keyboard.pressRaw(k);
  if (k >= 0xE0 && k < 0xE8) btMods |= 1 << (k - 0xE0);
  else {
    for (int i = 0; i < 6; i++) if (btKeys[i] == k) { btKbSend(); return; }
    for (int i = 0; i < 6; i++) if (btKeys[i] == 0) { btKeys[i] = k; break; }
  }
  btKbSend();
}
void kRelease(uint8_t k) {
  Keyboard.releaseRaw(k);
  if (k >= 0xE0 && k < 0xE8) btMods &= ~(1 << (k - 0xE0));
  else for (int i = 0; i < 6; i++) if (btKeys[i] == k) btKeys[i] = 0;
  btKbSend();
}
void kReleaseAll() {
  Keyboard.releaseAll();
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
  Consumer.press(u); btCcSend(u);
  delay(30);
  Consumer.release(); btCcSend(0);
}
void mouseMove(int8_t x, int8_t y, int8_t w, int8_t pan) {
  Mouse.move(x, y, w, pan);
  btMouseSend(x, y, w);
}
void mousePress(uint8_t b) { Mouse.press(b); btMouseBtn |= b; btMouseSend(0, 0, 0); }
void mouseRelease(uint8_t b) { Mouse.release(b); btMouseBtn &= ~b; btMouseSend(0, 0, 0); }
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
  snprintf(buf, sizeof(buf), "TVR-%02X%02X", (uint8_t)(mac >> 32), (uint8_t)(mac >> 40));
  return String(buf);
}

// ---------------- 명령 처리 ----------------
void handle(const Msg &m) {
  const uint8_t *d = m.d;
  size_t n = m.len;
  uint8_t c = d[0];
  const uint8_t *p = d + 1;
  size_t pn = n - 1;

  switch (c) {
    case C_KEY_TAP: if (pn >= 1) keyTap(p[0], pn >= 2 ? p[1] : 0); break;
    case C_KEY_DOWN: if (pn >= 1) kPress(p[0]); break;
    case C_KEY_UP: if (pn >= 1) kRelease(p[0]); break;
    case C_KEY_ALLUP:
      kReleaseAll(); Consumer.release(); btCcSend(0); mouseRelease(0x1F);
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
      if (pn >= 2) { uint16_t u = (uint16_t)p[0] | ((uint16_t)p[1] << 8); Consumer.press(u); btCcSend(u); }
      break;
    case C_CC_UP: Consumer.release(); btCcSend(0); break;
    case C_SYSTEM:
      if (pn >= 1) { SystemCtl.press(p[0]); delay(30); SystemCtl.release(); }
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
void setup() {
  pinMode(PIN_BTN, INPUT_PULLUP);
  led(0, 0, 18);
  for (int i = 0; i < 16; i++) mtuOf[i] = 23;

  prefs.begin("tvr", false);
  devName = prefs.getString("name", "");
  if (devName.length() == 0) devName = defaultName();

  // 버튼을 누른 채 전원을 넣으면 초기화 (이름 + 블루투스 키보드 연결 기록)
  bool resetAll = digitalRead(PIN_BTN) == LOW;
  if (resetAll) {
    prefs.remove("name");
    devName = defaultName();
  }

  cmdQueue = xQueueCreate(24, sizeof(Msg));
  irInit();

  // USB: 키보드 + 마우스 + 미디어키 + 시스템키
  USB.productName("Field TV Remote");
  USB.manufacturerName("CHAEUM");
  Keyboard.begin();
  Mouse.begin();
  Consumer.begin();
  SystemCtl.begin();
  USB.begin();

  // BLE
  BLEDevice::init(devName.c_str());
  BLEDevice::setMTU(517);
  if (resetAll) {
    ble_store_clear();
    for (int i = 0; i < 3; i++) { led(40, 0, 0); delay(200); led(0, 0, 0); delay(200); }
  }

  // 블루투스 키보드는 'Just Works' 페어링 (비밀번호 없이)
  BLESecurity *sec = new BLESecurity();
  sec->setAuthenticationMode(true, false, true);   // 본딩 O, MITM X, 보안 연결 O
  sec->setCapability(ESP_IO_CAP_NONE);

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCB());

  // 1) 휴대폰 앱 전용 서비스
  BLEService *svc = server->createService(SVC_UUID);
  BLECharacteristic *cmd = svc->createCharacteristic(
      CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CmdCB());
  infoChar = svc->createCharacteristic(
      INFO_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  infoChar->addDescriptor(new BLE2902());
  infoChar->setCallbacks(new InfoCB());
  svc->start();

  // 2) 블루투스 키보드·마우스 (HID)
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
  bleHid->setBatteryLevel(100);

  publishInfo();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->setAppearance(0x03C1);  // 키보드
  adv->addServiceUUID(SVC_UUID);
  adv->addServiceUUID(bleHid->hidService()->getUUID());
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
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
    bool usb = usbReady();
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
