# 交接说明（换窗口续接用）

> 这份是给"下一个会话的自己/别人"看的：现状、关键决定、怎么构建验证、还没做完的事。
> 仓库：`~/oppo-watch-ai`（GitHub `zoujiapeng/oppo-watch-ai`），工作分支 `feat/dsh-liquid-glass-siri`。
>
> 🚚 **要在另一台手机上安装部署**（而不是改代码）→ 直接看
> [`DEPLOY_OTHER_PHONE.md`](DEPLOY_OTHER_PHONE.md)：体检命令、装法、必开开关、
> 全新安装默认值表、踩坑速查表、给 AI 的最短行动清单。
>
> 📸 **用户报 UI 异常（胶囊变样 / 图标对不准 / 玻璃不见）→ 先看
> [`WITNESS.md`](WITNESS.md)**：取证文件在哪、怎么触发、`.txt` 每个字段怎么判读。

## 2026-11-10（第二版）：玻璃里**只显示这条会话的对话列** + 网页体积/缩放在设置里调

**用户原话**：「继续那个实验性的功能……不改变原本玻璃的大小，网页要显示对应的会话的
具体的 dsh 前端的对话部分（EvIC1a_column）」→ 第二轮：「网页要可像浏览器一样滑动等等操作，
并且大小要可在设置调整」→ 第三轮：「可以滑动和点击了，不过网页整体体积和网页缩放要在设置里可调整」。

一句话结论：玻璃还是原来那块（几何一个像素不动），但里面那块 WebView 现在
① **只画 `EvIC1a_column` 那一列**（DSH 前端自己的对话内容，不是整张应用界面）；
② **按 session id 精确打开那一条**（点左栏 `[data-row-key="session:<id>"]`，再用
`[data-conversation-region="chat"][data-conversation-session]` 核对）；
③ 能像浏览器一样**滑/点/双指缩放**；④ 设置页新增「网页体积」「网页缩放」两个滑块。

### 关键实现（都在 `WebChatJs.CONTROLLER_JS`，玻璃版和全屏版共用）

- **会话定位 —— 认 id，不认标题**
  - 左栏每一行是 `div[data-row-key="session:<sessionId>"]`（`dsh-client-ui-workspace` 的
    `SessionNodeItem`）；点它比"按标题找文本"稳得多（标题会改、窄屏左栏默认收起）。
  - 窄屏（<1024）左栏是收起的：先点 `button[aria-label="打开侧边栏"]`（locale 里有中英两种），
    再把 `[data-row-key^="workspace:"][aria-expanded="false"]` 全部展开，最后点目标行。
  - 点完核对 `[data-conversation-region="chat"]` 的 `data-conversation-session` 是不是这个 id；
    不是就重试（最多 10 次），**绝不用"最近那条"顶替**。有 sid 时根本不查 `session/list` 标题。
- **只显示对话列 —— 认属性，不认哈希类名**
  - 对话列是 `[data-chat-flow]`（CSS 模块类 `EvIC1a_column` 的哈希前缀会随构建变，属性不会；
    实测 `document.elementFromPoint(4,4)` 就是 `DIV.EvIC1a_column`）。
  - 聚焦模式：从这一列往上走到 `<html>`，**每一级只留链上的那个孩子、其余兄弟 `display:none`**
    （左栏/顶栏/输入条/右侧栏/浮层全没了）；链上每一级 `width:100% / height:100%`（grid 的改成
    `minmax(0,1fr)`）；**滚动容器里面那几层保持自然高度**（`height:auto`）——压成视口高会没得滚，
    这是第一版的坑。滚动容器（`[data-conversation-scroll]`）自己 `overflow-y:auto`。
  - 只动 DOM/CSS，不碰 Android 窗口几何。
- **浏览器式交互**：窗口 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`，网页矩形内的触摸照常进网页
  （滑动、点链接、双指缩放都行）；**不要**加 `FLAG_LAYOUT_IN_SCREEN` —— 文字窗/玻璃窗都是 overlay，
  坐标原点同在状态栏下（统一 +90px），单独给网页加这个 flag 会让它比玻璃高 90px。
  - 用户一碰网页（`pointerdown`/`touchstart`）就释放"钉在最新"，滑回底部再自动跟随。
- **网页体积**（prefs `watch/web_chat_box`，60~130，默认 100）：在**玻璃自己的可见范围**里缩放
  （`winW-2m` / `ballBottom-ballTop`），**底边钉住不动、往上长**，水平居中；100% = 文字视口。
- **网页缩放**（prefs `watch/web_chat_zoom`，70~160，默认 100）：页面上 `document.documentElement.style.zoom`
  —— 这是浏览器式缩放（布局视口跟着变窄，DSH 自己的排版重排），不是把整页糊成缩略图。
  注意 `applyFocus()` 里的宽度自检要除以 zoom（`clientWidth / S.zoom`），否则每拍都重摆一次会闪。
- **设置页**：「悬浮 → 网页体积 / 网页缩放」两个滑块（`settings.html` → `WatchBridge.get/setWebChatBox·Zoom`）。

### 验证（真机实测，2026-11-10）

```bash
# 起球 + 钉一段字（玻璃里就会盖上网页）
su -c "am start -n com.glslplayground.app/.PowerBallTrampolineActivity \
  --es pk_hold none --es test_reply '## 核对'"
su -c "logcat -s WebChatPane:* FloatingBall:*"
#   WebChatPane: 贴上玻璃 732x135 @(174,112)
#   WebChatPane: controller "installed"
#   WebChatPane: console LOG: dshwc: click row #1 session-…
#   WebChatPane: console LOG: dshwc: open session-…
#   WebChatPane: status → "open|want=session-…|cur=session-…|focus=1|col=true|…"
# 窗口对齐（网页 = 文字视口）：两条 frame 应该一模一样
su -c "dumpsys input" | grep glslplayground
#   网页 … frame=[174,202][906,337]   文字 … frame=[174,202][906,337]
```

页面内自查（调试直通，见下）：

```bash
# --es wc_js '@/sdcard/x.js' 或 '@<app files 目录>/x.js'（注意 chown 成 app uid + restorecon）
# 返回 {"col":true,"colVis":true,"rowVis":false,"composerVis":false,"at00":"DIV|EvIC1a_column", …}
#   → 视口左上角就是对话列，左栏/输入条都不在
```

- 体积 130%：`贴上玻璃 842x178`（宽度被玻璃内沿 842 夹住），底边仍与文字视口对齐（bottom=343）。
- 缩放 160%：`zoom → "open|…|ch=40"`（视口 50 CSS px / 1.6 ≈ 31 …滚动容器按缩放后算），
  会话仍是 `cur === want`，`focus=1`。

### 调试直通（这次新加）

```bash
# 在玻璃里的网页上跑任意 JS，结果打进 logcat（tag WebChatPane，`wc_js → …`）
su -c "am start -n com.glslplayground.app/.PowerBallTrampolineActivity \
  --es test_reply '## 核对' --es wc_js '@/data/data/com.glslplayground.app/files/x.js'"
su -c "am startservice -n com.glslplayground.app/.FloatingBallService \
  --es wc_js '@/data/data/com.glslplayground.app/files/x.js'"     # 已在跑时也能补一发
# 文件必须是 app uid（10286）可读：chown 10286:10286 + chmod 600 + restorecon，
# 否则 EACCES（Termux 是 10276，直接 cp 过去的文件 app 读不了）
```

### 已知边界

- 玻璃很小（默认文字视口 732x135 px ≈ 267x50 CSS px）：网页体积调到 130% 时宽度会被玻璃内沿夹住
  （约 115%），再宽就要改玻璃本身了。
- 网页不抢焦点 → 玻璃里点输入框不弹键盘（打字仍走球自己的输入行）；链接、滚动、缩放都正常。
- 注入式触摸（`input swipe`/agent 注入）会被球自己的「让开」逻辑把悬浮窗临时挪出屏幕
  （`parkAllOverlays`），所以**用 `input` 命令验不了网页滑动**；真手指可以。

## 2026-11-10：实验 —— 回复页玻璃里直接显示 **DSH 前端网页** 的对话页（分支 `feat/web-chat`）

**用户原话**：「做一个实验性的功能，就是把聊天页面直接用 dsh 前端的聊天页面的网站网页，
而不是手搓的」→ 澄清：「是 dsh 前端网页的对话页面显示在**回复页面的玻璃上**」
「缩小看不清也没关系，**不用改变原本玻璃的大小**」。

一句话结论：**玻璃的几何一个像素都不动**，只是把玻璃里"原本写字的那块矩形"盖上一条
WebView，加载本机 DSH 前端、并按标题点进当前那条会话；关掉开关就回到原来手搓的 markdown。

### 它长什么样 / 怎么工作

```
回复页出字（siriStr 有内容、球画成方形态）
        │  updateSiriTextPos() 每帧算出"文字视口"矩形 (x,y,tw,th)
        ▼
FloatingBallService.syncWebChatPane(x,y,tw,th)
        │  条件：web_chat 开 + 方形态 sq>0.55 + 不在菜单/搜索/六区/审批/全屏里
        ▼
WebChatPane（一块贴上去的 WebView 窗，TYPE_APPLICATION_OVERLAY）
        │  · 不抢焦点：FLAG_NOT_FOCUSABLE（回复页不许吃前台 App 的返回键）
        │  · 不抢触摸：FLAG_NOT_TOUCH_MODAL（网页矩形以外照常归球/桌面）
        │  · 圆角：FrameLayout + outline(radius 30dp) + clipToOutline
        ▼
http://127.0.0.1:3080/  （cookie 就是 RPC 那条签名 cookie；主文档自己取回来插兼容层）
        │  页面起来后：左栏「打开侧边栏」→ 按标题点开那条会话 → 滚到最新
        ▼
玻璃里看到的就是 DSH 前端自己的对话页
```

- **开关**：设置页「悬浮 → 网页聊天（玻璃内）」（prefs `watch/web_chat`，默认关）。
- **全屏版**：`WebChatActivity`（同一套兼容层）——参数面板「网页聊天（全屏）」、
  设置页「全屏打开网页聊天」。
- **兼容层**（这机器系统 WebView 是 **Chrome 116**，DSH 前端要 119 的
  `Promise.withResolvers`）：`shouldInterceptRequest` 把主文档自己用 cookie 取回来，
  在 `<head>` 后插 `Promise.withResolvers / URL.canParse / Array#toSorted·toReversed·with`，
  再交给 WebView —— 只有这条路能保证 shim **先于**页面脚本执行（`onPageStarted` 会被抢跑）。
- **玻璃里的网页是只读的**：窗口不抢焦点 → 点不动网页里的输入框。打字仍然走球自己那条
  输入行（`composerSubmit → convoSend`），发出去以后网页自己会实时收到——所以页面里
  那条 DSH 输入条被 CSS 藏掉了（`[data-composer-overlay]`），不白占地方。
- **会话定位**：DSH 前端没有 URL 路由，只能按标题在左栏点。`WebChatPane` 先 `session/list`
  查**这条会话自己的标题**（有 sid 就绝不用"最近那条"顶替 —— 那是开错对话的根源），
  点完再 `verify` 一眼：对话区有 `data-conversation-*` 才算真的开了，没开就重试（最多 2 次），
  还不行就停在首页，绝不假装已经进去了。日志里直接能看到玻璃里是哪条：
  `verify → "open#3:在最新分支使用 dsh 前端聊天页面 对话 轨迹 加载更早 …"`。
- **只手动收回**（用户 2026-11-09 二次明确）：「手动恢复回胶囊状态而不是自动恢复」。
  玻璃里是网页时，`pumpTextHoldSafety` 的 8 秒兜底、`releaseHoldForFinalReply` 的
  安静 1.5 秒、`SiriAssistant.quietTick` 的 30 秒清字+收形状**全部让路**
  （`FloatingBallService.webChatHoldingGlass()`）；文字被清空的空档也不许把玻璃收掉
  （`measureSiriTargetsInner` 的 `n<=1` 分支）。收回胶囊只走用户自己的动作：
  双指手势（`forceHomeShape`）、点球退出会话卡（`closeConversation`）——两条都显式
  `hideWebChatPane()`。

### 验证（截图看不到玻璃的真实渲染，别拿 screencap 判断）

```bash
# 起球 + 把一段字钉进玻璃（test_reply 走 previewText，会钉住方便对着看）
su -c "am start -n com.glslplayground.app/.PowerBallTrampolineActivity \
  --es pk_hold none --es test_reply '## 测试：玻璃里的 DSH 网页'"
# 窗口确实贴上去了：mViewVisibility=0x0 / isVisible=true / gr=TOP LEFT CENTER
su -c "dumpsys window windows" | grep -B2 -A6 glslplayground
# 日志
su -c "logcat -s WebChatPane:* FloatingBall:*"
#   WebChatPane: 贴上玻璃 732x277 @(84,112)
#   FloatingBall: webpane: 玻璃里显示 DSH 网页 …
#   WebChatPane: page finished … title=DeepSeek Harness
#   WebChatPane: open-session #1 → "hit:140:SPAN"
```

开关默认关；本机已经写好 `web_chat=true` 直接验过了。

### 已知边界

- 玻璃里那块的 CSS 宽度 ≈ 244px（= 文字视口 732px ÷ 3），DSH 按自己的窄屏布局排，
  一屏只能看到几行 —— 用户明确说过「缩小看不清也没关系」。
- 网页不抢焦点 → 玻璃里那块**不能打字**（这是 2026-11-09 那条"回复页不许吃返回键"的延续）。
- 球回到胶囊（`squareDrawn01() ≤ 0.55`）时网页自动收起；再长成方形态会自己贴回来，
  不重新加载页面（会话变了才重新点）。

## 2026-11-09（第四版）：回复页不抢前台返回键 / 参数面板回到原页 / 左下改成「最近会话」

**用户原话**：「1 回复页面是手机返回键对手机前台应用无效（比如当前在 edge 浏览器），
回复页面变回胶囊状态的动作已经有了不用加返回键触发（双指同时滑动）；
2 回复页面中双指长按输入框设置参数后点击空白区域返回的地方并不是回复页面这不人性化；
3 菜单中显示运行中的会话我想改成最近会话，只是顶部优先显示正在运行中的会话并有绿色标记，
并且不能只有停止选项，要弄成左半边点击就是进入回话，右半边点击即可停止」。

一句话结论：**回复页绝不接管返回键**（输入行平时不可聚焦，焦点留在前台 App）；
参数面板只是借玻璃用一下，关掉要原样还回来；
左下那张卡不再是"运行中列表 + 只能停"，而是"最近会话 + 绿点 + 左进右停"**。

### ① 回复页开着时，手机的返回键对**前台 App** 失效（Edge 退不了）

> ⚠️ 第一版把这条理解反了（以为"回复页要能用返回键收起"），做了一堆 BACK 拦截 + 顶焦点。
> 用户第二次说明才对齐：**回复页不该接管返回键** —— 返回键要照常给前台 App（比如 Edge 后退）；
> 回复页收回胶囊的动作已经有了（双指手势），不需要返回键。

- 病根是**焦点窗口**：玻璃/文字/代理窗口全是 `FLAG_NOT_FOCUSABLE`，App 里唯一能拿焦点的
  是那条**输入行**（`convoBar`）。它一可见，WindowManager 就把焦点给它（实测
  `mCurrentFocus = com.glslplayground.app`，而前台是 Edge）→ 系统把 BACK 送进这个
  没有 Activity 的悬浮窗，没人转发 → 前台 App 的返回键/边缘手势就"按了没反应"。
- 修法（`ensureComposerBar` + `beginComposerEdit/endComposerEdit`）：
  1. **输入行窗口默认带 `FLAG_NOT_FOCUSABLE`** —— 焦点一直在前台 App 手里，返回键照常后退。
  2. 只有用户**点输入行要打字**时（`onTapEdit → beginComposerEdit`）才临时去掉该 flag：
     删掉再加一次窗口（WM 才会真的给焦点）+ `focusInput()` + 弹键盘。
  3. 退出编辑立刻还回去：API 30+ 用窗口 insets 看 IME 可见性（键盘收起 → `endComposerEdit`），
     再挂 `addOnWindowFocusChangeListener` 兜底（窗口失焦 → 若没拿着焦点就还回去）。
     输入行收起时也清一次，保证下次露出来仍是不可聚焦。
  4. 参数面板（`menuView`）仍是临时可聚焦的**模态小窗**：它开着时返回键先关面板；
     这是唯一的 BACK 拦截。`onGlassMenuBack()` 只处理这一种情况。
- 实机验证（1080×2340，ColorOS）：回复页开着时 `dumpsys input` 的 FocusedWindows
  是浏览器、不是本 App；从屏幕左缘滑 → Edge 正常后退，FloatingBall 日志里**没有**任何
  "返回键"记录；点输入行 → `composer: 进入编辑…` + `winF=true`，键盘弹出；
  键盘收起后 `composer: 退出编辑… → 输入行不再抢焦点`，焦点回到浏览器。

### ② 参数面板关掉回到的不是回复页 —— 两条路，两个病根

- **第一版只修了回复页，漏了会话卡**（用户第二次追报就是这条）：
  `openSettingsMenu()` 里原来的"会话卡暂存"是 `setSiriText("")`，这会触发
  `measureSiriTargets("")` → `textHold + 空闲` → `releaseTextHold()`（内含
  `closeConversation("球回到初始形态")`）。参数面板还没关，**会话卡已经被收掉**；
  等用户点空白/按返回时 `convoSessionId` 已经是 null，`convoStashed` 的 restore 什么也做不了
  —— 屏幕上只剩一颗球，当然"回不到回复页"。
- 修法（统一成一条路，回复页/会话卡都适用）：
  1. `openSettingsMenu()` **不再清空 siriStr**，只 `hideReplyTextForUi()` + 收起输入行，
     并置 `menuReplyGuard = true`、续 `lastTextAt`；`siriStr / convoOpen / textHold` 全都不动。
  2. `hideMenu()` 按 `menuReplyGuard` 调 `restoreReplyTextAfterUi()`（重建内容 + 重算几何 + 露字）
     再 `measureSiriTargets + syncComposerBar`。
  3. `pumpTextHoldSafety()` / `releaseHoldForFinalReply()` 都加
     `menuShown || glassFullscreen` 守卫（面板期间不收回）。
  4. 废弃 `convoStashed / convoStashText / suppressConvoAutoClose`（这套清空暂存就是病根）。
- 实机验证（**真的双指**：用 root `app_process` 注入两指 800ms 长按到输入行）：
  - 回复页：`menu: 打开参数面板，先把当前这一页收好（convo=false，22 字）` → 点空白 →
    `menu: 参数面板关闭，原页放回（convo=false，22 字）` + `composer: 方形态有字 → 输入行摆上`；
    再开一次按返回 → `返回键（玻璃面板）` + `menu: 关闭（返回键）` + 原页放回。
  - 会话卡：`（convo=true，1270 字）` 开参数期间**没有** `convo: 退出` → 点空白/按返回 →
    `原页放回（convo=true，1270 字）` + 输入行回来（截图里 1270 字记录都在）。

### ③ 左下：运行中的会话 → 最近会话（绿点 + 左进右停）

- 数据：预取改成一次 `DshApi.sessions()`（`session/list`，已按 `updatedAt` 新→旧），
  `applyRecentPrefetch()` 再把 `running=true` 的捞到最前；`prefetchedRunning` 换成 `prefetchedRecent`。
- 卡片：标题「最近会话」，运行中的行首**绿点**（`0xFF30D158`）+ 副标题「运行中 · 多久以前 · 目录」；
  行中间一条发丝竖线，**右半边**：运行中 = 绿色 `⏹ 停止`，空闲 = 灰字「空闲」。
- 点击：`cardTap()` 的 kind=5 按 x 是否过行中线分两个动作 ——
  左半边 `openConversation()`；右半边且 running → `stopRunningSession()`（只有这一轮）；
  右半边但没在跑 → toast「现在没在运行」，卡片不关。
- 停止后不再把行摘掉：`markSessionStopped()` 把预取列表/目录缓存里的 `running` 降下来，
  绿点和「停止」当场变灰（下次按住也不会又显示成在跑）。
- 左下圆区图标：原来的「停止方块」换成**时钟**（`BallZonesView.drawIcon` case 2），
  停不停止不再由圆区表达，交给每一行的右半边。

**改到的文件**：`FloatingBallService.java`（输入行可聚焦生命周期 / 参数守卫 / 最近会话）、
`BallZonesView.java`（绿点 / 分栏 / 停止键 / 时钟图标）、`docs/OPERATIONS.md`。
`BallSearchBar.java` 这一轮**没有改动**（第一版的 backHandler 已撤掉）。
APK 已重编并在本机安装验证。

## 2026-11-09（第三版）：在会话卡里发消息 → 球的动画跟着这条会话走

**用户原话**：「我在新回话聊天，然后在查看回话的玻璃上回复他叫他去微信点外卖，它操控手机，
我把玻璃恢复到胶囊状态发现动画对不上，只显示待机动画但实际在调用工具/执行命令/思考」。

### 病根：卡里发消息**没有那条驱动状态机的流**

球自己的动画槽（思考中 / 执行中 / 调用工具 / 过程回复 / 最终回复 / 待机）由
`resolveSlot()` 算，喂它的是三个信号：`asstToolRunning`、`stateNow`、`asstTaskActive`。
这三个信号平时来自两处：

1. `SiriAssistant.askIn()`（语音/电源键那条路：球自己提问）；
2. `DshHttpTransport` 的**镜像流**（`session/follow` WS：tool/call ↔ tool/result）。

而**会话卡里发的消息走的是 `DshApi.prompt()`**，两条都没有 —— 本机
`siri_assistant.xml` 里 `mirror=false`（用户把镜像关了）。于是球什么都不知道，
一直显示待机。顺带还有两个坑：`openConversation()` 从来没调过 `setAssistantSession(sid)`，
所以即使镜像开着，这条会话的 `tool/call` 也会被 `belongsToShownSession()` 当“别的会话”丢掉。

### 修法：给会话卡配一条「看护」（`startConversationWatch`）

- `convoSend()` 把 prompt 发出去以后立刻：`setAssistantSession(sid)` →
  `setAssistantTaskActive(true)` → `setAssistantToolRunning(false)` → `setState("THINKING")`，
  然后起一个看护线程；
- 看护每 **1.5 秒**读一次这条会话的**最新一页**事件（`DshApi.pollSnapshot`），推：
  `tool/call` 没有配对 `tool/result` → 执行中/调用工具；`assistant/message` 带正文 → 过程回复；
  其它还在跑 → 思考中；最新那条相关事件是 `turn/end` → 待机并收工；
- **看护不看 `convoOpen`** —— 用户把玻璃收回胶囊、甚至点球关卡，球照样显示「执行中」；
- 卡里的字每 4.5 秒（以及收尾时）用 `transcriptTail()` 刷一次。

### ⚠️ 轮询**绝不能**每拍都查 `session/list`

本机实测：`session/list` 要 **1.57 秒**（70 条会话，每条的 projections 都很肥），
`session/page` 只要 **70 毫秒**。所以轮询只用 page，游标靠「上次读到的 seq + 600」去探，
探过头 DSH 会回 `session page through seq N is past cursor M` —— 把 M 抄下来重试一次
（`parsePastCursor`）。`session/list` 只在**开卡**时查一次。

### 实测（本机，`ver=convo-unify-1` + 本轮）

```
会话看护：跟着 session-b8f5767b…（动画：思考中）
场景动画: 槽=executing 动画=dots 有字=true → …          ← bash 在跑（sleep 12）
工具在跑=true（bash） → 槽 executing
工具在跑=false → 槽 thinking                            ← 工具回来了，模型在想
场景动画: 槽=reply_stream 动画=cap6 有字=true → …        ← 出字
会话看护：… 这一轮结束（7 拍）→ 动画交代给待机
idle settle 到点 → 只交还光效，**不收方形态**
```

同一轮里三个坑都补了：`setAssistantSession()` 写进 `openConversation()`；
换会话时 `watchGen++` 让旧看护退场；`startConversationWatch` 的代号在
`setAssistantSession` 之后取（那个方法自己会 `++watchGen`）。

## 2026-11-09（第二版）：会话卡 = 回复页（一套界面）+ 回话内容真读出来

**用户原话**：「1 菜单进入工作目录再进入回话后不显示回话内容（回话显示有两种一种是纯文字
另一种是有格式的能显示表格什么的，我倾向于能显示表格的），2 从打开的空白回话页面自动退回
胶囊状态时输入框依然未消失很难看 3 自己手动打开的回话页面不应该是和 AI 回复的页面一样吗？
怎么好像是两个不同的东西一样，一个有输入框一个没有」

一句话结论：**球里那块玻璃只有一套 —— 有字就有输入行**。会话卡不是"另一个界面"，
就是 AI 回复那块玻璃 + 整段会话记录。`ver=convo-unify-1`。

### ① 进会话卡没有内容 —— 三个病根叠在一起

| # | 病根 | 修法 |
|---|---|---|
| 1 | `replyTextShouldHide()` 里带着 `\|\| convoOpen` —— 卡的内容**就是这一层字**，卡一开就被 100ms 巡检收走（截图上就是"空玻璃 + 一条输入行"） | 条件里删掉 `convoOpen`；进卡第一帧先用「· 正在读取对话…」把旧回复顶掉，不靠这条挡 |
| 2 | `DshApi.lastUserMessage()` 读的是 `data.message.content`，可 `user/message` 的正文在 **`data.content`**（真机抓包核过）→ "我发过什么"一条都读不出来；`lastAnswer()` 又只认"最后一条 assistant 文本"，工具步多的会话最后一条常常是纯 `reasoning` | 新增 `contentOf()` 兼容两种位置；新增 **`sessionTranscript(sid, n)`**：把最近几轮拼成 markdown（`**你**` / `**AI**`，表格原样），并从后往前按 5600 字预算保留（结论永远在最后） |
| 3 | 卡里显示的是"你：<最后一句> + 最后一条回答"的**纯文字拼接** | 直接喂 markdown 给 `BallMarkdownView`（它本来就支持表格/标题/列表/代码块），卡的渲染上限放宽到 6000 字 |

另外：DSH 每一拍往会话里塞的 `Current runtime context…` / `[pj-snapshot …]` 注入消息会被
`isInjectedContext()` 跳过（那是给模型看的，不是"我说的话"），否则卡里全是噪音。

⚠️ `session/page` 的 `maxMessages` 数的是**消息条数、不是事件条数**：一个 30 步的工具回合
就能把"最后 60 条消息"全吃光（实测一条会话最后 60 条消息里一条正文都没有 → 卡里显示
"（这条会话还没有内容）"）。所以 `sessionTranscript()` 改成按 `throughSeq` **一页页往回翻**
（最多 4 页、翻到够 3000 字就停），每翻完一页就 `TranscriptProgress.onPartial()` 把手上
那部分先摆到卡上（正常会话第一页就够了；工具密集的会话至少不会一直空着）。

### ② 输入行不再归 `convoOpen` 管 —— 归"玻璃此刻画成什么"

新增 `composerShouldShow()`：**只有玻璃真的是"装着字的方形态"**才露输入行 ——
`siriTextShown && siriStr 非空 && squareDrawn01()>0.5 && !菜单 && !搜索胶囊 && !审批 && !藏界面 && !convoStashed`。

`siriTick`（16ms）里 `syncComposerBar()` 每帧跟一次：该在就摆好（贴着**玻璃底 − 波头带 40dp**，
Siri 波只露波头，输入行不许压住它），不该在就 `GONE`。于是：

