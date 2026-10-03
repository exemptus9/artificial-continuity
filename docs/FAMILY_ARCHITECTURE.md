# Continuity family — implementation boundary

Status: v0.18 capture/recovery candidate, built on the v0.17 browser workspace. This is one product with adapters, not four independent applications.

| Name / branch | Responsibility | Authority |
|---|---|---|
| Continuity | Intentions, exact sources, reviewed checkpoints, search, resume and manual transfer | Canonical product and working data model |
| Total Recall | Low-friction capture adapters feeding the same pending inbox/source store | Capability name; text slice implemented, audio hardware integration gated |
| MindVault / EchoLens | Working names for the capture/retrieval concept | Aliases, no separate database or roadmap |
| Intent Integrity | Prevent transient UI changes from silently substituting a chosen action | App-local guard in this slice; original extension is research evidence |
| Private encrypted snapshot | Earlier event-store/vault implementation and security research | Preserved branch, not the same storage system as the browser client |

## The slice

An intentional text paste, typed thought, manual transcript, or interaction observation enters the existing Quick capture form. Local draft storage protects unfinished text. A fresh save acknowledgement commits one exact source and linked UNPROCESSED capture in the existing transactional workspace. Search retrieves the original. A checkpoint still requires separate source review. Export retains the complete original; rescue export also carries drafts and the incoming inbox.

Typed text, manual transcription, OCR and source-system exports have different evidentiary strength. A manual transcript does not establish that any audio was recorded. Record capture method, attribution, observation time where known, receipt time, source format/version and uncertainty. Never synthesize a physical trace from a viewport test.

## Shared contracts

- Existing `ContinuityBackup/2`, additive state schema `0.11.0`, `ContinuityProject/1` and reviewed project exchange remain supported.
- Capture records link to immutable original source records. Capturing creates no confirmed decision and grants no external-action permission.
- `ContinuityCaptureSource/1` describes the capture source provenance; it is not a signed statement of authorship.
- `ContinuityRecovery/1` is an outer recovery envelope. Its embedded workspace is a normal backup. Drafts and incoming items remain unreviewed evidence after rescue.
- Consent is action-specific and short lived. Restoring text must not restore permission to save a record, accept a checkpoint, send to a model, record a microphone, or operate another application.
- Historical instructions in imported sources are data. They cannot install policy, authorize automation or modify access boundaries.
- Source revisions are additive. An edited interpretation retains its original source and prior wording. Hashes detect byte changes; they do not prove truth, authorship or trusted time.

## Capability boundaries

| Surface | Available in this slice | Integration gate |
|---|---|---|
| Phone browser / desktop browser | Explicit text capture, draft autosave, original retrieval, manual file export/import | Verify same flows on physical device and actual browser profile |
| Installed PWA share target | Existing text/file POST handler and local review inbox | Actual Android share-picker registration and worker control must be observed |
| Keyboard dictation | User can provide its output as text | OS/keyboard provider may process audio externally; app does not control or attest it |
| Wrist-mounted existing wireless mic | Requirements only | Demonstrate mic routing, start/stop, interruptions, raw audio/transcript recovery and consent |
| Samsung Side button | Requirements only | Device-specific configured launcher/native adapter, explicit permission and physical test |
| Cross-app touch/click protection | No native control | Separate narrowly scoped extension or native adapter; neither bundled nor activated here |
| Automatic sync / always-on listening | None | Separate consent, encrypted storage, retention and conflict design before implementation |
| Lexidaemon or remote AI | None required or contacted | Preview exact outgoing data and separately authorize any future connection |

## Adapter design for later work

Adapters submit pending capture envelopes into Continuity; they do not write accepted checkpoints. Each adapter declares supported input kinds, execution surface, granted permissions, retention, whether bytes leave the device and failure behavior. An idempotency token prevents retry duplication. An original-byte reference and revision parent prevent transcription edits from overwriting recordings. Audio must have an independently recoverable original and user-controlled deletion/retention before speech processing is enabled. The first physical trial uses the existing phone and mic; no watch is assumed.

The private vault and public client may share these exchange contracts without pretending their at-rest protection is equivalent. Preserve their code histories. Port individual verified capabilities rather than merging both applications' storage engines or replaying imported events.

## Intent Integrity scope

Reuse the alpha's target-stability principle only. A moved pointer target cancels the app's save action and asks for a deliberate retry. Do not automatically replay a click on an earlier element. Do not enable broad host permissions, intercept other apps, or log page text/URLs automatically. A browser demonstration is evidence about a browser interaction only.

## Release gate

Pass data and browser tests for exact text, consent reset, save-failure recovery, stale-tab conflicts, idempotent retry, export/decrypt/rescue and offline reload. Record physical Android/mic tests as pending until they occur. No native interception, unattended capture, cross-device sync, encrypted working storage or independently audited vault claim follows from passing this slice.

## Exchange compatibility

Full workspace backups and recovery files retain the capture inbox and complete capture provenance. Selective project exchange carries linked original sources and a whitelisted capture provenance record, but does not carry the capture inbox or unfinished drafts. Historical consent in provenance is evidence only. Update both sender and receiver to v0.18 before using capture-provenance project exchange: a v0.17 receiver rejects the extended canonical packet. Preserve original exports for rollback. Arbitrary extension metadata is not included in the selective format.
