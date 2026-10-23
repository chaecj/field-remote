package kr.chaeum.fieldremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 휴대폰 자체를 블루투스 키보드·마우스로 만든다 (동글 없이 전자칠판·노트북 제어).
 * 동글과 같은 명령 바이트를 받아 HID 리포트로 바꿔 보낸다. IR 명령은 무시.
 */
@SuppressLint("MissingPermission")
public final class HidKeyboard {
    // 동글 펌웨어와 같은 리포트 구성: 1=키보드, 2=미디어키, 3=마우스
    static final byte[] DESCRIPTOR = {
        0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
        0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
        (byte) 0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, (byte) 0x91, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x91, 0x01,
        (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, (byte) 0xA4, 0x05, 0x07, 0x19, 0x00, 0x29, (byte) 0xA4, (byte) 0x81, 0x00,
        (byte) 0xC0,
        0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01, (byte) 0x85, 0x02,
        0x15, 0x00, 0x26, (byte) 0xFF, 0x03, 0x19, 0x00, 0x2A, (byte) 0xFF, 0x03, 0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
        (byte) 0xC0,
        0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, (byte) 0x85, 0x03, 0x09, 0x01, (byte) 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, (byte) 0x95, 0x05, 0x75, 0x01, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06,
        (byte) 0xC0, (byte) 0xC0,
    };

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered;
    private boolean registering;
    private int attempt;
    private boolean wanted;
    private String state = "off";

    private byte mods = 0;
    private final byte[] keys = new byte[6];
    private byte mouseBtn = 0;

    HidKeyboard(Context ctx) {
        this.ctx = ctx;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    private static void log(String s) { Hub.emit("onHidLog", s); }

    // ---------- 켜기/끄기 ----------
    void start() {
        wanted = true;
        if (adapter == null) { log("블루투스 장치 없음"); setState("unsupported"); return; }
        if (!adapter.isEnabled()) { log("블루투스가 꺼져 있음"); setState("off"); return; }
        if (hid != null) {
            log("이미 준비된 상태 (등록 " + registered + ", 등록 중 " + registering + ")");
            if (!registered && !registering) { attempt = 0; register(); } else emitState();
            return;
        }
        boolean ok;
        try {
            ok = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    log("키보드 기능 연결됨");
                    hid = (BluetoothHidDevice) proxy;
                    attempt = 0;
                    if (wanted) Hub.main.postDelayed(() -> register(), 300);
                }
                @Override public void onServiceDisconnected(int profile) {
                    log("키보드 기능 끊김");
                    hid = null; registered = false; registering = false; host = null; setState("off");
                }
            }, BluetoothProfile.HID_DEVICE);
        } catch (Exception e) { log("키보드 기능 요청 오류: " + e); ok = false; }
        log("키보드 기능 요청: " + (ok ? "보냄" : "거절됨"));
        if (!ok) { setState("unsupported"); return; }
        Hub.main.postDelayed(() -> { if (hid == null && wanted) log("5초 동안 키보드 기능 응답 없음"); }, 5000);
    }

    private void register() {
        if (hid == null || registered || registering) return;
        attempt++;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
            "HW 리모컨", "Keyboard and mouse", "CHAEUM", BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        // 휴대폰마다 받아들이는 방식이 달라서 시도할 때마다 조합을 바꿈
        BluetoothHidDeviceAppQosSettings qos = attempt % 2 == 1
            ? new BluetoothHidDeviceAppQosSettings(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX)
            : null;
        java.util.concurrent.Executor exec = attempt <= 2 ? ctx.getMainExecutor() : ex;
        registering = true;
        try {
            boolean ok = hid.registerApp(sdp, null, qos, exec, cb);
            log("키보드 등록 요청 " + attempt + "차 (" + (qos != null ? "QoS" : "기본") + "): " + ok);
            if (!ok) {
                try { log("등록 해제: " + hid.unregisterApp()); } catch (Exception e) { log("해제 오류: " + e); }
                ok = hid.registerApp(sdp, null, qos, exec, cb);
                log("키보드 등록 재요청: " + ok);
            }
            if (!ok) { registering = false; setState("failed"); return; }
            final int my = attempt;
            Hub.main.postDelayed(() -> {
                if (!registering || attempt != my) return;
                log("5초 동안 등록 결과 없음");
                registering = false;
                if (attempt < 4 && wanted) {
                    try { hid.unregisterApp(); } catch (Exception ignored) {}
                    log("방식을 바꿔 다시 등록");
                    Hub.main.postDelayed(() -> register(), 800);
                } else setState("failed");
            }, 5000);
        } catch (Exception e) { log("등록 오류: " + e); registering = false; setState("failed"); }
    }

    void stop() {
        wanted = false;
        try {
            if (hid != null) {
                if (host != null) hid.disconnect(host);
                hid.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception ignored) {}
        hid = null; host = null; registered = false; registering = false;
        setState("off");
    }

    void connect(String addr) {
        if (hid == null) { log("키보드 기능이 아직 준비되지 않아 연결할 수 없음"); return; }
        if (hid == null || adapter == null || !BluetoothAdapter.checkBluetoothAddress(addr)) return;
        try { log("연결 요청 " + addr + ": " + hid.connect(adapter.getRemoteDevice(addr))); } catch (Exception e) { log("연결 오류: " + e); }
    }

    String bonded() {
        JSONArray arr = new JSONArray();
        try {
            Set<BluetoothDevice> set = adapter != null ? adapter.getBondedDevices() : null;
            if (set != null) for (BluetoothDevice d : set) {
                JSONObject o = new JSONObject();
                o.put("name", d.getName() == null ? "" : d.getName());
                o.put("addr", d.getAddress());
                arr.put(o);
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    void emitState() { Hub.emit("onHid", state, hostName()); }

    private String hostName() {
        if (host == null) return "";
        try { String n = host.getName(); return n == null ? host.getAddress() : n; } catch (Exception e) { return ""; }
    }

    private void setState(String s) { state = s; emitState(); }

    private final BluetoothHidDevice.Callback cb = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice device, boolean reg) {
            log("등록 결과: " + (reg ? "성공" : "해제됨") + (device != null ? " (" + device.getAddress() + ")" : ""));
            registered = reg;
            registering = false;
            if (!reg) { host = null; setState(wanted ? "off" : "off"); return; }
            setState(host != null ? "on" : "ready");
            // 예전에 연결했던 기기가 있으면 다시 연결 시도
            try {
                if (hid != null && host == null) {
                    List<BluetoothDevice> list = hid.getConnectedDevices();
                    if (list != null && !list.isEmpty()) { host = list.get(0); setState("on"); }
                }
            } catch (Exception ignored) {}
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int s) {
            String[] names = {"끊김", "연결 중", "연결됨", "끊는 중"};
            log("기기 " + (device != null ? device.getAddress() : "?") + ": " + (s >= 0 && s < 4 ? names[s] : String.valueOf(s)));
            if (s == BluetoothProfile.STATE_CONNECTED) { host = device; setState("on"); }
            else if (s == BluetoothProfile.STATE_DISCONNECTED) {
                if (host != null && host.equals(device)) host = null;
                setState(registered ? "ready" : "off");
            }
        }
    };

    // ---------- 명령 → HID 리포트 ----------
    void send(byte[] cmd) { ex.execute(() -> handle(cmd)); }

    private void handle(byte[] d) {
        if (d == null || d.length == 0 || hid == null || host == null) return;
        int c = d[0] & 0xFF;
        try {
            switch (c) {
                case 0x01: if (d.length >= 2) keyTap(d[1], d.length >= 3 ? d[2] : 0); break;
                case 0x02: if (d.length >= 2) press(d[1]); break;
                case 0x03: if (d.length >= 2) release(d[1]); break;
                case 0x04: releaseAll(); cc(0); mouseBtn = 0; mouse(0, 0, 0); break;
                case 0x05: for (int i = 1; i < d.length; i++) typeChar((char) (d[i] & 0xFF)); break;
                case 0x06: if (d.length >= 3) mouse(d[1], d[2], d.length >= 4 ? d[3] : 0); break;
                case 0x07: { byte b = d.length >= 2 ? d[1] : 1; mouseBtn |= b; mouse(0, 0, 0); sleep(20); mouseBtn &= ~b; mouse(0, 0, 0); break; }
                case 0x08: mouseBtn |= d.length >= 2 ? d[1] : 1; mouse(0, 0, 0); break;
                case 0x09: mouseBtn &= ~(d.length >= 2 ? d[1] : 1); mouse(0, 0, 0); break;
                case 0x0A: if (d.length >= 3) { cc((d[1] & 0xFF) | ((d[2] & 0xFF) << 8)); sleep(30); cc(0); } break;
                case 0x0B: if (d.length >= 3) cc((d[1] & 0xFF) | ((d[2] & 0xFF) << 8)); break;
                case 0x0C: cc(0); break;
                default: break; // IR 등은 휴대폰으로 보낼 수 없음
            }
        } catch (Exception ignored) {}
    }

    private void kbReport() {
        byte[] r = {mods, 0, keys[0], keys[1], keys[2], keys[3], keys[4], keys[5]};
        hid.sendReport(host, 1, r);
    }
    private void press(byte k) {
        int kk = k & 0xFF;
        if (kk >= 0xE0 && kk < 0xE8) mods |= (byte) (1 << (kk - 0xE0));
        else {
            boolean has = false;
            for (byte x : keys) if (x == k) has = true;
            if (!has) for (int i = 0; i < 6; i++) if (keys[i] == 0) { keys[i] = k; break; }
        }
        kbReport();
    }
    private void release(byte k) {
        int kk = k & 0xFF;
        if (kk >= 0xE0 && kk < 0xE8) mods &= (byte) ~(1 << (kk - 0xE0));
        else for (int i = 0; i < 6; i++) if (keys[i] == k) keys[i] = 0;
        kbReport();
    }
    private void releaseAll() { mods = 0; for (int i = 0; i < 6; i++) keys[i] = 0; kbReport(); }
    private void keyTap(byte key, byte m) {
        for (int i = 0; i < 8; i++) if ((m & (1 << i)) != 0) press((byte) (0xE0 + i));
        press(key);
        sleep(30);
        releaseAll();
    }
    private void cc(int usage) { hid.sendReport(host, 2, new byte[]{(byte) (usage & 0xFF), (byte) (usage >> 8)}); }
    private void mouse(int x, int y, int w) { hid.sendReport(host, 3, new byte[]{mouseBtn, (byte) x, (byte) y, (byte) w}); }

    private void typeChar(char c) {
        int key; boolean shift = false;
        if (c >= 'a' && c <= 'z') key = 0x04 + (c - 'a');
        else if (c >= 'A' && c <= 'Z') { key = 0x04 + (c - 'A'); shift = true; }
        else if (c >= '1' && c <= '9') key = 0x1E + (c - '1');
        else if (c == '0') key = 0x27;
        else if (c == ' ') key = 0x2C;
        else if (c == '\n') key = 0x28;
        else {
            String plain = "-=[]\\;'`,./", shifted = "_+{}|:\"~<>?", num = "!@#$%^&*()";
            int[] pk = {0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
            int i;
            if ((i = plain.indexOf(c)) >= 0) key = pk[i];
            else if ((i = shifted.indexOf(c)) >= 0) { key = pk[i]; shift = true; }
            else if ((i = num.indexOf(c)) >= 0) { key = i == 9 ? 0x27 : 0x1E + i; shift = true; }
            else return;
        }
        keyTap((byte) key, (byte) (shift ? 0x02 : 0));
        sleep(5);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
