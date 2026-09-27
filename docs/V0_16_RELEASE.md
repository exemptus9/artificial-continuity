# Continuity v0.16.0 — reviewed phone/desktop project round trips

## What is implemented

Phone ↔ desktop is a user-carried-file workflow, not automatic synchronization. Export one project's intention fields, original sources, checkpoints and follow-ups. Optionally encrypt the exported file using the existing AES-GCM/PBKDF2 encrypted-file module. Import on another browser, work there, and export a return file. A matching shared project ID offers a reviewed update to the existing project instead of always adding another copy.

Comparisons use a locally remembered field baseline when available. Incoming-only edits can be selected together; conflicts remain blank. Unknown baselines require explicit two-way review. A timestamp does not decide a winner. Missing incoming records never delete local records. An altered source or interpretation is retained as an additional version. Original sources and confirmed local checkpoints are not overwritten; newly received checkpoints are pending review. Evidence and follow-up additions are opt-in. Incoming follow-ups are separate versions, not status updates to local reminders. External dependency labels travel, but external project IDs do not; reconnect dependencies locally.

Each received packet has a replay receipt. The current workspace must still match the preview, and the existing IndexedDB transaction checks the stored revision as well. A pre-update recovery copy is kept. Application version 0.16.0; additive storage schema remains 0.11.0. Existing imports, handoffs, PWA installation and offline capture remain available.

## Boundaries

No account, relay, remote model, background sync or new external endpoint. Files contain FULL original text plus the last received field baseline. Inspect outgoing contents. Encryption protects the exported file only; working browser data, drafts, saved baselines and recovery copies remain unencrypted. Passphrases are not drafted or included in workspace records and have no recovery/reset. The existing encrypted-backup container is reused, but project packets must be opened in Phone ↔ desktop, not restored as whole-workspace backups.

SHA-256 detects accidental modifications; neither it nor project IDs prove sender identity. Use only deliberately obtained files. This is not a signed peer protocol or security audit. Keep full backups. Up to 24 field baselines and 2,000 replay receipts per project are retained. Older unknown baselines fall back to explicit review. Plain packets are limited to 16 MB, encrypted files to 24 MB, with a 15 MB combined-workspace save budget. Follow-up reconciliation and deletion propagation are intentionally not implemented.

Older Transfer project packets continue to use their existing importer; this feature does not silently reidentify older duplicate copies. A new exchange starts the shared project lineage.

## Validation

29 new Node tests cover complete round trips, exact source retention, unrelated context exclusion, conflicts, unknown bases, malformed/tampered files, original/interpretation variants, pending reviews, opt-in additions, stale previews, missing-record preservation, replay receipts, dependencies, and encrypted round trips.

A new Chromium suite uses two isolated browser contexts with real IndexedDB, service workers and downloads. It covers password failure/success, source inspection, same-project return, conflicts, protected decisions, recovery snapshots, replay/staleness/tampering, offline reload and 390px layout. CI gates publishing on all existing suites plus this new one. Release results are authoritative in the associated successful workflow run, not this document's presence.
