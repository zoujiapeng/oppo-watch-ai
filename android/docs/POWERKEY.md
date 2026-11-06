# 长按电源键 → 桌面悬浮球 + 按住说话（xiaomi-phone 分支）

> **短按电源键**照旧锁屏。
> **第一次长按**：球浮出来并直接进「按住说话」（弹胶囊、弹输入法、开始录），松手什么都不发生。
> **第二次长按**：结束「按住说话」→ 输入法出字 → 发到**上一次回话**那个会话（不新建）；
> 还没有「上一次回话」时才在 `日常使用` 下开一条新的。
> **音量减键**：只做「结束识别 + 发送」，目标是**全新会话**；球没开就什么都不做 —— **绝不呼球**。
> **音量加键**：把所有悬浮界面**藏起来**（球/胶囊/对话卡/菜单/四区）；之后**弹审批**、
> **按电源键**、或者**重新打开 App** 都会让它回来。
> **不回桌面、不按 HOME** —— 只把球呼出来。

## 1. 交互与时序

```
短按电源键           → 系统自己锁屏（本方案完全不参与）
按一下音量加键       → 所有悬浮界面隐藏（不呼球、不弹任何东西）
                       恢复时机：弹审批 / 按电源键 / 打开 App
按住 ≥0.65s 松手     → 球浮出来 + 打开胶囊(状态2) + 弹输入法 + 给输入法发「按住说话 down」
松开                 → 无事发生（一直录）
再按住 ≥0.65s 松手   → 给输入法发「up」→ 输入法把识别结果打进胶囊 → 读出来
                       → 发到「上一次回话」那个会话（上次的 id 还在就续上；
                         被删了 / 从没说过话 → 退回 hold_cwd 里最新的一条 → 都没有才新建）
按一下音量减键       → 同上收麦，但目标固定是**全新会话**（cwd=hold_cwd）
                       · 球没开 / 没在按住说话 → 完全不动（音量键继续当音量键用）
                       · 不呼球、不弹胶囊、不抢任何东西
```

阈值 **0.65s** 是故意大于框架自己的长按判定（~500ms）的：框架在 500ms 会把这次按键标成
long-press，松手就不再锁屏。抢在 450ms 先动手的话，用户 460ms 松手会「球出来了、屏幕也锁了」。

**识别完全交给输入法**：不调用系统 `SpeechRecognizer`、不调用 DSH 的 `speech/transcribe`
（本机那个接口是 404，实验插件没开）。走路子是仓库里已有的 VoiceMap：

```
App → su/广播 com.pj.voicemap.TRIGGER (cmd=down/up)
    → LSPosed 模块在输入法进程里往「空格键」注入一对真实触摸（长按 = 输入法的语音键）
    → 输入法自己识别、自己出字 → 打进焦点输入框（= 球的胶囊）
    → App 读胶囊里的文字 → 发到上一次回话 / 新建会话
```

胶囊开着时输入法被 VoiceMap 设成隐身（alpha 0），所以按住说话时键盘不会糊在屏幕上。

## 2. 为什么这么绕（真机踩到的坑）

| 想做的事 | 实际结果 |
|---|---|
| 用 LSPosed 钩 SystemUI 的电源菜单 | 钩到了，但澎湃的电源菜单是 **system_server** 画的（`LegacyGlobalActions`，session=1502），钩 SystemUI 挡不住 |
| `settings put system long_press_power_key none` | 小爱不再抢长按 ✅ |
| `settings put global power_button_long_press 0` | 框架对长按**什么都不做** ✅（短按锁屏不受影响） |
| 常驻 root 监听 `/dev/input/event*` 判长按 | 可行 ✅ 只读、不 EVIOCGRAB，音量键/短按/指纹都不受影响 |
| 广播里直接 `startForegroundService` | **被后台启动限制拦**：`Background start not allowed`（冷进程必现） |
| root `am start-foreground-service` | **同样被拦**（这条策略对 shell/root 也生效） |
| root `am start` 一个 Activity | **允许** ✅ → 于是有了「跳板 Activity」：它一起来 App 就在前台，此时起前台服务就过了 |

