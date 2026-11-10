package com.glslplayground.app;

/**
 * 玻璃里 / 全屏版 DSH 前端网页的**页面内控制器**（一段纯 JS，两处共用）。
 *
 * 用户口径（2026-11-09 三次）：
 *   「网页要显示对应的会话的具体的 dsh 前端的对话部分（EvIC1a_column）」
 *   「不改变原本玻璃的大小」
 *
 * 所以不再"按标题猜着点左栏"（标题会变、窄屏左栏是收起的、首页会开错对话），改成两条硬事实：
 *
 *  1. **会话行有稳定的 key**：DSH 前端左栏每一行是
 *     {@code div[data-row-key="session:<sessionId>"]}（见 dsh-client-ui-workspace 的
 *     SessionNodeItem）。按 id 精确点这一行，点完再核对对话区的
 *     {@code [data-conversation-region="chat"][data-conversation-session="<id>"]} ——
 *     对上了才算开了，绝不拿"最近那条"顶替。
 *
 *  2. **对话列有稳定的标记**：那一列就是 {@code [data-chat-flow]}（CSS 类名
 *     {@code EvIC1a_column} 的哈希前缀会随构建变，属性不会）。聚焦模式把这一列**以外**的
 *     东西全藏掉（左栏/顶栏/输入条/右侧栏…），把这一列撑满 WebView 视口：
 *     玻璃里看到的就是这一条会话的对话本身，而不是整张 DSH 应用界面。
 *
 * 只动页面里的 DOM/CSS，**一个像素都不碰** Android 那边的球/玻璃窗口几何。
 */
final class WebChatJs {

    private WebChatJs() { }

    /** 装控制器：幂等，重复调用只返回 ready。 */
    static final String CONTROLLER_JS =
            "(function(){"
          + "if(window.__dshWc&&window.__dshWc.v===4)return 'ready';"
          + "var S=window.__dshWc={v:4,want:'',focusMode:false,state:'boot',needBottom:true,follow:true,"
          + "clicks:0,lastClickAt:0,lastGroupAt:0,hidden:[],col:null,timer:0,lastNote:'',"
          + "touches:0,scrolls:0,touchBound:false,zoom:1};"
          + "function q(s){return document.querySelector(s);}"
          + "function qa(s){return Array.prototype.slice.call(document.querySelectorAll(s));}"
          + "function note(m){if(m===S.lastNote)return;S.lastNote=m;try{console.log('dshwc: '+m);}catch(e){}}"
          + "function fire(el){if(!el)return false;try{"
          + "'pointerdown,mousedown,pointerup,mouseup,click'.split(',').forEach(function(t){"
          + "el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window,button:0}));});"
          + "return true;}catch(e){return false;}}"
          + "function region(){return q('[data-conversation-region=\"chat\"]');}"
          + "function curSid(){var r=region();return r?(r.getAttribute('data-conversation-session')||''):'';}"
          + "function column(){var r=region();return r?r.querySelector('[data-chat-flow]'):null;}"
          + "function row(sid){return sid?q('[data-row-key=\"session:'+sid+'\"]'):null;}"
          + "function sidebarToggle(){var out=null;qa('button[aria-label]').forEach(function(b){"
          + "var l=b.getAttribute('aria-label')||'';"
          + "if(/打开侧边栏|展开侧边栏|Open sidebar|Show sidebar/i.test(l))out=b;});return out;}"
          + "function expandGroups(){var n=0;qa('[data-row-key^=\"workspace:\"][aria-expanded=\"false\"]')"
          + ".forEach(function(r){if(fire(r))n++;});return n;}"

