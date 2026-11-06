/*
 * PowerKeyDaemon —— 常驻电源键监听（root / app_process）。
 *
 * 为什么不用 Xposed / 不用 grab：
 *   澎湃（HyperOS）里长按电源键的电源菜单是 **system_server 自己** 画的
 *   （Window{… LegacyGlobalActions}，session=system_server），SystemUI 那层钩不到；
 *   而 `settings put global power_button_long_press 0` 之后，框架对长按**什么都不做** ——
 *   这时候只要旁边听着键盘事件就够了，不用抢（EVIOCGRAB）也不会影响短按锁屏、音量键。
 *
 * 做的事：
 *   /dev/input/event* 里带 KEY_POWER 的节点全听（物理的 qpnp_pon + 注入用的 uinput），
 *   按下超过 holdMs（默认 650ms）→
 *     亮屏(input keyevent 224) → 解锁(wm dismiss-keyguard) → 回桌面(input keyevent 3)
 *     → am start-foreground-service com.glslplayground.app/.FloatingBallService
 *
 * 用法（root）：
 *   CLASSPATH=/data/local/tmp/powerkey/powerkey-daemon.jar \
 *     app_process /system/bin PowerKeyDaemon [holdMs] [--dry] [--dev /dev/input/event1]
 *
 *   --dry        只打日志，不真的起球
 *   --dev PATH   只监听指定节点（调试注入时用 /dev/input/event2）
 */
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class PowerKeyDaemon {

    static final String TAG = "PowerKeyDaemon";
    static final int EV_KEY = 0x01;
    static final int KEY_POWER = 116;
    static final int EV_SYN = 0x00;

    static final String APP_SVC =
            "com.glslplayground.app/.FloatingBallService";

    // 阈值要比框架自己的长按判定（~500ms）**长**：
    // 短按是锁屏，框架在 500ms 就把这次按键标成 long-press，松手不再锁；
    // 我们若在 450ms 先动手，用户 460ms 松手就会「球出来了、屏幕也锁了」。
    static volatile long holdMs = 650;
    static volatile boolean dry = false;

    /** 每个被监听设备的按下时间；-1 = 现在没按住。 */
    static final Object lock = new Object();
    static volatile long downAt = -1;
    static volatile boolean fired = false;

    public static void main(String[] args) throws Exception {
        List<String> devs = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--dry".equals(a)) {
                dry = true;
            } else if ("--dev".equals(a) && i + 1 < args.length) {
                devs.add(args[++i]);
            } else if (a.matches("\\d+")) {
                holdMs = Long.parseLong(a);
            }
        }
        if (devs.isEmpty()) devs = findPowerDevices();
        if (devs.isEmpty()) {
            Log.e(TAG, "没找到带 KEY_POWER 的输入节点，退出");
            return;
        }
        if (dry) Log.i(TAG, "dry-run：只记录，不起球");

        // 定时检查：按住到点就触发（不等松手）
        Thread timer = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) { }
                    long t = downAt;
                    if (t > 0 && !fired && SystemClock.uptimeMillis() - t >= holdMs) {
                        fired = true;
                        fire();
                    }
                }
            }
        }, "pk-timer");
        timer.setDaemon(true);
        timer.start();

        for (String d : devs) {
            final String dev = d;
            Thread th = new Thread(new Runnable() {
                @Override public void run() { readLoop(dev); }
            }, "pk-" + new File(dev).getName());
            th.setDaemon(true);
            th.start();
        }
        Log.i(TAG, "在听：" + devs + "  阈值=" + holdMs + "ms");
        Thread.sleep(Long.MAX_VALUE);
    }

    /** 单个设备的事件循环：read(24B) → EV_KEY/KEY_POWER。 */
    static void readLoop(String dev) {
        byte[] buf = new byte[24];
        while (true) {
            InputStream in = null;
            try {
                in = new FileInputStream(dev);
                Log.i(TAG, "打开 " + dev);
                while (true) {
                    if (!readFully(in, buf)) break;
                    int type = u16(buf, 16);
                    int code = u16(buf, 18);
                    int value = s32(buf, 20);
                    if (type != EV_KEY || code != KEY_POWER) continue;
                    long now = SystemClock.uptimeMillis();
                    if (value == 1) {                       // 按下
                        synchronized (lock) {
                            downAt = now;
                            fired = false;
                        }
                        Log.i(TAG, "KEY_POWER down @" + dev);
                    } else if (value == 0) {                // 松开
                        long held = 0;
                        synchronized (lock) {
                            if (downAt > 0) held = now - downAt;
                            downAt = -1;
                        }
                        Log.i(TAG, "KEY_POWER up held=" + held + "ms fired=" + fired);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "读 " + dev + " 失败：" + t);
            } finally {
                if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
            }
            sleep(1000);
        }
    }

    /** 触发：只发一条广播，由 App（PowerBallReceiver）决定「起球 / 收球 / 回桌面」。 */
    static void fire() {
        Log.i(TAG, "长按到点 → 发 POWER_BALL" + (dry ? "（dry）" : ""));
        if (dry) return;
        try {
            sh("am broadcast -a com.glslplayground.app.action.POWER_BALL "
                    + "-n com.glslplayground.app/.PowerBallReceiver --user 0");
        } catch (Throwable t) {
            Log.e(TAG, "触发失败：" + t);
        }
    }

    static void sh(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[512];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) > 0 && sb.length() < 400) {
                sb.append(new String(buf, 0, n));
            }
            p.waitFor();
            String out = sb.toString().trim().replace('\n', ' ');
            Log.i(TAG, "sh: " + cmd + " → exit=" + p.exitValue()
                    + (out.isEmpty() ? "" : " out=" + out));
        } catch (Throwable t) {
            Log.w(TAG, "sh 失败：" + cmd + " → " + t);
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
        }
    }

    /** 从 /proc/bus/input/devices 里挑出所有带 KEY_POWER 的 event 节点。 */
    static List<String> findPowerDevices() {
        List<String> out = new ArrayList<String>();
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/bus/input/devices"));
            String name = "", handlers = "";
            boolean power = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=")) {
                    name = line.substring(8).replace("\"", "").trim();
                    power = false;
                    handlers = "";
                } else if (line.startsWith("H: Handlers=")) {
                    handlers = line.substring(12);
                } else if (line.startsWith("B: KEY=")) {
                    // 能力位图里有 KEY_POWER(116) 就是它
                    if (hasBit(line.substring(7).trim(), KEY_POWER)) power = true;
                } else if (line.trim().isEmpty()) {
                    addIfPower(out, name, handlers, power);
                }
            }
            addIfPower(out, name, handlers, power);
            r.close();
        } catch (Throwable t) {
            Log.w(TAG, "读 /proc/bus/input/devices 失败：" + t);
        }
        return out;
    }

    static void addIfPower(List<String> out, String name, String handlers, boolean power) {
        if (!power || handlers == null) return;
        for (String h : handlers.split("\\s+")) {
            if (h.startsWith("event")) {
                String p = "/dev/input/" + h;
                if (!out.contains(p)) out.add(p);
            }
        }
        if (!out.isEmpty()) Log.i(TAG, "候选节点：" + name + " → " + handlers);
    }

    /** /proc 的 KEY 位图是 64 位一组、空格分隔的十六进制，第 n 位 = 码 n。 */
    static boolean hasBit(String bitmap, int code) {
        String[] groups = bitmap.split("\\s+");
        int gi = code / 64, bit = code % 64;
        // 位图从左到右是高位组 → 低位组
        int idx = groups.length - 1 - gi;
        if (idx < 0 || idx >= groups.length) return false;
        long v;
        try {
            v = Long.parseUnsignedLong(groups[idx], 16);
        } catch (Throwable t) {
            return false;
        }
        return ((v >>> bit) & 1L) != 0;
    }

    static boolean readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static int s32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
