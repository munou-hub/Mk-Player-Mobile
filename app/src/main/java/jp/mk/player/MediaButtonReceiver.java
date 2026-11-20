package jp.mk.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;

/**
 * ヘッドセット／Bluetooth のメディアボタンを受けるレシーバー。
 * アプリのプロセスが落ちていてもシステムがここを起こし、サービスへ転送する。
 * サービス側は保存済みキューから復元して再生できる（標準メディアアプリの挙動）。
 */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ev == null || ev.getAction() != KeyEvent.ACTION_DOWN) return;
        Intent svc = new Intent(context, PlaybackService.class)
                .setAction(PlaybackService.ACTION_MEDIA_BUTTON)
                .putExtra("keycode", ev.getKeyCode());
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
        } catch (Exception ignored) { }
    }
}
