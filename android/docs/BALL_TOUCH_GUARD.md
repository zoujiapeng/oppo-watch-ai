# 悬浮球 × DSH 触摸门控（球让路协议）

> 一句话：**agent 注入的点击/滑动永远不会落到球上（球自己的任何一个窗口都不行），
> 用户手指照常能点球、拖球。**
> 做法不是"把球画得更透明"，而是：玻璃层只画不摸 + 球上只有一个贴身代理窗口接用户手势 +
> agent 要动手之前先打招呼，那一刻**球拥有的每一个窗口**一起退出命中测试。
>
> 2026-10-23 修订：以前只让**代理窗口**让路，球后来长出来的那些窗口（文字点击层、状态 3 的
> 全屏"点空白关掉"层、玻璃菜单、输入行、搜索层、审批三层）照样吃注入触摸 —— 这就是用户报的
> 「这功能以前做过，但失效了」。现在统一由 `applyAgentYield()` 一处收口，**设置页有开关**。

## 1. 为什么会有这个问题

DSH 的 mobile 工具注入触摸的链路是：

```
模型 → mobile 工具（dsh-preset-mobile-use）→ POST /api/action → vd_server
     → /system/bin/input …（shell uid 的 INJECT_EVENTS）
     → InputManagerService → InputDispatcher → **窗口命中测试** → 目标 App
```

关键是最后一步：**注入事件和真手指走的是同一套命中测试**，`MotionEvent` 里没有任何
"这是注入的"标记，系统层面分不出谁是谁。而悬浮球是 `TYPE_APPLICATION_OVERLAY`、常驻屏幕
中间、可触摸，于是：

- 注入的 `ACTION_DOWN` 落在球上 → 球吃掉 → 底下 App 连 down 都收不到，滑动半途而废；
- 更糟：球把这串手势**当成用户在操作**（按住 = 挪球、双指长按 = 打开搜索层）。

DSH 自己那套悬浮界面不吃手势，是因为：它要么是 `FLAG_NOT_TOUCHABLE` 的纯显示层
（`GlowService` 的边缘光 + 触摸指示点），要么根本不在物理屏上（自动化在副屏跑），
而且它不常驻。不是它"画得透明"。

## 2. 协议

```
DSH 侧（要注入触摸时）                          球侧（每 60ms 轮询）
──────────────────────────────────            ──────────────────────────────
1. 原子写 nonce → <GATEDIR>/dsh-ball-act
   （.tmp-<pid> + rename）
                                               2. 读到 nonce ≠ 上次那个 → 从**本地时钟**起算
                                                  让路窗口 ACT_HOLD_MS（1500ms）
                                               3. 代理触摸窗口 |= FLAG_NOT_TOUCHABLE
                                                  （玻璃层本来就 NOT_TOUCHABLE）
                                               4. 写同一个 nonce → <GATEDIR>/dsh-ball-ack
5. 读到 ACK 是自己的 nonce（最多等 500ms）
6. 才真正 POST /api/action 注入触摸
                                               7. 窗口到期 → 去掉 flag，球恢复可触摸
```

**GATEDIR = `/sdcard/Android/data/com.glslplayground.app/files`**（为什么是这个目录见 §5）。

四条关键设计（都是踩出来的）：

| 决定 | 为什么 |
|---|---|
| 只有 `click` / `swipe` / `key` 触发（`BALL_GUARD_TOUCH_ACTIONS`） | `observe` / `set_value` / `launch_app` / `switch_mode` / 审批 / 打字都不注入触摸，球这时该保持可交互 |
| 用**内容比对**（nonce 变了）而不是 mtime 比对 | `/sdcard` 是 FUSE，App 看到的 mtime 有缓存延迟；同一秒内的连续写 mtime 可能不变 |
| 让路窗口从**球自己看到新 nonce 的那一刻**起算（本地 `System.currentTimeMillis()`） | 实测球以为 mtime 比真实写时间早 ~700ms，1.5s 的窗口实际只剩 ~740ms；用本地时钟才稳 |
| 信号必须**原子写**（`.tmp` + rename） | 球每 60ms 读一次，直接 `>` 重定向会先截断，球容易读到半个 nonce → ACK 永远对不上（实测 3 次坏 1 次） |

还有：

