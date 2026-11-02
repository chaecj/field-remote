package kr.chaeum.fieldremote;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 앱 안에서 새 버전 확인·설치 (GitHub Releases의 최신 APK) */
public final class Updater {
    private static final String LATEST = "https://api.github.com/repos/chaecj/field-remote/releases/latest";
    private static volatile boolean busy = false;

    private Updater() {}

    static long versionCode(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode(); }
        catch (Exception e) { return 0; }
    }

    /** 결과: onUpdate(state, latest, current, name, url) — state: available / latest / error */
    static void check(Context ctx) {
        new Thread(() -> {
            long cur = versionCode(ctx);
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                if (code != 200) { Hub.emit("onUpdate", "error", 0, cur, "서버 응답 " + code, ""); return; }
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                String tag = j.optString("tag_name", "");
                long latest = 0;
                try { latest = Long.parseLong(tag.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                String url = "";
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
                }
                Hub.emit("onUpdate", latest > cur && !url.isEmpty() ? "available" : "latest", latest, cur, j.optString("name", ""), url);
            } catch (Exception e) {
                Hub.emit("onUpdate", "error", 0, cur, "인터넷 연결 확인", "");
            }
        }).start();
    }

    /** 내려받아 설치 화면 띄우기. 진행: onUpdateProgress(percent), 실패: onUpdate("error", ...) */
    static void install(MainActivity act, String url) {
        if (busy) return;
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            // 처음 한 번: 이 앱이 업데이트를 설치할 수 있게 허용
            Hub.emit("onUpdate", "permission", 0, versionCode(act), "", url);
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        busy = true;
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("서버 응답 " + code);
                long len = c.getContentLengthLong();

                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(act.getPackageName());
                int id = pi.createSession(p);
                session = pi.openSession(id);
                try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("update.apk", 0, len > 0 ? len : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0; int lastPct = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (len > 0) {
                            int pct = (int) (done * 100 / len);
                            if (pct != lastPct) { lastPct = pct; Hub.emit("onUpdateProgress", pct); }
                        }
                    }
                    session.fsync(out);
                }
                Intent i = new Intent(act, InstallReceiver.class);
                PendingIntent pend = PendingIntent.getBroadcast(act, id, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pend.getIntentSender());
                session.close();
                Hub.emit("onUpdateProgress", 100);
            } catch (Exception e) {
                if (session != null) try { session.abandon(); } catch (Exception ignored) {}
                Hub.emit("onUpdate", "error", 0, versionCode(act), "내려받기 실패: " + e.getMessage(), url);
            } finally {
                busy = false;
            }
        }).start();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }

    /** 설치 결과 받기: 확인 창이 필요하면 띄움 */
    public static class InstallReceiver extends BroadcastReceiver {
        @SuppressWarnings("deprecation")
        @Override public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); } catch (Exception ignored) {}
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Hub.emit("onUpdate", "error", 0, 0, "설치 취소 또는 실패" + (msg != null ? ": " + msg : ""), "");
            }
        }
    }
}
