package kr.mabed.control;

import java.io.File;

/** 시험용 '서버 폰' — 별도 프로그램으로 돈다 (실제로는 다른 폰).
 *  짝짓기 요청이 오면 요청 이름으로 허용/거절을 흉내 낸다: allow* → 허용, deny* → 거절.
 *  busy.flag 파일이 있으면 '이 폰도 짝짓기를 요청하는 중' 상태가 된다. closed.flag 가 있으면 화면이 닫힌 상태. */
public class PairServer {
    public static void main(String[] a) throws Exception {
        final File dir = new File(a[0]);
        PairTest.Ctx S = new PairTest.Ctx();
        S.p.edit().putString("phoneName", "서버폰").putString("phoneId", "srv00001")
                .putString("beds", "[{\"name\":\"아내 침대\",\"token\":\"" + a[1] + "\"}]").apply();
        App.server().start();
        App.startNet(S);
        App.uiVisible = true;
        Thread.sleep(700);
        System.out.println("READY");
        boolean busy = false;
        while (true) {
            boolean wantBusy = new File(dir, "busy.flag").exists();
            if (wantBusy && !busy) { App.pairOutStart(); busy = true; }
            if (!wantBusy && busy) { App.pairOutEnd(); busy = false; }
            App.uiVisible = !new File(dir, "closed.flag").exists();
            App.PairReq r = App.pendingPair();
            if (r != null && !r.shown) {
                r.shown = true;
                App.pairAnswer(r, r.name.startsWith("allow"));
            }
            Thread.sleep(50);
        }
    }
}
