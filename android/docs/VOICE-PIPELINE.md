# 语音识别到底用的是哪个引擎（以及为什么以前"没输入法准、说不久"）

> 结论先给：**长按电源键 / 音量减说话这条路，一直是输入法自己的语音引擎**（本机是搜狗）。
> 不准确、说不久，是**另外两条入口**（弹球自动听、单击球）绑的是系统 `SpeechRecognizer`，
> 而且按住说话期间有几处会"自己停"的坑。2026-11-07 之后全部收口到输入法。

## 一、三条入口，三个引擎（改之前）

| 入口 | 引擎 | 实际落到哪 | 识别率 | 能说多长 |
|---|---|---|---|---|
| 长按电源键 → 说话 | **输入法自己的语音** | 搜狗 | 高 | 受"自己收尾 / 抢麦"影响会断 |
| 长按电源键 → 说话（设置页「按住说话」按钮同路） | 同上（VoiceMap 注入空格长按） | 搜狗 | 高 | 同上 |
| 弹球自动听（`voice_auto`） | `AndroidAsr` → `SpeechRecognizer` | `com.xiaomi.mibrain.speech/.asr.AsrService` | 低 | 短（`onEndOfSpeech` 系统自己判） |
| 单击球之后接着说话 | `BallVoice` → `AndroidAsr` | 同上 | 低 | 短 |
| ~~录音 + `speech/transcribe`~~ | **不存在** | 本部署 `speech/*` 路由 404 | — | — |

实测确认（本机）：

```bash
su -c 'cmd package query-services -a android.speech.RecognitionService'
#   name=com.xiaomi.mibrain.speech.asr.AsrService
#   packageName=com.xiaomi.mibrain.speech
su -c 'cmd package query-services -a android.service.voice.VoiceInteractionService'  # 未作为 ASR 使用
```

这就是你感觉"没有手机输入法准确"的直接原因：**不是同一套模型**。
小米 mibrain 那套系统 ASR 在短句上还行，长句、专有名词、口音都明显弱于搜狗在线语音。

## 二、VoiceMap 是怎么把话交给输入法的

（`android/voicemap-ime/module/…/VoiceMapHook.java` + `android/src/…/FloatingBallService.java`）

```
电源键第一次长按
  PowerKeyDaemon → 广播 → FloatingBallService.beginPowerHold()
    ├ enterSearchMode()          弹胶囊、只拿焦点
    ├ vm().cmd("stealthon")      键盘窗口 alpha=0（看不见但存在）
    └ 900ms 后 vm().trigger("down")
         ↓ 广播 com.pj.voicemap.TRIGGER cmd=down
      模块(VoiceMapHook, 活在搜狗进程里)
         └ 向搜狗键盘视图 dispatchTouchEvent(ACTION_DOWN, 空格键坐标)
              ↓ 搜狗自己的长按空格逻辑
           搜狗开麦 → 语音引擎（在线/离线都在搜狗内部）
```

**关键点：我们没有重写识别，一个字的模型代码都没碰。** 模块只做两件框架层面的事：
隐身（窗口 alpha）+ 向空格键注入真实 `MotionEvent`。所以"输入法有多准，这路就有多准"。

空格键坐标：`resolvePoint()` 先用校准值（`voicemap.xml`），没有就用启发式
`(0.5w, 0.8703h)`，`cmd calibrate` 会逐个候选点试按并按"是否真的开始录音"自动判定。

## 三、"说不久"的四个真实成因（都已修）

### 1. 输入法自己收尾（12~17 秒不说话）
搜狗的语音有"长时间没检测到人声就 stop 并上屏"的保护。
`voiceAliveWatch` 每 1.2s 看一次 `AudioManager.getActiveRecordingConfigurations()`，
连续两次为空 → 补发 `down` 续按。

**旧实现的 bug**：只要框里已经有字就**不续按**。但流式识别的中间结果是一直往框里写的，
所以说到十几秒时框里必然已经有字 → 永远不续按 → 后半句全丢。
现在分成两种情况：

