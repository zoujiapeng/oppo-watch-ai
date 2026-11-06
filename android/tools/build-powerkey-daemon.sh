#!/data/data/com.termux/files/usr/bin/bash
# 电源键常驻监听：PowerKeyDaemon.java → assets/powerkey-daemon.jar
# （App / 安装脚本把它释放到 /data/local/tmp 或 /data/adb，用 app_process 以 root 起）
set -euo pipefail
B="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$B/../.." && pwd)"
: "${ANDROID_JAR:=$REPO/.build/android.jar}"
W="$B/.build-powerkey"
OUT="$REPO/android/assets/powerkey-daemon.jar"

[ -f "$ANDROID_JAR" ] || { echo "android.jar missing: $ANDROID_JAR"; exit 1; }
rm -rf "$W"; mkdir -p "$W/classes" "$W/dex"

javac --release 8 -classpath "$ANDROID_JAR" -d "$W/classes" "$B/PowerKeyDaemon.java"
d8 --release --min-api 21 --lib "$ANDROID_JAR" --output "$W/dex" \
  $(find "$W/classes" -name '*.class')

python3 - "$OUT" "$W/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'classes.dex')
print('ok ->', sys.argv[1])
PY
