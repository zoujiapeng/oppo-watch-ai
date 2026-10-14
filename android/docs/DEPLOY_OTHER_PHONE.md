# 换一台手机装这套东西（给人和 AI 看的落地指南）

> 目标读者：**要在另一台手机上把这个仓库跑起来的人，或者帮他操作的 AI**。
> 这份文档只讲「怎么在陌生机器上一次装成」，原理和内部实现看
> [`DSH_ASSISTANT.md`](DSH_ASSISTANT.md) / [`HANDOFF.md`](HANDOFF.md)。
>
> 仓库：`https://github.com/zoujiapeng/oppo-watch-ai`，工作分支 `feat/dsh-liquid-glass-siri`。

---

## 0. 一句话前提

这套东西**不是独立 App**，是「本机 DSH 的前端显示器」。所以目标手机必须同时具备：

```
① Android 手机 + ② root（Magisk / KernelSU 都行）+ ③ 标准 Termux + ④ 本机跑着 DSH
```

四条缺任何一条都跑不完整（缺 root 有降级路径，见 §5）。

---

## 1. 先做「能不能用」的体检（30 秒，别急着装）

在目标手机的 Termux 里逐条跑，**全绿再往下**：

```bash
# ① Termux 家目录必须是标准路径（App 只认这个路径读 DSH 日志）
echo $HOME            # 期望：/data/data/com.termux/files/home

# ② root 可用（App 会自己调 su，这里先确认 su 本身没问题）
su -c id              # 期望：uid=0(root)

# ③ App 能用 root 读到 Termux 私有目录（关键！）
su -c "ls -la $HOME/dsh.log"     # 期望：看到文件（没这个文件说明 DSH 没起过）

# ④ DSH 在跑，且能拿到启动 token
grep -o 'token=[A-Za-z0-9_-]*' $HOME/dsh.log | tail -1   # 期望：token=xxxx

# ⑤ DSH 端口（默认 3080）
grep -o 'http://127.0.0.1:[0-9]*' $HOME/dsh.log | tail -1

# ⑥ 构建工具齐不齐（如果要自己编译 APK）
command -v javac d8 aapt2 apksigner keytool python3 curl
```

**任何一条不对 → 先解决它，不要装 App 硬试。**

| 体检失败 | 怎么修 |
|---|---|
| `$HOME` 不是标准路径 | 换了 Termux 分支（如一些改包名的版本）→ 需要改代码里的 `TOKEN_LOGS` 路径后重新编译 |
| `su -c id` 不是 root | 装 Magisk / KernelSU，并在 root 管理器里**给 Termux 本体授权**（不是给 App） |
| 读不到 `dsh.log` | 先起一次 DSH；或 root 管理器拒绝了 Termux 的 su |
| 没有 token | DSH 版本太老或日志被轮转，重跑一次 `dsh web`（它会重新打印带 token 的地址） |
| 端口不是 3080 | 记下来，装完在设置页「DSH 地址」里改 |

---

## 2. 装 DSH（如果目标机还没装）

DSH 就是 DeepSeek Harness，正常按它的方式装到 Termux 里、能 `dsh web` 打开浏览器界面即可。
只要**满足两点**：服务监听 `127.0.0.1:<port>`，并且启动时把 `http://127.0.0.1:<port>/?token=…`
写进 `~/dsh.log`（默认行为）。本仓库不改 DSH 一行代码，走的是它给浏览器用的同一套 HTTP/WS 接口。

> 已验证版本：DSH `0.2.0-rc.2`。更新的版本理论上兼容（接口没变），但**没实测过**。

---

## 3. 装 App（两条路，选一条）

### 路线 A：直接从 Release / 仓库拿现成 APK（最快）

```bash
cd ~
curl -L -o glsl-playground.apk \
  https://github.com/zoujiapeng/oppo-watch-ai/raw/feat/dsh-liquid-glass-siri/glsl-playground.apk
# 直链已实测可用：HTTP 200，906247 字节（约 885KB）
pm install -r glsl-playground.apk
```

> ⚠️ 这个 APK 是**本机 debug key 签名**的，跨机器安装时如果目标机已装过**别的签名**的同包名
> （`com.glslplayground.app`）会冲突。先 `pm uninstall com.glslplayground.app` 再装。

### 路线 B：在目标机上自己编译（要求改动代码时用这条）

```bash
cd ~
pkg install git
git clone -b feat/dsh-liquid-glass-siri https://github.com/zoujiapeng/oppo-watch-ai.git
cd oppo-watch-ai

# 构建依赖（全新 Termux 一个都没有，需要联网装）
pkg install openjdk-17 aapt2 apksigner python curl git
# d8 随 openjdk/aapt2 的依赖链提供；先 `command -v d8` 确认，没有再补

bash android/build.sh          # 产出 ./glsl-playground.apk（首次会下载约 21MB 的 android.jar）
pm install -r glsl-playground.apk
```

