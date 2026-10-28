# 防误触：现状诊断 + 备选方案

> **2026-10-29 已按本文落地，实测通过（12/12 + 8/8）。**
> 落地后的实现说明、真机数据和回退开关见 [`HANDOFF.md`](HANDOFF.md) 顶部
> 「2026-10-29：防误触 v2」。本文保留为设计依据，其中三条判断被真机证实/推翻：
> - ✅ **遮挡拦截确有其事，且看的是"窗口框"不是可触摸区**：跨 App 点击时，玻璃不挪 = 完全没反应，
>   玻璃挪走 = 立刻点中。（同一 App 的窗口不拦 —— 这就是老验收一直"通过"的原因。）
> - ✅ **注入触摸的 deviceId 是 -1**（真实触摸屏是白名单里的设备号），判据可用。
> - ❌ **本机 MIUI 不允许 App 加 `TYPE_ACCESSIBILITY_OVERLAY`**
>   （`BadTokenException: token null is not valid`），受信任 overlay 这条在本机走不通，
>   代码里保留自动尝试 + 回退。
> - ✅ region 方案对"就地让开"有效，但因为上面第一条，非受信任窗口仍必须把玻璃挪出屏幕。

> 2026-10-29 真机实测 + 读码结论。目的是回答一个问题：
> **为什么这颗球的「防误触」体感上是"压根没实现"，以及如果重做，有哪几条路、各自代价多少。**
>
> 结论先行：现在的机制不是"防误触"，是**"注入前把 UI 挪开"的一次性合作协议**。
> 它有三个结构性缺陷：**只覆盖合作方**、**强时序耦合**、**零反馈**。任何一条断掉，
> 结果都是「静默失败」—— agent 以为点中了，其实点在球上，球还把这一下当成了用户手势。

---

## 一、实测证据（2026-10-29 03:3x，本机）

| 项目 | 实测值 | 怎么得到的 |
|---|---|---|
| 插件侧门控成功率 | **171 次点击尝试里 37 次 `ECONNREFUSED`（21.6% 完全没让开）** | `grep -c 'act start'` / `grep -c ECONNREFUSED ~/.cache/ball-plugin.log` |
| ACK 延迟 | **123 ms**（`GATE_SETTLE_MS=120` + 抖动） | 日志相邻两行时间差 `17:41:52.268 → .391` |
| DONE 后尾巴 | **300 ms**（`DONE_GRACE_MS`） | `FloatingBallService.java:2877` |
| 兜底超时 | **1200 ms**（`AGENT_MAX_MS`） | `FloatingBallService.java:171` |
| 轮询周期 | 40 ms（文件通道） / 事件驱动（socket） | `GATE_POLL_MS`、`serveGateClient` |
| 每次让开要动的窗口数 | **4~6 个**（proxy + siriTouch + 各 overlay + 可选玻璃） | `applyBallTouchability()` / `parkAllOverlays()` |
| 球进程当时 | **没在跑**（`ps` 只有 `vd_server` / `com.agent.mobileuse`），`ball-diag` 停在 03:31 | `su -c ps -A` |
| 门控端口 | `38721` **未监听**；vd 的 `3070` 在监听 | `/proc/net/tcp` |
| 注入通道 | `/system/bin/input` 是 853 字节 **shell 包装器**；Magisk 模块 `ball_input_gate` + `agent_mobile_use` 在位 | `head /system/bin/input` |

一句话：**门控是"有就用、没有就裸奔"的旁路，不是防线。** 每 5 次点击就有 1 次从它旁边走过去。

---

## 二、为什么体感是"压根没实现"—— 七条根因

### R1 覆盖面：只挡两条路，其余全裸
现在只有两种注入会让球让开：
1. 走 `/system/bin/input`（包装器）；
2. 走插件 `mobile_plugin.js` 的 `signalBall()`（socket）。

**完全绕过**的：
- `cmd input tap`（绕过包装器的第一条路，vd 的字符串里就有 `/system/bin/cmd`）；
- `uiautomator` / `UiDevice` 直接 `injectInputEvent`；
- 任何带无障碍服务的第三方自动化（Tasker / AutoJS / 李跳跳 / 快捷指令）；
- PC 端 `adb shell` 里没走 PATH 的调用、`sendevent`、`/dev/input`；
- vd 自己的 Java 注入面（`agent_vd.dex` 里若哪天加了坐标注入，包装器根本看不见）。

### R2 时序耦合：ACT →(≥123ms)→ ACK → 注入 → DONE →(300ms)→ 恢复
整条链任何一环抖动就丢：球主线程在渲染 / 输入法弹起 / GC / 被冻结（ColorOS 后台策略），
ACK 晚到 300ms 以上 → 包装器**照样注入**（它是 fail-open），这一下就喂给球了。
插件侧更糟：`signalBall()` **连接失败直接返回 null**，连 `skip` 都不给，直接注入。

