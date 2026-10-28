/*
 * PointerDaemon —— 常驻的"定向注入"小服务（root / app_process）。
 *
 * 为什么需要它：悬浮球是 TYPE_APPLICATION_OVERLAY，普通注入（input / injectInputEvent 两参数版）
 * 会先命中球所在的窗口 —— 球为了让开只能挪走或隐藏，肉眼就是"闪一下"。
 * 澎湃/HyperOS 上 `InputManager.injectInputEvent(event, mode, targetUid)` 会把事件**只投给目标 uid
 * 的窗口**：球连事件都收不到，不用让开、不用隐藏，玻璃完全"无事发生"（真机已验）。
 *
 * 协议（127.0.0.1:38722，一行一条，回一行）：
 *   PING                          -> PONG
 *   TAP x y [uid]                 -> OK / ERR ...
 *   SWIPE x1 y1 x2 y2 durMs [uid] -> OK / ERR ...
 *   LONG x y durMs [uid]          -> OK / ERR ...
 * uid 省略或 -1 = 自动取"当前前台 App"的 uid。
 *
 * 启动：
 *   CLASSPATH=/data/local/tmp/pointer.jar app_process /system/bin PointerDaemon [port]
 */
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PointerDaemon {

    private static Method inject3;   // injectInputEvent(InputEvent, int, int)
    private static Method inject2;   // injectInputEvent(InputEvent, int)
    private static Object im;

    private static volatile int cachedUid = -1;
    private static volatile long cachedAt = 0;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 38722;

        Class<?> imCls = Class.forName("android.hardware.input.InputManager");
        Class<?> evCls = Class.forName("android.view.InputEvent");
        im = imCls.getMethod("getInstance").invoke(null);
        try { inject3 = imCls.getMethod("injectInputEvent", evCls, int.class, int.class); }
        catch (NoSuchMethodException e) { inject3 = null; }
        inject2 = imCls.getMethod("injectInputEvent", evCls, int.class);

        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        System.out.println("pointer daemon listening on 127.0.0.1:" + port);

        while (true) {
            final Socket c = ss.accept();
            Thread t = new Thread(new Runnable() {
                @Override public void run() { serve(c); }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    private static void serve(Socket c) {
        try {
            c.setSoTimeout(4000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            OutputStream out = c.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String reply;
                try {
                    reply = handle(line);
                } catch (Throwable t) {
                    reply = "ERR " + t;
                }
                out.write((reply + "\n").getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try { c.close(); } catch (Throwable ignored) { }
        }
    }

    private static String handle(String line) throws Exception {
        String[] p = line.split("\\s+");
        String cmd = p[0].toUpperCase();

        if (cmd.equals("PING")) return "PONG";

        if (cmd.equals("TAP")) {
            float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
            int uid = p.length > 3 ? Integer.parseInt(p[3]) : -1;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = event(now, now, MotionEvent.ACTION_DOWN, x, y);
            MotionEvent up = event(now, now + 45, MotionEvent.ACTION_UP, x, y);
            boolean ok = inject(down, uid) & inject(up, uid);
            down.recycle(); up.recycle();
            return ok ? "OK" : "ERR inject-rejected";
        }

        if (cmd.equals("LONG")) {
            float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
            int dur = Integer.parseInt(p[3]);
            int uid = p.length > 4 ? Integer.parseInt(p[4]) : -1;
            long now = SystemClock.uptimeMillis();
            inject(event(now, now, MotionEvent.ACTION_DOWN, x, y), uid);
            Thread.sleep(dur);
            long t2 = SystemClock.uptimeMillis();
            inject(event(now, t2, MotionEvent.ACTION_UP, x, y), uid);
            return "OK";
        }

        if (cmd.equals("SWIPE")) {
            float x1 = Float.parseFloat(p[1]), y1 = Float.parseFloat(p[2]);
            float x2 = Float.parseFloat(p[3]), y2 = Float.parseFloat(p[4]);
            int dur = p.length > 5 ? Integer.parseInt(p[5]) : 300;
            int uid = p.length > 6 ? Integer.parseInt(p[6]) : -1;
            long t0 = SystemClock.uptimeMillis();
            inject(event(t0, t0, MotionEvent.ACTION_DOWN, x1, y1), uid);
            int steps = Math.max(6, dur / 16);
            for (int i = 1; i <= steps; i++) {
                float k = (float) i / steps;
                float x = x1 + (x2 - x1) * k;
                float y = y1 + (y2 - y1) * k;
                long t = t0 + (long) (dur * k);
                inject(event(t0, t, MotionEvent.ACTION_MOVE, x, y), uid);
                long wait = t - SystemClock.uptimeMillis();
                if (wait > 0) Thread.sleep(wait);
            }
            long tEnd = SystemClock.uptimeMillis();
            inject(event(t0, Math.max(tEnd, t0 + dur), MotionEvent.ACTION_UP, x2, y2), uid);
            return "OK";
        }

        return "ERR unknown command";
    }

    private static MotionEvent event(long downTime, long when, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(downTime, when, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    private static boolean inject(MotionEvent e, int uid) {
        try {
            int target = uid >= 0 ? uid : topUid();
            if (target >= 0 && inject3 != null) {
                return (Boolean) inject3.invoke(im, e, 0, target);
            }
            return (Boolean) inject2.invoke(im, e, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前前台 App 的 uid（1s 缓存）。拿不到就返回 -1（退回普通注入）。 */
    private static int topUid() {
        long now = System.currentTimeMillis();
        if (cachedUid > 0 && now - cachedAt < 1000) return cachedUid;
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                    "dumpsys activity activities | grep -m1 topResumedActivity"});
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            pr.waitFor();
            Matcher m = Pattern.compile("\\su(\\d+)\\s+([\\w\\.]+)/").matcher(sb.toString());
            if (!m.find()) return -1;
            String pkg = m.group(2);
            Process p2 = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                    "stat -c %u /data/data/" + pkg + " 2>/dev/null"});
            BufferedReader r2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
            String u = r2.readLine();
            r2.close();
            p2.waitFor();
            if (u == null || u.trim().isEmpty()) return -1;
            cachedUid = Integer.parseInt(u.trim());
            cachedAt = now;
            return cachedUid;
        } catch (Throwable t) {
            return -1;
        }
    }
}
