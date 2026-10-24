# Optional sealed archive export

`tools/seal_messenger_backup.py` encrypts a supplied portable ZIP or other file.
It does not modify the archive's working journal, hosted application, database,
source originals or existing backup. The optional dependency is `cryptography`
(tested with version 50.0.2). The ordinary archive tools remain standard-library
only; encryption tests skip explicitly when this dependency is absent.

The format uses the library's AES-256-GCM implementation, a randomly generated
256-bit key and a fresh 96-bit nonce per encryption. No homegrown cipher,
password-derived key, external model, network service or telemetry is used.

## Create an encrypted packet

Use separate private directories for the packet and recovery material. Compute
the original's SHA-256 before sealing so a changed source is rejected. All paths
below are illustrative; replace them with your own absolute paths.

```bash
python3 -m pip install cryptography
sha256sum /private/staging/Example_Archive.zip
python3 tools/seal_messenger_backup.py seal --input /private/staging/Example_Archive.zip --output /private/packets/Example_Archive.sealed.json --new-key-file /private/recovery/Example_Recovery_Key.json --expect-sha256 ORIGINAL_SHA256
```

The recovery key is generated in a new file with mode `0600`; its bytes are
never printed. Existing keys may be supplied through `--key-file` instead of
`--new-key-file`. Key material is never accepted through argv, environment
variables or passwords. Key files must be regular, owner-private files with no
group/other access; symlinks and paths traversing symlinks are refused.

Keep the recovery key **outside the ZIP and encrypted packet**, and deliver it
separately. Anyone holding both the key and packet can decrypt the complete
archive. Losing the key prevents recovery. Keep a secure separate copy of the
key; a file hash is not a replacement for it.

## Verify and recover

Decrypt into a new destination, then compare SHA-256 with the original. The
tool verifies GCM authentication, exact plaintext size and the authenticated
SHA-256 before creating any plaintext output.

```bash
python3 tools/seal_messenger_backup.py open --input /private/packets/Example_Archive.sealed.json --key-file /private/recovery/Example_Recovery_Key.json --output /private/recovered/Example_Archive.zip
sha256sum /private/recovered/Example_Archive.zip
```

Existing destinations, dangling symlinks and overwrite attempts are refused.
Writes use a private temporary file and an atomic no-overwrite link. A failed
authentication, wrong key, modified ciphertext, changed metadata or mismatched
hash produces a generic failure and no recovered file. If a write fails after
key generation, a new recovery key file may remain; no successful encryption is
reported until the sealed output is written and hashed.

The separately delivered offline recovery page uses WebCrypto with the same
format. It accepts a sealed packet plus its separate key and produces a local
ZIP download only after authentication and hash verification. The page has no
network dependencies. Browser/file-download acceptance must be tested in the
user's browser; Node WebCrypto interoperability alone does not establish that
browser workflow.

## Format and limits

`ContinuitySealedMessengerBackup/1` stores the algorithm, version, random key ID,
plaintext byte count and plaintext SHA-256 as authenticated metadata. Their
canonical JSON serialization is sorted by key, without whitespace, and serves
as GCM additional authenticated data. The envelope also stores a base64 nonce
and base64 ciphertext with its appended 128-bit GCM tag.

`ContinuityMessengerBackupRecoveryKey/1` is a separate JSON key file containing
the algorithm, matching key ID and base64 key. Never publish or commit this file.
The envelope reveals the packet's size and hash; its contents remain encrypted.

The source limit is **64 MiB**. The JSON envelope is bounded to the corresponding
base64 size plus metadata. The implementation holds bounded inputs in memory;
it is not a streaming-encryption system. Keys and plaintext necessarily exist
temporarily in the recovery process's memory. This feature provides encrypted
exports only; it makes no claim that a live service, working store, plaintext
staging file, recovered ZIP or browser memory is encrypted at rest.

## Checks

```bash
python3 -m unittest discover -s tests -p test_sealed_messenger_backup.py -v
```

Synthetic tests cover exact recovery, key permissions, fresh nonces, incorrect
source hashes, wrong keys, ciphertext/nonce/metadata tampering, source bounds,
noncanonical encodings, no-overwrite behavior, symlinks, generic errors, absence
of key logging and graceful optional-dependency unavailability. Private archives
and recovery keys do not belong in source repositories or public CI fixtures.
