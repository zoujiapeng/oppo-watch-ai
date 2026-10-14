# 交接说明（换窗口续接用）

> 这份是给"下一个会话的自己/别人"看的：现状、关键决定、怎么构建验证、还没做完的事。
> 仓库：`~/oppo-watch-ai`（GitHub `zoujiapeng/oppo-watch-ai`），工作分支 `feat/dsh-liquid-glass-siri`。

## 一句话现状

手机上的**悬浮液态玻璃水晶球**已经是 **DSH 的前端**：双击球说话 → 本机 DSH 转写+回答 →
回答**逐字打进球里**（Siri 模式的形变球）；DSH 里任何会话的 AI 输出也会被镜像进球。
DSH 侧一行没改，只走它已经在给浏览器用的 HTTP/WebSocket 接口。

## 怎么构建 / 装 / 看日志

```bash
cd ~/oppo-watch-ai/android && bash build.sh      # javac + d8 + aapt2 + apksigner（本机就有）
pm install -r ~/oppo-watch-ai/glsl-playground.apk
# 回滚包：~/glsl-playground.backup.apk
```
- 这个 App 同时是桌面/Kiosk：装完**悬浮球会停**，要在设置页重新打开「悬浮于桌面」。
- 日志（我平时靠它验证）：`logcat -d | grep -E "DshTransport|SiriAssistant|FloatingBall"`
- 球体**在物理屏（display 0）**上，虚拟屏（display 4）看不到它 →
  设置页有「保存球体截图」（PixelCopy 只抓悬浮窗那一帧，不含桌面）。
  也支持外部触发：`touch /data/local/tmp/ballshot` → 球在下一个渲染帧自己存一帧并删掉该文件，
  日志同时打出当时的助手状态（`ball capture requested (state=.. waveMode=.. orbMix=..)`）。
  调「思考中」这类短暂状态时用这个口子，比手点设置页按钮准。
  注意：App 私有目录 Termux 读不到（挂载命名空间），要看图得让用户发过来，
  或把截图改 POST 到本机 HTTP 服务。

## 关键文件

| 文件 | 作用 |
|---|---|
| `android/src/.../DshHttpTransport.java` | DSH 客户端：token→cookie、session/{list,create,prompt,page}、speech/transcribe、**常驻镜像** |
| `android/src/.../DshWebSocket.java` | 自实现最小 RFC6455 客户端（握手/掩码/分片/ping） |
| `android/src/.../SiriAssistant.java` | 状态机 + 打字机 + 镜像回调 + 空闲清空 |
| `android/src/.../VoiceCapture.java` | 16kHz PCM16 → WAV → base64，50ms 上报 RMS |
| `android/src/.../FloatingBallService.java` | 悬浮球：手势、Siri 形变几何（`measureSiriTargets`）、黑区、球体截图 |
| `settings.html` | 设置页（「DSH 语音助手」区块 + Siri 参数） |
| `android/docs/DSH_ASSISTANT.md` | 完整接入文档（协议、状态表、黑区公式） |

## 已经确定的行为（别改错）

- **手势**：双击=开始说话；再双击=结束并发送；长按=取消；拖动=只拖动；**单击不响应**。
- **球内文字只有两种**：语音识别到的那句话、AI 的回答。没有「聆听中/识别中/思考中」占位字。
- **球缩回最小形态时文字「同一刻」消失**：`measureSiriTargets` 走到「0/1 个字 → 最短形态」那一支时
  会立刻 `hideSiriTextNow()`（`setVisibility(GONE)`，不进布局）—— 只把文字设成空串不够，
  窗口/测量的更新是异步的，球收缩的半秒里旧字还会留在屏上（用户看到的「球小了、字飞出球外」）。
- **回答结束、球变回原本状态时文字一起消失**：等打字机弹完最后一个字 → 安静 1.5s → 清空并回 IDLE
  （`SiriAssistant.IDLE_CLEAR_MS` + `quietTick`）。
  **注意清空不看 turn/end**：镜像跟的那条会话可能是一轮跑很久的长任务（几十个 step、几分钟不 end），
  只认 turn/end 的话文字会一直挂在球里 —— 现在只要「没新字进来」就自己消散。
