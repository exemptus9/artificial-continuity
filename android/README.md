# Total Recall for Android · 0.7.0 test release

Install the supplied `TotalRecall-0.7.0-test.apk`. This is one native companion
inside the existing `artificial-continuity` repository, not a new product service.
It has no Internet permission, analytics SDK, paid dependency, or required desktop.
It requests special permissions only for optional app history, notifications and
user-triggered recording. Everything begins off with empty allowlists.

## First capture (about a minute)

1. Open the APK on Android 8.0+ and allow installation from that specific source
   only if Android asks. Verify this release's checksum/signature if your workflow
   supports it. If Android or Samsung blocks installation, use the displayed
   specific-source/restricted-setting guidance; do not disable protections globally.
2. Open **Total Recall · Test** → **Test** → **Prepare a unique multiline test phrase**.
3. Press **Save capture**. The receipt includes the record ID, time and text hash.
4. Close/reopen the app → **Test** → **Verify durable test record**.
5. **Timeline** shows it. Tap the item to inspect the original and annotate it.

Revoke the install-source permission afterward. This is a test-signed sideload,
not a Play Store release. Do not use it for sensitive everyday material yet.

## Intentional capture

- **Capture** accepts exact text received from the editor, including explicit paste.
- Android **Share** → **Total Recall · Test** receives text, URLs and up to four
  content-URI attachments. Review and press Save; share reception itself is not a save.
- **Choose an image or file** uses Android's document picker, without gallery access.
  The original selected bytes are hashed and retained. No OCR is implemented.
- **Record voice note** requests microphone permission, records visible-screen AAC/M4A,
  and preserves the original file. Stop saves; Cancel deletes. Leaving/locking stops
  and attempts to save. If the process dies, a recoverable, possibly incomplete file
  may remain. Playback is in the original-record view. Transcription is unavailable.
- The launcher icon and Android share target provide the low-friction entry points.
  There is no Side-button interception, watch requirement or background recording.

Text drafts are encrypted after a short debounce and on leaving the screen.
Attachments awaiting Save exist in memory and are not recoverable drafts. Android
and your keyboard may have transformed text before receipt; the app's hash cannot
prove upstream clipboard bytes. Shared URLs are saved links, not archived webpages.

## Automatic sources

In **Controls**, choose ordinary apps, enable the source, then open the matching
Android permission settings. OS permissions are broader than the internal allowlist.
Unselected apps are rejected before persistence; default is to allow nothing.
Keep password, authentication, health and financial apps unselected. No classifier
can promise to recognize every sensitive source. Whole browsers may be excluded.

App activity uses `UsageStatsManager.queryEvents`, on-open catch-up and a persisted
`JobScheduler` task with a 15-minute minimum period and battery-not-low constraint.
It records resumed/paused/stopped/destroyed transitions, original and observed times,
package identity and a hashed occurrence identity. It scans at most 24 hours back
and exposes gaps/backlog. It does not track screen text, URLs, attention or work.
Multi-window may yield overlapping activity. Same package/type/class/timestamp
occurrences are treated as retries because the public SDK exposes no finer identity.

Notifications use `NotificationListenerService`. Metadata only: package, posted /
updated / removed / discovered-active distinction, timestamps, grouping flags and a
salted-by-device hashed OS identity. First callback is “posted-or-updated” because
prior state may be unknown. Reconnect discovery uses observation time, with OS post
time separately labeled. No title, message, tag, channel, action or extras are saved.
Notification content capture is intentionally absent, including redacted content.
Initial discoveries whose post time predates enable/allowlist or a paused interval
are discarded. Notification callbacks are not proof that a person read anything.

Force-stop is stronger than closing: Android may suspend jobs/listeners until the
app opens again. Reboot, battery optimization, permission revocation and Samsung
restrictions can interrupt capture. Status does not imply complete coverage.
The build has no unstoppable foreground service. Battery impact is unmeasured.

## Protection and limits

Live store: atomic, encrypted AES-256-GCM snapshot in the app's no-backup directory,
with a non-exportable Android Keystore key. Hardware backing is not asserted.
Android sandboxing/device encryption are separate protections. There is no extra app
lock; anyone able to use the unlocked app can read the timeline. The app suppresses
recents/screenshots with `FLAG_SECURE`. The OS keyboard is outside its control.

Audio is briefly plaintext in the no-backup sandbox during recording/finalization.
Playback and explicit sharing create bounded temporary plaintext cache files.
Share grants expire at provider access after ten minutes; cache cleanup occurs via
Pause/Forget/reset and startup expiry cleanup. Independent recipient copies remain.
No crypto keys or private phone data are packaged in the APK or public source.

Protected backups use PBKDF2-HMAC-SHA256 (210,000 rounds, random 16-byte salt) and
AES-256-GCM (random 12-byte nonce, 128-bit tag), via standard JCA. This is a documented
custom file envelope using standard primitives, not a third-party audited vault.
Keep a strong passphrase separately; there is no password recovery service. Losing
the phone key does not block restoring a protected backup on a fresh install.

Limits: 500,000 UTF-8 text bytes per record, 4 attachments/capture, 5 MiB/attachment,
20 MiB total attachments, 3,000 active records, 32 MiB store, bounded import depth.
Seven-day automatic telemetry retention is applied during usage catch-up; manual
creative captures never expire. Tombstones consume space too; long-term compaction
is not implemented. Capacity failures retain the existing disk store, and the UI
reports failure. Do not treat this prototype as a sole archive.

