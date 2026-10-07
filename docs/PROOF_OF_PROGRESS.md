# Structured proof of progress

`tools/proof_of_progress.py` produces private and public-safe Markdown from a reviewed evidence snapshot. It runs locally with the Python standard library. It neither publishes nor schedules anything.

Each `ContinuityEvidence/1` event carries a stable ID, project ID, timestamp, category, verification status, summary, scope, source paths and SHA-256 hashes. Changed or missing source bytes fail validation. Repeated event IDs are rejected; superseded events are omitted from current views.

Public output requires a separate `ContinuityPublicPolicy/1` mapping of event IDs to exact event hashes and selected metric names. Imported fields cannot authorize export. Public output consists only of built-in text templates and bounded integer values from verified software, recovery or creative events. Names, correspondence, private source paths, free-text summaries, housing, health, legal and financial events are never rendered into that view. A changed approved event requires a new reviewed policy hash.

```sh
python3 tools/proof_of_progress.py --evidence /private/evidence.json --policy /private/public-policy.json --source-root /private --out /private/reports
```

Keep evidence and policy outside the public source repository. A connector may refresh a local snapshot explicitly; this tool has no account token, scheduled job, cloud synchronization or implicit publication approval. The generated receipt records input, policy and output hashes.

The public export is intentionally limited to exact tested templates. New templates require code review and privacy tests. Numeric publication remains an explicit curation decision and does not prove a physical-device test, a published book or a production deployment.
