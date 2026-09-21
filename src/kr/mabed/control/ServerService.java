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
        inst = this;
        applyLocks();
    }

    // ── 앱을 닫으면 5분 뒤 모두 끈다 (배터리) ─────────────────
    // 앱을 닫아도 계속 대기하던 것을 사용자 요청으로 바꿨다. 설정 "앱을 닫아도 대기"(stayOn)를 켜면 예전처럼 계속 돈다.
    // 바로 끄지 않고 5분 기다리는 건 잠깐 다른 앱(와이파이 설정 등)에 다녀올 때 끊기지 않게 하려는 것.
    static final long GRACE_MS = 5L * 60 * 1000;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile long stopAt = 0;     // 0 이면 끌 예정 없음 (elapsedRealtime 기준 — 폰이 잠든 시간도 센다)

    public static boolean stayOn(Context c) { return App.prefs(c).getBoolean("stayOn", false); }

    /** 화면이 안 보이게 됐을 때 (앱을 닫음 · 홈으로 · 최근 앱에서 지움) */
    static void appClosed() {
        ServerService s = inst;
        if (s != null) s.armStop();
    }

    /** 화면이 다시 보일 때 */
    static void appOpened() {
        ServerService s = inst;
        if (s != null) { s.stopAt = 0; s.h.removeCallbacks(s.stopCheck); }
    }

    private void armStop() {
        if (stayOn(this)) return;
        stopAt = android.os.SystemClock.elapsedRealtime() + GRACE_MS;
        h.removeCallbacks(stopCheck);
        h.postDelayed(stopCheck, 30000);
    }

    /** 30초마다 시각을 본다. 폰이 잠들어 있으면 이 확인도 멈추지만, 깨어나는 즉시 지난 시간을 따져 끈다 */
    private final Runnable stopCheck = new Runnable() { public void run() {
        if (stopAt == 0 || App.uiVisible || stayOn(ServerService.this)) { stopAt = 0; return; }
        if (android.os.SystemClock.elapsedRealtime() < stopAt) { h.postDelayed(this, 30000); return; }
        App.stopNet();
        getSharedPreferences("mabed", MODE_PRIVATE).edit().putBoolean("serverOn", false).apply();
        stopSelf();       // onDestroy 에서 침대 서버도 끈다 → 알림줄에서 사라진다
    }};

    @Override public void onTaskRemoved(Intent rootIntent) {
        // 최근 앱 목록에서 쓸어서 지웠다 — 앱을 닫은 것과 같다
        if (!App.uiVisible) armStop();
        super.onTaskRemoved(rootIntent);
    }

    /** '연결 유지 강화'(설정)를 켰을 때만 폰을 깨워두고 와이파이 절전을 막는다.
     *  5.6.2 까지는 늘 잡고 있어서 배터리를 계속 썼다 (사용자 요청으로 기본 끔).
     *  끈 상태에서도 앞쪽 서비스(알림줄)라서 네트워크는 살아 있고, 침대가 보내는 신호가 오면 폰이 깨어난다.
     *  화면이 꺼진 뒤 침대가 반응하지 않으면 이걸 켜면 예전처럼 동작한다. */
    public static boolean keepAwake(Context c) { return App.prefs(c).getBoolean("keepAwake", false); }

    /** 설정을 바꾼 뒤 부른다 */
    static void locksChanged() {
        ServerService s = inst;
        if (s != null) s.applyLocks();
    }

    private synchronized void applyLocks() {
        boolean on = keepAwake(this);
        try {
            if (on) {
                if (lock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mabed:server");
                    lock.setReferenceCounted(false);
                }
                if (!lock.isHeld()) lock.acquire();
                if (wifi == null) {
                    WifiManager wm = (WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        int mode = Build.VERSION.SDK_INT >= 29
                                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                        wifi = wm.createWifiLock(mode, "mabed:wifi");
                        wifi.setReferenceCounted(false);
                    }
                }
                if (wifi != null && !wifi.isHeld()) wifi.acquire();
            } else {
                if (lock != null && lock.isHeld()) lock.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
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
        // 화면 없이 켜졌다(재부팅 등) — '앱을 닫아도 대기'가 꺼져 있으면 5분 뒤 끈다
        if (!App.uiVisible) armStop();
        // '앱을 닫아도 대기'일 때만 시스템이 멈춘 서비스를 되살린다
        return stayOn(this) ? START_STICKY : START_NOT_STICKY;
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
        h.removeCallbacks(stopCheck);
        App.server().stop();
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
