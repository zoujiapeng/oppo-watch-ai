package com.glslplayground.app;

import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 实验：**回复页那块玻璃里直接放 DSH 前端网页的对话页** —— 不是手搓的 markdown 排版。
 *
 * 用户口径（2026-11-09）：
 *   「是 dsh 前端网页的对话页面显示在回复页面的玻璃上」
 *   「缩小看不清也没关系，不用改变原本玻璃的大小」
 *   「网页要显示对应的会话的具体的 dsh 前端的对话部分（EvIC1a_column）」
 *
 * 所以这里不碰球/玻璃的几何：玻璃还是原来那块（{@link FloatingBallService#updateSiriTextPos()}
 * 算出来多大就多大），只是把玻璃里**原本写字的那块矩形**盖上一层 WebView，加载本机 DSH 的
 * Web 前端，并且：
 *
 *  · **只显示那一条会话的对话列**：页面里的 {@code [data-chat-flow]}（CSS 类
 *    {@code EvIC1a_column} 的哈希前缀会随构建变，这个属性不会）以外的东西（左栏/顶栏/
 *    输入条/右侧栏）全部藏掉，这一列撑满 WebView —— 玻璃里就是 DSH 自己的对话，不是整张应用。
 *  · **按 session id 精确开那一条**：左栏每一行是 {@code [data-row-key="session:<id>"]}，
 *    点它，然后核对对话区的 {@code [data-conversation-region="chat"][data-conversation-session]}
 *    真的是这个 id —— 对不上就重试，绝不拿"最近那条"顶替。
 *
 * 三条硬约束（都是这个工程踩过的坑）：
 *  · **不抢焦点**：窗口一律 FLAG_NOT_FOCUSABLE —— 回复页不许吃掉前台 App 的返回键。
 *    打字仍然走球自己那条输入行（发出去网页实时收到）。
 *  · **不抢触摸**：FLAG_NOT_TOUCH_MODAL，网页矩形以外的点按照常落回球/桌面。
 *  · **不动几何**：只 updateViewLayout，绝不改球窗/文字窗的大小。
 *
 * 兼容层：系统 WebView 是 Chrome 116，而 DSH 前端用了 Promise.withResolvers（119）等新 API。
 * 主文档自己带 cookie 取回来、在 <head> 后插一段 shim 再交给 WebView（见 {@link #fetchAndShim}）。
 */
final class WebChatPane {

    private static final String TAG = "WebChatPane";

    /**
     * 回复页玻璃 / 玻璃里那块网页**共用**的圆角（dp）。
     *
     * 用户 2026-11-10：「把回复页面的玻璃的圆角弄成和网页圆角一致」—— 所以两边不再各写各的：
     * 网页的裁剪圆角用这个值，FloatingBallService 算玻璃几何时也用这个值
     * （{@code tCorner = dp(CORNER_DP) / hypx}），曲率一模一样。
     */
    static final int CORNER_DP = 30;

    private final Context ctx;
    private final WindowManager wm;

    private FrameLayout frame;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private boolean added;
    private boolean shown;

    private int lastX = Integer.MIN_VALUE, lastY, lastW, lastH;

    /** DSH 前端地址 + 和 RPC 共用的签名 cookie（主文档自己取回来插兼容层用）。 */
    private String base;
    private volatile String cookie;
    private boolean loaded;

    /** 网页里现在开的是哪条会话 / 想开哪条（都是 DSH 的 session id）。 */
    private String sessionId;
    private String wantSid;
    private int loadGen;
    /** 页面里的控制器（{@link WebChatJs#CONTROLLER_JS}）已经装好没有。 */
    private boolean controllerReady;
    private long showLogAt;
    /** 已经应用过的网页字号（设置页随时能改，这里跟着变）。 */
    private int lastZoom = 100;
    /** 网页长过输入行的那一截（px）：页面里用 padding-bottom 让开。 */
    private int insetPx = 0;
    private int appliedInsetCss = -1;

    WebChatPane(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
    }

    boolean isShown() { return shown; }

    /** 这一帧该不该出现在屏幕上（位置/大小每次都会更新，玻璃怎么动它就怎么跟）。 */
    void show(int x, int y, int w, int h) {
        if (w < 40 || h < 40) return;
        ensure();
        if (lp == null) return;
        shown = true;
        boolean moved = x != lastX || y != lastY || w != lastW || h != lastH;
        if (moved) {
            lastX = x; lastY = y; lastW = w; lastH = h;
            lp.x = x; lp.y = y; lp.width = w; lp.height = h;
            if (added) {
                try { wm.updateViewLayout(frame, lp); } catch (Throwable t) { Log.w(TAG, "move: " + t); }
            }
        }
        if (!added) {
            lp.x = x; lp.y = y; lp.width = w; lp.height = h;
            try {
                wm.addView(frame, lp);
                added = true;
                Log.i(TAG, "贴上玻璃 " + w + "x" + h + " @(" + x + "," + y + ")");
                frame.post(new Runnable() {
                    @Override public void run() {
                        if (frame == null) return;
                        int[] loc = new int[2];
                        frame.getLocationOnScreen(loc);
                        Log.i(TAG, "pane at " + frame.getWidth() + "x" + frame.getHeight()
                                + " @(" + loc[0] + "," + loc[1] + ")");
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "addView: " + t);
                return;
            }
        }
        applyZoomIfChanged();
        applyInsetIfChanged();
        if (frame.getVisibility() != View.VISIBLE) {
            frame.setVisibility(View.VISIBLE);
            int[] loc = new int[2];
            frame.getLocationOnScreen(loc);
            Log.i(TAG, "VISIBLE " + w + "x" + h + " @(" + x + "," + y + ") 实际="
                    + frame.getWidth() + "x" + frame.getHeight() + " @(" + loc[0] + "," + loc[1] + ")");
        }
        if (showLogAt == 0 || System.currentTimeMillis() - showLogAt > 3000) {
            showLogAt = System.currentTimeMillis();
            Log.i(TAG, "show: vis=" + frame.getVisibility() + " added=" + added
                    + " size=" + w + "x" + h + " @" + x + "," + y);
        }
        if (!loaded) load();
    }

    /**
     * 网页窗比输入行还长的那一截：页面底部留出同样的空白，最新一行不会被输入行压住。
     * 只在真变了才下发一次（每帧都会调用）。
     */
    void setBottomInset(int px) {
        insetPx = Math.max(0, px);
        applyInsetIfChanged();
    }

    private void applyInsetIfChanged() {
        if (web == null || !controllerReady) return;
        int css = Math.round(insetPx / ctx.getResources().getDisplayMetrics().density);
        if (css == appliedInsetCss) return;
        appliedInsetCss = css;
        web.evaluateJavascript("window.__dshWc&&window.__dshWc.setPadBottom(" + css + ")", null);
        Log.i(TAG, "网页底部让开 " + css + "px（输入行）");
    }

    /**
     * 设置页改了「网页缩放」→ 下一帧就跟上。
     * 走页面里的 {@code document.documentElement.style.zoom}：这就是浏览器式缩放 ——
     * 布局视口跟着变窄/变宽，DSH 自己的排版会重排（不是把整页糊成一张缩略图）。
     */
    private void applyZoomIfChanged() {
        if (web == null) return;
        int z = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
                .getInt("web_chat_zoom", 100);
        if (z == lastZoom) return;
        lastZoom = z;
        applyZoom(z);
    }

    private void applyZoom(int z) {
        if (web == null) return;
        Log.i(TAG, "网页缩放 → " + z + "%");
        web.evaluateJavascript("window.__dshWc?window.__dshWc.setZoom(" + z + "):"
                + "(document.documentElement.style.zoom='" + (z / 100f) + "')"
                + ";(window.__dshWc&&window.__dshWc.status())", v -> Log.i(TAG, "zoom → " + v));
    }

    void hide() {
        shown = false;
        if (frame != null && frame.getVisibility() != View.GONE) {
            frame.setVisibility(View.GONE);
            Log.i(TAG, "GONE");
        }
    }

    /**
     * 换会话：网页已经装好控制器就立刻让页面切过去（按 session id 精确点左栏那一行）；
     * 还在加载就等 onPageFinished 一次性装好再切。
     */
    void setSession(String sid, String title) {
        if (sid == null || sid.isEmpty()) return;
        if (sid.equals(wantSid)) return;
        wantSid = sid;
        if (!sid.equals(sessionId)) Log.i(TAG, "会话 → " + sid + "（" + title + "）");
        if (controllerReady) applySession();
    }

    // ── 建视图 ────────────────────────────────────────────────────────────

    private void ensure() {
        if (frame != null) return;
        final int radius = dp(CORNER_DP);

        frame = new FrameLayout(ctx);
        frame.setBackgroundColor(0xFF000000);      // 纯黑：和网页主题同一个底色，圆角外不露灰边
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);

        web = new WebView(ctx);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 页面自己有 <meta name="viewport" content="width=device-width">：
        // 这块 WebView 有多宽，DSH 就按多宽排（不整页缩小成一张缩略图）。
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        // 浏览器式操作：双指缩放 / 双击放大（缩放控件不显示，玻璃里没地方放它）
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        lastZoom = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
                .getInt("web_chat_zoom", 100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " DshBallGlass/1");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "console " + m.messageLevel() + ": " + m.message());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "page finished " + url + " title=" + v.getTitle());
                installController();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return false;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    if (req != null && req.isForMainFrame()
                            && base != null && req.getUrl().toString().startsWith(base)) {
                        return fetchAndShim(req.getUrl().toString());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "intercept: " + t);
                }
                return null;
            }
        });

        frame.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // 玻璃里的网页要能像浏览器一样滑：先确认这块窗真的收得到触摸（收不到就查窗口层级，
        // 别在页面里瞎找）。只打 DOWN/UP，不刷屏。
        frame.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_UP) {
                Log.i(TAG, "pane touch " + (a == android.view.MotionEvent.ACTION_DOWN ? "DOWN" : "UP")
                        + " @" + (int) e.getRawX() + "," + (int) e.getRawY());
            }
            return false;
        });

        lp = new WindowManager.LayoutParams();
        lp.type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                // 注意：**不要**加 FLAG_LAYOUT_IN_SCREEN。文字窗/玻璃窗都是 overlay，坐标原点
                // 同样在状态栏下面（系统统一 +90px）；单独给网页加这个 flag 会让它比玻璃高 90px，
                // 跑到玻璃外面去。三者必须用同一套坐标系，网页才正好盖在"写字那块矩形"上。
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.format = android.graphics.PixelFormat.TRANSLUCENT;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        lp.alpha = 1f;
        base = DshApi.get(ctx).endpoint();
    }

    private void load() {
        loaded = true;
        final int gen = ++loadGen;
        final String sid = wantSid;
        new Thread(new Runnable() {
            @Override public void run() {
                String c0 = null;
                try { c0 = DshApi.get(ctx).cookie(); }
                catch (Throwable t) { Log.w(TAG, "cookie: " + t); }
                final String c = c0;
                cookie = c;
                final String url = (base.endsWith("/") ? base : base + "/");
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGen || web == null) return;
                        try {
                            if (c != null && !c.isEmpty()) {
                                String host = android.net.Uri.parse(base).getHost();
                                CookieManager.getInstance().setCookie(base, c);
                                CookieManager.getInstance().setCookie("http://" + host, c);
                                CookieManager.getInstance().flush();
                            }
                        } catch (Throwable t) { Log.w(TAG, "setCookie: " + t); }
                        Log.i(TAG, "load " + url + (sid == null ? "" : " sid=" + sid));
                        web.loadUrl(url);
                    }
                });
            }
        }, "pane-load").start();
    }

    // ── 页面内控制器：装一次，之后只喂 session id ─────────────────────────

    private void installController() {
        if (web == null) return;
        web.evaluateJavascript(WebChatJs.CONTROLLER_JS, v -> {
            controllerReady = true;
            Log.i(TAG, "controller " + v);
            applyZoom(lastZoom);            // 页面刚加载：把设置里的缩放补上
            appliedInsetCss = -1;           // 控制器刚装好：底部让开的量也补一次
            applyInsetIfChanged();
            if (wantSid == null) resolveLatest();
            else applySession();
            logStatusSoon(2500);
            logStatusSoon(7000);
        });
    }

    private void applySession() {
        if (web == null) return;
        final String sid = wantSid;
        if (sid == null) return;
        sessionId = sid;
        web.evaluateJavascript(WebChatJs.setSession(sid, true), v -> {
            Log.i(TAG, "只显示这条会话的对话列 → " + v);
            web.postDelayed(new Runnable() {
                @Override public void run() { scrollToBottom(); }
            }, 600);
            logStatusSoon(3000);
        });
    }

    /** 不知道是哪条会话时：打开**最近更新**那条（写进 wantSid，之后一样走精确点击）。 */
    private void resolveLatest() {
        if (web == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String id = null, title = null;
                try {
                    java.util.List<DshApi.SessionRow> all = DshApi.get(ctx).sessions();
                    if (!all.isEmpty()) { id = all.get(0).id; title = all.get(0).title; }
                } catch (Throwable e) {
                    Log.w(TAG, "取最近会话失败: " + e);
                }
                final String fi = id, ft = title;
                if (fi == null) return;
                Log.i(TAG, "没指定会话 → 开最近的「" + ft + "」");
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override public void run() {
                        if (web == null) return;
                        wantSid = fi;
                        applySession();
                    }
                });
            }
        }, "pane-latest").start();
    }

    private void scrollToBottom() {
        if (web == null) return;
        web.evaluateJavascript("window.__dshWc&&window.__dshWc.scrollBottom()", null);
    }

    /** 调试：把页面里的状态打在日志里（玻璃里到底显示的是哪条会话、有没有聚焦到列）。 */
    private void logStatusSoon(long delayMs) {
        if (web == null) return;
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                web.evaluateJavascript(WebChatJs.STATUS_JS,
                        v -> Log.i(TAG, "status → " + v));
            }
        }, delayMs);
    }

    /** 调试直通：`am startservice … --es wc_js "…"` 在网页里跑一段 JS 并把结果打进日志。 */
    void debugEval(String js) {
        if (web == null || js == null) return;
        web.evaluateJavascript(js, v -> Log.i(TAG, "wc_js → " + v));
    }

    void destroy() {
        shown = false;
        controllerReady = false;
        sessionId = null;
        wantSid = null;
        if (frame != null) {
            try { if (added) wm.removeView(frame); } catch (Throwable ignored) { }
            frame = null;
        }
        added = false;
        web = null;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Chrome 116 兼容层（和 WebChatActivity 里的是同一段）
    // ══════════════════════════════════════════════════════════════════════

    /** 主文档自己取（带 cookie），在 <head> 后插 shim —— 保证先于页面脚本执行。 */
    private WebResourceResponse fetchAndShim(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept-Encoding", "identity");
        String ck = cookie;
        if (ck != null) c.setRequestProperty("cookie", ck);
        int code = c.getResponseCode();
        if (code != 200) {
            Log.w(TAG, "main doc HTTP " + code + " → 交给 WebView");
            return null;
        }
        String html = new String(readAll(c.getInputStream()), "utf-8");
        // 纯黑主题：<head> 里先钉一段黑色底 + 强制走暗色面板，再装 Chrome 116 兼容层。
        // 顺序很要紧 —— 两段都必须在 DSH 自己的脚本之前跑（它的主题 boot 脚本在 <body> 末尾）。
        String shim = "<style id=\"dshwc-black\">" + BLACK_THEME_CSS + "</style>"
                + "<script>" + BLACK_THEME_JS + "</script>"
                + "<script>" + SHIM_JS + "</script>";
        int head = html.indexOf("<head");
        int at = head < 0 ? -1 : html.indexOf('>', head);
        if (at > 0) html = html.substring(0, at + 1) + shim + html.substring(at + 1);
        else html = shim + html;
        Map<String, String> h = new HashMap<>();
        h.put("content-type", "text/html; charset=utf-8");
        h.put("cache-control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", h,
                new ByteArrayInputStream(html.getBytes("utf-8")));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        return out.toByteArray();
    }

    /**
     * 玻璃里那块网页的**纯黑主题**（用户 2026-11-10：「把实验功能的回复页面的网页主题弄成纯黑色」）。
     *
     * DSH 前端的暗色主题底是 #151517（`--dsw-static-neutral-bluish-950`），这里把它和
     * 各层底色一起钉成 #000：对话列铺满玻璃时，玻璃里就是纯黑，不再是灰黑。
     * 用 !important + 比 `body[data-ds-dark-theme]` 更高一级的选择器，压过前端自己的令牌。
     */
    private static final String BLACK_THEME_CSS =
            "html{color-scheme:dark!important;background:#000!important}"
          + "html body{background:#000!important;color-scheme:dark!important}"
          + "html body[data-ds-dark-theme]{"
          + "--dsh-boot-bg:#000!important;"
          + "--dsw-static-neutral-bluish-950:#000!important;"
          + "--dsw-alias-bg-base:#000!important;"
          + "--dsw-alias-bg-layer-1:#000!important;"
          + "--dsw-alias-bg-layer-2:#000!important;"
          + "--dsw-alias-bg-module-platform:#000!important;"
          + "--dsw-alias-bg-document-preview:#000!important;"
          + "--dsw-linear-gradient-think:linear-gradient(180deg,#000 20.19%,#00000000 100%)!important;"
          + "}";

    /**
     * 强制暗色：WebView 的系统配色跟着**宿主 App**走，App 是浅色 → DSH 前端会选浅色主题
     * （玻璃里那张白底截图就是这么来的）。这里两件事：
     *  ① 把 `(prefers-color-scheme: dark)` 的查询结果钉成 true —— DSH 的主题运行时装好时
     *     就会按暗色建快照（偏好是 system 时直接生效）；
     *  ② 万一偏好被存成 light / 别处又把属性摘掉：盯住 body，`data-ds-dark-theme` 缺了就补。
     * 只影响这块玻璃里的 WebView，不动 App 全局。
     */
    private static final String BLACK_THEME_JS =
            "(function(){"
          + "try{var om=window.matchMedia;if(om){window.matchMedia=function(q){"
          + "var r=om.call(window,q);"
          + "if(!/prefers-color-scheme\\s*:\\s*dark/i.test(String(q)))return r;"
          + "return {matches:true,media:r.media,onchange:null,"
          + "addListener:function(){},removeListener:function(){},"
          + "addEventListener:function(){},removeEventListener:function(){},"
          + "dispatchEvent:function(){return false;}};};}}catch(e){}"
          + "function black(){try{var b=document.body;if(!b)return false;"
          + "if(!b.hasAttribute('data-ds-dark-theme'))b.setAttribute('data-ds-dark-theme','');"
          + "document.documentElement.style.colorScheme='dark';return true;}catch(e){return false;}}"
          + "document.addEventListener('DOMContentLoaded',black);"
          + "try{var iv=setInterval(function(){if(black()){clearInterval(iv);"
          + "try{new MutationObserver(black).observe(document.body,"
          + "{attributes:true,attributeFilter:['data-ds-dark-theme']});}catch(e){}}},50);}catch(e){}"
          + "})();";

    private static final String SHIM_JS =
            "(function(){"
          + "try{if(typeof Promise.withResolvers!=='function'){"
          + "  Promise.withResolvers=function(){var res,rej;"
          + "    var p=new Promise(function(a,b){res=a;rej=b;});"
          + "    return {promise:p,resolve:res,reject:rej};};}}catch(e){}"
          + "try{if(typeof URL.canParse!=='function'){"
          + "  URL.canParse=function(u,b){try{new URL(u,b);return true;}catch(e){return false;}};}}catch(e){}"
          + "try{if(!Array.prototype.toSorted){"
          + "  Array.prototype.toSorted=function(cmp){var a=this.slice();a.sort(cmp);return a;};}}catch(e){}"
          + "try{if(!Array.prototype.toReversed){"
          + "  Array.prototype.toReversed=function(){return this.slice().reverse();};}}catch(e){}"
          + "try{if(!Array.prototype.with){"
          + "  Array.prototype.with=function(i,v){var a=this.slice();a[i<0?a.length+i:i]=v;return a;};}}catch(e){}"
          + "})();";
}