- **fail-open**：球没在跑 / 写不进信号 → 最多多等 500ms，动作照发（结果里会带一行 `[ball]` 提示）。
- **总开关**：`touch <GATEDIR>/dsh-ball-guard-off`（或 `/data/local/tmp/dsh-ball-guard-off`）
  → 球忽略信号，回到"永远可触摸"的老行为。
- **ACK 写失败退避** 500ms，不会每 60ms 刷一条错误日志。
- **遗留信号的豁免只看"文件够不够旧"**（`STALE_MS = 8000`）：只有"本次启动还没见过任何信号"
  **且** 文件 mtime 超过 8 秒没动，才当成遗留物忽略。
  ⚠️ 这里翻过一次车（一定要记住）：早先的版本是"启动后首读一律忽略"，结果球重启后首读把遗留
  nonce 记成"已见"，而插件下一次点击生成的 nonce 恰好是同一个序列 → **真信号被当旧信号丢掉**，
  球不让路、不回 ACK，那一次点击就被球吃掉（现象：`[ball] 让路确认未收到` + 点击无反应）。
- **握手成功后插件顺手删掉信号文件**（`fs.unlinkSync(actPath)`），从根上不留遗留物。

## 2.5 全 UI 让位（2026-10-23，当前语义）

老版本 `applyBallTouchability()` 只管代理窗口 `ballTouch` 一个，而且它在
`ballTouch == null` 时**整段 return**（代理窗口没加成功 → 门控直接死掉）。
10-16 之后球又长出 9 个窗口，全都可触摸：

| 窗口 | 形态 | 让路手法 |
|---|---|---|
| `ballTouch` 代理层 | 全透明 | `FLAG_NOT_TOUCHABLE` |
| `siriTouch` 文字点击层 | 全透明 2×2 → 文字带 | `FLAG_NOT_TOUCHABLE` |
| `menuOutside` / `searchOutside` 全屏"点空白关掉"层 | 全透明铺满屏 | `FLAG_NOT_TOUCHABLE` |
| `approvalTouch` 审批拖动层 | 全透明 | `FLAG_NOT_TOUCHABLE` |
| `menuView` 玻璃菜单 / `convoBar` 输入行 / `convoSideBtn` / `searchBar` 搜索条 | **看得见** | 可触摸区域置空（`yieldRegion`） |
| `siriText` 球内文字层 | 看得见（文字） | 可触摸区域置空 |
| `approvalContent` / `approvalActionsScroll` / `approvalInputRow` 审批三层 | 看得见 | 可触摸区域置空 |

**为什么分两种手法**：看得见的窗口一旦加 `FLAG_NOT_TOUCHABLE`，一加/OPPO 的 WindowManager 会把
整窗 `alpha` 钉在 0.80（见 §5 第 13 条），而且摘掉 flag 也不保证还原 —— 那就是 10-16 那次
"球变半透明贴纸"。所以可见窗口一律**只改可触摸区域**（`ViewTreeObserver` +
`TOUCHABLE_INSETS_REGION`，反射挂动态代理），视觉一动不动。

实现要点（`FloatingBallService`）：

- `applyAgentYield(boolean on)`：唯一入口。状态翻转时 logcat 打
  `agent yield -> 全 UI 让路（看得见、点不到）` / `恢复球窗口触摸`，并给所有登记过的可见窗口
  `requestLayout()` 让系统重算区域。
- `yieldFlag(view, params)`：只切换 `FLAG_NOT_TOUCHABLE` 位，**按切换前的真实状态还原**
  （所以 `siriText` 本来该 `NOT_TOUCHABLE` 的时候不会被误改成可触摸）。
- `yieldRegion(view)`：每个 View 只挂一次监听（`IdentityHashMap` 去重），监听里读
  `agentYieldOn`：让路时空 region、平时满 region（0,0,w,h）。挂不上就退回 `yieldFlag`。
- 调用点：`applyBallTouchability()` 尾部（所有状态切换都会走到），以及每 60ms 的 `pumpAgentGate()`
  里让路期间补登记 —— agent 动作中途才建出来的窗口（审批面板、菜单卡片）最迟 60ms 内让路。
- **开关**：`float_agent_yield`（默认 true）。设置页 → 悬浮水晶球 →「DSH 操控时全部 UI 让位」；
  也仍然认老的文件开关 `dsh-ball-guard-off`。关掉时球**不**让路，但照样回 `!skip:<nonce>` ACK，
  插件因此不用白等 500ms —— 手感跟没装球一样。
