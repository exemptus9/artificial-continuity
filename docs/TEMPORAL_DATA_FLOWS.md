# Temporal 0.9 data flows and trust boundaries

## Assets and threat model

Protect the owner's original language, recordings, project state, interpretation history, source provenance, signing keys and future ability to restore. Treat retrieved notes, imported archives and model responses as untrusted data. Hash verification establishes byte integrity, not authorship, truth, approval or immunity to a forged archive whose hashes were recomputed.

The local adversaries considered are another app attempting to invoke exported surfaces, accidentally overlapping audio sessions, late recognizer callbacks, a restored stale/deleted record, a hostile source containing instructions, lost app processes and accidental updates with a different signer. A compromised/rooted OS, malicious Android speech provider, stolen unlocked phone and physical extraction are not solved by this preview.

## Implemented paths

| Path | Content | Permission / boundary | Persistence |
| --- | --- | --- | --- |
| Typed input → vault | Exact received Unicode | Explicit Save or capture command | AES-GCM vault, Android Keystore key |
| Microphone → on-device recognizer → vault | Machine-produced transcript; optional confidence | Visible single-utterance action, RECORD_AUDIO, on-device API only | Transcript only if explicitly saved or optional question journal enabled |
| Microphone → foreground recorder → vault | Encoded m4a | Explicit start, foreground microphone service, visible notification, Stop/Discard | App-private plaintext buffer until atomic encrypted commit; recovery may be incomplete |
| Vault → deterministic recall | Matching text, timestamps, IDs | No model/tool execution | Reply stays in activity memory unless deliberately captured |
| Vault → owner review | Separate interpretation text + source digest/status | Deliberate review, source retained | Linked immutable event in existing archive |
| Recall → local TTS provider | Answer text | Explicit output toggle/button, installed non-network voice | Provider implementation outside app's vault boundary |
| Reviewed context → Android share chooser | Only previewed text | Explicit choice; source/pause revalidation | Recipient controls its copy; delivery not inferred |
| Protected archive → desktop adapter | Hash-validated records + deletion ledger | Explicit non-sensitive test-copy approval | Existing desktop working core is plaintext; private agent access remains denied |
| Desktop context client → local core | Read-only granted project context | Loopback, scoped expiring agent token, no redirects | Existing journal/object store, no new memory system |

The APK has no INTERNET permission. Android speech services are separate processes, so that alone is not sufficient: recognition uses `createOnDeviceSpeechRecognizer`, and output explicitly selects a voice that does not require network access. Those provider declarations and installed models still need real-device verification.

## Lifecycle and deletion controls

A shared microphone lease excludes foreground recognition, foreground recording and background recording. Session tokens reject cancelled recognition callbacks. Playback stops before capture; audio-focus loss cancels foreground voice activity. Recorder startup, interruption, saving failure and recovery have explicit status. Failed save bytes are retained for owner recovery rather than silently discarded.

Sources are immutable. Review events name the exact source and its digest. Review classifications are owner declarations. Deleting a source cascades through linked reviews and records tombstones. Restore validates both archive bytes and review links; missing source dependencies fail clearly, and deletion wins over older exports. Exports do not silently add omitted dependencies.

Cache/backup deletion follows the existing archive controls. No remote deletion guarantee is made. The desktop adapter refuses to claim propagation into an existing append-only imported history. It must be deliberately rebuilt/migrated if affected data was already imported.

## Lexidaimon boundary

No live OpenClaw installation or owner gateway is reachable from this build workspace. No credentials were requested, embedded, printed or distributed. Nothing opens port 18789 or adds a public proxy. Live pairing, encrypted transport, tool execution and on-phone provider selection remain unimplemented.

The repository already contains `tools/context_client.py`, which performs only scoped project/search/source reads from the authenticated loopback core. Real local HTTP integration is tested with synthetic data and synthetic credentials in `tests/test_context_client.py`. It rejects redirecting credentials, invalid IDs and write operations. Agent grants exclude private captures and expire; a note cannot expand its own permissions.

For now, use explicit reviewed text handoff from the phone, or the existing read-only context client on the owner's computer after deliberately configuring a grant. Neither approach provides access to ChatGPT's internal memory, dots or conversation history. A ChatGPT subscription is not an API credential.

## Official platform references

- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://developer.android.com/reference/android/speech/RecognizerIntent
- https://developer.android.com/reference/android/speech/tts/Voice
- https://developer.android.com/reference/android/speech/tts/TextToSpeech
- https://developer.android.com/media/optimize/audio-focus
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/reference/android/service/quicksettings/TileService

These establish documented API behavior, not a claim that Samsung hardware acceptance has passed.
