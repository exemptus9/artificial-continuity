# v0.15.0 — phone and desktop app experience

## Delivery choice

One installable web application, with the existing manifest ID and browser database. Not a new APK, executable or account. Install in Chrome on Android or Chrome/Edge on desktop. The site cannot install itself or move data out of ChatGPT's embedded browser automatically. Export/transfer existing records before changing browser profiles.

## Features

- Dedicated Install on phone & desktop screen, with standalone-window detection, offline-controller status, storage-persistence status, approximate origin usage and clean-address copying. No false assertion that a missing prompt means installed.
- POST share-target intake for text, HTTP(S) references, and UTF-8 .txt/.md files. A controlling service worker handles the request locally. No shared text is put in a URL, app-shell cache or model request. No reference is fetched.
- Bounded incoming inbox: 10 files per share, 1 MB each, 4 MB total, 25 inbox items. Invalid input rejects as a whole. Save as sources or captures only after review; speaker attribution Unknown; no automatic checkpoints. Downloads and explicit removal support recovery. Pending incoming items are not workspace backups.
- Desktop text-file selection/drop stages items in that same local inbox.
- Source inbox search, intention filtering, checkpoint-state filtering, explicit 50-item pagination and confirmed/pending counts. Filtering is debounced and rendering is bounded.
- Desktop focus layout, keyboard command screen, Ctrl/Cmd+Shift+K, active-form Ctrl/Cmd+Enter, a last-intention resume button and wide-screen layouts.
- Phone thumb-level navigation, safe-area spacing, 16px form controls, virtual-keyboard-aware dock, reduced-motion support and scrollable desktop navigation.
- App updates continue to wait for explicit approval. Existing workspace schema/database, source evidence and request contracts remain unchanged.
- Dedicated feature-branch testing before production; production requires all data and browser suites to pass.

## What installation does not provide

There is still no cloud account, automatic device synchronization, background push, direct access to ChatGPT, or live model. Working storage and incoming shares remain unencrypted. Encrypted exports remain available. Source privacy needs a trusted device/profile and deliberate backups.

This PWA can launch in its own app window where supported. The Android share picker, OS launcher, installed-app menu, Windows/macOS taskbar and physical Galaxy S22 behavior require user-device verification. Automated browser tests exercise the manifest, native IndexedDB, the service worker's POST handling, offline reload and UI workflows, not those native operating-system surfaces.

## Tests

New data tests cover input sizes, encoding boundaries, invalid schemes, exact-text preservation, duplicate skipping, replay prevention, source attribution, capture limits, icon dimensions and manifest/cache consistency.

New browser suite covers install guidance, copy, focus persistence, command navigation, keyboard save, POST intake without server receipt, inert markup, explicit imports, duplicate replay, desktop files, rejected input, offline intake, top-level POST navigation, source pagination/filtering, last-intention return, 390px layout and retained mobile drafts.

Existing source/handoff/portability/offline suites remain part of the release gate. Local container navigation is administrator-restricted, so a local syntax/unit pass is not represented as a physical-phone or live-site test. CI results are the browser evidence.

## Next native boundary

A signed Android/TWA package or desktop native shell becomes useful when a specific native capability justifies it. It is not needed for today's icon/window/offline experience. Store distribution/signing, reliable background delivery, automatic encrypted sync and on-device model integration are separate projects, not implied by this release.
