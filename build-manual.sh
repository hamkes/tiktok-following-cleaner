#!/usr/bin/env bash
# build-manual.sh — build the debug APK without Gradle.
#
# Why: in the assistant's VM the Gradle 8.9 daemon process is killed
# deterministically as soon as a build starts (host memory pressure),
# so the compile-verification in this environment uses the same tools
# Gradle/AGP would call: aapt2 -> kotlinc -> d8 -> zipalign -> apksigner.
# On a normal machine, prefer the Gradle build:  ./gradlew assembleDebug
#
# Expected layout (override via env):
#   JAVA_HOME      JDK 17   (default ~/workspace/tools/jdk17)
#   ANDROID_HOME   SDK      (default ~/workspace/tools/sdk)
#   KOTLIN_HOME    kotlinc  (default ~/workspace/tools/kotlin)
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/workspace/tools/sdk}"
KOTLIN_HOME="${KOTLIN_HOME:-$HOME/workspace/tools/kotlin}"
BT="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
PATH="$JAVA_HOME/bin:$PATH"

B=build/manual
rm -rf "$B"
mkdir -p "$B/gen" "$B/dex"

# Version/output overrides (defaults reproduce the v1.0 build exactly).
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
OUT_APK="${OUT_APK:-TikTokCleaner-debug.apk}"

echo "== aapt2 compile + link =="
"$BT/aapt2" compile --dir app/src/main/res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$B/gen" --min-sdk-version 26 --target-sdk-version 35 \
  "$B/res.zip"

echo "== kotlinc =="
# shellcheck disable=SC2046
"$KOTLIN_HOME/bin/kotlinc" \
  $(find app/src/main/java -name '*.kt') \
  $(find "$B/gen" -name 'R.java') \
  -cp "$ANDROID_JAR" -jvm-target 17 -nowarn \
  -d "$B/classes.jar"

echo "== d8 =="
"$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$B/dex" \
  "$B/classes.jar" "$KOTLIN_HOME/lib/kotlin-stdlib.jar"

echo "== package + align + sign =="
cp "$B/unsigned.apk" "$B/with-dex.apk"
( cd "$B/dex" && zip -q ../with-dex.apk classes.dex )
"$BT/zipalign" -f -p 4 "$B/with-dex.apk" "$B/aligned.apk"
if [ ! -f build-debug.keystore ]; then
  keytool -genkeypair -keystore build-debug.keystore -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks build-debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT_APK" "$B/aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --verbose "$OUT_APK" | head -4
"$BT/aapt2" dump badging "$OUT_APK" | head -6
ls -la "$OUT_APK"
echo "BUILD OK"
