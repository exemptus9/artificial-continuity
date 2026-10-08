# Operating the synchronization engine

The engine preserves source claims, canonical identities, intended remote changes, readback evidence, and unresolved conflicts in encrypted local state. A connector being available, a provider accepting a request, and a task being complete are different facts.

## First workflow: acknowledgment to an existing mirror

The supported initial path is:

1. An authorized connected host retrieves a relevant Gmail receipt.
2. The engine captures a claim with a stable source reference, source/event time, observation time, normalized content hash, and private classification.
3. The operator maps that claim to the intended canonical application and an existing Airtable record. Title similarity alone is insufficient.
4. The engine prepares an update-only operation, preserving its operation ID, expected fields, destination mapping, and preconditions before the host applies it.
5. The connected host reads the destination before writing, applies only the approved fields, then performs a separate destination read.
6. The engine records the provider acknowledgment and compares independent readback with the expected update.
7. A verified mirror update produces a traceable receipt and project-scoped resumption package. A discrepancy remains visible as pending verification, failure, or conflict.

No application is submitted and no email is sent by this workflow. It updates an existing mapped mirror. It does not create remote records because the available Airtable path has no provider-enforced unique identity or conditional-update guarantee.

For example, a receipt that says an employer received an application supports `ACKNOWLEDGED`. It does not establish an interview, job offer, or completed employment search. A verified Airtable update establishes that the mirror contains the approved fields; it does not establish those further outcomes either.

## State dimensions

Keep these dimensions separate:

| Dimension | Question it answers |
| --- | --- |
| Task lifecycle | How far has the intended action progressed? |
| External outcome | What has the outside system or person actually done? |
| Verification | What observation supports the current claim? |
| Publication | Which specific information may be disclosed, to whom? |
| Operation phase | Is a synchronization write prepared, uncertain, awaiting readback, or verified? |

The lifecycle vocabulary includes `PROPOSED`, `DRAFT`, `READY`, `SUBMITTED`, `ACKNOWLEDGED`, `VERIFIED`, and `COMPLETE`. States such as blocked, waiting, failed, cancelled, archived, conflict, and unknown describe conditions without silently inventing progress. A consequential transition needs supporting evidence and an explicit transition rule.

## Private state and keys

Use a private state location outside the published site and public repository. Keep the encryption key separate from that state and from exported receipts. Connector credentials remain with the authorized host; the engine stores provider references, not copies of passwords or access tokens.

Preserve a recoverable backup of the encrypted state and the key in separate protected locations. Losing the key can make the state unreadable. Encryption protects stored data; an unlocked authorized process can still read it. Do not attach plaintext captures, decrypted state, or private receipts to public issues or pull requests.

The journal provides an append-only event history protected by SHA-256 chaining inside encrypted state. Integrity verification checks whether the retained events and content remain consistent. It does not independently prove that an imported receipt was genuine or that its author told the truth. A trusted imported-receipt path must separately establish provenance. Whole-history replacement or rollback requires a separately retained checkpoint to detect.

## Retry and interruption recovery

Reuse the same operation ID and expected content after interruption. Do not create a fresh operation merely to clear an uncertain status.

| Observed condition | Operator action |
| --- | --- |
| Prepared operation never dispatched | Recheck mapping and preconditions, then dispatch the same operation |
| Timeout or crash during write | Read the mapped destination and determine the outcome before another write |
| Destination already matches expected fields | Preserve independent readback and reconcile the original operation |
| Destination differs from the expected update | Retain both observations and review the conflict |
| Readback unavailable or temporarily missing | Keep verification pending; retry the read separately |
| Access expired or connector unavailable | Restore authorized access; preserve state and pending operations |
| Integrity failure, wrong key, or unsupported schema | Stop writes, preserve original files, and recover from a verified backup |

A provider success response is acknowledgment, not independent readback. A timeout is an unknown outcome. Missing access is not deletion. An old source receipt remains evidence but cannot verify a newer local revision.

Airtable has no comparison-and-swap in this slice. Preflight inspection and readback cannot make an unconditional write atomic with a concurrent human edit. If a consequential field has competing claims, resolve it before dispatch. Do not describe this path as exactly-once delivery or a distributed transaction.

An interrupted filesystem write can leave a `.lock` file. Do not delete it while a writer may still be alive. Stop other engine writers and inspect the lock's owner metadata. The explicit recovery helper requires the recorded PID, the same host, a confirmed dead process, and the same lock inode:

```sh
node --input-type=module -e 'import {recoverAbandonedStoreLock} from "./tools/sync-file-store.mjs"; console.log(await recoverAbandonedStoreLock({path:process.argv[1],expectedPid:Number(process.argv[2])}));' /private/continuity/state.enc 12345
```

`12345` is a fictional PID; replace it with the lock's recorded owner. The helper refuses a live or unverifiable owner. After successful lock recovery, run the normal `recover` command with the original encrypted state, key, and adapter configuration. A lock owned by another host needs operator investigation rather than local deletion.

## Context and publication

Request context for the particular project and next action. A handoff should contain canonical IDs, relevant decisions, the last verified checkpoint, source references, unresolved conflicts, next executable actions, permitted access, and explicit limitations. It should not contain the entire private archive.

