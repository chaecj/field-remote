// =====================================================================
// PC로 파일 보내기: 동글이 PC에 'HW 리모컨' USB 드라이브(읽기 전용)로 보임
// ---------------------------------------------------------------------
// - 휴대폰 앱이 블루투스로 파일을 보내면 플래시의 hwfiles 파티션에 그대로 저장
// - PC가 읽을 때마다 FAT16 디스크를 즉석에서 만들어 보여 줌 (부트섹터·FAT·폴더는 계산, 파일 내용은 플래시)
// - 파일은 4KB 단위로 이어서 저장 (지우기는 '모두 비우기'만)
// - 파일 목록은 NVS에 저장 → 동글을 뽑았다 꽂아도 남아 있음
// =====================================================================
#pragma once
#include <Arduino.h>
#include <Preferences.h>
#include "USBMSC.h"
#include "esp_partition.h"
#include <time.h>

namespace hwfiles {

static const uint32_t FSECT = 512;
static const uint8_t FMAX_FILES = 24;
static const uint8_t FNAME_MAX = 96;      // UTF-8 바이트
static const uint32_t FROOT_ENTRIES = 512;
static const uint32_t FROOT_SECTS = FROOT_ENTRIES * 32 / FSECT;   // 32

struct FileRec {
  uint16_t start;    // 첫 클러스터 (2부터)
  uint16_t _pad;
  uint32_t size;
  uint32_t time;     // 현지 시각 (1970 기준 초)
  char name[FNAME_MAX];
};

static const esp_partition_t *part = nullptr;
static USBMSC *msc = nullptr;
static Preferences *pp = nullptr;
static FileRec files[FMAX_FILES];
static uint8_t count = 0;

// 디스크 모양
static uint32_t dataClus = 0, fatSects = 0, fatStart = 1, rootStart = 0, dataStart = 0, totalSects = 0;
static uint8_t rootBuf[FROOT_SECTS * FSECT];   // 폴더 영역 (16KB)
static volatile bool mediaOn = true;

// 받는 중
static bool rx = false;
static uint32_t rxBase = 0, rxSize = 0, rxGot = 0, rxErased = 0, rxTime = 0;
static uint16_t rxStart = 0;
static char rxName[FNAME_MAX];
String state = "";                 // "", rx, ok, err:...
static uint32_t pulseAt = 0;        // 매체 다시 넣기 시각

static inline uint32_t clusOf(uint32_t size) { return (size + FSECT - 1) / FSECT; }
static inline uint32_t align8(uint32_t c) { return ((c - 2 + 7) / 8) * 8 + 2; }   // 4KB 경계

bool ok() { return part != nullptr; }

uint32_t nextCluster() {
  uint32_t n = 2;
  for (uint8_t i = 0; i < count; i++) { uint32_t e = files[i].start + clusOf(files[i].size); if (e > n) n = e; }
  return align8(n);
}
int32_t freeBytes() {
  if (!part) return -1;
  int64_t f = (int64_t)(dataClus + 2 - nextCluster()) * FSECT;
  return f < 0 ? 0 : (int32_t)f;
}

// ---------- 이름 ----------
static int utf8to16(const char *s, uint16_t *out, int max) {
  int n = 0;
  const uint8_t *p = (const uint8_t *)s;
  while (*p && n < max) {
    uint32_t c = *p++;
    if (c >= 0xF0) { c = ((c & 7) << 18) | ((p[0] & 63) << 12) | ((p[1] & 63) << 6) | (p[2] & 63); p += 3; c = '_'; }
    else if (c >= 0xE0) { c = ((c & 15) << 12) | ((p[0] & 63) << 6) | (p[1] & 63); p += 2; }
    else if (c >= 0xC0) { c = ((c & 31) << 6) | (p[0] & 63); p += 1; }
    if (c < 0x20 || (c < 0x80 && strchr("\\/:*?\"<>|", (int)c))) c = '_';
    out[n++] = (uint16_t)c;
  }
  return n;
}

static void sfn(uint8_t idx, const char *name, uint8_t out[11]) {
  memset(out, ' ', 11);
  char base[9]; snprintf(base, sizeof(base), "HW%06u", (unsigned)idx + 1);
  memcpy(out, base, 8);
  const char *dot = strrchr(name, '.');
  if (dot && dot[1]) {
    int k = 0;
    for (const char *q = dot + 1; *q && k < 3; q++) {
      char ch = *q;
      if (ch >= 'a' && ch <= 'z') ch -= 32;
      if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) out[8 + k++] = ch; else break;
    }
  }
}
static uint8_t sfnSum(const uint8_t s[11]) { uint8_t sum = 0; for (int i = 0; i < 11; i++) sum = ((sum & 1) << 7) + (sum >> 1) + s[i]; return sum; }

