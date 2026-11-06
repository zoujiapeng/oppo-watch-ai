#!/data/data/com.termux/files/usr/bin/bash
# 电源键模块：aapt2 + javac + d8 + apksigner（不用 gradle / Android Studio）
#
#   bash android/powerkey-hook/build.sh
#   → android/powerkey-hook/out/powerkey-hook.apk
#
# Xposed api 桩复用 voicemap 模块那份（android/voicemap-ime/api），签名复用它的 keystore。
set -euo pipefail

B="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$B/../.." && pwd)"
: "${ANDROID_JAR:=$REPO/.build/android.jar}"
API="$B/../voicemap-ime/api"
KS="$B/../voicemap-ime/voicemap.jks"
KSPASS="${KSPASS:-voicemap}"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }

[ -f "$ANDROID_JAR" ] || { echo "android.jar missing: $ANDROID_JAR（先跑一次 android/build.sh）"; exit 1; }
[ -d "$API" ] || { echo "xposed api 桩没找到：$API"; exit 1; }
[ -f "$KS" ] || { echo "keystore 没找到：$KS（先跑一次 voicemap-ime/build.sh）"; exit 1; }

W="$B/.build"
rm -rf "$W"; mkdir -p "$W/api" "$W/gen" "$W/classes" "$W/dex" "$B/out"

log "编译 xposed api 桩"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$W/api" \
  $(find "$API" -name '*.java') 2>&1 | grep -v 'obsolete\|Xlint' || true
jar cf "$W/api.jar" -C "$W/api" .

log "aapt2 compile / link"
aapt2 compile --dir "$B/module/res" -o "$W/res.zip" >/dev/null
aapt2 link -o "$W/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$B/module/AndroidManifest.xml" \
  -R "$W/res.zip" \
  -A "$B/module/assets" \
  --java "$W/gen" \
  --min-sdk-version 26 --target-sdk-version 33 \
  --auto-add-overlay >/dev/null

log "javac"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -classpath "$W/api.jar" -d "$W/classes" \
  $(find "$B/module/src" "$W/gen" -name '*.java')
[ -n "$(find "$W/classes" -name '*.class' | head -1)" ] || { echo "javac 没产出 class"; exit 1; }

log "d8"
find "$W/classes" -name '*.class' > "$W/classlist.txt"
d8 --release --min-api 26 --lib "$ANDROID_JAR" --output "$W/dex" \
   --classpath "$W/api.jar" $(cat "$W/classlist.txt") >/dev/null

log "打包 + 签名"
python3 - "$W/unsigned.apk" "$W/dex/classes.dex" <<'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        if it.filename == "classes.dex":
            continue
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, "classes.dex")
shutil.move(tmp, apk)
print("   packed", os.path.getsize(apk), "bytes")
PY

apksigner sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$B/out/powerkey-hook.apk" "$W/unsigned.apk" >/dev/null

log "ok -> $B/out/powerkey-hook.apk"
ls -l "$B/out/powerkey-hook.apk"
