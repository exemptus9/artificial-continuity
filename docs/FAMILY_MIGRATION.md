# Family migration and recovery contract

The family has one intention/source/capture contract with adapters. It does **not** have one interchangeable storage schema. In particular, the private event-store branch is not an older version of the public browser's array workspace. Version numbers alone must never choose a migration.

This change implements the small offline Seeds project adapter in `tools/migrate-family.cjs`. It does not migrate the private encrypted vault, arm triggers, confirm historical claims, or write any browser database.

## Inspected branches and adapter decisions

| Evidence | Shape actually inspected | Decision |
| --- | --- | --- |
| Retained public client, app version 0.17.0 | Workspace schema 0.11.0, array collections; `ContinuityProject/1` import creates a copy | Existing source/capture/review operations remain the implementation base. Preserve schema and native validators. |
| Private encrypted snapshot, package 0.8.8 | State schema 0.7.0, object maps, chained event history; private context receipts, triggers, proposals and sync records | Retain this as a separate runtime branch until a reviewed adapter preserves its evidence and security guarantees. Source code and fixtures were recovered; an actual decrypted user vault was **not** recovered. |
| Seeds v4 FULL | Valid `ContinuityProject/1`, original sources, pending notes and occurrence witnesses | Adapter preserves exact source strings, archive/segment/attribution metadata and lineage. All notes remain pending; creates another project copy. |
| Seeds v5 UPDATE | Claims `ContinuityProject/1`, but sources omit native required fields and its note is a narrative memo | Fill only the defined source interface fields; archive overridden fields. Preserve the memo as a private report source and a pending context checkpoint. Creates another project copy, as the original `updatesExistingProject:false` requires. |
| Intent Integrity prototype and Total Recall requirements | Browser interaction protection and cross-device capture requirements, respectively | Integrate as capabilities against the family contract. Neither is a storage-format authority or permission to intercept native Android input. |

Private export/recovery formats are explicitly recognized and rejected by this adapter:

- `continuity-portable-bundle` / version 0.7.0: plaintext state plus events.
- `ContinuityVault/0.1`: encrypted portable envelope; decrypted payload is `continuity-encrypted-vault` / version 0.7.0.
- `ContinuityLocalVault/0.1`: private IndexedDB vault protocol (`continuity-encrypted-v0.8`, store `vault`, key `active`).
- Private encrypted sync formats are also blocked.

There is deliberately no bypass or decryption flag. Moving protected private-branch content into the public plaintext browser workspace would be a security downgrade. A future adapter must present a concrete per-field preview, identify the plaintext destinations and retention, obtain separate informed authorization, and then import a copy. The current task authorizes preparing that migration design; it does not silently authorize the downgrade.

## Offline project adapter

Dry-run is the default and prints only a report, hashes, counts and changed field paths, never source text:

```sh
node tools/migrate-family.cjs /private/path/Seeds_v5_UPDATE.json
```

To explicitly prepare three local files, choose a destination outside a public site/repository:

```sh
node tools/migrate-family.cjs /private/path/Seeds_v5_UPDATE.json \
  --output /private/path/review/Seeds_v5_adapted.json
```

The files are:

1. `Seeds_v5_adapted.json`: an importable `ContinuityProject/1` copy.
2. `Seeds_v5_adapted.json.original.json`: the original exact bytes, including UTF-8 BOM, whitespace and line endings.
3. `Seeds_v5_adapted.json.migration-report.json`: input/output SHA-256, counts, changes and boundaries.

Files are created exclusively with mode `0600` on this POSIX environment. Existing outputs are never overwritten. This is file access control, **not encryption**. Protect the destination and copies accordingly. Preparing a packet does not import it; nothing calls browser storage, network services, account APIs or the private runtime.

The adapter validates with the existing `OperationalCore.parseProject`, rather than inventing a parallel validity standard. The original raw evidence is retained separately because serializing parsed JSON would lose original formatting and byte identity. A checksum identifies bytes; it does not authenticate an author or establish the truth of their contents.

### Preservation and consent rules

- Unknown safe metadata remains attached. Original overwritten values are stored under `familyMigration.originalFields`; malformed note records also retain their complete parsed original record. The exact raw input companion remains authoritative for serialization and untouched values.
- Sources keep exact text, existing attribution metadata, archive IDs, occurrences, segments and source references. Missing speaker classification defaults to `unknown`. Preserved attribution assertions remain historical assertions, not findings made by migration.
- Every source receives `permission:private_only` and `privacy:private`. These are conservative policy markers; they do not implement access control or encryption. Payload requests to publish, run commands, activate permissions, or confirm claims remain inert historical data.
- All imported checkpoints have `reviewState:pending` and `notUserConfirmed:true`. The legacy schema requires `reviewed:true` even on pending imports. That field alone is **not acceptance**. Existing handoff generation excludes pending checkpoints.
- A broken quote/range is not repaired into a supposedly confirmed statement. Its complete note is preserved as report evidence and the new checkpoint is pending context. A narrative memo's exact `body` is the report source; its full original record also survives in metadata and the raw companion.
- Missing `createdAt` is filled with the adapter timestamp and explicitly marked as migration time, never composition or authorship evidence. A surviving file timestamp in `source_ref` remains a surviving-file timestamp.
- The original snapshot/parent IDs survive. The adapted packet gets a deterministic ID derived from raw input SHA-256. Exact repeated imports are rejected by the native importer. Reformatting identical JSON changes its raw hash, so semantic duplicate detection still requires review.
- Device-local exchange and previous-import identities are archived on the project, rather than inherited as active synchronization receipts. Importing the prepared packet remaps local record IDs and creates a copy. No field selection merges an existing project here.
- Unsafe property names, duplicate source IDs, oversized/empty source text, non-project collections and unrelated invalid schema shapes fail closed. Nothing is truncated to force acceptance. Keep unsupported inputs and use a separately reviewed adapter.

