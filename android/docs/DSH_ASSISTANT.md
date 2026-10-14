# 水晶球 × DSH 语音助手（安卓端）

把 **这台手机上的悬浮水晶球** 变成 DeepSeek Harness 的前端：点一下球就能说话，
AI 的回答**以 Siri 模式的文字写回球里**（球按你那套 `measureSiriTargets` 的几何随文字变形）。
DSH 本身不动一行代码 —— 只用它已经在给浏览器用的那套 HTTP/JSON-RPC 接口。

```
点球 ──► 录音 16kHz PCM16 WAV ──► DSH speech/transcribe（profile 里配的语音插件）
                                      │
                                      ▼
                            session/prompt 发进当前 DSH 会话
                                      │
                    session/page 轮询 ◄─┘  （assistant/message 正文）
                                      │
                                      ▼
              FloatingBallService.setSiriText(回答)  ──► 球内 Siri 模式文字
```

## 用到的 DSH 接口

| 用途 | endpoint | 参数 |
|---|---|---|
| 会话列表（`asOfSeq` 就是事件游标） | `session/list` | `{"_request":{}}` |
| 新会话（没有可用会话时） | `session/create` | `{"request":{"cwd":…}}` |
| 发一句话 | `session/prompt` | `{"request":{"requestId","sessionId","mode":"queue","content":[{"type":"text","text":…}]}}` |
| **流式回答（主路径）** | WS `/api/remote.mux` + `session/follow` | `{"request":{"address":{…},"assistantStream":true}}` |
| 读事件页（兜底路径） | `session/page` | `{"request":{"address":{…},"throughSeq":<asOfSeq>,"maxMessages":40}}` |
| 语音转写 | `speech/transcribe` | `{"request":{"audioBase64":<16kHz mono PCM16 WAV>,"language":"zh"}}` |

信封（`DshHttpTransport` 里逐字实现）：

```
POST http://127.0.0.1:3080/api/<namespace>/<method>      # 注意是斜杠
content-type: application/json
{"type":"client-request","rpcId":"<唯一>","method":"<namespace>/<method>","payload":{"args":{…}}}

→ {"type":"server-response","rpcId":"…","result":{"ok":true,"value":…}}      # 失败也是 HTTP 200
```

流式（`DshWebSocket`，自实现的最小 RFC6455 客户端）：

```
GET /api/remote.mux  (Upgrade: websocket, 不带 Origin)
→ {"type":"open","streamId":"s1","endpoint":"session/follow","payload":{"args":{"request":{…,"assistantStream":true}}}}
← {"type":"item","streamId":"s1","value":{"type":"snapshot",…}}
← {"type":"item","streamId":"s1","value":{"type":"assistant-stream","frame":{"type":"chunk","chunk":{"type":"text-delta","index":n,"text":"球"}}}}
← {"type":"item","streamId":"s1","value":{"type":"event","event":{"type":"assistant/message",…}}}   # 权威全文
← {"type":"item","streamId":"s1","value":{"type":"event","event":{"type":"turn/end",…}}}
```

**认证**：DSH 启动时会打印 `http://127.0.0.1:3080/?token=…`（见 `~/dsh.log`）。
`GET /?token=` → 303 + 签名 cookie `dsh-auth-<sha256(authority)>`（30 天），之后带着它请求 `/api` 与 WS。
安卓端以 root 读一次日志拿 token；cookie 失效（服务重启）会自动重换。

## 球体状态与光效的对应

助手只做两件事：写球里的文字、在**思考中**切一次光效。其余一律交还给用户自己的设定。

| 助手状态 | 球内文字 | 光效 |
|---|---|---|
| LISTENING / TRANSCRIBING | `聆听中…` / `识别中…` | Siri 波（振幅跟麦克风音量走，`voiceBoost` 叠加、不改用户参数） |
| **THINKING** | `思考中…` | **流体圆点（siriFluidDotsCore）—— 只有这个状态有** |
| ANSWERING | 回答逐字弹出 | 还原成用户自己的设定（默认就是原本的 Siri 模式） |
| IDLE / OFF | 保留回答 | 还原用户设定，音量增益归零 |

文字排版**完全走 App 原有设定**：助手只调 `FloatingBallService.setSiriText()`，
字号（Siri 字号 sp）、距球顶（px）、每多一行黑区下移、内边距、圆角与宽高，
全部由 `measureSiriTargets()` 按你那套规则算，助手不碰。

「一个字一个字弹出来」由两段拼成：模型侧是 WebSocket 的逐 token `text-delta`，
客户端再用 40ms/字的打字机揭示（落后超过 80 字才略微加速，绝不跳字），
所以无论模型吐字快慢，看到的都是逐字长大的球。

## 会话镜像：AI 一回复，球就出字

除了「点球提问」，助手还会常驻一条 **会话镜像**（设置里可关）：

