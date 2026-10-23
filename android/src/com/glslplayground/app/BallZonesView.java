package com.glslplayground.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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

    /** 四个区：0=左上 1=右上 2=左下 3=右下（顺序与 FloatingBallService.ZONE_* 一致）。 */
    static final String[] LABELS = {"工作目录", "搜索会话", "输入", "参数"};

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
    private final RectF ring = new RectF();

    private final float[] cx = new float[4], cy = new float[4];
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

    void setZones(float[] xs, float[] ys, float radius) {
        System.arraycopy(xs, 0, cx, 0, 4);
        System.arraycopy(ys, 0, cy, 0, 4);
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
        int save = cv.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(appear * 255));
        if (cardMode) {
            drawCards(cv);
            cv.restoreToCount(save);
            return;
        }
        for (int i = 0; i < 4; i++) {
            float a = (active == i) ? 1f : 0.92f;
            int alpha = Math.round(a * 255 * appear);
            stroke.setColor((alpha << 24) | 0x00FFFFFF);
            float rr = r * (active == i ? 1.06f : 1f);
            // 用户 2026-10-23：四个缩小镜里**不要文字**，只留一个大一点的发光图标（居中）
            drawIcon(cv, i, cx[i], cy[i], active == i);
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
        int n = Math.min(cardLabels.length, cardRects.length / 4);
        if (n <= 0) return;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(dp(10.5f));
        text.setColor(0xD9FFFFFF);
        if (cardTitleRect.length >= 4) {
            float tx = cardTitleRect[0] + dp(10f);
            float ty = cardTitleRect[1] + cardTitleRect[3] * 0.66f;
            cv.drawText(fit(cardTitle, text, cardTitleRect[2] - dp(34f)), tx, ty, text);
            if (cardScroll != 0) {
                // 右上一个很小的箭头：▲ 还能往上翻、▼ 下面还有（面板大小不变，只是可滚动）
                Paint.Align old = text.getTextAlign();
                text.setTextAlign(Paint.Align.RIGHT);
                text.setTextSize(dp(11f));
                text.setColor(0x99FFFFFF);
                String arrow = (cardScroll == 1) ? "↑" : (cardScroll == 3) ? "↑↓" : "↓";
                cv.drawText(arrow, cardTitleRect[0] + cardTitleRect[2] - dp(10f), ty, text);
                text.setTextAlign(old);
            }
        }
        // 焦点行的高亮：面板内部的一条胶囊底（不是另一块玻璃）
        if (cardFocus >= 0 && cardFocus * 4 + 3 < cardRects.length) {
            // 悬停越久底色越亮 + 底部一条进度线 —— "停够就进下一级"这件事必须看得见
            float p = (cardProgressIdx == cardFocus) ? cardProgress : 0f;
            int a = 0x40 + Math.round((0x88 - 0x40) * p);
            rowHi.setColor((a << 24) | 0x00FFFFFF);
            RectF hi = new RectF(cardRects[cardFocus * 4], cardRects[cardFocus * 4 + 1],
                    cardRects[cardFocus * 4] + cardRects[cardFocus * 4 + 2],
                    cardRects[cardFocus * 4 + 1] + cardRects[cardFocus * 4 + 3]);
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
            float x = cardRects[i * 4], y = cardRects[i * 4 + 1];
            float w = cardRects[i * 4 + 2], h = cardRects[i * 4 + 3];
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
