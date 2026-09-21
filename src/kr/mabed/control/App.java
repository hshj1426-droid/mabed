package kr.mabed.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 앱 전체가 함께 쓰는 서버 · 이웃 폰 창구 · 기록 (화면을 닫았다 열어도 하나만 돈다) */
public class App {
    public static final int MAX_LOG = 300;

    private static BedServer SERVER;
    private static ApiServer API;
    private static LanPeers LAN;
    private static final List<String[]> LOG = new ArrayList<>();   // {시각, 종류, 내용}
    private static Runnable uiCallback;

    /** 침대 목록이 화면 밖(이웃 폰의 이름 바꾸기 등)에서 바뀌면 올라간다 */
    public static volatile int bedsRev = 0;

    /** 화면이 보이는 중인가 (짝짓기 요청을 받을지, 앱을 닫은 뒤 끌지 판단) */
    public static volatile boolean uiVisible = false;

    /** 다른 폰이 보낸 짝짓기 요청 — 창구 스레드가 기다리고, 화면이 '허용/거절'로 답한다 */
    public static class PairReq {
        public final String name, ip;
        public volatile boolean allowed = false, shown = false;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        PairReq(String n, String i) { name = n; ip = i; }
    }
    private static PairReq pairReq;
    private static long pairDeniedAt = 0;

    /** 이 폰이 다른 폰에 짝짓기를 요청하는 중인가 — 그동안엔 남의 요청을 받지 않는다 (서로 동시에 요청하면 열쇠가 엇갈린다) */
    private static boolean pairOut = false;
    static synchronized boolean pairOutStart() { if (pairOut || pairReq != null) return false; pairOut = true; return true; }
    static synchronized void pairOutEnd() { pairOut = false; }

    /** 한 번에 하나만. 거절한 직후 10초는 새 요청을 받지 않는다 (창을 계속 띄우는 장난 막기) */
    static synchronized PairReq pairAsk(String name, String ip) {
        if (pairReq != null || pairOut) return null;
        if (System.currentTimeMillis() - pairDeniedAt < 10000) return null;
        pairReq = new PairReq(name, ip);
        ping();
        return pairReq;
    }
    static synchronized PairReq pendingPair() { return pairReq; }
    static synchronized void pairAnswer(PairReq r, boolean ok) {
        r.allowed = ok;
        if (!ok) pairDeniedAt = System.currentTimeMillis();
        r.latch.countDown();
    }
    static synchronized void pairDone(PairReq r) { if (pairReq == r) pairReq = null; ping(); }

