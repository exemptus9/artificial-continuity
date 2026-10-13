# Semantic Continuity — evidence, temporal truth, resumability, voice boundary

This slice adds a provider-independent semantic/event layer above Continuity's existing source-preserving context core. It does **not** replace the existing `ContinuityContext/1` project journal, capture object store, SQLite FTS5 index, browser workspace, or backups. It adds the missing semantics needed to say what a record means, why it is believed, how truth changes, what work is actually verified, and what another agent should resume.

## Durable model

`tools/continuity_semantics.py` uses an append-only hash-chained `ContinuitySemanticLedger/1`. Records are immutable events; current state is a replayed projection. Stable IDs survive renames. Sources are registered before claims reference them. Claims carry explicit epistemic origin (`user-confirmed`, `source-observed`, `imported`, `inferred`, `generated`), mandatory confidence, explicit authority, temporal validity, and canonical status (`possible`, `observed`, `provisional`, `user-confirmed`, `canonical`, `superseded`, `disputed`).

Work state is deliberately finer than `done`: `idea → planned → started → implemented → tested → verified → released → deprecated`. Tested/verified/released transitions require evidence references. A claim resolution atomically marks a winner canonical and the explicitly named prior claims superseded; it never deletes them.

The first-class `ContinuityResumePacket/1` contains project identity/name history, objective, canonical claims/decisions, last verified accomplishment, unresolved conflicts, blockers, artifacts, evidence pointers, work that should not be redone, and next actions. Both JSON and Markdown forms are generated from the same ledger.

## Operations

The CLI supplies provider-neutral operations that map directly onto the requested cross-model surface:

- `find-entity`
- `provenance`
- `find-conflicts`
- `changes`
- `relations`
- `work-items`
- `resume`
- `export`
- append-only `append` for `record_event`, `record_decision`, `record_artifact`, relationships, claims, and work-state transitions

A later MCP adapter should expose these operations rather than inventing a second state model. MCP is an interface, not the authority.

## Real vertical slice

`examples/continuity_public_vertical_slice.json` records public evidence for this repository itself: the pre-merge main commit, PR #5, the current v0.20 main release commit, and draft PR #9 carrying this semantic slice. Tests use those real public locators and exact commit/PR timestamps to prove:

1. old state remains reconstructable,
2. a new contradictory observation can coexist before resolution,
3. explicit resolution makes the new claim canonical and the old one superseded,
4. a source-backed project→system relationship is retrievable with provenance,
5. released work cannot be claimed without evidence,
6. a Resume Packet tells a fresh agent what not to redo and what remains,
7. the fresh agent records a new artifact/event,
8. the new state is replayed and appears in the next Resume Packet,
9. export/import reconstruction preserves the same projection and hash identity.

Synthetic `example.invalid` sources are used only for destructive edge-case tests, not as proof of the real project slice.

## Voice interface

`tools/continuity_voice.py` is intentionally a thin boundary. Speech recognition and synthesis are adapters outside canonical state. The module accepts a transcript, executes safe read operations such as `resume`, `conflicts`, and `what changed`, and returns `spoken_text` suitable for any TTS engine. Write-like speech such as `record note ...` is **staged, not committed**, and requires confirmation through an owner/capture interface. A mistranscription therefore cannot silently become canonical truth.

This permits Android speech APIs, Termux, whisper.cpp/faster-whisper, OS dictation, cloud STT, local TTS, or future voice models without changing Continuity's ledger semantics. Preserve original audio as a source object when audio itself matters; otherwise the transcript should record which STT adapter produced it and remain non-canonical until reviewed.

`tools/continuity_voice_termux.py` is a concrete Android adapter for Termux:API. It invokes `termux-speech-to-text`, takes the final non-empty recognition candidate, routes it through the same safety boundary, and optionally speaks Continuity's response with `termux-tts-speak`. It never uses `shell=True`, and it still cannot commit a spoken write. Physical-device microphone/TTS behavior remains a device-validation gate.

## Build vs integrate

- **Keep existing Continuity object storage + append-only journals + FTS5.** They are already implemented, dependency-light, inspectable, and tested.
- **Use W3C PROV concepts as an interchange vocabulary, not as the internal database.** Its Entity/Activity/Agent model is directly useful for export and provenance mapping.
- **Evaluate Graphiti later as a derived temporal/graph retrieval projection.** It already handles temporal facts, episodes/provenance, and hybrid graph/semantic retrieval. It should not become the only copy of source truth or canonical decisions.
- **Use MCP as a cross-model tool/resource interface.** The 2026-07-28 spec's stateless core fits Continuity well: explicit handles/IDs belong in Continuity rather than hidden connection state.
- **Evaluate Automerge only when multi-device concurrent editing becomes a measured need.** Its local-first/CRDT model is attractive for synchronization, but it should not replace immutable evidence or canonical-resolution rules.
- **Do not add a vector database yet.** Structured IDs, temporal replay, provenance, FTS5, and deterministic Resume Packets answer authority/state questions more safely. Embeddings can be a rebuildable retrieval aid later.

## Current boundary

This slice is application code. It does not prove installation on the user's laptop/phone, physical microphone behavior, encrypted working storage, automatic sync, or a production MCP server. The voice adapter proves the command/confirmation boundary, not speech-recognition accuracy.
