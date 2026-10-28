package com.glslplayground.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
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

    @Override protected void onServiceConnected() {
        connected = true;
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
        super.onDestroy();
    }
}
