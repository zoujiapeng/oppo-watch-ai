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
 *   speech/transcribe→ 16 kHz PCM16 WAV(base64) → 文本，走 profile 里配的语音插件
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

    private final Context app;
    private final String base;
    private final SharedPreferences prefs;
    private volatile String cookie;
    private volatile int rpcSeq = 0;
    private volatile boolean cancelled = false;
    private volatile Thread worker;

    public DshHttpTransport(Context ctx, String endpoint) {
        this.app = ctx.getApplicationContext();
        String b = (endpoint == null || endpoint.trim().isEmpty()) ? DEFAULT_ENDPOINT : endpoint.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.prefs = app.getSharedPreferences("siri_assistant", Context.MODE_PRIVATE);
    }

    @Override public String endpoint() { return base; }

    // ── Transport ─────────────────────────────────────────────────────

    @Override public void transcribe(final String wavBase64, final SiriAssistant.Result<String> cb) {
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject args = new JSONObject();
                    JSONObject req = new JSONObject();
                    req.put("audioBase64", wavBase64);
                    req.put("language", "zh");
                    args.put("request", req);
                    JSONObject value = rpc("speech/transcribe", args);
                    cb.ok(value == null ? "" : value.optString("text", ""));
                } catch (Throwable t) {
                    cb.fail(message(t));
                }
            }
        });
    }

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
                    if (askStreaming(sessionId, text, cb)) return;
                    if (cancelled) return;
                    askPolling(sessionId, text, cb);
                } catch (Throwable t) {
                    if (!cancelled) cb.onError(message(t));
                }
            }
        });
    }

    /**
     * 流式路径：`ws://…/api/remote.mux` 上开一条 `session/follow`
     * （带 `assistantStream:true` 才有增量），边收 `chunk.text-delta`
     * 边把累积文本回调出去；durable 的 assistant/message 到达时用它的
     * 正文覆盖累积值（权威文本），turn/end 收尾。
     */
    private boolean askStreaming(String sessionId, String text, final SiriAssistant.Stream cb) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(base);
        } catch (Throwable t) {
            return false;
        }
        final String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        final int port = uri.getPort() > 0 ? uri.getPort() : 80;

        final Object lock = new Object();
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
                        if ("assistant/message".equals(type)) {
                            String body = textOf(ev);
                            if (body != null && !body.trim().isEmpty()) {
                                synchronized (lock) {
                                    buf.setLength(0);
                                    buf.append(body);
                                    cb.onDelta(buf.toString());
                                }
                            }
                        } else if ("turn/end".equals(type) || "session/removed".equals(type)) {
                            synchronized (lock) { done[0] = true; lock.notifyAll(); }
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

            prompt(sessionId, text);
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
    private void askPolling(String sessionId, String text, SiriAssistant.Stream cb) throws Exception {
        long baseline = cursorOf(sessionId);
        prompt(sessionId, text);
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

    /** 发一条用户消息。 */
    private void prompt(String sessionId, String text) throws Exception {
        JSONObject args = new JSONObject();
        JSONObject req = new JSONObject();
        req.put("requestId", "android-" + System.currentTimeMillis() + "-" + (++rpcSeq));
        req.put("sessionId", sessionId);
        req.put("mode", "queue");
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", text));
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
                final String session = pickSession();
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

    /** 每 20 秒看一次当前会话有没有变（DSH 里切换对话 / 新建会话）。 */
    private void mirrorWatch() {
        while (mirrorRunning) {
            sleep(20000);
            if (!mirrorRunning) return;
            try {
                String current = pickSession();
                if (mirrorSession != null && !mirrorSession.equals(current)) {
                    DshWebSocket ws = mirrorSocket;
                    if (ws != null) ws.close();          // 关掉旧流，外层循环会连到新会话
                }
            } catch (Throwable ignored) {}
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
                cb.onTurnEnd();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 镜像累计文本的硬上限：一有新的 text-delta 就把最老的内容丢掉。
     * 一轮长任务的助手输出可以到几万字，原样攒下去会一起灌进球里（球内糊成一整面墙）。
     * 这里只保留「最近这一段」，配合 SiriAssistant.clipToBall 的显示上限。
     */
    private static final int MIRROR_BUF_MAX = 1200;

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

    /** 用启动 token 换签名 cookie。 */
    private void authenticate() throws IOException {
        String token = readLaunchToken();
        if (token == null || token.isEmpty()) {
            throw new IOException("没找到 DSH 启动 token（dsh.log）");
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

    /** 从 DSH 启动日志里取 launch token；直接读不到就用 root 读。 */
    private String readLaunchToken() {
        for (String path : TOKEN_LOGS) {
            String t = grepToken(readHeadQuietly(path));
            if (t != null) return t;
            t = grepToken(rootCat(path));
            if (t != null) return t;
        }
        return null;
    }

    private static String grepToken(String text) {
        if (text == null) return null;
        int from = 0;
        while (true) {
            int at = text.indexOf("token=", from);
            if (at < 0) return null;
            int i = at + 6;
            StringBuilder sb = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') { sb.append(c); i++; }
                else break;
            }
            if (sb.length() >= 16) return sb.toString();
            from = at + 6;
        }
    }

    private static String readHeadQuietly(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[262144];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static String rootCat(String path) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "head -c 262144 " + path).redirectErrorStream(false).start();
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
