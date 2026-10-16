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
        /**
         * 一条助手消息收完（**每个 step 一条，非最终回复也会来**）。
         *
         * 为什么要这个回调：朗读原来只在 turn/end 发生，于是「先说了两句话、
         * 再跑两分钟工具」这一整段是哑的。DSH 的 assistant/message 事件是
         * 按 step 落的（一次模型调用 = 一条），拿它才能做到「一有新回复就念」。
         *
         * @param text     这条消息的正文（可能是空串：那一步只有工具调用）
         * @param turn     DSH 的回合号（取不到时用事件 seq 兜底）
         * @param step     DSH 的步骤号
         * @param hasTools 这条消息里带不带工具调用 —— 带 = 处理中的旁白，不带 = 本回合最终回复
         */
        void onMessage(String text, long turn, int step, boolean hasTools);
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
        /** 见 {@link Stream#onMessage}：每个 step 一条，非最终回复也念。 */
        void onMessage(String text, long turn, int step, boolean hasTools);
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

    /**
     * 转写结果交给谁 —— 搜索胶囊里的麦克风走这条路。
     *
     * 为什么要一个"出口"而不是直接问：球内文字只允许出现两种东西（你说的那句 + AI 的回答），
     * 而搜索胶囊要的是"把识别到的字**放进输入框**，再当作用户打的字发出去"。
     * 所以录音/转写这两步照旧复用 {@link VoiceCapture} + DSH 的 speech/transcribe，
     * 只有"识别完交给谁"这一处分叉：默认写进球里；带 sink 时交给搜索框。
     */
    public interface TranscriptSink {
        void onTranscript(String text);
        void onTranscriptFailed(String message);
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
        speakSteps = sp.getBoolean("speak_steps", true);   // 处理中的非最终回复也念（默认开）
        // 一次性清理：早期版本把 DeepSeek key 存在这里过，现在改用 DSH 插件（不需要 key），
        // 留着只是多一份无用的敏感数据。
        if (sp.contains("speak_key") || sp.contains("speak_model")) {
            sp.edit().remove("speak_key").remove("speak_model").apply();
            Log.i(TAG, "已清理历史遗留的 speak_key / speak_model");
        }
        try {
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    Log.i(TAG, "TTS init status=" + status + " ready=" + ttsReady);
                    if (ttsReady) {
                        try {
                            int lang = tts.setLanguage(Locale.CHINA);
                            Log.i(TAG, "TTS setLanguage(CHINA) -> " + lang
                                    + " (LANG_MISSING_DATA=" + TextToSpeech.LANG_MISSING_DATA
                                    + " LANG_NOT_SUPPORTED=" + TextToSpeech.LANG_NOT_SUPPORTED + ")");
                        } catch (Throwable e) {
                            Log.w(TAG, "TTS setLanguage 失败: " + e);
                        }
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
                        turnSettled = false;         // 新一轮：还没结束，方形态保持住
                        shapeBackSent = false;
                        // 新一轮开始 → 方形态的「出波闸门」先关上（只有整轮结束才重新打开）
                        FloatingBallService.setAssistantTurnEnded(false);
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

            @Override public void onMessage(final String text, final long turn,
                                            final int step, final boolean hasTools) {
                if (askInFlight) return;      // 球自己问的那一轮由本地那条流负责念
                speakMessage(text, turn, step, !hasTools);
            }

            @Override public void onTurnEnd() {
                // 朗读**不能**被 askInFlight 挡掉：用户通过球说话时 askInFlight=true，
                // 原来这里连续两道 `if (askInFlight) return;` 会把朗读整个跳过 ——
                // 而 App 自己那条流式路径又不调用 speak()，结果两条路都不出声（实测就是这个）。
                // 所以：状态复位仍旧受 askInFlight 约束，朗读用「本轮已念过的 (turn,step)」去重。
                final String textToSpeak = speakReplies ? mirrorBuf.toString() : "";
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!askInFlight && (state == State.THINKING || state == State.ANSWERING)) {
                            setState(State.IDLE);
                        }
                        // 整轮结束：球里若还挂着这轮的回答文字，就在球底出一道"进了玻璃的"波
                        // （用户要求：方形态只有最终结果时才出波，且这道波要与背景同一套折射）
                        turnSettled = true;          // ★ 只有这里算"最终回复"
                        FloatingBallService.setAssistantTurnEnded(true);
                        // 逐条念过就不再补念（兜底：镜像没收到 assistant/message 时才用它）
                        speakTurnEnd(textToSpeak);
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
        beginListening(null);
    }

    /** 录音，识别完把字交给 {@code sink}（null = 老路：写进球里再提问）。 */
    public void beginListening(final TranscriptSink sink) {
        if (!enabled) return;
        if (transport == null) {
            toast("DSH 未连接");
            if (sink != null) sink.onTranscriptFailed("DSH 未连接");
            return;
        }
        if (!ensureMicPermission()) {
            toast("需要麦克风权限：请在设置里允许后重试");
            if (sink != null) sink.onTranscriptFailed("需要麦克风权限");
            return;
        }
        transcriptSink = sink;
        say("");
        FloatingBallService.setAssistantTurnEnded(false);
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
                        TranscriptSink s = takeTranscriptSink();
                        if (s != null) s.onTranscriptFailed(reason);
                    }
                });
            }
        });
        if (!ok) {
            setState(State.IDLE);
            TranscriptSink s = takeTranscriptSink();
            if (s != null) s.onTranscriptFailed("录音启动失败");
        }
    }

    /** 正在录音（搜索胶囊的麦克风按钮靠它决定"再点一下 = 结束并发送"）。 */
    public boolean isListening() { return state == State.LISTENING; }

    private volatile TranscriptSink transcriptSink;

    private TranscriptSink takeTranscriptSink() {
        TranscriptSink s = transcriptSink;
        transcriptSink = null;
        return s;
    }

    public void finishListening() {
        if (state != State.LISTENING) return;
        final TranscriptSink sink = takeTranscriptSink();   // 先取走：搜一次只交一次
        setState(State.TRANSCRIBING);
        FloatingBallService.setAssistantLevel(0f);
        byte[] wav = capture.stop();
        if (wav == null) {
            toast("没有录到声音");
            setState(State.IDLE);
            if (sink != null) sink.onTranscriptFailed("没有录到声音");
            return;
        }
        // 转写期间球里不写字（只留波形随音量呼吸），识别完直接把「你说了什么」显示出来
        final Transport t = transport;
        if (t == null) {
            setState(State.IDLE);
            if (sink != null) sink.onTranscriptFailed("DSH 未连接");
            return;
        }
        t.transcribe(VoiceCapture.toBase64(wav), new Result<String>() {
            @Override public void ok(final String text) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            toast("没听清，再说一次");
                            setState(State.IDLE);
                            if (sink != null) { sink.onTranscriptFailed("没听清"); return; }
                            sayNow("");
                            return;
                        }
                        // 先把你说的那句显示在球里，回答到了再逐字替换
                        if (sink != null) {          // 搜索胶囊：把字交给输入框，由那边决定发不发
                            setState(State.IDLE);
                            sink.onTranscript(text.trim());
                            return;
                        }
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
                        if (sink != null) sink.onTranscriptFailed(message);
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
            @Override public void onMessage(final String text, final long turn,
                                            final int step, final boolean hasTools) {
                speakMessage(text, turn, step, !hasTools);
            }
            @Override public void onDone(final String full) {
                main.post(new Runnable() {
                    @Override public void run() {
                        askInFlight = false;
                        lastAnswer = full == null ? "" : full;
                        if (!lastAnswer.isEmpty()) sayTyped(lastAnswer);   // 交给打字机把剩下的字弹完
                        turnSettled = true;          // 这条流自己收完了 → 算最终回复
                        setState(State.IDLE);
                        if (!lastAnswer.isEmpty()) speakTurnEnd(lastAnswer);
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

    /**
     * 静默取消录音：搜索胶囊要接管屏幕时用（不弹 toast、不动球内文字）。
     * 只掐「正在录/正在转写」这两态 —— 一轮问答（THINKING/ANSWERING）**不许**在这里被掐掉，
     * 那是 DSH 那边真在跑的任务，用户只是打开了搜索框而已。
     */
    public void cancelListeningQuiet() {
        if (state != State.LISTENING && state != State.TRANSCRIBING) return;
        capture.cancel();
        takeTranscriptSink();
        FloatingBallService.setAssistantLevel(0f);
        setState(State.IDLE);
        Log.i(TAG, "cancelListeningQuiet: 录音/转写已静默取消（搜索胶囊接管）");
    }

    private void abort(String note) {
        capture.cancel();
        takeTranscriptSink();
        if (transport != null) transport.cancel();
        askInFlight = false;
        stopTyping();
        FloatingBallService.setAssistantLevel(0f);
        FloatingBallService.setAssistantTurnEnded(false);
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
    /**
     * 「最终回复」安静多久把方形态收回初始圆球（用户要求 **1.5 秒**）。
     * 与 IDLE_CLEAR_MS 的分工：1.5s 收**形状**，4s 是清**文字**的兜底。
     * 关键：只在回合**真的结束**之后才计时 —— 处理中的短回复/多步工具调用一律保持方形。
     */
    private static final long FINAL_SHAPE_BACK_MS = 1500;
    /** 本轮是否已收到结束信号（turn/end 或本地流收完）。 */
    private volatile boolean turnSettled = false;
    /** 是否已经发过"收形状"，避免重复触发。 */
    private volatile boolean shapeBackSent = false;

    /** 最近一次球内文字有变化的时间（打字机每弹一个字都会刷新）。 */
    private volatile long lastTextAt = android.os.SystemClock.uptimeMillis();
    private final Runnable quietTick = new Runnable() {
        @Override public void run() {
            // 用户点过球内文字 = 钉住：文字保持不动，直到他再点一下解除干预
            if (FloatingBallService.isAssistantTextPinned()) return;
            long quiet = android.os.SystemClock.uptimeMillis() - lastTextAt;
            // 打字机队列里还有没弹完的字 → 重排计时，别在半途把字清掉
            if (targetText.length() > shownText.length()) { armQuiet(); return; }
            // 用户正在球里翻阅 → 不清、不收，继续等（松手 MD_SCROLL_HOLD_MS 后才继续）
            if (FloatingBallService.isTextBeingScrolled()) { armQuiet(); return; }

            // ★ 用户 2026-10-16 明确的规则：
            //   「除了最终回复外的每次回复都没必要恢复回初始形状 —— 处理中的动画也可以出现在
            //     方形内，和那段不算长的文字待在一起，等下一个回复」。
            //   所以本轮**还没结束**（没有 turn/end、也不是本 App 自己那条流收完）时：
            //   文字不清、形状不收，一律保持方形态，直到最终回复到来。
            if (!turnSettled) {
                main.postDelayed(this, 200);
                return;
            }

            // ① 最终回复：安静 1.5s → 收形状，**并且同一时刻把文字清掉**
            //    （用户要求：变回初始形状时不允许出现任何文字）
            if (!shapeBackSent && quiet >= FINAL_SHAPE_BACK_MS) {
                shapeBackSent = true;
                if (shownText != null && !shownText.isEmpty()) sayNow("");   // 先清字
                FloatingBallService.releaseHoldForFinalReply();             // 再收形状
                Log.i(TAG, "最终回复安静 " + FINAL_SHAPE_BACK_MS + "ms → 清字 + 收形状（回初始圆球）");
            }
            // ② 更久的兜底：只负责把"回答中/思考中"的光效收掉（文字/形状上面已经处理）
            if (quiet < IDLE_CLEAR_MS) { main.postDelayed(this, 200); return; }
            if (shownText != null && !shownText.isEmpty()) sayNow("");
            if (state == State.ANSWERING || state == State.THINKING) setState(State.IDLE);
        }
    };

    /**
     * 调试口子：把当前这轮标记成"最终回复已到"并重排安静计时。
     * 用于**不跑真实 DSH 回合**也能验证"1.5 秒清字 + 收形状"这条链路。
     */
    public void debugMarkTurnSettled() {
        main.post(new Runnable() {
            @Override public void run() {
                turnSettled = true;
                shapeBackSent = false;
                FloatingBallService.setAssistantPinned(false);
                lastTextAt = android.os.SystemClock.uptimeMillis();
                armQuiet();
                Log.i(TAG, "debug: 标记为最终回复 → 1.5s 后应变回初始圆球");
            }
        });
    }

    /** 供球侧调用（用户解除钉住后重新开始安静计时）。 */
    public void rearmQuietPublic() {
        main.post(new Runnable() {
            @Override public void run() { armQuiet(); }
        });
    }

    /** 重排安静计时（每次有字进来都调一次）。 */
    private void armQuiet() {
        main.removeCallbacks(quietTick);
        // ⚠️ 必须是"很快跳一次"（200ms），不能直接用 IDLE_CLEAR_MS 排：
        // quietTick 里同时管着 ①最终回复 1.5s 收形状 ②4s 清文字 两条计时，
        // 用 4.1s 排第一次 → 1.5s 那条永远等不到（实测用户报"1.5 秒后不恢复"就是这个）。
        main.postDelayed(quietTick, 200);
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
    /**
     * 球内文字的硬上限（超了只留**最后一段**，前面加省略号）。
     *
     * 历史：原方案是 TextView，600 字就会撑成一整面墙，所以卡在 600；
     * 现在球内是**可滚动的原生富文本**，600 字连一屏都不到（实测一屏约 1000px/18px ≈ 700 字），
     * 用户报"最终回复那么长我只看到一点点"就是这个上限卡的。
     * 现在与镜像缓存（{@code DshHttpTransport.MIRROR_BUF_MAX}）对齐到 2500 字。
     */
    private static final int BALL_TEXT_MAX = 2500;

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

    /** 排查用：把朗读过程直接写文件（logcat 会被别的服务刷掉，看不到）。 */
    private void speakLog(String msg) {
        Log.i(TAG, "SPEAK " + msg);
        try {
            java.io.File f = new java.io.File(app.getExternalFilesDir(null), "speak-trace.log");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write((System.currentTimeMillis() % 100000 + " " + msg + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }

    /** DSH 插件（dsh-plugin-speech）写的口播稿；球只读它，不碰 DSH 内部。 */
    private static final String SPOKEN_FILE = "spoken.txt";      // v1：纯文本，没有身份
    private static final String SPOKEN_JSON = "spoken.json";     // v2：带 (turn,step)，可精确对号
    /** 插件的心跳文件：它在跑才会有人写口播稿，它没在跑就一秒都不等。 */
    private static final String PLUGIN_ALIVE = "speech-plugin.alive";
    private static final long SPOKEN_FRESH_MS = 120000;          // 2 分钟内的才算这一轮
    /** 心跳超过这么久没更新 = 插件没在跑（插件每 15 秒跳一次）。 */
    private static final long PLUGIN_ALIVE_FRESH_MS = 45000;

    private long ageOf(String name) {
        try {
            java.io.File f = new java.io.File(app.getExternalFilesDir(null), name);
            if (!f.isFile()) return Long.MAX_VALUE;
            return System.currentTimeMillis() - f.lastModified();
        } catch (Throwable e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 口播稿插件在不在跑？（读它的心跳文件）
     *
     * **这就是「莫名其妙停 15 秒」的根治点。** 原来是「看不到插件也盲等满
     * speak_wait_ms（默认 15000）」—— 插件 10-16 起没被 DSH 加载的那一天多里，
     * 每一次朗读都实打实等满 15 秒才退化成规则清洗版（speak-trace.log 里
     * 连着十几条「等满 15xxx ms」）。现在：心跳不在或过期 → 一秒都不等。
     */
    private boolean pluginAlive() {
        long age = ageOf(PLUGIN_ALIVE);
        return age >= 0 && age < PLUGIN_ALIVE_FRESH_MS;
    }

    /** 设置页展示用。 */
    public boolean speechPluginAlive() { return pluginAlive(); }

    /**
     * 优先用 DSH 插件改写好的口播稿（若存在且新鲜）；
     * 没有（插件没装/失败）就回落本地规则清洗。
     * 这样插件是"可选增强"：不装也能用，装了更自然。
     */
    private String pluginSpokenOrNull() { return pluginSpokenOrNull(0L); }

    /**
     * 读插件口播稿（v1 纯文本 spoken.txt）。
     *
     * @param notBeforeMs 只接受这个时刻之后写入的文件。**关键**：插件与本轮不是同一时刻完成的，
     *   不设下界就会念到**上一轮**残留的稿子（实测：球 23:47:10 读到的 41 字是上一轮的，
     *   本轮 63 字的稿子 23:47:12 才写好）。传 0 表示不限制（仅用于其他调用点）。
     */
    private String pluginSpokenOrNull(long notBeforeMs) {
        try {
            java.io.File f = new java.io.File(app.getExternalFilesDir(null), SPOKEN_FILE);
            if (!f.isFile()) return null;
            if (f.lastModified() < notBeforeMs) return null;   // 上一轮的残留，继续等
            long age = System.currentTimeMillis() - f.lastModified();
            if (age > SPOKEN_FRESH_MS) return null;
            byte[] buf = new byte[(int) Math.min(f.length(), 8 * 1024)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            String t = new String(buf, 0, n, "UTF-8").trim();
            return t.isEmpty() ? null : t;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 读插件口播稿（v2 带身份的 spoken.json）：只认「就是这条消息」的那份。
     *
     * 为什么要身份：插件也会在**处理中间**抢先写稿（正文安静 400ms 就改写），
     * 那些稿子属于**别的** step。只按"文件够新"取，球会拿中间那段旁白的稿子
     * 去念最终回复（内容对不上）。带上 (turn, step) 之后就能精确对号。
     *
     * @return null = 还没有（继续等）；"" = 插件明说这条不用改写（立刻用本地规则版）
     */
    private String readPluginJson(long turn, int step) {
        try {
            java.io.File f = new java.io.File(app.getExternalFilesDir(null), SPOKEN_JSON);
            if (!f.isFile()) return null;
            if (System.currentTimeMillis() - f.lastModified() > SPOKEN_FRESH_MS) return null;
            byte[] buf = new byte[(int) Math.min(f.length(), 16 * 1024)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, 0, n, "UTF-8"));
            if (o.optInt("v", 0) < 2) return null;
            if (o.optLong("turn", -1L) != turn || (long) o.optInt("step", -1) != step) return null;
            return o.optString("text", "");
        } catch (Throwable e) {
            return null;
        }
    }

    private void dropFile(String name) {
        try { new java.io.File(app.getExternalFilesDir(null), name).delete(); } catch (Throwable ignored) { }
    }

    // ── 朗读：一条助手消息念一次（含非最终回复）────────────────────────
    //
    // 历史：原来只有 turn/end 那一下调 speakOnce，于是「先说两句话、再跑两分钟
    // 工具」的整段处理过程是哑的，用户要等到最后才有声音。现在按 DSH 的
    // assistant/message 事件逐条念（一次模型调用 = 一条 = 一个 step）：
    //   · 带工具调用的（处理中旁白）→ **立刻念**，什么都不等
    //   · 不带工具调用的（本回合最终回复）→ 最多等一小会儿插件口播稿
    // 去重按 (turn, step)：镜像 WebSocket 重连会把历史事件重放一遍，只按文本去重挡不住。

    /** 已念过的 (turn, step)。 */
    private final java.util.LinkedHashMap<String, Long> spokenSteps = new java.util.LinkedHashMap<>();
    private static final int SPOKEN_STEPS_MAX = 64;

    /** 最近一次朗读请求的序号：等待插件稿期间又来了新回复，就把旧的那次丢掉（别倒着念）。 */
    private final java.util.concurrent.atomic.AtomicLong speakGen = new java.util.concurrent.atomic.AtomicLong();

    /** 本轮有没有逐条念过；有的话 turn/end 就不补念一遍。 */
    private volatile boolean spokeThisTurn = false;
    private volatile long curTurn = -1;

    /**
     * 一条助手消息（一个 step）到达 —— 决定「立刻念 / 等一下念 / 不念」。线程安全。
     *
     * @param isFinal true = 这条是本回合最终回复（消息里没有工具调用）
     */
    private void speakMessage(String text, long turn, int step, boolean isFinal) {
        if (!speakReplies) return;
        if (turn <= 0) turn = 1;                       // 兜底：取不到 turn 时别让去重失效
        final String key = turn + "/" + step;
        final String raw = text == null ? "" : text.trim();
        synchronized (spokenSteps) {
            if (turn != curTurn) { curTurn = turn; spokeThisTurn = false; }
            long now = System.currentTimeMillis();
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = spokenSteps.entrySet().iterator();
            while (it.hasNext()) { if (now - it.next().getValue() > 600000) it.remove(); }
            if (spokenSteps.containsKey(key)) { speakLog("这一步念过了，跳过 " + key); return; }
            spokenSteps.put(key, now);
            while (spokenSteps.size() > SPOKEN_STEPS_MAX) {
                java.util.Iterator<String> k = spokenSteps.keySet().iterator();
                if (k.hasNext()) { k.next(); k.remove(); }
            }
        }
        if (!isFinal && !speakSteps) { speakLog("非最终回复（" + key + "）：「每步都念」关着，跳过"); return; }
        if (raw.isEmpty()) return;                     // 纯工具调用那一步：没有可念的字
        spokeThisTurn = true;
        speakLog((isFinal ? "最终回复 " : "处理中回复 ") + key + "：" + raw.length() + " 字");
        startSpeak(raw, isFinal, turn, step);
    }

    /** 回合末兜底：这一轮一条都没念过才补念（正常情况下上面已经逐条念完了）。 */
    private void speakTurnEnd(String text) {
        if (!speakReplies) return;
        if (spokeThisTurn) { speakLog("本轮已逐条念过，turn/end 不补念"); spokeThisTurn = false; return; }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        startSpeak(t, true, -1L, -1);
    }

    /** 排一次朗读：该等插件口播稿才等，其余一律立刻念。 */
    private void startSpeak(final String raw, final boolean isFinal, final long turn, final int step) {
        final long gen = speakGen.incrementAndGet();
        // 只有「最终回复」值得等模型写的口播稿（≤100 字），而且**只在插件真的在跑时**才等。
        // 这两条就是原来那个 15 秒停顿的两半：既不看插件死活，又是全程盲等。
        boolean wantScript = isFinal && turn > 0 && pluginAlive() && waitPluginMs() > 0;
        if (!wantScript) {
            speakLog("不等口播稿（" + (isFinal ? (pluginAlive() ? "等待上限为 0" : "插件没在跑") : "非最终回复")
                    + "）→ 直接念规则清洗版");
            // 走主线程排队（TTS 的 stop/speak 一律在主线程调，顺序也就跟着消息顺序）
            main.post(new Runnable() {
                @Override public void run() { speak(raw); }
            });
            return;
        }
        final long wait = waitPluginMs();
        final long notBeforeMs = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                speakLog("开始等插件口播稿（最多 " + wait + "ms，" + turn + "/" + step + "）");
                String got = waitForPluginSpoken(wait, turn, step, notBeforeMs);
                speakLog(got == null
                        ? ("没等到插件稿（等满 " + (System.currentTimeMillis() - t0) + "ms）→ 用规则清洗版")
                        : (got.isEmpty() ? ("插件说这条不用改写（等了 " + (System.currentTimeMillis() - t0) + "ms）→ 用规则清洗版")
                                         : ("等到插件稿 " + got.length() + " 字（等了 " + (System.currentTimeMillis() - t0) + "ms）")));
                final String plugin = got;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (speakGen.get() != gen) { speakLog("已被更新的回复取代，丢弃这次朗读"); return; }
                        if (plugin != null && !plugin.isEmpty()) speakNow(plugin, true);
                        else speak(raw);
                    }
                });
            }
        }, "speak-wait").start();
    }

    /**
     * 等插件写下**这条消息**的口播稿。等到就取走并删掉；超时返回 null。
     * 返回 "" 表示插件明说这条不用改写（立刻回落本地规则版，不必等满）。
     * 在后台线程调用。
     */
    private String waitForPluginSpoken(long timeoutMs, long turn, int step, long notBeforeMs) {
        if (!speakReplies) return null;
        long until = System.currentTimeMillis() + timeoutMs;
        while (true) {
            String js = readPluginJson(turn, step);
            if (js != null) { dropFile(SPOKEN_JSON); speakLog("轮询命中 spoken.json（对号成功）"); return js; }
            String t = pluginSpokenOrNull(notBeforeMs);          // v1 兜底：没身份但新鲜的也用
            if (t != null) { dropFile(SPOKEN_FILE); speakLog("轮询命中 spoken.txt: " + t.length() + " 字"); return t; }
            if (System.currentTimeMillis() >= until) return null;
            try { Thread.sleep(120); } catch (InterruptedException e) { return null; }
        }
    }

    // 等插件口播稿的上限（默认 2.5 秒，可用 prefs 的 speak_wait_ms 调，0 = 不等）。
    //
    // 历史：这里原来是 **15000ms**。那是因为插件在 turn/end 之后才发模型请求，
    // 球只能硬等；后来插件改成 400ms 防抖、实测 1 秒左右就写好稿子，15 秒就是
    // 纯粹的"插件死了也要等满"的兜底。
    private long waitPluginMs() {
        return prefs().getLong("speak_wait_ms", 2500L);
    }
    public void setSpeakWaitMs(long ms) {
        prefs().edit().putLong("speak_wait_ms", Math.max(0L, Math.min(8000L, ms))).apply();
    }
    public long getSpeakWaitMs() { return waitPluginMs(); }

    /** 「每步都念」：处理中的非最终回复也朗读（默认开）。 */
    private volatile boolean speakSteps = true;
    public boolean speakSteps() { return speakSteps; }
    public void setSpeakSteps(boolean v) {
        speakSteps = v;
        prefs().edit().putBoolean("speak_steps", v).apply();
    }

    /** 走本地规则清洗再念（不等插件的那种）。 */
    private void speak(String text) {
        if (!ttsReady || tts == null) {
            Log.i(TAG, "speak 被调用但 TTS 未就绪（ttsReady=" + ttsReady + "）");
            return;
        }
        Log.i(TAG, "speak 请求: 原文 " + (text == null ? 0 : text.length()) + " 字");
        // 朗读稿只有两个来源：DSH 插件写的口播稿（上面已经等到），或本地规则清洗。
        // App 侧不再自己调模型（用的是 DSH 的模型通道，不需要第二份 key）。
        speakNow(toSpeechText(text), false);
    }

    // 最后一道闸：同一段内容短时间内只念一次（镜像重放 / 两条路都走到朗读点时的兜底）
    private final java.util.LinkedHashMap<Integer, Long> spokenHashes = new java.util.LinkedHashMap<>();
    private static final long SPOKEN_DEDUPE_MS = 8000;

    /** 真正送给 TTS 的那一步（规则清洗版与模型版都走这里）。 */
    private void speakNow(String spoken, boolean byModel) {
        if (!ttsReady || tts == null || spoken == null) return;
        // 调试口：把「要念的稿子」落盘，方便核对清洗/改写效果（球自己的目录，能写能删）
        try {
            java.io.File f = new java.io.File(
                    app.getExternalFilesDir(null), "last-spoken.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(spoken.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
        if (spoken.isEmpty()) return;
        synchronized (spokenHashes) {
            long now = System.currentTimeMillis();
            java.util.Iterator<java.util.Map.Entry<Integer, Long>> it = spokenHashes.entrySet().iterator();
            while (it.hasNext()) { if (now - it.next().getValue() > SPOKEN_DEDUPE_MS) it.remove(); }
            Integer h = spoken.hashCode();
            if (spokenHashes.containsKey(h)) { speakLog("8 秒内念过同样内容，跳过"); return; }
            spokenHashes.put(h, now);
            while (spokenHashes.size() > 20) {
                java.util.Iterator<Integer> k = spokenHashes.keySet().iterator();
                if (k.hasNext()) { k.next(); k.remove(); }
            }
        }
        speakLog("交给 TTS: " + spoken.length() + " 字（" + (byModel ? "插件口播稿" : "规则清洗") + "）");
        Log.i(TAG, "speak(" + spoken.length() + "字" + (byModel ? "，口播稿" : "，规则清洗") + "): "
                + spoken.substring(0, Math.min(80, spoken.length())));
        try {
            tts.stop();
            tts.speak(spoken.length() > 800 ? spoken.substring(0, 800) : spoken,
                    TextToSpeech.QUEUE_FLUSH, null, "dsh-siri");
        } catch (Throwable ignored) {}
    }


    /**
     * 把「给眼睛看的 markdown」转成「给耳朵听的稿子」。
     *
     * 为什么需要：DSH 的回复是 markdown，直接丢给 TTS 会把 `**`、反引号、`#`、`-`、`|`
     * 这些符号念成"星号/井号/竖线"，听感很差。
     *
     * 为什么不用另一个模型改写：那会引入额外延迟（回答完还得再等一次生成）、额外费用，
     * 而且模型可能改写内容、把事实讲错。这里全是确定性规则，零延迟、零成本、不改语义。
     *
     * 规则：
     *   · 代码块 → 整段跳过（念代码没有意义），只在前面留一句「（有代码）」
     *   · 行内代码 → 只留内容、去掉反引号
     *   · 标题/列表/引用/表格/分隔线 → 去掉标记，保留文字
     *   · 链接/图片 → 只念链接文字
     *   · 去掉所有 markdown 强调符与其余符号（emoji、箭头、制表符等）
     *   · 只保留：中日韩汉字、拉丁字母数字、空白、逗号句号问号感叹号顿号分号冒号引号括号
     */
    public static String toSpeechText(String raw) {
        if (raw == null) return "";
        String t = raw;

        // 1) 代码块：整段丢掉，只留一句提示（用户明确说不想听拗口符号）
        boolean hadCode = false;
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("```[\\s\\S]*?```").matcher(t);
        if (fence.find()) {
            hadCode = true;
            t = fence.replaceAll(" （有代码，略过） ");
        }

        // 2) 行内代码 → 去反引号留内容
        t = t.replaceAll("`([^`]*)`", "$1");
        // 3) 图片 ![alt](url) → alt；链接 [text](url) → text
        t = t.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // 4) 行首标记：标题 / 引用 / 列表 / 分隔线 / 表格
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        t = t.replaceAll("(?m)^\\s{0,3}>\\s?", "");
        t = t.replaceAll("(?m)^\\s{0,3}[-*+]\\s+", "");
        t = t.replaceAll("(?m)^\\s{0,3}\\d+[.、)]\\s+", "");
        t = t.replaceAll("(?m)^\\s{0,3}([-*_]\\s*){3,}$", "");
        // 顺序很重要：表格对齐行（|---|---|）必须**先**清掉再动竖线/连字符，
        // 否则第 5、7 步会把 `-` 变成空格，这里再也匹配不到（听感就是莫名一顿）。
        t = t.replaceAll("(?m)^\\s*\\|?\\s*[:-]{2,}[\\s|:-]*$", "");   // 表格对齐行
        t = t.replaceAll("\\|", " ");                 // 表格竖线
        // 5) 强调符、删除线、其余零散 markdown 符号
        t = t.replaceAll("[*_~^\\[\\]{}<>|\\\\]", " ");
        t = t.replaceAll("#+", " ");
        // 6) 只保留"能读"的字符：中日韩、拉丁字母数字、空白、常用标点
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean keep;
            if (c >= '\u4E00' && c <= '\u9FFF') keep = true;        // 汉字
            else if (c >= '\u3040' && c <= '\u30FF') keep = true;   // 假名
            else if (c >= '\uAC00' && c <= '\uD7AF') keep = true;   // 谚文
            else if (Character.isLetterOrDigit(c)) keep = true;       // 拉丁/数字
            else if (c == ' ' || c == '\n' || c == '\t') keep = true;
            else keep = "，。！？、；：（）《》【】“”‘’".indexOf(c) >= 0;
            sb.append(keep ? c : ' ');
        }
        t = sb.toString();
        // 7) 路径/连字符：直接删会粘连（`-r` → `r`、`package/activity` → `packageactivity`），
        //    替换成空格再合并，听感才对（"pm install -r app" 而不是 "pm install r app" 的粘连版）
        t = t.replaceAll("[\\-/:@.]+", " ");
        // 7) 收尾：合并空白、去掉孤零零的标点（念出来会"顿"一下）
        t = t.replaceAll("[ \\t]{2,}", " ");
        // ⚠️ Java 的 replaceAll 有个坑：替换串里写 "\\n" 会被当成**转义字母 n**，不是换行。
        // 实测「第一行\n第二行」会被替换成「第一行n第二行」（多出一个字母 n，念出来很奇怪）。
        // 用 $0（整个匹配文本）代替：$ 开头的替换串按字面处理，换行原样保留。
        t = t.replaceAll("\\n{3,}", "$0$0");            // 多个换行压成两个（段落停顿）
        t = t.replaceAll("[ \\t]*\\n[ \\t]*", "$0");    // 行首行尾空白去掉
        t = t.replaceAll("(?m)^ +", "");                // 行首残留空格（表格/缩进留下的）
        // 表格行被上面的竖线替换弄成 `方案  成本  延迟`：一格一词连念很怪，
        // 把「同一行里 2~6 字的短词」用、连起来，听感才像话（表格本就只是给人看的）
        StringBuilder tb = new StringBuilder(t.length());
        for (String line : t.split("\n", -1)) {
            String[] parts = line.trim().split("\\s{2,}|\\s+");
            if (parts.length >= 2 && parts.length <= 8) {
                // 只对「纯中文、2~4 字」的词做顿号连接 —— 数字/时间戳/文件名被切开会更难听
                //（实测 `22:55:30` 会被切成 `22、55、30`）
                boolean allShort = true;
                for (String w : parts) {
                    if (w.length() > 4 || !w.matches("[\\u4E00-\\u9FFF]+")) { allShort = false; break; }
                }
                if (allShort) {
                    StringBuilder j = new StringBuilder();
                    for (String w : parts) {
                        if (w.isEmpty()) continue;
                        if (j.length() > 0) j.append('、');
                        j.append(w);
                    }
                    if (j.length() > 0) { tb.append(j).append('\n'); continue; }
                }
            }
            tb.append(line).append('\n');
        }
        t = tb.toString();
        t = t.replaceAll("^[，。！？、；：）】”’\\s]+", "");
        t = t.replaceAll("[，。！？、；：（【“‘\\s]+$", "");
        if (t.isEmpty() && hadCode) t = "有代码，略过";
        return t.trim();
    }

    private void stopTts() {
        if (tts != null) { try { tts.stop(); } catch (Throwable ignored) {} }
    }
}
