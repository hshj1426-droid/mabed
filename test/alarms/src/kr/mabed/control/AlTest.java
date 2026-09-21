package kr.mabed.control;

import java.util.Calendar;
import java.util.TimeZone;

/** 알람 값 형식 시험 (시간대 Asia/Seoul 로 돌릴 것 — run.sh 가 맞춘다)
 *  값: 시작초|종료초|시간대|요일|오프셋. 침대는 시작에 올라가고 종료에 다시 내려간다 (5.11.0 확인). */
public class AlTest {
  static String show(String s) { return s.replace('\0', '|'); }
  static int fail = 0;
  static void eq(String what, String got, String want) {
    boolean ok = got.equals(want); if (!ok) fail++;
    System.out.println((ok ? "OK   " : "FAIL ") + what + "  " + got + (ok ? "" : "  (기대 " + want + ")"));
  }
  static Alarms.A dn(Alarms.A a, int down) { Alarms.A b = new Alarms.A(); b.on = a.on; b.start = a.start; b.days = a.days; b.down = down; return b; }
  static long kst(int y, int mo, int d, int h, int mi) {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
    c.clear(); c.set(y, mo - 1, d, h, mi, 0); return c.getTimeInMillis();
  }
  public static void main(String[] a) {
    Alarms.A x = new Alarms.A(); x.start = 7*3600; x.days = 0x1F;                     // 평일 07:00
    eq("한국 시각 · 평일 07:00 · 안 눕힘(종료 = 다음 날 1분 전)", show(Alarms.valueFor(dn(x, 0), "local")), "25200|25140|Asia/Seoul|1,2,3,4,5|32400");
    eq("한국 시각 · 평일 07:00 · 10분 뒤 눕힘", show(Alarms.valueFor(dn(x, 600), "local")), "25200|25800|Asia/Seoul|1,2,3,4,5|32400");
    eq("UTC · 07:00 KST = 전날 22:00 UTC → 요일 하루 앞으로(일~목) · 안 눕힘", show(Alarms.valueFor(dn(x, 0), "utc")), "79200|79140|UTC|1,2,3,4,7|0");
    eq("UTC · 07:00 KST · 1시간 뒤 눕힘 → 23:00 UTC", show(Alarms.valueFor(dn(x, 3600), "utc")), "79200|82800|UTC|1,2,3,4,7|0");
    Alarms.A y = new Alarms.A(); y.start = 23*3600+30*60; y.days = 1<<6;              // 일 23:30
    eq("UTC · 일 23:30 KST = 일 14:30 UTC · 30분 뒤 눕힘", show(Alarms.valueFor(dn(y, 1800), "utc")), "52200|54000|UTC|7|0");
    Alarms.A z = new Alarms.A(); z.start = 86370; z.days = 1;                          // 월 23:59:30
    eq("한국 시각 · 눕힐 시각이 자정을 넘김", show(Alarms.valueFor(dn(z, 300), "local")), "86370|270|Asia/Seoul|1|32400");
    Alarms.A w = new Alarms.A(); w.start = 30; w.days = 1;                             // 월 00:00:30
    eq("한국 시각 · 안 눕힘 · 시작이 자정 직후(종료가 전날로)", show(Alarms.valueFor(dn(w, 0), "local")), "30|86370|Asia/Seoul|1|32400");
    eq("요일 글자", Alarms.daysText(0x1F) + "/" + Alarms.daysText(0x60) + "/" + Alarms.daysText(0x7F) + "/" + Alarms.daysText(1|4|64), "평일/주말/매일/월 수 일");

    // 빠른 알람 — 그 날 그 시각 하루만 (2026-09-21 은 월요일)
    eq("빠른 알람 · 월 23:30 KST → UTC 월 14:30 · 안 눕힘",
       show(Alarms.valueFor(Alarms.quickAsAlarm(kst(2026, 9, 21, 23, 30), 0), "utc")), "52200|52140|UTC|1|0");
    eq("빠른 알람 · 화 07:00 KST → UTC 월 22:00 (요일도 월로) · 5분 뒤 눕힘",
       show(Alarms.valueFor(Alarms.quickAsAlarm(kst(2026, 9, 22, 7, 0), 300), "utc")), "79200|79500|UTC|1|0");
    eq("빠른 알람 · 일 10:05 KST → UTC 일 01:05 · 안 눕힘",
       show(Alarms.valueFor(Alarms.quickAsAlarm(kst(2026, 9, 27, 10, 5), 0), "utc")), "3900|3840|UTC|7|0");

    long now = System.currentTimeMillis();
    eq("남은 시간 · 90분", Alarms.leftText(now + 90 * 60000L), "1시간 30분 뒤");
    eq("남은 시간 · 2시간 정각", Alarms.leftText(now + 120 * 60000L), "2시간 뒤");
    eq("남은 시간 · 10분", Alarms.leftText(now + 10 * 60000L), "10분 뒤");
    eq("남은 시간 · 지남", Alarms.leftText(now - 1000), "지금");
    eq("다시 눕히기 글자", Alarms.downText(0) + "/" + Alarms.downText(600) + "/" + Alarms.downText(3600), "올라간 채로/10분 뒤 눕힘/1시간 뒤 눕힘");
    eq("빠른 알람 버튼 글자", Alarms.minText(105) + "/" + Alarms.minText(30) + "/" + Alarms.minText(120), "1시간 45분/30분/2시간");
    System.out.println(fail == 0 ? "모두 통과" : ("실패 " + fail)); System.exit(fail);
  }
}
