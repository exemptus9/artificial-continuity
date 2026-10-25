#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
rm -rf build/instrumentation
mkdir -p build/instrumentation/classes build/instrumentation/dex
find instrumentation -name '*.java' -print | LC_ALL=C sort > build/instrumentation/sources.list
"$JAVA_HOME/bin/javac" --release 8 -cp "$PLATFORM:build/classes" -d build/instrumentation/classes @build/instrumentation/sources.list
find build/instrumentation/classes -name '*.class' -print | LC_ALL=C sort > build/instrumentation/classes.list
"$TOOLS/d8" --release --min-api 26 --lib "$PLATFORM" --classpath build/classes --output build/instrumentation/dex @build/instrumentation/classes.list
"$TOOLS/aapt2" link -o build/instrumentation/unsigned.apk --manifest instrumentation/AndroidManifest.xml -I "$PLATFORM"
python3 - <<'PY'
from zipfile import ZipFile
with ZipFile('build/instrumentation/unsigned.apk','a') as z:z.write('build/instrumentation/dex/classes.dex','classes.dex')
PY
"$TOOLS/zipalign" -f 4 build/instrumentation/unsigned.apk build/instrumentation/aligned.apk
"$TOOLS/apksigner" sign --ks "$RECALL_KEYSTORE" --ks-key-alias recall-test --ks-pass env:RECALL_KEY_PASSWORD --out build/instrumentation/test.apk build/instrumentation/aligned.apk
