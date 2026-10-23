package com.glslplayground.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.view.View;

/**
 * 状态 3 的**内容层**：四个圆区里的图标 + 文字，以及当前手指落在哪个区的反馈。
 *
 * 玻璃本体不在这里 —— 那是由 {@link ZoneLayerShader} 在 GL 层画的，四个圆和菜单面板
 * 与球、搜索胶囊共用同一套折射/高光公式。这个 View 只负责贴在最上面画白色线条图标，
 * 所以它是**透明、不吃触摸**的（触摸仍归球的代理窗口，才能一边按住一边滑）。
 *
 * 图标全部用 Path 画（和搜索胶囊的放大镜同一套路），不依赖字体/emoji ——
 * 换 ROM、换字体都不会变豆腐块。
 */
final class BallZonesView extends View {

    /**
     * 六个区（2026-10-23：四区 → 六区围成一圈）：
     * 0=左上 工作目录 / 1=右上 搜索会话 / 2=左下 停止（运行中会话） / 3=右下 参数 /
     * 4=正上 待定 / 5=正下 待定（占位，只画一圈淡淡的轮廓）。
     */
    static final String[] LABELS = {"工作目录", "搜索会话", "停止", "参数", "待定", "待定"};
    static final int ZONE_COUNT = 6;

    private final float density;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF ring2 = new RectF();
    /** 焦点行的悬停进度（0..1）—— 2026-10-23 起"停够时间就进下一级"，得让用户看得见。 */
    private int cardProgressIdx = -1;
    private float cardProgress = 0f;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 六区的"玻璃盘"：自己画（和图标同一个 Canvas / 同一个原点，天生对齐）。 */
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF discRect = new RectF();
    private final Paint radial = new Paint();
    private final RectF ring = new RectF();

    /** 区心（**屏幕**坐标，service 直接给这个）。draw 时再换算成本 View 坐标。 */
    private final float[] sx = new float[ZONE_COUNT], sy = new float[ZONE_COUNT];
    /** 本 View 坐标下的区心（onDraw 里按真实窗口位置算出来，保证和玻璃同一个原点）。 */
    private final float[] cx = new float[ZONE_COUNT], cy = new float[ZONE_COUNT];
    private float r = 60f;
    private int active = -1;
    /** 0..1 出现进度（按住时淡入，避免"啪"地出现）。 */
    private float appear = 0f;

    // ── 卡片模式（状态 3 的二级/三级菜单）───────────────────────────────
    // 和四个圆区共用这块内容层：圆区是"图标 + 名字"，卡片是"标题 + 若干行"。
    // 玻璃由 ZoneLayerShader 画（每张卡片一个圆角形状），这里只排版白字。
    private boolean cardMode = false;
    private String cardTitle = "";
    private String[] cardLabels = new String[0];
    private String[] cardSubs = new String[0];
    private float[] cardRects = new float[0];      // 本 View 坐标：x,y,w,h
    private int cardFocus = -1;
    /** 滚动提示：bit0 = 上面还有、bit1 = 下面还有（列表可滚动时画一个小箭头）。 */
    private int cardScroll = 0;

    void setCardScroll(int mode) {
        if (cardScroll == mode) return;
        cardScroll = mode;
        invalidate();
    }

    private float[] cardTitleRect = new float[0];
    private float[] cardListRect = new float[0];
    /** draw 时换算出来的"本 View 坐标下的卡片矩形"（service 给的是屏幕坐标）。 */
    private float[] localRects = new float[0];
    private float[] localTitleRect = new float[0];
    private float[] localListRect = new float[0];
    private int cardLocX = 0, cardLocY = 0;
    private long lastAlignLog = 0;
    private final Paint rowHi = new Paint(Paint.ANTI_ALIAS_FLAG);

