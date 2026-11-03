# HW 리모컨 (field-remote)

촬영장 소품 TV·노트북·전자칠판을 휴대폰 하나로 제어하는 앱과 동글 펌웨어.
사용자(채현우)는 드라마 제작부·연출부. **촬영 현장은 속전속결**이라 편의성과 실용성이 가장 중요하다. 설명보다 바로 쓸 수 있는 결과를 우선한다.

## 구조
```
휴대폰 앱(안드로이드) ──블루투스 LE──▶ 동글(M5Stack AtomS3 Lite, ESP32-S3)
                                      ├─ USB: TV에 키보드·마우스·미디어키로 인식
                                      ├─ IR: 내장 적외선 LED로 리모컨 신호
                                      └─ 블루투스 키보드(HID): 전자칠판 등
```

| 경로 | 내용 |
|---|---|
| `tv-remote/app/index.html` | **화면 전체 (HTML/CSS/JS 한 파일)**. 대부분의 수정은 여기서 한다 |
| `tv-remote/android/` | 안드로이드 앱 껍데기: WebView + 블루투스(BleManager), 휴대폰 키보드(HidKeyboard), 볼륨키 클리커·연결 유지(RemoteService), 앱 안 업데이트(Updater), JS 브리지(Bridge → `window.Native`) |
| `tv-remote/android/app/src/main/assets/index.html` | 빌드 때 `app/index.html`이 자동 복사됨. 직접 고치지 않는다 |
| `tv-remote/firmware/TVRemoteDongle/TVRemoteDongle.ino` | 동글 펌웨어 (Arduino, ESP32 core 3.3.x) |
| `tv-remote/firmware/TVRemoteDongle.merged.bin` | PC로 처음 굽는 통합 이미지 (주소 **0x0**) |
| `tv-remote/firmware/TVRemoteDongle.app.bin`, `latest.json` | 무선(OTA) 펌웨어 업데이트용. 앱이 `latest.json`을 읽어 새 버전을 알림 |
| `.github/workflows/android.yml` | main에 push하면 APK를 빌드해 Releases에 "HW 리모컨 N"으로 올림 |

## 배포 흐름
- **앱**: `tv-remote/app/index.html` 또는 `tv-remote/android/**`를 고쳐 main에 push → GitHub Actions가 APK 빌드(빌드 번호 = versionCode) → 휴대폰 앱이 켤 때 새 버전을 알리고 앱 안에서 설치. README·펌웨어만 바뀌면 빌드하지 않는다.
- **펌웨어**: `.ino` 수정 → 컴파일 → `TVRemoteDongle.app.bin`과 `latest.json`(version, size, sha256, url) 갱신 → push. `FW_VERSION`을 올려야 앱이 새 버전으로 인식한다. 앱의 TV 설정 탭에서 무선 업데이트.
  - 컴파일: `arduino-cli compile --fqbn "esp32:esp32:m5stack_atoms3:USBMode=default,CDCOnBoot=default" --export-binaries`
  - USB Mode는 반드시 **USB-OTG (TinyUSB)** (`USBMode=default`).
- **main에 합치기 (사용자와 정한 규칙)**
  - 앱·문서 변경: 확인(`node --check`, 가능하면 데모 TV로 동작 확인)을 마치면 **묻지 않고** PR을 만들어 바로 main에 합친다. 합친 뒤 APK 빌드가 시작됐는지 확인하고 알려 준다.
  - **펌웨어 변경(`tv-remote/firmware/**`)은 예외**: 컴파일까지 마친 뒤 사용자에게 먼저 묻고 합친다. 동글 업데이트가 잘못되면 PC로 다시 구워야 해서다.
  - 확인에 실패했거나 사용자의 의도가 애매하면 합치지 말고 먼저 묻는다.
  - 안드로이드는 낮은 버전으로 되돌릴 수 없으니, 문제가 생기면 고친 버전을 새로 올린다.
- 서명키 `tv-remote/android/app/fieldremote.keystore`는 바꾸지 않는다. 바꾸면 기존 앱 위에 업데이트가 안 된다.

## 동글 통신 (BLE)
- 서비스 `7e1a0001-…`, 명령 쓰기 `7e1a0002-…`, 상태 알림 `7e1a0003-…` (JSON: n 이름, v 펌웨어, usb, bt, mtu, btn, uc USB 호환 모드, o OTA 상태, d 진단)
- 명령 바이트: 0x01 키 누르기 [key, mods], 0x05 글자, 0x06 마우스 이동, 0x07 클릭, 0x0A 미디어키 [usage LE], 0x20/0x30~0x32 IR, 0x40 동글 찾기, 0x41 이름, 0x42 블루투스 기록 삭제, 0x43 USB 호환 모드, 0x50~0x53 OTA

## 작업 규칙
- 화면 문구는 한국어, 짧고 쉽게. 앱 이름은 **HW 리모컨**, 동글 블루투스 이름은 `HW-xxxx`.
- 테마: 어두운 네이비 배경 + HW 로고의 파란 그라데이션. 색은 `:root` 토큰만 쓴다.
- 세로·가로(태블릿) 모두 지원. 가로는 `@media (orientation: landscape) and (min-width: 700px)`에서 2단 배치.
- 고친 뒤에는 최소한 JS 문법 검사(`node --check`)와, 펌웨어면 컴파일까지 확인한다.
- 사용자에게는 바뀐 파일과 그대로인 파일을 분명히 알려 준다.
