# Intention semantics and the ChatGPT exchange

The complete user instructions, examples, and troubleshooting are in the [offline-capable application guide](../site/help.html). This document records the design rules behind that interface. See also [0.20.1 release notes](V0_20_1_RELEASE.md) and [future integration paths](CHATGPT_INTEGRATION_PATH.md).

## Existing working boundary

Continuity stores records in the owner's browser. The owner selects one intention, prepares and reviews its text, copies it to an AI conversation, and brings back a structured proposal. Preparing a handoff does not send it. Copying or sharing does not establish that an AI received or answered it.

The owner may paste raw `ContinuityFormReply/1` JSON, a single JSON code block, or a full answer containing exactly one such block. The importer ignores surrounding prose, rejects ambiguous code blocks and unsupported operations, matches the request, rejects stale baselines, and presents a field-by-field diff. Nothing is preselected. Only explicitly selected fields are saved. Sources and reviewed checkpoints cannot be changed through this reply.

A reply's format does not authenticate its author. Model output is a suggestion, not an accepted decision, a verified accomplishment, or authority to execute an external action. No direct account connection, background synchronization, or model API has been introduced by this release.

## Seven compatible fields

| Field | Intended meaning | Fictional example |
| --- | --- | --- |
| `title` | A short recognizable name | Finish the article |
| `objective` | Desired outcome | Publish a clear article for new readers |
| `last` | User-recorded position or action, not automatic proof | Drafted the opening paragraph |
| `next` | One useful action under the owner's control | Write one supporting example |
| `open` | Questions that remain uncertain or undecided | Which reader should this target? |
| `state` | The owner's reviewed progress state | CONTINUE |
| `waiting` | An identified pending reply/event or real blocking reason | Awaiting the editor's response to Monday's email |

A company name alone does not establish what was done. A proposed action is not a completed action. A concern is not a verified causal explanation. Unknown details should remain unknown, rather than being filled with plausible history.

## Barrier, dependency, waiting, next

A **barrier** makes progress harder without proving impossibility. A **dependency** is a prerequisite for a specific action. **Waiting** identifies a response or event that is genuinely pending. **Next** identifies a step the owner can take.

Limited time is a barrier, not a pending response. A required document is a dependency. Awaiting that document from a named provider may be a real wait. Gathering another available document may remain a useful next action. Record uncertain causes as open questions or preserved notes; do not erase established obstacles simply to make the form look clean.

Use CONTINUE when useful work remains possible, even when one particular reply is pending. WAITING should describe the intention's actual dependency on an external event, not frustration. BLOCKED requires an identifiable prerequisite preventing the required work. Do not infer a progress state from tone.

## State meanings

CONTINUE means work can proceed. PROCESSING means the user marked work as underway, not that an AI is doing invisible background work. WAITING means progress primarily depends on an identified external reply or event. BLOCKED means a real prerequisite prevents the needed work. RESOLVED means the user considers the intention completed or settled. REFERENCE retains information without an active task. ARCHIVED retains history outside active work.

## Example response contract

This is a synthetic format example. `EXAMPLE-ONLY` is not a request ID for the owner's workspace.

```json
{
  "format": "ContinuityFormReply/1",
  "requestId": "EXAMPLE-ONLY",
  "fields": {
    "next": "Write one supporting example.",
    "open": ["Which reader should this target?"]
  },
  "explanation": "Suggestions only; no completed work is established."
}
```

Only `title`, `objective`, `last`, `next`, `open`, `state`, and `waiting` are accepted under `fields`. Request matching and the unchanged baseline remain necessary even when the full AI answer is pasted.

## Privacy and future design

This public repository holds reusable code, documentation, and synthetic examples, not the user's personal operational registry. Private transcripts, employment/legal/medical details, credentials, and unpublished creative work do not belong in public fixtures or release logs. Source excerpts remain opt-in; unrelated projects stay out of a default handoff.

Typed barrier/dependency/waiting relations are a future model proposal, not new fields in the current schema. Any later migration must preserve original wording, provenance, uncertainty, and the user's decision. A future remote connector must start with owner-approved scopes and staged proposals, not unrestricted assistant writes. Keep the manual, cross-model, offline-friendly route as a fallback.
