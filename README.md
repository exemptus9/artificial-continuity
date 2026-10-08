# Artificial Continuity · 0.21.0

**Artificial Intelligence helps you think. Artificial Continuity helps your thinking survive time.**

Published app: https://exemptus9.github.io/artificial-continuity/

[Start with the guide](https://exemptus9.github.io/artificial-continuity/help.html) · [Release notes](docs/V0_20_1_RELEASE.md) · [Intention semantics](docs/INTENTIONS_AND_CHATGPT_BRIDGE.md)

A local-first, installable workspace for intentions, original sources, reviewed evidence, creative records, and resumable context. No account, model subscription, or production npm dependency is required. The browser database is not encrypted and does not automatically synchronize across profiles or devices.

## New in this iteration

**Prepare → Ask → Review.** The Use ChatGPT screen explains the complete exchange. Prepare one intention, inspect and copy its handoff, ask ChatGPT, then paste its response back. A full answer with exactly one JSON code block is accepted; raw JSON remains supported. Compare changes, select individual fields, and save only what you approve. No suggestions are selected automatically.

An offline-capable **Guide & examples** page explains the fields, states, copying and returning, common errors, and safe updates. General barriers, prerequisites, pending replies, and actions under your control are explicitly distinguished. Filling a field does not make its contents independently verified.

This is the working **manual handoff**, not a direct ChatGPT account connection or API integration. See [future integration options](docs/CHATGPT_INTEGRATION_PATH.md) for separately scoped design work.

## Use and update

Create an intention with One-box intake, attach original text, review evidence, and record where you stopped. Use a Resume Pack or the ChatGPT handoff to continue in another conversation.

To update an installed copy: save your work, export a backup, then **Device & drafts → Check for app update → App update ready · save & apply**. Confirm the reload and look for **v0.21.0**. **Do not clear browser data.** The app version is 0.21.0; the storage schema remains 0.11.0. No data migration is needed for this iteration.

## Preserved capabilities and boundaries

- Source-preserving capture and recovery: [family architecture](docs/FAMILY_ARCHITECTURE.md), [migration](docs/FAMILY_MIGRATION.md), [security and recovery](docs/FAMILY_SECURITY_RECOVERY.md).
- Archive, corpus, relationships, versions, and publication review: [0.20 archive release](docs/V0_20_ARCHIVE_RELEASE.md).
- Review desk and Log progress: [0.17 release](docs/V0_17_RELEASE.md). Phone/desktop project exchange: [0.16](docs/V0_16_RELEASE.md).
- Private, provider-neutral context tooling: [project context](docs/PROJECT_CONTEXT.md), [capture context](docs/CAPTURE_CONTEXT.md), [release and device validation](docs/MAX05_RELEASE.md).

Working browser storage, drafts, and incoming shares remain plaintext. Encryption protects exported files only. Full project files may contain complete original sources. Review any outgoing material before sharing. Follow-ups surface in the app; they are not background push notifications. Installing the app does not imply device synchronization.

## Verification and publication

Serve `site/` on localhost or HTTPS. Do not depend on file-URL storage behavior.

```sh
node --test tests/*.test.cjs
python3 -m unittest discover -s tests -p 'test_*.py' -v
npm install --no-save --no-package-lock --ignore-scripts playwright@1.56.1
npx playwright install chromium
```

The [test/deploy workflow](.github/workflows/pages.yml) runs the full browser suite and a real pinned 0.20.0 → 0.21.0 upgrade test before main deployment. It then compares published assets with source SHA-256 values and runs a fresh-browser workflow against the public URL. Source and verification artifacts are retained by GitHub Actions. Native Android launcher/app-switcher/share-picker behavior still needs physical-device validation.

A branch is not a release. A successful main deployment and live verification establish publication. Historical experiments and design directions remain in [Development History](docs/DEVELOPMENT_HISTORY.md) and [Architecture](docs/ARCHITECTURE.md); they are not silently enabled features.

**The model is replaceable. Continuity is the product.**
