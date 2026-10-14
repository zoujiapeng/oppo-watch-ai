package com.glslplayground.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * 水晶球的语音助手：把「悬浮球 → DSH → 球内 Siri 模式文字」串成一个闭环。
 *
 *   点球 → 采集麦克风（{@link VoiceCapture}，16 kHz PCM16 WAV）
 *        → DSH 转写（DSH 的语音插件，provider 由 profile 决定）
 *        → 作为 prompt 发给 DSH 会话
 *        → 流式读回助手回答，边收边写进 {@link FloatingBallService#setSiriText}
 *          （球体随之变形：1~2 行胶囊 / 3 行以上大圆角方形，黑区按行数下移）
 *        → 可选：用系统 TTS 念出来
 *
 * 传输层是 {@link Transport}，由 {@link DshHttpTransport} 实现，指向本机 DSH 服务。
 */
public final class SiriAssistant {

    private static final String TAG = "SiriAssistant";
    private static SiriAssistant instance;

    public interface Result<T> {
        void ok(T value);
        void fail(String message);
    }

    public interface Stream {
        /** @param soFar 到目前为止的完整回答文本（增量已合并） */
        void onDelta(String soFar);
        void onDone(String full);
        void onError(String message);
    }

    /** DSH 侧能力：转写 + 问答（会话的挑选由实现负责）。 */
    public interface Transport {
        void transcribe(String wavBase64, Result<String> cb);
        void ask(String text, Stream cb);
        void cancel();
        void shutdown();
        /** 供设置页展示：当前指向的 DSH 地址。 */
        String endpoint();
    }

    public enum State { OFF, IDLE, LISTENING, TRANSCRIBING, THINKING, ANSWERING }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VoiceCapture capture = new VoiceCapture();
    private Transport transport;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean speakReplies = true;
    private boolean enabled = true;

    private State state = State.IDLE;
    private String lastAnswer = "";

    private SiriAssistant(Context c) {
        this.app = c.getApplicationContext();
        android.content.SharedPreferences sp =
                app.getSharedPreferences("siri_assistant", Context.MODE_PRIVATE);
        enabled = sp.getBoolean("enabled", true);
        speakReplies = sp.getBoolean("speak", true);
        try {
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    if (ttsReady) {
                        try { tts.setLanguage(Locale.CHINA); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            tts = null;
        }
        // 传输层默认指向本机 DSH 服务；地址可在设置页改，存在同一个 prefs 里
        setTransport(new DshHttpTransport(app, sp.getString("endpoint", DshHttpTransport.DEFAULT_ENDPOINT)));
    }

    private android.content.SharedPreferences prefs() {
        return app.getSharedPreferences("siri_assistant", Context.MODE_PRIVATE);
    }

    public static synchronized SiriAssistant get(Context c) {
        if (instance == null) instance = new SiriAssistant(c);
        return instance;
    }

    public static synchronized SiriAssistant peek() { return instance; }

    public State state() { return state; }
    public boolean isBusy() {
        return state == State.LISTENING || state == State.TRANSCRIBING || state == State.THINKING;
    }
    public String lastAnswer() { return lastAnswer; }

    public void setSpeakReplies(boolean v) {
        speakReplies = v;
        prefs().edit().putBoolean("speak", v).apply();
        if (!v) stopTts();
    }
    public boolean speakReplies() { return speakReplies; }

    public void setEnabled(boolean v) {
        enabled = v;
        prefs().edit().putBoolean("enabled", v).apply();
        if (!v) {
            capture.cancel();
            if (transport != null) transport.cancel();
            stopTyping();
            setState(State.OFF);
            FloatingBallService.setSiriText("");
        }
    }
    public boolean enabled() { return enabled; }

    /** 设置页改 DSH 地址。 */
    public void setEndpoint(String url) {
        if (url == null || url.trim().isEmpty()) return;
        String clean = url.trim();
        prefs().edit().putString("endpoint", clean).apply();
        setTransport(new DshHttpTransport(app, clean));
    }
    public String endpoint() {
        return transport == null ? DshHttpTransport.DEFAULT_ENDPOINT : transport.endpoint();
    }

    public synchronized void setTransport(Transport t) {
        if (transport != null && transport != t) {
            try { transport.shutdown(); } catch (Throwable ignored) {}
        }
        transport = t;
    }

    // ── 交互入口：点一下球 ─────────────────────────────────────────────

    /** 空闲 → 开始聆听；聆听中 → 结束并发送；思考/回答中 → 打断。 */
    public void toggle() {
        if (!enabled) return;
        switch (state) {
            case LISTENING:   finishListening(); break;
            case TRANSCRIBING:
            case THINKING:    abort("已取消"); break;
            case ANSWERING:   abort(""); break;
            case IDLE:
            case OFF:         beginListening(); break;
            default:          break;
        }
    }

    public void beginListening() {
        if (!enabled) return;
        if (transport == null) {
            toast("DSH 未连接");
            return;
        }
        if (!ensureMicPermission()) {
            toast("需要麦克风权限：请在设置里允许后重试");
            return;
        }
        say("");
        setState(State.LISTENING);
        final boolean ok = capture.start(new VoiceCapture.Listener() {
            @Override public void onLevel(final float rms) {
                FloatingBallService.setAssistantLevel(rms);
            }
            @Override public void onError(final String reason) {
                main.post(new Runnable() {
                    @Override public void run() {
                        toast(reason);
                        setState(State.IDLE);
                    }
                });
            }
        });
        if (!ok) setState(State.IDLE);
    }

    public void finishListening() {
        if (state != State.LISTENING) return;
        setState(State.TRANSCRIBING);
        FloatingBallService.setAssistantLevel(0f);
        byte[] wav = capture.stop();
        if (wav == null) {
            toast("没有录到声音");
            setState(State.IDLE);
            return;
        }
        say("识别中…");
        final Transport t = transport;
        if (t == null) { setState(State.IDLE); return; }
        t.transcribe(VoiceCapture.toBase64(wav), new Result<String>() {
            @Override public void ok(final String text) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            toast("没听清，再说一次");
                            say("");
                            setState(State.IDLE);
                            return;
                        }
                        ask(text.trim());
                    }
                });
            }
            @Override public void fail(final String message) {
                main.post(new Runnable() {
                    @Override public void run() {
                        toast("识别失败：" + message);
                        say("");
                        setState(State.IDLE);
                    }
                });
            }
        });
    }

    /** 直接以文本提问（设置页 / 调试用，也可给键盘输入用）。 */
    public void ask(final String text) {
        if (transport == null) { toast("DSH 未连接"); return; }
        setState(State.THINKING);
        lastAnswer = "";
        say("思考中…");
        transport.ask(text, new Stream() {
            @Override public void onDelta(final String soFar) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (state != State.THINKING && state != State.ANSWERING) return;
                        if (state != State.ANSWERING) setState(State.ANSWERING);
                        sayTyped(soFar);
                    }
                });
            }
            @Override public void onDone(final String full) {
                main.post(new Runnable() {
                    @Override public void run() {
                        lastAnswer = full == null ? "" : full;
                        if (!lastAnswer.isEmpty()) sayTyped(lastAnswer);   // 交给打字机把剩下的字弹完
                        setState(State.IDLE);
                        if (speakReplies && !lastAnswer.isEmpty()) speak(lastAnswer);
                    }
                });
            }
            @Override public void onError(final String message) {
                main.post(new Runnable() {
                    @Override public void run() {
                        toast("DSH 出错：" + message);
                        setState(State.IDLE);
                    }
                });
            }
        });
    }

    private void abort(String note) {
        capture.cancel();
        if (transport != null) transport.cancel();
        stopTyping();
        FloatingBallService.setAssistantLevel(0f);
        if (note != null && !note.isEmpty()) toast(note);
        setState(State.IDLE);
    }

    public void shutdown() {
        capture.cancel();
        if (transport != null) transport.shutdown();
        stopTts();
        setState(State.OFF);
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private boolean hasMicPermission() {
        try {
            return app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;   // API < 23：安装即授权
        }
    }

    /**
     * 录音权限：设备所有者身份可以直接静默授予自己；否则拉起 MainActivity 弹一次系统对话框。
     */
    private boolean ensureMicPermission() {
        if (hasMicPermission()) return true;
        try {
            android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName admin =
                    new android.content.ComponentName(app, AdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(app.getPackageName())) {
                dpm.setPermissionGrantState(admin, app.getPackageName(),
                        android.Manifest.permission.RECORD_AUDIO,
                        android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            }
        } catch (Throwable t) {
            Log.w(TAG, "device-owner grant failed: " + t);
        }
        if (hasMicPermission()) return true;
        try {
            android.content.Intent i = new android.content.Intent(app, MainActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("request_mic", true);
            app.startActivity(i);
        } catch (Throwable ignored) {}
        return false;
    }

    private void setState(State s) {
        state = s;
        FloatingBallService.setAssistantState(s.name());
    }

    // ── 球内文字：一个字一个字弹出来 ────────────────────────────────────
    // 模型是按 token 吐的（一次几个字），这里再按固定节奏逐字揭示，
    // 于是无论模型吐得多快，看到的都是「打字机」效果；落後太多时略加速，
    // 但绝不跳字。每次只调 setSiriText，排版/字号/黑区仍由 App 原有设定决定。

    private String targetText = "";
    private String shownText = "";
    private boolean typing = false;

    private final Runnable typist = new Runnable() {
        @Override public void run() {
            if (!typing) return;
            int shown = shownText.length();
            int target = targetText.length();
            if (shown >= target) { typing = false; return; }
            int lag = target - shown;
            int step = lag > 80 ? 4 : lag > 30 ? 2 : 1;
            shownText = targetText.substring(0, Math.min(target, shown + step));
            FloatingBallService.setSiriText(shownText);
            main.postDelayed(this, 40);
        }
    };

    private void stopTyping() {
        typing = false;
        main.removeCallbacks(typist);
    }

    /** 立刻替换球内文字（状态提示用）。 */
    private void sayNow(String text) {
        String t = text == null ? "" : text;
        stopTyping();
        targetText = t;
        shownText = t;
        FloatingBallService.setSiriText(t);
    }

    /** 逐字揭示（回答用）；target 可以在揭示过程中不断变长。 */
    private void sayTyped(String text) {
        String t = text == null ? "" : text;
        if (!t.startsWith(shownText)) {         // 换了一轮回答 → 从头弹
            shownText = "";
            stopTyping();
        }
        targetText = t;
        if (!typing && shownText.length() < targetText.length()) {
            typing = true;
            main.post(typist);
        }
    }

    /** 写进球的 Siri 模式文字（兼容旧调用点）。 */
    private void say(String text) {
        sayNow(text);
    }

    private void toast(final String msg) {
        Log.i(TAG, msg);
        try {
            android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        try {
            tts.stop();
            tts.speak(text.length() > 800 ? text.substring(0, 800) : text,
                    TextToSpeech.QUEUE_FLUSH, null, "dsh-siri");
        } catch (Throwable ignored) {}
    }

    private void stopTts() {
        if (tts != null) { try { tts.stop(); } catch (Throwable ignored) {} }
    }
}
