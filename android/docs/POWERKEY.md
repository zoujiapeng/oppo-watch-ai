# 长按电源键 → 桌面悬浮球（xiaomi-phone 分支）

> 一句话：**短按电源键照旧锁屏；按住 0.65s 松手，水晶球浮到桌面上；球已经展开成胶囊/六区时，再长按一次就收球。**
> 小爱同学原来占着「长按电源键」这条路，本方案把那条绑定关掉了（不动它别的功能）。

## 1. 交互

| 手上的动作 | 系统 / 球的行为 |
|---|---|
| 短按电源键（松得快） | 系统自己锁屏 —— 本方案完全不插手 |
| 按住 ≥0.65s 再松 | 亮屏 → 球立刻浮出来 → 退锁屏 → 回桌面 |
| 球已开着，且是**胶囊 / 六区 / 菜单**状态 | 再长按一次 → 收球（窗口、通知、采集一起停） |
| 球已开着，还是**一颗光球** | 再长按 → 回桌面 + 摆正（球不动） |

阈值 0.65s 是**故意大于**框架自己的长按判定（~500ms）的：框架在 500ms 会把这次按键标成
long-press，松手就不再锁屏。若我们抢在 450ms 先起球，用户 460ms 松手就会
「球出来了、屏幕也锁了」——这就是上一版被骂的那件事。

## 2. 为什么不能只靠 Xposed 钩子

在澎湃（HyperOS 1.0 / Android 13，小米 10 Ultra，`M2009J1SC`）上实测：

| 想拦的东西 | 实际在哪 | 结论 |
|---|---|---|
| 长按电源键弹出的电源菜单 `LegacyGlobalActions` | `Window{… LegacyGlobalActions} mSession=1502:1000` → **system_server** | SystemUI 钩子钩不到；钩 system_server 风险太大 |
| 小爱同学被长按唤起 | `settings get system long_press_power_key` = `launch_voice_assistant` | 一条 settings 就能关 |
| 框架自己的长按动作 | `settings get global power_button_long_press` | 设 0 = 长按什么都不做 |

于是最终方案是**「让框架彻底不管长按，自己在旁边听键盘」**：

```
settings put system long_press_power_key none          # 小爱不再抢
settings put global power_button_long_press 0          # 框架长按 = 什么都不做
settings put secure power_menu_long_press_for_assist 0 # 长按呼助手那档也关
```

短按锁屏是 `interceptPowerKeyUp` 那条路，上面三条都不碰它（实测短按照样灭屏/亮屏）。

## 3. 组成

| 文件 | 干什么 |
|---|---|
| `android/tools/PowerKeyDaemon.java` | 常驻（root / app_process）：读 `/dev/input/event*` 里所有带 `KEY_POWER` 的节点，按住 ≥holdMs 发一条广播。**只读，不 EVIOCGRAB**，所以音量键、短按、指纹都不受影响 |
| `android/tools/build-powerkey-daemon.sh` | 上面那个 → `android/assets/powerkey-daemon.jar` |
| `android/src/…/PowerBallReceiver.java` | 收广播：没球就 `startForegroundService` + 亮屏/退锁屏/回桌面；球在且展开就 `stopSelf` 收球 |
| `android/src/…/FloatingBallService.java` | 新增 `isExpanded()`（胶囊/六区/菜单/搜索层任一在屏）与 `hideFromOutside()`；`onDestroy` 补上「把代理窗/胶囊/菜单全摘掉」——不收干净会留下一层看不见但吃触摸的窗口 |
| `android/assets/pkctl.sh` | 装机 / 开关 / 自检 / 模拟长按 |
| `android/assets/pk_lspd.py` | 只改 LSPosed 库里本模块那两行（备胎用） |
| `android/powerkey-hook/` | **备胎**：LSPosed 模块，钩 SystemUI 的 `GlobalActionsComponent#handleShowGlobalActionsMenu` 等三个入口，把电源菜单换成同一条广播。只在「框架仍然走 SystemUI 电源菜单」的 ROM 上有意义 |

