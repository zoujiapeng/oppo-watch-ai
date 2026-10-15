# dsh-plugin-speech —— 给悬浮球提供"短口播稿"

DSH 插件：助手每回合结束时，用**本会话的模型**把回复改写成一句到三句的中文口播稿
（≤100 字），原子写到悬浮球的外部私有目录；球朗读前读这个文件。

**好处**：不需要第二份 API key（复用 DSH 已经配好的 provider/model），不改 DSH 任何代码。
**代价**：开着朗读时，**每条回复多一次很小的模型调用**（一回合仅 1 次，输出 ≤400 token）。

## 数据流

```
assistant/message（流式追加，一回合会来很多条）→ 按会话累积正文
  → turn/end（或正文安静 400ms）→ 用最后一条的 provider/model 请求 1 次改写
  → 原子写 files/spoken.txt
  → 球朗读前最多等 15 秒读它；等不到就用本地规则清洗
```

## 安装（在手机上）

```bash
mkdir -p ~/dsh-plugin-speech/lib && cd ~/dsh-plugin-speech
cp /path/to/oppo-watch-ai/dsh-plugin-speech/lib/index.js lib/
cp /path/to/oppo-watch-ai/dsh-plugin-speech/cordis.patch.yml .

# 插件要 import DSH 自己的包，给软链（不复制、不下载）
DSH_LIB=/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai
mkdir -p node_modules/@deepseek-ai
ln -sfn $DSH_LIB/dsh-llm node_modules/@deepseek-ai/dsh-llm
ln -sfn $DSH_LIB/dsh-util-values node_modules/@deepseek-ai/dsh-util-values

# 装进 web profile 并**登记为 profile layer**
# （只 `dsh plugin add` 的话它只是普通依赖，提示 "declares no dsh.bundle"，不会被加载）
dsh plugin --profile web add .
python3 - <<'PY'
import json, io
p = "/data/data/com.termux/files/home/.dsh/profiles/web/package.json"
d = json.load(io.open(p, encoding="utf-8"))
b = d["dsh"]["profile"]["bundles"]
if "dsh-plugin-speech" not in b: b.append("dsh-plugin-speech")
d["dsh"]["profile"]["bundles"] = b
io.open(p, "w", encoding="utf-8").write(json.dumps(d, ensure_ascii=False, indent=2) + "\n")
PY

# 重启 DSH 生效（插件代码只在启动时加载）
pkill -f "dsh/lib/bin.js web"
```

> ⚠️ **改了插件就要重启 DSH**，而重启会掐断正在进行的会话 —— 安排在你方便的时候做。

## 核对（两份落盘 trace，不依赖 logcat）

这台机器上 `logcat` 会被别的服务刷掉，所以排查一律看文件：

```bash
F=/sdcard/Android/data/com.glslplayground.app/files
cat $F/speech-plugin.log   # 插件: 开始改写：本回合正文 N 字 → 口播稿 M 字已写入
cat $F/speak-trace.log     # 球  : 开始等插件口播稿(15000ms) → 等到插件稿 M 字(等了 Xms) → 交给 TTS
cat $F/last-spoken.txt     # 球实际念的稿子
```

**成功的样子**（实测）：

```
speech-plugin.log: 15:49:00 开始改写：本回合正文 810 字  →  15:49:01 口播稿 68 字已写入
speak-trace.log  : speakOnce 进入: 810 字 → 等到插件稿 68 字（等了 1414ms）→ 交给 TTS 68 字（插件口播稿）
```

## 踩过的坑（都是真金白银换的）

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| 1 | 正文在 `event.data.message` | 写成 `event.message` 永远取不到，**静默失效** | 三层兜底取值 |
| 2 | 模型默认带推理 | `blocks=reasoning 文本=0`——`max_tokens` 被推理吃光 | `reasoningEffort: "off"` + 额度重试 |
| 3 | `assistant/message` 是**流式追加**的 | 一回合来多条、**最后一条常很短**（实测 0→20→26 字），逐条判断永远不触发 | 按会话累积、`turn/end` 时改写一次 |
| 4 | `ctx.logger` 输出在设备上看不到 | 排查时"什么都没发生" | 直接 `fs.appendFileSync` 到 files/ 下 |
| 5 | 球的等待时间 | 插件在回合结束才发请求，3.5s/6s 都等不到 → 念成原文 | 球默认等 15 秒（`speak_wait_ms` 可调） |
| 6 | 新旧稿共用 `spoken.txt` | 球读到**上一轮**残留（实测只等 4ms 就命中） | 球只接受"本轮开始之后"写入的文件 |

## 卸载

从 `~/.dsh/profiles/web/package.json` 的 `dsh.profile.bundles` 里删掉 `dsh-plugin-speech`，
再 `pkill -f "dsh/lib/bin.js web"`。**App 侧不用改**：球会自动回落到本地规则清洗。
