package kr.chaeum.fieldremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

/** 앱 전체에서 하나만 있는 연결 허브: 동글 연결, 휴대폰 키보드, 볼륨 버튼 클리커, 화면(WebView)으로 이벤트 전달 */
public final class Hub {
    public interface Sink { void run(String js); }

    private static Context app;
    static BleManager ble;
    static HidKeyboard hid;
    static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Sink sink;
    private static volatile JSONObject volumeConfig;
    private static long lastVolume = 0;

    private Hub() {}

    public static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        ble = new BleManager(app);
        hid = new HidKeyboard(app);
    }

    public static void setSink(Sink s) { sink = s; }

    /** window.__native.<fn>(args...) 호출 */
    public static void emit(String fn, Object... args) {
        StringBuilder sb = new StringBuilder("window.__native&&window.__native.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            Object a = args[i];
            if (a instanceof Number || a instanceof Boolean) sb.append(a);
            else sb.append(JSONObject.quote(a == null ? "" : a.toString()));
        }
        sb.append(")");
        final String js = sb.toString();
        main.post(() -> { Sink s = sink; if (s != null) s.run(js); });
    }

    // ---------- 볼륨 버튼 클리커 ----------
    public static void setVolumeConfig(String json) {
        JSONObject cfg = null;
        try { if (json != null && !json.isEmpty()) cfg = new JSONObject(json); } catch (Exception ignored) {}
        volumeConfig = cfg;
        RemoteService.setVolumeMode(app, cfg != null);
    }

    public static boolean volumeEnabled() { return volumeConfig != null; }

    /** 볼륨 키. down=true 이면 '볼륨 아래' 버튼 */
    public static boolean onVolumeKey(boolean down) {
        JSONObject cfg = volumeConfig;
        if (cfg == null) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastVolume < 250) return true; // 길게 누를 때 연속 입력 막기
        lastVolume = now;
        boolean next = down != cfg.optBoolean("swap", false);
        JSONArray list = cfg.optJSONArray(next ? "next" : "prev");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                if (t == null) continue;
                String id = t.optString("id");
                JSONArray pk = t.optJSONArray("pk");
                if (pk == null) continue;
                for (int j = 0; j < pk.length(); j++) {
                    JSONArray p = pk.optJSONArray(j);
                    if (p == null) continue;
                    byte[] bytes = Base64.decode(p.optString(0), Base64.DEFAULT);
                    boolean reliable = p.optBoolean(1, false);
                    if ("phone-kb".equals(id)) hid.send(bytes);
                    else ble.write(id, bytes, reliable);
                }
            }
        }
        emit("onVolumeKey", next ? 1 : -1);
        return true;
    }
}
