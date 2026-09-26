# Continuity v0.9 Live Lab

v0.9 turns the standalone lab from a synchronization proof into a small, coherent continuity workspace.

## What is now testable

### Persistent local continuity
The Live Lab stores its state in browser `localStorage`. Reloading the page preserves the current lab state until the user explicitly resets it.

### Intent lifecycle and resumption
Threads are first-class intentions with states such as:

- CONTINUE
- PROCESSING
- WAITING
- BLOCKED
- RESOLVED
- REFERENCE
- ARCHIVED

A **Resume** action reconstructs:

- objective
- last meaningful position
- open loops
- next useful action

Changing an intent's state produces a provenance event.

### Capture → idea
The Capture inbox accepts friction, ideas, observations, questions, and notes.

A captured item can be developed into an Idea without destroying the original capture.

This demonstrates the principle:

> Evolution should create lineage, not overwrite origin.

### Prospective memory
The lab contains a relevance-triggered memory:

> Revisit Intent Integrity RFC when evidence_count >= 5.

This is deliberately not a calendar reminder. It preserves the reason for deferral and returns when that reason ceases to apply.

### Constitution / policy simulation
The Personal Constitution evaluates sample actions.

Examples:

- rename an internal thread → allowed / reversible
- add a classification → allowed / reversible
- permanently delete original creative work → blocked
- send an external email → explicit approval required

### Sync Lab
The v0.8 two-device experiment remains available:

- common ancestor
- valid Laptop branch
- valid Phone branch
- explicit divergence
- three-way semantic conflict resolution
- explicit adoption into the main continuity

### Timeline
Meaningful actions append user-visible events instead of silently mutating state.

### State export
The current browser-local continuity state can be exported as JSON.

## Suggested test path

1. Open **Now**.
2. Resume an intention and change its state.
3. Capture a new UX friction.
4. Develop the capture into an Idea.
5. Add evidence until the Prospective Memory fires.
6. Use the Constitution simulator.
7. Generate a two-device divergence in Sync Lab.
8. Explicitly reconcile and adopt it.
9. Inspect the Timeline.
10. Export the state.

## Limits

This is still an experimental lab.

- localStorage is not the production Kernel event store
- the Intelligence page uses a lightweight local heuristic rather than a remote model
- the two-device histories are simulated
- cryptographic trust/sync primitives from the full v0.7 source tree are not all embedded into this single-file lab
- there is no authentication or server-side account

Those omissions are deliberate: the Live Lab is a fast interactive surface for testing the product model.

## Design invariant

**The interface may change quickly. Continuity semantics should change deliberately.**
