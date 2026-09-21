package kr.mabed.control;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Ui u;

    private FrameLayout root;
    private View mainPane, devPane, wizPane;

    private List<Beds.Bed> beds = new ArrayList<>();
    private final LanPeers lan = new LanPeers();
    private ApiServer api;
    private List<LanPeers.RemoteBed> remotes = new ArrayList<>();
    private boolean selRemote = false;
    private int sel = 0;

    // 메인
    private BedView bedView;
    private TextView statusDot, angleText, slotText, lightState, speakerState;
    private TextView warnView;
    private View warnCard;
    private TextView headVal, legVal, tableVal;
    private View stopBtn;
    private Button tableToggle;
    private View tableRow;
    private View battRow, battHair;
    private final List<View> ownerRows = new ArrayList<>();
    private TextView updView;
    private View updCard;
    private Updates.Info pending;
    private LinearLayout tabRow;
    private final Map<String, Slider> bars = new LinkedHashMap<>();
    private final Map<String, Integer> targets = new LinkedHashMap<>();
    private boolean dragging = false;

    // 마법사
    private LinearLayout wizBox;
    private int step = 0;
    private boolean wizShownOrphan = false;
    private String wizName = "", wizToken = "", wizSsid = "", wizPass = "", wizHost = "";
    private boolean wizHandover = false;          // 다른 폰으로 넘기는 중인가
    private String wizTargetPhone = "";           // 넘겨받을 폰 이름
    private EditText wizNameIn, wizPassIn;
    private LinearLayout wizScan;
    private TextView wizMsg;

    // 개발자
    private TextView addrView, logView, finderView, pinView;
    private EditText pinIn, valIn, stopPinIn;
    private final List<Integer> pool = new ArrayList<>();
    private final List<Integer> trying = new ArrayList<>();
    private static final int[] CANDIDATES = {
        12,10,16,17,18,19,20,9,7,6,5,4,3,2,1,0,21,22,23,24,25,26,27,28,29,30,
        32,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,53,54,55,56,
        57,58,59,60,62,63 };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("mabed", MODE_PRIVATE);
        u = new Ui(this);
        beds = Beds.load(prefs);
        migrateOld();
        sel = Math.min(prefs.getInt("sel", 0), Math.max(0, beds.size() - 1));

        root = new FrameLayout(this);
        root.setBackgroundColor(u.bg);
        setContentView(root);
        applyInsets();

        if (Build.VERSION.SDK_INT >= 33)
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); }
            catch (Exception ignored) {}

        App.setCallback(new Runnable() { public void run() {
            ui.post(new Runnable() { public void run() { refresh(); } }); } });
        if (!App.server().isRunning()) startServer();

        String id = prefs.getString("phoneId", null);
        if (id == null) { id = Beds.newToken().substring(0, 8); prefs.edit().putString("phoneId", id).apply(); }
        api = new ApiServer(new ApiServer.Host() {
            public List<Beds.Bed> beds() { return beds; }
            public String phoneName() { return prefs.getString("phoneName", android.os.Build.MODEL); }
        });
        api.start();
        lan.start(this, prefs.getString("phoneName", android.os.Build.MODEL), id);

        rebuild();
        startTicking();
    }

    /** 화면을 보고 있을 때만 주기적으로 상태를 확인한다 */
    private boolean ticking = false;

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        ui.post(tick);
        ui.postDelayed(poll, 1200);
        ui.postDelayed(peerPoll, 1800);
    }

    private void stopTicking() {
        ticking = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(poll);
        ui.removeCallbacks(peerPoll);
    }

    @Override protected void onResume() {
        super.onResume();
        startTicking();
        long last = prefs.getLong("updCheckedAt", 0);
        if (System.currentTimeMillis() - last > 12L * 60 * 60 * 1000) checkUpdate(false);
        // 배터리 제한을 푼 뒤 돌아온 경우 메뉴에서 그 줄을 치운다
        if (battRow != null) {
            int vis = battOk() ? View.GONE : View.VISIBLE;
            battRow.setVisibility(vis);
            if (battHair != null) battHair.setVisibility(vis);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        stopTicking();   // 서버와 서비스는 계속 돌고, 화면 갱신만 멈춘다
    }

    /** 배터리 최적화 예외를 이미 받았는지 */
    private boolean battOk() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) { return true; }
    }

    /** 배터리 제한 해제 화면을 연다 */
    private void askBattery() {
        try {
            Intent i = new Intent(android.provider.Settings
                    .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings
                        .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("이 폰에서는 설정 화면을 열 수 없습니다");
            }
        }
    }

    /** 4.0 이전 버전에서 쓰던 단일 침대 설정을 목록으로 옮긴다 */
    private void migrateOld() {
        if (!beds.isEmpty()) return;
        String old = prefs.getString("token", null);
        if (old == null || old.length() < 8) return;
        Beds.Bed b = new Beds.Bed("내 침대", old);
        beds.add(b);
        Beds.save(prefs, beds);
        SharedPreferences.Editor e = prefs.edit();
        String k = "b_" + old + "_";
        e.putBoolean(k + "table", prefs.getBoolean("hasTable", false));
        if (prefs.contains("slotA_b")) {
            e.putInt(k + "Ab", prefs.getInt("slotA_b", 0));
            e.putInt(k + "Al", prefs.getInt("slotA_l", 0));
        }
        if (prefs.contains("slotB_b")) {
            e.putInt(k + "Bb", prefs.getInt("slotB_b", 0));
            e.putInt(k + "Bl", prefs.getInt("slotB_l", 0));
        }
        e.putInt("sel", 0);
        e.apply();
        App.addLog("복구", "예전 설정을 옮겼습니다 · 인증키 " + old);
    }

    /** 접속은 했는데 목록에 없는 침대를 찾는다 */
    private String orphanToken() {
        for (BedServer.Dev d : App.server().devices()) {
            boolean known = false;
            for (Beds.Bed b : beds) if (b.token.equals(d.token)) known = true;
            if (!known) return d.token;
        }
        return null;
    }

    private void rebuild() {
        root.removeAllViews();
        bars.clear();
        if (beds.isEmpty() && !remotes.isEmpty()) { selRemote = true; sel = 0; }
        if (beds.isEmpty() && remotes.isEmpty()) {
            step = 0;
            wizPane = buildWizard();
            root.addView(wizPane);
        } else {
            mainPane = buildMain();
            devPane = buildDev();
            devPane.setVisibility(View.GONE);
            root.addView(mainPane);
            root.addView(devPane);
        }
        refresh();
    }

    private Beds.Bed cur() {
        if (beds.isEmpty()) return null;
        if (selRemote || sel >= beds.size()) return beds.get(0);
        return beds.get(sel);
    }

    private LanPeers.RemoteBed curRemote() {
        if (!selRemote || sel >= remotes.size()) return null;
        return remotes.get(sel);
    }

    private String curToken() {
        LanPeers.RemoteBed r = curRemote();
        if (r != null) return r.token;
        Beds.Bed b = cur();
        return b == null ? "none" : b.token;
    }

    private String curName() {
        LanPeers.RemoteBed r = curRemote();
        if (r != null) return r.name;
        Beds.Bed b = cur();
        return b == null ? "" : b.name;
    }

    // ── 설정 마법사 ────────────────────────────────────
    private View buildWizard() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(u.bg);
        wizBox = u.col();
        wizBox.setPadding(u.dp(20), u.dp(24), u.dp(20), u.dp(28));
        sv.addView(wizBox);
        renderStep();
        return sv;
    }

    private void renderStep() {
        wizBox.removeAllViews();
        wizBox.addView(u.text(wizHandover ? "침대 넘겨주기" : "침대 연결하기", 22, u.fg, true));
        TextView sub = u.text(wizHandover
                ? (wizName + " → " + (wizTargetPhone.isEmpty() ? wizHost : wizTargetPhone))
                : ((step + 1) + " / 6 단계"), 12.5f, wizHandover ? u.accent : u.muted, true);
        sub.setPadding(0, u.dp(4), 0, u.dp(18));
        wizBox.addView(sub);

        switch (step) {
            case 0: {
                wizBox.addView(u.text("먼저 이 침대를 뭐라고 부를지 정해주세요.", 15, u.fg, false));
                wizBox.addView(spacer(14));
                wizNameIn = u.input("예: 내 침대", beds.isEmpty() ? "내 침대" : "", false);
                wizBox.addView(wizNameIn);
                wizBox.addView(u.note("침대가 두 대 이상이면 이 이름으로 구분합니다."));
                wizBox.addView(spacer(8));
                String ip = Net.myWifiIp(this);
                boolean okWifi = Net.isLan(ip) && !ip.startsWith("192.168.4.");
                if (okWifi) {
                    wizHost = ip;
                    String ss = currentSsid();
                    if (ss != null) { wizSsid = ss; prefs.edit().putString("homeSsid", ss).apply(); }
                    prefs.edit().putString("homeIp", ip).apply();
                    String saved = prefs.getString("homeSsid", "");
                    wizBox.addView(u.card(u.text("지금 집 와이파이에 연결돼 있습니다.\n"
                            + "이 폰 주소 : " + ip
                            + (saved.isEmpty() ? "" : "\n와이파이 : " + saved), 13, u.fg, false), 13));
                    if (saved.contains("5G") || saved.contains("5g")) {
                        wizBox.addView(u.card(u.text(
                            "이 폰은 지금 5GHz 와이파이에 붙어 있습니다.\n"
                            + "침대는 5GHz를 못 씁니다. 다음 단계에서 5G가 안 붙은 쪽을 고르세요.\n"
                            + "두 와이파이가 같은 공유기라면 폰은 그대로 두셔도 됩니다.", 13, u.accent, false), 13));
                    }
                    if (saved.isEmpty()) {
                        wizBox.addView(u.small("와이파이 이름 자동으로 가져오기", new Runnable(){ public void run(){
                            if (!hasLocationPerm()) askLocationPerm();
                            else {
                                String s2 = currentSsid();
                                if (s2 != null) { wizSsid = s2; prefs.edit().putString("homeSsid", s2).apply(); renderStep(); }
                                else toast("위치 기능을 켜고 다시 눌러주세요");
                            } }}));
                        wizBox.addView(u.note("안드로이드는 위치 권한이 있어야 와이파이 이름을 알려줍니다. 위치를 추적하지는 않습니다."));
                    }
                } else {
                    wizBox.addView(u.card(u.text("먼저 집 와이파이에 연결해주세요.\n연결한 뒤 이 화면으로 돌아오면 됩니다.", 13, u.danger, false), 13));
                }
                final String orphan = orphanToken();
                if (orphan != null) {
                    wizBox.addView(spacer(6));
                    wizBox.addView(u.card(u.text(
                        "이미 이 폰에 접속해 있는 침대가 있습니다.\n설정을 다시 할 필요 없이 바로 추가할 수 있습니다.",
                        13, u.ok, false), 13));
                    wizBox.addView(u.btn("접속해 있는 침대 바로 추가", u.ok, 0xFFFFFFFF, 0, 15, 16,
                        new Runnable(){ public void run(){
                            String n = wizNameIn.getText().toString().trim();
                            wizName = n.isEmpty() ? "내 침대" : n;
                            wizToken = orphan;
                            wizFinish();
                        }}));
                    wizBox.addView(spacer(10));
                }
                if (!remotes.isEmpty()) {
                    wizBox.addView(spacer(6));
                    wizBox.addView(u.card(u.text(
                        "다른 폰이 가진 침대 " + remotes.size() + "대가 보입니다.\n"
                        + "내 침대를 등록하지 않아도 그 침대는 지금 바로 조작할 수 있습니다.",
                        13, u.ok, false), 13));
                    wizBox.addView(u.btn("그 침대 조작하러 가기", u.ok, 0xFFFFFFFF, 0, 15, 16,
                        new Runnable(){ public void run(){
                            selRemote = true; sel = 0; rebuild(); }}));
                    wizBox.addView(spacer(10));
                }
                wizBox.addView(u.btn("다음", okWifi ? u.accent : u.card, okWifi ? 0xFFFFFFFF : u.muted,
                        okWifi ? 0 : u.line, 16, 17, new Runnable() { public void run() {
                    String n = wizNameIn.getText().toString().trim();
                    if (n.isEmpty()) { toast("이름을 넣어주세요"); return; }
                    String ip2 = Net.myWifiIp(MainActivity.this);
                    if (!Net.isLan(ip2) || ip2.startsWith("192.168.4.")) { toast("집 와이파이에 연결해주세요"); return; }
                    wizName = n; wizHost = ip2; wizToken = Beds.newToken();
                    step = 1; renderStep();
                }}));
                break;
            }
            case 1: {
                wizBox.addView(u.text("침대를 설정 모드로 바꿔주세요.", 16, u.fg, true));
                wizBox.addView(spacer(10));
                wizBox.addView(u.card(u.text(
                    "1. 침대 밑 컨트롤 박스의 나사 4개를 풉니다\n\n" +
                    "2. 안에 꽂힌 작은 검은 기판에서 micro-USB 단자 오른쪽의 BOOT 버튼을 찾습니다\n\n" +
                    "3. 전원이 켜진 상태로 BOOT를 10초간 꾹 누릅니다\n\n" +
                    "4. 빨간 불이 느리게 깜빡이다 빠르게 깜빡이면 완료입니다", 14, u.fg, false), 15));
                wizBox.addView(u.note("케이스 바깥이 아니라 안쪽 기판 위에 있습니다."));
                wizBox.addView(u.btn("했습니다", u.accent, 0xFFFFFFFF, 0, 16, 17,
                        new Runnable(){ public void run(){ step = 2; renderStep(); }}));
                if (!wizHandover) wizBox.addView(u.small("뒤로", new Runnable(){ public void run(){ step = 0; renderStep(); }}));
                else wizBox.addView(u.small("넘기기 취소", new Runnable(){ public void run(){
                        wizHandover = false; rebuild(); }}));
                break;
            }
            case 2: {
                wizBox.addView(u.text("폰을 침대 와이파이에 연결해주세요.", 16, u.fg, true));
                wizBox.addView(spacer(10));
                wizBox.addView(u.card(u.text(
                    "1. 폰 설정 → 와이파이\n\n" +
                    "2. birkits- 로 시작하는 이름을 찾아 연결합니다 (비밀번호 없음)\n\n" +
                    "3. \"인터넷이 안 된다\"고 나오면 이 네트워크 유지를 고릅니다\n\n" +
                    "4. 모바일 데이터를 꺼주세요  ← 이걸 안 하면 실패합니다", 14, u.fg, false), 15));
                wizMsg = u.note(Net.onBedAp(this) ? "지금 침대 와이파이에 연결돼 있습니다." : "아직 연결되지 않았습니다.");
                wizBox.addView(wizMsg);
                wizBox.addView(u.btn("연결했습니다 · 침대 찾기", u.accent, 0xFFFFFFFF, 0, 16, 17,
                        new Runnable(){ public void run(){ wizFind(); }}));
                wizBox.addView(u.small("뒤로", new Runnable(){ public void run(){ step = 1; renderStep(); }}));
                break;
            }
            case 3: {
                wizBox.addView(u.text("집 와이파이를 고르세요.", 16, u.fg, true));
                final String autoSsid = prefs.getString("homeSsid", "");
                if (!autoSsid.isEmpty()) {
                    wizBox.addView(u.card(u.text("이 폰이 쓰던 와이파이 : " + autoSsid, 14, u.ok, true), 13));
                    wizBox.addView(u.btn("이걸로 하기", u.ok, 0xFFFFFFFF, 0, 15, 15, new Runnable(){ public void run(){
                        wizSsid = autoSsid; renderStep(); }}));
                }
                wizBox.addView(u.note("아래는 침대가 자기 자리에서 잡히는 목록입니다. 신호가 셀수록 안정적입니다. 침대는 2.4GHz만 쓸 수 있어서 이름에 5G가 붙은 것은 여기에 아예 안 나옵니다."));
                wizScan = u.col();
                wizBox.addView(wizScan);
                wizBox.addView(u.small("목록 다시 받기", new Runnable(){ public void run(){ wizScan(); }}));
                wizBox.addView(spacer(6));
                wizBox.addView(u.text("고른 와이파이 : " + (wizSsid.isEmpty() ? "아직 없음" : wizSsid), 14, u.fg, true));
                if (wizPass.isEmpty()) wizPass = prefs.getString("pass", "");
                wizPassIn = u.input("와이파이 비밀번호", wizPass, false);
                wizPassIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                wizBox.addView(wizPassIn);
                wizBox.addView(u.small("비밀번호 보이기 / 가리기", new Runnable(){ public void run(){
                    boolean hidden = (wizPassIn.getInputType()
                            & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                    wizPassIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | (hidden ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                      : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
                    wizPassIn.setSelection(wizPassIn.getText().length());
                }}));
                wizBox.addView(u.note("비밀번호는 안드로이드가 앱에게 알려주지 않습니다. 한 번만 넣어두면 다음부터는 기억합니다."));
                wizBox.addView(u.btn("다음", u.accent, 0xFFFFFFFF, 0, 16, 17, new Runnable(){ public void run(){
                    if (wizSsid.isEmpty()) { toast("와이파이를 골라주세요"); return; }
                    wizPass = wizPassIn.getText().toString();
                    prefs.edit().putString("pass", wizPass).putString("ssid", wizSsid).apply();
                    step = 4; renderStep();
                }}));
                wizScan();
                break;
            }
            case 4: {
                wizBox.addView(u.text("이렇게 넣겠습니다.", 16, u.fg, true));
                wizBox.addView(spacer(10));
                wizBox.addView(u.card(u.text(
                    "이름        " + wizName + "\n" +
                    "와이파이     " + wizSsid + "\n" +
                    (wizHandover
                        ? "넘겨받을 폰   " + (wizTargetPhone.isEmpty() ? "" : wizTargetPhone + "  ") + wizHost
                        : "찾아올 주소   " + wizHost + " : " + BedServer.PORT), 14, u.fg, false), 15));
                if (wizHandover) {
                    wizBox.addView(u.card(u.text(
                        "이제부터 이 침대는 저 폰을 찾아갑니다.\n"
                        + "이 폰 목록에서는 빠지지만, 같은 와이파이에 있으면\n"
                        + "↗ 표시가 붙은 채로 계속 조작할 수 있습니다.", 13, u.accent, false), 13));
                }
                wizMsg = u.note("");
                wizBox.addView(wizMsg);
                wizBox.addView(u.btn("침대에 넣기", u.accent, 0xFFFFFFFF, 0, 16, 17,
                        new Runnable(){ public void run(){ wizSend(); }}));
                wizBox.addView(u.small("뒤로", new Runnable(){ public void run(){ step = 3; renderStep(); }}));
                break;
            }
            case 5: {
                if (wizHandover) {
                    wizBox.addView(u.text("넘겼습니다.", 16, u.fg, true));
                    wizBox.addView(spacer(10));
                    wizBox.addView(u.card(u.text(
                        "1. 이 폰을 다시 집 와이파이(" + wizSsid + ")로 연결하세요.\n\n" +
                        "2. " + (wizTargetPhone.isEmpty() ? "받는 폰" : wizTargetPhone) + " 에서 앱을 여세요.\n\n" +
                        "3. \"접속해 있는 침대가 있습니다\" 안내가 뜨면 한 번 누르면 끝입니다.",
                        14, u.fg, false), 15));
                    wizBox.addView(u.note("침대가 다시 시작하면서 저 폰을 찾아갑니다. 보통 10초 안에 붙습니다."));
                    wizBox.addView(u.btn("끝내기", u.accent, 0xFFFFFFFF, 0, 16, 17,
                            new Runnable(){ public void run(){ handoverDone(); }}));
                    break;
                }
                wizBox.addView(u.text("마지막입니다.", 16, u.fg, true));
                wizBox.addView(spacer(10));
                wizBox.addView(u.card(u.text(
                    "폰을 다시 집 와이파이(" + wizSsid + ")로 연결해주세요.\n\n" +
                    "침대가 스스로 다시 시작하면서 찾아옵니다.\n보통 10초 안에 연결됩니다.", 14, u.fg, false), 15));
                wizMsg = u.text("침대를 기다리는 중…", 15, u.muted, true);
                wizBox.addView(u.card(wizMsg, 15));
                wizBox.addView(u.small("건너뛰고 끝내기", new Runnable(){ public void run(){ wizFinish(); }}));
                break;
            }
        }
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, u.dp(h)));
        return v;
    }

    private void wizFind() {
        wizMsg.setText("침대를 찾는 중…");
        bg(new Runnable(){ public void run(){
            try {
                final String s = Net.get(MainActivity.this, "http://192.168.4.1/board_info.json", 6000);
                App.addLog("침대", "찾았습니다 · " + s.trim());
                post(new Runnable(){ public void run(){ step = 3; renderStep(); }});
            } catch (final Exception e) {
                post(new Runnable(){ public void run(){ wizMsg.setText(
                    "침대를 못 찾았습니다.\n\n· birkits- 와이파이에 연결돼 있는지\n· 모바일 데이터를 껐는지\n· 침대가 설정 모드인지 확인해주세요\n\n(" + e.getMessage() + ")"); }});
            }
        }});
    }

    private void wizScan() {
        wizScan.removeAllViews();
        wizScan.addView(u.note("불러오는 중…"));
        bg(new Runnable(){ public void run(){
            try {
                final String s = Net.get(MainActivity.this, "http://192.168.4.1/wifi_scan.json", 15000);
                post(new Runnable(){ public void run(){ wizShowScan(s); }});
            } catch (final Exception e) {
                post(new Runnable(){ public void run(){
                    wizScan.removeAllViews();
                    wizScan.addView(u.note("목록을 못 받았습니다. 침대 와이파이에 연결돼 있는지 확인하고 다시 받아보세요."));
                }});
            }
        }});
    }

    private void wizShowScan(String json) {
        wizScan.removeAllViews();
        try {
            JSONArray a = new JSONArray(json);
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String ssid = o.optString("ssid", "");
                if (!ssid.isEmpty()) rows.add(new String[]{ ssid, String.valueOf(o.optInt("rssi", -100)) });
            }
            Collections.sort(rows, new Comparator<String[]>() {
                public int compare(String[] x, String[] y) {
                    return Integer.parseInt(y[1]) - Integer.parseInt(x[1]); }});
            if (rows.isEmpty()) { wizScan.addView(u.note("침대가 와이파이를 하나도 못 봤습니다.")); return; }
            for (String[] r : rows) {
                final String ssid = r[0];
                int rssi = Integer.parseInt(r[1]);
                String q = rssi >= -60 ? "신호 좋음" : rssi >= -70 ? "신호 보통" : "신호 약함";
                wizScan.addView(u.small(ssid + "   ·   " + q, new Runnable(){ public void run(){
                    wizSsid = ssid; renderStep(); }}));
            }
        } catch (Exception e) { wizScan.addView(u.note("목록 해석 실패")); }
    }

    private void wizSend() {
        wizMsg.setText("보내는 중…");
        final String url = "http://192.168.4.1/config?ssid=" + Net.enc(wizSsid)
                + "&pass=" + Net.enc(wizPass) + "&blynk=" + Net.enc(wizToken)
                + "&host=" + Net.enc(wizHost) + "&port=" + BedServer.PORT + "&port_ssl=" + BedServer.PORT;
        bg(new Runnable(){ public void run(){
            try {
                Net.get(MainActivity.this, url, 9000);
                prefs.edit().putString("b_" + wizToken + "_host", wizHost)
                            .putString("b_" + wizToken + "_ssid", wizSsid).apply();
                App.addLog("설정", wizName + " · " + wizHost + " · " + wizSsid);
                post(new Runnable(){ public void run(){ step = 5; renderStep(); if (!wizHandover) waitForBed(); }});
            } catch (final Exception e) {
                post(new Runnable(){ public void run(){ wizMsg.setText("실패했습니다.\n" + e.getMessage()); }});
            }
        }});
    }

    private void waitForBed() {
        ui.postDelayed(new Runnable() { public void run() {
            if (step != 5) return;
            if (App.server().byToken(wizToken) != null) { wizFinish(); return; }
            if (wizMsg != null) wizMsg.setText("침대를 기다리는 중…  폰이 집 와이파이인지 확인해주세요.");
            ui.postDelayed(this, 1500);
        }}, 1500);
    }

    private void wizFinish() {
        // 안전장치 — 넘기기 중에는 절대 내 목록에 다시 넣지 않는다
        if (wizHandover) { handoverDone(); return; }
        beds.add(new Beds.Bed(wizName, wizToken));
        Beds.save(prefs, beds);
        sel = beds.size() - 1;
        prefs.edit().putInt("sel", sel).apply();
        toast(wizName + " 를 추가했습니다");
        rebuild();
    }

    /** 상태바 · 네비게이션바 · 키보드 영역만큼 안쪽을 비워둔다 (모든 기종 대응) */
    private void applyInsets() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                    public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                        android.graphics.Insets b = in.getInsets(
                                android.view.WindowInsets.Type.systemBars()
                                        | android.view.WindowInsets.Type.displayCutout());
                        android.graphics.Insets k = in.getInsets(android.view.WindowInsets.Type.ime());
                        v.setPadding(b.left, b.top, b.right, Math.max(b.bottom, k.bottom));
                        return in;
                    }
                });
            } else {
                root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                    @SuppressWarnings("deprecation")
                    public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                        v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                                in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                        return in;
                    }
                });
            }
            root.setFitsSystemWindows(false);
            root.requestApplyInsets();
        } catch (Throwable ignored) {}
    }

    // ── 메인 화면 ──────────────────────────────────────
    private View buildMain() {
        LinearLayout outer = u.col();

        // ── 헤더 ──
        LinearLayout top = u.row(0);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(u.dp(18), u.dp(12), u.dp(18), u.dp(8));
        LinearLayout titleCol = u.col();
        TextView eyebrow = u.text("MA BED", 10f, u.muted, true);
        eyebrow.setLetterSpacing(0.24f);
        titleCol.addView(eyebrow);
        TextView title = u.text("마베드", 24, u.fg, true);
        title.setPadding(0, u.dp(1), 0, 0);
        titleCol.addView(title);
        top.addView(titleCol, new LinearLayout.LayoutParams(-2, -2));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        statusDot = u.pill("", u.muted, u.card, u.line);
        top.addView(statusDot, new LinearLayout.LayoutParams(-2, -2));
        outer.addView(top);

        tabRow = u.row(0);
        tabRow.setPadding(u.dp(14), u.dp(2), u.dp(14), u.dp(2));
        outer.addView(tabRow);
        buildTabs();

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        LinearLayout c = u.col();
        c.setPadding(u.dp(18), u.dp(6), u.dp(18), u.dp(6));
        sv.addView(c);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        warnView = u.text("", 13, u.danger, true);
        warnCard = u.card(warnView, 14);
        warnCard.setVisibility(View.GONE);
        c.addView(warnCard);

        LinearLayout updBox = u.col();
        updView = u.text("", 13.5f, u.fg, true);
        updBox.addView(updView);
        Button updBtn = u.btn("받으러 가기", u.ok, 0xFFFFFFFF, 0, 14, 12,
                new Runnable(){ public void run(){ openUpdate(); }});
        LinearLayout.LayoutParams ubp = new LinearLayout.LayoutParams(-1, -2);
        ubp.topMargin = u.dp(10);
        ubp.bottomMargin = 0;
        updBtn.setLayoutParams(ubp);
        updBox.addView(updBtn);
        updCard = u.card(updBox, 14);
        updCard.setVisibility(View.GONE);
        c.addView(updCard);
        if (pending != null) showUpdate(pending);

        // ── 침대 그림 ──
        LinearLayout hero = u.col();
        hero.setPadding(u.dp(8), u.dp(16), u.dp(8), u.dp(14));
        hero.setBackground(u.grad(u.card, u.dark ? 0xFF2A241D : 0xFFF7F1E8, 22));
        hero.setElevation(u.dp(2));
        bedView = new BedView(this, u.line, u.accent, u.fg, u.muted);
        int bh = (int)(u.screenH * 0.155f);
        if (bh < u.dp(112)) bh = u.dp(112);
        if (bh > u.dp(190)) bh = u.dp(190);
        hero.addView(bedView, new LinearLayout.LayoutParams(-1, bh));
        angleText = u.text("—", 15.5f, u.fg, true);
        angleText.setGravity(Gravity.CENTER);
        angleText.setPadding(0, u.dp(12), 0, u.dp(2));
        hero.addView(angleText);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = u.dp(6);
        hero.setLayoutParams(hp);
        c.addView(hero);

        // ── 자세 ──
        c.addView(u.head("자세"));
        LinearLayout r1 = u.row(0);
        r1.addView(poseBtn("평평하게", 0, 0), u.w(1, 4));
        r1.addView(poseBtn("독서", 80, 0), u.w(1, 4));
        c.addView(r1);
        LinearLayout r2 = u.row(0);
        r2.addView(poseBtn("다리 올림", 0, 35), u.w(1, 4));
        r2.addView(poseBtn("무중력", 30, 30), u.w(1, 4));
        c.addView(r2);

        // ── 조절 ──
        c.addView(u.head("조절"));
        c.addView(stepper("상체", "11", 80));
        c.addView(stepper("다리", "13", 45));
        tableRow = stepper("테이블", "14", 850);
        c.addView(tableRow);
        tableToggle = u.btn("", 0x00000000, u.muted, 0, 12.5f, 9,
                new Runnable(){ public void run(){ toggleTable(); }});
        c.addView(tableToggle);
        applyTable();

        // ── 내 자세 ──
        c.addView(u.head("내 자세"));
        LinearLayout slot = u.col();
        slot.setPadding(u.dp(12), u.dp(13), u.dp(12), u.dp(6));
        slot.setBackground(u.box(u.card, u.line, 18));
        LinearLayout m1 = u.row(0);
        m1.addView(u.btn("A 불러오기", u.bg, u.fg, u.line, 14.5f, 14,
                new Runnable(){ public void run(){ recallSlot("A"); }}), u.w(1, 4));
        m1.addView(u.btn("B 불러오기", u.bg, u.fg, u.line, 14.5f, 14,
                new Runnable(){ public void run(){ recallSlot("B"); }}), u.w(1, 4));
        slot.addView(m1);
        LinearLayout m2 = u.row(0);
        m2.addView(u.btn("지금 자세를 A로", 0x00000000, u.muted, 0, 12.5f, 8,
                new Runnable(){ public void run(){ saveSlot("A"); }}), u.w(1, 4));
        m2.addView(u.btn("지금 자세를 B로", 0x00000000, u.muted, 0, 12.5f, 8,
                new Runnable(){ public void run(){ saveSlot("B"); }}), u.w(1, 4));
        slot.addView(m2);
        slotText = u.note("");
        slotText.setGravity(Gravity.CENTER);
        slot.addView(slotText);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = u.dp(6);
        slot.setLayoutParams(slp);
        c.addView(slot);

        // ── 조명 · 소리 ──
        c.addView(u.head("조명 · 소리"));
        LinearLayout t1 = u.row(0);
        lightState = u.pill("꺼짐", u.muted, u.bg, u.line);
        t1.addView(toggleCard(lightState, "52", "무드등", Glyph.LAMP), u.w(1, 4));
        speakerState = u.pill("꺼짐", u.muted, u.bg, u.line);
        t1.addView(toggleCard(speakerState, "61", "스피커", Glyph.SPEAKER), u.w(1, 4));
        c.addView(t1);
        c.addView(u.note("스피커를 켠 뒤 폰 블루투스에서 XDADADZ 에 연결하면 소리가 납니다."));

        // ── 침대 관리 ──
        c.addView(u.head("침대 관리"));
        LinearLayout mg = u.col();
        mg.setBackground(u.box(u.card, u.line, 18));
        mg.addView(linkRow("침대 추가하기", new Runnable(){ public void run(){ startWizard(); }}));
        mg.addView(u.hair());
        // 내 폰에 등록된 침대일 때만 보이는 줄들
        ownerRows.clear();
        View oh1 = u.hair();
        View or1 = linkRow("이름 바꾸기", new Runnable(){ public void run(){ renameBed(); }});
        mg.addView(or1); mg.addView(oh1);
        ownerRows.add(or1); ownerRows.add(oh1);

        mg.addView(linkRow("이 폰 이름 바꾸기", new Runnable(){ public void run(){ renamePhone(); }}));
        mg.addView(u.hair());
        mg.addView(linkRow("새 버전 확인", new Runnable(){ public void run(){ checkUpdate(true); }}));

        View oh2 = u.hair();
        View or2 = linkRow("이 침대를 다른 폰으로 넘기기", new Runnable(){ public void run(){ handoverPick(); }});
        View oh3 = u.hair();
        View or3 = linkRow("이 침대 목록에서 지우기", new Runnable(){ public void run(){ removeBed(); }});
        mg.addView(oh2); mg.addView(or2); mg.addView(oh3); mg.addView(or3);
        ownerRows.add(oh2); ownerRows.add(or2); ownerRows.add(oh3); ownerRows.add(or3);
        applyOwnerRows();
        mg.addView(u.hair());
        mg.addView(linkRow("개발자 모드", new Runnable(){ public void run(){ showDev(true); }}));
        battHair = u.hair();
        mg.addView(battHair);
        battRow = linkRow("배터리 제한 풀기", new Runnable(){ public void run(){ askBattery(); }});
        mg.addView(battRow);
        boolean bok = battOk();
        battRow.setVisibility(bok ? View.GONE : View.VISIBLE);
        battHair.setVisibility(bok ? View.GONE : View.VISIBLE);
        c.addView(mg);
        c.addView(spacer(6));

        // ── 정지 ──
        LinearLayout bar = u.col();
        bar.setBackgroundColor(u.bg);
        bar.setPadding(u.dp(18), u.dp(10), u.dp(18), u.dp(10));
        bar.setElevation(u.dp(10));
        LinearLayout stop = new LinearLayout(this);
        stop.setOrientation(LinearLayout.HORIZONTAL);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(0, u.dp(18), 0, u.dp(18));
        ImageView si = new ImageView(this);
        si.setImageDrawable(new Glyph(Glyph.STOP, 0xFFFFFFFF, u.dp(19)));
        stop.addView(si, new LinearLayout.LayoutParams(u.dp(19), u.dp(19)));
        TextView st = u.text("정지", 17.5f, 0xFFFFFFFF, true);
        st.setPadding(u.dp(10), 0, 0, 0);
        stop.addView(st, new LinearLayout.LayoutParams(-2, -2));
        u.ripple(stop, u.danger, 0, 18);
        stop.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ doStop(); }});
        bar.addView(stop, new LinearLayout.LayoutParams(-1, -2));
        stopBtn = stop;
        outer.addView(bar);
        return outer;
    }

    /** 오른쪽에 화살표가 있는 메뉴 한 줄 */
    private View linkRow(String title, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u.dp(16), u.dp(15), u.dp(14), u.dp(15));
        row.setMinimumHeight(u.rawDp(52));
        row.addView(u.text(title, 15, u.fg, false), new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView ch = new ImageView(this);
        ch.setImageDrawable(new Glyph(Glyph.CHEVRON, u.muted, u.dp(16)));
        row.addView(ch, new LinearLayout.LayoutParams(u.dp(16), u.dp(16)));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ r.run(); }});
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** 탭 버튼: 긴 이름이어도 한 줄로 줄여서 보여준다 */
    private void tab(Button t) {
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(u.dp(4), t.getPaddingTop(), u.dp(4), t.getPaddingBottom());
    }

    private void buildTabs() {
        tabRow.removeAllViews();
        int total = beds.size() + remotes.size();
        if (total < 2) { tabRow.setVisibility(View.GONE); return; }
        tabRow.setVisibility(View.VISIBLE);
        float ts = total >= 4 ? 11.5f : 13f;
        for (int i = 0; i < beds.size(); i++) {
            final int idx = i;
            boolean on = !selRemote && i == sel;
            Button t = u.btn(beds.get(i).name, on ? u.accent : u.card,
                    on ? 0xFFFFFFFF : u.fg, on ? 0 : u.line, ts, 12,
                    new Runnable(){ public void run(){
                        sel = idx; selRemote = false; prefs.edit().putInt("sel", sel).apply();
                        targets.clear(); buildTabs(); applyTable(); refresh(); }});
            tab(t);
            tabRow.addView(t, u.w(1, 3));
        }
        for (int i = 0; i < remotes.size(); i++) {
            final int idx = i;
            boolean on = selRemote && i == sel;
            Button t = u.btn(remotes.get(i).name + " ↗", on ? u.accent : u.card,
                    on ? 0xFFFFFFFF : u.fg, on ? 0 : u.line, ts, 12,
                    new Runnable(){ public void run(){
                        sel = idx; selRemote = true;
                        targets.clear(); buildTabs(); applyTable(); refresh(); }});
            tab(t);
            tabRow.addView(t, u.w(1, 3));
        }
    }

    private View poseBtn(final String name, final int body, final int leg) {
        LinearLayout box = u.col();
        box.setPadding(u.dp(10), u.dp(11), u.dp(10), u.dp(11));
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        BedView ic = new BedView(this, u.line, u.muted, u.muted, u.muted);
        ic.mini(body, leg);
        box.addView(ic, new LinearLayout.LayoutParams(u.dp(56), u.dp(26)));
        TextView t = u.text(name, 14f, u.fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, u.dp(7), 0, 0);
        TextView s = u.text(body + "° · " + leg + "°", 11f, u.muted, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, u.dp(1), 0, 0);
        box.addView(t); box.addView(s);
        u.ripple(box, u.card, u.line, 18);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { preset(body, leg); toast(name); } });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = u.dp(8);
        box.setLayoutParams(p);
        return box;
    }

    private View toggleCard(final TextView state, final String pin, String name, int glyph) {
        LinearLayout box = u.col();
        box.setPadding(u.dp(12), u.dp(15), u.dp(12), u.dp(14));
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Glyph(glyph, u.fg, u.dp(23)));
        box.addView(ic, new LinearLayout.LayoutParams(u.dp(23), u.dp(23)));
        TextView nm = u.text(name, 14.5f, u.fg, true);
        nm.setGravity(Gravity.CENTER);
        nm.setPadding(0, u.dp(9), 0, u.dp(7));
        box.addView(nm);
        box.addView(state, new LinearLayout.LayoutParams(-2, -2));
        u.ripple(box, u.card, u.line, 18);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = pinValue("V" + pin, 0) > 0;
                sendPin(pin, on ? "0" : "1");
                toast(on ? "껐습니다" : "켰습니다");
            } });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = u.dp(8);
        box.setLayoutParams(p);
        return box;
    }

    private View stepper(final String name, final String pin, final int max) {
        LinearLayout box = u.col();
        box.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(6));
        box.setBackground(u.box(u.card, u.line, 18));

        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.BOTTOM);
        hd.addView(u.text(name, 14, u.muted, true), new LinearLayout.LayoutParams(-2, -2));
        hd.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        final TextView vl = u.text("—", 24, u.fg, true);
        vl.setIncludeFontPadding(false);
        hd.addView(vl, new LinearLayout.LayoutParams(-2, -2));
        box.addView(hd, new LinearLayout.LayoutParams(-1, -2));
        if (pin.equals("11")) headVal = vl;
        else if (pin.equals("13")) legVal = vl;
        else tableVal = vl;

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, u.dp(4), 0, 0);

        Button minus = u.circleBtn("−", new Runnable(){ public void run(){ step(pin, max, -1); }});
        Button plus  = u.circleBtn("+", new Runnable(){ public void run(){ step(pin, max, +1); }});
        LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(u.dp(46), u.dp(46));

        final Slider sb = new Slider(this, u.line, u.accent, u.card, u.accent);
        sb.setMax(max);
        sb.setListener(new Slider.Listener() {
            public void onSlide(int v, boolean done) {
                vl.setText(v + (pin.equals("14") ? "" : "°") + (done ? "" : " →"));
                if (!done) {
                    if (!dragging) { dragging = true; askNow(); }
                } else {
                    dragging = false;
                    targets.put(pin, v);
                    sendPin(pin, String.valueOf(v));
                }
            } });
        bars.put(pin, sb);

        r.addView(minus, cb);
        r.addView(sb, new LinearLayout.LayoutParams(0, u.dp(46), 1f));
        r.addView(plus, cb);
        box.addView(r, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = u.dp(8);
        box.setLayoutParams(p);
        return box;
    }

    private void step(String pin, int max, int delta) {
        Integer t = targets.get(pin);
        int base = t != null ? t : pinValue("V" + pin, 0);
        if (pin.equals("14")) delta *= 10;
        int v = Math.max(0, Math.min(max, base + delta));
        targets.put(pin, v);
        sendPin(pin, String.valueOf(v));
    }

    /** 이 침대를 어느 폰으로 넘길지 고른다 */
    private void handoverPick() {
        final Beds.Bed b = cur();
        if (b == null) { toast("먼저 침대를 고르세요"); return; }

        final List<LanPeers.Peer> ps = lan.peers();
        final List<String> labels = new ArrayList<>();
        final List<String> ips = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (LanPeers.Peer p : ps) {
            if (p.ip == null || p.ip.isEmpty()) continue;
            String nm = (p.phone == null || p.phone.trim().isEmpty()) ? "이름 없는 폰" : p.phone.trim();
            labels.add(nm + "   " + p.ip);
            ips.add(p.ip);
            names.add(nm);
        }
        labels.add("주소를 직접 입력하기");
        ips.add("");
        names.add("");

        new android.app.AlertDialog.Builder(this)
            .setTitle(b.name + " 를 어느 폰으로 넘길까요?")
            .setItems(labels.toArray(new String[0]),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        String ip = ips.get(which);
                        if (ip.isEmpty()) handoverManual(b);
                        else handoverStart(b, ip, names.get(which));
                    } })
            .setNegativeButton("취소", null)
            .setNeutralButton("도움말", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("받을 폰이 목록에 없나요?")
                        .setMessage("받을 폰에서 이 앱을 설치하고 한 번 열어두세요.\n"
                                + "같은 와이파이에 있으면 잠시 뒤 목록에 나타납니다.\n\n"
                                + "그래도 안 보이면 받을 폰의 주소를 직접 넣으면 됩니다.")
                        .setPositiveButton("알겠습니다", null).show();
                } })
            .show();
    }

    private void handoverManual(final Beds.Bed b) {
        final EditText e = u.input("예: 192.168.0.10", "", false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("받을 폰의 주소")
            .setMessage("받을 폰에서 앱을 열고 개발자 모드에 나오는 주소를 그대로 넣으세요.")
            .setView(e)
            .setPositiveButton("넘기기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String ip = e.getText().toString().trim();
                    if (!Net.isLan(ip)) { toast("집 안 주소가 아닙니다"); return; }
                    handoverStart(b, ip, "");
                } })
            .setNegativeButton("취소", null).show();
    }

    /** 넘기기 모드로 마법사를 연다 — 이름·토큰·와이파이는 그대로, 주소만 상대 폰 것으로 */
    private void handoverStart(Beds.Bed b, String ip, String phoneName) {
        wizHandover = true;
        wizName = b.name;
        wizToken = b.token;
        wizHost = ip;
        wizTargetPhone = phoneName;
        wizSsid = prefs.getString("b_" + b.token + "_ssid", prefs.getString("homeSsid", ""));
        wizPass = prefs.getString("pass", "");
        wizShownOrphan = true;          // 넘기기 중엔 "바로 추가" 안내를 띄우지 않는다
        step = 1;   // 넘기기도 BOOT 버튼부터 시작한다
        root.removeAllViews();
        wizPane = buildWizard();
        root.addView(wizPane);
    }

    /** 넘기기를 마치고 내 목록에서 뺀다 */
    private void handoverDone() {
        Beds.Bed b = null;
        for (Beds.Bed x : beds) if (x.token.equals(wizToken)) b = x;
        if (b != null) { beds.remove(b); Beds.save(prefs, beds); }
        sel = 0; selRemote = false;
        prefs.edit().putInt("sel", 0).apply();
        wizHandover = false;
        final String who = wizTargetPhone.isEmpty() ? "받는 폰" : wizTargetPhone;
        rebuild();
        new android.app.AlertDialog.Builder(this)
            .setTitle("넘겼습니다")
            .setMessage(who + " 에서 앱을 열면\n"
                    + "\"접속해 있는 침대가 있습니다\" 안내가 뜹니다.\n"
                    + "그걸 한 번 누르면 끝입니다.\n\n"
                    + "잠시 뒤 이 앱에도 그 침대가 다시 보입니다.\n"
                    + "이름 옆에 ↗ 표시가 붙은 쪽이 그 침대입니다.")
            .setPositiveButton("알겠습니다", null).show();
    }

    private void startWizard() {
        wizName = ""; wizToken = ""; wizSsid = ""; wizPass = ""; step = 0; wizShownOrphan = false;
        wizHandover = false; wizTargetPhone = "";
        root.removeAllViews();
        wizPane = buildWizard();
        root.addView(wizPane);
    }

    private void renameBed() {
        final Beds.Bed b = cur();
        if (b == null) return;
        final EditText e = u.input("이름", b.name, false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("이름 바꾸기").setView(e)
            .setPositiveButton("저장", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) { b.name = n; Beds.save(prefs, beds); buildTabs(); toast("바꿨습니다"); }
                } })
            .setNegativeButton("취소", null).show();
    }

    private void renamePhone() {
        final EditText e = u.input("폰 이름", prefs.getString("phoneName", android.os.Build.MODEL), false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("이 폰 이름").setMessage("이웃 폰 목록에 이렇게 보입니다.").setView(e)
            .setPositiveButton("저장", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) {
                        prefs.edit().putString("phoneName", n).apply();
                        lan.stop();
                        lan.start(MainActivity.this, n, prefs.getString("phoneId", "x"));
                        toast("바꿨습니다");
                    } } })
            .setNegativeButton("취소", null).show();
    }

    private void removeBed() {
        final Beds.Bed b = cur();
        if (b == null) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle(b.name + " 를 지울까요?")
            .setMessage("앱 목록에서만 사라집니다. 침대 자체는 그대로이고, 다시 추가할 수 있습니다.")
            .setPositiveButton("지우기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    beds.remove(b); Beds.save(prefs, beds);
                    sel = 0; prefs.edit().putInt("sel", 0).apply();
                    rebuild();
                } })
            .setNegativeButton("취소", null).show();
    }

    // ── 개발자 화면 ────────────────────────────────────
    private View buildDev() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(u.bg);
        LinearLayout c = u.col();
        c.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(28));
        sv.addView(c);

        LinearLayout top = u.row(8);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(u.text("개발자 모드", 19, u.fg, true), new LinearLayout.LayoutParams(-2, -2));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        Button back = u.small("닫기", new Runnable(){ public void run(){ showDev(false); }});
        back.setLayoutParams(new LinearLayout.LayoutParams(u.dp(80), -2));
        top.addView(back);
        c.addView(top);

        c.addView(u.head("상태"));
        addrView = u.text("—", 12.5f, u.fg, false);
        addrView.setTypeface(Typeface.MONOSPACE);
        c.addView(u.card(addrView, 13));
        LinearLayout sr = u.row(0);
        sr.addView(u.small("서버 켜기", new Runnable(){ public void run(){ startServer(); }}), u.w(1,4));
        sr.addView(u.small("서버 끄기", new Runnable(){ public void run(){ stopServer(); }}), u.w(1,4));
        c.addView(sr);

        c.addView(u.head("정지 번호 찾기"));
        c.addView(u.note("후보를 절반씩 줄입니다. 한 묶음을 눌러보고 멈췄는지만 답하면 됩니다."));
        finderView = u.text("아직 시작하지 않았습니다.", 12.5f, u.fg, false);
        finderView.setTypeface(Typeface.MONOSPACE);
        c.addView(u.card(finderView, 13));
        c.addView(u.small("이번 묶음 시험", new Runnable(){ public void run(){ finderStart(); }}));
        LinearLayout fa = u.row(0);
        fa.addView(u.small("멈췄어요", new Runnable(){ public void run(){ finderAnswer(true); }}), u.w(1,4));
        fa.addView(u.small("안 멈췄어요", new Runnable(){ public void run(){ finderAnswer(false); }}), u.w(1,4));
        c.addView(fa);
        c.addView(u.small("처음부터 다시", new Runnable(){ public void run(){
            poolInit(); finderView.setText("후보 " + pool.size() + "개로 초기화했습니다."); }}));
        stopPinIn = u.input("정지 번호", prefs.getString("stopPin", "41"), true);
        c.addView(stopPinIn);
        c.addView(u.small("정지 번호로 저장", new Runnable(){ public void run(){
            String p = stopPinIn.getText().toString().trim();
            if (p.isEmpty()) return;
            prefs.edit().putString("stopPin", p).apply();
            toast("저장했습니다"); }}));

        c.addView(u.head("침대가 알려준 값"));
        pinView = u.text("아직 없습니다", 12.5f, u.fg, false);
        pinView.setTypeface(Typeface.MONOSPACE);
        c.addView(u.card(pinView, 13));

        c.addView(u.head("직접 보내기"));
        LinearLayout mr = u.row(0);
        pinIn = u.input("핀", "", true);
        valIn = u.input("값", "1", false);
        mr.addView(pinIn, u.w(1,3));
        mr.addView(valIn, u.w(1,3));
        mr.addView(u.small("보내기", new Runnable(){ public void run(){
            String p = pinIn.getText().toString().trim();
            if (p.isEmpty()) { toast("핀 번호를 넣어주세요"); return; }
            String v = valIn.getText().toString().trim();
            sendPin(p, v.isEmpty() ? "1" : v); }}), u.w(1,3));
        c.addView(mr);

        c.addView(u.head("기록"));
        logView = u.text("", 11.5f, u.fg, false);
        logView.setTypeface(Typeface.MONOSPACE);
        c.addView(u.card(logView, 13));
        return sv;
    }

    private void showDev(boolean on) {
        devPane.setVisibility(on ? View.VISIBLE : View.GONE);
        mainPane.setVisibility(on ? View.GONE : View.VISIBLE);
        refresh();
    }

    // ── 주기 ───────────────────────────────────────────
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!ticking) return;
            refresh();
            ui.postDelayed(this, 800);
        } };
    private final Runnable poll = new Runnable() {
        public void run() {
            if (!ticking) return;
            askNow();
            ui.postDelayed(this, 2500);
        } };

    private final Runnable peerPoll = new Runnable() {
        public void run() {
            if (!ticking) return;
            bg(new Runnable(){ public void run(){
                lan.refresh();
                final List<LanPeers.RemoteBed> rb = lan.remoteBeds();
                post(new Runnable(){ public void run(){
                    boolean changed = rb.size() != remotes.size();
                    boolean wasNone = remotes.isEmpty();
                    remotes = rb;
                    if (changed && tabRow != null) buildTabs();
                    // 마법사 첫 화면에 있을 때만, 안내를 띄우기 위해 다시 그린다
                    if (wasNone && !remotes.isEmpty() && beds.isEmpty()
                            && wizBox != null && step == 0 && !wizHandover) {
                        String typed = wizNameIn != null ? wizNameIn.getText().toString() : null;
                        renderStep();
                        if (typed != null && !typed.isEmpty() && wizNameIn != null)
                            wizNameIn.setText(typed);
                    }
                }});
            }});
            if (ticking) ui.postDelayed(this, 3000);
        } };

    private void askBurst() {
        for (int i = 1; i <= 8; i++)
            ui.postDelayed(new Runnable(){ public void run(){ askNow(); }}, i * 900L);
    }

    private void askNow() {
        if (curRemote() != null) {
            final LanPeers.RemoteBed r = curRemote();
            bg(new Runnable(){ public void run(){
                lan.send(r.peerIp, r.token, "11", "read");
                lan.send(r.peerIp, r.token, "13", "read"); }});
            return;
        }
        final BedServer.Dev d = dev();
        if (d == null) return;
        final boolean tb = hasTable();
        bg(new Runnable(){ public void run(){
            App.server().read(d, "11");
            App.server().read(d, "13");
            if (tb) App.server().read(d, "14");
        }});
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        App.setCallback(null);
        super.onDestroy();
    }

    // ── 새로고침 ───────────────────────────────────────
    private void refresh() {
        if (beds.isEmpty() && !remotes.isEmpty()) {
            selRemote = true;
            if (sel >= remotes.size()) sel = 0;
        }
        if (beds.isEmpty() && remotes.isEmpty()) {
            if (wizBox != null && step == 0 && orphanToken() != null
                    && wizBox.getChildCount() > 0 && !wizShownOrphan) {
                wizShownOrphan = true;
                renderStep();
            }
            return;
        }
        if (mainPane == null) return;
        LanPeers.RemoteBed rb = curRemote();
        boolean on = rb != null ? rb.online : dev() != null;

        statusDot.setText(rb != null
                ? (on ? "이웃 폰 연결" : "이웃 꺼짐")
                : (on ? "연결됨" : (App.server().isRunning() ? "기다리는 중" : "서버 꺼짐")));
        statusDot.setTextColor(on ? 0xFFFFFFFF : u.muted);
        statusDot.setBackground(u.box(on ? u.ok : u.card, on ? 0 : u.line, 20));

        int h = pinValue("V11", -1), l = pinValue("V13", -1), tb = pinValue("V14", -1);
        Integer ht = targets.get("11"), lt = targets.get("13"), tt = targets.get("14");
        if (ht != null && h >= 0 && Math.abs(h - ht) <= 2) { targets.remove("11"); ht = null; }
        if (lt != null && l >= 0 && Math.abs(l - lt) <= 2) { targets.remove("13"); lt = null; }
        if (tt != null && tb >= 0 && Math.abs(tb - tt) <= 20) { targets.remove("14"); tt = null; }

        bedView.set(Math.max(h,0), Math.max(l,0), ht == null ? -1 : ht, lt == null ? -1 : lt);
        if (!on) { angleText.setText("침대를 기다리는 중"); angleText.setTextColor(u.muted); }
        else if (ht != null || lt != null) {
            angleText.setText("움직이는 중 …"); angleText.setTextColor(u.accent);
        } else {
            angleText.setText("상체 " + Math.max(h,0) + "°    ·    다리 " + Math.max(l,0) + "°");
            angleText.setTextColor(u.fg);
        }

        headVal.setText(label(h, ht, "°"));
        legVal.setText(label(l, lt, "°"));
        tableVal.setText(label(tb, tt, ""));
        if (!dragging) { syncBar("11", h, ht); syncBar("13", l, lt); syncBar("14", tb, tt); }

        boolean lightOn = pinValue("V52", 0) > 0, spkOn = pinValue("V61", 0) > 0;
        statePill(lightState, lightOn);
        statePill(speakerState, spkOn);

        applyOwnerRows();
        slotText.setText(slotLine());
        stopBtn.setAlpha(on ? 1f : 0.45f);
        checkAddress(rb != null, on);

        if (devPane != null && devPane.getVisibility() == View.VISIBLE) refreshDev();
    }

    /** 폰 주소가 침대에 심어준 주소와 달라졌는지 본다 */
    private void checkAddress(boolean remote, boolean online) {
        if (warnCard == null) return;
        if (remote || online) { warnCard.setVisibility(View.GONE); return; }
        String ip = Net.myWifiIp(this);
        String want = prefs.getString("b_" + curToken() + "_host", prefs.getString("homeIp", ""));
        if (ip == null || !Net.isLan(ip) || want.isEmpty()) { warnCard.setVisibility(View.GONE); return; }
        if (ip.equals(want)) { warnCard.setVisibility(View.GONE); return; }

        String ssid = currentSsid();
        if (!sameNet(ip, want)) {
            warnView.setText("이 와이파이는 침대와 다른 네트워크입니다.\n\n"
                    + "침대가 있는 곳   " + net(want) + ".x\n"
                    + "지금 이 폰      " + net(ip) + ".x"
                    + (ssid == null ? "" : "  (" + ssid + ")") + "\n\n"
                    + "· " + net(want) + ".x 를 쓰는 와이파이로 옮기면 바로 됩니다\n"
                    + "· 이 와이파이를 쓰시려면 그 공유기를 '익스텐더(AP)' 모드로 바꿔야 합니다");
        } else {
            warnView.setText("폰 주소가 바뀌었습니다.\n\n"
                    + "침대는 " + want + " 를 찾아가는데\n"
                    + "지금 이 폰은 " + ip + " 입니다.\n\n"
                    + "· 폰 와이파이 설정에서 주소를 " + want + " 로 고정하거나\n"
                    + "· 침대 추가하기로 주소를 다시 심어주세요");
        }
        warnCard.setVisibility(View.VISIBLE);
    }

    private static String net(String ip) {
        if (ip == null) return "";
        int i = ip.lastIndexOf('.');
        return i < 0 ? ip : ip.substring(0, i);
    }

    private static boolean sameNet(String a, String b) {
        return net(a).equals(net(b));
    }

    private void syncBar(String pin, int cur, Integer target) {
        Slider sb = bars.get(pin);
        if (sb == null) return;
        if (cur >= 0) sb.setValue(Math.min(cur, sb.getMax()));
        sb.setTarget(target == null ? -1 : Math.min(target, sb.getMax()));
    }

    private String label(int v, Integer t, String unit) {
        if (t != null) return t + unit + " →";
        return v < 0 ? "—" : (v + unit);
    }

    private void refreshDev() {
        String ip = Net.myWifiIp(this);
        if (ip != null && Net.isLan(ip) && !ip.startsWith("192.168.4."))
            prefs.edit().putString("homeIp", ip).apply();
        StringBuilder a = new StringBuilder();
        a.append("서버      ").append(App.server().isRunning() ? "켜짐" : "꺼짐").append('\n');
        a.append("폰 주소    ").append(ip == null ? "와이파이 없음" : ip)
         .append(" : ").append(BedServer.PORT).append('\n');
        for (Beds.Bed b : beds) {
            BedServer.Dev dd = App.server().byToken(b.token);
            a.append(b.name).append("   ").append(dd == null ? "연결 안 됨" : dd.ip).append('\n');
        }
        a.append("\n이웃 폰\n");
        List<LanPeers.Peer> ps = lan.peers();
        if (ps.isEmpty()) a.append("   찾은 폰이 없습니다\n");
        for (LanPeers.Peer p : ps) {
            a.append("   ").append(p.phone).append("  ").append(p.ip)
             .append("  침대 ").append(p.beds.size()).append("대\n");
        }
        addrView.setText(a.toString().trim());

        StringBuilder pv = new StringBuilder();
        BedServer.Dev d = dev();
        if (d != null) for (Map.Entry<String,String> e : d.pins.entrySet())
            pv.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        pinView.setText(pv.length() == 0 ? "아직 없습니다" : pv.toString().trim());

        StringBuilder lg = new StringBuilder();
        for (String[] x : App.log()) lg.append(x[0]).append("  ").append(x[1]).append("  ").append(x[2]).append('\n');
        logView.setText(lg.length() == 0 ? "아직 기록이 없습니다" : lg.toString().trim());
    }

    // ── 동작 ───────────────────────────────────────────
    private void startServer() {
        Intent i = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }
    private void stopServer() {
        prefs.edit().putBoolean("serverOn", false).apply();
        stopService(new Intent(this, ServerService.class));
    }

    private BedServer.Dev dev() {
        Beds.Bed b = cur();
        return b == null ? null : App.server().byToken(b.token);
    }

    private int pinValue(String key, int fallback) {
        LanPeers.RemoteBed r = curRemote();
        String v = null;
        if (r != null) v = r.pins.get(key);
        else { BedServer.Dev d = dev(); if (d != null) v = d.pins.get(key); }
        if (v != null) try { return (int) Double.parseDouble(v.trim()); } catch (Exception ignored) {}
        return fallback;
    }

    private void sendPin(final String pin, final String value) {
        final LanPeers.RemoteBed r = curRemote();
        if (r != null) {
            if (!r.online) { toast("그 침대가 지금 꺼져 있습니다"); return; }
            bg(new Runnable(){ public void run(){
                final boolean ok = lan.send(r.peerIp, r.token, pin, value);
                if (!ok) post(new Runnable(){ public void run(){ toast("이웃 폰에 닿지 않습니다"); }});
            }});
            return;
        }
        final BedServer.Dev d = dev();
        if (d == null) { toast("이 침대가 접속해 있지 않습니다"); return; }
        if (pin.equals("11") || pin.equals("13") || pin.equals("14")
                || pin.equals(prefs.getString("stopPin","41"))) askBurst();
        bg(new Runnable(){ public void run(){ App.server().write(d, pin, value); }});
    }

    private void preset(int body, int leg) {
        targets.put("11", body); targets.put("13", leg);
        sendPin("11", String.valueOf(body));
        sendPin("13", String.valueOf(leg));
    }

    private void doStop() {
        targets.clear();
        sendPin(prefs.getString("stopPin", "41"), "1");
        toast("정지");
    }

    private String keyOf(String n) { return "b_" + curToken() + "_" + n; }

    private boolean hasTable() { return prefs.getBoolean(keyOf("table"), false); }
    /** 켜짐/꺼짐 알약 표시 */
    private void statePill(TextView t, boolean on) {
        t.setText(on ? "켜짐" : "꺼짐");
        t.setTextColor(on ? 0xFFFFFFFF : u.muted);
        t.setBackground(u.box(on ? u.accent : u.bg, on ? 0 : u.line, 20));
    }

    /** 새 버전 확인 — manual 이면 결과를 꼭 알려준다 */
    private void checkUpdate(final boolean manual) {
        if (manual) toast("확인하는 중…");
        Updates.check(this, ui, new Updates.Callback() {
            public void done(Updates.Info n) {
                prefs.edit().putLong("updCheckedAt", System.currentTimeMillis()).apply();
                if (n != null) showUpdate(n);
                else if (manual) toast("지금이 최신 버전입니다 (" + Updates.installed(MainActivity.this) + ")");
            } });
    }

    private void showUpdate(Updates.Info n) {
        pending = n;
        if (updCard == null || updView == null) return;
        String note = n.notes == null ? "" : n.notes.trim();
        if (note.length() > 160) note = note.substring(0, 160) + "…";
        updView.setText("새 버전 " + n.version + " 이 나왔습니다."
                + (note.isEmpty() ? "" : "\n\n" + note));
        updCard.setVisibility(View.VISIBLE);
    }

    private void openUpdate() {
        if (pending == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pending.url)));
        } catch (Throwable t) { toast("인터넷 창을 열 수 없습니다"); }
    }

    /** 이웃 폰 침대를 고른 동안에는 주인용 메뉴를 감춘다 */
    private void applyOwnerRows() {
        int vis = (!selRemote && cur() != null) ? View.VISIBLE : View.GONE;
        for (View v : ownerRows) if (v != null) v.setVisibility(vis);
    }

    private void applyTable() {
        boolean t = hasTable();
        tableToggle.setText(t ? "테이블 숨기기" : "테이블 사용 중이면 누르세요");
        tableRow.setVisibility(t ? View.VISIBLE : View.GONE);
    }
    private void toggleTable() {
        prefs.edit().putBoolean(keyOf("table"), !hasTable()).apply();
        applyTable();
    }

    private void saveSlot(String s) {
        int b = pinValue("V11", -1), l = pinValue("V13", -1);
        if (b < 0) { toast("침대 값이 아직 없습니다"); return; }
        prefs.edit().putInt(keyOf(s + "b"), b)
                    .putInt(keyOf(s + "l"), Math.max(l,0)).apply();
        toast(s + "에 저장했습니다"); refresh();
    }
    private void recallSlot(String s) {
        int b = prefs.getInt(keyOf(s + "b"), -1);
        if (b < 0) { toast(s + "에 저장된 자세가 없습니다"); return; }
        preset(b, prefs.getInt(keyOf(s + "l"), 0));
        toast(s + " 자세로");
    }
    private String slotLine() {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{"A","B"}) {
            int b = prefs.getInt(keyOf(s + "b"), -1);
            sb.append(s).append(" ").append(b < 0 ? "비어 있음"
                    : (b + "도 · " + prefs.getInt(keyOf(s + "l"), 0) + "도")).append("      ");
        }
        return sb.toString().trim();
    }

    private void poolInit() { pool.clear(); for (int c : CANDIDATES) pool.add(c); trying.clear(); }

    private void finderStart() {
        if (dev() == null) { toast("침대가 접속해 있지 않습니다"); return; }
        if (pool.isEmpty()) poolInit();
        if (pool.size() == 1) { stopPinIn.setText(String.valueOf(pool.get(0)));
            finderView.setText("찾았습니다.  V" + pool.get(0)); return; }
        trying.clear();
        int half = (pool.size() + 1) / 2;
        for (int i = 0; i < half; i++) trying.add(pool.get(i));
        int cur2 = pinValue("V11", 0);
        int far = cur2 < 40 ? 80 : 0;
        targets.put("11", far);
        sendPin("11", String.valueOf(far));
        finderView.setText("후보 " + pool.size() + "개 중 " + trying.size() + "개 시험.\n4초 뒤 눌러봅니다.");
        ui.postDelayed(finderFire, 4000);
    }

    private final Runnable finderFire = new Runnable() {
        public void run() {
            final List<Integer> batch = new ArrayList<>(trying);
            final BedServer.Dev d = dev();
            bg(new Runnable(){ public void run(){
                if (d == null) return;
                for (int pin : batch) {
                    App.server().write(d, String.valueOf(pin), "1");
                    try { Thread.sleep(120); } catch (Exception ignored) {}
                } }});
            finderView.setText("눌러봤습니다 (" + batch.size() + "개)\n\n멈췄나요?");
        } };

    private void finderAnswer(boolean stopped) {
        if (trying.isEmpty()) { toast("먼저 시험을 누르세요"); return; }
        if (stopped) { pool.clear(); pool.addAll(trying); } else pool.removeAll(trying);
        trying.clear();
        if (pool.isEmpty()) { finderView.setText("후보가 모두 떨어졌습니다."); return; }
        if (pool.size() == 1) { stopPinIn.setText(String.valueOf(pool.get(0)));
            finderView.setText("찾았습니다.  V" + pool.get(0)); return; }
        finderView.setText("남은 후보 " + pool.size() + "개\n다시 '이번 묶음 시험'을 누르세요.");
    }

    /** 지금 폰이 붙어 있는 와이파이 이름 (위치 권한 필요) */
    private String currentSsid() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return null;
            android.net.wifi.WifiInfo i = wm.getConnectionInfo();
            if (i == null) return null;
            String ss = i.getSSID();
            if (ss == null) return null;
            ss = ss.replace("\"", "").trim();
            if (ss.isEmpty() || ss.equals("<unknown ssid>") || ss.startsWith("0x")) return null;
            return ss;
        } catch (Exception e) { return null; }
    }

    private boolean hasLocationPerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askLocationPerm() {
        if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 7);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 7) {
            String ss = currentSsid();
            if (ss != null) { wizSsid = ss; prefs.edit().putString("homeSsid", ss).apply(); toast("와이파이 이름을 가져왔습니다"); }
            else toast("가져오지 못했습니다. 위치 기능이 켜져 있는지 확인해주세요");
            if (beds.isEmpty() && wizBox != null) renderStep();
        }
    }

    private void bg(Runnable r) { new Thread(r).start(); }
    private void post(Runnable r) { ui.post(r); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
