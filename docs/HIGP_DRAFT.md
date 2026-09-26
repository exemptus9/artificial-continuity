# HIGP — Human Intent Graph Protocol (draft 0.1)

HIGP is a proposed portable representation for human intentions and their computational context. It is deliberately model-provider-neutral.

## Core entities

- **Intention** — an ongoing directed human purpose.
- **Artifact** — a durable produced or consumed object: document, code, recording, image, message, etc.
- **Idea** — a developing concept whose status may range from seed to implemented.
- **Decision** — a choice plus alternatives, rationale, assumptions, and revisit conditions.
- **Claim** — a proposition with epistemic type, confidence, evidence, and status.
- **Assumption** — a claim currently relied upon by downstream reasoning or decisions.
- **Commitment** — something expected to happen, either by the user or another actor.
- **Event** — an immutable record of meaningful state transition.
- **Constitution Rule** — a user-owned constraint on automated behavior.

## Relationship vocabulary (initial)

- `SUPPORTS`
- `CONTRADICTS`
- `DEPENDS_ON`
- `BLOCKS`
- `UNBLOCKS`
- `GOVERNS`
- `PRODUCED`
- `EVIDENCE_FOR`
- `SUPERSEDES`
- `DERIVED_FROM`
- `GENERALIZES_TO`
- `BELONGS_TO`
- `WAITING_ON`

Relationships should be typed, directional, attributable, and optionally confidence-weighted.

## State vs history

A HIGP consumer SHOULD distinguish:

1. **Current derived state** — efficient representation of what the system currently believes.
2. **Event history** — append-only provenance explaining how the current state arose.

History should not be silently rewritten to conform to current state.

## Epistemic integrity

Claims SHOULD carry an epistemic type such as `OBSERVATION`, `INFERENCE`, `HYPOTHESIS`, `FACT`, `PREFERENCE`, `DECISION`, `ASSUMPTION`, `QUESTION`, `PREDICTION`, or `EXTERNAL_CLAIM`.

A consuming AI SHOULD NOT promote an uncertain claim to `FACT` merely because a downstream schema requires a single clean value.

## Conditional prospective memory

Deferral SHOULD be representable as:

```json
{
  "state": "DEFERRED",
  "reason": "Desktop prototype needs validation first",
  "reactivate_when": "Desktop prototype has 20 useful captures"
}
```

This differs from a time reminder. The system should reactivate when the reason for postponement ceases to apply.

## Portability

A portable Continuity bundle SHOULD contain at minimum:

- schema/protocol version,
- current state,
- event history,
- constitution rules,
- relationship graph.

The bundle SHOULD remain usable with a different reasoning model/provider.