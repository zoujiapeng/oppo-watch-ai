package com.glslplayground.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * 长按电源键 → 桌面悬浮球上的「按住说话」；音量减键 → 只收麦并发送。
 *
 * 触发源：
 *   1) /data/adb/powerkey 的常驻监听 PowerKeyDaemon（只读 /dev/input/event*，
 *      电源键按住 ≥650ms → POWER_BALL（显式广播，打到这里）；
 *      音量减键按下 → VOLUME_SEND（**隐式**广播 -p 本项目，正常是被
 *      FloatingBallService 里动态注册的接收器接走，球没开时没人收、不会冷启动 App））；
 *   2) 手动（测试用）：
 *      {@code su -c 'am broadcast -a com.glslplayground.app.action.POWER_BALL \
 *             -n com.glslplayground.app/.PowerBallReceiver'}
 *      {@code su -c 'am broadcast -a com.glslplayground.app.action.VOLUME_SEND \
 *             -n com.glslplayground.app/.PowerBallReceiver'}   ← 显式打到这里（pkctl.sh fire-vol）
 *
 * 语义（用户定的）：
 *   · 短按电源键        → 系统自己锁屏，这里完全不参与
 *   · 第一次长按        → 球浮出来，并直接进「按住说话」（松手无事发生，一直录）
 *   · 第二次长按        → 结束「按住说话」→ 转写 → 发回**上一次回话**那个会话
 *                        （还没有就新建一条；详见 FloatingBallService#sendTextToHoldSession）
 *   · 音量减键          → 结束「按住说话」→ 转写 → 发到**全新会话**
 *                        只做这一件事：球没开 / 没在说话时**什么都不做，绝不呼球**
 *   · 不回桌面、不按 HOME、不动别的东西
 */
public class PowerBallReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.glslplayground.app.action.POWER_BALL";
    /** 音量减键：收麦 + 发到全新会话（由 PowerKeyDaemon 发；不呼球）。 */
    public static final String ACTION_VOLUME_SEND = "com.glslplayground.app.action.VOLUME_SEND";
    private static final String TAG = "PowerBall";

    @Override public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final boolean volumeKey = intent != null && ACTION_VOLUME_SEND.equals(intent.getAction());
        Log.i(TAG, (volumeKey ? "volume-send" : "power-ball") + " 触发：running="
                + FloatingBallService.running
                + " holdActive=" + FloatingBallService.powerHoldActive);
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    handle(app, volumeKey);
                } catch (Throwable t) {
                    Log.w(TAG, "power-ball 处理失败：" + t);
                } finally {
                    try { pr.finish(); } catch (Throwable ignored) { }
                }
            }
        }, volumeKey ? "volume-send" : "power-ball").start();
    }

    private void handle(Context app, boolean volumeKey) {
        if (volumeKey) { handleVolumeKey(app); return; }
        // ① 已经在「按住说话」→ 这一次是"收麦"，发回上一次回话
        if (FloatingBallService.running && FloatingBallService.powerHoldActive) {
            Log.i(TAG, "第二次长按 → 结束按住说话 → 发回上一次回话");
            tellService(app, "stop", false, "last", "power_key");
            return;
        }
        // ② 球没开 / 开着但没在按住说话 → 呼出球并开始按住说话
        if (FloatingBallService.running) {
            Log.i(TAG, "球已开着 → 直接开始按住说话");
            tellService(app, "start", true, "last", "power_key");
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
        tellService(app, "start", true, "last", "power_key");
    }

    /**
     * 音量减键：**只**做「结束按住说话 → 发到全新会话」。
     *
     * 球没开 / 球开着但没在按住说话时一律什么都不做 —— 尤其不能呼球，
     * 也不能把音量键的日常用途（调音量）抢走。
     */
    private void handleVolumeKey(Context app) {
        if (!FloatingBallService.running) {
            Log.i(TAG, "音量减：球没开 → 不做事（绝不呼球）");
            return;
        }
        if (!FloatingBallService.powerHoldActive) {
            Log.i(TAG, "音量减：没在按住说话 → 不做事");
            return;
        }
        Log.i(TAG, "音量减 → 结束按住说话 → 发到全新会话");
        tellService(app, "stop", false, "new", "vol_key");
    }

    /**
     * 起/停都用同一条 Intent，靠 pk_hold 区分；pk_send 决定发送位置
     * （last = 上一次回话 / new = 全新会话），pk_from 记录是谁触发的（音量键要把音量调回去）。
     *
     * 注意：从广播里起前台服务会被「后台启动限制」拦（AMS: Background start not allowed），
     * 冷进程尤其明显 —— 所以直连一次之后 700ms 复查，没起来就用 root 补一刀
     * （su 起服务不受那条限制；本 App 本来就有 root）。
     */
    private static void tellService(final Context app, final String what, boolean foreground,
                                    final String send, final String from) {
        boolean issued = false;
        try {
            Intent i = new Intent(app, FloatingBallService.class);
            i.putExtra("pk_hold", what);
            i.putExtra("pk_send", send);
            i.putExtra("pk_from", from);
            i.putExtra("from", from);
            if (foreground && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
            issued = true;
            Log.i(TAG, "→ FloatingBallService pk_hold=" + what
                    + " pk_send=" + send + " pk_from=" + from);
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
                        + " --es pk_hold " + what
                        + " --es pk_send " + send
                        + " --es pk_from " + from
                        + " --user 0";
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
