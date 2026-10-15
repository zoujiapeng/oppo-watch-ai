# dsh-plugin-speech —— 给悬浮球提供"短口播稿"

DSH 插件：每当助手回复完成，用它**本会话的模型**把回复改写成一句到三句的中文口播稿
（≤100 字），原子写到悬浮球的外部私有目录；球朗读前读这个文件。

**好处**：不需要第二份 API key（复用 DSH 已经配好的 provider/model），不改 DSH 任何代码。
**代价**：开着朗读时，每条回复会多一次很小的模型调用（输出 ≤300 token）。

## 安装（在手机上）

```bash
mkdir -p ~/dsh-plugin-speech && cd ~/dsh-plugin-speech
# 把本目录的 lib/index.js 与 cordis.patch.yml 放到对应位置（或直接 git clone 本仓库后拷贝）
mkdir -p lib && cp /path/to/oppo-watch-ai/dsh-plugin-speech/lib/index.js lib/
cp /path/to/oppo-watch-ai/dsh-plugin-speech/cordis.patch.yml .

# 插件要 import DSH 自己的包，给个软链（不复制、不下载）
DSH_LIB=/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai
mkdir -p node_modules/@deepseek-ai
ln -sfn $DSH_LIB/dsh-llm node_modules/@deepseek-ai/dsh-llm
ln -sfn $DSH_LIB/dsh-util-values node_modules/@deepseek-ai/dsh-util-values

# 装进 web profile 并**登记为 profile layer**（只 add 不登记的话它只是普通依赖，不会被加载）
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

# 重启 DSH 生效
pkill -f "dsh/lib/bin.js web"
```

## 核对

```bash
ls -la /sdcard/Android/data/com.glslplayground.app/files/spoken.txt   # 有回复后应出现
cat  /sdcard/Android/data/com.glslplayground.app/files/spoken.txt     # 就是球要念的稿子
logcat -s SiriAssistant | grep speak                                  # 「口播稿」= 走了插件；「规则清洗」= 回落
```

球的逻辑：**优先用这个文件（2 分钟内、用掉即删），没有就用本地规则清洗** ——
所以不装插件也能用，装了更自然。
