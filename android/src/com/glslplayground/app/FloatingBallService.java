package com.glslplayground.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.GLSurfaceView;
import android.graphics.Color;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Paths;

/** 悬浮水晶球：TYPE_APPLICATION_OVERLAY 悬浮窗 + MediaProjection 抓屏 + GLSurfaceView 渲染。 */
public class FloatingBallService extends Service {

    public static volatile boolean running = false;
    private static volatile FloatingBallService INSTANCE;

    /**
     * 球现在是不是「展开」状态 —— 搜索胶囊 / 输入胶囊 / 六区 任一开着。
     * 长按电源键第二次靠它判断：展开着就收球（{@link #hideFromOutside()}），
     * 还是光秃秃一颗球就走「回桌面 + 居中」。
     */
    public static boolean isExpanded() {
        FloatingBallService s = INSTANCE;
        if (s == null) return false;
        try {
            if (s.searchActive || s.searchClosing || s.sqPhase != 0) return true;   // 搜索胶囊
            if (s.searchBar != null && s.searchBar.isShown()) return true;
            if (s.convoBar != null && s.convoBar.isShown()) return true;           // 输入胶囊
            if (s.menuShown || s.glassFullscreen) return true;                     // 卡片菜单 / 整屏玻璃
            return s.zonesActive;                                                  // 六区
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从外面（电源键那条广播）把球收掉：主线程 stopSelf → onDestroy 里摘窗口、停采集。 */
    public static void hideFromOutside() {
        final FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                try { s.stopSelf(); } catch (Throwable ignored) { }
            }
        });
    }

    public static void updateParams(String json) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.applyParams(json);
    }

    public static void setMode(int mode) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.applyMode(mode);
    }

    private void applyMode(final int mode) {
        if (glView == null || renderer == null) return;
        glView.queueEvent(new Runnable() {
            public void run() { renderer.setBgMode(mode); }
        });
    }

    private static final String CHANNEL = "float_ball";
    private static final int NOTIF_ID = 42;
    private static final int REQ_OPEN = 1;

    private WindowManager wm;
    private GLSurfaceView glView;

    // 思考中「胶囊加载」：透明 WebView 叠在玻璃球上面（同窗口位置/尺寸），只画白点。
    private WebView thinkLoader;
    private WindowManager.LayoutParams thinkLoaderParams;
    private boolean thinkLoaderPageReady, thinkLoaderVisible;
    private int thinkSentMode = -1;
    private float thinkSentSpeed = -1f;
    private float thinkLastNx = -9f, thinkLastNy = -9f, thinkLastNrx = -9f, thinkLastNry = -9f;
    private GlassRenderer renderer;
    private WindowManager.LayoutParams params;

    private View ballTouch;                                  // 唯一接用户手势的窗口（玻璃层只画不摸）
    private WindowManager.LayoutParams ballTouchParams;
    private File gateDir;                                    // App 可写目录（文件调试钩子用）

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private Surface captureSurface;
    private SurfaceTexture captureTexture;
    private RootCapture rootCapture;

    // ── 抓帧看门狗 ──
    // 抓帧是"按需启动一次"的子进程（su screencap 或被包成常驻采集器的 app_process）。
    // 它可能因为重装 APK / 系统回收 / su 授权被拒而死掉，而那时 **没有任何人重启它** ——
    // 结果 GL 一直用最后一帧当折射源：球里显示的永远是启动那一刻的画面（上半截恒黑、
    // 下半截恒亮，且对背景变化毫无反应）。用户报的"只有下半部分有折射"就是这个。
    // 这里每 2 秒检查一次，帧停了就重启采集。
    private static final long CAP_WATCHDOG_MS = 2000;
    private static final long CAP_STALE_MS = 2500;
    private final Runnable capWatchdog = new Runnable() {
        @Override public void run() {
            boolean rootMode = renderer != null && renderer.captureSource == 1;
            if (rootMode) {
                long now = System.currentTimeMillis();
                long last = renderer.lastFrameAtMs;
                boolean stale = last > 0 && now - last > CAP_STALE_MS;
                // 关键：首帧一直没来（su 被拒 / 管道断了）以前是"永远不重启" →
                // 球其实一帧都没画出来，窗口却照样吃触摸，屏幕中间就成了一块看不见的死区。
                boolean never = last == 0 && surfaceReadyAtMs > 0
                        && now - surfaceReadyAtMs > 4000;
                if (stale || never) {
                    Log.w("FloatingBall", never ? "抓帧从未到达 → 重启采集"
                            : "抓帧已停 " + (now - last) + "ms → 重启采集");
                    try { if (rootCapture != null) rootCapture.stop(); } catch (Throwable ignored) { }
                    rootCapture = null;
                    startRootCapture();
                }
            }
            applyBallTouchability();   // 球看不见 / 看见了 → 代理窗口的触摸能力跟着变
            startPointerDaemon();      // 定向注入守护进程（死了自动拉起）
            // 兜底：surface 重建过（隐藏→恢复）就把抓屏排除补回去。判据是 SurfaceControl 身份，
            // 不是"打过一次就永远算打过" —— 见 ensureGlassSkipScreenshot() 里的说明。
            ensureGlassSkipScreenshot();
            if (!surfaceTrusted) markSurfaceTrusted(glView);   // surface 建好后补打受信任标志
            main.postDelayed(this, CAP_WATCHDOG_MS);
        }
    };

    /** 启动 root 直采（幂等；低延迟偏好时用常驻采集器）。 */
    private void startRootCapture() {
        if (rootCapture == null) rootCapture = new RootCapture();
        rootCapture.setTargetFps(targetFps);
        int cw = screenW, ch = screenH;
        if (prefs().getBoolean("float_lowlat", false)) {
            int scale = Math.max(1, prefs().getInt("float_cap_scale", 2));
            int tw = Math.max(2, screenW / scale), th = Math.max(2, screenH / scale);
            String jar = ensureCapJar();
            if (jar != null) {
                rootCapture.useAppProcess(jar, tw, th);
                cw = tw; ch = th;
            }
        }
        renderer.capW = cw;
        renderer.capH = ch;
        rootCapture.start(renderer);
    }
    private String ensureCapJar() {
        try {
            File f = new File(getFilesDir(), "caplive.jar");
            if (!f.exists() || f.length() == 0) {
                InputStream in = getAssets().open("caplive.jar");
                FileOutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                f.setReadable(true, false);
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Log.w("FloatingBall", "ensureCapJar failed: " + t);
            return null;
        }
    }

    private String rootError;

    // ── 定向注入守护进程：AI 的点击/滑动直接投给目标 App，球完全无感 ────────────────
    //
    // 澎湃/HyperOS 上 `InputManager.injectInputEvent(event, mode, targetUid)` 只把事件投给
    // 目标 uid 的窗口：球（另一个 uid 的悬浮窗）连事件都收不到，于是**不用让开、不用隐藏、
    // 不用闪烁**，玻璃"无事发生"，下面的 App 照常被操控（真机已验）。
    // 守护进程由本 App 用 su 拉起（assets/pointer.jar），死了自动重启。
    private Process pointerProc;
    private volatile boolean pointerAlive = false;
    private long pointerRetryAt = 0;

    private String ensureAssetJar(String name) {
        try {
            File f = new File(getFilesDir(), name);
            // 每次都重写：APK 升级后旧 jar 还躺在私有目录里，'存在就跳过'会一直跑老代码
            InputStream in = getAssets().open(name);
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            f.setReadable(true, false);
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Log.w("FloatingBall", "ensureAssetJar(" + name + ") failed: " + t);
            return null;
        }
    }


    /**
     * 把某个 SurfaceView 的**子 surface** 标成受信任 overlay
     * （`SurfaceControl.Transaction#setTrustedOverlay(sc, true)`，@hide）。
     * 成功之后 InputDispatcher 的"不受信任遮挡"判定会跳过它 —— 球可以原地不动。
     */
    private void markSurfaceTrusted(View v) {
        try {
            if (!new File(gateDir(), "trusted-surface").exists()) return;
        } catch (Throwable ignored) { return; }
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object sc = Class.forName("android.view.SurfaceView")
                    .getMethod("getSurfaceControl").invoke(v);
            if (sc == null) { Log.w("FloatingBall", "markSurfaceTrusted: sc=null"); return; }
            Object tx = txCls.getConstructor().newInstance();
            txCls.getMethod("setTrustedOverlay", scCls, boolean.class).invoke(tx, sc, true);
            txCls.getMethod("apply").invoke(tx);
            surfaceTrusted = true;
            Log.i("FloatingBall", "SurfaceView 子 surface 已标为受信任 overlay");
        } catch (Throwable t) {
            Log.w("FloatingBall", "markSurfaceTrusted failed: " + t
                    + (t.getCause() != null ? " / " + t.getCause() : ""));
        }
    }

    /** 拉起（或重启）定向注入守护进程。 */
    private void startPointerDaemon() {
        if (pointerAlive) return;
        // 默认不启用：实测"定向注入"也躲不过跨 App 的遮挡拦截（球在场时一样被丢），
        // 留在代码里只是给"只想让球收不到事件"的场景用。放 files/pointer-daemon 才开。
        try {
            if (!new File(gateDir(), "pointer-daemon").exists()) return;
        } catch (Throwable ignored) { return; }
        long now = System.currentTimeMillis();
        if (now < pointerRetryAt) return;
        pointerRetryAt = now + 5000;
        try {
            String jar = ensureAssetJar("pointer.jar");
            if (jar == null) return;
            String cmd = "CLASSPATH=" + jar + " /system/bin/app_process /system/bin PointerDaemon 38722";
            final Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            pointerProc = p;
            pointerAlive = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            Log.i("FloatingBall", "pointer: " + line);
                        }
                    } catch (Throwable ignored) { }
                    pointerAlive = false;
                    pointerProc = null;
                    Log.w("FloatingBall", "pointer daemon exited");
                }
            }, "pointer-watch").start();
            Log.i("FloatingBall", "pointer daemon started");
        } catch (Throwable t) {
            pointerAlive = false;
            Log.w("FloatingBall", "startPointerDaemon failed: " + t);
        }
    }

    // ── agent 触摸门控（DSH 注入触摸的那一下，球让开）────────────────────────────
    //
    // DSH 的 mobile 工具用 `input` 注入点击/滑动，走的是和真手指**同一套**窗口命中测试。
    // 球常驻屏幕中间，注入的 DOWN 落在球上就被球吃掉（底下 App 收不到、球还会被拖着走）。
    //
    // 协议（只覆盖"那一下"，不做整段让路）：
    //   1. DSH 动手前把 nonce 写进 ACT → 球立刻把代理触摸窗口挪走（NOT_TOUCHABLE + 1×1 到屏外），回 ACK；
    //   2. DSH 注入完这次点击/滑动后，把同一个 nonce 写进 DONE → 球**立刻**把触摸区还原；
    //   3. 兜底：最多 1200ms 没等到 DONE 也自动还原（插件崩了/被杀也不会让球一直点不动）。
    private static final String F_ACT = "dsh-ball-act";
    private static final String F_ACK = "dsh-ball-ack";
    private static final String F_DONE = "dsh-ball-done";
    private static final long AGENT_MAX_MS = 1200;
    private static final long GATE_ACT_TTL_MS = 10000;   // 超过 10s 的 ACT 当残留，不认
    private static final long GATE_POLL_MS = 40;
    private volatile boolean agentBlind = false;   // 正在让开（只在这一下触摸注入期间）
    private long blindUntilMs = 0;
    private String lastActNonce = "";
    private String lastAckNonce = "";
    private long lastAckAt = 0;
    private long lastDiagAt = 0;
    private boolean proxyParked = false;           // 代理窗口已被缩成 1×1 挪到屏外
    private boolean lastActWasDrag = false;   // 这一下是“拖”还是“点”（拖才需要整窗让开）
    private long blindStartMs = 0;                 // 让开动作开始的时间（ACK 要等窗口稳定再回）
    private boolean glassParked = false;           // 玻璃窗也被挪到屏外（注入那一刻，避免遮挡判定）
    private int glassSavedX = 0, glassSavedY = 0;
    private boolean lastDrawn = false;             // 上一次判定"球画出来了没有"（诊断用）
    private boolean lastBlindApplied = false;      // 上一次真正下发的"别接触摸"状态
    private long lastProxySyncAt = 0;              // 上一次对账代理窗口几何
    private long surfaceReadyAtMs = 0;             // GL surface 就绪时刻（抓帧看门狗用它判"首帧一直没来"）

    // ── 触摸来源判定：这一次触摸是「人」还是「机器」──────────────────────────────
    // 注入的触摸（input / uiautomator / InputManager.injectInputEvent / vd 自己的注入器）
    // 带的 deviceId 不是任何真实触摸屏的 id。把真实触摸屏的白名单记下来，不在表里的一律当"机器"：
    //   · 不执行任何用户手势（不拖球、不进状态 3、不弹搜索框）—— 以前 agent 一点，球就跑了；
    //   · 立刻自动让开 SYNTHETIC_DUCK_MS —— 门控全挂了也能自愈，下一拍就能穿过去。
    private final java.util.HashSet<Integer> realTouchDevices = new java.util.HashSet<>();
    private volatile long syntheticDuckUntil = 0;
    private volatile int swallowedCount = 0;       // 机器人的触摸落在球上、被兜住的累计次数
    private volatile long lastSyntheticAt = 0;
    private static final long SYNTHETIC_DUCK_MS = 1500;

    // ── 「让开」的新实现：改可触摸区域（Region），窗口一个像素都不动 ──────────────
    // 老办法是把窗口缩成 1×1 挪到屏外再挪回来：每次点击 8~12 次 updateViewLayout，
    // 会重建缓冲（玻璃发白）、还会抖。改 Region 只走一次 traversal，肉眼看不到。
    private volatile boolean blindNow = false;      // 注入那一刻：本 App **所有**窗口退出命中测试
    private volatile boolean ballBlindNow = false;  // 只有球自己的触摸层退出（搜索框/菜单这些状态）
    private volatile boolean surfaceTrusted = false;
    private boolean regionMode = false;            // 反射挂 InternalInsets 监听成功（能改 Region）
    private boolean a11yOverlayBroken = false;     // 受信任 overlay 被系统拒过（退回普通悬浮窗）
    private final java.util.Set<View> regionInstalled =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<View, Boolean>());
    private long lastRectAt = 0;

    private int screenW, screenH, winSize, winW, winH;
    private int resultCode;
    private Intent resultData;

    private final Handler main = new Handler(Looper.getMainLooper());

    // ── 帧率控制：渲染与抓屏都按 targetFps 节流（1~144）──
    private int targetFps = 60;

    public static void setFps(int fps) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFps(fps));
    }

    private void applyFps(int fps) {
        targetFps = Math.max(1, Math.min(144, fps));
        if (rootCapture != null) rootCapture.setTargetFps(targetFps);
        if (glView != null) {
            main.removeCallbacks(renderTick);
            main.post(renderTick);
        }
    }

    // ── 独立渲染节拍 ──────────────────────────────────────────────────────
    // 为什么不能只靠主线程的 postDelayed：方形态里那个**大字块**（上千像素高、几十行）
    // 每次重排/重绘都在主线程上，一打字就把主线程占住，renderTick 的 33ms 节拍被拖到 ~50ms
    // —— 实测 GPU 每帧只要 12~17ms，卡的是"节拍"不是"渲染"。
    // GLSurfaceView.requestRender() 本身是线程安全的，所以这里用一个专用线程按目标帧率敲门，
    // 主线程照旧只管几何/文字/窗口；这样即使主线程忙，球的动画也是稳的。
    private volatile boolean renderDriving = false;
    private Thread renderDriver;

    private void startRenderDriver() {
        if (renderDriving) return;
        renderDriving = true;
        renderDriver = new Thread(new Runnable() {
            @Override public void run() {
                while (renderDriving) {
                    GLSurfaceView v = glView;
                    try { if (v != null) v.requestRender(); } catch (Throwable ignored) { }
                    try { Thread.sleep(Math.max(1, 1000 / Math.max(1, targetFps))); }
                    catch (InterruptedException e) { return; }
                }
            }
        }, "ball-render-driver");
        renderDriver.setDaemon(true);
        renderDriver.start();
        Log.i("FloatingBall", "render driver 启动（独立节拍，目标 " + targetFps + "fps）");
    }

    private void stopRenderDriver() {
        renderDriving = false;
        Thread t = renderDriver;
        renderDriver = null;
        if (t != null) t.interrupt();
    }

    private final Runnable renderTick = new Runnable() {
        @Override public void run() {
            if (glView == null || targetFps <= 0) return;
            pumpScene();                  // 状态槽 → 动画（每个状态各绑一套，设置页可改）
            pumpOrbMix();                 // 波形交叉淡化按渲染节拍推进（状态切换时不再瞬间换脸）
            pumpWaveGate();               // 幅度包络：换形态前先淡出、换完再淡入
            pumpTextHoldSafety();         // 几何保持的兜底释放
            pumpShotRequest();            // 外部（调试脚本）请求一帧球体截图
            pumpStateRequest();           // 外部（调试脚本）喂一个助手状态，回归状态机用
            pumpSceneFiles();             // 外部（调试脚本）预览槽 / 改绑定，回归动画总控用
            pumpApprovalDebug();          // 外部（调试脚本）喂一条假审批，把审批球叫出来
            pumpSearchRequest();          // 外部（调试脚本）开关搜索胶囊 / 喂一句要发的话
            pumpGestureRequest();         // 外部（调试脚本）驱动三态手势机的每一步
            // requestRender 交给 ball-render-driver 线程（见上），这里只推进各种包络
            main.removeCallbacks(this);
            main.postDelayed(this, Math.max(1, 1000 / targetFps));
        }
    };

    // ── 球体截图的外部触发：`touch /data/local/tmp/ballshot` 即可让球存一帧到 files/ball-capture.png ──
    // 调「思考中」那类短暂状态时，光靠手点设置页按钮很难卡在正确时刻；
    // 有这个口子就能：先 touch 文件 → 再让助手进入该状态 → 球自己把那一帧存下来。
    private long nextShotCheck = 0;
    // 目录必须是**球自己能删**的地方：/data/local/tmp 是 shell_data_file 标签，
    // untrusted_app 只能读不能删 —— 删不掉就 return，文字/截图请求等于没生效（踩过）。
    private static final String SHOT_REQ =
            "/sdcard/Android/data/com.glslplayground.app/files/ballshot";

    // ── 状态回放的调试口子：`touch /data/local/tmp/ballstate-THINKING` 让球进入该状态 ──
    // 为什么要它：状态抖动（THINKING↔IDLE 反复）只在一个回合里出现，靠真人对话很难复现，
    // 也不方便断言"IDLE 有没有被压后"。有了这个口子就能脚本化喂一串状态，然后看日志/截图。
    // 改动动画状态机时用它回归，别删。状态取值：IDLE / THINKING / ANSWERING / OFF。
    private long nextStateCheck = 0;
    // 目录必须是**球自己能写**的地方：/data/local/tmp 是 shell_data_file 标签，
    // untrusted_app 只能读不能删（踩过：触发文件删不掉 → 每 120ms 被反复消费）。
    private static final String STATE_REQ_PREFIX = "/sdcard/Android/data/com.glslplayground.app/files/state-";
    private static final String[] STATE_NAMES =
            {"IDLE", "THINKING", "ANSWERING", "OFF"};
    // 另外两个只给「方形态出波」这条链路用的调试口子（一次性文件，用完即删）：
    //   state-TURNEND   → 等价于收到 turn/end（球里有字时会在球底出一道进了玻璃的波）
    //   state-TURNSTART → 等价于新一轮开始（把闸门关回去）
    // 为什么要它：turn/end 只有真跑完一整轮 DSH 才会有，回归「最终结果才出波」时
    // 不可能每次都等一次完整对话。
    /** 没有 files/md-sample.md 时的内置预览样例（表格/列表/代码块齐全）。 */
    private static final String MD_PREVIEW_FALLBACK =
            "# 排版预览\n\n球内正文 = 「Siri 字号」×「球内回复字号比例」。\n\n"
            + "| 方案 | 成本 | 说明 |\n|---|---|---|\n"
            + "| 离线预览 | 0 | 零依赖 |\n| 会话镜像 | 1 条会话 | 跟当前 DSH 会话 |\n\n"
            + "1. 第一步：拖字号滑块\n2. 第二步：看球里的变化\n   - 嵌套一层\n\n"
            + "> 引用：黑区下沿按文字实际高度算。\n\n"
            + "```bash\necho 0.25 > files/md-ratio\n```\n\n"
            + "最后一段普通文字，验证换行与滚动。";

    private static final String[] STATE_EXTRA =
            {"TURNEND", "TURNSTART", "FLUID",
             // 离线预览：`touch files/state-PREVIEW` 灌示例并钉住；`state-PREVIEWOFF` 结束。
             "PREVIEW", "PREVIEWOFF", "SETTLED"};

    private void pumpStateRequest() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextStateCheck) return;
        nextStateCheck = now + 120;
        for (final String name : STATE_EXTRA) {
            java.io.File f = new java.io.File(STATE_REQ_PREFIX + name);
            if (!f.exists()) continue;
            f.delete();
            Log.i("FloatingBall", "state extra (debug): " + name);
            if ("PREVIEW".equals(name)) {
                String smp = readPreviewSample();
                previewText(smp != null ? smp : MD_PREVIEW_FALLBACK);
            } else if ("PREVIEWOFF".equals(name)) {
                previewStop();
            } else if ("FLUID".equals(name)) {
                applyFluidDebugFile();
            } else if ("SETTLED".equals(name)) {
                SiriAssistant a = SiriAssistant.peek();
                if (a != null) a.debugMarkTurnSettled();
            } else {
                applyTurnEnded("TURNEND".equals(name));
            }
            return;
        }
        for (final String name : STATE_NAMES) {
            java.io.File f = new java.io.File(STATE_REQ_PREFIX + name);
            if (!f.exists()) continue;
            f.delete();
            Log.i("FloatingBall", "state request (debug): " + name);
            setAssistantState(name);
            return;
        }
    }

    // ── 动画总控的回归钩子（改动画状态机时用它，别删）──────────────────────
    //   touch files/scene-<槽>       → 预览该槽（idle/listen/thinking/executing/
    //                                  reply_stream/reply_final/approval/search/zones）
    //   touch files/sceneend         → 结束预览
    //   touch files/bind-<槽>=<动画> → 直接把某个槽绑到某个动画（wave/dots/fluid/cap1..9/off）
    // 为什么要文件口子：状态只在一轮对话里出现，靠手点设置页很难卡在正确时刻；
    // 有了它就能脚本化地「绑动画 → 触发状态 → 拍一帧 → 比对」。
    private long nextSceneFileCheck = 0;

    private void pumpSceneFiles() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextSceneFileCheck) return;
        nextSceneFileCheck = now + 250;
        java.io.File dir = new java.io.File(STATE_REQ_PREFIX).getParentFile();
        if (dir == null) return;
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            String n = f.getName();
            if ("sceneend".equals(n)) {
                if (!f.delete()) return;
                Log.i("FloatingBall", "scene debug: 结束预览");
                setScenePreview("");
                return;
            }
            if (n.startsWith("scene-")) {
                if (!f.delete()) return;
                String slot = n.substring(6);
                Log.i("FloatingBall", "scene debug: 预览 " + slot);
                setScenePreview(slot);
                return;
            }
            if (n.startsWith("bind-")) {
                int eq = n.indexOf('=', 5);
                if (eq < 0) continue;
                if (!f.delete()) return;
                String slot = n.substring(5, eq);
                String anim = n.substring(eq + 1);
                Log.i("FloatingBall", "scene debug: 绑定 " + slot + " = " + anim);
                if (AnimScene.A_OFF.equals(anim) || AnimScene.isCap(anim)
                        || AnimScene.A_WAVE.equals(anim) || AnimScene.A_DOTS.equals(anim)
                        || AnimScene.A_FLUID.equals(anim)) {
                    animPrefs().edit().putString("bind." + slot, anim).apply();
                }
                sceneDirty = true;
                refreshScene(true);
                return;
            }
        }
    }

    private void pumpShotRequest() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextShotCheck) return;
        nextShotCheck = now + 200;
        java.io.File f = new java.io.File(SHOT_REQ);
        if (!f.exists()) return;
        // 文件里写了内容 → 把它当成「要显示的球内文字」（调试长文本溢出用），否则只截一帧
        String payload = null;
        try {
            if (f.length() > 0) {
                byte[] buf = new byte[(int) Math.min(f.length(), 64 * 1024)];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = in.read(buf);
                in.close();
                if (n > 0) payload = new String(buf, 0, n, "UTF-8").trim();
            }
        } catch (Throwable ignored) { }
        if (!f.delete() && f.exists()) return;
        // 顺带支持参数热重载：改了 float_params 后碰一下这个钩子即可生效，不必重装 APK。
        // ⚠️ 注意：**不能在这里 return** —— 否则首次碰钩子时会跳过截图，
        // 读到的就是上一次的旧 png（排查时被这个骗过好几轮）。
        pumpParamsReload();
        if (payload != null && !payload.isEmpty()) {
            final String text = payload;
            Log.i("FloatingBall", "shot request text len=" + text.length());
            main.post(new Runnable() {
                @Override public void run() { applySiriText(text); }
            });
        }
        try { captureBallPngNow(); } catch (Throwable t) { Log.w("FloatingBall", "shot request failed: " + t); }
    }

    // ── DSH 文本助手（安卓前端 ↔ DSH）：球体作为状态显示器 ──────────────
    // 助手状态 → 光效形态（思考=流体圆点，回答=文字）

    /** 短振动（双指长按唤起 / 长按取消 都用它，别各自 new 一个 Vibrator）。 */
    private void vibrate(int ms) {
        try {
            android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(ms);
        } catch (Throwable ignored) { }
    }

    // 波 ↔ 流体圆点 的交叉淡化：状态一变就朝目标推进，避免「思考→回答」时光效瞬间换脸。
    private float orbMixNow = 0f;            // 当前值（0=纯 Siri 波，1=纯流体圆点）
    private float orbMixTarget = 0f;
    private long orbMixLastMs = 0;
    private static final long ORB_MIX_MS = 320;   // 淡化时长

    private void pumpOrbMix() {
        if (renderer == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        float dt = orbMixLastMs == 0 ? 0.016f : Math.min(0.05f, (now - orbMixLastMs) / 1000f);
        orbMixLastMs = now;
        if (orbMixNow != orbMixTarget) {
            float step = dt * 1000f / ORB_MIX_MS;
            orbMixNow += Math.max(-step, Math.min(step, orbMixTarget - orbMixNow));
            if (Math.abs(orbMixTarget - orbMixNow) < 0.002f) orbMixNow = orbMixTarget;
            renderer.orbMix = orbMixNow;
        }
    }

    private void setOrbMixTarget(float t) {
        orbMixTarget = Math.max(0f, Math.min(1f, t));
        if (renderer != null && orbMixNow == orbMixTarget) renderer.orbMix = orbMixNow;
    }

    /** 助手接管前的波形设置；交还时原样恢复，用户调好的参数不会被助手改掉。 */
    private boolean waveSaved = false;
    private boolean waveOnSaved = true;
    private int waveModeSaved = 1;

    // ── 波形幅度包络 + 状态沉降：解决「处理中 ↔ Siri 波」来回硬跳 ──────────────────
    // 两个问题合起来看：
    //  ① 抖动：一个回合里 DSH 会多次报 THINKING / ANSWERING / IDLE（多段生成、工具调用之间），
    //     而 IDLE 一到就把形态交还给用户的 Siri 模式 → 球在「流体圆点」和「Siri 波」之间闪。
    //  ② 生硬：waveMode 是 uniform 里的分支，切换即换脸，没有过渡。
    // 对策：
    //  ① 沉降（settle）：IDLE 不当场生效，压后 IDLE_SETTLE_MS。期间任何一次真实活动
    //     （THINKING/ANSWERING…）都会取消它 —— 只有「确实安静下来了」才回 IDLE。
    //  ② 包络（gate）：换形态先把幅度淡到 0、换完再淡回来（WAVE_FADE_MS ×2）。
    private String stateNow = "IDLE";          // 球当前呈现的助手状态
    private float orbWanted = 0f;              // 文字消失后该显示的形态（0=波 1=流体圆点）
    private long stateEnteredAt = 0;           // 进入 stateNow 的时刻
    private Runnable idleSettle;               // 压后的「回 IDLE」
    private long stateSig = 0;                 // 去重：同一个状态连续上报只处理一次
    // 安静多久才算「本轮结束」（结束前一直保持处理中，避免回 Siri 波又被打断造成闪动）。
    // 用户要求「文字停留久一点」，这里从 1200 拉到 3500；真正结束的定义仍是「安静这么久」。
    private static final long IDLE_SETTLE_MS = 3500;

    private float waveGateTarget = 1f;
    /** 流体圆点的目标 ny 偏移（有字 → 底部动效带；没字 → 默认位置），由渲染节拍平滑推进。 */
    private volatile float orbOffsetTarget = -0.03f;
    private int waveModePending = -1;          // 淡出到底时要切到的形态（-1=不切）
    private static final long WAVE_FADE_MS = 180;

    /** 每帧推进：幅度闸门朝目标走，走到底时执行挂起的形态切换。 */
    private void pumpWaveGate() {
        if (renderer == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        float dt = waveGateLastMs == 0 ? 0.016f : Math.min(0.05f, (now - waveGateLastMs) / 1000f);
        waveGateLastMs = now;
        if (renderer.waveGate == waveGateTarget) {
            if (waveGateTarget == 0f && (waveModePending >= 0 || sceneCommit != null)) {
                // 已经淡到 0（画面里没有波也没有点）→ 现在换形态，看不出跳变
                if (waveModePending >= 0) {
                    renderer.waveMode = waveModePending;
                    waveModePending = -1;
                    Log.i("FloatingBall", "wave mode -> " + renderer.waveMode + "（幅度为 0 时切换）");
                }
                if (sceneCommit != null) {
                    Runnable c = sceneCommit;
                    sceneCommit = null;
                    c.run();                          // 状态槽要的形态（波/点/流体/胶囊加载）
                }
                // 关掉的动画（槽=关闭）淡到 0 就停在那儿
                waveGateTarget = (renderer != null && renderer.waveOn) ? 1f : 0f;
            }
            return;
        }
        float step = dt * 1000f / WAVE_FADE_MS;
        // 圆点偏移平滑跟随目标（有字→底带 / 没字→默认）。直接赋值会跳一下，这里 200ms 左右收敛。
        if (renderer != null) {
            float cur = renderer.orbOffset, tgt = orbOffsetTarget;
            float k = Math.min(1f, step / 0.12f * 0.5f);
            if (Math.abs(tgt - cur) < 0.004f) renderer.orbOffset = tgt;
            else renderer.orbOffset = cur + (tgt - cur) * Math.max(0.06f, k);
        }
        float g = renderer.waveGate;
        g += Math.max(-step, Math.min(step, waveGateTarget - g));
        if (Math.abs(waveGateTarget - g) < 0.01f) g = waveGateTarget;
        renderer.waveGate = Math.max(0f, Math.min(1f, g));
    }

    private long waveGateLastMs = 0;

    /** 让波形幅度淡出 →（可选）换形态 → 淡入。 */
    private void setWaveForm(int mode) {
        if (renderer == null) return;
        if (mode < 0) {                            // 不换形态，只保证幅度是开着的
            waveModePending = -1;
            waveGateTarget = 1f;
            return;
        }
        if (renderer.waveMode == mode && waveModePending < 0) {
            waveGateTarget = 1f;
            return;
        }
        waveModePending = mode;
        waveGateTarget = 0f;
    }

    /** 把形态交还给用户自己的 Siri 模式设定（走包络，不硬切）。 */
    private void releaseWaveForm() {
        if (!waveSaved || renderer == null) return;
        waveSaved = false;                         // 只交还一次；形态在淡出到底时才真正切
        setWaveForm(waveModeSaved);
    }

    /** 助手状态：IDLE / THINKING / ANSWERING / OFF。 */
    public static void setAssistantState(String state) {
        FloatingBallService s = INSTANCE;
        if (s == null || s.renderer == null || state == null) return;

        // OFF = 助手关了，立刻回 IDLE 并交还形态（不参与沉降）
        if ("OFF".equals(state)) {
            s.cancelIdleSettle();
            s.stateNow = "IDLE";
            s.stateSig++;
            s.applyAssistantState("OFF");
            s.releaseTextHold();
            Log.i("FloatingBall", "assistant state=OFF → IDLE（交还形态）");
            return;
        }

        boolean active = !"IDLE".equals(state);
        if (active) {
            // 任何真实活动都取消「压后回 IDLE」，IDLE 才能真正代表结束
            boolean hadSettle = s.idleSettle != null;
            s.cancelIdleSettle();
            if (!state.equals(s.stateNow)) {
                s.stateNow = state;
                s.stateSig++;
                s.applyAssistantState(state);
            } else if (hadSettle) {
                Log.i("FloatingBall", "idle settle 被 " + state + " 取消（回合还没完）");
            }
            return;
        }

        // IDLE：压后生效 —— 安静 IDLE_SETTLE_MS 之后，才回 Siri 波 / 空白
        if (s.idleSettle != null) return;          // 已经在压后了
        if ("IDLE".equals(s.stateNow)) { s.stateSig++; s.applyAssistantState("IDLE"); return; }
        final long sig = ++s.stateSig;
        s.idleSettle = new Runnable() {
            @Override public void run() {
                FloatingBallService cur = INSTANCE;
                if (cur == null || sig != cur.stateSig) return;
                cur.idleSettle = null;
                cur.stateNow = "IDLE";
                cur.applyAssistantState("IDLE");
                // ⚠️ 这里**绝对不能**再 releaseTextHold()（老代码就是在这收的）。
                // DSH 一个回合里会多次报 THINKING→IDLE（工具调用之间的空档就是），
                // 只要某个空档超过 IDLE_SETTLE_MS，方形态就被无条件收掉 ——
                // 用户看到的"处理中动画几秒就不见了 / 球自己缩回圆球"就是这个。
                // 用户规则：**只有最终回复**才收回初始形状，那一刻由
                // SiriAssistant.quietTick 在安静 1.5s 后调 releaseHoldForFinalReply()。
                Log.i("FloatingBall", "idle settle 到点 → 只交还光效，**不收方形态**"
                        + "（收回只由最终回复触发，安静了 " + IDLE_SETTLE_MS + "ms）");
            }
        };
        s.main.postDelayed(s.idleSettle, IDLE_SETTLE_MS);
        Log.i("FloatingBall", "IDLE 压后 " + IDLE_SETTLE_MS + "ms（期间再有活动就取消）");
    }

    private void cancelIdleSettle() {
        if (idleSettle != null) {
            main.removeCallbacks(idleSettle);
            idleSettle = null;
        }
    }

    /** 球里现在有没有字（文字优先于动画：有字时不出波也不出圆点）。 */
    private boolean hasBallText() {
        return siriTextShown && siriStr != null && !siriStr.trim().isEmpty();
    }

    // ── 「整轮结束」信号 + 方形态的出波闸门（2026-10-16 用户要求）──────────────
    // 需求原话：「方形状态下只有 agent 回复最终结果才能出现 Siri 波，且这道波要像背景一样
    // 被折射变糊、贴着方形底端不变」。用户选定的判定是 **turn/end**（整轮结束）才出波。
    // 做法：波不再叠在玻璃表面，而是先画进背景纹理（GlassRenderer.FS_WAVE），
    // 于是它天然吃到与背景完全相同的折射/放大/色散/模糊；锚点在球底（renderer.waveNy）。
    private volatile boolean turnEnded = false;
    /**
     * 生效中的闸门（带压后）。为什么不直接用 turnEnded：
     * DSH 一轮里 `turn/end` 和**下一条消息的 attempt-start** 会交替出现（实测相隔几百毫秒），
     * 直接跟着它开关，球底那截波就会**一闪一闪像 LED**（用户报的现象）。
     * 所以"开"立刻生效、"关"压后 WAVE_OFF_SETTLE_MS，期间又遇到 turn/end 就当没关过。
     */
    private volatile boolean waveHold = false;
    private Runnable waveOffSettle;
    private static final long WAVE_OFF_SETTLE_MS = 1200;

    /** SiriAssistant 在 turn/end 时调用；新一轮开始（onAttemptStart / 新提问 / 清空）时调 false。 */
    public static void setAssistantTurnEnded(boolean ended) {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(() -> s.applyTurnEnded(ended));
    }

    private void applyTurnEnded(boolean ended) {
        if (turnEnded == ended) return;
        turnEnded = ended;
        if (ended) {
            cancelWaveOffSettle();      // 又结束了 → 撤销"关闸门"的压后
            waveHold = true;
            markReplySettled();         // 1.2s 后确认：会话真停了才算最终回复
            Log.i("FloatingBall", "turn ended=true（波闸门 开；等确认是否最终回复）");
            refreshFinalWave();
        } else {
            scheduleWaveOffSettle();    // 关闸门走压后，避免 turn/end 与 attempt-start 交替造成闪烁
            Log.i("FloatingBall", "turn ended=false（波闸门压后 " + WAVE_OFF_SETTLE_MS + "ms 再关）");
        }
    }

    private void cancelWaveOffSettle() {
        if (waveOffSettle != null) {
            main.removeCallbacks(waveOffSettle);
            waveOffSettle = null;
        }
    }

    private void scheduleWaveOffSettle() {
        if (waveOffSettle != null) return;
        waveOffSettle = new Runnable() {
            @Override public void run() {
                waveOffSettle = null;
                if (turnEnded) return;
                waveHold = false;
                Log.i("FloatingBall", "波闸门压后到点 → 关");
                refreshFinalWave();
            }
        };
        main.postDelayed(waveOffSettle, WAVE_OFF_SETTLE_MS);
    }

    /**
     * 方形态（球里有字）的出波闸门：**只有整轮结束**才出波，而且波走玻璃、贴球底。
     *
     * ★ 2026-11-08（二改，用户定稿）：「Siri 波固定在回复 AI 文字的那块玻璃的底部，
     *   无论玻璃变高还是变矮，固定只显示 Siri 波头」。
     *   所以：
     *   · **不再**看「沉入玻璃」开关 —— 有字 + 槽里绑的是 Siri 波，就一定是沉底那一支；
     *   · 文字和波之间**不再互相定位**（「黑区下沿 / 安全距离」两个滑块已删）：
     *     波钉在玻璃底边，只由渲染器的固定波头带决定露多少（见 WAVE_HEAD_DP）。
     */
    private void refreshFinalWave() {
        if (renderer == null) return;
        // ⚠️ 这个布尔量是"圆点动画看不见"的**真凶**，改之前先读着色器：
        //   `if (uWaveInGlass > 0.5) { 只画球底那一截波 } else { 圆点/波在这里画 }`
        // 也就是说 waveInGlass=1 时，**整段流体圆点的绘制被跳过**。
        String finalAnim = AnimScene.master(animPrefs())
                ? AnimScene.binding(animPrefs(), AnimScene.SLOT_FINAL) : AnimScene.A_WAVE;
        boolean show = siriEnabled && replyFinal && waveHold && hasBallText()
                && squareDrawn01() > 0.05f          // 胶囊形态上绝不画回复波（球已经是胶囊了）
                && AnimScene.A_WAVE.equals(finalAnim);
        if (show) {
            renderer.waveInGlass = 1f;      // 有字 + 波 → 永远钉玻璃底、只露波头
            renderer.waveOn = true;
            // ⚠️ 这里**不走 setWaveForm()**：那个函数为了"看不见跳变"会先把幅度淡到 0、
            // 换完形态再淡回来；而这个函数每打一个字就会被调用一次（镜像打字机），
            // 一走淡出淡入就是用户看到的"LED 一闪一闪"。方形态固定用 Siri 波，直接钉住。
            if (renderer.waveMode != 1) { renderer.waveMode = 1; waveModePending = -1; }
            // ★ 2026-11-08（用户：最终回复显示的还是过程中回复的动画、Siri 波没了）：
            //   applyAnimToRenderer() 换槽时会把 gate 目标压到 0，并把「关 loader / 换形态」
            //   存进 sceneCommit，等 gate 到 0 才执行。这里要是无条件把 gate 目标抬回 1，
            //   commit 永远等不到 0 → loaderMode 一直停在 cap6（过程中回复的胶囊加载），
            //   Siri 波被盖住。所以只在「没有待执行的场景切换」时才抬 gate。
            if (sceneCommit == null) waveGateTarget = 1f;
        } else if (!hasBallText() || !AnimScene.A_WAVE.equals(sceneAnim)) {
            renderer.waveInGlass = 0f;      // 没字 / 槽不是波（圆点、胶囊）→ 交还给球心那套
        }
    }

    /**
     * 状态一变：重新解析「这个状态该用哪个动画」并落到渲染器上。
     *
     * ★ 2026-11-08 重写。老版本在这里写死了「THINKING 用圆点 / 其它用 Siri 波」，
     * 所以加胶囊加载动画时只能靠三个互斥开关抢同一个位置，Siri 波和 dots 就被覆盖了。
     * 现在这里只剩两件事：
     *   ① 把状态翻译成「槽」（resolveSlot）；
     *   ② 把槽里绑的动画落到渲染器（refreshScene）—— 动画本身去 AnimScene 查。
     * 「有字 / 没字」不再决定出不出动画，只决定动画摆在球心还是球底动效带。
     */
    private void applyAssistantState(String state) {
        if (renderer == null) return;
        // ⚠️ 这里**不能**在 IDLE 时清「执行中」：实测 turn/end 比命令结束早 10 秒以上，
        //    一清，整段命令执行就掉回待机动画。清它只认三件事：tool/result、
        //    新一轮提问/新的 assistant attempt、以及 10 分钟超时兜底（见 toolRunningNow）。
        if ("OFF".equals(state)) {
            asstToolRunning = false; toolSinceMs = 0; asstToolName = null; asstTaskActive = false;
            replySettledPending = false; replyFinal = false; cancelFinalConfirm();
        }
        // 新一轮（思考/回答重新开始）→ 上一句不可能是最终回复
        if ("THINKING".equals(state) || "ANSWERING".equals(state)) {
            replySettledPending = false; replyFinal = false; cancelFinalConfirm();
        }
        orbWanted = 0f;
        stateEnteredAt = android.os.SystemClock.uptimeMillis();
        sceneDirty = true;
        refreshScene(true);
        refreshFinalWave();
        final String st = state;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (renderer == null) return;
                Log.i("FloatingBall", "动效收敛后[" + st + "]: 槽=" + sceneSlot + " 动画=" + sceneAnim
                        + " orbMix=" + renderer.orbMix + " waveInGlass=" + renderer.waveInGlass
                        + " waveGate=" + renderer.waveGate + " waveMode=" + renderer.waveMode
                        + " loader=" + renderer.loaderMode);
            }
        }, 420);
        Log.i("FloatingBall", "assistant state=" + state + " → 槽 " + sceneSlot + " / 动画 " + sceneAnim
                + (AnimScene.master(animPrefs()) ? "" : "（统一调度已关：按旧逻辑）"));
    }

    // ════════════════ 动画总控：状态槽 → 动画（AnimScene） ════════════════
    // 用户 2026-11-08：「每个状态甚至界面都要能定制不同的动画，动画同一个级别汇总」。
    // 这里只有一条链路：
    //   resolveSlot() 决定现在是哪个槽 → AnimScene 查这个槽绑的动画 → 落到渲染器。
    // 旧的 think_style / think_loader / float_fluid_style **不再参与判断**，
    // 只在「统一调度」关掉时当作退回路径（legacyAnimFor），方便一键回到老行为。

    private android.content.SharedPreferences animPrefs() {
        return getSharedPreferences(AnimScene.PREFS, MODE_PRIVATE);
    }

    private String sceneSlot = AnimScene.SLOT_IDLE;   // 当前生效的槽
    private boolean sceneTextLast = false;            // 上一次「球里有没有字」（摆位跟着它变）
    private int sceneBandLast = 0;                    // 上一次摆位时的动效带高度（变高/变矮要重摆）
    private String sceneAnim = "";                    // 当前生效的动画 id
    private String previewSlot = null;                // 设置页「预览」钉住的槽
    private volatile boolean asstToolRunning = false; // 工具在跑的显式标记（命令 / 其它工具共用）
    private volatile String asstToolName = null;      // 当前跑的工具名（bash / read / edit …）
    /** 球自己提问的那一轮还在跑（从 ask 发出到 DSH 真正收尾）。 */
    private volatile boolean asstTaskActive = false;
    private boolean sceneDirty = false;               // 参数/绑定变了，要重推
    private Runnable sceneCommit = null;              // 幅度淡到 0 时执行的形态切换
    private long sceneLastCheck = 0;

    // ═══════════════ 菜单界面：动画先藏起来 + AI 回复的字别跑到菜单里（临时，用户口径） ═══════════════
    /**
     * ★ 2026-11-08（用户要求，**临时**）：
     *
     *   ① **长按球呼出的那个菜单界面不出动画。**
     *      「菜单界面」= 长按球进入的状态 3（六区），以及菜单里的各级子界面：
     *      按住滑出来的二级/三级卡片（工作目录 / 搜索会话 / 运行中的会话 / 参数 …）、
     *      还有贴在被撑出来的玻璃面板上那几层（BallGlassMenu）—— 一律把动画压成 off。
     *      **菜单自己那些字（面板标题 / 行名 / 说明）照常显示**，一个字都不动。
     *
     *   ② **AI 回复的那段字不要出现在这些地方**：菜单（六区 / 子界面 / 玻璃面板）、
     *      搜索胶囊、输入胶囊。那里本来就不该挂一段 AI 回复。
     *      球自己的回复文字照旧，离开这些界面就回来。
     *
     *   绑定和别处的显示都没动：`bind.zones` 还是用户原来那条（现在是 cap8）。想恢复：
     *     · 重编：把下面两个常量改回 false；
     *     · 不重编（1 秒内被探测到）：
     *         su -c 'touch /data/data/com.glslplayground.app/files/menu-anim-on'   ← 菜单里恢复动画
     *         su -c 'touch /data/data/com.glslplayground.app/files/menu-reply-on'  ← 这些界面里也允许 AI 回复的字
     *       `rm` 掉对应文件就又藏起来。
     */
    private static final boolean MENU_ANIM_HIDDEN = true;
    private static final boolean MENU_REPLY_TEXT_HIDDEN = true;
    private long menuHideProbeAt = 0L;
    private boolean menuHideAnim = MENU_ANIM_HIDDEN;
    private boolean menuHideReply = MENU_REPLY_TEXT_HIDDEN;
    /** AI 回复的字现在是因为"这些界面不该有它"被收掉的（关掉界面时据此还原）。 */
    private boolean replyTextHiddenForUi = false;

    /**
     * 菜单那一屏开着吗（六区 / 菜单里任意一级子界面 / 玻璃面板）。
     *
     * 只认 zonesActive 和 menuShown：菜单里的二级/三级卡片本来就是挂在六区这一层上的
     * （BallZonesView.cardMode），zonesActive 为真时它们一定也在。
     * 别再拿 holdLevel 当判据 —— 音量加「藏界面」那条路把六区收掉时不会顺手清 holdLevel，
     * 它会一直留着，于是离开菜单以后别的状态（麦克风 / 待机）也被误压成 off（实测踩到）。
     */
    private boolean menuSurfaceOpen() {
        return zonesActive || menuShown;
    }

    /** 这一屏不该出现 AI 回复的字吗（菜单 / 搜索胶囊）。 */
    private boolean replyTextShouldHide() {
        // ★ 2026-11-09 修（用户报的"菜单进工作目录再进回话，回话内容不显示"）：
        //   这里以前还带着 `|| convoOpen` —— 可是**会话卡的内容就是这一层字**
        //   （siriStr 由 setSiriText 灌进来），于是卡一开，字就被每 100ms 一次的巡检
        //   收走，屏幕上只剩一块空玻璃 + 一条输入行：正是用户截图那个样子。
        //   进菜单时旧回复的字会被"正在读取对话…"顶掉（见 openConversation），不需要靠这条挡。
        return menuHideReply && (menuSurfaceOpen() || searchActive);
    }

    /** 每 1 秒看一眼两个免重编的文件口子。 */
    private void pumpMenuHideProbe() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - menuHideProbeAt < 1000L) return;
        menuHideProbeAt = now;
        File dir = getFilesDir();
        menuHideAnim = MENU_ANIM_HIDDEN && !new File(dir, "menu-anim-on").exists();
        menuHideReply = MENU_REPLY_TEXT_HIDDEN && !new File(dir, "menu-reply-on").exists();
    }

    /** 菜单界面要不要把动画藏掉（临时开关 + 免重编的文件口子）。 */
    private boolean hideAnimInMenu() {
        return menuHideAnim;
    }

    /** 槽 + 绑定到的动画 → 真正落地的动画 id（菜单界面统一压成 off）。 */
    private String effectiveAnim(String slot, String anim) {
        if (menuSurfaceOpen() && hideAnimInMenu()) return AnimScene.A_OFF;
        return anim;
    }

    /**
     * 每 100ms 跟一次「AI 回复的字该不该出现」。
     *
     * 只在**翻转的那一下**动文字层：进来收掉、出去还原，中途不碰 —— 免得和
     * 「文字消失 → 按槽重推动画」那套逻辑打架（hideSiriTextNow 会顺手重推动画，这里不用它）。
     */
    private void pumpReplyTextHide() {
        boolean hide = replyTextShouldHide();
        if (hide) {
            // 已经标记过、但文字层又被别的路径放出来了（比如菜单开着时来了一段新回复）→ 再收一次
            if (!replyTextHiddenForUi
                    || (siriText != null && siriText.getVisibility() == View.VISIBLE)) {
                hideReplyTextForUi();
            }
            return;
        }
        if (replyTextHiddenForUi) restoreReplyTextAfterUi();
    }

    /** 把 AI 回复的文字层收掉（球里那段字），不动 siriStr、不动形态、不动动画。 */
    private void hideReplyTextForUi() {
        replyTextHiddenForUi = true;
        if (siriText == null) return;
        siriTextShown = false;
        if (siriText.getVisibility() != View.GONE) {
            siriText.setVisibility(View.GONE);
            Log.i("FloatingBall", "AI 回复的字先收起来（菜单 / 搜索胶囊 / 输入胶囊里不该有它）");
        }
        syncSiriTouchLayerQuiet();
    }

    /**
     * 回到能显示回复的界面：原来有字就再放出来。
     *
     * ★ 2026-11-09（用户实拍「菜单里字消失是对的，回来以后字没回来」）：这里以前只有
     *   一句 `showSiriTextNow()` —— 而它只改可见性。菜单（六区）进来时
     *   `forceHideAllText()` 已经把内容 View 全删了，所以必须：
     *     ① 先按 siriStr 把内容重建出来；
     *     ② 球如果还停在最小胶囊上（菜单把几何重置了），按文字重新量一遍几何。
     *   两件事都做完再露字，否则屏幕上还是那块空玻璃。
     */
    private void restoreReplyTextAfterUi() {
        if (siriText == null || uiHidden) return;   // 界面还藏着 / 文字层还没建：标记留着，下一帧再放
        if (siriStr == null || siriStr.trim().isEmpty()) {
            replyTextHiddenForUi = false;
            return;
        }
        replyTextHiddenForUi = false;
        ensureMarkdownContent();                    // ① 内容（菜单里被清空过 → 重建）
        if (!squareFormActive()) {                  // ② 几何（球可能还停在胶囊上）
            measureSiriTargets(siriStr);
        }
        showSiriTextNow();                          // ③ 露字
        if (convoOpen) scrollConvoToEnd();          // 会话卡：回来还停在最新那一头
        boolean up = siriText != null && siriText.getVisibility() == View.VISIBLE;
        Log.i("FloatingBall", "离开菜单/胶囊 → AI 回复的字放回来（" + siriStr.length() + " 字，内容块="
                + (mdColumn == null ? -1 : mdColumn.getChildCount()) + "，"
                + (up ? "已显示" : "还没显示") + "）");
    }

    /** 麦克风正在用（按住说话 / 输入法语音 / 旧的开麦路径）—— 这几个都算「刚开始用麦克风」。 */
    private boolean micActive() {
        return powerHoldActive || searchVoiceMode || (voice != null && voice.listening());
    }

    /** 当前该用哪个槽。顺序 = 谁更"当下"。 */
    private String resolveSlot() {
        if (previewSlot != null) return previewSlot;
        if (micActive()) return AnimScene.SLOT_LISTEN;       // 麦克风优先（按住说话时胶囊也是开着的）
        if (searchActive) return AnimScene.SLOT_SEARCH;
        if (zonesActive) return AnimScene.SLOT_ZONES;
        if (approvalShown) return AnimScene.SLOT_APPROVAL;
        // ★ 2026-11-08（二修）：**工具在跑就一定是「执行中」**，和 stateNow 无关。
        //   实测事件顺序：assistant/message → tool/call → job/registered →（命令真在跑）
        //   → 1~2 秒后 turn/end 先到 → 3.5 秒沉降把 stateNow 刷成 IDLE
        //   —— 命令还在跑（tool/result 要十几秒后才来），球却已经回待机。
        //   以前 EXEC 只写在 THINKING 分支里，所以整段命令执行显示的是待机动画。
        //   命令（bash / 终端）走「执行中」，其它工具（读/改/搜索/子代理…）走「调用工具」——
        //   两档各绑一套动画，用户要求「调用工具时也要能单独设置」。
        if (toolRunningNow()) {
            return toolIsCommand() ? AnimScene.SLOT_EXEC : AnimScene.SLOT_TOOL;
        }
        if ("THINKING".equals(stateNow)) {
            // ★ 2026-11-08：这里原来还有一条「思考超过 2.5s 没出字 = 在执行」的猜测，
            //   模型正常想 5~20 秒很常见 →「思考中」的动画只亮 2.5 秒就被「执行中」顶掉，
            //   用户看到的就是「思考中的动画和设置对不上」。现在只认真信号：
            //   镜像流里的 tool/call ↔ tool/result（见 DshHttpTransport.dispatchMirror）。
            return asstToolRunning ? AnimScene.SLOT_EXEC : AnimScene.SLOT_THINK;
        }
        // ★ 2026-11-08（三修，用户口径）：
        //   「过程中回复」= 这段回复**后面还有事要做**；
        //   「最终回复」= 这段回复**之后会话就停了**。
        //   以前只看 turn/end —— 可 DSH 每一步都会先 turn/end 再继续（工具是异步 job），
        //   于是"还会接着干"的那些回复全被当成最终回复。现在 turn/end 只算"这段说完了"，
        //   真正定性要等 1.2 秒后查一次这条会话还在不在跑（见 markReplySettled）。
        if ("ANSWERING".equals(stateNow) && hasBallText() && squareDrawn01() > 0.05f) {
            return replyFinal ? AnimScene.SLOT_FINAL : AnimScene.SLOT_STREAM;
        }
        // ★ 2026-11-08（用户问「为什么变回胶囊后还显示一段 Siri 波才回待机动画」）：
        //   根因就是上面这一支原来**只看 stateNow**。最终回复收完、文字已经清掉、球也缩回
        //   胶囊了，可状态机还要等 IDLE 沉降（IDLE_SETTLE_MS）才把 stateNow 从 ANSWERING
        //   改回 IDLE —— 这段时间里 resolveSlot() 仍然报「最终回复」→ 空胶囊继续画 Siri 波，
        //   直到沉降到点才换回待机动画。
        //   现在「有没有字」是硬条件：球里没字就不可能是回复槽，立刻落到下面
        //   （工具还在跑 → 思考/执行；否则 → 待机）。
        // 字还在球上、但流已经收了：pending = 还没确认，先按过程中回复；确认停了才转最终回复
        if (hasBallText() && squareDrawn01() > 0.05f && (replyFinal || replySettledPending)) {
            return replyFinal ? AnimScene.SLOT_FINAL : AnimScene.SLOT_STREAM;
        }
        // ★ 球自己那一轮还在跑（工具之间模型在思考、球里没字）——这时不能显示待机：
        //   「帮我点个外卖」这类任务，工具一秒一个、间隙两三秒，
        //   以前每段间隙都落回 idle，用户看到的就是"大部分时候是待机动画"。
        if (asstTaskActive) return AnimScene.SLOT_THINK;
        return AnimScene.SLOT_IDLE;
    }

    /** 统一调度关掉时的旧映射（尽量还原老行为）。 */
    private String legacyAnimFor(String slot) {
        if (AnimScene.SLOT_THINK.equals(slot) || AnimScene.SLOT_EXEC.equals(slot)) {
            int f = thinkForm();
            if (f == 0) return AnimScene.cap(Math.max(1, Math.min(9, prefs().getInt("think_loader", 1))));
            return f == 1 ? AnimScene.A_WAVE : (f == 2 ? AnimScene.A_DOTS : AnimScene.A_FLUID);
        }
        if (AnimScene.SLOT_APPROVAL.equals(slot) || AnimScene.SLOT_SEARCH.equals(slot)
                || AnimScene.SLOT_ZONES.equals(slot)) {
            return AnimScene.A_OFF;
        }
        return AnimScene.A_WAVE;
    }

    /** 解析槽 + 落地动画（force=true 时无视"看起来没变"）。 */
    private void refreshScene(boolean force) {
        if (renderer == null) return;
        String slot = resolveSlot();
        boolean master = AnimScene.master(animPrefs());
        String bound = master ? AnimScene.binding(animPrefs(), slot) : legacyAnimFor(slot);
        String anim = effectiveAnim(slot, bound);   // ★ 菜单界面：动画暂时藏起来
        boolean changed = force || sceneDirty || !slot.equals(sceneSlot) || !anim.equals(sceneAnim);
        sceneSlot = slot;
        sceneAnim = anim;
        if (!changed) return;
        sceneDirty = false;
        if (!anim.equals(bound)) {
            Log.i("FloatingBall", "菜单界面：动画暂时藏起来（槽=" + slot
                    + " 绑定的是 " + bound + " → 实际 off）");
        }
        boolean text = hasBallText();
        applyAnimParams(anim);
        applyAnimToRenderer(anim);
        placeAnimForText(anim, text);
        Log.i("FloatingBall", "场景动画: 槽=" + slot + " 动画=" + anim
                + (master ? "" : "（旧逻辑）") + " 有字=" + text
                + " → waveMode=" + renderer.waveMode + " loader=" + renderer.loaderMode
                + " gate→" + waveGateTarget + " orbMix→" + orbMixTarget);
    }

    /** 把某个动画自己的参数推到渲染器（每个动画一套，互不干扰）。 */
    private void applyAnimParams(String anim) {
        if (renderer == null) return;
        android.content.SharedPreferences sp = animPrefs();
        if (AnimScene.isCap(anim)) {
            renderer.animSpeed = 1f;
            renderer.loaderSpeed = Math.max(.2f, AnimScene.param(sp, anim, "speed") / 100f);
            renderer.loaderScale = Math.max(.2f, AnimScene.param(sp, anim, "scale") / 100f);
            renderer.loaderDot = Math.max(.2f, AnimScene.param(sp, anim, "dot") / 100f);
            return;
        }
        if (AnimScene.A_WAVE.equals(anim)) {
            renderer.waveAmp = Math.max(.2f, AnimScene.param(sp, anim, "amp") / 100f);
            renderer.waveScale = Math.max(.3f, AnimScene.param(sp, anim, "scale") / 100f);
            // 「沉底偏移 / 沉入玻璃」两个定位参数已删：波心藏在玻璃底边下方多少、
            // 露出的波头多高，都由渲染器的固定规则算（见 GlassRenderer 的 uWaveHead）。
            renderer.waveHeadPx = dp(WAVE_HEAD_DP);
            renderer.animSpeed = Math.max(.2f, AnimScene.param(sp, anim, "speed") / 100f);
            return;
        }
        if (AnimScene.A_DOTS.equals(anim)) {
            renderer.orbRadius = Math.max(30f, AnimScene.param(sp, anim, "radius"));
            renderer.orbGain = Math.max(.1f, AnimScene.param(sp, anim, "gain") / 100f);
            renderer.orbRim = Math.max(0f, AnimScene.param(sp, anim, "rim") / 100f);
            renderer.orbOffsetDefault = AnimScene.param(sp, anim, "offset") / 100f;
            renderer.animSpeed = 1f;
            return;
        }
        if (AnimScene.A_FLUID.equals(anim)) {
            int cm = (int) AnimScene.param(sp, anim, "colorMode");
            renderer.fluidColorMode = Math.max(0, Math.min(2, cm));
            int hue = ((int) AnimScene.param(sp, anim, "hue") % 360 + 360) % 360;
            int c = android.graphics.Color.HSVToColor(new float[]{ hue, 0.85f, 1f });
            renderer.fluidColorR = android.graphics.Color.red(c) / 255f;
            renderer.fluidColorG = android.graphics.Color.green(c) / 255f;
            renderer.fluidColorB = android.graphics.Color.blue(c) / 255f;
            renderer.fluidHot = Math.max(0f, AnimScene.param(sp, anim, "hot") / 100f);
            renderer.fluidDash = Math.max(0f, AnimScene.param(sp, anim, "dash") / 100f);
            renderer.fluidRibs = Math.max(4f, AnimScene.param(sp, anim, "ribs"));
            renderer.fluidSize = Math.max(.1f, AnimScene.param(sp, anim, "size") / 100f);
            renderer.fluidGain = Math.max(.2f, AnimScene.param(sp, anim, "gain") / 100f);
            renderer.animSpeed = 1f;
            return;
        }
        renderer.animSpeed = 1f;
    }

    /**
     * 把动画落到渲染器。换形态一律走「幅度淡到 0 → 换 → 淡回来」的包络，
     * 所以任何两个动画之间切换都不会硬跳。
     */
    private void applyAnimToRenderer(final String anim) {
        if (renderer == null) return;
        final boolean cap = AnimScene.isCap(anim);
        final boolean off = AnimScene.A_OFF.equals(anim);
        final int mode = AnimScene.A_WAVE.equals(anim) ? 1
                : AnimScene.A_DOTS.equals(anim) ? 2
                : AnimScene.A_FLUID.equals(anim) ? 3 : 0;

        // 搜索胶囊 / 六区：球那趟合成不跑 → 胶囊加载改走屏幕空间叠层
        boolean overlay = cap && (AnimScene.SLOT_SEARCH.equals(sceneSlot)
                || AnimScene.SLOT_ZONES.equals(sceneSlot));
        renderer.overlayLoader = overlay;
        if (overlay) {
            float[] geo = overlayGeometry();
            renderer.ovlCx = geo[0];
            renderer.ovlCy = geo[1];
            renderer.ovlHx = Math.max(24f, geo[2]);
            renderer.ovlHy = Math.max(24f, geo[3]);
        }

        final int wantLoader = cap ? AnimScene.capIndex(anim) : 0;
        final boolean wantSuppress = cap;
        final boolean wantWaveOn = !off;
        final int wantMode = mode == 0 ? renderer.waveMode : mode;

        Runnable commit = new Runnable() {
            @Override public void run() {
                if (renderer == null) return;
                if (wantMode > 0 && renderer.waveMode != wantMode) renderer.waveMode = wantMode;
                renderer.loaderMode = wantLoader;
                renderer.suppressWave = wantSuppress;
                renderer.waveOn = wantWaveOn;
                // ★ 2026-11-08：有字 + 槽里绑的是 Siri 波 → 波永远沉到玻璃底部（只露波头）。
                //   这是**唯一**的波定位方式，文字和波之间不再有任何可调距离。
                renderer.waveInGlass = (AnimScene.A_WAVE.equals(anim)
                        && hasBallText() && squareDrawn01() > 0.05f) ? 1f : 0f;
            }
        };

        boolean needFade = (mode != 0 && renderer.waveMode != mode)
                || renderer.loaderMode != wantLoader
                || renderer.suppressWave != wantSuppress
                || renderer.waveOn != wantWaveOn;

        if (needFade && renderer.waveGate > 0.02f) {
            sceneCommit = commit;
            waveModePending = -1;
            waveGateTarget = 0f;              // 淡到 0 时执行 commit（见 pumpWaveGate）
        } else {
            commit.run();
            sceneCommit = null;
            waveGateTarget = wantWaveOn ? 1f : 0f;
        }

        boolean orb = AnimScene.A_DOTS.equals(anim) || AnimScene.A_FLUID.equals(anim);
        setOrbMixTarget(orb ? 1f : 0f);
        orbWanted = orb ? 1f : 0f;
    }

    /** 搜索胶囊 / 六区里，动画该叠在哪里（窗口像素，y 向下）。 */
    private float[] overlayGeometry() {
        if (renderer == null) return new float[]{ 0, 0, 100, 40 };
        if (AnimScene.SLOT_SEARCH.equals(sceneSlot) && renderer.searchMode) {
            return new float[]{
                    renderer.sBlobCx - renderer.winX,
                    renderer.sBlobCy - renderer.winY,
                    Math.max(20f, renderer.sBlobHx),
                    Math.max(20f, renderer.sBlobHy)
            };
        }
        if (zonesView != null) {
            int[] loc = new int[2];
            try { zonesView.getLocationOnScreen(loc); } catch (Throwable ignored) { }
            int w = zonesView.getWidth(), h = zonesView.getHeight();
            if (w > 0 && h > 0) {
                return new float[]{
                        loc[0] - renderer.winX + w / 2f,
                        loc[1] - renderer.winY + h / 2f,
                        Math.max(24f, w / 4f),
                        Math.max(24f, h / 4f)
                };
            }
        }
        return new float[]{ renderer.ballCxW, renderer.ballCyW,
                Math.max(24f, renderer.ballHxW), Math.max(24f, renderer.ballHyW) };
    }

    /** 有字时把动画挪到球底「动效带」，没字时回到球心（文字和动画不打架）。 */
    private void placeAnimForText(String anim, boolean text) {
        if (renderer == null) return;
        boolean orb = AnimScene.A_DOTS.equals(anim) || AnimScene.A_FLUID.equals(anim);
        boolean cap = AnimScene.isCap(anim);
        if (text && orb) {
            float halfH = winSize * 0.9f * Math.max(0.05f, tHalfY) * 0.5f;
            float depthPerNy = Math.max(dp(40), halfH * 0.85f);
            float wantNy = Math.min(0.85f, (siriAnimBand * 0.5f) / depthPerNy);
            orbOffsetTarget = Math.max(0.05f, wantNy);
            renderer.loaderDyPx = 0f;
            renderer.loaderBandPx = 0f;
        } else if (text && cap) {
            // ★ 有字：点阵改成「只在文字下面那条动效带里」布点。
            //   以前只把整团点阵往下挪 45% 带高，方形态里照样铺满整块玻璃、压在字上
            //   —— 那就是用户说的「回复时动画在方形玻璃里根本不像设置里选的那个」。
            orbOffsetTarget = Math.max(-0.06f, Math.min(0.06f, renderer.orbOffsetDefault));
            renderer.loaderDyPx = 0f;
            renderer.loaderBandPx = Math.max(0f, siriAnimBand);
        } else {
            orbOffsetTarget = Math.max(-0.06f, Math.min(0.06f, renderer.orbOffsetDefault));
            renderer.loaderDyPx = 0f;
            renderer.loaderBandPx = 0f;
        }
    }

    /** 每帧（限频 100ms）看一次「槽 / 动画」有没有变。 */
    private void pumpScene() {
        pumpMenuHideProbe();                        // ★ 菜单界面：动画藏不藏的临时开关
        pumpReplyTextHide();                        // ★ AI 回复的字别出现在菜单 / 胶囊里
        if (renderer == null) {
            sceneLastCheck = android.os.SystemClock.uptimeMillis();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sceneLastCheck < 100) return;
        sceneLastCheck = now;
        String slot = resolveSlot();
        String anim = AnimScene.master(animPrefs())
                ? AnimScene.binding(animPrefs(), slot) : legacyAnimFor(slot);
        anim = effectiveAnim(slot, anim);          // ★ 菜单界面：动画暂时藏起来
        // ★ 2026-11-08：除了「槽/动画号变了」，还要看**渲染器有没有被别人改回去**。
        //   以前只在槽号变化时才推一次，于是任何一处老代码（文字清空、旧 think_loader、
        //   启动时那几行）把 waveMode/loaderMode 写回去之后，球就**一直**停在那个画面上，
        //   状态再变也不会重推 —— 用户看到的就是「不管什么状态都是同一个动画」。
        boolean textNow = hasBallText();
        // 动效带的高度是**文字量出来之后**才有的（siriAnimBand 在文字布局里算）。
        // 只在「有字翻转」那一刻摆一次是不够的：字刚出现时带高还是 0，
        // 于是方形态里点阵又铺回整颗球（实测踩到过：带=0.0px）。带高一变就重摆。
        if (textNow != sceneTextLast || (textNow && siriAnimBand != sceneBandLast)) {
            // 字出现 / 消失 → 动画该在球心还是球底那条带子里，是两套摆法（见 placeAnimForText）
            sceneTextLast = textNow;
            sceneBandLast = siriAnimBand;
            placeAnimForText(sceneAnim, textNow);
            Log.i("FloatingBall", "有字=" + textNow + " → 动画重新摆位（槽=" + sceneSlot
                    + " 动画=" + sceneAnim + " 带=" + renderer.loaderBandPx + "px）");
        }
        if (sceneDirty || !slot.equals(sceneSlot) || !anim.equals(sceneAnim)
                || (!previewingScene() && !specialSurfaceActive() && animDrifted(anim))) {
            if (!sceneDirty && !previewingScene() && !specialSurfaceActive() && animDrifted(anim)) {
                Log.i("FloatingBall", "动画被旁路改掉 → 按槽重推（槽=" + slot + " 动画=" + anim
                        + " 实际 loader=" + renderer.loaderMode + " waveMode=" + renderer.waveMode
                        + " waveOn=" + renderer.waveOn + "）");
            }
            refreshScene(false);
        }
    }

    /**
     * 这次跑的是不是「命令」。
     *
     * DSH 的 tool/call 载荷里带 `data.name`（实测：bash / read / edit / grep …）。
     * 名字缺失时按命令算 —— 那是这条链路以前的行为，不能因为解析不到就换槽。
     */
    private boolean toolIsCommand() {
        String n = asstToolName;
        if (n == null || n.isEmpty()) return true;
        n = n.toLowerCase(java.util.Locale.US);
        return n.equals("bash") || n.equals("shell") || n.equals("sh") || n.equals("exec")
                || n.equals("run") || n.equals("terminal") || n.equals("command");
    }

    /** 工具真的在跑（带 10 分钟兜底：result 丢了也不会永远卡在「执行中」）。 */
    private boolean toolRunningNow() {
        if (!asstToolRunning) return false;
        if (toolSinceMs <= 0) return true;
        return android.os.SystemClock.uptimeMillis() - toolSinceMs < TOOL_STUCK_MS;
    }

    private static final long TOOL_STUCK_MS = 10 * 60 * 1000;
    private long toolSinceMs = 0;

    // ── 「最终回复」的确认闸门 ─────────────────────────────────────────────
    // 用户口径（2026-11-08）：
    //   过程中回复 = 这段回复之后还有活要干；最终回复 = 回复之后会话就停了。
    // DSH 每个 step 都会先发 turn/end 再接着干（tool 是异步 job），所以 turn/end 本身
    // 区分不了「说完这句还要继续」和「说完了，收工」。这里等 FINAL_CONFIRM_MS 再查
    // 一次 session/list 的 running：还在跑 → 过程中回复；停了 → 最终回复。
    private static final long FINAL_CONFIRM_MS = 1200;
    /** 最多追这么多次（≈18s）：还没停就按"过程中回复"收手，别一直耗着。 */
    private static final int FINAL_CHECK_MAX = 12;
    private volatile boolean replySettledPending = false;
    private volatile boolean replyFinal = false;
    private Runnable finalConfirm;
    private int finalCheckAttempt = 0;
    private volatile String asstSessionId = null;
    /**
     * 会话卡是不是正占着球内文字。
     *
     * 卡里显示的是**整段会话记录**（你 / AI 一段段排），而镜像那条路是"逐字把当前回答打进球里"——
     * 两者同时写就是：字刚排好一段又被逐字覆盖、记录跳来跳去。卡开着就让卡自己轮询，
     * 镜像这条通道让路（球的动画/状态完全不受影响，SiriAssistant 只是不写字）。
     */
    public static boolean conversationCardOwnsText() {
        FloatingBallService s = INSTANCE;
        return s != null && s.convoOpen && !s.convoStashed;
    }

    /** 当前球里这段回复是**哪条会话**产的（最终回复要查的是它，不是镜像随手跟的那条）。 */
    private volatile String replySessionId = null;

    /** 球现在跟的是哪条会话（问 running 用）。提问 / 镜像跟随时都会更新。 */
    public static void setAssistantSession(String sid) {
        FloatingBallService s = INSTANCE;
        if (s == null || sid == null || sid.isEmpty()) return;
        if (sid.equals(s.asstSessionId)) return;
        final String prev = s.asstSessionId;
        s.asstSessionId = sid;
        // 换了一条会话：上一条的「回复判定 / 工具状态」都不属于这条，必须清掉，
        // 否则新的会话会带着别人的"还在跑"或"工具在跑"显示。
        if (prev != null && !prev.equals(sid)) {
            s.main.post(new Runnable() {
                @Override public void run() {
                    if (!sid.equals(s.asstSessionId)) return;
                    s.replySettledPending = false;
                    s.replyFinal = false;
                    s.cancelFinalConfirm();
                    s.asstToolRunning = false;
                    s.toolSinceMs = 0;
                    s.asstToolName = null;
                    s.sceneDirty = true;
                    s.refreshScene(true);
                    Log.i("FloatingBall", "会话切换 " + prev.substring(0, Math.min(13, prev.length()))
                            + " → " + sid.substring(0, Math.min(13, sid.length())) + "（清掉上一条的判定）");
                }
            });
        }
    }

    /** 这段回复来自哪条会话（提问流 / 镜像的文字与 turn/end 都会登记）。 */
    public static void setAssistantReplySession(String sid) {
        FloatingBallService s = INSTANCE;
        if (s == null || sid == null || sid.isEmpty()) return;
        s.replySessionId = sid;
    }

    /** 这条会话的回复还在"待确认最终"或任务还没跑完 → 镜分别急着切走。 */
    public static boolean isReplySettling() {
        FloatingBallService s = INSTANCE;
        return s != null && (s.asstTaskActive || s.replySettledPending);
    }

    /** 事件是不是属于球当前显示的那条会话（不属于就别拿它改球的状态）。 */
    private static boolean belongsToShownSession(String sid) {
        FloatingBallService s = INSTANCE;
        if (s == null) return false;
        String cur = s.asstSessionId;
        return sid == null || cur == null || sid.equals(cur);
    }

    /** null = 查不到（别拿「查不到」冒充「已经停了」，那会把过程中回复误判成最终回复）。 */
    private Boolean sessionRunning(String sid) {
        if (sid == null || sid.isEmpty()) return null;
        try {
            for (DshApi.SessionRow r : DshApi.get(this).sessions()) {
                if (sid.equals(r.id)) return r.running;
            }
            return false;                      // 列表里没有这条 = 会话没了
        } catch (Throwable t) {
            Log.w("FloatingBall", "查会话状态失败：" + message(t));
            return null;
        }
    }

    /**
     * turn/end（或本地流收完）时进来：**立刻**认定这段回复是最终回复。
     *
     * 为什么不再去查 running（2026-11-08 用户连着两次实测后的口径）：
     *   「回话停止了才算最终回复」——回话（这段输出）停止的那一刻就是它；
     *   而 DSH 的 session running 在收尾阶段还会挂一会儿，用它当判据的结果就是
     *   球永远停在过程中回复、Siri 波一次都不出。
     * 后面**真的**还有动作（tool/call、job、新 step、新的一轮）→ noteReplyActivity()
     * 会把状态退回过程中回复。所以这里是"先当最终，有活再退"，不是一锤定音。
     */
    private void markReplySettled() {
        cancelFinalConfirm();
        replySettledPending = false;
        replyFinal = true;
        sceneDirty = true;
        refreshScene(true);
        refreshFinalWave();
        Log.i("FloatingBall", "回话停止 → 最终回复（后面若再来工具会自动退回过程中回复）");
    }

    private void cancelFinalConfirm() {
        if (finalConfirm != null) {
            main.removeCallbacks(finalConfirm);
            finalConfirm = null;
        }
    }

    /** 又来了新动静（新 step / 工具 / 新的一轮）→ 刚才那句就不是最终回复了。 */
    private void noteReplyActivity(String why) {
        if (!replySettledPending && !replyFinal) return;
        replySettledPending = false;
        replyFinal = false;
        cancelFinalConfirm();
        sceneDirty = true;
        refreshScene(true);
        Log.i("FloatingBall", "又有活动（" + why + "）→ 回到过程中回复");
    }

    /** 正在预览（钉住某个槽）时不做漂移自检：预览就是要盖住真实状态。 */
    private boolean previewingScene() {
        return previewSlot != null;
    }

    /**
     * 这些界面由自己的玻璃层接管绘制（审批卡片 / 搜索胶囊 / 六区 / 菜单面板全屏），
     * 它们会**有意**关掉波、改几何，不能拿球槽的期望值去纠它。
     */
    private boolean specialSurfaceActive() {
        return approvalShown || searchActive || zonesActive || glassFullscreen;
    }

    /**
     * 渲染器当前画的东西，和「这个槽该给的动画」对不对得上。
     *
     * 只在**没有正在进行的淡出切换**时判定（sceneCommit / waveModePending 挂起时，
     * 老形态还留在屏幕上是对的，那是包络过渡，不是漂移）。
     */
    private boolean animDrifted(String anim) {
        if (renderer == null) return false;
        if (sceneCommit != null || waveModePending >= 0) return false;
        if (AnimScene.isCap(anim)) {
            return renderer.loaderMode != AnimScene.capIndex(anim)
                    || !renderer.suppressWave || !renderer.waveOn;
        }
        if (AnimScene.A_OFF.equals(anim)) return renderer.waveOn || renderer.loaderMode != 0;
        if (AnimScene.A_WAVE.equals(anim)) {
            return renderer.waveMode != 1 || !renderer.waveOn || renderer.suppressWave
                    || renderer.loaderMode != 0;
        }
        if (AnimScene.A_DOTS.equals(anim)) {
            return renderer.waveMode != 2 || !renderer.waveOn || renderer.suppressWave
                    || renderer.loaderMode != 0 || orbMixTarget < 0.99f;
        }
        if (AnimScene.A_FLUID.equals(anim)) {
            return renderer.waveMode != 3 || !renderer.waveOn || renderer.suppressWave
                    || renderer.loaderMode != 0 || orbMixTarget < 0.99f;
        }
        return false;
    }

    /** 设置页改了绑定/参数：立刻重推（悬浮球没开就只是存着，下次启动生效）。 */
    public static void notifySceneChanged() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                s.sceneDirty = true;
                s.refreshScene(true);
            }
        });
    }

    /**
     * 设置页「预览」：把球钉在某个槽上（空字符串 = 结束预览）。
     *
     * ★ 2026-11-08 修「不管什么状态都只显示同一个动画」：
     *   预览原来是**永久**生效的 —— 只有点 ■ 才解除。而设置页里「给某个状态挑动画」
     *   每挑一次就顺手 preview(那个槽)，于是最后一个动过的槽（多半是「空闲 / 待机」）
     *   就永远钉在球上；resolveSlot() 第一行 `if (previewSlot != null) return previewSlot;`
     *   直接把真实状态短路掉 → 思考 / 回复 / 最终回复全是待机那一套。
     *   现在预览是**限时**的：PREVIEW_TTL_MS 之后自己解除，回到真实状态；
     *   再点一次预览 / 再改一个绑定会重新计时。
     */
    public static void setScenePreview(String slot) {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        final String v = (slot == null || slot.trim().isEmpty()) ? null : slot.trim();
        s.main.post(new Runnable() {
            @Override public void run() {
                s.previewSlot = v;
                s.sceneDirty = true;
                s.refreshScene(true);
                if (v == null) {
                    s.cancelPreviewExpire();
                    Log.i("FloatingBall", "预览槽 → 结束（回到真实状态 " + s.resolveSlot() + "）");
                } else {
                    s.armPreviewExpire();
                    Log.i("FloatingBall", "预览槽 → " + v + "（" + (PREVIEW_TTL_MS / 1000)
                            + "s 后自动结束）");
                }
            }
        });
    }

    private static final long PREVIEW_TTL_MS = 20000;
    private Runnable previewExpire;

    private void armPreviewExpire() {
        cancelPreviewExpire();
        previewExpire = new Runnable() {
            @Override public void run() {
                previewExpire = null;
                if (previewSlot == null) return;
                final String was = previewSlot;
                previewSlot = null;          // 先松钉，再算真实状态（否则日志里还是预览那个）
                Log.i("FloatingBall", "预览到点 → 自动结束（槽 " + was
                        + "），回到真实状态 " + resolveSlot());
                sceneDirty = true;
                refreshScene(true);
            }
        };
        main.postDelayed(previewExpire, PREVIEW_TTL_MS);
    }

    private void cancelPreviewExpire() {
        if (previewExpire != null) {
            main.removeCallbacks(previewExpire);
            previewExpire = null;
        }
    }

    /** 给设置页/日志看：现在到底在跑哪个槽、哪个动画。 */
    public static String sceneStateJson() {
        FloatingBallService s = INSTANCE;
        if (s == null) return "{\"running\":false}";
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("running", true);
            o.put("slot", s.sceneSlot);
            o.put("anim", s.sceneAnim);
            o.put("preview", s.previewSlot == null ? "" : s.previewSlot);
            o.put("master", AnimScene.master(s.animPrefs()));
            o.put("gate", s.renderer == null ? 0 : s.renderer.waveGate);
            o.put("loader", s.renderer == null ? 0 : s.renderer.loaderMode);
            o.put("waveMode", s.renderer == null ? 0 : s.renderer.waveMode);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    /** 外部（DSH 实际发来工具事件时）可以显式标「执行中」。 */
    public static void setAssistantToolRunning(boolean running) {
        setAssistantToolRunning(running, null);
    }

    /** 球自己那一轮提问开始 / 结束（由 SiriAssistant 调）。 */
    public static void setAssistantTaskActive(final boolean active) {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                if (s.asstTaskActive == active) return;
                s.asstTaskActive = active;
                s.sceneDirty = true;
                s.refreshScene(true);
                Log.i("FloatingBall", "任务在跑=" + active + " → 槽 " + s.resolveSlot());
            }
        });
    }

    /**
     * @param toolName DSH 的 `tool/call.data.name`（bash / read / edit …）；null = 沿用上一次的名字。
     */
    public static void setAssistantToolRunning(boolean running, final String toolName) {
        setAssistantToolRunning(running, toolName, null);
    }

    public static void setAssistantToolRunning(boolean running, final String toolName, final String sessionId) {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        if (!belongsToShownSession(sessionId)) {
            Log.i("FloatingBall", "忽略别的会话的工具事件（" + (sessionId == null ? "?" :
                    sessionId.substring(0, Math.min(13, sessionId.length()))) + "）");
            return;
        }
        s.main.post(new Runnable() {
            @Override public void run() {
                boolean nameChanged = toolName != null && !toolName.isEmpty()
                        && !toolName.equals(s.asstToolName);
                if (s.asstToolRunning != running || nameChanged) {
                    s.asstToolRunning = running;
                    if (running) {
                        if (toolName != null && !toolName.isEmpty()) s.asstToolName = toolName;
                        s.toolSinceMs = android.os.SystemClock.uptimeMillis();
                    } else {
                        s.asstToolName = null;
                        s.toolSinceMs = 0;
                    }
                    s.sceneDirty = true;
                    s.refreshScene(true);
                    if (running) {
                        s.noteReplyActivity("工具开始跑");
                        SiriAssistant.noteToolActivity();
                    }
                    Log.i("FloatingBall", "工具在跑=" + running
                            + (running ? "（" + s.asstToolName + "）" : "")
                            + " → 槽 " + s.resolveSlot());
                }
            }
        });
    }

    /** 助手接管前的波形设置存档（只存一次）。 */
    private void saveWaveOnce() {
        if (waveSaved || renderer == null) return;
        waveOnSaved = renderer.waveOn;
        waveModeSaved = renderer.waveMode == 0 ? 1 : renderer.waveMode;
        waveSaved = true;
    }

    // ── 球内文字「钉住」：用户点一下文字就保持住，再点一下解除干预 ──────────────
    // 助手侧（SiriAssistant.quietTick）会看这个标志：钉住时不做安静清空。
    private volatile boolean asstTextPinned = false;
    private long lastTextTapAt = 0;
    private final Runnable togglePin = new Runnable() {
        @Override public void run() { setAssistantPinned(!asstTextPinned); }
    };

    /** 球内文字是否被用户钉住（助手据此暂停自动清空）。 */
    public static boolean isAssistantTextPinned() {
        FloatingBallService s = INSTANCE;
        return s != null && s.asstTextPinned;
    }

    /** 钉住 / 解除钉住球内文字。 */
    public static void setAssistantPinned(final boolean pinned) {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(() -> s.applyAssistantPinned(pinned));
    }

    private void applyAssistantPinned(boolean pinned) {
        if (asstTextPinned == pinned) return;
        asstTextPinned = pinned;
        main.removeCallbacks(togglePin);
        // 钉住状态不再往正文里插图钉（原生渲染下没必要，还会破坏排版）；
        // 视觉提示由球顶抓取条的抓取标记/日志承担。这里只需让助手侧知道"别清空"。
        mdBuiltFor = "";          // 重建一次（displayTextOf 可能带钉住标记）
        rebuildMarkdown();
        if (!pinned) armQuietFromBall();      // 解除干预 → 安静计时重新开始，该消失就消失
        Log.i("FloatingBall", "assistant text " + (pinned ? "PINNED（不再自动清空）" : "unpinned（恢复自动清空）"));
    }

    /** 用户解除钉住后，让助手侧重新开始安静计时（该消散就消散）。 */
    private void armQuietFromBall() {
        SiriAssistant a = SiriAssistant.peek();
        if (a != null) a.rearmQuietPublic();
    }

    /** 球当前是否处于 Siri 形变模式（助手据此决定要不要先打开）。 */
    public static boolean isSiriOn() {
        FloatingBallService s = INSTANCE;
        return s != null && s.siriEnabled;
    }

    /** 球内此刻显示的 Siri 文字（供设置页回读，确认回答真的写进去了）。 */
    public static String siriTextNow() {
        FloatingBallService s = INSTANCE;
        return s == null ? "" : (s.siriStr == null ? "" : s.siriStr);
    }


    // ═══════════════════════════════════════════════════════════════════
    //  审批 / 提问面板：DSH 要你点头或回答时，**同一颗球**变成可交互的玻璃卡片
    //
    //  参考苹果那种卡片：外壳还是这颗水晶球（折射 / 黑顶 / 赤道亮线 / 底缘，
    //  不带 Siri 波），壳**里面**按 DSH 给的内容自适应排：
    //
    //    审批（DSH 只有这两种，协议上写死）：
    //      ┌──────────────────────────────┐
    //      │ bash                         │ ← 标题（工具名）
    //      │ 这个命令要在沙箱之外执行…      │ ← 说明（reason / displayReason）
    //      │ ┌──────────────┐             │
    //      │ │    批准       │             │ ← 选项：绿色，点一下直接答
    //      │ └──────────────┘             │
    //      │ ┌──────────────┐             │
    //      │ │    拒绝       │             │ ← 红色
    //      │ └──────────────┘             │
    //      └──────────────────────────────┘
    //
    //    提问（ask_user_question，**千变万化**）：
    //      ┌──────────────────────────────┐
    //      │ 选择运行模式                   │ ← header / 题干
    //      │ 打开 QQ 需要操作屏幕…          │
    //      │ ┌──────────────────────────┐ │
    //      │ │ ① 前台 Foreground（推荐）  │ │ ← 选项数量/文案/多选都由 DSH 决定
    //      │ │   在物理屏幕上显示…        │ │ ← description 也照排
    //      │ └──────────────────────────┘ │
    //      │ ┌──────────────────────────┐ │
    //      │ │ ② 后台 Background         │ │
    //      │ └──────────────────────────┘ │
    //      │ ┌──────────────────────────┐ │
    //      │ │ 或者自己写点什么…   [发送] │ │ ← 自由输入 = 协议里的 custom
    //      │ └──────────────────────────┘ │
    //      └──────────────────────────────┘
    //
    //  协议（对着 DSH 源码抄的，别自己发明）：
    //    · 审批的 outcome 只有 `allowed-once` / `rejected` 两个值，就在
    //      `dsh-client-ui-approval` 的 locales（allowOnce / reject）与
    //      `PendingApproval.answer(outcome)` 里，没有第三种。
    //    · 提问走同一条瀑布，**回的是答案对象**而不是字符串：
    //        { answers: [ { id, selected: [label…], custom?: "自由文本" } ] }
    //      单选 + 写了自由文本时，客户端会把 selected 清空（见
    //      dsh-client-ui-user-questions/lib/client.js 里 `selected: custom === "" … ? value.selected : []`）。
    //      所以这里照抄这个语义。
    //    · 多选（multiSelect）时 selected 可以有多个，且可以与 custom 并存。
    //
    //  关于「不跳」和「点得动」：几何只在档位变化时算一次，内层两个窗口只在实测值
    //  变化时 update；按钮是壳内普通 View + OnClickListener，不靠命中判定。
    // ═══════════════════════════════════════════════════════════════════

    /** 面板正挂在球上（球此刻显示审批/提问内容，不是 Siri 文字）。 */
    private boolean approvalShown = false;
    private boolean approvalClosing = false;
    private DshApprovalClient.Request approvalReq;

    // 壳内：文字块（标题/说明/题干）
    private android.widget.LinearLayout approvalContent;
    private WindowManager.LayoutParams approvalContentParams;
    private android.widget.TextView approvalBodyView;
    private boolean approvalBodyClipped = false;
    // 壳内：交互层（选项 + 自由输入 + 发送）
    private android.widget.LinearLayout approvalActions;
    /** 选项多/文案长时，交互层自己滚 —— 绝不能压到上面的文字块上去。 */
    private android.widget.ScrollView approvalActionsScroll;
    private WindowManager.LayoutParams approvalActionsParams;
    /**
     * 自由输入行**单独一个悬浮窗**。原因：按钮那个窗口带 FLAG_NOT_FOCUSABLE，
     * 窗口本身拿不到焦点 → 里面的 EditText 永远收不到输入法（用户报「点输入框无效」）。
     * 所以输入行拆出来，去掉 NOT_FOCUSABLE，并允许 IME 调整尺寸。
     */
    private android.widget.LinearLayout approvalInputRow;
    private WindowManager.LayoutParams approvalInputParams;
    private int apInputW, apInputH, apInputY;
    private boolean apImeUp = false;
    private android.widget.EditText approvalInput;
    private android.widget.TextView approvalSend;
    /** 选项按钮：label / 选中态 / 视图。 */
    private final java.util.List<View> approvalOptViews = new java.util.ArrayList<View>();
    private final java.util.List<android.widget.TextView> approvalOptLabels =
            new java.util.ArrayList<android.widget.TextView>();
    private final java.util.List<String> approvalOptValues = new java.util.ArrayList<String>();
    private final java.util.List<Boolean> approvalOptChecked = new java.util.ArrayList<Boolean>();
    private final java.util.List<Integer> approvalOptAccents = new java.util.ArrayList<Integer>();
    private int approvalSingleQid = -1;        // 单选时锁定的题号（本面板一次只答第一题）

    // 审批面板**自己的一套**几何目标（单独一组，绝不与 Siri 那条路共用）。
    // 共用会出事：镜像打字 / 静默清空 / 新一轮提问任一调 measureSiriTargets()，
    // 就会把 tHalfX/tHalfY 改回小球，而面板内容还挂着 → 内容溢出玻璃（实拍踩到过）。
    private float aprHalfX = 0.37f, aprHalfY = 0.25f, aprCorner = 1f, aprBlack = 0f;

    // 几何缓存
    private String apGeomKey = "";
    private int apTextW, apTextH, apActW, apActH;
    private boolean apGeomDirty = true;
    private int apLayoutKey = Integer.MIN_VALUE;

    // 拖动
    private View approvalTouch;
    private WindowManager.LayoutParams approvalTouchParams;
    private float apDownX, apDownY;
    private int apStartX, apStartY;
    private boolean apDragging;

    // 原球存档
    private boolean apSaved = false;
    private int apSaveWinW, apSaveWinH, apSaveX, apSaveY;
    private float apSaveTHalfX, apSaveTHalfY, apSaveTCorner, apSaveTBlack, apSaveTMaster;
    private float apSaveAHalfX, apSaveAHalfY, apSaveACorner, apSaveABlack, apSaveAMaster;
    private boolean apSaveWaveOn, apSaveSiriGeom;

    /**
     * 面板整体缩放：字号 / 按钮 / 内边距 / 间距全部乘这个系数（等比）。
     * 用户反馈默认尺寸偏大（「整个界面和字体等比例缩小一半会好点」），所以给一个总开关，
     * 想再大再小只改这一个数。
     */
    private static final float AP_SCALE = 0.55f;

    private int apPadX, apPadY, apGap, apTitlePx, apBodyPx, apOptPadX, apOptPadY, apOptGap;
    private static final int AP_PAD_X_DP = 30, AP_PAD_Y_DP = 26;
    private static final int AP_GAP_DP = 14;
    private static final int AP_OPT_GAP_DP = 10;
    private static final int AP_OPT_PAD_X_DP = 18, AP_OPT_PAD_Y_DP = 13;
    private static final int AP_OPT_MIN_H_DP = 46;
    private static final int AP_ACCENT_OK = 0xFF3ED598;
    private static final int AP_ACCENT_NO = 0xFFFF6B6B;
    private static final int AP_ACCENT_ASK = 0xFF6FA8FF;
    /** 面板底：半透明深玻璃，让球本身的折射还能透出来一点（纯黑会像糊了块板子）。 */
    /**
     * 文字块底：**淡淡的暗色**，不是不透明黑面板。
     * 球壳自己就是液态玻璃（折射/色散/黑顶/赤道线），这一层只给文字垫底；
     * 一旦调实（原来 0xD8≈85%），玻璃的折射色散就全看不见了 —— 用户明确要求保留玻璃效果。
     */
    private static final int AP_PANEL_BG = 0x57101419;
    /** 球尽量接近方形：文字块允许的最大宽度 = 屏宽的这个比例。 */
    private static final float AP_TEXT_W_RATIO = 0.60f;

    private void pumpApprovalDebug() {
        SiriAssistant a = SiriAssistant.peek();
        if (a == null) return;
        DshApprovalClient c = a.approvals();
        if (c == null) return;
        // 文件钩子：`files/approval.json` = 一条假审批/提问（JSON 格式同 HTTP 回路）。
        // 为什么不用 HTTP 那条：它固定走 DSH 端口 + 1（3081），那个端口常常被 DSH 自己的
        // 代理占着，喂不进去。文件这条路只依赖 App 自己的外部目录，Termux 能直接写。
        try {
            java.io.File f = new java.io.File(gateDir(), "approval.json");
            if (f.exists()) {
                String json = readSmallString(f, 64 * 1024).trim();
                if (!f.delete() && f.exists()) return;      // 删不掉就别反复消费
                if (!json.isEmpty()) {
                    Log.i("FloatingBall", "approval debug（文件）: " + json.length() + " 字节");
                    c.feedDebugPayload(json);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w("FloatingBall", "approval debug 文件读失败：" + t);
        }
        c.pumpDebugRequest();
    }

    void onApprovalRequest(Object request) {
        if (!(request instanceof DshApprovalClient.Request)) return;
        showApprovalPanel((DshApprovalClient.Request) request);
    }

    void onApprovalWithdraw(String eventId) {
        if (approvalReq == null || eventId == null || !eventId.equals(approvalReq.eventId)) return;
        Log.i("FloatingBall", "approval withdrawn eventId=" + eventId + " → 收面板");
        hideApprovalPanel();
    }

    // ── 出场 / 退场 ────────────────────────────────────────────────────

    private void showApprovalPanel(final DshApprovalClient.Request req) {
        if (wm == null || glView == null || renderer == null) return;
        if (uiHidden) applyUiHidden(false, "审批弹出");   // 被音量加藏起来时，审批来了要自己回来
        if (approvalShown && approvalReq != null && req.eventId != null
                && req.eventId.equals(approvalReq.eventId)) return;
        // 搜索胶囊开着时不能直接弹面板：两者都靠"同一颗球"的几何，同屏会互相改尺寸。
        // 先收胶囊、等它彻底交还给球，再把面板弹出来（DSH 那边等这一秒没问题）。
        if (searchActive || searchClosing) {
            Log.i("FloatingBall", "审批来了，但搜索胶囊开着 → 先收胶囊，稍后再弹面板");
            closeSearchMode("审批来了");
            main.postDelayed(new Runnable() {
                @Override public void run() { showApprovalPanel(req); }
            }, 1600);   // 等胶囊收完（真机 ~1.5s）；早弹会把面板按全屏窗口算成巨大一张
            return;
        }

        saveOriginalBall();
        approvalReq = req;
        approvalClosing = false;
        renderer.waveOn = false;          // 面板不带 Siri 波（退出时按存档还原）

        float d = getResources().getDisplayMetrics().density * apScale();   // 面板整体等比缩放
        apPadX = Math.round(AP_PAD_X_DP * d);
        apPadY = Math.round(AP_PAD_Y_DP * d);
        apGap = Math.round(AP_GAP_DP * d);
        apTitlePx = Math.round(19f * d);
        apBodyPx = Math.round(14f * d);
        apOptPadX = Math.round(AP_OPT_PAD_X_DP * d);
        apOptPadY = Math.round(AP_OPT_PAD_Y_DP * d);
        apOptGap = Math.round(AP_OPT_GAP_DP * d);

        approvalShown = true;
        applyBallTouchability();          // 面板期间球层让开：玻璃外面那一圈别再吃点击
        // 面板模式：黑区不是死黑 —— 底部透强调色并带光晕（审批=暖红，提问=蓝）
        if (renderer != null) {
            boolean appr = req.isApproval();
            renderer.panelMode = 1f;
            renderer.panelAccentR = appr ? 1.00f : 0.42f;
            renderer.panelAccentG = appr ? 0.28f : 0.66f;
            renderer.panelAccentB = appr ? 0.22f : 1.00f;
        }
        apGeomDirty = true;
        apGeomKey = "";
        apLayoutKey = Integer.MIN_VALUE;
        buildApprovalViews(req);
        applyApprovalGeometry();
        ensureApprovalWindows();
        applyApprovalGeometry();     // ensureApprovalWindows 之后面板尺寸才算定，再算一次
        updateApprovalWindow();      // 球变大了，窗口必须立刻跟上，否则球面/内容一起被裁
        Log.i("FloatingBall", "approval panel up: " + req.title()
                + " kind=" + (req.isApproval() ? "approval" : "question")
                + " text=" + apTextW + "x" + apTextH
                + " actions=" + apActW + "x" + apActH
                + " half=" + round3(aprHalfX) + "x" + round3(aprHalfY)
                + " corner=" + round3(aprCorner) + " black=" + round3(aprBlack)
                + " options=" + approvalOptValues.size()
                + (approvalInput != null ? " +输入框" : "")
                + (approvalBodyClipped ? " [截断]" : ""));
    }

    private void hideApprovalPanel() {
        if (!approvalShown) { approvalReq = null; return; }
        hideKeyboard();
        approvalShown = false;
        approvalClosing = true;
        approvalReq = null;
        applyBallTouchability();          // 交还给球：代理层重新接管球上的手势
        if (renderer != null) renderer.panelMode = 0f;   // 交还给普通球（纯黑区）
        hideApprovalWindows();
        // 收球动画：这一次才允许动 Siri 那套目标（面板已不再需要它们）
        tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
        main.postDelayed(new Runnable() {
            @Override public void run() { finishApprovalClose(); }
        }, 280);
    }

    private void finishApprovalClose() {
        if (!approvalClosing) return;
        approvalClosing = false;
        removeApprovalViews();
        restoreOriginalBall();
        Log.i("FloatingBall", "approval panel down · 原球已复原");
    }

    private void saveOriginalBall() {
        if (apSaved) return;
        apSaved = true;
        apSaveWinW = winW; apSaveWinH = winH;
        apSaveX = params == null ? 0 : params.x;
        apSaveY = params == null ? 0 : params.y;
        apSaveTHalfX = tHalfX; apSaveTHalfY = tHalfY; apSaveTCorner = tCorner;
        apSaveTBlack = tBlack; apSaveTMaster = tMaster;
        apSaveAHalfX = aHalfX; apSaveAHalfY = aHalfY; apSaveACorner = aCorner;
        apSaveABlack = aBlack; apSaveAMaster = aMaster;
        apSaveWaveOn = renderer != null && renderer.waveOn;
        apSaveSiriGeom = renderer != null && renderer.siriGeom;
    }

    private void restoreOriginalBall() {
        if (!apSaved) return;
        apSaved = false;
        if (params != null && wm != null) {
            winW = apSaveWinW; winH = apSaveWinH;
            params.width = apSaveWinW; params.height = apSaveWinH;
            params.x = apSaveX; params.y = apSaveY;
            try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        }
        tHalfX = apSaveTHalfX; tHalfY = apSaveTHalfY; tCorner = apSaveTCorner;
        tBlack = apSaveTBlack; tMaster = apSaveTMaster;
        aHalfX = apSaveAHalfX; aHalfY = apSaveAHalfY; aCorner = apSaveACorner;
        aBlack = apSaveABlack; aMaster = apSaveAMaster;
        if (renderer != null) {
            renderer.waveOn = apSaveWaveOn;
            renderer.siriGeom = apSaveSiriGeom;
        }
        if (siriEnabled) measureSiriTargets(siriStr);
        syncSiriTouchLayer();
    }

    // ── 壳里的内容 ────────────────────────────────────────────────────

    private void buildApprovalViews(DshApprovalClient.Request req) {
        removeApprovalViews();
        if (wm == null) return;
        float d = getResources().getDisplayMetrics().density;
        int textMaxW = approvalMaxContentW();

        // ① 文字块
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(apPadX, apPadY, apPadX, apPadY);
        box.setBackground(panelBackground());

        android.widget.TextView apTitle = addText(box, req.title(), apTitlePx, 0xFFF2F5FA,
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD), 3, textMaxW);
        apTitle.setShadowLayer(dp(7), 0f, dp(1), 0xE0000000);   // 底变透后靠阴影保可读
        String body = approvalBodyText(req);
        if (body != null && !body.trim().isEmpty()) {
            approvalBodyView = addText(box, body.trim(), apBodyPx, 0xFFAEB8C6,
                    android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL),
                    12, textMaxW);
            approvalBodyView.setShadowLayer(dp(7), 0f, dp(1), 0xE0000000);
            ((android.widget.LinearLayout.LayoutParams) approvalBodyView.getLayoutParams()).topMargin = apGap;
        }
        approvalContent = box;
        approvalContentParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        makeTouchTransparent(box);   // 面板底只画不摸（不用 FLAG_NOT_TOUCHABLE，见该方法注释）

        // ② 交互层：选项 + 自由输入 + 发送
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        if (req.isApproval()) {
            addOption(col, "批准", "", AP_ACCENT_OK, "allowed-once");
            addOption(col, "拒绝", "", AP_ACCENT_NO, "rejected");
        } else {
            approvalSingleQid = 0;
            for (int qi = 0; qi < req.questions.size(); qi++) {
                DshApprovalClient.Question q = req.questions.get(qi);
                boolean multi = q.isMultiSelect();
                if (qi > 0 && !q.question.isEmpty()) {
                    android.widget.TextView tv = addText(col, q.question, apBodyPx, 0xFF9AA5B4,
                            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD),
                            3, textMaxW);
                    ((android.widget.LinearLayout.LayoutParams) tv.getLayoutParams()).topMargin = apGap;
                }
                for (int i = 0; i < q.labels.size(); i++) {
                    String label = q.labels.get(i);
                    String desc = i < q.descriptions.size() ? q.descriptions.get(i) : "";
                    // 多选 = 复选框；一批多题时单选也要能看出「已点」= 单选框；
                    // 只有「单题单选」才保留 1./2. 的编号（点了就走，无需交卷）
                    String prefix = multi ? "☐ " : (batchMode() ? "○ " : (i + 1) + ". ");
                    addOption(col, prefix + label, desc, AP_ACCENT_ASK,
                            q.id + "\u0000" + label);
                }
            }
        }

        // 自由输入（协议里的 custom）：提问一定有，审批没有
        if (!req.isApproval()) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(optionBackground(0xCCFFFFFF));   // 实色浅底（原 0x22 几乎全透）
            row.setPadding(apOptPadX, Math.round(apOptPadY * 0.6f), Math.round(apOptPadX * 0.5f),
                    Math.round(apOptPadY * 0.6f));
            android.widget.LinearLayout.LayoutParams rowLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = apOptGap;

            approvalInput = new android.widget.EditText(this);
            approvalInput.setHint("或者在这里自己写…");
            approvalInput.setHintTextColor(0x8AFFFFFF);
            approvalInput.setTextColor(0xFFF2F5FA);
            approvalInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f * d * AP_SCALE);
            approvalInput.setBackgroundColor(0x00000000);
            approvalInput.setPadding(0, Math.round(6 * d), Math.round(8 * d), Math.round(6 * d));
            approvalInput.setMaxLines(4);
            approvalInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            approvalInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            approvalInput.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(android.widget.TextView v, int actionId,
                                                        android.view.KeyEvent e) {
                    submitCustomAnswer();
                    return true;
                }
            });
            row.addView(approvalInput, new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            approvalSend = new android.widget.TextView(this);
            approvalSend.setText("发送");
            approvalSend.setTextColor(0xFFF4F8FF);
            approvalSend.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f * d * AP_SCALE);
            approvalSend.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            approvalSend.setGravity(Gravity.CENTER);
            approvalSend.setPadding(apOptPadX, Math.round(9 * d), apOptPadX, Math.round(9 * d));
            approvalSend.setBackground(optionBackground(AP_ACCENT_ASK));   // 实色
            approvalSend.setClickable(true);
            approvalSend.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { submitCustomAnswer(); }
            });
            row.addView(approvalSend);
            approvalInputRow = row;      // 不进 col：它有自己的窗口（要焦点）
        }

        approvalActions = col;
        approvalActionsScroll = new android.widget.ScrollView(this);
        approvalActionsScroll.setVerticalScrollBarEnabled(false);
        approvalActionsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 选项多于放得下的时候这里是滚的：上下边缘加一层淡出，别让最后一行**硬生生被切一半**
        // （用户 2026-11-06 让压测多选项面板，切一半看着像坏了）。
        approvalActionsScroll.setVerticalFadingEdgeEnabled(true);
        approvalActionsScroll.setFadingEdgeLength(Math.round(dp(26)));
        approvalActionsScroll.addView(col, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        approvalActionsParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        if (approvalInputRow != null) {
            // 故意**不带** FLAG_NOT_FOCUSABLE：窗口能拿焦点，EditText 才能拉起输入法。
            // ADJUST_RESIZE 让键盘弹出时我们收到 inset，好把面板整体上移。
            approvalInputParams = overlayParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            approvalInputParams.softInputMode =
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            // 键盘顶起来时把面板整体抬上去，别让输入框被键盘盖住
            approvalInputRow.setOnApplyWindowInsetsListener(
                    new View.OnApplyWindowInsetsListener() {
                        @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                            int bottom = insets.getSystemWindowInsetBottom();
                            main.post(new Runnable() {
                                @Override public void run() { onImeInset(bottom); }
                            });
                            return insets;
                        }
                    });
            approvalInputRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { focusApprovalInput(); }
            });
            // 悬浮窗的焦点可能被系统/前台 App 抢走；一旦拿到就补一次输入法请求
            approvalInputRow.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    Log.i("FloatingBall", "input window focus=" + hasFocus);
                    if (hasFocus && apImeUp) {
                        main.postDelayed(new Runnable() {
                            @Override public void run() { showImeNow(); }
                        }, 60);
                    }
                }
            });
            approvalInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) focusApprovalInput();
                }
            });
        }
    }

    /**
     * 点输入框：给它焦点并主动拉起输入法（悬浮窗不会自动弹）。
     *
     * **必须延后到抬手之后**：键盘一弹出来，窗口会因为 ADJUST_RESIZE 整体上移，
     * 于是同一串手势的 ACTION_UP 就落到了别的控件上（实测会把选项点掉、面板直接消失）。
     * 所以这里只记一个「待聚焦」标志，由主线程延迟执行。
     */
    private void focusApprovalInput() {
        if (approvalInput == null) return;
        main.removeCallbacks(approvalFocusTask);
        main.postDelayed(approvalFocusTask, 220);
    }

    private final Runnable approvalFocusTask = new Runnable() {
        @Override public void run() { doFocusApprovalInput(); }
    };

    private void doFocusApprovalInput() {
        if (approvalInput == null) return;
        try {
            if (approvalInputRow != null) approvalInputRow.requestFocus();   // 先让窗口拿焦点
            approvalInput.setFocusableInTouchMode(true);
            approvalInput.requestFocus();                                    // 再让 EditText 拿焦点
            showImeNow();
            Log.i("FloatingBall", "approval input focus requested");
        } catch (Throwable t) {
            Log.w("FloatingBall", "focus input failed: " + t);
        }
    }

    /** 单独一件事：把输入法叫起来（窗口焦点不够时会失败，所以会被补发几次）。 */
    private void showImeNow() {
        if (approvalInput == null) return;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) return;
            boolean ok = imm.showSoftInput(approvalInput, 0);
            Log.i("FloatingBall", "showImeNow ok=" + ok
                    + " winFocus=" + (approvalInputRow != null && approvalInputRow.hasWindowFocus()));
        } catch (Throwable t) {
            Log.w("FloatingBall", "showImeNow failed: " + t);
        }
    }

    /** 键盘高度变化：>120dp 视为弹出 → 整块面板上移，保证输入框在键盘之上。 */
    private void onImeInset(int bottomPx) {
        float d = getResources().getDisplayMetrics().density;
        boolean up = bottomPx > Math.round(120 * d);
        if (up == apImeUp) return;
        apImeUp = up;
        apLayoutKey = Integer.MIN_VALUE;      // 强制重排
        Log.i("FloatingBall", "ime inset=" + bottomPx + " up=" + up);
    }

    /** 加一段文字，返回这个 TextView（调用方可以再改 LayoutParams）。 */
    private android.widget.TextView addText(android.widget.LinearLayout parent, String text,
                                            int sizePx, int color, android.graphics.Typeface tf,
                                            int maxLines, int maxW) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        tv.setTypeface(tf);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(maxLines);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setMaxWidth(maxW);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        parent.addView(tv, lp);
        return tv;
    }

    /** 加一枚选项：可选描述排在标签下面（DSH 的 option.description）。 */
    private void addOption(android.widget.LinearLayout parent, String label, String desc,
                           int accent, String value) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout item = new android.widget.LinearLayout(this);
        item.setOrientation(android.widget.LinearLayout.VERTICAL);
        item.setPadding(apOptPadX, apOptPadY, apOptPadX, apOptPadY);
        item.setBackground(optionBackground(withAlpha(accent, 0x33)));
        item.setClickable(true);
        item.setMinimumHeight(Math.round(AP_OPT_MIN_H_DP * d * AP_SCALE));

        android.widget.TextView lab = new android.widget.TextView(this);
        lab.setText(label);
        lab.setTextColor(0xFFF4F8FF);
        lab.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f * d * AP_SCALE);
        lab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        lab.setMaxLines(3);
        lab.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.addView(lab);

        if (desc != null && !desc.trim().isEmpty()) {
            android.widget.TextView dv = new android.widget.TextView(this);
            dv.setText(desc.trim());
            dv.setTextColor(0xFF9AA5B4);
            dv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12.5f * d * AP_SCALE);
            dv.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            dv.setMaxLines(3);
            dv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            android.widget.LinearLayout.LayoutParams dlp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Math.round(4 * d);
            item.addView(dv, dlp);
        }

        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = approvalOptViews.isEmpty() ? 0 : apOptGap;
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onOptionTap(v); }
        });
        parent.addView(item, lp);

        approvalOptViews.add(item);
        approvalOptLabels.add(lab);
        approvalOptValues.add(value);
        approvalOptChecked.add(Boolean.FALSE);
        approvalOptAccents.add(accent);
    }

    /**
     * 选项底：淡语义色玻璃 + 细描边，按下时提亮 —— 对齐苹果那种「玻璃里嵌一块浅色卡」，
     * 而不是一整块实心色（实心色在球里看着很糊）。
     */
    private android.graphics.drawable.Drawable optionBackground(int color) {
        float d = getResources().getDisplayMetrics().density;
        // ⚠️ 全部实色（alpha=0xFF）：半透明按钮会让玻璃折射透上来（见 solidBlend 的注释）
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(16f * d * AP_SCALE);
        g.setStroke(Math.max(1, Math.round(d)), withAlpha(solidBlend(color, 0.75f), 0xFF));
        g.setColors(new int[]{ solidBlend(color, 0.42f), solidBlend(color, 0.22f) });
        g.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        // 按下态：整体提亮，同样实色
        android.graphics.drawable.GradientDrawable pressed =
                new android.graphics.drawable.GradientDrawable();
        pressed.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(16f * d * AP_SCALE);
        pressed.setStroke(Math.max(1, Math.round(d)), withAlpha(solidBlend(color, 1.0f), 0xFF));
        pressed.setColors(new int[]{ solidBlend(color, 0.78f), solidBlend(color, 0.50f) });
        pressed.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        return new android.graphics.drawable.StateListDrawable() {{
            addState(new int[]{android.R.attr.state_pressed}, pressed);
            addState(new int[]{}, g);
        }};
    }

    /**
     * 把语义色压成**不透明**的实色（与面板深底按比例混）。
     *
     * 为什么需要：审批/提问的选项按钮原来是半透明的（`withAlpha(accent, 0x33)` ≈ 20%），
     * 面板窗口虽然排在玻璃**上面**，但按钮自身半透明时，底下玻璃的折射/黑顶会直接透上来 ——
     * 用户观感就是"液态玻璃在折射按钮本身，怪怪的"（多个选项的提问面板尤其明显）。
     * 这里强制 alpha=0xFF，只保留语义色的色相。
     */
    private static int solidBlend(int accent, float t) {
        int ar = (accent >> 16) & 0xFF, ag = (accent >> 8) & 0xFF, ab = accent & 0xFF;
        int br = 0x14, bg = 0x18, bb = 0x1F;      // 面板深底（AP_PANEL_BG 附近）
        int r = Math.round(br + (ar - br) * t);
        int g = Math.round(bg + (ag - bg) * t);
        int b = Math.round(bb + (ab - bb) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private android.graphics.drawable.Drawable panelBackground() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(26f * getResources().getDisplayMetrics().density * AP_SCALE);
        g.setColor(AP_PANEL_BG);
        return g;
    }

    private static String approvalBodyText(DshApprovalClient.Request req) {
        if (req.isApproval()) return req.reason == null ? "" : req.reason;
        StringBuilder sb = new StringBuilder();
        for (DshApprovalClient.Question q : req.questions) {
            String text = q.detail != null && !q.detail.isEmpty() ? q.detail : q.question;
            if (text == null || text.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        return sb.length() > 0 ? sb.toString() : (req.reason == null ? "" : req.reason);
    }

    private void removeApprovalViews() {
        if (approvalContent != null && wm != null) {
            try { wm.removeView(approvalContent); } catch (Throwable ignored) { }
        }
        if (approvalActionsScroll != null && wm != null) {
            try { wm.removeView(approvalActionsScroll); } catch (Throwable ignored) { }
        }
        if (approvalInputRow != null && wm != null) {
            try { wm.removeView(approvalInputRow); } catch (Throwable ignored) { }
        }
        approvalContent = null;
        approvalActions = null;
        approvalActionsScroll = null;
        approvalInputRow = null;
        approvalBodyView = null;
        approvalInput = null;
        approvalSend = null;
        approvalOptViews.clear();
        approvalOptLabels.clear();
        approvalOptValues.clear();
        approvalOptChecked.clear();
        approvalOptAccents.clear();
        approvalSingleQid = -1;
    }

    private void hideApprovalWindows() {
        if (approvalContent != null) approvalContent.setVisibility(View.GONE);
        if (approvalActionsScroll != null) approvalActionsScroll.setVisibility(View.GONE);
        if (approvalInputRow != null) approvalInputRow.setVisibility(View.GONE);
    }

    private int approvalMaxContentW() {
        float d = getResources().getDisplayMetrics().density;
        // 球要接近方形，所以文字块不能铺满屏：给它一个「近乎方形」的宽度预算
        int byRatio = Math.round(screenW * AP_TEXT_W_RATIO);
        int byBall = (int) (siriMaxBallW() - 2 * (AP_PAD_X_DP + 16) * d);
        return (int) Math.max(160 * d, Math.min(byRatio, byBall));
    }

    // ── 点击 → 回答案 ──────────────────────────────────────────────────

    /**
     * 某个选项属于哪道题。选项的 value 形如 `"<questionId>\0<label>"`，
     * 所以题号可以从 value 里还原 —— 一批问题混着单选/多选时，必须按选项
     * 自己的题号判定，不能拿 `questions.get(0)` 一刀切。
     */
    /**
     * 一批里超过一道题吗。多题时必须由「发送」整批交卷 —— DSH 的 answers
     * 是数组，点一下只回一条等于把没答的题一起结掉。
     */
    private boolean batchMode() {
        return approvalReq != null && approvalReq.questions.size() > 1;
    }

    private DshApprovalClient.Question questionOfOption(int idx) {
        if (approvalReq == null || idx < 0 || idx >= approvalOptValues.size()) return null;
        String v = approvalOptValues.get(idx);
        int sep = v.indexOf('\u0000');
        String qid = sep < 0 ? "" : v.substring(0, sep);
        for (DshApprovalClient.Question q : approvalReq.questions) {
            if (q.id.equals(qid)) return q;
        }
        return approvalReq.questions.isEmpty() ? null : approvalReq.questions.get(0);
    }

    private void onOptionTap(View v) {
        int idx = approvalOptViews.indexOf(v);
        if (idx < 0 || approvalReq == null) return;
        String value = approvalOptValues.get(idx);
        try {
            android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(20);
        } catch (Throwable ignored) { }

        if (approvalReq.isApproval()) {
            Log.i("FloatingBall", "approval tap → " + value);
            DshApprovalClient c = approvals();
            if (c != null) {
                if (c.debugMode()) c.writeDebugAnswer(value);
                else if ("allowed-once".equals(value)) c.allow();
                else c.reject();
            }
            hideApprovalPanel();
            return;
        }

        // 提问：value = "<questionId>\0<label>"
        DshApprovalClient.Question q = questionOfOption(idx);
        if (q != null && q.isMultiSelect()) {          // 多选：点亮/取消，由「发送」提交
            approvalOptChecked.set(idx, !approvalOptChecked.get(idx));
            refreshOptionStyles();
            return;
        }
        if (batchMode()) {
            // 一批多题：一道题的答案不能独自交卷，否则 DSH 会拿着半截 answers
            // 把整道 ask_user_question 结掉。这里只在题内做单选互斥，等「发送」。
            for (int i = 0; i < approvalOptValues.size(); i++) {
                if (i == idx) continue;
                DshApprovalClient.Question oq = questionOfOption(i);
                if (oq != null && oq.id.equals(q == null ? "" : q.id)) {
                    approvalOptChecked.set(i, Boolean.FALSE);
                }
            }
            approvalOptChecked.set(idx, !Boolean.TRUE.equals(approvalOptChecked.get(idx)));
            refreshOptionStyles();
            return;
        }
        int sep = value.indexOf('\u0000');
        String qid = sep < 0 ? "" : value.substring(0, sep);
        String label = sep < 0 ? value : value.substring(sep + 1);
        Log.i("FloatingBall", "question answer → " + qid + " = " + label);
        sendAnswer(qid, java.util.Collections.singletonList(label), "");
        hideApprovalPanel();
    }

    /**
     * 提交：自由输入 + 已勾选的选项。
     *
     * 三条语义：
     *   · 单选 + 写了自由文本 → selected 清空，两者互斥（照抄 DSH 客户端
     *     `dsh-client-ui-user-questions/lib/client.js` 里
     *     `selected: custom === "" || multiSelect ? selected : []`）；
     *   · 多选 → selected 与 custom 可以并存；
     *   · **多选允许「只勾选、不写字」** —— 之前这里开头就 `if (text.isEmpty()) return`，
     *     导致纯选项的多选答案根本递不出去（注释却说「由发送提交」，名不副实）。
     */
    private void submitCustomAnswer() {
        if (approvalReq == null) return;
        String text = approvalInput == null || approvalInput.getText() == null
                ? "" : approvalInput.getText().toString().trim();

        // 勾选项按题号归拢：一道题一条答案，别把别题的勾选算进来
        java.util.LinkedHashMap<String, java.util.List<String>> byQuestion =
                new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (int i = 0; i < approvalOptValues.size(); i++) {
            if (!Boolean.TRUE.equals(approvalOptChecked.get(i))) continue;
            DshApprovalClient.Question q = questionOfOption(i);
            // 单选只有在一批多题时才靠勾选提交（单题单选是点了就走）
            if (q == null || !(q.isMultiSelect() || batchMode())) continue;
            String v = approvalOptValues.get(i);
            int sep = v.indexOf('\u0000');
            java.util.List<String> bucket = byQuestion.get(q.id);
            if (bucket == null) {
                bucket = new java.util.ArrayList<String>();
                byQuestion.put(q.id, bucket);
            }
            bucket.add(sep < 0 ? v : v.substring(sep + 1));
        }

        if (text.isEmpty() && byQuestion.isEmpty()) return;   // 既没写也没勾，没什么可发

        java.util.List<DshApprovalClient.Answer> batch =
                new java.util.ArrayList<DshApprovalClient.Answer>();
        if (byQuestion.isEmpty()) {                           // 纯自由文本（单选）
            String qid = approvalReq.questions.isEmpty() ? "" : approvalReq.questions.get(0).id;
            batch.add(new DshApprovalClient.Answer(qid, null, text));
        } else {
            java.util.List<String> qids =
                    new java.util.ArrayList<String>(byQuestion.keySet());
            for (int i = 0; i < qids.size(); i++) {
                // custom 只跟最后一条走，免得同一段文字被记到每道题上
                String custom = i == qids.size() - 1 ? text : "";
                batch.add(new DshApprovalClient.Answer(qids.get(i), byQuestion.get(qids.get(i)), custom));
            }
        }
        for (DshApprovalClient.Answer a : batch) {
            Log.i("FloatingBall", "question answer → " + a.questionId
                    + " selected=" + a.selected + " custom=" + a.custom);
        }
        sendAnswers(batch);
        hideApprovalPanel();
    }

    /** 把答案交给 DSH：审批是字符串，提问是 {answers:[…]} 对象（整批一次回）。 */
    private void sendAnswers(java.util.List<DshApprovalClient.Answer> batch) {
        DshApprovalClient c = approvals();
        if (c == null || batch == null || batch.isEmpty()) return;
        if (c.debugMode()) {
            // 调试回路只看「点了什么」，把整批摊平打印，方便探针核对多选
            StringBuilder sb = new StringBuilder();
            for (DshApprovalClient.Answer a : batch) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(a.questionId).append('=').append(a.selected);
                if (!a.custom.isEmpty()) sb.append(" custom:").append(a.custom);
            }
            c.writeDebugAnswer(sb.toString());
            return;
        }
        c.answerQuestions(batch);
    }

    /** 单条答案的便捷包装（单选点一下就走的路径）。 */
    private void sendAnswer(String questionId, java.util.List<String> selected, String custom) {
        java.util.List<DshApprovalClient.Answer> batch =
                new java.util.ArrayList<DshApprovalClient.Answer>();
        batch.add(new DshApprovalClient.Answer(questionId, selected, custom));
        sendAnswers(batch);
    }

    private DshApprovalClient approvals() {
        SiriAssistant a = SiriAssistant.peek();
        return a == null ? null : a.approvals();
    }

    private void refreshOptionStyles() {
        for (int i = 0; i < approvalOptViews.size(); i++) {
            boolean on = Boolean.TRUE.equals(approvalOptChecked.get(i));
            int accent = approvalOptAccents.get(i);
            approvalOptViews.get(i).setBackground(optionBackground(
                    on ? withAlpha(accent, 0xF0) : accent));
            DshApprovalClient.Question q = questionOfOption(i);
            boolean multi = q != null && q.isMultiSelect();
            android.widget.TextView lab = approvalOptLabels.get(i);
            String raw = lab.getText().toString();
            // 前缀固定两字符：☐/☑（多选）、○/●（一批多题里的单选）
            if (raw.length() >= 2 && (raw.startsWith("☐ ") || raw.startsWith("☑ ")
                    || raw.startsWith("○ ") || raw.startsWith("● "))) {
                String mark = multi ? (on ? "☑ " : "☐ ") : (on ? "● " : "○ ");
                lab.setText(mark + raw.substring(2));
            }
        }
    }

    private void hideKeyboard() {
        main.removeCallbacks(approvalFocusTask);
        try { if (approvalInput != null) approvalInput.clearFocus(); } catch (Throwable ignored) { }
        apImeUp = false;
        try {
            if (approvalInput != null) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(approvalInput.getWindowToken(), 0);
            }
        } catch (Throwable ignored) { }
    }

    // ── 几何：只在档位变化时重算 ───────────────────────────────────────

    private void applyApprovalGeometry() {
        if (approvalContent == null || approvalActions == null) return;
        float d = getResources().getDisplayMetrics().density;
        float base = winSize * 0.9f;
        if (base <= 0) base = 1f;
        int textMaxW = approvalMaxContentW();
        int actMaxW = textMaxW + 2 * apPadX;      // 交互层可以比文字块宽一点（按钮要有存在感）
        // 面板总高上限（球也按它收）。★ 2026-11-06：别再按「玻璃内高」收 ——
        //   那样 8 个选项时会把选项层压到只剩 4 行。上限就是屏幕的 66%，
        //   装不下的选项在它自己的 ScrollView 里滚（上下带淡出，不会硬切一半）。
        float cap = screenH * 0.66f;

        String key = textMaxW + "|" + actMaxW + "|" + apBodyPx + "|" + approvalOptValues.size()
                + "|" + (approvalInput != null);
        if (!apGeomDirty && key.equals(apGeomKey)) return;
        apGeomKey = key;
        apGeomDirty = false;

        // 文字块：先把宽度上限给到每个 TextView，再量（否则量出「一行」的假高度）
        for (int i = 0; i < approvalContent.getChildCount(); i++) {
            View c = approvalContent.getChildAt(i);
            if (c instanceof android.widget.TextView) ((android.widget.TextView) c).setMaxWidth(textMaxW);
        }
        apBodyPx = Math.round(14f * getResources().getDisplayMetrics().density * AP_SCALE);
        approvalBodyClipped = false;
        if (approvalBodyView != null) {
            approvalBodyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, apBodyPx);
            approvalBodyView.setMaxLines(12);
        }
        remeasure(approvalContent, textMaxW);

        // 放不下就压：先压交互层（说明文字行数 → 内边距），再压正文
        for (int guard = 0; guard < 8; guard++) {
            measureActions(actMaxW, Integer.MAX_VALUE);
            measureInput(actMaxW);
            float total = approvalContent.getMeasuredHeight() + apGap
                    + approvalActions.getMeasuredHeight() + (apInputH > 0 ? apGap + apInputH : 0);
            if (total <= cap) break;
            if (apOptPadY > Math.round(7 * d)) {
                apOptPadY = Math.max(Math.round(7 * d), apOptPadY - Math.round(2 * d));
                for (int i = 0; i < approvalOptViews.size(); i++) {
                    approvalOptViews.get(i).setPadding(apOptPadX, apOptPadY, apOptPadX, apOptPadY);
                }
                continue;
            }
            if (approvalBodyView != null && apBodyPx > Math.round(11 * d)) {
                apBodyPx = Math.max(Math.round(11 * d), apBodyPx - Math.round(1.5f * d));
                approvalBodyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, apBodyPx);
            } else if (approvalBodyView != null && approvalBodyView.getMaxLines() > 2) {
                approvalBodyView.setMaxLines(approvalBodyView.getMaxLines() - 2);
                approvalBodyClipped = true;
            } else {
                break;                            // 选项实在太多：球封顶，内容由窗口裁
            }
            remeasure(approvalContent, textMaxW);
        }
        apTextW = approvalContent.getMeasuredWidth();
        apTextH = approvalContent.getMeasuredHeight();
        // 交互层最多能占多高：面板上限 − 文字块 − 缝。超出的部分在它自己的框里滚，
        // **绝不允许压到文字块上**（之前没夹高度，选项直接盖住了说明文字）。
        int actBudget = Math.max(Math.round(120 * d),
                (int) (cap - apTextH - apGap - apPadY / 2f));
        // 先给输入行留出它自己的高度，按钮层的预算再扣掉它
        measureInput(actMaxW);
        int actBudget2 = Math.max(Math.round(120 * d), actBudget - (apInputH > 0 ? apInputH + apGap : 0));
        measureActions(actMaxW, actBudget2);
        View actBox = approvalActionsScroll != null ? (View) approvalActionsScroll : approvalActions;
        apActW = Math.min(Math.max(actBox.getMeasuredWidth(), apInputW), actMaxW);
        apActH = Math.min(actBox.getMeasuredHeight(), actBudget2);

        // 球：接近方形（取文字块与交互层里更宽的那个），高度装下两块 + 留白
        float innerW = Math.max(apTextW, apActW);
        float halfW = Math.max(base * minHalfX0, innerW / 2f + apPadX / 2f + dp(4));   // 原版球宽：球缩小不影响审批面板
        float blockTotal = apTextH + apGap + apActH + (apInputH > 0 ? apGap + apInputH : 0);
        float halfH = Math.max(base * minHalfY0,
                blockTotal / 2f + apPadY / 2f + dp(6));
        // 面板宽度夹到「聊天界面」那条宽度（siriMaxBallW）：用户要的是审批/提问面板
        // 跟聊天卡片一样宽，别比它还大、挡住操作。
        float maxHalfW = Math.min(screenW * 0.92f / 2f, siriMaxBallW() / 2f);
        // ★ 2026-11-06：高度上限从「屏高 68%」收到「屏幕里真能放下」——
        //   8 个选项那种面板原来会长到快顶满屏幕，最后一个选项被顶出玻璃。
        // 高度上限 58% 屏高（原来 68%，9 个选项时几乎顶满整屏 —— 用户说「窗体过大」）。
        float maxHalfH = Math.min(screenH * 0.58f,
                screenH - winY0() - dp(28)) / 2f;
        halfW = Math.min(halfW, maxHalfW);
        halfH = Math.min(halfH, maxHalfH);
        halfH = Math.max(halfH, halfW * 0.9f);            // 别扁成一条：至少接近方形
        halfW = Math.max(halfW, halfH * 0.62f);
        halfW = Math.min(halfW, maxHalfW);
        halfH = Math.min(halfH, maxHalfH);

        // ★ 选项层必须在玻璃里收住：可用高 = 玻璃内高 − 文字块 − 缝 − 输入行。
        //   超出的选项在自己的 ScrollView 里滚（下面 updateApprovalLayout 也按这个高度贴底摆）。
        float innerH = halfH * 2f - apPadY * 1.15f - dp(10);
        float maxActH = innerH - apTextH - apGap - (apInputH > 0 ? apGap + apInputH : 0);
        int actFloor = Math.round(dp(72));
        if (apActH > maxActH) {
            apActH = Math.max(actFloor, Math.round(maxActH));
            Log.i("FloatingBall", "approval: 选项层收到 " + apActH + "px（玻璃内高 "
                    + Math.round(innerH) + "，文字 " + apTextH + "）");
        }

        float cr = 0.28f * Math.min(halfW, halfH);        // squircle：比方角柔、比椭圆方正
        aprCorner = Math.max(0.28f, Math.min(minCorner, cr / Math.max(1f, halfH)));
        aprHalfX = halfW / base;
        aprHalfY = halfH / base;

        float contentBottomPx = apTextH + apGap + apActH
                + (apInputH > 0 ? apGap + apInputH : 0) + dp(8);
        float ny = 1f - contentBottomPx / Math.max(1f, halfH);
        float gap = BLACK_GAP_NY;                 // 固定值（原来的「安全距离」滑块已删）
        float black1Base = (renderer != null) ? renderer.black1 : -0.01f;
        aprBlack = Math.max(0f, Math.min(1.5f, black1Base - (ny - gap)));
    }

    /** 量输入行（它现在是独立悬浮窗，必须单独参与尺寸计算）。 */
    private void measureInput(int maxW) {
        if (approvalInputRow == null) { apInputW = 0; apInputH = 0; return; }
        approvalInputRow.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, maxW), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        apInputW = Math.min(approvalInputRow.getMeasuredWidth(), maxW);
        apInputH = approvalInputRow.getMeasuredHeight();
    }

    private void remeasure(View v, int maxW) {
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, maxW), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    }

    /** 交互层（带滚动）按「最多这么高」来量：超了就滚，不压文字。 */
    private void measureActions(int maxW, int maxH) {
        View v = approvalActionsScroll != null ? (View) approvalActionsScroll : approvalActions;
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, maxW), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, maxH), View.MeasureSpec.AT_MOST));
    }

    // ── 窗口 ──────────────────────────────────────────────────────────

    /**
     * `WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY`（Android 12+，@hide，值 0x20000000）。
     *
     * 系统给的注释是"这个悬浮窗是受信任的"：InputDispatcher 的 tap-jacking 拦截只拦
     * **不受信任**的悬浮窗（"触摸穿过一个不受信任的悬浮窗"就整串丢弃）。打上这个标志，
     * 球就能**原地不动**——只把可触摸区置空——让点击干净地穿到下面**别的 App**。
     * 这是"AI 操作时玻璃无事发生"的关键一枚开关（比无障碍 overlay 轻得多）。
     */
    private static final int PFLAG_TRUSTED_OVERLAY = 0x20000000;
    private boolean trustedFlagApplied = false;
    private static java.lang.reflect.Field pflagField;

    /**
     * 给窗口打上"受信任 overlay"私有标志。
     *
     * ⚠️ 澎湃/HyperOS 的 DisplayPolicy 会直接拒绝：
     *   `DisplayPolicy: uid 10286 does not have android.permission.INTERNAL_SYSTEM_WINDOW`
     * （而且是在 addWindow 里抛 SecurityException，会把进程带崩）。
     * 所以**默认不打**，只有放了 `files/trusted-overlay` 开关、并且确认这个 App 已经
     * 被"系统化"（/system/priv-app + privapp-permissions 里给了 INTERNAL_SYSTEM_WINDOW）时才开。
     */
    private void markTrustedOverlay(WindowManager.LayoutParams p) {
        if (p == null) return;
        // 澎湃/HyperOS：DisplayPolicy 要求 INTERNAL_SYSTEM_WINDOW（signature 级），
        // 普通 App 永远拿不到 → 没这个权限就绝对不要打，否则 addWindow 抛 SecurityException 把进程带崩。
        try {
            if (checkSelfPermission("android.permission.INTERNAL_SYSTEM_WINDOW")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        } catch (Throwable t) { return; }
        try {
            if (pflagField == null) {
                // privateFlags 是 @hide 字段（SDK 桩里没有），只能反射拿
                pflagField = WindowManager.LayoutParams.class.getField("privateFlags");
            }
            int cur = pflagField.getInt(p);
            pflagField.setInt(p, cur | PFLAG_TRUSTED_OVERLAY);
            trustedFlagApplied = true;
        } catch (Throwable t) {
            Log.w("FloatingBall", "markTrustedOverlay failed: " + t);
        }
    }

    /**
     * 悬浮窗类型。
     *
     * 优先 {@code TYPE_ACCESSIBILITY_OVERLAY}（**受信任** overlay）：Android 12+ 的
     * InputDispatcher 有一条 tap-jacking 拦截 —— 触摸要"穿过"一个**不受信任**的悬浮窗
     * 落到别的 App 上时会被整串丢弃（实测：点在球上的位置去点系统设置，完全没反应；
     * 目标窗口和悬浮窗同属一个 App 时例外）。受信任 overlay 不受这条规则约束，
     * 于是球可以**原地不动、只把触摸区置空**就让点击穿过去，既不用挪窗口（会闪）也不用把球藏起来。
     *
     * 无障碍服务没连上时退回普通系统悬浮窗（那时"让开"必须把窗口挪开，见 applyBallTouchability）。
     */
    private int overlayType() {
        if (!a11yOverlayBroken && BallA11yService.connected && a11yOverlayAllowed()) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** 受信任 overlay 模式是否允许（`files/glasspark` = 强制回老路，排查用）。 */
    private boolean a11yOverlayAllowed() {
        if (legacyGlassPark()) return false;
        try { return prefs().getBoolean("ball_a11y", true); } catch (Throwable t) { return true; }
    }

    /** 当前窗口是不是"受信任"的（决定让开要不要挪窗口）。 */
    private boolean trustedOverlayNow() {
        if (glView == null || params == null) return false;
        if (legacyGlassPark()) return false;                       // 排查开关：强制回"挪窗口"
        if (params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) return true;
        // 系统要是关掉了"不受信任触摸拦截"（settings put secure block_untrusted_touches 0），
        // 球就只需要把触摸区置空、窗口一动不用动 —— 这就是一加/ColorOS 上的效果。
        try {
            if (android.provider.Settings.Secure.getInt(getContentResolver(),
                    "block_untrusted_touches", 1) == 0) return true;
        } catch (Throwable ignored) { }
        // 调试开关：放 files/region-only 强制走"只置空、不挪窗"
        try { if (new File(gateDir(), "region-only").exists()) return true; } catch (Throwable ignored) { }
        // 调试开关：files/region-only → "只置空触摸区、不挪窗口"（用来验遮挡判定，别长期开）
        try { if (new File(gateDir(), "region-only").exists()) return true; } catch (Throwable ignored) { }
        // 私有标志 PRIVATE_FLAG_TRUSTED_OVERLAY 打上了就算受信任（本机走的就是这条）
        return trustedFlagApplied;
    }

    private WindowManager.LayoutParams overlayParams(int w, int h, int flags) {
        int type = overlayType();
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        markTrustedOverlay(p);
        return p;
    }

    /**
     * 让一个悬浮窗「只画不摸」—— **不用 FLAG_NOT_TOUCHABLE**。
     *
     * 为什么不能用那个 flag（2026-10-16 用 logcat 定位到的真根因）：
     * 一加 / OPPO 的 WindowManager 加了一条 OEM 规则，logcat 原文：
     *
     *   W WindowManager: App com.glslplayground.app has a system alert window (type = 2038)
     *   with FLAG_NOT_TOUCHABLE and LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80
     *   to let touches pass through (if this is isn't desirable, remove flag FLAG_NOT_TOUCHABLE).
     *
     * 也就是说：**只要系统悬浮窗带 FLAG_NOT_TOUCHABLE，系统就把 alpha 钉成 0.80**。
     * 球因此永远只有 80% 不透明 —— 剩下 20% 是「未经折射的真实背景」直接叠上来，
     * 于是球看着像一层半透明贴纸（既看得到折射、又清清楚楚看得到原背景）。
     * 这个 alpha 是窗口级的，shader 里把 aa 写成 1 也救不回来（排查过好几轮都栽在这）。
     *
     * 等价做法：把窗口的**可触摸区域置空**（ViewTreeObserver 的 TOUCHABLE_INSETS_REGION）。
     * 输入系统照样命中不到这个窗口（触摸穿到下面 / 交给代理窗口），但 OEM 那条规则不触发，
     * 球保持 100% 不透明。
     *
     * 这套 API（ViewTreeObserver.OnComputeInternalInsetsListener / InternalInsetsInfo）在
     * 公开 SDK 里是 @hide 的（android.jar 里根本没有），所以只能反射挂监听 + 动态代理。
     * 失败时只打日志、不影响其它逻辑；那时窗口退回「可触摸」，代理窗口仍在上面接手势。
     */
    private void makeTouchTransparent(final View v) {
        if (v == null) return;
        try {
            final Class<?> listenerCls = Class.forName(
                    "android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            final Class<?> infoCls = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            final java.lang.reflect.Method setTouchableInsets =
                    infoCls.getMethod("setTouchableInsets", int.class);
            final java.lang.reflect.Field regionField = infoCls.getField("touchableRegion");
            final int TOUCHABLE_INSETS_REGION = 3;   // InternalInsetsInfo.TOUCHABLE_INSETS_REGION
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerCls.getClassLoader(), new Class<?>[]{listenerCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
                            if (a != null && a.length == 1 && a[0] != null) {
                                try {
                                    setTouchableInsets.invoke(a[0], TOUCHABLE_INSETS_REGION);
                                    Object region = regionField.get(a[0]);
                                    if (region instanceof android.graphics.Region) {
                                        ((android.graphics.Region) region).setEmpty();
                                    }
                                } catch (Throwable ignored) { }
                            }
                            return null;
                        }
                    });
            java.lang.reflect.Method add = android.view.ViewTreeObserver.class.getMethod(
                    "addOnComputeInternalInsetsListener", listenerCls);
            add.invoke(v.getViewTreeObserver(), listener);
            Log.i("FloatingBall", "makeTouchTransparent ok（窗口只画不摸，但不带 FLAG_NOT_TOUCHABLE）");
        } catch (Throwable t) {
            Log.w("FloatingBall", "makeTouchTransparent failed: " + t);
        }
    }

    private void ensureApprovalWindows() {
        if (wm == null) return;
        // 面板的三层窗口也要排除出抓屏（否则玻璃会折射面板自己的按钮，见 applySkipScreenshotTo）
        scheduleApprovalSkipScreenshot();
        if (approvalContent != null && approvalContent.getParent() == null) {
            try { wm.addView(approvalContent, approvalContentParams); trackWindow(approvalContent); }
            catch (Throwable t) { Log.w("FloatingBall", "add approval content failed: " + t); }
        }
        if (approvalActionsScroll != null && approvalActionsScroll.getParent() == null) {
            try { wm.addView(approvalActionsScroll, approvalActionsParams); trackWindow(approvalActionsScroll); }
            catch (Throwable t) { Log.w("FloatingBall", "add approval actions failed: " + t); }
        }
        if (approvalInputRow != null && approvalInputRow.getParent() == null) {
            try { wm.addView(approvalInputRow, approvalInputParams); trackWindow(approvalInputRow); }
            catch (Throwable t) { Log.w("FloatingBall", "add approval input failed: " + t); }
        }
        if (approvalContent != null) approvalContent.setVisibility(View.VISIBLE);
        if (approvalActionsScroll != null) approvalActionsScroll.setVisibility(View.VISIBLE);
        if (approvalInputRow != null) approvalInputRow.setVisibility(View.VISIBLE);
        // 拖动层必须藏起来（它在按钮之上且铺满屏，会吃掉点击）
        if (approvalTouch != null) approvalTouch.setVisibility(View.GONE);
        if (approvalTouch == null) {
            View v = new View(this);
            v.setBackgroundColor(0x00000000);
            v.setClickable(true);
            approvalTouchParams = overlayParams(2, 2,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            v.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View view, MotionEvent e) {
                    return onApprovalTouch(e);
                }
            });
            try { wm.addView(v, approvalTouchParams); approvalTouch = v; trackWindow(v); }
            catch (Throwable t) { Log.w("FloatingBall", "add approval touch failed: " + t); }
        }
    }

    /** 球窗口：完整包住这颗球（否则球面会被窗口裁掉）。 */
    private void updateApprovalWindow() {
        if (wm == null || glView == null || !approvalShown) return;
        float m = dp(24);
        float base = winSize * 0.9f;
        float hw = aprHalfX > 0f ? aprHalfX : minHalfX0;
        float hh = aprHalfY > 0f ? aprHalfY : minHalfY0;
        int W = Math.min(screenW, Math.max(2, Math.round(2 * (base * hw + m))));
        int H = Math.min(screenH, Math.max(2, Math.round(2 * (base * hh + m))));
        // ★ 2026-11-06：**必须**把「窗口尺寸」写给渲染器。
        //   k = 绘制缓冲 / 窗口像素；这个字段停在上一次小球窗口（793x579）时，
        //   k 会算成 1.23 —— 整块形状被放大 23%、四角被窗口切平（用户报的
        //   「内容一多、窗体一大，圆角就被挤成方形」就是这么来的）。
        if (renderer != null) { renderer.winPixelW = W; renderer.winPixelH = H; }
        if (W == winW && H == winH) return;
        float bcx = params.x + winW * 0.5f;
        float bcy = params.y + winH * 0.5f;
        winW = W; winH = H;
        params.width = W; params.height = H;
        setGlassBufferScale((long) W * H);      // 面板很大时降分辨率画（球的 0.3 模糊度本来就几乎不糊）
        params.x = Math.round(bcx - W * 0.5f);
        params.y = Math.round(bcy - H * 0.5f);
        params.x = Math.max(-W / 2, Math.min(screenW - W / 2, params.x));
        // ★ 2026-11-06：面板竖着的位置改成「能放进屏幕就放进屏幕」——
        //   以前保持球心再夹 ±24dp，面板一高就被顶到屏幕外（顶边压在状态栏上、
        //   底下被屏幕切掉）。现在：请求 y 的合法区间 = [状态栏下 8dp, 屏幕底 − 40dp]。
        int minReqY = Math.round(dp(8));
        int maxReqY = Math.round(screenH - H - dp(40) - winY0());
        params.y = Math.max(minReqY, Math.min(Math.max(minReqY, maxReqY), params.y));
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        glView.post(winSync);
        Log.i("FloatingBall", "approval window -> " + W + "x" + H + " at " + params.x + "," + params.y);
    }

    /** 摆放两块内容；同档位下不重复动窗口（这就是「不跳」的关键）。 */
    private void updateApprovalLayout() {
        if (!approvalShown || approvalContent == null || approvalActions == null || wm == null) return;
        if (approvalTouch != null && approvalTouch.getVisibility() == View.VISIBLE) {
            approvalTouch.setVisibility(View.GONE);   // 双保险
        }
        float winXnow = (renderer != null) ? renderer.winX : params.x;
        float winYnow = (renderer != null) ? renderer.winY : params.y;
        float base = winSize * 0.9f;
        float hypx = base * aHalfY * aMaster;
        float hxpx = base * aHalfX * aMaster;
        float sy = winYnow + winH * 0.5f;
        float ballTop = sy - hypx;
        float sysOffset = winYnow - params.y;

        // ★ 2026-11-06：从**玻璃内底边往上**摆（原来是从球顶往下累加）。
        //   往下累加时，选项一多整块就顶出玻璃底边 —— 用户看到的就是「最后几个选项
        //   被输入行/球底切掉」。现在输入行贴内底边、选项层贴它上面、文字块贴最上面，
        //   三者永远在玻璃里；选项超出就在自己的 ScrollView 里滚（高度已在
        //   applyApprovalGeometry 里按玻璃内高夹过）。
        float pad = dp(10);
        float glassBottom = ballTop + hypx * 2f - pad;          // 玻璃内底边（屏幕 y）
        float topLimit = ballTop + pad;
        if (apImeUp) topLimit = ballTop + dp(4);      // 键盘弹起：内容顶到球顶，尽量离键盘远一点
        float inTop = (apInputH > 0) ? (glassBottom - apInputH) : 0f;
        float actBottom = (apInputH > 0) ? (inTop - apGap) : glassBottom;
        float actTop = actBottom - apActH;
        float textTop = actTop - apGap - apTextH;
        if (textTop < topLimit) textTop = topLimit;
        // ★ 2026-11-06：上面算的全是**屏幕坐标**，写进 WindowManager.LayoutParams 必须减 inset
        //   （原来 textY 有减 sysOffset，我改成倒着摆的时候漏了 → 文字/选项/输入行整体下移 90px，
        //    看着就是「按钮超出大玻璃、最下面被切掉」）。
        int textY = Math.round(textTop - sysOffset);
        int actY = Math.round(actTop - sysOffset);
        int inY = (apInputH > 0) ? Math.round(inTop - sysOffset) : 0;
        int textX = Math.round(winXnow + winW / 2f - apTextW / 2f);
        int actX = Math.round(winXnow + winW / 2f - apActW / 2f);

        int key = textX * 31 + textY * 17 + actX * 7 + actY * 3 + inY
                + apTextW + apActW + apActH + apInputH + (apImeUp ? 1 : 0);
        if (key == apLayoutKey) return;
        apLayoutKey = key;

        if (approvalContentParams.width != apTextW || approvalContentParams.height != apTextH) {
            approvalContentParams.width = Math.max(1, apTextW);
            approvalContentParams.height = Math.max(1, apTextH);
        }
        approvalContentParams.x = textX;
        approvalContentParams.y = textY;
        try { wm.updateViewLayout(approvalContent, approvalContentParams); } catch (Throwable ignored) { }

        View actBox = approvalActionsScroll != null ? (View) approvalActionsScroll : approvalActions;
        if (approvalActionsParams.width != apActW || approvalActionsParams.height != apActH) {
            approvalActionsParams.width = Math.max(1, apActW);
            approvalActionsParams.height = Math.max(1, apActH);
        }
        approvalActionsParams.x = actX;
        approvalActionsParams.y = actY;
        try { wm.updateViewLayout(actBox, approvalActionsParams); } catch (Throwable ignored) { }

        // 输入行：自己一个窗口，贴玻璃内底边（inY 是上面按底边倒算出来的）
        if (approvalInputRow != null && apInputH > 0) {
            int inX = Math.round(winXnow + winW / 2f - apInputW / 2f);
            if (approvalInputParams.width != apInputW || approvalInputParams.height != apInputH) {
                approvalInputParams.width = Math.max(1, apInputW);
                approvalInputParams.height = Math.max(1, apInputH);
            }
            approvalInputParams.x = inX;
            approvalInputParams.y = inY;
            apInputY = inY;
            try { wm.updateViewLayout(approvalInputRow, approvalInputParams); } catch (Throwable ignored) { }
        }
    }

    private void updateApprovalTouchLayer(float hxpx, float hypx, float winXnow, float winYnow) {
        if (approvalTouch == null || approvalTouchParams == null || wm == null) return;
        // 实测教训：这个「拖动层」虽然只给了 2x2 的初始尺寸，但 FLAG_LAYOUT_NO_LIMITS +
        // TOP|START 会让它真的铺满整屏（dumpsys: frame=[0,96][1080,2296]），而它加在
        // 按钮窗口之后 → z 更高 → **把选项按钮的点击全吃掉了**（按钮看得见点不动）。
        // 面板期间球在屏幕中间，本来也不需要在球上拖动，所以直接藏起来，把点击还给按钮。
        if (approvalTouch.getVisibility() != View.GONE) approvalTouch.setVisibility(View.GONE);
        return;
    }


    private boolean onApprovalTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                apDownX = e.getRawX(); apDownY = e.getRawY();
                apStartX = params.x; apStartY = params.y;
                apDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float moved = Math.abs(e.getRawX() - apDownX) + Math.abs(e.getRawY() - apDownY);
                if (!apDragging && moved > dp(12)) apDragging = true;
                if (apDragging) {
                    params.x = (int) (apStartX + (e.getRawX() - apDownX));
                    params.y = (int) (apStartY + (e.getRawY() - apDownY));
                    params.x = Math.max(-winW / 2, Math.min(screenW - winW / 2, params.x));
                    params.y = Math.max(-Math.round(dp(24)),
                            Math.min(screenH - winH + Math.round(dp(24)), params.y));
                    if (wm != null) try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
                    glView.post(winSync);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                apDragging = false;
                return true;
            default:
                return false;
        }
    }

    static boolean isApprovalUp() {
        FloatingBallService s = INSTANCE;
        return s != null && (s.approvalShown || s.approvalClosing);
    }

    static String approvalSummary() {
        FloatingBallService s = INSTANCE;
        if (s == null || s.approvalReq == null) return "";
        DshApprovalClient.Request r = s.approvalReq;
        return r.title() + " · " + (r.isApproval() ? "审批" : "提问")
                + " · 选项 " + s.approvalOptViews.size()
                + (s.approvalInput != null ? " +输入" : "");
    }

    static void debugApproval(final String toolName, final String reason) {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                FloatingBallService s2 = INSTANCE;
                if (s2 == null) return;
                DshApprovalClient.Request req = new DshApprovalClient.Request();
                req.event = "approval/request";
                req.eventId = "debug-" + System.currentTimeMillis();
                req.toolName = toolName == null || toolName.isEmpty() ? "bash" : toolName;
                req.reason = reason == null ? "" : reason;
                s2.showApprovalPanel(req);
            }
        });
    }

    /** 调试：造一条「多选项 + 自由输入」的提问，用来验收自适应版式。 */
    static void debugQuestion() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                FloatingBallService s2 = INSTANCE;
                if (s2 == null) return;
                DshApprovalClient.Request req = new DshApprovalClient.Request();
                req.event = "user-questions/request";
                req.eventId = "debug-q-" + System.currentTimeMillis();
                DshApprovalClient.Question q = new DshApprovalClient.Question();
                q.id = "mode";
                q.header = "选择运行模式";
                q.question = "打开 QQ 需要操作屏幕，请选择运行模式：";
                q.labels.add("前台 Foreground");
                q.descriptions.add("在物理屏幕上显示，你能看到画面和触摸光效。");
                q.labels.add("后台 Background");
                q.descriptions.add("在隔离的虚拟显示中静默运行，不占用你的屏幕。");
                q.labels.add("先不动，我自己来");
                q.descriptions.add("什么都不做，你去网页端处理。");
                req.questions.add(q);
                s2.showApprovalPanel(req);
            }
        });
    }

    static FloatingBallService instance() { return INSTANCE; }

    static void dismissApprovalIfAny() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                FloatingBallService s2 = INSTANCE;
                if (s2 != null && s2.approvalShown) s2.hideApprovalPanel();
            }
        });
    }

    /** 调试试用：把球体这一帧存成 PNG，返回文件路径。 */
    public static String captureBallPng() {
        FloatingBallService s = INSTANCE;
        if (s == null || s.glView == null) return "";
        Log.i("FloatingBall", "ball capture requested (state=" + SiriAssistant.stateName()
                + " waveMode=" + s.renderer.waveMode + " orbMix=" + s.renderer.orbMix + ")");
        return s.captureBallPngNow();
    }

    private String captureBallPngNow() {
        FloatingBallService s = this;
        String path = "";
        try {
            java.io.File dir = s.getFilesDir();
            path = new java.io.File(dir, "ball-capture.png").getAbsolutePath();
            final String out = path;
            final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    Math.max(1, s.glView.getWidth()), Math.max(1, s.glView.getHeight()),
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(s.glView, bmp,
                    new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                        @Override public void onPixelCopyFinished(int result) {
                            if (result != android.view.PixelCopy.SUCCESS) {
                                Log.w("FloatingBall", "pixel copy result=" + result);
                                bmp.recycle();
                                return;
                            }
                            java.io.OutputStream os = null;
                            try {
                                os = new java.io.FileOutputStream(out);
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os);
                                os.flush();
                                Log.i("FloatingBall", "ball capture -> " + out);
                            } catch (Throwable t) {
                                Log.w("FloatingBall", "ball capture write failed: " + t);
                            } finally {
                                try { if (os != null) os.close(); } catch (Throwable ignored) {}
                                bmp.recycle();
                            }
                        }
                    }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w("FloatingBall", "ball capture failed: " + t);
            return "";
        }
        return path;
    }

    // ══════════════════════ 取证快门（witness）═══════════════════════════════
    //  触发：**快速双击音量加**（PowerKeyDaemon 判双击 → VOLUME_SHOT）
    //        ／ files/witness 文件钩子 ／ tools/witness.sh
    //  产出：/sdcard/Pictures/Screenshots/glass_<时间>.png  ← 普通截图（玻璃层在里面，相册可见）
    //        /sdcard/glass-evidence/glass_<时间>.txt      ← 那一刻的状态机快照（数值全在里面）
    //        /sdcard/glass-evidence/glass_<时间>.log      ← logcat 里 FloatingBall 最近 400 行
    //
    //  为什么不是直接按 MIUI 的「音量减 + 电源」：玻璃层带 SKIP_SCREENSHOT（抓屏排除），
    //  系统截图/录屏里那一层是**完全隐身**的，拍下来只有字、没有胶囊。
    //  这里用 root 的 screencap 拍：拍之前临时撤掉排除 + **冻结采集**（见 GlassRenderer.freezeCapture），
    //  拍完立刻恢复 —— 拍到的就是眼睛此刻看到的那一帧；不冻结的话自己抓屏会拍到自己，
    //  玻璃当场变暗变糊，取证就成了伪证。

    private volatile boolean witnessBusy = false;
    private long witnessCount = 0;

    /**
     * 取证文件的旁挂目录 = App 自己的外部目录（这个目录**一定可写**）。
     * ⚠️ 别写成 /sdcard/glass-evidence：本 App 没申请 WRITE_EXTERNAL_STORAGE，
     *    直接写 /sdcard 根下会 ENOENT（实测第一次取证就是这么丢的 txt/log）。
     *    截图那张走 root 的 screencap，所以可以放 /sdcard/Pictures/Screenshots。
     */
    private File witnessDir() {
        return gateDir();
    }

    /**
     * 音量加双击这条路的入口：**不能被"上一张还在拍"吞掉**。
     *
     * 用户按两次是很明确的动作，而 witness 本身要撤抓屏排除 → 等两帧 → screencap →
     * 再恢复，整个过程 1~2 秒。这段时间里再按一次双击，以前直接丢掉（"没反应"）；
     * 现在排队补拍一张。另外界面被音量加藏起来时先叫回来，否则拍到的是空屏。
     */
    private void witnessCaptureFromKey() {
        if (uiHidden) applyUiHidden(false, "双击音量加取证");
        if (witnessBusy) {
            Log.i("FloatingBall", "双击音量加：上一张还在拍 → 600ms 后补拍");
            main.removeCallbacks(witnessKeyRetry);
            main.postDelayed(witnessKeyRetry, 600);
            return;
        }
        witnessCapture("双击音量加");
    }

    private final Runnable witnessKeyRetry = new Runnable() {
        @Override public void run() { witnessCaptureFromKey(); }
    };

    private void witnessCapture(final String why) {
        if (witnessBusy) { Log.i("FloatingBall", "witness: 上一张还没拍完，忽略这次"); return; }
        if (wm == null || glView == null || renderer == null) return;
        witnessBusy = true;
        final long n = ++witnessCount;
        final String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                java.util.Locale.US).format(new java.util.Date());
        final String base = "glass_" + ts + (n > 1 ? "_" + n : "");
        final File dir = witnessDir();
        final File png = new File("/sdcard/Pictures/Screenshots", base + ".png");
        try { writeAtomic(new File(dir, base + ".txt"), witnessStateText(why)); }
        catch (Throwable t) { Log.w("FloatingBall", "witness: 状态快照写失败 " + t); }
        Log.i("FloatingBall", "witness: 快门 " + base + (why == null || why.isEmpty() ? "" : "（" + why + "）"));
        vibrate(25);
        // ① 撤掉玻璃层的抓屏排除：这一层才会出现在 screencap 里
        if (!debugShowGlass) {
            try { skipScreenshotOf(glView.getSurfaceControl(), "glass", false); } catch (Throwable ignored) { }
        }
        // ①′ 文字层同样撤掉排除：玻璃 + 字一起拍下来才判得了「字到底显示没显示」
        if (!debugShowGlass && siriText != null) {
            try { applySkipScreenshotTo(siriText, "text", false); } catch (Throwable ignored) { }
        }
        // ② 冻结采集：这一小段玻璃的输入不变 → 屏上那一帧就是"真样子"
        renderer.freezeCapture = true;
        // ③ 等两帧（~220ms）让 SurfaceFlinger 吃到新状态，再拍
        main.postDelayed(new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (shootTo(png)) {
                                dumpWitnessLog(new File(dir, base + ".log"));
                                toast("已截图（含玻璃）：" + base + ".png");
                            } else {
                                toast("取证截图失败：root 不可用？");
                            }
                        } finally {
                            main.post(new Runnable() { @Override public void run() { witnessRestore(); } });
                        }
                    }
                }, "witness-shot").start();
            }
        }, 220);
    }

    /** 快门结束：解冻 + 把抓屏排除打回去（下一帧起球又"隐身"了）。 */
    private void witnessRestore() {
        if (renderer != null) renderer.freezeCapture = false;
        if (!debugShowGlass && siriText != null && siriText.getVisibility() == View.VISIBLE) {
            try { applyTextSkipScreenshot(); } catch (Throwable ignored) { }
        }
        if (!debugShowGlass && glView != null) {
            try {
                SurfaceControl sc = glView.getSurfaceControl();
                if (skipScreenshotOf(sc, "glass", true)) {
                    glassMarkedSc = sc;
                    if (renderer != null) renderer.selfExcluded = true;
                }
            } catch (Throwable ignored) { }
        }
        witnessBusy = false;
        Log.i("FloatingBall", "witness: 快门结束（抓屏排除已恢复）");
    }

    /** root `screencap -p` 拍一张到 dst。成功返回 true。 */
    private boolean shootTo(File dst) {
        String tmp = "/data/local/tmp/_witness.png";
        String d = dst.getAbsolutePath();
        String cmd = "screencap -p " + tmp
                + " && chmod 644 " + tmp
                + " && mkdir -p " + dst.getParent()
                + " && cp " + tmp + " " + d
                + " && chmod 644 " + d
                + " && rm -f " + tmp;
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            String out = readAllText(p.getInputStream());
            int rc = p.waitFor();
            Log.i("FloatingBall", "witness: screencap rc=" + rc
                    + " size=" + (dst.exists() ? dst.length() : -1)
                    + (out.isEmpty() ? "" : " out=" + out.trim()));
            if (rc == 0 && dst.exists() && dst.length() > 1024) {
                scanMedia(dst);
                return true;
            }
        } catch (Throwable t) {
            Log.w("FloatingBall", "witness: screencap 失败 " + t);
        }
        return false;
    }

    /** 让相册立刻看到这张图（MIUI 的新图库要扫描）。 */
    private void scanMedia(File f) {
        try {
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(f)));
        } catch (Throwable ignored) { }
    }

    /** logcat 里本 App 的最近 400 行（带毫秒时间戳）：和截图放一起就是完整证据。 */
    private void dumpWitnessLog(File out) {
        try {
            Process p = new ProcessBuilder("su", "-c",
                    "logcat -d -v time | grep -E 'FloatingBall|PowerBall|VoiceMap' | tail -n 400")
                    .redirectErrorStream(true).start();
            String s = readAllText(p.getInputStream());
            p.waitFor();
            writeAtomic(out, s);
            Log.i("FloatingBall", "witness: 日志 " + s.length() + " 字符 → " + out.getName());
        } catch (Throwable t) {
            Log.w("FloatingBall", "witness: 日志转储失败 " + t);
        }
    }

    private static String readAllText(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    /**
     * 状态快照：一行一个键值，脚本/人都能直接对。
     * 关键就是 **blob（弹簧算出来的胶囊矩形）** 和 **barReal（输入行窗口的真实屏幕矩形）**
     * 这两组数 —— 用户报的"搜索框自己变样/跑偏"在这一对数字上一定是先分开的。
     */
    private String witnessStateText(String why) {
        StringBuilder sb = new StringBuilder();
        sb.append("time=").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date())).append('\n');
        sb.append("why=").append(why == null ? "" : why).append('\n');
        sb.append("uptime=").append(android.os.SystemClock.uptimeMillis()).append('\n');
        sb.append("screen=").append(screenW).append('x').append(screenH)
          .append(" density=").append(sqDensity());
        if (renderer != null) sb.append(" refSize=").append(Math.round(renderer.refSize));
        sb.append('\n');
        try {
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            sb.append("glassWin=").append(loc[0]).append(',').append(loc[1]).append(',')
              .append(glView.getWidth()).append(',').append(glView.getHeight()).append('\n');
            sb.append("glassParams=").append(params.x).append(',').append(params.y).append(',')
              .append(params.width).append(',').append(params.height).append('\n');
        } catch (Throwable ignored) { }
        if (renderer != null) {
            sb.append("renderer=winX:").append(renderer.winX)
              .append(" winY:").append(renderer.winY)
              .append(" winPixel:").append(renderer.winPixelW).append('x').append(renderer.winPixelH)
              .append(" selfExcluded:").append(renderer.selfExcluded)
              .append(" freeze:").append(renderer.freezeCapture).append('\n');
        }
        sb.append("winY0=").append(Math.round(winY0())).append('\n');
        if (renderer != null) {
            sb.append("anim=state:").append(stateNow)
              .append(" waveMode:").append(renderer.waveMode)
              .append(" wavePending:").append(waveModePending)
              .append(" gate:").append(round3(renderer.waveGate))
              .append(" orbMix:").append(round3(renderer.orbMix))
              .append(" fluidStyle:").append(prefs().getInt("float_fluid_style", 0))
              .append(" fluid:").append(renderer.fluidRibs).append('/').append(renderer.fluidGain)
              .append('\n');
        }
        sb.append("flags=searchActive:").append(searchActive)
          .append(" searchOpen:").append(searchOpen)
          .append(" searchClosing:").append(searchClosing)
          .append(" zonesActive:").append(zonesActive)
          .append(" menuShown:").append(menuShown)
          .append(" glassFullscreen:").append(glassFullscreen)
          .append(" convoOpen:").append(convoOpen)
          .append(" approval:").append(approvalShown)
          .append(" uiHidden:").append(uiHidden)
          .append(" holdActive:").append(powerHoldActive).append('\n');
        if (searchActive) {
            float d = sqDensity();
            sb.append("search=phase:").append(sqPhase)
              .append(" capsH:").append(Math.round(searchCapsH))
              .append(" open:").append(searchOpen).append('\n');
            sb.append("springs=cx:").append(round3(sqCx.x))
              .append(" cy:").append(round3(sqCy.x))
              .append(" bx:").append(round3(sqBx.x))
              .append(" by:").append(round3(sqBy.x))
              .append(" k:").append(round3(sqK.x))
              .append(" dark:").append(round3(sqDark.x))
              .append(" cont:").append(round3(sqCont.x)).append('\n');
            sb.append("blob=").append(Math.round(sqCx.x * d)).append(',')
              .append(Math.round(sqCy.x * d)).append(',')
              .append(Math.round(2 * sqBx.x * d)).append(',')
              .append(Math.round(2 * sqBy.x * d)).append('\n');
            sb.append("barReq=").append(sqBarX).append(',').append(sqBarY).append(',')
              .append(sqBarW).append(',').append(sqBarH).append('\n');
            if (searchBar != null) {
                int[] bl = new int[2];
                searchBar.getLocationOnScreen(bl);
                sb.append("barReal=").append(bl[0]).append(',').append(bl[1]).append(',')
                  .append(searchBar.getWidth()).append(',').append(searchBar.getHeight()).append('\n');
                sb.append("barView=vis:").append(searchBar.getVisibility())
                  .append(" alpha:").append(round3(searchBar.getAlpha()))
                  .append(" editing:").append(searchBar.isEditing()).append('\n');
            }
        }
        if (zonesActive) {
            sb.append("zones=center:").append(Math.round(holdCenterX)).append(',')
              .append(Math.round(holdCenterY)).append(" R:").append(Math.round(zoneR))
              .append(" ring:").append(Math.round(zoneRing))
              .append(" level:").append(holdLevel)
              .append(" hover:").append(holdZoneHover).append('\n');
        }
        if (menuShown && menuRect != null) {
            sb.append("menu=").append(Math.round(menuRect[0])).append(',')
              .append(Math.round(menuRect[1])).append(',')
              .append(Math.round(menuRect[2])).append(',')
              .append(Math.round(menuRect[3])).append('\n');
        }
        return sb.toString();
    }

    /** 助手开始工作前确保球在 Siri 形变模式下（否则内容没有地方显示）。 */
    public static void ensureSiriForAssistant() {
        FloatingBallService s = INSTANCE;
        if (s == null || s.siriEnabled) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                FloatingBallService s2 = INSTANCE;
                if (s2 != null && !s2.siriEnabled) s2.applySiriEnabled(true);
            }
        });
    }

    private static int withAlpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    private static String round3(float v) { return String.valueOf(Math.round(v * 1000f) / 1000f); }

    // ── Siri 模式：球弹出、文字从球内出现、球随文字实时变宽变高、圆角变小 ──
    /** 方形态宽度 = 屏宽 × 该比例（不再随文字/字号变）。 */
    private static final float SIRI_W_RATIO = 0.78f;
    /** 方形态高度上限 = 屏高 × 该比例（用户要求 55%）。 */
    @Deprecated  // 2026-10-23 起高度上限 = 宽度（siriMaxBallH 直接返回 siriMaxBallW），这个比例不再参与计算
    private static final float SIRI_H_RATIO = 0.55f;
    private boolean siriEnabled = false;
    /**
     * 球内文字层：`ScrollView`（原生，取代原来的 TextView / WebView 两代方案）。
     *
     * 演进口径：TextView（纯文本）→ WebView（能渲染真表格，但真机上出现"有球无字"+
     * 一次 native 崩溃，且被 SKIP_SCREENSHOT 挡住没法自证）→ **现在这套原生 View 树**。
     * 原生方案的高度/字号都能在 Java 里直接量，可见性肉眼可验，且不会崩。
     */
    private android.widget.ScrollView siriText;
    /** 球内内容列（markdown 渲染成一棵 View 树放在这里）。 */
    private android.widget.LinearLayout mdColumn;
    /** 上一次建内容时的指纹（文字|宽度|字号）—— 没变就不重建。 */
    private String mdBuiltFor = "";
    /** 内容高度（px）：由 {@code mdColumn.measure()} 直接量出，不再是"猜"或"等页面回报"。 */
    private volatile int mdContentH = 0;
    /** 排版宽度（px）：方形态固定内宽，与窗口此刻多宽无关。 */
    private volatile int mdLayoutW = 0;
    /** 用户正在球里翻阅：这段时间不许缩回初始形状、也不许清空文字。 */
    private volatile boolean mdScrolling = false;
    private volatile long mdScrollAt = 0L;
    /** 松手多久之后才允许恢复自动收起（用户要求 1.5 秒）。 */
    private static final long MD_SCROLL_HOLD_MS = 1500;
    /** 文字层当前是否可触摸（内容超出视口才 true —— 装得下时手势归球）。 */
    private volatile boolean siriTextTouchable = false;
    /**
     * 方形底部给「Siri 波头 / 处理中动画」预留的**固定**带子高度（dp）。
     *
     * ★ 2026-11-08 用户定稿：「Siri 波固定在回复 AI 文字的那块玻璃的底部，无论玻璃变高
     *   还是变矮，固定只显示 Siri 波头」。所以这里不再按球高算比例（旧逻辑 14%、70~200px，
     *   玻璃一变高带子就变高、波头露出的量也跟着变），改成**和玻璃高度无关的常量**：
     *   · 文字视口底 = 玻璃底 − 这条带子 → 玻璃长高只多显示几行字，波头位置不动；
     *   · 胶囊加载 / 流体圆点在有字时也落在同一条带子里（placeAnimForText）。
     */
    private static final float ANIM_BAND_DP = 40f;

    /**
     * Siri 波头在玻璃底部的可见高度（dp）。渲染器按这个高度做一道竖直蒙版：
     * 玻璃多高、波幅多大，露出来的都只有贴底这一截（见 GlassRenderer 的 headMask）。
     */
    private static final float WAVE_HEAD_DP = 30f;

    /** 方形底部给"处理中动画"预留的带子高度（px）。 */
    private volatile int siriAnimBand = 0;
    private int animBandPx = 0;
    /** 文字区视口高度（= 文字窗高度）。 */
    private volatile int siriViewportH = 0;
    /** 球内正文字号 = 设置页「Siri 字号」× 该比例（用户真机定档 0.25 ≈ 18px）。 */
    private float mdFontRatio() {
        int pct = prefs().getInt("md_font_ratio_pct", 25);
        return Math.max(0.05f, Math.min(1.2f, pct / 100f));
    }

    /** 审批/提问面板整体缩放（设置页滑块，默认 0.55）。 */
    private float apScale() {
        int pct = prefs().getInt("ap_scale_pct", 55);
        return Math.max(0.2f, Math.min(1.5f, pct / 100f));
    }
    private WindowManager.LayoutParams siriParams;
    private volatile String siriStr = "";
    // 文字窗上一次真正落下去的位置/尺寸（用来跳过无变化的 updateViewLayout）
    private int siriTextLastX = Integer.MIN_VALUE, siriTextLastY = Integer.MIN_VALUE;
    private int siriTextLastW = -1, siriTextLastH = -1;
    // 文本测量缓存（见 updateSiriTextPos：每帧重量大段文字会把主线程吃满）
    private String textMeasuredStr = null;
    private float textMeasuredPx = -1f, textTopGap = 0f;
    private int textMeasuredMaxW = -1, textMeasuredW = -1;
    private float aMaster = 1f, aHalfX = 0.30f, aHalfY = 0.30f, aCorner = 1f;
    private float tMaster = 1f, tHalfX = 0.30f, tHalfY = 0.30f, tCorner = 1f;
    private float siriTextPx;
    private long siriTickLast = 0;
    /** 几何缓动时间常数（秒）：生长 0.06 跟手，收起 0.22 从容。 */
    private volatile float siriEaseTau = 0.06f;
    private float aBlack = 0f, tBlack = 0f; // 黑区下移动画
    /** 审批面板：黑区下沿比文字下沿再低多少（ny，1.0 = 半个玻璃高）。 */
    private static final float BLACK_GAP_NY = 0.10f;
    /**
     * 回复玻璃的**黑区是固定的**，和文字多少、玻璃高矮全都无关（用户 2026-11-09 口径：
     * 「黑区不要和文字产生关联，黑区要像当前截图的一样位置固定住」）。
     *
     * 值 = 发送给着色器的 `uBlackOffset`（ny，+1＝玻璃顶、−1＝玻璃底）。着色器里
     * `b1 = uBlack1 − uBlackOffset` 是黑区下沿、`b0 = uBlack0 − uBlackOffset` 是它的起始：
     *   · 0          → 球体默认那条渐变：**玻璃顶永远是纯黑**，到球心以下才慢慢变亮，
     *                  最底下一条最亮（用户截图上就是这块，实测黑区下沿落在 ny≈−0.01）。
     *   · 调大（>0） → 整块黑区往下压，亮面只剩玻璃底边一窄条。
     * 以前这里是「按文字实际高度反推 b1」，字一变长黑区就跟着往下长、字一清空又跳回来
     * （玻璃一缩更明显）—— 用户看到的就是「黑区跟着字跑」。
     */
    private static final float BLACK_FIXED_NY = 0.0f;
    // 最短状态 = 原来的水晶球（0/1 个字时），不能再短
    private float minHalfX = 0.37f, minHalfY = 0.25f, minCorner = 1f;
    /**
     * **原版**球的半宽/半高（未缩放，占基准 base = winSize×0.9 的份额）。
     *
     * 用户 2026-11-06：「把所有胶囊形时的大小等比例缩小到宽度为当前屏幕宽度的三分之一」
     * —— 说的是**刚启动时那颗胶囊状的球**（见 {@link #ballIdleScale()}）。
     *
     * 所以这里分成两套：
     *   · {@link #minHalfX}/{@link #minHalfY} = **球自己**的大小（乘过缩放系数）；
     *   · minHalfX0/minHalfY0 = **原版**大小，留给「球一放大就变成的那些面板」——
     *     六区菜单的圆、列表面板、聊天卡片、审批面板。它们一律按原版算，
     *     球缩了它们也一点没变（用户原话：菜单/聊天/搜索/审批都不能受影响）。
     */
    private float minHalfX0 = 0.37f, minHalfY0 = 0.25f;
    /** 启动时那颗球的宽度 = 屏宽 × 这个比例（用户 2026-11-06 定 1/3）。 */
    private static final float BALL_W_RATIO = 1f / 3f;

    public static void setSiriText(String text) {
        FloatingBallService s = INSTANCE;
        if (s != null && s.convoOpen && !s.suppressConvoAutoClose
                && (text == null || text.isEmpty())) {
            s.main.post(new Runnable() {
                @Override public void run() { s.closeConversation("球回到初始态"); }
            });
        }
        if (s != null) s.main.post(() -> s.applySiriText(text));
    }

    /** Siri 文字字号(sp)，实时生效：重设 TextView 字号并重新测量球体尺寸。 */
    public static void setSiriTextSize(int sp) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applySiriTextSize(sp));
    }

    private void applySiriTextSize(int sp) {
        sp = Math.max(10, Math.min(120, sp));
        siriTextPx = dp(sp);
        mdBuiltFor = "";          // 字号变了 → 重建内容并重量高度
        rebuildMarkdown();
    }

    /**
     * 离线预览：把示例 markdown 显示在球里并**钉住**，方便慢慢调字号/排版（不依赖 DSH）。
     */
    public static void previewText(final String text) {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                s.applySiriText(text);
                setAssistantPinned(true);
                // 预览必须进方形态：先让几何展开（否则 squareFormActive() 会把内容挡掉）
                s.enterTextHold();
                s.mdBuiltFor = "";
                s.rebuildMarkdown();
                Log.i("FloatingBall", "离线预览：灌入示例（"
                        + (text == null ? 0 : text.length()) + " 字）并钉住");
            }
        });
    }

    /** 结束离线预览（解除钉住 + 清空 + 回圆球）。 */
    public static void previewStop() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                setAssistantPinned(false);
                s.applySiriText("");
                s.releaseTextHold();
                Log.i("FloatingBall", "离线预览：结束");
            }
        });
    }

    /** 读离线预览样例（`files/md-sample.md`，没有就返回 null，调用方用内置那份）。 */
    static String readPreviewSample() {
        try {
            FloatingBallService s = INSTANCE;
            java.io.File dir = (s != null) ? s.getExternalFilesDir(null) : null;
            if (dir != null) {
                java.io.File f = new java.io.File(dir, "md-sample.md");
                if (f.isFile() && f.length() > 0 && f.length() < 64 * 1024) {
                    byte[] buf = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    int n = in.read(buf);
                    in.close();
                    if (n > 0) return new String(buf, 0, n, "UTF-8").trim();
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 设置页改了「球内字号比例」→ 立刻重建内容（字号即时生效）。 */
    public static void onMdStyleChanged() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                s.mdBuiltFor = "";
                s.rebuildMarkdown();
            }
        });
    }

    /** 设置页改了「审批面板缩放」→ 下次弹就是新尺寸（挂着的话当场重排）。 */
    public static void onApStyleChanged() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                s.apGeomDirty = true;
                if (s.approvalShown && s.approvalReq != null) {
                    s.applyApprovalGeometry();
                    s.updateApprovalWindow();
                    s.updateApprovalLayout();
                }
            }
        });
    }

    /** 思考中流体圆点的环半径（屏幕像素），实时生效。 */
    public static void setOrbRadius(int px) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyOrbRadius(px));
    }

    /** 思考中流体圆点的亮度增益（百分之一，110=1.10），实时生效。 */
    public static void setOrbGain(int pct) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyOrbGain(pct));
    }

    public static int getOrbRadius() {
        FloatingBallService s = INSTANCE;
        return s == null ? 90 : s.prefs().getInt("float_orb_radius", 90);
    }

    public static int getOrbGain() {
        FloatingBallService s = INSTANCE;
        return s == null ? 110 : s.prefs().getInt("float_orb_gain", 110);
    }

    /** 思考中圆点带起的边缘辉光（百分之一，130=1.30），实时生效。 */
    public static void setOrbRim(int pct) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyOrbRim(pct));
    }

    public static int getOrbRim() {
        FloatingBallService s = INSTANCE;
        return s == null ? 130 : s.prefs().getInt("float_orb_rim", 130);
    }

    private void applyOrbRim(int pct) {
        pct = Math.max(0, Math.min(400, pct));
        prefs().edit().putInt("float_orb_rim", pct).apply();
        if (renderer != null) renderer.orbRim = pct / 100f;
    }

    private void applyOrbRadius(int px) {
        px = Math.max(30, Math.min(300, px));
        prefs().edit().putInt("float_orb_radius", px).apply();
        if (renderer != null) renderer.orbRadius = px;
    }

    private void applyOrbGain(int pct) {
        pct = Math.max(10, Math.min(400, pct));
        prefs().edit().putInt("float_orb_gain", pct).apply();
        if (renderer != null) renderer.orbGain = pct / 100f;
    }

    // ── 思考中动画：9 种胶囊加载（0 = 关；1..9 = 脉冲/珠链/螺旋/环轨/星座/彗尾/磁吸/跳环/涟漪）──
    public static void setThinkLoader(int v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyThinkLoader(v));
    }

    public static int getThinkLoader() {
        FloatingBallService s = INSTANCE;
        return s == null ? 0 : s.prefs().getInt("think_loader", 0);
    }

    public static void setThinkLoaderSpeed(float v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyThinkLoaderSpeed(v));
    }

    public static float getThinkLoaderSpeed() {
        FloatingBallService s = INSTANCE;
        return s == null ? 1f : s.prefs().getFloat("think_loader_speed", 1f);
    }

    public static void setThinkStyle(int st) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyThinkStyle(st));
    }

    public static int getThinkStyle() {
        FloatingBallService s = INSTANCE;
        return s == null ? 0 : s.prefs().getInt("think_style", 0);
    }

    public static void setThinkLoaderScale(float v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyThinkLoaderScale(v));
    }

    public static float getThinkLoaderScale() {
        FloatingBallService s = INSTANCE;
        return s == null ? 1f : s.prefs().getFloat("think_loader_scale", 1f);
    }

    public static void setThinkLoaderDot(float v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyThinkLoaderDot(v));
    }

    public static float getThinkLoaderDot() {
        FloatingBallService s = INSTANCE;
        return s == null ? 1f : s.prefs().getFloat("think_loader_dot", 1f);
    }

    // ── 思考中动画：圆点 / 连续发光流体（Fluid Glow）──────────────────────────
    // 用户：现有两种动画（Siri 波、dots）之外，再要一个「和 dots 同一套光核的连续流体」，
    // 颜色可纯白/单色/混色，亮度不均（有的刺眼有的柔和），背景跟 dots 一样只有纯黑。
    // 实现：它就是一种新的 waveMode（3），几何/光核在 GlassRenderer.fluidScenePx()。

    /** 思考中动画形态：0 = 流体圆点（dots），1 = 连续发光流体（fluid glow）。 */
    public static void setFluidStyle(int v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidStyle(v));
    }

    public static int getFluidStyle() {
        FloatingBallService s = INSTANCE;
        return s == null ? 0 : s.prefs().getInt("float_fluid_style", 0);
    }

    /** 流体发光的颜色模式：0 纯白 / 1 单色（配 Hue）/ 2 混色（按丝线走光谱）。 */
    public static void setFluidColorMode(int v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidColorMode(v));
    }

    public static int getFluidColorMode() {
        FloatingBallService s = INSTANCE;
        return s == null ? 0 : s.prefs().getInt("float_fluid_color_mode", 0);
    }

    /** 单色模式的色相（0..359）。 */
    public static void setFluidHue(int hue) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidHue(hue));
    }

    public static int getFluidHue() {
        FloatingBallService s = INSTANCE;
        return s == null ? 190 : s.prefs().getInt("float_fluid_hue", 190);
    }

    /** 刺眼程度（百分之一，100 = 1.00）：越大，越多的段落烧到纯白。 */
    public static void setFluidHot(int pct) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidHot(pct));
    }

    public static int getFluidHot() {
        FloatingBallService s = INSTANCE;
        return s == null ? 100 : s.prefs().getInt("float_fluid_hot", 100);
    }

    /** 丝线密度（条）：球小的时候 8~12 最清楚。 */
    public static void setFluidRibs(int v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidRibs(v));
    }

    public static int getFluidRibs() {
        FloatingBallService s = INSTANCE;
        return s == null ? 14 : s.prefs().getInt("float_fluid_ribs", 12);
    }

    /** 体量（百分之一，100 = 和圆点环同半径）。 */
    public static void setFluidSize(int pct) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidSize(pct));
    }

    public static int getFluidSize() {
        FloatingBallService s = INSTANCE;
        return s == null ? 78 : s.prefs().getInt("float_fluid_size", 100);
    }

    /** 断线密度：0 = 整根连续；越大段越短（百分之一刻度，600 = 6.0）。 */
    public static void setFluidDash(int pct) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidDash(pct));
    }

    public static int getFluidDash() {
        FloatingBallService s = INSTANCE;
        return s == null ? 600 : s.prefs().getInt("float_fluid_dash", 0);
    }

    private void applyFluidDash(int pct) {
        pct = Math.max(0, Math.min(2400, pct));
        prefs().edit().putInt("float_fluid_dash", pct).apply();
        if (renderer != null) renderer.fluidDash = pct / 100f;
    }

    /** 流体亮度（百分之一，100 = 1.00）。 */
    public static void setFluidGain(int pct) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applyFluidGain(pct));
    }

    public static int getFluidGain() {
        FloatingBallService s = INSTANCE;
        return s == null ? 85 : s.prefs().getInt("float_fluid_gain", 120);
    }

    /**
     * 设置页的「预览」：直接把球钉在思考态，方便一边拖滑杆一边看效果。
     * （用户报「点了没反应」——真因是球只在思考中才显示这个动画，空闲时改了也看不见。）
     */
    public static void previewThinking() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(() -> {
            Log.i("FloatingBall", "预览：钉住思考动画");
            setAssistantState("THINKING");
        });
    }

    public static void previewIdle() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(() -> {
            Log.i("FloatingBall", "预览：结束");
            setAssistantState("IDLE");
        });
    }

    /** 思考中动画 waveMode：1 = Siri 波，2 = dots，3 = 流体发光；0 = 关（胶囊加载器）。 */
    private int thinkForm() {
        int st = prefs().getInt("think_style", 0);
        if (st >= 4) return 0;                 // 9 种胶囊加载
        if (st >= 1 && st <= 3) return st;     // 原版：Siri 波 / dots / 流体发光
        if (prefs().getInt("think_loader", 0) > 0) return 0;
        return prefs().getInt("float_fluid_style", 0) == 1 ? 3 : 2;
    }

    private void applyThinkStyle(int st) {
        if (AnimScene.master(animPrefs())) return;   // 已交给「动画总控」
        st = Math.max(0, Math.min(3, st));
        prefs().edit().putInt("think_style", st).apply();
        if (st >= 1 && st <= 3) {
            prefs().edit().putInt("think_loader", 0).apply();
            if (renderer != null) {
                renderer.suppressWave = false;
                renderer.loaderMode = 0;
            }
        }
        if (renderer != null && "THINKING".equals(stateNow)) {
            renderer.waveOn = true;
            setWaveForm(thinkForm());
            waveGateTarget = 1f;
        }
        syncThinkLoader(true);
    }

    private void applyFluidStyle(int v) {
        if (AnimScene.master(animPrefs())) return;   // 已交给「动画总控」
        v = v == 1 ? 1 : 0;
        prefs().edit().putInt("float_fluid_style", v).apply();
        if (renderer == null) return;
        // 正在思考中就立刻换脸（走包络，不硬切）
        if ("THINKING".equals(stateNow)) {
            saveWaveOnce();
            renderer.waveOn = true;
            setWaveForm(v == 1 ? 3 : 2);
            setOrbMixTarget(1f);
            waveGateTarget = 1f;
        }
        Log.i("FloatingBall", "思考中动画 → " + (v == 1 ? "流体发光" : "流体圆点"));
    }

    private void applyThinkLoader(int v) {
        if (AnimScene.master(animPrefs())) return;   // 已交给「动画总控」
        v = Math.max(0, Math.min(9, v));
        prefs().edit().putInt("think_loader", v).apply();
        prefs().edit().putInt("think_style", v > 0 ? v + 3 : 0).apply();
        if (renderer != null) {
            renderer.suppressWave = v > 0;
            renderer.loaderSpeed = prefs().getFloat("think_loader_speed", 1f);
            renderer.loaderScale = prefs().getFloat("think_loader_scale", 1f);
            renderer.loaderDot = prefs().getFloat("think_loader_dot", 1f);
            renderer.loaderMode = "THINKING".equals(stateNow) ? v : 0;
            if ("THINKING".equals(stateNow)) {
                renderer.waveOn = true;
                setWaveForm(thinkForm());
                waveGateTarget = 1f;
            }
        }
        syncThinkLoader(true);
        Log.i("FloatingBall", "思考中胶囊加载 → " + (v == 0 ? "关（dots/流体）" : String.valueOf(v)));
    }

    private void applyThinkLoaderSpeed(float v) {
        v = Math.max(0.2f, Math.min(2.5f, v));
        prefs().edit().putFloat("think_loader_speed", v).apply();
        if (renderer != null) renderer.loaderSpeed = v;
        syncThinkLoader(true);
    }

    private void applyThinkLoaderScale(float v) {
        v = Math.max(0.2f, Math.min(8f, v));
        prefs().edit().putFloat("think_loader_scale", v).apply();
        if (renderer != null) renderer.loaderScale = v;
        syncThinkLoader(true);
    }

    private void applyThinkLoaderDot(float v) {
        v = Math.max(0.2f, Math.min(6f, v));
        prefs().edit().putFloat("think_loader_dot", v).apply();
        if (renderer != null) renderer.loaderDot = v;
        syncThinkLoader(true);
    }

    private void applyFluidColorMode(int v) {
        v = Math.max(0, Math.min(2, v));
        prefs().edit().putInt("float_fluid_color_mode", v).apply();
        if (renderer != null) renderer.fluidColorMode = v;
    }

    private void applyFluidHue(int hue) {
        hue = ((hue % 360) + 360) % 360;
        prefs().edit().putInt("float_fluid_hue", hue).apply();
        if (renderer == null) return;
        int c = android.graphics.Color.HSVToColor(new float[]{ hue, 0.85f, 1f });
        renderer.fluidColorR = android.graphics.Color.red(c) / 255f;
        renderer.fluidColorG = android.graphics.Color.green(c) / 255f;
        renderer.fluidColorB = android.graphics.Color.blue(c) / 255f;
    }

    private void applyFluidHot(int pct) {
        pct = Math.max(0, Math.min(200, pct));
        prefs().edit().putInt("float_fluid_hot", pct).apply();
        if (renderer != null) renderer.fluidHot = pct / 100f;
    }

    private void applyFluidRibs(int v) {
        v = Math.max(4, Math.min(20, v));
        prefs().edit().putInt("float_fluid_ribs", v).apply();
        if (renderer != null) renderer.fluidRibs = v;
    }

    private void applyFluidSize(int pct) {
        pct = Math.max(10, Math.min(400, pct));
        prefs().edit().putInt("float_fluid_size", pct).apply();
        if (renderer != null) renderer.fluidSize = pct / 100f;
    }

    private void applyFluidGain(int pct) {
        pct = Math.max(20, Math.min(300, pct));
        prefs().edit().putInt("float_fluid_gain", pct).apply();
        if (renderer != null) renderer.fluidGain = pct / 100f;
    }

    /** 把流体发光的全部参数从 prefs 推到 renderer（启动/热重载用）。 */
    /**
     * 调试口子（改流体发光的手感不用重装）：
     *   echo '{"style":1,"colorMode":0,"hue":190,"hot":1.2,"ribs":9,"size":1.0,"gain":1.0}' \
     *     > /sdcard/Android/data/com.glslplayground.app/files/fluid.json
     *   touch /sdcard/Android/data/com.glslplayground.app/files/state-FLUID
     * 每个字段都可省略；改完立刻生效（正在思考中就当场换）。
     */
    private void applyFluidDebugFile() {
        java.io.File f = new java.io.File("/sdcard/Android/data/com.glslplayground.app/files/fluid.json");
        String txt = null;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b); in.close();
            txt = new String(b, 0, Math.max(0, n), "UTF-8").trim();
        } catch (Throwable ignored) { }
        if (txt == null || txt.isEmpty()) { Log.w("FloatingBall", "fluid.json 读不到"); return; }
        try {
            org.json.JSONObject o = new org.json.JSONObject(txt);
            if (o.has("style"))     applyFluidStyle(o.optInt("style", 1));
            if (o.has("colorMode")) applyFluidColorMode(o.optInt("colorMode", 0));
            if (o.has("hue"))       applyFluidHue(o.optInt("hue", 190));
            if (o.has("hot"))       applyFluidHot(Math.round((float) o.optDouble("hot", 1.0) * 100f));
            if (o.has("ribs"))      applyFluidRibs(o.optInt("ribs", 9));
            if (o.has("size"))      applyFluidSize(Math.round((float) o.optDouble("size", 1.0) * 100f));
            if (o.has("gain"))      applyFluidGain(Math.round((float) o.optDouble("gain", 1.0) * 100f));
            if (o.has("dash"))      applyFluidDash(Math.round((float) o.optDouble("dash", 6.0) * 100f));
            Log.i("FloatingBall", "fluid debug: " + txt);
        } catch (Throwable t) {
            Log.w("FloatingBall", "fluid.json 解析失败：" + t);
        }
    }

    private void applyFluidPrefs() {
        if (renderer == null) return;
        renderer.fluidRibs  = Math.max(4, Math.min(24, prefs().getInt("float_fluid_ribs", 12)));
        renderer.fluidSize  = Math.max(0.1f, Math.min(4.0f, prefs().getInt("float_fluid_size", 100) / 100f));
        renderer.fluidHot   = Math.max(0f, Math.min(2f, prefs().getInt("float_fluid_hot", 100) / 100f));
        renderer.fluidGain  = Math.max(0.2f, Math.min(3f, prefs().getInt("float_fluid_gain", 120) / 100f));
        renderer.fluidDash  = Math.max(0f, Math.min(24f, prefs().getInt("float_fluid_dash", 0) / 100f));
        renderer.fluidColorMode = Math.max(0, Math.min(2, prefs().getInt("float_fluid_color_mode", 0)));
        int hue = prefs().getInt("float_fluid_hue", 190);
        int c = android.graphics.Color.HSVToColor(new float[]{ hue, 0.85f, 1f });
        renderer.fluidColorR = android.graphics.Color.red(c) / 255f;
        renderer.fluidColorG = android.graphics.Color.green(c) / 255f;
        renderer.fluidColorB = android.graphics.Color.blue(c) / 255f;
    }

    /**
     * 球（胶囊形）的整体缩放系数：把球宽从「2 × 基准 × 原版半宽」缩到「屏宽 × {@link #BALL_W_RATIO}」。
     * 高度、圆角、窗口、触摸区、拖拽夹取、家位置全部跟着它走 —— 等比缩小，形状比例不变。
     *
     * ⚠️ 只有**球本身**用它（minHalfX/minHalfY）；菜单/聊天卡/审批面板读的是原版
     * minHalfX0/minHalfY0，所以球变小不影响它们。
     */
    private float ballIdleScale() {
        if (screenW <= 0 || winSize <= 0) return 1f;      // 还没量到屏幕：按原版走
        float curW = 2f * winSize * 0.9f * Math.max(0.02f, minHalfX0);
        float s = (screenW * BALL_W_RATIO) / Math.max(1f, curW);
        return Math.max(0.02f, Math.min(1f, s));
    }

    private void applySiriEnabled(boolean en) {
        siriEnabled = en;
        if (en) {
            siriTextPx = dp(prefs().getInt("float_siri_sp", 34));
            // Siri 波头高度是**固定 dp**：玻璃长高/变矮，露出来的波头都一样多。
            if (renderer != null) renderer.waveHeadPx = dp(WAVE_HEAD_DP);
            // 记录“原来的水晶球”尺寸，作为最短形态。
            // 这里刻意【不】乘整体 scale(master)：设置页的「球体大小」滑块走的是 master，
            // 而 Siri 模式下球体几何走 svHalf*/tMaster —— minHalf 只作为「空球时的基准比例」，
            // 球实际多大由 master 决定。两个都乘会让滑块一动就双重缩放。
            if (renderer != null) {
                if (renderer.halfX > 0.02f) minHalfX0 = renderer.halfX;
                if (renderer.halfY > 0.02f) minHalfY0 = renderer.halfY;
                minCorner = Math.max(0.5f, Math.min(1f, renderer.corner));
            }
            // ★ 2026-11-06 用户：启动时那颗球（胶囊形）整体缩到「屏宽 1/3」，等比缩。
            //   minHalfX/Y = 球自己的大小；minHalfX0/minHalfY0 保留原版给菜单/卡片用。
            float bs = ballIdleScale();
            minHalfX = minHalfX0 * bs;
            minHalfY = minHalfY0 * bs;
            // 普通球那条渲染路也按同一个系数缩（见 GlassRenderer.idleScale 的说明）
            if (renderer != null) renderer.idleScale = bs;
            Log.i("FloatingBall", "ball: 常态球 " + Math.round(2f * winSize * 0.9f * minHalfX)
                    + "x" + Math.round(2f * winSize * 0.9f * minHalfY) + "（屏宽 1/"
                    + Math.round(1f / Math.max(0.001f, BALL_W_RATIO)) + "，×" + round3(bs)
                    + "；原版 " + Math.round(2f * winSize * 0.9f * minHalfX0) + "x"
                    + Math.round(2f * winSize * 0.9f * minHalfY0) + "）");
            // 初始形态必须是"原来的水晶球"，避免用默认值(0.30/0.30 → 圆形)一闪
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner;
            aMaster = 1f; aHalfX = minHalfX; aHalfY = minHalfY; aCorner = minCorner;
            if (siriText == null) setupSiriText();
            measureSiriTargets(siriStr);
            main.removeCallbacks(siriTick);
            siriTickLast = 0;
            main.post(siriTick);
        } else {
            main.removeCallbacks(siriTick);
            removeSiriText();
            if (renderer != null) {
                renderer.siriGeom = false; renderer.svBlackOffset = 0f;
                renderer.waveInGlass = 0f;
            }
        }
    }

    private void setupSiriText() {
        if (siriText != null || wm == null) return;
        if (siriTextPx <= 0) siriTextPx = dp(34);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(false);
        mdColumn = new android.widget.LinearLayout(this);
        mdColumn.setOrientation(android.widget.LinearLayout.VERTICAL);
        sv.addView(mdColumn, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setAlpha(0f);
        // 用户翻阅时保持方形态：原生 ScrollView 能直接给我们触摸/滚动事件
        // （WebView 时代得靠 JS 回调，那条路踩崩过）。
        sv.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                    case android.view.MotionEvent.ACTION_MOVE:
                        mdScrolling = true;
                        mdScrollAt = android.os.SystemClock.uptimeMillis();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        mdScrollAt = android.os.SystemClock.uptimeMillis();
                        break;
                    default: break;
                }
                return false;      // 不消费，交给 ScrollView 自己滚
            }
        });
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                mdScrollAt = android.os.SystemClock.uptimeMillis();
            }
        });
        int type = overlayType();
        siriParams = new WindowManager.LayoutParams(
                2, 2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // 半透明窗口**默认就吃触摸**：文字层没字时也必须明确声明"只画不摸"，
        // 否则它会以自己那个矩形（球内正文区）吞掉落在球中部的点按（用户 2026-10-22 报的
        // 「球周围/球下方点不动」的另一半原因）。真正要滚的时候 syncSiriTouchLayer 会摘掉它。
        siriParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        siriParams.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(sv, siriParams);
            trackWindow(sv);
            siriText = sv;
            mdBuiltFor = "";
            sv.post(this::applyTextSkipScreenshot);
            setupSiriTouchLayer();
            rebuildMarkdown();
        } catch (Exception e) {
            Log.w("FloatingBall", "add siri text (native) failed: " + e);
        }
    }

    /**
     * 按当前文字重建球内内容（原生 View 树），并**直接量出内容高度**。
     *
     * 这是原方案里最绕的一块（WebView 要靠页面 JS 回报高度 + 猜缩放），
     * 换成原生之后就是一次 measure：宽度给定、高度立刻出来，单位就是物理像素。
     */
    private void rebuildMarkdown() {
        if (mdColumn == null) return;
        // ⚠️ 必须保证字号已初始化：siriTextPx 平时由 measureSiriTargets/setupSiriText 设，
        // 但预览/设置页可能在它们之前就触发重建 —— 那时 siriTextPx=0，正文会被夹到下限 9px
        //（实测"预览里字特别小"就是这个）。
        if (siriTextPx <= 0) siriTextPx = dp(27);
        String src = displayTextOf(siriStr);
        int width = Math.max((int) dp(120), mdLayoutWidthPx());
        // ★ 会话卡要显示整段会话（几轮问答 + 表格），上限放宽到 6000 字；
        //   球里"当前这句回复"仍按 BallMarkdownView.MAX_CHARS 截断（球不是日志窗口）。
        //   卡/回复两种口径写进指纹 —— 切换时必须重建，否则沿用了另一套上限。
        final int mdCap = convoOpen ? 6000 : BallMarkdownView.MAX_CHARS;
        String key = src.length() + "|" + width + "|" + Math.round(siriTextPx)
                + "|" + Math.round(mdFontRatio() * 1000) + "|" + mdCap;
        if (key.equals(mdBuiltFor)) return;
        mdBuiltFor = key;

        BallMarkdownView.Style st = new BallMarkdownView.Style();
        st.bodyPx = Math.max(9f, siriTextPx * mdFontRatio());
        st.headPx = st.bodyPx * 1.06f;
        st.codePx = st.bodyPx * 0.94f;
        st.padH = (int) dp(20);
        // ⚠️ 顶部留白用固定的小值（0/10px 会让首行离球顶显得很空）。
        st.padTop = (int) dp(8);
        st.padBottom = (int) dp(10);
        st.gap = Math.round(st.bodyPx * 0.55f);

        mdColumn.removeAllViews();
        BallMarkdownView.Result r = BallMarkdownView.build(this, src, st, mdCap);
        mdColumn.addView(r.view, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        mdColumn.measure(wSpec, hSpec);
        mdContentH = Math.max(1, mdColumn.getMeasuredHeight());
        mdLayoutW = width;
        Log.i("FloatingBall", "md rebuild: 内容=" + mdContentH + "px 宽=" + width
                + " 字号=" + Math.round(st.bodyPx) + "px len=" + src.length()
                + (r.truncated ? "（超长已截断）" : ""));
        if (siriEnabled) measureSiriTargets(siriStr);
    }

    /**
     * **保证文字层里真的有内容**（用户 2026-11-08 实拍：「菜单里字消失是对的，回来以后字没回来」）。
     *
     * 病根：{@link #forceHideAllText()} 为了"一帧干净"会把 `mdColumn` 的子 View **全部删掉**
     * 并把 `mdBuiltFor` 清空（进六区菜单 / 开胶囊 / 收字回球都会走它）。可是之后所有
     * 「把字放出来」的路径（{@link #showSiriTextNow()} / `measureSiriTargets()` /
     * {@link #restoreReplyTextAfterUi()}）**只改可见性**，不重建内容 —— 于是玻璃照旧长回来了，
     * 里面却一个字都没有；`siriStr` 本身是好的，所以只有"再来一段新回复"才能碰巧恢复。
     *
     * 这里就是那个补刀口：内容被清空过（子 View 数为 0 或 `mdBuiltFor` 为空）就按 `siriStr`
     * 重建一次。已经在的（子 View 有内容、`mdBuiltFor` 有值）直接返回，16ms 的 tick 上调
     * 也不会反复排 StringBuilder。
     *
     * @return true = 这次真的重建了（几何已经跟着重量过）
     */
    private boolean ensureMarkdownContent() {
        if (mdColumn == null) return false;
        String src = displayTextOf(siriStr);
        if (src == null || src.trim().isEmpty()) return false;
        if (mdColumn.getChildCount() > 0 && mdBuiltFor != null && !mdBuiltFor.isEmpty()) return false;
        Log.i("FloatingBall", "文字层是空的（内容被清空过）→ 按 siriStr 重建（" + src.length() + " 字）");
        mdBuiltFor = "";
        rebuildMarkdown();
        return true;
    }

    /** 球内内容的排版宽度（方形态固定内宽）。 */
    private int mdLayoutWidthPx() {
        return Math.round(siriMaxBallW() - 2f * dp(20));
    }

    // ── 文字上的「点击区」：一个透明层，压在文字窗之上 ────────────────────────
    // 为什么不直接把文字 TextView 变可触摸：文字窗盖住球心一大块，一旦可触摸，
    // 用户就没法从文字那块拖动球了（触摸全被文字吃掉）。所以改成：
    //   · 文字层永远 NOT_TOUCHABLE（只管画）
    //   · 上面盖一个同尺寸的透明层，只在「有文字」时存在
    //   · 它只认「单击=钉住/解除、长按=解除」；一旦移动超过阈值就转成拖球，
    //     用的是和球完全相同的夹取逻辑（moveBallDrag），所以手感一致
    private volatile boolean siriTextShown = false;   // 文字当前是否在屏上（不依赖 getVisibility，测量期间布局还没更新）
    private View siriTouch;
    private WindowManager.LayoutParams siriTouchParams;
    private float touchDownRawX, touchDownRawY;
    private int touchStartX, touchStartY;
    private boolean touchDragging;

    private void setupSiriTouchLayer() {
        if (siriTouch != null || wm == null) return;
        View v = new View(this);
        v.setBackgroundColor(0x00000000);      // 透明，但可触摸
        v.setClickable(true);
        int type = overlayType();
        siriTouchParams = new WindowManager.LayoutParams(
                2, 2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        markTrustedOverlay(siriTouchParams);
        siriTouchParams.gravity = Gravity.TOP | Gravity.START;
        final android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                main.removeCallbacks(togglePin);
                main.postDelayed(togglePin, 60);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                main.removeCallbacks(togglePin);       // 双击不切换，避免误触
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                main.removeCallbacks(togglePin);
                if (asstTextPinned) setAssistantPinned(false);
            }
        });
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 机器人的触摸不执行任何手势（钉住/解除/拖球都不做）
                        if (isSyntheticTouch(e)) { onSyntheticDown(e); return true; }
                        touchDownRawX = e.getRawX(); touchDownRawY = e.getRawY();
                        touchStartX = params.x; touchStartY = params.y;
                        touchDragging = false;
                        long now = System.currentTimeMillis();
                        if (now - lastTextTapAt < 400) main.removeCallbacks(togglePin);
                        lastTextTapAt = now;
                        gd.onTouchEvent(e);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float moved = Math.abs(e.getRawX() - touchDownRawX)
                                + Math.abs(e.getRawY() - touchDownRawY);
                        if (!touchDragging && moved > dp(10)) {
                            touchDragging = true;
                            main.removeCallbacks(togglePin);   // 拖了就不算点击
                        }
                        if (touchDragging) {
                            moveBallDrag(touchStartX + (e.getRawX() - touchDownRawX),
                                    touchStartY + (e.getRawY() - touchDownRawY));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        gd.onTouchEvent(e);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        touchDragging = false;
                        gd.onTouchEvent(e);
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            wm.addView(v, siriTouchParams);
            siriTouch = v;
            trackWindow(v);
            Log.i("FloatingBall", "touch layer added");
        } catch (Exception e) {
            Log.w("FloatingBall", "add siri touch layer failed: " + e);
        }
    }

    // ══ 触摸来源：这一次触摸是「人」还是「机器」═══════════════════════════════

    /** 记下真实触摸屏的 deviceId 白名单（注入事件的 deviceId 不在里面）。 */
    private void refreshTouchDevices() {
        realTouchDevices.clear();
        try {
            for (int id : android.view.InputDevice.getDeviceIds()) {
                android.view.InputDevice d = android.view.InputDevice.getDevice(id);
                if (d == null) continue;
                int src = d.getSources();
                if ((src & android.view.InputDevice.SOURCE_TOUCHSCREEN) != 0
                        || (src & android.view.InputDevice.SOURCE_STYLUS) != 0) {
                    realTouchDevices.add(id);
                }
            }
        } catch (Throwable t) {
            Log.w("FloatingBall", "refreshTouchDevices failed: " + t);
        }
        // 万一 0 号就是主触摸屏 → 判据失效。宁可漏判机器，也不能把真手指当机器（球会点不动）。
        if (realTouchDevices.contains(0)) {
            Log.w("FloatingBall", "输入设备 0 是触摸屏 → 触摸来源判定关闭（区分不了人/机器）");
            realTouchDevices.clear();
        }
        Log.i("FloatingBall", "真实触摸设备 = " + realTouchDevices);
    }

    /** 这一次触摸是不是注入的（机器人）。白名单为空时一律当"人"。 */
    private boolean isSyntheticTouch(MotionEvent e) {
        if (realTouchDevices.isEmpty()) return false;
        return !realTouchDevices.contains(e.getDeviceId());
    }

    /**
     * 机器人的触摸落在球上：**不做任何用户手势**，并且立刻自动让开一段。
     *
     * 这一层不依赖任何协议：即使门控 socket、包装器、插件全部失联，
     * 也只会"少点中一下"，不会出现"点一下球被拖走 / 弹出四区 / 蹦出搜索框"。
     */
    private void onSyntheticDown(MotionEvent e) {
        swallowedCount++;
        lastSyntheticAt = System.currentTimeMillis();
        syntheticDuckUntil = lastSyntheticAt + SYNTHETIC_DUCK_MS;
        Log.i("FloatingBall", "synthetic touch dev=" + e.getDeviceId()
                + " @" + Math.round(e.getRawX()) + "," + Math.round(e.getRawY())
                + " → 不执行手势，自动让开 " + SYNTHETIC_DUCK_MS + "ms");
        applyBallTouchability();
        lastDiagAt = 0;          // 下一拍（≤40ms）就把计数写进 ball-diag，别等 2s
    }

    // ══ 可触摸区域（Region）：让开的真身 ═════════════════════════════════════

    /** 给窗口挂「可触摸区域」计算器。成功返回 true（之后让开就只改 Region，不搬窗口）。 */
    private boolean installTouchRegion(final View v) {
        if (v == null) return false;
        if (regionInstalled.contains(v)) return true;
        try {
            final Class<?> listenerCls = Class.forName(
                    "android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            final Class<?> infoCls = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            final Method setTouchableInsets = infoCls.getMethod("setTouchableInsets", int.class);
            final java.lang.reflect.Field regionField = infoCls.getField("touchableRegion");
            final int TOUCHABLE_INSETS_REGION = 3;   // InternalInsetsInfo.TOUCHABLE_INSETS_REGION
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerCls.getClassLoader(), new Class<?>[]{listenerCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method m, Object[] a) {
                            if (a != null && a.length == 1 && a[0] != null) {
                                try {
                                    setTouchableInsets.invoke(a[0], TOUCHABLE_INSETS_REGION);
                                    Object region = regionField.get(a[0]);
                                    if (region instanceof android.graphics.Region) {
                                        buildTouchRegion(v, (android.graphics.Region) region);
                                    }
                                } catch (Throwable ignored) { }
                            }
                            return null;
                        }
                    });
            android.view.ViewTreeObserver.class
                    .getMethod("addOnComputeInternalInsetsListener", listenerCls)
                    .invoke(v.getViewTreeObserver(), listener);
            regionInstalled.add(v);
            return true;
        } catch (Throwable t) {
            Log.w("FloatingBall", "installTouchRegion failed: " + t);
            return false;
        }
    }

    /** 代理窗口建好之后，把之前就存在的触摸窗口（文字层等）补挂一遍。 */
    private void backfillTouchRegions() {
        for (View v : allTouchViews()) {
            if (v == ballTouch) continue;
            installTouchRegion(v);
        }
    }

    /**
     * 当前该给这个窗口多大的可触摸区。
     *   · 让开中        → 空（触摸直接穿到下面的 App）
     *   · 球是圆的/胶囊 → 内切圆角矩形（方角那圈"看不见的死区"从此归下面的 App）
     *   · 其它形态      → 整矩形（卡片/六区/搜索胶囊不能切，切了会点不到）
     */
    private void buildTouchRegion(View v, android.graphics.Region out) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) { out.setEmpty(); return; }
        // ⚠️ 搜索胶囊/卡片菜单/审批面板这些**交互窗口**，只在"注入那一刻"才让开；
        // 平时（哪怕球自己要让开）它们的触摸区必须是整块 —— 否则胶囊自己就变成点不动的死屏。
        boolean ballLayer = (v == ballTouch || v == siriTouch);
        if (blindNow || (ballBlindNow && ballLayer)) { out.setEmpty(); return; }
        if (v == ballTouch && roundishOk(w, h)) {
            // ★ 2026-11-06：可触摸区只给**球本身**，不给整个代理窗口。
            //   代理窗口 = 球 + 2×12dp 的 pad（拖动时好抓），以前这里按**窗口**尺寸取胶囊，
            //   于是球周围那一圈 12dp 落在"窗口的可触摸区里、但 inBallAtRaw() 又判定不在球上"
            //   —— 手指按下去没人处理，事件也不会派发给下面的窗口 → 就是用户报的
            //   「球附近那片屏幕点不动」（看他说的方位：球的左上角那一片，正是这圈边）。
            //   现在区域按玻璃的真实半宽/半高（glassHalfW/H，和渲染同一套公式）算，
            //   多出来的 pad 一律不吃触摸，直接穿到下面的 App。
            float bhx = Math.max(1f, glassHalfW());
            float bhy = Math.max(1f, glassHalfH());
            bhx = Math.min(bhx, w * 0.5f);
            bhy = Math.min(bhy, h * 0.5f);
            if (bhx < 4f || bhy < 4f) { out.set(0, 0, w, h); return; }   // 量不到球：退回整块
            float r = Math.min(bhx, bhy) * ballCornerRatio();
            float l = w * 0.5f - bhx, t = h * 0.5f - bhy;
            android.graphics.Path p = new android.graphics.Path();
            p.addRoundRect(new android.graphics.RectF(l, t, l + 2f * bhx, t + 2f * bhy), r, r,
                    android.graphics.Path.Direction.CW);
            if (!out.setPath(p, new android.graphics.Region(0, 0, w, h))) out.set(0, 0, w, h);
            return;
        }
        out.set(0, 0, w, h);
    }

    /**
     * 球（液态玻璃）当前的圆角比例：1 = 胶囊（常态球就是它），0 = 直角。
     * 触摸区域要和渲染出来的形状对得上 —— 用胶囊区域去兜一个方角卡片，
     * 四个角就成了"看得见点不动"；反过来用矩形兜胶囊，四角又会变成死区。
     */
    private float ballCornerRatio() {
        if (siriEnabled && aMaster > 0.02f) return Math.max(0f, Math.min(1f, aCorner));
        if (renderer != null) return Math.max(0f, Math.min(1f, renderer.corner));
        return 1f;
    }

    /** 只有"球就是圆的/胶囊"的形态才用圆角区域；大方框、卡片、菜单一律整矩形。 */
    private boolean roundishOk(int w, int h) {
        if (zonesActive || searchActive || menuShown || approvalShown
                || glassFullscreen || siriTextShown || convoOpen) return false;
        float mx = Math.max(w, h), mn = Math.min(w, h);
        return mx > 0 && (mx - mn) < mx * 0.35f;
    }

    /** 本 App 所有"接触摸的窗口"。 */
    private List<View> allTouchViews() {
        List<View> out = new ArrayList<>();
        if (ballTouch != null) out.add(ballTouch);
        if (siriTouch != null) out.add(siriTouch);
        out.addAll(gateWindows);
        return out;
    }

    /** 让每个窗口重新算一次可触摸区（一帧生效，窗口不动、不重建缓冲）。 */
    private void nudgeRegions() {
        for (View v : allTouchViews()) {
            if (v == null || v.getParent() == null) continue;
            try { v.requestLayout(); v.invalidate(); } catch (Throwable ignored) { }
        }
    }

    /** 球的"可触摸矩形"（屏幕坐标），给门控/插件判断"这一点会不会落到球上"。 */
    private int[] ballTouchRect() {
        if (ballTouchParams == null) return null;
        return new int[]{
                ballTouchParams.x,
                ballTouchParams.y + Math.round(winY0()),
                ballTouchParams.width,
                ballTouchParams.height};
    }

    /**
     * 代理窗口（唯一接用户手势的窗口）什么时候接管触摸：
     *   · 搜索胶囊、卡片菜单、整屏玻璃 —— 这些状态本来就不该由它抢手势；
     *   · agent 注入触摸的**那一下**（ACT 到 DONE 之间）；
     *   · 刚被机器人的触摸砸中过（SYNTHETIC_DUCK_MS 内自愈让开）。
     * 让开 = **把可触摸区域置空**（Region 方案），窗口本身不动；反射挂不上时才退回
     * 老的 flag + 1×1 挪屏外兜底。
     */
    private void applyBallTouchability() {
        if (ballTouchParams == null || wm == null || ballTouch == null) return;
        // 三种"让开"要分清：
        //   · agentBlind / 自愈 duck：跨 App 的触摸要真的到下面，玻璃和代理窗都得离开那个点；
        //   · 搜索胶囊 / 卡片菜单 / 审批面板：球自己的层让开，但这些**交互窗口必须保持可触摸**，
        //     否则胶囊自己就成了点不动的死屏（2026-10-29 人类触摸全废就是这条踩的坑）。
        boolean ducking = System.currentTimeMillis() < syntheticDuckUntil;
        boolean yieldAll = agentBlind || ducking;
        boolean yieldBall = yieldAll || overlayTouchState();
        boolean blind = yieldBall;
        blindNow = yieldAll;
        ballBlindNow = yieldBall;
        if (regionMode) nudgeRegions();            // 圆角区域 / 置空，都要重新算一次

        if (regionMode && trustedOverlayNow()) {
            // 受信任 overlay：region 置空就够了 —— 系统不会因为"有东西挡着"把点击丢掉。
            // 零位移、零重建缓冲、零闪烁；球一直在原地。
            if (proxyParked) { proxyParked = false; syncBallTouchWindow(); }
            applyGlassParkRaw(false);
            lastBlindApplied = blind;
            return;
        }
        int flags = ballTouchParams.flags;
        if (blind) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            proxyParked = true;
            ballTouchParams.width = 1;
            ballTouchParams.height = 1;
            ballTouchParams.x = -8;
            ballTouchParams.y = -8;
        } else {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        boolean flagChanged = flags != ballTouchParams.flags;
        ballTouchParams.flags = flags;
        if (flagChanged) updateProxyWindow();
        if (!blind && proxyParked) {
            proxyParked = false;
            syncBallTouchWindow();          // 把 1×1 的窗口还原成球的尺寸/位置
        }
        // 走到这里说明窗口**不是受信任 overlay**：跨 App 时系统的遮挡拦截只看窗口框，
        // 光把触摸区置空不够 —— 必须把玻璃和代理窗一起挪出屏幕（会闪一下，但只在这一下）。
        // 注意：只有"注入那一刻"才动玻璃和交互窗口；搜索框之类的状态只让球自己的层后退。
        applyGlassParkRaw(yieldAll);
        parkAllOverlays(yieldAll);
    }

    /**
     * 玻璃窗口要不要在注入那一刻挪出屏幕。
     *
     * 默认**不挪**（零闪烁）：只把代理窗口收成 1×1，玻璃原样留着。这台 ROM 实测够用。
     * 个别 ROM 的 InputDispatcher 会把"穿过不受信任悬浮窗"的触摸整串丢掉（tap-jacking 保护），
     * 那种情况下放一个 files/glasspark 文件即可回到"整窗挪开"的老办法——代价是操控时玻璃闪一下。
     */
    private boolean legacyGlassPark() {
        try { return new File(gateDir(), "glasspark").exists(); } catch (Throwable t) { return false; }
    }

    /** 本 App 所有悬浮窗口（球/胶囊/菜单/卡片/审批/文字层…）。注入那一刻要全部让开。 */
    private final java.util.List<View> gateWindows = new java.util.ArrayList<>();
    private final java.util.Map<View, int[]> gateWindowSaved = new java.util.IdentityHashMap<>();

    private void trackWindow(View v) {
        // 球本身（玻璃窗 + 触摸代理窗）有自己的让开逻辑，别在这里重复挪：
        // 两个地方各自保存/还原坐标会打架，玻璃还会因为二次挪动重建缓冲（画面发白）。
        if (v == null || v == glView || v == ballTouch) return;
        if (!gateWindows.contains(v)) {
            gateWindows.add(v);
            if (regionMode) installTouchRegion(v);   // 每个窗口都自带"可触摸区"计算器
        }
    }

    /**
     * 把本 App 的**每一个**悬浮窗口都挪到屏幕外（或在 DONE 后原样还回来）。
     * 只收触摸区不够：点击一旦落在任何一个悬浮界面上（胶囊、卡片、审批、输入框…）就被吃掉了。
     */
    private void parkAllOverlays(boolean on) {
        if (wm == null) return;
        for (int i = 0; i < gateWindows.size(); i++) {
            View v = gateWindows.get(i);
            if (v == null || v.getParent() == null) continue;
            WindowManager.LayoutParams p;
            try { p = (WindowManager.LayoutParams) v.getLayoutParams(); } catch (Throwable t) { continue; }
            if (p == null) continue;
            try {
                if (on) {
                    if (!gateWindowSaved.containsKey(v)) gateWindowSaved.put(v, new int[]{p.x, p.y});
                    p.x = -screenW * 4;
                    p.y = -screenH * 4;
                } else {
                    int[] old = gateWindowSaved.remove(v);
                    if (old == null) continue;
                    p.x = old[0];
                    p.y = old[1];
                }
                wm.updateViewLayout(v, p);
            } catch (Throwable ignored) { }
        }
    }

    private void applyGlassParkRaw(boolean on) {
        if (glView == null || params == null || wm == null) return;
        // 注意：调用方（applyBallTouchability）已经判过"要不要让开"——
        // 受信任 overlay 不挪，非受信任（含点击）一律挪，否则跨 App 的触摸会被遮挡拦截丢掉。
        if (on == glassParked) return;
        try {
            if (on) {
                int sx = params.x, sy = params.y;
                params.x = -screenW * 3;
                params.y = -screenH * 3;
                wm.updateViewLayout(glView, params);
                glassSavedX = sx;               // 只有窗口真的挪成功才记状态
                glassSavedY = sy;
                glassParked = true;
            } else {
                params.x = glassSavedX;
                params.y = glassSavedY;
                wm.updateViewLayout(glView, params);
                glassParked = false;            // 只有真的还回去才清标志
            }
        } catch (Throwable ignored) { }
    }

    /** 搜索胶囊 / 卡片菜单 / 整屏玻璃：这几态本来就不该由代理窗口接手势。 */
    private boolean overlayTouchState() {
        // ★ 审批/提问面板也必须算进来：面板期间球窗是 973×1489 的大盒子，
        //   球的代理触摸层要是还活着，玻璃以外那一圈透明区域会把点击全吃掉
        //   （用户 2026-11-06：「玻璃以外的触碰似乎有受影响」）。
        return searchActive || searchClosing || menuShown || glassFullscreen
                || approvalShown || approvalClosing || uiHidden;
    }

    /**
     * 是否要让代理窗口退出命中测试（不含 agent 那一下）。
     * 三类：搜索胶囊/卡片菜单/整屏玻璃这些"本来就不该接手势"的状态；
     * 以及**刚被机器人的触摸砸中**之后的自愈让开窗口（SYNTHETIC_DUCK_MS）。
     * 「球没画出来」不再参与判断 —— 那个判据会把菜单态误判成隐形，反而把球点死。
     */
    private boolean blindWanted() {
        return overlayTouchState() || System.currentTimeMillis() < syntheticDuckUntil;
    }

    // 门控轮询：40ms 看一次 ACT / DONE 文件（root 侧会用；普通 Termux 写不进
    // /sdcard/Android/data/<别的包>/files —— Android 11+ 的分区存储，所以主通道是下面的 socket）。
    private final Runnable gateTick = new Runnable() {
        @Override public void run() {
            pumpGate();
            main.removeCallbacks(this);
            main.postDelayed(this, GATE_POLL_MS);
        }
    };

    // ── 门控主通道：127.0.0.1 上的一个迷你 socket 服务 ─────────────────────────
    // 为什么不用文件：插件跑在 Termux（自己的 uid），Android 11+ 下它**读写不了**
    // /sdcard/Android/data/com.glslplayground.app/files（实测 EACCES）。所以改成回环 socket：
    //   ACT <nonce>   → 主线程把触摸区挪走，然后回 "ACK <nonce>"
    //   DONE <nonce>  → 立刻还原，回 "OK"
    //   PING          → "PONG"
    // 只绑 127.0.0.1，不对外；连不上就 fail-open（插件照发，球最多被吃一次）。
    private static final int GATE_PORT = 38721;
    private volatile java.net.ServerSocket gateServer;
    private Thread gateThread;

    private void startGateServer() {
        if (gateThread != null) return;
        gateThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.ServerSocket ss = new java.net.ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress(
                            java.net.InetAddress.getByName("127.0.0.1"), GATE_PORT), 4);
                    gateServer = ss;
                    Log.i("FloatingBall", "gate socket listening on 127.0.0.1:" + GATE_PORT);
                    while (!ss.isClosed()) {
                        java.net.Socket c = null;
                        try {
                            c = ss.accept();
                            // 每个连接一个线程：ACT 的 socket 要一直开到 DONE，
                            // 单线程串行会让"注入后的对账查询"排在它后面超时。
                            final java.net.Socket cc = c;
                            c = null;
                            Thread t = new Thread(new Runnable() {
                                @Override public void run() {
                                    try { serveGateClient(cc); }
                                    catch (Throwable ignored) { }
                                    finally { try { cc.close(); } catch (Throwable ignored) { } }
                                }
                            }, "ball-gate-conn");
                            t.setDaemon(true);
                            t.start();
                        } catch (Throwable ignored) {
                        } finally {
                            if (c != null) try { c.close(); } catch (Throwable ignored) { }
                        }
                    }
                } catch (Throwable t) {
                    Log.w("FloatingBall", "gate socket failed: " + t);
                }
            }
        }, "ball-gate");
        gateThread.setDaemon(true);
        gateThread.start();
    }

    private void serveGateClient(java.net.Socket c) throws Exception {
        c.setSoTimeout(3000);
        java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        java.io.OutputStream out = c.getOutputStream();
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("ACT")) {
                String[] actParts = line.substring(3).trim().split("\\s+");
                final String nonce = actParts.length > 0 ? actParts[0] : "";
                final boolean actDrag = actParts.length > 1 && actParts[1].startsWith("drag");
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                main.post(new Runnable() {
                    @Override public void run() {
                        lastActWasDrag = actDrag;
                        beginAgentWindow(nonce);
                        latch.countDown();
                    }
                });
                latch.await(150, java.util.concurrent.TimeUnit.MILLISECONDS);   // 等窗口真的挪开再回 ACK
                try { Thread.sleep(GATE_SETTLE_MS); } catch (InterruptedException ignored) { }
                out.write(("ACK " + nonce + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else if (line.startsWith("DONE")) {
                main.post(new Runnable() {
                    @Override public void run() { endAgentWindow(); }
                });
                out.write("OK\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else if (line.startsWith("PING")) {
                out.write("PONG\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else if (line.startsWith("STAT")) {
                // 门控状态查询：让注入方在**注入之前**就知道"这一点会不会落到球上"、
                // 以及"上一次点击是不是被球吞了"（吞了就重试，球此刻正让开）。
                int[] rr = ballTouchRect();
                String stat = "STAT blind=" + ((agentBlind || blindWanted()) ? 1 : 0)
                        + " rect=" + (rr == null ? "0,0,0,0" : rr[0] + "," + rr[1] + "," + rr[2] + "," + rr[3])
                        + " swallowed=" + swallowedCount + "\n";
                out.write(stat.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /** 注入要开始了：把代理窗口挪到屏外（40ms 内生效）。 */
    private void beginAgentWindow(String nonce) {
        blindStartMs = System.currentTimeMillis();
        blindUntilMs = blindStartMs + AGENT_MAX_MS;
        if (nonce != null && !nonce.isEmpty()) lastActNonce = nonce;
        if (!agentBlind) {
            agentBlind = true;
            lastBlindApplied = true;
            applyBallTouchability();
            Log.i("FloatingBall", "gate(ACT) -> 触摸区让开");
        }
    }

    /**
     * 这一下注入完了：把触摸区还给球 —— 但留一点尾巴。
     *
     * 触摸/滑动的注入是异步的：DONE 到达时最后几个 MOVE/UP 可能还在路上，
     * 代理窗口这时候回来会把正在进行的滑动手势掐断（表现为"拖不动前台"）。
     * 所以延后 DONE_GRACE_MS 再还原；点击也只多藏这一小会儿，肉眼看不出来。
     */
    private void endAgentWindow() {
        blindUntilMs = 0;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (System.currentTimeMillis() < blindUntilMs) return;   // 期间又来了新的 ACT
                if (!agentBlind) return;
                agentBlind = false;
                lastBlindApplied = false;
                applyBallTouchability();
                Log.i("FloatingBall", "gate(DONE) -> 恢复触摸区");
            }
        }, lastActWasDrag ? DONE_GRACE_MS : DONE_GRACE_TAP_MS);
    }

    private static final String SHELL_DIR = "/data/local/tmp";
    /** 让开之后要等一帧多，窗口位置对 InputDispatcher 才真的生效（不等就会丢点击）。 */
    private static final long GATE_SETTLE_MS = 90;
    /** DONE 之后再等一小会儿才还原，避免掐断还在路上的滑动。 */
    private static final long DONE_GRACE_MS = 300;      // 滑动才需要长尾巴（MOVE/UP 还在路上）
    private static final long DONE_GRACE_TAP_MS = 80;   // 点击：注入完立刻还回来，尽量不让人看见

    /** 两个候选里取修改时间更新的那个（都不存在就返回第一个）。 */
    private static File freshest(File a, File b) {
        boolean ea = a != null && a.exists(), eb = b != null && b.exists();
        if (ea && eb) return (b.lastModified() > a.lastModified()) ? b : a;
        if (eb) return b;
        return a;
    }

    private void pumpGate() {
        long now = System.currentTimeMillis();
        File dir = gateDir();
        // 两路文件信号：App 自己的外部目录（root 写）+ /data/local/tmp（shell/root 的 input 包装器写）。
        // untrusted_app 能读 /data/local/tmp（shell_data_file），写不了 —— 所以只有读这一半。
        File act = freshest(new File(dir, F_ACT), new File(SHELL_DIR, F_ACT));
        String actRaw = (act != null && act.exists()) ? readSmallString(act, 64) : "";
        // ⚠️ 先切出 nonce 再比较：ACT 文件里是 "nonce kind" 两段，拿整行去比会让
        //    "同一个 nonce" 每一拍都被当成新的（表现为 act/done 每 40ms 抽搐一次）。
        String nonce = "";
        boolean actDrag = false;
        if (!actRaw.isEmpty()) {
            String[] np = actRaw.split("\\s+");
            nonce = np.length > 0 ? np[0] : "";
            actDrag = np.length > 1 && np[1].startsWith("drag");
        }
        // 只认 10s 内的信号：重启后残留的旧 ACT 不该把球重新拽进"让开"
        if (!nonce.isEmpty() && act != null
                && now - act.lastModified() > GATE_ACT_TTL_MS) nonce = "";
        if (!nonce.isEmpty() && !nonce.equals(lastActNonce)) {
            lastActWasDrag = actDrag;
            lastActNonce = nonce;
            blindStartMs = now;
            blindUntilMs = now + AGENT_MAX_MS;
            Log.i("FloatingBall", "agent act: " + nonce + (actDrag ? " (drag)" : " (tap)") + " → 触摸区让开");
        }
        // DSH 注入完这一下就写 DONE（同一个 nonce）→ 立刻还原，不等兜底超时。
        if (blindUntilMs > 0 && !nonce.isEmpty()) {
            File doneFile = freshest(new File(dir, F_DONE), new File(SHELL_DIR, F_DONE));
            String done = (doneFile != null && doneFile.exists()) ? readSmallString(doneFile, 64) : "";
            if (nonce.equals(done)) {
                blindUntilMs = 0;
                lastAckNonce = "";          // 下一次点击重新握手
                Log.i("FloatingBall", "agent done: " + nonce + " → 恢复触摸区");
            }
        }
        boolean signalBlind = now < blindUntilMs;
        boolean want = signalBlind || blindWanted();
        if (want != lastBlindApplied) {
            lastBlindApplied = want;
            agentBlind = signalBlind;
            applyBallTouchability();
            Log.i("FloatingBall", "agent gate -> " + (want ? "不接触摸" : "可触摸"));
        } else if (signalBlind != agentBlind) {
            agentBlind = signalBlind;
        }
        // 兜底对账：菜单/卡片/搜索这些状态由别处收尾，万一哪条路径忘了把代理窗口叫回来
        // （症状：面板收起、球也画出来了，但球点不动），这里每 40ms 拉平一次。
        if (!want && proxyParked) {
            applyBallTouchability();
        } else if (!want && now - lastProxySyncAt >= 300) {
            lastProxySyncAt = now;
            syncBallTouchWindow();     // 数值没变就什么都不做
        }
        // 输入框里有没有"等着发"的字：电源键/音量减键要据此决定是发字还是开麦。
        // 必须在主线程算（读 EditText），广播线程只读 volatile。
        refreshComposerHasText();
        boolean blind = signalBlind;
        if (blind && !nonce.isEmpty() && !nonce.equals(lastAckNonce) && now - lastAckAt >= 200
                && now - blindStartMs >= GATE_SETTLE_MS) {
            lastAckAt = now;
            try {
                writeAtomic(new File(dir, F_ACK), nonce + "\n");
                lastAckNonce = nonce;
            } catch (Throwable ignored) { }
        }
        // 球的矩形心跳（400ms）：包装器/插件靠它判断"这一点会不会落到球上"，
        // 落在球外就直接注入、连让开都省了（0 延迟、0 抖动）。
        if (now - lastRectAt >= 400) {
            lastRectAt = now;
            try {
                int[] rr = ballTouchRect();
                if (rr != null) {
                    writeAtomic(new File(dir, "ball-rect"),
                            rr[0] + " " + rr[1] + " " + rr[2] + " " + rr[3]
                                    + " " + ((want || blindNow) ? 1 : 0) + " " + now + "\n");
                }
            } catch (Throwable ignored) { }
        }
        // 诊断口子（2s 一次）：门控状态。排查"球点不动 / 让不开"时先看它。
        if (now - lastDiagAt >= 2000) {
            lastDiagAt = now;
            try {
                long age = renderer != null ? now - renderer.lastFrameAtMs : -1;
                int[] rr = ballTouchRect();
                writeAtomic(new File(dir, "ball-diag"), "blind=" + want
                        + " parked=" + proxyParked
                        + " glassParked=" + glassParked
                        + " agent=" + agentBlind
                        + " kind=" + (lastActWasDrag ? "drag" : "tap")
                        + " fullscreen=" + glassFullscreen
                        + " search=" + searchActive
                        + " menu=" + menuShown
                        + " region=" + (regionMode ? 1 : 0)
                        + " pointer=" + (pointerAlive ? 1 : 0)
                        + " a11y=" + (BallA11yService.connected ? 1 : 0)
                        + " trusted=" + (trustedOverlayNow() ? 1 : 0)
                        + " duck=" + (now < syntheticDuckUntil ? Math.max(0, syntheticDuckUntil - now) : 0)
                        + " synthetic=" + swallowedCount
                        + " rect=" + (rr == null ? "-" : rr[0] + "," + rr[1] + "," + rr[2] + "," + rr[3])
                        + " nonce=" + (nonce.isEmpty() ? "-" : nonce)
                        + " ageMs=" + age + "\n");
            } catch (Throwable ignored) { }
        }
    }

    /** 原子写：先写 .tmp 再 rename（DSH 可能正在读）。 */
    private static void writeAtomic(File dst, String body) throws Exception {
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable e) {
            if (!tmp.renameTo(dst)) throw e;
        }
        try { dst.setReadable(true, false); dst.setWritable(true, false); } catch (Throwable ignored) { }
    }

    /** 统一的代理窗口更新口。 */
    private void updateProxyWindow() {
        if (wm == null || ballTouch == null || ballTouchParams == null) return;
        try { wm.updateViewLayout(ballTouch, ballTouchParams); } catch (Throwable ignored) { }
    }

    private static String readSmallString(File f, int max) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[Math.max(8, max)];
            int n = in.read(buf);
            if (n <= 0) return "";
            int end = Math.min(n, max);
            return new String(buf, 0, end, StandardCharsets.UTF_8).trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 代理窗口的屏幕矩形：**贴合玻璃层真正有像素的那块** + 少量外扩。
     *
     * 为什么不能用窗口矩形：窗口四边各有 24dp 留白，窗口里没玻璃的地方
     * （玻璃顶/底到窗口边、球两侧的圆角外）代理窗口照样吃触摸 ——
     * 用户 2026-10-22 报的「球下方的屏幕有时候触摸失灵」就是它。
     * 实测修复前：窗口 793×579，玻璃实心只有 661×447 → 球正下方一条 ~66px 高的死带。
     * 现在把可触摸区钳在「玻璃包围盒」上，再留 12dp 给拖拽起手（球的边缘 SDF 判定仍在
     * {@link #inBallAtRaw} 里做，所以这 12dp 只影响"能否开始拖"，不影响误触）。
     */
    private void syncBallTouchWindow() {
        if (ballTouch == null || ballTouchParams == null || wm == null || glView == null) return;
        // 兜底（反射挂不上监听）时窗口会被缩成 1×1 挪到屏外，别在这里又把它算回球的位置。
        // Region 模式下窗口不动，几何照常同步 —— 球在让开期间也可能被拖走。
        if (!regionMode && (agentBlind || proxyParked)) return;
        float pad = Math.min(dp(12), dp(24));           // 外扩，但要**远小于**窗口留白
        float bhx, bhy;
        if (zonesActive) {
            // 状态 3 期间：整个环 / 整张卡片都要能点到（玻璃只画球那么大，得按环来）
            bhx = zoneRing + zoneR + dp(6);
            bhy = zoneRing + zoneR + dp(6);
        } else {
            bhx = Math.max(1f, glassHalfW());
            bhy = Math.max(1f, glassHalfH());
        }
        // 中心按窗口 params 算：overlay 窗口的 params 是"屏幕坐标减掉状态栏"，所以**屏幕**中心
        // = params.y + winY0() + winH/2。
        float cx = params.x + winW * 0.5f;
        float cy = params.y + Math.round(winY0()) + winH * 0.5f;
        int w = Math.max(2, Math.min(screenW, Math.round(2 * (bhx + pad))));
        int h = Math.max(2, Math.min(screenH, Math.round(2 * (bhy + pad))));
        int x = Math.round(cx - w * 0.5f);
        // ★ 2026-11-06（十二）：转回 params 时**必须再减掉状态栏**。
        //   上面算出来的 cy 是屏幕坐标；而 ballTouchParams.y 是"屏幕坐标 − winY0()"这个空间的
        //   （窗口管理器会自己再加回去，实测 mAttrs=(327,213) → input frame 顶边 303 = 213+90）。
        //   以前这里少减了一次 → 代理窗口/可触摸区整体比球**低 90px**（实测：球画在 y 230..475，
        //   命中区却在 303..612）。后果正是用户报的那两个现象：
        //     · 球上半截点不动、拖不动；
        //     · 球**下面**那一条透明区域反而吃掉点击（点在它上面什么都不发生）。
        int y = Math.round(cy - h * 0.5f) - Math.round(winY0());
        if (ballTouchParams.x == x && ballTouchParams.y == y
                && ballTouchParams.width == w && ballTouchParams.height == h) return;
        ballTouchParams.x = x;
        ballTouchParams.y = y;
        ballTouchParams.width = w;
        ballTouchParams.height = h;
        updateProxyWindow();
    }

    /**
     * 玻璃（液态球体）当前的屏幕半宽/半高 —— 触摸代理窗口就按它来定大小。
     *
     * 公式与 {@code GlassRenderer} 里画球的那套完全一致：
     *   hx = ref*0.9 * halfX * master，Siri 形态用 sv* 那组（动画值），
     *   ref = renderer.refSize（原悬浮窗边长，与窗口实际宽高解耦）。
     * 取 Siri 与常态两者的较大值，避免形态切换的那一瞬代理窗口比玻璃小。
     */
    private float glassHalfW() {
        if (renderer == null) return winW * 0.5f;
        float ref = renderer.refSize > 0 ? renderer.refSize : winW;
        float base = ref * 0.9f;
        // ★ 2026-11-06：normal 这一路要乘球的缩放 —— 球缩到屏宽 1/3 后，
        // 触摸代理跟着缩，球四周那圈透明窗口不吃触摸。
        float normal = base * renderer.halfX * renderer.master * ballIdleScale();
        float siri = base * renderer.svHalfX * renderer.svMaster;
        float v = Math.max(normal, siri);
        return v > 2f ? v : winW * 0.5f;
    }

    private float glassHalfH() {
        if (renderer == null) return winH * 0.5f;
        float ref = renderer.refSize > 0 ? renderer.refSize : winW;
        float base = ref * 0.9f;
        float normal = base * renderer.halfY * renderer.master * ballIdleScale();
        float siri = base * renderer.svHalfY * renderer.svMaster;
        float v = Math.max(normal, siri);
        return v > 2f ? v : winH * 0.5f;
    }

    /** 代理窗口里的触摸是否落在球上：换算到玻璃窗口本地坐标后用同一套 SDF。 */
    private boolean inBallAtRaw(float rawX, float rawY) {
        if (!siriEnabled) return true;                 // 普通模式：整颗球都认
        if (glView == null) return true;
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        return inSiriBall(rawX - loc[0], rawY - loc[1]);
    }

    // ── 代理窗口：球上的一切用户手势都在这里（单击/长按取消/拖动）──
    private void setupBallTouchLayer() {
        if (ballTouch != null || wm == null) return;
        View v = new View(this);
        v.setBackgroundColor(0x00000000);   // 透明但可触摸
        v.setClickable(true);
        int type = overlayType();
        ballTouchParams = new WindowManager.LayoutParams(
                2, 2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        markTrustedOverlay(ballTouchParams);
        ballTouchParams.gravity = Gravity.TOP | Gravity.START;
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging, longFired;
            /** 手指此刻是否还按着（长按回调迟到时靠它判断，见 longPress 里那段注释）。 */
            boolean fingerDown;
            /** 双指按住不动 SEARCH_HOLD_MS = 唤起搜索胶囊（用户要的入口）。 */
            final Runnable twoFingerHold = new Runnable() {
                @Override public void run() {
                    if (twoHoldFired || dragging) return;
                    if (!twoDown) return;                    // 手指已经抬起来了
                    twoHoldFired = true;
                    Log.i("FloatingBall", "search: 双指长按 → 唤起状态 2");
                    vibrate(30);
                    if (voice != null) voice.cancel();
                    enterSearchMode("双指长按", "", false, BAR_SEND);
                }
            };
            boolean twoDown = false, twoHoldFired = false;
            /**
             * 按住不动 480ms = **进入状态 3**（用户 2026-10-23 新交互）。
             *
             * 老版本这里是"取消这一轮问答"；现在取消挪到别处（状态 3 的右下角也不做取消，
             * 因为草图里长按的语义明确是"变成状态 3"）。手指不松开就显示四个圆区，
             * 滑到某个区再松手才执行；松手不在区里就回初始状态。
             */
            final Runnable longPress = new Runnable() {
                @Override public void run() {
                    if (dragging || longFired) return;
                    // ★ 2026-11-07：主线程被别的活占住时，这个 480ms 的回调会**迟到**，
                    //   可能拖到手指抬起之后才跑 —— 那时状态 3 会自己凭空弹出来（"幽灵菜单"），
                    //   用户再点一下屏幕就变成"菜单操作"了。手指已经抬起就别弹了。
                    if (!fingerDown) {
                        Log.i("FloatingBall", "长按回调迟到（手指已抬起）→ 不弹状态 3");
                        return;
                    }
                    longFired = true;
                    Log.i("FloatingBall", "长按 480ms → 状态 3（四个圆区）");
                    vibrate(35);
                    enterZones("长按");
                }
            };
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // 状态 3 按住期间：第二根手指也归这一串，不要切成"双指长按开搜索框"
                        if (zonesActive) return true;
                        // 第二根手指落下 = 双指手势的起点。这里不再立刻复位形状，
                        // 而是分成两种意图（用户要求）：
                        //   · 双指**拖动** → 还是老行为：强制回到初始形状（见 ACTION_MOVE）
                        //   · 双指**长按** → 唤起搜索胶囊（SEARCH_HOLD_MS 后触发）
                        //   · 双指快速点一下（没动、也没按够）→ 松手时补一次复位，老手感不丢
                        if (e.getPointerCount() >= 2) {
                            twoDown = true;
                            twoHoldFired = false;
                            main.removeCallbacks(longPress);     // 双指时不要再触发"长按取消"
                            main.removeCallbacks(twoFingerHold);
                            main.postDelayed(twoFingerHold, SEARCH_HOLD_MS);
                            return true;
                        }
                        return true;
                    case MotionEvent.ACTION_DOWN:
                        // 机器人的触摸：一律不执行用户手势（以前会把球拖走/弹四区/开搜索框）
                        if (isSyntheticTouch(e)) { onSyntheticDown(e); return true; }
                        // 审批球挂着时不抢手势：屏幕这时候归审批球用
                        if (approvalShown) return false;
                        if (searchActive) return false;         // 搜索胶囊期间屏幕归胶囊
                        if (menuShown) return false;            // 玻璃菜单开着时归菜单
                        fingerDown = true;
                        // ── 状态 3 的"点击模式"：卡片落定后 / 六区（松手后仍在）都靠点击 ──
                        if (zonesActive) {
                            cardDownX = e.getRawX(); cardDownY = e.getRawY();
                            cardDragged = false; cardDownFirst = holdFirst;
                            main.removeCallbacks(longPress);
                            if (holdLevel == 0) updateHoldTouch(e.getRawX(), e.getRawY());
                            return true;
                        }
                        // 球上才算（代理窗口比球略大，多出来的边不响应）
                        if (!inBallAtRaw(e.getRawX(), e.getRawY())) return false;
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = params.x; startY = params.y;
                        dragging = false;
                        longFired = false;
                        twoDown = false;
                        twoHoldFired = false;
                        main.removeCallbacks(longPress);
                        main.postDelayed(longPress, 480);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        // 六区里移动=换高亮；卡片里移动=拖动滚动
                        if (zonesActive) {
                            if (holdLevel == 0) { updateHoldTouch(e.getRawX(), e.getRawY()); return true; }
                            float dy = e.getRawY() - cardDownY;
                            if (!cardDragged && Math.abs(dy) > dp(8)) cardDragged = true;
                            if (cardDragged && !holdItems.isEmpty()) {
                                int rh = Math.max(1, Math.round(dp(34)));
                                int step = Math.round(dy / rh);
                                int maxFirst = Math.max(0, holdItems.size() - holdVisible);
                                int want = Math.max(0, Math.min(maxFirst, cardDownFirst - step));
                                if (want != holdFirst) { holdFirst = want; refreshCards(); }
                            }
                            return true;
                        }
                        // 状态 3：手指还按着 —— 只更新"落在哪个圆区"，不做别的
                        if (zonesActive) {
                            updateHoldTouch(e.getRawX(), e.getRawY());
                            return true;
                        }
                        float moved = Math.abs(e.getRawX() - downX) + Math.abs(e.getRawY() - downY);
                        if (twoDown) {
                            if (!twoHoldFired && moved > dp(12)) {   // 双指拖动了 → 老行为：复位
                                main.removeCallbacks(twoFingerHold);
                                Log.i("FloatingBall", "双指拖动 → 强制回到初始形状");
                                forceHomeShape("双指拖动");
                                twoHoldFired = true;
                                return true;
                            }
                            if (twoHoldFired) return true;
                        }
                        if (!dragging) {
                            if (moved <= dp(10)) return true;        // 还没算拖动
                            dragging = true;
                            main.removeCallbacks(longPress);         // 一旦拖动就不再触发长按
                        }
                        moveBallDrag(startX + (e.getRawX() - downX), startY + (e.getRawY() - downY));
                        return true;
                    }
                    case MotionEvent.ACTION_POINTER_UP: {
                        if (zonesActive) return true;
                        main.removeCallbacks(twoFingerHold);
                        if (twoDown && !twoHoldFired) {              // 双指没按够就松手：补一次复位
                            Log.i("FloatingBall", "双指手势 → 强制回到初始形状");
                            forceHomeShape("双指手势");
                            twoHoldFired = true;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        main.removeCallbacks(longPress);
                        main.removeCallbacks(twoFingerHold);
                        twoDown = false;
                        fingerDown = false;
                        if (zonesActive) {                               // 松手 = 一次点击（六区或卡片）
                            boolean dragged = cardDragged;
                            cardDragged = false;
                            if (dragged) return true;
                            if (holdLevel == 0) {
                                int z = holdZoneHover;
                                Log.i("FloatingBall", "zones: 点击圆区 z=" + z);
                                if (z >= 0) runZoneAction(z); else exitZones();
                                return true;
                            }
                            cardTap(e.getRawX(), e.getRawY());
                            return true;
                        }
                        if (zonesActive) {                               // 状态 3 松手
                            finishZones(false);
                            return true;
                        }
                        if (longFired || twoHoldFired) return true;
                        float moved = Math.abs(e.getRawX() - downX) + Math.abs(e.getRawY() - downY);
                        if (dragging || moved >= dp(10)) return true;    // 拖动过就只是拖动
                        // ── 初始状态的单击（= 草图里"单击变成状态 2"）──
                        //   正在听 → 停麦，把已经听出来的字带进状态 2；
                        //   没在听 → **直接弹出搜索框**（不是"再开一次麦"——用户明确要求
                        //   "初始状态单击没有弹出搜索框"是 bug）。
                        vibrate(10);
                        if (convoOpen) {
                            Log.i("FloatingBall", "单击 → 退出对话卡");
                            closeConversation("点球");
                            return true;
                        }
                        // 单击**永远**先弹出输入框（用户：球点击不是应该弹出输入框吗）。
                        //
                        // ★ 2026-11-07：单击之后**直接开输入法语音**（和电源键同一条引擎）。
                        //   以前这里绑的是 {@link BallVoice}（AndroidAsr → 小米 mibrain 系统
                        //   识别）。实测那条路的识别率和"能说的长度"都明显不如搜狗自己的语音，
                        //   用户的原话是「没有手机输入法准确，识别长度也没那么长」——
                        //   根因就是**引擎不是同一个**。现在单击 = 弹框 + 按住说话，
                        //   引擎统一成输入法，长语音的表现和长按空格完全一致。
                        boolean listeningNow = voice != null && voice.listening();
                        gateOldAsrOnTap();
                        Log.i("FloatingBall", "单击 → 弹出状态 2 输入框 + 输入法语音（listening="
                                + listeningNow + "）");
                        targetSessionId = null;     // 单击弹框 = 发到新会话（选中会话有专门的入口）
                        targetSessionTitle = null;
                        enterSearchMode("单击", "", true, BAR_SEND);
                        if (listeningNow) voice.tapStop();
                        if (searchActive && !powerHoldActive) {
                            main.postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (searchActive && !powerHoldActive) beginPowerHold();
                                }
                            }, 260);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        main.removeCallbacks(twoFingerHold);
                        twoDown = false;
                        fingerDown = false;
                        if (zonesActive) return true;                    // 别把卡片/菜单关掉
                        if (zonesActive) finishZones(true);
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            // 加在玻璃层刚出现之后 → z 最低的触摸层：审批面板 / 文字层 / 输入行都压在它上面
            wm.addView(v, ballTouchParams);
            ballTouch = v;
            trackWindow(v);
            // 让开从此走"可触摸区域置空"：窗口不动、不重建缓冲、也不触发 OEM 的 alpha 0.80 规则
            regionMode = installTouchRegion(v);
            if (regionMode) backfillTouchRegions();
            Log.i("FloatingBall", "ball touch proxy added (regionMode=" + regionMode + ")");
        } catch (Throwable e) {
            Log.w("FloatingBall", "add ball touch proxy failed: " + e);
        }
    }

    /**
     * 信号目录（App 可写）：App 外部私有目录 /sdcard/Android/data/&lt;pkg&gt;/files，
     * 拿不到外部存储时退回内部私有目录。两条路 DSH 侧都能读（root 读内部目录也在 SELinux 允许内）。
     */
    private File gateDir() {
        File d = gateDir;
        if (d != null) return d;
        File ext = getExternalFilesDir(null);
        d = (ext != null) ? ext : getFilesDir();
        gateDir = d;
        Log.i("FloatingBall", "gate dir = " + d.getAbsolutePath());
        return d;
    }

    /**
     * 把**整颗球**（含球内文字）夹在屏幕内 —— 用户要求「文字永远被包裹在近方形玻璃内、不能超出屏幕」。
     *
     * 为什么需要单独做一次：`updateSiriWindow()` 只保住"球心不动"，球长高长宽之后就会顶出屏幕
     * （球心可以贴到屏幕边缘，一半球在屏幕外），文字层跟着一起出界。
     * 这里按**当前几何**算玻璃轮廓的屏幕矩形（球心 + 半宽/半高），平移窗口把它夹回屏幕内。
     * 位置计算与拖动夹取同源：玻璃窗口中心 = params + winW/2、winH/2。
     * 留 sysOffset 是因为 Siri 模式下系统会给窗口加一个顶部 inset（实测 ≈96px 状态栏），
     * 不补上的话"夹到顶"实际会停在状态栏下面一点/或反过来被推出去。
     */
    private void clampBallOnScreen() {
        if (wm == null || glView == null || renderer == null) return;
        if (searchActive) return;      // 搜索胶囊的位置由胶囊那份几何自己管（updateSearchWindows）
        float base = winSize * 0.9f;
        if (base <= 0f) return;
        // ⚠️ 两个坑（都实测踩过，"长回复结束后球上下跳、像跳出屏幕"就是这个）：
        //  ① 不能用**动画值** svHalf*：窗口刚 resize 时动画值还是上一套（大方框）尺寸，
        //     拿它去夹取会把刚摆好的最小窗口又推走 —— 实测 clamp 当场把 y=919 改成 986/1427，
        //     球最终停在离"家"530px 的位置。改用**目标几何** tHalf*。
        //  ② 不能用 renderer.winY 反推系统 inset：窗口刚变大/变小时它是过期值，
        //     算出来的球心会偏出几百像素。直接用 glView.getLocationOnScreen()。
        float m = Math.max(0.01f, siriEnabled ? tMaster : 1f);
        float halfW, halfH;
        if (siriEnabled) {
            halfW = base * tHalfX * m;
            halfH = base * tHalfY * m;
        } else {
            halfW = halfH = base * 0.5f;               // 常态是一颗圆球
        }
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        float cx = loc[0] + winW * 0.5f;
        float cy = loc[1] + winH * 0.5f;
        float newCx = Math.max(halfW, Math.min(screenW - halfW, cx));
        float newCy = Math.max(halfH, Math.min(screenH - halfH, cy));
        if (Math.abs(newCx - cx) < 1f && Math.abs(newCy - cy) < 1f) return;
        params.x = Math.round(newCx - winW * 0.5f);
        params.y = Math.round(newCy - winH * 0.5f);
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        glView.post(winSync);
        Log.i("FloatingBall", "clampBallOnScreen: 球心 (" + Math.round(cx) + "," + Math.round(cy)
                + ") → (" + Math.round(newCx) + "," + Math.round(newCy) + ")"
                + " 半宽/半高=" + Math.round(halfW) + "/" + Math.round(halfH));
    }

    /**
     * **强制回到初始形状**（双指手势 / 用户主动收球时用）。
     *
     * 做三件事：① 解除文字钉住；② 彻底隐藏所有文字（同 releaseTextHold 的顺序要求）；
     * ③ 清空本轮保持状态并把几何落回最小圆球。任何状态下都可用。
     */
    void forceHomeShape(String why) {
        Log.i("FloatingBall", "forceHomeShape: " + why);
        SiriAssistant a = SiriAssistant.peek();
        if (a != null) a.cancelByGesture();      // 顺手取消正在进行的这一轮问答
        setAssistantPinned(false);
        siriStr = "";
        forceHideAllText();                      // ① + ②
        textHold = false;                        // ③ 本轮保持直接作废
        releasingHold = true;
        holdHalfX = holdHalfY = 0f;
        siriEaseTau = 0.22f;
        tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
        if (renderer != null) {
            renderer.waveInGlass = 0f;
            renderer.orbOffset = renderer.orbOffsetDefault;
        }
        if (glView != null) {
            glassBufW = glassBufH = -1;
            try { glView.getHolder().setFixedSize(0, 0); } catch (Throwable ignored) { }
        }
        float b0 = winSize * 0.9f;
        updateSiriWindow(b0 * minHalfX, b0 * minHalfY);
        // 双指手势 = "回初始圆球，清掉一切"：会话卡也一起收（否则留着一张空卡，
        // 下次单击球还会走「退出对话卡」那条分支，用户会莫名其妙）
        if (convoOpen) closeConversation("双指手势回初始形态");
    }

    /** 把球的窗口挪到目标位置（夹取规则与球自身拖动完全一致）。 */
    private void moveBallDrag(float wantX, float wantY) {
        // 状态 2/3、菜单、对话卡期间**球不可拖动**（用户：搜索框要弄成不可拖动，
        // 否则长按开框后手指没松，整块窗口会被拖着走、输入框像被手吸住）
        if (searchActive || zonesActive || menuShown || glassFullscreen) return;
        if (convoOpen) {
            // 对话卡：只允许上下挪，X 永远居中。
            // ⚠️ 这里**不能 return**：下面还有 updateViewLayout(glView) 与文字层/代理窗同步，
            //    提前返回会出现"玻璃不动、里面的字和输入框跟着手指跑"的怪象。
            wantX = (screenW - winW) / 2f;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - convoDragLogAt > 400) {
                convoDragLogAt = now;
                Log.i("FloatingBall", "convo drag → params=(" + Math.round(wantX) + ","
                        + Math.round(wantY) + ") win=" + winW + "x" + winH);
            }
        }
        params.x = Math.round(wantX);
        params.y = Math.round(wantY);
        rememberHomeIfMinimal();   // 拖动 = 搬家（只在最小形态时才算家）
        if (siriEnabled) {
            float base = winSize * 0.9f;
            float m = Math.max(0.01f, renderer.svMaster);
            float hx = base * renderer.svHalfX * m;
            float hy = base * renderer.svHalfY * m;
            float bcx = params.x + winW * 0.5f;
            float bcy = params.y + winH * 0.5f;
            bcx = Math.max(hx, Math.min(screenW - hx, bcx));
            bcy = Math.max(hy, Math.min(screenH - hy, bcy));
            params.x = Math.round(bcx - winW * 0.5f);
            params.y = Math.round(bcy - winH * 0.5f);
        } else {
            // 普通模式窗口是整块方形，比球本身大一圈；夹取按球的直径算，
            // 免得把球拖到只剩四分之一露在屏幕里（代理窗口也跟着贴不住）。
            float d = winSize * 0.9f;
            params.x = Math.max(Math.round(-d / 4), Math.min(Math.round(screenW - d * 3 / 4), params.x));
            params.y = Math.max(Math.round(-d / 4), Math.min(Math.round(screenH - d * 3 / 4), params.y));
        }
        // ⚠️ glView 可能为 null：服务刚启动还没建好视图、或已在销毁流程中收到触摸。
        // 原来只判了 wm != null，于是 updateViewLayout(null, ...) 抛
        //   IllegalArgumentException: view must not be null
        // → 整个 App 崩溃（实测崩溃栈：moveBallDrag → updateViewLayout）。
        if (glView == null) return;
        if (wm != null) {
            try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        }
        glView.post(winSync);
        clampBallOnScreen();          // 拖到屏幕边缘时把整颗球（含文字）夹回屏内
    }

    /**
     * 球内文字层**要不要吃触摸**：只在"内容真的超出视口、需要滚动"时才吃。
     *
     * 用户反馈串起来的结果：抓取条删掉后拖球/点按都归球自己的触摸层；
     * 但文字层若一直可触摸会挡住球中间一大片（"球正下方屏幕失灵"）；
     * 完全不吃触摸又没法上下滑看长回复 → 折中：装得下就不吃，超出才吃。
     */
    private void syncSiriTouchLayer() {
        if (siriText == null || siriParams == null || wm == null) return;
        // 球没画成方形态（还是/已经变回胶囊）时，文字层一律不可触摸 ——
        // 否则一层看不见的文字窗会吃掉胶囊上的点按。
        boolean scrollable = siriTextShown && squareDrawn01() > 0.5f
                && mdContentH > siriViewportH + 2;
        // 判据直接看**真实 flags**，不看缓存：早期版本把文字层建成"可触摸"，缓存却是 false，
        // 于是这里一直 return —— 文字层永远可触摸，把球中部/下方的点按全吃掉（2026-10-22 报的）。
        int flagsNow = siriParams.flags;
        boolean nowTouchable = (flagsNow & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        if (scrollable == nowTouchable) { siriTextTouchable = scrollable; return; }
        siriTextTouchable = scrollable;
        if (scrollable) siriParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else siriParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(siriText, siriParams); } catch (Throwable ignored) { }
        Log.i("FloatingBall", "球内文字层触摸: "
                + (scrollable ? "开（内容超出，可上下滑）" : "关（装得下，手势归球）"));
    }

    /**
     * 用户是否正在球里翻阅（触摸中、或刚滚过 {@link #MD_SCROLL_HOLD_MS}）。
     * 翻阅期间：方形态保持不缩、文字不清空。
     */
    boolean isUserScrollingText() {
        if (mdScrolling) return true;
        return android.os.SystemClock.uptimeMillis() - mdScrollAt < MD_SCROLL_HOLD_MS;
    }

    /** 供助手侧查询：用户在翻阅时不要清空球内文字。 */
    public static boolean isTextBeingScrolled() {
        FloatingBallService s = INSTANCE;
        return s != null && s.isUserScrollingText();
    }

    /**
     * 结束本轮几何保持：把球从方形态放回初始圆球。
     *
     * 用户要求：「**只有最终回复**才会恢复，过程中（多步/短回复）都保持方形」。
     * 所以只在"回合真的结束 + 安静 1.5 秒"时才被调用（见 SiriAssistant 侧）。
     */
    public static void releaseHoldForFinalReply() {
        FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                if (s.isUserScrollingText()) {          // 正在翻阅 → 不收回
                    s.lastTextAt = android.os.SystemClock.uptimeMillis();
                    return;
                }
                // ★ 2026-11-09：会话卡开着 = 用户正在看这一页（和"一段转瞬即逝的回复"不同），
                //   安静 30 秒的"最终回复收回"不许把卡收掉。关卡只有点球 / 双指 / 进菜单这几条路。
                if (s.convoOpen) {
                    s.lastTextAt = android.os.SystemClock.uptimeMillis();
                    return;
                }
                // ★ 搜索胶囊开着：这次"收回初始圆球"先记在账上 —— 球这会儿本来就是藏着的，
                //   真要收也只该等胶囊收起之后。否则不光会把整屏玻璃窗打回球窗（见
                //   updateSiriWindow 的守卫），还会把刚到的最终回复从球里清掉，
                //   用户收起胶囊就看到一颗空球（2026-11-08「搜索框待久了显示有问题」同一条线）。
                if (s.searchActive) {
                    Log.i("FloatingBall", "最终回复到了，但搜索胶囊还开着 → 先不收（等胶囊收起再收）");
                    s.holdReleasePending = true;
                    return;
                }
                Log.i("FloatingBall", "最终回复安静 1.5s → 收起方形态，回初始圆球");
                s.releaseTextHold();
            }
        });
    }

    /**
     * **彻底隐藏所有会显示文字的东西**（用户明确要求：变回初始形状之前必须先隐藏）。
     *
     * 做四件事，一次做完、顺序固定：
     *   ① 把内容列清空（不留任何 View，避免残留半帧内容）；
     *   ② 文字层 `GONE`（立即生效、不参与布局，不等窗口/测量的异步更新）；
     *   ③ 指纹清空（下次出现时重建，而不是沿用旧高度）；
     *   ④ 触摸关掉（圆球态手势全归球）。
     *
     * 为什么不只 setText("")：文字层的窗口/布局更新是异步的，
     * 只清字符串的话，球收缩的那半秒里旧文字还留在屏上（用户报过"球小了字还在"）。
     */
    private void forceHideAllText() {
        siriTextShown = false;
        if (mdColumn != null) mdColumn.removeAllViews();      // ① 内容清空
        mdContentH = 0;
        mdBuiltFor = "";
        if (siriText != null) {
            siriText.setVisibility(View.GONE);                // ② 立即不可见
            siriText.scrollTo(0, 0);
        }
        siriTextTouchable = false;                            // ④ 触摸归球
        Log.i("FloatingBall", "隐藏所有文字：视图=" + (siriText == null ? "null" : siriText.getVisibility())
                + "（8=GONE）内容块=" + (mdColumn == null ? -1 : mdColumn.getChildCount()));
        if (syncSiriTouchLayerQuiet()) return;
        syncSiriTouchLayer();
    }

    /** 供 forceHideAllText 用：把"不可触摸"直接落下去（跳过可滚动判定）。 */
    private boolean syncSiriTouchLayerQuiet() {
        if (siriText == null || siriParams == null || wm == null) return false;
        if ((siriParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) return true;
        siriParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(siriText, siriParams); } catch (Throwable ignored) { }
        return true;
    }

    /** 立刻把球内文字藏掉（不进布局）—— 球缩回最小形态时必须同一帧生效。 */
    private void hideSiriTextNow() {
        if (siriText == null) return;
        boolean hadText = siriTextShown;
        siriTextShown = false;
        // 「文字消失 → 动画怎么接」的接续点。
        //
        // ★ 2026-11-08 改写：原来这里写死「处理中 → 切 Siri 波 / 结束 → 交还用户的 Siri 波」，
        //   等于绕开动画总控直接改 waveMode。用户把「思考中 / 过程中回复」绑成胶囊加载后，
        //   只要一段文字被清掉（多段生成、工具调用之间都会），球立刻被掰回 Siri 波 ——
        //   表现就是「状态明明变了，球还是待机那一套」。总控开着时只做一件事：按槽重推。
        if (hadText && renderer != null) {
            boolean processing = "THINKING".equals(stateNow) || "ANSWERING".equals(stateNow);
            if (AnimScene.master(animPrefs())) {
                // 动画由「槽 → 绑定」决定；文字没了只影响摆放位置，不影响是哪个动画
                sceneDirty = true;
                refreshScene(true);
                Log.i("FloatingBall", "文字消失 → 按槽重推动画（state=" + stateNow
                        + " 槽=" + sceneSlot + " 动画=" + sceneAnim + "）");
            } else if (processing) {
                // 处理中：续上"处理中"动画（形态只在这一刻切一次，之后不再变 → 连续不闪）
                renderer.waveOn = true;
                setWaveForm(1);              // 先淡出到 0（此刻本来就没有内容）再切形态
                setOrbMixTarget(1f);
                Log.i("FloatingBall", "文字消失 → 续上处理中动画（state=" + stateNow + "）");
            } else if ("IDLE".equals(stateNow) && idleSettle == null) {
                // 本轮已结束：交还用户自己的 Siri 模式设定
                releaseWaveForm();
                setOrbMixTarget(0f);
                Log.i("FloatingBall", "文字消失 → 交还 Siri 波（本轮已结束）");
            }
        }
        // ★ 2026-11-08（用户口径）：「胶囊状态下不能显示文字，文字要暂时隐藏」。
        //   以前钉住时这里会 return，把字留在缩小的胶囊外面（用户截图那种"字飘在桌面上"）。
        //   钉住只表示**内容别自动清空**，不代表要画在胶囊上：球回最小形态就 GONE，
        //   siriStr / asstTextPinned 都保留，下次球长成方形态还能放回来。
        if (siriText.getVisibility() != View.GONE) {
            siriText.setVisibility(View.GONE);
            Log.i("FloatingBall", "siri text hidden (ball back to minimal)");
        }
        if (siriText != null) siriText.scrollTo(0, 0);   // 下次出现从头看
        syncSiriTouchLayer();             // 没字 → 触摸层收起，球的手势完全不受影响
    }

    /** 有字要显示时再放出来。 */
    /**
     * 球当前是不是"方形态"（有内容时展开的那种）。
     *
     * 用户要求：「恢复到球的初始形状时**不允许**出现字和页面，只有方形才能出现」。
     * 判据用"本轮已进入保持"或"目标几何已明显大于最小形态"—— 不能只看 getVisibility，
     * 因为窗口/度量的更新是异步的。
     */
    private boolean squareFormActive() {
        if (textHold) return true;
        return tHalfX > minHalfX * 1.06f || tHalfY > minHalfY * 1.06f;
    }

    /**
     * 球**画出来**的形态有多"方"：0 = 已经是最小胶囊，1 = 已明显长成方形态。
     *
     * 和 {@link #squareFormActive()} 的区别：那个看的是**目标**几何（textHold / tHalf*），
     * 在"文字还在、球正缩回胶囊"的过渡里它是 true，可屏幕上的球已经是胶囊了。
     * 用户 2026-11-08 的截图就是这种：胶囊在屏上、回复的字还挂在桌面上。
     * 所以文字层的可见度用**动画值** aHalf* 来判 —— 球画成什么样，字才配长什么样。
     */
    private float squareDrawn01() {
        float x = (aHalfX - minHalfX * 1.06f) / Math.max(0.0001f, minHalfX * 0.30f);
        float y = (aHalfY - minHalfY * 1.06f) / Math.max(0.0001f, minHalfY * 0.30f);
        return Math.max(0f, Math.min(1f, Math.max(x, y)));
    }

    private void showSiriTextNow() {
        if (siriText == null) return;
        if (uiHidden) return;            // 被音量加藏起来了：文字层也不许自己冒出来（球玻璃是看不见的）
        // ★ 2026-11-08（用户口径）：菜单 / 搜索胶囊 / 输入胶囊里**不许**冒出 AI 回复的字。
        //   在"放出来"这一个总口子上直接拦掉 —— 靠 100ms 轮询补刀会先闪一帧。
        if (replyTextShouldHide()) {
            hideReplyTextForUi();
            return;
        }
        // ★ 2026-11-09（用户实拍：长按进菜单再回来，玻璃回来了、字没了）：
        //   `forceHideAllText()` 会把 mdColumn 的子 View **全删掉**，之后所有"露出文字"的
        //   路径都只把可见性打开 —— 内容早就不在了，屏幕上就是一块空玻璃。所以露字之前
        //   先补一刀：内容被清空过就按 siriStr 重建一次。
        ensureMarkdownContent();
        if (!squareFormActive()) {
            // 圆球态本该不显示内容 —— 但如果**球里确实有字**（比如刚被清空又来了新字、
            // 或用户点了文字导致几何重置），那就把方形态撑起来，而不是把内容藏掉。
            // 用户实测："点一下界面飞出方形之外、离开 1.5 秒后界面不显示" 就是这条路径。
            if (siriStr != null && !siriStr.trim().isEmpty()) {
                enterTextHold();
                mdBuiltFor = "";
                rebuildMarkdown();
            }
            if (!squareFormActive()) {
                hideSiriTextNow();
                return;
            }
        }
        siriTextShown = true;
        // 审批球在屏上时原球文字一律不露头（审批结束后 measureSiriTargets 会重新放出来）
        if (approvalShown) return;
        if (siriText.getVisibility() != View.VISIBLE) siriText.setVisibility(View.VISIBLE);
        syncSiriTouchLayer();             // 有字 → 触摸层铺在文字区域上
    }

    private void removeSiriText() {
        if (siriText != null && wm != null) {
            try { wm.removeView(siriText); } catch (Exception ignored) { }
        }
        siriText = null;
    }

    /** 文字本体 +（钉住时）图钉标记 —— 排版/黑区仍按纯文字算，图钉只影响显示。 */
    private String displayTextOf(String text) {
        String t = text == null ? "" : text;
        if (asstTextPinned && !t.isEmpty()) return t + "\u2009\uD83D\uDCCD";
        return t;
    }

    private void applySiriText(String text) {
        // 面板挂着的时候，Siri 那条文字通道一律让路：球里的内容此刻属于面板。
        // 否则镜像/静默清空写一次文字就会触发 measureSiriTargets()，把球改回小球尺寸，
        // 面板内容立刻溢出玻璃（实拍踩过）。
        if (approvalShown || approvalClosing) return;
        String prev = siriStr;
        siriStr = text == null ? "" : text;
        mdBuiltFor = "";          // 内容变了 → 重建（内部会量高度并触发几何重算）
        rebuildMarkdown();
        refreshFinalWave();      // 字没了 → 收掉球底那道波；字又来了 → 按 turnEnded 决定要不要出
        boolean prevEmpty = prev == null || prev.trim().isEmpty();
        boolean nowEmpty = siriStr.trim().isEmpty();
        if (prevEmpty && !nowEmpty) {           // 文字出现 → 触发弹出
            aMaster = 0.03f;
        }
    }

    /** 球宽上限：屏幕宽度的 85%。 */
    /** 方形态底边最多到屏高 66%（再往下就压到输入框/键盘了，用户反馈过）。 */
    private static final float SIRI_SAFE_BOTTOM_RATIO = 0.66f;

    /**
     * 球顶的屏幕 y 坐标（**唯一**竖直定位点：玻璃窗口、文字层、黑区都用它）。
     * 球底越过安全线时把整颗球上移 —— 用户原话「弹出位置老挤在下面影响我打字」。
     */
    private float ballTopOnScreen(float halfH) {
        float hy0 = homeCySafe();
        // ★ 2026-11-06：竖直锚点继续用**原版球顶**。球虽然缩到屏宽 1/3，但窗口/方形态/
        //   聊天卡的位置还是缩小前那一条 —— 用户要求「只改球的大小，别的都不许动」。
        float minHalfH = winSize * 0.9f * minHalfY0;
        float top = hy0 - minHalfH;
        float bottom = top + halfH * 2f;
        float safeBottom = screenH * SIRI_SAFE_BOTTOM_RATIO;
        if (bottom > safeBottom) top -= (bottom - safeBottom);
        float minTop = Math.max(0f, winY0());
        float maxTop = Math.max(minTop, screenH - halfH * 2f - winY0());   // 别把球顶到屏幕外
        if (top < minTop) top = minTop;
        if (top > maxTop) top = maxTop;
        return top;
    }

    /**
     * 悬浮窗「请求 y → 实际屏幕 y」的系统 inset（本机 90px 状态栏）。
     *
     * ★ 2026-10-23 修：**必须缓存**。
     * 原来这里每次现算 `loc[1] - params.y`，但调用点（菜单/文字层/输入行）几乎都在
     * `updateViewLayout()` 改完 params **之后**立刻调用 —— 那一刻 `getLocationOnScreen()`
     * 还是旧位置，算出来是个离谱的大数，被 `d < screenH/4` 挡掉 → 返回 0 →
     * 文字层整块**少抬一个状态栏**。实测菜单玻璃在 y=856、文字在 y=947（差 90px），
     * 于是标题上面空一大块、最后两行被面板下沿切掉（用户报的「文字超出界面」）。
     *
     * 现在只把"看起来合理"的测量写进缓存，之后一律用缓存值；窗口尺寸/位置怎么变都不影响。
     */
    private float winInsetY = -1f;
    /**
     * 最近一次**改窗口几何**（尺寸/位置）的时刻。
     *
     * ★ 2026-11-06：窗口尺寸变化（方形态 ↔ 圆球）那一两帧里，getLocationOnScreen() 还是**旧位置**，
     *   算出来的 inset 是假的（实测读成 22 而不是 90 → 摆球时把球顶到屏幕最上面，还把这个假位置
     *   记成"家"，球从此永久偏上）。所以：窗口刚动过的那 300ms 内，量到的值和缓存不一致就信缓存。
     */
    private long winGeomAt = 0;

    private void markWinGeomChanged() { winGeomAt = android.os.SystemClock.uptimeMillis(); }

    private float winY0() {
        float anchor = statusBarH();
        if (glView != null && params != null) {
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            float d = loc[1] - params.y;
            // 刚改过窗口：测量值不可信（拿的是旧位置），差一点就沿用缓存
            if (winInsetY >= 0f
                    && android.os.SystemClock.uptimeMillis() - winGeomAt < 300
                    && Math.abs(d - winInsetY) > 1f) {
                return winInsetY;
            }
            // ★ 2026-11-06：只接受「看起来就是状态栏那一条」的测量。
            //   窗口刚被 updateViewLayout 挪过时，getLocationOnScreen() 还是**旧位置**，
            //   算出来会是几百像素的假 inset（实测被记成 207 → 之后每次摆球都按它压下去
            //   117px，聊天结束缩回球时 Siri 波/圆点就不在启动时的那个位置了）。
            //   真 inset 不可能比状态栏高太多（这里给到 48dp 的余量）。
            float limit = Math.max(dp(48), anchor < 0 ? dp(48) : anchor + dp(8));
            if (d >= 0 && d <= limit) { winInsetY = d; return d; }
        }
        if (anchor >= 0) { winInsetY = anchor; return anchor; }   // 量不到就用状态栏高度兜底
        if (winInsetY >= 0f) return winInsetY;          // 刚改过 params：用上次测到的真值
        if (renderer != null && winH > 0 && renderer.winY > 0 && params != null) {
            float d = renderer.winY - params.y;
            if (d >= 0 && d <= Math.max(dp(48), (statusBarH() < 0 ? dp(48) : statusBarH() + dp(8)))) {
                winInsetY = d; return d;
            }
        }
        return 0f;
    }

    /** 系统状态栏高度（px）；取不到返回 -1。 */
    private float statusBarH() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                float h = getResources().getDimensionPixelSize(id);
                if (h >= 0 && h < screenH / 4f) return h;
            }
        } catch (Throwable ignored) { }
        return -1f;
    }

    /**
     * 方形态高度上限 = **宽度**（用户 2026-10-23：「限高弄成最高的高度等同于宽度，最大就是正方形」）。
     *
     * 原来是屏高 55%（2412×0.55 ≈ 1327），比宽度（1080×0.78 ≈ 842）大得多 ——
     * 长回复会把方框拉成一条竖长条。现在顶到宽度就停，再长的字在方形里滚动。
     */
    private float siriMaxBallH() {
        return siriMaxBallW();
    }

    private float siriMaxBallW() {
        return screenW * SIRI_W_RATIO;
    }

    // ── 回合内几何保持（textHold）：本轮只要出现过文字，就一直保持「近方形」─────────
    // 用户要的观感：只有「开始」和「真正结束」时是初始的圆球，处理中一旦出过字就一直是近方形。
    // 为什么需要它：DSH 一个回合里会多次「出字 → 清空 → 再出字」（多段生成、工具调用之间），
    // 清空那一下按老逻辑会缩回圆球 → 球在圆球/大胶囊之间反复跳，很难看。
    // 规则：
    //   · 出现文字（≥2 字）→ 进入保持，并记住本轮量过的最大几何；
    //   · 文字被清空但保持还没结束 → 不缩回圆球，继续用那份最大几何；
    //   · 本轮真正结束（idle settle 到点 / answerSettledAfterTurn / OFF）→ 释放，回圆球。
    private boolean textHold = false;
    /** 搜索胶囊开着时到了「最终回复该收回」的时刻 → 记下来，等胶囊收起再执行。 */
    private boolean holdReleasePending = false;
    private boolean releasingHold = false;   // 释放时那次重新测量不要再把保持武装起来
    private float holdHalfX = 0f, holdHalfY = 0f, holdCorner = 1f, holdBlack = 0f;
    private long lastTextAt = 0;                         // 最后一次「球里有非空文字」的时刻
    private static final long HOLD_SAFETY_MS = 8000;     // 出字后这么久都没动 → 兜底释放

    /** 兜底：助手在一次流式输出中途被关掉时可能没有 IDLE 事件，久静则自动释放保持。 */
    private void pumpTextHoldSafety() {
        if (!textHold || lastTextAt == 0) return;
        // ★ 2026-11-09（用户报「打开的空白回话页面自己退回胶囊状态」）：
        //   会话卡是**一页在看的东西**，不是转瞬即逝的一段回复 —— 只要卡还开着就续命，
        //   8 秒兜底不许把它收走（收走 = 用户正看着，屏幕上的对话突然没了、输入框悬着）。
        //   关掉卡只有三条路：点球（单击退出卡）、双指手势、进菜单/搜索/拍照藏界面。
        if (convoOpen) {
            lastTextAt = android.os.SystemClock.uptimeMillis();
            return;
        }
        // 用户正在球里翻阅 → 一直保持方形态（并且不断续命），松手 1.5s 后才走正常收起
        if (isUserScrollingText()) {
            lastTextAt = android.os.SystemClock.uptimeMillis();
            return;
        }
        mdScrolling = false;
        // ★ 用户规则：「处理中不恢复初始形状，处理中的圆点动画和那段文字一起待着等下一个回复」。
        //   所以只要助手还在处理中（THINKING/ANSWERING），这个 8 秒兜底也**不许**释放 —— 否则
        //   长任务（几十个 step、几分钟）里球会自己缩回圆球、文字也没了。
        //   真正的收回只由「最终回复安静 1.5s」（SiriAssistant.quietTick）触发。
        if ("THINKING".equals(stateNow) || "ANSWERING".equals(stateNow)) {
            lastTextAt = android.os.SystemClock.uptimeMillis();
            return;
        }
        long idleMs = android.os.SystemClock.uptimeMillis() - lastTextAt;
        if (idleMs > HOLD_SAFETY_MS) {
            Log.i("FloatingBall", "text hold: 兜底释放（出字后静了 " + idleMs + "ms）");
            releaseTextHold();
            lastTextAt = 0;
        }
    }

    private void enterTextHold() {
        siriEaseTau = 0.06f;        // 出字时恢复跟手速度
        lastTextAt = android.os.SystemClock.uptimeMillis();
        if (!textHold) {
            textHold = true;
            holdHalfX = holdHalfY = 0f;      // 新一轮从零开始积累
            holdBlack = 0f;
            Log.i("FloatingBall", "text hold: on（本轮出现过文字，保持近方形）");
        }
    }

    /** 释放保持，把球交还给「初始圆球」形态。 */
    private void releaseTextHold() {
        boolean convoWasOpen = convoOpen;
        // ⚠️ 这里**不能**再 `if (!textHold) return;`：那样"没在保持"时直接返回，
        // 文字不会被隐藏，但形状照样收 —— 用户看到的正是「先变形、字还挂在球上」。
        // 现在无论是否在保持，都先把所有文字隐藏掉（顺序也是硬要求）。
        forceHideAllText();          // ★ 先隐藏一切文字
        if (!textHold) {
            // 没在保持：只需把几何落回初始形态（可能是文字被清空后残留的大几何）
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
            float bb = winSize * 0.9f;
            updateSiriWindow(bb * minHalfX, bb * minHalfY);
            if (convoWasOpen) {                  // 会话卡跟着一起收（见方法末尾的注释）
                Log.i("FloatingBall", "convo: 球回到初始形态 → 会话卡一起收起（未在保持）");
                closeConversation("球回到初始形态");
            }
            return;
        }
        textHold = false;
        releasingHold = true;
        siriEaseTau = 0.22f;        // 收起慢一点：连续缩回，而不是一帧跳回最小球
        holdHalfX = holdHalfY = 0f;
        Log.i("FloatingBall", "text hold: off（本轮结束，回初始圆球）");
        // 直接落回初始形态，**不要**拿残留文字再量一遍 —— 那会量出「按最后那段文字算的方形」
        // （实测结束时停在 705x705 而不是 787x573 的圆球）。文字已在方法开头彻底隐藏。
        tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
        if (renderer != null) {
            renderer.waveInGlass = 0f;   // 球底那截波跟着收掉
            // ★ 2026-11-06：方形态期间圆点被沉到了「底部动效带」（orbOffset 0.05~0.85），
            //   波闸门也可能被文字压到 0。收字回球时必须**当场**把这两样放回常态 ——
            //   否则球缩回胶囊了，圆点还挂在原来那条低带上（用户报的「dots 没像启动时那样
            //   居中」就是这么来的），波也还是关着的。这里不等下一次 applyAssistantState()。
            orbOffsetTarget = Math.max(-0.06f, Math.min(0.06f, renderer.orbOffsetDefault));
            renderer.orbOffset = orbOffsetTarget;
            waveGateTarget = 1f;
            waveGateLastMs = 0;
        }
        if (glView != null) {                              // 回最小形态 → 恢复全分辨率渲染
            glassBufW = glassBufH = -1;
            try { glView.getHolder().setFixedSize(0, 0); } catch (Throwable ignored) { }
        }
        float b0 = winSize * 0.9f;
        updateSiriWindow(b0 * minHalfX, b0 * minHalfY);
        // ★ 2026-11-09：球自己收回胶囊 = 这一屏会话结束。
        //   卡也要一起收（否则 siriStr 里那 73 字还在，任何一次 measure 都会把它重新放出来，
        //   屏幕上就是"字自己诈尸"）。输入行由 syncComposerBar 同一拍收起。
        if (convoWasOpen) {
            Log.i("FloatingBall", "convo: 球回到初始形态 → 会话卡一起收起");
            closeConversation("球回到初始形态");
        }
    }

    /** 保持期间：不缩小，取「本轮量过的最大值」。 */
    private void applyHoldGeometry() {
        if (holdHalfX <= 0f) return;
        if (tHalfX < holdHalfX) tHalfX = holdHalfX;
        if (tHalfY < holdHalfY) tHalfY = holdHalfY;
        tCorner = Math.min(tCorner, holdCorner);      // 更小 = 更方
        if (tBlack < holdBlack) tBlack = holdBlack;
        tMaster = 1f;
        float base = winSize * 0.9f;
        updateSiriWindow(base * tHalfX, base * tHalfY);
    }

    /** 文字可换行的最大宽度。 */
    private int siriTextMaxW() {
        return (int) Math.max(dp(60), siriMaxBallW() - 2 * dp(42));
    }

    private void measureSiriTargets(String s) {
        if (approvalShown) return;      // 面板在屏上：球尺寸归面板管，别在这儿改
        if (searchActive) return;       // 搜索胶囊在屏上：球尺寸归弹簧管（同一颗球，两套几何别互写）
        // ⚠️ 重入守卫（实测踩过、很隐蔽）：本方法里会调 showSiriTextNow()，
        // 它可能触发 rebuildMarkdown() → 又回头调 measureSiriTargets()。
        // 递归那一层会把 tHalfY 覆盖成"按文字高度算的值"，**比真实需要的小**，
        // 于是球缩小了、而文字窗还按大几何摆 → 文字超出球底（用户实拍的那个）。
        if (measuringNow) return;
        measuringNow = true;
        try {
            measureSiriTargetsInner(s);
        } finally {
            measuringNow = false;
        }
    }

    private volatile boolean measuringNow = false;

    private void measureSiriTargetsInner(String s) {
        int n = s == null ? 0 : s.trim().length();
        if (n <= 1) {                           // 0/1 个字
            if (textHold) {
                // 本轮已经出过字、还没结束：文字被清空（多段生成之间的空档）也不缩回圆球，
                // 继续用本轮量过的最大几何 —— 否则球会在圆球/近方形之间反复缩胀。
                //
                // ⚠️ 但「回合已经结束」时继续保持就是 bug（用户 2026-10-22 报的）：
                // 窗口停在方形态的 993×993 上，球其实已经缩回圆球，
                // 球周围那一大圈透明区域照样吃触摸 —— 屏幕上看着球好好的，
                // 点它旁边却没反应。回合结束后落回初始圆球是唯一正确解。
                if (isIdleState()) {
                    Log.i("FloatingBall", "text hold: 没在跑且文字为空 → 收回初始圆球（顺带缩窗）");
                    releaseTextHold();
                    return;
                }
                hideSiriTextNow();
                applyHoldGeometry();
                return;
            }
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner;
            tBlack = 0f;
            float b0 = winSize * 0.9f;
            updateSiriWindow(b0 * minHalfX, b0 * minHalfY);
            // 球回初始圆球 = 对话结束：输入行（和附件标签）必须一起消失
            // 只有"进过对话、且确实显示过内容"之后球才允许因为文字清空而收铬；
            // 刚打开对话时的那一帧文字还是空的，不能据此就把对话关掉。
            if (convoOpen && convoHadText
                    && android.os.SystemClock.uptimeMillis() - convoOpenedAt > 1500) {
                main.post(new Runnable() {
                    @Override public void run() { closeConversation("球回到初始形态"); }
                });
            }
            // 球要缩回最小形态了 —— 文字必须「同一时刻」消失。
            // 只把文字设成空串是不够的：窗口/测量的更新是异步的，球收缩的这半秒里
            // 旧文字还留在屏幕上，看起来就是「球变小了、文字却飞出球外」。
            // 直接把文字视图藏掉（GONE 立即生效、不参与布局），球一变小就没有字。
            hideSiriTextNow();
            return;
        }
        if (siriTextPx <= 0) siriTextPx = dp(34);
        float base = winSize * 0.9f;
        if (base <= 0) base = 1f;

        // ── 文字高度从哪来 ────────────────────────────────────────────────────
        // 不再在 Java 里"猜"换行（StaticLayout 对表格/列表本来也不准），
        // 也不用 WebView 的 JS 回报 —— 原生渲染器已经 measure 过了，直接读。
        // ⚠️ 但 `mdContentH == 0` 说明内容被 forceHideAllText() 清空过（进菜单 / 开胶囊 /
        //    收字回球）：这时 mdContentH 已经不是"文字高度"了，先按 siriStr 重建再量，
        //    否则玻璃会退回一条窄条、字也没了（用户 2026-11-08 那张空玻璃截图就是这么来的）。
        if (mdContentH <= 0) ensureMarkdownContent();
        float th = Math.max(dp(20), mdContentH > 0 ? mdContentH : dp(40));
        float tw = mdLayoutW > 0 ? mdLayoutW : siriTextMaxW();
        // 上下留白（黑区/球高的余量）：字号大就多留一点
        // 文字四周留白：底部多留一截，保证最后一行完整落在玻璃里（用户：宁可玻璃拉长也别露字）
        float padY = Math.max(dp(18), siriTextPx * mdFontRatio() * 0.85f);
        float maxBallW = siriMaxBallW();
        // 方形态高度上限 = 宽度（最大就是个正方形）。多出来的文字在方形里滚动。
        float maxBallH = siriMaxBallH();
        // ── 方形态几何（用户 2026-10-16 明确要求）────────────────────────────
        //   · 宽**固定** = 屏宽 85%（siriMaxBallW），字号/文字多少都不许改它；
        //   · 高随文字往下长（th = 文字实际高度 + 上下留白），**不再强行拉成正方形** ——
        //     用户原话：「方形不是说是正方形，且方形的宽要固定好（屏幕宽度的 85%）不可变，
        //     高度随文字往下涨的体积变化（不限字号）」。
        float hxpx = maxBallW / 2f;
        // 对话卡：底部多留一条"输入区"，输入框就落在玻璃里（而不是浮在玻璃外面）
        // 会话面的输入行：底部多留一条"输入区"，输入框就落在玻璃里（而不是浮在玻璃外面）。
        // ★ 2026-11-09：不再看 convoOpen —— 只要输入行**真的在屏上**（回复页也算），
        //   玻璃就得给它留位置；否则最后几行字被输入行压住。
        float convoExtra = composerReservePx();
        // ★ 2026-11-09：玻璃高度必须**真的装得下**文字 + 底部那两条带子。
        //   以前只按 `th/2 + padY` 算，可 updateSiriTextPos 还要再扣 顶部8dp + 底部14dp +
        //   波头带40dp + 输入行一截 —— 结果文字视口比内容矮一大截，最后一行被切掉半行
        //   （实拍：表格下面那句「结束了。」只剩上半截）。
        float needH = th + dp(8) + dp(14) + dp(ANIM_BAND_DP) + convoExtra;
        float hypx = Math.max(base * minHalfY0, Math.max(th / 2f + padY, needH / 2f));  // 原版球高：聊天卡的最小高度不变
        hypx = Math.min(hypx, maxBallH / 2f + convoExtra / 2f);       // 高 ≤ 屏高 50%（+输入区）
        // 本轮只增不减：文字块被清空后又来一小段（工具调用之间很常见）时，方框不缩回去。
        if (textHold) {
            hypx = Math.max(hypx, holdHalfY * base);
            hypx = Math.min(hypx, maxBallH / 2f + convoExtra / 2f);   // 保持期也给输入行留位
            hxpx = maxBallW / 2f;                                    // 宽永远钉在这个值
        }
        {
            float r = 0.38f * Math.min(hxpx, hypx);                  // 圆角 ≈ 短边 38% → squircle
            tCorner = Math.max(0.18f, Math.min(minCorner, r / Math.max(1f, hypx)));
        }
        // 提示：球内文字被清空（siriStr 为空）但本轮还没结束时，黑区/文字层也跟着藏掉，
        //      只留一个近方形的空玻璃壳 —— 这正是用户要的「处理中保持形态」。
        if (s == null || s.trim().isEmpty()) {
            hideSiriTextNow();
            tBlack = 0f;
        }
        updateSiriWindow(hxpx, hypx);

        // ── 黑区：**固定死**，一个字都不看（用户 2026-11-09 口径）─────────────
        // 以前这里按「文字实际高度」反推 b1（b1 = 文字下沿 − 0.10），于是：
        //   · 回复变长 → 黑区跟着往下长；
        //   · 文字被收走 / 玻璃变矮 → 黑区又跳回去（用户截图里那块位置是对的，就是不稳）。
        // 现在只发一个常量：着色器 b1 = uBlack1 − uBlackOffset，玻璃顶永远纯黑、
        // 球心以下才开始变亮、最底边一条最亮 —— 位置跟着玻璃走，跟文字彻底脱钩。
        tBlack = BLACK_FIXED_NY;

        tHalfX = hxpx / base;
        tHalfY = hypx / base;
        tMaster = 1f;

        // 本轮出现过文字 → 进入保持，并累计最大几何（保持期间只增不减）
        if (releasingHold) {
            releasingHold = false;          // 释放后的那次测量只是把球缩回圆球，别再武装保持
        } else {
            enterTextHold();
        }
        holdHalfX = Math.max(holdHalfX, tHalfX);
        holdHalfY = Math.max(holdHalfY, tHalfY);
        holdCorner = Math.min(holdCorner, tCorner);
        holdBlack = Math.max(holdBlack, tBlack);

        // 几何已经定下来 → 这时才决定文字层要不要露头（方形态才允许，见 squareFormActive）
        if (s != null && !s.trim().isEmpty()) showSiriTextNow();
    }

    /**
     * 球已经回初始形态、窗口却还停在方形态的大盒子上 —— 那块透明区域照样吃触摸
     * （用户 2026-10-22：「球周围无法触摸屏幕，可能是变成方形后恢复初始状态后触摸有效区域没有及时更新」）。
     *
     * 每 400ms 巡检一次，两条判据：
     *   ① 文字空了、回合也结束了，却还在"保持方形态" → 直接收回初始圆球（releaseTextHold 内部会缩窗）；
     *   ② 球（动画值）已经是最小圆球了，窗口却明显更大 → 把窗口缩回"球 + 24dp 留白"。
     */
    private void auditWindowAgainstBall() {
        if (searchActive || approvalShown || menuShown || glassFullscreen || zonesActive
                || renderer == null || wm == null) return;
        // ① 非 Siri 形态（助手没接管、Siri 模式关着）：球按 renderer.halfX/halfY 画，
        //    窗口若还停在助手那一轮的方形态大盒子上，四周透明区域就把触摸吃了。
        //    这条路径以前**根本没人管**：siriTick 在 !siriEnabled 时直接 return，
        //    所以"方形态 → 回初始状态"之后窗口永远是 993×993（实测玻璃只有 661×449）。
        if (!siriEnabled) {
            float b = winSize * 0.9f;
            // 窗口要贴**画出来的**那颗球（2026-11-06 起普通球也乘了 idleScale，屏宽 1/3）
            float idle = ballIdleScale();
            float hx = (renderer.halfX > 0.02f ? renderer.halfX : 0.37f) * idle;
            float hy = (renderer.halfY > 0.02f ? renderer.halfY : 0.25f) * idle;
            int tw = Math.min(screenW, Math.round(2 * b * hx + 2 * dp(24)));
            int th = Math.min(screenH, Math.round(2 * b * hy + 2 * dp(24)));
            if (Math.abs(tw - winW) > dp(8) || Math.abs(th - winH) > dp(8)) {
                Log.i("FloatingBall", "window audit(idle): 窗口 " + winW + "x" + winH
                        + " → " + tw + "x" + th + "（球 " + Math.round(2 * b * hx) + "x"
                        + Math.round(2 * b * hy) + " + 2×24dp）");
                params.x += (winW - tw) / 2;          // 保持球心不动
                params.y += (winH - th) / 2;
                winW = tw; winH = th;
                params.width = tw; params.height = th;
                renderer.winPixelW = tw; renderer.winPixelH = th;
                try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
                glView.post(winSync);
            }
            return;
        }
        // ② Siri 形态：先把「保持」的兜底释放跑起来。pumpTextHoldSafety() 写好了却没人调用 ——
        //    于是镜像出字（浏览器那边在回话）、或流中途断掉时，textHold 永远不释放，
        //    窗口就永久停在方形态的大盒子上（用户看到的就是"球周围点不动"）。
        pumpTextHoldSafety();
        String s = siriStr;
        boolean textEmpty = s == null || s.trim().isEmpty();
        if (textHold && textEmpty && isIdleState()) {
            Log.i("FloatingBall", "window audit: 没在跑且文字为空 → 收回初始圆球（缩窗）");
            releaseTextHold();
            return;
        }
        float base = winSize * 0.9f;
        int restW = Math.round(2 * base * minHalfX0 + 2 * dp(24));   // 窗口 = 原版球窗（球本身缩小）
        int restH = Math.round(2 * base * minHalfY0 + 2 * dp(24));
        boolean ballAtRest = aHalfX <= minHalfX * 1.06f && aHalfY <= minHalfY * 1.06f;
        // 让「方框缩回圆球」那段缓动先走完再动窗口：动画中途就抢着缩窗，
        // 用户看到的是"啪一下跳回去"（代码里其它地方专门为这个调过 siriEaseTau）。
        long now = android.os.SystemClock.uptimeMillis();
        if (!ballAtRest) { ballRestSince = 0; return; }
        // ★ 2026-11-08（用户截图：球已经缩成胶囊、回复的字还摊在桌面上）：
        //   有字/正在保持方形态的时候，这个巡检**一律不许缩窗**。以前判据只看 textHold，
        //   于是「文字还在、hold 刚好被放掉」的那一瞬，窗口被打回 793×579（甚至更小），
        //   玻璃没了、文字层却还 VISIBLE → 截图里那种"字飘在桌面上"。
        if (hasBallText() || textHold) { ballRestSince = 0; return; }
        if (ballRestSince == 0) ballRestSince = now;
        if (now - ballRestSince < 700) return;
        if (winW > restW + dp(8) || winH > restH + dp(8)) {
            Log.i("FloatingBall", "window audit: 球已回最小形态，窗口 " + winW + "x" + winH
                    + " → " + restW + "x" + restH + "（触摸有效区域跟着缩）");
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
            updateSiriWindow(base * minHalfX, base * minHalfY);
            forceHideAllText();          // 兜底：缩窗的同时把文字层收掉（胶囊上绝不带字）
        }
        // ★ 2026-11-06：窗口已经**落定**在常态最小球窗（不是动画途中）→ 顺手校准一次「家」。
        //   centerBallOnScreen() 在启动那一帧记不了（窗口还是 993×993 / winY0=0），
        //   不在这里补记的话，家会永远停在那个偏低 117px 的假值上 —— 之后每次从
        //   聊天卡片缩回球，球都落在低一截的位置，波和圆点看着就不是启动那几个位置。
        if (!textHold && Math.abs(winW - restW) <= dp(2) && Math.abs(winH - restH) <= dp(2)) {
            rememberHomeIfMinimal();
        }
    }

    /** 每 400ms 巡检一次窗口/触摸区域。Siri 关着时 siriTick 会直接 return，所以必须独立一条。 */
    private final Runnable auditTick = new Runnable() {
        @Override public void run() {
            try { auditWindowAgainstBall(); } catch (Throwable ignored) { }
            main.postDelayed(this, 400);
        }
    };

    /** 回合是否已经结束（turn/end 或本地流收完）且当前没有在跑的状态。 */
    // ══════════ 对齐心跳：窗口 / 视图 / 绘制缓冲三者不一致 = 形状被压扁、图标对不准 ══════════
    // 这段只在 状态 2 / 状态 3 / 菜单 / 审批 挂着的时候每秒打一行，平时一条都不打。
    // 事后拿 logcat 就能看出"从哪一刻开始 winPixelW 和窗口宽度不一样了"。
    private long lastHbAt = 0;

    private void heartbeatTick(long now) {
        if (now - lastHbAt < 1000) return;
        lastHbAt = now;
        if (!zonesActive && !menuShown && !searchActive && !approvalShown) return;
        if (renderer == null || params == null || glView == null) return;
        int gw = glView.getWidth(), gh = glView.getHeight();
        Log.i("FloatingBall", "hb: search=" + (searchActive ? ("1/phase" + sqPhase) : "0")
                + " zones=" + (zonesActive ? "1" : "0")
                + " menu=" + (menuShown ? "1" : "0")
                + " approval=" + (approvalShown ? "1" : "0")
                + " win=" + params.x + "," + params.y + "," + params.width + "x" + params.height
                + " view=" + gw + "x" + gh
                + " pixel=" + renderer.winPixelW + "x" + renderer.winPixelH
                + " rWin=" + renderer.winX + "," + renderer.winY
                + " zone0=" + Math.round(zoneCx[0]) + "," + Math.round(zoneCy[0])
                // 输入框焦点：语音期间没有它 = 输入法没有编辑框 = 识别的字上不了屏
                + " imF=" + (searchBar != null && searchBar.input().hasFocus())
                + " barF=" + (searchBar != null && searchBar.hasFocus())
                + " winF=" + (searchBar != null && searchBar.hasWindowFocus()));
        boolean bad = gw > 0 && (Math.abs(gw - params.width) > 2 || Math.abs(gh - params.height) > 2
                || Math.abs(renderer.winPixelW - gw) > 2 || Math.abs(renderer.winPixelH - gh) > 2);
        if (bad) {
            Log.w("FloatingBall", "hb 不一致 → 形状会被压扁/错位：params=" + params.width + "x"
                    + params.height + " view=" + gw + "x" + gh + " pixel="
                    + renderer.winPixelW + "x" + renderer.winPixelH);
            // ★ 自愈：谁的路径忘了同步，这里都按 **View 的真实尺寸** 纠正一次 ——
            //   k = 缓冲 ÷ 窗口 差一点点，形状就整体缩放/偏移（六区图标对不准就是这么来的）。
            //   只在 View 已经落定（和 params 一致）时纠正，避免和窗口动画打架。
            if (gw > 0 && gh > 0 && Math.abs(gw - params.width) <= 2 && Math.abs(gh - params.height) <= 2
                    && (Math.abs(renderer.winPixelW - gw) > 2 || Math.abs(renderer.winPixelH - gh) > 2)) {
                int ow = renderer.winPixelW, oh = renderer.winPixelH;
                renderer.winPixelW = gw;
                renderer.winPixelH = gh;
                winW = gw;
                winH = gh;
                setGlassBufferScale((long) gw * gh);
                Log.w("FloatingBall", "hb 自愈：pixel " + ow + "x" + oh + " → " + gw + "x" + gh
                        + "，buffer 重新按窗口钉");
            }
        }
    }

    private boolean isIdleSettled() {
        String st = SiriAssistant.stateName();
        if (!("IDLE".equals(st) || "OFF".equals(st))) return false;
        return SiriAssistant.isTurnSettled();
    }

    /** 当前不在听/不在这轮问答里（镜像出字、流中断那些没有 turn/end 的路径也认）。 */
    private boolean isIdleState() {
        String st = SiriAssistant.stateName();
        return "IDLE".equals(st) || "OFF".equals(st);
    }

    private long lastWinAuditAt = 0;
    /** 球（动画值）落在最小形态上的起始时刻；用来给缩窗留出动画时间。 */
    private long ballRestSince = 0;

    private final Runnable siriTick = new Runnable() {
        @Override public void run() {
            if (!siriEnabled) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = siriTickLast == 0 ? 0.016f : Math.min(0.05f, (now - siriTickLast) / 1000f);
            siriTickLast = now;
            // 窗口/触摸有效区域的兜底巡检（用户 2026-10-22 报的「球周围点不动」）：
            // 球回圆球了、窗口却还停在方形态的大盒子上时，透明区域照样吃触摸。
            if (now - lastWinAuditAt > 400) { lastWinAuditAt = now; auditWindowAgainstBall(); }
            // 生长要跟手（0.06s），收起要从容（0.22s）—— 收字时从大方框缩回圆球，
            // 太快就是"啪一下跳回去"（用户报的"上下跳动"）
            float k = 1f - (float) Math.exp(-dt / Math.max(0.02f, siriEaseTau));
            // 搜索胶囊：几何**不由**这条指数缓动驱动，走自己的欠阻尼弹簧（searchStep 直接写 a*）
            if (searchActive) {
                searchStep(dt);
            } else {
            // 面板挂着时**每帧把目标值钉回审批那一套**：Siri 那条路（镜像打字、静默清空、
            // 新一轮提问）随时可能调 measureSiriTargets() 把 tHalf* 改成小球尺寸，
            // 这里不许它影响面板 —— 面板撤走时才把控制权交还（approvalShown=false）。
            if (approvalShown) {
                tMaster = 1f;
                tHalfX = aprHalfX; tHalfY = aprHalfY;
                tCorner = aprCorner; tBlack = aprBlack;
            }
            aMaster += (tMaster - aMaster) * k;
            aHalfX += (tHalfX - aHalfX) * k;
            aHalfY += (tHalfY - aHalfY) * k;
            aCorner += (tCorner - aCorner) * k;
            aBlack += (tBlack - aBlack) * k;
            }
            if (renderer != null) {
                renderer.siriGeom = true;
                renderer.svBlackOffset = aBlack;
                renderer.svMaster = aMaster;
                renderer.svHalfX = aHalfX;
                renderer.svHalfY = aHalfY;
                renderer.svCorner = aCorner;
                renderer.svPosX = 0.5f;
                renderer.svPosY = 0.5f;
            }
            // 审批球挂着的时候：几何由审批那一套驱动，原球那条 Siri 文字通道一律不碰 ——
            // 这样原球的文字、钉住状态、光效设定在整个审批期间原封不动。
            // 窗口跟着**动画值**走：文字期的方框是"一步步"量出来的（每换一行跳一次），
            // 而收字回圆球时更是从 1044x2097 一次性掉回 787x573 —— 用户看到的就是"上下跳"。
            // 这里按 16ms 的节拍让它跟着缓动值连续变，只在变化超过阈值时才真的动窗口。
            // 搜索层：玻璃窗是整屏（进场时设好，不动），输入行的摆放由 searchStep 负责
            if (!searchActive && !approvalShown && siriEnabled && aMaster > 0.02f) {
                float baseS = winSize * 0.9f;
                maybeUpdateSiriWindowSmooth(baseS * aHalfX * aMaster, baseS * aHalfY * aMaster);
            }
            if (approvalShown) {
                updateApprovalWindow();
                updateApprovalLayout();
                updateApprovalTouchLayer(winSize * 0.9f * aHalfX * aMaster,
                        winSize * 0.9f * aHalfY * aMaster,
                        renderer != null ? renderer.winX : params.x,
                        renderer != null ? renderer.winY : params.y);
            } else if (!searchActive) {
                updateSiriTextPos();
            }
            // ★ 2026-11-09：输入行每帧跟一次"此刻玻璃的样子"。
            //   这是问题 ② 的根治点：以前输入行只在 openConversation / 关搜索 / 关菜单
            //   这三处显式摆位，球"自己缩回胶囊"这条路上没人收它 → 玻璃没了、输入框还挂着。
            if (!approvalShown) syncComposerBar();
            heartbeatTick(now);
            main.postDelayed(this, 16);
        }
    };

    /** Siri：把悬浮窗尺寸调整为贴合当前球（触摸有效区域=球），并保持球心屏幕位置不变。 */
    // ── 球的"家"：常态（最小圆球）时球心的屏幕坐标 ──────────────────────────
    // 方形态的长高以它为锚：**顶边钉在家的位置，高度往下长**（用户要求"高度随文字往下涨"）。
    // 以前是保持"球心"不动 → 文字一变长球就往上顶，观感就是"球在往上跑"。
    // 用户拖动球、或常态下窗口重建时更新它。
    private float homeCx = -1f, homeCy = -1f;

    /**
     * 记一次"家"。**只在当前就是最小形态时**才记 —— 否则方形态那套大几何会被记成家，
     * 下一次定位又拿它当基准，位置会越滚越偏（实测滚到过 -3e7 这种离谱值，球直接飞出屏幕）。
     */
    private void rememberHomeIfMinimal() {
        if (renderer == null || winW <= 0 || winH <= 0) return;
        // ⚠️ 搜索胶囊期间**绝不能**记家：小黑球比最小圆球还小，判据会认成"最小形态"，
        // 于是把小黑球的中心记成新的家（这个坑和"方框缩回途中误记家"是同一类，见下）。
        if (searchActive) return;
        // ⚠️ 判据必须用「目标几何 + 本轮没出过字 + 窗口也是最小窗口」，**不能只看动画值**：
        // 方框缩回圆球的过程中，动画值会途经最小尺寸，而那一刻窗口还停在被 clamp 顶上来的
        // 位置 —— 于是把那个位置记成了"家"，球从此永久偏上去。
        // 实测症状：长回复结束后球停在 449..896，而它的家本该是 982..1555（差了 530px），
        // 用户看到的就是"会话结束弹出最后文字时上下跳，然后像跳出屏幕不见了"。
        float minHalfH = winSize * 0.9f * minHalfY0;      // 窗口还是原版球窗（见 updateSiriWindow）
        int minWinH = Math.round(2 * minHalfH + 2 * dp(24));
        boolean minimal = !textHold
                && tHalfX <= minHalfX * 1.02f && tHalfY <= minHalfY * 1.02f
                && renderer.svHalfX <= minHalfX * 1.06f && renderer.svHalfY <= minHalfY * 1.06f
                && winH <= minWinH * 1.12f;
        if (!minimal) return;
        float cx = params.x + winW * 0.5f;
        float cy = params.y + winH * 0.5f + winY0();       // ★ home 一律是**屏幕坐标**（ballTopOnScreen 按屏幕 y 用）
        if (cx < -screenW || cx > screenW * 2f || cy < -screenH || cy > screenH * 2f) return;   // 离谱值直接丢弃
        homeCx = cx;
        homeCy = cy;
    }

    /** 定位用的家：没记过 / 记成了离谱值 → 退回屏幕中心。 */
    private float homeCxSafe() {
        if (homeCx < 0f || homeCx > screenW * 2f) return screenW * 0.5f;
        return homeCx;
    }

    private float homeCySafe() {
        if (homeCy < 0f || homeCy > screenH * 2f) return screenH * 0.5f;
        return homeCy;
    }

    /**
     * 按动画值平滑地跟窗口尺寸（过渡期用）。阈值 14px：静止时不产生任何窗口操作，
     * 生长/收起时每帧连续变化 —— 屏幕上就是"方框连续长大/缩回"，而不是一格一格跳。
     */
    private void maybeUpdateSiriWindowSmooth(float hxpx, float hypx) {
        float m = dp(24);
        // 同 updateSiriWindow：窗口不小于原版球窗（菜单按它排版）
        float base0 = winSize * 0.9f;
        int W = Math.min(screenW, Math.max(2, Math.round(2 * Math.max(hxpx, base0 * minHalfX0) + 2 * m)));
        int H = Math.min(screenH, Math.max(2, Math.round(2 * Math.max(hypx, base0 * minHalfY0) + 2 * m)));
        if (Math.abs(W - winW) < 14 && Math.abs(H - winH) < 14) return;
        updateSiriWindow(hxpx, hypx);
    }

    private void updateSiriWindow(float hxpx, float hypx) {
        if (!siriEnabled || wm == null || glView == null) return;
        // 菜单面板 / 状态 3 期间，窗口归那块玻璃用：球的几何更新一律不许改窗口大小
        // （之前 audit 已挡了，但 siriTick 每帧还会经 measureSiriTargets 调到这里，窗口被打回球尺寸）
        // ★ zonesActive 也要挡：状态 3 期间窗口归卡片用（卡片比球窗高时我们主动撑高窗口），
        //   不挡的话 siriTick → measureSiriTargets → 这里每帧把窗口打回球尺寸 → 卡片又被裁成 4 行。
        // ★ 2026-11-08：searchActive 要**单挡**。以前写的是 `glassFullscreen && searchActive`，
        //   而搜索层从来没把 glassFullscreen 置 true（那是玻璃菜单的标志）→ 这个守卫等于不存在。
        //   于是搜索胶囊开着的时候，只要「最终回复安静 1.5s → releaseHoldForFinalReply()」
        //   （或任何别的路径）走到这里，整屏玻璃窗就被打回球窗 793×579@(143,117) ——
        //   屏幕上就是"搜索框待久了，自己变成一个小胶囊/显示错位"（2026-11-08 复现：
        //   win=0,-90,1080x2340 → 793x579，期间 search=1/phase2）。
        //   搜索期间窗口归弹簧/胶囊用，别人一律不许改。
        if (menuShown || zonesActive || searchActive) return;
        float m = dp(24);
        // ★ 2026-11-06：球缩到屏宽 1/3，但**窗口还是原版大小** —— 六区菜单、列表面板、
        //   聊天卡的排版全都按这个窗口算，窗口一缩它们就全变（用户要求：菜单/聊天不能受影响）。
        //   所以窗口尺寸取「原版球」和「当前形状」的较大者：球在时 = 原版球窗（和缩小前一模一样），
        //   方形态/卡片更大时才跟着长（和平时的行为一致）。
        float base0 = winSize * 0.9f;
        // ★ 2026-11-06（十一）：**光秃秃一颗球**的时候，窗口贴着球缩到最小，不再固定成原版球窗。
        //   原版球窗（793×579）会在球四周铺开一大片透明玻璃：`makeTouchTransparent()` 把它的
        //   可触摸区置空了，但个别 ROM / 个别时刻那片"透明玻璃"照样会吃掉点击 —— 用户报的
        //   「球左上角方向那片屏幕点不动」就在这片里（实测窗口 [137,169]-[930,748]，球只有
        //   360×243，球外面那一大圈全是不该存在的接触面）。
        //   带文字/卡片/菜单/六区/胶囊/审批时仍然按原版球窗走（那些排版就是按那个窗算的），
        //   一点不变。
        boolean bareBall = !siriTextShown && !textHold && !convoOpen
                && !menuShown && !zonesActive && !approvalShown && !searchActive && !glassFullscreen;
        float wx = bareBall ? hxpx : Math.max(hxpx, base0 * minHalfX0);
        float wy = bareBall ? hypx : Math.max(hypx, base0 * minHalfY0);
        int W = Math.min(screenW, Math.max(2, Math.round(2 * wx + 2 * m)));
        int H = Math.min(screenH, Math.max(2, Math.round(2 * wy + 2 * m)));
        if (Math.abs(W - winW) > 60 || Math.abs(H - winH) > 60) {
            Log.i("FloatingBall", "ball window -> " + W + "x" + H + " (halfX=" + hxpx + " halfY=" + hypx + ")");
        }
        if (renderer != null) { renderer.winPixelW = W; renderer.winPixelH = H; }
        if (W == winW && H == winH) return;
        final float hx0 = homeCxSafe(), hy0 = homeCySafe();
        winW = W; winH = H;
        params.width = W; params.height = H;
        markWinGeomChanged();                   // 刚改过窗口 → 这一刻的 getLocationOnScreen 不可信
        setGlassBufferScale((long) W * H);      // 大方框降分辨率渲染玻璃（否则每帧 45~55ms = 卡顿）
        // 水平：以"家"为中心（宽固定 85% 屏宽，左右对称）。
        params.x = Math.round(hx0 - W * 0.5f);
        // 竖直：**家的球顶钉住，高度往下长**（用户要求"高度随文字往下涨"）。
        // 以前保持球心不动 → 文字一变长球就往上顶，观感是"球在往上跑、波也跟着上去"。
        // 窗口 = 球 + 四周 m 留白 ⇒ 窗口顶边 = 常态圆球的球顶 - m。
        // 竖直定位：球顶（含"躲开输入区"的上移）+ 夹到屏幕内。
        // ⚠️ 必须在这里按**屏幕坐标**夹：ballTopOnScreen() 返回的是屏幕 y，
        // 而 params.y 是请求坐标（与屏幕差一个 inset）。之前只在 ballTopOnScreen 内部夹，
        // 夹的是请求坐标 → 球实际会掉到屏幕外（实测球窗到 1024+1449=2473，屏只有 2412），
        // 文字跟着一起出界，用户看到的就是"底下文字超出方形/看不到"。
        int reqY = Math.round(ballTopOnScreen(hypx) - m - winY0());
        int winHNow = params.height;                       // 刚设好的窗口高
        int minReqY = Math.round(winY0() - m);             // 玻璃顶不高于屏幕上沿
        int maxReqY = Math.round(screenH - winY0() - (winHNow - 2 * m) - m);  // 玻璃底不低于屏幕下沿
        if (maxReqY < minReqY) maxReqY = minReqY;
        params.y = Math.max(minReqY, Math.min(maxReqY, reqY));
        Log.i("FloatingBall", "siri window place: home=(" + Math.round(hx0) + ","
                + Math.round(hy0) + ") 球顶=" + Math.round(ballTopOnScreen(hypx))
                + " m=" + Math.round(m) + " -> params=(" + params.x + "," + params.y + ")"
                + " size=" + W + "x" + H);
        try { wm.updateViewLayout(glView, params); } catch (Exception ignored) { }
        glView.post(winSync);
        clampBallOnScreen();          // 文字变长 → 球变大 → 可能要往屏内挪，否则文字出界
    }

    /**
     * 每帧把文字层摆进玻璃里。
     *
     * 用户要求「文字始终被束缚在液态玻璃里面」，所以这里三件事：
     *   ① 宽度 = 方形态固定内宽（与窗口此刻多宽无关，避免换行变化导致抖动）；
     *   ② 高度 = 球顶+留白 → 球底−16px（球底有圆角与波的锚点，收到球底会看起来"漏出球外"）；
     *   ③ 位置以**玻璃的真实屏幕矩形**为准夹取（不能拿屏幕高当约束，那会把文字推出去）。
     */
    private void updateSiriTextPos() {
        if (siriText == null || siriParams == null || wm == null) return;
        if (siriText.getVisibility() != View.VISIBLE) return;   // 藏起来（球回最小形态）就别再摆
        if (glView == null) return;
        // 玻璃的**真实屏幕矩形**是唯一可信基准：renderer.winX/winY 是 500ms 刷一次的缓存，
        // 窗口刚变大时是过期值（拿它算会把文字整体推偏，实测推低到屏外）。
        int[] gl = new int[2];
        glView.getLocationOnScreen(gl);
        float winXnow = gl[0], winYnow = gl[1];
        int sysDy = Math.round(winYnow) - params.y;             // 请求坐标 → 屏幕坐标的系统 inset
        float base = winSize * 0.9f;
        if (base <= 0) base = 1f;
        float sx = winXnow + winW * 0.5f;
        // ── 玻璃的**可见边界**从"窗口矩形 − 窗口边距 m"算 ────────────────────
        // ⚠️ 两个坑合起来才定位到这里（都用实测数据锁定的）：
        //  ① 玻璃不是铺满窗口的：窗口四边各有 m(=dp(24)≈63px) 的留白 —— 实测截一帧量到
        //     "上/下/左/右各 62px 全是透明"；所以**窗口边缘 ≠ 玻璃边缘**，差 62px。
        //  ② 半高不能用动画值 aHalfY：窗口是用动画值摆的，两者在动画途中会差一截
        //     （实测 aHalfY 差分出 96px），文字就跑到球外面去了。
        // 由窗口尺寸反推半高，天然与屏幕一致；窗口位置也直接取 view 的真实位置。
        float m = dp(24);
        float ballHalfH = Math.max(dp(20), (winH - 2f * m) / 2f);
        float ballTop = winYnow + m;
        float ballBottom = ballTop + ballHalfH * 2f;

        int topPad = (int) dp(8);           // 与 rebuildMarkdown 的 padTop 保持一致
        int padBottomSafe = (int) dp(14);
        int tw = Math.max((int) dp(40), mdLayoutWidthPx());
        // ★ 2026-11-08：底带改成**固定高度**（dp），不再跟玻璃高度按比例长。
        //   原来 14%（夹在 70~200px）的算法有个副作用：玻璃一变高带子就变高，
        //   Siri 波头露出的位置也跟着「长」——用户要的是「玻璃怎么变，只露波头这一截」。
        //   现在文字视口底 = 玻璃底 − 这条固定带子：玻璃长高只多显示几行字，波头纹丝不动。
        animBandPx = Math.round(dp(ANIM_BAND_DP));
        siriAnimBand = animBandPx;
        // 高度 = 球顶+留白 → 球底−留白（再减动效带），**同时**不能超出屏幕可用范围，
        // 也不能小于 0（球被摆得很高时空间会不够）。空间不足就变矮，由 ScrollView 滚。
        int availTop = Math.round(ballTop + topPad);
        // 输入行占掉的那条带子（在"波头带"**之上**）：有输入行时文字视口往上收一截，
        // 否则最后两行字会被输入行压住（用户口径：文字必须整行落在玻璃里）。
        int composerBand = composerReservePx();
        int availBottom = Math.round(Math.min(ballBottom - padBottomSafe - animBandPx - composerBand,
                screenH - (int) dp(8)));
        int th = Math.max((int) dp(40), availBottom - availTop);
        th = Math.min(th, Math.max((int) dp(40), Math.round(screenH - dp(12))));
        siriViewportH = th;
        int x = Math.max(0, Math.min(Math.max(0, screenW - tw), Math.round(sx - tw / 2f)));
        int wantScreenY = Math.round(ballTop) + 2;
        int maxScreenY = Math.round(ballBottom) - padBottomSafe - th;   // 不越球底
        if (maxScreenY < wantScreenY) maxScreenY = wantScreenY;         // 球比文字还矮：贴球顶
        int screenLimit = Math.round(screenH - dp(8) - th);             // 也不越屏幕底
        int screenY = Math.max(wantScreenY, Math.min(maxScreenY, availTop));
        if (screenY > screenLimit) screenY = Math.max(wantScreenY, screenLimit);
        int y = screenY - sysDy;

        // ⚠️ 只在真的变了才 updateViewLayout（这函数跑在 16ms 的 tick 上，每帧重摆 = 每帧一次跨进程事务）
        if (x != siriTextLastX || y != siriTextLastY
                || tw != siriTextLastW || th != siriTextLastH) {
            siriTextLastX = x; siriTextLastY = y;
            siriTextLastW = tw; siriTextLastH = th;
            siriParams.x = x; siriParams.y = y;
            siriParams.width = tw; siriParams.height = th;
            try { wm.updateViewLayout(siriText, siriParams); } catch (Throwable ignored) { }
            int realBottom = y + sysDy + th;
            if (realBottom > Math.round(ballBottom) + 2 && Math.abs(th - siriTextLogH) > 24) {
                siriTextLogH = th;
                Log.w("FloatingBall", "文字窗超出球底！文字底=" + realBottom
                        + " 球底=" + Math.round(ballBottom) + " 高度=" + th);
            }
        }
        syncSiriTouchLayer();
        // ★ 2026-11-08：胶囊状态（球还没长成方形态 / 正在缩回）里不许露字。
        //   可见度跟着**画出来的**几何走 → 球一缩成胶囊，字立刻淡没（不是等下一次测量）。
        float sq = squareDrawn01();
        siriText.setAlpha(Math.max(0f, Math.min(1f, (aMaster - 0.1f) / 0.7f)) * sq);
    }

    /** 上一次打过"越界"告警的文字高度（抑制刷屏）。 */
    private int siriTextLogH = -1;

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 触摸点（视图坐标）是否落在当前水晶球形状内（圆角矩形 SDF）。 */
    private boolean inSiriBall(float lx, float ly) {
        if (renderer == null) return true;
        float base = winSize * 0.9f;
        float m = Math.max(0.01f, renderer.svMaster);
        float hx = base * renderer.svHalfX * m;
        float hy = base * renderer.svHalfY * m;
        float r = base * renderer.svHalfY * renderer.svCorner * m;
        float cx = winW * 0.5f, cy = winH * 0.5f;
        float dx = Math.abs(lx - cx) - Math.max(0f, hx - r);
        float dy = Math.abs(ly - cy) - Math.max(0f, hy - r);
        float ex = Math.max(dx, 0f), ey = Math.max(dy, 0f);
        float sd = Math.min(Math.max(dx, dy), 0f) + (float) Math.sqrt(ex * ex + ey * ey) - r;
        return sd <= dp(8);   // 少量边缘容差，便于点到
    }

    private final MediaProjection.Callback projectionCb = new MediaProjection.Callback() {
        @Override public void onStop() { stopSelf(); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        Log.i("FloatingBall", "onCreate pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid() + " ver=" + BuildConfigVersion.V);
        running = true;
        INSTANCE = this;
        exemptHiddenApis();
        refreshTouchDevices();
        createChannel();
        registerVolumeSendReceiver();     // 音量减 = 收麦 + 发新会话（球没开时没人收，见方法注释）
        // DSH 文本助手：同时起一条常驻镜像流，
        // DSH 里只要 AI 在回话（不管是谁问的），球就逐字出字。
        try {
            SiriAssistant a = SiriAssistant.get(this);
            Log.i("FloatingBall", "assistant got: enabled=" + a.enabled()
                    + " approvalOrb=" + a.approvalEnabled());
            if (a.enabled()) a.startMirror();
            a.startApprovals();          // 审批球：DSH 要你批的时候就弹另一颗球
            Log.i("FloatingBall", "assistant init done");
        } catch (Throwable t) {
            Log.w("FloatingBall", "siri assistant init failed: " + t, t);
        }
        main.post(auditTick);   // 窗口/触摸区域巡检（Siri 关着也要跑）
    }

    /** 构建版本号（日志里认版本用，省得怀疑装错了包）。 */
    static final class BuildConfigVersion {
        static final String V = "convo-unify-1";
    }

    /** 允许反射调用 @hide 的 SurfaceControl#setSkipScreenshot（抓屏排除本窗口）。
     *  用「反射 Class.forName/getDeclaredMethod」的元反射绕过 Android 的 hidden API 限制。 */
    private static void exemptHiddenApis() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmClass, "getRuntime", new Class[0]);
            Method setExempt = (Method) getDeclaredMethod.invoke(
                    vmClass, "setHiddenApiExemptions", new Class[]{String[].class});
            Object vm = getRuntime.invoke(null);
            setExempt.invoke(vm, (Object) new String[]{"L"});
        } catch (Throwable t) {
            Log.w("FloatingBall", "exemptHiddenApis failed: " + t);
        }
    }

    /** 给悬浮窗的 Surface 打上 SKIP_SCREENSHOT 标记：系统截图/录屏（含 root screencap）不再包含水晶球， */
    /** 给悬浮窗的 Surface 打上 SKIP_SCREENSHOT 标记：系统截图/录屏（含 root screencap）不再包含水晶球， */
    /**
     * 调试口子：`files/show-glass` 存在时**不给玻璃打 SKIP_SCREENSHOT**，
     * root 截屏就能拍到"用户真正看到的合成画面"（含窗口底色/阴影/文字叠放）。
     * `files/hide-glass` 关掉。**生产必须关**（开着的话抓屏里含着球自己 → 折射会自采样）。
     */
    private volatile boolean debugShowGlass = false;

    private boolean applySkipScreenshot() {
        if (debugShowGlass) return false;
        if (Build.VERSION.SDK_INT < 29 || glView == null) return false;
        return skipScreenshotOf(glView.getSurfaceControl(), "glass");
    }

    /**
     * 上一次成功打过 SKIP_SCREENSHOT 的**那个** SurfaceControl。
     * 判据必须是"当前这个 SurfaceControl 打过没有"，而不是一个 boolean —— 见下面那段。
     */
    private SurfaceControl glassMarkedSc = null;

    /**
     * 玻璃层的抓屏排除：**按当前 SurfaceControl 的身份**判断要不要重打。
     *
     * 这修的是用户 2026-11-06 报的「音量加隐藏 → 长按电源键恢复后，球的折射效果消失，
     * 一杀进程重开又好了」：
     *   音量加隐藏走 `glView.setVisibility(INVISIBLE)` —— SurfaceView 的 surface 会被系统
     *   **销毁**（实测 `dumpsys SurfaceFlinger --list` 里那个 `SurfaceView[]` 图层直接消失），
     *   恢复可见时再**重建**，`getSurfaceControl()` 返回的是**另一个对象**；而
     *   SKIP_SCREENSHOT 打在 SurfaceControl 上 → 跟着旧对象一起没了。
     *   同期 `renderer.selfExcluded` 还是 true（成功过一次就没人复位），旧代码那句
     *   `if (!selfExcluded) apply...` 就永远进不去 → 新 surface 再没被打过标记
     *   → root 抓屏（CapLive）开始**把球自己也拍进去**（实测：那一刻 `screencap` 能拍到球了，
     *   正常情况下是拍不到的）→ 球折射自己的上一帧 → 屏幕上就是「玻璃变死、折射消失」，
     *   而且**不会自愈**：只有杀进程重开、重走一遍 surfaceCreated 才好（正好是用户的描述）。
     *
     * 现在：SurfaceControl 换了新对象就补打；补打成功后把背景重灌一次，
     * 免得标记失效那几帧"含球"的画面被 ping-pong 永久卡在球里。
     */
    private void ensureGlassSkipScreenshot() {
        if (glView == null || renderer == null) return;
        if (Build.VERSION.SDK_INT < 29) return;
        SurfaceControl sc = null;
        try { sc = glView.getSurfaceControl(); } catch (Throwable ignored) { }
        if (sc == null) return;                       // surface 还没真正建出来，下一拍再来
        if (sc == glassMarkedSc && renderer.selfExcluded) return;
        if (glassMarkedSc != null && glassMarkedSc != sc) {
            // 换了新的 SurfaceControl：受信任 overlay / 文字层那几个标记也一起失效了，全部重打
            surfaceTrusted = false;
            textSkipShotDone = false;
            Log.i("FloatingBall", "glass: surface 换了新 SurfaceControl → 重打抓屏排除");
        }
        if (applySkipScreenshot()) {
            glassMarkedSc = sc;
            if (!renderer.selfExcluded) {
                renderer.selfExcluded = true;
                resetGlassBackground("抓屏排除补打");
            }
        } else {
            // show-glass 调试态（applySkipScreenshot 恒 false）→ 保持"球留在抓屏里"
            glassMarkedSc = null;
            renderer.selfExcluded = false;
        }
    }

    /** 打上抓屏排除之后把球里的背景重灌一遍（GL 线程）。 */
    private void resetGlassBackground(final String why) {
        if (glView == null || renderer == null) return;
        glView.queueEvent(new Runnable() {
            @Override public void run() {
                if (renderer == null) return;
                renderer.resetBackground();
                Log.i("FloatingBall", "背景重灌（" + why + "）");
            }
        });
    }

    /**
     * 盯住玻璃层 surface 的销毁/重建。
     *
     * 隐藏（INVISIBLE）时 surface 会被销毁、恢复时重建 —— 这是 surface 级标记（抓屏排除 /
     * 受信任 overlay）失效的唯一入口。500ms 的 winSyncLoop 是兜底，这里让补打**立刻**发生
     * （否则恢复后的前几帧还是"含球"的抓屏，背景容易被灌脏）。
     */
    private void watchGlassSurface() {
        if (glView == null) return;
        try {
            glView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    Log.i("FloatingBall", "glass surface created → 补打 surface 级标记");
                    main.post(new Runnable() { @Override public void run() {
                        ensureGlassSkipScreenshot();
                        if (siriText != null) applyTextSkipScreenshot();
                    }});
                }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    Log.i("FloatingBall", "glass surface destroyed → surface 级标记作废，等重建后补打");
                    surfaceTrusted = false;
                    textSkipShotDone = false;
                    glassMarkedSc = null;
                    if (renderer != null) renderer.selfExcluded = false;
                }
            });
        } catch (Throwable t) {
            Log.w("FloatingBall", "watchGlassSurface failed: " + t);
        }
    }

    /**
     * 球里那层**文字**也要打 SKIP_SCREENSHOT。
     *
     * 为什么：`applySkipScreenshot()` 只处理了玻璃层（SurfaceView 的 SurfaceControl），
     * 文字是一个普通 View 的独立悬浮窗 —— 结果截屏/录屏里玻璃没了、**文字还在**：
     * 一张图上凭空浮着球里那段白字（实测：5220 字的长回答截屏时整块白字压在桌面上）。
     * 普通 View 没有 `getSurfaceControl()`，只能反射走 ViewRootImpl（API 29+ 有这个方法）。
     */
    /** 文字层是否已经成功打过 SKIP_SCREENSHOT（成功一次就够，别每 500ms 重打）。 */
    private volatile boolean textSkipShotDone = false;

    private boolean applyTextSkipScreenshot() {
        if (textSkipShotDone) return true;
        boolean ok = applySkipScreenshotTo(siriText, "text");
        if (ok) textSkipShotDone = true;
        return ok;
    }

    /**
     * 给**任意**悬浮窗 View 打 SKIP_SCREENSHOT（抓屏/录屏不再包含它）。
     *
     * 为什么需要通用版：审批面板有**三层独立窗口**（文字块 / 选项滚动 / 输入行）。
     * 它们以前从来没打过这个标记 → 出现在 MediaProjection 的抓屏画面里
     * → 球壳的液态玻璃把抓屏当背景采样 → **玻璃里折射出面板自己的按钮**
     * （用户报的"审批界面的液态玻璃会折射里面的按钮本身，怪怪的"）。
     * 窗口刚 add 时 SurfaceControl 可能还没 native 对象，所以返回 false 让调用方稍后重试。
     */
    private boolean applySkipScreenshotTo(View v, String what) {
        return applySkipScreenshotTo(v, what, true);
    }

    /** on=false = 临时撤掉标记（取证快门要拍到这一层的真样子）。 */
    private boolean applySkipScreenshotTo(View v, String what, boolean on) {
        if (Build.VERSION.SDK_INT < 29 || v == null) return false;
        try {
            Object vr = android.view.View.class.getMethod("getViewRootImpl").invoke(v);
            if (vr == null) return false;
            Object sc = vr.getClass().getMethod("getSurfaceControl").invoke(vr);
            if (sc == null) return false;
            try {
                Object valid = sc.getClass().getMethod("isValid").invoke(sc);
                if (Boolean.FALSE.equals(valid)) return false;      // 还没画出来 → 下轮再来
            } catch (Throwable ignored) { }
            return skipScreenshotOf((SurfaceControl) sc, what, on);
        } catch (Throwable t) {
            Log.w("FloatingBall", what + " setSkipScreenshot failed: " + t);
            return false;
        }
    }

    /** 面板窗口的抓屏排除：窗口刚建时 SurfaceControl 可能无效，重试几次。 */
    private void scheduleApprovalSkipScreenshot() {
        if (Build.VERSION.SDK_INT < 29) return;
        // 调试口子 show-glass：审批面板也一起留在抓屏里 —— 否则截出来只有一块玻璃，
        // 面板的排版（太大/圆角/溢出）根本没法验。
        if (debugShowGlass) {
            Log.i("FloatingBall", "debug: 审批面板留在抓屏里（show-glass）");
            return;
        }
        for (int i = 0; i < 6; i++) {
            final int attempt = i;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    boolean ok1 = applySkipScreenshotTo(approvalContent, "approval-content");
                    boolean ok2 = applySkipScreenshotTo(approvalActionsScroll, "approval-actions");
                    boolean ok3 = applySkipScreenshotTo(approvalInputRow, "approval-input");
                    if ((ok1 || approvalContent == null) && (ok2 || approvalActionsScroll == null)
                            && (ok3 || approvalInputRow == null)) {
                        Log.i("FloatingBall", "审批面板抓屏排除完成（第 " + (attempt + 1) + " 次尝试）");
                    }
                }
            }, 120L + i * 220L);
        }
    }

    private boolean skipScreenshotOf(SurfaceControl sc, String what) {
        return skipScreenshotOf(sc, what, true);
    }

    /** on=false 用来**撤销**标记（调试口子 show-glass 要拍合成画面）。 */
    private boolean skipScreenshotOf(SurfaceControl sc, String what, boolean on) {
        if (sc == null) return false;
        try {
            Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txClass.getDeclaredConstructor().newInstance();
            try {
                Method m = txClass.getMethod("setSkipScreenshot", SurfaceControl.class, boolean.class);
                m.invoke(tx, sc, on);
            } catch (NoSuchMethodException e) {
                Method m = txClass.getMethod("setSkipScreenshot", boolean.class);
                m.invoke(tx, on);
            }
            txClass.getMethod("apply").invoke(tx);
            Log.i("FloatingBall", "skip-screenshot applied: " + what);
            return true;
        } catch (Throwable t) {
            Log.w("FloatingBall", "setSkipScreenshot failed (" + what + "): " + t);
            return false;
        }
    }


    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        // 清掉历史遗留的调试开关 float_no_skipshot：它会让球不被排除出抓屏 → 自我采样，
        // 屏幕上看着就是「半透明贴纸」。代码里已删掉这个口子，设备上残留的值也在这里清掉。
        if (prefs().getBoolean("float_no_skipshot", false)) {
            prefs().edit().remove("float_no_skipshot").apply();
            Log.w("FloatingBall", "清掉残留的 float_no_skipshot（自我采样根源）");
        }
        if (intent != null) {
            resultCode = intent.getIntExtra("code", 0);
            resultData = intent.getParcelableExtra("data");
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (glView == null) setupOverlay();

        // 电源键那条路（PowerBallReceiver 带 pk_hold 进来）：
        //   start = 球出来 + 直接进「按住说话」；
        //   stop  = 收麦 → 转写 → 发送：默认发回「上一次回话」（pk_send=new 时发全新会话）。
        final String pkHold = intent == null ? null : intent.getStringExtra("pk_hold");
        // pk_send: "new" = 全新会话 / "last" = 上一次回话 / 缺省或 "auto" = 跟「发送目标」设置走
        final String pkSend = intent == null ? null : intent.getStringExtra("pk_send");
        final boolean pkNewSession = (pkSend == null || "auto".equals(pkSend))
                ? sendToNewSession() : "new".equals(pkSend);
        final boolean pkFromVolume =
                intent != null && "vol_key".equals(intent.getStringExtra("pk_from"));
        if ("stop".equals(pkHold)) {
            main.post(new Runnable() {
                @Override public void run() { endPowerHold(pkNewSession, pkFromVolume); }
            });
            return START_NOT_STICKY;
        }
        // ★ 2026-11-07：「状态 2 里已经打好的字」也走这条键。
        //   以前电源键只会开麦、音量减键只会收麦，输入框里的字根本没有出口 —— 按哪个键都不发。
        if ("text".equals(pkHold)) {
            main.post(new Runnable() {
                @Override public void run() { sendComposerByKey(pkNewSession, !pkFromVolume); }
            });
            return START_NOT_STICKY;
        }
        if ("start".equals(pkHold)) {
            main.postDelayed(new Runnable() {
                @Override public void run() { beginPowerHold(); }
            }, 700);
        } else {
            // 初始状态会"听"：等窗口真的起来、且没在做别的事之后再开麦
            main.postDelayed(new Runnable() {
                @Override public void run() { armVoice("服务启动/球弹出"); }
            }, 1200);
        }
        // 预热"工作目录 + 各目录会话"：第一次长按进状态 3 时列表已经是热的
        main.postDelayed(new Runnable() {
            @Override public void run() { prefetchZonesData(); }
        }, 2500);
        // 启动后把球摆到"水平居中、贴近屏幕顶端"——方形长高时只会往下长
        main.postDelayed(new Runnable() {
            @Override public void run() { centerBallOnScreen(); }
        }, 800);
        return START_NOT_STICKY;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 三态手势机（2026-10-23 按用户草图重做）
    //
    //   初始状态（球，会听）
    //     · 弹出即开麦：VAD 判定"话音刚落" → 自动停 → 转写 → 发到**新会话**
    //     · 识别过程中单击 → 停止识别（音频照样转写）→ 进入状态 2，文字留在输入框
    //     · 长按 → 状态 3
    //
    //   状态 2（胶囊，= 原来的搜索框）
    //     · 单击 → 编辑（弹键盘）；回车 → 发送到会话（默认新会话，可先在工作目录/搜索里选）
    //     · 长按 → 添附件（等价于前端输入框的 `uV2eYG_add`）
    //     · 双指长按 → Agent 预设 / 模型 / 推理等级 / 权限 弹窗
    //     · 点输入框以外 / 发送完 → 回初始状态
    //
    //   状态 3（四个液玻璃圆区，按住不放才出现）
    //     · 松手不在任何区 → 回初始状态
    //     · 左上 → 二级菜单 1：工作目录 → 三级菜单：该目录下的会话
    //     · 右上 → 状态 2，但发的是"搜索会话"（= 前端侧栏 `bhn1Oq_searchButton`）
    //     · 左下 → 状态 2（普通输入）；右下 → 参数弹窗
    //
    // 视觉：四个圆区与所有菜单面板都由 {@link ZoneLayerShader} 画 —— 与球、搜索胶囊
    // **同一套液态玻璃材质**（用户：颜色只是草图里用来区分的，实际全部要液态玻璃）。
    // ══════════════════════════════════════════════════════════════════════════

    // 2026-10-23：四区 → **六区围成一圈**。编号沿用老的四个（dispatch 不用改），
    // 另外两个"待定"占位圆放在正上、正下。
    static final int ZONE_TL = 0, ZONE_TR = 1, ZONE_BL = 2, ZONE_BR = 3,
                     ZONE_TOP = 4, ZONE_BOTTOM = 5;
    static final int ZONE_COUNT = 6;
    private static final int BAR_SEND = 0, BAR_SEARCH = 1;

    private BallVoice voice;
    private boolean zonesActive = false;
    private int zoneActive = -1;
    private BallZonesView zonesView;
    private WindowManager.LayoutParams zonesParams;
    private final float[] zoneCx = new float[ZONE_COUNT];
    private final float[] zoneCy = new float[ZONE_COUNT];
    private float zoneR = 1f;
    /** 六个圆所在的环半径（圆心到球心的距离）。 */
    private float zoneRing = 1f;

    /** 状态 2 这次打开是"发消息"还是"搜会话"（右上角圆区进来的就是后者）。 */
    private int barPurpose = BAR_SEND;
    /** 选中的会话（工作目录三级菜单 / 会话搜索）：下一次发送就发给它。 */
    private String targetSessionId = null, targetSessionTitle = null;
    /** 待发送附件（长按添进来的那个）。 */
    private File pendingAttach = null;
    private String pendingAttachName = null;

    // 玻璃菜单（工作目录 / 会话列表 / 参数）：面板由 GL 层画，文字排版在 menuView 里
    private BallGlassMenu menuView;
    private WindowManager.LayoutParams menuViewParams;
    private View menuOutside;
    private WindowManager.LayoutParams menuOutsideParams;
    private boolean menuShown = false;
    private long menuArmAt = 0;
    /** 当前菜单路径："" = 关着；dirs / dir:<path> / sessions:<dir> / settings / preset / model / effort / perm / search */
    private String menuPath = "";
    private String menuReturnPath = "";
    private float[] menuRect = null;          // x, y, w, h（屏幕像素）
    private boolean glassFullscreen = false;

    static boolean isVoiceListening() {
        FloatingBallService s = INSTANCE;
        return s != null && s.voice != null && s.voice.listening();
    }

    static boolean isZonesOpen() {
        FloatingBallService s = INSTANCE;
        return s != null && s.zonesActive;
    }

    // 语音识别完全交给输入法（电源键那条路走 VoiceMap → 输入法语音），
    // 所以球自己不再开麦：默认关，要旧行为就在设置里把 voice_auto 打开。
    private boolean voiceAutoEnabled() { return prefs().getBoolean("voice_auto", false); }

    /** 弹球就开始听（用户要的初始状态）。已经在听就什么都不做。 */
    private void armVoice(String why) {
        if (uiHidden) return;                     // 被藏起来了：不自作主张开麦
        if (!voiceAutoEnabled()) return;
        if (!siriEnabled) return;
        if (searchActive || zonesActive || approvalShown || menuShown) return;
        if (voice == null) voice = new BallVoice(this);
        if (voice.busy()) return;
        Log.i("FloatingBall", "voice: 开始听（" + why + "）");
        vibrate(18);
        voice.start(new BallVoice.Cb() {
            @Override public void onListening() { }

            @Override public void onLevel(float level) { }

            @Override public void onTranscribing() { }

            @Override public void onHeard(final String text, final boolean auto) {
                final String t = text == null ? "" : text.trim();
                Log.i("FloatingBall", "voice: 转写" + (auto ? "（话音刚落）" : "（点击打断）")
                        + " = 「" + t + "」");
                if (t.isEmpty()) {
                    // 没听出字：只提示，不重新开麦（语音只在球刚激活时听一次）
                    toast("没听清");
                    return;
                }
                if (auto) {
                    // 话音刚落 → 直接发到新会话
                    sendTextNewSession(t, null);
                } else if (searchActive && searchBar != null) {
                    // 框已经弹出来了（单击先弹框）：直接填字
                    searchBar.setText(t);
                    searchBar.focusInput();
                    Log.i("FloatingBall", "voice: 识别结果填进已打开的状态 2");
                } else {
                    enterSearchMode("语音打断", t, false, BAR_SEND);
                }
            }

            @Override public void onError(final String message) {
                Log.w("FloatingBall", "voice: " + message);
                toast(message);
            }
        });
    }

    // ───────── 电源键「按住说话」（识别完全交给输入法）─────────
    // 第一次长按：球出来 + 弹胶囊（聚焦、弹输入法）+ 给输入法发 down
    //             （VoiceMap 模块往输入法空格键注入长按，输入法自己开语音）；
    // 第二次长按：给输入法发 up → 输入法把识别结果打进胶囊 → 读出文字 →
    //             发回「上一次回话」那个会话（没有就新建一条并记住）。
    // 音量减：     只做「收麦 + 发送」，目标是**全新会话**；球没开就什么都不做（绝不呼球）。
    private static final String HOLD_CWD_DEFAULT =
            "/data/data/com.termux/files/home/日常使用";
    /** 上一次用球说话的那个会话（每次 askIn 都记一笔）；第二次长按电源键就回这里。 */
    private static final String PREF_LAST_SESSION = "last_session_id";

    /** 「按住说话」此刻是不是开着（PowerBallReceiver 据此决定这次是"开录"还是"收尾"）。 */
    static volatile boolean powerHoldActive = false;
    /** down 真的发出去了没有（第二次按得太快时别发一个孤零零的 up）。 */
    private volatile boolean powerHoldDown = false;
    /** 按住说话开始时的媒体音量：音量减键收尾后调回去（那一下按键不该留下音量副作用）。 */
    private int holdMusicVolume = -1;

    private VoiceMapControl voiceMap;

    private VoiceMapControl vm() {
        if (voiceMap == null) voiceMap = VoiceMapControl.get(this);
        return voiceMap;
    }

    /**
     * 音量减键的接收器 —— **动态注册**，只在服务活着的时候存在（manifest 里没有这条 action）。
     *
     * 为什么不写进 manifest：daemon 每按一次音量减都会发一条广播；manifest 收的话，
     * 球没开时这条广播会把整个 App 进程拉起来（冷启动 200~500ms）就为了判断一句
     * 「没在按住说话、什么都不做」。动态注册的话球没开就没人收，音量键完完全全是音量键。
     *
     * 语义只有一个：**正在按住说话** → 收麦 + 发到全新会话；其它时候一律忽略。
     */
    private BroadcastReceiver volumeSendReceiver;

    private void registerVolumeSendReceiver() {
        if (volumeSendReceiver != null) return;
        volumeSendReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i != null && PowerBallReceiver.ACTION_VOLUME_SHOT.equals(i.getAction())) {
                    Log.i("FloatingBall", "收到 VOLUME_SHOT（音量加双击）→ 取证快门");
                    witnessCaptureFromKey();
                    return;
                }
                if (i != null && PowerBallReceiver.ACTION_VOLUME_HIDE.equals(i.getAction())) {
                    Log.i("FloatingBall", "收到 VOLUME_HIDE → 隐藏全部界面");
                    applyUiHidden(true, "音量加");
                    return;
                }
                Log.i("FloatingBall", "收到 VOLUME_SEND：holdActive=" + powerHoldActive);
                if (!powerHoldActive) {
                    // 音量减只负责"收麦 + 发新会话"。取证快门已经挪到**大声量加双击**
                    // （见 VOLUME_SHOT）：放在音量减上会和"再多说一段"抢同一颗键。
                    Log.i("FloatingBall", "音量减：没在按住说话 → 忽略（取证 = 快速双击音量加）");
                    return;
                }
                Log.i("FloatingBall", "音量减 → 换行继续听（多轮）");
                vibrate(12);
                multiTurnDictation();
            }
        };
        try {
            IntentFilter f = new IntentFilter(PowerBallReceiver.ACTION_VOLUME_SEND);
            f.addAction(PowerBallReceiver.ACTION_VOLUME_HIDE);
            f.addAction(PowerBallReceiver.ACTION_VOLUME_SHOT);
            registerReceiver(volumeSendReceiver, f);
            Log.i("FloatingBall", "音量减广播接收器已注册");
        } catch (Throwable t) {
            Log.w("FloatingBall", "音量减广播接收器注册失败：" + t);
            volumeSendReceiver = null;
        }
    }

    private void unregisterVolumeSendReceiver() {
        if (volumeSendReceiver == null) return;
        try { unregisterReceiver(volumeSendReceiver); } catch (Throwable ignored) { }
        volumeSendReceiver = null;
    }

    // ───────── 音量加：隐藏 / 恢复全部悬浮界面（用户 2026-11-06 要的）─────────
    /**
     * true = 所有悬浮界面都被藏起来了（球、胶囊、对话卡、菜单、四区、玻璃）。
     * 恢复时机（用户定的语义）：
     *   · 弹审批 / 提问面板 → 自动恢复；
     *   · 按电源键 → 恢复；
     *   · 用别的方式打开 App（MainActivity）→ 恢复。
     */
    static volatile boolean uiHidden = false;

    static void setUiHidden(final boolean hide, final String why) {
        final FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() { s.applyUiHidden(hide, why); }
        });
    }

    static boolean isUiHidden() { return uiHidden; }

    private void applyUiHidden(boolean hide, String why) {
        if (uiHidden == hide) return;
        uiHidden = hide;
        Log.i("FloatingBall", "ui: " + (hide ? "隐藏全部界面" : "恢复界面") + "（" + why + "）");
        if (hide) {
            // 各模式先拆干净（不走动画：藏起来就是立刻消失）
            try { if (searchActive || searchClosing) dropSearchNow(); } catch (Throwable ignored) { }
            try { if (zonesActive) exitZones(); } catch (Throwable ignored) { }
            try { if (menuShown) hideMenu("音量加隐藏"); } catch (Throwable ignored) { }
            if (glView != null) glView.setVisibility(View.INVISIBLE);
            if (siriText != null) siriText.setVisibility(View.GONE);
            if (convoBar != null) convoBar.setVisibility(View.GONE);
        } else {
            if (glView != null) glView.setVisibility(View.VISIBLE);
            if (hasBallText()) showSiriTextNow();
            centerBallOnScreen();
            if (siriEnabled) measureSiriTargets(siriStr);
            syncComposerBar();            // 输入行跟着"玻璃回来了没有"自己决定露不露
        }
        applyBallTouchability();          // 藏着的时候代理层也别吃触摸
    }

    /**
     * 同一个「长按」可能被送进来两次：服务冷启动时直连 startForegroundService 被后台限制
     * 拦掉 → 700ms 后 root 跳板又起一次；电源键监听同时听到两个输入节点时也会连发两条广播。
     * 第二次要是被当成「用户又按了一下」，就会立刻 `up` —— 表现正是
     * 「语音刚开始没多久就自己结束、只说出了前面几个字」。所以这段时间内的重复 start 一律忽略。
     */
    private static final long HOLD_DUP_GUARD_MS = 2500;
    /** 本次「按住说话」真正开始的时刻（用来识别重复 start）。 */
    private long powerHoldStartedAt = 0;

    /** 电源键第一次长按：弹胶囊 + 让输入法开始语音识别。 */
    void beginPowerHold() {
        long now = android.os.SystemClock.uptimeMillis();
        if (searchActive && powerHoldActive) {
            if (now - powerHoldStartedAt < HOLD_DUP_GUARD_MS) {
                Log.i("FloatingBall", "电源键：重复的 start（" + (now - powerHoldStartedAt)
                        + "ms 内又来一次）→ 忽略，不结束语音");
                return;
            }
            endPowerHold(false, false);   // 正在语音 → 当第二次按下（收麦发送）
            return;
        }
        if (searchActive) {
            // ★ 2026-11-07：胶囊开着、但**没在语音**（只是打开了输入框 / 上一次刚被收尾）。
            //   以前这里直接 endPowerHold() → wasDown=false → 「手太快」→ 把胶囊关掉，
            //   用户看到的就是「按一下电源键，输入框没了，也没发出去」。
            //   正确的语义：这一下 = 开始按住说话（胶囊本来就在，不用重开）。
            Log.i("FloatingBall", "电源键：胶囊已开、没在语音 → 直接开始按住说话");
            powerHoldStartedAt = now;
            searchVoiceMode = true;
            if (searchBar != null) { try { searchBar.focusInput(); } catch (Throwable ignored) { } }
            startVoiceFocusGuard();          // 胶囊已开：输入框焦点同样要盯住
            try { vm().cmd("stealthon"); } catch (Throwable ignored) { }
            powerHoldActive = true;
            powerHoldDown = false;
            holdMusicVolume = musicVolume();
            vibrate(18);
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!powerHoldActive || !searchActive) return;
                    powerHoldDown = true;
                    startVoiceAliveWatch();     // ★ 时序：先挂环视器再发 down，别漏掉开头那段
                    vm().trigger("down");
                    Log.i("FloatingBall", "电源键：输入法语音 down（胶囊已开）");
                }
            }, 500);
            return;
        }
        powerHoldStartedAt = now;
        Log.i("FloatingBall", "电源键：弹胶囊 + 交给输入法语音");
        // ⚠ 按住说话：**只要焦点、不要键盘**。键盘由 Voicemap 自己以"隐身"（alpha=0）拉起来，
        //   我们自己 showSoftInput 的话会先弹出一个真的键盘、再被隐身 —— 用户看到的就是
        //   「每次长按进入语音识别时突然弹出键盘再隐藏」（2026-11-06 报的）。
        // ⚠ 这个标记要活到 sqToCapsule()（胶囊 250ms 后才落定，barAutoFocus 那一段在里面才跑），
        //   所以不能 try/finally 立刻复位 —— 由 closeSearchMode() / 点输入框 负责清。
        searchVoiceMode = true;
        enterSearchMode("电源键·按住说话", "", true, BAR_SEND);
        if (!searchActive) {
            // 审批球 / 菜单 / 玻璃全屏在屏上时 enterSearchMode 会直接返回；
            // 这时候千万不能再往输入法里注入空格长按 —— 那会点到别人的界面上。
            Log.w("FloatingBall", "电源键：胶囊没开（别的面板占着），本次不触发语音");
            searchVoiceMode = false;
            return;
        }
        // 尽早让键盘隐身：如果上一个 App 的键盘还开着（alpha=1），等到 900ms 后 `down` 才隐身
        // 就会看见它"亮着→变透明"一下。这里胶囊一开就先把隐身挂上。
        try { vm().cmd("stealthon"); } catch (Throwable ignored) { }
        powerHoldActive = true;
        powerHoldDown = false;
        holdMusicVolume = musicVolume();
        vibrate(18);
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!powerHoldActive || !searchActive) return;
                powerHoldDown = true;
                startVoiceAliveWatch();     // ★ 同上：环视器先挂上
                vm().trigger("down");
                Log.i("FloatingBall", "电源键：输入法语音 down");
            }
        }, 900);
    }

    // ───────── 按住说话期间「输入法自己收尾了 → 自动续按」─────────
    // 用户的语义：**只有我按音量减 / 再长按电源键 / 点输入框，语音才结束**。
    // 但输入法引擎自己有"长时间没听到人声就收尾"的保护（实测按住不说话 12~17 秒，
    // 搜狗会自己 stop 并把字上屏）。一收尾，我们这边还按着也没用 —— 后面说的话全丢。
    // 所以按住期间盯一下系统里"还有没有正在录音的客户端"：连续两次（≈2.4s）都没有，
    // 就再发一次 `down` 让输入法接着听，直到用户真的按结束为止。
    private int voiceDeadTicks = 0;
    /** 上一次判定「录音没了」的时候，输入框里已经是多少字（用来区分"这一轮说完了"和"被抢麦"）。 */
    private int voiceDeadBoxLen = -1;
    private long voiceReholdAt = 0;
    private long voiceHoldStartedAt = 0;
    /**
     * 自动续按的总时长上限：到点就不再续（和模块那边 180s 的看门狗对齐）。
     *
     * ⚠ 这是**连续按住**的上限，不是会话时长。多轮模式下 {@link #multiTurnMark()}
     * 每上屏一轮就清零重算，所以「换行继续」可以一直说下去，不会被它截断。
     */
    private static final long VOICE_REHOLD_MAX_MS = 175000;
    /**
     * 播放器抢麦造成的闪断（媒体音 / 来电 / 别的 App 短暂占麦）：只消抖 2.4s，不加 4s 冷却，
     * 否则 TTS 播报读完那一下要白丢 4 秒的字。
     */
    private static final long VOICE_REHOLD_MEDIA_MS = 1500;
    /** 这一轮「按住说话」到现在一共上屏了多少字（多轮模式下逐轮累加，用来判定是不是新的一轮）。 */
    private volatile int powerHoldTyped = 0;

    private void startVoiceAliveWatch() {
        voiceDeadTicks = 0;
        voiceDeadBoxLen = -1;
        powerHoldTyped = 0;
        voiceHoldStartedAt = android.os.SystemClock.uptimeMillis();
        voiceReholdAt = voiceHoldStartedAt + 3500;   // 开头 3.5s 不判（输入法还在起录音）
        main.removeCallbacks(voiceAliveWatch);
        main.postDelayed(voiceAliveWatch, 1200);
    }

    /**
     * 多轮模式（电源键第二轮起：输入法把上一轮的字上屏了、但用户还没按结束）：
     * 把续按时长重置成"又从头开始计"，这样一直说下去不会被 {@link #VOICE_REHOLD_MAX_MS} 截断。
     *
     * 为什么不直接把这个上限调大：上限存在的意义是"UP 丢了也终究要停"，所以它必须有限；
     * 但它的计时单位应该是**连续这一轮**，而不是整个会话 —— 用户每换一行就是新的一轮。
     */
    private void multiTurnMark() {
        long now = android.os.SystemClock.uptimeMillis();
        voiceHoldStartedAt = now;
        voiceDeadTicks = 0;
        voiceReholdAt = now + 1200;      // 换行之后模块那边已经重新按下了，2s 内就恢复监测
        Log.i("FloatingBall", "语音：多轮模式，换成新的一轮继续听（已累计 " + powerHoldTyped + " 字）");
    }

    private void stopVoiceAliveWatch() {
        main.removeCallbacks(voiceAliveWatch);
        voiceDeadTicks = 0;
        voiceDeadBoxLen = -1;
    }

    /**
     * 单击球时，**兼容期**把老的系统识别（`BallVoice` → `AndroidAsr`）收干净。
     *
     * 2026-11-07 之前弹球会自动开麦，引擎是系统 SpeechRecognizer（本机实际落到
     * 小米 mibrain 那套 ASR `com.xiaomi.mibrain.speech`）。它和应用内长按空格走的
     * 输入法语音不是同一个引擎，识别率 / "能说多长"都差一截 —— 用户报的
     * 「没有手机输入法准确、识别长度也没那么长」就是这个。
     * 现在单击统一走输入法；设置里 voice_auto 打开（老行为）时仍会先开麦，
     * 这里负责在单击那一刻把它停掉，免得两条引擎同时抢麦克风。
     */
    private void gateOldAsrOnTap() {
        if (voice == null) return;
        if (!voice.busy()) return;
        Log.i("FloatingBall", "voice: 单击 → 收起旧的系统识别，改用输入法语音");
        try { voice.cancel(); } catch (Throwable ignored) { }
        voice = null;
    }

    /** 「新一轮第一句」的判定：输入框里此刻的内容就是这一轮上屏的字数。 */
    private int boxLen() {
        try {
            String t = searchBar == null ? "" : searchBar.text();
            return t == null ? 0 : t.trim().length();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 音量减：**换行，接着说**（多轮）。
     *
     * 用户要的语义（原话）：输入法长按空格能「输入好久」，一轮一轮说下去。
     * 所以：
     *   · 输入法这一轮的终稿上屏（up → 字停稳）之后，**不发送、不关胶囊**；
     *   · 框里已有的字（含全部分行）原样保留，交给 DSH 用；
     *   · 给输入法补一个回车换行，再补一条 down —— 输入法在同一个输入框里开**新的一轮**，
     *     上一轮的字不会被清掉（这也正是手打「换行 → 长按空格再说」的等价动作）；
     *   · 环视器（voiceAliveWatch）在这之后继续盯着，播放器抢麦 / 输入法自己收尾都会立刻补按。
     * 结束由**电源键第二次长按**负责（那一轮才把全文一次性发出去）。
     */
    private void multiTurnDictation() {
        if (!powerHoldActive || !searchActive) {
            Log.i("FloatingBall", "音量减：不在按住说话里 → 不换行");
            return;
        }
        final int before = boxLen();
        powerHoldTyped = before;
        voiceDeadBoxLen = before;
        multiTurnMark();
        // ① up：让输入法把这一轮的终稿真正写进框里（流式中间结果才算落定）
        try { vm().trigger("up"); } catch (Throwable ignored) { }
        // ② 等字停稳，再换行 + 重新按下
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!powerHoldActive || !searchActive) return;
                powerHoldTyped = Math.max(powerHoldTyped, boxLen());
                try {
                    if (searchBar != null) searchBar.editNewline();
                } catch (Throwable ignored) { }
                powerHoldDown = true;
                try { vm().trigger("down"); } catch (Throwable ignored) { }
                multiTurnMark();
                Log.i("FloatingBall", "语音：换行完成（框里 " + boxLen() + " 字）→ 新一轮已经按下，继续听");
                toast("继续，说完按电源键");
            }
        }, IME_SETTLE_MS);
    }

    private final Runnable voiceAliveWatch = new Runnable() {
        @Override public void run() {
            if (!powerHoldActive || !powerHoldDown) return;
            long now = android.os.SystemClock.uptimeMillis();
            boolean recording = false;
            try {
                android.media.AudioManager am =
                        (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    java.util.List<android.media.AudioRecordingConfiguration> l =
                            am.getActiveRecordingConfigurations();
                    recording = l != null && !l.isEmpty();
                }
            } catch (Throwable ignored) { }
            if (recording) {
                if (voiceDeadTicks > 0) Log.i("FloatingBall", "语音：录音又回来了");
                voiceDeadTicks = 0;
            } else if (now >= voiceReholdAt) {
                if (++voiceDeadTicks >= 2) {
                    voiceDeadTicks = 0;
                    // ★ 2026-11-07：**框里已经有字就别再按下去**。
                    //   搜狗自己收尾时是"stop + 把识别结果上屏"；这时候再补一条 down 会开一次
                    //   **新的**语音会话，输入法通常会把刚上屏的那句清掉重来 —— 用户看到的是
                    //   「字识别出来了，但按结束时框是空的 → 没听清 → 发送不成功」。
                    //
                    // ★ 2026-11-07（二次修正）：但"有字"要分成两种情况，混在一起处理正是
                    //   「说一会儿就断、只识别到前半句」的另一半成因：
                    //     · 框里的字数跟上次判定时**一样** → 输入法把这一轮说完了，上屏了，
                    //       等用户继续（或者等用户按结束）。这时不能按，按了会清掉。
                    //     · 框里的字数**变了**（说明这一轮已经上屏完了、我在接着听下一轮）
                    //       或者框里**根本没变长**却被停麦 → 这多半是**播放器抢麦**
                    //       （DSH 的 TTS 朗读回复 / 来电 / 别的 App 录音），属于闪断，
                    //       必须马上补按，否则朗读那几秒说的话全丢。
                    String box = "";
                    try { box = searchBar == null ? "" : searchBar.text(); } catch (Throwable ignored) { }
                    int len = box == null ? 0 : box.trim().length();
                    if (len > 0 && len == voiceDeadBoxLen) {
                        Log.i("FloatingBall", "语音：输入法收尾了但已经上屏 " + len
                                + " 字 → 不续按（保住已识别的文字）");
                        main.postDelayed(this, 1200);
                        return;
                    }
                    boolean mediaCut = voiceDeadBoxLen > 0 && len > 0 && len != voiceDeadBoxLen;
                    long sinceStart = now - voiceHoldStartedAt;
                    if (!mediaCut && sinceStart > VOICE_REHOLD_MAX_MS) {
                        Log.w("FloatingBall", "语音：已经续按到 " + (VOICE_REHOLD_MAX_MS / 1000)
                                + "s 上限 → 不再续，等用户按结束");
                        return;
                    }
                    voiceDeadBoxLen = len;
                    // 闪断给 1.5s 消抖；真·收尾给 4s（别连环按，把输入法刚起的录音打断）
                    voiceReholdAt = now + (mediaCut ? VOICE_REHOLD_MEDIA_MS : 4000);
                    Log.i("FloatingBall", "语音：" + (mediaCut
                                    ? "录音被抢走（播放器/来电，框里 " + len + " 字）→ 马上补按，接着听"
                                    : "输入法自己把录音停了（还没出字）→ 自动续按，继续听")
                            + "；本轮已 " + (sinceStart / 1000) + "s");
                    try { vm().trigger("down"); } catch (Throwable ignored) { }
                }
            }
            main.postDelayed(this, 1200);
        }
    };

    /**
     * 收尾：让输入法把字吐出来，然后发出去。
     *
     * @param toNewSession true  = 发到**全新会话**（音量减键）；
     *                     false = 发回**上一次回话**那个会话（第二次长按电源键）。
     * @param fromVolume   这一次是不是音量减键按的（它顺带把那格音量按下去了，要调回来）。
     */
    /**
     * 点输入框 = 结束语音识别（**不发送**）+ 立刻把键盘显出来。
     *
     * Voicemap 那边 `up` 之后默认是"继续隐身 4 秒再把键盘收掉"，这里直接补一条
     * `stealthoff`，让输入法窗口立刻显形 —— 用户点一下就能看到键盘、接着打字。
     */
    private void endVoiceByTap() {
        boolean wasHolding = powerHoldActive;
        if (wasHolding) {
            powerHoldActive = false;
            powerHoldDown = false;
            stopVoiceAliveWatch();
            try { vm().trigger("up"); } catch (Throwable ignored) { }
        }
        try { vm().cmd("stealthoff"); } catch (Throwable ignored) { }
        if (searchBar != null) {
            try { searchBar.input().setShowSoftInputOnFocus(true); } catch (Throwable ignored) { }
        }
        searchVoiceMode = false;            // 接下来是打字，不是语音了
        stopVoiceFocusGuard();              // 手点输入框那条路：焦点自己会回来，看守撤掉
        Log.i("FloatingBall", "search: 点输入框 → 结束语音识别（不发送，wasHolding=" + wasHolding + "）");
    }

    void endPowerHold(boolean toNewSession, boolean fromVolume) {
        // ★ 2026-11-06：第二次按得太快（输入法语音还没开起来，powerHoldDown=false）时
        //   不要再「直接收起」——那会在胶囊还在弹开的半路上把它收回去，屏幕上是半截怪形状
        //   （用户：「连按两下，输入框显示有问题，再试一次又好了」）。
        //   现在改成等 down 真正发出去（最多 1.2s）再按正常流程收尾。
        if (!powerHoldDown && powerHoldActive) {
            Log.i("FloatingBall", "电源键：第二次按得太快 → 等语音真正开起来再收尾");
            final boolean toNew = toNewSession;
            final boolean fromVol = fromVolume;
            final int[] tries = {0};
            final Runnable[] wait = new Runnable[1];
            wait[0] = new Runnable() {
                @Override public void run() {
                    if (!powerHoldActive) return;                 // 中途被别的东西收掉了
                    if (powerHoldDown) { endPowerHold(toNew, fromVol); return; }
                    if (++tries[0] > 14) {                        // ~1.4s 还没开起来：认了，收起
                        powerHoldActive = false;
                        powerHoldDown = false;
                        Log.i("FloatingBall", "电源键：等不到语音开起来 → 收起胶囊");
                        closeSearchMode("按住说话取消");
                        return;
                    }
                    main.postDelayed(wait[0], 100);
                }
            };
            main.postDelayed(wait[0], 100);
            return;
        }
        final boolean wasDown = powerHoldDown;
        powerHoldActive = false;
        powerHoldDown = false;
        stopVoiceAliveWatch();
        restoreMusicVolumeIfFromVolumeKey(fromVolume);
        if (!wasDown) {
            Log.i("FloatingBall", "电源键：手太快，语音还没开 → 直接收起");
            closeSearchMode("按住说话取消");
            return;
        }
        vm().trigger("up");
        Log.i("FloatingBall", (toNewSession ? "音量减键" : "电源键") + "：输入法语音 up → 等出字");
        waitImeTextAndSend(0, toNewSession);
    }

    /**
     * 输入法出字是**流式**的：按住说话的整个过程里它一直在往输入框里写"中间结果"，
     * `up` 之后终稿还会再来一下（有时是把整句重写一遍），延迟几百毫秒到 1 秒多。
     *
     * 旧实现里"输入框里只要有字就立刻发出去"——于是在你说到一半时框里已经有前半句了，
     * 一 `up` 就 200ms 后把**中间结果**发走、胶囊关掉，终稿到达时已经没有输入框可写。
     * 用户看到的就是「说很长的话只识别到前面的」。
     *
     * 现在改成**等字停稳**再取：连续 {@link #IME_SETTLE_MS} 没有新字才算说完；
     * 最多等 {@link #IME_MAX_WAIT_MS}（防止输入法一直不吐终稿时卡住）。
     */
    private static final long IME_SETTLE_MS = 900;      // 字连续这么久没变 = 出完了
    private static final long IME_FIRST_WAIT_MS = 350;  // up 之后先给输入法一点时间出终稿
    private static final long IME_MAX_WAIT_MS = 12000;  // 兜底：最多等这么久
    private String imeLastText = "";
    /** 这一轮里**见过的最长/最后一个非空**结果（框被输入法清空时的兜底）。 */
    private String imeBestText = "";
    private long imeLastChangeAt = 0;
    private long imeWaitStartedAt = 0;
    private long imeWaitGen = 0;

    private void waitImeTextAndSend(final int attempt, final boolean toNewSession) {
        if (attempt == 0) {
            imeWaitStartedAt = android.os.SystemClock.uptimeMillis();
            imeLastText = "";
            imeBestText = "";
            imeLastChangeAt = imeWaitStartedAt;
            imeWaitGen++;
            Log.i("FloatingBall", "电源键：等输入法出终稿（字停稳 " + IME_SETTLE_MS + "ms 才取）");
        }
        final long gen = imeWaitGen;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != imeWaitGen) return;                  // 这一轮已经被取消/重开
                long now = android.os.SystemClock.uptimeMillis();
                String t = searchBar == null ? "" : searchBar.text();
                t = t == null ? "" : t.trim();
                if (!t.isEmpty()) imeBestText = t;              // ★ 记住见过的非空结果
                if (!t.equals(imeLastText)) {                   // 还在出字 → 静默计时重来
                    if (!t.isEmpty()) {
                        Log.i("FloatingBall", "电源键：输入法还在出字（" + t.length() + " 字）");
                    }
                    imeLastText = t;
                    imeLastChangeAt = now;
                }
                long quiet = now - imeLastChangeAt;
                long total = now - imeWaitStartedAt;
                boolean settled = !t.isEmpty() && quiet >= IME_SETTLE_MS;
                if (!settled && total < IME_MAX_WAIT_MS) {
                    waitImeTextAndSend(attempt + 1, toNewSession);
                    return;
                }
                if (t.isEmpty()) {
                    // ★ 2026-11-07：框被输入法自己清空了，但这一轮**明明已经出过字** ——
                    //   用那句发出去，别把用户刚说的话丢掉（用户报的「识别到了但发送不成功」）。
                    if (!imeBestText.isEmpty()) {
                        Log.w("FloatingBall", "电源键：末次读到空框，回退到本轮已识别的「"
                                + imeBestText + "」");
                        t = imeBestText;
                    } else {
                        Log.w("FloatingBall", "电源键：等不到输入法出字（" + total + "ms）");
                        toast("没听清");
                        return;
                    }
                }
                Log.i("FloatingBall", "电源键：输入法结果 = 「" + t + "」（静默 " + quiet
                        + "ms / 共 " + total + "ms）");
                powerHoldTyped = t.length();
                voiceDeadBoxLen = t.length();
                sendTextToHoldSession(t, toNewSession);
                powerHoldActive = false;          // 这一轮真的结束了：别再补按
                stopVoiceAliveWatch();
                powerHoldTyped = 0;
                try { if (searchBar != null) searchBar.setText(""); } catch (Throwable ignored) { }
                closeSearchMode("电源键说话完成");
            }
        }, attempt == 0 ? IME_FIRST_WAIT_MS : 150);
    }

    /**
     * 状态 2 胶囊里**用户已经打好、等着发**的字（空串 = 没有）。
     *
     * 为什么需要它：以前电源键 / 音量减键只会「开麦 / 收麦」，压根不看输入框里有没有字。
     * 用户打完字按电源键，走的是 `start`（又开了一次语音，框里的字被盖掉）；
     * 按音量减键更直接 —— `没在按住说话 → 不做事`。两条都**发不出去**，
     * 表现就是「字打好了，按哪个键都没反应，也一直没回复」。
     */
    String pendingComposerText() {
        if (powerHoldActive) return "";
        try {
            if (searchActive) {
                String t = searchBar == null ? "" : searchBar.text();
                return t == null ? "" : t.trim();
            }
            // 会话面上那条输入行（回话卡 / AI 回复页共用）同样算「打好的字」。
            if (convoBar != null && convoBar.isShown()) {
                String t = convoBar.text();
                return t == null ? "" : t.trim();
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** 广播接收器（同进程）问一句：现在按键该不该走「发输入框里的字」。 */
    static boolean hasComposerTextForKeys() {
        FloatingBallService s = INSTANCE;
        return s != null && s.composerHasText;
    }

    /**
     * 输入框此刻是不是**开着**（胶囊 / 对话卡输入行）。
     *
     * 接收器用它决定电源键是「发送」还是「开麦」：开着就交给服务在 UI 线程
     * 用最新状态判断（框里有没有字），避免广播线程读到 40ms 前的旧标志 ——
     * 那个竞态会让"刚说完话、字刚上屏就按电源键"落到"手太快 → 关胶囊"，
     * 字被丢掉（用户报的「语音输入你好后按电源键没有任何回应」）。
     */
    static boolean isComposerOpen() {
        FloatingBallService s = INSTANCE;
        return s != null && s.composerOpen;
    }

    /**
     * 主线程每拍刷新一次「输入框开没开 / 有没有字」。
     * 广播是后台线程进来的，不能碰 View —— 这里在 UI 线程算好，接收器只读 volatile。
     */
    private volatile boolean composerHasText = false;
    private volatile boolean composerOpen = false;

    private void refreshComposerHasText() {
        boolean open = false, has = false;
        try {
            open = searchActive || (convoBar != null && convoBar.isShown());
            has = open && !powerHoldActive && !pendingComposerText().isEmpty();
        } catch (Throwable ignored) { }
        composerOpen = open;
        composerHasText = has;
    }

    /**
     * 把胶囊里打好的字发出去（电源键 = 上一次回话 / 音量减键 = 全新会话）。
     * 发完把胶囊收掉，回答照常流回球里。
     */
    private void sendComposerByKey(final boolean toNewSession) {
        sendComposerByKey(toNewSession, false);
    }

    /**
     * @param fallbackVoice 框里没字时要不要改走「开始按住说话」（电源键 = true，音量减键 = false）。
     *                      这样"字刚上屏就按键"的竞态不会落进「手太快 → 关胶囊 → 字没了」。
     */
    private void sendComposerByKey(final boolean toNewSession, final boolean fallbackVoice) {
        final String text = pendingComposerText();
        if (text.isEmpty()) {
            if (fallbackVoice && !powerHoldActive) {
                Log.i("FloatingBall", "输入框发送：框里没字 → 电源键改走按住说话");
                beginPowerHold();
            } else {
                Log.i("FloatingBall", "输入框发送：框里没字 → 忽略");
            }
            return;
        }
        // ① 会话面那条输入行（回话卡 / AI 回复页共用）：发给它对着的那条会话
        if (!searchActive && composerShown && convoBar != null) {
            Log.i("FloatingBall", "输入框发送（会话面 " + composerTargetSession() + "）：「" + text + "」");
            try { if (convoBar != null) convoBar.setText(""); } catch (Throwable ignored) { }
            composerSubmit(text);
            return;
        }
        // ② 状态 2 胶囊：按 pk_send 决定「上一次回话 / 全新会话」
        if (!searchActive) {
            Log.i("FloatingBall", "输入框发送：没有可用的输入框 → 忽略");
            return;
        }
        Log.i("FloatingBall", "输入框发送（" + (toNewSession ? "全新会话" : "上一次回话")
                + "）：「" + text + "」");
        try { if (searchBar != null) searchBar.setText(""); } catch (Throwable ignored) { }
        closeSearchMode("按键发送输入框内容");
        sendTextToHoldSession(text, toNewSession);
    }

    /**
     * 「按住说话」的发送目标。
     *
     *  · toNewSession=false（第二次长按电源键）→ **上一次回话**那个会话：
     *    记住的 id 还在就续上；会话被删了 / 从没记过就退回 hold_cwd 下最新的那条；
     *    连一条都没有才新建。
     *  · toNewSession=true（音量减键）→ 不管上次是谁，直接在 hold_cwd 里开一条新的。
     *
     * 新建出来的会话由 {@link #askIn} 记成「上一次回话」，所以音量减键开的那条，
     * 下一次长按电源键就接着它。
     */
    private void sendTextToHoldSession(final String text, final boolean toNewSession) {
        final String cwd = holdCwd();
        final String remembered = toNewSession ? "" : lastTalkSession();
        if (toNewSession) {
            Log.i("FloatingBall", "发送目标=新会话（cwd=" + cwd + "，插话模式）");
            sendTextNewSessionTo(cwd, text, null, SiriAssistant.Transport.MODE_STEER);
            return;
        }
        if (remembered.isEmpty()) {
            Log.i("FloatingBall", "发送目标=上一个对话，但还没有记录 → 新建一条（cwd=" + cwd + "）");
            sendTextNewSessionTo(cwd, text, null, SiriAssistant.Transport.MODE_STEER);
            return;
        }
        Log.i("FloatingBall", "发送目标=上一个对话 → " + remembered);
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                String target = remembered;
                try {
                    if (!api.sessionExists(target)) {
                        target = newestSessionIn(cwd);
                        Log.i("FloatingBall", "电源键：之前的会话不在了 → 退回 "
                                + (target.isEmpty() ? "新建" : target));
                    }
                } catch (Throwable t) {
                    // 查不了（DSH 刚起 / 网络抖）就当作还在，直接发过去 —— 别为了一次查询失败
                    // 就把用户的上下文拆成新会话。
                    Log.w("FloatingBall", "电源键：查上一次回话失败（" + message(t) + "）→ 直接发");
                }
                final String sid = target;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (sid == null || sid.isEmpty())
                            sendTextNewSessionTo(cwd, text, null, SiriAssistant.Transport.MODE_STEER);
                        else sendTextToSession(sid, text, null, null, SiriAssistant.Transport.MODE_STEER);
                    }
                });
            }
        }, "dsh-hold-target").start();
    }

    /** hold_cwd 下最新的会话 id（没有就空串）。 */
    private String newestSessionIn(String cwd) {
        try {
            List<DshApi.SessionRow> rows = DshApi.get(this).sessionsIn(cwd);
            return rows.isEmpty() ? "" : rows.get(0).id;
        } catch (Throwable t) {
            Log.w("FloatingBall", "电源键：列 " + cwd + " 的会话失败 " + message(t));
            return "";
        }
    }

    private String holdCwd() {
        String cwd = prefs().getString("hold_cwd", HOLD_CWD_DEFAULT);
        if (cwd == null || cwd.trim().isEmpty()) cwd = HOLD_CWD_DEFAULT;
        return cwd.trim();
    }

    /** 「上一次回话」的会话 id（空串 = 还没说过话）。 */
    private String lastTalkSession() {
        String sid = prefs().getString(PREF_LAST_SESSION, "");
        return sid == null ? "" : sid.trim();
    }

    /** 每发一次话就记一笔：电源键第二次长按要回到这里，而不是另起一条。 */
    private void rememberTalkSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        prefs().edit().putString(PREF_LAST_SESSION, sessionId.trim()).apply();
    }

    private int musicVolume() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            return am == null ? -1 : am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 音量减键会先把媒体音量按下去一格；收尾后调回按住说话时的值。 */
    private void restoreMusicVolumeIfFromVolumeKey(boolean fromVolume) {
        if (!fromVolume) return;
        final int baseline = holdMusicVolume;
        if (baseline < 0) return;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.media.AudioManager am =
                            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am == null) return;
                    int now = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    if (now >= baseline) return;
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, baseline, 0);
                    Log.i("FloatingBall", "音量减键：音量 " + now + " → " + baseline + "（收尾后调回）");
                } catch (Throwable t) {
                    Log.w("FloatingBall", "音量减键：调回音量失败 " + message(t));
                }
            }
        }, 250);
    }

    private void toast(final String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    private static String message(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.toString() : m;
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /**
     * 发送目标（一条设置，三处共用）：
     *   false = 上一个对话（当前会话）  true = 新会话
     * 共用方：① 胶囊输入框回车 ② 电源键 ③ 音量减键（见 pk_send="auto"）。
     */
    boolean sendToNewSession() { return prefs().getBoolean("send_new_session", false); }

    void setSendNewSession(boolean v) {
        prefs().edit().putBoolean("send_new_session", v).apply();
        if (searchBar != null) searchBar.setTargetNewSession(v);
        Log.i("FloatingBall", "发送目标 → " + (v ? "新会话" : "上一个对话（当前会话）"));
    }

    /** 状态 2 的发送目标：有选中会话就发给它，否则按「发送目标」设置发。 */
    private void sendFromBar(final String text, final File attach, final String attachName) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() && attach == null) return;
        if (targetSessionId != null) {
            // 三态里选过某条会话 → 就发给它（明确选择优先于默认设置）
            sendTextToSession(targetSessionId, t, attach, attachName);
            return;
        }
        if (attach == null) {
            // 和电源键同一条路：按「发送目标」发到上一个对话 / 新会话
            sendTextToHoldSession(t, sendToNewSession());
            return;
        }
        sendTextNewSession(t, attach);   // 带附件仍走新会话（hold 那条路不带附件）
    }

    private void sendTextNewSession(final String text, final File attach) {
        sendTextNewSessionTo(prefs().getString("send_cwd", ""), text, attach);
    }

    /** 同 {@link #sendTextNewSession}，但工作目录由调用方指定（按住说话固定发「日常使用」）。 */
    private void sendTextNewSessionTo(final String cwdIn, final String text, final File attach) {
        sendTextNewSessionTo(cwdIn, text, attach, SiriAssistant.Transport.MODE_QUEUE);
    }

    /**
     * 同 {@link #sendTextNewSessionTo(String, String, File)}，但可以指定投递方式。
     *
     * `steer` = 插话：DSH 那边不是排到下一条 turn，而是在**当前这一轮的下一个 step**
     * 把这句话塞进去（`agent.steer()`）。新会话本来就是空闲的，两种方式等价；
     * 这个参数是为了让「按住说话 → 上一次回话」那条路也能插话 —— 那正是它最常见的场景
     * （球里正在出字，人又想补一句）。
     */
    private void sendTextNewSessionTo(final String cwdIn, final String text, final File attach,
                                      final String mode) {
        final String attachName = pendingAttachName;
        final String cwd = cwdIn == null ? "" : cwdIn;
        final String preset = prefs().getString("send_preset", "");
        final String provider = prefs().getString("send_provider", "");
        final String model = prefs().getString("send_model", "");
        final String effort = prefs().getString("send_effort", "");
        final String perm = prefs().getString("send_perm", "");
        final DshApi api = DshApi.get(this);
        Log.i("FloatingBall", "send: 新会话 cwd=" + cwd + " preset=" + preset
                + " model=" + model + "/" + effort + " perm=" + perm + " 附件="
                + (attach == null ? "无" : attach.getName()));
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String sid = api.createSession(cwd, preset);
                    if (!model.isEmpty() && !provider.isEmpty()) {
                        api.selectModel(sid, provider, model, effort);
                    }
                    if (!perm.isEmpty()) api.setPermission(sid, perm);
                    List<String> receipts = new ArrayList<>();
                    if (attach != null && attach.exists()) {
                        String rid = api.upload(sid, attachName == null ? attach.getName() : attachName,
                                readAll(attach));
                        receipts.add(rid);
                    }
                    final String fid = sid;
                    main.post(new Runnable() {
                        @Override public void run() {
                            askIn(fid, text, receipts, mode);
                        }
                    });
                } catch (Throwable t) {
                    final String m = message(t);
                    Log.w("FloatingBall", "send: 新会话失败 " + m);
                    main.post(new Runnable() {
                        @Override public void run() { toast("发送失败：" + m); }
                    });
                }
            }
        }, "dsh-send-new").start();
    }

    private void sendTextToSession(final String sid, final String text,
                                   final File attach, final String attachName) {
        sendTextToSession(sid, text, attach, attachName, SiriAssistant.Transport.MODE_QUEUE);
    }

    /** 同 {@link #sendTextToSession(String, String, File, String)}，但可以指定投递方式（queue / steer）。 */
    private void sendTextToSession(final String sid, final String text,
                                   final File attach, final String attachName,
                                   final String mode) {
        final DshApi api = DshApi.get(this);
        Log.i("FloatingBall", "send: 发到已有会话 " + sid + "（" + targetSessionTitle + "）");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> receipts = new ArrayList<>();
                    if (attach != null && attach.exists()) {
                        receipts.add(api.upload(sid, attachName == null ? attach.getName() : attachName,
                                readAll(attach)));
                    }
                    main.post(new Runnable() {
                        @Override public void run() { askIn(sid, text, receipts, mode); }
                    });
                } catch (Throwable t) {
                    final String m = message(t);
                    main.post(new Runnable() {
                        @Override public void run() { toast("发送失败：" + m); }
                    });
                }
            }
        }, "dsh-send-session").start();
    }

    /** 把这一句交给助手：流式回答照旧逐字写进球里。 */
    private void askIn(final String sessionId, final String text, final List<String> receipts) {
        askIn(sessionId, text, receipts, SiriAssistant.Transport.MODE_QUEUE);
    }

    /**
     * 同 {@link #askIn(String, String, List)}，但显式指定投递方式。
     * 长按电源键 / 音量减键的语音发送走 {@link SiriAssistant.Transport#MODE_STEER}（插话），
     * 打字发送仍是 {@link SiriAssistant.Transport#MODE_QUEUE}（排队）。
     */
    private void askIn(final String sessionId, final String text, final List<String> receipts,
                       final String mode) {
        SiriAssistant a = SiriAssistant.get(this);
        if (!a.enabled()) {
            toast("DSH 助手没开");
            return;
        }
        // 发到哪儿，哪儿就是「上一次回话」：电源键第二次长按下次还回这里。
        rememberTalkSession(sessionId);
        ensureSiriForAssistant();
        a.askIn(sessionId, text, receipts, mode);
    }

    // ── 状态 3：四个液玻璃圆区 ───────────────────────────────────────────────

    /** 按住球 480ms → 四个圆区出现（不松手）。松手不在区里就回初始状态。 */
    void enterZones(String why) {
        if (zonesActive || searchActive || approvalShown || menuShown) return;
        if (wm == null || glView == null || renderer == null || params == null) return;
        if (!siriEnabled) applySiriEnabled(true);
        if (!siriEnabled) return;
        if (voice != null) voice.cancel();               // 长按进状态 3：语音不转了
        zonesActive = true;
        zoneActive = -1;
        holdLevel = 0;
        holdZoneHover = -1;
        holdFocus = -1;
        swipeAnchorX = 0;
        holdWinH0 = 0;                       // 每次进状态 3 重新记一次基准高度
        holdItems.clear();
        holdDir = null;
        prefetchZonesData();
        forceHideAllText();                              // 球内文字先藏（和搜索层一致）
        ensureZoneBaseWindow();                          // ★ 第一帧就把窗口定成原版球窗（793×579），此后整个状态 3 不再改窗口
        computeZones();
        // 六区的**玻璃圆仍由 GL 画**（用户：玻璃要通透），**图标由文字层画**。
        // 关键：窗口不放大（见 computeZones）→ GL 的 surface 不失配（k=1）→ 两者同心。
        // 圆只在"某个区被指到"时才显示（见 pushZoneScene 的 enabled 逻辑）。
        renderer.zoneMode = true;
        if (glView != null) glView.setAlpha(1f);
        renderer.sHlAmt = 1.5f;
        renderer.sAb = 0.12f;
        renderer.sDensity = sqDensity();
        renderer.zCont = 0.80f;      // 和球同款"上黑下透"（球的黑顶就是这条曲线）
        renderer.zRefract = 16f;
        pushZoneScene();
        Log.i("FloatingBall", "zones: glass alpha=" + (glView == null ? -1 : glView.getAlpha())
                + " zoneMode=" + renderer.zoneMode + " win=" + winW + "x" + winH
                + " z0=(" + Math.round(zoneCx[0]) + "," + Math.round(zoneCy[0]) + ")");
        showZonesOverlay();
        applyBallTouchability();
        vibrate(22);
        Log.i("FloatingBall", "zones: 几何 i0=(" + Math.round(zoneCx[0]) + "," + Math.round(zoneCy[0])
                + ") i4=(" + Math.round(zoneCx[4]) + "," + Math.round(zoneCy[4])
                + ") i2=(" + Math.round(zoneCx[2]) + "," + Math.round(zoneCy[2]) + ") ring=" + Math.round(zoneRing));
        Log.i("FloatingBall", "zones: 进入状态 3（" + why + "）R=" + Math.round(zoneR)
                + " 中心=(" + Math.round(zoneCx[0] + zoneR * 1.15f) + ","
                + Math.round(zoneCy[0] + zoneR * 1.15f) + ") 请滑到某个圆区");
    }

    private void computeZones() {
        // ⚠ 2026-11-07：窗口原点**只能取 params**。
        //   ensureZoneBaseWindow() 刚刚 updateViewLayout 过（把球窗定成 793×579），
        //   这一刻 glView.getLocationOnScreen() 还是**旧窗口的位置**（实测 y=281，新的是 179），
        //   配上新的 winH 一算，菜单中心就低了 ~100px：六圆环整体下移、最下面那个圆被窗口裁掉、
        //   卡片标题/列表跟着往下跑（用户报的「菜单/工作目录/会话页面都不正常」）。
        //   params 是我们自己刚写的，和 updateViewLayout 同帧，才是准的。
        int inset = Math.round(winY0());
        int winLeft = params != null ? params.x : 0;
        int winTop = params != null ? (params.y + inset) : 0;
        // 2026-10-23：菜单**整体居中于屏幕**（不再跟球心跑）。
        // 球可能贴着屏幕边/底，跟着它算菜单就会有一角出屏 —— 用户报的就是这个。
        int topInset = inset;
        // 用户 2026-10-23：**左右居中**，上下跟"触发那一刻球在哪"（再夹进屏幕）。
        float cx = screenW * 0.5f;
        float cy = winTop + winH * 0.5f;
        // ★ 2026-11-06：球缩到屏宽 1/3 了，但**六区菜单不能跟着缩**（用户要求）。
        //   圆半径还按**原版球高**算，装不装得下也按**原版球窗**算 —— 于是 R、环半径、
        //   圆心屏幕坐标和缩小前完全一样；球窗太小时由 ensureZoneWindow() 临时撑到原版大小，
        //   退出状态 3 再缩回小球（见 exitZones）。
        float halfH = winSize * 0.9f * minHalfY0;
        zoneR = Math.max(dp(46), halfH * 0.48f);
        // 六个圆围成一圈：半径 = 2.35 × 圆半径。
        //   相邻圆心弦长 = ring × 2sin30° = ring = 2.35R，两个圆之间的空隙 ≈ 0.35R；
        //   命中判定半径是 1.15R —— 相邻两个命中圈的间距 (2.35R − 2×1.15R) = 0.05R > 0，
        //   所以**不会两个圆同时算命中**，这是"稍微再圆一点点、别选错"的关键。
        // ★ 2026-10-23 用户确认"小一号那版效果很好"：六圆按**当前球窗**装下（不放大窗口），
        //   这样 GL 的 surface 不会失配（k=1），玻璃圆和图标天然对齐。
        //   ring = 2.1R：相邻圆心距 2.1R，命中圈 1.15R → 留 0.2R 空隙，不会选错。
        float maxR = (Math.min(winW, winH) * 0.5f - dp(8)) / 3.1f;
        zoneR = Math.min(zoneR, Math.max(dp(30), maxR));
        zoneRing = zoneR * 2.1f;
        // 角度从正上方开始、每 60° 一个（屏幕 y 向下 → sin 取正即向下）
        final int[] order = {ZONE_TOP, ZONE_TR, ZONE_BR, ZONE_BOTTOM, ZONE_BL, ZONE_TL};
        for (int i = 0; i < ZONE_COUNT; i++) {
            double a = Math.toRadians(-90 + i * 60);
            zoneCx[order[i]] = cx + (float) (zoneRing * Math.cos(a));
            zoneCy[order[i]] = cy + (float) (zoneRing * Math.sin(a));
        }
        // 上下夹一下：整个环必须落在屏幕内（含 14dp 边距）
        float ext = zoneRing + zoneR + dp(14);
        float cyMin = topInset + ext;
        float cyMax = screenH - ext;
        if (cyMax < cyMin) cyMax = cyMin;
        cy = Math.max(cyMin, Math.min(cyMax, cy));
        holdCenterX = cx; holdCenterY = cy;
        if (holdWinH0 <= 0) holdWinH0 = winH;
        if (holdWinW0 <= 0) holdWinW0 = winW;
        zoneLocalX = new float[ZONE_COUNT];
        zoneLocalY = new float[ZONE_COUNT];
        for (int i = 0; i < ZONE_COUNT; i++) {
            zoneLocalX[i] = zoneCx[i] - winLeft;
            zoneLocalY[i] = zoneCy[i] - winTop;
        }
        // 窗口保持球的尺寸（放大窗口会让 GL surface 失配 → 玻璃和图标错位）
    }

    private float[] zoneLocalX, zoneLocalY;
    /** 进状态 3 时的球心（屏幕像素）——卡片以它为中心排版，窗口长高也不会跑。 */
    private float holdCenterX = -1f, holdCenterY = -1f;
    /** 进状态 3 时的球窗高度：卡片用完要还回去（用户要求"保持当前大小"）。 */
    private int holdWinH0 = 0;
    /** 进状态 3 时的球窗宽度（六区环形排布要先把窗口撑大，退出时按这个还回去）。 */
    private int holdWinW0 = 0;
    /** 进状态 3 前的窗口 y / x（退出状态 3 要一模一样还回去）。 */
    private int holdWinX0 = Integer.MIN_VALUE, holdWinY0v = Integer.MIN_VALUE;

    /**
     * 进状态 3 的**第一帧**：把玻璃窗一次性定成"原版球窗"（793×579，以球心为中心），
     * 然后整个状态 3 期间**一个像素都不再动窗口**。
     *
     * 为什么必须一次定死：六圆的玻璃由 GL 画（用 renderer.winX/winY）、图标由 zonesView 画
     * （用它自己的窗口位置）、命中判定用触摸代理窗口的矩形 —— 三套坐标系。状态中途
     * updateViewLayout 会让它们差一拍：图标离圆、标题行点不到、文字跑到玻璃外面。
     * 原版（球没缩小那版）之所以一直正常，就是因为它的球窗本来就是 793×579，从头到尾没动过。
     */
    private void ensureZoneBaseWindow() {
        if (wm == null || glView == null || params == null) return;
        int inset = Math.round(winY0());
        float base = winSize * 0.9f;
        int W = Math.min(screenW, Math.max(2, Math.round(2f * base * minHalfX0 + 2f * dp(24))));
        int H = Math.min(screenH, Math.max(2, Math.round(2f * base * minHalfY0 + 2f * dp(24))));
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        int cx = loc[0] + winW / 2;
        int cy = loc[1] + winH / 2;
        int x = Math.max(0, Math.min(Math.max(0, screenW - W), cx - W / 2));
        int yScreen = Math.max(inset, Math.min(Math.max(inset, screenH - H), cy - H / 2));
        // 记下"进状态 3 之前"的窗口，退出时原样还回去
        holdWinW0 = winW;
        holdWinH0 = winH;
        holdWinX0 = params.x;
        holdWinY0v = params.y;
        params.x = x;
        params.y = yScreen - inset;
        params.width = W;
        params.height = H;
        winW = W;
        winH = H;
        if (renderer != null) {
            renderer.winPixelW = W;
            renderer.winPixelH = H;
            renderer.winX = x;              // ★ 屏幕坐标：GL 画形状时按它换算
            renderer.winY = yScreen;
        }
        markWinGeomChanged();
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        resetGlassBufferFull();             // surface 跟着钉成 W×H（k=1）
        if (zonesView != null && zonesParams != null) {
            zonesParams.x = x;
            zonesParams.y = yScreen - inset;
            zonesParams.width = W;
            zonesParams.height = H;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
        }
        syncBallTouchWindow();
        Log.i("FloatingBall", "zones: 基准窗口 " + W + "x" + H + " @(" + x + "," + yScreen
                + ")（原 " + holdWinW0 + "x" + holdWinH0 + "）");
    }

    /** 按住的那一刻就把"工作目录 + 最近目录下的会话"取回来，卡片一出现就是热的。 */
    private void prefetchZonesData() {
        final DshApi api = DshApi.get(this);
        prefetchedDirs = null;
        prefetchDirPath = null;
        prefetchedRunning = null;          // null = 还没取到（卡片会显示「读取中…」）
        new Thread(() -> {
            // ① **先**取「运行中的会话」：一次 session/list 就够，几十毫秒。
            //   左下角那张卡片是 260ms 悬停后就弹的，必须比它快才能一按就有内容。
            try {
                applyRunningPrefetch(api.runningSessions());
            } catch (Throwable t) {
                Log.w("FloatingBall", "zones: 读运行中会话失败 " + message(t));
            }
            try {
                final List<String> dirs = api.recentDirs();
                // 前 4 个目录的会话都先取回：右滑进去时列表已经热的，不会先闪一个"载入中/1 项"
                final java.util.LinkedHashMap<String, List<DshApi.SessionRow>> cache =
                        new java.util.LinkedHashMap<>();
                for (int i = 0; i < Math.min(4, dirs.size()); i++) {
                    try { cache.put(dirs.get(i), api.sessionsIn(dirs.get(i))); }
                    catch (Throwable ignored) { }
                }
                final String firstDir = dirs.isEmpty() ? null : dirs.get(0);
                final List<DshApi.SessionRow> rows =
                        firstDir == null ? new ArrayList<>() : cache.get(firstDir);
                main.post(() -> {
                    prefetchedDirs = dirs;
                    prefetchCache.clear();
                    prefetchCache.putAll(cache);
                    prefetchDirPath = firstDir;
                    prefetchedSessions = rows == null ? new ArrayList<>() : rows;
                    Log.i("FloatingBall", "zones: 预取 " + dirs.size() + " 个目录 / "
                            + rows.size() + " 条会话 / 运行中 "
                            + (prefetchedRunning == null ? "?" : prefetchedRunning.size()) + " 条");
                });
            } catch (Throwable t) {
                Log.w("FloatingBall", "zones: 预取失败 " + message(t));
            }
        }, "hold-prefetch").start();
    }

    /** 每帧把六个圆区（屏幕像素、y 向下）交给 GL 层；高亮跟着 zoneActive 走。 */
    private void pushZoneScene() {
        if (renderer == null) return;
        // ★ 卡片开着的时候，GL 应该画**那张方形玻璃**，不是六个圆。
        //   以前手势/窗口回调会再推一次六圆 → 卡片底下变成一圈圆玻璃、文字浮在圆上（"乱了"）。
        if (holdLevel > 0 && holdListRect != null && zonesActive) {
            pushCardScene();
            return;
        }
        // 玻璃层原点**直接取窗口 params**（syncWinPos 是 500ms 轮询，刚挪完窗口时还是旧值）
        if (params != null) {
            renderer.winX = params.x;
            renderer.winY = params.y + Math.round(winY0());
        }
        float[] s = new float[ZONE_COUNT * 4];
        float[] m = new float[ZONE_COUNT * 4];
        for (int i = 0; i < ZONE_COUNT; i++) {
            s[i * 4] = zoneCx[i];
            s[i * 4 + 1] = zoneCy[i];
            s[i * 4 + 2] = zoneR;
            s[i * 4 + 3] = zoneR;
            m[i * 4] = (zoneActive == i) ? 1f : 0f;      // act
            m[i * 4 + 1] = 0.02f;                        // dark：和球一样是浅玻璃
            m[i * 4 + 2] = 1f;                           // 圆：圆角 = 半宽
            // 用户 2026-10-23（第三次确认）：**六个圆一直显示**（不要只在指到时才亮），
            // 而且玻璃就是球那颗 shader 复用出来的（不是自绘的圆盘）。
            m[i * 4 + 3] = 1f;
        }
        // 六个形状全部启用（不再有"第 5 个关掉"这回事）
        // 圆区：整圈都在折射带里（像一颗被压扁的球）；黑顶压到中线上，下半圈留给折射
        zGlassMaterial(zoneR, 1.00f, 0.20f);
        renderer.zRefract = Math.max(dp(16), zoneR * 0.62f);
        renderer.zAb = 0.34f;
        renderer.zGlow = 1.0f;                       // 球顶两团柔光（只给圆区）
        renderer.zFrost = 0.45f;                     // 圆区：透镜为主，只加一点雾
        renderer.zScrim = 0.14f;
        renderer.zTopDarken = 0.80f;
        renderer.zCapPow = 1.35f;
        renderer.zEdgeShade = 0.45f;
        renderer.zEdgeShade2 = 0.90f;
        renderer.zDesat = 0.15f;
        renderer.zDome = 0.75f;
        renderer.zShape = s;
        renderer.zMeta = m;
    }

    /**
     * 状态 3 / 菜单面板的**材质**统一在这里给（2026-10-23：用户要"和球一样的液态玻璃"）。
     *
     * 球那条链路（GlassRenderer.FS_COMP）的光学参数是 refraction 200px / band 170px /
     * dome 0.4 —— 折射带宽比形状本身还大，所以整颗球都在"被掰弯"。上一版区域层只给了
     * 14px / 18px，等于一块平的半透明塑料（用户看到的就是那个）。
     * 这里按**形状短半轴**换算，圆区和面板都拿到和球同比例的透镜：
     *   band    = 短半轴 × bandRatio（≈1 → 整个形状都在折射带里）
     *   refract = 短半轴 × 0.34（球是 200/170 ≈ 1.18×band；UI 上取温和一点，
     *             太猛会把菜单文字后面的桌面撕成碎条）
     */
    private void zGlassMaterial(float shortHalf, float bandRatio, float dark) {
        if (renderer == null) return;
        renderer.zoneMode = true;
        renderer.zBandRatio = bandRatio;
        renderer.zRefract = Math.max(dp(10), shortHalf * 0.34f);
        renderer.zDome = 0.55f;
        renderer.zAb = 0.18f;
        renderer.zScrim = 0.14f + 0.10f * dark;
        renderer.zEdgeBleed = 0.13f;
        renderer.zGlow = 0.5f;
    }

    private void showZonesOverlay() {
        if (wm == null) return;
        if (zonesView == null) {
            BallZonesView v = new BallZonesView(this);
            zonesParams = overlayParams(winW, winH,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            zonesParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            // ⚠ 位置直接取 **params**（window 的父容器坐标）。别用 glView.getLocationOnScreen：
            //   窗口刚被 ensureZoneWindow 挪过时它还是旧值 → 图标整层偏一个位移（用户报的"图标离圆了"）。
            zonesParams.x = params.x;
            zonesParams.y = params.y;
            try {
                wm.addView(v, zonesParams);
                trackWindow(v);
                zonesView = v;
                // 只画不摸（不用 FLAG_NOT_TOUCHABLE：那会被 OEM 把 alpha 压到 0.8）
                makeTouchTransparent(v);
            } catch (Throwable t) {
                Log.w("FloatingBall", "add zones overlay failed: " + t);
                return;
            }
        }
        // 每次进入都重新贴窗口位置（球被挪过 / 窗口位置变过时，图标和玻璃必须还对齐）
        if (glView != null && zonesParams != null) {
            zonesParams.x = params.x;
            zonesParams.y = params.y;
            zonesParams.width = winW;
            zonesParams.height = winH;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
            syncZoneLocal();
        }
        zonesView.hideCards();                 // 上一次的列表文字不许留在屏幕上
        zonesView.setZones(zoneCx, zoneCy, zoneR);   // **屏幕**坐标：View 自己按真实窗口位置换算
        zonesView.setActive(-1);
        zonesView.setAppear(1f);
        zonesParams.width = winW;
        zonesParams.height = winH;
        zonesView.setVisibility(View.VISIBLE);
    }

    /** 手指在某点上：落在哪个圆区（屏幕坐标）。 */
    private int zoneAt(float rawX, float rawY) {
        if (!zonesActive) return -1;
        float bestD = zoneR * 1.15f;
        int best = -1;
        for (int i = 0; i < ZONE_COUNT; i++) {
            float dx = rawX - zoneCx[i], dy = rawY - zoneCy[i];
            float d = (float) Math.hypot(dx, dy);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    // ── 状态 3 的"全程不松手"多级卡片菜单 ─────────────────────────────────
    //
    // 用户 2026-10-23 的规则：「状态三下是多级自动隐藏式的卡片菜单，全程需要不松手，
    // 松手则恢复初始状态」。所以菜单不再是"松手后常驻的面板"，而是：
    //   按住球 → 四个圆区（一级）
    //   → 滑进左上圆区（悬停 260ms）→ 二级卡片：工作目录列表
    //   → 滑到某个目录（悬停 650ms）→ 三级卡片：该目录下的会话
    //   → 滑到某个会话（悬停 650ms）→ **提交**：收起状态 3、进入状态 2（发给那条会话）
    //   手指离开卡片区 → 卡片自动隐藏、回到一级圆区；任何时刻松手 → 回初始状态。
    // 右上/左下圆区悬停 260ms 就直接进状态 2（搜索会话 / 普通输入）；右下进 DSH 设置。

    static final class HoldItem {
        String label, sub;
        int kind;          // 0=目录 1=会话 2=新建会话 3=动作
        String data;       // 目录路径 / 会话 id
        HoldItem(String label, String sub, int kind, String data) {
            this.label = label; this.sub = sub; this.kind = kind; this.data = data;
        }
    }

    private static final long ZONE_DWELL_MS = 260;
    /** 悬停在**目录行**上多久 → 进这个目录的会话列表（2026-10-23 新交互：不再靠右滑）。 */
    private static final long DIR_DWELL_MS = 520;
    // 2026-10-23：**只有目录行**能"悬停进入"。会话行不做悬停动作 —— 用户明确说
    // 「太容易不小心进入会话」，进会话必须是"手指停在该行 + 松手"这一步显式确认。
    /** 悬停在**标题行（‹ 返回）**上多久 → 回退一级。 */
    private static final long TITLE_BACK_DWELL_MS = 200;
    /** 换级之后的"静默期"：这段时间里的悬停/松手都不算数（防止手指没动就被带进下一级）。 */
    private static final long LEVEL_ARM_MS = 300;

    private int holdLevel = 0;                  // 0=四区 1=二级卡片 2=三级卡片
    private String holdTitle = "";
    private final List<HoldItem> holdItems = new ArrayList<>();
    private final float[] holdRects = new float[40];   // 10 行位（面板只显示 holdVisible 行）
    /** 卡片列表的滚动窗口：holdFirst = 当前显示的第一项，holdVisible = 一次能显示几行。 */
    private int holdFirst = 0, holdVisible = 5;
    /** 手指停在卡片的上/下边缘 → 列表自动滚动（+1 下滚 / -1 上滚 / 0 停）。 */
    /** 一级（工作目录）滚到哪了 —— 从会话退回来还停在原来那一页（用户要求）。 */
    private int holdDirsFirst = 0;
    private int holdEdgeDir = 0;
    private boolean holdEdgeRunning = false;
    private float[] holdTitleRect = null;
    private float[] holdTitleLocal = null;
    private float[] holdListRect = null;
    private float[] holdListLocal = null;
    private float lastHoldX = 0, lastHoldY = 0;
    private float holdEnterY = -1;
    private boolean holdFocusArmed = false;
    private long holdLevelAt = 0;
    private int holdPointers = 1;
    private float holdLevelX = 0;
    private float swipeAnchorX = 0;
    private boolean holdPendingExpand = false;
    private int holdFocus = -1;
    private long holdFocusAt = 0;
    /** 手指进「标题行 = ‹ 返回」热区的时刻（0 = 现在不在标题行上）。 */
    private long titleHoverAt = 0;
    /** 标题行回退过一次 → 手指必须先离开标题行，才允许再触发（否则会一口气退两级）。 */
    private boolean titleLatched = false;
    // ── 点击模式（2026-10-23：卡片出现后 = 普通点击面板，不用再按住）──────────────
    /** true = 卡片已经"落定"，手指可以抬起；之后靠点击选择。 */
    private boolean cardTapMode = false;
    private float cardDownX, cardDownY;
    private boolean cardDragged;
    private int cardDownFirst;
    /** finishZones 是被"点击"调用的（点击模式里松手不应该关掉卡片）。 */
    private boolean cardTapCommit;
    /** 悬停进度（0..1），只用来画焦点行的高亮/进度条。 */
    private float holdDwellProgress = 0f;
    private boolean holdTickPosted = false;
    private String holdDir = null;
    private long holdZoneAt = 0;
    private int holdZoneHover = -1;
    /** 预取的数据（按住的那一刻就去拿，别等手指停在卡片上才发 RPC）。 */
    private List<String> prefetchedDirs = null;
    private String prefetchDirPath = null;
    private List<DshApi.SessionRow> prefetchedSessions = new ArrayList<>();
    /** 左下角「运行中的会话」预取结果；null = 还没取到。 */
    private List<DshApi.SessionRow> prefetchedRunning = null;
    private final java.util.LinkedHashMap<String, List<DshApi.SessionRow>> prefetchCache =
            new java.util.LinkedHashMap<>();

    private final Runnable holdZoneDwell = new Runnable() {
        @Override public void run() {
            int z = holdZoneHover;
            if (!zonesActive || z < 0 || holdLevel != 0) return;
            if (android.os.SystemClock.uptimeMillis() - holdZoneAt < ZONE_DWELL_MS) return;
            runZoneAction(z);
        }
    };

    /**
     * 某个圆区的动作（悬停够时、或点击时都走这里）。
     *
     * 左下角（2026-10-23 替换掉原来的「参数」）是**正在跑的会话**；参数弹窗没丢 ——
     * 搜索框双指长按（onTwoFingerHold）还是同一条路。
     */
    private void runZoneAction(int z) {
        if (z == ZONE_TOP || z == ZONE_BOTTOM) {
            // 两个"待定"占位圆：只做高亮，不挂动作（用户：另外两个圆待定）
            Log.i("FloatingBall", "zones: 待定圆（" + (z == ZONE_TOP ? "上" : "下") + "）暂无动作");
            return;
        }
        if (z == ZONE_TL) openHoldDirs();
        else if (z == ZONE_TR) commitHold(() -> enterSearchMode("状态3 右上：搜索会话", "", true, BAR_SEARCH));
        else if (z == ZONE_BL) startHoldRunning();
        else commitHold(() -> openDshSettings("状态3 右下"));
    }

    /**
     * 卡片上的一次点击（2026-10-23：卡片落定后就是普通可点面板）：
     *   标题行「‹ 」→ 返回上一级；某一行 → 执行（目录=进它的会话 / 会话=进对话 / 新建=建 / 运行中=停止）；
     *   点在面板外面（还在球身上）→ 收起卡片回六区。
     */
    private void cardTap(float x, float y) {
        if (onCardTitle(x, y)) {
            Log.i("FloatingBall", "zones: 点击标题 → 返回上一级");
            goBackOneLevel();
            return;
        }
        boolean inPanel = holdListRect != null
                && x >= holdListRect[0] && x <= holdListRect[0] + holdListRect[2]
                && y >= holdListRect[1] && y <= holdListRect[1] + holdListRect[3];
        int i = nearestRow(y);
        if (inPanel && i >= 0 && i < holdItems.size()) {
            final HoldItem it = holdItems.get(i);
            holdFocus = i;
            refreshCards();
            Log.i("FloatingBall", "zones: 点击第 " + (i + 1) + " 行「" + it.label + "」kind=" + it.kind);
            if (it.kind == 0) {                       // 目录 → 进它的会话列表
                openHoldSessions(it.data);
                return;
            }
            if (it.kind == 1 || it.kind == 2 || it.kind == 4) {
                cardTapCommit = true;
                try { finishZones(false); } finally { cardTapCommit = false; }
                return;
            }
            return;                                   // 占位/其它：点了不动
        }
        Log.i("FloatingBall", "zones: 点面板外 → 收起卡片，回六区");
        closeCards();
        syncBallTouchWindow();
    }

    /** 悬停不再触发任何提交：选中只发生在"二级里松手"。 */

    /** 提交：先收起状态 3（球与窗口都回初始几何），再执行动作。 */
    private void commitHold(Runnable action) {
        if (!zonesActive) return;
        exitZones();
        main.postDelayed(action, 60);
    }

    /** 悬停进度的可视化：卡片/圆区的高亮跟着手指走。 */
    private void updateHoldTouch(float rawX, float rawY) {
        lastHoldX = rawX; lastHoldY = rawY;
        if (holdLevel == 0) {
            int z = zoneAt(rawX, rawY);
            if (z != holdZoneHover) {
                holdZoneHover = z;
                holdZoneAt = android.os.SystemClock.uptimeMillis();
                if (z >= 0) vibrate(8);
                zoneActive = z;
                if (zonesView != null) zonesView.setActive(z);
                pushZoneScene();
                main.removeCallbacks(holdZoneDwell);
                if (z >= 0) main.postDelayed(holdZoneDwell, ZONE_DWELL_MS + 20);
            }
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();

        // ── 回退一级（2026-10-23 重做）──────────────────────────────────────────
        // 老做法：必须"往左划"，而且是在面板内部划 —— 面板很宽，划起来又长又难控。
        // 现在三个口子，都是**短距离**，而且**必须在"边缘自动滚动"之前判**：
        // 手指抽到面板上方时，边缘逻辑会把它当成"上滚"，永远轮不到回退。
        //   ① 手指从面板里抽走（左/上/下任意方向离开面板 26dp）→ 立刻退一级；
        //   ② 指到**标题行**（‹ 返回）停 200ms → 退一级（和按钮一样明确）；
        //   ③ 面板内部往左甩 20dp（习惯动作，保留）。
        // 2026-10-23 再改：手指**滑出面板**（往下滑去够下面没显示的行）**不再**回退菜单。
        // 回退只走两个明确口子：标题行「‹ 」悬停 / 面板内左甩；松手 = 结束状态 3 回初始球。
        if (onCardTitle(rawX, rawY)) {
            setHoldEdgeDir(0);
            if (titleHoverAt == 0) titleHoverAt = now;   // 计时在 tick 里做（手指停着不动也要能回退）
            return;                                      // 标题行不算"选中某一行"
        }
        titleHoverAt = 0;
        titleLatched = false;

        // 手指停在面板上/下边缘 → 自动滚动；滚动期间不抢焦点（否则滚动一格焦点就跳一行）
        if (holdListRect != null && holdVisible < holdItems.size()) {
            float top = holdListRect[1] + dp(26);                  // 跳过标题
            float bottom = holdListRect[1] + holdListRect[3] - dp(4);
            float edge = Math.min(dp(46), (bottom - top) * 0.22f);
            if (rawY > bottom - edge) setHoldEdgeDir(+1);
            else if (rawY < top + edge * 0.7f) setHoldEdgeDir(-1);
            else setHoldEdgeDir(0);
        } else {
            setHoldEdgeDir(0);
        }
        if (holdEdgeDir != 0) return;
        int i = nearestRow(rawY);
        float dx = rawX - swipeAnchorX;
        if (holdLevel >= 2 && dx < -dp(20)) {
            swipeAnchorX = rawX;
            goBackOneLevel();
            return;
        }

        // ── 焦点：手指真的移到哪一行，就选哪一行 ────────────────────────────────
        if (i != holdFocus) {
            holdFocus = i;
            holdFocusAt = now;
            holdDwellProgress = 0f;
            holdScrollToFocus();                 // 先滚（可能重排过一次）
            refreshCards();
            if (i >= 0) vibrate(6);
        }
    }

    /**
     * 一级 → 关掉卡片回四区；二级 → 回上一级（工作目录）。
     * （"回退"只退一级，不再要求用户斜着划到面板外面去。）
     */
    private void goBackOneLevel() {
        if (holdLevel >= 2) {
            Log.i("FloatingBall", "zones: 回退一级 → 工作目录列表");
            holdLevel = 1;
            openHoldDirs();
        } else if (holdLevel == 1) {
            Log.i("FloatingBall", "zones: 回退一级 → 四个圆区");
            closeCards();
        }
    }

    /** 手指是不是在标题行上（标题行 = 「‹ 返回」热区）。 */
    private boolean onCardTitle(float rawX, float rawY) {
        if (holdTitleRect == null) return false;
        float m = dp(6);
        return rawX >= holdTitleRect[0] - m && rawX <= holdTitleRect[0] + holdTitleRect[2] + m
                && rawY >= holdTitleRect[1] - m && rawY <= holdTitleRect[1] + holdTitleRect[3] + m;
    }

    // ── 悬停计时：手指停住不动也要能"累计"（靠定时器，不靠触摸事件）──────────────
    private final Runnable holdDwellTick = new Runnable() {
        @Override public void run() {
            if (!zonesActive || holdLevel == 0) { holdTickPosted = false; return; }
            pumpHoldDwell();
            if (zonesActive && holdLevel > 0) main.postDelayed(this, 50);
            else holdTickPosted = false;
        }
    };

    private void startHoldDwellTick() {
        if (holdTickPosted) return;
        holdTickPosted = true;
        main.removeCallbacks(holdDwellTick);
        main.post(holdDwellTick);
    }

    /**
     * 悬停判定 + 进度条。
     *
     * 规则（2026-10-23 新交互）：
     *   · 目录行停 {@link #DIR_DWELL_MS} → 进会话列表；
     *   · 会话行停 {@link #ITEM_DWELL_MS} → 直接进这条会话；
     *   · 「运行中的会话」那几条**不**做悬停动作（停止只能在松手时确认，避免误停）；
     *   · 换级后 {@link #LEVEL_ARM_MS} 内的悬停不算数 —— 手指没动就被带进下一级是以前最大的坑。
     */
    private void pumpHoldDwell() {
        if (!zonesActive || holdLevel == 0) return;
        // 2026-10-23：卡片已经是"点击面板"了 —— **不再**靠悬停自动进下一级
        //（手指刚好停在第一行上时会被自动带进去，用户明确不要这个）。
        if (cardTapMode) return;
        long now = android.os.SystemClock.uptimeMillis();
        // ① 标题行「‹ 」：停 200ms → 回退一级（手指不动也算）
        if (onCardTitle(lastHoldX, lastHoldY)) {
            if (titleHoverAt == 0) titleHoverAt = now;
            holdDwellProgress = 0f;
            if (zonesView != null) zonesView.setCardProgress(-1, 0f);
            if (!titleLatched && now - titleHoverAt >= TITLE_BACK_DWELL_MS) {
                titleLatched = true;             // 手指不离开标题行就只退这一级
                goBackOneLevel();
            }
            return;
        }
        titleHoverAt = 0;
        titleLatched = false;
        // ② 焦点必须还停在"手指当前所在的那一行"上，否则一律不算数
        //    （以前手指挪到标题行/面板外，旧的焦点还在偷偷累计悬停时间 → 会莫名其妙钻进某个目录）
        int rowNow = nearestRow(lastHoldY);
        if (rowNow != holdFocus) {
            holdFocus = rowNow;
            holdFocusAt = now;
            holdDwellProgress = 0f;
            if (zonesView != null) zonesView.setCardProgress(holdFocus < 0 ? -1 : holdFocus - holdFirst, 0f);
        }
        if (holdFocus < 0 || holdFocus >= visCount() + holdFirst) { holdDwellProgress = 0f; return; }
        int idx = holdFocus;
        if (idx >= holdItems.size()) { holdDwellProgress = 0f; return; }
        // 正在**滚动**（手指停在面板上/下边缘、或者已经滑到面板外面去够下面的行）→
        // 不累计、不触发"悬停进入"。用户原话：往下滑想选底下没显示的行时被带进二级列表，太难受。
        if (holdEdgeDir != 0) {
            holdDwellProgress = 0f;
            if (zonesView != null) zonesView.setCardProgress(-1, 0f);
            holdFocusAt = now;                 // 滚完要重新"停够时间"才算数
            return;
        }
        HoldItem it = holdItems.get(idx);
        // 只有**目录行**能悬停进入（kind 0）。会话行（kind 1）必须"停在那一行再松手"，
        // 不做悬停自动进入 —— 那太容易误开一条会话。
        if (it.kind != 0) { holdDwellProgress = 0f; return; }
        if (now - holdLevelAt < LEVEL_ARM_MS) { holdDwellProgress = 0f; return; }
        long need = DIR_DWELL_MS;
        long held = now - holdFocusAt;
        holdDwellProgress = Math.max(0f, Math.min(1f, held / (float) need));
        if (zonesView != null) zonesView.setCardProgress(holdFocus - holdFirst, holdDwellProgress);
        if (held < need) return;
        holdDwellProgress = 0f;
        Log.i("FloatingBall", "zones: 悬停 " + need + "ms → 进入「" + it.label + "」的会话");
        openHoldSessions(it.data);
    }

    private boolean farFromCards(float rawY) {
        if (holdItems.isEmpty()) return true;
        int last = Math.max(0, visCount() - 1);
        float top = holdRects[1], bottom = holdRects[last * 4 + 1] + holdRects[last * 4 + 3];
        return rawY < top - dp(36) || rawY > bottom + dp(36);
    }

    /** 按当前 holdFirst/holdFocus 重排并推给文字层（滚动时用）。 */
    private void refreshCards() {
        computeCardRects();
        if (zonesView != null) {
            zonesView.showCards(cardTitleText(), labels(), subs(), localRects(), holdTitleLocal,
                    holdListLocal, holdFocus < 0 ? -1 : holdFocus - holdFirst);
            zonesView.setCardScroll(cardScrollMode());
        }
        pushCardScene();
    }

    /** 0=不滚 / 1=上面还有 / 2=下面还有 / 3=两边都有。 */
    private int cardScrollMode() {
        int m = 0;
        if (holdFirst > 0) m |= 1;
        if (holdFirst + visCount() < holdItems.size()) m |= 2;
        return m;
    }

    /**
     * 焦点滚出可视窗口 → 列表跟着滚一行（用户 2026-10-23：「文字显示区不变但可滚动」）。
     * 保持原来的手势逻辑：手指上下移 = 选行；行到边界时**列表**滚，而不是手指必须滑到屏幕边上。
     */
    private void holdScrollToFocus() {
        if (holdFocus < 0 || holdItems.isEmpty()) return;
        int maxFirst = Math.max(0, holdItems.size() - holdVisible);
        int want = holdFirst;
        if (holdFocus < holdFirst) want = holdFocus;
        else if (holdFocus >= holdFirst + holdVisible) want = holdFocus - holdVisible + 1;
        want = Math.max(0, Math.min(maxFirst, want));
        if (want == holdFirst) return;
        holdFirst = want;
        Log.i("FloatingBall", "zones: 列表滚动 holdFirst=" + holdFirst + " focus=" + holdFocus);
        refreshCards();
    }

    /**
     * 手指停在卡片上/下边缘时自动滚动列表（用户 2026-10-23：「文字显示区不变但可滚动」）。
     * 按 380ms 一格滚，滚到底/顶自动停；手指一离开边缘立刻停。
     */
    private final Runnable holdEdgeScrollTick = new Runnable() {
        @Override public void run() {
            if (!zonesActive || holdLevel == 0 || holdItems.isEmpty() || holdEdgeDir == 0) {
                holdEdgeRunning = false;
                return;
            }
            int maxFirst = Math.max(0, holdItems.size() - holdVisible);
            int want = Math.max(0, Math.min(maxFirst, holdFirst + holdEdgeDir));
            if (want != holdFirst) {
                holdFirst = want;
                refreshCards();
                vibrate(4);
            }
            if ((holdEdgeDir > 0 && holdFirst >= maxFirst) || (holdEdgeDir < 0 && holdFirst <= 0)) {
                holdEdgeRunning = false;
                return;
            }
            main.postDelayed(this, 380);
        }
    };

    private void setHoldEdgeDir(int dir) {
        if (dir == holdEdgeDir && (holdEdgeRunning || dir == 0)) return;
        holdEdgeDir = dir;
        if (dir != 0) holdFocusAt = android.os.SystemClock.uptimeMillis();   // 滚动开始 → 悬停计时重来
        if (dir == 0) { holdEdgeRunning = false; return; }
        if (!holdEdgeRunning) {
            holdEdgeRunning = true;
            main.postDelayed(holdEdgeScrollTick, 260);
        }
    }

    /** 离手指纵向最近的条目（面板内才有效）。 */
    private int nearestRow(float rawY) {
        if (holdItems.isEmpty() || holdListRect == null) return -1;
        if (rawY < holdListRect[1] - dp(24) || rawY > holdListRect[1] + holdListRect[3] + dp(24)) return -1;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < visCount(); i++) {
            float cy = holdRects[i * 4 + 1] + holdRects[i * 4 + 3] * 0.5f;
            float d = Math.abs(rawY - cy);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best < 0 ? -1 : holdFirst + best;       // ← 换算成 holdItems 里的绝对下标
    }

    private int cardAt(float rawX, float rawY) {
        for (int i = 0; i < visCount(); i++) {
            float x = holdRects[i * 4], y = holdRects[i * 4 + 1];
            float w = holdRects[i * 4 + 2], h = holdRects[i * 4 + 3];
            if (rawX >= x - dp(10) && rawX <= x + w + dp(10)
                    && rawY >= y - dp(6) && rawY <= y + h + dp(6)) return holdFirst + i;
        }
        return -1;
    }

    /** 二级：工作目录（按住时预取过，直接出卡片）。 */
    private void openHoldDirs() {
        List<HoldItem> items = new ArrayList<>();
        List<String> dirs = prefetchedDirs;
        if (dirs != null) {
            // ★ 2026-10-23：原来这里写死 4 个（用户：「工作目录只能显示四个」）。
            //   现在按窗口能撑到的高度给到 10 个 —— 卡片会自动长高（ensureHoldWindow），
            //   行高/宽度/手势逻辑都没变。
            int maxDirs = Math.min(10, dirs.size());
            for (int i = 0; i < maxDirs; i++) {
                String d = dirs.get(i);
                items.add(new HoldItem(leaf(d), d, 0, d));
            }
        }
        if (items.isEmpty()) items.add(new HoldItem("（没有最近目录）", "", 3, ""));
        final boolean pending = holdPendingExpand && items.size() > 1
                && lastHoldX > holdLevelX + dp(22);
        showCards(1, "工作目录", items);
        if (holdDirsFirst > 0 && holdItems.size() > holdVisible) {
            holdFirst = Math.max(0, Math.min(holdItems.size() - holdVisible, holdDirsFirst));
            refreshCards();
            Log.i("FloatingBall", "zones: 回到工作目录，恢复滚动位置 " + holdFirst);
        }
        holdDirsFirst = 0;
        if (pending) {
            holdPendingExpand = false;
            Log.i("FloatingBall", "zones: 列表就绪 → 补做右滑展开「" + items.get(0).label + "」");
            openHoldSessions(items.get(0).data);
            return;
        }
        if (dirs == null) {
            // 还没预取完：后台拿一次再刷
            final DshApi api = DshApi.get(this);
            new Thread(() -> {
                try {
                    final List<String> d = api.recentDirs();
                    main.post(() -> {
                        prefetchedDirs = d;
                        if (zonesActive && holdLevel == 1 && "工作目录".equals(holdTitle)) openHoldDirs();
                    });
                } catch (Throwable t) {
                    Log.w("FloatingBall", "zones: 读目录失败 " + message(t));
                }
            }, "hold-dirs").start();
        }
    }

    /** 三级：某个目录下的会话。 */
    private void openHoldSessions(final String dir) {
        if (holdLevel == 1) holdDirsFirst = holdFirst;   // 记住工作目录列表滚到哪了
        holdDir = dir;
        List<HoldItem> items = new ArrayList<>();
        List<DshApi.SessionRow> cached = prefetchCache.get(dir);
        if (cached == null && dir.equals(prefetchDirPath)) cached = prefetchedSessions;
        if (cached != null) {
            // 会话同理：原来 3 条，现在最多 9 条（+「在此新建会话」= 10 行，正好是面板上限）
            for (int i = 0; i < Math.min(9, cached.size()); i++) {
                DshApi.SessionRow r = cached.get(i);
                items.add(new HoldItem(r.title == null || "null".equals(r.title)
                                ? "（未命名会话）" : r.title,
                        relTime(r.updatedAt), 1, r.id));
            }
        }
        items.add(new HoldItem("在此新建会话", leaf(dir), 2, dir));
        showCards(2, leaf(dir), items);
        if (cached == null) {
            final DshApi api = DshApi.get(this);
            new Thread(() -> {
                try {
                    final List<DshApi.SessionRow> rows = api.sessionsIn(dir);
                    main.post(() -> {
                        prefetchDirPath = dir;
                        prefetchedSessions = rows;
                        prefetchCache.put(dir, rows);
                        if (zonesActive && holdLevel == 2 && dir.equals(holdDir)) openHoldSessions(dir);
                    });
                } catch (Throwable t) {
                    Log.w("FloatingBall", "zones: 读会话失败 " + message(t));
                }
            }, "hold-sessions").start();
        }
    }

    /**
     * 左下角：**正在跑的会话**（`session/list` 里 running=true 的那些）。
     *
     * 用一级卡片（和工作目录同一套版式），所以没有"返回上一级"：
     * 手指停在某一行上松手 = 停掉那条会话的当前这一轮（`session/cancel`）。
     * 这一步只中止当前回合，会话本身还在，随时能继续发消息。
     */
    /** 把「运行中」的预取结果落下去；如果卡片正开着，立刻重画一次（不等用户再悬停）。 */
    private void applyRunningPrefetch(final List<DshApi.SessionRow> rows) {
        main.post(() -> {
            prefetchedRunning = rows;
            if (zonesActive && holdLevel == 1 && "运行中的会话".equals(holdTitle)) {
                startHoldRunning();            // 这一次走的是"已有数据"分支，不会递归取数
            }
        });
    }

    private void startHoldRunning() {
        if (!zonesActive) return;
        List<DshApi.SessionRow> rows = prefetchedRunning;
        List<HoldItem> items = new ArrayList<>();
        if (rows != null) {
            int max = Math.min(10, rows.size());          // 面板上限 10 行，多了靠边缘滚动
            for (int i = 0; i < max; i++) {
                DshApi.SessionRow r = rows.get(i);
                String sub = relTime(r.updatedAt);
                if (!r.subtitle().isEmpty()) sub = sub + " · " + r.subtitle();
                items.add(new HoldItem(r.title, sub, 4, r.id));   // kind=4：运行中会话（松手停止）
            }
            if (items.isEmpty()) items.add(new HoldItem("没有正在跑的会话", "松手退出", 9, ""));
        } else {
            items.add(new HoldItem("读取运行中的会话…", "", 9, ""));
        }
        showCards(1, "运行中的会话", items);
        if (rows != null) return;
        // 预取还没回来：后台取一次再刷（用户还停在这一级才刷，别抢画面）
        final DshApi api = DshApi.get(this);
        new Thread(() -> {
            try {
                final List<DshApi.SessionRow> got = api.runningSessions();
                main.post(() -> {
                    prefetchedRunning = got;
                    if (zonesActive && holdLevel == 1 && "运行中的会话".equals(holdTitle)) {
                        startHoldRunning();
                    }
                });
            } catch (Throwable t) {
                Log.w("FloatingBall", "zones: 读运行中会话失败 " + message(t));
            }
        }, "hold-running").start();
    }

    /** 停掉一条正在跑的会话（`session/cancel`），球上给一句 toast 反馈。 */
    private void stopRunningSession(final String sid, final String title) {
        final DshApi api = DshApi.get(this);
        Log.i("FloatingBall", "zones: 停止会话「" + title + "」sid=" + sid);
        new Thread(() -> {
            boolean ok;
            String err = null;
            try { ok = api.cancelSession(sid); }
            catch (Throwable t) { ok = false; err = message(t); }
            final boolean okf = ok;
            final String errf = err;
            main.post(() -> {
                String msg = okf ? "已停止「" + title + "」" : "停止失败：" + errf;
                Log.i("FloatingBall", "zones: " + msg);
                try {
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) { }
                if (okf && prefetchedRunning != null) {      // 本地列表顺手摘掉，下次按住就是新的
                    List<DshApi.SessionRow> keep = new ArrayList<>();
                    for (DshApi.SessionRow r : prefetchedRunning) if (!sid.equals(r.id)) keep.add(r);
                    prefetchedRunning = keep;
                }
            });
        }, "hold-stop").start();
    }

    /** 卡片上屏（形状 + 文字一起换）。 */
    private void showCards(int level, String title, List<HoldItem> items) {
        boolean sameLevel = (holdLevel == level) && title.equals(holdTitle);
        holdLevel = level;
        holdLevelAt = android.os.SystemClock.uptimeMillis();
        if (!sameLevel) { holdLevelX = lastHoldX; swipeAnchorX = lastHoldX; }
        holdPointers = 1;
        holdTitle = title;
        holdItems.clear();
        holdItems.addAll(items);          // 全部收进来，面板只显示 holdVisible 行，靠 holdFirst 滚动
        holdFirst = 0;
        holdFocus = -1;
        holdDwellProgress = 0f;
        titleHoverAt = 0;
        computeCardRects();
        if (holdListRect != null) ensureHoldWindow(holdListRect[1], holdListRect[3]);
        if (zonesView != null) {
            zonesView.showCards(cardTitleText(), labels(), subs(), localRects(), holdTitleLocal,
                    holdListLocal, -1);
            zonesView.setCardScroll(cardScrollMode());
            zonesView.setCardProgress(-1, 0f);
        }
        // ★ 2026-10-23（用户："方形完全透明，点一下才正常"）：这里以前把 GL 层 alpha 置 0，
        //   让文字层"自绘面板"。文字层那块黑底已经删了（方形要玻璃），再置 0 就**什么都没有** →
        //   卡片一出来是完全透明的，点一下才被别的路径重新推出来。
        //   现在：GL 层保持可见，并且立刻把**方形玻璃**推给 GL（pushCardScene）。
        pushCardScene();
        cardTapMode = true;               // 卡片一出现就落定：手指可以抬起，之后靠点击
        syncBallTouchWindow();            // 代理窗口要盖住整张卡片，否则点不到
        startHoldDwellTick();            // 手指停着不动也要能累计悬停（靠定时器）
        Log.i("FloatingBall", "zones: 卡片「" + title + "」" + items.size() + " 项");
        // 换级后**不**自动选中：手指要真的移到某一行上才算（否则停着不动会一路自动钻到底）
        holdEnterY = lastHoldY;
        holdFocusArmed = false;
    }

    /** 卡片标题的**显示**文字：前面加「‹ 」提示"标题行 = 返回"（holdTitle 本身不带，别污染判断）。 */
    private String cardTitleText() {
        return "‹ " + (holdTitle == null ? "" : holdTitle);
    }

    private void closeCards() {
        if (holdLevel == 0) return;
        setHoldEdgeDir(0);
        // 回到四区后必须把"上一次停在哪个圆区"清掉，否则手指还停在同一个圆区上时
        // 再想进这一区就再也不会触发（z != holdZoneHover 永远为 false）—— 2026-10-23 实测到的坑。
        holdZoneHover = -1;
        holdZoneAt = 0;
        titleHoverAt = 0;
        holdDwellProgress = 0f;
        cardTapMode = false;
        holdLevel = 0;
        syncBallTouchWindow();          // 退出点击模式 → 代理窗口缩回球那么大
        holdItems.clear();
        holdFocus = -1;
        holdDir = null;
        if (zonesView != null) { zonesView.hideCards(); zonesView.setCardScroll(0); }
        restoreHoldWindow();
        if (renderer != null) renderer.zoneMode = true;
        if (glView != null) glView.setAlpha(1f);    // 回六区：玻璃回来
        pushZoneScene();
        Log.i("FloatingBall", "zones: 手指离开卡片 → 自动隐藏，回到六区");
    }

    /** 当前**可见**的那一段（滚动窗口 [holdFirst, holdFirst+holdVisible)）。 */
    private int visCount() {
        return Math.max(0, Math.min(holdVisible, holdItems.size() - holdFirst));
    }

    private String[] labels() {
        int c = visCount();
        String[] a = new String[c];
        for (int i = 0; i < c; i++) a[i] = holdItems.get(holdFirst + i).label;
        return a;
    }

    private String[] subs() {
        int c = visCount();
        String[] a = new String[c];
        for (int i = 0; i < c; i++) a[i] = holdItems.get(holdFirst + i).sub;
        return a;
    }

    /**
     * 一张**整的**列表面板（用户 2026-10-23：「同一个列表不用切成多块」）：
     * 标题 + n 行全在一张圆角玻璃里，行只是面板内部的条目，不是各自一张卡。
     */
    private void computeCardRects() {
        int n = Math.max(1, holdItems.size());
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        float bw = Math.min(dp(260), winW - dp(36));
        float th = dp(26);                 // 标题行
        float pad = dp(6);
        float rh = dp(34);                 // 行高固定 = 原来那个大小（用户：保持当前大小）
        // ★ 面板**尺寸不变**，改成"列表在面板里滚动"（用户 2026-10-23：
        //   「相当于弄成文字显示区不变但是可滚动不就行了吗」）。
        //   先算这个窗口最多能放几行，多出来的靠 holdFirst 滚上来。
        float avail = Math.max(dp(80), winH - dp(20));
        holdVisible = Math.max(1, Math.min(n, (int) ((avail - th - pad) / rh)));
        int shown = Math.min(n, holdVisible);
        float total = th + shown * rh + pad;
        float cx = holdCenterX > 0 ? holdCenterX : loc[0] + winW * 0.5f;
        float cy = holdCenterY > 0 ? holdCenterY : loc[1] + winH * 0.5f;
        float left = cx - bw * 0.5f;
        float top = Math.max(Math.round(winY0()) + dp(8),
                Math.min(screenH - total - dp(16), cy - total * 0.5f));
        holdListRect = new float[]{left, top, bw, total};
        holdTitleRect = new float[]{left, top + pad * 0.5f, bw, th};
        for (int i = 0; i < 10; i++) {
            if (i < shown) {
                holdRects[i * 4] = left + pad;
                holdRects[i * 4 + 1] = top + th + i * rh;
                holdRects[i * 4 + 2] = bw - 2 * pad;
                holdRects[i * 4 + 3] = rh;
            } else {
                holdRects[i * 4] = 0; holdRects[i * 4 + 1] = 0;
                holdRects[i * 4 + 2] = 1; holdRects[i * 4 + 3] = 1;
            }
        }
    }

    /** 卡片矩形（**屏幕**坐标）—— 文字层自己在 draw 时换算成本窗口坐标，不再预减窗口原点。 */
    private float[] localRects() {
        float[] out = new float[visCount() * 4];
        if (holdTitleRect != null) {
            holdTitleLocal = new float[]{holdTitleRect[0], holdTitleRect[1],
                    holdTitleRect[2], holdTitleRect[3]};
        }
        if (holdListRect != null) {
            holdListLocal = new float[]{holdListRect[0], holdListRect[1],
                    holdListRect[2], holdListRect[3]};
        }
        for (int i = 0; i < visCount(); i++) {
            out[i * 4] = holdRects[i * 4];
            out[i * 4 + 1] = holdRects[i * 4 + 1];
            out[i * 4 + 2] = holdRects[i * 4 + 2];
            out[i * 4 + 3] = holdRects[i * 4 + 3];
        }
        return out;
    }

    /**
     * 卡片面板比球窗高时，把**玻璃窗往下长**（而不是把行数砍到 4）。
     *
     * 为什么以前只有 4 行：卡片是画在球窗里的，球窗高 ~210dp，5 行 × 34dp + 标题就溢出了，
     * 溢出的部分直接被窗口裁掉 —— 用户看到的就是"工作目录只能显示四个"。
     * 现在按面板实际高度把窗口撑到够（最多屏高 72%），必要时整体上移让它不出屏；
     * 关掉卡片（或退出状态 3）时 {@link #restoreHoldWindow()} 还回原来的大小。
     */
    private void ensureHoldWindow(float panelTop, float total) {
        if (wm == null || glView == null || params == null) return;
        int inset = Math.round(winY0());
        int m = Math.round(dp(12));
        int wantTop = Math.round(panelTop) - m;
        int wantBot = Math.round(panelTop + total) + m;
        int curTop = params.y + inset;
        int curBot = curTop + winH;
        int newTop = Math.min(curTop, Math.max(inset, wantTop));
        int newBot = Math.max(curBot, Math.min(screenH - Math.round(dp(6)), wantBot));
        newBot = Math.min(newBot, newTop + Math.round(screenH * 0.72f));
        int newH = Math.max(winH, newBot - newTop);
        if (newH <= winH && newTop == curTop) return;
        if (holdWinH0 <= 0) holdWinH0 = winH;
        params.y = newTop - inset;
        params.height = newH;
        winH = newH;
        if (renderer != null) {
            renderer.winPixelH = newH;
            renderer.winY = newTop;
        }
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        resetGlassBufferFull();
        syncBallTouchWindow();          // 触摸代理必须盖住整张卡片（否则标题行点不到、回不去）
        // 文字/图标那层也跟着长，否则卡片文字会被覆盖层自己裁掉
        if (zonesView != null && zonesParams != null) {
            zonesParams.y = newTop - inset;
            zonesParams.height = newH;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            for (int i = 0; i < ZONE_COUNT; i++) {
                zoneLocalX[i] = zoneCx[i] - loc[0];
                zoneLocalY[i] = zoneCy[i] - loc[1];
            }
            zonesView.setZones(zoneCx, zoneCy, zoneR);   // **屏幕**坐标：View 自己按真实窗口位置换算
        }
        Log.i("FloatingBall", "zones: 卡片窗口长高 " + winH + "px（顶 y=" + newTop + "）");
    }

    /** 按**当前窗口 params** 重算 zoneLocal（不能用 getLocationOnScreen：updateViewLayout 之后还没生效 → 图标错位）。 */
    private void syncZoneLocal() {
        if (params == null) return;
        int inset = Math.round(winY0());
        for (int i = 0; i < ZONE_COUNT; i++) {
            zoneLocalX[i] = zoneCx[i] - params.x;
            zoneLocalY[i] = zoneCy[i] - (params.y + inset);
        }
    }

    /**
     * 六区是**环形**排布，比球大一圈 —— 窗口先长到能装下整个环，否则最上/最下的圆会被窗口裁掉。
     * 以球心为中心同时长宽（球心不动），退出时由 restoreHoldWindow() 还回去。
     */
    /**
     * 玻璃层缓冲恢复"跟随视图"全分辨率。
     *
     * 为什么必须：大方框那条路上会 `setFixedSize(bw,bh)` 降分辨率（area > 70 万像素时），
     * shader 里于是 k = winW/winPixelW ≠ 1，所有形状按 k 缩放 —— 六区这种"图形要跟图标对齐"
     * 的层被这么一缩，就整体朝左上偏，越远偏得越多（用户看到的"一个都没对准"）。
     */
    private void resetGlassBufferFull() {
        if (glView == null) return;
        glassBufW = -1; glassBufH = -1;
        try {
            // ⚠ 这里**必须钉成窗口尺寸**，不能写 0,0（"跟随视图"）。
            //   实测：窗口从 787×573 长到 846×846 后，surface 仍停在 787×573 →
            //   shader 里 k = winW/winPixelW = 1.075，六圆整体缩放，跟图标全部错位。
            glView.getHolder().setFixedSize(Math.max(2, winW), Math.max(2, winH));
        } catch (Throwable ignored) { }
        Log.i("FloatingBall", "glass buffer -> 全分辨率 " + winW + "x" + winH);
    }

    private void ensureZoneWindow() {
        if (wm == null || glView == null || params == null) return;
        int inset = Math.round(winY0());
        int need = Math.round(2f * (zoneRing + zoneR) + dp(14));
        need = Math.min(need, Math.min(screenW, screenH));
        float cx = holdCenterX, cy = holdCenterY;
        if (cx <= 0 || cy <= 0) return;
        int newW = Math.max(winW, need);
        int newH = Math.max(winH, need);
        int m = Math.round(dp(8));                       // 和真实屏幕至少留 8dp
        int newX = Math.round(cx - newW * 0.5f);
        int newXmin = m, newXmax = screenW - m - newW;
        if (newXmax < newXmin) newXmax = newXmin;
        newX = Math.max(newXmin, Math.min(newXmax, newX));
        int newYScreen = Math.round(cy - newH * 0.5f);
        int newYminS = inset + m, newYmaxS = screenH - m - newH;
        if (newYmaxS < newYminS) newYmaxS = newYminS;
        newYScreen = Math.max(newYminS, Math.min(newYmaxS, newYScreen));
        int newY = newYScreen - inset;
        if (newW == winW && newH == winH && newX == params.x && newY == params.y) return;
        params.x = newX; params.y = newY;
        params.width = newW; params.height = newH;
        winW = newW; winH = newH;
        markWinGeomChanged();
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        // 绘制缓冲尺寸要跟窗口一起改，否则 shader 里 k = winW/winPixelW ≠ 1 —— 六圆按 k 缩放、和图标错位
        if (renderer != null) { renderer.winPixelW = newW; renderer.winPixelH = newH; }
        resetGlassBufferFull();          // 六区要精确对齐 → 关掉降采样，并把 surface 钉成窗口尺寸
        // 立刻再钉一次：updateViewLayout 之后系统还会走一趟 layout，可能把 surface 改回旧尺寸
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!zonesActive || glView == null) return;
                try { glView.requestLayout(); } catch (Throwable ignored) { }
                try { glView.getHolder().setFixedSize(Math.max(2, winW), Math.max(2, winH)); }
                catch (Throwable ignored) { }
                Log.i("FloatingBall", "glass surface 重新钉 " + winW + "x" + winH);
            }
        }, 350);
        syncZoneLocal();                     // ⚠️ 用新 params 算（getLocationOnScreen 此刻还没生效 → 图标错位）
        if (zonesView != null && zonesParams != null) {
            zonesParams.x = newX; zonesParams.y = newY;
            zonesParams.width = newW; zonesParams.height = newH;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
            zonesView.setZones(zoneCx, zoneCy, zoneR);   // **屏幕**坐标：View 自己按真实窗口位置换算
        }
        // 玻璃层也用同一个原点：renderer.winX/winY 是**屏幕**坐标，卡片的形状就按它换算
        if (renderer != null) { renderer.winX = params.x; renderer.winY = newYScreen; }
        pushZoneScene();
        Log.i("FloatingBall", "zones: 六区窗口 " + winW + "x" + winH + " @(" + newX + "," + newYScreen + ")"
                + " z0=(" + Math.round(zoneLocalX[0]) + "," + Math.round(zoneLocalY[0]) + ")"
                + " z4=(" + Math.round(zoneLocalX[4]) + "," + Math.round(zoneLocalY[4]) + ")"
                + " z5=(" + Math.round(zoneLocalX[5]) + "," + Math.round(zoneLocalY[5]) + ")"
                + " view=" + (zonesView == null ? "null" : zonesView.getWidth() + "x" + zonesView.getHeight()));
    }

    /** 卡片关掉 → 球窗恢复进状态 3 时的高度。 */
    private void restoreHoldWindow() {
        if (holdWinH0 <= 0 || wm == null || glView == null || params == null) return;
        int w0 = holdWinW0 > 0 ? holdWinW0 : winW;
        int h0 = holdWinH0;
        if (zonesActive) {
            // 还在状态 3：关掉卡片后要回到**能装下六个圆**的窗口，缩回球窗会把最上/最下的圆裁掉
            int need = Math.round(2f * (zoneRing + zoneR) + dp(14));
            need = Math.min(need, Math.min(screenW, screenH));
            w0 = Math.max(w0, need);
            h0 = Math.max(h0, need);
        }
        if (winH == h0 && winW == w0 && !zonesActive) return;
        winH = h0;
        winW = w0;
        params.width = w0;
        params.height = h0;
        if (zonesActive) {
            // ★ 2026-11-07：缩回来的时候**必须重新以环心为中心摆位**。
            //   以前只改宽高、x/y 原封不动 —— 窗口是 793 那一档时 x=144，缩到 574 还留 144，
            //   于是窗口右边到 718，而六圆环最右边到 807：右边那两个圆被窗口裁掉
            //   （用户说的「菜单右边似乎被裁切了」）。和 ensureZoneWindow() 同一套摆法。
            int inset = Math.round(winY0());
            int m = Math.round(dp(8));
            int cx = Math.round(holdCenterX > 0 ? holdCenterX : screenW * 0.5f);
            int cy = Math.round(holdCenterY > 0 ? holdCenterY : winH * 0.5f);
            int nx = Math.round(cx - w0 * 0.5f);
            int nxMax = screenW - m - w0;
            if (nxMax < m) nxMax = m;
            nx = Math.max(m, Math.min(nxMax, nx));
            int nys = Math.round(cy - h0 * 0.5f);
            int nyMin = inset + m, nyMax = screenH - m - h0;
            if (nyMax < nyMin) nyMax = nyMin;
            nys = Math.max(nyMin, Math.min(nyMax, nys));
            params.x = nx;
            params.y = nys - inset;
        }
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        if (zonesView != null && zonesParams != null) {
            zonesParams.width = w0;
            zonesParams.height = h0;
            zonesParams.x = params.x;
            zonesParams.y = params.y;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
            syncZoneLocal();
            zonesView.setZones(zoneCx, zoneCy, zoneR);   // **屏幕**坐标：View 自己按真实窗口位置换算
        }
        if (renderer != null) {
            renderer.winX = params.x;
            renderer.winY = params.y + Math.round(winY0());
        }
        // ★ 2026-11-07：这里原来**只改窗口、不改绘制缓冲/winPixel** ——
        //   卡片关掉、窗口从 793 缩回 574 之后，renderer.winPixelW/H 还停在 793x579，
        //   SurfaceView 的 buffer 也还钉在 793x579，于是 shader 里 winW/winPixelW 不再代表
        //   "缓冲÷窗口"，六圆被横向压扁成 0.72 倍、整体左移 110px（实测：
        //   玻璃环中心 x=430，而图标中心 x=540 —— 用户说的「图标一个都没对准」）。
        //   和 ensureZoneWindow() 一样，改完窗口必须同步 pixel + 重新钉死 buffer。
        if (renderer != null) {
            renderer.winPixelW = w0;
            renderer.winPixelH = h0;
        }
        resetGlassBufferFull();
        pushZoneScene();
        Log.i("FloatingBall", "zones: 卡片关闭，球窗回到 " + w0 + "x" + h0);
    }

    /** 列表的玻璃形状：**一个**圆角矩形（标题和所有行都在里面）。 */
    private void pushCardScene() {
        if (renderer == null || holdListRect == null) return;
        float[] s = new float[24];
        float[] m = new float[24];
        s[0] = holdListRect[0] + holdListRect[2] * 0.5f;
        s[1] = holdListRect[1] + holdListRect[3] * 0.5f;
        s[2] = holdListRect[2] * 0.5f;
        s[3] = holdListRect[3] * 0.5f;
        m[0] = 0f; m[1] = 0.60f; m[2] = 0.14f; m[3] = 1f;   // 面板模式，圆角 ≈ 20dp
        for (int i = 1; i < 6; i++) {
            s[i * 4] = 0; s[i * 4 + 1] = 0; s[i * 4 + 2] = 1; s[i * 4 + 3] = 1;
            m[i * 4] = 0; m[i * 4 + 1] = 0; m[i * 4 + 2] = 1; m[i * 4 + 3] = 0;
        }
        // 卡片面板：**整块均匀烟熏**（不要竖向黑顶 —— 那正是"直边黑块"的唯一来源）
        zGlassMaterial(Math.min(holdListRect[2], holdListRect[3]) * 0.5f, 0.55f, 0.35f);
        renderer.zGlow = 0f;
        renderer.zFrost = 0.38f;         // 别糊太狠：糊死了折射就看不见，玻璃感全靠它
        renderer.zBandRatio = 0.55f;     // 厚边只占 55% 短半轴：一圈厚玻璃边 + 中间平
        renderer.zRefract = Math.max(dp(12), Math.min(holdListRect[2], holdListRect[3]) * 0.26f);
        renderer.zAb = 0.10f;            // 色散收一点：太多彩边像故障艺术，不像玻璃
        renderer.zTopDarken = 0.0f;
        renderer.zScrim = 0.34f;
        renderer.zEdgeShade = 0.0f;      // 不要整圈压暗
        renderer.zEdgeShade2 = 1.0f;     // 只要"朝上的边"的内阴影 = 厚度
        renderer.zDesat = 0.45f;
        renderer.zDome = 1.0f;
        renderer.zShadow = 0.0f;
        renderer.zShape = s;
        renderer.zMeta = m;
    }

    /** 松手：不管在哪一级，都回初始状态（"松手则恢复初始状态"）。 */
    void finishZones(boolean cancelled) {
        if (!zonesActive) return;
        if (cardTapMode && !cardTapCommit && holdLevel > 0) {
            // 卡片已经落定成"点击面板"：手指抬起来不该把它关掉（用户 2026-10-23 要求）
            Log.i("FloatingBall", "zones: 松手（卡片已落定，保持打开）");
            return;
        }
        // ⚠️ 2026-10-23：**不再**"松手就选最近那一行"。
        // 老逻辑在 holdFocus<0 时抓 nearestRow —— 于是「刚进会话列表、手指没动就松手」
        // 会直接把第一条会话打开（用户报的"太容易不小心进入会话"）。
        // 现在只有"手指**真的停在某一行上**"且过了换级静默期，松手才算选中。
        boolean armed = android.os.SystemClock.uptimeMillis() - holdLevelAt >= LEVEL_ARM_MS;
        if (!cancelled && (!armed || holdFocus < 0)) {
            Log.i("FloatingBall", "zones: 松手时没有明确选中的行（armed=" + armed
                    + " focus=" + holdFocus + "）→ 只回初始状态");
            holdFocus = -1;
        }
        if (!cancelled && holdFocus >= 0 && holdFocus < holdItems.size()
                && holdItems.get(holdFocus).kind == 4) {
            final String sid = holdItems.get(holdFocus).data;
            final String title = holdItems.get(holdFocus).label;
            Log.i("FloatingBall", "zones: 松手选中运行中会话「" + title + "」→ 停止");
            exitZones();
            main.postDelayed(new Runnable() {
                @Override public void run() { stopRunningSession(sid, title); }
            }, 60);
            return;
        }
        if (!cancelled && holdLevel >= 2 && holdFocus >= 0 && holdFocus < holdItems.size()
                && holdItems.get(holdFocus).kind == 1) {
            final String sid = holdItems.get(holdFocus).data;
            final String title = holdItems.get(holdFocus).label;
            Log.i("FloatingBall", "zones: 松手选中会话「" + title + "」→ 进对话卡");
            exitZones();
            main.postDelayed(new Runnable() {
                @Override public void run() { openConversation(sid, title); }
            }, 60);
            return;
        }
        // 二级里最底下那行「在此新建会话」：真的建一条新会话并进对话卡（不是无效操作）
        if (!cancelled && holdLevel >= 2 && holdFocus >= 0 && holdFocus < holdItems.size()
                && holdItems.get(holdFocus).kind == 2) {
            final String dir = holdItems.get(holdFocus).data;
            Log.i("FloatingBall", "zones: 松手选中「在此新建会话」dir=" + dir);
            exitZones();
            main.postDelayed(new Runnable() {
                @Override public void run() { createAndOpenConversation(dir); }
            }, 60);
            return;
        }
        if (!cancelled && holdLevel == 1) {
            Log.i("FloatingBall", "zones: 一级卡片「" + holdTitle + "」松手没落在某一行 → 不回退、不执行");
        }
        Log.i("FloatingBall", "zones: 松手 → 回初始状态（level=" + holdLevel + "）");
        exitZones();
    }

    /** 在指定目录新建一条会话，然后直接进对话卡。 */
    private void createAndOpenConversation(final String dir) {
        prefs().edit().putString("send_cwd", dir).apply();
        final String preset = prefs().getString("send_preset", "");
        final DshApi api = DshApi.get(this);
        Log.i("FloatingBall", "convo: 新建会话 dir=" + dir + " preset=" + preset);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String sid = api.createSession(dir, preset);
                    main.post(new Runnable() {
                        @Override public void run() { openConversation(sid, "新会话"); }
                    });
                } catch (Throwable t) {
                    final String m = message(t);
                    main.post(new Runnable() {
                        @Override public void run() { toast("新建会话失败：" + m); }
                    });
                }
            }
        }, "dsh-new-convo").start();
    }

    private void openDshSettings(String why) {
        Log.i("FloatingBall", "settings: 打开 DSH 自己的设置界面（" + why + "）");
        try {
            Intent i = new Intent(this, DshSettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开 DSH 设置：" + message(t));
        }
    }

    /**
     * 每次回到初始状态：**水平居中、竖直贴近屏幕顶端**（用户 2026-10-23 要求）。
     *
     * 为什么不是屏幕正中：方形会长高，而"球顶钉住、往下长"的锚点就是这里设的 home。
     * home 在中间时，一长高就只能往上/往下被夹，观感就是"刚发出去就突然往下移"。
     * 把 home 放到接近顶端后，长高只有"往下长"这一个方向。
     */
    private void centerBallOnScreen() {
        if (params == null || wm == null || glView == null) return;
        if (searchActive || zonesActive || menuShown || glassFullscreen || convoOpen) return;
        params.x = Math.round((screenW - winW) / 2f);
        int topMargin = Math.round(winY0() + dp(10));          // 玻璃顶 ≈ 状态栏下沿
        params.y = Math.max(Math.round(winY0()), topMargin - Math.round(dp(24)));  // 窗口比玻璃多 24dp 留白
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        // ★ 2026-11-06：「家」只在**窗口已经是常态最小球窗、且 inset 已经量到**时才记。
        //   以前无条件记 —— 启动那一帧窗口还是 993×993、winY0() 也还是 0（布局没回来），
        //   记下来的家比真实球心低 117px（实测 587 vs 470）。后果：从聊天卡片缩回球时
        //   窗口按这个假家定位，整颗球（连同里面的 Siri 波/圆点）落在低 117px 的地方，
        //   看起来就是「和刚启动时不一样、波和点没居中」。窗口没落定时交给
        //   auditWindowAgainstBall() 落定后再补记（见那边）。
        float hyNow = siriEnabled ? minHalfY0        // 窗口还是原版球窗（球缩小不影响窗口）
                : (renderer != null && renderer.halfY > 0.02f ? renderer.halfY : 0.25f);
        int expectH = Math.round(2 * winSize * 0.9f * hyNow + 2 * dp(24));
        if (winY0() > 0 && Math.abs(winH - expectH) <= dp(10)) {
            homeCx = params.x + winW * 0.5f;
            homeCy = params.y + winH * 0.5f + winY0();          // home 用屏幕坐标
            Log.i("FloatingBall", "球回到初始状态 → 居中靠顶 (" + params.x + "," + params.y
                    + ") home=(" + Math.round(homeCx) + "," + Math.round(homeCy) + ")");
        } else {
            Log.i("FloatingBall", "球回到初始状态 → 居中靠顶 (" + params.x + "," + params.y
                    + ") home 暂不记（窗口 " + winW + "x" + winH + " 还没落到 " + expectH + "）");
        }
    }

    private void exitZones() {
        zonesActive = false;
        // ★ 状态 3 把窗口定成了原版球窗：退出时按进状态 3 前记下的值**原样**还回去
        if (holdWinW0 > 0 && holdWinH0 > 0 && wm != null && glView != null && params != null) {
            params.width = holdWinW0;
            params.height = holdWinH0;
            if (holdWinX0 != Integer.MIN_VALUE) params.x = holdWinX0;
            if (holdWinY0v != Integer.MIN_VALUE) params.y = holdWinY0v;
            winW = holdWinW0;
            winH = holdWinH0;
            if (renderer != null) {
                renderer.winPixelW = winW;
                renderer.winPixelH = winH;
                renderer.winX = params.x;
                renderer.winY = params.y + Math.round(winY0());
            }
            try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
            resetGlassBufferFull();
        }
        holdWinW0 = 0;
        holdWinH0 = 0;
        holdWinX0 = Integer.MIN_VALUE;
        holdWinY0v = Integer.MIN_VALUE;
        cardTapMode = false;
        holdWinH0 = 0;          // 下次进状态 3 重新记基准窗口（六区会把它撑大）
        holdWinW0 = 0;
        zoneActive = -1;
        if (zonesView != null) {
            zonesView.hideCards();
            zonesView.setVisibility(View.GONE);
        }
        if (renderer != null) {
            renderer.zoneMode = false;
            renderer.zShape = new float[24];
            renderer.zMeta = new float[24];
        }
        if (glView != null) glView.setAlpha(1f);         // 退出状态 3：玻璃层回来
        // 回到球：几何/文字按当前状态重排（和搜索层收起时同一套收尾）
        tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
        aMaster = 1f; aHalfX = minHalfX; aHalfY = minHalfY; aCorner = minCorner; aBlack = 0f;
        if (siriEnabled) {
            float base = winSize * 0.9f;
            updateSiriWindow(base * minHalfX, base * minHalfY);
            measureSiriTargets(siriStr);
            clampBallOnScreen();
        }
        applyBallTouchability();
        centerBallOnScreen();
    }

    // ── 玻璃面板 = **一个小悬浮窗**（不是全屏层）────────────────────────────
    // 用户 2026-10-23 的要求：「一切东西都是小悬浮窗的形式……尽可能小到不影响用户正常操作」。
    // 所以菜单不再铺满整屏：GL 窗口缩到"面板 + 一圈边距"，贴着球的位置摆；
    // 面板以外的屏幕完全归用户（球在菜单打开期间不参与绘制，触摸代理也不接管）。

    private void enterGlassPanel(int x, int y, int w, int h) {
        if (wm == null || glView == null || params == null) return;
        glassFullscreen = true;                    // 复用一个标志：玻璃窗 ≠ 球窗
        int m = Math.round(dp(14));
        int W = Math.min(screenW, w + 2 * m);
        int H = Math.min(screenH, h + 2 * m);
        int wx = Math.max(0, Math.min(screenW - W, x - m));
        int wy = Math.max(0, Math.min(screenH - H, y - m));
        glassBufW = glassBufH = -1;
        try { glView.getHolder().setFixedSize(0, 0); } catch (Throwable ignored) { }
        winW = W;
        winH = H;
        params.width = W;
        params.height = H;
        params.x = wx;
        params.y = wy - Math.round(winY0());
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (renderer != null) { renderer.winPixelW = W; renderer.winPixelH = H; }
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        glView.post(winSync);
        applyBallTouchability();
        Log.i("FloatingBall", "glass panel window " + W + "x" + H + " @(" + wx + "," + wy + ")");
    }

    /** 收回球窗（菜单关掉之后）。 */
    private void exitGlassPanel() {
        if (!glassFullscreen) return;
        glassFullscreen = false;
        if (renderer != null) renderer.zoneMode = false;
        tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
        aMaster = 1f; aHalfX = minHalfX; aHalfY = minHalfY; aCorner = minCorner; aBlack = 0f;
        if (params != null) {
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED;
        }
        if (glView != null && params != null) {
            float base = winSize * 0.9f;
            updateSiriWindow(base * minHalfX, base * minHalfY);
            measureSiriTargets(siriStr);
            clampBallOnScreen();
        }
        applyBallTouchability();
        centerBallOnScreen();
    }

    // ══════ 会话卡（状态 3 选完目录/会话、状态 2 的搜索选完都进这里）══════
    // 用户 2026-10-23：「对话框没做出来，直接复用球最终恢复的那个大方形加个输入和侧栏按钮即可」。
    // 所以这里不新造界面：球的方形（Siri 文字态）+ 底部一条输入 + 左边一个侧栏按钮。
    //
    // ★ 2026-11-09（用户口径）：「自己手动打开的回话页面不应该是和 AI 回复的页面一样吗？
    //   怎么像是两个不同的东西，一个有输入框一个没有」。⇒ **不新造第二种界面**：
    //   · 输入行不再归 `convoOpen` 管，而是归"玻璃此刻真的画成了方形态、里面有字"管
    //     （见 composerShouldShow()）——所以 AI 回复的那块玻璃底下也有这条输入行；
    //   · 内容一律走**同一套 markdown 排版**（BallMarkdownView，表格照排）；
    //   · 在回复页上打字发送 = 直接进这条会话的卡（不换界面，只把"这一轮"接上去）。

    private boolean convoOpen = false;
    /** 每一次"开卡 / 发消息"自增：异步读会话的线程只在自己那一代还生效时回写。 */
    private int convoGen = 0;
    /** 输入行当前是不是摆在屏上（只在 composerShouldShow() 为真时为真）。 */
    private boolean composerShown = false;
    /** 输入行窗口的高度（px）—— 玻璃底部要给这条带子留位置。 */
    private int composerH = 0;
    /**
     * 这一次 enterSearchMode 是「按住说话」那条语音路：输入框要拿焦点（Voicemap 往这儿塞字），
     * 但**不能弹键盘**。见 focusSearchInputForVoice()。
     */
    private boolean searchVoiceMode = false;
    private boolean convoStashed = false;
    private long convoOpenedAt = 0;
    private boolean convoHadText = false;
    private long convoDragLogAt = 0;
    private String convoStashText = "";
    private String convoSentText = "";
    private boolean suppressConvoAutoClose = false;
    private String convoSessionId = null, convoTitle = null;
    private BallSearchBar convoBar;
    private WindowManager.LayoutParams convoBarParams;
    private android.widget.TextView convoSideBtn;
    private WindowManager.LayoutParams convoSideParams;

    void openConversation(final String sid, final String title) {
        if (sid == null || sid.isEmpty()) return;
        convoOpen = true;
        final int gen = ++convoGen;
        convoOpenedAt = android.os.SystemClock.uptimeMillis();
        convoHadText = false;
        convoSessionId = sid;
        convoLastUser = "";
        convoTranscriptCache = "";
        convoTitle = title == null || title.trim().isEmpty() || "null".equals(title) ? "对话" : title;
        targetSessionId = sid;
        targetSessionTitle = title;
        ensureSiriForAssistant();          // 让球进入方形文字态
        // ★ 2026-11-09：开卡第一帧就把**旧的回复文字**换掉。
        //   以前这里不清字，等异步读完会话才 setSiriText()；中间那半秒里球里挂的是
        //   "上一条 AI 回复"——和这张卡的会话毫无关系。现在先摆一句占位。
        applySiriText("· 正在读取对话…");
        main.postDelayed(new Runnable() {
            @Override public void run() { if (convoOpen) measureSiriTargets(siriStr); }
        }, 120);                            // 让"底部输入区"立刻算进方形高度
        Log.i("FloatingBall", "convo: 进入对话「" + convoTitle + "」" + sid);
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                String text = "";
                try { text = api.sessionTranscript(sid, 60); }
                catch (Throwable t) { Log.w("FloatingBall", "convo: 读会话失败 " + message(t)); }
                final String tt = text == null ? "" : text.trim();
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!convoOpen || gen != convoGen || !sid.equals(convoSessionId)) return;
                        convoHadText = !tt.isEmpty();
                        convoTranscriptCache = tt;
                        Log.i("FloatingBall", "convo: 会话内容 " + tt.length() + " 字"
                                + (tt.isEmpty() ? "（空会话/还没说话）" : ""));
                        setSiriText(tt.isEmpty() ? "（这条会话还没有内容）" : tt);
                        scrollConvoToEnd();
                        main.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (!convoOpen || gen != convoGen) return;
                                measureSiriTargets(siriStr);   // 让方形的宽度/高度重新按文字收紧
                                syncComposerBar();
                            }
                        }, 350);
                    }
                });
            }
        }, "convo-load").start();
        ensureComposerBar(convoTitle);
        if (convoBar != null) convoBar.setHint(composerHint(convoTitle));
    }

    // ── 输入行：**会话面**的那条输入框（回复页 / 会话卡共用同一条）──────────

    /**
     * 这一帧输入行该不该在屏上。
     *
     * 判据只有一条：**玻璃此刻真的是"里面装着字的方形态"**。
     * 用户 2026-11-09 的两个口径都落在这条上：
     *   · 「AI 回复的页面和手动打开的回话页面应该是一样的」→ 只要玻璃里是字，输入行就在
     *     （不再区分 convoOpen，回复页也有）；
     *   · 「空白回话页面自动退回胶囊状态时，输入框依然没消失，很难看」→ 球一缩回胶囊
     *     （字没了 / 画出来是胶囊 / 进菜单 / 搜索胶囊 / 拍照藏界面），输入行同一拍收起。
     */
    private boolean composerShouldShow() {
        if (!siriEnabled || uiHidden) return false;
        if (glView == null || wm == null) return false;
        if (searchActive || searchClosing || menuSurfaceOpen()) return false;
        if (approvalShown || approvalClosing || glassFullscreen) return false;
        if (convoStashed) return false;                       // 参数面板借走了这块玻璃
        if (!siriTextShown) return false;                     // 字没露出来 → 输入行也不该在
        if (siriStr == null || siriStr.trim().isEmpty()) return false;
        if (squareDrawn01() < 0.5f) return false;             // 画出来还是胶囊 → 不许有输入行
        return true;
    }

    /** 输入行占用的底部带子高度（px）：玻璃要给它在**波头带子之上**留出位置。 */
    private int composerReservePx() {
        if (!composerShown || convoBar == null) return 0;
        return composerH + Math.round(dp(6)) + Math.round(dp(8));
    }

    /** 输入行贴着"玻璃底 − 波头带子"（Siri 波只露波头，输入行不许压住它）。 */
    private int composerY(int h) {
        int y = Math.round(screenH * 0.70f);
        if (glView != null) {
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            int glassBottom = loc[1] + winH - Math.round(dp(24));   // 窗口含 24dp 留白
            int band = Math.round(dp(ANIM_BAND_DP));                // 底部给波头留的那一截
            y = glassBottom - band - Math.round(dp(6)) - h;
        }
        return Math.max(Math.round(dp(80)), Math.min(y, screenH - h - Math.round(dp(12))));
    }

    /**
     * 建出那条输入行（**只负责建**，可见性/位置一律交给 {@link #syncComposerBar()}）。
     *
     * @return true = 窗口已就绪
     */
    private boolean ensureComposerBar(final String title) {
        if (wm == null) return false;
        if (convoBar != null) return true;
        BallSearchBar bar = new BallSearchBar(this);
        bar.setBackdrop(false);         // 输入行落在方形玻璃内部，不需要自带底
        bar.setHint(composerHint(title));
        bar.setListener(new BallSearchBar.Listener() {
            @Override public void onSubmit(String text) {
                composerSubmit(text);
            }
            @Override public void onLongPressAdd() { pickAttachment(); }
            @Override public void onTwoFingerHold() { openSettingsMenu(); }
            @Override public void onAttachmentTap() { clearAttachment(); }

            @Override public void onTargetTap() { }
            @Override public void onTapEdit() {
                if (convoBar != null) { convoBar.focusInput(); showConvoIme(); }
            }
            @Override public void onInputHeight(int px) { onConvoInputHeight(px); }
        });
        convoBarParams = overlayParams(2, 2,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        convoBarParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        convoBarParams.width = Math.max(Math.round(dp(160)), Math.round(screenW * 0.6f));
        convoBarParams.height = composerH = Math.round(dp(40));
        try { wm.addView(bar, convoBarParams); convoBar = bar; trackWindow(bar); }
        catch (Throwable t) { Log.w("FloatingBall", "composer bar failed: " + t); return false; }
        convoBar.setVisibility(View.GONE);
        Log.i("FloatingBall", "composer: 输入行窗口已建好（默认收起）");
        return true;
    }

    /** 输入行的提示语：带会话名时**截断**（长标题会把 40dp 那条输入行撑成两行、字被切掉）。 */
    private static String composerHint(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || "null".equals(t)) return "发消息…";
        if (t.length() > 6) t = t.substring(0, 6) + "…";
        return "回复「" + t + "」…";
    }

    /**
     * 每帧（siriTick）跟一次：该在就摆好，不该在就收起。
     *
     * ★ 这是问题 ② 的根子：以前输入行只在 openConversation / 关搜索 / 关菜单这三处
     *   显式摆位，球"自己缩回胶囊"（静了 8 秒兜底释放、最终回复 30 秒回收、双指手势、
     *   进菜单、拍照藏界面……）这条路上没人管它 → 玻璃早没了，输入框还悬在桌面上。
     */
    private void syncComposerBar() {
        if (wm == null) return;
        boolean want = composerShouldShow();
        if (!want) {
            if (composerShown) {
                composerShown = false;
                if (convoBar != null && convoBar.getVisibility() != View.GONE) {
                    convoBar.setVisibility(View.GONE);
                    Log.i("FloatingBall", "composer: 玻璃不是方形态（胶囊/菜单/搜索/空）→ 输入行收起");
                }
                // ⚠️ 这里**绝对不能**再 measureSiriTargets()：siriStr 还在（兜底释放只藏内容），
                //    量一遍就会按它重建内容 → 玻璃又长成方形态 → 输入行又摆上 → 8 秒后再收……
                //    实测就是这样来回抖（兜底释放 → 重建 → text hold on → 再释放）。
            }
            return;
        }
        if (convoBar == null && !ensureComposerBar(convoOpen ? convoTitle : null)) return;
        if (!convoOpen && convoBar != null && convoBarParams != null) {
            convoBar.setHint(composerHint(null));      // 回复页：一条普通输入行
        }
        if (convoBar.getVisibility() != View.VISIBLE) {       // 重新露出来：走一次"凝聚"
            composerShown = true;
            convoBar.setVisibility(View.VISIBLE);
            convoBar.resetMaterial();
            convoBar.materialize(true, 0);
            Log.i("FloatingBall", "composer: 方形态有字 → 输入行摆上（" + siriStr.length() + " 字）");
            remeasureForComposer();                          // 玻璃要让出这条带子
        }
        int h = Math.max(Math.round(dp(40)), convoBarParams.height);
        composerH = h;
        // 宽度跟"大方形"同宽（玻璃宽度 − 一点边距），不再固定 560dp
        int glassW = Math.round(2f * glassHalfW()) - Math.round(dp(28));
        int squareW = (siriText != null && siriText.getWidth() > dp(120))
                ? siriText.getWidth()
                : Math.round(screenW * SIRI_W_RATIO);
        int w = Math.max(Math.round(dp(160)),
                Math.min(Math.min(screenW - Math.round(dp(48)), squareW), glassW));
        int x = (screenW - w) / 2;
        int y = composerY(h) - Math.round(winY0());
        if (w != convoBarParams.width || h != convoBarParams.height
                || x != convoBarParams.x || y != convoBarParams.y) {
            convoBarParams.width = w;
            convoBarParams.height = h;
            convoBarParams.x = x;
            convoBarParams.y = y;
            try { wm.updateViewLayout(convoBar, convoBarParams); } catch (Throwable ignored) { }
        }
    }

    /** 输入行"上/下"那一刻要重排一次玻璃高度（重入守卫：measure 里可能又走到这儿）。 */
    private volatile boolean inComposerRemeasure = false;

    private void remeasureForComposer() {
        if (inComposerRemeasure) return;
        inComposerRemeasure = true;
        try { measureSiriTargets(siriStr); }
        catch (Throwable ignored) { }
        finally { inComposerRemeasure = false; }
    }

    /**
     * 对话卡里的输入行：字换行了就把这条小窗也拉高（底边不动，往上长）。
     * 卡片本身的玻璃高度由 measureSiriTargets 的 composerReservePx() 兜着。
     */
    private void onConvoInputHeight(int px) {
        if (convoBar == null || convoBarParams == null || wm == null) return;
        int minH = Math.round(dp(40));
        int maxH = Math.round(dp(40) * 2.2f);
        int want = (px <= 0) ? minH : Math.max(minH, Math.min(maxH, px));
        if (want == convoBarParams.height) return;
        convoBarParams.height = want;
        composerH = want;
        convoBarParams.y = composerY(want) - Math.round(winY0());
        try { wm.updateViewLayout(convoBar, convoBarParams); } catch (Throwable ignored) { }
        Log.i("FloatingBall", "convo: 输入行按行数拉到 " + want + "px");
    }

    /** 兼容老调用点（拖动球以后要重新摆一下输入行）。 */
    private void updateConvoChrome() { syncComposerBar(); }

    /** 这条输入行现在发给谁：卡里 = 卡的会话；回复页 = 刚回我话的那条；都没有 = 新会话。 */
    private String composerTargetSession() {
        if (convoOpen && convoSessionId != null && !convoSessionId.isEmpty()) return convoSessionId;
        if (asstSessionId != null && !asstSessionId.isEmpty()) return asstSessionId;
        if (replySessionId != null && !replySessionId.isEmpty()) return replySessionId;
        return null;
    }

    /**
     * 输入行回车：**一个入口**（回复页 / 会话卡 / 新会话都从这儿走）。
     *
     * 在回复页上打字 = 接着这条会话说下一句 → 顺手把这条会话开成会话卡
     * （用户要的"两个页面其实是同一个东西"：界面不换，只把这一轮接上去）。
     */
    private void composerSubmit(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        if (convoBar != null) {
            convoBar.setText("");
            convoBar.setHint("已发出 · 等待回复…");
        }
        String target = composerTargetSession();
        if (target == null || target.isEmpty()) {
            Log.i("FloatingBall", "composer: 没有目标会话 → 发到新会话「" + t + "」");
            sendTextNewSession(t, null);
            return;
        }
        if (!convoOpen || !target.equals(convoSessionId)) {
            // 回复页（或球里还挂着别的会话的字）→ 先把这条会话开成卡
            String title = target.equals(replySessionId) && convoTitle != null ? convoTitle : null;
            Log.i("FloatingBall", "composer: 回复页发送 → 接进会话 " + target + " 的卡");
            openConversation(target, title);
        }
        convoSend(target, t);
    }

    /**
     * 会话卡里发一条：自己发 + 轮询会话内容。
     *
     * 为什么不用 SiriAssistant.askIn：它会把球内文字整体替换成助手回复，
     * 于是"我刚发了什么"立刻消失（用户报的就是这个）。
     *
     * ★ 2026-11-09：轮询回来的不再是"最后一句回答"，而是**整段会话的 markdown**
     *   （`sessionTranscript`）—— 用户要的是"能显示表格的那种"回话显示，球内文字层
     *   本来就是 BallMarkdownView，喂原始 markdown 就自动排出标题/列表/表格。
     */
    private void convoSend(final String sid, final String text) {
        final File attach = pendingAttach;
        final String attachName = pendingAttachName;
        convoLastUser = text;
        if (attach != null && attach.length() > 8L * 1024 * 1024) {
            setSiriText(convoView("· 文件太大（" + (attach.length() / 1024 / 1024) + " MB），上限 8 MB，未发送"));
            if (convoBar != null) convoBar.setHint("文件太大，未发送");
            clearAttachment();
            return;
        }
        convoHadText = true;
        final int gen = ++convoGen;
        if (attach != null) convoLastUser = text + "\n［附件］" + (attachName == null ? attach.getName() : attachName);
        setSiriText(convoView("· 已发出，等待回复…"));
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> receipts = new ArrayList<>();
                    if (attach != null && attach.exists()) {
                        String rid = api.upload(sid,
                                attachName == null ? attach.getName() : attachName, readAll(attach));
                        receipts.add(rid);
                        Log.i("FloatingBall", "convo: 附件已上传 receipt=" + rid);
                        main.post(new Runnable() {
                            @Override public void run() { toast("已带附件发出"); }
                        });
                        main.post(new Runnable() {
                            @Override public void run() { clearAttachment(); }
                        });
                    }
                    api.prompt(sid, text, receipts);
                } catch (Throwable t) {
                    final String m = message(t);
                    main.post(new Runnable() {
                        @Override public void run() {
                            setSiriText(convoView("· 发送失败：" + m));
                            if (convoBar != null) convoBar.setHint("发送失败：" + m);
                        }
                    });
                    return;
                }
                String last = convoTranscriptCache == null ? "" : convoTranscriptCache;
                for (int i = 0; i < 60; i++) {          // 最长等 ~72s
                    try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
                    if (gen != convoGen) return;
                    if (convoOpen && !sid.equals(convoSessionId)) return;
                    String tr = "";
                    try { tr = api.sessionTranscript(sid, 60); } catch (Throwable ignored) { }
                    if (tr != null && !tr.equals(last)) {
                        last = tr;
                        final String shown = tr;
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (gen != convoGen) return;
                                convoTranscriptCache = shown;
                                setSiriText(convoView(null));
                                scrollConvoToEnd();
                            }
                        });
                    }
                }
            }
        }, "convo-send").start();
    }

    /**
     * 会话卡滚到**最新**那一头（聊天习惯：新的话在下面）。
     *
     * 球内文字窗是 `ScrollView`，会话记录是"你 / AI"一段段往下排的；打开卡时如果停在顶部，
     * 用户看到的就是这段会话**最早**那几句 —— 那不是他要的「回话内容」。
     */
    private void scrollConvoToEnd() {
        if (!convoOpen || siriText == null) return;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!convoOpen || siriText == null) return;
                try { siriText.fullScroll(View.FOCUS_DOWN); } catch (Throwable ignored) { }
            }
        }, 260);
    }

    /** 会话卡当前内容的缓存（markdown）：发消息时先把它和新发的那句拼起来立刻显示。 */
    private volatile String convoTranscriptCache = "";

    private String convoLastUser = "";

    /**
     * 会话卡要显示的文字 = **已有记录** + （还没进记录里的）我刚发的那句 + 状态行。
     *
     * 记录里已经含这句（`sessionTranscript` 刷出来了）就不重复追加 —— 否则屏幕上会出现
     * 两个「你」。
     */
    private String convoView(String status) {
        StringBuilder sb = new StringBuilder(
                convoTranscriptCache == null ? "" : convoTranscriptCache.trim());
        String mine = convoLastUser == null ? "" : convoLastUser.trim();
        if (!mine.isEmpty() && sb.indexOf(mine) < 0) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("**你**\n\n").append(mine);
        }
        if (status != null && !status.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(status);
        }
        return sb.toString();
    }

    private String convoLastUserBlock() {
        return convoLastUser == null || convoLastUser.trim().isEmpty()
                ? "" : "你：" + convoLastUser.trim();
    }

    private void showConvoIme() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && convoBar != null) imm.showSoftInput(convoBar.input(), 0);
        } catch (Throwable ignored) { }
    }

    private void closeConversation(String why) {
        if (!convoOpen) return;
        convoOpen = false;
        convoGen++;                             // 还在路上的读会话/轮询一律作废
        convoBarHiddenBySearch = false;
        Log.i("FloatingBall", "convo: 退出对话（" + why + "）");
        try {
            if (convoBar != null && wm != null) wm.removeView(convoBar);
        } catch (Throwable ignored) { }
        convoBar = null;
        composerShown = false;
        convoSessionId = null;
        convoTranscriptCache = "";
        convoLastUser = "";
        targetSessionId = null;                 // 关掉对话卡 = 回到"默认新会话"的语义
        targetSessionTitle = null;
        setSiriText("");
        centerBallOnScreen();
    }

    // ── 状态 2 暂存：菜单是小窗，胶囊那层收不起来 → 开菜单时先收起胶囊，关掉再放回来 ──

    private String stashedBarText = null;
    private String stashedBarHint = null;
    private boolean stashedBarFocus = true;

    private void stashBarIfOpen() {
        if (!searchActive || searchBar == null) return;
        stashedBarText = searchBar.text();
        stashedBarHint = null;
        stashedBarFocus = true;
        Log.i("FloatingBall", "menu: 暂存状态 2 的文字「" + stashedBarText + "」");
        dropSearchNow();
    }

    /** 立刻拆掉搜索层（不播收回动画）：菜单要独占那块玻璃窗。 */
    private void dropSearchNow() {
        searchActive = false;
        searchClosing = false;
        searchOpen = false;
        glassFullscreen = false;
        hideSearchIme();
        removeSearchWindows();
        if (renderer != null) renderer.searchMode = false;
        applyBallTouchability();
    }

    private void restoreBarIfStashed() {
        String t = stashedBarText;
        if (t == null) return;
        stashedBarText = null;
        enterSearchMode("菜单关闭：回到状态 2", t, stashedBarFocus, barPurpose);
    }

    // ── 玻璃菜单（工作目录 / 会话 / 参数）：面板由 GL 画，文字在 menuView 里 ──

    private void ensureMenuWindows() {
        if (wm == null) return;
        if (menuOutside == null) {
            View v = new View(this);
            v.setBackgroundColor(0x00000000);
            v.setClickable(true);
            menuOutsideParams = overlayParams(screenW, screenH,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            menuOutsideParams.x = 0;
            menuOutsideParams.y = -Math.round(winY0());
            menuOutsideParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            v.setOnTouchListener(new View.OnTouchListener() {
                boolean sawDown = false;
                @Override public boolean onTouch(View view, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            sawDown = true;
                            return true;
                        case MotionEvent.ACTION_UP: {
                            if (!sawDown) return true;
                            sawDown = false;
                            if (android.os.SystemClock.uptimeMillis() < menuArmAt) return true;
                            float x = e.getRawX(), y = e.getRawY();
                            if (menuRect != null && x > menuRect[0] && x < menuRect[0] + menuRect[2]
                                    && y > menuRect[1] && y < menuRect[1] + menuRect[3]) {
                                return true;                 // 落在面板上：交给面板那层
                            }
                            hideMenu("点空白处");
                            return true;
                        }
                        case MotionEvent.ACTION_CANCEL:
                            sawDown = false;
                            return true;
                        default:
                            return true;
                    }
                }
            });
            try { wm.addView(v, menuOutsideParams); menuOutside = v; trackWindow(v); }
            catch (Throwable t) { Log.w("FloatingBall", "add menu outside failed: " + t); }
        }
        if (menuView == null) {
            BallGlassMenu m = new BallGlassMenu(this);
            menuViewParams = overlayParams(2, 2,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            menuViewParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            m.setListener(new BallGlassMenu.Listener() {
                @Override public void onRow(int index) { onMenuRow(index); }
                @Override public void onDismiss() { hideMenu("面板返回"); }
            });
            try { wm.addView(m, menuViewParams); menuView = m; trackWindow(m); }
            catch (Throwable t) { Log.w("FloatingBall", "add menu view failed: " + t); }
        }
    }

    /**
     * 打开一块**小**液玻璃面板（贴着球、最多 5 行可见，不再铺满屏幕）。
     * 面板形状交给 zone 着色器画 —— 和第 3 状态的四个圆同一套材质。
     */
    private void showMenu(String path, String title, String subtitle,
                          List<BallGlassMenu.Row> rows, String returnPath) {
        if (wm == null || glView == null) return;
        if (!menuShown) stashBarIfOpen();             // 第一次开：先把状态 2 收起来
        menuPath = path;
        menuReturnPath = returnPath == null ? "" : returnPath;

        int rowHdp = 34;
        int w = Math.min(Math.round(screenW * 0.62f), Math.round(dp(268)));
        int headerH = Math.round(dp(14 + 18 + (subtitle == null || subtitle.isEmpty() ? 4 : 16)));
        int visible = Math.max(1, Math.min(rows.size(), 5));
        int h = Math.round(headerH + visible * dp(rowHdp) + dp(10));
        h = Math.min(h, Math.round(screenH * 0.48f));
        // 贴着球：横向居中于球心，纵向尽量压在球的位置（球此时不画），但不越界
        int ballCx = screenW / 2;
        if (glView != null && !glassFullscreen) {
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            ballCx = loc[0] + winW / 2;
        }
        int x = Math.max(Math.round(dp(8)), Math.min(screenW - w - Math.round(dp(8)), ballCx - w / 2));
        int y = Math.round(screenH * 0.5f - h / 2f);
        if (glView != null && !glassFullscreen) {
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            int ballCy = loc[1] + winH / 2;
            y = ballCy - h / 2;                       // 正压在球的位置（球此时不画）
        }
        y = Math.max(Math.round(dp(40)), Math.min(screenH - h - Math.round(dp(40)), y));
        menuRect = new float[]{x, y, w, h};

        enterGlassPanel(x, y, w, h);
        ensureMenuWindows();
        if (menuView == null) return;
        menuViewParams.width = w;
        menuViewParams.height = h;
        menuViewParams.x = x;
        menuViewParams.y = y - Math.round(winY0());
        try { wm.updateViewLayout(menuView, menuViewParams); } catch (Throwable ignored) { }
        // ★ 2026-10-23 六修：文字层**贴着玻璃窗口的真实位置**再对一次。
        //   上面那行用的是"请求坐标 - inset"，而 inset 会随状态变（父容器顶边、面板是否被
        //   clamp 到屏幕内、球是否被挪过）。实测出现过 90/186px 的错位 → 文字跑到玻璃外面。
        //   这里等玻璃窗口布局完，直接拿它自己的屏幕位置反推文字窗口该放哪，一次对死。
        glView.post(new Runnable() {
            @Override public void run() {
                if (menuView == null || !menuShown || glView == null || menuViewParams == null) return;
                int[] loc = new int[2];
                glView.getLocationOnScreen(loc);
                int m = Math.round(dp(14));
                int parentTop = loc[1] - params.y;              // 同一窗口、同一时刻 → 精确
                menuViewParams.x = loc[0] + m;
                menuViewParams.y = loc[1] + m - parentTop;
                try { wm.updateViewLayout(menuView, menuViewParams); } catch (Throwable ignored) { }
                Log.i("FloatingBall", "menu: 文字对齐玻璃 @(" + menuViewParams.x + ","
                        + menuViewParams.y + ") 玻璃屏内 y=" + loc[1] + " inset=" + parentTop);
            }
        });
        menuView.setContent(title, subtitle, rows);
        menuView.setVisibility(View.VISIBLE);
        menuShown = true;
        menuArmAt = android.os.SystemClock.uptimeMillis() + 700;
        pushMenuScene();
        Log.i("FloatingBall", "menu: " + path + " 「" + title + "」 " + rows.size() + " 行 "
                + w + "x" + h + " @(" + x + "," + y + ")");
    }

    /** 面板形状交给 zone 着色器（窗口已经是面板 + 边距那一小块）。 */
    private void pushMenuScene() {
        if (renderer == null || menuRect == null) return;
        float[] s = new float[24];
        float[] m = new float[24];
        s[0] = menuRect[0] + menuRect[2] * 0.5f;
        s[1] = menuRect[1] + menuRect[3] * 0.5f;
        s[2] = menuRect[2] * 0.5f;
        s[3] = menuRect[3] * 0.5f;
        m[0] = 0f; m[1] = 0.60f; m[2] = 0.22f;   // m[1]>=0.5 → 面板模式（和聊天卡同一支着色器）
        m[3] = 1f;
        for (int i = 1; i < 6; i++) {
            s[i * 4] = 0; s[i * 4 + 1] = 0; s[i * 4 + 2] = 1; s[i * 4 + 3] = 1;
            m[i * 4] = 0; m[i * 4 + 1] = 0; m[i * 4 + 2] = 1; m[i * 4 + 3] = 0;
        }
        // 菜单面板：和球同一套透镜，但**没有竖向黑顶** —— 整块均匀烟熏。
        // 之前那版"顶黑下透"的渐变就是用户看到的"锋利的黑色长方形"：只要暗度沿 y 变化、
        // 变化又集中在顶部一小段，人眼看到的就是一个直角方块（哪怕曲线是连续的）。
        // 现在改成：uTopDarken=0 + 均匀 scrim（0.46）→ 没有台阶、没有任何"边"。
        zGlassMaterial(Math.min(menuRect[2], menuRect[3]) * 0.5f, 0.55f, 0.30f);
        renderer.zGlow = 0f;
        renderer.zFrost = 0.55f;
        renderer.zBandRatio = 1.25f;
        renderer.zRefract = Math.max(dp(14), Math.min(menuRect[2], menuRect[3]) * 0.26f);
        renderer.zAb = 0.10f;
        renderer.zTopDarken = 0.0f;
        renderer.zScrim = 0.34f;
        renderer.zEdgeShade = 0.0f;
        renderer.zEdgeShade2 = 1.0f;
        renderer.zDesat = 0.45f;
        renderer.zDome = 1.0f;
        renderer.zShadow = 0.0f;
        renderer.zShape = s;
        renderer.zMeta = m;
        renderer.sHlAmt = 1.1f;          // 面板的贴边高光
    }

    void hideMenu(String why) {
        if (!menuShown) return;
        menuShown = false;
        menuPath = "";
        menuRect = null;
        if (menuView != null) menuView.setVisibility(View.GONE);
        Log.i("FloatingBall", "menu: 关闭（" + why + "）");
        removeMenuWindows();
        exitGlassPanel();
        applyBallTouchability();
        restoreBarIfStashed();               // 是从状态 2 进来的就把胶囊放回去
        if (convoStashed) {                  // 参数面板关掉：把对话内容放回来
            convoStashed = false;
            suppressConvoAutoClose = false;
            if (convoSessionId != null) {
                setSiriText(convoStashText == null ? "" : convoStashText);
                main.postDelayed(new Runnable() {
                    @Override public void run() { syncComposerBar(); }
                }, 80);
                Log.i("FloatingBall", "convo: 参数面板关闭，恢复对话内容");
            }
        }
    }

    private void removeMenuWindows() {
        if (menuView != null && wm != null) {
            try { wm.removeView(menuView); } catch (Throwable ignored) { }
            menuView = null;
            menuViewParams = null;
        }
        if (menuOutside != null && wm != null) {
            try { wm.removeView(menuOutside); } catch (Throwable ignored) { }
            menuOutside = null;
            menuOutsideParams = null;
        }
    }

    // ── 菜单内容：工作目录（二级）→ 会话（三级）────────────────────────────
    // 注意：**所有 RPC 都在后台线程先跑完、把结果缓存下来**，菜单点击只读缓存 + 改 prefs，
    //       绝不在主线程上等网络（主线程卡 1 秒就会把球的动画和触摸一起拖死）。

    private List<String> cacheDirs = new ArrayList<>();
    private String cacheBrowsePath = null;
    private List<String> cacheBrowseDirs = new ArrayList<>(), cacheBrowseNames = new ArrayList<>();
    private String cacheSessionsDir = null;
    private List<DshApi.SessionRow> cacheSessions = new ArrayList<>();
    private String cacheSearchQuery = null;
    private List<DshApi.SessionRow> cacheSearch = new ArrayList<>();
    private List<DshApi.Opt> cachePresets = new ArrayList<>();
    private List<DshApi.Opt> cachePerms = new ArrayList<>();
    private List<DshApi.Model> cacheModels = new ArrayList<>();
    private List<DshApi.Opt> cacheEfforts = new ArrayList<>();

    private void openDirsMenu() {
        showMenu("dirs", "工作目录", "最近用过的目录", new ArrayList<BallGlassMenu.Row>() {{
            add(new BallGlassMenu.Row("载入中…", "", true));
        }}, "");
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<String> dirs = api.recentDirs();
                    main.post(new Runnable() {
                        @Override public void run() {
                            cacheDirs = dirs;
                            if (!"dirs".equals(menuPath)) return;
                            List<BallGlassMenu.Row> rows = new ArrayList<>();
                            for (String dir : dirs) rows.add(new BallGlassMenu.Row(leaf(dir), dir, false));
                            rows.add(new BallGlassMenu.Row("浏览目录…", "从根目录逐层选", false));
                            rows.add(new BallGlassMenu.Row("关闭", "", true));
                            showMenu("dirs", "工作目录", dirs.size() + " 个最近目录", rows, "");
                        }
                    });
                } catch (Throwable t) {
                    fail("读工作目录失败", t);
                }
            }
        }, "dsh-dirs").start();
    }

    private void openDirsBrowse(final String dir) {
        showMenu("browse:" + dir, "浏览目录", dir == null ? "" : dir,
                new ArrayList<BallGlassMenu.Row>() {{
                    add(new BallGlassMenu.Row("载入中…", "", true));
                }}, "dirs");
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final DshApi.DirList list = api.listDir(dir);
                    main.post(new Runnable() {
                        @Override public void run() {
                            cacheBrowsePath = list.path;
                            cacheBrowseDirs = list.dirs;
                            cacheBrowseNames = list.names;
                            if (!("browse:" + list.path).equals(menuPath)) return;
                            List<BallGlassMenu.Row> rows = new ArrayList<>();
                            int n = Math.min(list.dirs.size(), 24);
                            for (int i = 0; i < n; i++) {
                                rows.add(new BallGlassMenu.Row(list.names.get(i), list.dirs.get(i), false));
                            }
                            rows.add(new BallGlassMenu.Row("在这里新建会话", list.path, false));
                            rows.add(new BallGlassMenu.Row("返回", "回到最近目录", true));
                            showMenu("browse:" + list.path, "浏览目录", list.path, rows, "dirs");
                        }
                    });
                } catch (Throwable t) {
                    fail("列目录失败", t);
                }
            }
        }, "dsh-browse").start();
    }

    private void openSessionsForDir(final String dir) {
        showMenu("sessions:" + dir, leaf(dir), "该目录下的会话",
                new ArrayList<BallGlassMenu.Row>() {{
                    add(new BallGlassMenu.Row("载入中…", "", true));
                }}, "dirs");
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DshApi.SessionRow> rows = api.sessionsIn(dir);
                    main.post(new Runnable() {
                        @Override public void run() {
                            cacheSessionsDir = dir;
                            cacheSessions = rows;
                            if (!("sessions:" + dir).equals(menuPath)) return;
                            List<BallGlassMenu.Row> out = new ArrayList<>();
                            for (DshApi.SessionRow r : rows) {
                                out.add(new BallGlassMenu.Row(r.title,
                                        (r.running ? "进行中 · " : "") + relTime(r.updatedAt), false));
                            }
                            out.add(new BallGlassMenu.Row("在这里新建会话", dir, false));
                            out.add(new BallGlassMenu.Row("返回", "回到工作目录", true));
                            showMenu("sessions:" + dir, leaf(dir), rows.size() + " 条会话", out, "dirs");
                        }
                    });
                } catch (Throwable t) {
                    fail("读会话失败", t);
                }
            }
        }, "dsh-dir-sessions").start();
    }

    // ── 会话搜索（右上角圆区 / 状态 2 的搜索模式）──────────────────────────

    /**
     * 「搜索会话」= 前端侧栏 `bhn1Oq_searchButton` 的行为：**本地按标题过滤**。
     * 本部署把 DSH 的内容检索索引关了（`session/search` 直接报 disabled），所以不走它。
     */
    private void runSessionSearch(final String query) {
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DshApi.SessionRow> rows = api.searchSessions(query);
                    main.post(new Runnable() {
                        @Override public void run() {
                            cacheSearchQuery = query;
                            cacheSearch = rows;
                            List<BallGlassMenu.Row> out = new ArrayList<>();
                            for (DshApi.SessionRow r : rows) {
                                out.add(new BallGlassMenu.Row(r.title,
                                        (r.running ? "进行中 · " : "") + relTime(r.updatedAt), false));
                            }
                            if (rows.isEmpty()) out.add(new BallGlassMenu.Row("没有匹配的会话", "", true));
                            out.add(new BallGlassMenu.Row("关闭", "", true));
                            showMenu("search", "搜索会话", rows.size() + " 条匹配 · " + query, out, "");
                        }
                    });
                } catch (Throwable t) {
                    fail("搜索失败", t);
                }
            }
        }, "dsh-search").start();
    }

    // ── 参数（Agent 预设 / 模型 / 推理等级 / 权限 / 发送目录）──────────────

    private void openSettingsMenu() {
        // 从对话卡里双指长按进来的：先把对话内容暂时收起（否则参数面板和对话文字叠在一起很乱）
        if (convoOpen && !convoStashed) {
            convoStashed = true;
            convoStashText = siriStr == null ? "" : siriStr;
            suppressConvoAutoClose = true;
            setSiriText("");
            suppressConvoAutoClose = false;
            if (convoBar != null) convoBar.setVisibility(View.GONE);
            Log.i("FloatingBall", "convo: 打开参数面板，先收起对话内容");
        }
        List<BallGlassMenu.Row> rows = new ArrayList<>();
        rows.add(new BallGlassMenu.Row("Agent 预设", cur("send_preset", "跟随默认"), false));
        String modelName = prefs().getString("send_model", "");
        rows.add(new BallGlassMenu.Row("模型", modelName.isEmpty() ? "跟随默认" : modelName, false));
        String effort = prefs().getString("send_effort", "");
        rows.add(new BallGlassMenu.Row("推理等级", effort.isEmpty() ? "跟随模型默认" : effort, false));
        rows.add(new BallGlassMenu.Row("权限", cur("send_perm", "跟随默认"), false));
        rows.add(new BallGlassMenu.Row("发送目录", cur("send_cwd", "跟随 DSH 默认"), false));
        rows.add(new BallGlassMenu.Row("关闭", "", true));
        showMenu("settings", "参数", "下一次发送时生效", rows, "");
    }

    private String cur(String key, String def) {
        String v = prefs().getString(key, "");
        return v == null || v.isEmpty() ? def : v;
    }

    private void openPresetMenu() {
        showMenu("preset", "Agent 预设", "新会话用的预设",
                new ArrayList<BallGlassMenu.Row>() {{ add(new BallGlassMenu.Row("载入中…", "", true)); }},
                "settings");
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DshApi.Opt> opts = api.agentPresets();
                    main.post(new Runnable() {
                        @Override public void run() {
                            cachePresets = opts;
                            if (!"preset".equals(menuPath)) return;
                            List<BallGlassMenu.Row> rows = new ArrayList<>();
                            String curId = prefs().getString("send_preset", "");
                            for (DshApi.Opt o : opts) {
                                rows.add(new BallGlassMenu.Row(
                                        (o.id.equals(curId) ? "✓ " : "") + o.name, o.desc, false));
                            }
                            rows.add(new BallGlassMenu.Row("返回", "", true));
                            showMenu("preset", "Agent 预设", "新会话用的预设", rows, "settings");
                        }
                    });
                } catch (Throwable t) {
                    fail("读预设失败", t);
                }
            }
        }, "dsh-presets").start();
    }

    private void openPermMenu() {
        showMenu("perm", "权限", "沙箱模式 + 审批策略",
                new ArrayList<BallGlassMenu.Row>() {{ add(new BallGlassMenu.Row("载入中…", "", true)); }},
                "settings");
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DshApi.Opt> opts = api.permissionPresets();
                    main.post(new Runnable() {
                        @Override public void run() {
                            cachePerms = opts;
                            if (!"perm".equals(menuPath)) return;
                            List<BallGlassMenu.Row> rows = new ArrayList<>();
                            String curId = prefs().getString("send_perm", "");
                            for (DshApi.Opt o : opts) {
                                rows.add(new BallGlassMenu.Row(
                                        (o.id.equals(curId) ? "✓ " : "") + o.name, "", false));
                            }
                            rows.add(new BallGlassMenu.Row("返回", "", true));
                            showMenu("perm", "权限", "沙箱模式 + 审批策略", rows, "settings");
                        }
                    });
                } catch (Throwable t) {
                    fail("读权限档失败", t);
                }
            }
        }, "dsh-perms").start();
    }

    private void openModelMenu() {
        showMenu("model", "模型", "下一次发送用这个模型",
                new ArrayList<BallGlassMenu.Row>() {{ add(new BallGlassMenu.Row("载入中…", "", true)); }},
                "settings");
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final DshApi.Catalog cat = api.modelCatalog();
                    final List<DshApi.Model> models = new ArrayList<>();
                    final List<String> groupNames = new ArrayList<>();
                    for (DshApi.ModelGroup g : cat.groups) {
                        for (DshApi.Model m : g.models) { models.add(m); groupNames.add(g.name); }
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            cacheModels = models;
                            if (!"model".equals(menuPath)) return;
                            List<BallGlassMenu.Row> rows = new ArrayList<>();
                            String curId = prefs().getString("send_model", "");
                            for (int i = 0; i < models.size(); i++) {
                                DshApi.Model m = models.get(i);
                                rows.add(new BallGlassMenu.Row(
                                        (m.id.equals(curId) ? "✓ " : "") + m.name,
                                        groupNames.get(i) + " · " + m.id, false));
                            }
                            rows.add(new BallGlassMenu.Row("返回", "", true));
                            showMenu("model", "模型", "下一次发送用这个模型", rows, "settings");
                        }
                    });
                } catch (Throwable t) {
                    fail("读模型目录失败", t);
                }
            }
        }, "dsh-models").start();
    }

    private void openEffortMenu() {
        showMenu("effort", "推理等级", "",
                new ArrayList<BallGlassMenu.Row>() {{ add(new BallGlassMenu.Row("载入中…", "", true)); }},
                "settings");
        final DshApi api = DshApi.get(this);
        final String provider = prefs().getString("send_provider", "");
        final String model = prefs().getString("send_model", "");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final DshApi.Catalog cat = api.modelCatalog();
                    DshApi.Model m = model.isEmpty() ? null : cat.find(provider, model);
                    if (m == null) m = cat.first();
                    final DshApi.Model mm = m;
                    final List<DshApi.Opt> efforts = m == null ? new ArrayList<DshApi.Opt>() : m.efforts;
                    main.post(new Runnable() {
                        @Override public void run() {
                            cacheEfforts = efforts;
                            if (!"effort".equals(menuPath)) return;
                            List<BallGlassMenu.Row> rows = new ArrayList<>();
                            String curId = prefs().getString("send_effort", "");
                            for (DshApi.Opt o : efforts) {
                                rows.add(new BallGlassMenu.Row(
                                        (o.id.equals(curId) ? "✓ " : "") + o.name, o.desc, false));
                            }
                            if (rows.isEmpty()) {
                                rows.add(new BallGlassMenu.Row("这个模型没有推理档", "", true));
                            }
                            rows.add(new BallGlassMenu.Row("返回", "", true));
                            showMenu("effort", "推理等级", mm == null ? "" : mm.name, rows, "settings");
                        }
                    });
                } catch (Throwable t) {
                    fail("读推理档失败", t);
                }
            }
        }, "dsh-efforts").start();
    }

    private void fail(final String what, Throwable t) {
        final String m = message(t);
        Log.w("FloatingBall", what + "：" + m);
        main.post(new Runnable() {
            @Override public void run() { toast(what + "：" + m); }
        });
    }

    /** 菜单点击分发：只读缓存 / 改 prefs / 切菜单，**不做网络**。 */
    private void onMenuRow(int index) {
        final String path = menuPath;
        Log.i("FloatingBall", "menu: 点击 " + path + " #" + index);
        if (path.isEmpty()) return;
        if (path.equals("dirs")) {
            if (index < cacheDirs.size()) { openSessionsForDir(cacheDirs.get(index)); return; }
            if (index == cacheDirs.size()) { openDirsBrowse("/data/data/com.termux/files/home"); return; }
            hideMenu("关闭");
            return;
        }
        if (path.startsWith("browse:")) {
            String dir = path.substring("browse:".length());
            int n = Math.min(cacheBrowseDirs.size(), 24);
            if (index < n) { openDirsBrowse(cacheBrowseDirs.get(index)); return; }
            if (index == n) {
                prefs().edit().putString("send_cwd", dir).apply();
                targetSessionId = null; targetSessionTitle = null;
                hideMenu("选定目录");
                enterSearchMode("工作目录：新建会话", "", true, BAR_SEND);
                return;
            }
            openDirsMenu();
            return;
        }
        if (path.startsWith("sessions:")) {
            String dir = path.substring("sessions:".length());
            if (index < cacheSessions.size()) {
                DshApi.SessionRow r = cacheSessions.get(index);
                targetSessionId = r.id;
                targetSessionTitle = r.title;
                prefs().edit().putString("send_cwd", r.cwd).apply();
                hideMenu("选定会话");
                enterSearchMode("发给「" + r.title + "」", "", true, BAR_SEND);
                return;
            }
            if (index == cacheSessions.size()) {
                prefs().edit().putString("send_cwd", dir).apply();
                targetSessionId = null; targetSessionTitle = null;
                hideMenu("目录新建");
                enterSearchMode("工作目录：新建会话", "", true, BAR_SEND);
                return;
            }
            openDirsMenu();
            return;
        }
        if (path.equals("search")) {
            if (index < cacheSearch.size()) {
                DshApi.SessionRow r = cacheSearch.get(index);
                targetSessionId = r.id;
                targetSessionTitle = r.title;
                hideMenu("选定会话");
                if (searchActive && searchBar != null) {
                    searchBar.setHint("发给：" + r.title);
                    searchBar.focusInput();
                } else {
                    // 侧栏/搜索里选了一条 → 进的就是"方形对话卡"
                    openConversation(r.id, r.title);
                }
                toast("已选：" + r.title);
                return;
            }
            hideMenu("关闭");
            return;
        }
        if (path.equals("settings")) {
            switch (index) {
                case 0: openPresetMenu(); return;
                case 1: openModelMenu(); return;
                case 2: openEffortMenu(); return;
                case 3: openPermMenu(); return;
                case 4: openDirsMenu(); return;
                default: hideMenu("关闭"); return;
            }
        }
        if (path.equals("preset")) {
            if (index < cachePresets.size()) {
                prefs().edit().putString("send_preset", cachePresets.get(index).id).apply();
                toast("预设：" + cachePresets.get(index).name);
            }
            openSettingsMenu();
            return;
        }
        if (path.equals("perm")) {
            if (index < cachePerms.size()) {
                prefs().edit().putString("send_perm", cachePerms.get(index).id).apply();
                toast("权限：" + cachePerms.get(index).name);
            }
            openSettingsMenu();
            return;
        }
        if (path.equals("model")) {
            if (index < cacheModels.size()) {
                DshApi.Model m = cacheModels.get(index);
                prefs().edit().putString("send_provider", m.provider)
                        .putString("send_model", m.id)
                        .putString("send_effort", m.defaultEffort == null ? "" : m.defaultEffort)
                        .apply();
                toast("模型：" + m.name);
            }
            openSettingsMenu();
            return;
        }
        if (path.equals("effort")) {
            if (index < cacheEfforts.size()) {
                prefs().edit().putString("send_effort", cacheEfforts.get(index).id).apply();
                toast("推理：" + cacheEfforts.get(index).name);
            }
            openSettingsMenu();
            return;
        }
        hideMenu("未知菜单");
    }

    private static String leaf(String path) {
        if (path == null || path.isEmpty()) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : path;
    }

    private static String relTime(long at) {
        if (at <= 0) return "";
        long ms = System.currentTimeMillis() - at;
        if (ms < 60_000L) return "刚刚";
        if (ms < 3_600_000L) return (ms / 60_000L) + " 分钟前";
        if (ms < 86_400_000L) return (ms / 3_600_000L) + " 小时前";
        return (ms / 86_400_000L) + " 天前";
    }

    // ── 附件（= 前端输入框的 uV2eYG_add）──────────────────────────────────

    /**
     * 系统文件选择器回来（{@link BallAttachPickerActivity} 从任何界面调起都走这里）：
     * 记下附件，并把状态 2 拉回来 —— 胶囊里能看到刚选中的文件名（用户要求）。
     */
    static void onAttachmentPicked(final File file, final String name) {
        final FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() {
                s.pendingAttach = file;
                s.pendingAttachName = name;
                if (s.convoOpen) {
                    // 对话卡里选的附件：就留在对话卡上，不要切回状态 2
                    if (s.convoBar != null) {
                        s.convoBar.setAttachmentName(name);
                        s.convoBar.setHint("已附加 " + name + "，发送时一起发出");
                    }
                    Log.i("FloatingBall", "attach: 对话卡挂上「" + name + "」");
                    return;
                }
                if (s.searchActive && s.searchBar != null) {
                    s.searchBar.setAttachmentName(name);
                    s.searchBar.focusInput();
                    s.showSearchIme();
                    Log.i("FloatingBall", "attach: 回到状态 2，胶囊里显示「" + name + "」");
                } else {
                    s.enterSearchMode("附件回来", "", true, BAR_SEND);
                    s.main.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (s.searchBar != null) s.searchBar.setAttachmentName(name);
                        }
                    }, 900);
                }
            }
        });
    }

    /** 长按状态 2 的胶囊：挑一个文件，发送时作为 file part 上传。 */
    void pickAttachment() {
        BallAttachPickerActivity.pick(this, new BallAttachPickerActivity.Result() {
            @Override public void onPicked(File file, String name) {
                pendingAttach = file;
                pendingAttachName = name;
                if (searchBar != null) searchBar.setAttachmentName(name);
                if (convoBar != null) convoBar.setAttachmentName(name);
                Log.i("FloatingBall", "attach: 选中 " + name + " (" + file.length() + " 字节)");
            }
        });
    }

    private void clearAttachment() {
        pendingAttach = null;
        pendingAttachName = null;
        if (searchBar != null) searchBar.setAttachmentName(null);
        if (convoBar != null) convoBar.setAttachmentName(null);   // 对话卡上那枚附件小标签也要收掉
    }

    // ═══════════ 搜索层：metaball-search.html 原样移植（双指长按唤起）═══════════

    //
    // 用户要求原话（2026-10-17，两次强调）：
    //   「它长按就隐藏球，然后出现小黑球，然后要和 Metaball→Search 页面里从顶部拿到的黑球
    //     一模一样，并且要复用 Metaball→Search 页面的动画和效果，**而不是球的变体**。」
    //
    // 所以这一版是**搬**，不是"借鉴"：
    //   · 形状层 = 演示页那段 FRAG **整段**移植（SearchLayerShader.java，只改了四处必须改的：
    //     去掉 #version、bgcol 换成抓屏纹理、fwidth 换成固定 1.5px、输出改成悬浮窗的预乘 alpha）；
    //   · 动画层 = 演示页那份 CPU 弹簧（class Spring）+ 状态机（IDLE/DRAG/CAPSULE/RETRACT）
    //     与它那组参数（ω=11 / ζ=0.72 / K=64 / height=18 / refract=14 …）逐条照抄；
    //   · 手势起手换成"双指长按"（用户要求），起手后**球的玻璃层整趟让路**：
    //     `renderer.searchMode = true`，那一帧画的是演示页那一层，球一点不参与。
    //
    // 唯一"我们自己的"部分是：① 唤起手势（双指长按，演示页是从顶部拖）；
    // ② 小黑球的出生点 = 球当时的球心（演示页是手指位置）；③ 胶囊里那行 DOM
    //    （🔍 输入框）是安卓 View，不是网页 —— 版式仍照演示页的 #search 抄。

    /** 演示页的 P（单位 = CSS px；安卓这边 1 CSS px = 1 dp，交给 shader 前再乘 density）。 */
    private static final float SQ_P_K = 64f;        // 融合半径 k
    private static final float SQ_P_HEIGHT = 18f;   // 透镜高度 height
    private static final float SQ_P_REFRACT = 14f;  // 折射强度（负向）
    private static final float SQ_P_HL = 1.5f;      // 高光强度
    private static final float SQ_P_AB = 0.12f;     // 色差
    private static final float SQ_P_CONT = 0.5f;    // 黑玻璃（容器）强度
    private static final float SQ_OM = 11f;         // 弹簧频率 ω
    private static final float SQ_ZE = 0.72f;       // 阻尼比 ζ
    private static final float SQ_EDGE = 26f;       // 顶部黑边高度（CSS px）
    private static final float SQ_R = 34f;          // 拖拽液滴半径（CSS px）
    private static final float SQ_CAP_H = 50f;      // 胶囊高（手机档）
    private static final float SQ_CAP_MARGIN = 44f; // 胶囊左右留白（sw - 44）
    private static final float SQ_CAP_TOP = 70f;    // 胶囊 y = EDGE + 70
    private static final float SQ_CAP_MAX_W = 560f; // 桌面档封顶（sw ≥ 600 时用）
    private static final long SQ_DROP_MS = 260;     // 小黑球先单独待一会儿再弹开（演示页是等人松手）
    private static final long SEARCH_HOLD_MS = 520; // 双指按住多久算"长按"（唤起搜索层）

    private String barInitialText = "";
    private boolean barAutoFocus = true;
    private boolean searchActive = false;      // 搜索层在屏上（含进场/出场动画）
    private boolean searchClosing = false;      // 正在出场（挡住重复进入）
    private boolean searchOpen = false;         // 胶囊已落定、可以打字
    private long searchGen = 0;                 // 进/出各 +1：延迟任务用它作废旧回调
    private long searchArmAt = 0;               // 全屏"点空白关掉"层开始接受点击的时刻
    private int sqPhase = 0;                    // 0=OFF 1=DRAG(小黑球) 2=CAPSULE 3=RETRACT
    private float sqDropCx = 0f, sqDropCy = 0f; // 小黑球的出生点（屏幕 px，= 球当时的球心）
    private BallSearchBar searchBar;
    private WindowManager.LayoutParams searchBarParams;
    private View searchOutside;
    private WindowManager.LayoutParams searchOutsideParams;
    private int sqBarX = Integer.MIN_VALUE, sqBarY, sqBarW, sqBarH;
    /**
     * 胶囊当前高度（CSS px / dp）。默认就是演示页的 50；语音识别/长句子把输入框挤满时，
     * BallSearchBar 会回调 {@link #onSearchInputHeight(int)} 把它按行数调大（最多约 4 行），
     * 胶囊弹簧跟着长高 —— 这就是用户要的「输入框灵活一点变高让文字换行」。
     */
    private float searchCapsH = SQ_CAP_H;
    private static final float SQ_CAP_H_MAX = SQ_CAP_H * 2.6f;   // 旧值，保留兜底
    /**
     * 胶囊高度上限（dp）：**按文字体积长**，不再钉死在一个固定值上。
     * 屏高 42% 或 8 行，哪个先到算哪个；再长的文字在输入框里自己滚。
     */
    private float searchCapsHMaxDp() {
        float d = sqDensity();
        return Math.min(SQ_CAP_H * 8f, (screenH * 0.42f) / Math.max(1f, d));
    }
    /** 胶囊开的时候把对话卡的输入行临时藏起来了（关胶囊再放回来）。 */
    private boolean convoBarHiddenBySearch = false;

    /** 一维欠阻尼弹簧（半隐式欧拉）—— 与 metaball-search.html 的 class Spring 逐行一致。 */
    private static final class MorphSpring {
        float x, v, target, om = SQ_OM, ze = SQ_ZE;
        MorphSpring(float v0) { x = target = v0; }
        MorphSpring cfg(float om, float ze) { this.om = om; this.ze = ze; return this; }
        void jump(float v0) { x = target = v0; v = 0f; }
        void to(float t) { target = t; }
        void step(float dt) {
            v += (om * om * (target - x) - 2f * ze * om * v) * dt;
            x += v * dt;
        }
    }

    // 演示页那个 sp 对象：cx/cy = 液滴中心，bx/by = 半宽/半高，k/dark/cont = 材质（全是 CSS px 或 0..1）
    private final MorphSpring sqCx = new MorphSpring(0f);
    private final MorphSpring sqCy = new MorphSpring(0f);
    private final MorphSpring sqBx = new MorphSpring(SQ_R);
    private final MorphSpring sqBy = new MorphSpring(SQ_R);
    private final MorphSpring sqK = new MorphSpring(SQ_P_K);
    private final MorphSpring sqDark = new MorphSpring(1f);
    private final MorphSpring sqCont = new MorphSpring(0f);

    private float sqDensity() {
        return Math.max(0.5f, getResources().getDisplayMetrics().density);
    }

    /** 演示页的 capsuleRect()（单位 CSS px / dp）。 */
    private float[] capsuleRect() {
        float sw = screenW / sqDensity();                       // 屏宽（dp）
        float w = sw < 600f ? sw - SQ_CAP_MARGIN : Math.min(SQ_CAP_MAX_W, sw * 0.62f);
        float h = sw < 600f ? searchCapsH : Math.max(searchCapsH, 56f);   // 高度跟着输入框行数走
        return new float[]{ sw / 2f, SQ_EDGE + (sw < 600f ? SQ_CAP_TOP : 96f), w, h };
    }

    /**
     * 输入框报上来的「我需要多高」（屏幕像素）→ 胶囊高度目标。
     * 只在胶囊落定（CAPSULE 阶段）时改弹簧目标；进场/出场途中不动，免得和 morph 打架。
     */
    void onSearchInputHeight(int px) {
        float d = sqDensity();
        if (d <= 0f) return;
        float h = (px <= 0) ? SQ_CAP_H : Math.max(SQ_CAP_H, Math.min(searchCapsHMaxDp(), px / d));
        if (Math.abs(h - searchCapsH) < 0.5f) return;
        searchCapsH = h;
        // 只有**胶囊已经落定**时才改高度目标 —— 进场/出场动画中途改会让弹簧和窗口打架
        //（用户 2026-11-06：电源键连按两下时输入框形状一度变得很怪）。
        if (!searchActive || !searchOpen) return;
        sqBy.to(searchCapsH * 0.5f);
        Log.i("FloatingBall", "search: 输入框要 " + px + "px → 胶囊高 " + Math.round(searchCapsH) + "dp");
    }

    /** 演示页的 setMorphSprings()：四个形状弹簧都换成 P.om/P.ze。 */
    private void sqSetMorphSprings() {
        sqCx.cfg(SQ_OM, SQ_ZE);
        sqCy.cfg(SQ_OM, SQ_ZE);
        sqBx.cfg(SQ_OM, SQ_ZE);
        sqBy.cfg(SQ_OM, SQ_ZE);
    }

    /** 搜索框是不是开着（设置页 / 调试脚本读）。 */
    public static boolean isSearchBoxOpen() {
        FloatingBallService s = INSTANCE;
        return s != null && (s.searchActive || s.searchClosing);
    }

    public static boolean isSearchBoxTyping() {
        FloatingBallService s = INSTANCE;
        return s != null && s.searchOpen;
    }

    /** 从外面（设置页按钮 / 文件钩子）把搜索层叫出来。 */
    public static void openSearchBox(String why) {
        final FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() { s.enterSearchMode("外部：" + why); }
        });
    }

    public static void closeSearchBox(String why) {
        final FloatingBallService s = INSTANCE;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override public void run() { s.closeSearchMode("外部：" + why); }
        });
    }

    /**
     * 唤起：**球隐藏** → 小黑球出现在球原来的球心 → 弹开成胶囊 → 输入行凝聚 + 键盘。
     *
     * 演示页的对应关系：pointerdown 建液滴（跟手弹簧 18/0.95），pointerup 之后
     * setMorphSprings() 换成（11/0.72）并朝 capsuleRect 弹。我们这里没有"拖"这一段，
     * 所以：长按 = 液滴出生（DRAG 材质：深黑 + k=64），SQ_DROP_MS 之后自动"松手"。
     */
    void enterSearchMode(String why) {
        enterSearchMode(why, "", true, BAR_SEND);
    }

    /**
     * 状态 2 的入口（带内容与焦点策略）：
     *   text      —— 语音打断留下的文字（要"保留在输入框里"）
     *   autoFocus —— false = 只显示、不弹键盘（用户单击才进入编辑）
     *   purpose   —— BAR_SEND（发消息）/ BAR_SEARCH（搜索会话，等于前端 bhn1Oq_searchButton）
     */
    void enterSearchMode(String why, String text, boolean autoFocus, int purpose) {
        if (searchActive || searchClosing) return;
        if (uiHidden) { Log.i("FloatingBall", "search: 界面被音量加藏起来了 → 先不弹胶囊"); return; }
        if (approvalShown || approvalClosing) {
            Log.i("FloatingBall", "search: 审批球在屏上，先不开搜索框");
            return;
        }
        if (wm == null || glView == null || params == null || renderer == null) return;
        // ★ 2026-11-07：**状态 3 / 玻璃菜单 还开着就不能直接开胶囊**。
        //   以前这里没有互斥：zoneMode 和 searchMode 同时为真时，GL 先判 zoneMode，
        //   画的是六区那一层 —— 屏幕上胶囊的玻璃**整个不见了**，只剩顶部那行
        //   "Search or Ask" 文字和旁边一颗黑圆。用户报的「搜索框自己变成图 2」就是这个
        //   （六区菜单开着时按电源键 / 语音 / 点球，都会走到这里）。
        if (zonesActive) {
            Log.i("FloatingBall", "search: 状态 3 还开着 → 先退出状态 3 再开胶囊");
            exitZones();
        }
        if (menuShown) {
            Log.i("FloatingBall", "search: 玻璃菜单还开着 → 先关菜单再开胶囊");
            hideMenu("开胶囊");
        }
        if (!siriEnabled) applySiriEnabled(true);   // 窗口/节拍那条路要活着（球本身不参与渲染）
        if (!siriEnabled) return;
        searchActive = true;
        searchClosing = false;
        searchOpen = false;
        barPurpose = purpose;
        barInitialText = text == null ? "" : text;
        barAutoFocus = autoFocus;
        sqPhase = 1;
        final long gen = ++searchGen;
        forceHideAllText();                          // 球内文字先藏掉（siriStr 留着，出来还在）
        // 会话面那条输入行是**另一个窗口**：不藏起来就会和搜索胶囊同时出现在屏幕上
        // （用户 2026-11-06：「聊天界面里连按两下，输入框显示有问题」）。
        // 现在可见性统一由 syncComposerBar() 判（searchActive 为真 → 一律收起）。
        if (composerShown) {
            convoBarHiddenBySearch = true;
            composerShown = false;
            if (convoBar != null) convoBar.setVisibility(View.GONE);
            Log.i("FloatingBall", "search: 先藏起会话面的输入行（避免两条输入框叠着）");
        }
        applyBallTouchability();                     // 搜索期间屏幕归搜索层
        // ── 记下球此刻的屏幕球心（小黑球从这儿出生）── 必须在窗口改尺寸之前算
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        sqDropCx = loc[0] + winW * 0.5f;
        sqDropCy = loc[1] + winH * 0.5f;
        float d = sqDensity();
        // ── 窗口铺满整屏：演示页是整屏 demo，这一层要能画到顶部黑边与整个胶囊 ──
        glassBufW = glassBufH = -1;
        try { glView.getHolder().setFixedSize(0, 0); } catch (Throwable ignored) { }
        winW = screenW;
        winH = screenH;
        params.width = screenW;
        params.height = screenH;
        params.x = 0;
        params.y = -Math.round(winY0());
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (renderer != null) { renderer.winPixelW = screenW; renderer.winPixelH = screenH; }
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        glView.post(winSync);
        // ── 液滴初值：演示页 pointerdown 那一段（cx/cy 跟手 18/0.95，bx/by 用 P 的值）──
        sqCx.jump(sqDropCx / d);
        sqCy.jump(sqDropCy / d);
        sqBx.jump(SQ_R);
        sqBy.jump(SQ_R);
        sqK.jump(SQ_P_K);
        sqDark.jump(1f);
        sqCont.jump(0f);
        sqCx.cfg(18f, 0.95f);
        sqCy.cfg(18f, 0.95f);
        sqBx.cfg(SQ_OM, SQ_ZE);
        sqBy.cfg(SQ_OM, SQ_ZE);
        if (renderer != null) {
            renderer.searchMode = true;              // 球整趟让路：这一帧画的是演示页那一层
            renderer.sValid = 1f;
            renderer.sDensity = d;
        }
        searchCapsH = SQ_CAP_H;                      // 每次进场都从演示页的标准胶囊高开始
        ensureSearchWindows();
        searchArmAt = android.os.SystemClock.uptimeMillis() + 900;
        Log.i("FloatingBall", "search: 进入（" + why + "）小黑球 @(" + Math.round(sqDropCx)
                + "," + Math.round(sqDropCy) + ") 球已隐藏");
        main.postDelayed(new Runnable() {
            @Override public void run() { sqToCapsule(gen); }
        }, SQ_DROP_MS);
    }

    /** 演示页 pointerup 那一支：换成 P 的弹簧、朝 capsuleRect 弹，材质深黑→浅、k 收小、容器淡入。 */
    private void sqToCapsule(final long gen) {
        if (gen != searchGen || !searchActive || sqPhase != 1) return;
        sqPhase = 2;
        searchCapsH = SQ_CAP_H;          // 落定先回到原版高度；有字要换行时再由输入框回调拉高
        sqSetMorphSprings();
        float[] c = capsuleRect();
        sqCx.to(c[0]);
        sqCy.to(c[1]);
        sqBx.to(c[2] * 0.5f);
        sqBy.to(c[3] * 0.5f);
        sqDark.to(0f);
        sqK.to(10f);
        sqCont.to(1f);
        Log.i("FloatingBall", "search: 小黑球弹开成胶囊 cx=" + Math.round(c[0] * sqDensity())
                + " cy=" + Math.round(c[1] * sqDensity())
                + " 半宽/半高=" + Math.round(c[2] * 0.5f * sqDensity())
                + "/" + Math.round(c[3] * 0.5f * sqDensity()));
        searchOpen = true;
        if (searchBar != null) {
            searchBar.setVisibility(View.VISIBLE);
            searchBar.setHint(barPurpose == BAR_SEARCH ? "搜索会话" : "Search or Ask");
            searchBar.setText(barInitialText);       // 语音识别结果留在输入框里
            searchBar.setAttachmentName(pendingAttachName);
            searchBar.materialize(true, 0);          // 演示页 #search.appear：从模糊里凝聚（0.55s）
        }
        if (barAutoFocus) {
            if (searchVoiceMode) focusSearchInputForVoice();   // 按住说话：拿焦点、不弹键盘
            else focusSearchInput();
        } else {
            // 不抢焦点：这样"单击一下"才等于"进入编辑"（状态 2 的语法）
            main.removeCallbacks(searchFocusTask);
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (searchBar == null) return;
                    searchBar.requestFocus();
                    searchBar.blurInput();
                    Log.i("FloatingBall", "search: 已落定（不弹键盘，等单击进入编辑）");
                }
            }, 240);
        }
    }

    /** 演示页 retract()：被吸回顶部黑边（cy → EDGE*0.3、bx/by → 1、材质回深黑、容器淡出）。 */
    void closeSearchMode(String why) {
        if (!searchActive || searchClosing) return;
        searchClosing = true;
        searchOpen = false;
        sqPhase = 3;
        final long gen = ++searchGen;
        imeWaitGen++;                    // 作废还在等的「轮询输入法结果」（别在关掉之后弹"没听清"）
        // ★ 胶囊被关掉（点空白 / 返回键 / 审批弹出来 / 音量加隐藏…）→ 这一次「按住说话」就结束了。
        //   以前这里不清 hold 状态：powerHoldActive 一直挂着 → 下一次长按电源键会被当成
        //   「第二次按下」去结束一个根本不存在的语音（表现为"按了没反应 / 语音不起来"），
        //   输入法的语音也还停在按住状态（键盘隐身不恢复）。
        if (powerHoldActive) {
            powerHoldActive = false;
            powerHoldDown = false;
            stopVoiceAliveWatch();
            try { vm().trigger("up"); } catch (Throwable ignored) { }
            Log.i("FloatingBall", "search: 收起胶囊 → 同时结束「按住说话」（清 hold 状态）");
        }
        Log.i("FloatingBall", "search: 退出（" + why + "）");
        if (searchBar != null) {
            searchBar.materialize(false, 0);
        }
        hideSearchIme();
        try { vm().cmd("stealthoff"); } catch (Throwable ignored) { }   // 万一还停在隐身态
        searchVoiceMode = false;
        stopVoiceFocusGuard();
        main.postDelayed(new Runnable() {
            @Override public void run() { removeSearchWindows(); }
        }, 170);
        sqSetMorphSprings();
        sqCy.to(SQ_EDGE * 0.3f);
        sqBx.to(1f);
        sqBy.to(1f);
        sqDark.to(1f);
        sqK.to(SQ_P_K);
        sqCont.to(0f);
        // 收尾由 searchStep 判：演示页是 `if (state === 'RETRACT' && sp.by.x < 2.5) state = 'IDLE'`
    }

    /** 每帧推进（siriTick 在 searchActive 时调用）：走演示页那套弹簧，然后把值写进 renderer。 */
    private void searchStep(float dt) {
        sqCx.step(dt); sqCy.step(dt); sqBx.step(dt); sqBy.step(dt);
        sqK.step(dt); sqDark.step(dt); sqCont.step(dt);
        float d = sqDensity();
        // 兜底：搜索层这一帧必须铺满整屏。任何路径（新加的、异步晚到的、ROM 自己摆的）
        // 把玻璃窗改小了，这里当场掰回来 —— 用户报的「搜索框待久了显示出问题」
        // 根子在窗口被别人的球几何打回 793×579，这一条保证就算再漏一个口子也立刻自愈。
        if (wm != null && glView != null && (winW != screenW || winH != screenH)) {
            Log.w("FloatingBall", "search: 玻璃窗被改成 " + winW + "x" + winH + "（应为 "
                    + screenW + "x" + screenH + "），当场掰回整屏");
            winW = screenW; winH = screenH;
            params.width = screenW;
            params.height = screenH;
            params.x = 0;
            params.y = -Math.round(winY0());
            if (renderer != null) { renderer.winPixelW = screenW; renderer.winPixelH = screenH; }
            try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
            glView.post(winSync);
        }
        if (renderer != null) {
            renderer.searchMode = true;
            renderer.sBlobCx = sqCx.x * d;
            renderer.sBlobCy = sqCy.x * d;
            renderer.sBlobHx = sqBx.x * d;
            renderer.sBlobHy = sqBy.x * d;
            renderer.sK = sqK.x * d;
            renderer.sHeight = SQ_P_HEIGHT * d;
            renderer.sRefract = SQ_P_REFRACT * d;
            renderer.sHlAmt = SQ_P_HL;
            renderer.sAb = SQ_P_AB;
            renderer.sDark = Math.max(0f, Math.min(1f, sqDark.x));
            renderer.sCont = Math.max(0f, Math.min(1f, sqCont.x)) * SQ_P_CONT;
            renderer.sValid = 1f;
        }
        if (sqPhase == 3 && sqBy.x < 2.5f) {          // 演示页的回落判据
            finishSearchClose(searchGen);
            return;
        }
        if (menuShown) pushMenuScene();                // 面板开着时胶囊仍在弹：每帧把两个形状都递过去
        updateSearchWindows();
    }

    /**
     * 输入行窗口跟着弹簧矩形走（演示页是每帧设 DOM 的 left/top/width/height，一模一样）。
     * 只在真的变了才 updateViewLayout（整屏窗口那个教训：别每帧跨进程摆窗）。
     */
    private void updateSearchWindows() {
        if (!searchActive || wm == null || searchBar == null || searchBarParams == null) return;
        float d = sqDensity();
        int bw = Math.max(2, Math.round(2 * sqBx.x * d));
        int bh = Math.max(2, Math.round(2 * sqBy.x * d));
        int bx = Math.round(sqCx.x * d - bw * 0.5f);
        int by = Math.round(sqCy.x * d - bh * 0.5f - winY0());
        if (Math.abs(bw - sqBarW) <= 2 && Math.abs(bh - sqBarH) <= 2
                && Math.abs(bx - sqBarX) <= 2 && Math.abs(by - sqBarY) <= 2) return;
        sqBarW = bw; sqBarH = bh; sqBarX = bx; sqBarY = by;
        searchBarParams.width = bw;
        searchBarParams.height = bh;
        searchBarParams.x = bx;
        searchBarParams.y = by;
        try { wm.updateViewLayout(searchBar, searchBarParams); } catch (Throwable ignored) { }
    }

    private void finishSearchClose(long gen) {
        if (gen != searchGen) return;
        Log.i("FloatingBall", "search: 收回到顶部黑边，交还给球");
        searchCapsH = SQ_CAP_H;                        // 下次进场从标准高度开始
        if (convoBarHiddenBySearch) {                  // 输入行交还给 syncComposerBar 重新判
            convoBarHiddenBySearch = false;
            Log.i("FloatingBall", "search: 收回 → 输入行交给 syncComposerBar 重新判");
        }
        searchActive = false;                          // 先交还几何通道，下面立刻把窗口收回球窗
        glassFullscreen = false;                       // ★ 面板标志不清，updateSiriWindow 会一直早退 → 球窗停在全屏、样式全变
        if (renderer != null) { renderer.searchMode = false; renderer.sValid = 0f; }
        float base = winSize * 0.9f;
        tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
        aMaster = 1f; aHalfX = minHalfX; aHalfY = minHalfY; aCorner = minCorner; aBlack = 0f;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED;
        updateSiriWindow(base * minHalfX, base * minHalfY);   // 窗口从整屏收回球窗
        measureSiriTargets(siriStr);                         // 有字就按字重排（方形态），没字就是圆球
        clampBallOnScreen();
        centerBallOnScreen();
        // 胶囊开着期间到的「最终回复该收回」→ 这时才补做（窗口已经交还给球了，收得动、
        // 也不会把整屏搜索层裁掉）。没这条待办的话，球会一直顶着方形态到下一个兜底。
        if (holdReleasePending) {
            holdReleasePending = false;
            Log.i("FloatingBall", "search: 收起 → 补做胶囊期间欠下的「最终回复收回」");
            releaseTextHold();
        }
        // 代理触摸窗口这时可能还是"整屏大小"：先别让它接手，等窗口真的收完再放行
        main.postDelayed(new Runnable() {
            @Override public void run() {
                searchClosing = false;
                applyBallTouchability();
            }
        }, 180);
    }

    /** 演示页的搜索行（🔍 输入框）+ 一块全屏"点空白关掉"层。 */
    private void ensureSearchWindows() {
        if (wm == null) return;
        if (searchOutside == null) {
            View v = new View(this);
            v.setBackgroundColor(0x00000000);
            v.setClickable(true);
            searchOutsideParams = overlayParams(screenW, screenH,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            searchOutsideParams.x = 0;
            searchOutsideParams.y = -Math.round(winY0());     // 铺满整屏（含状态栏那一带）
            searchOutsideParams.softInputMode =
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            // 「点空白处关掉」要防两个坑（实测踩过第一个）：
            //  ① **唤起那串手势的尾巴**：手指还按着的时候这一层就挂上了，代理窗口一让路，
            //     同一串手势剩下的 ACTION_UP 就落到这里 —— 表现为"搜索框刚出现就自己关掉"。
            //     所以只认**从本层自己 DOWN 开始**的那一串（sawDown）。
            //  ② 手指抬起有先后 → 不在 ACTION_POINTER_UP 上判定，只看最后一个 ACTION_UP。
            v.setOnTouchListener(new View.OnTouchListener() {
                boolean sawDown = false;
                @Override public boolean onTouch(View view, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            sawDown = true;
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!sawDown) return true;
                            sawDown = false;
                            if (android.os.SystemClock.uptimeMillis() < searchArmAt) return true;
                            float[] c = capsuleRect();
                            float d = sqDensity();
                            float dx = Math.abs(e.getRawX() - c[0] * d) / Math.max(1f, c[2] * 0.5f * d);
                            float dy = Math.abs(e.getRawY() - c[1] * d) / Math.max(1f, c[3] * 0.5f * d);
                            Log.i("FloatingBall", "search: 全屏层收到点击 (" + Math.round(e.getRawX())
                                    + "," + Math.round(e.getRawY()) + ") 胶囊外=" + (dx > 1f || dy > 1f));
                            if (dx > 1f || dy > 1f) closeSearchMode("点空白处");
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            sawDown = false;
                            return true;
                        default:
                            return true;
                    }
                }
            });
            try { wm.addView(v, searchOutsideParams); searchOutside = v; trackWindow(v); }
            catch (Throwable t) { Log.w("FloatingBall", "add search outside failed: " + t); }
        }
        if (searchBar == null) {
            BallSearchBar bar = new BallSearchBar(this);
            bar.setListener(new BallSearchBar.Listener() {
                @Override public void onSubmit(String text) { submitSearch(text); }

                @Override public void onTapEdit() {
                    // 状态 2 的单击：**结束语音识别 + 进入编辑（弹键盘）**。
                    // 这是用户 2026-11-06 要的第三条"结束语音"的路径（另外两条是音量减、
                    // 再长按电源键，那两条会**发出去**；这一条只结束、字留在框里接着手打）。
                    endVoiceByTap();
                    focusSearchInputNow();
                    if (searchBar != null) searchBar.focusInput();
                    Log.i("FloatingBall", "search: 单击 → 结束语音并进入编辑");
                }

                @Override public void onLongPressAdd() {
                    // 长按 = 添附件（等价于前端输入框的 uV2eYG_add）
                    Log.i("FloatingBall", "search: 长按 → 添附件");
                    vibrate(18);
                    pickAttachment();
                }

                @Override public void onTwoFingerHold() {
                    // 双指长按 = Agent 预设 / 模型 / 推理等级 / 权限
                    Log.i("FloatingBall", "search: 双指长按 → 参数弹窗");
                    vibrate(28);
                    hideSearchIme();
                    openSettingsMenu();
                }

                @Override public void onAttachmentTap() {
                    clearAttachment();
                    if (convoBar != null) convoBar.setHint("发消息…");
                    toast("已移除附件");
                }

                @Override public void onTargetTap() {
                    // 点「上个对话 / 新会话」小标签：存下来，电源键 / 音量减也跟着这个设置走
                    boolean want = searchBar != null && searchBar.isTargetNewSession();
                    setSendNewSession(want);
                    toast(want ? "发送到：新会话（新建）" : "发送到：上一个对话（当前会话）");
                }

                @Override public void onInputHeight(int px) { onSearchInputHeight(px); }
            });
            bar.setTargetNewSession(sendToNewSession());
            // 故意**不带** FLAG_NOT_FOCUSABLE：悬浮窗能拿焦点，EditText 才收得到输入法
            // （审批面板那三个坑同源，见 HANDOFF「自由输入」一节）。
            searchBarParams = overlayParams(2, 2,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            searchBarParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            bar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    // ⚠ 语音期间**不许**补弹键盘：胶囊一拿到窗口焦点这里就会 showSoftInput，
                    //   而那时 Voicemap 的隐身还没生效 → 屏幕上就是"先弹一个真键盘、再被隐藏"。
                    if (!hasFocus || !searchActive || !searchOpen) return;
                    if (searchVoiceMode) {
                        // ★ 容器自己 setFocusableInTouchMode(true)（为了吃返回键）：窗口焦点一来，
                        //   系统会把焦点塞给它、而不是 EditText —— 输入法于是"没有编辑框"
                        //   （getCurrentInputConnection()=null），语音识别的字无处上屏。
                        //   这里一发现容器抢到焦点就立刻还给输入框，键盘仍然不弹。
                        Log.i("FloatingBall", "voice-focus: 容器抢到焦点 → 还给输入框（不弹键盘）");
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (!searchActive || !searchVoiceMode || searchBar == null) return;
                                try {
                                    searchBar.input().setFocusableInTouchMode(true);
                                    searchBar.input().requestFocus();
                                } catch (Throwable ignored) { }
                            }
                        });
                        return;
                    }
                    main.postDelayed(new Runnable() {
                        @Override public void run() { showSearchIme(); }
                    }, 80);      // 窗口焦点会被系统抢走，拿到就补一次
                }
            });
            // 语音期间把 EditText 的焦点进出记下来（排障用：没有焦点 = 输入法没编辑框）
            bar.input().setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    if (searchActive && searchVoiceMode) {
                        Log.i("FloatingBall", "voice-focus: EditText focus=" + hasFocus
                                + " barF=" + (searchBar != null && searchBar.hasFocus())
                                + " winF=" + (searchBar != null && searchBar.hasWindowFocus()));
                    }
                }
            });
            // 返回键：键盘弹着时第一下由输入法吃掉（收键盘），第二下才轮到我们 → 收起胶囊。
            // ⚠️ 三个钩子都要挂（实测只挂第一个收不到）：OnKeyListener 只在"这个 View 自己
            // 拿着焦点"时回调，而焦点在 EditText 上；而悬浮窗**没有 Activity** —— 没人处理的
            // 按键走不到 Activity.onKeyDown，只能靠 addOnUnhandledKeyEventListener（API 28+）。
            final android.view.View.OnKeyListener backEater = new android.view.View.OnKeyListener() {
                @Override public boolean onKey(View v, int code, android.view.KeyEvent ev) {
                    if (code == android.view.KeyEvent.KEYCODE_BACK
                            && ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                        closeSearchMode("返回键");
                        return true;
                    }
                    return false;
                }
            };
            bar.setFocusableInTouchMode(true);
            bar.setOnKeyListener(backEater);
            bar.input().setOnKeyListener(backEater);
            if (Build.VERSION.SDK_INT >= 28) {
                bar.addOnUnhandledKeyEventListener(new View.OnUnhandledKeyEventListener() {
                    @Override public boolean onUnhandledKeyEvent(View v, android.view.KeyEvent ev) {
                        return backEater.onKey(v, ev.getKeyCode(), ev);
                    }
                });
            }
            bar.setVisibility(View.INVISIBLE);
            try {
                wm.addView(bar, searchBarParams);
                trackWindow(bar);
                searchBar = bar;
                Log.i("FloatingBall", "search: 输入行窗口已建");
            } catch (Throwable t) {
                Log.w("FloatingBall", "add search bar failed: " + t);
            }
        }
    }

    private void removeSearchWindows() {
        hideSearchIme();
        if (searchBar != null) {
            searchBar.resetMaterial();
            searchBar.setVisibility(View.INVISIBLE);
        }
        if (searchBar != null && wm != null) {
            try { wm.removeView(searchBar); } catch (Throwable ignored) { }
            searchBar = null;
            searchBarParams = null;
            sqBarX = Integer.MIN_VALUE;
        }
        if (searchOutside != null && wm != null) {
            try { wm.removeView(searchOutside); } catch (Throwable ignored) { }
            searchOutside = null;
            searchOutsideParams = null;
        }
    }

    /** 「胶囊自己弹出来」时那一路：保持原样（240ms 之后要一次焦点），别变成自动弹键盘。 */
    private void focusSearchInput() {
        main.removeCallbacks(searchFocusTask);
        main.postDelayed(searchFocusTask, 240);   // 抬手之后再要焦点（键盘一弹窗口会动）
    }

    /**
     * 「按住说话」期间：输入框拿焦点，但**不让系统弹键盘**。
     *
     * 为什么要焦点：Voicemap 是注入在输入法进程里的模块，识别出来的字是通过输入连接
     * 塞进**当前焦点**的 EditText 的 —— 没焦点就塞不进来。
     * 为什么不弹键盘：键盘由 Voicemap 自己 `requestShowSelf()` 拉起来，并且它会在
     * `down` 的那一刻把输入法窗口 alpha 设成 0（隐身）。我们先 showSoftInput 的话，
     * 顺序就反了：先弹出一个真键盘、再被隐身 —— 屏幕上就是"闪一下又没了"。
     */
    private void focusSearchInputForVoice() {
        if (searchBar == null || !searchActive) return;
        try {
            searchBar.input().setShowSoftInputOnFocus(false);   // 拿焦点也不弹键盘
            Log.i("FloatingBall", "voice-focus: 进入 barF=" + searchBar.hasFocus()
                    + " imF=" + searchBar.input().hasFocus()
                    + " winF=" + searchBar.hasWindowFocus());
            // ⚠ 别让胶囊容器先拿焦点：它自己 setFocusableInTouchMode(true)（为了吃返回键），
            //   先 requestFocus() 会把焦点从 EditText 拿走 —— 输入法就成了"服务一个 LinearLayout"，
            //   getCurrentInputConnection() 是 null，语音识别出来的字没地方上屏（用户报的
            //   「长按电源键说话，输入框里一个字都没有」）。窗口焦点是系统给的，不靠这一句。
            searchBar.focusInput();
            Log.i("FloatingBall", "voice-focus: 之后 barF=" + searchBar.hasFocus()
                    + " imF=" + searchBar.input().hasFocus()
                    + " winF=" + searchBar.hasWindowFocus());
            startVoiceFocusGuard();
        } catch (Throwable t) {
            Log.w("FloatingBall", "voice focus failed: " + t);
        }
    }

    // ───────── 按住说话期间：输入框焦点看守 ─────────
    // 输入法的识别结果是通过**当前焦点视图的 InputConnection** 上屏的（Voicemap 只是
    // 在输入法进程里按住空格）。胶囊窗口一拿到/丢掉窗口焦点、输入法窗口隐身地 showWindow
    // 的那一下，焦点都可能被系统塞回容器（BallSearchBar）——一旦那样，输入法就"没有编辑框"，
    // 说话全白说。这里在语音期间盯着，一掉就悄悄要回来，绝不动键盘可见性。
    private final Runnable voiceFocusGuard = new Runnable() {
        @Override public void run() {
            if (!searchActive || !searchVoiceMode || searchBar == null) return;
            boolean held;
            try {
                held = searchBar.input().hasFocus();
            } catch (Throwable t) {
                return;
            }
            if (!held) {
                try {
                    searchBar.input().setFocusableInTouchMode(true);
                    boolean ok = searchBar.input().requestFocus();
                    Log.i("FloatingBall", "voice-focus: 焦点掉了 → 要回来 ok=" + ok
                            + " imF=" + searchBar.input().hasFocus()
                            + " barF=" + searchBar.hasFocus()
                            + " winF=" + searchBar.hasWindowFocus());
                } catch (Throwable ignored) { }
            }
            main.postDelayed(this, held ? 600 : 250);
        }
    };

    private void startVoiceFocusGuard() {
        main.removeCallbacks(voiceFocusGuard);
        main.postDelayed(voiceFocusGuard, 300);
    }

    private void stopVoiceFocusGuard() {
        main.removeCallbacks(voiceFocusGuard);
    }

    /**
     * **手点输入框**那一路：单击就要快。
     *
     * 以前单击走的也是 {@link #focusSearchInput()}：手指抬起 240ms 后才把 EditText 变成可聚焦、
     * 才 `showSoftInput` —— 观感就是"点一下没反应、再点一下键盘才出来"（用户 2026-11-06 报的慢）。
     * 现在立刻来一次，再在 120ms / 320ms 各补一刀：悬浮窗没有 Activity，`showSoftInput`
     * 偶尔会被系统吞一次（窗口焦点还没拿到）。补的时候只在"输入法还没连上这个输入框"时才动作，
     * 已经弹出来就什么都不做 —— 不会重复弹。
     *
     * ⚠ 只给"点输入框"用：胶囊自己弹出来（按住说话）那条路仍然只要一次焦点，
     * 不主动把键盘顶出来（用户要求「只能手动弹出」）。
     */
    private void focusSearchInputNow() {
        main.removeCallbacks(searchFocusTask);
        main.post(searchFocusTask);
        main.postDelayed(searchFocusTask, 120);
        main.postDelayed(searchFocusTask, 320);
    }

    private final Runnable searchFocusTask = new Runnable() {
        @Override public void run() { doFocusSearchInput(); }
    };

    private void doFocusSearchInput() {
        if (searchBar == null || !searchActive) return;
        try {
            boolean imeOn = false;
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imeOn = imm != null && imm.isActive(searchBar.input());
            } catch (Throwable ignored) { }
            if (imeOn && searchBar.input().hasFocus()) return;    // 已经连上输入法了，别重复弹
            searchBar.requestFocus();             // 先让窗口拿焦点
            searchBar.focusInput();               // 再让 EditText 拿焦点
            showSearchIme();
            Log.i("FloatingBall", "search: 请求输入焦点 winFocus=" + searchBar.hasWindowFocus()
                    + " imeActive=" + imeOn);
        } catch (Throwable t) {
            Log.w("FloatingBall", "search focus failed: " + t);
        }
    }

    private void showSearchIme() {
        if (searchBar == null) return;
        // 语音期间键盘只能由 Voicemap 以隐身方式（alpha=0）拉起来；我们这边一律不主动弹。
        // 这是最后一道闸：不管哪条路调进来（焦点监听 / 附件回来 / 兜底），都不会让键盘可见。
        if (searchVoiceMode) {
            Log.i("FloatingBall", "search: 语音期间不主动弹键盘（showSoftInput 被拦下）");
            return;
        }
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) return;
            imm.showSoftInput(searchBar.input(), 0);
        } catch (Throwable t) {
            Log.w("FloatingBall", "search ime failed: " + t);
        }
    }

    private void hideSearchIme() {
        if (searchBar == null) return;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    /**
     * 键盘回车 / IME 发送（状态 2 的"回车"）：
     *   · BAR_SEARCH → 按标题过滤会话，结果用同一套液玻璃面板列出来；
     *   · BAR_SEND   → 有选中会话就发给它，否则按当前参数档（预设/模型/推理/权限/目录）
     *                  新建一条会话再发；带了附件就先把附件传上去换成 receiptId。
     * 发完/搜完都收起胶囊回初始状态（用户要求："发送完毕或者点击输入框以外的地方变回初始状态"）。
     */
    private void submitSearch(String text) {
        final String t = text == null ? "" : text.trim();
        Log.i("FloatingBall", "search: 回车 → " + (barPurpose == BAR_SEARCH ? "搜索会话「" : "发送「")
                + t + "」");
        if (barPurpose == BAR_SEARCH) {
            if (t.isEmpty()) return;
            runSessionSearch(t);
            return;
        }
        final File attach = pendingAttach;
        final String attachName = pendingAttachName;
        if (t.isEmpty() && attach == null) return;
        if (searchBar != null) {
            searchBar.setText("");
            searchBar.materialize(false, 0);
            searchBar.setAttachmentName(null);
        }
        closeSearchMode("发送");
        clearAttachment();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                sendFromBar(t, attach, attachName);
            }
        }, 480);            // 等胶囊收回去再发，文字才不会撞上搜索层的几何
    }

    // ── 三态手势机的调试口子（和 search-open / state-* 同一套：一次性文件，用完即删）──
    // 真机注入"长按不松手再滑到某个区"在当前工具链里做不到，所以把状态机每一步都开一个口子：
    //   zone-open         → 进状态 3（不按手指也显示四个圆区）
    //   zone-move         → 内容 tl/tr/bl/br：模拟手指悬停到对应区
    //   zone-release      → 松手（有悬停就执行，没有就回初始状态）
    //   bar-open          → 状态 2（发消息）
    //   bar-search        → 状态 2（搜索会话）：内容 = 初始文字
    //   bar-type          → 只填字（不发送）
    //   voice-tap         → 等价于"识别过程中被点"：停麦 + 转写 + 进状态 2
    //   menu-dirs / menu-settings / menu-search:<q> → 直接叫出对应玻璃菜单
    private long nextGestureCheck = 0;

    private void pumpGestureRequest() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextGestureCheck) return;
        nextGestureCheck = now + 120;
        File dir = gateDir();
        // files/witness → 取证三件套（玻璃层原图 + 状态快照；全屏截图由 tools/witness.sh 做）
        File fWitness = new File(dir, "witness");
        if (fWitness.exists()) {
            String tag = readSmallString(fWitness, 200);
            fWitness.delete();
            witnessCapture(tag == null ? "" : tag.trim());
        }
        File fShowGlass = new File(dir, "show-glass");
        if (fShowGlass.exists()) {
            fShowGlass.delete();
            debugShowGlass = true;
            // 已经打过标记的 Surface 要主动撤掉，否则这一趟还是拍不到玻璃
            if (glView != null) {
                try { skipScreenshotOf(glView.getSurfaceControl(), "glass", false); } catch (Throwable ignored) { }
            }
            renderer.selfExcluded = false;
            glassMarkedSc = null;          // 标记身份也一并作废（否则 ensure 会以为已经打过）
            Log.i("FloatingBall", "debug: 玻璃留在抓屏里");
        }
        File fHideGlass = new File(dir, "hide-glass");
        if (fHideGlass.exists()) {
            fHideGlass.delete();
            debugShowGlass = false;
            if (glView != null) {
                SurfaceControl sc = null;
                try { sc = glView.getSurfaceControl(); } catch (Throwable ignored) { }
                try { skipScreenshotOf(sc, "glass", true); } catch (Throwable ignored) { }
                renderer.selfExcluded = true;
                glassMarkedSc = sc;
                resetGlassBackground("hide-glass 钩子");
            }
            Log.i("FloatingBall", "debug: 玻璃重新排除出抓屏");
        }
        File fOpen = new File(dir, "zone-open");
        if (fOpen.exists()) {
            fOpen.delete();
            Log.i("FloatingBall", "zones: 文件钩子 zone-open");
            enterZones("文件钩子");
        }
        File fMove = new File(dir, "zone-move");
        if (fMove.exists()) {
            String t = readSmallString(fMove, 32).trim().toLowerCase();
            fMove.delete();
            int z = t.equals("tl") ? ZONE_TL : t.equals("tr") ? ZONE_TR
                    : t.equals("bl") ? ZONE_BL : t.equals("br") ? ZONE_BR
                    : t.equals("t") ? ZONE_TOP : t.equals("b") ? ZONE_BOTTOM : -1;
            if (z >= 0) {
                updateHoldTouch(zoneCx[z], zoneCy[z]);
            } else if (t.contains(",")) {
                // `x,y` = 直接把手指坐标喂给状态机（验证卡片滚动/选行用）
                try {
                    String[] xy = t.split(",");
                    updateHoldTouch(Float.parseFloat(xy[0].trim()), Float.parseFloat(xy[1].trim()));
                } catch (Throwable e) {
                    Log.i("FloatingBall", "zones: zone-move 坐标解析失败：" + t);
                }
            } else {
                Log.i("FloatingBall", "zones: zone-move 内容不认识：" + t);
            }
        }
        File fUp = new File(dir, "zone-release");
        if (fUp.exists()) {
            fUp.delete();
            finishZones(false);
        }
        File fBar = new File(dir, "bar-open");
        if (fBar.exists()) {
            fBar.delete();
            enterSearchMode("文件钩子 bar-open", "", false, BAR_SEND);
        }
        File fSearch = new File(dir, "bar-search");
        if (fSearch.exists()) {
            String t = readSmallString(fSearch, 500).trim();
            fSearch.delete();
            enterSearchMode("文件钩子 bar-search", t, true, BAR_SEARCH);
        }
        File fType = new File(dir, "bar-type");
        if (fType.exists()) {
            final String t = readSmallString(fType, 2000).trim();
            fType.delete();
            if (!searchActive) enterSearchMode("文件钩子 bar-type", t, true, BAR_SEND);
            else if (searchBar != null) {
                main.postDelayed(new Runnable() {
                    @Override public void run() { if (searchBar != null) searchBar.setText(t); }
                }, 200);
            }
        }
        // 假回复：`echo 文字 > files/reply-text`（内容 = 要灌给球的那段字）——
        // 验证「菜单 / 搜索胶囊 / 输入胶囊里不该出现 AI 回复的字」时用，不用等真的跑一轮对话。
        File fReply = new File(dir, "reply-text");
        if (fReply.exists()) {
            String t = readSmallString(fReply, 4000);
            fReply.delete();
            if (t == null || t.trim().isEmpty()) t = "测试：AI 回复的文字（菜单里不该出现它）";
            final String txt = t.trim();
            Log.i("FloatingBall", "debug: 灌一段假回复字（" + txt.length() + " 字）");
            main.post(new Runnable() { @Override public void run() { applySiriText(txt); } });
        }
        File fTap = new File(dir, "voice-tap");
        if (fTap.exists()) {
            fTap.delete();
            if (voice != null && voice.listening()) voice.tapStop();
            else Log.i("FloatingBall", "voice-tap：现在没在听");
        }
        File fAttach = new File(dir, "convo-attach");
        if (fAttach.exists()) {
            String pth = readSmallString(fAttach, 500).trim();
            fAttach.delete();
            File f = new File(pth);
            if (f.exists()) {
                pendingAttach = f;
                pendingAttachName = f.getName();
                if (searchBar != null) searchBar.setAttachmentName(pendingAttachName);
                if (convoBar != null) convoBar.setAttachmentName(pendingAttachName);
                Log.i("FloatingBall", "attach: 文件钩子挂上 " + pth + " (" + f.length() + "B)");
            } else {
                Log.w("FloatingBall", "attach: 文件不存在 " + pth);
            }
        }
        File fConvo = new File(dir, "convo-send");
        if (fConvo.exists()) {
            String t = readSmallString(fConvo, 500).trim();
            fConvo.delete();
            if (convoSessionId != null && !t.isEmpty()) convoSend(convoSessionId, t);
        }
        File fDirs = new File(dir, "menu-dirs");
        if (fDirs.exists()) {
            fDirs.delete();
            openDirsMenu();
        }
        File fSet = new File(dir, "menu-settings");
        if (fSet.exists()) {
            fSet.delete();
            openSettingsMenu();
        }
        // convo-latest → 直接打开最近一条会话（验证"切回对话"那张卡的玻璃/排版用；
        // 真机上切回对话只能靠长按卡片选，纯脚本很难卡准）
        // convo-open → 打开**指定**会话（文件内容 = sessionId 或标题里的一段；
        // 也支持 `session-xxx` 前缀匹配）。给"要看某一条带表格的会话"取证用。
        File fConvoOpen = new File(dir, "convo-open");
        if (fConvoOpen.exists()) {
            final String want = readSmallString(fConvoOpen, 200).trim();
            fConvoOpen.delete();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        DshApi api = DshApi.get(FloatingBallService.this);
                        DshApi.SessionRow hit = null;
                        for (DshApi.SessionRow r : api.sessions()) {
                            if (r.id.equals(want) || r.id.startsWith(want)
                                    || (r.title != null && r.title.contains(want))) { hit = r; break; }
                        }
                        if (hit == null) { Log.w("FloatingBall", "convo-open: 找不到 " + want); return; }
                        final DshApi.SessionRow r = hit;
                        main.post(new Runnable() {
                            @Override public void run() { openConversation(r.id, r.title); }
                        });
                        Log.i("FloatingBall", "convo-open: 打开「" + r.title + "」" + r.id);
                    } catch (Throwable t) {
                        Log.w("FloatingBall", "convo-open 失败 " + message(t));
                    }
                }
            }, "convo-open").start();
        }
        File fConvoLatest = new File(dir, "convo-latest");
        if (fConvoLatest.exists()) {
            fConvoLatest.delete();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        DshApi api = DshApi.get(FloatingBallService.this);
                        final DshApi.SessionRow r = api.sessions().isEmpty() ? null : api.sessions().get(0);
                        if (r == null) { Log.w("FloatingBall", "convo-latest: 没有会话"); return; }
                        main.post(new Runnable() {
                            @Override public void run() { openConversation(r.id, r.title); }
                        });
                        Log.i("FloatingBall", "convo-latest: 打开「" + r.title + "」" + r.id);
                    } catch (Throwable t) {
                        Log.w("FloatingBall", "convo-latest 失败 " + message(t));
                    }
                }
            }, "convo-latest").start();
        }
        File fSearchMenu = new File(dir, "menu-search");
        if (fSearchMenu.exists()) {
            String q = readSmallString(fSearchMenu, 200).trim();
            fSearchMenu.delete();
            runSessionSearch(q);
        }
    }

    // ── 搜索层的调试口子（和 state-* / ballshot 同一套：一次性文件，用完即删）──
    //   files/search-open   → 把搜索层叫出来（不必做双指长按）
    //   files/search-close  → 收回去
    //   files/search-type   → 文件内容填进输入框（不发送）
    //   files/search-ask    → 文件内容 = 要发的文本（等价于"打字 + 回车"）
    // 为什么需要：真机注入双指长按/输入法在当前工具链里做不到，版式和发送链路必须能脚本化回归。
    private long nextSearchCheck = 0;

    private void pumpSearchRequest() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextSearchCheck) return;
        nextSearchCheck = now + 120;
        File dir = gateDir();
        // 兜底不变量：搜索不在进行中，就绝不允许留着那两块窗口 ——
        // 全屏"点空白关掉"层要是在异常路径上留下来，整块屏幕就点不动了。
        if (!searchActive && !searchClosing && (searchOutside != null || searchBar != null)) {
            Log.w("FloatingBall", "search: 发现搜索窗口残留（状态机没走上），强制收掉");
            removeSearchWindows();
        }
        File fOpen = new File(dir, "search-open");
        if (fOpen.exists()) {
            fOpen.delete();
            Log.i("FloatingBall", "search: 文件钩子 search-open");
            enterSearchMode("文件钩子");
        }
        File fClose = new File(dir, "search-close");
        if (fClose.exists()) {
            fClose.delete();
            closeSearchMode("文件钩子");
        }
        // hold-release：模拟「最终回复安静 1.5s → 收起方形态」那条释放路径。
        // 为什么要有：搜索胶囊开着的时候助手照样会把最终回复推过来，这条释放就在那一刻跑；
        // 它以前不看 searchActive，一跑就把整屏玻璃窗打回球窗（搜索层被裁进 793×579）。
        File fHold = new File(dir, "hold-release");
        if (fHold.exists()) {
            fHold.delete();
            Log.i("FloatingBall", "hook: hold-release（模拟最终回复释放）");
            releaseHoldForFinalReply();
        }
        // search-type：只把字填进输入框（不发送）—— 用来验证"键盘上的回车/发送键"那条路
        File fType = new File(dir, "search-type");
        if (fType.exists()) {
            String txt = readSmallString(fType, 2000);
            fType.delete();
            final String t = txt.trim();
            if (!t.isEmpty()) {
                if (!searchActive && !searchClosing) enterSearchMode("文件钩子 search-type");
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (searchBar != null) {
                            searchBar.setText(t);
                            searchBar.focusInput();
                            Log.i("FloatingBall", "search: 文件钩子 search-type 填了 " + t.length()
                                    + " 字（等键盘回车）");
                        }
                    }
                }, 1500);
            }
        }
        File fAsk = new File(dir, "search-ask");
        if (fAsk.exists()) {
            String txt = readSmallString(fAsk, 2000);
            fAsk.delete();
            final String t = txt.trim();
            Log.i("FloatingBall", "search: 文件钩子 search-ask=" + t);
            if (!t.isEmpty()) {
                if (!searchActive && !searchClosing) enterSearchMode("文件钩子 search-ask");
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (searchBar != null) searchBar.setText(t);
                        submitSearch(t);
                    }
                }, 1500);
            }
        }
    }

    // ───────────────────────── 悬浮窗 ─────────────────────────
    private void setupOverlay() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        winSize = (int) (Math.min(screenW, screenH) * 0.92f);   // 参考边长（几何缩放基准）
        boolean siri0 = prefs().getBoolean("float_siri", false);
        if (siri0) {
            // Siri 模式：窗口自动贴合球的包围盒（触摸有效区域 = 球），先按“原球”尺寸初始化
            float base0 = winSize * 0.9f;
            winW = Math.min(screenW, Math.round(base0 * 0.37f * 2 + 2 * dp(24)));
            winH = Math.min(screenH, Math.round(base0 * 0.25f * 2 + 2 * dp(24)));
        } else {
            winW = winSize;
            winH = winSize;
        }

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        glView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        // SurfaceView 自带一层 "Background for SurfaceView"（SurfaceFlinger 里能看到
        // color=(0,0,0,1) isOpaque=1）—— 它就是窗口那块**纯黑矩形**：只要 GL 这一帧还没
        // 铺满（面板窗口四周 14dp 留白、或 surface 刚 resize 的那几帧），它就会露出来。
        // 设成透明后，那层不再是不透明的黑。
        glView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        // 诊断：玻璃窗的可触摸区应该是空的（`makeTouchTransparent`）。真吃到触摸就记一笔 ——
        // 用户报「球旁边那片屏幕点不动」时，这一行能直接分辨"是玻璃吃的"还是"别人吃的"。
        glView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    Log.w("FloatingBall", "玻璃窗收到触摸 @" + Math.round(e.getRawX()) + ","
                            + Math.round(e.getRawY()) + "（可触摸区本应为空 → 这一下被玻璃吃掉了）");
                }
                return false;
            }
        });
        glView.setZOrderOnTop(true);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new GlassRenderer(screenW, screenH, winW, winH);
        // 动画总控：第一次跑新逻辑时把老三套开关（think_style / think_loader / float_fluid_*）
        // 的值搬进 anim_scene，用户已经调好的观感不丢。
        AnimScene.migrateIfNeeded(animPrefs(), prefs());
        renderer.refSize = winSize;
        // ★ 冷启动第一帧就得是「常态球」尺寸（屏宽 1/3）。
        //   普通球那条渲染路（siriGeom=false）以前用原版 halfX/halfY → 窗口已经显示、
        //   Siri 节拍还没接管的那几百毫秒里画的是一颗 661×447 的大球，等 enterSearchMode
        //   把 Siri 形态打开才"啪"地缩到 360×243（用户报的「刚启动第一次弹出的球还是大的」）。
        float bootScale = ballIdleScale();
        renderer.idleScale = bootScale;
        // 触摸代理/命中判定的 sv* 默认值也是原版大球，这里一并按小球起手，
        // 免得 siriTick 第一次写值之前 glassHalfW() 把代理窗口撑成 661 那一档。
        renderer.svHalfX = renderer.halfX * bootScale;
        renderer.svHalfY = renderer.halfY * bootScale;
        renderer.svMaster = 1f;
        renderer.svCorner = renderer.corner;
        // 思考中的流体圆点：环半径(px)/亮度增益，跟着设置页走
        renderer.orbRadius = prefs().getInt("float_orb_radius", 90);
        renderer.orbGain = prefs().getInt("float_orb_gain", 110) / 100f;
        renderer.orbRim = prefs().getInt("float_orb_rim", 130) / 100f;
        applyFluidPrefs();                       // 思考中流体发光的颜色/密度/刺眼程度
        // ★ 2026-11-08：这三行是旧「思考中动画」三套开关的残留。总控开着时必须全让位，
        //   否则球一启动就是「旧版：隐藏 Siri 波 + 用 think_loader 那个号」，
        //   而槽要的动画要等下一次状态变化才盖上来 —— 期间看到的就是旧版动画。
        boolean sceneMaster = AnimScene.master(animPrefs());
        renderer.suppressWave = !sceneMaster && prefs().getInt("think_loader", 0) > 0;
        renderer.loaderSpeed = prefs().getFloat("think_loader_speed", 1f);
        renderer.loaderScale = prefs().getFloat("think_loader_scale", 1f);
        renderer.loaderDot = prefs().getFloat("think_loader_dot", 1f);
        renderer.loaderMode = 0;
        renderer.orbOffsetDefault = renderer.orbOffset;   // 有字时会被临时改到"底带"，恢复用这个
        // Siri 波头：固定显示在玻璃底边往上这一截（dp）。旧版「沉底偏移」滑块已删 ——
        // 现在玻璃怎么变高变矮，露出的都是同样多的一条波头（见 GlassRenderer 的 headMask）。
        renderer.waveHeadPx = dp(WAVE_HEAD_DP);
        targetFps = Math.max(1, Math.min(144, prefs().getInt("float_fps", 60)));
        int src = prefs().getInt("float_capture_src", 0);
        boolean lowlatPref = prefs().getBoolean("float_lowlat", false);
        int scalePref = prefs().getInt("float_cap_scale", 2);
        Log.i("FloatingBall", "setupOverlay src=" + src + " lowlat=" + lowlatPref
                + " scale=" + scalePref + " fps=" + targetFps);
        renderer.captureSource = src;
        if (src == 1) {
            // root 直采：无投影、无录屏
            rootCapture = new RootCapture();
            rootCapture.setTargetFps(targetFps);
            rootCapture.setErrorListener(msg -> main.post(() -> {
                if (rootError == null) { rootError = msg; }
            }));
            int cw = screenW, ch = screenH;
            if (lowlatPref) {
                // 低延迟：常驻 CapLive 采集器 + 可降分辨率（避免每帧 fork screencap 的 ~100ms 启动）
                int scale = Math.max(1, scalePref);
                int tw = Math.max(2, screenW / scale);
                int th = Math.max(2, screenH / scale);
                String jar = ensureCapJar();
                if (jar != null) {
                    rootCapture.useAppProcess(jar, tw, th);
                    cw = tw; ch = th;
                    Log.i("FloatingBall", "low-latency capture " + cw + "x" + ch);
                } else {
                    Log.w("FloatingBall", "caplive.jar 提取失败，回退 screencap");
                }
            }
            renderer.capW = cw;
            renderer.capH = ch;
            renderer.setOnSurfaceReady(st -> main.post(new Runnable() {
                @Override public void run() {
                    surfaceReadyAtMs = System.currentTimeMillis();
                    startRootCapture();
                    main.removeCallbacks(capWatchdog);
                    main.postDelayed(capWatchdog, CAP_WATCHDOG_MS);
                }
            }));
        } else {
            renderer.capW = screenW;
            renderer.capH = screenH;
            renderer.setOnSurfaceReady(st -> main.post(() -> startCapture(st)));
        }
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);   // 由 ball-render-driver 按目标帧率驱动
        startRenderDriver();
        glView.setFocusable(false);

        int type = overlayType();
        // 液态玻璃这一层只负责画：所有用户手势都交给 setupBallTouchLayer() 的代理窗口。
        // 这样窗口里那圈「看不见的透明边」（FLAG_LAYOUT_NO_LIMITS 下会真的铺开）不再吞触摸。
        // ⚠️ 这里**不能**用 FLAG_NOT_TOUCHABLE：一加/OPPO 的 WindowManager 会把带这个 flag 的
        // 系统悬浮窗 alpha 强行压到 0.80 → 球永远只有 80% 不透明、背景直接透上来
        // （用户报的「半透明图层 / 贴纸」）。改用「空可触摸区域」达到同样的只画不摸，
        // 细节见 makeTouchTransparent()。
        params = new WindowManager.LayoutParams(
                winW, winH, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        markTrustedOverlay(params);
        // 实验开关：files/alpha099 → 玻璃窗 alpha=0.99（验"累积 opacity 要 > 1.0 才拦"这条判据）
        try {
            if (new File(gateDir(), "alpha099").exists()) params.alpha = 0.99f;
        } catch (Throwable ignored) { }
        params.gravity = Gravity.TOP | Gravity.START;
        // 启动位置就是"水平居中、贴近屏幕顶端"（用户要求）：不要先放中间再跳上去
        params.x = (screenW - winW) / 2;
        params.y = Math.max(Math.round(winY0()), Math.round(winY0() + dp(10) - dp(24)));
        renderer.winX = params.x;
        renderer.winY = params.y;

        if (wm != null) {
            try {
                wm.addView(glView, params);
            } catch (Throwable t) {
                // 受信任 overlay 被系统拒绝（无障碍没真正生效 / OEM 权限校验等）→ 退回普通悬浮窗，
                // 后面 applyBallTouchability() 会走"挪窗口"的老路，功能不丢，只是会闪。
                if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        && params.type != WindowManager.LayoutParams.TYPE_PHONE) {
                    Log.w("FloatingBall", "TYPE_ACCESSIBILITY_OVERLAY 被拒 → 退回普通悬浮窗: " + t);
                    a11yOverlayBroken = true;
                    params.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    wm.addView(glView, params);
                } else {
                    throw t;
                }
            }
            trackWindow(glView);
            // setupThinkLoader(params.type);  // 旧 WebView 叠层方案：点会跑到胶囊外，已弃用
            homeCx = params.x + winW * 0.5f;    // 启动时窗口就是最小圆球 → 这里就是家
            homeCy = params.y + winH * 0.5f + winY0();
            // ★ 关键一格：实测挡注入的是 GLSurfaceView 自带的 SurfaceView 子 surface
            //   （BLOCK_UNTRUSTED），把**这个 surface** 标成受信任 overlay，
            //   遮挡判定就会跳过它 —— 球不用挪、不用闪。
            markSurfaceTrusted(glView);
            // 实测：真正挡住注入的是 GLSurfaceView 自带的 SurfaceView 输入窗
            //   "Untrusted touch due to occlusion by /10286/SurfaceView[](BLAST)"
            // 把它的 surface alpha 压到 0.99（肉眼无感），累积 opacity 就不再是 1.0。
            try {
                if (new File(gateDir(), "alpha099").exists()) glView.setAlpha(0.99f);
            } catch (Throwable ignored) { }
            makeTouchTransparent(glView);   // 只画不摸（但不用 FLAG_NOT_TOUCHABLE，见上）
            // 隐藏 → 恢复会销毁/重建 surface，surface 级标记（抓屏排除 / 受信任 overlay）跟着失效。
            // 这里是"重建后立刻补打"的入口；500ms 的 winSyncLoop 只是兜底。
            watchGlassSurface();
            setupBallTouchLayer();          // 玻璃层之后立刻加 → 代理窗口处于触摸层最低位
            main.post(gateTick);            // agent 门控（文件通道，root 调试用）
            startGateServer();              // agent 门控主通道：127.0.0.1 socket
        }
        glView.onResume();
        glView.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> syncWinPos());
        glView.post(winSync);
        main.post(renderTick);
        main.postDelayed(winSyncLoop, 500);
        applySavedParams();
        lastParamsJson = prefs().getString("float_params", null);
        renderer.bgMode = prefs().getInt("float_mode", 0);
        if (prefs().getBoolean("float_siri", false)) applySiriEnabled(true);
    }

    /**
     * 大方框降分辨率渲染玻璃层。
     *
     * 为什么：方形态窗口最大 1044×2115 ≈ 2.2M 像素，全分辨率实测每帧 45~55ms（圆球态 32ms），
     * 屏幕上就是"卡顿 / 像 LED 一闪一闪"。而折射源本身只有半屏分辨率（CapLive 540×1206），
     * 所以把绘制缓冲降到 ~52 万像素（≈ 圆球态的规模）几乎看不出差别，帧间隔直接回到 33ms。
     *
     * 只在"面积档位"变化时才调 `setFixedSize` —— 每次调用都会重建缓冲（会顿一下）。
     */
    private int glassBufW = -1, glassBufH = -1;

    private void setGlassBufferScale(long areaPx) {
        if (glView == null) return;
        float scale = 1f;
        if (areaPx > 700_000L) {
            scale = (float) Math.sqrt(520_000.0 / (double) areaPx);   // 目标 ≈52 万像素
            scale = Math.max(0.42f, Math.min(1f, scale));
            scale = Math.round(scale * 10f) / 10f;                    // 量化到 0.1：少几次缓冲重建
        }
        int bw = Math.max(2, Math.round(winW * scale));
        int bh = Math.max(2, Math.round(winH * scale));
        if (glassBufW > 0 && Math.abs(bw - glassBufW) < glassBufW * 0.08f
                && Math.abs(bh - glassBufH) < glassBufH * 0.08f) return;   // 变化不大就别重建缓冲
        glassBufW = bw; glassBufH = bh;
        try {
            if (scale >= 0.999f) glView.getHolder().setFixedSize(0, 0);   // 回到跟随视图
            else glView.getHolder().setFixedSize(bw, bh);
            Log.i("FloatingBall", "glass buffer " + bw + "x" + bh + " (window " + winW + "x" + winH
                    + ", scale=" + String.format("%.2f", scale) + ")");
        } catch (Throwable t) {
            Log.w("FloatingBall", "setFixedSize failed: " + t);
        }
    }

    // 抓屏映射必须用窗口的真实屏幕位置（params.x/y 是请求值，会被系统 inset 修正）
    private final Runnable winSync = this::syncWinPos;
    private final Runnable winSyncLoop = new Runnable() {
        @Override public void run() { syncWinPos(); main.postDelayed(this, 500); }
    };

    // ───────────────────────── 思考中胶囊加载（WebView 叠层）─────────────────────────
    private final Runnable thinkLoaderTick = new Runnable() {
        @Override public void run() {
            syncThinkLoader(false);
            main.removeCallbacks(this);
            main.postDelayed(this, 66);
        }
    };

    private void setupThinkLoader(int type) {
        if (thinkLoader != null) return;
        try {
            thinkLoader = new WebView(this);
            thinkLoader.setBackgroundColor(Color.TRANSPARENT);
            WebSettings ws = thinkLoader.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setAllowFileAccess(true);
            thinkLoader.setVerticalScrollBarEnabled(false);
            thinkLoader.setHorizontalScrollBarEnabled(false);
            thinkLoader.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            thinkLoader.setVisibility(View.INVISIBLE);
            thinkLoader.setAlpha(0f);
            thinkLoader.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) {
                    thinkLoaderPageReady = true;
                    syncThinkLoader(true);
                }
            });
            thinkLoaderParams = new WindowManager.LayoutParams(
                    winW, winH, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            thinkLoaderParams.gravity = Gravity.TOP | Gravity.START;
            thinkLoaderParams.x = params.x;
            thinkLoaderParams.y = params.y;
            wm.addView(thinkLoader, thinkLoaderParams);
            trackWindow(thinkLoader);
            thinkLoader.loadUrl("file:///android_asset/think-loader.html");
            main.post(thinkLoaderTick);
            Log.i("FloatingBall", "think loader overlay added " + winW + "x" + winH);
        } catch (Throwable t) {
            Log.w("FloatingBall", "think loader setup failed: " + t);
            thinkLoader = null;
        }
    }

    private void jsThink(String js) {
        if (thinkLoader == null) return;
        try { thinkLoader.evaluateJavascript(js, null); } catch (Throwable ignored) { }
    }

    private void syncThinkLoader(boolean force) {
        if (thinkLoader == null || glView == null || renderer == null) return;
        int mode = prefs().getInt("think_loader", 0);
        boolean want = mode > 0 && "THINKING".equals(stateNow)
                && renderer.waveGate > 0.015f;
        if (!want) {
            if (thinkLoaderVisible) {
                thinkLoaderVisible = false;
                thinkLoader.setVisibility(View.INVISIBLE);
                jsThink("window.pause&&window.pause()");
            }
            return;
        }
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        int w = glView.getWidth(), h = glView.getHeight();
        if (w > 0 && h > 0 && (thinkLoaderParams.x != loc[0] || thinkLoaderParams.y != loc[1]
                || thinkLoaderParams.width != w || thinkLoaderParams.height != h)) {
            thinkLoaderParams.x = loc[0];
            thinkLoaderParams.y = loc[1];
            thinkLoaderParams.width = w;
            thinkLoaderParams.height = h;
            try { wm.updateViewLayout(thinkLoader, thinkLoaderParams); } catch (Throwable ignored) { }
        }
        if (!thinkLoaderPageReady) return;
        float nx = renderer.ballCxW / Math.max(1f, w);
        float ny = renderer.ballCyW / Math.max(1f, h);
        float nrx = renderer.ballHxW / Math.max(1f, w);
        float nry = renderer.ballHyW / Math.max(1f, h);
        if (force || Math.abs(nx - thinkLastNx) > .002f || Math.abs(ny - thinkLastNy) > .002f
                || Math.abs(nrx - thinkLastNrx) > .002f || Math.abs(nry - thinkLastNry) > .002f) {
            jsThink("window.setBall(" + nx + "," + ny + "," + nrx + "," + nry + ")");
            thinkLastNx = nx; thinkLastNy = ny; thinkLastNrx = nrx; thinkLastNry = nry;
        }
        float speed = prefs().getFloat("think_loader_speed", 1f);
        if (force || mode != thinkSentMode || Math.abs(speed - thinkSentSpeed) > .001f) {
            jsThink("window.setLoader(" + mode + "," + speed + ")");
            thinkSentMode = mode;
            thinkSentSpeed = speed;
        }
        thinkLoader.setAlpha(renderer.waveGate);
        if (!thinkLoaderVisible) {
            thinkLoaderVisible = true;
            thinkLoader.setVisibility(View.VISIBLE);
            jsThink("window.play&&window.play()");
        }
    }

    /** syncWinPos 里"后面那些与球几何相关"的部分：菜单面板态下也要跑。 */
    private void syncWinPosDone() {
        ensureGlassSkipScreenshot();
    }

    private void syncWinPos() {
        if (glView == null || renderer == null) return;
        if (glassParked) return;                 // 注入那一刻玻璃被挪出屏幕，别把它算回来
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        renderer.winX = loc[0];
        renderer.winY = loc[1];
        if (glassFullscreen) {
            // 玻璃窗此刻是"菜单面板"，不是球：别把球窗几何夹回去、也别让代理窗口跟着变成面板大小
            syncWinPosDone();
            return;
        }
        updateConvoChrome();            // 对话卡的输入行贴着球下沿（球上下拖动时跟着走）
        syncBallTouchWindow();          // 代理窗口跟着玻璃窗口的真实屏幕位置/尺寸走
        clampBallOnScreen();            // 常态也保证整颗球（含文字）在屏内（有自比较，不会抖）
        // ⚠️ 这里**不要**再周期性 rememberHomeIfMinimal()：平滑跟窗时窗口会滞后动画十几像素，
        // 顺手一记就把这点滞后烘焙成了新的"家"，一轮一轮累积 —— 实测球每轮往下漂 ~27px。
        // "家"只在两处更新：服务启动时的窗口位置、以及用户拖动球（且当时是最小形态）。
        // Surface 就绪后打上 SKIP_SCREENSHOT：抓屏不再包含本窗口 → 球内实时折射真实桌面。
        // ⚠️ 这一步必须是**无条件**的：一旦球没被排除，抓屏里就含着球自己，背景会被冻住
        // （球内显示的是启动那一刻的画面，或者干脆是球自己的上一帧）→ 观感就是「半透明贴纸」
        // 且不随桌面变化。历史上这里挂过一个 float_no_skipshot 调试开关，被留在 true 两次，
        // 两次都被误判成「玻璃被改坏了」。要看球体自带画面请用 files/ballshot 钩子（只截本窗口）。
        ensureGlassSkipScreenshot();
        if (siriText != null) applyTextSkipScreenshot();   // 文字层是独立窗口，得单独排除
    }

    // ───────────────────────── 参数同步（来自 liquid-glass 页面）─────────────────────────
    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("watch", MODE_PRIVATE);
    }

    private String lastParamsJson = null;

    /** float_params 有变化就重新应用；返回 true 表示这轮只做了重载（不抓帧）。 */
    private boolean pumpParamsReload() {
        String s = prefs().getString("float_params", null);
        String sig = s + "|" + prefs().getInt("float_orb_gain", 110)
                + "|" + prefs().getInt("float_orb_rim", 130)
                + "|" + prefs().getInt("float_orb_radius", 90);
        if (sig.equals(lastParamsJson)) return false;
        lastParamsJson = sig;
        if (s != null) applyParams(s);
        // 圆点/黑区那几个标量原先只在启动读一次，调"太亮/太实"时每次都得重装。
        // ★ 动画总控开着时，这些标量由 AnimScene 的「动画参数」管，
        //   这里再回推会把用户刚在设置页调好的值冲掉。
        if (renderer != null && !AnimScene.master(animPrefs())) {
            renderer.orbGain = prefs().getInt("float_orb_gain", 110) / 100f;
            renderer.orbRim = prefs().getInt("float_orb_rim", 130) / 100f;
            renderer.orbRadius = prefs().getInt("float_orb_radius", 90);
            renderer.waveHeadPx = dp(WAVE_HEAD_DP);
            applyFluidPrefs();                    // 流体发光的颜色/密度/刺眼程度（热重载）
        }
        Log.i("FloatingBall", "参数热重载（含圆点亮度/黑区不透明度）");
        return true;
    }

    private void applySavedParams() {
        String s = prefs().getString("float_params", null);
        if (s != null) applyParams(s);
    }

    public void applyParams(String json) {
        if (json == null || renderer == null || glView == null) return;
        final JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return; }
        final boolean sceneDrives = AnimScene.master(animPrefs());
        glView.queueEvent(new Runnable() {
            @Override public void run() {
                renderer.master     = f(o, "master", renderer.master);
                renderer.posX       = f(o, "posX", renderer.posX);
                renderer.posY       = f(o, "posY", renderer.posY);
                renderer.halfX      = f(o, "halfX", renderer.halfX);
                renderer.halfY      = f(o, "halfY", renderer.halfY);
                renderer.corner     = f(o, "corner", renderer.corner);
                renderer.refraction = f(o, "refraction", renderer.refraction);
                renderer.band       = f(o, "band", renderer.band);
                renderer.zoom       = f(o, "zoom", renderer.zoom);
                renderer.dome       = f(o, "dome", renderer.dome);
                renderer.aberration = f(o, "aberration", renderer.aberration);
                renderer.blur       = f(o, "blur", renderer.blur);
                renderer.topDarken  = f(o, "topDarken", renderer.topDarken);
                renderer.black0     = f(o, "black0", renderer.black0);
                renderer.black1     = f(o, "black1", renderer.black1);
                renderer.edgeBleed  = f(o, "edgeBleed", renderer.edgeBleed);
                // 统一调度开着时，波的形态/参数由状态槽里的动画管，别让 float_params 抢
                if (!sceneDrives) {
                    renderer.waveAmp    = f(o, "waveAmp", renderer.waveAmp);
                    renderer.waveScale  = f(o, "waveScale", renderer.waveScale);
                    renderer.waveOn     = o.optBoolean("waveOn", renderer.waveOn);
                    renderer.waveMode   = o.optInt("waveMode", renderer.waveMode);
                }
            }
        });
    }

    private static float f(JSONObject o, String k, float def) {
        return (float) o.optDouble(k, def);
    }

    // ───────────────────────── 抓屏 ─────────────────────────
    private void startCapture(SurfaceTexture st) {
        if (projection != null) return;
        captureTexture = st;
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null || resultData == null) return;
        try {
            projection = mpm.getMediaProjection(resultCode, resultData);
            projection.registerCallback(projectionCb, main);
            captureSurface = new Surface(st);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = projection.createVirtualDisplay(
                    "glass-capture", screenW, screenH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSurface, null, null);
        } catch (Exception e) {
            stopSelf();
        }
    }

    // ───────────────────────── 通知 / 前台 ─────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "悬浮水晶球",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundCompat() {
        PendingIntent pi = PendingIntent.getActivity(this, REQ_OPEN,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("悬浮水晶球")
                .setContentText("正在折射桌面 · 拖动可移动")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true);
        Notification n = b.build();
        boolean rec = prefs().getInt("float_capture_src", 0) == 0;
        if (rec && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                return;
            } catch (Exception ignored) { }
        }
        startForeground(NOTIF_ID, n);
    }

    @Override public void onDestroy() {
        unregisterVolumeSendReceiver();   // 音量减接收器跟着服务一起收（球没开时不该有人收）
        stopRenderDriver();          // 独立渲染节拍线程跟着服务一起收
        running = false;
        INSTANCE = null;
        main.removeCallbacks(winSyncLoop);
        cancelIdleSettle();
        main.removeCallbacks(renderTick);
        main.removeCallbacks(siriTick);
        removeSiriText();
        if (voice != null) { try { voice.cancel(); } catch (Throwable ignored) { } voice = null; }
        try { exitZones(); } catch (Throwable ignored) { }
        try { hideMenu("服务结束"); } catch (Throwable ignored) { }
        removeApprovalViews();            // 面板的两个内层窗口得自己收
        try {
            SiriAssistant a = SiriAssistant.peek();
            if (a != null) a.stopApprovals();
        } catch (Throwable ignored) { }
        if (rootCapture != null) { rootCapture.stop(); rootCapture = null; }
        try { glView.onPause(); } catch (Exception ignored) { }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (captureSurface != null) { captureSurface.release(); captureSurface = null; }
        if (glView != null && wm != null) {
            try { wm.removeView(glView); } catch (Exception ignored) { }
        }
        // 收掉所有别的悬浮窗（胶囊 / 卡片菜单 / 蓝色触控代理 / 审批面板…）。
        // 不摘干净的话：stopSelf 之后这些窗还挂在 WindowManager 上，
        // 屏幕上什么都看不见、却在原位置吃触摸 —— 长按电源键"收球"就成了一次性把桌面点死。
        if (wm != null) {
            for (int i = 0; i < gateWindows.size(); i++) {
                try { wm.removeView(gateWindows.get(i)); } catch (Throwable ignored) { }
            }
            if (ballTouch != null) {
                try { wm.removeView(ballTouch); } catch (Throwable ignored) { }
                ballTouch = null;
            }
        }
        gateWindows.clear();
        gateWindowSaved.clear();
        glView = null;
        super.onDestroy();
    }
}