## 3. 组成

| 文件 | 干什么 |
|---|---|
| `android/tools/PowerKeyDaemon.java` | 常驻 root 监听：读 `/dev/input/event*` 里所有带 `KEY_POWER` / `KEY_VOLUMEDOWN` 的节点；电源键按住 ≥650ms 发 `POWER_BALL`，音量减按下发 `VOLUME_SEND` |
| `android/tools/build-powerkey-daemon.sh` | → `android/assets/powerkey-daemon.jar` |
| `android/src/…/PowerBallReceiver.java` | 收**显式**广播决定「开录 / 收尾 / 音量减收尾」；直连服务失败时用 root 起跳板 |
| `android/src/…/PowerBallTrampolineActivity.java` | `Theme.NoDisplay` 无界面跳板：在 Activity 上下文里起前台服务（绕开后台启动限制）然后立刻 finish（透传 `pk_send` / `pk_from`） |
| `android/src/…/FloatingBallService.java` | `beginPowerHold()`：开胶囊 + 等 900ms 发 VoiceMap `down`；`endPowerHold(toNewSession, fromVolume)`：发 `up` + 轮询胶囊文字 + `sendTextToHoldSession()` 决定发哪儿；`onCreate` 里动态注册 `VOLUME_SEND` 接收器（随服务生死）；`isExpanded()/hideFromOutside()` 留着以后做"收球"用 |
| `android/assets/pkctl.sh` | 装机 / 开关 / 自检 / 模拟长按 / 模拟音量减 |
| `android/powerkey-hook/` | 备胎 LSPosed 模块（钩 SystemUI 电源菜单）；本机不需要，装着也无害 |

### 3.1 拉球的链路

```
PowerKeyDaemon (root)
  ├─ 电源键 ≥650ms → am broadcast POWER_BALL -n …/.PowerBallReceiver  →  PowerBallReceiver
  │     ├─ 服务没开 → Intent(pk_hold=start) 直连 startForegroundService
  │     │     └─ 700ms 后没起来 → su: am start PowerBallTrampolineActivity --es pk_hold start …
  │     │            └─ Activity 里 startForegroundService → FloatingBallService
  │     └─ 正在按住说话 → Intent(pk_hold=stop, pk_send=last) → endPowerHold(false,false)
  │           └─ 发回「上一次回话」那个会话
  ├─ 音量加按下 → am broadcast VOLUME_HIDE -p com.glslplayground.app（**隐式**）
  │     └─ 服务里的动态接收器 → applyUiHidden(true)：拆掉胶囊/菜单/四区、隐藏球窗和文字层
  └─ 音量减按下 → am broadcast VOLUME_SEND -p com.glslplayground.app（**隐式**）
        └─ FloatingBallService 里动态注册的接收器（只有球开着时存在）
              ├─ 没在按住说话 → 忽略
              ├─ 球没开 → 接收器根本不存在，广播没人收（不冷启动 App，音量键照常）
              └─ 正在按住说话 → endPowerHold(true,true) → 发到全新会话 + 把音量调回去
        （`pkctl.sh fire-vol` 是**显式**广播 → PowerBallReceiver（未在说话时直接返回），
          用来单独验「不呼球」这条；真机按键走的是上面那条隐式路）
```

### 3.2 「上一次回话」是哪儿

`FloatingBallService` 每发一句话（`askIn()`）就把 `sessionId` 记进 `watch` 偏好里的
`last_session_id`。第二次长按电源键时：

1. 记住的 id 还在（`DshApi#sessionExists` 查一次 `session/list`）→ 直接发进去，**不新建**；
2. 不在（会话被删了）→ 退回 `hold_cwd`（默认 `日常使用`）下**最新的一条**；
3. 那个目录也没有会话 → 才 `session/create` 新建一条（新 id 照样记进 `last_session_id`）。

