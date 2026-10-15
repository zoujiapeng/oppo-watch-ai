package com.glslplayground.app;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 球内文字的**原生**渲染器：把一段 markdown 变成一棵 View 树（替代原来自写的 WebView 方案）。
 *
 * <p>为什么换掉 WebView：用户实测「有球无字」+ 一次进程 native 崩溃，而这类问题
 * 在**悬浮窗**里的 WebView 上极难自证（截屏又被 SKIP_SCREENSHOT 排除，看不到）。
 * 换成原生 View 之后：字号/高度都能在 Java 里直接量、可见性肉眼可验、不会崩。
 *
 * <p>支持的 markdown（够 DSH 回复用）：
 * 标题 · **逐行对齐的表格** · 有序/无序列表（按缩进做缩进与项目符号）· 引用 · 代码块 ·
 * 分割线 · 行内粗体/斜体/行内代码（用 {@code SpannableString} 实现）。
 * 图片不加载、链接不生成可点区域（球里点不了，不如老实当文字）。
 *
 * <p>表格的做法：**等宽列 = 每列一个定宽 TextView**，列宽按各列最长内容估出来再夹到总宽内，
 * 放不下就按比例压缩（宁可换行，也不溢出玻璃）。
 */
final class BallMarkdownView {

    private BallMarkdownView() { }

    /** 构建结果：整棵内容视图（放进 ScrollView 里）。 */
    static final class Result {
        View view;
        /** 内容是否被"太长"截断过（日志/调试用）。 */
        boolean truncated;
    }

    /** 正文之外的字号/间距，全部由调用方按像素给（球内是像素坐标系）。 */
    static final class Style {
        float bodyPx;        // 正文字号(px)
        float headPx;        // 标题基准字号(px)（h1 = 1.3×，逐级递减）
        float codePx;        // 代码字体
        int padH;            // 左右内边距
        int padTop;          // 顶部内边距（= 文字距球顶）
        int padBottom;
        int gap;             // 段落间距
        int fg = 0xFFF4F5F7;
        int fgDim = 0xFFC9CDD4;
        int line = 0x59FFFFFF;
        int codeBg = 0x26FFFFFF;

        int px(float v) { return Math.round(v); }
    }

    // DSH 一条回复最多渲染这么多字符（球是"当前这句话"的显示器，不是日志窗口）
    static final int MAX_CHARS = 2500;

    static Result build(Context ctx, String markdown, Style st) {
        Result r = new Result();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        col.setLayoutParams(colLp);
        col.setPadding(st.padH, st.padTop, st.padH, st.padBottom);

        String md = markdown == null ? "" : markdown;
        if (md.length() > MAX_CHARS) {          // 超长只留最后一段（结论通常在最后）
            md = "…" + md.substring(md.length() - MAX_CHARS);
            r.truncated = true;
        }
        r.view = col;
        body(ctx, col, md, st);
        return r;
    }

    // ───────────────────────── 块级解析 ─────────────────────────

