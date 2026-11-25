#!/usr/bin/env bash
# Smoke runner is deliberately scoped to an explicitly selected disposable emulator.
set -euo pipefail
cd "$(dirname "$0")"
APP="${1:?Pass the target APK path}"
ADB="${ADB:-${ANDROID_SDK_ROOT:?}/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:?Choose the disposable emulator serial}"
a=("$ADB" -s "$SERIAL")
[[ "$("${a[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 || "$("${a[@]}" shell getprop ro.boot.qemu | tr -d '\r')" == 1 ]] || { echo 'Only a disposable emulator is accepted.' >&2; exit 1; }
"${a[@]}" install -r -g "$APP"
"${a[@]}" install -r -t build/RecallSmoke.apk
"${a[@]}" install -r -t -g build/contender/Contender.apk
"${a[@]}" shell input keyevent KEYCODE_WAKEUP
"${a[@]}" shell wm dismiss-keyguard
mkdir -p build/receipts
failed=0
for phase in archive restart asr journal microphone overlay contention; do
  if [[ "$phase" = restart ]]; then "${a[@]}" shell am force-stop org.continuity.recall.test; fi
  if ! "${a[@]}" shell am instrument -w -e phase "$phase" org.totalrecall.smoke/org.totalrecall.listening.RecallSmoke | tee "build/receipts/$phase.txt"; then failed=1; fi
  if ! grep -q "outcome=PASS_$phase" "build/receipts/$phase.txt"; then failed=1; fi
done
for screen in paused listening; do
  "${a[@]}" pull "/data/local/tmp/recall-v11-$screen.png" "build/receipts/$screen.png" || failed=1
  "${a[@]}" shell rm -f "/data/local/tmp/recall-v11-$screen.png"
done
exit "$failed"
