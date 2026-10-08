# Synchronization baseline

Observed on 2026-10-08. This is a public-safe summary of the starting state, not a dump of connected accounts or their records. Provider account, workspace, file, message, base, and record identifiers belong in private encrypted state.

## Repository and deployed release

| Item | Verified starting state |
| --- | --- |
| Repository | [exemptus9/artificial-continuity](https://github.com/exemptus9/artificial-continuity) |
| Default branch | `main` |
| Pinned main commit | `80a3099ce20f20be2b99f94483e972c4f049c414` |
| Version | `0.20.1` |
| Release PR | [PR #10](https://github.com/exemptus9/artificial-continuity/pull/10), closed and merged |
| Total Recall candidate | [PR #8](https://github.com/exemptus9/artificial-continuity/pull/8), open draft, not merged |
| Semantic/voice candidate | [PR #9](https://github.com/exemptus9/artificial-continuity/pull/9), open draft, not merged |
| GitHub Releases | None returned in the baseline query |
| Existing Pages URL | [Continuity](https://exemptus9.github.io/artificial-continuity/) |

The baseline GitHub Actions evidence records a successful Pages deployment at the pinned main commit, verification of 46 published assets against release SHA-256 values, loading in an empty browser, and a fresh-browser manual handoff check. Those observations establish the existing release's recorded deployment evidence. They do not establish that a later synchronization-engine change has been deployed.

The source snapshot contains 129 files, totaling 934,134 bytes. Every retrieved source file was checked against its Git blob ID using the canonical Git blob encoding. This verifies the retrieved repository content; it does not prove the correctness of its behavior or the truth of imported personal data.

## Service boundaries

| System | Role and observed access | Synchronization boundary |
| --- | --- | --- |
| Continuity | Canonical local identities, evidence, decisions, operations, conflicts, and scoped handoff state | Existing browser records are not automatically enrolled in engine coverage |
| Gmail | Connected host can discover relevant messages and retrieve source evidence | Bounded host scans; no daemon credentials; drafts, sent messages, acknowledgments, and outcomes remain distinct |
| Airtable | Existing command surface and application-record mirror; connected host can read and write | Update an explicitly mapped existing record; provider comparison-and-swap and uniqueness are unavailable in this slice |
| Google Drive | Connected host can inspect designated documents and file metadata | Bounded scans; no daemon credentials; document access is not complete archive synchronization |
| Notion | Existing read-only command surface | Its content is stale; no automatic authoritative writeback or ownership reassignment |
| GitHub | Source, PR, CI, release, and deployment evidence | These are separate facts; merged code is not deployment proof |
| RhymeMosaic | Documented archive/production recovery responsibilities | Database connectivity, exact restoration, and production cutover remain unverified by this slice |
| Hydra-Index | Documented recovery/modularization responsibilities | Current running service and its persistence remain unverified by this slice |
| Lexidaemon / Total Recall | Documented local capture/agent boundary | Local runtime access and authenticated continuous delivery remain unverified by this slice |

Connected host access means an authorized host session can invoke its installed connectors. It does not mean the command-line engine can call those services unattended. The bridge records the intent and verification receipt around an explicit host-assisted operation.

## Current gaps and constraints

- The initial vertical slice reconciles a Gmail acknowledgment into canonical evidence and an existing mapped Airtable record. It does not create remote application records or send email.
- Record titles are not identity keys. Source identifiers and destination mappings must be verified privately before an update.
- Airtable preflight/readback checks can detect many discrepancies, but there is a race between a read and an unconditional provider update. The slice does not guarantee distributed transactions or exactly-once writes.
- Bounded scans must report their coverage. A scan that did not see a record is not evidence that the record was deleted.
- A hash checks content integrity. It does not prove that a source statement is true, that an application succeeded, or that a backup can be restored.
- The open PR candidates remain separate until their behavior and compatibility are reviewed. This increment does not silently merge them.

## Evidence and publication

The private baseline manifest retains detailed provider references. This file intentionally contains only public repository facts and service capability boundaries. Personal receipts, source messages, provider IDs, keys, and encrypted state must not be committed to the public repository.

The starting-state facts above were extracted from the repository verification manifest and the host connector baseline observed during this run. New implementation and deployment acceptance must be reported separately as `PASS`, `FAIL`, or `NOT RUN` with evidence for the particular version tested.
