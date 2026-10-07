# Clear intentions and the ChatGPT bridge

## What this document describes

Continuity is the owner-controlled record of intentions, sources, decisions and progress. ChatGPT is an optional reasoning assistant, **not** an automatically connected database, account reader, or source of accepted decisions. This document describes the existing browser app's user-driven exchange and the meaning of intention fields. It does not claim automatic two-way synchronization.

**Important privacy boundary:** The public source repository contains reusable application code and synthetic examples. A user's actual intention data resides in the browser profile where it was saved (or in a separately controlled export/store). Never publish private intentions, raw transcripts, passwords, personal contact details, or legal/medical history in this repository, a public PR, or test artifacts.

## The simplest mental model

- **Continuity remembers what you authorized it to preserve.**
- **ChatGPT thinks about only the material you choose to show it.**
- **You decide which proposed changes, if any, become your current record.**

```text
ONE SELECTED INTENTION (stored in the user's browser)
       |
       | Prepare a scoped, editable handoff
       | User reviews and copies / shares it
       v
    ChatGPT or another AI assistant
       |
       | Analysis plus ContinuityFormReply/1 JSON
       | User copies the JSON reply back
       v
Continuity checks matching request ID + unchanged intention
       |
       | Field-by-field diff; no fields preselected
       v
USER SELECTS WHICH FIELDS TO ACCEPT
       |
       v
Reviewed local save, preserving original sources/checkpoints
```

The browser app does **not** read the ChatGPT app's history, fetch a private account, send the handoff automatically, or import a model reply automatically. The ChatGPT service does not get access to the local Continuity database. The user-driven handoff is the integration in this release.

## Use ChatGPT with an intention: five small actions

1. **Choose the intention.** Open the Continuity app in the **same browser/profile that contains the record**. Open **Intentions**, select your project, then **Ask ChatGPT for suggestions** (or open **Use ChatGPT with an intention**). Choose that one intention.
2. **Prepare what to share.** Type an optional question, such as “Identify my most useful next action and separate known obstacles from assumptions.” Leave **Include confirmed checkpoint excerpts** unchecked unless those source quotations are actually necessary. Select **Prepare text for ChatGPT**. Carefully review the exact generated text; it includes the chosen seven intention fields, request ID, and any excerpts you explicitly opted into.
3. **Copy, switch, send.** Select **Copy text for ChatGPT**. Switch to a ChatGPT conversation (or another model), paste, and send. You can open ChatGPT separately. No private text is added to the URL. Do not assume that merely clicking **Open ChatGPT** transfers the handoff.
4. **Bring back the structured suggestion.** ChatGPT will usually respond with analysis and a single JSON code block marked `ContinuityFormReply/1`. Copy the **whole ChatGPT response** if it contains exactly one JSON code block, or copy the JSON block by itself. The importer extracts the one JSON code block and ignores surrounding prose. If the response has no JSON block or multiple code blocks, ask for one clean `ContinuityFormReply/1` JSON block. Return to the same Continuity browser/profile and paste it under **Step 3: Review ChatGPT suggestions**. Select **Compare suggested changes**.
5. **Approve specific edits.** Review the **Current** and **Proposed** values for every changed field. Nothing is checked in advance. Select only the changes you agree with, then **Save approved changes**. Rejected fields remain untouched. Original sources and reviewed checkpoints cannot be modified through this assistant reply.

**Example question to type before preparing:** “Which next action can I complete today? Separate established facts from concerns, and leave unanswered questions explicitly open.”

**Example, not a real imported record:** Suppose your intention is “Find dependable work.” Your last entry says only “Employer A,” and your next action is empty. ChatGPT can suggest that you *record what you last actually did* and propose the actionable next step “Find three suitable vacancies, apply to one, and record the date.” It must **not** claim you submitted a particular application or that an employer rejected you without actual evidence. You still choose which suggestions to adopt.

### Example reply format (illustrative ONLY)

This is deliberately **not** a valid reply for your own open handoff: its request ID is a placeholder. Use the exact request ID supplied by your Continuity app.

```json
{
  "format": "ContinuityFormReply/1",
  "requestId": "EXAMPLE-ONLY",
  "fields": {
    "next": "Identify three suitable openings, apply to one, and record the result.",
    "open": [
      "Which hours are workable?",
      "Which requirements still need verification?"
    ]
  },
  "explanation": "These are suggestions, not evidence of applications or employer decisions."
}
```

Only the seven supported keys belong inside `fields`: `title`, `objective`, `last`, `next`, `open`, `state`, `waiting`. No commands, source changes, checkpoints, or external actions are accepted from this reply.

## What every field means

| Current field | Friendly interpretation | Strong example | Weak / ambiguous example |
| --- | --- | --- | --- |
| `title` | **Name** — a short recognizable intention | “Find dependable work” | “Stuff / task” |
| `objective` | **Desired outcome** — what success looks like | “Obtain a role with predictable hours” | “Bojangles” (an entity, not an outcome) |
| `last` | **Last recorded position** — what actually happened or where you stopped | “Sent a message requesting an interview on Monday” *(only if true)* | “Company A” without an action |
| `next` | **Next useful action** — a small step you can control | “Apply for one opening and log its details” | “Get hired” |
| `open` | **Open questions** — what must be investigated or decided | “Which shifts can I accept?” | An unverified suspicion presented as a fact |
| `state` | **Progress state** — overall phase | `CONTINUE` when action remains possible | `BLOCKED` just because progress is difficult |
| `waiting` | **Pending external response/event or actual blocking reason** | “Awaiting response to the interview inquiry sent Monday” | “I haven't applied enough” |

