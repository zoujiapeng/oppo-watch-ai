package com.pj.voicemap.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * VoiceMap —— 与具体输入法无关的「长按空格 = 语音」桥。
 *
 * 不重写识别、不依赖某个输入法的内部类名：识别永远是输入法自己的引擎。
 * 只做两件框架层面的事：
 *
 *  1) 隐身：当（且仅当）当前输入目标是触发 App 时，把输入法进程的窗口 alpha 置 0、
 *     去掉 FLAG_DIM_BEHIND、加 FLAG_NOT_TOUCHABLE；换成别的 App 立刻恢复 alpha=1.0
 *     并去掉那个 flag（只恢复自己动过的那一个窗口）。
 *     任何输入法都是用 InputMethodService 在自己的进程里开窗口 —— 这一层天然通用。
 *
 *  2) 触发：在输入法自己的键盘视图上，向「空格键」发一对真实 MotionEvent
 *     （DOWN … UP），让输入法自己的长按逻辑跑起来。空格键位置：
 *        a. 该输入法 + 该分辨率已校准过（SharedPreferences 有记录）→ 直接用；
 *        b. 否则用通用启发式：底行居中（几乎所有输入法的空格条都在这里）；
 *        c. `cmd=calibrate` 会逐个候选点试按，并按「是否真的开始录音 / 是否出现语音视图」
 *           自动判定命中，命中点存盘 —— 新输入法不用改代码，自校准即可。
 */
public class VoiceMapHook implements IXposedHookLoadPackage {

    static final String TAG = "VoiceMap";
    /** 触发 App 默认包名；任何 App 发 cmd=register 后也会被加入可隐身白名单 */
    static final String TRIGGER_PKG = "com.pj.voicemap";
    static final String ACTION_TRIGGER = "com.pj.voicemap.TRIGGER";
    static final String ACTION_ACK = "com.pj.voicemap.ACK";
    static final String PREFS = "voicemap";

    static volatile Handler sMain;
    static ClassLoader sCl;
    static InputMethodService sService;
    static volatile String sActivePkg = "";
    // 默认【不】隐身：平时点输入框，键盘照常弹出可以手打。
    // 只在 cmd=down（按住说话）期间打开，cmd=up 时关掉并收起键盘。
    static volatile boolean sStealthEnabled = false;
    static volatile boolean sPressed;
    /** 按住/松手的序号：手动操作（stealthon/off、down）会让还没跑的隐式收尾作废 */
    static volatile int sHoldSeq;
    /** 每次 down 自增：看门狗据此判断这一轮是不是还该活着 */
    static volatile int sDownSeq;
    static volatile long sDownTime;
    static volatile float sDownX, sDownY;
    static volatile Object sKeyView;
    static volatile boolean sInPatch;
    static volatile boolean sCalibrating;
    /** 输入法进程里通过 WindowManager 额外加进来的窗口根视图（搜狗的语音浮层就在这里） */
    static final List<WeakReference<View>> sAddedRoots = new CopyOnWriteArrayList<WeakReference<View>>();

