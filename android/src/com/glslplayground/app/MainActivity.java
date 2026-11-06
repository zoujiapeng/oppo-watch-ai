package com.glslplayground.app;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int MEDIA_PROJECTION_REQUEST = 1002;
    private static final int OVERLAY_REQUEST = 1003;

    /**
     * 初始状态的语音输入要麦克风。Android 6+ 是运行时权限 —— 打开 App（首页/设置页）时
     * 顺手要一次；用户拒绝也不影响别的功能（球只是不会自动听，点球会说"麦克风不可用"）。
     */
    private void askRecordAudio() {
        try {
            if (Build.VERSION.SDK_INT < 23) return;
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1004);
        } catch (Throwable ignored) { }
    }

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean kiosk = false;
    private static boolean autoKioskDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        askRecordAudio();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (isKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        goImmersive();

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setBackgroundColor(0xFF0A0A0C);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // 网页里通过 window.Watch.xxx() 调用系统能力
        webView.addJavascriptInterface(new WatchBridge(this), "Watch");

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        setContentView(webView, lp);

        armVoiceAck();

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
        maybeAutoFloat(getIntent());
    }

    private void goImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        if (!autoKioskDone && isAutoKiosk() && isDeviceOwner()) {
            autoKioskDone = true;
            enableKiosk();
        }
    }

    /**
     * 长按电源键那条路（{@link PowerBallReceiver}）在没开 root 直采时只能把人拉到前台，
     * 由这里补一次「悬浮于桌面」的请求（会走 MediaProjection 授权）。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeAutoFloat(intent);
    }

    private void maybeAutoFloat(Intent it) {
        if (it == null || !it.getBooleanExtra("auto_float", false)) return;
        FloatingBallService.setUiHidden(false, "打开 App");   // 被音量加藏起来时，打开 App 就回来
        it.removeExtra("auto_float");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { requestFloat(); }
        }, 500);
    }

    // ---------- 偏好设置（供 JS 桥读写）----------
    private SharedPreferences prefs() {
        return getSharedPreferences("watch", MODE_PRIVATE);
    }

    public boolean isAutoKiosk() {
        return prefs().getBoolean("autoKiosk", false);
    }

    public void setAutoKiosk(boolean v) {
        prefs().edit().putBoolean("autoKiosk", v).apply();
    }

    public boolean isKeepScreenOn() {
        return prefs().getBoolean("keepScreenOn", true);
    }

    public void setKeepScreenOn(boolean v) {
        prefs().edit().putBoolean("keepScreenOn", v).apply();
        if (v) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------- Kiosk（锁定任务）----------
    public boolean isDeviceOwner() {
        try {
            DevicePolicyManager d =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            return d != null && d.isDeviceOwnerApp(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public void enableKiosk() {
        try {
            DevicePolicyManager d =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (d != null && d.isDeviceOwnerApp(getPackageName())) {
                d.setLockTaskPackages(admin, new String[]{getPackageName()});
                if (Build.VERSION.SDK_INT >= 28) {
                    d.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                }
            }
            startLockTask();
            kiosk = true;
        } catch (Exception ignored) { }
    }

    public void disableKiosk() {
        try {
            stopLockTask();
        } catch (Exception ignored) { }
        kiosk = false;
    }

    public boolean isKiosk() {
        return kiosk;
    }

    // ---------- 悬浮水晶球 ----------
    public void requestFloat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, OVERLAY_REQUEST);
            } catch (Exception e) {
                // 部分 ROM 无此页面，直接按当前采集源启动
                beginFloat();
            }
            return;
        }
        beginFloat();
    }

    // 采集源：1=root 直采（直接启动，无录屏授权）；0=MediaProjection（需授权）
    private void beginFloat() {
        if (getFloatCaptureSrc() == 1) {
            startFloatServiceDirect();
        } else {
            startMediaProjection();
        }
    }

    private void startFloatServiceDirect() {
        Intent i = new Intent(this, FloatingBallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void startMediaProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST);
        } catch (Exception ignored) { }
    }

    public void stopFloat() {
        stopService(new Intent(this, FloatingBallService.class));
    }

    public boolean isFloating() {
        return FloatingBallService.running;
    }

    public void applyFloatParams(String json) {
        if (json == null) return;
        prefs().edit().putString("float_params", json).apply();
        if (FloatingBallService.running) {
            FloatingBallService.updateParams(json);
        }
    }

    // 返回已保存的液态玻璃参数（JSON），未设置时为 null
    public String getFloatParams() {
        return prefs().getString("float_params", null);
    }

    public void setFloatMode(int mode) {
        prefs().edit().putInt("float_mode", mode).apply();
        if (FloatingBallService.running) FloatingBallService.setMode(mode);
    }

    public int getFloatMode() {
        return prefs().getInt("float_mode", 0);
    }

    // 采集源：0=MediaProjection（录屏） 1=root 直采（无录屏）
    public int getFloatCaptureSrc() {
        return prefs().getInt("float_capture_src", 0);
    }

    public void setFloatCaptureSrc(int src) {
        prefs().edit().putInt("float_capture_src", src).apply();
        if (FloatingBallService.running) {
            stopFloat();
            getWindow().getDecorView().postDelayed(new Runnable() {
                public void run() { beginFloat(); }
            }, 300);
        }
    }

    // 悬浮水晶球目标帧率（1~144）
    public int getFloatFps() {
        return prefs().getInt("float_fps", 60);
    }

    public void setFloatFps(int fps) {
        fps = Math.max(1, Math.min(144, fps));
        prefs().edit().putInt("float_fps", fps).apply();
        if (FloatingBallService.running) {
            FloatingBallService.setFps(fps);
        }
    }

    // 低延迟采集（root 常驻）与抓屏缩放
    public boolean getFloatLowLat() {
        return prefs().getBoolean("float_lowlat", false);
    }

    public void setFloatLowLat(boolean v) {
        prefs().edit().putBoolean("float_lowlat", v).apply();
        restartFloat();
    }

    public int getFloatCapScale() {
        return prefs().getInt("float_cap_scale", 2);
    }

    public void setFloatCapScale(int s) {
        s = Math.max(1, Math.min(8, s));
        prefs().edit().putInt("float_cap_scale", s).apply();
        restartFloat();
    }

    private void restartFloat() {
        if (FloatingBallService.running) {
            stopFloat();
            getWindow().getDecorView().postDelayed(new Runnable() {
                public void run() { beginFloat(); }
            }, 300);
        }
    }

    // Siri 模式：文字形变球
    public boolean getSiriEnabled() {
        return prefs().getBoolean("float_siri", false);
    }

    public void setSiriEnabled(boolean v) {
        prefs().edit().putBoolean("float_siri", v).apply();
        // 窗口高度需要随 Siri 模式改变 → 重启悬浮球
        restartFloat();
    }

    public void sendSiriText(String text) {
        if (FloatingBallService.running) {
            FloatingBallService.setSiriText(text);
        }
    }

    // 文字相对球顶的上下偏移(px)，正=更低
    public int getSiriTextDy() {
        return prefs().getInt("float_siri_dy", 10);
    }

    public void setSiriTextDy(int dy) {
        dy = Math.max(-120, Math.min(240, dy));
        prefs().edit().putInt("float_siri_dy", dy).apply();
        if (FloatingBallService.running) {
            FloatingBallService.setSiriTextDy(dy);
        }
    }

    // Siri 文字字号(sp)
    public int getSiriTextSp() {
        return prefs().getInt("float_siri_sp", 34);
    }

    public void setSiriTextSp(int sp) {
        sp = Math.max(10, Math.min(120, sp));
        prefs().edit().putInt("float_siri_sp", sp).apply();
        if (FloatingBallService.running) {
            FloatingBallService.setSiriTextSize(sp);
        }
    }

    // 球内 AI 回复（富文本）正文字号比例（百分数，25 = 0.25）：正文 = 「Siri 字号」× 它
    public int getMdFontRatioPct() {
        return prefs().getInt("md_font_ratio_pct", 25);
    }

    public void setMdFontRatioPct(int pct) {
        pct = Math.max(5, Math.min(120, pct));
        prefs().edit().putInt("md_font_ratio_pct", pct).apply();
        if (FloatingBallService.running) FloatingBallService.onMdStyleChanged();
    }

    // 审批/提问面板整体缩放（百分数，55 = 0.55）
    public int getApScalePct() {
        return prefs().getInt("ap_scale_pct", 55);
    }

    public void setApScalePct(int pct) {
        pct = Math.max(20, Math.min(150, pct));
        prefs().edit().putInt("ap_scale_pct", pct).apply();
        FloatingBallService.onApStyleChanged();
    }

    /** 离线预览：往球里灌一段示例 markdown（不依赖 DSH），并钉住方便调字号。 */
    public void previewSiriText() {
        String sample = FloatingBallService.readPreviewSample();
        setSiriEnabled(true);
        if (!FloatingBallService.running) {
            android.util.Log.w("MainActivity", "离线预览：悬浮球没开（先打开「悬浮于桌面」）");
            return;
        }
        FloatingBallService.previewText(sample != null ? sample : MD_SAMPLE);
    }

    public void previewSiriTextStop() {
        FloatingBallService.previewStop();
    }

    /** 内置预览样例（有 files/md-sample.md 时用那个）。 */
    private static final String MD_SAMPLE =
            "# 排版预览\n\n这是一段用于调字号的示例 markdown。\n\n"
            + "| 方案 | 成本 | 说明 |\n|---|---|---|\n"
            + "| 本地规则清洗 | 0 | 零依赖，语义不变 |\n"
            + "| 模型改写 | 1 次小请求 | 需要 dsh-llm |\n\n"
            + "1. 第一步：拖字号滑块\n2. 第二步：看球里的变化\n   - 嵌套一层也能对齐\n\n"
            + "> 引用：黑区下沿按文字实际高度算。\n\n"
            + "```bash\necho 0.25 > files/md-ratio\n```\n\n"
            + "最后一段普通文字，验证换行与滚动。\n";

    // 文字下沿与 Siri 波的安全距离（千分之一单位，100=0.10）；
    // 黑区下沿本身由文字实际高度决定，不再按行数下移。
    public int getSiriBlackStep() {
        return prefs().getInt("float_siri_wavegap", 100);
    }

    public void setSiriBlackStep(int v) {
        v = Math.max(0, Math.min(450, v));
        prefs().edit().putInt("float_siri_wavegap", v).apply();
        if (FloatingBallService.running) {
            FloatingBallService.setSiriBlackStep(v);
        }
    }

    // ---------- WebView / 生命周期 ----------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        if (requestCode == OVERLAY_REQUEST) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                beginFloat();
            }
            return;
        }
        if (requestCode == MEDIA_PROJECTION_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Intent i = new Intent(this, FloatingBallService.class);
                i.putExtra("code", resultCode);
                i.putExtra("data", data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ── 设置页「按住说话」的无 root 兜底 ────────────────────────────────────
    //
    // VoiceMap 那条路的前提是 root + LSPosed（模块注入输入法进程、往键盘发触摸），
    // 缺一样都出不了字。所以同一个按钮还有第二条路：直接走本 App 自己的语音链路
    // （AndroidAsr 系统识别 → 不行再 VoiceCapture + DSH speech/transcribe），
    // 结果用 evaluateJavascript 回调给页面。两条路都不需要输入法配合。

    private BallVoice talk;
    private volatile String talkState = "";

    public void startVoiceTalk() {
        if (talk == null) talk = new BallVoice(this);
        if (talk.busy()) return;
        talk.start(new BallVoice.Cb() {
            @Override public void onListening() {
                talkState = "listening";
                emitVoice("listening", "");
            }
            @Override public void onLevel(float level) { }
            @Override public void onTranscribing() {
                talkState = "transcribing";
                emitVoice("transcribing", "");
            }
            @Override public void onHeard(String text, boolean auto) {
                talkState = "";
                emitVoice("result", text);
            }
            @Override public void onError(String message) {
                talkState = "";
                emitVoice("error", message);
            }
        });
    }

    public void stopVoiceTalk() {
        if (talk != null) talk.tapStop();
    }

    public String voiceTalkState() { return talkState; }

    /**
     * 模块把 ACTION_DOWN 真正注进键盘那一刻会回一条广播（com.pj.voicemap.ACK, msg=down/…）。
     * 收到就点亮"正在听"并轻震一下 —— 从按下到输入法真的开始录音之间必然有几百毫秒，
     * 有了这个提示，用户知道"现在说话"，就不会把开头几个字白白丢掉。
     */
    private BroadcastReceiver vmAck;
    private boolean vmAckRegistered;

    private void armVoiceAck() {
        if (vmAckRegistered) return;
        try {
            vmAck = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent in) {
                    String msg = in == null ? null : in.getStringExtra("msg");
                    if (msg == null) return;
                    if (msg.startsWith("down")) {
                        emitVoice("holding", "");
                        try {
                            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                            if (v != null) v.vibrate(25);
                        } catch (Throwable ignored) { }
                    }
                }
            };
            IntentFilter f = new IntentFilter("com.pj.voicemap.ACK");
            // 本 App targetSdk=28，注册出来的 receiver 默认就是 exported，
            // 不需要 API 33 的 RECEIVER_EXPORTED 标志（编译用的 android.jar 也没这个常量）。
            registerReceiver(vmAck, f);
            vmAckRegistered = true;
        } catch (Throwable t) {
            Log.w("VoiceAck", "register failed: " + t);
        }
    }

    @Override
    protected void onDestroy() {
        if (vmAckRegistered && vmAck != null) {
            try { unregisterReceiver(vmAck); } catch (Throwable ignored) { }
            vmAckRegistered = false;
        }
        super.onDestroy();
    }

    private void emitVoice(final String evt, final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                String js = "window.vmOnVoice&&window.vmOnVoice("
                        + jsLit(evt) + "," + jsLit(text) + ")";
                try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) { }
            }
        });
    }

    private static String jsLit(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') continue;
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append("'").toString();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            if (kiosk) return true;   // 锁定状态吞掉返回键
        }
        return super.onKeyDown(keyCode, event);
    }
}
