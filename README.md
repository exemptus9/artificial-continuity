# Artificial Continuity

**Artificial Intelligence helps you think. Artificial Continuity helps your thinking survive time.**

## Current workspace: v0.11.0

Live: https://exemptus9.github.io/artificial-continuity/

**Save a conversation → review its decisions → resume in another AI conversation.**

Create an intention, paste a source or import a UTF-8 `.txt`/`.md` file, link it to the intention, and review source-backed checkpoints. A Resume Pack combines your goal, last position, next action, open questions, confirmed decisions and cited source lines. Copy it or export Markdown. Original source text remains separate from interpretation.

Also includes attention flags, quick-capture drafts, capture-to-idea lineage, actual keyword search, validated backups/recovery, session undo, and legacy v0.9/v0.10 migration. IndexedDB writes use transactional revision checks to reject stale-tab overwrites. No account, model bill or build step is required.

See [v0.11 release notes](docs/V0_11_RELEASE.md) for exact capabilities, limits, migration and test coverage. Historical architecture documents describe a broader research direction, not features silently enabled in this public workspace.

## Try locally

Serve `site/` on localhost, for example `python3 -m http.server 8080 --directory site`, then open `http://localhost:8080`. Use HTTPS for remote hosting. Do not rely on `file://` storage behavior.

Data tests: `node --test tests/workspace.test.cjs`.
Browser tests: `npm install --no-save --no-package-lock --ignore-scripts playwright@1.56.1`, `npx playwright install chromium`, then `node tests/browser.cjs`.

The Pages workflow tests the release before deploying and preserves browser evidence. No production dependencies are downloaded by the app.

## Privacy and boundaries

Data stays in that browser. It is not encrypted or automatically synced. Export private backups before switching devices or clearing browser data. Checkpoints require human review; keyword suggestions are not automatically accepted. Resume Packs are deterministic working briefs, not model-generated memories. Source URLs are references only and are never fetched. Imported conversations do not grant control over ChatGPT's sidebar.

## Development lineage

Continuity existed before this GitHub repository. Preserved local history covers v0.1.0 through v0.8.1, including experimental event logs, HIGP, branching, provider isolation, consent, encryption, prospective memory and signed sync. See [Development History](docs/DEVELOPMENT_HISTORY.md). Those experiments must not be confused with the hosted application's capabilities. Historical documents and commits remain intact.

## Core doctrine

1. Intent over interface state.
2. Provenance over overwrite.
3. Uncertainty over false certainty.
4. Explicit authority over silent agency.
5. Divergence over data loss.
6. Portability over vendor custody.
7. Relevance over arbitrary notification timing.

## Architecture direction

The intended system separates the Continuity Kernel, Intent Graph, policy/Constitution, replaceable reasoning providers, capture surfaces, authenticated divergence-aware synchronization and web/mobile/desktop clients. These are design directions, not a claim that this release implements the full architecture.

**Continuity is the product. The model is the current reasoning engine.**

Experimental software. The current priority is a usable, evidence-backed source-to-resumption workflow with honest limits.
