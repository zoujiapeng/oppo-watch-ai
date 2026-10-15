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

手机上的**悬浮液态玻璃水晶球**已经是 **DSH 的前端**：双击球说话 → 本机 DSH 转写+回答 →
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
| `android/src/.../DshHttpTransport.java` | DSH 客户端：token→cookie、session/{list,create,prompt,page}、speech/transcribe、**常驻镜像** |
| `android/src/.../DshWebSocket.java` | 自实现最小 RFC6455 客户端（握手/掩码/分片/ping） |
| `android/src/.../DshApprovalClient.java` | **审批/提问的数据面**：`$events` 瀑布订阅 + 答案回传（审批=字符串、提问=答案对象）+ 调试回路 |
| `android/src/.../SiriAssistant.java` | 状态机 + 打字机 + 镜像回调 + 空闲清空 + 审批流生命周期 |
| `android/src/.../VoiceCapture.java` | 16kHz PCM16 → WAV → base64，50ms 上报 RMS |
| `android/src/.../FloatingBallService.java` | 悬浮球：手势、Siri 形变几何（`measureSiriTargets`）、黑区、球体截图、**审批/提问面板** |
| `android/tools/approval-debug-server.mjs` | 调试回路（喂假审批 / 收球上的点击），端口 = DSH 端口 + 1 |
| `android/docs/probes/dsh-approval-probe.mjs` | 只读探针：订阅 `$events`、打印瀑布请求、可选自动应答 |
| `android/docs/probes/dsh-approval-trigger.mjs` | 造场景：建会话 / 切权限预设 / 发会触发审批的 prompt |
| `settings.html` | 设置页（「DSH 语音助手」区块 + Siri 参数 + **审批球**区块） |
| `android/docs/DSH_ASSISTANT.md` | 完整接入文档（协议、状态表、黑区公式） |

## 已经确定的行为（别改错）

- **手势**：双击=开始说话；再双击=结束并发送；长按=取消；拖动=只拖动；**单击不响应**。
- **球内文字只有两种**：语音识别到的那句话、AI 的回答。没有「聆听中/识别中/思考中」占位字。
- **球缩回最小形态时文字「同一刻」消失**：`measureSiriTargets` 走到「0/1 个字 → 最短形态」那一支时
  会立刻 `hideSiriTextNow()`（`setVisibility(GONE)`，不进布局）—— 只把文字设成空串不够，
  窗口/测量的更新是异步的，球收缩的半秒里旧字还会留在屏上（用户看到的「球小了、字飞出球外」）。
- **点球内文字 = 钉住**：单击文字把它保持住（不再自动清空，文字后带 📌 提示），再单击解除；
  长按文字也是解除；在文字上按住拖动 = 照常拖球。实现是「文字之上盖一个透明触摸层」，
  文字层本身永远 NOT_TOUCHABLE —— 否则文字那块就没法拖球了（见 `setupSiriTouchLayer`）。
  新一轮提问会自动解除钉住。
- **回答结束、球变回原本状态时文字一起消失**：等打字机弹完最后一个字 → 安静 1.5s → 清空并回 IDLE
  （`SiriAssistant.IDLE_CLEAR_MS` + `quietTick`）。
  **注意清空不看 turn/end**：镜像跟的那条会话可能是一轮跑很久的长任务（几十个 step、几分钟不 end），
  只认 turn/end 的话文字会一直挂在球里 —— 现在只要「没新字进来」就自己消散。
- **光效**：只有 THINKING 是流体圆点；LISTENING/TRANSCRIBING 是 Siri 波；
  ANSWERING/IDLE 还原用户自己的波形设定（`FloatingBallService.setAssistantState`）。
  圆点与波**共用同一套坐标框架**（屏幕像素 / `uOrbRadius` * 0.17，x/y 同倍率），
  边缘辉光取「圆点自身亮度 × `uOrbRim`」而不是固定满值；切换时 `uOrbMix` 做 320ms 交叉淡化。
  调这三个参数：设置页「思考中圆点环半径 / 亮度 / 边缘辉光」，
  离线看效果：`android/tools/preview`（不必刷 APK）。
- **黑区下沿按文字实际高度算**，不按行数：
  `tBlack = clamp(uBlack1 - (textBottomNy - waveGap), 0, 1.5)`，
  滑块「文字与 Siri 波的安全距离」= `waveGap`（pref `float_siri_wavegap`，默认 100 → 0.10）。
