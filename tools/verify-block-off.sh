#!/data/data/com.termux/files/usr/bin/bash
# 重启后判定：这台机认不认 block_untrusted_touches=0。
# 判据：YIELD 期间（球只置空触摸区、窗口不动）注入一次，看还有没有
#       "Dropping untrusted touch"。0 条=开关生效；≥1 条=MIUI 写死。
set -u
PKG=com.glslplayground.app
D=/sdcard/Android/data/$PKG/files
DIAG=$D/ball-diag
RECTF=$D/ball-rect
ACKF=$D/dsh-ball-ack
FLAG=$D/region-only

echo "=== 0. 开关 ==="
V=$(settings --user 0 get secure block_untrusted_touches 2>/dev/null)
echo "  block_untrusted_touches = $V"
[ "$V" = "0" ] || { echo "  ❌ 先 settings put secure block_untrusted_touches 0 再重启"; exit 2; }

echo "=== 1. 球 ==="
if [ ! -s "$DIAG" ]; then
  echo "  球没跑 → 拉起"
  /data/data/com.termux/files/usr/bin/bash "$(dirname "$0")/restart-ball.sh" 2>&1 | tail -3
  sleep 2
fi
sed 's/^/  /' "$DIAG" 2>/dev/null

echo "=== 2. 只置空 region、不挪窗口 ==="
touch "$FLAG"
sleep 0.6

echo "=== 3. 靶子 ==="
am force-stop com.android.settings >/dev/null 2>&1
sleep 0.4
am start -a android.settings.SETTINGS >/dev/null 2>&1
sleep 2.2
RECT=$(awk '{print $1" "$2" "$3" "$4}' "$RECTF" 2>/dev/null)
BX=$(echo "$RECT" | awk '{print $1}'); BY=$(echo "$RECT" | awk '{print $2}')
BW=$(echo "$RECT" | awk '{print $3}'); BH=$(echo "$RECT" | awk '{print $4}')
CX=$((BX + BW/2)); CY=$((BY + BH/2))
echo "  球矩形=$RECT → 注入 ($CX,$CY)"

echo "=== 4. YIELD → 立刻注入 ==="
rm -f /data/local/tmp/vb.out
/data/data/com.termux/files/usr/bin/node "$(dirname "$0")/../.build/hold.mjs" 6000 > /data/local/tmp/vb.out 2>&1 &
OK=0
i=0
while [ $i -lt 25 ]; do
  sleep 0.1
  grep -q "acked" /data/local/tmp/vb.out 2>/dev/null && { OK=1; break; }
  i=$((i+1))
done
if [ "$OK" != "1" ]; then echo "  ❌ 门控 socket 没 ACK → 判定无效"; rm -f "$FLAG"; exit 3; fi
echo "  ACK ok，立刻注入（让开窗口只有 1.2s）"
logcat -c
cmd input tap $CX $CY
sleep 0.8

DROP=$(logcat -d | grep -c "Dropping untrusted")
LINE=$(logcat -d | grep -m1 "Untrusted touch due to occlusion by" | sed 's/^.*InputDispatcher: //')
echo "  Dropping 条数 = $DROP"
[ -n "$LINE" ] && echo "  $LINE"
rm -f "$FLAG"

echo
if [ "$DROP" = "0" ]; then
  echo "✅ 开关生效 → 球可以永久零闪烁（只置空 region）"
  exit 0
else
  echo "❌ 仍被拦截 → 开关被 MIUI 写死，走 TextureView"
  exit 1
fi