    private static void body(Context ctx, LinearLayout col, String md, Style st) {
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0, n = lines.length;
        StringBuilder para = new StringBuilder();
        int listDepth = 0;              // 当前列表缩进层数（0 = 不在列表里）

        while (i < n) {
            String raw = lines[i];
            String t = raw.trim();

            if (t.isEmpty()) {
                flushPara(ctx, col, para, st);
                i++;
                continue;
            }

            // 代码块 ``` / ~~~
            if (t.startsWith("```") || t.startsWith("~~~")) {
                flushPara(ctx, col, para, st);
                String fence = t.startsWith("```") ? "```" : "~~~";
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++;
                addCode(ctx, col, code.toString(), st);
                continue;
            }

            // 分割线：**直接跳过**（用户要求"把横线删掉"—— 它在球里只占地方）
            if (isHr(t)) {
                flushPara(ctx, col, para, st);
                i++;
                continue;
            }

            // 表格：本行含 | 且下一行是对齐行
            if (t.indexOf('|') >= 0 && i + 1 < n && isTableSep(lines[i + 1])) {
                flushPara(ctx, col, para, st);
                List<String> head = splitRow(t);
                i += 2;
                List<List<String>> rows = new ArrayList<>();
                while (i < n && lines[i].indexOf('|') >= 0 && !lines[i].trim().isEmpty()) {
                    rows.add(splitRow(lines[i].trim()));
                    i++;
                }
                addTable(ctx, col, head, rows, st);
                continue;
            }

            // 标题
            int hashes = headingLevel(t);
            if (hashes > 0) {
                flushPara(ctx, col, para, st);
                String txt = t.substring(hashes).trim().replaceAll("\\s*#+\\s*$", "");
                addHeading(ctx, col, hashes, txt, st);
                i++;
                continue;
            }

            // 引用
            if (t.startsWith(">")) {
                flushPara(ctx, col, para, st);
                if (listDepth > 0) listDepth = 0;
                String q = t.substring(1).trim();
                addQuote(ctx, col, q, st);
                i++;
                continue;
            }

            // 列表项
            String item = listItemText(t);
            if (item != null) {
                flushPara(ctx, col, para, st);
                int indent = indentOf(raw);
                listDepth = Math.max(1, Math.min(4, indent / 2 + 1));
                addListItem(ctx, col, item, t, listDepth, st);
                i++;
                continue;
            }

            // 普通段落
            listDepth = 0;
            if (para.length() > 0) para.append('\n');
            para.append(t);
            i++;
        }
        flushPara(ctx, col, para, st);
    }

    private static void flushPara(Context ctx, LinearLayout col, StringBuilder para, Style st) {
        if (para.length() == 0) return;
        addText(ctx, col, para.toString(), st.bodyPx, st.fg, 0, st.gap);
        para.setLength(0);
    }

    // ───────────────────────── 各种块 ─────────────────────────

    private static void addHeading(Context ctx, LinearLayout col, int level, String txt, Style st) {
        float scale = level <= 1 ? 1.30f : level == 2 ? 1.16f : level == 3 ? 1.04f : 1.0f;
        TextView tv = makeText(ctx, txt, st.headPx * scale, st.fg, Typeface.BOLD, st);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
        lp.topMargin = Math.round(st.gap * 1.4f);
        lp.bottomMargin = Math.round(st.gap * 0.5f);
        col.addView(tv);
    }

    private static void addQuote(Context ctx, LinearLayout col, String txt, Style st) {
        TextView tv = makeText(ctx, txt, st.bodyPx, st.fgDim, Typeface.NORMAL, st);
        tv.setPadding(Math.round(st.bodyPx * 0.7f), Math.round(st.gap * 0.3f), 0, Math.round(st.gap * 0.3f));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
        lp.bottomMargin = st.gap;
        col.addView(tv);
    }

    private static void addCode(Context ctx, LinearLayout col, String code, Style st) {
        TextView tv = makeText(ctx, code.trim(), st.codePx, st.fg, Typeface.NORMAL, st);
        tv.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        tv.setBackgroundColor(st.codeBg);
        int p = Math.round(st.bodyPx * 0.5f);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
        lp.bottomMargin = st.gap;
        col.addView(tv);
    }

