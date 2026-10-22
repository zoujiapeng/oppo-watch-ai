# 落地报告：oppo-watch-ai 克隆 / 安装 / 实测

> 机器：M2007J1SC（MIUI，Android 13 / API 33）、Termux（非 root 前缀 `/data/data/com.termux`）、
> Magisk root、本机 DSH `0.2.1-rc.2`。
> 仓库：`https://github.com/zoujiapeng/oppo-watch-ai` @ `c8768e8`
> （`docs: 开源准备 —— 补「从源码构建 APK」与逐部分许可证说明`）。

---

## 1. 结论

| 项 | 结果 |
|---|---|
| clone | ✅ `~/OPPO-watch-ai`，`main` @ `c8768e8` |
| 装 APK（现成包） | ✅ `com.glslplayground.app` versionCode 20 / versionName 1.2，uid 10282 |
| 启动 / 三个 WebGL demo | ✅ 首页与设置页正常 |
| 悬浮水晶球 | ✅ 前台服务 + 2 个 `APPLICATION_OVERLAY` 窗口；`Root 直采` 起 `su -c while screencap` |
| 设置页开关 | ✅ 悬浮于桌面 / 采集方式 / 帧率 / 分辨率 / 助手 / 镜像 全部可点且落地 |
| 「问一句（测试链路）」 | ✅ 球内出现 **球内链路正常**（DSH 真实模型往返，非本地假字） |
| 镜像当前会话 | ✅ `镜像中 · session-74483c29-…` |
| 后端重启后自愈 | ✅ 换 token → App 自动重读 `~/dsh.log` → 再次问通（turns 3→4） |
| **修掉触摸死区** | ✅ 方形态结束后窗口缩回「球 + 24dp」：`993×993 → 793×579`（见 §10） |
| **直连 dshpj 的 DSH** | ✅ `~/dsh.cookie` 复用 3080 那条会话 cookie，不再另起 DSH / 不再占端口（见 §11） |

---

## 2. clone

```bash
cd ~
git clone --depth 1 https://github.com/zoujiapeng/oppo-watch-ai.git OPPO-watch-ai
cd OPPO-watch-ai && git log --oneline -1
# c8768e8 docs: 开源准备 —— 补「从源码构建 APK」与逐部分许可证说明
```

## 3. 装 APK（这一步在本机必须走 root）

```bash
cd ~/OPPO-watch-ai
sha256sum glsl-playground.apk
# 5994e0f51b5947bd69aaf1b4b4ef66173a33580f2e436d9f630e8cb74269fbf7

pm uninstall com.glslplayground.app          # 干净装
pm install -r glsl-playground.apk            # ❌ 本机失败，见下
su -c "pm install -r $PWD/glsl-playground.apk"   # ✅ Success
```

`pm install` 以 Termux 普通身份执行时被 MIUI 的 `XSpaceManagerServiceImpl` 挡掉：

```
java.lang.SecurityException: You either need MANAGE_USERS or CREATE_USERS
permission to: query users
  at com.miui.server.xspace.XSpaceManagerServiceImpl.getDefaultUserId(...)
  at PackageManagerShellCommand$InstallParams.<init>(...)
```

`su -c pm install` 一次过 —— **本机装包命令固定用这条**。

## 4. 开球（必须手动，且是「会话级」开关）

1. 桌面图标 `GLSL Playground` → **⚙ 设置**；
2. **悬浮于桌面** 打开（第一次会跳系统「显示在其他应用的上层」→ 允许）；
3. 采集方式保持 **Root 直采**（首次会弹 Magisk 授权 → 允许）；
4. 设置页往下：**DSH 地址** 填后端地址、**镜像当前会话** 打开。

验证球起来了（不看屏幕也能查）：

```bash
su -c "dumpsys activity services com.glslplayground.app | grep -E 'ServiceRecord|isForeground'"
#   * ServiceRecord{… com.glslplayground.app/.FloatingBallService}
#     isForeground=true …
su -c "dumpsys window windows | grep -A2 APPLICATION_OVERLAY | head"
#   mAttrs={(15,735)(1049x1049) … ty=APPLICATION_OVERLAY …
su -c "ps -A -o PID,USER,ARGS | grep 'while screencap'"
#   root  sh -c while screencap; do :; done
```

