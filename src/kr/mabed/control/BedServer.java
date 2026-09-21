package kr.mabed.control;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** 침대가 접속해오는 서버. Blynk Legacy 프로토콜. */
public class BedServer {

    public interface Listener {
        void onLog(String kind, String text);
        void onDevices(List<Dev> devices);
    }

    public static class Dev {
        public final String token, ip;
        final Socket sock;
        int msgId = 1;
        public final long since = System.currentTimeMillis();
        public final Map<String,String> pins = new LinkedHashMap<>();
        public final Map<String,Long> pinAt = new LinkedHashMap<>();
        Dev(String token, Socket s) { this.token = token; this.sock = s;
            this.ip = s.getInetAddress().getHostAddress(); }
        public String label() {
            String t = token.length() > 6 ? token.substring(token.length()-4) : token;
            return "침대 …" + t + " (" + ip + ")";
        }
    }

    public static final int PORT = 8080;

    private ServerSocket server;
    private volatile boolean running;
    private final Listener listener;
    private final List<Dev> devices = Collections.synchronizedList(new ArrayList<Dev>());
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public BedServer(Listener l) { this.listener = l; }

    public boolean isRunning() { return running; }
    public List<Dev> devices() { synchronized (devices) { return new ArrayList<>(devices); } }

    /** 인증키로 해당 침대를 찾는다 */
    public Dev byToken(String token) {
        if (token == null) return null;
        Dev best = null;
        synchronized (devices) {
            for (Dev d : devices) if (token.equals(d.token) && (best == null || d.since > best.since)) best = d;
        }
        return best;
    }

    private void log(String k, String t) { if (listener != null) listener.onLog(k, t); }
    private void pushDevices() { if (listener != null) listener.onDevices(devices()); }

    public void start() {
        if (running) return;
        running = true;
        pool.execute(new Runnable() { public void run() { accept(); } });
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        synchronized (devices) {
            for (Dev d : devices) { try { d.sock.close(); } catch (Exception ignored) {} }
            devices.clear();
        }
        pushDevices();
        log("중지", "서버를 껐습니다");
    }