- **AI 回复页也有输入行了**（用户问题 ③：两个页面不再是两个东西）；
- 球"自己缩回胶囊"（兜底释放 / 双指 / 进菜单 / 搜索 / 拍照藏界面）时输入行**同一拍消失**
  （用户问题 ②：以前它只在 openConversation / 关搜索 / 关菜单三处显式摆位，缩球这条路上没人管它）。

⚠️ 两个新踩的坑（都写在代码注释里）：

1. **收起输入行时绝不能 `measureSiriTargets()`** —— `siriStr` 还在，量一遍就按它重建内容 →
   玻璃又长成方形态 → 输入行又摆上 → 8 秒后再收，来回抖（实测抓到了）。
2. **会话卡开着就不再走"8 秒兜底释放 / 最终回复安静 30 秒收回"**（`pumpTextHoldSafety()` 与
   `releaseHoldForFinalReply()` 都加了 `convoOpen` 守卫）。卡是"一页在看的东西"，不是一段
   转瞬即逝的回复；关卡只有：点球（单击退出卡）/ 双指手势 / 进菜单·搜索·藏界面。

### ③ 玻璃高度按"真的装得下"算；正文行内标记终于生效

- `measureSiriTargetsInner()` 原来只按 `th/2 + padY` 算高，可文字视口还要扣
  顶部 8dp + 底部 14dp + 波头带 40dp + 输入行一截 → **最后一行被切半行**（实拍「结束了。」）。
  现在 `needH = th + dp(8) + dp(14) + dp(ANIM_BAND_DP) + composerReservePx()`。
- `BallMarkdownView.inline()` 的 NPE：正文段落走 `addText → makeText(..., null)`，
  而 `inline()` 里直接读 `st.codeBg` → NPE → 被 catch 吞掉 → **整行原样输出**，
  屏上就是 `**粗体**`、反引号、`[文字](链接)` 全裸着。现在 `st == null` 有兜底，
  并且把 markdown 链接压成它的文字；解析改成"一次扫描边扫边拼"（老实现删符号时下标会错位）。
- 会话卡打开/刷新/从菜单回来都 `scrollConvoToEnd()`（聊天习惯：新的话在下面）。
- 镜像（`SiriAssistant` 逐字打字）在卡开着时让路（`conversationCardOwnsText()`）：
  卡里是整段记录，逐字覆盖会把记录打乱；卡自己每 1.2s 轮询一次整段。

### 实测（本机真机，vivo/OPPO-ish 1080×2340，`ver=convo-unify-1`）

```
$ su -c 'touch .../files/convo-open'     # 文件内容 = sessionId 前缀/标题片段
convo: 进入对话「修复菜单恢复后文字消失」session-0c23c7ba…
composer: 输入行窗口已建好（默认收起）
composer: 方形态有字 → 输入行摆上（9 字）
convo: 会话内容 1880 字
md rebuild: 内容=6739px 宽=732 字号=38px len=1880

$ su -c 'touch .../files/search-open'    # 进胶囊：输入行必须让位
search: 先藏起会话面的输入行（避免两条输入框叠着）
$ su -c 'touch .../files/search-close'   # 收胶囊：交还给 syncComposerBar 重新判
离开菜单/胶囊 → AI 回复的字放回来（1880 字，内容块=1，已显示）
composer: 方形态有字 → 输入行摆上（1880 字）
```

取证截图（witness 快门，`docs/` 之外放在 `shots/`）：卡片内容 + 表格 + 输入行都在一张图里；
`reply-text` 灌一段带表格的假回复，回复页同样有输入行 —— 两页现在是同一个东西。

### 新调试口子

| 文件（`/sdcard/Android/data/com.glslplayground.app/files/`） | 作用 |
|---|---|
| `convo-open` | 打开**指定**会话（内容 = sessionId / 前缀 / 标题片段），验证"某条带表格的会话"用 |
| `convo-send` | （原有）往当前卡里发一条 |
| `reply-text` | （原有）灌一段假回复，验证排版/输入行 |

> witness 快门现在会把**文字层**一起拍进来（以前只撤掉玻璃的抓屏排除，图里只有玻璃、看不到字，
> "字到底显示没显示"根本判不了）。

## 2026-11-09：黑区钉死（不跟文字）+ 从菜单回来文字不再丢

**用户原话**：「这是 AI 最终回复后然后长按呼出菜单再恢复回来的界面（文字在菜单里消失了是
对的，但是恢复回文字页面文字就没有恢复回来），还有黑区不要和文字产生关联，黑区要像当前
截图的一样位置固定住，保持亮的地方在地下一点点，最顶上永远是最黑的」。

### ① 菜单回来字没了 —— 病根是 `forceHideAllText()` 把内容删了、没人重建

`forceHideAllText()`（进六区 `enterZones()` / 开胶囊 / 收字回球都走它）为了"一帧干净"
做的是 **`mdColumn.removeAllViews()` + `mdVisibility=GONE` + `mdBuiltFor=""`**。
可是之后所有"把字放出来"的路径 —— `showSiriTextNow()`、`measureSiriTargets()`、
`restoreReplyTextAfterUi()` —— **只改可见性**，谁也不重建内容：

- 于是玻璃照旧长回来了（`textHold` 里留着方形态几何），里面**一个字都没有**；
- `siriStr` 是好的，所以只有"再来一段新回复"才会碰巧恢复 —— 用户看到的就是空玻璃。

三处补刀（都在 `FloatingBallService`）：

| 位置 | 做了什么 |
|---|---|
| 新增 `ensureMarkdownContent()` | 内容被清空过（`mdColumn` 子 View=0 或 `mdBuiltFor` 为空）就按 `siriStr` 重建一次；已在就立刻返回（16ms tick 上不会反复排版） |
| `showSiriTextNow()` | 露字之前先 `ensureMarkdownContent()` —— 所有"放字出来"的总口子 |
| `measureSiriTargetsInner()` | `mdContentH <= 0` 时先重建再量高度（否则 `th` 退回 `dp(40)`，玻璃缩成一条窄条） |
| `restoreReplyTextAfterUi()` | ①重建内容 ②`!squareFormActive()` 时 `measureSiriTargets()` 重排几何 ③再露字；`uiHidden` 时**不**清标记（下一帧重试） |

**实测**（`ver=blackfix-1`，文件钩子灌 51 字假回复）：

```
隐藏所有文字：视图=8（8=GONE）内容块=0            ← 进菜单
文字层是空的（内容被清空过）→ 按 siriStr 重建（51 字）
md rebuild: 内容=345px 宽=732 字号=38px len=51
离开菜单/胶囊 → AI 回复的字放回来（51 字，内容块=1，已显示）
```

两条路都验过：`zone-open`/`zone-release`（六区）与 `menu-settings`+点空白处（玻璃面板）。

### ② 黑区改成常量，和文字彻底脱钩

以前 `measureSiriTargetsInner()` 里按文字像素高度反推黑区下沿
（`tBlack = black1Base − (textBottomNy − 0.10)`）：回复一变长黑区就跟着往下长、字一收走
（或玻璃变矮）黑区又跳回来 —— 用户看到的就是"黑区跟着字跑"。

现在只有一个常量（`BLACK_FIXED_NY = 0.0f` → 发给着色器的 `uBlackOffset`）：

- 玻璃顶**永远**纯黑（`uTopDarken=0.93`，`blackMask` 上半段恒为 1）；
- 到球心以下才开始变亮，最底边一条最亮 —— 就是用户截图里那块（实测黑区下沿落在
  `ny≈−0.02`，抓帧量下来玻璃上半 51% 是黑的）；
- 玻璃长高/变矮，黑区只是同比跟着形状走，**不再读文字高度**。
- `TEXT_TOP_INSET_DP` 随之删掉；`BLACK_GAP_NY` 只剩审批面板（`aprBlack`）在用。

想整体把黑区往下压（亮面只留玻璃底一条）：把 `BLACK_FIXED_NY` 调大（0.2 ≈ 下压玻璃高的 20%）。


## 2026-11-08：Siri 波钉死在回复玻璃底边 —— 只露波头，删掉文字/波距离滑块

**用户原话**：「Siri 波固定在回复 AI 文字的那块玻璃的底部，无论玻璃变高还是变矮但固定
只显示 Siri 波头；删掉以前文字和 Siri 波动画的定位逻辑（原本文字与 Siri 波是可设置距离的，
现在删掉）」。

**以前的毛病**：这道波在圆球坐标里锚在黑区下沿 `b1`。长回复时 `tBlack` 被夹到 1.5，
`b1` 落到球底下方，碰巧看起来像"贴着玻璃底露波头"；短回复 `b1` 在黑区里，波就跑到文字
下面去了 —— 位置随文字高度乱跑。**现在**：

1. `waveInGlass`（玻璃里那一支波）不再由动画参数 / 「沉入玻璃」开关决定：
   `applyAnimToRenderer()` 的 commit 里写死
   `waveInGlass = (槽动画 == wave && hasBallText()) ? 1 : 0`。
   有字 + Siri 波 = 一定是贴玻璃底那一支；没字 = 回圆球那一支。
2. 位置在 `GlassRenderer` 的 FS_COMP 里算（不再有 `uWaveDrop`）：
   - `s = max(uHalf.x * uWaveScale, 1)`（半宽固定 → 玻璃高度不影响波头）；
   - 波心藏在底边下方 `dropPx = 0.32*s - 0.55*uWaveHead`；
   - `headMask = 1 - smoothstep(0, uWaveHead, hAbove)`，`hAbove = uHalf.y - c.y`
     —— **只画贴底 `uWaveHead` 像素**，再往上渐隐。玻璃长高/变矮，露出来的就是同样一截。
   - `uWaveHead = waveHeadPx * k`，服务侧按 `WAVE_HEAD_DP = 30dp` 写进去。
3. 文字不再给波让「可调距离」：
   - 删掉滑块「黑区下沿」(`float_siri_dy`) 和「安全距离」(`float_siri_wavegap`)，
     `MainActivity`/`WatchBridge` 的 get/set 也一起删；
   - `measureSiriTargets()` 用常量 `TEXT_TOP_INSET_DP = 4dp`、`BLACK_GAP_NY = 0.10`；
   - 文字视口底 = 玻璃底 − `ANIM_BAND_DP = 40dp`（固定 dp，不再按玻璃高的 14% 算）。
4. 动画库里 Siri 波删掉「沉底偏移 / 沉入玻璃」两个参数（只剩幅度/波长/速度）；
   旧 prefs `p.wave.drop`、`p.wave.inglass` 不再读取（留着无害）。

**顺带修掉用户问的「变回胶囊后还显示一段 Siri 波才回待机动画」**：
`resolveSlot()` 里 `ANSWERING` 那一支原来**只看状态**，文字清空、球缩回胶囊后
状态机还要等 IDLE 沉降（`IDLE_SETTLE_MS`）才改 `stateNow` → 这段时间仍报 `SLOT_FINAL`
（wave）。现在加了硬条件 `hasBallText()`：**球里没字就不可能是回复槽**，字一没就落到
「工具在跑 → 思考/执行；否则 → 待机」。实测 `隐藏所有文字` 与 `场景动画: 槽=idle`
在同一毫秒（00:26:46.851 → .904）发生，空胶囊不再顶着一道波。

**追加（同一晚，用户截图：「胶囊在屏上、回复的字还摊在桌面上」）**：

- `auditWindowAgainstBall()` 的缩窗分支原来只看 `textHold`：文字还在、hold 刚被放掉那一瞬
  会把窗口打回 793×579（甚至更小）→ 玻璃没了、文字层还 VISIBLE。
  现在**有字/在保持一律不许缩窗**（`hasBallText() || textHold` → 直接 return，并把
  `ballRestSince` 清 0），缩窗那一下也顺手 `forceHideAllText()` 兜底。
- `hideSiriTextNow()` 里原来有一条「钉住就 return」——结果钉住的字会一直留在缩小的胶囊外面。
  钉住只表示**内容别自动清空**，不代表要画在胶囊上：这条 return 已删，球回最小形态就 GONE，
  `siriStr` / `asstTextPinned` 保留，下次长成方形态还能放回来。
- 文字层可见度改成跟着**画出来的**几何走：新增 `squareDrawn01()`（看动画值 `aHalf*`，
  不是目标 `tHalf*`）。`updateSiriTextPos()` 里 `alpha *= squareDrawn01()`，
  触摸层也要求 `> 0.5` —— 球一缩回胶囊，字立刻淡没、也不再吃触摸。
- `resolveSlot()` 的回复槽 / `refreshFinalWave()` 也加了 `squareDrawn01() > 0.05`：
  胶囊形态上永远不画回复波。
- **最终回复的 Siri 波被「过程中回复」的胶囊加载盖住**（用户：最终回复怎么显示的是过程中回复的动画）：
  根因在 `refreshFinalWave()` 的 `waveGateTarget = 1f`。`applyAnimToRenderer()` 换槽时会把
  gate 目标压到 0、把「关 loader / 换 waveMode」存进 `sceneCommit`，等 gate 到 0 才执行；
  `refreshFinalWave()` 紧接着把目标抬回 1 → commit 永远等不到 0 → `loaderMode` 一直停在 cap6，
  盖住 Siri 波。现在只有 `sceneCommit == null` 时才抬 gate（有 commit 就让它先落地）。
  实测：最终回复日志 `槽=reply_final 动画=wave` 后 loader 归 0、波正常出现。

**验证**（真机、`files/witness` 合成截图 + 像素量）：短回复玻璃窗 974×579 与长回复
969×968，波头可见高度都是 **76~77px**（同一 `uWaveHead`），位置都在玻璃底边；
`witness` 的状态 txt 里 `glassWin` 可直接对。


## 2026-11-08：搜索胶囊「待久了显示就坏了」—— 根因是窗口被球几何打回 793×579

**用户原话**：「搜索框待久了，等会就会出现显示问题。」

**现场**（`files/glass_*.txt` 与 `logcat -s FloatingBall`，两者时间对得上）：

```
hb: search=1/phase2 ... win=0,-90,1080x2340   ← 刚弹出来，对
hb: search=1/phase2 ... win=143,117,793x579   ← 待了一会儿，错
最终回复安静 1.5s → 收起方形态，回初始圆球     ← 就在这两行之间反复出现
```

**根因**：搜索层把整屏玻璃窗撑成 `screenW×screenH`，可 `updateSiriWindow()` 的守卫写的是

```java
if (menuShown || zonesActive || (glassFullscreen && searchActive)) return;   // 旧
```

而 `glassFullscreen` 是**玻璃菜单**的标志，搜索路径从来没把它置 true
（`enterSearchMode()` 只改 `params.width/height`）→ `glassFullscreen && searchActive`
恒为 false，这个守卫等于不存在。于是搜索胶囊开着时，任何走到 `updateSiriWindow()` 的
路径都会把整屏窗口打回球窗：

* 最常走的一条是 `releaseHoldForFinalReply()`（`SiriAssistant.quietTick` 在最终回复
  安静 1.5s 后调）→ `releaseTextHold()` → `updateSiriWindow(球半径)`。
  搜索开着的时候助手照样会把这句"最终回复"推过来 —— 这就是"待久了才坏"。

**修法**（三处，都在 `FloatingBallService`）：

1. `updateSiriWindow()` 守卫改成 `if (menuShown || zonesActive || searchActive) return;`
   —— 搜索期间窗口归弹簧/胶囊，别人一律不许改。
2. `releaseHoldForFinalReply()`：`searchActive` 时**先不收**，记 `holdReleasePending`，
   等 `finishSearchClose()` 里补做。这样既不打回窗口，也不会把刚到的最终回复从球里清掉
   （否则收起胶囊是颗空球）。
3. `searchStep()` 每帧兜底：`winW/winH` 不是 `screenW/screenH` 就当场掰回整屏并打 warn ——
   以后再有新路径漏了守卫，下一页就自愈，用户看不到坏相。

**回归钩子**：`files/hold-release`（模拟上面那条释放路径，`pumpSearchRequest()` 里）。
不用真的让助手回一句就能复现/验收。

**实测（同一颗球、同一个钩子，改前 vs 改后）**：

| | 打开胶囊 + `hold-release` 后 |
|---|---|
| 改前 | `ball window -> 793x579`，`hb: search=1/phase2 ... win=143,117,793x579` |
| 改后 | 窗口一直 `0,-90,1080x2340`，日志 `最终回复到了，但搜索胶囊还开着 → 先不收`；收起时 `search: 收起 → 补做胶囊期间欠下的「最终回复收回」` |

## 2026-10-29（续）：让球"当无事发生"到底卡在哪 —— 已解决

目标：AI 点击时**玻璃一个像素都不动**，下面的 App 照常被操控。结论先说：
**卡在 `TYPE_ACCESSIBILITY_OVERLAY` 缺 window token**；补上 token 之后，澎湃也会把窗口
认成**受信任 overlay**，遮挡拦截不再生效，球只需要把触摸区置空——窗口一动不动。

### 1. 关键证据链

| 尝试 | 结果 |
|---|---|
| `TYPE_APPLICATION_OVERLAY`（普通系统悬浮窗） | 会被 WMS 收下，但**不受信任**：跨 App 注入被"触摸穿过不受信任悬浮窗"整串丢弃（同 App 的目标不拦——以前的验收一直被这条盖住） |
| `TYPE_ACCESSIBILITY_OVERLAY`（裸加） | `W WindowManager: Attempted to add Accessibility overlay window with unknown token null. Aborting.` → 要求 token |
| 同上 + `createWindowContext(int, Bundle)` | `UnsupportedOperationException: Tried to obtain display from a Context not associated with one` → 服务的 Context 没绑 display |
| **同上 + `createWindowContext(Display, int, Bundle)`** | ✅ 通过。`dumpsys input` 里窗口变成 `inputConfig=... TRUSTED_OVERLAY` |
| `PRIVATE_FLAG_TRUSTED_OVERLAY`（0x20000000） | ❌ `SecurityException: DisplayPolicy: uid ... does not have INTERNAL_SYSTEM_WINDOW`（该权限 `prot=signature`，priv-app 白名单也没用），且是在 addWindow 里抛，会把进程带崩 → 代码已改成先 `checkSelfPermission` |

### 2. 所以"一加 9 Pro（同样 Android 13）为什么可以"

它的 ROM 沿用了 AOSP 的宽松行为：无障碍服务直接加 `TYPE_ACCESSIBILITY_OVERLAY` 就收，
窗口天生是受信任 overlay，遮挡拦截对它不生效 —— 球原地不动也能点中。
澎湃在这条路上多要了一个 token，我们没给 → 掉回普通 overlay → 被拦 → 只能挪球。

### 3. 现在的实现

`BallA11yService.onServiceConnected()` 里：
```java
Display d = DisplayManager.getDisplay(DEFAULT_DISPLAY);
Context ctx = createWindowContext(d, TYPE_ACCESSIBILITY_OVERLAY, null);   // 带 token
overlayWm = (WindowManager) ctx.getSystemService(WINDOW_SERVICE);
```
`FloatingBallService.setupOverlay()` 优先用这个 `overlayWm` 加所有悬浮窗；
`overlayType()` 在有 `overlayWm` 时返回 `TYPE_ACCESSIBILITY_OVERLAY`；
`trustedOverlayNow()` 为真 → **让开时只把触摸区置空，窗口不挪、不隐藏**。

真机实测（系统设置，点在球正中央 540,630）：
```
D0: blind=false parked=false glassParked=false trusted=1
D1: blind=false parked=false glassParked=false trusted=1  nonce=sh-...   ← 走完了门控
界面：设置首页  →  小米账号（点中了）
```
即：**跨 App 点中了，球一像素没动、没闪、没隐藏**。

### 4. 附带影响

- 无障碍服务必须保持启用（`settings put secure enabled_accessibility_services
  com.glslplayground.app/com.glslplayground.app.BallA11yService`）。它只为拿"受信任窗口"资格，
  不读屏幕内容、不收事件。
- 开着这个服务时，`uiautomator dump` 会和它抢 UiAutomation 槽位（报
  `UiAutomationService already registered`）。vd 的 `observe` 不受影响（实测 38 节点正常）。
  自己写脚本要 dump 界面时，先 `settings put secure enabled_accessibility_services null`。
- 服务没启用 / 被系统踢掉时自动退回：普通 overlay + 挪窗口让开（会闪一下），功能不丢。

## 2026-10-29：防误触 v2（球的触摸来源判定 + 按需让开）

上一版的问题是**只挡合作方**：`/system/bin/input` 包装器和插件 socket 之外的一切注入
（`cmd input`、uiautomator、别的自动化、vd 自己的注入器）都直接打在球上；而且门控是
"每一下都握手"，21.6% 的点击连门控都没连上（`~/.cache/ball-plugin.log` 里 171 次里 37 次
`ECONNREFUSED`）。这一版把"防误触"拆成四层，任何一层挂了都不会出现"点一下球被拖走/弹出菜单"。

**1. 球认得"这不是人"**（`FloatingBallService.refreshTouchDevices/isSyntheticTouch`）
把真实触摸屏的 deviceId 记成白名单，不在表里的一律当机器：**不执行任何用户手势**，
只记账并自动让开 `SYNTHETIC_DUCK_MS=1500`。
真机实测：`input tap` / `cmd input tap` 到代理窗口时 `deviceId=-1`，真手指是白名单里的设备号。
（万一把 0 号设备也认成触摸屏，会整体关闭判据 —— 宁可漏判机器，不能让球点不动。）

**2. 让开不再搬窗口**（`installTouchRegion/buildTouchRegion`）
用 `ViewTreeObserver.OnComputeInternalInsetsListener` 的 `TOUCHABLE_INSETS_REGION`
把窗口的可触摸区设成任意 `Region`：让开 = 置空，平时 = 内切圆角矩形（球是圆/胶囊，
方角那圈"看不见的死区"从此归下面的 App）。窗口位置、尺寸一个像素都不动。
反射挂不上时自动退回老的 `FLAG_NOT_TOUCHABLE + 1×1 挪屏外`。

**3. 只在"这一点真的会落到球上"时握手**（包装器 + 插件）
App 每 400ms 写 `files/ball-rect`（`x y w h blind ts`）：
- `/system/bin/input` 包装器先看点在不在矩形里：**在外面直接注入**（实测 ~100ms，零握手、零抖动）；
  在里面才 ACT/ACK；等不到 ACK 就**拒绝注入**（fail-closed，exit 3），不再把点击喂给球。
- 插件改成先 `STAT` 问球（返回 `blind/rect/swallowed`），注入后对账 `swallowed`，
  被吞就**立刻重试一次**（此刻球正在自愈窗口里，必中）。`STAT` 是同一个 socket 上的新命令。

**4. 受信任 overlay（跨 ROM 的根治手段，本机 MIUI 不给用）**
新增空壳 `BallA11yService`：无障碍服务连着时窗口用 `TYPE_ACCESSIBILITY_OVERLAY`，
系统就不会因为"有东西挡着"把跨 App 的触摸丢掉，于是"原地不动 + 区域置空"就够，零闪烁。
**本机实测 MIUI 拒绝**：
`WindowManager$BadTokenException: Unable to add window -- token null is not valid` →
代码自动退回普通悬浮窗（`a11yOverlayBroken=true`），让开改走"挪窗口"（会闪一下，只在这一下）。
换 ROM 或哪天 MIUI 放行，这段会自动生效。

**必须知道的一条**：Android 12+ 的遮挡拦截看的是**窗口框**，不是可触摸区 ——
目标窗口和悬浮窗**同属一个 App** 时不拦（所以老验收"点自己的设置卡片"一直是通的，
把这个问题盖住了）。跨 App（微信/系统设置）必须让窗口真的离开那个点。
实测：玻璃不挪 → 点击完全没反应；玻璃挪走 → 立刻点中。

### 验收（`tools/`，都跑在真机上）

```bash
bash tools/restart-ball.sh          # 拉起球（含无障碍授权、打开「悬浮于桌面」）
bash tools/e2e-ball-gate.sh         # 12 项：球外零开销 / 球上让开 / 机器触摸不执行手势 / 自愈 / 球被杀
node tools/e2e-gate-protocol.mjs    # 8 项：STAT/ACT/ACK/DONE 协议 + 裸注入（模拟 vd）路径
```

本机实测（2026-10-29 04:0x，小米 M2007J1SC / Android 13）：
```
12 通过 / 0 失败      e2e-ball-gate.sh
 8 通过 / 0 失败      e2e-gate-protocol.mjs
球外点击  109ms（非门控路径）
球上点击  458ms（120ms 让开 + 40ms + 300ms 尾巴）
ball-diag 新增字段：region= a11y= trusted= duck= synthetic= rect=
```

### 回退

- 想让开逻辑退回"每一下都挪窗口"：`touch files/glasspark`（同时关掉受信任 overlay）。
- 想彻底关掉门控：`touch /data/local/tmp/ball-gate-off`（包装器直接 `exec cmd input`）。
- 关掉无障碍：`settings put secure enabled_accessibility_services null`。

### 插件改动怎么生效

DSH 加载的是 `~/.dsh/.agent-presets/mobile-use/mobile_plugin.js`（不是仓库里的 `patches/` 副本）。
改完执行 `bash patches/sync-ball-gate.sh`（拿 git 里的上一版做基底打 diff），**重启 DSH** 才生效。

## 2026-10-28：防误触——只在注入触摸的那一下让开

早先那套（ACT/ACK + 全 UI 让位 + LSPosed 模块）整体删掉了（`f8356bb`）；这里是最新一版
（`e98942a`），语义只有一句：**mobile use 发 click/swipe 的那一下，把球的触摸区藏起来；
注入完立刻还回去**，别的状态一概不管。

**协议**（`FloatingBallService` + `~/dsh-preset-mobile-use/lib/index.js`）：

1. 插件要发 `click` / `swipe` → 把 nonce **原子写**进 `files/dsh-ball-act`；
2. 球每 **40ms** 读一次，看到内容变了：
   - 代理窗口 `|= FLAG_NOT_TOUCHABLE`，**并且**缩成 **1×1** 挪到屏幕边（ROM 忽略 flag 时的物理兜底）；
   - 把同一个 nonce 写进 `files/dsh-ball-ack`；
3. 插件收到 ACK（最多等 200ms，收不到也照发：fail-open）→ `POST /api/action` 真正注入；
4. 注入返回（成功/失败都一样，`finally`）→ 把同一个 nonce 写进 `files/dsh-ball-done`；
5. 球看到 `ACT == DONE` → **下一拍（≤40ms）就把触摸区还原**，回到球的几何。
6. 兜底：插件没写 DONE（崩了/被杀）时，最多 **1200ms** 自动还原，不会把球一直晾着。

代理窗口唯一的另外两个让开理由是原本就有的：搜索胶囊 / 卡片菜单 / 整屏玻璃这几态本来就不该
抢手势（`overlayTouchState()`）。

**对账**：`pumpGate()` 每 40ms 检查一次；`want=false` 但窗口还停着就立刻 `applyBallTouchability()`，
否则每 300ms `syncBallTouchWindow()` 拉一次几何 —— 任何路径忘了收尾都会在 300ms 内被拉回来。

**诊断口子**：`files/ball-diag`（2s 刷新）：`blind= parked= nonce= ageMs=`。
`blind=true` + `parked=true` 就是"触摸区正藏着"；`nonce` 是最近一次信号。

**抓帧看门狗**：首帧一直没来（su 被拒 / 管道断）也会重启采集（`surfaceReadyAtMs` 起 4s），
不然球会一直是透明的。

