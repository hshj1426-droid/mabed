package kr.mabed.control;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** 등록된 침대 목록. 침대마다 고유한 인증키를 가진다. */
public class Beds {

    public static class Bed {
        public String name, token;
        public Bed(String n, String t) { name = n; token = t; }
    }

    public static List<Bed> load(SharedPreferences p) {
        List<Bed> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("beds", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Bed(o.optString("name", "침대"), o.optString("token", "")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(SharedPreferences p, List<Bed> beds) {
        JSONArray a = new JSONArray();
        for (Bed b : beds) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", b.name);
                o.put("token", b.token);
                a.put(o);
            } catch (Exception ignored) {}
        }
        p.edit().putString("beds", a.toString()).apply();
    }

    public static String newToken() {
        String hex = "0123456789abcdef";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(hex.charAt(r.nextInt(16)));
        return sb.toString();
    }

    /** 침대별 설정값 키 (자세 저장, 테이블 유무 등) */
    public static String key(Bed b, String name) {
        return "b_" + (b == null ? "none" : b.token) + "_" + name;
    }
}