    private static void addListItem(Context ctx, LinearLayout col, String item, String rawT,
                                    int depth, Style st) {
        boolean ordered = rawT.matches("\\d{1,9}[.)]\\s+.*");
        String marker = ordered ? rawT.replaceAll("^(\\d{1,9})[.)]\\s+.*$", "$1.") : "•";
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int indent = Math.round(st.bodyPx * 1.1f * (depth - 1));
        row.setPadding(indent, 0, 0, 0);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Math.round(st.gap * 0.35f);
        row.setLayoutParams(rowLp);

        TextView mk = makeText(ctx, marker, st.bodyPx, st.fgDim, Typeface.NORMAL, st);
        int mkWidth = Math.round(st.bodyPx * (ordered ? 2.2f : 1.4f));
        LinearLayout.LayoutParams mkLp = new LinearLayout.LayoutParams(mkWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        mk.setLayoutParams(mkLp);
        mk.setGravity(Gravity.START);
        row.addView(mk);

        TextView tv = makeText(ctx, item, st.bodyPx, st.fg, Typeface.NORMAL, st);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tvLp);
        row.addView(tv);
        col.addView(row);
    }

    /**
     * 表格：每行一个横向 LinearLayout，每列一个定宽 TextView —— **列与列对齐**，观感接近真表格
     * （没有画框线：球内是玻璃背景，框线反而脏；用列对齐 + 表头加粗来区分）。
     */
    private static void addTable(Context ctx, LinearLayout col, List<String> head,
                                 List<List<String>> rows, Style st) {
        int cols = head.size();
        if (cols <= 0) return;
        // 每列按"最长内容"估个宽度权重（汉字算 2、其他算 1）
        float[] weight = new float[cols];
        for (int c = 0; c < cols; c++) weight[c] = Math.max(2f, visualLen(head.get(c)));
        for (List<String> r : rows) {
            for (int c = 0; c < cols; c++) {
                if (c < r.size()) weight[c] = Math.max(weight[c], visualLen(r.get(c)));
            }
        }
        float total = 0f;
        for (float w : weight) total += w;

        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = st.gap;
        table.setLayoutParams(tLp);

        addTableRow(ctx, table, head, cols, weight, total, st, true);
        for (List<String> r : rows) addTableRow(ctx, table, r, cols, weight, total, st, false);
        col.addView(table);
    }

