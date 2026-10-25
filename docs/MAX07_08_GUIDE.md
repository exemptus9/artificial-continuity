# Total Recall 0.8 — capture, recall, resume

October 25, 2026 · America/New_York. A non-sensitive Android test release.

Install `TotalRecall-0.8.0-test.apk` over the original `org.continuity.recall.test`
0.7.0 or 0.7.1 installation. Do not uninstall or clear data. Export important
captures first. The delivered APK uses the original retained TEST signing key;
the CI emulator uses a separate disposable key for both versions in its update
test. These are separate artifacts even when the source commit is the same.

## One-minute start

1. Capture → type a harmless two-line note → Save capture. The receipt appears
   at the top with its ID, received UTF-8 hash, and **Open saved capture**.
2. Recall → choose Today, Inbox, Links, Voice, Files or Important. Open a source
   to mark it reviewed/important or add your interpretation, decision or next step.
3. Add a review with an existing project ID such as P29. **Threads** shows the last
   saved source and latest explicit decision/next step/blocker for that project.
4. Ask → “find notes about lighthouse today” or “first mention of lighthouse”.
   Results quote saved text and link to originals. Questions are never saved here.
5. Test → Verify durable test record. The visible result popup from 0.7.1 remains.

A draft is kept separately and is not a committed capture. New captures never
require a title, project, tags or a review. Raw voice notes remain playable in
the original archive view; there is no new transcription or background audio.

## What the new views mean

Recall's ordinary views show intentional captures and owner reviews. Automatic
app/notification metadata remains available under **All events & advanced filters**.
No app activity is used to infer work, attention, accomplishment or mood.

Threads uses the latest review of each source as its current classification.
Earlier interpretations remain preserved. If you change a decision into a next
step, that source no longer supplies a current decision; the old review remains
available. Separate source notes can hold separate decisions and blockers.
“Canonical” is an explicit personal label, not publication or a manuscript edit.
No recorded next step creates a reminder, message, calendar event or agent action.

Ask is deterministic lexical retrieval, not a connected LLM. It supports saved
topics, today/yesterday/last weekday/ISO date, and earliest surviving mention.
It does not understand arbitrary questions or search untranscribed audio. Empty
results mean missing matching evidence, not inactivity. Source text remains inert.
Pause still allows reading existing sources but blocks capture and outgoing share.

## Sharing, preservation and recovery

Share this view/project/source → preview → choose readable or JSON packet → choose
your destination. ChatGPT sees only what you attach/share. No endpoint, connected
archive, Lexidaemon enrollment or background synchronization is created.
Source-linked reviews include their original before the interpretation. Context
previews recheck record and organization hashes before sharing; deletion or edits
require a fresh preview. Packets remain bounded to 30 records and 60 KB of text.

Source deletion removes related review text and tombstones its IDs. Complete
protected exports include reviews and original references. Independent restore
checks source hashes and rejects orphan/corrupt dependencies. The Continuity
adapter preserves review attribution, orders originals first, and still requires
explicit non-sensitive test transfer because its destination working store is
plaintext. Independent old copies cannot be recalled.

The same encrypted vault and Keystore alias are used. There is no schema migration.
VersionCode 9 allows the normal update over 7/8; physical Samsung update behavior
still needs confirmation. Do not uninstall to solve a certificate conflict.
Android normally refuses version downgrades: code rollback needs a later same-key
repair build, or verified protected backup and a separately reviewed restore.

## Privacy and scope

No INTERNET permission, analytics, subscription, API key or new device permissions.
Automatic sources remain opt-in with local allowlists. No on-device model download
is required. Existing temporary plaintext audio/share files and lack of a separate
app lock remain limitations. This is not an audited vault or a sole archive.

## Source and verification

This iteration starts at the 0.7.1 fix commit
`92a89f26de5218b97263f959f6e9aa9eec68e08d`. It selectively reuses MemoryWorkbench,
TemporalEngine, source-review Core/transfer checks and tests from
`9f4c2b209e82d13f29a8f38c98e78722ee242342`, adapting the interface to the original
Total Recall package. It does not change the Temporal preview or production.

`android/tests/run-host-tests.sh` runs source integrity, retrieval, review,
privacy/deletion, Unicode, DST and transfer regressions. The separate native
workflow seeds 0.7.1 through its actual UI, updates in-place, verifies the visible
receipt, source view, review dialog, thread, Ask, encrypted independent restore and
process restart. The release receipt records actual executed results and exact
source/APK/signing identity; do not infer physical-phone PASS from CI.