- **球体大小**：设置页「液态玻璃 · 形状 → 球体大小（整体 scale）」= `master`（20%~200%，1.00 = 原始大小），
  实时生效、随 `float_params` 一起持久化。
  注意：Siri 模式（聆听/思考/回答）下球体几何走 `svHalf*/tMaster`，`master` 不参与 ——
  所以 `minHalfX/minHalfY`（空球基准比例）刻意**不乘** master，否则滑块一动就双重缩放。
- **球高封顶屏高 70%**，封顶后按能放下的行数截断并加省略号；`measureSiriTargets` 还会再校验
  measured 高度、超了就继续减行数（兜底，防文字糊到球外）。
- **球内文字长度封顶 600 字**（`SiriAssistant.BALL_TEXT_MAX`，超了只留最后一段）＋
  镜像缓存封顶 1200 字（`DshHttpTransport.MIRROR_BUF_MAX`）—— 一条长任务的输出几万字，
  不封顶的话 TextView 会撑成一整面墙、文字堵在球外面。
- 文字排版全部走 App 原有设定（字号/距球顶/内边距/圆角），助手只调 `setSiriText`。

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

1. **语音识别没有实时字幕**：DSH 的 `speech/transcribe` 是整段返回，说话时只有波形反馈。
   想边说话边出字，得改用系统 `SpeechRecognizer`（`onPartialResults`），代价是不走 DSH 语音插件了。
2. **麦克风授权**：本机 App 不是设备所有者，首次双击会弹一次系统授权框。
   免弹窗：`adb shell dpm set-device-owner com.glslplayground.app/.AdminReceiver`。
3. **镜像跟随**：跟"最近更新的非子智能体会话"，20 秒轮询切换；想固定某条会话需要另加设置。
4. `android/docs/probes/*.mjs` 是当时跑通的 Node 探针，可复用。
5. 仓库里 `dsh-liquid-glass-siri/` 是早期做的**网页版** DSH 插件（用户最终没要），
   独立功能、可从 profile 移除：`dsh plugin --profile web remove dsh-liquid-glass-siri`。
6. 设置页很长、WebView 不能滚动（实测自动化 swipe 无效），要触发「思考中」只能：
   双击球说话（真机路径），或先把 DSH 区块挪到页首再重装。球体截图在
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
底下 App 收不到 down（滑动半途而废），球还会把这串手势当成用户在操作（双击开听、按住挪球）。

**做法**（详见 [`BALL_TOUCH_GUARD.md`](BALL_TOUCH_GUARD.md)）：

1. 玻璃层 `GLSurfaceView` 改成 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`
   —— **只画不摸**；顺手消掉"窗口里那圈看不见的透明边也在吞触摸"的老坑。
2. 新增**代理触摸窗口**（`setTitle("dsh-ball-proxy")`，`setupBallTouchLayer()`）：贴身包住球
   （+10dp），在玻璃层之后 addView → z 序最低的触摸层，审批面板/文字层/输入行照样压在它上面。
   单击/双击/长按取消/拖动的手感与旧代码同源（同一个 `inSiriBall()` SDF + `moveBallDrag()`）。
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

## 朗读稿清洗（TTS）：把 markdown 转成"给耳朵听的话"

**用户反馈**：朗读"怪怪的"，把空格和一些字符念成星号，想要只读中文和短英文、避开拗口符号。

**根因**：`speak()` 直接把 DSH 的**原始 markdown** 丢给 TTS —— `**加粗**`、反引号、`#`、`-`、`|`
都被念成"星号/井号/竖线"。

**为什么不用另一个模型改写**（用户原本的想法）：那会引入"回答完还得再等一次生成"的延迟、
额外 token 成本，而且模型可能改内容、把事实讲错。**确定性规则零延迟、零成本、不改语义**，
这个场景不值得上模型。

**实现**：`SiriAssistant.toSpeechText()`（纯静态、无副作用），送 TTS 之前过一遍：

- 代码块整段跳过（留一句"（有代码，略过）"）；行内代码去反引号留内容
- 标题/引用/列表/表格/分隔线去标记留文字；链接只留链接文字
- emoji、箭头、进度条等符号一律丢掉；只保留汉字/假名/谚文/拉丁字母数字/空白/常用标点
- 路径与连字符（`-` `/` `:` `@` `.`）替换成空格再合并 —— 直接删会粘连
  （`-r` → `r`、`package/activity` → `packageactivity`），听感更差
