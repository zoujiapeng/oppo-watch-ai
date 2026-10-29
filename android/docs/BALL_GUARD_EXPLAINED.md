# 防 AI 误触是怎么做出来的（原理 · 代码位置 · 数字 · 证据）

> 面向"想改它 / 想审它 / 换台手机重装"的人。所有结论都能在仓库里指到行号，
> 所有"实测"都注明日期与机型，没跑过的步骤会写明**未执行**。

---

## 0. 一句话

**不是把球画得更透明，而是让球在 agent 动手的那一瞬间，把它拥有的每一个输入窗口
一起从「窗口命中测试」里摘出去；摘之前先握手，摘完再放回。**

三个部件缺一不可：

| # | 部件 | 在哪 | 作用 |
|---|---|---|---|
| ① | 分层窗口 | `FloatingBallService.java` | 玻璃只画不摸；球上只有一个"贴身代理窗口"接用户手势 |
| ② | 让路协议 | 球侧 `pumpAgentGate()` + `gateCommand()`，DSH 侧 `waitForBallStandDown()` | 注入前握手：YIELD → ACK → 注入 → RELEASE |
| ③ | 一处收口 | `applyAgentYield(boolean)` | 球的**所有**窗口在同一时刻退出命中测试，新增窗口必须在这里登记 |

---

## 1. 为什么"防误触"不能靠球自己判断

DSH 注入触摸的链路（`dsh-preset-mobile-use` → `POST /api/action` → `vd_server`
→ `/system/bin/input` → `InputManagerService` → `InputDispatcher` → 窗口命中测试）：

```
模型 → mobile 工具 → vd_server → input(uid=shell, INJECT_EVENTS)
     → InputDispatcher → 命中测试 → 目标窗口
```

关键事实：**注入事件和真手指走同一套命中测试**。App 层拿到的 `MotionEvent` 里没有
一个可用的"这是注入的"标记。所以球**无法**自己分辨"这一下是用户还是 agent"。
任何"看起来像注入就忽略"的启发式（速度、坐标整数化、时间间隔）都会误伤真手指。

结论：必须有**外部信号**。信号由知道"我要注入触摸了"的那一方（DSH 插件）发出，
球只负责"收到信号 → 退出命中测试 → 回执"。

---

## 2. 第一层：窗口结构 —— 谁接触摸，谁只画画

球不是一个窗口，是一堆 `TYPE_APPLICATION_OVERLAY` 窗口叠出来的。历史上有 10+ 个，
它们全都可触摸时，agent 的 `ACTION_DOWN` 落到任何一个上都会被吃掉（底下 App 连 down
都收不到，滑动半途而废），更糟的是球会把它当成用户在操作（按住=挪球、双指长按=搜索层）。

### 2.1 玻璃层：只画不摸，但**不加** `FLAG_NOT_TOUCHABLE`

`makeTouchTransparent(View)`（`FloatingBallService.java:1873`）反射挂
`ViewTreeObserver.OnComputeInternalInsetsListener`，把
`InternalInsetsInfo.touchableRegion` 设为空：

```java
setTouchableInsets.invoke(a[0], TOUCHABLE_INSETS_REGION); // 常量 = 3
((android.graphics.Region) regionField.get(a[0])).setEmpty();
```

为什么不用更简单的 `FLAG_NOT_TOUCHABLE`：一加/OPPO 的 WindowManager 会把这个 flag
和"系统悬浮窗"组合起来把整窗 `alpha` 钉在 **0.80**，而且摘掉 flag 不一定还原
（2026-10-16 那次"球变半透明贴纸"）。空可触摸区域同样让输入系统命中不到，
但窗口保持 `alpha=1.0`。

这套 API 在公开 SDK 里是 `@hide` 的（`android.jar` 里没有），所以只能反射 + 动态代理；
失败只打日志，窗口退回可触摸 —— 此时还有代理窗口在上面接手势。

### 2.2 代理窗口 `dsh-ball-proxy`：唯一接用户手势的窗口

- 尺寸**贴着玻璃真实像素包围盒**，外扩 12dp（`syncBallTouchWindow()`，:3053）。
  早期按"球的最小形态"固定尺寸，Siri 球宽动画到 ~1040px 时两侧出现死区 → "某一笔凭空消失"。
