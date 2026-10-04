# Capture and controlled context core

Status: additive implementation, not a browser upgrade, public deployment,
native capture service, or installed agent integration. Python 3.10+ standard
library; a SQLite build with FTS5 is required. Linux/POSIX is the supported
durability target. The public source tree contains only reusable code and
synthetic tests. Personal state stays outside Git source directories.

## Authority and compatibility

Use the existing `ContinuityContext/1` journal from `context_store.py` as the
project state and source-metadata authority. Preserve its baseline, event IDs,
project IDs, evidence grades, reviewed update flow and historical checkpoints.
`capture_context.py` appends capture source records through that same writer.
It does not replace or create a second project master.

Exact raw UTF-8 input lives in content-addressed objects. The journal registers
its checksum and provenance. The FTS5 database is a rebuildable projection;
it must never be edited to change canonical content. Equal bytes share an
object while independent capture occurrences retain their own IDs and metadata.

The existing browser workspace and the context journal use different formats.
Browser backups are imported as reviewed private source copies. Projects,
notes, decisions, draft actions and old consent are not executed or accepted.
The whole original export survives as an object, including unsupported data.
Recovery exports preserve unsorted drafts/incoming material in the raw archive;
this adapter does not pretend to turn those items into saved browser captures.
Encrypted/private-profile imports are refused. Export encryption is not an
encrypted working store. This local core and its backups are plaintext.

## Private initialization

Use a directory outside source repositories on an owner-controlled encrypted
disk for sensitive contents. Do not initialize from an encrypted private vault
by decrypting it into this store. Keep the old vault intact.

```sh
python3 tools/capture_context.py --root /private/continuity init --context /private/import/Context_Store.json
```

Initialization preserves the exact input journal, rather than relabeling its
baseline as independently verified fact. The original input is not modified.
An existing destination journal cannot be silently replaced.

## Capture, offline outbox, retrieval

Prepare a request in a private file. The UUID is generated once by the capture
client, before delivery, and reused after a lost response. This is synthetic:

```json
{
  "format": "ContinuityCaptureRequest/1",
  "capture_id": "9c350e0d-b906-49f2-8b32-124e4d43f42d",
  "text": "An idea, with its exact punctuation.\r\nA second line …",
  "title": "Quick note",
  "project_id": null,
  "visibility": "private",
  "source_kind": "UNKNOWN",
  "speaker": "unknown",
  "observed_at": null,
  "device_id": "optional-device-id",
  "mechanism": "manual-text",
  "source_app": "unknown",
  "source_url": "",
  "origin": {}
}
```

No project/tag assignment is needed. `project_id: null` is the unsorted inbox.
The given source class is a classification, not verification of truth or
authorship. The server's received time and user-supplied observation time are
separate. There is no implicit location or background application capture.

```sh
python3 tools/capture_context.py --root /private/continuity spool /private/request.json
python3 tools/capture_context.py --root /private/continuity flush --approve
python3 tools/capture_context.py --root /private/continuity index
python3 tools/capture_context.py --root /private/continuity search 'exact punctuation'
```

Spooling succeeds locally without a service/network. Flushing requires the
caller's deliberate authorization for these private copies. A failed flush
retains the outbox. Direct `capture REQUEST --approve` is also supported.
No inherited consent flag authorizes publication. An acknowledgement explicitly
says `journal_committed: true`, `indexed: false`; indexing failure never pretends
to undo a durable capture. Retrieval detects stale/missing/corrupt indexes and
rebuilds them. Returned excerpts are recomputed from hash-verified originals,
not trusted from the index's text. No semantic/vector retrieval is implemented.

Capture writes preserve raw input first, then append a checked journal event.
Failure before journal commit may leave an orphan object. Keep it for recovery;
do not automatically delete objects. It is not an acknowledged capture until
the journal commit succeeds. Repeated UUID + same request is idempotent;
repeated UUID + changed content or metadata is a conflict.

## Controlled API

Supply distinct tokens of at least 32 characters through a protected environment
or supervisor credential facility. Never put them in source, URLs, prompts,
command-line arguments, public test logs or a broad agent memory file.

- `CONTINUITY_OWNER_TOKEN`: local owner operations.
- `CONTINUITY_AGENT_TOKEN`: read-only, explicit project grants, internal captures.
- `CONTINUITY_CAPTURE_TOKEN`: append private unverified captures only.

```sh
python3 tools/capture_context.py --root /private/continuity serve --agent-project PROJECT_ID
```

Agent authorization expires after one hour of service uptime, measured with a
monotonic clock. `--agent-ttl-seconds` can set a lifetime from 1 to 86400 seconds.
After expiry, even a matching agent token receives HTTP 401. Renew deliberately
by restarting with fresh scoped credentials; there is no automatic renewal.
Owner/capture credentials remain process credentials and must be rotated by
their operator. Expiring the agent grant does not disable owner recovery.

The server binds only `127.0.0.1:8766`. Every request requires a bearer token and
the exact loopback Host. Requests carrying a browser Origin are rejected; no
CORS or remote exposure is enabled. Use a trusted local tool host, not a browser
page's fetch, for this API. The server never invokes shell, external URLs,
models, email, installed skills or publication tools.

