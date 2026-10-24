#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to installed Android SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${RECALL_KEYSTORE:?Set path to a private signing keystore outside the repository}"
: "${RECALL_KEY_PASSWORD:?Set signing password through the environment}"
RECALL_KEY_ALIAS="${RECALL_KEY_ALIAS:-recall-test}"
RECALL_SIGNING_KIND="${RECALL_SIGNING_KIND:-unverified-local}"
case "$RECALL_SIGNING_KIND" in ephemeral-test|owner-managed|unverified-local) ;; *) echo 'Unknown signing kind' >&2; exit 1;; esac
TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"
test -x "$JAVA_HOME/bin/javac"
test -s "$PLATFORM"
test -f "$RECALL_KEYSTORE"
# Keep evidence/host-test outputs; discard every compiler/linker output before building.
rm -rf build/classes build/dex build/generated
mkdir -p build/classes build/dex build/generated/org/continuity/recall
export RECALL_SIGNING_KIND
python3 build-metadata.py prepare
RECALL_VERSION=$(python3 -c 'import json; print(json.load(open("build/build-evidence.json"))["version"])')
APK="build/TotalRecall-$RECALL_VERSION.apk"
trap 'rm -f "$APK"' ERR
rm -f "$APK" build/unsigned.apk build/aligned.apk build/resources.zip \
  build/SHA256SUMS build/signature.txt build/package.txt build/TEST-SIGNING-WARNING.txt
"$TOOLS/aapt2" compile --dir res -o build/resources.zip
"$TOOLS/aapt2" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$PLATFORM" build/resources.zip
find src build/generated -name '*.java' -print | LC_ALL=C sort > build/sources.list
javac --release 8 -encoding UTF-8 -classpath "$PLATFORM" -d build/classes @build/sources.list
find build/classes -name '*.class' -print | LC_ALL=C sort > build/classes.list
"$TOOLS/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex @build/classes.list
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo
with ZipFile('build/unsigned.apk', 'a', ZIP_DEFLATED) as z:
    for f in sorted(Path('build/dex').glob('*.dex')):
        entry = ZipInfo(f.name, (2000, 1, 1, 0, 0, 0))
        entry.compress_type = ZIP_DEFLATED
        z.writestr(entry, f.read_bytes())
PY
"$TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$TOOLS/apksigner" sign --ks "$RECALL_KEYSTORE" --ks-key-alias "$RECALL_KEY_ALIAS" --ks-pass env:RECALL_KEY_PASSWORD --key-pass env:RECALL_KEY_PASSWORD --out "$APK" build/aligned.apk
"$TOOLS/apksigner" verify --verbose --print-certs "$APK" > build/signature.txt
"$TOOLS/aapt2" dump badging "$APK" > build/package.txt
python3 build-metadata.py verify
(cd build && sha256sum "TotalRecall-$RECALL_VERSION.apk" > SHA256SUMS)
if [ "$RECALL_SIGNING_KIND" != owner-managed ]; then
  printf '%s\n' 'EXPERIMENTAL: signer continuity is not established. Do not uninstall or replace an installation containing unexported data. Different signing certificates cannot upgrade one another.' > build/TEST-SIGNING-WARNING.txt
else
  printf '%s\n' 'Owner-managed signing was requested. Compare this certificate with your stored release certificate before installing. Signing does not establish production readiness.' > build/TEST-SIGNING-WARNING.txt
fi
printf 'Built: android/%s\n' "$APK"
