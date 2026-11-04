package kr.chaeum.fieldremote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/** 화면: 리모컨 HTML을 띄우고, 블루투스 권한과 볼륨 버튼을 처리 */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 10, REQ_ENABLE_BT = 11, REQ_DISCOVERABLE = 12, REQ_SHARE_PICK = 13, REQ_CAMERA = 14;

    private WebView web;
    private final List<Runnable> afterBluetooth = new ArrayList<>();
    private boolean asking = false;
    ScreenShare share;
    static MainActivity current;   // 앱 완전히 끄기에서 화면을 닫을 때 씀
    private boolean userKeepOn = false, shareKeepOn = false;
    private PermissionRequest pendingCam;   // 화면이 카메라를 달라고 했는데 권한을 묻는 중

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        Hub.init(this);

        Window w = getWindow();
        int bg = Color.parseColor("#0b111b");
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            // 터치패드 카메라 모드: 화면(getUserMedia)이 카메라를 쓰게 허락. 안드로이드 권한이 없으면 먼저 물어봄
            @Override public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean cam = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) cam = true;
                    if (!cam) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingCam = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }
        });
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(this), "Native");
        setContentView(web);

        Hub.setSink(js -> { if (web != null) web.evaluateJavascript(js, null); });
        web.loadUrl("file:///android_asset/index.html");

        // 화면 공유: TV가 미러링·케이블로 연결되면 TV에는 고른 파일만 보여 줌
        share = new ScreenShare(this);
        share.start();

        // 처음 실행: 권한 받고 연결 유지 서비스 시작
        withBluetooth(() -> RemoteService.start(this));
    }

    @Override protected void onResume() {
        super.onResume();
        // 앱으로 돌아오면 TV 화면이 닫혀 있는지 확인하고 다시 띄움
        if (share != null) share.refresh();
    }

    @Override protected void onDestroy() {
        Hub.setSink(null);
        if (current == this) current = null;
        if (share != null) { share.stop(); share = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ---------- 권한 ----------
    private String[] neededPerms() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String x : p) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) missing.add(x);
        return missing.toArray(new String[0]);
    }

    private boolean btPermsOk() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean btOn() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        return a != null && a.isEnabled();
    }

    /** 권한과 블루투스가 준비되면 실행 (안 되어 있으면 먼저 요청) */
    void withBluetooth(Runnable r) {
        runOnUiThread(() -> {
            if (btPermsOk() && btOn()) { r.run(); return; }
            afterBluetooth.add(r);
            if (asking) return;
            asking = true;
            String[] miss = neededPerms();
            if (miss.length > 0 && !btPermsOk()) requestPermissions(miss, REQ_PERMS);
            else askEnable();
        });
    }

    @SuppressLint("MissingPermission")
    private void askEnable() {
        if (btOn()) { flush(); return; }
        try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT); }
        catch (Exception e) { asking = false; afterBluetooth.clear(); Hub.emit("onPerm", false); }
    }

    private void flush() {
        asking = false;
        List<Runnable> list = new ArrayList<>(afterBluetooth);
        afterBluetooth.clear();
        for (Runnable r : list) r.run();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA) {
            PermissionRequest r = pendingCam; pendingCam = null;
            if (r != null) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                else r.deny();
            }
            return;
        }
        if (code != REQ_PERMS) return;
        if (btPermsOk()) askEnable();
        else { asking = false; afterBluetooth.clear(); Hub.emit("onPerm", false); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_SHARE_PICK) {
            if (result == RESULT_OK && data != null && share != null) share.onPicked(data);
            return;
        }
        if (code == REQ_ENABLE_BT) {
            if (btOn()) flush();
            else { asking = false; afterBluetooth.clear(); Hub.emit("onPerm", false); }
        }
    }

    @SuppressLint("MissingPermission")
    void requestDiscoverable() {
        withBluetooth(() -> {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            try { startActivityForResult(i, REQ_DISCOVERABLE); } catch (Exception ignored) {}
        });
    }

    void keepScreenOn(boolean on) { userKeepOn = on; applyKeepOn(); }

    /** TV에 화면 공유 중에는 휴대폰 화면이 꺼지지 않게 (꺼지면 TV 쪽도 끊김) */
    void setShareKeepOn(boolean on) { shareKeepOn = on; applyKeepOn(); }

    private void applyKeepOn() {
        runOnUiThread(() -> {
            if (userKeepOn || shareKeepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    void pickShareFiles() {
        runOnUiThread(() -> {
            try { startActivityForResult(ScreenShare.pickIntent(), REQ_SHARE_PICK); } catch (Exception ignored) {}
        });
    }

    /** 휴대폰의 화면 전송(스마트 뷰·미러링) 설정 열기. 없으면 false */
    boolean openCastSettings() {
        String[] actions = {"android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS"};
        for (String a : actions) {
            try { startActivity(new Intent(a)); return true; } catch (Exception ignored) {}
        }
        return false;
    }

    // ---------- 볼륨 버튼 / 뒤로 ----------
    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && Hub.volumeEnabled()) {
            if (e.getRepeatCount() == 0) Hub.onVolumeKey(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && Hub.volumeEnabled()) return true;
        return super.onKeyUp(keyCode, e);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.__nativeBack&&window.__nativeBack())?'1':'0'", v -> {
            // 화면을 닫지 않고 뒤로 보내서 연결과 화면 상태를 유지
            if (!"\"1\"".equals(v)) moveTaskToBack(true);
        });
    }
}
