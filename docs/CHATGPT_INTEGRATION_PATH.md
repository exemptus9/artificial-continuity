# ChatGPT integration paths — minimal friction, explicit control

**Status:** design options only, except for the existing browser-local handoff and review flow. The current app does **not** have a connected ChatGPT account, an MCP endpoint, automatic cloud synchronization, or an OpenAI API integration. All examples in this document are synthetic.

Related: [Intention fields and the manual ChatGPT guide](INTENTIONS_AND_CHATGPT_BRIDGE.md).

## First decide which experience is desired

| Mode | What the person does | What currently works? | Trade-off |
| --- | --- | --- | --- |
| **Quick handoff** | Prepare one project, tap copy or device share, paste in ChatGPT; paste the entire AI reply back and approve fields | **Implemented** for one intention on the existing static browser app; sharing target depends on device | A few manual taps, no remote account/backend required |
| **Ask AI inside Continuity** | Select an intention and tap **Analyze with AI**; see proposals beside records | **Not implemented** | Requires a protected backend and model access. This is an API-driven AI interface, **not the user's existing ChatGPT conversation**, and API billing is separate |
| **ChatGPT-connected Continuity** | Say “Show my Continuity next actions” **in ChatGPT**; it calls a narrowly authorized Continuity service | **Not implemented** | Requires a remotely reachable MCP/custom app, authorization, and safe sync of selected data from local browser storage |

**Recommended sequence:** improve the current one-intention handoff; then build a scoped read-only connector with synthetic test data and opt-in synchronization; only later add explicitly approved write staging. Do not expose the entire private vault simply to save taps.

## A. Usable right now — quick handoff

The current workflow is:

1. Open Continuity in the **correct browser profile**, select an intention, and choose **Ask ChatGPT for suggestions**.
2. Select **Prepare text for ChatGPT** and review the proposed outgoing fields. Leave checkpoint excerpts off unless needed.
3. Select **Copy text for ChatGPT** or try the device share sheet. Switch to ChatGPT; paste and submit. Sharing or opening ChatGPT does **not** automatically send.
4. Copy the **whole ChatGPT reply** if it includes one JSON code block (or copy just that JSON). Paste in Continuity's **Use ChatGPT → Step 3**, then select **Compare suggested changes**.
5. Compare current and proposed values, check only the fields you want, and select **Save approved changes**.

The importer accepts a raw `ContinuityFormReply/1` JSON object, a single JSON fenced block, or one fenced JSON reply embedded in explanatory text. It rejects multiple fenced blocks, unsupported keys or commands, missing request IDs, stale requests, and changes to sources/checkpoints. No fields are preselected.

This intentionally avoids constructing a URL containing personal data, which would be easy to leak through history, logs, share previews or analytics.

## B. Natural interaction inside Continuity (future)

**Desired screen concept**

```text
Intention: Finish a working prototype

[Where I left off: reviewed notes]   [Next: test the demo]

[ Ask AI about this intention ]

Share for this analysis:
 [x] Intention's seven fields
 [ ] Confirmed evidence excerpts
 [ ] Full source text (requires separate warning/consent)

AI suggested:
  NEXT
  Current:  Test something
  Proposed: Run three defined acceptance tests and record results

[ ] Approve Next       [Discard suggestion]
```

**Suggested architecture:**

```text
Owner's Continuity browser
   -> explicit opt-in request with scoped content
   -> authenticated, rate-limited service (stores model API key)
   -> OpenAI Responses API / replaceable model provider
   <- one strictly validated proposal + explanation
   -> diff + owner's approval
   -> local event log; do not auto-accept model output
```

Requirements: authentication, scope consent, protected server-side API key, defined usage budget, minimal retention, short response limits, secure transport, injection-resistant typed outputs, replay/stale-write protection, and an offline/no-AI fallback. **Do not put a provider API key into browser JavaScript, the public GitHub repository, or URLs.**

This would be convenient on Android and could use voice dictation for the question. It would **not** share your current ChatGPT chat history, memory, or subscription billing automatically. OpenAI API billing is a separate product; account-based allowance sharing may exist in participating tools but cannot be assumed for a custom app.

