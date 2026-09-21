package kr.mabed.control;

import java.util.Calendar;
import java.util.TimeZone;

/** 알람 값 형식 시험 (시간대 Asia/Seoul 로 돌릴 것 — run.sh 가 맞춘다) */
public class AlTest {
  static String show(String s) { return s.replace('\0', '|'); }
  static int fail = 0;
  static void eq(String what, String got, String want) {
    boolean ok = got.equals(want); if (!ok) fail++;
    System.out.println((ok ? "OK   " : "FAIL ") + what + "  " + got + (ok ? "" : "  (기대 " + want + ")"));
  }
  static long kst(int y, int mo, int d, int h, int mi) {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
    c.clear(); c.set(y, mo - 1, d, h, mi, 0); return c.getTimeInMillis();
  }
  public static void main(String[] a) {
    // 반복 알람 — 시작~종료는 1분 고정 (5.10.2)
    Alarms.A x = new Alarms.A(); x.start = 7*3600; x.days = 0x1F;                     // 평일 07:00
    eq("한국 시각 방식 · 평일 07:00", show(Alarms.valueFor(x, "local")), "25200|25260|Asia/Seoul|1,2,3,4,5|32400");
    eq("UTC 방식 · 07:00 KST = 전날 22:00 UTC → 요일 하루 앞으로(일~목)", show(Alarms.valueFor(x, "utc")), "79200|79260|UTC|1,2,3,4,7|0");
    Alarms.A y = new Alarms.A(); y.start = 23*3600+30*60; y.days = 1<<6;              // 일 23:30
    eq("UTC 방식 · 일 23:30 KST = 일 14:30 UTC (날짜 안 넘어감)", show(Alarms.valueFor(y, "utc")), "52200|52260|UTC|7|0");
    Alarms.A z = new Alarms.A(); z.start = 86370; z.days = 1;                          // 월 23:59:30 → 종료가 자정을 넘김
    eq("한국 시각 방식 · 종료가 자정을 넘김", show(Alarms.valueFor(z, "local")), "86370|30|Asia/Seoul|1|32400");
    eq("요일 글자", Alarms.daysText(0x1F) + "/" + Alarms.daysText(0x60) + "/" + Alarms.daysText(0x7F) + "/" + Alarms.daysText(1|4|64), "평일/주말/매일/월 수 일");

    // 빠른 알람 — 그 날 그 시각 하루만 (2026-09-21 은 월요일)
    eq("빠른 알람 · 월 23:30 KST → UTC 월 14:30",
       show(Alarms.valueFor(Alarms.quickAsAlarm(kst(2026, 9, 21, 23, 30)), "utc")), "52200|52260|UTC|1|0");
    eq("빠른 알람 · 화 07:00 KST → UTC 월 22:00 (요일도 월로)",
       show(Alarms.valueFor(Alarms.quickAsAlarm(kst(2026, 9, 22, 7, 0)), "utc")), "79200|79260|UTC|1|0");
    eq("빠른 알람 · 일 10:05 KST → UTC 일 01:05",
       show(Alarms.valueFor(Alarms.quickAsAlarm(kst(2026, 9, 27, 10, 5)), "utc")), "3900|3960|UTC|7|0");

    // 남은 시간 글자
    long now = System.currentTimeMillis();
    eq("남은 시간 · 90분", Alarms.leftText(now + 90 * 60000L), "1시간 30분 뒤");
    eq("남은 시간 · 2시간 정각", Alarms.leftText(now + 120 * 60000L), "2시간 뒤");
    eq("남은 시간 · 10분", Alarms.leftText(now + 10 * 60000L), "10분 뒤");
    eq("남은 시간 · 지남", Alarms.leftText(now - 1000), "지금");
    System.out.println(fail == 0 ? "모두 통과" : ("실패 " + fail)); System.exit(fail);
  }
}
