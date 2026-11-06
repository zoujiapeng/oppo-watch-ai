# 长按电源键 → 桌面悬浮球 + 按住说话（xiaomi-phone 分支）

> **短按电源键**照旧锁屏。
> **第一次长按**：球浮出来并直接进「按住说话」（弹胶囊、弹输入法、开始录），松手什么都不发生。
> **第二次长按**：结束「按住说话」→ 输入法出字 → 自动发到 DSH 的 **`日常使用`** 工作目录下的新会话。
> **不回桌面、不按 HOME** —— 只把球呼出来。

## 1. 交互与时序

```
短按电源键           → 系统自己锁屏（本方案完全不参与）
按住 ≥0.65s 松手     → 球浮出来 + 打开胶囊(状态2) + 弹输入法 + 给输入法发「按住说话 down」
松开                 → 无事发生（一直录）
再按住 ≥0.65s 松手   → 给输入法发「up」→ 输入法把识别结果打进胶囊 → 读出来 → 发「日常使用」
```

阈值 **0.65s** 是故意大于框架自己的长按判定（~500ms）的：框架在 500ms 会把这次按键标成
long-press，松手就不再锁屏。抢在 450ms 先动手的话，用户 460ms 松手会「球出来了、屏幕也锁了」。

**识别完全交给输入法**：不调用系统 `SpeechRecognizer`、不调用 DSH 的 `speech/transcribe`
（本机那个接口是 404，实验插件没开）。走路子是仓库里已有的 VoiceMap：

```
App → su/广播 com.pj.voicemap.TRIGGER (cmd=down/up)
    → LSPosed 模块在输入法进程里往「空格键」注入一对真实触摸（长按 = 输入法的语音键）
    → 输入法自己识别、自己出字 → 打进焦点输入框（= 球的胶囊）
    → App 读胶囊里的文字 → DSH 新会话(cwd=日常使用)
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
| `android/tools/PowerKeyDaemon.java` | 常驻 root 监听：读 `/dev/input/event*` 里所有带 `KEY_POWER` 的节点，按住 ≥650ms 发一条 `POWER_BALL` 广播 |
| `android/tools/build-powerkey-daemon.sh` | → `android/assets/powerkey-daemon.jar` |
| `android/src/…/PowerBallReceiver.java` | 收广播决定「开录 / 收尾」；直连服务失败时用 root 起跳板 |
| `android/src/…/PowerBallTrampolineActivity.java` | `Theme.NoDisplay` 无界面跳板：在 Activity 上下文里起前台服务（绕开后台启动限制）然后立刻 finish |
| `android/src/…/FloatingBallService.java` | `beginPowerHold()`：开胶囊 + 等 900ms 发 VoiceMap `down`；`endPowerHold()`：发 `up` + 轮询胶囊文字 + 发「日常使用」；`isExpanded()/hideFromOutside()` 留着以后做"收球"用 |
| `android/assets/pkctl.sh` | 装机 / 开关 / 自检 / 模拟长按 |
| `android/powerkey-hook/` | 备胎 LSPosed 模块（钩 SystemUI 电源菜单）；本机不需要，装着也无害 |

### 3.1 拉球的链路

```
PowerKeyDaemon (root)
  └─ am broadcast POWER_BALL  →  PowerBallReceiver (App 进程)
        ├─ 服务没开 → Intent(pk_hold=start) 直连 startForegroundService
        │     └─ 700ms 后没起来 → su: am start PowerBallTrampolineActivity --es pk_hold start
        │            └─ Activity 里 startForegroundService → FloatingBallService
        └─ 正在按住说话 → Intent(pk_hold=stop) → endPowerHold()
```

## 4. 装 / 开 / 关

```bash
su -c 'sh android/assets/pkctl.sh setup android/assets/powerkey-daemon.jar'
su -c 'sh android/assets/pkctl.sh settings'     # 只改系统设置（关小爱那条长按路）
su -c 'sh android/assets/pkctl.sh status'
su -c 'sh android/assets/pkctl.sh test'         # sendevent 模拟一次长按
su -c 'sh android/assets/pkctl.sh uninstall'
```

开机自启：`/data/adb/service.d/powerkey-ball.sh`（等 `sys.boot_completed` → 起 `PowerKeyDaemon 650`，挂了 5 秒重试）。

小爱同学：`long_press_power_key=none` 已经让它不抢长按；要连人一起停：
`pkctl.sh xiaoai off` / `on` / `purge`。

## 5. 这一轮顺手修的渲染问题

| 症状 | 根因 | 修法 |
|---|---|---|
| 球跑久了花屏 / 卡顿 | `GlassRenderer.onSurfaceChanged` 每次窗口尺寸变化都把 5 个 FBO 置空重建，旧 FBO **从不 glDelete** → 每切一次尺寸漏 ~50MB 显存 | 加 `Fbo.release()` / `releaseFbos()` / `releaseGl()`；`onSurfaceChanged` 只在**抓帧尺寸**变了才重建 |
| 球被留在放大态、球里动画也变大 | 动画期间窗口尺寸每帧都变，上面那个「每帧删/建 FBO」把动画卡在半路 | 同上：尺寸没变就复用；`onSurfaceCreated` 重进时先 `releaseGl()` 再建 |
| 审批面板异常巨大 | 面板在胶囊还开着（窗口=全屏）时被弹出来，几何按全屏窗口算 | 审批延后到胶囊收完（900→1600ms）；胶囊没开成功时绝不往输入法注入触摸 |

## 6. 实机验证（2026-11-06，MI 10 Ultra / HyperOS 1.0 / Android 13）

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

## 7. 已知边界

- **必须**：默认输入法装着且 VoiceMap 模块启用（`vmctl.sh status` 里 `mod_enabled=1`、`scope=<当前输入法>`）；
  换输入法要在设置页重新「启用模块」。
- 锁屏状态下长按：球浮在锁屏上，桌面在锁屏后面；有密码/指纹还是要自己解一次。
- 胶囊没开成（审批球/菜单占着屏）时**不会**触发输入法语音 —— 免得把空格长按点到别人界面上。
- `sendevent` 往 `uinput-goodix` 注入 KEY_POWER 不触发框架的锁屏策略，只能验「监听→广播→拉球」这段。