## Private-branch migration gate

The private source has real security and history code worth preserving. It does not establish that a user's historical vault was recovered or can be unlocked.

1. Keep the exact encrypted vault and original private source/revision. Work from copies. Do not paste a passphrase into a project source, CLI argument or migration report.
2. Recover using the retained private runtime. Its `src/kernel/export.js` `verifiedBundleState` verifies chain/history and agreement between replayed and supplied state. Nested restore histories are bounded at 20 levels / 100,000 events. A future adapter should reuse that validator, not merely trust a JSON `state` field or a stored hash.
3. Retain events, source history, policy/consent receipts, assumptions, relationships, triggers and sync metadata as versioned private evidence even when the receiving family cannot execute them. A projection of intentions is not a lossless runtime migration. HIGP export in this branch does not preserve all event history or executable trigger semantics.
4. Produce a proposed mapping to family records. Unmapped records stay archived with explicit reasons; imported triggers/proposals remain disarmed. Attribution, source evidence and a payload's apparent approval must not become new user authorization.
5. Before any plaintext projection, show the destination store/export behavior and obtain informed authorization specific to that downgrade. The adapter presently refuses this path. A same-or-stronger encrypted target is preferable for future integration.
6. Verify the prepared copy, retrieve known evidence and export/recover it into a separate test profile before cutover. Keep the old runtime available until that concrete recovery succeeds. No destructive branch migration is needed to unify the product design.

## Failure and export recovery

| Failure | Behavior / recovery |
| --- | --- |
| Native Seeds v5 import fails | Preserve v5 raw file. Run this adapter dry-run, review the report, prepare a separate-copy packet. Do not edit the only original until the native validator accepts it. |
| Adapter rejects malformed data | No store is touched. Repair a copy using an explicitly reviewed conversion; preserve the failed original and its checksum. |
| Output already exists | Stop without replacing it. Choose a new output name after checking whether it is the same input hash. |
| File write throws | Newly created files are removed where possible; input is untouched. The packet is written last and each file is flushed. This is not a transactional filesystem protocol across all three files. A crash may leave partial companions; inspect all files and hashes before reuse. |
| Adapted packet lost | Regenerate from the exact raw companion using the recorded adapter version. The output time/hash may differ, but the input-bound packet ID remains the same. |
| Raw companion lost | The adapted packet retains source text and original overridden fields, but cannot reproduce the original serialized byte stream. Do not claim exact-byte recovery without the companion. |
| Browser data cleared or origin changed | Prepared files survive outside browser storage. Existing local drafts do not magically travel with a project packet; export/capture them deliberately before changing origin or clearing storage. Restore into a separate profile first. |
| Private vault passphrase lost or ciphertext changed | The adapter cannot bypass encryption or reconstruct missing keys. Retain intact backups and recover in the private runtime if credentials remain available. |
| Pending notes needed for a handoff | Review the evidence and explicitly confirm selected notes in the existing review flow. Migration never does this on the user's behalf. |

The public array workspace persists plaintext browser data; its sealed backup feature encrypts an exported file, not the live workspace. A local plaintext draft remains plaintext even if an encrypted backup also exists. The private branch uses AES-GCM: the inspected local vault uses PBKDF2-SHA-256 with 310,000 iterations; portable `vault.js` exports default to 210,000. Neither is a claim of an independent security audit. Encryption at rest does not protect unlocked browser memory, malicious code executing in the same origin, screenshots, or plaintext exports.

## Verification performed, 2026-10-03

`node --test tests/family-migration.test.cjs` passed 14 synthetic tests. Cases cover exact-byte/CRLF/BOM preservation, metadata and lineage, private permission defaults, invalid checkpoint evidence, narrative conversion, separate-copy import, pending-note handoff exclusion, deterministic IDs, private-format downgrade refusal, duplicate/unsafe input rejection, dry-run nonmutation, output permissions/hashes and overwrite refusal.

Private-input compatibility and exact source identities are recorded in the separately retained private audit, not embedded in tests or this repository. These checks do not establish authorship, publication permission, physical-device behavior or recovery of a user encrypted vault.
