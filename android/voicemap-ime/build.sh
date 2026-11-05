#!/data/data/com.termux/files/usr/bin/bash
# VoiceMap on-device build: aapt2 + javac + d8 + apksigner (no gradle)
set -euo pipefail

B="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_JAR:=/data/data/com.termux/files/home/oppo-watch-ai/.build/android.jar}"
KS="$B/voicemap.jks"
KSPASS="voicemap"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }

[ -f "$ANDROID_JAR" ] || { echo "android.jar missing: $ANDROID_JAR"; exit 1; }

if [ ! -f "$KS" ]; then
  log "creating keystore"
  keytool -genkeypair -keystore "$KS" -storepass "$KSPASS" -keypass "$KSPASS" \
    -alias voicemap -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=VoiceMap, O=PJ, C=CN" >/dev/null 2>&1
fi

build_apk() {
  local name="$1" dir="$2" libs="$3"
  local work="$B/build/$name"
  rm -rf "$work"; mkdir -p "$work/gen" "$work/classes" "$work/dex"

  log "[$name] aapt2 compile"
  aapt2 compile --dir "$dir/res" -o "$work/res.zip" >/dev/null

  log "[$name] aapt2 link"
  local assets=()
  [ -d "$dir/assets" ] && assets=(-A "$dir/assets")
  aapt2 link -o "$work/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$dir/AndroidManifest.xml" \
    -R "$work/res.zip" \
    --java "$work/gen" \
    --min-sdk-version 26 --target-sdk-version 33 \
    "${assets[@]+"${assets[@]}"}" \
    --auto-add-overlay >/dev/null

  log "[$name] javac"
  local srcs
  srcs="$(find "$dir/src" "$work/gen" -name '*.java')"
  javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -classpath "$B/build/api.jar" \
    -d "$work/classes" $srcs 2>&1 | grep -v 'obsolete\|Xlint:-options\|^3 warnings\|^1 warning' || true
  if [ ! -d "$work/classes" ] || [ -z "$(find "$work/classes" -name '*.class' | head -1)" ]; then
    echo "[$name] javac produced nothing"; exit 1
  fi

  log "[$name] d8"
  find "$work/classes" -name '*.class' > "$work/classlist.txt"
  d8 --release --min-api 26 --lib "$ANDROID_JAR" --output "$work/dex" \
     --classpath "$B/build/api.jar" $(cat "$work/classlist.txt") >/dev/null

  log "[$name] packaging"
  python3 - "$work/unsigned.apk" "$work/dex/classes.dex" <<'PY'
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

  log "[$name] sign"
  apksigner sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$B/out/$name.apk" "$work/unsigned.apk" >/dev/null

  log "[$name] ok -> out/$name.apk"
}

mkdir -p "$B/out"
log "building xposed api stubs"
rm -rf "$B/build/api"; mkdir -p "$B/build/api"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$B/build/api" \
  $(find "$B/api" -name '*.java') 2>&1 | grep -v 'obsolete\|Xlint' || true
jar cf "$B/build/api.jar" -C "$B/build/api" .

build_apk voicemap-ime "$B/module" ""
build_apk voicemap "$B/app" ""
ls -la "$B/out"