- **心跳**：球每 2s 写 `dsh-ball-alive`。插件先看心跳：球没跑就根本不发信号、也不等 ACK
  （以前球关着时每次注入都白等半秒的根源）。


## 3. 落地位置（三处）

| 位置 | 角色 |
|---|---|
| `android/src/.../FloatingBallService.java` | 球侧：`pumpAgentGate()` / `applyBallTouchability()` / `syncBallTouchWindow()` / `setupBallTouchLayer()` / `inBallAtRaw()` / `gateDir()` |
| `~/dsh-preset-mobile-use/lib/index.js` | DSH 侧**主路径**：`waitForBallStandDown()`（心跳探测 + `!skip:` 认账 + 全动作让路），在 `default:` 分支发 `/api/action` 之前调用 |
| `patches/apply-vd-ball-guard-patch.sh` | CLI 路径：给 Magisk 模块里的 `/system/bin/vd` 的 tap/swipe/key 分支插 `ball_signal`（手动调试用） |

### 3.1 窗口结构（这次改动）

```
玻璃层 GLSurfaceView   fl=NOT_FOCUSABLE NOT_TOUCH_MODAL LAYOUT_NO_LIMITS   ← 只画不摸
                       可触摸区域 = 空（ViewTreeObserver TOUCHABLE_INSETS_REGION）
      ↑ z 更低
代理窗口 ballTouch     贴身包住球（+10dp 容错），唯一接用户手势的窗口
                       （只有它保留 FLAG_NOT_TOUCHABLE，让路时用）
      ↑ z 更高
文字触摸层 / 审批按钮层 / 审批输入行   各自按需出现（原样没动）
```

> ⚠️ **2026-10-16 起，玻璃层不再用 `FLAG_NOT_TOUCHABLE`**（见 HANDOFF「半透明图层真根因」）：
> 一加/OPPO 的 WindowManager 会把带这个 flag 的系统悬浮窗 alpha 钉成 0.80，
> 球会永远只有 80% 不透明。现在改用**空可触摸区域**
> （`FloatingBallService.makeTouchTransparent()`：反射挂
> `ViewTreeObserver.OnComputeInternalInsetsListener` → `TOUCHABLE_INSETS_REGION` + 空 region），
> 输入系统照样命中不到玻璃层，但窗口保持 alpha=1.0。
> **别把 `FLAG_NOT_TOUCHABLE` 加回玻璃层/文字层/审批底** —— 那不是"只画不摸"的唯一写法。

**代理窗口尺寸 = 玻璃层当前矩形 + 每边 10dp**（`syncBallTouchWindow()`，数值没变就不动窗口）。
早期版本按"球的最小形态"固定尺寸，结果 Siri 球宽在 ~700↔~1040 之间动画时，
玻璃层变大、代理层没跟上 → 球两侧出现**死区**：触摸确实落在球上，却没被代理窗口盖住，
事件被直接丢弃（实测：画布上那一笔凭空消失，且球的水平位置会跳变）。
现在玻璃层每次改尺寸代理层都跟，窗口只在数值变化时更新。

代理窗口用 `setTitle("dsh-ball-proxy")` 起过名字 → `dumpsys` 里就是
`Window{<token> u0 dsh-ball-proxy}`，验证工具按**名字**认它，不靠"猜哪个窗口是代理层"
（早期版本猜错过：文字触摸层 / 玻璃层都会带偏）。

代理窗口是 `setupBallTouchLayer()` 在玻璃层 `addView` **之后立刻加**的 → z 序最低的触摸层，
所以审批面板的文字/按钮/输入行照样压在它上面；面板期间球本来也不该抢手势
（`approvalShown` 时直接 `return false`）。

顺带修掉一个隐形坑：玻璃窗口比球本身大一圈（普通模式 `winSize` 方形 vs 球 `0.9×winSize`），
`FLAG_LAYOUT_NO_LIMITS` 下窗口里那圈"看不见的透明边"同样在吞触摸。现在玻璃层不接触摸、
代理窗口只包球，这个坑没有了。

### 3.2 用户手势（手感没变）

