#!/usr/bin/env bash
#
# Build the LSPosed module APK using ONLY the locally-installed Android build-tools
# (aapt2 / d8 / zipalign / apksigner) and the Android Studio bundled JDK (JBR).
# No Gradle / AGP download required.
#
set -euo pipefail

# ---- toolchain -------------------------------------------------------------
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="$SDK/build-tools/36.1.0"
ANDROID_JAR="$SDK/platforms/android-36.1/android.jar"
JBR="${JBR_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
JAVAC="$JBR/bin/javac"
KEYTOOL="$JBR/bin/keytool"

# d8 / apksigner are Java wrappers; make sure they find the bundled JDK.
export JAVA_HOME="$JBR"
export PATH="$JBR/bin:$PATH"

# ---- project layout --------------------------------------------------------
PKG="com.leiting.fleet.lsposedfix"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
LIBS="$ROOT/libs"
BUILD="$ROOT/build"
OUT="$ROOT/dist"
XPOSED_JAR="$LIBS/xposed-api-82.jar"

for t in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$JAVAC" "$KEYTOOL"; do
  [ -x "$t" ] || { echo "ERROR: missing tool: $t" >&2; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "ERROR: missing $ANDROID_JAR" >&2; exit 1; }
if [ ! -f "$XPOSED_JAR" ]; then
  echo "Fetching Xposed API 82 jar..."
  mkdir -p "$LIBS"
  curl -fSL "https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar" -o "$XPOSED_JAR"
fi

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "[1/7] compile resources"
"$AAPT2" compile --dir "$SRC/res" -o "$BUILD/compiled_res.zip"

echo "[2/7] link resources + assets + manifest"
# AGP injects the package from 'namespace'; for raw aapt2 we inject it here.
sed "s#<manifest #<manifest package=\"$PKG\" #" "$SRC/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  -R "$BUILD/compiled_res.zip" \
  -A "$SRC/assets" \
  --min-sdk-version 21 \
  --target-sdk-version 35 \
  --auto-add-overlay

echo "[3/7] compile java"
find "$SRC/java" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -source 17 -target 17 -encoding UTF-8 -nowarn \
  -classpath "$ANDROID_JAR:$XPOSED_JAR" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[4/7] dex"
# shellcheck disable=SC2046
"$D8" --min-api 21 --lib "$ANDROID_JAR" --lib "$XPOSED_JAR" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

echo "[5/7] add classes.dex to apk"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q "$BUILD/unsigned.apk" classes.dex )

echo "[6/7] zipalign"
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[7/7] sign"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$APKSIGNER" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/$PKG.apk" "$BUILD/aligned.apk"

"$APKSIGNER" verify "$OUT/$PKG.apk" >/dev/null
echo
echo "BUILD OK -> $OUT/$PKG.apk"