实测（真机）：
```
ACT → 代理窗口 frame=[37,454][38,455]（1×1，触摸区没了）
     input tap 落在玻璃背后的「设置」卡片 → DshSettingsActivity 打开
DONE → 40ms 内 frame 回到球的 [177,1622][904,2135]，可正常拖动
```

插件是启动时加载的，**改完要重启 DSH 才生效**。

## 一句话现状

手机上的**悬浮液态玻璃水晶球**已经是 **DSH 的前端**：双指长按打开搜索胶囊、打字提问 → 本机 DSH 回答 →
回答**逐字打进球里**（Siri 模式的形变球）；DSH 里任何会话的 AI 输出也会被镜像进球。
DSH 侧一行没改，只走它已经在给浏览器用的 HTTP/WebSocket 接口。

**本次新增「审批 / 提问面板」**：DSH 要你点「批准 / 拒绝」，或问你问题时，**同一颗球**会
变成一张近方形的可交互玻璃卡片：上面是标题与说明，下面是**自适应**的选项按钮（数量和文案
由 DSH 给，能多选）以及一个**自由输入框**（协议里的 `custom`）。详见下面「审批 / 提问面板」一节。

## 怎么构建 / 装 / 看日志

```bash
cd ~/oppo-watch-ai/android && bash build.sh      # javac + d8 + aapt2 + apksigner（本机就有）
pm install -r ~/oppo-watch-ai/glsl-playground.apk
# 回滚包：~/glsl-playground.backup.apk
```
- 这个 App 同时是桌面/Kiosk：装完**悬浮球会停**，要在设置页重新打开「悬浮于桌面」。
- **Termux 里调 am/pm 必须带干净环境**，否则 Termux 的 `LD_PRELOAD` 会让 app_process 起不来：
  `env -i PATH=/system/bin HOME=/data/local/tmp am start-foreground-service -n com.glslplayground.app/.FloatingBallService`
- **不要用 `mkdir` 去创建 App 的私有目录**：`/data/data/com.glslplayground.app` 由系统按
  uid 拥有（0711），用 root 建出来的目录属主是 root，应用就再也写不进 prefs 了
  （症状：设置页开关点了没用、`siri_assistant.xml` 不生成、`approval_orb` 永远是默认值）。
  万一踩了：`chown -R <uid>:<uid> /data/data/com.glslplayground.app && chmod 771 <dirs>`。
- 日志（我平时靠它验证）：`logcat -d | grep -E "DshTransport|DshApproval|SiriAssistant|FloatingBall"`
- 球体**在物理屏（display 0）**上，虚拟屏看不到它 →
  设置页有「保存球体截图」（PixelCopy 只抓悬浮窗那一帧，不含桌面）。
  也支持外部触发：`touch /data/local/tmp/ballshot` → 球在下一个渲染帧自己存一帧并删掉该文件。
  **注意**：`/data/local/tmp` 对 untrusted_app 是 SELinux 禁区（`chmod 777` 也没用），
  `ballshot` 这条老路在新版系统上不灵；App 私有目录 Termux 又看不见（挂载命名空间不同，
  Termux 里连 `/data/user` 都不存在）。要看图只能让用户从设置页按钮存、或把图 POST 出来。

## 关键文件

| 文件 | 作用 |
|---|---|
| `android/src/.../DshHttpTransport.java` | DSH 客户端：token→cookie、session/{list,create,prompt,page}、**常驻镜像** |
| `android/src/.../DshWebSocket.java` | 自实现最小 RFC6455 客户端（握手/掩码/分片/ping） |
| `android/src/.../DshApprovalClient.java` | **审批/提问的数据面**：`$events` 瀑布订阅 + 答案回传（审批=字符串、提问=答案对象）+ 调试回路 |
| `android/src/.../SiriAssistant.java` | 状态机 + 打字机 + 镜像回调 + 空闲清空 + 审批流生命周期 |
| `android/src/.../FloatingBallService.java` | 悬浮球：手势、Siri 形变几何（`measureSiriTargets`）、黑区、球体截图、**审批/提问面板**、搜索胶囊 |
| `android/tools/approval-debug-server.mjs` | 调试回路（喂假审批 / 收球上的点击），端口 = DSH 端口 + 1 |
| `android/docs/probes/dsh-approval-probe.mjs` | 只读探针：订阅 `$events`、打印瀑布请求、可选自动应答 |
| `android/docs/probes/dsh-approval-trigger.mjs` | 造场景：建会话 / 切权限预设 / 发会触发审批的 prompt |
| `settings.html` | 设置页（「DSH 文本助手」区块 + Siri 参数 + **审批球**区块） |
| `android/docs/DSH_ASSISTANT.md` | 完整接入文档（协议、状态表、黑区公式） |

## 已经确定的行为（别改错）

- **手势**：双指长按=打开搜索胶囊打字；长按=取消当前轮；拖动=只拖动；**单击不响应**。
- **球内文字只有一种**：AI 的回答。没有「思考中」之类的占位字。
- **球缩回最小形态时文字「同一刻」消失**：`measureSiriTargets` 走到「0/1 个字 → 最短形态」那一支时
  会立刻 `hideSiriTextNow()`（`setVisibility(GONE)`，不进布局）—— 只把文字设成空串不够，
  窗口/测量的更新是异步的，球收缩的半秒里旧字还会留在屏上（用户看到的「球小了、字飞出球外」）。
- **点球内文字 = 钉住**：单击文字把它保持住（不再自动清空，文字后带 📌 提示），再单击解除；
  长按文字也是解除；在文字上按住拖动 = 照常拖球。实现是「文字之上盖一个透明触摸层」，
  文字层本身永远 NOT_TOUCHABLE —— 否则文字那块就没法拖球了（见 `setupSiriTouchLayer`）。
  新一轮提问会自动解除钉住。
- **回答结束、球变回原本状态时文字一起消失**：等打字机弹完最后一个字 → 文字静默 4s 清空并回 IDLE，
  最终方形态 30s 后收回（`SiriAssistant.FINAL_SHAPE_BACK_MS` / `IDLE_CLEAR_MS` + `quietTick`）。
  **注意清空不看 turn/end**：镜像跟的那条会话可能是一轮跑很久的长任务（几十个 step、几分钟不 end），
  只认 turn/end 的话文字会一直挂在球里 —— 现在只要「没新字进来」就自己消散。
- **光效**：只有 THINKING 是流体圆点；ANSWERING/IDLE 还原用户自己的波形设定
  （`FloatingBallService.setAssistantState`）。
  圆点与波**共用同一套坐标框架**（屏幕像素 / `uOrbRadius` * 0.17，x/y 同倍率），
  边缘辉光取「圆点自身亮度 × `uOrbRim`」而不是固定满值；切换时 `uOrbMix` 做 320ms 交叉淡化。
  调这三个参数：设置页「思考中圆点环半径 / 亮度 / 边缘辉光」，
  离线看效果：`android/tools/preview`（不必刷 APK）。
- **黑区下沿按文字实际高度算**，不按行数：
  `tBlack = clamp(uBlack1 - (textBottomNy - waveGap), 0, 1.5)`，
  滑块「文字与 Siri 波的安全距离」= `waveGap`（pref `float_siri_wavegap`，默认 100 → 0.10）。
- **球体大小**：设置页「液态玻璃 · 形状 → 球体大小（整体 scale）」= `master`（20%~200%，1.00 = 原始大小），
  实时生效、随 `float_params` 一起持久化。
  注意：Siri 模式（思考/回答）下球体几何走 `svHalf*/tMaster`，`master` 不参与 ——
  所以 `minHalfX/minHalfY`（空球基准比例）刻意**不乘** master，否则滑块一动就双重缩放。
- **球高封顶 = 宽度**（2026-10-23 用户：「把回复内容的大方形的限高弄成最高的高度等同于宽度，
  相当于最大就是正方形」）：`siriMaxBallH()` 直接返回 `siriMaxBallW()`，方形态最大是
  **正方形**（1080 宽的机器上是 842×842；实测长文本 40916px 内容 → 球窗 905×905 = 玻璃 842×842）。
  封顶后**不再截断**：多出来的内容在玻璃**内部滚动**（见下面「球内富文本」一节）。
  旧值「屏高 55%（`SIRI_H_RATIO`）」已废弃，常量只为兼容保留。
- **球内文字长度封顶 4000 字**（`FloatingBallService.BALL_TEXT_MAX`，超了只留最后一段）＋
  镜像缓存封顶 1200 字（`DshHttpTransport.MIRROR_BUF_MAX`）—— 一条长任务的输出几万字，
  不封顶的话球内页面会撑成一整面墙。
- 文字排版：字号仍取设置页「Siri 字号」，但富文本正文会再乘 `MD_FONT_RATIO`（0.78），
  距球顶/内边距/圆角沿用原设定，助手只调 `setSiriText`。

## 球内富文本：**原生 View 方案**（2026-10-16 第二次大改，取代 WebView）

> 历史：先做了 WebView + 自写 markdown（能渲染真表格），但真机上出现
> **「有球无字」+ 一次进程 native 崩溃**，而球的两层窗口都被 SKIP_SCREENSHOT 排除、
> 截屏/球体截图都拍不到它 —— 这类问题在悬浮窗里的 WebView 上**无法自证**。
> 用户拍板改用原生，于是有了现在这套。

### 现在的结构

| 位置 | 说明 |
|---|---|
| `BallMarkdownView.java` | markdown → **View 树**：标题 / 列表（带缩进）/ 引用 / 代码块 / 分割线 / 行内粗斜体与行内代码 / **逐行对齐的表格** |
| `FloatingBallService.siriText` | `ScrollView`（球内滚动） |
| `FloatingBallService.mdColumn` | 里面的 `LinearLayout`，放渲染出来的块 |
| `setupSiriTouchLayer` | 球顶「抓取条」：拖球 / 单击钉住（文字区留给滚动） |

### 关键实现点

1. **内容高度是量出来的，不是猜的**：`rebuildMarkdown()` 里
   `mdColumn.measure(EXACTLY(宽), UNSPECIFIED)` → `getMeasuredHeight()`，
   单位就是物理像素，和球的几何同一套坐标（WebView 时代这里要靠页面 JS 回报 + 缩放换算，
   踩了 6 个坑）。
2. **指纹去抖**：`mdBuiltFor = 文字长度 | 宽度 | 字号`，没变就不重建 —— 避免
   "同一内容反复重排"导致的脉动/闪烁。
3. **表格**：每行一个横向 `LinearLayout`、每列一个定宽 `TextView`，列宽按各列最长内容
   加权分配（**不画网格线**，只画表头下一条细线 —— 球内是玻璃背景，网格线很脏）。
4. **行内格式解析失败就原样显示**（`try/catch` 兜底）：排版出点错远好过把球搞崩。
5. **超长截断**：只留最后 `MAX_CHARS = 2500` 字（结论通常在最后）。
6. **字号** = 设置页「Siri 字号」×「球内回复字号比例」（滑块，默认 0.25 ≈ 18px）。

## 球内富文本：WebView + 自写 markdown（2026-10-16 大改，重点看）

**用户要求**：① 方形玻璃高度限制在屏高 55%；② 文字永远被束缚在玻璃里，满了像记事本一样
上下滑；③ AI 回复要和 DSH 网页一样支持表格之类的 markdown；④ 不要打字机效果。

### 结构

| 位置 | 说明 |
|---|---|
| `BallMarkdown.java` | markdown → HTML（表格/标题/列表/引用/代码块/行内样式）＋页面 CSS/JS。纯静态、零依赖 |
| `FloatingBallService.siriText` | 从 `TextView` 换成 **`WebView`**（透明背景，压在玻璃黑区上） |
| `setupSiriTouchLayer` | 原来那张「和文字等大的透明触摸层」改成**球顶一条抓取条**（文字区要能滚） |

几何：宽度固定 78% 屏宽（`SIRI_W_RATIO`）、高度 = 内容高度（封顶 = 宽度 → 最大正方形），
内容比视口高时 WebView 内部滚动（`overflow` 默认行为，不需要额外代码）。

### 六个必须记住的坑（都实测踩过）

1. **CSS 数值必须除以 density**。Android WebView 里 `font-size:27px` 画出来是 **71 物理像素**
   （27 × density 2.625）；`setInitialScale` 和 viewport meta 里的 `initial-scale`
   **都不认**（页面探针量出来永远是 1，因为 DOM 报的就是 CSS px）。所以 Java 侧写 CSS 时
   全部除以 `mdScale`（= density），字号用 `siriTextPx * MD_FONT_RATIO / mdScale`。
2. **`#md` 外层 div 不能被 innerHTML 换掉**。宽度/字号/顶部留白挂在 `#md` 上的
   CSS 变量（`--w/--fs/--pt`），流式更新只能 `#md.innerHTML = 片段`。
   早期把外层 div 一起写进片段 → 每次渲染都把设置换没了，`--fs` 失效退回 16px、
   宽度丢失，一段 500 字的回复被量成 **6155px** 高、球一直顶着上限。
3. **不要给内容设 `min-height = 视口高`**。那样 `scrollHeight` 变成「内容/视口」的较大者，
   量出来的"内容高度"被视口撑大 → 球越算越大。内容从顶部排、超出滚动，本来就不需要它。
4. **页面的 JS 每次改完必须做语法体检**。一个 `+ " 生效后 fs=" +` 这种字符串拼接错误
   会让整段脚本 `Uncaught SyntaxError` 静默失效（表现只是"字号/宽度都对不上"）。
   现成的体检脚本：`java -cp .build/classes:.build/javac-check DumpJs && node --check .build/page.js`
   （`DumpJs` 用反射把 `BallMarkdown.JS` 抽成 `.build/page.js`）。
5. **文字窗必须按玻璃的「真实屏幕矩形」摆**，不能用 `renderer.winX/winY`（500ms 才刷一次，
   窗口刚变大时是过期值）。实测拿过期值算，文字窗底部跑到 **2508**（屏只有 2296）——
   就是用户说的「文字跑出玻璃/贴屏幕底」。现在用 `glView.getLocationOnScreen()`，
   并且夹取保证 `y + sysDy + 高度 ≤ 屏高`。
6. **`clampBallOnScreen` 有收敛守卫**（`clampLastWant*/clampLastLoc*`）。球顶到最大高度时
   它在屏幕上的实际位置就是被夹住的位置，于是每帧都算出「要挪」→ `updateViewLayout` →
   `winSync` → `clamp` 无限互相触发：实测 **11ms 内 18715 条日志**、主线程被塞满，
   表现就是用户报的**卡死**。两个值都没变就直接 return。

### 位置：球长高时往上躲（2026-10-16 用户反馈）

用户原话：「弹出位置为什么老挤在下面影响我打字」。真因：方形态是**钉住球顶往下长**
（当初用户要的是"高度随文字往下涨"），长到上限时底边到 `home + 1327`，正好压在输入框/键盘上。

修法：`ballTopOnScreen(halfH)` —— 球顶的**唯一**竖直定位点：
球底超过 `screenH × SIRI_SAFE_BOTTOM_RATIO(0.66)` 就把整颗球上移，让底边停在安全线上。
玻璃窗口（`updateSiriWindow`）和文字层（`updateSiriTextPos`）**都必须调它**
（以前各自用 `sy ± halfH` 算，一旦上移就会玻璃走了、文字没走 → 文字掉出球外）。

### 离线测试（不依赖 DSH，2026-10-16 用户要求）

调字号/排版时不必等真实回复。三个入口，效果一样（都灌一段示例 markdown 并**钉住**，
不会 4 秒后自己散掉）：

| 入口 | 怎么用 |
|---|---|
| 设置页按钮 | 「Siri 模式」区块 → **离线预览球内排版** / **结束预览** |
| 文件钩子 | `touch files/state-PREVIEW` / `touch files/state-PREVIEWOFF`（一次性，用完自删） |
| 假审批 | 设置页「叫一条假审批 / 假提问」+ 「审批面板字号」滑块（调审批卡片用这个） |

示例文本来源：`files/md-sample.md`（现场想改样例就编辑它，**不用重装**）；
文件不存在则用代码里的内置样例。样例刻意包含表格 / 标题 / 有序无序列表（带嵌套）/
引用 / 代码块 / 长段落 —— 这几种是最容易撑爆玻璃的。

字号滑块（设置页，即时生效、存 prefs）：
- **球内回复字号比例** `md_font_ratio_pct`（5~120，默认 25 = 0.25）
- **审批面板字号** `ap_scale_pct`（20~150，默认 55 = 0.55）

### 调试口子（回归用）

- `files/ballshot` 写文字 → 球显示它并截一帧（`files/ball-capture.png`）。
  想看**排版**（不是玻璃）时，先 `touch files/md-show`：文字层不再排除出抓屏，
  普通截图/录屏就能看到球内 markdown 的真实观感（删掉该文件即恢复排除）。
- `logcat | grep "md geom"` → `内容=1688px 视口=1243px 球高上限=1327px(55%屏高) 球宽=918px`：
  一眼看出"球是不是封顶了、内容会不会滚、上限比例对不对"。
- 越界兜底会打 `文字窗越界！`（正常情况下不该出现）。

### 验收记录（2026-10-16 真机）

```
球窗口      1044x1453  → 玻璃 1044x1327 = 屏高 2412 的 55% ✅
文字窗      (215,1142) 813x1158，完全落在玻璃矩形 (99,1022)-(1143,2349) 内 ✅
内容 1688px > 上限 1327px → 球封顶、内容在玻璃内滚动 ✅
真实 DSH 回复（含表格、147 字）：内容 930px < 上限 → 球按内容定高、不滚动、不截断 ✅
```

## DSH 接口速查（改客户端时用）

```
POST http://127.0.0.1:3080/api/<namespace>/<method>      # 斜杠，不是点
{"type":"client-request","rpcId":"..","method":"<ns>/<m>","payload":{"args":{..}}}
→ {"type":"server-response","rpcId":"..","result":{"ok":true,"value":..}}   # 失败也是 HTTP 200
参数 wire 名：session/list → _request；其余 → request
流：ws://127.0.0.1:3080/api/remote.mux
    → {"type":"open","streamId","endpoint":"session/follow","payload":{"args":{"request":{...,"assistantStream":true}}}}
    ← {"type":"item","value":{"type":"assistant-stream","frame":{"type":"chunk","chunk":{"type":"text-delta","text":".."}}}}
    ← {"type":"item","value":{"type":"event","event":{"type":"assistant/message"|"turn/end",...}}}
认证：GET /?token=<~/dsh.log 里的 launch token> → 303 + 签名 cookie（dsh-auth-<sha256(authority)>）
```
完整逆向记录：`android/docs/dsh-external-client-spec.md`（916 行）。

## 还没做 / 可继续的方向

1. **镜像跟随**：跟"最近更新的非子智能体会话"，20 秒轮询切换；想固定某条会话需要另加设置。
2. `android/docs/probes/*.mjs` 是当时跑通的 Node 探针，可复用。
3. 仓库里 `dsh-liquid-glass-siri/` 是早期做的**网页版** DSH 插件（用户最终没要），
   独立功能、可从 profile 移除：`dsh plugin --profile web remove dsh-liquid-glass-siri`。
4. 设置页很长、WebView 不能滚动（实测自动化 swipe 无效），要触发「思考中」只能：
   在搜索框里打字发送（真机路径），或先把 DSH 区块挪到页首再重装。球体截图在
   `/data/data/com.glslplayground.app/files/ball-capture.png`（root 可读，Termux 要经
   `su -c cp` 到 `~/oppo-watch-ai/.preview/`，`/sdcard` 与 `/data/local/tmp` 在 Termux 命名空间里看不见）。
   （注：用 `mobile` 工具的 swipe 其实能滚 WebView，这条老结论已经不成立。）

## 审批 / 提问面板（本次新增，重点看这一节）

### 它解决什么

以前 DSH 要你点「批准/拒绝」或回答问题时，手机上什么提示都没有 —— 必须切到浏览器、还得正好
停在那条会话上才看得见。现在这些会**变成球本身**：同一颗水晶球展开成一张近方形的玻璃卡片，
上面是标题与说明、下面是可点的选项（以及自由输入），点一下就直接回给 DSH。

### 协议：审批与提问是两种东西，别混

两条都走 Gateway 的 `$events` 瀑布（浏览器也是这么收的）：

```
ws /api/remote.mux
→ {"type":"open","streamId":"…","endpoint":"$events","payload":{"args":{}}}   # args 必须是空对象
← {"type":"item","value":{"type":"ready","clientId":"<uuid>","host":{…}}}
← {"type":"item","value":{"type":"waterfall","event":"approval/request",
     "eventId":"<uuid>","agentId":"…",
     "request":{"toolName":"bash","reason":"…","displayReason":{"zh":"…","en":"…"}}}}
← {"type":"item","value":{"type":"waterfall","event":"user-questions/request",
     "request":{"questions":[{"id","question","detail?","header?","multiSelect?",
                              "options":[{"label","description?"}]}]}}}
```

**应答**都是普通 HTTP RPC：

```
POST /api/$events/result
{"type":"client-request","rpcId":"…","method":"$events/result",
 "payload":{"args":{"clientId":"…","eventId":"…","outcome":{"kind":"result","value":<V>}}}}
```

`<V>` 的形状**按事件类型不同**（这是最容易做错的地方）：

| 事件 | `<V>` | 出处 |
|---|---|---|
| `approval/request` | 字符串 `"allowed-once"` 或 `"rejected"`，**只有这两种** | `dsh-client-ui-approval/lib/types/client/locales.d.ts`（`allowOnce` / `reject`）、`PendingApproval.answer(outcome: ApprovalDecision)` |
| `user-questions/request` | **对象** `{"answers":[{"id","selected":[label…],"custom"?}]}` | `dsh-user-questions/lib/types/types.d.ts` 的 `AskUserQuestionAnswer` |

提问的**自由输入**就是答案对象里的 `custom`（`AskUserQuestionAnswerItem.custom`）。
还有一个语义坑，照抄了 DSH 自己客户端的做法
（`dsh-client-ui-user-questions/lib/client.js`）：

```js
selected: custom === "" || item.multiSelect === true ? value.selected : [],
...custom === "" ? {} : { custom }
```

也就是**单选时「自己写」与「选一个」互斥** —— 写了自由文本就把 `selected` 清空；
多选（`multiSelect`）才允许「选了若干 + 再补一句」。本实现照此办理。

`outcome` 只认三种（`api-gateway` 的 `parseRemoteEventResult`）：
`{"kind":"result","value":…}` 是终局 · `{"kind":"rejected",…}` 是抛错 · `{"kind":"next"}` 是
「我不管，交给下一个应答方」。**千万别自动回 `next`**：Gateway 是「谁先答谁算」，
手机端抢答 `next` 会把浏览器那边的按钮一起弄没。所以本实现只在用户真的点了才回结果。
结算后 Host 会给所有拿到过这条请求的客户端推 `{"type":"cancel","eventId":…}`，收到就收球。

### 界面：同一颗球，别开第二颗

用户明确要求：**不要再弹第二颗球**，就用这一颗。所以面板是：

```
      ┌──────────────────────────────┐
      │ 选择运行模式                   │ ← header / 标题（审批时是工具名）
      │ 打开 QQ 需要操作屏幕…          │ ← question / detail（审批时是 reason）
      │ ┌──────────────────────────┐ │
      │ │ ① 前台 Foreground         │ │ ← 选项：数量/文案/描述/多选全由 DSH 定
      │ │   在物理屏幕上显示…        │ │
      │ └──────────────────────────┘ │
      │ ┌──────────────────────────┐ │
      │ │ ② 后台 Background         │ │
      │ └──────────────────────────┘ │
      │ ┌──────────────────────────┐ │
      │ │ 或者在这里自己写…  [发送]  │ │ ← 自由输入 → custom
      │ └──────────────────────────┘ │
      └──────────────────────────────┘
```

五条已经踩过坑的规则（前两条都是实拍反馈里抓出来的）：

1. **球要近方形**：文字块宽度预算 = 屏宽 × `AP_TEXT_W_RATIO`（0.60），交互层比它宽一点；
   球取两者更宽的那个定宽，高度再兜一个「至少 0.9 倍宽度」，所以长内容也长成方的而不是一条。
   圆角 `corner = 0.28 × min(halfW, halfH) / halfH`（下限 0.28）→ squircle 观感。
2. **内容必须在球里**：交互层（选项 + 输入框）实测高度算进球高；总高超过屏高 66% 时依次
   压：选项内边距 → 正文字号 → 正文行数。**球变大后窗口要立刻跟上**
   （`applyApprovalGeometry()` 之后马上 `updateApprovalWindow()`），否则球面与内容一起被窗口裁掉。
3. **量之前先给宽度**：每个 TextView 都要 `setMaxWidth()` 再量，否则按「一行不换行」量出假高度，
   球就定小了。
4. **别每帧摆窗口**：几何只在「档位变化」时算一次（`apGeomKey`），两块内层窗口只在实测
   `x/y/w/h` 变了才 `updateViewLayout`（`apLayoutKey`）。上一版每帧重摆正是「疯狂改宽高」
   和「点了没反应」的根因。
5. **几何变量必须两套**：面板用 `aprHalfX/aprHalfY/aprCorner/aprBlack`，球自己用
   `tHalfX/tHalfY/...`。**绝不能共用** —— 共用时镜像打字 / 静默清空 / 新一轮提问只要调一次
   `measureSiriTargets()`，球就被改回小球尺寸，而面板内容还挂着 → 内容溢出玻璃
   （用户实拍就是「一开始正常，球自动变回小球之后就不正常了」）。
   另外 `siriTick` 在面板期间每帧把目标值钉回 `apr*`，`measureSiriTargets()` 与
   `applySiriText()` 在面板期间直接让路 —— 三层保险。
6. **交互层要限高 + 可滚**：选项多/文案长时，交互层外包一层 `ScrollView`，高度夹在
   「面板上限 − 文字块 − 缝」，超出的部分自己滚。没夹高度时它会**盖住上面的说明文字**
   （实拍里「批准」直接压在 reason 上）。
7. **面板期间必须藏掉「拖动层」**：这是「按钮看得见、点了没反应」的真凶。
   那个透明拖动层虽然只给了 `2x2` 的初始尺寸，但 `FLAG_LAYOUT_NO_LIMITS` + `TOP|START`
   会让它**真的铺满整屏**（`dumpsys` 里 `frame=[0,96][1080,2296]`），而它加在按钮窗口
   **之后 → z 更高** → 把选项按钮的点击全吃掉了。球在面板期间本来就在屏幕中间、
   不需要在球上拖动，所以直接 `setVisibility(GONE)`，点击就归按钮窗口了。
   **排查手法**：`dumpsys window windows | grep -A6 glslplayground` 看每个窗口的
   `Frames:`（真实屏幕矩形）与 `isVisible`；再用 view 上的 `OnTouchListener` 打一行
   日志，就能区分「没点到」和「点到了但被吞」。注意 `mAttrs` 里的坐标**不含**系统 inset
   （这台机器 +96px 状态栏），真实位置一律看 `Frames:`。