构建脚本注意点：

- 首次构建会 `curl` 下载 `~21MB` 的 `android.jar` 到 `.build/`（需要能访问 GitHub；`.build/` 没入库）。
  网络不通就手动把 android-30 的 `android.jar` 放到 `.build/android.jar`。
- 重新构建**不会**动你的设置（设置存在 `shared_prefs`）。

---

## 4. 首次运行：必须手动开的开关（这一步最容易漏）

装完 App 是**没有任何反应的**，按顺序做：

1. **打开 App**（桌面图标 `GLSL Playground`，或它同时是 HOME/Kiosk）。
2. 进 **「⚙ 设置」**。
3. **「悬浮水晶球」→「悬浮于桌面」开关打开** ← 不打开球永远不会出现。
4. **采集方式**：
   - `Root 直采`（默认，需要 root）：无弹窗、无录屏标记、球内实时折射不闪。
   - `录屏 MediaProjection`：会弹一次系统录屏授权，球照常工作但球内画面略滞后。
5. 进 **「DSH 语音助手」** 区块：
   - 开关**默认就是打开的**（`SiriAssistant.enabled` 默认 `true`），不用特意去开；
   - **确认 DSH 地址**：默认 `http://127.0.0.1:3080`，体检第 ⑤ 步如果不是这个端口就改掉；
   - 点 **「问一句（测试链路）」** —— 不需要麦克风就能验证整条链路通不通，球里出字 = 通了。

   > 注意区分两个开关：**「Siri 模式 (文字形变球)」默认是关的**（`float_siri` 默认 `false`），
   > 它只影响"球会不会随文字变形"。助手干活时会自己临时接管，所以不打开也能用；
   > 想让它常驻形变（比如手动输入文字看效果）再打开。
6. 回到桌面，**双击水晶球**开始说话（说完再双击发送，长按取消，拖动只移动，单击不响应）。
   回答出来之后：**单击球内文字 = 钉住**（不再自动消失，末尾有 📌），再单击解除；长按文字也解除。
   在文字上按住拖动仍然是拖球。

> 首次双击说话会弹一次**麦克风授权**，允许即可（授权后长期有效）。
> 想免弹窗：`adb shell dpm set-device-owner com.glslplayground.app/.AdminReceiver`（见 §6）。

---

## 4.5 全新安装的默认值（哪些要手动、哪些已经对）

装完 `pm install` 后**没有 `shared_prefs`**，所以一切走代码默认值（已核对源码）：

| 设置项 | 新装默认 | 要不要手动改 |
|---|---|---|
| 「悬浮于桌面」 | **关**，且**不持久化**（只有 `FloatingBallService.running` 这个内存标志） | ✅ **每次都要打开**：装完、重启手机、被系统杀进程之后，都得回设置页再点一次（见 §7 第 1 条） |
| 采集方式 `float_capture_src` | `1` = Root 直采 | 无 root 才改成录屏 |
| 帧率 `float_fps` | `60` | 可不动（95 更顺，费电） |
| 低延迟采集 `float_lowlat` | 关 | 想省事打开（用常驻 CapLive） |
| DSH 地址 `siri_assistant/endpoint` | `http://127.0.0.1:3080` | 端口非标才改 |
| DSH 语音助手开关 `enabled` | **开** | 不用动 |
| 镜像当前会话 `mirror` | 开 | 不想让浏览器里的对话进球就关掉 |
| 朗读回答 `speak` | 关 | 想听就读打开 |
| 「Siri 模式 (文字形变球)」`float_siri` | 关 | 助手会自己临时接管，一般不用开 |
| 思考中圆点：「环半径 / 亮度 / 边缘辉光」 | `90px / 1.10 / 1.30` | 已有调好的观感，不用动 |

**最小手动动作只有两个**：① 打开「悬浮于桌面」；② 端口非 3080 时改「DSH 地址」。

---

## 5. 降级路径：目标机没有 root

| 能力 | 有 root | 无 root |
|---|---|---|
| 悬浮水晶球本体 | ✅ | ✅ |
| 球内实时折射真实桌面（不闪） | ✅（Root 直采 + SKIP_SCREENSHOT） | ✅ 但用 MediaProjection，略滞后、有系统录屏标记 |
| 免录屏授权弹窗 | ✅ | ❌ 每次启动服务要授权一次 |
| DSH 语音助手（双击说话、逐字回球） | ✅ | ⚠️ **不行** —— 读 `dsh.log` 拿 token 依赖 root 读取 Termux 私有目录 |
| 麦克风静默授权 | ✅（device owner） | ❌ 弹一次框 |