音量减键不走上面三条：它永远 `session/create` 一条新的（cwd=`hold_cwd`），
新 id 也记进 `last_session_id` —— 也就是说音量减键开的那条，下一次长按电源键会接着它。
查询失败（DSH 刚启动 / 抖动）时**不做**「新建」决策，直接往记住的会话发，避免把上下文拆掉。

## 4. 装 / 开 / 关

```bash
su -c 'sh android/assets/pkctl.sh setup android/assets/powerkey-daemon.jar'
su -c 'sh android/assets/pkctl.sh settings'     # 只改系统设置（关小爱那条长按路）
su -c 'sh android/assets/pkctl.sh status'
su -c 'sh android/assets/pkctl.sh test'         # sendevent 模拟一次长按
su -c 'sh android/assets/pkctl.sh voltest'      # sendevent 模拟按一下音量减
su -c 'sh android/assets/pkctl.sh volup'        # sendevent 模拟按一下音量加（= 隐藏全部界面）
su -c 'sh android/assets/pkctl.sh fire-vol'     # 直接发 VOLUME_SEND（不经过 input）
su -c 'sh android/assets/pkctl.sh uninstall'
```

开机自启：`/data/adb/service.d/powerkey-ball.sh`（等 `sys.boot_completed` → 起 `PowerKeyDaemon 650`，挂了 5 秒重试）。

小爱同学：`long_press_power_key=none` 已经让它不抢长按；要连人一起停：
`pkctl.sh xiaoai off` / `on` / `purge`。

## 5. 这一轮顺手修的渲染问题

| 症状 | 根因 | 修法 |
|---|---|---|
| 球跑久了花屏 / 卡顿 | `GlassRenderer.onSurfaceChanged` 每次窗口尺寸变化都把 5 个 FBO 引用置空重建，旧 FBO **从不 glDelete** → 每切一次尺寸（以及每次 surface 重建）漏 ≈9MB 显存 | `Fbo.release()` + `releaseFbos()`，`onSurfaceChanged` 里真的删；`onSurfaceCreated`（= 换了一套 EGL 上下文）只把引用置 null、**不** glDelete（那些名字属于旧上下文，删了可能误删别人的） |
| 球被留在放大态、球里动画也变大 | 动画期间窗口尺寸每帧都变，上面那个「每帧删/建 FBO」把动画卡在半路 | 尺寸变化不再每帧重建整条管线；surface 重建时 FBO 一律重建（`onSurfaceChanged`） |
| **音量加隐藏 → 电源键恢复后，球的折射效果消失**（杀进程重开就恢复） | 隐藏走 `glView.setVisibility(INVISIBLE)`，SurfaceView 的 surface 被系统**销毁**（实测 SurfaceFlinger 里 `SurfaceView[]` 图层消失）；恢复时重建 → `getSurfaceControl()` 是**新对象**，打在它上面的 `SKIP_SCREENSHOT` 跟着旧对象一起没了。而 `renderer.selfExcluded` 还是 true（成功一次就没人复位），旧代码 `if (!selfExcluded) apply...` 永远进不去 → 抓屏把球自己拍进去 → 球折射自己的上一帧 | 判据从「打过没有(boolean)」改成「**当前这个 SurfaceControl** 打过没有」（`ensureGlassSkipScreenshot()`）；`SurfaceHolder.Callback` 在 `surfaceCreated` 立刻补打；补打成功后 `GlassRenderer.resetBackground()` 重灌背景（不然标记失效那几帧「含球」的画面会被 ping-pong 永久卡在球里） |
| 审批面板异常巨大 | 面板在胶囊还开着（窗口=全屏）时被弹出来，几何按全屏窗口算 | 审批延后到胶囊收完（900→1600ms）；胶囊没开成功时绝不往输入法注入触摸 |

