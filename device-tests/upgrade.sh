#!/usr/bin/env bash
# Run on a clean disposable emulator. The probe deletes its own synthetic clip.
set -euo pipefail
cd "$(dirname "$0")"
prior=${1:?Pass the prior APK}
new=${2:?Pass the new APK}
prior_code=${3:-12}
[[ "$prior_code" =~ ^[0-9]+$ ]]
adb=${ADB:-${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}/platform-tools/adb}
serial=${ANDROID_SERIAL:?Select the disposable emulator}
device=("$adb" -s "$serial")
target=org.continuity.recall.test
probe=org.totalrecall.smoke/org.totalrecall.listening.UpgradeProbe
mkdir -p build/receipts
[[ "$("${device[@]}" shell getprop ro.boot.qemu | tr -d '\r')" == 1 ||
   "$("${device[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
if "${device[@]}" shell pm path "$target" | grep -q '^package:'; then
  echo "Use a fresh emulator so the upgrade fixture starts on the prior version." >&2
  exit 1
fi
version_code() {
  "${device[@]}" shell dumpsys package "$target" | sed -nE 's/^ *versionCode=([0-9]+).*/\1/p' | head -n1
}
phase() {
  "${device[@]}" shell am instrument -w -e phase "$1" -e sourceVersion "$prior_code" "$probe" | tee "build/receipts/upgrade-$prior_code-$1.txt"
  grep -q "outcome=PASS_upgrade_$1" "build/receipts/upgrade-$prior_code-$1.txt"
}
"${device[@]}" install --no-incremental -g "$prior"
[[ "$(version_code)" == "$prior_code" ]]
"${device[@]}" install --no-incremental -r -t build/RecallSmoke.apk
phase seed
"${device[@]}" shell am force-stop "$target"
"${device[@]}" install --no-incremental -r -g "$new"
new_code=$(version_code)
[[ "$new_code" =~ ^[0-9]+$ && "$new_code" -gt "$prior_code" ]]
phase verify
echo "Updated package in place from versionCode $prior_code to $new_code; the prior catalog row, edits, journal and WAV passed verification." | tee -a build/receipts/upgrade.txt
