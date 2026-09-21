package kr.mabed.control;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

/** 앱을 내려도 서버가 계속 돌도록 잡아두는 서비스 */
public class ServerService extends Service {

    public static final String CHANNEL = "mabed";
    private PowerManager.WakeLock lock;
    private WifiManager.WifiLock wifi;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "마베드 서버", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mabed:server");
        lock.setReferenceCounted(false);
        lock.acquire();

        // 화면이 꺼져도 와이파이가 잠들지 않게 붙잡아 둔다
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int mode = Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifi = wm.createWifiLock(mode, "mabed:wifi");
                wifi.setReferenceCounted(false);
                wifi.acquire();
            }
        } catch (Throwable ignored) {}
    }

    /** 알림줄 글자를 침대 연결 상태에 맞춘다 (예전엔 연결돼 있어도 늘 "기다리는 중") */
    private static volatile ServerService inst;
    private volatile String noteText = "";

    static void devicesChanged() {
        ServerService s = inst;
        if (s != null) s.updateNote();
    }

    private void updateNote() {
        try {
            int n = App.server().devices().size();
            String t = n > 0 ? "침대 " + n + "대 연결됨" : "침대를 기다리는 중입니다";
            if (t.equals(noteText)) return;
            noteText = t;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, buildNote(t));
        } catch (Throwable ignored) {}
    }

    private Notification buildNote(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);   // 화면이 겹쳐 뜨지 않게
        int pf = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("마베드 서버 켜짐")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)   // 예전엔 '배터리 부족' 아이콘이라 오해를 샀다
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        inst = this;
        noteText = "침대를 기다리는 중입니다";
        Notification n = buildNote(noteText);

        if (Build.VERSION.SDK_INT >= 29) {
            // connectedDevice 종류라야 안드로이드 15의 "하루 6시간" 제한에 걸리지 않는다
            startForeground(1, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
        App.server().start();
        // 재부팅 뒤 화면을 안 열어도 다른 폰이 이 폰의 침대를 조작할 수 있게
        try { App.startNet(this); } catch (Throwable ignored) {}
        getSharedPreferences("mabed", MODE_PRIVATE).edit().putBoolean("serverOn", true).apply();
        updateNote();
        return START_STICKY;
    }

    /** 혹시라도 시스템이 시간 제한을 걸면 터지지 말고 조용히 내려간다 */
    @Override public void onTimeout(int startId) {
        try { App.server().stop(); } catch (Throwable ignored) {}
        stopSelf();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        try { App.server().stop(); } catch (Throwable ignored) {}
        stopSelf();
    }

    @Override public void onDestroy() {
        inst = null;
        App.server().stop();
        if (lock != null && lock.isHeld()) lock.release();
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
