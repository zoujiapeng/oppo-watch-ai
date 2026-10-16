# dsh-plugin-speech —— 给悬浮球提供"短口播稿"

DSH 插件：助手**每回合的最终回复**出来时，用**本会话的模型**把它改写成一句到三句的
中文口播稿（≤100 字），原子写到悬浮球的私有目录；球朗读前读这个文件。

**好处**：不需要第二份 API key（复用 DSH 已经配好的 provider/model），不改 DSH 任何代码。
**代价**：开着朗读时，**每回合多一次很小的模型调用**（输出 ≤400 token；正文短于 60 字直接跳过）。

## 数据流（v2）

```
assistant/message（DSH 一步一条：带 tool-call 的是处理中旁白，不带的才是最终回复）
  → 只挑「不带 tool-call」那条（= 本回合最终回复）→ 正文安静 400ms（或 turn/end）触发
  → 用这条消息的 provider/model 请求 1 次改写
  → 原子写 files/spoken.json = {"v":2,"turn":T,"step":S,"text":"…"}   ← 带身份
     同时写 files/spoken.txt（纯文本，老版球的兜底通道）
     text 为空串 = 「这条我没稿子，念你本地那版」（太短 / 改写失败都这样写）
  → 球只等「对得上 (turn,step)」的那份，最多 2.5 秒，等不到就用本地规则清洗版
```

**心跳文件** `files/speech-plugin.alive`（每 15 秒跳一次）是 v2 的关键：
球拿它判断"插件到底在不在跑"，**不在跑就一秒都不等**。
（旧版不管插件死活都盲等满 `speak_wait_ms`，默认 15000 —— 插件没加载的那一天多里，
每次朗读都实打实停 15 秒。见下面「踩过的坑」第 7 条。）

## 安装（在手机上）

```bash
mkdir -p ~/dsh-plugin-speech/lib && cd ~/dsh-plugin-speech
cp /path/to/oppo-watch-ai/dsh-plugin-speech/lib/index.js lib/

# 插件要 import DSH 自己的包，给软链（不复制、不下载）
DSH_LIB=/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai
mkdir -p node_modules/@deepseek-ai
ln -sfn $DSH_LIB/dsh-llm node_modules/@deepseek-ai/dsh-llm
ln -sfn $DSH_LIB/dsh-util-values node_modules/@deepseek-ai/dsh-util-values

# 装进 web profile（node_modules 里出现软链 dsh-plugin-speech -> ~/dsh-plugin-speech）
dsh plugin --profile web add .
```

然后在 **profile 自己的 patch 层**里登记（**不要**写 `dsh.profile.bundles`，见坑 7）：

```bash
python3 - <<'PY'
import io
p = "/data/data/com.termux/files/home/.dsh/profiles/web/cordis.patch.yml"
s = io.open(p, encoding="utf-8").read()
if "dsh-plugin-speech" not in s:
    s += '\n- insert:\n    - id: dsh-plugin-speech\n      name: "dsh-plugin-speech"\n'
    io.open(p, "w", encoding="utf-8").write(s)
    print("已登记")
else:
    print("已登记过")
PY

# 重启 DSH 生效（插件代码只在启动时 import 一次）
pkill -f "dsh/lib/bin.js web"
# 拉起来（本机原来的启动方式）
cd ~ && setsid nohup ~/.local/bin/dsh web --no-open --port 3080 >> ~/dsh.log 2>&1 < /dev/null &
```

> ⚠️ **改了插件就要重启 DSH**，而重启会掐断正在进行的会话 —— 安排在你方便的时候做。
> （改 `cordis.patch.yml` 本身会触发 profile 热重载，但**插件模块是 ESM 缓存**的，
> 改完 `lib/index.js` 还是得重启进程才会生效。）

## 核对（两份落盘 trace，不依赖 logcat）

这台机器上 `logcat` 会被别的服务刷掉，所以排查一律看文件：