8. **面板底色：一层淡淡的黑，绝不能盖住玻璃**。这一条被用户打回过四次，最终结论是：

   **球壳的液态玻璃（折射 / 色散 / 黑顶 / 赤道亮线 / 底缘）必须原样可见** —— 苹果那张
   天气卡片就是这个道理：壳是玻璃，只有文字/按钮那几块有一层浅底。
   面板底只做两件事：**给文字垫底** + **上浓下浅**。

   ```glsl
   // 着色（~331 行）
   float panelFade = 1.0, tBlackK = 1.0;
   if(uPanelMode > 0.5){
     float rise = clamp((1.0 - ny) / 1.5, 0.0, 1.0);   // 0=球顶 1=黑区底
     blackCol  = vec3(0.0);                            // 纯黑，不带任何颜色
     tBlackK   = mix(1.0, 0.12, pow(rise, 2.6));       // 顶上纯黑 → 向下**加速**变透
   }
   col = mix(col, blackCol, blackMask*uTopDarken*tBlackK);
   // 输出（~371 行）—— 预乘 alpha，遮罩强度要和着色用同一个系数
   aa *= mix(1.0, panelFade, blackMask*uTopDarken*tBlackK);
   gl_FragColor = vec4(col * aa, aa);
   ```

   Java 侧对应的一层（文字块的底）：`AP_PANEL_BG = 0x57101419`（≈34% 不透明），
   并用 `setShadowLayer` 给标题/正文加软阴影保可读。

   **改法**：`1.0` = 顶上的压暗强度（**纯黑**，用户要求顶上就是纯黑），
   `0.12` = 底部最浅（越小越透），**`2.6` = 渐变指数** —— 指数 >1 就是「加速」：
   前段几乎不变、后半段掉得很快；想要平缓就调向 1，想要更陡就加大（3~4）。
   文字底看 `AP_PANEL_BG` 的 alpha（现在 `0x57`）。

   走过的弯路（别再回去）：
   - ① 黑区往强调色偏 → 整块发红，不要；
   - ② 「黑底 + 底部白光刺眼」→ 嫌亮区太大；
   - ③ 「纯黑 + 底下靠 alpha 变透（0.26）」→ **把玻璃全盖没了，用户退货**；
   - ④ 「半透明浅黑 + 平缓渐变」→ 用户要求**顶上必须纯黑**、且**往下加速**变透；
   - ⑤ 现在：`mix(1.0, 0.12, pow(rise, 2.6))` —— 顶上纯黑、越往下掉得越快，
     底部只剩 12%，玻璃的折射/色散在下半段整片可见。
   - `uPanelAccent` 只填不用；普通球 `uPanelMode = 0` 完全不受影响。
   - **选项按钮保持语义色**（批准=淡绿、拒绝=淡红、提问选项=蓝）—— 用户要求按钮不变。

9. **自由输入（custom）要能真的打字，有三个坑**（用户报过「点输入框无效」）：
   1. **窗口必须可聚焦**：`FLAG_NOT_FOCUSABLE` 的悬浮窗里 EditText 永远收不到输入法。
      所以输入行**单独一个窗口**，去掉该 flag，并设 `softInputMode = SOFT_INPUT_ADJUST_RESIZE`。
   2. **焦点/键盘要延后到抬手之后**（`main.postDelayed(approvalFocusTask, 220)`）：
      键盘一弹窗口就上移，同一串手势的 ACTION_UP 会落到别的控件上 —— 实测直接把选项点掉、
      面板消失。
   3. **窗口焦点会被抢**（`dumpsys` 里 `mCurrentFocus=null`），这时 `showSoftInput` 会被静默丢掉。
      所以再挂一个窗口焦点监听，拿到焦点后**补发一次** `showImeNow()`。
   排查手法：`InputMethodManager: showSoftInput() view=android.widget.EditText{... .F...I.}`
   里那个 `I` 就是「EditText 拿到了 IME 焦点」，能区分「没聚焦」和「聚焦了但键盘没起来」。

### 面板整体缩放（`AP_SCALE`）

用户说默认尺寸偏大，现在的总开关是 `FloatingBallService.AP_SCALE = 0.55f`：
**字号 / 按钮最小高 / 内边距 / 间距 / 圆角全部乘它**，等比缩放。
想更大更小只改这一个数（`1.0` = 早期那版大尺寸）。
实测 0.55 的效果：文字块 648×340 → 464×161，按钮层 602×415 → 336×229，输入行 602×141 → 336×99。

### 权限预设：没有「会问」的档就永远不会有审批

审批只在会话的 `approval=ask` 时才产生。本机 `web` profile 已经改过
（`~/.dsh/profiles/web/cordis.patch.yml`，`id: permission`）：

- 新会话默认 `danger-full-access` + **`ask`**（档名「全权限 · 每次都问」）→ 每次工具调用都会问你；
- 另留 `workspace-write` + `ask`（「工作区内 · 每次都问」）。

**本机（Android/Termux）没有可用沙箱后端**：`workspace-write` 下 shell 会 fail-closed
（`sandbox mode "workspace-write" … no sandbox backend is usable on this host`）。
而且更坑的是：**在只读/受限档下，host 并不会为一次普通 bash 调用产生 `ask`** ——
实测 `read-only` 档下 agent 直接写文件成功、`tool/result` 直接返回，全程没有 `approval/request`。

不过 **`user-questions/request` 是会正常产生的**（agent 遇到「本机没沙箱后端，怎么办」这类
岔路就会问），而且走的是同一条瀑布、同一套 UI —— 所以验收用提问这条路最省事。

### 怎么调 / 怎么验

```bash
# ① 只读探针：看 $events 到底转发来什么（可选自动应答）
node android/docs/probes/dsh-approval-probe.mjs listen [--allow|--reject]
# ② 造场景：新建会话 / 切权限预设 / 发会触发审批的 prompt
node android/docs/probes/dsh-approval-trigger.mjs create | permission <sid> <preset> | prompt <sid> | events <sid>
# ③ 调试回路：喂假审批 / 假提问，把面板叫出来对版式
node android/tools/approval-debug-server.mjs        # 端口 = DSH 端口 + 1
curl -s -X POST -d '' http://127.0.0.1:3081/ballapproval/push     # 入队一条默认假审批
curl -s http://127.0.0.1:3081/last                                # 看球上点了什么
```

设置页「审批球」区块里有三个按钮，不用命令行也能看效果：
**叫一条假审批（调版式）** / **叫一条假提问（多选项+输入）** / **撤掉球上的审批**。

为什么假审批走 HTTP 而不是文件：**Termux 与应用是两套挂载命名空间**
（Termux 里没有 `/data/user`，写进 `/data/data/...` 应用看不见），`/data/local/tmp` 又是
SELinux 禁区，所以只能用 127.0.0.1 上的小服务当信箱。安卓侧在
`DshApprovalClient.debugPollLoop()`，**必须在后台线程发 HTTP**（主线程会
`NetworkOnMainThreadException`）。

### 已验证 / 未验证

- ✅ `$events` 订阅在真机上打通：`events ready clientId=…`。
- ✅ **真提问跑通完整来回**：agent 问「本机没沙箱后端怎么办」，球上显示
  `选择运行模式 · 提问 · 选项 3 +输入`，点选项后设置页回读到
  `已回传：{answers:[{id:permission_choice, selected:[真的切到 workspace-write，我接受 shell 命令会失败]}]}`
  —— 答案对象形状与 DSH 客户端一致。
- ✅ 版式：提问面板实测 球窗口 1024×1350、文字块 648×340、选项+输入 798×747，三者都在球内。
- ✅ **「点了批准没反应」已定位并修掉**：是全屏拖动层吃掉了点击（见上面第 7 条）。
  修完实测 `option DOWN raw=540,1420` → `approval tap → allowed-once` → 调试回路
  `★ 球上点了： "allowed-once"`，整条通了。
- ✅ **自由输入（custom）已在真机验通**（用户确认「已经成功输入了」）。
  实现细节与三个坑见上面第 9 条。
- ⚠️ 多选（`multiSelect`）逻辑写了（勾选 + 发送），但还没拿到一条真的多选问题来验。
- ⚠️ 没验证过的还有：选项特别多（>6）时球会封顶、内容压缩的顺序好不好看。
- ⚠️ 调试回路的 `debug fetch #n → HTTP 204` 每 250ms 一条日志，比较吵；
  正式用可以把 `DshApprovalClient.debugPollLoop()` 的 sleep 调大，或删掉这段调试口。
- ✅ **「面板自己消失 / 点了没反应」已定位并修掉**：那是几何共用的副作用 ——
  球被 Siri 那条路改小后，内容/交互层的位置与窗口来回打架，过程里会冒出没有真实触摸的
  `approval tap` 把面板答掉。改成两套几何变量 + 每帧钉住 + 交互层限高滚动之后，
  连续多次弹出都能长时间挂着，`option touch` 日志也再没出现过。
- ⚠️ 长文案仍会被截断（正文 `maxLines` 上限 12，放不下会缩字号再减行），
  目前没有「正文可滚动」；如果遇到很长的 reason 想完整看，需要再加一层滚动。
- ⚠️ 选项排现在是竖排单列（每行一个、左对齐、可带描述）。选项很短很多时竖排会偏长，
  靠滚动兜住；如果想让短选项并排成两列，需要在几何里再做一次行装箱。

## 状态动画：沉降 + 幅度包络（2026-10-15 新增）

**用户反馈**：「处理中的动画在没有真正回复时不要恢复为 Siri 波和空白状态，不然老是在处理中和
Siri 波之间跳动；而且处理中的动画老是断断续续，不丝滑。」

**根因**（两个叠加）：

1. **状态抖动**：一个回合里 DSH 会多次报 `THINKING / ANSWERING / IDLE`（多段生成、工具调用
   之间各来一次），而 `IDLE` 一到就 `restoreWave()` 把形态交还给用户的 Siri 模式 →
   球在「流体圆点」和「Siri 波」之间来回闪。
2. **切换生硬**：`waveMode` 是 shader 里的分支（1=波 / 2=圆点），改它就是硬切换脸；
   同一个 `uWaveAmp` 管着两者，所以也没有交叉淡化的余地。

**做法**：

- **沉降（settle）**：`IDLE` 不当场生效，压后 `IDLE_SETTLE_MS = 1200`；期间任何一次真实活动
  （`THINKING/ANSWERING/LISTENING/TRANSCRIBING`）都会取消它。`ANSWERING` 仍然立即生效
  （一旦开始出字就得是 Siri 波 + 文字），所以短回复也不会延迟。
- **幅度包络（envelope）**：`GlassRenderer.waveGate`（0..1）乘在 `uWaveAmp` 上，只影响波/圆点，
  **不动玻璃壳**。换形态时先淡到 0 → 在"无波无点"那一瞬切 `waveMode` → 再淡回 1
  （`WAVE_FADE_MS = 180` 单程）。切换点被藏进"什么都没有"的那一帧里，看不出跳变。
- 日志会打 `gate→` / `pending=` 和 `wave mode -> N（幅度为 0 时切换）`，便于回归。

**实测（用调试口子喂状态，脚本可复现）**：

```
场景A 抖动：THINKING→IDLE→THINKING→IDLE→THINKING
  22:10:59.229  THINKING: gate→0.0 pending=2
  22:10:59.465  wave mode -> 2（幅度为 0 时切换）
  22:10:59.634  IDLE  → 压后（没有交还）
  22:11:00.044  THINKING → idle settle 被 THINKING 取消（回合还没完）
  22:11:00.993  THINKING → 再次取消
  → 三次 IDLE 假信号一次都没让球回到 Siri 波

场景B 真结束：THINKING→ANSWERING→IDLE
  22:11:07.196  ANSWERING: 换回 Siri 波（原设计：出字时就得是波+文字）
  22:11:07.673  喂入 IDLE
  22:11:08.992  idle settle 到点 → 交还（安静了 1200ms，正好符合设定）
```

球体区域像素校验：IDLE 与 THINKING 两态都有内容（均值 28–33），**没有空白帧**；
淡出只掐掉波/圆点，玻璃壳一直在。

**动画状态机的调试口子**（回归用，别删）：
`touch /sdcard/Android/data/com.glslplayground.app/files/state-THINKING`
（可选值 `IDLE/THINKING/ANSWERING/LISTENING/TRANSCRIBING/OFF`）。
注意触发文件必须放在**球自己能删**的目录 —— 放 `/data/local/tmp` 会因为 SELinux
（`shell_data_file` / untrusted_app 只能读不能删）删不掉，结果每 120ms 被反复消费一次。

## 球体几何：有文字就保持近方形（2026-10-15 新增）

**用户要求**：「只要有文字就尽可能变成近方形，只有结束和开始时是初始状态，处理中只要出现过
文字后就一直是近方形。」

**改了什么**（都在 `FloatingBallService`）：

1. **1~2 行也做成近方形**：原来只有 ≥3 行才是大圆角方形，1~2 行是横向胶囊（宽 ≥ 高×1.35），
   而且只补宽度 —— 实测长文字会变成 `1044x573`（5:1 横条），不是近方形。
   现在两个方向都补齐：短边往长边靠（上限 1.18 倍），夹在「宽 ≤ 屏宽 85%、高 ≤ 屏高 70%」内，
   圆角取短边 38% → squircle。
2. **回合内只增不减（textHold）**：本轮只要出现过文字，就记住量过的最大几何；
   文字被清空（DSH 一个回合里会多次「出字 → 清空 → 再出字」）时**不缩回圆球**，
   后面来一小段字（`AB` 这种）也不缩。文字层照常藏掉，只留一个空心的近方形玻璃壳。
3. **释放时机 = 本轮真正结束**：`idle settle` 到点（安静 1.2s）或助手 `OFF`。
   释放时**直接落回初始形态**（787x573 圆球），不拿残留文字再量一遍
   （否则会停在「按最后那段文字算的方形」，实测 705x705）。
   另有兜底：出字后静了 8s 还没结束信号 → 自动释放（防助手中途被杀导致球一直不缩）。
4. 顺手修了一个**一直没生效的老口子**：`ballshot`（喂球内文字 / 存一帧球体截图）
   原来指向 `/data/local/tmp` —— 球在那儿读得到删不掉，`delete()` 失败就 return，
   等于这个功能从来没工作过。现在改到
   `/sdcard/Android/data/com.glslplayground.app/files/ballshot`。

**实测（窗口尺寸即量具，脚本见下）**：

```
初始            787x573  圆球（初始态）
① 出短文字      653x653  正方形
② 出长文字      748x748  正方形（只增不减）
③ 清空后再来2字 787x787  仍是正方形，没缩回去
④ 回合结束      787x573  回到初始圆球
```

复现方式：`printf '文字' > <GATEDIR>/ballshot` 喂文字，
`touch <GATEDIR>/state-ANSWERING` / `state-IDLE` 驱动状态（见上一节）。

## 文字 / 动画的优先级与停留（2026-10-15，第二轮）

**用户要求**：① 文字和 Siri 波不能同时出现；② 出现文字时停留久一点；
③ 处理中动画要连续（agent 操作/执行命令/思考都算处理中）；④ Siri 波只在初始和结束状态出现。

**规则（现在是显式的优先级，不再"看着像"）：**

| 球里有没有字 | 状态 | 显示 |
|---|---|---|
| 有字 | 任意（除 LISTENING/TRANSCRIBING） | **只有文字**（`waveGate→0`，不出波也不出圆点） |
| 无字 | LISTENING / TRANSCRIBING | Siri 波 |
| 无字 | THINKING / ANSWERING | 流体圆点（处理中） |
| 无字 | IDLE（本轮沉降到点） | 交还用户自己的 Siri 模式设定 |

- **形态切换只在"画面里没有内容"时发生**（`waveGate == 0`）：文字期间不做任何形态切换，
  等文字消失那一刻按当时状态切一次 → 处理中动画不会被打断，所以看起来是连续的。
- **ANSWERING 不再切回 Siri 波**（原来会，导致"出字时波一闪"）：出字属于处理中。
- **停留时间**：球内文字 `IDLE_CLEAR_MS` 1500 → **4000ms**；几何/动画回初始 `IDLE_SETTLE_MS`
  1200 → **3500ms**。后者略短，所以正常顺序是「文字消失 → 交还 Siri 波 → 球回初始圆球」。
- 若"结束"先到而字还挂着：IDLE 不交还波（保持静默），等 `hideSiriTextNow()` 那一刻再交还
  —— 这就是"文字和波不同时出现"的兜底。

**实测（脚本喂状态 + 文字）**：

```
22:26:04.998  THINKING: gate→0.0 pending=2
22:26:05.234  wave mode -> 2（幅度为 0 时切换）
22:26:05.690  text hold: on
22:26:07.141  有字优先：状态 ANSWERING 暂不出动画，闸门→0
22:26:13.423  文字消失 → 交还 Siri 波（本轮已结束）
22:26:13.463  wave mode -> 1（幅度为 0 时切换）
几何：787x573 圆球 → 出字 653x653 方（文字期间一直 653x653 不变）→ 结束回 787x573
```

## 文字包裹与「不出屏」的硬保证（2026-10-15，第三轮）

**用户要求**：文字永远被包裹在近方形玻璃内，不能超出屏幕。

**两个真问题（都实测复现过）**：

1. **文字层宽度没跟着球缩**：文字层宽度原来只在"文字变化"时量一次（`measureSiriTargets` 里
   `setMaxWidth` + 测量），球后来变小了、文字层还留着旧的大宽度 → 横向溢出玻璃
   （实测球 658 宽、文字层 697 宽，两侧各冒 19px）。
   **改法**：`updateSiriTextPos()` 每帧按**当前几何**算宽度上限
   （`2 * base * aHalfX * aMaster - 2*dp(28)`），并且**不再调用 `setMaxWidth`**
   （那会污染球的测量），只用 `AT_MOST` 量一次用于排版与居中。
2. **球本身可以被拖/长到屏幕外**：`updateSiriWindow()` 只保住"球心不动"，球长大之后会顶出屏幕；
   拖动夹取也只保证"露出四分之一"。文字跟着一起出界。
   **改法**：`clampBallOnScreen()` —— 按当前几何算玻璃轮廓的屏幕矩形，把**整颗球**平移回屏内
   （留 `sysOffset` 补系统顶部 inset）。挂在三处：`moveBallDrag`（拖完立即夹）、
   `updateSiriWindow`（长大之后夹）、`syncWinPos`（周期性兜底，有自比较不会抖）。
   另外文字层自身也做一次 x/y 夹取 + "不低于球顶、不高于球底"的垂直夹取。

**实测**：

```
glass(玻璃) : (147, 1016, 934, 1589)
text(文字层) : (348, 1076, 733, 1280)
横向: 文字宽 385 vs 玻璃宽 787 → ✅ 在玻璃内
纵向: 文字底 1280 vs 玻璃底 1589 → ✅ 在玻璃内
屏内: ✅
```

**注意**：夹取依赖 `renderer.winY - params.y` 这个系统 inset 差值，换 ROM/换分辨率后
应当重新实测一次（看 `clampBallOnScreen` 的日志是否合理）。

## 球体玻璃：按"真实物理观感"重调 + 参数热重载（2026-10-16）

**用户反馈**："真实物理世界的液态玻璃不应该是放大背景吗，怎么你是缩小的"、"背景一丁点变形都看不到"。

**先排除误判**：保存的 `float_params` 与演示页滑杆默认值**逐项一致**
（refraction=200/zoom=1/dome=0.4/blur=1.8/topDarken=0.93/black0=-1/black1=-0.01/edgeBleed=0.11），
不是被改坏的 —— 是**这组数值下变形本来就看不出来**。

**关键公式**（`uZoom` 的方向很容易搞反）：

```glsl
suv = cuv + (suv - cuv) / uZoom;    // uZoom=2 → 采样范围 1/2 → 放大 2 倍
                                    // uZoom=0.55 → 采样范围 1.8 倍 → 缩小（我一开始设错成这个）
vec2 cw = (c + grad*d*0.5) / uZoom; // ★ Siri 波的坐标也被除以 zoom
                                    //   → zoom 太大(2.0)会把波带一起拉细拉扁
```

所以**放大应该主要来自折射位移 `refraction`（径向，circleMap 已给物理曲线），
zoom 只做轻微收窄（≈1.1）**。第一次把 zoom 设成 2.0 虽然"变大了"，
但那是整体缩放而非玻璃的径向放大，还把 Siri 波拉细了。

**最终默认值**（已固化进 `GlassRenderer`，重装/换机不丢）：

| 参数 | 原 | 新 | 物理含义 |
|---|---|---|---|
| `refraction` | 200 | **620** | 径向位移 = 玻璃球的放大与拉弯（主因） |
| `zoom` | 1.0 | **1.1** | 轻微视野收窄（放大的辅助项） |
| `aberration` | 10 | **26** | 边缘色散 = 彩虹边 |
| `edgeBleed` | 0.11 | **0.24** | 边缘亮环（全内反射的观感） |
| `blur` | 1.8 | **1.9** | 玻璃体内柔和失焦 |
| `band` | 170 | **210** | 折射过渡带宽度 |
| `dome` | 0.40 | **0.35** | 球面法线外推 |
| `topDarken` | 0.93 | **0.40** | 黑区深浅 |
| `blackOpacity` | 1.0 | **0.28** | 黑区不透明度（太实会盖掉折射） |

对照图：`android/docs/ball-glass-physics.png`（球内可见背景被放大拖弯 + 边缘彩虹）。

**新增：参数热重载**（`pumpParamsReload()`）。此前 `refraction/zoom` 这类只在服务启动时读一次，
每试一组值都得重装 APK（这是排查慢的主因）。现在改完 `float_params` 后碰一下
`files/ballshot` 钩子即可生效：

```bash
printf '' > /sdcard/Android/data/com.glslplayground.app/files/ballshot
```

## 球体玻璃外观：为什么"会自己变"以及怎么锁住（2026-10-16 凌晨排查）

用户反馈"球不知道什么时候变成只有下半部分有玻璃"、"太透明、能看清背景"、
"一点弯曲都没有"。排查 15+ 轮后用**数据**定位到三件事，都不是"玻璃被改坏了"：

### 1. 演示页一打开就会覆盖球的外观（根因级隐患）

`liquid-glass.html` 里滑杆初始化 `bind()` 会调用 `pushFloatParams()`，
页面加载末尾也会再 push 一次 —— **只要打开这个页面，滑杆值就推给悬浮球并持久化**。
所以"只是打开演示页看看"就会悄悄改掉球的外观（排查期间打开过多次）。

**改法**：页面默认不再自动推参数，需要时在控制台执行 `window.__pushToBall = true`。

### 2. 排查陷阱：热重载会跳过截图，读到的是旧 png

`pumpShotRequest()` 里我加的 `if (pumpParamsReload()) return;` 会在参数变化时**跳过截图**，
于是 `ball-capture.png` 是上一次的旧文件。连续几轮对着**过期画面**下结论，
把"球是实心还是透明"判断反了好几次。已修成"热重载后继续截图"，并在注释里写明别再 return。

### 3. 参数值被测试脚本留在极端值

A/B 测试时把 `refraction` 设成 0（关掉折射）后忘了还原 → 用户看到"一点弯曲都没有"。
`refraction=0` 就是字面意义上的"完全无折射"。

### 排查这类问题的正确姿势（本轮教训）

1. **先确认截图是"当下"的**：删掉旧 png 再触发，或用文件 mtime 校验；
2. **不要靠"打开页面看看"来对比** —— 演示页会推参数；
3. **改参数前先记录原值**，测试脚本必须成对还原；
4. 球体渲染好坏的**客观指标**：球心区的平均亮度与对比度（std）。
   清晰背景 ≈ 亮度 90~120 / std 45；折射内容 ≈ 亮度 26 / std 14。

### 当前锁定的外观参数（= 用户认可的 10-15 20:56 状态）

| 参数 | 值 | 说明 |
|---|---|---|
| `refraction` | 200 | 边缘折射位移（中心为 0，所以中心看着"直"、边缘弯 —— 这是正常玻璃观感）|
| `zoom` | 1.0 | 不额外放大 |
| `blur` | 0.3 | 玻璃体失焦（口味滑杆，2026-10-16 由 1.8 一路下调到 0.3 = 不模糊，见文末「失焦 blur」一节）|
| `topDarken` | 0.93 | 黑区强度 |
| `edgeBleed` | 0.11 | 赤道线/边缘亮环 |
| `aberration` | 10 | 色散 |
| `waveAmp` | 1.9 | Siri 波幅度 |

## ★「球是半透明图层 / 贴纸」的真根因：ColorOS 把 NOT_TOUCHABLE 悬浮窗钉成 alpha 0.80

**2026-10-16 02:26 定位并修复（前面 15+ 轮全找错方向，因为根因根本不在本项目里）。**

### 现象（用户原话）

> "失去了液态玻璃的真实效果，看起来像半透明图层"
> "背景和折射后的同时出现，但背景远比折射效果清晰"

### 真根因：一加 / OPPO 的 WindowManager 有一条 OEM 规则

```bash
$ adb shell logcat | grep "setting alpha"
W WindowManager: App com.glslplayground.app has a system alert window (type = 2038)
  with FLAG_NOT_TOUCHABLE and LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80
  to let touches pass through (if this is isn't desirable, remove flag FLAG_NOT_TOUCHABLE).
```

**只要系统悬浮窗（TYPE_APPLICATION_OVERLAY = 2038）带 `FLAG_NOT_TOUCHABLE`，
系统就把窗口 alpha 强行压到 0.80。** 球的玻璃层为了"只画不摸"正好带着这个 flag，
于是屏幕上永远只有 80% 不透明 —— 剩下 20% 是**未经折射的真实背景**直接叠上来，
既有折射又有清晰原背景，观感就是"半透明贴纸"。

量出来的证据（别再靠肉眼猜）：

```bash
dumpsys window windows | grep -A3 "Window .*glslplayground"   # mAttrs ... alpha=0.8
dumpsys window ... mShownAlpha=0.8 mAlpha=0.8 mLastAlpha=0.8  # 合成权重确实是 0.8
dumpsys input | grep glslplayground                            # alpha=0.80
dumpsys SurfaceFlinger | grep -B1 alpha=0.799805                # 图层 alpha
```

### 为什么前面十几轮全没找对

- shader 里球内 `aa=1`、预乘输出都**没问题**：`b557c74` 的纯红实验量到"合成只有 ~55% 不透明"，
  结论（"EGL 配置 / SurfaceView format 把 alpha 打了折"）是**错的** —— 那是 0.8 的窗口 alpha
  再叠上黑区遮罩后的平均值。顺着这条错线索改了两轮 alpha 公式，全是白改。
- 窗口 alpha **不在本仓库**：`grep -rn alpha android/src` 一处赋值都没有，
  所以"读代码找 0.8"永远找不到。它是系统在 `relayout` 时改的。
- 排查这类"屏幕上和 shader 算的不一样"的问题，**先量合成，再怀疑 shader**：
  `dumpsys window`（mAttrs.alpha / mShownAlpha）、`dumpsys input`（InputWindowHandle alpha）、
  `logcat | grep WindowManager`（OEM 改了什么）。

### 修法：空可触摸区域，而不是 FLAG_NOT_TOUCHABLE

`FloatingBallService.makeTouchTransparent(View)`：

```java
// 反射挂 ViewTreeObserver.OnComputeInternalInsetsListener（@hide，android.jar 里没有）
info.setTouchableInsets(TOUCHABLE_INSETS_REGION);   // = 3
info.touchableRegion.setEmpty();                    // 输入系统命中不到 → 相当于 NOT_TOUCHABLE
```

窗口 flags 同时把 `FLAG_NOT_TOUCHABLE` 换成 `FLAG_NOT_TOUCH_MODAL`。三处都改了：

| 窗口 | 作用 |
|---|---|
| 玻璃层（`params`，glView） | **球本体**，最关键的 0.8 就是它 |
| Siri 文字层（`siriParams`） | 球里的回答文字（也会被压成 0.8）|
| 审批面板底（`approvalContentParams`） | 审批卡片的底（同一个 flag，同样的病）|

代理触摸窗口（球的唯一触摸层）平时可触摸，只在搜索层 / 卡片菜单 / 整屏玻璃这些
本来就不该接管手势的状态里切 `FLAG_NOT_TOUCHABLE`（`applyBallTouchability()` 一处管）。

