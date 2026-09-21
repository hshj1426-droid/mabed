package kr.mabed.control;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.*;
import java.net.*;

/** 깃허브에 새 버전이 올라왔는지 확인한다 */
public class Updates {

    /** 깃허브 저장소 — 형식: 아이디/저장소이름 */
    public static final String REPO = "hshj1426-droid/mabed";

    public static class Info {
        public String version = "";      // 예: 5.3.0
        public String url = "";          // APK 내려받을 주소
        public String notes = "";        // 무엇이 바뀌었는지
    }

    public interface Callback { void done(Info newer); }   // 새 버전이 없으면 null

    /** 지금 깔린 버전 */
    public static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) { return "0"; }
    }

    /** "5.10.0" 이 "5.9.0" 보다 크다고 제대로 판단한다 */
    public static boolean isNewer(String candidate, String current) {
        String[] a = clean(candidate).split("\\."), b = clean(current).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static String clean(String s) {
        if (s == null) return "0";
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    private static int num(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') break;
            n = n * 10 + (ch - '0');
        }
        return n;
    }

    /** 배경에서 확인하고 결과를 돌려준다 */
    public static void check(final Context c, final android.os.Handler ui, final Callback cb) {
        new Thread(new Runnable() { public void run() {
            Info found = null;
            try {
                String json = fetch("https://api.github.com/repos/" + REPO + "/releases/latest");
                String tag = field(json, "tag_name");
                if (tag != null && isNewer(tag, installed(c))) {
                    Info i = new Info();
                    i.version = clean(tag);
                    i.notes = field(json, "body");
                    i.url = apkUrl(json);
                    if (i.url == null || i.url.isEmpty())
                        i.url = "https://github.com/" + REPO + "/releases/latest";
                    found = i;
                }
            } catch (Throwable ignored) {
                // 인터넷이 없거나 저장소가 없으면 조용히 넘어간다
            }
            final Info out = found;
            ui.post(new Runnable() { public void run() { cb.done(out); } });
        }}).start();
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "mabed");
        try {
            if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /** json 라이브러리 없이 필요한 값만 꺼낸다 */
    private static String field(String json, String key) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            return o.optString(key, null);
        } catch (Throwable t) { return null; }
    }

    private static String apkUrl(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONArray a = o.optJSONArray("assets");
            if (a == null) return null;
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject x = a.getJSONObject(i);
                String n = x.optString("name", "");
                if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))
                    return x.optString("browser_download_url", null);
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
