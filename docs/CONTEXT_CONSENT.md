# Context Consent Receipts

Remote reasoning creates a privacy problem even when the model is excellent: what exactly left the user's device?

Continuity records a `ContextConsent/0.1` receipt **before** a receipt-aware provider request is sent.

A receipt records:
- provider ID;
- reasoning operation;
- human-readable purpose;
- minimum-necessary scope policy;
- payload SHA-256 hash;
- approximate payload size;
- entity IDs identifiable from the payload;
- timestamp;
- stated retention policy.

It deliberately does **not** duplicate the full outbound prose into the receipt ledger.

## What a receipt proves

It lets Continuity later answer:

> Which provider was given what bounded context, for what operation, at what time, and can we identify the exact bytes again by hash?

It does not prove what the remote provider did after receiving the request. That remains governed by the provider's actual policy/contract and deployment environment.

## Data minimization

The generic remote adapter sends operation-specific context:

- resumption: one thread, directly related relationships, bounded relevant events;
- proposal drafting: a bounded Context Pack;
- pattern detection: idea/claim/relationship data, not unrelated artifacts;
- reconciliation: structural thread/decision/assumption state.