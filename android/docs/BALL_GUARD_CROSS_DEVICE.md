# 悬浮球让路 × 跨机型失效（"本机能用，换台手机 AI 就点穿玻璃"）

> 一句话结论：**让路是"两次握手"，一半在 APK，一半在 DSH 的插件里；而且插件那半只有
> 在"DSH 进程能读到球的外部私有目录"时才会发信号。** 本机 DSH 是 **root** 起的，
> 所以一直好用；换到一台用普通 Termux 用户起 DSH 的手机，插件 `ballRunning()` 直接
> `EACCES` → 它以为"球没在跑" → **一个信号都不发、还不报错** → 点击照旧落在球上。
>
> 2026-10-24 的修法：球侧加一条**回环 TCP 门控**（127.0.0.1:3181，不碰文件系统），
> 插件改成 **TCP → 文件 → su 中继** 三级通道，并且**任何一级都失败时必须打 `[ball]` 行**
> （不再静默）。实测：Termux uid 下握手 18ms 成功；老代码同一场景 1ms 静默跳过。

---

## 1. 链路（五个环，断哪一环现象都一样）

```
        ┌─ 环 A：DSH 里装了带让路逻辑的插件吗？（dsh-preset-mobile-use/lib/index.js）
        │        没装 = 没有人发信号。这是换机后最常见的状态。
        │
模型 → mobile 工具 ─┤
        │
        │      ┌─ 环 B：插件进程能**看到**球吗？（心跳 / TCP 探活）
        └──────┤        看不到 → 老版本会假装"球没在跑"直接放行（静默）
               │
               ├─ 环 C：信号到得了球吗？（文件权限 / 端口 / su）
               │
               ├─ 环 D：球把**所有窗口**退出命中测试了吗？（applyAgentYield 登记表）
               │
               └─ 环 E：注入本身通不通？（input / InputManager，与球无关）
```

本机五环全通；换机断在 **B/C**，而且断得无声无息。

## 2. 证据（都是本机实测，命令可复现）

### 2.1 本机的 DSH 是 root 跑的 —— 这就是"只有本机生效"的原因

```console
$ ps -A -o user,pid,args | grep dsh
root  10974  .../dsh/lib/bin.js --profile web ... --port 3081
```

球是普通应用（`ownerUid=10390`），信号目录是它的**外部私有目录**。Android 11+ 上
`/sdcard/Android/data/<pkg>` 只对属主 App 开放 —— 对 root 不设限，对普通 App uid 设限。

### 2.2 把本机进程降到 Termux 的 uid（10326）后，文件通道全线 EACCES

```console
$ su 10326 -c 'cat /sdcard/Android/data/com.glslplayground.app/files/dsh-ball-alive'
cat: ...: Permission denied
$ su 10326 -c 'echo probe > /sdcard/Android/data/com.glslplayground.app/files/.probe'
/system/bin/sh: can't create ...: Permission denied
$ su 10326 -c 'stat /sdcard/Android/data/com.glslplayground.app'
stat: '...': Permission denied
```

### 2.3 老插件在这个 uid 下的行为：**1 毫秒、静默、什么都没发**

```console
$ su 10326 -c 'node ... 调用旧版 waitForBallStandDown("click")'
{"uid":10326,"ok":true,"skipped":"ball-not-running","reason":"","dt":1}
```

`ok=true` + `skipped` → 调用方**不会**打 `[ball]` 提示，用户和 AI 都看不到任何异常，
动作照发，于是手势落在球上：**用户看到的就是"误触到玻璃"**。

### 2.4 新插件（TCP 通道）在同一个 uid 下：18ms 握手成功

```console
$ su 10326 -c 'node ... 调用新版 waitForBallStandDown("click")'
{"uid":10326,"ok":true,"via":"tcp:3181","reason":"","dt":18}
$ su 10326 -c 'DSH_BALL_GATE_PORT=59999 node ...  （故意关掉 TCP）'
{"uid":10326,"ok":false,"via":"","reason":"tcp 端口 59999 都没人应答，文件心跳在 6 个候选目录里也读不到（...）","dt":474}
```

关掉 TCP 后**不再是静默**：`ok=false` + 逐条写明哪条通道怎么失败 → 结果里出现
`[ball] 让路确认未收到（...）`。

### 2.5 球侧：门控一次完整握手（真机窗口状态）

