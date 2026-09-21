package kr.mabed.control;

import java.util.ArrayList;
import java.util.List;

/** 앱 전체가 함께 쓰는 서버 한 개와 기록 */
public class App {
    public static final int MAX_LOG = 300;

    private static BedServer SERVER;
    private static final List<String[]> LOG = new ArrayList<>();   // {시각, 종류, 내용}
    private static Runnable uiCallback;

    public static synchronized BedServer server() {
        if (SERVER == null) {
            SERVER = new BedServer(new BedServer.Listener() {
                public void onLog(String kind, String text) { addLog(kind, text); }
                public void onDevices(List<BedServer.Dev> d) { ping(); }
            });
        }
        return SERVER;
    }

    public static synchronized void setCallback(Runnable r) { uiCallback = r; }

    private static void ping() {
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