**无 root 时助手为什么不行**：App 读 DSH 启动 token 有两级尝试 —— 直接读 `~/dsh.log`（普通 App
无权限）→ 失败后走 `su -c head`（root）。无 root 两级都失败，换不到 cookie，连不上 DSH。
若目标机无 root 但**确实要用助手**，可考虑把 DSH 换成固定的静态 token（需改 `DshHttpTransport`）。

---

## 5.5 球是「会话级」的：重启、重装、被杀之后要重新打开

这是最容易让人以为"装坏了"的一条 —— **代码里没有开机自启，`悬浮于桌面` 这个开关也没写进
shared_prefs**，球的存活完全靠 `FloatingBallService.running` 这个内存标志。所以下面这些情况
球都会消失，且都**只需回设置页把开关再点一次**（不用重装）：

- 装完 APK（`pm install` 会杀掉旧进程）
- **重启手机**
- 被国产 ROM 的后台管理杀掉（§6）
- 手动在设置页关掉过

```bash
# 判断球现在到底在不在（不用看屏幕）
dumpsys activity services com.glslplayground.app | grep -E "ServiceRecord|isForeground"
#   有 FloatingBallService 且 isForeground=true → 球在跑
#   什么都没有 → 球没起，去设置页打开「悬浮于桌面」
```

> 想让它真的开机自启，需要加一个 `BOOT_COMPLETED` 的 BroadcastReceiver（权限已声明但**没有实现**）。
> 这是目前已知的功能缺口，需要的话可以补。

---

## 6. 国产 ROM 的额外坑（OPPO / 小米 / 华为 / vivo 必看）

系统会静默杀掉悬浮窗服务，表现为「球闪一下就没了」：

- **显示悬浮窗**：设置 → 应用 → 本应用 → 「显示悬浮窗 / 后台弹出界面」**允许**
- **自启动**：加白名单（ColorOS「自启动管理」、MIUI「自启动」、EMUI「应用启动管理」）
- **电池优化**：设为「不优化 / 允许后台运行」，否则息屏后球被回收
- **通知权限**（Android 13+）：球是前台服务，通知被禁会连带服务不稳

设成 **device owner** 可一并解决授权类问题：

```bash
# 前提：这台设备上没有其它账号/工作资料（否则 dpm 会报错），
#       并且要在 App 第一次启动、创建数据之前设置 —— 干净装完立刻设最省事
#       设置失败就先 pm uninstall 再重装，然后马上执行
adb shell dpm set-device-owner com.glslplayground.app/.AdminReceiver
```

手机上没有 USB 也能用：Termux 里 `pkg install android-tools` 拿 `adb`，
再通过「开发者选项 → 无线调试」配对连接本机（`adb pair 127.0.0.1:<port>`）。

---

## 7. 出问题怎么查（AI 优先按这个顺序）

```bash
# ① 服务到底起没起（球是前台服务）
dumpsys activity services com.glslplayground.app | grep -E "ServiceRecord|isForeground"

# ② 悬浮窗有没有真的加上（看 APPLICATION_OVERLAY 的窗口与尺寸）
dumpsys window windows | grep -A3 APPLICATION_OVERLAY

# ③ 应用日志（球/助手/DSH 的关键信息都在这几个 tag）
logcat -d | grep -E "FloatingBall|GlassRenderer|SiriAssistant|DshTransport" | tail -50

# ④ DSH 侧有没有被连上（cookie 换取成功会打印）
logcat -d | grep "authenticated against"

# ⑤ 采集进程（Root 直采低延迟模式会常驻一个 CapLive）
ps -A | grep -i caplive
```

| 症状 | 最可能的原因 |
|---|---|
| 球完全不出现（刚重启过/刚装完） | 「悬浮于桌面」是**会话级**开关，重开一次即可（§5.5） |
| 球完全不出现（其它情况） | 「悬浮于桌面」没开 / 悬浮窗权限没给 / ROM 杀了服务 |
| 球闪一下消失 | 自启动白名单、电池优化、通知权限 |
| 球在，但球内是黑/透明 | 采集没起来：看 ⑤ 有没有 CapLive，或换「录屏」模式试 |
| 球内画面是静止的 | 采集进程死了 → 关掉再打开「悬浮于桌面」重启服务 |
| 助手连不上（日志 `没找到 DSH 启动 token`） | 无 root / `$HOME` 非标准路径 / DSH 没起过 |
| 助手连不上（日志 `DSH cookie 名称异常`） | DSH 版本不兼容或端口写错 |
| 双击球没反应 | 球必须处于 Siri 模式；单击不响应是设计（双击=说话/发送） |
| 说话没转写 | 麦克风权限没给；或 DSH 侧没配语音插件（`speech/transcribe`） |
| 回答不逐字出现 | WebSocket 连不上会退回 850ms 轮询（仍可用，只是不逐字） |