- 数值没变就不 `updateViewLayout`（:3073）—— 这个项目被"每帧改窗口"咬过两次。
- 只带 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS`，不抢输入法焦点。
- 用 `setTitle("dsh-ball-proxy")` 命名 → `dumpsys` 里可按名字认窗口，验证脚本不靠猜。
- `inBallAtRaw()`（:3111）把屏幕坐标换算到玻璃窗口本地坐标，再用与渲染同源的圆角矩形 SDF
  判"这一下是不是按在球上"。

窗口 z 序（低 → 高）：

```
GLSurfaceView 玻璃层   fl=NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_NO_LIMITS  区域=<empty>  只画
代理窗口 dsh-ball-proxy 贴身包球 +12dp                                  唯一接用户手势
文字触摸层 / 审批层 …   按需出现，各自可触摸（让路时也要让）
```

---

## 3. 第二层：让路协议 —— 注入前先打招呼

### 3.1 主通道：回环 TCP（2026-10-24 起）

球在 `startGateTcpServer()`（:3502）里绑 `127.0.0.1:3181`（被占顺延 3182/3183），
daemon 线程 `dsh-ball-gate`，一行命令一行回复：

| 命令 | 回复 | 语义 |
|---|---|---|
| `PING` | `ALIVE <pid> <0\|1> <port>` | 探活；`1` = 正在让路 |
| `STATUS` | `STATE <0\|1> <untilMs> yield=<0\|1>` | 调试/体检用 |
| `YIELD <nonce> [holdMs]` | `OK <nonce>` 或 `!skip:<nonce>` | 让路（默认 4000ms，最短 300ms） |
| `RELEASE <nonce>` | `RELEASED <nonce>` / `IGNORED <nonce>` | 动作发完立刻恢复 |

`YIELD` 的处理（:3593）：写 `lastTcpNonce`，若开关关着直接回 `!skip:`；否则
`gateUntilMs = now + hold`，然后 `main.post(agentGateKick)` **立刻**在主线程跑一次门控
—— 不等 60ms 轮询。`RELEASE` 的 nonce 对不上就 `IGNORED`（防止两个动作互相放行）。

为什么 TCP 是主通道：不碰文件系统 → 不受 Android 11+ `/sdcard/Android/data` 的属主限制、
不受挂载命名空间、不受 SELinux 文件标签影响；顺手消掉 FUSE mtime 缓存和"读到半个 nonce"
两个老坑（一行一条，天然原子）。两边只要 INTERNET 权限。

### 3.2 兜底：文件信号 + 心跳

- 信号目录 `gateDirs()`（:3457）= 外部私有 `getExternalFilesDir(null)` **+** 内部私有
  `getFilesDir()`，两个都写；老路径 `/data/local/tmp` 只读（兼容老 vd 补丁）。
- 心跳 `dsh-ball-alive` / `dsh-ball-gate`，每 2s（`ALIVE_EVERY_MS`），JSON：
  `{"ts","pid","port","tcp","yield","touch","dirs"}`。插件据此拿**真实端口**和让位开关状态。
- 心跳写在 `ballTouch == null` 判空**之前**（:2722）—— 老版本第一行 `if (wm == null ||
  ballTouch == null) return;` 会把心跳一起跳过，代理窗口一旦没加成功，插件就永远认为
  "球没在跑"。这是"换机不让路"的第二条静默死路。
- 文件协议：写 `dsh-ball-act`（内容 = nonce）→ 球回 `dsh-ball-ack`（同一 nonce）。
  **两端都原子写**（`.tmp-<pid>` + `rename`）：球每 60ms 读一次，`>` 重定向先截断再写，
  球容易读到半个 nonce → ACK 永远对不上（实测 3 次坏 1 次）。

### 3.3 DSH 侧：`waitForBallStandDown()`

`patches/preset-mobile-use/index.js.patched:345`（= 线上 `~/dsh-preset-mobile-use/lib/index.js`）：

```
nonce = `${Date.now()}-${pid}-${++seq}-${action}`     // 同毫秒连续调用也不撞
① TCP：tcpPing() 逐个试 3181/3182/3183（各 200ms）→ YIELD（等 400ms）
       回 OK → via=tcp:<port>，直接注入
       回 !skip → skipped=yield-disabled，直接注入（不白等）
