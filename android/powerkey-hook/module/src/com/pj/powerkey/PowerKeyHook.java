package com.pj.powerkey;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Member;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 长按电源键 → 桌面悬浮球。
 *
 * 澎湃 / MIUI 里长按电源键的落点是 SystemUI 的电源菜单：
 * system_server（PhoneWindowManager）收到长按 → 让 SystemUI 弹
 * {@code MiuiGlobalActionsDialog}（关机 / 重启 / 紧急）。
 * 小爱同学那条路是另一个开关（{@code settings put system long_press_power_key none} 关掉）。
 *
 * 本模块只做一件事：在 SystemUI 里把「弹电源菜单」的入口拦下来，
 * 换成给 {@code com.glslplayground.app} 发一条广播
 * （{@code com.glslplayground.app.action.POWER_BALL}）。
 * App 收到后：亮屏 → 解锁 → 回桌面 → 起 FloatingBallService（那颗水晶球）。
 *
 * 钩子挂了三个入口，命中哪个算哪个（同一次长按只发一次，1.5s 去重）：
 *   - GlobalActionsComponent.handleShowGlobalActionsMenu()   ← system_server 的标准入口
 *   - GlobalActionsImpl.showGlobalActions(GlobalActionsManager)
 *   - MiuiGlobalActions.showOrHideDialog(boolean, boolean)   ← MIUI 自己那层；只拦「显示」
 *
 * 找不到类 / 方法一律静默跳过：换了 ROM 也不会把 SystemUI 带崩。
 * 日志看：logcat -s LSPosed-Bridge | grep PowerKey
 */
public class PowerKeyHook implements IXposedHookLoadPackage {

    private static final String TAG = "PowerKey";
    private static final String TARGET_PKG = "com.glslplayground.app";
    private static final String ACTION = "com.glslplayground.app.action.POWER_BALL";
    private static final String COMPONENT = TARGET_PKG + "/.PowerBallReceiver";

    /** 一次长按可能穿过两三层钩子，1.5 秒内只算一次。 */
    private static long lastFireMs = 0L;

    private static final String CLS_COMPONENT =
            "com.android.systemui.globalactions.GlobalActionsComponent";
    private static final String CLS_IMPL =
            "com.android.systemui.globalactions.GlobalActionsImpl";
    private static final String CLS_MIUI =
            "com.android.systemui.globalactions.MiuiGlobalActions";

    @Override public void handleLoadPackage(LoadPackageParam lp) {
        if (lp == null || !"com.android.systemui".equals(lp.packageName)) return;
        try {
            XposedBridge.log("[" + TAG + "] 注入 SystemUI process=" + lp.processName);
            hookAll(lp, CLS_COMPONENT, "handleShowGlobalActionsMenu");
            hookAll(lp, CLS_IMPL, "showGlobalActions");
            hookAll(lp, CLS_MIUI, "showOrHideDialog");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] 安装钩子失败：" + t);
        }
    }

    private void hookAll(LoadPackageParam lp, String clsName, String method) {
        Class<?> c;
        try {
            c = Class.forName(clsName, false, lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] 没有类 " + clsName + "（跳过）");
            return;
        }
        try {
            java.util.Set<XC_MethodHook.Unhook> set =
                    XposedBridge.hookAllMethods(c, method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!isShowCall(param)) return;      // hide / 别的重载：放行
                                fire("hook:" + ((Member) param.method).getName());
                                param.setResult(null);               // 吞掉原实现：不弹电源菜单
                            } catch (Throwable t) {
                                XposedBridge.log("[" + TAG + "] hook 体出错：" + t);
                            }
                        }
                    });
            XposedBridge.log("[" + TAG + "] 已挂 " + clsName + "#" + method
                    + " (" + (set == null ? 0 : set.size()) + ")");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] 挂 " + clsName + "#" + method + " 失败：" + t);
        }
    }

    /** MIUI 的 showOrHideDialog(boolean show, boolean animate)：只在 show=true 时动手。 */
    private static boolean isShowCall(XC_MethodHook.MethodHookParam p) {
        if (p == null || p.args == null || p.args.length == 0) return true;
        Object a0 = p.args[0];
        if (a0 instanceof Boolean) return (Boolean) a0;
        return true;
    }

    private static synchronized void fire(String from) {
        long now = System.currentTimeMillis();
        if (now - lastFireMs < 1500L) {
            XposedBridge.log("[" + TAG + "] 去重（" + (now - lastFireMs) + "ms）← " + from);
            return;
        }
        lastFireMs = now;
        XposedBridge.log("[" + TAG + "] 长按电源键 → 发 " + ACTION + " ← " + from);

        Context ctx = appContext();
        if (ctx != null) {
            try {
                Intent i = new Intent(ACTION);
                i.setPackage(TARGET_PKG);
                // SystemUI 跑在 system 进程里：必须带 user 发，否则只发到 USER_ALL，
                // App 那边收不到（logcat 会出一行 "without a qualified user"）。
                if (sendAsCurrentUser(ctx, i)) return;
                ctx.sendBroadcast(i);
                return;
            } catch (Throwable t) {
                XposedBridge.log("[" + TAG + "] sendBroadcast 失败：" + t);
            }
        }
        // 兜底：system uid 直接敲 am（SystemUI 进程里 Context 偶尔拿不到）
        try {
            Runtime.getRuntime().exec(new String[]{
                    "/system/bin/am", "broadcast", "-a", ACTION, "-n", COMPONENT,
                    "--user", String.valueOf(uidUser())
            });
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] am 兜底也失败：" + t);
        }
    }

    private static int uidUser() {
        try {
            return Build.VERSION.SDK_INT >= 24
                    ? (int) Class.forName("android.os.UserHandle")
                        .getMethod("myUserId").invoke(null)
                    : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) { }
        return null;
    }

    /** Context#sendBroadcastAsUser(intent, UserHandle.CURRENT) —— 反射调，省得受 api 版本限制。 */
    private static boolean sendAsCurrentUser(Context ctx, Intent i) {
        try {
            Class<?> uh = Class.forName("android.os.UserHandle");
            Object cur = uh.getField("CURRENT").get(null);
            java.lang.reflect.Method m = ctx.getClass()
                    .getMethod("sendBroadcastAsUser", Intent.class, uh);
            m.invoke(ctx, i, cur);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] sendBroadcastAsUser 不可用（" + t + "），退回 sendBroadcast");
            return false;
        }
    }
}