### 验证（改完必须逐条过）

```bash
dumpsys window windows | grep -A2 "Window .*glslplayground"   # 玻璃窗 mAttrs 里**不再有** alpha=0.8
dumpsys input | grep glslplayground                           # 玻璃窗 alpha=1.00 + touchableRegion=<empty>
logcat -d | grep "setting alpha to 0.80" | tail                # 只剩代理窗口（透明，无害）
```

### 顺手清掉的第二个坑：`float_no_skipshot` 调试开关

同一个 session 里还留着 `float_no_skipshot=true`（`b557c74` 排查时开的）。它让球不被
`SKIP_SCREENSHOT` 排除 → **球把自己的画面抓进折射背景**（自我采样）→ 球内显示的要么是
启动那一刻的旧桌面、要么干脆是球自己的上一帧，同样表现为"半透明贴纸且不实时"。

已从代码里**删掉这个口子**（`syncWinPos()` 里改为无条件打 `SKIP_SCREENSHOT`），
并在 `onStartCommand()` 里把设备上残留的值清掉。要看球体自带画面请用 `files/ballshot`
钩子（PixelCopy 只截本窗口），别再去动截屏包含关系。

排查这类问题的正确姿势（新增两条）：

5. 屏幕上看到的和 shader 算的不一致时，**先 `dumpsys` 量合成 alpha**，别急着改 shader；
6. **调试开关必须成对还原**，能删就删 —— 这个项目已经被"忘了关的开关"坑过两次
   （`float_no_skipshot` 一次、测试脚本留下的极端参数一次）。

## 玻璃的「失焦 blur」：唯一的口味滑杆（2026-10-16 定到 0.3 = 完全不模糊）

根因修掉（球恢复 100% 不透明）之后，用户按实物把 `blur` 一路往下调：
**1.8 → 1.2 → 0.5 → 0.3**。0.3 走的是 `buildBlur()` 里 `blur < 0.5` 那条分支 ——
**根本不做高斯模糊**，直接拿全分辨率抓屏当折射源：球内就是"清晰 + 被折射放大/拖弯 +
边缘色散"的画面，玻璃感全部来自折射位移 / 色散 / 黑顶 / 边缘亮线，像一枚干净的透镜。
（0.3 也正是这条管线最早的老默认值。）

| 值 | 观感 |
|---|---|
| `0.3` | **当前值**（代码默认 + 设备 `float_params` 已对齐）：完全不模糊，最锐 |
| `0.5` | 同一条分支的边界：0.5 已经进 else（还是有一点点糊） |
| `< 0.5` | `blur < 0.5` → 跳过模糊、用全分辨率原图（0.3 ≈ 旧默认） |
| `1.0 ~ 1.8` | 逐步回到"磨砂玻璃"；2.5+ 内容被抹成光斑 |

实测锐度（球内折射区平均 |Laplacian|，越大越锐；同机同桌面附近取值）：

```
blur 1.8 → 1.54     blur 1.2 → 1.68     blur 0.5 → 1.91     blur 0.3 → 2.20
```

注意：**演示页 `liquid-glass.html` 的滑杆默认还是 1.8**。打开那个页面不会自动推参数了，
但一旦手动推（控制台 `window.__pushToBall = true`）就会把球改回磨砂 —— 调完记得推回当前值，
或者干脆只在这个文件里改默认值。

调参后必须**成对落两处**：`GlassRenderer.blur`（重装/清数据后的默认）
+ 设备 `float_params.blur`（运行期覆盖）。设备那份要在 **App 停止时**改 XML
（SharedPreferences 有内存缓存，运行期改磁盘会被写回覆盖），改完重启球才生效。
改参数的小工具：`.preview/fix/set_pref.py <key> <value>`。

## 方形态（有字）的收尾：Siri 波沉到球底以下，只露波顶一点点（2026-10-16 定稿）

用户三轮收敛（**别再往回改**）：

1. 「有文字就变成近方形」——`measureSiriTargets()` 里那条近方形约束（已有）；
2. 「方形状态下只有 agent 回复最终结果才能出现 Siri 波」——判定用 **turn/end**（整轮结束）；
3. 「相当于方形状态下 Siri 出现在球的 y 轴往下，刚刚好能看到波的顶部一点点，不搞什么漫反射了」
   ——最终形态：**波照旧画（就是那条熟悉的 Siri 波），只把竖直锚点从黑区下沿挪到球底以下**。

### 实现（一个 uniform + 一行 mix，零额外开销）

```glsl
float waveAnchor = mix(b1, uWaveAnchor, uWaveInGlass);   // 圆球态=b1（原观感）；方形态收尾=-1.25
vec2 wp = vec2(cw.x/uHalf.x, -cw.y/uHalf.y - waveAnchor) / max(uWaveScale, 0.1);
```

- `uWaveAnchor` = `renderer.waveAnchorGlass`，默认 **-1.25**（ny：-1 是球底，所以整条波压在球底下方，
  球内只露出波顶那一小截）。设备侧 `float_wave_anchor`（千分比）可调，改完重启球生效；
  想露得多一点就往 -1.1 调，想只剩一丝就 -1.4。
- 闸门在 `refreshFinalWave()`：`siriEnabled && turnEnded && 有字` 才把 `uWaveInGlass` 置 1；
  有字但这一轮还没结束 → 幅度压到 0（一点波都没有），与老行为一致。
- 圆球态（思考/回答）**一点没动**：那边 `uWaveInGlass=0`，锚点仍是黑区下沿 `b1`。

### ⚠️ 走过的两条弯路（都别再捡回来）

1. **「把波烘进背景纹理再折射」**：新建 `bgWave` + 1/6 分辨率散光画布，每帧多一整趟全屏 pass。
   效果对，但用户实机反馈「太耗性能」→ 整体拆除。
2. **「球底漫反射散光」**（合成阶段十来个 ALU 的柔光带）：性能没问题（GPU 忙闲实测
   开/关各 5 次：40~43% vs 31~39%，差异落在桌面内容波动里），但**观感不是用户要的** ——
   用户要的是"看得见波顶一点点"，不是"一片看不到形状的光"。

结论：**别自造抽象**。用户要的就是那条现成的 Siri 波，位置往下挪而已。

### 黑区**保持原样**（用户明确要求）

中途试过"黑区紧贴文字"（`waveGap` 压到 0.005 + 过渡宽度 0.04），用户看过实机后说
「原本黑区就不改了，恢复成原来的渐变黑」——已全部撤回：

- `GlassRenderer`：`blackMask = smoothstep(b0, b1, ny)`（曾经的 `uBlackSoft` uniform 已删除）；
- `measureSiriTargets()`：`waveGap = clamp(siriBlackStep/1000, 0, 0.45)`、默认 100（0.10）；
- 设置页「文字与 Siri 波的安全距离」滑杆保持原样。

### 调试口子（回归这条链路用，一次性文件、用完即删）

```bash
D=/sdcard/Android/data/com.glslplayground.app/files
printf '文字' > $D/ballshot        # 往球里塞文字（顺带抓一帧到 files/ball-capture.png）
touch $D/state-TURNEND             # 等价于收到 turn/end → 球底露出波顶
touch $D/state-TURNSTART           # 等价于新一轮开始 → 收掉
```

注意两个坑：① 喂文字会**同时**触发抓帧，脚本要等 `ball-capture.png` 写完（末尾 IEND）
再拷，否则拷到半个 PNG；② 助手镜像会不停改写 `turnEnded`（我这边每发一条消息都会
`onAttemptStart → false`），所以调试时要**反复喂** `state-TURNEND`，别喂一次就截图。

## 方形态几何与波的锚点（2026-10-16 第三轮收敛：定稿）

用户这一轮提了两件事，都是"尺度感"问题：

> 「我在调整字号时为什么 Siri 波往上移动了？我需要方形状态时 Siri 要藏着底下
> 无论字体涨到哪里，还有一个就是方形不是说是正方形，且方形的宽要固定好
>（屏幕宽度的 85%）不可变，高度随文字往下涨的体积变化（不限字号）」

### 1. 几何：宽固定 78% 屏宽，高随文字往下长（2026-10-23 起上限 = 宽度 → 最大正方形）

`measureSiriTargets()`：

```java
float hxpx = maxBallW / 2f;                       // 宽 = 屏宽 85% 的一半，钉死
float hypx = max(base*minHalfY, th/2f + padY + dp(16));   // 高 = 文字实际高度
hypx = Math.min(hypx, maxBallH / 2f);             // 只受屏幕物理上限（0.88 屏高）
```

- 删掉了原来那段"近方形"约束（它会把高也拉成宽度，字号一大球就整体变胖）。
- 高度上限 = **屏幕的 85%**（`screenH * 0.85f`）；**不能再用 `winH`**（窗口高度本身随文字长，
  套进去会自我锁死）。0.85 屏高 ⇒ 球半高 ≤ 0.425 屏高 ⇒ `clampBallOnScreen()` 的夹取区间
  `[halfH, screenH-halfH]` 永远有效 ⇒ **球必然完整落在屏幕内，上下都不出界**（用户明确要求）。
- 实测：字号 34 → 918×900；字号 54 → 918×1989。**宽度两次都是 918 = 1080×85%** ✓

### 2. 位置：顶边钉在"球的 home"，只往下长

`updateSiriWindow()` 以前保持球心不动 → 文字一变长球就往上顶（观感"球在往上跑、波也跟着上去"）。
现在：

```java
params.x = round(homeCx - W/2f);                       // 水平以 home 为中心
params.y = round(homeCy - minHalfH - dp(24));          // 顶边 = 常态圆球的球顶
```

`homeCx/homeCy` = 常态最小圆球的球心，`rememberHomeIfMinimal()` 维护：
**只在当前确实是最小形态时**才记（否则会把方形态那套大几何记成 home，位置越滚越偏 ——
实测滚到过 `params.y = -3e7`，球直接飞出屏幕），并且记之前做合法性夹取。

### 3. 波的锚点：从"按球高归一化"改成"按像素下沉"

原来（错的那版）用 ny 锚点 `-1.25`：ny 是相对**球半高**归一化的，方框一长高，
0.25×半高 的像素距离也跟着变大 → 露出来的波顶同比变多 → 用户看到"波往上跑了"。

现在方形态走**像素**：

```glsl
float s = max(uHalf.x * uWaveScale, 1.0);                  // 竖直尺度也用半宽（半宽固定）
wp = vec2(cw.x / s, (-cw.y + uHalf.y + uWaveDrop) / s);    // 波心藏在球底下方 uWaveDrop 像素
```

`uWaveDrop` = `renderer.waveDropPx`（默认 78，设备侧 `float_wave_drop` 可调）：
球底边缘正好切在波的顶部一点点。**球高怎么变，露出来的量都一样** ——
实测字号 34 / 54 两档，球底往上连续彩色行都是 ~157~160px（差 3px，来自桌面内容本身的颜色）。

另外两处"让它真的看得见"的修正（都是实测踩出来的）：

1. 方形态下 `waveVis` **不乘**黑区遮蔽（`mix(1 - blackMask*..., 1.0, uWaveInGlass)`）：
   那截波顶正好落在黑区渐变里，按老公式会被压掉 5 倍，屏幕上几乎看不见。
2. 方形态下波的幅度 **×2.2**（`waveBoost = mix(1.0, 2.2, uWaveInGlass)`）：
   它是**加**在玻璃上的，而方框下半部分常常压着很亮的桌面内容，加一点点光在亮底子上等于看不见。
   圆球态不加成 —— 它画在黑区上，对比度本来就够。

实测（字号 27 / 54 两档，同一套参数）：波顶彩色行 130px / 133px、峰值饱和度 54.0 / 53.1
—— 即"露出来的量"与"亮度"都不随字号/球高变化。

### 4. 圆球态一点没动

`uWaveInGlass=0` 时锚点仍是黑区下沿 `b1`、`waveVis` 仍是老公式、几何仍是那颗圆球。

## 方形态"卡卡的、像 LED 一闪一闪"：闸门抖动 + 每像素白烧（2026-10-16 定位）

用户原话：「方形状态下的 Siri 波好像卡卡的像 LED 灯一闪一闪的」。两个独立原因：

### 1. 真凶：`turn/end` 与下一条消息的 `attempt-start` 交替出现 → 闸门反复淡出淡入

实测日志（几百毫秒内一开一关）：

```
03:58:00.619 turn ended=false  03:58:00.819 turn ended=true    ← 相隔 200ms
03:58:17.371 turn ended=false  03:58:17.452 turn ended=true    ← 相隔 81ms
```

老实现里 `applyTurnEnded(false)` 立刻把幅度闸门压到 0（180ms 淡出），紧接着的 `true` 又淡回来
→ **看起来就是 LED 一闪一闪**。更糟的是 `refreshFinalWave()` 每打一个字都被调用一次
（镜像打字机），里面走的是 `setWaveForm(1)` —— 那函数为了"看不见跳变"会先淡到 0 再淡回来，
于是打字过程中也在不停闪。

改法（两条都必须）：

- **闸门带压后**：`waveHold` 由 `applyTurnEnded` 维护 —— 开立刻生效，关压后
  `WAVE_OFF_SETTLE_MS = 1200ms`，期间再遇到 `turn/end` 就撤销（实测 3 次抖动只关了 1 次，
  另两次被吸收）。这跟球里既有的 `IDLE_SETTLE_MS` 是同一个套路。
- **方形态不走 `setWaveForm()`**：直接 `waveMode = 1` + `waveGateTarget = 1f` 钉住，
  不做淡出淡入（那套只在圆球态的形态切换时才需要）。

### 2. 帮凶：方形态每像素白烧一大截

方框面积是圆球的 ~4 倍（最大 1044×2115 ≈ 2.2M 像素），而合成 shader 原来对**每个**像素都算：
2 次 `waveCol`（核心 + 7.0 的辉光）+ 整套流体圆点（6 个点带 smin/pow/pow(…,1/1.2)）。
方形态既不显示圆点、也不需要辉光，而且离波带远的像素波函数本来就是 0。现在：

```glsl
if(uWaveInGlass > 0.5){
  if(abs(wp.y) < 1.6){                       // 只有贴着球底那条带要画
    col += waveCol(wp, iTime, 1.0) * 2.2 * uWaveAmp;
  }
} else { …原来的圆球态整段，一个字没动… }
```

另外 `updateSiriTextPos()` 原来**每帧**都 `updateViewLayout(siriText, …)`（16ms tick），
方形态的文字块又高又多行 → 每帧一次跨进程事务。现在按位置/尺寸比对，只在真变了才摆
（项目里"别每帧重摆窗口"这条教训原本只写在代理窗口上，这里补上）。

### 实测（`dumpsys SurfaceFlinger --latency <球层>`，各采样 127 帧）

| 状态 | 中位数 | p90 | 平均 |
|---|---|---|---|
| 圆球态（无文字） | 33.1ms | 49.8ms | 49.2ms |
| 方形态（带波） | 33.1ms | 66.1ms | 51.3ms |

中位数都稳在 33.1ms（= 30fps 目标）；方形态只比圆球态重一点点，尾巴上的抖动是这台机器
本身的（桌面采集 30fps + 系统负载），不是这段代码。

## 方形态"卡顿 / 像 LED 一闪一闪"的真正瓶颈：**渲染节拍**，不是 GPU（2026-10-16 定位）

上一轮我以为是"每像素白烧"（砍了圆点+辉光），用户仍反馈卡。这一轮用数据把它钉死了：

### 量出来的事实

| 量 | 圆球态 | 方形态 |
|---|---|---|
| `onDrawFrame` 间隔（自测） | 32ms | **45~56ms** |
| `glFinish()` 后的真实 GPU 时间（自测） | 17ms | **12ms** |
| 方形态但不出波（gate=0） | — | 仍 43~53ms |

第三行是关键：**关掉波、把片元砍到 1/4，节拍照样 50ms** —— 说明瓶颈不在 GPU（它只用 12ms），
而在**谁在按 33ms 敲门**。原来 `requestRender()` 挂在主线程的 `renderTick` 上，
而方形态那个大字块（上千像素高、几十行）每次排版/重绘都在主线程 —— 主线程一忙，
节拍就被拖到 50ms，屏幕上就是"卡顿、一闪一闪"。

### 修法（两条都留着，互补）

1. **独立渲染节拍线程**（关键）：`GLSurfaceView.requestRender()` 本身线程安全，
   于是开一个 `ball-render-driver` 守护线程按目标帧率敲门，主线程只管几何/文字/窗口。
   实测节拍 45~56ms → **32ms**，`dumpsys SurfaceFlinger --latency` 平均 32.4ms ≈ **30.9fps**
   （修之前约 20fps）。
2. **大方框降分辨率渲染**：方框最大 2.2M 像素，全分辨率 GPU 要 ~40ms（会顶到帧上限）。
   用 `SurfaceView.setFixedSize()` 把**绘制缓冲**压到 ~52 万像素（≈圆球态规模），
   SurfaceFlinger 负责放大。折射源本来就是半屏分辨率（540×1206），所以玻璃几乎看不出差别。
3. 顺带：`updateSiriTextPos()` 里的文本 `measure()` 以前**每帧**都做（大段多行文本很贵），
   现在按「文字/字号/可用宽度」缓存；文字窗的 `updateViewLayout` 也只在位置真变了才调。

### ⚠️ 降分辨率的坑：坐标要分两套

合成 pass 里 (cx, cy) 是"窗口内位置"，直接用**缓冲尺寸**算；而球半径/圆角/折射位移/带宽/
边缘宽度/圆点环半径/波的下沉量都是按**屏幕像素**给的，必须乘 `k = 缓冲像素/窗口像素`；
屏幕 UV 那边（`screenUv`、`cScreenTop`）则要乘 `1/k`（新增 uniform `uPxToScreen`）。
我第一版把 (cx, cy) 也乘了 k → **双重缩放**，球直接跑到窗口左上角外（抓帧一看就露馅）。
抓屏抠图那一趟工作在屏幕像素，也要 `/k` 换回去。

## 「会话结束弹出最后文字时球上下跳、像跳出屏幕不见了」（2026-10-16 定位并修复）

用户报的现象：一轮结束、最后那段长文字出现时，球上下跳一下，然后像跑出屏幕不见了。

复现方式（可回归）：喂一段 2940 字的长文本 + `state-TURNEND`，每 0.6s 采样一次
`dumpsys window windows` 里球窗的 `mAttrs` 与 `Frames`，对比"文字期"和"收字后"的球位置。

### 实测数据（修前）

```
文字期：球窗 1044x2097@18,149   球 212..2183
收字后：球窗  787x573@146,386   球 449..896     ← 家在 982..1555，偏上 530px！
```

### 两个根因（都在 `clampBallOnScreen()`，跟文字/波无关）

1. **用了动画值而不是目标值**：窗口刚 resize 回最小尺寸时，`renderer.svHalf*` 还是上一套
   （大方框）的尺寸 —— 拿它去"夹取到屏内"就会把**刚摆好**的窗口又推走。
   日志实拍：`siri window place -> params=(146,919)` 紧接着
   `clampBallOnScreen: 球心 (540,532) → (540,986) 半宽/半高=459/986`。
   改用**目标几何** `tHalfX/tHalfY/tMaster`。
2. **用 `renderer.winY` 反推系统 inset 是过期值**：窗口刚变大/变小时 `renderer.winY` 还没跟上，
   算出来的球心能偏出几百像素（上面那个 `球心 532` 就是这么来的，按窗口位置应该是 1205）。
   改成直接 `glView.getLocationOnScreen()`。

顺带把 `rememberHomeIfMinimal()` 的判据收紧：必须**目标几何就是最小、本轮没出过字、
窗口也是最小窗口**时才认"家" —— 只看动画值会在方框缩回圆球的过程中途经最小尺寸时误记。

### 修后实测

```
文字期：球窗 1044x2097@18,378   球 441..2412   ← 底边正好贴屏幕下沿（在屏内）
收字后：球窗  787x573@146,919   球 982..1429   ← 精确回到"家"
```

文字期往上顶是**必须**的（1971px 高的方框要放进 2412px 的屏幕），收字后回到原处 ✓。

## 方形态的"上下跳动"：窗口是**一格一格跳**的，不是连续长的（2026-10-16 第三轮）

用户补了一句关键信息：「就是你刚刚结束时卡的，上下跳动」——**是收字那一刻**，不是打字过程。
我前两轮的测试文字都不够长（球没到夹取阈值），所以没复现出来。

### 三个叠加的原因

1. **窗口尺寸是"量一次定一次"**：`updateSiriWindow()` 只在 `measureSiriTargets()` 里被调，
   而那是"每换一行量一次" —— 生长是一条 113px 的阶梯；**收起更狠**：从 1044×2097
   一次性掉回 787×573，一帧跳 1524px。
2. **收起和生长用同一个缓动**（τ=0.06s）：生长跟手是好事，收起那么快就是"啪一下跳回去"。
3. **"家"被周期性重记**：平滑跟窗后窗口会滞后动画十几像素，`syncWinPos()` 里那句
   `rememberHomeIfMinimal()` 顺手把这十几像素记成了新家 —— 实测**每轮往下漂 ~27px**。

### 修法

- `siriTick`（16ms）里按**动画值** `aHalfX/aHalfY/aMaster` 平滑跟窗口
  （`maybeUpdateSiriWindowSmooth()`，变化 < 14px 不动窗口，所以静止时零开销）。
  日志实证（收起过程）：`992x1787 → 950x1540 → 928x1406 → 905x1270 → 884x1146 → 867x1047
  → 851x950 → …` 连续缩回。
- 缓动分两档：生长 `siriEaseTau=0.06`（跟手），收起 `0.22`（从容缩回）。
- **删掉周期性记家**：只保留"服务启动时的窗口位置"和"用户拖动球（且当时是最小形态）"两处。
  实测连跑两轮，球都精确停在 `146,919`（不漂移）。

### 复现脚本（可回归）

```bash
# 把一段长文本按 105 字递增地喂进球（模拟打字机），喂完停手等它自己收
sh /sdcard/stream3.sh
dumpsys window windows | grep -A14 glslplayground | grep mAttrs   # 收字后应回到 146,919
logcat -d | grep "ball window ->"                                  # 收字过程应是连续的一串尺寸
```

## metaball 搜索层：双指长按 → 小黑球 → 搜索胶囊（2026-10-17 新增，**移植**不是仿制）

### 用户要的是什么（两次强调，第一版做错了一次）

> 「把 Metaball→Search 里的东西**原封不动地搬出来**」
> 「它长按就**隐藏球**，然后出现小黑球，然后要和 Metaball→Search 页面里**从顶部拿到的黑球
> 一模一样**，并且要**复用那个页面的动画和效果，而不是球的变体**」

**第一版做错了**：我让球自己形变过去（复用球那段 shader，加 `uSearchK` 材质分支、
把球的 SDF 从圆弹成胶囊）。用户看完直接说"理解错了"。区别在于：那仍然是**球的变体**，
而用户要的是**演示页那一层本身**。

### 现在是怎么做的（"搬"的三条）

| 层 | 从哪搬 | 落在哪 |
|---|---|---|
| 形状/材质 | `metaball-search.html` 的 `const FRAG = \`…\`` | `SearchLayerShader.java`（**整段抄**，只改 4 处必须改的） |
| 动画/手感 | 演示页的 `class Spring` + `P` 参数 + 状态机（IDLE/DRAG/CAPSULE/RETRACT） | `FloatingBallService` 的 `MorphSpring` + `SQ_*` 常量 + `sqPhase` |
| 界面 | 演示页 `#search` 那段 DOM/CSS | `BallSearchBar.java`（🔍 输入框，含自绘发光光标） |

着色器只改了四处（都在 `SearchLayerShader` 的头注释里标了 ★）：
① 去掉 `#version 300 es`、`outColor`→`gl_FragColor`（本机是 GLES2）；
② `bgcol()` 从"内嵌壁纸 + textureLod 取 mip"换成**本机抓屏的两张纹理**（清晰 iBg / 模糊 iBlur，
   语义与演示页的 lod 一致）；
③ `fwidth()`（GLES2 要扩展）→ 固定 1.5 物理像素 AA；
④ 输出：演示页是整屏 demo、形状外画壁纸；我们是**悬浮窗**，形状外必须透明 →
   只保留演示页那层软阴影（预乘 alpha），另加一行远处像素早退；
⑤ **删掉顶部黑边**（用户要求：「要删掉出现搜索框时那个碰到屏幕最顶端的黑区」）——
   `bar` 半平面与 `uEdge` 整个拿掉，形状只跟 blob 做 SDF，材质权重 `wb` 恒为 1
   （= 演示页里"已落地、离边缘很远"那一档）。`sdfUnion()` 因此没人用了，一并删掉。
**其余公式（sdf_union 梯度感知 smin / 圆弧剖面折射 / 8 抽样圆盘 / 色差 / edge_bleed /
2.2px 双光高光 / crush 压暗）一个字没动** —— 这份代码的价值就是"和演示页逐行对得上"。

### 渲染怎么接进来的（球整趟让路）

搜索层跑在**球那个 GLSurfaceView** 里（同一条抓屏/抠图/模糊管线），但走的是**另一个 program**：
`GlassRenderer.searchMode = true` 时 `onDrawFrame` 直接 `drawSearchLayer(bgTex); return;` ——
球那趟合成一点不算。窗口在进场时铺满整屏（`params = 0,-winY0(),screenW,screenH`，
缓冲设回全分辨率），所以演示页的"顶部黑边 + 胶囊"能画到屏幕边缘。
`uDpr` 仍然传 density（演示页的 CSS px → 画布 px），所以胶囊尺寸和演示页一样是 50dp 高。

### 踩过的四个坑（都实测过）

1. **`winY0()` 在"窗口贴屏幕最上沿"时算成 0**：老实现要求 `renderer.winY > 0` 才算数，
   而整屏窗口的 winY 正好是 0 → 输入行整体下移一个状态栏（实测行在 347、胶囊在 253）。
   现在改为**先问 `glView.getLocationOnScreen()`**（唯一在 y=0 时也对得上的来源）。
2. **放大镜贴到了胶囊左边缘**：我一开始为了给图标加热区把左边距写小了，
   用户一眼看出来"搜索按钮偏左一点点"。现在左边距 = 14dp，输入框右侧也留 14dp。
3. **全屏"点空白关掉"层会被唤起手势的尾巴点到**：手指还按着的时候这一层就挂上了，代理窗口
   一让路，同一串手势剩下的 `ACTION_UP` 就落到它头上 → "搜索框刚出现就自己关掉"。
   现在只认**从本层自己 DOWN 开始**的那一串 + 800ms 静默期。
4. **悬浮窗收不到返回键**：`OnKeyListener` 只在"这个 View 自己拿焦点"时回调（焦点其实在
   EditText 上），而**悬浮窗没有 Activity**，没人处理的键走不到 `Activity.onKeyDown`。
   现在三个钩子都挂：bar、EditText、`addOnUnhandledKeyEventListener`（API 28+）。

### 顺带修掉的一个真 bug：DSH launch token 读的是**最旧**那个

`dsh.log` 是**追加**写的（看门狗 `>>`），DSH 每重启一次就多一行启动横幅，而**只有最新那个
token 有效**。老 `grepToken` 返回第一个 → DSH 一重启球就再也认证不上，日志只有一句
`DSH 未下发会话 cookie (HTTP 303)`（旧 token 的 303 不带 set-cookie），看起来像 DSH 的毛病。
现在：取最后一个 token + 读文件**尾部** 256KB。实测改完立刻 `authenticated against …`。

