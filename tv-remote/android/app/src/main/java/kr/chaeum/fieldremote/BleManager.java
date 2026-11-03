package kr.chaeum.fieldremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 동글(AtomS3)과의 블루투스 LE 연결. 동글마다 명령을 순서대로 하나씩 보낸다. */
@SuppressLint("MissingPermission")
public final class BleManager {
    static final UUID SVC = UUID.fromString("7e1a0001-5c2b-4f3a-9e7d-3c1b8a9f0a11");
    static final UUID CMD = UUID.fromString("7e1a0002-5c2b-4f3a-9e7d-3c1b8a9f0a11");
    static final UUID INFO = UUID.fromString("7e1a0003-5c2b-4f3a-9e7d-3c1b8a9f0a11");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int OP_WRITE = 0, OP_NOTIFY = 1, OP_READ = 2;

    private static final class Op {
        final int kind; final byte[] data; final boolean reliable; int tries;
        Op(int kind, byte[] data, boolean reliable) { this.kind = kind; this.data = data; this.reliable = reliable; }
    }

    private final class Conn {
        final String addr;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic cmd, info;
        final ArrayDeque<Op> q = new ArrayDeque<>();
        boolean busy, ready, wanted = true;
        Op inflight;   // 지금 보내는 중인 것 (실패 응답이 오면 다시 보냄)
        boolean unbondTried;   // 휴대폰에 남은 동글 등록 기록을 지워 봤는지
        int retries;
        String name = "";
        Conn(String addr) { this.addr = addr; }
    }

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private ScanCallback scanCb;

