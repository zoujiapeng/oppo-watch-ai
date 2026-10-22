# 交接说明（换窗口续接用）

> 这份是给"下一个会话的自己/别人"看的：现状、关键决定、怎么构建验证、还没做完的事。
> 仓库：`~/oppo-watch-ai`（GitHub `zoujiapeng/oppo-watch-ai`），工作分支 `feat/dsh-liquid-glass-siri`。
>
> 🚚 **要在另一台手机上安装部署**（而不是改代码）→ 直接看
> [`DEPLOY_OTHER_PHONE.md`](DEPLOY_OTHER_PHONE.md)：体检命令、装法、必开开关、
> 全新安装默认值表、踩坑速查表、给 AI 的最短行动清单。
>
> 🖐 **Agent 在操控手机时，注入的点击/滑动会不会点到球上？** → 看
> [`BALL_TOUCH_GUARD.md`](BALL_TOUCH_GUARD.md)：球让路协议（信号文件 + ACK 握手）、
> 代理触摸窗口的窗口结构、SELinux 只能读不能写的两个坑、验证脚本用法。

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
- **球高封顶屏高 55%**（`SIRI_H_RATIO`，2026-10-16 用户要求从 85% 收到 55%），
  封顶后**不再截断**：多出来的内容在玻璃**内部滚动**（见下面「球内富文本」一节）。
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
7. **触摸让路**：文字层要能滚动所以是可触摸的，会挡住下面 App 的手势；
   `applyTextTouchGate()` 在 `agentActing` 为真时给它设 `FLAG_NOT_TOUCHABLE`。

## 球内富文本：WebView + 自写 markdown（2026-10-16 大改，重点看）

**用户要求**：① 方形玻璃高度限制在屏高 55%；② 文字永远被束缚在玻璃里，满了像记事本一样
上下滑；③ AI 回复要和 DSH 网页一样支持表格之类的 markdown；④ 不要打字机效果。

### 结构

| 位置 | 说明 |
|---|---|
| `BallMarkdown.java` | markdown → HTML（表格/标题/列表/引用/代码块/行内样式）＋页面 CSS/JS。纯静态、零依赖 |
| `FloatingBallService.siriText` | 从 `TextView` 换成 **`WebView`**（透明背景，压在玻璃黑区上） |
| `setupSiriTouchLayer` | 原来那张「和文字等大的透明触摸层」改成**球顶一条抓取条**（文字区要能滚） |

几何：宽度固定 85% 屏宽、高度 = 内容高度（封顶 55% 屏高），
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

## Agent 触摸门控（2026-10-15 新增）

**问题**：DSH 的 mobile 工具注入触摸走 `/system/bin/input`，和真手指走**同一套窗口命中测试**，
`MotionEvent` 里没有"注入"标记。球常驻屏幕中间又可触摸，于是注入的 `ACTION_DOWN` 被球吃掉 →
底下 App 收不到 down（滑动半途而废），球还会把这串手势当成用户在操作（按住挪球）。

**做法**（详见 [`BALL_TOUCH_GUARD.md`](BALL_TOUCH_GUARD.md)）：

