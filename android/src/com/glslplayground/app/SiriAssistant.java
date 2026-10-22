package com.glslplayground.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 水晶球的 DSH 文本助手：把「悬浮球 → DSH → 球内 Siri 模式文字」串成一个闭环。
 *
 *   搜索框里打字 / 设置页「问一句」
 *        → 作为 prompt 发给 DSH 会话
 *        → 流式读回助手回答，边收边写进 {@link FloatingBallService#setSiriText}
 *          （球体随之变形：1~2 行胶囊 / 3 行以上大圆角方形，黑区按行数下移）
 *
 * 传输层是 {@link Transport}，由 {@link DshHttpTransport} 实现，指向本机 DSH 服务。
 */
public final class SiriAssistant {

    private static final String TAG = "SiriAssistant";
    private static SiriAssistant instance;

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

    /** DSH 侧能力：问答（会话的挑选由实现负责）。 */
    public interface Transport {
        void ask(String text, Stream cb);
        /** 发到指定会话（可带附件回执）。三态手势机：语音/状态 2 的发送目标由界面决定。 */
        void askContent(String sessionId, String text, java.util.List<String> receipts, Stream cb);
        void cancel();
        void shutdown();
        /** 供设置页展示：当前指向的 DSH 地址。 */
        String endpoint();
        /** 常驻镜像：跟着当前会话，AI 一有输出就往球里打。 */
        void startMirror(Mirror mirror);
        void stopMirror();
    }

    public enum State { OFF, IDLE, THINKING, ANSWERING }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Transport transport;
    private boolean enabled = true;

    private State state = State.IDLE;

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
        // 传输层默认指向本机 DSH 服务；地址可在设置页改，存在同一个 prefs 里
        setTransport(new DshHttpTransport(app, sp.getString("endpoint", DshHttpTransport.DEFAULT_ENDPOINT)));
        mirrorEnabled = sp.getBoolean("mirror", true);
        approvalEnabled = sp.getBoolean("approval_orb", true);
    }

    // ── 审批球：DSH 要你点「批准/拒绝」时，弹一颗新的液态玻璃球 ──────────
    // 这条链路和文本助手是两回事（不提问、不碰球内文字），只是共用
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
                        setState(State.THINKING);
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
                        if (state != State.ANSWERING) setState(State.ANSWERING);
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
                        setState(State.ANSWERING);
                        sayTyped(text);
                    }
                });
            }

            @Override public void onTurnEnd() {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!askInFlight && (state == State.THINKING || state == State.ANSWERING)) {
                            setState(State.IDLE);
                        }
                        // 整轮结束：球里若还挂着这轮的回答文字，就在球底出一道"进了玻璃的"波
                        // （用户要求：方形态只有最终结果时才出波，且这道波要与背景同一套折射）
                        turnSettled = true;          // ★ 只有这里算"最终回复"
                        FloatingBallService.setAssistantTurnEnded(true);
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

    /**
     * 本轮是否已经收到结束信号（turn/end 或本 App 自己那条流收完）。
     *
     * 球侧拿它判断「方形态还该不该保持」：回合没结束时保持是设计（多步/短回复不缩回圆球），
     * 但回合结束之后还保持，就是窗口停在 993×993、球早已缩回圆球、
     * 球周围一圈透明区把触摸全吃掉的那个 bug（用户 2026-10-22 反馈的那条）。
     */
    public static boolean isTurnSettled() {
        SiriAssistant a = instance;
        return a != null && a.turnSettled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
        prefs().edit().putBoolean("enabled", v).apply();
        if (!v) {
            if (transport != null) transport.cancel();
            stopMirror();
            stopTyping();
            setState(State.OFF);
            FloatingBallService.setSiriText("");
        } else {
            startMirror();
        }
        // 审批球是独立的一条链路：文本助手开着或关着，它都照常工作
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

    /** 直接以文本提问（搜索框 / 设置页 / 调试用）——目标仍是"最近在用的那条会话"。 */
    public void ask(final String text) {
        if (transport == null) { toast("DSH 未连接"); return; }
        FloatingBallService.setAssistantPinned(false);   // 新一轮开始：解除文字钉住
        setState(State.THINKING);
        // 球里不显示这句提问：球内只允许出现 AI 的回答，
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
                        String answer = full == null ? "" : full;
                        if (!answer.isEmpty()) sayTyped(answer);   // 交给打字机把剩下的字弹完
                        turnSettled = true;          // 这条流自己收完了 → 算最终回复
                        setState(State.IDLE);
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

    /**
     * 发到**指定会话**（可带附件回执）。
     *
     * 和 {@link #ask(String)} 的区别只有一处：目标会话由调用方给定，不再"猜最近那条"。
     * 为什么需要：状态 2 的发送语义是"发到新会话 / 工作目录或搜索里选中的那条"，
     * 而语音话音刚落那条也必须落在刚建好的会话上（那时它未必已经是"最近更新"）。
     */
    public void askIn(final String sessionId, final String text,
                      final java.util.List<String> receipts) {
        if (transport == null) { toast("DSH 未连接"); return; }
        if (sessionId == null || sessionId.isEmpty()) { toast("没有目标会话"); return; }
        FloatingBallService.setAssistantPinned(false);
        setState(State.THINKING);
        askInFlight = true;
        transport.askContent(sessionId, text, receipts, new Stream() {
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
                        String answer = full == null ? "" : full;
                        if (!answer.isEmpty()) sayTyped(answer);
                        turnSettled = true;
                        setState(State.IDLE);
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

    /** 长按手势：取消这一轮问答，并清空球内文字。 */
    public void cancelByGesture() {
        abort("已取消");
        sayNow("");
    }

    private void abort(String note) {
        if (transport != null) transport.cancel();
        askInFlight = false;
        stopTyping();
        FloatingBallService.setAssistantTurnEnded(false);
        if (note != null && !note.isEmpty()) toast(note);
        setState(State.IDLE);
    }

    public void shutdown() {
        if (transport != null) transport.shutdown();
        setState(State.OFF);
    }

    // ── 内部 ───────────────────────────────────────────────────────────

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
     * 「最终回复」安静多久把方形态收回初始圆球。
     *
     * 原来是 1.5s —— 实测太短：回答一长，打字机刚弹完、人还没看，字就被清掉、
     * 球也缩回去了，用户看到的是「DSH 都回我了，球上什么都没有」（2026-10-22 反馈）。
     * 现在留 30s 让人读完；想提前收：点一下球内文字 = 钉住/取消，或设置页「清空球内文字」，
     * 下一轮提问/mirror 新回合开始也会直接换掉。
     * 与 IDLE_CLEAR_MS 的分工不变：这里收**形状+文字**，IDLE_CLEAR_MS 只管兜底清文字。
     */
    private static final long FINAL_SHAPE_BACK_MS = 30000;
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

            // ① 最终回复：安静 30s → 收形状，**并且同一时刻把文字清掉**
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
     * 用于**不跑真实 DSH 回合**也能验证"清字 + 收形状"这条链路。
     */
    public void debugMarkTurnSettled() {
        main.post(new Runnable() {
            @Override public void run() {
                turnSettled = true;
                shapeBackSent = false;
                FloatingBallService.setAssistantPinned(false);
                lastTextAt = android.os.SystemClock.uptimeMillis();
                armQuiet();
                Log.i(TAG, "debug: 标记为最终回复 → " + FINAL_SHAPE_BACK_MS + "ms 后应变回初始圆球");
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
        // quietTick 里同时管着 ①最终回复收形状 ②4s 清文字 两条计时，
        // 用 4.1s 排第一次 → 收形状那条永远等不到（实测用户报"不会恢复"就是这个）。
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

    private void toast(final String msg) {
        Log.i(TAG, msg);
        try {
            android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
