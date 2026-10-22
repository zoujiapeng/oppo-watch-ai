package com.glslplayground.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 玻璃菜单的**内容层**：标题 + 若干行（工作目录 / 目录下的会话 / 会话搜索结果 / 参数项）。
 *
 * 和 {@link BallZonesView} 一样，玻璃面板本身不在这里画 —— 那由
 * {@link ZoneLayerShader} 在 GL 层用**和球完全相同的材质**画出来（用户要求：
 * 「全部都是和原本的球一样的液态玻璃」）。这个 View 只用白字 + 发丝线排版，
 * 所以整体是透明的；窗口位置 = 面板矩形，触摸只在这块矩形里。
 *
 * 行多了可以上下拖（不会把面板撑出屏幕），拖不动时抬手才算点中。
 */
final class BallGlassMenu extends View {

    interface Listener {
        /** 点了第 index 行。 */
        void onRow(int index);
        /** 点面板以外（由全屏层转发）或返回键。 */
        void onDismiss();
    }

    static final class Row {
        final String label, sub;
        final boolean dim;
        Row(String label, String sub, boolean dim) {
            this.label = label == null ? "" : label;
            this.sub = sub == null ? "" : sub;
            this.dim = dim;
        }
    }

    private final float density;
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String titleText = "";
    private String subText = "";
    private final List<Row> rows = new ArrayList<>();
    private Listener listener;

    private float scrollY = 0f;
    private float maxScroll = 0f;
    private float downY = 0f, downScroll = 0f;
    private boolean moving = false;
    private int pressed = -1;

    BallGlassMenu(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        title.setTextSize(dp(13.5f));
        title.setColor(0xFFFFFFFF);
        title.setFakeBoldText(true);
        sub.setTextSize(dp(10f));
        sub.setColor(0xA6FFFFFF);
        rowLabel.setTextSize(dp(12.5f));
        rowLabel.setColor(0xF2FFFFFF);
        rowSub.setTextSize(dp(10f));
        rowSub.setColor(0x99FFFFFF);
        hair.setColor(0x26FFFFFF);
        hair.setStrokeWidth(Math.max(1f, dp(0.7f)));
        chip.setColor(0x1FFFFFFF);
        setClickable(true);
        setFocusable(false);
    }

    private float dp(float v) { return v * density; }

    /** 单行截断：超出面板宽度就加省略号（画廊式的长路径不再捅出面板）。 */
    private static String ellipsize(String text, Paint p, float maxW) {
        if (text == null) return "";
        if (maxW <= 0 || p.measureText(text) <= maxW) return text;
        int n = text.length();
        while (n > 1 && p.measureText(text.substring(0, n) + "…") > maxW) n--;
        return text.substring(0, n) + "…";
    }
    private float rowH() { return dp(34f); }
    private float padTop() { return dp(11f); }

    void setListener(Listener l) { listener = l; }

    void setContent(String title, String subtitle, List<Row> items) {
        titleText = title == null ? "" : title;
        subText = subtitle == null ? "" : subtitle;
        rows.clear();
        if (items != null) rows.addAll(items);
        scrollY = 0f;
        pressed = -1;
        invalidate();
    }

    void clear() {
        rows.clear();
        titleText = "";
        subText = "";
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        recomputeScroll();
    }

    private void recomputeScroll() {
        float top = headerH();
        float content = rows.size() * rowH();
        maxScroll = Math.max(0f, content - Math.max(0f, getHeight() - top - dp(10f)));
        scrollY = Math.max(0f, Math.min(scrollY, maxScroll));
    }

    private float headerH() {
        return padTop() + dp(16f) + (subText.isEmpty() ? dp(4f) : dp(13f));
    }

    @Override protected void onDraw(Canvas cv) {
        float x = dp(14f);
        float y = padTop() + dp(12f);
        cv.drawText(ellipsize(titleText, title, getWidth() - 2 * x), x, y, title);
        if (!subText.isEmpty()) cv.drawText(ellipsize(subText, sub, getWidth() - 2 * x), x, y + dp(13f), sub);

        float top = headerH();
        cv.save();
        cv.clipRect(0, top, getWidth(), getHeight() - dp(6f));
        float bottomLimit = getHeight() - dp(8f);
        for (int i = 0; i < rows.size(); i++) {
            float ry = top + i * rowH() - scrollY;
            if (ry > getHeight() || ry + rowH() < top) continue;
            // 只画"完整落在玻璃里"的行：半行文字会被面板边缘切断（用户：文字要被囊括起来）
            if (ry + rowH() > bottomLimit) continue;
            Row row = rows.get(i);
            if (i == pressed) {
                r.set(dp(8f), ry + dp(3f), getWidth() - dp(8f), ry + rowH() - dp(3f));
                cv.drawRoundRect(r, dp(12f), dp(12f), chip);
            }
            if (i > 0) cv.drawLine(dp(18f), ry, getWidth() - dp(18f), ry, hair);
            float baseY = ry + (row.sub == null || row.sub.isEmpty() ? rowH() * 0.58f : rowH() * 0.42f);
            rowLabel.setColor(row.dim ? 0x73FFFFFF : 0xF2FFFFFF);
            rowSub.setColor(row.dim ? 0x5CFFFFFF : 0x99FFFFFF);
            cv.drawText(ellipsize(row.label, rowLabel, getWidth() - 2 * x), x, baseY, rowLabel);
            if (row.sub != null && !row.sub.isEmpty()) {
                cv.drawText(ellipsize(row.sub, rowSub, getWidth() - 2 * x), x, baseY + dp(13f), rowSub);
            }
        }
        cv.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downScroll = scrollY;
                moving = false;
                pressed = rowAt(e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - downY;
                if (!moving && Math.abs(dy) > dp(6f)) { moving = true; pressed = -1; }
                if (moving && maxScroll > 0f) {
                    scrollY = Math.max(0f, Math.min(maxScroll, downScroll - dy));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int idx = moving ? -1 : pressed;
                pressed = -1;
                invalidate();
                if (idx >= 0 && listener != null) listener.onRow(idx);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int rowAt(float y) {
        float top = headerH();
        if (y < top) return -1;
        int i = (int) ((y - top + scrollY) / rowH());
        return i >= 0 && i < rows.size() ? i : -1;
    }
}
