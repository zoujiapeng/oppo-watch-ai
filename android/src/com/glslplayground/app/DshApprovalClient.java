package com.glslplayground.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * DSH 的「提问应答方」：把 Host 抛给浏览器的审批/提问，接到手机的悬浮球上。
 *
 * ── 为什么能这么做 ────────────────────────────────────────────────
 * DSH 的审批不是某个按钮的私有接口，而是一条 Cordis 瀑布事件
 * `approval/request`（`ask_user_question` 走的是同一套的 `user-questions/request`）。
 * Gateway 把这条瀑布转发给**每一个**开着 `$events` 逻辑流的客户端：
 *
 *   （浏览器）ws /api/remote.mux → {"type":"open","endpoint":"$events",
 *                                  "payload":{"args":{}}}
 *   ← {"type":"item","value":{"type":"ready","clientId":"<uuid>","host":{…}}}
 *   ← {"type":"item","value":{"type":"waterfall","event":"approval/request",
 *        "eventId":"<uuid>","agentId":"…",
 *        "request":{"toolName":"bash","reason":"…","displayReason":{…}}}}
 *
 * 谁先应答谁说了算（`api-gateway` 的 `receiveRemoteEventResult`：任一客户端给出
 * 终局结果就结算整条瀑布）。应答本身是一条普通 HTTP RPC：
 *
 *   POST /api/$events/result
 *   {"type":"client-request","rpcId":"…","method":"$events/result",
 *    "payload":{"args":{"clientId":"…","eventId":"…",
 *                       "outcome":{"kind":"result","value":"allowed-once"}}}}
 *
 * outcome 的三种形态（`$events/result` 的解析器只认这三种）：
 *   {"kind":"result","value":"allowed-once"}  → 批准这一次
 *   {"kind":"result","value":"rejected"}      → 拒绝
 *   {"kind":"next"}                           → 我不管，交给下一个应答方
 *
 * ── 我们为什么不自动 next ────────────────────────────────────────
 * 浏览器标签页开着的时候，它自己会撑着这条请求等用户点按钮；如果手机端抢先回
 * `next`，Host 会认为「这一份交付已经答完」，整条瀑布就可能结算成 unavailable
 * （=工具被拒）。所以这里**只在用户真的选了**才回结果，其余时间一声不吭。
 *
 * ── 结束了怎么知道 ──────────────────────────────────────────────
 * 结算后 Host 会给所有拿到过这条请求的客户端推 {"type":"cancel","eventId":"…"}，
 * 收到就撤球（比如你在浏览器里已经点了）。
 *
 * 响应者的选择结果（approval）：
 *   allowed-once / rejected；提问（user-questions）则回选中的 option.label。
 */
public final class DshApprovalClient {

    private static final String TAG = "DshApproval";

    /** 一次待答的请求：审批或提问，球上只关心「标题 + 说明 + 一串选项」。 */
    public static final class Request {
        /** 事件名：approval/request 或 user-questions/request。 */
        public String event;
        public String eventId;
        public String agentId;
        /** 审批的工具名（提问为空）。 */
        public String toolName = "";
        /** 人类可读原因（审批）或问题正文（提问）。 */
        public String reason = "";
        /** 结构化问题（提问事件才有）：[[{id,header,question,options:[{label,description}]}]]。 */
        public final List<Question> questions = new ArrayList<>();

        public boolean isApproval() { return "approval/request".equals(event); }

        /** 球头行：审批显示工具，提问显示第一个问题的标题。 */
        public String title() {
            if (isApproval()) {
                return toolName.isEmpty() ? "需要审批" : toolName;
            }
            if (!questions.isEmpty()) {
                Question q = questions.get(0);
                if (q.header != null && !q.header.isEmpty()) return q.header;
                if (q.id != null && !q.id.isEmpty()) return q.id;
            }
            return "需要回答";
        }
    }

    /** 一个问题的选项。 */
    public static final class Question {
        public String id = "";
        public String header = "";
        public String question = "";
        /** 题干之外的补充说明（协议里的 detail）；有它时题面优先显示它。 */
        public String detail = "";
        /** 允许多选（协议里的 multiSelect，默认单选）。 */
        public boolean multiSelect = false;
        public final List<String> labels = new ArrayList<>();
        public final List<String> descriptions = new ArrayList<>();

