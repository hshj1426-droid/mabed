package kr.mabed.control;

import android.content.Context;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 우리 집 폰끼리만 나눠 가진 '집 열쇠'.
 *  짝지은 폰끼리만 서로의 침대를 조작할 수 있게, 9099 창구로 오가는 요청마다 이 열쇠로 도장(HMAC)을 찍는다.
 *  열쇠 자체는 짝짓는 순간에만 한 번 오간다. */
public class HomeKey {

    /** 도장에 적힌 시각이 이만큼 넘게 차이 나면 거절 (옛 요청을 녹화했다 다시 보내는 것 막기) */
    public static final long MAX_SKEW_MS = 120_000;

    public static String get(Context c) { return App.prefs(c).getString("homeKey", ""); }

    public static boolean has(Context c) { return !get(c).isEmpty(); }

    /** 열쇠가 없으면 새로 만든다 (짝짓기를 허용할 때) */
    public static synchronized String ensure(Context c) {
        String k = get(c);
        if (!k.isEmpty()) return k;
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        k = hex(b);
        App.prefs(c).edit().putString("homeKey", k).apply();
        return k;
    }

    public static void set(Context c, String k) { App.prefs(c).edit().putString("homeKey", k).apply(); }

    /** 짝 풀기 — 열쇠를 버린다. 다음 짝짓기 때 새 열쇠가 생긴다 */
    public static void clear(Context c) { App.prefs(c).edit().remove("homeKey").apply(); }

    /** 열쇠에서 뽑은 짧은 표식 — 알림 신호에 실어서 같은 집 폰인지 알아본다 (열쇠는 알 수 없다) */
    public static String id(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(("mabed-home|" + key).getBytes("UTF-8"))).substring(0, 12);
        } catch (Exception e) { return ""; }
    }

    /** 도장 = HMAC-SHA256(열쇠, 요청 주소) */
    public static String sign(String key, String pathAndQuery) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            return hex(m.doFinal(pathAndQuery.getBytes("UTF-8")));
        } catch (Exception e) { return ""; }
    }

    /** 같은지 비교 — 걸리는 시간으로 도장을 알아내지 못하게 한 글자씩 끝까지 비교한다 */
    public static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        try { return MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8")); }
        catch (Exception e) { return false; }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