- 收尾合并空白、去掉行首残留空格和多余空行

**踩到的两个坑（都值得记住）**：

1. **Java `replaceAll` 的替换串里 `\n` 会被当成"转义字母 n"**，不是换行 ——
   实测「第一行\n第二行」被替换成「第一行n第二行」，念出来多一个字母 n。
   替换串改用 `$0`（整个匹配文本），`$` 开头的按字面处理。
   最小复现：`"a\nb".replaceAll("[ ]*\\n[ ]*", "\\n")` → `"anb"`。
2. **清洗顺序**：表格对齐行（`|---|---|`）必须先清，否则后面的"连字符→空格"先把 `-` 干掉，
   对齐行规则再也匹配不到，听感就是莫名一顿。

**调试口子**：每次朗读都会把"要念的稿子"写到
`/sdcard/Android/data/com.glslplayground.app/files/last-spoken.txt`，
配合 `logcat -s SiriAssistant` 的 `speak(N字): …` 就能核对清洗效果（不用反复听音频）。

**验证方式**：`toSpeechText` 是纯静态函数，从源码里抽出来在本机跑即可，不必刷机：

```
$ java SpeakTest < sample.md
结论
可以 ，但要分两步做：
先跑 adb shell input tap 100 200 看看效果
…
（有代码，略过）
```

## 朗读稿改写：交给大模型（2026-10-15 新增，默认关）

**用户要求**：用大模型把回复改写成适合朗读的稿子；只有开启朗读时才这样，不开朗读完全不影响 DSH；
**用最简单的方式做，反正朗读不需要那么长**。

**实现（`SpeechRewriter`，131 行，刻意最简）**：

- 只在 `speakReplies`（朗读回答）打开、且设置页填了 DeepSeek key 时才发请求；
  否则一律走本地规则清洗（`SiriAssistant.toSpeechText`）；
- **不做任何"值不值得改写"的判断**（第一版写了 `needsRewrite`/`isSane` 两道启发式，
  按用户"最简单"的要求删掉了）；
- 提示词直接要求**输出 100 字以内的口播稿** —— 短了既好念又省钱，比在代码里截断自然；
- 3s 连接 / 5s 读取超时；**任何失败（没 key / 断网 / 超时 / 空返回）都回落规则清洗版**，
  朗读不会因为网络问题停摆；
- 输入裁剪到 1200 字；`max_tokens=300`；
- **不碰 DSH**：不改它的代码、不占它的会话、不读它的凭据；key 存在 App 自己的首选项
  （`watch` prefs 的 `speak_key`），只在开启该功能时用到。

**实测提示词效果**（直接用设备上的 key 打一次 API 验证，未经过 App）：

```
输入：## 结论 / **可以**，但分两步：/ 1. 跑 `adb shell input tap 100 200` / 表格 / 链接 🚀
输出：可以，但得分两步。先跑 adb shell input tap 100 200，再改 settings.html 的开关。本地规则成本是 0。
```

**设置页**（「DSH 语音助手」展开后）：

- 开关「朗读稿交给模型改写（更自然、要联网）」→ `swAsstSpeakModel`
- 输入框「DeepSeek API Key（只存本 App）」→ `asstSpeakKey`（填完即存，输入框清空；
  用 `hasAssistantSpeechKey()` 只判断"有没有"，不回显 key）

**核对方式**：`files/last-spoken.txt`（实际要念的稿子）+ `logcat -s SpeechRewrite SiriAssistant`
（会打 `speak(N字，模型改写)` 或 `speak(N字，规则清洗)`，一眼看出走的是哪条路）。

## 口播稿插件 dsh-plugin-speech（2026-10-15，替代 App 侧 key 方案）

**用户要求**：用 DSH 插件配合，**依赖 DSH、不要第二份 key**。

**做法**：一个 DSH 插件（`~/dsh-plugin-speech`，源码也存仓库 `dsh-plugin-speech/`）——
监听 `session/event` 里的 `assistant/message`，取出正文与 `message.source.{provider,model}`，
用 `ctx.llm.stream()`（官方 `dsh-session-title-llm` 同款用法）生成 ≤100 字口播稿，
原子写到 `/sdcard/Android/data/com.glslplayground.app/files/spoken.txt`。

