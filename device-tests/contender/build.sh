#!/usr/bin/env bash
# Build the disposable second Android application's private microphone arbitration fixture.
set -euo pipefail
cd "$(dirname "$0")"
tools="${ANDROID_SDK_ROOT:?}/build-tools/36.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
out=../build/contender
mkdir -p "$out/classes" "$out/dex"
"$tools/aapt2" link --manifest AndroidManifest.xml -I "$platform" -o "$out/unsigned.apk"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$platform" -d "$out/classes" src/org/totalrecall/contender/VoiceActivity.java
find "$out/classes" -name '*.class' > "$out/inputs"
"$tools/d8" --min-api 26 --lib "$platform" --output "$out/dex" @"$out/inputs"
python3 - <<'PY'
from zipfile import ZipFile, ZIP_DEFLATED
with ZipFile('../build/contender/unsigned.apk', 'a', ZIP_DEFLATED) as archive:
    archive.write('../build/contender/dex/classes.dex', 'classes.dex')
PY
"$tools/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$tools/apksigner" sign --ks "${RECALL_KEYSTORE:?}" --ks-key-alias "${RECALL_KEY_ALIAS:-recall-test}" --ks-pass env:RECALL_KEY_PASSWORD --key-pass env:RECALL_KEY_PASSWORD --out "$out/Contender.apk" "$out/aligned.apk"
"$tools/apksigner" verify "$out/Contender.apk"