### R3 零反馈：吞掉了没人知道
球把 agent 的 DOWN 吃掉了，**注入方拿不到任何回执**。agent 只能靠下一步 `observe`
猜"这一点没生效"。没有重试、没有告警、没有计数 —— 失败完全不可观测。

### R4 用"挪窗口"来实现"让开"
`parkAllOverlays()` 把每个窗口挪到 `-4×屏`，`proxy` 缩成 `1×1` 挪到 `(-8,-8)`，
玻璃按 `glasspark` 开关决定挪不挪。代价：
- 每次点击 8~12 次 `wm.updateViewLayout`（全是主线程 IPC）；
- 窗口一动就可能重建缓冲（他们自己踩过"玻璃发白"）；
- 视觉上球在抖，操控时用户能看见"球被推了一下又回来"。

### R5 球一死 = 全裸 + 半残
球进程被系统杀掉（实测现在就是死的）→ socket 拒绝连接 → 插件 fail-open。
更糟的是：**球活着但 socket 线程死掉**时，插件以为门控挂了，包装器还在傻等文件 ACK。

### R6 几何是矩形，球是圆的
代理窗口是**球的包围盒 + 12dp**。球是圆的，方角上那块是"看不见的死区"：
人和 agent 点到那儿都会被吃。`FloatingBallService.java:2997` 的注释已经承认过
"球下方一条 ~66px 高的死带"。这才是用户视角的"误触"本体。

### R7 只在"注入那一下"生效
不做会话级：agent 连点 20 下，球就抽搐 20 次。
也不做人机共存：用户正拖着球时 agent 一点，两个手势在同一窗口里打架。

---

## 三、最关键的一条认知：球分不清"人"和"机器"

现在球收到 DOWN 就进入用户手势状态机：拖球、480ms 长按进状态 3、双指长按开搜索。
**agent 的注入点击落上去，会把球拖动、甚至弹出四区/搜索框** —— 这不是"没让开"，
这是"把机器的动作当成了人的动作"。这一层修好，即便门控全挂，也不会再出现
"点一下球飞了"这种事。

### 判据：device 白名单（不用猜 0）
```java
// 初始化一次：真实触摸屏的 deviceId 集合
for (int id : InputDevice.getDeviceIds()) {
    InputDevice d = InputDevice.getDevice(id);
    if (d != null && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) != 0) realTouchIds.add(id);
}
// onTouch 里
boolean synthetic = !realTouchIds.contains(e.getDeviceId());
```
`input tap` / `uiautomator` / `injectInputEvent` 造出来的事件 deviceId 都不是真实触摸屏的 id
（通常是 0）。判据用**白名单反查**而不是"==0"，不怕 OEM 改动；万一某个 ROM 连虚拟键盘
也报 0，也只会让"合成事件"多认几个，不会把真人误判成机器（真人只可能来自白名单里的设备）。

> 待真机确认（需要一次 logcat，目前不改代码）：`input tap` 落到代理窗口时
> `e.getDeviceId()` 的值。一条 `Log.i` 就能定死。

**用途**：
- **A. 合成事件 → 不执行任何用户手势**（不拖球、不进状态 3、不开搜索），并写一条
  `swallowed` 计数进 `ball-diag`；
- **B. 自愈**：连续吞到 2 次合成事件 → 直接进"挂起"（见 L4），不再逐次赌；
- **C. 可观测**：agent 侧能读到"我这一下被球吃了"，于是可以重试而不是瞎猜。

---

## 四、三层正解（按"从根上治"排序）

### L1 几何层：触摸区域换成"圆形 Region"，而不是挪窗口

代码里已经有一个宝贝：`makeTouchTransparent()`（`FloatingBallService.java:1808`）。
它用 `ViewTreeObserver.OnComputeInternalInsetsListener` + `InternalInsetsInfo.TOUCHABLE_INSETS_REGION`
把窗口的可触摸区域设成**任意 `Region`** —— 公开 SDK 里是 @hide，但反射挂得上，
而且**他们已经在用**（玻璃窗口就是靠它"只画不摸"，还顺手绕开了 OPPO 那条
"带 `FLAG_NOT_TOUCHABLE` 就把 alpha 钉成 0.80"的 OEM 规则，见同文件 1786 行注释）。

这条路能一次解决三件事：

1. **矩形死区消失**：把代理窗口的 region 设成圆（甚至"甜甜圈"，把球内文字那块掏掉），
   方角上的点击**由系统直接派给下面的 App**，球根本收不到。这是像素级、物理级的防误触。