球侧：朗读前**优先读这个文件**（2 分钟内有效，用掉即删），没有就回落本地规则清洗
—— 所以插件是"可选增强"：不装也能用，装了更自然。App 里那条 DeepSeek key 的路径保留但不再是主路。

**装法（要点，容易踩）**：

1. 插件要 `import "@deepseek-ai/dsh-llm"`，但插件目录解析不到 DSH 的包 →
   在插件下 `node_modules/@deepseek-ai/` 里**软链到 DSH 自己的包**
   （`.../dsh/node_modules/@deepseek-ai/dsh-llm` 与 `dsh-util-values`）；
2. `dsh plugin --profile web add .` **只把它装成普通依赖**（会提示 "declares no dsh.bundle"），
   必须在插件 `package.json` 里声明 `dsh.bundle.patch = ./cordis.patch.yml`，
   并把它**加进 profile 的 `bundles` 数组**，否则不会被加载；
3. 重启 DSH 生效（`pkill -f "dsh/lib/bin.js web"`，看门狗约 30 秒拉起）。

**核对**：`files/spoken.txt` 是否出现、`logcat -s SiriAssistant | grep speak` 里是
「口播稿」（走了插件）还是「规则清洗」（回落）。

**踩坑（第一次真实回合就翻车）**：`session.append(type, data)` 产生的事件是
`{type, seq, time, data}`，**消息体在 `event.data.message`**，不是 `event.message`。
写成后者 → 每次都在 `if (!text) return;` 静默返回：不报错、不写文件，
表现是"插件好像装了但完全没工作"（那一轮球只能走规则清洗）。
现在 `messageOf()` 按 `data.message → message → data` 兜底，并且拿不到 provider/model 时
会打一行日志（不再静默）。

**已验证**：插件可被 DSH 解析加载；用**真实事件结构**（`data.message` + `source`）驱动时，
模型调用与原子写全部走通（`→ provider=… model=… 输入=25字` → `口播稿 8 字已写入`）。

## 口播稿插件：真正的失败原因（2026-10-15 定位完成）

排查链条（每一步都靠"直接落盘"的日志，因为 `ctx.logger` 的输出看不到）：

```
15:26:47 收到 assistant/message 但取不到正文              ← 该回合第 1 条只有 reasoning+tool-call，正常跳过
15:26:56 收到 assistant/message：正文 64 字，deepseek-official/deepseek-flash
15:26:56 开始调用 ctx.llm.stream …
15:26:58 模型返回 chunks=304 blocks=reasoning 文本=0 字     ← ★ 真凶
15:26:58 模型没给文本，跳过（球用规则清洗）
```

**根因**：这个模型默认**带推理**，而改写请求只给了 `maxTokens: 300` ——
模型把额度全花在 reasoning 块上，正文一个字都没输出就被截断，
于是插件永远写不出稿子（球只能走本地规则清洗，所以听感"不像被模型改写过"）。

**改法**：
1. `reasoningEffort: ReasoningEffortId("off")` —— DeepSeek 适配器原生支持
   `off/low/high/max`（在 `dsh-llm-deepseek` 里定义），改写是纯格式化任务，不需要推理；
2. `maxTokens` 300 → 400；
3. 兜底：万一仍然只回 reasoning，**自动用 1200 token 重试一次**。

**顺带记两个排查经验**：
- `ctx.logger.info` 的输出在设备上**看不到**（不知道它去了哪），排查这类插件要
  **直接 `fs.appendFileSync` 到 files/ 下的日志**，否则永远是"静默失败"；
- 一个回合里 `assistant/message` 会来**多条**（每一步一条），
  其中有些只有 `reasoning|tool-call` 没有正文 —— 插件跳过它们是正确行为，不是 bug。

## 插件对 DSH 的影响面（2026-10-15，按"尽可能不影响 DSH"收敛后）

**结论：插件是纯旁路，改不到 DSH 的能力。** 逐条对照：

| 维度 | 情况 |
|---|---|
| 改 DSH 代码 | ❌ 没有。插件是 profile 里的一个 bundle，DSH 源码一行没动 |
| 会话历史 | ❌ 不写。插件只 `ctx.on("session/event")` 读事件，从不 `session.append`，改写稿不进对话上下文 |
| 阻塞主流程 | ❌ 不会。监听是同步返回、改写是 fire-and-forget；每一步 `try/catch`，出错只写自己的日志 |
| 影响回复内容 | ❌ 不会。改写用的是**独立的** LLM 请求（`purpose: "speech-rewrite"`），不是会话的一部分 |
| 额度/限流 | ⚠️ **唯一的真实成本**：每条够长的回复多 1 次小请求 |