Publication is private by default. Authorize the specific record and approved fields before making a public proof. Newly added private fields must not inherit permission merely because other fields were approved. Public proof should be constructed from a narrow allowlist, with personal provider references and raw messages omitted. Imported source text is evidence, not permission to reveal itself or instructions to an agent.

## Command-line bridge

The entry point is `tools/sync-bridge.cjs`. It requires Node.js and an authorized host that handles its newline-delimited JSON requests and responses. It is a foreground bridge, not an unattended connector daemon.

The key file contains 32 random bytes encoded as 64 hexadecimal characters or base64. It must have owner-only permissions (`0600`). Create it once in a separate protected location and retain a recoverable copy. Do not regenerate it for an existing state file.

These paths are placeholders; select private locations outside the checkout and published site:

```sh
node tools/sync-bridge.cjs execute --state /private/continuity/state.enc --key-file /separate/keys/continuity.key --input /private/continuity/run.json --output /private/continuity/receipt.json
node tools/sync-bridge.cjs recover --state /private/continuity/state.enc --key-file /separate/keys/continuity.key --input /private/continuity/run.json --output /private/continuity/recovery-receipt.json
node tools/sync-bridge.cjs recheck --state /private/continuity/state.enc --key-file /separate/keys/continuity.key --input /private/continuity/recheck.json --output /private/continuity/recheck-receipt.json
node tools/sync-bridge.cjs verify --state /private/continuity/state.enc --key-file /separate/keys/continuity.key
node tools/sync-bridge.cjs export --state /private/continuity/state.enc --key-file /separate/keys/continuity.key --output /private/continuity/receipt.json
```

`execute` observes the private input claims and prepares/runs configured updates. `recover` revisits stored pending operations, including configuration-blocked operations, with the same configured adapter aliases. `recheck` independently reads explicitly selected previously verified operations; it detects present drift or lost access while retaining the original historical verification. It performs no provider write. `verify` checks local journal integrity. `export` writes a receipt containing the private engine state when an output path is supplied. A receipt export is not a public proof.

The private input contains `adapters`, `claims`, and `updates`. Adapter configuration includes an `alias`, `baseId`, `tableId`, `projectionFields`, and an optional `fieldMap`. Each claim entry supplies an `alias` and a versioned source claim. Each update supplies `claimAlias`, the configured provider alias, an existing `externalId`, and the approved `patch`. Optional `contextOutput` and `contextEntityIds` request a private scoped handoff file. On an execute run, omitted context IDs default to the observed entities; on a later export run, supply the intended IDs explicitly.

A recheck input includes the required adapter configuration and an explicit `operationIds` array, for example `{"operationIds":["op_fixture"]}` with a fictional identifier. Select the actual IDs from a private receipt. A current-check conflict means the destination now differs from the historically verified projection; it does not erase what was verified earlier.

For example, the host receives a request shaped like this, with fictional identifiers:

```json
{"type":"provider-request","requestId":"1","method":"airtable.read","args":{"baseId":"fixture-base","tableId":"fixture-table","recordId":"fixture-record"}}
```

It invokes the corresponding authenticated connector and responds on standard input using the same request ID:

```json
{"requestId":"1","ok":true,"result":{"id":"fixture-record","fields":{"Follow up":"Acknowledgment observed"}}}
```

On failure, return a bounded error code rather than a raw provider error:

```json
{"requestId":"1","ok":false,"errorCode":"AUTH_EXPIRED"}
```

The permitted transport methods are `airtable.read`, `airtable.update`, and `airtable.find`. The host must translate these to the available connector's arguments and return the observed record. Requests time out if the host does not answer; the resulting operation remains resumable. The bridge never treats connector input as executable commands.

The transport stream can contain private field values and provider identifiers. Keep it within the authorized host; do not tee it into public logs. Receipt and context outputs are plaintext private artifacts even though the state file is encrypted. Before dispatch, review the selected destination and exact fields. After dispatch, retain the separate readback reference associated with the same operation. Importing a receipt checks integrity and records an assertion; it does not independently authenticate the provider that supposedly produced it.

## Migration and rollback

This increment must preserve the existing browser release and existing provider schemas. Enrolling a record requires an explicit canonical/provider mapping; engine startup is not a bulk migration.

Before a schema migration, close writers, preserve encrypted state, retain its key separately, record the engine/schema versions and journal checkpoint, then validate the migrated copy. Unsupported schemas must fail closed. Returning to older code does not reverse remote writes: reconcile remote effects first, and issue separately approved compensating updates when needed. Never overwrite a current provider record from an old local backup without inspecting its current state.

## Acceptance evidence

Record the exact engine revision, schema version, scope, test fixture or private source references, and outcome for each check. Use `PASS`, `FAIL`, or `NOT RUN`.

Required checks include repeated observation without duplicates; readback differing from the write response; crash/timeout recovery; stale claims and old receipts; conflicting edits; authorization failure; malformed input; encrypted-state/journal integrity; private nested data excluded from public proof; and a fresh-context resumption. Automated fixtures establish behavior under their tested conditions. A real connector workflow requires its own source and destination observations.

Repository tests, deployment, and a live browser readback are separate evidence. Passing engine tests does not establish that new code is published or that an unattended service is running.