代理窗口的 `onTouch` 和原来 `glView` 上那套**完全同源**：单击不动、
按住 480ms = 长按取消（问答中）、移动超过 10dp 转拖动（`moveBallDrag()`，夹取规则一致）。
判断"按在球上"用同一个圆角矩形 SDF（`inSiriBall()`），这里多一步屏幕坐标 → 玻璃窗口本地坐标
换算（`inBallAtRaw()`）。

## 4. 验证（两个脚本，都不需要重启 DSH）

### 4.1 球侧：`android/tools/ball-guard-verify.sh`

```bash
bash android/tools/ball-guard-verify.sh     # 球必须在跑
```

打 ACT 信号 → 断言代理窗口变 `notTouchable` + ACK 匹配 → 等 1.5s 断言自动恢复 → 打日志。
实测（2026-10-15，LE2120 / Android 13）：

```
[ok] com.glslplayground.app pid=7707  信号目录=/sdcard/Android/data/com.glslplayground.app/files
[ball] 信号前：touchable 710x497 at (185,1053) tok=d301016
[ball] 信号后：notTouchable 719x499 at (180,1052) tok=d301016
[ok] ACK 匹配：verify-1792069713862926920
[ok] 代理窗口已退出命中测试（agent 的触摸不会落到球上）
[ok] 球恢复可触摸（用户手指能点/拖）
```

### 4.1b 全窗口让位：`android/tools/ball-yield-verify.sh`（2026-10-23 新增）

```bash
bash android/tools/ball-yield-verify.sh --with-search   # 先把搜索层叫出来，窗口最多
bash android/tools/ball-yield-verify.sh --off           # 验证开关关掉：不让路 + 回 !skip ACK
```

它按 `ownerPid` 把球进程的**所有**输入窗口列出来，读 `dumpsys input` 里的 `inputConfig`
（有没有 `NOT_TOUCHABLE`）与 `touchableRegion`（是不是 `<empty>`），断言：

- 平时：至少一个窗口可触摸；
- 让路中：**一个都不能可触摸**（脚本持续重打信号，避免 `dumpsys` 太慢跑出 1.5s 窗口）；
- 1.5s 后：恢复可触摸。

实测（2026-10-23，LE2120 / Android 13，开着搜索层）：

```
── before
35361a3 com.glslplayground.app|[57,186][1022,317]|touchable      ← 搜索胶囊
85bde21 com.glslplayground.app|[0,-191][1080,2221]|touchable     ← 全屏"点空白关掉"层
724e4d6 com.glslplayground.app|[0,96][2,98]|touchable            ← 文字点击层
d82de03 dsh-ball-proxy|[178,1047][902,1557]|notTouchable          ← 搜索期间本来就不接管
650a626 com.glslplayground.app|<empty>|notTouchable              ← 玻璃（永远只画不摸）
── during
35361a3 com.glslplayground.app|<empty>|notTouchable              ← 区域被置空，画面不动
85bde21 com.glslplayground.app|[0,-191][1080,2221]|notTouchable   ← 加了 flag
724e4d6 com.glslplayground.app|[0,96][2,98]|notTouchable
d82de03 dsh-ball-proxy|[178,1047][902,1557]|notTouchable
  [ok] 让路期间 5 个球窗口全部退出命中测试
  [ok] ACK 匹配：verify-1792787896537941620-14-click
── after
  [ok] 已恢复可触摸（用户照常点球/拖球）
★ 全部通过
```

开关关掉（`--off`）那一路也实测过：

```
  [ok] 开关关掉时窗口照旧可触摸（预期行为）
  [ok] 球回了 skip ACK（插件不用白等 500ms）：!skip:verify-1792787946564177851-14-click
★ 全部通过
```


### 4.2 插件侧贯通：`android/tools/ball-guard-plugin-e2e.mjs`

```bash
node android/tools/ball-guard-plugin-e2e.mjs click   # 也支持 swipe / key
```

它从 `~/dsh-preset-mobile-use/lib/index.js` **原样切出** `resolveBallGuardDir` +
`waitForBallStandDown`（同一份源码，不是抄一份），在真机上调用，同时观察真实球窗口。
所以**不用重启 DSH 也能验证插件那半边**：

