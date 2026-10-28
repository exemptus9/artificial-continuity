#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${RECALL_SYNTHETIC_DEVICE:?Explicit synthetic emulator opt-in required}"
test "$RECALL_SYNTHETIC_DEVICE" = YES
ADB="$ANDROID_HOME/platform-tools/adb"
test "$($ADB shell getprop ro.kernel.qemu | tr -d '\r')" = 1
# An emulator is not inherently disposable. Inspect every listed user/profile,
# including retained uninstalled packages, and fail closed on query/parse errors.
users="$($ADB shell pm list users | tr -d '\r')"
ids="$(printf '%s\n' "$users" | awk '
  /UserInfo\{/ { s=$0; sub(/^.*UserInfo\{/, "", s);
    if (s !~ /^[0-9]+:/) { bad=1; next }
    sub(/:.*/, "", s); print s; count++ }
  END { if (bad || count==0) exit 1 }
')"
while IFS= read -r user_id; do
  existing="$($ADB shell pm list packages -u --user "$user_id" org.continuity.recall.test | tr -d '\r')"
  if [[ -n "$existing" ]] && ! printf '%s\n' "$existing" | awk '
    /^package:[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$/ { next }
    { exit 1 }
  '; then
    echo 'Unexpected package query output; refusing synthetic upgrade test.' >&2
    exit 1
  fi
  if printf '%s\n' "$existing" | grep -Fxq 'package:org.continuity.recall.test'; then
    echo 'Existing or retained Total Recall installation; refusing synthetic upgrade test.' >&2
    exit 1
  fi
done <<< "$ids"
mkdir -p build/device-evidence
fixture_owned=0
collect_fixture_evidence() {
  # Only collect named synthetic outputs after a clean seed completes. Do not
  # copy an app's whole external directory on a failed preflight or setup.
  if [[ "$fixture_owned" = 1 ]]; then
    for name in upgrade-seed.json iteration-ui.json iteration-restart.json iteration-failure.json \
      iteration-native.trbackup.json capture-receipt.png threads.png ask-sources.png; do
      "$ADB" pull "/sdcard/Android/data/org.continuity.recall.test/files/$name" \
        "build/device-evidence/$name" >/dev/null 2>&1 || true
    done
  fi
}
trap collect_fixture_evidence EXIT
$ADB install build/upgrade-base.apk
$ADB install -r build/instrumentation/test.apk
$ADB shell am instrument -w -e synthetic_emulator_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.UpgradeSeed \
  | tee build/device-evidence/upgrade-seed.log
grep -q UPGRADE_SEED_PASS build/device-evidence/upgrade-seed.log
fixture_owned=1
# Negative control: re-running seed over its own synthetic data must refuse.
{ $ADB shell am instrument -w -e synthetic_emulator_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.UpgradeSeed || true; } \
  | tee build/device-evidence/upgrade-seed-refusal.log
grep -q 'UPGRADE_SEED_FAIL.*refusing to seed' build/device-evidence/upgrade-seed-refusal.log
! grep -q UPGRADE_SEED_PASS build/device-evidence/upgrade-seed-refusal.log
$ADB shell am force-stop org.continuity.recall.test
$ADB install -r build/TotalRecall-0.8.1-test.apk
$ADB shell am instrument -w -e synthetic_emulator_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.IterationNativeTests \
  | tee build/device-evidence/native-ui.log
grep -q ITERATION_PASS build/device-evidence/native-ui.log
$ADB shell am force-stop org.continuity.recall.test
$ADB shell am instrument -w -e synthetic_emulator_only YES -e phase restart \
  org.continuity.recall.instrumentation/org.continuity.recall.IterationNativeTests \
  | tee build/device-evidence/native-restart.log
grep -q ITERATION_PASS build/device-evidence/native-restart.log
collect_fixture_evidence
python3 - <<'PY'
import json
from pathlib import Path
for name in ['iteration-ui.json','iteration-restart.json']:
 r=json.loads((Path('build/device-evidence')/name).read_text())
 assert r['status']=='PASS',r
 print(name,r['assertions'],'passed',r['commit'])
PY
