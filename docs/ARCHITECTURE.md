# Continuity Architecture

## Thesis

**Artificial Intelligence helps you think. Artificial Continuity helps your thinking survive time.**

The model is replaceable. Continuity is the product.

## Kernel pipeline

```text
capture / connector / user command
              ↓
      structured observation
              ↓
       reasoning provider
              ↓
        action proposal
              ↓
    Personal Constitution
              ↓
 autonomy + policy classification
              ↓
 explicit approval when required
              ↓
        append-only event
              ↓
         event reducer
              ↓
       derived current state
              ↓
  reconciliation / resumption
```

## Core modules

- schema — current state shape and seed graph
- reducer — deterministic state transitions from events
- event store — append-only storage and hash-chain validation
- policy — autonomy classification and constitutional checks
- reconciliation — context debt, decision debt, stale assumption dependency detection
- proposals — proposal envelope and safe local command parsing
- export — provider-neutral portable bundle
- reasoning provider — replaceable local/cloud reasoning boundary
- sync — divergence-aware event-history exchange
- prospective memory — condition-triggered relevance
- HIGP — portable intent-graph representation

## Authority model

| Level | Capability | Default |
|---|---|---|
| 0 | Observe/classify/reason | allowed |
| 1 | Reversible internal mutation | may be standing-authorized |
| 2 | Consequential internal mutation | explicit approval |
| 3 | External action | explicit approval |

## Event sourcing

Current state is derived from an ordered event stream. Periodic snapshots may exist as performance accelerators; events remain canonical.

## Cognitive branching

A branch is not merely a copy of a document; it is an alternate evolution of the intent graph.

The architecture supports:

- create branch from a known base
- mutate branches independently
- structural / semantic comparison
- merge preview
- explicit conflict review
- adoption through a provenance-bearing event

## Intention semantics and assistant boundary

The current seven browser intention fields summarize user-recorded project position, not independently proved truth. See [Intention fields and the ChatGPT bridge](INTENTIONS_AND_CHATGPT_BRIDGE.md) for precise distinctions between barriers, dependencies, genuine waiting and actionable next steps. Assistant output is non-canonical until the user selects proposed fields. Future typed relations and direct MCP integration must preserve this approval boundary; neither is silently enabled by the present UI changes.

## Design invariant

**Reasoning may be replaceable. Continuity semantics should remain inspectable, portable, and user-governed.**