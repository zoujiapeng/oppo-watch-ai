package com.glslplayground.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * 长按电源键 → 桌面悬浮球（第二次按 = 收球）。
 *
 * 触发源：
 *   1) /data/adb/powerkey 里的常驻监听 PowerKeyDaemon（读 /dev/input/event*，只读不抢）：
 *      按住 ≥HOLD_MS 就给这里发一条广播。
 *   2) 手动：{@code su -c 'am broadcast -a com.glslplayground.app.action.POWER_BALL \
 *             -n com.glslplayground.app/.PowerBallReceiver'}
 *
 * 语义（按用户定的）：
 *   · 短按电源键         → 系统自己锁屏，这里完全不参与
 *   · 按住一会儿再松      → 亮屏 → 球立刻浮出来 → 退锁屏 → 回桌面
 *   · 球已经开着且展开成胶囊/六区 → 再按一次 = 收球（stopSelf，窗口和通知一起没）
 */
public class PowerBallReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.glslplayground.app.action.POWER_BALL";
    private static final String TAG = "PowerBall";

    @Override public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        Log.i(TAG, "power-ball 触发：action=" + (intent == null ? "-" : intent.getAction())
                + " running=" + FloatingBallService.running);
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    handle(app);
                } catch (Throwable t) {
                    Log.w(TAG, "power-ball 处理失败：" + t);
                } finally {
                    try { pr.finish(); } catch (Throwable ignored) { }
                }
            }
        }, "power-ball").start();
    }

    private void handle(Context app) {
        // ① 球已经在：展开着就收掉；还是光球就重新摆到桌面中间
        if (FloatingBallService.running) {
            if (FloatingBallService.isExpanded()) {
                Log.i(TAG, "球是展开状态（胶囊/六区）→ 收球");
                FloatingBallService.hideFromOutside();
                return;
            }
            Log.i(TAG, "球是光球状态 → 回桌面 + 摆正");
            wakeHome(app);
            return;
        }

        // ② 还没球：没权限就往 App 里引，root 直采就直接起
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "没有悬浮窗权限 → 打开 App");
            openMain(app);
            return;
        }
        if (captureSrc(app) != 1) {
            Log.i(TAG, "不是 root 直采（float_capture_src≠1）→ 拉前台让用户授权录屏");
            openMain(app);
            return;
        }

        // 球马上出来（别等下面那串 root 命令），再去点亮/退锁屏/回桌面
        startBall(app);
        runRoot("input keyevent 224; wm dismiss-keyguard; sleep 0.9; "
                + "input keyevent 3; sleep 0.45; input keyevent 3");
    }

    private static void wakeHome(Context app) {
        runRoot("input keyevent 224; wm dismiss-keyguard; sleep 0.5; input keyevent 3");
    }

    private static int captureSrc(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences("watch", Context.MODE_PRIVATE);
            return p.getInt("float_capture_src", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void startBall(Context app) {
        try {
            Intent i = new Intent(app, FloatingBallService.class);
            i.putExtra("from", "power_key");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
            Log.i(TAG, "FloatingBallService 已拉起");
        } catch (Throwable t) {
            Log.w(TAG, "起球失败：" + t);
        }
    }

    private static void openMain(Context app) {
        try {
            Intent i = new Intent(app, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("auto_float", true);
            app.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "拉不起 MainActivity：" + t);
        }
    }

    /** 一条 su 里做完，省掉多次 fork；失败不影响起球。 */
    private static void runRoot(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "su 失败（" + cmd + "）：" + t);
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