```
[ball] 返回 ok=true reason=- nonce=1792069724479-14159-1-key 用时=54ms
[ok] 球回了 ACK（握手成功，54ms）
[ball] 现场状态：notTouchable 713x499 at (188,1052) tok=d301016
[ok] 球此刻不接触摸 → agent 的触摸不会落到球上
[ok] 球恢复可触摸（用户手指能点/拖）
[ok] 拿不到信号目录时不阻塞（1ms，调用方照发动作）
★ 全部通过
```

> ⚠️ 跑之前先把搜索层收掉：`touch /sdcard/Android/data/com.glslplayground.app/files/search-close`。
> 搜索胶囊在屏上时代理窗口**本来就故意不接管**（`applyBallTouchability` 的 `searchActive`），
> 第三步"恢复可触摸"会误报失败。

### 4.2b 真机"agent 穿球点击"验证（2026-10-15 实拍，DSH 重启后）

重启 DSH（插件新代码生效）后，用 **mobile 工具**（就是 agent 走的那条路）点击一个
**落在球里面**的便签：球的范围 `x 7–1080, y 989–1614`，点击目标 (540,1224)
（便签列表第 4 条「底下垫着干净的东西…」）。

```
21:32:21.623 FloatingBall: agent act signal: 1792071141563-20615-5-click → 让路 1500ms
21:32:21.624 FloatingBall: agent gate -> DISARM (球只显示)
21:32:21.628 FloatingBall: agent gate ack: ... -> dsh-ball-ack
21:32:23.164 FloatingBall: agent gate -> ARM (球可触摸)
```

结果：便签**详情页打开了**（`NoteViewRichEditActivity`，内容 109 字），
且球没有被挪动（y 仍是 989；宽度变化是文字动画，不是拖动）。

对照（同一位置、旧插件 / 无握手时）：
- 点击被球吃掉 → 界面毫无反应（`[ball] 让路确认未收到`）；
- 球**外面**的点击一直正常（`topResumedActivity` 变成详情页）→ 证明注入本身是通的。

### 4.3 真机"透传"验证：在便签画布上穿过球画线（2026-10-15 实拍）

步骤：便签 → 新建 → 涂鸦 → 从球上方起笔、竖着贯穿球体画到球下方 → 截图做像素比对。

结果（球当时占 `x 120–959, y 989–1614`，截图坐标 ÷2）：

```
竖线暗像素连续段：y=214..991（长 778px）     ← 贯穿全画布
球覆盖区（截图 y 494..807）内是否有断口：无
球区内采样：y=500 -> 0, y=650 -> 4, y=800 -> 0   （0/4 = 纯黑笔画）
```

同一轮还确认了两件事：

- **用户拖动没退化**：在球上按住往下拖 400px，球的 y 从 989 → 603（正常跟手）。
- **球覆盖 App 控件时它会吃掉那次点击**（设计如此，不是 bug）：球停在顶部中央时盖住了
  撤销/完成按钮，点撤销没反应 —— 球**可见范围内**本来就归球。想点被盖住的控件，
  先把球挪开（拖一下即可）。

辅助工具：`python3 android/tools/ball-proxy-state.py` → `touchable|notTouchable WxH at (x,y) tok=...`
（两个脚本都靠它判状态，口径统一；代理窗口名 `dsh-ball-proxy` 就是给这一步用的）

日志（`logcat -s FloatingBall`）：

```
gate dir = /sdcard/Android/data/com.glslplayground.app/files
ball touch proxy added
agent act signal: 1792069...-1-click @.../dsh-ball-act → 让路 1500ms
agent gate -> DISARM (球只显示)
agent gate ack: 1792069...-1-click -> .../dsh-ball-ack
agent gate -> ARM (球可触摸)
```

## 5. 踩过的坑（别再回去）

1. **球自己判断不了**：`MotionEvent` 没有"注入"标记，必须有外部信号。
2. **`/data/local/tmp` 根下 App 只能读不能写**：目录是 `drwxrwx--x shell shell` + `shell_data_file`
   标签，`untrusted_app` 域写 ACK 报 `EACCES`（实测；读 ACT 是成功的，所以很容易误判成"能写"）。
3. **`/data/local/tmp/dsh-ball` 0777 子目录也不行**：连目录创建/写文件都被 SELinux 拒。
   → 最终选 App 的**外部私有目录** `/sdcard/Android/data/<pkg>/files`：App 免权限可写，
   root / shell / Termux 用户都读得到（`media_rw_data_file` 标签，实测 shell 写 OK）。
   **别用内部私有目录 `/data/data/<pkg>/files` 当主路径**：C2S 写没问题，但不保证所有
   SELinux 策略下非 root 进程读得到。
