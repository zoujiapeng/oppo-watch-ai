#!/data/data/com.termux/files/usr/bin/bash
# 构建并部署"定向注入"守护进程到 /data/local/tmp/pointer.jar（改完 PointerDaemon.java 重跑即可）。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
B="$ROOT/.build/pointer"
mkdir -p "$B"
javac --release 8 -classpath "$ROOT/.build/android.jar" -d "$B" "$ROOT/android/tools/PointerDaemon.java"
d8 --release --min-api 21 --lib "$ROOT/.build/android.jar" --output "$B" $(find "$B" -name '*.class')
python3 - "$B/pointer.jar" "$B/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY
su -c "cp -f '$B/pointer.jar' /data/local/tmp/pointer.jar; chmod 644 /data/local/tmp/pointer.jar"
echo "deployed: /data/local/tmp/pointer.jar"