### 怎么调 / 怎么验

```bash
D=/sdcard/Android/data/com.glslplayground.app/files
touch $D/search-open                      # 打开（不必真做双指长按）
printf '你好' > $D/search-type            # 只填字（再用键盘回车发）
printf '你好' > $D/search-ask             # 一条龙：打开 → 填字 → 发送
touch $D/search-close
logcat -d | grep -E "search: "            # 全程日志
printf '' > $D/ballshot && su -c "cp /data/user/0/com.glslplayground.app/files/ball-capture.png /sdcard/cap.png"
```

设置页「DSH 文本助手」里也有两个按钮：**打开搜索框（调版式）** / **关掉搜索框**。

### 已验证 / 未验证

- ✅ **双指长按真机跑通**（日志 `search: 进入（…）小黑球 @(540,1302) 球已隐藏`，用户实测多次）。
- ✅ **球确实让路**：`renderer.searchMode` 那一帧画的是演示页那层，抓帧里没有球。
- ✅ **小黑球 → 胶囊**的弹簧与材质（抓帧对过：胶囊 + 软阴影 + 折射背景）。
- ✅ **顶部黑区已删**：抓帧量顶部 0~40 行 alpha=0（黑边在时那一段是满的）。
- ✅ **发光光标**：连抓 6 帧，x≈163 那一列亮/灭交替（1.12s 里 60% 亮）。
- ✅ **打字 + 键盘回车发送**：`search: 发送 N 字` → `ws stream done: N text-deltas` → 球里出字。
- ✅ 点空白 / 发送 / 返回键 都能收，收完球**精确回到原来的家**（`siri window place: home=(…)`）。
- ⚠️ **没验到的**：浅色桌面下胶囊底部的折射观感
  （设置页/桌面偏暗，看不透）；胶囊里那行字在**极亮背景**下的可读性。
- ⚠️ 双指长按的 520ms 阈值、SQ_DROP_MS=260（小黑球单独待多久）只有用户能定。

## 状态 3 / 菜单 / 卡片的玻璃：**就是球那套材质**（2026-10-23 重做）

> 用户原话：「给你看球长按的样子和切换回话的样子，这个样式太丑了并且文字也超出界面了，
> 我原本要求是要弄成跟球一样的液态玻璃的实时折射的」。

### 之前为什么丑（两个独立病灶）

1. **材质不对**：`ZoneLayerShader` 只抄了球的 uniform 名字，折射那一段其实还是
   metaball-search 胶囊的圆柱公式（`uHeight=18px`）。18 像素的折射带铺在 200~250px 的
   圆/面板上 = 只有最外一圈被掰了一下，里面是**一块平的半透明塑料**。
   再加 `cap`/`crush` 把整块压成近黑 → 观感就是「一个黑色圆贴纸」。
2. **文字真的画到玻璃外面**：`winY0()`（请求 y → 屏幕 y 的 inset）是**每次现算**
   `getLocationOnScreen()[1] - params.y`。而调用点几乎都在 `updateViewLayout()` 之后立刻调用，
   那一刻 location 还是旧的 → 差值离谱 → 被 `< screenH/4` 挡掉 → 返回 0 →
   文字层少抬一个状态栏。实测同一时刻：**玻璃面板顶 = 856，文字窗口 = 947（差 90px）**，
   于是标题上面空一大块、最后两行被面板下沿切掉。
   另外长按卡片的行数没上限（`holdRects` 只有 5 位），面板高度按 `holdItems.size()` 算，
   >5 个目录时整块面板比球窗还高 → 直接被窗口裁掉。

### 现在的做法

| 位置 | 改动 |
|---|---|
| `ZoneLayerShader.java` | 折射换成**球那条曲线**：`amp = circleMap(1 + sd/band) * refract`（边缘最强、往里按 band 衰减），色散按 `lp.x*lp.y/(hx*hy)` 加权；黑顶 `cap = pow(1-top, uCapPow)`（**连续衰减**，二修掉了"锋利的黑色长方形"）+ 贴边 26dp 内变薄；球顶两团柔光；底缘亮弧 + 赤道线 + 壳边；`uFrost` 把背景换成磨砂（面板 0.82 / 圆区 0.55） |
| `GlassRenderer.java` | 新增 `zBandRatio / zDome / zAb / zTopDarken / zCap0 / zCap1 / zEdgeBleed / zScrim / zGlow / zFrost / zoneBlur`；`buildBlur()` 里 `zoneMode ? zoneBlur : blur` —— **面板的磨砂力度独立于球的 `float_params.blur`**，退出即恢复 |
| `FloatingBallService.zGlassMaterial()` | 统一按**形状短半轴**换算 `band / refract`，圆区、长按卡片、菜单三处只改黑顶与磨砂 |
| `winY0()` | 改成**缓存**（只有合理测量才写缓存，否则用上次真值，最后兜底问 `status_bar_height`）；`zonesParams.y` 也补上 `- winY0()`（之前整层图标比玻璃低一个状态栏） |
| `showCards()` / `computeCardRects()` | 行数封顶 5（多了显示「还有 N 个…」），行高按窗口高度自适应 —— 面板永远落在 GL 窗口里 |
| `BallZonesView` / `BallGlassMenu` | 白字统一加投影：玻璃变清透之后不再靠"把玻璃压黑"来垫字 |

### 怎么验（真机，全部脚本可跑）

```bash
bash tools/deploy-and-shot.sh menu-after menu-dirs     # 装 + 重启 + 开菜单 + 存一帧玻璃
bash tools/deploy-and-shot.sh zones-after zone-open    # 四个圆区
bash tools/deploy-and-shot.sh card-after zone-open zone-move:tl   # 长按卡片
bash tools/deploy-and-shot.sh convo-after convo-latest # 切回对话卡
# 对位（文字 vs 玻璃）：两者窗口 frame 必须重合
su -c 'dumpsys window windows' | grep -A2 glslplayground | grep 'frame=\['
```

实测对位（2026-10-23，菜单）：文字窗口 `205,857 670x627`；玻璃窗 `166,818 748x705`，
面板 inset 14dp=38.5 → 面板 `204.5,856.5 669x627` —— **重合**（修之前差 90px）。
状态 3：`zones` 覆盖层与 GL 窗同在 `144,180 793x579`。


### 二修：那条「锋利的黑色长方形」（2026-10-23）

第一次改完，用户说「依然有一层锋利的黑色长方形」。用 `files/show-glass`（新调试口子：
**不给玻璃打 SKIP_SCREENSHOT**，root 截屏就能拍到用户真正看到的合成画面）一拍就看清了：
黑块不是窗口底色、不是阴影、也不是文字层，而是**黑顶那条曲线**：

```glsl
// 旧的：cap0=-1 → cap1=0.78 的 smoothstep
cap = 1.0 - smoothstep(-1.0, 0.78, ny);   // 顶部 15% 就烧掉 90% 的暗度
```

smoothstep 两头平、中间陡，铺在整块面板上 = 顶上一条**边是直的**（连圆角都被盖住）、
下沿有明显台阶的黑色带。现在的写法：

```glsl
float topN = clamp((ny + 1.0) * 0.5, 0.0, 1.0);
float cap  = pow(1.0 - topN, uCapPow);          // 从上往下连续衰减，没有台阶
cap *= smoothstep(0.0, 26.0 * uDpr, -d);        // 贴边变薄 → 圆角跟着形状收
glass = mix(refr, vec3(0.010,0.012,0.020), cap * uTopDarken);
```

参数：面板 `uTopDarken=0.88 / uCapPow=1.55 / 磨砂 0.82 / 烟熏 0.30`，
圆区 `0.80 / 1.35 / 0.55 / 0.20`。

合成截图（`files/show-glass` 打开时拍的，就是用户眼睛看到的）：
`docs/glass-menu-composite.png`、`docs/glass-zones-composite.png`。
拍完**必须** `touch files/hide-glass` 或重启，否则抓屏里含着玻璃自己 → 折射自采样。


### 三修：对话卡上的同一个黑方块（`GlassRenderer.FS_COMP` 的 `uPanelMode`）

对话卡（Siri 方形态 / 审批提问面板）走的是另一条链路，黑区在 `FS_COMP` 里：

```glsl
// 旧的：rise<0.45 时 pow(rise,2.6)≈0.1 → 混出来还是 0.91 黑 → 整个上半段都是 1.0
tBlackK = mix(1.0, 0.12, pow(rise, 2.6));
```

结果就是**黑块铺满上半段、下沿一条直线、四个直角还盖住了圆角**（用户说的"锋利的黑色长方形"）。
现在：

```glsl
float top01 = clamp((1.0 + ny) * 0.5, 0.0, 1.0);        // 0=顶 1=底
tBlackK = mix(0.92, 0.10, pow(top01, 1.6));             // 连续掉，没有平台
tBlackK *= smoothstep(0.0, 18.0 * uScale, -sd);         // 贴边变薄 → 圆角跟着形状收
```

对比图：`docs/glass-convo-before.png` / `docs/glass-convo-after.png`（都是 `files/show-glass`
打开时拍的合成画面，就是用户眼睛看到的那一帧）。


### 四修：真正把"直边"从面板上拿掉（2026-10-23 最终版）

三修之后用户拍图仍然说「依然存在」。复盘：只要**暗度沿 y 变化**，人眼就会把它读成
"一个直角方块"——曲线再平滑也一样（台阶在感知上，不在数学上）。所以面板/卡片干脆
**不做竖向黑顶**：

| 参数 | 之前 | 现在（菜单 / 长按卡片） |
|---|---|---|
| `zTopDarken` | 0.88（顶黑底透） | **0.0**（完全没有 y 方向的暗度项） |
| `zScrim` | 0.30 | 0.46（整块均匀烟熏，白字有底） |
| `zFrost` | 0.82 | 0.85 |
| `zoneBlur` | 1.25 | **9.0**（1.25 在 `buildBlur()` 里只是 1px 级别，等于没糊 → 桌面文字直接透进面板） |

另一处"黑矩形"来自系统：SurfaceFlinger 里 SurfaceView 自带一层
`Background for SurfaceView`，`isOpaque=1 color=(0,0,0,1)` —— **窗口大小的纯黑矩形**，
只要 GL 这一帧没铺满（面板四周 14dp 留白、或 surface 刚 resize 的几帧）它就露出来。
`setupOverlay()` 里给 glView 设了 `setBackgroundColor(TRANSPARENT)`，那层不再是不透明黑。

四区（圆）仍然保留 `uTopDarken=0.80` 的黑顶 —— 圆形的暗顶是球的观感，不会读成方块。

合成截图：`docs/glass-switcher-final.png`（带文字）与 `docs/glass-switcher-glass.png`（纯玻璃）。


### 五修：外围那一圈"薄薄的黑"（2026-10-23）

用户第三次反馈：「外围的一层压暗的东西不能删吗……棱角分明的薄薄一层黑」。逐项查，
面板边缘其实叠了 **4 层**压暗/描边，全部来自当年从球上搬过来的"厚度感"：

| # | 来源 | 原值 | 现在（面板） |
|---|---|---|---|
| 1 | `innerEdge`：贴边 18dp 内 `refr *= 0.72` | 0.72 | `uEdgeShade=0` → 1.0（完全不压） |
| 2 | `shell`：贴边 2.2dp 的深灰线 `+= vec3(0.10,0.11,0.13)*0.6` | 有 | ×`uEdgeShade` = 0（没有） |
| 3 | 外阴影 `exp(-d/26dp)*0.20` | 0.20 | `uShadow=0`（连阴影都不要） |
| 4 | 折射在**最外圈**采的是玻璃外面的画面（深桌面=黑圈/白桌面=白圈） | 有 | 最外 14dp 位移收 0：`amp *= smoothstep(0, 14dp, -d)` |

另外赤道线 `eq` 现在只在有黑顶时才画（`uTopDarken` 为 0 = 没有那条横线），
贴边高光也从 `sHlAmt=1.5` 收到 `0.9`（太亮同样会被看成"一圈边"）。

### 六修：文字层又一次错位（90/186px）

五修时发现菜单文字跑到了玻璃外面（实测文字窗口 205,670 vs 面板 205,856）。
根因还是"请求坐标 - inset"这一套：inset 会随父容器/是否被 clamp/球是否被挪过而变。
现在 `showMenu()` 里在玻璃窗口布局完之后 `glView.post(...)`：
拿玻璃**自己的屏幕位置**反推文字窗口该在哪（`parentTop = loc[1] - params.y`，同一窗口同一时刻，精确），
一次对死。实测：文字 205,857 / 面板 204.5,856.5 —— 重合。


### 七修：卡片列表可滚动（面板尺寸不变）

用户：「工作目录只能显示四个……相当于弄成文字显示区不变但是可滚动不就行了吗」。

两个原因：
1. `openHoldDirs()` 里写死 `Math.min(4, dirs.size())`，`openHoldSessions()` 写死 `Math.min(3, ...)`
   —— 列表本身就只喂了 4 条/3 条给卡片，跟面板高度无关。现在给到 10 条。
2. 卡片画在球窗里（~210dp 高），5 行 × 34dp + 标题就溢出 → 被窗口裁掉。

现在的做法（**面板尺寸一行没改**，只让内容滚）：

| 变量 | 作用 |
|---|---|
| `holdItems` | **全部**条目（最多 10 条） |
| `holdVisible` | 当前窗口能放几行（由窗口高算出，本机 = 4 行） |
| `holdFirst` | 列表滚动到第几条（滚动窗口的起点） |

- `labels()/subs()/localRects()` 只取 `[holdFirst, holdFirst+holdVisible)` 这一段；
- `nearestRow()/cardAt()` 返回**绝对下标**（`holdFirst + 行号`）；
- 手指停在面板上/下**边缘**（下 22% / 上 15%）→ `holdEdgeScrollTick` 每 380ms 滚一格，
  到底/到顶自动停；滚动期间不抢焦点，避免"滚一格焦点跳一行"；
- 标题右上角画 `↑`/`↓`/`↑↓` 提示还有内容（`BallZonesView.setCardScroll`）；
- 从会话退回来（左滑）时 `holdDirsFirst` 记住工作目录那一页，**回到原来滚到的位置**。

调试口子 `zone-move` 现在也吃 `x,y`（直接喂手指坐标），脚本里能验证滚动：
```bash
touch $D/zone-open; printf 'tl' > $D/zone-move        # 出卡片
printf '540,1050' > $D/zone-move && sleep 2.5         # 停在下边缘 → 自动下滚
```
截图：`docs/glass-card-scroll-top.png`（↓ 还有）、`docs/glass-card-scroll-bottom.png`（↑ 已滚）。


### 八修：把"逼真的液态玻璃"做回来（2026-10-23）

前几轮为了去掉"黑边/黑方块"，把玻璃的光学项几乎删光了（贴边压暗=0、壳线=0、阴影=0、
贴边位移=0），结果面板变成"一块磨砂塑料"——用户：「现在……都没有液态玻璃的效果」。

这次按**真实玻璃片**的物理重写 `ZoneLayerShader` 的材质段：

| 项 | 做法 |
|---|---|
| 折射剖面 | 弯月面 `amp = refract · sin²(π·u^0.7)`，u=深度/band。**两端导数为 0**：贴边处位移为 0（不会出现"贴边一圈"），最厚处（u≈0.4）位移最大，里面回到 0。用 `sin` 会在边缘留一道可见的棱线，`sin²` 没有 |
| 整块透镜 | 面板 `bandRatio=1.25`（整块都在折射带里，像球）—— 之前 0.34 只弯一圈边，中间是"一块方的平区"，看着像个画框 |
| 色散 | 跟坡度走 `abAmt = uAb·|sin·cos|`，只在最厚那一圈出现彩边；面板 `uAb=0.10`（多了像故障艺术） |
| 磨砂 | `uFrost=0.55` + `zoneBlur=9`：糊得住桌面文字，又不会把折射糊没（0.92 时折射完全看不见） |
| 去饱和 | `uDesat=0.45`：磨砂玻璃会把背景颜色洗淡，不去饱和会被绿壁纸染成"脏绿色塑料" |
| 四道光 | ①贴边 1.6dp 细亮线（朝光侧更亮）②贴边 9dp 柔光晕 ③**只压朝上的那条边**的内阴影（厚度感，不是整块渐变）④下缘接光弧 |
| 烟熏 | 均匀 `uScrim=0.34`（**不做任何竖向渐变** —— 那正是之前"黑方块"的来源） |

圆区同步：`bandRatio=1.0 / refract=0.62R / frost=0.45 / desat=0.15`，仍保留球那套黑顶
（圆形不会被读成方块）。

截图：`docs/glass-real-menu.png`（切换会话面板）、`docs/glass-real-zones.png`（四区）。


### 九修：区域层**直接复用球的着色器**（2026-10-23，最终形态）

用户看完成片后一句话定调：

> 「太丑了，不逼真，你直接复用聊天界面那个方形不就行了吗……还有菜单的球你相当于把初始状态的胶囊
> 弄成圆形同时弄四个不就成菜单的样子了吗」

于是 `ZoneLayerShader`（我自己搓的第二套材质）**整条链路停用**，改用
`GlassRenderer.drawZoneLayerBall()`：拿球的合成着色器 `FS_COMP` 把每个形状画一遍。

- 形状来源不变（`zShape/zMeta`，5 个槽位）：`m[i*4+1] >= 0.5` → 面板模式（`uPanelMode=1`，
  就是聊天卡那张方形）；否则 → 球模式（`uPanelMode=0`，初始那颗胶囊的材质）。
- 每个形状单独 `glDrawArrays`，开预乘 alpha 混合 `GL_ONE / GL_ONE_MINUS_SRC_ALPHA` 依次叠。
- 折射/黑区/赤道线/壳光/边缘色散全部是球那一份代码 —— 不再有两份材质对不齐的问题。
- **用户拍到的那条"对角线"**是旧 `ZoneLayerShader` 里 SDF 梯度在四角收敛出的棱（弯月面 +
  圆角矩形梯度），换成 `FS_COMP` 后这条线在数学上就不存在了。

截图：`docs/glass-ballshader-menu.png`（切换会话）、`docs/glass-ballshader-zones.png`（四区）。


### 十修：四区改成"水珠"（2026-10-23）

用户：「会话切换器没问题了，但是菜单那四个球太黑了，微调一下，弄逼真一点要有水珠类似的折射效果」。

四区仍走 `FS_COMP`（球那一支），只是给它开了一个 `uDropMode`（球/面板都是 0，不受影响）：

| 参数 | 面板（聊天卡那套） | 四区（水珠） |
|---|---|---|
| `uTopDarken` | 0.93 | **0.55** |
| `uBlack0 / uBlack1` | -1.0 / -0.01 | **-0.98 / 0.75**（黑区只压上半，淡很多） |
| `uRefraction` | `zRefract` | **0.85 × 半径**（水珠比球更"弯"） |
| `uBand` | `短半轴 × 0.55` | **1.60 × 半径**（整颗都在折射带里） |
| `uEdgeBleed` | 0.11 | 0.22 |

`uDropMode=1` 时额外加三样（只加光/只按球面明暗，不加整块暗度）：

1. **两个镜面高光点**（左上，`exp(-d²·26)·0.36 + exp(-d²·80)·0.26`）—— 光源在水珠上的像；
2. **贴边聚光**：`smoothstep(4.5px, 0, |sd+2px|)·0.26` —— 球面把光收在边缘；
3. **背光侧压暗**：`mix(1.0, 0.60, side)`，`side` 由 `dot(归一化坐标, 光方向)` 算 ——
   光从左上，右下自然暗（球面的明暗交界，不会变成一条直边）。

截图：`docs/glass-droplets.png`。


### 十一修：四区改成**真球面光学的水珠**（2026-10-23）

用户：「水珠还是好假好丑，一点都不透明，苹果的是接近真实的根据背景实时变化的反光和折射的，
而不是写死的一层蒙版」。

上一版的水珠是"画"上去的（镜面高光斑 + 背光侧压暗 + 黑顶），所以假。现在 `uDropMode=1`
完全走光学模型，**没有任何手画的亮斑/蒙版**：

```glsl
vec2  q  = c / uHalf;                       // 形状内的归一化坐标
float z  = sqrt(1.0 - dot(q,q));            // 半球：球面高度
vec2  disp = q * (1.0 - z) * uRefraction / uScreen;      // 折射：边缘弯得多、中心几乎不动
vec3  refr = 三通道各采一次（色散 = uAberration）
vec2  rxy  = 2.0 * z * q;                                // 反射方向 R = 2(N·V)N − V
vec3  refl = texture2D(iBlur, uv0 + rxy * 短半轴 * 0.18); // 用**实时抓屏**当环境
float F    = 0.03 + 0.97 * pow(1.0 - z, 5.0);            // 菲涅尔
col = mix(refr, refl, F);                                // 正面透明、边缘反射
```

所以：**玻璃里看到的就是桌面本身（实时）**，边缘那一圈因为菲涅尔而出现强的环境反光 ——
不是固定的一层高光。参数：`uRefraction = 0.26 × 半径`、`uAberration = 2`、
`uTopDarken = 0.20`（只在最顶上垫一点点，否则白色图标在亮天空上会消失）、`uBlack1 = 0.25`。

同一轮里还做了两件配套的事：

1. **赤道线 / 底缘壳光 / 贴边压暗**在 `uDropMode` 下全部乘 `(1 - uDropMode)` —— 那些是球的笔触，
   对水珠只会变成"白雾 + 横线"。
2. 白图标/白字在全透明玻璃上会消失：`BallZonesView.drawIcon()` 改成**先描一层 24% 黑底、再画白线**
   （很轻的暗边，玻璃本身一个像素没动）。

> ⚠️ 调试口子 `files/show-glass`（让玻璃留在系统抓屏里）在这种"玻璃采样桌面"的材质下会**自采样**：
> 抓屏里含玻璃自己 → 出现黑色反馈斑。判断水珠真实观感必须用 `files/ballshot`（只截 GL 窗口），
> 或者关掉 show-glass 再看。

截图：`docs/glass-droplets.png`。


### 十二修：四区做成"缩小镜"（2026-10-23）

用户：「相当于四个缩小镜，缩小的再夸张一点点」。

水珠的采样映射换成**缩小镜公式**（`uDropZoom = zDropZoom`）：

```glsl
vec2  q  = c / uHalf;                                  // 归一化坐标
float r2 = clamp(dot(q, q), 0.0, 1.0);
float kz = max(uDropZoom, 1.0);                        // 1.0 = 不缩，1.7 = 当前
vec2  qs = q * (kz - (kz - 1.0) * r2);                 // 采样半径 = r·(k − (k−1)r²)
vec2  duv = (qs - q) * uHalf * uPxToScreen / uScreen;  // 往外采样 → 圆里装的画面更大、东西更小
```

- `r=0 → 0`、`r=1 → 1`：圆周上和外面的画面对齐，不留接缝；
- `k>1.5` 时靠近边缘的采样半径会回落 = 边缘的像轻微**翻过来**，就是水珠边缘那种倒像；
- 一轮试过 `smoothstep` 版（k=2.4）：那是把整个场景怼成放射条纹，已弃用。

⚠️ 代价：如果背后的画面有明显纹理（比如岩石），缩小镜会在边缘拉出放射状纹路 —— 这是径向
缩放的固有形态，不是实现 bug。想收就把 `zDropZoom` 调回 1.3~1.5。

截图：`docs/glass-droplets-zoom.png`。


### 十三修：四区只留"发光图标"（2026-10-23）

用户：「四个缩小镜里不要出现文字，替换大一点细一点符合真实功能的白色图标，并且图标像发光一样要亮的刺眼那种」。

- **去掉文字**：`BallZonesView.onDraw` 的四区分支不再 `drawText`，图标改到圆心（`cy[i]` 而不是 `cy - 0.22r`）。
  卡片/菜单的文字在 `drawCards` / `BallGlassMenu` 里，不受影响。
- **图标更大更细**：边长 `0.52r → 0.80r`，线宽改成按"最终像素"算（`core*k24`，`k24 = 24/s`，
  否则 canvas 的缩放会把线宽一起放大），`core = 1.15px`。
- **发光**：用 **15 层连续衰减的白色描边**模拟辉光：
  ```java
  for (float w = 1.0f; w <= 24f; w += 1.55f) {
      int a = (int) (165 * pow(1 - (w-1)/23, 2.3));   // 内亮外淡
      stroke.setColor((a << 24) | 0xFFFFFF);
      stroke.setStrokeWidth((core + w) * k24);
      cv.drawPath(path, stroke);
  }
  ```
  不用 `BlurMaskFilter`：它在硬件加速画布上对 `Path` 经常被直接忽略。层数少于 ~10 会看到"年轮"。
- 文件夹那条内横线删掉（加上它像表格，不像系统文件夹图标）。

⚠️ 这份截图是 `show-glass` 打开时拍的（所以有水珠自采样的放射纹）；正常运行时玻璃不留在抓屏里，
`files/ballshot` 拍到的画面里没有那些纹路。


## 2026-10-28：球让路改成 socket（附：真正被加载的是预设副本）

DSH 的 `mobile` 工具用的是 `~/.dsh/.agent-presets/mobile-use/mobile_plugin.js`；
`~/dsh-preset-mobile-use/lib/index.js` 改了不生效。补丁：`python3 patches/patch-mobile-preset.py`，
通道是 `127.0.0.1:38721` 的 `ACT`/`DONE` 回环 socket（文件通道在 Android 11+ 被分区存储挡死）。


## 2026-10-28（续）：两个真正的原因

1. **触摸被系统遮挡策略整串丢弃**：ColorOS 的 InputDispatcher 有 tap-jacking 保护 ——
   触摸要"穿过"不受信任的悬浮窗（哪怕该窗口的可触摸区域是空的）会被丢弃。
   所以只把代理窗口缩成 1×1 不够，**玻璃窗口本身也得让开**：
   `applyGlassPark(agentBlind)` 把 glView 的 params 挪到屏幕外 3 倍处，`syncWinPos()` 期间跳过重算，DONE 后原样还原。
2. **ACK 回得太早**：窗口位置对 InputDispatcher 生效要一帧多，注入方收到 ACK 立刻注入会丢。
   现在 socket 与文件两路 ACK 都延后 `GATE_SETTLE_MS = 45ms` 才回。

### 验收（球压在「⚙ 设置」卡片上，点 540,1866）

| 条件 | 结果 |
|---|---|
| 门控开 + `input tap`（走 /system/bin/input 包装器） | DshSettingsActivity 打开，球零位移 |
| 门控开 + `mobile click`（插件 socket） | DshSettingsActivity 打开 |
| 门控关（`touch /data/local/tmp/ball-gate-off`）+ `input tap` | 停在首页（球把点击吃掉） |


## 2026-10-28（三）：默认零闪烁

实测：**这台 ROM 并不会拦"穿过悬浮窗"的触摸** —— 之前点击丢失的真正原因是 ACK 回得太早（窗口还没挪完），
加 `GATE_SETTLE_MS=45` 之后就通了。所以玻璃窗口根本不用挪。

- 默认：注入时只把代理窗口收成 1×1，玻璃原样（不闪）。
- 兼容开关：放一个 `files/glasspark` 文件 → 回到"整窗挪出屏幕"的老办法（个别 ROM 需要，代价是闪）。
- 验收：`proxy` 1×1（`[-1,130][0,131]`）期间玻璃帧不动 `[144,1499][937,2078]`，点击照样打开 DshSettingsActivity。

## 2026-10-28（四）：滑动改走 /system/bin/input

