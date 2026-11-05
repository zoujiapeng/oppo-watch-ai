# VoiceMap 集成（设置页里的「语音按键」分组）

把 [zoujiapeng/voicemap](https://github.com/zoujiapeng/voicemap) 整个接进本 App 的设置页：
装/卸、LSPosed 启用与作用域、重启输入法进程、校准空格键、键盘隐身、按住说话，
全部从 `settings.html` 点，不用再开电脑、不用记 broadcast 命令。

## 一、为什么这么接

voicemap 的能力全在两个进程里：

| 角色 | 包名 | 干什么 |
|---|---|---|
| 触发 App | `com.pj.voicemap` | 一个按钮 + 一个输入框；按住按钮 = 按住输入法空格 |
| LSPosed 模块 | `com.pj.voicemap.ime` | 注入输入法进程：窗口 alpha=0（键盘隐身）+ 往键盘 View 发 ACTION_DOWN/UP |

模块的对外接口只有**明文广播** `com.pj.voicemap.TRIGGER`（`cmd=down/up/calibrate/dump/
stealthon/stealthoff/register`），模块开关只在 LSPosed 自己的 sqlite 库
（`/data/adb/lspd/config/modules_config.db`）里。两者都要 root。

所以集成层是三层：

```
settings.html「语音按键 · VoiceMap」分组
   ↓ window.Watch.vm*()                （JS 桥）
WatchBridge.vm*()  →  VoiceMapControl （Java：单线程池排队跑 root）
   ↓ su -c sh <files>/vmctl.sh <子命令>（assets 释放到私有目录，root 每次重写）
vmctl.sh  →  am broadcast …           （命令进输入法进程里的模块）
          →  vm_lspd.py（python3 写 LSPosed 库：启用 + 作用域）
          →  pm install / kill <ime> / magisk --install-module
```

不用 Java 直连 LSPosed 库：App 进程没有 root，而且 LSPosed 的库结构会变；脚本能现场改。

## 二、文件

```
settings.html                          +「语音按键 · VoiceMap」分组（状态 / 按钮 / 按住说话 / 日志）
android/assets/vmctl.sh                root 侧控制面（status / enable / restart / cmd / trigger / install / log）
android/assets/vm_lspd.py              写 LSPosed 库：query / enable <ime> / disable
android/assets/voicemap.apk            触发 App（来自 voicemap 仓库 apk/，已签名）
android/assets/voicemap-ime.apk        模块（同上）
android/src/.../VoiceMapControl.java   缓存状态 + 所有 root 动作
android/src/.../WatchBridge.java       vm* 桥接方法
```

`vmctl.sh` / `vm_lspd.py` / 两个 APK 都在 APK 的 `assets/` 里，App 运行期释放到
`/data/user/0/com.glslplayground.app/files/` —— **离线可装、可修**。

## 三、设置页里有什么

**状态**（每秒读 `W.vmStatus()` 的缓存，不碰 UI 线程）
触发 App / 隐身模块 / 模块已启用 / 作用域 / LSPosed 框架 / 默认输入法 / 输入法进程 PID。

**动作**
- 安装 / 更新 VoiceMap（两个 APK 从 assets 装，`pm install -r -g`）
- 启用模块（写 LSPosed 库，作用域 = 下面选中的输入法；默认当前默认输入法）
- 停用模块
- 重启输入法进程（`kill <pid>`，下次弹键盘时进程重启才会注入模块）
- 重新校准空格键 / 清校准 / 导出键盘视图树（`cmd=calibrate/clearcal/dump`）
- 键盘隐身：开 / 临时显形（`cmd=stealthon/stealthoff`）
- 本 App 设为隐身目标（`cmd=register --es pkg com.glslplayground.app`）
- 按住说话：pointerdown → `cmd=down`，pointerup → `cmd=up`
- 下载并安装 LSPosed（zygisk 版 zip → `magisk --install-module`，重启生效）
- 卸载 VoiceMap / 打开 VoiceMap App / 重新自检 / 看日志（`logcat -s LSPosed-Bridge`）

**输入法作用域**：从 `ime list -a -s` 读本机所有输入法，点一下选中，
再点「启用模块」把作用域写成那一个包名（换输入法不用改代码）。

## 四、用起来（顺序）

1. Magisk 里给本 App 授权 root（弹窗点「允许」；否则整组都是灰色的「拿不到 root」）。
2. 没有 LSPosed → 点「下载并安装 LSPosed」→ **重启**。
3. 点「安装 / 更新 VoiceMap」。
4. 选输入法 → 「启用模块」→ 「重启输入法进程」。
5. 「本 App 设为隐身目标」→ 在下面那个输入框里点一下 → 「按住说话」。

验收：`logcat -s LSPosed-Bridge` 里应出现

```
VoiceMap: loaded into com.sohu.inputmethod.sogou.xiaomi
VoiceMap: stealth on (showWindow) alpha=0.0
VoiceMap: DOWN at (540.0,553.5) handled=true
```

`dumpsys window | grep -A3 'InputMethod}'` 里窗口应是 `alpha=0.0`。

## 五、坑

- 模块只对「长按空格 = 语音」的中文输入法有效（搜狗/百度/讯飞/QQ）；Gboard 的语音在
  工具栏麦克风键，自校准会失败 —— 那时改用「校准」→「导出视图树」手点坐标。
- 作用域必须就是**当前默认输入法**的包名；换输入法要重新「启用模块」+「重启输入法进程」。
- 触发时本 App 必须前台且输入框有焦点（InputConnection 才是它，字才有地方落）。
- 按住说话的 down/up 是两次广播，中间别切 App。
- Magisk 的授权是**按 uid** 的：重装本 App 不会换 uid，但如果被拒过一次，
  授权框可能要点「允许」，或者 Magisk → 超级用户 → 本 App 打开开关。

## 六、本次实机踩到的（都写进代码/脚本了）

- **按住说话现在有两条路**，面板上「引擎：」那行写明这次走哪条：
  - `输入法语音（VoiceMap，键盘隐身）`：要 root + LSPosed + 模块已启用 + 点过「本 App 设为隐身目标」；
  - `本 App 语音识别（不需要 root）`：`AndroidAsr` 系统识别 → 不行退回 `VoiceCapture` + DSH
    `speech/transcribe`，结果经 `window.vmOnVoice(evt, text)` 回调进测试输入框。
    2026-11-06 实机验证：`AndroidAsr: onResults「你好」` → 输入框里出现「你好」。
- **绝对不要直接写 `/data/adb/magisk.db`**。`magisk --sqlite "UPDATE policies …"` 会把这个
  Magisk 31.0（Rust 守护进程）写崩，之后 `su` 一律 `Cannot connect to daemon`。
  授权只能在 Magisk 界面里点，或者改 `magisk` 官方客户端都没有的接口——没有。
  崩了只能重启（重启会重新拉起 magiskd，boot 补丁不受影响）。
- **刚开机就 `pm install` 会失败**：`Failure calling service package: Failed transaction (2147483646)`，
  或 MIUI 的 `SecurityException: need MANAGE_USERS`（XSpace 取默认用户）。等几秒重试即可，
  `tools/post-reboot-recover.sh` 已带 5 次重试。
- **Magisk 弹授权框的瞬间**，`su -c` 会立刻返回空（不是超时）：面板会显示
  「拿不到 root 输出（空）」，点一下页面上的「重新自检」或等下一次轮询就正常了。

## 七、LSPosed 1.9.2 上「写库 ≠ 生效」（最坑的一条，实机踩到）

`vm_lspd.py` 只改 LSPosed 自己的 sqlite 库。库改对了（管理器里能看到模块、开关是开的、
作用域也勾着），**但输入法进程里仍然没有模块** —— `logcat -s LSPosed-Bridge` 一片空白。
原因：lspd 在内存里缓存模块表，并且要给模块 dex 做一次优化，直接写库这两步都不会发生。

实测有效的最小动作（按顺序）：

1. 管理器得先真的装上（模块 zip 里的 `manager.apk` 不会自己出现在 pm 列表里）：
   ```bash
   su -c "pm install -r /data/adb/modules/zygisk_lsposed/manager.apk"
   ```
2. `vmctl.sh enable`（写库：`enabled=1` + 作用域）。
3. 打开 LSPosed 管理器 → 模块 → VoiceMap IME Bridge → **把「启用模块」关一次再打开**
   （这一步让 lspd 重新读库、做 dexopt、通知 zygisk）。
4. `vmctl.sh restart` 杀掉输入法进程；下次弹键盘时新进程才会带模块起来。
5. 验收：`logcat -s LSPosed-Bridge` 里应出现
   ```
   Loading legacy module com.pj.voicemap.ime from /data/app/…/base.apk
   Loading class com.pj.voicemap.ime.VoiceMapHook
   VoiceMap: loaded into com.sohu.inputmethod.sogou.xiaomi
   VoiceMap: receiver registered
   ```

之后再按住说话，实测日志（2026-11-06 本机）：

```
VoiceMap: stealth on (showWindow) alpha=0.0 flags=0x81800118   ← 键盘窗口透明
VoiceMap: DOWN at (540.0,1958.175) handled=true                ← 真按在搜狗空格键上
AudioRecord: stop mSessionID=113                               ← 输入法自己录了音
VoiceMap: UP after 2754ms handled=true
sInput: VoiceInputResultDispatcher startVoiceView              ← 输入法自己的语音结果视图
```

同时 `dumpsys window windows | grep -A1 'InputMethod}'` 里是
`ty=INPUT_METHOD … alpha=0.0` —— 键盘全隐身，屏幕上只有本 App。

LSPosed 1.9.2 的库结构和老版本不同：`modules(mid, module_pkg_name, apk_path, enabled)`
（**没有** `auto_include`）、`scope(mid, app_pkg_name, user_id)`。`vm_lspd.py` 现在按
`PRAGMA table_info` 动态取列，新旧版本都能用。


