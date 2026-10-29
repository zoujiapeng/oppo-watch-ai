# AGENTS.md —— 给在这个仓库里干活的 AI

本文件会被 DSH 的 `agent-instructions` 插件在会话开始时自动读进上下文。**先读完这 20 行再动手。**

## 这个仓库是什么

一套跑在 **Android（Termux + root）** 上的东西：

- `android/` —— 「悬浮水晶球」APK 的源码（`FloatingBallService` 一万行是主体），
  它把本机 DSH 的会话显示成一个液态玻璃球，并作为 DSH 的前端显示器；
- `patches/` —— 给 DSH 侧插件（`dsh-preset-mobile-use`，提供 `mobile` 工具）打的补丁；
- `patches/preset-mobile-use/index.js.patched` —— **当前生效的插件源码**（含"球让路"那半边）；
- `android/tools/` —— 真机验证脚本（不需要重启 DSH）；
- `android/docs/` —— 设计与踩坑记录；`AI-FIX-BALL-GUARD.md` 是**作业单**。

## 如果你接到的任务是「AI 误触悬浮球 / 点在玻璃上 / 点了没反应」

**不要自己分析、不要先读别的文档**，直接按作业单执行：

```bash
cd ~/oppo-watch-ai && git pull --ff-only
bash ai-ball-guard-setup.sh            # 末行 RESULT: PASS / FAIL
```

- PASS → 还要 `bash ai-ball-guard-setup.sh --restart-dsh`（插件是 ESM，只有重启 DSH 才生效），
  再按作业单 §2.1 验收三条可观察事实。
- FAIL → 按作业单 §3 的分支表处理（`F0`~`F5`），处理完重跑，直到 PASS。

细节与判定标准全在 [`AI-FIX-BALL-GUARD.md`](AI-FIX-BALL-GUARD.md)；原理与实测证据在
[`android/docs/BALL_GUARD_CROSS_DEVICE.md`](android/docs/BALL_GUARD_CROSS_DEVICE.md)。

让位有**三档**（设置页「AI 操控时的防误触」/ `float_agent_yield_mode`）：`off` / `touch`（看得见点不到）/
`flash`（**闪开**：AI 触摸的瞬间玻璃真的消失，触摸结束立刻恢复）。换机型后「让位看着在、其实没用」就切 `flash`，
验证：`bash android/tools/ball-flash-verify.sh`（要点：它会临时放 `files/show-glass`，跑完自动 `hide-glass`）。

## 硬性约束（违反会引入更贵的问题）

1. 让路是**两半**：球 APK（收到信号让路）+ DSH 插件（注入前发信号）。**只装一半等于没修。**
2. **不要 `pm uninstall` 球**，不要改 `shared_prefs`，不要替用户点设置页开关。
3. **不要跳过 DSH 重启**就宣布修好；重启用 `--restart-dsh`（detached，3 秒后），别前台 `pkill -9`。
4. **不要给可见窗口加 `FLAG_NOT_TOUCHABLE`**（ColorOS 会把 alpha 钉在 0.80）；可见窗口用空可触摸区域。
5. **不要把信号目录换成 `/data/local/tmp`**（`shell_data_file` 标签，球写不进 ACK）。
6. **不要用 `input tap` / `sendevent` 绕过 `mobile` 工具**做验证或干活。
7. **不要改 DSH 源码**；只在 `dsh-preset-mobile-use` bundle 上打补丁。
8. 球和 DSH 都跑在用户自己的机器上；他们的数据（会话、设置、订单）**不碰**。

## 其他常见任务的入口

| 任务 | 去哪 |
|---|---|
| 换一台手机把整套装起来 | [`android/docs/DEPLOY_OTHER_PHONE.md`](android/docs/DEPLOY_OTHER_PHONE.md) |
| 改球的 UI / 玻璃 / 手势 | [`android/docs/HANDOFF.md`](android/docs/HANDOFF.md)、[`OPERATIONS.md`](android/docs/OPERATIONS.md) |
| 让路协议的实现细节与踩过的坑 | [`android/docs/BALL_TOUCH_GUARD.md`](android/docs/BALL_TOUCH_GUARD.md) |
| 球为什么吃不掉 agent 的手势（跨机型） | [`android/docs/BALL_GUARD_CROSS_DEVICE.md`](android/docs/BALL_GUARD_CROSS_DEVICE.md) |
| 编 APK | `bash android/build.sh` → 产出 `glsl-playground.apk` |
