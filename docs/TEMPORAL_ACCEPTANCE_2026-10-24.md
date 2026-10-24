# Temporal Voice 0.9 acceptance report — 2026-10-24

## Release decision

**Experimental preview, not a production or daily-use release.** The implemented offline capture/recall/review path is substantially expanded and verified with host tests and synthetic Android 36 execution. Real Samsung recognition, speech output, screen-lock capture, Bluetooth, calls, battery and physical accessibility are not verified. Live Lexidaimon integration and stable owner-held release signing are not available.

Repository: `exemptus9/artificial-continuity`. Baseline: `b0bb64414dac30a9956aefc356f81f8ef30b4864` (draft PR #13). Upgrade branch: `work/temporal-intelligence-20261024`. Review: https://github.com/exemptus9/artificial-continuity/pull/14. Final tested application commit: `7defa86605e8ad52c2554487e810f3e2ebf38057`. Later documentation-only commits do not change the delivered APK. No production merge or website deployment was performed.

## Exact delivered build

| Field | Verified value |
| --- | --- |
| Version | `0.9.0-preview`, versionCode `9` |
| Package | `org.continuity.recall.temporal.preview09` |
| Passing build and Android run | https://github.com/exemptus9/artificial-continuity/actions/runs/40072372864 |
| APK artifact ID | `12551966425` |
| Host evidence artifact ID | `12551289889` |
| Android evidence artifact ID | `12552036466` |
| APK SHA-256 | `a35f737de268ca9525487407acd266569cad35bec6e7946a5b2399a2461c3c9c` |
| Signing certificate SHA-256 | `d702cffe2f28db02505dec75168fd22a748bfbcc954de0d4aeb00adb12a1cf89` |

The downloaded APK signature, checksum and all 24 recorded build-input hashes were independently rechecked. Both temporal device receipts match the application source commit and aggregate source hash. The emulator job downloads and installs the APK artifact from this same workflow run. The signing identity is disposable; this is an isolated installation, not an approved in-place upgrade of an existing vault.

## Changes delivered

- Native Now, Timeline/date ribbon, source detail, Continuity Desk and Privacy screens; Quick Settings opens the visible Now screen.
- One-request on-device recognition, explicit local TTS selection, quiet default, cancellation and late-callback fences; mutually exclusive microphone ownership across all capture paths.
- Topic plus today/yesterday/last weekday/ISO-date recall, earliest surviving mention, exact received-text attribution, optional recognizer scores and dated bookmarks.
- Immutable source-linked owner reviews; current classification can change without rewriting original evidence. Project decisions/next steps/blockers require explicit labels.
- Idempotent interrupted-audio recovery, truthful unknown duration and cleanup errors, source-review deletion propagation, strict restore dependencies and safer desktop transfer.
- Clean SDK36 build, source/input/checksum/certificate receipts, update verifier and repeatable synthetic Android acceptance workflow.

## Verification

The final local host run passed **173 Java assertions and 169 Python tests**:

| Suite | Passing checks | Scope |
| --- | ---: | --- |
| CoreTests | 35 | Capture policy, hashes, exact Unicode, encrypted transfer, restore and deletion |
| TemporalEngineTests | 55 | Source attribution, clock/zone/DST, topic/date, earliest mention, inert source instructions |
| MemoryWorkbenchTests | 43 | Review integrity, corrections, project desk, export dependencies and deleted-source restore |
| VoiceSessionTests | 22 | Synthetic callback generations and microphone leases; not real speech |
| RecordedAudioTests | 18 | Commit/retry identity, timing, deletion, pause and provenance; synthetic bytes |
| Python unittest suite | 169 | Existing context/archive boundaries plus 15 new review-transfer cases |

Commands:

```bash
JAVA_HOME=/path/to/jdk17 RECALL_JSON_JAR=/path/to/json-20240303.jar \
  bash android/tests/run-host-tests.sh
ANDROID_SDK_ROOT=/path/to/sdk JAVA_HOME=/path/to/jdk17 \
  RECALL_KEYSTORE=/private/key.p12 RECALL_KEY_PASSWORD='<provided securely>' \
  bash android/build.sh
bash android/instrumentation/build-test.sh
RECALL_SYNTHETIC_DEVICE=YES bash android/instrumentation/run-device-tests.sh
```

The JSON dependency was checksum-verified. SDK platform/build-tools 36 and JDK17 were downloaded from official distribution sources and their published checksums verified. Android application and instrumentation Java compile, dex generation, resource packaging, alignment and signature checks passed. The update checker rejects same-version replacement and mismatching signing identities.

Build receipts state the actual commit, source-file hashes, APK SHA-256 and certificate fingerprint. Do not mix a local APK with CI device evidence: each CI run has a different disposable signer. The downloadable package identifies the exact APK it contains.

The accelerated Android 36 workflow passed **30 native assertions**: 11 existing archive/crypto/capture checks, 15 temporal UI/review/privacy/layout checks, and four checks after a full process restart. It produced eight native Activity decor images while retaining the production secure-window flag. These are synthetic-emulator renderings, not physical-device screenshots and not proof of working STT/TTS acoustics.

The companion release receipt identifies the final passing workflow, exact APK hash and source commit. PR merge checkouts may name a merge commit; the selected downloadable artifact comes from a push workflow using its implementation head directly. All relevant host/context/browser workflows also passed before final packaging.

## Feature matrix

| Capability | Implementation | Verification boundary |
| --- | --- | --- |
| Exact typed capture, encrypted vault and restore | Implemented | Host crypto/schema tests and Android native instrumentation |
| Single-utterance offline recognition | Implemented, API31+ and installed model required | Session rules tested; real recognizer unverified |
| Local spoken answers and cancellation | Implemented, installed local voice required | Compile/controller evidence; real acoustics unverified |
| Background audio, stop, discard, recovery | Implemented, bounded | Commit/recovery tests; lock-screen/call/Bluetooth unverified |
| Date/topic retrieval and earliest source | Implemented lexical retrieval | Host clock, timezone, DST and provenance regressions |
| Source correction and project resumption | Implemented explicit owner review | Host hash/history/deletion tests plus synthetic native UI and process restart |
| Privacy controls, manual sharing | Implemented | Independent code review, session tests and synthetic privacy UI; physical acceptance pending |
| Cross-device archive import | Existing path hardened for reviews/deletion | Synthetic Python transfer tests; private desktop storage remains plaintext |
| Local scoped context client | Existing read-only boundary retained | Actual loopback HTTP integration tests with synthetic credentials |
| Live Lexidaimon pairing/execution | Not connected | Owner machine, credentials and secure transport unavailable |
| ChatGPT/dot internal memory integration | Unsupported/not claimed | Manual reviewed context handoff only |
| Wake word/continuous conversation | Not implemented | No hidden ambient capture |
| Automatic audio transcription | Not implemented | Audio without transcript cannot be searched by words |
| Semantic embeddings/concept graph | Not implemented | Timeline and explicit project/source relationships only |
| Automatic sync/alarms/reminders | Not implemented | Recorded intentions are not scheduled tasks |
| Stable release signing/store publication | Prepared instructions only | Owner-held signer and release approval still required |

## Independent review and corrections

A separate source review identified five privacy/lifecycle weaknesses. All received code fixes before the implementation commit: share confirmation now binds to answer generation and is dismissed on pause; reset rejects an active microphone; recovery/discard bind to the expected file and acquire a microphone lease; answer fingerprints include linked reviews; asynchronous privacy completion catches errors and sends immediate recorder control while visible. The source review is not a claim of formal verification.

The public patch was audited for secret/key/token/phone/email/home-path patterns and manually inspected for personal data. Fixtures use generic synthetic wording. No private recordings, personal archives, credentials or signing keys were published.

## Performance and unresolved risks

A warmed synthetic JDK benchmark with 3,000 notes measured median **12.81 ms**, p95 **38.75 ms** over 40 topic/date queries. This excludes vault decryption, JSON parsing, Android UI, review-heavy records, device battery and audio. It is not a phone performance result.

Phone targets for acceptance: recognition activation under one second where the installed engine permits; local retrieval UI under 250 ms for a typical small vault; no acknowledged-note loss on restart; cancellation causes no subsequent transcript saves. These are targets, not measured achievements.

Active/interrupted audio is app-private plaintext until atomic encrypted saving. VoiceActivity still performs some vault I/O on the main thread, and background pause-policy polling reads the vault every two seconds. Attachment-heavy archives and sustained sessions need profiling. Imported hashes do not prove truth. Exported/private recipients' copies cannot be revoked. No separate app lock is provided.

## Owner acceptance still required

On a supported Samsung phone, start with harmless synthetic notes. Test microphone/notification denial; installed and missing offline speech models; immediate cancel/restart; speaking-to-listening interruption; a saved note after process restart; locked-screen Stop/Discard; calls and Bluetooth reconnect; process death and recovery; large text/TalkBack/landscape; protected backup and isolated restore; signer-safe updating. Record actual observations rather than marking untested rows passed.

Keep the old installation intact. The isolated `preview09` package starts empty. Never uninstall an app containing unexported data to work around a certificate conflict.