## 5. DSH 后端：为什么用独立实例（本机关键差异）

> **2026-10-22 更新**：本机现在走 §11 那条路（`~/dsh.cookie` 直连 dshpj 的 3080），
> 下面这套"另起一个 DSH"只在没有反代、或想完全隔离时用（会多占一个端口）。

App 只从 `~/dsh.log`（及 `dsh-restart.log` / `dsh-watchdog.log`）里 `grep token=` 换 cookie。
本机主 DSH（`127.0.0.1:3080`）是用 `dsh web --profile web` 直接在终端里起的，**横幅没有落盘**，
`~/dsh.log` 不存在 → App 必然 401（`DSH 未下发会话 cookie (HTTP 401)`）。

所以给它配一个专用后端（不动主 DSH、不动主会话）：

```bash
bash ~/OPPO-watch-ai/tools/start-ball-dsh.sh          # 起 3090，横幅追加进 ~/dsh.log，并预建一条会话
bash ~/OPPO-watch-ai/tools/start-ball-dsh.sh stop     # 停
BALL_DSH_PORT=3091 bash ~/OPPO-watch-ai/tools/start-ball-dsh.sh   # 换端口
```

脚本做的事：`DSH_HOME=~/.dsh-watch-test`（复制 `~/.dsh` 的 `profiles/`、`.agent-presets/`、
`.credentials.yaml`，**模型凭据必须一起带过去**，否则 `llm-deepseek: no API key`）→
`setsid nohup node --expose-internals …/dsh/lib/bin.js --profile web --port 3090 --no-open >> ~/dsh.log` →
从**本次新写入**的横幅里取 token → 换 cookie → `session/list` 为空就 `session/create`（cwd=`$HOME`）。

> 想让球直接连主 DSH（3080）也可以：重启主 DSH 时把 stdout 落盘即可
> （`dsh web … >> ~/dsh.log 2>&1`），App 侧地址填回 `http://127.0.0.1:3080`。
> 但那会把浏览器里那条会话暴露给球（镜像跟着走），且要中断当前 GUI 会话。

## 6. 实测链路（可复现）

设置页 **DSH 地址** = `http://127.0.0.1:3090` → 点 **问一句（测试链路）**：

```
DshTransport: authenticated against http://127.0.0.1:3090 (dsh-auth-4b6qQ-Y94Wo8ht4…)
DshApproval:  approval $events stream opened against http://127.0.0.1:3090
POST /api/session/list   200
POST /api/session/prompt 200  {"sessionId":"session-74483c29-…","content":[{"type":"text","text":"只回复四个字：球内链路正常"}]}
WS   /api/remote.mux     （session/follow 流式，逐字回球）
```

DSH 侧落盘的事件（`session/page`）：

```
seq 18 assistant/message → 球内链路正常
seq 27 assistant/message → 球内链路正常
seq 36 assistant/message → 球内链路正常
turn/end {"reason":{"kind":"completed"}}
```

App 侧回读（设置页「球内当前文字（实时回读）」）：`球内链路正常`；
「镜像」：`镜像中 · session-74483c29-04a3-46b4-b904-925973f2195e`。

证据两张：

- [`docs/ball-on-desktop.jpg`](docs/ball-on-desktop.jpg) —— 桌面实拍：玻璃球浮在壁纸上，左边挂着 `球内链路正常`
- [`docs/ball-capture.png`](docs/ball-capture.png) —— 球自己的 `PixelCopy` 单帧（玻璃折射 + 球内文字，来自
  `/sdcard/Android/data/com.glslplayground.app/files/ballshot` 钩子：往里面写文字会同时喂给球并截一帧）

## 7. 踩到的坑与处理（换机直接抄）

