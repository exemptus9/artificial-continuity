#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?JDK 17 required}"
: "${RECALL_JSON_JAR:?Path to org.json 20240303 test jar}"
RECALL_EVIDENCE_DIR="${RECALL_EVIDENCE_DIR:-$PWD/build/evidence}"
rm -rf build/host-tests
mkdir -p build/host-tests "$RECALL_EVIDENCE_DIR"
"$JAVA_HOME/bin/javac" --release 8 -cp "$RECALL_JSON_JAR" -d build/host-tests src/org/continuity/recall/Core.java src/org/continuity/recall/Crypto.java src/org/continuity/recall/StrictJson.java src/org/continuity/recall/TemporalEngine.java src/org/continuity/recall/MemoryWorkbench.java src/org/continuity/recall/VoiceSession.java src/org/continuity/recall/MicrophoneLease.java src/org/continuity/recall/RecordedAudio.java tests/CoreTests.java tests/TemporalEngineTests.java tests/MemoryWorkbenchTests.java tests/VoiceSessionTests.java tests/RecordedAudioTests.java
"$JAVA_HOME/bin/java" -cp "build/host-tests:$RECALL_JSON_JAR" org.continuity.recall.CoreTests "$RECALL_EVIDENCE_DIR"
"$JAVA_HOME/bin/java" -cp "build/host-tests:$RECALL_JSON_JAR" org.continuity.recall.TemporalEngineTests
for suite in MemoryWorkbenchTests VoiceSessionTests RecordedAudioTests; do
  "$JAVA_HOME/bin/java" -cp "build/host-tests:$RECALL_JSON_JAR" "org.continuity.recall.$suite"
done
cd ..
python3 -m unittest discover -s tests -p 'test_*.py' -v