4. **别每帧重摆窗口**：`syncBallTouchWindow()` 所有字段都比对过才 `updateViewLayout`。
   这个项目已经被"每帧改窗口"咬过两次（审批面板"点了没反应"、面板几何打架）。
5. **代理窗口只加 `FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL|FLAG_LAYOUT_NO_LIMITS`**：
   它是不可聚焦的，不会抢输入法焦点；审批输入行是**另一个**可聚焦窗口，两者不冲突。
6. **ACK 要原子写**（`.tmp` + rename），否则插件可能读到半个 nonce，永远等不到确认。
7. **`/system/bin/vd` 要改 Magisk 模块里的那份**（`/data/adb/modules/agent_mobile_use/system/bin/vd`）；
   `/system/bin` 在 Magisk 下是 **ro 挂载**，`cp` 过去会失败 —— 模块里改好，**重启后自动生效**。
8. **`/sdcard`（FUSE）上的 mtime 不可信**：球读到的 mtime 可能比真实写时间早 ~700ms，
   "mtime + 1.5s 内算新鲜"这种判定窗口会缩水（实测只剩 740ms）。判"有没有新信号"要用
   **内容（nonce）比对**，窗口长度用**本地时钟**起算。
9. **信号文件必须原子写**：球 60ms 读一次，`>` 重定向先截断再写，球会读到半个 nonce，
   ACK 永远对不上 —— 表现为"球偶尔让路、偶尔不让路"。插件与 vd 脚本都已改成 `.tmp` + rename，
   验证脚本同理。
10. **别靠"猜哪个窗口是代理层"做验证**：文字触摸层（`697x102` 那种）和玻璃层都会把
   "面积最大/最小"的启发式带偏。代理窗口已 `setTitle("dsh-ball-proxy")`，按名字找。
11. **代理窗口不能按"球的最小形态"固定尺寸**：Siri 球宽动画到 ~1040px 时，固定尺寸的代理层
   盖不住球的两侧 → 那一片成了死区（触摸落到球上却被 `inBallAtRaw()` 判成球外、直接丢弃），
   症状是"某一笔凭空消失 / 球位置莫名跳变"。代理层要**跟玻璃层同尺寸**（+外扩）。
12. **改代码别用"从 A 切到 B"的大块替换**：这次就是这么把 `syncBallTouchWindow` /
   `inBallAtRaw` / `setupBallTouchLayer` 三个方法整段删掉过一次（编译报 symbol not found 才发现）。
13. **`FLAG_NOT_TOUCHABLE` 在 ColorOS 上会换来 alpha=0.80**（2026-10-16 定位）：玻璃层用它 =
   球永久 80% 不透明（"半透明贴纸"）。只画不摸要用**空可触摸区域**，
   见 `makeTouchTransparent()` 与 HANDOFF 那一节。**只有全透明的代理窗口可以留这个 flag。**
14. **Siri 文字上的点击层（`siriTouch`）不会让路**：`applyBallTouchability()` 只切代理窗口，
   球里有文字时那条文字带仍可触摸 → agent 注入到那一带的点击会被它吃掉。改门控时记得带上它。
15. **门控必须"一处收口"，不能一个窗口一个补丁**：`applyAgentYield()` 是唯一入口，
    **以后新增任何可触摸窗口，都要在它里面登记**（透明层进 `yieldFlag`，可见层进 `yieldRegion`），
    否则新窗口就会重演"让位失效"。查漏办法：`bash android/tools/ball-yield-verify.sh --with-search`。
16. **`applyBallTouchability()` 不许再在第一行 return**：老版本 `ballTouch == null` 就整段退出，
    于是代理窗口一旦没加成功，连其它窗口的让路也一起死了（日志里只有一行 `add ball touch failed`）。
    现在代理窗口那段单独判空，后面照跑。
17. **可见窗口别用 `FLAG_NOT_TOUCHABLE` 做让路**（第 13 条的延伸）：换来的是"贴纸感"，而且摘掉
    flag 未必还原。可见窗口用 `yieldRegion`（空可触摸区域），透明窗口才用 flag。

## 6. 已知限制

