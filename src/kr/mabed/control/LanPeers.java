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
        public long seen;
        public final List<RemoteBed> beds = new ArrayList<>();
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

    public void start(Context ctx, String name, String id) {
        myName = name; myId = id;
        if (running) return;
        running = true;
        // 이게 없으면 기종에 따라 브로드캐스트가 아예 안 들어온다
        try {
            if (ctx != null) {
                WifiManager wm = (WifiManager) ctx.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    mcast = wm.createMulticastLock("mabed:peers");
                    mcast.setReferenceCounted(false);
                    mcast.acquire();
                }
            }
        } catch (Throwable ignored) {}
        new Thread(new Runnable(){ public void run(){ listen(); }}).start();
        new Thread(new Runnable(){ public void run(){ beacon(); }}).start();
    }

    /** 이웃에게 알릴 이 폰 이름을 바꾼다 */
    public void setName(String name) { if (name != null && !name.isEmpty()) myName = name; }

    public void stop() {
        running = false;
        try { if (mcast != null && mcast.isHeld()) mcast.release(); } catch (Throwable ignored) {}
        mcast = null;
    }

    public List<Peer> peers() {
        List<Peer> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (peers) {
            for (Peer p : peers.values()) if (now - p.seen < 20000) out.add(p);
        }
        return out;
    }

    public List<RemoteBed> remoteBeds() {
        List<RemoteBed> out = new ArrayList<>();
        for (Peer p : peers()) synchronized (p.beds) { out.addAll(p.beds); }
        return out;
    }

    private void beacon() {
        while (running) {
            try {
                DatagramSocket s = new DatagramSocket();
                s.setBroadcast(true);
                byte[] msg = ("MABED|" + myId + "|" + myName).getBytes("UTF-8");
                s.send(new DatagramPacket(msg, msg.length,
                        InetAddress.getByName("255.255.255.255"), UDP_PORT));
                s.close();
            } catch (Exception ignored) {}
            try { Thread.sleep(4000); } catch (Exception ignored) {}
        }
    }

    private void listen() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(UDP_PORT));
            s.setBroadcast(true);
            byte[] buf = new byte[512];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (!msg.startsWith("MABED|")) continue;
                String[] parts = msg.split("\\|", 3);
                if (parts.length < 3 || parts[1].equals(myId)) continue;   // 내 것은 무시
                String ip = p.getAddress().getHostAddress();
                synchronized (peers) {
                    Peer pe = peers.get(ip);
                    if (pe == null) { pe = new Peer(); pe.ip = ip; peers.put(ip, pe); }
                    pe.phone = parts[2];
                    pe.seen = System.currentTimeMillis();
                }
            }
        } catch (Exception ignored) {
        } finally { if (s != null) s.close(); }
    }

    /** 이웃 폰들의 침대 상태를 받아온다 */
    public void refresh() {
        for (final Peer p : peers()) {
            try {
                String json = http("http://" + p.ip + ":" + ApiServer.PORT + "/state", 2500);
                JSONObject o = new JSONObject(json);
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
            } catch (Exception ignored) {}
        }
    }

    /** 이웃 폰을 거쳐 명령을 보낸다. 그 폰에 닿았고, 침대에도 전달됐으면 true */
    public boolean send(String peerIp, String token, String pin, String val) {
        try {
            String r = http("http://" + peerIp + ":" + ApiServer.PORT + "/cmd?token=" + enc(token)
                    + "&pin=" + enc(pin) + "&val=" + enc(val), 3000);
            return new JSONObject(r).optBoolean("ok", true);
        } catch (Exception e) { return false; }
    }

    /** 이웃 폰에 등록된 침대 이름을 바꾼다 */
    public boolean rename(String peerIp, String token, String name) {
        try {
            String r = http("http://" + peerIp + ":" + ApiServer.PORT + "/rename?token=" + enc(token)
                    + "&name=" + enc(name), 3000);
            return new JSONObject(r).optBoolean("ok", false);
        } catch (Exception e) { return false; }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static String http(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[2048]; int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