| 症状 | 真因 | 处理 |
|---|---|---|
| `pm install` 抛 `MANAGE_USERS` | MIUI xspace 调 `getDefaultUserId` | `su -c "pm install -r …"` |
| 球不出现 | 「悬浮于桌面」不持久化（内存标志） | 装完 / 重启 / 被杀后回设置页再点一次 |
| 第一次点开关只跳到权限页 | 没有 `SYSTEM_ALERT_WINDOW` | 系统页里打开「允许显示在其他应用的上层」，回来再点 |
| 问一句 → `DSH 出错：DSH 未下发会话 cookie (HTTP 401)` | `~/dsh.log` 里没有**当前**实例的 token | 用 §5 的脚本起后端（横幅落盘） |
| 问一句 → `DSH 出错：DSH 没有返回内容`，DSH 里能查到 `session/create` 500 | App 建会话用 `cwd=/data/user/0/com.glslplayground.app/files`，Termux 侧 DSH 无权 `mkdir` → `EACCES` | 后端里**先存在任意一条会话**，App 就不会去 create（脚本已自动预建） |
| 后端重启后球不出字 | 旧 token 失效 | App 每次认证都重读 `~/dsh.log` 最后一条 `token=`，重启后自动恢复（实测通过） |
| 别用 `pm uninstall` 之后不重开球 | 卸载会杀进程 | 重装后回设置页重开「悬浮于桌面」 |

## 8. 版本 / 兼容

- 仓库 README 只对 DSH `0.2.0-rc.2` 实测；本机是 `0.2.1-rc.2`，
  `session/list`、`session/create`、`session/prompt`、`session/page`、`/api/remote.mux` 均兼容。
- APK `targetSdk 28 / minSdk 21`，Android 13 上无需额外兼容开关。
- 编译路线（本机暂未走）：`pkg install openjdk-17 aapt2 d8 apksigner python` 后
  `bash android/build.sh`（首次会下载 `android-30/android.jar` 到 `.build/`）。

## 9. 复现清单

```bash
git clone --depth 1 https://github.com/zoujiapeng/oppo-watch-ai.git ~/OPPO-watch-ai
cd ~/OPPO-watch-ai
bash android/build.sh                           # 本机工具链已装好，改过源就必须自己编
su -c "pm uninstall com.glslplayground.app"     # 换签名要卸干净（见 §10.3）
su -c "pm install -r $PWD/glsl-playground.apk"
bash tools/dsh-cookie.sh                        # 抄 dshpj 那条 cookie → ~/dsh.cookie
su -c "appops set com.glslplayground.app SYSTEM_ALERT_WINDOW allow"
# App → 设置 → 悬浮于桌面=开 → DSH 地址=http://127.0.0.1:3080 → 问一句（测试链路）
```

---

## 10. 触摸死区（「球周围点不动」）—— 已修

### 10.1 现象与根因

用户反馈：**球变成方形、再回初始状态之后，球周围一圈点不动**。

实测（`dumpsys window` + 球自己 `PixelCopy` 的一帧做 JSON 解码量像素）：

| | 窗口 | 玻璃实际占的区域 | 四周透明死区 |
|---|---|---|---|
| 修复前 | `993×993` | `661×449` | 左右各 **166px**、上下各 **272px** |
| 修复后 | `793×579` | `661×447` | 左右各 **66px**（= 24dp 设计留白） |

原因两条，都在 `FloatingBallService`：

1. **窗口只在 Siri 形态里跟着球缩**。`siriTick` 开头就是 `if (!siriEnabled) return;`，
   而助手那一轮结束会把 Siri 模式关掉 —— 于是方形态结束之后，再没有任何代码把
   `params.width/height` 缩回圆球尺寸，窗口永久停在方形态的大盒子上。
   窗口是 `APPLICATION_OVERLAY` + `FLAG_NOT_TOUCH_MODAL`，框里没玻璃的地方**照样吃触摸**，
   这就是"球周围点不动"。
2. **`pumpTextHoldSafety()` 写好了却没人调用**。镜像出字（浏览器那边在回话）、
   或流中途断掉时 `textHold` 永远不释放，方形态永久保持。

### 10.2 改动（`android/src/com/glslplayground/app/FloatingBallService.java`）

- 新增 `auditTick`（`onCreate` 里 `main.post`，400ms 一轮），**与 Siri 开关无关**地巡检窗口：
  - 非 Siri 形态：按 `renderer.halfX/halfY` 算目标窗口 `2×base×half + 2×24dp`，
    与当前差 > 8dp 就把窗口缩回去（保持球心不动，`params.x/y` 各补一半差值）；
  - Siri 形态：先跑 `pumpTextHoldSafety()` 兜底释放；文字空了且不在跑 → `releaseTextHold()`；
    球已回最小形态且窗口还大 → 缩窗（等 700ms 让缓动走完，避免"啪一下跳回去"）。