    static Handler main() {
        Handler h = sMain;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            sMain = h;
        }
        return h;
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        // 不写死输入法包名：任何进程都装钩子；只有真正跑 InputMethodService 的进程会用到，
        // 而隐身只在「当前输入目标是触发 App」时生效，别的 App 里零副作用。
        sCl = lp.classLoader;
        log("loaded into " + lp.packageName + "/" + lp.processName);
        try {
            hookIme(lp.classLoader);
        } catch (Throwable t) {
            log("hookIme failed: " + t);
        }
    }

    /* ------------------------------------------------------------------ hooks */

    static void hookIme(ClassLoader cl) throws Throwable {
        Class<?> ims = Class.forName("android.inputmethodservice.InputMethodService", false, cl);

        XposedBridge.hookAllMethods(ims, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                sService = (InputMethodService) p.thisObject;
                registerReceiver(sService);
                log("InputMethodService created, ime=" + sService.getPackageName());
            }
        });

        XposedBridge.hookAllMethods(ims, "onStartInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                EditorInfo ei = (EditorInfo) p.args[0];
                sActivePkg = ei == null || ei.packageName == null ? "" : ei.packageName.toString();
                log("onStartInput pkg=" + sActivePkg);
                applyWindow((InputMethodService) p.thisObject, "onStartInput");
            }
        });

        XposedBridge.hookAllMethods(ims, "onStartInputView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                EditorInfo ei = (EditorInfo) p.args[0];
                sActivePkg = ei == null || ei.packageName == null ? "" : ei.packageName.toString();
                log("onStartInputView pkg=" + sActivePkg);
                applyWindow((InputMethodService) p.thisObject, "onStartInputView");
            }
        });

        XposedBridge.hookAllMethods(ims, "showWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                applyWindow((InputMethodService) p.thisObject, "showWindow");
            }
        });

        XposedBridge.hookAllMethods(ims, "onWindowShown", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                applyWindow((InputMethodService) p.thisObject, "onWindowShown");
            }
        });

        XposedBridge.hookAllMethods(ims, "onFinishInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (armedPkg(sActivePkg)) {
                    sActivePkg = "";
                    applyWindow((InputMethodService) p.thisObject, "onFinishInput");
                    log("onFinishInput, stealth released");
                }
            }
        });

        XposedBridge.hookAllMethods(Window.class, "setAttributes", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (sInPatch || !stealthArmed()) {
                    return;
                }
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[0];
                if (lp != null && zeroOut(lp)) {
                    p.args[0] = lp;
                }
            }
        });

        Class<?> wmi = Class.forName("android.view.WindowManagerImpl", false, cl);
        XposedBridge.hookAllMethods(wmi, "addView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof View) {
                    sAddedRoots.add(new WeakReference<View>((View) p.args[0]));
                }
                if (!stealthArmed() || p.args.length < 2) {
                    return;
                }
                if (p.args[1] instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
                    if (zeroOut(lp)) {
                        p.args[1] = lp;
                    }
                }
            }
        });
    }

    static void registerReceiver(final Context ctx) {
        try {
            IntentFilter f = new IntentFilter(ACTION_TRIGGER);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(new Cmd(), f, 2 /* RECEIVER_EXPORTED */);
            } else {
                ctx.registerReceiver(new Cmd(), f);
            }
            log("receiver registered");
        } catch (Throwable t) {
            log("registerReceiver failed: " + t);
        }
    }

    /* ---------------------------------------------------------------- 白名单 */

    static SharedPreferences prefs() {
        return sService == null ? null : sService.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean armedPkg(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return false;
        }
        if (TRIGGER_PKG.equals(pkg)) {
            return true;
        }
        SharedPreferences sp = prefs();
        return sp != null && pkg.equals(sp.getString("arm_pkg", null));
    }

    static boolean stealthArmed() {
        // 只认"现在是不是按住说话的那一刻"，不再看输入目标是哪个包。
        //
        // 旧版靠 arm_pkg / TRIGGER_PKG 判断，实测太脆：arm_pkg 存在输入法进程自己的
        // SharedPreferences 里，输入法进程一重启、或者页面没重新加载导致 register 没发，
        // 判断就变成 false —— 按住说话时键盘照样弹出来。
        // 现在的语义是：sStealthEnabled 只在 cmd=down 期间为 true（设置页按住时先发
        // stealthon、松手发 up），所以"隐身中"等价于"正在按住说话"。
        return sStealthEnabled;
    }

    /* ---------------------------------------------------------------- stealth */

    static boolean zeroOut(WindowManager.LayoutParams lp) {
        boolean changed = false;
        if (lp.alpha != 0f) {
            lp.alpha = 0f;
            changed = true;
        }
        if (lp.dimAmount != 0f) {
            lp.dimAmount = 0f;
            changed = true;
        }
        if ((lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            changed = true;
        }
        if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            changed = true;
        }
        return changed;
    }

    static boolean restore(WindowManager.LayoutParams lp) {
        boolean changed = false;
        if (lp.alpha != 1f) {
            lp.alpha = 1f;
            changed = true;
        }
        if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            changed = true;
        }
        return changed;
    }


    /**
     * 松手之后收尾：先不动（输入法自己还在跑识别，窗口保持 alpha=0、不闪），
     * 3 秒后把键盘收起来，再等 400ms 把 alpha 还原成 1 ——
     * 于是「下一次手动点输入框」弹出的是正常键盘。
     * 期间如果又按住了（sPressed / sStealthEnabled 为真），本轮收尾直接放弃。
     */
    static void hideAfterHold() {
        final int seq = ++sHoldSeq;
        // 松手后先让隐身继续保持：输入法跑完识别会自己把键盘再弹一次（showWindow），
        // 这段时间必须仍然是透明的，否则屏幕上会闪一下键盘。
        main().postDelayed(new Runnable() {
            @Override public void run() {
                if (seq != sHoldSeq || sPressed) {
                    return;
                }
                final InputMethodService svc = sService;
                if (svc == null) {
                    return;
                }
                try {
                    svc.hideWindow();
                    log("hideAfterHold: hideWindow");
                } catch (Throwable t) {
                    log("hideAfterHold: hideWindow failed: " + t);
                    try { svc.requestHideSelf(0); } catch (Throwable ignore) { }
                }
                main().postDelayed(new Runnable() {
                    @Override public void run() {
                        if (seq != sHoldSeq) {
                            return;
                        }
                        sStealthEnabled = false;
                        applyWindow(sService, "restore after hold");
                    }
                }, 400);
            }
        }, 4000);
    }

    static java.lang.ref.WeakReference<Window> sZeroed = new java.lang.ref.WeakReference<Window>(null);

    static void applyWindow(InputMethodService svc, String why) {
        if (svc == null) {
            return;
        }
        try {
            Window w = svc.getWindow() == null ? null : svc.getWindow().getWindow();
            if (w == null) {
                return;
            }
            WindowManager.LayoutParams lp = w.getAttributes();
            boolean armed = stealthArmed();
            boolean changed;
            if (armed) {
                changed = zeroOut(lp);
                sZeroed = new java.lang.ref.WeakReference<Window>(w);
            } else {
                if (sZeroed.get() != w) {
                    return;
                }
                changed = restore(lp);
                if (!changed) {
                    sZeroed = new java.lang.ref.WeakReference<Window>(null);
                }
            }
            if (changed) {
                sInPatch = true;
                w.setAttributes(lp);
                sInPatch = false;
            }
            log((armed ? "stealth on (" : "stealth restored (") + why + ") alpha=" + lp.alpha
                    + " flags=0x" + Integer.toHexString(lp.flags));
        } catch (Throwable t) {
            sInPatch = false;
            log("applyWindow failed: " + t);
        }
    }

    /* ---------------------------------------------------------------- command */

    static class Cmd extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent in) {
            try {
                String cmd = in.getStringExtra("cmd");
                String strategy = in.getStringExtra("strategy");
                if (strategy == null) {
                    strategy = "touch";
                }
                log("cmd=" + cmd + " strategy=" + strategy);
                if ("register".equals(cmd)) {
                    String caller = in.getStringExtra("pkg");
                    SharedPreferences sp = prefs();
                    if (sp != null && caller != null) {
                        sp.edit().putString("arm_pkg", caller).apply();
                    }
                    ack(ctx, "registered " + caller);
                } else if ("dump".equals(cmd)) {
                    dump(ctx);
                } else if ("calibrate".equals(cmd)) {
                    calibrate(ctx);
                } else if ("clearcal".equals(cmd)) {
                    SharedPreferences sp = prefs();
                    if (sp != null) {
                        sp.edit().clear().apply();
                    }
                    ack(ctx, "calibration cleared");
                } else if ("stealthoff".equals(cmd)) {
                    sHoldSeq++;            // 用户要显形了，取消还没跑的收尾
                    sStealthEnabled = false;
                    applyWindow(sService, "cmd stealthoff");   // 立即恢复，不用等下一次 showWindow
                    ack(ctx, "stealth off");
                } else if ("stealthon".equals(cmd)) {
                    sHoldSeq++;
                    sStealthEnabled = true;
                    applyWindow(sService, "cmd stealthon");    // 立即透明
                    ack(ctx, "stealth on");
                } else if ("down".equals(cmd)) {
                    sHoldSeq++;            // 又按住了，取消上一轮的收尾
                    sStealthEnabled = true;
                    applyWindow(sService, "down");
                    start(ctx, in, strategy);
                } else if ("up".equals(cmd)) {
                    stop(ctx, strategy);
                    hideAfterHold();   // 隐身继续保持，4 秒后收键盘并恢复
                }
            } catch (Throwable t) {
                log("onReceive failed: " + t);
                ack(ctx, "ERR " + t);
            }
        }
    }

    static void start(final Context ctx, final Intent in, final String strategy) {
        if (sService == null) {
            ack(ctx, "ERR no service");
            return;
        }
        if (!sService.isInputViewShown()) {
            try {
                sService.requestShowSelf(0);
            } catch (Throwable t) {
                log("requestShowSelf: " + t);
            }
            main().postDelayed(new Runnable() {
                @Override
                public void run() {
                    start(ctx, in, strategy);
                }
            }, 220);
            return;
        }
        if ("method".equals(strategy)) {
            ack(ctx, callDel("X1") ? "down/method ok" : "down/method FAILED");
            return;
        }
        pressDown(ctx, in);
        // 看门狗：万一 UP 没送到（触摸被系统手势抢走、进程假死…）才强制松手。
        // ⚠️ 时间必须给足：用户要「说很长的话」，20 秒一到就把按住状态强行松掉，
        //    输入法那边等于"说到一半突然结束"，后半句全丢。3 分钟足够长，真丢了 UP
        //    也还有这条兜底（避免键盘永久隐身/按键永久按着）。
        final int seq = ++sDownSeq;
        main().postDelayed(new Runnable() {
            @Override public void run() {
                if (seq != sDownSeq || !sPressed) {
                    return;
                }
                log("watchdog: 按住超过 180s，强制松手");
                pressUp(sService);
                sStealthEnabled = false;
                applyWindow(sService, "watchdog");
            }
        }, 180000);
    }

    static void stop(Context ctx, String strategy) {
        if ("method".equals(strategy)) {
            callDel("v2");
            ack(ctx, "up/method ok");
            return;
        }
        pressUp(ctx);
    }

    static void pressDown(Context ctx, Intent in) {
        View kv = keyboardView();
        if (kv == null) {
            ack(ctx, "ERR no keyboard view");
            return;
        }
        float x, y;
        if (in.hasExtra("x") && in.hasExtra("y")) {
            x = in.getFloatExtra("x", 0f);
            y = in.getFloatExtra("y", 0f);
        } else {
            float[] c = resolvePoint(kv);
            if (c == null) {
                ack(ctx, "ERR no point");
                return;
            }
            x = c[0];
            y = c[1];
        }
        sKeyView = kv;
        sDownX = x;
        sDownY = y;
        sDownTime = SystemClock.uptimeMillis();
        boolean handled = kv.dispatchTouchEvent(motion(sDownTime, sDownTime, MotionEvent.ACTION_DOWN, x, y, 1f));
        sPressed = true;
        log("DOWN at (" + x + "," + y + ") handled=" + handled + " kv=" + kv.getClass().getName()
                + " " + kv.getWidth() + "x" + kv.getHeight());
        ack(ctx, "down/touch (" + (int) x + "," + (int) y + ")");
    }

    static void pressUp(Context ctx) {
        View kv = (View) sKeyView;
        if (kv == null || !sPressed) {
            ack(ctx, "ERR not pressed");
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean handled = kv.dispatchTouchEvent(motion(sDownTime, now, MotionEvent.ACTION_UP, sDownX, sDownY, 0f));
        sPressed = false;
        log("UP after " + (now - sDownTime) + "ms handled=" + handled);
        ack(ctx, "up/touch " + (now - sDownTime) + "ms");
    }

    static MotionEvent motion(long downTime, long t, int action, float x, float y, float pressure) {
        MotionEvent ev = MotionEvent.obtain(downTime, t, action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return ev;
    }

    static void ack(Context ctx, String msg) {
        log("ack: " + msg);
        try {
            Intent i = new Intent(ACTION_ACK);
            String arm = prefs() == null ? null : prefs().getString("arm_pkg", null);
            if (arm == null) {
                arm = TRIGGER_PKG;
            }
            i.setPackage(arm);
            i.putExtra("msg", msg);
            ctx.sendBroadcast(i);
        } catch (Throwable ignore) {
        }
    }

    /* ---------------------------------------------------------- 空格键坐标 */

    static String pointKey(View kv) {
        return "pt_" + kv.getWidth() + "x" + kv.getHeight();
    }

    static float[] savedPoint(View kv) {
        SharedPreferences sp = prefs();
        if (sp == null) {
            return null;
        }
        String v = sp.getString(pointKey(kv), null);
        if (v == null) {
            return null;
        }
        try {
            String[] p = v.split(",");
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1])};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 通用候选点。「底行居中」这条规则几乎所有输入法都成立（空格条在键盘最下一行、水平居中），
     * 再给几个上下/左右偏移，由 cmd=calibrate 逐个试并按「是否真的开始录音」判定。
     */
    static float[][] candidates(View kv) {
        float w = kv.getWidth();
        float h = kv.getHeight();
        return new float[][]{
                {w * 0.50f, h * 0.8703f},
                {w * 0.50f, h * 0.8000f},
                {w * 0.50f, h * 0.9300f},
                {w * 0.35f, h * 0.8703f},
                {w * 0.65f, h * 0.8703f},
                {w * 0.50f, h * 0.7500f},
        };
    }

    static float[] resolvePoint(View kv) {
        float[] saved = savedPoint(kv);
        if (saved != null) {
            return saved;
        }
        return candidates(kv)[0];
    }

    /* ------------------------------------------------------------ 自动校准 */

    static void calibrate(final Context ctx) {
        if (sService == null) {
            ack(ctx, "ERR no service");
            return;
        }
        if (!sService.isInputViewShown()) {
            try {
                sService.requestShowSelf(0);
            } catch (Throwable ignore) {
            }
            main().postDelayed(new Runnable() {
                @Override
                public void run() {
                    calibrate(ctx);
                }
            }, 260);
            return;
        }
        final View kv = keyboardView();
        if (kv == null) {
            ack(ctx, "ERR no keyboard view");
            return;
        }
        if (sCalibrating) {
            ack(ctx, "calibrating...");
            return;
        }
        sCalibrating = true;
        final float[][] pts = candidates(kv);
        final int[] idx = {0};

        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            @Override
            public void run() {
                if (idx[0] >= pts.length) {
                    sCalibrating = false;
                    ack(ctx, "calibrate FAILED");
                    return;
                }
                final float x = pts[idx[0]][0];
                final float y = pts[idx[0]][1];
                idx[0]++;
                final Set<String> before = classSet(kv);
                final long down = SystemClock.uptimeMillis();
                kv.dispatchTouchEvent(motion(down, down, MotionEvent.ACTION_DOWN, x, y, 1f));
                log("calib probe (" + x + "," + y + ")");
                main().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        boolean hit = voiceRunning(kv, before);
                        long up = SystemClock.uptimeMillis();
                        kv.dispatchTouchEvent(motion(down, up, MotionEvent.ACTION_UP, x, y, 0f));
                        log("calib probe (" + x + "," + y + ") hit=" + hit);
                        if (hit) {
                            SharedPreferences sp = prefs();
                            if (sp != null) {
                                sp.edit().putString(pointKey(kv), x + "," + y).apply();
                            }
                            sCalibrating = false;
                            ack(ctx, "calibrated " + (int) x + "," + (int) y);
                        } else {
                            main().postDelayed(step[0], 350);
                        }
                    }
                }, 800);
            }
        };
        step[0].run();
    }

    /**
     * 判定输入法的语音界面此刻是否真的起来了。三个彼此独立的信号，任一命中即算：
     *   1) 本进程开始录音（AudioManager.getActiveRecordingConfigurations 里有自己的 uid）；
     *   2) 输入法窗口树里出现了「语音/麦克风」类视图并且可见；
     *   3) 输入法进程新加了一个窗口（很多输入法的语音浮层是独立窗口）。
     */
    static boolean voiceRunning(View kv, Set<String> before) {
        try {
            AudioManager am = (AudioManager) sService.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                List<AudioRecordingConfiguration> list = am.getActiveRecordingConfigurations();
                StringBuilder sb = new StringBuilder();
                for (AudioRecordingConfiguration c : list) {
                    int uid = -1;
                    try {
                        Object r = c.getClass().getMethod("getClientUid").invoke(c);
                        if (r instanceof Integer) {
                            uid = (Integer) r;
                        }
                    } catch (Throwable ignore) {
                    }
                    sb.append(uid).append(' ');
                    if (uid == Process.myUid()) {
                        log("voice detected: recording by uid " + uid);
                        return true;
                    }
                }
                log("recordings during probe: [" + sb.toString().trim() + "]");
            }
        } catch (Throwable t) {
            log("recording query failed: " + t);
        }

        for (View root : allRoots(kv)) {
            if (voiceUiInside(root)) {
                log("voice detected: voice view visible");
                return true;
            }
        }
        return false;
    }

    static List<View> allRoots(View kv) {
        List<View> roots = new java.util.ArrayList<View>();
        if (kv != null) {
            roots.add(kv);
        }
        try {
            if (sService != null && sService.getWindow() != null && sService.getWindow().getWindow() != null) {
                roots.add(sService.getWindow().getWindow().getDecorView());
            }
        } catch (Throwable ignore) {
        }
        for (WeakReference<View> ref : sAddedRoots) {
            View v = ref.get();
            if (v != null) {
                roots.add(v);
            }
        }
        return roots;
    }

    /** 树里是否有「语音类」视图正在可见显示。 */
    static boolean voiceUiInside(View v) {
        if (v == null) {
            return false;
        }
        String n = v.getClass().getName().toLowerCase(Locale.ROOT);
        boolean named = n.contains("voice") || n.contains("speech") || n.contains("mic")
                || n.contains("record") || n.contains("asr") || n.contains("audio");
        if (named && v.isShown()) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (voiceUiInside(g.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    static Set<String> classSet(View v) {
        Set<String> out = new HashSet<String>();
        collect(v, out);
        return out;
    }

    static void collect(View v, Set<String> out) {
        out.add(v.getClass().getName());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    /* --------------------------------------------------------------- reflect */

    static Object imeDel() {
        if (sService == null) {
            return null;
        }
        try {
            Class<?> c = Class.forName("com.sohu.inputmethod.sogou.MainImeServiceDel", true, sCl);
            Method m = c.getMethod("getInstance");
            return m.invoke(null);
        } catch (Throwable t) {
            log("imeDel: " + t);
            return null;
        }
    }

    static boolean callDel(String name) {
        Object del = imeDel();
        if (del == null) {
            return false;
        }
        try {
            for (Class<?> c = del.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        log("called " + name + " -> " + m.invoke(del));
                        return true;
                    }
                }
            }
            log("method not found: " + name);
        } catch (Throwable t) {
            log("callDel(" + name + ") failed: " + t);
        }
        return false;
    }

    /**
     * 找输入法的键盘视图：
     *  1) 搜狗精准路径：MainImeServiceDel 里类型为 SogouKeyboardView 的字段；
     *  2) 通用路径：输入法窗口 decor 树里面积最大的可见子 View（候选栏在顶部、键盘占大半）。
     */
    static View keyboardView() {
        Object del = imeDel();
        if (del != null) {
            for (Class<?> c = del.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    String tn = f.getType().getName();
                    if (tn.endsWith(".SogouKeyboardView") || tn.endsWith(".SKeyboardView")) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(del);
                            if (v instanceof View) {
                                return (View) v;
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
        }
        return genericKeyboardView();
    }

    static View genericKeyboardView() {
        if (sService == null || sService.getWindow() == null || sService.getWindow().getWindow() == null) {
            return null;
        }
        View decor = sService.getWindow().getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return decor;
        }
        ViewGroup root = (ViewGroup) decor;
        View best = null;
        int bestArea = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            int area = child.getWidth() * child.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = child;
            }
        }
        return best == null ? decor : best;
    }

    /* ------------------------------------------------------------------ dump */

    static void dump(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== VoiceMap dump ===\n");
        sb.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" ime=").append(sService == null ? "?" : sService.getPackageName())
                .append(" activePkg=").append(sActivePkg)
                .append(" armed=").append(stealthArmed()).append('\n');
        View kv = keyboardView();
        sb.append("keyboardView=").append(kv == null ? "null" : kv.getClass().getName()).append('\n');
        if (kv != null) {
            int[] loc = new int[2];
            kv.getLocationOnScreen(loc);
            sb.append("kv size=").append(kv.getWidth()).append('x').append(kv.getHeight())
                    .append(" onScreen=").append(loc[0]).append(',').append(loc[1]).append('\n');
            float[] saved = savedPoint(kv);
            sb.append("pointsKey=").append(pointKey(kv)).append(" saved=")
                    .append(saved == null ? "none" : java.util.Arrays.toString(saved)).append('\n');
            sb.append("--- tree ---\n");
            tree(kv, 0, sb);
        }
        if (sService != null && sService.getWindow() != null && sService.getWindow().getWindow() != null) {
            WindowManager.LayoutParams lp = sService.getWindow().getWindow().getAttributes();
            sb.append("imeWindow alpha=").append(lp.alpha).append(" flags=0x")
                    .append(Integer.toHexString(lp.flags)).append(" type=").append(lp.type).append('\n');
        }
        String s = sb.toString();
        log(s);
        write(ctx, s);
        ack(ctx, "dump written");
    }

    static void tree(View v, int depth, StringBuilder sb) {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            pad.append("  ");
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(pad).append(v.getClass().getName())
                .append(" [").append(loc[0]).append(',').append(loc[1]).append(' ')
                .append(v.getWidth()).append('x').append(v.getHeight()).append(']')
                .append(" vis=").append(v.getVisibility())
                .append(" id=").append(v.getId())
                .append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                tree(g.getChildAt(i), depth + 1, sb);
            }
        }
    }

    static void write(Context ctx, String s) {
        FileOutputStream fos = null;
        try {
            File f = new File(ctx.getCacheDir(), "voicemap_dump.txt");
            fos = new FileOutputStream(f, false);
            fos.write(s.getBytes("UTF-8"));
            fos.flush();
            log("dump -> " + f.getAbsolutePath());
        } catch (Throwable t) {
            log("write dump failed: " + t);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }
}
