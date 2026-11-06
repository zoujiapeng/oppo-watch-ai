#!/data/data/com.termux/files/usr/bin/bash
# 取证：把「屏幕上真实的玻璃渲染」拍成一张**普通截图**，并把那一刻的状态 + 日志一起存下来。
#
# 为什么需要它：玻璃层带 SKIP_SCREENSHOT，MIUI 的「音量减 + 电源」截图 / 录屏里
# 那一层是**完全隐身**的 —— 拍下来只有字、没有胶囊。而手指一碰屏幕，搜索框就收起了，
# 也很难"摆好姿势再点按钮"。
#
# 做法：让 App 自己拍 —— 临时撤掉抓屏排除 + 冻结采集（玻璃保持原样），root screencap 一张，
# 拍完立刻恢复。所以拍到的就是**眼睛此刻看到的**那一帧。
#
# 用法：
#   bash tools/witness.sh                 # 立刻拍一张
#   bash tools/witness.sh "标题"           # 带标签（写进状态文件的 why=）
#   bash tools/witness.sh -d 5            # 等 5 秒再拍（留时间让你切回要拍的界面）
#   bash tools/witness.sh -n 6 -i 2       # 连拍 6 张、间隔 2 秒（抓"偶发"用）
#
# 产出（都在手机里）：
#   /sdcard/Pictures/Screenshots/glass_<时间>.png   ← 相册可见的普通截图（含玻璃）
#   .../files/glass_<时间>.txt                      ← 状态机快照（blob / barReal / k / 窗口 …）
#   .../files/glass_<时间>.log                      ← logcat 里本 App 最近 400 行
# 本脚本最后会把它们复制到 ~/OPPO-watch-ai/shots/witness/<批次>/ 方便直接看。
set -u
PKG=com.glslplayground.app
D=/sdcard/Android/data/$PKG/files
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
OUT="$HOME_DIR/OPPO-watch-ai/shots/witness"

DELAY=0
COUNT=1
INTERVAL=2
LABEL=""
while [ $# -gt 0 ]; do
  case "$1" in
    -d) DELAY="${2:-0}"; shift 2 ;;
    -n) COUNT="${2:-1}"; shift 2 ;;
    -i) INTERVAL="${2:-2}"; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) LABEL="$1"; shift ;;
  esac
done

if ! su -c "pidof $PKG" >/dev/null 2>&1; then
  echo "❌ 球没在跑（pidof $PKG 空）。先起来： su -c 'am start -n $PKG/.PowerBallTrampolineActivity'"
  exit 1
fi

STAMP=$(date +%Y%m%d-%H%M%S)
DEST="$OUT/$STAMP"
mkdir -p "$DEST"

if [ "$DELAY" -gt 0 ]; then
  echo "⏳ ${DELAY}s 后开拍……（现在切回你要拍的界面）"
  sleep "$DELAY"
fi

for i in $(seq 1 "$COUNT"); do
  before=$(ls -t "/sdcard/Pictures/Screenshots" 2>/dev/null | head -1)
  if [ -n "$LABEL" ]; then
    su -c "printf '%s' '$LABEL' > $D/witness"
  else
    su -c "touch $D/witness"
  fi
  # 快门本身 ≈ 2s（screencap + logcat），等它落盘
  sleep 4
  newest=$(ls -t "/sdcard/Pictures/Screenshots" 2>/dev/null | head -1)
  if [ "$newest" = "$before" ] || [ -z "$newest" ]; then
    echo "❌ 第 $i 张没拍出来（看 logcat | grep witness）"
  else
    cp "/sdcard/Pictures/Screenshots/$newest" "$DEST/" 2>/dev/null
    base="${newest%.png}"
    su -c "cp $D/$base.txt $D/$base.log /sdcard/" 2>/dev/null
    cp "/sdcard/$base.txt" "/sdcard/$base.log" "$DEST/" 2>/dev/null
    rm -f "/sdcard/$base.txt" "/sdcard/$base.log" 2>/dev/null
    echo "✅ $newest  →  $DEST/"
    # 顺手把状态里最关键的两行打出来：胶囊（blob）和输入行窗口（barReal）是否还对齐
    if [ -f "$DEST/$base.txt" ]; then
      grep -E "^(blob|barReal|springs|flags|hb)=" "$DEST/$base.txt" | sed 's/^/   /'
    fi
  fi
  [ "$i" -lt "$COUNT" ] && sleep "$INTERVAL"
done
echo
echo "全部产出一份在这里：$DEST"
echo "相册里也有同一张（Pictures/Screenshots），可以直接发给我。"