### 5.1 「音量加隐藏 → 电源键恢复 → 折射消失」的复现与验证（2026-11-06 实机）

判据（都不需要肉眼）：**球被 `SKIP_SCREENSHOT` 排除时，`screencap` 是拍不到球的**。
所以「截屏里出现球」＝「抓屏把球自己拍进去了」＝折射在自采样。

```
# 修复前：音量加隐藏 → 电源键恢复
FloatingBall: ui: 隐藏全部界面（音量加）
FloatingBall: ui: 恢复界面（电源键）
（没有任何一条 skip-screenshot applied）
$ dumpsys SurfaceFlinger --list | grep 'SurfaceView\[\]'
Background for SurfaceView[]#47567      ← 新图层（旧的是 #47482）
SurfaceView[]#47565
$ screencap -p probe.png                 ← 截屏里**能看见球**（不该看见）＝球在抓屏里

# 修复后：同一套动作
FloatingBall: glass surface destroyed → surface 级标记作废，等重建后补打
FloatingBall: glass surface created → 补打 surface 级标记
FloatingBall: skip-screenshot applied: glass
FloatingBall: 背景重灌（抓屏排除补打）
$ screencap -p probe.png                 ← 截屏里看不见球了
$ touch files/ballshot → files/ball-capture.png  ← 球自己那一帧：桌面文字被折射、边缘有彩边
```

连续 3 轮「隐藏 → 恢复」：`glass surface created → 补打` ×3、`skip-screenshot applied: glass` ×3，
无 `GL_INVALID` / `Framebuffer incomplete`，球每一轮都是正常透镜。

## 6. 实机验证（2026-11-06，MI 10 Ultra / HyperOS 1.0 / Android 13）

下面是「发新会话」那版（本分支之前）的实测日志，只作为链路参考：

```
# 第一次长按（冷进程，走跳板）
PowerBall: 呼出球 + 开始按住说话
FloatingBall: 电源键：弹胶囊 + 交给输入法语音
FloatingBall: search: 进入（电源键·按住说话）… 胶囊 cx=540 cy=264
FloatingBall: 电源键：输入法语音 down
LSPosed-Bridge: VoiceMap: DOWN at (540.0,1958.175) handled=true

# 第二次长按
FloatingBall: 电源键：输入法语音 up → 等出字
FloatingBall: 电源键：输入法结果 = 「你好呀！」
FloatingBall: 电源键：发到 /data/data/com.termux/files/home/日常使用
FloatingBall: send: 新会话 cwd=…/日常使用 preset=mobile-use model=deepseek-flash/off
~/.dsh/sessions/--data-data-com.termux-files-home-字面量--/session-f08ee49a…  ← 新会话落地

# 收窗口
ball window -> 938x579 → … → 795x579（回到球，不留全屏窗口）
```

### 6.1 本分支（「上一次回话」+ 音量减）的实机验证（2026-11-06 20:48，同机 MI 10 Ultra / HyperOS 1.0）

做法：`pm install -r glsl-playground.apk` + `pkctl.sh setup android/assets/powerkey-daemon.jar`，
再用 `am broadcast POWER_BALL`（等价第一次长按）+ `input text` 往胶囊里塞一句 +
`pkctl.sh voltest`（sendevent 注入真实音量减键，走隐式广播→服务内动态接收器）驱动。
**真实按键语音识别没测**（要人说话），但「收麦→取字→发哪儿」这条链是通的。

