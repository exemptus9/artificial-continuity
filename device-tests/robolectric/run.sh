#!/usr/bin/env bash
# Run after building Android. Point RECALL_ROBO_JARS at Robolectric 4.14.1 with JUnit 4,
# its runtime dependencies and instrumented android-all jars for API 28 and 35.
set -euo pipefail
cd "$(dirname "$0")"
root="$(cd ../.. && pwd)"
jars="${RECALL_ROBO_JARS:?Set RECALL_ROBO_JARS to the offline Robolectric dependency directory}"
classes="${RECALL_APP_CLASSES:-$root/android/build/classes}"
speech="${RECALL_SPEECH_JARS:-$root/android/build/speech}"
platform="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}/platforms/android-36/android.jar"
[[ -d "$classes/org/totalrecall/listening" ]] || { echo 'Build the Android app before running these UI tests.' >&2; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp="$work:$classes:$jars/*:$speech/*"
sources=(*Test.java)
suites=()
for file in "${sources[@]}"; do suites+=("org.totalrecall.listening.${file%.java}"); done
javac -encoding UTF-8 -cp "$platform:$cp" -d "$work" "${sources[@]}"
java -Drobolectric.offline=true -Drobolectric.dependency.dir="$jars" -cp "$cp" \
    org.junit.runner.JUnitCore "${suites[@]}"
