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
    private View mainPane, setPane, wizPane;

    /** 지금 보이는 화면 */
    private static final int SCR_MAIN = 0, SCR_SETTINGS = 1, SCR_WIZARD = 2;
    private int screen = SCR_MAIN;

    private List<Beds.Bed> beds = new ArrayList<>();
    private int seenRev = -1;
    private LanPeers lan;
    /** 다른 폰의 침대 — 내 목록에 있는 침대와 겹치는 것은 뺀다 */
    private List<LanPeers.RemoteBed> remotes = new ArrayList<>();
    /** 겹치는 것까지 포함한 전체 (넘겨준 침대의 행방·이름 찾기용) */
    private List<LanPeers.RemoteBed> allRemotes = new ArrayList<>();
    private String remoteSig = "";
    private boolean selRemote = false;
    private int sel = 0;

    // 메인
    private BedView bedView;
    private TextView statusDot, angleText, slotText, lightState, speakerState;
    private TextView warnView;
    private View warnCard;
    private TextView noticeView;
    private View noticeCard;
    private Button noticeBtn;
    private Runnable noticeAction;
    private TextView headVal, legVal, tableVal;
    private View stopBtn;
    private View tableRow;
    private TextView updView;
    private View updCard;
    private Updates.Info pending;
    private boolean installAfterPerm = false;   // 설치 허용 화면에 다녀오는 중
    private boolean installAfterSecurity = false;   // 보안 설정(삼성 '보안 위험 자동 차단')에 다녀오는 중
    private String askedVer = "";               // 이번에 이미 물어본 새 버전
    private boolean updWaiting = false;         // 설치를 넘기고 안드로이드의 답을 기다리는 중
    private LinearLayout tabRow;
    private LinearLayout alarmBox;
    private boolean alarmTesting = false;
    private View heroCard, heroPrev, heroNext;
    private TextView heroName, heroSub, heroDots;
    /** 지난번에 보던 침대 — 이웃 침대면 목록이 도착했을 때 그리로 옮겨준다 */
    private String restoreTok = "";
    private final Map<String, Slider> bars = new LinkedHashMap<>();
    private final List<Object[]> poses = new ArrayList<>();   // {BedView 아이콘, 숫자 글자, 상체값, 다리값}

    /** 보낸 목표 — 어디서(from) 어디로(to) 가는 중인지 */
    private static class Tgt {
        final int from, to; final long at;
        Tgt(int f, int t, long a) { from = f; to = t; at = a; }
    }
    private final Map<String, Tgt> targets = new LinkedHashMap<>();
    private String targetsToken = "";       // targets 가 어느 침대의 것인지
    private String dragPin = null;          // 지금 손가락으로 끌고 있는 슬라이더
    private String dragToken = "";          // 끌기 시작할 때 고른 침대
    /** 명령은 한 줄로 차례대로 보낸다 — 빨리 연달아 눌러도 순서가 뒤바뀌지 않게 */
    private final java.util.concurrent.ExecutorService sendQ =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean peerBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean askBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private int pollCount = 0;

    /** 무드등·스피커처럼 침대가 값을 잘 안 알려주는 핀은 마지막으로 보낸 값을 기억해둔다 */
    private final Map<String, Integer> sentVal = new HashMap<>();
    private final Map<String, Long> sentAt = new HashMap<>();

    // 설정
    private LinearLayout setBox;

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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("mabed", MODE_PRIVATE);
        u = new Ui(this);
        beds = Beds.load(prefs);
        migrateOld();
        seenRev = App.bedsRev;
        sel = Math.min(prefs.getInt("sel", 0), Math.max(0, beds.size() - 1));
        // 지난번에 보던 침대부터. 이웃 침대였다면 이웃 목록이 도착할 때 옮겨간다 (applyRemotes)
        restoreTok = prefs.getString("selToken", "");
        for (int i = 0; i < beds.size(); i++)
            if (beds.get(i).token.equals(restoreTok)) { sel = i; restoreTok = ""; }

        root = new FrameLayout(this);
        root.setBackgroundColor(u.bg);
        setContentView(root);
        applyInsets();

        // 알림 권한은 처음 한 번만 묻는다 (예전엔 앱을 켤 때마다 물었다)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); }
            catch (Exception ignored) {}
        }

        App.setCallback(new Runnable() { public void run() {
            ui.post(new Runnable() { public void run() { refresh(); } }); } });
        if (!App.server().isRunning()) startServer();

        // 이웃 폰 창구는 앱 전체에 하나만 — 화면을 닫았다 열어도 새로 만들지 않는다
        App.startNet(this);
        lan = App.lan();

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
        App.uiVisible = true;
        // 앱을 켜면 다시 연결한다 — 닫은 뒤 5분이 지나 모두 꺼져 있었을 수 있다
        ServerService.appOpened();
        if (!App.server().isRunning()) startServer();
        App.startNet(this);
        lan.setListening(true);     // 화면을 볼 때만 이웃 폰 신호를 받는다 (배터리)
        startTicking();
        // '이 출처 허용'을 켜러 갔다 돌아왔다 — 켜졌으면 설치를 이어서, 못 켰으면(삼성 자동 차단으로 회색) 안내
        if (installAfterPerm && pending != null) {
            installAfterPerm = false;
            if (Updater.canInstall(this)) startUpdate(pending);
            else installBlockedHelp(true);
        }
        // 보안 설정(자동 차단 끄기)에 다녀왔다 — 설치를 이어서
        else if (installAfterSecurity) {
            installAfterSecurity = false;
            if (pending != null) startUpdate(pending); else checkUpdate(true);
        }
        // 설치 화면에서 돌아왔다 — 버전이 그대로면 막혔을 수 있다
        else checkInstallOutcome();
        // 새 버전 확인은 앱을 켤 때만 (배경에서 주기적으로 확인하지 않는다 — 배터리).
        // 뒤로 보냈다가 다시 연 것도 '켠 것'으로 치되, 1시간 안에 다시 묻지는 않는다.
        long last = prefs.getLong("updCheckedAt", 0);
        if (System.currentTimeMillis() - last > 60L * 60 * 1000) checkUpdate(false);
        // 배터리 제한을 푼 뒤 돌아온 경우 설정 화면의 그 줄을 치운다
        if (screen == SCR_SETTINGS) renderSettings();
    }

    @Override protected void onPause() {
        super.onPause();
        App.uiVisible = false;
        lan.setListening(false);
        stopTicking();   // 서버와 서비스는 계속 돌고, 화면 갱신만 멈춘다
    }

    @Override protected void onStop() {
        super.onStop();
        // 화면이 안 보인다 — 5분 안에 돌아오지 않으면 침대 대기·이웃 연결을 모두 끈다 (설정에서 바꿀 수 있음)
        ServerService.appClosed();
    }

    // ── 뒤로가기 ───────────────────────────────────────
    /** 폰의 뒤로 버튼. 예전엔 처리가 없어서 누르면 앱이 그냥 꺼졌다 */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (screen == SCR_SETTINGS) { closeSettings(); return; }
        if (screen == SCR_WIZARD) { wizBack(); return; }
        // 메인에서는 앱을 끄지 않고 뒤로 보낸다 (서버는 계속 돈다)
        moveTaskToBack(true);
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
            if (!ownToken(d.token)) return d.token;
        }
        return null;
    }

    private boolean ownToken(String token) {
        for (Beds.Bed b : beds) if (b.token.equals(token)) return true;
        return false;
    }

    /** 다른 폰이 알고 있는 이 침대의 이름 (넘겨받은 침대에 원래 이름을 붙여주려고) */
    private String knownName(String token) {
        for (LanPeers.RemoteBed r : allRemotes) if (r.token.equals(token)) return r.name;
        return null;
    }

    private void rebuild() {
        root.removeAllViews();
        bars.clear();
        poses.clear();
        setPane = null;
        fixSel();
        if (beds.isEmpty() && remotes.isEmpty()) {
            step = 0;
            screen = SCR_WIZARD;
            wizPane = buildWizard();
            root.addView(wizPane);
            mainPane = null;
        } else {
            screen = SCR_MAIN;
            mainPane = buildMain();
            root.addView(mainPane);
            wizBox = null;
            saveSel();
        }
        refresh();
    }

    /** 고른 침대 번호가 목록 범위를 벗어나지 않게 */
    private void fixSel() {
        if (beds.isEmpty() && !remotes.isEmpty()) selRemote = true;
        if (selRemote && remotes.isEmpty()) { selRemote = false; sel = 0; }
        if (selRemote) { if (sel >= remotes.size()) sel = 0; }
        else if (sel >= beds.size()) sel = 0;
    }

    private Beds.Bed cur() {
        if (selRemote || beds.isEmpty() || sel >= beds.size()) return null;
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

    /** 제목 + 왼쪽 뒤로 버튼이 있는 윗줄 */
    private LinearLayout topBar(String title, Runnable back) {
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        if (back != null) {
            ImageView bk = u.iconBtn(Glyph.BACK, u.fg, "뒤로", back);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(u.rawDp(44), u.rawDp(44));
            bp.rightMargin = u.dp(4);
            hd.addView(bk, bp);
        }
        hd.addView(u.text(title, 22, u.fg, true), new LinearLayout.LayoutParams(0, -2, 1f));
        return hd;
    }

    // ── 설정 마법사 ────────────────────────────────────
    private View buildWizard() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(u.bg);
        wizBox = u.col();
        wizBox.setPadding(u.dp(14), u.dp(14), u.dp(20), u.dp(28));
        sv.addView(wizBox);
        renderStep();
        return sv;
    }

    /** 마법사에서 뒤로 갈 곳이 있는가 */
    private boolean wizCanBack() {
        return step > 0 || wizHandover || !beds.isEmpty() || !remotes.isEmpty();
    }

    /** 마법사 한 단계 뒤로 — 첫 단계면 마법사를 닫는다 */
    private void wizBack() {
        if (wizHandover) {
            if (step == 5) { handoverDone(); return; }       // 이미 침대에 넣었다 — 마무리
            if (step <= 1) { wizHandover = false; rebuild(); toast("넘기기를 취소했습니다"); return; }
            step--; renderStep(); return;
        }
        if (step == 5) { wizFinish(); return; }              // 침대 설정은 이미 끝났다 — 목록에 넣고 닫는다
        if (step > 0) { step--; renderStep(); return; }
        if (!beds.isEmpty() || !remotes.isEmpty()) { rebuild(); return; }
        moveTaskToBack(true);                                // 침대가 하나도 없으면 앱만 뒤로
    }

    private void renderStep() {
        wizBox.removeAllViews();
        wizBox.addView(topBar(wizHandover ? "침대 넘겨주기" : "침대 연결하기",
                wizCanBack() ? new Runnable(){ public void run(){ wizBack(); }} : null));
        TextView sub = u.text(wizHandover
                ? (wizName + " → " + (wizTargetPhone.isEmpty() ? wizHost : wizTargetPhone))
                : ((step + 1) + " / 6 단계"), 12.5f, wizHandover ? u.accent : u.muted, true);
        sub.setPadding(u.dp(6), u.dp(4), 0, u.dp(18));
        wizBox.addView(sub);
        LinearLayout body = u.col();
        body.setPadding(u.dp(6), 0, 0, 0);
        wizBox.addView(body);
        LinearLayout box = body;

        switch (step) {
            case 0: {
                box.addView(u.text("먼저 이 침대를 뭐라고 부를지 정해주세요.", 15, u.fg, false));
                box.addView(spacer(14));
                wizNameIn = u.input("예: 내 침대", beds.isEmpty() ? "내 침대" : "", false);
                box.addView(wizNameIn);
                box.addView(u.note("침대가 두 대 이상이면 이 이름으로 구분합니다."));
                box.addView(spacer(8));
                String ip = Net.myWifiIp(this);
                boolean okWifi = Net.isLan(ip) && !ip.startsWith("192.168.4.");
                if (okWifi) {
                    wizHost = ip;
                    String ss = currentSsid();
                    if (ss != null) { wizSsid = ss; prefs.edit().putString("homeSsid", ss).apply(); }
                    prefs.edit().putString("homeIp", ip).apply();
                    String saved = prefs.getString("homeSsid", "");
                    box.addView(u.card(u.text("지금 집 와이파이에 연결돼 있습니다.\n"
                            + "이 폰 주소 : " + ip
                            + (saved.isEmpty() ? "" : "\n와이파이 : " + saved), 13, u.fg, false), 13));
                    if (saved.contains("5G") || saved.contains("5g")) {
                        box.addView(u.card(u.text(
                            "이 폰은 지금 5GHz 와이파이에 붙어 있습니다.\n"
                            + "침대는 5GHz를 못 씁니다. 다음 단계에서 5G가 안 붙은 쪽을 고르세요.\n"
                            + "두 와이파이가 같은 공유기라면 폰은 그대로 두셔도 됩니다.", 13, u.accent, false), 13));
                    }
                    if (saved.isEmpty()) {
                        box.addView(u.small("와이파이 이름 자동으로 가져오기", new Runnable(){ public void run(){
                            if (!hasLocationPerm()) askLocationPerm();
                            else {
                                String s2 = currentSsid();
                                if (s2 != null) { wizSsid = s2; prefs.edit().putString("homeSsid", s2).apply(); renderStep(); }
                                else toast("위치 기능을 켜고 다시 눌러주세요");
                            } }}));
                        box.addView(u.note("안드로이드는 위치 권한이 있어야 와이파이 이름을 알려줍니다. 위치를 추적하지는 않습니다."));
                    }
                } else {
                    box.addView(u.card(u.text("먼저 집 와이파이에 연결해주세요.\n연결한 뒤 이 화면으로 돌아오면 됩니다.", 13, u.danger, false), 13));
                }
                final String orphan = orphanToken();
                if (orphan != null) {
                    String known = knownName(orphan);
                    if (known != null && beds.isEmpty()) wizNameIn.setText(known);
                    box.addView(spacer(6));
                    box.addView(u.card(u.text(
                        "이미 이 폰에 접속해 있는 침대가 있습니다.\n설정을 다시 할 필요 없이 바로 추가할 수 있습니다.",
                        13, u.ok, false), 13));
                    box.addView(u.btn("접속해 있는 침대 바로 추가", u.ok, 0xFFFFFFFF, 0, 15, 16,
                        new Runnable(){ public void run(){
                            // 누르는 순간 다시 찾는다 — 그 사이 끊기고 다른 침대가 붙었을 수 있다
                            String now = orphanToken();
                            if (now == null) { toast("접속해 있던 침대가 끊겼습니다. 잠시 뒤 다시 시도해주세요"); wizShownOrphan = false; renderStep(); return; }
                            String n = wizNameIn.getText().toString().trim();
                            wizName = n.isEmpty() ? "내 침대" : n;
                            wizToken = now;
                            wizFinish();
                        }}));
                    box.addView(spacer(10));
                }
                if (!remotes.isEmpty() && beds.isEmpty()) {
                    box.addView(spacer(6));
                    box.addView(u.card(u.text(
                        "다른 폰이 가진 침대 " + remotes.size() + "대가 보입니다.\n"
                        + "내 침대를 등록하지 않아도 그 침대는 지금 바로 조작할 수 있습니다.",
                        13, u.ok, false), 13));
                    box.addView(u.btn("그 침대 조작하러 가기", u.ok, 0xFFFFFFFF, 0, 15, 16,
                        new Runnable(){ public void run(){
                            selRemote = true; sel = 0; rebuild(); }}));
                    box.addView(spacer(10));
                }
                box.addView(u.btn("다음", okWifi ? u.accent : u.card, okWifi ? 0xFFFFFFFF : u.muted,
                        okWifi ? 0 : u.line, 16, 17, new Runnable() { public void run() {
                    String n = wizNameIn.getText().toString().trim();
                    if (n.isEmpty()) { toast("이름을 넣어주세요"); return; }
                    String ip2 = Net.myWifiIp(MainActivity.this);
                    if (!Net.isLan(ip2) || ip2.startsWith("192.168.4.")) { toast("집 와이파이에 연결해주세요"); return; }
                    wizName = n; wizHost = ip2; wizToken = Beds.newToken();
                    step = 1; renderStep();
                }}));
                if (beds.isEmpty() && remotes.isEmpty()) {
                    // 내 침대 없이 상대 폰의 침대만 쓰려면 먼저 짝을 지어야 한다
                    box.addView(u.small("다른 폰과 짝짓기 · 상대 침대만 쓸 때", new Runnable(){ public void run(){ pickPair(); }}));
                    box.addView(u.note("이 폰에 침대를 등록하지 않고 가족 폰의 침대만 쓰려면, 두 폰 모두 앱을 연 채로 짝을 지으세요."));
                }
                if (!beds.isEmpty() || !remotes.isEmpty())
                    box.addView(u.small("취소", new Runnable(){ public void run(){ rebuild(); }}));
                break;
            }
            case 1: {
                box.addView(u.text("침대를 설정 모드로 바꿔주세요.", 16, u.fg, true));
                box.addView(spacer(10));
                box.addView(u.card(u.text(
                    "1. 침대 밑 컨트롤 박스의 나사 4개를 풉니다\n\n" +
                    "2. 안에 꽂힌 작은 검은 기판에서 micro-USB 단자 오른쪽의 BOOT 버튼을 찾습니다\n\n" +
                    "3. 전원이 켜진 상태로 BOOT를 10초간 꾹 누릅니다\n\n" +
                    "4. 빨간 불이 느리게 깜빡이다 빠르게 깜빡이면 완료입니다", 14, u.fg, false), 15));
                box.addView(u.note("케이스 바깥이 아니라 안쪽 기판 위에 있습니다."));
                box.addView(u.btn("했습니다", u.accent, 0xFFFFFFFF, 0, 16, 17,
                        new Runnable(){ public void run(){ step = 2; renderStep(); }}));
                if (!wizHandover) box.addView(u.small("뒤로", new Runnable(){ public void run(){ step = 0; renderStep(); }}));
                else box.addView(u.small("넘기기 취소", new Runnable(){ public void run(){
                        wizHandover = false; rebuild(); }}));
                break;
            }
            case 2: {
                box.addView(u.text("폰을 침대 와이파이에 연결해주세요.", 16, u.fg, true));
                box.addView(spacer(10));
                box.addView(u.card(u.text(
                    "1. 폰 설정 → 와이파이\n\n" +
                    "2. birkits- 로 시작하는 이름을 찾아 연결합니다 (비밀번호 없음)\n\n" +
                    "3. \"인터넷이 안 된다\"고 나오면 이 네트워크 유지를 고릅니다\n\n" +
                    "4. 모바일 데이터를 꺼주세요  ← 이걸 안 하면 실패합니다", 14, u.fg, false), 15));
                wizMsg = u.note(Net.onBedAp(this) ? "지금 침대 와이파이에 연결돼 있습니다." : "아직 연결되지 않았습니다.");
                box.addView(wizMsg);
                box.addView(u.btn("연결했습니다 · 침대 찾기", u.accent, 0xFFFFFFFF, 0, 16, 17,
                        new Runnable(){ public void run(){ wizFind(); }}));
                box.addView(u.small("뒤로", new Runnable(){ public void run(){ step = 1; renderStep(); }}));
                break;
            }
            case 3: {
                box.addView(u.text("집 와이파이를 고르세요.", 16, u.fg, true));
                final String autoSsid = prefs.getString("homeSsid", "");
                if (!autoSsid.isEmpty()) {
                    box.addView(u.card(u.text("이 폰이 쓰던 와이파이 : " + autoSsid, 14, u.ok, true), 13));
                    box.addView(u.btn("이걸로 하기", u.ok, 0xFFFFFFFF, 0, 15, 15, new Runnable(){ public void run(){
                        wizSsid = autoSsid; keepPass(); renderStep(); }}));
                }
                box.addView(u.note("아래는 침대가 자기 자리에서 잡히는 목록입니다. 신호가 셀수록 안정적입니다. 침대는 2.4GHz만 쓸 수 있어서 이름에 5G가 붙은 것은 여기에 아예 안 나옵니다."));
                wizScan = u.col();
                box.addView(wizScan);
                box.addView(u.small("목록 다시 받기", new Runnable(){ public void run(){ wizScan(); }}));
                box.addView(spacer(6));
                box.addView(u.text("고른 와이파이 : " + (wizSsid.isEmpty() ? "아직 없음" : wizSsid), 14, u.fg, true));
                if (wizPass.isEmpty()) wizPass = prefs.getString("pass", "");
                wizPassIn = u.input("와이파이 비밀번호", wizPass, false);
                wizPassIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                box.addView(wizPassIn);
                box.addView(u.small("비밀번호 보이기 / 가리기", new Runnable(){ public void run(){
                    boolean hidden = (wizPassIn.getInputType()
                            & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                    wizPassIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | (hidden ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                      : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
                    wizPassIn.setSelection(wizPassIn.getText().length());
                }}));
                box.addView(u.note("비밀번호는 안드로이드가 앱에게 알려주지 않습니다. 한 번만 넣어두면 다음부터는 기억합니다."));
                box.addView(u.btn("다음", u.accent, 0xFFFFFFFF, 0, 16, 17, new Runnable(){ public void run(){
                    if (wizSsid.isEmpty()) { toast("와이파이를 골라주세요"); return; }
                    wizPass = wizPassIn.getText().toString();
                    prefs.edit().putString("pass", wizPass).putString("ssid", wizSsid).apply();
                    step = 4; renderStep();
                }}));
                wizScan();
                break;
            }
            case 4: {
                box.addView(u.text("이렇게 넣겠습니다.", 16, u.fg, true));
                box.addView(spacer(10));
                box.addView(u.card(u.text(
                    "이름        " + wizName + "\n" +
                    "와이파이     " + wizSsid + "\n" +
                    (wizHandover
                        ? "넘겨받을 폰   " + (wizTargetPhone.isEmpty() ? "" : wizTargetPhone + "  ") + wizHost
                        : "찾아올 주소   " + wizHost + " : " + BedServer.PORT), 14, u.fg, false), 15));
                if (wizHandover) {
                    box.addView(u.card(u.text(
                        "이제부터 이 침대는 저 폰을 찾아갑니다.\n"
                        + "이 폰 목록에서는 빠지지만, 같은 와이파이에 있으면\n"
                        + "↗ 표시가 붙은 채로 계속 조작할 수 있습니다.", 13, u.accent, false), 13));
                }
                wizMsg = u.note("");
                box.addView(wizMsg);
                box.addView(u.btn("침대에 넣기", u.accent, 0xFFFFFFFF, 0, 16, 17,
                        new Runnable(){ public void run(){ wizSend(); }}));
                box.addView(u.small("뒤로", new Runnable(){ public void run(){ step = 3; renderStep(); }}));
                break;
            }
            case 5: {
                if (wizHandover) {
                    box.addView(u.text("넘겼습니다.", 16, u.fg, true));
                    box.addView(spacer(10));
                    box.addView(u.card(u.text(
                        "1. 이 폰을 다시 집 와이파이(" + wizSsid + ")로 연결하세요.\n\n" +
                        "2. " + (wizTargetPhone.isEmpty() ? "받는 폰" : wizTargetPhone) + " 에서 앱을 여세요.\n\n" +
                        "3. \"접속해 있는 침대가 있습니다\" 안내가 뜨면 한 번 누르면 끝입니다.",
                        14, u.fg, false), 15));
                    box.addView(u.note("침대가 다시 시작하면서 저 폰을 찾아갑니다. 보통 10초 안에 붙습니다."));
                    box.addView(u.btn("끝내기", u.accent, 0xFFFFFFFF, 0, 16, 17,
                            new Runnable(){ public void run(){ handoverDone(); }}));
                    break;
                }
                box.addView(u.text("마지막입니다.", 16, u.fg, true));
                box.addView(spacer(10));
                box.addView(u.card(u.text(
                    "폰을 다시 집 와이파이(" + wizSsid + ")로 연결해주세요.\n\n" +
                    "침대가 스스로 다시 시작하면서 찾아옵니다.\n보통 10초 안에 연결됩니다.", 14, u.fg, false), 15));
                wizMsg = u.text("침대를 기다리는 중…", 15, u.muted, true);
                box.addView(u.card(wizMsg, 15));
                box.addView(u.small("건너뛰고 끝내기", new Runnable(){ public void run(){ wizFinish(); }}));
                break;
            }
        }
    }

    /** 와이파이를 고를 때 입력 중이던 비밀번호를 잃지 않게 */
    private void keepPass() {
        if (wizPassIn != null) wizPass = wizPassIn.getText().toString();
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
                post(new Runnable(){ public void run(){ if (step == 2) { step = 3; renderStep(); } }});
            } catch (final Exception e) {
                post(new Runnable(){ public void run(){ if (step == 2 && wizMsg != null) wizMsg.setText(
                    "침대를 못 찾았습니다.\n\n· birkits- 와이파이에 연결돼 있는지\n· 모바일 데이터를 껐는지\n· 침대가 설정 모드인지 확인해주세요\n\n(" + e.getMessage() + ")"); }});
            }
        }});
    }

    private void wizScan() {
        final LinearLayout target = wizScan;
        target.removeAllViews();
        target.addView(u.note("불러오는 중…"));
        bg(new Runnable(){ public void run(){
            try {
                final String s = Net.get(MainActivity.this, "http://192.168.4.1/wifi_scan.json", 15000);
                post(new Runnable(){ public void run(){ if (wizScan == target) wizShowScan(s); }});
            } catch (final Exception e) {
                post(new Runnable(){ public void run(){
                    if (wizScan != target) return;
                    target.removeAllViews();
                    target.addView(u.note("목록을 못 받았습니다. 침대 와이파이에 연결돼 있는지 확인하고 다시 받아보세요."));
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
                    wizSsid = ssid; keepPass(); renderStep(); }}));
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
                post(new Runnable(){ public void run(){ if (wizMsg != null) wizMsg.setText("실패했습니다.\n" + e.getMessage()); }});
            }
        }});
    }

    private void waitForBed() {
        ui.postDelayed(new Runnable() { public void run() {
            if (step != 5 || screen != SCR_WIZARD) return;
            if (App.server().byToken(wizToken) != null) { wizFinish(); return; }
            if (wizMsg != null) wizMsg.setText("침대를 기다리는 중…  폰이 집 와이파이인지 확인해주세요.");
            ui.postDelayed(this, 1500);
        }}, 1500);
    }

    private void wizFinish() {
        // 안전장치 — 넘기기 중에는 절대 내 목록에 다시 넣지 않는다
        if (wizHandover) { handoverDone(); return; }
        if (!ownToken(wizToken)) {
            beds.add(new Beds.Bed(wizName, wizToken));
            Beds.save(prefs, beds);
        }
        for (int i = 0; i < beds.size(); i++) if (beds.get(i).token.equals(wizToken)) sel = i;
        selRemote = false;
        prefs.edit().putInt("sel", sel).apply();
        toast(wizName + " 를 추가했습니다");
        step = 0;
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
        top.setPadding(u.dp(18), u.dp(12), u.dp(8), u.dp(8));
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
        // 설정 버튼 — 톱니 + '설정' 글자 (아이콘만 있을 땐 해·밝기 버튼처럼 보였다)
        LinearLayout gear = new LinearLayout(this);
        gear.setOrientation(LinearLayout.HORIZONTAL);
        gear.setGravity(Gravity.CENTER_VERTICAL);
        gear.setPadding(u.dp(10), 0, u.dp(12), 0);
        gear.setMinimumHeight(u.rawDp(40));
        ImageView gi = new ImageView(this);
        gi.setImageDrawable(new Glyph(Glyph.GEAR, u.fg, u.dp(18)));
        gear.addView(gi, new LinearLayout.LayoutParams(u.dp(18), u.dp(18)));
        TextView gt = u.text("설정", 13.5f, u.fg, true);
        gt.setPadding(u.dp(6), 0, 0, 0);
        gear.addView(gt);
        u.ripple(gear, u.card, u.line, 20);
        gear.setContentDescription("설정");
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSettings(); }});
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, u.rawDp(40));
        gp.leftMargin = u.dp(8);
        top.addView(gear, gp);
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

        // 접속했는데 목록에 없는 침대 · 다른 폰으로 옮겨간 침대 안내
        LinearLayout nBox = u.col();
        noticeView = u.text("", 13.5f, u.fg, true);
        nBox.addView(noticeView);
        noticeBtn = u.btn("", u.ok, 0xFFFFFFFF, 0, 14, 12,
                new Runnable(){ public void run(){ if (noticeAction != null) noticeAction.run(); }});
        LinearLayout.LayoutParams nbp = new LinearLayout.LayoutParams(-1, -2);
        nbp.topMargin = u.dp(10);
        nbp.bottomMargin = 0;
        noticeBtn.setLayoutParams(nbp);
        nBox.addView(noticeBtn);
        noticeCard = u.card(nBox, 14);
        noticeCard.setVisibility(View.GONE);
        c.addView(noticeCard);

        LinearLayout updBox = u.col();
        updView = u.text("", 13.5f, u.fg, true);
        updBox.addView(updView);
        Button updBtn = u.btn("지금 설치", u.ok, 0xFFFFFFFF, 0, 14, 12,
                new Runnable(){ public void run(){ if (pending != null) startUpdate(pending); }});
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

        // 침대 이름 + ‹ › — 침대가 둘 이상이면 여기서 넘기거나, 카드를 옆으로 쓸어서 바꾼다
        LinearLayout hh = new LinearLayout(this);
        hh.setOrientation(LinearLayout.HORIZONTAL);
        hh.setGravity(Gravity.CENTER_VERTICAL);
        heroPrev = u.iconBtn(Glyph.BACK, u.fg, "이전 침대", new Runnable(){ public void run(){ swipeTo(-1); }});
        hh.addView(heroPrev, new LinearLayout.LayoutParams(u.rawDp(44), u.rawDp(44)));
        LinearLayout nameCol = u.col();
        nameCol.setGravity(Gravity.CENTER_HORIZONTAL);
        heroName = u.text("", 17, u.fg, true);
        heroName.setGravity(Gravity.CENTER);
        heroName.setSingleLine(true);
        heroName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameCol.addView(heroName);
        heroSub = u.text("", 11.5f, u.muted, false);
        heroSub.setGravity(Gravity.CENTER);
        heroSub.setSingleLine(true);
        nameCol.addView(heroSub);
        hh.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1f));
        heroNext = u.iconBtn(Glyph.CHEVRON, u.fg, "다음 침대", new Runnable(){ public void run(){ swipeTo(+1); }});
        hh.addView(heroNext, new LinearLayout.LayoutParams(u.rawDp(44), u.rawDp(44)));
        hero.addView(hh, new LinearLayout.LayoutParams(-1, -2));

        bedView = new BedView(this, u.line, u.accent, u.fg, u.muted);
        int bh = (int)(u.screenH * 0.155f);
        if (bh < u.dp(112)) bh = u.dp(112);
        if (bh > u.dp(190)) bh = u.dp(190);
        hero.addView(bedView, new LinearLayout.LayoutParams(-1, bh));
        angleText = u.text("—", 15.5f, u.fg, true);
        angleText.setGravity(Gravity.CENTER);
        angleText.setPadding(0, u.dp(12), 0, u.dp(2));
        hero.addView(angleText);
        heroDots = u.text("", 10f, u.muted, false);
        heroDots.setGravity(Gravity.CENTER);
        heroDots.setLetterSpacing(0.3f);
        heroDots.setPadding(0, u.dp(6), 0, 0);
        hero.addView(heroDots);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = u.dp(6);
        hero.setLayoutParams(hp);
        c.addView(hero);
        heroCard = hero;

        // 카드를 옆으로 쓸면 다음/이전 침대 (세로로 쓸면 평소처럼 화면이 내려간다)
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null || bedCount() < 2) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > u.dp(50) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    swipeTo(dx < 0 ? +1 : -1);
                    return true;
                }
                return false;
            }
        });
        hero.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
        });

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

        // ── 알람 (원래 앱의 ALARM 1·2·3 — 침대가 스스로 실행) ──
        c.addView(u.head("알람"));
        alarmBox = u.col();
        c.addView(alarmBox);
        c.addView(u.note("이름 바꾸기 · 테이블 · 각도 맞추기 · 침대 추가는 오른쪽 위 '설정'에 있습니다."));
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

        applyBedSpecific();
        return outer;
    }

    /** 메뉴 한 줄 — 오른쪽에 현재 값, 누를 수 있으면 화살표 */
    private View linkRow(String title, String value, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u.dp(16), u.dp(15), u.dp(14), u.dp(15));
        row.setMinimumHeight(u.rawDp(52));
        row.addView(u.text(title, 15, u.fg, false), new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && !value.isEmpty()) {
            TextView vt = u.text(value, 13.5f, u.muted, false);
            vt.setPadding(u.dp(8), 0, u.dp(6), 0);
            vt.setSingleLine(true);
            vt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            vt.setMaxWidth((int)(u.screenW * 0.45f));
            row.addView(vt, new LinearLayout.LayoutParams(-2, -2));
        }
        if (r != null) {
            ImageView ch = new ImageView(this);
            ch.setImageDrawable(new Glyph(Glyph.CHEVRON, u.muted, u.dp(16)));
            row.addView(ch, new LinearLayout.LayoutParams(u.dp(16), u.dp(16)));
            row.setClickable(true);
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(u.dark ? 0x33FFFFFF : 0x22000000), null,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            row.setOnClickListener(new View.OnClickListener(){
                public void onClick(View v){ r.run(); }});
        }
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
        if (tabRow == null) return;
        tabRow.removeAllViews();
        int total = beds.size() + remotes.size();
        if (total < 2) { tabRow.setVisibility(View.GONE); return; }
        tabRow.setVisibility(View.VISIBLE);
        float ts = total >= 4 ? 11.5f : 13f;
        int cur = curIndex();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            boolean on = i == cur;
            String nm = i < beds.size() ? beds.get(i).name : remotes.get(i - beds.size()).name + " ↗";
            Button t = u.btn(nm, on ? u.accent : u.card,
                    on ? 0xFFFFFFFF : u.fg, on ? 0 : u.line, ts, 12,
                    new Runnable(){ public void run(){ selectIndex(idx); }});
            tab(t);
            tabRow.addView(t, u.w(1, 3));
        }
    }

    // ── 침대 넘기기 (내 침대 → 이웃 침대 순서로 한 줄) ──
    private int bedCount() { return beds.size() + remotes.size(); }

    private int curIndex() { return selRemote ? beds.size() + sel : sel; }

    /** i 번째 침대를 고른다. 끝에서 넘기면 처음으로 돈다 */
    private void selectIndex(int i) {
        int n = bedCount();
        if (n == 0) return;
        i = ((i % n) + n) % n;
        if (i == curIndex()) return;
        if (i < beds.size()) { selRemote = false; sel = i; }
        else { selRemote = true; sel = i - beds.size(); }
        restoreTok = "";            // 사용자가 직접 골랐다 — 마지막 침대 복원은 그만
        saveSel();
        switched();
    }

    /** 마지막으로 본 침대를 기억한다 — 다음에 앱을 켜면 그 침대부터 */
    private void saveSel() {
        SharedPreferences.Editor e = prefs.edit().putString("selToken", curToken());
        if (!selRemote) e.putInt("sel", sel);
        e.apply();
    }

    /** ‹ › 버튼이나 카드를 쓸었을 때 — 그림이 옆으로 밀려나며 바뀐다 */
    private void swipeTo(final int dir) {
        if (bedCount() < 2) return;
        if (bedView == null || heroCard == null) { selectIndex(curIndex() + dir); return; }
        final float w = heroCard.getWidth() * 0.25f;
        bedView.animate().translationX(-dir * w).alpha(0f).setDuration(110).withEndAction(new Runnable(){ public void run(){
            selectIndex(curIndex() + dir);
            if (bedView == null) return;
            bedView.setTranslationX(dir * w);
            bedView.animate().translationX(0).alpha(1f).setDuration(150).start();
        }}).start();
    }

    /** 카드 위쪽 이름·주인·점 표시 */
    private void updateHero() {
        if (heroName == null) return;
        int n = bedCount();
        LanPeers.RemoteBed rb = curRemote();
        heroName.setText(curName() + (rb != null ? " ↗" : ""));
        heroSub.setText(rb != null
                ? (rb.phone.isEmpty() ? "다른 폰" : rb.phone) + " 에 등록된 침대 · 그 폰을 거쳐 조작"
                : "이 폰에 등록된 침대");
        int vis = n >= 2 ? View.VISIBLE : View.INVISIBLE;
        heroPrev.setVisibility(vis);
        heroNext.setVisibility(vis);
        if (n >= 2) {
            StringBuilder d = new StringBuilder();
            int cur = curIndex();
            for (int i = 0; i < n; i++) d.append(i == cur ? '●' : '○');
            heroDots.setText(d.toString() + "   옆으로 넘겨서 바꾸기");
            heroDots.setVisibility(View.VISIBLE);
        } else heroDots.setVisibility(View.GONE);
    }

    /** 다른 침대로 바꿨을 때 */
    private void switched() {
        targets.clear();
        dragPin = null;
        LanPeers.RemoteBed rb = curRemote();
        if (rb != null) remoteAlarms.remove(rb.token);   // 상대 침대 알람은 볼 때마다 주인 폰에서 새로 받는다
        buildTabs();
        applyBedSpecific();
        refresh();
        pollCount = 0;          // 다음 확인 때 무드등·스피커 상태도 묻는다
        askNow(true);
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
        TextView s = u.text("", 11f, u.muted, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, u.dp(1), 0, 0);
        box.addView(t); box.addView(s);
        poses.add(new Object[]{ ic, s, body, leg });
        u.ripple(box, u.card, u.line, 18);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (preset(body, leg)) toast(name); } });
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
                boolean on = toggleOn(pin);
                if (sendPin(pin, on ? "0" : "1")) { toast(on ? "껐습니다" : "켰습니다"); refresh(); }
            } });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = u.dp(8);
        box.setLayoutParams(p);
        return box;
    }

    /** 켜짐/꺼짐 — 침대가 알려준 값이 내가 마지막으로 보낸 것보다 새것일 때만 그 값을 믿는다.
     *  (예전엔 침대가 값을 안 알려주면 늘 '꺼짐'으로 보여서, 무드등을 끌 수가 없었다) */
    private boolean toggleOn(String pin) {
        String k = curToken() + "|" + pin;
        int bedV = pinValue("V" + pin, -1);
        long bedAt = pinAt("V" + pin);
        Integer sv = sentVal.get(k);
        Long sa = sentAt.get(k);
        if (sv != null && sa != null && (bedV < 0 || sa > bedAt)) return sv > 0;
        return bedV > 0;
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
        minus.setContentDescription(name + " 내리기");
        plus.setContentDescription(name + " 올리기");
        LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(u.dp(46), u.dp(46));

        final Slider sb = new Slider(this, u.line, u.accent, u.card, u.accent);
        sb.setMax(max);
        sb.setListener(new Slider.Listener() {
            public void onSlide(int v, boolean done) {
                if (!done) {
                    if (dragPin == null) { askNow(); dragToken = curToken(); }
                    dragPin = pin;
                    vl.setText("→ " + fmt(pin, v));      // 끄는 동안엔 놓으면 갈 값을 보여준다
                    vl.setTextColor(u.accent);
                } else {
                    boolean same = dragPin != null && curToken().equals(dragToken);
                    dragPin = null;
                    // 끄는 사이에 고른 침대가 바뀌었으면(이웃 침대가 사라지는 등) 보내지 않는다
                    if (!same) { toast("침대가 바뀌어서 보내지 않았습니다"); refresh(); return; }
                    if (sendPin(pin, String.valueOf(v))) setTarget(pin, v);
                    refresh();
                }
            }
            public void onCancel() { dragPin = null; refresh(); }
        });
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

    /** −/+ 한 칸. 연달아 누르면 누른 만큼 쌓인다 */
    private void step(String pin, int max, int delta) {
        ownTargets();
        Tgt t = targets.get(pin);
        int base = t != null ? t.to : pinValue("V" + pin, -1);
        // 예전엔 값을 모를 때 0에서 시작해서, + 를 누르면 침대가 거의 끝까지 내려가 버렸다
        if (base < 0) { toast("침대 값을 아직 모릅니다. 잠시 뒤 다시 눌러주세요"); askNow(); return; }
        if (pin.equals("14")) delta *= 10;
        int v = Math.max(0, Math.min(max, base + delta));
        if (v == base) return;
        if (sendPin(pin, String.valueOf(v))) setTarget(pin, v);
        refresh();
    }

    /** 목표를 기록한다. 이미 가는 중이면 출발점은 처음 것을 유지한다 */
    private void setTarget(String pin, int to) {
        ownTargets();
        targetsToken = curToken();
        Tgt old = targets.get(pin);
        int from = old != null ? old.from : pinValue("V" + pin, -1);
        targets.put(pin, new Tgt(from, to, System.currentTimeMillis()));
    }

    /** 목표값은 그 목표를 보낸 침대의 것 — 고른 침대가 바뀌었으면 버린다
     *  (다른 침대의 목표를 물려받아 + 한 번에 크게 움직이는 일이 없게) */
    private void ownTargets() {
        if (!targets.isEmpty() && !curToken().equals(targetsToken)) targets.clear();
    }

    /** 도착했거나 너무 오래된 목표를 지운다.
     *  예전엔 목표와 2도 차이만 나도 바로 지워서, + 를 여러 번 눌러도 1도씩만 움직이고
     *  "몇 도 → 몇 도" 표시도 금방 사라졌다. 이제는 보낸 뒤에 새로 받은 값으로만 판단한다. */
    private void settleTargets() {
        ownTargets();
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Tgt>> it = targets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Tgt> e = it.next();
            String pin = e.getKey();
            Tgt t = e.getValue();
            long age = now - t.at;
            if (age > 60000) { it.remove(); continue; }                 // 1분 넘게 소식이 없으면 포기
            int v = pinValue("V" + pin, -1);
            if (v < 0 || pinAt("V" + pin) < t.at + 400) continue;       // 보낸 뒤의 새 값이 아직 없다
            int tol = pin.equals("14") ? 20 : 2;
            if (v == t.to || (Math.abs(v - t.to) <= tol && age >= 2500)) it.remove();
        }
    }

    /** 이 침대를 어느 폰으로 넘길지 고른다 */
    private void handoverPick() {
        final Beds.Bed b = cur();
        if (b == null) { toast("이 폰에 등록된 침대를 먼저 고르세요"); return; }

        final List<LanPeers.Peer> ps = lan.allPeers();   // 넘겨주기는 침대 설정으로 하는 거라 짝이 아니어도 된다
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
                                + "그래도 안 보이면 받을 폰의 주소를 직접 넣으면 됩니다.\n"
                                + "(받을 폰의 설정 → 이 폰 주소)")
                        .setPositiveButton("알겠습니다", null).show();
                } })
            .show();
    }

    private void handoverManual(final Beds.Bed b) {
        final EditText e = u.input("예: 192.168.0.10", "", false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("받을 폰의 주소")
            .setMessage("받을 폰에서 앱을 열고 설정 → '이 폰 주소'에 나오는 숫자를 그대로 넣으세요.")
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
        showWizardPane();
    }

    /** 넘기기를 마치고 내 목록에서 뺀다 */
    private void handoverDone() {
        Beds.Bed b = null;
        for (Beds.Bed x : beds) if (x.token.equals(wizToken)) b = x;
        if (b != null) { beds.remove(b); Beds.save(prefs, beds); }
        sel = 0; selRemote = false;
        prefs.edit().putInt("sel", 0).apply();
        wizHandover = false;
        step = 0;
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
        showWizardPane();
    }

    private void showWizardPane() {
        root.removeAllViews();
        setPane = null;
        mainPane = null;
        tabRow = null;
        bars.clear();
        poses.clear();
        screen = SCR_WIZARD;
        wizPane = buildWizard();
        root.addView(wizPane);
    }

    /** 이름 바꾸기 — 다른 폰에 등록된 침대(↗)도 그 폰에 부탁해서 바꾼다 */
    private void renameBed() {
        final LanPeers.RemoteBed rb = curRemote();
        final Beds.Bed b = cur();
        if (rb == null && b == null) return;
        final EditText e = u.input("이름", rb != null ? rb.name : b.name, false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("침대 이름 바꾸기")
            .setMessage(rb != null ? "이 침대는 '" + rb.phone + "' 에 등록돼 있습니다.\n그 폰에서도 이 이름으로 바뀝니다." : null)
            .setView(e)
            .setPositiveButton("저장", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    final String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    if (rb == null) {
                        b.name = n; Beds.save(prefs, beds); buildTabs(); toast("바꿨습니다");
                        if (screen == SCR_SETTINGS) renderSettings();
                        return;
                    }
                    toast("바꾸는 중…");
                    bg(new Runnable(){ public void run(){
                        final boolean ok = lan.rename(rb.peerIp, rb.token, n);
                        post(new Runnable(){ public void run(){
                            if (ok) { rb.name = n; remoteSig = ""; buildTabs(); toast("바꿨습니다"); }
                            else toast("바꾸지 못했습니다. " + (rb.phone.isEmpty() ? "주인 폰" : rb.phone)
                                    + " 에도 새 버전을 설치해야 합니다");
                            if (screen == SCR_SETTINGS) renderSettings();
                        }});
                    }});
                } })
            .setNegativeButton("취소", null).show();
    }

    private void renamePhone() {
        final EditText e = u.input("폰 이름", App.phoneName(this), false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("이 폰 이름").setMessage("다른 폰의 목록에 이 이름으로 보입니다.").setView(e)
            .setPositiveButton("저장", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) {
                        prefs.edit().putString("phoneName", n).apply();
                        lan.setName(n);
                        toast("바꿨습니다");
                        if (screen == SCR_SETTINGS) renderSettings();
                    } } })
            .setNegativeButton("취소", null).show();
    }

    private void removeBed() {
        final Beds.Bed b = cur();
        if (b == null) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle(b.name + " 를 지울까요?")
            .setMessage("이 폰 목록에서만 사라집니다. 침대 자체는 그대로이고, 다시 추가할 수 있습니다.")
            .setPositiveButton("지우기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    beds.remove(b); Beds.save(prefs, beds);
                    sel = 0; selRemote = false; prefs.edit().putInt("sel", 0).apply();
                    rebuild();
                } })
            .setNegativeButton("취소", null).show();
    }

    /** 접속해 있는데 목록에 없는 침대를 메인 화면에서 바로 추가한다 (넘겨받은 침대) */
    private void addOrphan(final String token) {
        String known = knownName(token);
        final EditText e = u.input("이름", known != null ? known : (beds.isEmpty() ? "내 침대" : "침대 " + (beds.size() + 1)), false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("이 침대를 이 폰에 등록할까요?")
            .setMessage("등록하면 이 폰이 이 침대의 주인이 됩니다.\n이름 바꾸기·넘기기도 이 폰에서 할 수 있습니다.")
            .setView(e)
            .setPositiveButton("등록", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String n = e.getText().toString().trim();
                    if (ownToken(token)) return;
                    beds.add(new Beds.Bed(n.isEmpty() ? "내 침대" : n, token));
                    Beds.save(prefs, beds);
                    sel = beds.size() - 1; selRemote = false;
                    prefs.edit().putInt("sel", sel).apply();
                    remoteSig = "";
                    toast("등록했습니다");
                    rebuild();
                } })
            .setNegativeButton("취소", null).show();
    }

    // ── 각도 맞추기 ─────────────────────────────────────
    // 침대가 알려주는 값(상체 0~80, 다리 0~45)을 실제 각도로 바꿔서 보여준다.
    // 침대에 보내는 값은 그대로다. 사용자가 "끝까지 올렸을 때 실제로 몇 도인지"만 넣으면 된다.
    private int rawMax(String pin) { return pin.equals("11") ? 80 : 45; }

    private int realMax(String pin) {
        return prefs.getInt(keyOf(pin.equals("11") ? "headReal" : "legReal"), rawMax(pin));
    }

    /** 침대 값 → 화면에 보일 각도 */
    private int deg(String pin, int raw) {
        if (raw < 0 || pin.equals("14")) return raw;
        return Math.round(raw * (float) realMax(pin) / rawMax(pin));
    }

    private String fmt(String pin, int raw) {
        if (raw < 0) return "—";
        return pin.equals("14") ? String.valueOf(raw) : deg(pin, raw) + "°";
    }

    private void calibrate() {
        LinearLayout box = u.col();
        box.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        box.addView(u.note("침대를 끝까지 올렸을 때 실제로 몇 도인지 넣어주세요. "
                + "앱의 숫자와 그림이 그 각도에 맞춰집니다. 침대 움직임은 바뀌지 않습니다."));
        box.addView(u.text("상체 끝까지 올렸을 때 (기본 80)", 13, u.fg, true));
        final EditText h = u.input("80", String.valueOf(realMax("11")), true);
        box.addView(h);
        box.addView(u.text("다리 끝까지 올렸을 때 (기본 45)", 13, u.fg, true));
        final EditText l = u.input("45", String.valueOf(realMax("13")), true);
        box.addView(l);
        new android.app.AlertDialog.Builder(this)
            .setTitle("각도 맞추기 · " + curName())
            .setView(box)
            .setPositiveButton("저장", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    int hv = parse(h, 80), lv = parse(l, 45);
                    if (hv < 10 || hv > 90 || lv < 5 || lv > 90) { toast("5 ~ 90 사이 숫자로 넣어주세요"); return; }
                    prefs.edit().putInt(keyOf("headReal"), hv).putInt(keyOf("legReal"), lv).apply();
                    applyBedSpecific(); refresh(); renderSettings();
                    toast("맞췄습니다");
                } })
            .setNeutralButton("기본값으로", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    prefs.edit().remove(keyOf("headReal")).remove(keyOf("legReal")).apply();
                    applyBedSpecific(); refresh(); renderSettings();
                } })
            .setNegativeButton("취소", null).show();
    }

    private static int parse(EditText e, int dflt) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return dflt; }
    }

    /** 침대마다 다른 것(테이블 유무, 각도 맞춤)을 화면에 반영 */
    private void applyBedSpecific() {
        renderAlarms();
        if (tableRow != null) tableRow.setVisibility(hasTable() ? View.VISIBLE : View.GONE);
        for (Object[] p : poses) {
            int body = (Integer) p[2], leg = (Integer) p[3];
            ((BedView) p[0]).mini(deg("11", body), deg("13", leg));
            ((TextView) p[1]).setText(deg("11", body) + "° · " + deg("13", leg) + "°");
        }
    }

    // ── 설정 화면 (예전 '개발자 모드' 자리) ─────────────────
    private void openSettings() {
        if (mainPane == null) return;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(u.bg);
        LinearLayout outer = u.col();
        outer.setPadding(u.dp(10), u.dp(10), u.dp(18), u.dp(28));
        outer.addView(topBar("설정", new Runnable(){ public void run(){ closeSettings(); }}));
        setBox = u.col();
        setBox.setPadding(u.dp(8), 0, 0, 0);
        outer.addView(setBox);
        sv.addView(outer);
        setPane = sv;
        root.addView(setPane);
        mainPane.setVisibility(View.GONE);
        screen = SCR_SETTINGS;
        renderSettings();
    }

    private void closeSettings() {
        if (setPane != null) root.removeView(setPane);
        setPane = null;
        setBox = null;
        if (mainPane != null) mainPane.setVisibility(View.VISIBLE);
        screen = SCR_MAIN;
        refresh();
    }

    private LinearLayout group() {
        LinearLayout g = u.col();
        g.setBackground(u.box(u.card, u.line, 18));
        return g;
    }

    private void renderSettings() {
        if (setBox == null) return;
        setBox.removeAllViews();

        LanPeers.RemoteBed rb = curRemote();
        Beds.Bed b = cur();
        if (rb != null || b != null) {
            setBox.addView(u.head("이 침대 · " + curName()));
            LinearLayout g = group();
            g.addView(linkRow("이름 바꾸기", curName(), new Runnable(){ public void run(){ renameBed(); }}));
            g.addView(u.hair());
            g.addView(linkRow("테이블", hasTable() ? "있음 · 조절 칸 보임" : "없음", new Runnable(){ public void run(){ toggleTable(); }}));
            g.addView(u.hair());
            g.addView(linkRow("각도 맞추기", "상체 " + realMax("11") + "° · 다리 " + realMax("13") + "°",
                    new Runnable(){ public void run(){ calibrate(); }}));
            if (b != null) {
                g.addView(u.hair());
                g.addView(linkRow("다른 폰으로 넘기기", null, new Runnable(){ public void run(){ handoverPick(); }}));
                g.addView(u.hair());
                g.addView(linkRow("이 폰 목록에서 지우기", null, new Runnable(){ public void run(){ removeBed(); }}));
            }
            setBox.addView(g);
            setBox.addView(u.note(rb != null
                    ? "이 침대의 주인은 '" + (rb.phone.isEmpty() ? "다른 폰" : rb.phone) + "' 입니다. "
                      + "넘기기·지우기는 주인 폰에서 할 수 있습니다."
                    : "이 폰이 이 침대의 주인입니다. 테이블이 없는 침대에서 테이블 칸을 쓰면 다리가 움직입니다."));
        }

        setBox.addView(u.head("침대"));
        LinearLayout g2 = group();
        g2.addView(linkRow("침대 추가하기", null, new Runnable(){ public void run(){ startWizard(); }}));
        setBox.addView(g2);

        setBox.addView(u.head("이 폰"));
        LinearLayout g3 = group();
        g3.addView(linkRow("이 폰 이름", App.phoneName(this), new Runnable(){ public void run(){ renamePhone(); }}));
        g3.addView(u.hair());
        String ip = Net.myWifiIp(this);
        g3.addView(linkRow("이 폰 주소", ip == null ? "와이파이 없음" : ip, null));
        g3.addView(u.hair());
        boolean srv = App.server().isRunning();
        g3.addView(linkRow("침대 받는 서버", srv ? "켜짐" : "꺼짐 · 눌러서 켜기",
                srv ? null : new Runnable(){ public void run(){
                    startServer();
                    ui.postDelayed(new Runnable(){ public void run(){ renderSettings(); }}, 800); }}));
        if (!battOk()) {
            g3.addView(u.hair());
            g3.addView(linkRow("배터리 제한 풀기", "권장", new Runnable(){ public void run(){ askBattery(); }}));
        }
        g3.addView(u.hair());
        final boolean stay = ServerService.stayOn(this);
        g3.addView(linkRow("앱을 닫아도 대기", stay ? "켜짐 · 배터리 더 씀" : "꺼짐 · 닫고 5분 뒤 끔",
                new Runnable(){ public void run(){
                    prefs.edit().putBoolean("stayOn", !stay).apply();
                    toast(stay ? "앱을 닫고 5분이 지나면 연결을 모두 끕니다" : "앱을 닫아도 계속 침대를 기다립니다");
                    renderSettings(); }}));
        g3.addView(u.hair());
        final boolean awake = ServerService.keepAwake(this);
        g3.addView(linkRow("연결 유지 강화", awake ? "켜짐 · 배터리 더 씀" : "꺼짐 · 배터리 절약",
                new Runnable(){ public void run(){
                    prefs.edit().putBoolean("keepAwake", !awake).apply();
                    ServerService.locksChanged();
                    toast(awake ? "껐습니다. 배터리를 아낍니다" : "켰습니다. 폰이 잠들지 않고 침대를 기다립니다");
                    renderSettings(); }}));
        setBox.addView(g3);
        setBox.addView(u.note("다른 폰이 넘기기 목록에서 이 폰을 못 찾으면, 위 주소를 직접 넣으면 됩니다."));
        setBox.addView(u.note("앱을 닫아도 대기: 꺼 두면 앱을 닫고 5분 뒤 침대 연결과 다른 폰 연결을 모두 끄고, 앱을 켜면 다시 연결합니다 "
                + "(침대가 다시 붙는 데 몇 초 걸립니다). 그동안에는 다른 폰도 이 폰의 침대를 조작할 수 없습니다. "
                + "상대방 폰의 앱이 닫혀 있어도 그 침대를 쓰고 싶다면 상대방 폰에서 이걸 켜세요."));
        setBox.addView(u.note("연결 유지 강화: 평소엔 꺼 두세요. 폰 화면이 꺼진 뒤 한참 지나서 침대가 반응하지 않거나 "
                + "'기다리는 중'으로 바뀌면 그때 켜세요. 켜면 폰이 잠들지 않아 배터리를 더 씁니다."));

        setBox.addView(u.head("폰 짝짓기"));
        LinearLayout gp = group();
        StringBuilder mates = new StringBuilder();
        for (LanPeers.Peer p : lan.peers()) { if (mates.length() > 0) mates.append(", "); mates.append(p.phone); }
        boolean hasKey = HomeKey.has(this);
        gp.addView(linkRow("짝지은 폰", !hasKey ? "없음" : (mates.length() == 0 ? "지금 안 보임" : mates.toString()), null));
        gp.addView(u.hair());
        gp.addView(linkRow("다른 폰과 짝짓기", null, new Runnable(){ public void run(){ pickPair(); }}));
        if (hasKey) {
            gp.addView(u.hair());
            gp.addView(linkRow("짝 풀기", null, new Runnable(){ public void run(){ unpair(); }}));
        }
        setBox.addView(gp);
        setBox.addView(u.note("짝지은 폰끼리만 서로의 침대를 조작할 수 있습니다. 같은 와이파이의 다른 사람 폰이나 기기는 조작할 수 없습니다. "
                + "처음 한 번, 두 폰 모두 앱을 열어둔 채로 한쪽에서 짝짓기를 누르고 다른 쪽에서 '허용'을 누르면 됩니다."));

        setBox.addView(u.head("앱"));
        LinearLayout g4 = group();
        g4.addView(linkRow("새 버전 확인하고 설치", "지금 " + Updates.installed(this),
                new Runnable(){ public void run(){ checkUpdate(true); }}));
        g4.addView(u.hair());
        g4.addView(linkRow("업데이트가 막힐 때", isSamsung() ? "보안 위험 자동 차단" : null,
                new Runnable(){ public void run(){ installBlockedHelp(false); }}));
        if (!Updater.canInstall(this)) {
            g4.addView(u.hair());
            g4.addView(linkRow("앱 설치 허용 (처음 한 번)", "필요", new Runnable(){ public void run(){
                openInstallPerm(); }}));
        }
        g4.addView(u.hair());
        g4.addView(linkRow("연결 기록 보기", null, new Runnable(){ public void run(){ showLog(); }}));
        setBox.addView(g4);
        setBox.addView(u.note("앱을 켤 때 새 버전이 있는지 확인하고, 있으면 설치할지 물어봅니다. "
                + "배경에서 따로 확인하지 않아서 배터리를 쓰지 않습니다."));
        setBox.addView(u.note("문제가 생기면 연결 기록 화면을 캡처해서 보내주세요."));
    }

    /** 침대 값 목록을 글자로. 침대 통신 스레드가 동시에 값을 넣을 수 있어서(그러면 앱이 죽는다)
     *  충돌하면 몇 번 다시 시도한다 */
    private static String pinsText(Map<String,String> pins) {
        for (int tries = 0; tries < 5; tries++) {
            try {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String,String> e : pins.entrySet())
                    sb.append("  ").append(e.getKey()).append('=').append(e.getValue());
                return sb.toString();
            } catch (ConcurrentModificationException retry) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        }
        return "  (값 읽기 실패)";
    }

    /** 화면이 닫히는 중이면 창을 띄우지 않는다 (닫힌 화면에 창을 띄우면 앱이 죽는다) */
    private boolean alive() {
        return !isFinishing() && !(Build.VERSION.SDK_INT >= 17 && isDestroyed());
    }

    /** 문제 해결용 기록 — 예전 개발자 모드에서 쓸모 있던 부분만 남겼다 */
    private void showLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("서버 ").append(App.server().isRunning() ? "켜짐" : "꺼짐");
        String ip = Net.myWifiIp(this);
        sb.append(" · 이 폰 ").append(ip == null ? "와이파이 없음" : ip + ":" + BedServer.PORT).append('\n');
        for (Beds.Bed b : beds) {
            BedServer.Dev dd = App.server().byToken(b.token);
            sb.append(b.name).append("  ").append(dd == null ? "연결 안 됨" : dd.ip);
            if (dd != null) sb.append(pinsText(dd.pins));
            sb.append('\n');
        }
        for (LanPeers.Peer p : lan.allPeers())
            sb.append("이웃 ").append(p.phone).append("  ").append(p.ip)
              .append(lan.paired(p) ? "  짝 · 침대 " + p.beds.size() + "대" : (p.old() ? "  옛 버전" : "  짝 아님"))
              .append('\n');
        // 침대가 접속하며 보낸 것 — 원래 앱의 알람·LED 핀을 알아내는 단서 (캡처해서 보내주면 된다)
        List<String> hello = App.bedHello();
        sb.append("\n[침대가 접속하며 보낸 것]\n");
        if (hello.isEmpty()) sb.append("  아직 없음 — 아래 '침대 다시 연결'을 누르고 10초 뒤 다시 열어보세요\n");
        for (String h : hello) sb.append("  ").append(h).append('\n');
        sb.append('\n');
        for (String[] x : App.log()) sb.append(x[0]).append("  ").append(x[1]).append("  ").append(x[2]).append('\n');

        TextView tv = u.text(sb.toString().trim(), 11f, u.fg, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(u.dp(18), u.dp(8), u.dp(18), u.dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
            .setTitle("연결 기록")
            .setView(sv)
            .setPositiveButton("닫기", null)
            .setNeutralButton("침대 다시 연결", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { reconnectBeds(); } })
            .show();
    }

    /** 침대 서버를 잠깐 껐다 켠다 — 침대가 다시 접속하면서 처음 보내는 것들을 기록에 남기려고 */
    private void reconnectBeds() {
        toast("침대 연결을 다시 합니다. 10초쯤 뒤 연결 기록을 다시 열어보세요");
        bg(new Runnable(){ public void run(){
            App.server().stop();
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            App.server().start();
        }});
    }

    // ── 주기 ───────────────────────────────────────────
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!ticking) return;
            refresh();
            tickQuick();
            ui.postDelayed(this, 800);
        } };
    private final Runnable poll = new Runnable() {
        public void run() {
            if (!ticking) return;
            // 20초에 한 번은 무드등·스피커 상태도 묻는다 (앱을 새로 켜면 늘 '꺼짐'으로 보이던 문제)
            askNow(pollCount++ % 8 == 0);
            ui.postDelayed(this, 2500);
        } };

    private final Runnable peerPoll = new Runnable() {
        public void run() {
            if (!ticking) return;
            // 앞의 갱신이 아직 안 끝났으면 건너뛴다 (늦게 끝난 옛 응답이 새 목록을 덮지 않게)
            if (peerBusy.compareAndSet(false, true)) bg(new Runnable(){ public void run(){
                try {
                    lan.refresh();
                    final List<LanPeers.RemoteBed> rb = lan.remoteBeds();
                    post(new Runnable(){ public void run(){ applyRemotes(rb); }});
                } finally { peerBusy.set(false); }
            }});
            // 이웃 침대를 보고 있으면 자주(1.2초) 받아와서 리모컨처럼 바로바로 보이게, 아니면 3초
            if (ticking) ui.postDelayed(this, curRemote() != null ? 1200 : 3000);
        } };

    /** 이웃 폰 침대 목록을 반영한다 — 고른 침대는 순서가 바뀌어도 그대로 유지 */
    private void applyRemotes(List<LanPeers.RemoteBed> all) {
        String selTok = selRemote && sel < remotes.size() ? remotes.get(sel).token : null;
        boolean wasNone = remotes.isEmpty();
        allRemotes = all;
        List<LanPeers.RemoteBed> mine = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (LanPeers.RemoteBed r : all) {
            if (ownToken(r.token)) continue;       // 내 목록에 있는 침대는 한 번만 보인다
            mine.add(r);
            sig.append(r.token).append('=').append(r.name).append(';');
        }
        remotes = mine;
        // 지난번에 이웃 침대를 보다가 앱을 껐다면, 그 침대가 보이는 순간 그리로 옮긴다 (직접 고르기 전까지만)
        boolean restored = false;
        if (!restoreTok.isEmpty() && screen == SCR_MAIN) {
            for (int i = 0; i < remotes.size(); i++) if (remotes.get(i).token.equals(restoreTok)) {
                selRemote = true; sel = i; selTok = null; restoreTok = ""; restored = true;
            }
        }
        if (selTok != null) {
            int found = -1;
            for (int i = 0; i < remotes.size(); i++) if (remotes.get(i).token.equals(selTok)) found = i;
            if (found >= 0) sel = found;
            else {
                // 보던 이웃 침대가 사라졌다 — 끌던 것·목표를 모두 버리고, 설정 화면도 새 침대로 다시 그린다
                selRemote = false; sel = 0; targets.clear(); dragPin = null;
                if (screen == SCR_SETTINGS) renderSettings();
            }
        }
        fixSel();
        boolean changed = !sig.toString().equals(remoteSig);
        remoteSig = sig.toString();

        if (screen == SCR_MAIN) {
            if (beds.isEmpty() && remotes.isEmpty()) { rebuild(); return; }   // 볼 침대가 없어졌다 → 연결하기 화면
            if (restored) { switched(); return; }
            if (changed) { buildTabs(); applyBedSpecific(); updateHero(); }
        } else if (screen == SCR_WIZARD && wasNone && !remotes.isEmpty() && beds.isEmpty()
                && wizBox != null && step == 0 && !wizHandover) {
            // 마법사 첫 화면에 있을 때만, 안내를 띄우기 위해 다시 그린다
            String typed = wizNameIn != null ? wizNameIn.getText().toString() : null;
            renderStep();
            if (typed != null && !typed.isEmpty() && wizNameIn != null)
                wizNameIn.setText(typed);
        }
    }

    private void askBurst() {
        for (int i = 1; i <= 8; i++)
            ui.postDelayed(new Runnable(){ public void run(){ askNow(); }}, i * 900L);
    }

    private void askNow() { askNow(false); }

    /** 침대에 지금 값을 묻는다. toggles 면 무드등·스피커 상태도 묻는다 (앱을 새로 켜면 모르니까).
     *  앞의 질문이 아직 안 끝났으면 건너뛴다 — 이웃 폰이 느릴 때 질문 스레드가 쌓이지 않게 */
    private void askNow(final boolean toggles) {
        if (!askBusy.compareAndSet(false, true)) return;
        final LanPeers.RemoteBed r = curRemote();
        final BedServer.Dev d = r == null ? dev() : null;
        if (r == null && d == null) { askBusy.set(false); return; }
        final boolean tb = hasTable();
        bg(new Runnable(){ public void run(){
            try {
                List<String> pins = new ArrayList<>(Arrays.asList("11", "13"));
                if (tb) pins.add("14");
                if (toggles) { pins.add("52"); pins.add("61"); }
                for (String p : pins) {
                    if (r != null) lan.send(r.peerIp, r.token, p, "read");
                    else App.server().read(d, p);
                }
            } finally { askBusy.set(false); }
        }});
    }

    @Override protected void onDestroy() {
        sendQ.shutdown();   // 이미 넣은 명령은 마저 보낸다
        ui.removeCallbacksAndMessages(null);
        App.setCallback(null);
        super.onDestroy();
    }

    // ── 새로고침 ───────────────────────────────────────
    private void refresh() {
        handlePair();
        // 다른 폰이 이 폰 침대의 이름을 바꾼 경우 등 — 저장된 목록을 다시 읽는다
        if (App.bedsRev != seenRev) {
            seenRev = App.bedsRev;
            String selTok = cur() != null ? cur().token : null;
            beds = Beds.load(prefs);
            if (selTok != null) for (int i = 0; i < beds.size(); i++) if (beds.get(i).token.equals(selTok)) sel = i;
            fixSel();
            buildTabs();
            renderAlarms();          // 다른 폰이 이 폰 침대의 알람을 바꿨을 수도 있다
            if (screen == SCR_SETTINGS) renderSettings();
        }
        if (screen == SCR_WIZARD) {
            if (wizBox != null && step == 0 && !wizHandover && orphanToken() != null
                    && wizBox.getChildCount() > 0 && !wizShownOrphan) {
                wizShownOrphan = true;
                String typed = wizNameIn != null ? wizNameIn.getText().toString() : null;
                renderStep();
                if (typed != null && !typed.isEmpty() && wizNameIn != null && !beds.isEmpty())
                    wizNameIn.setText(typed);
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

        updateHero();
        settleTargets();
        int h = pinValue("V11", -1), l = pinValue("V13", -1), tb = pinValue("V14", -1);
        Tgt ht = targets.get("11"), lt = targets.get("13"), tt = targets.get("14");

        bedView.set(Math.max(deg("11", h), 0), Math.max(deg("13", l), 0),
                ht == null ? -1 : deg("11", ht.to), lt == null ? -1 : deg("13", lt.to));
        if (!on) { angleText.setText("침대를 기다리는 중"); angleText.setTextColor(u.muted); }
        else {
            // 움직이는 중에는 "몇 도 → 몇 도" 를 보여준다
            angleText.setText("상체 " + moveText("11", h, ht) + "    ·    다리 " + moveText("13", l, lt));
            angleText.setTextColor(ht != null || lt != null ? u.accent : u.fg);
        }

        setVal(headVal, "11", h, ht);
        setVal(legVal, "13", l, lt);
        setVal(tableVal, "14", tb, tt);
        syncBar("11", h, ht); syncBar("13", l, lt); syncBar("14", tb, tt);

        statePill(lightState, toggleOn("52"));
        statePill(speakerState, toggleOn("61"));

        slotText.setText(slotLine());
        stopBtn.setAlpha(on ? 1f : 0.45f);
        checkAddress(rb != null, on);
        updateNotice();
    }

    private String moveText(String pin, int v, Tgt t) {
        if (t == null) return fmt(pin, v);
        int from = t.from >= 0 ? t.from : v;
        return (from >= 0 ? fmt(pin, from) + " → " : "→ ") + fmt(pin, t.to);
    }

    private void setVal(TextView tv, String pin, int v, Tgt t) {
        if (tv == null || pin.equals(dragPin)) return;     // 끌고 있는 동안에는 덮어쓰지 않는다
        tv.setText(moveText(pin, v, t));
        tv.setTextColor(t != null ? u.accent : u.fg);
    }

    /** 메인 화면 위쪽 안내 카드 */
    private void updateNotice() {
        if (noticeCard == null) return;
        final String orphan = orphanToken();
        if (orphan != null) {
            String known = knownName(orphan);
            noticeView.setText("이 폰에 새로 접속한 침대가 있습니다"
                    + (known != null ? " (" + known + ")" : "") + ".\n"
                    + "다른 폰에서 넘겨받은 침대라면, 여기서 등록하면 끝입니다.");
            noticeBtn.setText("이 폰에 등록하기");
            noticeAction = new Runnable(){ public void run(){ addOrphan(orphan); }};
            noticeCard.setVisibility(View.VISIBLE);
            return;
        }
        final Beds.Bed b = cur();
        if (b != null && dev() == null) {
            for (LanPeers.RemoteBed r : allRemotes) {
                if (r.token.equals(b.token) && r.online) {
                    noticeView.setText("'" + b.name + "' 는 지금 " + (r.phone.isEmpty() ? "다른 폰" : r.phone)
                            + " 에 붙어 있습니다.\n그 폰으로 넘겼다면 이 폰 목록에서 빼주세요. "
                            + "빼도 ↗ 표시로 계속 조작할 수 있습니다.");
                    noticeBtn.setText("이 폰 목록에서 빼기");
                    noticeAction = new Runnable(){ public void run(){ removeBed(); }};
                    noticeBtn.setVisibility(View.VISIBLE);
                    noticeCard.setVisibility(View.VISIBLE);
                    return;
                }
            }
        }
        // 같은 와이파이에 짝짓지 않은 마베드 폰이 있다 (5.9.0 부터는 짝을 지어야 서로의 침대를 조작할 수 있다)
        if (lan.peers().isEmpty()) {
            for (final LanPeers.Peer p : lan.allPeers()) {
                if (lan.paired(p)) continue;
                if (p.old()) {
                    noticeView.setText("같은 와이파이에 '" + p.phone + "' 폰이 있지만 옛 버전입니다.\n"
                            + "그 폰에서도 새 버전을 설치하면 짝을 지어 서로의 침대를 조작할 수 있습니다.");
                    noticeBtn.setVisibility(View.GONE);
                } else {
                    noticeView.setText("같은 와이파이에 '" + p.phone + "' 폰이 있습니다.\n"
                            + "짝을 지으면 서로의 침대를 조작할 수 있습니다. 두 폰 모두 앱을 열어두세요.");
                    noticeBtn.setText("'" + p.phone + "' 와 짝짓기");
                    noticeBtn.setVisibility(View.VISIBLE);
                    noticeAction = new Runnable(){ public void run(){ doPair(p.ip, p.phone); }};
                }
                noticeCard.setVisibility(View.VISIBLE);
                return;
            }
        }
        noticeAction = null;
        noticeBtn.setVisibility(View.VISIBLE);
        noticeCard.setVisibility(View.GONE);
    }

    // ── 알람 ───────────────────────────────────────────
    // 내 침대면 이 폰 저장소에서 읽고 고친다. 상대 침대(↗)면 주인 폰에 물어보고, 주인 폰에 부탁해서 고친다
    // (설정은 주인 폰에 저장되고 주인 폰이 침대에 넣는다 — 사용자 결정 5.12.0).

    /** 알람을 고치는 창구 */
    private interface AlarmSink { void apply(Map<String, String> op, String okMsg); }

    /** 상대 침대 알람 (주인 폰에서 받아온 것). 볼 때마다 다시 받는다 */
    private final Map<String, Alarms.State> remoteAlarms = new HashMap<>();
    private final Set<String> remoteLoading = new HashSet<>();

    private static Map<String, String> op(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** 메인 화면의 알람 칸 */
    private void renderAlarms() {
        if (alarmBox == null) return;
        alarmBox.removeAllViews();
        quickLeft = null; quickTok = null; quickShownAt = 0;
        final Beds.Bed b = cur();
        if (b != null) {
            final String tok = b.token;
            if (!Alarms.ready(prefs, tok)) { alarmBox.addView(alarmTestCard(tok)); return; }
            drawAlarms(tok, Alarms.local(prefs, tok), localSink(tok), null);
            return;
        }
        final LanPeers.RemoteBed rb = curRemote();
        if (rb == null) return;
        String owner = rb.phone.isEmpty() ? "주인 폰" : rb.phone;
        Alarms.State st = remoteAlarms.get(rb.token);
        if (st == null) {
            alarmBox.addView(u.note(owner + " 에서 알람을 불러오는 중…"));
            loadRemoteAlarms(rb);
            return;
        }
        if (st.error != null) {
            alarmBox.addView(u.note(st.error));
            alarmBox.addView(u.small("다시 불러오기", new Runnable(){ public void run(){
                remoteAlarms.remove(rb.token); renderAlarms(); }}));
            return;
        }
        if (!st.ready()) {
            alarmBox.addView(u.note("이 침대는 " + owner + " 에서 알람 시험을 먼저 해야 알람을 쓸 수 있습니다."));
            return;
        }
        drawAlarms(rb.token, st, remoteSink(rb), owner);
    }

    /** 알람 시험 전 안내 칸 (내 침대만) */
    private View alarmTestCard(String tok) {
        boolean none = Alarms.clock(prefs, tok).equals("none");
        LinearLayout box = u.col();
        box.addView(u.text(none
                ? "지난 알람 시험에서 침대가 움직이지 않았습니다.\n침대가 연결된 상태에서 한 번 더 해볼 수 있습니다."
                : "원래 앱의 알람을 되살렸습니다 — 정해진 시각에 침대가 스스로 상체를 올려 깨워줍니다. "
                  + "폰이 꺼져 있어도 침대가 알아서 합니다.\n\n처음 한 번, 침대 시계를 맞추는 5분짜리 시험이 필요합니다.",
                13.5f, u.fg, false));
        Button go = u.btn(none ? "알람 시험 다시 하기" : "알람 시험 시작 (5분)", u.accent, 0xFFFFFFFF, 0, 14, 12,
                new Runnable(){ public void run(){ startAlarmTest(); }});
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = u.dp(10); gp.bottomMargin = 0;
        go.setLayoutParams(gp);
        box.addView(go);
        return u.card(box, 14);
    }

    private void loadRemoteAlarms(final LanPeers.RemoteBed rb) {
        if (!remoteLoading.add(rb.token)) return;
        bg(new Runnable(){ public void run(){
            final JSONObject o = lan.alarms(rb.peerIp, rb.token);
            post(new Runnable(){ public void run(){
                remoteLoading.remove(rb.token);
                Alarms.State s = Alarms.fromJson(o);
                if (o == null) s.error = (rb.phone.isEmpty() ? "주인 폰" : rb.phone)
                        + " 에서 알람을 받아오지 못했습니다. 그 폰도 새 버전이고 앱이 켜져 있는지 확인해주세요.";
                remoteAlarms.put(rb.token, s);
                if (curRemote() != null && curRemote().token.equals(rb.token)) renderAlarms();
            }});
        }});
    }

    private AlarmSink localSink(final String tok) {
        return new AlarmSink() { public void apply(Map<String, String> o, String okMsg) {
            try { Alarms.apply(prefs, tok, o); }
            catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
            renderAlarms();
            BedServer.Dev d = App.server().byToken(tok);
            if (d == null) { toast("저장했습니다. 침대가 연결되면 바로 들어갑니다"); return; }
            pushAlarms(tok);
            if (okMsg != null) toast(okMsg);
        }};
    }

    private AlarmSink remoteSink(final LanPeers.RemoteBed rb) {
        return new AlarmSink() { public void apply(final Map<String, String> o, final String okMsg) {
            toast("주인 폰에 저장하는 중…");
            bg(new Runnable(){ public void run(){
                final JSONObject r = lan.alarmSet(rb.peerIp, rb.token, o);
                post(new Runnable(){ public void run(){
                    if (r != null && r.optBoolean("ok", false)) {
                        remoteAlarms.put(rb.token, Alarms.fromJson(r.optJSONObject("alarms")));
                        renderAlarms();
                        if (okMsg != null) toast(okMsg);
                    } else {
                        String m = r == null ? "" : r.optString("msg", "");
                        toast(m.isEmpty() ? "바꾸지 못했습니다. " + (rb.phone.isEmpty() ? "주인 폰" : rb.phone) + " 도 새 버전으로 올려주세요" : m);
                    }
                }});
            }});
        }};
    }

    /** 알람 칸 그리기 — 반복 알람 2개 + 빠른 알람 + 알람 때 상체 높이. owner != null 이면 상대 침대 */
    private void drawAlarms(final String tok, final Alarms.State st, final AlarmSink sink, String owner) {
        LinearLayout g = u.col();
        g.setBackground(u.box(u.card, u.line, 18));
        for (int i = 1; i <= Alarms.COUNT; i++) {
            if (i > 1) g.addView(u.hair());
            g.addView(alarmRow(i, st.al[i - 1], sink));
        }
        g.addView(u.hair());
        g.addView(quickBlock(tok, st, sink));
        g.addView(u.hair());
        // 알람 때 상체 높이 (원래 앱의 '알람시 상체높이' — 침대에 자리가 하나라 모든 알람 공통)
        LinearLayout hb = u.col();
        hb.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(6));
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.addView(u.text("알람 때 상체 높이 (모든 알람 공통)", 14, u.muted, true), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView hv = u.text(fmt("11", st.height), 16, u.fg, true);
        hd.addView(hv);
        hb.addView(hd);
        final Slider hs = new Slider(this, u.line, u.accent, u.card, u.accent);
        hs.setMax(80);
        hs.setValue(st.height);
        hs.setListener(new Slider.Listener() {
            public void onSlide(int v, boolean done) {
                hv.setText(fmt("11", v));
                if (done) sink.apply(op("op", "height", "v", String.valueOf(v)), "알람 때 상체 높이 " + fmt("11", v));
            }
            public void onCancel() { hv.setText(fmt("11", st.height)); }
        });
        hb.addView(hs, new LinearLayout.LayoutParams(-1, u.dp(46)));
        g.addView(hb);
        alarmBox.addView(g);
        alarmBox.addView(u.note(owner != null
                ? "이 침대의 알람은 " + owner + " 에 저장되고, 그 폰이 침대에 넣어줍니다. 바꾸려면 그 폰의 앱이 켜져 있거나 '앱을 닫아도 대기'가 켜져 있어야 합니다."
                : "알람은 침대가 스스로 실행합니다. 폰이 꺼져 있어도 됩니다. 알람 1·2 는 매주 그 요일마다, 빠른 알람은 한 번만."));
    }

    private View alarmRow(final int i, final Alarms.A a, final AlarmSink sink) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u.dp(16), u.dp(12), u.dp(12), u.dp(12));
        row.setMinimumHeight(u.rawDp(56));
        LinearLayout col = u.col();
        col.addView(u.text("알람 " + i + "   " + Alarms.hm(a.start), 16, a.on ? u.fg : u.muted, true));
        col.addView(u.text(Alarms.daysText(a.days) + " · " + Alarms.downText(a.down), 12.5f, u.muted, false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView pill = u.pill(a.on ? "켜짐" : "꺼짐", a.on ? 0xFFFFFFFF : u.muted, a.on ? u.accent : u.bg, a.on ? 0 : u.line);
        pill.setPadding(u.dp(16), u.dp(9), u.dp(16), u.dp(10));
        pill.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            boolean on = !a.on;
            sink.apply(op("op", "alarm", "i", String.valueOf(i), "on", on ? "1" : "0",
                          "days", String.valueOf(on && a.days == 0 ? 0x7F : a.days)),
                    on ? "알람 " + i + " 켰습니다 · " + Alarms.hm(a.start) : "알람 " + i + " 껐습니다");
        }});
        pill.setContentDescription("알람 " + i + (a.on ? " 끄기" : " 켜기"));
        row.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        row.setClickable(true);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(u.dark ? 0x33FFFFFF : 0x22000000), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editAlarm(i, a, sink); }});
        return row;
    }

    /** 알람 하나 고치기 — 시각, 요일, 다시 눕히기 */
    private void editAlarm(final int i, Alarms.A orig, final AlarmSink sink) {
        final Alarms.A a = new Alarms.A();
        a.on = orig.on; a.start = orig.start; a.days = orig.days; a.down = orig.down;
        LinearLayout box = u.col();
        box.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);

        box.addView(u.text("시각", 13, u.muted, true));
        final Button timeBtn = u.btn(Alarms.hm(a.start), u.card, u.fg, u.line, 24, 10, null);
        timeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            new android.app.TimePickerDialog(MainActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
                    a.start = h * 3600 + m * 60; timeBtn.setText(Alarms.hm(a.start)); }
            }, a.start / 3600, (a.start / 60) % 60, true).show();
        }});
        box.addView(timeBtn);

        box.addView(u.text("요일", 13, u.muted, true));
        LinearLayout dayRow = u.row(8);
        final Button[] dayBtns = new Button[7];
        for (int x = 0; x < 7; x++) {
            final int bit = 1 << x;
            final Button bt = u.btn(Alarms.DAY_NAMES[x], u.card, u.fg, u.line, 12.5f, 8, null);
            bt.setMinWidth(0); bt.setMinimumWidth(0);
            dayBtns[x] = bt;
            bt.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                a.days ^= bit; paintDays(dayBtns, a.days); }});
            dayRow.addView(bt, u.w(1, 1));
        }
        paintDays(dayBtns, a.days);
        box.addView(dayRow);

        box.addView(u.text("올라간 뒤 다시 눕히기", 13, u.muted, true));
        box.addView(downRow(a.down, new DownPick() { public void picked(int sec) { a.down = sec; }}));

        new android.app.AlertDialog.Builder(this)
            .setTitle("알람 " + i)
            .setView(box)
            .setPositiveButton("저장하고 켜기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    if (a.days == 0) { toast("요일을 하나 이상 골라주세요"); return; }
                    a.on = true;
                    sink.apply(op("op", "alarm", "i", String.valueOf(i), "on", "1", "start", String.valueOf(a.start),
                                  "days", String.valueOf(a.days), "down", String.valueOf(a.down)),
                            "알람 " + i + " · " + Alarms.describe(a));
                } })
            .setNeutralButton("끄기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    sink.apply(op("op", "alarm", "i", String.valueOf(i), "on", "0"), "알람 " + i + " 껐습니다"); } })
            .setNegativeButton("취소", null).show();
    }

    private interface DownPick { void picked(int sec); }

    /** '다시 눕히기' 고르는 줄: 안 함 · 5분 · 10분 · 30분 · 1시간 */
    private View downRow(int current, final DownPick pick) {
        LinearLayout dr = u.row(4);
        dr.setPadding(0, u.dp(6), 0, 0);
        final List<Button> bs = new ArrayList<>();
        int on = 0;
        for (int x = 0; x < Alarms.DOWN_CHOICES.length; x++) {
            final int sec = Alarms.DOWN_CHOICES[x], idx = x;
            if (sec == current) on = x;
            Button bt = u.btn(Alarms.DOWN_SHORT[x], u.bg, u.fg, u.line, 12.5f, 8, null);
            bt.setMinWidth(0); bt.setMinimumWidth(0);
            bt.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                paintChoice(bs, idx); pick.picked(sec); }});
            bs.add(bt);
            dr.addView(bt, u.w(1, 2));
        }
        paintChoice(bs, on);
        return dr;
    }

    // ── 빠른 알람 ("30분 뒤") — 침대의 알람 3 자리 ─────────────
    private TextView quickLeft;          // "1시간 12분 뒤" — 1초마다 갱신
    private String quickTok = null;
    private long quickShownAt = 0;       // 화면에 그린 빠른 알람 시각
    private int quickShownDown = 0;

    private View quickBlock(final String tok, final Alarms.State st, final AlarmSink sink) {
        LinearLayout qb = u.col();
        qb.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(10));
        quickTok = tok;
        quickShownAt = st.quickAt;
        quickShownDown = st.quickDown;
        if (st.quickAt != 0) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout col = u.col();
            col.addView(u.text("빠른 알람   " + Alarms.clockText(st.quickAt), 16, u.fg, true));
            quickLeft = u.text(Alarms.leftText(st.quickAt) + " 올라갑니다 · " + Alarms.downText(st.quickDown), 12.5f, u.accent, true);
            col.addView(quickLeft);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView cancel = u.pill("취소", u.fg, u.bg, u.line);
            cancel.setPadding(u.dp(16), u.dp(9), u.dp(16), u.dp(10));
            cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                sink.apply(op("op", "quickoff"), "빠른 알람을 취소했습니다"); }});
            r.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
            qb.addView(r);
            return qb;
        }
        qb.addView(u.text("빠른 알람 — 지금부터 (한 번만)", 14, u.muted, true));
        // 10분 · 30분 · 1시간 · 2시간 + 마지막으로 직접 정한 시간 (사용자 결정 5.12.0)
        List<Integer> mins = new ArrayList<>(Arrays.asList(10, 30, 60, 120));
        int last = prefs.getInt("qaLastMin", 0);
        if (last > 0 && !mins.contains(last)) mins.add(last);
        LinearLayout row = u.row(0);
        row.setPadding(0, u.dp(8), 0, 0);
        for (final int m : mins) {
            Button b = u.btn(Alarms.minText(m).replace("시간 ", "시간\n"), u.bg, u.fg, u.line, 13f, 8,
                    new Runnable(){ public void run(){ setQuick(sink, m, st.quickDown); }});
            b.setMinWidth(0); b.setMinimumWidth(0);
            row.addView(b, u.w(1, 2));
        }
        qb.addView(row);
        qb.addView(u.small("직접 정하기 (몇 시간 몇 분 뒤)", new Runnable(){ public void run(){ pickQuick(sink, st.quickDown); }}));
        qb.addView(u.text("올라간 뒤 다시 눕히기", 13, u.muted, true));
        qb.addView(downRow(st.quickDown, new DownPick() { public void picked(int sec) {
            sink.apply(op("op", "quickdown", "v", String.valueOf(sec)), null); }}));
        return qb;
    }

    private void setQuick(AlarmSink sink, int minutes, int down) {
        long at = System.currentTimeMillis() + minutes * 60000L;
        sink.apply(op("op", "quick", "min", String.valueOf(minutes), "down", String.valueOf(down)),
                Alarms.leftText(at) + " · " + Alarms.clockText(at) + " 에 올라갑니다");
    }

    /** 몇 시간 몇 분 뒤 — 고르는 동안 올라갈 시각을 보여준다. 정한 시간은 다음에 버튼으로 남는다 */
    private void pickQuick(final AlarmSink sink, final int down) {
        LinearLayout box = u.col();
        box.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER);
        int last = prefs.getInt("qaLastMin", 30);
        final NumberPicker hp = new NumberPicker(this);
        hp.setMinValue(0); hp.setMaxValue(23); hp.setValue(last / 60);
        final NumberPicker mp = new NumberPicker(this);
        mp.setMinValue(0); mp.setMaxValue(59); mp.setValue(last % 60);
        pr.addView(hp); pr.addView(u.text("시간", 15, u.fg, true));
        pr.addView(mp); pr.addView(u.text("분 뒤", 15, u.fg, true));
        box.addView(pr);
        final TextView when = u.text("", 15, u.accent, true);
        when.setGravity(Gravity.CENTER);
        when.setPadding(0, u.dp(10), 0, 0);
        box.addView(when);
        final Runnable upd = new Runnable(){ public void run(){
            int m = hp.getValue() * 60 + mp.getValue();
            when.setText(m < 1 ? "1분 이상으로 골라주세요"
                    : "→ " + Alarms.clockText(System.currentTimeMillis() + m * 60000L) + " 에 올라갑니다");
        }};
        NumberPicker.OnValueChangeListener l = new NumberPicker.OnValueChangeListener() {
            public void onValueChange(NumberPicker p, int o, int n) { upd.run(); } };
        hp.setOnValueChangedListener(l); mp.setOnValueChangedListener(l);
        upd.run();
        new android.app.AlertDialog.Builder(this)
            .setTitle("빠른 알람")
            .setView(box)
            .setPositiveButton("맞추기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    int m = hp.getValue() * 60 + mp.getValue();
                    if (m < 1) { toast("1분 이상으로 골라주세요"); return; }
                    prefs.edit().putInt("qaLastMin", m).apply();    // 다음에 버튼으로 남긴다
                    setQuick(sink, m, down);
                } })
            .setNegativeButton("취소", null).show();
    }

    /** 1초마다 — 빠른 알람 남은 시간을 고치고, 다 끝나면 칸을 되돌린다 */
    private void tickQuick() {
        if (quickTok == null || quickLeft == null || quickShownAt == 0) return;
        if (!quickTok.equals(curToken())) return;
        if (System.currentTimeMillis() > quickShownAt + Alarms.keepMs(quickShownDown)) {
            // 울리고 다시 눕히기까지 끝났다 — 침대의 알람 3 자리를 끈다 (안 끄면 다음 주 같은 시각에 또 울린다)
            final String tok = quickTok;
            quickShownAt = 0;
            if (cur() != null) {
                Alarms.quickAt(prefs, tok);          // 저장소에서 지운다
                final BedServer.Dev d = App.server().byToken(tok);
                if (d != null) sendQ.execute(new Runnable(){ public void run(){ Alarms.push(MainActivity.this, d); }});
            } else remoteAlarms.remove(tok);         // 상대 침대는 주인 폰이 끈다 — 다시 받아온다
            renderAlarms();
            return;
        }
        quickLeft.setText(Alarms.leftText(quickShownAt) + " 올라갑니다 · " + Alarms.downText(quickShownDown));
    }

    private void paintChoice(List<Button> bs, int on) {
        for (int x = 0; x < bs.size(); x++) {
            boolean s = x == on;
            u.ripple(bs.get(x), s ? u.accent : u.card, s ? 0 : u.line, 14);
            bs.get(x).setTextColor(s ? 0xFFFFFFFF : u.fg);
        }
    }

    private void paintDays(Button[] bs, int days) {
        for (int x = 0; x < 7; x++) {
            boolean s = (days & (1 << x)) != 0;
            u.ripple(bs[x], s ? u.accent : u.card, s ? 0 : u.line, 14);
            bs[x].setTextColor(s ? 0xFFFFFFFF : u.fg);
        }
    }

    /** 바뀐 알람을 침대에 바로 넣는다. 침대가 연결돼 있지 않으면 다음에 접속할 때 들어간다 */
    private void pushAlarms(final String tok) {
        final BedServer.Dev d = App.server().byToken(tok);
        if (d == null) return;
        sendQ.execute(new Runnable(){ public void run(){ Alarms.push(MainActivity.this, d); }});
    }

    // ── 알람 시험 — 침대 시계가 한국 시각인지 UTC 인지 알아낸다 ──────────
    // 알람 1 은 '한국 시각' 기준으로 2분 뒤, 알람 2 는 'UTC' 기준으로 4분 뒤에 맞춰 넣고,
    // 침대가 몇 분에 올라가는지 상체 값으로 확인한다. 끝나면 두 시험 알람은 끈다.
    private static final int TEST_HEIGHT = 30;

    private void startAlarmTest() {
        final Beds.Bed b = cur();
        final BedServer.Dev d = dev();
        if (b == null || d == null) { toast("침대가 연결돼 있어야 시험할 수 있습니다"); return; }
        if (alarmTesting) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle("알람 시험 (5분)")
            .setMessage("1. 침대를 평평하게 눕힙니다\n"
                    + "2. 2분 뒤, 또는 4분 뒤에 상체가 조금(" + fmt("11", TEST_HEIGHT) + ") 올라갑니다\n"
                    + "3. 몇 분에 올라가는지로 침대 시계를 맞춥니다\n\n"
                    + "끝날 때까지 이 화면을 켜 두세요. 침대 위에 물건이 없는지 확인해주세요.")
            .setPositiveButton("시작", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface x, int w) { runAlarmTest(b.token, d); } })
            .setNegativeButton("취소", null).show();
    }

    /** 이 침대는 움직이는 동안 연결이 끊겼다가 스스로 다시 붙는다(5.10.0 시험에서 확인).
     *  그래서 시험은 연결 하나를 붙잡지 않고, 매번 인증키로 '지금 연결'을 찾는다.
     *  보내다 실패한 명령은 다음 초에 새 연결로 다시 보내고, 다시 붙으면 시험 알람도 다시 넣는다. */
    private void runAlarmTest(final String tok, BedServer.Dev first) {
        alarmTesting = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final BedServer s = App.server();
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle("알람 시험")
            .setMessage("평평하게 눕히는 중…")
            .setCancelable(false)
            .setNegativeButton("그만두기", null)
            .show();
        final long[] t0 = { 0 };          // 시험 알람을 넣은 시각 (0 = 아직 눕히는 중)
        final int[] base = { -1 };
        final long began = System.currentTimeMillis();
        final long[] lastSeen = { began };
        final boolean[] flatSent = { false };
        final String[][] testVals = { null };             // 넣을 시험 알람 값 (다시 붙으면 또 넣는다)
        final BedServer.Dev[] wroteTo = { null };         // 시험 알람을 넣은 연결
        App.addLog("알람", "시험 시작");

        final Runnable[] loop = new Runnable[1];
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { endAlarmTest(tok, null, dlg, loop[0]); }});

        loop[0] = new Runnable() { public void run() {
            if (!alarmTesting) return;
            long now = System.currentTimeMillis();
            final BedServer.Dev d = s.byToken(tok);
            if (d == null) {
                // 움직이는 중이라 잠깐 끊겼을 수 있다 — 40초까지 기다린다
                if (now - lastSeen[0] > 40000) { endAlarmTest(tok, "lost", dlg, this); return; }
                dlg.setMessage((t0[0] == 0 ? "평평하게 눕히는 중…" : "기다리는 중…") + "\n(침대가 움직이며 잠깐 연결이 끊겼습니다. 곧 다시 붙습니다)");
                ui.postDelayed(this, 1000);
                return;
            }
            lastSeen[0] = now;
            final int v = pinOf(d, "V11");
            Long atL = d.pinAt.get("V11");
            long at = atL == null ? 0 : atL;
            sendQ.execute(new Runnable(){ public void run(){ s.read(d, "11"); }});

            if (!flatSent[0]) {
                sendQ.execute(new Runnable(){ public void run(){ if (s.write(d, "11", "0")) flatSent[0] = true; }});
            }

            if (t0[0] == 0) {
                // 1단계: 평평하게 눕히기 (최대 90초)
                if (v >= 0 && v <= 5 && at > began + 1000 || now - began > 90000) {
                    if (v > 20) { endAlarmTest(tok, "notflat", dlg, this); return; }
                    base[0] = Math.max(0, v);
                    final int L = Alarms.nowOfDay(), off = Alarms.tzOffset();
                    final String tz = java.util.TimeZone.getDefault().getID();
                    final int u2 = ((L - off + 240) % 86400 + 86400) % 86400;
                    testVals[0] = new String[]{
                        "7", "0",
                        "12", String.valueOf(TEST_HEIGHT),
                        "1", Alarms.timeInput((L + 120) % 86400, (L + 180) % 86400, 0x7F, tz, off),
                        "2", Alarms.timeInput(u2, (u2 + 60) % 86400, 0x7F, "UTC", 0),
                        "5", "1",
                        "6", "1" };
                    t0[0] = System.currentTimeMillis();
                    App.addLog("알람", "시험 알람 (①한국 시각 2분 뒤 · ②UTC 4분 뒤)");
                } else dlg.setMessage("평평하게 눕히는 중…  지금 상체 " + fmt("11", v));
            }
            if (t0[0] != 0) {
                // 시험 알람을 아직 이 연결에 못 넣었으면 넣는다 (처음, 또는 다시 붙은 뒤)
                if (wroteTo[0] != d && testVals[0] != null) {
                    final String[] tv = testVals[0];
                    sendQ.execute(new Runnable(){ public void run(){
                        boolean ok = true;
                        for (int i = 0; i + 1 < tv.length; i += 2) ok &= s.write(d, tv[i], tv[i + 1]);
                        if (ok) wroteTo[0] = d;
                    }});
                }
                // 2단계: 몇 분에 올라가는지 본다
                long e = (now - t0[0]) / 1000;
                boolean rose = v >= 0 && v - base[0] >= 10 && at > t0[0];
                if (rose && e < 200) { endAlarmTest(tok, "local", dlg, this); return; }
                if (rose) { endAlarmTest(tok, "utc", dlg, this); return; }
                if (e > 370) { endAlarmTest(tok, "none", dlg, this); return; }
                dlg.setMessage(String.format(java.util.Locale.KOREA,
                        "지난 시간  %d:%02d\n\n① 2:00 에 올라가면 — 한국 시각\n② 4:00 에 올라가면 — UTC\n\n지금 상체 %s\n\n이 화면을 켜 둔 채 기다려주세요.",
                        e / 60, e % 60, fmt("11", v)));
            }
            ui.postDelayed(this, 1000);
        }};
        ui.postDelayed(loop[0], 500);
    }

    private int pinOf(BedServer.Dev d, String key) {
        String v = d.pins.get(key);
        if (v != null) try { return (int) Double.parseDouble(v.trim()); } catch (Exception ignored) {}
        return -1;
    }

    /** 시험 끝 — 시험 알람을 끄고, 결과를 저장하고, 사용자 알람을 다시 넣는다 */
    private void endAlarmTest(final String tok, final String result,
                              android.app.AlertDialog dlg, Runnable loop) {
        alarmTesting = false;
        if (loop != null) ui.removeCallbacks(loop);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { dlg.dismiss(); } catch (Throwable ignored) {}
        if ("local".equals(result) || "utc".equals(result) || "none".equals(result)) Alarms.setClock(prefs, tok, result);
        // 시험 알람을 반드시 끈다 — 안 그러면 매일 그 시각에 침대가 올라간다.
        // 연결이 끊겨 있으면 1분까지 다시 시도하고, 그래도 안 되면 표시해 두었다가 다음 접속 때 끈다 (Alarms.push)
        Alarms.markDirty(prefs, tok, true);
        final BedServer s = App.server();
        bg(new Runnable(){ public void run(){
            for (int i = 0; i < 60; i++) {
                BedServer.Dev d = s.byToken(tok);
                if (d != null && s.write(d, "5", "0") && s.write(d, "6", "0")) {
                    Alarms.markDirty(prefs, tok, false);
                    Alarms.push(MainActivity.this, d);     // 시계를 알았으면 사용자 알람을 다시 넣는다 (기본은 모두 꺼짐)
                    App.addLog("알람", "시험 알람을 껐습니다");
                    return;
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
            App.addLog("알람", "시험 알람을 아직 못 껐습니다 — 다음에 침대가 붙을 때 끕니다");
        }});
        App.addLog("알람", "시험 끝 · " + (result == null ? "그만둠" : result));
        renderAlarms();
        if (result == null || !alive()) return;
        String title, msg;
        switch (result) {
            case "local": case "utc":
                title = "알람 준비 완료";
                msg = "침대가 " + (result.equals("local") ? "2분" : "4분") + " 뒤에 올라갔습니다 — 침대 시계를 맞췄습니다.\n\n"
                        + "이제 메인 화면 '알람'에서 시각과 요일을 정하고 켜면 됩니다. 폰이 꺼져 있어도 침대가 알아서 올라갑니다.\n"
                        + "침대가 시험 때문에 올라가 있으니 필요하면 평평하게 눕혀주세요.";
                break;
            case "none":
                title = "침대가 움직이지 않았습니다";
                msg = "5분 동안 두 시험 알람 모두 반응이 없었습니다. 침대의 알람 방식이 예상과 다른 것 같습니다.\n\n"
                        + "이 화면을 캡처해서 보내주시면 다른 방법(폰이 알람을 맡는 방식)을 준비하겠습니다.";
                break;
            case "notflat":
                title = "시험을 시작하지 못했습니다";
                msg = "침대가 평평하게 눕혀지지 않았습니다. 리모컨이나 '평평하게'로 눕힌 뒤 다시 해주세요.";
                break;
            default:
                title = "시험이 중단됐습니다";
                msg = "시험 중에 침대 연결이 끊겼습니다. 연결된 뒤 다시 해주세요.";
        }
        new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("알겠습니다", null).show();
    }

    // ── 폰 짝짓기 (9099 창구 잠금) ─────────────────────────
    private android.app.AlertDialog pairDialog;     // 다른 폰의 요청에 대한 '허용/거절' 창

    /** 다른 폰이 짝짓기를 요청했으면 '허용/거절'을 묻는다 */
    private void handlePair() {
        final App.PairReq r = App.pendingPair();
        if (r == null) {
            if (pairDialog != null && pairDialog.isShowing()) try { pairDialog.dismiss(); } catch (Throwable ignored) {}
            pairDialog = null;
            return;
        }
        if (r.shown || !App.uiVisible || !alive()) return;
        r.shown = true;
        pairDialog = new android.app.AlertDialog.Builder(this)
            .setTitle("짝짓기 요청")
            .setMessage("'" + r.name + "' 폰 (" + r.ip + ") 이 이 폰과 짝을 짓자고 합니다.\n\n"
                    + "허용하면 두 폰이 서로의 침대를 조작할 수 있습니다.\n"
                    + "우리 집 폰이 아니면 거절하세요.")
            .setCancelable(false)
            .setPositiveButton("허용", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    App.pairAnswer(r, true); remoteSig = ""; toast("짝지었습니다"); } })
            .setNegativeButton("거절", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { App.pairAnswer(r, false); } })
            .show();
    }

    /** 짝지을 폰 고르기 */
    private void pickPair() {
        final List<String> labels = new ArrayList<>(), ips = new ArrayList<>(), names = new ArrayList<>();
        for (LanPeers.Peer p : lan.allPeers()) {
            if (lan.paired(p)) continue;
            labels.add(p.phone + "   " + p.ip + (p.old() ? "   · 옛 버전" : ""));
            ips.add(p.old() ? "" : p.ip);
            names.add(p.phone);
        }
        labels.add("주소를 직접 입력하기");
        ips.add("manual"); names.add("");
        new android.app.AlertDialog.Builder(this)
            .setTitle("어느 폰과 짝을 지을까요?")
            .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    String ip = ips.get(which);
                    if (ip.isEmpty()) { toast("그 폰에서 새 버전을 먼저 설치해주세요"); return; }
                    if (ip.equals("manual")) { pairManual(); return; }
                    doPair(ip, names.get(which));
                } })
            .setNegativeButton("취소", null).show();
    }

    private void pairManual() {
        final EditText e = u.input("예: 192.168.0.10", "", false);
        new android.app.AlertDialog.Builder(this)
            .setTitle("짝지을 폰의 주소")
            .setMessage("상대 폰에서 앱을 열고 설정 → '이 폰 주소'에 나오는 숫자를 넣으세요.")
            .setView(e)
            .setPositiveButton("짝짓기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String ip = e.getText().toString().trim();
                    if (!Net.isLan(ip)) { toast("집 안 주소가 아닙니다"); return; }
                    doPair(ip, ip);
                } })
            .setNegativeButton("취소", null).show();
    }

    /** 상대 폰에 짝짓기를 요청하고, 상대가 '허용'을 누를 때까지 기다린다 (최대 1분).
     *  요청한 쪽이 상대의 열쇠를 받아 쓰므로, 이 폰이 이미 다른 짝(다른 열쇠)이 있으면 그 짝은 풀린다 — 먼저 묻는다 */
    private void doPair(final String ip, final String name) {
        String otherHome = null;
        for (LanPeers.Peer p : lan.allPeers()) if (p.ip.equals(ip)) otherHome = p.home;
        boolean mine = HomeKey.has(this);
        String myHome = HomeKey.id(HomeKey.get(this));
        if (mine && otherHome != null && !otherHome.isEmpty() && !otherHome.equals(myHome)) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("이미 다른 짝이 있습니다")
                .setMessage("이 폰과 '" + name + "' 폰은 서로 다른 폰과 짝을 지은 상태입니다.\n"
                        + "계속하면 이 폰은 '" + name + "' 쪽 짝으로 옮겨가고, 지금 짝과는 풀립니다.")
                .setPositiveButton("계속", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { doPairNow(ip, name); } })
                .setNegativeButton("취소", null).show();
            return;
        }
        doPairNow(ip, name);
    }

    private void doPairNow(final String ip, final String name) {
        final android.app.AlertDialog wait = new android.app.AlertDialog.Builder(this)
            .setTitle("짝짓기")
            .setMessage("'" + name + "' 폰에 '짝짓기 요청' 창이 떴습니다.\n그 폰에서 [허용]을 눌러주세요.\n\n(최대 1분 기다립니다)")
            .setCancelable(false)
            .show();
        bg(new Runnable(){ public void run(){
            final String err = lan.pair(ip);
            post(new Runnable(){ public void run(){
                try { wait.dismiss(); } catch (Throwable ignored) {}
                if (!alive()) return;
                if (err == null) {
                    remoteSig = "";
                    toast("짝지었습니다. 곧 상대 침대가 보입니다");
                    if (screen == SCR_SETTINGS) renderSettings();
                } else {
                    new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("짝짓지 못했습니다").setMessage(err)
                        .setPositiveButton("알겠습니다", null).show();
                }
            }});
        }});
    }

    private void unpair() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("짝을 풀까요?")
            .setMessage("이 폰의 집 열쇠를 버립니다. 짝지었던 폰들과 서로 조작할 수 없게 됩니다.\n"
                    + "폰을 잃어버렸거나 바꿨을 때 쓰세요. 남은 폰끼리는 다시 짝을 지으면 됩니다.")
            .setPositiveButton("짝 풀기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    HomeKey.clear(MainActivity.this);
                    remoteSig = "";
                    App.addLog("짝", "짝을 풀었습니다");
                    toast("짝을 풀었습니다");
                    renderSettings();
                } })
            .setNegativeButton("취소", null).show();
    }

    /** 폰 주소가 침대에 심어준 주소와 달라졌는지 본다 */
    private void checkAddress(boolean remote, boolean online) {
        if (warnCard == null) return;
        if (remote || online || cur() == null) { warnCard.setVisibility(View.GONE); return; }
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
                    + "· 공유기 설정에서 이 폰 주소를 " + want + " 로 고정하거나\n"
                    + "· 설정 → 침대 추가하기로 주소를 다시 심어주세요");
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

    private void syncBar(String pin, int cur, Tgt target) {
        if (pin.equals(dragPin)) return;
        Slider sb = bars.get(pin);
        if (sb == null) return;
        if (cur >= 0) sb.setValue(Math.min(cur, sb.getMax()));
        sb.setTarget(target == null ? -1 : Math.min(target.to, sb.getMax()));
    }

    // ── 동작 ───────────────────────────────────────────
    private void startServer() {
        Intent i = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
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

    /** 그 값을 침대에게서 받은 시각 (없으면 0) */
    private long pinAt(String key) {
        LanPeers.RemoteBed r = curRemote();
        Long t = null;
        if (r != null) t = r.pinAt.get(key);
        else { BedServer.Dev d = dev(); if (d != null) t = d.pinAt.get(key); }
        return t == null ? 0 : t;
    }

    /** 명령을 보낸다. 보낼 수 없는 상태면 알려주고 false */
    private boolean sendPin(final String pin, final String value) {
        final LanPeers.RemoteBed r = curRemote();
        final String k = curToken() + "|" + pin;
        if (r != null) {
            if (!r.online) { toast("그 침대가 지금 꺼져 있습니다"); return false; }
            rememberSent(k, value);
            if (pin.equals("11") || pin.equals("13") || pin.equals("14")) askBurst();
            sendQ.execute(new Runnable(){ public void run(){
                if (!lan.send(r.peerIp, r.token, pin, value))
                    sendFailed(r.token, pin, "명령이 전달되지 않았습니다. "
                            + (r.phone.isEmpty() ? "주인 폰" : r.phone) + " 이 켜져 있는지 확인해주세요");
            }});
            return true;
        }
        final BedServer.Dev d = dev();
        if (d == null) { toast(cur() == null ? "침대를 먼저 고르세요" : "이 침대가 접속해 있지 않습니다"); return false; }
        rememberSent(k, value);
        if (pin.equals("11") || pin.equals("13") || pin.equals("14")
                || pin.equals(prefs.getString("stopPin","41"))) askBurst();
        sendQ.execute(new Runnable(){ public void run(){
            if (!App.server().write(d, pin, value))
                sendFailed(d.token, pin, "명령이 전달되지 않았습니다. 침대 연결이 끊겼습니다");
        }});
        return true;
    }

    /** 보내기 실패 — 그 목표는 없던 일로 한다 (다음 + 가 안 간 값을 기준으로 계산하지 않게) */
    private void sendFailed(final String token, final String pin, final String msg) {
        post(new Runnable(){ public void run(){
            if (token.equals(targetsToken)) targets.remove(pin);
            toast(msg);
            refresh();
        }});
    }

    private void rememberSent(String k, String value) {
        try {
            sentVal.put(k, (int) Double.parseDouble(value));
            sentAt.put(k, System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    private boolean preset(int body, int leg) {
        if (!sendPin("11", String.valueOf(body))) return false;
        setTarget("11", body);
        if (sendPin("13", String.valueOf(leg))) setTarget("13", leg);
        refresh();
        return true;
    }

    private void doStop() {
        targets.clear();
        sendPin(prefs.getString("stopPin", "41"), "1");
        toast("정지");
        refresh();
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
                if (!alive()) return;
                if (n != null) {
                    showUpdate(n);
                    // 앱을 켰을 때 찾은 새 버전은 한 번 묻는다. '나중에'를 누르면 위쪽 카드로만 남는다
                    if (manual || !n.version.equals(askedVer)) { askedVer = n.version; askInstall(n); }
                }
                else if (manual) toast("지금이 최신 버전입니다 (" + Updates.installed(MainActivity.this) + ")");
            } });
    }

    private void askInstall(final Updates.Info n) {
        String note = n.notes == null ? "" : n.notes.trim();
        if (note.length() > 300) note = note.substring(0, 300) + "…";
        new android.app.AlertDialog.Builder(this)
            .setTitle("새 버전 " + n.version)
            .setMessage((note.isEmpty() ? "" : note + "\n\n")
                    + "지금 " + Updates.installed(this) + " → " + n.version + "\n"
                    + "설치하는 동안 앱이 잠깐 닫히고, 침대 연결이 몇 초 끊겼다 다시 붙습니다.")
            .setPositiveButton("설치", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { startUpdate(n); } })
            .setNeutralButton("브라우저로 받기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { pending = n; openUpdate(); } })
            .setNegativeButton("나중에", null).show();
    }

    /** 새 버전을 앱 안에서 받아 설치한다 */
    private void startUpdate(final Updates.Info n) {
        if (!Updater.hasApk(n)) { openUpdate(); return; }       // APK 가 없는 릴리스 — 브라우저로
        if (!Updater.canInstall(this)) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("처음 한 번만 허용해주세요")
                .setMessage("안드로이드는 플레이스토어 밖의 앱이 스스로 업데이트하려면 허용을 한 번 받아야 합니다.\n\n"
                        + "다음 화면에서 '이 출처 허용'(또는 '출처를 알 수 없는 앱 설치')을 켜고\n뒤로 돌아오면 바로 설치를 이어갑니다.")
                .setPositiveButton("허용하러 가기", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        installAfterPerm = true; openInstallPerm(); } })
                .setNegativeButton("취소", null).show();
            return;
        }
        if (updWaiting) { toast("이미 내려받는 중입니다"); return; }
        updWaiting = true;
        if (updView != null) updView.setText("새 버전 " + n.version + " 내려받는 중…");
        toast("내려받는 중…");
        bg(new Runnable(){ public void run(){
            try {
                // 받은 양을 보여준다 — 멈춰 있는지 진행 중인지 눈으로 보이게
                Updater.download(MainActivity.this, n, new Updater.Progress() {
                    long shown = -1;
                    public void at(final long done, final long total) {
                        long kb = done / 1024;
                        if (kb == shown) return;
                        shown = kb;
                        post(new Runnable(){ public void run(){
                            if (updView != null) updView.setText("새 버전 " + n.version + " 내려받는 중… "
                                    + (done / 1024) + "KB" + (total > 0 ? " / " + (total / 1024) + "KB" : ""));
                        }});
                    }
                });
                post(new Runnable(){ public void run(){
                    updWaiting = false;
                    if (!alive()) return;
                    showUpdate(n);
                    // 안드로이드 기본 설치 화면 ("업데이트할까요?") — 브라우저로 받아 설치할 때와 같은 화면
                    try {
                        startActivity(Updater.installIntent());
                        // 돌아왔을 때 버전이 그대로면 '막혔나요?' 안내를 띄우려고 기록해 둔다
                        prefs.edit().putLong("updLaunchAt", System.currentTimeMillis())
                                    .putString("updFromVer", Updates.installed(MainActivity.this)).apply();
                        toast("'업데이트'를 누르면 끝납니다");
                    } catch (Throwable t) {
                        App.addLog("업데이트", "설치 화면 열기 실패 · " + t);
                        updateFailed(n, "설치 화면을 열지 못했습니다 (" + t.getClass().getSimpleName() + ")");
                    }
                }});
            } catch (final Exception e) {
                post(new Runnable(){ public void run(){
                    updWaiting = false;
                    if (!alive()) return;
                    showUpdate(n);
                    updateFailed(n, e.getMessage());
                }});
            }
        }});
    }

    /** 설치 화면에서 돌아왔을 때: 버전이 바뀌었으면 완료, 그대로면 막혔을 수 있으니 안내 (30분 안에 돌아온 경우만) */
    private void checkInstallOutcome() {
        long at = prefs.getLong("updLaunchAt", 0);
        if (at == 0) return;
        String from = prefs.getString("updFromVer", "");
        prefs.edit().remove("updLaunchAt").remove("updFromVer").apply();
        String now = Updates.installed(this);
        if (!now.equals(from)) { toast("업데이트 완료 · " + now); return; }
        if (System.currentTimeMillis() - at > 30L * 60 * 1000) return;
        installBlockedHelp(false);
    }

    private static boolean isSamsung() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /** 설치가 막혔을 때 — 경로 설명만 하지 않고, 그 설정 화면을 바로 연다.
     *  삼성 One UI 6+ 의 '보안 위험 자동 차단'이 켜져 있으면 스토어 밖 앱 설치가 막히고, '이 출처 허용'도 회색으로 잠긴다.
     *  자동 차단 화면으로 가는 공개된 주소는 없어서, 그 바로 앞인 '보안 및 개인정보 보호'(ACTION_SECURITY_SETTINGS)를 연다.
     *  permScreen = '이 출처 허용'을 켜러 갔다가 못 켜고 돌아온 경우 */
    private void installBlockedHelp(boolean permScreen) {
        if (!alive()) return;
        String msg;
        if (isSamsung()) {
            msg = (permScreen ? "'이 출처 허용'이 켜지지 않았습니다 (스위치가 회색이었나요?).\n\n"
                              : "업데이트가 설치되지 않았습니다.\n\n")
                + "삼성 폰의 '보안 위험 자동 차단'이 켜져 있으면 플레이스토어 밖의 앱은 설치·업데이트가 막힙니다.\n\n"
                + "1. 아래 [보안 설정 열기] → '보안 및 개인정보 보호' 화면이 열립니다\n"
                + "2. '보안 위험 자동 차단'을 눌러 끕니다\n"
                + "3. 이 앱으로 돌아오면 설치를 바로 이어서 합니다\n\n"
                + "업데이트가 끝나면 다시 켜도 됩니다 (그럼 다음 업데이트 때 또 꺼야 합니다).";
        } else {
            msg = (permScreen ? "'이 출처 허용'이 켜지지 않았습니다.\n\n" : "업데이트가 설치되지 않았습니다.\n\n")
                + "폰의 보안 설정이 스토어 밖 앱 설치를 막고 있을 수 있습니다. [보안 설정 열기]에서 관련 차단을 끄고 돌아오면 이어서 설치합니다.";
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("설치가 막혔나요?")
            .setMessage(msg)
            .setPositiveButton("보안 설정 열기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { openSecuritySettings(); } })
            .setNeutralButton("브라우저로 받기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { openUpdate(); } })
            .setNegativeButton("닫기", null).show();
    }

    private void openSecuritySettings() {
        installAfterSecurity = true;
        try { startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)); return; }
        catch (Throwable ignored) {}
        try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
        catch (Throwable t) { installAfterSecurity = false; toast("설정 화면을 열 수 없습니다"); }
    }

    /** 앱 안 업데이트가 안 됐을 때 — 이유를 보여주고, 브라우저로 받는 길을 연다 (끝없이 기다리게 두지 않는다) */
    private void updateFailed(Updates.Info n, String why) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("업데이트하지 못했습니다")
            .setMessage((why == null ? "" : why + "\n\n")
                    + "'브라우저로 받기'를 누르면 깃허브에서 직접 받아 설치할 수 있습니다.")
            .setPositiveButton("브라우저로 받기", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { openUpdate(); } })
            .setNegativeButton("닫기", null).show();
    }

    private void openInstallPerm() {
        // 허용 화면에 가 있는 사이 앱이 정리돼도, 다시 열면 바로 새 버전을 확인하게
        prefs.edit().putLong("updCheckedAt", 0).apply();
        try { startActivity(Updater.permIntent(this)); }
        catch (Throwable t) { installAfterPerm = false; toast("이 폰에서는 설정 화면을 열 수 없습니다"); }
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
        // 새 버전 정보가 없으면(앱이 새로 켜진 뒤 등) 최신 릴리스 페이지로
        String url = pending != null ? pending.url : "https://github.com/" + Updates.REPO + "/releases/latest";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) { toast("인터넷 창을 열 수 없습니다"); }
    }

    private void toggleTable() {
        prefs.edit().putBoolean(keyOf("table"), !hasTable()).apply();
        applyBedSpecific();
        renderSettings();
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
        if (preset(b, prefs.getInt(keyOf(s + "l"), 0))) toast(s + " 자세로");
    }
    private String slotLine() {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{"A","B"}) {
            int b = prefs.getInt(keyOf(s + "b"), -1);
            sb.append(s).append(" ").append(b < 0 ? "비어 있음"
                    : (deg("11", b) + "도 · " + deg("13", prefs.getInt(keyOf(s + "l"), 0)) + "도")).append("      ");
        }
        return sb.toString().trim();
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
            if (screen == SCR_WIZARD && wizBox != null) renderStep();
        }
    }

    private void bg(Runnable r) { new Thread(r).start(); }
    private void post(Runnable r) { ui.post(r); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
