/*
 * TapTarget —— 把一次点击**定向注入给某个 uid 的窗口**，绕过"最顶层窗口"命中。
 *
 * 为什么需要它：悬浮球是 TYPE_APPLICATION_OVERLAY，普通注入（input / injectInputEvent）
 * 会先命中球所在的窗口；球为了让开只能挪走/隐藏（肉眼就是闪一下）。
 * 定向注入只投给目标 uid 的窗口 —— 球连事件都收不到，自然"无事发生"。
 *
 * 用法（root 或 shell）：
 *   CLASSPATH=/data/local/tmp/taptarget.jar app_process /system/bin TapTarget X Y [UID]
 *   UID 省略或 -1 = 普通注入（对照用）
 */
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public class TapTarget {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: TapTarget X Y [TARGET_UID]");
            System.exit(2);
        }
        float x = Float.parseFloat(args[0]);
        float y = Float.parseFloat(args[1]);
        int uid = args.length > 2 ? Integer.parseInt(args[2]) : -1;

        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            Class<?> evCls = Class.forName("android.view.InputEvent");
            Object im = imCls.getMethod("getInstance").invoke(null);

            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);

            // 本机（澎湃 / Android 13）没有 injectInputEventToTarget，
            // 但有 3 参数的 injectInputEvent(event, mode, targetUid)。
            Method target = null;
            try {
                target = imCls.getMethod("injectInputEventToTarget", evCls, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (target == null) {
                try {
                    target = imCls.getMethod("injectInputEvent", evCls, int.class, int.class);
                } catch (NoSuchMethodException ignored) {
                }
            }

            boolean ok1, ok2;
            if (uid >= 0 && target != null) {
                ok1 = (Boolean) target.invoke(im, down, 0, uid);
                ok2 = (Boolean) target.invoke(im, up, 0, uid);
                System.out.println("targeted(" + target.getName() + ") uid=" + uid + " -> " + ok1 + "/" + ok2);
            } else {
                Method plain = imCls.getMethod("injectInputEvent", evCls, int.class);
                ok1 = (Boolean) plain.invoke(im, down, 0);
                ok2 = (Boolean) plain.invoke(im, up, 0);
                System.out.println("plain injectInputEvent -> " + ok1 + "/" + ok2);
            }
            down.recycle();
            up.recycle();
            System.exit(ok1 && ok2 ? 0 : 1);
        } catch (Throwable e) {
            System.err.println("failed: " + e);
            if (e.getCause() != null) System.err.println("cause: " + e.getCause());
            System.exit(3);
        }
    }
}