实测（微信消息列表，球代理覆盖下拉起点 540,350）：
- `input swipe …`（包装器路径）→ 稳定拉出小程序面板；之后上拉回消息页正常
- vd 的 `/api/action swipe` → 微信毫无反应（手势太"轻"，面板/长按类阈值识别不到）

所以插件里 `action == "swipe"` 不再走 vd 注入：直接 `su -c "input swipe x1 y1 x2 y2 dur"`
（`/system/bin/input` 已是门控包装器），随后用 vd 的 `observe` 取无障碍树。点击仍走 vd。


## 2026-11-06（五）：回球位置 / 输入框换行 / 审批面板压测

用户报的三件事，都在这一轮修掉并实机拍过：

### 1. 「聊天结束缩回胶囊时 dots 和 Siri 波不在启动那个位置」

根因是**「家」（home）被记错**，不是波形本身：
- `centerBallOnScreen()` 在启动那一帧就记 home，可那会儿窗口还是 `993×993`、
  `winY0()` 还没量到（=0）→ 记下来 `(541,587)`，而真实球心是 `(541,470)`。
- 更坑的是 `winY0()` 自己：它用 `loc[1] - params.y` 量 inset，但 `updateViewLayout`
  之后 `getLocationOnScreen()` 还是**旧位置**，算出来 207 也被当成"状态栏高度"缓存住了。

修法（`FloatingBallService`）：
- `winY0()`：只接受「量出来的值和状态栏高度差 ≤ 48dp」的测量，否则用
  `status_bar_height` 兜底；`winInsetY` 不会再被几百像素的假值污染。
- `centerBallOnScreen()`：只有「窗口已经落到常态最小球窗 + inset 有效」才写 home，
  否则留空，交给 `auditWindowAgainstBall()` 在窗口落定后补记。
- `rememberHomeIfMinimal()`：home 统一用**屏幕坐标**（补上 `winY0()`）。
- `releaseTextHold()`：收字回球时**当场**把圆点偏移 `orbOffset` 放回默认值、
  把波闸门 `waveGate` 放开 —— 方形态里圆点被沉到"底部动效带"的那一档不能带回来
  （这就是「dots 没像启动时那样居中」的直接原因）。

实机：启动 `home=(541,470)`、`球顶=246`；开一次对话卡再缩回来，窗口仍是
`(144,90)`、`球顶=246` —— 和启动完全一致。**最终回复时那道波照旧沉在球底（不居中）**，
这一条没动。

### 2. 输入框按行数长高（语音识别字多时换行）

- `BallSearchBar`：`EditText` 从单行改成多行（最多 4 行、回车仍=发送），
  文字换行后回调用 `Listener#onInputHeight(px)` 通知服务侧。
- `FloatingBallService`：`capsuleRect()` 的高度不再是常量 `SQ_CAP_H`，改成
  `searchCapsH`；`onSearchInputHeight()` 把它换算成 dp 并重设胶囊弹簧 `sqBy`。
  多行时输入行左右留白 14dp → 22dp，字不会贴着胶囊的大圆角被啃掉。
- 对话卡里那条输入行（`onConvoInputHeight`）同样按行数拉高（最多约 2 行，底边不动）。

实机：47 个汉字 → 胶囊从 50dp 长到 120dp，三行完整显示（`glass-capsule-3lines.png`）。

### 3. 审批 / 提问面板压测

- 新增**文件版**调试回路：`files/approval.json` = 一条假请求（JSON 格式同 HTTP 那套）。
  为什么加：HTTP 回路固定在 `DSH 端口+1`（3081），这个端口在本机被 DSH 自己的代理占着，
  假审批根本喂不进去。
- `show-glass` 调试口子现在**连审批面板一起留在抓屏里**（原来只有玻璃，截图里没字，
  没法验排版）。
- 布局改成**从玻璃内底边往上摆**：输入行贴内底、选项层贴它上面、文字块最上面；
  选项超出时在自己的 ScrollView 里滚，并给上下边缘加淡出（不再硬切一半）。
- 面板竖向位置改成「能放进屏幕就放进屏幕」：请求 y ∈ [状态栏下 8dp, 屏幕底−40dp]；
  高度上限仍是屏高 66%，所以 8 个选项也不会顶出屏幕。
- 圆角没变：面板还是那条 `cr = 0.28 × min(halfW, halfH)` 的 squircle。

实机截图：`glass-approval-normal.png`（bash 审批，两个按钮）、
`glass-approval-8options.png`（8 选项 + 输入框，选项区滚动）。
⚠️ 这两张是开着 `show-glass` 拍的 —— 玻璃会把自己也抓进背景（半透明重影），
那是**调试口子的副作用**，正常运行时玻璃被排除在抓屏外，没有重影。

### 调试口子速查（这一轮新增/改动）

```bash
F=/sdcard/Android/data/com.glslplayground.app/files
# 喂一条假审批（内容见 DshApprovalClient#handleDebugPayload 的 JSON 字段）
su -c "echo '{\"event\":\"approval/request\",\"toolName\":\"bash\",\"reason\":\"...\"}' > $F/approval.json"
# 截图时把玻璃 + 审批面板都留在抓屏里（拍完 touch hide-glass 撤掉）
su -c "touch $F/show-glass"
```

### 3.1 补：内容一多，窗体过大 + 圆角被挤成方形（2026-11-06 二修）

用户报：审批/提问内容多的时候窗体太大，边缘圆角像是被挤成了方形。

根因（实测日志抓到的）不是圆角公式，是**渲染比例算错**：

```
geom: siri=true sv=0.471x0.812 corner=0.280 -> hx/hy/cr=421/725/203
      win=974x1583 buf=793x579 k=1.23 ref=993
```

`k = 绘制缓冲 / 窗口像素`。`updateApprovalWindow()` 改了窗口尺寸，却**没把新的窗口尺寸
写给渲染器**（`renderer.winPixelW/H` 还停在圆球那次的 `793x579`）→ `k` 被算成 `1.23`，
整块形状被放大 23%，四角被窗口边界直接切平 → 看着就是"圆角变方了、窗体变大了"。

修法（`FloatingBallService#updateApprovalWindow`）：
- 每次算完 `W/H` 立刻 `renderer.winPixelW = W; renderer.winPixelH = H;`（窗口尺寸，不是缓冲尺寸）
- 顺手 `setGlassBufferScale(W*H)`：面板大时按 0.6 左右降分辨率画
- 面板高度上限 68% → **58% 屏高**（9 个选项那档不再顶满屏幕）

实机：`k` 回到 1.00、形状 `hx/hy/cr=421/725/203`，四角是完整的 203px squircle
（`glass-approval-big-fixed.png` 是球体自己那一帧，没有 show-glass 自采样重影）。

### 3.2 补：按钮/选项跑到玻璃外面 + 玻璃以外的触摸被吃（2026-11-06 三修）

现象：内容多的审批，选项/输入行落到大玻璃**下面**去了；而且玻璃外面那圈（仍在 973×1489 的
球窗里）点不动。

两个原因，都是这次改布局时带出来的：

1. **坐标系少了 inset**：`updateApprovalLayout()` 里把「从玻璃内底边往上摆」改成了用屏幕坐标算，
   但写进 `WindowManager.LayoutParams` 时要减掉系统 inset（`sysOffset = winYnow - params.y`，
   本机 90px）。原来只有 `textY` 减了，倒着摆之后 `actY/inY/textY` 全都漏 → 整块内容下移 90px，
   于是输入行/最后几个选项跑出玻璃。现在三个 y 统一 `- sysOffset`。
2. **面板期间球层没让开**：`overlayTouchState()` 只算了 搜索/菜单/全屏玻璃，**没算审批面板** →
   球的触摸代理（973×1489 大盒子）继续接手势，玻璃外那圈透明区域把点击全吃掉。
   现在把 `approvalShown || approvalClosing` 也算进去，并在 `showApprovalPanel()` /
   `hideApprovalPanel()` 里显式 `applyBallTouchability()`。

实机（dumpsys window 实测，面板 973×1489 @[54,112]-[1027,1601]，玻璃 [120,178]-[961,1534]）：
- 文字块 `[246,276]-[835,631]`、选项层 `[239,677]-[843,1329]`、输入行 `[305,1375]-[776,1508]` —— 全在玻璃内
- 触摸代理已变成 `NOT_TOUCHABLE` + 2×2 空区域；玻璃窗本身一直是 `touchableRegion=empty`
- 截图：`glass-approval-content-inside.png`（9 选项 + 长说明，全部落在玻璃里，最后一行淡出）

### 3.3 补：审批玻璃「跟别的玻璃不一样 / 像没实时渲染」（2026-11-06 四修）

先量了一遍，**它其实是实时的**（同一块背景换一次，球体自己那一帧的差异）：

| 场景 | 平均像素差（0-255） |
|---|---|
| 圆球（无面板） | 9.5 |
| 审批面板，`show-glass` 关（正常） | 42 / 45 / 54（RGB） |
| 审批面板，`show-glass` 开 | 0（**调试口子的副作用**：玻璃留在抓屏里 → 球体区域走「无缝抠图」保留旧背景，当然不动） |

所以"没实时渲染"的来源是 `show-glass` 这个截图口子，正常用不会这样。

真正让它**看着**像另一块玻璃的是材质：面板分支 `tBlackK = mix(0.92, 0.10, pow(top01,1.6))`
+ `blackMask≈1`（`aprBlack` 把黑区下沿推到球底）→ 整块面板 90% 都是黑的，折射只剩边上一点，
当然和球/胶囊那种"上黑下透"完全不是一个观感。

改法（`GlassRenderer` FS_COMP 面板分支）：
```
tBlackK = mix(0.92, 0.02, pow(top01, 0.60));   // 原来 0.10 / 1.6 次幂
```
顶部保持 0.92（文字可读），**掉得快**：25% 高度处 ≈0.49、底部 0.02 —— 下半个面板就是普通玻璃，
和球/胶囊同一套观感。文字块自己还有 34% 的深色底（`AP_PANEL_BG`），可读性不受影响。

截图：`glass-approval-glass-after.png`（设置页在后面，能透出搜索框/头像/列表的折射与色散）。

### 3.4 补：输入框高度 / 连按两下的怪形状（2026-11-06 五修）

用户：①「聊天界面里快速连按两下电源键触发语音，输入框显示有问题，再试一次又好了」；
②「输入框要原版的高度，只有文字需要换行时才变高，不是一开始就这么高」。

**② 高度语义**（`BallSearchBar.desiredHeightPx`）：原来固定返回「下限 50dp」，于是
- 空文本也报 50dp → 对话卡那条 40dp 的输入行被拉到 138px（一开始就高）；
- 上一次多行留下的 `Layout` 还没跟上新文字，也会报大值 → 胶囊一打开就是高的。

现在回调语义改成「**这段文字要多高，0 = 没有额外要求**」：
- 空文本 / `Layout.getText()` 和当前文字不一致 → 返回 0；
- 搜索胶囊：0 → `SQ_CAP_H`（原版 50dp）；有字要换行才按行数长（上限 ≈4 行）；
- 对话卡输入行：0 → 原版 40dp，最多长到 ≈2 行。
另外 `onSearchInputHeight()` 只在胶囊**已落定**（`searchActive && searchOpen`）时才改弹簧，
`sqToCapsule()` 落定瞬间先把 `searchCapsH` 复位成 `SQ_CAP_H`。

**① 连按两下的怪形状**，两个来源：
- 聊天卡片开着时，卡片那条输入行是**另一个窗口**，胶囊一开就两条输入框叠在一起 →
  `enterSearchMode()` 现在把 `convoBar` 藏起来，胶囊收完（`finishSearchClose`）再放回来；
- 第二下按得太快（`powerHoldDown` 还是 false）时原来直接 `closeSearchMode()` → 胶囊在
  弹开半路被收回，屏幕上是半截怪形状。现在 `endPowerHold()` 会**等输入法语音真的开起来**
  （`powerHoldDown=true`，最多 1.4s）再走正常收尾。

实机：空胶囊打开不再出现 `胶囊高` 日志（保持 50dp）；47~40 字的长句 → `胶囊高 127~130dp`；
连按两下（间隔 350ms）日志是「第二次按得太快 → 等语音真正开起来再收尾」→ down → up，
中途截图里输入行就是原版单行，没有叠影。

### 3.5 补：高度要「跟着文字体积」，不是定死（2026-11-06 六修）

用户：「不对，是根据文字体积动态调整高度，而不是定死的」。

上一版有两个「定死」：
1. **上限钉在 130dp**（`SQ_CAP_H_MAX = 2.6 × 50`）—— 文字再多也不长了；
   现在上限是 `min(8 行, 屏高 42%)`（`searchCapsHMaxDp()`），长句能一路长到 ~300dp 才在框里滚。
2. 更要命的是一个**正反馈 bug**：高度用 `TextView.getTotalPaddingTop/Bottom()` 算。
   这个 API 把「文字的竖直偏移」也算进去（`CENTER_VERTICAL` 时视图越高它越大）→
   算出来的高度越大 → 视图越高 → 再算更大……实测一路涨到 900px 还在涨（`barH 853→889…`）。
   改用 `getPaddingTop/Bottom()`（纯内边距）之后就稳了。

现在的实测（同一段文字逐次加长）：

| 文字 | 行数 | 胶囊高 |
|---|---|---|
| 空 | — | 50dp（原版） |
| 短句 | 1 | 50dp（不长） |
| 中等 | 2 | 65dp |
| 长句 | 4 | 107dp |
| 很长 | 10+ | 一路长到上限（8 行 / 屏高 42%），再长在框里滚 |

也就是说：**高度 = 文字实际高度 + 上下内边距**，一行时正好是原版胶囊，字多了按体积长。

## 2026-11-06（六）：音量加 = 隐藏全部悬浮界面

用户要的：**检测到音量加键就把所有界面藏起来**；之后**弹审批**、**按电源键**、
或者**用别的方式打开 App** 会恢复。

- `PowerKeyDaemon`：新增 `KEY_VOLUMEUP(115)`，按下即发**隐式**广播
  `com.glslplayground.app.action.VOLUME_HIDE`（`-p` 限定包名；球没开时没人收，不冷启动 App）。
- `FloatingBallService`：
  - 动态接收器（和音量减同一个）多匹配一条 `VOLUME_HIDE` → `applyUiHidden(true, "音量加")`；
  - `applyUiHidden(true)`：先把胶囊/四区/菜单拆干净（`dropSearchNow` / `exitZones` / `hideMenu`），
    再 `glView.setVisibility(INVISIBLE)` + 藏文字层 + 藏对话卡输入行；`applyBallTouchability()` 让代理层让开；
  - 恢复：`glView.VISIBLE`、按需恢复文字层/输入行、`centerBallOnScreen()` + `measureSiriTargets()`；
  - 藏在状态里时：`armVoice()` / `enterSearchMode()` / `showSiriTextNow()` 都不许自己冒出来。
- 恢复入口三个：`showApprovalPanel()`（审批来了先 `applyUiHidden(false,"审批弹出")`）、
  `PowerBallReceiver.handle()`（电源键先 `setUiHidden(false,"电源键")`）、
  `MainActivity.maybeAutoFloat()`（打开 App → `setUiHidden(false,"打开 App")`）。
- `pkctl.sh` 新增 `volup`（sendevent 注入 KEY_VOLUMEUP 115）。

实机验证（同一轮）：
```
PowerKeyDaemon: 音量加键 → 发 VOLUME_HIDE @/dev/input/event2
FloatingBall: 收到 VOLUME_HIDE → 隐藏全部界面
FloatingBall: ui: 隐藏全部界面（音量加）
   …按电源键…   FloatingBall: ui: 恢复界面（电源键）
   …弹审批…     FloatingBall: ui: 恢复界面（审批弹出） → approval panel up
   …打开 App…   FloatingBall: ui: 恢复界面（打开 App）
```
截图对比：隐藏后屏幕上连球的玻璃都没有（`show-glass` 开着也拍不到）。

## 2026-11-06（七）：音量加隐藏 → 电源键恢复后「折射消失」的根因与修法

用户报：**音量加把界面藏掉，再长按电源键呼出来，球的折射效果就没了；杀掉 App 重开又正常。**

### 根因：`SKIP_SCREENSHOT` 是打在 **SurfaceControl** 上的，而 surface 会重建

1. 隐藏走的是 `glView.setVisibility(View.INVISIBLE)`（`applyUiHidden`）。
   SurfaceView 在 `setVisibility` 里会 **销毁自己的子 surface** —— 实测：
   `dumpsys SurfaceFlinger --list` 里 `SurfaceView[]#47482` 这层直接消失。
2. 恢复可见时系统**重建** surface：新图层 `SurfaceView[]#47565`，`getSurfaceControl()`
   返回的是**另一个对象**。`SKIP_SCREENSHOT`（抓屏排除本窗口）打在旧对象上 → 一起没了。
3. 服务这边 `renderer.selfExcluded` 成功一次就置 true、之后再没人复位，
   于是 `syncWinPos()` 里那句
   `if (!renderer.selfExcluded && applySkipScreenshot()) ...` **永远进不去**
   → 新 surface 再也没被打过标记。
4. 结果：root 直采（CapLive 540×1170）开始**把球自己也拍进画面**
   （判据很硬：正常情况下 `screencap` 是拍不到球的，那一刻能拍到 —— 见下图），
   球把"含自己"的抓屏当折射源 → 折射自己的上一帧 → 屏幕上就是一块死玻璃、折射消失。
   而且**不会自愈**：bg 是 ping-pong 的，球体那一块永远保留旧内容，脏图一旦灌进去就卡死；
   只有杀进程重开、重走一遍 `surfaceCreated` → 重新打标记才好（正是用户看到的现象）。

### 修法

| 位置 | 改动 |
|---|---|
| `FloatingBallService.ensureGlassSkipScreenshot()` | 判据从「打过没有(boolean)」改成「**当前这个 SurfaceControl** 打过没有」；SurfaceControl 一换对象就补打，并复位 `surfaceTrusted` / `textSkipShotDone` |
| `FloatingBallService.watchGlassSurface()` | `glView.getHolder().addCallback(...)`：`surfaceCreated` 立刻补打（500ms 的 `winSyncLoop` 只是兜底）；`surfaceDestroyed` 把 surface 级标记全部作废 |
| `capWatchdog` | 每 2 秒兜底调一次 `ensureGlassSkipScreenshot()`（同时保留受信任 overlay 的补打） |
| `GlassRenderer.resetBackground()` | 补打成功后重灌背景：标记失效那几帧「含球」的画面必须冲掉，否则被 ping-pong 永久卡在球里 |
| `GlassRenderer.onSurfaceCreated/onSurfaceChanged` | 换 EGL 上下文时把 program/buffer/texture/FBO 引用全部置 null（**不** glDelete，那些名字属于旧上下文）；`onSurfaceChanged` 里补上真正的 `glDelete`（`Fbo.release()` + `releaseFbos()`），修掉每次 surface 重建漏 ≈9MB 显存 |
| 调试钩子 `show-glass` / `hide-glass` | 同步维护 `glassMarkedSc`，别让补打逻辑和调试开关打架 |

### 实机验证（MI 10 Ultra / Android 13，root 直采 src=1、低延迟 540×1170）

```
# 修复前
FloatingBall: ui: 隐藏全部界面（音量加）
FloatingBall: ui: 恢复界面（电源键）
（没有任何 skip-screenshot applied）
$ screencap -p probe.png          → 截屏里**能看见球** ＝ 球在抓屏里 ＝ 自采样

# 修复后（同一套动作，连续 3 轮）
FloatingBall: glass surface destroyed → surface 级标记作废，等重建后补打
FloatingBall: glass surface created → 补打 surface 级标记
FloatingBall: skip-screenshot applied: glass
FloatingBall: 背景重灌（抓屏排除补打）
$ screencap -p probe.png          → 截屏里看不见球了
$ touch files/ballshot            → files/ball-capture.png：桌面文字被折射、边缘彩边，正常透镜
```
3 轮「隐藏→恢复」：补打 ×3、`skip-screenshot applied` ×3，无 `GL_INVALID` / `Framebuffer incomplete`。

对比图（同一部机、同一个钩子拍球自己那一帧）：
`shots/ball_idle_with_exclusion.png`（正常：透镜） vs `shots/ball_idle_selfsampled.png`（自采样：整颗发白过曝、
桌面被自己的画面糊掉）—— 平均像素差 25/255，最大 255。

## 2026-11-06（八）：长按电源键 / 音量减键的语音发送改成「插话」

以前这两条语音通路发出去的话都是 `mode=queue`（DSH 侧 `agent.followup()` → `next-turn`），
球里正在跑的时候补一句，只能等这一轮跑完才被看到。用户要的是**插话**：

- DSH 的 `session/prompt` 有 `mode: "queue" | "steer"`；`steer` 走 `agent.steer()`，
  消息落在 inbox 的 **`next-step`** 位 —— 当前这一轮的下一个 step 边界就吃到，等于边跑边改主意。
  会话空闲时两者等价（`agent.steer()` 也会 `wakeDriver` 开新一轮）。
- 客户端把 `mode` 从 `SiriAssistant.Transport` → `DshHttpTransport.prompt()` 一路透传；
  `FloatingBallService` 里只有 `sendTextToHoldSession()` 这条路传 `MODE_STEER`，
  打字的发送（胶囊 / 菜单 / `sendFromBar`）仍是 `MODE_QUEUE`。
- 传输层日志新增 `prompt mode=… session=…`，一眼能看出这句话是插话还是排队。

实测：3000 字长文跑到一半时插话「STOP… reply exactly: GOT IT」，
两条 follow 流在同一秒收尾、最终正文 6 个字符 —— 长文当场被掐，不是写完再回。

## 2026-11-06（九）：冷启动第一颗球为什么是大的

用户问：**「为什么这个软件刚刚启动时第一次弹出的球还是大的？」**

时间线（冷启动，`logcat` 原文）：

```
01:51:47.825 setupOverlay src=1 …
01:51:47.899 window audit(idle): 窗口 993x993 → 793x579（球 661x447 + 2×24dp）   ← 画的是原版大球
01:51:48.586 电源键：弹胶囊 + 交给输入法语音
01:51:48.587 ball: 常态球 360x243（屏宽 1/3，×0.544；原版 661x447）              ← 这之后才变小
```

根因：**「缩到屏宽 1/3」只做在 Siri 那条几何路上**。

- Siri 形态走 `sv*`（服务侧动画值，基准是 `minHalfX = minHalfX0 × ballIdleScale()` 已经缩好的 1/3）；
- 而**普通球那条路**（`siriGeom=false`）在 `GlassRenderer.onDrawFrame` 里是
  `hx = base*halfX*master` —— 原版 `halfX=0.37` → 661×447。
- 冷启动时 `float_siri` 是 false，`applySiriEnabled(true)` 要等到第一次
  `enterSearchMode`（长按电源键）/出现球内文字才被调用；在那之前窗口已经显示、
  渲染已经在跑 → 头 0.6~0.7 秒屏幕上是原版大球，然后"啪"地缩成 360×243。

修法：

- `GlassRenderer` 新增 `idleScale`（服务侧 `ballIdleScale()` 算好塞进来），
  非 Siri 形态下 `hx/hy/cr` 同乘它；`shapeK` 自然变成 0.544，
  镜缘/带宽/圆点环半径跟着一起缩（和 Siri 那条路一致）。
- `FloatingBallService.setupOverlay()`：建完 renderer 立刻
  `renderer.idleScale = ballIdleScale()`，并把 `svHalfX/svHalfY/svMaster` 也按小球起手
  （它们的默认值 0.37/0.25 是原版大球，会让 `glassHalfW()` 在 siriTick 第一次写值之前
  把触摸代理撑成 661 那一档）。
- `applySiriEnabled()` 里同步刷新 `renderer.idleScale`（`minHalfX0` 可能刚更新）。
- 新增一条一眼能验的日志：`GlassRenderer: first frame geom: 球 360x243（… idleScale=0.544 …）`，
  每次 surface 重建后打一次。

实测（同一台机，改完冷启动）：

```
01:54:09.438 window audit(idle): 窗口 993x993 → 793x579（球 661x447 + 2×24dp）   ← 窗口还是原版窗（不变）
01:54:09.675 first frame geom: 球 360x243（hx=180 hy=122 siriGeom=false idleScale=0.544 win=793x579）
01:54:10.127 ball: 常态球 360x243 …                                              ← 和稳定态一模一样
```

窗口尺寸刻意**没动**（还是 793×579 = 原版球窗 + 24dp）：球缩了但窗口不缩，
六区菜单/聊天卡片都画在这个窗里，缩窗会把它们切掉（见 `auditWindowAgainstBall` 那段注释）。

## 2026-11-06（十）：球「附近那片屏幕点不动」——代理窗口的可触摸区比球大一圈

用户报：**球周围（尤其球左上角方向）那片屏幕点不动**。

根因在触摸代理窗口（`ballTouch`）的**可触摸区**：

- 代理窗口的大小 = 球 + 2×12dp 的 pad（`syncBallTouchWindow`，为了拖动好抓）；
- 而 `buildTouchRegion()` 以前是拿**窗口尺寸**做胶囊区域（`r = min(w,h)/2`），
  于是整个窗口（球 + 那一圈 pad）都算"可触摸"；
- 手指落在这圈 pad 上时，事件**派给了代理窗口**，可 `onTouch` 里
  `if (!inBallAtRaw(...)) return false;` —— 事件就被丢掉了。
  Android 的手势一旦确定了目标窗口，**不会**再转发给下面的 App ——
  所以这一圈是"看得见、点不动"的死区。

实测（`dumpsys input`，球的玻璃 360×243）：

```
修前：FRAME [328,405][754,714]   REGION bbox x 328..754 (426) y 405..714 (309)   ← 球 + 一圈 pad
修后：FRAME [328,405][754,714]   REGION bbox x 361..721 (360) y 438..681 (243)   ← 正好是球
```

修法（`FloatingBallService.buildTouchRegion`）：`v == ballTouch` 且形态是圆的/胶囊时，
区域按 `glassHalfW()/glassHalfH()`（和渲染同一套公式）算内切圆角矩形，圆角用
`ballCornerRatio()`（常态球 corner=1 → 胶囊；方角卡片 → 矩形），居中对齐；
`pad` 那一圈直接**不吃触摸**、穿到下面的 App。
`glassHalfW/H` 量不到（≤4px）时退回整块矩形，不会把球自己锁死。

其余形态不受影响：搜索胶囊 / 卡片菜单 / 六区 / 审批面板（`roundishOk()` 为 false）仍是整矩形。

## 2026-11-06（十二）：球的触摸区整体比球**低 90px**（状态栏高度被加了两次）

用户第二次报同一件事：**球旁边那个按钮点不动**（截图上球就在按钮右边一点）。

拿 `files/show-glass` 把球拍进截屏里量了一下，问题很清楚：

```
球的画面（截屏实测）      y 250..490
触摸可触摸区（dumpsys）  y 246..489   ← 修好之后
------------------------------
修之前：球画面             y 230..475
        代理窗口            frame [327,303]-[753,612]  → 比球低 90px
```

根因在 `syncBallTouchWindow()` 最后那一步换算：

- 它先算出球的**屏幕**中心 `cy = params.y + winY0() + winH/2`（overlay 窗口的
  `params` 是"屏幕坐标 − 状态栏"，所以这里加 `winY0()` 是对的）；
- 但往回写 `ballTouchParams.y = cy - h/2` 时**忘了再减掉 `winY0()`** ——
  WindowManager 会自己再加一次（实测 `mAttrs=(327,213)` → input frame 顶边 303 = 213+90）。
  于是整个代理窗口 / 可触摸区**往下偏了一个状态栏高度**。

