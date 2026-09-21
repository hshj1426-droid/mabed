package kr.mabed.control;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** 설치 결과를 받는다. 사람의 확인이 필요하면 확인 화면을 띄우거나 알림으로 부탁한다 */
public class InstallReceiver extends BroadcastReceiver {

    @SuppressWarnings("deprecation")
    @Override public void onReceive(Context c, Intent i) {
        try {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) { Updater.busy = false; return; }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                boolean shown = false;
                if (App.uiVisible) {
                    // 앱을 보고 있으면 바로 안드로이드의 '설치' 확인 창을 띄운다
                    try { c.startActivity(confirm); shown = true; } catch (Throwable ignored) {}
                }
                if (!shown) {
                    // 알림 권한이 꺼져 있을 수도 있다 — 앱을 다시 열면 그때 창을 띄운다
                    App.keepConfirm(confirm);
                    // 뒤에서는 창을 띄울 수 없다 — 알림을 누르면 확인 창이 뜬다
                    int f = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                    PendingIntent tap = PendingIntent.getActivity(c, 4, confirm, f);
                    Updater.alert(c, "마베드 새 버전 설치 준비됨", "눌러서 설치를 마쳐주세요", tap);
                }
                App.addLog("업데이트", "설치 확인을 기다리는 중");
                Updater.busy = false;
                return;
            }
            Updater.busy = false;
            if (st == PackageInstaller.STATUS_SUCCESS) {
                App.addLog("업데이트", "설치 완료");
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                App.addLog("업데이트", "설치 안 됨 · " + st + (msg == null ? "" : " · " + msg));
                if (st != PackageInstaller.STATUS_FAILURE_ABORTED)   // 사용자가 취소한 건 알리지 않는다
                    Updater.alert(c, "마베드 업데이트 실패", msg == null ? "설정 → 연결 기록을 확인해주세요" : msg,
                            Updater.openApp(c));
            }
        } catch (Throwable t) {
            Updater.busy = false;
        }
    }
}