          // ── 聚焦模式：只留对话列 ─────────────────────────────────────────
          + "function restoreChrome(){for(var i=0;i<S.hidden.length;i++){var rec=S.hidden[i];try{"
          + "if(!rec||!rec.el)continue;rec.el.style.removeProperty('display');"
          + "if(rec.disp)rec.el.style.display=rec.disp;rec.el.__dshWcHidden=false;}catch(e){}}"
          + "S.hidden=[];if(S.col)try{S.col.__dshWcFocus=false;}catch(e){}}"
          + "function hideEl(el){if(!el)return;var t=el.tagName;"
          + "if(t==='STYLE'||t==='SCRIPT'||t==='LINK'||t==='HEAD')return;if(el.__dshWcHidden)return;"
          + "el.__dshWcHidden=true;S.hidden.push({el:el,disp:el.style.display});"
          + "el.style.setProperty('display','none','important');}"
          + "function applyFocus(){var col=column();if(!col)return 'nocol';"
          + "if(S.col===col&&col.__dshWcFocus&&document.contains(col)){"
          + "var w0=col.getBoundingClientRect().width;"
          // zoom 之后列宽 = 视口宽 / zoom，别拿没缩过的视口宽去比，否则每拍都重摆一次（会闪）
          + "var z0=S.zoom||1;"
          + "if(w0>0&&w0>=document.documentElement.clientWidth/z0-8)return 'ok';}"
          + "restoreChrome();S.col=col;col.__dshWcFocus=true;"
          + "var sc0=col.closest('[data-conversation-scroll]');"
          + "var chain=[],el=col;while(el&&el!==document.documentElement){chain.push(el);el=el.parentElement;}"
          + "var scIdx=-1;for(var si=0;si<chain.length;si++)if(chain[si]===sc0){scIdx=si;break;}"
          + "for(var i=0;i<chain.length;i++){var p=chain[i].parentElement;if(!p)continue;"
          + "var kids=Array.prototype.slice.call(p.children);"
          + "for(var k=0;k<kids.length;k++)if(kids[k]!==chain[i])hideEl(kids[k]);}"
          + "for(var j=0;j<chain.length;j++){var e=chain[j],cs=getComputedStyle(e);"
          + "if(cs.display==='grid'||cs.display==='inline-grid'){"
          + "e.style.setProperty('grid-template-columns','minmax(0,1fr)','important');"
          + "e.style.setProperty('grid-template-rows','minmax(0,1fr)','important');}"
          + "e.style.setProperty('width','100%','important');"
          + "e.style.setProperty('max-width','none','important');"
          + "e.style.setProperty('min-width','0','important');"
          + "e.style.setProperty('margin','0','important');"
          + "e.style.setProperty('box-sizing','border-box','important');"
          + "if(e===col){e.style.setProperty('flex','none','important');"
          + "e.style.setProperty('height','auto','important');"
          + "e.style.setProperty('min-height','0','important');"
          + "e.style.setProperty('overflow','visible','important');"
          + "e.style.setProperty('padding','4px 6px','important');"
          + "}else if(scIdx>=0&&j<scIdx){"
          // 滚动容器**里面**的那几层：自然高度往上长，别压成视口高（压了就没东西可滚）
          + "e.style.setProperty('flex','none','important');"
          + "e.style.setProperty('height','auto','important');"
          + "e.style.setProperty('max-height','none','important');"
          + "e.style.setProperty('min-height','0','important');"
          + "e.style.setProperty('overflow','visible','important');"
          + "e.style.setProperty('padding','0','important');"
          + "}else{e.style.setProperty('flex','1 1 auto','important');"
          + "e.style.setProperty('height','100%','important');"
          + "e.style.setProperty('max-height','none','important');"
          + "e.style.setProperty('min-height','0','important');"
          + "e.style.setProperty('overflow','hidden','important');"
          + "e.style.setProperty('padding','0','important');}}"
          + "var sc=sc0;if(sc){"
          + "sc.style.setProperty('height','100%','important');"
          + "sc.style.setProperty('max-height','none','important');"
          + "sc.style.setProperty('min-height','0','important');"
          + "sc.style.setProperty('overflow-y','auto','important');"
          + "sc.style.setProperty('overflow-x','hidden','important');"
          + "sc.style.setProperty('padding','0','important');}"
          + "return 'focus:'+document.documentElement.clientWidth+'x'+document.documentElement.clientHeight;}"

          // ── 滚动：默认钉在最新一条；用户自己往上翻就不再抢 ────────────────
          + "function bindScroll(){"
          // 浏览器式交互：用户一碰网页就把"钉在最新"让出来，自己滑到哪儿看哪儿；
          // 滑回底部再自动跟随（和聊天软件的"回到底部"一个意思）。
          + "if(!S.touchBound){S.touchBound=true;"
          + "document.addEventListener('pointerdown',function(){S.touches++;S.follow=false;},true);"
          + "document.addEventListener('touchstart',function(){S.touches++;S.follow=false;},{passive:true,capture:true});}"
          + "var sc=q('[data-conversation-scroll]');"
          + "if(!sc||sc.__dshWcBound)return;sc.__dshWcBound=true;"
          + "sc.addEventListener('scroll',function(){S.scrolls++;"
          + "var d=sc.scrollHeight-sc.scrollTop-sc.clientHeight;S.follow=d<48;},{passive:true});}"
          + "function settle(){var sc=q('[data-conversation-scroll]');if(!sc)return;"
          + "if(S.needBottom){sc.scrollTop=sc.scrollHeight;S.needBottom=false;return;}"
          + "if(S.follow){var d=sc.scrollHeight-sc.scrollTop-sc.clientHeight;"
          + "if(d>2)sc.scrollTop=sc.scrollHeight;}}"

