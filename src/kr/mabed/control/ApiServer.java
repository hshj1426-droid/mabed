package kr.mabed.control;

import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** 다른 폰이 이 폰의 침대를 조작할 수 있게 열어두는 작은 창구 */
public class ApiServer {

    public static final int PORT = 9099;

    public interface Host {
        List<Beds.Bed> beds();
        String phoneName();
        /** 다른 폰이 이 폰에 등록된 침대 이름을 바꾼다 */
        boolean rename(String token, String name);
    }

    private ServerSocket server;
    private volatile boolean running;
    private final Host host;

    public ApiServer(Host h) { this.host = h; }

    public void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() { public void run() { loop(); } }).start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }

    private void loop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            App.addLog("이웃", "다른 폰 창구 열림 · 포트 " + PORT);
            while (running) {
                final Socket s = server.accept();
                new Thread(new Runnable() { public void run() { handle(s); } }).start();
            }
        } catch (Exception e) {
            if (running) App.addLog("오류", "이웃 창구 실패 · " + e.getMessage());
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(6000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = in.readLine();
            if (line == null) { s.close(); return; }
            String path = line.split(" ").length > 1 ? line.split(" ")[1] : "/";
            String body;
            if (path.startsWith("/state")) body = state();
            else if (path.startsWith("/cmd")) body = cmd(query(path));
            else if (path.startsWith("/rename")) body = rename(query(path));
            else body = "{\"ok\":false}";
            byte[] out = body.getBytes("UTF-8");
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Access-Control-Allow-Origin: *\r\nContent-Length: " + out.length + "\r\n\r\n").getBytes("UTF-8"));
            o.write(out); o.flush();
        } catch (Exception ignored) {
        } finally { try { s.close(); } catch (Exception ignored) {} }
    }

    private Map<String,String> query(String path) {
        Map<String,String> m = new HashMap<>();
        int q = path.indexOf('?');
        if (q < 0) return m;
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) try {
                m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                      URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }

    private String state() {
        try {
            JSONObject o = new JSONObject();
            o.put("phone", host.phoneName());
            JSONArray arr = new JSONArray();
            for (Beds.Bed b : host.beds()) {
                JSONObject j = new JSONObject();
                j.put("name", b.name);
                j.put("token", b.token);
                BedServer.Dev d = App.server().byToken(b.token);
                j.put("online", d != null);
                JSONObject pins = new JSONObject();
                JSONObject ages = new JSONObject();   // 각 값을 받은 지 몇 ms 됐는지 (폰끼리 시계가 달라서 나이로 보낸다)
                long now = System.currentTimeMillis();
                if (d != null) {
                    for (Map.Entry<String,String> e : d.pins.entrySet())
                        pins.put(e.getKey(), e.getValue());
                    for (Map.Entry<String,Long> e : d.pinAt.entrySet())
                        ages.put(e.getKey(), Math.max(0, now - e.getValue()));
                }
                j.put("pins", pins);
                j.put("age", ages);
                arr.put(j);
            }
            o.put("beds", arr);
            return o.toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    private String cmd(Map<String,String> q) {
        String token = q.get("token"), pin = q.get("pin"), val = q.get("val");
        if (token == null || pin == null) return "{\"ok\":false,\"msg\":\"부족\"}";
        BedServer.Dev d = App.server().byToken(token);
        if (d == null) return "{\"ok\":false,\"msg\":\"그 침대가 접속해 있지 않습니다\"}";
        boolean ok;
        if ("read".equals(val)) ok = App.server().read(d, pin);
        else ok = App.server().write(d, pin, val == null ? "1" : val);
        return "{\"ok\":" + ok + "}";
    }

    private String rename(Map<String,String> q) {
        String token = q.get("token"), name = q.get("name");
        if (token == null || name == null || name.trim().isEmpty()) return "{\"ok\":false}";
        String n = name.trim();
        if (n.length() > 30) n = n.substring(0, 30);
        return "{\"ok\":" + host.rename(token, n) + "}";
    }
}