2. **"让开"变成一次 region 更新**：`region.setEmpty()` + `requestLayout()`，
   一帧生效，**不挪窗口、不重建缓冲、不触发 OEM alpha 规则**。
   现在那套 `1×1 + 屏外 + parkAllOverlays + glasspark` 可以整段删掉。
3. **人机共存**：用户按住球拖动的同时，agent 点球外 → 两个窗口各收各的，不打架。

代价：`Region` 是窗口本地坐标，要跟着 `syncBallTouchWindow()` 一起重算；
region 变更要触发一次 traversal，理论上比 `updateViewLayout` 便宜得多，但要实测落帧时间。

### L2 遮挡层：窗口类型换成 `TYPE_ACCESSIBILITY_OVERLAY`

Android 12+ 的 InputDispatcher 有"不受信任悬浮窗遮挡"拦截：触摸要**穿过**一个
`TYPE_APPLICATION_OVERLAY` 落到下面的窗口时，可能被整串丢弃。这就是为什么
HANDOFF 里要"把玻璃挪出屏幕 3 倍"。

`TYPE_ACCESSIBILITY_OVERLAY` 是**受信任 overlay**，这条拦截对它不生效。
换过去之后：**玻璃原地不动 + region 置空 = 点击干净穿过去**，零闪烁、零位移，
`glasspark` 那个兼容开关可以删。

代价：要声明并启用一个 `AccessibilityService`（用户设置里点一次）。
在这台机器上不是问题（本来就有 root + vd 的无障碍），但会多一个"无障碍被占用"的状态。
额外收益：无障碍在手，`dispatchGesture()` 也就有了（见 L3）。

### L3 注入层：能不用触摸就不用触摸

`mobile` 工具本身支持 `target='<id>'`（元素级操作）。节点级点击走
`AccessibilityNodeInfo.performAction(ACTION_CLICK)`，**根本不进触摸管线**，
球连事件都收不到 —— 这是最彻底的"防误触"：没有触摸，就没有误触。

- 点击 → `ACTION_CLICK`（有 target 时）
- 滚动 → `ACTION_SCROLL_FORWARD/BACKWARD`（列表类）
- 输入 → `ACTION_SET_TEXT`（已经是这条）
- 只有 Canvas / 游戏 / 自绘控件才退回坐标点击

插件层可以再加一条策略：**坐标点击前先问球要 rect，若落点在球内且节点有 target，
自动改走 target**。这样 agent 写坐标也不会踩雷。

### L4 会话层：把"逐次协议"降级成"兜底"

DSH 侧本来就有 session 镜像（WebSocket）。agent 一开 turn：

- 球进"挂起"：region 空 / 触摸层整体不可命中（一次切换）；
- turn 结束：恢复。

一次会话一次切换，全程零抖动、零竞态。逐次的 ACT/ACK/DONE 只作为"用户手动输入"或
"非 DSH 注入"的兜底保留。收益：20 步任务省下 20 × (123+300) ms ≈ **8.5 秒**的门控尾巴，
外加 20 次窗口抖动。

### L5 注入源层（可选，见"邪修"）

把注入从 `input` 命令换成自己控制的注入器，才能做**同步**门控（见下）。

---

## 五、邪修清单（按"邪"的程度排）

| # | 邪修 | 做法 | 收益 | 代价/风险 |
|---|---|---|---|---|
| 1 | **注入坐标重定向** | 在注入器（包装器或 hook）里判断"这一点落在球窗口内" → 把坐标挪到球外最近的合法点（优先落在同一 a11y 节点的矩形内） | 球**完全无感**，连"让"都不需要；零延迟 | 目标太小时无处可挪；需要节点矩形 |
| 2 | **圆形 / 甜甜圈 touchableRegion** | `Region.setPath(circle)` | 像素级防误触，治"球周围一圈死区" | 需反射/隐藏 API（已有先例） |
| 3 | **a11y `dispatchGesture` 回放** | 球识别出合成事件 → 把自己 region 置空 → 把缓存的 DOWN/MOVE/UP 用 `dispatchGesture` 重放给下面 | 球变成真正的"透明玻璃"：吞了还能吐出来 | 手势重放有 ~30ms 延迟；多点手势要拆 |
| 4 | **trusted overlay** | 换 `TYPE_ACCESSIBILITY_OVERLAY` | 免疫 tap-jacking 拦截 | 需启用无障碍 |
| 5 | **uinput 假手势** | root 守护进程写 `/dev/uinput`，**先握手再写 DOWN** | 1 RTT ≈ 2ms 的同步门控；还能合成压力/多点/抖动，顺手治好"vd 手势太轻，微信认不出" | 要写 C/守护进程；不再是 `input` 命令 |
| 6 | **system_server hook 改坐标/改 region** | 他们已经有 `agent_hook.apk`，在 `InputManagerService.injectInputEvent` 或 `WindowState.getInputInfo` 里动手 | 无法绕过（adb、别的 App 全管）；可做"注入坐标直方图" | 系统级 hook 风险最高；ROM 升级要维护 |
| 7 | **fail-closed 而不是 fail-open** | 包装器等不到 ACK 且落点在球内 → **放弃这次注入并返回错误** | 把"静默失败"变成"显式失败"，agent 会重试 | 偶尔会多一次重试 |

