# Creative capture replay

`test-engine.sh` loads the release pronunciation index and runs six freestyle cases, five coining cases and seven ordinary conversation controls. All passed at balanced sensitivity. The cases include AAA and ABAB rhyme, different spellings for the same rhyme, repeated initial sounds, a growing unfinished hypothesis, alternate spellings, a spoken correction, shopping lists and a noncreative use of “made up.” Additional checks exercise strict and exploratory sensitivity. A 180-second synthetic performance split at the 150-second clip limit retained every input sample, in order, across the boundary.

The Android `creative` instrumentation phase replays two original Flite performances and one conversation with a spoken save request through the bundled English Vosk model and production selector. For each, it checks the selected reason, SQLite row, original WAV samples and stored SHA-256, then deletes the fixture row and audio. `make-creative-fixtures.py` regenerates the test-only PCM with FFmpeg and Flite's `slt` voice. The recipe inserts a one-second gap between lines. The two automatic fixtures contain no save command. The conversation fixture starts with incidental instructions, follows with “Hey Recall,” and asks “Save that line” after a one-second gap. It must make exactly one Poem line save. All three use synthetic speech and exercise both local decoders.

The native host replay fed real Vosk partial and final results to the Java selector in 100 ms PCM blocks. The four verse lines ended in *night, light, flight, white*. Vosk heard “i fall of the birds into their flight” for “I follow the birds into their flight.” The selector saved all 374,400 original PCM bytes as `Poem line`, with the reason “Three successive phrases have similar lengths and distinct rhyming endings.”

The invented word *glimbershine* illustrates a failure a text-only archive would conceal. Vosk heard “i fall in line or shine” for “I call it glimbershine,” followed by “that is a word i made up for the like that days after dark.” The selector saved all 204,480 original PCM bytes as `Forge word` because the speaker described inventing a word. The original pronunciation can be replayed and its spelling corrected in the catalog.

These are small, scripted regression fixtures. They establish behavior on these inputs. They do not estimate recognition or selection accuracy for a particular speaker, dialect, noisy room, music-backed performance, free verse or unmarked coinage. The detector analyzes English speech hypotheses and dictionary stress, syllables and rhyme endings. It can miss passages whose rhythm lives chiefly in acoustic timing or words omitted by the recognizer. A repeating name or unplanned rhyming conversation can also trigger a clip.

## Quiet microphone replay

Run the production live gain and the previous boost against the same 16 kHz mono inputs. This uses a JDK, little-endian Linux or macOS, Python 3, an FFmpeg build with Flite, the native Vosk 0.3.45 host library, and the unmodified small English 0.15 model. Supply the installed paths on your machine.

```bash
OPENBLAS_NUM_THREADS=1 python3 device-tests/check-quiet-recognition.py --libvosk "$LIBVOSK" --model "$VOSK_MODEL"
OPENBLAS_NUM_THREADS=1 python3 device-tests/check-quiet-recognition.py --holdout --libvosk "$LIBVOSK" --model "$VOSK_MODEL"
```

Each of the two sets uses eight synthesized phrases and three voices, with two seconds of room input before the phrase. The holdout uses different sentences, voice assignments and attenuation levels. Each audio variant runs in a fresh process forked from the same loaded, unused model. Counts include word substitutions, insertions and deletions. Lower counts indicate closer agreement with the known sentence.

| Fixture at 0.29 | Reference words | Raw errors | Previous boost | Adaptive standard | Extended reach |
| --- | ---: | ---: | ---: | ---: | ---: |
| Normal | 55 | 12 | 11 | 11 | 11 |
| Very faint | 55 | 55 | 25 | 25 | 23 |
| Faint with steady noise | 55 | 45 | 23 | 23 | 17 |
| Holdout, normal | 60 | 0 | 0 | 0 | 2 |
| Holdout, very faint | 60 | 57 | 6 | 6 | 4 |
| Holdout, faint with steady noise | 60 | 53 | 20 | 21 | 7 |

Standard and previous processing produced identical normal-level PCM on all sixteen sentences. Extended reach reduced errors under both very faint conditions and introduced two at normal levels. These figures describe the fixtures. Real voices, distances, rooms and microphones can give different results. The separate `MicSignalTest` in `test-engine.sh` checks unity gain on stationary room sound, quiet clip tails, exact source samples, and rejection of a varying calibration sample.

## Archived dictation replay

Twelve held-out sentences include names, unfamiliar words, times, counts and negations. Three Flite voices read each sentence at normal level, very faint level, and with simulated fan noise. The 108 recordings contain 981 reference words. Each build received identical 16 kHz PCM through its production archive decoder on Robolectric API 28 with desktop Vosk 0.3.45, the bundled small English 0.15 model, standard gain and no enrolled voice.

