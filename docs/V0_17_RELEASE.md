# Continuity v0.17 — review evidence, record progress

Built from main `1e35f70c8e5a24f753079602bbaaae76de7a8b82` on a separate work branch. Existing sources, storage identity and the additive 0.11 schema remain unchanged.

## Review desk

One project-scoped, searchable queue for pending imported checkpoints. Displays exact source quotations, source attribution and line references next to an editable interpretation/type. Ten checkpoints per page. Select each item explicitly; unselected items stay pending. Text drafts survive navigation/reload, but acceptance selections do not. Confirming a batch retains the prior imported wording in `reviewedFrom` and leaves source text unchanged. Stale source/checkpoint previews and repeated batches are rejected; storage revision checks also reject conflicting tab writes.

This queue reviews already imported checkpoints, not every unstructured sentence. It does not infer accepted decisions or classify the entire archive with a model. Reviewed checkpoints can enter a selective Resume Pack; they are not automatically shared.

## Log progress

Open an intention, then Log progress. Write where you stopped and optionally a next action. The state and waiting reason are collapsed optional fields. Preview the exact source record; saving always preserves the note's raw inputs. Optionally select last position, next action, state and/or waiting reason to update. No intention field is preselected. A waiting/blocked state still requires a reason. Changing the draft invalidates its preview. If the intention changes after preview, reopen the form; the draft remains available.

Work notes are ordinary linked source records, not inferred facts. They are included in full backups and project files and are readable through the project's source history. Source classifications are not cryptographic authorship claims. Saving a note does not clear Needs attention or manufacture an accepted checkpoint.

## Boundaries

No new account, cloud store, model call, analytics or background worker is introduced. The active browser database and drafts remain unencrypted. Existing encrypted export files and reviewed phone/desktop project exchanges remain separate operations. Work notes and pending checkpoint interpretations can be private; review exports before sending them elsewhere.

## Release gate

New deterministic data tests cover selections, original wording, stale previews, replay, source tampering, field isolation, waiting reasons and backup/project portability. A new real-Chromium suite exercises IndexedDB, draft restoration, cross-tab conflicts, offline writes and phone-sized layouts. Existing suites remain required before deployment. CI logs and downloadable artifacts provide the result for each exact commit; this document does not claim tests that have not run.

Local browser navigation in the authoring container was policy-blocked. Native browser verification is therefore performed on the normal GitHub Actions runner rather than described as a local success.
