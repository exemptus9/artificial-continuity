# Artificial Continuity

**Artificial Intelligence helps you think. Artificial Continuity helps your thinking survive time.**

## Current workspace: v0.16.0

Live: https://exemptus9.github.io/artificial-continuity/

**Capture → preserve evidence → review decisions → resume → follow through.**

This is a usable browser-local workspace, not a general autonomous AI or a simulation-only dashboard. No account, model subscription or production npm dependency is needed.

### New in v0.16

**Phone ↔ desktop: reviewed project round trips.** Export one project, work on it in another browser, then review and apply a return file to the same project. Local changes and incoming changes are compared against a remembered baseline when available. Conflicts require choices; sources and locally confirmed checkpoints are preserved. Newly received checkpoints need review. Encryption is optional for the exported file, not enabled for local storage. No account, relay, model or automatic sync.

See [v0.16 release notes](docs/V0_16_RELEASE.md) for exact transfer semantics and limits. Incoming follow-ups are added only by choice as separate versions; reminder status and deletions are not synchronized.

### Preserved from v0.15

**Install once, use as an app.** A dedicated phone/desktop setup screen, offline diagnostics, command navigation, focus layout and last-intention resumption. The installed web app uses the same browser profile's data; a different browser/device requires explicit transfer.

**Share → local review inbox → save.** Supported Android installations register as a POST share target. Text, links and selected UTF-8 .txt/.md files are intercepted by the active service worker and staged locally, not sent to a model or automatically made into decisions. Desktop file drops use the same inbox. Source search/filtering and pagination keep larger collections usable.

See [v0.15 release notes](docs/V0_15_RELEASE.md). This is a PWA, not a separately signed APK or desktop executable. Native install/share-sheet behavior still needs checking on the target device. Incoming shares are unencrypted and separate from workspace backups until saved.

### Preserved from v0.14

**One-box intake → review → save.** Paste rough text or labelled fields once. Preview and edit the name, goal, next action and optional details. Save the intention and exact original input together. Existing intention editors also support a selected-field fill preview from one text block.

**ChatGPT bridge → reviewed field updates.** Select one intention, prepare a scoped analysis request, inspect/edit its text, then copy, share where supported, or download it. It includes a reply contract that lets an AI propose fields. Pasted replies must match a locally saved request and its unchanged baseline. No fields are selected automatically. Only the fields you accept can change; sources, reviewed checkpoints, external actions and unrelated projects are outside the contract. Pasted replies are not cryptographically authenticated as coming from a provider.

**Open separately.** A clean-address copy control and best-effort Android Chrome intent help move Continuity out of an embedded browser. Existing work may be in a different browser profile: export or transfer it before switching. This site cannot control ChatGPT's link handling or discover the currently open private conversation.

See [v0.14 release notes](docs/V0_14_RELEASE.md). Existing chat import, source-linked Resume Packs, project dossiers, follow-ups, manual project transfer and encrypted export backups remain available.

### Use it

Create an intention. Save a conversation, or batch-import selected `.txt`/`.md` files after preview. Accept source-backed checkpoints yourself. Choose which checkpoints belong in a Resume Pack and copy or download the exact preview for another conversation.

Add follow-ups for a date or the resolution of another intention. Ready items surface on Now when you use the app. Transfer a whole project to another browser through a previewed JSON packet; imports append copies rather than overwriting existing work. Incoming checkpoints require fresh review.

Install/add to Home screen where supported. After the first successful online cache, the workspace can reload and capture offline. Updates wait for review instead of interrupting typing. Device & drafts rescues unsaved forms from other tabs. Unsaved drafts must be saved as records to enter a full backup.

See [v0.12 release notes](docs/V0_12_RELEASE.md) for capabilities, exact limits, migration and the test gate. The application is version 0.16.0; its additive storage schema remains 0.11.0. Existing v0.9/v0.10 migration remains supported.

### Test locally

Serve `site/` with a static server on localhost, for example `python3 -m http.server 8080 --directory site`. Remote hosting needs HTTPS for storage/clipboard/offline features. Do not rely on file-URL storage behavior.

Data: `node --test tests/*.test.cjs`.

Browser tooling: `npm install --no-save --no-package-lock --ignore-scripts playwright@1.56.1` then `npx playwright install chromium`.

Browser suites: `node tests/browser.cjs` and `node tests/operations-browser.cjs`, plus `node tests/portability-browser.cjs` and `node tests/handoff-browser.cjs` and `node tests/platform-browser.cjs` and `node tests/exchange-browser.cjs`.

GitHub Actions tests the release before deployment and preserves test artifacts. Native mobile installation/launcher behavior must still be verified on each target device.

## Privacy and limitations

Workspace data stays in the browser's IndexedDB and is NOT encrypted. The app-shell cache contains code/assets only. No model, analytics or upload endpoint receives your conversations. Text copied out, exported files and selected source links are deliberate user actions. Links are references, not automatically fetched.

Export complete backups before clearing browser data or moving devices. Project packets include FULL source text and are private data. They provide manual transfer, not automatic synchronization or cryptographic authenticity. A sensitive-pattern warning is not proof of privacy. Follow-ups are checked while using the app, not sent as background push notifications. Source intake supports selected text/Markdown files and supported extracted conversation JSON. No PDF, media, whole ZIP, or direct account access is enabled.

## Development lineage

Continuity existed before this repository. Preserved local history covers v0.1.0 through v0.8.1. See [Development History](docs/DEVELOPMENT_HISTORY.md). Earlier HIGP, encryption, policy, sync and provider experiments describe a broader research direction; they are not silently enabled in this hosted workspace. Original architecture documents and commits remain intact.

## Core doctrine

Intent over interface state. Provenance over overwrite. Uncertainty over false certainty. Explicit authority over silent agency. Divergence over data loss. Portability over vendor custody. Relevance over arbitrary notification timing.

**Continuity is the product. The model is a replaceable reasoning engine.**

Experimental software, advancing through runnable workflows and test-gated releases rather than feature claims alone.
