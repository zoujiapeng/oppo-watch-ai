# 给 AI 的作业单：把「AI 误触悬浮球」在这台手机上彻底修好

> **这份文件是写给执行它的 AI 看的，不是给人看的教程。** 如果你是接到"同步 GitHub 后
> 自动把这台手机的悬浮球误触问题修好"的那一方：**从 §1 开始按顺序做，别跳步、别自创流程**。
> 遇到与本文件冲突的旧文档（`BALL_TOUCH_GUARD.md` 的历史章节等），**以本文件为准**。
>
> 仓库：`https://github.com/zoujiapeng/oppo-watch-ai`（分支 `main`）

---

## 0. 一句话任务

这台手机装了悬浮球（`com.glslplayground.app`）和 DSH。用户让 AI 用它点外卖时，**点击会落到
球上**（用户说"误触到玻璃"）。这是"让路机制"的**两半只装了一半**导致的：

```
 球（APK）  ←─ 收到信号就把所有窗口退出命中测试
    ▲
    │  信号（TCP 127.0.0.1:3181，退化为文件）
    │
 DSH 插件（mobile 工具）←─ 注入每次 click/swipe/key 之前先发信号
```

**只装球 = 永远没人发信号 = 点击落球。** 插件那半在 `dsh-preset-mobile-use/lib/index.js`，
不在 npm 上，必须从仓库装并打让路补丁。而且 **2026-10-24 之前**的插件版本在"DSH 不是 root
启动"的机器上会**静默失效**（读 `/sdcard/Android/data/<球包名>` 被 Android 11+ 拒），
所以修复必须同时升级 **APK** 和 **插件**，缺一不可。

---

## 1. 主路径：三条命令（先做这个，不要先读别的）

```bash
cd ~ && git clone https://github.com/zoujiapeng/oppo-watch-ai.git 2>/dev/null || (cd ~/oppo-watch-ai && git pull --ff-only)
cd ~/oppo-watch-ai && git log --oneline -1
bash ai-ball-guard-setup.sh
```

最后一行会打印 `RESULT: PASS` 或 `RESULT: FAIL（N 处）`，退出码 0/1。

- **PASS** → 跳到 §2（还有最后一步：重启 DSH，脚本默认不重启）。
- **FAIL** → 跳到 §3 的分支表，按对应行处理，处理完**重跑同一条命令**直到 PASS。

脚本是**幂等**的：重复跑不会重复安装、不会卸载、不会改球的任何设置。

---

## 2. PASS 之后：让插件真正生效（必做）

插件是 ESM，**DSH 启动时才 import 进内存**。所以要么让用户重启，要么用脚本的安全重启：

```bash
bash ai-ball-guard-setup.sh --restart-dsh
```

它会：捕获当前 `dsh` 进程的完整命令行 → 3 秒后 kill → 2 秒后用同样的命令行 `setsid nohup`
重新拉起，并把输出追加到 `~/dsh.log`（球读 token 就靠这个文件）。

> ⚠️ 如果你自己就跑在这个 DSH 里：`--restart-dsh` 会**让你这条会话断一下**（3~5 秒）。
> 断之前先把要做的事做完；重连后继续按 §2.1 验证。不允许因为怕断线就跳过重启——
> 不重启 = 插件还是旧的 = 问题没修。

### 2.1 重启后的验收（三条可观察事实，缺一条不算修好）

```bash
# ① 球的 TCP 门控在应答（期望：ALIVE <pid> <0|1> 3181）
printf 'PING\n' | nc -w 1 127.0.0.1 3181

# ② 让 agent 自己点一下（任何 click 都行），然后看流水：期望 via=tcp:3181 ok=1
tail -1 ~/dsh-ball-guard.log

# ③ 全链路体检：期望最后一行是「全部环节通过」
bash ~/oppo-watch-ai/android/tools/ball-gate-doctor.sh | tail -3
```

② 里如果是 `via=file` 也算通（说明这台机器 DSH 是 root 或目录可读）；如果日志根本没有新行，
说明**当前 DSH 里还是旧插件** → 重启没成功，回 §3 的 `F1`。

---

## 3. FAIL 分支表（按脚本打印的 NEXT 对号入座）

### F0 · `缺 <工具>`
```bash
pkg install git nodejs netcat-openbsd pnpm python  # 缺哪个装哪个；nc 的包名是 netcat-openbsd
```

