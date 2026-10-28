#!/data/data/com.termux/files/usr/bin/bash
# 端到端验收：防误触（球让开 / 机器触摸不被当成手势 / 球外零开销）
#
# 用法：  bash tools/e2e-ball-gate.sh [--fast]
# 前提：  球在跑（"悬浮于桌面"打开），系统设置能打开。
# 判据全部来自设备真实状态：ball-diag 的计数 + uiautomator dump 的界面文本差异。
set -u
ADB_TAP=/system/bin/input
DIAG=/sdcard/Android/data/com.glslplayground.app/files/ball-diag
RECTF=/sdcard/Android/data/com.glslplayground.app/files/ball-rect
TMP=/data/local/tmp/e2e
mkdir -p $TMP
PASS=0; FAIL=0
ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
diag() { cat "$DIAG" 2>/dev/null; }
field(){ diag | tr ' ' '\n' | grep "^$1=" | cut -d= -f2; }
shots(){ uiautomator dump "$TMP/$1.xml" >/dev/null 2>&1; }
# 界面是否变化：只比"可见文本"集合（不依赖 python）
texts(){ grep -o 'text="[^"]*"' "$TMP/$1.xml" 2>/dev/null | sort -u; }
changed(){ # $1=before xml $2=after xml
  # 不用进程替换：su 环境里 /dev/fd 不一定可用（实测 <() 拿到空内容 → 永远判"没变化"）
  texts "$1" > "$TMP/.cmp1" 2>/dev/null
  texts "$2" > "$TMP/.cmp2" 2>/dev/null
  diff -q "$TMP/.cmp1" "$TMP/.cmp2" >/dev/null 2>&1 && echo 0 || echo 1
}

echo "=== 0. 状态 ==="
# 前置：球必须活着（ball-rect 每 400ms 刷新；超过 2s 就当没在跑）
MT=$(stat -c %Y "$RECTF" 2>/dev/null || echo 0)
AGEB=$(( $(date +%s) - MT ))
if [ -z "$(cat "$DIAG" 2>/dev/null)" ] || [ "$AGEB" -gt 3 ]; then
  echo "  ❌ 球没在跑（ball-rect ${AGEB}s 前更新）→ 先执行 bash tools/restart-ball.sh"
  exit 1
fi
D0=$(diag)
echo "  $D0"
[ -n "$D0" ] && ok "ball-diag 可读" || { bad "ball-diag 读不到（球没在跑？）"; exit 1; }
[ "$(field region)" = "1" ] && ok "Region 模式启用（可触摸区可动态置空）" || bad "Region 模式没启用（反射失败，退化成挪窗口）"
RX=$(cat "$RECTF" 2>/dev/null | awk '{print $1" "$2" "$3" "$4}')
echo "  球矩形: $RX"
BX=$(echo $RX | awk '{print $1}'); BY=$(echo $RX | awk '{print $2}')
BW=$(echo $RX | awk '{print $3}'); BH=$(echo $RX | awk '{print $4}')
CX=$((BX + BW/2)); CY=$((BY + BH/2))
echo "  球心: $CX,$CY"

# 找一个"球覆盖得到"的目标行：系统设置账号行固定在 (540,630) 附近
IN_X=540; IN_Y=630
OUT_X=540; OUT_Y=1150

ensure_settings() {
  # 每次从"设置首页"开始：先杀掉设置，避免它记住上次的二级页
  am force-stop com.android.settings >/dev/null 2>&1
  sleep 0.4
  am start -a android.settings.SETTINGS >/dev/null 2>&1
  sleep 2.0
}

echo
echo "=== 1. 球外点击：不该触发门控（零开销） ==="
ensure_settings
shots a; N1=$(field nonce)
T0=$(date +%s%N)
$ADB_TAP tap $OUT_X $OUT_Y
T1=$(date +%s%N)
sleep 1.2
shots b
N2=$(field nonce)
MS=$(( (T1-T0)/1000000 ))
[ "$(changed a b)" = "1" ] && ok "球外点击生效（界面变了）" || bad "球外点击没反应"
[ "$N1" = "$N2" ] && ok "没走门控（nonce 未变，耗时 ${MS}ms）" || bad "球外的点击也去握手了（nonce $N1 → $N2）"
[ "$MS" -lt 200 ] && ok "注入耗时 ${MS}ms（<200ms）" || bad "注入耗时 ${MS}ms，偏慢"

echo
echo "=== 2. 球上点击：让开 → 跨 App 点中 ==="
ensure_settings
shots c; SY0=$(field synthetic); N3=$(field nonce)
T0=$(date +%s%N)
$ADB_TAP tap $IN_X $IN_Y
T1=$(date +%s%N)
sleep 1.4
shots d
SY1=$(field synthetic); N4=$(field nonce)
MS=$(( (T1-T0)/1000000 ))
[ "$(changed c d)" = "1" ] && ok "点中球下面的目标（界面变了）" || bad "球上点击没生效（被丢弃/被球吃掉）"
[ "$N3" != "$N4" ] && ok "走了门控握手（nonce 变了）" || bad "没走门控（nonce 没变）"
[ "$SY0" = "$SY1" ] && ok "球没吞掉这一下（synthetic 未增）" || bad "球吞了（synthetic $SY0 → $SY1）"
echo "  注入耗时 ${MS}ms（含 120ms 让开 + 300ms 尾巴）"

echo
echo "=== 3. 绕过门控直接注入：不执行手势 + 自愈 ==="
ensure_settings
shots e; SY2=$(field synthetic)
# cmd input 绕过 /system/bin/input 包装器 → 球应该"认得这不是人"
cmd input tap $IN_X $IN_Y >/dev/null 2>&1
sleep 0.4
SY3=$(field synthetic)          # diag 在吞到触摸后 40ms 内就会刷新
[ "$SY3" -gt "$SY2" ] && ok "识别为机器触摸并计数（synthetic $SY2 → $SY3）" || bad "没识别成机器触摸（synthetic 没变）"
# 紧接着再来一下：此刻球正处在 1.5s 自愈窗口里，应该能穿过去
cmd input tap $IN_X $IN_Y >/dev/null 2>&1
sleep 1.0
shots f
SY4=$(field synthetic)
[ "$(changed e f)" = "1" ] && ok "自愈窗口内的第二次点击穿过去了（1.5s 自动让开）" || bad "第二次点击还是没过去（自愈失效）"
[ "$SY4" = "$SY3" ] && ok "第二次没再被吞（synthetic 停在 $SY4）" || bad "第二次也被吞了（$SY3 → $SY4）"

echo "=== 4. 球被杀掉后：普通点击照常 ==="
ensure_settings
shots h
am force-stop com.glslplayground.app >/dev/null 2>&1
sleep 2.6
$ADB_TAP tap $IN_X $IN_Y
sleep 1.2
shots i
[ "$(changed h i)" = "1" ] && ok "没有球时点击直达 App" || bad "没有球时点击也没了"

echo
echo "=== 汇总：$PASS 通过 / $FAIL 失败 ==="
[ "$FAIL" = "0" ] && echo "ALL PASS" || echo "HAS FAILURES"
exit $FAIL
