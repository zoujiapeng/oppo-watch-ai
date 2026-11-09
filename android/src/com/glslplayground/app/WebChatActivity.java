package com.glslplayground.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 实验：**聊天页 = DSH 前端网页本身**，不是我们手搓的会话卡。
 *
 * 用户原话（2026-11-09）：「做一个实验性的功能，就是把聊天页面直接用 dsh 前端的
 * 聊天页面的网站网页，而不是手搓的」。
 *
 * 做法：
 *  · 这个 Activity 就是一块全屏 WebView，加载本机 DSH 的 Web 前端（`DshApi.endpoint()`）；
 *  · 认证不另起一套：向 {@link DshApi#cookie()} 要那条和 RPC 共用的签名 cookie，
 *    塞进 WebView 的 CookieManager（和 {@link DshSettingsActivity} 完全一个路子）；
 *  · 从球里点某条会话进来时带 {@code session_id / session_title}：页面加载完后
 *    在左栏按标题找那一行点一下（DSH 前端没有 URL 级的路由，只能这样"点进去"）；
 *    连续几次找不到就老实停在 DSH 首页，让用户自己点 —— 绝不假装已经进去了；
 *  · 打开期间把悬浮球那一套界面藏起来（{@code FloatingBallService.setUiHidden}），
 *    退出（返回键 / 边缘手势）时原样恢复（原本就是藏着的就不动）。
 *
 * 开关在设置页「悬浮 → 网页聊天（实验）」；球里的入口在
 * 「参数面板 → 网页聊天」和「最近会话 / 目录里选会话」。
 */
public class WebChatActivity extends Activity {

    private static final String TAG = "WebChat";

    /** 要定位到哪条会话（DSH 的 session id；为空 = 只打开 DSH 前端首页）。 */
    public static final String EXTRA_SESSION_ID = "session_id";
    /** 那条会话的标题 —— 前端没有路由，只能按标题在左栏里找。 */
    public static final String EXTRA_SESSION_TITLE = "session_title";

    /** 页面加载完后按这些时刻去试着点开会话（毫秒）。前端是异步渲染，一次点不中很正常。 */
    private static final long[] OPEN_ATTEMPTS_MS = {350, 900, 1700, 2800, 4200, 6000};

    private WebView web;
    private TextView tip;
    private FrameLayout root;

    private String base;
    /** 和 RPC 共用的签名 cookie —— 主文档是我们自己带 cookie 取回来再注入兼容层的。 */
    private volatile String authCookie;
    private String wantTitle;
    private String wantSid;

    /** 打开前那一套悬浮界面是不是本来就藏着（藏着就不该在退出时把它放出来）。 */
    private boolean uiWasHidden;
    private int attempt;             // 当前第几次尝试点会话
    private boolean hit;             // 已经点中过

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();      // 全屏：DSH 前端自己就是整套界面，状态栏那一条留给它没意义
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // 刘海/挖孔屏：不声明 shortEdges 的话，顶部会留一条 90px 的黑带
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF0B0B0D);
            getWindow().setNavigationBarColor(0xFF0B0B0D);
        }

        Intent it = getIntent();
        wantSid = it == null ? null : it.getStringExtra(EXTRA_SESSION_ID);
        wantTitle = it == null ? null : it.getStringExtra(EXTRA_SESSION_TITLE);
        if (wantTitle != null) wantTitle = wantTitle.trim();
        if (wantTitle == null || wantTitle.isEmpty() || "null".equals(wantTitle)
                || "新会话".equals(wantTitle) || "对话".equals(wantTitle)) {
            // 这些不是 DSH 里真有的标题：新会话还没标题，硬找只会点到别的会话。
            wantTitle = null;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0B0D);
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
        // 一眼能认出这是 App 里的网页聊天（也方便 DSH 侧按 UA 做适配）
        s.setUserAgentString(s.getUserAgentString() + " DshBallWebChat/1");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "console " + m.messageLevel() + ": " + m.message());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                hideTip();
                Log.i(TAG, "page finished: " + url + " title=" + v.getTitle());
                scheduleOpenAttempt();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return false;                       // 全在 WebView 里走（本机 DSH，不外跳）
            }

            /**
             * 主文档自己取：系统 WebView 是 116（Chrome 116），而 DSH 前端用了
             * `Promise.withResolvers`（Chrome 119 才有）—— 不补这一层，页面一秒就白屏。
             * 走 shouldInterceptRequest 而不是 onPageStarted + evaluateJavascript：
             * 只有前者能保证兼容层**先于**页面自己的 script 执行。
             */
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    if (req != null && req.isForMainFrame()
                            && req.getUrl().toString().startsWith(base)) {
                        return fetchAndShim(req.getUrl().toString());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "intercept failed: " + t);
                }
                return null;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tip = new TextView(this);
        tip.setText("正在连接本机 DSH…");
        tip.setTextColor(0xFFB9B9C6);
        tip.setTextSize(13f);
        tip.setGravity(Gravity.CENTER);
        tip.setBackgroundColor(0xCC0B0B0D);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.BOTTOM;
        tp.bottomMargin = dip(22);
        root.addView(tip, tp);

        setContentView(root);

        // 球那一套先藏起来（网页是全屏的，球压在上面会挡字）
        uiWasHidden = FloatingBallService.isUiHidden();
        if (!uiWasHidden) FloatingBallService.setUiHidden(true, "打开网页聊天");

        final DshApi api = DshApi.get(this);
        base = api.endpoint();
        Toast.makeText(this, "DSH 网页聊天 · 返回键/边缘手势退出", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                String cookie = null;
                try { cookie = api.cookie(); }
                catch (Throwable t) { Log.w(TAG, "cookie failed: " + t); }
                final String c = cookie;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (web == null) return;
                        try {
                            if (c != null && !c.isEmpty()) {
                                String host = android.net.Uri.parse(base).getHost();
                                cm.setCookie(base, c);
                                cm.setCookie("http://" + host, c);
                                cm.flush();
                                Log.i(TAG, "cookie in: " + c.substring(0, Math.min(22, c.length())) + "…");
                            } else {
                                Log.w(TAG, "没有 cookie，直接加载（DSH 可能关了认证）");
                            }
                        } catch (Throwable t) { Log.w(TAG, "setCookie: " + t); }
                        authCookie = c;
                        String url = base.endsWith("/") ? base : base + "/";
                        Log.i(TAG, "load " + url + (wantSid == null ? "" : " (session " + wantSid + ")"));
                        web.loadUrl(url);
                    }
                });
            }
        }, "webchat").start();
    }

    // ── 兼容层：把 DSH 前端要的新 API 补在系统 WebView（116）上 ─────────────

    /** 取主文档（带 cookie），在 <head> 后插一段兼容 shim 再交给 WebView。 */
    private WebResourceResponse fetchAndShim(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (authCookie != null) c.setRequestProperty("cookie", authCookie);
        int code = c.getResponseCode();
        if (code != 200) {
            Log.w(TAG, "main doc HTTP " + code + " → 交给 WebView 自己处理");
            return null;
        }
        String html = new String(readAll(c.getInputStream()), "utf-8");
        String shim = "<script>" + SHIM_JS + "</script>";
        int head = html.indexOf("<head");
        int at = head < 0 ? -1 : html.indexOf('>', head);
        if (at > 0) html = html.substring(0, at + 1) + shim + html.substring(at + 1);
        else html = shim + html;
        Map<String, String> h = new HashMap<>();
        h.put("content-type", "text/html; charset=utf-8");
        h.put("cache-control", "no-store");
        Log.i(TAG, "main doc " + html.length() + " chars, shim injected");
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
     * 只补 DSH 前端真的用到的、Chrome 116 还没有的：
     * Promise.withResolvers（119）、URL.canParse（120）、Array#toSorted（110）。
     * 其余（structuredClone / findLast / crypto.randomUUID）116 都有，不碰。
     */
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

    private void goImmersive() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) { }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    /** singleTop：在已开的网页聊天上再点一条会话 —— 不重开 Activity，直接在里面找那条。 */
    @Override protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        String sid = it == null ? null : it.getStringExtra(EXTRA_SESSION_ID);
        String title = it == null ? null : it.getStringExtra(EXTRA_SESSION_TITLE);
        if (sid == null || sid.isEmpty()) return;
        wantSid = sid;
        wantTitle = title == null ? null : title.trim();
        if (wantTitle != null && (wantTitle.isEmpty() || "null".equals(wantTitle)
                || "新会话".equals(wantTitle) || "对话".equals(wantTitle))) wantTitle = null;
        attempt = 0;
        hit = false;
        if (web != null) web.evaluateJavascript("window.__dshWcDone=0;window.__dshWcSidebar=0;", null);
        scheduleOpenAttempt();
    }

    // ── 会话定位：前端没有 URL 路由，只能"按标题点左栏那一行" ──────────────

    private void scheduleOpenAttempt() {
        if (wantTitle == null || hit) return;
        if (attempt >= OPEN_ATTEMPTS_MS.length) {
            Log.i(TAG, "没能在左栏找到「" + wantTitle + "」，停在 DSH 首页");
            Toast.makeText(this, "已打开 DSH；没找到会话「" + wantTitle + "」，请在左侧点它",
                    Toast.LENGTH_LONG).show();
            return;
        }
        long delay = attempt == 0 ? OPEN_ATTEMPTS_MS[0] : OPEN_ATTEMPTS_MS[attempt] - OPEN_ATTEMPTS_MS[attempt - 1];
        attempt++;
        web.postDelayed(new Runnable() {
            @Override public void run() { runOpenScript(); }
        }, Math.max(120, delay));
    }

    private void runOpenScript() {
        if (web == null || wantTitle == null || hit) return;
        String js = OPEN_SESSION_JS.replace("__WANT__", org.json.JSONObject.quote(wantTitle));
        web.evaluateJavascript(js, value -> {
            Log.i(TAG, "open-session #" + attempt + " → " + value);
            if (value != null && value.contains("hit")) {
                hit = true;
                hideTip();
            } else {
                scheduleOpenAttempt();
            }
        });
    }

    /**
     * 在页面里按标题找会话行并点开。
     *  · 只认左半屏、高度合理的元素（左栏会话行），标题匹配越长越好；
     *  · 找不到就先点一次"展开/显示侧栏"那个控件（窄屏默认收起），下一轮再找；
     *  · 返回 'hit'（点了）/ 'expand'（展开了侧栏）/ 'miss' / 'nowant'。
     */
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

    private void hideTip() {
        if (tip != null) tip.setVisibility(View.GONE);
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onDestroy() {
        if (!uiWasHidden) FloatingBallService.setUiHidden(false, "网页聊天关闭");
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) { }
            web = null;
        }
        super.onDestroy();
    }

    private int dip(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
