package com.glslplayground.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * 长按电源键 → 桌面悬浮球上的「按住说话」。
 *
 * 触发源：
 *   1) /data/adb/powerkey 的常驻监听 PowerKeyDaemon（只读 /dev/input/event*，按住 ≥650ms 发广播）；
 *   2) 手动：{@code su -c 'am broadcast -a com.glslplayground.app.action.POWER_BALL \
 *             -n com.glslplayground.app/.PowerBallReceiver'}
 *
 * 语义（用户定的）：
 *   · 短按电源键        → 系统自己锁屏，这里完全不参与
 *   · 第一次长按        → 球浮出来，并直接进「按住说话」（松手无事发生，一直录）
 *   · 第二次长按        → 结束「按住说话」→ 转写 → 发到「日常使用」工作目录的新会话
 *   · 不回桌面、不按 HOME、不动别的东西 —— 只呼出球
 */
public class PowerBallReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.glslplayground.app.action.POWER_BALL";
    private static final String TAG = "PowerBall";

    @Override public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        Log.i(TAG, "power-ball 触发：running=" + FloatingBallService.running
                + " holdActive=" + FloatingBallService.powerHoldActive);
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
        // ① 已经在「按住说话」→ 这一次是"收麦"
        if (FloatingBallService.running && FloatingBallService.powerHoldActive) {
            Log.i(TAG, "第二次长按 → 结束按住说话并发送");
            tellService(app, "stop", false);
            return;
        }
        // ② 球没开 / 开着但没在按住说话 → 呼出球并开始按住说话
        if (FloatingBallService.running) {
            Log.i(TAG, "球已开着 → 直接开始按住说话");
            tellService(app, "start", true);
            return;
        }
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
        Log.i(TAG, "呼出球 + 开始按住说话");
        tellService(app, "start", true);
    }

    /**
     * 起/停都用同一条 Intent，靠 pk_hold 区分。
     *
     * 注意：从广播里起前台服务会被「后台启动限制」拦（AMS: Background start not allowed），
     * 冷进程尤其明显 —— 所以直连一次之后 700ms 复查，没起来就用 root 补一刀
     * （su 起服务不受那条限制；本 App 本来就有 root）。
     */
    private static void tellService(final Context app, final String what, boolean foreground) {
        boolean issued = false;
        try {
            Intent i = new Intent(app, FloatingBallService.class);
            i.putExtra("pk_hold", what);
            i.putExtra("from", "power_key");
            if (foreground && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
            issued = true;
            Log.i(TAG, "→ FloatingBallService pk_hold=" + what);
        } catch (Throwable t) {
            Log.w(TAG, "直接起服务失败（稍后走 root 兜底）：" + t);
        }
        final boolean ok = issued;
        new Thread(new Runnable() {
            @Override public void run() {
                sleep(700);
                boolean alive = "start".equals(what)
                        ? FloatingBallService.running
                        : (!FloatingBallService.running || !FloatingBallService.powerHoldActive);
                if (alive) return;
                Log.i(TAG, "直连没生效（ok=" + ok + "）→ root 兜底：起跳板 Activity");
                // 不能直接 am start-foreground-service：这条策略对 root 也生效（实测被拦）。
                // 但 root 起 Activity 是允许的 —— 跳板一起来 App 就在前台，再起前台服务就过了。
                String cmd = "am start -n com.glslplayground.app/.PowerBallTrampolineActivity"
                        + " --es pk_hold " + what + " --user 0";
                Process p = null;
                try {
                    p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                    p.waitFor();
                } catch (Throwable t) {
                    Log.w(TAG, "root 兜底失败：" + t);
                } finally {
                    if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
                }
            }
        }, "power-ball-fallback").start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static int captureSrc(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences("watch", Context.MODE_PRIVATE);
            return p.getInt("float_capture_src", 0);
        } catch (Throwable t) {
            return 0;
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
}
