package kr.mabed.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** 침대 자체 알람 (원래 앱의 ALARM 1·2·3).
 *
 *  침대는 접속할 때마다 V1~V7, V12 값을 달라고 한다(5.9.3 에서 확인). 원래는 제조사 서버가 저장해 뒀다가 보내줬다.
 *    V1·V2·V3 = 알람 1·2·3 (Blynk Time Input: 시작·종료·요일), V5·V6·V7 = 켜기/끄기, V12 = 알람 때 상체 높이(모든 알람 공통).
 *  침대는 **시작 시각에 올라가고 종료 시각에 다시 내려간다** (5.11.0 확인). 기판 시계는 UTC (5.10.1 시험).
 *  알람은 기판이 스스로 실행하므로, 값을 넣어두면 폰이 꺼져 있어도 동작한다.
 *
 *  사용자 결정(5.12.0): 반복 알람 2개(V1·V2) + 빠른 알람 1개(V3). 다시 눕히기는 알람마다 따로.
 *  상대 침대 알람도 짝지은 폰에서 보고 바꿀 수 있다 — 설정은 주인 폰에 저장되고 주인 폰이 침대에 넣는다(ApiServer /alarms).
 *
 *  시계 방식을 모르면(알람 시험 전) 절대로 알람을 켜서 보내지 않는다 — 엉뚱한 시각에 침대가 움직이지 않게. */
public class Alarms {

    /** 반복 알람 개수 — 침대의 알람 1·2. 알람 3 자리는 빠른 알람 */
    public static final int COUNT = 2;
    /** 다시 눕히기 선택지(초). 0 = 안 눕힘 */
    public static final int[] DOWN_CHOICES = { 0, 300, 600, 1800, 3600 };
    public static final String[] DOWN_SHORT = { "안 함", "5분", "10분", "30분", "1시간" };
    public static final String[] DAY_NAMES = { "월", "화", "수", "목", "금", "토", "일" };

    /** 알람 하나 */
    public static class A {
        public boolean on = false;
        public int start = 7 * 3600;      // 하루 중 몇 초 (한국 시각)
        public int days = 0x7F;           // 비트: 월=1 … 일=64
        public int down = 0;              // 다시 눕히기 (초), 0 = 안 함
    }

    private static String k(String token, String n) { return "b_" + token + "_" + n; }

    /** 5.11.1 의 공통 '다시 눕히기' — 알람마다 따로가 되면서 각 알람의 처음 값으로 옮겨 쓴다 */
    private static int legacyDown(SharedPreferences p, String token) { return p.getInt(k(token, "alDown"), 0); }

    public static A get(SharedPreferences p, String token, int i) {
        A a = new A();
        a.on = p.getBoolean(k(token, "al" + i + "on"), false);
        a.start = p.getInt(k(token, "al" + i + "start"), a.start);
        a.days = p.getInt(k(token, "al" + i + "days"), a.days);
        a.down = p.getInt(k(token, "al" + i + "down"), legacyDown(p, token));
        return a;
    }

    public static void put(SharedPreferences p, String token, int i, A a) {
        p.edit().putBoolean(k(token, "al" + i + "on"), a.on).putInt(k(token, "al" + i + "start"), a.start)
                .putInt(k(token, "al" + i + "days"), a.days).putInt(k(token, "al" + i + "down"), a.down).apply();
    }

    /** 알람 때 상체 높이 (침대 값 0~80, 원래 앱 기본 50) — 침대에 자리가 하나라 모든 알람 공통 */
    public static int height(SharedPreferences p, String token) { return p.getInt(k(token, "alHeight"), 50); }
    public static void setHeight(SharedPreferences p, String token, int h) { p.edit().putInt(k(token, "alHeight"), h).apply(); }

    /** 기판 시계 방식: "" = 아직 시험 안 함, "local" = 한국 시각, "utc" = UTC, "none" = 시험에서 반응 없음 */
    public static String clock(SharedPreferences p, String token) { return p.getString(k(token, "alClock"), ""); }
    public static void setClock(SharedPreferences p, String token, String m) { p.edit().putString(k(token, "alClock"), m).apply(); }
    public static boolean ready(SharedPreferences p, String token) { return isReady(clock(p, token)); }
    static boolean isReady(String clock) { return "local".equals(clock) || "utc".equals(clock); }