    private void accept() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            log("시작", "침대 대기 중 · 포트 " + PORT);
            while (running) {
                final Socket s = server.accept();
                pool.execute(new Runnable() { public void run() { handle(s); } });
            }
        } catch (final Exception e) {
            if (running) log("오류", "서버를 열지 못했습니다 · " + e.getMessage());
        }
    }

    // ── 프로토콜 ────────────────────────────────────────
    private static final int RESPONSE=0, LOGIN=2, PING=6, HW_SYNC=16, INTERNAL=17, HARDWARE=20, HW_LOGIN=29;

    private static byte[] head(int cmd, int msgId, int len) {
        return new byte[]{ (byte) cmd, (byte)(msgId>>8), (byte) msgId,
                           (byte)(len>>8), (byte) len };
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    private void handle(Socket s) {
        String ip = s.getInetAddress().getHostAddress();
        log("연결", ip + " 에서 접속");
        Dev dev = null;
        try {
            s.setSoTimeout(120000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] h = new byte[5];
            while (running && readFully(in, h)) {
                int cmd = h[0] & 0xFF;
                int msgId = ((h[1] & 0xFF) << 8) | (h[2] & 0xFF);
                int len = ((h[3] & 0xFF) << 8) | (h[4] & 0xFF);
                byte[] body = new byte[len];
                if (len > 0 && !readFully(in, body)) break;

                switch (cmd) {
                    case HW_LOGIN:
                    case LOGIN: {
                        String token = new String(body, "UTF-8").trim();
                        synchronized (out) { out.write(head(RESPONSE, msgId, 200)); out.flush(); }
                        synchronized (devices) {
                            for (Iterator<Dev> it = devices.iterator(); it.hasNext(); ) {
                                Dev old = it.next();
                                if (old.token.equals(token)) {
                                    try { old.sock.close(); } catch (Exception ignored) {}
                                    it.remove();
                                    log("정리", "같은 침대의 옛 연결을 닫았습니다");
                                }
                            }
                        }
                        dev = new Dev(token, s);
                        devices.add(dev);
                        log("성공", "침대가 붙었습니다 · 인증키 " + token);
                        pushDevices();
                        break;
                    }
                    case PING:
                        synchronized (out) { out.write(head(RESPONSE, msgId, 200)); out.flush(); }
                        break;
                    case INTERNAL: {
                        String[] p = split(body);
                        log("기기정보", join(p));
                        synchronized (out) { out.write(head(RESPONSE, msgId, 200)); out.flush(); }
                        if (p.length > 0 && "rtc".equals(p[0])) {
                            long now = System.currentTimeMillis() / 1000L;
                            byte[] rb = ("rtc" + ((char) 0) + now).getBytes("UTF-8");
                            int mid2;
                            if (dev != null) { synchronized (dev) { dev.msgId = (dev.msgId % 60000) + 1; mid2 = dev.msgId; } }
                            else mid2 = (msgId % 60000) + 1;
                            synchronized (out) {
                                out.write(head(INTERNAL, mid2, rb.length));
                                out.write(rb); out.flush();
                            }
                            log("시각", "침대에 현재 시각을 알려줬습니다");
                        }
                        break;
                    }
                    case HARDWARE: {
                        String[] p = split(body);
                        if (p.length >= 3 && (p[0].equals("vw") || p[0].equals("pw"))) {
                            if (dev != null) {
                                dev.pins.put("V" + p[1], p[2]);
                                dev.pinAt.put("V" + p[1], System.currentTimeMillis());
                            }
                            log("받음", "V" + p[1] + " = " + p[2]);
                            pushDevices();
                        } else {
                            log("받음", join(p));
                        }
                        synchronized (out) { out.write(head(RESPONSE, msgId, 200)); out.flush(); }
                        break;
                    }
                    case HW_SYNC:
                        log("동기화", "침대가 현재 상태를 물어봄");
                        synchronized (out) { out.write(head(RESPONSE, msgId, 200)); out.flush(); }
                        break;
                    case RESPONSE:
                        break;
                    default:
                        log("명령" + cmd, len > 0 ? join(split(body)) : "(내용 없음)");
                        synchronized (out) { out.write(head(RESPONSE, msgId, 200)); out.flush(); }
                }
            }
        } catch (SocketTimeoutException e) {
            log("끊김", "응답 없음 (타임아웃)");
        } catch (Exception e) {
            if (running) log("끊김", ip + " · " + e.getClass().getSimpleName());
        } finally {
            if (dev != null) devices.remove(dev);
            try { s.close(); } catch (Exception ignored) {}
            pushDevices();
            log("끊김", ip + " 연결 종료");
        }
    }

    private static String[] split(byte[] b) {
        try {
            String s = new String(b, "UTF-8");
            return s.split("\u0000", -1);
        } catch (Exception e) { return new String[]{""}; }
    }

    private static String join(String[] p) {
        StringBuilder sb = new StringBuilder();
        for (String x : p) { if (x.isEmpty()) continue; if (sb.length()>0) sb.append(" / "); sb.append(x); }
        return sb.toString();
    }

    /** 침대에게 지금 값을 알려달라고 요청한다 (vr) */
    public boolean read(Dev d, String pin) {
        if (d == null) return false;
        try {
            byte[] p = pin.getBytes("UTF-8");
            byte[] body = new byte[3 + p.length];
            int i = 0;
            body[i++]='v'; body[i++]='r'; body[i++]=0;
            System.arraycopy(p, 0, body, i, p.length);
            int mid;
            synchronized (d) { d.msgId = (d.msgId % 60000) + 1; mid = d.msgId; }
            OutputStream out = d.sock.getOutputStream();
            synchronized (out) {
                out.write(head(HARDWARE, mid, body.length));
                out.write(body); out.flush();
            }
            log("물어봄", "V" + pin + " 지금 값이 뭐야?");
            return true;
        } catch (Exception e) {
            log("오류", "질문 실패 · " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** 침대에 'V핀 = 값' 명령을 보낸다 */
    public boolean write(Dev d, String pin, String value) {
        if (d == null) return false;
        try {
            byte[] p = pin.getBytes("UTF-8"), v = value.getBytes("UTF-8");
            byte[] body = new byte[2 + 1 + p.length + 1 + v.length];
            int i = 0;
            body[i++]='v'; body[i++]='w'; body[i++]=0;
            System.arraycopy(p,0,body,i,p.length); i+=p.length; body[i++]=0;
            System.arraycopy(v,0,body,i,v.length);
            int mid;
            synchronized (d) { d.msgId = (d.msgId % 60000) + 1; mid = d.msgId; }
            OutputStream out = d.sock.getOutputStream();
            synchronized (out) {
                out.write(head(HARDWARE, mid, body.length));
                out.write(body);
                out.flush();
            }
            log("보냄", "V" + pin + " = " + value);
            return true;
        } catch (Exception e) {
            String m = e.getMessage();
            log("오류", "명령 실패 · " + e.getClass().getSimpleName()
                    + (m == null ? "" : " · " + m));
            if (e instanceof IOException) {
                devices.remove(d);
                try { d.sock.close(); } catch (Exception ignored) {}
                pushDevices();
            }
            return false;
        }
    }
}
