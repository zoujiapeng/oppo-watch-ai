package com.glslplayground.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 搜索胶囊里的那一行：🔍 输入框 +（可选）附件小标签 —— 版式照抄 metaball-search.html 的 #search。
 *
 * 三个刻意的地方：
 *
 * 1. **图标是画出来的，不是 emoji/字体**。放大镜的路径直接取自演示页那段 SVG
 *    （viewBox 20×20），按控件尺寸等比缩放 —— 换字体/换 ROM 都不会变成豆腐块。
 * 2. **"凝聚"动画走 RenderEffect 模糊**（Apple Intelligence materialize）：
 *    演示页用 CSS `filter: blur(9px) → 0`，Android 12+ 有等价的 View.setRenderEffect。
 *    低版本只淡入，不影响功能。
 * 3. **手势全部在这一层判**（状态 2 的语法，见 FloatingBallService）：
 *    单击 = 进入编辑（弹键盘）；长按 = 添附件（等同 DSH 输入框那个 `uV2eYG_add`）；
 *    双指长按 = 参数弹窗（Agent 预设 / 模型 / 推理等级 / 权限）。
 *    判手势用的是**窗口级的 dispatchTouchEvent**，所以点在输入框里也算数。
 */
public class BallSearchBar extends LinearLayout {

    public interface Listener {
        /** 键盘回车 / IME 的发送键：把文字交出去。 */
        void onSubmit(String text);
        /** 长按：添附件（等价于前端输入框左边那个「＋」）。 */
        void onLongPressAdd();
        /** 双指长按：弹出 Agent 预设 / 模型 / 推理等级 / 权限。 */
        void onTwoFingerHold();
        /** 点了附件小标签：把它去掉。 */
        void onAttachmentTap();
        /** 单击胶囊 / 输入框：进入编辑（状态 2 的"单击=编辑"）。 */
        void onTapEdit();
    }

    // 颜色/透明度照 metaball-search.html 的 #search 抄：图标 #fff @ .72、
    // 文字 #fff、placeholder rgba(235,235,245,.45)、文字带 0 1px 5px rgba(0,0,0,.35) 的投影
    private static final int COL_ICON = 0xB8FFFFFF;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_HINT = 0x73EBEBF5;

    private final float d;
    private final IconView iconSearch;
    private final EditText input;
    private final TextView attachChip;
    private Listener listener;

    /** 凝聚动画：0 = 完全没出现（模糊+透明），1 = 落定。 */
    private float material = 0f;
    private ValueAnimator matAnim;

    // ── 手势：长按添附件 / 双指长按参数（都在本窗口里判）─────────────────────
    private static final long ADD_HOLD_MS = 500;
    private static final long TWO_HOLD_MS = 520;
    private float downX, downY;
    private boolean gestureTwo = false;
    private boolean gestureFired = false;
    private final Runnable addHold = new Runnable() {
        @Override public void run() {
            if (gestureFired || gestureTwo) return;
            gestureFired = true;
            if (listener != null) listener.onLongPressAdd();
        }
    };
    private final Runnable twoHold = new Runnable() {
        @Override public void run() {
            if (gestureFired || !gestureTwo) return;
            gestureFired = true;
            if (listener != null) listener.onTwoFingerHold();
        }
    };

