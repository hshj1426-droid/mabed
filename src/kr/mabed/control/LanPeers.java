package kr.mabed.control;

import android.content.Context;
import android.net.wifi.WifiManager;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** 같은 와이파이에 있는 다른 폰을 서로 찾아내고 대화한다 */
public class LanPeers {

    public static final int UDP_PORT = 9098;

    public static class Peer {
        public String ip, phone = "";
        public String home = "";   // 그 폰의 집 열쇠 표식 (없으면 "" · 옛 버전이면 null)
        public long seen;          // 마지막으로 소식을 들은 때 (알림 신호 또는 상태 응답)
        int fails;                 // 연달아 상태 응답이 없었던 횟수
        String authMsg = "";       // 마지막으로 받은 거절 이유 (같은 이유를 기록에 반복해 남기지 않게)
        public final List<RemoteBed> beds = new ArrayList<>();
        /** 옛 버전(5.8.0 이하) 폰인가 — 짝짓기를 못 한다 */
        public boolean old() { return home == null; }
    }

    /** 이 폰의 집 열쇠 (없으면 "") */
    private String myKey() { return app == null ? "" : HomeKey.get(app); }

    /** 같은 집 열쇠를 가진 폰인가 */
    public boolean paired(Peer p) {
        String k = myKey();
        return !k.isEmpty() && p.home != null && p.home.equals(HomeKey.id(k));
    }

    public static class RemoteBed {
        public String name, token, peerIp;
        public String phone = "";            // 이 침대의 주인 폰 이름
        public boolean online;
        public final Map<String,String> pins = new LinkedHashMap<>();
        public final Map<String,Long> pinAt = new LinkedHashMap<>();   // 이 폰 시계 기준, 값을 받은 시각
    }

    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private volatile boolean running;
    private volatile String myName = "폰", myId = "";   // 화면에서 바꾸고 알림 스레드가 읽는다
    private WifiManager.MulticastLock mcast;
    private Context app;
    /** 화면을 보고 있는가 — 그때만 이웃 신호를 받고, 알림 신호도 자주 보낸다 */
    private volatile boolean listening = false;

    public synchronized void start(Context ctx, String name, String id) {
        myName = name; myId = id;
        if (ctx != null) app = ctx.getApplicationContext();
        if (running) return;
        running = true;
        final int g = ++gen;
        new Thread(new Runnable(){ public void run(){ listen(g); }}).start();
        new Thread(new Runnable(){ public void run(){ beacon(g); }}).start();
    }

    /** 끄고 다시 켤 때 예전 스레드가 같이 도는 일이 없게 — 켤 때마다 번호가 바뀌고, 옛 번호 스레드는 멈춘다 */
    private volatile int gen = 0;
    private DatagramSocket listenSock;