第 7 条其实是立刻能做的止血：现在包装器等 300ms 没 ACK 就照打，
结果打在球上 → 看起来"没反应"。改成返回非 0，插件就会走
"防误触：球没能在注入前让开触摸区，这次点击已跳过"这条已有分支。

---

## 六、推荐路线（不改代码版结论）

1. **P0（止血，改动最小）**：device 白名单判据 + 合成事件不执行用户手势 + `swallowed` 计数。
   → 即使门控全挂，也不会再"点一下球飞了/弹出四区"。
2. **P0.5**：包装器 fail-closed（落点在球内且无 ACK 就返回错误）。
3. **P1（治本，优雅）**：`region` 开关替代"挪窗口"，删掉 `1×1 + 屏外 + parkAllOverlays`。
4. **P2**：会话级挂起，逐次协议降级为兜底。
5. **P3**：圆形 region（人机共存）。
6. **P4**：`TYPE_ACCESSIBILITY_OVERLAY`（跨 ROM 稳定）。
7. **P5**：uinput 注入器或 system hook（要彻底根治再上）。

前三步做完，这个功能才能叫"实现了"；后三步是"做到别人做不到"。

---

## 七、验收标准（可复现）

前提：球在跑（`su -c "env -i PATH=/system/bin HOME=/data/local/tmp am start-foreground-service -n com.glslplayground.app/.FloatingBallService"`）。

| # | 场景 | 命令 | 期望 |
|---|---|---|---|
| 1 | 球压在「⚙ 设置」上，agent 点击 | `mobile click` 到卡片中心 | 打开 `DshSettingsActivity`；球**零位移、零闪烁**；`ball-diag` 里 `swallowed=0` |
| 2 | 同上，但门控关掉 | `su -c "touch /data/local/tmp/ball-gate-off"` + `input tap` | **也应该打开**（region 方案下不需要门控）；若打不开，说明 region 没生效 |
| 3 | 点在球外 3px（方角内、圆外） | `input tap` 到包围盒角 | 事件直达下面的 App；球不动 |
| 4 | 用户拖球 与 agent 点击同时 | 手指按住球 + `mobile click` 别处 | 球照常跟手；点击照常命中 |
| 5 | 球进程被杀后 agent 点击 | `am force-stop` 后 click | 点击直达 App（不再有 21.6% 的裸奔不可知） |
| 6 | 连续 20 次点击 | for 循环 `mobile click` | 每次 < 60ms 额外延迟；无窗口抖动；`ball-diag` 计数自洽 |

诊断口子保持：`files/ball-diag` 增加 `synthetic=`（最近 2s 内的合成事件数）、
`swallowed=`（累计被吞次数）、`region=`（当前可触摸区面积/模式）。

---

## 八、坑（写的时候别踩）

- **OPPO/一加的 alpha 0.80 规则**：任何 `TYPE_APPLICATION_OVERLAY` 带 `FLAG_NOT_TOUCHABLE`
  且 `alpha > 0.8` 会被系统钉成 0.80。所以"让开"**不要用 flag**，用 region（已有先例）。
- **`Region` 是窗口本地坐标**，滚动/缩放/状态 3 换形时都要跟着重算，否则圆会跑偏。
- **不要在用 region 的同时又挪窗口**：两套坐标会打架（现在的 `glassParked` 就是被这个坑过）。
- **`cmd input` 绕过包装器**：要么把包装器做进 `cmd`，要么别依赖包装器（走 uinput / hook）。
- **球进程保活**：ColorOS 会杀。现在杀了就是"全裸"，需要 root 侧看门狗（`dsh-ball-alive` 时间戳
  超过 10s 就 `am start-foreground-service`）。
- **无障碍 overlay 与 kiosk**：`TYPE_ACCESSIBILITY_OVERLAY` 的生命周期跟无障碍服务走，
  服务被系统关掉时窗口会一起消失，要有降级到 `TYPE_APPLICATION_OVERLAY` 的路径。
- **别把"合成事件"判据用在滚动上**：惯性滚动（`input swipe` 之后系统自己发的）也可能不是白名单
  设备，只对 `ACTION_DOWN` 做判据。