    public static synchronized BedServer server() {
        if (SERVER == null) {
            SERVER = new BedServer(new BedServer.Listener() {
                public void onLog(String kind, String text) { addLog(kind, text); }
                public void onDevices(List<BedServer.Dev> d) {
                    ping(); ServerService.devicesChanged();
                    Alarms.onDevices(appCtx, d);   // 새로 접속한 침대에 알람 설정을 넣어준다 (원래는 제조사 서버가 하던 일)
                }
            });
        }
        return SERVER;
    }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("mabed", Context.MODE_PRIVATE);
    }

    /** 이 폰의 고유 번호 (이웃 폰이 나를 구분하는 데 쓴다) */
    public static String phoneId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("phoneId", null);
        if (id == null) { id = Beds.newToken().substring(0, 8); p.edit().putString("phoneId", id).apply(); }
        return id;
    }

    public static String phoneName(Context c) {
        return prefs(c).getString("phoneName", android.os.Build.MODEL);
    }

    /** 이웃 폰 창구(9099)와 이웃 찾기(9098)를 한 번만 켠다 — 화면과 서비스 양쪽에서 부른다 */
    /** 앱 전체 Context — 침대가 접속할 때 저장된 알람을 읽으려고 */
    static volatile Context appCtx;

    public static synchronized void startNet(Context ctx) {
        final Context app = ctx.getApplicationContext();
        appCtx = app;
        if (API == null) {
            API = new ApiServer(new ApiServer.Host() {
                // 화면이 가진 목록이 아니라 저장된 목록을 매번 읽는다 — 화면이 새로 떠도 어긋나지 않게
                public List<Beds.Bed> beds() { return Beds.load(prefs(app)); }
                public String phoneName() { return App.phoneName(app); }
                public boolean rename(String token, String name) {
                    List<Beds.Bed> list = Beds.load(prefs(app));
                    boolean hit = false;
                    for (Beds.Bed b : list) if (b.token.equals(token)) { b.name = name; hit = true; }
                    if (hit) {
                        Beds.save(prefs(app), list);
                        addLog("이웃", "다른 폰이 침대 이름을 '" + name + "' 로 바꿨습니다");
                    }
                    return hit;
                }
                public String homeKey(boolean create) {
                    return create ? HomeKey.ensure(app) : HomeKey.get(app);
                }
                private boolean own(String token) {
                    if (token == null) return false;
                    for (Beds.Bed b : Beds.load(prefs(app))) if (b.token.equals(token)) return true;
                    return false;
                }
                public String alarms(String token) {
                    return own(token) ? Alarms.toJson(prefs(app), token).toString() : null;
                }
                public String alarmSet(String token, Map<String,String> op) {
                    if (!own(token)) return null;
                    if (!Alarms.ready(prefs(app), token)) throw new IllegalArgumentException("주인 폰에서 알람 시험을 먼저 해주세요");
                    Alarms.apply(prefs(app), token, op);
                    Alarms.push(app, server().byToken(token));   // 침대가 붙어 있으면 바로 넣는다 (아니면 다음 접속 때)
                    addLog("알람", "다른 폰이 알람을 바꿨습니다 · " + op.get("op"));
                    bedsRev++;                                   // 이 폰 화면도 다시 그리게
                    return Alarms.toJson(prefs(app), token).toString();
                }
            });
        }
        API.start();   // 이미 돌고 있으면 아무것도 안 한다. 포트 열기에 실패했었다면 다시 시도한다
        lan().start(app, phoneName(app), phoneId(app));
    }

    /** 앱을 닫고 한참 지나면 다른 폰 창구와 이웃 찾기를 끈다 (배터리) */
    public static synchronized void stopNet() {
        if (API != null) API.stop();
        if (LAN != null) LAN.stop();
        addLog("대기", "앱을 닫은 지 오래돼 연결을 모두 껐습니다");
    }

    public static synchronized LanPeers lan() {
        if (LAN == null) LAN = new LanPeers();
        return LAN;
    }

    public static synchronized void setCallback(Runnable r) { uiCallback = r; }

    static void ping() {
        Runnable r;
        synchronized (App.class) { r = uiCallback; }
        if (r != null) r.run();
    }

    /** 침대가 접속하며 보낸 것 중 뜻을 모르는 것 (동기화 요청·기기정보·모르는 명령) — 기록이 금방 밀려나도 남게 따로 모은다 */
    private static final java.util.LinkedHashSet<String> BED_HELLO = new java.util.LinkedHashSet<>();

    public static List<String> bedHello() {
        synchronized (BED_HELLO) { return new ArrayList<>(BED_HELLO); }
    }

    public static void addLog(String kind, String text) {
        if (kind.equals("동기화") || kind.equals("기기정보") || kind.startsWith("명령")
                || (kind.equals("받음") && !text.startsWith("V"))) {
            synchronized (BED_HELLO) {
                BED_HELLO.add(kind + " · " + text);
                while (BED_HELLO.size() > 40) BED_HELLO.remove(BED_HELLO.iterator().next());
            }
        }
        String t = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.KOREA).format(new java.util.Date());
        synchronized (LOG) {
            LOG.add(0, new String[]{t, kind, text});
            while (LOG.size() > MAX_LOG) LOG.remove(LOG.size()-1);
        }
        ping();
    }

    public static List<String[]> log() {
        synchronized (LOG) { return new ArrayList<>(LOG); }
    }
}
