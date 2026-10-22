package com.glslplayground.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** 悬浮水晶球：TYPE_APPLICATION_OVERLAY 悬浮窗 + MediaProjection 抓屏 + GLSurfaceView 渲染。 */
public class FloatingBallService extends Service {

    public static volatile boolean running = false;
    private static volatile FloatingBallService INSTANCE;

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
    private GlassRenderer renderer;
    private WindowManager.LayoutParams params;

    // ═══════════════════ agent 触摸门控（球在 DSH 注入触摸时不参与命中测试）═══════════════════
    //
    // 背景：DSH 的 mobile 工具注入触摸走的是 `/system/bin/input`（shell uid 的 INJECT_EVENTS），
    // 事件一样要过 InputDispatcher 的 **窗口命中测试**。球的悬浮窗常驻屏幕中间，注入的
    // ACTION_DOWN 落在球上就会被球吃掉 —— 底下 App 连 down 都收不到（滑动半途而废），
    // 更糟的是球会把这串手势当成用户在操作（按住挪球）。
    //
    // 系统层面**分不出**「注入」和「真手指」，所以只能由 DSH 侧在动手之前先打个招呼：
    //   · DSH 侧（dsh-preset-mobile-use）要发 click/swipe/key 时，先把一个 nonce 写进 ACT 文件；
    //   · 球看到 ACT 文件变新 → 代理触摸窗口立刻设 FLAG_NOT_TOUCHABLE（球变成纯显示），
    //     再把这个 nonce 写进 ACK 文件；DSH 侧等到 ACK（最多 ~500ms）才真正注入触摸；
    //   · 之后 ACT 文件的 mtime 在 ACT_HOLD_MS 内保持「有 agent 在操作」→ 球继续不接触摸，
    //     覆盖 observe→act→observe 的来回；超过这个窗口没有新信号就自动恢复可触摸。
    //
    // 结果：agent 的点击/滑动永远落不到球上，用户手指照常能点球/拖球（除注入那一刻）。
    // 注意 ACT_HOLD_MS 必须大于 DSH 侧「收到 ACK → 真正注入」的往返时间，否则球会提前醒。
    //
    // 路径（踩过的坑，别改回去）：
    //   ✗ /data/local/tmp 根下：目录是 drwxrwx--x shell shell + shell_data_file 标签，
    //     untrusted_app 域**能读不能写** —— 实测读 ACT 成功、写 ACK 报 EACCES。
    //   ✗ /data/local/tmp/dsh-ball（0777 子目录）：写文件同样被 SELinux 拒（目录创建也拒）。
    //   ✓ App 外部私有目录 /sdcard/Android/data/<pkg>/files：App 免权限可写，
    //     root/shell/普通 Termux 用户都读得到（实测 shell 写 OK，标签 media_rw_data_file）。
    // 所以信号文件固定放这里；DSH 侧默认路径与之一致（可用 DSH_BALL_GUARD_DIR 覆盖）。
    private static final String DIR_LEGACY = "/data/local/tmp";        // 兼容老版插件/老 vd 补丁
    private static final String F_ACT = "dsh-ball-act";
    private static final String F_ACK = "dsh-ball-ack";
    private static final String F_GUARD_OFF = "dsh-ball-guard-off";
    // 让路窗口长度：从**球自己看到新 nonce 的那一刻**起算（不是文件 mtime）。
    // 为什么不看 mtime：信号目录在 /sdcard（FUSE），mtime 对 App 有缓存延迟 ——
    // 实测球以为 mtime 比真实写时间早 700ms，1.5s 的窗口实际只剩 ~740ms。
    // 必须大于 DSH 侧「收到 ACK → 真正注入」的往返（~100ms），1500ms 富余很大。
    private static final long ACT_HOLD_MS = 1500;
    private static final long GATE_POLL_MS = 60;
    private static final long ACK_RETRY_MS = 500;
    private static final long STALE_MS = 8000;   // 超过这么久没动过的信号文件 = 遗留物，不当新信号
    private static final String PROXY_TITLE = "dsh-ball-proxy";   // dumpsys 里认这个名字（验证脚本用）