1. 玻璃层 `GLSurfaceView` 改成 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`
   —— **只画不摸**；顺手消掉"窗口里那圈看不见的透明边也在吞触摸"的老坑。
2. 新增**代理触摸窗口**（`setTitle("dsh-ball-proxy")`，`setupBallTouchLayer()`）：贴身包住球
   （+10dp），在玻璃层之后 addView → z 序最低的触摸层，审批面板/文字层/输入行照样压在它上面。
   单击/长按取消/拖动的手感与旧代码同源（同一个 `inSiriBall()` SDF + `moveBallDrag()`）。
3. **agent 门控** `pumpAgentGate()`（60ms 一次）：DSH 要发 click/swipe/key 之前**原子写**一个
   唯一 nonce 到 `<GATEDIR>/dsh-ball-act`；球读到**内容变了**就从**本地时钟**起算 1500ms 让路窗口
   （此时代理窗口也设 NOT_TOUCHABLE），并回写 `<GATEDIR>/dsh-ball-ack`；DSH 侧读到 ACK 才真正注入。
4. 两个必须记住的坑（都是实测翻车后改的）：
   - **不能靠 mtime 判新鲜**：`/sdcard` 是 FUSE，球看到的 mtime 早 ~700ms，1.5s 窗口缩成 740ms；
   - **信号必须原子写**（`.tmp` + rename）：球 60ms 读一次，直接 `>` 重定向会读到半个 nonce，
     ACK 永远对不上（表现为"偶尔让路"）。
5. `GATEDIR = /sdcard/Android/data/com.glslplayground.app/files` —— **不能**放
   `/data/local/tmp`（`shell_data_file` 标签，`untrusted_app` 只能读不能写，写 ACK 报 EACCES；
   0777 子目录也一样被拒）。

DSH 侧对应两处：`~/dsh-preset-mobile-use/lib/index.js` 的 `waitForBallStandDown()`
（**升级会覆盖，需重打**；备份 `patches/preset-mobile-use/index.js.patched`），
和 Magisk 模块里的 `/system/bin/vd`（tap/swipe/key 插 `ball_signal`，
用 `patches/apply-vd-ball-guard-patch.sh` 幂等应用，**重启生效**）。

验证（两个都不需要重启 DSH）：

```bash
bash ~/oppo-watch-ai/android/tools/ball-guard-verify.sh          # 球侧：信号→notTouchable+ACK→恢复
node ~/oppo-watch-ai/android/tools/ball-guard-plugin-e2e.mjs key # 插件侧贯通（切真实插件代码跑）
python3 ~/oppo-watch-ai/android/tools/ball-proxy-state.py        # 单看代理层状态
```

实测：`touchable 710x497 tok=d301016` → 信号后 `notTouchable 719x499` + `ACK 匹配` → 1.5s 后恢复。

**真机透传已验**（2026-10-15）：便签涂鸦里从球上方贯穿到球下方画竖线，
像素比对确认球覆盖区无断口；同轮确认在球上拖动仍跟手（y 989→603）。
注意：球**可见范围内**的点击归球（球压在按钮上时那次点击进不了 App）——先把球挪开即可。

**真实 agent 路径已验通**（2026-10-15，DSH 重启后）：用 mobile 工具点击**落在球里**的便签
→ 球先 `DISARM`（让路 1500ms）并回 ACK → **便签详情页正常打开**，球没被挪动。
对照：同一位置在旧插件（无握手）下点击会被球吃掉、界面无反应。

⚠️ 期间修掉一个我自己引入的坑：球"启动后首读只登记不让路"的守卫，会把**同序列的真信号**
当成遗留物丢掉（球重启后第一次 agent 点击必失败）。现在改成"只有文件 mtime 超过 8 秒
且本次启动没见过任何信号"才算遗留。

**还没做的**：DSH 再重启一次可让插件带上"握手成功后删信号文件"的清理（非必需，球侧已有 8 秒豁免兜底）。

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

代理触摸窗口（`dsh-ball-proxy`）**故意保留** `FLAG_NOT_TOUCHABLE`：它是全透明的，
球让路时被压成 0.8 也看不见（logcat 里那条警告现在只属于它）。

### 验证（改完必须逐条过）

```bash
dumpsys window windows | grep -A2 "Window .*glslplayground"   # 玻璃窗 mAttrs 里**不再有** alpha=0.8
dumpsys input | grep glslplayground                           # 玻璃窗 alpha=1.00 + touchableRegion=<empty>
logcat -d | grep "setting alpha to 0.80" | tail                # 只剩代理窗口（透明，无害）
# 让路窗口内再量一次：球 alpha 仍 1.00（说明让路协议没把球重新变透明）
printf x > $D/.tmp-act && mv $D/.tmp-act $D/dsh-ball-act
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

### 已知遗留（本次没动）

- Siri 文字上的**点击层**（`siriTouch`）在"球让路"时不会跟着变 NOT_TOUCHABLE，
  所以球里有文字时，agent 注入到那条文字带上的触摸会被它吃掉（`applyBallTouchability()`
  只管代理窗口）。要修就把它一起纳入让路逻辑。

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

### 1. 几何：宽固定 85% 屏宽，高随文字往下长（不再是正方形）

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
| `ZoneLayerShader.java` | 折射换成**球那条曲线**：`amp = circleMap(1 + sd/band) * refract`（边缘最强、往里按 band 衰减），色散按 `lp.x*lp.y/(hx*hy)` 加权；黑顶 `uCap0→uCap1` 渐隐；球顶两团柔光；底缘亮弧 + 赤道线 + 壳边；`uFrost` 把背景换成磨砂（面板 0.82 / 圆区 0.55） |
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
