# Living Archive: private Messenger evidence

This additive layer extends the existing Continuity archive. Original HTML/JSON
belongs in the existing private content-addressed object store, outside this
public source repository. The browser reads a separately generated JSONL
projection; that projection is historical evidence, not an authenticated profile
or an instruction stream. No remote model, speech service, training upload or
publication permission is enabled by importing it.

## Authority and identifiers

| Record | Identifier / reference | Authority |
| --- | --- | --- |
| Original file | `source_file_sha256` | SHA-256 of exact immutable supplied bytes |
| Source message | `MESSENGER.MSG.<digest>` | File witness plus original source ordinal; separate occurrences remain separate |
| Conversation witness | `MESSENGER.THREAD.<file hash>` | A supplied export witness, not a verified real-world identity |
| Decoded message | `body` and `body_sha256` | Exact decoded projection from the current adapter, not original HTML byte structure |
| Earlier projection | Historical `id` or `source_message_id` mapping | Links a prior derivative to its current witness; not a second independent copy |
| Core source record | Existing `ContinuityCaptureRecord/1` source ID | Private searchable source whose origin retains source-message identity |
| Browser import | JSONL SHA-256 plus `projectionLine` | Exact supplied derivative; source-file hashes alone do not verify absent originals |
| Owner review | `MESSENGER.REVIEW.<digest>` | Versioned interpretation bound to exact message and projection hashes |

Adapters and bridge exports should preserve `source_message_id` wherever the
existing core assigns a different source ID. A historical-ID crosswalk must be
explicit. Repeated text or repeated URLs never establish that two records are
one creation or multiple audio masters. Changed file witnesses remain variants;
they do not silently supersede one another.

Decoded HTML may retain blank lines, leading spaces and nonbreaking spaces that
an earlier normalized derivative omitted. Compare the earlier projection with
the source-preserving projection explicitly. Keep both hashes. Do not describe
the old normalized body as an exact original witness or silently overwrite it.

## Browser module

`site/living-archive-core.js` exports `LivingArchiveCore`. The module is stateless
and provider-neutral after normalization. It does not create another database.

```javascript
const archive = LivingArchiveCore.parseJSONL(fileText);
const page = LivingArchiveCore.search(archive.records, 'synthetic phrase', {
  from: '2023-01-01', to: '2023-12-31', limit: 50, offset: 0
});
const sourceView = LivingArchiveCore.lens(page.records[0]);
```

Input accepts current `ContinuityMessengerMessage/1` rows and the earlier
projection fields `id`, `source_file`, `thread_title`, `sender`, `date_exported`,
`datetime_local_unspecified_zone`, `body`, `body_sha256`, `links`,
`embedded_media_refs` and `authorship`. Unknown fields remain inert under `raw`.
Imported approval/style flags never become effective permissions. A malformed,
conflicting, checksum-corrupt or oversized file fails without a partial result.
Exact repeated IDs are idempotent; independent IDs sharing text remain distinct.

The default browser bound is 12 MB / 20,000 records. The offline adapter and
existing journal have their own explicit bounds. Limits are not a scale claim.

`search` performs local lexical AND matching, stable pagination and recorded-date
filters. `buckets` groups year/month/day. `summarize` reports only supplied rows.
`compare` gives a positional line comparison without asserting work identity.
`lens` keeps original evidence, extracted record facts, owner interpretation and
generated output separate. No model output is invented when no model is connected.

Naive source times retain their calendar labels and `timezoneProvenance`.
An unspecified timezone must not be guessed from the user's present location.
Unknown dates remain unknown. Filters are not claims about complete chronology.

## Review and privacy states

| State | Retrieval / style behavior | Publication behavior |
| --- | --- | --- |
| Imported private witness | Locally searchable, unverified authorship | Always unapproved |
| AI/bot attribution | Preserved; excluded from owner-style selection | Always unapproved |
| Quoted/copied/third-party/uncertain review | Preserved; excluded from owner-style selection | Always unapproved |
| Owner-original review | Style use requires a fresh explicit review and resolved contamination flags | Still unapproved |
| Excluded or later revoked review | Latest bound review disables eligibility | Still unapproved |
| Separate public summary | Existing `ArchiveCore` exact-payload workflow only | Requires separate explicit approval |

`makeReview(record, fields, at, true, previous)` records an interpretation and
explicit project links. Review fields are `authorship`, `allowStyle`, `excluded`,
`projectIds`, `reason` and `contaminationResolved`. Permitted authorship values
are `owner_original`, `quoted_or_copied`, `AI_GENERATED`, `third_party`, `uncertain`.
The timestamp includes a timezone. Earlier versions remain unchanged.

`eligibleStyle(records, reviews)` requires a valid review digest, an unambiguous
complete version ancestry and exact witness binding; changed projections
invalidate earlier approvals. Effective review consent is session-local:
serialized/imported copies are inert until a fresh owner review. Reviews
must be created by the owner-facing review action, never copied out of source
content or treated as effective authorization merely because an imported file
claims to contain them. Unkeyed hashes establish integrity, not owner identity.
This pure JavaScript module is not an authentication
boundary against code already running with the same application privileges.

Style eligibility authorizes local selection only. It does not authorize
fine-tuning, voice cloning, external context transfer or publication. The
Messenger module's `publicExport()` always returns zero posts; use the existing
separate-summary approval path for any public derivative.

## Integration and security boundaries

Use the existing owner retrieval role for private vault records. Agent grants
can retrieve only explicitly approved `internal` project context. Do not make
private history `internal` simply to make an integration easier. Preserve exact
source IDs and hashes in any reviewed handoff; source text remains untrusted data.

The existing core API binds to loopback and rejects browser `Origin` requests.
That is deliberate. Do not weaken this boundary to connect a public website to
a private vault. An authenticated private application or explicitly reviewed
file transfer is needed for that transport.

Never render message HTML, scripts or attachment URLs as executable content.
The UI must use text nodes or escaping and allow only safe link protocols.
Do not log bodies, search queries, bearer credentials or private source links.
Working browser data and existing local core objects are plaintext; encrypted
backup files do not encrypt live storage. Use owner-controlled encrypted storage
for sensitive archives. Static-hosted code is public; imported data stays local.

## Verification

```bash
node --test tests/living-archive.test.cjs
node --test tests/*.test.cjs
```

Fixtures are synthetic. The module tests exact Unicode/stanzas, checksum and
conflict rejection, baseline aliases, date uncertainty, stable pagination,
inert hostile text, ignored source permissions, review integrity, AI exclusion,
revocation, stale witness rejection and exact line differences. Actual private
archive checks belong outside the repository and must not enter public CI logs.