    private static void addTableRow(Context ctx, LinearLayout table, List<String> cells, int cols,
                                    float[] weight, float total, Style st, boolean header) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int c = 0; c < cols; c++) {
            String txt = c < cells.size() ? cells.get(c) : "";
            TextView tv = makeText(ctx, txt, st.bodyPx * 0.94f,
                    header ? st.fg : st.fg, header ? Typeface.BOLD : Typeface.NORMAL, st);
            float frac = total > 0 ? weight[c] / total : (1f / cols);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.max(0.15f, frac));
            int gapPx = Math.round(st.bodyPx * 0.35f);
            lp.rightMargin = gapPx;
            tv.setLayoutParams(lp);
            if (header) {
                int bot = Math.round(st.bodyPx * 0.22f);
                tv.setPadding(0, 0, 0, bot);
            }
            row.addView(tv);
        }
        if (header) {
            // 表头不再画横线（用户要求删掉横线）—— 只靠**加粗 + 下方留白**区分表头
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.bottomMargin = Math.round(st.bodyPx * 0.34f);
            row.setLayoutParams(hlp);
            table.addView(row);
        } else {
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Math.round(st.bodyPx * 0.22f);
            row.setLayoutParams(rlp);
            table.addView(row);
        }
    }

    // ───────────────────────── 文本工具 ─────────────────────────

    private static void addText(Context ctx, LinearLayout col, String txt, float sizePx,
                                int color, int typefaceStyle, int bottomGap) {
        TextView tv = makeText(ctx, txt, sizePx, color, typefaceStyle, null);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
        lp.bottomMargin = bottomGap;
        col.addView(tv);
    }

    private static TextView makeText(Context ctx, String txt, float sizePx, int color,
                                     int typefaceStyle, Style st) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create("sans-serif", typefaceStyle));
        tv.setLineSpacing(0f, 1.5f);
        tv.setIncludeFontPadding(false);
        tv.setText(inline(txt, sizePx, st));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    /**
     * 行内标记 → Spannable：**粗体 / 斜体 / 行内代码**。
     *
     * 刻意保持"简单且绝不抛异常"：解析失败就原样显示（球里显示错一点远好过崩掉）。
     */
    private static CharSequence inline(String s, float sizePx, Style st) {
        if (s == null || s.indexOf('*') < 0 && s.indexOf('`') < 0) return s;
        try {
            android.text.SpannableString sp = new android.text.SpannableString(s);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|`([^`]+)`").matcher(s);
            List<int[]> spans = new ArrayList<>();   // {start, end, kind}
            while (m.find()) {
                if (m.group(1) != null) spans.add(new int[]{m.start(), m.end(), 0});
                else if (m.group(2) != null) spans.add(new int[]{m.start(), m.end(), 1});
                else spans.add(new int[]{m.start(), m.end(), 2});
            }
            // 从后往前替换（免得下标错位）：先把标记符去掉，再套 span
            StringBuilder sb = new StringBuilder(s);
            List<int[]> out = new ArrayList<>();
            for (int k = spans.size() - 1; k >= 0; k--) {
                int[] sp1 = spans.get(k);
                int a = sp1[0], b = sp1[1], kind = sp1[2];
                int innerA = kind == 0 ? a + 2 : a + 1;
                int innerB = kind == 0 ? b - 2 : (kind == 1 ? b - 1 : b - 1);
                if (innerA < 0 || innerB > sb.length() || innerA >= innerB) continue;
                sb.delete(b - (kind == 0 ? 2 : 1), b);                 // 去掉尾部标记
                sb.delete(a, a + (kind == 0 ? 2 : 1));                 // 去掉头部标记
                out.add(new int[]{a, b - (kind == 0 ? 4 : 2), kind});
            }
            sp = new android.text.SpannableString(sb.toString());
            for (int[] o : out) {
                int a = Math.max(0, Math.min(o[0], sp.length()));
                int b = Math.max(a, Math.min(o[1], sp.length()));
                if (a >= b) continue;
                if (o[2] == 0) sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                        a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                else if (o[2] == 1) sp.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC),
                        a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                else {
                    sp.setSpan(new android.text.style.TypefaceSpan("monospace"),
                            a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new android.text.style.BackgroundColorSpan(st.codeBg),
                            a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return sp;
        } catch (Throwable t) {
            return s;   // 解析出错就原样显示，绝不因为排版把球搞崩
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static boolean isHr(String t) {
        String s = t.replace(" ", "");
        if (s.length() < 3) return false;
        char c = s.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return true;
    }

    private static int headingLevel(String t) {
        int k = 0;
        while (k < t.length() && t.charAt(k) == '#') k++;
        if (k == 0 || k > 6) return 0;
        if (k < t.length() && t.charAt(k) != ' ') return 0;
        return k;
    }

    private static boolean isTableSep(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.indexOf('-') < 0) return false;
        int cells = 0;
        for (String c : t.split("\\|", -1)) {
            String s = c.trim();
            if (s.isEmpty()) continue;
            if (!s.matches(":?-{1,}:?")) return false;
            cells++;
        }
        return cells >= 1;
    }

    private static int indentOf(String raw) {
        int k = 0;
        while (k < raw.length() && (raw.charAt(k) == ' ' || raw.charAt(k) == '\t')) {
            k += raw.charAt(k) == '\t' ? 2 : 1;
        }
        return k;
    }

    private static String listItemText(String t) {
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) return t.substring(2).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\d{1,9}[.)]\\s+(.*)$").matcher(t);
        if (m.matches()) return m.group(1).trim();
        return null;
    }

    private static List<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : t.split("\\|", -1)) cells.add(c.trim());
        return cells;
    }

    /** 视觉长度：汉字算 2、其他算 1（估列宽用）。 */
    private static float visualLen(String s) {
        if (s == null) return 0f;
        float n = 0f;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c >= 0x2E80) ? 2f : 1f;
        }
        return n;
    }
}