```
# ① 音量减：按住说话中间按一下 → 发到全新会话
FloatingBall: 电源键：输入法语音 down
PowerKeyDaemon: 音量减键 → 发 VOLUME_SEND @/dev/input/event2
FloatingBall: 收到 VOLUME_SEND：holdActive=true
FloatingBall: 音量减 → 结束按住说话 → 发到全新会话
FloatingBall: 音量减键：输入法语音 up → 等出字
FloatingBall: 电源键：输入法结果 = 「testvol1」
FloatingBall: 音量减键：发到全新会话（cwd=/data/data/com.termux/files/home/日常使用）
FloatingBall: send: 新会话 cwd=…/日常使用 preset=mobile-use model=deepseek-flash/off perm=danger-full-access
FloatingBall: search: 退出（电源键说话完成）          ← 胶囊收起，球回初始态
#   → ~/.dsh/sessions/…日常使用/session-bfa3effe-…  ← 真的一条新会话，消息落库

# ② 再走一次电源键收尾 → 发回「上一次回话」（不是新建）
PowerBall: 第二次长按 → 结束按住说话 → 发回上一次回话
FloatingBall: 电源键：输入法结果 = 「testlast1」
FloatingBall: 电源键：回到上一次回话 session-bfa3effe-0ae2-481d-9fef-bd5d2a7278b6
FloatingBall: send: 发到已有会话 session-bfa3effe-0ae2-481d-9fef-bd5d2a7278b6（null）
#   → 同一条会话里 testvol1 / testlast1 都在；这一轮没有再出现 "send: 新会话"

# ③ 球没开时按音量减 → 不冷启动、不冒球
pid(before)=空 → voltest → pid(after)=空
PowerKeyDaemon: 音量减键 → 发 VOLUME_SEND @/dev/input/event2
PowerKeyDaemon: sh: am broadcast -a …VOLUME_SEND -p com.glslplayground.app … exit=0
#   → App 进程没起来（动态接收器不存在，广播没人收）
```

**编译验证**：`bash android/build.sh` ✅、`bash android/tools/build-powerkey-daemon.sh` ✅、
`aapt2 dump xmltree .build/glsl-playground.apk --file AndroidManifest.xml` 里能看到
`MODIFY_AUDIO_SETTINGS`。（`VOLUME_SEND` 不进 manifest —— 它是服务里动态注册的，见 §3.1。）
测试写完已删掉 `日常使用/testvol1.txt`、`testlast1.txt` 和那条测试会话。

**还没测的**：
- 真的对着麦克风说话（`holdActive` 那段用的是胶囊里的现成文字）；
- 「记住的会话被删 → 退回该目录最新一条」这条兜底（只做了代码路径检查）；
- 音量调回那条日志（注入的按键没让系统真的降音量，`now >= baseline` 直接跳过）。

## 7. 已知边界

- **必须**：默认输入法装着且 VoiceMap 模块启用（`vmctl.sh status` 里 `mod_enabled=1`、`scope=<当前输入法>`）；
  换输入法要在设置页重新「启用模块」。
- 锁屏状态下长按：球浮在锁屏上，桌面在锁屏后面；有密码/指纹还是要自己解一次。
- 胶囊没开成（审批球/菜单占着屏）时**不会**触发输入法语音 —— 免得把空格长按点到别人界面上。
- `sendevent` 往 `uinput-goodix` 注入 KEY_POWER 不触发框架的锁屏策略，只能验「监听→广播→拉球」这段。
- **音量减键不是「抢键」**：daemon 只读 `/dev/input`，系统照常处理音量减，所以按一下会先把媒体音量
  按下去一格；App 收尾后把音量调回按住说话开始时的值（`restoreMusicVolumeIfFromVolumeKey`）。
  如果按住说话期间你手动调过音量，收尾时会被拉回开始时的值 —— 这是「那一下是发送键」的代价。
- 音量减只有**按下**就触发（不等长按）；球没开 / 没在按住说话时 `PowerBallReceiver` 直接返回，
  不会呼球、不会弹胶囊、不会改 `last_session_id`。
- 「上一次回话」记的是**球发过话的会话**（`askIn` 时写 `last_session_id`）；在 DSH 桌面端新开的会话
  不算，除非你也用球往那条会话里发过一句。
