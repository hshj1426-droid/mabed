package kr.mabed.control;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/** 새 버전을 앱이 직접 내려받아, 안드로이드 기본 설치 화면("업데이트할까요?")으로 넘긴다.
 *
 *  5.5.0~5.9.1 은 PackageInstaller 세션으로 앱이 스스로 설치했는데, 사용자 폰(갤럭시)에서
 *  "내려받는 중"에 멈춰 끝나지 않았다(원인 미확정 — 폰 로그 없음). 그래서 브라우저·파일 앱이 쓰는 것과 같은,
 *  그 폰에서 이미 잘 되는 방법(ACTION_VIEW + content:// 주소)으로 바꿨다. 매번 설치 화면에서 '업데이트'를 한 번 누른다. */
public class Updater {

    /** 내려받는 중인가 (같은 버전을 두 번 받지 않게) */
    static volatile boolean busy = false;

    /** 진행 상황 — 받은 바이트 / 전체 바이트(모르면 -1) */
    public interface Progress { void at(long done, long total); }

    /** 이 앱이 설치 화면을 열어도 되는지 — 폰 설정의 '이 출처 허용' */
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

    /** 내려받고 확인까지 — 반드시 배경 스레드에서. 실패하면 이유가 담긴 예외.
     *  어떤 오류든(Exception 이 아닌 Error 까지) 잡아서 알린다 — 조용히 죽어 화면이 멈춰 있는 일이 없게 */
    public static void download(Context ctx, Updates.Info n, Progress p) throws Exception {
        if (busy) throw new IOException("이미 내려받는 중입니다");
        busy = true;
        try {
            Context c = ctx.getApplicationContext();
            File f = ApkProvider.file(c);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("저장할 곳을 만들지 못했습니다");
            if (f.exists() && !f.delete()) throw new IOException("예전 파일을 지우지 못했습니다");
            App.addLog("업데이트", n.version + " 내려받기 시작");
            fetch(n.url, f, p);
            App.addLog("업데이트", n.version + " 받음 · " + (f.length() / 1024) + "KB");

            // 받은 파일이 정말 이 앱인지, 정말 새 버전인지 확인한다
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            if (pi == null || !c.getPackageName().equals(pi.packageName))
                throw new IOException("받은 파일이 마베드 앱이 아닙니다");
            if (!Updates.isNewer(pi.versionName, Updates.installed(c)))
                throw new IOException("받은 파일이 새 버전이 아닙니다 (" + pi.versionName + ")");
        } catch (Exception e) {
            App.addLog("업데이트", "실패 · " + e.getMessage());
            throw e;
        } catch (Throwable t) {
            App.addLog("업데이트", "실패 · " + t);
            throw new IOException("내려받다 멈췄습니다 (" + t.getClass().getSimpleName() + ")");
        } finally {
            busy = false;
        }
    }

    /** 안드로이드 기본 설치 화면을 여는 요청 */
    public static Intent installIntent() {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(ApkProvider.uri(), ApkProvider.MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static void fetch(String url, File out, Progress p) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);          // 깃허브는 다른 주소로 한 번 넘겨준다
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);                     // 20초 동안 한 바이트도 안 오면 멈춘 것으로 본다
        c.setRequestProperty("User-Agent", "mabed");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("내려받기 실패 · http " + code);
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream o = new FileOutputStream(out);
            long done = 0;
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    o.write(buf, 0, r);
                    done += r;
                    if (p != null) p.at(done, total);
                }
            } finally { o.close(); in.close(); }
            if (total > 0 && done != total) throw new IOException("받다가 끊겼습니다 (" + done + "/" + total + ")");
        } finally { c.disconnect(); }
        if (out.length() < 10000) throw new IOException("받은 파일이 너무 작습니다");
    }
}