- **光效**：只有 THINKING 是流体圆点；LISTENING/TRANSCRIBING 是 Siri 波；
  ANSWERING/IDLE 还原用户自己的波形设定（`FloatingBallService.setAssistantState`）。
  圆点与波**共用同一套坐标框架**（屏幕像素 / `uOrbRadius` * 0.17，x/y 同倍率），
  边缘辉光取「圆点自身亮度 × `uOrbRim`」而不是固定满值；切换时 `uOrbMix` 做 320ms 交叉淡化。
  调这三个参数：设置页「思考中圆点环半径 / 亮度 / 边缘辉光」，
  离线看效果：`android/tools/preview`（不必刷 APK）。
- **黑区下沿按文字实际高度算**，不按行数：
  `tBlack = clamp(uBlack1 - (textBottomNy - waveGap), 0, 1.5)`，
  滑块「文字与 Siri 波的安全距离」= `waveGap`（pref `float_siri_wavegap`，默认 100 → 0.10）。
- **球高封顶屏高 70%**，封顶后按能放下的行数截断并加省略号；`measureSiriTargets` 还会再校验
  measured 高度、超了就继续减行数（兜底，防文字糊到球外）。
- **球内文字长度封顶 600 字**（`SiriAssistant.BALL_TEXT_MAX`，超了只留最后一段）＋
  镜像缓存封顶 1200 字（`DshHttpTransport.MIRROR_BUF_MAX`）—— 一条长任务的输出几万字，
  不封顶的话 TextView 会撑成一整面墙、文字堵在球外面。
- 文字排版全部走 App 原有设定（字号/距球顶/内边距/圆角），助手只调 `setSiriText`。

## DSH 接口速查（改客户端时用）

```
POST http://127.0.0.1:3080/api/<namespace>/<method>      # 斜杠，不是点
{"type":"client-request","rpcId":"..","method":"<ns>/<m>","payload":{"args":{..}}}
→ {"type":"server-response","rpcId":"..","result":{"ok":true,"value":..}}   # 失败也是 HTTP 200
参数 wire 名：session/list → _request；其余 → request
流：ws://127.0.0.1:3080/api/remote.mux
    → {"type":"open","streamId","endpoint":"session/follow","payload":{"args":{"request":{...,"assistantStream":true}}}}
    ← {"type":"item","value":{"type":"assistant-stream","frame":{"type":"chunk","chunk":{"type":"text-delta","text":".."}}}}
    ← {"type":"item","value":{"type":"event","event":{"type":"assistant/message"|"turn/end",...}}}
认证：GET /?token=<~/dsh.log 里的 launch token> → 303 + 签名 cookie（dsh-auth-<sha256(authority)>）
```
完整逆向记录：`android/docs/dsh-external-client-spec.md`（916 行）。

## 还没做 / 可继续的方向

1. **语音识别没有实时字幕**：DSH 的 `speech/transcribe` 是整段返回，说话时只有波形反馈。
   想边说话边出字，得改用系统 `SpeechRecognizer`（`onPartialResults`），代价是不走 DSH 语音插件了。
2. **麦克风授权**：本机 App 不是设备所有者，首次双击会弹一次系统授权框。
   免弹窗：`adb shell dpm set-device-owner com.glslplayground.app/.AdminReceiver`。
3. **镜像跟随**：跟"最近更新的非子智能体会话"，20 秒轮询切换；想固定某条会话需要另加设置。
4. `android/docs/probes/*.mjs` 是当时跑通的 Node 探针，可复用。
5. 仓库里 `dsh-liquid-glass-siri/` 是早期做的**网页版** DSH 插件（用户最终没要），
   独立功能、可从 profile 移除：`dsh plugin --profile web remove dsh-liquid-glass-siri`。
6. 设置页很长、WebView 不能滚动（实测自动化 swipe 无效），要触发「思考中」只能：
   双击球说话（真机路径），或先把 DSH 区块挪到页首再重装。球体截图在
   `/data/data/com.glslplayground.app/files/ball-capture.png`（root 可读，Termux 要经
   `su -c cp` 到 `~/oppo-watch-ai/.preview/`，`/sdcard` 与 `/data/local/tmp` 在 Termux 命名空间里看不见）。
