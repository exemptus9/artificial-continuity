# v0.18.0 — capture and recovery family candidate

Extends the v0.17 application. Storage schema remains 0.11.0. This is a review branch, not a claim that any hosted origin was updated.

## Changes

- Quick capture retains exact acquired text, records input method and supported attribution, and saves an immutable original source plus a linked UNPROCESSED capture. Decisions remain a separate review action.
- Save requires fresh local consent. Draft restoration never restores it. A source version is an initial immutable version, not a cryptographically verified revision chain.
- Save is locked through draft flush and commit. An interrupted post-commit cleanup can retry without duplicating the capture. Failed or stale writes retain the draft.
- A moved Save pointer target cancels the action; keyboard activation still works. Scope is this app's Save button only.
- Recovery files contain the workspace, saved drafts and pending incoming items. They can be readable or encrypted. Import previews first and restores inert separate drafts; extracting and replacing a workspace are separate actions.
- Paste/file/source rescue preserves raw CRLF/CR/LF beyond HTML textarea normalization. Upstream clipboard/keyboard transformations remain unknown.
- Project exchange preserves the supported capture provenance fields. Full backups/recovery are required to carry the capture inbox and drafts. Update both devices to this version before sending the extended project format to a receiving app.
- Offline migration CLI validates a separate-copy project and preserves exact original bytes alongside its report. Incompatible encrypted/event-store formats are refused; no silent plaintext downgrade.

## Validation

- 247 Node data/contract tests passed across the complete suite.
- 140 existing browser scenarios passed across eight regression suites.
- 14 new capture browser scenarios and 12 recovery browser scenarios passed.
- Browser tests used Chromium 141 headless shell, real IndexedDB, WebCrypto and service workers on a local server. They include injected save/cleanup failures, pointer movement, stale tabs, deferred saves, offline capture, encrypted recovery, wrong passwords, transaction aborts and repeated import.
- The targeted exact-text regression used a separately supplied minimal reconstruction of a user-reported newline boundary. It is not a raw clipboard recording or reproduction of a third-party application defect.
- 390px layouts were checked. No physical Android, wireless mic, Side-button, background-recording or native-interception test was run.

## Run

Serve `site/` on localhost, for example `python3 -m http.server 8765 --bind 127.0.0.1 --directory site`, then open `http://localhost:8765/#capture`. A different port/origin has different browser storage. For a phone, a reachable HTTPS host is required for the full PWA feature set; do not assume opening a file or a laptop localhost URL reaches the same app/storage.

Run `node --test tests/*.test.cjs`. Use the existing pinned Playwright 1.56.1 setup in CI, then run all ten browser scripts listed in `.github/workflows/pages.yml`. `CHROMIUM_PATH` can select an installed Chromium executable. Optional `FAMILY_TRACE_FILE` selects external private trace fixtures for `tests/family-browser.cjs`; such fixtures must remain outside the public repository.

Read [architecture](FAMILY_ARCHITECTURE.md), [migration](FAMILY_MIGRATION.md) and [security/recovery](FAMILY_SECURITY_RECOVERY.md) before moving real records. Working browser storage is plaintext. Export-file encryption does not change that.