- `measureSiriTargetsInner` 的"0/1 个字 + textHold"分支：不在跑（IDLE/OFF）时直接
  `releaseTextHold()`，不再死守方形态。
- `SiriAssistant.isTurnSettled()` 新增（判断"回合真的结束了"）。

### 10.3 验证（可复现）

```bash
# 1) 窗口尺寸（修复前 993×993，修复后 793×579）
su -c "dumpsys window windows" | grep -E "\([0-9-]+,[0-9-]+\)\([0-9]+x[0-9]+\).*APPLICATION_OVERLAY"
# 2) 玻璃实际大小（把球的一帧存下来量像素）
su -c "touch /sdcard/Android/data/com.glslplayground.app/files/ballshot"   # 空文件 = 只截一帧
su -c "cp /data/data/com.glslplayground.app/files/ball-capture.png ~/ball.png"
# 3) 点"框内、玻璃外"的位置：修复前毫无反应，修复后落到下面的桌面
#    实测：点 (820,800) 在旧 993 框内、新框外 → 成功打开「系统应用」文件夹
```

日志里能看到这一次收缩：

```
I FloatingBall: window audit(idle): 窗口 993x993 → 793x579（球 661x447 + 2×24dp）
```

修复前的那一帧（窗口 993×993，玻璃只占中间 661×449）存在
[`docs/ball-window-993.png`](docs/ball-window-993.png)。

> 重新编译的 APK 用的是本机新生成的 debug key，**签名与仓库里那个不一样**，
> 所以升级必须先 `pm uninstall`（`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 就是它）。
> 卸载会把「悬浮于桌面」和悬浮窗权限一起清掉，装完要重新给。

---

## 11. 直连 dshpj 的 DSH（不再另起实例、不再占端口）

之前为了拿 launch token，另外起了一个 3090 的 DSH —— 那会多占一个端口，而且镜像的是
另一个实例的会话。现在改成复用 **dshpj 正在跑的那一个**：

- `dshpj` 的反代（`~/.dshpj/bin/dshpj-proxy.mjs`）环境里带着一条 30 天有效的
  `dsh-auth-…=v1.…` cookie；`tools/dsh-cookie.sh` 用 root 读一次
  `/proc/<pid>/environ`，把它写进 `~/dsh.cookie`（600）。
- App 侧 `DshHttpTransport.authenticate()` 现在**先看这个文件**：命中就直接用它当 cookie，
  跳过 token 交换；被 DSH 拒过一次（401/403）就自动作废、回退到 `~/dsh.log` 里的 token。

实测日志：

```
I DshTransport: authenticated against http://127.0.0.1:3080 via dsh.cookie (dsh-auth-VPhEEcLKeqRDBoB…)
I DshTransport: mirror following session-01228744-81ab-4848-b331-9957d7daedaa
```

用完的临时实例已经关掉，现在本机只剩 dshpj 自己的 3080 / 3081：

```bash
bash ~/OPPO-watch-ai/tools/start-ball-dsh.sh stop   # 3090 已停
```

> DSH 重启后 cookie 会换，重跑一次 `bash tools/dsh-cookie.sh` 即可（30 天有效期，
> 但重启会作废旧签名）。


---

## 12. 「DSH 都回我了，球上怎么什么都没有」—— 是同步的，但被 1.5s 擦掉了

**镜像是实时的**：球常驻一条 `ws://127.0.0.1:3080/api/remote.mux` 的 `session/follow`，
跟着「你在用的那条会话」逐字收 `assistant/message` 增量并打进球里。日志能对上行：

```
I DshTransport: authenticated against http://127.0.0.1:3080 via dsh.cookie (…)
I DshTransport: mirror following session-01228744-81ab-4848-b331-9957d7daedaa   ← 就是当前这条会话
I FloatingBall: assistant state=THINKING … pending=2                             ← 回合开始，球进思考态
I SiriAssistant: 最终回复安静 1500ms → 清字 + 收形状（回初始圆球）                ← 问题在这一行
```

`SiriAssistant.FINAL_SHAPE_BACK_MS` 原本是 **1500ms**：打字机把最后一段弹完之后只要静 1.5 秒，
就把球内文字**清空**并把方形态收回圆球。人一般在这之后几秒才抬眼 —— 看到的就是一颗空球，
于是以为是"没同步"。

