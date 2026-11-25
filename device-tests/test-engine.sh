#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SOURCE="${RECALL_ENGINE_SOURCE:-../android/src/org/totalrecall/listening/SelectiveEngine.java}"
mkdir -p build/host
javac -encoding UTF-8 -source 8 -target 8 -d build/host "$SOURCE" host/SelectiveEngineTest.java
java -cp build/host org.totalrecall.listening.SelectiveEngineTest
