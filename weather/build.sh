#!/usr/bin/env bash
# Builds weather/build/dumbtv-weather.apk, the forecast and title-search helper dumbtv home reads, with the same
# bare SDK tools as launcher/build.sh and the same signing key (build the launcher first).
# Needs a JDK 17 and an Android SDK with platforms;android-25 and any build-tools.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

VERSION_CODE=4
VERSION_NAME=0.4
MIN_SDK=25

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
JAR=$SDK/platforms/android-$MIN_SDK/android.jar
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)
[[ -f $JAR ]] || { echo "error: $JAR missing — sdkmanager 'platforms;android-$MIN_SDK'" >&2; exit 1; }
[[ -x $BT/aapt2 ]] || { echo "error: no build-tools in $SDK" >&2; exit 1; }
[[ -x ${JAVA_HOME:-}/bin/javac ]] || { echo "error: set JAVA_HOME to a JDK 17" >&2; exit 1; }

KEYSTORE=${DUMBTV_KEYSTORE:-$PWD/../launcher/release.keystore}
KEYPASS=${DUMBTV_KEYPASS:-dumbtvhome}
[[ -f $KEYSTORE ]] || { echo "error: no signing key at $KEYSTORE — run launcher/build.sh first" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-weather.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build
# apksigner signs over an existing output and then fails its own verify, so start clean.
rm -f build/dumbtv-weather.apk build/dumbtv-weather.apk.idsig

"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" -o "$WORK/app.apk"

find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/dumbtv-weather.apk "$WORK/aligned.apk"
"$BT/apksigner" verify build/dumbtv-weather.apk
printf 'built build/dumbtv-weather.apk  %s bytes\n' "$(wc -c < build/dumbtv-weather.apk | tr -d ' ')"
