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
 *
 * 所以这里不碰球/玻璃的几何：玻璃还是原来那块（{@code updateSiriTextPos} 算出来多大就多大），
 * 只是把玻璃里**原本写字的那块矩形**盖上一层 WebView，加载本机 DSH 的 Web 前端，
 * 并把它按标题点进当前那条会话。玻璃的黑顶、波头、输入行全部保持原样。
 *
 * 三条硬约束（都是这个工程踩过的坑）：
 *  · **不抢焦点**：窗口一律 FLAG_NOT_FOCUSABLE —— 回复页不许吃掉前台 App 的返回键
 *    （见 HANDOFF 2026-11-09 第四版）。打字仍然走球自己那条输入行。
 *  · **不抢触摸**：FLAG_NOT_TOUCH_MODAL，网页矩形以外的点按照常落到球/桌面上。
 *  · **不动几何**：只 updateViewLayout，绝不改球窗/文字窗的大小。
 *
 * 兼容层：系统 WebView 是 Chrome 116，而 DSH 前端用了 Promise.withResolvers（119）等新 API。
 * 主文档自己带 cookie 取回来、在 <head> 后插一段 shim 再交给 WebView（见 {@link #fetchAndShim}）。
 */
final class WebChatPane {

    private static final String TAG = "WebChatPane";

    /** 页面加载完后按这些时刻试着点开会话（前端异步渲染，一次点不中很正常）。 */
    private static final long[] OPEN_AT = {500, 1200, 2200, 3400, 5000, 7000};

    private final Context ctx;
    private final WindowManager wm;

    private FrameLayout frame;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private boolean added;
    private boolean shown;

    private int lastX = Integer.MIN_VALUE, lastY, lastW, lastH;

    private String base;
    private volatile String cookie;
    private boolean loaded;

    /** 现在网页里开的是哪条会话 / 想开哪条。 */
    private String sessionId;
    private String wantSid, wantTitle;
    private int attempt;
    private boolean hit;
    private int loadGen;
    private boolean resolvingTitle;
    private boolean latestTried;
    private int verifyFails = 0;
    private long showLogAt;

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
            } catch (Throwable t) {
                Log.w(TAG, "addView: " + t);
                return;
            }
        }
        if (frame.getVisibility() != View.VISIBLE) {
            frame.setVisibility(View.VISIBLE);
            Log.i(TAG, "VISIBLE " + w + "x" + h + " @(" + x + "," + y + ")");
        }
        if (showLogAt == 0 || System.currentTimeMillis() - showLogAt > 3000) {
            showLogAt = System.currentTimeMillis();
            Log.i(TAG, "show: vis=" + frame.getVisibility() + " added=" + added
                    + " size=" + w + "x" + h + " @" + x + "," + y);
        }
        if (!loaded) load();
        if (hit) scrollToEndSoon();
    }

    void hide() {
        shown = false;
        if (frame != null && frame.getVisibility() != View.GONE) {
            frame.setVisibility(View.GONE);
            Log.i(TAG, "GONE");
        }
    }

    /** 换会话：网页已经加载好就重新点一条；还在加载就等 onPageFinished。 */
    void setSession(String sid, String title) {
        if (sid == null || sid.isEmpty()) return;
        if (sid.equals(wantSid)) return;
        wantSid = sid;
        wantTitle = (title == null || title.trim().isEmpty()) ? null : title.trim();
        attempt = 0;
        hit = false;
        verifyFails = 0;
        if (!sid.equals(sessionId)) Log.i(TAG, "会话 → " + sid + "（" + wantTitle + "）");
        if (loaded) {
            resetPageState();
            scheduleOpen();
        }
    }

    /** 目标会话的标题（DSH 侧栏里显示的那一行）——不知道就问一次 session/list。 */
    private void resolveTitle(String sid, int gen) {
        if (wantTitle != null || resolvingTitle) return;
        resolvingTitle = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String t = null;
                try {
                    for (DshApi.SessionRow r : DshApi.get(ctx).sessions()) {
                        if (sid.equals(r.id)) { t = r.title; break; }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "查会话标题失败: " + e);
                }
                final String ft = t;
                resolvingTitle = false;
                if (ft == null) return;
                Log.i(TAG, "会话标题 = 「" + ft + "」");
                wantTitle = ft;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override public void run() {
                        if (gen == loadGen && loaded) scheduleOpen();
                    }
                });
            }
        }, "pane-title").start();
    }

    // ── 建视图 ────────────────────────────────────────────────────────────

    private void ensure() {
        if (frame != null) return;
        final int radius = dp(30);

        frame = new FrameLayout(ctx);
        frame.setBackgroundColor(0xFF151517);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);

        web = new WebView(ctx);
        web.setBackgroundColor(0xFF151517);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
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
                scheduleOpen();
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

        lp = new WindowManager.LayoutParams();
        lp.type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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
        if (sid != null) resolveTitle(sid, gen);
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
                        Log.i(TAG, "load " + url + (wantTitle == null ? "" : " → 「" + wantTitle + "」"));
                        web.loadUrl(url);
                    }
                });
            }
        }, "pane-load").start();
    }

    // ── 定位到那条会话 ────────────────────────────────────────────────────

    private void scheduleOpen() {
        if (wantTitle == null) {
            // 知道是哪条会话（有 sid）就等标题 —— 绝不拿"最近那条"顶替，否则玻璃里会开错对话。
            if (wantSid != null) resolveTitle(wantSid, loadGen);
            else resolveLatest();
            return;
        }
        if (hit || attempt >= OPEN_AT.length) {
            if (!hit) Log.i(TAG, "没找到「" + wantTitle + "」，停在 DSH 首页");
            return;
        }
        long delay = attempt == 0 ? OPEN_AT[0] : OPEN_AT[attempt] - OPEN_AT[attempt - 1];
        attempt++;
        web.postDelayed(new Runnable() {
            @Override public void run() { runOpen(); }
        }, Math.max(150, delay));
    }

    private void runOpen() {
        if (web == null || wantTitle == null || hit) return;
        String js = OPEN_SESSION_JS.replace("__WANT__", org.json.JSONObject.quote(wantTitle));
        web.evaluateJavascript(js, value -> {
            Log.i(TAG, "open-session #" + attempt + " → " + value);
            if (value != null && value.contains("hit")) {
                hit = true;
                sessionId = wantSid;
                scrollToEndSoon();
                verifyOpen();
            } else {
                scheduleOpen();
            }
        });
    }

    /**
     * 不知道是哪条会话时：**直接开最近更新那条**。
     * 球里那段回复十有八九就是它；开错了用户也能在网页里自己点。
     */
    private void resolveLatest() {
        if (latestTried || web == null) return;
        latestTried = true;
        final int gen = loadGen;
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
                        if (gen != loadGen || web == null) return;
                        wantSid = fi;
                        wantTitle = ft;
                        attempt = 0;
                        hit = false;
                        resetPageState();
                        scheduleOpen();
                    }
                });
            }
        }, "pane-latest").start();
    }

    /**
     * 点完再看一眼：对话区到底开没开（`data-conversation-*` 是 DSH 前端对话区的标记）。
     * 没开就清掉"点过了"的标记再试一次 —— 光看 click 有没有派发出去不算数。
     */
    private void verifyOpen() {
        if (web == null) return;
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (web == null || !hit) return;
                web.evaluateJavascript(VERIFY_JS, v -> {
                    Log.i(TAG, "verify → " + v);
                    boolean open = v != null && v.contains("open");
                    if (open) return;
                    if (verifyFails++ >= 2) {
                        Log.w(TAG, "对话区一直没开，停在 DSH 首页（用户可自己点）");
                        return;
                    }
                    hit = false;
                    sessionId = null;
                    resetPageState();
                    scheduleOpen();
                });
            }
        }, 1200);
    }

    private static final String VERIFY_JS =
            "(function(){var n=document.querySelectorAll("
          + "'[data-conversation-region],[data-conversation-scroll],[data-conversation-session]').length;"
          + "var t=(document.body.innerText||'').replace(/\\s+/g,' ').slice(0,70);"
          + "return (n>0?'open':'closed')+'#'+n+':'+t;})()";

    private void resetPageState() {
        if (web == null) return;
        web.evaluateJavascript(
                "window.__dshWcDone=0;window.__dshWcSidebar=0;", null);
    }

    /** 打开后 / 每次都把对话滚到最新那一头（玻璃里只能看到一屏）。 */
    private void scrollToEndSoon() {
        if (web == null) return;
        web.postDelayed(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript(
                        "(function(){var els=document.querySelectorAll('[data-conversation-scroll]');"
                      + "for(var i=0;i<els.length;i++){els[i].scrollTop=els[i].scrollHeight;}"
                      + "window.scrollTo(0,document.body.scrollHeight);return 'scroll';})()", null);
            }
        }, 450);
    }

    void destroy() {
        shown = false;
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
    //  下面两块和 WebChatActivity 里的是同一套（主文档注入兼容层 / 按标题点会话）
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
        // 玻璃里那块很小：DSH 页面自己的输入条占掉一大截，而这块窗**不抢焦点**（打不了字），
        // 留着只会白占地方 —— 藏掉；打字走球自己那条输入行（发出去网页会实时收到）。
        String css = "<style>[data-conversation-composer-overlay],[data-composer-overlay]"
                + "{display:none!important}</style>";
        String shim = css + "<script>" + SHIM_JS + "</script>";
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

    private static final String OPEN_SESSION_JS =
            "(function(){try{"
          + "if(window.__dshWcDone)return 'done';"
          + "var want=__WANT__;if(!want)return 'nowant';"
          + "var norm=function(s){return (s||'').replace(/\\s+/g,'').toLowerCase();};"
          + "var w=norm(want);if(!w)return 'nowant';"
          + "var vis=function(e){var r=e.getBoundingClientRect();if(r.width<4||r.height<4)return false;"
          + "if(r.bottom<0||r.top>innerHeight)return false;var s=getComputedStyle(e);"
          + "return s.visibility!=='hidden'&&s.display!=='none'&&+s.opacity>0.05;};"
          + "var all=document.querySelectorAll('body *'),best=null,score=-1;"
          + "for(var i=0;i<all.length;i++){var e=all[i];if(!vis(e))continue;"
          + "  var t=norm(e.textContent);if(!t||t.length<w.length||t.indexOf(w)<0)continue;"
          + "  var r=e.getBoundingClientRect();"
          + "  if(r.height>140||r.width>innerWidth*0.92)continue;"
          + "  var sc=(t===w?100:60)-Math.min(t.length-w.length,40);"
          + "  if(r.left<innerWidth*0.5)sc+=30;"
          + "  if(!e.children.length)sc+=10;"
          + "  if(sc>score){score=sc;best=e;} }"
          + "if(best){var t2=best;"
          + "  for(var d=0;d<5&&t2&&t2!==document.body;d++){var cs=getComputedStyle(t2);"
          + "    if(cs.cursor==='pointer'||t2.tagName==='BUTTON'||t2.tagName==='A'"
          + "       ||t2.getAttribute('role')||t2.onclick)break;t2=t2.parentElement;}"
          + "  if(!t2||t2===document.body)t2=best;"
          + "  ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(ev){"
          + "    try{t2.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,view:window}));}catch(e){}});"
          + "  try{t2.click();}catch(e){}"
          + "  window.__dshWcDone=1;return 'hit:'+score+':'+best.tagName;}"
          + "if(!window.__dshWcSidebar){"
          + "  var ctl=null,els=document.querySelectorAll('button,[role=button],a,[aria-label],[title]');"
          + "  for(var j=0;j<els.length;j++){var q=els[j];if(!vis(q))continue;"
          + "    var lab=(q.getAttribute('aria-label')||'')+' '+(q.getAttribute('title')||'');"
          + "    if(/侧边栏|sidebar/i.test(lab)&&!/账号|账户|account/i.test(lab)){"
          + "      var rr=q.getBoundingClientRect();if(rr.width<90&&rr.height<90){ctl=q;break;}}}"
          + "  if(ctl){window.__dshWcSidebar=1;ctl.click();return 'expand';}}"
          + "return 'miss';"
          + "}catch(err){return 'err:'+err;}})()";
}
