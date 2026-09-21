package kr.mabed.control;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** 설치 결과를 받는다.
 *  안드로이드의 '설치' 확인 창은 여기(리시버)서 직접 띄우지 않는다 — 안드로이드 14 이상은
 *  리시버에서 띄우는 창을 소리 없이 막아서, 5.5.0 에서 "내려받는 중"에 멈춰 있었다.
 *  대신 화면(MainActivity)에 넘겨서 화면이 띄운다. 화면이 없으면 알림으로 부탁한다. */
public class InstallReceiver extends BroadcastReceiver {

    @SuppressWarnings("deprecation")
    @Override public void onReceive(Context c, Intent i) {
        try {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Updater.busy = false;
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) { App.installResult("설치 창을 받지 못했습니다. 다시 눌러주세요"); return; }
                App.addLog("업데이트", "설치 확인을 기다리는 중");
                App.keepConfirm(confirm);          // 화면이 가져가서 띄운다
                if (!App.uiVisible) {
                    // 화면이 뒤에 있다 — 알림을 누르면 확인 창이 뜬다 (알림이 꺼져 있으면 앱을 열 때 뜬다)
                    Intent tapI = new Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    int f = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                    Updater.alert(c, "마베드 새 버전 설치 준비됨", "눌러서 설치를 마쳐주세요",
                            PendingIntent.getActivity(c, 4, tapI, f));
                }
                App.ping();
                return;
            }
            Updater.busy = false;
            if (st == PackageInstaller.STATUS_SUCCESS) {
                App.addLog("업데이트", "설치 완료");
                App.installResult(null);
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                App.addLog("업데이트", "설치 안 됨 · " + st + (msg == null ? "" : " · " + msg));
                if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    App.installResult("설치를 취소했습니다");
                } else {
                    App.installResult("설치하지 못했습니다 (" + st + ")" + (msg == null ? "" : "\n" + msg));
                    if (!App.uiVisible)
                        Updater.alert(c, "마베드 업데이트 실패", msg == null ? "설정 → 연결 기록을 확인해주세요" : msg,
                                Updater.openApp(c));
                }
            }
        } catch (Throwable t) {
            Updater.busy = false;
            App.installResult("설치 결과를 처리하지 못했습니다 · " + t.getClass().getSimpleName());
        }
    }
}