| 观察 | 判定 | 动作 |
|---|---|---|
| 框里有字，且**字数跟上次判定时一样** | 输入法把这一轮说完并上屏了 | 不按（按了会清掉刚上屏的字） |
| 框里有字，但**字数变了** | 这一轮完成后被**抢麦**（播放器/来电） | 1.5s 消抖后**立刻补按** |
| 框里没字 | 输入法录音被停掉 | 4s 消抖后补按 |

### 2. 播放器抢麦 —— 最隐蔽的一条
**DSH 的 TTS 在朗读回复时会把麦克风从输入法手里抢走。**
表现就是"球刚念完话，我接着说的那几句没进去"。旧逻辑一律当"输入法收尾了"处理，
还要等 4 秒冷却 —— 那一整句就没了。现在是闪断分支：1.5 秒消抖、立即补按。

### 3. 看门狗的单轮上限
- App 侧 `VOICE_REHOLD_MAX_MS` = 175s
- 模块侧 watchdog = 180s

以前这是**整个会话**的上限：说到 3 分钟强制松手。现在改成**续期式**：
每次 `down` 都重新计时（`armWatchdog(++sDownSeq)`），一直说下去不会被截断；
真的丢了 UP 时仍然会在最后一个 down 之后 3 分钟内兜住（键盘不会永久隐身）。

### 4. 多轮续说：音量减 = 换行接着说
以前音量减 = 结束并发送。现在跟输入法里"换行 → 再长按空格"等价：

```
音量减
  ① vm().trigger("up")        这一轮终稿落定（字停稳 900ms）
  ② searchBar.editNewline()   在光标处插入 \n，已有文字一个不动
  ③ vm().trigger("down")      同一个输入框里开新的一轮
  ④ multiTurnMark()           续按时长从 0 重新计
```

**为什么必须是真换行**：同一个语音会话连着说，输入法会把上一轮覆盖掉；
换行让它认为是新的输入段，上一轮的字保留。这也是手打时的等价动作。

结束方式：**再长按一次电源键** → `endPowerHold()` → `up` → 等字停稳 → 全文（含所有换行）
一次发出一整条消息。所以你可以：`说话 → 音量减 → 说话 → 音量减 → … → 电源键`。

## 四、现在的入口统一表

| 入口 | 引擎 |
|---|---|
| 单击球 | **输入法语音**（弹框 + `beginPowerHold()`） |
| 长按电源键（第一下） | **输入法语音** |
| 音量减（按住中） | 换行，继续输入法语音 |
| 电源键（按住中） | 结束 → 发全文 |
| 点输入框 | 结束语音、键盘显形，接着手打 |
| 设置页「🎙 按住说话」按钮 | 输入法语音；模块不可用时退到 `BallVoice`（系统 ASR） |
| `voice_auto=1`（老行为） | `AndroidAsr` → 小米 mibrain；单击时会被 `gateOldAsrOnTap()` 停掉 |

## 五、怎么验证「真的没断」

```bash
# 看续按 / 换行的判定
su -c 'logcat -d -s FloatingBall' | grep -E "续按|抢走|多轮|换行|已经上屏"

# 看模块注入的每一次按下/松手
sh /data/data/com.glslplayground.app/files/vmctl.sh log | grep -E "DOWN|UP after|watchdog"
```

判读：

- `语音：录音被抢走（播放器/来电，框里 N 字）→ 马上补按，接着听` → 播放器抢麦被接住了（正常）
- `语音：输入法收尾了但已经上屏 N 字 → 不续按` → 输入法自己说完一轮，等你说下一句（正常）
- `语音：多轮模式，换成新的一轮继续听（已累计 N 字）` → 音量减换行生效
- `watchdog: 按住超过 180s 没有新的 down/up` → **不该出现**；出现说明 UP 真丢了

## 六、为什么球这次真的会「按一下就听」

`beginPowerHold()` 里 `enterSearchMode()` 的守卫全部放行之后才会注入空格长按：

- `uiHidden`（音量加藏起来了）
- `approvalShown`（审批球在屏）
- `zonesActive` / `menuShown` → 会先 `exitZones()` / `hideMenu()` 再继续
- `!siriEnabled` → 会 `applySiriEnabled(true)`

否则日志里会看到 `电源键：胶囊没开（别的面板占着），本次不触发语音`。