### 3.1 起球那一下的时序

```
daemon: am broadcast POWER_BALL           (root)
   └─ PowerBallReceiver (App 进程，冷启动 ~0.4s)
        ├─ startForegroundService(FloatingBallService)   ← 球先出来
        └─ su -c "input keyevent 224; wm dismiss-keyguard;
                  sleep 0.9; input keyevent 3; sleep 0.45; input keyevent 3"
```

`am start -a MAIN -c HOME` 在本机不可靠（焦点不跟过来），HOME 键要发两条：
刚唤醒时第一条常被锁屏吃掉（真机验过）。

## 4. 装 / 开 / 关

```bash
# 一次性装好（root）
su -c 'sh android/assets/pkctl.sh setup android/assets/powerkey-daemon.jar'

# 只改系统设置（关小爱那条长按路）
su -c 'sh android/assets/pkctl.sh settings'

# 状态 / 启停
su -c 'sh android/assets/pkctl.sh status'
su -c 'sh android/assets/pkctl.sh daemon-start|daemon-stop|daemon-restart'

# 模拟一次长按（往 uinput 节点写 0.9s 的 KEY_POWER，不碰系统设置）
su -c 'sh android/assets/pkctl.sh test'

# 彻底停用
su -c 'sh android/assets/pkctl.sh uninstall'
```

开机自启靠 Magisk：`pkctl.sh` 会写 `/data/adb/service.d/powerkey-ball.sh`，
里面等 `sys.boot_completed` → 确认 App 还装着 → 起 `PowerKeyDaemon 650`，
挂了就 5 秒后重来。

### 4.1 把小爱同学彻底拿掉（可选，默认没做）

`long_press_power_key=none` 已经让它不抢电源键；要连人一起停：

```bash
su -c 'sh android/assets/pkctl.sh xiaoai off'    # pm disable-user 四个包
su -c 'sh android/assets/pkctl.sh xiaoai on'     # 恢复
su -c 'sh android/assets/pkctl.sh xiaoai purge'  # 用户 0 卸载（install-existing 可回）
```

## 5. 实机验证记录（2026-11-06，MI 10 Ultra / HyperOS 1.0 / Android 13）

```
# 起球
sendevent /dev/input/event2 1 116 1 … 0.9s … 1 116 0
PowerKeyDaemon: KEY_POWER down @/dev/input/event2
PowerKeyDaemon: 长按到点 → 发 POWER_BALL
PowerBall: power-ball 触发：… running=false
PowerBall: FloatingBallService 已拉起
dumpsys activity services → ServiceRecord ×1（isForeground=true）
dumpsys window → mCurrentFocus=com.miui.home/.launcher.Launcher   ← 桌面
dumpsys window windows → 2 个 com.glslplayground.app 的 APPLICATION_OVERLAY 窗

# 收球（am stopservice 走同一条 onDestroy）
Service stopped → ServiceRecord ×0，APPLICATION_OVERLAY ×0   ← 没有残留窗口

# 短按锁屏（框架原样）
power_button_long_press=0 下：短按 → mWakefulness=Asleep；再短按 → Awake
```

## 6. 已知边界

- **锁屏状态下**长按：球会浮在锁屏上面，但桌面在锁屏后面 —— 有密码/指纹时必须自己解锁一次，
  `wm dismiss-keyguard` 只对无密码锁有效。
- `sendevent` 往 `uinput-goodix` 写 KEY_POWER 不会触发框架的锁屏/长按策略（实测），
  所以它只能用来验「监听 → 广播 → 起球」这段，验不了「短按锁屏」。
- 真机物理电源键走 `qpnp_pon`（`/dev/input/event1`，带 `KEY_POWER` + `KEY_VOLUMEDOWN`）；
  守护进程同时监听 `event1`（物理）与 `event2`（注入），物理那条不用改任何代码。
- 备胎 LSPosed 模块在「框架长按 = 电源菜单」的 ROM 上才需要；本机现在不需要，
  装着也不影响（钩子方法压根不会被调用）。
