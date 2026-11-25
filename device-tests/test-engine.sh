#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SOURCE="${RECALL_ENGINE_SOURCE:-../android/src/org/totalrecall/listening/SelectiveEngine.java}"
if [[ ! -f ../android/assets/listening/cmudict-prosody.tsv.gz ]]; then
  python3 ../android/tests/prepare-prosody.py
fi
mkdir -p build/host
listen=../android/src/org/totalrecall/listening
javac -encoding UTF-8 --release 8 -d build/host "$SOURCE" host/SelectiveEngineTest.java \
  "$listen/LiveCapture.java" "$listen/MicrophoneContinuity.java" ../android/tests/LiveCaptureTest.java \
  "$listen/PcmExcerpt.java" ../android/tests/PcmExcerptTest.java
java -cp build/host org.totalrecall.listening.SelectiveEngineTest
java -cp build/host org.totalrecall.listening.LiveCaptureTest
java -cp build/host org.totalrecall.listening.PcmExcerptTest