        public boolean isMultiSelect() { return multiSelect; }
    }

    /** 球侧实现。所有回调都在主线程。 */
    public interface Listener {
        /** 收到一条待答请求：把球变成审批形态。 */
        void onRequest(Request request);
        /** 这条请求作废了（用户在别处答了 / 被取消）：撤球，原样复原。 */
        void onWithdraw(String eventId);
        /** 连接状态文字（设置页展示）。 */
        void onStatus(String note);
    }

    private static final String EVENTS_ENDPOINT = "$events";
    private static final String RESULT_ENDPOINT = "$events/result";

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private volatile boolean running = false;
    private volatile DshWebSocket socket;
    private volatile String clientId;
    private volatile Listener listener;
    private volatile String status = "";

    /** 当前挂着的那条请求（球上正显示的那个）；null = 没有待答。 */
    private volatile Request current;

    public DshApprovalClient(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.prefs = app.getSharedPreferences("siri_assistant", Context.MODE_PRIVATE);
    }

    public boolean enabled() { return prefs.getBoolean("approval_orb", true); }

    public void setListener(Listener l) { listener = l; }

    public String status() { return status; }

    public boolean connected() { return running && socket != null && !socket.isClosed(); }

    /** 球上正挂着的请求（设置页回读用）。 */
    public Request current() { return current; }

    /** 用户是不是正被球上的审批挡着（球据此决定要不要吞掉手势）。 */
    public boolean hasPending() { return current != null; }

    // ── 生命周期 ───────────────────────────────────────────────────────

