package com.glslplayground.app;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 动画总控 —— 一套「动画库 + 状态槽」的统一注册表。
 *
 * 为什么要这个类（2026-11-08 用户要求）：
 *   加胶囊加载动画的那几版把「思考中动画」的入口拆成了三处互斥的开关
 *   （think_style / think_loader / float_fluid_style），谁最后写谁赢 ——
 *   于是原本的 Siri 波和 dots 被覆盖掉了，而且只有「思考中」这一个状态能换动画。
 *
 * 现在的模型只有两层，都是同一级别：
 *
 *   ① 动画库（animations）：每一个动画都是一条独立记录，参数是**每个动画自己的**，
 *      互不干扰。目前 12 条：关闭 / Siri 波 / 流体圆点 dots / 连续流体发光 / 胶囊加载 ×9。
 *   ② 状态槽（slots）：每一个状态（空闲、麦克风刚开、思考、执行、过程回复、最终回复、
 *      审批卡片、搜索胶囊、六区）各自绑一个动画 id。状态一变，服务就按槽里绑的动画去渲染。
 *
 * 存储（SharedPreferences "anim_scene"）：
 *   master               统一调度总开关（关掉 = 退回旧行为）
 *   bind.<slot>          该状态绑的动画 id
 *   p.<anim>.<key>       动画参数（整数，单位见 catalog 的 fmt）
 *
 * 旧值迁移：首次启动把 watch prefs 里那堆 float_orb_* / float_fluid_* / think_* 读进来，
 * 所以用户已经调好的观感不会丢。
 */
public final class AnimScene {

    public static final String PREFS = "anim_scene";

    // ── 状态槽 id ────────────────────────────────────────────────────────────
    public static final String SLOT_IDLE = "idle";
    public static final String SLOT_LISTEN = "listen";
    public static final String SLOT_THINK = "thinking";
    public static final String SLOT_EXEC = "executing";
    public static final String SLOT_STREAM = "reply_stream";
    public static final String SLOT_FINAL = "reply_final";
    public static final String SLOT_APPROVAL = "approval";
    public static final String SLOT_SEARCH = "search";
    public static final String SLOT_ZONES = "zones";

    // ── 动画 id ──────────────────────────────────────────────────────────────
    public static final String A_OFF = "off";
    public static final String A_WAVE = "wave";
    public static final String A_DOTS = "dots";
    public static final String A_FLUID = "fluid";

    private AnimScene() { }

    /** 胶囊加载动画 id：cap1..cap9。 */
    public static String cap(int n) { return "cap" + n; }

    /** 该动画是不是胶囊加载（1..9 种点阵）。 */
    public static boolean isCap(String anim) {
        return anim != null && anim.length() == 4 && anim.startsWith("cap")
                && anim.charAt(3) >= '1' && anim.charAt(3) <= '9';
    }

    public static int capIndex(String anim) {
        return isCap(anim) ? anim.charAt(3) - '0' : 0;
    }

    // ══════════════════════════ 动画库（唯一真相） ══════════════════════════

    private static JSONObject param(String k, String label, int min, int max, int step,
                                   int def, String fmt) throws Exception {
        JSONObject p = new JSONObject();
        p.put("k", k);
        p.put("label", label);
        p.put("min", min);
        p.put("max", max);
        p.put("step", step);
        p.put("def", def);
        p.put("fmt", fmt);
        return p;
    }

