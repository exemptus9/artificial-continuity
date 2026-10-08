# Continuity 0.21.0 — synchronization increment

The existing 0.20.1 workspace gains a scoped synchronization core and an encrypted evidence view. Existing source records, reviewed authorial text, and the 0.11 storage schema are preserved.

Implemented: durable identities and provider mappings; split lifecycle/outcome/verification/publication; encrypted atomic outbox and SHA-256 journal; existing-row Airtable adapter; uncertain-write reconciliation and independent readback; explicit read-only drift checks; preserved conflict queue; revision-bound publication grants; scoped model-neutral context; migration/rollback documentation.

The operational slice reobserved a real received-email event and updated three existing metadata/follow-up records through the authenticated host bridge. The independent provider readbacks matched all projected fields. A fresh process repeated the observation with zero external writes and unchanged encrypted journal bytes. Private provider identifiers, source content, credentials and operational journal are stored separately from this public source tree.

Local verification: 355 Node tests and 120 Python tests passed. Forty-four Node scenarios specifically cover synchronization failures, encryption, disclosures, source replacement, conflicts and idempotency. Browser/upgrade/deployment checks run in the repository release workflow; their outcome must be inspected before calling the release live.

Limits: this increment updates known destination rows; it does not remotely create records or guarantee exactly-once third-party delivery. Airtable exposes no atomic compare-and-set in this transport. Current checks are point-in-time observations. Receipts preserve integrity and host provenance, not cryptographic proof of source truth. Other provider adapters, calendar recurrence/DST synchronization, device capture, creative database recovery and account-wide unattended synchronization remain unimplemented. Conflict decisions require an operator. New evidence is encrypted at rest; existing workspace storage behavior is unchanged.

Recovery: retain the separately encrypted journal, its separately held key, and a checkpoint hash. Run the scoped bridge recovery command, query uncertain outcomes before rewriting, and import the encrypted receipt into Synchronization. Never clear browser data to obtain an app update.
