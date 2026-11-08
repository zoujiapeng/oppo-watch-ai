/*
 * PowerKeyDaemon —— 常驻电源键 / 音量减监听（root / app_process）。
 *
 * 为什么不用 Xposed / 不用 grab：
 *   澎湃（HyperOS）里长按电源键的电源菜单是 **system_server 自己** 画的
 *   （Window{… LegacyGlobalActions}，session=system_server），SystemUI 那层钩不到；
 *   而 `settings put global power_button_long_press 0` 之后，框架对长按**什么都不做** ——
 *   这时候只要旁边听着键盘事件就够了，不用抢（EVIOCGRAB）也不会影响短按锁屏、音量键。
 *
 * 做的事：
 *   /dev/input/event* 里带 KEY_POWER / KEY_VOLUMEDOWN 的节点全听（物理的 qpnp_pon、
 *   gpio-keys + 注入用的 uinput）：
 *     · KEY_POWER 按下超过 holdMs（默认 650ms）→ 发 POWER_BALL
 *       （App 那边：第一次 = 呼球 + 按住说话；第二次 = 收麦 + 发回上一次回话）
 *     · KEY_VOLUMEDOWN 按下 → 立刻发 VOLUME_SEND
 *       （App 那边：只有正在按住说话时才收麦 + 发到全新会话；球没开就什么都不做，
 *         所以平时按音量减仍然是正常的调音量）
 *     · KEY_VOLUMEUP 单击   → 等 420ms 没等到第二下，发 VOLUME_HIDE
 *       （App 那边：把所有悬浮界面藏起来；之后弹审批、按电源键、或重新打开 App 会恢复）
 *     · KEY_VOLUMEUP 快速双击 → 发 VOLUME_SHOT（App 那边取证截图：把玻璃层也拍进去）
 *
 * 用法（root）：
 *   CLASSPATH=/data/local/tmp/powerkey/powerkey-daemon.jar \
 *     app_process /system/bin PowerKeyDaemon [holdMs] [--dry] [--dev /dev/input/event1]
 *
 *   --dry        只打日志，不真的发广播
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
    static final int KEY_VOLUMEDOWN = 114;
    static final int KEY_VOLUMEUP = 115;
    static final int EV_SYN = 0x00;

    static final String APP_SVC =
            "com.glslplayground.app/.FloatingBallService";
    static final String APP_PKG = "com.glslplayground.app";
    static final String RECEIVER = APP_PKG + "/.PowerBallReceiver";
    static final String ACTION_POWER = APP_PKG + ".action.POWER_BALL";
    static final String ACTION_VOLUME = APP_PKG + ".action.VOLUME_SEND";
    static final String ACTION_HIDE = APP_PKG + ".action.VOLUME_HIDE";
    /** 音量加双击 → 取证截图（App 里把玻璃层临时放回抓屏，拍一张"真样子"）。 */
    static final String ACTION_SHOT = APP_PKG + ".action.VOLUME_SHOT";

    // 阈值要比框架自己的长按判定（~500ms）**长**：
    // 短按是锁屏，框架在 500ms 就把这次按键标成 long-press，松手不再锁；
    // 我们若在 450ms 先动手，用户 460ms 松手就会「球出来了、屏幕也锁了」。
    static volatile long holdMs = 650;
    static volatile boolean dry = false;

    /** 每个被监听设备的按下时间；-1 = 现在没按住。 */
    static final Object lock = new Object();
    static volatile long downAt = -1;
    static volatile boolean fired = false;
    /**
     * 这一次物理按键是不是还按着（**跨设备**共用）。
     *
     * 本机有两路节点都报 KEY_POWER：`qpnp_pon`（event1）和 `uinput-goodix`（event2）。
     * 同一次长按会被两路各报一次 down —— 以前每来一个 down 就把 downAt/fired 重置，
     * 定时器于是**再**发一条 POWER_BALL。App 那边把第二条当成「用户又按了一下」→ 立刻收麦，
     * 表现就是用户报的「语音刚开始说就自己结束 / 一句话都识别不出来」，
     * 以及收麦后 4 秒键盘恢复显形 ——「说着说着键盘自己弹出来」。
     * 现在：只要还有一路按着，后面来的 down 一律忽略；任意一路报 up 才算松手。
     */
    static volatile boolean pressActive = false;
    /** 同一路节点上，按下超过这么久还没等到 up，就认为 up 丢了，允许下一次按下。 */
    static final long STALE_PRESS_MS = 8000;
    /** 上一次 fire 的时刻：两路节点几乎同时到点时，只发一条广播。 */
    static volatile long lastPowerFireMs = 0L;
    /** 音量减：按下即触发，700ms 内只算一次（多个节点 / 注入节点同时上报时去重）。 */
    static volatile long lastVolFireMs = 0L;
    /**
     * 音量加：**要看双击**，不能再"按下即发"。
     *
     * 一次物理按下的判定：
     *   · 80ms 内同一颗键的重复上报（多路 input 节点）→ 丢掉；
     *   · 距上一次按下 ≤ {@link #VOL_DOUBLE_MS} → 这是一次**双击** → 发 VOLUME_SHOT；
     *   · 只有一下 → 等 {@link #VOL_DOUBLE_MS} 确认没第二下，再发 VOLUME_HIDE。
     *
     * 以前是"按下即发 VOLUME_HIDE" + 700ms 去重：双击的第二下正好被 700ms 吃掉，
     * 所以 App 里那个「双击音量减截图」永远等不到第二下（用户：「取证没了」）。
     */
    static final long VOL_DOUBLE_MS = 420L;
    static final long VOL_UP_DUP_MS = 80L;
    static volatile long lastVolUpDownAt = 0L;
    static volatile String lastVolUpDev = "";
    static volatile java.util.TimerTask pendingVolHide = null;
    /** 单线程定时器：只用来"等第二下"。 */
    static final java.util.Timer volTimer = new java.util.Timer("pk-vol", true);

    /** 单例锁：开机脚本和手动 daemon-start 会各起一个，两个一起听同一颗键 = 一次按下发两条广播。 */
    static java.io.RandomAccessFile lockRaf;

    static boolean acquireSingleton() {
        try {
            lockRaf = new java.io.RandomAccessFile("/data/adb/powerkey/daemon.lock", "rw");
            if (lockRaf.getChannel().tryLock() == null) return false;
            return true;                       // 进程活着就持有，退出自动释放
        } catch (Throwable t) {
            Log.w(TAG, "单例锁不可用（" + t + "）→ 照常跑");
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        if (!acquireSingleton()) {
            Log.i(TAG, "已经有一个 PowerKeyDaemon 在跑 → 本实例退出（避免一次按键发两条广播）");
            return;
        }
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
        if (devs.isEmpty()) devs = findKeyDevices();
        if (devs.isEmpty()) {
            Log.e(TAG, "没找到带 KEY_POWER / KEY_VOLUMEDOWN 的输入节点，退出");
            return;
        }
        if (dry) Log.i(TAG, "dry-run：只记录，不发广播");

        // 定时检查：按住到点就触发（不等松手）
        Thread timer = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) { }
                    long t = downAt;
                    // up 丢了（节点拔掉 / 注入设备消失）：别让按键永久"按着"，8 秒后放开
                    if (t > 0 && pressActive && SystemClock.uptimeMillis() - t >= STALE_PRESS_MS) {
                        Log.w(TAG, "KEY_POWER 按住超过 " + STALE_PRESS_MS + "ms 没等到 up → 强制复位");
                        synchronized (lock) { downAt = -1; pressActive = false; fired = false; }
                        continue;
                    }
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

    /** 单个设备的事件循环：read(24B) → EV_KEY/KEY_POWER | KEY_VOLUMEDOWN。 */
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
                    if (type != EV_KEY) continue;
                    if (code == KEY_VOLUMEDOWN) {
                        // 音量减：按下就发（不等长按）—— App 那边只在「按住说话」时才动手
                        if (value == 1) fireVolume(dev);
                        else if (value == 0) Log.i(TAG, "KEY_VOLUMEDOWN up @" + dev);
                        continue;
                    }
                    if (code == KEY_VOLUMEUP) {
                        // 音量加：单击 = 藏界面（延迟 420ms 判定），双击 = 取证截图
                        if (value == 1) onVolumeUp(dev);
                        else if (value == 0) Log.i(TAG, "KEY_VOLUMEUP up @" + dev);
                        continue;
                    }
                    if (code != KEY_POWER) continue;
                    long now = SystemClock.uptimeMillis();
                    if (value == 1) {                       // 按下
                        boolean dup;
                        synchronized (lock) {
                            // 已经有一路按着了 → 这是同一颗按键的另一路节点，别再重置定时器
                            dup = pressActive && now - downAt < STALE_PRESS_MS;
                            if (!dup) {
                                pressActive = true;
                                downAt = now;
                                fired = false;
                            }
                        }
                        if (dup) {
                            Log.i(TAG, "KEY_POWER down 重复（同一颗按键的另一路节点）→ 忽略 @" + dev);
                        } else {
                            Log.i(TAG, "KEY_POWER down @" + dev);
                        }
                    } else if (value == 0) {                // 松开
                        long held = 0;
                        synchronized (lock) {
                            if (downAt > 0) held = now - downAt;
                            downAt = -1;
                            pressActive = false;
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
        long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            if (now - lastPowerFireMs < 1200L) {      // 两路节点几乎同时到点：只发一条
                Log.i(TAG, "长按到点但 1.2s 内已经发过 → 去重");
                return;
            }
            lastPowerFireMs = now;
        }
        Log.i(TAG, "长按到点 → 发 POWER_BALL" + (dry ? "（dry）" : ""));
        if (dry) return;
        try {
            sh("am broadcast -a " + ACTION_POWER + " -n " + RECEIVER + " --user 0");
        } catch (Throwable t) {
            Log.e(TAG, "触发失败：" + t);
        }
    }

    /**
     * 音量减键：发 VOLUME_SEND。
     *
     * 注意这条广播**不是**「呼球」：App 那边只有在「按住说话」正开着时才会
     * 收麦 + 发到全新会话；其它时候直接忽略（音量照常由系统处理）。
     *
     * 这里故意发**隐式**广播（-p 限定包名，不带 -n 组件）：球没开的时候 App 里
     * 没有接收器，广播就没人收 —— 不会为了判断一句「什么都不做」把 App 冷启动起来。
     * App 侧的接收器在 FloatingBallService 里动态注册（随服务生死）。
     */
    static void fireVolume(String dev) {
        long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            if (now - lastVolFireMs < 700L) return;
            lastVolFireMs = now;
        }
        Log.i(TAG, "音量减键 → 发 VOLUME_SEND @" + dev + (dry ? "（dry）" : ""));
        if (dry) return;
        try {
            sh("am broadcast -a " + ACTION_VOLUME + " -p " + APP_PKG + " --user 0");
        } catch (Throwable t) {
            Log.e(TAG, "音量触发失败：" + t);
        }
    }

    /**
     * 音量加按下：判定「单击（藏界面）」还是「双击（取证截图）」。
     *
     * 为什么必须在 daemon 这一层判：App 那边只收到"按了一下"的广播，看不到中间隔了多久；
     * 而"单击藏界面"和"双击截图"又必须在**第一下按下时就分开**——否则第一下已经把界面藏了，
     * 截出来的是空屏。
     */
    static void onVolumeUp(String dev) {
        final long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            // ① 多路节点把同一次按下报了两遍：80ms 内一律当重复
            if (now - lastVolUpDownAt <= VOL_UP_DUP_MS) {
                Log.i(TAG, "KEY_VOLUMEUP down 重复（同一按键的另一路节点）→ 忽略 @" + dev);
                return;
            }
            // ② 距上一次按下足够近 → 双击
            long gap = lastVolUpDownAt > 0 ? now - lastVolUpDownAt : 0L;
            boolean dbl = lastVolUpDownAt > 0 && gap <= VOL_DOUBLE_MS;
            lastVolUpDownAt = now;
            lastVolUpDev = dev;
            if (pendingVolHide != null) {              // 上一轮还在等第二下：先撤掉
                pendingVolHide.cancel();
                pendingVolHide = null;
            }
            if (dbl) {
                lastVolUpDownAt = 0;                   // 三连击只算一次双击
                Log.i(TAG, "音量加双击（间隔 " + gap + "ms）→ 取证截图 @" + dev);
                fireShot(dev);
                return;
            }
            // ③ 只有一下：等 VOL_DOUBLE_MS，没人跟就藏界面
            final long at = now;
            java.util.TimerTask t = new java.util.TimerTask() {
                @Override public void run() {
                    synchronized (lock) {
                        if (lastVolUpDownAt != at) return;     // 中间又按了（双击被别处吃掉）
                        lastVolUpDownAt = 0;
                        pendingVolHide = null;
                    }
                    Log.i(TAG, "音量加单击 → 藏界面");
                    fireHide(lastVolUpDev);
                }
            };
            pendingVolHide = t;
            volTimer.schedule(t, VOL_DOUBLE_MS);
        }
    }

    /** 音量加双击 → 发 VOLUME_SHOT（App 收到后拍一张带玻璃的取证图）。 */
    static void fireShot(String dev) {
        Log.i(TAG, "音量加双击 → 发 VOLUME_SHOT @" + dev + (dry ? "（dry）" : ""));
        if (dry) return;
        try {
            sh("am broadcast -a " + ACTION_SHOT + " -p " + APP_PKG + " --user 0");
        } catch (Throwable t) {
            Log.e(TAG, "取证触发失败：" + t);
        }
    }

    /**
     * 音量加键：发 VOLUME_HIDE。
     *
     * 语义（App 那边实现）：**把所有悬浮界面藏起来**（球、胶囊、卡片、菜单、四区），
     * 之后弹审批、按电源键、或重新打开 App 都会让它回来。
     * 同样发隐式广播（-p 限定包名），球没开的时候没人收 —— 不会把 App 冷启动起来。
     */
    static void fireHide(String dev) {
        // 不再在这里做时间去重：单击/双击已经由 onVolumeUp() 按"每次按下"判过了，
        // 这里多一层 700ms 会把双击的那条路挡掉（以前就是这么丢的）。
        Log.i(TAG, "音量加键 → 发 VOLUME_HIDE @" + dev + (dry ? "（dry）" : ""));
        if (dry) return;
        try {
            sh("am broadcast -a " + ACTION_HIDE + " -p " + APP_PKG + " --user 0");
        } catch (Throwable t) {
            Log.e(TAG, "隐藏触发失败：" + t);
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

    /** 从 /proc/bus/input/devices 里挑出所有带 KEY_POWER 或 KEY_VOLUMEDOWN 的 event 节点。 */
    static List<String> findKeyDevices() {
        List<String> out = new ArrayList<String>();
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/bus/input/devices"));
            String name = "", handlers = "";
            boolean power = false, volume = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=")) {
                    name = line.substring(8).replace("\"", "").trim();
                    power = false;
                    volume = false;
                    handlers = "";
                } else if (line.startsWith("H: Handlers=")) {
                    handlers = line.substring(12);
                } else if (line.startsWith("B: KEY=")) {
                    // 能力位图里有 KEY_POWER(116) / KEY_VOLUMEDOWN(114) 就是它
                    String bitmap = line.substring(7).trim();
                    if (hasBit(bitmap, KEY_POWER)) power = true;
                    if (hasBit(bitmap, KEY_VOLUMEDOWN)) volume = true;
                    if (hasBit(bitmap, KEY_VOLUMEUP)) volume = true;
                } else if (line.trim().isEmpty()) {
                    addIfWanted(out, name, handlers, power, volume);
                }
            }
            addIfWanted(out, name, handlers, power, volume);
            r.close();
        } catch (Throwable t) {
            Log.w(TAG, "读 /proc/bus/input/devices 失败：" + t);
        }
        return out;
    }

    static void addIfWanted(List<String> out, String name, String handlers,
                            boolean power, boolean volume) {
        if ((!power && !volume) || handlers == null) return;
        int before = out.size();
        for (String h : handlers.split("\\s+")) {
            if (h.startsWith("event")) {
                String p = "/dev/input/" + h;
                if (!out.contains(p)) out.add(p);
            }
        }
        if (out.size() > before) {
            Log.i(TAG, "候选节点：" + name + " → " + handlers
                    + "（power=" + power + " volumedown=" + volume + "）");
        }
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
