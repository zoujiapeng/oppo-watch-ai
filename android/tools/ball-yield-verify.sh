#!/data/data/com.termux/files/usr/bin/bash
# ball-yield-verify.sh —— 验证「DSH 操控时全部 UI 让位」
#
# 断言三件事：
#   ① 平时：球的代理触摸层是**可触摸**的（用户能点球、拖球）
#   ② 收到 agent 信号后：球拥有的**每一个**窗口都退出命中测试
#      （透明层 = NOT_TOUCHABLE；看得见的层 = 可触摸区域置空，alpha 不动）
#   ③ 1.5s 后：全部原样恢复可触摸
#
# 用法：
#   bash android/tools/ball-yield-verify.sh                # 只测当前界面
#   bash android/tools/ball-yield-verify.sh --with-search  # 先把搜索层叫出来（多几个可见窗口）
#   bash android/tools/ball-yield-verify.sh --with-menu    # 先把状态3 菜单叫出来
#   bash android/tools/ball-yield-verify.sh --off          # 验证「开关关掉」：不让路 + 回 !skip ACK
#
# 退出码：0 = 全部通过，1 = 有断言失败。
set -uo pipefail

D="${BALL_GATE_DIR:-/sdcard/Android/data/com.glslplayground.app/files}"
PKG="${BALL_PKG:-com.glslplayground.app}"
MODE="default"
for a in "$@"; do
  case "$a" in
    --with-search) MODE="search" ;;
    --with-menu)   MODE="menu" ;;
    --off)         MODE="off" ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "未知参数：$a"; exit 2 ;;
  esac
done

OUT="${BALL_VERIFY_OUT:-${TMPDIR:-$HOME/.cache}/ball-yield-verify}"
mkdir -p "$OUT" || OUT="$HOME"

fail=0
ok()   { echo "  [ok] $*"; }
bad()  { echo "  [!!] $*"; fail=1; }

pid="$(pidof "$PKG" 2>/dev/null || true)"
if [ -z "${pid:-}" ]; then
  echo "[!!] 球没在跑（pidof $PKG 空）。先启动："
  echo "     su -c 'env -i PATH=/system/bin HOME=/data/local/tmp am start-foreground-service -n $PKG/.FloatingBallService'"
  exit 1
fi
alive="?"
[ -f "$D/dsh-ball-alive" ] && alive=$(( $(date +%s) - $(stat -c %Y "$D/dsh-ball-alive") ))
echo "球在跑：pid=$pid  心跳=${alive}s 前  信号目录=$D"

# 让多出来的窗口出现（搜索胶囊 / 状态 3 菜单），好证明"每一个窗口都让路"
case "$MODE" in
  search) : > "$D/search-open"; sleep 2 ;;
  menu)   : > "$D/zone-open"; sleep 2; echo tl > "$D/zone-move"; sleep 1.5 ;;
esac

# 一行一个窗口：name|touchableRegion|touchable / notTouchable
# 只认 ownerPid = 球进程的窗口（App 的 SurfaceView 子窗也会列出来，它们本来就不接触摸）。
PY_SRC='
import re, sys
pid = sys.argv[1]
seen = set()
for line in sys.stdin:
    m = re.search(r"^ *\d+: name=.([^,]*?)., id=.*?inputConfig=([^,]*),.*?touchableRegion=(\S+?),? ", line)
    if not m:
        continue
    name, cfg, region = m.group(1), m.group(2), m.group(3).rstrip(",")
    o = re.search(r"ownerPid=(\d+)", line)
    if not o or o.group(1) != pid:
        continue
    if "NO_INPUT_CHANNEL" in cfg:
        continue
    not_touchable = "NOT_TOUCHABLE" in cfg
    empty = region.startswith("<empty")
    key = (name, region)
    if key in seen:
        continue
    seen.add(key)
    label = "notTouchable" if (not_touchable or empty) else "touchable"
    print(name + "|" + region + "|" + label)
'
snapshot() {   # $1=标题
  echo "── $1"
  dumpsys input 2>/dev/null | python3 -c "$PY_SRC" "$pid" | tee "$OUT/yield-$1.txt"
}

snapshot before
if grep -q '|touchable$' "$OUT/yield-before.txt"; then
  ok "平时至少有一个窗口可触摸（用户能操作球）"
else
  bad "平时连一个可触摸窗口都没有 —— 球根本点不到，先查 proxy 窗口在不在"
fi

if [ "$MODE" = "off" ]; then
  echo "── 发送 agent 信号（开关=关，期望：不让路 + 回 !skip: ACK）"
else
  echo "── 发送 agent 信号（期望：全部窗口退出命中测试）"
fi
(
  for i in $(seq 1 14); do
    printf 'verify-%s-%s-click\n' "$(date +%s%N)" "$i" > "$D/dsh-ball-act"
    sleep 0.25
  done
) &
rearm=$!
sleep 0.4
snapshot during
wait "$rearm" 2>/dev/null

if [ "$MODE" = "off" ]; then
  if grep -q '|touchable$' "$OUT/yield-during.txt"; then
    ok "开关关掉时窗口照旧可触摸（预期行为）"
  else
    bad "开关关掉却还是全不可触摸 —— 开关没生效"
  fi
  ack="$(tr -d '\n' < "$D/dsh-ball-ack" 2>/dev/null)"
  case "$ack" in
    '!skip:'*) ok "球回了 skip ACK（插件不用白等 500ms）：$ack" ;;
    *) bad "ACK 不是 !skip: 前缀，实际是「$ack」" ;;
  esac
else
  stragglers="$(awk -F'|' '$3=="touchable"{printf "%s ", $1}' "$OUT/yield-during.txt")"
  if [ -n "$stragglers" ]; then
    bad "让路期间还有窗口可触摸：$stragglers"
  else
    n=$(wc -l < "$OUT/yield-during.txt")
    ok "让路期间 $n 个球窗口全部退出命中测试"
  fi
  ack="$(tr -d '\n' < "$D/dsh-ball-ack" 2>/dev/null)"
  case "$ack" in
    !skip:*) bad "开关是开的，球却回了 skip ACK（$ack）—— 查设置项 float_agent_yield" ;;
    '') bad "ACK 文件空：握手没完成" ;;
    *) ok "ACK 匹配：$ack" ;;
  esac
fi

sleep 2
snapshot after
if [ "$MODE" = "off" ]; then
  ok "（开关关掉模式不检查恢复）"
else
  if grep -q '|touchable$' "$OUT/yield-after.txt"; then
    ok "已恢复可触摸（用户照常点球/拖球）"
  else
    bad "1.5s 后没有恢复可触摸 —— 看 logcat -s FloatingBall 里 yieldFlag 那几行"
  fi
fi

echo
if [ "$fail" = "0" ]; then echo "★ 全部通过"; else echo "✗ 有断言失败（上面 [!!] 那几行）"; fi
exit "$fail"