后果正是用户看到的两件事：

1. 球的上半截点不动（命中区不在那里）；
2. **球下面那一条透明区域反而吃点击** —— 点它什么都不发生，也不会传给下面的 App。

修法：

- `ballTouchParams.y` 写回时 `- winY0()`，代理窗口和球严格同心（实测都是 `(540, 367.5)`）。
- 顺带：`bareBall`（光秃秃一颗球、没文字/卡片/菜单/胶囊/审批）时，玻璃窗不再固定成
  原版球窗 793×579，而是贴着球缩到 492×375 —— 球周围少一大片"透明玻璃"，
  个别 ROM 上那片的点击本来就容易掉。带面板/文字时仍按原版球窗走，排版不变。
- 加了一条诊断：玻璃窗万一真收到触摸，会打
  `玻璃窗收到触摸 @x,y（可触摸区本应为空 → 这一下被玻璃吃掉了）` —— 下次再出这类问题，
  `logcat | grep 玻璃窗` 就能一句话定位是玻璃吃的还是别人吃的。

另外记一笔**设计上就有**的一条：搜索胶囊打开时，有一层全屏「点空白处关掉」
（`searchOutside`）会吃掉胶囊外面的第一下点击（它只负责关胶囊，不会再传给下面的 App）。
这不是 bug，但如果用户觉得"胶囊开着的时候点哪都没反应"，那是这一层。

## 2026-11-06（十三）：点输入框弹键盘太慢

用户：「长按电源键触发语音、字已经进输入框了，手点输入框要很久才弹键盘；要快一点，但**只能手动弹**，别自动弹。」

慢在两处（都在我们自己这边，跟输入法无关）：

1. `focusSearchInput()` 里 `postDelayed(..., 240)` —— 手指抬起 240ms 之后才去要焦点；
2. 更要命的是：`EditText` 的 `setFocusableInTouchMode(true)` 也是在那一刻才设的。
   在那之前点它**根本不会拿焦点**，所以经常是"点一下没反应、再点一下键盘才出来"。

改法（只动"手点"这条线，**胶囊自己弹出来那条路原样不动**，所以不会变成自动弹键盘）：

- `BallSearchBar`：`input` 的 `ACTION_DOWN` 里就把 `setFocusableInTouchMode(true)` + `requestFocus()`
  —— 手指一落下就是可编辑状态。
- 服务侧新增 `focusSearchInputNow()`（`onTapEdit` 用）：立刻 `requestFocus + showSoftInput`，
  再在 120ms / 320ms 各补一刀 —— 悬浮窗没有 Activity，`showSoftInput` 偶尔被系统吞一次
  （窗口焦点还没拿到）。补的时候先查 `imm.isActive(input)`，已经连上就什么都不做，不会重复弹。
- 原 `focusSearchInput()`（`barAutoFocus` 那条：按住说话自动展开）保持 240ms 单次，
  不主动顶键盘 —— 满足"只能手动弹出"。

实测（Sogou 输入法，`dumpsys input_method` 轮询）：点一下 → 0.25s 内 `mInputShown=true`；
日志顺序 `search: 单击 → 进入编辑` → IME 起来。之前这一步光是我们自己的延时就有 240ms，
再叠加"第一次点击只拿到窗口焦点、键盘没弹"的重试，观感就是"点了好久"。

## 2026-11-06（十四）：按住说话不再闪键盘；点输入框 = 结束语音 + 弹键盘

用户：「我不要弹出键盘，我要我主动能弹键盘。语音输入结束的触发条件是音量减或长按电源键，
我想**点输入框**也是结束语音识别并且同时弹出键盘，而不是每次长按进语音时突然弹键盘再隐藏。」

### 为什么会"闪一下键盘"

VoiceMap 模块的机制（`android/voicemap-ime`）：

- `down` → `sStealthEnabled=true` + `applyWindow()` 把输入法窗口 **alpha=0**（隐身），再 `start()` 语音；
- `up`   → 停止识别，继续隐身 4 秒后把键盘收掉。

而我们这边 `enterSearchMode(autoFocus=true)` 走 `focusSearchInput()` → 240ms 后 `showSoftInput()`：
键盘**先**被我们以"正常可见"拉起来，**然后**才被模块隐身 —— 屏幕上就是闪一下。

### 改法

| 位置 | 改动 |
|---|---|
| `focusSearchInputForVoice()`（新增） | 按住说话期间只 `setShowSoftInputOnFocus(false)` + `requestFocus()`：**拿焦点、不弹键盘**。焦点必须有 —— Voicemap 是靠输入连接把字塞进"当前焦点输入框"的；键盘由模块自己 `requestShowSelf()` 拉起来，并且是隐身状态 |
| `beginPowerHold()` | 打开胶囊时置 `searchVoiceMode=true`（这个标记要活到 `sqToCapsule()`，胶囊 250ms 后才落定，所以不能用 try/finally 立刻清） |
| `sqToCapsule()` 的 `barAutoFocus` 分支 | 语音路走 `focusSearchInputForVoice()`，其它路照旧 `focusSearchInput()` |
| `endVoiceByTap()`（新增） | **点输入框**：`vm().trigger("up")` 结束识别（**不发送**）+ `vm().cmd("stealthoff")` 让键盘立刻显形 + `setShowSoftInputOnFocus(true)` + `focusSearchInputNow()` |
| `closeSearchMode()` | 顺手补一条 `stealthoff`，别让下次键盘还是透明的 |

三条"结束语音"的路径现在是：

- 音量减 → 结束 + 取字 + **发到新会话**；
- 再长按电源键 → 结束 + 取字 + **发回上一次回话**；
- **点输入框 → 结束（不发送），字留在框里，键盘立刻出来接着打**。

实机验证（Sogou + Voicemap）：

```
search: 按住说话 → 输入框拿焦点（不弹键盘，等 Voicemap 隐身接管）
电源键：输入法语音 down
$ dumpsys window windows | grep -A1 "InputMethod}"
    mAttrs={... ty=INPUT_METHOD ... alpha=0.0        ← 键盘在、但完全透明
$ input tap 540 264
search: 点输入框 → 结束语音识别（不发送，wasHolding=true）
search: 单击 → 结束语音并进入编辑
$ screencap → 搜狗键盘正常显示
```

### （十四·补）真正让键盘"闪出来"的是焦点监听

上一版只删了自己那句 `showSoftInput`，用户实测还是"弹出来又自动隐藏"。真正的原因还有两个：

1. `ensureSearchWindows()` 里给胶囊窗口挂了
   `bar.setOnFocusChangeListener(... hasFocus → 80ms 后 showSearchIme())` ——
   **胶囊一拿到窗口焦点就补弹一次键盘**，那时 Voicemap 的隐身还没生效 → 真键盘先亮、再被隐身。
   这条以前是为了"窗口焦点被系统抢走时补一次"，现在加 `!searchVoiceMode` 才跑。
2. 如果上一个 App 的键盘本来就开着（alpha=1），要等 `down`（胶囊开 900ms 后）才隐身 ——
   中间能看见它"亮着→变透明"。现在 `beginPowerHold()` 打开胶囊后立刻发一条
   `vm().cmd("stealthon")`，把隐身提前到胶囊出现的那一刻。

另外在 `showSearchIme()` 里加了最后一道闸：`searchVoiceMode` 期间任何路径调进来都直接 return
（并打一行 `search: 语音期间不主动弹键盘（showSoftInput 被拦下）`），以后新增调用点也不会再漏。

实测（按住电源键 6 秒，每 0.45s 采一次输入法窗口）：

```
ime_                       ← 键盘窗口还没建出来
ime_alpha=0.0              ← 建出来就是全透明，之后一直 0.0（全程没有出现过 1.0）
...
$ 点输入框
search: 点输入框 → 结束语音识别（不发送，wasHolding=true）
ime_alpha=1.0              ← 键盘显形
```

## 2026-11-07（十五）：语音「说着就自己结束 / 只识别到前面 / 说着键盘自己弹出来」

用户三连问：「语音识别的开始后没多久它就自动结束了，我说很长的话只识别到前面的」「为什么说着它就自动弹出键盘？」
「为什么一句话都识别不出来？」。**同一个根因**：电源键长按被送进来两次，第二次被当成"又按了一下"。

### 根因 1：两路输入节点都在报 KEY_POWER

```
$ su -c 'getevent -pl' | grep -B12 KEY_POWER
add device 4: /dev/input/event1   name: "qpnp_pon"          KEY_POWER
add device 8: /dev/input/event2   name: "uinput-goodix"     KEY_POWER
```

`PowerKeyDaemon` 对所有带 KEY_POWER 的节点开一个线程。一次物理长按被两路各报一次
`KEY_POWER down` —— 旧代码每收到一个 down 就 `downAt=now; fired=false`，定时器于是
**再发一条 POWER_BALL**。App 侧 `PowerBallReceiver` 只看 `powerHoldActive`：

```
03:01:06.19  power-ball 触发 holdActive=false → 开始按住说话
03:01:07.87  输入法语音 down
03:01:08.10  VoiceMap: DOWN at (540,1958) handled=true     ← 语音真的开始了
03:01:09.31  power-ball 触发 holdActive=true → 「第二次长按」→ 收麦   ← 1.2s 就结束
```

1.2 秒后 `up`，一句话都还没说完 → "一句话都识别不出来"。
`hideAfterHold()` 又会在 4 秒后把输入法窗口恢复成 alpha=1 → **键盘在说话途中自己弹出来**。

### 根因 2：`up` 之后"看到字就发"发的是中间结果

搜狗是流式出字：按住期间它一直往输入框里写中间结果（实测 31 字时还在变）。
旧 `waitImeTextAndSend` 每 200ms 轮询，**一看到非空就发** —— 发走的是前半句，
终稿到达时胶囊已经关了。这就是"只识别到前面的"。

### 改法（全部在"调度输入法"这一层，没有换引擎、没有手搓识别）

| 文件 | 改动 |
|---|---|
| `tools/PowerKeyDaemon.java` | `pressActive` 跨设备共享：还有一路按着时，后面来的 down 一律忽略；任意一路 up 才复位；`fire()` 1.2s 去重；8s 没等到 up 自动复位（防节点拔掉后卡死） |
| `FloatingBallService.beginPowerHold()` | 2.5s 内的重复 `start` 直接忽略（冷启动「直连 FGS 被拦 → root 跳板又起一次」也会造成双 start），不再当成"第二次按下" |
| `FloatingBallService.waitImeTextAndSend()` | 改成**等字停稳**：连续 `IME_SETTLE_MS=900ms` 没有新字才取；最长等 12s；`up` 后先等 350ms 给终稿；日志会打"输入法还在出字（N 字）" |
| `FloatingBallService.closeSearchMode()` | 顺手清 `powerHoldActive/powerHoldDown` 并补一条 `up`（以前胶囊被点空白关掉后 hold 状态一直挂着，下一次长按变成"结束一个不存在的语音"） |
| `voicemap-ime` `VoiceMapHook.start()` | 看门狗 20s → **180s**（长语音不再被强行松手） |

### 实测（新 APK + 新 daemon + 新模块）

```
03:04:54.406 电源键：弹胶囊 + 交给输入法语音
03:04:55.583 电源键：输入法语音 down
03:04:56.057 VoiceMap: DOWN at (540.0,1958.175) handled=true
   …（13.5 秒里没有任何第二条 power-ball）
03:05:09.567 power-ball 触发 holdActive=true → 第二次长按（这次是真的）
03:05:09.592 VoiceMap: UP after 13542ms handled=true
03:05:09.935 电源键：输入法还在出字（31 字）
03:05:10.850 电源键：输入法结果 = 「你好，你好，刚刚语音识别。本来就退出了，现在能识别很长一段吗？」（静默 915ms / 共 1266ms）
```

按住时长从 1.2s → 13.5s，取到的是终稿整句。

### 部署注意

- 改了 daemon 要 `cp android/assets/powerkey-daemon.jar /data/adb/powerkey/` 再
  `sh android/assets/pkctl.sh daemon-restart`；
- 改了模块要 `bash android/voicemap-ime/build.sh` → 装 `out/voicemap-ime.apk` →
  **杀掉输入法进程**（LSPosed 只在进程启动时注入）。

### （十五·补）语音长度必须由用户决定 —— 输入法自己收尾就自动续按

用户：「识别的长度不是由用户什么时候按下音量减或者电源键或者点击输入框来结束吗？
就像输入法长按空格键什么时候松手也是用户决定。」对，这才是目标语义。但实测有第二个"自己结束"的来源：

```
03:11:14.308 VoiceMap: DOWN at (540,1958) handled=true      ← 语音开始
（中间没人说话）
03:11:29.9  rec start session:481 … 03:11:36.2 rec stop     ← 输入法自己收了尾（≠ 我们发的 up）
```

搜狗在**长时间没听到人声**（实测 6~17 秒，随环境噪声变）时会自己 stop 并把字上屏。
我们这边 `powerHoldActive` 还是 true，用户后面说的话就全丢了 —— 观感就是"说到一半没了"。

处理：按住期间每 1.2s 看一次 `AudioManager.getActiveRecordingConfigurations()`：

- 有录音 → 正常；
- 连续两次（≈2.4s）一个录音都没有，且已经过了开头 3.5s 的启动期 → 补发一条 `vm().trigger("down")`
  （模块侧 `pressDown` 遇到 `sPressed==true` 会先 `re-down` 复位再按，不会把新 DOWN 当成同一串手势）；
- 续按后 4 秒内不再判定（给输入法重新起录音的时间），总时长封顶 **175s**（和模块看门狗 180s 对齐）。

实测（按住 26 秒不说话，中途输入法自己收了两次尾）：

```
03:11:29.538 语音：输入法自己把录音停了（还按着）→ 自动续按，继续听
03:11:29.540 VoiceMap: re-down: 上一次按住已结束但没有 UP，重新按
03:11:29.542 VoiceMap: DOWN at (540.0,1958.175) handled=true
…
03:11:40.805 power-ball 触发（用户按结束）
03:11:41.209 电源键：输入法还在出字（39 字）
03:11:42.124 电源键：输入法结果 = 「…」（静默 916ms / 共 1268ms）
```

于是"什么时候结束"只剩用户那三种动作：音量减 / 再长按电源键 / 点输入框。

## 2026-11-07（十六）：六区菜单/工作目录卡片被裁切、变形（球缩到 1/3 之后的后遗症）

用户：「菜单显示错位还被裁切，工作目录页面也变小了，进入工作目录也回到菜单界面就乱完了图标都对不准了」。

根因不在菜单代码本身，而是 **球缩到屏宽 1/3（0e01d80）+ 空闲窗口贴着小球缩（59ff928）** 之后，
六区/卡片还在拿"当前窗口"当尺寸基准：

| 位置 | 旧逻辑 | 结果 |
|---|---|---|
| `computeZones` | `maxR = (min(winW,winH)*0.5-dp8)/3.1` | 窗口 492×375 → R 被压到 53（菜单一小坨） |
| `computeCardRects` | `bw = min(dp(260), winW - dp(36))` | 卡片只剩 393px 宽 |
| `computeCardRects` | `avail = winH - dp(20)` | 只能放 2 行（其余被裁） |
| `ensureZoneWindow()` | **从 d9bfcc1 之后没有任何调用** | 窗口永远不会为六区撑大 |
| `ensureHoldWindow()` | **从来没有调用点** | 卡片比窗口大时不会长高 |

### 改法

- `computeZones`：半径按**原版球窗**夹（`minHalfX0/minHalfY0`）→ 回到用户认可的"小一号"（R=86），
  进状态 3 时调一次 `ensureZoneWindow()`，窗口只撑到装下这个环（实测 573×573 @(254,112)）。
- `computeCardRects`：卡片宽度/行数按**屏幕**算（715px 宽、最高屏高 72%），不再受球窗限制。
- `showCards` 里调 `ensureHoldWindow()`（现在会同时加宽 + 加高窗口，并把 `zonesParams` 一起改）；
- `restoreHoldWindow()` 补上 `x` 还原（`holdWinX0`）；
- `exitZones()` 先 `restoreHoldWindow()` 再清基准，否则退出后留一个 573×573 的大窗
  （球画在中间、四周都能点）。

实测（文件钩子 zone-open / zone-release）：

```
球窗 327,213 426×309  →  zone-open: 224,82 634×634（撑到装下六圆）
                       →  zone-release: 327,213 426×309（收回小球，一个像素不差）
卡片：715px 宽完整显示（修复前 393px、2 行）
```

> 踩过的坑：第一版把半径直接按屏幕算，六圆变得比原来大一圈（用户：「菜单怎么变这么大了？原本可是小小的」）。
> 正确做法是"半径按原版球窗（小一号）"，只把**窗口**撑到够，而不是把圆放大。

### （十六·补）「胶囊被压扁」—— 窗口缩回来时 GL 缓冲没跟着缩

`ensureHoldWindow` 会把窗口撑宽到卡片宽度（715px），关卡片/退出状态 3 时窗口缩回 492×375，
但 **`renderer.winPixelW/H` 和 SurfaceView 的 buffer 还停在 715**。GLSurfaceView 会把那张
宽图硬压进窄窗口 → 球被横向压扁（用户：「胶囊都被你压扁了」）。

修法：`restoreHoldWindow()` 和 `exitZones()` 在改完 params 之后统一
`renderer.winPixelW/H = winW/winH` + `resetGlassBufferFull()`（= `setFixedSize(winW, winH)`）。

验证：`files/ballshot` 抓一帧，`ball-capture.png` 是 492×375，球是 360×243 的胶囊（宽高比正确）。

### （十六·补二）图标没对准 / 卡片点不了返回 —— 状态 3 期间**不许再动窗口**

对照 GitHub 上正常那版（`origin/main`）发现关键差别：**它的球窗本来就是原版 793×579，进状态 3 从不改窗口**，
所以 GL 的 `k = winW/winPixelW = 1`、图标层和玻璃层用同一套窗口坐标，天然同心。

球缩到 1/3 之后我们这版窗口只有 492×375，我上一版用 `ensureZoneWindow()` 把它撑到 573 —— 这一改就出问题：
`updateViewLayout` 之后 GL surface 和 View 坐标系差一拍，图标离圆；触摸代理窗口也没跟着卡片走，标题行点不到（回不去）。

改法（对齐 main）：
- 新增 `ensureZoneBaseWindow()`：进状态 3 的**第一件事**把窗口摆回原版球窗（793×579，以球心为中心），
  之后整个状态 3（六圆 + 卡片）**一次都不再改窗口**；
- `ensureZoneWindow()` 保留但已成 no-op（793 已经够装下 R=86 的环）；
- `ensureHoldWindow()` 只在卡片真的比窗口宽时才挪 x（以前 `panelW>0` 就挪）。

实测：`zones: 基准窗口回到原版球窗 793x579 @(144,90)` → `六区窗口 793x579` → `R=86`。

## 2026-11-07（十七）：搜索胶囊变黑/跑偏、"菜单图标对不准" —— 两个结构性根因 + 取证工具

用户报的两件事：
1. 「搜索框有时候会无缘无故从好好的图 1 自动变成图 2」（图 2 = 顶部只剩 "Search or Ask" 一行字，
   下面一颗黑色实心块，胶囊玻璃整个不见）；
2. 「菜单界面多尝试几次长按滑动到菜单选项就有概率出现图 3」（六区图标浮在桌面上、没玻璃/对不准）。

### 根因 A：状态 2（胶囊）和状态 3（六区/卡片）能**同时为真**

`enterSearchMode()` 以前不检查 `zonesActive`：六区菜单还开着的时候按电源键 / 语音 / 点球，
`zoneMode` 和 `searchMode` 同时为 true。GL 那条分支先判 `zoneMode` →

* 画出来的是**六区那一层**（暗玻璃圆 / 卡片），胶囊的玻璃就"没了"；
* 输入行窗口（`BallSearchBar`）还照旧摆在顶部 → 屏幕上只剩一行 "Search or Ask" 文字；
* 用户看到的正是图 2（文字 + 旁边一颗黑块）。

取证（`files/witness` 钩子，见下）：

```
flags=searchActive:true ... zonesActive:true      ← 两个都开着
blob=540,264,959,137   barReal=60,195,960,137     ← 胶囊矩形本身是对的
```

改法（两处，互为兜底）：
* `enterSearchMode()` 开头：`zonesActive` → 先 `exitZones()`；`menuShown` → 先 `hideMenu()`；
* `GlassRenderer.onDrawFrame()`：**搜索层先判**（`if (searchMode)` 放到 `if (zoneMode)` 前面）。

实测（六区 + 卡片都开着时开胶囊）：

```
search: 状态 3 还开着 → 先退出状态 3 再开胶囊
search: 小黑球弹开成胶囊 cx=540 cy=264 半宽/半高=480/69
flags=... zonesActive:false   blob=540,264,959,137   barReal=60,195,960,137
```

### 根因 B：`restoreHoldWindow()` 改窗口却**不改绘制缓冲**

卡片列表关掉、回到六区时，窗口从 793×579 缩成 574×574（`need = 2*(ring+R)+14dp`），
但 `renderer.winPixelW/H` 和 SurfaceView 的 buffer 还停在 793×579 →
`k = 缓冲 ÷ 窗口` 不再是 1，六个玻璃圆被**横向压扁 0.72 倍、整体左移 110px**，
而图标是 View 按"屏幕坐标 − 窗口原点"画的，两边就对不上了。

实测（修前，`files/witness`）：

```
hb: zones=1 win=144,0,574x574 view=574x574 pixel=793x579
hb 不一致 → 形状会被压扁/错位：params=574x574 view=574x574 pixel=793x579
```

从 `ballshot` 量出来的六圆中心/尺寸 vs 图标：

| | 玻璃圆（实测） | 图标（zoneCx/zoneCy） |
|---|---|---|
| 环心 x | 430 | 540 |
| 圆直径 | 125×172（压扁） | — |

改法：
* `restoreHoldWindow()`：改完 params 补 `renderer.winPixelW/H = w0/h0` + `resetGlassBufferFull()`
  （和 `ensureZoneWindow()` 同一套）；同时在 `zonesActive` 时**以环心重新摆位** ——
  以前只改宽高不改 x/y，窗口右边停在 718，而六环最右到 807，右边两个圆会被窗口裁掉；
* `heartbeatTick()`（新增，状态 2/3/菜单开着时每秒一行）：发现 `params / view / pixel`
  三者不一致就**按 View 真实尺寸自愈**（同步 pixel + 重新钉 buffer），并在 logcat 里留 WARN。
  以后谁再漏同步，日志里直接能看见、且不会一直坏在屏幕上。

修后：

```
hb: zones=1 win=144,0,574x574 view=574x574 pixel=574x574   ← 三者一致
```

### 顺手修的：长按回调迟到 → 幽灵状态 3

`postDelayed(longPress, 480)` 在主线程被占住时会**迟到**到手指抬起之后才跑，
那时照样 `enterZones()` —— 屏幕上凭空冒出六区，接着随便点一下就变成"菜单操作"。
现在 `longPress` 里加了 `fingerDown` 判据（手指已抬起就不弹）。

### 新工具：取证快门 `files/witness` / `tools/witness.sh` / 双击音量加

玻璃层带 `SKIP_SCREENSHOT`，所以**普通截图 / 录屏里它是完全隐身的** —— 出问题时根本拍不到证据。
现在有三条入口，产出同一套三件套：

| 入口 | 说明 |
|---|---|
| `bash tools/witness.sh`（Termux） | 一条命令，拍完自动把 png/txt/log 收进 `shots/witness/<时间>/` |
| `su -c 'touch .../files/witness'` | 文件钩子（脚本/调试用） |
| **快速双击音量加**（两下间隔 <420ms；daemon 判，和「单击藏界面」互斥） | 手上正在看 bug、又不想碰屏幕时用 |

产出：
* `/sdcard/Pictures/Screenshots/glass_<时间>.png` —— **普通截图**，玻璃层在里面（相册可见，可直接发给 AI）；
* `.../files/glass_<时间>.txt` —— 状态快照（`blob` / `barReal` / `springs` / `window` / `pixel` / `k` …）；
* `.../files/glass_<时间>.log` —— logcat 里本 App 最近 400 行。

实现要点（`witnessCapture()`）：
1. 临时 `setSkipScreenshot(false)`，让这一层进 screencap；
2. `renderer.freezeCapture = true` **冻结采集** —— 否则我们自己的抓屏会拍到球本身，
   球开始折射自己，玻璃当场变暗变糊（实测对比图见 `shots/witness/`），拍下来就是伪证；
3. root `screencap -p` 一张（≈2s），落盘 + `MEDIA_SCANNER_SCAN_FILE`；
4. 立刻恢复 `SKIP_SCREENSHOT` 与解冻。

⚠️ 旁挂文件**不能**写 `/sdcard/glass-evidence`：本 App 没有 `WRITE_EXTERNAL_STORAGE`，
直接写 /sdcard 根目录会 ENOENT（第一次就这么丢的 txt/log）；只有截图那张走 root 才能放 Pictures 下。

## 2026-11-08（十八）：菜单界面先不出动画；AI 回复的字不许跑进菜单 / 胶囊

用户两次纠正口径，最终定的是两条：

1. **长按球呼出的那个菜单界面不出动画**（六区 + 菜单里的各级子界面 + 玻璃面板）。
   只压动画，**不动菜单自己的字**（面板标题 / 行名 / 说明照常显示）。
2. **AI 回复的那段字不许出现在这些界面**：菜单（六区 / 子界面 / 玻璃面板）、
   搜索胶囊、输入胶囊 —— 那里本来就不该挂一段 AI 回复。球自己的回复照旧，离开这些界面就回来。

实现（`FloatingBallService`，都带"临时"注释，方便整体回滚）：

| 位置 | 干什么 |
|---|---|
| `menuSurfaceOpen()` | `zonesActive \|\| menuShown`（**别**再用 `holdLevel>0` 当判据：音量加「藏界面」把六区收掉时不清 holdLevel，会误压其它状态，实测踩到） |
| `effectiveAnim(slot, anim)` | 菜单开着时一律返回 `off`；`refreshScene` / `pumpScene` 两处都过它 |
| `showSiriTextNow()` | 开头拦一道：`replyTextShouldHide()` 为真就不放字（这是"放出来"的唯一总口子，靠轮询补刀会先闪一帧） |
| `pumpReplyTextHide()` | 每 100ms 兜一次底：菜单/胶囊开着而文字层又被别的路径放出来 → 再收一次 |
| `restoreReplyTextAfterUi()` | 离开这些界面 → `showSiriTextNow()` 把字放回来 |

免重编的口子（`files/` 下，探测 1 秒一次，`rm` 掉就恢复原样）：

```bash
touch .../files/menu-anim-on     # 菜单里恢复动画
touch .../files/menu-reply-on    # 这些界面里也允许显示 AI 回复的字
```

调试口子（本轮加的）：`echo 任意文字 > files/reply-text` —— 立刻往球里灌一段"假回复"，
不用真跑一轮对话就能验"菜单里该不该出现这段字"。

实测（本机 2026-11-08 23:20，root screencap，图在 `shots/menuhide/`）：

* `final-2`：六区开着 → 只有图标，**没有 cap8 跳环动画**、没有回复文字；日志
  `菜单界面：动画暂时藏起来（槽=zones 绑定的是 cap8 → 实际 off）`；
* `final-3`：菜单开着时灌一段回复 → 画面**逐像素不变**（和 final-2 同 sha256），字被挡在门外；
* `final-4`：关掉菜单 → 那段字自己回到球里（说明是真被"暂存"住，不是丢了）。
