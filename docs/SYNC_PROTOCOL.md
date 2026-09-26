# Continuity Sync Protocol 0.1

Continuity synchronizes **event histories**, not mutable dashboard snapshots.

## States

Given local and remote histories:

- `EQUAL` — identical event hashes.
- `LOCAL_AHEAD` — remote history is a strict prefix of local.
- `REMOTE_AHEAD` — local history is a strict prefix of remote.
- `DIVERGED` — both contain events after the last common ancestor.

Only a strict prefix relationship may fast-forward automatically.

## Divergence rule

Divergence is preserved rather than flattened with last-write-wins.

A divergence report contains:
- common ancestor hash;
- local tail;
- remote tail;
- peer identity metadata;
- an explicit requirement for reconciliation.

This is the sync analogue of Cognitive Git.

## Transport

The core `ContinuitySync/0.1` bundle is transport-neutral. The v0.7 UI exports it inside an encrypted `ContinuityVault/0.1` envelope by default.

A future sync service can transport the same protocol over HTTP/WebSocket/object storage without changing event semantics.

## Peer authentication

`sync/identity.js` provides experimental ECDSA-P256 signed bundles. Production use still needs peer enrollment, key rotation/revocation, recovery, and secure private-key persistence.