```console
$ printf 'YIELD tcp-verify-1\n' | nc -w 2 127.0.0.1 3181
OK tcp-verify-1
# 代理窗口 + 文字层 → NOT_TOUCHABLE；玻璃层本来就是 <empty>
$ printf 'RELEASE tcp-verify-1\n' | nc -w 2 127.0.0.1 3181
RELEASED tcp-verify-1
# 0.3s 内恢复 alpha=1.00 / touchable
```

logcat（`logcat -s FloatingBall`）：

```
tcp yield: tcp-verify-1 → 让路 4000ms
agent yield -> 全 UI 让路（看得见、点不到）
yieldFlag View -> NOT_TOUCHABLE
agent gate -> DISARM (球只显示)
tcp release: tcp-verify-1
agent yield -> 恢复球窗口触摸
agent gate -> ARM (球可触摸)
```

## 3. 改了什么

### 3.1 球侧（`FloatingBallService.java`）

| 改动 | 为什么 |
|---|---|
| 新增**回环 TCP 门控**：`ServerSocket` 绑 `127.0.0.1:3181`（被占就 3182/3183），行协议 `PING` / `YIELD <nonce> [holdMs]` / `RELEASE <nonce>` / `STATUS` | 不碰文件系统 → 不受 Android 11+ 的 `/sdcard/Android/data` 限制、不受挂载命名空间、不受 SELinux 文件标签影响。两边只要有 INTERNET 就能通 |
| 心跳移到 `ballTouch == null` **之前**，且**每个目录都写** | 老版本第一行 `if (wm == null \|\| ballTouch == null) return;`：代理窗口没加成功时连心跳都没有 → 插件永远认为"球没在跑"。这就是第二条静默死路 |
| `gateDirs()`：外部私有 + **内部私有** 两份都写（心跳 / ACK / 读 ACT） | 内部私有目录只有 root 读得到，但 root 侧 `su -c cat /data/user/0/<pkg>/files/dsh-ball-alive` 永远命中；文件通道因此多一条命 |
| 心跳内容改成 JSON：`{"ts","pid","port","tcp","yield","touch","dirs"}` | 插件能自己发现**真实端口**与**让位开关状态**，换机不用改常量 |
| `gateUntilMs` / `lastActNonce` 改 `volatile`；TCP 线程改完立刻 `post(pumpAgentGate)` | TCP 线程与主线程共享状态，且让路要**立刻**生效（不必等 60ms 轮询） |
| `onDestroy()` 里 `stopGateTcpServer()` | 服务停 = 端口释放，不留僵死监听 |

文件协议原样保留：老插件、`vd` CLI 补丁、以及"DSH 以 root 跑"的场景继续走文件。

### 3.2 DSH 侧（`dsh-preset-mobile-use/lib/index.js`）

| 改动 | 为什么 |
|---|---|
| 通道顺序：**TCP → 文件（6 个候选目录，直连或 `su` 中继）→ 报错** | 换机型时总有一条通；不再有"默认目录读不到就当球不存在" |
| `ballRunning()` 从"静默 false"改成三态：TCP 活着 / 心跳新鲜 / 明确失败并给原因 | 失败必须对调用方可见 |
| 候选目录加了 `/data/media/0/...`、`/data/user/0/...`、`/data/data/...`、`/data/user_de/0/...` | `/data/media/0` 是 `/storage/emulated/0` 的**后端**，root 侧绕开 FUSE；后两个是球的内部私有目录（球现在也写） |
| 写信号：直连失败 → `su -c`（base64 过管道，避免引号/nonce 字符被 shell 吃掉） | 目标机是 root 的，这条基本必通 |
| 动作发完立刻 `RELEASE <nonce>` | TCP 通道下用户不用干等 4 秒；`RELEASE` 的 nonce 对不上就不理（防串） |
| 全断时 `reason` 里列出**每条通道**的失败原因 | 换机排错只看 `[ball]` 这一行就够 |
| 环境变量：`DSH_BALL_GATE_PORT` / `DSH_BALL_GUARD_DIRS` / `DSH_BALL_PKG` | 非常规端口/包名不用改源码 |

### 3.3 工具

| 文件 | 用途 |
|---|---|
| `patches/install-ball-guard-plugin.sh` | 在**任意手机**上把插件装好/升级好（备份 + 语法自检 + 注册进 profile + 重启提示）。`--check` 只看不改 |
| `android/tools/ball-gate-doctor.sh` | 八环体检，逐环 `[ok]/[!!]` + 一句结论。换机第一件事就跑它 |
| `android/tools/ball-guard-plugin-e2e.mjs` | 从**真实插件源码**切出协议代码跑：TCP 握手 → 窗口 `notTouchable` → RELEASE 恢复 → 文件通道 → 全断诊断 |

