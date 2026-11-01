# Reviewed audio → private Continuity sources · 0.22.0

The Audio Workbench handoff is a deliberate file transfer. The source recording stays in the workbench. Continuity imports only the excerpts the user selects from a validated `total-recall-audio/continuity-handoff-v1` file. Importing does not publish, send to an AI service, identify speakers, create accepted decisions, or change an intention's status.

## Browser workflow

1. In Audio Workbench, listen, save the review, and pin the excerpts to keep. Export **Continuity handoff JSON**. Only pinned excerpts saved as verified against audio are exportable.
2. Open **Reviewed audio** in Continuity and choose the JSON file (UTF-8, up to 5 MiB; 1–1,000 excerpts). Inspect the text, machine draft, corrections, recording hash and timing. Check only the excerpts to keep; none are preselected. Optionally link an intention.
3. Confirm the local private copy and import. Open **Sources** or **Search** to find the words. Each selection is a source with unknown attribution; a separate checkpoint review is required to accept an interpretation.
4. Select exact source lines when reviewing a checkpoint. Resume Packs carry recording hashes and timing only for included confirmed checkpoints. **Use ChatGPT** includes these references only when confirmed checkpoint excerpts are explicitly enabled. Review outgoing text before copying.
5. Export a complete backup. Restore validates the effective text, machine text, review history and excerpt checksums. Full project transfers preserve the audio provenance too.

The same excerpt revision imports once, including after a backup restore or a project transfer that changes local source IDs. A changed review becomes a separate source revision; an older reviewed source and its checkpoints remain intact. Equal words from a different recording or segment are not collapsed.

## Operator CLI

This uses the existing private context journal, immutable object store and disposable search index. Initialize that store as described in [Capture context](CAPTURE_CONTEXT.md), then preview and explicitly select stable IDs:

```sh
python3 tools/capture_context.py --root /private/continuity import-audio /private/reviewed-audio.json
python3 tools/capture_context.py --root /private/continuity import-audio /private/reviewed-audio.json --select trae_EXACT_ID_FROM_PREVIEW --project PROJECT_ID --approve
python3 tools/capture_context.py --root /private/continuity search 'words from the selected excerpt'
python3 tools/capture_context.py --root /private/continuity source CAPTURE.EXACT_ID_FROM_RECEIPT
python3 tools/capture_context.py --root /private/continuity backup /backup/continuity/reviewed-audio-001
```

Repeat `--select` for multiple excerpts; omit `--project` for an unsorted source. Without `--approve`, the command only previews. Each selected source is a separate atomic journal append, so a partially failed batch is resumable by repeating it. A retry never silently relinks an existing source to another project. Selected effective text is searchable, and authorized `source` retrieval returns the exact selected machine/review provenance. Unselected excerpts are not copied into the immutable objects. The metadata object is a required part of backup verification and restore. Imported review assertions do not grant agent access or publication permission; local records remain private, unreviewed `UNKNOWN` sources.

## Integrity and privacy boundaries

- Stable identity binds the recording SHA-256, transcript fingerprint and segment sequence. Revision SHA-256 binds the exact selected payload; the bundle hash binds the exported selection. No Unicode normalization is performed. Canonical JSON uses sorted fixed ASCII keys, compact separators and UTF-8. Integers must be safe integers. Browser and Python parity include combining marks and supplementary-plane characters.
- Strict field allowlists, duplicate-field rejection, explicit booleans, timing ranges, contiguous review history, size limits and checksums are validated before import. Filenames must be basenames; audio bytes and absolute source paths are excluded from this contract. A person can still put private text in an excerpt or review note: inspect it before sharing a handoff or backup.
- Checksums establish internal content consistency, not authorship, proof of listening, factual accuracy or correct timestamps. Verification and timing remain exporter assertions. The recording is not loaded or listened to by Continuity.
- Browser working storage and ordinary backups are unencrypted. The CLI uses its existing private-directory permissions and authorization scope; this adapter does not change those boundaries. No account connector, background upload, provider write, new hosting or model dependency is added.
- Browser source provenance includes the selected machine text and saved review history. Those can contain words removed from the effective text, so complete backups and full project transfers deserve the same privacy care as the source. Ordinary selected checkpoint handoffs include the chosen quote and compact recording reference, not the full correction history or unrelated excerpts.

## Verification

`tests/audio-import.test.cjs` checks the shared Python/JavaScript Unicode vector, SHA-256 parity, strict rejection, consent, selection, duplicate/revision behavior, recovery, selected handoffs and project exchange. `tests/test_reviewed_audio.py` checks the existing journal/search/authorization/backup/recovery workflow and CLI. `tests/audio-import-browser.cjs` exercises the user interface and native IndexedDB in the existing browser suite. The only committed handoff fixture is fictional. Retained ASR-derived acceptance evidence is kept outside public app assets and fixtures and explicitly labels its simulated review.

Storage schema remains 0.11.0. The app shell is 0.22.0; the new scripts and stylesheet are pinned into the offline cache. Existing drafts and sources need no migration. Physical-device recording, speaker recognition and independent audio verification are outside this release.
