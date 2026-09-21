package kr.mabed.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** 침대 자체 알람 (원래 앱의 ALARM 1·2·3).
 *
 *  침대는 접속할 때마다 V1~V7, V12 값을 달라고 한다(5.9.3 에서 확인). 원래는 제조사 서버가 저장해 뒀다가 보내줬다.
 *    V1·V2·V3 = 알람 1·2·3 시각 (Blynk Time Input 형식), V5·V6·V7 = 알람 1·2·3 켜기/끄기, V12 = 알람 때 상체 높이.
 *    (V4 = 타이머로 추정 — 아직 쓰지 않는다)
 *  알람은 기판이 스스로 시계를 보고 실행하므로, 값을 넣어두면 폰이 꺼져 있어도 동작한다.
 *
 *  기판 시계가 한국 시각인지 UTC 인지는 모른다 → 처음 한 번 '알람 시험'으로 정한다 (clock = local / utc / none).
 *  시험 전(또는 반응 없음)에는 절대로 알람을 켜서 보내지 않는다 — 엉뚱한 시각에 침대가 움직이지 않게. */
public class Alarms {

    /** 반복 알람 개수 — 침대의 알람 1·2. (알람 3 자리는 빠른 알람이 쓴다, 5.10.2) */
    public static final int COUNT = 2;
    /** 침대 알람은 시작 시각에 올라가고, **종료 시각에 다시 내려간다** (5.11.0 사용자 확인 — 1분 뒤 내려감).
     *  그래서 종료 = '다시 눕히기' 시각이다. 사용자가 고른다: 0 = 안 눕힘(기본), 아니면 몇 초 뒤. */
    public static final int[] DOWN_CHOICES = { 0, 300, 600, 1800, 3600 };
    public static final String[] DOWN_NAMES = { "안 함", "5분 뒤", "10분 뒤", "30분 뒤", "1시간 뒤" };

    /** 알람 뒤 다시 눕히기 (초). 0 = 안 눕힘 */
    public static int down(SharedPreferences p, String token) { return p.getInt(k(token, "alDown"), 0); }
    public static void setDown(SharedPreferences p, String token, int sec) { p.edit().putInt(k(token, "alDown"), sec).apply(); }

    /** 종료(= 다시 눕힐) 시각. '안 눕힘'이면 다음 날 시작 1분 전으로 — 그때는 보통 이미 누워 있어서 아무 일도 없다 */
    static int stopFor(int start, int down) {
        return down <= 0 ? (start + 86400 - 60) % 86400 : (start + down) % 86400;
    }
    public static final String[] DAY_NAMES = { "월", "화", "수", "목", "금", "토", "일" };

    /** 알람 하나 */
    public static class A {
        public boolean on = false;
        public int start = 7 * 3600;      // 하루 중 몇 초 (한국 시각)
        public int days = 0x7F;           // 비트: 월=1 … 일=64
    }

    // ── 빠른 알람 ("30분 뒤") — 침대의 알람 3 자리를 쓴다 ─────────────
    // 침대 알람은 '매주 그 요일' 반복이라, 울린 뒤에 앱이 꺼야 한다.
    // 울린 뒤 침대가 움직이며 다시 접속하면 push 가 그 자리를 끈다. (앱을 일주일 넘게 안 열면 한 번 더 울릴 수는 있다)

    /** 울린 뒤 이만큼은 켜 둔다 — 너무 일찍 끄면 올라가기 전에, 또는 '다시 눕히기' 전에 꺼버린다.
     *  안 눕힘이면 올라간 뒤 2분 반, 눕힘이면 눕힐 시각 뒤 1분 반 */
    static long quickKeep(SharedPreferences p, String token) {
        int d = down(p, token);
        return (d <= 0 ? 60 : d) * 1000L + 90000L;
    }

    /** 빠른 알람 시각(밀리초). 없거나, 다 끝났으면 0 */
    public static long quickAt(SharedPreferences p, String token) {
        long at = p.getLong(k(token, "qaAt"), 0);
        if (at != 0 && System.currentTimeMillis() > at + quickKeep(p, token)) { p.edit().remove(k(token, "qaAt")).apply(); return 0; }
        return at;
    }

    public static void setQuick(SharedPreferences p, String token, long atMs) {
        if (atMs <= 0) p.edit().remove(k(token, "qaAt")).apply();
        else p.edit().putLong(k(token, "qaAt"), atMs).apply();
    }

