# Artificial Continuity

**Artificial Intelligence helps you think. Artificial Continuity helps your thinking survive time.**

Continuity is an experimental intent-centered computing system. Instead of organizing a person's digital life primarily around files, apps, pages, or chat transcripts, it treats **human intention and its continuity through time** as the primary object.

> A transcript remembers what was said.  
> Continuity remembers what it meant for what came next.

## Current test surface

The repository currently contains a standalone **v0.9 Live Lab** under `site/`.

It demonstrates:

- explicit Continue / Waiting / Blocked / Resolved states
- resumption briefs
- capture → idea lineage
- relevance-triggered prospective memory
- Personal Constitution policy checks
- user-visible provenance events
- two-device divergence and explicit three-way reconciliation
- browser-local persistence and JSON export
- local heuristic context reconstruction

No account, backend, model API, or build step is required for the Live Lab.

Open `site/index.html` directly or serve the `site/` directory with any static web server.

## Development lineage

Continuity existed before this GitHub repository was created.

The preserved original local Git graph contains versions **v0.1.0 through v0.8.1**, including the transition from a prototype interface to:

- append-only history
- policy-gated agent proposals
- HIGP / Human Intent Graph Protocol
- Cognitive Git
- provider-neutral reasoning
- context-consent receipts
- encrypted vault primitives
- prospective memory
- signed synchronization
- three-way semantic reconciliation

See [Development History](docs/DEVELOPMENT_HISTORY.md).

From the GitHub-repository era forward, meaningful development should land as normal commits before release artifacts are produced.

## Core doctrine

1. **Intent over interface state.**
2. **Provenance over overwrite.**
3. **Uncertainty over false certainty.**
4. **Explicit authority over silent agency.**
5. **Divergence over data loss.**
6. **Portability over vendor custody.**
7. **Relevance over arbitrary notification timing.**

## Architecture direction

The intended system separates:

- **Continuity Kernel** — authoritative event/state machinery
- **Intent Graph** — intentions, ideas, decisions, assumptions, evidence, artifacts, commitments
- **Policy / Constitution** — authority constraints
- **Reasoning Providers** — replaceable intelligence engines
- **Capture surfaces** — browser extension, voice, files, links, screenshots
- **Sync** — authenticated, divergence-aware history exchange
- **Clients** — web, mobile, desktop, CLI, extensions

The model is not the product.

**Continuity is the product. The model is the current reasoning engine.**

## Live Lab v0.9

See [v0.9 Live Lab notes](docs/V0_9_LIVE_LAB.md).

## Status

Experimental research prototype. Not production software.

Current priority: make the semantics testable before optimizing infrastructure or visual polish.