## C. Talk to Continuity directly from ChatGPT (future MCP/custom app)

**Desired user command:** “Continuity, what are my open intentions, and what did I last verify about the prototype?”

ChatGPT could call a private, project-scoped remote connector instead of receiving a manually copied Resume Pack. **A static GitHub Pages site with browser-local IndexedDB is not an MCP server**; the AI cannot reach private browser storage merely because the site is publicly hosted.

### Prerequisites

1. Create a secure remote companion / sync gateway that holds **only owner-approved project projections** or securely proxies a reachable user-controlled store.
2. Authenticate the owner through a well-defined access flow (prefer OAuth/OIDC). Support short-lived tokens, scoped permissions, revocation and an access log.
3. Expose read-only MCP tools first, such as:
   - `continuity.list_intentions` — IDs, titles and selected progress state for explicitly allowed projects.
   - `continuity.get_resume_pack` — scoped facts with provenance; redacted by default.
   - `continuity.get_recent_changes` — changes limited by project and time.
   - `continuity.get_evidence` — only if a separate, explicit source-content permission was granted.
4. Test with synthetic records and inspect the exact content ChatGPT sees. Ensure other projects, sources and credentials never enter results.
5. Only after this is secure, add **`continuity.stage_proposal`**, which creates a pending proposal. There should be no unrestricted assistant-controlled `apply`, `publish`, `delete`, or share action. The **owner must approve** any durable mutation through Continuity's interface, with base version and source checks.

### Suggested action boundary

```text
User asks in ChatGPT
  -> MCP list/get scoped context (read-only)
  -> model produces analysis
  -> optional stage_proposal (not canonical)
  -> owner opens Continuity review
  -> selected changes explicitly accepted
  -> append-only event; source and decisions preserved
```

A future app might display a small “Review 3 suggestions” card within ChatGPT itself. A card's existence cannot substitute for the underlying Continuity authorization and concurrency checks.

**Important plan/interface caveat (October 2026):** The published OpenAI developer-mode guidance says custom MCP apps are available in selected account/workspace configurations, that full MCP write support is not universal, and that custom MCP apps are currently a **web-only** experience. Plan eligibility and organization permissions must be checked in the current product. Do not promise an Android custom-MCP workflow that the platform does not currently offer.

Current official references:
- [Developer mode and MCP apps in ChatGPT](https://help.openai.com/en/articles/12584461-developer-mode-and-full-mcp-connectors-in-chatgpt)
- [Build with the Apps SDK](https://help.openai.com/en/articles/12515353-build-with-the-apps-sdk)
- [ChatGPT vs API billing](https://help.openai.com/en/articles/9039756-billing-settings-in-chatgpt-vs-platform)

## Security and design acceptance gates

- No global ChatGPT account access implied by a copied handoff or source URL.
- No automatic ingestion of all chats or all browser-local intentions.
- Default share scope: one selected intention's `title/objective/last/next/open/state/waiting`, minus private extra sources.
- Record *which scope* the user chose, when, and for what request. Provide a way to revoke future remote access.
- Treat model text, imported documents, transcripts and retrieved tool results as evidence or suggestions, **never commands**.
- Keep `user-reported`, `source-observed`, `inferred`, `generated`, and `user-approved` distinct, with provenance and time.
- Do not treat “difficult” as synonymous with “blocked” or “waiting”. Missing evidence must remain unknown.
- No write without explicit user approval; no silent best-guess merges after a concurrent edit.
- Keep original sources immutable; retain backups and private/offline use.
- Fail closed if token refresh, access control or sync reconciliation fails; do not silently fall back to public data access.

## Suggested smallest next engineering milestone

Build and test an **opt-in read-only Resume Pack endpoint** with synthetic intentions. Confirm project-scoped permissions, credentials never reaching client logs, revoked tokens failing, and source-excerpt opt-in before syncing any real project data. Only then attempt a ChatGPT custom app connection from an eligible web environment. Preserve the current copy/paste route as the offline and cross-model fallback.

Do **not** represent a prepared MCP specification, staged JSON, public demo, or GitHub commit as a live connection to the user's private data.
