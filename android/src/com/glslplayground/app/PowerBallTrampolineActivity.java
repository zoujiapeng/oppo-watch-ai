package com.glslplayground.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 无界面的「起球/停球」跳板。
 *
 * 为什么需要它：从广播里直接 startForegroundService 会被后台启动限制拦掉
 * （AMS: Background start not allowed），root 用 am start-foreground-service 也一样
 * （这条策略对 shell/root 同样生效）。而 **用 root 起一个 Activity 是允许的**，
 * Activity 一起来 App 就在前台，此时再起前台服务就没问题了。
 *
 * 所以：PowerBallReceiver 直连失败时 → root `am start` 这个 Activity（Theme.NoDisplay，
 * 不画任何东西）→ 它把服务拉起来 → 立刻 finish()。
 */
public class PowerBallTrampolineActivity extends Activity {

    private static final String TAG = "PowerBall";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent it = getIntent();
        String what = it == null ? "start" : it.getStringExtra("pk_hold");
        if (what == null || what.isEmpty()) what = "start";
        // 发送位置 / 触发来源（stop 时用；音量减键要发全新会话、并把音量调回去）
        String send = it == null ? null : it.getStringExtra("pk_send");
        String from = it == null ? null : it.getStringExtra("pk_from");
        if (send == null || send.isEmpty()) send = "last";
        if (from == null || from.isEmpty()) from = "power_key";
        Log.i(TAG, "跳板起来 → pk_hold=" + what + " pk_send=" + send + " pk_from=" + from);
        try {
            Intent i = new Intent(this, FloatingBallService.class);
            i.putExtra("pk_hold", what);
            i.putExtra("pk_send", send);
            i.putExtra("pk_from", from);
            i.putExtra("from", from);
            // 调试直通（实验用）：`--es test_reply "…"` 让球把这段字摆进玻璃
            String test = it == null ? null : it.getStringExtra("test_reply");
            if (test != null) i.putExtra("test_reply", test);
            if ("stop".equals(what)) {
                startService(i);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "跳板起服务失败：" + t);
        }
        finish();
    }
}
