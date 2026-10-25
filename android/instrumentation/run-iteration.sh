#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${RECALL_SYNTHETIC_DEVICE:?Explicit synthetic emulator opt-in required}"
test "$RECALL_SYNTHETIC_DEVICE" = YES
ADB="$ANDROID_HOME/platform-tools/adb"
test "$($ADB shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mkdir -p build/device-evidence
$ADB install -r build/upgrade-base.apk
$ADB install -r build/instrumentation/test.apk
$ADB shell am instrument -w -e synthetic_emulator_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.UpgradeSeed \
  | tee build/device-evidence/upgrade-seed.log
grep -q UPGRADE_SEED_PASS build/device-evidence/upgrade-seed.log
$ADB shell am force-stop org.continuity.recall.test
$ADB install -r build/TotalRecall-0.8.0-test.apk
$ADB shell am instrument -w -e synthetic_emulator_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.IterationNativeTests \
  | tee build/device-evidence/native-ui.log
grep -q ITERATION_PASS build/device-evidence/native-ui.log
$ADB shell am force-stop org.continuity.recall.test
$ADB shell am instrument -w -e synthetic_emulator_only YES -e phase restart \
  org.continuity.recall.instrumentation/org.continuity.recall.IterationNativeTests \
  | tee build/device-evidence/native-restart.log
grep -q ITERATION_PASS build/device-evidence/native-restart.log
$ADB pull /sdcard/Android/data/org.continuity.recall.test/files/. build/device-evidence/
python3 - <<'PY'
import json
from pathlib import Path
for name in ['iteration-ui.json','iteration-restart.json']:
 r=json.loads((Path('build/device-evidence')/name).read_text())
 assert r['status']=='PASS',r
 print(name,r['assertions'],'passed',r['commit'])
PY