    BleManager(Context ctx) {
        this.ctx = ctx;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    boolean available() { return adapter != null && adapter.isEnabled(); }

    // ---------- 찾기 ----------
    void scan() {
        if (!available()) { Hub.emit("onPerm", false); return; }
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) return;
        stopScan();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                ScanRecord rec = r.getScanRecord();
                String name = rec != null && rec.getDeviceName() != null ? rec.getDeviceName() : d.getName();
                Hub.emit("onScan", d.getAddress(), name == null ? "" : name, r.getRssi());
            }
        };
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try { sc.startScan(Collections.singletonList(f), s, scanCb); } catch (Exception e) { Hub.emit("onPerm", false); return; }
        h.postDelayed(this::stopScan, 15000);
    }

    void stopScan() {
        if (scanCb == null || adapter == null) return;
        try { BluetoothLeScanner sc = adapter.getBluetoothLeScanner(); if (sc != null) sc.stopScan(scanCb); } catch (Exception ignored) {}
        scanCb = null;
    }

    // ---------- 연결 ----------
    void connect(String addr) {
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(addr)) return;
        Conn c = conns.get(addr);
        if (c == null) { c = new Conn(addr); conns.put(addr, c); }
        c.wanted = true;
        if (c.gatt != null) { // 이미 연결 중이면 상태만 다시 알림
            Hub.emit("onState", addr, c.ready ? "on" : "wait", c.name);
            return;
        }
        open(c, false);
    }

    private void open(Conn c, boolean auto) {
        if (!available()) { Hub.emit("onState", c.addr, "offline", c.name); scheduleRetry(c); return; }
        BluetoothDevice dev = adapter.getRemoteDevice(c.addr);
        // 이 앱은 휴대폰과 동글을 등록(본딩)할 필요가 없음. 등록 기록이 남아 있으면, 동글을 다시 굽거나
        // 기록을 지웠을 때 열쇠가 어긋나 '연결됨'인데 아무것도 주고받지 못함 → 연결 전에 지움
        if (!c.unbondTried && bonded(dev)) {
            c.unbondTried = true;
            if (removeBond(dev)) { Hub.emit("onState", c.addr, "wait", c.name); h.postDelayed(() -> { if (c.wanted && c.gatt == null) open(c, auto); }, 1200); return; }
        }
        Hub.emit("onState", c.addr, "wait", c.name);
        try {
            c.gatt = dev.connectGatt(ctx, auto, new Cb(c), BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            c.gatt = null;
            Hub.emit("onState", c.addr, "offline", c.name);
        }
    }

    @SuppressWarnings("MissingPermission")
    private static boolean bonded(BluetoothDevice d) {
        try { return d.getBondState() == BluetoothDevice.BOND_BONDED; } catch (Exception e) { return false; }
    }

    // 숨은 API지만 대부분의 휴대폰에서 동작함. 안 되면 사용자가 블루투스 설정에서 등록 해제
    private static boolean removeBond(BluetoothDevice d) {
        try { Object r = d.getClass().getMethod("removeBond").invoke(d); return Boolean.TRUE.equals(r); }
        catch (Exception e) { return false; }
    }

    void disconnect(String addr) {
        Conn c = conns.remove(addr);
        if (c == null) return;
        c.wanted = false;
        if (c.gatt != null) { try { c.gatt.disconnect(); c.gatt.close(); } catch (Exception ignored) {} }
        c.gatt = null;
    }

    /** 모든 동글 연결을 끊고 다시 붙지 않게 (앱 완전히 끄기) */
    void disconnectAll() {
        for (String addr : new java.util.ArrayList<>(conns.keySet())) disconnect(addr);
        stopScan();
    }

    private void scheduleRetry(Conn c) {
        if (!c.wanted) return;
        long delay = Math.min(30000, 1500L * (1 + c.retries));
        final boolean auto = c.retries >= 2; // 몇 번 실패하면 동글이 다시 보일 때 자동 연결
        c.retries++;
        h.postDelayed(() -> { if (c.wanted && c.gatt == null && conns.get(c.addr) == c) open(c, auto); }, delay);
    }

    void dumpStates() {
        for (Conn c : conns.values()) Hub.emit("onState", c.addr, c.ready ? "on" : c.gatt != null ? "wait" : "offline", c.name);
    }

    int pending(String addr) {
        Conn c = conns.get(addr);
        if (c == null) return 0;
        synchronized (c) { return c.q.size() + (c.busy ? 1 : 0); }
    }

    // ---------- 보내기 ----------
    void write(String addr, byte[] data, boolean reliable) {
        Conn c = conns.get(addr);
        if (c == null || !c.ready) return;
        synchronized (c) {
            if (!reliable && c.q.size() > 30) return; // 밀리면 마우스 이동 같은 것은 버림
            c.q.add(new Op(OP_WRITE, data, reliable));
        }
        pump(c);
    }

    private void pump(Conn c) {
        Op op;
        synchronized (c) {
            if (c.busy || c.gatt == null) return;
            op = c.q.poll();
            if (op == null) return;
            c.busy = true;
            c.inflight = op;
        }
        boolean ok;
        try { ok = run(c, op); } catch (Exception e) { ok = false; }
        if (ok) return; // 완료 콜백에서 다음 것을 보냄
        boolean gaveUp;
        synchronized (c) {
            c.busy = false; c.inflight = null;
            // 잠시 뒤 다시 시도. 확인 받는 것(펌웨어 조각 등)은 약 3초까지 버티고, 그래도 안 되면 화면에 알림
            gaveUp = ++op.tries > (op.reliable ? 200 : 20);
            if (!gaveUp) c.q.addFirst(op);
        }
        if (gaveUp && op.reliable && op.kind == OP_WRITE) Hub.emit("onWriteFail", c.addr);
        h.postDelayed(() -> pump(c), 15);
    }

    @SuppressWarnings("deprecation")
    private boolean run(Conn c, Op op) {
        BluetoothGatt g = c.gatt;
        if (g == null) return false;
        switch (op.kind) {
            case OP_WRITE: {
                int type = op.reliable ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                if (Build.VERSION.SDK_INT >= 33) return g.writeCharacteristic(c.cmd, op.data, type) == BluetoothStatusCodes.SUCCESS;
                c.cmd.setWriteType(type);
                c.cmd.setValue(op.data);
                return g.writeCharacteristic(c.cmd);
            }
            case OP_NOTIFY: {
                g.setCharacteristicNotification(c.info, true);
                BluetoothGattDescriptor d = c.info.getDescriptor(CCCD);
                if (d == null) { done(c); return true; }
                if (Build.VERSION.SDK_INT >= 33)
                    return g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                return g.writeDescriptor(d);
            }
            case OP_READ:
                return g.readCharacteristic(c.info);
        }
        return true;
    }

    private void done(Conn c) {
        synchronized (c) { c.busy = false; c.inflight = null; }
        h.post(() -> pump(c));
    }

    // 쓰기 응답: 실패면 같은 것을 다시 보냄 (예전엔 실패해도 다음으로 넘어가서 펌웨어 조각이 빠졌음)
    private void writeDone(Conn c, int status) {
        Op op;
        synchronized (c) { op = c.inflight; }
        if (status == BluetoothGatt.GATT_SUCCESS || op == null || !op.reliable) { done(c); return; }
        boolean gaveUp;
        synchronized (c) {
            c.busy = false; c.inflight = null;
            gaveUp = ++op.tries > 20;
            if (!gaveUp) c.q.addFirst(op);
        }
        if (gaveUp) Hub.emit("onWriteFail", c.addr);
        h.postDelayed(() -> pump(c), 30);
    }

    private void onInfo(Conn c, byte[] v) {
        if (v == null) return;
        Hub.emit("onInfo", c.addr, new String(v, StandardCharsets.UTF_8));
    }

    // ---------- GATT 콜백 ----------
    private final class Cb extends BluetoothGattCallback {
        final Conn c;
        Cb(Conn c) { this.c = c; }

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                c.gatt = g;
                Hub.emit("onState", c.addr, "wait", c.name);
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                h.post(() -> { if (!g.requestMtu(517)) g.discoverServices(); });
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close(); } catch (Exception ignored) {}
                synchronized (c) { c.q.clear(); c.busy = false; }
                c.ready = false;
                if (c.gatt == g) c.gatt = null;
                Hub.emit("onState", c.addr, "offline", c.name);
                scheduleRetry(c);
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            g.discoverServices();
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { g.disconnect(); return; }
            c.cmd = s.getCharacteristic(CMD);
            c.info = s.getCharacteristic(INFO);
            if (c.cmd == null || c.info == null) { g.disconnect(); return; }
            BluetoothDevice dev = g.getDevice();
            String n = dev.getName();
            if (n != null) c.name = n;
            synchronized (c) {
                c.q.clear(); c.busy = false;
                c.q.add(new Op(OP_NOTIFY, null, true));
                c.q.add(new Op(OP_READ, null, true));
            }
            pump(c);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { done(c); }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) { writeDone(c, status); }

        // Android 13 이상
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value, int status) {
            handleRead(g, value, status);
        }

        // Android 12 이하
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (Build.VERSION.SDK_INT < 33) handleRead(g, ch.getValue(), status);
        }

        private void handleRead(BluetoothGatt g, byte[] value, int status) {
            // 동글 정보를 못 읽으면 '연결됨'으로 보지 않음 (예전엔 실패해도 연결됨으로 표시해서 버튼이 안 먹혔음)
            if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.length == 0) {
                BluetoothDevice dev = g.getDevice();
                if (bonded(dev)) { c.unbondTried = true; removeBond(dev); }
                Hub.emit("onConnProblem", c.addr, bonded(dev) ? "bond" : "read");
                synchronized (c) { c.busy = false; c.inflight = null; }
                try { g.disconnect(); } catch (Exception ignored) {}   // 끊기면 잠시 뒤 다시 연결함
                return;
            }
            onInfo(c, value);
            if (!c.ready) {
                c.ready = true;
                c.retries = 0;
                Hub.emit("onState", c.addr, "on", c.name);
            }
            done(c);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            if (INFO.equals(ch.getUuid())) onInfo(c, value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33 && INFO.equals(ch.getUuid())) onInfo(c, ch.getValue());
        }
    }
}