- 跟着 **DSH 里最近在用的那条会话**（`updatedAt` 最新的非子智能体会话）；
  在浏览器里换对话 / 新开一条，约 20 秒内自动跟上；
- 只要那条会话里有助手输出，就用同一套打字机写进球里 —— **不区分这条回复是谁问的**
  （浏览器里聊、别的客户端发的、语音问的都一样）；
- 本 App 自己提问时镜像让路（`askInFlight`），避免同一段文字打两遍；
- 断线 2.5s 退避重连，状态显示在设置页。

所以在 DSH 网页端跟 agent 聊天时，手机上的水晶球会同步逐字显示它的回答。

真机验证（LE2120 / Android 13）：从外部往 DSH 会话发一条长问题，
日志依次 `assistant state=THINKING waveMode=2` → `ANSWERING waveMode=1` → `IDLE`，
设置页「球内当前文字」回读到完整的多段回答（边缘折射／色散／黑顶镜面／底部焦散四段）。

## 代码

新增：

| 文件 | 作用 |
|---|---|
| `DshHttpTransport.java` | DSH 客户端：token→cookie、上面 5 个 endpoint、轮询出回答；`org.json` + `HttpURLConnection`，无第三方依赖 |
| `SiriAssistant.java` | 状态机 OFF/IDLE/LISTENING/TRANSCRIBING/THINKING/ANSWERING；录音→转写→提问→流式写回球；可选系统 TTS 朗读 |
| `VoiceCapture.java` | `AudioRecord` 16 kHz 单声道 PCM16 → canonical WAV → base64，并上报 RMS 给球的 Siri 波 |
| `res/xml/network_security_config.xml` | targetSdk 28 起默认禁明文 HTTP；这里只给 `127.0.0.1/localhost` 开口子 |

改动：

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | `RECORD_AUDIO` / `INTERNET`；挂上上面的 network security config |
| `FloatingBallService.java` | 轻点球（<14dp 位移、<450ms）＝ 说话/结束/打断；`setAssistantLevel`（音量→Siri 波）、`setAssistantState`（聆听=声波，思考=流体圆点）、`siriTextNow()` 回读 |
| `GlassRenderer.java` | `voiceBoost`：把实时音量叠在用户设置的 `waveAmp` 上，不改用户参数 |
| `MainActivity.java` | 需要时弹一次录音授权 |
| `WatchBridge.java` | 给设置页的 JS 接口：开关 / 朗读 / DSH 地址 / 问一句 / 聆听 / 回读球内文字 |
| `settings.html` | 新增「DSH 语音助手」区块（开关、朗读、地址、问一句、聆听、清空、球内文字与状态实时回读） |

## 怎么用

1. 设置页打开「悬浮于桌面」（球浮起来），并保持「Siri 模式」开启。
2. 设置页 →「DSH 语音助手」：确认开关打开、地址是 `http://127.0.0.1:3080`。
3. **轻点球**开始说话，说完再点一下：转写 → 提问 → 回答逐段写进球里。
   思考/回答中点击 = 打断。
4. 不想说话时，用「问一句（测试链路）」直接走一遍 DSH 往返（不需要麦克风）；
   也可以直接在设置页的输入框里打字喂给球（`setSiriText`）。

回答会发进**最近更新的那个 DSH 会话**（排除子智能体），所以你在浏览器里开的对话就是球在聊的那条。
想固定某条会话，删掉应用的 `siri_assistant` prefs 里的 `session_id` 让它重新跟随即可。

## 已知边界

- **录音权限**：这台机器上应用不是设备所有者，所以系统会弹一次授权框；授权后一直有效。
  若能用 `adb shell dpm set-device-owner com.glslplayground.app/.AdminReceiver` 设为设备所有者，
  `SiriAssistant.ensureMicPermission()` 会静默自授权。
- **回答是逐 token 的**：走 `ws://127.0.0.1:3080/api/remote.mux` 上的 `session/follow{assistantStream:true}`，
  渲染 `chunk.type=="text-delta"`；只有连不上 WebSocket 时才退回 `session/page` 轮询（约 850ms）。
- 上游 DSH 的 cookie 校验在这台机器上被本地补丁关掉了；本实现走的是**上游也认的** token→cookie 流程，
  所以补丁回滚后依然能用。
- 只在 DSH `0.2.0-rc.2` 上验证过。

## 构建

```bash
bash android/build.sh          # 需要 javac / d8 / aapt2 / apksigner / python3
pm install -r glsl-playground.apk
```

`docs/dsh-external-client-spec.md`（916 行）是这套接口的完整逆向记录：WebSocket 流式协议、
每个 endpoint 的字节级样例、鉴权与签名 cookie 的生成、以及 `dsh sdk` / `dsh acp` 两条备选路径的对比。
`docs/probes/` 下是当时跑通的 Node 探针脚本。
