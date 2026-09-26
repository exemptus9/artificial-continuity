# Continuity Trust Model v0.7

Continuity separates four guarantees that software often collapses into one vague claim of being "secure":

1. **Confidentiality** — who can read the content?
2. **Integrity** — has the history been altered?
3. **Authenticity** — did this history actually come from the peer/device it claims to come from?
4. **Authority** — even if a model/device is trusted, what is it allowed to do?

## Confidentiality

- `ContinuityVault/0.1` encrypts JSON using AES-256-GCM.
- Keys are derived from a user passphrase with PBKDF2-SHA-256 (210,000 iterations) using WebCrypto.
- `EncryptedEventStore` can wrap an event store so the underlying storage contains ciphertext rather than event plaintext.
- v0.7 does not yet provide production recovery/key escrow or a polished unlock flow.
- PBKDF2 is used because it is natively available in WebCrypto; a production implementation should evaluate Argon2id on supported platforms.

## Integrity

Each event is hash-chained with SHA-256. Browser database sequence numbers are explicitly excluded from the hash base because they are storage metadata, not event semantics.

v0.7 fixes two earlier prototype defects:
- IndexedDB lacked `latest()`, which could cause a new event to point to GENESIS.
- IndexedDB sequence metadata could invalidate verification after retrieval.

Both are regression-tested.

## Authenticity

`sync/identity.js` implements experimental ECDSA P-256 device identities and signed sync bundles.

A valid signature proves possession of the corresponding private key. It does **not** by itself prove that the key belongs to a human-trusted device. Enrollment still needs an explicit trust-on-first-use or out-of-band verification flow.

## Authority

Device/model trust never implies action authority. Continuity separately classifies actions:

- Observe
- Reversible internal
- Consequential internal
- External

The Personal Constitution can further restrict these defaults.

## Core principle

> Trust is multidimensional. A component may be allowed to read a narrow context without being allowed to mutate memory; a device may be allowed to sync history without being allowed to resolve divergence; a provider may be intelligent without being sovereign.