    private View ballTouch;                                  // 唯一接用户手势的窗口（玻璃层只画不摸）
    private WindowManager.LayoutParams ballTouchParams;
    private volatile boolean agentActing = false;            // agent 正在发触摸（或刚发完）
    private volatile String lastAckNonce = "";               // 已确认「球已让路」的 nonce
    private long gateUntilMs = 0;                            // 让路截止时刻（本地时钟，不用文件 mtime）
    private String lastActNonce = "";                        // 上一次处理过的信号 nonce
    private File gateDir;                                    // 信号目录（惰性解析）
    private File gateAckFile;
    private File lastActFile;                                // 上一次读到信号的文件（写 ACK 前回读用）
    private long lastAckTryAt = 0;
    private long lastStaleScanAt = 0;

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
            boolean alive = rootCapture != null && rootCapture.isRunning()
                    && renderer != null && renderer.captureSource == 1;
            if (alive) {
                long age = System.currentTimeMillis() - renderer.lastFrameAtMs;
                if (renderer.lastFrameAtMs > 0 && age > CAP_STALE_MS) {
                    Log.w("FloatingBall", "抓帧已停 " + age + "ms → 重启采集");
                    try { rootCapture.stop(); } catch (Throwable ignored) { }
                    startRootCapture();
                }
            }
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
            pumpOrbMix();                 // 波形交叉淡化按渲染节拍推进（状态切换时不再瞬间换脸）
            pumpWaveGate();               // 幅度包络：换形态前先淡出、换完再淡入
            pumpTextHoldSafety();         // 几何保持的兜底释放
            pumpShotRequest();            // 外部（调试脚本）请求一帧球体截图
            pumpStateRequest();           // 外部（调试脚本）喂一个助手状态，回归状态机用
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
            {"TURNEND", "TURNSTART",
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
            if (waveModePending >= 0 && waveGateTarget == 0f) {
                // 已经淡到 0（画面里没有波也没有点）→ 现在换形态，看不出跳变
                renderer.waveMode = waveModePending;
                waveModePending = -1;
                waveGateTarget = 1f;
                Log.i("FloatingBall", "wave mode -> " + renderer.waveMode + "（幅度为 0 时切换）");
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
            Log.i("FloatingBall", "turn ended=true（波闸门 开）");
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
     * 其它情况（文字还在流、思考中）一律把闸门压到 0 —— 与老行为一致。
     */
    private void refreshFinalWave() {
        if (renderer == null) return;
        // ⚠️⚠️ 这个布尔量是"圆点动画看不见"的**真凶**，改之前先读着色器：
        //   `if (uWaveInGlass > 0.5) { 只画球底那一截波 } else { 圆点/波在这里画 }`
        // 也就是说 waveInGlass=1 时，**整段流体圆点的绘制被跳过**。
        // 老代码只要「球里有字」就把它设成 1 → 处理中（读取/运行命令/工具调用）时
        // 无论怎么调 orbMix/orbOffset 都看不到圆点（用户报的"我还是没看到动画"）。
        // 现在：**只有整轮结束**（waveHold，最终结果那一刻）才把波沉进玻璃；
        // 处理中一律 0 → 圆点照常画（有字时沉到动效带、没字时铺在球心）。
        boolean show = siriEnabled && waveHold && hasBallText();
        renderer.waveInGlass = show ? 1f : 0f;
        if (show) {
            renderer.waveOn = true;
            // ⚠️ 这里**不走 setWaveForm()**：那个函数为了"看不见跳变"会先把幅度淡到 0、
            // 换完形态再淡回来；而这个函数每打一个字就会被调用一次（镜像打字机），
            // 一走淡出淡入就是用户看到的"LED 一闪一闪"。方形态固定用 Siri 波，直接钉住。
            if (renderer.waveMode != 1) { renderer.waveMode = 1; waveModePending = -1; }
            waveGateTarget = 1f;
        }
    }

    /** 把状态真正落到波形上（走包络，不硬切形态）。 */
    private void applyAssistantState(String state) {
        if (renderer == null) return;
        orbWanted = 0f;
        // ── 文字优先：球里有字就只显示字 ────────────────────────────────
        // 用户要求「文字和 Siri 波不能同时出现」。所以：
        //   · 有字 → 波/圆点一律不出（waveGate 压到 0），文字结束后再按状态恢复；
        //   · THINKING（思考/操作/执行命令）→ 流体圆点（处理中）；
        //   · ANSWERING/IDLE 没字 → 交还用户自己的 Siri 模式设定。
        if (hasBallText()) {
            renderer.waveOn = true;
            if ("THINKING".equals(state) && renderer.waveInGlass <= 0.5f) {
                // ★ 用户 2026-10-16 新要求：「方形里也要能看到处理中动画 —— 处理中的动画和那段
                //   文字待在一起等下一个回复」。所以有字时**不再**把圆点压到 0：
                //   把流体圆点沉到"底部动效带"里（文字区已经不占那条带，见 updateSiriTextPos）。
                // 位置：球底往上 animBand/2（屏占比），并放大 uOrbOffset 让圆点真的落在那条带上。
                setWaveForm(2);
                float halfH = winSize * 0.9f * Math.max(0.05f, tHalfY) * 0.5f;
                float depthPerNy = Math.max(dp(40), halfH * 0.85f);
                // 有字：圆点沉到"底部动效带"（不压字）。没字时会被下面各分支改回默认值。
                float wantNy = Math.min(0.85f, (siriAnimBand * 0.5f) / depthPerNy);
                orbOffsetTarget = Math.max(0.05f, wantNy);
                setOrbMixTarget(1f);
                waveGateTarget = 1f;
                orbWanted = 1f;
            } else {
                // 其它状态（回答中/空闲）：文字 + Siri 波共存。
                // 用户 2026-10-16 澄清：「圆点不等于波，当初没要求波不和字共存」——
                // 所以这里**不再压到 0**，波照常放（球底那道波与文字可以同时出现）。
                setWaveForm(1);
                waveGateTarget = 1f;
                orbWanted = 0f;
            }
            refreshFinalWave();
            final String st = state;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (renderer == null) return;
                    Log.i("FloatingBall", "动效收敛后[" + st + "]: orbMix=" + renderer.orbMix
                            + " waveInGlass=" + renderer.waveInGlass
                            + " waveGate=" + renderer.waveGate
                            + " orbOffset=" + renderer.orbOffset);
                }
            }, 420);
            Log.i("FloatingBall", "有字 + 状态 " + state + " → 动效="
                    + (orbWanted > 0.5f ? "底带流体圆点" : "Siri 波（与字共存）")
                    + " [实测] waveInGlass=" + renderer.waveInGlass
                    + " orbMix=" + renderer.orbMix + " waveGate=" + renderer.waveGate
                    + " orbOffset=" + renderer.orbOffset
                    + " (着色的分岔：waveInGlass>0.5 就只画球底那截波、圆点整段跳过)");
            return;
        }
        // 没字：圆点回到**默认位置**（正常大小地铺在球里 —— 用户要求「读取/运行命令/工具调用
        // 和思考是同一个状态，要用同一个动画」，而工具执行期间球里通常没字，
        // 那时圆点若还沉在底部窄带里，就几乎看不到动画）。
        // 没字 → 0（圆点铺在球心）。注意不能直接用用户那个 -0.03 的默认值：
        // 扁球里 hy 小，-0.03*hy 几乎等于没偏移，反而会让圆点被球底切掉一块。
        orbOffsetTarget = Math.max(-0.06f, Math.min(0.06f, renderer.orbOffsetDefault));
        if ("THINKING".equals(state)) {
            // 只有「思考中」才切到流体圆点
            saveWaveOnce();
            renderer.waveOn = true;
            setWaveForm(2);                        // siriFluidDotsCore
            setOrbMixTarget(1f);
        } else {
            // 回答中 / 空闲：不出波，保持「处理中」形态 —— 用户要求处理中动画必须连续，
            // 所以 ANSWERING 不再切回 Siri 波（波只在"初始/结束"出现）。
            // 球里此时挂着回答文字；字清掉之后，IDLE 沉降到点再交还给用户的 Siri 模式。
            if ("IDLE".equals(state) && !hasBallText()) {
                // 真正结束且已经没字 → 交还 Siri 波（用户要的"结束状态才出波"）
                releaseWaveForm();
                setOrbMixTarget(0f);
            } else {
                // 还在处理（THINKING/ANSWERING）或字还挂着 → 保持"处理中"，不出波
                setWaveForm(-1);
                waveGateTarget = 0f;
            }
        }
        Log.i("FloatingBall", "assistant state=" + state
                + " waveOn=" + renderer.waveOn + " waveMode=" + renderer.waveMode
                + " gate→" + waveGateTarget + " pending=" + waveModePending
                + " orbMix→" + orbMixTarget + " (0=Siri波 1=流体圆点)");
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
        if (c != null) c.pumpDebugRequest();
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
        if (approvalShown && approvalReq != null && req.eventId != null
                && req.eventId.equals(approvalReq.eventId)) return;
        // 搜索胶囊开着时不能直接弹面板：两者都靠"同一颗球"的几何，同屏会互相改尺寸。
        // 先收胶囊、等它彻底交还给球，再把面板弹出来（DSH 那边等这一秒没问题）。
        if (searchActive || searchClosing) {
            Log.i("FloatingBall", "审批来了，但搜索胶囊开着 → 先收胶囊，稍后再弹面板");
            closeSearchMode("审批来了");
            main.postDelayed(new Runnable() {
                @Override public void run() { showApprovalPanel(req); }
            }, 900);
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
        float cap = screenH * 0.66f;              // 面板总高上限（球也按它收）

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
        float halfW = Math.max(base * minHalfX, innerW / 2f + apPadX / 2f + dp(4));
        float blockTotal = apTextH + apGap + apActH + (apInputH > 0 ? apGap + apInputH : 0);
        float halfH = Math.max(base * minHalfY,
                blockTotal / 2f + apPadY / 2f + dp(6));
        float maxHalfW = screenW * 0.92f / 2f;
        float maxHalfH = screenH * 0.68f / 2f;
        halfW = Math.min(halfW, maxHalfW);
        halfH = Math.min(halfH, maxHalfH);
        halfH = Math.max(halfH, halfW * 0.9f);            // 别扁成一条：至少接近方形
        halfW = Math.max(halfW, halfH * 0.62f);
        halfW = Math.min(halfW, maxHalfW);
        halfH = Math.min(halfH, maxHalfH);

        float cr = 0.28f * Math.min(halfW, halfH);        // squircle：比方角柔、比椭圆方正
        aprCorner = Math.max(0.28f, Math.min(minCorner, cr / Math.max(1f, halfH)));
        aprHalfX = halfW / base;
        aprHalfY = halfH / base;

        float contentBottomPx = apTextH + apGap + apActH
                + (apInputH > 0 ? apGap + apInputH : 0) + dp(8);
        float ny = 1f - contentBottomPx / Math.max(1f, halfH);
        float gap = Math.max(0f, Math.min(0.25f, siriBlackStep / 1000f));
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

    private WindowManager.LayoutParams overlayParams(int w, int h, int flags) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
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
            try { wm.addView(approvalContent, approvalContentParams); }
            catch (Throwable t) { Log.w("FloatingBall", "add approval content failed: " + t); }
        }
        if (approvalActionsScroll != null && approvalActionsScroll.getParent() == null) {
            try { wm.addView(approvalActionsScroll, approvalActionsParams); }
            catch (Throwable t) { Log.w("FloatingBall", "add approval actions failed: " + t); }
        }
        if (approvalInputRow != null && approvalInputRow.getParent() == null) {
            try { wm.addView(approvalInputRow, approvalInputParams); }
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
            try { wm.addView(v, approvalTouchParams); approvalTouch = v; }
            catch (Throwable t) { Log.w("FloatingBall", "add approval touch failed: " + t); }
        }
    }

    /** 球窗口：完整包住这颗球（否则球面会被窗口裁掉）。 */
    private void updateApprovalWindow() {
        if (wm == null || glView == null || !approvalShown) return;
        float m = dp(24);
        float base = winSize * 0.9f;
        float hw = aprHalfX > 0f ? aprHalfX : minHalfX;
        float hh = aprHalfY > 0f ? aprHalfY : minHalfY;
        int W = Math.min(screenW, Math.max(2, Math.round(2 * (base * hw + m))));
        int H = Math.min(screenH, Math.max(2, Math.round(2 * (base * hh + m))));
        if (W == winW && H == winH) return;
        float bcx = params.x + winW * 0.5f;
        float bcy = params.y + winH * 0.5f;
        winW = W; winH = H;
        params.width = W; params.height = H;
        params.x = Math.round(bcx - W * 0.5f);
        params.y = Math.round(bcy - H * 0.5f);
        params.x = Math.max(-W / 2, Math.min(screenW - W / 2, params.x));
        params.y = Math.max(-Math.round(dp(24)), Math.min(screenH - H + Math.round(dp(24)), params.y));
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

        float blockH = apTextH + apGap + apActH + (apInputH > 0 ? apGap + apInputH : 0);
        float topPad = Math.max(dp(4), (hypx - Math.max(0f, aBlack) * hypx - blockH) / 2f);
        if (apImeUp) topPad = dp(6);      // 键盘弹起：内容顶到球顶，尽量离键盘远一点
        int textX = Math.round(winXnow + winW / 2f - apTextW / 2f);
        int textY = Math.round(ballTop + topPad - sysOffset);
        int actX = Math.round(winXnow + winW / 2f - apActW / 2f);
        int actY = Math.round(textY + apTextH + apGap);

        int key = textX * 31 + textY * 17 + actX * 7 + actY * 3
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

        // 输入行：自己一个窗口，接在按钮层下面
        if (approvalInputRow != null && apInputH > 0) {
            int inX = Math.round(winXnow + winW / 2f - apInputW / 2f);
            int inY = actY + apActH + apGap;
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
     * 方形底部给"处理中动画"预留的带子占球高的比例。
     *
     * 用户要求：「方形回复后进入思考/执行命令时也要看得到处理中动画」——
     * 以前"有字就把动画压到 0"（那是早期"文字和波不能同时出现"的要求）。
     * 现在文字区底部让出这条带子，流体圆点沉到这里显示（见 applyAssistantState）。
     * 想让带子更高/更矮就改这个数（0.10 ~ 0.22 之间比较合适）。
     */
    private static final float ANIM_BAND_RATIO = 0.14f;

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
    private volatile int siriTextDy = 10;   // 文字相对球顶的上下偏移(px)：正=更低
    private float aBlack = 0f, tBlack = 0f; // 黑区下移动画
    private volatile int siriBlackStep = 100; // 文字下沿与 Siri 波的安全距离（千分之一单位，100=0.10）
    // 最短状态 = 原来的水晶球（0/1 个字时），不能再短
    private float minHalfX = 0.37f, minHalfY = 0.25f, minCorner = 1f;

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

    /** 文字相对球顶的上下偏移(px)，实时生效。 */
    public static void setSiriTextDy(int dy) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.siriTextDy = dy);
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

    /** 每多一行黑区整体下移量（百分之一单位，35=0.35），实时生效。 */
    public static void setSiriBlackStep(int v) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applySiriBlackStep(v));
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

    private void applySiriBlackStep(int v) {
        siriBlackStep = Math.max(0, Math.min(450, v));
        if (siriEnabled) measureSiriTargets(siriStr);
    }

    private void applySiriEnabled(boolean en) {
        siriEnabled = en;
        if (en) {
            siriTextDy = prefs().getInt("float_siri_dy", 10);
            siriTextPx = dp(prefs().getInt("float_siri_sp", 34));
            siriBlackStep = prefs().getInt("float_siri_wavegap", 100);
            // 记录“原来的水晶球”尺寸，作为最短形态。
            // 这里刻意【不】乘整体 scale(master)：设置页的「球体大小」滑块走的是 master，
            // 而 Siri 模式下球体几何走 svHalf*/tMaster —— minHalf 只作为「空球时的基准比例」，
            // 球实际多大由 master 决定。两个都乘会让滑块一动就双重缩放。
            if (renderer != null) {
                if (renderer.halfX > 0.02f) minHalfX = renderer.halfX;
                if (renderer.halfY > 0.02f) minHalfY = renderer.halfY;
                minCorner = Math.max(0.5f, Math.min(1f, renderer.corner));
            }
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
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
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
        String key = src.length() + "|" + width + "|" + Math.round(siriTextPx)
                + "|" + Math.round(mdFontRatio() * 1000);
        if (key.equals(mdBuiltFor)) return;
        mdBuiltFor = key;

        BallMarkdownView.Style st = new BallMarkdownView.Style();
        st.bodyPx = Math.max(9f, siriTextPx * mdFontRatio());
        st.headPx = st.bodyPx * 1.06f;
        st.codePx = st.bodyPx * 0.94f;
        st.padH = (int) dp(20);
        // ⚠️ 顶部留白**不要**用 siriTextDy（那是给"文字距球顶"滑块、配合 Siri 波用的，
        // 默认 10px 会让首行离球顶显得很空）。富文本用固定的小留白即可。
        st.padTop = (int) dp(8);
        st.padBottom = (int) dp(10);
        st.gap = Math.round(st.bodyPx * 0.55f);

        mdColumn.removeAllViews();
        BallMarkdownView.Result r = BallMarkdownView.build(this, src, st);
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
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        siriTouchParams = new WindowManager.LayoutParams(
                2, 2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
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
            Log.i("FloatingBall", "touch layer added");
        } catch (Exception e) {
            Log.w("FloatingBall", "add siri touch layer failed: " + e);
        }
    }

    // ═══════════════════ agent 触摸门控 ═══════════════════
    //
    // 每 60ms 看一次信号文件：agent 要发 click/swipe/key 时会先把 nonce 写进 ACT 文件。
    // 信号新鲜（mtime 在 ACT_HOLD_MS 内）→ 代理窗口失去触摸能力（纯显示）+ 回 ACK；
    // 信号过期 → 恢复可触摸（用户照常能点球/拖球）。文件不存在 = 无人用这个协议，行为不变。
    private final Runnable agentGateTick = new Runnable() {
        @Override public void run() {
            pumpAgentGate();
            main.removeCallbacks(this);
            main.postDelayed(this, GATE_POLL_MS);
        }
    };

    private void pumpAgentGate() {
        if (wm == null || ballTouch == null) return;
        File dir = gateDir();
        // 开关：dsh-ball-guard-off 放在信号目录或旧路径都认
        boolean off = new File(dir, F_GUARD_OFF).exists()
                || new File(DIR_LEGACY, F_GUARD_OFF).exists();
        long now = System.currentTimeMillis();
        String nonce = null;
        File src = null;
        if (!off) {
            File[] candidates = {
                    new File(dir, F_ACT),
                    new File(DIR_LEGACY, F_ACT),      // 兼容老插件/老 vd 补丁
            };
            for (File c : candidates) {
                if (c.lastModified() > 0) { src = c; break; }   // 只看"在不在"，不看 mtime
            }
            if (src != null) nonce = readSmallString(src, 64);
        }
        // 看到**新的** nonce = 有新的一次 agent 触摸 → 从这一刻起让路 ACT_HOLD_MS。
        // 内容比对而不是 mtime 比对：FUSE 上同一秒内的连续写 mtime 可能不变。
        // 陈旧信号豁免：**只在信号文件本身很旧、且这次启动还没见过任何信号时**才忽略它。
        // 为什么不能"启动后首读一律忽略"：实测翻过车 —— 球重启后首读把遗留 nonce 记成"已见"，
        // 而插件下一次点击生成的 nonce 恰好就是同一个序列（同一毫秒+同一 pid+同一 action），
        // 于是真信号被当成旧信号忽略，球不让路、不回 ACK，那一次点击就被球吃掉了。
        long ageMs = (src != null) ? (now - src.lastModified()) : -1;
        if (nonce != null && !nonce.isEmpty() && lastActNonce.isEmpty() && ageMs > STALE_MS) {
            lastActNonce = nonce;
            Log.i("FloatingBall", "agent act signal (陈旧 " + ageMs + "ms，仅登记): " + nonce);
            return;
        }
        if (nonce != null && !nonce.isEmpty() && !nonce.equals(lastActNonce)) {
            lastActNonce = nonce;
            gateUntilMs = now + ACT_HOLD_MS;
            Log.i("FloatingBall", "agent act signal: " + nonce
                    + " @" + (src != null ? src.getPath() : "?") + " → 让路 " + ACT_HOLD_MS + "ms");
        }
        boolean acting = now < gateUntilMs;
        if (acting != agentActing) {
            agentActing = acting;
            applyBallTouchability();
            Log.i("FloatingBall", "agent gate -> " + (acting ? "DISARM (球只显示)" : "ARM (球可触摸)"));
        }
        if (acting && nonce != null && !nonce.isEmpty() && !nonce.equals(lastAckNonce)) {
            // 写 ACK 前先回读一次：万一这一瞬间插件又写了新 nonce，就别回旧的那个
            if (src != null) {
                String cur = readSmallString(src, 64);
                if (!cur.isEmpty() && !cur.equals(nonce)) {
                    nonce = cur;
                    lastActNonce = cur;
                    gateUntilMs = now + ACT_HOLD_MS;
                }
            }
            if (now - lastAckTryAt >= ACK_RETRY_MS) {   // 失败时退避，别每 60ms 刷一条错误日志
                lastAckTryAt = now;
                try {
                    writeAtomic(gateAckFile(), nonce + "\n");
                    lastAckNonce = nonce;
                    Log.i("FloatingBall", "agent gate ack: " + nonce + " -> " + gateAckFile().getPath());
                } catch (Throwable e) {
                    Log.w("FloatingBall", "ack write failed: " + e + " (dir=" + gateAckFile().getPath() + ")");
                }
            }
        }
        // 顺手清掉插件留下的 .tmp-* 残渣（原子写用；插件 rename 成功时本来就没有）
        if (now - lastStaleScanAt > 10000) {
            lastStaleScanAt = now;
            try {
                File[] stale = dir.listFiles();
                if (stale != null) {
                    for (File f : stale) {
                        String n = f.getName();
                        if (n.startsWith(F_ACT + ".tmp-") && now - f.lastModified() > 60000) f.delete();
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 代理窗口（唯一接用户手势的窗口）：agent 注入触摸期间、以及搜索胶囊期间必须让路。 */
    private void applyBallTouchability() {
        if (ballTouchParams == null || wm == null || ballTouch == null) return;
        boolean blind = agentActing || searchActive || searchClosing || menuShown || glassFullscreen;
        int want = blind ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0;
        int now = ballTouchParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (want == now) return;
        ballTouchParams.flags = blind
                ? (ballTouchParams.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                : (ballTouchParams.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        updateProxyWindow();
    }

    /** 统一的代理窗口更新口：updateViewLayout 之后补一次 setTitle（系统可能把 title 丢了）。 */
    private void updateProxyWindow() {
        try {
            ballTouchParams.setTitle(PROXY_TITLE);
            try { wm.updateViewLayout(ballTouch, ballTouchParams); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
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

    /** 原子写：先写临时文件再 rename（插件可能正好在这一刻读走）。 */
    private static void writeAtomic(File dst, String body) throws Exception {
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try { out.getFD().sync(); } catch (Throwable ignored) { }
        }
        File parent = dst.getParentFile();
        if (parent != null) {
            try { parent.setExecutable(true, false); parent.setReadable(true, false); } catch (Throwable ignored) { }
        }
        try {
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable e) {
            if (!tmp.renameTo(dst)) throw e;
        }
        try {
            dst.setReadable(true, false);
            dst.setWritable(true, false);
        } catch (Throwable ignored) { }
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
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        float pad = Math.min(dp(12), dp(24));           // 外扩，但要**远小于**窗口留白
        float bhx = Math.max(1f, glassHalfW());
        float bhy = Math.max(1f, glassHalfH());
        float cx = loc[0] + winW * 0.5f;
        float cy = loc[1] + winH * 0.5f;
        int w = Math.max(2, Math.min(screenW, Math.round(2 * (bhx + pad))));
        int h = Math.max(2, Math.min(screenH, Math.round(2 * (bhy + pad))));
        int x = Math.round(cx - w * 0.5f);
        int y = Math.round(cy - h * 0.5f);
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
        float normal = base * renderer.halfX * renderer.master;
        float siri = base * renderer.svHalfX * renderer.svMaster;
        float v = Math.max(normal, siri);
        return v > 2f ? v : winW * 0.5f;
    }

    private float glassHalfH() {
        if (renderer == null) return winH * 0.5f;
        float ref = renderer.refSize > 0 ? renderer.refSize : winW;
        float base = ref * 0.9f;
        float normal = base * renderer.halfY * renderer.master;
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
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        ballTouchParams = new WindowManager.LayoutParams(
                2, 2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballTouchParams.gravity = Gravity.TOP | Gravity.START;
        // 起个名字：dumpsys 里就能按 Window{... u0 dsh-ball-proxy} 直接认出代理层，
        // 不用靠"猜哪个窗口是它"（验证脚本 ball-proxy-state.py 就靠这个名字）。


        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging, longFired;
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
                        // 审批球挂着时不抢手势：屏幕这时候归审批球用
                        if (approvalShown) return false;
                        if (searchActive) return false;         // 搜索胶囊期间屏幕归胶囊
                        if (menuShown) return false;            // 玻璃菜单开着时归菜单
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
                        // 若此刻正在听：顺手停麦，转写结果回来再填进这个框（不改变"先弹框"这件事）。
                        boolean listeningNow = voice != null && voice.listening();
                        Log.i("FloatingBall", "单击 → 弹出状态 2 输入框（listening=" + listeningNow + "）");
                        targetSessionId = null;     // 单击弹框 = 发到新会话（选中会话有专门的入口）
                        targetSessionTitle = null;
                        enterSearchMode("单击", "", false, BAR_SEND);
                        if (listeningNow) voice.tapStop();
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        main.removeCallbacks(twoFingerHold);
                        twoDown = false;
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
            Log.i("FloatingBall", "ball touch proxy added");
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

    private File gateAckFile() {
        File f = gateAckFile;
        if (f == null) {
            f = new File(gateDir(), F_ACK);
            gateAckFile = f;
        }
        return f;
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
        boolean scrollable = siriTextShown && mdContentH > siriViewportH + 2;
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
        // 「文字消失 → 波出来」的接续点：本轮已经结束（IDLE 且沉降到点）时才恢复 Siri 波。
        // 处理中（THINKING/ANSWERING）不恢复 —— 那时要保持连续的处理中动画。
        if (hadText && renderer != null) {
            boolean processing = "THINKING".equals(stateNow) || "ANSWERING".equals(stateNow);
            if (processing) {
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
        // 钉住状态下文字必须一直留着（否则就点不到、解不开了）——
        // 这时球缩回最小形态，但文字保持可见不动，直到用户再点一下文字
        if (asstTextPinned && siriStr != null && !siriStr.isEmpty()) return;
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

    private void showSiriTextNow() {
        if (siriText == null) return;
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
        float minHalfH = winSize * 0.9f * minHalfY;
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

    private float winY0() {
        if (glView != null && params != null) {
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            float d = loc[1] - params.y;
            if (d >= 0 && d < screenH / 4f) { winInsetY = d; return d; }
        }
        if (winInsetY >= 0f) return winInsetY;          // 刚改过 params：用上次测到的真值
        if (renderer != null && winH > 0 && renderer.winY > 0 && params != null) {
            float d = renderer.winY - params.y;
            if (d >= 0 && d < screenH / 4f) { winInsetY = d; return d; }
        }
        // 最后兜底：直接问系统状态栏高度（悬浮窗 TYPE_APPLICATION_OVERLAY 的父容器顶边就是它）
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                float h = getResources().getDimensionPixelSize(id);
                if (h >= 0 && h < screenH / 4f) { winInsetY = h; return h; }
            }
        } catch (Throwable ignored) { }
        return 0f;
    }

    /** 方形态高度上限：屏高 55%（用户 2026-10-16 要求从 85% 收到 55%）。 */
    private float siriMaxBallH() {
        return screenH * SIRI_H_RATIO;
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
    private boolean releasingHold = false;   // 释放时那次重新测量不要再把保持武装起来
    private float holdHalfX = 0f, holdHalfY = 0f, holdCorner = 1f, holdBlack = 0f;
    private long lastTextAt = 0;                         // 最后一次「球里有非空文字」的时刻
    private static final long HOLD_SAFETY_MS = 8000;     // 出字后这么久都没动 → 兜底释放

    /** 兜底：助手在一次流式输出中途被关掉时可能没有 IDLE 事件，久静则自动释放保持。 */
    private void pumpTextHoldSafety() {
        if (!textHold || lastTextAt == 0) return;
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
        // ⚠️ 这里**不能**再 `if (!textHold) return;`：那样"没在保持"时直接返回，
        // 文字不会被隐藏，但形状照样收 —— 用户看到的正是「先变形、字还挂在球上」。
        // 现在无论是否在保持，都先把所有文字隐藏掉（顺序也是硬要求）。
        forceHideAllText();          // ★ 先隐藏一切文字
        if (!textHold) {
            // 没在保持：只需把几何落回初始形态（可能是文字被清空后残留的大几何）
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
            float bb = winSize * 0.9f;
            updateSiriWindow(bb * minHalfX, bb * minHalfY);
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
        if (renderer != null) renderer.waveInGlass = 0f;   // 球底那截波跟着收掉
        if (glView != null) {                              // 回最小形态 → 恢复全分辨率渲染
            glassBufW = glassBufH = -1;
            try { glView.getHolder().setFixedSize(0, 0); } catch (Throwable ignored) { }
        }
        float b0 = winSize * 0.9f;
        updateSiriWindow(b0 * minHalfX, b0 * minHalfY);
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
        float th = Math.max(dp(20), mdContentH > 0 ? mdContentH : dp(40));
        float tw = mdLayoutW > 0 ? mdLayoutW : siriTextMaxW();
        // 上下留白（黑区/球高的余量）：字号大就多留一点
        // 文字四周留白：底部多留一截，保证最后一行完整落在玻璃里（用户：宁可玻璃拉长也别露字）
        float padY = Math.max(dp(18), siriTextPx * mdFontRatio() * 0.85f);
        // 方形态高度上限：**屏高 50%**（用户 2026-10-23 定），再长的文字在方形内滚动
        float maxBallH = screenH * 0.50f;

        float maxBallW = siriMaxBallW();
        // ── 方形态几何（用户 2026-10-16 明确要求）────────────────────────────
        //   · 宽**固定** = 屏宽 85%（siriMaxBallW），字号/文字多少都不许改它；
        //   · 高随文字往下长（th = 文字实际高度 + 上下留白），**不再强行拉成正方形** ——
        //     用户原话：「方形不是说是正方形，且方形的宽要固定好（屏幕宽度的 85%）不可变，
        //     高度随文字往下涨的体积变化（不限字号）」。
        float hxpx = maxBallW / 2f;
        // 对话卡：底部多留一条"输入区"，输入框就落在玻璃里（而不是浮在玻璃外面）
        float convoExtra = convoOpen ? (dp(40) + dp(8) + dp(6)) : 0f;
        float hypx = Math.max(base * minHalfY, th / 2f + padY + convoExtra / 2f);
        hypx = Math.min(hypx, maxBallH / 2f + convoExtra / 2f);       // 高 ≤ 屏高 50%（+输入区）
        // 本轮只增不减：文字块被清空后又来一小段（工具调用之间很常见）时，方框不缩回去。
        if (textHold) {
            hypx = Math.max(hypx, holdHalfY * base);
            hypx = Math.min(hypx, maxBallH / 2f);
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

        // ── 黑区下移量：按「文字到底有多高」算，而不是按行数 ───────────────
        // 着色器里黑区下沿 b1 = uBlack1 - blackOffset，ny 以球顶为 +1、球底为 -1。
        // 文字从球顶往下铺，占 [1 - textTop/hypx, 1 - (textTop+th)/hypx]；
        // 只要把 b1 放在文字下沿再往下一个「安全距离」，文字就永远待在黑区里、
        // 不会碰到黑区下方那道 Siri 波 —— 而且和字号无关（字号变小、行数变多也照样对）。
        float textTopPx = Math.max(0f, siriTextDy) + dp(4);
        float textBottomNy = 1f - ((textTopPx + th) / Math.max(1f, hypx));
        // 黑区下沿：**保持原来的样子**（用户 2026-10-16 明确说"原本黑区就不改了，恢复成原来的渐变黑"）。
        // 即：文字下面留出 siriBlackStep 这段安全距离，且黑区从下沿一路渐变到球底。
        float waveGap = Math.max(0f, Math.min(0.45f, siriBlackStep / 1000f));
        float black1Base = (renderer != null) ? renderer.black1 : -0.01f;
        tBlack = Math.max(0f, Math.min(1.5f, black1Base - (textBottomNy - waveGap)));

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
            float hx = renderer.halfX > 0.02f ? renderer.halfX : 0.37f;
            float hy = renderer.halfY > 0.02f ? renderer.halfY : 0.25f;
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
        int restW = Math.round(2 * base * minHalfX + 2 * dp(24));
        int restH = Math.round(2 * base * minHalfY + 2 * dp(24));
        boolean ballAtRest = aHalfX <= minHalfX * 1.06f && aHalfY <= minHalfY * 1.06f;
        // 让「方框缩回圆球」那段缓动先走完再动窗口：动画中途就抢着缩窗，
        // 用户看到的是"啪一下跳回去"（代码里其它地方专门为这个调过 siriEaseTau）。
        long now = android.os.SystemClock.uptimeMillis();
        if (!ballAtRest) { ballRestSince = 0; return; }
        if (ballRestSince == 0) ballRestSince = now;
        if (now - ballRestSince < 700) return;
        if (!textHold && (winW > restW + dp(8) || winH > restH + dp(8))) {
            Log.i("FloatingBall", "window audit: 球已回最小形态，窗口 " + winW + "x" + winH
                    + " → " + restW + "x" + restH + "（触摸有效区域跟着缩）");
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
            updateSiriWindow(base * minHalfX, base * minHalfY);
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
        float minHalfH = winSize * 0.9f * minHalfY;
        int minWinH = Math.round(2 * minHalfH + 2 * dp(24));
        boolean minimal = !textHold
                && tHalfX <= minHalfX * 1.02f && tHalfY <= minHalfY * 1.02f
                && renderer.svHalfX <= minHalfX * 1.06f && renderer.svHalfY <= minHalfY * 1.06f
                && winH <= minWinH * 1.12f;
        if (!minimal) return;
        float cx = params.x + winW * 0.5f;
        float cy = params.y + winH * 0.5f;
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
        int W = Math.min(screenW, Math.max(2, Math.round(2 * hxpx + 2 * m)));
        int H = Math.min(screenH, Math.max(2, Math.round(2 * hypx + 2 * m)));
        if (Math.abs(W - winW) < 14 && Math.abs(H - winH) < 14) return;
        updateSiriWindow(hxpx, hypx);
    }

    private void updateSiriWindow(float hxpx, float hypx) {
        if (!siriEnabled || wm == null || glView == null) return;
        // 菜单面板 / 状态 3 期间，窗口归那块玻璃用：球的几何更新一律不许改窗口大小
        // （之前 audit 已挡了，但 siriTick 每帧还会经 measureSiriTargets 调到这里，窗口被打回球尺寸）
        // ★ zonesActive 也要挡：状态 3 期间窗口归卡片用（卡片比球窗高时我们主动撑高窗口），
        //   不挡的话 siriTick → measureSiriTargets → 这里每帧把窗口打回球尺寸 → 卡片又被裁成 4 行。
        if (menuShown || zonesActive || (glassFullscreen && searchActive)) return;
        float m = dp(24);
        int W = Math.min(screenW, Math.max(2, Math.round(2 * hxpx + 2 * m)));
        int H = Math.min(screenH, Math.max(2, Math.round(2 * hypx + 2 * m)));
        if (Math.abs(W - winW) > 60 || Math.abs(H - winH) > 60) {
            Log.i("FloatingBall", "ball window -> " + W + "x" + H + " (halfX=" + hxpx + " halfY=" + hypx + ")");
        }
        if (renderer != null) { renderer.winPixelW = W; renderer.winPixelH = H; }
        if (W == winW && H == winH) return;
        final float hx0 = homeCxSafe(), hy0 = homeCySafe();
        winW = W; winH = H;
        params.width = W; params.height = H;
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
        // ★ 方形里给"处理中动画"留一条底带（用户要求：方形内也要能看到处理中动画）。
        //   文字区不占满到球底，底部留出这条带子；带子高度 = 球高的 14%（夹在 70~200px）。
        float glassHalfH = Math.max(dp(20), (winH - 2f * dp(24)) / 2f);
        animBandPx = Math.round(Math.max(dp(70), Math.min(dp(200), glassHalfH * 2f * ANIM_BAND_RATIO)));
        siriAnimBand = animBandPx;
        // 高度 = 球顶+留白 → 球底−留白（再减动效带），**同时**不能超出屏幕可用范围，
        // 也不能小于 0（球被摆得很高时空间会不够）。空间不足就变矮，由 ScrollView 滚。
        int availTop = Math.round(ballTop + topPad);
        int availBottom = Math.round(Math.min(ballBottom - padBottomSafe - animBandPx,
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
        siriText.setAlpha(Math.max(0f, Math.min(1f, (aMaster - 0.1f) / 0.7f)));
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
        createChannel();
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
        static final String V = "approval-orb-4";
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
            return skipScreenshotOf((SurfaceControl) sc, what);
        } catch (Throwable t) {
            Log.w("FloatingBall", what + " setSkipScreenshot failed: " + t);
            return false;
        }
    }

    /** 面板窗口的抓屏排除：窗口刚建时 SurfaceControl 可能无效，重试几次。 */
    private void scheduleApprovalSkipScreenshot() {
        if (Build.VERSION.SDK_INT < 29) return;
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
        // 初始状态会"听"：等窗口真的起来、且没在做别的事之后再开麦
        main.postDelayed(new Runnable() {
            @Override public void run() { armVoice("服务启动/球弹出"); }
        }, 1200);
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

    static final int ZONE_TL = 0, ZONE_TR = 1, ZONE_BL = 2, ZONE_BR = 3;
    private static final int BAR_SEND = 0, BAR_SEARCH = 1;

    private BallVoice voice;
    private boolean zonesActive = false;
    private int zoneActive = -1;
    private BallZonesView zonesView;
    private WindowManager.LayoutParams zonesParams;
    private final float[] zoneCx = new float[4];
    private final float[] zoneCy = new float[4];
    private float zoneR = 1f;

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

    private boolean voiceAutoEnabled() { return prefs().getBoolean("voice_auto", true); }

    /** 弹球就开始听（用户要的初始状态）。已经在听就什么都不做。 */
    private void armVoice(String why) {
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

    /** 状态 2 的发送目标：有选中会话就发给它，否则按"新会话 + 当前参数档"发。 */
    private void sendFromBar(final String text, final File attach, final String attachName) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() && attach == null) return;
        if (targetSessionId != null) {
            sendTextToSession(targetSessionId, t, attach, attachName);
        } else {
            sendTextNewSession(t, attach);
        }
    }

    private void sendTextNewSession(final String text, final File attach) {
        final String attachName = pendingAttachName;
        final String cwd = prefs().getString("send_cwd", "");
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
                            askIn(fid, text, receipts);
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
                        @Override public void run() { askIn(sid, text, receipts); }
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
        SiriAssistant a = SiriAssistant.get(this);
        if (!a.enabled()) {
            toast("DSH 助手没开");
            return;
        }
        ensureSiriForAssistant();
        a.askIn(sessionId, text, receipts);
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
        computeZones();
        renderer.zoneMode = true;                        // 球整趟让路：这一帧画的是四个圆区
        renderer.sHlAmt = 1.5f;
        renderer.sAb = 0.12f;
        renderer.sDensity = sqDensity();
        renderer.zCont = 0.80f;      // 和球同款"上黑下透"（球的黑顶就是这条曲线）
        renderer.zRefract = 16f;
        pushZoneScene();
        showZonesOverlay();
        applyBallTouchability();
        vibrate(22);
        Log.i("FloatingBall", "zones: 进入状态 3（" + why + "）R=" + Math.round(zoneR)
                + " 中心=(" + Math.round(zoneCx[0] + zoneR * 1.15f) + ","
                + Math.round(zoneCy[0] + zoneR * 1.15f) + ") 请滑到某个圆区");
    }

    private void computeZones() {
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        float cx = loc[0] + winW * 0.5f;
        float cy = loc[1] + winH * 0.5f;
        float halfH = winSize * 0.9f * minHalfY;
        zoneR = Math.max(dp(46), halfH * 0.48f);
        float off = zoneR * 1.15f;
        zoneCx[ZONE_TL] = cx - off; zoneCy[ZONE_TL] = cy - off;
        zoneCx[ZONE_TR] = cx + off; zoneCy[ZONE_TR] = cy - off;
        zoneCx[ZONE_BL] = cx - off; zoneCy[ZONE_BL] = cy + off;
        zoneCx[ZONE_BR] = cx + off; zoneCy[ZONE_BR] = cy + off;
        holdCenterX = cx; holdCenterY = cy;
        if (holdWinH0 <= 0) holdWinH0 = winH;
        zoneLocalX = new float[]{zoneCx[0] - loc[0], zoneCx[1] - loc[0],
                zoneCx[2] - loc[0], zoneCx[3] - loc[0]};
        zoneLocalY = new float[]{zoneCy[0] - loc[1], zoneCy[1] - loc[1],
                zoneCy[2] - loc[1], zoneCy[3] - loc[1]};
    }

    private float[] zoneLocalX, zoneLocalY;
    /** 进状态 3 时的球心（屏幕像素）——卡片以它为中心排版，窗口长高也不会跑。 */
    private float holdCenterX = -1f, holdCenterY = -1f;
    /** 进状态 3 时的球窗高度：卡片用完要还回去（用户要求"保持当前大小"）。 */
    private int holdWinH0 = 0;

    /** 按住的那一刻就把"工作目录 + 最近目录下的会话"取回来，卡片一出现就是热的。 */
    private void prefetchZonesData() {
        final DshApi api = DshApi.get(this);
        prefetchedDirs = null;
        prefetchDirPath = null;
        new Thread(() -> {
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
                            + rows.size() + " 条会话");
                });
            } catch (Throwable t) {
                Log.w("FloatingBall", "zones: 预取失败 " + message(t));
            }
        }, "hold-prefetch").start();
    }

    /** 每帧把四个圆区（屏幕像素、y 向下）交给 GL 层；高亮跟着 zoneActive 走。 */
    private void pushZoneScene() {
        if (renderer == null) return;
        float[] s = new float[20];
        float[] m = new float[20];
        for (int i = 0; i < 4; i++) {
            s[i * 4] = zoneCx[i];
            s[i * 4 + 1] = zoneCy[i];
            s[i * 4 + 2] = zoneR;
            s[i * 4 + 3] = zoneR;
            m[i * 4] = (zoneActive == i) ? 1f : 0f;      // act
            m[i * 4 + 1] = 0.02f;                        // dark：和球一样是浅玻璃
            m[i * 4 + 2] = 1f;                           // 圆：圆角 = 半宽
            m[i * 4 + 3] = 1f;                           // enabled
        }
        s[16] = 0; s[17] = 0; s[18] = 1; s[19] = 1;
        m[16] = 0; m[17] = 0; m[18] = 1; m[19] = 0;      // 第 5 个形状关掉
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
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            zonesParams.x = loc[0];
            // ⚠ params 是**父容器内**坐标（父容器顶边 = 状态栏下沿），loc 是屏幕坐标 → 要减 inset，
            //   否则整层文字/图标比玻璃低一个状态栏（2026-10-23 修）
            zonesParams.y = loc[1] - Math.round(winY0());
            try {
                wm.addView(v, zonesParams);
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
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            zonesParams.x = loc[0];
            zonesParams.y = loc[1] - Math.round(winY0());
            zonesParams.width = winW;
            zonesParams.height = winH;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
            for (int i = 0; i < 4; i++) {
                zoneLocalX[i] = zoneCx[i] - loc[0];
                zoneLocalY[i] = zoneCy[i] - loc[1];
            }
        }
        zonesView.hideCards();                 // 上一次的列表文字不许留在屏幕上
        zonesView.setZones(zoneLocalX, zoneLocalY, zoneR);
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
        for (int i = 0; i < 4; i++) {
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
    private static final long ITEM_DWELL_MS = 650;

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
    private String holdDir = null;
    private long holdZoneAt = 0;
    private int holdZoneHover = -1;
    /** 预取的数据（按住的那一刻就去拿，别等手指停在卡片上才发 RPC）。 */
    private List<String> prefetchedDirs = null;
    private String prefetchDirPath = null;
    private List<DshApi.SessionRow> prefetchedSessions = new ArrayList<>();
    private final java.util.LinkedHashMap<String, List<DshApi.SessionRow>> prefetchCache =
            new java.util.LinkedHashMap<>();

    private final Runnable holdZoneDwell = new Runnable() {
        @Override public void run() {
            int z = holdZoneHover;
            if (!zonesActive || z < 0 || holdLevel != 0) return;
            if (android.os.SystemClock.uptimeMillis() - holdZoneAt < ZONE_DWELL_MS) return;
            if (z == ZONE_TL) openHoldDirs();
            else if (z == ZONE_TR) commitHold(() -> enterSearchMode("状态3 右上：搜索会话", "", true, BAR_SEARCH));
            else if (z == ZONE_BL) commitHold(() -> openSettingsMenu());   // = 搜索框双指长按的参数弹窗
            else commitHold(() -> openDshSettings("状态3 右下"));
        }
    };

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
        // 一级（工作目录）：双指**往右滑一点点**就进入该目录选会话
        // （用户：长按→左上显示工作目录，然后双指往右滑一点点进目录；不要求滑到屏幕边缘）
        // 方向判定永远盯着**手指当前坐标**相对上一次转折点的位移（不是"进入这一级时的 X"），
        // 这样手指在半路往右/往左推一点点就能生效，不会因为起点太远而失效。
        float dx = rawX - swipeAnchorX;
        if (holdLevel == 1 && dx > dp(10)) {
            swipeAnchorX = rawX;
            if (i >= 0 && i < holdItems.size() && holdItems.get(i).kind == 0) {
                Log.i("FloatingBall", "zones: 右滑 " + Math.round(dx) + "px → 进入「"
                        + holdItems.get(i).label + "」选会话");
                openHoldSessions(holdItems.get(i).data);
                return;
            }
            holdPendingExpand = true;
            Log.i("FloatingBall", "zones: 右滑时列表未就绪，挂起展开");
        }
        if (holdLevel >= 2 && dx < -dp(10)) {
            Log.i("FloatingBall", "zones: 左滑 " + Math.round(dx) + "px → 退回工作目录列表");
            holdLevel = 1;
            swipeAnchorX = rawX;
            openHoldDirs();
            return;
        }
        if (i != holdFocus) {
            holdFocus = i;
            holdFocusAt = android.os.SystemClock.uptimeMillis();
            holdScrollToFocus();                 // 先滚（可能重排过一次）
            refreshCards();
            if (i >= 0) vibrate(6);
        }
        // 手指离开卡片区 → 自动隐藏，回到一级圆区
        if (i < 0 && farFromCards(rawY)) {
            closeCards();
        }
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
            zonesView.showCards(holdTitle, labels(), subs(), localRects(), holdTitleLocal,
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
        computeCardRects();
        if (zonesView != null) {
            zonesView.showCards(title, labels(), subs(), localRects(), holdTitleLocal,
                    holdListLocal, -1);
            zonesView.setCardScroll(cardScrollMode());
        }
        pushCardScene();
        Log.i("FloatingBall", "zones: 卡片「" + title + "」" + items.size() + " 项");
        // 换级后**不**自动选中：手指要真的移到某一行上才算（否则停着不动会一路自动钻到底）
        holdEnterY = lastHoldY;
        holdFocusArmed = false;
    }

    private void closeCards() {
        if (holdLevel == 0) return;
        setHoldEdgeDir(0);
        holdLevel = 0;
        holdItems.clear();
        holdFocus = -1;
        holdDir = null;
        if (zonesView != null) { zonesView.hideCards(); zonesView.setCardScroll(0); }
        restoreHoldWindow();
        pushZoneScene();
        Log.i("FloatingBall", "zones: 手指离开卡片 → 自动隐藏，回到四区");
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

    private float[] localRects() {
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        float[] out = new float[visCount() * 4];
        if (holdTitleRect != null) {
            holdTitleLocal = new float[]{holdTitleRect[0] - loc[0], holdTitleRect[1] - loc[1],
                    holdTitleRect[2], holdTitleRect[3]};
        }
        if (holdListRect != null) {
            holdListLocal = new float[]{holdListRect[0] - loc[0], holdListRect[1] - loc[1],
                    holdListRect[2], holdListRect[3]};
        }
        for (int i = 0; i < visCount(); i++) {
            out[i * 4] = holdRects[i * 4] - loc[0];
            out[i * 4 + 1] = holdRects[i * 4 + 1] - loc[1];
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
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        // 文字/图标那层也跟着长，否则卡片文字会被覆盖层自己裁掉
        if (zonesView != null && zonesParams != null) {
            zonesParams.y = newTop - inset;
            zonesParams.height = newH;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            for (int i = 0; i < 4; i++) {
                zoneLocalX[i] = zoneCx[i] - loc[0];
                zoneLocalY[i] = zoneCy[i] - loc[1];
            }
            zonesView.setZones(zoneLocalX, zoneLocalY, zoneR);
        }
        Log.i("FloatingBall", "zones: 卡片窗口长高 " + winH + "px（顶 y=" + newTop + "）");
    }

    /** 卡片关掉 → 球窗恢复进状态 3 时的高度。 */
    private void restoreHoldWindow() {
        if (holdWinH0 <= 0 || wm == null || glView == null || params == null) return;
        if (winH == holdWinH0) return;
        int h = holdWinH0;
        winH = h;
        params.height = h;
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        if (zonesView != null && zonesParams != null) {
            zonesParams.height = h;
            try { wm.updateViewLayout(zonesView, zonesParams); } catch (Throwable ignored) { }
        }
        Log.i("FloatingBall", "zones: 卡片关闭，球窗恢复 " + h + "px");
    }

    /** 列表的玻璃形状：**一个**圆角矩形（标题和所有行都在里面）。 */
    private void pushCardScene() {
        if (renderer == null || holdListRect == null) return;
        float[] s = new float[20];
        float[] m = new float[20];
        s[0] = holdListRect[0] + holdListRect[2] * 0.5f;
        s[1] = holdListRect[1] + holdListRect[3] * 0.5f;
        s[2] = holdListRect[2] * 0.5f;
        s[3] = holdListRect[3] * 0.5f;
        m[0] = 0f; m[1] = 0.45f; m[2] = 0.14f; m[3] = 1f;   // 圆角 ≈ 20dp
        for (int i = 1; i < 5; i++) {
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
        // 三级（会话）里松手 = 选中这条会话（进对话卡）；只停在工作目录层级松手 = 无效操作
        if (!cancelled && holdLevel >= 2 && holdFocus < 0) holdFocus = nearestRow(lastHoldY);
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
            Log.i("FloatingBall", "zones: 只选到工作目录，视为无效操作");
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
        homeCx = params.x + winW * 0.5f;
        homeCy = params.y + winH * 0.5f + winY0();              // home 用屏幕坐标
        Log.i("FloatingBall", "球回到初始状态 → 居中靠顶 (" + params.x + "," + params.y
                + ") home=(" + Math.round(homeCx) + "," + Math.round(homeCy) + ")");
    }

    private void exitZones() {
        zonesActive = false;
        zoneActive = -1;
        if (zonesView != null) {
            zonesView.hideCards();
            zonesView.setVisibility(View.GONE);
        }
        if (renderer != null) {
            renderer.zoneMode = false;
            renderer.zShape = new float[20];
            renderer.zMeta = new float[20];
        }
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

    // ══════ 对话卡（状态 3 选完目录/会话、状态 2 的搜索选完都进这里）══════
    // 用户 2026-10-23：「对话框没做出来，直接复用球最终恢复的那个大方形加个输入和侧栏按钮即可」。
    // 所以这里不新造界面：球的方形（Siri 文字态）+ 底部一条输入 + 左边一个侧栏按钮。

    private boolean convoOpen = false;
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
        convoOpenedAt = android.os.SystemClock.uptimeMillis();
        convoHadText = false;
        convoSessionId = sid;
        convoTitle = title == null || title.trim().isEmpty() || "null".equals(title) ? "对话" : title;
        targetSessionId = sid;
        targetSessionTitle = title;
        ensureSiriForAssistant();          // 让球进入方形文字态
        main.postDelayed(new Runnable() {
            @Override public void run() { if (convoOpen) measureSiriTargets(siriStr); }
        }, 120);                            // 让"底部输入区"立刻算进方形高度
        Log.i("FloatingBall", "convo: 进入对话「" + title + "」" + sid);
        final DshApi api = DshApi.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                String text = "";
                String lastUser = "";
                try { text = api.lastAnswer(sid); }
                catch (Throwable t) { Log.w("FloatingBall", "convo: 读最后回答失败 " + message(t)); }
                try { lastUser = api.lastUserMessage(sid); }
                catch (Throwable t) { Log.w("FloatingBall", "convo: 读最后提问失败 " + message(t)); }
                final String tt = text;
                final String lu = lastUser;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!convoOpen || !sid.equals(convoSessionId)) return;
                        convoLastUser = lu == null ? "" : lu.trim();
                        StringBuilder sb = new StringBuilder();
                        if (!convoLastUser.isEmpty()) sb.append("你：").append(convoLastUser).append("\n\n");
                        if (tt != null && !tt.trim().isEmpty()) sb.append(tt);
                        else sb.append("（这个对话还没有回复）");
                        convoHadText = true;
                        setSiriText(sb.toString());
                        main.postDelayed(new Runnable() {
                            @Override public void run() {
                                measureSiriTargets(siriStr);   // 让方形的宽度/高度重新按文字收紧
                                updateConvoChrome();
                            }
                        }, 350);
                    }
                });
            }
        }, "convo-load").start();
        showConvoChrome(convoTitle);
    }

    private void showConvoChrome(final String title) {
        if (wm == null) return;
        if (convoBar == null) {
            BallSearchBar bar = new BallSearchBar(this);
            bar.setBackdrop(false);         // 输入行现在落在方形玻璃内部，不需要自带底
            bar.setHint("发消息…");
            bar.setListener(new BallSearchBar.Listener() {
                @Override public void onSubmit(String text) {
                    String t = text == null ? "" : text.trim();
                    if (t.isEmpty() || convoSessionId == null) return;
                    if (convoBar != null) {
                        convoBar.setText("");
                        convoBar.setHint("已发出 · 等待回复…");
                    }
                    Log.i("FloatingBall", "convo: 发送「" + t + "」");
                    convoSend(convoSessionId, t);
                }
                @Override public void onLongPressAdd() { pickAttachment(); }
                @Override public void onTwoFingerHold() { openSettingsMenu(); }
                @Override public void onAttachmentTap() { clearAttachment(); }
                @Override public void onTapEdit() {
                    if (convoBar != null) { convoBar.focusInput(); showConvoIme(); }
                }
            });
            convoBarParams = overlayParams(2, 2,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            convoBarParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            try { wm.addView(bar, convoBarParams); convoBar = bar; }
            catch (Throwable t) { Log.w("FloatingBall", "convo bar failed: " + t); }
        }
        // 宽度跟"大方形"同宽（玻璃宽度 = 2 × glassHalfW），不再固定 560dp
        int glassW = Math.round(2f * glassHalfW()) - Math.round(dp(28));   // 比玻璃窄一点，左右图标都在玻璃内
        int squareW = (siriText != null && siriText.getWidth() > dp(120))
                ? siriText.getWidth()
                : Math.round(screenW * SIRI_W_RATIO);
        int w = Math.max(Math.round(dp(160)),
                Math.min(Math.min(screenW - Math.round(dp(48)), squareW), glassW));
        int h = Math.round(dp(40));
        int x = (screenW - w) / 2;                 // 输入框居中（侧栏按钮已删除）
        int y = convoChromeY(h);
        convoBarParams.width = w;
        convoBarParams.height = h;
        convoBarParams.x = x;
        convoBarParams.y = y - Math.round(winY0());
        try { wm.updateViewLayout(convoBar, convoBarParams); } catch (Throwable ignored) { }
        convoBar.setVisibility(View.VISIBLE);
        convoBar.resetMaterial();
        convoBar.materialize(true, 0);      // 凝聚出现（构造时 alpha=0，不调这个就永远看不见）
    }

    /** 输入行贴着方形球的下沿（球被上下拖动后也要跟着走）。 */
    private int convoChromeY(int h) {
        int y = Math.round(screenH * 0.70f);
        if (glView != null) {
            int[] loc = new int[2];
            glView.getLocationOnScreen(loc);
            int glassBottom = loc[1] + winH - Math.round(dp(24));   // 窗口含 24dp 留白
            y = glassBottom - h - Math.round(dp(12));  // 玻璃内部、离底边 12dp（再抬高一点，图标不会顶出玻璃）
        }
        return Math.max(Math.round(dp(80)), Math.min(y, screenH - h - Math.round(dp(12))));
    }

    private void updateConvoChrome() {
        if (!convoOpen || convoBar == null || convoBarParams == null) return;
        int h = convoBarParams.height;
        int y = convoChromeY(h);
        if (siriText != null && siriText.getWidth() > dp(120)) {
            int w2 = Math.max(Math.round(dp(160)),
                    Math.min(Math.min(screenW - Math.round(dp(48)), siriText.getWidth()),
                            Math.round(2f * glassHalfW()) - Math.round(dp(28))));
            if (Math.abs(w2 - convoBarParams.width) > 2) {
                convoBarParams.width = w2;
                convoBarParams.x = (screenW - w2) / 2;
                try { wm.updateViewLayout(convoBar, convoBarParams); } catch (Throwable ignored) { }
            }
        }
        if (Math.abs(convoBarParams.y - (y - Math.round(winY0()))) < 2) return;
        convoBarParams.y = y - Math.round(winY0());
        try { wm.updateViewLayout(convoBar, convoBarParams); } catch (Throwable ignored) { }
    }

    /**
     * 对话卡里发一条：自己发 + 轮询回答，球内始终显示「你：<这句> + 最新回复」。
     *
     * 为什么不用 SiriAssistant.askIn：它会把球内文字整体替换成助手回复，
     * 于是"我刚发了什么"立刻消失（用户报的就是这个）。这里改成自己维护一段合成文本。
     */
    private void convoSend(final String sid, final String text) {
        final File attach = pendingAttach;
        final String attachName = pendingAttachName;
        convoLastUser = text;
        if (attach != null && attach.length() > 8L * 1024 * 1024) {
            setSiriText("· 文件太大（" + (attach.length() / 1024 / 1024) + " MB），上限 8 MB，未发送");
            if (convoBar != null) convoBar.setHint("文件太大，未发送");
            clearAttachment();
            return;
        }
        convoHadText = true;
        String echo = convoLastUserBlock();
        if (attach != null) echo += "\n［附件］" + (attachName == null ? attach.getName() : attachName);
        setSiriText(echo + "\n\n· 已发出，等待回复…");
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
                            setSiriText(convoLastUserBlock() + "\n\n· 发送失败：" + m);
                            if (convoBar != null) convoBar.setHint("发送失败：" + m);
                        }
                    });
                    return;
                }
                String last = "";
                for (int i = 0; i < 60; i++) {          // 最长等 ~72s
                    try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
                    if (!convoOpen || !sid.equals(convoSessionId)) return;
                    String ans = "";
                    try { ans = api.lastAnswer(sid); } catch (Throwable ignored) { }
                    if (ans != null && !ans.trim().isEmpty() && !ans.equals(last)) {
                        last = ans;
                        final String shown = convoLastUserBlock() + "\n\n" + ans;
                        main.post(new Runnable() {
                            @Override public void run() { setSiriText(shown); }
                        });
                    }
                }
            }
        }, "convo-send").start();
    }

    private String convoLastUser = "";

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
        Log.i("FloatingBall", "convo: 退出对话（" + why + "）");
        try {
            if (convoBar != null && wm != null) wm.removeView(convoBar);
        } catch (Throwable ignored) { }
        convoBar = null;
        convoSessionId = null;
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
            try { wm.addView(v, menuOutsideParams); menuOutside = v; }
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
            try { wm.addView(m, menuViewParams); menuView = m; }
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
        float[] s = new float[20];
        float[] m = new float[20];
        s[0] = menuRect[0] + menuRect[2] * 0.5f;
        s[1] = menuRect[1] + menuRect[3] * 0.5f;
        s[2] = menuRect[2] * 0.5f;
        s[3] = menuRect[3] * 0.5f;
        m[0] = 0f; m[1] = 0.30f; m[2] = 0.22f;   // 面板圆角 = 0.22 × 半高（≈22dp），不是椭圆
        m[3] = 1f;
        for (int i = 1; i < 5; i++) {
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
                if (convoBar != null) convoBar.setVisibility(View.VISIBLE);
                updateConvoChrome();
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
        float h = sw < 600f ? SQ_CAP_H : 56f;
        return new float[]{ sw / 2f, SQ_EDGE + (sw < 600f ? SQ_CAP_TOP : 96f), w, h };
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
        if (approvalShown || approvalClosing) {
            Log.i("FloatingBall", "search: 审批球在屏上，先不开搜索框");
            return;
        }
        if (wm == null || glView == null || params == null || renderer == null) return;
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
            focusSearchInput();
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
        Log.i("FloatingBall", "search: 退出（" + why + "）");
        if (searchBar != null) {
            searchBar.materialize(false, 0);
        }
        hideSearchIme();
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
            try { wm.addView(v, searchOutsideParams); searchOutside = v; }
            catch (Throwable t) { Log.w("FloatingBall", "add search outside failed: " + t); }
        }
        if (searchBar == null) {
            BallSearchBar bar = new BallSearchBar(this);
            bar.setListener(new BallSearchBar.Listener() {
                @Override public void onSubmit(String text) { submitSearch(text); }

                @Override public void onTapEdit() {
                    // 状态 2 的单击：进入编辑（弹键盘），文字已在这儿，不用再取一次
                    focusSearchInput();
                    if (searchBar != null) searchBar.focusInput();
                    Log.i("FloatingBall", "search: 单击 → 进入编辑");
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
            });
            // 故意**不带** FLAG_NOT_FOCUSABLE：悬浮窗能拿焦点，EditText 才收得到输入法
            // （审批面板那三个坑同源，见 HANDOFF「自由输入」一节）。
            searchBarParams = overlayParams(2, 2,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            searchBarParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            bar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus && searchActive && searchOpen) {
                        main.postDelayed(new Runnable() {
                            @Override public void run() { showSearchIme(); }
                        }, 80);      // 窗口焦点会被系统抢走，拿到就补一次
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

    private void focusSearchInput() {
        main.removeCallbacks(searchFocusTask);
        main.postDelayed(searchFocusTask, 240);   // 抬手之后再要焦点（键盘一弹窗口会动）
    }

    private final Runnable searchFocusTask = new Runnable() {
        @Override public void run() { doFocusSearchInput(); }
    };

    private void doFocusSearchInput() {
        if (searchBar == null || !searchActive) return;
        try {
            searchBar.requestFocus();             // 先让窗口拿焦点
            searchBar.focusInput();               // 再让 EditText 拿焦点
            showSearchIme();
            Log.i("FloatingBall", "search: 请求输入焦点 winFocus=" + searchBar.hasWindowFocus());
        } catch (Throwable t) {
            Log.w("FloatingBall", "search focus failed: " + t);
        }
    }

    private void showSearchIme() {
        if (searchBar == null) return;
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
        File fShowGlass = new File(dir, "show-glass");
        if (fShowGlass.exists()) {
            fShowGlass.delete();
            debugShowGlass = true;
            // 已经打过标记的 Surface 要主动撤掉，否则这一趟还是拍不到玻璃
            if (glView != null) {
                try { skipScreenshotOf(glView.getSurfaceControl(), "glass", false); } catch (Throwable ignored) { }
            }
            renderer.selfExcluded = false;
            Log.i("FloatingBall", "debug: 玻璃留在抓屏里");
        }
        File fHideGlass = new File(dir, "hide-glass");
        if (fHideGlass.exists()) {
            fHideGlass.delete();
            debugShowGlass = false;
            if (glView != null) {
                try { skipScreenshotOf(glView.getSurfaceControl(), "glass", true); } catch (Throwable ignored) { }
                renderer.selfExcluded = true;
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
                    : t.equals("bl") ? ZONE_BL : t.equals("br") ? ZONE_BR : -1;
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
        glView.setZOrderOnTop(true);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new GlassRenderer(screenW, screenH, winW, winH);
        renderer.refSize = winSize;
        // 思考中的流体圆点：环半径(px)/亮度增益，跟着设置页走
        renderer.orbRadius = prefs().getInt("float_orb_radius", 90);
        renderer.orbGain = prefs().getInt("float_orb_gain", 110) / 100f;
        renderer.orbRim = prefs().getInt("float_orb_rim", 130) / 100f;
        renderer.orbOffsetDefault = renderer.orbOffset;   // 有字时会被临时改到"底带"，恢复用这个
        // 方形态收尾时，Siri 波中心藏在球底**下方多少像素**（与球高无关 → 字号怎么变、
        // 露出来的波顶都一样多）。默认 150px：球底边缘正好切在波的顶部一点点。
        renderer.waveDropPx = prefs().getInt("float_wave_drop", 78);
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

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
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
        params.gravity = Gravity.TOP | Gravity.START;
        // 启动位置就是"水平居中、贴近屏幕顶端"（用户要求）：不要先放中间再跳上去
        params.x = (screenW - winW) / 2;
        params.y = Math.max(Math.round(winY0()), Math.round(winY0() + dp(10) - dp(24)));
        renderer.winX = params.x;
        renderer.winY = params.y;

        if (wm != null) {
            wm.addView(glView, params);
            homeCx = params.x + winW * 0.5f;    // 启动时窗口就是最小圆球 → 这里就是家
            homeCy = params.y + winH * 0.5f + winY0();
            makeTouchTransparent(glView);   // 只画不摸（但不用 FLAG_NOT_TOUCHABLE，见上）
            setupBallTouchLayer();          // 玻璃层之后立刻加 → 代理窗口处于触摸层最低位
            main.post(agentGateTick);       // agent 触摸门控：DSH 要注入触摸时球让路
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

    /** syncWinPos 里"后面那些与球几何相关"的部分：菜单面板态下也要跑。 */
    private void syncWinPosDone() {
        if (!renderer.selfExcluded && applySkipScreenshot()) {
            renderer.selfExcluded = true;
        }
    }

    private void syncWinPos() {
        if (glView == null || renderer == null) return;
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
        if (!renderer.selfExcluded && applySkipScreenshot()) {
            renderer.selfExcluded = true;
        }
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
        if (renderer != null) {
            renderer.orbGain = prefs().getInt("float_orb_gain", 110) / 100f;
            renderer.orbRim = prefs().getInt("float_orb_rim", 130) / 100f;
            renderer.orbRadius = prefs().getInt("float_orb_radius", 90);
            renderer.waveDropPx = prefs().getInt("float_wave_drop", 78);
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
                renderer.waveAmp    = f(o, "waveAmp", renderer.waveAmp);
                renderer.waveScale  = f(o, "waveScale", renderer.waveScale);
                renderer.waveOn     = o.optBoolean("waveOn", renderer.waveOn);
                renderer.waveMode   = o.optInt("waveMode", renderer.waveMode);
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
        glView = null;
        super.onDestroy();
    }
}