    private static JSONObject anim(String id, String kind, String name, String desc,
                                   JSONObject[] params) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("kind", kind);
        o.put("name", name);
        o.put("desc", desc);
        JSONArray arr = new JSONArray();
        for (JSONObject p : params) arr.put(p);
        o.put("params", arr);
        return o;
    }

    /** 动画库：顺序就是设置页里从上到下的顺序（同一个级别，谁也不藏在子菜单里）。 */
    public static JSONArray animations() {
        JSONArray out = new JSONArray();
        try {
            out.put(anim(A_OFF, "none", "关闭", "这个状态不出动画（只留水晶球本身）", new JSONObject[0]));

            out.put(anim(A_WAVE, "wave", "Siri 波",
                    "原版那道波：贴着球底 / 黑区起伏，最终回复时会沉进玻璃里被折射",
                    new JSONObject[]{
                            param("amp", "波动幅度", 20, 400, 5, 190, "pct"),
                            param("scale", "波长缩放", 30, 200, 5, 85, "pct"),
                            param("drop", "沉底偏移", 0, 300, 5, 78, "px"),
                            param("speed", "速度", 20, 300, 5, 100, "pct"),
                            // 0 = 和 Liquid Glass 页面那条波完全一样的画法（默认）；
                            // 1 = 旧版"最终回复"那种：整道波沉到球底下方、只露波顶一截
                            param("inglass", "沉入玻璃 0普通 1沉底", 0, 1, 1, 0, "mode")
                    }));

            out.put(anim(A_DOTS, "dots", "流体圆点 dots",
                    "原版那一圈会呼吸的圆点（思考中的默认动画）",
                    new JSONObject[]{
                            param("radius", "环半径", 30, 300, 5, 90, "px"),
                            param("gain", "亮度", 10, 400, 5, 110, "pct"),
                            param("rim", "边缘辉光", 0, 400, 5, 130, "pct"),
                            param("offset", "上下偏移", -60, 60, 1, -3, "ny")
                    }));

            out.put(anim(A_FLUID, "fluid", "连续流体发光",
                    "和 dots 同一套光核，但是一团在水里收缩 / 扩散的连续光",
                    new JSONObject[]{
                            param("colorMode", "配色 0纯白 1单色 2混色", 0, 2, 1, 0, "mode"),
                            param("hue", "单色色相", 0, 359, 1, 190, "deg"),
                            param("hot", "刺眼程度", 0, 200, 5, 100, "pct"),
                            param("dash", "断线密度", 0, 2400, 50, 0, "pct2"),
                            param("ribs", "丝线密度", 4, 16, 1, 12, "count"),
                            param("size", "体量", 10, 400, 5, 100, "pct"),
                            param("gain", "亮度", 20, 300, 5, 120, "pct")
                    }));

            String[] names = {"脉冲", "珠链", "螺旋", "环轨", "星座", "彗尾", "磁吸", "跳环", "涟漪"};
            String[] descs = {
                    "一颗核在胶囊里一呼一吸，外圈光晕跟着涨",
                    "一串珠子沿胶囊跑道跑圈，前后带拖尾",
                    "点沿胶囊内壁盘旋，像 DNA / 螺旋楼梯",
                    "几颗点绕胶囊长轴做椭圆轨道",
                    "固定的星座点位，亮度忽明忽暗地闪",
                    "彗星一样沿跑道冲，拖尾比本体长",
                    "点被胶囊内壁「磁吸」住，靠边时被拉扁",
                    "两点在胶囊两端来回跳，中间拉一道光",
                    "从中心一圈一圈荡开的水波涟漪"
            };
            for (int i = 1; i <= 9; i++) {
                out.put(anim(cap(i), "cap", "胶囊加载 " + i + " · " + names[i - 1], descs[i - 1],
                        new JSONObject[]{
                                param("speed", "速度", 20, 250, 5, 100, "pct"),
                                param("scale", "点阵大小", 20, 800, 20, 100, "pct"),
                                param("dot", "点的大小", 20, 600, 10, 100, "pct")
                        }));
            }
        } catch (Exception e) {
            // JSONObject 在这里不会抛；真抛了就是内存问题，返回空表让上层退回旧行为
        }
        return out;
    }

    // ══════════════════════════ 状态槽（唯一入口） ══════════════════════════

    private static JSONObject slot(String id, String name, String desc, String def,
                                   boolean ballOnly, String note) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("desc", desc);
        o.put("def", def);
        o.put("ballOnly", ballOnly);   // true = 这个界面由自己的玻璃层绘制
        o.put("note", note);
        return o;
    }

    public static JSONArray slots() {
        JSONArray out = new JSONArray();
        try {
            out.put(slot(SLOT_IDLE, "空闲 / 待机", "没有任务时的圆球", A_WAVE, false,
                    ""));
            out.put(slot(SLOT_LISTEN, "麦克风使用中", "刚开始按住说话 / 语音收音这一段", A_WAVE, false,
                    ""));
            out.put(slot(SLOT_THINK, "思考中", "模型在生成、球里还没有字", A_DOTS, false,
                    ""));
            out.put(slot(SLOT_EXEC, "执行中", "DSH 在跑命令 / 工具调用（球里通常没字）", A_DOTS, false,
                    ""));
            out.put(slot(SLOT_STREAM, "过程中回复", "流式出字、这一轮还没结束", A_WAVE, false,
                    ""));
            out.put(slot(SLOT_FINAL, "最终回复", "任务完成、这一轮收尾", A_WAVE, false,
                    "默认和 Liquid Glass 是同一条波；想要旧版「沉进球底只露一截」，在动画库的 Siri 波里把「沉入玻璃」调成 1"));
            out.put(slot(SLOT_APPROVAL, "审批 / 提问卡片", "DSH 要你点头时弹出来的卡片", A_OFF, false,
                    ""));
            out.put(slot(SLOT_SEARCH, "搜索胶囊", "双指长按打开的输入胶囊", A_OFF, true,
                    "这个界面由搜索层玻璃自己画：只能叠「胶囊加载」类动画，其余动画在这里不显示"));
            out.put(slot(SLOT_ZONES, "六区 / 玻璃菜单", "状态三那圈圆区与面板", A_OFF, true,
                    "这个界面由六区玻璃自己画：只能叠「胶囊加载」类动画，其余动画在这里不显示"));
        } catch (Exception ignored) { }
        return out;
    }

    /** 设置页要的整份目录：动画库 + 状态槽，一次取完。 */
    public static String catalogJson(SharedPreferences sp) {
        try {
            JSONObject o = new JSONObject();
            o.put("animations", animations());
            o.put("slots", slots());
            o.put("bindings", bindingsJson(sp));
            return o.toString();
        } catch (Exception e) {
            return "{\"animations\":[],\"slots\":[],\"bindings\":{}}";
        }
    }

    // ══════════════════════════ 存储 ══════════════════════════

    public static boolean master(SharedPreferences sp) {
        return sp.getBoolean("master", true);
    }

    public static void setMaster(SharedPreferences sp, boolean on) {
        sp.edit().putBoolean("master", on).apply();
    }

    public static String binding(SharedPreferences sp, String slot) {
        String def = defaultFor(slot);
        return sp.getString("bind." + slot, def);
    }

    public static String defaultFor(String slot) {
        JSONArray arr = slots();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && slot.equals(o.optString("id"))) return o.optString("def", A_OFF);
        }
        return A_OFF;
    }

    public static void setBinding(SharedPreferences sp, String slot, String anim) {
        if (slot == null || anim == null) return;
        sp.edit().putString("bind." + slot, anim).apply();
    }

    /** 这个状态槽是否被限定成「只能叠胶囊加载」（搜索胶囊 / 六区）。 */
    public static boolean ballOnly(String slot) {
        return SLOT_SEARCH.equals(slot) || SLOT_ZONES.equals(slot);
    }

    /** 该槽可选的动画 id 列表（搜索/六区只给 off + cap1..9）。 */
    public static JSONArray allowedFor(String slot) {
        JSONArray out = new JSONArray();
        if (!ballOnly(slot)) {
            out.put(A_OFF); out.put(A_WAVE); out.put(A_DOTS); out.put(A_FLUID);
        } else {
            out.put(A_OFF);
        }
        for (int i = 1; i <= 9; i++) out.put(cap(i));
        return out;
    }

    // ── 每个动画自己的参数 ──────────────────────────────────────────────────

    /** 取某动画的全部参数（缺省值补齐）。 */
    public static JSONObject params(SharedPreferences sp, String anim) {
        JSONObject out = new JSONObject();
        JSONObject def = animDef(anim);
        if (def == null) return out;
        JSONArray ps = def.optJSONArray("params");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            String k = p.optString("k");
            try {
                out.put(k, sp.getFloat("p." + anim + "." + k, p.optInt("def", 0)));
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static float param(SharedPreferences sp, String anim, String key) {
        JSONObject def = animDef(anim);
        int d = 0;
        JSONArray ps = def == null ? null : def.optJSONArray("params");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && key.equals(p.optString("k"))) { d = p.optInt("def", 0); break; }
        }
        return sp.getFloat("p." + anim + "." + key, d);
    }

    public static void setParam(SharedPreferences sp, String anim, String key, float v) {
        if (anim == null || key == null) return;
        sp.edit().putFloat("p." + anim + "." + key, v).apply();
    }

    /** 每个动画的参数总览（设置页一次画完所有卡片）。 */
    public static JSONObject allParams(SharedPreferences sp) {
        JSONObject o = new JSONObject();
        JSONArray arr = animations();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String id = a.optString("id");
            try { o.put(id, params(sp, id)); } catch (Exception ignored) { }
        }
        return o;
    }

    private static JSONObject animDef(String anim) {
        JSONArray arr = animations();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a != null && anim != null && anim.equals(a.optString("id"))) return a;
        }
        return null;
    }

    /** 状态槽 → 动画 的整张表。 */
    public static JSONObject bindingsJson(SharedPreferences sp) {
        JSONObject o = new JSONObject();
        JSONArray arr = slots();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id");
            try { o.put(id, binding(sp, id)); } catch (Exception ignored) { }
        }
        return o;
    }

    /** 某个动画被哪些状态用着（设置页卡片上的「用于 N 个状态」）。 */
    public static String usedBy(SharedPreferences sp, String anim) {
        StringBuilder sb = new StringBuilder();
        JSONArray arr = slots();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id");
            if (anim.equals(binding(sp, id))) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(s.optString("name", id));
            }
        }
        return sb.toString();
    }

    // ══════════════════════════ 旧值迁移 ══════════════════════════

    /**
     * 第一次跑新逻辑时，把老三套开关里的值搬进来（只搬一次）。
     *
     * 迁移的优先级：胶囊加载 > 原版三选一（Siri 波 / dots / 流体发光）。
     * 这样「上一版明明选了胶囊加载，更新后突然变回 dots」不会发生；
     * 同时没碰过胶囊加载的人拿到的还是原来的 Siri 波 / dots。
     */
    public static void migrateIfNeeded(SharedPreferences scene, SharedPreferences watch) {
        if (scene.getBoolean("migrated", false)) return;
        SharedPreferences.Editor e = scene.edit();
        try {
            if (watch != null) {
                e.putFloat("p.wave.drop", watch.getInt("float_wave_drop", 78));
                // ★ 波的幅度/波长一直存在 float_params 里（液态玻璃页那两个滑杆）。
                //   不搬过来的话，新逻辑会用目录默认 1.90/0.85 —— 用户调过的手感就丢了，
                //   表现就是「Siri 波变得不一样了」。
                try {
                    String fp = watch.getString("float_params", null);
                    if (fp != null) {
                        org.json.JSONObject o = new org.json.JSONObject(fp);
                        if (o.has("waveAmp")) e.putFloat("p.wave.amp", (float) (o.optDouble("waveAmp", 1.9) * 100));
                        if (o.has("waveScale")) e.putFloat("p.wave.scale", (float) (o.optDouble("waveScale", 0.85) * 100));
                    }
                } catch (Throwable ignored) { }
                e.putFloat("p.dots.radius", watch.getInt("float_orb_radius", 90));
                e.putFloat("p.dots.gain", watch.getInt("float_orb_gain", 110));
                e.putFloat("p.dots.rim", watch.getInt("float_orb_rim", 130));
                e.putFloat("p.fluid.colorMode", watch.getInt("float_fluid_color_mode", 0));
                e.putFloat("p.fluid.hue", watch.getInt("float_fluid_hue", 190));
                e.putFloat("p.fluid.hot", watch.getInt("float_fluid_hot", 100));
                e.putFloat("p.fluid.dash", watch.getInt("float_fluid_dash", 0));
                e.putFloat("p.fluid.ribs", watch.getInt("float_fluid_ribs", 12));
                e.putFloat("p.fluid.size", watch.getInt("float_fluid_size", 100));
                e.putFloat("p.fluid.gain", watch.getInt("float_fluid_gain", 120));
                int capSpeed = Math.round(watch.getFloat("think_loader_speed", 1f) * 100f);
                int capScale = Math.round(watch.getFloat("think_loader_scale", 1f) * 100f);
                int capDot = Math.round(watch.getFloat("think_loader_dot", 1f) * 100f);
                for (int i = 1; i <= 9; i++) {
                    e.putFloat("p.cap" + i + ".speed", capSpeed);
                    e.putFloat("p.cap" + i + ".scale", capScale);
                    e.putFloat("p.cap" + i + ".dot", capDot);
                }

                String think = defaultFor(SLOT_THINK);          // dots
                int loader = watch.getInt("think_loader", 0);
                int style = watch.getInt("think_style", 0);
                if (loader >= 1 && loader <= 9) {
                    think = cap(loader);
                } else if (style >= 1 && style <= 3) {
                    think = style == 1 ? A_WAVE : (style == 2 ? A_DOTS : A_FLUID);
                } else if (watch.getInt("float_fluid_style", 0) == 1) {
                    think = A_FLUID;
                }
                e.putString("bind." + SLOT_THINK, think);
                // 执行中和思考中原本是同一个动画（工具调用期间也是那圈点）→ 跟着搬
                e.putString("bind." + SLOT_EXEC, think);
                e.putString("bind." + SLOT_LISTEN, A_WAVE);
                e.putString("bind." + SLOT_IDLE, A_WAVE);
                e.putString("bind." + SLOT_STREAM, A_WAVE);
                e.putString("bind." + SLOT_FINAL, A_WAVE);
                e.putString("bind." + SLOT_APPROVAL, A_OFF);
                e.putString("bind." + SLOT_SEARCH, A_OFF);
                e.putString("bind." + SLOT_ZONES, A_OFF);
            }
        } catch (Throwable ignored) { }
        e.putBoolean("migrated", true);
        e.putBoolean("master", true);
        e.apply();
    }

    /** 恢复出厂：参数回到目录里的默认值，状态绑定回到默认动画。 */
    public static void reset(SharedPreferences scene) {
        SharedPreferences.Editor e = scene.edit();
        e.clear();
        e.putBoolean("migrated", true);
        e.putBoolean("master", true);
        e.apply();
    }

    /** 调试/日志用：一行摘要。 */
    public static String summary(SharedPreferences sp) {
        return String.format(Locale.US, "master=%s idle=%s listen=%s think=%s exec=%s stream=%s final=%s approval=%s",
                master(sp), binding(sp, SLOT_IDLE), binding(sp, SLOT_LISTEN), binding(sp, SLOT_THINK),
                binding(sp, SLOT_EXEC), binding(sp, SLOT_STREAM), binding(sp, SLOT_FINAL),
                binding(sp, SLOT_APPROVAL));
    }
}
