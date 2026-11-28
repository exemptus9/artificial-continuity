#!/usr/bin/env bash
# Generate once: python3 archive-dictation-holdout.py generate /path/to/corpus
# Run once per built revision on the same corpus, then use the "compare" subcommand.
set -euo pipefail
if [[ $# -ne 2 ]]; then echo "Usage: $0 CORPUS_DIRECTORY OUTPUT_JSONL  or  $0 --phrase-check CORPUS_DIRECTORY" >&2; exit 2; fi
if [[ "$1" == "--phrase-check" ]]; then
    suite=PhraseCheckReplay
    replay_args=("-Drecall.fixture=$(realpath "$2/rms-04-clean.pcm")")
else
    suite=ArchiveDictationReplay
    replay_args=("-Drecall.corpus=$(realpath "$1")" "-Drecall.report=$(realpath -m "$2")")
fi
script_root="$(cd "$(dirname "$0")" && pwd)"
classes="${RECALL_APP_CLASSES:?Compiled classes for the revision being evaluated}"
robo="${RECALL_ROBO_JARS:?Offline Robolectric/JUnit jars, including API 28}"
host="${RECALL_HOST_SPEECH_CP:?Classpath for desktop Vosk 0.3.45 and its JNA jar}"
model="${RECALL_VOSK_MODEL:?Path to the unpacked vosk-model-small-en-us-0.15}"
platform="${ANDROID_SDK_ROOT:?Android SDK}/platforms/android-36/android.jar"
speech="${RECALL_SPEECH_JARS:-$script_root/../android/build/speech}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp="$work:$classes:$host:$robo/*:$speech/*"
javac -encoding UTF-8 -cp "$platform:$cp" -d "$work" "$script_root/host/$suite.java"
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -Drobolectric.offline=true \
    -Drobolectric.dependency.dir="$robo" -Drecall.model="$(realpath "$model")" \
    -Drecall.archiveMode="${RECALL_ARCHIVE_MODE:-standard}" -Drecall.spellings="$(realpath "$script_root/../mint/web/speech-context.tsv")" "${replay_args[@]}" \
    -cp "$cp" org.junit.runner.JUnitCore "org.totalrecall.listening.$suite"
