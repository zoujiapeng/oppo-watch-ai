package com.glslplayground.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 空壳无障碍服务，唯一作用是让本 App 有资格创建 {@code TYPE_ACCESSIBILITY_OVERLAY} 窗口。
 *
 * <p>为什么需要它：Android 12+ 的 InputDispatcher 有一条 tap-jacking 拦截 —— 触摸要"穿过"
 * 一个不受信任的悬浮窗落到别的 App 上时会被整串丢弃。受信任 overlay（无障碍悬浮窗、输入法）
 * 不受这条规则约束。有了它，球可以**原地不动**、只把触摸区置空就让点击穿过去，
 * 既不用把窗口挪出屏幕（会闪），也不用把球藏起来。
 *
 * <p>不读窗口内容、不收事件、不做任何手势 —— 只是个身份。
 */
public class BallA11yService extends AccessibilityService {

    /** 服务是否连着（悬浮窗类型据此切换）。 */
    static volatile boolean connected = false;

    /**
     * **带窗口 token 的 WindowManager**。
     *
     * 澎湃/HyperOS 的 WMS 加 `TYPE_ACCESSIBILITY_OVERLAY` 窗口时要求 token：
     *   "Attempted to add Accessibility overlay window with unknown token null. Aborting."
     * 用无障碍服务的 `createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)` 拿到的
     * Context 自带的 token 正好满足它 —— 这样加出来的窗口是**受信任 overlay**，
     * InputDispatcher 的"触摸穿过不受信任悬浮窗就丢弃"对它不生效，
     * 球就能原地不动、只把触摸区置空（零闪烁）。
     */
    static volatile WindowManager overlayWm = null;

    @Override protected void onServiceConnected() {
        connected = true;
        try {
            Object ctx = null;
            try {
                // 服务自己的 Context 没有绑定 display，必须用带 Display 的那个重载
                android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                        getSystemService(Context.DISPLAY_SERVICE);
                android.view.Display disp = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                java.lang.reflect.Method m3 = AccessibilityService.class.getMethod(
                        "createWindowContext", android.view.Display.class, int.class, Bundle.class);
                ctx = m3.invoke(this, disp,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, (Bundle) null);
            } catch (Throwable t2) {
                Log.w("FloatingBall", "createWindowContext(display) failed: " + t2
                        + (t2.getCause() != null ? " / " + t2.getCause() : ""));
                java.lang.reflect.Method m2 = AccessibilityService.class.getMethod(
                        "createWindowContext", int.class, Bundle.class);
                ctx = m2.invoke(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            }
            if (ctx instanceof Context) {
                overlayWm = (WindowManager) ((Context) ctx).getSystemService(Context.WINDOW_SERVICE);
                Log.i("FloatingBall", "a11y window context ok（窗口可带 token）");
            }
        } catch (Throwable t) {
            Log.w("FloatingBall", "createWindowContext failed: " + t
                    + (t.getCause() != null ? " / cause=" + t.getCause() : ""));
        }
        Log.i("FloatingBall", "a11y connected → 悬浮窗可用 TYPE_ACCESSIBILITY_OVERLAY（不再被遮挡拦截）");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        connected = false;
        overlayWm = null;
        super.onDestroy();
    }
}
