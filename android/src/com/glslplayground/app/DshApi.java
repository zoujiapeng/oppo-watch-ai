package com.glslplayground.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DSH 前端缺的那几件事，全部走**已经存在的** RPC，不碰 DSH 本体：
 *
 *   session/list · session/create · session/selectModel · session/prompt
 *   agentPresets/list · agentPresets/select · permissionPresets/catalog
 *   commands/execute（`/permission <preset>`）· session/modelCatalog
 *   speech/transcribe · fileUploads/upload · directoryPicker/list
 *
 * 这里的方法都是**同步**的（跑在调用方自己的后台线程），异常由调用方接住转成 UI 提示。
 * 认证/重试逻辑复用 {@link DshHttpTransport#call}，因此和球、审批面板共用同一套 cookie。
 *
 * ⚠️ 本部署把内容检索索引关掉了（`openAt: never`）：`session/search` 会返回
 * `session search is disabled`。所以「搜索会话」走**前端同款**做法 ——
 * 拉 `session/list` 在本地按标题过滤（DSH 会话列表那个放大镜 `bhn1Oq_searchButton`
 * 本身就是本地过滤，不是内容检索）。
 */
final class DshApi {

    private static final String TAG = "DshApi";

    /** 下拉一项（Agent 预设 / 权限档）：id + 展示名 + 说明。 */
    static final class Opt {
        final String id, name, desc;
        Opt(String id, String name, String desc) {
            this.id = id;
            this.name = name == null || name.trim().isEmpty() ? id : name;
            this.desc = desc == null ? "" : desc;
        }
        @Override public String toString() { return name; }
    }

    /** 会话列表一行（= `session/list` 的 SessionSummary 我们关心的那几个字段）。 */
    static final class SessionRow {
        final String id, title, cwd;
        final long updatedAt;
        final boolean running;
        SessionRow(String id, String title, String cwd, long updatedAt, boolean running) {
            this.id = id;
            this.title = title == null || title.trim().isEmpty() ? "（未命名会话）" : title;
            this.cwd = cwd == null ? "" : cwd;
            this.updatedAt = updatedAt;
            this.running = running;
        }
        /** 展示在列表里：标题 + 目录尾名（和 DSH 侧栏一致的信息密度）。 */
        String subtitle() {
            if (cwd == null || cwd.isEmpty()) return "";
            int i = cwd.lastIndexOf('/');
            return i >= 0 && i + 1 < cwd.length() ? cwd.substring(i + 1) : cwd;
        }
    }

    /** 模型目录里的一个模型（含推理档）。 */
    static final class Model {
        final String id, name, desc, provider;
        final List<Opt> efforts = new ArrayList<>();
        String defaultEffort;
        Model(String provider, String id, String name, String desc, String defaultEffort) {
            this.provider = provider;
            this.id = id;
            this.name = name == null ? id : name;
            this.desc = desc == null ? "" : desc;
            this.defaultEffort = defaultEffort;
        }
    }

    /** 一个 provider 分组。 */
    static final class ModelGroup {
        final String id, name;
        final List<Model> models = new ArrayList<>();
        ModelGroup(String id, String name) { this.id = id; this.name = name; }
    }

    /** `session/modelCatalog` 的投影。 */
    static final class Catalog {
        String defProvider = "", defModel = "", defEffort = "";
        final List<ModelGroup> groups = new ArrayList<>();
        Model find(String provider, String model) {
            for (ModelGroup g : groups) {
                if (!g.id.equals(provider)) continue;
                for (Model m : g.models) if (m.id.equals(model)) return m;
            }
            return null;
        }
        Model first() {
            for (ModelGroup g : groups) if (!g.models.isEmpty()) return g.models.get(0);
            return null;
        }
    }

    // ── 单例 ────────────────────────────────────────────────────────────

    private static DshApi instance;
    private final Context app;
    private final DshHttpTransport transport;
    private final Object lock = new Object();

    private DshApi(Context c) {
        app = c.getApplicationContext();
        String endpoint = app.getSharedPreferences("siri_assistant", Context.MODE_PRIVATE)
                .getString("endpoint", DshHttpTransport.DEFAULT_ENDPOINT);
        transport = new DshHttpTransport(app, endpoint);
    }

    static synchronized DshApi get(Context c) {
        if (instance == null) instance = new DshApi(c);
        return instance;
    }

    /** 设置页改了 DSH 地址：换一条传输（下一次调用自动重新认证）。 */
    static synchronized void reset() { instance = null; }

    /** 给内置 WebView（DSH 设置页）用的签名 cookie：和普通 RPC 是同一份。 */
    String cookie() throws Exception {
        synchronized (lock) { return transport.authCookie(); }
    }

    /** 设置页 WebView 要用的基地址。 */
    String endpoint() { return transport.endpoint(); }

    private JSONObject call(String endpoint, JSONObject args) throws Exception {
        synchronized (lock) { return transport.call(endpoint, args); }
    }

    // ── 会话 ────────────────────────────────────────────────────────────

    private static JSONObject argsOf(JSONObject request) throws Exception {
        JSONObject args = new JSONObject();
        args.put("request", request);
        return args;
    }

    /** `session/list` → 新到旧（DSH 自己就是这个顺序，这里再排一次保险）。 */
    List<SessionRow> sessions() throws Exception {
        JSONObject args = new JSONObject();
        args.put("_request", new JSONObject());          // 形参名就是 _request
        JSONObject value = call("session/list", args);
        List<SessionRow> out = new ArrayList<>();
        JSONArray items = value == null ? null : value.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            if ("subagent".equals(it.optString("origin"))) continue;   // 子智能体不进列表
            String title = "";
            JSONObject proj = it.optJSONObject("projections");
            if (proj != null) {
                JSONObject vals = proj.optJSONObject("values");
                if (vals != null) title = vals.optString("title", "");
            }
            out.add(new SessionRow(it.optString("sessionId"), title, it.optString("cwd"),
                    it.optLong("updatedAt", 0), it.optBoolean("running", false)));
        }
        Collections.sort(out, new Comparator<SessionRow>() {
            @Override public int compare(SessionRow a, SessionRow b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    /**
     * 「搜索会话」= DSH 侧栏那个放大镜的行为：**本地**按标题过滤（标题为空时也匹配目录）。
     * 内容检索（`session/search`）在本部署被关了，这里不碰它。
     */
    List<SessionRow> searchSessions(String query) throws Exception {
        List<SessionRow> all = sessions();
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return all;
        List<SessionRow> hit = new ArrayList<>();
        for (SessionRow r : all) {
            if (r.title.toLowerCase().contains(q) || r.cwd.toLowerCase().contains(q)) hit.add(r);
        }
        return hit;
    }

    /** 工作目录（= 最近用过的 cwd，按会话更新时间去重，新的在前）。 */
    List<String> recentDirs() throws Exception {
        LinkedHashSet<String> dirs = new LinkedHashSet<>();
        for (SessionRow r : sessions()) {
            if (r.cwd != null && !r.cwd.isEmpty()) dirs.add(r.cwd);
        }
        return new ArrayList<>(dirs);
    }

    /** 某个目录下的会话（三级菜单）。 */
    List<SessionRow> sessionsIn(String dir) throws Exception {
        List<SessionRow> out = new ArrayList<>();
        for (SessionRow r : sessions()) {
            if (dir != null && dir.equals(r.cwd)) out.add(r);
        }
        return out;
    }

    /** 这条会话还在不在（电源键「回到上一次回话」前先问一句：删掉了就退回新建）。 */
    boolean sessionExists(String sessionId) throws Exception {
        if (sessionId == null || sessionId.isEmpty()) return false;
        for (SessionRow r : sessions()) if (sessionId.equals(r.id)) return true;
        return false;
    }

    /** 正在跑的会话（`running=true`），新到旧 —— 状态 3 左下角那张「运行中」卡片用它。 */
    List<SessionRow> runningSessions() throws Exception {
        List<SessionRow> out = new ArrayList<>();
        for (SessionRow r : sessions()) if (r.running) out.add(r);
        return out;
    }

    /**
     * 停掉一条会话**当前正在跑的这一轮**：`session/cancel` `{request:{sessionId}}`。
     *
     * 语义（spec §session/prompt 脚注）：只中止这一轮，会话本身还在，之后还能继续发。
     * 会话已经结束 / 不存在时 DSH 也回 ok（幂等），所以调用方不需要先查 running。
     */
    boolean cancelSession(String sessionId) throws Exception {
        if (sessionId == null || sessionId.isEmpty()) return false;
        JSONObject req = new JSONObject();
        req.put("sessionId", sessionId);
        JSONObject value = call("session/cancel", argsOf(req));
        Log.i(TAG, "session/cancel " + sessionId + " -> " + value);
        return true;
    }

    // ── 会话内容：进对话卡要看到什么 ────────────────────────────────────

    /** 会话当前的持久游标（`projections.asOfSeq`）；拿不到就是 0。 */
    private long cursorOf(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return 0;
        try {
            JSONObject listArgs = new JSONObject();
            listArgs.put("_request", new JSONObject());
            JSONObject value = call("session/list", listArgs);
            JSONArray items = value == null ? null : value.optJSONArray("items");
            if (items == null) return 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || !sessionId.equals(it.optString("sessionId"))) continue;
                JSONObject proj = it.optJSONObject("projections");
                if (proj != null) return proj.optLong("asOfSeq", 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "会话游标读取失败：" + t);
        }
        return 0;
    }

    /** `session/page` 的 records（最近 maxMessages 条事件，时间正序）；会话是空的就返回 null。 */
    private JSONArray recordsOf(String sessionId, int maxMessages) throws Exception {
        long cursor = cursorOf(sessionId);
        if (cursor <= 0) return null;
        return pageRecords(sessionId, cursor, maxMessages);
    }

    /**
     * 一条 `user/message` / `assistant/message` 的正文块。
     *
     * ⚠️ 两个事件的字段位置**不一样**（2026-11-09 对着真机 DSH 一条条核过）：
     *   · `user/message`      → `data.content[]`
     *   · `assistant/message` → `data.message.content[]`
     * 以前统一只读 `data.message.content`，于是**用户说的话一条都读不出来**
     * （对话卡里永远没有「你：…」），只剩最后一条 assistant 文本。
     */
    private static JSONArray contentOf(JSONObject ev) {
        JSONObject data = ev == null ? null : ev.optJSONObject("data");
        if (data == null) return null;
        JSONArray c = data.optJSONArray("content");            // user/message
        if (c != null) return c;
        JSONObject msg = data.optJSONObject("message");        // assistant/message
        return msg == null ? null : msg.optJSONArray("content");
    }

    /** 正文（只要 text 块；图片/文件折成一行标记，不渲染）。 */
    private static String textOf(JSONArray content) {
        if (content == null || content.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        int files = 0;
        for (int k = 0; k < content.length(); k++) {
            JSONObject b = content.optJSONObject(k);
            if (b == null) continue;
            String type = b.optString("type");
            if ("text".equals(type)) {
                String t = b.optString("text", "");
                if (t != null && !t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            } else if ("image".equals(type) || "file".equals(type) || "attachment".equals(type)) {
                files++;
            }
        }
        if (files > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("［附件 ×").append(files).append("］");
        }
        return sb.toString().trim();
    }

    /**
     * DSH 每一拍都会往会话里塞一条注入上下文（`Current runtime context…` / `[pj-snapshot …]`）。
     * 那是给模型看的，不是"我说的话" —— 对话卡里显示出来就是一大段噪音，跳过。
     */
    private static boolean isInjectedContext(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("Current runtime context")
                || t.startsWith("[pj-snapshot")
                || t.startsWith("[operator · pj");
    }

    /** 会话最后一条助手回答（"进入这个对话"时的兜底）。 */
    String lastAnswer(String sessionId) throws Exception {
        JSONArray records = recordsOf(sessionId, 30);
        if (records == null) return "";
        String text = "";
        for (int i = 0; i < records.length(); i++) {
            JSONObject rec = records.optJSONObject(i);
            JSONObject ev = rec == null ? null : rec.optJSONObject("event");
            if (ev == null || !"assistant/message".equals(ev.optString("type"))) continue;
            String t = textOf(contentOf(ev));
            if (!t.isEmpty()) text = t;
        }
        return text;
    }

    /** 会话最后一条**用户**消息（"我发过什么"）。 */
    String lastUserMessage(String sessionId) throws Exception {
        JSONArray records = recordsOf(sessionId, 30);
        if (records == null) return "";
        String text = "";
        for (int i = 0; i < records.length(); i++) {
            JSONObject rec = records.optJSONObject(i);
            JSONObject ev = rec == null ? null : rec.optJSONObject("event");
            if (ev == null || !"user/message".equals(ev.optString("type"))) continue;
            String t = textOf(contentOf(ev));
            if (isInjectedContext(t)) continue;          // 注入的上下文不算"我说的话"
            if (!t.isEmpty()) text = t;
        }
        return text;
    }

    /**
     * 「打开这个会话」时要显示的东西：最近几轮的**完整对话**（原始 markdown）。
     *
     * 用户口径（2026-11-09）：「回话显示有两种，一种纯文字，一种有格式的能显示表格 ——
     * 我倾向能显示表格的」。球内文字层本来就是 {@link BallMarkdownView}（标题 / 列表 /
     * 表格 / 代码块全支持），所以这里只负责把原始 markdown 拼回去，**一个字都不 strip**，
     * 表格就自己排好了。
     *
     * 结构（整段拼，太长就从**最老的**整段丢，结论永远在最后）：
     * <pre>
     * **你**
     *
     * 我发的那句
     *
     * **AI**
     *
     * 回答（原始 markdown，表格照排）
     * </pre>
     */
    /** 逐页回来的会话记录（每翻一页回调一次，字符串是"到目前为止拼好的 markdown"）。 */
    interface TranscriptProgress { void onPartial(String markdown); }

    String sessionTranscript(String sessionId, int maxMessages) throws Exception {
        return sessionTranscript(sessionId, maxMessages, null);
    }

    String sessionTranscript(String sessionId, int maxMessages, TranscriptProgress cb) throws Exception {
        long cursor = cursorOf(sessionId);
        if (cursor <= 0) return "";
        // ── 往后翻页 ────────────────────────────────────────────────────────
        // ⚠️ `maxMessages` 数的是**消息条数**，不是事件条数：一个工具密集的回合里
        //    tool call / step 事件能把"最后 60 条消息"整个吃掉（实测一条 30 步的会话
        //    最后 60 条消息里**一条正文都没有**，卡里就是"（这条会话还没有内容）"）。
        //    所以这里按 `throughSeq` 一页页往回翻，翻到"够排版用的字数"就停。
        List<Object[]> msgs = new ArrayList<>();          // {Boolean user, String text}，时间正序
        int page = 0;
        int textChars = 0;
        while (page < 4 && cursor > 0) {
            JSONArray records = pageRecords(sessionId, cursor, Math.max(8, maxMessages));
            if (records == null || records.length() == 0) break;
            List<Object[]> batch = new ArrayList<>();
            long oldest = -1;
            for (int i = 0; i < records.length(); i++) {
                JSONObject rec = records.optJSONObject(i);
                JSONObject ev = rec == null ? null : rec.optJSONObject("event");
                if (ev == null) continue;
                long seq = ev.optLong("seq", -1);
                if (seq >= 0 && (oldest < 0 || seq < oldest)) oldest = seq;
                String type = ev.optString("type");
                boolean isUser = "user/message".equals(type);
                if (!isUser && !"assistant/message".equals(type)) continue;
                String text = textOf(contentOf(ev));
                if (text.isEmpty()) continue;
                if (isUser && isInjectedContext(text)) continue;
                batch.add(new Object[]{isUser, text});
                textChars += text.length();
            }
            msgs.addAll(0, batch);                        // 这一页比手上的都老 → 插到前面
            page++;
            // 先把"手上的"抖出去：工具密集的会话要翻好几页才有正文，别让卡一直空着等
            if (cb != null && !msgs.isEmpty()) {
                try { cb.onPartial(composeTranscript(msgs)); } catch (Throwable ignored) { }
            }
            if (textChars >= 3000) break;                 // 够一段可读的对话了
            if (oldest <= 0) break;
            cursor = oldest - 1;                          // 再往前翻：读到这一页最老那条之前
        }
        if (msgs.isEmpty()) return "";
        String out = composeTranscript(msgs);
        Log.i(TAG, "sessionTranscript: " + page + " 页 / " + msgs.size() + " 条正文 → "
                + out.length() + " 字");
        return out;
    }

    /** `{user, text}` 序列 → 可渲染的 markdown（太长从**最老的**整段丢）。 */
    private static String composeTranscript(List<Object[]> msgs) {
        List<String> blocks = new ArrayList<>();          // 排版好的整段，时间正序
        String pendingUser = null;
        StringBuilder answer = new StringBuilder();
        for (Object[] m : msgs) {
            boolean isUser = (Boolean) m[0];
            String text = (String) m[1];
            if (isUser) {
                flushTurn(blocks, pendingUser, answer);
                pendingUser = text;
            } else {
                if (answer.length() > 0) answer.append("\n\n");
                answer.append(text);
            }
        }
        flushTurn(blocks, pendingUser, answer);
        // 从后往前保留：总长超了就把最老的整段扔掉（BallMarkdownView 还有自己的上限）
        int budget = 5600;
        int from = blocks.size();
        int used = 0;
        while (from > 0 && used + blocks.get(from - 1).length() <= budget) {
            from--;
            used += blocks.get(from).length();
        }
        if (from == blocks.size() && !blocks.isEmpty()) {
            // 单段就超预算：留最后这一段（截断交给渲染层），别把整张卡清空
            from = blocks.size() - 1;
        }
        StringBuilder out = new StringBuilder();
        for (int i = from; i < blocks.size(); i++) {
            if (out.length() > 0) out.append("\n\n");
            out.append(blocks.get(i));
        }
        return out.toString();
    }

    /** 一页历史：`throughSeq` 之前（含）最近 maxMessages 条消息。 */
    private JSONArray pageRecords(String sessionId, long throughSeq, int maxMessages) throws Exception {
        JSONObject req = new JSONObject();
        JSONObject addr = new JSONObject();
        addr.put("kind", "session");
        addr.put("sessionId", sessionId);
        req.put("address", addr);
        req.put("throughSeq", throughSeq);
        req.put("maxMessages", maxMessages);
        JSONObject page = call("session/page", argsOf(req));
        return page == null ? null : page.optJSONArray("records");
    }

    /** 把「这一轮我说了什么 + AI 答了什么」拼成一段；answer 用掉后清空。 */
    private static void flushTurn(List<String> blocks, String user, StringBuilder answer) {
        boolean hasUser = user != null && !user.isEmpty();
        if (!hasUser && answer.length() == 0) return;
        StringBuilder sb = new StringBuilder();
        if (hasUser) sb.append("**你**\n\n").append(user);
        if (answer.length() > 0) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("**AI**\n\n").append(answer.toString().trim());
        }
        answer.setLength(0);
        blocks.add(sb.toString());
    }

    /** 新建会话（预设在这里落库：`session/create` 的 agentPreset 只在首轮之前生效）。 */
    String createSession(String cwd, String agentPreset) throws Exception {
        JSONObject req = new JSONObject();
        if (cwd != null && !cwd.isEmpty()) req.put("cwd", cwd);
        if (agentPreset != null && !agentPreset.isEmpty()) req.put("agentPreset", agentPreset);
        JSONObject value = call("session/create", argsOf(req));
        String sid = value == null ? null : value.optString("sessionId");
        if (sid == null || sid.isEmpty()) throw new Exception("DSH 没返回新会话");
        return sid;
    }

    /** 会话切模型 + 推理等级（都进 ModelSelection；effort 省略 = 用模型默认）。 */
    void selectModel(String sessionId, String provider, String model, String effort) throws Exception {
        JSONObject req = new JSONObject();
        req.put("sessionId", sessionId);
        req.put("provider", provider);
        req.put("model", model);
        if (effort != null && !effort.isEmpty()) req.put("reasoningEffort", effort);
        call("session/selectModel", argsOf(req));
    }

    /** 会话切 Agent 预设（DSH 只允许**首轮之前**切换；已开跑的会话会原样保留）。 */
    void selectAgentPreset(String sessionId, String preset) throws Exception {
        if (preset == null || preset.isEmpty()) return;
        JSONObject args = new JSONObject();
        args.put("agentId", sessionId);
        args.put("agentPreset", preset);
        call("agentPresets/select", args);
    }

    /** 权限档 = 执行 DSH 自己的 `/permission <name>` 命令（和前端那个下拉同一条路）。 */
    void setPermission(String sessionId, String preset) throws Exception {
        if (preset == null || preset.isEmpty()) return;
        JSONObject args = new JSONObject();
        args.put("agentId", sessionId);
        args.put("line", "/permission " + preset);
        args.put("submittedAttachments", new JSONArray());
        call("commands/execute", args);
    }

    // ── 三张目录 ────────────────────────────────────────────────────────

    List<Opt> agentPresets() throws Exception {
        JSONObject value = call("agentPresets/list", new JSONObject());
        List<Opt> out = new ArrayList<>();
        JSONArray arr = value == null ? null : value.optJSONArray("presets");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            String name = o.optString("name", id);
            out.add(new Opt(id, name + (o.optBoolean("isDefault") ? " · 默认" : ""),
                    o.optString("description", "")));
        }
        return out;
    }

    List<Opt> permissionPresets() throws Exception {
        JSONObject value = call("permissionPresets/catalog", new JSONObject());
        List<Opt> out = new ArrayList<>();
        JSONArray arr = value == null ? null : value.optJSONArray("options");
        String def = value == null ? "" : value.optString("defaultPreset", "");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String v = o.optString("value");
            out.add(new Opt(v, o.optString("name", v) + (v.equals(def) ? " · 默认" : ""), ""));
        }
        return out;
    }

    Catalog modelCatalog() throws Exception {
        JSONObject value = call("session/modelCatalog", new JSONObject());
        Catalog cat = new Catalog();
        if (value == null) return cat;
        JSONObject def = value.optJSONObject("default");
        if (def != null) {
            cat.defProvider = def.optString("provider", "");
            cat.defModel = def.optString("model", "");
            cat.defEffort = def.optString("reasoningEffort", "");
        }
        JSONArray groups = value.optJSONArray("groups");
        if (groups == null) return cat;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            ModelGroup mg = new ModelGroup(g.optString("id"), g.optString("name", g.optString("id")));
            JSONArray ms = g.optJSONArray("models");
            if (ms != null) {
                for (int j = 0; j < ms.length(); j++) {
                    JSONObject m = ms.optJSONObject(j);
                    if (m == null) continue;
                    String provider = m.optString("provider", mg.id);
                    Model model = new Model(provider, m.optString("id"), m.optString("name"),
                            m.optString("description", ""), "");
                    JSONObject rz = m.optJSONObject("reasoning");
                    if (rz != null) {
                        JSONArray es = rz.optJSONArray("efforts");
                        if (es != null) {
                            for (int k = 0; k < es.length(); k++) {
                                JSONObject e = es.optJSONObject(k);
                                if (e == null) continue;
                                model.efforts.add(new Opt(e.optString("id"), e.optString("name"),
                                        e.optString("description", "")));
                            }
                        }
                        if (model.defaultEffort == null || model.defaultEffort.isEmpty()) {
                            model.defaultEffort = rz.optString("defaultEffort", "");
                        }
                    }
                    mg.models.add(model);
                }
            }
            cat.groups.add(mg);
        }
        return cat;
    }

    // ── 语音转文字 / 附件 / 发送 ────────────────────────────────────────

    /** `speech/transcribe`：16 kHz mono PCM16 WAV → 文本（空串 = 没听清）。 */
    String transcribe(byte[] wav) throws Exception {
        if (wav == null || wav.length <= 44) return "";
        JSONObject req = new JSONObject();
        req.put("audioBase64", Base64.encodeToString(wav, Base64.NO_WRAP));   // 必须 canonical
        JSONObject value = call("speech/transcribe", argsOf(req));
        return value == null ? "" : value.optString("text", "").trim();
    }

    /** 附件上传（`uV2eYG_add` 那条路）：拿到 receiptId 才能塞进 prompt content。 */
    String upload(String sessionId, String name, byte[] data) throws Exception {
        if (sessionId == null || sessionId.isEmpty()) throw new Exception("没有会话，无法上传附件");
        JSONObject args = new JSONObject();
        args.put("agentId", sessionId);
        JSONObject req = new JSONObject();
        req.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
        if (name != null && !name.isEmpty()) req.put("name", name);
        args.put("request", req);
        JSONObject value = call("fileUploads/upload", args);
        String rid = value == null ? null : value.optString("receiptId");
        if (rid == null || rid.isEmpty()) throw new Exception("DSH 没返回附件回执");
        return rid;
    }

    /** 直接发一条（带可选附件回执）。流式回答由 {@link DshHttpTransport#askContent} 负责。 */
    void prompt(String sessionId, String text, List<String> receipts) throws Exception {
        JSONObject req = new JSONObject();
        req.put("requestId", "android-" + System.currentTimeMillis());
        req.put("sessionId", sessionId);
        req.put("mode", "queue");
        JSONArray content = new JSONArray();
        if (text != null && !text.trim().isEmpty()) {
            content.put(new JSONObject().put("type", "text").put("text", text));
        }
        if (receipts != null) {
            for (String r : receipts) {
                if (r != null && !r.isEmpty()) {
                    content.put(new JSONObject().put("type", "file").put("receiptId", r));
                }
            }
        }
        if (content.length() == 0) throw new Exception("没有内容可发");
        req.put("content", content);
        call("session/prompt", argsOf(req));
    }

    /** `directoryPicker/list`：工作目录浏览（只留目录，文件不列）。 */
    static final class DirList {
        final String path;
        final List<String> dirs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        DirList(String path) { this.path = path; }
    }

    DirList listDir(String path) throws Exception {
        JSONObject args = new JSONObject();
        if (path != null && !path.isEmpty()) args.put("path", path);
        JSONObject value = call("directoryPicker/list", args);
        DirList out = new DirList(value == null ? path : value.optString("path", path));
        JSONArray arr = value == null ? null : value.optJSONArray("entries");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                String p = e.optString("path");
                if (p == null || p.isEmpty()) continue;
                // 只列目录：DSH 的 entries 里目录没有扩展名/类型字段，按名字尾判断不可靠，
                // 用 directoryPicker 自己给的 kind（有就有，没有就当目录）。
                String kind = e.optString("kind", e.optString("type", ""));
                if (!kind.isEmpty() && !"directory".equals(kind)) continue;
                out.dirs.add(p);
                out.names.add(e.optString("name", p.substring(p.lastIndexOf('/') + 1)));
            }
        }
        return out;
    }

    static String log(String s) { Log.i(TAG, s); return s; }
}
