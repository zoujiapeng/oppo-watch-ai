#!/data/data/com.termux/files/usr/bin/bash
# 一键：构建 → 装 → 重启球 → 从应用私有目录喂一条假审批 → 等你在球上点 → 抓图 + 读答案
#
# 为什么这么绕：
#   · untrusted_app 在 SELinux 下读不了 /data/local/tmp，调试文件只能放
#     /data/data/com.glslplayground.app/files/（0711，root 与 termux 都够得着）
#   · 球在物理屏上，虚拟屏看不到；球自己会用 PixelCopy 存一帧到同一个私有目录
#   · Termux 里 am/pm 必须用干净环境（Termux 的 LD_PRELOAD 会让 app_process 挂掉）
#   · 文件里的中文必须用 python 写，printf 在 C locale 下会把 UTF-8 打碎
#
# 用法：
#   bash approval-e2e.sh            # 全流程，喂完假审批后等你点，最多等 40s
#   bash approval-e2e.sh --no-build # 只重启 + 喂 + 等（改完 HTML/参数时用）
set -eu
PKG=com.glslplayground.app
FILES=/data/data/$PKG/files
OUT=/data/data/com.termux/files/home/oppo-watch-ai/.preview
BALLSHOT=/data/local/tmp/ballshot
BUILD=1
[ "${1:-}" = "--no-build" ] && BUILD=0

if [ "$BUILD" = 1 ]; then
  echo "① 构建"
  cd /data/data/com.termux/files/home/oppo-watch-ai/android
  bash build.sh 2>&1 | grep -E "error|APK ready" | head -20
  echo "② 安装"
  pm install -r /data/data/com.termux/files/home/oppo-watch-ai/glsl-playground.apk 2>&1 | tail -1
fi

echo "③ 强停 + 重启球服务（新 dex 生效）"
env -i PATH=/system/bin HOME=/data/local/tmp am force-stop $PKG
sleep 1
env -i PATH=/system/bin HOME=/data/local/tmp am start-foreground-service -n $PKG/.FloatingBallService 2>&1 | tail -1
sleep 4

echo "④ 喂假审批"
rm -f "$FILES/ballapproval.answer"
python3 - "$FILES/ballapproval" <<'PY'
import sys, json, io
payload = {
    "toolName": "bash",
    "reason": "这个命令要在沙箱之外执行：rm -rf /data/local/tmp/probe。批准就放行，拒绝就拦下。",
}
with io.open(sys.argv[1], "w", encoding="utf-8") as f:
    f.write(json.dumps(payload, ensure_ascii=False))
print("wrote", sys.argv[1])
PY
chmod 666 "$FILES/ballapproval"
ls -la "$FILES/ballapproval"
sleep 2
echo "球是否捡走了请求（文件应已消失）：$(ls "$FILES/ballapproval" 2>/dev/null || echo 已捡走)"

echo "⑤ 等你在球上点一个选项（最多 40s）…"
for i in $(seq 1 40); do
  if [ -f "$FILES/ballapproval.answer" ]; then
    echo "答案：$(cat "$FILES/ballapproval.answer")"
    break
  fi
  sleep 1
done
[ -f "$FILES/ballapproval.answer" ] || echo "（40s 内没点，球应该还挂着）"

echo "⑥ 抓球体截图"
mkdir -p "$OUT"
rm -f "$FILES/ball-capture.png"
touch "$BALLSHOT"; chmod 666 "$BALLSHOT"
sleep 2
if [ -f "$FILES/ball-capture.png" ]; then
  cp "$FILES/ball-capture.png" "$OUT/approval-ball.png"
  echo "screenshot -> $OUT/approval-ball.png ($(stat -c%s "$OUT/approval-ball.png") bytes)"
else
  echo "!! 没有 ball-capture.png"
fi

echo "⑦ 日志"
logcat -d 2>/dev/null | grep -E "DshApproval|approval ball|approval window|approval tap|shot request" | tail -25
