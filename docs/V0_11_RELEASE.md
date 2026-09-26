# Continuity v0.11.0 — Source to resumption

Built from main `12195ac56558c95cb9db3fa8f5199bc489dfb8a5`. Existing Git history and architecture documents are retained. Earlier standalone kernel experiments are not assumed to be the implementation of this hosted workspace.

## Use it

Create an intention. Save a selected conversation in Sources (paste or UTF-8 .txt/.md up to 1 MB). Link it to the intention. Review suggested or manually selected line ranges as a decision, constraint, question, context or authoritative material. Each accepted checkpoint requires explicit human review. Open the intention's Resume Pack, inspect it, then copy it to a new AI conversation or download Markdown.

The pack is deterministic, not an AI-generated summary. It includes saved intention fields, reviewed checkpoints, exact cited evidence ranges and source inventory. Unselected source prose is not exported in the pack. The full backup includes original sources and must remain private. Keyword suggestions are explicitly unconfirmed; assistant speech is not silently promoted into a decision. Source URLs are not fetched. There is no ChatGPT sidebar access, full-account importer or model connection.

## Data and safety

- IndexedDB is primary storage. A revision comparison and write share the same readwrite transaction; stale-tab writes fail instead of overwriting newer state.
- First startup migrates compatible v0.9/v0.10 localStorage data and preserves original bytes in the recovery store. Invalid old data is not replaced. Close old-version tabs: their separate legacy store cannot update the new workspace.
- Browser-storage loss, full device failure and all crash cases are not solved. No encryption or automatic cross-device synchronization is claimed.
- Sources retain original imported text; checkpoints store exact normalized line quotations checked against originals during restore. Importing a file decodes UTF-8 text; it is not binary file preservation.
- Tab-local source/capture/editor drafts save after a short debounce. Wait for the saved indicator before closing. A beforeunload warning covers pending writes, but is not a guarantee against abrupt termination.
- JSON restore validates shape and references, previews record counts, requires replacement confirmation and retains a pre-import recovery copy in the same transaction.
- Undo covers the current session's last ten changes. Activity history is preserved, but is not cryptographically chained.
- New users start with an empty workspace, not asserted personal project status. Legacy lab records remain in backups; sync/policy simulations are no longer primary workspace features.

## Verification

`node --test tests/workspace.test.cjs`: 24 data tests.

`node tests/browser.cjs` after installing Playwright 1.56.1 and Chromium: real-browser scenarios exercise native IndexedDB boot, intention creation, source preservation and inert markup, reviewed evidence, source-minimized Resume Packs, clipboard/download, reload persistence, draft recovery, stale-tab rejection, mobile layout, invalid-import rejection, backup recovery/round trip, legacy migration and absence of uncaught JavaScript errors. The Pages workflow runs these before deployment and stores screenshots/results as an artifact. A defined test is not a passed test: the linked Actions run is the source of truth for release results.

Local development-container navigation is restricted. A separate local DOM test used a storage fixture for layout and interaction inspection; it does not substitute for the native IndexedDB CI scenarios.

## Scope

Single-document IndexedDB storage is adequate for selected conversations, not a validated large archive. Sources are limited to 1 MB each, backups to 16 MB, checkpoint quotes to 200 lines. Originals are never fetched or uploaded automatically. Shared/public hosting does not make browser-local user records part of the repository.
