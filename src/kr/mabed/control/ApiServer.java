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
        /** 집 열쇠 (없으면 ""). create 면 없을 때 새로 만든다 */
        String homeKey(boolean create);
    }

    private ServerSocket server;
    private volatile boolean running;
    private final Host host;

    public ApiServer(Host h) { this.host = h; }

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() { public void run() { loop(); } }).start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    private void loop() {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            synchronized (this) {
                if (!running) { ss.close(); return; }   // 여는 사이 꺼졌다
                server = ss;
            }
            App.addLog("이웃", "다른 폰 창구 열림 · 포트 " + PORT);
            while (running) {
                final Socket s = ss.accept();
                new Thread(new Runnable() { public void run() { handle(s); } }).start();
            }
        } catch (Exception e) {
            synchronized (this) {
                // 끈 뒤 곧바로 다시 켰을 수 있다 — 지금 창구가 이 스레드의 것일 때만 '꺼짐'으로 돌린다
                if (running && (server == ss || server == null)) {
                    App.addLog("오류", "이웃 창구 실패 · " + e.getMessage());
                    running = false;   // 다음 startNet 때 다시 열 수 있게
                    server = null;
                }
            }
            try { if (ss != null) ss.close(); } catch (Exception ignored) {}
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(6000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = in.readLine();
            if (line == null) { s.close(); return; }
            String path = line.split(" ").length > 1 ? line.split(" ")[1] : "/";
            // 집 안 주소에서 온 것만 받는다
            String from = s.getInetAddress().getHostAddress();
            String body;
            if (!Net.isLan(from)) body = "{\"ok\":false}";
            else if (path.startsWith("/hello")) body = hello();
            else if (path.startsWith("/pair")) body = pair(query(path), from);
            else {
                // 나머지는 모두 집 열쇠 도장이 있어야 한다 (5.9.0 — 그 전엔 같은 와이파이의 누구나 조작 가능했다)
                String err = checkSig(path);
                if (err != null) body = "{\"ok\":false,\"auth\":false,\"msg\":" + JSONObject.quote(err) + "}";
                else if (path.startsWith("/state")) body = state();
                else if (path.startsWith("/cmd")) body = cmd(query(path));
                else if (path.startsWith("/rename")) body = rename(query(path));
                else body = "{\"ok\":false}";
            }
            byte[] out = body.getBytes("UTF-8");
            OutputStream o = s.getOutputStream();
            // 예전엔 'Access-Control-Allow-Origin: *' 를 붙여서, 집 안 컴퓨터의 브라우저로 연 아무 웹페이지나
            // 이 창구에 명령을 보낼 수 있었다 → 뺐다
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + out.length + "\r\n\r\n").getBytes("UTF-8"));
            o.write(out); o.flush();
        } catch (Exception ignored) {
        } finally { try { s.close(); } catch (Exception ignored) {} }
    }

    // ── 집 열쇠 도장 확인 ────────────────────────────────
    /** 최근에 받은 도장 — 같은 도장을 두 번 쓰면(녹화해서 다시 보내기) 거절 */
    private final Map<String, Long> seenSigs = new HashMap<>();

    /** 문제가 있으면 이유, 괜찮으면 null.
     *  요청 주소는 "...&ts=<시각>&sig=<도장>" 이고, 도장은 "&sig=" 앞부분 전체에 대한 HMAC 이다 */
    private String checkSig(String path) {
        String key = host.homeKey(false);
        if (key.isEmpty()) return "이 폰은 아직 짝지은 폰이 없습니다. 설정 → 다른 폰과 짝짓기";
        int i = path.lastIndexOf("&sig=");
        if (i < 0) return "짝짓기가 필요합니다 (상대 폰도 새 버전인지 확인)";
        String signed = path.substring(0, i), sig = path.substring(i + 5);
        if (!HomeKey.same(HomeKey.sign(key, signed), sig)) return "짝지은 폰이 아닙니다";
        long ts;
        try { ts = Long.parseLong(query(signed).get("ts")); } catch (Exception e) { return "시각이 없습니다"; }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > HomeKey.MAX_SKEW_MS) return "두 폰의 시계가 2분 넘게 다릅니다";
        // 상태 보기는 다시 보내져도 해가 없다 — 네트워크가 요청을 한 번 더 보낸 걸 공격으로 오해해
        // 상대 침대가 목록에서 깜빡 사라지지 않게, 재사용 검사는 침대를 바꾸는 요청에만
        if (path.startsWith("/state")) return null;
        synchronized (seenSigs) {
            for (Iterator<Map.Entry<String, Long>> it = seenSigs.entrySet().iterator(); it.hasNext(); )
                if (now - it.next().getValue() > 2 * HomeKey.MAX_SKEW_MS) it.remove();
            if (seenSigs.containsKey(sig)) return "이미 쓴 요청입니다";
            seenSigs.put(sig, now);
        }
        return null;
    }

    /** 누구나 볼 수 있는 인사 — 폰 이름과 짝 여부만 (침대 정보는 없다) */
    private String hello() {
        try {
            JSONObject o = new JSONObject();
            o.put("phone", host.phoneName());
            o.put("home", HomeKey.id(host.homeKey(false)));
            return o.toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    /** 짝짓기 요청 — 이 폰 화면에 '허용/거절' 을 띄우고 사람의 답을 기다린다 (최대 60초) */
    private String pair(Map<String,String> q, String from) {
        try {
            String name = q.get("name");
            if (name == null || name.trim().isEmpty()) name = "이름 없는 폰";
            name = name.trim();
            if (name.length() > 30) name = name.substring(0, 30);
            if (!App.uiVisible)
                return "{\"ok\":false,\"msg\":\"상대 폰에서 마베드 앱을 열어둔 채로 다시 시도해주세요\"}";
            App.PairReq r = App.pairAsk(name, from);
            if (r == null) return "{\"ok\":false,\"msg\":\"상대 폰도 지금 짝짓기를 요청하는 중이거나 다른 요청을 처리하는 중입니다. 한쪽에서만 눌러주세요\"}";
            boolean ok;
            try { ok = r.latch.await(60, java.util.concurrent.TimeUnit.SECONDS) && r.allowed; }
            finally { App.pairDone(r); }
            if (!ok) return "{\"ok\":false,\"msg\":\"상대 폰에서 허용하지 않았습니다\"}";
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("key", host.homeKey(true));
            o.put("phone", host.phoneName());
            App.addLog("짝", name + " (" + from + ") 와 짝지었습니다");
            return o.toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
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

    /** 침대 통신 스레드가 값을 넣는 순간과 겹치면 목록 읽기가 실패한다 — 몇 번 다시 시도한다 */
    private String state() {
        for (int tries = 0; tries < 5; tries++) {
            try { return stateOnce(); }
            catch (ConcurrentModificationException retry) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        }
        return "{\"ok\":false}";
    }

    private String stateOnce() {
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
        } catch (ConcurrentModificationException e) { throw e;          // 위에서 다시 시도
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
