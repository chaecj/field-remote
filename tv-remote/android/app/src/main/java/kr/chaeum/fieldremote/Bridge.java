package kr.chaeum.fieldremote;

import android.util.Base64;
import android.webkit.JavascriptInterface;

/** 화면(HTML)에서 window.Native 로 부르는 기능 */
public final class Bridge {
    private final MainActivity act;

    Bridge(MainActivity act) { this.act = act; }

    @JavascriptInterface public boolean isAvailable() { return true; }

    @JavascriptInterface public void ready() {
        Hub.ble.dumpStates();
        Hub.hid.emitState();
    }

    // 동글
    @JavascriptInterface public void scan() { act.withBluetooth(() -> Hub.ble.scan()); }
    @JavascriptInterface public void stopScan() { Hub.ble.stopScan(); }
    @JavascriptInterface public void connect(String addr) { act.withBluetooth(() -> Hub.ble.connect(addr)); }
    @JavascriptInterface public void disconnect(String addr) { Hub.ble.disconnect(addr); }
    @JavascriptInterface public void write(String addr, String b64, boolean reliable) {
        Hub.ble.write(addr, Base64.decode(b64, Base64.DEFAULT), reliable);
    }
    @JavascriptInterface public int pending(String addr) { return Hub.ble.pending(addr); }

    // 휴대폰을 블루투스 키보드로
    @JavascriptInterface public void hidStart() { act.withBluetooth(() -> Hub.hid.start()); }
    @JavascriptInterface public void hidStop() { Hub.hid.stop(); }
    @JavascriptInterface public void hidDiscoverable() { act.requestDiscoverable(); }
    @JavascriptInterface public String hidBonded() { return Hub.hid.bonded(); }
    @JavascriptInterface public void hidConnect(String addr) { Hub.hid.connect(addr); }
    @JavascriptInterface public void hidSend(String b64) { Hub.hid.send(Base64.decode(b64, Base64.DEFAULT)); }

    // 볼륨 버튼 클리커 / 화면
    @JavascriptInterface public void setVolumeConfig(String json) { Hub.setVolumeConfig(json); }
    @JavascriptInterface public void keepScreenOn(boolean on) { act.keepScreenOn(on); }
    // 앱 업데이트
    @JavascriptInterface public void checkUpdate() { Updater.check(act); }
    @JavascriptInterface public void installUpdate(String url) { Updater.install(act, url); }
    @JavascriptInterface public long versionCode() { return Updater.versionCode(act); }

    @JavascriptInterface public String appVersion() {
        try { return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }
}
