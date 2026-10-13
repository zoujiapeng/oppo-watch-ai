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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

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

    private final MediaProjection.Callback projectionCb = new MediaProjection.Callback() {
        @Override public void onStop() { stopSelf(); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        INSTANCE = this;
        createChannel();
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
        int src = prefs().getInt("float_capture_src", 0);
        renderer.captureSource = src;
        if (src == 1) {
            // root 直采：无投影、无录屏
            rootCapture = new RootCapture();
            rootCapture.setErrorListener(msg -> main.post(() -> {
                if (rootError == null) { rootError = msg; }
            }));
            renderer.setOnSurfaceReady(st -> main.post(() -> rootCapture.start(renderer)));
        } else {
            renderer.setOnSurfaceReady(st -> main.post(() -> startCapture(st)));
        }
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
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
        main.postDelayed(winSyncLoop, 500);
        applySavedParams();
        renderer.bgMode = prefs().getInt("float_mode", 0);
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