```bash
F=/sdcard/Android/data/com.glslplayground.app/files
cat $F/speech-plugin.log      # 插件: 插件已加载（v2）… / 开始改写：最终回复 N 字（turn=… step=…） / 口播稿 M 字已写入
cat $F/speak-trace.log        # 球  : 处理中回复|最终回复 turn/step → 不等|开始等插件口播稿（最多 Nms）→ 交给 TTS N 字
cat $F/last-spoken.txt        # 球实际念的稿子
ls -l $F/speech-plugin.alive  # 插件心跳（45 秒内算"在跑"）；没有这个文件 = 球一秒都不等
cat $F/spoken.json            # 插件写给"这条消息"的口播稿（带 turn/step），球取走即删
```

**成功的样子**（实测，v2）：

```
speech-plugin.log: 插件已加载（v2），心跳 …/speech-plugin.alive
speech-plugin.log: 18:09:46 开始改写：最终回复 1530 字（turn=1 step=8，deepseek-official/deepseek-flash）
                   18:09:47 口播稿 125 字已写入（turn=1 step=8）
speak-trace.log  : 最终回复 1/8：1530 字 → 开始等插件口播稿（最多 2500ms，1/8）
                   → 等到插件稿 125 字（等了 1120ms）→ 交给 TTS 125 字（插件口播稿）
```

失败/插件不在跑的样子（**也是一秒都不多等**）：

```
speak-trace.log  : 处理中回复 1/90：93 字 → 不等口播稿（非最终回复）→ 交给 TTS 87 字（规则清洗）
speak-trace.log  : 最终回复 1/12：902 字 → 不等口播稿（插件没在跑）→ 交给 TTS 705 字（规则清洗）
```

## 踩过的坑（都是真金白银换的）

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| 1 | 正文在 `event.data.message` | 写成 `event.message` 永远取不到，**静默失效** | 三层兜底取值 |
| 2 | 模型默认带推理 | `blocks=reasoning 文本=0`——`max_tokens` 被推理吃光 | `reasoningEffort: "off"` + 额度重试 |
| 3 | `assistant/message` **一步一条** | 一回合来很多条（带 tool-call 的是旁白），逐条判断会改写错对象 | 只挑「不带 tool-call」那条 = 最终回复（v2）= 本回合最长的正文（v1，已废） |
| 4 | `ctx.logger` 输出在设备上看不到 | 排查时"什么都没发生" | 直接 `fs.appendFileSync` 到 files/ 下 |
| 5 | 球的等待时间 | 插件慢一点球就念成原文 | 球只对**最终回复**等，且**插件不在跑时一秒都不等**；上限默认 2.5 秒（`speak_wait_ms` 可调） |
| 6 | 新旧稿共用 `spoken.txt` | 球读到**上一轮/别的 step** 残留（实测只等 4ms 就命中） | v2 写 `spoken.json` 带 `(turn,step)`，球对号入座；纯文本只作兜底 |
| 7 | **登记在 `dsh.profile.bundles` 里会被抹掉** | 2026-10-16 装 `dsh-web-mobile-fix` 时 `package.json` 被重写，speech 的登记消失 → 插件一整天没加载 → 球每次朗读都**盲等满 15 秒**（`speak-trace.log` 里一串「等满 15xxx ms」） | 登记改放 **profile 自己的 `cordis.patch.yml`**（`dsh plugin add` 不碰它），并且球加了**插件心跳**：插件不在跑就一秒都不等 |
| 8 | 改 `lib/index.js` 不重启不生效 | 编辑完文件行为没变 | 插件是 ESM，进程内按路径缓存；必须重启 DSH 进程 |

## 卸载

删掉 `~/.dsh/profiles/web/cordis.patch.yml` 里那三行 `- insert: … dsh-plugin-speech`，
再 `pkill -f "dsh/lib/bin.js web"`。**App 侧不用改**：球发现没有心跳就一秒都不等，
直接回落到本地规则清洗。