    public void start() {
        Log.i(TAG, "start() called: running=" + running + " enabled=" + enabled());
        if (running) return;
        if (!enabled()) { setStatus("审批球已关闭"); return; }
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "dsh-approval");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "approval thread spawned");
    }

    public void stop() {
        running = false;
        DshWebSocket ws = socket;
        if (ws != null) ws.close();
        socket = null;
        clientId = null;
        current = null;
        setStatus("审批球已停止");
    }

    public void shutdown() { stop(); }

    /** 断线重连：DSH 重启 / 网线断了都靠这条循环自己回来。 */
    private void loop() {
        int backoff = 1000;
        Log.i(TAG, "approval loop entered (endpoint=" + endpoint() + ")");
        while (running) {
            try {
                connectOnce();
                backoff = 1000;
            } catch (Throwable t) {
                setStatus("审批流断开：" + shortMsg(t));
                Log.w(TAG, "events stream failed: " + t, t);
            }
            socket = null;
            clientId = null;
            if (!running) return;
            sleep(backoff);
            backoff = Math.min(15000, backoff * 2);
        }
    }

    /**
     * 开一条 `$events` 流并一直挂着。DSH 会把之后所有转发事件按 item 推过来；
     * 断了就抛出去让外层退避重连（重连时 Gateway 会把仍然 pending 的请求重发一遍，
     * 所以断线期间错过的审批不会丢）。
     */
    private void connectOnce() throws Exception {
        Log.i(TAG, "connectOnce: authenticating…");
        DshHttpTransport transport = new DshHttpTransport(app, endpoint());
        String cookie = transport.authCookie();
        Log.i(TAG, "connectOnce: cookie=" + (cookie == null ? "null" : "ok")
                + " host=" + transport.host() + " port=" + transport.port());
        DshWebSocket ws = new DshWebSocket();
        socket = ws;
        setStatus("正在连审批流…");
        ws.connect(transport.host(), transport.port(), "/api/remote.mux", cookie, 6000,
                new DshWebSocket.Listener() {
                    @Override public void onText(String message) { dispatch(message); }
                    @Override public void onFailure(String message) {
                        setStatus("审批流断开：" + message);
                    }
                });
        JSONObject open = new JSONObject();
        open.put("type", "open");
        open.put("streamId", "events-" + System.currentTimeMillis());
        open.put("endpoint", EVENTS_ENDPOINT);
        JSONObject payload = new JSONObject();
        payload.put("args", new JSONObject());        // 必须是空对象，多了会被拒
        open.put("payload", payload);
        ws.sendText(open.toString());
        Log.i(TAG, "approval $events stream opened against " + endpoint());
        while (running && !ws.isClosed()) sleep(300);
        ws.close();
        if (running) throw new java.io.IOException("events stream closed");
    }

    private String endpoint() {
        return prefs.getString("endpoint", DshHttpTransport.DEFAULT_ENDPOINT);
    }

    // ── 收帧 ───────────────────────────────────────────────────────────

    private void dispatch(String message) {
        try {
            JSONObject frame = new JSONObject(message);
            if (!"item".equals(frame.optString("type"))) {
                if ("error".equals(frame.optString("type"))) {
                    setStatus("审批流报错：" + frame.optJSONObject("error"));
                }
                return;
            }
            JSONObject value = frame.optJSONObject("value");
            if (value == null) return;
            String type = value.optString("type");
            if ("ready".equals(type)) {
                clientId = value.optString("clientId", "");
                setStatus("审批流已连上");
                Log.i(TAG, "events ready clientId=" + clientId
                        + " home=" + value.optJSONObject("host"));
                return;
            }
            if ("cancel".equals(type)) {
                String id = value.optString("eventId", "");
                Log.i(TAG, "cancel eventId=" + id);
                if (current != null && current.eventId.equals(id)) {
                    final String ev = id;
                    current = null;
                    if (listener != null) main.post(new Runnable() {
                        @Override public void run() { listener.onWithdraw(ev); }
                    });
                }
                return;
            }
            if (!"waterfall".equals(type)) return;

            String event = value.optString("event", "");
            if (!"approval/request".equals(event) && !"user-questions/request".equals(event)) {
                return;   // 其它转发事件（会话状态等）与球无关
            }
            Request req = parse(value, event);
            if (req == null || req.eventId.isEmpty()) return;
            Log.i(TAG, "waterfall " + event + " eventId=" + req.eventId
                    + " title=" + req.title() + " options=" + optionCount(req));
            current = req;
            final Request r = req;
            if (listener != null) main.post(new Runnable() {
                @Override public void run() { listener.onRequest(r); }
            });
        } catch (Throwable t) {
            Log.w(TAG, "dispatch failed: " + t);
        }
    }

    private static int optionCount(Request r) {
        int n = r.isApproval() ? 2 : 0;
        for (Question q : r.questions) n += q.labels.size();
        return n;
    }

    /** 把一条 waterfall 帧翻译成球要看的东西。 */
    private static Request parse(JSONObject value, String event) {
        JSONObject req = value.optJSONObject("request");
        if (req == null) return null;
        Request out = new Request();
        out.event = event;
        out.eventId = value.optString("eventId", "");
        out.agentId = value.optString("agentId", "");
        if ("approval/request".equals(event)) {
            out.toolName = req.optString("toolName", "");
            // displayReason 是给界面看的本地化文案：优先中文，退回 en，再退回审计用的 reason
            JSONObject disp = req.optJSONObject("displayReason");
            String shown = disp == null ? "" : disp.optString("zh", "");
            if (shown.isEmpty() && disp != null) shown = disp.optString("en", "");
            if (shown.isEmpty()) shown = req.optString("reason", "");
            out.reason = shown;
            return out;
        }
        JSONArray questions = req.optJSONArray("questions");
        if (questions == null) return out;
        for (int i = 0; i < questions.length(); i++) {
            JSONObject q = questions.optJSONObject(i);
            if (q == null) continue;
            Question item = new Question();
            item.id = q.optString("id", "");
            item.header = q.optString("header", "");
            item.question = q.optString("question", "");
            item.detail = q.optString("detail", "");
            item.multiSelect = q.optBoolean("multiSelect", false);
            JSONArray options = q.optJSONArray("options");
            if (options != null) {
                for (int j = 0; j < options.length(); j++) {
                    JSONObject o = options.optJSONObject(j);
                    if (o == null) continue;
                    item.labels.add(o.optString("label", ""));
                    item.descriptions.add(o.optString("description", ""));
                }
            }
            out.questions.add(item);
        }
        return out;
    }

    // ── 应答 ───────────────────────────────────────────────────────────

    /** 用户点了「批准这一次」。 */
    public void allow() { decide("allowed-once"); }

    /** 用户点了「拒绝」。 */
    public void reject() { decide("rejected"); }

    /**
     * 用户点了提问的某个选项。**注意**：提问的答案不是「一个字符串」，
     * 而是 `{answers:[{id,selected,custom}]}` —— 所以这里必须带上题号。
     * 单条问题的场景由 {@link #answerQuestion} 负责组装。
     */
    public void choose(String label) {
        String qid = current != null && !current.questions.isEmpty() ? current.questions.get(0).id : "";
        answerQuestion(qid, java.util.Collections.singletonList(label == null ? "" : label), "");
    }

    /**
     * 把结果回给 DSH。跑在后台线程（HTTP 会阻塞）。
     *
     * @param value 结果值。审批是字符串（allowed-once / rejected）；
     *              提问是**整个答案对象** `{"answers":[{"id","selected":[…],"custom"?}]}`
     *              —— 协议这么定的（见 AskUserQuestionAnswer），别塞成字符串。
     */
    public void decide(final Object value) {
        final Request req = current;
        if (req == null) return;
        final String id = clientId;
        if (id == null || id.isEmpty()) {
            setStatus("还没连上审批流，答案没送出去");
            return;
        }
        current = null;
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    DshHttpTransport transport = new DshHttpTransport(app, endpoint());
                    JSONObject outcome = new JSONObject();
                    outcome.put("kind", "result");
                    if (value instanceof JSONObject) outcome.put("value", (JSONObject) value);
                    else outcome.put("value", String.valueOf(value));
                    JSONObject args = new JSONObject();
                    args.put("clientId", id);
                    args.put("eventId", req.eventId);
                    args.put("outcome", outcome);
                    transport.call(RESULT_ENDPOINT, args);
                    Log.i(TAG, "answered eventId=" + req.eventId + " → " + value);
                    setStatus("已回传：" + value);
                } catch (Throwable t) {
                    setStatus("回传失败：" + shortMsg(t));
                    Log.w(TAG, "answer failed: " + t);
                }
            }
        });
    }

    /**
     * 回答一道提问（`ask_user_question`）。答案结构照抄 DSH 自己的客户端：
     *   `{ answers: [ { id, selected: [label…], custom?: "自由文本" } ] }`
     *
     * **单选的语义坑**：用户在自由输入里写了东西时，DSH 客户端会把 `selected`
     * 清空（`selected: custom === "" || multiSelect ? selected : []`）—— 因为
     * 单选下「自己写」和「选一个」是互斥的。多选则允许两者并存。
     *
     * @param questionId 题号（协议里的 question.id，原样回）
     * @param selected   选中的选项 label（单选最多一个；多选可多个）
     * @param custom     自由输入的内容，空串表示没写
     */
    public void answerQuestion(String questionId, java.util.List<String> selected, String custom) {
        final Request req = current;
        if (req == null) return;
        boolean multi = !req.questions.isEmpty() && req.questions.get(0).isMultiSelect();
        String text = custom == null ? "" : custom.trim();
        try {
            JSONObject item = new JSONObject();
            item.put("id", questionId == null ? "" : questionId);
            JSONArray picked = new JSONArray();
            if (text.isEmpty() || multi) {                 // 单选 + 自由文本 → selected 清空
                if (selected != null) for (String s : selected) picked.put(s);
            }
            item.put("selected", picked);
            if (!text.isEmpty()) item.put("custom", text);
            JSONObject answer = new JSONObject();
            JSONArray answers = new JSONArray();
            answers.put(item);
            answer.put("answers", answers);
            decide(answer);
        } catch (Throwable t) {
            Log.w(TAG, "answerQuestion build failed: " + t);
        }
    }

    // ── 调试：回路 HTTP 喂一条假请求 ───────────────────────────────────
    // 真审批要么等权限预设切到「会问」，要么等 DSH 自己在某处卡住。
    // 有了这个口子就能随时把球叫出来，对着调版式和点击。
    //
    // 为什么不直接读文件：这台机器上 Termux 与应用的挂载命名空间不一样
    // （Termux 里没有 /data/user，写 /data/data/... 应用看不见），
    // 所以走 127.0.0.1 上的一个小服务：
    //   GET  http://<endpoint host>:<port+1>/ballapproval       → 假请求 JSON（204 = 没有）
    //   POST http://<endpoint host>:<port+1>/ballapproval/answer → 用户点了哪个选项
    // 服务由 android/tools/approval-debug-server.mjs 提供，端口默认 DSH 端口 + 1。

    private boolean debugPolling = false;

    /**
     * 由球的渲染节拍调用（主线程）。
     *
     * HTTP 不能在主线程做（NetworkOnMainThreadException），所以这里只负责
     * 起一条后台轮询线程，取数与解析都在那条线程上跑。
     */
    void pumpDebugRequest() {
        if (!enabled()) return;          // 开关关着就别去戳调试回路（用户的意图优先）
        if (debugPolling) return;
        debugPolling = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { debugPollLoop(); }
        }, "dsh-approval-debug");
        t.setDaemon(true);
        t.start();
    }

    private void debugPollLoop() {
        Log.i(TAG, "approval debug pump live · bridge " + debugBase());
        while (true) {
            try {
                String text = debugFetch();                 // 阻塞，跑在后台线程
                if (text != null && !text.trim().isEmpty()) {
                    String err = handleDebugPayload(text);
                    if (err != null) Log.w(TAG, "debug payload rejected: " + err);
                }
            } catch (Throwable t) {
                Log.w(TAG, "debug poll failed: " + t);
            }
            sleep(250);
        }
    }

    /** 解析并弹球；只有 UI 那一段回到主线程。 */
    private String handleDebugPayload(String text) {
        try {
            JSONObject o = new JSONObject(text);
            final Request req = new Request();
            req.event = o.optString("event", "approval/request");
            req.eventId = "debug-" + System.currentTimeMillis();
            req.toolName = o.optString("toolName", "bash");
            req.reason = o.optString("reason", "");
            JSONArray opts = o.optJSONArray("options");
            if (opts != null) {
                Question q = new Question();
                q.header = o.optString("header", "");
                q.question = req.reason;
                for (int i = 0; i < opts.length(); i++) {
                    q.labels.add(opts.optString(i, ""));
                    q.descriptions.add("");
                }
                req.questions.add(q);
            }
            Log.i(TAG, "debug approval request: title=" + req.title()
                    + " options=" + optionCount(req));
            current = req;
            main.post(new Runnable() {
                @Override public void run() { if (listener != null) listener.onRequest(req); }
            });
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /** 调试模式下的答案走回路 HTTP（没有 clientId 就没法真回给 DSH）。 */
    boolean debugMode() {
        return current != null && current.eventId != null && current.eventId.startsWith("debug-");
    }

    void writeDebugAnswer(final String value) {
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(debugBase() + "/ballapproval/answer").openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(1500);
                    byte[] body = value.getBytes(Charset.forName("UTF-8"));
                    c.setFixedLengthStreamingMode(body.length);
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.close();
                    c.getResponseCode();
                    c.disconnect();
                    Log.i(TAG, "debug answer → bridge: " + value);
                } catch (Throwable t) {
                    Log.w(TAG, "debug answer post failed: " + t);
                }
            }
        });
    }

    /** 调试回路服务的基址：跟 DSH 同主机，端口 = DSH 端口 + 1。 */
    private String debugBase() {
        try {
            java.net.URI uri = java.net.URI.create(endpoint());
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            int port = (uri.getPort() > 0 ? uri.getPort() : 80) + 1;
            return "http://" + host + ":" + port;
        } catch (Throwable t) {
            return "http://127.0.0.1:3081";
        }
    }

    /** 拉一次假请求；204/连不上都返回 null（= 没有假审批，正常情况）。 */
    private String debugFetch() {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection)
                    new java.net.URL(debugBase() + "/ballapproval").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(700);
            c.setReadTimeout(700);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (debugFetches++ < 3) Log.i(TAG, "debug fetch #" + debugFetches + " → HTTP " + code);
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            in.close();
            return new String(buf.toByteArray(), Charset.forName("UTF-8"));
        } catch (Throwable t) {
            if (debugFetchErrors++ < 3) Log.w(TAG, "debug fetch #" + debugFetches + " failed: " + t);
            return null;      // 调试服务没开是常态，不打搅日志（前几次记一笔）
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private int debugFetches = 0;
    private int debugFetchErrors = 0;

    // ── 小工具 ────────────────────────────────────────────────────────

    private void setStatus(final String note) {
        status = note;
        final Listener l = listener;
        if (l != null) main.post(new Runnable() {
            @Override public void run() { l.onStatus(note); }
        });
    }

    private void runAsync(Runnable r) {
        Thread t = new Thread(r, "dsh-approval-post");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
