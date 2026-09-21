package kr.mabed.control;
public class AlTest {
  static String show(String s) { return s.replace('\0', '|'); }
  static int fail = 0;
  static void eq(String what, String got, String want) { boolean ok = got.equals(want); if (!ok) fail++; System.out.println((ok ? "OK   " : "FAIL ") + what + "  " + got + (ok ? "" : "  (기대 " + want + ")")); }
  public static void main(String[] a) {
    Alarms.A x = new Alarms.A(); x.start = 7*3600; x.dur = 180; x.days = 0x1F;            // 평일 07:00
    eq("한국 시각 방식 · 평일 07:00~07:03", show(Alarms.valueFor(x, "local")), "25200|25380|Asia/Seoul|1,2,3,4,5|32400");
    eq("UTC 방식 · 07:00 KST = 전날 22:00 UTC → 요일 하루 앞으로(일~목)", show(Alarms.valueFor(x, "utc")), "79200|79380|UTC|1,2,3,4,7|0");
    Alarms.A y = new Alarms.A(); y.start = 23*3600+30*60; y.dur = 600; y.days = 1<<6;    // 일 23:30
    eq("UTC 방식 · 일 23:30 KST = 일 14:30 UTC (날짜 안 넘어감)", show(Alarms.valueFor(y, "utc")), "52200|52800|UTC|7|0");
    Alarms.A z = new Alarms.A(); z.start = 23*3600+58*60; z.dur = 300; z.days = 1;       // 월 23:58, 5분 → 자정 넘김
    eq("한국 시각 방식 · 종료가 자정을 넘김", show(Alarms.valueFor(z, "local")), "86280|180|Asia/Seoul|1|32400");
    eq("요일 글자", Alarms.daysText(0x1F) + "/" + Alarms.daysText(0x60) + "/" + Alarms.daysText(0x7F) + "/" + Alarms.daysText(1|4|64), "평일/주말/매일/월 수 일");
    System.out.println(fail == 0 ? "모두 통과" : ("실패 " + fail)); System.exit(fail);
  }
}
