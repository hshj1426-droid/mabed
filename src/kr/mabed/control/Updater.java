package kr.mabed.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/** 새 버전을 앱이 직접 내려받아 설치한다 (브라우저를 거치지 않는다).
 *  안드로이드 규칙: 처음 한 번은 사람이 '설치'를 눌러야 한다.
 *  이 앱이 스스로 설치한 다음부터는(안드로이드 12 이상) 묻지 않고 조용히 설치된다. */
public class Updater {

    static final String ACTION_RESULT = "kr.mabed.control.INSTALL_RESULT";
    static final String CH_UPDATE = "mabed_update";

    /** 설치가 진행 중인가 (같은 버전을 두 번 받지 않게) */
    static volatile boolean busy = false;

    /** 이 앱이 다른 앱(자기 자신 포함)을 설치해도 되는지 — 폰 설정의 '이 출처 허용' */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try { return c.getPackageManager().canRequestPackageInstalls(); }
        catch (Throwable t) { return false; }
    }

    /** '이 출처 허용' 설정 화면 */
    public static Intent permIntent(Context c) {
        return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + c.getPackageName()));
    }

    /** 내려받을 APK 주소가 있는가 (없으면 브라우저로 릴리스 페이지를 연다) */
    public static boolean hasApk(Updates.Info n) {
        return n != null && n.url != null && n.url.toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
    }

    /** 내려받아 설치를 시작한다 — 반드시 배경 스레드에서 부를 것 */
    public static void downloadAndInstall(Context ctx, Updates.Info n) throws Exception {
        if (busy) throw new IOException("이미 설치를 진행하고 있습니다");
        busy = true;
        try {
            Context c = ctx.getApplicationContext();
            File f = new File(c.getCacheDir(), "update.apk");
            App.addLog("업데이트", n.version + " 내려받는 중");
            download(n.url, f);

            // 받은 파일이 정말 이 앱인지, 정말 새 버전인지 확인한다
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            if (pi == null || !c.getPackageName().equals(pi.packageName))
                throw new IOException("받은 파일이 마베드 앱이 아닙니다");
            if (!Updates.isNewer(pi.versionName, Updates.installed(c)))
                throw new IOException("받은 파일이 새 버전이 아닙니다 (" + pi.versionName + ")");

            install(c, f);
            App.addLog("업데이트", n.version + " 설치를 시작했습니다");
        } catch (Exception e) {
            busy = false;
            App.addLog("업데이트", "실패 · " + e.getMessage());
            throw e;
        }
    }

    private static void download(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);          // 깃허브는 다른 주소로 한 번 넘겨준다
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "mabed");
        try {
            if (c.getResponseCode() != 200) throw new IOException("내려받기 실패 · http " + c.getResponseCode());
            InputStream in = c.getInputStream();
            OutputStream o = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            } finally { o.close(); in.close(); }
        } finally { c.disconnect(); }
        if (out.length() < 10000) throw new IOException("받은 파일이 너무 작습니다");
    }

    private static void install(Context c, File f) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        // 안드로이드 12+: 이 앱이 스스로 설치한 적이 있으면 묻지 않고 설치된다
        if (Build.VERSION.SDK_INT >= 31)
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream o = s.openWrite("mabed.apk", 0, f.length());
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                s.fsync(o);
            } finally { in.close(); o.close(); }

            Intent i = new Intent(c, InstallReceiver.class).setAction(ACTION_RESULT);
            // 시스템이 결과를 채워 넣어야 하므로 MUTABLE (31 이상)
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(pend.getIntentSender());
        } finally { s.close(); }
    }

    /** 업데이트 알림 (폰 알림줄) */
    static void alert(Context c, String title, String text, PendingIntent tap) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new NotificationChannel(
                        CH_UPDATE, "마베드 업데이트", NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH_UPDATE) : new Notification.Builder(c);
            b.setContentTitle(title).setContentText(text)
             .setSmallIcon(R.drawable.ic_stat).setAutoCancel(true);
            if (tap != null) b.setContentIntent(tap);
            nm.notify(2, b.build());
        } catch (Throwable ignored) {}
    }

    /** 앱을 여는 알림용 PendingIntent */
    static PendingIntent openApp(Context c) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class), f);
    }

    // ── 자동 업데이트 ────────────────────────────────────
    private static final long CHECK_EVERY = 6L * 60 * 60 * 1000;     // 6시간마다 확인
    private static final long QUIET_FOR   = 30L * 60 * 1000;         // 30분 동안 조작이 없을 때만 설치

    public static boolean autoOn(SharedPreferences p) { return p.getBoolean("autoUpd", true); }

    /** 서버 서비스가 30분마다 부른다. 조건이 맞으면 확인하고 설치까지 한다 */
    public static void autoTick(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        final SharedPreferences p = App.prefs(c);
        if (!autoOn(p) || busy) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("autoCheckedAt", 0) < CHECK_EVERY) return;
        if (now - App.lastCmdAt < QUIET_FOR) return;     // 누가 침대를 쓰는 중일 수 있다 — 다음에

        Updates.check(c, new Handler(Looper.getMainLooper()), new Updates.Callback() {
            public void done(final Updates.Info n) {
                p.edit().putLong("autoCheckedAt", System.currentTimeMillis()).apply();
                if (n == null || !hasApk(n)) return;
                if (!canInstall(c)) {
                    // 설치 허용을 아직 안 받았다 — 같은 버전은 한 번만 알린다
                    if (!n.version.equals(p.getString("notifiedVer", ""))) {
                        p.edit().putString("notifiedVer", n.version).apply();
                        alert(c, "마베드 새 버전 " + n.version,
                                "앱을 열어 설정 → 새 버전 확인을 눌러주세요", openApp(c));
                    }
                    return;
                }
                new Thread(new Runnable() { public void run() {
                    try { downloadAndInstall(c, n); } catch (Exception ignored) {}
                }}).start();
            } });
    }
}