    // ── 빠른 알람 ("30분 뒤") — 침대의 알람 3 자리 ─────────────
    // 침대 알람은 '매주 그 요일' 반복이라, 울린 뒤에 앱이 꺼야 한다. 너무 일찍 끄면 올라가기 전에,
    // 또는 다시 눕히기 전에 꺼져 버리므로 (다시 눕히기 또는 1분) + 1분 반 뒤에 끈다.
    // 울린 뒤 침대가 움직이며 다시 접속할 때나 앱을 열 때 꺼진다. (앱을 일주일 넘게 안 열면 한 번 더 울릴 수는 있다)

    public static long keepMs(int down) { return (down <= 0 ? 60 : down) * 1000L + 90000L; }

    public static int quickDown(SharedPreferences p, String token) { return p.getInt(k(token, "qaDown"), legacyDown(p, token)); }

    /** 빠른 알람 시각(밀리초). 없거나 다 끝났으면 0 */
    public static long quickAt(SharedPreferences p, String token) {
        long at = p.getLong(k(token, "qaAt"), 0);
        if (at != 0 && System.currentTimeMillis() > at + keepMs(quickDown(p, token))) {
            p.edit().remove(k(token, "qaAt")).apply();
            return 0;
        }
        return at;
    }

    public static void setQuick(SharedPreferences p, String token, long atMs) {
        if (atMs <= 0) p.edit().remove(k(token, "qaAt")).apply();
        else p.edit().putLong(k(token, "qaAt"), atMs).apply();
    }