### F1 · 插件补丁/注册失败（脚本第 ② 步）
```bash
cat /tmp/bg-plugin-install.log          # 先看真实报错
ls -la ~/.dsh/profiles/web/package.json # 常见根因：profile 还没生成（DSH 没起过）
```
- **profile 不存在** → 先起一次 DSH（任意方式），再重跑 `bash ai-ball-guard-setup.sh`。
- **clone 失败** → `git clone https://github.com/AcidGr/dsh-preset-mobile-use.git ~/dsh-preset-mobile-use`。
- **权限** → `~/dsh-preset-mobile-use` 必须归 Termux 用户；`chown -R $(id -u):$(id -g) ~/dsh-preset-mobile-use`。

单独跑插件那一半（脚本已封装，但它能给出更细的日志）：
```bash
bash ~/oppo-watch-ai/patches/install-ball-guard-plugin.sh          # 装/升级
bash ~/oppo-watch-ai/patches/install-ball-guard-plugin.sh --check  # 只看状态
```

### F2 · TCP 门控没应答（脚本第 ① 步：`tcp_alive` 超时）
按顺序排除，**不要跳**：

1. 球服务在不在：`dumpsys activity services com.glslplayground.app | grep FloatingBallService`
   - 没有 → `su -c "/system/bin/am start-foreground-service -n com.glslplayground.app/.FloatingBallService"`
   - 仍没有 → 让**用户**打开 App → 设置 →「悬浮于桌面」（这一步需要人，别代替他点）
2. 装的是不是新 APK：`logcat -d -s FloatingBall | grep "gate tcp"`
   - 没有这行 → 还是老 APK：`pm install -r ~/oppo-watch-ai/glsl-playground.apk`，重启服务再看
3. 端口被占：球会顺延到 3182/3183。`printf 'PING\n' | nc -w 1 127.0.0.1 3182`；
   插件默认会探 3181/3182/3183，所以顺延不影响插件。
4. 还不行：`logcat -d -s FloatingBall | tail -30`，把 `gate tcp` 相关行原样贴给用户。

### F3 · e2e 未全通
```bash
node ~/oppo-watch-ai/android/tools/ball-guard-plugin-e2e.mjs click
```
- `[FAIL] 没拿到确认：tcp 端口 …` → 回 F2。
- `[FAIL] 球仍可触摸` → 球在让路期间有窗口还能点：跑
  `bash ~/oppo-watch-ai/android/tools/ball-yield-verify.sh --with-search`，
  哪个窗口 `touchable` 就说明 `applyAgentYield()` 里没登记它（这是**仓库 bug**，不是环境问题）→
  在 issue 里贴这行输出，不要自己改球的代码。
- `[??] 文件通道不可用` 是**允许**的（换机时常见），只要 TCP 那两项过就算通。

### F4 · doctor 某环 `[!!]`
doctor 的环号就是诊断路径：

| 环 | 含义 | 断了的处理 |
|---|---|---|
| 1 | 球在跑 | F2 第 1 条 |
| 2 | DSH 以哪个 uid 跑 | 只是信息；`root` 最省事，非 root 就走 TCP（插件已支持） |
| 3 | 三处心跳 | 都没有 → 球是老 APK 或没跑 |
| 4 | 用 DSH 的 uid 实测读目录 | `Permission denied` 是**预期**，靠 TCP 就够；不用修 |
| 5 | 插件有没有让路代码 | 回 F1 |
| 6 | 让位档位 | 设置页「AI 操控时的防误触」不能是**关闭**；其它机型让位不灵就切**闪开**（`echo flash > …/files/yield-mode`）；或删掉 `dsh-ball-guard-off` 文件 |
| 7 | TCP 门控 | 回 F2 |
| 8 | 此刻窗口可触摸状态 | 只在动作那几秒应当全 `notTouchable`，平时可触摸是正常 |
| 9 | 最近一次动作走的通道 | 没有新行 = 还是旧插件（重启 DSH） |

### F5 · 用户坚持"点了还是穿过去"
先拿证据再动手：`bash ai-ball-guard-setup.sh && bash android/tools/ball-gate-doctor.sh`，
把 **环 7 + 环 9 的原文**和 `~/dsh-ball-guard.log` 最后 3 行贴给用户。
如果 `via=tcp:3181 ok=1` 而用户仍说穿过去，那不是让路问题（球已让路），而是**球挡住了下面
控件的可见区域**——让用户把球拖开，或把球收起来；不要改让路逻辑。

---

## 4. 硬性禁止（做了会引入更贵的问题）

