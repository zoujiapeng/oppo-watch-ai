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
