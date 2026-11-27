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
