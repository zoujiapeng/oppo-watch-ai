#!/data/data/com.termux/files/usr/bin/bash
# agent 触摸门控（球让路协议）端到端验证 —— 不需要 DSH 参与。
#
# 做三件事：
#   1) 让路：原子写 ACT 信号（模拟 DSH 要注入 click）→ 断言球的「代理触摸窗口」
#      在 ~500ms 内变成 notTouchable，且球回了匹配的 ACK；
#   2) 恢复：等 1.5s 迟滞过期 → 断言窗口恢复可触摸；
#   3) 证据：打印球侧日志。
#
# 球（悬浮水晶球服务）必须在跑：设置页打开「悬浮于桌面」。
# 用法：bash android/tools/ball-guard-verify.sh
#
# 注意：信号**必须原子写**（.tmp + mv）。球每 60ms 读一次，直接 `>` 重定向会先截断，
# 球容易读到半个 nonce → ACK 对不上，表现为"偶尔让路"。实测就是这么翻车的（3 次里坏 1 次）。
# 插件侧（dsh-preset-mobile-use）用同一套协议；不重启 DSH 也能做真机贯通测试：
#   node android/tools/ball-guard-plugin-e2e.mjs swipe

set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
# 信号目录 = 球的外部私有目录（球免权限可写；/data/local/tmp 是 shell_data_file 标签，
# untrusted_app 只能读不能写，ACK 会 EACCES）。可用 DSH_BALL_GUARD_DIR 覆盖。
GATEDIR="${DSH_BALL_GUARD_DIR:-/sdcard/Android/data/com.glslplayground.app/files}"
ACT="$GATEDIR/dsh-ball-act"
ACK="$GATEDIR/dsh-ball-ack"
PKG=com.glslplayground.app

echo "== 前置检查 =="
if ! pidof "$PKG" >/dev/null 2>&1; then
  echo "[FAIL] $PKG 没在跑：先在手机上打开 App 并点「悬浮于桌面」，球起来再跑这个脚本"
  exit 2
fi
echo "[ok] $PKG pid=$(pidof $PKG)  信号目录=$GATEDIR"
[ -d "$GATEDIR" ] || { echo "[FAIL] 信号目录不存在：$GATEDIR"; exit 2; }

BEFORE=$(python3 "$HERE/ball-proxy-state.py")
echo "[ball] 信号前：$BEFORE"

echo
echo "== 1) 打 ACT 信号（模拟 agent 要 click）=="
NONCE="verify-$(date +%s%N)"
rm -f "$ACK"
printf '%s\n' "$NONCE" > "$ACT.tmp-$$" && mv -f "$ACT.tmp-$$" "$ACT"
echo "[ball] nonce=$NONCE"

DEADLINE=$(( $(date +%s%N) / 1000000 + 1500 ))
STATE=""
while [ $(( $(date +%s%N) / 1000000 )) -lt $DEADLINE ]; do
  STATE=$(python3 "$HERE/ball-proxy-state.py")
  case "$STATE" in notTouchable*) break ;; esac
  sleep 0.05
done
echo "[ball] 信号后：$STATE"

ACKVAL=$(cat "$ACK" 2>/dev/null | tr -d '\n')
if [ "$ACKVAL" = "$NONCE" ]; then
  echo "[ok] ACK 匹配：$ACKVAL"
else
  echo "[FAIL] ACK 不匹配：'$ACKVAL' != '$NONCE'"
fi
case "$STATE" in
  notTouchable*) echo "[ok] 代理窗口已退出命中测试（agent 的触摸不会落到球上）" ;;
  *)            echo "[FAIL] 代理窗口仍然可触摸" ;;
esac

echo
echo "== 2) 等信号过期（1.5s 迟滞）后应自动恢复 =="
sleep 2.2
AFTER=$(python3 "$HERE/ball-proxy-state.py")
echo "[ball] 恢复后：$AFTER"
case "$AFTER" in
  touchable*) echo "[ok] 球恢复可触摸（用户手指能点/拖）" ;;
  *)          echo "[FAIL] 球没有恢复可触摸" ;;
esac

echo
echo "== 3) 日志证据（球侧）=="
logcat -d -t 400 2>/dev/null | grep -i "FloatingBall" | tail -8

# 收尾：清掉信号，别让门控一直挂着
rm -f "$ACT" "$ACK" "$ACT.tmp-$$"
echo
echo "（已清理 $ACT / $ACK）"
