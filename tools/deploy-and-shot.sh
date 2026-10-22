#!/data/data/com.termux/files/usr/bin/bash
# 装机 + 重启悬浮球 + 按需截一张玻璃图。用法： bash tools/deploy-and-shot.sh <名字> [zone-open|menu-dirs|zone-move:tl ...]
set -e
NAME="${1:-shot}"
shift || true
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D=/sdcard/Android/data/com.glslplayground.app/files
PKG=com.glslplayground.app

su -c "cp $ROOT/glsl-playground.apk /data/local/tmp/app.apk; chmod 644 /data/local/tmp/app.apk"
su -c "pm install -r --user 0 /data/local/tmp/app.apk" | tail -1
su -c "env -i PATH=/system/bin HOME=/data/local/tmp am start-foreground-service -n $PKG/.FloatingBallService" >/dev/null
sleep 4

for step in "$@"; do
  case "$step" in
    zone-move:*) su -c "echo ${step#zone-move:} > $D/zone-move" ;;
    menu-search:*) su -c "echo ${step#menu-search:} > $D/menu-search" ;;
    *) su -c "touch $D/$step" ;;
  esac
  sleep 1.6
done

su -c "touch $D/ballshot"
sleep 2
su -c "cp /data/data/$PKG/files/ball-capture.png /sdcard/$NAME.png; chmod 644 /sdcard/$NAME.png"
cp "/sdcard/$NAME.png" "$HOME/$NAME.png"
echo "shot -> $HOME/$NAME.png"