② 文件/su：readHeartbeat() 6 个候选目录（直连 → su -c 中继），心跳 >8s 判死
       写 ACT（直连 → su 中继，base64 过管道）→ 20ms 轮询读 ACK，上限 500ms
③ 全断：ok=false + 逐条写明哪条通道怎么失败
```

调用点（:653）：`default:` 分支里 **注入前** 调，动作 `POST /api/action` 之后
**无论成功失败**都 `releaseBallStandDown(nonce)`（:664 / :682）。

### 3.4 一次 click 的完整时间轴（本机实测，2026-10-24）

```
插件                              球（60ms 轮询 + TCP 线程）
── tcpPing → ALIVE ──────────────▶
── YIELD 1792…-14-click ─────────▶  gateUntilMs = now+4000
                                   main.post(agentGateKick) → applyAgentYield(true)
                                   → 所有窗口 notTouchable / <empty>
◀──────────────── OK <nonce> ────   全程 18ms
POST /api/action（此时命中测试已经没有球）
── RELEASE <nonce> ──────────────▶  gateUntilMs = 0 → applyAgentYield(false)
◀──────────────── RELEASED ──────   0.3s 内恢复 alpha=1.00 / touchable
```

logcat（`logcat -s FloatingBall`）长这样：

```
gate tcp listening 127.0.0.1:3181
tcp yield: tcp-verify-1 → 让路 4000ms
agent yield -> 全 UI 让路（看得见、点不到）
yieldFlag View -> NOT_TOUCHABLE
agent gate -> DISARM (球只显示)
tcp release: tcp-verify-1
agent yield -> 恢复球窗口触摸
agent gate -> ARM (球可触摸)
```

文件通道那一路（老 APK / root 起的 DSH）：`agent act signal: … → 让路 1500ms` +
`agent gate ack: … -> N 个目录`，1.5s 后自动 `ARM`。

---

## 4. 第三层：全 UI 让位 —— 一处收口，两类手法

老版本 `applyBallTouchability()` 只管代理窗口一个，而且 `ballTouch == null` 时**整段
return**。10-16 之后球又长出 9 个可触摸窗口（文字点击层、全屏"点空白关掉"层、玻璃菜单、
输入行、搜索条、审批三层…），它们照样吃注入触摸 —— 这就是用户报的"这功能以前做过，但失效了"。

现在唯一入口是 `applyAgentYield(boolean on)`（:2879），登记表分两类：

| 手法 | 用在 | 代码 |
|---|---|---|
| `FLAG_NOT_TOUCHABLE` | **全透明**窗口：代理窗、`siriTouch`、`menuOutside`、`searchOutside`、`approvalTouch` | `yieldFlag(v, params)`（:2904） |
| **可触摸区域置空** | **看得见**的窗口：`menuView`、`convoBar`、`convoSideBtn`、`searchBar`、`siriText`、`approvalContent`、`approvalActionsScroll`、`approvalInputRow` | `yieldRegion(v)`（:2921） |

为什么分两类：看得见的窗口加 `FLAG_NOT_TOUCHABLE` 会换来 ColorOS 的 alpha=0.80（"贴纸感"），
所以可见窗口只改 `TOUCHABLE_INSETS_REGION`，视觉一动不动；`yieldRegion` 每个 View 只挂一次
监听（`WeakHashMap` 去重），监听里读 `agentYieldOn` 决定空/满 region，翻转时 `requestLayout()`
让系统重算。

两个补漏机制：

- `pumpAgentGate()` 在让路期间**每 60ms 补登记一次**（:2815）—— agent 动作中途才建出来的窗口
  （审批面板、菜单卡片）最迟 60ms 内跟着让路。
- `yieldRegion` 挂不上（OEM 不认 @hide API）就退回 `yieldFlag`：让路仍成功，代价是那个窗口
  可能被压到 alpha 0.80。

注：代理窗口本身在 `searchActive / searchClosing / menuShown / glassFullscreen` 时
**本来就**不接管（`applyBallTouchability()` :2853），所以那些状态下球不抢手势是独立于让路协议的。

---

## 5. 每个"设计决定"背后的实测坑

| 决定 | 不这么做会怎样（实测） |
|---|---|
| 内容比对（nonce 变了）而不是 mtime 比对 | `/sdcard` 是 FUSE，App 看到的 mtime 有缓存延迟，同一秒连写 mtime 可能不变 |
| 让路窗口从**球自己看到新 nonce**的那一刻用**本地时钟**起算 | 球以为 mtime 比真实写时间早 ~700ms，1.5s 窗口实际只剩 ~740ms |
| 信号原子写（`.tmp`+rename） | 球读到半个 nonce → ACK 永远对不上，"偶尔让路偶尔不让" |
| 遗留信号豁免 = "本次启动还没见过任何信号" **且** 文件 mtime > 8s | 早先"启动后首读一律忽略"翻过车：遗留 nonce 被记成已见，下一次真 nonce 恰好同序列 → 真信号被丢，那一下点击被球吃掉 |
| 握手成功后插件 `unlink` ACT | 从根上不留遗留物，不依赖上面的豁免逻辑 |
| 心跳先于 `ballTouch == null` 判空 | 否则代理窗口没加成功的机器上插件永远认为"球没在跑" |
| TCP 主通道 | 只靠文件：非 root DSH 读不到 `/sdcard/Android/data/<球包>` → 1ms 静默跳过，点击落球 |
| 失败必须打 `[ball]` 行 | 老版本 `{ok:true,skipped:"ball-not-running"}` 静默返回，用户和 AI 都看不到异常 |
| 可见窗口用空 region，不用 flag | ColorOS 把 alpha 钉 0.80，摘 flag 也不还原 |
| 代理窗口跟玻璃同尺寸 | 固定尺寸 → 球两侧死带，"某一笔凭空消失 / 球位置莫名跳变" |
| 代理窗口 `setTitle` | 按面积猜窗口会认错（文字层 697x102 那种） |

---

## 6. 失效模式与自愈

- **fail-open**：球没在跑 / 写不进信号 → 最多多等 500ms，动作照发，结果里带一行
  `[ball] 让路确认未收到（<逐条原因>）`。
- **`!skip:<nonce>`**：设置页关了「DSH 操控时全部 UI 让位」，或放了 `dsh-ball-guard-off`
  文件 → 球不让路但立刻回 skip，插件不等 500ms（手感跟没装球一样）。
- **nonce 防串**：`RELEASE` 只认 `lastTcpNonce`；`YIELD` 会覆盖 `lastActNonce`，
  防止同一次信号被文件协议再处理一遍。
- **超时兜底**：TCP `YIELD` 默认 hold 4000ms（最短 300）；插件崩了球最多 4s 自动恢复；
  文件通道 `ACT_HOLD_MS = 1500`。
- **体检**：`bash android/tools/ball-gate-doctor.sh` 九环（球在跑 / DSH 的 uid / 心跳三路径 /
  用 DSH uid 实测读 / 插件有没有让路代码 / 让位开关 / TCP 端口 / 此刻窗口是否 notTouchable /
  最近一次动作走了哪条通道），逐环 `[ok]/[!!]`。
- **流水**：每次让路往 `~/dsh-ball-guard.log` 追加一行
  `uid=… action=… ok=… via=tcp:3181 nonce=…`，>200KB 只留最后 200 行。

---

## 7. 实测证据（都是仓库里记过的真机结果）

1. **跨机型根因**：把本机进程降到 Termux uid 10326 →
   `cat/echo/stat /sdcard/Android/data/com.glslplayground.app` 全 `Permission denied`；
   老插件同场景 `{"ok":true,"skipped":"ball-not-running","dt":1}`（静默）；
   新插件 `{"ok":true,"via":"tcp:3181","dt":18}`。见 `BALL_GUARD_CROSS_DEVICE.md` §2。
2. **全窗口让位**（LE2120 / Android 13，开着搜索层）：
   `dumpsys input` 里 5 个球窗口让路前至少一个 `touchable`，让路中**一个都不**可触摸，
   1.5s 后恢复。见 `BALL_TOUCH_GUARD.md` §4.1b。
3. **穿球点击**：目标便签落在球范围 `x 7–1080, y 989–1614` 内，点 (540,1224)，
   `NoteViewRichEditActivity` 打开、球 y 仍是 989；对照组（不让路）点击被球吃掉、界面无反应。
4. **穿球画线**：从球心 `(534,1083)` 起笔画到 `(950,1650)`，画布上出现完整一笔、
   球没动没弹菜单；关掉让位后同一位置单击 → 球弹"状态 2 输入框"。
5. **开关关掉那一路**：窗口照旧可触摸 + 球回 `!skip:<nonce>`，插件不白等。

**写这份文档时的现场状态（2026-10-28 探测，如实记录）**：球当前**没有在跑** ——
`nc 127.0.0.1 3181` 两次都是 `Connection refused`，`dumpsys window` 里没有
`dsh-ball-proxy`，`~/dsh-ball-guard.log` 是 0 字节。也就是说此刻防误触**处于未生效状态**
（进程不在，没有可让路的窗口）；打开「悬浮于桌面」后协议即恢复，不需要改任何东西。
DSH 进程在跑（root，pid 11359，port 3081）。

---

## 8. 自己验（三条命令，都不用重启 DSH）

```bash
# ① 球侧：信号 → notTouchable + ACK → 1.5s 恢复
bash android/tools/ball-guard-verify.sh

