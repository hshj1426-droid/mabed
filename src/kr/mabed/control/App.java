package kr.mabed.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

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

    /** 화면이 보이는 중인가 (설치 확인 창을 바로 띄울지, 알림으로 부탁할지) */
    public static volatile boolean uiVisible = false;

    /** 뒤에 있을 때 도착한 '설치 확인' 창 — 알림이 막혀 있어도 앱을 열면 이어서 띄운다 */
    private static android.content.Intent pendingConfirm;
    static synchronized void keepConfirm(android.content.Intent i) { pendingConfirm = i; }
    static synchronized android.content.Intent takeConfirm() {
        android.content.Intent i = pendingConfirm; pendingConfirm = null; return i;
    }
    public static synchronized BedServer server() {
        if (SERVER == null) {
            SERVER = new BedServer(new BedServer.Listener() {
                public void onLog(String kind, String text) { addLog(kind, text); }
                public void onDevices(List<BedServer.Dev> d) { ping(); }
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
    public static synchronized void startNet(Context ctx) {
        final Context app = ctx.getApplicationContext();
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
            });
        }
        API.start();   // 이미 돌고 있으면 아무것도 안 한다. 포트 열기에 실패했었다면 다시 시도한다
        lan().start(app, phoneName(app), phoneId(app));
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

    public static void addLog(String kind, String text) {
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
