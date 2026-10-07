# Artificial Continuity

**Artificial Intelligence helps you think. Artificial Continuity helps your thinking survive time.**

## Integrated candidate: v0.19 browser archive and controlled context

See [MAX05 release instructions](docs/MAX05_RELEASE.md) for setup, the demonstrated
capture/reload/export/restore path, scoped access and device validation boundaries.

Published v0.17 baseline: https://exemptus9.github.io/artificial-continuity/

This branch is a review candidate; its existence does not update either deployed origin.

**Capture → preserve evidence → review → act → record progress → resume.**

A browser-local workspace with no account, model subscription or production npm dependency. Phone and desktop use the same installable web application, but do not automatically synchronize data.

### New: one Continuity capture family

Quick capture now commits an exact original source plus a linked unreviewed capture after fresh consent. Interrupted drafts remain recoverable; retries after draft-cleanup failure avoid duplicate captures. Search retrieves the original. An app-local target guard cancels a moved Save button; it does not intercept native Android or other apps.

Recovery files include the saved workspace, unfinished drafts and pending incoming shares. Open them for preview, restore separate inert drafts, or explicitly extract the saved workspace. Readable and encrypted file exports are available; working browser storage remains plaintext.

See [family architecture](docs/FAMILY_ARCHITECTURE.md), [migration](docs/FAMILY_MIGRATION.md), [storage and failure recovery](docs/FAMILY_SECURITY_RECOVERY.md), and [v0.18 release](docs/V0_18_RELEASE.md). Total Recall is the capture capability; MindVault/EchoLens remain aliases. The earlier private encrypted branch remains separate at the storage boundary.

### Review desk and Log progress

**Review desk:** a searchable queue of pending imported checkpoints with exact quotations and source line references. Edit the interpretation, select each item deliberately, then confirm the selected batch. Original sources and prior imported wording remain preserved. Unselected items stay pending. Text drafts are recoverable; acceptance is never restored automatically. Changed previews and stale-tab writes are rejected.

**Log progress:** open an intention and write where you stopped. Preview an optional next action, progress state and waiting reason. Save the exact input as a linked source and explicitly choose which intention fields, if any, to update. Work notes travel in backups and project files. No AI-generated decisions are manufactured.

See [v0.17 release notes](docs/V0_17_RELEASE.md) for scope, safety boundaries and tests.

**Step-by-step guide:** [Intention fields, the ChatGPT handoff, examples and error recovery](docs/INTENTIONS_AND_CHATGPT_BRIDGE.md). [Streamlined connection options](docs/CHATGPT_INTEGRATION_PATH.md) distinguish today's one-intention copy/share path from future in-app AI and remote MCP approaches. The current ChatGPT exchange is user-driven and suggestions require explicit acceptance. General barriers are not automatically reasons to mark the entire intention as WAITING.

### Existing operational workflows

- Reviewed phone ↔ desktop project round trips, optional encrypted files, explicit conflicts and recovery copies: [v0.16](docs/V0_16_RELEASE.md).
- PWA installation, offline capture, local incoming-share inbox, command navigation, focus layout and source pagination: [v0.15](docs/V0_15_RELEASE.md).
- One-box intake and a scoped ChatGPT handoff/selected-field reply bridge: [v0.14](docs/V0_14_RELEASE.md).
- Selected conversation-export imports, project dossiers and password-protected backup files: [v0.13](docs/V0_13_RELEASE.md).
- Follow-ups, file-batch import, transfer, draft rescue and selective Resume Packs: [v0.12](docs/V0_12_RELEASE.md).
- Transactional storage, exact original sources and source-linked reviewed checkpoints: [v0.11](docs/V0_11_RELEASE.md).

## Use it

Create an intention or use One-box intake. Save a conversation or import selected text. Confirm only the evidence you accept. Record progress before leaving. Copy a selective Resume Pack to another conversation or exchange a project file with another browser.

To update a cached installation: Device & drafts → Check for app update → Apply update after saving. **Do not clear browser data to update.** The application version is 0.18.0; the additive storage schema remains 0.11.0. Existing v0.9/v0.10 migration remains supported.

## Test and release

Serve `site/` using a static server on localhost. Remote hosts need HTTPS. Do not rely on file-URL storage behavior.

Data: `node --test tests/*.test.cjs`.

Browser tooling: `npm install --no-save --no-package-lock --ignore-scripts playwright@1.56.1`, then `npx playwright install chromium`.

Run `node tests/browser.cjs` plus `operations-browser.cjs`, `portability-browser.cjs`, `handoff-browser.cjs`, `platform-browser.cjs`, `exchange-browser.cjs` and `workflow-browser.cjs` in `tests/`.

GitHub Actions tests work branches and main. Only a successful main build deploys. Test artifacts preserve logs and screenshots. Native Android launcher/share-picker behavior still requires physical-device checking.

## Privacy and limitations

The working database, drafts and incoming-share inbox remain unencrypted. Encryption protects exported files only. No remote model, analytics or upload endpoint receives your conversation text. Follow-ups surface while using the application, not via background push. Installed-app status does not imply automatic sync.

Keep full backups before switching browser profiles, clearing data or moving devices. Project files include FULL original source text. Ordinary workspace backups omit drafts and pending incoming shares. Use the new Recovery files export to preserve these without promoting them to accepted records. Review Resume Packs before copying them elsewhere.

This is not an autonomous agent, an independently audited vault or a controller for ChatGPT's sidebar. Original source attribution and user confirmation are not cryptographic proofs of authorship.

## Development lineage and doctrine

Earlier experiments are preserved in [Development History](docs/DEVELOPMENT_HISTORY.md). HIGP, provider, cryptographic-kernel and synchronization documents describe the broader research direction; they are not silently enabled in the hosted client.

Intent over interface state. Provenance over overwrite. Uncertainty over false certainty. Explicit authority over silent agency. Divergence over data loss. Portability over vendor custody.

**Continuity is the product. The model is a replaceable reasoning engine.**