static void fatTime(uint32_t t, uint16_t &d, uint16_t &tm) {
  time_t tt = t ? t : 1767225600;   // 기본 2026-01-01
  struct tm g; gmtime_r(&tt, &g);
  int y = g.tm_year + 1900; if (y < 1980) y = 1980;
  d = ((y - 1980) << 9) | ((g.tm_mon + 1) << 5) | g.tm_mday;
  tm = (g.tm_hour << 11) | (g.tm_min << 5) | (g.tm_sec / 2);
}

// 폴더(루트) 영역을 다시 만듦
static void buildRoot() {
  memset(rootBuf, 0, sizeof(rootBuf));
  uint8_t *e = rootBuf;
  // 볼륨 이름
  memcpy(e, "HW REMOTE  ", 11); e[11] = 0x08; e += 32;
  for (uint8_t i = 0; i < count; i++) {
    uint16_t u[260]; int n = utf8to16(files[i].name, u, 255);
    int slots = (n + 12) / 13; if (slots < 1) slots = 1;
    if ((size_t)(e - rootBuf) + (slots + 1) * 32 > sizeof(rootBuf)) break;
    uint8_t s[11]; sfn(i, files[i].name, s);
    uint8_t sum = sfnSum(s);
    for (int k = slots; k >= 1; k--) {         // 긴 이름은 거꾸로 저장
      memset(e, 0, 32);
      e[0] = (uint8_t)k | (k == slots ? 0x40 : 0);
      e[11] = 0x0F; e[13] = sum;
      static const uint8_t pos[13] = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
      for (int j = 0; j < 13; j++) {
        int ci = (k - 1) * 13 + j;
        uint16_t ch = ci < n ? u[ci] : (ci == n ? 0x0000 : 0xFFFF);
        e[pos[j]] = ch & 0xFF; e[pos[j] + 1] = ch >> 8;
      }
      e += 32;
    }
    memcpy(e, s, 11);
    e[11] = 0x21;   // 읽기 전용 + 보관
    uint16_t d, t; fatTime(files[i].time, d, t);
    e[14] = t & 0xFF; e[15] = t >> 8; e[16] = d & 0xFF; e[17] = d >> 8;   // 만든 시각
    e[18] = d & 0xFF; e[19] = d >> 8;                                       // 마지막 접근
    e[22] = t & 0xFF; e[23] = t >> 8; e[24] = d & 0xFF; e[25] = d >> 8;   // 고친 시각
    uint16_t st = files[i].size ? files[i].start : 0;
    e[26] = st & 0xFF; e[27] = st >> 8;
    uint32_t sz = files[i].size;
    e[28] = sz; e[29] = sz >> 8; e[30] = sz >> 16; e[31] = sz >> 24;
    e += 32;
  }
}

