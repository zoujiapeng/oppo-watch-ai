#!/data/data/com.termux/files/usr/bin/bash
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
B="$ROOT/.build"
AJAR="$B/android.jar"

mkdir -p "$B/gen" "$B/classes"

cp "$ROOT"/*.html "$HERE/assets/"

if [ ! -f "$AJAR" ]; then
  curl -sL -o "$AJAR" "https://github.com/Sable/android-platforms/raw/master/android-30/android.jar"
fi

cd "$HERE"
rm -rf "$B/gen" "$B/classes"
mkdir -p "$B/gen" "$B/classes"
mkdir -p "$HERE/assets"

aapt2 compile --dir res -o "$B/res.zip"
aapt2 link -o "$B/app-unsigned.apk" -I "$AJAR" \
  --manifest AndroidManifest.xml -A assets \
  --java "$B/gen" "$B/res.zip"

javac --release 8 -classpath "$AJAR" -d "$B/classes" \
  $(find "$B/gen" src -name '*.java')

d8 --release --min-api 21 --lib "$AJAR" --output "$B" \
  $(find "$B/classes" -name '*.class')

python3 - "$B/app-unsigned.apk" "$B/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'classes.dex')
PY

if [ ! -f "$B/debug.keystore" ]; then
  keytool -genkeypair -keystore "$B/debug.keystore" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

rm -f "$B/glsl-playground.apk"
apksigner sign --ks "$B/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$B/glsl-playground.apk" "$B/app-unsigned.apk"

cp "$B/glsl-playground.apk" "$ROOT/glsl-playground.apk"
echo "APK ready: $ROOT/glsl-playground.apk"
