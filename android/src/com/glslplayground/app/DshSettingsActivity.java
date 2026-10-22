package com.glslplayground.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 「设置」= **DSH 自己的设置界面**，不是我们再画一套。
 *
 * 用户原话（2026-10-23）：「设置界面显示的是 dsh 的设置界面」。
 * 所以这里就开一个 WebView 指向本机 DSH 的 Web 前端，把 App 手里那条签名 cookie
 * 塞进 CookieManager（和 RPC 用的是同一份），加载完再尽量自动把 DSH 的设置抽屉点开；
 * 点不开也没关系 —— 用户看到的就是 DSH 前端本身，自己点一下齿轮即可。
 *
 * 为什么不做成悬浮小窗：这是**整页的设置**，需要滚动、需要完整控件；
 * 做成小卡片反而看不清（而且用户明确要的是 DSH 的设置界面）。
 */
public class DshSettingsActivity extends Activity {

    private static final String TAG = "DshSettings";
    private WebView web;
    private String base;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(OPEN_SETTINGS_JS, value ->
                        Log.i(TAG, "auto-open settings → " + value));
                // 「…」里点开的菜单要等一帧：再补一枪
                v.postDelayed(new Runnable() {
                    @Override public void run() {
                        v.evaluateJavascript(
                                "(function(){var a=[].slice.call(document.querySelectorAll('button,[role=menuitem],a,div'));"
                              + "for(var i=0;i<a.length;i++){var e=a[i];var b=e.getBoundingClientRect();"
                              + "if(b.width<=0||b.height<=0)continue;var t=(e.textContent||'')+' '+(e.getAttribute('aria-label')||'');"
                              + "if(/设置|Settings/i.test(t)){e.click();return 'late';}}return 'late-none';})()",
                                r -> Log.i(TAG, "late settings → " + r));
                    }
                }, 900);
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        final DshApi api = DshApi.get(this);
        base = api.endpoint();
        new Thread(new Runnable() {
            @Override public void run() {
                String cookie = null;
                try { cookie = api.cookie(); } catch (Throwable t) { Log.w(TAG, "cookie failed: " + t); }
                final String c = cookie;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (c != null && !c.isEmpty()) {
                                String host = android.net.Uri.parse(base).getHost();
                                cm.setCookie(base, c);
                                cm.setCookie("http://" + host, c);
                                cm.flush();
                            }
                        } catch (Throwable t) { Log.w(TAG, "setCookie: " + t); }
                        Log.i(TAG, "load " + base + "/");
                        web.loadUrl(base + "/");
                    }
                });
            }
        }, "dsh-settings").start();
    }

    /**
     * DSH 前端的设置是一个"抽屉"，没有独立 URL。这里按 aria-label / title / 文本找入口点一下；
     * 找不到就什么都不做（页面本身就是 DSH 前端，用户能看到并能自己点）。
     */
    private static final String OPEN_SETTINGS_JS =
            "(function(){"
          + "  function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
          + "  function all(){return [].slice.call(document.querySelectorAll('button,[role=button],a,[data-testid],li,div'));}"
          + "  function hit(re){var a=all();for(var i=0;i<a.length;i++){var e=a[i];if(!vis(e))continue;"
          + "    var t=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')"
          + "      +' '+(e.getAttribute('data-testid')||'')+' '+(e.textContent||'');"
          + "    if(re.test(t)){e.click();return t.slice(0,20);}}return null;}"
          + "  var r1=hit(/^(?!.*(?:不|取消)).*(?:设置|Settings)/i);"
          + "  if(r1) return 'direct:'+r1;"
          + "  // 设置抽屉有时收在左栏底部的「…」菜单里：先点它，再找一次"
          + "  var more=null, els=all();"
          + "  for(var i=0;i<els.length;i++){var e=els[i];if(!vis(e))continue;"
          + "    var t=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.textContent||'');"
          + "    if(/更多|more|其它|其他/i.test(t)){more=e;break;}}"
          + "  if(!more){"
          + "    // 兜底：左栏最靠下、最靠左的那个按钮（齿轮常在那里）"
          + "    var best=null,by=-1;"
          + "    for(var j=0;j<els.length;j++){var q=els[j];if(!vis(q))continue;var b=q.getBoundingClientRect();"
          + "      if(b.left<120&&b.top>b.y&&b.width<120&&b.height<120){if(b.top>by){by=b.top;best=q;}}}"
          + "    more=best;"
          + "  }"
          + "  if(!more) return 'no-trigger';"
          + "  more.click();"
          + "  setTimeout(function(){hit(/设置|Settings/i);}, 350);"
          + "  return 'opened-more';"
          + "})()";

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onDestroy() {
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) { }
            web = null;
        }
        super.onDestroy();
    }
}
