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
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        text.setColor(0xD9FFFFFF);
        text.setTextSize(dp(12f));
        text.setTextAlign(Paint.Align.CENTER);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    private float dp(float v) { return v * density; }

    /** 区心与半径（本 View 坐标系 = 球窗坐标系）。 */
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
            float a = (active == i) ? 1f : 0.72f;
            int alpha = Math.round(a * 255 * appear);
            stroke.setColor((alpha << 24) | 0x00FFFFFF);
            text.setColor(((int) (alpha * 0.82f) << 24) | 0x00FFFFFF);
            float rr = r * (active == i ? 1.06f : 1f);
            // 图标略偏上、文字压在圈内下缘 —— 两样都必须落在玻璃圆里（用户：文字要被囊括起来）
            drawIcon(cv, i, cx[i], cy[i] - r * 0.22f, active == i);
            String label = LABELS[i];
            cv.drawText(label, cx[i], cy[i] + r * 0.46f, text);
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
            cv.drawText(fit(cardTitle, text, cardTitleRect[2] - dp(20f)), tx, ty, text);
        }
        // 焦点行的高亮：面板内部的一条胶囊底（不是另一块玻璃）
        if (cardFocus >= 0 && cardFocus * 4 + 3 < cardRects.length) {
            rowHi.setColor(0x2EFFFFFF);
            RectF hi = new RectF(cardRects[cardFocus * 4], cardRects[cardFocus * 4 + 1],
                    cardRects[cardFocus * 4] + cardRects[cardFocus * 4 + 2],
                    cardRects[cardFocus * 4 + 1] + cardRects[cardFocus * 4 + 3]);
            hi.inset(dp(2f), dp(2f));
            cv.drawRoundRect(hi, dp(9f), dp(9f), rowHi);
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

    /** 图标：0 文件夹 / 1 放大镜 / 2 铅笔 / 3 滑杆。尺寸按 r 等比缩放。 */
    private void drawIcon(Canvas cv, int which, float x, float y, boolean on) {
        float s = r * 0.52f;                    // 图标边长
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
            case 1: {                           // 搜索会话：放大镜（与搜索胶囊同一个形状）
                path.addCircle(10.5f, 10.5f, 6.2f, Path.Direction.CW);
                path.moveTo(15.1f, 15.1f);
                path.lineTo(20.5f, 20.5f);
                break;
            }
            case 2: {                           // 输入：铅笔
                path.moveTo(4f, 20f);
                path.lineTo(5.2f, 15.4f);
                path.lineTo(16.4f, 4.2f);
                path.lineTo(19.8f, 7.6f);
                path.lineTo(8.6f, 18.8f);
                path.close();
                path.moveTo(14.6f, 6.0f);
                path.lineTo(18.0f, 9.4f);
                break;
            }
            default: {                          // 参数：三根滑杆
                path.moveTo(2.5f, 7f); path.lineTo(21.5f, 7f);
                path.moveTo(2.5f, 12f); path.lineTo(21.5f, 12f);
                path.moveTo(2.5f, 17f); path.lineTo(21.5f, 17f);
                break;
            }
        }
        if (on) {
            // 选中态：线条更亮一点（玻璃那边的亮边由 shader 负责）
            stroke.setColor(0xFFFFFFFF);
        }
        cv.drawPath(path, stroke);
        if (which == 3) {                       // 滑杆的三个旋钮
            cv.drawCircle(8f, 7f, 2.1f, fill);
            cv.drawCircle(16f, 12f, 2.1f, fill);
            cv.drawCircle(10.5f, 17f, 2.1f, fill);
        }
        if (which == 0) {                       // 文件夹口
            path.reset();
            path.moveTo(2.5f, 12.2f);
            path.lineTo(21.5f, 12.2f);
            cv.drawPath(path, stroke);
        }
        cv.restore();
    }
}