    /** 빠른 알람을 침대 알람 한 칸 모양으로 (그 시각 · 그 요일 하루만) */
    static A quickAsAlarm(long atMs, int down) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(atMs);
        A a = new A();
        a.on = true;
        a.start = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
        a.days = 1 << ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7);     // 일=1…토=7 → 월=0…일=6
        a.down = down;
        return a;
    }

    // ── 화면·다른 폰과 주고받는 모양 ─────────────────────────
    /** 알람 전체 상태 (내 침대면 이 폰 저장소에서, 상대 침대면 주인 폰에서 받아온 것) */
    public static class State {
        public String clock = "";
        public int height = 50;
        public A[] al = new A[COUNT];
        public long quickAt = 0;          // 이 폰 시계 기준 밀리초, 0 = 없음
        public int quickDown = 0;
        public String error;              // 받아오기 실패 이유
        public boolean ready() { return isReady(clock); }
    }

    public static JSONObject toJson(SharedPreferences p, String token) {
        try {
            JSONObject o = new JSONObject();
            o.put("clock", clock(p, token));
            o.put("height", height(p, token));
            JSONArray arr = new JSONArray();
            for (int i = 1; i <= COUNT; i++) {
                A a = get(p, token, i);
                arr.put(new JSONObject().put("on", a.on).put("start", a.start).put("days", a.days).put("down", a.down));
            }
            o.put("alarms", arr);
            long at = quickAt(p, token);
            // 폰끼리 시계가 조금 다를 수 있어서 시각 대신 '남은 밀리초'로 보낸다 (지났으면 음수)
            o.put("quickLeft", at == 0 ? 0 : at - System.currentTimeMillis());
            o.put("quick", at != 0);
            o.put("quickDown", quickDown(p, token));
            return o;
        } catch (JSONException e) { return new JSONObject(); }
    }

    public static State fromJson(JSONObject o) {
        State s = new State();
        if (o == null) { s.error = "알람을 받아오지 못했습니다"; return s; }
        s.clock = o.optString("clock", "");
        s.height = o.optInt("height", 50);
        JSONArray arr = o.optJSONArray("alarms");
        for (int i = 0; i < COUNT; i++) {
            A a = new A();
            JSONObject j = arr == null ? null : arr.optJSONObject(i);
            if (j != null) {
                a.on = j.optBoolean("on", false); a.start = j.optInt("start", a.start);
                a.days = j.optInt("days", a.days); a.down = j.optInt("down", 0);
            }
            s.al[i] = a;
        }
        s.quickAt = o.optBoolean("quick", false) ? System.currentTimeMillis() + o.optLong("quickLeft", 0) : 0;
        s.quickDown = o.optInt("quickDown", 0);
        return s;
    }

    public static State local(SharedPreferences p, String token) { return fromJson(toJson(p, token)); }

    /** 알람 바꾸기 — 화면에서도, 다른 폰의 요청(/alarmset)에서도 이것 하나로. 잘못된 값이면 IllegalArgumentException
     *    op=alarm&i=1..2 [&on=0|1][&start=초][&days=비트][&down=초]
     *    op=height&v=0..80 · op=quick&min=1..1440[&down=초] · op=quickoff · op=quickdown&v=초 */
    public static void apply(SharedPreferences p, String token, Map<String, String> op) {
        String o = op.get("op");
        if (o == null) throw new IllegalArgumentException("op 없음");
        switch (o) {
            case "alarm": {
                int i = num(op, "i", 1, COUNT);
                A a = get(p, token, i);
                if (op.containsKey("on")) a.on = "1".equals(op.get("on")) || "true".equals(op.get("on"));
                if (op.containsKey("start")) a.start = num(op, "start", 0, 86399);
                if (op.containsKey("days")) a.days = num(op, "days", 0, 0x7F);
                if (op.containsKey("down")) a.down = downOf(num(op, "down", 0, 86400));
                if (a.on && a.days == 0) throw new IllegalArgumentException("요일을 하나 이상 골라주세요");
                put(p, token, i, a);
                break;
            }
            case "height": setHeight(p, token, num(op, "v", 0, 80)); break;
            case "quick": {
                int min = num(op, "min", 1, 1440);
                if (op.containsKey("down")) p.edit().putInt(k(token, "qaDown"), downOf(num(op, "down", 0, 86400))).apply();
                setQuick(p, token, System.currentTimeMillis() + min * 60000L);
                break;
            }
            case "quickoff": setQuick(p, token, 0); break;
            case "quickdown": p.edit().putInt(k(token, "qaDown"), downOf(num(op, "v", 0, 86400))).apply(); break;
            default: throw new IllegalArgumentException("모르는 op: " + o);
        }
    }

    private static int num(Map<String, String> op, String key, int min, int max) {
        try {
            int v = Integer.parseInt(op.get(key).trim());
            if (v < min || v > max) throw new IllegalArgumentException(key + " 범위 밖");
            return v;
        } catch (NumberFormatException | NullPointerException e) { throw new IllegalArgumentException(key + " 없음"); }
    }

    /** 선택지에 있는 값만 받는다 */
    private static int downOf(int v) {
        for (int c : DOWN_CHOICES) if (c == v) return v;
        throw new IllegalArgumentException("다시 눕히기 값이 이상합니다");
    }

    public static String downText(int down) {
        for (int x = 0; x < DOWN_CHOICES.length; x++)
            if (DOWN_CHOICES[x] == down) return down == 0 ? "올라간 채로" : DOWN_SHORT[x] + " 뒤 눕힘";
        return "";
    }

    // ── 침대에 넣기 ────────────────────────────────────
    /** 이 폰의 시간대 오프셋(초) — 한국 +32400 */
    public static int tzOffset() {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
    }

    /** 지금 하루 중 몇 초 (이 폰 시각) */
    public static int nowOfDay() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
    }

    /** Blynk Time Input 값: 시작초 \0 종료초 \0 시간대 \0 요일(1=월…7=일, 쉼표) \0 시간대오프셋초 */
    static String timeInput(int start, int stop, int days, String tz, int tzOff) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) { if (d.length() > 0) d.append(','); d.append(i + 1); }
        char z = 0;
        return start + "" + z + stop + z + tz + z + d + z + tzOff;
    }

    /** 요일 비트를 하루 앞/뒤로 돌린다 (UTC 로 바꾸다 날짜가 넘어갈 때) */
    static int rotate(int days, int shift) {
        if (shift == 0) return days;
        int out = 0;
        for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) out |= 1 << (((i + shift) % 7 + 7) % 7);
        return out;
    }

    /** 종료(= 다시 눕힐) 시각. '안 눕힘'이면 다음 날 시작 1분 전으로 — 그때는 보통 이미 누워 있어서 아무 일도 없다
     *  (종료값을 비우면 기판이 어떻게 읽을지 몰라서 쓰지 않는다) */
    static int stopFor(int start, int down) {
        return down <= 0 ? (start + 86400 - 60) % 86400 : (start + down) % 86400;
    }

    /** 사용자가 정한 알람(한국 시각)을 기판 시계 방식에 맞는 값으로 */
    static String valueFor(A a, String clock) {
        int off = tzOffset();
        if (clock.equals("utc")) {
            int s = a.start - off, shift = 0;
            if (s < 0) { s += 86400; shift = -1; } else if (s >= 86400) { s -= 86400; shift = 1; }
            return timeInput(s, stopFor(s, a.down), rotate(a.days, shift), "UTC", 0);
        }
        return timeInput(a.start, stopFor(a.start, a.down), a.days, TimeZone.getDefault().getID(), off);
    }

    /** 시험 알람이 켜진 채 남았을 수 있다는 표시 (시험 끝에 연결이 끊겨 못 껐을 때) */
    public static void markDirty(SharedPreferences p, String token, boolean on) {
        p.edit().putBoolean(k(token, "alDirty"), on).apply();
    }

    /** 이 침대에 알람 설정을 모두 보낸다 (접속할 때 · 바꿀 때). 시험 전이면 아무것도 켜지 않는다 */
    public static void push(Context c, BedServer.Dev d) {
        if (d == null) return;
        SharedPreferences p = App.prefs(c);
        BedServer s = App.server();
        String clock = clock(p, d.token);
        if (!ready(p, d.token)) {
            // 시계를 모른다 → 알람을 켜지 않는다. 다만 못 끈 시험 알람이 남아 있으면 끈다
            if (p.getBoolean(k(d.token, "alDirty"), false)
                    && s.write(d, "5", "0") && s.write(d, "6", "0") && s.write(d, "7", "0")) {
                markDirty(p, d.token, false);
                App.addLog("알람", "남아 있던 시험 알람을 껐습니다");
            }
            return;
        }
        markDirty(p, d.token, false);   // 아래에서 켜기/끄기를 모두 다시 쓴다
        for (int i = 1; i <= COUNT; i++) {
            A a = get(p, d.token, i);
            s.write(d, String.valueOf(i), valueFor(a, clock));
            s.write(d, String.valueOf(4 + i), a.on ? "1" : "0");
        }
        // 알람 3 자리 = 빠른 알람. 없거나 끝났으면 끈다 (울린 뒤 다시 접속할 때 여기서 꺼진다)
        long qa = quickAt(p, d.token);
        A q = qa != 0 ? quickAsAlarm(qa, quickDown(p, d.token)) : new A();
        s.write(d, "3", valueFor(q, clock));
        s.write(d, "7", qa != 0 ? "1" : "0");
        s.write(d, "12", String.valueOf(height(p, d.token)));
        App.addLog("알람", "침대에 알람 설정을 보냈습니다" + (qa != 0 ? " · 빠른 알람 " + hm(q.start) : ""));
    }

    // ── 글자 ──────────────────────────────────────────
    /** 사람이 읽는 한 줄: "07:00 · 평일 · 올라간 채로" */
    public static String describe(A a) {
        return hm(a.start) + " · " + daysText(a.days) + " · " + downText(a.down);
    }

    /** "오후 3:25" */
    public static String clockText(long atMs) {
        return new java.text.SimpleDateFormat("a h:mm", Locale.KOREA).format(new Date(atMs));
    }

    /** 남은 시간 "1시간 12분 뒤" · "12분 뒤" · "1분 안에" · "지금" */
    public static String leftText(long atMs) {
        long ms = atMs - System.currentTimeMillis();
        if (ms <= 0) return "지금";
        long m = (ms + 59999) / 60000;
        if (m <= 1) return "1분 안에";
        return minText((int) m) + " 뒤";
    }

    /** "1시간 45분" · "30분" · "2시간" */
    public static String minText(int m) {
        if (m < 60) return m + "분";
        return (m / 60) + "시간" + (m % 60 == 0 ? "" : " " + (m % 60) + "분");
    }

    public static String hm(int sec) {
        return String.format(Locale.KOREA, "%02d:%02d", (sec / 3600) % 24, (sec / 60) % 60);
    }

    public static String daysText(int days) {
        if (days == 0x7F) return "매일";
        if (days == 0x1F) return "평일";
        if (days == 0x60) return "주말";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) { if (sb.length() > 0) sb.append(' '); sb.append(DAY_NAMES[i]); }
        return sb.length() == 0 ? "요일 없음" : sb.toString();
    }

    // ── 접속할 때 자동으로 보내기 ────────────────────────────
    private static final Set<BedServer.Dev> pushed =
            Collections.newSetFromMap(new WeakHashMap<BedServer.Dev, Boolean>());

    /** 침대 목록이 바뀔 때마다 부른다 — 새로 접속한 침대에게 2초 뒤 알람 설정을 보낸다 */
    static void onDevices(final Context c, List<BedServer.Dev> devs) {
        if (c == null) return;
        for (final BedServer.Dev d : devs) {
            synchronized (pushed) { if (!pushed.add(d)) continue; }
            new Thread(new Runnable() { public void run() {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                try { push(c, d); } catch (Throwable ignored) {}
            }}).start();
        }
    }
}