    public BallSearchBar(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        iconSearch = new IconView(c);
        iconSearch.setIconColor(COL_ICON);

        // 附件小标签：默认不占位；有附件时插在图标右边（对应前端输入框上方那排附件条）
        attachChip = new TextView(c);
        attachChip.setTextColor(0xE6FFFFFF);
        attachChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(12));
        attachChip.setGravity(Gravity.CENTER);
        attachChip.setPadding(Math.round(dp(9)), 0, Math.round(dp(9)), 0);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0x33FFFFFF);
        chipBg.setCornerRadius(dp(11));
        chipBg.setStroke(Math.round(dp(0.8f)), 0x4DFFFFFF);
        attachChip.setBackground(chipBg);
        attachChip.setVisibility(GONE);
        attachChip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onAttachmentTap(); }
        });

        input = new EditText(c);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setTextColor(COL_TEXT);
        input.setHintTextColor(COL_HINT);
        input.setHint("Search or Ask");
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));   // 演示页窄屏档 font-size:16px
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setPadding(Math.round(dp(8)), 0, Math.round(dp(8)), 0);   // 窄屏档 padding:0 8px
        input.setShadowLayer(dp(5), 0f, dp(1), 0x59000000);
        input.setCursorVisible(false);          // 原生光标换成下面自绘的那颗发光光标（演示页 #caret）
        input.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent e) {
                submit();
                return true;                      // 吃掉：不要让 IME 再插入换行
            }
        });
        // 有些输入法（含外接键盘）走的是 KeyEvent.ENTER 而不是 editor action，
        // 两条都接上，用户按回车就一定发得出去。
        input.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN) {
                    submit();
                    return true;
                }
                return false;
            }
        });

        // 演示页窄屏档：.ic margin-left:14px / 输入框自己 padding 0 8px；
        // 左右各留 14px，胶囊内部看起来才对称。
        int side = Math.round(dp(14));
        addView(iconSearch, new LayoutParams(Math.round(dp(17)), Math.round(dp(17))));
        addView(attachChip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(dp(22))));
        ((LayoutParams) attachChip.getLayoutParams()).setMargins(Math.round(dp(6)), 0, 0, 0);
        addView(input, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setPadding(side, 0, side, 0);

        // 点空白处也聚焦输入框（iOS 搜索框就是这个手感）—— 现在这句只在"编辑态"成立，
        // 未编辑时点一下由服务侧翻成"进入编辑"（状态 2 的单击语义）。
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { keepCaretSolid(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        input.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                keepCaretSolid();
                if (listener != null) listener.onTapEdit();
            }
        });
        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onTapEdit();
            }
        });
        setAlpha(0f);
    }

    private float dp(float v) { return v * d; }

    public void setListener(Listener l) { this.listener = l; }
    public EditText input() { return input; }

    public String text() {
        return input.getText() == null ? "" : input.getText().toString();
    }

    public void setText(String s) {
        input.setText(s == null ? "" : s);
        if (s != null && !s.isEmpty()) input.setSelection(input.getText().length());
    }

    public void setHint(String hint) {
        input.setHint(hint == null ? "" : hint);
    }

    /** 附件小标签：传 null/空 就收起来。 */
    public void setAttachmentName(String name) {
        if (name == null || name.isEmpty()) {
            attachChip.setVisibility(GONE);
            return;
        }
        String n = name;
        if (n.length() > 12) n = "…" + n.substring(n.length() - 11);
        attachChip.setText(n);
        attachChip.setVisibility(VISIBLE);
    }

    private void submit() {
        String t = text().trim();
        if (listener != null) listener.onSubmit(t);
    }

    public void focusInput() {
        try {
            input.setFocusableInTouchMode(true);
            input.requestFocus();
        } catch (Throwable ignored) { }
    }

    /** 退出编辑态（状态 2 刚落定、或点了输入框以外时）。 */
    public void blurInput() {
        try {
            input.clearFocus();
            setFocusableInTouchMode(false);
            setFocusable(false);
        } catch (Throwable ignored) { }
    }

    public boolean isEditing() { return input.hasFocus(); }

    // ── 手势判定：窗口级，点在输入框里也收得到 ────────────────────────────
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                gestureTwo = false;
                gestureFired = false;
                removeCallbacks(addHold);
                removeCallbacks(twoHold);
                postDelayed(addHold, ADD_HOLD_MS);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    gestureTwo = true;
                    removeCallbacks(addHold);
                    removeCallbacks(twoHold);
                    postDelayed(twoHold, TWO_HOLD_MS);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!gestureFired && !gestureTwo
                        && (Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY)) > dp(8)) {
                    removeCallbacks(addHold);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(addHold);
                removeCallbacks(twoHold);
                break;
        }
        boolean handled = super.dispatchTouchEvent(e);
        // 长按已经触发过：把这一串剩下的 up/cancel 吃掉，避免它再被当成"点了一下输入框"
        if (gestureFired && (e.getActionMasked() == MotionEvent.ACTION_UP
                || e.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
            gestureFired = false;
            gestureTwo = false;
            return true;
        }
        return handled;
    }

    // ── 凝聚（materialize）───────────────────────────────────────────────
    /** 从"一团模糊"凝聚成清晰内容：模糊 9dp→0 + 淡入，550ms（演示页 @keyframes mat）。 */
    public void materialize(final boolean on, long delayMs) {
        if (on) startCaretLoop();
        if (matAnim != null) { matAnim.cancel(); matAnim = null; }
        final float from = material;
        final float to = on ? 1f : 0f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(on ? 550 : 160);
        a.setStartDelay(Math.max(0, delayMs));
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator an) {
                float p = (Float) an.getAnimatedValue();
                applyMaterial(from + (to - from) * p);
            }
        });
        matAnim = a;
        a.start();
    }

    private void applyMaterial(float m) {
        material = Math.max(0f, Math.min(1f, m));
        setAlpha(material);
        applyBlur((1f - material) * dp(9));
    }

    // 「凝聚」的那层模糊：Android 12 才有 RenderEffect.createBlurEffect，
    // 而本项目的 android.jar 是 API 30（编译期根本没有这个类）→ 反射拿一次、缓存住；
    // 拿不到就只淡入（功能一样，只是少了那点"从模糊里浮出来"的味道）。
    private static java.lang.reflect.Method M_SET_EFFECT, M_CREATE_BLUR;
    private static boolean effectProbed;

    private static void probeRenderEffect() {
        if (effectProbed) return;
        effectProbed = true;
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            M_CREATE_BLUR = re.getMethod("createBlurEffect",
                    float.class, float.class, Shader.TileMode.class);
            M_SET_EFFECT = View.class.getMethod("setRenderEffect", re);
        } catch (Throwable t) {
            M_CREATE_BLUR = null;
            M_SET_EFFECT = null;
        }
    }

    private void applyBlur(float radiusPx) {
        probeRenderEffect();
        if (M_SET_EFFECT == null) return;
        try {
            Object eff = radiusPx < 0.4f ? null
                    : M_CREATE_BLUR.invoke(null, radiusPx, radiusPx, Shader.TileMode.CLAMP);
            M_SET_EFFECT.invoke(this, new Object[]{eff});
        } catch (Throwable ignored) { }
    }

    /** 被藏起来时把模糊/透明度复位，免得下次出现时是糊的。 */
    public void resetMaterial() { applyMaterial(0f); }

    // ── 发光光标（演示页 #caret：2px 白条 + 两层白光晕 + 1.12s step-end 闪烁）──────
    private final Paint caretGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caretBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF caretRect = new RectF();
    private long caretSolidUntil = 0;     // 打字时先常亮一小会儿（演示页 input 事件里也是这个手法）
    private boolean caretLoopRunning = false;

    private final Runnable caretTick = new Runnable() {
        @Override public void run() {
            caretLoopRunning = false;
            if (getVisibility() != VISIBLE || getWindowToken() == null) return;
            invalidate();
            caretLoopRunning = true;
            postDelayed(this, 60);
        }
    };

    private void startCaretLoop() {
        if (caretLoopRunning) return;
        caretLoopRunning = true;
        postDelayed(caretTick, 60);
    }

    /** 打字 / 移光标 → 光标先常亮 120ms（演示页是 input 时把 animation 掐掉再放开）。 */
    private void keepCaretSolid() { caretSolidUntil = android.os.SystemClock.uptimeMillis() + 120; }

    @Override protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv);
        drawCaret(cv);
    }

    private void drawCaret(Canvas cv) {
        if (input == null || !input.hasFocus()) return;
        CharSequence cs = input.getText();
        int sel = input.getSelectionStart();
        if (cs == null || sel < 0) return;
        long t = android.os.SystemClock.uptimeMillis();
        // 演示页 @keyframes blink：1.12s 里前 60% 亮、后 40% 灭（step-end）
        boolean on = t < caretSolidUntil || (t % 1120L) < 672L;
        if (!on) return;
        String upto = cs.subSequence(0, Math.min(sel, cs.length())).toString();
        float x = input.getLeft() + input.getPaddingLeft() - input.getScrollX()
                + input.getPaint().measureText(upto);
        float w = dp(2), h = dp(19);                  // 窄屏档 caret 高 19px
        float cy = getHeight() * 0.5f;
        x = Math.max(dp(2), Math.min(x, input.getRight() - dp(12)));
        caretRect.set(x, cy - h * 0.5f, x + w, cy + h * 0.5f);
        // 两层光晕（对应 box-shadow 0 0 5px / 0 0 14px）+ 本体白条
        caretGlow.setColor(0x80FFFFFF);
        caretGlow.setMaskFilter(new android.graphics.BlurMaskFilter(dp(9), android.graphics.BlurMaskFilter.Blur.NORMAL));
        cv.drawRoundRect(caretRect, dp(1), dp(1), caretGlow);
        caretGlow.setColor(0xF2FFFFFF);
        caretGlow.setMaskFilter(new android.graphics.BlurMaskFilter(dp(4), android.graphics.BlurMaskFilter.Blur.NORMAL));
        cv.drawRoundRect(caretRect, dp(1), dp(1), caretGlow);
        caretGlow.setMaskFilter(null);
        caretBar.setColor(0xFFFFFFFF);
        cv.drawRoundRect(caretRect, dp(1), dp(1), caretBar);
    }
}

/**
 * 搜索胶囊里的放大镜图标。
 *
 * 路径直接照抄 metaball-search.html 里那段 SVG（viewBox 20×20，stroke 1.6、
 * 圆头圆角），画的时候整体缩放到控件尺寸 —— 不依赖任何字体或 drawable 资源。
 */
class IconView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int iconColor = 0xCCFFFFFF;

    IconView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(1.6f);
        stroke.setColor(iconColor);
    }

    void setIconColor(int c) {
        iconColor = c;
        stroke.setColor(c);
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float cx = getPaddingLeft() + w * 0.5f;
        float cy = getPaddingTop() + h * 0.5f;
        float s = Math.min(w, h);
        if (s <= 1f) return;

        cv.save();
        cv.translate(cx - s * 0.5f, cy - s * 0.5f);
        cv.scale(s / 20f, s / 20f);          // 之后一律按演示页那套 20×20 坐标下笔
        build();
        cv.drawPath(path, stroke);
        cv.restore();
    }

    private void build() {
        path.reset();
        path.addCircle(8.5f, 8.5f, 5.5f, Path.Direction.CW);
        path.moveTo(12.8f, 12.8f);
        path.lineTo(17f, 17f);
    }
}