static void bootSector(uint8_t *b) {
  memset(b, 0, FSECT);
  b[0] = 0xEB; b[1] = 0x3C; b[2] = 0x90;
  memcpy(b + 3, "MSWIN4.1", 8);
  b[11] = FSECT & 0xFF; b[12] = FSECT >> 8;
  b[13] = 1;                       // 클러스터당 섹터
  b[14] = 1; b[15] = 0;            // 예약 섹터
  b[16] = 2;                       // FAT 2개
  b[17] = FROOT_ENTRIES & 0xFF; b[18] = FROOT_ENTRIES >> 8;
  b[19] = totalSects & 0xFF; b[20] = totalSects >> 8;
  b[21] = 0xF8;
  b[22] = fatSects & 0xFF; b[23] = fatSects >> 8;
  b[24] = 32; b[26] = 64;
  b[36] = 0x80; b[38] = 0x29;
  b[39] = 0x48; b[40] = 0x57; b[41] = 0x52; b[42] = 0x43;   // 볼륨 ID
  memcpy(b + 43, "HW REMOTE  ", 11);
  memcpy(b + 54, "FAT16   ", 8);
  b[510] = 0x55; b[511] = 0xAA;
}

static uint16_t fatEntry(uint32_t c) {
  if (c == 0) return 0xFFF8;
  if (c == 1) return 0xFFFF;
  for (uint8_t i = 0; i < count; i++) {
    uint32_t n = clusOf(files[i].size);
    if (n && c >= files[i].start && c < files[i].start + n) return c == files[i].start + n - 1 ? 0xFFFF : c + 1;
  }
  return 0;
}

// PC가 디스크를 읽을 때 (USB 작업 안에서 불림)
static int32_t onRead(uint32_t lba, uint32_t offset, void *buf, uint32_t bufsize) {
  uint8_t *out = (uint8_t *)buf;
  uint32_t done = 0;
  while (done < bufsize) {
    uint32_t s = lba + (offset + done) / FSECT, o = (offset + done) % FSECT;
    uint32_t n = FSECT - o; if (n > bufsize - done) n = bufsize - done;
    uint8_t tmp[FSECT];
    if (s == 0) bootSector(tmp);
    else if (s >= fatStart && s < rootStart) {
      uint32_t fs = (s - fatStart) % fatSects;
      for (uint32_t k = 0; k < FSECT / 2; k++) { uint16_t v = fatEntry(fs * (FSECT / 2) + k); tmp[2 * k] = v & 0xFF; tmp[2 * k + 1] = v >> 8; }
    } else if (s >= rootStart && s < dataStart) memcpy(tmp, rootBuf + (s - rootStart) * FSECT, FSECT);
    else if (s < totalSects && part) {
      if (esp_partition_read(part, (s - dataStart) * FSECT, tmp, FSECT) != ESP_OK) memset(tmp, 0, FSECT);
    } else memset(tmp, 0, FSECT);
    memcpy(out + done, tmp + o, n);
    done += n;
  }
  return bufsize;
}
static int32_t onWrite(uint32_t lba, uint32_t offset, uint8_t *buf, uint32_t bufsize) { return bufsize; }   // 읽기 전용 (쓰기는 무시)

static void saveList() {
  pp->putBytes("files", files, sizeof(FileRec) * count);
  pp->putUChar("fcount", count);
}

// PC가 바뀐 내용을 다시 읽도록 '꺼냈다 다시 넣기'
static void replug() {
  if (!msc) return;
  mediaOn = false; msc->mediaPresent(false);
  pulseAt = millis() + 1500;
}

// ---------- 시작 ----------
// mscOn: USB 드라이브로 보일지 (USB.begin 전에 불러야 함)
void begin(Preferences &prefs, bool mscOn) {
  pp = &prefs;
  part = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, (esp_partition_subtype_t)0x99, "hwfiles");
  if (!part) return;   // 예전 파티션 → PC로 펌웨어를 한 번 다시 넣어야 함
  dataClus = part->size / FSECT;
  if (dataClus > 65524) dataClus = 65524;
  fatSects = ((dataClus + 2) * 2 + FSECT - 1) / FSECT;
  rootStart = fatStart + 2 * fatSects;
  dataStart = rootStart + FROOT_SECTS;
  totalSects = dataStart + dataClus;
  count = prefs.getUChar("fcount", 0);
  if (count > FMAX_FILES) count = 0;
  if (count && prefs.getBytes("files", files, sizeof(FileRec) * count) != sizeof(FileRec) * count) count = 0;
  buildRoot();
  if (!mscOn) return;
  msc = new USBMSC();
  msc->vendorID("CHAEUM");
  msc->productID("HW Remote Files");
  msc->productRevision("1.0");
  msc->onRead(onRead);
  msc->onWrite(onWrite);
  msc->isWritable(false);
  msc->mediaPresent(true);
  msc->begin(totalSects, FSECT);
}