## Pause, deletion, restore, rollback

**Pause all capture** stops capture, draft persistence and outgoing share/export.
An active recording is cancelled. Resume advances the usage cursor and persists
an exclusion interval; paused OS history is never silently backfilled.
Source disable/re-enable likewise starts usage from the new enable time.

**Forget** removes the selected record/time range, unreferenced attachments, outbox,
drafts, temporary caches, isolated test restores and import checkpoints. It preserves
tombstones so importing an old backup into a store with the current policy cannot
resurrect deleted content. A fresh device needs a current backup containing that
policy. Old independent backups, sent context packets and previous Continuity
copies are not remotely deleted; this app does not promise that capability.

**Export complete protected backup** uses a user-selected destination. A file write
is not a backend acknowledgement. **Restore** decrypts and validates hashes/counts,
restores to an isolated encrypted store, reads it back, and shows a preview. The
active store remains unchanged until you explicitly choose Merge. Current deletion
policy wins; collection permissions are never imported. Repeated import is idempotent.
A merge first saves `before-import.v1`; Forget removes it. A failed validation never
replaces the active store. The schema has no upgrade migration yet: unknown schemas
fail closed, so app-code downgrade is not a data rollback. Export/verify before
installing another build. Never uninstall your sole unexported archive.

## Continuity and ChatGPT

Transfer is explicit/manual, not automatic synchronization. **Timeline** filters
by text/package, capture type, date, project and review state. Its context preview
includes up to 30 matching records and 60 KB of original text with IDs, timestamps,
provenance and coverage limitations. The readable and JSON shares contain no audio
or attachment bytes. Open Android's share chooser and choose ChatGPT if offered;
otherwise save the packet and attach it to a chat. Nothing is sent automatically.

The adapter `tools/phone_archive.py` reads protected complete backups and targets the
existing `capture_context.Store` implementation. It preserves the original event
object, exact text and original attachment bytes by hash. It also preserves deletion
ledgers in core backups. Existing Continuity working stores are plaintext, so this
release deliberately requires an explicit non-sensitive-test-copy designation.
Sensitive imports are blocked by the supported CLI. Local phone use does not need it.

```
python -m pip install -r android/requirements-transfer.txt
python tools/phone_archive.py TEST.trbackup.json
python tools/phone_archive.py TEST.trbackup.json --root /private/continuity-root \
  --project P29 --approve-non-sensitive-test-copy --receipt /private/receipt.json
```

The root must already be initialized with the existing core's init command and an
approved project registry. Password entry is interactive, never an argument or URL.
The new record IDs retrieve via existing `/v1/sources/CAPTURE.<event-id>` and lexical
search. The existing owner/agent authorization still applies: captured records stay
private and scoped agents cannot retrieve them. This assistant has no direct live
connection to the phone or automatically loaded future-chat context. Lexidaemon
registration, authenticated remote sync and direct ChatGPT-connected retrieval are
not configured. An endpoint's existence would not change that.

Native exports carry deletion rules. The adapter refuses transfers whose deletion
rules intersect previous imports, because the append-only old core cannot erase
historical payloads safely. Rebuild a reviewed destination if deletion is required;
do not claim remote erasure. The local app remains fully usable without this step.

## Troubleshooting

- Save fails: reduce attachment size or export/review capacity; original disk state
  remains. A failed save has no committed-record receipt.
- No app events: ensure both the source and package allowlist are enabled and Usage
  Access granted; switch apps, return, Catch up now. Prior disabled history is skipped.
- No notifications: allow this app itself, enable metadata, grant Notification Access,
  post a test notification, and inspect Timeline. Posting and listener receipt differ.
  If restricted settings block access, follow Android's specific app setting prompt;
  do not bypass device protection. Menu wording is device-dependent.
- Background delay: periodic scheduling is inexact; use manual catch-up and inspect
  gaps. Do not infer inactivity from absence. Force-stop/reboot tests remain separate.
- Missing mic: grant Record Audio, close conflicting recorders, then try phone mic.
  External input must be tested acoustically, including unplug/disconnect. No inferred
  Bluetooth route. Original audio without a transcript is expected.
- Corrupt backup/wrong password: active store is unchanged. Try a verified older copy;
  do not delete the original. Forgotten passphrases cannot be recovered.
- Missing network or credentials: local captures have no such dependency. No sync or
  credential UI is shipped; expired-auth tests apply only to the existing core service.

## Build and evidence

Build dependencies: JDK 17, Android platform 36, build-tools 36.0.0. No Gradle or app
library downloads are required once the toolchain is installed. `build.sh` compiles
Java, packages DEX/resources, aligns, signs and verifies the APK. Provide an external
private keystore/password; never commit signing material. The delivered identity is
TEST signing. A protected owner signing recovery package is separate from source.
Host tests also use `org.json:json:20240303` (test-only), and Python transfer uses
`cryptography==46.0.0`. See the release receipt for exact executed commands/results.

Current platform references (checked October 4–5, 2026):
- https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryEvents(long,long)
- https://developer.android.com/reference/android/service/notification/NotificationListenerService
- https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction
- https://developer.android.com/media/platform/mediarecorder
- https://developer.android.com/training/data-storage/shared/documents-files

Notification pause/disable is conservative: post times before the latest enable or
resume are excluded even if Android re-delivers an active notification later. That
can skip an update whose original OS post time is unchanged; it prevents silent
recovery of a paused interval, at the cost of explicitly incomplete coverage.
