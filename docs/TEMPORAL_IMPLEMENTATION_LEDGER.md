# Temporal Intelligence implementation ledger

## Scope and evidence baseline — 2026-10-24

Owner mandate: MAX-Ω-TEMPORAL, continue the existing Artificial Continuity / Total Recall implementation. Baseline: `b0bb64414dac30a9956aefc356f81f8ef30b4864`, draft PR #13 against `work/max08x-convergence`. Work branch: `work/temporal-intelligence-20261024`.

The baseline contains an Android encrypted vault, immutable source records, portable protected archives, deterministic voice recall, Android on-device recognition, system TTS, and a microphone foreground service. A prior APK build does not establish physical-device behavior. No owner Linux device, Samsung phone, OpenClaw credentials, remote speech service, or stable owner signing key is available to this workspace.

## System design

Deliver a usable offline vertical slice: explicit capture → durable encrypted record → dated retrieval → original source → reviewed interpretation → project continuation. Reuse `TotalRecallStore/1` and existing transfer machinery. Original text and hashes remain immutable; review is a separate linked event. Retrieved text is inert data, never permission to execute tools. No network permission or remote providers are added.

Serialize foreground recognition and background recording through one microphone lease. Fence recognizer callbacks by session. Use only installed local TTS voices; stop playback before capture. Status reflects service reality, including recovery and failure. Existing recording buffers are app-private plaintext until encrypted commit; expose that limitation.

New native memory views offer date grouping, source inspection, project grouping, explicit review and privacy controls. They must not pretend to contain semantic embeddings, causal graphs or an LLM. Offline deterministic retrieval is the implemented intelligence level.

## Implementation sequence and gates

1. Establish baseline source and test/build environment. Preserve existing production branches and app data.
2. Implement temporal queries, typed/spoken attribution, source-backed review and project context. Gate: host regressions, integrity/backup compatibility.
3. Implement microphone lifecycle and local speech output safeguards, Now interface and Quick Settings entry. Gate: controller regressions and Android compilation; real audio remains a device gate.
4. Implement memory timeline, continuity desk and privacy interface. Gate: compile and synthetic native instrumentation where available.
5. Harden build/signing evidence and cross-device handoff documentation. Gate: clean build, verified package/certificate/hash, source-associated CI.
6. Independent security/correctness review; fix confirmed findings; final acceptance matrix.

## Decisions

- Reuse the existing schema and archive format. New interpretation provenance lives in linked source records, not overwritten originals.
- Keep the APK offline. Live Lexidaimon pairing needs access to the owner's actual installation and a separately reviewed transport; no invented gateway.
- Use a separate 0.9 preview application ID for side-by-side installation because the old CI signing key was disposable. Migration is a deliberate encrypted backup/restore, never uninstall advice.
- Hardware-dependent release criteria remain unverified until actual hardware testing. This branch is experimental regardless of compilation/CI status.

## Living status

Implemented and host-tested: temporal retrieval, immutable source reviews, project desk, Now/Memory/Privacy UI, recognition session fences, microphone exclusion, foreground/background audio recovery, review-aware deletion/import, clean signed APK pipeline. Synthetic Android 36 runtime acceptance passed: 30 assertions across archive, temporal UI and cold-process restart, with eight native decor images. Physical-phone acceptance remains a separate gate.

Blocked externally: physical Samsung acceptance; real recognizer/TTS availability; Bluetooth/lock-screen/battery evidence; owner-controlled release signing; live Lexidaimon/OpenClaw connection.

Not implemented or claimed: always-listening wake word, automatic audio transcription, general AI conversation, semantic embeddings, full graph visualization, automatic cross-device sync, app-store release.

Final verification: 173 Java assertions, 169 Python tests and 30 Android 36 native assertions passed. All six applicable workflows passed for application commit `7defa86605e8ad52c2554487e810f3e2ebf38057`. The selected APK and eight native UI images come from push run `40072372864`. Exact hashes, feature status and outstanding physical-device gates are recorded in [TEMPORAL_ACCEPTANCE_2026-10-24.md](TEMPORAL_ACCEPTANCE_2026-10-24.md).