    /** 화면이 보일 때 true, 안 보일 때 false.
     *  MulticastLock 이 없으면 기종에 따라 브로드캐스트가 안 들어온다. 하지만 이걸 늘 잡고 있으면
     *  집 와이파이의 모든 방송 패킷에 폰이 깨어나 배터리를 쓴다 → 화면을 볼 때만 잡는다.
     *  (다른 폰을 찾는 건 화면을 보는 쪽이 할 일이다. 뒤에 있는 폰은 알림 신호만 보낸다) */
    public synchronized void setListening(boolean on) {
        listening = on;
        try {
            if (on) {
                if (mcast == null && app != null) {
                    WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        mcast = wm.createMulticastLock("mabed:peers");
                        mcast.setReferenceCounted(false);
                    }
                }
                if (mcast != null && !mcast.isHeld()) mcast.acquire();
            } else if (mcast != null && mcast.isHeld()) {
                mcast.release();
            }
        } catch (Throwable ignored) {}
    }

    /** 이웃에게 알릴 이 폰 이름을 바꾼다 */
    public void setName(String name) { if (name != null && !name.isEmpty()) myName = name; }

    public synchronized void stop() {
        running = false;
        gen++;
        try { if (listenSock != null) listenSock.close(); } catch (Throwable ignored) {}   // 받기 대기를 깨운다
        listenSock = null;
        try { if (mcast != null && mcast.isHeld()) mcast.release(); } catch (Throwable ignored) {}
        mcast = null;
    }

    /** 짝지은 폰만 (이 폰들의 침대만 조작할 수 있다) */
    public List<Peer> peers() {
        List<Peer> out = new ArrayList<>();
        for (Peer p : allPeers()) if (paired(p)) out.add(p);
        return out;
    }

    /** 같은 와이파이에 보이는 모든 마베드 폰 (짝짓기·넘겨주기 목록용) */
    public List<Peer> allPeers() {
        List<Peer> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (peers) {
            for (Peer p : peers.values()) if (now - p.seen < 30000) out.add(p);
        }
        return out;
    }

    public List<RemoteBed> remoteBeds() {
        List<RemoteBed> out = new ArrayList<>();
        for (Peer p : peers()) synchronized (p.beds) { out.addAll(p.beds); }
        return out;
    }

    private void beacon(int g) {
        while (running && g == gen) {
            try {
                DatagramSocket s = new DatagramSocket();
                s.setBroadcast(true);
                // MABED2|폰ID|집표식|이름 — 집표식은 열쇠에서 뽑은 짧은 값이라 열쇠는 알 수 없다
                byte[] msg = ("MABED2|" + myId + "|" + HomeKey.id(myKey()) + "|" + myName).getBytes("UTF-8");
                s.send(new DatagramPacket(msg, msg.length,
                        InetAddress.getByName("255.255.255.255"), UDP_PORT));
                s.close();
            } catch (Exception ignored) {}
            // 화면을 볼 때 4초, 뒤에 있을 때 10초 (다른 폰은 한 번 찾은 뒤엔 직접 물어보므로 자주 알릴 필요가 없다)
            try { Thread.sleep(listening ? 4000 : 10000); } catch (Exception ignored) {}
        }
    }

    private void listen(int g) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(UDP_PORT));
            s.setBroadcast(true);
            synchronized (this) {
                if (g != gen) { s.close(); return; }
                listenSock = s;
            }
            byte[] buf = new byte[512];
            while (running && g == gen) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                String id, home, name;
                if (msg.startsWith("MABED2|")) {
                    String[] parts = msg.split("\\|", 4);
                    if (parts.length < 4) continue;
                    id = parts[1]; home = parts[2]; name = parts[3];
                } else if (msg.startsWith("MABED|")) {             // 옛 버전 폰
                    String[] parts = msg.split("\\|", 3);
                    if (parts.length < 3) continue;
                    id = parts[1]; home = null; name = parts[2];
                } else continue;
                if (id.equals(myId)) continue;   // 내 것은 무시
                String ip = p.getAddress().getHostAddress();
                synchronized (peers) {
                    Peer pe = peers.get(ip);
                    if (pe == null) { pe = new Peer(); pe.ip = ip; peers.put(ip, pe); }
                    pe.phone = name;
                    pe.home = home;
                    pe.seen = System.currentTimeMillis();
                }
            }
        } catch (Exception ignored) {
        } finally { if (s != null) s.close(); }
    }

    /** 이웃 폰들의 침대 상태를 받아온다 */
    public void refresh() {
        // 한 번 찾은 폰은 알림 신호가 뜸해도(그 폰이 잠들어 있어도) 10분 동안은 직접 물어본다.
        // 직접 묻는 연결이 들어오면 그 폰이 깨어나 답한다. 연달아 3번 답이 없으면 신호가 다시 올 때까지 쉰다.
        List<Peer> known = new ArrayList<>();
        long now0 = System.currentTimeMillis();
        synchronized (peers) {
            for (Iterator<Peer> it = peers.values().iterator(); it.hasNext(); ) {
                Peer p = it.next();
                if (now0 - p.seen > 10 * 60 * 1000) { it.remove(); continue; }
                if (p.fails >= 3 && now0 - p.seen > 30000) continue;
                if (!paired(p)) { synchronized (p.beds) { p.beds.clear(); } continue; }   // 짝이 아니면 묻지 않는다
                known.add(p);
            }
        }
        for (final Peer p : known) {
            try {
                String json = http(signedUrl(p.ip, "/state"), 2500);
                JSONObject o = new JSONObject(json);
                if (o.has("auth") && !o.optBoolean("auth", true)) {
                    // 열쇠가 안 맞는다 (상대가 짝을 풀었거나, 두 폰 시계가 크게 다르다) — 이유가 바뀔 때만 기록
                    String why = o.optString("msg", "");
                    if (!why.equals(p.authMsg)) { p.authMsg = why; App.addLog("짝", p.phone + " 가 거절 · " + why); }
                    synchronized (p.beds) { p.beds.clear(); }
                    synchronized (peers) { p.fails++; }
                    continue;
                }
                p.authMsg = "";
                p.phone = o.optString("phone", p.phone);
                JSONArray a = o.optJSONArray("beds");
                List<RemoteBed> fresh = new ArrayList<>();
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    RemoteBed rb = new RemoteBed();
                    rb.name = j.optString("name", "침대");
                    rb.token = j.optString("token", "");
                    rb.online = j.optBoolean("online", false);
                    rb.peerIp = p.ip;
                    rb.phone = p.phone;
                    JSONObject pins = j.optJSONObject("pins");
                    if (pins != null) {
                        Iterator<String> it = pins.keys();
                        while (it.hasNext()) { String k = it.next(); rb.pins.put(k, pins.optString(k)); }
                    }
                    // 옛 버전 폰은 age 를 안 보낸다 — 그때는 방금 받은 값으로 친다
                    JSONObject age = j.optJSONObject("age");
                    long now = System.currentTimeMillis();
                    for (String k : rb.pins.keySet())
                        rb.pinAt.put(k, now - (age == null ? 0 : Math.max(0, age.optLong(k, 0))));
                    fresh.add(rb);
                }
                synchronized (p.beds) { p.beds.clear(); p.beds.addAll(fresh); }
                synchronized (peers) { p.seen = System.currentTimeMillis(); p.fails = 0; }
            } catch (Exception e) {
                synchronized (peers) { p.fails++; }
            }
        }
    }

    /** 이웃 폰을 거쳐 명령을 보낸다. 그 폰에 닿았고, 침대에도 전달됐으면 true */
    public boolean send(String peerIp, String token, String pin, String val) {
        try {
            String r = http(signedUrl(peerIp, "/cmd?token=" + enc(token)
                    + "&pin=" + enc(pin) + "&val=" + enc(val)), 3000);
            return new JSONObject(r).optBoolean("ok", false);
        } catch (Exception e) { return false; }
    }

    /** 이웃 폰에 등록된 침대 이름을 바꾼다 */
    public boolean rename(String peerIp, String token, String name) {
        try {
            String r = http(signedUrl(peerIp, "/rename?token=" + enc(token)
                    + "&name=" + enc(name)), 3000);
            return new JSONObject(r).optBoolean("ok", false);
        } catch (Exception e) { return false; }
    }

    /** 집 열쇠 도장을 찍은 주소: ...&ts=<지금>&sig=<앞부분 전체의 HMAC> */
    private String signedUrl(String ip, String path) {
        String base = path + (path.indexOf('?') < 0 ? "?" : "&") + "ts=" + System.currentTimeMillis();
        return "http://" + ip + ":" + ApiServer.PORT + base + "&sig=" + HomeKey.sign(myKey(), base);
    }

    /** 짝짓기 요청 — 상대 폰 화면에 '허용'이 뜨고, 허용하면 집 열쇠를 받아 이 폰에 저장한다.
     *  사람이 누를 때까지 최대 1분 기다린다. 돌려주는 값: null = 성공, 아니면 실패 이유 */
    public String pair(String ip) {
        // 내가 요청하는 동안에는 남의 요청을 받지 않는다 — 두 폰이 동시에 서로에게 요청해 둘 다 허용하면
        // 각자 만든 열쇠를 맞바꿔 가져서 짝이 영영 안 맞는 문제가 있었다
        if (!App.pairOutStart()) return "이 폰이 다른 짝짓기 요청을 처리하는 중입니다. 잠시 뒤 다시 해주세요";
        try {
            // 닿는지는 4초 안에 판단하고, 상대가 '허용'을 누를 때까지는 최대 70초 기다린다
            String r = http("http://" + ip + ":" + ApiServer.PORT + "/pair?name=" + enc(myName), 4000, 70000);
            JSONObject o = new JSONObject(r);
            if (!o.optBoolean("ok", false)) return o.optString("msg", "상대 폰이 옛 버전입니다. 상대 폰도 새 버전으로 올려주세요");
            String key = o.optString("key", "");
            if (key.length() < 32 || app == null) return "열쇠를 받지 못했습니다";
            HomeKey.set(app, key);
            App.addLog("짝", o.optString("phone", ip) + " 와 짝지었습니다");
            return null;
        } catch (java.net.SocketTimeoutException e) {
            return "상대 폰에 닿지 않거나 1분 안에 '허용'을 누르지 않았습니다";
        } catch (Exception e) {
            return "상대 폰에 닿지 않습니다. 같은 와이파이인지, 앱이 열려 있는지 확인해주세요";
        } finally {
            App.pairOutEnd();
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static String http(String url, int timeout) throws IOException { return http(url, timeout, timeout); }

    private static String http(String url, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs); c.setReadTimeout(readMs);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[2048]; int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