**调试图方便**：把任意文字写进 `/data/local/tmp/ballshot`，球会把它当「球内文字」显示并截一帧到
`/data/data/com.glslplayground.app/files/ball-capture.png`；空文件则只截一帧。
（注意：App 与 Termux 处于不同 mount namespace，**Termux 里可能看不见 `/data/local/tmp`**，
那就用 `su -c` 操作。）

---

## 8. App 认死的路径与默认值（改代码 / 排错时对照）

| 项 | 值 | 在哪 |
|---|---|---|
| DSH 默认地址 | `http://127.0.0.1:3080` | `DshHttpTransport.DEFAULT_ENDPOINT`（设置页可改） |
| 启动 token 的日志路径 | `$HOME/dsh.log`、`dsh-restart.log`、`dsh-watchdog.log` | `DshHttpTransport.TOKEN_LOGS` |
| 家目录 | `/data/data/com.termux/files/home` | 同上（硬编码） |
| 流式接口 | `ws://127.0.0.1:<port>/api/remote.mux` + `session/follow{assistantStream:true}` | `DshWebSocket` / `DshHttpTransport` |
| 认证 | `GET /?token=…` → 303 + 签名 cookie `dsh-auth-<sha256(authority)>`（30 天） | `DshHttpTransport.authenticate` |
| 明文 HTTP 白名单 | 只给 `127.0.0.1 / localhost / ::1` | `res/xml/network_security_config.xml` |
| 采集兜底命令 | `while screencap; do :; done` | `RootCapture.CMD` |
| 低延迟采集 | `app_process … CapLive <w> <h>`，协议 = `screencap`：每帧 `[w,h,fmt,cs]` + RGBA | `RootCapture.useAppProcess` / `tools/CapLive.java` |
| 球内文字上限 | 600 字（镜像缓存 1200 字） | `SiriAssistant.BALL_TEXT_MAX` / `DshHttpTransport.MIRROR_BUF_MAX` |
| 文字清空 | 安静 1.5s 后清空并回 IDLE | `SiriAssistant.IDLE_CLEAR_MS` |
| 球高上限 | 屏幕 70% | `FloatingBallService.measureSiriTargets` |
| 包名 | `com.glslplayground.app` | `AndroidManifest.xml` |
| targetSdk / minSdk | 28 / 21 | 同上 |

---

## 9. 已知边界（跨机型最可能出问题的地方，按风险排序）

1. **私有 API 反射**：低延迟采集走 `SurfaceControl.captureDisplay` /
   `SurfaceControl$DisplayCaptureArgs` / `HardwareBuffer` 反射，靠 `exemptHiddenApis()` 绕隐藏 API 限制。
   Android **15/16** 上签名可能变化 → 反射失败 → 自动退回 `screencap` 循环（能用，帧率/延迟差）。
   日志里看 `caplive.jar 提取失败，回退 screencap`。
2. **ROM 后台管理**：见 §6，这是「球不稳定」的头号原因。
3. **DSH 版本**：只在 `0.2.0-rc.2` 实测过。接口若变，症状是 token 换不到或 `session/follow` 没数据。
4. **非标准 Termux**：包名/家目录不同 → 需要改 `TOKEN_LOGS` 与家目录常量后重编。
5. **免 root**：助手功能不可用（§5）。
6. **签名冲突**：debug key 签名，跨机器重装同包名应用要先卸载。

---

## 10. 给 AI 的最短行动清单

```
1) 体检：$HOME 标准？su 可用？su 能读 $HOME/dsh.log？token 能 grep 到？端口多少？
2) 装：curl 拉 glsl-playground.apk → pm install -r（冲突先 uninstall）
3) 开：设置页 →「悬浮于桌面」打开 → DSH 地址填对 →「DSH 语音助手」打开
4) 验：点「问一句（测试链路）」，球里出字 = 整条链路通
5) 如果不行：logcat 抓 FloatingBall / DshTransport 的日志，对照 §7 的表
6) 球不稳：先查 §6 的 ROM 白名单/电池优化，再查采集进程
```

**不要做的事**：不要改 DSH 源码；不要假设 App 能直连远端 DSH；不要在没 root 的机器上纠结语音助手。
