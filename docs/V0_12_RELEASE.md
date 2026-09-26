# Continuity v0.12.0 — carry work forward

Application version: 0.12.0. Storage schema: 0.11.0 with additive optional fields. Existing intentions, sources, source quotations, checkpoints, drafts and backup recovery remain in the same IndexedDB database. Historical experimental kernel features are not implicitly enabled.

## Operational workflows

- **Batch source intake:** preview up to 20 UTF-8 text/Markdown files (1 MB each, 4 MB per batch). Select what to save. Exact existing and intra-batch duplicate text is skipped. Originals, filenames and import attribution are retained. Keyword clues still require human review. PDFs, ZIP archives and platform account JSON exports are not supported by this importer.
- **Follow-ups:** tie a real next step to a date on the device's calendar or the resolved state of another intention. Ready items appear on Now and in Follow-ups. Complete, reopen or defer date follow-ups to tomorrow. Readiness is checked on view/reload, or Check readiness now; there is no background notification service or automatic execution.
- **Selective Resume Packs:** include only the reviewed checkpoints you choose and optionally include follow-ups. Imported unreviewed checkpoints and unselected source inventories stay out. Edit the exact outgoing preview without modifying originals. Sensitive-pattern clues are not a privacy guarantee or a redaction service.
- **Project transfer:** export one intention, its FULL original sources, referenced checkpoints and follow-ups as `ContinuityProject/1`. Import previews the packet, creates new IDs and appends a distinct project copy. Existing work is not overwritten; a pre-import recovery copy is kept. Re-import of the identical packet ID is blocked. Incoming checkpoints require fresh local review before inclusion in Resume Packs. Dependencies outside the exported project require reconnection. Transfer is neither live sync nor proof of provenance/authorship.
- **Draft rescue:** inspect unfinished forms from other tabs, download their text or recover them into a source draft. The original draft stays until deliberately discarded. A pre-existing destination source draft is backed up before rescue. Unsaved drafts are separate from full workspace backups: save them as records to include them.
- **Large-source navigation:** jump to any line in a source longer than 800 lines, displaying 200 lines at a time. Exact evidence line references and original full text stay intact.
- **Offline app shell:** after the first successful online cache, the same workspace can reload and save locally without a network connection. Install/add-to-home-screen support and launcher shortcuts depend on the browser. App updates wait; applying one flushes drafts and asks before this tab reloads. No forced reload of other tabs. Persistent-storage permission is optional and is not a backup.

Native operating-system share-sheet intake is deliberately not enabled. Quick capture accepts paste and keyboard dictation. No microphone permission is requested by this app.

## Boundaries

No remote model calls, account authentication, encryption, automatic device synchronization, background reminders or remote access to the ChatGPT sidebar. Project JSON includes complete source text and must be treated as private. The offline cache contains app files, not workspace content; IndexedDB still stores user text unencrypted. Browser/OS clearing and device loss can still destroy local data. Exports remain necessary.

The v0.11 implementation is extended by `operations-core.js` (pure validation and data functions) and `operations.js` (reviewed UI operations), with explicit cache/update handling in `service-worker.js` and `offline.js`. The application version and storage version are intentionally different.

## Verification and release gate

Local pure-data suite: 49 tests (24 workspace, 25 operations). The repository retains 10 older core tests, giving the full CI gate 59 data tests.

Local Chromium DOM exercise uses an in-memory storage fixture because URL navigation is restricted in the editing environment. It covers the new UI flows but is not native-storage or offline proof.

GitHub Actions independently runs the original 15 browser scenarios and the new operational suite against real Chromium, native IndexedDB and the real GitHub Pages URL subpath. New scenarios cover previewed batch intake, selective handoff, date/dependency follow-ups, non-overwriting project transfer, review quarantine, repeat-import rejection, cross-tab draft rescue, long source navigation, mobile overflow, real offline reload/capture, and explicit updates preserving a draft. The workflow does not deploy if either suite fails. Check the actual workflow result for test status; this document is not a substitute for it.

## Technical references consulted

- MDN ServiceWorkerGlobalScope.skipWaiting: https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerGlobalScope/skipWaiting
- MDN service worker update: https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerRegistration/update
- MDN Cache.addAll: https://developer.mozilla.org/en-US/docs/Web/API/Cache/addAll

Do not clear browser data to update. Close older tabs after saving drafts, refresh normally, and apply prepared updates explicitly on future releases.