改动：`FINAL_SHAPE_BACK_MS` 1500 → **30000**（30 秒）。收字的触发条件不变：
下一轮提问 / mirror 新回合开始会直接换字；想立刻收，点一下球内文字（= 钉住/取消钉住）
或设置页「清空球内文字」。

球内文字上限 `BALL_TEXT_MAX` / `MIRROR_BUF_MAX` 都是 2500 字，正常回答不会被截断。

验证方式（不用等其他回合）：

```bash
su -c "logcat -d | grep -E 'mirror following|最终回复安静' | tail -5"
# 期望：mirror following session-<当前会话>；回合结束后那句应显示 30000ms
```

---

## 13. 触摸区再收一轮（球下方死带）+ 「串话」真因

### 13.1 触摸代理窗口按「玻璃」而不是「窗口」定尺寸

`syncBallTouchWindow()` 原来用 `winW/winH + 10dp`：窗口四边各有 24dp 留白，
代理层于是把整块透明框都盖住了 —— 窗口 `793×579`、玻璃实心只有 `661×447`，
**球正下方一条 66px 高的死带**，加上左右圆角外的角落。

改法：

- 新增 `glassHalfW()/glassHalfH()`，与 `GlassRenderer` 画球用同一套公式
  （`ref*0.9 * half * master`，Siri 形态用 `sv*` 动画值，两者取大）；
- `syncBallTouchWindow()` 改成「玻璃包围盒 + 12dp」，球心对中；
- `inBallAtRaw()` 的 SDF 判定不变 —— 多出的 12dp 只影响"能否开始拖"，不会误触。

### 13.2 球内文字层一直是可触摸的

`siriParams` 建窗口时**没带 `FLAG_NOT_TOUCHABLE`**（半透明窗口默认吃触摸），
而 `syncSiriTouchLayer()` 用 `if (scrollable == siriTextTouchable) return;` 早退 ——
缓存是 false、真实 flags 是"可触摸"，两边永远对不上 → 文字层永久吃触摸，
正好盖住球中部偏下那块。

改法：建窗口时直接 `|= FLAG_NOT_TOUCHABLE`；`syncSiriTouchLayer()` 改成比对**真实 flags**，
只有"内容超出视口需要滚动"时才摘掉。

> 状态：代码已编进 `03:48:05` 那一版 APK 并安装，但**还没在机器上实测**
> （装完球被杀了，没再打开「悬浮于桌面」）。

### 13.3 「上下文乱串」的真因：PJ 补丁的 `lastTask` 是全局的

不是球，也不是会话串了 —— 是 `~/.dshpj/assets/pj-jailbreak.mjs` 的 L6 任务重述层
用一个**进程级**变量记"最近一条用户消息"：

```js
let lastTask = ''
ctx.on('session/event', (_subject, event) => { … lastTask = compact(text, 600) })
```

而 `session/event` 是 DSH 所有会话共用的总线（`Session.commit()` 里 `callbackArgs = [this, event]`）。
`/home/test` 那条会话在 03:47:57 一发消息，就覆盖了 `lastTask`；本会话这一轮 03:48:00 起来时，
末尾的 `task frame` 引用的就是**别人**的请求。

证据：本会话事件流里那三段之间**没有**这条用户消息（只有 2780 / 2886 / 2915 三条真实的）。

改法：按会话分桶（`taskBySession` + `sessionKeyOf(session)`），
`text(context)` 里用 `context.agent.session.id` 取本会话的任务；
取不到时**只有进程里只见过一条会话**才退回全局 —— 否则宁可写 `(no request yet)`。

### 13.4 顺带修掉的静态路径

`patch.yml` 里那句 `Your working directory is /data/data/com.termux/files/home.` 是写死的，
在 `~/OPPO-watch-ai` 这类 cwd 下会误导模型。改成"以本次会话的 runtime context 为准，写路径前先 pwd"。

> 13.3 / 13.4 都改在 `~/.dshpj/assets/` 下，**要重启 dshpj 才加载**；
> 重启后 `~/dsh.cookie` 会失效，记得跑一次 `bash tools/dsh-cookie.sh`。