| Word errors | First reading | 0.29 archive | 0.30 archive |
| --- | ---: | ---: | ---: |
| Normal | 128 | 125 | 127 |
| Very faint | 144 | 145 | 144 |
| Simulated fan | 261 | 255 | 261 |
| Total | 533 | 525 | 532 |
| Word error rate | 54.33% | 53.52% | 54.23% |

The first readings matched across versions. 0.30 prevented two harmful rewrites, including one that lost the name “Marisol.” It also withheld four helpful rewrites and displayed each as an alternative, including one that recovered the correct time “six fifteen.” Overall word error rate rose by 0.71 percentage points.

0.30 flagged 66 recordings, all of which contained errors. It provided a distinct alternative in 21 and identified one to three words in the displayed transcript in 45. Another 34 failed to match the script and received no flag, including three noisy recordings on which neither version returned any words. No 0.30 automatic rewrite changed a protected number or negation in this replay. These case-level counts do not identify which flagged words were wrong. Synthetic speech from three voices and a simulated fan cannot establish accuracy for a person or a phone microphone.

To reproduce, build both Android revisions (0.29 baseline `781611e`) and keep their separate class directories. Run from the 0.30 tree with Python 3, FFmpeg with Flite, JDK 17 and Android SDK platform 36. Set `ANDROID_SDK_ROOT`, `RECALL_ROBO_JARS` (offline Robolectric 4.14.1, JUnit 4 and API 28 jars), `RECALL_HOST_SPEECH_CP` (desktop Vosk and JNA classpath) and `RECALL_VOSK_MODEL` (unpacked model directory).

```bash
python3 device-tests/archive-dictation-holdout.py generate /tmp/recall-dictation
RECALL_APP_CLASSES=/path/to/0.29/classes device-tests/run-archive-dictation.sh /tmp/recall-dictation /tmp/recall-029.jsonl
RECALL_APP_CLASSES=/path/to/0.30/classes device-tests/run-archive-dictation.sh /tmp/recall-dictation /tmp/recall-030.jsonl
python3 device-tests/archive-dictation-holdout.py compare /tmp/recall-029.jsonl /tmp/recall-030.jsonl
```

### Addressed command replay

The Android 16 creative phase also plays “Hey Recall save as” and the replies “line” and “cancel” through the service window. It compares the original microphone samples at the instant of the request with the retained WAV, verifies its catalog SHA-256, and checks that cancellation and timeout write no clip. The guarded-playback case feeds the packaged spoken retry hint back through the microphone path and then retries the request. The synthetic input comes from `make-creative-fixtures.py`.

The following benchmark scores named saves and unwanted actions through both local speech decoders and the checkout's actual command parser. It uses Python 3 with NumPy, FFmpeg with Flite, a JDK, Linux/macOS, Vosk 0.3.45 and the small English 0.15 model. Append `--holdout` for reserved phrases, two different voices and a different noise mix. Append `--noise-filter` for the microphone-check option **Voice commands in heavy noise**. Point `--source` at a v0.29 checkout’s `android/src/org/totalrecall/listening` directory to reproduce the baseline. Add `--json results.json` for each file's free and restricted readings and resulting actions.

```sh
OPENBLAS_NUM_THREADS=1 python3 device-tests/check-command-recognition.py --libvosk "$LIBVOSK" --model "$VOSK_MODEL"
```

| Synthetic recording set | v0.29 | v0.30 default | v0.30 noise filter on |
| --- | ---: | ---: | ---: |
| Original, clear | 11/12 | 11/12 | 11/12 |
| Original, quiet | 11/12 | 11/12 | 11/12 |
| Original, +12 dB SNR | 11/12 | 11/12 | 9/12 |
| Original, +3 dB SNR | 4/12 | 4/12 | 6/12 |
| Original, unwanted actions across conditions | 2/48 | 0/48 | 0/48 |
| Held-out, clear | 6/10 | 6/10 | 7/10 |
| Held-out, quiet | 6/10 | 6/10 | 7/10 |
| Held-out, +10 dB SNR | 7/10 | 5/10 | 5/10 |
| Held-out, +4 dB SNR | 7/10 | 7/10 | 8/10 |
| Held-out, unwanted actions across conditions | 4/40 | 3/40 | 2/40 |

A positive requires exactly one clip in the intended category. Negatives include quoted requests, negation, a reference to the film and ordinary conversation. The held-out heavy-noise set still produced false saves for a quoted instruction and a negation. The optional filter affects the restricted recognizer only. The microphone recording and ordinary dictation keep their established path. These fixtures measure synthesized speech, not a particular phone, room or person.