# ② 全窗口：平时可触摸 → 让路中一个都不能 → 恢复（--with-search 把搜索层也叫出来）
bash android/tools/ball-yield-verify.sh --with-search
bash android/tools/ball-yield-verify.sh --off        # 开关关掉：不让路 + 回 !skip ACK

# ③ 插件侧：从真实插件源码切出协议代码跑（含 fail-open 用例）
node android/tools/ball-guard-plugin-e2e.mjs click
```

换机 / 重装一条命令：`bash ai-ball-guard-setup.sh`（末行 `RESULT: PASS|FAIL`），
PASS 后再 `bash ai-ball-guard-setup.sh --restart-dsh`（插件是 ESM，只有重启 DSH 才生效）。

---

## 9. 已知边界（刻意的取舍，不是缺陷单）

- 让路只保证"球不吃手势"，**不保证"球看不见"**：球仍然画在屏幕上，截图里能看到它。
- 让路窗口内（1.5~4s）**用户**点球也会被穿过去。宁可漏一次用户点击，也不让 agent 的手势被吃掉。
- TCP 只绑 `127.0.0.1` 且**无鉴权**：同机任何应用都能发 `YIELD`（后果只是球短暂点不到）。
  加鉴权要多一份密钥分发，收益为零。
- 用户手指正按着球时 agent 要注入：球会在 ~60ms 内退出命中测试，用户那串手势收到
  `ACTION_CANCEL`（球突然不跟手）—— 设计取舍。
- `yieldRegion` 依赖 @hide API；OEM 不认时退回 flag（让路成功，视觉可能变 0.80 透明）。

---

## 10. 代码索引

| 位置 | 内容 |
|---|---|
| `android/src/com/glslplayground/app/FloatingBallService.java:2709` | `pumpAgentGate()` 文件协议主循环（心跳 / 开关 / nonce 比对 / ACK） |
| `…:2852` | `applyBallTouchability()` 代理窗口 + 收口调用 |
| `…:2879` | `applyAgentYield()` 全 UI 让位唯一入口 |
| `…:2904` / `…:2921` | `yieldFlag()` / `yieldRegion()` |
| `…:1873` | `makeTouchTransparent()` 玻璃只画不摸（空 region，不用 flag） |
| `…:3053` | `syncBallTouchWindow()` 代理窗口几何 |
| `…:3111` | `inBallAtRaw()` 命中判定（SDF） |
| `…:3502` | `startGateTcpServer()` 回环门控 |
| `…:3579` | `gateCommand()` 行协议实现 |
| `patches/preset-mobile-use/index.js.patched:313` | `standDownOnce()` 三级通道 |
| `…:345` / `…:352` | `waitForBallStandDown()` / `releaseBallStandDown()` |
| `…:653` | 插件调用点（注入前） |
| `android/tools/ball-gate-doctor.sh` | 九环体检 |
| `android/tools/ball-yield-verify.sh` | 全窗口让位断言 |