| Operation | Permission | Behavior |
| --- | --- | --- |
| `POST /v1/captures` | owner/capture | `{request, authorize_private_copy:true}`; stable UUID required |
| `GET /v1/search?q=...` | owner/scoped agent | lexical search; optional project, limit, since/until received-time filters |
| `GET /v1/sources/SOURCE_ID` | owner/scoped agent | metadata + exact original; inaccessible IDs stay unavailable |
| `GET /v1/projects/PROJECT_ID` | owner/explicit project grant | scoped project record + bounded handoff |
| `GET /v1/projects/PROJECT_ID?revision=N` | same | journal replay through N, never invented pre-baseline history |
| `GET /v1/health` | authenticated | owner gets journal/outbox state; other roles get availability only |

Project grants deliberately expose that project's registered context. They do
not grant raw/private source records, related projects, arbitrary file paths,
other account data, owner credentials or writes. Capture records are readable
by an agent only when explicitly saved as `internal` and linked to a granted
project. Default/unknown source visibility is private. The append credential
cannot select internal/public visibility or claim `SOURCE_FACT`.

Use `tools/context_client.py project PROJECT_ID` or `search QUERY --project ID`
with the scoped agent environment. The client supports read operations only,
uses loopback only, refuses redirects and bounds response size. This is a
tested generic client, not an OpenClaw plugin registration. A host-side agent
wrapper must be configured for the installed runtime separately.

**Sandbox networking:** `127.0.0.1` inside a container is not the host loopback.
Use a narrowly registered trusted host tool that calls this client/API outside
the execution sandbox. Do not disable sandboxing, mount the private store into
the agent, expose the owner token, mount an engine socket, or globally enable
host execution merely to make retrieval work. If no supported host tool exists
in the installed runtime, export a scoped handoff manually until one is verified.

## Reviewed browser import

```sh
python3 tools/capture_context.py --root /private/continuity import-browser /private/export.json --project PROJECT_ID
python3 tools/capture_context.py --root /private/continuity import-browser /private/export.json --project PROJECT_ID --approve
```

The first call is a source-subset preview. This is not a full browser-schema
validator or a restore into a live browser. Sources with invalid/duplicate IDs,
missing text or mismatching supplied text checksums are rejected. Import keys
include raw export digest + original source ID + adapter version/namespace.
Every original archive survives; selected text is a derivative extraction.
Every copied source is private/unreviewed, attribution unknown by default.
Each copy is atomic; the whole import is **resumable, not all-or-nothing**. Repeat
the same archive and project after failure. Changed project/content with reused
UUIDs is a conflict. No source is automatically deleted or merged by similarity.

## Backup and recovery

Pause outbox producers. Write a new checkpoint directory on another medium:

```sh
python3 tools/capture_context.py --root /private/continuity backup /backup/continuity/checkpoint-001
python3 tools/capture_context.py --root /private/continuity verify-backup /backup/continuity/checkpoint-001
```

The manifest covers journal, original objects, history, intake witness and
pending outbox. The index is omitted because it can be recreated. The context
journal is locked while the snapshot is written; outbox producers have a
separate writer, so their snapshot is not globally atomic. Pause them and
compare counts. The backup is plaintext and must be encrypted before off-site
upload. Copying to a second folder on the same disk is not off-site recovery.

Restore into a separate private directory, verify the manifest, run `index`,
retrieve a known source and compare its exact text/checksum and the journal
head. Test a pending outbox without ingesting it until deliberately approved.
Only then choose the restored directory as the service's working root. Never
overwrite a newer working journal automatically. Retain divergent roots and
their proposals for reconciliation.

If an index fails, keep originals and rebuild. If an original fails its hash,
stop that retrieval and restore from a verified checkpoint. If a capture write
fails, keep/retry its envelope. If an imported model write is wrong, append a
source-backed correction through the existing checked journal writer; do not
edit history. A hash chain is not a digital signature or protection against a
privileged attacker who can rewrite everything. OS encryption/access control,
off-site backups and a later external anchor are separate controls.

## Limits and tests

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

The integration tests exercise real files, SQLite FTS5, actual loopback HTTP,
scoped credentials, failure injection and separate-directory restore. Fixtures
are synthetic. The retained existing project journal tests remain applicable.
No physical Android/mic/Side-button behavior, native interception, genuine
OpenClaw invocation, end-to-end encryption, automatic sync, automatic backup
scheduler, arbitrary binary ingestion, vector search, publication approval
service or redaction/cryptographic erasure is implemented in this slice.

Current limits: 32 MB project journal, 1 MB text capture, 2 MB API body, at most
32 lexical terms, 100 results, 12,000-character handoff and 128 KB client response.
Source metadata is rescanned/replayed for reads; complexity grows with history.
Measure before migrating the journal to transactional SQLite event storage.
An unaudited threaded development HTTP server is unsuitable for Internet
exposure; place remote access behind a separately reviewed authenticated proxy.
