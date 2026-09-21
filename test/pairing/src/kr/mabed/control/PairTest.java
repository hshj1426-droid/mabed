package kr.mabed.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/** 9099 짝짓기·도장 통합 시험 — 실제 ApiServer / LanPeers / HomeKey / BedServer 를 PC 에서 돌린다 */
public class PairTest {

    static int pass = 0, fail = 0;
    static void check(String what, boolean ok, Object detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  → " + detail); }
    }

    // ── 가짜 저장소·Context ──
    static class Prefs implements SharedPreferences {
        final Map<String,Object> m = Collections.synchronizedMap(new HashMap<String,Object>());
        public String getString(String k, String d) { Object v = m.get(k); return v == null ? d : (String) v; }
        public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v == null ? d : (Boolean) v; }
        public int getInt(String k, int d) { Object v = m.get(k); return v == null ? d : (Integer) v; }
        public long getLong(String k, long d) { Object v = m.get(k); return v == null ? d : (Long) v; }
        public boolean contains(String k) { return m.containsKey(k); }
        public Editor edit() {
            return new Editor() {
                public Editor putString(String k, String v) { m.put(k, v); return this; }
                public Editor putBoolean(String k, boolean v) { m.put(k, v); return this; }
                public Editor putInt(String k, int v) { m.put(k, v); return this; }
                public Editor putLong(String k, long v) { m.put(k, v); return this; }
                public Editor remove(String k) { m.remove(k); return this; }
                public void apply() {}
                public boolean commit() { return true; }
            };
        }
    }
    static class Ctx extends Context {
        final Prefs p = new Prefs();
        public Context getApplicationContext() { return this; }
        public SharedPreferences getSharedPreferences(String n, int mode) { return p; }
    }

    static String lanIp() throws Exception {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses()))
                if (a instanceof Inet4Address && Net.isLan(a.getHostAddress())) return a.getHostAddress();
        }
        return null;
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            lastHeaders = c.getHeaderFields();
            return new String(in.readAllBytes(), "UTF-8");
        } finally { c.disconnect(); }
    }
    static Map<String, List<String>> lastHeaders;

    static Object call(Object o, String m, Class<?>[] t, Object... a) throws Exception {
        Method x = o.getClass().getDeclaredMethod(m, t); x.setAccessible(true); return x.invoke(o, a);
    }
    static void setField(Object o, String f, Object v) throws Exception {
        Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v);
    }
    static Object field(Object o, String f) throws Exception {
        Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o);
    }

    /** 가짜 이웃 폰(클라이언트) — 실제 LanPeers, 저장소만 따로 */
    static LanPeers client(Ctx c, String name) throws Exception {
        LanPeers l = new LanPeers();
        setField(l, "app", c);
        setField(l, "myName", name);
        return l;
    }

    /** 클라이언트가 서버를 '짝지은 이웃'으로 알게 한다 (실제로는 알림 신호로 들어오는 값) */
    @SuppressWarnings("unchecked")
    static void addPeer(LanPeers l, String ip, String home) throws Exception {
        Map<String, LanPeers.Peer> peers = (Map<String, LanPeers.Peer>) field(l, "peers");
        LanPeers.Peer p = new LanPeers.Peer();
        p.ip = ip; p.phone = "서버폰"; p.home = home; p.seen = System.currentTimeMillis();
        synchronized (peers) { peers.put(ip, p); }
    }

    static String home(String base) throws Exception {
        return new org.json.JSONObject(get(base + "/hello")).optString("home", "?");
    }

    /** 이 프로그램 = '요청하는 폰' + 가짜 침대. 서버 폰은 PairServer 로 따로 띄운다 (실제로 다른 폰이니까) */
    public static void main(String[] a) throws Exception {
        String ip = lanIp();
        System.out.println("PC 집 안 주소: " + ip);
        if (ip == null) { System.out.println("집 안 주소가 없어 시험 불가"); System.exit(2); }
        String base = "http://" + ip + ":" + ApiServer.PORT;
        final String TOKEN = "0123456789abcdef0123456789abcdef";
        File dir = new File(a[0]);
        new File(dir, "busy.flag").delete(); new File(dir, "closed.flag").delete();

        ProcessBuilder pb = new ProcessBuilder(a[1], "-Dstdout.encoding=UTF-8", "-cp", a[2],
                "kr.mabed.control.PairServer", dir.getAbsolutePath(), TOKEN);
        pb.redirectErrorStream(true);
        Process srv = pb.start();
        BufferedReader so = new BufferedReader(new InputStreamReader(srv.getInputStream(), "UTF-8"));
        String line;
        while ((line = so.readLine()) != null && !line.contains("READY")) System.out.println("  [서버] " + line);
        if (line == null) { System.out.println("서버 폰을 띄우지 못함"); System.exit(2); }
        try {
            run(ip, base, TOKEN, dir);
        } finally { srv.destroyForcibly(); }
        System.out.println("\n결과: 통과 " + pass + " · 실패 " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run(String ip, String base, String TOKEN, File dir) throws Exception {
        // 가짜 침대: 서버 폰의 8080 으로 HW_LOGIN(29)
        Socket bed = new Socket(ip, BedServer.PORT);
        bed.setSoTimeout(4000);
        byte[] tk = TOKEN.getBytes("UTF-8");
        OutputStream bo = bed.getOutputStream();
        bo.write(new byte[]{29, 0, 1, 0, (byte) tk.length}); bo.write(tk); bo.flush();
        DataInputStream bi = new DataInputStream(bed.getInputStream());
        byte[] h = new byte[5]; bi.readFully(h);
        check("가짜 침대 로그인 응답 200", (h[0] == 0) && (((h[3] & 0xFF) << 8 | (h[4] & 0xFF)) == 200), Arrays.toString(h));

        System.out.println("\n[1] 짝짓기 전");
        String r = get(base + "/hello");
        check("/hello 는 누구나 · 폰 이름", r.contains("서버폰"), r);
        check("/hello 에 침대 정보 없음", !r.contains(TOKEN) && !r.contains("침대"), r);
        check("CORS 헤더 없음", lastHeaders.get("Access-Control-Allow-Origin") == null, lastHeaders);
        r = get(base + "/state");
        check("도장 없는 /state 거절 · 인증키 안 샘", r.contains("\"auth\":false") && !r.contains(TOKEN), r);
        r = get(base + "/cmd?token=" + TOKEN + "&pin=11&val=30");
        check("도장 없는 /cmd 거절 (5.8.0 폰 흉내)", r.contains("\"auth\":false"), r);
        r = get("http://127.0.0.1:" + ApiServer.PORT + "/hello");
        check("집 안 주소가 아닌 곳(127.0.0.1) 거절", r.equals("{\"ok\":false}"), r);

        Ctx C = new Ctx();
        LanPeers cl = client(C, "allow-클라폰");

        System.out.println("\n[2] 상대 화면이 닫혀 있을 때");
        new File(dir, "closed.flag").createNewFile(); Thread.sleep(200);
        String err = cl.pair(ip);
        check("거절 + '앱을 열어둔 채로' 안내", err != null && err.contains("앱을 열어둔"), err);
        check("열쇠를 받지 않음", !HomeKey.has(C), C.p.m);
        new File(dir, "closed.flag").delete(); Thread.sleep(200);

        System.out.println("\n[3] 상대가 거절");
        setField(cl, "myName", "deny-클라폰");
        err = cl.pair(ip);
        check("거절하면 실패 + 이유", err != null && err.contains("허용하지"), err);
        check("열쇠를 받지 않음", !HomeKey.has(C), "");
        check("서버도 열쇠를 만들지 않음", home(base).isEmpty(), home(base));
        setField(cl, "myName", "allow-클라폰");
        err = cl.pair(ip);
        check("거절 직후 10초는 새 요청 안 받음", err != null, err);
        Thread.sleep(10300);

        System.out.println("\n[4] 두 폰이 동시에 요청 (열쇠 엇갈림 방지)");
        new File(dir, "busy.flag").createNewFile(); Thread.sleep(200);
        err = cl.pair(ip);
        check("상대도 요청 중이면 거절 + '한쪽에서만'", err != null && err.contains("한쪽에서만"), err);
        check("열쇠를 받지 않음", !HomeKey.has(C), "");
        new File(dir, "busy.flag").delete(); Thread.sleep(200);

        System.out.println("\n[5] 상대가 허용");
        err = cl.pair(ip);
        check("허용하면 성공", err == null, err);
        String kc = HomeKey.get(C);
        check("열쇠를 받음 (64글자)", kc.length() == 64, kc);
        check("서버와 같은 열쇠 (집 표식 일치)", HomeKey.id(kc).equals(home(base)), HomeKey.id(kc) + " / " + home(base));

        System.out.println("\n[6] 짝지은 뒤 — 다시 묻지 않고 상태 보기·조작");
        addPeer(cl, ip, home(base));
        check("서버를 짝으로 인식", cl.peers().size() == 1, cl.allPeers().size());
        cl.refresh();
        List<LanPeers.RemoteBed> rb = cl.remoteBeds();
        check("상대 침대가 보임 (이름·연결됨)", rb.size() == 1 && "아내 침대".equals(rb.get(0).name) && rb.get(0).online,
                rb.size() == 0 ? "없음" : rb.get(0).name + " " + rb.get(0).online);
        for (int i = 0; i < 3; i++) cl.refresh();
        check("여러 번 갱신해도 계속 보임", cl.remoteBeds().size() == 1, cl.remoteBeds().size());

        check("도장 찍은 명령 성공", cl.send(ip, TOKEN, "11", "30"), "");
        byte[] hh = new byte[5]; bi.readFully(hh);
        byte[] body = new byte[((hh[3] & 0xFF) << 8) | (hh[4] & 0xFF)]; bi.readFully(body);
        String got = new String(body, "UTF-8").replace('\0', '|');
        check("침대가 실제로 받은 명령 = vw|11|30", hh[0] == 20 && got.equals("vw|11|30"), hh[0] + " " + got);

        check("원격 이름 바꾸기 (한글·공백)", cl.rename(ip, TOKEN, "안방 침대 1"), "");
        cl.refresh();
        check("바뀐 이름이 보임", "안방 침대 1".equals(cl.remoteBeds().get(0).name), cl.remoteBeds().get(0).name);

        System.out.println("\n[7] 공격 흉내");
        String signed = (String) call(cl, "signedUrl", new Class<?>[]{String.class, String.class}, ip,
                "/cmd?token=" + TOKEN + "&pin=11&val=5");
        r = get(signed);
        check("정상 도장 1회는 통과", r.contains("\"ok\":true"), r);
        bi.readFully(hh); bi.readFully(new byte[((hh[3] & 0xFF) << 8) | (hh[4] & 0xFF)]);
        r = get(signed);
        check("같은 요청 다시 보내기(녹화 재전송) 거절", r.contains("\"auth\":false") && r.contains("이미"), r);
        r = get(signed.replace("val=5", "val=80"));
        check("값을 바꿔치기하면 거절", r.contains("\"auth\":false"), r);
        String old = "/cmd?token=" + TOKEN + "&pin=11&val=1&ts=" + (System.currentTimeMillis() - 5 * 60 * 1000);
        r = get(base + old + "&sig=" + HomeKey.sign(kc, old));
        check("5분 전 시각의 도장 거절", r.contains("\"auth\":false") && r.contains("시계"), r);
        String st = (String) call(cl, "signedUrl", new Class<?>[]{String.class, String.class}, ip, "/state");
        check("/state 는 같은 요청이 두 번 와도 통과 (깜빡임 방지)", get(st).contains(TOKEN) && get(st).contains(TOKEN), "");

        Ctx X = new Ctx();
        HomeKey.set(X, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        LanPeers stranger = client(X, "남의폰");
        addPeer(stranger, ip, home(base));   // 알림 신호의 표식까지 흉내 내도
        stranger.refresh();
        check("다른 열쇠를 가진 폰은 상태를 못 봄", stranger.remoteBeds().isEmpty(), stranger.remoteBeds().size());
        check("다른 열쇠를 가진 폰은 조작 못 함", !stranger.send(ip, TOKEN, "11", "80"), "");
        bed.setSoTimeout(800);
        boolean leaked = false;
        try { bi.readFully(hh); leaked = true; } catch (SocketTimeoutException ok) {}
        check("그 명령은 침대에 가지 않음", !leaked, "");

        System.out.println("\n[8] 짝 풀기");
        HomeKey.clear(C);
        check("짝 풀면 짝으로 안 보임", cl.peers().isEmpty(), cl.peers().size());
        check("짝 풀면 조작 못 함", !cl.send(ip, TOKEN, "11", "30"), "");
        bed.close();
    }
}
