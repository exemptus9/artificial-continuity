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
for phase in archive restart asr creative journal microphone overlay contention; do
  if [[ "$phase" = restart ]]; then "${a[@]}" shell am force-stop org.continuity.recall.test; fi
  if ! "${a[@]}" shell am instrument -w -e phase "$phase" org.totalrecall.smoke/org.totalrecall.listening.RecallSmoke | tee "build/receipts/$phase.txt"; then failed=1; fi
  if ! grep -q "outcome=PASS_$phase" "build/receipts/$phase.txt"; then failed=1; fi
done
# Exercise the visible System UI controls on the disposable emulator with swipe lock enabled.
prior_lock=$("${a[@]}" shell locksettings get-disabled | tr -d '\r')
prior_show=$("${a[@]}" shell settings get secure lock_screen_show_notifications | tr -d '\r')
prior_silent=$("${a[@]}" shell settings get secure lock_screen_show_silent_notifications | tr -d '\r')
restore_shade() {
  if [[ "$prior_lock" == true || "$prior_lock" == false ]]; then "${a[@]}" shell locksettings set-disabled "$prior_lock"; fi
  for pair in "lock_screen_show_notifications:$prior_show" "lock_screen_show_silent_notifications:$prior_silent"; do
    if [[ "${pair#*:}" == null ]]; then "${a[@]}" shell settings delete secure "${pair%%:*}"
    else "${a[@]}" shell settings put secure "${pair%%:*}" "${pair#*:}"; fi
  done
}
trap restore_shade EXIT
"${a[@]}" shell locksettings set-disabled false
"${a[@]}" shell settings put secure lock_screen_show_notifications 1
"${a[@]}" shell settings put secure lock_screen_show_silent_notifications 1
if ! "${a[@]}" shell am instrument -w -e phase shade org.totalrecall.smoke/org.totalrecall.listening.RecallSmoke | tee build/receipts/shade.txt; then failed=1; fi
if ! grep -q "outcome=PASS_shade" build/receipts/shade.txt; then failed=1; fi
for screen in paused listening; do
  "${a[@]}" pull "/data/local/tmp/recall-v11-$screen.png" "build/receipts/$screen.png" || failed=1
  "${a[@]}" shell rm -f "/data/local/tmp/recall-v11-$screen.png"
done
exit "$failed"
