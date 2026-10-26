# Project Constellation: release evidence in the existing journal

Project Constellation extends `tools/context_store.py`; it creates no new server,
database, account or task authority. Existing project IDs and task `state` remain
unchanged. The optional `delivery` field records an independent release state.
Unknown or historical records never become tested or live merely by exporting.

The existing init, validate, apply, context and export commands also validate and
project this field. Export adds `Project_Registry.md` and a versioned private
`ConstellationRegistry/1` JSON projection. A scoped `STATE.md` exposes the exact
release evidence, revision, environment and limitations and resolves source IDs.
Use these files with the existing private source-intake workflow; they are not
native browser backups. There is no automatic Airtable or browser synchronization.

## Release contract

```json
{
  "format": "ConstellationRelease/1",
  "status": "TESTED",
  "revision": "actual-commit-or-artifact-sha256",
  "environment": "exact-tested-environment",
  "evidence": [{
    "source_id": "REGISTERED-SOURCE-ID",
    "revision": "actual-commit-or-artifact-sha256",
    "environment": "exact-tested-environment",
    "check": "capture-reopen-export",
    "method": "test",
    "result": "pass",
    "checked_at": "2026-01-02T01:00:00Z"
  }],
  "limitations": ["Record all relevant unverified acceptance here."]
}
```

The illustrative identifiers above are not real test evidence. Add actual source
records through the existing reviewed proposal contract, then apply against the
current store hash. New evidence and a matching status may be in one event.
Ordinary journal idempotency, stale-head protection and history preservation apply.

Accepted states: DISCOVERED, UNVERIFIED, IMPLEMENTED, TESTED, RELEASE-CANDIDATE,
DEPLOYED-UNVERIFIED, LIVE-VERIFIED, BLOCKED, ARCHIVED, SUPERSEDED.

TESTED and RELEASE-CANDIDATE require a passing test or journey receipt for the
exact revision and environment. DEPLOYED-UNVERIFIED requires a deployment receipt.
LIVE-VERIFIED requires a live-journey or device-journey receipt; a successful build
or deployment cannot satisfy it. Emulator and physical-phone environments must
remain distinct. Use separate source records and explicitly scoped observations.
The tool validates consistency, not the truth, completeness or freshness of a
receipt. A caller can lie; recorded evidence must still be inspected. These
hash-linked records are not signatures or an independent security certification.

For each qualifying method and stable `check` ID, the latest timestamp controls
the recorded outcome. A newer failure or unrun check invalidates an older pass;
an unrelated passing check cannot hide it. Contradictory results at one timestamp
require resolution. Preserve failures, then add a newer receipt after a repair.

An optional registry inventory also accepts repository, branch, framework,
runtime, authentication, privacy_scope, canonical_data_source, operating_cost,
publication_authority and the string arrays locations, data_stores,
available_tests and release_artifacts. Unknown values should say unknown rather
than guessing. These pointers confer no access or publication permission.

## Authority and privacy

The source journal owns this operational projection. Domain applications own
their business data, literary authorities own canonical text, providers own
their live deployment state, and original receipts retain their own limitations.
Do not copy raw private content into a registry to demonstrate integration.

All generated views are PRIVATE, even if they link public projects. No public
projection or implicit redaction is supplied. Keep real registries outside the
public repository and outside static deployment directories. The exporter does
not fetch URLs, run captured commands, send telemetry or create a background job.
Export files retain the existing owner-only file mode; filesystem parent access
and encrypted storage require the owner's separate setup.

Recovery: copy the journal to a fresh private destination, validate it, export
views there, and retrieve one known project. Preserve the source witness and
immutable journal history. Never restore a derived registry over a browser vault.

## Verification

`python3 -m unittest discover -s tests -p 'test_context_store.py'`

Synthetic regressions exercise exact-revision/environment guards, deployment vs
live distinctions, source resolution, failure/not-run refusal, atomic evidence
registration, reload/export/restore/idempotent retry and legacy compatibility.
