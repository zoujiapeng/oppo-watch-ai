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
import android.view.WindowInsets;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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
    // 更糟的是球会把这串手势当成用户在操作（双击开听、按住挪球）。
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

    private final Runnable renderTick = new Runnable() {
        @Override public void run() {
            if (glView == null || targetFps <= 0) return;
            pumpOrbMix();                 // 波形交叉淡化按渲染节拍推进（状态切换时不再瞬间换脸）
            pumpWaveGate();               // 幅度包络：换形态前先淡出、换完再淡入
            pumpTextHoldSafety();         // 几何保持的兜底释放
            pumpShotRequest();            // 外部（调试脚本）请求一帧球体截图
            pumpStateRequest();           // 外部（调试脚本）喂一个助手状态，回归状态机用
            pumpApprovalDebug();          // 外部（调试脚本）喂一条假审批，把审批球叫出来
            glView.requestRender();
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
    // 改动动画状态机时用它回归，别删。状态取值：IDLE / THINKING / ANSWERING / LISTENING / OFF。
    private long nextStateCheck = 0;
    // 目录必须是**球自己能写**的地方：/data/local/tmp 是 shell_data_file 标签，
    // untrusted_app 只能读不能删（踩过：触发文件删不掉 → 每 120ms 被反复消费）。
    private static final String STATE_REQ_PREFIX = "/sdcard/Android/data/com.glslplayground.app/files/state-";
    private static final String[] STATE_NAMES =
            {"IDLE", "THINKING", "ANSWERING", "LISTENING", "TRANSCRIBING", "OFF"};

    private void pumpStateRequest() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextStateCheck) return;
        nextStateCheck = now + 120;
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

    // ── 语音助手（安卓前端 ↔ DSH）：球体作为状态显示器 ──────────────────
    // 采集音量 → Siri 波振幅；助手状态 → 光效形态（聆听=声波，思考=流体圆点）

    /** 麦克风实时音量（0..1），驱动球内 Siri 波的呼吸。 */
    public static void setAssistantLevel(final float rms) {
        FloatingBallService s = INSTANCE;
        if (s == null || s.renderer == null) return;
        s.renderer.voiceBoost = Math.max(0f, Math.min(1.6f, rms * 1.7f));
    }

    // 波 ↔ 流体圆点 的交叉淡化：状态一变就朝目标推进，避免「聆听→思考」时光效瞬间换脸。
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
    //     （THINKING/ANSWERING/LISTENING…）都会取消它 —— 只有「确实安静下来了」才回 IDLE。
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

    /** 助手状态：IDLE / LISTENING / TRANSCRIBING / THINKING / ANSWERING / OFF。 */
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
                cur.releaseTextHold();          // 本轮结束 → 球回初始圆球形态
                Log.i("FloatingBall", "idle settle 到点 → 交还 Siri 波 / 空白（安静了 "
                        + IDLE_SETTLE_MS + "ms）");
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

    /** 把状态真正落到波形上（走包络，不硬切形态）。 */
    private void applyAssistantState(String state) {
        if (renderer == null) return;
        orbWanted = 0f;
        // ── 文字优先：球里有字就只显示字 ────────────────────────────────
        // 用户要求「文字和 Siri 波不能同时出现」。所以：
        //   · 有字 → 波/圆点一律不出（waveGate 压到 0），文字结束后再按状态恢复；
        //   · LISTENING/TRANSCRIBING（用户在说话）没字 → Siri 波；
        //   · THINKING（思考/操作/执行命令）→ 流体圆点（处理中）；
        //   · ANSWERING/IDLE 没字 → 交还用户自己的 Siri 模式设定。
        if (hasBallText() && !"LISTENING".equals(state) && !"TRANSCRIBING".equals(state)) {
            // 有字：幅度压到 0（不出波也不出圆点），只记下"字没了之后该显示什么"。
            // 形态切换推迟到文字消失那一刻 —— 否则会在文字还在时换脸，破坏连续性。
            renderer.waveOn = true;
            waveGateTarget = 0f;
            orbWanted = "THINKING".equals(state) ? 1f : 0f;
            Log.i("FloatingBall", "有字优先：状态 " + state + " 暂不出动画，闸门→0（字没了显示 orbMix="
                    + orbWanted + "）");
            return;
        }
        if ("THINKING".equals(state)) {
            // 只有「思考中」才切到流体圆点
            saveWaveOnce();
            renderer.waveOn = true;
            setWaveForm(2);                        // siriFluidDotsCore
            setOrbMixTarget(1f);
        } else if ("LISTENING".equals(state) || "TRANSCRIBING".equals(state)) {
            saveWaveOnce();
            renderer.waveOn = true;
            setWaveForm(1);                        // siriWaveCore
            setOrbMixTarget(0f);
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
            if ("IDLE".equals(state)) renderer.voiceBoost = 0f;
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
        if (siriText != null) {
            // 钉住时显示一个图钉，解除后去掉；文字本体不变（siriStr 不动）
            siriText.setText(displayTextOf(siriStr));
        }
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
    private float apSaveVoiceBoost;

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

        saveOriginalBall();
        approvalReq = req;
        approvalClosing = false;
        renderer.waveOn = false;          // 面板不带 Siri 波（退出时按存档还原）
        renderer.voiceBoost = 0f;

        float d = getResources().getDisplayMetrics().density * AP_SCALE;   // 面板整体等比缩放
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
        apSaveVoiceBoost = renderer == null ? 0f : renderer.voiceBoost;
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
            renderer.voiceBoost = apSaveVoiceBoost;
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
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

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
            row.setBackground(optionBackground(0x22FFFFFF));
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
            approvalSend.setBackground(optionBackground(withAlpha(AP_ACCENT_ASK, 0x66)));
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
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(16f * d * AP_SCALE);
        g.setStroke(Math.max(1, Math.round(d)), withAlpha(color, 0x7A));
        g.setColors(new int[]{ blend(color, 0x3A), blend(color, 0x1C) });
        g.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        // 按下态单独给一版：同样形状，整体提亮
        android.graphics.drawable.GradientDrawable pressed =
                new android.graphics.drawable.GradientDrawable();
        pressed.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(16f * d);
        pressed.setStroke(Math.max(1, Math.round(d)), withAlpha(color, 0xB0));
        pressed.setColors(new int[]{ blend(color, 0x6E), blend(color, 0x40) });
        pressed.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        return new android.graphics.drawable.StateListDrawable() {{
            addState(new int[]{android.R.attr.state_pressed}, pressed);
            addState(new int[]{}, g);
        }};
    }

    /** 把语义色按比例叠到深底上，得到一层「淡色玻璃」。 */
    private static int blend(int color, int alpha) {
        return withAlpha(color, alpha);
    }

    private static int lighten(int c) {
        int a = (c >>> 24) & 0xFF;
        int r = Math.min(255, (int) (((c >> 16) & 0xFF) * 1.35f));
        int g = Math.min(255, (int) (((c >> 8) & 0xFF) * 1.35f));
        int b = Math.min(255, (int) ((c & 0xFF) * 1.35f));
        return (a << 24) | (r << 16) | (g << 8) | b;
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

    private void ensureApprovalWindows() {
        if (wm == null) return;
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
                    if (wm != null) wm.updateViewLayout(glView, params);
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
    private boolean siriEnabled = false;
    private android.widget.TextView siriText;
    private WindowManager.LayoutParams siriParams;
    private volatile String siriStr = "";
    private float aMaster = 1f, aHalfX = 0.30f, aHalfY = 0.30f, aCorner = 1f;
    private float tMaster = 1f, tHalfX = 0.30f, tHalfY = 0.30f, tCorner = 1f;
    private float siriTextPx;
    private long siriTickLast = 0;
    private volatile int siriTextDy = 10;   // 文字相对球顶的上下偏移(px)：正=更低
    private float aBlack = 0f, tBlack = 0f; // 黑区下移动画
    private volatile int siriBlackStep = 100; // 文字下沿与 Siri 波的安全距离（千分之一单位，100=0.10）
    // 最短状态 = 原来的水晶球（0/1 个字时），不能再短
    // 无文字时的"静止几何"。原来是 0.37/0.25/corner=1 —— 那是个**横向扁的圆角薄片**
    // （宽高比 1.37、圆角拉满），屏幕上看着就是"薄薄一层透明贴纸、不实"。
    // 出字后几何是 0.295/0.295/0.38（近方形、小圆角），那才是用户认可的实心玻璃观感。
    // 所以静止几何直接对齐成同一套（用户："现在依然是不实的" → 根因就在这里）。
    private float minHalfX = 0.295f, minHalfY = 0.295f, minCorner = 0.38f;

    /**
     * 冷启动时把 Siri 几何初始化成"无文字"状态。
     *
     * 为什么需要：siriGeom/sv* 只在**有文字**时由 siriTick 写入，所以刚开启球的那段时间
     * 走的是非 Siri 几何 + svBlackOffset=0 —— 渲染结果与出字后不一致，表现为
     * "刚打开球是半透的（不实），出字后才变实"（用户反复反馈）。
     * 这里按无文字的目标值（tMaster=1, tHalf*=minHalf*, tCorner=minCorner, tBlack=0）
     * 一次性把整套量写好，并且**要让动画值也从目标值起步**（否则会从 0 平滑过去，
     * 中间那几帧仍然不对）。
     */
    /**
     * 冷启动时把 Siri 几何初始化成**近方形的实心玻璃球**。
     *
     * 为什么：siriGeom/sv* 原先只在有文字时由 siriTick 写入，所以刚开启球时走的是
     * "最小形态"几何 —— `halfX=0.37 halfY=0.25 corner=1.0`，那是一个**横向扁的圆角薄片**
     * （宽高比 1.37、圆角拉满）。用户看到的就是"薄薄一层透明贴纸、不实"。
     * 出字后几何切成 `0.295/0.295/0.38`（近方形、小圆角），才显得是实心玻璃。
     *
     * 这里直接按近方形初始化（实测：冷启动球心亮度 91 → 与出字后的 26~45 同档）。
     */
    private void initSiriGeometryNoText() {
        if (renderer == null) return;
        final float hx = 0.295f, hy = 0.295f, cr = 0.38f;
        renderer.siriGeom = true;
        renderer.svMaster = 1f;
        renderer.svHalfX = hx;
        renderer.svHalfY = hy;
        renderer.svCorner = cr;
        renderer.svPosX = 0.5f;
        renderer.svPosY = 0.5f;
        renderer.svBlackOffset = 0f;
        // 动画中间量一起复位，避免开头几帧从旧几何平滑过去
        aMaster = 1f; aHalfX = hx; aHalfY = hy; aCorner = cr; aBlack = 0f;
        tMaster = 1f; tHalfX = hx; tHalfY = hy; tCorner = cr; tBlack = 0f;
        Log.i("FloatingBall", "siri 几何初始化（近方形实心）: " + hx + "/" + hy + " corner=" + cr);
    }

    public static void setSiriEnabled(boolean en) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applySiriEnabled(en));
    }

    public static void setSiriText(String text) {
        FloatingBallService s = INSTANCE;
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
        if (siriText != null) {
            siriText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, siriTextPx);
        }
        if (siriEnabled) measureSiriTargets(siriStr);
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
                // 原来这里把 minCorner 钳到 >=0.5，于是"静止几何"的圆角永远拉满 → 横向薄片观感。
                // 而出字后的几何是 corner=0.38（近方形），两者自相矛盾。
                // 现在直接跟随参数（renderer.corner），让静止态与出字态一致。
                minCorner = Math.max(0.2f, Math.min(1f, renderer.corner));
            }
            // 静止几何 = 近方形实心玻璃（与出字后的几何同一套）。
            //
            // 为什么不再用 renderer.halfX/halfY/corner：那三个是"演示页/设置页"的形状参数
            // （当前 0.37/0.25/1.0），套到 Siri 静止态就是**横向扁的圆角薄片** ——
            // 屏幕上看着"薄薄一层、不实"。而出字后 siriTick 会把几何切成 0.295/0.295/0.38
            // （近方形、小圆角），那才是正常观感。这里让静止态直接对齐后者。
            minHalfX = 0.295f; minHalfY = 0.295f; minCorner = 0.38f;
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
            if (renderer != null) { renderer.siriGeom = false; renderer.svBlackOffset = 0f; }
        }
    }

    private void setupSiriText() {
        if (siriText != null || wm == null) return;
        if (siriTextPx <= 0) siriTextPx = dp(34);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTextColor(0xFFF4F5F7);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, siriTextPx);
        tv.setSingleLine(false);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        tv.setAlpha(0f);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        siriParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        siriParams.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(tv, siriParams);
            siriText = tv;
            setupSiriTouchLayer();
        } catch (Exception e) {
            Log.w("FloatingBall", "add siri text failed: " + e);
        }
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

    /** 代理窗口（唯一接用户手势的窗口）：agent 注入触摸期间必须从命中测试里消失。 */
    private void applyBallTouchability() {
        if (ballTouchParams == null || wm == null || ballTouch == null) return;
        int want = agentActing ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0;
        int now = ballTouchParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (want == now) return;
        ballTouchParams.flags = agentActing
                ? (ballTouchParams.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                : (ballTouchParams.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        updateProxyWindow();
    }

    /** 统一的代理窗口更新口：updateViewLayout 之后补一次 setTitle（系统可能把 title 丢了）。 */
    private void updateProxyWindow() {
        try {
            ballTouchParams.setTitle(PROXY_TITLE);
            wm.updateViewLayout(ballTouch, ballTouchParams);
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

    /** 代理窗口的屏幕矩形：**贴合玻璃层的当前矩形** + 少量外扩（数值没变就不动窗口）。
     *
     * 为什么不能只按"球的最小/最大形态"固定一个尺寸：Siri 球宽在 ~700↔~1040 之间动画，
     * 代理窗口若只按最小形态固定，玻璃层变大时球两侧就出现**死区** —— 触摸确实落在球上，
     * 但代理窗口没盖住那块，事件要么被丢弃要么跑去窗口外（实测：画布上那一笔凭空消失）。
     * 所以直接跟玻璃层同尺寸 + 外扩，窗口只在尺寸真的变了才更新。
     * 注意：球在动画期间这个窗口会跟着改几次尺寸，这是有意的（数值不同的才动）。
     */
    private void syncBallTouchWindow() {
        if (ballTouch == null || ballTouchParams == null || wm == null || glView == null) return;
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        int pad = Math.round(dp(10));
        int w = Math.max(2, Math.min(screenW, winW + 2 * pad));
        int h = Math.max(2, Math.min(screenH, winH + 2 * pad));
        int x = Math.round(loc[0] + winW * 0.5f - w * 0.5f);
        int y = Math.round(loc[1] + winH * 0.5f - h * 0.5f);
        if (ballTouchParams.x == x && ballTouchParams.y == y
                && ballTouchParams.width == w && ballTouchParams.height == h) return;
        ballTouchParams.x = x;
        ballTouchParams.y = y;
        ballTouchParams.width = w;
        ballTouchParams.height = h;
        updateProxyWindow();
    }

    /** 代理窗口里的触摸是否落在球上：换算到玻璃窗口本地坐标后用同一套 SDF。 */
    private boolean inBallAtRaw(float rawX, float rawY) {
        if (!siriEnabled) return true;                 // 普通模式：整颗球都认
        if (glView == null) return true;
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        return inSiriBall(rawX - loc[0], rawY - loc[1]);
    }

    // ── 代理窗口：球上的一切用户手势都在这里（单击/双击/长按取消/拖动）──
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
            long lastTapAt;
            boolean dragging, longFired;
            /** 按住不动 480ms = 长按取消（录音中或正在问答时）。 */
            final Runnable longPress = new Runnable() {
                @Override public void run() {
                    if (dragging || longFired) return;
                    longFired = true;
                    SiriAssistant a = SiriAssistant.peek();
                    if (a != null && a.enabled()) {
                        a.cancelByGesture();
                        try {
                            android.os.Vibrator vb = (android.os.Vibrator)
                                    getSystemService(android.content.Context.VIBRATOR_SERVICE);
                            if (vb != null) vb.vibrate(35);
                        } catch (Throwable ignored) {}
                    }
                }
            };
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 审批球挂着时不抢手势：屏幕这时候归审批球用
                        if (approvalShown) return false;
                        // 球上才算（代理窗口比球略大，多出来的边不响应）
                        if (!inBallAtRaw(e.getRawX(), e.getRawY())) return false;
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = params.x; startY = params.y;
                        dragging = false;
                        longFired = false;
                        main.removeCallbacks(longPress);
                        main.postDelayed(longPress, 480);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float moved = Math.abs(e.getRawX() - downX) + Math.abs(e.getRawY() - downY);
                        if (!dragging) {
                            if (moved <= dp(10)) return true;        // 还没算拖动
                            dragging = true;
                            main.removeCallbacks(longPress);         // 一旦拖动就不再触发长按
                        }
                        moveBallDrag(startX + (e.getRawX() - downX), startY + (e.getRawY() - downY));
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        main.removeCallbacks(longPress);
                        if (longFired) return true;
                        float moved = Math.abs(e.getRawX() - downX) + Math.abs(e.getRawY() - downY);
                        if (dragging || moved >= dp(10)) return true;    // 拖动过就只是拖动
                        long now = System.currentTimeMillis();
                        if (now - lastTapAt < 340) {                     // 双击：开始聆听 / 结束并发送
                            lastTapAt = 0;
                            SiriAssistant a = SiriAssistant.peek();
                            if (a != null && a.enabled()) {
                                ensureSiriForAssistant();
                                a.toggle();
                            }
                        } else {
                            lastTapAt = now;                             // 单击：什么都不做
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
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
        float base = winSize * 0.9f;
        if (base <= 0f) return;
        float m = Math.max(0.01f, siriEnabled ? renderer.svMaster : 1f);
        float halfW, halfH;
        if (siriEnabled) {
            halfW = base * renderer.svHalfX * m;
            halfH = base * renderer.svHalfY * m;
        } else {
            halfW = halfH = base * 0.5f;               // 常态是一颗圆球
        }
        float sysOffset = renderer.winY - params.y;    // 请求 y → 实际 y 的差值（顶部 inset）
        float cx = params.x + winW * 0.5f;
        float cy = params.y + winH * 0.5f + sysOffset;
        float newCx = Math.max(halfW, Math.min(screenW - halfW, cx));
        float newCy = Math.max(halfH, Math.min(screenH - halfH, cy));
        if (Math.abs(newCx - cx) < 1f && Math.abs(newCy - cy) < 1f) return;
        params.x = Math.round(newCx - winW * 0.5f);
        params.y = Math.round(newCy - winH * 0.5f - sysOffset);
        try { wm.updateViewLayout(glView, params); } catch (Throwable ignored) { }
        glView.post(winSync);
        Log.i("FloatingBall", "clampBallOnScreen: 球心 (" + Math.round(cx) + "," + Math.round(cy)
                + ") → (" + Math.round(newCx) + "," + Math.round(newCy) + ")"
                + " 半宽/半高=" + Math.round(halfW) + "/" + Math.round(halfH));
    }

    /** 把球的窗口挪到目标位置（夹取规则与球自身拖动完全一致）。 */
    private void moveBallDrag(float wantX, float wantY) {
        params.x = Math.round(wantX);
        params.y = Math.round(wantY);
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
        if (wm != null) wm.updateViewLayout(glView, params);
        glView.post(winSync);
        clampBallOnScreen();          // 拖到屏幕边缘时把整颗球（含文字）夹回屏内
    }

    /** 触摸层跟随文字区域（有文字才有触摸层，没文字就收起，球的手势完全不受影响）。 */
    private void syncSiriTouchLayer() {
        if (siriTouch == null || siriTouchParams == null || wm == null) return;
        boolean show = siriTextShown && siriStr != null && !siriStr.trim().isEmpty()
                && !approvalShown;      // 审批球在屏上时原球的文字触摸层必须让路
        int wantVis = show ? View.VISIBLE : View.GONE;
        if (siriTouch.getVisibility() != wantVis) {
            siriTouch.setVisibility(wantVis);
            Log.i("FloatingBall", "text touch layer " + (show ? "on" : "off"));
        }
        if (!show) return;
        int w = Math.max(2, siriText.getMeasuredWidth());
        int h = Math.max(2, siriText.getMeasuredHeight());
        if (siriTouchParams.width == w && siriTouchParams.height == h
                && siriTouchParams.x == siriParams.x && siriTouchParams.y == siriParams.y) return;
        siriTouchParams.width = w;
        siriTouchParams.height = h;
        siriTouchParams.x = siriParams.x;
        siriTouchParams.y = siriParams.y;
        try { wm.updateViewLayout(siriTouch, siriTouchParams); } catch (Throwable ignored) { }
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
        syncSiriTouchLayer();             // 没字 → 触摸层收起，球的手势完全不受影响
    }

    /** 有字要显示时再放出来。 */
    private void showSiriTextNow() {
        if (siriText == null) return;
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
        if (siriText != null) siriText.setText(displayTextOf(siriStr));
        measureSiriTargets(siriStr);
        boolean prevEmpty = prev == null || prev.trim().isEmpty();
        boolean nowEmpty = siriStr.trim().isEmpty();
        if (prevEmpty && !nowEmpty) {           // 文字出现 → 触发弹出
            aMaster = 0.03f;
        }
    }

    /** 球宽上限：屏幕宽度的 85%。 */
    private float siriMaxBallW() {
        return screenW * 0.85f;
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
        long idleMs = android.os.SystemClock.uptimeMillis() - lastTextAt;
        if (idleMs > HOLD_SAFETY_MS) {
            Log.i("FloatingBall", "text hold: 兜底释放（出字后静了 " + idleMs + "ms）");
            releaseTextHold();
            lastTextAt = 0;
        }
    }

    private void enterTextHold() {
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
        if (!textHold) return;
        textHold = false;
        releasingHold = true;
        holdHalfX = holdHalfY = 0f;
        Log.i("FloatingBall", "text hold: off（本轮结束，回初始圆球）");
        // 直接落回初始形态，**不要**拿残留文字再量一遍 —— 那会量出「按最后那段文字算的方形」
        // （实测结束时停在 705x705 而不是 787x573 的圆球）。文字层同一时刻藏掉。
        hideSiriTextNow();
        tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner; tBlack = 0f;
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
        int n = s == null ? 0 : s.trim().length();
        if (n <= 1) {                           // 0/1 个字
            if (textHold) {
                // 本轮已经出过字、还没结束：文字被清空（多段生成之间的空档）也不缩回圆球，
                // 继续用本轮量过的最大几何 —— 否则球会在圆球/近方形之间反复缩胀。
                hideSiriTextNow();
                applyHoldGeometry();
                return;
            }
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner;
            tBlack = 0f;
            float b0 = winSize * 0.9f;
            updateSiriWindow(b0 * minHalfX, b0 * minHalfY);
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
        int textMaxW = siriTextMaxW();

        // 换行行数
        int lines;
        {
            android.text.TextPaint lp = new android.text.TextPaint();
            lp.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            lp.setTextSize(siriTextPx);
            android.text.StaticLayout sl = android.text.StaticLayout.Builder
                    .obtain(s, 0, s.length(), lp, textMaxW)
                    .setIncludePad(false)
                    .build();
            lines = sl.getLineCount();
        }
        // 黑区下移量不在这里算：它必须由「文字实际有多高」决定，见下面 hypx 定下来之后。
        // 第 3 行起内边距改大（大圆角方形，留白更足）
        float padX = lines >= 3 ? dp(58) : dp(42);
        float padY = lines >= 3 ? dp(44) : dp(34);

        float tw, th;
        // 高度上限（屏幕 70%）：先算出来，才能把文字按「装得下几行」截断
        float maxBallH = Math.min(screenH * 0.70f, winH);
        if (siriText != null) {
            // 直接用真实 TextView 的换行结果测量，保证球与文字完全匹配；
            // 封顶后放不下的行用省略号截掉 —— 文字永远待在球里，既不溢出也不占满屏
            float maxHalfText = Math.max(dp(20), maxBallH / 2f - padY - dp(16));
            float lineH = siriTextPx * 1.25f;
            try {
                android.graphics.Paint.FontMetrics fm = siriText.getPaint().getFontMetrics();
                if (fm != null && fm.descent - fm.ascent > 1f) lineH = fm.descent - fm.ascent;
            } catch (Throwable ignored) { }
            int maxLines = Math.max(1, (int) Math.floor(maxHalfText * 2f / Math.max(1f, lineH)));
            siriText.setMaxLines(maxLines);
            siriText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            siriText.setText(s);
            siriText.setMaxWidth(textMaxW);
            siriText.measure(View.MeasureSpec.makeMeasureSpec(textMaxW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            // 兜底：极端情况下（超大字号 / 超长文本）measured 高度仍可能超过球的上限，
            // 那就继续减行数 —— 文字必须待在球里，绝不能糊到球外面去。
            for (int guard = 0; guard < 64 && maxLines > 1
                    && siriText.getMeasuredHeight() > maxHalfText * 2f; guard++) {
                maxLines--;
                siriText.setMaxLines(maxLines);
                siriText.measure(View.MeasureSpec.makeMeasureSpec(textMaxW, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            }
            tw = siriText.getMeasuredWidth();
            th = siriText.getMeasuredHeight();
            showSiriTextNow();          // 有字 → 确保文字层可见（之前可能被藏起来过）
            if (th > maxHalfText * 2f + 1f) {
                Log.w("FloatingBall", "siri text still too tall: h=" + th
                        + " limit=" + (maxHalfText * 2f) + " lines=" + maxLines);
            }
            if (s.length() > 60) {
                Log.i("FloatingBall", "siri text len=" + s.length() + " lines=" + maxLines + " h=" + th);
            }
        } else {
            android.text.TextPaint p = new android.text.TextPaint();
            p.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            p.setTextSize(siriTextPx);
            android.text.StaticLayout layout = android.text.StaticLayout.Builder
                    .obtain(s, 0, s.length(), p, textMaxW)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();
            float maxLineW = 0;
            for (int i = 0; i < layout.getLineCount(); i++) {
                maxLineW = Math.max(maxLineW, layout.getLineWidth(i));
            }
            tw = maxLineW;
            th = layout.getHeight();
        }

        float maxBallW = siriMaxBallW();
        // 文字所需半宽/半高（不小于“原来的水晶球”）
        float hxpx = Math.max(base * minHalfX, Math.min(maxBallW / 2f, tw / 2f + padX));
        float hypx = Math.max(base * minHalfY, th / 2f + padY + dp(16));
        hypx = Math.min(hypx, maxBallH / 2f);                         // 高 ≤ 屏幕 70%（长回复不占满屏）
        // ① 本轮只增不减：文字块被清空后又来一小段（工具调用之间很常见）时，球不缩回去。
        //    先做减法：把本轮「量过的最大值」钳进来，再夹到上限。
        if (textHold) {
            hxpx = Math.max(hxpx, holdHalfX * base);
            hypx = Math.max(hypx, holdHalfY * base);
            hypx = Math.min(hypx, maxBallH / 2f);
        }
        // ② 只要这一轮出过字，就一律往「近方形」收 —— 用户的原话是「有文字就尽可能变成近方形」。
        //    两个方向都要补齐：只补宽度会得到一条 5:1 的横条（实测 1044x573），不是近方形。
        {
            float a = Math.max(hxpx, hypx);                    // 近方形：短边往长边靠
            float lo = Math.min(hxpx, hypx);
            a = Math.min(a, lo * 1.18f);                       // 但别拉太狠（上限 1.18 倍）
            a = Math.min(a, maxBallW / 2f);                    // 宽 ≤ 屏宽 85%
            a = Math.min(a, maxBallH / 2f);                    // 高 ≤ 屏高 70%
            a = Math.max(a, Math.min(base * minHalfY, base * minHalfX));   // 不小于原来的水晶球
            hxpx = hypx = a;
            float r = 0.38f * Math.min(hxpx, hypx);            // 圆角 ≈ 短边 38% → squircle
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
    }

    private final Runnable siriTick = new Runnable() {
        @Override public void run() {
            if (!siriEnabled) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = siriTickLast == 0 ? 0.016f : Math.min(0.05f, (now - siriTickLast) / 1000f);
            siriTickLast = now;
            float k = 1f - (float) Math.exp(-dt / 0.06);
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
            if (approvalShown) {
                updateApprovalWindow();
                updateApprovalLayout();
                updateApprovalTouchLayer(winSize * 0.9f * aHalfX * aMaster,
                        winSize * 0.9f * aHalfY * aMaster,
                        renderer != null ? renderer.winX : params.x,
                        renderer != null ? renderer.winY : params.y);
            } else {
                updateSiriTextPos();
            }
            main.postDelayed(this, 16);
        }
    };

    /** Siri：把悬浮窗尺寸调整为贴合当前球（触摸有效区域=球），并保持球心屏幕位置不变。 */
    private void updateSiriWindow(float hxpx, float hypx) {
        if (!siriEnabled || wm == null || glView == null) return;
        float m = dp(24);
        int W = Math.min(screenW, Math.max(2, Math.round(2 * hxpx + 2 * m)));
        int H = Math.min(screenH, Math.max(2, Math.round(2 * hypx + 2 * m)));
        Log.i("FloatingBall", "ball window -> " + W + "x" + H + " (halfX=" + hxpx + " halfY=" + hypx + ")");
        if (W == winW && H == winH) return;
        float bcx = params.x + winW * 0.5f;
        float bcy = params.y + winH * 0.5f;
        winW = W; winH = H;
        params.width = W; params.height = H;
        params.x = Math.round(bcx - W * 0.5f);
        params.y = Math.round(bcy - H * 0.5f);
        try { wm.updateViewLayout(glView, params); } catch (Exception ignored) { }
        glView.post(winSync);
        clampBallOnScreen();          // 文字变长 → 球变大 → 可能要往屏内挪，否则文字出界
    }

    private void updateSiriTextPos() {
        if (siriText == null || siriParams == null || wm == null) return;
        if (siriText.getVisibility() != View.VISIBLE) return;   // 已藏起来（球回最小形态）就别再摆位置/改透明度
        float winXnow = (renderer != null) ? renderer.winX : params.x;
        float winYnow = (renderer != null) ? renderer.winY : params.y;
        float base = winSize * 0.9f;
        if (base <= 0) base = 1f;
        float sx = winXnow + winW * 0.5f;
        float sy = winYnow + winH * 0.5f;
        float hypx = base * aHalfY * aMaster;     // 当前球半高
        // 宽度上限 = 当前球的实际内宽（每帧按当前几何算），而不是"文字最长时的球宽"。
        // 坑：只在文字变化时量一次的话，球后来变小了、文字层还留着旧的大宽度 → 文字横向溢出玻璃
        //（实测球 658 宽、文字层 697 宽，两侧各冒出去 19px）。
        // 这里**不调用 setMaxWidth**（那会污染球的测量结果），只用 AT_MOST 量一次用于排版/居中。
        float ballHalfW = base * aHalfX * aMaster;
        int textMaxW = Math.max(1, Math.round(2f * ballHalfW - 2f * dp(28)));
        siriText.measure(View.MeasureSpec.makeMeasureSpec(textMaxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int tw = Math.min(siriText.getMeasuredWidth(), textMaxW);
        // 补偿字体上方留白(ascent 空白)，让首行字形真正贴近球顶，视觉间距 ≈ 5dp
        float topGap = 0f;
        try {
            android.text.TextPaint tp = new android.text.TextPaint(siriText.getPaint());
            tp.setTextSize(siriTextPx);
            android.graphics.Paint.FontMetricsInt fm = tp.getFontMetricsInt();
            android.graphics.Rect rb = new android.graphics.Rect();
            String first = (siriStr == null || siriStr.isEmpty()) ? "字" : siriStr.substring(0, 1);
            tp.getTextBounds(first, 0, first.length(), rb);
            topGap = -fm.ascent + rb.top;      // TextView 顶 → 首行字形顶
        } catch (Throwable ignored) { }
        float ballHalfH = base * aHalfY * aMaster;
        float ballTop = sy - ballHalfH;
        float ballBottom = sy + ballHalfH;
        siriParams.x = Math.round(sx - tw / 2f);
        // 系统会给悬浮窗加一个顶部 inset 偏移（=球窗口实际 top − 请求 y，≈状态栏高度）；
        // 必须减掉它，否则文字会被整体推低约一个状态栏的高度。
        float sysOffset = winYnow - params.y;
        siriParams.y = Math.round(ballTop + siriTextDy - Math.max(0f, topGap) - sysOffset);   // 字形顶相对球顶的偏移
        // 兜底夹取：文字层的实际屏幕位置也要在屏内（x 留一点边距，y 夹在屏幕高度内）
        int[] tloc = new int[2];
        siriText.getLocationOnScreen(tloc);
        int tdx = tloc[0] - siriParams.x;          // 请求 x → 实际 x 的差值（系统 inset）
        int tdy = tloc[1] - siriParams.y;
        int realX = siriParams.x + tdx;
        int realY = siriParams.y + tdy;
        int maxX = Math.max(0, screenW - tw);
        int clampedX = Math.max(0, Math.min(maxX, realX));
        int clampedY = Math.max(0, Math.min(Math.max(0, screenH - siriText.getMeasuredHeight()), realY));
        if (clampedX != realX) siriParams.x += clampedX - realX;
        if (clampedY != realY) siriParams.y += clampedY - realY;
        // 垂直方向也要在玻璃轮廓内：文字块顶不低于球顶、底不高于球底（否则就是"文字跑出玻璃"）
        int th = siriText.getMeasuredHeight();
        int glassTop = Math.round(ballTop) + 2;
        int glassBottom = Math.round(ballBottom) - 2;
        int wantY = siriParams.y + tdy;                       // 当前实际屏幕 y
        if (wantY < glassTop) siriParams.y += glassTop - wantY;
        else if (wantY + th > glassBottom) siriParams.y += (glassBottom - th) - wantY;
        try { wm.updateViewLayout(siriText, siriParams); } catch (Exception ignored) { }
        syncSiriTouchLayer();
        siriText.setAlpha(Math.max(0f, Math.min(1f, (aMaster - 0.1f) / 0.7f)));
    }

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
        // 语音助手：轻点球体即可跟本机 DSH 说话；同时起一条常驻镜像流，
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
    private boolean applySkipScreenshot() {
        if (Build.VERSION.SDK_INT < 29 || glView == null) return false;
        try {
            SurfaceControl sc = glView.getSurfaceControl();
            if (sc == null) return false;
            Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txClass.getDeclaredConstructor().newInstance();
            try {
                Method m = txClass.getMethod("setSkipScreenshot", SurfaceControl.class, boolean.class);
                m.invoke(tx, sc, true);
            } catch (NoSuchMethodException e) {
                Method m = txClass.getMethod("setSkipScreenshot", boolean.class);
                m.invoke(tx, true);
            }
            txClass.getMethod("apply").invoke(tx);
            return true;
        } catch (Throwable t) {
            Log.w("FloatingBall", "setSkipScreenshot failed: " + t);
            return false;
        }
    }


    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (intent != null) {
            resultCode = intent.getIntExtra("code", 0);
            resultData = intent.getParcelableExtra("data");
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (glView == null) setupOverlay();
        return START_NOT_STICKY;
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
        glView.setZOrderOnTop(true);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new GlassRenderer(screenW, screenH, winW, winH);
        renderer.refSize = winSize;
        // 思考中的流体圆点：环半径(px)/亮度增益，跟着设置页走
        renderer.orbRadius = prefs().getInt("float_orb_radius", 90);
        renderer.orbGain = prefs().getInt("float_orb_gain", 110) / 100f;
        renderer.orbRim = prefs().getInt("float_orb_rim", 130) / 100f;
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
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);   // 由 renderTick 按目标帧率驱动
        glView.setFocusable(false);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // 液态玻璃这一层只负责画：所有用户手势都交给 setupBallTouchLayer() 的代理窗口。
        // 这样窗口里那圈「看不见的透明边」（FLAG_LAYOUT_NO_LIMITS 下会真的铺开）不再吞触摸。
        params = new WindowManager.LayoutParams(
                winW, winH, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (screenW - winW) / 2;
        params.y = (screenH - winH) / 2;
        renderer.winX = params.x;
        renderer.winY = params.y;

        if (wm != null) {
            wm.addView(glView, params);
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
        initSiriGeometryNoText();
        lastParamsJson = prefs().getString("float_params", null);
        renderer.bgMode = prefs().getInt("float_mode", 0);
        if (prefs().getBoolean("float_siri", false)) applySiriEnabled(true);
    }

    // 抓屏映射必须用窗口的真实屏幕位置（params.x/y 是请求值，会被系统 inset 修正）
    private final Runnable winSync = this::syncWinPos;
    private final Runnable winSyncLoop = new Runnable() {
        @Override public void run() { syncWinPos(); main.postDelayed(this, 500); }
    };

    private void syncWinPos() {
        if (glView == null || renderer == null) return;
        int[] loc = new int[2];
        glView.getLocationOnScreen(loc);
        renderer.winX = loc[0];
        renderer.winY = loc[1];
        syncBallTouchWindow();          // 代理窗口跟着玻璃窗口的真实屏幕位置/尺寸走
        clampBallOnScreen();            // 常态也保证整颗球（含文字）在屏内（有自比较，不会抖）
        // Surface 就绪后打上 SKIP_SCREENSHOT：抓屏不再包含本窗口 → 球内实时折射真实桌面
        // 排查开关：打开 float_no_skipshot 时球会出现在系统截图里（注意：会导致自我采样）
        if (!prefs().getBoolean("float_no_skipshot", false)) {
            if (!renderer.selfExcluded && applySkipScreenshot()) {
                renderer.selfExcluded = true;
            }
        }
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
        running = false;
        INSTANCE = null;
        main.removeCallbacks(winSyncLoop);
        cancelIdleSettle();
        main.removeCallbacks(renderTick);
        main.removeCallbacks(siriTick);
        removeSiriText();
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