- ACT 的 mtime 精度是**秒级**：同一秒内的两次信号可能 mtime 相同（日志去重会少打一行，判定不受影响）。
- 用户手指正按着球时 agent 要注入触摸：球会在 ~60ms 内退出命中测试，用户那串手势会收到
  `ACTION_CANCEL`（表现为球突然不跟手），这是设计取舍。
- 球被系统杀掉再打开（点「悬浮于桌面」）后重新开始轮询，无状态残留。
- ~~球里有文字时，文字点击层那条带不受让路协议管~~ → **2026-10-23 已修**：`siriText` 与
  `siriTouch` 都进了 `applyAgentYield()`，实测让路期间两者都是 `notTouchable`。
- 让路只保证"球不吃手势"，不负责"球看不见"：球仍然画在屏幕上，DSH 截图里能看到它。
  要让球彻底让开视线，得靠球自己收起来 / 挪走，协议不管这个。
- 可见窗口的"可触摸区域置空"依赖 @hide API 反射；万一 OEM 又不认（日志 `yieldRegion failed`），
  会退回 `FLAG_NOT_TOUCHABLE`：让路仍成功，但那个窗口可能被压到 alpha 0.80。
- 插件侧的改动要**重启 DSH** 才生效（插件启动时 import 进内存）；App 侧装完 APK 立刻生效。
- 让路窗口（每次动作后 1.5s）里，**用户**点球/点审批按钮也会被穿过去（那 1.5s 内球这一侧
  确实"点不到"）。这是"全部让位"的必然代价：宁可漏一次用户点击，也不让 agent 的手势被吃掉。

## 7. 改动清单速查

| 文件 | 改了什么 |
|---|---|
| `FloatingBallService.java` | 玻璃层/文字层/审批底改用**空可触摸区域**（`makeTouchTransparent()`；2026-10-16 之前是 `FLAG_NOT_TOUCHABLE`，会被 ColorOS 压成 alpha 0.8）；新增代理窗口（`dsh-ball-proxy`，带 title）+ 门控（内容比对 + 本地时钟窗口 + ACK 退避 + 残留清理）；普通模式拖动夹取按球直径算 |
| `~/dsh-preset-mobile-use/lib/index.js` | `waitForBallStandDown()`（原子写 + 唯一 nonce + 20ms 轮询 ACK）+ `default:` 分支只对 click/swipe/key 调用 + guidance 里加一句（**升级会覆盖，需重打**，备份在 `patches/preset-mobile-use/index.js.patched`） |
| `/data/adb/modules/agent_mobile_use/system/bin/vd` | `ball_signal()`（原子写）+ tap/swipe/key 三处调用；`/system/bin` 是 Magisk ro 挂载，改模块里的文件、**重启后生效** |
| `patches/apply-vd-ball-guard-patch.sh` | 幂等应用上面那条 vd 补丁（含备份 `vd.bak-ballguard`） |
| `android/tools/ball-guard-verify.sh` | 球侧端到端验证（信号 → notTouchable + ACK → 恢复） |
| `android/tools/ball-guard-plugin-e2e.mjs` | 插件侧贯通验证（切真实插件代码跑，含 fail-open 用例） |
| `android/tools/ball-proxy-state.py` | 按窗口名读代理层触摸状态（两个脚本共用同一口径） |
| `FloatingBallService.java`（2026-10-23） | `applyAgentYield` / `yieldFlag` / `yieldRegion` / `setAgentYield` / `getAgentYield`；开关 `float_agent_yield`；心跳 `dsh-ball-alive`；关掉时回 `!skip:` ACK |
| `MainActivity.java` / `WatchBridge.java` / `settings.html` | 「DSH 操控时全部 UI 让位」开关（`W.getAgentYield()` / `W.setAgentYield()`） |
| `android/tools/ball-yield-verify.sh` | 全窗口让位端到端验证（平时可触摸 → 让路时全不可触摸 → 1.5s 恢复；`--off` 验证开关） |

## 8. 还没做的

- **一次真实 agent 手势的冒烟**：重启 DSH 后，让 agent 在球正上方做一次 swipe，确认
  `logcat -s FloatingBall` 里出现 `让路 1500ms` + `DISARM`，并且球没被挪走。
  （球侧与插件侧已各自端到端验证；这一步只是把两边接上真实会话再跑一遍。）
