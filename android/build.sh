#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to installed Android SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${RECALL_KEYSTORE:?Use a private user-owned test keystore outside the repository}"
: "${RECALL_KEY_PASSWORD:?Set private signing password}"
TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p build/classes build/dex build/generated/org/continuity/recall
COMMIT=$(git rev-parse HEAD)
printf 'package org.continuity.recall; public final class BuildInfo { public static final String VERSION="0.7.0-test"; public static final String COMMIT="%s"; }\n' "$COMMIT" > build/generated/org/continuity/recall/BuildInfo.java
"$TOOLS/aapt2" compile --dir res -o build/resources.zip
"$TOOLS/aapt2" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$PLATFORM" build/resources.zip
find src build/generated -name '*.java' -print > build/sources.list
javac --release 8 -encoding UTF-8 -classpath "$PLATFORM" -d build/classes @build/sources.list
find build/classes -name '*.class' -print > build/classes.list
"$TOOLS/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex @build/classes.list
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
with ZipFile('build/unsigned.apk','a',ZIP_DEFLATED) as z:
 for f in sorted(Path('build/dex').glob('*.dex')):z.write(f,f.name)
PY
"$TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$TOOLS/apksigner" sign --ks "$RECALL_KEYSTORE" --ks-key-alias recall-test --ks-pass env:RECALL_KEY_PASSWORD --key-pass env:RECALL_KEY_PASSWORD --out build/TotalRecall-0.7.0-test.apk build/aligned.apk
"$TOOLS/apksigner" verify --verbose --print-certs build/TotalRecall-0.7.0-test.apk > build/signature.txt
"$TOOLS/aapt2" dump badging build/TotalRecall-0.7.0-test.apk > build/package.txt
sha256sum build/TotalRecall-0.7.0-test.apk > build/SHA256SUMS
printf 'Built: android/build/TotalRecall-0.7.0-test.apk\n'
