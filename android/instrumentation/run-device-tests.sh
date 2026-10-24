#!/usr/bin/env bash
# Destructive synthetic tests: never run against an owner's phone or real archive.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK}"
: "${ANDROID_SERIAL:?Select an isolated emulator explicitly}"
: "${RECALL_SYNTHETIC_DEVICE:?Set RECALL_SYNTHETIC_DEVICE=YES for a disposable emulator}"
test "$RECALL_SYNTHETIC_DEVICE" = YES
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo 'Refusing a non-emulator serial' >&2; exit 2;; esac
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
AAPT="$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2"
APP="${1:-build/TotalRecall-0.9.0-preview.apk}"
TEST="${2:-build/instrumentation/test.apk}"
EVIDENCE="${RECALL_EVIDENCE_DIR:-$PWD/build/device-evidence}"
mkdir -p "$EVIDENCE"
HARDWARE="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.hardware | tr -d '\r')"
case "$HARDWARE" in ranchu|goldfish) ;; *) echo "Refusing unexpected hardware: $HARDWARE" >&2; exit 2;; esac
test "$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1
PACKAGE="$("$AAPT" dump badging "$APP" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
case "$PACKAGE" in org.continuity.recall.*preview*) ;; *) echo 'Only isolated preview packages are permitted' >&2; exit 2;; esac
collect_failure() {
  code=$?
  if test "$code" -ne 0; then
    # This hook is registered only after the disposable-emulator guards passed.
    "$ADB" -s "$ANDROID_SERIAL" logcat -d -t 1200 > "$EVIDENCE/failure-logcat.txt" 2>&1 || true
    "$ADB" -s "$ANDROID_SERIAL" shell dumpsys activity top > "$EVIDENCE/failure-activity.txt" 2>&1 || true
  fi
  exit "$code"
}
trap collect_failure EXIT
"$ADB" -s "$ANDROID_SERIAL" install -r "$APP"
"$ADB" -s "$ANDROID_SERIAL" install -r "$TEST"
"$ADB" -s "$ANDROID_SERIAL" shell appops set "$PACKAGE" GET_USAGE_STATS allow
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w \
  -e synthetic_emulator_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.NativeTests \
  | tee "$EVIDENCE/instrumentation.txt"
"$ADB" -s "$ANDROID_SERIAL" pull \
  "/sdcard/Android/data/$PACKAGE/files/native-test-receipt.json" \
  "$EVIDENCE/native-test-receipt.json"
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$EVIDENCE/instrumentation.txt"
"$ADB" -s "$ANDROID_SERIAL" pull \
  "/sdcard/Android/data/$PACKAGE/files/native-emulator.trbackup.json" \
  "$EVIDENCE/native-emulator.trbackup.json"
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w \
  -e synthetic_emulator_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.TemporalNativeTests \
  | tee "$EVIDENCE/temporal-instrumentation.txt"
"$ADB" -s "$ANDROID_SERIAL" pull \
  "/sdcard/Android/data/$PACKAGE/files/temporal-test-receipt.json" \
  "$EVIDENCE/temporal-test-receipt.json"
"$ADB" -s "$ANDROID_SERIAL" pull \
  "/sdcard/Android/data/$PACKAGE/files/temporal-visual-evidence" \
  "$EVIDENCE/temporal-visual-evidence" || true
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$EVIDENCE/temporal-instrumentation.txt"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w \
  -e synthetic_emulator_only YES -e verify_restart_only YES \
  org.continuity.recall.instrumentation/org.continuity.recall.TemporalNativeTests \
  | tee "$EVIDENCE/temporal-restart-instrumentation.txt"
"$ADB" -s "$ANDROID_SERIAL" pull \
  "/sdcard/Android/data/$PACKAGE/files/temporal-restart-receipt.json" \
  "$EVIDENCE/temporal-restart-receipt.json"
"$ADB" -s "$ANDROID_SERIAL" pull \
  "/sdcard/Android/data/$PACKAGE/files/temporal-visual-evidence/08-reopened.png" \
  "$EVIDENCE/temporal-visual-evidence/08-reopened.png" || true
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$EVIDENCE/temporal-restart-instrumentation.txt"
printf 'Synthetic Android instrumentation passed. This is not physical-phone acceptance.\n'
