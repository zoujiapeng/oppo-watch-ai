package com.glslplayground.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
 * 与「基本不动的」本机 DSH 服务对话：只用它已经在给浏览器用的那套接口。
 *
 * 认证：DSH 的 web 服务在启动时打印一个一次性 launch token
 * （形如 http://127.0.0.1:3080/?token=…）。GET 这个地址会 303 并下发一个
 * 签名的 HttpOnly cookie，之后所有 /api 请求都靠它。安卓端以 root 读一次
 * 启动日志拿到 token，换取 cookie；cookie 过期或被重启作废时自动重来。
 *
 * 传输：POST /api/<endpoint>，信封
 *   {"type":"client-request","rpcId":…,"method":"<endpoint>","payload":{"args":{…}}}
 * 回答：{"type":"server-response","rpcId":…,"result":{"ok":true,"value":…}}
 *
 * 用到的 endpoint（都是 DSH 已有的）：
 *   session/list     → 会话列表，item.projections.asOfSeq 就是当前事件游标
 *   session/create   → 新会话
 *   session/prompt   → 发一句话
 *   session/page     → 读事件页（含 assistant/message 正文）
 */
public final class DshHttpTransport implements SiriAssistant.Transport {

    private static final String TAG = "DshTransport";
    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:3080";

    /** DSH 启动横幅会写到这些日志里；按顺序找第一个带 token 的。 */
    private static final String[] TOKEN_LOGS = {
        "/data/data/com.termux/files/home/dsh.log",
        "/data/data/com.termux/files/home/dsh-restart.log",
        "/data/data/com.termux/files/home/dsh-watchdog.log",
    };

    /**
     * 有人（`tools/dsh-cookie.sh`）替我们把会话 cookie 落在 Termux 家目录时，直接用它。
     *
     * 适用场景：DSH 是 `dshpj` 起的 —— token 只在它自己的终端里打印，`~/dsh.log` 没有，
     * 而反代（3081）手里握着的就是一条 30 天有效的 cookie。抄过来就不用再起第二个 DSH。
     */
    private static final String COOKIE_FILE = "/data/data/com.termux/files/home/dsh.cookie";

    private final Context app;
    private final String base;
    private final SharedPreferences prefs;
    private volatile String cookie;
    private volatile int rpcSeq = 0;
    private volatile boolean cancelled = false;
    private volatile Thread worker;
    /** 落盘的会话 cookie 被 DSH 拒过一次（过期/换实例）→ 本次进程内不再用它，回退去找 token。 */
    private volatile boolean cookieFileRejected = false;

    public DshHttpTransport(Context ctx, String endpoint) {
        this.app = ctx.getApplicationContext();
        String b = (endpoint == null || endpoint.trim().isEmpty()) ? DEFAULT_ENDPOINT : endpoint.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.prefs = app.getSharedPreferences("siri_assistant", Context.MODE_PRIVATE);
    }

    @Override public String endpoint() { return base; }

    // ── Transport ─────────────────────────────────────────────────────

