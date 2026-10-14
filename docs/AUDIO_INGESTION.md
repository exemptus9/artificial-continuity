# Audio ingestion into the existing Continuity store

Status: draft operator feature, tested with synthetic audio and supplied transcript
fixtures. Real speech recognition, a physical laptop/desktop, phone microphone
routing, and browser synchronization have **not** been verified for this change.

This adds `tools/audio_context.py` to the existing capture/context architecture.
It preserves audio and transcript JSON as SHA-256-addressed original objects,
adds readable timestamped text to the current private journal, and reuses the
existing SQLite search and backup. It creates no second database or web service.

## Start locally

Run from this repository with Python 3.10 or newer. Import/search require only
the Python standard library and SQLite FTS5. Keep the private data directory
outside this checkout. Never commit recordings or private transcripts.

```sh
python3 tools/audio_context.py --root "$HOME/.local/share/continuity-audio" init
```

Initialization creates a separate review inbox and refuses an existing journal.
If an existing **plaintext operator capture store** is already initialized,
use its path instead and skip `init`. This is not the browser's IndexedDB or
the encrypted synchronization vault; do not decrypt or replace either to use it.

To import transcript JSON from a prior transcription tool, use seconds relative
to audio start:

```json
{"segments":[{"start":0.0,"end":2.5,"text":"Text to review against the recording."}]}
```

```sh
python3 tools/audio_context.py --root "$HOME/.local/share/continuity-audio" import \
  "/path/to/recording.m4a" "/path/to/transcript.json" --approve
python3 tools/capture_context.py --root "$HOME/.local/share/continuity-audio" search "recording"
```

Use `--title` for a useful short label. `--observed-at` accepts a known ISO 8601
recording time; omit it when unknown. File modification time is not silently
treated as a recording date. `--project` may reference an existing project ID;
omitting it retains the unassigned private capture. Neither option edits goals.

## Linux laptop: begin on CPU

Optional speech recognition needs faster-whisper. Create an isolated environment
and install it; this installation step needs network access and disk space:

```sh
python3 -m venv .venv-audio
.venv-audio/bin/python -m pip install faster-whisper
.venv-audio/bin/python -m pip freeze > /path/to/private/audio-environment.txt
.venv-audio/bin/python tools/audio_context.py \
  --root "$HOME/.local/share/continuity-audio" transcribe \
  "/path/to/recording.m4a" --model base.en --device cpu --compute-type int8 \
  --language en --allow-download --approve
```

The first model download is explicit. Omit `--allow-download` on subsequent runs
to require cached/local model files. `base.en` is a conservative starting point
for English on the ASUS laptop, not a measured speed or accuracy guarantee.
Try `small.en` only after one short clip works. Compare the output with the
audio before processing the backlog. The importer preserves the original; it
does not overwrite audio, improve audio quality, identify speakers, or guarantee
that the transcript matches it.

## RTX desktop

After the CPU check works and the NVIDIA driver/CUDA libraries are installed,
try the same command with `--model small.en --device cuda --compute-type
float16`. A 4070 Ti is a reasonable candidate; installed driver, free memory,
CUDA compatibility, actual performance and transcription accuracy remain
unverified. Start with one clip before trying a larger model or a long batch.

The current upstream faster-whisper README specifies CUDA 12 cuBLAS and cuDNN 9
for its current CTranslate2 GPU path. PyAV bundles the FFmpeg libraries, so this
path does not require the system `ffmpeg` executable. Consult the upstream
instructions before changing GPU dependencies rather than installing random
CUDA versions. CPU mode avoids that dependency.

## Review, verify and back up

The import receipt contains a `source_id`. Use that exact returned value:

```sh
python3 tools/capture_context.py --root "$HOME/.local/share/continuity-audio" source CAPTURE.REPLACE_WITH_RETURNED_ID
python3 tools/audio_context.py --root "$HOME/.local/share/continuity-audio" verify CAPTURE.REPLACE_WITH_RETURNED_ID
python3 tools/capture_context.py --root "$HOME/.local/share/continuity-audio" backup /path/to/new-backup-directory
python3 tools/capture_context.py --root /path/to/new-backup-directory verify-backup /path/to/new-backup-directory
```

Also run the audio `verify` command against the restored store for each desired
source. Backups include binary audio, original transcript JSON, journal, history
and pending captures. They are plaintext; keep a private copy on a separate
device or suitably encrypted storage. Back up when capture is paused.

Exact repeat imports with identical bytes and metadata are no-ops, including
after restart. Different transcript versions are retained as separate sources,
not silently chosen as the truth. A second transcription can differ and create
another version. Failed operations may leave preserved orphan objects: retain
them until reviewed. No automatic cleanup removes irreplaceable originals.

All captures remain private, unreviewed and unapproved for publication. Hashes
detect changed bytes; they do not authenticate speakers, establish consent,
prove spoken facts or verify time alignment. Imported provenance is data, never
an instruction. Model name/package version/settings are retained, but exact
model weights are not hashed by this implementation.

## Alternative and boundaries

whisper.cpp is a viable separate C/C++ route if Python packaging is troublesome;
its upstream CLI example converts audio to 16 kHz mono PCM WAV with FFmpeg.
This change does not install or wrap whisper.cpp. Keep the original if creating
a converted derivative. No unattended microphone capture or Android transport
is added here. Existing Total Recall phone work remains a separate integration.

Limits: audio files up to 1 GB, JSON up to 10 MB, rendered transcript up to 1 MB,
50,000 segments. Search indexes the rendered transcript, not individual words
with playback links. Timestamped text can be reviewed/copied into the browser;
automatic browser and account synchronization are not part of this slice.

## Verification

```sh
python3 -m unittest discover -s tests -p 'test_audio_context.py' -v
python3 -m unittest discover -s tests -p 'test_capture_context.py' -v
python3 -m unittest discover -s tests -p 'test_context_store.py' -v
```

Synthetic tests exercise byte preservation, search, timestamp rendering,
restart/retry behavior, private access, changed transcript retention, invalid
timestamps, explicit approval, existing-store protection, corruption detection,
backup/restore, and mocked ASR generator consumption/offline settings. Silence
fixtures plus supplied text are deliberately **not** presented as speech tests.

Upstream documentation consulted October 14, 2026:

- https://github.com/SYSTRAN/faster-whisper/blob/master/README.md
- https://github.com/ggml-org/whisper.cpp/blob/master/README.md