          // ── 每 500ms 一拍：开那一条会话 / 维持"只看对话列" / 钉住最新 ────────
          + "function tick(){try{bindScroll();"
          + "if(!S.want){if(S.focusMode)applyFocus();settle();return;}"
          + "var cur=curSid();"
          + "if(cur===S.want){if(S.focusMode)applyFocus();settle();"
          + "if(S.state!=='open'){S.state='open';note('open '+cur);}return;}"
          + "if(S.focusMode)restoreChrome();"
          + "var r=row(S.want);"
          + "if(!r){"
          + "if(S.state!=='no-row'){S.state='no-row';note('no-row '+S.want+' cur='+cur);}"
          + "var now=Date.now();"
          + "if(q('[data-sidebar-collapsed]')&&now-S.lastClickAt>900){var tg=sidebarToggle();"
          + "if(tg){S.lastClickAt=now;fire(tg);note('sidebar open');return;}}"
          + "if(now-S.lastGroupAt>900){S.lastGroupAt=now;"
          + "if(expandGroups()>0){note('groups expand');return;}}return;}"
          + "var t=Date.now();if(t-S.lastClickAt<1200)return;"
          + "S.lastClickAt=t;S.clicks++;"
          + "if(S.clicks>10){if(S.state!=='giveup'){S.state='giveup';note('give up '+S.want);}return;}"
          + "fire(r);S.state='clicked';note('click row #'+S.clicks+' '+S.want);"
          + "}catch(e){note('tick err '+e);}}"

          // ── 对外的三颗按钮 ────────────────────────────────────────────────
          + "S.setSession=function(sid,opts){sid=sid||'';opts=opts||{};"
          + "var changed=sid!==S.want;S.want=sid;S.focusMode=!!opts.focus;"
          + "if(changed){S.needBottom=true;S.follow=true;S.clicks=0;S.state='want';"
          + "try{restoreChrome();}catch(e){}S.col=null;}"
          + "if(!S.timer)S.timer=setInterval(tick,500);tick();"
          + "return 'want='+sid+' focus='+S.focusMode+' cur='+curSid()+' row='+!!row(sid);};"
          + "S.status=function(){var sc=q('[data-conversation-scroll]');"
          + "return S.state+'|want='+S.want+'|cur='+curSid()"
          + "+'|focus='+(S.focusMode?1:0)+'|col='+!!column()+'|row='+!!row(S.want)+'|clicks='+S.clicks"
          + "+'|tou='+S.touches+'|scr='+S.scrolls+'|top='+(sc?Math.round(sc.scrollTop):-1)"
          + "+'|sh='+(sc?Math.round(sc.scrollHeight):-1)+'|ch='+(sc?Math.round(sc.clientHeight):-1);};"
          + "S.setFocus=function(on){S.focusMode=!!on;if(!on)restoreChrome();tick();"
          + "return 'focus='+S.focusMode;};"
          + "S.scrollBottom=function(){S.needBottom=true;S.follow=true;settle();return 'bottom';};"
          // 网页缩放 = 浏览器式缩放：zoom 挂在 <html> 上，布局视口跟着变窄/变宽，内容自己重排
          + "S.setZoom=function(pct){var z=Math.max(0.3,Math.min(2.0,(pct||100)/100));"
          + "S.zoom=z;S.col=null;"
          + "document.documentElement.style.zoom=(z===1?'':String(z));"
          + "tick();return 'zoom='+z+' vw='+document.documentElement.clientWidth;};"

          + "if(!document.getElementById('dshwc-style')){var st=document.createElement('style');"
          + "st.id='dshwc-style';"
          + "st.textContent='html[data-dshwc]{height:100%!important;overflow:hidden!important}'"
          + "+'html[data-dshwc] body{margin:0!important;padding:0!important;height:100%!important;"
          + "overflow:hidden!important;background:#151517!important}'"
          + "+'html[data-dshwc] [data-chat-flow]{scrollbar-width:thin}';"
          + "document.head.appendChild(st);}"
          + "document.documentElement.setAttribute('data-dshwc','1');"
          + "if(!S.timer)S.timer=setInterval(tick,500);"
          + "return 'installed';})()";

    /** 让网页只显示某条会话的对话列（focus=true）/ 显示整张 DSH（false）。 */
    static String setSession(String sid, boolean focus) {
        return "window.__dshWc&&window.__dshWc.setSession("
                + org.json.JSONObject.quote(sid == null ? "" : sid)
                + ",{focus:" + focus + "})";
    }

    static final String STATUS_JS = "window.__dshWc?window.__dshWc.status():'nobridge'";
}
