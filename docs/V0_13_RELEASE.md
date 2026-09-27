# Continuity v0.13.0 — bring your history with you

Builds on the verified v0.12 main commit `2c8b8567dddcbb8c5d0aa616b2b3c34a16a2c5e5`. Application version is 0.13.0; the additive storage schema remains 0.11.0. Nothing resets browser records.

## Operational additions

**Selected conversation import.** Sources → Import chat export accepts an extracted JSON array (or an object with a conversations array) containing mapping/current_node records. Maximum 20 MB input, 10,000 conversation records, 100,000 message nodes in total; per-conversation limits are 20,000 nodes and 200 branch choices. Filter titles, preview a branch, explicitly select up to 20 conversations, then save up to 4 MB of rendered transcript text (1 MB per conversation). A 15 MB resulting-workspace check keeps imports within the existing portable-backup limit. Nothing is imported just by opening the file. Choose unlinked sources, one existing intention, or a separate intention per conversation. Exact duplicate transcript text is skipped without extra projects. Import remains transactional with the existing optimistic revision check.

**Branch and speaker provenance.** Traversal follows parent pointers from the selected tip, not timestamps. The current export path is suggested when supplied. Ambiguous branches require a selection. Cycles and missing parents are rejected. Visible user/assistant text is rendered with role headers and available timestamps; line endings are normalized. System, tool, hidden and non-final analysis messages are excluded. Non-text parts produce visible omission markers. Counts of omissions and off-branch nodes are shown. Original JSON/media remain in the user's export, not in this text source. Message ranges and role metadata survive backup and project transfer. Role-like text inside an assistant message cannot relabel it as a user decision. Quoted text remains evidence, never code or a system instruction. No accepted checkpoints are created by import.

**Project dossier.** Click an intention title to view its objective, next action, last position, sources (including ones referenced by checkpoints), confirmed and pending checkpoints, and follow-ups together. Source filtering is project-scoped. Existing editing, attention, source review, Resume Pack and transfer controls remain accessible.

**Password-protected backup files.** Backup & history → Create encrypted backup uses browser Web Crypto: AES-256-GCM, a fresh random 96-bit IV and 128-bit salt, a 128-bit authentication tag, and PBKDF2-HMAC-SHA256 with 600,000 iterations. Keys are non-extractable. Format and parameter fields are authenticated as additional data. Fixed supported KDF parameters and strict file/base64/length checks prevent attacker-controlled extreme work factors. Maximum 16 MB plaintext, 24 MB envelope. Passphrases must be 12–1,024 characters; use a strong unique one. Passphrase confirmation precedes export. Password fields are not registered with draft persistence and are cleared after operations. JavaScript cannot guarantee secure erasure of memory.

**Restore boundary.** Decryption never changes the workspace. Decrypted content must pass workspace validation, then counts are previewed, then replacement requires confirmation. A pre-import recovery copy remains in IndexedDB. Wrong passphrases and changed ciphertext fail authentication. Existing plaintext backups still work.

## Important boundaries

The working database, drafts and pre-import recovery copies remain unencrypted. This release encrypts FILE EXPORTS, not the open app or an unlocked session. It does not protect against a compromised browser, malicious code served from the app origin, weak passphrases or someone accessing an unlocked device. Forgotten passphrases cannot be reset. This is new, unaudited security-sensitive software; keep a separately stored known-good recovery option until a restore has been checked. No claim of FIPS certification, authenticity of the original author, or enterprise-vault security is made.

No remote AI, automatic cloud sync, background reminder service, direct ChatGPT sidebar access, ZIP extraction, PDF parser or media import is added. Import compatibility is tested against synthetic mapping/current_node fixtures, not an exhaustive set of real account exports. Unknown variants are rejected rather than guessed. Export structure is not treated as a provider-guaranteed API.

## Verification

29 new Node data/security tests cover parent-chain selection, omissions, attribution, duplicate/atomic import, supported shapes and limits, metadata round trips, project scoping, cryptographic round trips, wrong passwords, tampering, KDF bounds and file limits. Earlier data suites remain in the gate.

The new native-Chromium suite tests import selection/preview, alternative branches, committed records, reloads, duplicates, dossier filtering, provenance display, passphrase confirmation, actual Web Crypto file downloads, password non-persistence, wrong-password rejection, decrypt-only preview, cancellation, confirmed replacement/recovery, tampered ciphertext, invalid files, offline loading and 390px layout. Existing browser suites remain mandatory. The workflow must pass every suite before Pages deployment.

Local DOM/screenshot checks used injected source and an in-memory fixture because this container blocks browser URL navigation. They are not represented as native IndexedDB or live-site tests. GitHub Actions is the native-browser release gate; inspect the successful run before calling this deployed.

## Technical references

- OpenAI export guidance: https://help.openai.com/en/articles/7260999-exporting-your-chatgpt-history-and-data
- Web Crypto password-derived AES keys: https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/deriveKey
- PBKDF2 work-factor guidance: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html

The OWASP work factor informs the KDF choice; it does not certify this backup design or its implementation.
