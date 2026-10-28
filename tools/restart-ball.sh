#!/data/data/com.termux/files/usr/bin/bash
# 把球重新拉起来（改完代码/被杀掉之后用）。
# 前提：已装好 APK；无障碍服务已经授权过一次（本脚本会再写一遍 secure 设置）。
# 用法：bash tools/restart-ball.sh
set -u
PKG=com.glslplayground.app
A11Y=$PKG/$PKG.BallA11yService
DIAG=/sdcard/Android/data/com.glslplayground.app/files/ball-diag

echo "[1/6] 停掉旧进程"
am force-stop $PKG 2>/dev/null
settings put secure enabled_accessibility_services null 2>/dev/null
sleep 0.6

echo "[2/6] 启动 App"
am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 3

echo "[3/6] 打开无障碍（受信任 overlay 资格）"
settings put secure enabled_accessibility_services $A11Y 2>/dev/null
settings put secure accessibility_enabled 1 2>/dev/null
sleep 3

echo "[4/6] 进入设置页"
input tap 541 1987      # 首页「设置」卡片
sleep 2
input swipe 540 2000 540 900 300
sleep 1

echo "[5/6] 打开「悬浮于桌面」"
input tap 930 440
sleep 4

echo "[6/6] 校验"
D=$(cat "$DIAG" 2>/dev/null)
echo "  $D"
case "$D" in
  *"region=1"*) echo "  ✅ 球在跑（Region 模式）" ;;
  *) echo "  ❌ 球没起来，看看 logcat | grep FloatingBall"; exit 1 ;;
esac