    /** 빠른 알람을 침대 알람 한 칸 모양으로 (그 시각 · 그 요일 하루만) */
    static A quickAsAlarm(long atMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(atMs);
        A a = new A();
        a.on = true;
        a.start = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
        a.days = 1 << ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7);     // 일=1…토=7 → 월=0…일=6
        return a;
    }

    private static String k(String token, String n) { return "b_" + token + "_" + n; }

    public static A get(SharedPreferences p, String token, int i) {
        A a = new A();
        a.on = p.getBoolean(k(token, "al" + i + "on"), false);
        a.start = p.getInt(k(token, "al" + i + "start"), a.start);
        a.days = p.getInt(k(token, "al" + i + "days"), a.days);
        return a;
    }

    public static void put(SharedPreferences p, String token, int i, A a) {
        p.edit().putBoolean(k(token, "al" + i + "on"), a.on).putInt(k(token, "al" + i + "start"), a.start)
                .putInt(k(token, "al" + i + "days"), a.days).apply();
    }

    /** 알람 때 상체 높이 (침대 값 0~80, 원래 앱 기본 50) */
    public static int height(SharedPreferences p, String token) { return p.getInt(k(token, "alHeight"), 50); }
    public static void setHeight(SharedPreferences p, String token, int h) { p.edit().putInt(k(token, "alHeight"), h).apply(); }

    /** 기판 시계 방식: "" = 아직 시험 안 함, "local" = 한국 시각, "utc" = UTC, "none" = 시험에서 반응 없음 */
    public static String clock(SharedPreferences p, String token) { return p.getString(k(token, "alClock"), ""); }
    public static void setClock(SharedPreferences p, String token, String m) { p.edit().putString(k(token, "alClock"), m).apply(); }
    public static boolean ready(SharedPreferences p, String token) {
        String m = clock(p, token);
        return m.equals("local") || m.equals("utc");
    }

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

    /** 사용자가 정한 알람(한국 시각)을 기판 시계 방식에 맞는 값으로. down = 다시 눕히기(초, 0 = 안 함) */
    static String valueFor(A a, String clock, int down) {
        int off = tzOffset();
        if (clock.equals("utc")) {
            int s = a.start - off, shift = 0;
            if (s < 0) { s += 86400; shift = -1; } else if (s >= 86400) { s -= 86400; shift = 1; }
            return timeInput(s, stopFor(s, down), rotate(a.days, shift), "UTC", 0);
        }
        return timeInput(a.start, stopFor(a.start, down), a.days, TimeZone.getDefault().getID(), off);
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
        int down = down(p, d.token);
        for (int i = 1; i <= COUNT; i++) {
            A a = get(p, d.token, i);
            s.write(d, String.valueOf(i), valueFor(a, clock, down));
            s.write(d, String.valueOf(4 + i), a.on ? "1" : "0");
        }
        // 알람 3 자리 = 빠른 알람. 없거나 이미 지났으면 끈다 (울린 뒤 다시 접속할 때 여기서 꺼진다)
        long qa = quickAt(p, d.token);
        A q = qa != 0 ? quickAsAlarm(qa) : new A();
        s.write(d, "3", valueFor(q, clock, down));
        s.write(d, "7", qa != 0 ? "1" : "0");
        s.write(d, "12", String.valueOf(height(p, d.token)));
        App.addLog("알람", "침대에 알람 설정을 보냈습니다" + (qa != 0 ? " · 빠른 알람 " + hm(q.start) : ""));
    }

    /** 사람이 읽는 한 줄: "07:00 · 월 화 수 목 금" */
    public static String describe(A a) {
        return hm(a.start) + " · " + daysText(a.days);
    }

    /** "오후 3:25" */
    public static String clockText(long atMs) {
        return new java.text.SimpleDateFormat("a h:mm", Locale.KOREA).format(new Date(atMs));
    }

    /** 남은 시간 "1시간 12분 뒤" · "12분 뒤" · "1분 안에" */
    public static String leftText(long atMs) {
        long ms = atMs - System.currentTimeMillis();
        if (ms <= 0) return "지금";
        long m = (ms + 59999) / 60000;
        if (m <= 1) return "1분 안에";
        if (m < 60) return m + "분 뒤";
        return (m / 60) + "시간" + (m % 60 == 0 ? "" : " " + (m % 60) + "분") + " 뒤";
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
