#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Android SDK required}"
: "${JAVA_HOME:?JDK 17 required}"
: "${RECALL_KEYSTORE:?Use the same keystore as the target APK}"
: "${RECALL_KEY_PASSWORD:?Signing password required}"
TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
RECALL_KEY_ALIAS="${RECALL_KEY_ALIAS:-recall-test}"
rm -rf build/instrumentation
mkdir -p build/instrumentation/classes build/instrumentation/dex
# Derive target package, preventing a stale hardcoded test target after preview isolation.
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as E
ns = '{http://schemas.android.com/apk/res/android}'
E.register_namespace('android', 'http://schemas.android.com/apk/res/android')
app = E.parse('AndroidManifest.xml').getroot()
test = E.parse('instrumentation/AndroidManifest.xml')
test.getroot().find('instrumentation').set(ns + 'targetPackage', app.get('package'))
if Path('instrumentation/TemporalNativeTests.java').is_file():
    E.SubElement(test.getroot(), 'instrumentation', {
        ns + 'name': 'org.continuity.recall.TemporalNativeTests',
        ns + 'targetPackage': app.get('package'),
        ns + 'label': 'Synthetic temporal acceptance only',
    })
test.write('build/instrumentation/AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
PY
find instrumentation -name '*.java' -print | LC_ALL=C sort > build/instrumentation/sources.list
"$JAVA_HOME/bin/javac" --release 8 -cp "$PLATFORM:build/classes" -d build/instrumentation/classes @build/instrumentation/sources.list
find build/instrumentation/classes -name '*.class' -print | LC_ALL=C sort > build/instrumentation/classes.list
"$TOOLS/d8" --release --min-api 26 --lib "$PLATFORM" --classpath build/classes --output build/instrumentation/dex @build/instrumentation/classes.list
"$TOOLS/aapt2" link -o build/instrumentation/unsigned.apk --manifest build/instrumentation/AndroidManifest.xml -I "$PLATFORM"
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
with ZipFile('build/instrumentation/unsigned.apk', 'a') as z:
    for f in sorted(Path('build/instrumentation/dex').glob('*.dex')):
        z.write(f, f.name)
PY
"$TOOLS/zipalign" -f 4 build/instrumentation/unsigned.apk build/instrumentation/aligned.apk
"$TOOLS/apksigner" sign --ks "$RECALL_KEYSTORE" --ks-key-alias "$RECALL_KEY_ALIAS" --ks-pass env:RECALL_KEY_PASSWORD --key-pass env:RECALL_KEY_PASSWORD --out build/instrumentation/test.apk build/instrumentation/aligned.apk
"$TOOLS/apksigner" verify --verbose --print-certs build/instrumentation/test.apk > build/instrumentation/signature.txt