    @Override public void ask(final String text, final SiriAssistant.Stream cb) {
        cancelled = false;
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    if (cookie == null) {
                        try { authenticate(); } catch (Exception e) { Log.i(TAG, "no cookie: " + e.getMessage()); }
                    }
                    String sessionId = pickSession();
                    // 优先走 WebSocket：只有它给得了逐 token 的 text-delta，
                    // 「一个字一个字弹出来」靠的就是这条流；失败再退回轮询。
                    if (askStreaming(sessionId, textContent(text), cb, SiriAssistant.Transport.MODE_QUEUE)) return;
                    if (cancelled) return;
                    askPolling(sessionId, text, cb, SiriAssistant.Transport.MODE_QUEUE);
                } catch (Throwable t) {
                    if (!cancelled) cb.onError(message(t));
                }
            }
        });
    }

    /**
     * 发到**指定会话**（三态手势机用：语音/状态 2 的发送目标可能是刚建的新会话，
     * 也可能是工作目录/搜索里选中的旧会话），并且允许带附件回执。
     */
    @Override public void askContent(final String sessionId, final String text,
                                     final java.util.List<String> receipts,
                                     final SiriAssistant.Stream cb) {
        askContent(sessionId, text, receipts, SiriAssistant.Transport.MODE_QUEUE, cb);
    }

    @Override public void askContent(final String sessionId, final String text,
                                     final java.util.List<String> receipts,
                                     final String mode,
                                     final SiriAssistant.Stream cb) {
        cancelled = false;
        final String delivery = SiriAssistant.Transport.MODE_STEER.equals(mode)
                ? SiriAssistant.Transport.MODE_STEER : SiriAssistant.Transport.MODE_QUEUE;
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    if (cookie == null) {
                        try { authenticate(); } catch (Exception e) { Log.i(TAG, "no cookie: " + e.getMessage()); }
                    }
                    JSONArray content = textContent(text);
                    if (receipts != null) {
                        for (String r : receipts) {
                            if (r != null && !r.isEmpty()) {
                                content.put(new JSONObject().put("type", "file").put("receiptId", r));
                            }
                        }
                    }
                    Log.i(TAG, "prompt mode=" + delivery + " session=" + sessionId);
                    if (askStreaming(sessionId, content, cb, delivery)) return;
                    if (cancelled) return;
                    askPolling(sessionId, text, cb, delivery);
                } catch (Throwable t) {
                    if (!cancelled) cb.onError(message(t));
                }
            }
        });
    }

    /** prompt content 里的文字段（至少一段：空文本时给一个空格，避免"没有内容可发"）。 */
    private static JSONArray textContent(String text) throws Exception {
        JSONArray content = new JSONArray();
        String t = text == null ? "" : text;
        if (t.trim().isEmpty()) t = "（附件）";
        content.put(new JSONObject().put("type", "text").put("text", t));
        return content;
    }

    /**
     * 流式路径：`ws://…/api/remote.mux` 上开一条 `session/follow`
     * （带 `assistantStream:true` 才有增量），边收 `chunk.text-delta`
     * 边把累积文本回调出去；durable 的 assistant/message 到达时用它的
     * 正文覆盖累积值（权威文本），turn/end 收尾。
     */
    private boolean askStreaming(String sessionId, JSONArray content, final SiriAssistant.Stream cb,
                                 String mode) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(base);
        } catch (Throwable t) {
            return false;
        }
        final String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        final int port = uri.getPort() > 0 ? uri.getPort() : 80;

        final Object lock = new Object();
        final Object toolLock = new Object();
        final boolean[] toolBusy = {false};
        final StringBuilder buf = new StringBuilder();
        final boolean[] done = {false};
        final boolean[] failed = {false};
        final String[] failMsg = {null};
        final long[] lastPush = {0};
        final int[] deltas = {0};
        final DshWebSocket[] holder = new DshWebSocket[1];

        DshWebSocket ws = new DshWebSocket();
        holder[0] = ws;
        try {
            ws.connect(host, port, "/api/remote.mux", cookie, 6000, new DshWebSocket.Listener() {
                @Override public void onText(String message) {
                    try {
                        JSONObject frame = new JSONObject(message);
                        if (!"item".equals(frame.optString("type"))) return;
                        JSONObject value = frame.optJSONObject("value");
                        if (value == null) return;
                        if ("assistant-stream".equals(value.optString("type"))) {
                            JSONObject f = value.optJSONObject("frame");
                            if (f == null || !"chunk".equals(f.optString("type"))) return;
                            JSONObject chunk = f.optJSONObject("chunk");
                            if (chunk == null || !"text-delta".equals(chunk.optString("type"))) return;
                            synchronized (lock) {
                                buf.append(chunk.optString("text", ""));
                                deltas[0]++;
                                long now = System.currentTimeMillis();
                                if (now - lastPush[0] >= 45) {
                                    lastPush[0] = now;
                                    cb.onDelta(buf.toString());
                                }
                            }
                            return;
                        }
                        JSONObject ev = value.optJSONObject("event");
                        if (ev == null) ev = value;
                        String type = ev.optString("type");
                        // ★ 2026-11-08：球自己提问的这一轮，工具事件以前**整条被丢掉**——
                        //   镜像那条流在 askInFlight 时让路，这条流又不看 tool/call，
                        //   于是「帮我去微信点个外卖」这种整段在操控手机的任务，
                        //   球全程停在思考/待机。这里把工具/任务事件直接喂给球。
                        if ("tool/call".equals(type)) {
                            JSONObject data = ev.optJSONObject("data");
                            String toolName = data == null ? null : data.optString("name", null);
                            synchronized (toolLock) { toolBusy[0] = true; }
                            FloatingBallService.setAssistantToolRunning(true, toolName);
                        } else if ("job/registered".equals(type)) {
                            synchronized (toolLock) { toolBusy[0] = true; }
                            FloatingBallService.setAssistantToolRunning(true, null);
                        } else if ("tool/result".equals(type) || "job/settled".equals(type)) {
                            synchronized (toolLock) { toolBusy[0] = false; }
                            FloatingBallService.setAssistantToolRunning(false, null);
                        } else if ("assistant/message".equals(type)) {
                            String body = textOf(ev);
                            if (body != null && !body.trim().isEmpty()) {
                                synchronized (lock) {
                                    buf.setLength(0);
                                    buf.append(body);
                                    cb.onDelta(buf.toString());
                                }
                            }
                        } else if ("turn/end".equals(type) || "session/removed".equals(type)) {
                            // 工具还在跑就不算这一轮结束：DSH 会先把 turn/end 发出来，
                            // 命令/工具是异步 job（job/registered → job/settled）。
                            boolean busy;
                            synchronized (toolLock) { busy = toolBusy[0]; }
                            if (busy) {
                                Log.i(TAG, "turn/end 但工具还在跑 → 不结束这一轮");
                            } else {
                                synchronized (lock) { done[0] = true; lock.notifyAll(); }
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                @Override public void onFailure(String message) {
                    synchronized (lock) { failed[0] = true; failMsg[0] = message; lock.notifyAll(); }
                }
            });
        } catch (Throwable t) {
            Log.i(TAG, "ws connect failed: " + t);
            return false;
        }

        try {
            JSONObject open = new JSONObject();
            open.put("type", "open");
            open.put("streamId", "follow-" + (++rpcSeq));
            open.put("endpoint", "session/follow");
            JSONObject args = new JSONObject();
            JSONObject req = new JSONObject();
            JSONObject addr = new JSONObject();
            addr.put("kind", "session");
            addr.put("sessionId", sessionId);
            req.put("address", addr);
            req.put("assistantStream", true);
            args.put("request", req);
            JSONObject payload = new JSONObject();
            payload.put("args", args);
            open.put("payload", payload);
            ws.sendText(open.toString());

            prompt(sessionId, content, mode);
        } catch (Throwable t) {
            ws.close();
            Log.i(TAG, "ws setup/prompt failed: " + t);
            if (cancelled) return true;
            return false;
        }

        long deadline = System.currentTimeMillis() + 180000L;
        synchronized (lock) {
            while (!done[0] && !failed[0] && !cancelled && System.currentTimeMillis() < deadline) {
                try { lock.wait(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        ws.close();
        if (cancelled) return true;

        String answer;
        synchronized (lock) { answer = buf.toString().trim(); }
        if (answer.isEmpty()) {
            if (done[0]) { cb.onError("DSH 没有返回内容"); return true; }
            Log.i(TAG, "ws gave no text (" + failMsg[0] + "), falling back to polling");
            return false;
        }
        cb.onDone(answer);
        Log.i(TAG, "ws stream done: " + deltas[0] + " text-deltas, " + answer.length() + " chars");
        return true;
    }

    /** 兜底路径：轮询 session/page。流式不可用时才走这里。 */
    private void askPolling(String sessionId, String text, SiriAssistant.Stream cb, String mode) throws Exception {
        long baseline = cursorOf(sessionId);
        prompt(sessionId, textContent(text), mode);
        long deadline = System.currentTimeMillis() + 180000L;
        String best = "";
        while (!cancelled && System.currentTimeMillis() < deadline) {
            sleep(850);
            if (cancelled) break;
            String latest = readAnswer(sessionId, baseline);
            if (latest != null && latest.length() > best.length()) {
                best = latest;
                cb.onDelta(best);
            }
            if (!isRunning(sessionId) && !best.isEmpty()) break;
        }
        if (cancelled) return;
        if (best.isEmpty()) cb.onError("DSH 没有返回内容");
        else cb.onDone(best);
    }

    /** 发一条用户消息（content 已经是 prompt content parts）。 */
    private void prompt(String sessionId, JSONArray content, String mode) throws Exception {
        JSONObject args = new JSONObject();
        JSONObject req = new JSONObject();
        req.put("requestId", "android-" + System.currentTimeMillis() + "-" + (++rpcSeq));
        req.put("sessionId", sessionId);
        req.put("mode", SiriAssistant.Transport.MODE_STEER.equals(mode)
                ? SiriAssistant.Transport.MODE_STEER : SiriAssistant.Transport.MODE_QUEUE);
        req.put("content", content);
        args.put("request", req);
        rpc("session/prompt", args);
    }

    @Override public void cancel() {
        cancelled = true;
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    @Override public void shutdown() {
        cancel();
        stopMirror();
    }

    // ── 常驻镜像：跟着当前会话，AI 一有输出就往球里打 ──────────────────

    private volatile boolean mirrorRunning = false;
    private volatile DshWebSocket mirrorSocket;
    private volatile String mirrorSession;
    private volatile SiriAssistant.Mirror mirror;

    @Override public void startMirror(SiriAssistant.Mirror cb) {
        if (mirrorRunning) { mirror = cb; return; }
        mirror = cb;
        mirrorRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { mirrorLoop(); }
        }, "dsh-mirror");
        t.setDaemon(true);
        t.start();
        Thread w = new Thread(new Runnable() {
            @Override public void run() { mirrorWatch(); }
        }, "dsh-mirror-watch");
        w.setDaemon(true);
        w.start();
    }

    @Override public void stopMirror() {
        mirrorRunning = false;
        DshWebSocket ws = mirrorSocket;
        if (ws != null) ws.close();
        mirrorSocket = null;
        mirrorSession = null;
    }

    /** 断线重连；每次重连都重新解析「当前会话」，所以在 DSH 里换对话会自动跟上。 */
    private void mirrorLoop() {
        while (mirrorRunning) {
            try {
                if (cookie == null) authenticate();
                final String session = pickActiveSession();
                mirrorSession = session;
                DshWebSocket ws = new DshWebSocket();
                mirrorSocket = ws;
                ws.connect(hostOf(), portOf(), "/api/remote.mux", cookie, 6000, new DshWebSocket.Listener() {
                    @Override public void onText(String message) { dispatchMirror(message); }
                    @Override public void onFailure(String m) {
                        if (mirror != null && mirrorRunning) mirror.onStatus("镜像断开：" + m);
                    }
                });
                ws.sendText(openEnvelope(session));
                if (mirror != null) mirror.onStatus("镜像中 · " + session);
                Log.i(TAG, "mirror following " + session);
                while (mirrorRunning && !ws.isClosed()) sleep(300);
                ws.close();
            } catch (Throwable t) {
                if (mirror != null && mirrorRunning) mirror.onStatus("镜像连接失败：" + t.getMessage());
            }
            mirrorSocket = null;
            if (mirrorRunning) sleep(2500);
        }
    }

    /** 这条会话这一轮结束时置位：立刻重新挑一次「正在活动的会话」。 */
    private volatile boolean mirrorRecheck = false;

    /**
     * 盯着「现在该跟哪条会话」。
     *
     * 以前 20 秒才看一次，而且只看"最近更新"—— 于是 A 会话那一轮结束了，
     * 球还挂在 A 上回待机，明明 B 正在跑。现在：
     *   · 0.5s 一跳，每 ~3s（或收到 turn/end 的强制标记时）重新挑；
     *   · 挑的时候优先正在跑的会话（pickActiveSession）；
     *   · 变了就关掉旧 WS，外层 mirrorLoop 会连到新的那条。
     */
    private void mirrorWatch() {
        int tick = 0;
        while (mirrorRunning) {
            sleep(500);
            if (!mirrorRunning) return;
            boolean force = mirrorRecheck;
            if (!force && ++tick < 6) continue;
            tick = 0;
            mirrorRecheck = false;
            try {
                String active = pickActiveSession();
                if (mirrorSession != null && !mirrorSession.equals(active)) {
                    Log.i(TAG, "镜像：切会话 " + mirrorSession + " → " + active);
                    DshWebSocket ws = mirrorSocket;
                    if (ws != null) ws.close();          // 关掉旧流，外层循环会连到新会话
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 把一条 WS 消息翻译成镜像回调。 */
    private void dispatchMirror(String message) {
        SiriAssistant.Mirror cb = mirror;
        if (cb == null) return;
        try {
            JSONObject frame = new JSONObject(message);
            if (!"item".equals(frame.optString("type"))) return;
            JSONObject value = frame.optJSONObject("value");
            if (value == null) return;
            if ("assistant-stream".equals(value.optString("type"))) {
                JSONObject f = value.optJSONObject("frame");
                if (f == null) return;
                String ft = f.optString("type");
                if ("start".equals(ft)) {
                    cb.onAttemptStart();
                } else if ("chunk".equals(ft)) {
                    JSONObject chunk = f.optJSONObject("chunk");
                    if (chunk == null || !"text-delta".equals(chunk.optString("type"))) return;
                    cb.onText(chunk.optString("text", ""));
                }
                return;
            }
            JSONObject ev = value.optJSONObject("event");
            if (ev == null) ev = value;
            String type = ev.optString("type");
            if ("assistant/message".equals(type)) {
                String body = textOf(ev);
                if (body != null && !body.trim().isEmpty()) cb.onFinal(body);
            } else if ("turn/end".equals(type)) {
                // 这一轮结束了：马上重新挑「正在活动的会话」（别的会话可能正在跑）
                mirrorRecheck = true;
                // ⚠️ 这里**不要**清「执行中」：DSH 的 turn/end 比命令真正结束早得多
                //    （实测 turn/end 到 tool/result 隔着十几秒）。清掉的话，
                //    整段「运行命令」期间球显示的是待机动画 —— 用户报的就是这个。
                cb.onTurnEnd();
            } else if ("tool/call".equals(type)) {
                // 真·工具开始跑 → 「执行中 / 调用工具」槽（不再靠"思考超过 2.5s"猜）。
                // 工具名在 `data.name`（实测 bash / read / edit / grep …），球按它分流：
                // 命令走「执行中」，其它工具走「调用工具」——两档各绑一套动画。
                JSONObject data = ev.optJSONObject("data");
                String toolName = data == null ? null : data.optString("name", null);
                cb.onTool(true, toolName);
            } else if ("tool/result".equals(type)) {
                cb.onTool(false, null);
            } else if ("job/registered".equals(type)) {
                // 后台任务：tool/result 会先回来（只代表「已登记」），活儿还在跑
                cb.onTool(true, null);
            } else if ("job/settled".equals(type)) {
                cb.onTool(false, null);
            } else if ("user/message".equals(type)) {
                cb.onTool(false, null);        // 新一轮用户消息：工具状态清零
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 镜像累计文本的硬上限：一有新的 text-delta 就把最老的内容丢掉。
     * 一轮长任务的助手输出可以到几万字，原样攒下去会一起灌进球里（球内糊成一整面墙）。
     * 这里只保留「最近这一段」，配合 SiriAssistant.clipToBall 的显示上限。
     */
    // 镜像缓存上限：和球内文字上限同量级（球内可滚动，放宽到 2500 字；
    // 球侧还会再按 FloatingBallService.BALL_TEXT_MAX 截一次尾巴）。
    private static final int MIRROR_BUF_MAX = 2500;

    static void capBuffer(StringBuilder buf, int max) {
        int over = buf.length() - max;
        if (over > 0) buf.delete(0, over);
    }

    /** 字符串版：只保留最后 max 个字符（前面补省略号）。 */
    static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "…" + s.substring(s.length() - max);
    }

    private String hostOf() {
        try {
            java.net.URI uri = java.net.URI.create(base);
            return uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        } catch (Throwable t) {
            return "127.0.0.1";
        }
    }

    private int portOf() {
        try {
            java.net.URI uri = java.net.URI.create(base);
            return uri.getPort() > 0 ? uri.getPort() : 80;
        } catch (Throwable t) {
            return 80;
        }
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() > 12 ? id.substring(id.length() - 8) : id;
    }

    /** session/follow 的 open 信封。 */
    private static String openEnvelope(String sessionId) throws Exception {
        JSONObject open = new JSONObject();
        open.put("type", "open");
        open.put("streamId", "mirror-" + System.currentTimeMillis());
        open.put("endpoint", "session/follow");
        JSONObject args = new JSONObject();
        JSONObject req = new JSONObject();
        JSONObject addr = new JSONObject();
        addr.put("kind", "session");
        addr.put("sessionId", sessionId);
        req.put("address", addr);
        req.put("assistantStream", true);
        args.put("request", req);
        JSONObject payload = new JSONObject();
        payload.put("args", args);
        open.put("payload", payload);
        return open.toString();
    }

    // ── DSH 会话读取 ───────────────────────────────────────────────────

    /**
     * 当前要对话的会话 = DSH 里最近在用的那条（排除子智能体）。
     *
     * 刻意不记住上次用的会话：镜像要跟着「你正在聊的那条」，在浏览器里
     * 换了对话、或新开一条，球就跟着走；只有一条都没有时才新建。
     */
    /**
     * 镜像跟哪条会话。
     *
     * ★ 2026-11-08 用户要求：「对应的窗口结束没活动、回待机时，动画要自动对到正在活动的别的回话」。
     *   所以先看有没有 **running=true** 的会话（DSH 正在跑这一轮），有就跟它；
     *   都在闲着才退回"最近更新的那条"（原来的行为）。
     */
    private String pickActiveSession() throws Exception {
        JSONObject value = rpc("session/list", listArgs());
        JSONArray items = value == null ? null : value.optJSONArray("items");
        String busiest = null;
        long busiestAt = -1;
        String newest = null;
        long newestAt = -1;
        boolean followingStillRunning = false;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if ("subagent".equals(it.optString("origin"))) continue;
                String sid = it.optString("sessionId");
                if (sid == null || sid.isEmpty()) continue;
                long at = it.optLong("updatedAt", 0);
                if (at > newestAt) { newestAt = at; newest = sid; }
                boolean running = it.optBoolean("running", false);
                if (running && sid.equals(mirrorSession)) followingStillRunning = true;
                if (running && at > busiestAt) { busiestAt = at; busiest = sid; }
            }
        }
        // ⚠ 正在跟的那条**还在跑**就别切走 —— 否则两条会话同时在跑时会每 3 秒来回跳。
        //   只有「当前这条结束了 / 没了」才切到别的正在活动的会话上（用户要的正是这个）。
        String best;
        if (followingStillRunning && mirrorSession != null) {
            best = mirrorSession;
        } else {
            best = busiest != null ? busiest : newest;
            if (busiest != null && !busiest.equals(mirrorSession)) {
                Log.i(TAG, "镜像：当前会话已停，切到正在活动的 " + busiest);
            }
        }
        if (best != null && best.equals(mirrorSession)) return best;
        if (best == null || best.isEmpty()) {
            JSONObject args = new JSONObject();
            JSONObject req = new JSONObject();
            req.put("cwd", app.getFilesDir().getAbsolutePath());
            args.put("request", req);
            JSONObject created = rpc("session/create", args);
            best = created == null ? null : created.optString("sessionId");
        }
        if (best == null || best.isEmpty()) throw new IOException("没有可用的 DSH 会话");
        prefs.edit().putString("session_id", best).apply();
        return best;
    }

    private String pickSession() throws Exception {
        JSONObject value = rpc("session/list", listArgs());
        JSONArray items = value == null ? null : value.optJSONArray("items");
        String best = null;
        long bestAt = -1;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if ("subagent".equals(it.optString("origin"))) continue;
                long at = it.optLong("updatedAt", 0);
                if (at > bestAt) { bestAt = at; best = it.optString("sessionId"); }
            }
        }
        if (best == null || best.isEmpty()) {
            JSONObject args = new JSONObject();
            JSONObject req = new JSONObject();
            req.put("cwd", app.getFilesDir().getAbsolutePath());
            args.put("request", req);
            JSONObject created = rpc("session/create", args);
            best = created == null ? null : created.optString("sessionId");
        }
        if (best == null || best.isEmpty()) throw new IOException("没有可用的 DSH 会话");
        prefs.edit().putString("session_id", best).apply();
        return best;
    }

    private static JSONObject listArgs() throws Exception {
        JSONObject args = new JSONObject();
        args.put("_request", new JSONObject());   // list 的形参名就是 _request
        return args;
    }

    /** 会话当前事件游标（session/page 的 throughSeq 不能超过它）。 */
    private long cursorOf(String sessionId) throws Exception {
        JSONObject row = findRow(sessionId);
        if (row == null) return 0;
        JSONObject proj = row.optJSONObject("projections");
        return proj == null ? 0 : proj.optLong("asOfSeq", 0);
    }

    private boolean isRunning(String sessionId) throws Exception {
        JSONObject row = findRow(sessionId);
        return row != null && row.optBoolean("running", false);
    }

    private JSONObject findRow(String sessionId) throws Exception {
        JSONObject value = rpc("session/list", listArgs());
        JSONArray items = value == null ? null : value.optJSONArray("items");
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (sessionId.equals(it.optString("sessionId"))) return it;
        }
        return null;
    }

    /** 读一页事件，拼出 baseline 之后最后一个 assistant 正文。 */
    private String readAnswer(String sessionId, long baseline) throws Exception {
        long cursor = cursorOf(sessionId);
        if (cursor <= baseline) return null;
        JSONObject args = new JSONObject();
        JSONObject req = new JSONObject();
        JSONObject addr = new JSONObject();
        addr.put("kind", "session");
        addr.put("sessionId", sessionId);
        req.put("address", addr);
        req.put("throughSeq", cursor);
        req.put("maxMessages", 40);
        args.put("request", req);

        JSONObject page = rpc("session/page", args);
        JSONArray records = page == null ? null : page.optJSONArray("records");
        if (records == null) return null;
        String text = null;
        for (int i = 0; i < records.length(); i++) {
            JSONObject rec = records.getJSONObject(i);
            JSONObject ev = rec.optJSONObject("event");
            if (ev == null) ev = rec;
            if (!"assistant/message".equals(ev.optString("type"))) continue;
            if (ev.optLong("seq", 0) <= baseline) continue;
            String body = textOf(ev);
            if (body != null && !body.trim().isEmpty()) text = body;
        }
        return text;
    }

    private static String textOf(JSONObject event) {
        JSONObject data = event.optJSONObject("data");
        if (data == null) return null;
        JSONObject message = data.optJSONObject("message");
        if (message == null) return null;
        JSONArray content = message.optJSONArray("content");
        if (content == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if ("text".equals(block.optString("type"))) sb.append(block.optString("text", ""));
        }
        return sb.toString();
    }

    // ── 给审批流用的几个入口（同一个 cookie / 同一个地址）────────────────

    /** 换一次签名 cookie 并把 cookie 串交出去（WebSocket 握手要带它）。 */
    String authCookie() throws IOException {
        if (cookie == null) authenticate();
        return cookie;
    }

    String host() { return hostOf(); }

    int port() { return portOf(); }

    /** 通用 RPC（审批决策就是一条普通 RPC：`$events/result`）。 */
    JSONObject call(String endpoint, JSONObject args) throws Exception {
        return rpc(endpoint, args);
    }

    // ── JSON-RPC over HTTP ─────────────────────────────────────────────

    private JSONObject rpc(String endpoint, JSONObject args) throws Exception {
        Exception last = null;
        // 第一次先确保拿到 cookie；拿不到也照样试一次请求——这个 DSH 安装可能
        // 把单机认证关掉了（上游默认要求签名 cookie），此时无 cookie 也能通。
        try { if (cookie == null) authenticate(); } catch (Exception e) { Log.i(TAG, "no cookie: " + e.getMessage()); }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject envelope = new JSONObject();
                envelope.put("type", "client-request");
                envelope.put("rpcId", "android-" + (++rpcSeq));
                envelope.put("method", endpoint);
                JSONObject payload = new JSONObject();
                payload.put("args", args);
                envelope.put("payload", payload);

                HttpURLConnection c = open("/api/" + endpoint, "POST");
                c.setRequestProperty("content-type", "application/json");
                if (cookie != null) c.setRequestProperty("cookie", cookie);
                byte[] body = envelope.toString().getBytes(Charset.forName("UTF-8"));
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();

                int code = c.getResponseCode();
                if (code == 401 || code == 403) {          // cookie 过期/服务重启 → 重新换一次
                    cookie = null;
                    cookieFileRejected = true;             // 磁盘上那份也可能过期了，别再拿它试
                    drain(c);
                    last = new IOException("DSH 认证失败(" + code + ")");
                    continue;
                }
                if (code == 404 && attempt < 2) {           // 服务刚重启，稍后再试
                    drain(c);
                    last = new IOException("DSH 路由未就绪(404)");
                    sleep(400);
                    continue;
                }
                String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code >= 400) throw new IOException("DSH HTTP " + code + ": " + trim(text));
                JSONObject res = new JSONObject(text);
                JSONObject result = res.optJSONObject("result");
                if (result == null) throw new IOException("DSH 响应缺少 result");
                if (!result.optBoolean("ok", false)) {
                    JSONObject err = result.optJSONObject("error");
                    throw new IOException(err == null ? "DSH 调用失败" : err.optString("message", "DSH 调用失败"));
                }
                // 有些方法（比如审批回执 $events/result）只回 ok、没有 value，
                // 调用方拿 null 就当「成功但无返回值」。
                if (!result.has("value") || result.isNull("value")) return null;
                return result.optJSONObject("value");
            } catch (Exception e) {
                last = e;
                if (attempt < 2) {
                    cookie = null;
                    try { authenticate(); } catch (Exception ignored) {}
                    sleep(250);
                }
            }
        }
        throw last == null ? new IOException("DSH 调用失败") : last;
    }

    /**
     * 用启动 token 换签名 cookie。
     *
     * 但**先看有没有人替我们把 cookie 落盘了**：`dshpj` / 反代那条路上，token 只打印在
     * 它自己的终端里、`~/dsh.log` 里没有；由 `tools/dsh-cookie.sh` 把反代手里的
     * 会话 cookie 写进 `~/dsh.cookie`，球就直接用它 —— 不必再起第二个 DSH，
     * 也不必占一个多余端口（用户 2026-10-22 的要求）。
     */
    private void authenticate() throws IOException {
        String fileCookie = cookieFileRejected ? null : readCookieFile();
        if (fileCookie != null) {
            cookie = fileCookie;
            Log.i(TAG, "authenticated against " + base + " via dsh.cookie ("
                    + fileCookie.substring(0, Math.min(24, fileCookie.length())) + "…)");
            return;
        }
        String token = readLaunchToken();
        if (token == null || token.isEmpty()) {
            throw new IOException("没找到 DSH 启动 token（dsh.log），也没有 dsh.cookie");
        }
        HttpURLConnection c = open("/?token=" + URLEncoder.encode(token, "UTF-8"), "GET");
        int code = c.getResponseCode();
        String setCookie = c.getHeaderField("Set-Cookie");
        drain(c);
        if (setCookie == null || setCookie.isEmpty()) {
            throw new IOException("DSH 未下发会话 cookie (HTTP " + code + ")");
        }
        int semi = setCookie.indexOf(';');
        String pair = semi > 0 ? setCookie.substring(0, semi) : setCookie;
        if (!pair.startsWith("dsh-auth-")) throw new IOException("DSH cookie 名称异常");
        cookie = pair;
        Log.i(TAG, "authenticated against " + base + " (" + pair.substring(0, Math.min(24, pair.length())) + "…)");
    }

    /** 读 `~/dsh.cookie`：普通 App 读不到 Termux 家目录，直接读失败就走 root。 */
    private static String readCookieFile() {
        String txt = readTailQuietly(COOKIE_FILE, 4096);
        if (txt == null || txt.trim().isEmpty()) txt = rootTail(COOKIE_FILE, 4096);
        if (txt == null || txt.isEmpty()) return null;
        for (String line : txt.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("dsh-auth-") && t.indexOf('=') > "dsh-auth-".length()) return t;
        }
        return null;
    }

    /** 从 DSH 启动日志里取 launch token；直接读不到就用 root 读。 */
    private String readLaunchToken() {
        for (String path : TOKEN_LOGS) {
            String t = grepToken(readTailQuietly(path, 262144));
            if (t != null) return t;
            t = grepToken(rootTail(path, 262144));
            if (t != null) return t;
        }
        return null;
    }

    /**
     * 取**最后一个** token，不是第一个。
     *
     * ⚠️ 这是个真踩过的坑（2026-10-17）：`dsh.log` 是**追加**写的（看门狗 `>>`），
     * DSH 每重启一次就多一行启动横幅，而**只有最新那个 token 有效**。
     * 老实现返回第一个 → DSH 一重启，球就再也认证不上；日志只有一句
     * `DSH 未下发会话 cookie (HTTP 303)`（旧 token 换来的 303 不带 set-cookie），
     * 看起来像 DSH 的毛病，其实是读错了行。
     */
    private static String grepToken(String text) {
        if (text == null) return null;
        String found = null;
        int from = 0;
        while (true) {
            int at = text.indexOf("token=", from);
            if (at < 0) return found;
            int i = at + 6;
            StringBuilder sb = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') { sb.append(c); i++; }
                else break;
            }
            if (sb.length() >= 16) found = sb.toString();
            from = at + 6;
        }
    }

    /** 读文件**尾部** max 字节 —— token 在启动横幅里，日志长了只读头会错过最新那次启动。 */
    private static String readTailQuietly(String path, int max) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return null;
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            raf.seek(Math.max(0, len - max));
            byte[] buf = new byte[(int) Math.min(max, Math.max(0, len))];
            int n = raf.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) { }
        }
    }

    private static String rootTail(String path, int max) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "tail -c " + max + " " + path)
                    .redirectErrorStream(false).start();
            String out = read(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    // ── HTTP 小工具 ────────────────────────────────────────────────────

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(4000);
        c.setReadTimeout(120000);
        c.setUseCaches(false);
        return c;
    }

    private void runAsync(Runnable r) {
        Thread t = new Thread(r, "dsh-transport");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    private static void drain(HttpURLConnection c) {
        try { close(c.getInputStream()); } catch (Throwable ignored) {}
        try { close(c.getErrorStream()); } catch (Throwable ignored) {}
    }

    private static void close(InputStream in) {
        if (in != null) try { in.close(); } catch (Throwable ignored) {}
    }

    private static void close(OutputStream out) {
        if (out != null) try { out.close(); } catch (Throwable ignored) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        if (t instanceof java.net.ConnectException) m = "连不上 DSH（" + m + "）";
        return m;
    }
}
