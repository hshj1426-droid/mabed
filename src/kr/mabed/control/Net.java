package kr.mabed.control;

import android.content.Context;
import android.net.*;
import android.os.Build;
import java.io.*;
import java.net.*;

/** 와이파이 쪽으로만 나가게 묶어주는 도우미 (인터넷 안 되는 와이파이에서도 통신하려면 필요) */
public class Net {

    public static Network wifiNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** 지금 와이파이에서 내 폰의 주소 */
    public static String myWifiIp(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = wifiNetwork(ctx);
        if (cm != null && n != null) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        }
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String nm = ni.getName() == null ? "" : ni.getName();
                if (!nm.startsWith("wlan") && !nm.startsWith("ap")) continue;   // 와이파이 쪽만
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 집 안 네트워크 주소인가 (10.x / 172.16~31.x / 192.168.x) */
    public static boolean isLan(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("192.0.0.")) return false;          // 모바일데이터 내부주소
        if (ip.startsWith("169.254.")) return false;          // 주소 못 받은 상태
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int b = Integer.parseInt(ip.split("\\.")[1]);
                return b >= 16 && b <= 31;
            } catch (Exception e) { return false; }
        }
        return false;
    }

    /** 침대 설정 모드(192.168.4.1)에 붙어 있는 상태인지 */
    public static boolean onBedAp(Context ctx) {
        String ip = myWifiIp(ctx);
        return ip != null && ip.startsWith("192.168.4.");
    }

    /** 와이파이로 묶어서 GET 요청 */
    public static String get(Context ctx, String url, int timeoutMs) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c;
        Network n = wifiNetwork(ctx);
        if (n != null && Build.VERSION.SDK_INT >= 21) {
            c = (HttpURLConnection) n.openConnection(u);
        } else {
            c = (HttpURLConnection) u.openConnection();
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "MaBed/1.0");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            if (in != null) while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            String body = bo.toString("UTF-8");
            if (code >= 400) throw new IOException("HTTP " + code + " · " + body);
            return body;
        } finally {
            c.disconnect();
        }
    }

    public static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return ""; }
    }
}
