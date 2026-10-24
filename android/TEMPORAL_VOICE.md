# Total Recall · Temporal Voice experimental Android build (0.8.0-test)

**Status:** source integration and CI-build candidate, not a tested production assistant.
Built on **work/max08x-convergence** with existing encryption and event provenance preserved.
The production website and main branch are untouched. **The preview has its own Android application ID** (org.continuity.recall.temporal.preview) and therefore installs beside an older Total Recall test installation, with an independent empty encrypted vault. It will not automatically read the older app's files; use reviewed backups and the existing export/restore flow when needed.

## Implemented

- **Voice Recall test launcher** and entry point from Total Recall > Capture.
- **Speak once** uses Android's **on-device** recognition request; no cloud
  recognition fallback. Requires API 31+ and a functioning installed on-device
  recognition service. Recognized words are not verbatim recordings.
- Say "Remember the glove compartment latch sticks", "What did I say about the
  glove latch?", "Read my last note", or "What did I record today?".
- Manual text events use the existing encrypted Vault, IDs, timestamps,
  source classifications, hashes, pause/forget policy and export structure.
- Local recall quotes matching previously saved manual text records, cites record
  IDs and does not invent matches. There is no LLM in this build.
- Answers read aloud using Android TextToSpeech when installed. Its engine is
  separate system software, potentially with its own privacy policy.
- Spoken search queries are **not saved by default**; journaling recognized
  utterances is optional and initially off. "Remember", "note", "log" and
  "record this" utterances explicitly request saves.
- **Start background audio note** begins a microphone foreground service only
  after visible user initiation and microphone permission. A persistent Android
  notification has Stop & save / Cancel actions; locking the screen after starting
  does not intentionally stop recording.
- Background audio is limited to 15 minutes or the existing 5 MiB attachment cap,
  whichever comes first. AAC/M4A is committed to encrypted Vault after successful
  finalization; incomplete temporary recordings might remain in private app files.
- Pause is checked every 2 seconds and cancels the background recording. Battery,
  low storage, force-stop and manufacturer restrictions can interrupt capture.
- A user-reviewed question and recall can be explicitly shared to ChatGPT via
  Android share chooser. Nothing is uploaded automatically.

## Not implemented

- Permanent "Hey ChatGPT" offline wake word.
- SpeechRecognizer streaming throughout the day (the Android API is not intended
  for continuous recognition).
- Automatic transcription from background audio.
- Generative model answers, real-time two-way ChatGPT audio, or Lexidaimon sync.
- Passive ambient recording of other people's conversations.
- Physical Samsung Android 16, Bluetooth, power, notification and lockscreen checks.

## Installation and signing warning

The experimental GitHub Actions APK artifact is signed with a **different random
test keystore on each CI run**. That makes it inappropriate as an enduring app
identity: APKs from different runs cannot update each other in place. The preview is isolated from previously installed Total Recall test apps; it is still not suitable for irreplaceable personal records. Do not
uninstall an app containing your only local archive. First export and validate a
complete encrypted backup, preserve its passphrase, and only then evaluate
another test build. An owner-held stable signing key and device migration tests
are release prerequisites.

Test using exclusively non-sensitive fake notes on a spare device or profile
first. Verify package, APK SHA-256, signer, microphone indicator, background Stop,
Cancel, lockscreen behavior, recoverability, archive capacity, and backup restore.
The app has no Internet permission and no embedded credentials.

## Next integration boundary

Total Recall's existing manual transfer can send explicitly reviewed non-sensitive
test packets to Continuity. Live Lexidaimon integration needs an authenticated,
paired bridge with source minimization, encryption, authorization boundaries, and
deletion/retention semantics. Do not expose the OpenClaw gateway or put an API key
in the APK.

References:
https://developer.android.com/reference/android/speech/SpeechRecognizer
https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
https://developer.android.com/reference/android/speech/tts/TextToSpeech
