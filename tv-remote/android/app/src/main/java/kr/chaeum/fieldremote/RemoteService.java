package kr.chaeum.fieldremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * 앱을 내리거나 화면을 꺼도 동글 연결을 유지하는 상주 서비스.
 * 볼륨 버튼 클리커가 켜져 있으면, 화면이 꺼진 상태의 볼륨 버튼도 받는다.
 */
public class RemoteService extends Service {
    private static final String CH = "connection";
    private static RemoteService instance;
    private static boolean wantVolume;

    private MediaSession session;

    static void start(Context c) {
        Intent i = new Intent(c, RemoteService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {}
    }

    static void setVolumeMode(Context c, boolean on) {
        wantVolume = on;
        Hub.main.post(() -> {
            if (instance != null) { instance.applyVolume(); instance.notifyUpdate(); }
            else if (on && c != null) start(c);
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        Hub.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "연결 유지", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, n);
        } catch (Exception e) {
            stopSelf();
            return;
        }
        applyVolume();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (session != null) { session.release(); session = null; }
        instance = null;
        super.onDestroy();
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH);
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HW 리모컨")
            .setContentText(wantVolume ? "연결 유지 중 · 볼륨 버튼으로 넘기기 켬" : "동글 연결 유지 중")
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void notifyUpdate() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(1, build()); } catch (Exception ignored) {}
    }

    /** 원격 재생 세션을 열어 두면, 화면이 꺼져도 볼륨 버튼이 이 앱으로 온다 */
    private void applyVolume() {
        if (wantVolume && session == null) {
            session = new MediaSession(this, "FieldRemoteClicker");
            session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                @Override public void onAdjustVolume(int direction) {
                    if (direction < 0) Hub.onVolumeKey(true);
                    else if (direction > 0) Hub.onVolumeKey(false);
                }
            });
            session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build());
            session.setActive(true);
        } else if (!wantVolume && session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
    }
}
