package kr.mabed.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** 폰을 껐다 켜도 서버가 다시 올라오게 한다 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent i) {
        try {
            SharedPreferences p = c.getSharedPreferences("mabed", Context.MODE_PRIVATE);
            // '앱을 닫아도 대기'를 켠 경우에만 부팅·업데이트 뒤 되살린다 (5.8.0 — 기본은 앱을 켤 때만 동작)
            if (!p.getBoolean("stayOn", false)) return;
            // 사용자가 서버를 켜둔 상태였고, 등록된 침대가 있을 때만 되살린다
            if (!p.getBoolean("serverOn", false)) return;
            String beds = p.getString("beds", "");
            if (beds == null || beds.trim().length() < 3) return;

            Intent s = new Intent(c, ServerService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Throwable ignored) {
            // 부팅 중에는 절대 죽지 않는다
        }
    }
}
