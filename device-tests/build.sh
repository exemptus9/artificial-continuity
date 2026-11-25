#!/usr/bin/env bash
# Build this test-only instrumentation package after android/build.sh completes.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Android SDK required}" "${RECALL_KEYSTORE:?Use the same test signer as target}" "${RECALL_KEY_PASSWORD:?Private signer password required}"
SRC_CLASSES="${TARGET_CLASSES:-../android/build/classes}"
TOOLS="${ANDROID_SDK_ROOT}/build-tools/36.0.0"
PLATFORM="${ANDROID_SDK_ROOT}/platforms/android-36/android.jar"
bash test-engine.sh
test -f "$SRC_CLASSES/org/totalrecall/listening/SelectiveEngine.class" || { echo 'Set TARGET_CLASSES to the compiled target app classes.' >&2; exit 1; }
TEST_BUILD=build
rm -rf "$TEST_BUILD/classes" "$TEST_BUILD/dex"
mkdir -p "$TEST_BUILD/classes" "$TEST_BUILD/dex"
"$TOOLS/aapt2" link --manifest AndroidManifest.xml -I "$PLATFORM" -A assets -o "$TEST_BUILD/test-unsigned.apk"
find src -name '*.java' | sort > "$TEST_BUILD/sources"
javac -encoding UTF-8 --release 8 -classpath "$PLATFORM:$SRC_CLASSES" -d "$TEST_BUILD/classes" @"$TEST_BUILD/sources"
find "$TEST_BUILD/classes" -name '*.class' > "$TEST_BUILD/inputs"
"$TOOLS/d8" --min-api 26 --lib "$PLATFORM" --classpath "$SRC_CLASSES" --output "$TEST_BUILD/dex" @"$TEST_BUILD/inputs"
python3 - <<'PY'
from zipfile import ZipFile,ZIP_DEFLATED
with ZipFile('build/test-unsigned.apk','a',ZIP_DEFLATED) as z:z.write('build/dex/classes.dex','classes.dex')
PY
"$TOOLS/zipalign" -f 4 "$TEST_BUILD/test-unsigned.apk" "$TEST_BUILD/test-aligned.apk"
"$TOOLS/apksigner" sign --ks "$RECALL_KEYSTORE" --ks-key-alias "${RECALL_KEY_ALIAS:-recall-test}" --ks-pass env:RECALL_KEY_PASSWORD --key-pass env:RECALL_KEY_PASSWORD --out "$TEST_BUILD/RecallSmoke.apk" "$TEST_BUILD/test-aligned.apk"
"$TOOLS/apksigner" verify "$TEST_BUILD/RecallSmoke.apk"
bash contender/build.sh