**为把那个唯一成本压到最小，做了 4 件事**（都在插件里）：

1. **一回合最多改写 1 次**：`assistant/message` 每步都来一条且正文是累积的，
   不去重的话一条 3 步回复会改写 3 次（实测 64/139/210 字各一次）。
   现在按「会话 + 正文长度」判重，实测 3 步回合只调用 **1 次**；
2. **短回复跳过**：正文 < 50 字（"好的""已完成"）直接用规则清洗，零请求；
3. **额度按正文给**：正文 > 300 字才给 400 token，否则 250；
4. **超时 8s → 5s**：卡住就早点放弃，不占着连接。

**单回合最坏开销**：1 次请求 ≈ 输入 ≤1200 字 + 输出 ≤400 token。
按 deepseek-chat 的价格量级 ≈ 千分之几美分/次；一天几百轮也远低于一次正常对话的消耗。

**如果哪天不想要它**：把 `dsh-plugin-speech` 从
`~/.dsh/profiles/web/package.json` 的 `dsh.profile.bundles` 里删掉、重启 DSH 即可，
App 侧会自动回落本地规则清洗（不需要改 App）。

## 口播稿终于打通：三个真坑（2026-10-15 全部定位）

前三轮一直"听不到改写稿"，靠**直接落盘的两份 trace**（球一份、插件一份）才逐层查清：

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| 1 | 事件形状：正文在 `event.data.message` | 插件取不到正文，静默 return | `messageOf()` 三层兜底 |
| 2 | 模型默认带推理 | `chunks=304 blocks=reasoning 文本=0`，正文被 `max_tokens` 截断 | `reasoningEffort: "off"` + 额度重试 |
| 3 | **`assistant/message` 是流式追加的** | 一回合来很多条（实测 0 → 20 → 26 字），**最后一条很短** → 我的 `MIN_TEXT=50` + 长度判重把它全挡掉，永远不触发 | 改为**按会话累积、`turn/end` 时改写一次**，并去掉最小长度门槛 |

**球的等待时长也必须配套**：插件是在**回合结束**后才发模型请求，
所以 3.5s / 6s 都不够（实测两次都"等满超时"→ 念规则清洗版），
现在默认 **15 秒**（`speak_wait_ms` 可调）。

**两份 trace 是这次能查清的关键**（`logcat` 在这台机器上会被别的服务刷掉）：

- `files/speak-trace.log` —— 球：`speakOnce 进入 N 字` → `开始等插件口播稿` →
  `等到插件稿 N 字（等了 Xms）` / `没等到插件稿（等满 Xms）` → `交给 TTS N 字（插件口播稿/规则清洗）`
- `files/speech-plugin.log` —— 插件：`开始改写：本回合正文 N 字` → `口播稿 N 字已写入`

**经验**：这类"跨进程 + 时序"的问题，别靠听感和 logcat，
直接让两边各自把判断过程写文件，对照时间戳，一次就能定位。

## 删掉 App 侧的 key 路径（2026-10-15，改用插件后就不需要了）

插件方案跑通后，App 自己调 DeepSeek 那条路（设置页的「DeepSeek API Key」输入框 +
`SpeechRewriter` 类 + `speak_model` 开关）就是**多余的第二条路**，全部删除：

- 删 `SpeechRewriter.java`（App 不再自己调模型）
- 删 `speakUseModel()` / `setSpeakUseModel()` / `setSpeechApiKey()` / `hasSpeechApiKey()`
  及 `WatchBridge` 里对应的 4 个 `@JavascriptInterface`
- 删 `settings.html` 里的「朗读稿交给模型改写」开关与 key 输入框
- 启动时**一次性清理**历史遗留的 `speak_key` / `speak_model`（避免留一份无用的敏感数据）

**现在的朗读稿只有两个来源**：

1. **DSH 插件**写的 `spoken.txt`（首选，球最多等 15 秒）
2. **本地规则清洗**（兜底：插件没装/失败/超时）

实测（`speak-trace.log` + `speech-plugin.log` 对照）：

```
插件: 本回合正文 810 字 → 口播稿 68 字已写入（约 1 秒）
球  : 开始等插件口播稿(15000ms) → 等到 68 字（等了 1414ms）→ 交给 TTS 68 字（插件口播稿）
```
