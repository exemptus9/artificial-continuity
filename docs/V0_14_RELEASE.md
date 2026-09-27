# Continuity v0.14.0 — fewer boxes, explicit handoffs

## Problem addressed

The user reported two connected usability failures: creating a useful project requires too much repetitive field entry, and opening Continuity inside ChatGPT hides the conversation they need to copy from. They also asked for analysis of a newly saved intention. Browser-local records are not part of the public repository and were not available to the development assistant; no private intention was inspected or inferred.

## Implemented workflows

1. **One-box intake.** Paste or dictate rough text (up to 16,000 characters), or use optional Name/Goal/Last/Next/Questions/State/Waiting labels. Plain text supplies a provisional name and goal, not inferred tasks or decisions. Preview, edit, then explicitly save the intention and exact original as a linked source. Attribution defaults to unknown. Duplicate originals are rejected rather than overwritten. Drafts preserve input and edited previews; changed raw input requires a new preview.
2. **Fill an existing form.** Inside an intention editor, expand “Fill these boxes from one text block.” Preview parsed fields, select the ones to use, then fill the unsaved form. The ordinary Save intention action remains necessary. This helper does not independently create source evidence or confirm decisions.
3. **Analyze / fill with ChatGPT.** Select one intention. A deterministic completeness check reports missing recorded fields and evidence counts; it is not model analysis. Prepare a request bound to that intention's seven editable fields. Checkpoint excerpts are opt-in; pending checkpoints, unrelated projects and full source transcripts are excluded. Review the exact outgoing prompt. Copy, native-share where supported, or download text. Generated requests persist and edited outgoing drafts can be reopened.
4. **Paste back proposed fields.** A `ContinuityFormReply/1` JSON response must identify an existing local request. Unknown/replayed requests, stale baselines, unsupported fields/commands, unsafe properties and invalid states are rejected. The user selects each changed field before applying. Applied requests are marked consumed in the same transactional workspace update. Sources, checkpoints and other projects cannot be edited through this contract. Replies are pasted data, not authenticated provider messages.
5. **Open separately.** Copy a clean app URL, with query/fragment removed, for a regular browser; on Android, a user-triggered “Try opening Chrome” intent is offered as best effort. The host app may block it. The helper explains profile-local storage and requires no public conversation share link. It does not access ChatGPT tabs, history or the current chat ID.

## Data and privacy

No remote inference endpoint, secret key, analytics, background upload or automatic chat-account access was added. The bridge moves selected text only on an explicit copy/share/download action. Clipboard and exported/shared text leave the workspace's control and need review. Native sharing does not guarantee ChatGPT is offered as a destination. The Chrome shortcut cannot guarantee an OS app switch.

App 0.14.0 continues using storage schema 0.11.0 with a validated additive assistantRequests collection. Full backups retain those requests; project transfers intentionally do not transfer reply authority. Browser records and drafts remain unencrypted. Optional encrypted backup files do not encrypt the working database. Requests are baseline checks, not digital signatures or a security boundary against someone who controls the browser itself.

No claims of novelty, patentability, background assistance or autonomous action are made.

## Release validation

The release workflow syntax-checks all JavaScript, runs all data tests, then the existing three native Chromium suites plus `tests/handoff-browser.cjs` before Pages deployment. The handoff suite tests one-box input, exact source preservation, selected form fill, drafts, scoped request text, clipboard, file download, response review/replay/staleness, inert markup, browser-help copy, 390px layout and offline operation. Native OS share and Chrome launching are tested only as feature-detected contracts; they still need verification on the user's phone.

The local development sandbox blocks URL navigation, including localhost. Local pure data tests are valid; native-browser results are taken from GitHub Actions, not inferred from a server starting. Refer to the actual release run for the final pass/fail counts.