## 4. 换机部署（三步，缺一步就是"只有一半"）

```bash
# ① 装球（本仓库 glsl-playground.apk，2026-10-24 之后才有 TCP 门控）
pm install -r glsl-playground.apk
#    打开 App → 设置 →「悬浮于桌面」；Android 11+ 还要给「显示悬浮窗」

# ② 装 DSH 侧插件（这一步就是"是不是还要在 DSH 上装插件"的答案：必须装）
git clone https://github.com/AcidGr/dsh-preset-mobile-use.git ~/dsh-preset-mobile-use
dsh plugin add --profile web "link:$HOME/dsh-preset-mobile-use"
bash ~/oppo-watch-ai/patches/install-ball-guard-plugin.sh     # 打上让路补丁

# ③ 重启 DSH（插件是 ESM，启动时才进内存），然后体检
bash ~/oppo-watch-ai/android/tools/ball-gate-doctor.sh
node ~/oppo-watch-ai/android/tools/ball-guard-plugin-e2e.mjs click
```

体检里 **环 5** 是"插件有没有让路代码"，**环 7** 是"球的 TCP 端口在不在"。
这两环任一不过，AI 的点击就会落到球上——不用看别的。

## 5. 排错表

| 现象 | 断点 | 处理 |
|---|---|---|
| 结果里连 `[ball]` 都没有，点击直接落球上 | 环 A（插件是原版）或环 C（老插件静默跳过） | `bash patches/install-ball-guard-plugin.sh --check`，没装/是旧版就装，然后**重启 DSH** |
| `[ball] 让路确认未收到（tcp 端口 …都没人应答，文件心跳 … 读不到）` | 环 C（球没跑 / 球是老 APK / DSH 非 root 且无 su） | 先看球在不在（环 1），再看 `nc 127.0.0.1 3181`（环 7）；老 APK 就重装 |
| `[ball] 让路确认未收到（… 500ms 内没有 ACK）` | 环 C 半通：信号写进去了，球没回（球卡死/被冻结） | 看 `logcat -s FloatingBall`；ROM 冻结后台就去加白名单 |
| 握手成功但窗口仍 `touchable` | 环 D（`applyAgentYield` 没登记新窗口） | `bash android/tools/ball-yield-verify.sh --with-search`，把新窗口登进 `applyAgentYield()` |
| 球"变半透明贴纸" | 给**可见窗口**加了 `FLAG_NOT_TOUCHABLE` | 可见窗口只能用 `yieldRegion`（空可触摸区域），见 `BALL_TOUCH_GUARD.md` §5 第 13/17 条 |
| 端口 3181 被占 | 环 C | 球会顺延 3182/3183，插件按心跳/默认表探测；也可在设置里改 `float_gate_port` |

## 6. 已知限制

- TCP 只绑 `127.0.0.1`，不接受外部连接；**同一台机器上任何应用**都能发 `YIELD`（后果只是"球
  短暂点不到"），没有鉴权——这是刻意的取舍：鉴权要多一份共享密钥分发，收益为零。
- `YIELD` 默认 hold 4000ms；插件发完动作会 `RELEASE`。插件崩溃/被杀时球最多 4 秒后自动恢复。
- 文件通道仍然需要"能读那个目录"（root 或属主）：非 root + 无 su 时它会失败——但现在**会说出来**。
- 多用户（工作资料 / 副用户）下球的目录是 `/data/user/<id>/0/<pkg>/files`，候选表里的
  `userId` 只覆盖 0；这种情况用 TCP（不受影响）或 `DSH_BALL_GUARD_DIRS` 指定。
- 让路期间**用户**点球也会被穿过去（1.5~4s 窗口），这是"宁可漏一次用户点击，也不让 agent
  的手势被吃掉"的取舍，见 `BALL_TOUCH_GUARD.md` §6。

## 7. 复现实验（想自己验一遍）

```bash
# 1) 用 Termux 的 uid 模拟"非 root DSH"，证明文件通道死了
BALL_UID=$(stat -c %u /sdcard/Android/data/com.glslplayground.app 2>/dev/null || echo 10326)
su "$BALL_UID" -c "cat /sdcard/Android/data/com.glslplayground.app/files/dsh-ball-alive"   # 期望：Permission denied

# 2) 同一个 uid 调新版插件代码 → 应该走 tcp:3181
su "$BALL_UID" -c "node android/tools/ball-guard-plugin-e2e.mjs click"                     # 期望：via=tcp:3181
```