**Important:** `last` is *user-recorded*, not independently verified merely because it is filled in. A supported checkpoint/source improves the evidence grade. **Assistant-suggested** wording is not a confirmed event.

## The distinction that matters most

A project can have several different kinds of friction, and they should not be collapsed into “waiting”:

| Concept | Definition | Example | Where it belongs today |
| --- | --- | --- | --- |
| **Barrier** | Makes progress harder; does not prove impossibility | Limited transportation options | A user note or an `open` question such as “What travel options work?” |
| **Dependency** | A genuine prerequisite for a specific step | A required document before enrollment | Note it as an unresolved question or record; a pending external dependency may also appear in `waiting` |
| **Waiting** | A *specific identified* reply or event has not happened | Application response still pending | `waiting`, with `WAITING` only if it governs the project's state |
| **Next action** | A concrete step under your control | Gather the required document | `next` |
| **Unknown cause** | A hypothesis that has not been established | Why an application was unsuccessful | `open`, not a statement of verified causation |

**Decision rule:** If a useful next action is available, `CONTINUE` often describes the project better than `WAITING`, even when you are waiting for *one particular* reply. `WAITING` or `BLOCKED` should reflect the actual state of the intention, not frustration, perceived difficulty, or an unsupported explanation.

## State names (the existing seven)

- **CONTINUE** — work can proceed; there is a plausible next action.
- **PROCESSING** — the user has marked work as underway. This label does **not** mean ChatGPT is performing unseen background work.
- **WAITING** — progress is primarily pending a specific external response or event; describe it in `waiting`.
- **BLOCKED** — a real, identifiable prerequisite prevents the needed work; record what prevents it.
- **RESOLVED** — the intention is completed or settled by the user.
- **REFERENCE** — kept for information, without an active to-do.
- **ARCHIVED** — retained for history but no longer active.

**Do not infer progress state from tone.** Preserve the user's selected state unless they explicitly review and adopt a change. In particular, imported AI output is a proposal, not an authorization.

## Common problems and recovery

- **“No matching request on this browser.”** Return to the browser/profile in which you prepared the handoff. If you moved devices or cleared storage, restore or transfer data first rather than guessing at request IDs. A different browser can show an empty workspace.
- **“The intention changed after this request.”** This is an intentional stale-write protection. Prepare a fresh handoff using the newly saved fields.
- **ChatGPT produced paragraphs but no JSON.** Ask: “Please return exactly one `ContinuityFormReply/1` JSON block using the original requestId and only the allowed fields.” Copy only the JSON.
- **No suggestions appear.** The proposed values may match the existing fields; no change is necessary.
- **The clipboard fails.** Select/copy the text manually or use the existing download/share fallback. Sharing to another app is not confirmation that an analysis was completed.
- **Android opens Continuity in a temporary browser panel.** Use **Open separately / browser help**, copy the clean URL, and open it in your main browser. Back up or transfer your intention first: data does not synchronize automatically between browser profiles.
- **The reply mentions evidence you did not share.** Treat it as an unsupported inference. Do not turn it into a completed action or canonical decision without checking real sources.

## Design corpus: what changes now and what comes later

**Implemented without a storage migration:** clearer editable intention-field labels and examples; guided three-step handoff; the stricter analysis prompt that separates barriers, dependencies, genuine waiting, and controllable next actions; and ordinary review-before-save. Existing intention records, source history, and the `ContinuityFormReply/1` contract are retained.

**Future model proposal (not implemented as fields in this version):** represent `barrier`, `dependency`, `waiting_for`, and `next_action` as typed work relations or events with source references, attribution, validity time, confidence, privacy scope, and user decision status. The current seven-field form remains backward compatible. Do not silently migrate a free-text `waiting` value into a confirmed dependency without review.

See [ChatGPT integration paths](CHATGPT_INTEGRATION_PATH.md) for the concrete future routes and security requirements.

**Future optional direct connection (not implemented):** an explicitly authorized, project-scoped read API or MCP adapter could let ChatGPT (where supported and connected) request only approved Resume Packs and submit *staged* proposals. The Continuity owner must grant and revoke access, see precisely which records are shared, and explicitly confirm durable writes. Never place reusable API keys in exported prompts, URLs, or public source files. This must not bypass local provenance, stale-update checks, or field-by-field approval. If no direct connector is configured, the manual handoff remains a complete, portable path.

## Acceptance criteria

A usable release should preserve these invariants:

1. The user can find the handoff steps inside the app without guessing how ChatGPT can see data.
2. Unselected projects and unpublished original sources are never included in the default handoff.
3. An assistant can propose field edits but cannot execute commands, add evidence, or change sources through `ContinuityFormReply/1`.
4. No field is automatically selected for adoption; stale replies are rejected.
5. An ambiguous concern never becomes a verified barrier or a false `WAITING` transition without review.
6. On mobile, a user can copy, switch, return, preview, and adopt without assuming automatic account integration.
7. Documentation explicitly distinguishes current working behavior from optional future connectors.
