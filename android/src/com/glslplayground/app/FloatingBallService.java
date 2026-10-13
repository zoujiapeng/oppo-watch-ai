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
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

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

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private Surface captureSurface;
    private SurfaceTexture captureTexture;
    private RootCapture rootCapture;
    private String rootError;

    private int screenW, screenH, winSize;
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
            glView.requestRender();
            main.removeCallbacks(this);
            main.postDelayed(this, Math.max(1, 1000 / targetFps));
        }
    };

    // ── Siri 模式：球弹出、文字从球内出现、球随文字实时变宽变高、圆角变小 ──
    private boolean siriEnabled = false;
    private android.widget.TextView siriText;
    private WindowManager.LayoutParams siriParams;
    private volatile String siriStr = "";
    private float aMaster = 1f, aHalfX = 0.30f, aHalfY = 0.30f, aCorner = 1f;
    private float tMaster = 1f, tHalfX = 0.30f, tHalfY = 0.30f, tCorner = 1f;
    private float siriTextPx;
    private long siriTickLast = 0;
    // 最短状态 = 原来的水晶球（0/1 个字时），不能再短
    private float minHalfX = 0.37f, minHalfY = 0.25f, minCorner = 1f;

    public static void setSiriEnabled(boolean en) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applySiriEnabled(en));
    }

    public static void setSiriText(String text) {
        FloatingBallService s = INSTANCE;
        if (s != null) s.main.post(() -> s.applySiriText(text));
    }

    private void applySiriEnabled(boolean en) {
        siriEnabled = en;
        if (en) {
            // 记录“原来的水晶球”尺寸，作为最短形态
            if (renderer != null) {
                if (renderer.halfX > 0.02f) minHalfX = renderer.halfX;
                if (renderer.halfY > 0.02f) minHalfY = renderer.halfY;
                minCorner = Math.max(0.5f, Math.min(1f, renderer.corner));
            }
            if (siriText == null) setupSiriText();
            main.removeCallbacks(siriTick);
            siriTickLast = 0;
            main.post(siriTick);
        } else {
            main.removeCallbacks(siriTick);
            removeSiriText();
            if (renderer != null) renderer.siriGeom = false;
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
        } catch (Exception e) {
            Log.w("FloatingBall", "add siri text failed: " + e);
        }
    }

    private void removeSiriText() {
        if (siriText != null && wm != null) {
            try { wm.removeView(siriText); } catch (Exception ignored) { }
        }
        siriText = null;
    }

    private void applySiriText(String text) {
        String prev = siriStr;
        siriStr = text == null ? "" : text;
        if (siriText != null) siriText.setText(siriStr);
        measureSiriTargets(siriStr);
        boolean prevEmpty = prev == null || prev.trim().isEmpty();
        boolean nowEmpty = siriStr.trim().isEmpty();
        if (prevEmpty && !nowEmpty) {           // 文字出现 → 触发弹出
            aMaster = 0.03f;
        }
    }

    /** 球宽上限：屏幕宽度的 85%（且不超过悬浮窗）。 */
    private float siriMaxBallW() {
        return Math.min(screenW * 0.85f, winSize);
    }

    /** 文字可换行的最大宽度。 */
    private int siriTextMaxW() {
        return (int) Math.max(dp(60), siriMaxBallW() - 2 * dp(42));
    }

    private void measureSiriTargets(String s) {
        int n = s == null ? 0 : s.trim().length();
        if (n <= 1) {                           // 0/1 个字：最短形态 = 原来的水晶球
            tMaster = 1f; tHalfX = minHalfX; tHalfY = minHalfY; tCorner = minCorner;
            return;
        }
        if (siriTextPx <= 0) siriTextPx = dp(34);
        float base = winSize * 0.9f;
        if (base <= 0) base = 1f;
        float padX = dp(42), padY = dp(34);
        int textMaxW = siriTextMaxW();

        float tw, th;
        if (siriText != null) {
            // 直接用真实 TextView 的换行结果测量，保证球与文字完全匹配
            siriText.setText(s);
            siriText.setMaxWidth(textMaxW);
            siriText.measure(View.MeasureSpec.makeMeasureSpec(textMaxW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            tw = siriText.getMeasuredWidth();
            th = siriText.getMeasuredHeight();
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
        // 始终是“横着的胶囊”：宽至少为高的 1.35 倍，行数变多也只会更宽、不会变圆
        hxpx = Math.min(maxBallW / 2f, Math.max(hxpx, hypx * 1.35f));
        tHalfX = hxpx / base;
        tHalfY = hypx / base;
        tCorner = minCorner;                 // 胶囊端 = 半圆，始终圆滑
        tMaster = 1f;
    }

    private final Runnable siriTick = new Runnable() {
        @Override public void run() {
            if (!siriEnabled) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = siriTickLast == 0 ? 0.016f : Math.min(0.05f, (now - siriTickLast) / 1000f);
            siriTickLast = now;
            float k = 1f - (float) Math.exp(-dt / 0.06);
            aMaster += (tMaster - aMaster) * k;
            aHalfX += (tHalfX - aHalfX) * k;
            aHalfY += (tHalfY - aHalfY) * k;
            aCorner += (tCorner - aCorner) * k;
            if (renderer != null) {
                renderer.siriGeom = true;
                renderer.svMaster = aMaster;
                renderer.svHalfX = aHalfX;
                renderer.svHalfY = aHalfY;
                renderer.svCorner = aCorner;
                renderer.svPosX = 0.5f;
                renderer.svPosY = 0.5f;
            }
            updateSiriTextPos();
            main.postDelayed(this, 16);
        }
    };

    private void updateSiriTextPos() {
        if (siriText == null || siriParams == null || wm == null) return;
        float winXnow = (renderer != null) ? renderer.winX : params.x;
        float winYnow = (renderer != null) ? renderer.winY : params.y;
        float base = winSize * 0.9f;
        if (base <= 0) base = 1f;
        float sx = winXnow + winSize * 0.5f;
        float sy = winYnow + winSize * 0.5f;
        float hypx = base * aHalfY * aMaster;     // 当前球半高
        float ballTop = sy - hypx;
        int textMaxW = siriTextMaxW();
        siriText.setMaxWidth(textMaxW);
        siriText.measure(View.MeasureSpec.makeMeasureSpec(textMaxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int tw = siriText.getMeasuredWidth();
        siriParams.x = Math.round(sx - tw / 2f);
        siriParams.y = Math.round(ballTop + dp(8));    // 贴近球顶 → 文字在水晶球顶部
        try { wm.updateViewLayout(siriText, siriParams); } catch (Exception ignored) { }
        siriText.setAlpha(Math.max(0f, Math.min(1f, (aMaster - 0.1f) / 0.7f)));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private final MediaProjection.Callback projectionCb = new MediaProjection.Callback() {
        @Override public void onStop() { stopSelf(); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        INSTANCE = this;
        exemptHiddenApis();
        createChannel();
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

    /** 把 assets/caplive.jar 释放到 filesDir，返回绝对路径（供 root app_process 以 CLASSPATH 加载）。 */
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
        winSize = (int) (Math.min(screenW, screenH) * 0.92f);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        glView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        glView.setZOrderOnTop(true);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new GlassRenderer(screenW, screenH, winSize, winSize);
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
            renderer.setOnSurfaceReady(st -> main.post(() -> rootCapture.start(renderer)));
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
        params = new WindowManager.LayoutParams(
                winSize, winSize, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (screenW - winSize) / 2;
        params.y = (screenH - winSize) / 2;
        renderer.winX = params.x;
        renderer.winY = params.y;

        glView.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = params.x; startY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = (int) (startX + (e.getRawX() - downX));
                        params.y = (int) (startY + (e.getRawY() - downY));
                        params.x = Math.max(-winSize / 4, Math.min(screenW - winSize * 3 / 4, params.x));
                        params.y = Math.max(-winSize / 4, Math.min(screenH - winSize * 3 / 4, params.y));
                        if (wm != null) wm.updateViewLayout(glView, params);
                        glView.post(winSync);
                        return true;
                    default:
                        return false;
                }
            }
        });

        wm.addView(glView, params);
        glView.onResume();
        glView.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> syncWinPos());
        glView.post(winSync);
        main.post(renderTick);
        main.postDelayed(winSyncLoop, 500);
        applySavedParams();
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
        // Surface 就绪后打上 SKIP_SCREENSHOT：抓屏不再包含本窗口 → 球内实时折射真实桌面
        if (!renderer.selfExcluded && applySkipScreenshot()) {
            renderer.selfExcluded = true;
        }
    }

    // ───────────────────────── 参数同步（来自 liquid-glass 页面）─────────────────────────
    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("watch", MODE_PRIVATE);
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
        main.removeCallbacks(renderTick);
        main.removeCallbacks(siriTick);
        removeSiriText();
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
