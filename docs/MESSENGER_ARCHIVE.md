# Private Messenger ingestion

`tools/messenger_archive.py` adds Messenger HTML/JSON witnesses to the existing
`ContinuityContext/1` journal and immutable object directory used by
`capture_context.py`. It does not create a second project-state database. The
existing `search.sqlite` is disposable FTS5 search state; rebuild it from the
journal and checked objects.

No network, model calls, training, publication, HTML execution or media fetching
occurs. Sender labels are unverified export attributions. Every imported body
starts unreviewed and ineligible for style use. Heuristic AI/quote flags are
review cues, not an authorship classifier. Source assertions do not become
current user facts or accepted project decisions.

## Import and retrieval

Start with an existing private context journal. If migrating it into a new
working directory, preserve the original with the existing initializer:

```bash
python3 tools/capture_context.py --root /private/continuity init --context /private/context.json
python3 tools/messenger_archive.py preview /private/message.html --self-sender 'Example Owner'
python3 tools/messenger_archive.py import --root /private/continuity /private/message.html --self-sender 'Example Owner' --approve-private
python3 tools/messenger_archive.py verify --root /private/continuity
python3 tools/messenger_archive.py export-jsonl --root /private/continuity --output /private/messages.jsonl
```

Use the existing gateway's owner retrieval interface or `Store.search()` for
full-text results, then `messenger_archive.lineage()` for checked source hash,
original occurrence, attribution, timestamp and body. Owner credentials can
retrieve private messages; existing scoped agent credentials cannot. A separate
reviewed app integration must deliberately grant any additional retrieval.

The JSONL output contains exact decoded projections and remains private. Raw
HTML/JSON bytes are stored separately under their SHA-256. Never load raw HTML
through `innerHTML`; display decoded bodies with text nodes. Attachment URIs,
including dangerous or missing references, are inert evidence, never clickable
authorization to fetch or execute them.

## What is preserved

- Original bytes, encoding and source hash. The original controls any ambiguity.
- File-scoped occurrence IDs; identical messages and independent re-exports are
  separate witnesses. Reimporting identical bytes with the same configuration
  is idempotent. Different sender/project configuration cannot overwrite an
  existing witness silently.
- HTML entities decoded; `br` preserved as LF; literal spaces and blank lines
  retained. Adjacent block elements receive a documented LF projection. Inline
  anchors do not synthesize line breaks. Old normalized derivatives remain
  separate historical objects.
- Sender/date export strings, attachments, reactions and available JSON reply
  relationships. HTML reply targets unavailable in the supplied layout remain
  unknown. Media availability is unknown until separately verified.
- HTML timestamps remain local with unknown timezone. An export-generation
  offset does not establish every message's timezone. JSON `timestamp_ms` is
  interpreted as the recorded UTC Unix instant, with invalid values flagged.
- Exporter metadata blocks counted separately; platform AI disclaimers and
  identifiable bot senders remain in the archive, outside authentic-style use.

## Recovery and rollback

Use `Store.backup()` and `Store.verify_backup()` from `capture_context.py`.
Verification checks journal history and every retained object's digest. Restore
to a separate private directory: initialize from the checked backup journal,
copy every checked object with `Store.put_file(expected=...)`, verify imports,
then rebuild FTS. Keep the original working directory until comparison passes.

`rollback-latest` accepts the latest batch ID and exact current journal hash.
It refuses intervening writes, retains the before-rollback journal under
`history/`, retains original objects, and discards the stale search index. It
does not delete historical evidence. Earlier-batch removal needs explicit
reconciliation or a verified checkpoint restore; it cannot silently erase
later work.

```bash
python3 tools/messenger_archive.py rollback-latest --root /private/continuity BATCH_ID --expected-head CURRENT_STORE_SHA256 --approve
python3 -m unittest discover -s tests -p test_messenger_archive.py -v
```

Working objects, journals, JSONL and backups are plaintext files with owner-only
permissions. Use an encrypted disk/container for encryption at rest. Keep private
directories outside all source repositories; the existing store rejects any
directory beneath a `.git` ancestor. Do not publish them or include them in CI.

The parser reads files up to 64 MB and at most 100,000 entries, but the existing
journal enforces its 32 MB capacity before committing. These are safety limits,
not a demonstrated scale claim. No approximate/semantic search, automatic
cross-export identity merging, external speech processing, style approval or
fine-tuning is implemented by this adapter.
