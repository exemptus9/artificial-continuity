# Continuity 0.20 — supplied history becomes recoverable memory

This release extends the integrated 0.19/MAX05 candidate. The browser remains
the interactive private workspace; `tools/capture_context.py` remains the local
object store and scoped retrieval bridge. `tools/account_archive.py` is an
offline adapter for that same core, not another database or hosted service.

## What to use

Open **Private archive**. Enable private indexing once, then use **Capture a
source** or **Import source / export file**. Source saves retain the original
text and propose private records in the existing optimistic IndexedDB commit.
Review a record to add a version, multiple project links, status or a correction.
The original source and earlier interpretations remain available.

Use **What is not indexed?** to see the exact remaining nodes of each supplied
ChatGPT export. Import another branch to extend coverage. This view does not
claim access to your account or treat quiet calendar days as missing history.

Use **Daily Genesis** with your browser timezone, or enter `America/New_York`.
The journal groups known source times or fallback indexing times by that local
calendar day, including daylight-saving boundaries. It measures captured
activity, not intelligence, neurological development, or a complete day.

Select records for a **Context Capsule**. Only their current interpretations
and cited ranges are included. It is a private Markdown handoff; review it
before sending it to another model. Search is lexical with an explicit concept
dictionary, not an embedding service.

## Account ZIP / larger JSON import

Python 3.10+, standard library. No model, API key, network request or paid
service is required. Keep originals and the working directory on an encrypted
disk/container if importing sensitive history. Permissions alone are not
encryption. The adapter requires an existing Continuity core root; initialize
it using the documented `capture_context.py init` command and your verified
canonical journal. Preserve its referenced objects too, not only the journal.

```bash
# Read-only preview. Does not make a private copy or start indexing.
python3 tools/account_archive.py preview /path/to/export.zip

# Import every supported conversation as private, unreviewed witnesses.
python3 tools/account_archive.py import --root /private/continuity \
  --approve-private /path/to/export.zip

# Or select zero-based conversation indices from the preview.
python3 tools/account_archive.py import --root /private/continuity \
  --index 0 --index 3 --project P28 --approve-private /path/to/export.zip

python3 tools/account_archive.py coverage --root /private/continuity
python3 tools/capture_context.py --help
```

The ZIP is preserved unchanged, including attachments. A unique
`conversations.json` is read without extracting paths; its exact bytes are
also retained. Supported user/assistant text from **all supplied branches** is
indexed. Parent links, node IDs, message IDs, timestamps, branch tips, file and
message hashes are preserved in immutable reports. Multipart string content
is projected with LF separators; original JSON controls its exact structure.
Non-text and internal/hidden nodes remain in the raw original and are counted
as raw-only, never falsely reported as searchable text. Attachment bytes are
retained in ZIPs but not decoded, transcribed, or indexed.

Unchanged provider conversation/node/message identities with the same witness
reuse their records across exports. Changed witnesses become linked variants;
the importer never selects which variant is authoritative. Missing or repeated
conversation IDs use explicit file-scoped identity. Equal words in independent
conversations are not merged. Live captures without reliable provider IDs are
not automatically merged with exported messages; they require review.

One import is an atomic journal proposal with an expected-head check. The
adapter validates and preflights capacity before writing objects. A failed
journal commit may leave immutable orphan objects, but never returns a success
receipt. Retrying is idempotent. New sources are always private and unapproved,
regardless of embedded permission claims. Agents cannot query them through an
`internal` grant.

### Explicit bounds

* Original file: up to 1 GB; conversation JSON: up to 64 MB.
* Up to 10,000 conversations and 100,000 mapping nodes per supplied file.
* Text projection: up to 1 MB per message; larger nodes remain raw-only.
* Existing journal: 32 MB. An oversized proposal fails before commit; select
  fewer conversations. A truly large corpus needs deliberate journal/index
  scaling, not silently split competing stores.
* Browser source import remains 750 KB; browser recovery remains bounded at
  16 MB. The account adapter does not load a large corpus into IndexedDB.
* JSON parsing is bounded in memory. This is not a multi-gigabyte streaming
  JSON parser. ZIP object preservation/backup copying uses bounded buffers.

## Canonical schema and authority

The validators and formats already in Continuity remain authoritative:

| Layer | Format / implementation | Authority |
|---|---|---|
| Project history | `ContinuityContext/1`, `context_store.py` | Append-only baseline + hash-linked events; optimistic writes |
| Original capture | `ContinuityCaptureRecord/1`, `capture_context.py` | Immutable UTF-8 object; metadata explicitly distinguishes claims from facts |
| Original account export | `ContinuityAccountExport/1` | Exact content-addressed ZIP/JSON object, private |
| Account reconciliation | `ContinuityAccountImport/1` report object | Derived coverage and source references; immutable, not an original transcript |
| Account inventory | `ContinuityAccountCoverage/1` | Recomputed union within each supplied export; unknown account denominator |
| Browser raw store | Workspace `0.11.0` sources | Original text and provider witnesses; no implicit migration to the core |
| Browser index / graph | `ContinuityArchive/1`, `archive-core.js` | Versioned interpretations, evidence ranges, projects, relations, statuses |
| Public derivative | `ContinuityPublic/1` | Allowlisted separately authored fields + fresh exact-payload approval |
| Model handoff | `ContinuityCapsule/1` | Scoped private interpretations and quotations |
| Recovery | `ContinuityObjectBackup/1`; existing browser Backup/Recovery formats | Hashed originals and lineage; indexes are disposable |

Thread/work/term lineage lives in archive item `lineageId`, `version`,
`supersedes`, `reason` and exact source references. Relations include
`derived_from`, `version_of`, `defines`, `uses`, `depends_on` and `corrects`.
Project links are many-to-many. An imported variant is not a correction until
reviewed; automatic candidates are not accepted decisions or proven first uses.

The archive remembers. The index structures. A replaceable model can reason
over selected context. Only the user decides what becomes public.

## Publication and Proof of Progress

The private archive is not the public feed. Public drafting starts blank;
review requires a separate exact-payload approval. The exporter selects only
approved current payloads and excludes raw source records, private source IDs
and URLs. Edits/supersession/revocation invalidate release eligibility; backup
imports reset approval. The heuristic linter catches common identifying
patterns and supplied private terms, but is not a full privacy classifier.

The public page remains a separate static policy/project surface. It reads no
private database and does not consume an automatic daily feed. Zero public
posts is a valid result. This release does not approve any private transcript,
poem, account history, health/financial record, or personal milestone.

## Recovery and security boundaries

Raw private core objects live at `<root>/objects/<sha256>`; the journal is
`<root>/Context_Store.json`; SQLite FTS is a disposable search index. Browser
workspace, drafts and incoming shares are plaintext IndexedDB. Private Drive
and Library files retain their existing provider access controls. Neither is
claimed to be user-held-key encrypted by this application.

Core backups are **plaintext**, including history, raw originals, reports and
pending outbox. They omit tokens and disposable search indexes. Large objects
are copied and verified by streaming hashes. Verification rejects a manifest
that omits an authoritative original object even if remaining files match.
Pause producers while backing up; outbox capture is not atomic with the journal.

Browser sealed backup files use the existing WebCrypto encryption module;
this does not encrypt the live browser database. Same-origin sibling paths are
not an authentication boundary. Use a dedicated browser profile for private
work and do not treat the public code host as a private vault.

No background account collection, device monitoring, scheduled processing,
remote sync, automatic cross-chat loading, embedding upload, or native Android
validation is claimed. Native Total Recall remains the separate test candidate
on `work/max07-phone`. Credentials are not required for this adapter and must
never enter the public repository. Source content is untrusted data, never
instructions to run commands or approve actions.

Retention is append-only. Deleting an input file does not erase stored objects,
history, backups, or prior exports. No selective secure-erasure claim is made.

## Existing Drive surfaces and migration

Master Creative Archive remains the authority for creative genealogy where
established; Master Dashboard remains the operational surface. Imported text
representations must retain original file IDs, read dates and authority scope.
Drive summaries do not become original conversation testimony.

The source adapter path is additive: retain original artifact → record immutable
source → create private candidate → review and cross-link → export/restore.
Do not bulk import a public Sheet's status flags as publication consent. Do not
replace a working Drive tracker until its fields and lineage survive a tested
round trip. This release does not claim two-way Drive or browser/core sync.

## Verification and remaining work

Run `python3 -m unittest discover -s tests -p 'test_*.py' -v`,
`node --test tests/*.test.cjs`, and the repository's Chromium suites. New tests
cover ZIP retention, ambiguity/path rejection, incremental and partial imports,
variants, privacy scopes, corruption, stale writes, failure recovery, exact gaps,
timezone boundaries and browser interactions. Fixtures are synthetic.

Outstanding: real owner export ingestion; live/export identity reconciliation
where IDs are missing; encrypted live-core storage; multi-gigabyte streaming;
physical Android/share/microphone tests; scoped browser/core graph transfer;
scheduled local reconciliation; richer semantic retrieval; author decisions
and publication of exact selected creations. The browser UI and core adapter
are usable without claiming those gates have passed.