1. **不要 `pm uninstall` 球**。设置（`shared_prefs/watch.xml`）、采集授权都在这儿；脚本只 `-r` 覆盖装。
2. **不要改球的 shared_prefs**，也不要替用户点设置页开关（那是用户的设备）。
3. **不要用 `input tap` / `sendevent` 绕开 `mobile` 工具**去"验证"球——那会把真实状态搞乱，
   而且 agent 的正式路径就是 `mobile` 工具。
4. **不要给可见窗口加 `FLAG_NOT_TOUCHABLE`**：ColorOS/一加 会把整窗 alpha 钉在 0.80（球的
   "半透明贴纸"就是这么来的）。可见窗口只能用"空可触摸区域"。
5. **不要把信号目录改到 `/data/local/tmp`**：`shell_data_file` 标签，球（untrusted_app）
   能读不能写，ACK 会 `EACCES`。官方通道是 TCP，兜底才是文件。
6. **不要改 DSH 源码**，只在 `dsh-preset-mobile-use` 这个 bundle 上打补丁。
7. **不要跳过 DSH 重启**（§2）就宣布修好。

---

## 5. 完成判据（贴给用户的结论必须是这四条都成立）

```text
[1] pm 里球的 lastUpdateTime ≥ 本仓库 APK 的时间（即装了新 APK）
[2] printf 'PING\n' | nc -w 1 127.0.0.1 3181   → ALIVE ...
[3] ~/dsh-ball-guard.log 最后一行 ok=1（via=tcp:3181 或 via=file）
[4] ball-gate-doctor.sh 末行 = 全部环节通过
```

四条全绿才算修好。任何一条不满足，按 §3 对应分支继续，**不要**用"应该可以了"结论收尾。

## 6. 回报给用户的格式（直接照抄，替换数值）

```text
已修好（本机 <机型/Android 版本>）。
- 球 APK：已升级到仓库版，TCP 门控 127.0.0.1:3181 应答（pid=xxxx）
- 插件：dsh-preset-mobile-use 已打让路补丁并注册进 web profile
- DSH：已重启，最近一次 agent 点击走 tcp:3181，握手 xx ms
- 体检：doctor 九环全过；e2e 全过
用户侧不需要做别的；以后连点被球盖住的按钮时，先把球拖开即可。
```

## 7. 附：这台机器上各文件干嘛的

| 路径 | 作用 |
|---|---|
| `ai-ball-guard-setup.sh` | 一条命令装/修/验证（本文件 §1 的主入口） |
| `glsl-playground.apk` | 球（含回环 TCP 门控；`gate tcp listening 127.0.0.1:3181`） |
| `patches/preset-mobile-use/index.js.patched` | DSH 插件里"让路那半边"的补丁后源码 |
| `patches/install-ball-guard-plugin.sh` | 把上面那份装进 `~/dsh-preset-mobile-use` 并注册到 profile |
| `android/tools/ball-gate-doctor.sh` | 九环体检（球/uid/心跳/权限/插件/开关/TCP/窗口/最近通道） |
| `android/tools/ball-guard-plugin-e2e.mjs` | 从真实插件源码切出协议代码跑真机贯通 |
| `android/tools/ball-yield-verify.sh` | 让位档端到端：让位期间所有球窗口不可触摸、1.5s 后恢复 |
| `android/tools/ball-flash-verify.sh` | **闪开档端到端**：mode=flash → flash=1 → 球区像素变化 ~29% → RELEASE 后复原 |
| `android/docs/BALL_GUARD_CROSS_DEVICE.md` | 根因、实测证据、设计取舍（要理解为什么这么改就读它） |
| `android/docs/BALL_TOUCH_GUARD.md` | 历史实现细节（让位窗口登记表、踩过的坑） |

协议速查（球侧 `127.0.0.1:3181`，一行一条）：

```
PING                    → ALIVE <pid> <yield 0|1> <port>
YIELD <nonce> [holdMs]   → OK <nonce>        （让路，默认 4000ms）
RELEASE <nonce>          → RELEASED <nonce>  （动作做完立刻恢复）
STATUS                   → STATE <yield> <untilMs> yield=<0|1> mode=<off|touch|flash> flash=<0|1>
```

回滚（只在确实要退回旧行为时）：

```bash
# 插件：把备份覆盖回去
ls ~/dsh-preset-mobile-use/lib/index.js.bak-* | tail -1
cp <上面那个文件> ~/dsh-preset-mobile-use/lib/index.js && <重启 DSH>
# 临时关掉联动（不卸载、不改文件）
touch /sdcard/Android/data/com.glslplayground.app/files/dsh-ball-guard-off
```