void loop() {
  if (pulseAt && (int32_t)(millis() - pulseAt) >= 0) { pulseAt = 0; mediaOn = true; if (msc) msc->mediaPresent(true); }
}

// ---------- 휴대폰에서 받기 ----------
// [크기 u32][시각 u32][이름 utf8]
void cmdBegin(const uint8_t *p, size_t n) {
  if (!part) { state = "err:partition"; return; }
  if (n < 9) { state = "err:begin"; return; }
  if (count >= FMAX_FILES) { state = "err:many"; return; }
  rxSize = p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24);
  rxTime = p[4] | (p[5] << 8) | (p[6] << 16) | ((uint32_t)p[7] << 24);
  size_t ln = n - 8; if (ln >= FNAME_MAX) ln = FNAME_MAX - 1;
  while (ln > 0 && (p[8 + ln] & 0xC0) == 0x80) ln--;   // UTF-8 글자 중간에서 자르지 않음
  memcpy(rxName, p + 8, ln); rxName[ln] = 0;
  rxStart = nextCluster();
  if ((int64_t)rxSize > freeBytes()) { state = "err:full"; rx = false; return; }
  rxBase = (rxStart - 2) * FSECT; rxErased = rxBase; rxGot = 0; rx = true;
  state = "rx";
}
// [위치 u32][바이트...]
void cmdData(const uint8_t *p, size_t n) {
  if (!rx || n < 5) return;
  uint32_t off = p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24);
  size_t len = n - 4;
  if (off < rxGot) return;                                            // 이미 받은 조각 (재전송) → 무시
  if (off > rxGot) { state = "err:order"; rx = false; return; }       // 빠진 조각
  if (off + len > rxSize) { state = "err:size"; rx = false; return; }
  uint32_t a = rxBase + off;
  while (rxErased < a + len) {
    if (esp_partition_erase_range(part, rxErased, 4096) != ESP_OK) { state = "err:erase"; rx = false; return; }
    rxErased += 4096;
  }
  if (esp_partition_write(part, a, p + 4, len) != ESP_OK) { state = "err:write"; rx = false; return; }
  rxGot += len;
}
void cmdEnd() {
  if (!rx) return;
  rx = false;
  if (rxGot != rxSize) { state = "err:short"; return; }
  // 같은 이름이 있으면 ' (2)'를 붙임
  String nm = rxName;
  for (int k = 2; k < 100; k++) {
    bool dup = false;
    for (uint8_t i = 0; i < count; i++) if (nm == files[i].name) dup = true;
    if (!dup) break;
    String b = rxName; int dot = b.lastIndexOf('.');
    nm = dot > 0 ? b.substring(0, dot) + " (" + k + ")" + b.substring(dot) : b + " (" + k + ")";
  }
  FileRec &f = files[count];
  memset(&f, 0, sizeof(f));
  f.start = rxStart; f.size = rxSize; f.time = rxTime;
  strncpy(f.name, nm.c_str(), FNAME_MAX - 1);
  count++;
  replug();
  buildRoot();
  saveList();
  state = "ok";
}
void cmdClear() {
  rx = false; count = 0;
  replug();
  buildRoot();
  saveList();
  state = "cleared";
}

uint8_t fileCount() { return count; }
uint32_t receiving() { return rx ? rxGot : 0; }

}  // namespace hwfiles
