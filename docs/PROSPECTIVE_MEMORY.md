# Prospective Memory

Traditional reminders are time-centric: "tell me Tuesday."

Continuity treats many deferred intentions as **condition-centric**:

> Bring this back when the reason for deferral stops being true.

`ProspectiveMemory/0.1` supports deterministic conditions such as:

- event count / threshold;
- entity field state;
- relationship existence;
- date/time threshold;
- `all` / `any` composition.

When a trigger becomes true, v0.7 records `TRIGGER_FIRED` and creates a policy-gated proposal. It does **not** silently execute the proposed state change.

This preserves the distinction between:

- remembering that something is newly relevant; and
- authorizing the action that relevance suggests.