# Total Recall native schema and adapter / v1

This extends the existing capture family through `phone_archive.py`; it does not
change `ContinuityContext/1` or add a public endpoint. Stable phone UUIDs map to
`CAPTURE.<uuid>` in `ContinuityCaptureRecord/1`, with private visibility and UNKNOWN
source classification. Project transfer scope defaults to existing P29, explicitly
chosen by the operator. Phone annotations never mutate canonical project state.

`TotalRecallEvent/1`: `id`, pseudonymous per-installation `device_id`, `source_type`,
`capture_method`, `event_ms/at`, `observed_ms/at`, `ingested_ms/at`, timezone and offset,
privacy scope, consent policy version, evidence category, package/app label, original
text and SHA-256, attachment refs, and bounded type-specific metadata. Source time
is a reported OS/user clock, not trusted server proof. Negative, unknown and malformed
timestamps are rejected, not guessed. Clock reversal resets catch-up with a gap.

Types are `note`, `link`, `image`, `file`, `audio`, `auto.usage`, `auto.notification`.
Manual text has USER_SUPPLIED classification; automatic metadata has OS_OBSERVATION.
No inferred accomplishments, moods, diagnoses or commitments are stored. Audio text
is empty until a separately attributed transcription feature exists. User revisions
retain an explicit `metadata.revision_of` plus new UUID; originals remain untouched.

Text hash boundary is the accepted Java Unicode string's strict UTF-8 bytes. No
Unicode normalization, newline folding, trim, reflow or summarization occurs. Invalid
surrogates and NUL are rejected. Files and audio hash their original received bytes.
Record integrity uses recursively lexicographically sorted ASCII field keys, compact
JSON, Unicode strings unescaped except JSON quote/backslash/control escapes, integer
numbers, and UTF-8. Host-Java/Python fixtures independently verify identical digests.
Hash identity does not verify reality, completeness, authorship or upstream bytes.

`TotalRecallBundle/1` wraps a `TotalRecallArchive/1` payload plus payload SHA-256. The
payload contains records with individual hashes, mutable annotations, hash-addressed
base64 attachments, tombstones, time-range deletion policy and pause exclusions.
No archive path extraction occurs; attachment identifiers must be 64 lowercase hex
characters. Display filenames are inert labels, never filesystem paths. Imports
reject unknown schemas, duplicate JSON keys/IDs, size/depth limits, broken hashes,
identity conflicts and missing/corrupt attachment bytes before replacing state.

The complete backup is wrapped by `TotalRecallSealed/1`: PBKDF2-HMAC-SHA256 with
210000 rounds and random 16-byte salt; AES-256-GCM, 12-byte IV prefixed to ciphertext,
128-bit tag appended by JCA; AAD `TotalRecall sealed v1`. Passwords encode UTF-8 in
the Java/Python implementations, including a tested non-ASCII synthetic passphrase.
Local Android ciphertext uses the same AEAD boundary with a Keystore-generated key,
not a password-derived key. Export encryption and phone storage are separate keys.

Deduplication: each intentional Save gets a new UUID even if text is identical.
A repeated import with identical ID+record hash is a no-op. Conflicting content under
an existing ID fails. Usage identity uses device+package+event type+OS millisecond
+activity class, hashed into the name-based UUID; no raw class name is persisted.
Same-millisecond identical OS instances cannot be distinguished by this public SDK
route; this is an explicit resolution limit. First-observation metadata wins on retry.
Notifications use UUIDs per callback; repeated posted callbacks may be distinct
revisions. A hashed OS identity relates revisions; active/discovery state can be
incomplete after process loss. Delivery callbacks are not claimed exactly-once.

Pause excludes incoming captures, drafts and outgoing shares/exports. Persisted
exclusion windows and per-source enable/allowlist times prevent retrospective OS
backfill. A manually restored archive does not import capture permissions. Deletion
applies to events, outbox, unreferenced blobs and derivative/temp stores. Current
policies beat old backups. A fresh device without current tombstones cannot know
what was deleted later; independent exports are beyond local erase control.

Continuity transfer stores original canonical event bytes and attachment bytes as
immutable objects, then uses the existing approved `Store.accept` path. Nonempty
text stays exact. Empty/whitespace/audio/automatic sources use a clearly labeled
metadata representation for lexical search, while original text remains in the
hashed event object. The source `origin` records event/attachment hashes and whether
indexed text is the original. Native import NEVER approves source truth/publication.
Existing scoped agent grants cannot read private captures. Original objects are
included by the existing backup path; the deletion ledger is now included too.

Outbox is a durable manual-transfer ledger: pending or exported-unacknowledged.
There is no remote delivery endpoint, credential enrollment, automatic sync, upload
backoff or server acknowledgement implemented. Paused transfer is rejected. Import
into the existing plaintext core is restricted by the supported CLI to explicitly
approved non-sensitive tests. Remote deletion is unsupported and flagged, never
silently promised. Direct ChatGPT integration and Lexidaemon setup remain incomplete.