    BallZonesView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(dp(1.7f));
        stroke.setColor(0xF2FFFFFF);
        // 玻璃变清透之后（2026-10-23 改成球那套折射），白字/白线在亮桌面上会"糊"掉 ——
        // 统一加一层柔和投影，等于给它们各自垫了一圈玻璃边，不再依赖把整块玻璃压黑。
        // stroke shadow off (test)
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        text.setColor(0xD9FFFFFF);
        text.setShadowLayer(dp(1.6f), 0f, dp(0.6f), 0xC0000000);
        text.setTextSize(dp(12f));
        text.setTextAlign(Paint.Align.CENTER);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(1.6f));
        rim.setColor(0x66FFFFFF);
        disc.setStyle(Paint.Style.FILL);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    private float dp(float v) { return v * density; }

    /** 区心与半径（本 View 坐标系 = 球窗坐标系）。 */
    /** 焦点行的悬停进度：0..1（idx = 可见段里的下标，-1 = 没有）。 */
    void setCardProgress(int idx, float p) {
        int ni = idx;
        float np = Math.max(0f, Math.min(1f, p));
        if (ni == cardProgressIdx && Math.abs(np - cardProgress) < 0.01f) return;
        cardProgressIdx = ni;
        cardProgress = np;
        invalidate();
    }

    /** 传**屏幕**坐标：draw 时用 getLocationOnScreen 换算，窗口被挪过也不会错位。 */
    void setZones(float[] xs, float[] ys, float radius) {
        if (xs != null) System.arraycopy(xs, 0, sx, 0, Math.min(ZONE_COUNT, xs.length));
        if (ys != null) System.arraycopy(ys, 0, sy, 0, Math.min(ZONE_COUNT, ys.length));
        r = radius;
        invalidate();
    }

    void setActive(int idx) {
        if (active == idx) return;
        active = idx;
        invalidate();
    }

    /** 切到卡片模式（二级/三级菜单）：圆区图标整组隐藏。 */
    void showCards(String title, String[] labels, String[] subs, float[] rects,
                   float[] titleRect, float[] listRect, int focus) {
        cardMode = true;
        cardTitle = title == null ? "" : title;
        cardLabels = labels == null ? new String[0] : labels;
        cardSubs = subs == null ? new String[0] : subs;
        cardRects = rects == null ? new float[0] : rects;
        cardTitleRect = titleRect == null ? new float[0] : titleRect;
        cardListRect = listRect == null ? new float[0] : listRect;
        cardFocus = focus;
        invalidate();
    }

    /** 回到四个圆区。 */
    void hideCards() {
        if (!cardMode) return;
        cardMode = false;
        cardFocus = -1;
        invalidate();
    }

    void setCardFocus(int focus) {
        if (cardFocus == focus) return;
        cardFocus = focus;
        invalidate();
    }

    void setAppear(float a) {
        appear = Math.max(0f, Math.min(1f, a));
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        if (appear <= 0.01f) return;
        // ⚠ 2026-10-23：所有坐标都从**屏幕坐标**换算到"本 View 坐标"。
        // 以前由 service 用 glView.getLocationOnScreen() 预先算好 —— 窗口刚被挪过时那是旧值，
        // 于是整层图标偏一个位移（"六个图标没有一个对得上的"）。draw 这一刻的位置才是真的。
        int[] wloc = new int[2];
        getLocationOnScreen(wloc);
        for (int i = 0; i < ZONE_COUNT; i++) {
            cx[i] = sx[i] - wloc[0];
            cy[i] = sy[i] - wloc[1];
        }
        cardLocX = wloc[0];
        cardLocY = wloc[1];
        // 对齐自检：View 窗口原点 + 本地圆心 应该 == 屏幕圆心（service 侧的 zoneCx/zoneCy）
        if (System.currentTimeMillis() - lastAlignLog > 800) {
            lastAlignLog = System.currentTimeMillis();
            android.util.Log.i("FloatingBall", "zones align: viewWin=(" + wloc[0] + "," + wloc[1]
                    + ") r=" + Math.round(r)
                    + " i0=(" + Math.round(cx[0] + wloc[0]) + "," + Math.round(cy[0] + wloc[1]) + ")"
                    + " i4=(" + Math.round(cx[4] + wloc[0]) + "," + Math.round(cy[4] + wloc[1]) + ")");
        }
        if (localTitleRect.length < 4) localTitleRect = new float[4];
        if (localListRect.length < 4) localListRect = new float[4];
        if (cardTitleRect.length >= 4) {
            localTitleRect[0] = cardTitleRect[0] - cardLocX;
            localTitleRect[1] = cardTitleRect[1] - cardLocY;
            localTitleRect[2] = cardTitleRect[2];
            localTitleRect[3] = cardTitleRect[3];
        }
        if (cardListRect.length >= 4) {
            localListRect[0] = cardListRect[0] - cardLocX;
            localListRect[1] = cardListRect[1] - cardLocY;
            localListRect[2] = cardListRect[2];
            localListRect[3] = cardListRect[3];
        }
        if (localRects.length < cardRects.length) localRects = new float[cardRects.length];
        for (int i = 0; i < cardRects.length; i += 4) {
            localRects[i] = cardRects[i] - cardLocX;
            localRects[i + 1] = cardRects[i + 1] - cardLocY;
            localRects[i + 2] = cardRects[i + 2];
            localRects[i + 3] = cardRects[i + 3];
        }
        int save = cv.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(appear * 255));
        if (cardMode) {
            drawCards(cv);
            cv.restoreToCount(save);
            return;
        }
        for (int i = 0; i < ZONE_COUNT; i++) {
            float a = (active == i) ? 1f : (i >= 4 ? 0.42f : 0.92f);   // 待定的两个淡一点
            int alpha = Math.round(a * 255 * appear);
            stroke.setColor((alpha << 24) | 0x00FFFFFF);
            // ── 玻璃圆：**这里自己画**（和图标同一个 Canvas、同一个圆心）──
            //   用户 2026-10-23：「圆是玻璃，要让图标被玻璃囊括起来」。
            //   以前圆由 GL 层画：GL 的 surface 在窗口放大后不重建（k≠1），圆被缩放/裁切，
            //   和图标永远对不上。现在两样都在同一个 Canvas 上 → 圆心就是图标中心。
            float rr = r * (active == i ? 1.04f : 1f);
            int aTop = (int) ((active == i ? 64 : 40) * appear);
            int aMid = (int) ((active == i ? 30 : 18) * appear);
            radial.setShader(new android.graphics.RadialGradient(cx[i], cy[i] - rr * 0.30f, rr * 1.28f,
                    new int[]{0x00FFFFFF | (aTop << 24), 0x00FFFFFF | (aMid << 24), 0x00000000},
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            disc.setShader(radial.getShader());
            cv.drawCircle(cx[i], cy[i], rr, disc);
            disc.setShader(null); radial.setShader(null);
            // 一圈亮边 + 顶部高光弧：有了这两笔才像"玻璃"而不是"描边圆"
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(dp(1.6f));
            rim.setColor(((int) ((active == i ? 0x8A : 0x5E) * appear) << 24) | 0x00FFFFFF);
            cv.drawCircle(cx[i], cy[i], rr - dp(0.8f), rim);
            rim.setStrokeWidth(dp(1.2f));
            rim.setColor(((int) ((active == i ? 0x9A : 0x66) * appear) << 24) | 0x00FFFFFF);
            cv.drawArc(new RectF(cx[i] - rr + dp(0.6f), cy[i] - rr + dp(0.6f),
                    cx[i] + rr - dp(0.6f), cy[i] + rr - dp(0.6f)), 208f, 124f, false, rim);
            // 用户 2026-10-23：圆区里**不要文字**，只留一个大一点的发光图标（居中）
            if (i >= 4) {
                // 待定：只画一圈细环（占位，先说"这里还空着"）
                stroke.setStrokeWidth(dp(1.2f));
                ring.set(cx[i] - rr * 0.52f, cy[i] - rr * 0.52f, cx[i] + rr * 0.52f, cy[i] + rr * 0.52f);
                cv.drawOval(ring, stroke);
                stroke.setStrokeWidth(dp(1.7f));
            } else {
                drawIcon(cv, i, cx[i], cy[i], active == i);
            }
            if (active == i) {
                ring.set(cx[i] - rr, cy[i] - rr, cx[i] + rr, cy[i] + rr);
                stroke.setStrokeWidth(dp(2.2f));
                stroke.setColor(0x66FFFFFF);
                cv.drawOval(ring, stroke);
                stroke.setStrokeWidth(dp(1.7f));
            }
        }
        cv.restoreToCount(save);
    }

    /** 卡片排版：标题一行 + 每张卡片两行（名字 / 说明），焦点那张更亮。 */
    private void drawCards(Canvas cv) {
        int n = Math.min(cardLabels.length, localRects.length / 4);
        if (n <= 0) return;
        // ── 面板底：整个状态 3 都由这一个 View 画（不再用 GL 层）──
        //   理由：GLSurfaceView 的 surface 在窗口尺寸变化后可能仍停在旧尺寸（实测 787×573 vs 846×846），
        //   玻璃层于是被缩放/裁切，圆和图标永远对不齐。这里用同一个 Canvas 画，天生对齐。
        if (localListRect.length >= 4) {
            float pad = dp(6f);
            RectF bg = new RectF(localListRect[0] - pad, localListRect[1] - pad,
                    localListRect[0] + localListRect[2] + pad,
                    localListRect[1] + localListRect[3] + pad);
            disc.setShader(new android.graphics.LinearGradient(bg.left, bg.top, bg.left, bg.bottom,
                    new int[]{0xE616161A, 0xE60E0E11}, null, android.graphics.Shader.TileMode.CLAMP));
            disc.setColor(0x00000000);
            cv.drawRoundRect(bg, dp(14f), dp(14f), disc);
            disc.setShader(null);
            rim.setColor(0x33FFFFFF);
            rim.setStrokeWidth(dp(1.2f));
            cv.drawRoundRect(bg, dp(14f), dp(14f), rim);
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(dp(10.5f));
        text.setColor(0xD9FFFFFF);
        if (localTitleRect.length >= 4) {
            float tx = localTitleRect[0] + dp(10f);
            float ty = localTitleRect[1] + localTitleRect[3] * 0.66f;
            cv.drawText(fit(cardTitle, text, localTitleRect[2] - dp(34f)), tx, ty, text);
            if (cardScroll != 0) {
                // 右上一个很小的箭头：▲ 还能往上翻、▼ 下面还有（面板大小不变，只是可滚动）
                Paint.Align old = text.getTextAlign();
                text.setTextAlign(Paint.Align.RIGHT);
                text.setTextSize(dp(11f));
                text.setColor(0x99FFFFFF);
                String arrow = (cardScroll == 1) ? "↑" : (cardScroll == 3) ? "↑↓" : "↓";
                cv.drawText(arrow, localTitleRect[0] + localTitleRect[2] - dp(10f), ty, text);
                text.setTextAlign(old);
            }
        }
        // 焦点行的高亮：面板内部的一条胶囊底（不是另一块玻璃）
        if (cardFocus >= 0 && cardFocus * 4 + 3 < localRects.length) {
            // 悬停越久底色越亮 + 底部一条进度线 —— "停够就进下一级"这件事必须看得见
            float p = (cardProgressIdx == cardFocus) ? cardProgress : 0f;
            int a = 0x40 + Math.round((0x88 - 0x40) * p);
            rowHi.setColor((a << 24) | 0x00FFFFFF);
            RectF hi = new RectF(localRects[cardFocus * 4], localRects[cardFocus * 4 + 1],
                    localRects[cardFocus * 4] + localRects[cardFocus * 4 + 2],
                    localRects[cardFocus * 4 + 1] + localRects[cardFocus * 4 + 3]);
            hi.inset(dp(2f), dp(2f));
            cv.drawRoundRect(hi, dp(9f), dp(9f), rowHi);
            if (p > 0.02f) {
                // 进度线：行底一条纯白细线，从左往右长到 p —— 悬停进度的唯一可视反馈
                float inner = hi.width() - dp(12f);
                float w = Math.max(dp(3f), inner * p);
                bar.setColor(0xFFFFFFFF);
                cv.drawRoundRect(hi.left + dp(6f), hi.bottom - dp(3.4f),
                        hi.left + dp(6f) + w, hi.bottom - dp(1.4f), dp(1f), dp(1f), bar);
            }
        }
        for (int i = 0; i < n; i++) {
            float x = localRects[i * 4], y = localRects[i * 4 + 1];
            float w = localRects[i * 4 + 2], h = localRects[i * 4 + 3];
            boolean on = (i == cardFocus);
            text.setTextSize(dp(on ? 13.5f : 12.5f));
            text.setColor(on ? 0xFFFFFFFF : 0xE6FFFFFF);
            String label = cardLabels[i];
            String sub = i < cardSubs.length ? cardSubs[i] : "";
            float baseY = sub == null || sub.isEmpty() ? y + h * 0.62f : y + h * 0.42f;
            cv.drawText(fit(label, text, w - dp(24f)), x + dp(12f), baseY, text);
            if (sub != null && !sub.isEmpty()) {
                text.setTextSize(dp(9.5f));
                text.setColor(on ? 0xCFFFFFFF : 0x8AFFFFFF);
                cv.drawText(fit(sub, text, w - dp(24f)), x + dp(12f), baseY + dp(13f), text);
            }
        }
    }

    private static String fit(String s, Paint p, float maxW) {
        if (s == null) return "";
        if (maxW <= 0 || p.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && p.measureText(s.substring(0, n) + "…") > maxW) n--;
        return s.substring(0, n) + "…";
    }

    /** 图标：0 文件夹 / 1 放大镜 / 2 停止（运行中会话） / 3 滑杆。尺寸按 r 等比缩放。 */
    private void drawIcon(Canvas cv, int which, float x, float y, boolean on) {
        float s = r * 0.80f;                    // 图标边长（比原来大一截）
        cv.save();
        cv.translate(x - s * 0.5f, y - s * 0.5f);
        cv.scale(s / 24f, s / 24f);             // 统一按 24×24 坐标下笔
        path.reset();
        switch (which) {
            case 0: {                           // 工作目录：文件夹
                path.moveTo(2.5f, 7f);
                path.lineTo(9f, 7f);
                path.lineTo(11f, 9.5f);
                path.lineTo(21.5f, 9.5f);
                path.lineTo(21.5f, 19f);
                path.lineTo(2.5f, 19f);
                path.close();
                break;
            }
            case 1: {                           // 搜索会话：放大镜
                path.addCircle(10.5f, 10.5f, 6.4f, Path.Direction.CW);
                path.moveTo(15.2f, 15.2f);
                path.lineTo(20.8f, 20.8f);
                break;
            }
            case 2: {                           // 运行中的会话 / 停止（2026-10-23 换掉铅笔）
                // 外框一个圆角方块 + 中间实心方块 = 一眼认得出的「停止」
                ring2.set(2.6f, 2.6f, 21.4f, 21.4f);
                path.addRoundRect(ring2, 4.2f, 4.2f, Path.Direction.CW);
                break;
            }
            default: {                          // 参数：三根滑杆
                path.moveTo(2.5f, 7f); path.lineTo(21.5f, 7f);
                path.moveTo(2.5f, 12f); path.lineTo(21.5f, 12f);
                path.moveTo(2.5f, 17f); path.lineTo(21.5f, 17f);
                break;
            }
        }
        // ── 发光：从外到内叠 4 层白描边（越外越淡、越粗），最后一层是"刺眼"的纯白细线 ──
        // 不用 BlurMaskFilter（硬件层上不稳），用多层描边模拟辉光，任何 ROM 都一致。
        float core = dp(1.15f);                       // 线宽（px）
        float k24  = 24f / s;                         // px → 24 坐标系的换算
        // 15 层连续衰减的描边 = 平滑的辉光（层数少了会看到一圈圈"年轮"）。
        // 用多层描边而不是 BlurMaskFilter：后者在硬件加速画布上对 Path 经常被忽略。
        for (float w = 1.0f; w <= 24.0f; w += 1.55f) {
            float f = (w - 1.0f) / 23.0f;
            int a = (int) (165.0 * Math.pow(1.0f - f, 2.3));
            if (a <= 2) continue;
            stroke.setColor((a << 24) | 0x00FFFFFF);
            stroke.setStrokeWidth((core + w) * k24);
            cv.drawPath(path, stroke);
        }
        stroke.setColor(0xFFFFFFFF);                  // 芯：纯白
        stroke.setStrokeWidth(core * k24);
        cv.drawPath(path, stroke);
        if (which == 2) {                             // 停止：中间那块实心（跟芯一样纯白）
            fill.setColor(0xFFFFFFFF);
            cv.drawRoundRect(new RectF(8.2f, 8.2f, 15.8f, 15.8f), 1.6f, 1.6f, fill);
        }
        if (which == 3) {                             // 滑杆的三个旋钮（也用纯白，跟芯一致）
            fill.setColor(0xFFFFFFFF);
            cv.drawCircle(8f, 7f, 1.9f, fill);
            cv.drawCircle(16f, 12f, 1.9f, fill);
            cv.drawCircle(10.5f, 17f, 1.9f, fill);
        }
        // 文件夹口那条横线去掉：加它看着像"表格"，干净的文件夹轮廓更像系统图标
        cv.restore();
    }
}
