#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the installed Android SDK}"
: "${RECALL_KEYSTORE:?Set RECALL_KEYSTORE to the existing private test keystore}"
: "${RECALL_KEY_PASSWORD:?Supply the private signing password via the environment}"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
tools="$ANDROID_SDK_ROOT/build-tools/36.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
version=$(python3 -c 'from xml.etree import ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName"))')
commit=$(git rev-parse HEAD 2>/dev/null || echo source-export)
output="build/TotalRecall-$version.apk"
mkdir -p assets/listening
cp ../mint/web/sound-groups.tsv ../mint/web/situation-rules.tsv ../mint/web/speech-context.tsv assets/listening/
python3 prepare-speech.py
python3 tests/prepare-prosody.py
rm -rf build/classes build/dex build/generated
mkdir -p build/classes build/dex build/generated/org/continuity/recall
printf 'package org.continuity.recall; public final class BuildInfo { public static final String VERSION="%s"; public static final String COMMIT="%s"; }\n' "$version" "$commit" >build/generated/org/continuity/recall/BuildInfo.java
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$platform" -A assets -0 zip build/resources.zip
find src build/generated -name '*.java' -print >build/sources.list
javac --release 8 -encoding UTF-8 -classpath "$platform:build/speech/vosk.jar:build/speech/jna.jar:build/speech/zxing-core.jar:build/speech/tensorflow-lite.jar:build/speech/tensorflow-lite-api.jar" -d build/classes @build/sources.list
find build/classes -name '*.class' -print >build/classes.list
"$tools/d8" --release --min-api 26 --lib "$platform" --output build/dex @build/classes.list build/speech/vosk.jar build/speech/jna.jar build/speech/zxing-core.jar build/speech/tensorflow-lite.jar build/speech/tensorflow-lite-api.jar
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
base = Path('build/speech')
libs = sorted((base / 'lib').glob('*/*.so'))
if not libs: raise RuntimeError('Missing native speech libraries')
with ZipFile('build/unsigned.apk', 'a') as apk:
    for item in sorted(Path('build/dex').glob('*.dex')):
        apk.write(item, item.name, compress_type=ZIP_DEFLATED)
    for item in libs:
        apk.write(item, str(item.relative_to(base)), compress_type=ZIP_DEFLATED)
PY
"$tools/zipalign" -f -P 16 4 build/unsigned.apk build/aligned.apk
"$tools/apksigner" sign --ks "$RECALL_KEYSTORE" --ks-key-alias recall-test --ks-pass env:RECALL_KEY_PASSWORD --key-pass env:RECALL_KEY_PASSWORD --out "$output" build/aligned.apk
"$tools/apksigner" verify --verbose --print-certs "$output" >build/signature.txt
"$tools/zipalign" -c -P 16 4 "$output"
"$tools/aapt2" dump badging "$output" >build/package.txt
sha256sum "$output" >build/SHA256SUMS
python3 build-receipt.py
printf 'Built %s\n' "$output"
