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

    /**
     * 会话镜像：不管这条回复是谁问的，只要 DSH 当前会话产出助手文本就回调。
     * 回调在后台线程，实现方自己 post 到主线程。
     */
    public interface Mirror {
        void onAttemptStart();
        void onText(String soFar);
        void onFinal(String text);
        void onTurnEnd();
        void onStatus(String note);
    }

    /** DSH 侧能力：转写 + 问答（会话的挑选由实现负责）。 */
    public interface Transport {
        void transcribe(String wavBase64, Result<String> cb);
        void ask(String text, Stream cb);
        void cancel();
        void shutdown();
        /** 供设置页展示：当前指向的 DSH 地址。 */
        String endpoint();
        /** 常驻镜像：跟着当前会话，AI 一有输出就往球里打。 */
        void startMirror(Mirror mirror);
        void stopMirror();
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

    // ── 会话镜像：DSH 里只要 AI 在回话，球就出字（不管是谁问的）──
    private boolean mirrorEnabled = true;
    private volatile boolean askInFlight = false;
    private final StringBuilder mirrorBuf = new StringBuilder();
    private volatile String mirrorStatus = "";

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
        mirrorEnabled = sp.getBoolean("mirror", true);
        approvalEnabled = sp.getBoolean("approval_orb", true);
    }

    // ── 审批球：DSH 要你点「批准/拒绝」时，弹一颗新的液态玻璃球 ──────────
    // 这条链路和语音助手是两回事（不录音、不提问、不碰球内文字），只是共用
    // 同一个 DSH 地址与悬浮球服务。见 DshApprovalClient 的头注释。

    private DshApprovalClient approvals;
    private boolean approvalEnabled = true;
    private volatile String approvalStatus = "";

    /** 审批流的客户端（球与设置页共用同一条连接）。 */
    public synchronized DshApprovalClient approvals() {
        if (approvals == null) approvals = new DshApprovalClient(app);
        return approvals;
    }

    public boolean approvalEnabled() { return approvalEnabled; }

    public void setApprovalEnabled(boolean v) {
        approvalEnabled = v;
        prefs().edit().putBoolean("approval_orb", v).apply();
        if (v) startApprovals(); else stopApprovals();
    }

    /** 状态：连没连上审批流（设置页展示）。 */
    public String approvalStatus() { return approvalStatus; }

    /** 起审批流；球挂上回调，请求来了就弹球。 */
    public void startApprovals() {
        try {
            startApprovalsInner();
        } catch (Throwable t) {
            Log.w(TAG, "startApprovals failed: " + t, t);
        }
    }

    private void startApprovalsInner() {
        Log.i(TAG, "startApprovals: enabled=" + approvalEnabled);
        if (!approvalEnabled) return;
        final DshApprovalClient c = approvals();
        c.setListener(new DshApprovalClient.Listener() {
            @Override public void onRequest(DshApprovalClient.Request request) {
                Log.i(TAG, "approval listener onRequest: "
                        + (request == null ? "null" : request.title()));
                FloatingBallService.ensureSiriForAssistant();
                FloatingBallService s = FloatingBallService.instance();
                if (s != null) s.onApprovalRequest(request);
                else Log.w(TAG, "审批来了但球没起来（悬浮球未开启？）");
            }

            @Override public void onWithdraw(String eventId) {
                FloatingBallService s = FloatingBallService.instance();
                if (s != null) s.onApprovalWithdraw(eventId);
            }

            @Override public void onStatus(String note) {
                approvalStatus = note;
            }
        });
        c.start();
    }

    public void stopApprovals() {
        if (approvals != null) approvals.stop();
        FloatingBallService.dismissApprovalIfAny();
        approvalStatus = "审批球已关闭";
    }

    public static synchronized SiriAssistant get(Context c) {
        if (instance == null) instance = new SiriAssistant(c);
        return instance;
    }

    public void setMirrorEnabled(boolean v) {
        mirrorEnabled = v;
        prefs().edit().putBoolean("mirror", v).apply();
        if (v) startMirror(); else stopMirror();
    }

    public boolean mirrorEnabled() { return mirrorEnabled; }

    public String mirrorStatus() { return mirrorStatus; }

    /** 起一条常驻镜像流：跟着当前会话，AI 一有输出就往球里打。 */
    public void startMirror() {
        if (!mirrorEnabled || transport == null) return;
        transport.startMirror(new Mirror() {
            @Override public void onAttemptStart() {
                if (askInFlight) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (askInFlight) return;
                        mirrorBuf.setLength(0);
                        // 模型开始生成了 —— 这一刻才算「思考中」
                        if (state != State.LISTENING && state != State.TRANSCRIBING) {
                            setState(State.THINKING);
                        }
                    }
                });
            }

            @Override public void onText(final String delta) {
                if (askInFlight) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (askInFlight) return;
                        mirrorBuf.append(delta);
                        DshHttpTransport.capBuffer(mirrorBuf, 1200);   // 长任务别把几万字一起灌进球
                        if (state != State.LISTENING && state != State.TRANSCRIBING) {
                            if (state != State.ANSWERING) setState(State.ANSWERING);
                        }
                        sayTyped(mirrorBuf.toString());
                    }
                });
            }

            @Override public void onFinal(final String text) {
                if (askInFlight) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (askInFlight) return;
                        mirrorBuf.setLength(0);
                        mirrorBuf.append(DshHttpTransport.clip(text, 1200));
                        lastAnswer = text;
                        if (state != State.LISTENING && state != State.TRANSCRIBING) setState(State.ANSWERING);
                        sayTyped(text);
                    }
                });
            }

            @Override public void onTurnEnd() {
                if (askInFlight) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (askInFlight) return;
                        if (state == State.THINKING || state == State.ANSWERING) setState(State.IDLE);
                        if (speakReplies && mirrorBuf.length() > 0) speak(mirrorBuf.toString());
                    }
                });
            }

            @Override public void onStatus(final String note) {
                mirrorStatus = note;
            }
        });
    }

    public void stopMirror() {
        if (transport != null) transport.stopMirror();
        mirrorStatus = "";
    }

    private android.content.SharedPreferences prefs() {
        return app.getSharedPreferences("siri_assistant", Context.MODE_PRIVATE);
    }

    public static synchronized SiriAssistant peek() { return instance; }

    public State state() { return state; }

    /** 当前状态名（日志与球体截图用）。 */
    public static String stateName() {
        SiriAssistant a = instance;
        return a == null ? "?" : a.state.name();
    }
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
            stopMirror();
            stopTyping();
            setState(State.OFF);
            FloatingBallService.setSiriText("");
        } else {
            startMirror();
        }
        // 审批球是独立的一条链路：语音助手开着或关着，它都照常工作
        if (v) startApprovals(); else stopApprovals();
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
        // 转写期间球里不写字（只留波形随音量呼吸），识别完直接把「你说了什么」显示出来
        final Transport t = transport;
        if (t == null) { setState(State.IDLE); return; }
        t.transcribe(VoiceCapture.toBase64(wav), new Result<String>() {
            @Override public void ok(final String text) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            toast("没听清，再说一次");
                            sayNow("");
                            setState(State.IDLE);
                            return;
                        }
                        // 先把你说的那句显示在球里，回答到了再逐字替换
                        sayNow(text.trim());
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
        FloatingBallService.setAssistantPinned(false);   // 新一轮开始：解除文字钉住
        setState(State.THINKING);
        lastAnswer = "";
        // 球里不显示这句提问：球内只允许出现「语音识别到的字」和 AI 的回答，
        // 别的字（思考中…、提问原文、状态词）一律不写。
        askInFlight = true;          // 这一轮由本 App 自己收流，镜像先让路，避免重复打字
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
                        askInFlight = false;
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
                        askInFlight = false;
                        toast("DSH 出错：" + message);
                        setState(State.IDLE);
                    }
                });
            }
        });
    }

    /** 长按手势：取消这一轮（录音中或正在问答），并清空球内文字。 */
    public void cancelByGesture() {
        abort("已取消");
        sayNow("");
    }

    private void abort(String note) {
        capture.cancel();
        if (transport != null) transport.cancel();
        askInFlight = false;
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
        // 文字的清空由「安静计时」负责（见 quietTick）：不管是否收到 turn/end，
        // 只要球内文字停了一会儿没变，就自己消散 —— 球恢复成原本那颗水晶球。
        armQuiet();
    }

    /**
     * 回答结束后，球内文字还能留多久。
     * 「球变回原本状态」和「文字消失」是同一件事：打字机弹完最后一个字，
     * 再静 IDLE_CLEAR_MS 没有新字进来，就把文字清掉、状态回 IDLE，球随即收缩回原本那颗水晶球。
     *
     * 为什么不能只等 turn/end：镜像跟随的那条会话可能是一轮跑很久的长任务
     * （几十个 step、几分钟都不 end），只认 turn/end 的话文字会一直挂在那儿 ——
     * 用户看到的就是「回答完变回球了，字还留着，怪怪的」。
     */
    // 用户要求「出现文字时停留时间长一点」：安静这么久才把球内文字清掉。
    // 注意球侧 IDLE_SETTLE_MS（几何/动画回初始）要比它略长，让「文字消失 → 球回圆球」有先后。
    private static final long IDLE_CLEAR_MS = 4000;

    /** 最近一次球内文字有变化的时间（打字机每弹一个字都会刷新）。 */
    private volatile long lastTextAt = android.os.SystemClock.uptimeMillis();
    private final Runnable quietTick = new Runnable() {
        @Override public void run() {
            // 用户点过球内文字 = 钉住：文字保持不动，直到他再点一下文字解除干预
            if (FloatingBallService.isAssistantTextPinned()) return;
            long quiet = android.os.SystemClock.uptimeMillis() - lastTextAt;
            // 打字机队列里还有没弹完的字 → 重排计时，别在半途把字清掉
            if (targetText.length() > shownText.length()) { armQuiet(); return; }
            if (quiet < IDLE_CLEAR_MS) { main.postDelayed(this, 200); return; }
            if (shownText != null && !shownText.isEmpty()) sayNow("");   // 文字消散，球自己缩回原样
            // 不管有没有字：球不该一直挂着「回答中/思考中」的光效
            if (state == State.ANSWERING || state == State.THINKING) setState(State.IDLE);
        }
    };

    /** 供球侧调用（用户解除钉住后重新开始安静计时）。 */
    public void rearmQuietPublic() {
        main.post(new Runnable() {
            @Override public void run() { armQuiet(); }
        });
    }

    /** 重排安静计时（每次有字进来都调一次）。 */
    private void armQuiet() {
        main.removeCallbacks(quietTick);
        main.postDelayed(quietTick, IDLE_CLEAR_MS + 100);
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
            lastTextAt = android.os.SystemClock.uptimeMillis();   // 有字在弹 → 重置安静计时
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
        lastTextAt = android.os.SystemClock.uptimeMillis();
        FloatingBallService.setSiriText(t);
        armQuiet();
    }

    /** 逐字揭示（回答用）；target 可以在揭示过程中不断变长。 */
    private void sayTyped(String text) {
        String t = clipToBall(text);
        if (!t.startsWith(shownText)) {         // 换了一轮回答 → 从头弹
            shownText = "";
            stopTyping();
        }
        targetText = t;
        lastTextAt = android.os.SystemClock.uptimeMillis();   // 新字到了 → 重置安静计时
        armQuiet();
        if (!typing && shownText.length() < targetText.length()) {
            typing = true;
            main.post(typist);
        }
    }

    /**
     * 球内文字的硬上限：超了就只留最后一段（前面加省略号）。
     *
     * 一条会话里的助手输出可以长到几万字（实测镜像跟到长任务时就是几千字一起灌进来），
     * 全塞进球里的话 TextView 会撑成一整面墙、文字糊在球外面 —— 球是「当前这句话」的显示器，
     * 不是日志窗口。封顶后：球里永远只有能装下的最后一段，超出部分自动消失。
     */
    private static final int BALL_TEXT_MAX = 600;

    private static String clipToBall(String text) {
        String t = text == null ? "" : text;
        if (t.length() <= BALL_TEXT_MAX) return t;
        return "…" + t.substring(t.length() - BALL_TEXT_MAX);
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
