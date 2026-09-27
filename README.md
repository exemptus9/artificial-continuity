# Artificial Continuity

**Artificial Intelligence helps you think. Artificial Continuity helps your thinking survive time.**

## Current workspace: v0.13.0

Live: https://exemptus9.github.io/artificial-continuity/

**Capture → preserve evidence → review decisions → resume → follow through.**

This is a usable browser-local workspace, not a general autonomous AI or a simulation-only dashboard. No account, model subscription or production npm dependency is needed.

### New in v0.13

Sources → Import chat export reads selected conversation branches from extracted mapping/current_node JSON files. Preview before selection; create separate intentions, link to one, or leave sources unlinked. Imported text records roles and omissions but does not accept decisions. Whole ZIPs/media are not supported. The importer is covered by synthetic mapping fixtures, not every account-export variant.

Click an intention’s title to open a project dossier: goal, next action, linked/referenced sources, reviewed checkpoints, pending review, and follow-ups in one view.

Backup & history can encrypt a complete saved-record backup with a passphrase (AES-256-GCM / PBKDF2-SHA256). Decrypt → validate → preview → explicitly confirm restore. The browser database and recovery copies remain UNENCRYPTED. Passphrases have no recovery/reset.

See [v0.13 release notes](docs/V0_13_RELEASE.md).

### Use it

Create an intention. Save a conversation, or batch-import selected `.txt`/`.md` files after preview. Accept source-backed checkpoints yourself. Choose which checkpoints belong in a Resume Pack and copy or download the exact preview for another conversation.

Add follow-ups for a date or the resolution of another intention. Ready items surface on Now when you use the app. Transfer a whole project to another browser through a previewed JSON packet; imports append copies rather than overwriting existing work. Incoming checkpoints require fresh review.

Install/add to Home screen where supported. After the first successful online cache, the workspace can reload and capture offline. Updates wait for review instead of interrupting typing. Device & drafts rescues unsaved forms from other tabs. Unsaved drafts must be saved as records to enter a full backup.

See [v0.12 release notes](docs/V0_12_RELEASE.md) for capabilities, exact limits, migration and the test gate. The application is version 0.13.0; its additive storage schema remains 0.11.0. Existing v0.9/v0.10 migration remains supported.

### Test locally

Serve `site/` with a static server on localhost, for example `python3 -m http.server 8080 --directory site`. Remote hosting needs HTTPS for storage/clipboard/offline features. Do not rely on file-URL storage behavior.

Data: `node --test tests/*.test.cjs`.

Browser tooling: `npm install --no-save --no-package-lock --ignore-scripts playwright@1.56.1` then `npx playwright install chromium`.

Browser suites: `node tests/browser.cjs` and `node tests/operations-browser.cjs`, plus `node tests/portability-browser.cjs`.

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
